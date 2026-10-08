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
from root_source_identity_checkpoint import RootSourceIdentityCheckpoint,root_public_sha


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
        self.identity=RootSourceIdentityCheckpoint(self.runtime)

    def path(self,scope):
        return self.root/("source-"+scope["templateUpgradeUuid"]+".json")

    def marker(self,request):
        scope=root_recovery_scope(request)
        status=self.runtime.command(("operation","maintenance","status"))
        if status.get("maintenanceKind")!="ROOT" or status.get("bootHeld") is not True or status.get("scope")!=scope:
            raise ValueError("ROOT source recovery lacks its exact protected held marker")
        return scope

    def canonical_bytes(self):
        root=Path(os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        info=root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:
            raise ValueError("ROOT canonical namespace is not protected")
        root_fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);result={}
        fields=("st_dev","st_ino","st_mode","st_uid","st_gid","st_size","st_mtime_ns","st_ctime_ns")
        try:
            opened_root=os.fstat(root_fd)
            if (info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid)!=(opened_root.st_dev,opened_root.st_ino,opened_root.st_mode,opened_root.st_uid,opened_root.st_gid):
                raise ValueError("ROOT canonical namespace changed while opening")
            for name in sorted(DESIRED_PATHS):
                parts=name.split("/");parent_fd=os.dup(root_fd);parent_path=root;absent=False
                try:
                    for part in parts[:-1]:
                        try:parent_info=os.stat(part,dir_fd=parent_fd,follow_symlinks=False)
                        except FileNotFoundError:
                            absent=True;break
                        if not stat.S_ISDIR(parent_info.st_mode) or parent_info.st_uid!=os.geteuid() or parent_info.st_mode&0o022:
                            raise ValueError("ROOT canonical parent is not protected")
                        descriptor=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent_fd);actual=os.fstat(descriptor)
                        if (parent_info.st_dev,parent_info.st_ino,parent_info.st_mode,parent_info.st_uid)!=(actual.st_dev,actual.st_ino,actual.st_mode,actual.st_uid):
                            os.close(descriptor);raise ValueError("ROOT canonical parent changed while opening")
                        os.close(parent_fd);parent_fd=descriptor;parent_path=parent_path/part
                    if absent:
                        result[name]={"present":False,"sha256":None};continue
                    try:leaf=os.stat(parts[-1],dir_fd=parent_fd,follow_symlinks=False)
                    except FileNotFoundError:
                        result[name]={"present":False,"sha256":None};continue
                    if not stat.S_ISREG(leaf.st_mode) or leaf.st_uid!=os.geteuid() or leaf.st_mode&0o022 or leaf.st_size>8*1024*1024:
                        raise ValueError("ROOT source canonical file is not protected")
                    descriptor=os.open(parts[-1],os.O_RDONLY|os.O_NOFOLLOW,dir_fd=parent_fd)
                    try:
                        opened=os.fstat(descriptor)
                        if any(getattr(leaf,key)!=getattr(opened,key) for key in fields):raise ValueError("ROOT canonical file changed while opening")
                        data=os.read(descriptor,8*1024*1024+1);after=os.fstat(descriptor)
                        named=os.stat(parts[-1],dir_fd=parent_fd,follow_symlinks=False)
                        if (len(data)>8*1024*1024 or any(getattr(after,key)!=getattr(opened,key) or getattr(named,key)!=getattr(opened,key) for key in fields)
                                or (parent_path.lstat().st_dev,parent_path.lstat().st_ino)!=(os.fstat(parent_fd).st_dev,os.fstat(parent_fd).st_ino)):
                            raise ValueError("ROOT canonical file or named parent changed while reading")
                        result[name]={"present":True,"sha256":hashlib.sha256(data).hexdigest()}
                    finally:os.close(descriptor)
                finally:os.close(parent_fd)
            final=root.lstat()
            if (final.st_dev,final.st_ino,final.st_mode,final.st_uid)!=(opened_root.st_dev,opened_root.st_ino,opened_root.st_mode,opened_root.st_uid):
                raise ValueError("ROOT canonical namespace changed during capture")
            return result
        finally:os.close(root_fd)

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
            _,_,saved=self.validate(request)
            self.identity.unchanged(scope,actual,saved["sourcePublicIdentity"])
            return {**self.capture_receipt(scope,saved),"sideEffects":False}
        verified=self.driver.verify(self.store.pointer())
        if set(verified)!=set(DOMAINS) or any(verified[domain] is not True for domain in DOMAINS):
            raise ValueError("ROOT source baseline failed fresh all-four readback before quiesce")
        value={"schemaVersion":1,"scope":scope,"phase":"CAPTURED","sourceGeneration":actual["generation"],
               "sourceRendered":status["current"],"sourceActivation":status.get("activation"),
               "sourceConfigurationSha256":actual["configurationSha256"],"canonicalBytes":self.canonical_bytes(),"capturedEpoch":time.time(),
               "sourcePublicIdentity":self.identity.freeze(scope,actual)}
        rendered_json(path,value)
        return self.capture_receipt(scope,value)

    def capture_receipt(self,scope,saved):
        frozen=saved["sourcePublicIdentity"]
        return {"success":True,"scope":scope,"sourceCaptured":True,"sourceGeneration":saved["sourceGeneration"],
                "sourceConfigurationSha256":saved["sourceConfigurationSha256"],
                "sourceRenderedManifestSha256":saved["sourceRendered"]["manifestSha256"],"canonicalDesiredStateChanged":False,
                "publicAdPreStopCaptured":frozen["publicAdIdentity"] is not None,"publicAdPreStopSha256":frozen["publicAdPreStopSha256"]}

    def quiesce_source(self,request):
        scope,path,saved=self.validate(request)
        if saved["phase"] not in ("CAPTURED","STOPPING","QUIESCED","RECOVERY_REQUIRED"):
            raise ValueError("ROOT source quiesce phase differs")
        actual,_=self.observation(scope)
        self.identity.unchanged(scope,actual,saved["sourcePublicIdentity"])
        if saved.get("sourceStoppedReceipt") is not None:
            receipt=self.identity.stopped(scope,actual,saved["sourcePublicIdentity"],saved["sourceRendered"],saved["sourceStoppedReceipt"])
        else:
            if "sourceStopOwners" not in saved:
                saved["sourceStopOwners"]=self.identity.owners()
            saved["phase"]="STOPPING";rendered_json(path,saved)
            try:
                receipt=self.identity.stopped(scope,actual,saved["sourcePublicIdentity"],saved["sourceRendered"],plan=saved["sourceStopOwners"])
            except Exception:
                saved["phase"]="RECOVERY_REQUIRED";rendered_json(path,saved);raise
            saved["sourceStoppedReceipt"]=receipt
        saved["phase"]="QUIESCED";rendered_json(path,saved)
        return {**self.capture_receipt(scope,saved),"quiesced":True,"bootHeld":True,"maintenanceSupported":True,
                "instanceUuid":scope["instanceUuid"],"operationUuid":scope["operationUuid"],"revision":scope["revision"],
                "domainsQuiesced":["NFS","SMB"],"stoppedUnits":[row["unit"] for row in receipt["owners"]],
                "blockTargetsPreserved":True,"blockSessionBoundary":"NORMAL_VM_SHUTDOWN",
                "rootSourceStoppedVerified":True,"stoppedReceiptSha256":root_public_sha(receipt)}

    def identity_export_source(self,request):
        scope,path,saved=self.validate(request)
        receipt=saved.get("sourceStoppedReceipt")
        if saved["phase"]!="QUIESCED" or not isinstance(receipt,dict) or receipt.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip():
            raise ValueError("ROOT RAW identity export requires its same-boot protected stopped source receipt")
        actual,_=self.observation(scope)
        self.identity.stopped(scope,actual,saved["sourcePublicIdentity"],saved["sourceRendered"],receipt)
        frozen=saved["sourcePublicIdentity"]
        return {**self.capture_receipt(scope,saved),"rootSourceStoppedVerified":True,"stoppedReceiptSha256":root_public_sha(receipt),
                "adIdentity":frozen["publicAdIdentity"],"posixPolicies":frozen["sourcePosixPolicies"],
                "rootSourceConfiguration":frozen["rootSourceConfiguration"],"sideEffects":False}

    def validate(self,request,require_verified=False):
        scope=self.marker(request);path=self.path(scope);saved=credential_json(rendered_read(path));actual,status=self.observation(scope)
        fields={"schemaVersion","scope","phase","sourceGeneration","sourceRendered","sourceActivation","sourceConfigurationSha256","canonicalBytes","capturedEpoch","sourcePublicIdentity"}
        if not isinstance(saved,dict) or not set(saved)<=fields|{"startedDomains","verifiedEpoch","sourceStopOwners","sourceStoppedReceipt"} or not fields<=set(saved):
            raise ValueError("ROOT source checkpoint fields are not exact")
        root_recovery_scope(saved["scope"])
        if (type(saved.get("schemaVersion")) is not int or saved["schemaVersion"]!=1 or saved.get("scope")!=scope
                or saved.get("phase") not in ("CAPTURED","STOPPING","QUIESCED","REPLAYING","VERIFIED","RECOVERY_REQUIRED")
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
