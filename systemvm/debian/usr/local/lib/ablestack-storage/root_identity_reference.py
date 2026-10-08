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

"""Protected original capsule references for same-instance ROOT rollback only."""
import hashlib
import json
import os
from pathlib import Path
import re
import uuid
from posix_root_initialization import root_receipt_read,root_receipt_write


class RootIdentityReference:
    def __init__(self,root=None):
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_ROOT_IDENTITY_REFERENCES","/var/lib/ablestack-storage/root-identity-capsules"))

    def scope(self,request):
        names={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
        if not isinstance(request,dict) or (set(request)&names)!=names or "maintenanceUuid" in request:
            raise ValueError("ROOT identity reference lacks its typed complete scope")
        result={key:str(uuid.UUID(request[key])) for key in names-{"revision"}}
        if type(request["revision"]) is not int or request["revision"]<1:raise ValueError("ROOT identity reference revision is invalid")
        result["revision"]=request["revision"];return result

    def digest(self,capsule):
        if not isinstance(capsule,dict) or type(capsule.get("schemaVersion")) is not int or capsule["schemaVersion"]!=1 or not re.fullmatch("[0-9a-f]{64}",str(capsule.get("sha256"))):
            raise ValueError("ROOT identity capsule reference is invalid")
        return hashlib.sha256(json.dumps(capsule,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()

    def path(self,root_scope,capsule):
        return self.root/(root_scope["templateUpgradeUuid"]+"-"+self.digest(capsule)+".json")

    def retain(self,request,capsule,source_sha):
        scope=self.scope(request)
        if capsule.get("scope")!=scope["instanceUuid"]+":"+scope["operationUuid"] or not re.fullmatch("[0-9a-f]{64}",str(source_sha)):
            raise ValueError("ROOT export reference source scope/configuration is invalid")
        value={"schemaVersion":1,"sourceRootScope":scope,"capsuleSha256":self.digest(capsule),"sourceConfigurationSha256":source_sha}
        path=self.path(scope,capsule);old=root_receipt_read(path)
        if old is not None and old!=value:raise ValueError("ROOT capsule reference changed after retention")
        if old is None:root_receipt_write(path,value)
        return value

    def authorize(self,request,capsule,maintenance):
        current=self.scope(request)
        if maintenance.get("maintenanceKind") not in (None,"ROOT") or maintenance.get("bootHeld") is not True or maintenance.get("scope")!=current:
            raise ValueError("ROOT capsule import requires its exact protected boot-held scope")
        own=current["instanceUuid"]+":"+current["operationUuid"]
        if capsule.get("scope")==own:
            if request.get("originalSourceScope") not in (None,current):raise ValueError("ROOT identity original source differs from its capsule")
            return own
        source=self.scope(request.get("originalSourceScope"))
        if any(source[key]!=current[key] for key in ("instanceUuid","templateUpgradeUuid")) or source["revision"]>=current["revision"]:
            raise ValueError("ROOT rollback original source is foreign or not a previous revision")
        record=root_receipt_read(self.path(source,capsule))
        expected={"schemaVersion":1,"sourceRootScope":source,"capsuleSha256":self.digest(capsule),"sourceConfigurationSha256":request.get("sourceConfigurationSha256")}
        if record!=expected or capsule.get("scope")!=source["instanceUuid"]+":"+source["operationUuid"]:
            raise ValueError("ROOT rollback lacks the exact protected original capsule reference")
        return capsule["scope"]
