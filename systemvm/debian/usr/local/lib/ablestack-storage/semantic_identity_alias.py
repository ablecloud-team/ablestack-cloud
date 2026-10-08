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

"""Protected source-share aliases retain fixed Unix owners after UUID remap."""
import base64
import hashlib
import json
import os
from pathlib import Path
import uuid
import re
from ad_authority import ad_authority_read

SEMANTIC_ALIAS_FILE="/etc/ablestack-storage/smb-semantic-identity-aliases.json"
SEMANTIC_ALIAS_ROW={"sourceShareUuid","targetShareUuid","managedUser","managedGroup","ownerUid","ownerGid","sourceIdentityVerified","identityNamespaceShareUuid"}


def semantic_managed_aliases(original,resource_mappings):
    file=original["payload"]["files"].get("/etc/ablestack-storage/smb-managed-identities.json")
    managed={} if not file or file.get("absent") is True else json.loads(base64.b64decode(file["data"],validate=True))
    if not isinstance(managed,dict):raise ValueError("Semantic managed identity collection is invalid")
    if not isinstance(resource_mappings,list) or len(resource_mappings)>512:raise ValueError("Semantic share mapping is unavailable")
    result=[];sources=set();targets=set()
    for mapping in resource_mappings:
        if not isinstance(mapping,dict) or set(mapping)!={"sourceShareUuid","targetShareUuid"}:raise ValueError("Semantic share mapping shape is invalid")
        for field in ("sourceShareUuid","targetShareUuid"):
            value=mapping[field]
            if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError("Semantic share UUID is invalid")
        source=mapping["sourceShareUuid"];target=mapping["targetShareUuid"]
        if source in sources or target in targets:raise ValueError("Semantic share mapping union collides")
        sources.add(source);targets.add(target);identity=managed.get(source)
        if identity is None:continue
        namespace=identity.get("sourceIdentityShareUuid",source)
        if not isinstance(namespace,str) or str(uuid.UUID(namespace))!=namespace:raise ValueError("Semantic owner namespace UUID is invalid")
        token=namespace.replace("-","")[:20]
        if (identity.get("managedUser")!="sf_u_"+token or identity.get("managedGroup")!="sf_g_"+token or identity.get("posixOwnershipMode")!="FORCED_UID_GID"):
            raise ValueError("Semantic source forced owner namespace differs")
        for field in ("ownerUid","ownerGid"):
            value=identity.get(field)
            if type(value) is not int or not 10000<=value<=2147483647 or value==65534:raise ValueError("Semantic source fixed owner numeric identity is unsafe")
        result.append({**mapping,**{key:identity[key] for key in ("managedUser","managedGroup","ownerUid","ownerGid")},"sourceIdentityVerified":True,"identityNamespaceShareUuid":namespace})
    if set(managed)-sources:raise ValueError("Semantic source fixed owners have no reviewed target mapping")
    return result


def semantic_forced_alias(instance,share,uid,gid,configuration=None):
    root=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"));path=root/"smb-semantic-identity-aliases.json"
    if not path.exists() and not path.is_symlink():return None
    raw=ad_authority_read(path,0o600);aliases=json.loads(raw);state=json.loads(ad_authority_read(root/"smb-domain.json",0o600))
    if (type(aliases.get("schemaVersion")) is not int or aliases["schemaVersion"]!=1 or aliases.get("instanceUuid")!=instance
            or state.get("instanceUuid")!=instance or state.get("semanticManagedIdentityAliasSha256")!=hashlib.sha256(raw).hexdigest()):
        raise ValueError("Semantic fixed owner aliases are not bound to protected native identity")
    scope=aliases.get("scope")
    if (not isinstance(scope,dict) or set(scope)!={"instanceUuid","maintenanceUuid","operationUuid","revision"}
            or scope.get("instanceUuid")!=instance or scope.get("maintenanceUuid")!=scope.get("operationUuid")
            or type(scope.get("revision")) is not int or scope["revision"]<1
            or not isinstance(aliases.get("sourceCheckpointRecordSha256"),str) or not re.fullmatch("[0-9a-f]{64}",aliases["sourceCheckpointRecordSha256"])):
        raise ValueError("Semantic alias source scope/cipher receipt is invalid")
    for key in ("instanceUuid","maintenanceUuid","operationUuid"):
        if not isinstance(scope[key],str) or str(uuid.UUID(scope[key]))!=scope[key]:raise ValueError("Semantic alias source scope UUID is invalid")
    if not isinstance(aliases.get("bootId"),str) or str(uuid.UUID(aliases["bootId"]))!=aliases["bootId"]:raise ValueError("Semantic alias source boot is invalid")
    binding=state.get("semanticManagedIdentityAliasBinding")
    if (not isinstance(binding,dict) or aliases.get("scope")!=binding.get("scope") or aliases.get("bootId")!=binding.get("bootId")
            or aliases.get("sourceCheckpointRecordSha256")!=binding.get("sourceCheckpointRecordSha256")
            or aliases.get("originalSourceAuthority")!=binding.get("originalSourceAuthority")):
        raise ValueError("Semantic alias receipt scope/boot/source cipher binding differs")
    rows=aliases.get("mappings")
    if not isinstance(rows,list):raise ValueError("Semantic fixed owner alias rows are invalid")
    selected=None;seen=set()
    for row in rows:
        if not isinstance(row,dict) or set(row)!=SEMANTIC_ALIAS_ROW or row["sourceIdentityVerified"] is not True:raise ValueError("Semantic fixed owner alias row is unverified")
        for key in ("sourceShareUuid","targetShareUuid","identityNamespaceShareUuid"):
            if not isinstance(row[key],str) or str(uuid.UUID(row[key]))!=row[key]:raise ValueError("Semantic fixed owner alias UUID is invalid")
        token=row["identityNamespaceShareUuid"].replace("-","")[:20]
        if row["managedUser"]!="sf_u_"+token or row["managedGroup"]!="sf_g_"+token or row["targetShareUuid"] in seen:
            raise ValueError("Semantic fixed owner alias namespace collides")
        for key in ("ownerUid","ownerGid"):
            if type(row[key]) is not int or not 10000<=row[key]<=2147483647 or row[key]==65534:raise ValueError("Semantic alias numeric row is unsafe")
        seen.add(row["targetShareUuid"])
        if row["targetShareUuid"]==str(share):selected=row
    if selected is None:return None
    if type(uid) is not int or type(gid) is not int or selected["ownerUid"]!=uid or selected["ownerGid"]!=gid:raise ValueError("Semantic fixed owner alias numeric policy differs")
    return {**{key:selected[key] for key in ("managedUser","managedGroup","ownerUid","ownerGid")},"sourceIdentityShareUuid":selected["identityNamespaceShareUuid"]}
