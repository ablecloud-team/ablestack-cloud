# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Explicit one-inode initialization bound to an immutable NEW format receipt."""
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import time
import uuid


def root_receipt_read(path):
    try:
        info = path.lstat()
    except FileNotFoundError:
        return None
    parent = path.parent.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077
            or info.st_size > 256 * 1024 or not stat.S_ISDIR(parent.st_mode)
            or parent.st_uid != os.geteuid() or parent.st_mode & 0o022):
        raise ValueError("NEW filesystem receipt is not protected")
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        pinned = os.fstat(directory)
        if (pinned.st_dev, pinned.st_ino, pinned.st_uid, pinned.st_mode) != (parent.st_dev, parent.st_ino, parent.st_uid, parent.st_mode):
            raise ValueError("NEW filesystem receipt directory changed")
        descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
    finally:
        os.close(directory)
    try:
        opened = os.fstat(descriptor)
        fields = ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_size")
        if tuple(getattr(opened, key) for key in fields) != tuple(getattr(info, key) for key in fields):
            raise ValueError("NEW filesystem receipt changed while reading")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            content = handle.read(256 * 1024 + 1)
        if len(content) > 256 * 1024:
            raise ValueError("NEW filesystem receipt is too large")
        return json.loads(content)
    finally:
        os.close(descriptor)


def root_receipt_write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    parent = path.parent.lstat()
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o077:
        raise ValueError("NEW initializer receipt directory is not protected")
    descriptor, temporary = tempfile.mkstemp(prefix=".root-initializer-", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump(value, handle, sort_keys=True, allow_nan=False)
            handle.flush(); os.fsync(handle.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class RootInitialization:
    def __init__(self, operations=None, receipts=None):
        self.operations = Path(operations or os.environ.get("ABLESTACK_STORAGE_VOLUME_OPERATIONS", "/var/lib/ablestack-storage/volume-operations"))
        self.receipts = Path(receipts or os.environ.get("ABLESTACK_STORAGE_ROOT_INITIALIZATIONS", "/var/lib/ablestack-storage/root-posix-initializations"))

    def path(self, request):
        return self.receipts / (str(uuid.UUID(request["volumeUuid"])) + ".json")

    def scope(self, request):
        initialization = request.get("rootInitialization")
        if not isinstance(initialization, dict) or set(initialization) != {"formatReceiptUuid", "operationUuid"}:
            raise ValueError("NEW root initializer needs its exact format and operation scope")
        if request.get("allowFilesystemRoot") is not True or request.get("relativePath") not in ("", "."):
            raise ValueError("NEW initializer applies only to the selected filesystem root")
        revision = request["revision"]
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
            raise ValueError("NEW initializer policy revision is invalid")
        return {"instanceUuid": str(uuid.UUID(request["instanceUuid"])), "policyUuid": str(uuid.UUID(request["uuid"])),
                "volumeUuid": str(uuid.UUID(request["volumeUuid"])), "revision": revision,
                "formatReceiptUuid": str(uuid.UUID(initialization["formatReceiptUuid"])),
                "operationUuid": str(uuid.UUID(initialization["operationUuid"]))}

    def empty_root(self, descriptor):
        for name in os.listdir(descriptor):
            info = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if name != "lost+found" or not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
                raise ValueError("NEW filesystem root already contains user data")
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            try:
                if os.listdir(child):
                    raise ValueError("NEW filesystem root recovery directory contains data")
            finally:
                os.close(child)

    def preview(self, request, before, descriptor):
        receipt = root_receipt_read(self.path(request))
        if receipt:
            return {"available": False, "phase": receipt["phase"], "formatReceiptUuid": receipt["scope"]["formatReceiptUuid"]}
        journal = root_receipt_read(self.operations / (str(uuid.UUID(request["volumeUuid"])) + ".json"))
        proof = (journal or {}).get("formatReceipt") or {}
        available = (journal is not None and journal.get("phase") == "COMPLETE" and journal.get("formatStarted") is True
                     and proof.get("schemaVersion") == 1 and proof.get("volumeUuid") == request["volumeUuid"]
                     and proof.get("matchedBy") == "VOLUME_SERIAL" and bool(proof.get("serial"))
                     and journal.get("mountPath") == request["volumeMountPath"]
                     and proof.get("directoryIdentity") == before["directoryIdentity"]
                     and proof.get("filesystemUuid") == before["filesystemUuid"])
        if available:
            try:
                self.empty_root(descriptor)
            except ValueError:
                available = False
        return {"available": bool(available), "phase": "UNUSED" if available else "UNAVAILABLE",
                "formatReceiptUuid": proof.get("receiptUuid") if available else None}

    def prepare(self, request, before, descriptor):
        scope = self.scope(request)
        opened = os.fstat(descriptor)
        actual = {"device": opened.st_dev, "inode": opened.st_ino, "effectiveUid": opened.st_uid,
                  "effectiveGid": opened.st_gid, "effectiveMode": format(stat.S_IMODE(opened.st_mode), "04o")}
        if any(before["directoryIdentity"].get(key) != value for key, value in actual.items()):
            raise ValueError("NEW filesystem root changed after preview")
        checksum = hashlib.sha256(json.dumps(request.get("config") or {}, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
        existing = root_receipt_read(self.path(request))
        if existing:
            if existing.get("scope") != scope or existing.get("policySha256") != checksum or request.get("expectedDirectoryIdentity") != existing["before"]["directoryIdentity"]:
                raise ValueError("NEW initializer receipt belongs to another request")
            if existing.get("phase") in ("VERIFIED", "COMPLETE"):
                if existing["after"]["directoryIdentity"] != before["directoryIdentity"]:
                    raise ValueError("Initialized filesystem root has changed")
                return existing, True
            if existing.get("phase") != "APPLYING" or existing["before"]["directoryIdentity"] != before["directoryIdentity"]:
                raise ValueError("NEW initializer requires verified recovery")
            return existing, False
        journal = root_receipt_read(self.operations / (scope["volumeUuid"] + ".json"))
        proof = (journal or {}).get("formatReceipt")
        if (not journal or journal.get("formatStarted") is not True or journal.get("phase") != "COMPLETE"
                or not isinstance(proof, dict) or proof.get("schemaVersion") != 1
                or proof.get("receiptUuid") != scope["formatReceiptUuid"]
                or proof.get("volumeUuid") != scope["volumeUuid"] or proof.get("matchedBy") != "VOLUME_SERIAL"
                or not proof.get("serial") or journal.get("mountPath") != request["volumeMountPath"]
                or proof.get("filesystemUuid") != before["filesystemUuid"]
                or proof.get("directoryIdentity") != before["directoryIdentity"]
                or request.get("expectedDirectoryIdentity") != before["directoryIdentity"]):
            raise ValueError("NEW root initialization lacks an exact unused format receipt")
        self.empty_root(descriptor)
        receipt = {"scope": scope, "policySha256": checksum, "phase": "APPLYING", "before": before, "createdEpoch": time.time()}
        root_receipt_write(self.path(request), receipt)
        return receipt, False

    def verified(self, request, receipt, after):
        if receipt.get("scope") != self.scope(request) or any(after["directoryIdentity"].get(key) != receipt["before"]["directoryIdentity"].get(key) for key in ("filesystemUuid", "device", "inode")):
            raise ValueError("NEW root initializer verification scope changed")
        receipt.update(phase="VERIFIED", after=after, verifiedEpoch=time.time())
        root_receipt_write(self.path(request), receipt)

    def complete(self, request, receipt):
        if receipt.get("scope") != self.scope(request) or receipt.get("phase") not in ("VERIFIED", "COMPLETE"):
            raise ValueError("NEW root initializer has not been verified")
        receipt.update(phase="COMPLETE", completedEpoch=time.time())
        root_receipt_write(self.path(request), receipt)

    def failed(self, request, receipt, observed):
        restored = observed["directoryIdentity"] == receipt["before"]["directoryIdentity"]
        receipt.update(phase="ROLLED_BACK" if restored else "RECOVERY_REQUIRED", observed=observed)
        root_receipt_write(self.path(request), receipt)
