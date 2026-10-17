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


import ast,copy,errno,hashlib,json,os,re,subprocess,sys,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import Mock
R=Path(__file__).resolve().parents[2]
LIB=R/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import volume_formatter as v
CLI=R/"systemvm/debian/usr/local/bin/ablestack-storagectl"

class StorageVolumeFormatterTest(unittest.TestCase):
    def setUp(self):
        self.volume,self.fs,self.boot,self.current=(str(uuid.uuid4())for _ in range(4))
        self.owner={"instanceUuid":str(uuid.uuid4()),"managerOperationUuid":str(uuid.uuid4()),"revision":3}
        self.args=["mkfs.xfs","-f","-K","/dev/synthetic-only"]
        self.identity=v.volume_capture_formatter(111,self.args,self.args[-1],self.boot,
              lambda pid:{"pid":pid,"uid":os.geteuid(),"startTicks":123,"state":"S","argv":[os.fsencode(a)for a in self.args]})
        self.record={"phase":"FILESYSTEM_VERIFIED","formatStarted":True,"volumeUuid":self.volume,
            "originalFormatOwner":self.owner,"formatterBootId":self.boot,"formatterStartTicks":123,"formatterPid":111,
            "formatter":"mkfs.xfs","formatterIdentity":self.identity,"devicePath":self.args[-1],
            "serial":self.volume.replace("-","")[:20],"volumeSizeBytes":10*(1<<40),"filesystem":"xfs",
            "filesystemUuid":self.fs,"formatterExitCode":0,"terminationPending":False,
            "formatDiscardPolicy":"SKIP_DISCARD","formatterOptions":["-f","-K"]}
        self.record["formatterSuccessReceipt"]={"schemaVersion":2,"originalFormatOwner":self.owner,
            "formatterBootId":self.boot,"formatterStartTicks":123,"formatterPid":111,"volumeUuid":self.volume,
            "filesystemUuid":self.fs,"filesystem":"xfs","formatterExitCode":0,"serial":self.record["serial"],
            "sizeBytes":self.record["volumeSizeBytes"],"formatDiscardPolicy":"SKIP_DISCARD",
            "formatterOptions":["-f","-K"],"verifiedEpoch":1}

    def verify(self,record=None,serial=None,size=None):
        return v.volume_verify_format_receipt(record or self.record,"xfs",self.fs,
                     serial or self.record["serial"],size or self.record["volumeSizeBytes"])

    def test_strong_success_is_exact_and_readonly(self):
        before=copy.deepcopy(self.record)
        self.verify()
        self.assertEqual(before,self.record)

    def test_schema1_or_uuid_headers_alone_are_never_new_resume_authority(self):
        for changes in ({"schemaVersion":1},{"schemaVersion":True},{"formatterExitCode":False},
                        {"formatterExitCode":1},{"formatterBootId":str(uuid.uuid4())},
                        {"formatterStartTicks":124},{"formatterPid":112},{"verifiedEpoch":float("nan")},
                        {"formatterOptions":["-f","--foreign"]}):
            changed=copy.deepcopy(self.record);changed["formatterSuccessReceipt"].update(changes)
            with self.subTest(changes=changes),self.assertRaises(ValueError):self.verify(changed)

    def test_foreign_original_owner_live_serial_size_and_fs_are_rejected(self):
        for changes in ({"sizeBytes":1},{"serial":str(uuid.uuid4())},{"filesystemUuid":str(uuid.uuid4())},
                        {"originalFormatOwner":{**self.owner,"managerOperationUuid":str(uuid.uuid4())}}):
            changed=copy.deepcopy(self.record);changed["formatterSuccessReceipt"].update(changes)
            with self.subTest(changes=changes),self.assertRaises(ValueError):self.verify(changed)
        with self.assertRaises(ValueError):self.verify(size=self.record["volumeSizeBytes"]+512)
        with self.assertRaises(ValueError):self.verify(serial=str(uuid.uuid4()))
        changed=copy.deepcopy(self.record);changed["terminationPending"]=True
        with self.assertRaises(ValueError):self.verify(changed)

    def test_source_boot_is_bound_but_reboot_never_probes_a_reused_numeric_pid(self):
        reader=Mock(side_effect=AssertionError("old PID must not be inspected"))
        observed=v.volume_observe_formatter(self.record,self.current,reader)
        self.assertFalse(observed["active"]);self.assertTrue(observed["observationComplete"])
        self.assertEqual("PREVIOUS_BOOT",observed["reason"]);self.assertEqual(self.current,observed["bootId"])
        reader.assert_not_called()
        self.verify()

    def test_permission_io_and_argv_change_remain_unknown_not_inactive(self):
        for failure in (PermissionError(errno.EPERM,"private"),OSError(errno.EIO,"private")):
            seen=v.volume_observe_formatter(self.record,self.boot,Mock(side_effect=failure))
            self.assertIsNone(seen["active"]);self.assertFalse(seen["observationComplete"])
            self.assertNotIn("private",json.dumps(seen))
        changed={"startTicks":123,"state":"S","argv":[b"foreign"]}
        self.assertIsNone(v.volume_observe_formatter(self.record,self.boot,lambda pid:changed)["active"])

    def test_pid_exit_reuse_and_zombie_are_known_inactive_without_foreign_signals(self):
        for reader in (Mock(side_effect=FileNotFoundError()),Mock(side_effect=ProcessLookupError()),
                       lambda pid:{"startTicks":124,"state":"S","argv":[b"foreign"]},
                       lambda pid:{"startTicks":123,"state":"Z","argv":[]}):
            seen=v.volume_observe_formatter(self.record,self.boot,reader)
            self.assertFalse(seen["active"]);self.assertTrue(seen["observationComplete"])
        live={"startTicks":123,"state":"D","argv":[os.fsencode(a)for a in self.args]}
        seen=v.volume_observe_formatter(self.record,self.boot,lambda pid:live)
        self.assertTrue(seen["active"]);self.assertEqual("D",seen["state"])

    def test_legacy_complete_is_readable_but_partial_or_new_missing_identity_is_unknown(self):
        old={**self.record,"formatterIdentity":None,"originalFormatOwner":None,"formatterSuccessReceipt":{"schemaVersion":1}}
        self.assertTrue(v.volume_observe_formatter({**old,"phase":"COMPLETE"},self.boot)["observationComplete"])
        self.assertIsNone(v.volume_observe_formatter({**old,"phase":"FORMATTING"},self.boot)["active"])
        self.assertIsNone(v.volume_observe_formatter({**self.record,"phase":"COMPLETE","formatterIdentity":None},self.boot)["active"])

    def test_owner_aliases_are_exact_and_private_or_boolean_fields_are_rejected(self):
        self.assertEqual(self.owner,v.volume_format_owner({**self.owner,"managerRevision":3}))
        self.assertEqual(self.owner,v.volume_format_owner({k:w for k,w in self.owner.items()if k!="revision"}|{"managerRevision":3}))
        for changes in ({"managerRevision":4},{"revision":True},{"instanceUuid":"FOREIGN"}):
            with self.assertRaises(ValueError):v.volume_format_owner({**self.owner,**changes})
        with self.assertRaises(ValueError):v.volume_original_owner({**self.owner,"privateBody":"private"})

    def test_capture_owns_start_ticks_before_wait_even_if_child_already_zombie(self):
        identity=v.volume_capture_formatter(111,self.args,self.args[-1],self.boot,
             lambda pid:{"pid":pid,"uid":os.geteuid(),"startTicks":456,"state":"Z","argv":[]})
        self.assertEqual(456,identity["startTicks"])
        with self.assertRaises(ValueError):
            v.volume_capture_formatter(111,self.args,self.args[-1],self.boot,
             lambda pid:{"pid":pid,"startTicks":456,"state":"S","argv":[b"foreign"]})

    def test_real_local_owned_process_metadata_is_bounded_and_start_pinned(self):
        child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(10)"])
        try:
            row=v.volume_formatter_process(child.pid)
            self.assertEqual(child.pid,row["pid"]);self.assertGreater(row["startTicks"],0)
            self.assertEqual(os.geteuid(),row["uid"])
        finally:child.terminate();child.wait(timeout=3)

    def test_signed_attach_and_status_embed_every_exact_canonical_helper_body(self):
        text=CLI.read_text();blocks=re.findall(r"# BEGIN_CANONICAL_VOLUME_FORMATTER\n(.*?)\n# END_CANONICAL_VOLUME_FORMATTER",text,re.S)
        self.assertEqual(2,len(blocks))
        canonical=(LIB/"volume_formatter.py").read_text().rstrip()
        self.assertTrue(all(block==canonical for block in blocks))
        self.assertNotIn("from volume_formatter import",text)

    def test_native_resume_producer_keeps_original_owner_but_attests_new_caller_boot_and_fs(self):
        block=next(b for b in re.findall(r"<<'PY'\n(.*?)\nPY",CLI.read_text(),re.S)if "def format_empty_device():"in b)
        call=next(n for n in ast.walk(ast.parse(block))if isinstance(n,ast.Call)and isinstance(n.func,ast.Name)and n.func.id=="phase"and any(k.arg=="resumeReceipt"for k in n.keywords))
        expression=next(k.value for k in call.keywords if k.arg=="resumeReceipt")
        caller={**self.owner,"managerOperationUuid":str(uuid.uuid4()),"revision":4}
        ns={"current_owner":caller,"operation":self.record,"volume_original_owner":v.volume_original_owner,
            "volume_key":self.volume,"fs_uuid":self.fs,"filesystem":"xfs","resolved_device":{"serial":self.record["serial"]},
            "expected_size":self.record["volumeSizeBytes"],"mount_path":"/srv/ablestack-storage/volumes/"+self.volume,
            "current_boot":self.current,"time":Mock(time=Mock(return_value=123))}
        receipt=eval(compile(ast.Expression(expression),str(CLI),"eval"),ns)
        self.assertEqual({"schemaVersion":1,**caller,"originalFormatOwner":self.owner,"volumeUuid":self.volume,
            "operationId":"volume-"+self.volume,"filesystemUuid":self.fs,"filesystem":"xfs",
            "serial":self.record["serial"],"sizeBytes":self.record["volumeSizeBytes"],"mountPath":ns["mount_path"],
            "bootId":self.current,"formatInvoked":False,"completedEpoch":123},receipt)

    def test_actual_status_unknown_is_closed_and_never_rewrites_journal_or_writer_file(self):
        with tempfile.TemporaryDirectory()as folder:
            root=Path(folder);volume=str(uuid.uuid4());path=root/(volume+".json")
            original={"volumeUuid":volume,"operationId":"volume-"+volume,"phase":"FORMATTING","formatStarted":True,"formatterPid":123}
            path.write_text(json.dumps(original));path.chmod(0o600);before=path.read_bytes()
            request=root/"request.json";request.write_text(json.dumps({"volumeUuid":volume}))
            lock=root/"never"/"writer"
            result=subprocess.run([str(CLI),"volume","operation","status",str(request)],env=dict(os.environ,ABLESTACK_STORAGE_VOLUME_OPERATIONS=folder,ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(lock)),capture_output=True,text=True,timeout=10)
            self.assertEqual(0,result.returncode,result.stderr)
            value=json.loads(result.stdout)
            self.assertIsNone(value["formatterActive"]);self.assertFalse(value["formatterObservationComplete"])
            self.assertEqual("RECOVERY_REQUIRED",value["status"]);self.assertTrue(value["terminationPending"])
            self.assertEqual(original,value["operation"]);self.assertEqual(before,path.read_bytes());self.assertFalse(lock.exists())


class StorageVolumeLifecycleJournalObservationTest(unittest.TestCase):
    """Exercise the actual read-only CLI used before VM/DATA lifecycle effects."""
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.root=Path(self.temporary.name)
        self.volume=str(uuid.uuid4())
        self.operations=self.root/"operations"
        self.payload=self.root/"request.json"
        self.payload.write_text(json.dumps({"volumeUuid":self.volume}))
        self.lock=self.root/"never-created"/"writer.lock"

    def tearDown(self):
        self.temporary.cleanup()

    def observe(self):
        return subprocess.run([str(CLI),"volume","operation","status",str(self.payload)],
            env=dict(os.environ,ABLESTACK_STORAGE_VOLUME_OPERATIONS=str(self.operations),
                     ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.lock)),
            capture_output=True,text=True,timeout=10)

    def journal(self):
        self.operations.mkdir(mode=0o700,exist_ok=True)
        path=self.operations/(self.volume+".json")
        value={"volumeUuid":self.volume,"operationId":"volume-"+self.volume,"phase":"COMPLETE",
               "formatStarted":False,"terminationPending":False}
        path.write_text(json.dumps(value));path.chmod(0o600)
        return path

    def rejected(self):
        result=self.observe()
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertNotIn('"status": "NOT_STARTED"',result.stdout)
        self.assertFalse(self.lock.exists())

    def test_genuine_absence_preserves_legacy_not_started_without_creating_directory(self):
        result=self.observe()
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual("NOT_STARTED",json.loads(result.stdout)["status"])
        self.assertFalse(self.operations.exists());self.assertFalse(self.lock.exists())

    def test_genuine_missing_journal_in_protected_directory_is_read_only(self):
        self.operations.mkdir(mode=0o700)
        before=self.operations.stat()
        result=self.observe()
        self.assertEqual("NOT_STARTED",json.loads(result.stdout)["status"])
        self.assertEqual([],list(self.operations.iterdir()))
        self.assertEqual(before.st_mtime_ns,self.operations.stat().st_mtime_ns)

    def test_dangling_journal_is_unknown_not_not_started(self):
        self.operations.mkdir(mode=0o700)
        path=self.operations/(self.volume+".json");path.symlink_to(self.root/"absent")
        before=path.lstat()
        self.rejected()
        self.assertTrue(path.is_symlink());self.assertEqual(before.st_ino,path.lstat().st_ino)
        self.assertFalse((self.root/"absent").exists())

    def test_dangling_directory_is_unknown_not_not_started(self):
        self.operations.symlink_to(self.root/"absent")
        self.rejected()
        self.assertTrue(self.operations.is_symlink());self.assertFalse((self.root/"absent").exists())

    def test_directory_symlink_to_other_journals_is_rejected(self):
        target=self.root/"foreign";target.mkdir(mode=0o700)
        self.operations.symlink_to(target,target_is_directory=True)
        self.rejected();self.assertEqual([],list(target.iterdir()))

    def test_unprotected_directory_cannot_assert_no_formatter(self):
        self.operations.mkdir(mode=0o777);self.operations.chmod(0o777)
        self.rejected();self.assertEqual([],list(self.operations.iterdir()))

    def test_linked_regular_journal_is_rejected_without_rewriting_either_name(self):
        path=self.journal();other=self.root/"other.json";os.link(path,other);before=path.read_bytes()
        self.rejected();self.assertEqual(before,path.read_bytes());self.assertEqual(before,other.read_bytes())

    def test_symlink_to_valid_complete_journal_is_not_followed(self):
        path=self.journal();target=self.root/"foreign.json";path.rename(target);path.symlink_to(target)
        before=target.read_bytes()
        self.rejected();self.assertEqual(before,target.read_bytes())

    def test_malformed_journal_is_not_absence_and_is_preserved(self):
        path=self.journal();path.write_bytes(b'{"phase":');before=path.read_bytes()
        self.rejected();self.assertEqual(before,path.read_bytes())

    def test_healthy_no_format_complete_journal_remains_compatible(self):
        path=self.journal();before=path.read_bytes();metadata=path.stat()
        result=self.observe()
        self.assertEqual(0,result.returncode,result.stderr)
        value=json.loads(result.stdout)
        self.assertEqual("COMPLETE",value["status"]);self.assertFalse(value["formatterActive"])
        self.assertTrue(value["formatterObservationComplete"]);self.assertFalse(value["terminationPending"])
        self.assertEqual(before,path.read_bytes());self.assertEqual(metadata.st_mtime_ns,path.stat().st_mtime_ns)
        self.assertFalse(self.lock.exists())

if __name__=="__main__":unittest.main()
