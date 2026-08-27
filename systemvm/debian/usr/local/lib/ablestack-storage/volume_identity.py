#!/usr/bin/env python3

"""Stable Storage Service file-share volume identity helpers."""

import glob
import os


class VolumeIdentityError(RuntimeError):
    pass


def compact(value):
    return "".join(ch for ch in str(value or "").lower() if ch.isalnum())


def token_matches(value, tokens):
    candidate = compact(value)
    if not candidate:
        return False
    return any(len(token) >= 8 and (token in candidate or candidate in token) for token in tokens)


def flatten_devices(devices, parent_disk=None):
    flattened = []
    for source in devices or []:
        dev = dict(source)
        disk = dev if dev.get("type") == "disk" else parent_disk
        dev["_parentDisk"] = disk
        flattened.append(dev)
        flattened.extend(flatten_devices(dev.get("children") or [], disk))
    return flattened


def flatten_mounts(filesystems):
    """Flatten findmnt JSON while preserving every nested mount exactly once."""
    flattened = []
    for source in filesystems or []:
        mount = dict(source)
        children = mount.pop("children", None) or []
        flattened.append(mount)
        flattened.extend(flatten_mounts(children))
    return flattened


def root_disk_names(devices):
    names = set()
    for dev in devices:
        disk = dev.get("_parentDisk") or dev
        if dev.get("mountpoint") in ("/", "/boot", "/boot/efi") or str(dev.get("fstype") or "").lower() == "swap":
            if disk.get("name"):
                names.add(disk.get("name"))
    return names


def device_for_path(devices, path):
    if not path:
        return None
    real = os.path.realpath(path)
    for dev in devices:
        dev_path = dev.get("path")
        if dev_path and os.path.realpath(dev_path) == real:
            return dev
    return None


def has_mounted_or_formatted_child(devices, candidate):
    path = candidate.get("path")
    if not path:
        return False
    for dev in devices:
        parent = dev.get("_parentDisk")
        if parent and parent.get("path") == path and dev.get("path") != path:
            if dev.get("mountpoint") or dev.get("fstype"):
                return True
    return False


def safe_candidate(devices, dev, roots, expected_mount_source=None, blank_only=False):
    if not dev or not dev.get("path") or dev.get("type") not in ("disk", "part"):
        return False
    disk = dev.get("_parentDisk") or dev
    if disk.get("name") in roots:
        return False
    mountpoint = dev.get("mountpoint")
    if mountpoint:
        if not expected_mount_source or os.path.realpath(dev.get("path")) != os.path.realpath(expected_mount_source):
            return False
    if blank_only:
        return (dev.get("type") == "disk" and not dev.get("fstype") and not mountpoint
                and not has_mounted_or_formatted_child(devices, dev))
    return True


def by_id_candidates(devices, tokens, roots, expected_mount_source=None):
    matches = []
    for link in glob.glob("/dev/disk/by-id/*"):
        if not token_matches(os.path.basename(link), tokens):
            continue
        dev = device_for_path(devices, link)
        if safe_candidate(devices, dev, roots, expected_mount_source):
            matches.append(dev)
    return matches


def unique_candidate(candidates, reason):
    unique = {}
    for dev in candidates:
        path = os.path.realpath(dev.get("path")) if dev and dev.get("path") else ""
        if path:
            unique[path] = dev
    if len(unique) > 1:
        raise VolumeIdentityError(
            "Ambiguous Storage Service volume mapping for %s: %s" %
            (reason, ", ".join(sorted(unique))))
    return next(iter(unique.values())) if unique else None


def size_matches(dev, expected_size):
    try:
        actual = int(dev.get("size") or 0)
        expected = int(expected_size or 0)
    except (TypeError, ValueError):
        return False
    if actual <= 0 or expected <= 0:
        return False
    tolerance = max(1024 * 1024, expected // 100)
    return abs(actual - expected) <= tolerance


def resolution(dev, matched_by):
    disk = dev.get("_parentDisk") or dev
    disk_path = disk.get("path")
    if not disk_path and disk.get("name"):
        disk_path = "/dev/" + str(disk.get("name"))
    return {
        "devicePath": dev.get("path"),
        "diskPath": disk_path or dev.get("path"),
        "diskName": disk.get("name"),
        "filesystem": dev.get("fstype"),
        "filesystemUuid": dev.get("uuid"),
        "serial": dev.get("serial") or disk.get("serial"),
        "matchedBy": matched_by,
    }


def resolve_volume_device(blockdevices, volume_uuid=None, volume_name=None,
                          filesystem_uuid=None, mount_source=None,
                          expected_size=0, allow_blank_size_fallback=False):
    """Resolve one volume without trusting a historical /dev path."""
    devices = flatten_devices(blockdevices)
    roots = root_disk_names(devices)
    identity_tokens = [compact(item) for item in (volume_uuid, volume_name) if compact(item)]

    stable = by_id_candidates(devices, identity_tokens, roots, mount_source)
    stable.extend(
        dev for dev in devices
        if safe_candidate(devices, dev, roots, mount_source)
        and token_matches(dev.get("serial"), identity_tokens)
    )
    matched = unique_candidate(stable, "ABLESTACK volume UUID/serial")
    if matched:
        return resolution(matched, "VOLUME_SERIAL")

    fs_token = compact(filesystem_uuid)
    if fs_token:
        fs_matches = [
            dev for dev in devices
            if safe_candidate(devices, dev, roots, mount_source)
            and compact(dev.get("uuid")) == fs_token
        ]
        matched = unique_candidate(fs_matches, "filesystem UUID")
        if matched:
            return resolution(matched, "FILESYSTEM_UUID")

    if mount_source:
        mounted = device_for_path(devices, mount_source)
        if safe_candidate(devices, mounted, roots, mount_source):
            if identity_tokens and not token_matches(mounted.get("serial"), identity_tokens):
                raise VolumeIdentityError(
                    "Mounted source does not match the expected ABLESTACK volume identity: %s" % mount_source)
            return resolution(mounted, "MOUNT_SOURCE")

    if allow_blank_size_fallback:
        blanks = [
            dev for dev in devices
            if safe_candidate(devices, dev, roots, blank_only=True)
            and size_matches(dev, expected_size)
        ]
        matched = unique_candidate(blanks, "unique blank volume size")
        if matched:
            return resolution(matched, "UNIQUE_BLANK_SIZE")

    raise VolumeIdentityError(
        "Unable to identify the exact attached Storage Service volume device for %s" %
        (volume_uuid or volume_name or filesystem_uuid or "unknown volume"))
