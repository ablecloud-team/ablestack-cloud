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
import uuid
from ad_authority import ad_authority_read,protected_ad_policy
from samba_public_sid import samba_public_sid


def root_ad_retained_authority(common,maintenance,configuration=None,root=None,reference=None,sid_reader=None):
    scope=maintenance.get("scope")
    keys={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
    if (maintenance.get("bootHeld") is not True or maintenance.get("maintenanceKind")!="ROOT" or not isinstance(scope,dict) or set(scope)!=keys
            or any(scope.get(key)!=value for key,value in common.items()) or type(scope.get("revision")) is not int or scope["revision"]<1):
        raise ValueError("Cold ROOT AD attestation lacks its exact held ROOT scope")
    for key in keys-{"revision"}:
        if not isinstance(scope[key],str) or str(uuid.UUID(scope[key]))!=scope[key]:raise ValueError("Cold ROOT AD scope UUID is invalid")
    identifier=str(uuid.uuid5(uuid.UUID(scope["operationUuid"]),"ablestack-root-retained-authorization"))
    base=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"root-retained-authorizations"
    raw=ad_authority_read(base/(identifier+"-authorization.json"),0o600);record=json.loads(raw)
    if reference is not None:
        if (not isinstance(reference,dict) or set(reference)!={"authorizationUuid","sha256"} or reference.get("authorizationUuid")!=identifier
                or reference.get("sha256")!=hashlib.sha256(json.dumps(record,sort_keys=True,separators=(",",":")).encode()).hexdigest()):
            raise ValueError("ROOT AD retained opaque reference differs")
    identity=record.get("latestAdIdentity");boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if (record.get("kind")!="ROOT_RETAINED_AUTHORIZATION" or record.get("scope")!=scope or record.get("authorizedBootId")!=boot
            or not isinstance(identity,dict) or identity.get("trustVerified") is not True or type(identity.get("schemaVersion")) is not int or identity.get("schemaVersion")!=1
            or record.get("latestSourceRootScope",{}).get("instanceUuid")!=scope["instanceUuid"]
            or not re.fullmatch("[0-9a-f]{64}",str(record.get("identityCheckpointSha256")))
            or not re.fullmatch("[0-9a-f]{64}",str(record.get("latestConfigurationSha256")))):
        raise ValueError("ROOT AD retained AEAD source/boot authority is unavailable")
    identity_record=json.loads(ad_authority_read(base/(identifier+"-identity.json"),0o600))
    if (identity_record.get("scope")!=common or identity_record.get("sourceConfigurationSha256")!=record["latestConfigurationSha256"]
            or identity_record.get("publicKey")!=record.get("checkpointPublicKey")
            or identity_record.get("capsule",{}).get("sha256")!=record["identityCheckpointSha256"]):
        raise ValueError("ROOT AD opaque encrypted source checkpoint differs")
    capsule=identity_record["capsule"];ciphertext=base64.b64decode(capsule["ciphertext"],validate=True)
    if (type(capsule.get("schemaVersion")) is not int or capsule["schemaVersion"]!=1 or not 0<len(ciphertext)<=8*1024*1024+16
            or capsule.get("scope")!=scope["instanceUuid"]+":"+scope["operationUuid"]
            or hashlib.sha256(ciphertext).hexdigest()!=record["identityCheckpointSha256"]):
        raise ValueError("ROOT AD original encrypted checkpoint bytes/scope differ")
    directory=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
    policy=protected_ad_policy(scope["instanceUuid"],configuration=directory)
    state=json.loads(ad_authority_read(directory/"smb-domain.json",0o600))
    if (state.get("identityReceipt")!={key:identity[key] for key in ("machineSid","domainSid","machineAccountSid")}
            or policy.get("machineConfigurationSha256")!=identity["machineConfigurationSha256"]
            or any(policy.get(key)!=identity.get(key) for key in ("domain","realm","workgroup","netbiosName","domainSid","idmapPolicy"))
            or any(state.get(key)!=identity.get(key) for key in ("dnsAliases","servicePrincipals"))):
        raise ValueError("Imported ROOT AD SID/private configuration differs from encrypted original")
    if (sid_reader or samba_public_sid)(identity["netbiosName"])!=identity["machineSid"]:raise ValueError("Imported ROOT public SAM is not the original SAME_VM identity")
    return {"scope":scope,"identity":identity,"authorizationUuid":identifier,"authorizationSha256":hashlib.sha256(json.dumps(record,sort_keys=True,separators=(",",":")).encode()).hexdigest(),"bootId":boot}
