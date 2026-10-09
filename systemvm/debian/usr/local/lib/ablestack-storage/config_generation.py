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
    parent = path.parent.lstat()
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
        raise ValueError("Generation parent directory is not protected")
    parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        pinned_parent = os.fstat(parent_fd)
        if (pinned_parent.st_dev, pinned_parent.st_ino, pinned_parent.st_uid, pinned_parent.st_mode) != (parent.st_dev, parent.st_ino, parent.st_uid, parent.st_mode):
            raise ValueError("Generation parent changed while opening")
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
    finally:
        os.close(parent_fd)
    try:
        opened = os.fstat(fd)
        if (opened.st_dev, opened.st_ino, opened.st_uid, opened.st_gid, opened.st_mode, opened.st_size) != (info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode, info.st_size):
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
    paths = ("desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json",
             "iscsi-targets.json", "nvmeof-subsystems.json", "posix-directory-policies.json",
             "network-endpoints.json", "sharedfs-network.json")

    def __init__(self, state=None, configuration=None):
        self.root = Path(state or os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR", "/var/lib/ablestack-storage/config-generations"))
        self.config = Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT", "/etc/ablestack-storage"))
        self.current = self.root / "current.json"
        self.pending = self.root / "pending.json"

    def files(self):
        return {name: redact(read_json(self.config / name)) for name in self.paths}

    def digest(self):
        return hashlib.sha256(json.dumps(self.files(), sort_keys=True, separators=(",", ":")).encode()).hexdigest()

    def status(self):
        current = read_json(self.current) or {}
        pending = read_json(self.pending)
        digest = self.digest()
        return {"success": True, "generationSupported": True, "runtimeRevision": current.get("revision", 0),
                "generation": current, "pendingOperationUuid": pending.get("operationUuid") if pending else None,
                "generationStatus": "PENDING" if pending else ("IN_SYNC" if current and current.get("configurationSha256") == digest else "UNVERIFIED"),
                "configurationSha256": digest, "configurationDesiredState": self.files(),
                "bootId":str(uuid.UUID(Path("/proc/sys/kernel/random/boot_id").read_text().strip()))}

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

    def canonical_desired(self, desired):
        if not isinstance(desired, dict) or set(desired) != set(self.paths) or redact(desired) != desired:
            raise ValueError("Desired state must contain the exact nonsecret configuration allowlist")
        if any(value is not None and not isinstance(value, dict) for value in desired.values()):
            raise ValueError("Desired files must be JSON objects or absent")
        content = json.dumps(desired, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
        if len(content) > MAX_BYTES:
            raise ValueError("Desired state exceeds its size limit")
        return hashlib.sha256(content).hexdigest()

    def frozen(self, request):
        # Immutable verification artifacts are the authority for canonical file
        # presence. Observed/live desired files may already be partially applied.
        artifact = read_json(self.root / (request["operationUuid"] + ".json"))
        if not isinstance(artifact, dict) or artifact.get("phase") != "VERIFIED":
            raise ValueError("Frozen verified generation is unavailable")
        if any(artifact.get(key) != request[key] for key in ("instanceUuid", "operationUuid", "revision")):
            raise ValueError("Frozen generation scope changed")
        desired = artifact.get("desired")
        checksum = self.canonical_desired(desired)
        if checksum != artifact.get("configurationSha256") or isinstance(artifact.get("verifiedAt"), bool) or not isinstance(artifact.get("verifiedAt"), (int, float)):
            raise ValueError("Frozen generation desired state was not exactly verified")
        generation = {key: artifact[key] for key in ("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt")}
        return {"success": True, "generationSupported": True, "frozen": True,
                "generation": generation, "configurationSha256": checksum, "configurationDesiredState": desired}

    def replace_desired(self, desired):
        protected_directory(self.config)
        for name in self.paths:
            protected_directory((self.config / name).parent)
        previous = {name: read_json(self.config / name) for name in self.paths}
        def write(values):
            for name in sorted(self.paths):
                path = self.config / name
                if values[name] is None:
                    path.unlink(missing_ok=True)
                    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                    try:
                        os.fsync(descriptor)
                    finally:
                        os.close(descriptor)
                else:
                    atomic_json(path, values[name])
        try:
            write(desired)
            if self.digest() != self.canonical_desired(desired):
                raise ValueError("Desired state changed while normalizing")
        except Exception:
            write(previous)
            raise

    def initial_source(self, request, restoring=False):
        allowed = {"instanceUuid", "operationUuid", "revision", "configurationDesiredState"}
        if restoring:
            allowed.add("previousGeneration")
        if set(request) != allowed or restoring and request.get("previousGeneration") != {}:
            raise ValueError("Initial source accepts only its exact nonsecret request")
        request = self.request(request)
        pending = self.scoped(request)
        if (pending.get("phase") != "PREPARED" or pending.get("previous") != {}
                or read_json(self.current) not in (None, {})):
            raise ValueError("Initial source requires its original PREPARED writer without a generation")
        checksum = self.canonical_desired(request["configurationDesiredState"])
        if checksum != pending.get("beforeSha256"):
            raise ValueError("Initial source differs from the protected pending source digest")
        return pending, checksum

    def frozen_initial(self, request):
        pending, checksum = self.initial_source(request)
        return {"success": True, "generationSupported": True, "frozen": True,
                "initialSourceVerified": True, "scope": {key: pending[key] for key in ("instanceUuid", "operationUuid", "revision")},
                "generation": {}, "configurationSha256": checksum,
                "configurationDesiredState": request["configurationDesiredState"]}

    def restore(self, request):
        pending = self.scoped(request)
        if request.get("previousGeneration") == {}:
            pending, checksum = self.initial_source(request, restoring=True)
            self.replace_desired(request["configurationDesiredState"])
            return {"success": True, "generationSupported": True, "canonicalRestored": True,
                    "configurationSha256": checksum, "generationAdvanced": False,
                    "pendingOperationUuid": pending["operationUuid"]}

        previous = request.get("previousGeneration")
        if not previous or pending.get("previous") != previous:
            raise ValueError("Canonical restore previous generation changed")
        source = self.request(previous)
        if source["instanceUuid"] != request["instanceUuid"] or source["revision"] >= request["revision"]:
            raise ValueError("Canonical restore source scope changed")
        checksum = self.canonical_desired(request.get("configurationDesiredState"))
        if checksum != pending.get("beforeSha256") or checksum != previous.get("configurationSha256"):
            raise ValueError("Canonical restore differs from the pending writer's frozen source")
        # Manager must first compare the DB semantics and verify real runtime.
        # This normalizes only fixed declarative files; rollback remains a
        # separate verified action. Daemons, secrets, DATA and generation
        # pointers are never changed here.
        self.replace_desired(request["configurationDesiredState"])
        return {"success": True, "generationSupported": True, "canonicalRestored": True,
                "configurationSha256": checksum, "generationAdvanced": False,
                "pendingOperationUuid": pending["operationUuid"]}

    def seed(self, request):
        if request.get("sourceKind") != "INTERNAL_ROOT_GENERATION":
            raise ValueError("ROOT seed accepts only an internal source generation")
        source = request.get("previousGeneration")
        desired = request.get("configurationDesiredState")
        if not isinstance(source, dict) or source.get("instanceUuid") != request["instanceUuid"]:
            raise ValueError("ROOT seed source generation scope changed")
        old_revision = source.get("revision")
        if isinstance(old_revision, bool) or not isinstance(old_revision, int) or not 0 < old_revision < request["revision"]:
            raise ValueError("ROOT seed source revision is invalid")
        str(uuid.UUID(source["operationUuid"]))
        current = read_json(self.current) or {}
        pending = read_json(self.pending)
        if pending or (current and current != request.get("expectedPreviousGeneration")):
            raise ValueError("ROOT seed target generation changed")
        allowed = set(self.files())
        if not isinstance(desired, dict) or set(desired) != allowed or redact(desired) != desired:
            raise ValueError("ROOT seed must contain the exact nonsecret configuration allowlist")
        if any(value is not None and not isinstance(value, dict) for value in desired.values()):
            raise ValueError("ROOT seed desired files must be JSON objects or absent")
        checksum = hashlib.sha256(json.dumps(desired, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if checksum != source.get("configurationSha256"):
            raise ValueError("ROOT seed desired state checksum differs from source generation")
        # This command seeds only allowlisted declarative files before reconcile.
        # It does not adopt a generation, render a daemon config or touch DATA.
        protected_directory(self.config)
        for name in allowed:
            protected_directory((self.config / name).parent)
        previous = {name: read_json(self.config / name) for name in allowed}
        try:
            for name in sorted(allowed):
                path = self.config / name
                protected_directory(path.parent)
                if desired[name] is None:
                    path.unlink(missing_ok=True)
                else:
                    atomic_json(path, desired[name])
            if self.digest() != checksum:
                raise ValueError("ROOT seed configuration changed while writing")
        except Exception:
            for name, value in previous.items():
                path = self.config / name
                if value is None:
                    path.unlink(missing_ok=True)
                else:
                    atomic_json(path, value)
            raise
        return {"success": True, "seeded": True, "configurationSha256": checksum,
                "generationAdopted": False}

    def execute(self, action, request=None):
        if action == "status":
            return self.status()
        request = self.request(request)
        if action == "frozen":
            return self.frozen(request)
        if action == "frozen-initial":
            return self.frozen_initial(request)
        protected_directory(self.root)
        if action == "restore":
            return self.restore(request)
        if action == "seed":
            return self.seed(request)
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
