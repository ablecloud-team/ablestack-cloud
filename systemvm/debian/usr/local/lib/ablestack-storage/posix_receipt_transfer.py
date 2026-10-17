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

"""Same-instance ROOT POSIX receipt transfer; no DATA permission mutation."""
import hashlib
import json
import uuid
import re
from posix_policy_receipt import PosixPolicyReceipt
from posix_root_initialization import root_receipt_write

POSIX_TRANSFER_KEYS = {"canonicalRow","rowSha256","postReceipt"}


def posix_row_sha256(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()


def validate_posix_transfers(records,instance_uuid):
    instance=str(uuid.UUID(instance_uuid))
    if not isinstance(records,dict) or len(records)>512:raise ValueError("ROOT POSIX receipt collection is invalid")
    for policy,record in records.items():
        if str(uuid.UUID(policy))!=policy or not isinstance(record,dict) or set(record)!=POSIX_TRANSFER_KEYS:
            raise ValueError("ROOT POSIX receipt contains an unknown policy or field")
        row=record["canonicalRow"]
        if not isinstance(row,dict) or set(row)!={"request","config","effective"} or record["rowSha256"]!=posix_row_sha256(row):
            raise ValueError("ROOT POSIX canonical policy digest differs")
        request=row["request"];revision=request.get("revision")
        if (request.get("uuid")!=policy or request.get("instanceUuid")!=instance or row["config"]!=request.get("config",{})
                or type(revision) is not int or revision<1):
            raise ValueError("ROOT POSIX receipt belongs to another instance/policy")
        uuid.UUID(request["volumeUuid"])
        expected_scope={"instanceUuid":instance,"policyUuid":policy,"volumeUuid":request["volumeUuid"],"revision":revision,
                        "volumeMountPath":request["volumeMountPath"],"relativePath":request["relativePath"]}
        receipt=record["postReceipt"]
        if (not isinstance(receipt,dict) or type(receipt.get("schemaVersion")) is not int or receipt.get("schemaVersion")!=1 or receipt.get("phase")!="COMPLETE"
                or receipt.get("scope")!=expected_scope or receipt.get("requestSha256")!=posix_row_sha256(request)):
            raise ValueError("ROOT POSIX protected post-apply receipt is invalid")
        canonical=receipt.get("canonicalDirectoryIdentity",receipt["directoryIdentity"])
        fields={"filesystemUuid","device","inode","effectiveUid","effectiveGid","effectiveMode","aclSha256"}
        for identity in (canonical,receipt["directoryIdentity"]):
            if not isinstance(identity,dict) or set(identity)!=fields:
                raise ValueError("ROOT POSIX identity has an unsupported field")
            uuid.UUID(identity["filesystemUuid"])
            if any(type(identity[key]) is not int or identity[key]<0 for key in ("device","inode","effectiveUid","effectiveGid")) or not re.fullmatch("[0-7]{4}",identity["effectiveMode"]) or not re.fullmatch("[0-9a-f]{64}",identity["aclSha256"]):
                raise ValueError("ROOT POSIX identity metadata is invalid")
        if "canonicalDirectoryIdentity" in receipt:
            root=receipt.get("rootScope");binding=receipt.get("volumeBinding")
            if (not isinstance(root,dict) or set(root)!={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
                    or root["instanceUuid"]!=instance or type(root["revision"]) is not int or root["revision"]<1
                    or not re.fullmatch("[0-9a-f]{64}",str(receipt.get("sourceConfigurationSha256")))
                    or receipt.get("canonicalRowSha256")!=record["rowSha256"] or not isinstance(binding,dict)
                    or set(binding)!={"volumeUuid","sizeBytes","filesystemUuid"} or binding["volumeUuid"]!=request["volumeUuid"]
                    or type(binding["sizeBytes"]) is not int or binding["sizeBytes"]<=0 or binding["filesystemUuid"]!=canonical["filesystemUuid"]):
                raise ValueError("ROOT POSIX previous device remap lacks its protected complete provenance")
            for key in ("instanceUuid","templateUpgradeUuid","operationUuid"):uuid.UUID(root[key])
        if row["effective"].get("directoryIdentity")!=canonical or any(canonical[key]!=receipt["directoryIdentity"][key] for key in fields if key!="device"):
            raise ValueError("ROOT POSIX source receipt differs from its canonical inode/attributes")
    return records


class PosixReceiptTransfer:
    def __init__(self,store=None):
        self.store=store or PosixPolicyReceipt()

    def collect(self,canonical,instance,inspect):
        if canonical is None:return {}
        if not isinstance(canonical,dict):raise ValueError("ROOT POSIX canonical collection is invalid")
        records={}
        for policy,row in canonical.items():
            request=row["request"]
            actual=inspect(request)
            receipt=self.store.read(request)
            if receipt is None:raise ValueError("ROOT POSIX source has no approved post-apply receipt")
            self.store.verify(request,actual,receipt)
            canonical_identity=receipt.get("canonicalDirectoryIdentity",receipt["directoryIdentity"])
            if row["effective"].get("directoryIdentity")!=canonical_identity:
                raise ValueError("ROOT POSIX source canonical effective state changed")
            records[policy]={"canonicalRow":row,"rowSha256":posix_row_sha256(row),"postReceipt":receipt}
        return validate_posix_transfers(records,instance)

    def restore(self,records,root_scope,source_sha,volume_bindings,maintenance,inspect,inspect_volume):
        # Callers reach this adapter only after decrypting a scope-bound native
        # identity capsule. Public seed/backup payloads cannot call it directly.
        instance=str(uuid.UUID(root_scope["instanceUuid"]))
        validate_posix_transfers(records,instance)
        keys={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
        if (set(root_scope)!=keys or type(root_scope["revision"]) is not int or root_scope["revision"]<1
                or maintenance.get("bootHeld") is not True or maintenance.get("scope")!=root_scope):
            raise ValueError("ROOT POSIX receipt restore lacks its exact boot-held maintenance scope")
        for key in keys-{"revision"}:uuid.UUID(root_scope[key])
        if not isinstance(source_sha,str) or len(source_sha)!=64 or any(char not in "0123456789abcdef" for char in source_sha):
            raise ValueError("ROOT POSIX source configuration digest is invalid")
        if not isinstance(volume_bindings,list):raise ValueError("ROOT POSIX FILE bindings are unavailable")
        bindings={}
        for binding in volume_bindings:
            if set(binding)!={"volumeUuid","sizeBytes","filesystemUuid"} or binding["volumeUuid"] in bindings or type(binding["sizeBytes"]) is not int or binding["sizeBytes"]<=0:
                raise ValueError("ROOT POSIX FILE binding is ambiguous")
            uuid.UUID(binding["volumeUuid"]);uuid.UUID(binding["filesystemUuid"]);bindings[binding["volumeUuid"]]=binding
        staged=[]
        for policy,record in records.items():
            row=record["canonicalRow"];request=row["request"];source=record["postReceipt"]
            binding=bindings.get(request["volumeUuid"])
            if binding is None:raise ValueError("ROOT POSIX policy volume is outside its frozen FILE set")
            volume=inspect_volume(binding)
            if (volume.get("matchedBy")!="VOLUME_SERIAL" or volume.get("mappingStatus")!="EXACT" or not volume.get("serial")
                    or "".join(char for char in str(volume.get("serial") or "").lower() if char.isalnum()) not in (binding["volumeUuid"].replace("-",""),binding["volumeUuid"].replace("-","")[:20])
                    or volume.get("sizeBytes")!=binding["sizeBytes"] or volume.get("filesystemUuid")!=binding["filesystemUuid"]
                    or not any(mount.get("target")==request["volumeMountPath"] for mount in volume.get("mounts",[]))):
                raise ValueError("ROOT POSIX mounted DATA differs from its frozen serial/filesystem/size")
            observed=inspect(request)
            actual=observed["directoryIdentity"];canonical=row["effective"]["directoryIdentity"]
            if actual.get("filesystemUuid")!=binding["filesystemUuid"] or any(actual.get(key)!=canonical.get(key) for key in canonical if key!="device"):
                raise ValueError("ROOT POSIX DATA inode/permissions/ACL differ from the approved source")
            receipt={**source,"directoryIdentity":actual,"canonicalDirectoryIdentity":canonical,
                     "rootScope":root_scope,"sourceConfigurationSha256":source_sha,"canonicalRowSha256":record["rowSha256"],
                     "volumeBinding":binding}
            staged.append((request,observed,receipt))
        # Reobserve every inode before the first receipt write, then each write.
        for request,observed,receipt in staged:
            if inspect(request)["directoryIdentity"]!=observed["directoryIdentity"]:raise ValueError("ROOT POSIX DATA changed before attestation")
        for request,observed,receipt in staged:
            if inspect(request)["directoryIdentity"]!=observed["directoryIdentity"]:raise ValueError("ROOT POSIX DATA changed during attestation")
            root_receipt_write(self.store.path(request),receipt)
            self.store.verify(request,observed,self.store.read(request))
        return {"success":True,"posixReceiptsRestored":len(staged),"dataPermissionsChanged":False,"canonicalDesiredStateChanged":False}
