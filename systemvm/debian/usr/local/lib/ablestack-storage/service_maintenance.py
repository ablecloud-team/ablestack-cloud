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

"""Approved downtime boundary with durable typed scopes and owned acceptors."""
import json
import os
from pathlib import Path
import stat
import uuid
import ipaddress
import re
import signal
import subprocess
import time
from template_maintenance import Maintenance,scope,marker_kind


def service_run(arguments,timeout=15,pass_fds=()):
    if not callable(getattr(os,"pidfd_open",None)) or not callable(getattr(signal,"pidfd_send_signal",None)):
        raise ValueError("SERVICE maintenance requires exact child control")
    inherited=(9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else ()
    pass_fds=tuple(set((*pass_fds,*inherited)))
    child=subprocess.Popen(arguments,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,pass_fds=pass_fds)
    descriptor=None
    try:
        try:descriptor=os.pidfd_open(child.pid,0)
        except ProcessLookupError:
            if child.poll() is None:raise ValueError("Maintenance child identity is unavailable")
        try:output,_=child.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            if descriptor is not None:
                try:signal.pidfd_send_signal(descriptor,signal.SIGKILL,None,0)
                except ProcessLookupError:pass
            try:child.wait(timeout=.2)
            except subprocess.TimeoutExpired:pass
            raise TimeoutError("SERVICE maintenance command timed out; boot remains held")
        if child.returncode or len(output)>8*1024*1024:raise ValueError("SERVICE maintenance command failed")
        return output
    finally:
        if descriptor is not None:os.close(descriptor)
        if child.stdout:child.stdout.close()


class ServiceMaintenance:
    def __init__(self,maintenance,cli,run=None,command=None,units=None):
        self.maintenance=maintenance;self.cli=str(cli);self.run=run or service_run
        self.command=command or self.native;self.units=units or self.owned_units
        self.deadline=time.monotonic()+180
        self.journal=maintenance.root/"service-maintenance.json"

    def remaining(self):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("SERVICE maintenance total deadline exceeded")
        return min(15,remaining)

    def native(self,arguments,payload=None):
        inherited=(9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else ()
        descriptor=None
        try:
            if payload is not None:
                import fcntl
                descriptor=os.memfd_create("service-maintenance-request",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING);os.fchmod(descriptor,0o600)
                os.write(descriptor,json.dumps(payload,allow_nan=False).encode());os.lseek(descriptor,0,os.SEEK_SET)
                fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
                inherited=(*inherited,descriptor);arguments=(*arguments,"/proc/self/fd/"+str(descriptor))
            result=json.loads(self.run([self.cli,*arguments],timeout=self.remaining(),pass_fds=inherited))
            if result.get("success") is not True:raise ValueError("SERVICE native observation was not verified")
            return result
        finally:
            if descriptor is not None:os.close(descriptor)

    def fence(self,request):
        desired=scope(request,"SERVICE");actual=self.maintenance.generation()
        current=actual.get("generation") or {}
        if current.get("instanceUuid")!=desired["instanceUuid"] or type(current.get("revision")) is not int or current["revision"]>desired["revision"]:
            raise ValueError("SERVICE native generation scope is foreign or stale")
        pending=actual.get("pendingOperationUuid")
        if pending not in (None,desired["operationUuid"]):raise ValueError("Foreign generation blocks SERVICE maintenance")
        directory=Path(os.environ.get("ABLESTACK_STORAGE_VOLUME_OPERATIONS","/var/lib/ablestack-storage/volume-operations"))
        if directory.exists() or directory.is_symlink():
            info=directory.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:raise ValueError("Formatter journals are unprotected")
            for path in sorted(directory.iterdir()):
                if path.suffix!=".json":raise ValueError("Unknown formatter journal entry")
                volume=str(uuid.UUID(path.stem));observed=self.command(("volume","operation","status"),{"volumeUuid":volume})
                if (observed.get("formatterActive") is True or observed.get("terminationPending") is True
                        or observed.get("status") not in ("COMPLETE","MOUNT_EXISTING","NOT_STARTED")):
                    raise ValueError("Unresolved formatter blocks SERVICE maintenance")
        return desired

    def prove_unit(self,unit,require_active=True):
        managed_nfs=re.fullmatch(r"ablestack-storage-ganesha@([A-Za-z0-9_.-]+)\.service",unit)
        managed_smb=re.fullmatch(r"ablestack-storage-smb@([0-9a-f]{24})\.service",unit)
        managed_ad=unit=="ablestack-storage-winbind.service"
        if not managed_nfs and not managed_smb and not managed_ad:raise ValueError("SERVICE stop refuses a foreign or legacy acceptor")
        output=self.run(["systemctl","show",unit,"--property=MainPID,ActiveState,SubState","--no-pager"],timeout=self.remaining())
        value=dict(line.split("=",1) for line in output.splitlines() if "=" in line)
        if value.get("ActiveState")!="active":
            if require_active:raise ValueError("SERVICE acceptor is not active")
            return None
        pid=int(value.get("MainPID") or "0")
        if pid<=0:raise ValueError("SERVICE acceptor has no exact main PID")
        proc=Path("/proc")/str(pid);arguments=[os.fsdecode(item) for item in (proc/"cmdline").read_bytes().split(bytes([0])) if item]
        executable_path=os.readlink(proc/"exe");executable=Path(executable_path).name
        allowed={"/usr/bin/ganesha.nfsd","/opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd"} if managed_nfs else {"/usr/sbin/winbindd"} if managed_ad else {"/usr/sbin/smbd"}
        if executable_path not in allowed:raise ValueError("SERVICE executable is outside its packaged paths")
        if managed_nfs:
            configuration="/etc/ganesha/ablestack-storage/"+managed_nfs[1]+".conf"
            if executable!="ganesha.nfsd" or arguments.count("-f")!=1 or arguments[arguments.index("-f")+1]!=configuration:raise ValueError("SERVICE NFS unit has a foreign executable/configuration")
        elif managed_ad:
            configuration="/etc/ablestack-storage/ad-machine.conf"
            if arguments.count("--configfile="+configuration)!=1:raise ValueError("SERVICE owned AD daemon has a foreign configuration")
        else:
            configuration="/etc/samba/smb.conf"
            bindings=Path("/etc/ablestack-storage/smb-endpoint-listeners")/(managed_smb[1]+".json")
            endpoint=self.maintenance.read(bindings)
            if endpoint is None or executable!="smbd":raise ValueError("SERVICE SMB unit lacks a protected endpoint")
            if not any(argument in ("--configfile="+configuration,configuration) for argument in arguments):raise ValueError("SERVICE SMB unit has a foreign configuration")
        if "/"+unit not in (proc/"cgroup").read_text():raise ValueError("SERVICE main PID is outside its owned unit")
        if managed_nfs:
            real=Path(configuration).resolve(strict=True);info=real.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_size>8*1024*1024:raise ValueError("SERVICE NFS configuration is not protected")
            descriptor=os.open(real,os.O_RDONLY|os.O_NOFOLLOW)
            try:
                opened=os.fstat(descriptor)
                if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("SERVICE NFS configuration changed")
                content=os.read(descriptor,8*1024*1024+1).decode()
            finally:os.close(descriptor)
            addresses=re.findall(r"(?mi)^\s*Bind_Addr\s*=\s*([^;]+);",content);ports=re.findall(r"(?mi)^\s*NFS_Port\s*=\s*([0-9]+);",content)
            if len(addresses)!=1 or len(ports)!=1:raise ValueError("SERVICE NFS listener configuration is ambiguous")
            endpoints=[{"listenIp":str(ipaddress.ip_address(addresses[0].strip().strip(chr(34)))),"port":int(ports[0])}]
        elif managed_ad:endpoints=[]
        else:endpoints=[{"listenIp":str(ipaddress.ip_address(endpoint["listenIp"])),"port":int(endpoint["port"])}]
        if any(not 1<=endpoint["port"]<=65535 for endpoint in endpoints):raise ValueError("SERVICE listener port is invalid")
        return {"unit":unit,"pid":pid,"startTicks":(proc/"stat").read_text().rpartition(")")[2].split()[19],"configurationPath":configuration,"listenerEndpoints":endpoints}

    def listeners_clear(self,owners):
        endpoints=[endpoint for owner in owners for endpoint in owner.get("listenerEndpoints",[])]
        if not endpoints:return
        for arguments in (["ss","-H","-ltnp"],["ss","-H","-lunp"]):
            output=self.run(arguments,timeout=self.remaining())
            for line in output.splitlines():
                fields=line.split()
                if len(fields)<5:raise ValueError("SERVICE native listener readback is malformed")
                address,separator,port=fields[3].rpartition(":")
                if not separator or not port.isdigit():raise ValueError("SERVICE listener address readback is malformed")
                address=address.strip("[]");address="0.0.0.0" if address=="*" else str(ipaddress.ip_address(address))
                if any(int(port)==row["port"] and (address in ("0.0.0.0","::") or row["listenIp"] in (address,"0.0.0.0","::")) for row in endpoints):
                    raise ValueError("SERVICE listener remains exposed after owned stop")

    def owned_units(self):
        output=self.run(["systemctl","list-units","--state=active","--type=service","--no-legend","--plain",
                         "ablestack-storage-ganesha@*.service","ablestack-storage-smb@*.service","ablestack-storage-winbind.service",
                         "smbd.service","nmbd.service","winbind.service","nfs-ganesha.service","nfs-server.service","nfs-kernel-server.service"],timeout=self.remaining())
        names=[line.split()[0] for line in output.splitlines() if line.split()]
        if len(names)!=len(set(names)):raise ValueError("SERVICE owned acceptor set is ambiguous")
        return [self.prove_unit(unit) for unit in sorted(names)]

    def status(self,request=None):
        status=self.maintenance.status()
        if request is not None and status["bootHeld"] and (status["maintenanceKind"]!="SERVICE" or status["scope"]!=scope(request,"SERVICE")):
            raise ValueError("Foreign maintenance scope cannot be observed as this SERVICE operation")
        journal=self.maintenance.read(self.journal)
        if journal is not None and status["bootHeld"] and journal.get("scope")!=status["scope"]:raise ValueError("SERVICE journal scope differs from its boot hold")
        return {**status,"serviceMaintenanceSupported":True,"drainSupported":False,"phase":journal.get("phase") if journal and status["bootHeld"] else None,
                "stoppedUnits":journal.get("stoppedUnits",[]) if journal and status["bootHeld"] else [],
                **({key:journal[key] for key in ("serviceSourceStoppedVerified","stoppedReceiptSha256","sourceGeneration","sourceConfigurationSha256","publicAdPreStopSha256","publicLocalMachineSid") if key in journal} if journal and status["bootHeld"] else {})}

    def enter(self,request):
        desired=self.fence(request);status=self.maintenance.status()
        if status["bootHeld"] and (status["maintenanceKind"]!="SERVICE" or status["scope"]!=desired):raise ValueError("Foreign boot hold blocks SERVICE entry")
        journal=self.maintenance.read(self.journal)
        if journal and journal.get("scope")==desired and journal.get("phase")=="HELD":return self.status(request)
        owners=self.units() # Validate the entire ownership set before any marker/effect.
        source_generation=self.maintenance.generation()
        source_rendered=self.command(("operation","generation","render-status"))
        if source_rendered.get("bootHeld") is True:raise ValueError("Interrupted rendered writer blocks SERVICE entry")
        recovering=bool(journal and journal.get("scope")==desired and status.get("bootHeld") is True and status.get("maintenanceKind")=="SERVICE")
        if not recovering:
            source_current=source_generation.get("generation") or {};source_sha=source_generation.get("configurationSha256")
            if (not isinstance(source_sha,str) or not re.fullmatch("[0-9a-f]{64}",source_sha)
                    or source_current.get("configurationSha256")!=source_sha
                    or source_generation.get("pendingOperationUuid") not in (None,desired["operationUuid"])):
                raise ValueError("SERVICE source current/configuration digest drifted before entry")
            baseline=source_rendered.get("current")
            if baseline is None or (baseline.get("configurationSha256")!=source_sha
                    or any(baseline.get("scope",{}).get(key)!=source_current.get(key) for key in ("instanceUuid","operationUuid","revision"))):
                raise ValueError("SERVICE rendered baseline differs from its exact native source generation")

        identity_checkpoint=self.maintenance.root/("service-identity-source-"+desired["operationUuid"]+".json")
        domain_file=Path(os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))/"smb-domain.json"
        domain_state=self.maintenance.read(domain_file) if domain_file.exists() or domain_file.is_symlink() else None
        identity_required=bool(identity_checkpoint.exists() or identity_checkpoint.is_symlink() or isinstance(domain_state,dict) and str(domain_state.get("state") or domain_state.get("joinState") or "").upper()=="JOINED")
        if identity_required:
            captured=self.command(("operation","generation","render-service-source-quiesce-guard"),desired)
            if captured.get("scope")!=desired or captured.get("sourceCaptured") is not True or captured.get("quiesceAuthorized") is not True:
                raise ValueError("SERVICE identity stop lacks its protected PRESTOP capture")
        self.maintenance.enter(request,"SERVICE")
        if not journal or journal.get("scope")!=desired:
            journal={"scope":desired,"phase":"STOPPING","owners":owners,"stoppedUnits":[],"sourceGeneration":source_generation["generation"],"sourceRendered":source_rendered.get("current"),"sourceActivation":source_rendered.get("activation")}
            self.maintenance.write(self.journal,journal)
        try:
            for owner in sorted(journal["owners"],key=lambda row:row["unit"]=="ablestack-storage-winbind.service"):
                unit=owner["unit"]
                if unit in journal["stoppedUnits"]:continue
                fresh=self.prove_unit(unit,False) if self.units==self.owned_units else next((row for row in self.units() if row["unit"]==unit),None)
                if fresh is not None and fresh!=owner:raise ValueError("SERVICE acceptor identity changed before stop")
                if fresh is not None:self.run(["systemctl","stop",unit],timeout=self.remaining())
                journal["stoppedUnits"].append(unit);self.maintenance.write(self.journal,journal)
            if self.units():raise ValueError("SERVICE acceptors remain active after owned stop")
            self.listeners_clear(journal["owners"])
            journal["phase"]="HELD";self.maintenance.write(self.journal,journal)
            if identity_required:
                stopped=self.command(("operation","generation","render-service-source-stopped"),desired)
                if stopped.get("scope")!=desired or stopped.get("serviceSourceStoppedVerified") is not True or not re.fullmatch("[0-9a-f]{64}",str(stopped.get("stoppedReceiptSha256"))):
                    raise ValueError("SERVICE identity source lacks its protected AFTERSTOP receipt")
                journal.update({key:stopped[key] for key in ("serviceSourceStoppedVerified","stoppedReceiptSha256","sourceGeneration","sourceConfigurationSha256","publicAdPreStopSha256","publicLocalMachineSid")})
                self.maintenance.write(self.journal,journal)
            return self.status(request)
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";self.maintenance.write(self.journal,journal)
            raise

    def quiesce(self,request):
        desired=self.fence(request);status=self.status(request)
        if status.get("maintenanceKind")!="SERVICE" or status.get("bootHeld") is not True:raise ValueError("SERVICE quiesce requires its protected held scope")
        domains=request.get("domains")
        if not isinstance(domains,list) or not domains or len(set(domains))!=len(domains) or not set(domains)<={"NFS","SMB"}:raise ValueError("SERVICE quiesce domains are invalid")
        def domain(unit):return "NFS" if unit.startswith("ablestack-storage-ganesha@") else "SMB"
        owners=[owner for owner in self.units() if domain(owner["unit"]) in domains]
        stopped=[]
        for owner in sorted(owners,key=lambda row:row["unit"]=="ablestack-storage-winbind.service"):
            fresh=self.prove_unit(owner["unit"],False) if self.units==self.owned_units else next((row for row in self.units() if row["unit"]==owner["unit"]),None)
            if fresh!=owner:raise ValueError("SERVICE acceptor changed before inverse quiesce")
            self.run(["systemctl","stop",owner["unit"]],timeout=self.remaining());stopped.append(owner["unit"])
        if any(domain(owner["unit"]) in domains for owner in self.units()):raise ValueError("SERVICE inverse acceptors remain active")
        self.listeners_clear(owners)
        return {"success":True,"quiesced":True,"scope":desired,"bootHeld":True,"maintenanceKind":"SERVICE","domainsQuiesced":domains,"stoppedUnits":stopped,"drainSupported":False}

    def release(self,request):
        desired=self.fence(request);status=self.status(request)
        if not status["bootHeld"]:return self.maintenance.release(request,"SERVICE")
        journal=self.maintenance.read(self.journal)
        if journal is None or journal.get("scope")!=desired:raise ValueError("SERVICE release lacks its protected owned-unit receipt")
        # The coordinator must restore/apply current immutable runtime while the
        # boot marker remains held. Native readback is fresh and all-four exact.
        observed=self.command(("operation","generation","render-maintenance-verify"),request)
        if observed.get("runtimeVerified") is not True or observed.get("scope")!=desired:
            raise ValueError("SERVICE runtime was not freshly verified under its held scope")
        result=self.maintenance.release(request,"SERVICE")
        journal["phase"]="RELEASED";self.maintenance.write(self.journal,journal)
        return {**result,"serviceMaintenanceSupported":True,"drainSupported":False,"runtimeVerified":True}
