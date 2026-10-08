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

"""SERVICE-only public PRESTOP and fresh owned-stop identity export authority."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid
from rendered_generation import DOMAINS,no_rendered_secrets,rendered_read,rendered_json
from rendered_credentials import credential_json
from root_source_identity_checkpoint import RootSourceIdentityCheckpoint,root_public_sha,root_source_validate_ad_identity,ROOT_IDENTITY_HOLDER_SCOPE
from root_source_recovery import RootSourceRecovery


def service_identity_scope(request):
    keys={"instanceUuid","maintenanceUuid","operationUuid","revision"}
    if not isinstance(request,dict) or set(request)!=keys:raise ValueError("SERVICE identity source requires only its exact typed four-field scope")
    result={key:request[key] for key in keys}
    for key in keys-{"revision"}:
        if not isinstance(result[key],str) or str(uuid.UUID(result[key]))!=result[key]:raise ValueError("SERVICE identity UUID is invalid")
    if result["maintenanceUuid"]!=result["operationUuid"] or type(result["revision"]) is not int or result["revision"]<1:
        raise ValueError("SERVICE identity operation/revision differs")
    return result


def service_identity_writer():
    if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")!="9":raise ValueError("SERVICE identity capture requires its owned native writer")
    info=os.fstat(9);path=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock"));named=path.lstat()
    pattern=r"(?m)^lock:\s+[0-9]+:\s+FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+[0-9a-f]+:[0-9a-f]+:"+str(info.st_ino)+r"\s+0\s+EOF\s*$"
    if (not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600
            or not stat.S_ISREG(named.st_mode) or named.st_uid!=os.geteuid() or stat.S_IMODE(named.st_mode)!=0o600
            or (info.st_dev,info.st_ino)!=(named.st_dev,named.st_ino) or not re.search(pattern,Path("/proc/self/fdinfo/9").read_text())):
        raise ValueError("SERVICE identity writer does not hold its exact exclusive named descriptor")


class ServicePublicIdentity(RootSourceIdentityCheckpoint):
    def local_sid(self):
        output=self.command(["net","getlocalsid"])
        match=re.fullmatch(r"SID for domain [^:\r\n]+ is:\s*(S-1-5-21(?:-[0-9]{1,10}){3})\s*",output)
        if not match:raise ValueError("SERVICE public local SAM SID is unavailable or ambiguous")
        return match[1]

    def freeze(self,scope,actual):
        raw,binding=self.public_file(self.configuration/"smb-domain.json",True)
        state=credential_json(raw) if raw is not None else {};metadata=None;machine_binding=None
        local_sid=self.local_sid()
        if str(state.get("joinState") or state.get("state") or "").upper()=="JOINED":
            expected={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
            observed=self.runtime.command(("identity","domain","inspect"),expected)
            required=("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","trustVerified","idmapPolicy","dnsAliases")
            epoch=observed.get("generatedEpoch");boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
            if (observed.get("scope")!=expected or any(observed.get(name) is not True for name in ("identityVerified","trustVerified","adSpnsVerified","dnsAliasesVerified"))
                    or observed.get("bootId")!=boot or type(epoch) not in (int,float) or not time.time()-60<=epoch<=time.time()+5
                    or any(name not in observed for name in required) or observed.get("machineSid")!=local_sid):
                raise ValueError("SERVICE PRESTOP AD public trust/SID/SPN/DNS/idmap evidence is not fresh")
            _,machine_binding=self.public_file(self.configuration/"ad-machine.conf",exact_mode=0o600)
            if state.get("machineConfigurationSha256")!=machine_binding["sha256"]:raise ValueError("SERVICE AD machine configuration differs from its protected joined receipt")
            metadata={"schemaVersion":1,**{name:observed[name] for name in required},"machineConfigurationSha256":machine_binding["sha256"]}
            root_source_validate_ad_identity(metadata)
        result={"schemaVersion":1,"publicAdIdentity":metadata,"publicAdPreStopSha256":root_public_sha(metadata) if metadata is not None else None,
                "publicLocalMachineSid":local_sid,"domainFileBinding":binding,"machineFileBinding":machine_binding,
                "sourcePosixPolicies":self.posix(actual["configurationDesiredState"],scope["instanceUuid"])}
        no_rendered_secrets(result);return result

    def unchanged(self,scope,actual,frozen):
        fields={"schemaVersion","publicAdIdentity","publicAdPreStopSha256","publicLocalMachineSid","domainFileBinding","machineFileBinding","sourcePosixPolicies"}
        if not isinstance(frozen,dict) or set(frozen)!=fields or type(frozen["schemaVersion"]) is not int or frozen["schemaVersion"]!=1:
            raise ValueError("SERVICE PRESTOP identity shape differs")
        _,domain=self.public_file(self.configuration/"smb-domain.json",True)
        if domain!=frozen["domainFileBinding"]:raise ValueError("SERVICE PRESTOP public AD state changed")
        if frozen["machineFileBinding"] is not None:
            _,machine=self.public_file(self.configuration/"ad-machine.conf",exact_mode=0o600)
            if machine!=frozen["machineFileBinding"]:raise ValueError("SERVICE PRESTOP machine configuration changed")
        if (self.local_sid()!=frozen["publicLocalMachineSid"]
                or frozen["publicAdPreStopSha256"]!=(root_public_sha(frozen["publicAdIdentity"]) if frozen["publicAdIdentity"] is not None else None)
                or frozen["sourcePosixPolicies"]!=self.posix(actual["configurationDesiredState"],scope["instanceUuid"])):
            raise ValueError("SERVICE PRESTOP public SID/POSIX authority changed")
        if frozen["publicAdIdentity"] is not None:root_source_validate_ad_identity(frozen["publicAdIdentity"])
        no_rendered_secrets(frozen)


class ServiceIdentitySource:
    def __init__(self,driver,root=None,identity=None,writer=None):
        self.driver=driver;self.store=driver.store;self.runtime=driver.runtime
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))
        self.identity=identity or ServicePublicIdentity(self.runtime);self.writer=writer or service_identity_writer

    def path(self,scope):
        return self.root/("service-identity-source-"+scope["operationUuid"]+".json")

    def canonical_bytes(self):
        # Reuse only the protected canonical byte reader; no ROOT marker,
        # ROOT scope conversion, ROOT recovery record or replay authority.
        return RootSourceRecovery.canonical_bytes(self)

    def observation(self,scope,allow_pending=False):
        actual=self.driver.generation();status=self.store.status();current=status.get("current");generation=actual.get("generation") or {}
        pending=actual.get("pendingOperationUuid");checksum=actual.get("configurationSha256")
        if (pending not in ((None,scope["operationUuid"]) if allow_pending else (None,))
                or actual.get("generationStatus") not in (("IN_SYNC","PENDING") if pending else ("IN_SYNC",))
                or generation.get("instanceUuid")!=scope["instanceUuid"] or type(generation.get("revision")) is not int
                or generation["revision"]>scope["revision"] or generation.get("configurationSha256")!=checksum
                or status.get("bootHeld") is not False or not isinstance(current,dict) or current.get("configurationSha256")!=checksum
                or any(current.get("scope",{}).get(key)!=generation.get(key) for key in ("instanceUuid","operationUuid","revision"))):
            raise ValueError("SERVICE identity source native/rendered generation is foreign, pending or stale")
        desired=credential_json(rendered_read(self.store.pointer()/"desired-state.json"))
        if actual.get("configurationDesiredState")!=desired:raise ValueError("SERVICE source seven differ from their immutable imported baseline")
        return actual,status

    def held(self,scope):
        marker=self.runtime.command(("operation","maintenance","status"))
        if marker.get("bootHeld") is not True or marker.get("maintenanceKind")!="SERVICE" or marker.get("scope")!=scope:
            raise ValueError("SERVICE identity export requires its exact held SERVICE marker")

    def capture(self,request):
        scope=service_identity_scope(request);self.writer()
        marker=self.runtime.command(("operation","maintenance","status"))
        if marker.get("bootHeld") is not False and (marker.get("maintenanceKind")!="SERVICE" or marker.get("scope")!=scope):
            raise ValueError("Foreign held maintenance blocks SERVICE identity PRESTOP capture")
        actual,status=self.observation(scope);path=self.path(scope)
        if path.exists() or path.is_symlink():
            _,saved,_=self.validate(request)
            return {**self.receipt(scope,saved),"sideEffects":False}
        verified=self.driver.verify(self.store.pointer())
        if set(verified)!=set(DOMAINS) or any(verified[name] is not True for name in DOMAINS):
            raise ValueError("SERVICE source all-four readback failed before PRESTOP capture")
        frozen=self.identity.freeze(scope,actual);owners=self.identity.owners()
        if (frozen["publicAdIdentity"] is not None)!=any(row["unit"]=="ablestack-storage-winbind.service" for row in owners):
            raise ValueError("SERVICE PRESTOP AD identity differs from its exact owned winbind")
        saved={"schemaVersion":1,"kind":"SERVICE_IDENTITY_SOURCE","scope":scope,"phase":"CAPTURED","sourceGeneration":actual["generation"],
               "sourceRendered":status["current"],"sourceActivation":status.get("activation"),"sourceConfigurationSha256":actual["configurationSha256"],
               "sourcePublicIdentity":frozen,"sourceOwners":owners,"canonicalBytes":self.canonical_bytes(),
               "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"capturedEpoch":time.time()}
        rendered_json(path,saved);return self.receipt(scope,saved)

    def receipt(self,scope,saved):
        frozen=saved["sourcePublicIdentity"]
        value={"success":True,"scope":scope,"sourceCaptured":True,"sourceGeneration":saved["sourceGeneration"],
               "sourceConfigurationSha256":saved["sourceConfigurationSha256"],"sourceRenderedManifestSha256":saved["sourceRendered"]["manifestSha256"],
               "canonicalDesiredStateChanged":False,"publicAdPreStopCaptured":frozen["publicAdIdentity"] is not None,
               "publicAdPreStopSha256":frozen["publicAdPreStopSha256"],"publicLocalMachineSid":frozen["publicLocalMachineSid"]}
        if saved.get("sourceStoppedReceipt") is not None:
            value.update(serviceSourceStoppedVerified=True,stoppedReceiptSha256=root_public_sha(saved["sourceStoppedReceipt"]))
        return value

    def validate(self,request,held=False):
        scope=service_identity_scope(request);self.writer()
        marker=self.runtime.command(("operation","maintenance","status"))
        if marker.get("bootHeld") is not False and (marker.get("maintenanceKind")!="SERVICE" or marker.get("scope")!=scope):
            raise ValueError("Foreign maintenance cannot borrow SERVICE identity source")
        if held:self.held(scope)
        saved=credential_json(rendered_read(self.path(scope)));actual,status=self.observation(scope,True)
        fields={"schemaVersion","kind","scope","phase","sourceGeneration","sourceRendered","sourceActivation","sourceConfigurationSha256","sourcePublicIdentity","sourceOwners","canonicalBytes","bootId","capturedEpoch"}
        if (not isinstance(saved,dict) or not fields<=set(saved) or not set(saved)<=fields|{"sourceStoppedReceipt"}
                or type(saved["schemaVersion"]) is not int or saved["schemaVersion"]!=1 or saved["kind"]!="SERVICE_IDENTITY_SOURCE"
                or saved["scope"]!=scope or saved["phase"] not in ("CAPTURED","STOPPED") or saved["sourceGeneration"]!=actual["generation"]
                or saved["sourceRendered"]!=status["current"] or saved["sourceActivation"]!=status.get("activation")
                or saved["sourceConfigurationSha256"]!=actual["configurationSha256"] or saved["canonicalBytes"]!=self.canonical_bytes()
                or saved["bootId"]!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()):
            raise ValueError("SERVICE identity source checkpoint differs from its exact PRESTOP source")
        self.identity.unchanged(scope,actual,saved["sourcePublicIdentity"])
        return scope,saved,actual

    def guard(self,request):
        scope,saved,actual=self.validate(request)
        marker=self.runtime.command(("operation","maintenance","status"))
        if marker.get("bootHeld") is False and (type(saved["capturedEpoch"]) not in (int,float) or not time.time()-180<=saved["capturedEpoch"]<=time.time()+5):
            raise ValueError("SERVICE PRESTOP capture expired before its owned stop")
        observed={row["unit"]:row for row in self.identity.owners()}
        if any(row["unit"] not in {owner["unit"] for owner in saved["sourceOwners"]} for row in observed.values()):
            raise ValueError("SERVICE source acquired a foreign identity owner")
        if any(observed.get(owner["unit"],owner)!=owner for owner in saved["sourceOwners"]):
            raise ValueError("SERVICE PRESTOP owner PID/start/configuration changed")
        return {**self.receipt(scope,saved),"quiesceAuthorized":True,"sideEffects":False}

    def stopped(self,request):
        scope,saved,actual=self.validate(request,True)
        journal=credential_json(rendered_read(self.root/"service-maintenance.json"));owners=saved["sourceOwners"]
        if (journal.get("scope")!=scope or journal.get("phase") not in ("HELD","RECOVERY_REQUIRED")
                or journal.get("sourceGeneration")!=saved["sourceGeneration"] or journal.get("sourceRendered")!=saved["sourceRendered"]
                or journal.get("sourceActivation")!=saved["sourceActivation"] or journal.get("owners")!=owners
                or not isinstance(journal.get("stoppedUnits"),list) or len(journal["stoppedUnits"])!=len(owners)
                or set(journal["stoppedUnits"])!={row["unit"] for row in owners}
                or self.identity.owners()):
            raise ValueError("SERVICE identity source lacks its exact durable owned-stop journal")
        self.identity.listeners_clear(owners)
        if self.identity.holders():raise ValueError("SERVICE known private identity database holders remain after stop")
        frozen=saved["sourcePublicIdentity"]
        receipt={"schemaVersion":1,"kind":"SERVICE_SOURCE_STOPPED","scope":scope,"sourceGeneration":saved["sourceGeneration"],
                 "sourceConfigurationSha256":saved["sourceConfigurationSha256"],"sourceRenderedManifestSha256":saved["sourceRendered"]["manifestSha256"],
                 "publicAdPreStopSha256":frozen["publicAdPreStopSha256"],"publicLocalMachineSid":frozen["publicLocalMachineSid"],
                 "owners":owners,"holderObservationScope":ROOT_IDENTITY_HOLDER_SCOPE,"knownIdentityDatabaseHolders":0,
                 "bootId":saved["bootId"]}
        previous=saved.get("sourceStoppedReceipt")
        if previous is not None and previous!=receipt:raise ValueError("SERVICE protected stopped receipt changed")
        if previous is None:
            saved["sourceStoppedReceipt"]=receipt;saved["phase"]="STOPPED";rendered_json(self.path(scope),saved)
        return {**self.receipt(scope,saved),"bootHeld":True,"maintenanceKind":"SERVICE","stoppedUnits":[owner["unit"] for owner in owners],"sideEffects":False}

    def export_source(self,request):
        scope,saved,actual=self.validate(request,True)
        if saved["phase"]!="STOPPED" or saved.get("sourceStoppedReceipt") is None:raise ValueError("SERVICE RAW export requires its protected AFTERSTOP receipt")
        self.stopped(request)
        frozen=saved["sourcePublicIdentity"]
        return {**self.receipt(scope,saved),"adIdentity":frozen["publicAdIdentity"],"posixPolicies":frozen["sourcePosixPolicies"],
                "sideEffects":False}
