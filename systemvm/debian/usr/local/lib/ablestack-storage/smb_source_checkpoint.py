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

"""Stopped LOCAL source checkpoints; preserve opaque DBs and prior active SMB units."""
import hashlib,json,os,re,signal,stat,subprocess,tempfile,time,uuid
from pathlib import Path
from smb_identity import SmbIdentity,IDENTITY_DATABASES
from smb_current_retention import SmbCurrentRetention
from pending_nfs_authorization import PendingNfsAuthorization,PendingNfsGeneration
from identity_capsule import collect,encrypt,decrypt,validate_payload,regular_file,restore,select_restore_domains,restore_protected
from service_identity_cipher import ServiceIdentityCipher,service_cipher_digest
from posix_receipt_transfer import PosixReceiptTransfer
LOCAL_SOURCE_KIND="LOCAL_SOURCE_IDENTITY_CHECKPOINT"
LOCAL_SOURCE_SCOPE={"instanceUuid","operationUuid","revision","localCheckpointUuid"}
LOCAL_SOURCE_COMMON=LOCAL_SOURCE_SCOPE|{"sourceGeneration","sourceConfigurationSha256","expectedBootId"}

class SmbSourceCheckpoint:
    def __init__(self,cli=None,handler=None,command=None,root=None):
        self.handler=handler or SmbIdentity(cli);self.cli=self.handler.cli
        self.root=Path(root or self.handler.generations.parent/"smb-local-source-checkpoints")
        self.io=ServiceIdentityCipher();self.command=command or self.native_command
        self.collector=lambda *args,**kwargs:collect(*args,**kwargs);self.encryptor=lambda *args:encrypt(*args)
        self.decryptor=lambda *args:decrypt(*args);self.validator=lambda *args:validate_payload(*args)
        self.importer=lambda value:restore(select_restore_domains(value,["SMB"]))
        self.posix_collector=lambda desired,instance:PosixReceiptTransfer().collect(desired,instance,
            lambda value:self.command(("posix","directory","inspect","/dev/stdin"),value))
        self.reader=lambda *args:regular_file(*args);self.unit_authority=SmbCurrentRetention(self.cli,handler=self.handler)
        self.boot=lambda:Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.lock=lambda:PendingNfsAuthorization.lock(None,os.getpid())
        self.start_grant=self.pending_start_grant
        self.runtime_root=Path('/opt/ablestack/storage-runtime')
        self.installation_root=Path('/var/lib/ablestack-storage/runtime-updates')

    def installed_release_authority(self,release,manifest):
        """Read bounded public installation receipts; never mint a transaction."""
        root=self.installation_root;info=root.lstat()
        if not stat.S_ISDIR(info.st_mode)or info.st_uid!=0 or info.st_mode&0o022:
            raise ValueError('LOCAL historical installation directory is foreign')
        paths=list(root.iterdir())
        if len(paths)>128:raise ValueError('LOCAL historical installation lookup exceeds its bound')
        matches=[]
        for path in sorted(paths):
            parent=path.lstat()
            if not stat.S_ISDIR(parent.st_mode)or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}',path.name):continue
            if parent.st_uid!=0 or parent.st_mode&0o022:raise ValueError('LOCAL installation receipt directory is foreign')
            header=path/'state.json'
            if not os.path.lexists(header):continue
            raw,header_meta=self.reader(str(header),64*1024);state=json.loads(raw)
            if state.get('bundleVersion')!=release.name:continue
            if (header_meta.st_mode&0o022
                    or state.get('transactionId')!=path.name or state.get('phase')not in ('VERIFIED','COMPLETE')
                    or state.get('releasePath')!=str(release)or state.get('keyId')!=manifest.get('keyId')
                    or not isinstance(state.get('keyId'),str)or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}',state['keyId'])
                    or any(not isinstance(state.get(k),str)or not re.fullmatch('[0-9a-f]{64}',state[k])
                        for k in ('manifestSha256','archiveSha256'))):
                raise ValueError('LOCAL historical installation receipt changed')
            transaction_raw,transaction_meta=self.reader(str(path/'manifest.json'),64*1024)
            if transaction_meta.st_mode&0o022 or hashlib.sha256(transaction_raw).hexdigest()!=state['manifestSha256']or json.loads(transaction_raw)!=manifest:
                raise ValueError('LOCAL historical signed manifest differs from installation')
            signature=path/'manifest.sig'
            if os.path.lexists(signature):
                self.reader(str(signature));key=self.runtime_root/'trusted-keys'/(state['keyId']+'.pem')
                self.reader(str(key))
                result=subprocess.run(['openssl','pkeyutl','-verify','-pubin','-inkey',str(key),'-sigfile',str(signature),
                    '-rawin','-in',str(path/'manifest.json')],capture_output=True,timeout=self.handler.remaining(10))
                if result.returncode:raise ValueError('LOCAL historical installation signature changed')
            matches.append({key:state[key]for key in ('transactionId','bundleVersion','keyId','manifestSha256','archiveSha256','phase')})
            after=path.lstat()
            if (after.st_dev,after.st_ino,after.st_uid,after.st_mode)!=(parent.st_dev,parent.st_ino,parent.st_uid,parent.st_mode):
                raise ValueError('LOCAL installation directory changed while reading')
        if not matches or len({tuple(row[key]for key in ('bundleVersion','keyId','manifestSha256','archiveSha256'))for row in matches})!=1:
            raise ValueError('LOCAL historical verified installation is absent or ambiguous')
        return matches[0]

    @staticmethod
    def endpoint_body(source):
        marker='smb_endpoint_run() {\n'
        if source.count(marker)!=1:raise ValueError('LOCAL signed endpoint entry is ambiguous')
        body=source.split(marker,1)[1].split('\n}\n',1)[0]
        if not body or "<<'PYSMBENDPOINT'" not in body:
            raise ValueError('LOCAL signed endpoint entry is unavailable')
        return body

    def launcher_authority(self,unit,master):
        """Bind the signed Bash launcher to its direct, exact managed acceptor."""
        main=int(self.handler.run(['systemctl','show',unit,'--property=MainPID','--value']).strip())
        if main==master['pid']:return None
        if main<=0:raise ValueError('LOCAL managed launcher is absent')
        proc=self.handler.process_root/str(main);child=self.handler.process_root/str(master['pid'])
        before=(proc/'stat').read_text().rpartition(')')[2].split()
        child_stat=(child/'stat').read_text().rpartition(')')[2].split()
        uid=next(line.split()[1:]for line in (proc/'status').read_text().splitlines()if line.startswith('Uid:'))
        cgroup=(proc/'cgroup').read_text()
        key=unit.removeprefix('ablestack-storage-smb@').removesuffix('.service')
        declared_group=self.handler.run(['systemctl','show',unit,'--property=ControlGroup','--value']).strip()
        declared_slice=self.handler.run(['systemctl','show',unit,'--property=Slice','--value']).strip()
        default_slice='system-'+'ablestack-storage-smb'.replace('-','\\x2d')+'.slice'
        expected_group='/system.slice/'+(declared_slice+'/'if declared_slice==default_slice else '')+unit
        system_groups=[line.split(':',2)[-1]for line in cgroup.splitlines()
            if len(line.split(':',2))==3 and line.split(':',2)[1]in ('','name=systemd')]
        if (not re.fullmatch('[0-9a-f]{24}',key)or int(child_stat[1])!=main or child_stat[19]!=master['startTicks']
                or uid!=['0']*4 or cgroup!=(child/'cgroup').read_text()
                or declared_slice not in ('system.slice',default_slice)or declared_group!=expected_group
                or system_groups!=[declared_group]):
            raise ValueError('LOCAL managed launcher parent/UID/cgroup changed')
        argv=[part.decode()for part in (proc/'cmdline').read_bytes().split(bytes([0]))if part]
        if argv not in [[shell,'/usr/local/bin/ablestack-storagectl','smb','endpoint-run',key]
                for shell in ('bash','/bin/bash','/usr/bin/bash')]:
            raise ValueError('LOCAL managed launcher argv is foreign')
        bash,bash_meta=self.reader('/usr/bin/bash');checksums,_=self.reader('/var/lib/dpkg/info/bash.md5sums')
        matches=[line.split()[0]for line in checksums.decode().splitlines()
            if len(line.split())==2 and line.split()[1]in ('bin/bash','usr/bin/bash')]
        loaded=(proc/'exe').stat()
        if (len(matches)!=1 or hashlib.md5(bash).hexdigest()!=matches[0]
                or os.path.realpath(proc/'exe')!='/usr/bin/bash'
                or (loaded.st_dev,loaded.st_ino)!=(bash_meta.st_dev,bash_meta.st_ino)):
            raise ValueError('LOCAL managed launcher fixed Bash package changed')
        fdpath=proc/'fd/255';target=os.readlink(fdpath)
        release=Path(target).parent
        if (target.endswith(' (deleted)')or Path(target).name!='ablestack-storagectl'
                or release.parent!=self.runtime_root/'releases'
                or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}',release.name)):
            raise ValueError('LOCAL managed launcher loaded script is foreign')
        for directory in (self.runtime_root,self.runtime_root/'releases',release):
            info=directory.lstat()
            if not stat.S_ISDIR(info.st_mode)or info.st_uid!=0 or info.st_mode&0o022:
                raise ValueError('LOCAL managed launcher release directory is foreign')
        script,meta=self.reader(target);fdmeta=fdpath.stat()
        if ((fdmeta.st_dev,fdmeta.st_ino,fdmeta.st_size)!=(meta.st_dev,meta.st_ino,meta.st_size)
                or meta.st_uid!=0 or stat.S_IMODE(meta.st_mode)!=0o755):
            raise ValueError('LOCAL managed launcher script FD changed')
        manifest_raw,_=self.reader(str(release/'manifest.json'));manifest=json.loads(manifest_raw)
        entries=[row for row in manifest.get('files',[])if isinstance(row,dict)and row.get('path')=='ablestack-storagectl']
        digest=hashlib.sha256(script).hexdigest()
        if (manifest.get('bundleVersion')!=release.name or len(entries)!=1
                or entries[0].get('sha256')!=digest or entries[0].get('mode')!='0755'
                or entries[0].get('owner')!='root' or entries[0].get('group')!='root'):
            raise ValueError('LOCAL managed launcher installed ENTRY manifest changed')
        installed=self.installed_release_authority(release,manifest)
        # Historical files are installed, protected runtime releases. Their
        # permitted execution is narrowed to the byte-identical fixed endpoint
        # body of the currently signed CLI; this is not current-runtime attestation.
        body=self.endpoint_body(script.decode());signed_body=self.endpoint_body(self.cli.read_text())
        if body!=signed_body:raise ValueError('LOCAL managed launcher endpoint body changed')
        if ((proc/'stat').read_text().rpartition(')')[2].split()[19]!=before[19]
                or (child/'stat').read_text().rpartition(')')[2].split()[1]!=str(main)
                or os.readlink(fdpath)!=target or fdpath.stat().st_ino!=fdmeta.st_ino):
            raise ValueError('LOCAL managed launcher changed during observation')
        return {'pid':main,'startTicks':before[19],'argv':argv,'cgroup':cgroup,'controlGroup':declared_group,'slice':declared_slice,
            'executable':{'path':'/usr/bin/bash','device':bash_meta.st_dev,'inode':bash_meta.st_ino,'sha256':hashlib.sha256(bash).hexdigest()},
            'loadedScript':{'path':target,'device':meta.st_dev,'inode':meta.st_ino,'sha256':digest,
                'manifestSha256':hashlib.sha256(manifest_raw).hexdigest(),'endpointBodySha256':hashlib.sha256(body.encode()).hexdigest(),
                'verifiedInstallation':installed}}
    def pending_start_grant(self,scope,unit):
        owner=self
        class NativeStatus:
            def __init__(self,args):self.args=args
            def status(self):return owner.command(self.args)
        authorization=PendingNfsAuthorization(generation=PendingNfsGeneration(),protocol="SMB",
            rendered=NativeStatus(("operation","generation","render-status")),
            maintenance=NativeStatus(("operation","maintenance","status")))
        return authorization.grant({"instanceUuid":scope["instanceUuid"],
            "operationScope":{k:scope[k]for k in ("instanceUuid","operationUuid","revision")}},unit)
    def native_command(self,args,payload=None):
        result=subprocess.run([str(self.cli),*args],input=json.dumps(payload)if payload is not None else None,
            capture_output=True,text=True,timeout=self.handler.remaining(20),
            pass_fds=(9,)if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")=="9"else ())
        if result.returncode:raise ValueError("LOCAL source native observation failed")
        value=json.loads(result.stdout)
        if value.get("success")is not True:raise ValueError("LOCAL source native observation did not verify")
        return value
    def request(self,value,action):
        extra={"export-local-source":{"publicKey","names","nvmeHosts","authReplayDomains"},
            "import-local-source":{"capsule","credentialPrivateKey","localSourceCheckpointReference","deferNvmeReplay","restoreDomains"},
            "replay-local-source-auth":{"capsule","credentialPrivateKey","localSourceCheckpointReference","restoreDomains"}|(
                {"nvmeDesired"}if isinstance(value,dict)and "NVMEOF"in value.get("restoreDomains",[])else set()),
            "local-source-status":set()}[action]
        if action in("import-local-source","replay-local-source-auth")and isinstance(value,dict)and "renderedManifestSha256"in value:
            extra=extra|{"renderedManifestSha256"}
        if not isinstance(value,dict)or set(value)!=LOCAL_SOURCE_COMMON|extra:raise ValueError("LOCAL source request is not closed")
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        for key in ("instanceUuid","operationUuid","localCheckpointUuid"):
            if not isinstance(scope[key],str)or str(uuid.UUID(scope[key]))!=scope[key]:raise ValueError("LOCAL source UUID is not canonical")
        expected=str(uuid.UUID(bytes=hashlib.md5(("local-source-checkpoint:"+scope["operationUuid"]).encode()).digest(),version=3))
        if scope["localCheckpointUuid"]!=expected or type(scope["revision"])is not int or scope["revision"]<1:
            raise ValueError("LOCAL source purpose/revision is foreign")
        if (not isinstance(value["sourceGeneration"],dict)or not re.fullmatch("[0-9a-f]{64}",str(value["sourceConfigurationSha256"]))
                or not isinstance(value["expectedBootId"],str)or str(uuid.UUID(value["expectedBootId"]))!=value["expectedBootId"]):
            raise ValueError("LOCAL source generation/hash/boot is malformed")
        if action=="import-local-source"and(value["deferNvmeReplay"]is not True or value["restoreDomains"]!=["SMB"]):
            raise ValueError("LOCAL source restore domains are not closed")
        domains=value.get("authReplayDomains",value.get("restoreDomains"))
        if action in("export-local-source","replay-local-source-auth")and domains not in([], ["ISCSI"], ["NVMEOF"], ["ISCSI","NVMEOF"]):
            raise ValueError("LOCAL source block domain scope is not closed")
        if action=="replay-local-source-auth"and not domains:
            raise ValueError("LOCAL auth replay request has foreign NVMe bindings")
        if action!="local-source-status":
            if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")!="9":raise ValueError("LOCAL source requires bootstrapped FD9")
            self.lock()
        return scope
    def common(self,value):return {key:value[key]for key in LOCAL_SOURCE_COMMON}
    def path(self,scope,suffix):return self.root/(scope["localCheckpointUuid"]+"-"+suffix+".json")
    def read(self,path):return self.io.read(path)if os.path.lexists(path)else None
    def write(self,scope,suffix,value,immutable=False):
        self.root.mkdir(mode=0o700,parents=True,exist_ok=True);parent=self.root.lstat()
        if not stat.S_ISDIR(parent.st_mode)or parent.st_uid!=os.geteuid()or stat.S_IMODE(parent.st_mode)!=0o700:
            raise ValueError("LOCAL source checkpoint directory is foreign")
        path=self.path(scope,suffix)
        if immutable:
            old=self.read(path)
            if old is not None:
                if old!=value:raise ValueError("LOCAL source immutable checkpoint changed")
            else:self.io.write(path,value)
            return
        raw=json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()
        if len(raw)>16*1024*1024:raise ValueError("LOCAL source journal exceeds its bound")
        fd,tmp=tempfile.mkstemp(prefix=".local-source-",dir=self.root)
        try:
            os.fchmod(fd,0o600)
            with os.fdopen(fd,"wb")as out:out.write(raw);out.flush();os.fsync(out.fileno())
            os.replace(tmp,path);directory=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    def state(self,value,source_only=True,terminal=False):
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        pending=self.read(self.handler.generations/"pending.json");current=self.read(self.handler.generations/"current.json")or {}
        expected_phase="PREPARED"
        if pending is None and terminal:
            completed=self.read(self.handler.generations/(scope["operationUuid"]+".json"))
            journal=self.read(self.path(scope,"journal"))
            if (not completed or completed.get("phase")!="ROLLED_BACK"or not journal or journal.get("phase")!="RESUMED"
                    or completed.get("previous")!=value["sourceGeneration"]):
                raise ValueError("LOCAL terminal observation lacks its rolled-back original writer")
            pending=completed;expected_phase="ROLLED_BACK"
        if not isinstance(pending,dict):raise ValueError("LOCAL source original pending writer is absent")
        if (pending.get("phase")!=expected_phase or any(pending.get(k)!=scope[k]for k in ("instanceUuid","operationUuid","revision"))
                or pending.get("previous")!=value["sourceGeneration"]or current!=value["sourceGeneration"]
                or pending.get("beforeSha256")!=value["sourceConfigurationSha256"]or self.boot()!=value["expectedBootId"]):
            raise ValueError("LOCAL source PREPARED authority changed")
        if current:
            if (current.get("instanceUuid")!=scope["instanceUuid"]or current.get("revision")!=scope["revision"]-1
                    or current.get("configurationSha256")!=value["sourceConfigurationSha256"]):
                raise ValueError("LOCAL source current generation is foreign")
            frozen=self.command(("operation","generation","frozen","/dev/stdin"),{k:current[k]for k in ("instanceUuid","operationUuid","revision")})
            if frozen.get("generation")!=current or frozen.get("configurationSha256")!=value["sourceConfigurationSha256"]:
                raise ValueError("LOCAL source verified artifact changed")
        elif scope["revision"]!=1:raise ValueError("LOCAL initial source has a later revision")
        status=self.command(("operation","generation","status"))
        maintenance=self.command(("operation","maintenance","status"));rendered=self.command(("operation","generation","render-status"))
        activation=rendered.get("activation")or {};pin=value.get("renderedManifestSha256")
        held_local=(isinstance(pin,str)and re.fullmatch("[0-9a-f]{64}",pin)and activation.get("scope")=={k:scope[k]for k in("instanceUuid","operationUuid","revision")}
            and activation.get("phase")=="ROLLING_BACK"and activation.get("targetSha256")==pin
            and (rendered.get("current")or {}).get("manifestSha256")==activation.get("previousSha256"))
        if (status.get("generation")not in (current,None)or status.get("bootId")!=value["expectedBootId"]
                or maintenance.get("bootHeld")is not False or not(rendered.get("bootHeld")is False or held_local)):
            raise ValueError("LOCAL source cannot borrow ROOT/SERVICE/rendered hold")
        if source_only and status.get("configurationSha256")!=value["sourceConfigurationSha256"]:
            raise ValueError("LOCAL source canonical seven changed")
        active=rendered.get("current")
        if active is not None and(not current or active.get("scope")!={k:current[k]for k in ("instanceUuid","operationUuid","revision")}
                or active.get("configurationSha256")!=value["sourceConfigurationSha256"]):
            raise ValueError("LOCAL source rendered baseline is foreign")
        domain=self.read(self.handler.configuration/"smb-domain.json")
        if domain and(domain.get("joined")is True or domain.get("identityProvider")=="AD"or domain.get("state")=="JOINED"):
            raise ValueError("LOCAL source cannot capture joined AD")
        if any(os.path.lexists(n)for n in ("/etc/ablestack-storage/ad-machine.conf","/etc/krb5.keytab")):
            raise ValueError("LOCAL source contains protected AD artifacts")
        return status

    def stopped_observation(self):
        if self.unit_authority.units(require_master=False)or self.handler.identity_holders():
            raise ValueError("LOCAL stopped source has active units/private holders")
        databases=self.handler.database_identity()
        for proc in self.handler.process_root.iterdir():
            self.handler.remaining()
            if not proc.name.isdigit():continue
            try:
                if (proc/"comm").read_text().strip()in("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd"):
                    raise ValueError("LOCAL stopped source has an identity process")
                for fd in (proc/"fd").iterdir():
                    try:
                        target=os.readlink(fd);canonical=target[:-10]if target.endswith(" (deleted)")else target
                        if canonical in IDENTITY_DATABASES.values():raise ValueError("LOCAL stopped source has a private FD")
                    except FileNotFoundError:continue
            except FileNotFoundError:continue
        path=self.handler.configuration/"desired-state/smb-share-apply.json"
        endpoints=self.handler.endpoints()if os.path.lexists(path)else []
        ports={row["port"]for row in endpoints}|{139,445}
        if any(row["port"]in ports for row in self.handler.socket_rows(self.handler.run(["ss","-H","-ltnp"]))):
            raise ValueError("LOCAL stopped source has an SMB listener")
        if any(row["port"]in ports and row["state"]in("ESTAB","SYN-RECV","SYN_RECV")
                for row in self.handler.socket_rows(self.handler.run(["ss","-H","-ntp"]))):
            raise ValueError("LOCAL stopped source has a live SMB connection")
        return {"databases":databases,"units":[],"ownedEndpoints":[],"sessionsVerifiedEmpty":True,
            "configurationSha256":hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()}

    def observe(self,value,stopped=False,allow_missing=False,require_empty=True):
        if stopped:return self.stopped_observation()
        scope={k:value[k]for k in ("instanceUuid","operationUuid","revision")}
        if not value["sourceGeneration"]:
            if os.path.lexists(self.handler.configuration/"desired-state/smb-share-apply.json"):
                raise ValueError("LOCAL initial source contains an unverified SMB configuration")
            databases=self.handler.database_identity()
            if any(row.get("present")for row in databases.values())or self.handler.identity_holders():
                raise ValueError("LOCAL initial source has an unowned identity database")
            units=self.unit_authority.units(require_master=False)
            if units:raise ValueError("LOCAL initial source has active SMB units")
            return {"databases":databases,"units":[],"ownedEndpoints":[],"sessionsVerifiedEmpty":True,
                "configurationSha256":hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()}
        view=self.handler.inspect(scope,allow_missing=True)
        if (require_empty and view.get("sessions",{}).get("safeToRebind")is not True
                or view.get("identityDatabaseAligned")is not True):
            raise ValueError("LOCAL source sessions/descriptors are not safe")
        units=self.unit_authority.units(require_master=False);masters=view["masters"]
        for row in units:
            if row["unit"]=="smbd.service"and not any(m["pid"]==row["pid"]for m in masters):
                raise ValueError("LOCAL default smbd is not its owned endpoint")
        for master in masters:
            if master["unit"]=="smbd.service":continue
            unit=master["unit"];path=self.handler.unit_path;info=path.lstat()
            fragment=self.handler.run(["systemctl","show",unit,"--property=FragmentPath","--value"]).strip()
            if (fragment!=str(path)or not stat.S_ISREG(info.st_mode)or info.st_uid!=os.geteuid()or info.st_mode&0o022
                    or path.read_text()!=self.handler.managed_unit_definition()
                    or self.handler.run(["systemctl","show",unit,"--property=DropInPaths","--value"]).strip()):
                raise ValueError("LOCAL managed unit definition/master is foreign")
            endpoint=next(row for row in view["ownedEndpoints"]if row["pid"]==master["pid"])
            record=self.io.read(self.handler.configuration/"smb-endpoint-listeners"/(endpoint["key"]+".json"))
            if record!={"listenIp":endpoint["listenIp"],"port":endpoint["port"]}:
                raise ValueError("LOCAL managed endpoint registry changed")
            binary=self.unit_authority.package_file("/usr/sbin/smbd")
            proc=self.handler.process_root/str(master["pid"]);loaded=(proc/"exe").stat()
            if (loaded.st_dev,loaded.st_ino)!=(binary["device"],binary["inode"])or os.path.realpath(proc/"exe")!="/usr/sbin/smbd":
                raise ValueError("LOCAL loaded managed binary changed")
            pid_dir="/run/ablestack-storage/smb/"+endpoint["key"]
            expected=["/usr/sbin/smbd","--foreground","--no-process-group","-s","/etc/samba/smb.conf",
                "--option=smb ports="+str(endpoint["port"]),"--option=pid directory="+pid_dir]
            if endpoint["listenIp"]in("0.0.0.0","::"):expected.append("--option=bind interfaces only=no")
            else:expected.extend(["--option=interfaces="+endpoint["listenIp"],"--option=bind interfaces only=yes"])
            actual=[arg.decode()for arg in (proc/"cmdline").read_bytes().split(bytes([0]))if arg]
            if actual!=expected:raise ValueError("LOCAL managed source argv changed")
            units.append({**master,"executable":binary,'managedLauncher':self.launcher_authority(unit,master)})
        for row in units:
            master=next((item for item in masters if item["pid"]==row["pid"]),None)
            row["lockingDatabasesAligned"]=master["lockingDatabasesAligned"]if master else True
        allowed={row["unit"]for row in units}
        holders=self.handler.identity_holders()
        for process in self.handler.process_root.iterdir():
            self.handler.remaining()
            if not process.name.isdigit():continue
            try:
                group=(process/"cgroup").read_text();name=(process/"comm").read_text().strip()
                if name in ("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd")and not any("/"+unit in group for unit in allowed):
                    raise ValueError("LOCAL source has an unowned identity process")
                for fd in (process/"fd").iterdir():
                    try:
                        target=os.readlink(fd);canonical=target[:-10]if target.endswith(" (deleted)")else target
                        if canonical not in IDENTITY_DATABASES.values():continue
                        row=next(item for item in view["databases"].values()if item["path"]==canonical);opened=fd.stat()
                        if (stopped or target.endswith(" (deleted)")or not any("/"+unit in group for unit in allowed)
                                or (opened.st_dev,opened.st_ino)!=(row["device"],row["inode"])):
                            raise ValueError("LOCAL source private FD is foreign/stale")
                    except FileNotFoundError:continue
            except FileNotFoundError:continue
        sockets=self.handler.socket_rows(self.handler.run(["ss","-H","-ltnp"]))
        wanted={row["port"]for row in view["ownedEndpoints"]}|{139,445};pids={row["pid"]for row in units if row["unit"]!="nmbd.service"}
        if any(row["port"]in wanted and(row["state"]!="LISTEN"or not row["pids"]or not set(row["pids"])<=pids)for row in sockets):
            raise ValueError("LOCAL source has an unowned listener")
        if stopped and(units or holders or any(row.get("listening")for row in view["ownedEndpoints"])):
            raise ValueError("LOCAL source still has an identity holder/acceptor")
        return {"databases":view["databases"],"units":sorted(units,key=lambda row:row["unit"]),
            "ownedEndpoints":view["ownedEndpoints"],"sessionsVerifiedEmpty":view["sessions"]["safeToRebind"]is True,
            "configurationSha256":view["configurationSha256"]}

    def compat_entry(self,path,expected_sha):
        release=path.parent
        if (release.parent!=self.runtime_root/'releases' or path.name!='ablestack-storagectl'
                or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}',release.name)):
            raise ValueError('LOCAL compatibility ENTRY path is foreign')
        for folder in (self.runtime_root,self.runtime_root/'releases',release):
            meta=folder.lstat()
            if not stat.S_ISDIR(meta.st_mode)or meta.st_uid!=0 or meta.st_mode&0o022:
                raise ValueError('LOCAL compatibility installation directory is foreign')
        raw,meta=self.reader(str(path))
        if meta.st_uid!=0 or stat.S_IMODE(meta.st_mode)!=0o755 or hashlib.sha256(raw).hexdigest()!=expected_sha:
            raise ValueError('LOCAL compatibility ENTRY identity changed')
        manifest_raw,manifest_meta=self.reader(str(release/'manifest.json'),64*1024)
        manifest=json.loads(manifest_raw)
        entries=[row for row in manifest.get('files',[])if isinstance(row,dict)and row.get('path')=='ablestack-storagectl']
        if (manifest_meta.st_mode&0o022 or manifest.get('bundleVersion')!=release.name or len(entries)!=1
                or entries[0].get('sha256')!=expected_sha or entries[0].get('mode')!='0755'
                or entries[0].get('owner')!='root' or entries[0].get('group')!='root'):
            raise ValueError('LOCAL compatibility installed manifest changed')
        self.installed_release_authority(release,manifest)
        return raw

    def normalized_collector_cli(self,data,original):
        """Pure byte normalization for one explicitly authorized LOCAL collector repair."""
        import ast
        import copy
        import hashlib
        import re

        SECTIONS = ("PYIDENTITY", "PYRENDEREDGENERATION")
        KNOWN_HELPERS = frozenset(("normalized_collector_cli", "compat_entry", "code_compatibility"))

        class Rejected(ValueError):
            pass

        def require(value, reason):
            if not value:
                raise Rejected(reason)

        def span(lines, node):
            start = sum(map(len, lines[:node.lineno - 1])) + node.col_offset
            end = sum(map(len, lines[:node.end_lineno - 1])) + node.end_col_offset
            return start, end

        def section(data, label):
            opener = ("<<'" + label + "'\n").encode()
            closer = ("\n" + label + "\n").encode()
            require(data.count(opener) == 1 and data.count(closer) == 1, "section occurrence differs")
            start = data.index(opener) + len(opener)
            end = data.index(closer, start)
            return start, end, data[start:end]

        def parsed_class(block):
            try:
                tree = ast.parse(block.decode("utf-8"))
            except (SyntaxError, UnicodeError) as invalid:
                raise Rejected("inline Python cannot be parsed") from invalid
            classes = [node for node in ast.walk(tree) if isinstance(node, ast.ClassDef) and node.name == "SmbSourceCheckpoint"]
            require(len(classes) == 1 and classes[0] in tree.body, "LOCAL class occurrence differs")
            cls = classes[0]
            methods = [node for node in cls.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))]
            require(len({node.name for node in methods}) == len(methods), "duplicate LOCAL method")
            return cls, {node.name: node for node in methods}, block.splitlines(keepends=True)

        def ast_same(left, right):
            return ast.dump(left, include_attributes=False) == ast.dump(right, include_attributes=False)

        def collector_assignment(method):
            nodes = [node for node in ast.walk(method) if isinstance(node, ast.Assign)
                     and len(node.targets) == 1 and isinstance(node.targets[0], ast.Attribute)
                     and isinstance(node.targets[0].value, ast.Name) and node.targets[0].value.id == "self"
                     and node.targets[0].attr == "collector"]
            require(len(nodes) == 1, "collector assignment occurrence differs")
            return nodes[0]

        def expected_frozen(old):
            require(isinstance(old, ast.FunctionDef) and len(old.body) == 2
                    and isinstance(old.body[0], ast.If) and isinstance(old.body[1], ast.Return)
                    and isinstance(old.body[0].test, ast.BoolOp) and isinstance(old.body[0].test.op, ast.Or)
                    and len(old.body[0].test.values) == 3 and not old.body[0].orelse,
                    "historical frozen shape differs")
            result = copy.deepcopy(old)
            checksum = copy.deepcopy(result.body[0].test.values[-1])
            result.body[0].test.values = result.body[0].test.values[:2]
            call = ast.parse("self.code_compatibility(value,journal)").body[0]
            result.body.insert(1, ast.If(test=checksum, body=[call], orelse=[]))
            return result

        def normalize_block(current, original, helpers):
            _, methods, lines = parsed_class(current)
            _, old, old_lines = parsed_class(original)
            require(set(methods) == set(old) | helpers and not (set(old) & helpers), "unknown or missing LOCAL method")
            require(ast_same(methods["frozen"], expected_frozen(old["frozen"])), "unknown frozen body/signature")
            current_collector = collector_assignment(methods["__init__"])
            old_collector = collector_assignment(old["__init__"])
            expected_old = ast.parse("self.collector=lambda *args:collect(*args)").body[0]
            expected_new = ast.parse("self.collector=lambda *args,**kwargs:collect(*args,**kwargs)").body[0]
            require(ast_same(old_collector, expected_old) and ast_same(current_collector, expected_new), "unknown collector forwarding")
            edits = []
            for name in helpers:
                node = methods[name]
                require(isinstance(node, ast.FunctionDef), "unknown compatibility method type")
                first = min([node.lineno] + [decorator.lineno for decorator in node.decorator_list])
                start = sum(map(len, lines[:first - 1]))
                end = sum(map(len, lines[:node.end_lineno]))
                require(node.end_lineno < len(lines) and lines[node.end_lineno] == b"\n", "helper trailing separator differs")
                edits.append((start, end + 1, b""))
            first, last = span(lines, methods["frozen"])
            a, b = span(old_lines, old["frozen"])
            edits.append((first, last, original[a:b]))
            first, last = span(lines, current_collector)
            a, b = span(old_lines, old_collector)
            edits.append((first, last, original[a:b]))
            ordered = sorted(edits)
            require(all(left[1] <= right[0] for left, right in zip(ordered, ordered[1:])), "overlapping normalization edits")
            normalized = current
            for first, last, replacement in reversed(ordered):
                normalized = normalized[:first] + replacement + normalized[last:]
            require(normalized == original, "non-permitted inline bytes changed")
            return normalized

        def normalize_cli(current, original, helper_names):
            helpers = frozenset(helper_names)
            require(helpers == KNOWN_HELPERS and len(tuple(helper_names)) == 3, "helper allowlist differs")
            require(current.count(("class " + "SmbSourceCheckpoint" + ":").encode()) == 2 and original.count(("class " + "SmbSourceCheckpoint" + ":").encode()) == 2,
                    "unexpected LOCAL class outside sections")
            edits = []
            for label in SECTIONS:
                start, end, block = section(current, label)
                _, _, old_block = section(original, label)
                edits.append((start, end, normalize_block(block, old_block, helpers)))
            normalized = current
            for first, last, replacement in reversed(sorted(edits)):
                normalized = normalized[:first] + replacement + normalized[last:]
            require(normalized == original, "non-permitted whole CLI bytes changed")
            return normalized
        return normalize_cli(data,original,("normalized_collector_cli","compat_entry","code_compatibility"))

    def code_compatibility(self,value,journal):
        old_sha=journal.get('cliSha256');current=self.cli.read_bytes();new_sha=hashlib.sha256(current).hexdigest()
        if not isinstance(old_sha,str)or not re.fullmatch('[0-9a-f]{64}',old_sha):
            raise ValueError('LOCAL original STOP code pin is malformed')
        current_entry=self.cli.resolve(strict=True)
        active=(self.runtime_root/'current'/'ablestack-storagectl').resolve(strict=True)
        if current_entry!=active:raise ValueError('LOCAL current compatibility ENTRY is not installed')
        self.compat_entry(current_entry,new_sha)
        releases=self.runtime_root/'releases';paths=list(releases.iterdir())
        if len(paths)>128:raise ValueError('LOCAL historical code lookup exceeds its bound')
        old=[]
        for directory in sorted(paths):
            meta=directory.lstat()
            if not stat.S_ISDIR(meta.st_mode):continue
            candidate=directory/'ablestack-storagectl'
            if not os.path.lexists(candidate):continue
            raw,_=self.reader(str(candidate))
            if hashlib.sha256(raw).hexdigest()==old_sha:old.append(self.compat_entry(candidate,old_sha))
        if len(old)!=1 or self.normalized_collector_cli(current,old[0])!=old[0]:
            raise ValueError('LOCAL collector retry differs from its complete original signed code')
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        path=self.path(scope,'code-compatibility');receipt=self.read(path)
        stable={'schemaVersion':1,'kind':'LOCAL_SOURCE_COLLECTOR_CODE_COMPATIBILITY','scope':scope,'common':self.common(value),
            'originalCliSha256':old_sha,'currentCliSha256':new_sha,'publicKeySha256':hashlib.sha256(journal['publicKey'].encode()).hexdigest(),
            'names':journal['names'],'nvmeHosts':journal['nvmeHosts'],'authReplayDomains':journal['authReplayDomains']}
        if receipt is not None:
            if set(receipt)!=set(stable)|{'originalStoppedMetadata'}or any(receipt.get(key)!=item for key,item in stable.items()):
                raise ValueError('LOCAL immutable compatibility receipt changed')
            if not journal.get('imported')and receipt['originalStoppedMetadata']!=journal.get('stopped'):
                raise ValueError('LOCAL original STOP compatibility metadata changed')
            return
        if (journal.get('phase')not in ('RECOVERY_REQUIRED','STOPPED')or os.path.lexists(self.path(scope,'cipher'))
                or not isinstance(journal.get('stopped'),dict)or self.stopped_observation()!=journal['stopped']):
            raise ValueError('LOCAL compatibility needs its original stopped source and no ciphertext')
        export_fields=LOCAL_SOURCE_COMMON|{'publicKey','names','nvmeHosts','authReplayDomains'}
        if set(value)==export_fields:
            if (os.environ.get('ABLESTACK_STORAGE_WRITER_LOCK_FD')!='9'
                    or any(value[key]!=journal[key]for key in ('publicKey','names','nvmeHosts','authReplayDomains'))):
                raise ValueError('LOCAL compatibility retry key or collection changed')
            self.lock()
            self.write(scope,'code-compatibility',{**stable,'originalStoppedMetadata':journal['stopped']},immutable=True)
        elif set(value)!=LOCAL_SOURCE_COMMON:
            raise ValueError('LOCAL compatibility receipt is required for restoration')
        # A first status observes eligibility only; no mutable authority is minted.
        return

    def frozen(self,value,journal):
        if (journal.get("scope")!={key:value[key]for key in LOCAL_SOURCE_SCOPE}or journal.get("common")!=self.common(value)):
            raise ValueError("LOCAL source journal/command authority changed")
        if journal.get('cliSha256')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():
            self.code_compatibility(value,journal)
        return journal

    def stop(self,value,journal,rollback=False):
        scope=journal["scope"]
        retry=not rollback and journal["phase"]in("QUIESCING","RECOVERY_REQUIRED")
        if retry:
            # A pinned/paused acceptor cannot answer a readiness probe. Preserve
            # the original intent and complete only its exact still-owned PIDs.
            # This path never uses failed TCP readiness as new stop authority.
            before=dict(journal["prior"]);fresh={row["unit"]:row for row in self.unit_authority.units(require_master=False)}
            remaining=[]
            for row in before["units"]:
                if row["unit"]in("smbd.service","nmbd.service"):
                    observed=fresh.get(row["unit"])
                    if observed is None:continue
                    if any(observed.get(key)!=row.get(key)for key in("pid","startTicks","executable","vendorUnit","argv","cgroup","configurationPath")):
                        raise ValueError("LOCAL interrupted stop has a replaced master")
                else:
                    pid=int(self.handler.run(["systemctl","show",row["unit"],"--property=MainPID","--value"]).strip())
                    if pid==0:continue
                    if self.launcher_authority(row['unit'],row)!=row.get('managedLauncher'):
                        raise ValueError("LOCAL interrupted managed stop has a replaced launcher")
                remaining.append(row)
            if (self.handler.database_identity()!=before["databases"]
                    or hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()!=before["configurationSha256"]
                    or self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True):
                raise ValueError("LOCAL interrupted stop source/sessions changed")
            before["units"]=remaining
        else:
            before=self.observe(value)
            if not rollback and journal["prior"]!=before:raise ValueError("LOCAL source prior ownership changed")
        pinned=before['units']+[row['managedLauncher']for row in before['units']if row.get('managedLauncher')]
        handles=self.handler.open_master_handles(pinned)
        try:
            fresh={row["unit"]:row for row in self.unit_authority.units(require_master=False)}
            for row in before["units"]:
                if row["unit"]in ("smbd.service","nmbd.service"):
                    observed=fresh.get(row["unit"])
                    if not observed or any(observed.get(key)!=row.get(key)for key in
                            ("pid","startTicks","executable","vendorUnit","argv","cgroup","configurationPath")):
                        raise ValueError("LOCAL exact default master changed after pidfd pin")
                else:
                    proc=self.handler.process_root/str(row["pid"])
                    if (proc/"stat").read_text().rpartition(")")[2].split()[19]!=row["startTicks"]:
                        raise ValueError("LOCAL managed master changed after pidfd pin")
                    if self.launcher_authority(row['unit'],row)!=row.get('managedLauncher'):
                        raise ValueError('LOCAL managed launcher changed after pidfd pin')
            journal["phase"]="QUIESCING";self.write(scope,"journal",journal)
            for row in before["units"]:
                if row['unit']not in ('smbd.service','nmbd.service')and self.launcher_authority(row['unit'],row)!=row.get('managedLauncher'):
                    raise ValueError('LOCAL managed launcher changed before signalling')
                signal.pidfd_send_signal(handles[row["pid"]],signal.SIGSTOP,None,0)
            # Do not call inspect here: its TCP readiness probe would create a
            # connection after SIGSTOP. Recheck source descriptors and sessions
            # directly while the exact acceptors cannot accept application I/O.
            if (self.handler.database_identity()!=before["databases"]
                    or hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()!=before["configurationSha256"]
                    or self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True):
                raise ValueError("LOCAL source changed after exact PID pin")
            for row in before["units"]:
                signal.pidfd_send_signal(handles[row["pid"]],signal.SIGTERM,None,0)
                signal.pidfd_send_signal(handles[row["pid"]],signal.SIGCONT,None,0)
            for unit in sorted({row["unit"]for row in before["units"]}):self.handler.run(["systemctl","stop",unit])
            after=self.observe(value,stopped=True);journal["phase"]="STOPPED";journal["stopped"]=after
            self.write(scope,"journal",journal);return after
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";self.write(scope,"journal",journal);raise
        finally:
            for fd in handles.values():os.close(fd)

    def resume(self,value,journal,record):
        scope=journal["scope"];self.state(value)
        active=set()
        if journal["phase"] in ("EXPORTED","STOPPED"):
            self.observe(value,stopped=True);self.match_private(record)
        elif journal["phase"] in ("RESUMING","RECOVERY_REQUIRED"):
            current=self.observe(value,allow_missing=True,require_empty=False)
            if not {row["unit"]for row in current["units"]}<={row["unit"]for row in journal["prior"]["units"]}:
                raise ValueError("LOCAL partial resume has a foreign unit")
            active={row["unit"]for row in current["units"]}
            if active!={row["unit"]for row in journal["prior"]["units"]}and current["sessionsVerifiedEmpty"]is not True:
                raise ValueError("LOCAL partial source resume requires empty sessions")
        else:raise ValueError("LOCAL source has no resume intent")
        if hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()!=record["sourceConfigurationFileSha256"]:
            raise ValueError("LOCAL resume SOURCE configuration changed")
        journal["phase"]="RESUMING";self.write(scope,"journal",journal)
        for row in journal["prior"]["units"]:
            if row["unit"]in active:continue
            with self.start_grant(scope,row["unit"]):self.handler.run(["systemctl","start",row["unit"]])
        deadline=time.monotonic()+self.handler.remaining(30)
        while True:
            actual=self.observe(value,allow_missing=True,require_empty=False)
            if ({row["unit"]for row in actual["units"]}=={row["unit"]for row in journal["prior"]["units"]}
                    and actual["databases"]==record["stoppedDatabases"]
                    and actual["configurationSha256"]==record["sourceConfigurationFileSha256"]
                    and (not any(row["unit"]!="nmbd.service"for row in journal["prior"]["units"])
                         or all(row.get("tcpReady")for row in actual["ownedEndpoints"]))):break
            if time.monotonic()>=deadline:raise ValueError("LOCAL source owned resume did not verify")
            time.sleep(.1)
        journal["phase"]="RESUMED";self.write(scope,"journal",journal);return actual

    def match_private(self,record):
        self.stopped_observation()
        for path,checksum in record["privateFingerprints"].items():
            if hashlib.sha256(self.reader(path)[0]).hexdigest()!=checksum:
                raise ValueError("LOCAL stopped opaque source bytes changed")

    def reference(self,record):
        return {"localCheckpointUuid":record["scope"]["localCheckpointUuid"],"sha256":service_cipher_digest(record)}

    def receipt(self,record):
        return {"kind":LOCAL_SOURCE_KIND,"scope":record["scope"],"capsuleSha256":record["capsule"]["sha256"],
            "sourceConfigurationSha256":record["common"]["sourceConfigurationSha256"],"checkpointRecordSha256":service_cipher_digest(record)}

    def result(self,record,journal,imported=False):
        return {"success":True,"scope":record["scope"],"localSourceIdentityCheckpoint":self.receipt(record),
            "localSourceCheckpointReference":self.reference(record),"sourceSmbResumed":True,"sourceRuntimeVerified":True,
            "sourceIdentityCheckpointCaptured":True,"sourceIdentityRestored":imported,"canonicalDesiredStateChanged":False,
            "priorActiveUnitCount":len(journal["prior"]["units"]),**({"capsule":record["capsule"]}if not imported else {})}

    def validate_record(self,value,record):
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        if (not isinstance(record,dict)or record.get("schemaVersion")!=1 or record.get("kind")!=LOCAL_SOURCE_KIND
                or record.get("scope")!=scope or record.get("common")!=self.common(value)):
            raise ValueError("LOCAL source cached checkpoint role changed")
        capsule=record["capsule"]
        if (set(capsule)!={"schemaVersion","scope","wrappedKey","nonce","ciphertext","sha256"}
                or type(capsule["schemaVersion"])is not int or capsule["schemaVersion"]!=1
                or capsule["scope"]!=value["instanceUuid"]+":"+value["operationUuid"]
                or hashlib.sha256(__import__("base64").b64decode(capsule["ciphertext"],validate=True)).hexdigest()!=capsule["sha256"]):
            raise ValueError("LOCAL source cached cipher is malformed")
        return record

    def export(self,value):
        scope=self.request(value,"export-local-source");source=self.state(value)
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        key=serialization.load_pem_public_key(value["publicKey"].encode())
        if not isinstance(key,rsa.RSAPublicKey)or key.key_size<2048:raise ValueError("LOCAL source wrapping key is unsupported")
        names=value["names"];hosts=value["nvmeHosts"]
        if (not isinstance(names,list)or len(names)>1024 or len(set(names))!=len(names)
                or any(not isinstance(n,str)or n in {"root","nobody","daemon","cloud","debian"}
                       or not re.fullmatch("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}",n)for n in names)
                or not isinstance(hosts,list)or len(hosts)>1024 or any(not isinstance(h,str)for h in hosts)):
            raise ValueError("LOCAL source collection identifiers are invalid")
        from identity_capsule import validate_host_nqn
        for host in hosts:validate_host_nqn(host)
        journal=self.read(self.path(scope,"journal"));record=self.read(self.path(scope,"cipher"))
        if journal is None:
            journal={"schemaVersion":1,"scope":scope,"common":self.common(value),"publicKey":value["publicKey"],
                "names":names,"nvmeHosts":hosts,"authReplayDomains":value["authReplayDomains"],
                "sourceDesiredState":source["configurationDesiredState"],"prior":self.observe(value),"phase":"INTENT_DURABLE",
                "cliSha256":hashlib.sha256(self.cli.read_bytes()).hexdigest()}
            self.write(scope,"journal",journal)
        self.frozen(value,journal)
        if any(journal[key]!=value[key]for key in ("publicKey","names","nvmeHosts","authReplayDomains")):
            raise ValueError("LOCAL source retry key/collection changed")
        try:
            if record is not None:
                self.validate_record(value,record)
                if record["publicKey"]!=value["publicKey"]:raise ValueError("LOCAL source cached wrapping key changed")
                if journal["phase"]=="RESUMED":
                    actual=self.observe(value,require_empty=False)
                    if ({row["unit"]for row in actual["units"]}!={row["unit"]for row in journal["prior"]["units"]}
                            or actual["configurationSha256"]!=record["sourceConfigurationFileSha256"]
                            or actual["databases"]!=journal.get("importedStoppedDatabases",record["stoppedDatabases"])
                            or not all(row.get("tcpReady")for row in actual["ownedEndpoints"])):
                        raise ValueError("LOCAL source resumed cache ownership changed")
                    return self.result(record,journal)
                self.resume(value,journal,record);return self.result(record,journal)
            if journal["phase"]not in("STOPPED","EXPORTED"):
                if journal["phase"]in("QUIESCING","RECOVERY_REQUIRED"):
                    try:stopped=self.stopped_observation()
                    except ValueError:stopped=None
                    if stopped is not None:
                        if (stopped["databases"]!=journal["prior"]["databases"]
                                or stopped["configurationSha256"]!=journal["prior"]["configurationSha256"]):
                            raise ValueError("LOCAL interrupted stop source changed")
                        journal["phase"]="STOPPED";journal["stopped"]=stopped;self.write(scope,"journal",journal)
                    else:self.stop(value,journal)
                else:self.stop(value,journal)
            stopped=self.stopped_observation()
            records=self.posix_collector(journal["sourceDesiredState"]["posix-directory-policies.json"],value["instanceUuid"])
            payload=self.collector(names,hosts,posix_policies=records)
            payload["sourceConfigurationSha256"]=value["sourceConfigurationSha256"];self.validator(payload)
            if payload.get("adIdentity")is not None:raise ValueError("LOCAL opaque capture cannot include AD")
            wrapper={"schemaVersion":1,"kind":LOCAL_SOURCE_KIND,"scope":scope,"sourceGeneration":value["sourceGeneration"],
                "sourceConfigurationSha256":value["sourceConfigurationSha256"],"bootId":value["expectedBootId"],"identity":payload}
            capsule=self.encryptor(wrapper,value["publicKey"],scope["instanceUuid"]+":"+scope["operationUuid"])
            if self.stopped_observation()!=stopped:raise ValueError("LOCAL source changed during opaque collection")
            record={"schemaVersion":1,"kind":LOCAL_SOURCE_KIND,"scope":scope,"common":self.common(value),"publicKey":value["publicKey"],
                "capsule":capsule,"stoppedDatabases":stopped["databases"],"sourceConfigurationFileSha256":stopped["configurationSha256"],
                "authReplayDomains":value["authReplayDomains"],
                "sourceDesiredState":journal["sourceDesiredState"],
                "privateFingerprints":{path:row["sha256"]for path,row in payload["files"].items()if row.get("absent")is not True}}
            self.match_private(record);self.write(scope,"cipher",record,immutable=True)
            journal["phase"]="EXPORTED";self.write(scope,"journal",journal)
            self.resume(value,journal,record);return self.result(record,journal)
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";self.write(scope,"journal",journal);raise

    def decode(self,value,record):
        self.validate_record(value,record)
        if value["localSourceCheckpointReference"]!=self.reference(record)or value["capsule"]!=record["capsule"]:
            raise ValueError("LOCAL original cipher/reference changed")
        from cryptography.hazmat.primitives import serialization
        key=serialization.load_pem_private_key(value["credentialPrivateKey"].encode(),password=None)
        public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        if public!=record["publicKey"]:raise ValueError("LOCAL original unwrap key differs")
        wrapper=self.decryptor(value["capsule"],value["credentialPrivateKey"],value["instanceUuid"]+":"+value["operationUuid"])
        required={"schemaVersion","kind","scope","sourceGeneration","sourceConfigurationSha256","bootId","identity"}
        if (not isinstance(wrapper,dict)or set(wrapper)!=required or type(wrapper["schemaVersion"])is not int or wrapper["schemaVersion"]!=1
                or wrapper["kind"]!=LOCAL_SOURCE_KIND or wrapper["scope"]!=record["scope"]
                or wrapper["sourceGeneration"]!=value["sourceGeneration"]or wrapper["sourceConfigurationSha256"]!=value["sourceConfigurationSha256"]
                or wrapper["bootId"]!=value["expectedBootId"]):raise ValueError("LOCAL authenticated wrapper is foreign")
        self.validator(wrapper["identity"]);return wrapper["identity"]

    def import_source(self,value):
        scope=self.request(value,"import-local-source");self.state(value)
        journal=self.frozen(value,self.read(self.path(scope,"journal"))or {})
        record=self.read(self.path(scope,"cipher"))
        if not record:raise ValueError("LOCAL original checkpoint is absent")
        payload=self.decode(value,record)
        for path in IDENTITY_DATABASES.values():
            if payload["files"].get(path)=={"absent":True}and os.path.lexists(path):
                raise ValueError("LOCAL refuses to erase an unproven new SAM/database")
        try:
            if journal.get("imported")is True and journal["phase"]=="RESUMED":
                actual=self.observe(value,require_empty=False)
                if (actual["configurationSha256"]!=record["sourceConfigurationFileSha256"]
                        or actual["databases"]!=journal.get("importedStoppedDatabases")
                        or {row["unit"]for row in actual["units"]}!={row["unit"]for row in journal["prior"]["units"]}):
                    raise ValueError("LOCAL imported resume changed")
                return self.result(record,journal,imported=True)
            if journal.get("imported")is not True:
                if journal["phase"]!="STOPPED":self.stop(value,journal,rollback=True)
                self.stopped_observation()
                file_payload=dict(payload);file_payload.pop("posixPolicies",None);file_payload.pop("sourceConfigurationSha256",None)
                self.importer(file_payload)
                selected=select_restore_domains(file_payload,["SMB"])
                restored={"privateFingerprints":{path:row["sha256"]for path,row in selected["files"].items()if row.get("absent")is not True}}
                self.match_private(restored)
                journal["imported"]=True;journal["phase"]="STOPPED"
                journal["importedStoppedDatabases"]=self.stopped_observation()["databases"]
                journal["importedFingerprints"]=restored["privateFingerprints"]
                self.write(scope,"journal",journal)
            if not isinstance(journal.get("importedStoppedDatabases"),dict)or not isinstance(journal.get("importedFingerprints"),dict):
                raise ValueError("LOCAL restored descriptor authority is absent")
            resume_record={**record,"stoppedDatabases":journal["importedStoppedDatabases"],"privateFingerprints":journal["importedFingerprints"]}
            self.resume(value,journal,resume_record);return self.result(record,journal,imported=True)
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";self.write(scope,"journal",journal);raise

    def status(self,value):
        scope=self.request(value,"local-source-status");actual=self.state(value,source_only=False,terminal=True)
        journal=self.read(self.path(scope,"journal"))
        result={"success":True,"checkpointSupported":True,"scope":scope,"sourceGeneration":value["sourceGeneration"],
            "sourceConfigurationSha256":value["sourceConfigurationSha256"],"bootId":self.boot(),
            "journalPresent":journal is not None,"canonicalDesiredStateChanged":False}
        if journal is None:return result
        self.frozen(value,journal);record=self.read(self.path(scope,"cipher"))
        if record is not None:self.validate_record(value,record)
        view=self.observe(value,stopped=journal["phase"]in("STOPPED","EXPORTED"),allow_missing=True)
        verified=(actual["configurationSha256"]==value["sourceConfigurationSha256"]and journal["phase"]=="RESUMED"and record is not None
            and view["configurationSha256"]==record["sourceConfigurationFileSha256"]
            and {row["unit"]for row in view["units"]}=={row["unit"]for row in journal["prior"]["units"]})
        return {**result,"phase":journal["phase"],"sourceSmbResumed":verified,"sourceRuntimeVerified":verified,
            "sourceIdentityRestoreSupported":record is not None,"currentRuntimeOwnershipVerified":True,
            "currentSessionsVerifiedEmpty":view["sessionsVerifiedEmpty"],"currentConfigurationSha256":actual["configurationSha256"],
            "currentSmbConfigurationSha256":view["configurationSha256"],**({"localSourceCheckpointReference":self.reference(record)}if record is not None else {})}

    def replay_auth(self,value):
        scope=self.request(value,"replay-local-source-auth");self.state(value)
        journal=self.frozen(value,self.read(self.path(scope,"journal"))or {})
        record=self.read(self.path(scope,"cipher"))
        if not record or journal.get("imported")is not True or journal.get("phase")!="RESUMED":
            raise ValueError("LOCAL source auth replay lacks verified original SMB restore")
        payload=self.decode(value,record);domains=value["restoreDomains"]
        permitted=record["authReplayDomains"]
        if permitted!=journal["authReplayDomains"]:
            raise ValueError("LOCAL source original auth intent changed")
        if "renderedManifestSha256"in value:
            activation=self.command(("operation","generation","render-status"))["activation"]
            changed=activation.get("changedDomains");started=activation.get("startedDomains")
            if (not isinstance(changed,list)or not isinstance(started,list)or len(set(started))!=len(started)
                    or not set(started)<=set(changed)):
                raise ValueError("LOCAL rendered started-domain authority is malformed")
            exact=[name for name in("ISCSI","NVMEOF")if name in started]
            if domains!=exact or not set(domains)<=set(permitted):
                raise ValueError("LOCAL rendered auth replay is outside its protected actual started domains")
        elif domains!=permitted:
            raise ValueError("LOCAL source auth replay domains differ from the original intent")
        desired=value.get("nvmeDesired")
        expected=record["sourceDesiredState"].get("nvmeof-subsystems.json")
        if "NVMEOF"in domains and desired!=expected:
            raise ValueError("LOCAL source NVMe public binding differs from frozen SOURCE7")
        if "NVMEOF"in domains and desired is None:
            if payload.get("nvmeHosts"):
                raise ValueError("LOCAL source absent NVMe cannot carry protected host keys")
            for path in (Path("/sys/kernel/config/nvmet/subsystems"),Path("/sys/kernel/config/nvmet/ports")):
                if os.path.lexists(path)and(path.is_symlink()or any(path.iterdir())):
                    raise ValueError("LOCAL absent NVMe source has unproven runtime targets")
        selected=select_restore_domains(payload,domains)
        selected=dict(selected);selected.pop("posixPolicies",None);selected.pop("sourceConfigurationSha256",None)
        if "NVMEOF"in domains and desired is None:
            for path,item in selected["files"].items():
                if item.get("absent")is True and os.path.lexists(path):
                    raise ValueError("LOCAL absent auth source cannot erase unproven target credentials")
        restored=restore_protected(selected,desired if "NVMEOF"in domains else None)
        result={"success":True,"scope":scope,"sourceAuthReplayed":True,"replayedDomains":domains,
            "sourceConfigurationSha256":value["sourceConfigurationSha256"],"localSourceCheckpointReference":self.reference(record),
            "smbIdentityChanged":False,"canonicalDesiredStateChanged":False}
        if "NVMEOF"in domains:
            result["nvmeRestored"]=restored.get("nvmeRestored")is True or desired is None
        journal["authReplayedDomains"]=domains;self.write(scope,"journal",journal)
        return result

    @classmethod
    def for_renderer(cls,cli):
        # This reader never constructs the mutating producer or invokes SAM.
        value=cls.__new__(cls);value.cli=Path(cli);value.io=ServiceIdentityCipher()
        value.root=Path(os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR","/var/lib/ablestack-storage/config-generations")).parent/"smb-local-source-checkpoints"
        return value

    def rendered_checkpoint(self,request,source):
        ref=request.get("localSourceCheckpointReference")
        if not isinstance(ref,dict)or set(ref)!={"localCheckpointUuid","sha256"}:
            raise ValueError("LOCAL rendered source reference is not closed")
        scope={key:request[key]for key in ("instanceUuid","operationUuid","revision")}
        local_scope={**scope,"localCheckpointUuid":ref["localCheckpointUuid"]}
        record=self.read(self.path(local_scope,"cipher"));journal=self.read(self.path(local_scope,"journal"))
        if not record or not journal:raise ValueError("LOCAL rendered source checkpoint is absent")
        self.validate_record(record["common"],record);self.frozen(record["common"],journal)
        if (record["scope"]!=local_scope or ref!=self.reference(record)or journal.get("phase")!="RESUMED"
                or source.get("generation")!=record["common"]["sourceGeneration"]
                or source.get("configurationSha256")!=record["common"]["sourceConfigurationSha256"]
                or source.get("pendingOperationUuid")!=scope["operationUuid"]
                or request.get("checkpointPublicKey")!=record["publicKey"]):
            raise ValueError("LOCAL rendered source/key/scope differs from its original opaque checkpoint")
        return {"schemaVersion":1,"scope":scope,"sourceConfigurationSha256":record["common"]["sourceConfigurationSha256"],
            "publicKey":record["publicKey"],"capsule":record["capsule"]}

    def renderer_identity(self,saved,private_key,ref,decryptor,validator):
        local_scope={**saved["scope"],"localCheckpointUuid":ref["localCheckpointUuid"]}
        record=self.read(self.path(local_scope,"cipher"))
        if not record or saved!={"schemaVersion":1,"scope":saved["scope"],"sourceConfigurationSha256":record["common"]["sourceConfigurationSha256"],
                "publicKey":record["publicKey"],"capsule":record["capsule"]}:
            raise ValueError("LOCAL rendered immutable checkpoint changed")
        self.decryptor=decryptor;self.validator=validator
        return self.decode({**record["common"],"capsule":saved["capsule"],"credentialPrivateKey":private_key,
            "localSourceCheckpointReference":ref},record)
