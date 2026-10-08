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

"""Post-apply POSIX attestation; replay verifies one inode without changing metadata."""
import hashlib
import json
import os
from pathlib import Path
import time
import uuid
from posix_root_initialization import root_receipt_read, root_receipt_write

IDENTITY_FIELDS = ("filesystemUuid", "device", "inode", "effectiveUid", "effectiveGid", "effectiveMode", "aclSha256")


class PosixPolicyReceipt:
    def __init__(self, receipts=None):
        self.receipts = Path(receipts or os.environ.get("ABLESTACK_STORAGE_POSIX_RECEIPTS", "/var/lib/ablestack-storage/posix-policy-receipts"))

    def scope(self, request):
        revision = request.get("revision")
        if type(revision) is not int or revision < 1:
            raise ValueError("POSIX attestation needs its exact positive policy revision")
        return {"instanceUuid": str(uuid.UUID(request["instanceUuid"])), "policyUuid": str(uuid.UUID(request["uuid"])),
                "volumeUuid": str(uuid.UUID(request["volumeUuid"])), "revision": revision,
                "volumeMountPath": request["volumeMountPath"], "relativePath": request["relativePath"]}

    def path(self, request):
        return self.receipts / (str(uuid.UUID(request["uuid"])) + ".json")

    def checksum(self, request):
        return hashlib.sha256(json.dumps(request, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

    def read(self, request):
        return root_receipt_read(self.path(request))

    def attest(self, request, observed):
        identity = observed.get("directoryIdentity")
        if not isinstance(identity, dict) or set(identity) != set(IDENTITY_FIELDS):
            raise ValueError("POSIX post-apply identity is incomplete")
        return {"schemaVersion": 1, "phase": "COMPLETE", "scope": self.scope(request),
                "requestSha256": self.checksum(request), "directoryIdentity": identity, "verifiedEpoch": time.time()}

    def verify(self, request, observed, receipt):
        if (not isinstance(receipt, dict) or receipt.get("schemaVersion") != 1 or receipt.get("phase") != "COMPLETE"
                or receipt.get("scope") != self.scope(request) or receipt.get("requestSha256") != self.checksum(request)
                or receipt.get("directoryIdentity") != observed.get("directoryIdentity")):
            raise ValueError("POSIX post-apply receipt differs from its request or current directory")
        return True

    def replay(self, request, stored, observed):
        if not isinstance(stored, dict) or stored.get("request") != request:
            return False
        receipt = self.read(request)
        if receipt is None:
            return False
        self.verify(request, observed, receipt)
        if (stored.get("effective") or {}).get("directoryIdentity") != observed.get("directoryIdentity"):
            raise ValueError("POSIX committed effective directory differs from its post-apply receipt")
        return True

    def write(self, request, observed):
        receipt = self.attest(request, observed)
        root_receipt_write(self.path(request), receipt)
        self.verify(request, observed, self.read(request))
        return receipt

    def restore(self, request, previous_policy, previous_receipt, observed):
        if previous_receipt is None:
            # Invalidate newer attestation after an exact rollback to a legacy policy.
            path = self.path(request)
            if path.exists() or path.is_symlink():
                self.read(request)
                path.unlink()
                descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try: os.fsync(descriptor)
                finally: os.close(descriptor)
            return
        previous_request = (previous_policy or {}).get("request")
        if not isinstance(previous_request, dict) or previous_request.get("uuid") != request.get("uuid"):
            raise ValueError("POSIX receipt rollback lacks the exact previous committed request")
        self.verify(previous_request, observed, previous_receipt)
        if (previous_policy.get("effective") or {}).get("directoryIdentity") != observed.get("directoryIdentity"):
            raise ValueError("POSIX restored metadata differs from the previous committed policy")
        root_receipt_write(self.path(previous_request), previous_receipt)
