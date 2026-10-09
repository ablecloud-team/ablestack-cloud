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

"""Forward ROOT imported AD authority; authenticated ciphertext, immutable public reference."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
from cryptography.hazmat.primitives import serialization
from ad_authority import ad_authority_read,protected_ad_policy
from samba_public_sid import samba_public_sid
from root_identity_reference import RootIdentityReference
from root_configuration_capsule import root_configuration_authorize
from identity_capsule import decrypt,encrypt,validate_payload,require_identity_database_quiescence
from posix_root_initialization import root_receipt_read,root_receipt_write


def root_ad_runtime_readback(request):
    result=subprocess.run(["/usr/local/bin/ablestack-storage-runtime-updater","readback","/dev/stdin"],
                          input=json.dumps(request),capture_output=True,text=True,timeout=15)
    if result.returncode:raise ValueError("Forward ROOT signed target runtime readback failed")
    return json.loads(result.stdout)


def root_ad_runtime_verified(pin,transaction,runtime_provider=None):
    if (not isinstance(pin,dict) or set(pin)!={"bundleVersion","archiveSha256","manifestSha256","updaterSha256"}
            or not isinstance(pin.get("bundleVersion"),str) or not re.fullmatch("[A-Za-z0-9][A-Za-z0-9._-]{0,127}",pin["bundleVersion"])
            or any(not isinstance(pin.get(key),str) or not re.fullmatch("[0-9a-f]{64}",pin[key]) for key in ("archiveSha256","manifestSha256","updaterSha256"))):
        raise ValueError("Forward ROOT signed target runtime pin is invalid")
    observed=(runtime_provider or root_ad_runtime_readback)({"transactionId":transaction,**{key:pin[key] for key in ("bundleVersion","archiveSha256","manifestSha256")}})
    if (not isinstance(observed,dict) or any(observed.get(key) is not True for key in ("success","signedRuntimeVerified","installedFilesVerified","entrypointsVerified"))
            or observed.get("currentVersion")!=pin["bundleVersion"] or any(observed.get(key)!=pin[key] for key in ("archiveSha256","manifestSha256","updaterSha256"))):
        raise ValueError("Forward ROOT target installed signed runtime differs")
    return observed


def root_ad_imported_matches(common,identity,configuration=None,sid_reader=None):
    directory=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
    policy=protected_ad_policy(common["instanceUuid"],configuration=directory)
    state=json.loads(ad_authority_read(directory/"smb-domain.json",0o600))
    if (state.get("identityReceipt")!={key:identity[key] for key in ("machineSid","domainSid","machineAccountSid")}
            or policy.get("machineConfigurationSha256")!=identity["machineConfigurationSha256"]
            or any(policy.get(key)!=identity.get(key) for key in ("domain","realm","workgroup","netbiosName","domainSid","idmapPolicy"))
            or any(state.get(key)!=identity.get(key) for key in ("dnsAliases","servicePrincipals"))):
        raise ValueError("Imported ROOT AD identity/configuration differs from authenticated source")
    if (sid_reader or samba_public_sid)(identity["netbiosName"])!=identity["machineSid"]:
        raise ValueError("Imported ROOT public SAM is not original SAME_VM identity")
    return identity


class RootAdImportedAuthorization:
    def __init__(self,root=None,configuration=None,sid_reader=None,quiescence=None,runtime_provider=None):
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"root-ad-imported-authorizations"
        self.configuration=configuration;self.sid_reader=sid_reader;self.quiescence=quiescence or require_identity_database_quiescence
        self.runtime_provider=runtime_provider or root_ad_runtime_readback

    def optional_read(self,path):
        if not path.exists() and not path.is_symlink():return None
        return json.loads(ad_authority_read(path,0o600,12*1024*1024))

    def authorize(self,request,maintenance):
        fields={"instanceUuid","templateUpgradeUuid","operationUuid","revision","capsule","credentialPrivateKey","originalSourceScope","sourceConfigurationSha256","targetRuntimePin"}
        if not isinstance(request,dict) or set(request)!=fields:
            raise ValueError("Forward ROOT AD authority requires its closed encrypted import request")
        reference=RootIdentityReference();scope=reference.scope(request);original=reference.scope(request["originalSourceScope"])
        if maintenance.get("maintenanceKind")!="ROOT":raise ValueError("Forward ROOT AD requires its explicit typed boot hold")
        aad=reference.authorize(request,request["capsule"],maintenance)
        pin=request["targetRuntimePin"];transaction="root-target-"+scope["operationUuid"]
        root_ad_runtime_verified(pin,transaction,self.runtime_provider)
        if (original["instanceUuid"]!=scope["instanceUuid"] or original["templateUpgradeUuid"]!=scope["templateUpgradeUuid"]
                or original["revision"]>scope["revision"] or aad!=original["instanceUuid"]+":"+original["operationUuid"]):
            raise ValueError("Forward ROOT AD original source scope differs")
        original_cipher=request["capsule"];ciphertext=base64.b64decode(original_cipher["ciphertext"],validate=True)
        if hashlib.sha256(ciphertext).hexdigest()!=original_cipher.get("sha256"):
            raise ValueError("Forward ROOT AD original encrypted bytes differ")
        payload=decrypt(original_cipher,request["credentialPrivateKey"],aad);validate_payload(payload)
        source_sha=request["sourceConfigurationSha256"]
        root_configuration_authorize(payload,original,source_sha)
        identity=payload.get("adIdentity")
        if not isinstance(identity,dict) or not {"machineAccountSid","dnsAliases","servicePrincipals","machineConfigurationSha256","idmapPolicy"}<=set(identity):
            raise ValueError("Forward ROOT AD source lacks complete authenticated public identity")
        self.quiescence(payload.get("files",{}))
        common={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
        root_ad_imported_matches(common,identity,self.configuration,self.sid_reader)
        boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        public=serialization.load_pem_private_key(request["credentialPrivateKey"].encode(),password=None).public_key().public_bytes(
            serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        identifier=str(uuid.uuid5(uuid.UUID(scope["operationUuid"]),"ablestack-root-ad-imported-authorization"))
        identity_path=self.root/(identifier+"-identity.json");record_path=self.root/(identifier+"-authorization.json")
        checkpoint=self.optional_read(identity_path)
        if checkpoint is None:
            checkpoint={"schemaVersion":1,"scope":common,"sourceConfigurationSha256":source_sha,"publicKey":public,
                        "capsule":encrypt(payload,public,scope["instanceUuid"]+":"+scope["operationUuid"])}
        elif (type(checkpoint.get("schemaVersion")) is not int or checkpoint.get("schemaVersion")!=1 or checkpoint.get("scope")!=common or checkpoint.get("sourceConfigurationSha256")!=source_sha
                or checkpoint.get("publicKey")!=public or decrypt(checkpoint["capsule"],request["credentialPrivateKey"],scope["instanceUuid"]+":"+scope["operationUuid"])!=payload):
            raise ValueError("Forward ROOT AD immutable encrypted checkpoint changed")
        record={"schemaVersion":1,"kind":"ROOT_AD_IMPORTED_AUTHORIZATION","scope":scope,"latestSourceRootScope":original,
                "latestConfigurationSha256":source_sha,"latestAdIdentity":identity,"authorizedBootId":boot,
                "targetRuntimePin":pin,"targetRuntimeTransactionId":transaction,"originalCipherSha256":original_cipher["sha256"],"identityCheckpointSha256":checkpoint["capsule"]["sha256"],"checkpointPublicKey":public}
        existing=self.optional_read(record_path)
        if existing is not None and existing!=record:
            raise ValueError("Forward ROOT AD published authorization cannot be replaced")
        # Re-observe public import authority before either immutable publication.
        root_ad_imported_matches(common,identity,self.configuration,self.sid_reader)
        if self.optional_read(identity_path) is None:root_receipt_write(identity_path,checkpoint)
        if existing is None:root_receipt_write(record_path,record)
        digest=hashlib.sha256(json.dumps(record,sort_keys=True,separators=(",",":")).encode()).hexdigest()
        return {"success":True,"scope":scope,"rootAdImportedAuthorized":True,"kind":record["kind"],"retainedRootAuthorization":{"authorizationUuid":identifier,"sha256":digest},
                "sourceConfigurationSha256":source_sha,"bootId":boot,"identity":identity,"canonicalDesiredStateChanged":False,"dataPermissionsChanged":False}
