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
import re
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
        canonical=receipt.get("canonicalDirectoryIdentity",receipt["directoryIdentity"])
        if (stored.get("effective") or {}).get("directoryIdentity") != canonical:
            raise ValueError("POSIX committed effective directory differs from its post-apply receipt")
        if canonical!=observed.get("directoryIdentity"):
            if (receipt.get("canonicalRowSha256")!=hashlib.sha256(json.dumps(stored,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()
                    or not isinstance(receipt.get("rootScope"),dict) or receipt["rootScope"].get("instanceUuid")!=request["instanceUuid"]
                    or any(canonical.get(key)!=observed["directoryIdentity"].get(key) for key in IDENTITY_FIELDS if key!="device")):
                raise ValueError("POSIX ROOT device remap lacks its protected source canonical attestation")
        return True

    def transferred_plan(self,row,observed,root_scope,source_sha,maintenance):
        request=row.get("request") if isinstance(row,dict) else None
        names={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
        if (not isinstance(root_scope,dict) or set(root_scope)!=names or type(root_scope["revision"]) is not int or root_scope["revision"]<1
                or maintenance.get("maintenanceKind") not in (None,"ROOT") or maintenance.get("bootHeld") is not True or maintenance.get("scope")!=root_scope
                or not isinstance(request,dict) or request.get("instanceUuid")!=root_scope["instanceUuid"]
                or not re.fullmatch("[0-9a-f]{64}",str(source_sha)) or set(row)!={"request","config","effective"} or row.get("config")!=request.get("config",{})):
            raise ValueError("ROOT POSIX transferred plan lacks its exact protected marker")
        for key in names-{"revision"}:uuid.UUID(root_scope[key])
        receipt=self.read(request);self.verify(request,observed,receipt)
        checksum=hashlib.sha256(json.dumps(row,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()
        canonical=receipt.get("canonicalDirectoryIdentity");binding=receipt.get("volumeBinding")
        if (receipt.get("rootScope")!=root_scope or receipt.get("sourceConfigurationSha256")!=source_sha or receipt.get("canonicalRowSha256")!=checksum
                or not isinstance(canonical,dict) or canonical!=(row.get("effective") or {}).get("directoryIdentity")
                or not isinstance(binding,dict) or binding.get("volumeUuid")!=request.get("volumeUuid")
                or binding.get("filesystemUuid")!=observed["directoryIdentity"].get("filesystemUuid")
                or any(canonical.get(key)!=observed["directoryIdentity"].get(key) for key in IDENTITY_FIELDS if key!="device")):
            raise ValueError("ROOT POSIX transferred plan differs from its attested source row/inode")
        return {"success":True,"sideEffects":False,"transferOnly":True,"beforeDirectoryIdentity":observed["directoryIdentity"],
                "predictedDirectoryIdentity":observed["directoryIdentity"],"configurationDesiredRow":row,"postApplyReceiptRequired":True,
                "postApplyReceiptVerified":True,"rootScope":root_scope,"sourceConfigurationSha256":source_sha,"dataPermissionsChanged":False,"canonicalDesiredStateChanged":False}

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
        canonical=previous_receipt.get("canonicalDirectoryIdentity",previous_receipt["directoryIdentity"])
        if (previous_policy.get("effective") or {}).get("directoryIdentity") != canonical:
            raise ValueError("POSIX restored metadata differs from the previous committed policy")
        if canonical!=observed.get("directoryIdentity") and previous_receipt.get("canonicalRowSha256")!=hashlib.sha256(json.dumps(previous_policy,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest():
            raise ValueError("POSIX previous ROOT receipt lacks its exact canonical policy digest")
        root_receipt_write(self.path(previous_request), previous_receipt)
