#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Owned winbind lifecycle; starts need an exact scoped native writer or verified boot."""
import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
import stat
import time
import uuid
from ad_identity import ad_protected_json,bounded_ad_run
from posix_root_initialization import root_receipt_write
from ad_authority import protected_ad_policy

AD_WINBIND_UNIT="ablestack-storage-winbind.service"
AD_WINBIND_CONFIGURATION="/etc/ablestack-storage/ad-machine.conf"
AD_WINBIND_UNIT_CONTENT="""[Unit]
Description=ABLESTACK protected AD identity service
After=network-online.target local-fs.target
Wants=network-online.target
[Service]
Type=simple
ExecCondition=/usr/local/bin/ablestack-storagectl identity domain daemon-gate
ExecStart=/usr/sbin/winbindd --foreground --no-process-group --configfile=/etc/ablestack-storage/ad-machine.conf
Restart=on-failure
RestartSec=2
KillMode=control-group
TimeoutStopSec=20
[Install]
WantedBy=multi-user.target
"""


class AdWinbind:
    def __init__(self,cli,configuration=None,authorization=None,unit_directory=None,run=None):
        self.cli=str(cli);self.configuration=Path(configuration or "/etc/ablestack-storage")
        self.authorization=Path(authorization or "/run/ablestack-storage/ad-authorization")
        self.unit_directory=Path(unit_directory or "/etc/systemd/system")
        self.run=run or bounded_ad_run;self.deadline=time.monotonic()+60

    def command(self,args):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("AD daemon lifecycle total deadline expired")
        result=self.run(args,capture_output=True,text=True,timeout=min(20,remaining))
        if result.returncode:raise ValueError("Owned AD daemon command failed")
        return result.stdout.strip()

    def scope(self,request):
        result={key:str(uuid.UUID(request[key])) for key in ("instanceUuid","operationUuid")}
        if type(request.get("revision")) is not int or request["revision"]<1:raise ValueError("AD daemon scope revision is invalid")
        result["revision"]=request["revision"];return result

    def marker(self,request):
        desired=self.scope(request);status=json.loads(self.command([self.cli,"operation","maintenance","status"]))
        value=status.get("scope")
        if (status.get("bootHeld") is not True or not isinstance(value,dict) or any(value.get(key)!=item for key,item in desired.items())
                or not (set(value)=={"instanceUuid","templateUpgradeUuid","operationUuid","revision"}
                        or (status.get("maintenanceKind")=="SERVICE" and set(value)=={"instanceUuid","maintenanceUuid","operationUuid","revision"} and value["maintenanceUuid"]==value["operationUuid"]))):
            raise ValueError("AD daemon lifecycle requires its exact approved maintenance scope")
        return value

    def observe(self):
        output=self.command(["systemctl","show",AD_WINBIND_UNIT,"--property=MainPID,ActiveState","--no-pager"])
        values=dict(line.split("=",1) for line in output.splitlines() if "=" in line)
        if values.get("ActiveState")!="active":return {"active":False,"unit":AD_WINBIND_UNIT}
        pid=int(values.get("MainPID") or "0")
        if pid<=0:raise ValueError("Owned winbind daemon has no main PID")
        process=Path("/proc")/str(pid);arguments=[os.fsdecode(item) for item in (process/"cmdline").read_bytes().split(bytes([0])) if item]
        if (os.readlink(process/"exe")!="/usr/sbin/winbindd" or arguments.count("--configfile="+AD_WINBIND_CONFIGURATION)!=1
                or "/"+AD_WINBIND_UNIT not in (process/"cgroup").read_text()):
            raise ValueError("AD daemon PID executable/configuration/unit differs")
        return {"active":True,"unit":AD_WINBIND_UNIT,"pid":pid,"startTicks":(process/"stat").read_text().rpartition(")")[2].split()[19]}

    def install(self):
        directory=self.unit_directory;directory.mkdir(parents=True,mode=0o755,exist_ok=True);info=directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:raise ValueError("AD unit directory is not protected")
        path=directory/AD_WINBIND_UNIT
        if path.exists() or path.is_symlink():
            observed=path.lstat()
            if not stat.S_ISREG(observed.st_mode) or observed.st_uid!=os.geteuid() or observed.st_mode&0o022 or path.read_text()!=AD_WINBIND_UNIT_CONTENT:
                raise ValueError("Owned AD unit definition changed")
        else:
            descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o644)
            with os.fdopen(descriptor,"w") as handle:handle.write(AD_WINBIND_UNIT_CONTENT);handle.flush();os.fsync(handle.fileno())
        self.command(["systemctl","daemon-reload"])

    def authorize(self,request):
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")!="9":raise ValueError("AD daemon starts require the native writer descriptor")
        scope=self.scope(request);marker=self.marker(request);lock=os.fstat(9)
        if not stat.S_ISREG(lock.st_mode) or lock.st_uid!=os.geteuid() or stat.S_IMODE(lock.st_mode)!=0o600:raise ValueError("AD native writer descriptor is not protected")
        lock_path=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock"))
        named=lock_path.lstat()
        if (named.st_dev,named.st_ino,named.st_uid,stat.S_IMODE(named.st_mode))!=(lock.st_dev,lock.st_ino,os.geteuid(),0o600):raise ValueError("AD writer descriptor differs from its named lock")
        pattern=r"(?m)^lock:\s+[0-9]+:\s+FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+[0-9a-f]+:[0-9a-f]+:"+str(lock.st_ino)+r"\s+0\s+EOF\s*$"
        if not re.search(pattern,Path("/proc/self/fdinfo/9").read_text()):raise ValueError("AD daemon writer does not hold its own exclusive lock")
        record={"writerLockPath":str(lock_path),"scope":scope,"maintenanceScope":marker,"pid":os.getpid(),"startTicks":Path("/proc/self/stat").read_text().rpartition(")")[2].split()[19],
                "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"lockDevice":lock.st_dev,"lockInode":lock.st_ino}
        root_receipt_write(self.authorization/"writer.json",record);return record

    def start(self,request):
        self.marker(request);self.authorize(request)
        try:
            self.install()
            self.command(["systemctl","enable",AD_WINBIND_UNIT])
            self.command(["systemctl","start",AD_WINBIND_UNIT]);observed=self.observe()
            if observed.get("active") is not True:raise ValueError("Owned AD daemon did not become active")
            return {"success":True,"scope":self.scope(request),"daemon":observed}
        finally:(self.authorization/"writer.json").unlink(missing_ok=True)

    def stop(self,request):
        self.marker(request);before=self.observe()
        if before["active"]:
            if self.observe()!=before:raise ValueError("AD daemon identity changed before stop")
            self.command(["systemctl","stop",AD_WINBIND_UNIT])
        if self.observe()["active"]:raise ValueError("Owned AD daemon remains active after stop")
        return {"success":True,"scope":self.scope(request),"daemonStopped":True}

    def gate(self):
        maintenance=json.loads(self.command([self.cli,"operation","maintenance","status"]))
        if maintenance.get("bootHeld") is not False:
            record=ad_protected_json(self.authorization/"writer.json")
            if record.get("maintenanceScope")!=maintenance.get("scope") or record.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip():raise ValueError("AD held boot is not authorized by its writer")
            process=Path("/proc")/str(record["pid"]);before=(process/"stat").read_text().rpartition(")")[2].split()[19]
            lock=(process/"fd/9").stat();named=Path(record["writerLockPath"]).lstat()
            if (named.st_dev,named.st_ino)!=(lock.st_dev,lock.st_ino):raise ValueError("AD writer named lock changed")
            descriptor=os.open(record["writerLockPath"],os.O_RDONLY|os.O_NOFOLLOW)
            try:
                try:fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
                except BlockingIOError:pass
                else:
                    fcntl.flock(descriptor,fcntl.LOCK_UN);raise ValueError("AD writer no longer holds its exclusive lock")
            finally:os.close(descriptor)
            if (before!=record.get("startTicks") or (lock.st_dev,lock.st_ino)!=(record.get("lockDevice"),record.get("lockInode"))
                    or not stat.S_ISREG(lock.st_mode) or lock.st_uid!=os.geteuid() or stat.S_IMODE(lock.st_mode)!=0o600):
                raise ValueError("AD writer process/descriptor identity changed")
        else:
            generation=json.loads(self.command([self.cli,"operation","generation","status"]))
            state=ad_protected_json(self.configuration/"smb-domain.json")
            if (generation.get("generationStatus")!="IN_SYNC" or generation.get("pendingOperationUuid") or state.get("state")!="JOINED"
                    or not isinstance(state.get("identityReceipt"),dict) or not isinstance(state.get("idmapPolicy"),dict)):
                raise ValueError("AD automatic boot lacks its committed joined identity")
            current=generation.get("generation") or {}
            protected_ad_policy(current.get("instanceUuid"),configuration=self.configuration)
        return {"success":True,"daemonStartAuthorized":True,"unit":AD_WINBIND_UNIT}
