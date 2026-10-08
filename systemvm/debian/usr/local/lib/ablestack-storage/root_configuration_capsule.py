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

"""Native-observed latest ROOT configuration inside the authenticated identity capsule."""
import re
import hashlib
import json
import uuid
ROOT_CONFIGURATION_PATHS={"desired-state/nfs-export-apply.json","desired-state/smb-share-apply.json","iscsi-targets.json","nvmeof-subsystems.json","posix-directory-policies.json","network-endpoints.json","sharedfs-network.json"}


def root_configuration_no_secrets(value):
    if isinstance(value,dict):
        for key,item in value.items():
            if not isinstance(key,str) or any(token in key.lower() for token in ("password","secret","dhchapkey","dhchapctrlkey","privatekey","keytab","capsule")):
                raise ValueError("ROOT configuration accepts public references, not credential material")
            root_configuration_no_secrets(item)
    elif isinstance(value,list):
        for item in value:root_configuration_no_secrets(item)


def root_configuration_sha256(value):
    if not isinstance(value,dict) or set(value)!=ROOT_CONFIGURATION_PATHS or any(item is not None and not isinstance(item,dict) for item in value.values()):
        raise ValueError("ROOT configuration requires its exact seven canonical files")
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()


def root_capsule_scope(value):
    fields={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
    if not isinstance(value,dict) or set(value)!=fields:
        raise ValueError("ROOT configuration capsule scope is not exact")
    for key in fields-{"revision"}:
        if not isinstance(value[key],str) or str(uuid.UUID(value[key]))!=value[key]:
            raise ValueError("ROOT configuration capsule UUID is invalid")
    if type(value["revision"]) is not int or value["revision"]<1:raise ValueError("ROOT configuration capsule revision is invalid")
    return value


def validate_root_configuration(value):
    fields={"schemaVersion","kind","scope","sourceGeneration","sourceConfigurationSha256","configurationDesiredState"}
    if (not isinstance(value,dict) or set(value)!=fields or type(value["schemaVersion"]) is not int or value["schemaVersion"]!=1
            or value["kind"]!="ROOT_SOURCE_CONFIGURATION"):
        raise ValueError("ROOT configuration capsule fields are not exact")
    scope=root_capsule_scope(value["scope"]);generation=value["sourceGeneration"];checksum=value["sourceConfigurationSha256"]
    if not isinstance(checksum,str) or not re.fullmatch("[0-9a-f]{64}",checksum):raise ValueError("ROOT configuration capsule checksum is invalid")
    if (not isinstance(generation,dict) or generation.get("instanceUuid")!=scope["instanceUuid"]
            or type(generation.get("revision")) is not int or not 1<=generation["revision"]<=scope["revision"]
            or generation.get("configurationSha256")!=checksum):
        raise ValueError("ROOT configuration capsule source generation is foreign or unpinned")
    if not isinstance(generation.get("operationUuid"),str) or str(uuid.UUID(generation["operationUuid"]))!=generation["operationUuid"]:
        raise ValueError("ROOT configuration capsule source operation is invalid")
    root_configuration_no_secrets(value["configurationDesiredState"])
    if root_configuration_sha256(value["configurationDesiredState"])!=checksum:
        raise ValueError("ROOT configuration capsule seven-file checksum differs")
    return value


def root_configuration_from_observation(request,observed):
    scope=root_capsule_scope({key:request[key] for key in ("instanceUuid","templateUpgradeUuid","operationUuid","revision")})
    if (observed.get("generationStatus")!="IN_SYNC" or observed.get("pendingOperationUuid")
            or (observed.get("generation") or {}).get("configurationSha256")!=observed.get("configurationSha256")):
        raise ValueError("ROOT configuration capsule requires a fresh native LKG before source shutdown")
    result={"schemaVersion":1,"kind":"ROOT_SOURCE_CONFIGURATION","scope":scope,"sourceGeneration":observed["generation"],
            "sourceConfigurationSha256":observed["configurationSha256"],"configurationDesiredState":observed["configurationDesiredState"]}
    return validate_root_configuration(result)


def root_configuration_authorize(payload,expected_scope,source_sha):
    if not isinstance(payload,dict) or "rootSourceConfiguration" not in payload:
        raise ValueError("Retained ROOT requires its latest encrypted native seven-file source")
    value=validate_root_configuration(payload["rootSourceConfiguration"])
    if value["scope"]!=root_capsule_scope(expected_scope) or value["sourceConfigurationSha256"]!=source_sha:
        raise ValueError("Retained ROOT encrypted source differs from its protected scope or source reference")
    return value["configurationDesiredState"]
