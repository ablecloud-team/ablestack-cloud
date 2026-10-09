import base64,contextlib,copy,fcntl,hashlib,json,os,sys,unittest,uuid
from pathlib import Path
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import TestStorageSmbCurrentRetention as fixture
import smb_source_checkpoint as module
import identity_capsule as capsule
import config_generation as generation

class StorageSmbSourceCheckpointTest(unittest.TestCase):
    public=fixture.StorageSmbCurrentRetentionTest.public
    pem=fixture.StorageSmbCurrentRetentionTest.pem
    empty_payload=fixture.StorageSmbCurrentRetentionTest.empty_payload
    collect=fixture.StorageSmbCurrentRetentionTest.collect
    read=fixture.StorageSmbCurrentRetentionTest.read
    databases=fixture.StorageSmbCurrentRetentionTest.databases
    create_sid=fixture.StorageSmbCurrentRetentionTest.create_sid
    add_long_distinct_namespace=fixture.StorageSmbCurrentRetentionTest.add_long_distinct_namespace
    close_lock=fixture.StorageSmbCurrentRetentionTest.close_lock
    units=fixture.StorageSmbCurrentRetentionTest.units
    command=fixture.StorageSmbCurrentRetentionTest.command
    run_command=fixture.StorageSmbCurrentRetentionTest.run_command

    def setUp(self):
        fixture.StorageSmbCurrentRetentionTest.setUp(self)
        self.add_long_distinct_namespace()
        self.native=module.SmbSourceCheckpoint(handler=self.handler,command=self.local_command,root=self.root/"local-source")
        self.native.boot=lambda:self.boot;self.native.reader=self.read
        self.native.unit_authority.units=self.units
        self.native.collector=lambda names,hosts,**kwargs:{**self.collect(names),"posixPolicies":kwargs.get("posix_policies",{})}
        self.native.posix_collector=lambda desired,instance:{}
        self.native.observe=self.observation
        self.native.stopped_observation=lambda:self.observation({},stopped=True)
        self.native.importer=self.atomic_import
        self.active_units={row["unit"]for row in self.units_value}
        self.starts=[];self.handler.run=self.local_run
        self.request={**self.scope,"localCheckpointUuid":str(uuid.UUID(bytes=hashlib.md5(("local-source-checkpoint:"+self.scope["operationUuid"]).encode()).digest(),version=3)),
            "sourceGeneration":self.current,"sourceConfigurationSha256":self.current["configurationSha256"],"expectedBootId":self.boot}
        self.export_request={**self.request,"publicKey":self.public(self.newkey),"names":[],"nvmeHosts":[],"authReplayDomains":[]}
        self.signals=[]
        self.pin=patch.object(self.handler,"open_master_handles",side_effect=lambda rows:{row["pid"]:os.open("/dev/null",os.O_RDONLY)for row in rows})
        self.pin.start();self.addCleanup(self.pin.stop)
        self.signal=patch.object(module.signal,"pidfd_send_signal",side_effect=self.observe_signal)
        self.signal.start();self.addCleanup(self.signal.stop)
        self.native.start_grant=lambda scope,unit:contextlib.nullcontext()

    def local_command(self,args,payload=None):
        if args==("operation","generation","frozen","/dev/stdin"):return self.gen.frozen(payload)
        value=self.command(args,payload)
        if args==("operation","generation","status"):value["bootId"]=self.boot
        if args==("operation","generation","render-status"):value["bootHeld"]=False
        return value

    def observation(self,value,stopped=False,allow_missing=False,require_empty=True):
        if stopped and self.active_units:raise ValueError("fixture still owns active SMB acceptors")
        units=[{**row,"lockingDatabasesAligned":True}for row in self.units_value if row["unit"]in self.active_units]
        owners=[{"unit":"smbd.service","tcpReady":True}]if "smbd.service"in self.active_units else []
        return {"databases":self.databases(),"units":copy.deepcopy(units),"ownedEndpoints":owners,
            "sessionsVerifiedEmpty":True,"configurationSha256":hashlib.sha256(self.read("/etc/samba/smb.conf")[0]).hexdigest()}

    def observe_signal(self,fd,signal,*args):
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertEqual("QUIESCING",journal["phase"])
        self.assertEqual(self.public(self.newkey),journal["publicKey"])
        self.signals.append(signal)

    def local_run(self,args):
        if args[:2]==["systemctl","stop"]:
            self.calls.append(args);self.active_units.discard(args[2]);return ""
        if args[:2]==["systemctl","start"]:
            self.calls.append(args);self.starts.append(args[2]);self.active_units.add(args[2]);return ""
        return self.run_command(args)

    def atomic_import(self,payload):
        files={str(self.private[label]):payload["files"][path]for label,path in module.IDENTITY_DATABASES.items()}
        with patch.object(capsule,"FILES",set(files)),patch.object(capsule,"LIVE_TDB_FILES",set(files)):
            return capsule.restore({"schemaVersion":1,"files":files,"accounts":{}})

    def imported_request(self,result):
        return {**self.request,"capsule":result["capsule"],"credentialPrivateKey":self.pem(self.newkey),
            "localSourceCheckpointReference":result["localSourceCheckpointReference"],"deferNvmeReplay":True,"restoreDomains":["SMB"]}

    def test_real_crypto_two_namespace_opaque_snapshot_and_no_current_contract_change(self):
        result=self.native.export(self.export_request)
        self.assertTrue(result["sourceSmbResumed"])
        record=self.native.read(self.native.path(self.request,"cipher"))
        identity=self.native.decode(self.imported_request(result),record)
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})
        self.assertEqual(1,self.counter)
        self.assertEqual(2,len(self.starts))
        with self.assertRaises(ValueError):capsule.validate_payload(capsule.decrypt(result["capsule"],self.pem(self.newkey),self.scope["instanceUuid"]+":"+self.scope["operationUuid"]))
        self.assertEqual(self.collect([])["files"],identity["files"])

    def test_response_loss_resumed_cache_does_not_collect_stop_or_start_again(self):
        first=self.native.export(self.export_request);counts=(self.counter,len(self.signals),len(self.starts))
        second=self.native.export(copy.deepcopy(self.export_request))
        self.assertEqual(first,second);self.assertEqual(counts,(self.counter,len(self.signals),len(self.starts)))

    def test_named_fd9_without_original_flock_rejects_before_intent(self):
        fcntl.flock(9,fcntl.LOCK_UN)
        try:
            with self.assertRaisesRegex(ValueError,"actual FLOCK"):self.native.export(self.export_request)
            self.assertFalse(self.native.root.exists());self.assertFalse(self.signals)
        finally:fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)

    def test_scope_boot_source_or_maintenance_change_rejects_before_signal(self):
        for field in ("revision","expectedBootId","sourceConfigurationSha256","localCheckpointUuid"):
            request=copy.deepcopy(self.export_request)
            request[field]=request[field]+1 if field=="revision"else str(uuid.uuid4())if field!="sourceConfigurationSha256"else "f"*64
            with self.subTest(field=field),self.assertRaises(ValueError):self.native.export(request)
        self.assertFalse(self.signals);self.assertFalse(self.native.root.exists())

    def test_bad_original_key_kind_or_reference_rejects_before_import_effect(self):
        result=self.native.export(self.export_request)
        count=len(self.signals)
        for field in ("credentialPrivateKey","localSourceCheckpointReference","restoreDomains"):
            request=self.imported_request(result)
            request[field]=self.pem(self.key)if field=="credentialPrivateKey"else {}if field=="localSourceCheckpointReference"else ["SMB","NVMEOF"]
            with self.subTest(field=field),self.assertRaises(ValueError):self.native.import_source(request)
        self.assertEqual(count,len(self.signals))

    def test_real_atomic_restore_new_inode_provenance_and_original_bytes(self):
        result=self.native.export(self.export_request);before={name:path.stat().st_ino for name,path in self.private.items()}
        for path in self.private.values():path.write_bytes(b"SYNTHETIC TARGET MUTATION")
        observed=self.native.import_source(self.imported_request(result))
        self.assertTrue(observed["sourceIdentityRestored"]);self.assertTrue(observed["sourceRuntimeVerified"])
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})
        after={name:path.stat().st_ino for name,path in self.private.items()}
        self.assertTrue(all(after[name]!=before[name]for name in after))
        record=self.native.read(self.native.path(self.request,"cipher"));journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertNotEqual(record["stoppedDatabases"],journal["importedStoppedDatabases"])
        counts=(len(self.starts),len(self.signals))
        self.assertTrue(self.native.import_source(self.imported_request(result))["sourceIdentityRestored"])
        self.assertEqual(counts,(len(self.starts),len(self.signals)))

    def test_failure_keeps_intent_pending_and_retry_same_key_finishes(self):
        saved=self.native.encryptor
        self.native.encryptor=lambda *args:(_ for _ in ()).throw(ValueError("fixture encryption fault"))
        with self.assertRaises(ValueError):self.native.export(self.export_request)
        self.assertEqual("RECOVERY_REQUIRED",self.native.read(self.native.path(self.request,"journal"))["phase"])
        self.assertTrue(self.gen.pending.exists());self.assertFalse(self.starts)
        self.native.encryptor=saved
        self.assertTrue(self.native.export(self.export_request)["sourceRuntimeVerified"])

    def test_real_named_fd9_pending_smb_unit_grant_is_bound_and_removed(self):
        from pending_nfs_authorization import PendingNfsAuthorization
        class PublicStatus:
            def status(self):return {"bootHeld":False,"current":None,"activation":None}
        config=self.root/"samba";config.mkdir();(config/"smb.conf").write_bytes(b"PUBLIC FIXTURE CONFIG")
        (config/"smb.conf").chmod(0o600)
        auth=PendingNfsAuthorization(generation=self.gen,rendered=PublicStatus(),maintenance=PublicStatus(),
            root=self.root/"grants",configuration_root=config,protocol="SMB")
        scope={k:self.request[k]for k in("instanceUuid","operationUuid","revision")}
        with auth.grant({"instanceUuid":scope["instanceUuid"],"operationScope":scope},"smbd.service"):
            grant=json.loads(auth.path.read_text());self.assertEqual("NATIVE_PENDING_SMB_APPLY",grant["mode"])
            self.assertEqual(os.getpid(),grant["pid"]);self.assertEqual(scope,grant["scope"])
        self.assertFalse(auth.path.exists())
        with self.assertRaises(ValueError):
            with auth.grant({"instanceUuid":scope["instanceUuid"],"operationScope":scope},"nfs-server.service"):pass

    def test_inode_foreign_replacement_after_source_resume_rejects_cached_success(self):
        self.native.export(self.export_request)
        target=self.private["SECRETS"];replacement=target.with_suffix(".replacement")
        replacement.write_bytes(target.read_bytes());replacement.chmod(0o600);os.replace(replacement,target)
        before=(len(self.starts),len(self.signals),self.counter)
        with self.assertRaises(ValueError):self.native.export(self.export_request)
        self.assertEqual(before,(len(self.starts),len(self.signals),self.counter))

    def test_inactive_source_is_not_started_and_posix_receipts_are_kept(self):
        self.active_units.clear()
        result=self.native.export(self.export_request)
        self.assertEqual(0,result["priorActiveUnitCount"]);self.assertFalse(self.starts);self.assertFalse(self.signals)
        record=self.native.read(self.native.path(self.request,"cipher"))
        identity=self.native.decode(self.imported_request(result),record)
        self.assertEqual({},identity["posixPolicies"])
        self.assertEqual(self.current["configurationSha256"],identity["sourceConfigurationSha256"])

    def test_original_auth_domain_cannot_expand_and_iscsi_request_has_no_nvme_field(self):
        request={**self.request,"capsule":{},"credentialPrivateKey":"RAM ONLY","localSourceCheckpointReference":{},"restoreDomains":["ISCSI"]}
        self.assertEqual(self.request["localCheckpointUuid"],self.native.request(request,"replay-local-source-auth")["localCheckpointUuid"])
        request["nvmeDesired"]={}
        with self.assertRaises(ValueError):self.native.request(request,"replay-local-source-auth")

    def test_actual_embedded_dispatcher_export_import_and_status(self):
        import ast,io
        from contextlib import redirect_stdout
        program=fixture.CLI.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(program);definitions=[]
        for node in tree.body:
            if isinstance(node,(ast.Import,ast.ImportFrom,ast.FunctionDef,ast.ClassDef)):definitions.append(node)
            elif isinstance(node,ast.Assign)and not any(isinstance(target,ast.Name)and target.id in("request","action","scope")for target in node.targets):definitions.append(node)
        namespace={"__name__":"actual_local_source_codec_fixture"}
        exec(compile(ast.Module(body=definitions,type_ignores=[]),"<actual-PYIDENTITY-definitions>","exec"),namespace)
        instance=namespace["SmbSourceCheckpoint"](fixture.CLI,handler=self.handler,command=self.local_command)
        instance.__dict__.update(self.native.__dict__)
        instance.encryptor=namespace["encrypt"];instance.decryptor=namespace["decrypt"];instance.validator=namespace["validate_payload"]
        namespace["SmbSourceCheckpoint"]=lambda *args,**kwargs:instance
        dispatch=next(node for node in tree.body if isinstance(node,ast.If)and isinstance(node.test,ast.Compare)
            and isinstance(node.test.left,ast.Name)and node.test.left.id=="action")
        def invoke(action,value):
            namespace.update(action=action,request=value,scope=self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
            output=io.StringIO()
            with patch.object(sys,"argv",["actual-script",action,"/dev/stdin",str(fixture.CLI)]),redirect_stdout(output):
                exec(compile(ast.Module(body=[dispatch],type_ignores=[]),"<actual-PYIDENTITY-dispatch>","exec"),namespace)
            return json.loads(output.getvalue())
        result=invoke("export-local-source",self.export_request)
        self.assertTrue(result["sourceRuntimeVerified"])
        self.assertTrue(invoke("import-local-source",self.imported_request(result))["sourceIdentityRestored"])
        self.assertTrue(invoke("local-source-status",self.request)["sourceIdentityRestoreSupported"])

    def test_real_bash_masked_stdin_status_matches_sealed_fd_and_rejects_foreign_scope(self):
        import shlex,subprocess
        root=self.root/'readonly-cli';root.mkdir(mode=0o700)
        config=root/'config';config.mkdir(mode=0o700)
        state=root/'generation';state.mkdir(mode=0o700)
        store=generation.Generation(state,config);digest=store.digest()
        scope={key:self.request[key]for key in module.LOCAL_SOURCE_SCOPE}
        scope['revision']=1
        value={**scope,'sourceGeneration':{},'sourceConfigurationSha256':digest,
            'expectedBootId':Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
        generation.atomic_json(store.current,{})
        generation.atomic_json(store.pending,{**{key:scope[key]for key in('instanceUuid','operationUuid','revision')},
            'phase':'PREPARED','previous':{},'beforeSha256':digest})
        environment={**os.environ,'ABLESTACK_STORAGE_GENERATION_DIR':str(state),
            'ABLESTACK_STORAGE_CONFIGURATION_ROOT':str(config),'ABLESTACK_STORAGE_RENDERED_GENERATIONS':str(root/'render'),
            'ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR':str(root/'maintenance')}
        environment.pop('ABLESTACK_STORAGE_WRITER_LOCK_FD',None)
        before={str(path):path.read_bytes()for path in root.rglob('*')if path.is_file()}
        command=shlex.quote(str(fixture.CLI))+' smb identity local-source-status /dev/stdin'
        public=subprocess.run(['bash','-lc',command],input=json.dumps(value),capture_output=True,text=True,env=environment,timeout=25)
        self.assertEqual(0,public.returncode,public.stderr)
        outcome=json.loads(public.stdout);self.assertTrue(outcome['success']);self.assertFalse(outcome['journalPresent'])
        fd=os.memfd_create('public-status-fixture',os.MFD_ALLOW_SEALING|os.MFD_CLOEXEC)
        os.write(fd,json.dumps(value).encode());os.lseek(fd,0,0)
        fcntl.fcntl(fd,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
        safe=fcntl.fcntl(fd,fcntl.F_DUPFD_CLOEXEC,16);os.close(fd)
        try:
            reply=subprocess.run(['bash','-lc',shlex.quote(str(fixture.CLI))+' smb identity local-source-status /proc/self/fd/'+str(safe)],
                capture_output=True,text=True,env=environment,pass_fds=(safe,),timeout=25)
            self.assertEqual(0,reply.returncode,reply.stderr);self.assertEqual(outcome,json.loads(reply.stdout))
        finally:os.close(safe)
        foreign={**value,'sourceConfigurationSha256':'f'*64}
        rejected=subprocess.run(['bash','-lc',command],input=json.dumps(foreign),capture_output=True,text=True,env=environment,timeout=25)
        self.assertEqual(1,rejected.returncode);self.assertEqual('LOCAL_SOURCE_CHECKPOINT_REJECTED',json.loads(rejected.stdout)['errorCode'])
        self.assertEqual(before,{str(path):path.read_bytes()for path in root.rglob('*')if path.is_file()})

    def test_managed_bash_launcher_historical_entry_parent_and_manifest_are_bound(self):
        runtime=self.root/'runtime';runtime.mkdir(mode=0o700);(runtime/'releases').mkdir(mode=0o700)
        release=runtime/'releases'/'signed-old';release.mkdir(mode=0o700)
        entry=release/'ablestack-storagectl';entry.write_text(fixture.CLI.read_text());entry.chmod(0o755)
        manifest={'bundleVersion':'signed-old','keyId':'fixture-trusted-key','files':[{'path':'ablestack-storagectl','sha256':hashlib.sha256(entry.read_bytes()).hexdigest(),
            'mode':'0755','owner':'root','group':'root'}]}
        generation.atomic_json(release/'manifest.json',manifest)
        self.native.runtime_root=runtime
        installs=self.root/'installations';installs.mkdir(mode=0o700);tx=installs/'runtime-known';tx.mkdir(mode=0o700)
        generation.atomic_json(tx/'manifest.json',manifest)
        installation={'transactionId':tx.name,'bundleVersion':release.name,'releasePath':str(release),'phase':'COMPLETE',
            'keyId':manifest['keyId'],'manifestSha256':hashlib.sha256((tx/'manifest.json').read_bytes()).hexdigest(),'archiveSha256':'a'*64}
        generation.atomic_json(tx/'state.json',installation);self.native.installation_root=installs
        procroot=self.root/'launcher-proc';procroot.mkdir();self.handler.process_root=procroot
        unit='ablestack-storage-smb@'+'a'*24+'.service';parent=procroot/'111';child=procroot/'222'
        parent.mkdir();child.mkdir();(parent/'fd').mkdir()
        def procstat(pid,name,ppid,ticks):return str(pid)+' ('+name+') '+' '.join(['S',str(ppid)]+['0']*17+[ticks])
        (parent/'stat').write_text(procstat(111,'bash',1,'100'));(child/'stat').write_text(procstat(222,'smbd',111,'101'))
        (parent/'status').write_text('Uid:\t0\t0\t0\t0\n')
        cgroup='0::/system.slice/'+unit+'\n';(parent/'cgroup').write_text(cgroup);(child/'cgroup').write_text(cgroup)
        argv=['bash','/usr/local/bin/ablestack-storagectl','smb','endpoint-run','a'*24]
        (parent/'cmdline').write_bytes(bytes([0]).join(x.encode()for x in argv)+bytes([0]))
        (parent/'exe').symlink_to('/usr/bin/bash');(parent/'fd/255').symlink_to(entry)
        original=self.native.reader;bash=Path('/usr/bin/bash').read_bytes();bashmeta=Path('/usr/bin/bash').stat()
        self.native.reader=lambda path,*args:(bash,bashmeta)if path=='/usr/bin/bash'else((hashlib.md5(bash).hexdigest()+'  bin/bash\n').encode(),entry.stat())if path=='/var/lib/dpkg/info/bash.md5sums'else capsule.regular_file(path)
        properties={'MainPID':'111','Slice':'system.slice','ControlGroup':'/system.slice/'+unit}
        self.handler.run=lambda args:properties[args[3].split('=',1)[1]]
        master={'pid':222,'startTicks':'101'}
        good=self.native.launcher_authority(unit,master)
        self.assertEqual(111,good['pid']);self.assertEqual('100',good['startTicks'])
        self.assertEqual(str(entry),good['loadedScript']['path'])
        self.assertEqual(hashlib.sha256(entry.read_bytes()).hexdigest(),good['loadedScript']['sha256'])
        # CODE activation may advance both current and previous pointers. The
        # already opened, installed historical ENTRY remains bound to its inode.
        (runtime/'current').symlink_to(release);(runtime/'previous').symlink_to(release)
        self.assertEqual(good,self.native.launcher_authority(unit,master))
        template_slice='system-'+'ablestack-storage-smb'.replace('-','\\x2d')+'.slice'
        nested='/system.slice/'+template_slice+'/'+unit
        properties.update(Slice=template_slice,ControlGroup=nested)
        (parent/'cgroup').write_text('0::'+nested+'\n');(child/'cgroup').write_text('0::'+nested+'\n')
        self.assertEqual(template_slice,self.native.launcher_authority(unit,master)['slice'])
        for name,value in [('Slice','foreign.slice'),('ControlGroup',nested+'/foreign'),('ControlGroup',nested.replace(unit,unit+'-lookalike'))]:
            old=properties[name];properties[name]=value
            with self.assertRaisesRegex(ValueError,'cgroup changed'):self.native.launcher_authority(unit,master)
            properties[name]=old
        properties.update(Slice='system.slice',ControlGroup='/system.slice/'+unit)
        (parent/'cgroup').write_text(cgroup);(child/'cgroup').write_text(cgroup)
        cases=[(parent/'cmdline',b'bash\0foreign-script\0'),(child/'stat',procstat(222,'smbd',999,'101').encode()),
            (child/'cgroup',b'0::/system.slice/foreign.service\n'),(parent/'status',b'Uid:\t1\t1\t1\t1\n'),
            (release/'manifest.json',json.dumps({**manifest,'bundleVersion':'foreign'}).encode()),
            (tx/'state.json',json.dumps({**installation,'phase':'RECEIVED'}).encode()),
            (tx/'manifest.json',json.dumps({**manifest,'keyId':'foreign-key'}).encode())]
        for path,bad in cases:
            saved=path.read_bytes();path.write_bytes(bad)
            with self.subTest(path=path.name),self.assertRaises(ValueError):self.native.launcher_authority(unit,master)
            path.write_bytes(saved)
        receipt=tx/'state.json';hidden=tx/'hidden-state.json';receipt.rename(hidden)
        with self.assertRaisesRegex(ValueError,'installation is absent'):self.native.launcher_authority(unit,master)
        hidden.rename(receipt)
        original_link=module.os.readlink
        with patch.object(module.os,'readlink',side_effect=lambda path:str(entry)+' (deleted)'if Path(path)==parent/'fd/255'else original_link(path)):
            with self.assertRaisesRegex(ValueError,'loaded script is foreign'):self.native.launcher_authority(unit,master)
        replacement=self.root/'replacement-public-script';replacement.write_bytes(entry.read_bytes());replacement.chmod(0o755)
        (parent/'fd/255').unlink();(parent/'fd/255').symlink_to(replacement)
        with patch.object(module.os,'readlink',side_effect=lambda path:str(entry)if Path(path)==parent/'fd/255'else original_link(path)):
            with self.assertRaisesRegex(ValueError,'script FD changed'):self.native.launcher_authority(unit,master)
        (parent/'fd/255').unlink();(parent/'fd/255').symlink_to(entry)
        (parent/'stat').write_text(procstat(111,'bash',1,'999'))
        self.assertNotEqual(good,self.native.launcher_authority(unit,master))
        (parent/'stat').write_text(procstat(111,'bash',1,'100'))
        saved=entry.read_bytes();entry.write_bytes(saved.replace(b'os.execv(args[0], args)',b'os.execv(args[0], args) # foreign'))
        changed={**manifest,'files':[{**manifest['files'][0],'sha256':hashlib.sha256(entry.read_bytes()).hexdigest()}]}
        generation.atomic_json(release/'manifest.json',changed)
        generation.atomic_json(tx/'manifest.json',changed)
        generation.atomic_json(tx/'state.json',{**installation,'manifestSha256':hashlib.sha256((tx/'manifest.json').read_bytes()).hexdigest()})
        with self.assertRaisesRegex(ValueError,'endpoint body changed'):self.native.launcher_authority(unit,master)
        self.handler.run=lambda args:'222';self.assertIsNone(self.native.launcher_authority(unit,master))
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_post_pidfd_launcher_identity_change_is_rejected_before_signal(self):
        unit='ablestack-storage-smb@'+'b'*24+'.service'
        master={'unit':unit,'pid':222,'startTicks':'101','managedLauncher':{'pid':111,'startTicks':'100'}}
        before={**self.observation(self.request),'units':[master]}
        self.native.observe=lambda *args,**kwargs:copy.deepcopy(before)
        proc=self.handler.process_root/str(master['pid']);proc.mkdir(parents=True,exist_ok=True)
        (proc/'stat').write_text('222 (smbd) '+' '.join(['S','111']+['0']*17+['101']))
        journal={'scope':{key:self.request[key]for key in module.LOCAL_SOURCE_SCOPE},'prior':before,'phase':'INTENT_DURABLE'}
        self.native.launcher_authority=lambda *args:{'pid':111,'startTicks':'999'}
        with self.assertRaisesRegex(ValueError,'launcher changed after pidfd pin'):self.native.stop(self.request,journal)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_first_empty_identity_source_remains_absent_without_sam_initialization(self):
        self.active_units.clear();self.active=False
        for path in self.private.values():path.unlink()
        self.handler.database_identity=lambda:{label:{"path":path,"present":False}for label,path in module.IDENTITY_DATABASES.items()}
        self.native.collector=lambda *args,**kwargs:{**self.empty_payload(),"posixPolicies":{}}
        generation.atomic_json(self.gen.current,{})
        self.request["sourceGeneration"]={};self.request["revision"]=1
        generation.atomic_json(self.gen.pending,{**{k:self.request[k]for k in("instanceUuid","operationUuid","revision")},
            "phase":"PREPARED","previous":{},"beforeSha256":self.current["configurationSha256"]})
        self.export_request.update(self.request)
        self.native.observe=module.SmbSourceCheckpoint.observe.__get__(self.native)
        self.native.stopped_observation=lambda:{"databases":self.handler.database_identity(),"units":[],"ownedEndpoints":[],
            "sessionsVerifiedEmpty":True,"configurationSha256":hashlib.sha256(b"PUBLIC FIXTURE CONFIG").hexdigest()}
        self.native.reader=lambda path,*args:(b"PUBLIC FIXTURE CONFIG",self.lock_path.stat())if path=="/etc/samba/smb.conf"else capsule.regular_file(path)
        result=self.native.export(self.export_request)
        self.assertEqual(0,result["priorActiveUnitCount"]);self.assertFalse(self.starts);self.assertFalse(self.signals)
        self.assertTrue(all(not path.exists()for path in self.private.values()))

    def test_renderer_same_key_original_ref_reuse_and_mixed_role_rejection(self):
        result=self.native.export(self.export_request)
        reader=module.SmbSourceCheckpoint.for_renderer(fixture.CLI);reader.root=self.native.root
        stage={**self.scope,"localSourceCheckpointReference":result["localSourceCheckpointReference"],"checkpointPublicKey":self.public(self.newkey)}
        current=self.local_command(("operation","generation","status"))
        saved=reader.rendered_checkpoint(stage,current)
        identity=reader.renderer_identity(saved,self.pem(self.newkey),result["localSourceCheckpointReference"],capsule.decrypt,capsule.validate_payload)
        self.assertEqual(self.current["configurationSha256"],identity["sourceConfigurationSha256"])
        for mutate in ("key","ref","scope"):
            changed=copy.deepcopy(stage)
            if mutate=="key":changed["checkpointPublicKey"]=self.public(self.key)
            if mutate=="ref":changed["localSourceCheckpointReference"]["sha256"]="0"*64
            if mutate=="scope":changed["operationUuid"]=str(uuid.uuid4())
            with self.subTest(mutate=mutate),self.assertRaises((ValueError,FileNotFoundError)):reader.rendered_checkpoint(changed,current)

    def update_source(self):
        self.gen.replace_desired(self.desired);self.current["configurationSha256"]=self.gen.digest()
        generation.atomic_json(self.gen.current,self.current)
        generation.atomic_json(self.gen.root/(self.current["operationUuid"]+".json"),{**self.current,"phase":"VERIFIED","desired":self.desired})
        pending=generation.read_json(self.gen.pending);pending["previous"]=self.current;pending["beforeSha256"]=self.current["configurationSha256"]
        generation.atomic_json(self.gen.pending,pending)
        self.request.update(sourceGeneration=self.current,sourceConfigurationSha256=self.current["configurationSha256"])
        self.export_request.update(self.request)

    def test_real_posix_receipt_source_is_encrypted_without_policy_rewrite(self):
        from posix_policy_receipt import PosixPolicyReceipt
        from posix_receipt_transfer import PosixReceiptTransfer
        policy=str(uuid.uuid4());volume=str(uuid.uuid4());fs=str(uuid.uuid4())
        identity={"filesystemUuid":fs,"device":2064,"inode":12345,"effectiveUid":0,"effectiveGid":0,"effectiveMode":"0770","aclSha256":"a"*64}
        request={"uuid":policy,"instanceUuid":self.scope["instanceUuid"],"volumeUuid":volume,
            "volumeMountPath":"/srv/ablestack-storage/volumes/"+volume,"relativePath":"leaf","revision":2,
            "expectedDirectoryIdentity":identity,"config":{"directoryMode":"0770","applyOwner":True,"ownerUid":0,"ownerGid":0}}
        row={"request":request,"config":request["config"],"effective":{"directoryIdentity":identity}}
        receipts=PosixPolicyReceipt(self.root/"posix");receipts.write(request,row["effective"])
        before=receipts.path(request).read_bytes();transfer=PosixReceiptTransfer(receipts)
        self.desired["posix-directory-policies.json"]={policy:row};self.update_source()
        self.native.posix_collector=lambda desired,instance:transfer.collect(desired,instance,lambda value:row["effective"])
        result=self.native.export(self.export_request);record=self.native.read(self.native.path(self.request,"cipher"))
        decoded=self.native.decode(self.imported_request(result),record)
        self.assertEqual(1,len(decoded["posixPolicies"]));self.assertEqual(row,decoded["posixPolicies"][policy]["canonicalRow"])
        self.assertEqual(before,receipts.path(request).read_bytes())

    def test_pid_replacement_after_pin_has_zero_signals_and_keeps_recovery(self):
        def replaced(rows):
            self.units_value[0]["startTicks"]="FOREIGN_REPLACEMENT"
            return {row["pid"]:os.open("/dev/null",os.O_RDONLY)for row in rows}
        with patch.object(self.handler,"open_master_handles",side_effect=replaced):
            with self.assertRaisesRegex(ValueError,"after pidfd pin"):self.native.export(self.export_request)
        self.assertFalse(self.signals);self.assertFalse(self.starts)
        self.assertEqual("RECOVERY_REQUIRED",self.native.read(self.native.path(self.request,"journal"))["phase"])

    def test_owned_rendered_rollback_requires_exact_pin_and_source_pointer(self):
        result=self.native.export(self.export_request)
        request=self.imported_request(result);request["renderedManifestSha256"]="a"*64
        plain=self.native.command
        record={"scope":self.scope,"phase":"ROLLING_BACK","targetSha256":"a"*64,"previousSha256":"b"*64}
        current={"scope":{k:self.current[k]for k in("instanceUuid","operationUuid","revision")},
            "configurationSha256":self.current["configurationSha256"],"manifestSha256":"b"*64}
        def observed(args,payload=None):
            if args==("operation","generation","render-status"):
                return {"success":True,"bootHeld":True,"activation":record,"current":current}
            return plain(args,payload)
        self.native.command=observed
        self.native.state(request)
        for key,bad in(("targetSha256","c"*64),("previousSha256","d"*64),("phase","ACTIVATING")):
            old=record[key];record[key]=bad
            with self.subTest(key=key),self.assertRaises(ValueError):self.native.state(request)
            record[key]=old
        foreign=copy.deepcopy(record["scope"]);record["scope"]={**foreign,"operationUuid":str(uuid.uuid4())}
        with self.assertRaises(ValueError):self.native.state(request)

    def test_generation_terminal_blocks_a_stopped_checkpoint_and_allows_real_resume(self):
        self.boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.request["expectedBootId"]=self.boot;self.export_request.update(self.request)
        # Generation guard intentionally uses the fixed sibling checkpoint path.
        self.native.root=self.gen.root.parent/"smb-local-source-checkpoints"
        saved=self.native.encryptor;self.native.encryptor=lambda *args:(_ for _ in ()).throw(ValueError("fixture crypto fault"))
        with self.assertRaises(ValueError):self.native.export(self.export_request)
        with self.assertRaisesRegex(ValueError,"owned verified resume"):self.gen.execute("rollback",self.scope)
        self.assertTrue(self.gen.pending.exists());self.assertEqual(self.current,generation.read_json(self.gen.current))
        self.native.encryptor=saved;self.native.export(self.export_request)
        self.gen.execute("rollback",self.scope)
        terminal=self.native.status(self.request)
        self.assertTrue(terminal["sourceRuntimeVerified"]);self.assertFalse(self.gen.pending.exists())

    def test_partial_sigstop_failure_retries_exact_intent_without_tcp_probe(self):
        original=self.handler.sessions
        self.handler.sessions=lambda *args:(_ for _ in ()).throw(ValueError("fixture after-pause failure"))
        with self.assertRaises(ValueError):self.native.export(self.export_request)
        self.assertTrue(self.signals);self.assertFalse(self.starts)
        self.handler.sessions=original
        observe=self.native.observe
        def paused(value,**kwargs):
            if not kwargs.get("stopped")and self.active_units:raise ValueError("paused master cannot answer TCP probe")
            return observe(value,**kwargs)
        self.native.observe=paused
        # Only resumed readiness needs the active observer; the STOP retry does
        # not invoke it. Restore that observer as the start operation completes.
        run=self.handler.run
        def start(args):
            result=run(args)
            if args[:2]==["systemctl","start"]:self.native.observe=observe
            return result
        self.handler.run=start
        self.assertTrue(self.native.export(self.export_request)["sourceRuntimeVerified"])

    def test_cached_import_foreign_same_bytes_inode_is_not_restored_identity(self):
        result=self.native.export(self.export_request);request=self.imported_request(result)
        self.native.import_source(request)
        path=self.private["SECRETS"];copy_path=path.with_suffix(".other")
        copy_path.write_bytes(path.read_bytes());copy_path.chmod(0o600);os.replace(copy_path,path)
        effects=(len(self.signals),len(self.starts))
        with self.assertRaises(ValueError):self.native.import_source(request)
        self.assertEqual(effects,(len(self.signals),len(self.starts)))

    def test_rendered_mixed_auth_replay_uses_only_actual_started_domains(self):
        self.export_request["authReplayDomains"]=["ISCSI","NVMEOF"]
        result=self.native.export(self.export_request);self.native.import_source(self.imported_request(result))
        plain=self.native.command
        activation={"scope":self.scope,"phase":"ROLLING_BACK","targetSha256":"a"*64,"previousSha256":"b"*64,
            "changedDomains":["ISCSI","NVMEOF"],"startedDomains":["ISCSI"]}
        current={"scope":{k:self.current[k]for k in("instanceUuid","operationUuid","revision")},
            "configurationSha256":self.current["configurationSha256"],"manifestSha256":"b"*64}
        self.native.command=lambda args,payload=None:{"success":True,"bootHeld":True,"activation":activation,"current":current}if args==("operation","generation","render-status")else plain(args,payload)
        request={**self.request,"capsule":result["capsule"],"credentialPrivateKey":self.pem(self.newkey),
            "localSourceCheckpointReference":result["localSourceCheckpointReference"],"renderedManifestSha256":"a"*64}
        for started in(["ISCSI"],["NVMEOF"],["ISCSI","NVMEOF"],[]):
            activation["startedDomains"]=started
            value={**request,"restoreDomains":started,**({"nvmeDesired":None}if "NVMEOF"in started else {})}
            with self.subTest(started=started),patch.object(module,"restore_protected",return_value={"success":True,"nvmeRestored":True})as applied:
                if started:
                    self.assertEqual(started,self.native.replay_auth(value)["replayedDomains"])
                else:
                    with self.assertRaises(ValueError):self.native.replay_auth(value)
                    applied.assert_not_called()
        for changed in({"startedDomains":["SMB"]},{"startedDomains":["ISCSI"],"changedDomains":[]},{"startedDomains":None}):
            old=copy.deepcopy(activation);activation.update(changed)
            with patch.object(module,"restore_protected")as applied,self.assertRaises(ValueError):
                self.native.replay_auth({**request,"restoreDomains":["ISCSI"]})
            applied.assert_not_called();activation.clear();activation.update(old)

    def test_original_iscsi_and_nvme_auth_real_crypto_restore_and_bounded_replay(self):
        host="nqn.2026-10.test:source-host"
        desired={"subsystems":[{"uuid":str(uuid.uuid4()),"nqn":"nqn.2026-10.test:source-target","hosts":[{
            "uuid":str(uuid.uuid4()),"principal":host,"config":{"dhChapEnabled":True,"dhChapCtrlEnabled":True}}]}]}
        self.desired["nvmeof-subsystems.json"]=desired;self.update_source()
        self.export_request["authReplayDomains"]=["ISCSI","NVMEOF"]
        self.export_request["nvmeHosts"]=[host]
        originals={"/etc/ablestack-storage/secrets/iscsi-acl-secrets.json":b'{"synthetic":"ORIGINAL_CHAP"}',
                   "/etc/ablestack-storage/secrets/nvmeof-acl-secrets.json":b'{"synthetic":"ORIGINAL_DHHC"}'}
        localfiles={path:self.root/("block-"+str(n)+".json")for n,path in enumerate(originals)}
        for path,target in localfiles.items():target.write_bytes(originals[path]);target.chmod(0o600)
        reader=self.native.reader
        self.native.reader=lambda path,*args:capsule.regular_file(localfiles[path])if path in localfiles else reader(path,*args)
        collect=self.native.collector
        def source_payload(*args,**kwargs):
            value=collect(*args,**kwargs)
            for path,data in originals.items():value["files"][path]={"data":base64.b64encode(data).decode(),"sha256":hashlib.sha256(data).hexdigest(),"mode":0o600,"uid":0,"gid":0}
            value["nvmeHosts"]={host:{"dhchap_key":"DHHC-1:synthetic-original-host","dhchap_ctrl_key":"DHHC-1:synthetic-original-controller"}}
            return value
        self.native.collector=source_payload
        result=self.native.export(self.export_request);self.native.import_source(self.imported_request(result))
        for target in localfiles.values():target.write_bytes(b"SYNTHETIC TARGET AUTH")
        replay={**self.request,"capsule":result["capsule"],"credentialPrivateKey":self.pem(self.newkey),
            "localSourceCheckpointReference":result["localSourceCheckpointReference"],"restoreDomains":["ISCSI","NVMEOF"],"nvmeDesired":desired}
        effects=[]
        def transport(argv,**kwargs):
            assert argv==["/usr/local/bin/ablestack-storagectl","nvmeof","subsystem","apply","/dev/stdin"]
            assert kwargs["pass_fds"]==(9,)
            actual=json.loads(kwargs["input"]);secret=actual["subsystems"][0]["hosts"][0]["secrets"]
            assert secret["dhChapKey"]=="DHHC-1:synthetic-original-host"
            assert secret["dhChapCtrlKey"]=="DHHC-1:synthetic-original-controller"
            effects.append("protected-existing-source-replay")
            return type("Reply",(),{"returncode":0,"stdout":'{"success":true}'})()
        actual_restore=capsule.restore_protected
        def block_restore(value,nvme):
            mapped={str(localfiles[path]):row for path,row in value["files"].items()if path in localfiles}
            with patch.object(capsule,"FILES",set(mapped)),patch.object(capsule.subprocess,"run",side_effect=transport):
                return actual_restore({**value,"files":mapped,"accounts":{}},nvme)
        with patch.object(module,"restore_protected",side_effect=block_restore):
            observed=self.native.replay_auth(replay)
        self.assertTrue(observed["sourceAuthReplayed"]);self.assertTrue(observed["nvmeRestored"])
        self.assertFalse(observed["smbIdentityChanged"])
        for path,target in localfiles.items():self.assertEqual(originals[path],target.read_bytes())
        self.assertEqual(1,len(effects))
        for bad in("foreignNqn","downgrade","domain"):
            changed=copy.deepcopy(replay)
            if bad=="foreignNqn":changed["nvmeDesired"]["subsystems"][0]["hosts"][0]["principal"]="nqn.2026-10.test:foreign"
            if bad=="downgrade":changed["nvmeDesired"]["subsystems"][0]["hosts"][0]["config"]["dhChapEnabled"]=False
            if bad=="domain":changed["restoreDomains"]=["NVMEOF"]
            with self.subTest(bad=bad),patch.object(module,"restore_protected")as applied,self.assertRaises(ValueError):
                self.native.replay_auth(changed)
            applied.assert_not_called()

if __name__=="__main__":unittest.main()
