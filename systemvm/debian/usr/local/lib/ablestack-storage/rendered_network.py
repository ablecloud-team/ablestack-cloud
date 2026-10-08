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

"""Root-maintenance-only reversible secondary aliases; primary/MAC never move."""
import ipaddress
import json
import os
from pathlib import Path
import subprocess
from rendered_generation import rendered_read,rendered_json,rendered_directory


class RenderedNetwork:
    def __init__(self,runtime,store):self.runtime=runtime;self.store=store

    def authorize(self,scope,require_writer=True):
        status=self.runtime.command(("operation","maintenance","status"))
        actual=status.get("scope")
        proof=json.loads(rendered_read(Path("/run/ablestack-storage/rendered-authorization/writer.json"))) if require_writer else None
        if (status.get("bootHeld") is not True or not isinstance(actual,dict) or (set(actual)!={"instanceUuid","templateUpgradeUuid","operationUuid","revision"} and not (status.get("maintenanceKind")=="SERVICE" and set(actual)=={"instanceUuid","maintenanceUuid","operationUuid","revision"} and actual["maintenanceUuid"]==actual["operationUuid"]))
                or (require_writer and (proof.get("maintenanceScope")!=actual or proof.get("scope")!=scope))
                or any(actual.get(key)!=value for key,value in scope.items())):
            raise ValueError("Network transition requires the exact approved Root maintenance scope")
        return actual

    def bindings(self,path):
        return json.loads(rendered_read(path/"prerequisites.json"))["expectedBindings"]

    def observe(self,desired,bindings):
        result=self.runtime.command(("network","endpoints","inspect"),{**(desired or {"endpoints":[]}),"expectedBindings":bindings})
        if result.get("sideEffects") is not False or result.get("bindingsValidated") is not True:raise ValueError("Root network binding observation is unavailable")
        expected={row["listenIp"]:row for row in bindings}
        if {row["listenIp"] for row in result["endpoints"]}!=set(expected):raise ValueError("Root binding readback set differs")
        observed={}
        for row in result["endpoints"]:
            planned=expected[row["listenIp"]]
            if type(row.get("active")) is not bool or row.get("macAddress")!=planned["macAddress"] or row.get("prefixlen")!=planned["prefixlen"]:
                raise ValueError("Root binding readback MAC/prefix differs")
            observed[row["listenIp"]]={**planned,**row}
        return observed

    def ip(self,action,row):
        if row["listenIp"]==row["primaryIp"]:raise ValueError("Root transition never removes/replaces a primary IP")
        address=str(ipaddress.IPv4Address(row["listenIp"]))+"/"+str(row["prefixlen"])
        self.authorize(self.scope)
        inherited=(9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else ()
        # Fixed ip action and observed exact MAC interface, never caller command.
        result=subprocess.run(["ip","addr",action,address,"dev",row["interface"]],capture_output=True,text=True,timeout=self.runtime.remaining(5),pass_fds=inherited)
        if result.returncode:raise ValueError("Root secondary alias transition failed")

    def apply(self,target,source,rollback=False):
        target_manifest=self.store.inspect(target);source_manifest=self.store.inspect(source)
        self.scope=target_manifest["scope"]
        before=json.loads(rendered_read(source/"desired-state.json"));after=json.loads(rendered_read(target/"desired-state.json"))
        if before["sharedfs-network.json"]!=after["sharedfs-network.json"]:raise ValueError("Root network transition cannot change the primary declaration")
        if before["network-endpoints.json"]==after["network-endpoints.json"]:return True
        maintenance=self.authorize(self.scope)
        old=self.bindings(source);new=self.bindings(target)
        combined={row["listenIp"]:row for row in old}
        for row in new:
            if row["listenIp"] in combined and combined[row["listenIp"]]!=row:raise ValueError("Root secondary alias MAC/primary/prefix changed")
            combined[row["listenIp"]]=row
        expected=list(combined.values())
        root=self.store.root/"network-transitions";record_path=root/(self.scope["operationUuid"]+".json")
        if record_path.exists():
            receipt=json.loads(rendered_read(record_path))
            if receipt["scope"]!=self.scope or receipt["maintenanceScope"]!=maintenance or receipt["targetSha256"]!=target_manifest["manifestSha256"] or receipt["sourceSha256"]!=source_manifest["manifestSha256"]:
                raise ValueError("Root network transition receipt/source pin changed")
        else:
            if rollback:raise ValueError("Root network inverse has no durable pre-effect receipt")
            actual=self.observe(before["network-endpoints.json"],expected)
            receipt={"scope":self.scope,"maintenanceScope":maintenance,"targetSha256":target_manifest["manifestSha256"],"sourceSha256":source_manifest["manifestSha256"],
                     "before":actual,"phase":"PREPARED"}
            rendered_directory(root,True);rendered_json(record_path,receipt)
        receipt["phase"]="ROLLING_BACK" if rollback else "APPLYING";rendered_json(record_path,receipt)
        wanted=before["network-endpoints.json"] if rollback else after["network-endpoints.json"]
        bindings=old if rollback else new
        self.runtime.persist_one("network-endpoints.json",wanted)
        result=self.runtime.command(("network","endpoints","reconcile"),{"expectedBindings":bindings})
        if result.get("bindingReceiptVerified") is not True:raise ValueError("Root network desired/receipt readback failed")
        actual=self.observe(wanted,expected)
        old_ips={row["listenIp"] for row in old};new_ips={row["listenIp"] for row in new}
        remove=new_ips-old_ips if rollback else old_ips-new_ips
        for address in sorted(remove):
            observed=actual[address];original=receipt["before"][address]
            # Rollback only deletes aliases it added; forward only removes a
            # previously observed owned secondary, never an unrelated address.
            if observed["active"] and (not rollback or original["active"] is False):
                self.ip("del",observed)
        if rollback:
            actual=self.observe(wanted,expected)
            for address,row in receipt["before"].items():
                if row["active"] and not actual[address]["active"]:self.ip("add",actual[address])
            observed=self.observe(wanted,expected)
            if any(observed[ip]["active"]!=row["active"] for ip,row in receipt["before"].items()):
                raise ValueError("Root network inverse did not restore exact prior alias presence")
        else:
            observed=self.observe(wanted,bindings)
            if not all(row["active"] for row in observed.values()):raise ValueError("Root target secondary alias readback failed")
        receipt["phase"]="ROLLED_BACK" if rollback else "VERIFIED";rendered_json(record_path,receipt)
        return True
