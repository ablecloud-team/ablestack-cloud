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

"""Protected ROOT source recovery; immutable baseline and exact canonical bytes."""
import hashlib
import fcntl
import json
import os
import re
from pathlib import Path
import stat
import time
import uuid
from rendered_generation import DESIRED_PATHS,DOMAINS,rendered_read,rendered_json,rendered_fsync
from rendered_credentials import credential_json


def root_recovery_scope(request):
    keys={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
    if not isinstance(request,dict) or "maintenanceUuid" in request:
        raise ValueError("ROOT source recovery cannot borrow a SERVICE scope")
    if not set(request)<=keys|{"verifiedGeneration"}:
        raise ValueError("ROOT source recovery accepts no caller snapshot or replay flag")
    value={key:request[key] for key in keys}
    for key in keys-{"revision"}:
        if not isinstance(value[key],str) or str(uuid.UUID(value[key]))!=value[key]:
            raise ValueError("ROOT source recovery UUID is invalid")
    if type(value["revision"]) is not int or value["revision"]<1:
        raise ValueError("ROOT source recovery revision is invalid")
    return value


class RootSourceRecovery:
    def __init__(self,driver,root=None):
        self.driver=driver;self.store=driver.store;self.runtime=driver.runtime
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"root-rendered-recovery"
        self.authorization=Path("/run/ablestack-storage/rendered-authorization")

    def path(self,scope):
        return self.root/("source-"+scope["templateUpgradeUuid"]+".json")

    def marker(self,request):
        scope=root_recovery_scope(request)
        status=self.runtime.command(("operation","maintenance","status"))
        if status.get("maintenanceKind")!="ROOT" or status.get("bootHeld") is not True or status.get("scope")!=scope:
            raise ValueError("ROOT source recovery lacks its exact protected held marker")
        return scope

    def canonical_bytes(self):
        root=Path(os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"));result={}
        for name in sorted(DESIRED_PATHS):
            path=root/name
            try:info=path.lstat()
            except FileNotFoundError:
                result[name]={"present":False,"sha256":None};continue
            parent=path.parent.lstat()
            if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022
                    or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_size>8*1024*1024):
                raise ValueError("ROOT source canonical file is not protected")
            descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
            try:
                fields=("st_dev","st_ino","st_mode","st_uid","st_gid","st_size","st_mtime_ns","st_ctime_ns")
                opened=os.fstat(descriptor)
                if any(getattr(info,key)!=getattr(opened,key) for key in fields):raise ValueError("ROOT canonical file changed while opening")
                data=os.read(descriptor,8*1024*1024+1);after=os.fstat(descriptor)
                if len(data)>8*1024*1024 or any(getattr(after,key)!=getattr(opened,key) for key in fields):raise ValueError("ROOT canonical file changed while reading")
                result[name]={"present":True,"sha256":hashlib.sha256(data).hexdigest()}
            finally:os.close(descriptor)
        return result

    def observation(self,scope):
        actual=self.driver.generation();status=self.store.status();current=status.get("current")
        generation=actual.get("generation") or {};checksum=actual.get("configurationSha256")
        if (actual.get("pendingOperationUuid") or actual.get("generationStatus")!="IN_SYNC"
                or generation.get("instanceUuid")!=scope["instanceUuid"] or type(generation.get("revision")) is not int
                or generation["revision"]>scope["revision"] or generation.get("configurationSha256")!=checksum):
            raise ValueError("ROOT source native generation is stale, pending or unverified")
        if (status.get("bootHeld") is not False or not isinstance(current,dict) or current.get("configurationSha256")!=checksum
                or any(current.get("scope",{}).get(key)!=generation.get(key) for key in ("instanceUuid","operationUuid","revision"))):
            raise ValueError("ROOT source requires its exact previously imported immutable baseline")
        desired=json.loads(rendered_read(self.store.pointer()/"desired-state.json"))
        if actual.get("configurationDesiredState")!=desired:
            raise ValueError("ROOT source canonical seven differ from the immutable baseline")
        return actual,status

    def capture(self,request):
        scope=self.marker(request);actual,status=self.observation(scope);path=self.path(scope)
        if path.exists() or path.is_symlink():
            self.validate(request)
            return {"success":True,"scope":scope,"sourceCaptured":True,"sourceGeneration":actual["generation"],"sideEffects":False}
        verified=self.driver.verify(self.store.pointer())
        if set(verified)!=set(DOMAINS) or any(verified[domain] is not True for domain in DOMAINS):
            raise ValueError("ROOT source baseline failed fresh all-four readback before quiesce")
        value={"schemaVersion":1,"scope":scope,"phase":"CAPTURED","sourceGeneration":actual["generation"],
               "sourceRendered":status["current"],"sourceActivation":status.get("activation"),
               "sourceConfigurationSha256":actual["configurationSha256"],"canonicalBytes":self.canonical_bytes(),"capturedEpoch":time.time()}
        rendered_json(path,value)
        return {"success":True,"scope":scope,"sourceCaptured":True,"sourceGeneration":value["sourceGeneration"],
                "sourceRenderedManifestSha256":value["sourceRendered"]["manifestSha256"],"canonicalDesiredStateChanged":False}

    def validate(self,request,require_verified=False):
        scope=self.marker(request);path=self.path(scope);saved=credential_json(rendered_read(path));actual,status=self.observation(scope)
        fields={"schemaVersion","scope","phase","sourceGeneration","sourceRendered","sourceActivation","sourceConfigurationSha256","canonicalBytes","capturedEpoch"}
        if not isinstance(saved,dict) or not set(saved)<=fields|{"startedDomains","verifiedEpoch"} or not fields<=set(saved):
            raise ValueError("ROOT source checkpoint fields are not exact")
        root_recovery_scope(saved["scope"])
        if (type(saved.get("schemaVersion")) is not int or saved["schemaVersion"]!=1 or saved.get("scope")!=scope
                or saved.get("phase") not in ("CAPTURED","QUIESCED","REPLAYING","VERIFIED","RECOVERY_REQUIRED")
                or saved.get("sourceGeneration")!=actual["generation"] or saved.get("sourceRendered")!=status["current"]
                or saved.get("sourceActivation")!=status.get("activation") or saved.get("sourceConfigurationSha256")!=actual["configurationSha256"]
                or saved.get("canonicalBytes")!=self.canonical_bytes()):
            raise ValueError("ROOT source recovery differs from its protected pre-quiesce checkpoint")
        if require_verified and request.get("verifiedGeneration")!=saved["sourceGeneration"]:
            raise ValueError("ROOT source resume caller generation differs from the protected source")
        return scope,path,saved

    def guard(self):
        marker=self.runtime.command(("operation","maintenance","status"))
        request=marker.get("scope");scope,path,saved=self.validate(request)
        proof=credential_json(rendered_read(self.authorization/"writer.json"))
        if (saved["phase"] not in ("REPLAYING","VERIFIED") or proof.get("mode")!="ROOT_SOURCE_RESTORE"
                or proof.get("maintenanceScope")!=scope or proof.get("sourceManifestSha256")!=saved["sourceRendered"]["manifestSha256"]
                or proof.get("scope")!={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
                or os.environ.get("ABLESTACK_STORAGE_RENDERED_REPLAY")!=str(self.store.pointer())
                or os.environ.get("ABLESTACK_STORAGE_RENDERED_FROM")!=str(self.store.pointer())
                or proof.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()):
            raise ValueError("ROOT source replay lacks its protected baseline writer authorization")
        try:
            process=Path("/proc")/str(int(proof["pid"]))
            before=(process/"stat").read_text().rpartition(")")[2].split()[19]
            lock=(process/"fd/9").stat();own=os.fstat(9)
            named_path=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock"))
            named=named_path.lstat()
            identity=(lock.st_dev,lock.st_ino,lock.st_uid,stat.S_IMODE(lock.st_mode))
            if (not stat.S_ISREG(lock.st_mode) or identity!=(proof["lockDevice"],proof["lockInode"],os.geteuid(),0o600)
                    or identity!=(own.st_dev,own.st_ino,own.st_uid,stat.S_IMODE(own.st_mode))
                    or identity!=(named.st_dev,named.st_ino,named.st_uid,stat.S_IMODE(named.st_mode))
                    or before!=proof["startTicks"]):
                raise ValueError("ROOT source writer descriptor identity changed")
            pattern=r"(?m)^lock:\s+[0-9]+:\s+FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+[0-9a-f]+:[0-9a-f]+:"+str(lock.st_ino)+r"\s+0\s+EOF\s*$"
            if not re.search(pattern,(process/"fdinfo/9").read_text()) or not re.search(pattern,Path("/proc/self/fdinfo/9").read_text()):
                raise ValueError("ROOT source writer does not hold its own exclusive lock")
            descriptor=os.open(named_path,os.O_RDONLY|os.O_NOFOLLOW)
            try:
                try:fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
                except BlockingIOError:pass
                else:
                    fcntl.flock(descriptor,fcntl.LOCK_UN);raise ValueError("ROOT source writer no longer holds its exclusive lock")
            finally:os.close(descriptor)
            if (process/"stat").read_text().rpartition(")")[2].split()[19]!=before:raise ValueError("ROOT source writer process changed")
        except (OSError,KeyError,TypeError,IndexError) as invalid:
            raise ValueError("ROOT source replay writer is unavailable") from invalid
        return {"success":True,"scope":scope,"rootSourceReplayAuthorized":True,"canonicalBytesUnchangedVerified":True}

    def resume(self,request):
        scope,path,saved=self.validate(request,True)
        saved["phase"]="REPLAYING";saved["startedDomains"]=[];rendered_json(path,saved)
        proof=None;previous_mode=getattr(self.runtime,"root_source_replay",False);self.runtime.credentials={"target":{},"previous":{}}
        try:
            proof=self.driver.authorize_units(request,source_only="ROOT")
            self.runtime.root_source_replay=True
            for domain in DOMAINS:
                self.validate(request,True)
                saved["startedDomains"].append(domain);rendered_json(path,saved)
                self.runtime.replay(self.store.pointer(),domain)
            self.validate(request,True)
            verified=self.driver.verify(self.store.pointer())
            if set(verified)!=set(DOMAINS) or any(verified[domain] is not True for domain in DOMAINS):
                raise ValueError("ROOT resumed source failed fresh all-four readback")
            saved["phase"]="VERIFIED";saved["verifiedEpoch"]=time.time();rendered_json(path,saved)
            return {"success":True,"scope":scope,"sourceResumePhase":"VERIFIED","runtimeVerified":True,"protocols":verified,
                    "sourceGeneration":saved["sourceGeneration"],"sourceRenderedManifestSha256":saved["sourceRendered"]["manifestSha256"],
                    "canonicalDesiredStateChanged":False,"nativeGenerationChanged":False,"canonicalBytesUnchangedVerified":True}
        except Exception:
            saved["phase"]="RECOVERY_REQUIRED";rendered_json(path,saved)
            raise
        finally:
            self.runtime.root_source_replay=previous_mode;self.runtime.credentials.clear()
            if proof is not None:proof.unlink(missing_ok=True);rendered_fsync(proof.parent)
