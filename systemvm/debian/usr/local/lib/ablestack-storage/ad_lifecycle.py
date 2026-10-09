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
from ad_identity import (AdIdentityRpc,AdIdentityProbe,ad_configuration,ad_protected_json,bounded_ad_run,credential_command,dns_aliases,domain_name,idmap_policy,machine_account_sid,service_principal,machine_sids)
from ad_winbind import AdWinbind,AD_WINBIND_CONFIGURATION
from posix_root_initialization import root_receipt_write
from service_identity_cipher import ServiceIdentityCipher,service_cipher_digest,service_cipher_scope
from samba_public_sid import samba_public_sid
from semantic_ad_source import semantic_new_target,semantic_same_target
from local_sam_bootstrap import LocalSamBootstrap
from ad_authority import protected_ad_policy
from root_ad_identity_authority import root_ad_retained_authority


class AdDomainLifecycle:
    def __init__(self,cli,configuration=None,run=None,daemon=None,quiescence=None,system_root=None,source_provider=None,sid_reader=None,original_provider=None):
        self.system_root=Path(system_root or "/")
        self.cli=str(cli);self.configuration=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        self.run=run or bounded_ad_run;self.deadline=time.monotonic()+180
        self.daemon=daemon or AdWinbind(cli,self.configuration,run=self.run)
        self.quiescence=quiescence or self.require_quiescence
        self.state=self.configuration/"smb-domain.json";self.journal=self.configuration/"ad-lifecycle"/"current.json"
        self.machine=self.configuration/"ad-machine.conf"
        self.source_provider=source_provider;self.original_provider=original_provider
        self.sid_reader=sid_reader or (lambda name:samba_public_sid(name,self.system_root/'var/lib/samba/private/secrets.tdb'))

    def command(self,arguments):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("AD lifecycle total deadline expired")
        result=self.configured_run(arguments,capture_output=True,text=True,timeout=min(15,remaining))
        if result.returncode:raise ValueError("AD lifecycle command failed; private diagnostic omitted")
        return result.stdout.strip()

    def configured_run(self,arguments,**kwargs):
        if arguments[0]=="net":arguments=[arguments[0],"--configfile="+str(self.machine),*arguments[1:]]
        elif arguments[0]=="testparm":arguments=[arguments[0],str(self.machine),*arguments[1:]]
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
            expected_name="STOR"+scope["instanceUuid"].replace("-","")[:10].upper()
            if pure["netbiosName"]!=expected_name:raise ValueError("Fresh clone AD computer name must be derived from its new instance UUID")
            if "sourceIdentity" in request:raise ValueError("Caller plain source identity has no authenticated original authority")

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
                for kind in ("A","AAAA"):
                    result=self.run(["dig","+short","+time=2","+tries=1",kind,alias["hostname"],"@"+server],
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
                source=self.protected_request(("operation","generation","render-service-identity-export-source"),scope)
            else:
                frozen=native["sourcePublicIdentity"]
                source={"scope":scope,"serviceSourceStoppedVerified":True,"bootId":native["bootId"],"publicLocalMachineSid":frozen["publicLocalMachineSid"],
                        "adIdentity":frozen["publicAdIdentity"],"sourceConfigurationSha256":native["sourceConfigurationSha256"]}
        if (not isinstance(source,dict) or source.get("scope")!=scope or source.get("serviceSourceStoppedVerified") is not True
                or source.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
                or not re.fullmatch(r"S-1-5-21(?:-[0-9]{1,10}){3}",str(source.get("publicLocalMachineSid")))):
            raise ValueError("AD mutation source boot/SAM/stopped proof is invalid")
        return source

    def protected_request(self,command,request):
        import fcntl
        temporary=os.memfd_create("ad-protected-request",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
        # Signed shell helpers reserve FD3 for protected stdin and FD9 for the
        # native writer. Keep the sealed request outside both reserved slots.
        try:descriptor=fcntl.fcntl(temporary,fcntl.F_DUPFD_CLOEXEC,16)
        finally:os.close(temporary)
        try:
            os.fchmod(descriptor,0o600);os.write(descriptor,json.dumps(request).encode());os.lseek(descriptor,0,os.SEEK_SET)
            fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
            remaining=self.deadline-time.monotonic()
            if remaining<=0:raise TimeoutError("AD protected request deadline expired")
            result=self.run([self.cli,*command,"/proc/self/fd/"+str(descriptor)],capture_output=True,text=True,timeout=min(15,remaining),
                            pass_fds=(descriptor,9) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9" else (descriptor,))
            if result.returncode:raise ValueError("AD protected authority observer rejected its request")
            return json.loads(result.stdout)
        finally:os.close(descriptor)

    def original_source(self,request):
        if "sourceIdentity" in request:raise ValueError("Caller plain source identity has no authenticated original authority")
        fields=("originalSourceAuthority","originalSourceCapsule","originalSourceCredentialPrivateKey")
        if any(field not in request for field in fields):raise ValueError("NEW_INSTANCE requires authenticated original authority and fresh target SAM bootstrap")
        scoped={**self.daemon.scope(request),**{field:request[field] for field in fields}}
        result=self.original_provider(scoped) if self.original_provider is not None else self.protected_request(("identity","capsule","semantic-original"),scoped)
        if (not isinstance(result,dict) or result.get("success") is not True or result.get("scope")!=self.daemon.scope(request)
                or result.get("originalSourceAuthority")!=request["originalSourceAuthority"] or not isinstance(result.get("originalIdentity"),dict)):
            raise ValueError("AD semantic original decoder receipt is invalid")
        return {"descriptor":result["originalSourceAuthority"],"identity":result["originalIdentity"]}

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
        configured=self.run(["testparm","-s","--parameter-name=netbios name"],capture_output=True,text=True,timeout=min(5,self.deadline-time.monotonic()))
        if configured.returncode or configured.stdout.strip().upper()!=public["netbiosName"]:
            raise ValueError("AD JOIN name differs from the actual captured target configuration")
        if self.sid_reader(public["netbiosName"])!=source["publicLocalMachineSid"]:
            raise ValueError("AD JOIN machine name has no exact captured target SAM key")
        original=None
        if public["identityMode"]=="NEW_INSTANCE":
            original=self.original_source(request)
            semantic_new_target(original,public,source,self.daemon.scope(request)["instanceUuid"])
        elif any(field in request for field in ("originalSourceAuthority","originalSourceCapsule","originalSourceCredentialPrivateKey")):
            original=self.original_source(request)
            semantic_same_target(original,public,source,self.daemon.scope(request)["instanceUuid"])
        if existing and existing.get("state")=="JOINED":
            actual=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if actual.get("identityVerified") is not True or any(actual.get(key)!=public[key] for key in ("domain","realm","workgroup","netbiosName","dnsAliases","servicePrincipals")):
                raise ValueError("Existing AD identity cannot be silently replaced or rejoined")
            if public["identityMode"]=="NEW_INSTANCE" and (existing.get("semanticOriginalSourceAuthority")!=original["descriptor"]
                    or actual.get("machineSid")!=source["publicLocalMachineSid"] or actual.get("machineAccountSid")==original["identity"]["machineAccountSid"]
                    or actual.get("domainSid")!=original["identity"]["domainSid"] or actual.get("idmapPolicy")!=original["identity"]["idmapPolicy"]):
                raise ValueError("NEW_INSTANCE retry differs from its authenticated original or preserved target")
            return {"success":True,"scope":self.daemon.scope(request),"identityPreserved":True,"rejoined":False,"identity":actual,"canonicalDesiredStateChanged":False}
        for path in (self.machine,self.system_root/"etc/krb5.keytab",self.system_root/"var/lib/samba/winbindd_idmap.tdb"):
            if path.exists() or path.is_symlink():raise ValueError("AD first join refuses preexisting foreign identity artifacts")
        self.quiescence()
        backups={"krb5.conf":self.public_backup(self.system_root/"etc/krb5.conf"),"resolv.conf":self.public_backup(self.system_root/"etc/resolv.conf")}
        public["inversePublicConfiguration"]=backups
        public["inverseOwnedPublicSha256"]={"krb5.conf":hashlib.sha256(pure["kerberosConfiguration"].encode()).hexdigest(),"resolv.conf":hashlib.sha256(pure["resolverConfiguration"].encode()).hexdigest()}
        public["inverseSource"]={"scope":source["scope"],"bootId":source["bootId"],"publicLocalMachineSid":source["publicLocalMachineSid"],"sourceConfigurationSha256":source["sourceConfigurationSha256"]}
        public["inverseCreatedArtifacts"]={}
        self.new_identity_clear(request,public)
        public["preJoinRemoteIdentityAbsent"]=True
        self.snapshot(request,"PREPARING",public)
        try:
            # This private config carries existing share definitions; canonical SMB
            # and all seven desired files remain under the rendered coordinator.
            self.write_public(self.machine,pure["smbConfiguration"])
            self.write_public(self.system_root/"etc/krb5.conf",pure["kerberosConfiguration"])
            self.write_public(self.system_root/"etc/resolv.conf",pure["resolverConfiguration"])
            self.command(["testparm","-s"])
            public["createdMachineConfigurationSha256"]=hashlib.sha256(self.machine.read_bytes()).hexdigest()
            self.snapshot(request,"JOINING",public)
            args=["net","ads","join"]
            if request.get("organizationalUnit"):args.append("createcomputer="+request["organizationalUnit"])
            credential_command(args,request["username"],request["password"],public["domain"],self.configured_run,self.deadline)
            self.command(["net","ads","testjoin","--machine-pass"])
            local=machine_sids(self.command(["net","getdomainsid"]))
            if local["machineSid"]!=source["publicLocalMachineSid"]:raise ValueError("JOIN machine-auth receipt changed target SAM")
            output=self.command(["net","ads","search","(sAMAccountName="+public["netbiosName"]+"$)","objectSid","--machine-pass"])
            matches=re.findall(r"(?mi)^\s*objectSid:\s*(S-[0-9-]+)\s*$",output)
            if not re.match(r"^Got 1 replies\s",output) or len(matches)!=1 or not re.fullmatch(re.escape(local["domainSid"])+r"-[0-9]{1,10}",matches[0]):
                raise ValueError("JOIN has no machine-authenticated owned computer SID")
            public["ownedComputerSid"]=matches[0];public["ownedDomainSid"]=local["domainSid"];public["machineAuthenticatedComputer"]=True
            self.snapshot(request,"OWNED_COMPUTER_CREATED",public)
            self.snapshot(request,"REGISTERING",public)
            for principal in public["servicePrincipals"]:
                credential_command(["net","ads","setspn","add",public["netbiosName"],principal],request["username"],request["password"],public["domain"],self.configured_run,self.deadline)
            for alias in public["dnsAliases"]:
                credential_command(["net","ads","dns","register",alias["hostname"],*alias["addresses"]],request["username"],request["password"],public["domain"],self.configured_run,self.deadline)
            self.command(["net","ads","keytab","create","--machine-pass"])
            for name,path in (("krb5.keytab",self.system_root/"etc/krb5.keytab"),):
                if path.exists():
                    info=path.lstat();public["inverseCreatedArtifacts"][name]={key:getattr(info,key) for key in ("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns")}
            self.snapshot(request,"KEYTAB_CREATED",public)
            self.daemon.start(request)
            idmap=self.system_root/"var/lib/samba/winbindd_idmap.tdb"
            if idmap.exists():
                info=idmap.lstat();public["inverseCreatedArtifacts"]["winbindd_idmap.tdb"]={key:getattr(info,key) for key in ("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns")}
            self.snapshot(request,"OWNED_DAEMON_STARTED",public)
            self.snapshot(request,"VERIFYING",public)
            probe=AdIdentityProbe(self.configured_run,self.deadline).verify(public["domain"],public["servicePrincipals"],machine_name=public["netbiosName"])
            account=machine_account_sid(self.command(["wbinfo","--name-to-sid",public["workgroup"]+chr(92)+public["netbiosName"]+"$"]),probe["domainSid"])
            mapping=idmap_policy(self.configured_run,public["workgroup"],self.deadline)
            if mapping!=public["idmapPolicy"]:raise ValueError("AD joined idmap readback differs from its exact private configuration")
            if probe["machineSid"]!=source["publicLocalMachineSid"]:raise ValueError("AD JOIN changed the target's captured public local SAM SID")
            if original is not None and (probe["domainSid"]!=original["identity"]["domainSid"] or account==original["identity"]["machineAccountSid"]):
                raise ValueError("NEW_INSTANCE computer identity reuses or escapes authenticated original")
            state={"state":"JOINED","joinState":"JOINED","instanceUuid":self.daemon.scope(request)["instanceUuid"],"domainName":public["domain"],"realm":public["realm"],"workgroup":public["workgroup"],"netbiosName":public["netbiosName"],
                   "dnsServers":public["dnsServers"],"dnsAliases":public["dnsAliases"],"servicePrincipals":public["servicePrincipals"],"idmapPolicy":mapping,
                   "identityReceipt":{key:probe[key] for key in ("machineSid","domainSid")},"machineConfigurationSha256":hashlib.sha256(self.machine.read_bytes()).hexdigest()}
            state["identityReceipt"]["machineAccountSid"]=account
            state["ordinaryJoinSource"]={"scope":source["scope"],"bootId":source["bootId"],"localMachineSid":source["publicLocalMachineSid"]}
            if original is not None:state["semanticOriginalSourceAuthority"]=original["descriptor"]
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

    def inverse_join(self,request):
        username=request.get("username");password=request.get("password")
        if (not isinstance(username,str) or not re.fullmatch(r"[A-Za-z0-9_.@\\-]{1,256}",username)
                or not isinstance(password,str) or not password or len(password)>4096 or any(c in password for c in ("\n","\r","\0"))):
            raise ValueError("Owned JOIN inverse requires complete sealed credentials")
        source=self.source_authority(request,fresh=False);journal=ad_protected_json(self.journal);public=journal.get("publicConfiguration")
        if (journal.get("scope")!=self.daemon.scope(request) or journal.get("phase") not in ("RECOVERY_REQUIRED","COMPLETE","OWNED_JOIN_INVERTED")
                or not isinstance(public,dict) or public.get("preJoinRemoteIdentityAbsent") is not True
                or public.get("machineAuthenticatedComputer") is not True or source.get("adIdentity") is not None
                or public.get("inverseSource")!={key:source[key] for key in ("scope","bootId","publicLocalMachineSid","sourceConfigurationSha256")}
                or not re.fullmatch(re.escape(str(public.get("ownedDomainSid")))+r"-[0-9]{1,10}",str(public.get("ownedComputerSid")))
                or self.sid_reader(public["netbiosName"])!=source["publicLocalMachineSid"]):
            raise ValueError("JOIN inverse has no exact newly-owned machine-authenticated computer/source receipt")
        if journal["phase"]=="OWNED_JOIN_INVERTED":
            for path in (self.machine,self.system_root/"etc/krb5.keytab",self.system_root/"var/lib/samba/winbindd_idmap.tdb"):
                if path.exists() or path.is_symlink():raise ValueError("JOIN inverse replay found a replacement AD artifact")
            for name,backup in public["inversePublicConfiguration"].items():
                path=self.system_root/"etc"/name
                if backup.get("absent") is True:
                    if path.exists() or path.is_symlink():raise ValueError("JOIN inverse replay original public absence changed")
                elif path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=backup.get("sha256"):
                    raise ValueError("JOIN inverse replay original public configuration changed")
            fresh=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if fresh.get("machineSid")!=source["publicLocalMachineSid"] or fresh.get("joinState")!="NOT_JOINED" or fresh.get("bootId")!=source["bootId"]:
                raise ValueError("JOIN inverse replay has no fresh preserved local identity")
            return self.inverse_receipt(request,fresh,replayed=True)
        if hashlib.sha256(self.machine.read_bytes()).hexdigest()!=public.get("createdMachineConfigurationSha256"):
            raise ValueError("JOIN inverse private machine configuration was replaced")
        backups=public.get("inversePublicConfiguration");owned=public.get("inverseOwnedPublicSha256")
        if not isinstance(backups,dict) or set(backups)!={"krb5.conf","resolv.conf"} or not isinstance(owned,dict) or set(owned)!=set(backups):
            raise ValueError("JOIN inverse public original ownership is unavailable")
        for name in backups:
            path=self.system_root/"etc"/name
            if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=owned[name]:raise ValueError("JOIN inverse public configuration was replaced")
        self.daemon.stop(request);self.quiescence()
        artifacts={"krb5.keytab":self.system_root/"etc/krb5.keytab","winbindd_idmap.tdb":self.system_root/"var/lib/samba/winbindd_idmap.tdb"}
        for name,path in artifacts.items():
            if path.exists() or path.is_symlink():
                binding=public.get("inverseCreatedArtifacts",{}).get(name);info=path.lstat()
                if (not isinstance(binding,dict) or not stat.S_ISREG(info.st_mode)
                        or any(getattr(info,key)!=binding[key] for key in ("st_dev","st_ino","st_uid","st_gid","st_mode"))):
                    raise ValueError("JOIN inverse artifact is not the journaled owned inode")
        self.command(["net","ads","testjoin","--machine-pass"])
        observed=self.command(["net","ads","search","(sAMAccountName="+public["netbiosName"]+"$)","objectSid","--machine-pass"])
        sids=re.findall(r"(?mi)^\s*objectSid:\s*(S-[0-9-]+)\s*$",observed)
        if not re.match(r"^Got 1 replies\s",observed) or sids!=[public["ownedComputerSid"]]:
            raise ValueError("JOIN inverse computer was replaced or machine trust is unproven")
        spns=[]
        for principal in public["servicePrincipals"]:
            output=[None]
            def capture(args,**kwargs):
                result=self.run(args,**kwargs)
                if result.returncode==0:output[0]=result.stdout
                return result
            credential_command(["net","ads","search","(servicePrincipalName="+principal+")","objectSid","--realm="+public["realm"],"--workgroup="+public["workgroup"],"--option=security=ADS"],
                               username,password,public["domain"],capture,self.deadline)
            if re.fullmatch(r"Got 0 replies\s*",output[0] or ""):continue
            matches=re.findall(r"(?mi)^\s*objectSid:\s*(S-[0-9-]+)\s*$",output[0] or "")
            if not re.match(r"^Got 1 replies\s",output[0] or "") or matches!=[public["ownedComputerSid"]]:raise ValueError("JOIN inverse SPN belongs to a foreign object")
            spns.append(principal)
        aliases=[]
        for alias in public["dnsAliases"]:
            values=[]
            for server in public["dnsServers"]:
                for kind in ("A","AAAA"):
                    result=self.run(["dig","+short","+time=2","+tries=1",kind,alias["hostname"],"@"+server],capture_output=True,text=True,timeout=min(5,self.deadline-time.monotonic()))
                    if result.returncode:raise ValueError("JOIN inverse DNS ownership is unobservable")
                    lines=sorted(set(result.stdout.split()))
                    if kind=="AAAA" and lines:raise ValueError("JOIN inverse DNS has foreign IPv6 addresses")
                    if kind=="A":
                        if lines and lines!=alias["addresses"]:raise ValueError("JOIN inverse DNS has foreign IPv4 addresses")
                        values.append(lines)
            if any(values):
                if any(value!=alias["addresses"] for value in values) or not {"host/"+alias["hostname"],"cifs/"+alias["hostname"]}<=set(spns):
                    raise ValueError("JOIN inverse DNS lacks exact owned computer/SPN binding")
                aliases.append(alias)
        self.snapshot(request,"INVERTING_OWNED_JOIN",public)
        try:
            for principal in spns:credential_command(["net","ads","setspn","delete",public["netbiosName"],principal],username,password,public["domain"],self.configured_run,self.deadline)
            for alias in aliases:credential_command(["net","ads","dns","unregister",alias["hostname"]],username,password,public["domain"],self.configured_run,self.deadline)
            credential_command(["net","ads","leave"],username,password,public["domain"],self.configured_run,self.deadline)
            self.new_identity_clear(request,public)
            if self.sid_reader(public["netbiosName"])!=source["publicLocalMachineSid"]:raise ValueError("JOIN inverse changed target SAM")
            for path in artifacts.values():
                if path.exists():path.unlink()
            self.machine.unlink()
            for name in backups:self.restore_public(self.system_root/"etc"/name,backups[name],owned[name])
            self.write_public(self.state,json.dumps({"state":"NOT_JOINED","joinState":"NOT_JOINED","instanceUuid":request["instanceUuid"],"netbiosName":public["netbiosName"],"localMachineSid":source["publicLocalMachineSid"]},sort_keys=True))
            fresh=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if fresh.get("machineSid")!=source["publicLocalMachineSid"] or fresh.get("joinState")!="NOT_JOINED" or fresh.get("bootId")!=source["bootId"]:
                raise ValueError("JOIN inverse final preserved local identity is not fresh")
            self.snapshot(request,"OWNED_JOIN_INVERTED",public)
            return self.inverse_receipt(request,fresh)
        except Exception:
            self.snapshot(request,"RECOVERY_REQUIRED",public);raise

    def inverse_receipt(self,request,fresh,replayed=False):
        return {"success":True,"scope":self.daemon.scope(request),"externalJoinInverted":True,"localMachineSidPreserved":True,"adOwnedArtifactsRemoved":True,
                "computerAliasSpnAbsent":True,"dnsAliasesAbsent":True,"publicConfigurationRestored":True,"canonicalDesiredStateChanged":False,"identity":fresh,"replayed":replayed}

    def semantic_local_authority(self,request):
        # Reuse protected SOURCE/cipher and the exact owned writer; public
        # request dictionaries cannot authorize a direct identity import.
        LocalSamBootstrap(self.cli).require_writer()
        source=self.source_authority(request,fresh=False);original=self.original_source(request)
        state=ad_protected_json(self.state);scope=self.daemon.scope(request);identity=original["identity"]
        if state.get("instanceUuid")!=scope["instanceUuid"] or state.get("joinState")!="JOINED":
            raise ValueError("Semantic LOCAL requires its domain-first protected joined target")
        receipt=state.get("identityReceipt") or {};name=state.get("netbiosName")
        if receipt.get("machineSid")!=source["publicLocalMachineSid"] or self.sid_reader(name)!=source["publicLocalMachineSid"]:
            raise ValueError("Semantic LOCAL target SAM changed after domain-first join")
        same=original["descriptor"]["sourceInstanceUuid"]==scope["instanceUuid"]
        if same:
            if receipt!={key:identity[key] for key in ("machineSid","domainSid","machineAccountSid")}:
                raise ValueError("SAME_VM semantic LOCAL target SID authority differs")
            fields=("realm","workgroup","netbiosName","dnsAliases","servicePrincipals","idmapPolicy","machineConfigurationSha256")
            if state.get("domainName")!=identity["domain"] or any(state.get(key)!=identity[key] for key in fields):
                raise ValueError("SAME_VM semantic LOCAL joined metadata differs")
        elif (state.get("semanticOriginalSourceAuthority")!=original["descriptor"] or receipt.get("machineSid")==identity["machineSid"]
                or receipt.get("domainSid")!=identity["domainSid"] or receipt.get("machineAccountSid")==identity["machineAccountSid"]):
            raise ValueError("NEW_INSTANCE semantic LOCAL original/target binding differs")
        return source,original,state

    def semantic_local_guard(self,request):
        source,original,state=self.semantic_local_authority(request);journal=ad_protected_json(self.journal)
        if journal.get("scope")!=self.daemon.scope(request) or journal.get("phase")!="LOCAL_RESTORING":
            raise ValueError("Semantic LOCAL import lacks its owned restore journal")
        self.quiescence()
        return {"success":True,"scope":self.daemon.scope(request),"originalSourceAuthority":original["descriptor"],
                "targetLocalMachineSid":source["publicLocalMachineSid"],"targetNetbiosName":state["netbiosName"],"bootId":source["bootId"],
                "serviceScope":self.daemon.marker(request),"sourceCheckpointRecordSha256":service_cipher_digest(ServiceIdentityCipher().read(ServiceIdentityCipher().path(self.daemon.marker(request))))}

    def semantic_local_restore(self,request):
        source,original,state=self.semantic_local_authority(request)
        public={key:state[key] for key in ("realm","workgroup","netbiosName","dnsAliases","servicePrincipals")}
        public["domain"]=state["domainName"]
        self.daemon.stop(request);self.quiescence();self.snapshot(request,"LOCAL_RESTORING",public)
        try:
            fields=("originalSourceAuthority","originalSourceCapsule","originalSourceCredentialPrivateKey")
            result=self.protected_request(("identity","capsule","semantic-local-restore"),{**self.daemon.marker(request),**{field:request[field] for field in fields},"resourceMappings":request.get("resourceMappings",[])})
            if any(result.get(key) is not True for key in ("localAccountsRestored","localPassdbSidRebased","targetSamPreserved")):
                raise ValueError("Semantic LOCAL importer has no complete literal receipt")
            self.daemon.start(request);fresh=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if fresh.get("machineSid")!=source["publicLocalMachineSid"] or fresh.get("bootId")!=source["bootId"] or fresh.get("identityVerified") is not True:
                raise ValueError("Semantic LOCAL final joined identity was not freshly preserved")
            state=ad_protected_json(self.state)
            state["semanticLocalIdentityReceipt"]={"scope":self.daemon.scope(request),"originalSourceAuthority":original["descriptor"],
                                                  "publicMappings":result["publicMappings"],"publicGroupMappings":result["publicGroupMappings"],"managedIdentityMappings":result["managedIdentityMappings"]}
            self.write_public(self.state,json.dumps(state,sort_keys=True));self.snapshot(request,"COMPLETE",public)
            return {"success":True,"scope":self.daemon.scope(request),**result,"identity":fresh,"canonicalDesiredStateChanged":False}
        except Exception:
            self.snapshot(request,"RECOVERY_REQUIRED",public);raise

    def target_resume(self,request):
        scope=self.daemon.scope(request);marker=service_cipher_scope(self.daemon.marker(request))
        root=Path(os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))
        captured=ad_protected_json(root/("service-identity-target-"+scope["operationUuid"]+".json"))
        if captured.get("kind")!="SERVICE_IDENTITY_TARGET" or captured.get("scope")!=marker:
            raise ValueError("TARGET winbind resume has no independent captured target")
        query={**marker,"targetConfigurationSha256":captured["targetConfigurationSha256"]}
        proof=self.protected_request(("operation","generation","render-service-target-stopped"),query)
        if (proof.get("scope")!=marker or proof.get("serviceTargetStoppedVerified") is not True
                or proof.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()):
            raise ValueError("TARGET winbind resume lacks its exact owned stopped target")
        protected_ad_policy(scope["instanceUuid"],configuration=self.configuration)
        started=self.daemon.start(request)
        return {**started,"scope":scope,"targetDaemonResumed":True}

    def retain(self,request,expected):
        marker=self.daemon.marker(request)
        if "templateUpgradeUuid" in marker:
            status={"maintenanceKind":"ROOT","bootHeld":True,"scope":marker}
            authority=root_ad_retained_authority(self.daemon.scope(request),status,configuration=self.configuration,reference=request.get("retainedRootAuthorization"))
            if (request.get("retainedRootAuthorization") is None or expected!=authority["identity"]
                    or any(request.get(key)!=authority.get(key) for key in ("originalCipherSha256","sourceConfigurationSha256","originalSourceScope"))):
                raise ValueError("ROOT opaque AD retain lacks its authenticated original ciphertext authority")
        else:
            source=self.source_authority(request,fresh=False);identity=source.get("adIdentity")
            fields=("domain","realm","workgroup","netbiosName","machineSid","domainSid","machineAccountSid","servicePrincipals","idmapPolicy","dnsAliases","machineConfigurationSha256")
            if not isinstance(identity,dict) or any(expected.get(key)!=identity.get(key) for key in fields):
                raise ValueError("SERVICE AD retain differs from protected BEFOREJOIN source")
        state=ad_protected_json(self.state)
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
            left_state={"state":"NOT_JOINED","joinState":"NOT_JOINED","instanceUuid":request["instanceUuid"],
                        "netbiosName":actual["netbiosName"],"previousIdentityReceipt":state["identityReceipt"],"localMachineSid":source["publicLocalMachineSid"]}
            for key in ("semanticManagedIdentityAliasSha256","semanticManagedIdentityAliasBinding","semanticLocalIdentityReceipt"):
                if key in state:left_state[key]=state[key]
            self.write_public(self.state,json.dumps(left_state,sort_keys=True))
            fresh=AdIdentityRpc(self.run,self.configuration,cli=self.cli).inspect(request)
            if (fresh.get("joinState")!="NOT_JOINED" or fresh.get("machineSid")!=source["publicLocalMachineSid"]
                    or fresh.get("bootId")!=source["bootId"]):raise ValueError("AD leave final local identity was not freshly attested")
            self.snapshot(request,"COMPLETE_LEFT",public)
            return {"success":True,"scope":self.daemon.scope(request),"left":True,"canonicalDesiredStateChanged":False,
                    "localMachineSidPreserved":True,"adOwnedArtifactsRemoved":True,"ownedArtifactCleanup":cleanup,
                    "publicConfigurationRestored":True,"computerAliasSpnAbsent":True,"dnsAliasesAbsent":True,"identity":fresh}
        except Exception:
            self.snapshot(request,"RECOVERY_REQUIRED",public);raise
