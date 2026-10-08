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

"""Native-only AFTERSTOP cipher publication and immutable SERVICE source reuse."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import uuid


def service_cipher_scope(request):
    keys={"instanceUuid","maintenanceUuid","operationUuid","revision"}
    if not isinstance(request,dict) or "templateUpgradeUuid" in request or not keys<=set(request):
        raise ValueError("SERVICE source cipher requires its unmixed typed scope")
    value={key:request[key] for key in keys}
    for key in keys-{"revision"}:
        if not isinstance(value[key],str) or str(uuid.UUID(value[key]))!=value[key]:raise ValueError("SERVICE cipher UUID is invalid")
    if value["maintenanceUuid"]!=value["operationUuid"] or type(value["revision"]) is not int or value["revision"]<1:
        raise ValueError("SERVICE cipher operation/revision is invalid")
    return value


def service_cipher_digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()


def service_cipher_json(value):
    def unique(pairs):
        result={}
        for key,item in pairs:
            if key in result:raise ValueError("SERVICE cipher record has duplicate fields")
            result[key]=item
        return result
    def invalid(value):raise ValueError("SERVICE cipher record has an invalid JSON number")
    return json.loads(value,object_pairs_hook=unique,parse_constant=invalid)


class ServiceIdentityCipher:
    def __init__(self,root=None):
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))

    def path(self,scope):
        return self.root/("service-identity-cipher-"+str(uuid.UUID(scope["operationUuid"]))+".json")

    def source_path(self,scope):
        return self.root/("service-identity-source-"+str(uuid.UUID(scope["operationUuid"]))+".json")

    def read(self,path):
        parent=path.parent.lstat();info=path.lstat();fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
        if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022
                or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_size>16*1024*1024):
            raise ValueError("SERVICE encrypted source checkpoint is not protected")
        descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        try:
            opened=os.fstat(descriptor)
            if any(getattr(opened,key)!=getattr(info,key) for key in fields):raise ValueError("SERVICE encrypted checkpoint changed during open")
            with os.fdopen(descriptor,"rb",closefd=False) as handle:raw=handle.read(16*1024*1024+1)
            after=os.fstat(descriptor);named=path.lstat()
            if len(raw)>16*1024*1024 or any(getattr(opened,key)!=getattr(after,key) or getattr(opened,key)!=getattr(named,key) for key in fields):
                raise ValueError("SERVICE encrypted checkpoint changed during read")
            return service_cipher_json(raw)
        finally:os.close(descriptor)

    def write(self,path,value):
        # Capture already established the protected parent; never create a
        # source namespace from an export caller or persist any plaintext.
        parent=path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022:raise ValueError("SERVICE cipher parent is foreign")
        raw=json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False)
        if len(raw.encode())>16*1024*1024:raise ValueError("SERVICE source cipher exceeds its bound")
        descriptor,temporary=tempfile.mkstemp(prefix=".service-source-cipher-",dir=path.parent)
        try:
            os.fchmod(descriptor,0o600)
            with os.fdopen(descriptor,"w") as handle:handle.write(raw);handle.flush();os.fsync(handle.fileno())
            os.link(temporary,path,follow_symlinks=False)
            directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)

    def source(self,scope):
        value=self.read(self.source_path(scope));receipt=value.get("sourceStoppedReceipt")
        if (value.get("schemaVersion")!=1 or value.get("kind")!="SERVICE_IDENTITY_SOURCE" or value.get("scope")!=scope
                or value.get("phase")!="STOPPED" or not isinstance(receipt,dict) or receipt.get("kind")!="SERVICE_SOURCE_STOPPED"
                or receipt.get("scope")!=scope or type(receipt.get("knownIdentityDatabaseHolders")) is not int or receipt["knownIdentityDatabaseHolders"]!=0
                or value.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
                or receipt.get("bootId")!=value["bootId"] or receipt.get("sourceGeneration")!=value.get("sourceGeneration")
                or receipt.get("sourceConfigurationSha256")!=value.get("sourceConfigurationSha256")
                or receipt.get("sourceRenderedManifestSha256")!=(value.get("sourceRendered") or {}).get("manifestSha256")):
            raise ValueError("SERVICE cipher source lacks its exact native AFTERSTOP provenance")
        return value

    def retain(self,request,capsule,source):
        # Only the fixed native capsule export calls this after RAM collect,
        # encryption and a second fresh source stop verification. No RPC or
        # caller snapshot/cipher-import route publishes this protected record.
        scope=service_cipher_scope(request);native=self.source(scope);checksum=native["sourceConfigurationSha256"]
        if (source.get("scope")!=scope or source.get("serviceSourceStoppedVerified") is not True or source.get("sourceConfigurationSha256")!=checksum
                or source.get("stoppedReceiptSha256")!=service_cipher_digest(native["sourceStoppedReceipt"])
                or not isinstance(capsule,dict) or set(capsule)!={"schemaVersion","scope","wrappedKey","nonce","ciphertext","sha256"}
                or type(capsule["schemaVersion"]) is not int or capsule["schemaVersion"]!=1 or capsule["scope"]!=scope["instanceUuid"]+":"+scope["operationUuid"]):
            raise ValueError("SERVICE source cipher differs from its native export provenance")
        ciphertext=base64.b64decode(capsule["ciphertext"],validate=True)
        if hashlib.sha256(ciphertext).hexdigest()!=capsule["sha256"]:raise ValueError("SERVICE source cipher digest differs")
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        public=serialization.load_pem_public_key(request["publicKey"].encode())
        if not isinstance(public,rsa.RSAPublicKey) or public.key_size<2048:raise ValueError("SERVICE wrapping key is unsupported")
        checkpoint={"schemaVersion":1,"scope":{key:scope[key] for key in ("instanceUuid","operationUuid","revision")},
                    "sourceConfigurationSha256":checksum,"publicKey":request["publicKey"],"capsule":capsule}
        value={"schemaVersion":1,"kind":"SERVICE_SOURCE_IDENTITY_CHECKPOINT","serviceScope":scope,"sourceRecordSha256":service_cipher_digest(native),
               "sourceGeneration":native["sourceGeneration"],"sourceRenderedManifestSha256":native["sourceRendered"]["manifestSha256"],
               "stoppedReceiptSha256":service_cipher_digest(native["sourceStoppedReceipt"]),"identityCheckpoint":checkpoint}
        path=self.path(scope)
        if path.exists() or path.is_symlink():
            if self.read(path)!=value:raise ValueError("SERVICE source cipher was already published with another encryption/key")
        else:self.write(path,value)
        return {"kind":"SERVICE_SOURCE_IDENTITY_CHECKPOINT","scope":scope,"capsuleSha256":capsule["sha256"],"sourceConfigurationSha256":checksum,
                "checkpointRecordSha256":service_cipher_digest(value)}

    def authorize(self,request,source,marker,canonical_bytes):
        scope=service_cipher_scope(marker.get("scope"))
        common={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
        if type(request.get("revision")) is not int or marker.get("maintenanceKind")!="SERVICE" or marker.get("bootHeld") is not True or any(request.get(key)!=value for key,value in common.items()):
            raise ValueError("SERVICE stage cannot borrow another held source cipher")
        native=self.source(scope);value=self.read(self.path(scope));checkpoint=value.get("identityCheckpoint")
        if (not isinstance(checkpoint,dict) or set(value)!={"schemaVersion","kind","serviceScope","sourceRecordSha256","sourceGeneration","sourceRenderedManifestSha256","stoppedReceiptSha256","identityCheckpoint"}
                or value.get("schemaVersion")!=1 or value.get("kind")!="SERVICE_SOURCE_IDENTITY_CHECKPOINT" or value.get("serviceScope")!=scope
                or value.get("sourceRecordSha256")!=service_cipher_digest(native) or value.get("sourceGeneration")!=source.get("generation")
                or value.get("sourceRenderedManifestSha256")!=native["sourceRendered"]["manifestSha256"]
                or value.get("stoppedReceiptSha256")!=service_cipher_digest(native["sourceStoppedReceipt"])
                or checkpoint.get("scope")!=common or checkpoint.get("sourceConfigurationSha256")!=source.get("configurationSha256")
                or native["canonicalBytes"]!=canonical_bytes):
            raise ValueError("SERVICE stage source cipher/generation/seven changed")
        from cryptography.hazmat.primitives import serialization
        try:
            actual=serialization.load_pem_public_key(str(request.get("checkpointPublicKey") or "").encode())
            expected=serialization.load_pem_public_key(checkpoint["publicKey"].encode())
            if actual.public_numbers()!=expected.public_numbers():raise ValueError("wrong key")
        except Exception as invalid:raise ValueError("SERVICE stage wrapping key differs from its original BEFOREJOIN cipher") from invalid
        capsule=checkpoint.get("capsule") or {}
        if capsule.get("scope")!=scope["instanceUuid"]+":"+scope["operationUuid"] or hashlib.sha256(base64.b64decode(capsule["ciphertext"],validate=True)).hexdigest()!=capsule.get("sha256"):
            raise ValueError("SERVICE protected original cipher digest/scope changed")
        return checkpoint
