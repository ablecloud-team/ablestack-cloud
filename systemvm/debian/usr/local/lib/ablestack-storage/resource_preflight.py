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

"""Bounded read-only readiness; never drains, mounts, formats, or grants writer authority."""
import json
import math
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import time
import uuid
from root_data_inspection import inspect_data
from volume_identity import VolumeIdentityError, flatten_devices, flatten_mounts


PREFLIGHT_PROTOCOLS = ("NFS", "SMB", "ISCSI", "NVME_OF")
PREFLIGHT_CONFIGS = {
    "NFS": ("desired-state/nfs-export-apply.json", "exports"),
    "SMB": ("desired-state/smb-share-apply.json", "shares"),
    "ISCSI": ("iscsi-targets.json", "targets"),
    "NVME_OF": ("nvmeof-subsystems.json", "subsystems"),
}
PREFLIGHT_PORTS = {"NFS": 2049, "SMB": 445, "ISCSI": 3260, "NVME_OF": 4420}


def preflight_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Readiness JSON has duplicate fields")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite readiness JSON")))


def preflight_read(path, limit=8 * 1024 * 1024):
    try:
        info = path.lstat()
    except FileNotFoundError:
        return None
    parent = path.parent.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022
            or not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
            or info.st_mode & 0o022 or info.st_size > limit):
        raise ValueError("Readiness source is not protected")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | getattr(os, "O_NOATIME", 0))
    try:
        opened = os.fstat(fd)
        signature = lambda v: (v.st_dev, v.st_ino, v.st_mode, v.st_uid, v.st_gid, v.st_size, v.st_mtime_ns, v.st_ctime_ns)
        if signature(opened) != signature(info):
            raise ValueError("Readiness source changed before read")
        raw = os.read(fd, limit + 1)
        if len(raw) > limit or signature(opened) != signature(os.fstat(fd)) or signature(opened) != signature(path.lstat()):
            raise ValueError("Readiness source changed during read")
        return preflight_json(raw)
    finally:
        os.close(fd)


class ReadinessProbe:
    """The command vocabulary is fixed; timeout/output bounds include every child."""
    def __init__(self, config=None, clock=None):
        self.config = Path(config or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT", "/etc/ablestack-storage"))
        self.clock = clock or time.monotonic
        self.deadline = self.clock() + 20

    def run(self, arguments, limit=1024 * 1024):
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise TimeoutError("Readiness observation deadline exceeded")
        child = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, close_fds=True)
        selector = selectors.DefaultSelector()
        data = bytearray()
        try:
            os.set_blocking(child.stdout.fileno(), False)
            selector.register(child.stdout, selectors.EVENT_READ)
            until = min(self.deadline, self.clock() + 3)
            while selector.get_map():
                remaining = until - self.clock()
                if remaining <= 0:
                    raise TimeoutError("Readiness child deadline exceeded")
                for key, _ in selector.select(remaining):
                    raw = os.read(key.fileobj.fileno(), min(65536, limit + 1 - len(data)))
                    if not raw:
                        selector.unregister(key.fileobj)
                    else:
                        data.extend(raw)
                        if len(data) > limit:
                            raise ValueError("Readiness observation output exceeds its bound")
            if child.wait(timeout=max(.001, until - self.clock())) != 0:
                raise ValueError("Readiness observation command failed")
            return bytes(data).decode("utf-8")
        finally:
            selector.close()
            if child.stdout:
                child.stdout.close()
            if child.poll() is None:
                child.kill()  # Only this owned read-only observer child, never a formatter/service.
                try:
                    child.wait(timeout=.2)
                except subprocess.TimeoutExpired:
                    pass

    def ports(self, protocol, scope=None):
        path, collection = PREFLIGHT_CONFIGS[protocol]
        value = preflight_read(self.config / path)
        if value is None:
            return set(), False
        if not isinstance(value, dict) or not isinstance(value.get(collection, []), list):
            raise ValueError("Readiness protocol declaration is invalid")
        if scope is not None and value.get("instanceUuid") != scope["instanceUuid"]:
            raise ValueError("Readiness canonical protocol belongs to another instance")
        if "enabled" in value and type(value["enabled"]) is not bool:
            raise ValueError("Readiness enabled declaration is invalid")
        if any(not isinstance(row, dict) for row in value.get(collection, [])):
            raise ValueError("Readiness resource declaration is invalid")
        rows = [row for row in value.get(collection, [])
                if row.get("state", "Ready") not in ("Disabled", "Destroyed", "Error")]
        if value.get("enabled") is False or not rows:
            return set(), False
        ports = set()
        listeners = value.get("listeners", [])
        if not isinstance(listeners, list):
            raise ValueError("Readiness listener collection is invalid")
        for listener in listeners + ([{"port": value["port"]}] if "port" in value else []):
            if not isinstance(listener, dict):
                raise ValueError("Readiness listener declaration is invalid")
            if listener.get("state", "Ready") in ("Disabled", "Destroyed", "Error"):
                continue
            port = listener.get("port")
            if type(port) is not int or not 1 <= port <= 65535:
                raise ValueError("Readiness listener port is invalid")
            ports.add(port)
        if protocol == "NVME_OF":
            rows = rows + [item for row in rows for item in row.get("namespaces", [])]
        for row in rows:
            config = row.get("config") or {}
            candidates = config.get("listenerGroupPorts") or []
            if not isinstance(candidates, list):
                raise ValueError("Readiness listener declaration is invalid")
            for port in candidates:
                if type(port) is not int or not 1 <= port <= 65535:
                    raise ValueError("Readiness listener port is invalid")
                ports.add(port)
            supplied = row.get("listenerPorts")
            if supplied is not None:
                if not isinstance(supplied, str) or not re.fullmatch(r"[0-9]+(?:,[0-9]+)*", supplied):
                    raise ValueError("Readiness listener ports are malformed")
                for port in supplied.split(","):
                    if not 1 <= int(port) <= 65535:
                        raise ValueError("Readiness listener port is invalid")
                    ports.add(int(port))
        if not ports:
            ports.add(PREFLIGHT_PORTS[protocol])
        return ports, True

    def protocols(self, requested, scope=None):
        established = self.run(["ss", "-H", "-nt"])
        listening = self.run(["ss", "-H", "-lnt"])
        active_ports, listen_ports = [], set()
        for line in established.splitlines():
            parts = line.split()
            if len(parts) < 5:
                raise ValueError("Readiness TCP observation is malformed")
            if parts[0] in ("ESTAB", "SYN-RECV"):
                port = parts[3].rsplit(":", 1)[-1]
                if not port.isdigit():
                    raise ValueError("Readiness TCP port is invalid")
                active_ports.append(int(port))
        for line in listening.splitlines():
            parts = line.split()
            if len(parts) < 4 or parts[0] != "LISTEN" or not parts[3].rsplit(":", 1)[-1].isdigit():
                raise ValueError("Readiness listener observation is malformed")
            listen_ports.add(int(parts[3].rsplit(":", 1)[-1]))
        units = self.run(["systemctl", "list-units", "--state=active", "--type=service", "--no-legend", "--plain",
                          "ablestack-storage-ganesha@*.service", "ablestack-storage-smb@*.service",
                          "smbd.service", "smb.service", "samba.service", "nfs-ganesha.service",
                          "nfs-server.service", "nfs-kernel-server.service"])
        active_units = []
        for line in units.splitlines():
            fields = line.split()
            if len(fields) < 4:
                raise ValueError("Readiness service observation is malformed")
            if fields[2] == "active":
                active_units.append(fields[0])
        health, ports = {}, set()
        for protocol in requested:
            expected, configured = self.ports(protocol, scope)
            ports.update(expected)
            owned_service = True
            if protocol == "NFS":
                owned_service = any(re.fullmatch(r"(?:ablestack-storage-ganesha@[A-Za-z0-9_.-]+|nfs-ganesha|nfs-server|nfs-kernel-server)\.service", unit) for unit in active_units)
            elif protocol == "SMB":
                owned_service = any(re.fullmatch(r"(?:ablestack-storage-smb@[0-9a-f]{24}|smbd|smb|samba)\.service", unit) for unit in active_units)
            health[protocol] = "NOT_CONFIGURED" if not configured else "READY" if expected <= listen_ports and owned_service else "UNAVAILABLE"
        return sum(port in ports for port in active_ports), health

    def signatures(self, device):
        value = preflight_json(self.run(["wipefs", "--no-act", "--json", device]))
        if not isinstance(value, dict) or not isinstance(value.get("signatures"), list):
            raise ValueError("Readiness disk signatures are unobserved")
        return {"available": True, "signatures": value["signatures"]}

    def volumes(self, scope, declarations):
        if not declarations:
            return []
        blocks = preflight_json(self.run(["lsblk", "-J", "-b", "-o", "NAME,PATH,TYPE,SERIAL,SIZE,FSTYPE,UUID,MOUNTPOINT"]))
        mounts = preflight_json(self.run(["findmnt", "-J", "-o", "SOURCE,TARGET,FSTYPE,OPTIONS,UUID"]))
        observed = inspect_data({**scope, "templateUpgradeUuid": scope["operationUuid"], "volumes": declarations},
                                blocks["blockdevices"], mounts["filesystems"], self.signatures)
        result = []
        devices = flatten_devices(blocks["blockdevices"]);filesystems = flatten_mounts(mounts["filesystems"])
        for declared, row in zip(declarations, observed):
            exact = (row["mappingStatus"] == "EXACT" and row["matchedBy"] == "VOLUME_SERIAL"
                     and row["sizeBytes"] == declared["sizeBytes"] and row["signatureObservationAvailable"] is True)
            mounted = bool(row["mounts"])
            if declared["kind"] == "FILE_DATA":
                filesystem = self.run(["blkid", "-p", "-o", "export", "--", row["observedDevicePath"]])
                fields = dict(line.split("=", 1) for line in filesystem.splitlines() if "=" in line)
                exact = exact and row["filesystemUuid"] == declared["filesystemUuid"] == fields.get("UUID") and any(
                    item.get("target") == "/srv/ablestack-storage/volumes/" + declared["volumeUuid"]
                    for item in row["mounts"])
            else:
                raw = next((item for item in devices if item.get("path") == row["observedDevicePath"]), None)
                disk = (raw.get("_parentDisk") or raw) if raw else None
                related = {item.get("path") for item in devices if disk and (item.get("path") == disk.get("path")
                           or (item.get("_parentDisk") or {}).get("path") == disk.get("path"))}
                related_uuids = {item.get("uuid") for item in devices if item.get("path") in related and item.get("uuid")}
                mounted = mounted or any(str(item.get("source", "")).split("[", 1)[0] in related
                                         or item.get("uuid") in related_uuids for item in filesystems)
                exact = exact and not mounted
                if declared["kind"] == "UNUSED":
                    exact = exact and row["blank"]
            result.append({**declared, "mappingStatus": "EXACT" if exact else "UNAVAILABLE",
                           "mounted": mounted, "readOnly": True})
        return result

    def observe(self, scope, readiness):
        sessions, health = self.protocols(readiness["protocols"], scope)
        volumes = self.volumes(scope, readiness["volumes"])
        return {"activeSessions": sessions, "protocolHealth": health, "backingVolumes": volumes,
                "sessionObservationKind": "TCP_CONNECTIONS_ONLY"}


class ResourcePreflight:
    def __init__(self, reservation, probe=None):
        self.reservation = reservation
        self.probe = probe or ReadinessProbe()

    def readiness(self, request):
        readiness = request.get("readiness")
        if not isinstance(readiness, dict) or set(readiness) != {"protocols", "volumes"}:
            raise ValueError("Readiness requires exact protocol and volume declarations")
        protocols, volumes = readiness["protocols"], readiness["volumes"]
        if (not isinstance(protocols, list) or not 1 <= len(protocols) <= 4
                or any(protocol not in PREFLIGHT_PROTOCOLS for protocol in protocols)
                or len(set(protocols)) != len(protocols) or not isinstance(volumes, list) or len(volumes) > 512):
            raise ValueError("Readiness protocol or volume scope is invalid")
        seen = set()
        for row in volumes:
            if not isinstance(row, dict) or set(row) != {"volumeUuid", "sizeBytes", "kind", "filesystemUuid"}:
                raise ValueError("Readiness volume fields are invalid")
            value = row["volumeUuid"]
            if not isinstance(value, str) or str(uuid.UUID(value)) != value or value in seen:
                raise ValueError("Readiness volume identity is ambiguous")
            seen.add(value)
            if type(row["sizeBytes"]) is not int or not 0 < row["sizeBytes"] <= 1 << 50 or row["kind"] not in ("FILE_DATA", "BLOCK_RAW", "UNUSED"):
                raise ValueError("Readiness volume geometry is invalid")
            fs = row["filesystemUuid"]
            if row["kind"] == "FILE_DATA":
                if not isinstance(fs, str) or str(uuid.UUID(fs)) != fs:
                    raise ValueError("Readiness FILE_DATA filesystem identity is absent")
            elif fs is not None:
                raise ValueError("Readiness raw volume cannot declare a filesystem")
        return readiness

    def execute(self, request=None):
        store = self.reservation
        observed = dict(store.observe())
        if request is None:
            ok = (type(observed.get("memoryAvailableBytes")) is int and observed["memoryAvailableBytes"] >= 256 * 1024 * 1024
                  and type(observed.get("stagingFreeBytes")) is int and observed["stagingFreeBytes"] >= 100 * 1024 * 1024)
            return {"success": ok, "compatible": ok, "readOnly": True, "mode": "LEGACY_FIXED_FLOORS",
                    "status": "READY" if ok else "RESOURCE_PRESSURE", "legacyFixedFloors": True,
                    "memoryAvailableBytes": observed.get("memoryAvailableBytes"),
                    "stagingFreeBytes": observed.get("stagingFreeBytes"), "drainSupported": False,
                    "instanceUuid": None, "maintenanceAlternative": "NONE"}
        if not isinstance(request, dict) or set(request) != {"instanceUuid", "operationUuid", "revision", "requirements", "readiness"}:
            raise ValueError("Typed preflight request has unsupported or absent fields")
        scope = store.scope(request)
        store.validate_generation(scope, request)  # Preserve existing foreign/pending/initial-ROOT authority rules.
        source = (store.read(store.generation / "current.json"), store.read(store.generation / "pending.json"))
        requirements = store.requirements(request)
        readiness = self.readiness(request)
        blockers = []
        for key, threshold, reason in (("memoryAvailableBytes", "minimumMemoryAvailableBytes", "MEMORY_HEADROOM"),
                                       ("stagingFreeBytes", "stagingRequiredBytes", "STAGING_HEADROOM")):
            if type(observed.get(key)) is not int or observed[key] < 0:
                blockers.append(reason + "_UNAVAILABLE")
            elif observed[key] < requirements[threshold]:
                blockers.append(reason)
        load = observed.get("loadPerCpu")
        if type(load) not in (int, float) or not math.isfinite(load) or load < 0:
            blockers.append("LOAD_HEADROOM_UNAVAILABLE")
        elif load > requirements["maxLoadPerCpu"]:
            blockers.append("LOAD_HEADROOM")
        observed.update(activeSessions=None, sessionObservationKind="TCP_CONNECTIONS_ONLY", protocolHealth={p: "UNAVAILABLE" for p in readiness["protocols"]},
                        backingVolumes=[{**row, "mappingStatus": "UNAVAILABLE", "mounted": None, "readOnly": True}
                                        for row in readiness["volumes"]])
        if not blockers:
            try:
                observed.update(self.probe.observe(scope, readiness))
            except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, TimeoutError, VolumeIdentityError):
                blockers.append("READINESS_OBSERVATION_UNAVAILABLE")
            sessions = observed.get("activeSessions")
            if type(sessions) is not int or sessions < 0:
                blockers.append("SESSION_OBSERVATION_UNAVAILABLE")
            elif sessions:
                blockers.append("ACTIVE_SESSIONS_REQUIRE_MAINTENANCE")
            health = observed.get("protocolHealth")
            if not isinstance(health, dict) or set(health) != set(readiness["protocols"]) or any(
                    state not in ("READY", "NOT_CONFIGURED") for state in health.values()):
                blockers.append("PROTOCOL_HEALTH_UNAVAILABLE")
            volumes = observed.get("backingVolumes")
            if not isinstance(volumes, list) or len(volumes) != len(readiness["volumes"]) or any(
                    not isinstance(row, dict) or row.get("mappingStatus") != "EXACT" or row.get("readOnly") is not True
                    or any(row.get(key) != expected[key] for key in expected)
                    for row, expected in zip(volumes, readiness["volumes"])):
                blockers.append("BACKING_VOLUME_UNAVAILABLE")
        if requirements["requireSessionDrain"]:
            blockers.append("DRAIN_NOT_IMPLEMENTED")
        if (store.read(store.generation / "current.json"), store.read(store.generation / "pending.json")) != source:
            raise ValueError("Typed preflight generation changed during read")
        compatible = not blockers
        return {"success": compatible, "compatible": compatible, "readOnly": True, "mode": "TYPED_READINESS_V1",
                "status": "READY" if compatible else "RESOURCE_PRESSURE", "scope": scope,
                "instanceUuid": scope["instanceUuid"], "bootId": store.boot_id,
                "generatedEpoch": store.clock() / 1000, "requirements": requirements, "observed": observed,
                "blockers": sorted(set(blockers)), "drainSupported": False,
                "maintenanceAlternative": "APPROVED_MAINTENANCE_PLAN_REQUIRED" if (
                    requirements["requireSessionDrain"] or "ACTIVE_SESSIONS_REQUIRE_MAINTENANCE" in blockers) else "NONE"}
