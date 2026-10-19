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
from identity_capsule import collect,encrypt,decrypt,validate_payload,regular_file,restore,select_restore_domains,restore_protected,ACCOUNT_FILES,owned_account_records
from service_identity_cipher import ServiceIdentityCipher,service_cipher_digest
from posix_receipt_transfer import PosixReceiptTransfer
from smb_source_module_link import source_module_link_compatibility
LOCAL_SOURCE_SMB_CONFIG=Path("/etc/samba/smb.conf")
LOCAL_SOURCE_NVME_ROOT=Path("/sys/kernel/config/nvmet")
LOCAL_SOURCE_KIND="LOCAL_SOURCE_IDENTITY_CHECKPOINT"
LOCAL_SOURCE_SCOPE={"instanceUuid","operationUuid","revision","localCheckpointUuid"}
LOCAL_SOURCE_COMMON=LOCAL_SOURCE_SCOPE|{"sourceGeneration","sourceConfigurationSha256","expectedBootId"}

LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256={"stable_sessions_empty":"433c93bb827d2901761f69a02310e5337824934bdf1a6599d0188aef0ce3ac16","unconfigured_stop_bridge":"9cdbd9f92e6fdbe61b393e9ae0d72c42a720a4098fdc3d85961ec38dd90903ff","unconfigured_stop_bridge_cli":"981a9dcb110d7f8ccc627f6081578bd88c1277d05b202b924a2aa180fb2bbcaf","unconfigured_stop_empty_identity":"2e65a69c95cd6ebb04cdd7017a5caf70bad69383a967c1cb581afff774146dd2"}

LOCAL_SOURCE_MODULE_LINK_SHA256="1a17e9ce0338f4903df1461c01bd31e3b8a8f81331d983ac675c44b0fa2395e3"
LOCAL_SOURCE_FAILURE_STAGES=frozenset({
    "EXPORT_SOURCE_AUTHORITY",
    "EXPORT_SOURCE_OBSERVE",
    "EXPORT_RECORD_VALIDATE",
    "EXPORT_CACHE_OBSERVE",
    "EXPORT_RETRY_STOP_OBSERVE",
    "EXPORT_STOP",
    "EXPORT_STOPPED_OBSERVE",
    "EXPORT_POSIX_COLLECT",
    "EXPORT_IDENTITY_COLLECT",
    "EXPORT_IDENTITY_VALIDATE",
    "EXPORT_ENCRYPT",
    "EXPORT_STOPPED_RECHECK",
    "EXPORT_CHECKPOINT_PUBLISH",
    "STOP_OBSERVE",
    "STOP_PIN",
    "STOP_OWNER_VERIFY",
    "STOP_INTENT_PUBLISH",
    "STOP_SIGNAL",
    "STOP_STABLE_VERIFY",
    "STOP_TERMINATE",
    "STOP_UNITS",
    "STOP_VERIFY",
    "STOP_COMPLETE_PUBLISH",
    "RESUME_SOURCE_AUTHORITY",
    "RESUME_STOPPED_OBSERVE",
    "RESUME_PRIVATE_VERIFY",
    "RESUME_PARTIAL_OBSERVE",
    "RESUME_PARTIAL_AUTHORITY",
    "RESUME_CONFIG_VERIFY",
    "RESUME_INTENT_PUBLISH",
    "RESUME_START_GRANT",
    "RESUME_START_UNIT",
    "RESUME_START_GRANT_CLOSE",
    "RESUME_VERIFY_OBSERVE",
    "RESUME_VERIFY",
    "RESUME_COMPLETE_PUBLISH",
    "IMPORT_SOURCE_AUTHORITY",
    "IMPORT_CACHE_OBSERVE",
    "IMPORT_STOP",
    "IMPORT_STOPPED_OBSERVE",
    "IMPORT_IDENTITY_RESTORE",
    "IMPORT_PRIVATE_VERIFY",
    "IMPORT_CHECKPOINT_PUBLISH",
    "IMPORT_CONFIG_RESTORE",
})

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
        self._failure_stage=None
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
                    or state.get('transactionId')!=path.name or state.get('phase')not in ('VERIFIED','COMPLETE','ROLLED_BACK')
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
        eligible=[row for row in matches if row['phase']in ('VERIFIED','COMPLETE')]
        if not eligible:raise ValueError('LOCAL historical verified installation is absent')
        return eligible[0]

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
    def record_failure(self,scope,journal,error):
        # Journal only a closed internal stage and standard exception class.
        # Never stringify the exception: messages/args can contain credentials.
        classes={ValueError:"ValueError",KeyError:"KeyError",TypeError:"TypeError",
            IndexError:"IndexError",StopIteration:"StopIteration",RuntimeError:"RuntimeError",
            FileNotFoundError:"FileNotFoundError",ProcessLookupError:"ProcessLookupError",
            PermissionError:"PermissionError",TimeoutError:"TimeoutError",OSError:"OSError",
            subprocess.TimeoutExpired:"TimeoutExpired"}
        stage=getattr(self,"_failure_stage",None)
        journal["lastFailure"]={"failureStage":stage if stage in LOCAL_SOURCE_FAILURE_STAGES else "UNKNOWN",
            "exceptionClass":classes.get(type(error),"OtherException")}
        journal["phase"]="RECOVERY_REQUIRED";self.write(scope,"journal",journal)

    def pending_start_grant(self,scope,unit):
        owner=self
        class NativeStatus:
            def __init__(self,args):self.args=args
            def status(self):return owner.command(self.args)
        authorization=PendingNfsAuthorization(generation=PendingNfsGeneration(),protocol="SMB",
            rendered=NativeStatus(("operation","generation","render-status")),
            maintenance=NativeStatus(("operation","maintenance","status")))
        journal=self.read(self.path(scope,"journal"))
        if journal and journal.get("imported")is True:
            return authorization.local_source_grant({k:scope[k]for k in ("instanceUuid","operationUuid","revision")},unit)
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
    def same_service_writer(self,value,maintenance,rendered,observation=False):
        # SERVICE owns availability; the existing opaque LOCAL record continues
        # to own identity. Neither may replace the other's protected SOURCE.
        from template_maintenance import Maintenance
        from service_maintenance import ServiceMaintenance
        from rendered_generation import RenderedGeneration
        scope={key:value[key]for key in("instanceUuid","operationUuid","revision")}
        expected={**scope,"maintenanceUuid":scope["operationUuid"]}
        if (maintenance.get("maintenanceKind")!="SERVICE"or maintenance.get("bootHeld")is not True
                or maintenance.get("scope")!=expected or not value["sourceGeneration"]):return False
        read_only=observation and set(value)==LOCAL_SOURCE_COMMON
        if not read_only and not re.fullmatch("[0-9a-f]{64}",str(value.get("renderedManifestSha256"))):return False
        keeper=Maintenance()
        service=ServiceMaintenance(keeper,self.cli,command=self.command)
        actual=service.status(expected);checkpoint=self.read(service.journal)
        if (actual.get("maintenanceKind")!="SERVICE"or actual.get("bootHeld")is not True
                or actual.get("scope")!=expected or actual.get("phase")not in("HELD","RECOVERY_REQUIRED")
                or not checkpoint or checkpoint.get("scope")!=expected or checkpoint.get("phase")!=actual["phase"]
                or checkpoint.get("sourceGeneration")!=value["sourceGeneration"]):
            raise ValueError("LOCAL same-writer SERVICE source journal changed")
        source=checkpoint.get("sourceRendered");source_scope={key:value["sourceGeneration"][key]for key in scope}
        if (not isinstance(source,dict)or source.get("scope")!=source_scope
                or source.get("configurationSha256")!=value["sourceConfigurationSha256"]
                or not re.fullmatch("[0-9a-f]{64}",str(source.get("manifestSha256")))):
            raise ValueError("LOCAL same-writer SERVICE rendered source changed")
        store=RenderedGeneration(Path(os.environ.get("ABLESTACK_STORAGE_RENDERED_GENERATIONS",
            self.handler.generations.parent/"rendered-generations")))
        observed=store.status()
        if (store.inspect(store.generations/source_scope["operationUuid"])!=source
                or any(rendered.get(key)!=item for key,item in observed.items())):
            raise ValueError("LOCAL same-writer SERVICE immutable source or actual pointer changed")
        local_scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        journal=self.read(self.path(local_scope,"journal"));record=self.read(self.path(local_scope,"cipher"))
        if (not journal or not record or journal.get("scope")!=local_scope or record.get("scope")!=local_scope
                or journal.get("common")!=self.common(value)or record.get("common")!=self.common(value)
                or journal.get("sourceDesiredState")!=record.get("sourceDesiredState")):
            raise ValueError("LOCAL same-writer SERVICE lacks its original opaque checkpoint")
        self.validate_record(value,record)
        activation=rendered.get("activation")or {};current=rendered.get("current")
        if read_only:
            if current==source and activation==checkpoint.get("sourceActivation")and rendered.get("bootHeld")is False:return True
        target=store.inspect(store.generations/scope["operationUuid"])
        owned=(activation.get("scope")==scope and activation.get("previousOperationUuid")==source_scope["operationUuid"]
            and activation.get("previousSha256")==source["manifestSha256"]and target is not None
            and target.get("scope")==scope and target.get("previousRenderedSha256")==source["manifestSha256"]
            and activation.get("targetSha256")==target["manifestSha256"])
        if not owned:raise ValueError("LOCAL same-writer SERVICE activation is not linked to its original source")
        if read_only:
            return (activation.get("phase")in("ACTIVATING","VERIFIED","RECOVERY_REQUIRED","ROLLING_BACK","ROLLED_BACK")
                and current in(source,target))
        if (activation.get("phase")!="ROLLING_BACK"or current!=source
                or value.get("renderedManifestSha256")!=target["manifestSha256"]):
            raise ValueError("LOCAL same-writer SERVICE import lacks its owned inverse pointer")
        action="import-local-source"if value.get("restoreDomains")==["SMB"]else"replay-local-source-auth"
        self.request(value,action)
        if value.get("localSourceCheckpointReference")!=self.reference(record):
            raise ValueError("LOCAL same-writer SERVICE import changed its original checkpoint reference")
        return True

    def state(self,value,source_only=True,terminal=False,recovery=False):
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        pending=self.read(self.handler.generations/"pending.json");current=self.read(self.handler.generations/"current.json")or {}
        expected_phases=("PREPARED","VERIFIED")if recovery else("PREPARED",)
        if pending is None and terminal:
            completed=self.read(self.handler.generations/(scope["operationUuid"]+".json"))
            journal=self.read(self.path(scope,"journal"))
            if (not completed or completed.get("phase")!="ROLLED_BACK"or not journal or journal.get("phase")!="RESUMED"
                    or completed.get("previous")!=value["sourceGeneration"]):
                raise ValueError("LOCAL terminal observation lacks its rolled-back original writer")
            pending=completed;expected_phases=("ROLLED_BACK",)
        if not isinstance(pending,dict):raise ValueError("LOCAL source original pending writer is absent")
        if (pending.get("phase")not in expected_phases or any(pending.get(k)!=scope[k]for k in ("instanceUuid","operationUuid","revision"))
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
        service_held=(maintenance.get("bootHeld")is True and self.same_service_writer(value,maintenance,rendered,observation=terminal))
        if (status.get("generation")not in (current,None)or status.get("bootId")!=value["expectedBootId"]
                or not(maintenance.get("bootHeld")is False or service_held)
                or not(rendered.get("bootHeld")is False or held_local or service_held)):
            raise ValueError("LOCAL source cannot borrow ROOT/SERVICE/rendered hold")
        if source_only and status.get("configurationSha256")!=value["sourceConfigurationSha256"]:
            raise ValueError("LOCAL source canonical seven changed")
        active=rendered.get("current")
        if active is not None and not service_held and(not current or active.get("scope")!={k:current[k]for k in ("instanceUuid","operationUuid","revision")}
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
                    except (FileNotFoundError,ProcessLookupError):continue
            except (FileNotFoundError,ProcessLookupError):continue
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
        exact_pids={row["pid"]for row in units}|{row["managedLauncher"]["pid"]for row in units if row.get("managedLauncher")}
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
                    except ProcessLookupError:
                        if int(process.name)in exact_pids:raise
                        continue
            except (FileNotFoundError,ProcessLookupError):
                if int(process.name)in exact_pids:raise
                continue
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

        KNOWN_STATUS_PROOF = "if (record is None and journal.get('phase')in ('STOPPED','RECOVERY_REQUIRED')\n                and isinstance(journal.get('stopped'),dict)and not os.path.lexists(self.path(scope,'cipher'))):\n            self.state(value,source_only=True,recovery=True)\n            stopped=self.stopped_observation()\n            if (stopped!=journal['stopped']or stopped['configurationSha256']!=journal['prior']['configurationSha256']):\n                raise ValueError('LOCAL runtime quarantine original STOP changed')\n            current_sha=hashlib.sha256(self.cli.read_bytes()).hexdigest()\n            current_entry=self.cli.resolve(strict=True)\n            if current_entry!=(self.runtime_root/'current'/'ablestack-storagectl').resolve(strict=True):\n                raise ValueError('LOCAL runtime quarantine current ENTRY changed')\n            self.compat_entry(current_entry,current_sha)\n            result['localSourceStoppedRuntimeProof']={'kind':'LOCAL_SOURCE_STOPPED_RUNTIME_QUARANTINE','scope':scope,\n                'sourceGeneration':value['sourceGeneration'],'sourceConfigurationSha256':value['sourceConfigurationSha256'],\n                'bootId':self.boot(),'originalCliSha256':journal['cliSha256'],'currentCliSha256':current_sha,\n                'sourceStoppedVerified':True,'stoppedMetadataVerified':True,'privateHoldersAbsent':True,'cipherAbsent':True,\n                'sourceCanonicalUnchanged':True,'originalSignedCodeVerified':True,'currentSignedCodeVerified':True,\n                'serviceAvailabilityVerified':False}"

        def status_proof_span(method, old):
            expected = copy.deepcopy(old)
            anchors = [i for i, node in enumerate(old.body) if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == "view" for target in node.targets)
                and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute)
                and isinstance(node.value.func.value, ast.Name) and node.value.func.value.id == "self"
                and node.value.func.attr == "observe"]
            require(len(anchors) == 1, "historical status proof anchor differs")
            index = anchors[0]
            expected.body.insert(index, ast.parse(KNOWN_STATUS_PROOF).body[0])
            require(ast_same(method, expected), "unknown stopped runtime status addition")
            return method.body[index]

        def expected_installation_authority(old):
            result = copy.deepcopy(old)
            phase = ast.parse("state.get('phase')not in ('VERIFIED','COMPLETE')", mode="eval").body
            guards = [node for node in ast.walk(result) if ast_same(node, phase)]
            require(len(guards) == 1 and ast_same(result.body[-1], ast.parse("return matches[0]").body[0]),
                    "historical installation phase/return differs")
            guards[0].comparators[0] = ast.parse("('VERIFIED','COMPLETE','ROLLED_BACK')", mode="eval").body
            result.body[-1:] = ast.parse("eligible=[row for row in matches if row['phase']in ('VERIFIED','COMPLETE')]\n"
                "if not eligible:raise ValueError('LOCAL historical verified installation is absent')\nreturn eligible[0]").body
            return result

        def normalize_block(current, original, helpers):
            _, methods, lines = parsed_class(current)
            _, old, old_lines = parsed_class(original)
            require(set(methods) == set(old) | helpers and not (set(old) & helpers), "unknown or missing LOCAL method")
            require(ast_same(methods["frozen"], expected_frozen(old["frozen"])), "unknown frozen body/signature")
            require(ast_same(methods["installed_release_authority"], expected_installation_authority(old["installed_release_authority"])),
                    "unknown historical installation authority change")
            current_collector = collector_assignment(methods["__init__"])
            old_collector = collector_assignment(old["__init__"])
            expected_old = ast.parse("self.collector=lambda *args:collect(*args)").body[0]
            expected_new = ast.parse("self.collector=lambda *args,**kwargs:collect(*args,**kwargs)").body[0]
            require(ast_same(old_collector, expected_old) and ast_same(current_collector, expected_new), "unknown collector forwarding")
            edits = []
            proof_node = status_proof_span(methods["status"], old["status"])
            proof_start = sum(map(len, lines[:proof_node.lineno - 1]))
            proof_end = sum(map(len, lines[:proof_node.end_lineno]))
            edits.append((proof_start, proof_end, b""))
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
            first, last = span(lines, methods["installed_release_authority"])
            a, b = span(old_lines, old["installed_release_authority"])
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

    def unconfigured_stop_bridge_cli(self,current,original,initial=False):
        """Apply one closed source transformation; all other CLI bytes stay exact."""
        import ast
        names={"stable_sessions_empty","unconfigured_stop_bridge_cli","unconfigured_stop_empty_identity","unconfigured_stop_bridge"}
        pins=LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256
        if set(pins)!=names:raise ValueError("LOCAL unconfigured bridge source pins differ")
        def section(raw,label):
            begin=("<<'"+label+"'\n").encode();end=("\n"+label+"\n").encode()
            if raw.count(begin)!=1 or raw.count(end)!=1:raise ValueError("LOCAL unconfigured bridge section differs")
            start=raw.index(begin)+len(begin);finish=raw.index(end,start)
            return start,finish,raw[start:finish]
        def methods(raw):
            tree=ast.parse(raw.decode());classes=[node for node in ast.walk(tree)if isinstance(node,ast.ClassDef)and node.name=="SmbSourceCheckpoint"]
            if len(classes)!=1 or classes[0]not in tree.body:raise ValueError("LOCAL unconfigured bridge class differs")
            rows=[node for node in classes[0].body if isinstance(node,ast.FunctionDef)]
            if len({row.name for row in rows})!=len(rows):raise ValueError("LOCAL unconfigured bridge method is duplicated")
            lines=raw.splitlines(keepends=True)
            return {row.name:b"".join(lines[row.lineno-1:row.end_lineno])for row in rows}
        if initial and hashlib.sha256(original).hexdigest()!="475dc43cadc01191da5904e9fed15f67144d7423fdc21e9494d66d093b58ba6c":
            raise ValueError("LOCAL initial bridge original ENTRY differs")
        edits=[]
        for label in("PYIDENTITY","PYRENDEREDGENERATION"):
            start,finish,old=section(original,label);_,_,new=section(current,label)
            before=methods(old);after=methods(new)
            if initial:
                changed={"stable_sessions_empty","unconfigured_stop_bridge","unconfigured_stop_bridge_cli"}
                if set(after)!=set(before)or not changed<=set(before):
                    raise ValueError("LOCAL initial bridge method set differs")
                for name in changed:
                    if hashlib.sha256(after[name]).hexdigest()!=pins[name]:
                        raise ValueError("LOCAL initial bridge method bytes differ")
                expected=old
                for name in sorted(changed):
                    if expected.count(before[name])!=1:raise ValueError("LOCAL initial bridge source span differs")
                    expected=expected.replace(before[name],after[name],1)
                prefix=b"LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256="
                old_pins=[line for line in old.splitlines(keepends=True)if line.startswith(prefix)]
                new_pins=[line for line in new.splitlines(keepends=True)if line.startswith(prefix)]
                declaration=prefix+json.dumps(pins,sort_keys=True,separators=(",",":")).encode()+b"\n"
                if len(old_pins)!=1 or new_pins!=[declaration]:
                    raise ValueError("LOCAL initial bridge source pin declaration differs")
                expected=expected.replace(old_pins[0],declaration,1)
                if expected!=new:raise ValueError("LOCAL initial bridge changed unreviewed inline bytes")
                edits.append((start,finish,expected))
                continue
            if set(after)!=set(before)|names or set(before)&names:raise ValueError("LOCAL unconfigured bridge method set differs")
            for name in names:
                if hashlib.sha256(after[name]).hexdigest()!=pins[name]:raise ValueError("LOCAL unconfigured bridge method bytes differ")
            expected=old
            replacements={
                "frozen":before["frozen"].replace(b"            self.code_compatibility(value,journal)\n",
                    b"            if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)\n"),
                "stop":before["stop"].replace(b'self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True',
                    b"not self.stable_sessions_empty(value,before)"),
                "import_source":before["import_source"].replace(b"        payload=self.decode(value,record)\n",
                    b"        payload=self.decode(value,record)\n        if journal.get('cliSha256')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():\n            self.unconfigured_stop_bridge(value,journal,payload)\n")}
            if (before["frozen"].count(b"            self.code_compatibility(value,journal)\n")!=1
                    or before["stop"].count(b'self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True')!=2
                    or before["import_source"].count(b"        payload=self.decode(value,record)\n")!=1):
                raise ValueError("LOCAL unconfigured bridge historical anchors differ")
            for name,replacement in replacements.items():
                if after[name]!=replacement:raise ValueError("LOCAL unconfigured bridge changed an unknown statement")
                expected=expected.replace(before[name],replacement,1)
            anchor=b"    def frozen(self,value,journal):\n"
            added=b"\n".join(after[name]for name in("unconfigured_stop_bridge_cli","unconfigured_stop_empty_identity","unconfigured_stop_bridge"))+b"\n"
            expected=expected.replace(anchor,added+anchor,1)
            anchor=b"    def stop(self,value,journal,rollback=False):\n"
            expected=expected.replace(anchor,after["stable_sessions_empty"]+b"\n"+anchor,1)
            marker=b"LOCAL_SOURCE_FAILURE_STAGES=frozenset({\n"
            declaration=("LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256="+json.dumps(pins,sort_keys=True,separators=(",",":"))+"\n\n").encode()
            if expected.count(marker)!=1 or b"LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256" in old:
                raise ValueError("LOCAL unconfigured bridge source marker differs")
            expected=expected.replace(marker,declaration+marker,1)
            if expected!=new:raise ValueError("LOCAL unconfigured bridge changed unreviewed inline bytes")
            edits.append((start,finish,expected))
        expected=original
        for start,finish,block in reversed(sorted(edits)):expected=expected[:start]+block+expected[finish:]
        if current!=expected:raise ValueError("LOCAL unconfigured bridge changed other CLI bytes")
        return True

    def unconfigured_stop_empty_identity(self,payload):
        selected=select_restore_domains(payload,["SMB"])
        files={"/var/lib/samba/private/passdb.tdb","/var/lib/samba/private/secrets.tdb",
            "/etc/ablestack-storage/smb-managed-identities.json","/etc/ablestack-storage/smb-local-account-provenance.json",
            "/etc/ablestack-storage/smb-semantic-identity-aliases.json"}
        accounts={"/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"}
        if (set(selected["files"])!=files or any(row!={"absent":True}for row in selected["files"].values())
                or set(selected["accounts"])!=accounts or any(row!=[]for row in selected["accounts"].values())
                or payload.get("adIdentity")is not None or payload.get("nvmeHosts",{})!={}):
            raise ValueError("LOCAL unconfigured bridge identity is not an exact empty SMB source")
        return files

    def unconfigured_stop_bridge(self,value,journal,payload=None):
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        record=self.read(self.path(scope,"cipher"))
        # An existing collector receipt retains only its original validator.
        # Its presence never grants the new empty-source compatibility kind.
        if self.read(self.path(scope,"code-compatibility"))is not None:return False
        if record is None or record.get("kind")!=LOCAL_SOURCE_KIND or record.get("privateFingerprints")!={}:return False
        self.validate_record(value,record)
        source=self.state(value,recovery=True)
        pending=self.read(self.handler.generations/"pending.json")
        fields={"instanceUuid","operationUuid","revision","phase","previous","startedAt","beforeSha256"}
        initial=value["sourceGeneration"]=={}
        if ((not value["sourceGeneration"]and not initial)or set(pending)!=fields or pending["phase"]!="PREPARED"
                or self.read(self.handler.generations/(scope["operationUuid"]+".json"))is not None
                or source["configurationDesiredState"]["desired-state/smb-share-apply.json"]is not None
                or source["configurationDesiredState"]!=journal.get("sourceDesiredState")
                or record["sourceDesiredState"]!=journal.get("sourceDesiredState")
                or journal.get("names")!=[]or journal.get("nvmeHosts")!=[]or journal.get("authReplayDomains")!=[]
                or record.get("privateFingerprints")!={}or journal.get("phase")not in("RECOVERY_REQUIRED","STOPPED","EXPORTED","RESUMED")
                or journal.get("publicKey")!=record["publicKey"]
                or not self.stable_sessions_empty(value,journal["prior"])
                or record["stoppedDatabases"]!=journal["prior"]["databases"]
                or record["sourceConfigurationFileSha256"]!=journal["prior"]["configurationSha256"]):
            raise ValueError("LOCAL unconfigured bridge source or unfinished target differs")
        if initial and(journal.get("scope")!=scope or journal.get("common")!=self.common(value)
                or journal.get("stopped")!=journal["prior"]
                or journal.get("cliSha256")!="475dc43cadc01191da5904e9fed15f67144d7423fdc21e9494d66d093b58ba6c"):
            raise ValueError("LOCAL initial bridge original SOURCE authority differs")
        files={"/var/lib/samba/private/passdb.tdb","/var/lib/samba/private/secrets.tdb",
            "/etc/ablestack-storage/smb-managed-identities.json","/etc/ablestack-storage/smb-local-account-provenance.json",
            "/etc/ablestack-storage/smb-semantic-identity-aliases.json"}
        if any(os.path.lexists(path)for path in files):raise ValueError("LOCAL unconfigured bridge current SMB identity exists")
        old_sha=journal.get("cliSha256");current=self.cli.read_bytes();current_sha=hashlib.sha256(current).hexdigest()
        if not isinstance(old_sha,str)or not re.fullmatch("[0-9a-f]{64}",old_sha):
            raise ValueError("LOCAL unconfigured bridge original code pin differs")
        active=self.cli.resolve(strict=True)
        if active!=(self.runtime_root/"current"/"ablestack-storagectl").resolve(strict=True):
            raise ValueError("LOCAL unconfigured bridge current ENTRY is not installed")
        def signed(path,checksum):
            raw=self.compat_entry(path,checksum)
            manifest=json.loads(self.reader(str(path.parent/"manifest.json"),64*1024)[0])
            authority=self.installed_release_authority(path.parent,manifest)
            if not os.path.lexists(self.installation_root/authority["transactionId"]/"manifest.sig"):
                raise ValueError("LOCAL unconfigured bridge requires signed installation receipts")
            return raw
        signed(active,current_sha)
        paths=list((self.runtime_root/"releases").iterdir())
        if len(paths)>128:raise ValueError("LOCAL unconfigured bridge historical lookup exceeds its bound")
        originals=[]
        for directory in sorted(paths):
            meta=directory.lstat()
            if not stat.S_ISDIR(meta.st_mode):continue
            candidate=directory/"ablestack-storagectl"
            if not os.path.lexists(candidate):continue
            raw,_=self.reader(str(candidate))
            if hashlib.sha256(raw).hexdigest()==old_sha:originals.append(signed(candidate,old_sha))
        if len(originals)!=1 or not self.unconfigured_stop_bridge_cli(current,originals[0],initial=initial):
            raise ValueError("LOCAL unconfigured bridge original code differs")
        stable={"schemaVersion":1,"kind":"LOCAL_UNCONFIGURED_SOURCE_STOP_GUARD_COMPATIBILITY","scope":scope,"common":self.common(value),
            "originalCliSha256":old_sha,"currentCliSha256":current_sha,"checkpointSha256":service_cipher_digest(record),
            "sourceSmbConfigurationSha256":record["sourceConfigurationFileSha256"],
            "emptySmbIdentityAuthenticated":True,"formatOrAccountMutationAuthorized":False}
        path=self.path(scope,"unconfigured-stop-code-compatibility");saved=self.read(path)
        if saved is not None and saved!=stable:raise ValueError("LOCAL unconfigured bridge immutable receipt differs")
        if payload is not None:
            if set(value)!=LOCAL_SOURCE_COMMON|{"capsule","credentialPrivateKey","localSourceCheckpointReference","deferNvmeReplay","restoreDomains"}:
                raise ValueError("LOCAL unconfigured bridge requires its original import request")
            self.lock()
            self.unconfigured_stop_empty_identity(payload)
            # decode() authenticated the exact immutable record/reference before
            # this call. Only this POST path can publish the compatibility proof.
            if saved is None:self.write(scope,"unconfigured-stop-code-compatibility",stable,immutable=True)
        return True

    def frozen(self,value,journal):
        if (journal.get("scope")!={key:value[key]for key in LOCAL_SOURCE_SCOPE}or journal.get("common")!=self.common(value)):
            raise ValueError("LOCAL source journal/command authority changed")
        if journal.get('cliSha256')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():
            if not source_module_link_compatibility(self,value,journal,LOCAL_SOURCE_MODULE_LINK_SHA256,regular_file,SmbIdentity):
                if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)
        return journal

    def stable_sessions_empty(self,value,before):
        # Empty rows alone are not authority. The exact initial pending writer
        # or a frozen verified SOURCE must also prove all-domain SMB absence.
        absent=(set(before["databases"])==set(IDENTITY_DATABASES)
            and all(row=={"path":IDENTITY_DATABASES[name],"present":False}for name,row in before["databases"].items()))
        if before["units"]or before["ownedEndpoints"]or not absent:
            return self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is True
        source=self.state(value,recovery=True)
        if source["configurationDesiredState"]["desired-state/smb-share-apply.json"]is not None:
            return self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is True
        if value["sourceGeneration"]=={}:
            pending=self.read(self.handler.generations/"pending.json")
            fields={"instanceUuid","operationUuid","revision","phase","previous","startedAt","beforeSha256"}
            if (os.path.lexists(self.handler.generations/"current.json")or not isinstance(pending,dict)
                    or set(pending)!=fields or pending["phase"]!="PREPARED"or pending["previous"]!={}
                    or pending["revision"]!=1 or type(pending["startedAt"])not in(int,float)
                    or not 0<pending["startedAt"]<float("inf")
                    or any(os.path.lexists(path)for path in IDENTITY_DATABASES.values())
                    or self.stopped_observation()!=before):
                raise ValueError("LOCAL initial source absence proof changed")
            return True
        scope={key:value[key]for key in("instanceUuid","operationUuid","revision")}
        view=self.handler.inspect(scope,allow_missing=True);sessions=view.get("sessions",{})
        counts=("establishedTcpCount","synRecvTcpCount","smbSessionCount","treeConnectionCount","openFileCount","byteLockOpenFileCount")
        if (view.get("identityBaselineKind")!="UNCONFIGURED_SMB_SOURCE"or view.get("scope")!=scope
                or view.get("generation")!=value["sourceGeneration"]or view.get("bootId")!=value["expectedBootId"]
                or any(view.get(key)is not True for key in("success","smbIdentitySupported","ownershipVerified","identityDatabaseAligned","identityRestoreSafe"))
                or view.get("requiresQuiesce")is not False
                or any(view.get(key)!=[]for key in("masters","ownedEndpoints","identityHolders"))
                or view.get("databases")!=before["databases"]or view.get("configurationSha256")!=before["configurationSha256"]
                or sessions.get("source")!="UNCONFIGURED_SMB_SOURCE_ABSENCE"
                or any(sessions.get(key)is not True for key in("available","lockingDatabasesAligned","safeToRebind"))
                or any(type(sessions.get(key))is not int or sessions[key]!=0 for key in counts)
                or self.stopped_observation()!=before):
            raise ValueError("LOCAL unconfigured source absence proof changed")
        return True

    def stop(self,value,journal,rollback=False):
        self._failure_stage="STOP_OBSERVE"
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
                    or not self.stable_sessions_empty(value,before)):
                raise ValueError("LOCAL interrupted stop source/sessions changed")
            before["units"]=remaining
        else:
            before=self.observe(value)
            if not rollback and journal["prior"]!=before:raise ValueError("LOCAL source prior ownership changed")
        pinned=before['units']+[row['managedLauncher']for row in before['units']if row.get('managedLauncher')]
        self._failure_stage="STOP_PIN"
        handles=self.handler.open_master_handles(pinned)
        try:
            self._failure_stage="STOP_OWNER_VERIFY"
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
            self._failure_stage="STOP_INTENT_PUBLISH"
            journal["phase"]="QUIESCING";self.write(scope,"journal",journal)
            self._failure_stage="STOP_SIGNAL"
            for row in before["units"]:
                if row['unit']not in ('smbd.service','nmbd.service')and self.launcher_authority(row['unit'],row)!=row.get('managedLauncher'):
                    raise ValueError('LOCAL managed launcher changed before signalling')
                signal.pidfd_send_signal(handles[row["pid"]],signal.SIGSTOP,None,0)
            # Do not call inspect here: its TCP readiness probe would create a
            # connection after SIGSTOP. Recheck source descriptors and sessions
            # directly while the exact acceptors cannot accept application I/O.
            self._failure_stage="STOP_STABLE_VERIFY"
            if (self.handler.database_identity()!=before["databases"]
                    or hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()!=before["configurationSha256"]
                    or not self.stable_sessions_empty(value,before)):
                raise ValueError("LOCAL source changed after exact PID pin")
            self._failure_stage="STOP_TERMINATE"
            for row in before["units"]:
                signal.pidfd_send_signal(handles[row["pid"]],signal.SIGTERM,None,0)
                signal.pidfd_send_signal(handles[row["pid"]],signal.SIGCONT,None,0)
            self._failure_stage="STOP_UNITS"
            for unit in sorted({row["unit"]for row in before["units"]}):self.handler.run(["systemctl","stop",unit])
            self._failure_stage="STOP_VERIFY"
            after=self.observe(value,stopped=True);journal["phase"]="STOPPED";journal["stopped"]=after
            self._failure_stage="STOP_COMPLETE_PUBLISH"
            self.write(scope,"journal",journal);return after
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";self.write(scope,"journal",journal);raise
        finally:
            for fd in handles.values():os.close(fd)

    def resume(self,value,journal,record):
        self._failure_stage="RESUME_SOURCE_AUTHORITY"
        scope=journal["scope"];self.state(value,recovery=journal.get("imported")is True)
        active=set()
        if journal["phase"] in ("EXPORTED","STOPPED"):
            self._failure_stage="RESUME_STOPPED_OBSERVE"
            self.observe(value,stopped=True)
            self._failure_stage="RESUME_PRIVATE_VERIFY"
            self.match_private(record)
        elif journal["phase"] in ("RESUMING","RECOVERY_REQUIRED"):
            self._failure_stage="RESUME_PARTIAL_OBSERVE"
            current=self.observe(value,allow_missing=True,require_empty=False)
            self._failure_stage="RESUME_PARTIAL_AUTHORITY"
            if not {row["unit"]for row in current["units"]}<={row["unit"]for row in journal["prior"]["units"]}:
                raise ValueError("LOCAL partial resume has a foreign unit")
            active={row["unit"]for row in current["units"]}
            if active!={row["unit"]for row in journal["prior"]["units"]}and current["sessionsVerifiedEmpty"]is not True:
                raise ValueError("LOCAL partial source resume requires empty sessions")
        else:raise ValueError("LOCAL source has no resume intent")
        self._failure_stage="RESUME_CONFIG_VERIFY"
        if hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest()!=record["sourceConfigurationFileSha256"]:
            raise ValueError("LOCAL resume SOURCE configuration changed")
        self._failure_stage="RESUME_INTENT_PUBLISH"
        journal["phase"]="RESUMING";self.write(scope,"journal",journal)
        for row in journal["prior"]["units"]:
            if row["unit"]in active:continue
            self._failure_stage="RESUME_START_GRANT"
            with self.start_grant(scope,row["unit"]):
                self._failure_stage="RESUME_START_UNIT"
                self.handler.run(["systemctl","start",row["unit"]])
                self._failure_stage="RESUME_START_GRANT_CLOSE"
        deadline=time.monotonic()+self.handler.remaining(30)
        while True:
            self._failure_stage="RESUME_VERIFY_OBSERVE"
            actual=self.observe(value,allow_missing=True,require_empty=False)
            self._failure_stage="RESUME_VERIFY"
            if ({row["unit"]for row in actual["units"]}=={row["unit"]for row in journal["prior"]["units"]}
                    and actual["databases"]==record["stoppedDatabases"]
                    and actual["configurationSha256"]==record["sourceConfigurationFileSha256"]
                    and (not any(row["unit"]!="nmbd.service"for row in journal["prior"]["units"])
                         or all(row.get("tcpReady")for row in actual["ownedEndpoints"]))):break
            if time.monotonic()>=deadline:raise ValueError("LOCAL source owned resume did not verify")
            time.sleep(.1)
        self._failure_stage="RESUME_COMPLETE_PUBLISH"
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
        self._failure_stage="EXPORT_SOURCE_AUTHORITY"
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
                "sourceDesiredState":source["configurationDesiredState"],"phase":"INTENT_DURABLE",
                "cliSha256":hashlib.sha256(self.cli.read_bytes()).hexdigest()}
            self._failure_stage="EXPORT_SOURCE_OBSERVE"
            journal["prior"]=self.observe(value)
            self.write(scope,"journal",journal)
        self.frozen(value,journal)
        if any(journal[key]!=value[key]for key in ("publicKey","names","nvmeHosts","authReplayDomains")):
            raise ValueError("LOCAL source retry key/collection changed")
        try:
            self._failure_stage="EXPORT_RECORD_VALIDATE"
            if record is not None:
                self.validate_record(value,record)
                if record["publicKey"]!=value["publicKey"]:raise ValueError("LOCAL source cached wrapping key changed")
                if journal["phase"]=="RESUMED":
                    self._failure_stage="EXPORT_CACHE_OBSERVE"
                    actual=self.observe(value,require_empty=False)
                    if ({row["unit"]for row in actual["units"]}!={row["unit"]for row in journal["prior"]["units"]}
                            or actual["configurationSha256"]!=record["sourceConfigurationFileSha256"]
                            or actual["databases"]!=journal.get("importedStoppedDatabases",record["stoppedDatabases"])
                            or not all(row.get("tcpReady")for row in actual["ownedEndpoints"])):
                        raise ValueError("LOCAL source resumed cache ownership changed")
                    return self.result(record,journal)
                self.resume(value,journal,record);return self.result(record,journal)
            self._failure_stage="EXPORT_STOP"
            if journal["phase"]not in("STOPPED","EXPORTED"):
                if journal["phase"]in("QUIESCING","RECOVERY_REQUIRED"):
                    self._failure_stage="EXPORT_RETRY_STOP_OBSERVE"
                    try:stopped=self.stopped_observation()
                    except ValueError:stopped=None
                    if stopped is not None:
                        if (stopped["databases"]!=journal["prior"]["databases"]
                                or stopped["configurationSha256"]!=journal["prior"]["configurationSha256"]):
                            raise ValueError("LOCAL interrupted stop source changed")
                        journal["phase"]="STOPPED";journal["stopped"]=stopped;self.write(scope,"journal",journal)
                    else:self.stop(value,journal)
                else:self.stop(value,journal)
            self._failure_stage="EXPORT_STOPPED_OBSERVE"
            stopped=self.stopped_observation()
            source_raw,source_info=self.reader(str(LOCAL_SOURCE_SMB_CONFIG))
            source_file=self.smb_configuration_file_fingerprint(source_raw,source_info)
            if source_file["sha256"]!=stopped["configurationSha256"]:
                raise ValueError("LOCAL SOURCE SMB configuration changed before capture")
            self._failure_stage="EXPORT_POSIX_COLLECT"
            records=self.posix_collector(journal["sourceDesiredState"]["posix-directory-policies.json"],value["instanceUuid"])
            self._failure_stage="EXPORT_IDENTITY_COLLECT"
            payload=self.collector(names,hosts,posix_policies=records)
            self._failure_stage="EXPORT_IDENTITY_VALIDATE"
            payload["sourceConfigurationSha256"]=value["sourceConfigurationSha256"];self.validator(payload)
            if payload.get("adIdentity")is not None:raise ValueError("LOCAL opaque capture cannot include AD")
            wrapper={"schemaVersion":1,"kind":LOCAL_SOURCE_KIND,"scope":scope,"sourceGeneration":value["sourceGeneration"],
                "sourceConfigurationSha256":value["sourceConfigurationSha256"],"bootId":value["expectedBootId"],"identity":payload}
            self._failure_stage="EXPORT_ENCRYPT"
            capsule=self.encryptor(wrapper,value["publicKey"],scope["instanceUuid"]+":"+scope["operationUuid"])
            self._failure_stage="EXPORT_STOPPED_RECHECK"
            if (self.stopped_observation()!=stopped
                    or self.smb_configuration_file_fingerprint(*self.reader(str(LOCAL_SOURCE_SMB_CONFIG)))!=source_file):
                raise ValueError("LOCAL source changed during opaque collection")
            record={"schemaVersion":1,"kind":LOCAL_SOURCE_KIND,"scope":scope,"common":self.common(value),"publicKey":value["publicKey"],
                "capsule":capsule,"stoppedDatabases":stopped["databases"],"sourceConfigurationFileSha256":stopped["configurationSha256"],
                "sourceConfigurationFileMetadata":source_file,
                "authReplayDomains":value["authReplayDomains"],
                "sourceDesiredState":journal["sourceDesiredState"],
                "privateFingerprints":{path:row["sha256"]for path,row in payload["files"].items()if row.get("absent")is not True}}
            self._failure_stage="EXPORT_CHECKPOINT_PUBLISH"
            if (record["sourceDesiredState"]["desired-state/smb-share-apply.json"]is None
                    and record["privateFingerprints"]=={}):
                self.vendor_source_smb_candidate(record,journal)
            self.match_private(record);self.write(scope,"cipher",record,immutable=True)
            journal["phase"]="EXPORTED";self.write(scope,"journal",journal)
            self.resume(value,journal,record);return self.result(record,journal)
        except Exception as error:
            self.record_failure(scope,journal,error);raise

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

    def smb_configuration_file_fingerprint(self,raw,info):
        if (not isinstance(raw,bytes)or not stat.S_ISREG(info.st_mode)or info.st_uid!=0
                or info.st_nlink!=1 or info.st_mode&0o7022 or info.st_size!=len(raw)
                or len(raw)>8*1024*1024):
            raise ValueError("LOCAL SOURCE SMB configuration is not a protected exact regular file")
        return {"sha256":hashlib.sha256(raw).hexdigest(),"sizeBytes":len(raw),
            "uid":info.st_uid,"gid":info.st_gid,"mode":stat.S_IMODE(info.st_mode)}

    def vendor_source_smb_candidate(self,record,journal):
        metadata=record.get("sourceConfigurationFileMetadata")
        if (not isinstance(metadata,dict)or set(metadata)!={"sha256","sizeBytes","uid","gid","mode"}
                or any(type(metadata[key])is not int for key in("sizeBytes","uid","gid","mode"))
                or metadata["sha256"]!=record["sourceConfigurationFileSha256"]
                or record["sourceDesiredState"]["desired-state/smb-share-apply.json"]is not None
                or record.get("privateFingerprints")!={}or journal["prior"]["units"]or journal["prior"]["ownedEndpoints"]
                or set(record["stoppedDatabases"])!=set(IDENTITY_DATABASES)
                or any(row!={"path":IDENTITY_DATABASES[name],"present":False}
                    for name,row in record["stoppedDatabases"].items())):
            raise ValueError("LOCAL empty SOURCE SMB lacks its original protected vendor authority")
        # This fixed package path is never supplied by an archive or request.
        parents=[Path(name)for name in("/usr","/usr/share","/usr/share/samba")]
        before=[path.lstat()for path in parents]
        if any(not stat.S_ISDIR(info.st_mode)or info.st_uid!=0 or info.st_mode&0o022 for info in before):
            raise ValueError("LOCAL SOURCE SMB vendor parents are not protected")
        path=Path("/usr/share/samba/smb.conf")
        raw,info=self.reader("/usr/share/samba/smb.conf")
        named=path.lstat();fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
        if (any(getattr(named,key)!=getattr(info,key)for key in fields)
                or any(any(getattr(path.lstat(),key)!=getattr(prior,key)for key in fields)
                    for path,prior in zip(parents,before))
                or self.smb_configuration_file_fingerprint(raw,info)!=metadata):
            raise ValueError("LOCAL SOURCE SMB vendor configuration differs from its original fingerprint")
        return raw

    def signed_source_smb_candidate(self,payload,journal):
        """Reuse only the fixed pure SMB renderer from the installed signed ENTRY."""
        import ast,ipaddress,zlib
        if (not isinstance(payload,dict)or payload.get("instanceUuid")!=journal["scope"]["instanceUuid"]
                or (payload.get("identityDomain")or {}).get("joinState")=="JOINED"):
            raise ValueError("LOCAL SOURCE SMB render scope is invalid")
        current=self.cli.resolve(strict=True)
        if current!=(self.runtime_root/"current"/"ablestack-storagectl").resolve(strict=True):
            raise ValueError("LOCAL SOURCE SMB renderer ENTRY is not installed")
        raw=self.compat_entry(current,journal["cliSha256"])
        if hashlib.sha256(raw).hexdigest()!=journal["cliSha256"]:
            raise ValueError("LOCAL SOURCE SMB renderer code pin changed")
        opener="<<'PYRENDEREDGENERATION'"+chr(10);closer=chr(10)+"PYRENDEREDGENERATION"+chr(10)
        text=raw.decode()
        if text.count(opener)!=1 or text.count(closer)!=1:
            raise ValueError("LOCAL SOURCE SMB renderer closure is unavailable")
        body=text.split(opener,1)[1].split(closer,1)[0];tree=ast.parse(body)
        functions={"ad_authority_read","ad_qualified_name","ad_policy_lines","protected_ad_policy",
            "semantic_forced_alias","fixed_render_helpers","render_smb_candidate"}
        constants={"SMB_HELPERS","SEMANTIC_ALIAS_ROW"}
        selected=[node for node in tree.body if isinstance(node,ast.FunctionDef)and node.name in functions]
        if len(selected)!=len(functions)or {node.name for node in selected}!=functions:
            raise ValueError("LOCAL SOURCE SMB renderer definitions differ")
        namespace={"ast":ast,"hashlib":hashlib,"ipaddress":ipaddress,"json":json,"os":os,
            "Path":Path,"re":re,"stat":stat,"uuid":uuid,"zlib":zlib}
        found=set()
        for node in tree.body:
            if (isinstance(node,ast.Assign)and len(node.targets)==1 and isinstance(node.targets[0],ast.Name)
                    and node.targets[0].id in constants):
                name=node.targets[0].id
                if name in found:raise ValueError("LOCAL SOURCE SMB renderer constants collide")
                value=ast.literal_eval(node.value)
                if not isinstance(value,set)or any(not isinstance(item,str)for item in value):
                    raise ValueError("LOCAL SOURCE SMB renderer constants differ")
                namespace[name]=value;found.add(name)
        if found!=constants:raise ValueError("LOCAL SOURCE SMB renderer constants are absent")
        exec(compile(ast.Module(body=selected,type_ignores=[]),str(current),"exec"),namespace)
        files=namespace["render_smb_candidate"](payload,current)
        candidate=files.get("smb/smb.conf")
        if not isinstance(candidate,str)or len(candidate.encode())>8*1024*1024:
            raise ValueError("LOCAL SOURCE SMB renderer candidate is invalid")
        if self.cli.resolve(strict=True)!=current or hashlib.sha256(self.cli.read_bytes()).hexdigest()!=journal["cliSha256"]:
            raise ValueError("LOCAL SOURCE SMB renderer changed during generation")
        return candidate.encode()

    def restore_source_smb_configuration(self,value,journal,record):
        # The opaque capsule deliberately excludes generated smb.conf. A legacy
        # writer must restore that exact SOURCE output before exposing its SAM.
        path=LOCAL_SOURCE_SMB_CONFIG
        current,info=self.reader(str(path));expected=record["sourceConfigurationFileSha256"]
        metadata=record.get("sourceConfigurationFileMetadata")
        if metadata is not None and (not isinstance(metadata,dict)
                or set(metadata)!={"sha256","sizeBytes","uid","gid","mode"}
                or any(type(metadata[key])is not int for key in("sizeBytes","uid","gid","mode"))
                or metadata["sha256"]!=expected or metadata["uid"]!=0 or metadata["gid"]<0
                or not 0<=metadata["sizeBytes"]<=8*1024*1024 or not 0<=metadata["mode"]<=0o777
                or metadata["mode"]&0o022):
            raise ValueError("LOCAL SOURCE SMB original configuration metadata is malformed")
        if (hashlib.sha256(current).hexdigest()==expected
                and (metadata is None or self.smb_configuration_file_fingerprint(current,info)==metadata)):return
        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}
        source=self.state(value,recovery=True);self.frozen(value,journal);self.validate_record(value,record)
        if (self.reference(record)!=value["localSourceCheckpointReference"]or journal.get("imported")is not True
                or journal.get("phase")not in("STOPPED","RESUMING","RECOVERY_REQUIRED")
                or not isinstance(journal.get("importedStoppedDatabases"),dict)
                or not isinstance(journal.get("importedFingerprints"),dict)
                or source.get("configurationDesiredState")!=record["sourceDesiredState"]
                or journal["sourceDesiredState"]!=record["sourceDesiredState"]):
            raise ValueError("LOCAL SOURCE SMB configuration restore authority differs")
        stopped=self.stopped_observation()
        if stopped["databases"]!=journal["importedStoppedDatabases"]:
            raise ValueError("LOCAL SOURCE SMB imported stopped metadata changed")
        self.match_private({"privateFingerprints":journal["importedFingerprints"]})
        parent=path.parent.lstat();named=path.lstat()
        if (not stat.S_ISDIR(parent.st_mode)or parent.st_uid!=os.geteuid()or parent.st_mode&0o022
                or not stat.S_ISREG(named.st_mode)or named.st_uid!=os.geteuid()or named.st_mode&0o022
                or named.st_nlink!=1 or (named.st_dev,named.st_ino)!=(info.st_dev,info.st_ino)):
            raise ValueError("LOCAL SOURCE SMB configuration path is not protected")
        journal["sourceConfigurationRestoreStage"]="RENDER";self.write(scope,"journal",journal)
        source_smb=record["sourceDesiredState"]["desired-state/smb-share-apply.json"]
        candidate=(self.vendor_source_smb_candidate(record,journal)if source_smb is None
            else self.signed_source_smb_candidate(source_smb,journal))
        journal["sourceConfigurationRestoreStage"]="CANDIDATE_HASH";self.write(scope,"journal",journal)
        if hashlib.sha256(candidate).hexdigest()!=expected:
            raise ValueError("LOCAL SOURCE SMB rendered configuration differs from its original hash")
        directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        temporary=None;descriptor=None
        try:
            opened=os.fstat(directory)
            if (opened.st_dev,opened.st_ino)!=(parent.st_dev,parent.st_ino):
                raise ValueError("LOCAL SOURCE SMB configuration directory changed")
            descriptor,temporary=tempfile.mkstemp(prefix=".local-source-smb-",dir=path.parent)
            original=metadata or {"mode":stat.S_IMODE(info.st_mode),"uid":info.st_uid,"gid":info.st_gid}
            os.fchmod(descriptor,original["mode"]);os.fchown(descriptor,original["uid"],original["gid"])
            with os.fdopen(descriptor,"wb",closefd=False)as handle:
                handle.write(candidate);handle.flush();os.fsync(descriptor)
            journal["sourceConfigurationRestoreStage"]="TESTPARM";self.write(scope,"journal",journal)
            try:self.handler.run(["testparm","-s",temporary])
            except Exception:raise ValueError("LOCAL SOURCE SMB rendered configuration validation failed")from None
            self.state(value,recovery=True)
            if self.stopped_observation()!=stopped:
                raise ValueError("LOCAL SOURCE SMB stopped ownership changed before configuration restore")
            self.match_private({"privateFingerprints":journal["importedFingerprints"]})
            latest=path.lstat();parent_now=path.parent.lstat()
            fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
            if (any(getattr(latest,key)!=getattr(info,key)for key in fields)
                    or (parent_now.st_dev,parent_now.st_ino)!=(parent.st_dev,parent.st_ino)):
                raise ValueError("LOCAL SOURCE SMB configuration changed before publication")
            journal["sourceConfigurationRestoreStage"]="PUBLISH";self.write(scope,"journal",journal)
            os.replace(temporary,path);temporary=None;os.fsync(directory)
            restored,restored_info=self.reader(str(path))
            if (hashlib.sha256(restored).hexdigest()!=expected
                    or metadata is not None and self.smb_configuration_file_fingerprint(restored,restored_info)!=metadata):
                raise ValueError("LOCAL SOURCE SMB configuration restore did not verify")
            journal["sourceConfigurationRestoreStage"]="VERIFIED";self.write(scope,"journal",journal)
        finally:
            if descriptor is not None:os.close(descriptor)
            if temporary is not None and os.path.exists(temporary):os.unlink(temporary)
            os.close(directory)

    def rendered_target_private_context(self,request,inverse=False):
        from rendered_generation import RenderedGeneration
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")!="9":raise ValueError("LOCAL rendered private target requires its owned FD9")
        self.lock()
        scope={key:request[key]for key in("instanceUuid","operationUuid","revision")}
        ref=request.get("localSourceCheckpointReference")
        if not isinstance(ref,dict)or set(ref)!={"localCheckpointUuid","sha256"}:
            raise ValueError("LOCAL rendered private target lacks its original source reference")
        local_scope={**scope,"localCheckpointUuid":ref["localCheckpointUuid"]}
        record=self.read(self.path(local_scope,"cipher"));journal=self.read(self.path(local_scope,"journal"))
        if not record or not journal:raise ValueError("LOCAL rendered private target original source is absent")
        common=record["common"];self.request(common,"local-source-status")
        if any(common.get(key)!=item for key,item in local_scope.items())or self.reference(record)!=ref:
            raise ValueError("LOCAL rendered private target source scope changed")
        self.frozen(common,journal);self.validate_record(common,record)
        value={**common,"capsule":record["capsule"],"credentialPrivateKey":request["checkpointPrivateKey"],
            "localSourceCheckpointReference":ref,"deferNvmeReplay":True,"restoreDomains":["SMB"],
            "renderedManifestSha256":request["renderedManifestSha256"]}
        payload=self.decode(value,record)
        absent=(record.get("privateFingerprints")=={} and record["sourceDesiredState"]["desired-state/smb-share-apply.json"]is None
            and not journal["prior"]["units"]and not journal["prior"]["ownedEndpoints"]
            and set(record["stoppedDatabases"])==set(IDENTITY_DATABASES)
            and all(row=={"path":IDENTITY_DATABASES[name],"present":False}for name,row in record["stoppedDatabases"].items()))
        if not absent:return None
        self.state(common,source_only=False,terminal=True,recovery=True)
        store=RenderedGeneration(Path(os.environ.get("ABLESTACK_STORAGE_RENDERED_GENERATIONS",
            self.handler.generations.parent/"rendered-generations")))
        activation=store.scoped_activation(scope);target=store.inspect(store.generations/scope["operationUuid"])
        source=store.inspect(store.generations/activation["previousOperationUuid"])
        if (activation["phase"]!=("ROLLING_BACK"if inverse else"ACTIVATING")
                or request["renderedManifestSha256"]!=target["manifestSha256"]
                or store.status()["current"]!=(source if inverse else target)
                or source["configurationSha256"]!=common["sourceConfigurationSha256"]
                or activation["previousSha256"]!=source["manifestSha256"]
                or target["previousRenderedSha256"]!=source["manifestSha256"]):
            raise ValueError("LOCAL rendered private target immutable source/target activation changed")
        desired=json.loads(__import__("rendered_generation").rendered_read(store.generations/scope["operationUuid"]/"desired-state.json"))
        smb=desired["desired-state/smb-share-apply.json"]
        if (not isinstance(smb,dict)or smb.get("instanceUuid")!=scope["instanceUuid"]
                or any((share.get("config")or{}).get("posixOwnershipMode","AUTHENTICATED_USER")!="AUTHENTICATED_USER"for share in smb.get("shares",[]))):
            return None
        principals=set()
        for share in smb.get("shares",[]):
            if share.get("state","Ready")in("Disabled","Destroyed","Error"):continue
            for acl in share.get("acls",[]):
                if acl.get("state","Ready")in("Disabled","Destroyed","Error"):continue
                if (acl.get("config")or{}).get("localAccount")is True:
                    name=acl.get("principal")
                    if not isinstance(name,str)or not re.fullmatch("[A-Za-z0-9_.-]{1,64}",name)or name in("root","nobody"):
                        raise ValueError("LOCAL rendered private target principal is not closed")
                    principals.add(name)
        if not principals:return None
        return scope,value,record,journal,source,target,sorted(principals)

    def rendered_target_private_snapshot(self,principals):
        import base64
        paths=set(IDENTITY_DATABASES.values())|{
            "/etc/ablestack-storage/smb-managed-identities.json",
            "/etc/ablestack-storage/smb-local-account-provenance.json",
            "/etc/ablestack-storage/smb-semantic-identity-aliases.json"}
        fingerprints={};contents={}
        for name in sorted(paths|set(ACCOUNT_FILES)):
            if not os.path.lexists(name):
                fingerprints[name]={"present":False};continue
            raw,info=self.reader(name)
            if name in paths and info.st_mode&0o077:raise ValueError("LOCAL rendered private target file is not private")
            fingerprints[name]={"present":True,"device":info.st_dev,"inode":info.st_ino,"uid":info.st_uid,"gid":info.st_gid,
                "mode":format(stat.S_IMODE(info.st_mode),"04o"),"size":info.st_size,
                "mtimeNs":info.st_mtime_ns,"ctimeNs":info.st_ctime_ns,"sha256":hashlib.sha256(raw).hexdigest()}
            contents[name]=raw
        for name in("/var/lib/samba/private/passdb.tdb","/var/lib/samba/private/secrets.tdb"):
            if name not in contents:raise ValueError("LOCAL rendered private target has no completed SAM")
        public={name:{"data":base64.b64encode(raw).decode()}for name,raw in contents.items()
            if name in("/etc/ablestack-storage/smb-managed-identities.json","/etc/ablestack-storage/smb-local-account-provenance.json",
                "/etc/ablestack-storage/smb-semantic-identity-aliases.json")}
        owned=owned_account_records(public)
        if set(owned["/etc/passwd"])!=set(principals)or not set(owned["/etc/group"])<=set(principals):
            raise ValueError("LOCAL rendered private target contains unapproved account provenance")
        rows={}
        for name in ACCOUNT_FILES:
            if name not in contents:raise ValueError("LOCAL rendered private target account file is absent")
            observed={line.split(":",1)[0]:line for line in contents[name].decode().splitlines()}
            wanted=owned[name]if name in("/etc/passwd","/etc/group")else{
                key:observed[key]for key in(owned["/etc/passwd"]if name=="/etc/shadow"else owned["/etc/group"])if key in observed}
            if any(observed.get(key)!=row for key,row in wanted.items())or not set(wanted)<=set(principals):
                raise ValueError("LOCAL rendered private target account rows changed")
            rows[name]={key:hashlib.sha256(row.encode()).hexdigest()for key,row in wanted.items()}
        users={}
        for name,line in owned["/etc/passwd"].items():
            fields=line.split(":");uid=int(fields[2]);gid=int(fields[3])
            if uid<1000 or gid<1000 or uid==65534 or gid==65534 or fields[6]not in("/usr/sbin/nologin","/sbin/nologin"):
                raise ValueError("LOCAL rendered private target account is protected or interactive")
            users[name]={"uid":uid,"gid":gid}
        pdb={}
        for line in self.handler.run(["pdbedit","-L"]).splitlines():
            fields=line.split(":",2)
            if len(fields)!=3 or fields[0]in pdb:raise ValueError("LOCAL rendered private target SAM users are malformed")
            pdb[fields[0]]=int(fields[1])
        if pdb!={name:row["uid"]for name,row in users.items()}:
            raise ValueError("LOCAL rendered private target SAM contains foreign users")
        sid=self.handler.run(["net","getlocalsid"]).strip()
        match=re.fullmatch(r"SID for domain [A-Za-z0-9_.-]+ is: (S-1-5-21-[0-9]+-[0-9]+-[0-9]+)",sid)
        if not match:raise ValueError("LOCAL rendered private target machine SID is unavailable")
        return {"files":fingerprints,"accountRows":rows,"principals":users,"machineSid":match[1]}

    def rendered_target_private_receipt(self,context,units):
        scope,value,record,journal,source,target,principals=context
        return {"schemaVersion":1,"kind":"LOCAL_RENDERED_TARGET_PRIVATE","scope":{key:value[key]for key in LOCAL_SOURCE_SCOPE},
            "sourceReference":self.reference(record),"sourceGeneration":value["sourceGeneration"],
            "sourceConfigurationSha256":value["sourceConfigurationSha256"],"bootId":value["expectedBootId"],
            "cliSha256":journal["cliSha256"],"sourceManifestSha256":source["manifestSha256"],
            "targetManifestSha256":target["manifestSha256"],"targetSmbConfigurationSha256":target["files"]["smb/smb.conf"],
            "private":self.rendered_target_private_snapshot(principals),"targetUnits":units}

    def capture_rendered_target_private(self,request):
        context=self.rendered_target_private_context(request)
        if context is None:return {"success":True,"targetPrivateCheckpointCaptured":False}
        scope,value,record,journal,source,target,principals=context
        actual=self.command(("operation","generation","status"))
        if actual["configurationDesiredState"]["desired-state/smb-share-apply.json"]!=json.loads(
                __import__("rendered_generation").rendered_read(
                    Path(os.environ.get("ABLESTACK_STORAGE_RENDERED_GENERATIONS",self.handler.generations.parent/"rendered-generations"))/
                    "generations"/scope["operationUuid"]/"desired-state.json"))["desired-state/smb-share-apply.json"]:
            raise ValueError("LOCAL rendered private target canonical SMB differs from its completed replay")
        raw,_=self.reader("/etc/samba/smb.conf")
        if hashlib.sha256(raw).hexdigest()!=target["files"]["smb/smb.conf"]:
            raise ValueError("LOCAL rendered private target SMB configuration differs")
        view=self.observe(value,allow_missing=True)
        wanted=json.loads(__import__("rendered_generation").rendered_read(
            Path(os.environ.get("ABLESTACK_STORAGE_RENDERED_GENERATIONS",self.handler.generations.parent/"rendered-generations"))/
            "generations"/scope["operationUuid"]/"smb/manifest.json"))["listeners"]
        if ({(row["listenIp"],row["port"])for row in view["ownedEndpoints"]}!={(row["listenIp"],row["port"])for row in wanted}
                or not view["units"]or any(row.get("tcpReady")is not True for row in view["ownedEndpoints"])):
            raise ValueError("LOCAL rendered private target SMB replay is not live and exactly owned")
        units=sorted({row["unit"]for row in view["units"]})
        receipt=self.rendered_target_private_receipt(context,units)
        self.write(value,"rendered-target-private",receipt,immutable=True)
        return {"success":True,"targetPrivateCheckpointCaptured":True}

    def prepare_rendered_target_private_restore(self,request):
        context=self.rendered_target_private_context(request,inverse=True)
        if context is None:return {"success":True,"targetPrivateStopped":False}
        scope,value,record,journal,source,target,principals=context
        receipt=self.read(self.path(value,"rendered-target-private"))
        if not receipt:raise ValueError("LOCAL rendered private target completed replay receipt is absent")
        expected=self.rendered_target_private_receipt(context,receipt.get("targetUnits"))
        if (receipt!=expected or not isinstance(receipt.get("targetUnits"),list)
                or len(set(receipt["targetUnits"]))!=len(receipt["targetUnits"])
                or any(not re.fullmatch(r"ablestack-storage-smb@[0-9a-f]{24}\.service",unit)for unit in receipt["targetUnits"])):
            raise ValueError("LOCAL rendered private target has no exact completed replay receipt")
        before=self.observe(value,allow_missing=True)
        if not {row["unit"]for row in before["units"]}<=set(receipt["targetUnits"]):
            raise ValueError("LOCAL rendered private target acquired a foreign acceptor")
        service={**scope,"maintenanceUuid":scope["operationUuid"]}
        stopped=self.command(("operation","quiesce"),{**service,"domains":["SMB"]})
        if (stopped.get("success")is not True or stopped.get("quiesced")is not True or stopped.get("scope")!=service
                or stopped.get("maintenanceKind")!="SERVICE"or stopped.get("bootHeld")is not True
                or stopped.get("domainsQuiesced")!=["SMB"]):
            raise ValueError("LOCAL rendered private target stop is unknown")
        view=self.stopped_observation()
        if (view["configurationSha256"]!=receipt["targetSmbConfigurationSha256"]
                or self.rendered_target_private_snapshot(principals)!=receipt["private"]):
            raise ValueError("LOCAL rendered private target changed across its owned stop")
        proof={"schemaVersion":1,"kind":"LOCAL_RENDERED_TARGET_PRIVATE_STOPPED","scope":receipt["scope"],
            "targetReceiptSha256":service_cipher_digest(receipt),"sourceReference":self.reference(record),
            "bootId":value["expectedBootId"],"stoppedUnits":stopped["stoppedUnits"],"stopped":view}
        self.write(value,"rendered-target-private-stop",proof,immutable=True)
        return {"success":True,"targetPrivateStopped":True}

    def rendered_target_private_restore_safe(self,value,record,payload):
        scope={key:value[key]for key in("instanceUuid","operationUuid","revision")}
        request={**scope,"localSourceCheckpointReference":value["localSourceCheckpointReference"],
            "checkpointPrivateKey":value["credentialPrivateKey"],"renderedManifestSha256":value["renderedManifestSha256"]}
        context=self.rendered_target_private_context(request,inverse=True)
        if context is None:raise ValueError("LOCAL rendered private inverse source was not originally absent")
        receipt=self.read(self.path(value,"rendered-target-private"))
        stopped=self.read(self.path(value,"rendered-target-private-stop"))
        if (not receipt or not stopped or stopped.get("schemaVersion")!=1
                or stopped.get("kind")!="LOCAL_RENDERED_TARGET_PRIVATE_STOPPED"
                or stopped.get("scope")!={key:value[key]for key in LOCAL_SOURCE_SCOPE}
                or stopped.get("targetReceiptSha256")!=service_cipher_digest(receipt)
                or stopped.get("sourceReference")!=self.reference(record)or stopped.get("bootId")!=value["expectedBootId"]
                or receipt!=self.rendered_target_private_receipt(context,receipt.get("targetUnits"))
                or record.get("privateFingerprints")!={}
                or any(payload["files"].get(path)!={"absent":True}for path,meta in receipt.get("private",{}).get("files",{}).items()
                    if meta.get("present")is True and path not in("/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"))):
            raise ValueError("LOCAL rendered private inverse lacks its exact stopped target proof")
        self.state(value,recovery=True)
        view=self.stopped_observation()
        if view!=stopped["stopped"]:
            raise ValueError("LOCAL rendered private inverse stopped FD or configuration changed")
        return stopped

    def import_source(self,value):
        self._failure_stage="IMPORT_SOURCE_AUTHORITY"
        scope=self.request(value,"import-local-source");self.state(value,recovery=True)
        journal=self.frozen(value,self.read(self.path(scope,"journal"))or {})
        record=self.read(self.path(scope,"cipher"))
        if not record:raise ValueError("LOCAL original checkpoint is absent")
        payload=self.decode(value,record)
        if journal.get('cliSha256')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():
            self.unconfigured_stop_bridge(value,journal,payload)
        target_stop=None
        for path in IDENTITY_DATABASES.values():
            if payload["files"].get(path)=={"absent":True}and os.path.lexists(path):
                if self.read(self.path(scope,"rendered-target-private-stop"))is None:
                    raise ValueError("LOCAL refuses to erase an unproven new SAM/database")
                target_stop=self.rendered_target_private_restore_safe(value,record,payload)
                break
        try:
            if journal.get("imported")is True and journal["phase"]=="RESUMED":
                self._failure_stage="IMPORT_CACHE_OBSERVE"
                actual=self.observe(value,require_empty=False)
                if (actual["configurationSha256"]!=record["sourceConfigurationFileSha256"]
                        or actual["databases"]!=journal.get("importedStoppedDatabases")
                        or {row["unit"]for row in actual["units"]}!={row["unit"]for row in journal["prior"]["units"]}):
                    raise ValueError("LOCAL imported resume changed")
                return self.result(record,journal,imported=True)
            if journal.get("imported")is not True:
                self._failure_stage="IMPORT_STOP"
                if target_stop is not None:
                    journal["targetPrivateStopReference"]=service_cipher_digest(target_stop)
                    journal["phase"]="STOPPED";self.write(scope,"journal",journal)
                elif journal["phase"]!="STOPPED":self.stop(value,journal,rollback=True)
                self._failure_stage="IMPORT_STOPPED_OBSERVE"
                self.stopped_observation()
                file_payload=dict(payload);file_payload.pop("posixPolicies",None);file_payload.pop("sourceConfigurationSha256",None)
                self._failure_stage="IMPORT_IDENTITY_RESTORE"
                self.importer(file_payload)
                selected=select_restore_domains(file_payload,["SMB"])
                restored={"privateFingerprints":{path:row["sha256"]for path,row in selected["files"].items()if row.get("absent")is not True}}
                self._failure_stage="IMPORT_PRIVATE_VERIFY"
                self.match_private(restored)
                journal["imported"]=True;journal["phase"]="STOPPED"
                journal["importedStoppedDatabases"]=self.stopped_observation()["databases"]
                journal["importedFingerprints"]=restored["privateFingerprints"]
                journal["importedCheckpointSha256"]=service_cipher_digest(record)
                self._failure_stage="IMPORT_CHECKPOINT_PUBLISH"
                self.write(scope,"journal",journal)
            if not isinstance(journal.get("importedStoppedDatabases"),dict)or not isinstance(journal.get("importedFingerprints"),dict):
                raise ValueError("LOCAL restored descriptor authority is absent")
            resume_record={**record,"stoppedDatabases":journal["importedStoppedDatabases"],"privateFingerprints":journal["importedFingerprints"]}
            self._failure_stage="IMPORT_CONFIG_RESTORE"
            self.restore_source_smb_configuration(value,journal,record)
            self.resume(value,journal,resume_record);return self.result(record,journal,imported=True)
        except Exception as error:
            self.record_failure(scope,journal,error);raise

    def status(self,value):
        scope=self.request(value,"local-source-status");actual=self.state(value,source_only=False,terminal=True,recovery=True)
        journal=self.read(self.path(scope,"journal"))
        result={"success":True,"checkpointSupported":True,"scope":scope,"sourceGeneration":value["sourceGeneration"],
            "sourceConfigurationSha256":value["sourceConfigurationSha256"],"bootId":self.boot(),
            "journalPresent":journal is not None,"canonicalDesiredStateChanged":False}
        if journal is None:
            # Export publishes its durable journal before STOP. Journal absence
            # alone is insufficient: prove the exact unmodified, empty SOURCE
            # under its original pending writer before permitting cancellation.
            if (not os.path.lexists(self.path(scope,"cipher"))
                    and actual["configurationDesiredState"]["desired-state/smb-share-apply.json"] is None):
                self.state(value,source_only=True,terminal=True,recovery=True)
                view=self.observe(value,allow_missing=True)
                if (not view["units"] and not view["ownedEndpoints"] and view["sessionsVerifiedEmpty"] is True
                        and set(view["databases"])==set(IDENTITY_DATABASES)
                        and all(row=={"path":IDENTITY_DATABASES[name],"present":False}
                            for name,row in view["databases"].items())):
                    result.update({"phase":"NOT_STARTED","sourceStopNotStarted":True,"checkpointCipherAbsent":True,
                        "sourceRuntimeVerified":True,"currentSessionsVerifiedEmpty":True})
            return result
        self.frozen(value,journal);record=self.read(self.path(scope,"cipher"))
        if record is not None:self.validate_record(value,record)
        if (record is None and journal.get('phase')in ('STOPPED','RECOVERY_REQUIRED')
                and isinstance(journal.get('stopped'),dict)and not os.path.lexists(self.path(scope,'cipher'))):
            self.state(value,source_only=True,recovery=True)
            stopped=self.stopped_observation()
            if (stopped!=journal['stopped']or stopped['configurationSha256']!=journal['prior']['configurationSha256']):
                raise ValueError('LOCAL runtime quarantine original STOP changed')
            current_sha=hashlib.sha256(self.cli.read_bytes()).hexdigest()
            current_entry=self.cli.resolve(strict=True)
            if current_entry!=(self.runtime_root/'current'/'ablestack-storagectl').resolve(strict=True):
                raise ValueError('LOCAL runtime quarantine current ENTRY changed')
            self.compat_entry(current_entry,current_sha)
            result['localSourceStoppedRuntimeProof']={'kind':'LOCAL_SOURCE_STOPPED_RUNTIME_QUARANTINE','scope':scope,
                'sourceGeneration':value['sourceGeneration'],'sourceConfigurationSha256':value['sourceConfigurationSha256'],
                'bootId':self.boot(),'originalCliSha256':journal['cliSha256'],'currentCliSha256':current_sha,
                'sourceStoppedVerified':True,'stoppedMetadataVerified':True,'privateHoldersAbsent':True,'cipherAbsent':True,
                'sourceCanonicalUnchanged':True,'originalSignedCodeVerified':True,'currentSignedCodeVerified':True,
                'serviceAvailabilityVerified':False}
        view=self.observe(value,stopped=journal["phase"]in("STOPPED","EXPORTED"),allow_missing=True)
        verified=(actual["configurationSha256"]==value["sourceConfigurationSha256"]and journal["phase"]=="RESUMED"and record is not None
            and view["configurationSha256"]==record["sourceConfigurationFileSha256"]
            and {row["unit"]for row in view["units"]}=={row["unit"]for row in journal["prior"]["units"]})
        return {**result,"phase":journal["phase"],"sourceSmbResumed":verified,"sourceRuntimeVerified":verified,
            "sourceIdentityRestoreSupported":record is not None,"currentRuntimeOwnershipVerified":True,
            "currentSessionsVerifiedEmpty":view["sessionsVerifiedEmpty"],"currentConfigurationSha256":actual["configurationSha256"],
            "currentSmbConfigurationSha256":view["configurationSha256"],**({"localSourceCheckpointReference":self.reference(record)}if record is not None else {})}

    def cleanup_absent_nvme_source(self,value,record):
        # Only an existing original LOCAL import can reach this consumer. The
        # normal signed NVMe adapter remains the full TARGET ownership validator.
        self.state(value,recovery=True)
        pending=self.read(self.handler.generations/"pending.json")
        receipt=pending.get("nvmeRuntimeIdentity")if isinstance(pending,dict)else None
        scope={key:value[key]for key in("instanceUuid","operationUuid","revision")}
        fields={"schemaVersion","kind","scope","bootId","sourceGeneration","sourceConfigurationSha256",
            "sourceBindings","targetBindings","identities","checkpointSha256"}
        empty={"subsystems":[],"namespaces":[],"hosts":[]}
        if (not isinstance(receipt,dict)or set(receipt)!=fields or type(receipt["schemaVersion"])is not int or receipt["schemaVersion"]!=1
                or receipt["kind"]!="NVMEOF_SOURCE_RUNTIME_IDENTITY"or receipt["scope"]!=scope
                or receipt["bootId"]!=value["expectedBootId"]or receipt["sourceGeneration"]!=value["sourceGeneration"]
                or receipt["sourceConfigurationSha256"]!=value["sourceConfigurationSha256"]
                or receipt["sourceBindings"]!=empty or receipt["identities"]!=[]
                or not isinstance(receipt["targetBindings"],dict)or set(receipt["targetBindings"])!=set(empty)
                or not isinstance(receipt["targetBindings"]["subsystems"],list)or not receipt["targetBindings"]["subsystems"]
                or hashlib.sha256(json.dumps({key:item for key,item in receipt.items()if key!="checkpointSha256"},
                    sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()!=receipt["checkpointSha256"]):
            raise ValueError("LOCAL absent NVMe source lacks its exact owned TARGET receipt")
        source=record["sourceDesiredState"]
        if (not isinstance(source,dict)or set(source)!=set(PendingNfsGeneration.paths)or source["nvmeof-subsystems.json"]is not None
                or any(row is not None and not isinstance(row,dict)for row in source.values())
                or hashlib.sha256(json.dumps(source,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()!=value["sourceConfigurationSha256"]):
            raise ValueError("LOCAL absent NVMe source frozen declaration changed")
        applied=self.command(("nvmeof","subsystem","apply","/dev/stdin"),{"instanceUuid":scope["instanceUuid"],"enabled":False,"subsystems":[]})
        if applied.get("success")is not True:raise ValueError("LOCAL absent NVMe owned TARGET cleanup did not verify")
        # The empty adapter publishes its normal canonical file. Restore only
        # the exact frozen seven-file SOURCE, preserving its original absence.
        restored=self.command(("operation","generation","restore","/dev/stdin"),
            {**scope,"previousGeneration":value["sourceGeneration"],"configurationDesiredState":source})
        if (restored.get("success")is not True or restored.get("canonicalRestored")is not True
                or restored.get("generationAdvanced")is not False or restored.get("pendingOperationUuid")!=scope["operationUuid"]
                or restored.get("configurationSha256")!=value["sourceConfigurationSha256"]):
            raise ValueError("LOCAL absent NVMe canonical SOURCE did not verify")
        self.state(value,recovery=True)

    def replay_auth(self,value):
        scope=self.request(value,"replay-local-source-auth");self.state(value,recovery=True)
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
        selected=select_restore_domains(payload,domains)
        selected=dict(selected);selected.pop("posixPolicies",None);selected.pop("sourceConfigurationSha256",None)
        if "NVMEOF"in domains and desired is None:
            if payload.get("nvmeHosts"):
                raise ValueError("LOCAL source absent NVMe cannot carry protected host keys")
            # Check this before the normal empty adapter can persist a vault:
            # absent SOURCE keys never authorize overwriting a new private file.
            for path,item in selected["files"].items():
                if item.get("absent")is True and os.path.lexists(path):
                    raise ValueError("LOCAL absent auth source cannot erase unproven target credentials")
            paths=(LOCAL_SOURCE_NVME_ROOT/"subsystems",LOCAL_SOURCE_NVME_ROOT/"ports")
            if any(os.path.lexists(path)and(path.is_symlink()or any(path.iterdir()))for path in paths):
                self.cleanup_absent_nvme_source(value,record)
            for path in paths:
                if os.path.lexists(path)and(path.is_symlink()or any(path.iterdir())):
                    raise ValueError("LOCAL absent NVMe source has unproven runtime targets")
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
