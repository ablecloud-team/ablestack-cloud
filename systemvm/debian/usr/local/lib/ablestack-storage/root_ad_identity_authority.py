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

"""Read-only cold ROOT AD scope requires authenticated retained source metadata."""
import hashlib
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
from ad_authority import ad_authority_read,protected_ad_policy
from samba_public_sid import samba_public_sid
from root_ad_imported_authorization import root_ad_imported_matches,root_ad_runtime_verified


def root_ad_retained_authority(common,maintenance,configuration=None,root=None,reference=None,sid_reader=None):
    scope=maintenance.get("scope")
    keys={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
    if (maintenance.get("bootHeld") is not True or maintenance.get("maintenanceKind")!="ROOT" or not isinstance(scope,dict) or set(scope)!=keys
            or any(scope.get(key)!=value for key,value in common.items()) or type(scope.get("revision")) is not int or scope["revision"]<1):
        raise ValueError("Cold ROOT AD attestation lacks its exact held ROOT scope")
    for key in keys-{"revision"}:
        if not isinstance(scope[key],str) or str(uuid.UUID(scope[key]))!=scope[key]:raise ValueError("Cold ROOT AD scope UUID is invalid")
    root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))
    options=[]
    for namespace,label,kind in (("root-retained-authorizations","ablestack-root-retained-authorization","ROOT_RETAINED_AUTHORIZATION"),
                                 ("root-ad-imported-authorizations","ablestack-root-ad-imported-authorization","ROOT_AD_IMPORTED_AUTHORIZATION")):
        identifier=str(uuid.uuid5(uuid.UUID(scope["operationUuid"]),label));base=root/namespace
        path=base/(identifier+"-authorization.json")
        if path.exists() or path.is_symlink():options.append((base,identifier,kind))
    if len(options)!=1:raise ValueError("Cold ROOT AD has no single typed imported/retained opaque authority")
    base,identifier,kind=options[0]
    raw=ad_authority_read(base/(identifier+"-authorization.json"),0o600);record=json.loads(raw)
    if reference is not None:
        if (not isinstance(reference,dict) or set(reference)!={"authorizationUuid","sha256"} or reference.get("authorizationUuid")!=identifier
                or reference.get("sha256")!=hashlib.sha256(json.dumps(record,sort_keys=True,separators=(",",":")).encode()).hexdigest()):
            raise ValueError("ROOT AD retained opaque reference differs")
    identity=record.get("latestAdIdentity");boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if (record.get("kind")!=kind or record.get("scope")!=scope or record.get("authorizedBootId")!=boot
            or not isinstance(identity,dict) or identity.get("trustVerified") is not True or type(identity.get("schemaVersion")) is not int or identity.get("schemaVersion")!=1
            or record.get("latestSourceRootScope",{}).get("instanceUuid")!=scope["instanceUuid"]
            or not re.fullmatch("[0-9a-f]{64}",str(record.get("identityCheckpointSha256")))
            or not re.fullmatch("[0-9a-f]{64}",str(record.get("latestConfigurationSha256")))):
        raise ValueError("ROOT AD retained AEAD source/boot authority is unavailable")
    if kind=="ROOT_AD_IMPORTED_AUTHORIZATION":
        transaction="root-target-"+scope["operationUuid"]
        if record.get("targetRuntimeTransactionId")!=transaction:raise ValueError("ROOT AD forward target transaction differs")
        root_ad_runtime_verified(record.get("targetRuntimePin"),transaction)
    identity_record=json.loads(ad_authority_read(base/(identifier+"-identity.json"),0o600,12*1024*1024))
    if (identity_record.get("scope")!=common or identity_record.get("sourceConfigurationSha256")!=record["latestConfigurationSha256"]
            or identity_record.get("publicKey")!=record.get("checkpointPublicKey")
            or identity_record.get("capsule",{}).get("sha256")!=record["identityCheckpointSha256"]):
        raise ValueError("ROOT AD opaque encrypted source checkpoint differs")
    capsule=identity_record["capsule"];ciphertext=base64.b64decode(capsule["ciphertext"],validate=True)
    if (type(capsule.get("schemaVersion")) is not int or capsule["schemaVersion"]!=1 or not 0<len(ciphertext)<=8*1024*1024+16
            or capsule.get("scope")!=scope["instanceUuid"]+":"+scope["operationUuid"]
            or hashlib.sha256(ciphertext).hexdigest()!=record["identityCheckpointSha256"]):
        raise ValueError("ROOT AD original encrypted checkpoint bytes/scope differ")
    root_ad_imported_matches(common,identity,configuration,sid_reader or samba_public_sid)
    return {"scope":scope,"identity":identity,"authorizationUuid":identifier,"authorizationSha256":hashlib.sha256(json.dumps(record,sort_keys=True,separators=(",",":")).encode()).hexdigest(),"bootId":boot,
            "originalCipherSha256":record.get("originalCipherSha256"),"sourceConfigurationSha256":record["latestConfigurationSha256"],"originalSourceScope":record["latestSourceRootScope"]}
