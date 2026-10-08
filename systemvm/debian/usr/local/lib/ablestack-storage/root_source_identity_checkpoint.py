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

"""Public PRESTOP identity and scoped owned-stop proof; no private DB reads."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import ipaddress
from rendered_generation import rendered_read,no_rendered_secrets
from rendered_credentials import credential_json
from root_configuration_capsule import root_configuration_from_observation

ROOT_IDENTITY_COMMS=("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd")
ROOT_IDENTITY_DATABASES=("/var/lib/samba/private/passdb.tdb","/var/lib/samba/private/secrets.tdb","/var/lib/samba/winbindd_idmap.tdb")
ROOT_IDENTITY_HOLDER_SCOPE={"processNames":list(ROOT_IDENTITY_COMMS),"databasePaths":list(ROOT_IDENTITY_DATABASES)}


def root_public_sha(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()


def root_source_validate_ad_identity(value):
    fields={"schemaVersion","domain","realm","workgroup","netbiosName","machineSid","domainSid","servicePrincipals","trustVerified"}
    if not isinstance(value,dict) or set(value)-{"idmapPolicy","machineAccountSid","dnsAliases","machineConfigurationSha256"}!=fields or type(value["schemaVersion"]) is not int or value["schemaVersion"]!=1 or value["trustVerified"] is not True:
        raise ValueError("AD capsule metadata is not a verified joined identity")
    domain=value["domain"]
    if not isinstance(domain,str) or len(domain)>253 or domain!=domain.lower() or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?",domain) or any(not label or label.startswith("-") or label.endswith("-") for label in domain.split(".")):
        raise ValueError("AD capsule DNS domain is invalid")
    if value["realm"]!=domain.upper() or any(not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",str(value[key])) for key in ("workgroup","netbiosName")):
        raise ValueError("AD capsule realm/machine identity is invalid")
    for key in ("machineSid","domainSid"):
        if not re.fullmatch(r"S-1-5-21(?:-[0-9]{1,10}){3}",str(value[key])):raise ValueError("AD capsule SID is invalid")
    principals=value["servicePrincipals"]
    if not isinstance(principals,list) or not principals or len(principals)>128 or len(set(principals))!=len(principals):raise ValueError("AD capsule SPN list is invalid")
    for principal in principals:
        if not isinstance(principal,str) or not re.fullmatch(r"(?:host|cifs)/[a-z0-9.-]+",principal) or not principal.split("/",1)[1].endswith("."+domain):
            raise ValueError("AD capsule SPN escapes its joined DNS domain")
    if "idmapPolicy" in value:
        mapping=value["idmapPolicy"]
        if not isinstance(mapping,dict) or set(mapping)!={"default","domain"}:raise ValueError("AD capsule idmap policy is incomplete")
        ranges=[]
        for label,entry in mapping.items():
            if not isinstance(entry,dict) or entry.get("backend") not in ("tdb","rid") or set(entry)!=({"backend","range","baseRid"} if entry["backend"]=="rid" else {"backend","range"}):
                raise ValueError("AD capsule idmap backend is unsupported")
            numbers=entry["range"]
            if (not isinstance(numbers,list) or len(numbers)!=2 or any(type(number) is not int for number in numbers)
                    or not 1000<=numbers[0]<=numbers[1]<=2147483647 or numbers[0]<=65534<=numbers[1]):
                raise ValueError("AD capsule idmap range is unsafe")
            if entry["backend"]=="rid" and (type(entry["baseRid"]) is not int or not 0<=entry["baseRid"]<=2147483647):
                raise ValueError("AD capsule RID base is invalid")
            ranges.append(numbers)
        if max(row[0] for row in ranges)<=min(row[1] for row in ranges):raise ValueError("AD capsule idmap ranges overlap")
    extra={"machineAccountSid","dnsAliases","machineConfigurationSha256"}
    if extra&set(value):
        if not extra<=set(value) or "idmapPolicy" not in value:raise ValueError("AD full identity capsule metadata is incomplete")
        account=value["machineAccountSid"]
        if not isinstance(account,str) or not re.fullmatch(re.escape(value["domainSid"])+r"-[0-9]{1,10}",account):raise ValueError("AD computer account SID differs from its domain")
        if not re.fullmatch("[0-9a-f]{64}",str(value["machineConfigurationSha256"])):raise ValueError("AD private configuration digest is invalid")
        aliases=value["dnsAliases"]
        if not isinstance(aliases,list) or not aliases or len(aliases)>64:raise ValueError("AD capsule DNS aliases are unavailable")
        wanted=set()
        for alias in aliases:
            if not isinstance(alias,dict) or set(alias)!={"hostname","addresses"} or not alias["hostname"].endswith("."+domain) or not re.fullmatch("[a-z0-9.-]+",alias["hostname"]):raise ValueError("AD capsule DNS alias is foreign")
            if not isinstance(alias["addresses"],list) or not alias["addresses"] or len(alias["addresses"])>16:raise ValueError("AD capsule DNS endpoint set is invalid")
            import ipaddress
            for address in alias["addresses"]:ipaddress.IPv4Address(address)
            wanted.update(service+"/"+alias["hostname"] for service in ("host","cifs"))
        if not wanted<=set(principals):raise ValueError("AD capsule aliases lack exact CIFS/HOST SPNs")
    return value

class RootSourceIdentityCheckpoint:
    def __init__(self,runtime,configuration=None,run=None,process_root=None):
        self.runtime=runtime
        self.configuration=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        self.run=run or subprocess.run;self.process_root=Path(process_root or "/proc")
        self.deadline=time.monotonic()+180

    def command(self,args):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("ROOT source stop exceeded its total deadline")
        value=self.run(args,capture_output=True,text=True,timeout=min(15,remaining))
        if value.returncode or len(value.stdout)>1024*1024:raise ValueError("ROOT owned source observation/stop failed")
        return value.stdout.strip()

    def public_file(self,path,optional=False,exact_mode=None):
        path=Path(path);parent=path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022:
            raise ValueError("ROOT public identity parent is not protected")
        directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        fields=("st_dev","st_ino","st_mode","st_uid","st_gid","st_size","st_mtime_ns","st_ctime_ns")
        parent_fields=("st_dev","st_ino","st_mode","st_uid","st_gid")
        try:
            held_parent=os.fstat(directory)
            if any(getattr(parent,key)!=getattr(held_parent,key) for key in parent_fields):
                raise ValueError("ROOT public identity parent changed while opening")
            try:before=os.stat(path.name,dir_fd=directory,follow_symlinks=False)
            except FileNotFoundError:
                if optional:
                    named_parent=path.parent.lstat()
                    if any(getattr(named_parent,key)!=getattr(held_parent,key) for key in parent_fields):
                        raise ValueError("ROOT absent public identity parent changed")
                    return None,None
                raise
            if (not stat.S_ISREG(before.st_mode) or before.st_uid!=os.geteuid() or before.st_mode&0o022 or before.st_size>8*1024*1024
                    or (exact_mode is not None and stat.S_IMODE(before.st_mode)!=exact_mode)):
                raise ValueError("ROOT public identity file is not protected")
            fd=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=directory)
            try:
                opened=os.fstat(fd);data=os.read(fd,8*1024*1024+1);after=os.fstat(fd)
                named=os.stat(path.name,dir_fd=directory,follow_symlinks=False);named_parent=path.parent.lstat()
                if (len(data)>8*1024*1024 or any(getattr(before,key)!=getattr(opened,key) or getattr(after,key)!=getattr(opened,key)
                        or getattr(named,key)!=getattr(opened,key) for key in fields)
                        or any(getattr(named_parent,key)!=getattr(held_parent,key) for key in parent_fields)):
                    raise ValueError("ROOT public identity file or parent changed during read")
                signature={key:getattr(opened,key) for key in fields}
                signature.update({"parentDevice":held_parent.st_dev,"parentInode":held_parent.st_ino,"sha256":hashlib.sha256(data).hexdigest()})
                return data,signature
            finally:os.close(fd)
        finally:os.close(directory)

    def posix(self,desired,instance):
        rows=desired["posix-directory-policies.json"];records={}
        if rows is None:return records
        if not isinstance(rows,dict):raise ValueError("ROOT source POSIX policies are invalid")
        receipt_root=Path(os.environ.get("ABLESTACK_STORAGE_POSIX_RECEIPTS","/var/lib/ablestack-storage/posix-policy-receipts"))
        for policy,row in rows.items():
            request=row["request"]
            if request.get("instanceUuid")!=instance or request.get("uuid")!=policy:raise ValueError("ROOT source POSIX scope is foreign")
            observed=self.runtime.command(("posix","directory","inspect"),request)
            raw,_=self.public_file(receipt_root/(policy+".json"))
            receipt=credential_json(raw)
            expected={"instanceUuid":instance,"policyUuid":policy,"volumeUuid":request["volumeUuid"],"revision":request["revision"],
                      "volumeMountPath":request["volumeMountPath"],"relativePath":request["relativePath"]}
            identity=observed.get("directoryIdentity");canonical=receipt.get("canonicalDirectoryIdentity",receipt.get("directoryIdentity"))
            if (type(receipt.get("schemaVersion")) is not int or receipt["schemaVersion"]!=1 or receipt.get("phase")!="COMPLETE"
                    or receipt.get("scope")!=expected or receipt.get("requestSha256")!=root_public_sha(request)
                    or receipt.get("directoryIdentity")!=identity or row.get("effective",{}).get("directoryIdentity")!=canonical):
                raise ValueError("ROOT source POSIX UID/GID/inode policy is not freshly attested")
            records[policy]={"canonicalRow":row,"rowSha256":root_public_sha(row),"postReceipt":receipt}
        return records

    def freeze(self,scope,actual):
        raw,binding=self.public_file(self.configuration/"smb-domain.json",True)
        state=credential_json(raw) if raw is not None else {};metadata=None;machine_binding=None
        if str(state.get("joinState") or state.get("state") or "").upper()=="JOINED":
            inspect=self.runtime.command(("identity","domain","inspect"),{key:scope[key] for key in ("instanceUuid","operationUuid","revision")})
            required=("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","trustVerified","idmapPolicy","dnsAliases")
            if (inspect.get("scope")!={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
                    or inspect.get("identityVerified") is not True or inspect.get("trustVerified") is not True
                    or inspect.get("adSpnsVerified") is not True or inspect.get("dnsAliasesVerified") is not True
                    or any(key not in inspect for key in required)):
                raise ValueError("ROOT PRESTOP AD public identity is incomplete or unverified")
            _,machine_binding=self.public_file(self.configuration/"ad-machine.conf",exact_mode=0o600)
            if state.get("machineConfigurationSha256")!=machine_binding["sha256"]:
                raise ValueError("ROOT PRESTOP AD machine configuration differs from its protected joined receipt")
            metadata={"schemaVersion":1,**{key:inspect[key] for key in required},"machineConfigurationSha256":machine_binding["sha256"]}
            root_source_validate_ad_identity(metadata);no_rendered_secrets(metadata)
        result={"schemaVersion":1,"publicAdIdentity":metadata,"publicAdPreStopSha256":root_public_sha(metadata) if metadata is not None else None,
                "domainFileBinding":binding,"machineFileBinding":machine_binding,
                "sourcePosixPolicies":self.posix(actual["configurationDesiredState"],scope["instanceUuid"]),
                "rootSourceConfiguration":root_configuration_from_observation(scope,actual)}
        no_rendered_secrets(result);return result

    def unchanged(self,scope,actual,frozen):
        if (not isinstance(frozen,dict) or set(frozen)!={"schemaVersion","publicAdIdentity","publicAdPreStopSha256","domainFileBinding","machineFileBinding","sourcePosixPolicies","rootSourceConfiguration"}
                or type(frozen["schemaVersion"]) is not int or frozen["schemaVersion"]!=1):
            raise ValueError("ROOT protected PRESTOP public identity shape changed")
        _,domain=self.public_file(self.configuration/"smb-domain.json",True)
        if domain!=frozen["domainFileBinding"]:raise ValueError("ROOT PRESTOP public AD state changed")
        if frozen["machineFileBinding"] is not None:
            _,machine=self.public_file(self.configuration/"ad-machine.conf",exact_mode=0o600)
            if machine!=frozen["machineFileBinding"]:raise ValueError("ROOT PRESTOP public machine configuration changed")
        if (frozen["publicAdPreStopSha256"]!=(root_public_sha(frozen["publicAdIdentity"]) if frozen["publicAdIdentity"] is not None else None)
                or frozen["rootSourceConfiguration"]!=root_configuration_from_observation(scope,actual)
                or frozen["sourcePosixPolicies"]!=self.posix(actual["configurationDesiredState"],scope["instanceUuid"])):
            raise ValueError("ROOT PRESTOP public identity/UID/policy source changed")
        if frozen["publicAdIdentity"] is not None:root_source_validate_ad_identity(frozen["publicAdIdentity"])
        no_rendered_secrets(frozen)

    def owner(self,unit,require_active=True):
        nfs=re.fullmatch(r"ablestack-storage-ganesha@([A-Za-z0-9_.-]+)\.service",unit)
        smb=re.fullmatch(r"ablestack-storage-smb@([0-9a-f]{24})\.service",unit)
        ad=unit=="ablestack-storage-winbind.service"
        if not nfs and not smb and not ad:raise ValueError("ROOT stop refuses a foreign or legacy file/identity acceptor")
        output=self.command(["systemctl","show",unit,"--property=MainPID,ActiveState","--no-pager"])
        values=dict(line.split("=",1) for line in output.splitlines() if "=" in line)
        if values.get("ActiveState")!="active":
            if require_active:raise ValueError("ROOT source owner is no longer active")
            return None
        pid=int(values.get("MainPID") or "0")
        if pid<=0:raise ValueError("ROOT source owner lacks its exact main PID")
        process=self.process_root/str(pid);args=[os.fsdecode(item) for item in (process/"cmdline").read_bytes().split(b"\0") if item]
        exe=os.readlink(process/"exe");endpoints=[]
        if nfs:
            config="/etc/ganesha/ablestack-storage/"+nfs[1]+".conf"
            if exe not in ("/usr/bin/ganesha.nfsd","/opt/ablestack-ganesha/5.5.3/bin/ganesha.nfsd") or args.count("-f")!=1 or args[args.index("-f")+1]!=config:
                raise ValueError("ROOT NFS owner executable/configuration differs")
            raw,_=self.public_file(Path(config).resolve(strict=True));content=raw.decode()
            addresses=re.findall(r"(?mi)^\s*Bind_Addr\s*=\s*([^;]+);",content);ports=re.findall(r"(?mi)^\s*NFS_Port\s*=\s*([0-9]+);",content)
            if len(addresses)!=1 or len(ports)!=1:raise ValueError("ROOT NFS owner listener is ambiguous")
            endpoints=[{"listenIp":str(ipaddress.ip_address(addresses[0].strip().strip('"'))),"port":int(ports[0])}]
        elif smb:
            config="/etc/samba/smb.conf"
            if exe!="/usr/sbin/smbd" or not any(arg in ("--configfile="+config,config) for arg in args):
                raise ValueError("ROOT SMB owner executable/configuration differs")
            raw,_=self.public_file(self.configuration/"smb-endpoint-listeners"/(smb[1]+".json"))
            endpoint=credential_json(raw);endpoints=[{"listenIp":str(ipaddress.ip_address(endpoint["listenIp"])),"port":int(endpoint["port"])}]
        else:
            config="/etc/ablestack-storage/ad-machine.conf"
            if exe!="/usr/sbin/winbindd" or args.count("--configfile="+config)!=1:
                raise ValueError("ROOT winbind owner executable/configuration differs")
        if "/"+unit not in (process/"cgroup").read_text():raise ValueError("ROOT source PID differs from its owned service")
        if any(not 1<=row["port"]<=65535 for row in endpoints):raise ValueError("ROOT owned listener port is invalid")
        return {"unit":unit,"pid":pid,"startTicks":(process/"stat").read_text().rpartition(")")[2].split()[19],"configurationPath":config,"listenerEndpoints":endpoints}

    def owners(self):
        output=self.command(["systemctl","list-units","--state=active","--type=service","--no-legend","--plain",
                "ablestack-storage-ganesha@*.service","ablestack-storage-smb@*.service","ablestack-storage-winbind.service",
                "smbd.service","nmbd.service","winbind.service","samba.service","nfs-server.service","nfs-kernel-server.service","nfs-ganesha.service"])
        names=[line.split()[0] for line in output.splitlines() if line.split()]
        if len(names)!=len(set(names)):raise ValueError("ROOT source owner set is ambiguous")
        return [self.owner(unit) for unit in sorted(names)]

    def listeners_clear(self,owners):
        endpoints=[row for owner in owners for row in owner["listenerEndpoints"]]
        if not endpoints:return
        for args in (["ss","-H","-ltnp"],["ss","-H","-lunp"]):
            for line in self.command(args).splitlines():
                fields=line.split()
                if len(fields)<5:raise ValueError("ROOT source listener readback is malformed")
                address,sep,port=fields[3].rpartition(":")
                if not sep or not port.isdigit():raise ValueError("ROOT source listener readback is malformed")
                address=address.strip("[]");address="0.0.0.0" if address=="*" else str(ipaddress.ip_address(address))
                if any(int(port)==row["port"] and (address in ("0.0.0.0","::") or row["listenIp"] in (address,"0.0.0.0","::")) for row in endpoints):
                    raise ValueError("ROOT owned listener remains exposed after stop")

    def holders(self):
        holders=[];until=min(self.deadline,time.monotonic()+5)
        for process in self.process_root.iterdir():
            if time.monotonic()>until:raise ValueError("ROOT scoped identity holder observation timed out")
            if not process.name.isdigit():continue
            try:
                if (process/"comm").read_text().strip() not in ROOT_IDENTITY_COMMS:continue
                for fd in (process/"fd").iterdir():
                    try:
                        name=os.readlink(fd);name=name[:-10] if name.endswith(" (deleted)") else name
                        if name in ROOT_IDENTITY_DATABASES:
                            value=fd.stat();holders.append({"pid":int(process.name),"fd":int(fd.name),"path":name,"device":value.st_dev,"inode":value.st_ino})
                    except FileNotFoundError:continue
            except FileNotFoundError:continue
            except PermissionError as invalid:raise ValueError("ROOT scoped identity holders cannot be observed") from invalid
        return holders

    def stopped(self,scope,actual,frozen,source_rendered,previous=None,plan=None):
        self.unchanged(scope,actual,frozen)
        owners=self.owners()
        if previous is not None:
            if (previous.get("scope")!=scope or previous.get("sourceGeneration")!=actual["generation"]
                    or previous.get("sourceConfigurationSha256")!=actual["configurationSha256"]
                    or previous.get("sourceRenderedManifestSha256")!=source_rendered["manifestSha256"]
                    or previous.get("publicAdPreStopSha256")!=frozen["publicAdPreStopSha256"]
                    or previous.get("holderObservationScope")!=ROOT_IDENTITY_HOLDER_SCOPE
                    or type(previous.get("knownIdentityDatabaseHolders")) is not int or previous["knownIdentityDatabaseHolders"]!=0
                    or previous.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()):
                raise ValueError("ROOT source stopped receipt is foreign or from another boot")
            if owners:raise ValueError("ROOT source service restarted after its stopped receipt")
            self.listeners_clear(previous["owners"])
            if self.holders():raise ValueError("ROOT known identity DB holder appeared after stopped receipt")
            return previous
        if plan is not None:
            current={row["unit"]:row for row in owners}
            if any(row["unit"] not in {item["unit"] for item in plan} for row in owners):
                raise ValueError("ROOT source acquired a foreign owner during stopped recovery")
            if any(current.get(row["unit"],row)!=row for row in plan):
                raise ValueError("ROOT source planned owner changed during stopped recovery")
            owners=plan
        joined=frozen["publicAdIdentity"] is not None
        if joined!=any(row["unit"]=="ablestack-storage-winbind.service" for row in owners):
            raise ValueError("ROOT PRESTOP joined identity differs from its owned active winbind")
        for owner in owners:
            fresh=self.owner(owner["unit"],False)
            if fresh is not None and fresh!=owner:raise ValueError("ROOT source owner PID changed before stop")
        # Stop every owned file acceptor before the private identity daemon.
        for owner in sorted(owners,key=lambda row:row["unit"]=="ablestack-storage-winbind.service"):
            fresh=self.owner(owner["unit"],False)
            if fresh is not None and fresh!=owner:raise ValueError("ROOT source owner PID changed during stop")
            if fresh is not None:self.command(["systemctl","stop",owner["unit"]])
        if self.owners():raise ValueError("ROOT owned source acceptors remain active")
        self.listeners_clear(owners)
        if self.holders():raise ValueError("ROOT known Samba identity DB holders remain after stop")
        self.unchanged(scope,actual,frozen)
        return {"schemaVersion":1,"kind":"ROOT_SOURCE_STOPPED","scope":scope,"sourceGeneration":actual["generation"],
                "sourceConfigurationSha256":actual["configurationSha256"],"sourceRenderedManifestSha256":source_rendered["manifestSha256"],
                "publicAdPreStopSha256":frozen["publicAdPreStopSha256"],"owners":owners,
                "holderObservationScope":ROOT_IDENTITY_HOLDER_SCOPE,"knownIdentityDatabaseHolders":0,
                "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"stoppedEpoch":time.time()}
