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

"""Guarded AD join and same-instance identity restore; public receipts, RAM credentials."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import time
import uuid
from ad_identity import (AdIdentityRpc,AdIdentityProbe,ad_configuration,ad_protected_json,bounded_ad_run,credential_command,dns_aliases,domain_name,idmap_policy,machine_account_sid,service_principal)
from ad_winbind import AdWinbind,AD_WINBIND_CONFIGURATION
from posix_root_initialization import root_receipt_write
from service_identity_cipher import ServiceIdentityCipher,service_cipher_digest,service_cipher_scope
from samba_public_sid import samba_public_sid


class AdDomainLifecycle:
    def __init__(self,cli,configuration=None,run=None,daemon=None,quiescence=None,system_root=None,source_provider=None,sid_reader=None):
        self.system_root=Path(system_root or "/")
        self.cli=str(cli);self.configuration=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        self.run=run or bounded_ad_run;self.deadline=time.monotonic()+180
        self.daemon=daemon or AdWinbind(cli,self.configuration,run=self.run)
        self.quiescence=quiescence or self.require_quiescence
        self.state=self.configuration/"smb-domain.json";self.journal=self.configuration/"ad-lifecycle"/"current.json"
        self.machine=self.configuration/"ad-machine.conf"
        self.source_provider=source_provider
        self.sid_reader=sid_reader or (lambda name:samba_public_sid(name,self.system_root/'var/lib/samba/private/secrets.tdb'))

    def command(self,arguments):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("AD lifecycle total deadline expired")
        result=self.configured_run(arguments,capture_output=True,text=True,timeout=min(15,remaining))
        if result.returncode:raise ValueError("AD lifecycle command failed; private diagnostic omitted")
        return result.stdout.strip()

    def configured_run(self,arguments,**kwargs):
        if arguments[0] in ("net","testparm"):arguments=[arguments[0],"--configfile="+str(self.machine),*arguments[1:]]
        return self.run(arguments,**kwargs)

    def require_quiescence(self):
        for process in Path("/proc").iterdir():
            if not process.name.isdigit():continue
            try:
                name=(process/"comm").read_text().strip()
                if name in ("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd"):
                    raise ValueError("AD identity writes require all Samba identity daemons to be quiesced")
            except FileNotFoundError:continue

    def write_public(self,path,content):
        path.parent.mkdir(parents=True,mode=0o700,exist_ok=True);directory=path.parent.lstat()
        if not stat.S_ISDIR(directory.st_mode) or directory.st_uid!=os.geteuid() or directory.st_mode&0o022:raise ValueError("AD public configuration directory is not protected")
        if path.exists() or path.is_symlink():
            info=path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:raise ValueError("AD public configuration target is foreign")
        descriptor,temporary=tempfile.mkstemp(prefix=".ad-public-",dir=path.parent)
        try:
            os.fchmod(descriptor,0o600)
            with os.fdopen(descriptor,"w") as handle:handle.write(content);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,path)
            directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)

    def snapshot(self,request,phase,public):
        value={"schemaVersion":1,"scope":self.daemon.scope(request),"phase":phase,"publicConfiguration":public,"updatedEpoch":time.time()}
        root_receipt_write(self.journal,value);return value

    def validate(self,request):
        scope=self.daemon.scope(request);self.daemon.marker(request)
        AdIdentityRpc(self.run,self.configuration,cli=self.cli).scope(request)
        domain=domain_name(request["domainName"]);config=request.get("config") or {}
        workgroup=request.get("workgroup") or config.get("workgroup");netbios=request.get("netbiosName") or config.get("netbiosName")
        source=self.system_root/"etc/samba/smb.conf"
        if source.is_symlink():source=source.resolve(strict=True)
        info=source.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_size>8*1024*1024:raise ValueError("AD existing Samba shares are not protected")
        descriptor=os.open(source,os.O_RDONLY|os.O_NOFOLLOW)
        try:
            opened=os.fstat(descriptor)
            if (opened.st_dev,opened.st_ino,opened.st_size)!=(info.st_dev,info.st_ino,info.st_size):raise ValueError("AD source Samba configuration changed")
            existing=os.read(descriptor,8*1024*1024+1).decode()
        finally:os.close(descriptor)
        pure=ad_configuration(domain,workgroup,netbios,existing,request.get("dnsServers") or config.get("dnsServers"),request.get("idmapPolicy") or config.get("idmapPolicy"))
        aliases=dns_aliases(request.get("dnsAliases"),domain)
        required=[service_principal(item,domain) for item in request.get("servicePrincipals") or []]
        needed={service+"/"+alias["hostname"] for alias in aliases for service in ("cifs","host")}
        if set(required)!=needed or len(required)!=len(needed):raise ValueError("AD required SPNs do not exactly cover owned DNS aliases")
        username=request.get("username");password=request.get("password")
        if not isinstance(username,str) or not re.fullmatch(r"[A-Za-z0-9_.@\\-]{1,256}",username):raise ValueError("AD join credential username is invalid")
        if not isinstance(password,str) or not password or len(password)>4096 or any(char in password for char in ("\n","\r","\0")):raise ValueError("AD join password transport is invalid")
        mode=request.get("identityMode")
        if mode not in ("JOIN_EXISTING","NEW_INSTANCE"):raise ValueError("AD join requires an explicit identity mode")
        if mode=="NEW_INSTANCE":
            expected_name="AST"+scope["instanceUuid"].replace("-","")[:12].upper()
            if pure["netbiosName"]!=expected_name:raise ValueError("Fresh clone AD computer name must be derived from its new instance UUID")
            source_identity=request.get("sourceIdentity")
            if isinstance(source_identity,dict) and pure["netbiosName"]==source_identity.get("netbiosName"):
                raise ValueError("Fresh clone cannot reuse the source AD computer account name")
            if isinstance(source_identity,dict):
                if any(value in set(source_identity.get("servicePrincipals") or []) for value in required) or any(row["hostname"] in {item["hostname"] for item in source_identity.get("dnsAliases") or []} for row in aliases):
                    raise ValueError("Fresh clone cannot reuse source AD aliases or service principals")

        ou=request.get("organizationalUnit")
        if ou is not None and (not isinstance(ou,str) or not re.fullmatch(r"[A-Za-z0-9_ .,-/]{1,512}",ou) or ou!=ou.strip()):raise ValueError("AD organizational unit is invalid")
        public={key:pure[key] for key in ("domain","realm","workgroup","netbiosName","dnsServers","idmapPolicy")}
        public.update(dnsAliases=aliases,servicePrincipals=sorted(required),identityMode=mode)
        return pure,public

    def new_identity_clear(self,request,public):
        # Query only fixed public attributes. Credentials remain in the same
        # sealed auth memfd/MEMORY kcache as join; no LDAP payload is persisted.
        wanted=[("sAMAccountName",public["netbiosName"]+"$")]
        wanted.extend(("dNSHostName",row["hostname"]) for row in public["dnsAliases"])
        wanted.extend(("servicePrincipalName",value) for value in public["servicePrincipals"])
        if any(not re.fullmatch(r"[A-Za-z0-9_.$/-]+",value) for _,value in wanted):
            raise ValueError("Fresh AD public collision query has an invalid LDAP value")
        expression="(|"+"".join("("+field+"="+value+")" for field,value in wanted)+")"
        captured=[None]
        def observe(arguments,**kwargs):
            result=self.run(arguments,**kwargs)
            if result.returncode==0:captured[0]=result.stdout
            return result
        try:
            credential_command(["net","ads","search",expression,"sAMAccountName","dNSHostName","servicePrincipalName",
                                "--realm="+public["realm"],"--workgroup="+public["workgroup"],"--option=security=ADS"],
                               request["username"],request["password"],public["domain"],observe,self.deadline)
            # Samba 4.17 net_ads_search prints this successful empty-result
            # header. A malformed/translated/error response is never absence.
            if not isinstance(captured[0],str) or not re.fullmatch(r"Got 0 replies\s*",captured[0]):
                raise ValueError("Fresh AD computer/alias/SPN already exists or absence was not proven")
        finally:captured[0]=None
        for alias in public["dnsAliases"]:
            for server in public["dnsServers"]:
                remaining=self.deadline-time.monotonic()
                if remaining<=0:raise TimeoutError("Fresh AD identity collision observation deadline expired")
                result=self.run(["dig","+short","+time=2","+tries=1","A",alias["hostname"],"@"+server],
                                capture_output=True,text=True,timeout=min(5,remaining))
                if result.returncode or result.stdout.strip():
                    raise ValueError("Fresh AD DNS alias exists or absence was not proven")
        return {"success":True,"computerAliasSpnAbsent":True,"dnsAliasesAbsent":True}

    def source_authority(self,request,fresh=True):
        marker=self.daemon.marker(request)
        scope=service_cipher_scope(marker)
        if any(request.get(key)!=value for key,value in scope.items()):
            raise ValueError("Ordinary AD mutation requires its exact held SERVICE4 scope")
        if self.source_provider is not None:
            source=self.source_provider(scope,fresh)
        else:
            cipher=ServiceIdentityCipher();native=cipher.source(scope);record=cipher.read(cipher.path(scope))
            if (record.get("serviceScope")!=scope or record.get("sourceRecordSha256")!=service_cipher_digest(native)
                    or record.get("stoppedReceiptSha256")!=service_cipher_digest(native["sourceStoppedReceipt"])
                    or record.get("identityCheckpoint",{}).get("sourceConfigurationSha256")!=native["sourceConfigurationSha256"]):
                raise ValueError("AD mutation lacks its native BEFOREJOIN encrypted source")
            observed=json.loads(self.command([self.cli,"operation","generation","status"]))
            if (observed.get("generation")!=native["sourceGeneration"] or observed.get("configurationSha256")!=native["sourceConfigurationSha256"]
                    or observed.get("pendingOperationUuid")!=scope["operationUuid"] or observed.get("generationStatus")!="PENDING"):
                raise ValueError("AD mutation source generation/seven differs")
            if fresh:
                # Fixed request travels through the same bounded sealed input
                # mechanism as other native lifecycle observations.
                descriptor=os.memfd_create("ad-source-authority",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
                try:
                    import fcntl
                    os.fchmod(descriptor,0o600);os.write(descriptor,json.dumps(scope).encode());os.lseek(descriptor,0,os.SEEK_SET)
                    fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
                    result=self.run([self.cli,"operation","generation","render-service-identity-export-source","/proc/self/fd/"+str(descriptor)],
                                    capture_output=True,text=True,timeout=min(15,self.deadline-time.monotonic()),pass_fds=(descriptor,9) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else (descriptor,))
                    if result.returncode:raise ValueError("AD mutation fresh AFTERSTOP source was rejected")
                    source=json.loads(result.stdout)
                finally:os.close(descriptor)
            else:
                frozen=native["sourcePublicIdentity"]
                source={"scope":scope,"serviceSourceStoppedVerified":True,"bootId":native["bootId"],"publicLocalMachineSid":frozen["publicLocalMachineSid"],
                        "adIdentity":frozen["publicAdIdentity"],"sourceConfigurationSha256":native["sourceConfigurationSha256"]}
        if (not isinstance(source,dict) or source.get("scope")!=scope or source.get("serviceSourceStoppedVerified") is not True
                or source.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
                or not re.fullmatch(r"S-1-5-21(?:-[0-9]{1,10}){3}",str(source.get("publicLocalMachineSid")))):
            raise ValueError("AD mutation source boot/SAM/stopped proof is invalid")
        return source

    def public_backup(self,path):
        if not path.exists() and not path.is_symlink():return {"absent":True}
        info=path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_size>65536:
            raise ValueError("AD original public configuration is not protected")
        content=path.read_text()
        if re.search(r"(?im)(?:password|secret|credential)\s*=|BEGIN .*PRIVATE KEY",content):
            raise ValueError("AD public configuration backup contains unsupported private material")
        return {"content":content,"mode":stat.S_IMODE(info.st_mode),"sha256":hashlib.sha256(content.encode()).hexdigest()}

    def restore_public(self,path,backup,owned_sha):
        if (not path.exists() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=owned_sha):
            raise ValueError("AD owned public configuration changed before cleanup")
        if backup.get("absent") is True:path.unlink();return
        if set(backup)!={"content","mode","sha256"} or hashlib.sha256(backup["content"].encode()).hexdigest()!=backup["sha256"]:
            raise ValueError("AD original public configuration backup changed")
        self.write_public(path,backup["content"]);path.chmod(backup["mode"])

    def join(self,request):
        pure,public=self.validate(request);existing=ad_protected_json(self.state,True)
        journal=ad_protected_json(self.journal,True)
        own_complete=bool(journal and journal.get("scope")==self.daemon.scope(request) and journal.get("phase")=="COMPLETE")
        source=self.source_authority(request,fresh=not own_complete)
        if existing and existing.get("state")=="JOINED":
            actual=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if actual.get("identityVerified") is not True or any(actual.get(key)!=public[key] for key in ("domain","realm","workgroup","netbiosName","dnsAliases","servicePrincipals")):
                raise ValueError("Existing AD identity cannot be silently replaced or rejoined")
            return {"success":True,"scope":self.daemon.scope(request),"identityPreserved":True,"rejoined":False,"identity":actual,"canonicalDesiredStateChanged":False}
        if public["identityMode"]=="NEW_INSTANCE" and ((self.system_root/"etc/krb5.keytab").exists() or existing):raise ValueError("Fresh clone cannot carry a source AD identity")
        if public["identityMode"]=="NEW_INSTANCE":raise ValueError("NEW_INSTANCE requires authenticated original authority and fresh target SAM bootstrap")
        for path in (self.machine,self.system_root/"etc/krb5.keytab",self.system_root/"var/lib/samba/winbindd_idmap.tdb"):
            if path.exists() or path.is_symlink():raise ValueError("AD first join refuses preexisting foreign identity artifacts")
        self.quiescence()
        backups={"krb5.conf":self.public_backup(self.system_root/"etc/krb5.conf"),"resolv.conf":self.public_backup(self.system_root/"etc/resolv.conf")}
        self.new_identity_clear(request,public)
        self.snapshot(request,"PREPARING",public)
        try:
            # This private config carries existing share definitions; canonical SMB
            # and all seven desired files remain under the rendered coordinator.
            self.write_public(self.machine,pure["smbConfiguration"])
            self.write_public(self.system_root/"etc/krb5.conf",pure["kerberosConfiguration"])
            self.write_public(self.system_root/"etc/resolv.conf",pure["resolverConfiguration"])
            self.command(["testparm","-s"])
            self.snapshot(request,"JOINING",public)
            args=["net","ads","join"]
            if request.get("organizationalUnit"):args.append("createcomputer="+request["organizationalUnit"])
            credential_command(args,request["username"],request["password"],public["domain"],self.configured_run,self.deadline)
            self.snapshot(request,"REGISTERING",public)
            for principal in public["servicePrincipals"]:
                credential_command(["net","ads","setspn","add",public["netbiosName"],principal],request["username"],request["password"],public["domain"],self.configured_run,self.deadline)
            for alias in public["dnsAliases"]:
                credential_command(["net","ads","dns","register",alias["hostname"],*alias["addresses"]],request["username"],request["password"],public["domain"],self.configured_run,self.deadline)
            self.command(["net","ads","keytab","create","--machine-pass"])
            self.daemon.start(request)
            self.snapshot(request,"VERIFYING",public)
            probe=AdIdentityProbe(self.configured_run,self.deadline).verify(public["domain"],public["servicePrincipals"],machine_name=public["netbiosName"])
            account=machine_account_sid(self.command(["wbinfo","--name-to-sid",public["workgroup"]+chr(92)+public["netbiosName"]+"$"]),probe["domainSid"])
            mapping=idmap_policy(self.configured_run,public["workgroup"],self.deadline)
            if mapping!=public["idmapPolicy"]:raise ValueError("AD joined idmap readback differs from its exact private configuration")
            if probe["machineSid"]!=source["publicLocalMachineSid"]:raise ValueError("JOIN_EXISTING changed its original public local SAM SID")
            state={"state":"JOINED","joinState":"JOINED","instanceUuid":self.daemon.scope(request)["instanceUuid"],"domainName":public["domain"],"realm":public["realm"],"workgroup":public["workgroup"],"netbiosName":public["netbiosName"],
                   "dnsServers":public["dnsServers"],"dnsAliases":public["dnsAliases"],"servicePrincipals":public["servicePrincipals"],"idmapPolicy":mapping,
                   "identityReceipt":{key:probe[key] for key in ("machineSid","domainSid")},"machineConfigurationSha256":hashlib.sha256(self.machine.read_bytes()).hexdigest()}
            state["identityReceipt"]["machineAccountSid"]=account
            state["ordinaryJoinSource"]={"scope":source["scope"],"bootId":source["bootId"],"localMachineSid":source["publicLocalMachineSid"]}
            state["publicConfigurationBeforeJoin"]=backups
            state["ownedPublicConfigurationSha256"]={"krb5.conf":hashlib.sha256(pure["kerberosConfiguration"].encode()).hexdigest(),"resolv.conf":hashlib.sha256(pure["resolverConfiguration"].encode()).hexdigest()}
            state["ownedAdArtifacts"]=["ad-machine.conf","krb5.keytab","winbindd_idmap.tdb"]
            self.write_public(self.state,json.dumps(state,sort_keys=True))
            actual=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if actual.get("identityVerified") is not True:raise ValueError("AD joined identity was not freshly attested")
            self.snapshot(request,"COMPLETE",public)
            return {"success":True,"scope":self.daemon.scope(request),"joined":True,"identity":actual,"canonicalDesiredStateChanged":False,"protectedCredentialTransport":True}
        except Exception:
            try:self.daemon.stop(request)
            finally:self.snapshot(request,"RECOVERY_REQUIRED",public)
            raise

    def retain(self,request,expected):
        self.daemon.marker(request);state=ad_protected_json(self.state)
        if not isinstance(expected,dict) or state.get("identityReceipt")!={key:expected.get(key) for key in ("machineSid","domainSid","machineAccountSid")}:
            raise ValueError("SAMEVM AD restore differs from its encrypted source identity")
        info=self.machine.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600:raise ValueError("SAMEVM AD machine configuration is unprotected")
        expected_sha=expected.get("machineConfigurationSha256")
        if (not isinstance(expected_sha,str) or not re.fullmatch("[0-9a-f]{64}",expected_sha)
                or state.get("machineConfigurationSha256")!=expected_sha or hashlib.sha256(self.machine.read_bytes()).hexdigest()!=expected_sha):raise ValueError("SAMEVM AD configuration bytes differ before daemon restore")
        self.daemon.start(request);actual=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
        fields=("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","idmapPolicy","dnsAliases")
        if actual.get("identityVerified") is not True or any(actual.get(key)!=expected.get(key) for key in fields):
            self.daemon.stop(request);raise ValueError("SAMEVM AD trust/SID/SPN/DNS/idmap identity did not remain exact")
        return {"success":True,"scope":self.daemon.scope(request),"identityPreserved":True,"rejoined":False,"identity":actual}

    def remote_identity_exact(self,request,actual,dns_servers):
        name=actual["netbiosName"];account=actual["machineAccountSid"];domain=actual["domain"]
        queries=[("(sAMAccountName="+name+"$)","objectSid")]
        queries.extend(("(servicePrincipalName="+principal+")","objectSid") for principal in actual["servicePrincipals"])
        for expression,attribute in queries:
            output=[None]
            def observe(arguments,**kwargs):
                result=self.run(arguments,**kwargs)
                if result.returncode==0:output[0]=result.stdout
                return result
            credential_command(["net","ads","search",expression,attribute,"--realm="+actual["realm"],
                                "--workgroup="+actual["workgroup"],"--option=security=ADS"],
                               request["username"],request["password"],domain,observe,self.deadline)
            raw=output[0]
            matches=re.findall(r"(?mi)^\s*objectSid:\s*(S-[0-9-]+)\s*$",raw or "")
            if (not isinstance(raw,str) or not re.match(r"^Got 1 replies\s",raw)
                    or matches!=[account]):raise ValueError("AD remote computer/SPN belongs to a replaced or foreign object")
        for alias in actual["dnsAliases"]:
            for server in dns_servers:
                remaining=self.deadline-time.monotonic()
                if remaining<=0:raise TimeoutError("AD before-effect DNS authority observation deadline expired")
                result=self.run(["dig","+short","+time=2","+tries=1","A",alias["hostname"],"@"+server],capture_output=True,text=True,timeout=min(5,remaining))
                if result.returncode:raise ValueError("AD before-effect DNS authority is unavailable")
                import ipaddress
                observed=sorted({str(ipaddress.IPv4Address(line.strip())) for line in result.stdout.splitlines() if line.strip()})
                if observed!=alias["addresses"]:raise ValueError("AD DNS name contains a foreign or replaced address before delete")
                ipv6=self.run(["dig","+short","+time=2","+tries=1","AAAA",alias["hostname"],"@"+server],capture_output=True,text=True,timeout=min(5,self.deadline-time.monotonic()))
                if ipv6.returncode or ipv6.stdout.strip():raise ValueError("AD DNS name has foreign IPv6 records or absence was not proven before delete")
        return True

    def leave(self,request):
        username=request.get("username");password=request.get("password")
        if (not isinstance(username,str) or not re.fullmatch(r"[A-Za-z0-9_.@\\-]{1,256}",username)
                or not isinstance(password,str) or not password or len(password)>4096 or any(char in password for char in ("\n","\r","\0"))):
            raise ValueError("AD leave credential transport must be complete and valid before effects")
        self.daemon.scope(request);AdIdentityRpc(self.run,self.configuration,cli=self.cli).scope(request)
        source=self.source_authority(request);state=ad_protected_json(self.state);actual=source.get("adIdentity")
        if (not isinstance(actual,dict) or actual.get("trustVerified") is not True
                or state.get("identityReceipt")!={key:actual.get(key) for key in ("machineSid","domainSid","machineAccountSid")}
                or source["publicLocalMachineSid"]!=actual.get("machineSid")
                or state.get("machineConfigurationSha256")!=hashlib.sha256(self.machine.read_bytes()).hexdigest()):
            raise ValueError("AD leave requires its exact PRESTOP joined source identity")
        artifact_paths={"ad-machine.conf":self.machine,"krb5.keytab":self.system_root/"etc/krb5.keytab",
                        "winbindd_idmap.tdb":self.system_root/"var/lib/samba/winbindd_idmap.tdb"}
        if state.get("ownedAdArtifacts")!=list(artifact_paths):raise ValueError("AD leave has no closed owned artifact provenance")
        for name,path in artifact_paths.items():
            if not path.exists() and not path.is_symlink():
                if name=="winbindd_idmap.tdb":continue
                raise ValueError("Required owned AD artifact is missing")
            info=path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600:
                raise ValueError("AD leave artifact is foreign or unprotected")
        backups=state.get("publicConfigurationBeforeJoin");owned=state.get("ownedPublicConfigurationSha256")
        if not isinstance(backups,dict) or set(backups)!={"krb5.conf","resolv.conf"} or not isinstance(owned,dict) or set(owned)!=set(backups):
            raise ValueError("AD leave lacks its original public configuration ownership")
        for name in backups:
            path=self.system_root/"etc"/name
            if not path.exists() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=owned[name]:
                raise ValueError("AD public configuration drifted before leave")
        if self.sid_reader(actual["netbiosName"])!=source["publicLocalMachineSid"]:raise ValueError("AD leave local SAM drifted before effects")
        self.quiescence()
        public={key:actual[key] for key in ("domain","realm","workgroup","netbiosName","dnsAliases","servicePrincipals")}
        public["dnsServers"]=state["dnsServers"]
        self.remote_identity_exact(request,actual,state["dnsServers"])
        self.snapshot(request,"LEAVING",public)
        try:
            for principal in actual["servicePrincipals"]:
                credential_command(["net","ads","setspn","delete",actual["netbiosName"],principal],
                                   username,password,actual["domain"],self.configured_run,self.deadline)
            for alias in actual["dnsAliases"]:
                credential_command(["net","ads","dns","unregister",alias["hostname"]],username,password,actual["domain"],self.configured_run,self.deadline)
            credential_command(["net","ads","leave"],username,password,actual["domain"],self.configured_run,self.deadline)
            # Exact empty LDAP computer/SPN set and every configured DNS
            # server's empty alias A result; a command success alone is not it.
            self.new_identity_clear(request,public)
            if self.sid_reader(actual["netbiosName"])!=source["publicLocalMachineSid"]:raise ValueError("AD leave changed the local SAM SID")
            cleanup=[]
            for name,path in artifact_paths.items():
                if path.exists():path.unlink()
                if path.exists() or path.is_symlink():raise ValueError("Owned AD artifact remains after cleanup")
                cleanup.append({"name":name,"absent":True})
            for name in backups:self.restore_public(self.system_root/"etc"/name,backups[name],owned[name])
            self.write_public(self.state,json.dumps({"state":"NOT_JOINED","joinState":"NOT_JOINED","instanceUuid":request["instanceUuid"],
                              "netbiosName":actual["netbiosName"],"previousIdentityReceipt":state["identityReceipt"],
                              "localMachineSid":source["publicLocalMachineSid"]},sort_keys=True))
            fresh=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if (fresh.get("joinState")!="NOT_JOINED" or fresh.get("machineSid")!=source["publicLocalMachineSid"]
                    or fresh.get("bootId")!=source["bootId"]):raise ValueError("AD leave final local identity was not freshly attested")
            self.snapshot(request,"COMPLETE_LEFT",public)
            return {"success":True,"scope":self.daemon.scope(request),"left":True,"canonicalDesiredStateChanged":False,
                    "localMachineSidPreserved":True,"adOwnedArtifactsRemoved":True,"ownedArtifactCleanup":cleanup,
                    "publicConfigurationRestored":True,"computerAliasSpnAbsent":True,"dnsAliasesAbsent":True,"identity":fresh}
        except Exception:
            self.snapshot(request,"RECOVERY_REQUIRED",public);raise
