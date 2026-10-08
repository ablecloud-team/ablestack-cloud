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

"""Scope-bound logical headroom lease; this does not allocate or reserve RAM."""
import fcntl
import json
import math
import os
from pathlib import Path
import shutil
import stat
import tempfile
import time
import uuid


class ResourceReservation:
    def __init__(self,root=None,generation=None,observe=None,clock=None,boot_id=None,maintenance=None):
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_RESERVATION_DIR","/var/lib/ablestack-storage/resource-reservation"))
        self.record=self.root/"lease.json";self.lock=self.root/"lease.lock"
        self.maintenance=Path(maintenance or Path(os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"template-maintenance.json")
        self.generation=Path(generation or os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR","/var/lib/ablestack-storage/config-generations"))
        self.observe=observe or self.resources;self.clock=clock or (lambda:int(time.time()*1000))
        self.boot_id=boot_id or Path("/proc/sys/kernel/random/boot_id").read_text().strip()

    def read(self,path):
        if not path.exists() and not path.is_symlink():return None
        parent=path.parent.lstat();info=path.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022 or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_size>128*1024:
            raise ValueError("Logical reservation input is not protected")
        directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            opened_parent=os.fstat(directory)
            if (opened_parent.st_dev,opened_parent.st_ino)!=(parent.st_dev,parent.st_ino):raise ValueError("Logical reservation parent changed")
            descriptor=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=directory)
        finally:os.close(directory)
        try:
            opened=os.fstat(descriptor)
            if (opened.st_dev,opened.st_ino,opened.st_uid,opened.st_mode,opened.st_size)!=(info.st_dev,info.st_ino,info.st_uid,info.st_mode,info.st_size):raise ValueError("Logical reservation input changed")
            data=os.read(descriptor,128*1024+1);after=os.fstat(descriptor)
            if len(data)>128*1024 or (opened.st_size,opened.st_mtime_ns,opened.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):raise ValueError("Logical reservation input changed while reading")
            return json.loads(data)
        finally:os.close(descriptor)

    def scope(self,request):
        scope={key:str(uuid.UUID(request[key])) for key in ("instanceUuid","operationUuid")}
        revision=request["revision"]
        if isinstance(revision,bool) or not isinstance(revision,int) or revision<1:raise ValueError("Logical reservation revision is invalid")
        return {**scope,"revision":revision}

    def validate_root_maintenance(self,scope,request):
        expected=request.get("maintenanceScope")
        template=request.get("templateUpgradeUuid")
        if (not isinstance(expected,dict) or set(expected)!={"instanceUuid","operationUuid","revision","templateUpgradeUuid"}
                or any(expected.get(key)!=value for key,value in scope.items())
                or type(expected.get("revision")) is not int
                or str(uuid.UUID(template))!=expected.get("templateUpgradeUuid")):
            raise ValueError("Logical ROOT reservation lacks its exact maintenance scope")
        marker=self.read(self.maintenance)
        if not marker or set(marker)!={"scope"} or not isinstance(marker.get("scope"),dict) or type(marker["scope"].get("revision")) is not int or marker.get("scope")!=expected:
            raise ValueError("Logical ROOT reservation does not match its protected boot-held marker")
        return expected

    def validate_generation(self,scope,request):
        current=self.read(self.generation/"current.json")
        pending=self.read(self.generation/"pending.json")
        if current and current.get("instanceUuid")!=scope["instanceUuid"]:raise ValueError("Logical reservation native instance is foreign")
        if pending and any(pending.get(key)!=value for key,value in scope.items()):raise ValueError("Logical reservation conflicts with native pending writer")
        root_scope=None
        if not current and not pending:
            root_scope=self.validate_root_maintenance(scope,request)
        elif request.get("maintenanceScope") is not None or request.get("templateUpgradeUuid") is not None:
            root_scope=self.validate_root_maintenance(scope,request)
        if current and current.get("revision",0)>scope["revision"]:raise ValueError("Logical reservation revision is stale")
        return root_scope

    def requirements(self,request):
        requirements=request.get("requirements")
        fields={"minimumMemoryAvailableBytes","stagingRequiredBytes","maxLoadPerCpu","requireSessionDrain"}
        if not isinstance(requirements,dict) or set(requirements)!=fields:raise ValueError("Logical reservation requirements do not match the fixed schema")
        for field in ("minimumMemoryAvailableBytes","stagingRequiredBytes"):
            value=requirements[field]
            if isinstance(value,bool) or not isinstance(value,int) or not 0<=value<=1<<50:raise ValueError("Logical reservation byte requirement is invalid")
        load=requirements["maxLoadPerCpu"]
        if isinstance(load,bool) or not isinstance(load,(int,float)) or not math.isfinite(load) or not 0<load<=100:raise ValueError("Logical reservation load requirement is invalid")
        if type(requirements["requireSessionDrain"]) is not bool:raise ValueError("Logical reservation drain requirement is invalid")
        return requirements

    def resources(self):
        memory={}
        for line in Path("/proc/meminfo").read_text().splitlines():
            key,value=line.split(":",1);memory[key]=int(value.strip().split()[0])*1024
        path=self.root
        while not path.exists():path=path.parent
        return {"generatedEpoch":time.time(),"memoryAvailableBytes":memory["MemAvailable"],"stagingFreeBytes":shutil.disk_usage(path).free,
                "loadPerCpu":os.getloadavg()[0]/max(1,os.cpu_count() or 1),"logicalReservationOnly":True}

    def expired(self,record):return record.get("bootId")!=self.boot_id or record["leaseExpiresAt"]<=self.clock()

    def status(self):
        record=self.read(self.record)
        expired=self.expired(record) if record else False
        observed=dict(self.observe());observed.setdefault("generatedEpoch",self.clock()/1000)
        return {"success":True,"reservationSupported":True,"drainSupported":False,"logicalReservationOnly":True,
                "reservationAcquired":bool(record and not expired),"scope":record.get("scope") if record else None,
                "leaseExpiresAt":record.get("leaseExpiresAt") if record else None,"expired":expired,
                "observed":observed,"blockers":[]}

    def write(self,value):
        descriptor,temporary=tempfile.mkstemp(prefix=".reservation-",dir=self.root)
        try:
            os.fchmod(descriptor,0o600)
            with os.fdopen(descriptor,"w") as handle:json.dump(value,handle,allow_nan=False,sort_keys=True);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,self.record);self.sync()
        finally:
            if os.path.exists(temporary):os.unlink(temporary)

    def sync(self):
        descriptor=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:os.fsync(descriptor)
        finally:os.close(descriptor)

    def execute(self,action,request=None):
        if action=="status":return self.status()
        scope=self.scope(request);root_scope=self.validate_generation(scope,request)
        duration=request.get("leaseDurationSeconds",90)
        if isinstance(duration,bool) or not isinstance(duration,int) or not 30<=duration<=300:raise ValueError("Logical reservation TTL is invalid")
        self.root.mkdir(parents=True,mode=0o700,exist_ok=True)
        info=self.root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o077:raise ValueError("Logical reservation directory is not protected")
        descriptor=os.open(self.lock,os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
        try:
            lock=os.fstat(descriptor)
            if not stat.S_ISREG(lock.st_mode) or lock.st_uid!=os.geteuid() or stat.S_IMODE(lock.st_mode)!=0o600:raise ValueError("Logical reservation lock is not protected")
            fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
            record=self.read(self.record)
            if record and record["scope"]!=scope and (action!="acquire" or not self.expired(record)):
                raise ValueError("Logical reservation belongs to another scope")
            if record and record["scope"]==scope and record.get("maintenanceScope")!=root_scope:
                raise ValueError("Logical reservation maintenance attestation changed")
            if action=="release":
                if not record or record["scope"]!=scope:raise ValueError("Logical reservation release scope is unavailable")
                self.record.unlink();self.sync()
                return {"success":True,"reservationSupported":True,"reservationAcquired":False,"scope":scope,"drainSupported":False,"logicalReservationOnly":True,"blockers":[]}
            if action not in ("acquire","renew"):raise ValueError("Unknown fixed logical reservation action")
            if action=="renew" and (not record or self.expired(record)):raise ValueError("Logical reservation renewal lease expired")
            requirements=self.requirements(request) if action=="acquire" else record["requirements"]
            if record and record["scope"]==scope and action=="acquire" and record["requirements"]!=requirements:raise ValueError("Logical reservation requirements changed on replay")
            observed=self.observe();blockers=[]
            if observed["memoryAvailableBytes"]<requirements["minimumMemoryAvailableBytes"]:blockers.append("MEMORY_HEADROOM")
            if observed["stagingFreeBytes"]<requirements["stagingRequiredBytes"]:blockers.append("STAGING_HEADROOM")
            if observed["loadPerCpu"]>requirements["maxLoadPerCpu"]:blockers.append("LOAD_HEADROOM")
            if requirements["requireSessionDrain"]:blockers.append("DRAIN_NOT_IMPLEMENTED")
            if blockers:return {"success":False,"reservationSupported":True,"reservationAcquired":False,"scope":scope,"observed":observed,"blockers":blockers,"drainSupported":False,"logicalReservationOnly":True}
            self.write({"scope":scope,"maintenanceScope":root_scope,"requirements":requirements,"bootId":self.boot_id,"leaseExpiresAt":self.clock()+duration*1000,"observed":observed})
            return self.status()
        finally:os.close(descriptor)
