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

"""Native codec-only TARGET ciphertext publication; no caller publication RPC."""
import base64
import hashlib
import os
from pathlib import Path
from service_identity_cipher import ServiceIdentityCipher,service_cipher_digest,service_cipher_scope

class ServiceTargetCipher:
    prefix="service-identity-target"
    capture_kind="SERVICE_IDENTITY_TARGET"
    cipher_kind="SERVICE_TARGET_IDENTITY_CHECKPOINT"
    stopped_flag="serviceTargetStoppedVerified"
    def scope(self,request):return service_cipher_scope(request)
    def __init__(self,root=None):self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"));self.files=ServiceIdentityCipher(self.root)
    def path(self,scope):return self.root/(self.prefix+"-cipher-"+scope["operationUuid"]+".json")
    def retain(self,request,capsule,target,key_proof):
        scope=self.scope(request);saved=self.files.read(self.root/(self.prefix+"-"+scope["operationUuid"]+".json"))
        if (saved.get("kind")!=self.capture_kind or saved.get("scope")!=scope or saved.get("phase")!="STOPPED"
                or target.get("scope")!=scope or target.get(self.stopped_flag) is not True
                or target.get("targetGeneration")!=saved["targetGeneration"] or target.get("targetConfigurationSha256")!=saved["targetConfigurationSha256"]
                or target.get("stoppedReceiptSha256")!=service_cipher_digest(saved["targetStoppedReceipt"])
                or key_proof.get("scope")!=scope or key_proof.get("targetWrappingKeyVerified") is not True
                or not isinstance(capsule,dict) or set(capsule)!={"schemaVersion","scope","wrappedKey","nonce","ciphertext","sha256"}
                or type(capsule["schemaVersion"]) is not int or capsule["schemaVersion"]!=1 or capsule["scope"]!=scope["instanceUuid"]+":"+scope["operationUuid"]):
            raise ValueError("TARGET codec ciphertext lacks its actual stopped/key authority")
        raw=base64.b64decode(capsule["ciphertext"],validate=True)
        if not raw or len(raw)>8*1024*1024+16 or hashlib.sha256(raw).hexdigest()!=capsule["sha256"] or capsule["sha256"]==key_proof.get("originalCapsuleSha256"):
            raise ValueError("TARGET ciphertext is invalid or reuses BEFOREJOIN SOURCE")
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        public=serialization.load_pem_public_key(request["publicKey"].encode())
        if not isinstance(public,rsa.RSAPublicKey) or public.key_size<2048:raise ValueError("TARGET wrapping RSA key is invalid")
        record={"schemaVersion":1,"kind":self.cipher_kind,"scope":scope,"targetConfigurationSha256":saved["targetConfigurationSha256"],
                "targetRecordSha256":service_cipher_digest(saved),"stoppedReceiptSha256":service_cipher_digest(saved["targetStoppedReceipt"]),
                "publicKey":request["publicKey"],"capsule":capsule,"bootId":saved["bootId"]}
        path=self.path(scope)
        if path.exists() or path.is_symlink():
            if self.files.read(path)!=record:raise ValueError("TARGET original ciphertext publication changed")
        else:self.files.write(path,record)
        return {"kind":self.cipher_kind,"scope":scope,"capsuleSha256":capsule["sha256"],
                "targetConfigurationSha256":saved["targetConfigurationSha256"],"checkpointRecordSha256":service_cipher_digest(record)}
