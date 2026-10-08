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
from pathlib import Path
import copy,json,os,sys,tempfile,unittest,uuid
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from rendered_generation import RenderedGeneration,DESIRED_PATHS,DOMAINS,REQUIRED,rendered_json
from root_source_recovery import RootSourceRecovery

class StorageRootSourceRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.config=self.root/"config";self.config.mkdir(mode=0o700)
        self.env=patch.dict(os.environ,{"ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.config)});self.env.start();self.addCleanup(self.env.stop)
        self.request={"instanceUuid":str(uuid.uuid4()),"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":6}
        desired={key:None for key in DESIRED_PATHS};desired["sharedfs-network.json"]={"mode":"DHCP"}
        (self.config/"sharedfs-network.json").write_text('{ "mode" : "DHCP" }\n');(self.config/"sharedfs-network.json").chmod(0o600)
        store=RenderedGeneration(self.root/"render");scope={**self.request,"operationUuid":str(uuid.uuid4()),"revision":5,"expectedCurrentRenderedSha256":None}
        files={name:"{}" for name in REQUIRED};files["desired-state.json"]=json.dumps(desired)
        manifest=store.stage(scope,files,lambda *args:{key:True for key in DOMAINS});store.publish_pointer(store.generations/manifest["scope"]["operationUuid"])
        self.generation={**manifest["scope"],"configurationSha256":manifest["configurationSha256"]}
        self.actual={"generation":self.generation,"configurationSha256":manifest["configurationSha256"],"generationStatus":"IN_SYNC","pendingOperationUuid":None,"configurationDesiredState":desired}
        self.marker={"bootHeld":True,"maintenanceKind":"ROOT","scope":dict(self.request)};self.events=[];self.fail=None
        outer=self
        class Runtime:
            credentials={}
            def command(self,args):outer.events.append(("observe",args));return outer.marker
            def replay(self,path,domain):
                outer.assertTrue(self.root_source_replay);outer.events.append(("replay",domain))
                if outer.fail==domain:raise RuntimeError("injected source domain failure")
        runtime=Runtime()
        class Driver:
            def generation(self):return outer.actual
            def verify(self,path):outer.events.append(("verify",None));return {key:True for key in DOMAINS}
            def authorize_units(self,request,source_only):
                outer.assertEqual("ROOT",source_only);outer.events.append(("authorize",None));proof=outer.root/"writer.json";rendered_json(proof,{"scope":outer.request});return proof
        driver=Driver();driver.store=store;driver.runtime=runtime
        self.driver=driver;self.recovery=RootSourceRecovery(driver,self.root/"maintenance")
        self.request["verifiedGeneration"]=copy.deepcopy(self.generation)
    def test_source_capture_requires_fresh_imported_baseline_and_pins_original_canonical_bytes(self):
        result=self.recovery.capture(self.request);self.assertTrue(result["sourceCaptured"])
        saved=json.loads(self.recovery.path(self.marker["scope"]).read_text())
        self.assertEqual(self.generation,saved["sourceGeneration"]);self.assertEqual(7,len(saved["canonicalBytes"]))
        self.assertTrue(saved["canonicalBytes"]["sharedfs-network.json"]["present"])
        self.assertIn(("verify",None),self.events);self.assertFalse(any(event=="replay" for event,value in self.events))
    def test_missing_baseline_foreign_marker_or_native_pending_refuses_before_checkpoint_or_effects(self):
        for change in ("pending","marker","pointer"):
            actual=copy.deepcopy(self.actual);marker=copy.deepcopy(self.marker);pointer=self.driver.store.pointer()
            if change=="pending":self.actual["pendingOperationUuid"]=str(uuid.uuid4())
            elif change=="marker":self.marker["scope"]["operationUuid"]=str(uuid.uuid4())
            else:self.driver.store.current.unlink()
            with self.assertRaises(ValueError):self.recovery.capture(self.request)
            self.assertFalse(self.recovery.path(self.request).exists());self.assertFalse(any(event in ("authorize","replay") for event,value in self.events))
            self.actual=actual;self.marker=marker
            if change=="pointer":self.driver.store.publish_pointer(pointer)
    def test_resume_replays_all_four_under_root_marker_and_preserves_native_generation_and_all_seven_bytes(self):
        self.recovery.capture(self.request);before=self.recovery.canonical_bytes();generation=copy.deepcopy(self.actual);self.events.clear()
        result=self.recovery.resume(self.request)
        self.assertEqual(list(DOMAINS),[value for event,value in self.events if event=="replay"])
        self.assertTrue(result["canonicalBytesUnchangedVerified"]);self.assertFalse(result["canonicalDesiredStateChanged"]);self.assertFalse(result["nativeGenerationChanged"])
        self.assertEqual(before,self.recovery.canonical_bytes());self.assertEqual(generation,self.actual)
        self.assertEqual("VERIFIED",json.loads(self.recovery.path(self.request).read_text())["phase"]);self.assertTrue(self.marker["bootHeld"])
        self.assertFalse((self.root/"writer.json").exists());self.assertEqual({},self.driver.runtime.credentials)
    def test_caller_generation_and_raw_byte_drift_block_before_authorization(self):
        self.recovery.capture(self.request);self.events.clear()
        wrong={**self.request,"verifiedGeneration":{**self.generation,"revision":4}}
        with self.assertRaises(ValueError):self.recovery.resume(wrong)
        (self.config/"sharedfs-network.json").write_text('{"mode":"DHCP"}')
        with self.assertRaises(ValueError):self.recovery.resume(self.request)
        self.assertFalse(any(event in ("authorize","replay") for event,value in self.events))
    def test_runtime_failure_retains_recovery_required_and_never_releases_root_hold(self):
        self.recovery.capture(self.request);self.events.clear();self.fail="SMB"
        with self.assertRaises(RuntimeError):self.recovery.resume(self.request)
        self.assertEqual(["NFS","SMB"],[value for event,value in self.events if event=="replay"])
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.recovery.path(self.request).read_text())["phase"])
        self.assertTrue(self.marker["bootHeld"]);self.assertFalse((self.root/"writer.json").exists());self.assertEqual({},self.driver.runtime.credentials)
    def test_any_canonical_write_during_first_replay_is_detected_before_next_domain(self):
        self.recovery.capture(self.request);self.events.clear();runtime=self.driver.runtime;old=runtime.replay
        def injected(path,domain):
            old(path,domain);(self.config/"sharedfs-network.json").write_text('{"mode":"DHCP"}')
        runtime.replay=injected
        with self.assertRaises(ValueError):self.recovery.resume(self.request)
        self.assertEqual(["NFS"],[value for event,value in self.events if event=="replay"])
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.recovery.path(self.request).read_text())["phase"])
    def test_new_operation_or_service_marker_cannot_borrow_source_checkpoint(self):
        self.recovery.capture(self.request);self.events.clear()
        self.marker={**self.marker,"maintenanceKind":"SERVICE"}
        with self.assertRaises(ValueError):self.recovery.resume(self.request)
        self.marker={**self.marker,"maintenanceKind":"ROOT","scope":{**self.marker["scope"],"operationUuid":str(uuid.uuid4())}}
        request={**self.request,"operationUuid":self.marker["scope"]["operationUuid"]}
        with self.assertRaises(ValueError):self.recovery.resume(request)
        self.assertFalse(any(event in ("authorize","replay") for event,value in self.events))

    def test_root_replay_guard_requires_live_own_writer_lock_and_immutable_source_paths(self):
        import fcntl
        self.recovery.capture(self.request);path=self.recovery.path(self.request);saved=json.loads(path.read_text());saved["phase"]="REPLAYING";rendered_json(path,saved)
        authorization=self.root/"authorization";authorization.mkdir(mode=0o700);self.recovery.authorization=authorization
        lock=self.root/"writer.lock";lock.touch(mode=0o600)
        try:previous=os.dup(9)
        except OSError:previous=None
        descriptor=os.open(lock,os.O_RDWR)
        if descriptor!=9:os.dup2(descriptor,9);os.close(descriptor)
        try:
            info=os.fstat(9);proof={"mode":"ROOT_SOURCE_RESTORE","maintenanceScope":self.marker["scope"],"scope":{key:self.request[key] for key in ("instanceUuid","operationUuid","revision")},
                                  "sourceManifestSha256":saved["sourceRendered"]["manifestSha256"],"pid":os.getpid(),"startTicks":Path("/proc/self/stat").read_text().rpartition(")")[2].split()[19],
                                  "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"lockDevice":info.st_dev,"lockInode":info.st_ino}
            rendered_json(authorization/"writer.json",proof)
            env={"ABLESTACK_STORAGE_RENDERED_REPLAY":str(self.driver.store.pointer()),"ABLESTACK_STORAGE_RENDERED_FROM":str(self.driver.store.pointer()),"ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(lock)}
            with patch.dict(os.environ,env):
                with self.assertRaisesRegex(ValueError,"own exclusive lock"):self.recovery.guard()
                fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
                self.assertTrue(self.recovery.guard()["rootSourceReplayAuthorized"])
                with patch.dict(os.environ,{"ABLESTACK_STORAGE_RENDERED_REPLAY":str(self.root/"foreign")}):
                    with self.assertRaises(ValueError):self.recovery.guard()
                proof["startTicks"]="0";rendered_json(authorization/"writer.json",proof)
                with self.assertRaises(ValueError):self.recovery.guard()
        finally:
            if previous is not None:os.dup2(previous,9);os.close(previous)
            else:os.close(9)

    def test_caller_snapshot_or_skip_write_flag_is_not_a_root_authority(self):
        for field in ("sourceRendered","sourceGeneration","canonicalDesiredState","skipDesiredWrite"):
            with self.assertRaises(ValueError):self.recovery.capture({**self.request,field:{}})
        self.assertFalse(self.recovery.path(self.request).exists())

    def test_actual_cli_forged_root_mode_cannot_reach_any_protocol_writer(self):
        import subprocess
        # LIB is usr/local/lib/ablestack-storage; use the repository source path.
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        payload=self.root/"public-request.json";payload.write_text('{"enabled":false}')
        before=self.recovery.canonical_bytes()
        env=dict(os.environ,ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY="1",ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/"cli-writer.lock"),
                 ABLESTACK_STORAGE_GENERATION_DIR=str(self.root/"cli-generation"),ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.root/"cli-maintenance"),
                 ABLESTACK_STORAGE_RENDERED_GENERATIONS=str(self.root/"cli-rendered"))
        for arguments in (("nfs","export","apply"),("smb","share","apply"),("iscsi","target","apply"),("nvmeof","subsystem","apply")):
            result=subprocess.run([str(cli),*arguments,str(payload)],env=env,capture_output=True,text=True,timeout=10)
            self.assertNotEqual(0,result.returncode)
            self.assertEqual(before,self.recovery.canonical_bytes())
        self.assertFalse((self.root/"cli-rendered").exists());self.assertFalse((self.root/"cli-maintenance").exists())

    def test_exact_signed_canonical_writers_leave_all_seven_original_bytes_under_source_mode(self):
        import ast,subprocess
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl";source=cli.read_text()
        for name in DESIRED_PATHS:
            path=self.config/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps({"original":name})+"\n");path.chmod(0o600)
        before=self.recovery.canonical_bytes();payload=self.root/"public-target.json";payload.write_text('{"newPublicState":true}')
        # Execute the exact reviewed sink bodies, independently of service setup.
        for name,target in (("persist_nfs_desired_state","desired-state/nfs-export-apply.json"),("persist_smb_desired_state","desired-state/smb-share-apply.json")):
            start=source.index(name+"() {");end=source.index("\n}\n",start)+3;function=source[start:end]
            script=function+"\n"+name+' "$1"\n'
            env=dict(os.environ,ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY="1",DESIRED_STATE_DIR=str(self.config/"desired-state"),NFS_DESIRED_STATE_FILE=str(self.config/"desired-state/nfs-export-apply.json"),SMB_DESIRED_STATE_FILE=str(self.config/"desired-state/smb-share-apply.json"))
            result=subprocess.run(["bash","-c",script,"sink-test",str(payload)],env=env,capture_output=True,text=True,timeout=5)
            self.assertEqual(0,result.returncode,result.stderr)
        for function,target in (("apply_iscsi_targets","iscsi-targets.json"),("apply_nvmeof_subsystems","nvmeof-subsystems.json")):
            start=source.index(function+"() {");begin=source.index("<<'PY'\n",start)+len("<<'PY'\n");end=source.index("\nPY",begin)
            tree=ast.parse(source[begin:end])
            guarded=[node for node in tree.body if isinstance(node,ast.If) and "ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY" in ast.unparse(node.test)
                     and any(isinstance(item,ast.With) for item in ast.walk(node))]
            self.assertEqual(1,len(guarded))
            namespace={"os":os,"json":json,"payload":{"newPublicState":True},"sanitized":lambda value:value,"state_file":str(self.config/target),"state_dir":str(self.config),"state_payload":{"newPublicState":True}}
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY":"1"}):exec(compile(ast.Module(body=guarded,type_ignores=[]),str(cli),"exec"),namespace)
        self.assertEqual(before,self.recovery.canonical_bytes())

    def test_missing_canonical_files_cannot_hide_an_unprotected_or_symlinked_parent(self):
        desired=self.config/"desired-state";desired.mkdir(mode=0o777);desired.chmod(0o777)
        with self.assertRaisesRegex(ValueError,"parent is not protected"):self.recovery.canonical_bytes()
        desired.rmdir();outside=self.root/"outside";outside.mkdir(mode=0o700);desired.symlink_to(outside,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,"parent is not protected"):self.recovery.canonical_bytes()

if __name__=="__main__":unittest.main()
