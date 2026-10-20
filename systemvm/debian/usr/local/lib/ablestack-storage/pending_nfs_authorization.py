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

"""A temporary NFS-unit grant for the exact live legacy configuration writer."""
import contextlib
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid as pending_local_uuid
sys.path.insert(0, "/usr/local/lib/ablestack-storage")
from config_generation import Generation as PendingNfsGeneration, read_json as pending_nfs_read
from rendered_generation import RenderedGeneration
from template_maintenance import Maintenance

# LOCAL-only readers reuse the reviewed SERVICE cipher bounds and metadata checks.
def pending_local_source_json(value):

    def unique(pairs):
        result = {}
        for (key, item) in pairs:
            if key in result:
                raise ValueError('SERVICE cipher record has duplicate fields')
            result[key] = item
        return result

    def invalid(value):
        raise ValueError('SERVICE cipher record has an invalid JSON number')
    return json.loads(value, object_pairs_hook=unique, parse_constant=invalid)

def pending_local_source_read(path):
    parent = path.parent.lstat()
    info = path.lstat()
    fields = ('st_dev', 'st_ino', 'st_uid', 'st_gid', 'st_mode', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 18 or (not stat.S_ISREG(info.st_mode)) or (info.st_uid != os.geteuid()) or (stat.S_IMODE(info.st_mode) != 384) or (info.st_size > 16 * 1024 * 1024):
        raise ValueError('SERVICE encrypted source checkpoint is not protected')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(descriptor)
        if any((getattr(opened, key) != getattr(info, key) for key in fields)):
            raise ValueError('SERVICE encrypted checkpoint changed during open')
        with os.fdopen(descriptor, 'rb', closefd=False) as handle:
            raw = handle.read(16 * 1024 * 1024 + 1)
        after = os.fstat(descriptor)
        named = path.lstat()
        if len(raw) > 16 * 1024 * 1024 or any((getattr(opened, key) != getattr(after, key) or getattr(opened, key) != getattr(named, key) for key in fields)):
            raise ValueError('SERVICE encrypted checkpoint changed during read')
        return pending_local_source_json(raw)
    finally:
        os.close(descriptor)

class PendingNfsAuthorization:
    mode = "NATIVE_PENDING_NFS_APPLY"

    def __init__(self, generation=None, rendered=None, maintenance=None, root=None, configuration_root=None, protocol="NFS", registry=None):
        if protocol not in ("NFS", "SMB"):
            raise ValueError("Pending unit authorization protocol is not closed")
        self.protocol = protocol
        self.mode = {"NFS": "NATIVE_PENDING_NFS_APPLY", "SMB": "NATIVE_PENDING_SMB_APPLY"}[protocol]
        self.generation = generation or PendingNfsGeneration()
        self.rendered = rendered or RenderedGeneration()
        self.maintenance = maintenance
        self.root = Path(root or "/run/ablestack-storage/rendered-authorization")
        self.path = self.root / ("pending-" + protocol.lower() + ".json")
        self.configuration_root = Path(configuration_root or ("/etc/ganesha/ablestack-storage" if protocol == "NFS" else "/etc/samba"))
        self.registry = Path(registry or "/etc/ablestack-storage/smb-endpoint-listeners")

    def state(self, scope):
        # Forward/public grants remain PREPARED only.
        return self._writer_state(scope, ("PREPARED",))

    def _writer_state(self, scope, phases):
        if not isinstance(scope, dict) or set(scope) != {"instanceUuid", "operationUuid", "revision"}:
            raise ValueError("Pending NFS operation scope is not exact")
        scope = self.generation.request(scope)
        pending = self.generation.scoped(scope)
        info = self.generation.pending.lstat()
        if stat.S_IMODE(info.st_mode) != 0o600 or pending.get("phase") not in phases:
            raise ValueError("Pending NFS writer is not protected PREPARED")
        before = pending.get("beforeSha256")
        if not isinstance(before, str) or not re.fullmatch("[0-9a-f]{64}", before):
            raise ValueError("Pending NFS source digest is absent")
        current = self.generation.status()
        if current.get("pendingOperationUuid") != scope["operationUuid"]:
            raise ValueError("Pending NFS writer changed")
        rendered = self.rendered.status()
        maintenance = (self.maintenance or Maintenance()).status()
        if rendered.get("current") is not None or rendered.get("activation") is not None or maintenance.get("bootHeld") is not False:
            raise ValueError("Pending NFS cannot borrow rendered/ROOT/SERVICE authority")
        return scope, pending, current["configurationSha256"]

    def local_source_state(self, scope, unit):
        # This is an internal consumer of the existing opaque LOCAL import.
        # No JSON request or generic grant accepts a recovery flag or mode.
        if self.protocol != "SMB":
            raise ValueError("LOCAL source resume is restricted to SMB")
        scope, pending, digest = self._writer_state(scope, ("PREPARED", "VERIFIED"))
        current = self.generation.status()
        if current.get("generation") != pending.get("previous") or digest != pending["beforeSha256"]:
            raise ValueError("LOCAL source resume is outside its exact original SOURCE")
        boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        identity = str(pending_local_uuid.UUID(bytes=hashlib.md5(
            ("local-source-checkpoint:" + scope["operationUuid"]).encode()).digest(), version=3))
        root = self.generation.root.parent / "smb-local-source-checkpoints"
        parent = root.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or stat.S_IMODE(parent.st_mode) != 0o700:
            raise ValueError("LOCAL source resume checkpoint directory is not protected")
        records = {}
        for suffix in ("journal", "cipher"):
            path = root / (identity + "-" + suffix + ".json")
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
                raise ValueError("LOCAL source resume checkpoint file is not protected")
            records[suffix] = pending_local_source_read(path)
        journal, record = records["journal"], records["cipher"]
        local_scope = {**scope, "localCheckpointUuid": identity}
        common = {**local_scope, "sourceGeneration": pending.get("previous"),
                  "sourceConfigurationSha256": pending["beforeSha256"], "expectedBootId": boot}
        if (not isinstance(journal, dict) or journal.get("scope") != local_scope or journal.get("common") != common
                or journal.get("phase") != "RESUMING" or journal.get("imported") is not True
                or not isinstance(journal.get("importedFingerprints"), dict)
                or not isinstance(journal.get("importedStoppedDatabases"), dict)
                or not isinstance(record, dict) or type(record.get("schemaVersion")) is not int or record["schemaVersion"] != 1
                or record.get("kind") != "LOCAL_SOURCE_IDENTITY_CHECKPOINT" or record.get("scope") != local_scope
                or record.get("common") != common
                or journal.get("importedCheckpointSha256") != hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()):
            raise ValueError("LOCAL source resume has no original verified opaque import")
        desired = record.get("sourceDesiredState")
        if (not isinstance(desired, dict) or set(desired) != set(self.generation.paths)
                or any(value is not None and not isinstance(value, dict) for value in desired.values())
                or hashlib.sha256(json.dumps(desired, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest() != pending["beforeSha256"]):
            raise ValueError("LOCAL source resume frozen seven-file fingerprint changed")
        configuration, _ = self.configuration(unit)
        prior = journal.get("prior") or {}
        if (unit not in {row.get("unit") for row in prior.get("units", [])}
                or configuration != record.get("sourceConfigurationFileSha256")
                or configuration != prior.get("configurationSha256")):
            raise ValueError("LOCAL source resume unit/configuration is outside original SOURCE")
        evidence = {"localSourceCheckpointSha256": hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest(),
                    "localSourceJournalSha256": hashlib.sha256(json.dumps(journal, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()}
        return scope, pending, digest, evidence

    @contextlib.contextmanager
    def local_source_grant(self, scope, unit):
        scope, pending, digest, evidence = self.local_source_state(scope, unit)
        with self._grant(scope, pending, digest, unit, "NATIVE_PENDING_SMB_LOCAL_SOURCE_RESUME", evidence):
            yield

    def lock(self, pid):
        path = Path("/proc") / str(pid)
        fd = path / "fd/9"
        lock_path = Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE", "/run/ablestack-storage/desired-writer.lock"))
        if lock_path.is_symlink() or lock_path.parent.is_symlink():
            raise ValueError("Pending NFS writer lock is linked")
        named = lock_path.lstat()
        opened = fd.stat()
        if (not stat.S_ISREG(named.st_mode) or named.st_uid != os.geteuid()
                or stat.S_IMODE(named.st_mode) != 0o600
                or (opened.st_dev, opened.st_ino) != (named.st_dev, named.st_ino)
                or os.readlink(fd) != str(lock_path)):
            raise ValueError("Pending NFS inherited lock changed")
        locks = (path / "fdinfo/9").read_text()
        matched = False
        for line in locks.splitlines():
            value = re.search(r"FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+([0-9a-f]+):([0-9a-f]+):([0-9]+)\s+0\s+EOF", line, re.I)
            if value and (int(value[1], 16), int(value[2], 16), int(value[3])) == (os.major(opened.st_dev), os.minor(opened.st_dev), opened.st_ino):
                matched = True
        if not matched:
            raise ValueError("Pending NFS FD9 does not hold its actual FLOCK")
        ticks = (path / "stat").read_text().rpartition(")")[2].split()[19]
        return opened, ticks

    def configuration(self, unit):
        registry_identity = None
        if self.protocol == "NFS":
            if not isinstance(unit, str) or not re.fullmatch(r"ablestack-storage-ganesha@[A-Za-z0-9_.-]+\.service", unit):
                raise ValueError("Pending NFS unit name is not closed")
            key = unit.split("@", 1)[1][:-8]
        else:
            if unit not in ("smbd.service", "nmbd.service"):
                if not isinstance(unit, str) or not re.fullmatch(r"ablestack-storage-smb@[0-9a-f]{24}\.service", unit):
                    raise ValueError("Pending SMB unit name is not closed")
                key = unit.split("@", 1)[1][:-8]
                record_path = self.registry / (key + ".json")
                row = pending_nfs_read(record_path)
                if (not isinstance(row, dict) or set(row) != {"listenIp", "port"} or type(row["port"]) is not int
                        or not 1 <= row["port"] <= 65535 or not isinstance(row["listenIp"], str)):
                    raise ValueError("Pending SMB endpoint registry is not exact")
                address = str(ipaddress.ip_address(row["listenIp"]))
                if address != row["listenIp"] or hashlib.sha256((address + ":" + str(row["port"])).encode()).hexdigest()[:24] != key:
                    raise ValueError("Pending SMB endpoint unit differs from its protected registry")
                info = record_path.lstat()
                registry_identity = [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns,
                                     hashlib.sha256(json.dumps(row, sort_keys=True, separators=(",", ":")).encode()).hexdigest()]
            key = "smb"
        parent = self.configuration_root.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
            raise ValueError("Pending NFS endpoint parent is not protected")
        path = self.configuration_root / (key + ".conf")
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or info.st_size > 8 * 1024 * 1024:
            raise ValueError("Pending NFS endpoint configuration is not protected")
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            opened = os.fstat(descriptor)
            fields = ("st_dev", "st_ino", "st_mode", "st_size", "st_mtime_ns", "st_ctime_ns")
            if any(getattr(opened, k) != getattr(info, k) for k in fields):
                raise ValueError("Pending NFS endpoint changed while opening")
            data = os.read(descriptor, 8 * 1024 * 1024 + 1)
            if any(getattr(os.fstat(descriptor), k) != getattr(opened, k) for k in fields):
                raise ValueError("Pending NFS endpoint changed while reading")
            identity = [opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns]
            if self.protocol == "SMB":
                identity.append(registry_identity)
            return hashlib.sha256(data).hexdigest(), identity
        finally:
            os.close(descriptor)

    def validate_payload(self, payload):
        if "operationScope" not in payload:
            if self.generation.status().get("pendingOperationUuid"):
                raise ValueError("Pending NFS writer requires its explicit operation scope")
            return False
        scope = payload["operationScope"]
        if not isinstance(scope, dict) or payload.get("instanceUuid") != scope.get("instanceUuid"):
            raise ValueError("Pending NFS payload instance differs from its writer")
        self.state(scope)
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
            raise ValueError("Pending NFS requires its inherited FD9")
        self.lock(os.getpid())
        return True

    @contextlib.contextmanager
    def grant(self, payload, unit):
        if not self.validate_payload(payload):
            yield
            return
        scope = payload["operationScope"]
        scope, pending, digest = self.state(scope)
        with self._grant(scope, pending, digest, unit, self.mode, {}):
            yield

    @contextlib.contextmanager
    def _grant(self, scope, pending, digest, unit, mode, evidence):
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
            raise ValueError("Pending NFS requires its inherited FD9")
        opened, ticks = self.lock(os.getpid())
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        directory = self.root.lstat()
        if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.geteuid() or stat.S_IMODE(directory.st_mode) != 0o700:
            raise ValueError("Pending NFS grant directory is not protected")
        configuration_sha, configuration_identity = self.configuration(unit)
        proof = {"mode": mode, "scope": scope, "beforeSha256": pending["beforeSha256"],
                 "configurationSha256": digest, "unit": unit, "configurationFileSha256": configuration_sha, "configurationFileIdentity": configuration_identity,
                 "pid": os.getpid(), "startTicks": ticks, "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                 "lockDevice": opened.st_dev, "lockInode": opened.st_ino, **evidence}
        raw_fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            fd = fcntl.fcntl(raw_fd, fcntl.F_DUPFD_CLOEXEC, 16)
        finally:
            os.close(raw_fd)
        identity = os.fstat(fd)
        try:
            with os.fdopen(fd, "w", closefd=False) as handle:
                json.dump(proof, handle, sort_keys=True); handle.flush(); os.fsync(handle.fileno())
            yield
        finally:
            try:
                current = self.path.lstat()
                if (current.st_dev, current.st_ino) != (identity.st_dev, identity.st_ino) or pending_nfs_read(self.path) != proof:
                    raise ValueError("Pending NFS grant was replaced; preserving it")
                self.path.unlink()
            except FileNotFoundError:
                raise ValueError("Pending NFS grant disappeared")
            finally:
                os.close(fd)

    def authorized(self, unit, maintenance, rendered):
        try:
            if maintenance.get("bootHeld") is not False or rendered.get("current") is not None or rendered.get("activation") is not None:
                return False
            directory = self.root.lstat()
            record = self.path.lstat()
            if (not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.geteuid() or stat.S_IMODE(directory.st_mode) != 0o700
                    or not stat.S_ISREG(record.st_mode) or record.st_uid != os.geteuid() or stat.S_IMODE(record.st_mode) != 0o600):
                return False
            proof = pending_nfs_read(self.path)
            fields = {"mode", "scope", "beforeSha256", "configurationSha256", "unit", "configurationFileSha256", "configurationFileIdentity", "pid", "startTicks", "bootId", "lockDevice", "lockInode"}
            local = isinstance(proof, dict) and self.protocol == "SMB" and proof.get("mode") == "NATIVE_PENDING_SMB_LOCAL_SOURCE_RESUME"
            if local:
                fields |= {"localSourceCheckpointSha256", "localSourceJournalSha256"}
            if not isinstance(proof, dict) or set(proof) != fields:
                return False
            if (not local and proof["mode"] != self.mode) or proof["unit"] != unit or type(proof["pid"]) is not int or proof["pid"] < 1:
                return False
            if local:
                scope, pending, digest, evidence = self.local_source_state(proof["scope"], unit)
                if any(proof.get(key) != value for key, value in evidence.items()):
                    return False
            else:
                scope, pending, digest = self.state(proof["scope"])
            configuration_sha, configuration_identity = self.configuration(unit)
            if (proof["beforeSha256"] != pending["beforeSha256"] or proof["configurationSha256"] != digest
                    or proof["bootId"] != Path("/proc/sys/kernel/random/boot_id").read_text().strip()
                    or proof["configurationFileSha256"] != configuration_sha
                    or proof["configurationFileIdentity"] != configuration_identity):
                return False
            opened, ticks = self.lock(proof["pid"])
            return (ticks, opened.st_dev, opened.st_ino) == (proof["startTicks"], proof["lockDevice"], proof["lockInode"])
        except (OSError, ValueError, KeyError, IndexError, TypeError):
            return False
