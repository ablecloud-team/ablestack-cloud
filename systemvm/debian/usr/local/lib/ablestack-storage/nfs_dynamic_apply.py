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

"""Policy-only NFS updates under the existing native generation/FD9 writer."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import tempfile
import time
import uuid as nfs_dynamic_uuid
from ganesha_dbus import GaneshaDbus
from nfs_readiness import NfsEndpointReadiness, NfsProbeRunner, NfsReadinessError
from native_renderers import render_nfs_candidate
from config_generation import redact as nfs_dynamic_redact


class NfsLegacyDynamicApply:
    NFS="desired-state/nfs-export-apply.json"
    MAX_ENDPOINTS=256
    MAX_BYTES=16*1024*1024

    def __init__(self, authorization, deadline, command, cli=None, config_root=None, root=None, adapter_type=GaneshaDbus):
        self.authorization,self.deadline,self.command=authorization,deadline,command
        self.cli=Path(cli or "/usr/local/bin/ablestack-storagectl")
        self.config_root=Path(config_root or "/etc/ganesha/ablestack-storage")
        self.root=Path(root or "/run/ablestack-storage/nfs-dynamic-sources")
        self.adapter_type=adapter_type
        self.adapter=adapter_type(deadline=min(deadline,time.monotonic()+30))
        self.readiness=NfsEndpointReadiness(deadline,adapter_type,command,self.config_root)
        self.records=NfsProbeRunner({},str(self.root),deadline,None)
        self.active=False
        self.record=None
        self.path=None

    @staticmethod
    def checksum(value):
        return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()

    @staticmethod
    def protected_directory(path, create=False):
        if create:path.mkdir(mode=0o700,parents=True,exist_ok=True)
        info=path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o700:
            raise ValueError("NFS dynamic SOURCE directory is not protected")

    @staticmethod
    def compatible(before, after):
        if set(before)!=set(after):return False
        for key in before:
            old,new=GaneshaDbus.configuration(before[key]),GaneshaDbus.configuration(after[key])
            old_rows={row["exportId"]:row for row in old["exports"]}
            new_rows={row["exportId"]:row for row in new["exports"]}
            if old["globalSha256"]!=new["globalSha256"] or set(old_rows)!=set(new_rows):return False
            if any(old_rows[identifier].get(field)!=new_rows[identifier].get(field)
                   for identifier in old_rows for field in ("path","pseudo","fsal","structureSha256")):return False
        return True

    def render(self, payload):
        files=render_nfs_candidate(payload or {"enabled":False,"exports":[]},self.cli)
        endpoints=json.loads(files["nfs/manifest.json"])["endpoints"]
        if len(endpoints)>self.MAX_ENDPOINTS:raise ValueError("NFS dynamic endpoint bound exceeded")
        result={}
        for row in endpoints:
            key=row["legacyUnitKey"]
            if not re.fullmatch(r"[A-Za-z0-9_.-]+",key) or key in result:raise ValueError("NFS dynamic endpoint key is ambiguous")
            result[key]=files[row["configurationPath"]]
        if sum(len(value.encode())for value in result.values())>self.MAX_BYTES:
            raise ValueError("NFS dynamic config size bound exceeded")
        return result,endpoints

    def save(self):
        self.records.write(self.path/"checkpoint.json",self.record,self.MAX_BYTES)

    def discard(self):
        if self.path is None:return
        self.protected_directory(self.path)
        expected={"checkpoint.json"}|{side+"-"+key+".conf" for key in self.record["endpoints"] for side in ("source","target")}
        if {path.name for path in self.path.iterdir()}!=expected:
            raise ValueError("NFS dynamic checkpoint contains foreign files")
        for name in sorted(expected):
            path=self.path/name
            GaneshaDbus.source_bytes(path)
            path.unlink()
        self.path.rmdir()
        self.active=False

    def frozen_source(self, payload):
        if not self.authorization.validate_payload(payload):return None
        scope,pending,digest=self.authorization.state(payload["operationScope"])
        status=self.authorization.generation.status()
        previous=pending.get("previous") or {}
        if not previous:return None
        required={"instanceUuid","operationUuid","revision","configurationSha256","verifiedAt"}
        if (set(previous)!=required or status.get("generation")!=previous or previous["instanceUuid"]!=scope["instanceUuid"]
                or previous["configurationSha256"]!=pending["beforeSha256"]
                or type(previous["revision"])is not int or not 0<previous["revision"]<scope["revision"]
                or type(previous["verifiedAt"])not in(int,float) or not math.isfinite(previous["verifiedAt"])
                or any(type(previous[key])is not str or str(nfs_dynamic_uuid.UUID(previous[key]))!=previous[key] for key in("instanceUuid","operationUuid"))
                or previous["operationUuid"]==scope["operationUuid"]):raise ValueError("NFS dynamic SOURCE pointer differs")
        artifact_path=self.authorization.generation.root/(previous["operationUuid"]+".json")
        artifact=json.loads(GaneshaDbus.source_bytes(artifact_path))
        desired=artifact.get("desired")
        if (artifact.get("phase")!="VERIFIED" or any(artifact.get(key)!=previous[key] for key in required)
                or type(desired) is not dict or set(desired)!=set(self.authorization.generation.paths)
                or any(value is not None and type(value)is not dict for value in desired.values())
                or self.checksum(desired)!=pending["beforeSha256"]):
            raise ValueError("NFS dynamic frozen seven-file SOURCE differs")
        return scope,pending,digest,previous,desired

    def prepare(self, payload):
        self.payload=payload
        authority=self.frozen_source(payload)
        if authority is None:return self
        scope,pending,digest,previous,desired=authority
        self.scope,self.before=scope,pending["beforeSha256"]
        self.protected_directory(self.root,True)
        self.path=self.root/scope["operationUuid"]
        source,source_endpoints=self.render(desired[self.NFS])
        clean_payload=nfs_dynamic_redact({key:value for key,value in payload.items() if key!="operationScope"})
        source_intent=clean_payload==desired[self.NFS]
        self.source_intent=source_intent
        if self.path.exists() or self.path.is_symlink():
            self.protected_directory(self.path)
            record=json.loads(GaneshaDbus.source_bytes(self.path/"checkpoint.json"))
            if (record.get("scope")!=scope or record.get("beforeSha256")!=self.before
                    or record.get("sourceGeneration")!=previous or record.get("bootId")!=self.records.boot_id
                    or not source_intent or digest not in (self.before,record.get("targetDesiredSha256"))):
                raise ValueError("NFS dynamic unresolved checkpoint requires its exact SOURCE writer")
            if (record.get("schemaVersion")!=1 or record.get("phase")not in
                    ("PREPARED","PUBLISHING","ACTIVATING","TARGET_VERIFIED","ROLLING_BACK","ROLLED_BACK","RECOVERY_REQUIRED")
                    or type(record.get("endpoints"))is not dict or set(record["endpoints"])!=set(source)):
                raise ValueError("NFS dynamic SOURCE recovery checkpoint is malformed")
            for key,row in record["endpoints"].items():
                if type(row.get("sourceMode"))is not int or row["sourceMode"]&0o7022:
                    raise ValueError("NFS dynamic SOURCE mode is invalid")
                before=GaneshaDbus.source_bytes(self.path/("source-"+key+".conf"))
                after=GaneshaDbus.source_bytes(self.path/("target-"+key+".conf"))
                if (hashlib.sha256(before).hexdigest()!=row.get("sourceSha256") or
                        hashlib.sha256(after).hexdigest()!=row.get("targetSha256") or
                        GaneshaDbus.configuration(before.decode())!=GaneshaDbus.configuration(source[key]) or
                        not self.compatible({key:before.decode()},{key:after.decode()})):
                    raise ValueError("NFS dynamic frozen SOURCE recovery config differs")
            self.record=record;self.active=True
            self.rollback()
            self.discard()
        elif digest!=self.before:
            failure=NfsReadinessError("NFS_SOURCE_CHANGED")
            failure.public.update(phase="VERIFY_SOURCE",retryable=False)
            raise failure
        previous_path=self.root/previous["operationUuid"]
        if previous_path!=self.path and previous_path.exists():
            self.protected_directory(previous_path)
            old=json.loads(GaneshaDbus.source_bytes(previous_path/"checkpoint.json"))
            if (old.get("phase")=="TARGET_VERIFIED" and old.get("scope")=={key:previous[key]for key in("instanceUuid","operationUuid","revision")}
                    and old.get("targetDesiredSha256")==previous["configurationSha256"] and type(old.get("endpoints"))is dict
                    and len(old["endpoints"])<=self.MAX_ENDPOINTS and all(re.fullmatch(r"[A-Za-z0-9_.-]+",key)for key in old["endpoints"])):
                active_path=self.path;self.path=previous_path;self.record=old;self.discard();self.path=active_path;self.record=None
        target,target_endpoints=self.render(clean_payload)
        if not source or not self.compatible(source,target):return self
        actual_files={path.name[:-5] for path in self.config_root.glob("*.conf")}
        if actual_files!=set(source):raise ValueError("NFS dynamic actual SOURCE endpoint set differs")
        rows={};raw={};total=0
        for endpoint in source_endpoints:
            key=endpoint["legacyUnitKey"];configuration=self.config_root/(key+".conf")
            content=GaneshaDbus.source_bytes(configuration)
            total+=len(content)
            if total>self.MAX_BYTES:raise ValueError("NFS dynamic SOURCE snapshot bound exceeded")
            if GaneshaDbus.configuration(content.decode())!=GaneshaDbus.configuration(source[key]):
                raise ValueError("NFS dynamic actual config differs from frozen SOURCE")
            response=self.command(["systemctl","show","-p","MainPID","--value","ablestack-storage-ganesha@"+key+".service"],5)
            if response.returncode:raise ValueError("NFS dynamic SOURCE process observation failed")
            pid=int(response.stdout.strip() or "0")
            if not pid:return self # Cold/inactive endpoint uses the original strict startup.
            identity,_=self.readiness.snapshot(configuration,pid)
            observed={**identity,"listenIp":endpoint["listenIp"],"port":endpoint["port"],"protocolMode":endpoint["mode"]}
            if not self.readiness.listener_owned(observed):raise ValueError("NFS dynamic SOURCE listener is not owned")
            name,manager=self.adapter.owned_endpoint_manager(endpoint["listenIp"],endpoint["port"],pid)
            self.adapter.verify(manager,GaneshaDbus.configuration(content.decode())["exports"])
            self.adapter.owned_manager(name,pid)
            rows[key]={"identity":observed,"busName":name,"sourceSha256":hashlib.sha256(content).hexdigest(),
                       "targetSha256":hashlib.sha256(target[key].encode()).hexdigest(),
                       "sourceMode":stat.S_IMODE(configuration.lstat().st_mode)}
            raw[key]=content
        # All Source process/config/inventory observations precede publication.
        self.path.mkdir(mode=0o700)
        self.protected_directory(self.path)
        directory_identity=tuple(getattr(self.path.lstat(),key)for key in("st_dev","st_ino","st_uid","st_gid","st_mode"))
        created_files={}
        try:
            for key in rows:
                for side,content in (("source",raw[key]),("target",target[key].encode())):
                    filename=side+"-"+key+".conf"
                    descriptor=os.open(self.path/filename,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
                    created_files[filename]=tuple(getattr(os.fstat(descriptor),field)for field in
                                                  ("st_dev","st_ino","st_uid","st_gid","st_mode","st_nlink"))
                    with os.fdopen(descriptor,"wb") as handle:handle.write(content);handle.flush();os.fsync(handle.fileno())
            changed_desired={**desired,self.NFS:clean_payload}
            self.record={"schemaVersion":1,"phase":"PREPARED","scope":scope,"sourceGeneration":previous,
                         "beforeSha256":self.before,"targetDesiredSha256":self.checksum(changed_desired),
                         "bootId":self.records.boot_id,"endpoints":rows}
            self.save()
        except Exception:
            # A same-process pre-activation failure has no live config/DBus
            # effect. Remove only files whose O_EXCL descriptors we created.
            # Published checkpoints, foreign files and unknown crashes stay.
            try:
                actual=tuple(getattr(self.path.lstat(),key)for key in("st_dev","st_ino","st_uid","st_gid","st_mode"))
                names={path.name for path in self.path.iterdir()}
                if actual==directory_identity and names==set(created_files):
                    for name,identity in created_files.items():
                        info=(self.path/name).lstat()
                        observed=tuple(getattr(info,field)for field in("st_dev","st_ino","st_uid","st_gid","st_mode","st_nlink"))
                        if observed!=identity:raise ValueError("NFS partial SOURCE snapshot identity changed")
                    for name in created_files:(self.path/name).unlink()
                    self.path.rmdir()
            except (OSError,ValueError):pass
            raise
        self.active=True
        return self

    def publish(self):
        if not self.active:raise ValueError("NFS dynamic publication is not prepared")
        if self.authorization.validate_payload(self.payload) is not True:
            raise ValueError("NFS dynamic writer authorization is absent")
        before={}
        for key,row in self.record["endpoints"].items():
            path=self.config_root/(key+".conf")
            raw=GaneshaDbus.source_bytes(path)
            if hashlib.sha256(raw).hexdigest()!=row["sourceSha256"]:
                raise ValueError("NFS dynamic SOURCE config changed before publication")
            current,_=self.readiness.snapshot(path,row["identity"]["pid"])
            if any(current.get(field)!=row["identity"].get(field)for field in("bootId","pid","startTicks"))or not self.readiness.listener_owned(row["identity"]):
                raise ValueError("NFS dynamic SOURCE process/listener changed before publication")
            before[key]=raw
        if time.monotonic()>=self.deadline:raise TimeoutError("NFS dynamic publication deadline expired")
        self.record["phase"]="PUBLISHING";self.save()
        result=[]
        for key,row in sorted(self.record["endpoints"].items()):
            path=self.config_root/(key+".conf")
            target=GaneshaDbus.source_bytes(self.path/("target-"+key+".conf"))
            if hashlib.sha256(target).hexdigest()!=row["targetSha256"]:raise ValueError("NFS dynamic TARGET snapshot changed")
            if target!=before[key]:
                descriptor,temporary=tempfile.mkstemp(prefix=".nfs-target-",dir=self.config_root)
                try:
                    os.fchmod(descriptor,row["sourceMode"])
                    with os.fdopen(descriptor,"wb") as handle:handle.write(target);handle.flush();os.fsync(handle.fileno())
                    if GaneshaDbus.source_bytes(path)!=before[key]:raise ValueError("NFS dynamic SOURCE config changed at publication")
                    os.replace(temporary,path)
                    directory=os.open(self.config_root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
                    try:os.fsync(directory)
                    finally:os.close(directory)
                finally:
                    if os.path.lexists(temporary):os.unlink(temporary)
            identity=row["identity"]
            result.append((key,str(path),identity["listenIp"],identity["port"],identity["protocolMode"]))
        return result

    def candidates(self, configs):
        if not self.active:return {}
        if {row[0]for row in configs}!=set(self.record["endpoints"]):raise ValueError("NFS dynamic target endpoint set changed")
        result={}
        if self.authorization.validate_payload(self.payload) is not True:
            raise ValueError("NFS dynamic writer authorization is absent")
        for key,path,ip,port,mode in configs:
            source_path=self.path/("source-"+key+".conf")
            before=GaneshaDbus.source_bytes(source_path).decode()
            after=GaneshaDbus.source_bytes(path).decode()
            row=self.record["endpoints"][key]
            if (hashlib.sha256(before.encode()).hexdigest()!=row["sourceSha256"] or
                    hashlib.sha256(after.encode()).hexdigest()!=row["targetSha256"]):
                raise ValueError("NFS dynamic immutable SOURCE/TARGET bytes changed")
            current,_=self.readiness.snapshot(path,row["identity"]["pid"])
            if any(current.get(field)!=row["identity"].get(field) for field in ("bootId","pid","startTicks")):
                raise ValueError("NFS dynamic SOURCE process changed")
            if not self.readiness.listener_owned(row["identity"]):raise ValueError("NFS dynamic listener changed")
            manager=self.adapter.owned_manager(row["busName"],row["identity"]["pid"])
            self.adapter.verify(manager,GaneshaDbus.configuration(before)["exports"])
            result[key]=(self.adapter,row["identity"]["pid"],before,after,row["busName"],source_path)
        self.record["phase"]="ACTIVATING";self.save()
        return result

    def restore_configuration(self,key):
        row=self.record["endpoints"][key];path=self.config_root/(key+".conf")
        current=GaneshaDbus.source_bytes(path)
        if hashlib.sha256(current).hexdigest() not in (row["sourceSha256"],row["targetSha256"]):
            raise ValueError("NFS dynamic rollback config is foreign")
        source=GaneshaDbus.source_bytes(self.path/("source-"+key+".conf"))
        if hashlib.sha256(source).hexdigest()!=row["sourceSha256"]:raise ValueError("NFS dynamic SOURCE snapshot changed")
        if current==source:return
        descriptor,temporary=tempfile.mkstemp(prefix=".nfs-source-",dir=self.config_root)
        try:
            os.fchmod(descriptor,row["sourceMode"])
            with os.fdopen(descriptor,"wb") as handle:handle.write(source);handle.flush();os.fsync(handle.fileno())
            if GaneshaDbus.source_bytes(path)!=current:raise ValueError("NFS dynamic config changed before SOURCE restore")
            os.replace(temporary,path)
            directory=os.open(self.config_root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.lexists(temporary):os.unlink(temporary)

    def observed_rows(self,manager,source,target):
        if self.adapter.inventory(manager)!={row["exportId"]:row["pseudo"] if row.get("mountPathPseudo")else row["path"]for row in source}:
            raise ValueError("NFS dynamic rollback runtime inventory is foreign")
        old={row["exportId"]:row for row in source};new={row["exportId"]:row for row in target};result=[]
        for identifier in old:
            displayed=self.adapter.call(manager,"DisplayExport",identifier)
            if (len(displayed)!=5 or int(displayed[0])!=identifier or str(displayed[1])!=old[identifier]["path"]
                    or str(displayed[2])!=(old[identifier]["pseudo"]if old[identifier].get("mountPathPseudo")else old[identifier]["path"])):
                raise ValueError("NFS dynamic rollback export path is foreign")
            matched=None
            for expected in (old[identifier],new[identifier]):
                try:self.adapter.verify_clients(displayed[4],expected["clients"]);matched=expected;break
                except ValueError:pass
            if matched is None:raise ValueError("NFS dynamic rollback ACL is neither SOURCE nor own TARGET")
            result.append(matched)
        return result

    def rollback(self):
        if not self.active:return False
        if self.authorization.validate_payload(self.payload) is not True:
            raise ValueError("NFS dynamic SOURCE writer authorization is absent")
        self.record["phase"]="ROLLING_BACK";self.save()
        try:
            for key in self.record["endpoints"]:self.restore_configuration(key)
            for key,row in self.record["endpoints"].items():
                identity=row["identity"];pid=identity["pid"];configuration=self.config_root/(key+".conf")
                current,_=self.readiness.snapshot(configuration,pid)
                if any(current.get(field)!=identity.get(field)for field in("bootId","pid","startTicks"))or not self.readiness.listener_owned(identity):
                    raise ValueError("NFS dynamic rollback process/listener changed")
                source_path=self.path/("source-"+key+".conf")
                source=GaneshaDbus.configuration(GaneshaDbus.source_bytes(source_path).decode())["exports"]
                target_bytes=GaneshaDbus.source_bytes(self.path/("target-"+key+".conf"))
                if hashlib.sha256(target_bytes).hexdigest()!=row["targetSha256"]:raise ValueError("NFS dynamic TARGET snapshot changed")
                target=GaneshaDbus.configuration(target_bytes.decode())["exports"]
                manager=self.adapter.owned_manager(row["busName"],pid)
                observed=self.observed_rows(manager,source,target)
                self.adapter.update(row["busName"],pid,source_path,observed,source)
                after,_=self.readiness.snapshot(configuration,pid)
                if current!=after or not self.readiness.listener_owned(identity):raise ValueError("NFS dynamic SOURCE identity changed")
            self.record["phase"]="ROLLED_BACK";self.save()
            return True
        except Exception:
            self.record["phase"]="RECOVERY_REQUIRED";self.save()
            raise

    def verified(self):
        if not self.active:return
        self.record["phase"]="TARGET_VERIFIED";self.save()
        if self.source_intent:self.discard()
