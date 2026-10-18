import base64,contextlib,copy,fcntl,hashlib,json,os,stat,sys,unittest,uuid
from pathlib import Path
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import TestStorageSmbCurrentRetention as fixture
import smb_source_checkpoint as module
import identity_capsule as capsule
import config_generation as generation

INITIAL_STOP_475_ORIGINAL_METHODS={'unconfigured_stop_bridge_cli': '    def unconfigured_stop_bridge_cli(self,current,original):\n        """Apply one closed source transformation; all other CLI bytes stay exact."""\n        import ast\n        names={"stable_sessions_empty","unconfigured_stop_bridge_cli","unconfigured_stop_empty_identity","unconfigured_stop_bridge"}\n        pins=LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256\n        if set(pins)!=names:raise ValueError("LOCAL unconfigured bridge source pins differ")\n        def section(raw,label):\n            begin=("<<\'"+label+"\'\\n").encode();end=("\\n"+label+"\\n").encode()\n            if raw.count(begin)!=1 or raw.count(end)!=1:raise ValueError("LOCAL unconfigured bridge section differs")\n            start=raw.index(begin)+len(begin);finish=raw.index(end,start)\n            return start,finish,raw[start:finish]\n        def methods(raw):\n            tree=ast.parse(raw.decode());classes=[node for node in ast.walk(tree)if isinstance(node,ast.ClassDef)and node.name=="SmbSourceCheckpoint"]\n            if len(classes)!=1 or classes[0]not in tree.body:raise ValueError("LOCAL unconfigured bridge class differs")\n            rows=[node for node in classes[0].body if isinstance(node,ast.FunctionDef)]\n            if len({row.name for row in rows})!=len(rows):raise ValueError("LOCAL unconfigured bridge method is duplicated")\n            lines=raw.splitlines(keepends=True)\n            return {row.name:b"".join(lines[row.lineno-1:row.end_lineno])for row in rows}\n        edits=[]\n        for label in("PYIDENTITY","PYRENDEREDGENERATION"):\n            start,finish,old=section(original,label);_,_,new=section(current,label)\n            before=methods(old);after=methods(new)\n            if set(after)!=set(before)|names or set(before)&names:raise ValueError("LOCAL unconfigured bridge method set differs")\n            for name in names:\n                if hashlib.sha256(after[name]).hexdigest()!=pins[name]:raise ValueError("LOCAL unconfigured bridge method bytes differ")\n            expected=old\n            replacements={\n                "frozen":before["frozen"].replace(b"            self.code_compatibility(value,journal)\\n",\n                    b"            if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)\\n"),\n                "stop":before["stop"].replace(b\'self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True\',\n                    b"not self.stable_sessions_empty(value,before)"),\n                "import_source":before["import_source"].replace(b"        payload=self.decode(value,record)\\n",\n                    b"        payload=self.decode(value,record)\\n        if journal.get(\'cliSha256\')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():\\n            self.unconfigured_stop_bridge(value,journal,payload)\\n")}\n            if (before["frozen"].count(b"            self.code_compatibility(value,journal)\\n")!=1\n                    or before["stop"].count(b\'self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True\')!=2\n                    or before["import_source"].count(b"        payload=self.decode(value,record)\\n")!=1):\n                raise ValueError("LOCAL unconfigured bridge historical anchors differ")\n            for name,replacement in replacements.items():\n                if after[name]!=replacement:raise ValueError("LOCAL unconfigured bridge changed an unknown statement")\n                expected=expected.replace(before[name],replacement,1)\n            anchor=b"    def frozen(self,value,journal):\\n"\n            added=b"\\n".join(after[name]for name in("unconfigured_stop_bridge_cli","unconfigured_stop_empty_identity","unconfigured_stop_bridge"))+b"\\n"\n            expected=expected.replace(anchor,added+anchor,1)\n            anchor=b"    def stop(self,value,journal,rollback=False):\\n"\n            expected=expected.replace(anchor,after["stable_sessions_empty"]+b"\\n"+anchor,1)\n            marker=b"LOCAL_SOURCE_FAILURE_STAGES=frozenset({\\n"\n            declaration=("LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256="+json.dumps(pins,sort_keys=True,separators=(",",":"))+"\\n\\n").encode()\n            if expected.count(marker)!=1 or b"LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256" in old:\n                raise ValueError("LOCAL unconfigured bridge source marker differs")\n            expected=expected.replace(marker,declaration+marker,1)\n            if expected!=new:raise ValueError("LOCAL unconfigured bridge changed unreviewed inline bytes")\n            edits.append((start,finish,expected))\n        expected=original\n        for start,finish,block in reversed(sorted(edits)):expected=expected[:start]+block+expected[finish:]\n        if current!=expected:raise ValueError("LOCAL unconfigured bridge changed other CLI bytes")\n        return True\n', 'unconfigured_stop_bridge': '    def unconfigured_stop_bridge(self,value,journal,payload=None):\n        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}\n        record=self.read(self.path(scope,"cipher"))\n        # An existing collector receipt retains only its original validator.\n        # Its presence never grants the new empty-source compatibility kind.\n        if self.read(self.path(scope,"code-compatibility"))is not None:return False\n        if record is None or record.get("kind")!=LOCAL_SOURCE_KIND or record.get("privateFingerprints")!={}:return False\n        self.validate_record(value,record)\n        source=self.state(value,recovery=True)\n        pending=self.read(self.handler.generations/"pending.json")\n        fields={"instanceUuid","operationUuid","revision","phase","previous","startedAt","beforeSha256"}\n        if (not value["sourceGeneration"]or set(pending)!=fields or pending["phase"]!="PREPARED"\n                or self.read(self.handler.generations/(scope["operationUuid"]+".json"))is not None\n                or source["configurationDesiredState"]["desired-state/smb-share-apply.json"]is not None\n                or source["configurationDesiredState"]!=journal.get("sourceDesiredState")\n                or record["sourceDesiredState"]!=journal.get("sourceDesiredState")\n                or journal.get("names")!=[]or journal.get("nvmeHosts")!=[]or journal.get("authReplayDomains")!=[]\n                or record.get("privateFingerprints")!={}or journal.get("phase")not in("RECOVERY_REQUIRED","STOPPED","EXPORTED","RESUMED")\n                or journal.get("publicKey")!=record["publicKey"]\n                or not self.stable_sessions_empty(value,journal["prior"])\n                or record["stoppedDatabases"]!=journal["prior"]["databases"]\n                or record["sourceConfigurationFileSha256"]!=journal["prior"]["configurationSha256"]):\n            raise ValueError("LOCAL unconfigured bridge source or unfinished target differs")\n        files={"/var/lib/samba/private/passdb.tdb","/var/lib/samba/private/secrets.tdb",\n            "/etc/ablestack-storage/smb-managed-identities.json","/etc/ablestack-storage/smb-local-account-provenance.json",\n            "/etc/ablestack-storage/smb-semantic-identity-aliases.json"}\n        if any(os.path.lexists(path)for path in files):raise ValueError("LOCAL unconfigured bridge current SMB identity exists")\n        old_sha=journal.get("cliSha256");current=self.cli.read_bytes();current_sha=hashlib.sha256(current).hexdigest()\n        if not isinstance(old_sha,str)or not re.fullmatch("[0-9a-f]{64}",old_sha):\n            raise ValueError("LOCAL unconfigured bridge original code pin differs")\n        active=self.cli.resolve(strict=True)\n        if active!=(self.runtime_root/"current"/"ablestack-storagectl").resolve(strict=True):\n            raise ValueError("LOCAL unconfigured bridge current ENTRY is not installed")\n        def signed(path,checksum):\n            raw=self.compat_entry(path,checksum)\n            manifest=json.loads(self.reader(str(path.parent/"manifest.json"),64*1024)[0])\n            authority=self.installed_release_authority(path.parent,manifest)\n            if not os.path.lexists(self.installation_root/authority["transactionId"]/"manifest.sig"):\n                raise ValueError("LOCAL unconfigured bridge requires signed installation receipts")\n            return raw\n        signed(active,current_sha)\n        paths=list((self.runtime_root/"releases").iterdir())\n        if len(paths)>128:raise ValueError("LOCAL unconfigured bridge historical lookup exceeds its bound")\n        originals=[]\n        for directory in sorted(paths):\n            meta=directory.lstat()\n            if not stat.S_ISDIR(meta.st_mode):continue\n            candidate=directory/"ablestack-storagectl"\n            if not os.path.lexists(candidate):continue\n            raw,_=self.reader(str(candidate))\n            if hashlib.sha256(raw).hexdigest()==old_sha:originals.append(signed(candidate,old_sha))\n        if len(originals)!=1 or not self.unconfigured_stop_bridge_cli(current,originals[0]):\n            raise ValueError("LOCAL unconfigured bridge original code differs")\n        stable={"schemaVersion":1,"kind":"LOCAL_UNCONFIGURED_SOURCE_STOP_GUARD_COMPATIBILITY","scope":scope,"common":self.common(value),\n            "originalCliSha256":old_sha,"currentCliSha256":current_sha,"checkpointSha256":service_cipher_digest(record),\n            "sourceSmbConfigurationSha256":record["sourceConfigurationFileSha256"],\n            "emptySmbIdentityAuthenticated":True,"formatOrAccountMutationAuthorized":False}\n        path=self.path(scope,"unconfigured-stop-code-compatibility");saved=self.read(path)\n        if saved is not None and saved!=stable:raise ValueError("LOCAL unconfigured bridge immutable receipt differs")\n        if payload is not None:\n            if set(value)!=LOCAL_SOURCE_COMMON|{"capsule","credentialPrivateKey","localSourceCheckpointReference","deferNvmeReplay","restoreDomains"}:\n                raise ValueError("LOCAL unconfigured bridge requires its original import request")\n            self.lock()\n            self.unconfigured_stop_empty_identity(payload)\n            # decode() authenticated the exact immutable record/reference before\n            # this call. Only this POST path can publish the compatibility proof.\n            if saved is None:self.write(scope,"unconfigured-stop-code-compatibility",stable,immutable=True)\n        return True\n', 'stable_sessions_empty': '    def stable_sessions_empty(self,value,before):\n        # Empty rows alone are not authority. Only a frozen, verified SOURCE\n        # with no SMB declaration may use the native all-domain absence proof.\n        absent=(set(before["databases"])==set(IDENTITY_DATABASES)\n            and all(row=={"path":IDENTITY_DATABASES[name],"present":False}for name,row in before["databases"].items()))\n        if not value["sourceGeneration"]or before["units"]or before["ownedEndpoints"]or not absent:\n            return self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is True\n        source=self.state(value,recovery=True)\n        if source["configurationDesiredState"]["desired-state/smb-share-apply.json"]is not None:\n            return self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is True\n        scope={key:value[key]for key in("instanceUuid","operationUuid","revision")}\n        view=self.handler.inspect(scope,allow_missing=True);sessions=view.get("sessions",{})\n        counts=("establishedTcpCount","synRecvTcpCount","smbSessionCount","treeConnectionCount","openFileCount","byteLockOpenFileCount")\n        if (view.get("identityBaselineKind")!="UNCONFIGURED_SMB_SOURCE"or view.get("scope")!=scope\n                or view.get("generation")!=value["sourceGeneration"]or view.get("bootId")!=value["expectedBootId"]\n                or any(view.get(key)is not True for key in("success","smbIdentitySupported","ownershipVerified","identityDatabaseAligned","identityRestoreSafe"))\n                or view.get("requiresQuiesce")is not False\n                or any(view.get(key)!=[]for key in("masters","ownedEndpoints","identityHolders"))\n                or view.get("databases")!=before["databases"]or view.get("configurationSha256")!=before["configurationSha256"]\n                or sessions.get("source")!="UNCONFIGURED_SMB_SOURCE_ABSENCE"\n                or any(sessions.get(key)is not True for key in("available","lockingDatabasesAligned","safeToRebind"))\n                or any(type(sessions.get(key))is not int or sessions[key]!=0 for key in counts)\n                or self.stopped_observation()!=before):\n            raise ValueError("LOCAL unconfigured source absence proof changed")\n        return True\n'}
INITIAL_STOP_475_ORIGINAL_PINS='LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256={"stable_sessions_empty":"3b723d9814148e66fc63bb8afab129a6cf78a3bf0e1e31a2e7b52ecef283ff31","unconfigured_stop_bridge":"e73034d7ade38ed8d50301aa22a78324a6201d77e57575f48c6e7ee5fc1ea431","unconfigured_stop_bridge_cli":"d1c3bd346a3c849c7723d73f950c04047637422e83e4c7beb40367171753361a","unconfigured_stop_empty_identity":"2e65a69c95cd6ebb04cdd7017a5caf70bad69383a967c1cb581afff774146dd2"}\n'

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
        config=self.root/"local-source-smb.conf";config.write_bytes(b"PUBLIC FIXTURE CONFIG");config.chmod(0o644)
        original_read=self.read
        self.read=lambda path,*args:capsule.regular_file(config,*args)if str(path)=="/etc/samba/smb.conf"else original_read(path)
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

    def verified_unconfigured_source(self):
        # Actual first-SMB source: a verified NFS generation, no SAM or daemon.
        # Keep the real inspect/session implementations, including smbstatus's
        # observed unconfigured wire shape (open_files is absent).
        for path in self.private.values():path.unlink()
        self.active=False;self.active_units.clear()
        self.handler.database_identity=lambda:{label:{"path":path,"present":False}for label,path in module.IDENTITY_DATABASES.items()}
        self.handler.sessions=fixture.smb_identity.SmbIdentity.sessions.__get__(self.handler)
        config=self.root/"unconfigured-smb.conf";config.write_bytes(b"PUBLIC FIXTURE CONFIG");config.chmod(0o644)
        boot=self.root/"boot-id";boot.write_text(self.boot)
        original_path=Path
        vendor_root=self.root/"vendor-package";vendor_root.mkdir(mode=0o700)
        vendor_paths={}
        for name in("/usr","/usr/share","/usr/share/samba"):
            actual=vendor_root/name.lstrip("/");actual.mkdir(mode=0o755);vendor_paths[name]=actual
        vendor=vendor_paths["/usr/share/samba"]/"smb.conf";vendor.write_bytes(config.read_bytes());vendor.chmod(0o644)
        vendor_paths["/usr/share/samba/smb.conf"]=vendor
        self.vendor_config=vendor;self.vendor_paths=vendor_paths
        def path(value):
            name=str(value)
            return config if name=="/etc/samba/smb.conf"else boot if name=="/proc/sys/kernel/random/boot_id"else vendor_paths.get(name,original_path(value))
        source_paths=patch.object(module,"Path",side_effect=path)
        source_paths.start();self.addCleanup(source_paths.stop)
        source_config=patch.object(module,"LOCAL_SOURCE_SMB_CONFIG",config)
        source_config.start();self.addCleanup(source_config.stop)
        mapping=patch.object(fixture.smb_identity,"Path",side_effect=path)
        mapping.start();self.addCleanup(mapping.stop)
        self.native.reader=lambda value,*args:capsule.regular_file(config if str(value)=="/etc/samba/smb.conf"else vendor_paths.get(str(value),value),*args)
        self.native.observe=module.SmbSourceCheckpoint.observe.__get__(self.native)
        self.native.stopped_observation=module.SmbSourceCheckpoint.stopped_observation.__get__(self.native)
        self.native.collector=lambda names,hosts,**kwargs:{**self.empty_payload(),"posixPolicies":{}}
        original_run=self.handler.run
        def run(args):
            self.calls.append(args)
            if args[0]=="smbstatus":return json.dumps({"sessions":{},"tcons":{}})
            if args[:2]==["systemctl","show"]and args[-2:]==["--property=MainPID","--value"]:return "0\n"
            return original_run(args)
        self.handler.run=run
        return config

    def initial_unconfigured_source(self):
        config=self.verified_unconfigured_source()
        self.gen.current.unlink();self.gen.pending.unlink()
        self.desired={name:None for name in self.gen.paths}
        self.desired["sharedfs-network.json"]={"interface":"eth0","address":"192.0.2.75/24"}
        self.gen.replace_desired(self.desired)
        self.scope={**self.scope,"revision":1}
        self.gen.execute("begin",self.scope)
        self.current={}
        local=str(uuid.UUID(bytes=hashlib.md5(("local-source-checkpoint:"+self.scope["operationUuid"]).encode()).digest(),version=3))
        self.request={**self.scope,"localCheckpointUuid":local,"sourceGeneration":{},
            "sourceConfigurationSha256":self.gen.digest(),"expectedBootId":self.boot}
        self.export_request={**self.request,"publicKey":self.public(self.newkey),"names":[],"nvmeHosts":[],"authReplayDomains":[]}
        return config

    def test_empty_vendor_source_capture_seals_exact_metadata_without_raw_configuration(self):
        config=self.initial_unconfigured_source();captured=self.native.export(self.export_request)
        record=self.native.read(self.native.path(self.request,"cipher"));info=config.stat()
        self.assertEqual({"sha256":hashlib.sha256(config.read_bytes()).hexdigest(),"sizeBytes":info.st_size,
            "uid":info.st_uid,"gid":info.st_gid,"mode":0o644},record["sourceConfigurationFileMetadata"])
        decoded=self.native.decode(self.imported_request(captured),record)
        self.assertNotIn("/etc/samba/smb.conf",decoded["files"])
        self.assertNotIn("sourceConfigurationFileData",record)
        self.assertFalse(self.starts);self.assertFalse(self.signals)

    def test_empty_vendor_source_rejects_unreconstructible_configuration_before_forward(self):
        config=self.initial_unconfigured_source();original=config.read_bytes()
        for failure in("bytes","mode","parent-mode","hardlink","symlink","missing"):
            with self.subTest(failure=failure):
                vendor=self.vendor_config
                vendor.unlink(missing_ok=True);vendor.write_bytes(original);vendor.chmod(0o644)
                self.vendor_paths["/usr/share/samba"].chmod(0o755)
                alias=vendor.with_name("alias");alias.unlink(missing_ok=True)
                if failure=="bytes":vendor.write_bytes(original+b" changed")
                elif failure=="mode":vendor.chmod(0o600)
                elif failure=="parent-mode":vendor.parent.chmod(0o777)
                elif failure=="hardlink":os.link(vendor,alias)
                elif failure=="symlink":vendor.rename(alias);vendor.symlink_to(alias)
                elif failure=="missing":vendor.unlink()
                try:
                    with self.assertRaises((ValueError,FileNotFoundError)):self.native.export(self.export_request)
                    self.assertFalse(self.native.path(self.request,"cipher").exists())
                    self.assertEqual(original,config.read_bytes());self.assertFalse(self.starts);self.assertFalse(self.signals)
                finally:
                    vendor.unlink(missing_ok=True);alias.unlink(missing_ok=True);vendor.write_bytes(original)
                    vendor.chmod(0o644);vendor.parent.chmod(0o755)

    def test_empty_vendor_source_special_mode_is_rejected_before_forward(self):
        config=self.initial_unconfigured_source();config.chmod(0o4755)
        self.vendor_config.chmod(0o4755)
        with self.assertRaisesRegex(ValueError,"protected exact regular file"):self.native.export(self.export_request)
        self.assertFalse(self.native.path(self.request,"cipher").exists())
        self.assertFalse(self.starts);self.assertFalse(self.signals)

    def test_empty_vendor_source_changed_config_restores_original_metadata_and_reverifies(self):
        config=self.initial_unconfigured_source();source=config.read_bytes()
        captured=self.native.export(self.export_request)
        config.write_bytes(b"PUBLIC TARGET CONFIG");config.chmod(0o600)
        restored=self.native.import_source(self.imported_request(captured))
        self.assertTrue(restored["sourceIdentityRestored"]);self.assertTrue(restored["sourceRuntimeVerified"])
        self.assertEqual(source,config.read_bytes());self.assertEqual(0o644,stat.S_IMODE(config.stat().st_mode))
        self.assertEqual("VERIFIED",self.native.read(self.native.path(self.request,"journal"))["sourceConfigurationRestoreStage"])
        self.assertTrue(all(not path.exists()for path in self.private.values()))

    def test_empty_vendor_source_same_bytes_wrong_mode_restores_original_metadata(self):
        config=self.initial_unconfigured_source();captured=self.native.export(self.export_request)
        source=config.read_bytes();config.chmod(0o600)
        restored=self.native.import_source(self.imported_request(captured))
        self.assertTrue(restored["sourceRuntimeVerified"])
        self.assertEqual(source,config.read_bytes());self.assertEqual(0o644,stat.S_IMODE(config.stat().st_mode))
        self.assertEqual("VERIFIED",self.native.read(self.native.path(self.request,"journal"))["sourceConfigurationRestoreStage"])

    def test_empty_vendor_source_inverse_mismatch_never_publishes_or_starts(self):
        config=self.initial_unconfigured_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target);config.chmod(0o600)
        self.vendor_config.write_bytes(b"PUBLIC DIFFERENT PACKAGE CONFIG")
        with self.assertRaisesRegex(ValueError,"original fingerprint"):self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertEqual(0o600,stat.S_IMODE(config.stat().st_mode))
        self.assertFalse(self.starts);self.assertFalse(self.active_units)
        self.assertEqual("RECOVERY_REQUIRED",self.native.read(self.native.path(self.request,"journal"))["phase"])

    def test_empty_vendor_source_legacy_missing_metadata_only_blocks_changed_configuration(self):
        config=self.initial_unconfigured_source();captured=self.native.export(self.export_request)
        record=self.native.read(self.native.path(self.request,"cipher"));record.pop("sourceConfigurationFileMetadata")
        # Test-only legacy record schema in this owned fixture; no immutable producer guard changes.
        self.native.write(self.request,"cipher",record,immutable=False)
        request={**self.imported_request(captured),"localSourceCheckpointReference":self.native.reference(record)}
        self.assertTrue(self.native.import_source(request)["sourceRuntimeVerified"])

    def test_empty_vendor_source_legacy_missing_metadata_blocks_changed_configuration(self):
        config=self.initial_unconfigured_source();captured=self.native.export(self.export_request)
        record=self.native.read(self.native.path(self.request,"cipher"));record.pop("sourceConfigurationFileMetadata")
        # Test-only legacy record schema in this owned fixture; no immutable producer guard changes.
        self.native.write(self.request,"cipher",record,immutable=False)
        request={**self.imported_request(captured),"localSourceCheckpointReference":self.native.reference(record)}
        config.write_bytes(b"PUBLIC TARGET CONFIG")
        with self.assertRaisesRegex(ValueError,"original protected vendor authority"):self.native.import_source(request)
        self.assertEqual(b"PUBLIC TARGET CONFIG",config.read_bytes())

    def test_empty_vendor_source_malformed_metadata_and_foreign_source_never_select_vendor(self):
        self.initial_unconfigured_source();captured=self.native.export(self.export_request)
        record=self.native.read(self.native.path(self.request,"cipher"));journal=self.native.read(self.native.path(self.request,"journal"))
        for field,value in(("uid",False),("sizeBytes",True),("sha256","0"*64)):
            changed=copy.deepcopy(record);changed["sourceConfigurationFileMetadata"][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):self.native.vendor_source_smb_candidate(changed,journal)
        changed=copy.deepcopy(record);changed["privateFingerprints"]={"/foreign":"0"*64}
        with self.assertRaises(ValueError):self.native.vendor_source_smb_candidate(changed,journal)
        changed=copy.deepcopy(record);changed["sourceDesiredState"]["desired-state/smb-share-apply.json"]={}
        with self.assertRaises(ValueError):self.native.vendor_source_smb_candidate(changed,journal)
        request=self.imported_request(captured)
        with self.assertRaises(ValueError):self.native.import_source({**request,"expectedBootId":str(uuid.uuid4())})

    def test_initial_empty_source_missing_smbstatus_open_files_exports_imports_and_preserves_absence(self):
        config=self.initial_unconfigured_source()
        before=(self.gen.digest(),config.read_bytes(),self.gen.pending.read_bytes())
        with patch.object(self.handler,"inspect",side_effect=AssertionError("initial current is absent")):
            captured=self.native.export(self.export_request)
            self.assertTrue(captured["sourceSmbResumed"])
            restored=self.native.import_source(self.imported_request(captured))
            self.assertTrue(restored["sourceIdentityRestored"])
            self.assertTrue(self.native.status(self.request)["sourceRuntimeVerified"])
        self.assertEqual(before,(self.gen.digest(),config.read_bytes(),self.gen.pending.read_bytes()))
        self.assertFalse(self.gen.current.exists())
        self.assertFalse(self.signals);self.assertFalse(self.starts)
        self.assertFalse(any(args[0]=="smbstatus"for args in self.calls))
        self.assertTrue(all(not path.exists()for path in self.private.values()))

    def test_initial_empty_source_rejects_malformed_or_foreign_pending_current_and_boot(self):
        self.initial_unconfigured_source()
        before=self.native.stopped_observation()
        saved=self.gen.pending.read_bytes()
        cases=("missing","phase","previous","scope","revision","extra","missing-start","invalid-start","infinite-start","current-empty","current-dangling","boot")
        for failure in cases:
            with self.subTest(failure=failure):
                self.gen.pending.write_bytes(saved);self.gen.current.unlink(missing_ok=True)
                request=copy.deepcopy(self.request)
                pending=json.loads(saved)
                if failure=="missing":self.gen.pending.unlink()
                elif failure=="current-empty":generation.atomic_json(self.gen.current,{})
                elif failure=="current-dangling":self.gen.current.symlink_to(self.root/"no-such-current")
                elif failure=="boot":request["expectedBootId"]=str(uuid.uuid4())
                else:
                    if failure=="phase":pending["phase"]="VERIFIED"
                    elif failure=="previous":pending["previous"]={"revision":0}
                    elif failure=="scope":pending["operationUuid"]=str(uuid.uuid4())
                    elif failure=="revision":pending["revision"]=2
                    elif failure=="extra":pending["unknownAuthority"]=True
                    elif failure=="missing-start":pending.pop("startedAt")
                    elif failure=="invalid-start":pending["startedAt"]=True
                    elif failure=="infinite-start":pending["startedAt"]=float("inf")
                    generation.atomic_json(self.gen.pending,pending)
                with self.assertRaises((ValueError,FileNotFoundError)):
                    self.native.stable_sessions_empty(request,before)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_initial_empty_source_rechecks_canonical_and_smb_declaration_before_absence(self):
        self.initial_unconfigured_source()
        before=self.native.stopped_observation();saved=copy.deepcopy(self.desired)
        for filename in ("sharedfs-network.json","desired-state/smb-share-apply.json"):
            with self.subTest(filename=filename):
                desired=copy.deepcopy(saved);desired[filename]={"unexpected":True}
                self.gen.replace_desired(desired)
                with self.assertRaisesRegex(ValueError,"canonical seven"):
                    self.native.stable_sessions_empty(self.request,before)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_initial_empty_source_fresh_database_process_listener_or_connection_never_becomes_safe(self):
        self.initial_unconfigured_source()
        before=self.native.stopped_observation()
        database=self.handler.database_identity
        run=self.handler.run
        for failure in ("database","database-unknown","database-partial","process","listener","connection"):
            with self.subTest(failure=failure):
                proc=self.handler.process_root/"999"
                if failure=="process":
                    proc.mkdir();(proc/"comm").write_text("smbd")
                def databases():
                    rows=database()
                    if failure=="database":rows["PASSDB"]={**rows["PASSDB"],"present":True}
                    if failure=="database-unknown":rows["PASSDB"]["present"]=None
                    if failure=="database-partial":rows.pop("PASSDB")
                    return rows
                def observed(args):
                    if failure=="listener"and args[:3]==["ss","-H","-ltnp"]:
                        return "LISTEN 0 0 192.0.2.75:445 0.0.0.0:*"
                    if failure=="connection"and args[:3]==["ss","-H","-ntp"]:
                        return "ESTAB 0 0 192.0.2.75:445 192.0.2.1:50000"
                    return run(args)
                with patch.object(self.handler,"database_identity",side_effect=databases),patch.object(self.handler,"run",side_effect=observed):
                    with self.assertRaises(ValueError):
                        self.native.stable_sessions_empty(self.request,before)
                if proc.exists():(proc/"comm").unlink();proc.rmdir()
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_initial_empty_source_dangling_identity_database_is_not_absence(self):
        self.initial_unconfigured_source();before=self.native.stopped_observation()
        lexists=module.os.path.lexists
        for path in module.IDENTITY_DATABASES.values():
            with self.subTest(path=path):
                def present(value):
                    return True if str(value)==path else lexists(value)
                with patch.object(module.os.path,"lexists",side_effect=present):
                    with self.assertRaisesRegex(ValueError,"initial source absence"):
                        self.native.stable_sessions_empty(self.request,before)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_initial_empty_source_unknown_or_inaccessible_stopped_observation_fails_closed(self):
        self.initial_unconfigured_source();before=self.native.stopped_observation()
        original=self.native.stopped_observation
        for failure in ("unit","endpoint","session","configuration",PermissionError(),OSError(5,"synthetic")):
            with self.subTest(failure=type(failure).__name__ if isinstance(failure,Exception)else failure):
                def stopped():
                    if isinstance(failure,Exception):raise failure
                    value=original()
                    if failure=="unit":value["units"]=[{"unit":"foreign.service"}]
                    if failure=="endpoint":value["ownedEndpoints"]=[{"port":445}]
                    if failure=="session":value["sessionsVerifiedEmpty"]=False
                    if failure=="configuration":value["configurationSha256"]="f"*64
                    return value
                with patch.object(self.native,"stopped_observation",side_effect=stopped):
                    with self.assertRaises((ValueError,PermissionError,OSError)):
                        self.native.stable_sessions_empty(self.request,before)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_original_initial_recovery_required_checkpoint_resumes_by_existing_stopped_export_path(self):
        self.initial_unconfigured_source()
        with patch.object(self.native,"stable_sessions_empty",side_effect=ValueError("synthetic original smbstatus shape")):
            with self.assertRaises(ValueError):self.native.export(self.export_request)
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertEqual("RECOVERY_REQUIRED",journal["phase"])
        self.assertEqual("STOP_STABLE_VERIFY",journal["lastFailure"]["failureStage"])
        self.assertFalse(self.native.path(self.request,"cipher").exists())
        with patch.object(self.native,"stable_sessions_empty",side_effect=AssertionError("retry must use existing stopped observation")):
            resumed=self.native.export(self.export_request)
        self.assertTrue(resumed["sourceRuntimeVerified"])
        self.assertTrue(self.native.status(self.request)["sourceRuntimeVerified"])
        self.assertFalse(self.signals);self.assertFalse(self.starts)
        self.assertFalse(self.gen.current.exists())

    def test_verified_unconfigured_source_missing_open_files_exports_without_smbstatus_or_signals(self):
        config=self.verified_unconfigured_source()
        before=(self.gen.digest(),config.read_bytes())
        result=self.native.export(self.export_request)
        self.assertTrue(result["sourceSmbResumed"]);self.assertEqual(0,result["priorActiveUnitCount"])
        self.assertEqual(before,(self.gen.digest(),config.read_bytes()))
        self.assertFalse(self.signals);self.assertFalse(self.starts)
        self.assertFalse(any(args[0]=="smbstatus"for args in self.calls))
        self.assertTrue(all(not path.exists()for path in self.private.values()))

    def test_verified_unconfigured_source_original_import_stop_rechecks_absence(self):
        self.verified_unconfigured_source()
        # Seed the original cache independently so the old import-stop path is
        # exercised even when the first-export regression also fails.
        with patch.object(self.handler,"sessions",return_value={"safeToRebind":True}):
            result=self.native.export(self.export_request)
        observed=self.native.import_source(self.imported_request(result))
        self.assertTrue(observed["sourceIdentityRestored"]);self.assertTrue(observed["sourceRuntimeVerified"])
        self.assertTrue(self.native.status(self.request)["sourceSmbResumed"])
        self.assertFalse(self.signals);self.assertFalse(self.starts)
        self.assertFalse(any(args[0]=="smbstatus"for args in self.calls))
        self.assertTrue(all(not path.exists()for path in self.private.values()))

    def test_unconfigured_stop_fresh_foreign_presence_is_not_empty_session_authority(self):
        self.verified_unconfigured_source()
        original=self.native.write
        def write(scope,kind,value,**kwargs):
            result=original(scope,kind,value,**kwargs)
            if kind=="journal"and value.get("phase")=="QUIESCING":
                self.handler.identity_holders=lambda:[{"pid":999}]
            return result
        self.native.write=write
        with self.assertRaisesRegex(ValueError,"holder"):self.native.export(self.export_request)
        self.assertFalse(self.signals);self.assertFalse(self.starts)
        self.assertEqual("RECOVERY_REQUIRED",self.native.read(self.native.path(self.request,"journal"))["phase"])

    def test_unconfigured_stop_changed_source_canonical_does_not_reuse_absence(self):
        self.verified_unconfigured_source()
        original=self.native.write
        def write(scope,kind,value,**kwargs):
            result=original(scope,kind,value,**kwargs)
            if kind=="journal"and value.get("phase")=="QUIESCING":
                generation.atomic_json(self.config/"desired-state/smb-share-apply.json",{"enabled":False})
            return result
        self.native.write=write
        with self.assertRaisesRegex(ValueError,"canonical seven"):self.native.export(self.export_request)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_unconfigured_stop_unknown_fresh_absence_and_permission_errors_fail_closed(self):
        self.verified_unconfigured_source()
        original=self.handler.inspect
        for failure in ("missing-kind","unknown-sessions","foreign-boot","foreign-generation","PermissionError","OSError"):
            with self.subTest(failure=failure):
                def inspect(*args,**kwargs):
                    if failure=="PermissionError":raise PermissionError()
                    if failure=="OSError":raise OSError(5,"synthetic")
                    view=original(*args,**kwargs)
                    if failure=="missing-kind":view.pop("identityBaselineKind")
                    if failure=="unknown-sessions":view["sessions"]["openFileCount"]=None
                    if failure=="foreign-boot":view["bootId"]=str(uuid.uuid4())
                    if failure=="foreign-generation":view["generation"]={}
                    return view
                before=self.native.stopped_observation()
                with patch.object(self.handler,"inspect",side_effect=inspect),self.assertRaises((ValueError,PermissionError,OSError)):
                    self.native.stable_sessions_empty(self.request,before)
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_empty_rows_without_verified_unconfigured_source_still_require_smbstatus(self):
        self.verified_unconfigured_source()
        before=self.native.stopped_observation()
        with self.assertRaisesRegex(ValueError,"session/byte-lock"):
            self.handler.sessions([],[])
        for mode in ("initial","configured"):
            with self.subTest(mode=mode):
                request=copy.deepcopy(self.request)
                if mode=="initial":request["sourceGeneration"]={}
                else:
                    self.desired["desired-state/smb-share-apply.json"]={"enabled":False,"shares":[]}
                    self.update_source()
                    request=copy.deepcopy(self.request)
                with self.assertRaises(ValueError):
                    self.native.stable_sessions_empty(request,before)

    def historical_unconfigured_cli(self):
        import ast
        current=fixture.CLI.read_bytes();original=current
        for label in("PYIDENTITY","PYRENDEREDGENERATION"):
            begin=("<<'"+label+"'\n").encode();end=("\n"+label+"\n").encode()
            start=original.index(begin)+len(begin);finish=original.index(end,start);block=original[start:finish]
            tree=ast.parse(block.decode());cls=next(node for node in tree.body if isinstance(node,ast.ClassDef)and node.name=="SmbSourceCheckpoint")
            lines=block.splitlines(keepends=True)
            added={"stable_sessions_empty","unconfigured_stop_bridge_cli","unconfigured_stop_empty_identity","unconfigured_stop_bridge"}
            for node in cls.body:
                if isinstance(node,ast.FunctionDef)and node.name in added:
                    raw=b"".join(lines[node.lineno-1:node.end_lineno])+b"\n"
                    self.assertEqual(1,block.count(raw));block=block.replace(raw,b"",1)
            block=b"".join(line for line in block.splitlines(keepends=True)if not line.startswith(b"LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256="))
            block=block.replace(b"\n\n\nLOCAL_SOURCE_FAILURE_STAGES",b"\n\nLOCAL_SOURCE_FAILURE_STAGES")
            block=block.replace(b"            if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)\n",
                b"            self.code_compatibility(value,journal)\n")
            block=block.replace(b"not self.stable_sessions_empty(value,before)",
                b'self.handler.sessions(before["ownedEndpoints"],before["units"])["safeToRebind"]is not True')
            block=block.replace(b"        if journal.get('cliSha256')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():\n            self.unconfigured_stop_bridge(value,journal,payload)\n",b"")
            original=original[:start]+block+original[finish:]
        return original,current

    def historical_code8_before_shared_service_journal_io(self,current):
        # Test-only inverse: keep the actual Code8 whole CLI, never relabel new code as old.
        spans=[
            (b'checkpoint=Maintenance().read(checkpoint_path);status=self.store.status();actual=self.generation()',
             b'checkpoint=json.loads(rendered_read(checkpoint_path));status=self.store.status();actual=self.generation()',1),
            (b'Maintenance(root=checkpoint_path.parent).write(checkpoint_path,checkpoint)',
             b'rendered_json(checkpoint_path,checkpoint)',3),
            (b'journal=Maintenance(root=self.root).read(self.root/"service-maintenance.json");owners=saved["sourceOwners"]',
             b'journal=credential_json(rendered_read(self.root/"service-maintenance.json"));owners=saved["sourceOwners"]',1)]
        for after,before,count in spans:
            self.assertEqual(count,current.count(after));current=current.replace(after,before)
        self.assertEqual("4e5f9624ee03cb56941655f2100782e08fd60dff69a26de78d0eac54637eac5d",hashlib.sha256(current).hexdigest())
        return current
    def test_shared_service_journal_projection_reconstructs_genuine_code8(self):
        self.historical_code8_before_shared_service_journal_io(fixture.CLI.read_bytes())

    def historical_code7_before_formatter_mutexes(self,current):
        current=self.historical_code8_before_shared_service_journal_io(current)
        # This reconstructs the real old Code7 fixture, never Code8 runtime authority.
        import ast
        source=(Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage/service_maintenance.py").read_text()
        cls=next(n for n in ast.parse(source).body if isinstance(n,ast.ClassDef)and n.name=="ServiceMaintenance")
        method=next(n for n in cls.body if isinstance(n,ast.FunctionDef)and n.name=="fence")
        new="".join(source.splitlines(keepends=True)[method.lineno-1:method.end_lineno]).encode()
        self.assertEqual('4d29d8d2e6cb8383e4d50d45b584b10398ee990a16630cafc072fad91238e412',hashlib.sha256(new).hexdigest())
        old=b'    def fence(self,request):\n        desired=scope(request,"SERVICE");actual=self.maintenance.generation()\n        current=actual.get("generation") or {}\n        if current.get("instanceUuid")!=desired["instanceUuid"] or type(current.get("revision")) is not int or current["revision"]>desired["revision"]:\n            raise ValueError("SERVICE native generation scope is foreign or stale")\n        pending=actual.get("pendingOperationUuid")\n        if pending not in (None,desired["operationUuid"]):raise ValueError("Foreign generation blocks SERVICE maintenance")\n        directory=Path(os.environ.get("ABLESTACK_STORAGE_VOLUME_OPERATIONS","/var/lib/ablestack-storage/volume-operations"))\n        if directory.exists() or directory.is_symlink():\n            info=directory.lstat()\n            if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:raise ValueError("Formatter journals are unprotected")\n            for path in sorted(directory.iterdir()):\n                if path.suffix!=".json":raise ValueError("Unknown formatter journal entry")\n                volume=str(uuid.UUID(path.stem));observed=self.command(("volume","operation","status"),{"volumeUuid":volume})\n                if (observed.get("formatterActive") is True or observed.get("terminationPending") is True\n                        or observed.get("status") not in ("COMPLETE","MOUNT_EXISTING","NOT_STARTED")):\n                    raise ValueError("Unresolved formatter blocks SERVICE maintenance")\n        return desired\n'
        self.assertEqual(2,current.count(new));current=current.replace(new,old)
        new=b'"""Approved downtime boundary with durable typed scopes and owned acceptors."""\nimport fcntl\nimport json\n';old=b'"""Approved downtime boundary with durable typed scopes and owned acceptors."""\nimport json\n'
        self.assertEqual(2,current.count(new));current=current.replace(new,old)
        self.assertEqual("2784d70a1ea724922758b08b238022b2969f9c3f08a4a2df1a13ed48919144d0",hashlib.sha256(current).hexdigest())
        return current
    def test_formatter_mutex_projection_reconstructs_genuine_code7(self):
        self.historical_code7_before_formatter_mutexes(fixture.CLI.read_bytes())

    def historical_d524_before_disabled_smb_parser(self,current):
        current=self.historical_code7_before_formatter_mutexes(current)
        # Test-only reconstruction of real Code6 bytes, never Code7 runtime authority.
        new=b"'   smb ports = '+' '.join(str(port) for port in (ports or [445]))"
        old=b"'   smb ports = '+' '.join(str(port) for port in ports)"
        self.assertEqual(1,current.count(new));current=current.replace(new,old,1)
        self.assertEqual("d5241c01c48822f16f6eb7ea21754280fb923b323e93e65e853353603af94189",
                         hashlib.sha256(current).hexdigest())
        return current

    def historical_ba792_before_empty_source_restore(self,current):
        # Exact test-only inverse of the reviewed Runtime/Driver changes.
        # The intermediate whole-code pin precedes the existing vendor/c25b inverse.
        spans = [
            (b'        if value is None:\n            value = {"instanceUuid": self.store.inspect(path)["scope"]["instanceUuid"],\n                     "enabled": False, {"NFS":"exports", "SMB":"shares", "ISCSI":"targets", "NVMEOF":"subsystems"}[domain]: []}\n        value = json.loads(json.dumps(value))\n', b'        if value is None:\n            value = {"enabled": False, {"NFS":"exports", "SMB":"shares", "ISCSI":"targets", "NVMEOF":"subsystems"}[domain]: []}\n        value = json.loads(json.dumps(value))\n'),
            (b'\n    def verified_empty_local_smb_restore(self, request, record):\n        # Only the already-authenticated, imported opaque LOCAL SOURCE can\n        # suppress a lower empty apply that would replace its original vendor\n        # configuration. This proof is consumed by this one rollback closure.\n        if record["sourceDesiredState"]["desired-state/smb-share-apply.json"] is not None:\n            return False\n        if (record["scope"] != {**self.store.scope(request),\n                "localCheckpointUuid": request["localSourceCheckpointReference"]["localCheckpointUuid"]}\n                or record["privateFingerprints"] != {}\n                or any(row.get("present") is not False for row in record["stoppedDatabases"].values())):\n            raise ValueError("Empty LOCAL SMB replay lacks its original absent private source")\n        common = record["common"]\n        observed = self.runtime.command(("smb", "identity", "local-source-status"), common)\n        expected = {"scope": record["scope"], "bootId": common["expectedBootId"],\n            "sourceGeneration": common["sourceGeneration"],\n            "sourceConfigurationSha256": common["sourceConfigurationSha256"],\n            "currentConfigurationSha256": common["sourceConfigurationSha256"],\n            "currentSmbConfigurationSha256": record["sourceConfigurationFileSha256"],\n            "localSourceCheckpointReference": request["localSourceCheckpointReference"], "phase": "RESUMED"}\n        if (any(observed.get(key) != value for key, value in expected.items())\n                or any(observed.get(key) is not True for key in ("success", "sourceSmbResumed",\n                    "sourceRuntimeVerified", "currentRuntimeOwnershipVerified", "currentSessionsVerifiedEmpty"))):\n            raise ValueError("Empty LOCAL SMB original runtime did not freshly verify")\n        return True\n\n    def restore_identity(self, request):\n', b'\n    def restore_identity(self, request):\n'),
            (b'        saved = self.recovery_key(request)\n        empty_local_smb_restored = False\n        previous = self.store.pointer()\n', b'        saved = self.recovery_key(request)\n        previous = self.store.pointer()\n'),
            (b'                    **({"nvmeDesired":desired}if "NVMEOF"in auth else {})})\n            empty_local_smb_restored = self.verified_empty_local_smb_restore(request, record) if "SMB" in domains else False\n        else:self.runtime.command(("identity", "capsule", "import"), {**saved["scope"], "capsule": saved["capsule"],\n', b'                    **({"nvmeDesired":desired}if "NVMEOF"in auth else {})})\n        else:self.runtime.command(("identity", "capsule", "import"), {**saved["scope"], "capsule": saved["capsule"],\n'),
            (b'                raise ValueError("Scoped SMB rollback did not verify its exact preserved AD identity")\n        return empty_local_smb_restored\n\n', b'                raise ValueError("Scoped SMB rollback did not verify its exact preserved AD identity")\n\n'),
            (b'        previous_restored = False\n        empty_local_smb_restored = False\n        def replay(path, domain, rollback):\n            nonlocal previous_restored, empty_local_smb_restored\n            if retained is not None and rollback:raise ValueError("Retained ROOT cannot replay historical protocols/identity/permissions; latest ROOT compensation is required")\n            if rollback and not previous_restored:\n                empty_local_smb_restored = self.restore_identity(request) is True\n                previous_restored = True\n            if rollback and domain == "SMB" and empty_local_smb_restored:\n                return\n            result=self.runtime.replay(path, domain, rollback)\n', b'        previous_restored = False\n        def replay(path, domain, rollback):\n            nonlocal previous_restored\n            if retained is not None and rollback:raise ValueError("Retained ROOT cannot replay historical protocols/identity/permissions; latest ROOT compensation is required")\n            if rollback and not previous_restored:\n                self.restore_identity(request); previous_restored = True\n            result=self.runtime.replay(path, domain, rollback)\n'),
        ]
        for new,old in spans:
            self.assertEqual(1,current.count(new));current=current.replace(new,old,1)
        self.assertEqual("ba7922487035cd2cd752c9d18560f35ed2a6e8f97106427437780ff814f78f91",
                         hashlib.sha256(current).hexdigest())
        return current

    def historical_c25b_before_vendor_source(self,current):
        # Exact test-only inverse; production compatibility remains unchanged.
        current=self.historical_d524_before_disabled_smb_parser(current)
        current=self.historical_ba792_before_empty_source_restore(current)
        import ast
        pins={'export': 'bd35e8cc30b71a3400cd4ecbe802efc53062df467eb938c77397fadaf50f56fa', 'restore_source_smb_configuration': 'e3dd3f656cecbcb681caba9ea434e25e16e732a237921bbab76ea57d70188887', 'smb_configuration_file_fingerprint': '3e81ac56bb18dbaedbbd84a031eee632dc21ada14c382b670972703220821c98', 'vendor_source_smb_candidate': 'c89e0b85c5aaabfd9776ddfaa2772f2107b90ae6cd2492a142f2df83f4f52936'}
        originals={'export': '    def export(self,value):\n        self._failure_stage="EXPORT_SOURCE_AUTHORITY"\n        scope=self.request(value,"export-local-source");source=self.state(value)\n        from cryptography.hazmat.primitives import serialization\n        from cryptography.hazmat.primitives.asymmetric import rsa\n        key=serialization.load_pem_public_key(value["publicKey"].encode())\n        if not isinstance(key,rsa.RSAPublicKey)or key.key_size<2048:raise ValueError("LOCAL source wrapping key is unsupported")\n        names=value["names"];hosts=value["nvmeHosts"]\n        if (not isinstance(names,list)or len(names)>1024 or len(set(names))!=len(names)\n                or any(not isinstance(n,str)or n in {"root","nobody","daemon","cloud","debian"}\n                       or not re.fullmatch("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}",n)for n in names)\n                or not isinstance(hosts,list)or len(hosts)>1024 or any(not isinstance(h,str)for h in hosts)):\n            raise ValueError("LOCAL source collection identifiers are invalid")\n        for host in hosts:validate_host_nqn(host)\n        journal=self.read(self.path(scope,"journal"));record=self.read(self.path(scope,"cipher"))\n        if journal is None:\n            journal={"schemaVersion":1,"scope":scope,"common":self.common(value),"publicKey":value["publicKey"],\n                "names":names,"nvmeHosts":hosts,"authReplayDomains":value["authReplayDomains"],\n                "sourceDesiredState":source["configurationDesiredState"],"prior":self.observe(value),"phase":"INTENT_DURABLE",\n                "cliSha256":hashlib.sha256(self.cli.read_bytes()).hexdigest()}\n            self.write(scope,"journal",journal)\n        self.frozen(value,journal)\n        if any(journal[key]!=value[key]for key in ("publicKey","names","nvmeHosts","authReplayDomains")):\n            raise ValueError("LOCAL source retry key/collection changed")\n        try:\n            self._failure_stage="EXPORT_RECORD_VALIDATE"\n            if record is not None:\n                self.validate_record(value,record)\n                if record["publicKey"]!=value["publicKey"]:raise ValueError("LOCAL source cached wrapping key changed")\n                if journal["phase"]=="RESUMED":\n                    self._failure_stage="EXPORT_CACHE_OBSERVE"\n                    actual=self.observe(value,require_empty=False)\n                    if ({row["unit"]for row in actual["units"]}!={row["unit"]for row in journal["prior"]["units"]}\n                            or actual["configurationSha256"]!=record["sourceConfigurationFileSha256"]\n                            or actual["databases"]!=journal.get("importedStoppedDatabases",record["stoppedDatabases"])\n                            or not all(row.get("tcpReady")for row in actual["ownedEndpoints"])):\n                        raise ValueError("LOCAL source resumed cache ownership changed")\n                    return self.result(record,journal)\n                self.resume(value,journal,record);return self.result(record,journal)\n            self._failure_stage="EXPORT_STOP"\n            if journal["phase"]not in("STOPPED","EXPORTED"):\n                if journal["phase"]in("QUIESCING","RECOVERY_REQUIRED"):\n                    self._failure_stage="EXPORT_RETRY_STOP_OBSERVE"\n                    try:stopped=self.stopped_observation()\n                    except ValueError:stopped=None\n                    if stopped is not None:\n                        if (stopped["databases"]!=journal["prior"]["databases"]\n                                or stopped["configurationSha256"]!=journal["prior"]["configurationSha256"]):\n                            raise ValueError("LOCAL interrupted stop source changed")\n                        journal["phase"]="STOPPED";journal["stopped"]=stopped;self.write(scope,"journal",journal)\n                    else:self.stop(value,journal)\n                else:self.stop(value,journal)\n            self._failure_stage="EXPORT_STOPPED_OBSERVE"\n            stopped=self.stopped_observation()\n            self._failure_stage="EXPORT_POSIX_COLLECT"\n            records=self.posix_collector(journal["sourceDesiredState"]["posix-directory-policies.json"],value["instanceUuid"])\n            self._failure_stage="EXPORT_IDENTITY_COLLECT"\n            payload=self.collector(names,hosts,posix_policies=records)\n            self._failure_stage="EXPORT_IDENTITY_VALIDATE"\n            payload["sourceConfigurationSha256"]=value["sourceConfigurationSha256"];self.validator(payload)\n            if payload.get("adIdentity")is not None:raise ValueError("LOCAL opaque capture cannot include AD")\n            wrapper={"schemaVersion":1,"kind":LOCAL_SOURCE_KIND,"scope":scope,"sourceGeneration":value["sourceGeneration"],\n                "sourceConfigurationSha256":value["sourceConfigurationSha256"],"bootId":value["expectedBootId"],"identity":payload}\n            self._failure_stage="EXPORT_ENCRYPT"\n            capsule=self.encryptor(wrapper,value["publicKey"],scope["instanceUuid"]+":"+scope["operationUuid"])\n            self._failure_stage="EXPORT_STOPPED_RECHECK"\n            if self.stopped_observation()!=stopped:raise ValueError("LOCAL source changed during opaque collection")\n            record={"schemaVersion":1,"kind":LOCAL_SOURCE_KIND,"scope":scope,"common":self.common(value),"publicKey":value["publicKey"],\n                "capsule":capsule,"stoppedDatabases":stopped["databases"],"sourceConfigurationFileSha256":stopped["configurationSha256"],\n                "authReplayDomains":value["authReplayDomains"],\n                "sourceDesiredState":journal["sourceDesiredState"],\n                "privateFingerprints":{path:row["sha256"]for path,row in payload["files"].items()if row.get("absent")is not True}}\n            self._failure_stage="EXPORT_CHECKPOINT_PUBLISH"\n            self.match_private(record);self.write(scope,"cipher",record,immutable=True)\n            journal["phase"]="EXPORTED";self.write(scope,"journal",journal)\n            self.resume(value,journal,record);return self.result(record,journal)\n        except Exception as error:\n            self.record_failure(scope,journal,error);raise\n', 'restore_source_smb_configuration': '    def restore_source_smb_configuration(self,value,journal,record):\n        # The opaque capsule deliberately excludes generated smb.conf. A legacy\n        # writer must restore that exact SOURCE output before exposing its SAM.\n        path=LOCAL_SOURCE_SMB_CONFIG\n        current,info=self.reader(str(path));expected=record["sourceConfigurationFileSha256"]\n        if hashlib.sha256(current).hexdigest()==expected:return\n        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}\n        source=self.state(value,recovery=True);self.frozen(value,journal);self.validate_record(value,record)\n        if (self.reference(record)!=value["localSourceCheckpointReference"]or journal.get("imported")is not True\n                or journal.get("phase")not in("STOPPED","RESUMING","RECOVERY_REQUIRED")\n                or not isinstance(journal.get("importedStoppedDatabases"),dict)\n                or not isinstance(journal.get("importedFingerprints"),dict)\n                or source.get("configurationDesiredState")!=record["sourceDesiredState"]\n                or journal["sourceDesiredState"]!=record["sourceDesiredState"]):\n            raise ValueError("LOCAL SOURCE SMB configuration restore authority differs")\n        stopped=self.stopped_observation()\n        if stopped["databases"]!=journal["importedStoppedDatabases"]:\n            raise ValueError("LOCAL SOURCE SMB imported stopped metadata changed")\n        self.match_private({"privateFingerprints":journal["importedFingerprints"]})\n        parent=path.parent.lstat();named=path.lstat()\n        if (not stat.S_ISDIR(parent.st_mode)or parent.st_uid!=os.geteuid()or parent.st_mode&0o022\n                or not stat.S_ISREG(named.st_mode)or named.st_uid!=os.geteuid()or named.st_mode&0o022\n                or named.st_nlink!=1 or (named.st_dev,named.st_ino)!=(info.st_dev,info.st_ino)):\n            raise ValueError("LOCAL SOURCE SMB configuration path is not protected")\n        journal["sourceConfigurationRestoreStage"]="RENDER";self.write(scope,"journal",journal)\n        candidate=self.signed_source_smb_candidate(record["sourceDesiredState"]["desired-state/smb-share-apply.json"],journal)\n        journal["sourceConfigurationRestoreStage"]="CANDIDATE_HASH";self.write(scope,"journal",journal)\n        if hashlib.sha256(candidate).hexdigest()!=expected:\n            raise ValueError("LOCAL SOURCE SMB rendered configuration differs from its original hash")\n        directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)\n        temporary=None;descriptor=None\n        try:\n            opened=os.fstat(directory)\n            if (opened.st_dev,opened.st_ino)!=(parent.st_dev,parent.st_ino):\n                raise ValueError("LOCAL SOURCE SMB configuration directory changed")\n            descriptor,temporary=tempfile.mkstemp(prefix=".local-source-smb-",dir=path.parent)\n            os.fchmod(descriptor,stat.S_IMODE(info.st_mode));os.fchown(descriptor,info.st_uid,info.st_gid)\n            with os.fdopen(descriptor,"wb",closefd=False)as handle:\n                handle.write(candidate);handle.flush();os.fsync(descriptor)\n            journal["sourceConfigurationRestoreStage"]="TESTPARM";self.write(scope,"journal",journal)\n            try:self.handler.run(["testparm","-s",temporary])\n            except Exception:raise ValueError("LOCAL SOURCE SMB rendered configuration validation failed")from None\n            self.state(value,recovery=True)\n            if self.stopped_observation()!=stopped:\n                raise ValueError("LOCAL SOURCE SMB stopped ownership changed before configuration restore")\n            self.match_private({"privateFingerprints":journal["importedFingerprints"]})\n            latest=path.lstat();parent_now=path.parent.lstat()\n            fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")\n            if (any(getattr(latest,key)!=getattr(info,key)for key in fields)\n                    or (parent_now.st_dev,parent_now.st_ino)!=(parent.st_dev,parent.st_ino)):\n                raise ValueError("LOCAL SOURCE SMB configuration changed before publication")\n            journal["sourceConfigurationRestoreStage"]="PUBLISH";self.write(scope,"journal",journal)\n            os.replace(temporary,path);temporary=None;os.fsync(directory)\n            if hashlib.sha256(self.reader(str(path))[0]).hexdigest()!=expected:\n                raise ValueError("LOCAL SOURCE SMB configuration restore did not verify")\n            journal["sourceConfigurationRestoreStage"]="VERIFIED";self.write(scope,"journal",journal)\n        finally:\n            if descriptor is not None:os.close(descriptor)\n            if temporary is not None and os.path.exists(temporary):os.unlink(temporary)\n            os.close(directory)\n'}
        edits=[]
        for label in("PYIDENTITY","PYRENDEREDGENERATION"):
            begin=("<<'"+label+"'\n").encode();start=current.index(begin)+len(begin)
            finish=current.index(("\n"+label+"\n").encode(),start);block=current[start:finish]
            cls=[n for n in ast.parse(block.decode()).body if isinstance(n,ast.ClassDef)and n.name=="SmbSourceCheckpoint"]
            self.assertEqual(1,len(cls));lines=block.splitlines(keepends=True)
            methods={n.name:b"".join(lines[n.lineno-1:n.end_lineno])for n in cls[0].body if isinstance(n,ast.FunctionDef)}
            for name,pin in pins.items():
                raw=methods[name];self.assertEqual(pin,hashlib.sha256(raw).hexdigest())
                if name in originals:
                    self.assertEqual(1,block.count(raw));block=block.replace(raw,originals[name].encode(),1)
                else:
                    self.assertEqual(1,block.count(raw+b"\n"));block=block.replace(raw+b"\n",b"",1)
            edits.append((start,finish,block))
        for start,finish,block in reversed(edits):current=current[:start]+block+current[finish:]
        self.assertEqual("c25b32ea7496dba3a8148ee1b47bf59b2ff921c204f7fe8797705ba080d7ba4f",hashlib.sha256(current).hexdigest())
        return current

    def historical_16df_before_nfs_request_isolation(self):
        # Test-only reversal: a production bridge must still reject these unrelated later changes.
        current=self.historical_c25b_before_vendor_source(fixture.CLI.read_bytes())
        # Test-only exact reversal of completed-target private ownership.
        # Unknown method, capsule or Driver deltas still fail the hard whole CLI pin.
        import ast
        private_pins={'rendered_target_private_context': '0d7121830e9cb25e5d45ddadb4d027eeef6e4657188e722567508cb61bb6004e', 'rendered_target_private_snapshot': 'b24a1ed2a7973d24cc89b3c28ac5d3f4c86ca21e08c8368657a87e99fe66494e', 'rendered_target_private_receipt': '565e3fb40438808ca6a7b8f6a9865060001856ded02558c19cbd86280cb9c33b', 'capture_rendered_target_private': 'f8997585715944ec76422f1c19d28ab21ee403a0d3d7ab4cee75a75e0f7d3b1e', 'prepare_rendered_target_private_restore': '9ce860fb83ce0830b2708486ed9d1eb5f976726fe52312adcce4c34296c7e3f6', 'rendered_target_private_restore_safe': 'd584cb160e85f3f977f2967d31c98da796ef94afce4310c9fda7a3fe72e2babc', 'import_source': 'fbcd9011e4b6bb99b9c764a8e95bf88e8ce0cc3824a0c17ad992812a33b6c398'}
        private_old_import='    def import_source(self,value):\n        self._failure_stage="IMPORT_SOURCE_AUTHORITY"\n        scope=self.request(value,"import-local-source");self.state(value,recovery=True)\n        journal=self.frozen(value,self.read(self.path(scope,"journal"))or {})\n        record=self.read(self.path(scope,"cipher"))\n        if not record:raise ValueError("LOCAL original checkpoint is absent")\n        payload=self.decode(value,record)\n        if journal.get(\'cliSha256\')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():\n            self.unconfigured_stop_bridge(value,journal,payload)\n        for path in IDENTITY_DATABASES.values():\n            if payload["files"].get(path)=={"absent":True}and os.path.lexists(path):\n                raise ValueError("LOCAL refuses to erase an unproven new SAM/database")\n        try:\n            if journal.get("imported")is True and journal["phase"]=="RESUMED":\n                self._failure_stage="IMPORT_CACHE_OBSERVE"\n                actual=self.observe(value,require_empty=False)\n                if (actual["configurationSha256"]!=record["sourceConfigurationFileSha256"]\n                        or actual["databases"]!=journal.get("importedStoppedDatabases")\n                        or {row["unit"]for row in actual["units"]}!={row["unit"]for row in journal["prior"]["units"]}):\n                    raise ValueError("LOCAL imported resume changed")\n                return self.result(record,journal,imported=True)\n            if journal.get("imported")is not True:\n                self._failure_stage="IMPORT_STOP"\n                if journal["phase"]!="STOPPED":self.stop(value,journal,rollback=True)\n                self._failure_stage="IMPORT_STOPPED_OBSERVE"\n                self.stopped_observation()\n                file_payload=dict(payload);file_payload.pop("posixPolicies",None);file_payload.pop("sourceConfigurationSha256",None)\n                self._failure_stage="IMPORT_IDENTITY_RESTORE"\n                self.importer(file_payload)\n                selected=select_restore_domains(file_payload,["SMB"])\n                restored={"privateFingerprints":{path:row["sha256"]for path,row in selected["files"].items()if row.get("absent")is not True}}\n                self._failure_stage="IMPORT_PRIVATE_VERIFY"\n                self.match_private(restored)\n                journal["imported"]=True;journal["phase"]="STOPPED"\n                journal["importedStoppedDatabases"]=self.stopped_observation()["databases"]\n                journal["importedFingerprints"]=restored["privateFingerprints"]\n                journal["importedCheckpointSha256"]=service_cipher_digest(record)\n                self._failure_stage="IMPORT_CHECKPOINT_PUBLISH"\n                self.write(scope,"journal",journal)\n            if not isinstance(journal.get("importedStoppedDatabases"),dict)or not isinstance(journal.get("importedFingerprints"),dict):\n                raise ValueError("LOCAL restored descriptor authority is absent")\n            resume_record={**record,"stoppedDatabases":journal["importedStoppedDatabases"],"privateFingerprints":journal["importedFingerprints"]}\n            self._failure_stage="IMPORT_CONFIG_RESTORE"\n            self.restore_source_smb_configuration(value,journal,record)\n            self.resume(value,journal,resume_record);return self.result(record,journal,imported=True)\n        except Exception as error:\n            self.record_failure(scope,journal,error);raise\n\n'
        edits=[]
        for label in ("PYIDENTITY","PYRENDEREDGENERATION"):
            begin=("<<'"+label+"'\n").encode();start=current.index(begin)+len(begin)
            finish=current.index(("\n"+label+"\n").encode(),start);block=current[start:finish]
            classes=[n for n in ast.parse(block.decode()).body if isinstance(n,ast.ClassDef)and n.name=="SmbSourceCheckpoint"]
            self.assertEqual(1,len(classes));lines=block.splitlines(keepends=True)
            methods={n.name:n for n in classes[0].body if isinstance(n,ast.FunctionDef)}
            for name,checksum in private_pins.items():
                node=methods[name];raw=b"".join(lines[node.lineno-1:node.end_lineno])
                self.assertEqual(checksum,hashlib.sha256(raw).hexdigest());whole=raw+b"\n"
                self.assertEqual(1,block.count(whole))
                block=block.replace(whole,private_old_import.encode()if name=="import_source"else b"",1)
            edits.append((start,finish,block))
        for start,finish,block in reversed(edits):current=current[:start]+block+current[finish:]
        private_driver_spans={'persistBefore': '            self.persist_desired(previous)\n', 'persistAfter': '            if "SMB" in domains:\n                SmbSourceCheckpoint(self.runtime.cli).prepare_rendered_target_private_restore(request)\n            self.persist_desired(previous)\n', 'replayBefore': '            return self.runtime.replay(path, domain, rollback)\n', 'replayAfter': '            result=self.runtime.replay(path, domain, rollback)\n            if not rollback and domain=="SMB"and request.get("localSourceCheckpointReference")is not None:\n                SmbSourceCheckpoint(self.runtime.cli).capture_rendered_target_private(request)\n            return result\n'}
        for after,before in ((private_driver_spans["persistAfter"],private_driver_spans["persistBefore"]),
                             (private_driver_spans["replayAfter"],private_driver_spans["replayBefore"])):
            self.assertEqual(1,current.count(after.encode()));current=current.replace(after.encode(),before.encode(),1)
        self.assertEqual("4eb04e5f32ce083de0fbfe538b5a265045d3fae5be3ecd7caf77e8bbfb22893b",hashlib.sha256(current).hexdigest())
        # Reverse only the exact reviewed LOCAL/SERVICE composition; the
        # production historical bridge still rejects every unrelated CLI byte.
        import ast
        service_pins={'same_service_writer': '48a7399d91cbca0833a47183e47fcbbfe7da48274800c730615693c1a1d008b4', 'state': 'e206bbd8f1eb8496a7ca3842adcd2a7d981a3beabc87518b7339f8f21562f42a'}
        original_state='    def state(self,value,source_only=True,terminal=False,recovery=False):\n        scope={key:value[key]for key in LOCAL_SOURCE_SCOPE}\n        pending=self.read(self.handler.generations/"pending.json");current=self.read(self.handler.generations/"current.json")or {}\n        expected_phases=("PREPARED","VERIFIED")if recovery else("PREPARED",)\n        if pending is None and terminal:\n            completed=self.read(self.handler.generations/(scope["operationUuid"]+".json"))\n            journal=self.read(self.path(scope,"journal"))\n            if (not completed or completed.get("phase")!="ROLLED_BACK"or not journal or journal.get("phase")!="RESUMED"\n                    or completed.get("previous")!=value["sourceGeneration"]):\n                raise ValueError("LOCAL terminal observation lacks its rolled-back original writer")\n            pending=completed;expected_phases=("ROLLED_BACK",)\n        if not isinstance(pending,dict):raise ValueError("LOCAL source original pending writer is absent")\n        if (pending.get("phase")not in expected_phases or any(pending.get(k)!=scope[k]for k in ("instanceUuid","operationUuid","revision"))\n                or pending.get("previous")!=value["sourceGeneration"]or current!=value["sourceGeneration"]\n                or pending.get("beforeSha256")!=value["sourceConfigurationSha256"]or self.boot()!=value["expectedBootId"]):\n            raise ValueError("LOCAL source PREPARED authority changed")\n        if current:\n            if (current.get("instanceUuid")!=scope["instanceUuid"]or current.get("revision")!=scope["revision"]-1\n                    or current.get("configurationSha256")!=value["sourceConfigurationSha256"]):\n                raise ValueError("LOCAL source current generation is foreign")\n            frozen=self.command(("operation","generation","frozen","/dev/stdin"),{k:current[k]for k in ("instanceUuid","operationUuid","revision")})\n            if frozen.get("generation")!=current or frozen.get("configurationSha256")!=value["sourceConfigurationSha256"]:\n                raise ValueError("LOCAL source verified artifact changed")\n        elif scope["revision"]!=1:raise ValueError("LOCAL initial source has a later revision")\n        status=self.command(("operation","generation","status"))\n        maintenance=self.command(("operation","maintenance","status"));rendered=self.command(("operation","generation","render-status"))\n        activation=rendered.get("activation")or {};pin=value.get("renderedManifestSha256")\n        held_local=(isinstance(pin,str)and re.fullmatch("[0-9a-f]{64}",pin)and activation.get("scope")=={k:scope[k]for k in("instanceUuid","operationUuid","revision")}\n            and activation.get("phase")=="ROLLING_BACK"and activation.get("targetSha256")==pin\n            and (rendered.get("current")or {}).get("manifestSha256")==activation.get("previousSha256"))\n        if (status.get("generation")not in (current,None)or status.get("bootId")!=value["expectedBootId"]\n                or maintenance.get("bootHeld")is not False or not(rendered.get("bootHeld")is False or held_local)):\n            raise ValueError("LOCAL source cannot borrow ROOT/SERVICE/rendered hold")\n        if source_only and status.get("configurationSha256")!=value["sourceConfigurationSha256"]:\n            raise ValueError("LOCAL source canonical seven changed")\n        active=rendered.get("current")\n        if active is not None and(not current or active.get("scope")!={k:current[k]for k in ("instanceUuid","operationUuid","revision")}\n                or active.get("configurationSha256")!=value["sourceConfigurationSha256"]):\n            raise ValueError("LOCAL source rendered baseline is foreign")\n        domain=self.read(self.handler.configuration/"smb-domain.json")\n        if domain and(domain.get("joined")is True or domain.get("identityProvider")=="AD"or domain.get("state")=="JOINED"):\n            raise ValueError("LOCAL source cannot capture joined AD")\n        if any(os.path.lexists(n)for n in ("/etc/ablestack-storage/ad-machine.conf","/etc/krb5.keytab")):\n            raise ValueError("LOCAL source contains protected AD artifacts")\n        return status\n\n'
        edits=[]
        for label in ("PYIDENTITY","PYRENDEREDGENERATION"):
            start=current.index(("<<'"+label+"'\n").encode())+len(("<<'"+label+"'\n").encode())
            finish=current.index(("\n"+label+"\n").encode(),start);block=current[start:finish]
            tree=ast.parse(block.decode());classes=[n for n in tree.body if isinstance(n,ast.ClassDef)and n.name=="SmbSourceCheckpoint"]
            self.assertEqual(1,len(classes));lines=block.splitlines(keepends=True)
            methods={n.name:n for n in classes[0].body if isinstance(n,ast.FunctionDef)}
            for name in ("same_service_writer","state"):
                node=methods[name];raw=b"".join(lines[node.lineno-1:node.end_lineno])
                self.assertEqual(service_pins[name],hashlib.sha256(raw).hexdigest())
                whole=raw+b"\n";self.assertEqual(1,block.count(whole))
                block=block.replace(whole,b""if name=="same_service_writer"else original_state.encode(),1)
            edits.append((start,finish,block))
        for start,finish,block in reversed(edits):current=current[:start]+block+current[finish:]
        # Reverse only the reviewed fresh-stage parent creation, never grant
        # production LOCAL compatibility to this unrelated whole-CLI change.
        private_parents = b'            parents = set()\n            for name in normalized:\n                parent = (temporary / name).parent\n                while parent != temporary:\n                    parents.add(parent)\n                    parent = parent.parent\n            for parent in sorted(parents, key=lambda path: (len(path.parts), str(path))):\n                rendered_directory(parent, True)\n'
        self.assertEqual(1,current.count(private_parents))
        current=current.replace(private_parents,b"",1)
        # Exact test-only reversal of Code6's new read-only witness. Production
        # LOCAL compatibility must still reject this unrelated whole-CLI delta.
        spans=(
            (b"# BEGIN EMBEDDED CLONE_INITIAL_WITNESS_V1\n",b"# END EMBEDDED CLONE_INITIAL_WITNESS_V1\n\n",
             "eb5cfff8ebdc55c6d157182a29629fc9f6e0710554622b373acc372fa996a20d"),
            (b"# BEGIN CLONE_INITIAL_WITNESS_V1_DISPATCH\n",b"# END CLONE_INITIAL_WITNESS_V1_DISPATCH\n",
             "f1cfe90e4082dcaf0a8b0658bdc50853982c836b3622000f150ab5483b1f12f2"))
        for begin,end,checksum in spans:
            self.assertEqual(1,current.count(begin));self.assertEqual(1,current.count(end))
            start=current.index(begin);finish=current.index(end,start)+len(end)
            self.assertEqual(checksum,hashlib.sha256(current[start:finish]).hexdigest())
            current=current[:start]+current[finish:]
        for marker in(b"operation:generation:clone-initial-inspect|",b',"cloneInitialBaselineV1Supported":true'):
            self.assertEqual(1,current.count(marker));current=current.replace(marker,b"",1)
        self.assertEqual("8816bb08197640e41f60b52af60b339fc3be47b34ddbd760d3c7e82336aeb26a",
                         hashlib.sha256(current).hexdigest())
        # Test-only projection of the later mounted-source recheck fix. The production
        # closed bridge must continue to reject unrelated whole-CLI changes.
        later=b"    resolved = resolve_volume_device(fresh, volume_uuid=volume_uuid, mount_source=expected_device, expected_size=size, allow_blank_size_fallback=False)"
        earlier=b"    resolved = resolve_volume_device(fresh, volume_uuid=volume_uuid, expected_size=size, allow_blank_size_fallback=False)"
        self.assertEqual(1,current.count(later))
        current=current.replace(later,earlier,1)
        self.assertEqual("b8152f8c886052c35c201bc38b1723bc8013418df78ad9bf6ea347e8f606438d",
                         hashlib.sha256(current).hexdigest())
        start=current.index(b"apply_nfs_exports() {")
        finish=current.index(b"\nganesha_feature_capabilities() {",start)
        block=current[start:finish]
        changes=((b"import copy\nimport json\nfrom pathlib import Path",
                  b"import json\nfrom pathlib import Path"),
                 (b'for export in copy.deepcopy(payload.get("exports", [])):',
                  b'for export in payload.get("exports", []):'))
        for later,earlier in changes:
            self.assertEqual(1,block.count(later))
            block=block.replace(later,earlier,1)
        current=current[:start]+block+current[finish:]
        changes=((b"def nfs_dynamic_redact(value):\n    return pending_nfs_redact(value)\n",b""),
                 (b'clean_payload=nfs_dynamic_redact({key:value for key,value in payload.items() if key!="operationScope"})',
                  b'clean_payload={key:value for key,value in payload.items() if key!="operationScope"}'))
        for later,earlier in changes:
            self.assertEqual(2,current.count(later))
            current=current.replace(later,earlier)
        self.assertEqual("16df10b9273aefa7e2760eccd22c49acca0e84fd95d57152539e71386fa531a6",
                         hashlib.sha256(current).hexdigest())
        return current

    def historical_cbeef_before_expected_mount_guard(self):
        # Project only the exact reviewed later three spans, never authorize them in a production bridge.
        import ast,re
        current=self.historical_16df_before_nfs_request_isolation()
        block=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",current.decode(),re.S)
                   if "def format_empty_device():"in value)
        node=next(value for value in ast.parse(block).body if isinstance(value,ast.FunctionDef)
                  and value.name=="require_expected_mount_filesystem")
        helper=ast.get_source_segment(block,node).encode()+b"\n\n\n"
        self.assertEqual("d4bd949fd137566f4a324ef670493d27f2e9f1b8faf99f70f5e857b8912de0b8",hashlib.sha256(helper).hexdigest())
        spans=(helper,b"    require_expected_mount_filesystem(payload, device)\n",
               b',"expectedFilesystemUuidGuardSupported":true')
        for span in spans:
            self.assertEqual(1,current.count(span));current=current.replace(span,b"",1)
        self.assertEqual("cbeef9c50fe6cef72983943ec3c7bcea5f2bc5d69b0128a88702bbcf4a92807e",
                         hashlib.sha256(current).hexdigest())
        return current

    def initial_475_original_cli(self):
        import ast
        current=self.historical_cbeef_before_expected_mount_guard();original=current
        for label in("PYIDENTITY","PYRENDEREDGENERATION"):
            begin=("<<'"+label+"'\n").encode();end=("\n"+label+"\n").encode()
            start=original.index(begin)+len(begin);finish=original.index(end,start);block=original[start:finish]
            cls=next(node for node in ast.parse(block.decode()).body if isinstance(node,ast.ClassDef)and node.name=="SmbSourceCheckpoint")
            lines=block.splitlines(keepends=True)
            methods={node.name:b"".join(lines[node.lineno-1:node.end_lineno])for node in cls.body if isinstance(node,ast.FunctionDef)}
            for name,raw in INITIAL_STOP_475_ORIGINAL_METHODS.items():
                self.assertEqual(1,block.count(methods[name]))
                block=block.replace(methods[name],raw.encode(),1)
            pins=next(line for line in block.splitlines(keepends=True)if line.startswith(b"LOCAL_UNCONFIGURED_STOP_BRIDGE_METHOD_SHA256="))
            block=block.replace(pins,INITIAL_STOP_475_ORIGINAL_PINS.encode(),1)
            original=original[:start]+block+original[finish:]
        self.assertEqual("475dc43cadc01191da5904e9fed15f67144d7423fdc21e9494d66d093b58ba6c",hashlib.sha256(original).hexdigest())
        return original,current

    def test_later_mount_and_nfs_request_guards_do_not_expand_the_closed_initial475_bridge(self):
        original,_=self.initial_475_original_cli()
        with self.assertRaises(ValueError):
            self.native.unconfigured_stop_bridge_cli(fixture.CLI.read_bytes(),original)

    def unconfigured_bridge_fixture(self,initial=False):
        self.initial_unconfigured_source()if initial else self.verified_unconfigured_source()
        captured=self.native.export(self.export_request)
        original,current=self.initial_475_original_cli()if initial else self.historical_unconfigured_cli()
        runtime=self.root/"bridge-runtime";runtime.mkdir(mode=0o700);(runtime/"releases").mkdir(mode=0o700)
        installs=self.root/"bridge-installs";installs.mkdir(mode=0o700)
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives import serialization
        signer=Ed25519PrivateKey.generate()
        keys=runtime/"trusted-keys";keys.mkdir(mode=0o700)
        (keys/"bridge-fixture.pem").write_bytes(signer.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
        for version,raw in(("original-stop",original),("current-fixed",current)):
            release=runtime/"releases"/version;release.mkdir(mode=0o700)
            entry=release/"ablestack-storagectl";entry.write_bytes(raw);entry.chmod(0o755)
            manifest={"bundleVersion":version,"keyId":"bridge-fixture","files":[{"path":"ablestack-storagectl",
                "sha256":hashlib.sha256(raw).hexdigest(),"mode":"0755","owner":"root","group":"root"}]}
            generation.atomic_json(release/"manifest.json",manifest)
            tx=installs/("runtime-"+version);tx.mkdir(mode=0o700)
            (tx/"manifest.json").write_bytes((release/"manifest.json").read_bytes())
            (tx/"manifest.sig").write_bytes(signer.sign((tx/"manifest.json").read_bytes()))
            generation.atomic_json(tx/"state.json",{"transactionId":tx.name,"bundleVersion":version,"releasePath":str(release),
                "keyId":"bridge-fixture","phase":"COMPLETE","archiveSha256":"a"*64,
                "manifestSha256":hashlib.sha256((tx/"manifest.json").read_bytes()).hexdigest()})
        (runtime/"current").symlink_to(runtime/"releases/current-fixed")
        self.native.runtime_root=runtime;self.native.installation_root=installs
        self.native.cli=runtime/"releases/current-fixed/ablestack-storagectl"
        journal=self.native.read(self.native.path(self.request,"journal"))
        journal["cliSha256"]=hashlib.sha256(original).hexdigest();journal["phase"]="RECOVERY_REQUIRED"
        self.native.write(self.request,"journal",journal)
        pending=generation.read_json(self.gen.pending);pending["startedAt"]=1234
        generation.atomic_json(self.gen.pending,pending)
        return captured,journal,original,current

    def test_initial_475_bridge_exact_full_source_delta_is_initial_only_and_rejects_foreign_bytes(self):
        original,current=self.initial_475_original_cli()
        self.assertTrue(self.native.unconfigured_stop_bridge_cli(current,original,initial=True))
        with self.assertRaises(ValueError):self.native.unconfigured_stop_bridge_cli(current,original)
        variants=(current+b"\n# foreign complete CLI byte\n",
            current.replace(b"    def stable_sessions_empty",b"    def foreign_sessions_empty",1),
            current.replace(b"smb_endpoint_run() {",b"foreign_smb_endpoint_run() {",1),
            current.replace(b"self._failure_stage=None",b"self._failure_stage='UNKNOWN'",1))
        for changed in variants:
            with self.subTest(sha=hashlib.sha256(changed).hexdigest()):
                with self.assertRaises(ValueError):self.native.unconfigured_stop_bridge_cli(changed,original,initial=True)
        with self.assertRaisesRegex(ValueError,"original ENTRY"):
            self.native.unconfigured_stop_bridge_cli(current,original+b"\n",initial=True)

    def test_initial_475_signed_bridge_status_is_readonly_then_authenticated_import_publishes_once(self):
        captured,journal,original,current=self.unconfigured_bridge_fixture(initial=True)
        before={path.name:path.read_bytes()for path in self.native.root.glob("*.json")}
        self.assertTrue(self.native.status(self.request)["sourceIdentityRestoreSupported"])
        self.assertEqual(before,{path.name:path.read_bytes()for path in self.native.root.glob("*.json")})
        receipt_path=self.native.path(self.request,"unconfigured-stop-code-compatibility")
        self.assertFalse(receipt_path.exists())
        result=self.native.import_source(self.imported_request(captured))
        self.assertTrue(result["sourceIdentityRestored"]);self.assertTrue(result["sourceRuntimeVerified"])
        receipt=self.native.read(receipt_path)
        self.assertEqual({},receipt["common"]["sourceGeneration"])
        self.assertEqual(1,receipt["scope"]["revision"])
        self.assertEqual("475dc43cadc01191da5904e9fed15f67144d7423fdc21e9494d66d093b58ba6c",receipt["originalCliSha256"])
        self.assertTrue(receipt["emptySmbIdentityAuthenticated"]);self.assertFalse(receipt["formatOrAccountMutationAuthorized"])
        saved=receipt_path.read_bytes()
        self.assertTrue(self.native.import_source(self.imported_request(captured))["sourceIdentityRestored"])
        self.assertEqual(saved,receipt_path.read_bytes())
        self.assertEqual(before[self.native.path(self.request,"cipher").name],self.native.path(self.request,"cipher").read_bytes())
        self.assertFalse(self.gen.current.exists());self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_initial_475_signed_bridge_rejects_unsigned_installations_and_missing_actual_fd9(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture(initial=True)
        for version in("original-stop","current-fixed"):
            signature=self.native.installation_root/("runtime-"+version)/"manifest.sig"
            raw=signature.read_bytes();signature.unlink()
            with self.assertRaisesRegex(ValueError,"signed installation"):self.native.status(self.request)
            signature.write_bytes(raw)
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":""}):
            with self.assertRaises(ValueError):self.native.import_source(self.imported_request(captured))
        self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())

    def test_initial_475_bridge_rejects_changed_public_cipher_scope_generation_and_source(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture(initial=True)
        cp=self.native.path(self.request,"cipher");original=cp.read_bytes()
        for failure in("public-key","ciphertext","scope","generation","boot","source","stopped","old-cli"):
            with self.subTest(failure=failure):
                request=copy.deepcopy(self.request);changed=copy.deepcopy(journal)
                if failure=="public-key":changed["publicKey"]="FOREIGN PUBLIC WRAPPING KEY"
                if failure=="ciphertext":
                    record=json.loads(original)
                    record["capsule"]["ciphertext"]=base64.b64encode(b"FOREIGN CIPHER").decode()
                    cp.write_bytes(json.dumps(record,sort_keys=True,separators=(",",":")).encode())
                if failure=="scope":request["operationUuid"]=str(uuid.uuid4())
                if failure=="generation":request["sourceGeneration"]={"revision":0}
                if failure=="boot":request["expectedBootId"]=str(uuid.uuid4())
                if failure=="source":changed["sourceDesiredState"]["desired-state/smb-share-apply.json"]={}
                if failure=="stopped":changed["stopped"]["sessionsVerifiedEmpty"]=False
                if failure=="old-cli":changed["cliSha256"]="f"*64
                try:
                    with self.assertRaises((ValueError,TypeError)):self.native.unconfigured_stop_bridge(request,changed)
                finally:cp.write_bytes(original)
        self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())

    def test_initial_475_bridge_authenticated_capsule_and_exact_reference_precede_receipt_write(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture(initial=True)
        for failure in("private-key","reference","nonce"):
            request=copy.deepcopy(self.imported_request(captured))
            if failure=="private-key":request["credentialPrivateKey"]=self.pem(self.key)
            if failure=="reference":request["localSourceCheckpointReference"]["sha256"]="f"*64
            if failure=="nonce":request["capsule"]["nonce"]=base64.b64encode(b"wrong_nonce!").decode()
            with self.subTest(failure=failure):
                with self.assertRaises(Exception):self.native.import_source(request)
            self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_initial_475_bridge_rechecks_database_absence_and_does_not_publish_from_status_or_export(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture(initial=True)
        cp=self.native.path(self.request,"cipher");record=self.native.read(cp)
        payload=self.native.decode(self.imported_request(captured),record)
        for request in(self.request,self.export_request):
            with self.subTest(fields=sorted(request)):
                with self.assertRaises(ValueError):self.native.unconfigured_stop_bridge(request,journal,payload)
        actual=os.path.lexists
        with patch.object(module.os.path,"lexists",side_effect=lambda path:str(path)=="/var/lib/samba/private/passdb.tdb"or actual(path)):
            with self.assertRaises(ValueError):self.native.status(self.request)
        self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_known_unconfigured_bridge_status_mints_nothing_then_authenticated_import_restores(self):
        captured,journal,original,current=self.unconfigured_bridge_fixture()
        before={path.name:path.read_bytes()for path in self.native.root.glob("*.json")}
        self.assertTrue(self.native.status(self.request)["sourceIdentityRestoreSupported"])
        self.assertEqual(before,{path.name:path.read_bytes()for path in self.native.root.glob("*.json")})
        bridge=self.native.path(self.request,"unconfigured-stop-code-compatibility")
        self.assertFalse(bridge.exists())
        result=self.native.import_source(self.imported_request(captured))
        self.assertTrue(result["sourceIdentityRestored"]);self.assertTrue(result["sourceSmbResumed"])
        receipt=self.native.read(bridge)
        self.assertEqual("LOCAL_UNCONFIGURED_SOURCE_STOP_GUARD_COMPATIBILITY",receipt["kind"])
        self.assertTrue(receipt["emptySmbIdentityAuthenticated"]);self.assertFalse(receipt["formatOrAccountMutationAuthorized"])
        self.assertEqual(before[self.native.path(self.request,"cipher").name],self.native.path(self.request,"cipher").read_bytes())
        saved=bridge.read_bytes()
        self.assertTrue(self.native.import_source(self.imported_request(captured))["sourceIdentityRestored"])
        self.assertEqual(saved,bridge.read_bytes())
        self.assertFalse(self.signals);self.assertFalse(self.starts)

    def test_known_unconfigured_bridge_rejects_unreviewed_whole_cli_and_method_changes(self):
        original,current=self.historical_unconfigured_cli()
        self.assertTrue(self.native.unconfigured_stop_bridge_cli(current,original))
        changes=(current+b"\n# foreign shell header\n",
            current.replace(b"    def stable_sessions_empty",b"    def unknown_stable_sessions_empty",1),
            current.replace(b"accounts={",b"accounts = {",1),
            current.replace(b"smb_endpoint_run() {",b"foreign_smb_endpoint_run() {",1),
            current.replace(b"self._failure_stage=None",b"self._failure_stage='UNKNOWN'",1),
            current.replace(b'pending.get("phase")',b'pending.get("foreignPhase")',1))
        for changed in changes:
            with self.subTest(hash=hashlib.sha256(changed).hexdigest()):
                self.assertNotEqual(hashlib.sha256(current).hexdigest(),hashlib.sha256(changed).hexdigest())
                with self.assertRaises(ValueError):self.native.unconfigured_stop_bridge_cli(changed,original)
        with self.assertRaises(ValueError):self.native.unconfigured_stop_bridge_cli(current,original+b"\n# foreign original header\n")

    def test_known_unconfigured_bridge_cannot_publish_from_read_or_export_requests(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture()
        record=self.native.read(self.native.path(self.request,"cipher"));payload=self.native.decode(self.imported_request(captured),record)
        for request in(self.request,self.export_request):
            with self.subTest(fields=sorted(request)),self.assertRaises(ValueError):
                self.native.unconfigured_stop_bridge(request,journal,payload)
        self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())

    def test_known_unconfigured_bridge_rejects_nonempty_or_incomplete_authenticated_domains(self):
        self.verified_unconfigured_source()
        payload=self.empty_payload()
        for mode in("file","missing-file","account","missing-account","ad"):
            with self.subTest(mode=mode):
                changed=copy.deepcopy(payload)
                if mode=="file":
                    raw=b"PUBLIC SYNTHETIC BODY";changed["files"]["/etc/ablestack-storage/smb-managed-identities.json"]={
                        "data":base64.b64encode(raw).decode(),"sha256":hashlib.sha256(raw).hexdigest(),"mode":0o600,"uid":0,"gid":0}
                if mode=="missing-file":changed["files"].pop("/etc/ablestack-storage/smb-managed-identities.json")
                if mode=="account":changed["accounts"]["/etc/group"]=["fixture_group:x:1001:"]
                if mode=="missing-account":changed["accounts"].pop("/etc/gshadow")
                if mode=="ad":changed["adIdentity"]={}
                with self.assertRaises(ValueError):self.native.unconfigured_stop_empty_identity(changed)

    def test_known_unconfigured_bridge_rejects_source_boot_pending_target_and_collection_changes(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture()
        for mode in("names","hosts","auth","source","scope","boot","target"):
            with self.subTest(mode=mode):
                changed=copy.deepcopy(journal);request=copy.deepcopy(self.request)
                if mode=="names":changed["names"]=["fixture_user"]
                if mode=="hosts":changed["nvmeHosts"]=["nqn.2026-01.fixture:host"]
                if mode=="auth":changed["authReplayDomains"]=["ISCSI"]
                if mode=="source":changed["sourceDesiredState"]["desired-state/smb-share-apply.json"]={}
                if mode=="scope":request["operationUuid"]=str(uuid.uuid4())
                if mode=="boot":request["expectedBootId"]=str(uuid.uuid4())
                target=self.gen.root/(self.scope["operationUuid"]+".json")
                if mode=="target":generation.atomic_json(target,{"phase":"VERIFIED"})
                try:
                    with self.assertRaises((ValueError,TypeError)):self.native.unconfigured_stop_bridge(request,changed)
                finally:
                    if mode=="target":target.unlink()
        self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())

    def test_known_unconfigured_bridge_rejects_unsigned_receipt_foreign_current_identity_and_no_fd9(self):
        captured,journal,_,_=self.unconfigured_bridge_fixture()
        signature=self.native.installation_root/"runtime-original-stop"/"manifest.sig";raw=signature.read_bytes();signature.unlink()
        with self.assertRaisesRegex(ValueError,"signed installation"):self.native.status(self.request)
        signature.write_bytes(raw)
        original=os.path.lexists
        with patch.object(os.path,"lexists",side_effect=lambda path:str(path)=="/etc/ablestack-storage/smb-managed-identities.json"or original(path)):
            with self.assertRaisesRegex(ValueError,"current SMB identity"):self.native.status(self.request)
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":""}):
            with self.assertRaises(ValueError):self.native.import_source(self.imported_request(captured))
        self.assertFalse(self.native.path(self.request,"unconfigured-stop-code-compatibility").exists())

    def configured_generated_smb_source(self):
        import native_renderers
        import shutil
        source={"instanceUuid":self.scope["instanceUuid"],"netbiosName":"FIXTURESOURCE","enabled":True,
            "listeners":[{"listenIp":"10.10.13.243","port":445}],
            "shares":[{"uuid":str(uuid.uuid4()),"name":"fixture-share","path":"/export/fixture-share","state":"Ready",
                "config":{"backingPath":"/srv/ablestack-storage/volumes/"+str(uuid.uuid4())+"/export/fixture-share"},
                "acls":[{"principalType":"LOCAL_USER","principal":"fixture_user","permission":"READ_WRITE","state":"Ready"}]}]}
        self.desired["desired-state/smb-share-apply.json"]=source
        self.gen.replace_desired(self.desired);self.current["configurationSha256"]=self.gen.digest()
        generation.atomic_json(self.gen.current,self.current)
        generation.atomic_json(self.gen.root/(self.current["operationUuid"]+".json"),{**self.current,"phase":"VERIFIED","desired":self.desired})
        generation.atomic_json(self.gen.pending,{**self.scope,"phase":"PREPARED","previous":self.current,"beforeSha256":self.current["configurationSha256"]})
        self.request.update(sourceGeneration=self.current,sourceConfigurationSha256=self.current["configurationSha256"])
        self.export_request.update(self.request)
        config=self.root/"samba"/"smb.conf";config.parent.mkdir(mode=0o700)
        expected=native_renderers.render_smb_candidate(source,self.native.cli)["smb/smb.conf"].encode()
        config.write_bytes(expected);config.chmod(0o644)
        original=self.read
        def mapped(path,*args):
            return capsule.regular_file(config,*args) if str(path)=="/etc/samba/smb.conf" else original(path)
        self.read=mapped;self.native.reader=mapped
        runtime=self.root/"runtime";release=runtime/"releases"/"fixture";release.mkdir(parents=True)
        cli=release/"ablestack-storagectl";shutil.copyfile(self.native.cli,cli);cli.chmod(0o755)
        (runtime/"current").symlink_to(release,target_is_directory=True)
        self.native.cli=cli;self.native.runtime_root=runtime
        self.code_authority_checks=[]
        def signed(path,checksum):
            self.assertEqual(cli,path);raw=cli.read_bytes();self.assertEqual(hashlib.sha256(raw).hexdigest(),checksum)
            self.code_authority_checks.append(checksum);return raw
        self.native.compat_entry=signed
        self.config_path_patch=patch.object(module,"LOCAL_SOURCE_SMB_CONFIG",config,create=True)
        self.config_path_patch.start();self.addCleanup(self.config_path_patch.stop)
        return config,expected

    def test_generated_source_config_is_restored_while_stopped_before_import_resume(self):
        config,expected=self.configured_generated_smb_source()
        captured=self.native.export(self.export_request)
        for path in self.private.values():path.write_bytes(b"SYNTHETIC TARGET MUTATION")
        config.write_bytes(b"PUBLIC TARGET CONFIG WITH NEW SHARE")
        observed=self.native.import_source(self.imported_request(captured))
        self.assertTrue(observed["sourceIdentityRestored"]);self.assertTrue(observed["sourceRuntimeVerified"])
        self.assertEqual(expected,config.read_bytes());self.assertEqual(0o644,config.stat().st_mode&0o777)
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})
        self.assertEqual(self.current["configurationSha256"],self.gen.digest())
        self.assertTrue(self.code_authority_checks)
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertEqual("RESUMED",journal["phase"])
        self.assertEqual("VERIFIED",journal["sourceConfigurationRestoreStage"])
        counts=(len(self.signals),len(self.starts),len(self.code_authority_checks))
        self.assertTrue(self.native.import_source(self.imported_request(captured))["sourceIdentityRestored"])
        self.assertEqual(counts,(len(self.signals),len(self.starts),len(self.code_authority_checks)))

    def test_generated_source_candidate_hash_mismatch_preserves_target_and_stopped_identity(self):
        config,expected=self.configured_generated_smb_source()
        config.write_bytes(expected+b"# PUBLIC UNREPRODUCIBLE SOURCE COMMENT"+bytes([10]))
        captured=self.native.export(self.export_request);target=b"PUBLIC TARGET CONFIG"
        config.write_bytes(target)
        with self.assertRaisesRegex(ValueError,"original hash"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.active_units)
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertTrue(journal["imported"]);self.assertEqual("RECOVERY_REQUIRED",journal["phase"])
        self.assertEqual("CANDIDATE_HASH",journal["sourceConfigurationRestoreStage"])
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})

    def test_generated_source_testparm_failure_never_publishes_or_starts(self):
        config,expected=self.configured_generated_smb_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target);original=self.handler.run
        def reject(args):
            if args[:2]==["testparm","-s"]:raise ValueError("fixture validator unavailable")
            return original(args)
        self.handler.run=reject
        with self.assertRaisesRegex(ValueError,"configuration validation failed"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.active_units)
        self.assertEqual(["smb.conf"],sorted(p.name for p in config.parent.iterdir()))
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertEqual("TESTPARM",journal["sourceConfigurationRestoreStage"])

    def test_generated_source_requires_original_signed_renderer_authority(self):
        config,expected=self.configured_generated_smb_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target)
        self.native.compat_entry=lambda *args:(_ for _ in ()).throw(ValueError("fixture signed code rejected"))
        with self.assertRaisesRegex(ValueError,"signed code rejected"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.active_units)

    def test_generated_source_rejects_imported_stopped_inode_replacement(self):
        config,expected=self.configured_generated_smb_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target);original=self.native.restore_source_smb_configuration
        def changed(value,journal,record):
            path=self.private["PASSDB"];replacement=path.with_suffix(".foreign")
            replacement.write_bytes(path.read_bytes());replacement.chmod(0o600);os.replace(replacement,path)
            return original(value,journal,record)
        self.native.restore_source_smb_configuration=changed
        with self.assertRaisesRegex(ValueError,"stopped metadata changed"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.active_units)

    def test_generated_source_rejects_imported_private_byte_drift(self):
        config,expected=self.configured_generated_smb_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target);original=self.native.restore_source_smb_configuration
        def changed(value,journal,record):
            self.private["PASSDB"].write_bytes(b"SYNTHETIC FOREIGN PRIVATE BYTES")
            return original(value,journal,record)
        self.native.restore_source_smb_configuration=changed
        with self.assertRaisesRegex(ValueError,"opaque source bytes changed"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.active_units)

    def test_generated_source_rejects_live_acceptor_before_configuration_write(self):
        config,expected=self.configured_generated_smb_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target);original=self.native.restore_source_smb_configuration
        def changed(value,journal,record):
            self.active_units.add("smbd.service")
            return original(value,journal,record)
        self.native.restore_source_smb_configuration=changed
        with self.assertRaisesRegex(ValueError,"still owns active"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.code_authority_checks)

    def test_generated_source_rejects_foreign_canonical_state_before_render(self):
        config,expected=self.configured_generated_smb_source();captured=self.native.export(self.export_request)
        target=b"PUBLIC TARGET CONFIG";config.write_bytes(target);original=self.native.restore_source_smb_configuration
        def changed(value,journal,record):
            foreign=copy.deepcopy(self.desired);foreign["desired-state/smb-share-apply.json"]["shares"][0]["name"]="foreign"
            self.gen.replace_desired(foreign)
            return original(value,journal,record)
        self.native.restore_source_smb_configuration=changed
        with self.assertRaisesRegex(ValueError,"canonical seven changed"):
            self.native.import_source(self.imported_request(captured))
        self.assertEqual(target,config.read_bytes());self.assertFalse(self.active_units)
        self.assertFalse(self.code_authority_checks)

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

    def test_default_collector_keyword_reaches_real_generic_collect_and_crypto(self):
        fresh=module.SmbSourceCheckpoint(handler=self.handler,command=self.local_command,root=self.native.root)
        fresh.__dict__.update({key:value for key,value in self.native.__dict__.items()if key not in ('collector','posix_collector')})
        # Keep the production collector adapter intact. Only filesystem locations
        # and unit observations are fixtures; the generic body and RSA/AEAD run.
        accounts=self.root/'controlled-accounts';accounts.mkdir()
        account_files={}
        for name in ('passwd','shadow','group','gshadow'):
            path=accounts/name;path.write_text('');path.chmod(0o600);account_files['/etc/'+name]=path
        regular=capsule.regular_file
        def controlled(path,*args):return regular(account_files.get(str(path),path),*args)
        with patch.object(capsule,'FILES',{str(path)for path in self.private.values()}),\
                patch.object(capsule,'regular_file',side_effect=controlled),\
                patch.object(module,'collect',wraps=capsule.collect)as observed:
            result=fresh.export(self.export_request)
            self.assertEqual({},observed.call_args.kwargs['posix_policies'])
            decoded=capsule.decrypt(result['capsule'],self.pem(self.newkey),self.scope['instanceUuid']+':'+self.scope['operationUuid'])
            self.assertEqual({},decoded['identity']['posixPolicies'])
            self.assertEqual({str(path)for path in self.private.values()},set(decoded['identity']['files']))
            self.assertTrue(result['sourceSmbResumed']);self.assertTrue(result['sourceRuntimeVerified'])
            with self.assertRaisesRegex(ValueError,'Invalid managed account'):fresh.collector(['root'],[],posix_policies={})
            with self.assertRaises(TypeError):fresh.collector([],[],unexpected_keyword={})
            private=next(iter(self.private.values()));backup=private.with_suffix('.original');private.rename(backup);private.symlink_to(backup)
            try:
                with self.assertRaisesRegex(ValueError,'protected regular file'):fresh.collector([],[],posix_policies={})
            finally:private.unlink();backup.rename(private)

    def test_exact_original_stop_code_compatibility_status_export_and_receipt_guards(self):
        old_frozen='''    def frozen(self,value,journal):
        if (journal.get("scope")!={key:value[key]for key in LOCAL_SOURCE_SCOPE}or journal.get("common")!=self.common(value)
                or journal.get("cliSha256")!=hashlib.sha256(self.cli.read_bytes()).hexdigest()):
            raise ValueError("LOCAL source journal/command authority changed")
        return journal
'''
        new_frozen='''    def frozen(self,value,journal):
        if (journal.get("scope")!={key:value[key]for key in LOCAL_SOURCE_SCOPE}or journal.get("common")!=self.common(value)):
            raise ValueError("LOCAL source journal/command authority changed")
        if journal.get('cliSha256')!=hashlib.sha256(self.cli.read_bytes()).hexdigest():
            self.code_compatibility(value,journal)
        return journal
'''
        old_class='class SmbSourceCheckpoint:\n    def __init__(self):\n        self.collector=lambda *args:collect(*args)\n\n'+old_frozen
        old_status='\n    def status(self,value):\n        view=self.observe(value)\n        return view\n'
        import ast
        module_text=Path(module.__file__).read_text()
        cls=next(node for node in ast.parse(module_text).body if isinstance(node,ast.ClassDef)and node.name=='SmbSourceCheckpoint')
        status_method=next(node for node in cls.body if isinstance(node,ast.FunctionDef)and node.name=='status')
        proof_node=next(node for node in status_method.body if isinstance(node,ast.If)and any(isinstance(part,ast.Constant)and part.value=='localSourceStoppedRuntimeProof'for part in ast.walk(node)))
        proof_source='        '+ast.get_source_segment(module_text,proof_node)+'\n'
        new_status=old_status.replace('        view=self.observe(value)\n',proof_source+'        view=self.observe(value)\n')
        installed_method=next(node for node in cls.body if isinstance(node,ast.FunctionDef)and node.name=='installed_release_authority')
        new_installed='    '+ast.get_source_segment(module_text,installed_method)+'\n'
        old_installed=new_installed.replace("('VERIFIED','COMPLETE','ROLLED_BACK')","('VERIFIED','COMPLETE')").replace(
            "        eligible=[row for row in matches if row['phase']in ('VERIFIED','COMPLETE')]\n"
            "        if not eligible:raise ValueError('LOCAL historical verified installation is absent')\n        return eligible[0]",
            "        return matches[0]")
        self.assertNotEqual(old_installed,new_installed)
        old_class+=old_status+'\n'+old_installed
        helpers=''.join('    def '+name+'(self):\n        pass\n\n'for name in('normalized_collector_cli','compat_entry','code_compatibility'))
        new_class=old_class.replace('self.collector=lambda *args:collect(*args)','self.collector=lambda *args,**kwargs:collect(*args,**kwargs)').replace(old_frozen,helpers+new_frozen).replace(old_status,new_status).replace(old_installed,new_installed)
        def whole(cls):return ''.join("python3 - <<'"+marker+"'\n"+cls+'\n'+marker+'\n'for marker in('PYIDENTITY','PYRENDEREDGENERATION')).encode()
        original=whole(old_class);current=whole(new_class)
        self.assertEqual(original,self.native.normalized_collector_cli(current,original))
        with self.assertRaises(ValueError):self.native.normalized_collector_cli(current+b'\n# foreign shell code\n',original)
        runtime=self.root/'compat-runtime';runtime.mkdir(mode=0o700);(runtime/'releases').mkdir(mode=0o700)
        installs=self.root/'compat-installs';installs.mkdir(mode=0o700)
        for version,data in (('original-stop',original),('current-fixed',current)):
            release=runtime/'releases'/version;release.mkdir(mode=0o700)
            entry=release/'ablestack-storagectl';entry.write_bytes(data);entry.chmod(0o755)
            manifest={'bundleVersion':version,'keyId':'controlled-public-fixture','files':[{'path':'ablestack-storagectl',
                'sha256':hashlib.sha256(data).hexdigest(),'mode':'0755','owner':'root','group':'root'}]}
            generation.atomic_json(release/'manifest.json',manifest)
            tx=installs/('runtime-'+version);tx.mkdir(mode=0o700);generation.atomic_json(tx/'manifest.json',manifest)
            installation={'transactionId':tx.name,'bundleVersion':version,'releasePath':str(release),
                'keyId':manifest['keyId'],'phase':'COMPLETE','archiveSha256':'a'*64,
                'manifestSha256':hashlib.sha256((tx/'manifest.json').read_bytes()).hexdigest()}
            generation.atomic_json(tx/'state.json',installation)
            prior=installs/('runtime-000-rolledback-'+version);prior.mkdir(mode=0o700)
            (prior/'manifest.json').write_bytes((tx/'manifest.json').read_bytes())
            generation.atomic_json(prior/'state.json',{**installation,'transactionId':prior.name,'phase':'ROLLED_BACK'})
        (runtime/'current').symlink_to(runtime/'releases/current-fixed')
        self.native.runtime_root=runtime;self.native.installation_root=installs
        self.native.reader=capsule.regular_file
        self.native.cli=runtime/'releases/current-fixed/ablestack-storagectl'
        prior=self.observation(self.request);self.active_units.clear()
        stopped=self.native.stopped_observation()
        journal={'schemaVersion':1,'scope':{k:self.request[k]for k in module.LOCAL_SOURCE_SCOPE},'common':self.request,
            'cliSha256':hashlib.sha256(original).hexdigest(),'phase':'RECOVERY_REQUIRED','prior':prior,'stopped':stopped,
            'publicKey':self.export_request['publicKey'],'names':[],'nvmeHosts':[],'authReplayDomains':[],
            'sourceDesiredState':self.gen.files()}
        path=self.native.path(self.request,'code-compatibility')
        self.native.frozen(self.request,journal);self.assertFalse(path.exists())
        self.native.write(self.request,'journal',journal)
        readonly_before={str(p):p.read_bytes()for p in self.native.root.glob('*.json')}
        status_proof=self.native.status(self.request)['localSourceStoppedRuntimeProof']
        self.assertEqual(15,len(status_proof));self.assertFalse(status_proof['serviceAvailabilityVerified'])
        self.assertTrue(status_proof['sourceStoppedVerified']);self.assertTrue(status_proof['currentSignedCodeVerified'])
        self.assertEqual(readonly_before,{str(p):p.read_bytes()for p in self.native.root.glob('*.json')})
        malformed=current.replace(b"'serviceAvailabilityVerified':False",b"'serviceAvailabilityVerified':True")
        with self.assertRaises(ValueError):self.native.normalized_collector_cli(malformed,original)
        extra=current.replace(b'view=self.observe(value)',b'unknown_statement=1\n        view=self.observe(value)')
        with self.assertRaises(ValueError):self.native.normalized_collector_cli(extra,original)
        broad_phase=current.replace(b"('VERIFIED','COMPLETE','ROLLED_BACK')",b"('VERIFIED','COMPLETE','ROLLED_BACK','FAILED')")
        with self.assertRaises(ValueError):self.native.normalized_collector_cli(broad_phase,original)
        with patch.dict(os.environ,{'ABLESTACK_STORAGE_WRITER_LOCK_FD':''}):
            with self.assertRaisesRegex(ValueError,'retry key'):self.native.frozen(self.export_request,journal)
        for key,bad in [('names',['foreign']),('nvmeHosts',['foreign']),('authReplayDomains',['ISCSI'])]:
            with self.subTest(field=key),self.assertRaisesRegex(ValueError,'retry key'):
                self.native.frozen({**self.export_request,key:bad},journal)
        source_observation=self.native.stopped_observation
        self.native.stopped_observation=lambda:{**stopped,'configurationSha256':'b'*64}
        with self.assertRaisesRegex(ValueError,'original stopped'):self.native.frozen(self.export_request,journal)
        self.native.stopped_observation=source_observation
        self.native.root.mkdir(mode=0o700,exist_ok=True)
        cipher_path=self.native.path(self.request,'cipher');self.native.io.write(cipher_path,{'untrusted':True})
        with self.assertRaisesRegex(ValueError,'original stopped'):self.native.frozen(self.export_request,journal)
        cipher_path.unlink()
        old_receipt=installs/'runtime-original-stop/state.json';hidden=old_receipt.with_name('hidden-state.json');old_receipt.rename(hidden)
        with self.assertRaisesRegex(ValueError,'installation is absent'):self.native.frozen(self.request,journal)
        hidden.rename(old_receipt)
        old_entry=runtime/'releases/original-stop/ablestack-storagectl';saved_old=old_entry.read_bytes();old_entry.write_bytes(saved_old+b'\n# altered ENTRY\n')
        with self.assertRaisesRegex(ValueError,'complete original signed code'):self.native.frozen(self.request,journal)
        old_entry.write_bytes(saved_old)
        self.assertFalse(path.exists())
        wrong={**self.export_request,'publicKey':self.export_request['publicKey']+'\n'}
        with self.assertRaisesRegex(ValueError,'retry key'):self.native.frozen(wrong,journal)
        self.assertFalse(path.exists())
        self.native.frozen(self.export_request,journal);receipt=self.native.read(path)
        self.assertEqual(journal['cliSha256'],receipt['originalCliSha256'])
        self.assertEqual(stopped,receipt['originalStoppedMetadata'])
        self.assertEqual(hashlib.sha256(original).hexdigest(),journal['cliSha256'])
        before_receipt=path.read_bytes()
        self.native.reader=lambda path,*args:self.read(str(path))
        self.native.write(self.request,'journal',journal)
        exported=self.native.export(self.export_request)
        self.assertTrue(exported['sourceSmbResumed']);self.assertTrue(exported['sourceRuntimeVerified'])
        self.assertEqual(before_receipt,path.read_bytes())
        self.assertEqual(journal['cliSha256'],self.native.read(self.native.path(self.request,'journal'))['cliSha256'])
        self.assertTrue(self.native.status(self.request)['sourceIdentityRestoreSupported'])
        journal['phase']='RESUMED';self.native.frozen(self.request,journal)
        saved=path.read_bytes();path.unlink()
        with self.assertRaisesRegex(ValueError,'original stopped'):self.native.frozen(self.request,journal)
        path.write_bytes(saved);path.chmod(0o600)
        with self.assertRaisesRegex(ValueError,'journal/command'):self.native.frozen({**self.request,'expectedBootId':str(uuid.uuid4())},journal)
        altered={**receipt,'currentCliSha256':'f'*64}
        with self.assertRaises(FileExistsError):self.native.io.write(path,altered)
        generation.atomic_json(path,altered)
        with self.assertRaisesRegex(ValueError,'receipt changed'):self.native.frozen(self.request,journal)
        self.assertFalse(self.signals);self.assertEqual(2,len(self.starts))

    def test_signed_same_version_rollback_receipts_are_validated_but_not_authority(self):
        import subprocess
        runtime=self.root/'receipt-runtime';runtime.mkdir(mode=0o700)
        (runtime/'releases').mkdir(mode=0o700);(runtime/'trusted-keys').mkdir(mode=0o700)
        release=runtime/'releases'/'signed-retry';release.mkdir(mode=0o700)
        entry=release/'ablestack-storagectl';entry.write_bytes(fixture.CLI.read_bytes());entry.chmod(0o755)
        manifest={'bundleVersion':release.name,'keyId':'receipt-fixture-ed25519','files':[{'path':entry.name,
            'sha256':hashlib.sha256(entry.read_bytes()).hexdigest(),'mode':'0755','owner':'root','group':'root'}]}
        generation.atomic_json(release/'manifest.json',manifest)
        installations=self.root/'receipt-installations';installations.mkdir(mode=0o700)
        observer=module.SmbSourceCheckpoint(handler=self.handler,command=self.local_command)
        observer.runtime_root=runtime;observer.installation_root=installations
        private=subprocess.check_output(['openssl','genpkey','-algorithm','ED25519'],stderr=subprocess.DEVNULL)
        fd=os.memfd_create('test-receipt-signing-key',os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
        os.fchmod(fd,0o600);os.write(fd,private);private=None
        fcntl.fcntl(fd,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
        safe=fcntl.fcntl(fd,fcntl.F_DUPFD_CLOEXEC,16);os.close(fd)
        try:
            key_path='/proc/self/fd/'+str(safe)
            public=subprocess.check_output(['openssl','pkey','-in',key_path,'-pubout'],pass_fds=(safe,),stderr=subprocess.DEVNULL)
            (runtime/'trusted-keys'/('receipt-fixture-ed25519.pem')).write_bytes(public)
            states={}
            for name,phase in [('runtime-000-prior','ROLLED_BACK'),('runtime-999-current','COMPLETE')]:
                tx=installations/name;tx.mkdir(mode=0o700);generation.atomic_json(tx/'manifest.json',manifest)
                state={'transactionId':name,'bundleVersion':release.name,'releasePath':str(release),'phase':phase,
                    'keyId':manifest['keyId'],'manifestSha256':hashlib.sha256((tx/'manifest.json').read_bytes()).hexdigest(),
                    'archiveSha256':'a'*64}
                generation.atomic_json(tx/'state.json',state);states[name]=state
                signature=subprocess.check_output(['openssl','pkeyutl','-sign','-inkey',key_path,'-rawin','-in',str(tx/'manifest.json')],
                    pass_fds=(safe,),stderr=subprocess.DEVNULL)
                (tx/'manifest.sig').write_bytes(signature)
            before={str(path):path.read_bytes()for path in runtime.rglob('*')if path.is_file()}
            self.assertEqual('COMPLETE',observer.installed_release_authority(release,manifest)['phase'])
            current=installations/'runtime-999-current/state.json';saved_current=current.read_bytes()
            current.unlink()
            with self.assertRaisesRegex(ValueError,'verified installation is absent'):
                observer.installed_release_authority(release,manifest)
            current.write_bytes(saved_current);current.chmod(0o600)
            prior=installations/'runtime-000-prior'
            for field,bad in [('phase','RECEIVED'),('phase','FAILED'),('archiveSha256','invalid'),('archiveSha256','b'*64),
                    ('keyId','foreign-key'),('releasePath',str(runtime/'releases'/'foreign')),('transactionId','runtime-foreign')]:
                state={**states[prior.name],field:bad};generation.atomic_json(prior/'state.json',state)
                with self.subTest(field=field,value=bad),self.assertRaises(ValueError):observer.installed_release_authority(release,manifest)
                generation.atomic_json(prior/'state.json',states[prior.name])
            for target in (prior/'manifest.json',prior/'manifest.sig'):
                raw=target.read_bytes();target.write_bytes(raw+b' tampered')
                with self.subTest(file=target.name),self.assertRaises(ValueError):observer.installed_release_authority(release,manifest)
                target.write_bytes(raw)
            state_file=prior/'state.json';state_file.chmod(0o666)
            with self.assertRaises(ValueError):observer.installed_release_authority(release,manifest)
            state_file.chmod(0o600)
            # Model only this owned file's foreign UID/GID; Cap0 never chowns a real inode.
            import types
            real_stat=os.stat;owner_before=(state_file.stat().st_uid,state_file.stat().st_gid);foreign_reads=[]
            def foreign_owner(path,*args,**kwargs):
                info=real_stat(path,*args,**kwargs)
                if isinstance(path,(str,bytes,os.PathLike))and os.fsdecode(path)==str(state_file):
                    values={name:getattr(info,name)for name in dir(info)if name.startswith("st_")}
                    values.update(st_uid=os.geteuid()+1,st_gid=os.getegid()+1);foreign_reads.append(str(path))
                    return types.SimpleNamespace(**values)
                return info
            with patch.object(os,"stat",side_effect=foreign_owner):
                with self.assertRaises(ValueError):observer.installed_release_authority(release,manifest)
            self.assertTrue(foreign_reads)
            self.assertEqual(owner_before,(state_file.stat().st_uid,state_file.stat().st_gid))
            self.assertEqual(before,{str(path):path.read_bytes()for path in runtime.rglob('*')if path.is_file()})
            self.assertFalse(self.signals);self.assertFalse(self.starts)
        finally:os.close(safe)

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
        # Same empty identity state with actual SOURCE and fixed vendor file metadata.
        self.verified_unconfigured_source()
        self.native.collector=lambda *args,**kwargs:{**self.empty_payload(),"posixPolicies":{}}
        self.gen.current.unlink()
        self.request["sourceGeneration"]={};self.request["revision"]=1
        generation.atomic_json(self.gen.pending,{**{k:self.request[k]for k in("instanceUuid","operationUuid","revision")},
            "phase":"PREPARED","previous":{},"startedAt":1234,"beforeSha256":self.current["configurationSha256"]})
        self.export_request.update(self.request)
        self.native.observe=module.SmbSourceCheckpoint.observe.__get__(self.native)
        self.native.stopped_observation=lambda:{"databases":self.handler.database_identity(),"units":[],"ownedEndpoints":[],
            "sessionsVerifiedEmpty":True,"configurationSha256":hashlib.sha256(b"PUBLIC FIXTURE CONFIG").hexdigest()}
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


    def same_writer_service(self,empty=False):
        from rendered_generation import RenderedGeneration,DOMAINS,rendered_json
        from service_maintenance import ServiceMaintenance
        from template_maintenance import Maintenance
        if empty:self.verified_unconfigured_source()
        result=self.native.export(self.export_request)
        store=RenderedGeneration(self.root/"service-rendered")
        source_scope={key:self.current[key]for key in("instanceUuid","operationUuid","revision")}
        files={"nfs/manifest.json":'{"endpoints":[]}',"smb/smb.conf":"[global]\nsecurity = user\n",
            "smb/manifest.json":'{"endpoints":[]}',"block/iscsi-plan.json":'{"targets":[]}',
            "block/nvmeof-plan.json":'{"targets":[]}',"desired-state.json":json.dumps(self.desired),
            "network-bindings.json":'{"bindings":[]}',"posix-plan.json":"null",
            "file-volumes.json":'{"schemaVersion":1,"volumes":[]}',"prerequisites.json":'{"directories":{}}'}
        source=store.stage({**source_scope,"expectedCurrentRenderedSha256":None},files,
            lambda path,manifest:{domain:True for domain in DOMAINS})
        store.publish_pointer(store.generations/source_scope["operationUuid"])
        rendered_json(store.journal,{"scope":source_scope,"phase":"COMPLETE","targetSha256":source["manifestSha256"],
            "previousSha256":None,"changedDomains":[],"baselineImported":True})
        environment=patch.dict(os.environ,{"ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(self.root/"service-maintenance"),
            "ABLESTACK_STORAGE_RENDERED_GENERATIONS":str(store.root),
            "ABLESTACK_STORAGE_VOLUME_OPERATIONS":str(self.root/"no-volume-writers")})
        environment.start();self.addCleanup(environment.stop)
        keeper=Maintenance(generation=lambda:self.native.command(("operation","generation","status")))
        plain=self.native.command
        def command(args,payload=None):
            if args==("operation","generation","render-status"):return store.status()
            if args==("operation","maintenance","status"):return keeper.status()
            return plain(args,payload)
        self.native.command=command
        service=ServiceMaintenance(keeper,self.native.cli,command=command,units=lambda:[],
            run=lambda args,**kwargs:"")
        scope={**self.scope,"maintenanceUuid":self.scope["operationUuid"]}
        self.assertEqual("HELD",service.enter(scope)["phase"])
        self.assertEqual(self.current,keeper.read(service.journal)["sourceGeneration"])
        return result,store,service,scope,files

    def test_same_service_readonly_local_status_preserves_checkpoint_and_blocks_capture(self):
        result,store,service,scope,files=self.same_writer_service(empty=True)
        paths=(self.native.path(self.request,"journal"),self.native.path(self.request,"cipher"),service.journal,service.maintenance.marker)
        before={str(path):path.read_bytes()for path in paths}
        self.assertTrue(self.native.status(self.request)["sourceRuntimeVerified"])
        with self.assertRaisesRegex(ValueError,"hold"):self.native.export(self.export_request)
        self.assertEqual(before,{str(path):path.read_bytes()for path in paths})

    def test_same_service_postactivate_status_cannot_authorize_forward_import(self):
        from rendered_generation import DOMAINS
        result,store,service,scope,files=self.same_writer_service()
        files={**files,"smb/smb.conf":"[global]\nsecurity = user\n# owned target\n"}
        target=store.stage({**self.scope,"expectedCurrentRenderedSha256":store.status()["current"]["manifestSha256"]},
            files,lambda path,manifest:{domain:True for domain in DOMAINS})
        store.activate(self.scope,lambda *args:None,lambda path:{domain:True for domain in DOMAINS})
        paths=(self.native.path(self.request,"journal"),self.native.path(self.request,"cipher"),service.journal)
        before={str(path):path.read_bytes()for path in paths}
        self.assertTrue(self.native.status(self.request)["sourceIdentityRestoreSupported"])
        request={**self.imported_request(result),"renderedManifestSha256":target["manifestSha256"]}
        effects=(len(self.signals),len(self.starts))
        with self.assertRaisesRegex(ValueError,"inverse pointer"):self.native.import_source(request)
        self.assertEqual(effects,(len(self.signals),len(self.starts)))
        self.assertEqual(before,{str(path):path.read_bytes()for path in paths})

    def test_same_service_inverse_uses_original_capsule_and_real_rollback_pointer(self):
        from rendered_generation import DOMAINS
        result,store,service,scope,files=self.same_writer_service()
        files={**files,"smb/smb.conf":"[global]\nsecurity = user\n# owned target\n"}
        target=store.stage({**self.scope,"expectedCurrentRenderedSha256":store.status()["current"]["manifestSha256"]},
            files,lambda path,manifest:{domain:True for domain in DOMAINS})
        store.activate(self.scope,lambda *args:None,lambda path:{domain:True for domain in DOMAINS})
        request={**self.imported_request(result),"renderedManifestSha256":target["manifestSha256"]}
        results=[]
        def inverse(path,domain,rollback):
            if domain=="SMB":results.append(self.native.import_source(request))
        store.rollback(self.scope,inverse,lambda path:{domain:True for domain in DOMAINS})
        self.assertEqual(1,len(results));self.assertTrue(results[0]["sourceIdentityRestored"])
        self.assertTrue(service.status(scope)["bootHeld"])
        self.assertTrue(self.native.status(self.request)["sourceRuntimeVerified"])
        self.assertEqual("ROLLED_BACK",store.status()["activation"]["phase"])

    def test_same_service_foreign_source_scope_or_unprotected_journal_is_rejected(self):
        result,store,service,scope,files=self.same_writer_service()
        original=service.maintenance.read(service.journal)
        for key,bad in (("sourceGeneration",{**self.current,"revision":0}),
                ("sourceRendered",{**original["sourceRendered"],"manifestSha256":"f"*64}),
                ("scope",{**scope,"operationUuid":str(uuid.uuid4())})):
            value=copy.deepcopy(original);value[key]=bad;service.maintenance.write(service.journal,value)
            with self.subTest(key=key),self.assertRaises(ValueError):self.native.status(self.request)
        service.maintenance.write(service.journal,original)
        service.journal.chmod(0o644)
        with self.assertRaisesRegex(ValueError,"protected"):self.native.status(self.request)

    def test_same_service_root_or_foreign_hold_is_never_local_authority(self):
        result,store,service,scope,files=self.same_writer_service()
        keeper=service.maintenance
        for marker in ({"kind":"SERVICE","scope":{**scope,"maintenanceUuid":str(uuid.uuid4())},"bootHeld":True},
                {"kind":"ROOT","scope":{**self.scope,"templateUpgradeUuid":str(uuid.uuid4())},"bootHeld":True}):
            keeper.write(keeper.marker,marker)
            with self.subTest(kind=marker["kind"]),self.assertRaises(ValueError):self.native.status(self.request)


    def rendered_private_target(self):
        from rendered_generation import DOMAINS
        result,store,service,service_scope,files=self.same_writer_service(empty=True)
        user="issue909user";principal=str(uuid.uuid4());share=str(uuid.uuid4())
        listener={"listenIp":"192.0.2.75","port":445}
        unit="ablestack-storage-smb@"+hashlib.sha256(b"192.0.2.75:445").hexdigest()[:24]+".service"
        target_desired=copy.deepcopy(self.desired)
        target_desired["desired-state/smb-share-apply.json"]={"instanceUuid":self.scope["instanceUuid"],"enabled":True,
            "listeners":[listener],"shares":[{"uuid":share,"config":{"posixOwnershipMode":"AUTHENTICATED_USER"},
                "acls":[{"uuid":principal,"principal":user,"config":{"localAccount":True}}]}]}
        files={**files,"desired-state.json":json.dumps(target_desired),"smb/smb.conf":"[global]\nsecurity = user\n# TARGET\n",
            "smb/manifest.json":json.dumps({"listeners":[listener],"shares":[]})}
        target=store.stage({**self.scope,"expectedCurrentRenderedSha256":store.status()["current"]["manifestSha256"]},
            files,lambda path,manifest:{domain:True for domain in DOMAINS})
        request={**self.scope,"renderedManifestSha256":target["manifestSha256"],"checkpointPrivateKey":self.pem(self.newkey),
            "localSourceCheckpointReference":result["localSourceCheckpointReference"]}
        mapped={name:self.root/("private-target-"+str(index))for index,name in enumerate((
            "/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow","/etc/ablestack-storage/smb-managed-identities.json",
            "/etc/ablestack-storage/smb-local-account-provenance.json","/etc/ablestack-storage/smb-semantic-identity-aliases.json",
            *module.IDENTITY_DATABASES.values()))}
        for label,name in module.IDENTITY_DATABASES.items():mapped[name]=self.private[label]
        config=self.root/"private-target-smb.conf";mapped["/etc/samba/smb.conf"]=config
        raw={"/etc/passwd":user+":x:10000:10000::/nonexistent:/usr/sbin/nologin\n","/etc/group":user+":x:10000:\n",
            "/etc/shadow":user+":!:0:0:99999:7:::\n","/etc/gshadow":user+":!::\n",
            "/etc/ablestack-storage/smb-managed-identities.json":"{}",
            "/etc/ablestack-storage/smb-local-account-provenance.json":json.dumps({"schemaVersion":1,"accounts":{
                "/etc/passwd":[user+":x:10000:10000::/nonexistent:/usr/sbin/nologin"],"/etc/group":[user+":x:10000:"]}})}
        original_exists=os.path.lexists
        guard=patch.object(module.os.path,"lexists",side_effect=lambda path:
            original_exists(mapped[str(path)])if str(path)in mapped else original_exists(path))
        guard.start();self.addCleanup(guard.stop)
        self.native.reader=lambda path,*args:capsule.regular_file(mapped[str(path)]if str(path)in mapped else path,*args)
        self.handler.run=lambda args: user+":10000:Fixture\n"if args==["pdbedit","-L"]else"SID for domain FIXTURE is: S-1-5-21-11-22-33\n"
        self.active_units=set()
        def databases():
            values={}
            for label,path in module.IDENTITY_DATABASES.items():
                actual=mapped[path]
                values[label]={"path":path,"present":False}if not actual.exists()else{
                    "path":path,"present":True,"device":actual.stat().st_dev,"inode":actual.stat().st_ino,"uid":0,"gid":0,"mode":"0600"}
            return values
        self.handler.database_identity=databases
        def observation(value,**kwargs):
            return {"databases":databases(),"units":[{"unit":unit,"pid":1234}]if self.active_units else[],
                "ownedEndpoints":[{**listener,"tcpReady":True,"pid":1234}]if self.active_units else[],
                "sessionsVerifiedEmpty":True,"configurationSha256":hashlib.sha256(config.read_bytes()).hexdigest()}
        self.native.observe=observation
        def stopped():
            if self.active_units:raise ValueError("fixture SMB still active")
            return observation({})
        self.native.stopped_observation=stopped
        owner={"unit":unit,"pid":1234,"startTicks":"1234","configuration":"/etc/samba/smb.conf"}
        service.units=lambda:[owner]if self.active_units else[]
        self.stop_calls=[]
        def run(args,**kwargs):
            if args[:2]==["systemctl","stop"]:
                self.stop_calls.append(args[2]);self.active_units.clear()
            return ""
        service.run=run
        plain=self.native.command
        self.native.command=lambda args,payload=None:service.quiesce(payload)if args==("operation","quiesce")else plain(args,payload)
        def replay(path,domain,rollback):
            if domain!="SMB":return
            self.gen.replace_desired(target_desired);config.write_text(files["smb/smb.conf"]);config.chmod(0o600)
            for name,text in raw.items():mapped[name].write_text(text);mapped[name].chmod(0o600)
            for name in module.IDENTITY_DATABASES.values():mapped[name].write_bytes(b"SYNTHETIC TARGET SAM");mapped[name].chmod(0o600)
            self.active_units.add(unit);self.native.capture_rendered_target_private(request)
        store.activate(self.scope,replay,lambda path:{domain:True for domain in DOMAINS})
        return result,store,service,request,mapped,config

    def test_rendered_private_receipt_owns_only_completed_target_and_actual_stop(self):
        result,store,service,request,mapped,config=self.rendered_private_target()
        before=self.native.read(self.native.path(self.request,"cipher"))
        original=self.native.read(self.native.path(self.request,"journal"))["prior"]
        class StopObserved(Exception):pass
        def inverse(path,domain,rollback):
            if domain=="SMB":
                self.assertTrue(self.native.prepare_rendered_target_private_restore(request)["targetPrivateStopped"])
                self.assertFalse(self.active_units)
                self.assertTrue(all(path.exists()for path in self.private.values()))
                raise StopObserved()
        with self.assertRaises(StopObserved):store.rollback(self.scope,inverse,lambda path:{})
        self.assertEqual(before,self.native.read(self.native.path(self.request,"cipher")))
        self.assertEqual(original,self.native.read(self.native.path(self.request,"journal"))["prior"])
        self.assertTrue(service.status()["bootHeld"])
        self.assertEqual(1,len(self.stop_calls))

    def test_rendered_private_receipt_file_tamper_or_foreign_principal_never_stops(self):
        result,store,service,request,mapped,config=self.rendered_private_target()
        path=self.native.path(self.request,"rendered-target-private");saved=path.read_bytes()
        record=json.loads(saved);record["bootId"]=str(uuid.uuid4());path.write_text(json.dumps(record))
        def inverse(*args):self.native.prepare_rendered_target_private_restore(request)
        with self.assertRaises(ValueError):store.rollback(self.scope,inverse,lambda path:{})
        path.write_bytes(saved)
        self.private["PASSDB"].write_bytes(b"CHANGED OR FOREIGN SAM")
        with self.assertRaises(ValueError):store.rollback(self.scope,inverse,lambda path:{})
        self.assertFalse(self.stop_calls);self.assertTrue(self.active_units)
        self.assertTrue(service.status()["bootHeld"])

    def test_rendered_private_unknown_stop_keeps_target_sam_and_source_checkpoint(self):
        result,store,service,request,mapped,config=self.rendered_private_target()
        original=self.native.read(self.native.path(self.request,"cipher"))
        def unknown(args,**kwargs):
            if args[:2]==["systemctl","stop"]:raise TimeoutError("fixture unknown service stop")
            return ""
        service.run=unknown
        with self.assertRaises(TimeoutError):
            store.rollback(self.scope,lambda *args:self.native.prepare_rendered_target_private_restore(request),lambda path:{})
        self.assertTrue(all(path.exists()for path in self.private.values()))
        self.assertIsNone(self.native.read(self.native.path(self.request,"rendered-target-private-stop")))
        self.assertEqual(original,self.native.read(self.native.path(self.request,"cipher")))
        self.assertTrue(service.status()["bootHeld"])

    def test_rendered_private_missing_receipt_or_foreign_capsule_never_stops(self):
        result,store,service,request,mapped,config=self.rendered_private_target()
        path=self.native.path(self.request,"rendered-target-private");saved=path.read_bytes();path.unlink()
        with self.assertRaises(ValueError):
            store.rollback(self.scope,lambda *args:self.native.prepare_rendered_target_private_restore(request),lambda path:{})
        path.write_bytes(saved);path.chmod(0o600)
        foreign={**request,"checkpointPrivateKey":self.pem(fixture.rsa.generate_private_key(public_exponent=65537,key_size=2048))}
        with self.assertRaises(ValueError):
            store.rollback(self.scope,lambda *args:self.native.prepare_rendered_target_private_restore(foreign),lambda path:{})
        self.assertFalse(self.stop_calls);self.assertTrue(all(path.exists()for path in self.private.values()))
        self.assertTrue(service.status()["bootHeld"])

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

    def absent_nvme_target_replay(self):
        self.export_request["authReplayDomains"]=["NVMEOF"]
        captured=self.native.export(self.export_request)
        self.native.import_source(self.imported_request(captured))
        request={**self.request,"capsule":captured["capsule"],"credentialPrivateKey":self.pem(self.newkey),
            "localSourceCheckpointReference":captured["localSourceCheckpointReference"],"restoreDomains":["NVMEOF"],"nvmeDesired":None}
        runtime=self.root/"absent-source-nvmet";(runtime/"subsystems").mkdir(parents=True);(runtime/"ports").mkdir()
        target=runtime/"subsystems"/"nqn.2026-10.test:new-target";target.mkdir()
        scope={key:self.request[key]for key in("instanceUuid","operationUuid","revision")}
        empty={"subsystems":[],"namespaces":[],"hosts":[]}
        receipt={"schemaVersion":1,"kind":"NVMEOF_SOURCE_RUNTIME_IDENTITY","scope":scope,"bootId":self.boot,
            "sourceGeneration":self.current,"sourceConfigurationSha256":self.request["sourceConfigurationSha256"],
            "sourceBindings":empty,"targetBindings":{**empty,"subsystems":[{"uuid":str(uuid.uuid4()),"nqn":target.name}]},"identities":[]}
        receipt["checkpointSha256"]=hashlib.sha256(json.dumps(receipt,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()
        pending=generation.read_json(self.gen.pending);generation.atomic_json(self.gen.pending,{**pending,"nvmeRuntimeIdentity":receipt})
        patched=patch.object(module,"LOCAL_SOURCE_NVME_ROOT",runtime,create=True);patched.start();self.addCleanup(patched.stop)
        actual_path=module.Path
        def mapped_path(*args,**kwargs):
            if args and str(args[0]) in ("/sys/kernel/config/nvmet/subsystems","/sys/kernel/config/nvmet/ports"):
                return runtime/actual_path(args[0]).name
            return actual_path(*args,**kwargs)
        path_patch=patch.object(module,"Path",side_effect=mapped_path);path_patch.start();self.addCleanup(path_patch.stop)
        calls=[];plain=self.native.command
        def command(args,payload=None):
            if args==("nvmeof","subsystem","apply","/dev/stdin"):
                self.assertEqual({"instanceUuid":self.scope["instanceUuid"],"enabled":False,"subsystems":[]},payload)
                calls.append("empty-apply");target.rmdir()
                generation.atomic_json(self.gen.config/"nvmeof-subsystems.json",payload)
                return {"success":True}
            if args==("operation","generation","restore","/dev/stdin"):
                calls.append("source-restore")
                return self.gen.execute("restore",payload)
            return plain(args,payload)
        self.native.command=command
        return request,runtime,target,calls

    def test_first_nvme_target_failure_restores_absent_source_without_auth_erasure(self):
        request,runtime,target,calls=self.absent_nvme_target_replay()
        with patch.object(module,"restore_protected",return_value={"success":True})as protected:
            result=self.native.replay_auth(request)
        self.assertTrue(result["nvmeRestored"]);self.assertTrue(result["sourceAuthReplayed"])
        self.assertFalse(result["canonicalDesiredStateChanged"])
        self.assertEqual(["empty-apply","source-restore"],calls)
        self.assertFalse(target.exists());self.assertFalse((self.gen.config/"nvmeof-subsystems.json").exists())
        self.assertEqual(self.current["configurationSha256"],self.gen.digest())
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})
        protected.assert_called_once()

    def test_absent_nvme_source_without_target_receipt_keeps_original_rejection(self):
        request,runtime,target,calls=self.absent_nvme_target_replay()
        pending=generation.read_json(self.gen.pending);pending.pop("nvmeRuntimeIdentity")
        generation.atomic_json(self.gen.pending,pending)
        with patch.object(module,"restore_protected")as protected,self.assertRaises(ValueError):
            self.native.replay_auth(request)
        self.assertEqual([],calls);self.assertTrue(target.exists());protected.assert_not_called()

    def test_absent_nvme_source_rejects_foreign_receipt_scope_boot_hash_and_source(self):
        request,runtime,target,calls=self.absent_nvme_target_replay()
        original=generation.read_json(self.gen.pending)
        for change in ({"scope":{**self.scope,"operationUuid":str(uuid.uuid4())}},{"bootId":str(uuid.uuid4())},
                       {"sourceConfigurationSha256":"0"*64},{"sourceBindings":{"subsystems":[{"uuid":str(uuid.uuid4())}],"namespaces":[],"hosts":[]}},
                       {"checkpointSha256":"0"*64}):
            pending=copy.deepcopy(original);pending["nvmeRuntimeIdentity"].update(change)
            generation.atomic_json(self.gen.pending,pending)
            with self.subTest(change=change),patch.object(module,"restore_protected")as protected,self.assertRaises(ValueError):
                self.native.replay_auth(request)
            self.assertEqual([],calls);self.assertTrue(target.exists());protected.assert_not_called()

    def test_absent_nvme_new_private_vault_blocks_before_any_cleanup(self):
        request,runtime,target,calls=self.absent_nvme_target_replay()
        exists=os.path.lexists
        def present(path):
            return True if str(path)=="/etc/ablestack-storage/secrets/nvmeof-acl-secrets.json"else exists(path)
        with patch.object(module.os.path,"lexists",side_effect=present),patch.object(module,"restore_protected")as protected,self.assertRaises(ValueError):
            self.native.replay_auth(request)
        self.assertEqual([],calls);self.assertTrue(target.exists());protected.assert_not_called()

    def test_absent_nvme_callback_failure_or_foreign_runtime_is_not_success(self):
        request,runtime,target,calls=self.absent_nvme_target_replay()
        plain=self.native.command
        def refuse(args,payload=None):
            if args==("nvmeof","subsystem","apply","/dev/stdin"):
                calls.append("rejected-validation");raise ValueError("fixture exact TARGET ownership rejected")
            return plain(args,payload)
        self.native.command=refuse
        with patch.object(module,"restore_protected")as protected,self.assertRaises(ValueError):
            self.native.replay_auth(request)
        self.assertEqual(["rejected-validation"],calls);self.assertTrue(target.exists());protected.assert_not_called()
        calls.clear();self.native.command=plain
        (runtime/"subsystems"/"nqn.2026-10.test:foreign").mkdir()
        with patch.object(module,"restore_protected")as protected,self.assertRaises(ValueError):
            self.native.replay_auth(request)
        self.assertEqual(["empty-apply","source-restore"],calls);protected.assert_not_called()

    def test_verified_source_export_cannot_mint_a_new_opaque_identity_checkpoint(self):
        self.gen.execute("verify",self.scope)
        with self.assertRaises(ValueError):self.native.export(self.export_request)
        self.assertEqual([],self.signals);self.assertEqual(0,self.counter)
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})

    def test_verified_original_import_resumes_through_real_local_start_consumer(self):
        from pending_nfs_authorization import PendingNfsAuthorization as RealAuthorization
        self.boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.request["expectedBootId"]=self.boot;self.export_request.update(self.request)
        self.native.root=self.gen.root.parent/"smb-local-source-checkpoints"
        captured=self.native.export(self.export_request)
        conf=self.root/"verified-resume-samba";conf.mkdir(mode=0o700)
        (conf/"smb.conf").write_bytes(b"PUBLIC FIXTURE CONFIG")
        grants=[];started=[];plain=self.handler.run
        def factory(*args,**kwargs):
            kwargs.update(root=self.root/"verified-source-grant",configuration_root=conf)
            auth=RealAuthorization(*args,**kwargs);grants.append(auth);return auth
        def run(args):
            if args[:2]==["systemctl","start"]:
                auth=grants[-1]
                self.assertTrue(auth.authorized(args[2],{"bootHeld":False},{"current":None,"activation":None}))
                started.append(args[2])
            return plain(args)
        self.handler.run=run;self.native.start_grant=self.native.pending_start_grant
        self.gen.execute("verify",self.scope)
        with patch.object(module,"PendingNfsGeneration",return_value=self.gen),patch.object(module,"PendingNfsAuthorization",side_effect=factory):
            result=self.native.import_source(self.imported_request(captured))
        self.assertTrue(result["sourceSmbResumed"]);self.assertTrue(result["sourceIdentityRestored"])
        self.assertTrue(started);self.assertEqual(1,self.counter)
        self.assertEqual("VERIFIED",generation.read_json(self.gen.pending)["phase"])
        self.assertTrue(all(not auth.path.exists()for auth in grants))

    def test_committed_target_never_consumes_local_original_import(self):
        captured=self.native.export(self.export_request)
        self.gen.execute("verify",self.scope);self.gen.execute("commit",self.scope)
        effects=(len(self.signals),len(self.starts))
        with self.assertRaises(ValueError):self.native.import_source(self.imported_request(captured))
        self.assertEqual(effects,(len(self.signals),len(self.starts)))

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


    def assert_closed_failure(self,stage,exception):
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertEqual("RECOVERY_REQUIRED",journal["phase"])
        self.assertEqual({"failureStage":stage,"exceptionClass":exception},journal["lastFailure"])
        self.assertEqual(0o600,self.native.path(self.request,"journal").stat().st_mode&0o777)
        self.assertNotIn("PRIVATE_SENTINEL",json.dumps(journal["lastFailure"]))
        self.assertEqual(self.raw_before,{name:path.read_bytes()for name,path in self.private.items()})
        status=self.native.status(self.request)
        self.assertNotIn("lastFailure",status);self.assertNotIn("exceptionClass",status);self.assertNotIn("failureStage",status)

    def test_resume_source_authority_failure_records_only_closed_stage_and_class(self):
        plain=self.native.state;calls=[]
        def state(*args,**kwargs):
            calls.append(True)
            if len(calls)==2:raise ValueError("PRIVATE_SENTINEL/source authority")
            return plain(*args,**kwargs)
        self.native.state=state
        with self.assertRaises(ValueError):self.native.export(self.export_request)
        self.native.state=plain
        self.assert_closed_failure("RESUME_SOURCE_AUTHORITY","ValueError")
        self.assertFalse(self.starts)

    def test_resume_stopped_observation_failure_has_precise_private_free_diagnostic(self):
        plain=self.native.observe
        def observe(*args,**kwargs):
            journal=self.native.read(self.native.path(self.request,"journal"))
            if kwargs.get("stopped") and journal and journal["phase"]=="EXPORTED":
                raise PermissionError("PRIVATE_SENTINEL/stopped")
            return plain(*args,**kwargs)
        self.native.observe=observe
        with self.assertRaises(PermissionError):self.native.export(self.export_request)
        self.native.observe=plain
        self.assert_closed_failure("RESUME_STOPPED_OBSERVE","PermissionError")
        self.assertFalse(self.starts)

    def test_resume_start_grant_failure_records_timeout_class_without_args(self):
        import subprocess
        @contextlib.contextmanager
        def denied(*args):
            raise subprocess.TimeoutExpired(["PRIVATE_SENTINEL/command"],10,output=b"PRIVATE_SENTINEL/cipher")
            yield
        self.native.start_grant=denied
        with self.assertRaises(subprocess.TimeoutExpired):self.native.export(self.export_request)
        self.assert_closed_failure("RESUME_START_GRANT","TimeoutExpired")
        self.assertFalse(self.starts)

    def test_resume_start_unit_failure_records_stage_and_stop_iteration_class(self):
        plain=self.handler.run
        def run(args):
            if args[:2]==["systemctl","start"]:raise StopIteration("PRIVATE_SENTINEL/start")
            return plain(args)
        self.handler.run=run
        with self.assertRaises(StopIteration):self.native.export(self.export_request)
        self.handler.run=plain
        self.assert_closed_failure("RESUME_START_UNIT","StopIteration")

    def test_resume_verify_observation_failure_and_retry_keep_original_closed_failure(self):
        plain=self.native.observe
        def observe(*args,**kwargs):
            journal=self.native.read(self.native.path(self.request,"journal"))
            if journal and journal["phase"]=="RESUMING":raise ProcessLookupError("PRIVATE_SENTINEL/proc")
            return plain(*args,**kwargs)
        self.native.observe=observe
        with self.assertRaises(ProcessLookupError):self.native.export(self.export_request)
        self.native.observe=plain
        self.assert_closed_failure("RESUME_VERIFY_OBSERVE","ProcessLookupError")
        before=self.native.path(self.request,"cipher").read_bytes()
        self.assertTrue(self.native.export(self.export_request)["sourceRuntimeVerified"])
        journal=self.native.read(self.native.path(self.request,"journal"))
        self.assertEqual("RESUMED",journal["phase"])
        self.assertEqual("ProcessLookupError",journal["lastFailure"]["exceptionClass"])
        self.assertEqual(before,self.native.path(self.request,"cipher").read_bytes())

    def test_import_identity_failure_diagnostic_is_closed_and_does_not_expose_custom_class(self):
        result=self.native.export(self.export_request)
        class PRIVATE_SENTINEL(Exception):pass
        self.native.importer=lambda value:(_ for _ in ()).throw(PRIVATE_SENTINEL("PRIVATE_SENTINEL/secret"))
        with self.assertRaises(PRIVATE_SENTINEL):self.native.import_source(self.imported_request(result))
        self.assert_closed_failure("IMPORT_IDENTITY_RESTORE","OtherException")


class StorageSmbSourceProcExitTest(unittest.TestCase):
    def setUp(self):
        import tempfile,types
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.proc=self.root/"proc";self.proc.mkdir()
        self.process=self.proc/"4321";self.process.mkdir();(self.process/"fd").mkdir()
        (self.process/"comm").write_text("worker\n")
        (self.process/"cgroup").write_text("0::/system.slice/nmbd.service\n")
        self.fd=self.process/"fd/5";self.fd.symlink_to(module.IDENTITY_DATABASES["SECRETS"])
        self.owner=777;self.owner_rows=[{"unit":"nmbd.service","pid":self.owner}]
        self.handler=types.SimpleNamespace(process_root=self.proc,remaining=lambda *args:10,
            configuration=self.root/"configuration",identity_holders=lambda:[],
            database_identity=lambda:{"SECRETS":{"path":module.IDENTITY_DATABASES["SECRETS"],"device":1,"inode":2}},
            endpoints=lambda:[],run=lambda args:"")
        self.view={"sessions":{"safeToRebind":True},"identityDatabaseAligned":True,"masters":[],
            "databases":self.handler.database_identity(),"ownedEndpoints":[],"configurationSha256":"public"}
        self.handler.inspect=lambda *args,**kwargs:copy.deepcopy(self.view)
        self.native=module.SmbSourceCheckpoint.__new__(module.SmbSourceCheckpoint)
        self.native.handler=self.handler;self.native.unit_authority=types.SimpleNamespace(units=lambda **kwargs:copy.deepcopy(self.owner_rows))
        self.native.reader=lambda *args:(b"public",None)
        self.handler.socket_rows=lambda raw:[]
        self.value={"instanceUuid":"i","operationUuid":"o","revision":1,"sourceGeneration":{"revision":1}}

    def observe(self):
        return module.SmbSourceCheckpoint.observe(self.native,self.value)

    @contextlib.contextmanager
    def fault(self,where,error):
        import types
        plain_read=Path.read_text;plain_iter=Path.iterdir;plain_stat=Path.stat;plain_link=os.readlink
        selected={"comm":self.process/"comm","cgroup":self.process/"cgroup","fd":self.process/"fd",
                  "readlink":self.fd,"stat":self.fd}[where]
        def read(path,*args,**kwargs):
            if where in ("comm","cgroup") and path==selected:raise error
            return plain_read(path,*args,**kwargs)
        def iterate(path,*args,**kwargs):
            if where=="fd" and path==selected:raise error
            return plain_iter(path,*args,**kwargs)
        def link(path,*args,**kwargs):
            if where=="readlink" and Path(path)==selected:raise error
            return plain_link(path,*args,**kwargs)
        def info(path,*args,**kwargs):
            if path==self.fd:
                if where=="stat":raise error
                return types.SimpleNamespace(st_dev=1,st_ino=2)
            return plain_stat(path,*args,**kwargs)
        with patch.object(Path,"read_text",read),patch.object(Path,"iterdir",iterate),patch.object(Path,"stat",info),patch.object(os,"readlink",link):
            yield

    def test_unrelated_exiting_process_esrch_does_not_break_source_observation(self):
        import errno
        for where in ("comm","cgroup","fd","readlink","stat"):
            with self.subTest(where=where),self.fault(where,ProcessLookupError(errno.ESRCH,"private message must not escape")):
                self.assertEqual("public",self.observe()["configurationSha256"])

    def test_owned_master_exit_is_not_hidden_by_general_scan(self):
        import errno
        self.owner_rows[0]["pid"]=int(self.process.name)
        for where in ("comm","cgroup","fd","readlink","stat"):
            with self.subTest(where=where),self.fault(where,ProcessLookupError(errno.ESRCH,"owned master exited")):
                with self.assertRaises(ProcessLookupError):self.observe()

    def test_general_scan_does_not_hide_permission_or_io_errors(self):
        import errno
        for code in (errno.EPERM,errno.EACCES,errno.EIO):
            for where in ("comm","cgroup","fd","readlink","stat"):
                with self.subTest(code=code,where=where),self.fault(where,OSError(code,"secret")):
                    with self.assertRaises(OSError):self.observe()

    def test_live_foreign_process_and_private_fd_are_still_rejected(self):
        with self.fault("comm",ValueError("unexpected fault")):
            with self.assertRaises(ValueError):self.observe()
        (self.process/"comm").write_text("smbd\n");(self.process/"cgroup").write_text("0::/foreign.service\n")
        with self.assertRaisesRegex(ValueError,"unowned identity process"):self.observe()
        (self.process/"comm").write_text("worker\n")
        with self.fault("comm",FileNotFoundError()):
            self.assertEqual("public",self.observe()["configurationSha256"])
        # A live non-Samba process holding private credentials is also foreign.
        import types
        plain=Path.stat
        def info(path,*args,**kwargs):
            return types.SimpleNamespace(st_dev=1,st_ino=2) if path==self.fd else plain(path,*args,**kwargs)
        with patch.object(Path,"stat",info),self.assertRaisesRegex(ValueError,"private FD"):
            self.observe()

    def test_stopped_global_scan_ignores_only_dead_process_esrch(self):
        import errno
        self.owner_rows.clear()
        for where in ("comm","fd","readlink"):
            with self.subTest(where=where),self.fault(where,ProcessLookupError(errno.ESRCH,"gone")):
                self.assertTrue(module.SmbSourceCheckpoint.stopped_observation(self.native)["sessionsVerifiedEmpty"])
        for code in (errno.EPERM,errno.EIO):
            with self.subTest(code=code),self.fault("comm",OSError(code,"protected")):
                with self.assertRaises(OSError):module.SmbSourceCheckpoint.stopped_observation(self.native)


if __name__=="__main__":unittest.main()
