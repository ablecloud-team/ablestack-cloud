# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http: #www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


"""Durable desired-state generations. No credential material is copied into the journal."""
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import time
import uuid

MAX_BYTES = 8 * 1024 * 1024

def protected_directory(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise ValueError("Generation directory is not protected")
    return path

def atomic_json(path, value):
    protected_directory(path.parent)
    fd, temporary = tempfile.mkstemp(prefix=".generation-", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as handle:
            json.dump(value, handle, sort_keys=True)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def read_json(path):
    try:
        info = path.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_size > MAX_BYTES or info.st_mode & 0o022:
        raise ValueError("Generation input is not a protected regular file")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        if (opened.st_dev, opened.st_ino, opened.st_size) != (info.st_dev, info.st_ino, info.st_size):
            raise ValueError("Generation input changed while opening")
        with os.fdopen(fd, "rb", closefd=False) as handle:
            value = handle.read(MAX_BYTES + 1)
        if len(value) > MAX_BYTES:
            raise ValueError("Generation input exceeds its size limit")
        return json.loads(value)
    finally:
        os.close(fd)

def redact(value):
    if isinstance(value, dict):
        return {key: redact(item) for key, item in value.items()
                if key not in ("lastApply", "lastApplied", "observedAt", "generatedEpoch")
                and not any(word in key.lower() for word in ("password", "secret", "dhchapkey", "dhchapctrlkey", "privatekey", "keytab", "capsule"))}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value

class Generation:
    def __init__(self, state=None, configuration=None):
        self.root = Path(state or os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR", "/var/lib/ablestack-storage/config-generations"))
        self.config = Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT", "/etc/ablestack-storage"))
        self.current = self.root / "current.json"
        self.pending = self.root / "pending.json"

    def files(self):
        paths = ("desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json",
                 "iscsi-targets.json", "nvmeof-subsystems.json", "posix-directory-policies.json",
                 "network-endpoints.json", "sharedfs-network.json")
        return {name: redact(read_json(self.config / name)) for name in paths}

    def digest(self):
        return hashlib.sha256(json.dumps(self.files(), sort_keys=True, separators=(",", ":")).encode()).hexdigest()

    def status(self):
        current = read_json(self.current) or {}
        pending = read_json(self.pending)
        digest = self.digest()
        return {"success": True, "generationSupported": True, "runtimeRevision": current.get("revision", 0),
                "generation": current, "pendingOperationUuid": pending.get("operationUuid") if pending else None,
                "generationStatus": "PENDING" if pending else ("IN_SYNC" if current and current.get("configurationSha256") == digest else "UNVERIFIED"),
                "configurationSha256": digest}

    def request(self, request):
        result = dict(request)
        result["instanceUuid"] = str(uuid.UUID(request["instanceUuid"]))
        result["operationUuid"] = str(uuid.UUID(request["operationUuid"]))
        revision = request["revision"]
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
            raise ValueError("Invalid generation revision")
        return result

    def scoped(self, request):
        pending = read_json(self.pending)
        if not pending or any(pending[key] != request[key] for key in ("instanceUuid", "operationUuid", "revision")):
            raise ValueError("Generation operation scope changed")
        return pending

    def execute(self, action, request=None):
        if action == "status":
            return self.status()
        protected_directory(self.root)
        request = self.request(request)
        if action in ("adopt", "align"):
            if read_json(self.pending) is not None:
                raise ValueError("Pending generation must be recovered before ROOT transfer")
            previous = request.get("previousGeneration")
            if not isinstance(previous, dict) or previous.get("instanceUuid") != request["instanceUuid"]:
                raise ValueError("ROOT generation source scope changed")
            old_revision = previous.get("revision")
            if isinstance(old_revision, bool) or not isinstance(old_revision, int) or not 0 < old_revision < request["revision"]:
                raise ValueError("ROOT generation source revision is invalid")
            str(uuid.UUID(previous["operationUuid"]))
            if previous.get("configurationSha256") != self.digest():
                raise ValueError("ROOT transfer desired configuration was not exactly reconciled")
            current = read_json(self.current) or {}
            if current:
                if current == previous:
                    return self.status()
                if action != "align" or current.get("instanceUuid") != request["instanceUuid"] or current.get("revision", 0) >= old_revision:
                    raise ValueError("ROOT generation alignment cannot replace an equal or later revision")
                if current != request.get("expectedPreviousGeneration"):
                    raise ValueError("Retained ROOT generation changed before alignment")
            atomic_json(self.current, previous)
            return self.status()
        if action == "begin":
            pending = read_json(self.pending)
            if pending:
                self.scoped(request)
                return {"success": True, "generationSupported": True, "phase": pending["phase"], "previous": pending["previous"]}
            previous = read_json(self.current) or {}
            if previous and (previous["instanceUuid"] != request["instanceUuid"] or request["revision"] <= previous["revision"]):
                raise ValueError("Stale generation revision")
            if previous and previous["configurationSha256"] != self.digest():
                raise ValueError("Current generation has unresolved configuration drift")
            pending = {key: request[key] for key in ("instanceUuid", "operationUuid", "revision")}
            pending.update({"phase": "PREPARED", "previous": previous, "startedAt": time.time(),
                            "beforeSha256": self.digest()})
            atomic_json(self.pending, pending)
            return {"success": True, "generationSupported": True, "phase": "PREPARED", "previous": previous}
        if action == "finish" and read_json(self.pending) is None:
            current = read_json(self.current) or {}
            if current.get("operationUuid") == request["operationUuid"] and current.get("revision") == request["revision"]:
                return self.status()
            raise ValueError("Generation finalization scope changed")
        pending = self.scoped(request)
        if action == "verify":
            pending.update({"phase": "VERIFIED", "configurationSha256": self.digest(), "verifiedAt": time.time()})
            atomic_json(self.root / (request["operationUuid"] + ".json"),
                        {**pending, "desired": self.files()})
            atomic_json(self.pending, pending)
            return {"success": True, "generationSupported": True, "runtimeRevision": request["revision"],
                    "phase": "VERIFIED", "configurationSha256": pending["configurationSha256"]}
        if action == "commit":
            if pending["phase"] != "VERIFIED" or pending["configurationSha256"] != self.digest():
                raise ValueError("Generation was not verified or changed before commit")
            current = {key: pending[key] for key in ("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt")}
            atomic_json(self.current, current)
            pending["phase"] = "COMMITTED"
            atomic_json(self.pending, pending)
            return {"success": True, "generationSupported": True, "runtimeRevision": request["revision"], "phase": "COMMITTED"}
        if action == "finish":
            current = read_json(self.current) or {}
            if pending["phase"] != "COMMITTED" or current.get("operationUuid") != request["operationUuid"]:
                raise ValueError("Generation commit is not durable")
            self.pending.unlink()
            return self.status()
        if action == "rollback":
            if self.digest() != pending["beforeSha256"]:
                raise ValueError("Previous desired state was not fully restored")
            atomic_json(self.current, pending["previous"])
            pending["phase"] = "ROLLED_BACK"
            atomic_json(self.root / (request["operationUuid"] + ".json"), pending)
            self.pending.unlink()
            return self.status()
        raise ValueError("Unsupported generation action")
