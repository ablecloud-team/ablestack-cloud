# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Preserve saved boot declarations; repair only an attested single device-name observation."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import uuid

from config_generation import Generation, redact
from nvme_cleanup import nvme_cleanup_writer
from volume_identity import resolve_volume_device, flatten_devices

NVME_BOOT_FIELDS = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid", "st_size", "st_mtime_ns", "st_ctime_ns")
NVME_BOOT_LIMIT = 8 * 1024 * 1024


def nvme_boot_json(raw):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("NVMe boot record contains duplicate keys")
            value[key] = item
        return value
    value = json.loads(raw, object_pairs_hook=unique, parse_constant=lambda value: (_ for _ in ()).throw(
        ValueError("NVMe boot record contains a nonfinite number")))
    if not isinstance(value, dict):
        raise ValueError("NVMe boot record is not an object")
    return value


def nvme_boot_read(path, optional=False):
    path = Path(path)
    try:
        parent = path.parent.lstat()
    except FileNotFoundError:
        if not optional:
            raise
        # An unconfigured protocol can have no desired-state directory.
        # Inspect its nearest existing ancestor; a dangling/foreign parent is
        # never interpreted as an absent declaration.
        ancestor = path.parent
        while True:
            try:
                parent = ancestor.lstat()
                break
            except FileNotFoundError:
                ancestor = ancestor.parent
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
            raise ValueError("NVMe absent declaration ancestor is not protected")
        return None
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
        raise ValueError("NVMe boot record parent is not protected")
    try:
        before = path.lstat()
    except FileNotFoundError:
        if optional:
            return None
        raise
    if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid() or before.st_mode & 0o022
            or before.st_size > NVME_BOOT_LIMIT):
        raise ValueError("NVMe boot record is not protected")
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        if any(getattr(parent, key) != getattr(os.fstat(directory), key) for key in ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid")):
            raise ValueError("NVMe boot record directory changed")
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
        try:
            opened = os.fstat(fd)
            raw = os.read(fd, NVME_BOOT_LIMIT + 1)
            after = os.fstat(fd)
            named = path.lstat()
            if len(raw) > NVME_BOOT_LIMIT or any(
                    getattr(before, key) != getattr(opened, key) or getattr(after, key) != getattr(opened, key)
                    or getattr(named, key) != getattr(opened, key) for key in NVME_BOOT_FIELDS):
                raise ValueError("NVMe boot record changed during observation")
            return {"raw": raw, "value": nvme_boot_json(raw),
                    "signature": tuple(getattr(opened, key) for key in NVME_BOOT_FIELDS),
                    "sha256": hashlib.sha256(raw).hexdigest()}
        finally:
            os.close(fd)
    finally:
        os.close(directory)


class NvmeSavedBootSource:
    """Only the fixed protected canonical payload may suppress re-persistence."""

    def __init__(self, payload_file, payload, canonical="/etc/ablestack-storage/nvmeof-subsystems.json"):
        self.path = Path(canonical)
        self.saved = None
        source = Path(payload_file)
        if str(source) == str(self.path):
            nvme_cleanup_writer()
            self.saved = nvme_boot_read(self.path)
            if self.saved["value"] != payload:
                raise ValueError("NVMe boot payload differs from the protected saved declaration")
        elif self.path.exists() and os.path.samefile(source, self.path):
            raise ValueError("NVMe boot canonical source alias is not accepted")

    def preserve(self):
        if self.saved is None:
            return False
        nvme_cleanup_writer()
        if nvme_boot_read(self.path) != self.saved:
            raise ValueError("NVMe saved declaration changed before boot completion")
        return True


class NvmeBootRecovery:
    """No generation adoption: restore one cached path to the same immutable artifact."""

    def __init__(self, configuration="/etc/ablestack-storage",
                 generations="/var/lib/ablestack-storage/config-generations",
                 reservation="/var/lib/ablestack-storage/resource-reservation/lease.json",
                 base="/sys/kernel/config/nvmet", boot=None, devices=None):
        self.configuration = Path(configuration)
        self.generations = Path(generations)
        self.reservation = Path(reservation)
        self.base = Path(base)
        self.boot = boot or (lambda: Path("/proc/sys/kernel/random/boot_id").read_text().strip())
        self.devices = devices or self.blockdevices

    @staticmethod
    def blockdevices():
        result = subprocess.run(["lsblk", "-J", "-b", "-o", "NAME,KNAME,PATH,SIZE,TYPE,SERIAL,FSTYPE,UUID,MOUNTPOINT"],
                                capture_output=True, text=True, timeout=10)
        if result.returncode:
            raise ValueError("NVMe boot volume observation failed")
        value = json.loads(result.stdout)
        if not isinstance(value, dict) or not isinstance(value.get("blockdevices"), list):
            raise ValueError("NVMe boot volume observation is malformed")
        return value["blockdevices"]

    @staticmethod
    def digest(value):
        return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

    def snapshot(self):
        info = self.configuration.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise ValueError("NVMe configuration root is not protected")
        return {name: nvme_boot_read(self.configuration / name, optional=True) for name in Generation.paths}

    def quiet(self):
        # Do not create absent state directories or consume/alter a lease.
        for path in (self.generations / "pending.json", self.reservation):
            try:
                path.lstat()
            except FileNotFoundError:
                continue
            raise ValueError("NVMe boot repair conflicts with pending generation or reservation")

    @staticmethod
    def uuid(value):
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError("NVMe boot repair UUID is invalid")
        return value

    @staticmethod
    def device_path(value):
        if not isinstance(value, str) or not re.fullmatch(r"/dev/(?:sd[a-z]+|vd[a-z]+|xvd[a-z]+|nvme[0-9]+n[0-9]+)", value):
            raise ValueError("NVMe boot cached observation is outside direct block device names")
        return value

    def attribute(self, path):
        # configfs may allocate its inode on open; pin the opened FD and post-path.
        path = Path(path)
        for directory in (self.base, self.base / "subsystems", path.parent.parent.parent, path.parent.parent, path.parent):
            info = directory.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
                raise ValueError("NVMe boot live namespace directory is not protected")
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            before = os.fstat(fd)
            named = path.lstat()
            if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid() or before.st_mode & 0o022
                    or any(getattr(before, key) != getattr(named, key) for key in NVME_BOOT_FIELDS)):
                raise ValueError("NVMe boot live namespace attribute is not protected")
            raw = os.read(fd, 257)
            after = os.fstat(fd)
            named = path.lstat()
            if len(raw) > 256 or any(getattr(before, key) != getattr(after, key) or
                                      getattr(before, key) != getattr(named, key) for key in NVME_BOOT_FIELDS):
                raise ValueError("NVMe boot live namespace attribute changed")
            return raw.decode("ascii").strip()
        finally:
            os.close(fd)

    def live(self, subsystem, namespace):
        self.uuid(subsystem.get("uuid")); self.uuid(namespace.get("uuid"))
        volume = self.uuid(namespace.get("volumeUuid"))
        nqn, number, size = subsystem.get("targetName"), namespace.get("lunOrNamespace"), namespace.get("volumeSizeBytes")
        if (not isinstance(nqn, str) or not re.fullmatch(r"nqn\.[A-Za-z0-9_.:-]{1,219}", nqn)
                or ".local.storage:" not in nqn or type(number) not in (str, int)
                or not re.fullmatch(r"[1-9][0-9]{0,4}", str(number)) or int(number) > 65535
                or type(size) is not int or size <= 0 or subsystem.get("state") != "Ready"
                or namespace.get("state") != "Ready" or not isinstance(namespace.get("runtime"), dict)
                or namespace["runtime"].get("backingType") != "block"):
            raise ValueError("NVMe boot namespace canonical binding is invalid")
        devices = self.devices()
        resolved = resolve_volume_device(devices, volume_uuid=volume, expected_size=size, allow_blank_size_fallback=False)
        path = self.device_path(resolved["devicePath"])
        candidates = [row for row in flatten_devices(devices) if row.get("path") == path]
        if len(candidates) != 1:
            raise ValueError("NVMe boot volume mapping is ambiguous")
        row = candidates[0]
        serial = str(row.get("serial") or "").lower()
        if (resolved.get("matchedBy") != "VOLUME_SERIAL" or serial not in (volume.replace("-", ""), volume.replace("-", "")[:20])
                or type(row.get("size")) not in (int, str) or str(row["size"]) != str(size)
                or row.get("type") != "disk" or row.get("fstype") or row.get("mountpoint") or row.get("children")):
            raise ValueError("NVMe boot volume serial/size/raw identity differs")
        info = Path(path).lstat()
        if not stat.S_ISBLK(info.st_mode) or info.st_uid != os.geteuid():
            raise ValueError("NVMe boot resolved volume is not a direct owned block device")
        directory = self.base / "subsystems" / nqn / "namespaces" / str(number)
        if self.attribute(directory / "enable") != "1" or self.attribute(directory / "device_path") != path:
            raise ValueError("NVMe boot enabled namespace does not bind its exact current volume")
        return path, (info.st_dev, info.st_ino, info.st_rdev, info.st_mode, info.st_uid)

    def recover(self, request):
        fields = {"instanceUuid", "operationUuid", "revision", "expectedBootId", "expectedCurrentSha256",
                  "expectedArtifactSha256", "expectedConfigurationSha256", "expectedObservedConfigurationSha256", "namespaceUuid"}
        if not isinstance(request, dict) or set(request) != fields:
            raise ValueError("NVMe boot recovery request schema is invalid")
        for key in ("instanceUuid", "operationUuid", "expectedBootId", "namespaceUuid"):
            self.uuid(request[key])
        if type(request["revision"]) is not int or request["revision"] < 1:
            raise ValueError("NVMe boot recovery revision is invalid")
        for key in fields - {"instanceUuid", "operationUuid", "revision", "expectedBootId", "namespaceUuid"}:
            if not isinstance(request[key], str) or not re.fullmatch(r"[0-9a-f]{64}", request[key]):
                raise ValueError("NVMe boot recovery digest is invalid")
        nvme_cleanup_writer(); self.quiet()
        if self.boot() != request["expectedBootId"]:
            raise ValueError("NVMe boot recovery BOOT changed")
        current_path = self.generations / "current.json"
        artifact_path = self.generations / (request["operationUuid"] + ".json")
        current, artifact = nvme_boot_read(current_path), nvme_boot_read(artifact_path)
        scope = {key: request[key] for key in ("instanceUuid", "operationUuid", "revision")}
        if (any(current["value"].get(key) != value or artifact["value"].get(key) != value for key, value in scope.items())
                or current["sha256"] != request["expectedCurrentSha256"] or artifact["sha256"] != request["expectedArtifactSha256"]
                or type(current["value"].get("revision")) is not int or type(artifact["value"].get("revision")) is not int
                or artifact["value"].get("phase") != "VERIFIED"
                or current["value"].get("configurationSha256") != request["expectedConfigurationSha256"]
                or artifact["value"].get("configurationSha256") != request["expectedConfigurationSha256"]):
            raise ValueError("NVMe boot recovery current/artifact authority differs")
        desired = artifact["value"].get("desired")
        if (not isinstance(desired, dict) or set(desired) != set(Generation.paths) or redact(desired) != desired
                or any(value is not None and not isinstance(value, dict) for value in desired.values())
                or self.digest(desired) != request["expectedConfigurationSha256"]):
            raise ValueError("NVMe boot immutable artifact does not bind all seven declarations")
        before = self.snapshot()
        actual = {name: redact(record["value"]) if record else None for name, record in before.items()}
        observed = self.digest(actual)
        if observed not in (request["expectedObservedConfigurationSha256"], request["expectedConfigurationSha256"]):
            raise ValueError("NVMe boot observed generation differs from the exact requested snapshot")
        name = "nvmeof-subsystems.json"
        if (not isinstance(actual[name], dict) or actual[name].get("instanceUuid") != request["instanceUuid"]
                or actual[name].get("enabled") is not True):
            raise ValueError("NVMe boot repair lacks its exact enabled instance")
        candidates = [(subsystem, namespace) for subsystem in actual[name].get("subsystems", [])
                      for namespace in subsystem.get("namespaces", []) if namespace.get("uuid") == request["namespaceUuid"]]
        frozen = [(subsystem, namespace) for subsystem in (desired.get(name) or {}).get("subsystems", [])
                  for namespace in subsystem.get("namespaces", []) if namespace.get("uuid") == request["namespaceUuid"]]
        if len(candidates) != 1 or len(frozen) != 1:
            raise ValueError("NVMe boot repair namespace is unavailable or duplicated")
        subsystem, namespace = candidates[0]
        old_path = self.device_path((frozen[0][1].get("runtime") or {}).get("backingPath"))
        observed_path = self.device_path((namespace.get("runtime") or {}).get("backingPath"))
        corrected = copy.deepcopy(actual)
        corrected_namespace = next(ns for sub in corrected[name]["subsystems"] for ns in sub["namespaces"]
                                   if ns.get("uuid") == request["namespaceUuid"])
        corrected_namespace["runtime"]["backingPath"] = old_path
        if corrected != desired or (observed != request["expectedConfigurationSha256"] and observed_path == old_path):
            raise ValueError("NVMe boot recovery permits only one cached backingPath difference")
        live = self.live(subsystem, namespace)
        if observed != request["expectedConfigurationSha256"] and live[0] != observed_path:
            raise ValueError("NVMe boot changed observation is not the actual attached namespace path")

        def guard():
            nvme_cleanup_writer(); self.quiet()
            if (self.live(subsystem, namespace) != live or self.boot() != request["expectedBootId"]
                    or nvme_boot_read(current_path) != current or nvme_boot_read(artifact_path) != artifact
                    or self.snapshot() != before):
                raise ValueError("NVMe boot repair snapshot or live binding changed")

        guard()
        changed = observed != request["expectedConfigurationSha256"]
        if changed:
            value = copy.deepcopy(before[name]["value"])
            next(ns for sub in value["subsystems"] for ns in sub["namespaces"]
                 if ns.get("uuid") == request["namespaceUuid"])["runtime"]["backingPath"] = old_path
            raw = json.dumps(value, sort_keys=True, indent=2, allow_nan=False).encode()
            destination = self.configuration / name
            fd, temporary = tempfile.mkstemp(prefix=".nvme-boot-observation-", dir=destination.parent)
            try:
                os.fchown(fd, before[name]["signature"][3], before[name]["signature"][4])
                os.fchmod(fd, stat.S_IMODE(before[name]["signature"][2]))
                with os.fdopen(fd, "wb") as output:
                    output.write(raw); output.flush(); os.fsync(output.fileno())
                guard()
                os.replace(temporary, destination)
                directory = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        after = self.snapshot()
        expected = {key: record for key, record in before.items() if key != name}
        if ((after[name]["raw"] != raw if changed else after[name] != before[name]) or
                any(after[key] != record for key, record in expected.items()) or
                {key: redact(record["value"]) if record else None for key, record in after.items()} != desired
                or nvme_boot_read(current_path) != current or nvme_boot_read(artifact_path) != artifact
                or self.boot() != request["expectedBootId"] or self.live(subsystem, namespace) != live):
            raise ValueError("NVMe boot repair postcondition failed")
        nvme_cleanup_writer(); self.quiet()
        return {"success": True, "canonicalChanged": changed, "generationStatus": "IN_SYNC",
                "configurationSha256": request["expectedConfigurationSha256"], "scope": scope,
                "bootId": request["expectedBootId"], "namespaceUuid": request["namespaceUuid"],
                "nvmeConfigurationSha256": after[name]["sha256"]}
