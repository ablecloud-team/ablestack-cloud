#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Persistent same-row ROOT maintenance scopes; no credential material."""
import json
import os
from pathlib import Path
import stat
import tempfile
import uuid

SCOPE_KEYS = ("instanceUuid", "templateUpgradeUuid", "operationUuid", "revision")

def scope(request):
    result = {key: str(uuid.UUID(request[key])) for key in SCOPE_KEYS[:-1]}
    revision = request["revision"]
    if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
        raise ValueError("Invalid ROOT maintenance revision")
    result["revision"] = revision
    return result

class Maintenance:
    def __init__(self, root=None, generation=None):
        self.root = Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR", "/var/lib/ablestack-storage"))
        self.marker = self.root / "template-maintenance.json"
        self.receipt = self.root / "template-maintenance-release.json"
        self.generation = generation

    def read(self, path):
        if self.root.exists() or self.root.is_symlink():
            directory = self.root.lstat()
            if not stat.S_ISDIR(directory.st_mode) or directory.st_uid != os.geteuid() or directory.st_mode & 0o022:
                raise ValueError("ROOT maintenance read directory is not protected")
        if not path.exists() and not path.is_symlink():
            return None
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > 128 * 1024:
            raise ValueError("ROOT maintenance record is not protected")
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            opened = os.fstat(descriptor)
            fields = ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_size")
            if any(getattr(opened, key) != getattr(info, key) for key in fields):
                raise ValueError("ROOT maintenance record changed while opening")
            with os.fdopen(descriptor, "rb", closefd=False) as handle:
                data = handle.read(128 * 1024 + 1)
            if len(data) > 128 * 1024:
                raise ValueError("ROOT maintenance record exceeds its size limit")
            return json.loads(data)
        finally:
            os.close(descriptor)

    def protected(self):
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        info = self.root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise ValueError("ROOT maintenance directory is not protected")

    def write(self, path, value):
        self.protected()
        descriptor, temporary = tempfile.mkstemp(prefix=".template-maintenance-", dir=self.root)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w") as handle:
                json.dump(value, handle, sort_keys=True);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary, path)
            self.sync()
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def sync(self):
        descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:os.fsync(descriptor)
        finally:os.close(descriptor)

    def status(self):
        marker = self.read(self.marker)
        value = scope(marker["scope"]) if marker is not None else None
        return {"success": True, "maintenanceSupported": True, "bootHeld": marker is not None, "scope": value}

    def enter(self, request):
        desired = scope(request)
        marker = self.read(self.marker)
        previous = request.get("expectedPreviousScope")
        if marker is None:
            if previous is not None:
                raise ValueError("Expected previous ROOT maintenance scope is absent")
        else:
            actual = scope(marker["scope"])
            if actual == desired:
                return self.status()
            if previous is None or scope(previous) != actual or any(actual[key] != desired[key] for key in ("instanceUuid", "templateUpgradeUuid")):
                raise ValueError("ROOT maintenance scope is foreign or changed")
            if desired["revision"] <= actual["revision"]:
                raise ValueError("ROOT maintenance scope revision did not advance")
        self.write(self.marker, {"scope": desired})
        return self.status()

    def release(self, request):
        desired = scope(request)
        verified = request.get("verifiedGeneration")
        if not isinstance(verified, dict) or verified.get("instanceUuid") != desired["instanceUuid"]:
            raise ValueError("Verified ROOT maintenance generation scope is invalid")
        actual = self.generation()
        if actual.get("pendingOperationUuid") or actual.get("generation") != verified or actual.get("configurationSha256") != verified.get("configurationSha256") or actual.get("generationStatus") != "IN_SYNC":
            raise ValueError("ROOT maintenance release generation was not exactly verified")
        marker = self.read(self.marker)
        receipt = self.read(self.receipt)
        if marker is None:
            if receipt != {"scope": desired, "verifiedGeneration": verified}:
                raise ValueError("Released ROOT maintenance scope is unavailable or changed")
            return {"success": True, "released": True, "bootHeld": False, "scope": desired}
        if scope(marker["scope"]) != desired:
            raise ValueError("ROOT maintenance release scope changed")
        self.write(self.receipt, {"scope": desired, "verifiedGeneration": verified})
        self.marker.unlink()
        self.sync()
        return {"success": True, "released": True, "bootHeld": False, "scope": desired}
