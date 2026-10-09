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

"""Read-only identity/ownership evidence and scoped maintenance acceptor rebind."""
import hashlib
import ast
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import time
import tempfile
import uuid

IDENTITY_DATABASES = {"PASSDB":"/var/lib/samba/private/passdb.tdb", "SECRETS":"/var/lib/samba/private/secrets.tdb"}
LOCK_DATABASE_NAMES = {"locking.tdb", "brlock.tdb", "smbXsrv_session_global.tdb", "smbXsrv_tcon_global.tdb", "smbXsrv_open_global.tdb"}


def identity_json(path):
    info=path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_size>8*1024*1024:
        raise ValueError("SMB identity input is not protected")
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        opened=os.fstat(fd)
        if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("SMB identity input changed")
        return json.loads(os.read(fd,8*1024*1024+1))
    finally:os.close(fd)


class SmbIdentity:
    def __init__(self,cli=None):
        self.deadline=time.monotonic()+180
        self.cli=Path(cli or "/usr/local/bin/ablestack-storagectl")
        self.configuration=Path("/etc/ablestack-storage")
        self.process_root=Path("/proc")
        self.unit_path=Path("/etc/systemd/system/ablestack-storage-smb@.service")
        self.generations=Path(os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR","/var/lib/ablestack-storage/config-generations"))

    def remaining(self,maximum=10):
        value=min(maximum,self.deadline-time.monotonic())
        if value<=0:raise TimeoutError("SMB identity maintenance total deadline exceeded")
        return value

    def run(self,args):
        result=subprocess.run(args,capture_output=True,text=True,timeout=self.remaining())
        if result.returncode:raise ValueError("SMB identity observation/maintenance command failed")
        return result.stdout

    def scope(self,request):
        value={key:str(uuid.UUID(request[key])) for key in ("instanceUuid","operationUuid")}
        revision=request["revision"]
        if isinstance(revision,bool) or not isinstance(revision,int) or revision<1:raise ValueError("SMB identity scope revision is invalid")
        return {**value,"revision":revision}

    def generation(self,scope,writer=False):
        current=identity_json(self.generations/"current.json")
        if current.get("instanceUuid")!=scope["instanceUuid"]:raise ValueError("SMB identity native instance scope is foreign")
        pending=identity_json(self.generations/"pending.json") if (self.generations/"pending.json").exists() else None
        if writer and current.get("revision")!=scope["revision"]:
            raise ValueError("SMB repair must preserve the exact current desired revision")
        if pending and any(pending.get(key)!=value for key,value in scope.items()):raise ValueError("SMB identity native writer scope is foreign")
        return current

    def socket_rows(self,output):
        rows=[]
        for line in output.splitlines():
            fields=line.split()
            if len(fields)<5:raise ValueError("SMB socket observation is malformed")
            address,separator,port=fields[3].rpartition(":")
            if not separator or not port.isdigit():raise ValueError("SMB socket address is malformed")
            address=address.strip("[]");address="0.0.0.0" if address=="*" else address
            address=str(ipaddress.ip_address(address))
            rows.append({"ip":address,"port":int(port),"state":fields[0],"pids":sorted({int(pid) for pid in re.findall(r"pid=(\d+)",line)})})
        return rows

    def endpoints(self):
        desired=identity_json(self.configuration/"desired-state/smb-share-apply.json")
        rows=[] if desired.get("enabled") is False else desired.get("listeners") or [{"listenIp":desired.get("listenIp") or "0.0.0.0","port":desired.get("port") or 445}]
        result=[]
        for row in rows:
            if row.get("state","Ready") in ("Disabled","Destroyed","Error"):continue
            ip=str(ipaddress.ip_address(row.get("listenIp") or "0.0.0.0"));port=int(row.get("port") or 445)
            if not 1<=port<=65535:raise ValueError("SMB identity endpoint port is invalid")
            key=hashlib.sha256((ip+":"+str(port)).encode()).hexdigest()[:24]
            endpoint={"listenIp":ip,"port":port,"key":key,"unit":"ablestack-storage-smb@"+key+".service"}
            if endpoint not in result:result.append(endpoint)
        return sorted(result,key=lambda item:(item["listenIp"],item["port"]))

    def database_identity(self):
        values={}
        for label,name in IDENTITY_DATABASES.items():
            path=Path(name)
            if not path.exists():values[label]={"path":name,"present":False};continue
            info=path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o077:raise ValueError("SMB credential database is not protected")
            values[label]={"path":name,"present":True,"device":info.st_dev,"inode":info.st_ino,"uid":info.st_uid,"gid":info.st_gid,"mode":format(stat.S_IMODE(info.st_mode),"04o")}
        return values

    def master(self,pid,databases,default_pid,allowed_units):
        root=Path("/proc")/str(pid)
        if (root/"comm").read_text().strip()!="smbd":raise ValueError("SMB listener PID is not smbd")
        arguments=[item.decode() for item in (root/"cmdline").read_bytes().split(bytes([0])) if item]
        configuration="/etc/samba/smb.conf"
        configurations=[]
        for index,argument in enumerate(arguments):
            if argument in ("-s","--configfile"):
                if index+1>=len(arguments):raise ValueError("SMB listener config argument is incomplete")
                configurations.append(arguments[index+1])
            elif argument.startswith("--configfile="):configurations.append(argument.split("=",1)[1])
        if configurations:
            if len(set(configurations))!=1:raise ValueError("SMB listener has ambiguous configuration arguments")
            configuration=configurations[0]
        if configuration!="/etc/samba/smb.conf":raise ValueError("SMB listener uses an unrelated configuration")
        cgroups=(root/"cgroup").read_text()
        units=[unit for unit in allowed_units if ("/"+unit) in cgroups]
        unit="smbd.service" if pid==default_pid and "/smbd.service" in cgroups else units[0] if len(units)==1 else None
        if not unit:raise ValueError("SMB listener is not a known owned acceptor")
        descriptors=[];locks_aligned=True
        for descriptor in (root/"fd").iterdir():
            try:
                target=os.readlink(descriptor);canonical=target[:-10] if target.endswith(" (deleted)") else target
                if canonical not in IDENTITY_DATABASES.values() and Path(canonical).name not in LOCK_DATABASE_NAMES:continue
                info=descriptor.stat();actual=Path(canonical).stat() if Path(canonical).exists() else None
                aligned=bool(actual and info.st_dev==actual.st_dev and info.st_ino==actual.st_ino and info.st_nlink>0)
                entry={"fd":int(descriptor.name),"path":canonical,"device":info.st_dev,"inode":info.st_ino,"deleted":target.endswith(" (deleted)"),"matchesCurrentPathInode":aligned}
                descriptors.append(entry)
                if Path(canonical).name in LOCK_DATABASE_NAMES and not aligned:locks_aligned=False
            except FileNotFoundError:continue
        return {"pid":pid,"startTicks":(root/"stat").read_text().rpartition(")")[2].split()[19],"unit":unit,
                "configurationPath":configuration,"databaseDescriptors":sorted(descriptors,key=lambda item:item["fd"]),"lockingDatabasesAligned":locks_aligned}

    def sessions(self,endpoints,masters):
        observed=self.socket_rows(self.run(["ss","-H","-ntp"]))
        def owned(row):return any(row["port"]==item["port"] and (item["listenIp"] in ("0.0.0.0","::") or row["ip"]==item["listenIp"]) for item in endpoints)
        actual=[row for row in observed if owned(row) and row["state"] in ("ESTAB","SYN-RECV","SYN_RECV")]
        status=json.loads(self.run(["smbstatus","--json"]))
        locks=json.loads(self.run(["smbstatus","--json","--locks"]))
        if not all(isinstance(status.get(key),dict) for key in ("sessions","tcons","open_files")) or not isinstance(locks.get("open_files"),dict):
            raise ValueError("SMB session/byte-lock observation is unavailable")
        locking=all(item["lockingDatabasesAligned"] for item in masters)
        proof={"available":True,"establishedTcpCount":sum(row["state"]=="ESTAB" for row in actual),"synRecvTcpCount":sum(row["state"]!="ESTAB" for row in actual),
               "smbSessionCount":len(status["sessions"]),"treeConnectionCount":len(status["tcons"]),"openFileCount":len(status["open_files"]),
               "byteLockOpenFileCount":len(locks["open_files"]),"lockingDatabasesAligned":locking}
        proof["safeToRebind"]=locking and not any(proof[key] for key in ("establishedTcpCount","synRecvTcpCount","smbSessionCount","treeConnectionCount","openFileCount","byteLockOpenFileCount"))
        return proof

    def identity_holders(self):
        values=[]
        for process in Path("/proc").iterdir():
            self.remaining()
            if not process.name.isdigit():continue
            try:
                if (process/"comm").read_text().strip() not in ("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd"):continue
                for descriptor in (process/"fd").iterdir():
                    try:
                        target=os.readlink(descriptor);canonical=target[:-10] if target.endswith(" (deleted)") else target
                        if canonical not in IDENTITY_DATABASES.values():continue
                        info=descriptor.stat();values.append({"pid":int(process.name),"fd":int(descriptor.name),"path":canonical,"device":info.st_dev,"inode":info.st_ino,"deleted":target.endswith(" (deleted)")})
                    except FileNotFoundError:continue
            except FileNotFoundError:continue
        return values

    def repair_journal(self,scope,value=None):
        root=self.generations.parent/"smb-identity-repairs"
        path=root/(scope["operationUuid"]+".json")
        if value is None:return identity_json(path) if path.exists() else None
        root.mkdir(parents=True,mode=0o700,exist_ok=True)
        info=root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o077:raise ValueError("SMB repair journal directory is not protected")
        fd,temporary=tempfile.mkstemp(prefix=".smb-repair-",dir=root)
        try:
            os.fchmod(fd,0o600)
            with os.fdopen(fd,"w") as handle:json.dump(value,handle,sort_keys=True);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,path)
            descriptor=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(descriptor)
            finally:os.close(descriptor)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)
        return value

    def inspect_unconfigured(self, scope, current):
        desired_path = self.configuration / "desired-state/smb-share-apply.json"
        if os.path.lexists(desired_path):
            raise ValueError("SMB unconfigured source declaration exists")
        parent = desired_path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
            raise ValueError("SMB unconfigured source parent is not protected")
        artifact = identity_json(self.generations / (current["operationUuid"] + ".json"))
        keys = ("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt")
        if artifact.get("phase") != "VERIFIED" or any(artifact.get(key) != current.get(key) for key in keys):
            raise ValueError("SMB unconfigured source does not match its verified native generation")
        desired = artifact.get("desired")
        names = {"desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json",
                 "iscsi-targets.json", "nvmeof-subsystems.json", "posix-directory-policies.json",
                 "network-endpoints.json", "sharedfs-network.json"}
        if (not isinstance(desired, dict) or set(desired) != names
                or desired["desired-state/smb-share-apply.json"] is not None
                or hashlib.sha256(json.dumps(desired, sort_keys=True, separators=(",", ":")).encode()).hexdigest() != current["configurationSha256"]):
            raise ValueError("SMB unconfigured source declaration lacks its original seven-file commitment")
        pending = identity_json(self.generations / "pending.json") if (self.generations / "pending.json").exists() else None
        if pending:
            if pending.get("phase") != "PREPARED" or pending.get("previous") != current or pending.get("beforeSha256") != current["configurationSha256"]:
                raise ValueError("SMB unconfigured source does not match its pending writer baseline")
        elif scope["revision"] != current["revision"]:
            raise ValueError("SMB unconfigured observation revision is stale")
        databases = self.database_identity()
        if set(databases) != set(IDENTITY_DATABASES) or any(not isinstance(value, dict) or value.get("present") is not False for value in databases.values()):
            raise ValueError("SMB unconfigured source contains an identity database")
        sockets = self.socket_rows(self.run(["ss", "-H", "-ltnp"]))
        if any(row["port"] in (139, 445) for row in sockets):
            raise ValueError("SMB unconfigured source has a foreign or unobserved acceptor")
        for process in self.process_root.iterdir():
            self.remaining()
            if not process.name.isdigit():
                continue
            try:
                name = (process / "comm").read_text().strip()
            except FileNotFoundError:
                continue
            if name in ("smbd", "nmbd", "winbindd", "samba", "samba-dcerpcd", "samba-bgqd"):
                raise ValueError("SMB unconfigured source still has an identity daemon")
        if self.identity_holders():
            raise ValueError("SMB unconfigured source still has an identity holder")
        for unit in ("smbd.service", "nmbd.service", "winbind.service"):
            pid = self.run(["systemctl", "show", unit, "--property=MainPID", "--value"]).strip()
            if pid != "0":
                raise ValueError("SMB unconfigured source has an unobserved or active identity unit")
        sessions = {"available": True, "establishedTcpCount": 0, "synRecvTcpCount": 0, "smbSessionCount": 0,
                    "treeConnectionCount": 0, "openFileCount": 0, "byteLockOpenFileCount": 0,
                    "lockingDatabasesAligned": True, "safeToRebind": True, "source": "UNCONFIGURED_SMB_SOURCE_ABSENCE"}
        return {"success": True, "smbIdentitySupported": True, "scope": scope,
                "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(), "generation": current,
                "configurationSha256": hashlib.sha256(Path("/etc/samba/smb.conf").read_bytes()).hexdigest(),
                "identityBaselineKind": "UNCONFIGURED_SMB_SOURCE", "databases": databases,
                "masters": [], "ownedEndpoints": [], "ownershipVerified": True, "endpointTcpReady": True,
                "identityDatabaseAligned": True, "requiresQuiesce": False, "identityRestoreSafe": True,
                "identityHolders": [], "generatedEpoch": time.time(), "sessions": sessions}

    def inspect(self,request,allow_missing=False):
        scope=self.scope(request);current=self.generation(scope)
        if not os.path.lexists(self.configuration / "desired-state/smb-share-apply.json"):
            from smb_current_retention import SmbCurrentRetention
            retained = SmbCurrentRetention(self.cli, handler=self).inspect_retained(scope, current)
            if retained is not None:
                return retained
            return self.inspect_unconfigured(scope, current)
        endpoints=self.endpoints();databases=self.database_identity()
        sockets=self.socket_rows(self.run(["ss","-H","-ltnp"]))
        default_pid=int(self.run(["systemctl","show","smbd.service","--property=MainPID","--value"]).strip() or 0)
        pids=set();owners=[]
        for endpoint in endpoints:
            matches=[row for row in sockets if row["state"]=="LISTEN" and row["ip"]==endpoint["listenIp"] and row["port"]==endpoint["port"]]
            matched={pid for row in matches for pid in row["pids"]}
            if not matched and allow_missing:
                owners.append({**endpoint,"pid":None,"listenerOwned":False,"listening":False});continue
            if len(matched)!=1:raise ValueError("SMB endpoint listener ownership is unavailable or ambiguous")
            pids.update(matched);owners.append({**endpoint,"pid":next(iter(matched)),"listenerOwned":True,"listening":True})
        for endpoint in owners:
            endpoint["tcpReady"]=False
            if endpoint["listenerOwned"]:
                address="127.0.0.1" if endpoint["listenIp"]=="0.0.0.0" else "::1" if endpoint["listenIp"]=="::" else endpoint["listenIp"]
                try:
                    with socket.create_connection((address,endpoint["port"]),timeout=self.remaining(1)):endpoint["tcpReady"]=True
                except OSError:pass
        masters=[self.master(pid,databases,default_pid,{row["unit"] for row in endpoints}) for pid in sorted(pids)]
        holders=self.identity_holders()
        aligned=all(entry["matchesCurrentPathInode"] for item in masters for entry in item["databaseDescriptors"] if entry["path"] in IDENTITY_DATABASES.values())
        return {"success":True,"smbIdentitySupported":True,"scope":scope,"bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                "generation":current,"configurationSha256":hashlib.sha256(Path("/etc/samba/smb.conf").read_bytes()).hexdigest(),"databases":databases,
                "masters":masters,"ownedEndpoints":owners,"ownershipVerified":all(item["listenerOwned"] for item in owners),"endpointTcpReady":all(item["tcpReady"] for item in owners),"identityDatabaseAligned":aligned,
                "requiresQuiesce":bool(holders),"identityRestoreSafe":not holders,"identityHolders":holders,"generatedEpoch":time.time(),"sessions":self.sessions(endpoints,masters)}

    def managed_unit_definition(self):
        # Extract only the fixed string already used by the signed lifecycle.
        # Extra ExecStartPost/foreign command lines are never accepted.
        text=self.cli.read_text()
        block=next((item for item in re.findall(r"<<'PY'\n(.*?)\nPY",text,re.S) if "def reconcile_smb_endpoint_units(" in item),None)
        if block is None:raise ValueError("Signed SMB unit lifecycle is unavailable")
        tree=ast.parse(block)
        function=next(item for item in tree.body if isinstance(item,ast.FunctionDef) and item.name=="reconcile_smb_endpoint_units")
        definitions=[item.value for item in ast.walk(function) if isinstance(item,ast.Assign) and any(isinstance(target,ast.Name) and target.id=="unit_content" for target in item.targets)]
        if len(definitions)!=1:raise ValueError("Signed SMB unit definition is ambiguous")
        definition=ast.literal_eval(definitions[0])
        if not isinstance(definition,str):raise ValueError("Signed SMB unit definition is invalid")
        return definition

    def open_master_handles(self,masters):
        if not callable(getattr(os,"pidfd_open",None)) or not callable(getattr(signal,"pidfd_send_signal",None)):
            raise ValueError("SMB_PIDFD_REQUIRED: safe maintenance signalling is unsupported")
        handles={}
        try:
            for master in masters:
                descriptor=os.pidfd_open(master["pid"],0)
                handles[master["pid"]]=descriptor
                actual=self.process_root/str(master["pid"])
                if (actual/"stat").read_text().rpartition(")")[2].split()[19]!=master["startTicks"]:
                    raise ValueError("SMB master PID identity changed after pidfd pinning")
            return handles
        except Exception:
            for descriptor in handles.values():os.close(descriptor)
            raise

    def result(self,current,previous,rebound,idempotent=False):
        return {**current,"rebound":rebound,"idempotent":idempotent,"noConfigurationChange":True,
                "databasesUnchanged":current["databases"]==previous["databases"],
                "configurationUnchanged":current["configurationSha256"]==previous["configurationSha256"],
                "generationAdvanced":False,"verification":current,"previousMasters":previous["masters"]}

    def wait_for_ready(self,request,frozen,require_drained=True):
        # systemctl start acknowledges process creation before Samba has opened
        # its databases/listeners. Reobserve only; never signal/restart a healthy
        # acceptor to resolve this readiness race.
        deadline=time.monotonic()+self.remaining(30)
        attempts=0
        while True:
            attempts+=1
            result=self.inspect(request,allow_missing=True)
            stable=("scope","bootId","generation","configurationSha256","databases")
            if any(result.get(key)!=frozen.get(key) for key in stable):
                raise ValueError("SMB rebind source scope/configuration/database changed during readiness")
            if (result["identityDatabaseAligned"] and result["ownershipVerified"] and result.get("endpointTcpReady",True)
                    and (not require_drained or result["sessions"]["safeToRebind"] is True)):
                return {**result,"readinessAttempts":attempts}
            remaining=min(deadline-time.monotonic(),self.deadline-time.monotonic())
            if remaining<=0:raise TimeoutError("SMB acceptor readiness did not verify within its fixed deadline")
            time.sleep(min(.1,remaining))

    def rebind(self,request):
        scope=self.scope(request);self.generation(scope,writer=True)
        journal=self.repair_journal(scope)
        before=self.inspect(request,allow_missing=bool(journal));expected=request.get("expected")
        frozen=("scope","bootId","generation","configurationSha256","databases","masters","ownedEndpoints","ownershipVerified")
        if journal:
            if journal["scope"]!=scope or journal["expected"]!=expected:raise ValueError("SMB repair scope/expected evidence changed")
            stable=("bootId","generation","configurationSha256","databases")
            if any(before.get(key)!=expected.get(key) for key in stable):raise ValueError("SMB interrupted repair source identity/configuration changed")
            wanted=[{key:item[key] for key in ("listenIp","port","key","unit")} for item in expected["ownedEndpoints"]]
            actual=[{key:item[key] for key in ("listenIp","port","key","unit")} for item in before["ownedEndpoints"]]
            if wanted!=actual:raise ValueError("SMB interrupted repair endpoint registry changed")
            if before["identityDatabaseAligned"] and before["ownershipVerified"]:
                result=self.wait_for_ready(request,expected,require_drained=journal["phase"]!="COMPLETE")
                journal["phase"]="COMPLETE";journal["completedEpoch"]=time.time();journal["resultMasters"]=result["masters"]
                journal["readinessAttempts"]=result["readinessAttempts"];self.repair_journal(scope,journal)
                return self.result(result,expected,rebound=True,idempotent=True)
            # All present listeners were still resolved to known units by
            # inspect. Resume the same frozen repair through the common drained
            # rebind path; missing old acceptors are started only from its
            # immutable desired endpoint registry.
        if not journal and (not isinstance(expected,dict) or any(expected.get(key)!=before[key] for key in frozen)):raise ValueError("SMB identity maintenance evidence changed before rebind")
        if before["sessions"]["safeToRebind"] is not True:raise ValueError("SMB identity sessions/locks must drain before acceptor rebind")
        if before["identityDatabaseAligned"] and before["ownershipVerified"] and before.get("endpointTcpReady",True):
            if journal:
                journal["phase"]="COMPLETE";self.repair_journal(scope,journal)
                return self.result(before,expected,rebound=True,idempotent=True)
            return {**self.result(before,before,rebound=True),"alreadyAligned":True}
        # Validate every ownership/registry/unit and public config before any
        # signal. A is recreated as its existing dedicated registry unit; a
        # default master restart would bind B's address and steal that socket.
        for endpoint in before["ownedEndpoints"]:
            record=identity_json(self.configuration/"smb-endpoint-listeners"/(endpoint["key"]+".json"))
            if record!={"listenIp":endpoint["listenIp"],"port":endpoint["port"]}:raise ValueError("SMB owned endpoint registry changed")
        unit=self.unit_path
        info=unit.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or unit.read_text()!=self.managed_unit_definition():
            raise ValueError("SMB owned service definition is unavailable or foreign")
        self.run(["testparm","-s","/etc/samba/smb.conf"])
        journal=journal or {"scope":scope,"expected":expected,"phase":"PREPARED","startedEpoch":time.time()}
        self.repair_journal(scope,journal)
        handles=self.open_master_handles(before["masters"])
        paused=[];stopped=[]
        try:
            journal["phase"]="DRAINING";self.repair_journal(scope,journal)
            for master in before["masters"]:
                signal.pidfd_send_signal(handles[master["pid"]],signal.SIGSTOP,None,0);paused.append(master["pid"])
            if self.sessions(before["ownedEndpoints"],before["masters"])["safeToRebind"] is not True:raise ValueError("SMB connection raced the maintenance drain")
            journal["phase"]="REBINDING";self.repair_journal(scope,journal)
            # TERM is queued while each exact master is paused, then CONT lets
            # its shutdown handler run. Existing negotiated sessions were proved
            # absent and new application accepts remain held through this bound.
            for master in before["masters"]:
                signal.pidfd_send_signal(handles[master["pid"]],signal.SIGTERM,None,0);signal.pidfd_send_signal(handles[master["pid"]],signal.SIGCONT,None,0);paused.remove(master["pid"])
            for unit in sorted({master["unit"] for master in before["masters"]}):
                self.run(["systemctl","stop",unit]);stopped.append(unit)
            for endpoint in before["ownedEndpoints"]:self.run(["systemctl","start",endpoint["unit"]])
            journal["phase"]="VERIFYING";self.repair_journal(scope,journal)
            result=self.wait_for_ready(request,before)
            journal["phase"]="COMPLETE";journal["completedEpoch"]=time.time();journal["resultMasters"]=result["masters"];journal["readinessAttempts"]=result["readinessAttempts"];self.repair_journal(scope,journal)
            return self.result(result,expected,rebound=True)
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";journal["stoppedUnits"]=stopped;self.repair_journal(scope,journal)
            raise
        finally:
            for pid in paused:
                try:signal.pidfd_send_signal(handles[pid],signal.SIGCONT,None,0)
                except ProcessLookupError:pass
            for descriptor in handles.values():os.close(descriptor)
