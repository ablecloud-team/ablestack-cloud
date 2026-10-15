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

"""Public NVMe identity checkpoint for an exact native configuration writer."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import uuid

NVME_IDENTITY_PATHS = ("desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json",
                       "iscsi-targets.json", "nvmeof-subsystems.json", "posix-directory-policies.json",
                       "network-endpoints.json", "sharedfs-network.json")
NVME_IDENTITY_FIELD = "nvmeRuntimeIdentity"
NVME_IDENTITY_KIND = "NVMEOF_SOURCE_RUNTIME_IDENTITY"


def nvme_identity_uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("NVMe public identity UUID is not canonical")
    return value


def nvme_identity_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     allow_nan=False).encode()).hexdigest()


def nvme_identity_redact(value):
    if isinstance(value, dict):
        return {key: nvme_identity_redact(item) for key, item in value.items()
                if key not in ("lastApply", "lastApplied", "observedAt", "generatedEpoch")
                and not any(word in key.lower() for word in
                            ("password", "secret", "dhchapkey", "dhchapctrlkey", "privatekey", "keytab", "capsule"))}
    if isinstance(value, list):
        return [nvme_identity_redact(item) for item in value]
    return value


def nvme_identity_bindings(payload, instance):
    if not isinstance(payload, dict) or payload.get("instanceUuid", instance) != instance:
        raise ValueError("NVMe public identity canonical instance changed")
    rows = payload.get("subsystems") or []
    if not isinstance(rows, list) or len(rows) > 1024:
        raise ValueError("NVMe public identity canonical subsystem list is invalid")
    subsystems, namespaces, hosts, ids, names, namespace_ids = [], [], [], set(), set(), set()
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("namespaces", []), list):
            raise ValueError("NVMe public identity canonical subsystem row is invalid")
        identity = nvme_identity_uuid(row.get("uuid"))
        name = row.get("targetName")
        if (not isinstance(name, str) or not re.fullmatch(r"nqn\.[A-Za-z0-9_.:-]{1,219}", name)
                or identity in ids or name in names):
            raise ValueError("NVMe public identity subsystem binding is invalid")
        ids.add(identity); names.add(name)
        subsystems.append({"uuid": identity, "nqn": name})
        seen_hosts = set()
        for host in row.get("hosts") or []:
            principal = host.get("principal") if isinstance(host, dict) else None
            if (not isinstance(principal, str) or not re.fullmatch(r"nqn\.[A-Za-z0-9_.:-]{1,219}", principal)
                    or principal in seen_hosts):
                raise ValueError("NVMe public identity host binding is invalid")
            seen_hosts.add(principal)
            hosts.append({"subsystemUuid": identity, "nqn": name, "principal": principal})
        seen_nsids = set()
        for namespace in row.get("namespaces") or []:
            if not isinstance(namespace, dict):
                raise ValueError("NVMe public identity canonical namespace row is invalid")
            ns_uuid = nvme_identity_uuid(namespace.get("uuid"))
            volume = nvme_identity_uuid(namespace.get("volumeUuid"))
            number = namespace.get("lunOrNamespace", 1)
            size = namespace.get("volumeSizeBytes")
            if (type(number) not in (int, str) or not re.fullmatch(r"[0-9]{1,5}", str(number))
                    or not 1 <= int(number) <= 65535 or str(int(number)) in seen_nsids
                    or ns_uuid in namespace_ids or type(size) is not int or size <= 0):
                raise ValueError("NVMe public identity namespace/volume binding is invalid")
            seen_nsids.add(str(int(number))); namespace_ids.add(ns_uuid)
            namespaces.append({"uuid": ns_uuid, "subsystemUuid": identity, "nqn": name,
                               "nsid": str(int(number)), "volumeUuid": volume, "sizeBytes": size})
    if len(namespaces) > 4096 or len(hosts) > 4096:
        raise ValueError("NVMe public identity namespace collection exceeds its bound")
    return {"subsystems": sorted(subsystems, key=lambda row: row["uuid"]),
            "namespaces": sorted(namespaces, key=lambda row: row["uuid"]),
            "hosts": sorted(hosts, key=lambda row: (row["subsystemUuid"], row["principal"]))}


class NvmeRuntimeIdentity:
    """Run after NvmeManagedCleanup construction, before the first kernel/network effect.

    The cleanup object's protected prior canonical record and FD9 are reused.
    Only five fixed public identity attributes are ever written. DATA, auth
    attributes, enable bits and ports remain owned by their existing adapters.
    """

    def __init__(self, cleanup, payload, generation_root=None, probe=None, boot=None, write=None):
        self._initialize(cleanup, payload, generation_root, probe, boot, write)
        self.cleanup.guard()
        if self.pending is not None:
            self.scope = self._scope(self.pending)
            self._source_guard(self.pending, digest=True)
            recorded = self.pending.get(NVME_IDENTITY_FIELD)
            if recorded is not None:
                self._validate(recorded)
                if self.target_bindings not in (recorded["sourceBindings"], recorded["targetBindings"]):
                    raise ValueError("NVMe identity retry changed its original canonical target binding")
                if self.source_bindings != recorded["sourceBindings"]:
                    raise ValueError("NVMe identity retry has not restored its exact canonical SOURCE")
                self.checkpoint = recorded
            else:
                if self.pending.get("phase") != "PREPARED":
                    raise ValueError("NVMe identity SOURCE capture requires its original pre-mutation writer")
                self.checkpoint = self._capture(durable=True)
                self._publish(self.checkpoint)
        else:
            # Startup has no historical restore authority. Reuse only live
            # identities that are still bound to the protected prior canonical.
            self.checkpoint = self._capture(durable=False)
        self.guard()

    def _initialize(self, cleanup, payload, generation_root, probe, boot, write):
        self.cleanup = cleanup
        self.base = cleanup.base
        self.instance = nvme_identity_uuid(cleanup.instance)
        self.generation_root = Path(generation_root or os.environ.get(
            "ABLESTACK_STORAGE_GENERATION_DIR", "/var/lib/ablestack-storage/config-generations"))
        self.pending_path = self.generation_root / "pending.json"
        self.current_path = self.generation_root / "current.json"
        self.probe = probe or self.probe_volume
        self.boot = boot or (lambda: Path("/proc/sys/kernel/random/boot_id").read_text().strip())
        self.write = write or os.write
        self.source_bindings = nvme_identity_bindings(cleanup.previous, self.instance)
        self.target_bindings = nvme_identity_bindings(payload, self.instance)
        self.payload = payload
        self.pending = self._json(self.pending_path, optional=True)
        self.current = self._json(self.current_path, optional=True) or {}
        self.boot_id = nvme_identity_uuid(self.boot())
        self.existing_subsystems = {row["nqn"] for row in self.source_bindings["subsystems"]
                                    if (self.base / "subsystems" / row["nqn"]).exists()}
        self.checkpoint = None
        self.scope = None

    @classmethod
    def cleanup_authority(cls, cleanup, payload, generation_root=None, probe=None, boot=None):
        """Read existing immutable TARGET receipt before cleanup's foreign guards.

        This never captures/mints identity authority or modifies canonical/book.
        Runtime namespace devices must match an exact SOURCE or TARGET volume.
        """
        value = cls.__new__(cls)
        value._initialize(cleanup, payload, generation_root, probe, boot, None)
        cleanup.guard()
        if value.pending is None or NVME_IDENTITY_FIELD not in value.pending:
            return {}
        value.scope = value._scope(value.pending)
        value._source_guard(value.pending, digest=True)
        recorded = value.pending[NVME_IDENTITY_FIELD]
        value._validate(recorded)
        if (value.source_bindings != recorded["sourceBindings"]
                or value.target_bindings not in (recorded["sourceBindings"], recorded["targetBindings"])):
            raise ValueError("NVMe cleanup TARGET authority differs from its restored original SOURCE")
        value.checkpoint = recorded
        value.guard()
        bindings = (recorded["sourceBindings"], recorded["targetBindings"])
        rows = {}
        for projection in bindings:
            for subsystem in projection["subsystems"]:
                nqn = subsystem["nqn"]
                row = rows.setdefault(nqn, {"uuid": subsystem["uuid"], "targetName": nqn,
                                             "namespaces": [], "hosts": []})
                for namespace in projection["namespaces"]:
                    if namespace["subsystemUuid"] == subsystem["uuid"]:
                        row["namespaces"].append({"uuid": namespace["uuid"], "lunOrNamespace": namespace["nsid"],
                            "volumeUuid": namespace["volumeUuid"], "volumeSizeBytes": namespace["sizeBytes"]})
                for host in projection["hosts"]:
                    if host["subsystemUuid"] == subsystem["uuid"]:
                        row["hosts"].append({"principal": host["principal"]})
        for nqn, row in rows.items():
            path = value.base / "subsystems" / nqn
            if not path.exists():
                continue
            cleanup.directory(path)
            cleanup.directory(path / "namespaces")
            allowed = {}
            for projection in bindings:
                for namespace in projection["namespaces"]:
                    if namespace["nqn"] == nqn:
                        allowed.setdefault(namespace["nsid"], []).append(namespace)
            for namespace in (path / "namespaces").iterdir():
                cleanup.directory(namespace)
                candidates = allowed.get(namespace.name, [])
                if not candidates:
                    raise ValueError("NVMe cleanup TARGET has an unrecorded namespace")
                device = value._read(namespace / "device_path")
                matched = False
                for candidate in candidates:
                    try:
                        value._volume(candidate, device)
                    except ValueError:
                        continue
                    matched = True
                    break
                if not matched:
                    raise ValueError("NVMe cleanup TARGET namespace DATA volume is foreign")
            known_hosts = {host["principal"] for host in row["hosts"]}
            if any(link.name not in known_hosts for link in cleanup.links(path / "allowed_hosts")):
                raise ValueError("NVMe cleanup TARGET retains an unrecorded host")
        value.guard()
        return {"instanceUuid": value.instance, "subsystems": list(rows.values())}

    def _json(self, path, optional=False):
        if optional and not path.parent.exists():
            return None
        return self.cleanup.read(path, optional=optional, private=True)[0]

    def _scope(self, pending):
        scope = {key: pending.get(key) for key in ("instanceUuid", "operationUuid", "revision")}
        nvme_identity_uuid(scope["instanceUuid"]); nvme_identity_uuid(scope["operationUuid"])
        if (scope["instanceUuid"] != self.instance or type(scope["revision"]) is not int
                or scope["revision"] < 1):
            raise ValueError("NVMe identity native writer scope is invalid")
        return scope

    def _canonical(self):
        values = {}
        for name in NVME_IDENTITY_PATHS:
            path = self.cleanup.state.parent / name
            values[name] = nvme_identity_redact(self.cleanup.read(path, optional=True)[0]) if path.parent.exists() else None
        return values

    def _source_guard(self, pending, digest=False):
        scope = self._scope(pending)
        current = self._json(self.current_path, optional=True) or {}
        before = pending.get("beforeSha256")
        if current:
            nvme_identity_uuid(current.get("operationUuid"))
        if (pending.get("phase") not in ("PREPARED", "VERIFIED") or current != pending.get("previous")
                or current != self.current or scope != self.scope
                or not isinstance(before, str) or not re.fullmatch("[0-9a-f]{64}", before)
                or current and (current.get("instanceUuid") != self.instance
                    or current.get("configurationSha256") != before
                    or type(current.get("revision")) is not int
                    or current["revision"] >= scope["revision"])):
            raise ValueError("NVMe identity SOURCE/current/pending authority changed")
        if digest:
            actual = self._canonical()
            if current:
                artifact = self._json(self.generation_root / (current["operationUuid"] + ".json"))
                desired = artifact.get("desired")
                frozen = {key: artifact.get(key) for key in
                          ("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt")}
                if (artifact.get("phase") != "VERIFIED" or frozen != current
                        or not isinstance(desired, dict) or set(desired) != set(NVME_IDENTITY_PATHS)
                        or any(row is not None and not isinstance(row, dict) for row in desired.values())
                        or nvme_identity_redact(desired) != desired
                        or nvme_identity_digest(desired) != before):
                    raise ValueError("NVMe identity frozen SOURCE generation changed")
                # Earlier domains in one forward operation may already be TARGET.
                # The verified seven-file SOURCE artifact remains authoritative;
                # this protocol must still be its exact SOURCE before capture.
                previous_nvme = None if self.cleanup.source_signature is None else nvme_identity_redact(self.cleanup.previous)
                if (actual["nvmeof-subsystems.json"] != desired["nvmeof-subsystems.json"]
                        or previous_nvme != desired["nvmeof-subsystems.json"]):
                    raise ValueError("NVMe identity canonical SOURCE fingerprint changed")
            elif nvme_identity_digest(actual) != before:
                raise ValueError("NVMe initial identity SOURCE fingerprint changed")
        identity = str(uuid.UUID(bytes=hashlib.md5(
            ("local-source-checkpoint:" + scope["operationUuid"]).encode()).digest(), version=3))
        local_path = self.generation_root.parent / "smb-local-source-checkpoints" / (identity + "-journal.json")
        local = self._json(local_path, optional=True) if local_path.parent.exists() else None
        if local is not None and (local.get("scope") != {**scope, "localCheckpointUuid": identity}
                or local.get("common", {}).get("expectedBootId") != self.boot_id
                or local.get("common", {}).get("sourceConfigurationSha256") != before
                or local.get("common", {}).get("sourceGeneration") != current):
            raise ValueError("NVMe identity cannot borrow another LOCAL source scope or boot")

    def guard(self):
        self.cleanup.guard()
        if nvme_identity_uuid(self.boot()) != self.boot_id:
            raise ValueError("NVMe identity boot changed")
        if self.scope is not None:
            pending = self._json(self.pending_path)
            self._source_guard(pending)
            if pending.get(NVME_IDENTITY_FIELD) != self.checkpoint:
                raise ValueError("NVMe identity immutable checkpoint changed")
        elif self._json(self.pending_path, optional=True) is not None or (
                self._json(self.current_path, optional=True) or {}) != self.current:
            raise ValueError("NVMe startup identity generation scope changed")

    def _publish(self, value):
        self.cleanup.guard()
        pending = self._json(self.pending_path)
        self._source_guard(pending, digest=True)
        if pending != self.pending:
            raise ValueError("NVMe identity pending changed before checkpoint publication")
        info = self.generation_root.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o700):
            raise ValueError("NVMe identity generation directory is not private")
        raw = json.dumps({**pending, NVME_IDENTITY_FIELD: value}, sort_keys=True, allow_nan=False).encode()
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError("NVMe identity pending checkpoint exceeds its bound")
        fd, temporary = tempfile.mkstemp(prefix=".nvme-runtime-identity-", dir=self.generation_root)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw); stream.flush(); os.fsync(stream.fileno())
            os.replace(temporary, self.pending_path)
            directory = os.open(self.generation_root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def _attribute(self, path, write=False):
        self.cleanup.directory(path.parent)
        fd = os.open(path, (os.O_WRONLY if write else os.O_RDONLY) | os.O_NOFOLLOW)
        try:
            opened = os.fstat(fd); named = path.lstat()
            fields = ("st_dev", "st_ino", "st_mode", "st_uid", "st_gid")
            if (not stat.S_ISREG(opened.st_mode) or opened.st_uid != os.geteuid()
                    or opened.st_mode & 0o022 or opened.st_nlink != 1
                    or any(getattr(opened, key) != getattr(named, key) for key in fields)):
                raise ValueError("NVMe fixed public identity attribute is foreign")
            return fd
        except BaseException:
            os.close(fd)
            raise

    def _read(self, path):
        fd = self._attribute(path)
        try:
            raw = os.read(fd, 257)
            if len(raw) > 256:
                raise ValueError("NVMe public identity attribute exceeds its bound")
            text = raw.decode("ascii").removesuffix("\n")
            if "\n" in text or "\r" in text or "\0" in text:
                raise ValueError("NVMe public identity attribute is not one public scalar")
            return text.strip()
        finally:
            os.close(fd)

    @staticmethod
    def _valid_attribute(name, value):
        if name in ("attr_serial", "attr_model"):
            limit = 20 if name == "attr_serial" else 40
            if not isinstance(value, str) or not re.fullmatch("[ -~]{1," + str(limit) + "}", value):
                raise ValueError("NVMe public controller identity is invalid")
        elif name in ("device_uuid", "device_nguid"):
            nvme_identity_uuid(value)
        elif name == "device_eui64":
            if value is not None and (not isinstance(value, str) or not re.fullmatch("[0-9a-f]{16}", value)):
                raise ValueError("NVMe public EUI64 identity is invalid")
        else:
            raise ValueError("NVMe public identity attribute is outside its fixed allowlist")
        return value

    def _attributes(self, path, namespace=False):
        names = ("device_uuid", "device_nguid", "device_eui64") if namespace else ("attr_serial", "attr_model")
        values = {}
        for name in names:
            attribute = path / name
            value = None if name == "device_eui64" and not os.path.lexists(attribute) else self._read(attribute)
            values[name] = self._valid_attribute(name, value)
        return values

    def _volume(self, binding, device):
        proof = self.probe(binding, device)
        serial = binding["volumeUuid"].replace("-", "")
        if (not isinstance(proof, dict) or set(proof) != {"devicePath", "serial", "sizeBytes"}
                or proof["serial"] not in (serial, serial[:20])
                or proof["sizeBytes"] != binding["sizeBytes"]
                or not isinstance(proof["devicePath"], str) or not proof["devicePath"].startswith("/dev/")):
            raise ValueError("NVMe public identity DATA volume proof is not exact")
        return proof

    @staticmethod
    def probe_volume(binding, device):
        if not isinstance(device, str) or not device.startswith("/dev/") or "\n" in device or "\0" in device:
            raise ValueError("NVMe namespace device path is invalid")
        result = subprocess.run(["lsblk", "-J", "-b", "-o", "PATH,TYPE,SERIAL,SIZE,FSTYPE,MOUNTPOINTS"],
                                capture_output=True, text=True, timeout=8)
        if result.returncode or len(result.stdout) > 1024 * 1024:
            raise ValueError("NVMe DATA volume topology is unavailable")
        def flatten(items):
            for item in items:
                yield item
                yield from flatten(item.get("children") or [])
        rows = list(flatten(json.loads(result.stdout)["blockdevices"]))
        actual = os.path.realpath(device)
        matches = [row for row in rows if row.get("path") and os.path.realpath(row["path"]) == actual]
        if (len(matches) != 1 or matches[0].get("type") != "disk" or matches[0].get("fstype")
                or matches[0].get("children") or any(matches[0].get("mountpoints") or [])
                or not stat.S_ISBLK(os.stat(actual).st_mode)):
            raise ValueError("NVMe identity needs the exact unmounted raw DATA disk")
        row = matches[0]
        return {"devicePath": actual, "serial": row.get("serial"), "sizeBytes": row.get("size")}

    def _capture(self, durable):
        identities = []
        active_source = set(self.cleanup.rows(self.cleanup.previous))
        active_namespaces = {row.get("uuid") for subsystem in self.cleanup.previous.get("subsystems") or []
                             if subsystem.get("targetName") in active_source
                             for row in subsystem.get("namespaces") or []
                             if row.get("state", "Ready") not in ("Disabled", "Destroyed", "Error")}
        for subsystem in self.source_bindings["subsystems"]:
            path = self.base / "subsystems" / subsystem["nqn"]
            if not path.exists():
                if durable and subsystem["nqn"] in active_source:
                    raise ValueError("NVMe active SOURCE controller identity is unavailable")
                continue
            self.cleanup.directory(path)
            entry = {"binding": subsystem, "attributes": self._attributes(path), "namespaces": []}
            for binding in self.source_bindings["namespaces"]:
                if binding["subsystemUuid"] != subsystem["uuid"]:
                    continue
                namespace = path / "namespaces" / binding["nsid"]
                if not namespace.exists():
                    if durable and binding["uuid"] in active_namespaces:
                        raise ValueError("NVMe active SOURCE namespace identity is unavailable")
                    continue
                self.cleanup.directory(namespace)
                device = self._read(namespace / "device_path")
                entry["namespaces"].append({"binding": binding, "attributes": self._attributes(namespace, True),
                                            "device": self._volume(binding, device)})
            identities.append(entry)
        value = {"schemaVersion": 1, "kind": NVME_IDENTITY_KIND, "scope": self.scope,
                 "bootId": self.boot_id, "sourceGeneration": self.current,
                 "sourceConfigurationSha256": self.pending["beforeSha256"] if durable else nvme_identity_digest(self._canonical()),
                 "sourceBindings": self.source_bindings, "targetBindings": self.target_bindings,
                 "identities": identities}
        value["checkpointSha256"] = nvme_identity_digest(value)
        self._validate(value)
        return value

    def _validate(self, value):
        fields = {"schemaVersion", "kind", "scope", "bootId", "sourceGeneration", "sourceConfigurationSha256",
                  "sourceBindings", "targetBindings", "identities", "checkpointSha256"}
        if (not isinstance(value, dict) or set(value) != fields or type(value["schemaVersion"]) is not int
                or value["schemaVersion"] != 1 or value["kind"] != NVME_IDENTITY_KIND
                or value["scope"] != self.scope or value["bootId"] != self.boot_id
                or value["sourceGeneration"] != self.current
                or value["sourceConfigurationSha256"] != (self.pending["beforeSha256"] if self.pending else value["sourceConfigurationSha256"])
                or not re.fullmatch("[0-9a-f]{64}", str(value["sourceConfigurationSha256"]))
                or not isinstance(value["identities"], list) or len(value["identities"]) > 1024
                or nvme_identity_digest({key: item for key, item in value.items() if key != "checkpointSha256"}) != value["checkpointSha256"]):
            raise ValueError("NVMe public identity checkpoint scope/schema/hash changed")
        for bindings in (value["sourceBindings"], value["targetBindings"]):
            if not isinstance(bindings, dict) or set(bindings) != {"subsystems", "namespaces", "hosts"}:
                raise ValueError("NVMe public identity binding schema changed")
            rows = [{"uuid": row["uuid"], "targetName": row["nqn"], "namespaces": [
                {"uuid": namespace["uuid"], "lunOrNamespace": namespace["nsid"],
                 "volumeUuid": namespace["volumeUuid"], "volumeSizeBytes": namespace["sizeBytes"]}
                for namespace in bindings["namespaces"] if namespace["subsystemUuid"] == row["uuid"]],
                "hosts": [{"principal": host["principal"]} for host in bindings["hosts"]
                          if host["subsystemUuid"] == row["uuid"]]}
                for row in bindings["subsystems"]]
            if nvme_identity_bindings({"instanceUuid": self.instance, "subsystems": rows}, self.instance) != bindings:
                raise ValueError("NVMe public identity canonical binding changed")
        seen_subsystems, seen_namespaces = set(), set()
        for entry in value["identities"]:
            if (not isinstance(entry, dict) or set(entry) != {"binding", "attributes", "namespaces"}
                    or entry["binding"] not in value["sourceBindings"]["subsystems"]
                    or entry["binding"]["uuid"] in seen_subsystems
                    or set(entry["attributes"]) != {"attr_serial", "attr_model"}
                    or not isinstance(entry["namespaces"], list)):
                raise ValueError("NVMe SOURCE controller checkpoint is foreign")
            seen_subsystems.add(entry["binding"]["uuid"])
            for name, attribute in entry["attributes"].items():
                self._valid_attribute(name, attribute)
            for namespace in entry["namespaces"]:
                if (not isinstance(namespace, dict) or set(namespace) != {"binding", "attributes", "device"}
                        or namespace["binding"] not in value["sourceBindings"]["namespaces"]
                        or namespace["binding"]["subsystemUuid"] != entry["binding"]["uuid"]
                        or namespace["binding"]["uuid"] in seen_namespaces
                        or set(namespace["attributes"]) != {"device_uuid", "device_nguid", "device_eui64"}):
                    raise ValueError("NVMe SOURCE namespace checkpoint is foreign")
                seen_namespaces.add(namespace["binding"]["uuid"])
                for name, attribute in namespace["attributes"].items():
                    self._valid_attribute(name, attribute)
                proof = namespace["device"]
                serial = namespace["binding"]["volumeUuid"].replace("-", "")
                if (not isinstance(proof, dict) or set(proof) != {"devicePath", "serial", "sizeBytes"}
                        or proof["serial"] not in (serial, serial[:20])
                        or proof["sizeBytes"] != namespace["binding"]["sizeBytes"]
                        or not isinstance(proof["devicePath"], str) or not proof["devicePath"].startswith("/dev/")):
                    raise ValueError("NVMe SOURCE public volume checkpoint changed")

    def _subsystem_binding(self, subsystem):
        matches = [row for row in self.target_bindings["subsystems"]
                   if row["uuid"] == subsystem.get("uuid") and row["nqn"] == subsystem.get("targetName")]
        if len(matches) != 1:
            raise ValueError("NVMe restore controller is outside its original target intent")
        return matches[0]

    def _restore(self, path, expected, namespace=False, binding_guard=None):
        self.guard()
        actual = self._attributes(path, namespace)
        changes = [(name, value) for name, value in expected.items() if actual[name] != value]
        if not changes:
            return
        if namespace:
            if self._read(path / "enable") != "0":
                raise ValueError("NVMe enabled namespace identity differs from its checkpoint")
        else:
            if path.name in self.existing_subsystems or any(
                    child.is_dir() and self._read(child / "enable") == "1"
                    for child in (path / "namespaces").iterdir()):
                raise ValueError("NVMe existing controller identity drift requires owned reconciliation")
            for port in (self.base / "ports").iterdir():
                if os.path.lexists(port / "subsystems" / path.name):
                    raise ValueError("NVMe controller identity cannot change after port exposure")
        # Validate every presence/value before the first metadata write.
        if any(value is None or actual[name] is None for name, value in changes):
            raise ValueError("NVMe optional identity attribute presence changed")
        for name, value in changes:
            self.guard()
            if namespace and self._read(path / "enable") != "0":
                raise ValueError("NVMe namespace became enabled before identity restore")
            if binding_guard is not None:
                binding_guard()
            fd = self._attribute(path / name, write=True)
            try:
                encoded = value.encode("ascii")
                if self.write(fd, encoded) != len(encoded):
                    raise ValueError("NVMe public identity write was incomplete")
            finally:
                os.close(fd)
            if self._read(path / name) != value:
                raise ValueError("NVMe public identity readback differed")
        if self._attributes(path, namespace) != expected:
            raise ValueError("NVMe restored public identity did not verify")
        if binding_guard is not None:
            binding_guard()

    def restore_subsystem(self, subsystem):
        binding = self._subsystem_binding(subsystem)
        expected = next((entry for entry in self.checkpoint["identities"]
                         if entry["binding"]["uuid"] == binding["uuid"]), None)
        if expected is not None:
            path = self.base / "subsystems" / binding["nqn"]
            self.cleanup.directory(path)
            self._restore(path, expected["attributes"])

    def restore_namespace(self, subsystem, namespace, backing_path):
        parent = self._subsystem_binding(subsystem)
        number = namespace.get("lunOrNamespace", 1)
        if (type(number) not in (int, str) or not re.fullmatch(r"[0-9]{1,5}", str(number))
                or not 1 <= int(number) <= 65535):
            raise ValueError("NVMe restore namespace ID is not canonical")
        candidates = [row for row in self.target_bindings["namespaces"]
                      if row["uuid"] == namespace.get("uuid") and row["subsystemUuid"] == parent["uuid"]
                      and row["nqn"] == parent["nqn"] and row["nsid"] == str(int(number))
                      and row["volumeUuid"] == namespace.get("volumeUuid")
                      and row["sizeBytes"] == namespace.get("volumeSizeBytes")]
        if len(candidates) != 1:
            raise ValueError("NVMe restore namespace is outside its original target intent")
        binding = candidates[0]
        self._volume(binding, backing_path)
        expected = next((item for entry in self.checkpoint["identities"] for item in entry["namespaces"]
                         if item["binding"]["uuid"] == binding["uuid"]
                         and item["binding"]["subsystemUuid"] == parent["uuid"]), None)
        if expected is not None:
            path = self.base / "subsystems" / parent["nqn"] / "namespaces" / binding["nsid"]
            self.cleanup.directory(path)
            def bound():
                if self._read(path / "device_path") != backing_path:
                    raise ValueError("NVMe namespace DATA path changed before public identity restore")
                self._volume(binding, backing_path)
            bound()
            self._restore(path, expected["attributes"], True, bound)

