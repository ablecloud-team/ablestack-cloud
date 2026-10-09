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
import json
import os
from pathlib import Path
import re
import stat
import sys
sys.path.insert(0, "/usr/local/lib/ablestack-storage")
from config_generation import Generation as PendingNfsGeneration, read_json as pending_nfs_read
from rendered_generation import RenderedGeneration
from template_maintenance import Maintenance

class PendingNfsAuthorization:
    mode = "NATIVE_PENDING_NFS_APPLY"

    def __init__(self, generation=None, rendered=None, maintenance=None, root=None, configuration_root=None):
        self.generation = generation or PendingNfsGeneration()
        self.rendered = rendered or RenderedGeneration()
        self.maintenance = maintenance or Maintenance()
        self.root = Path(root or "/run/ablestack-storage/rendered-authorization")
        self.path = self.root / "pending-nfs.json"
        self.configuration_root = Path(configuration_root or "/etc/ganesha/ablestack-storage")

    def state(self, scope):
        if not isinstance(scope, dict) or set(scope) != {"instanceUuid", "operationUuid", "revision"}:
            raise ValueError("Pending NFS operation scope is not exact")
        scope = self.generation.request(scope)
        pending = self.generation.scoped(scope)
        info = self.generation.pending.lstat()
        if stat.S_IMODE(info.st_mode) != 0o600 or pending.get("phase") != "PREPARED":
            raise ValueError("Pending NFS writer is not protected PREPARED")
        before = pending.get("beforeSha256")
        if not isinstance(before, str) or not re.fullmatch("[0-9a-f]{64}", before):
            raise ValueError("Pending NFS source digest is absent")
        current = self.generation.status()
        if current.get("pendingOperationUuid") != scope["operationUuid"]:
            raise ValueError("Pending NFS writer changed")
        rendered = self.rendered.status()
        maintenance = self.maintenance.status()
        if rendered.get("current") is not None or rendered.get("activation") is not None or maintenance.get("bootHeld") is not False:
            raise ValueError("Pending NFS cannot borrow rendered/ROOT/SERVICE authority")
        return scope, pending, current["configurationSha256"]

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
        if not isinstance(unit, str) or not re.fullmatch(r"ablestack-storage-ganesha@[A-Za-z0-9_.-]+\.service", unit):
            raise ValueError("Pending NFS unit name is not closed")
        key = unit.split("@", 1)[1][:-8]
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
            return hashlib.sha256(data).hexdigest(), [opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns]
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
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
            raise ValueError("Pending NFS requires its inherited FD9")
        opened, ticks = self.lock(os.getpid())
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        directory = self.root.lstat()
        if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.geteuid() or stat.S_IMODE(directory.st_mode) != 0o700:
            raise ValueError("Pending NFS grant directory is not protected")
        configuration_sha, configuration_identity = self.configuration(unit)
        proof = {"mode": self.mode, "scope": scope, "beforeSha256": pending["beforeSha256"],
                 "configurationSha256": digest, "unit": unit, "configurationFileSha256": configuration_sha, "configurationFileIdentity": configuration_identity,
                 "pid": os.getpid(), "startTicks": ticks, "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                 "lockDevice": opened.st_dev, "lockInode": opened.st_ino}
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
            if not isinstance(proof, dict) or set(proof) != {"mode", "scope", "beforeSha256", "configurationSha256", "unit", "configurationFileSha256", "configurationFileIdentity", "pid", "startTicks", "bootId", "lockDevice", "lockInode"}:
                return False
            if proof["mode"] != self.mode or proof["unit"] != unit or type(proof["pid"]) is not int or proof["pid"] < 1:
                return False
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
