# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http: #www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.



"""Exercise format policy and recovery against the actual storagectl formatter."""
import ast
from pathlib import Path
import re
import os
import json
import stat
import tempfile
import subprocess
import unittest
import uuid
from unittest.mock import Mock

SOURCE = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/bin/ablestack-storagectl"


def formatter_namespace():
    block = next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S)
                 if "def format_empty_device():" in value)
    node = next(value for value in ast.parse(block).body
                if isinstance(value, ast.FunctionDef) and value.name == "format_empty_device")
    process = Mock(returncode=0, pid=111)
    process.communicate.side_effect = [
        subprocess.TimeoutExpired(["mkfs.xfs"], 5),
        subprocess.TimeoutExpired(["mkfs.xfs"], 5),
        ("formatted", ""),
    ]
    command = Mock(Popen=Mock(return_value=process), PIPE=subprocess.PIPE, TimeoutExpired=subprocess.TimeoutExpired)
    clock = Mock()
    clock.monotonic.side_effect = [0, 5, 10, 15, 20, 25]
    clock.time.return_value = 25
    operation = {}
    phases = []
    def phase(name, **diagnostic):
        operation.update(diagnostic)
        phases.append(name)
    def recovery(code, message):
        raise RuntimeError(code)
    ns = dict(requested_filesystem="xfs", operation=operation, device="/dev/verified-data",
              expected_size=10 * (1 << 40), format_deadline=1500, volume_key='fixture-volume', resolved_device={'serial':'fixture-serial'}, shutil=Mock(which=Mock(return_value="/sbin/mkfs.xfs")),
              run=Mock(return_value=subprocess.CompletedProcess(["wipefs"], 0, '{"signatures":[]}', "")),
              subprocess=command, time=clock, phase=phase, recovery=recovery,
              blkid_value=lambda device, field: "xfs" if field == "TYPE" else "fs-uuid", json=__import__("json"), uuid=uuid, os=os, payload={"provisioningType":"SPARSE"})
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), ns)
    return ns, phases, process


class VolumeFormatTest(unittest.TestCase):
    def test_large_format_survives_multiple_probe_intervals_and_verifies_identity(self):
        ns, phases, process = formatter_namespace()
        self.assertEqual("xfs", ns["format_empty_device"]())
        self.assertEqual("FILESYSTEM_VERIFIED", phases[-1])
        self.assertGreaterEqual(phases.count("FORMATTING"), 3)
        self.assertEqual(111, ns["operation"]["formatterPid"])
        self.assertEqual(3, process.communicate.call_count)
        self.assertEqual("fs-uuid", ns["operation"]["filesystemUuid"])
        self.assertEqual(10 * (1 << 40), ns["operation"]["volumeSizeBytes"])
        uuid.UUID(ns["operation"]["formatReceiptUuid"])

    def test_new_format_rejects_thin_or_unknown_provisioning_before_mkfs(self):
        for provision in ("THIN", None, "UNKNOWN"):
            ns, _, _ = formatter_namespace(); ns["payload"] = {"provisioningType": provision}
            with self.assertRaises(SystemExit): ns["format_empty_device"]()
            ns["subprocess"].Popen.assert_not_called()

    def test_discard_policy_is_per_exact_new_volume_and_options_never_become_arbitrary_flags(self):
        ns, _, _ = formatter_namespace();ns["payload"]["formatDiscardPolicy"]="SKIP_DISCARD"
        ns["format_empty_device"]()
        self.assertEqual(["mkfs.xfs","-f","-K","/dev/verified-data"],ns["subprocess"].Popen.call_args.args[0])
        self.assertEqual("SKIP_DISCARD",ns["operation"]["formatDiscardPolicy"])
        ns, _, _ = formatter_namespace();ns["payload"]["formatDiscardPolicy"]="SKIP_DISCARD";ns["requested_filesystem"]="ext4"
        ns["blkid_value"]=lambda device,field:"ext4" if field=="TYPE" else "fs-uuid"
        self.assertEqual("ext4",ns["format_empty_device"]())
        self.assertEqual(["mkfs.ext4","-F","-E","nodiscard,lazy_itable_init=1,lazy_journal_init=1","/dev/verified-data"],ns["subprocess"].Popen.call_args.args[0])
        ns, _, _ = formatter_namespace();ns["payload"]["formatDiscardPolicy"]="-f /dev/foreign"
        with self.assertRaises(SystemExit):ns["format_empty_device"]()
        ns["subprocess"].Popen.assert_not_called()

    def test_real_cli_format_capabilities_is_readonly_and_matches_supported_policy(self):
        with tempfile.TemporaryDirectory() as folder:
            environment=dict(os.environ,ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(Path(folder)/'writer'/'lock'))
            result=subprocess.run([str(SOURCE),'volume','operation','capabilities'],env=environment,capture_output=True,text=True,timeout=10)
            self.assertEqual(0,result.returncode,result.stderr);value=json.loads(result.stdout)
            self.assertEqual(['DEFAULT','SKIP_DISCARD'],value['formatDiscardPolicies']);self.assertTrue(value['sparseFormatRequired']);self.assertTrue(value['formatterSuccessReceiptSupported'])
            self.assertEqual([],list(Path(folder).iterdir()))

    def test_prior_format_never_authorizes_automatic_reformat(self):
        ns, _, _ = formatter_namespace()
        ns["operation"]["formatStarted"] = True
        with self.assertRaisesRegex(RuntimeError, "RECOVERY_REQUIRED"):
            ns["format_empty_device"]()
        ns["subprocess"].Popen.assert_not_called()

    def test_existing_signature_blocks_formatter(self):
        ns, _, _ = formatter_namespace()
        ns["run"].return_value = subprocess.CompletedProcess(["wipefs"], 0, '{"signatures":[{"type":"xfs"}]}', "")
        with self.assertRaisesRegex(RuntimeError, "RECOVERY_REQUIRED"):
            ns["format_empty_device"]()
        ns["subprocess"].Popen.assert_not_called()

    def test_deadline_preserves_pending_reconcile_state(self):
        ns, phases, process = formatter_namespace()
        ns["format_deadline"] = 1
        ns["time"].monotonic.side_effect = [0, 1, 2]
        process.communicate.side_effect = [subprocess.TimeoutExpired(["mkfs.xfs"], 1), ("", "")]
        with self.assertRaisesRegex(RuntimeError, "TIMED_OUT_PENDING_RECONCILE"):
            ns["format_empty_device"]()
        process.terminate.assert_called_once()
        self.assertTrue(ns["operation"]["formatStarted"])


    def test_uninterruptible_formatter_never_causes_unbounded_communicate_and_persists_recovery_first(self):
        ns, phases, process = formatter_namespace();ns["format_deadline"] = 1
        ns["time"].monotonic.side_effect = [0, 1, 2]
        process.communicate.side_effect = [subprocess.TimeoutExpired(["mkfs.xfs"], 1),
                                          subprocess.TimeoutExpired(["mkfs.xfs"], 5),
                                          subprocess.TimeoutExpired(["mkfs.xfs"], 5)]
        def terminate():
            self.assertIn("TIMED_OUT_PENDING_RECONCILE", phases)
            self.assertTrue(ns["operation"]["terminationPending"])
        process.terminate.side_effect = terminate
        with self.assertRaisesRegex(RuntimeError, "RECOVERY_REQUIRED"):ns["format_empty_device"]()
        process.kill.assert_called_once();self.assertTrue(ns["operation"]["formatterActive"])
        self.assertTrue(ns["operation"]["terminationPending"])
        self.assertEqual(3, process.communicate.call_count)
        self.assertTrue(all(0 < call.kwargs.get("timeout", 0) <= 5 for call in process.communicate.call_args_list))
        self.assertEqual("RECOVERY_REQUIRED", phases[-1])

class VolumeResumeSafetyTest(unittest.TestCase):
    def setUp(self):
        block = next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if "def format_empty_device():" in value)
        nodes = [node for node in ast.parse(block).body if isinstance(node, ast.FunctionDef) and node.name in ("require_resume_request", "require_resume_filesystem", "require_completed_format_receipt", "legacy_complete_mount_is_verified")]
        self.ns = {"uuid": uuid, "active_formatter": lambda record: False}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), self.ns)
        volume = str(uuid.uuid4()); self.fs = str(uuid.uuid4())
        self.request = {"instanceUuid":str(uuid.uuid4()), "managerOperationUuid":str(uuid.uuid4()), "revision":3,
                        "volumeUuid":volume, "operationId":"volume-"+volume, "provisioningType":"SPARSE",
                        "importMode":"MOUNT_EXISTING", "resumeOnly":True, "expectedFilesystemUuid":self.fs}
        self.record = {"volumeUuid":volume, "operationId":"volume-"+volume, "formatStarted":True,
                       "filesystem":"xfs", "filesystemUuid":self.fs, "formatterExitCode":0,
                       "formatterSuccessReceipt":{"schemaVersion":1,"volumeUuid":volume,"filesystemUuid":self.fs,"filesystem":"xfs","formatterExitCode":0}}

    def test_fixed_resume_accepts_only_same_scoped_known_existing_filesystem(self):
        self.assertTrue(self.ns["require_resume_request"](self.request, True))
        self.ns["require_resume_filesystem"](self.request,self.record,"xfs",self.fs)
        self.assertTrue(self.ns["require_resume_request"]({**self.request,"provisioningType":"THIN"}, True))

    def test_legacy_successful_complete_is_preserved_for_normal_mount_without_permitting_partial_resume(self):
        old={**self.record,"phase":"COMPLETE","formatterSuccessReceipt":None,"formatterExitCode":None}
        normal={**self.request,"resumeOnly":False}
        self.assertTrue(self.ns['legacy_complete_mount_is_verified'](normal,old,'xfs',self.fs))
        self.assertFalse(self.ns['legacy_complete_mount_is_verified'](self.request,old,'xfs',self.fs))
        for changes in ({"phase":"FORMATTING"},{"phase":"TIMED_OUT_PENDING_RECONCILE"},{"terminationPending":True},{"filesystemUuid":str(uuid.uuid4())}):
            self.assertFalse(self.ns['legacy_complete_mount_is_verified'](normal,{**old,**changes},'xfs',self.fs))

    def test_partial_filesystem_with_uuid_and_type_never_passes_resume_without_exit_success_receipt(self):
        for changes in ({"formatterSuccessReceipt":None},{"formatterExitCode":None},{"terminationPending":True},
                        {"formatterSuccessReceipt":{**self.record["formatterSuccessReceipt"],"formatterExitCode":1}}):
            with self.assertRaises(ValueError):self.ns['require_resume_filesystem'](self.request,{**self.record,**changes},'xfs',self.fs)

    def test_resume_requires_its_exact_frozen_filesystem_uuid(self):
        with self.assertRaises(ValueError):
            self.ns["require_resume_filesystem"]({**self.request,"expectedFilesystemUuid":str(uuid.uuid4())},self.record,"xfs",self.fs)

    def test_protected_operation_journal_rejects_writable_files_and_symlinks(self):
        block = next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if "def format_empty_device():" in value)
        function = next(node for node in ast.parse(block).body if isinstance(node,ast.FunctionDef) and node.name=="read_volume_operation")
        namespace={"os":os,"stat":stat,"json":json}
        exec(compile(ast.Module(body=[function],type_ignores=[]),str(SOURCE),"exec"),namespace)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'volume.json';path.write_text(json.dumps(self.record));path.chmod(0o600)
            self.assertEqual(self.record,namespace['read_volume_operation'](path))
            path.chmod(0o666)
            with self.assertRaises(ValueError):namespace['read_volume_operation'](path)
            path.chmod(0o600);real=path.with_suffix('.real');path.rename(real);path.symlink_to(real)
            with self.assertRaises(ValueError):namespace['read_volume_operation'](path)

    def test_resume_cannot_authorize_format_blank_partial_foreign_uuid_or_active_formatter(self):
        for changes in ({"importMode":"FORMAT_IF_EMPTY"}, {"resumeOnly":False}, {"operationId":"volume-"+str(uuid.uuid4())}, {"revision":True}):
            with self.assertRaises(ValueError): self.ns["require_resume_request"]({**self.request,**changes}, True)
        for record,kind,observed in ((self.record,None,None), ({**self.record,"filesystemUuid":None},"xfs",self.fs),
                                    (self.record,"xfs",str(uuid.uuid4())), ({**self.record,"volumeUuid":str(uuid.uuid4())},"xfs",self.fs),
                                    ({**self.record,"formatStarted":False},"xfs",self.fs)):
            with self.assertRaises(ValueError): self.ns["require_resume_filesystem"](self.request,record,kind,observed)
        self.ns["active_formatter"] = lambda record: True
        with self.assertRaises(ValueError): self.ns["require_resume_filesystem"](self.request,self.record,"xfs",self.fs)

class VolumeFreshDeadlineObservationTest(unittest.TestCase):
    def test_stale_uninterruptible_formatter_is_projected_without_rewriting_history(self):
        block=re.search(r"<<'PYVOLUME'\n(.*?)\nPYVOLUME",SOURCE.read_text(),re.S).group(1)
        function=next(node for node in ast.parse(block).body if isinstance(node,ast.FunctionDef) and node.name=='project_volume_operation')
        namespace={};exec(compile(ast.Module(body=[function],type_ignores=[]),str(SOURCE),'exec'),namespace)
        history={'phase':'FORMATTING','started':100,'formatDeadlineSeconds':1500,'formatterPid':123,'devicePath':'/dev/data','operationId':'volume-fixture'}
        before=dict(history)
        result=namespace['project_volume_operation'](history,{'active':True,'state':'D'},1700)
        self.assertEqual('RECOVERY_REQUIRED',result['status']);self.assertTrue(result['formatterActive'])
        self.assertTrue(result['terminationPending']);self.assertTrue(result['stale'])
        self.assertEqual('FORMATTING',result['operation']['phase']);self.assertEqual(before,history)
        self.assertEqual('RECOVERY_REQUIRED',result['observedOperation']['phase'])
        result=namespace['project_volume_operation'](history,{'active':False,'state':None},1700)
        self.assertEqual('RECONCILE_REQUIRED',result['status']);self.assertFalse(result['formatterActive'])

class VolumeMutationIdentityTest(unittest.TestCase):
    def setUp(self):
        block = next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if "def format_empty_device():" in value)
        self.tree = ast.parse(block)
        functions = [node for node in self.tree.body if isinstance(node, ast.FunctionDef) and node.name in ("compact", "parse_size", "assert_mutating_volume_identity")]
        self.ns = {"uuid": uuid}
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(SOURCE), "exec"), self.ns)
        self.identity = uuid.uuid4()
        self.resolved = {"matchedBy": "VOLUME_SERIAL", "serial": self.identity.hex[:20], "devicePath": "/dev/verified-data"}
        self.devices = [{"path": "/dev/verified-data", "size": 20 * (1 << 30)}]
        self.request = {"volumeUuid": str(self.identity)}

    def validate(self, resolved=None, request=None, size=None):
        self.ns["assert_mutating_volume_identity"](resolved or self.resolved, request or self.request, self.devices, size or self.devices[0]["size"])

    def test_full_and_pinned_truncated_volume_serial_are_valid(self):
        self.validate()
        self.validate({**self.resolved, "serial": str(self.identity)})

    def test_wrong_uuid_foreign_short_serial_or_filesystem_fallback_is_rejected(self):
        with self.assertRaises(ValueError): self.validate(request={"volumeUuid": str(uuid.uuid4())})
        for serial in ("", self.identity.hex[:8], uuid.uuid4().hex):
            with self.assertRaises(ValueError): self.validate({**self.resolved, "serial": serial})
        for matched in ("UNIQUE_BLANK_SIZE", "FILESYSTEM_UUID", "MOUNT_SOURCE"):
            with self.assertRaises(ValueError): self.validate({**self.resolved, "matchedBy": matched})

    def test_invalid_uuid_and_size_mismatch_are_rejected(self):
        with self.assertRaises(ValueError): self.validate(request={"volumeUuid": "wrong-uuid"})
        with self.assertRaises(ValueError): self.validate(size=self.devices[0]["size"] + 512)
        with self.assertRaises(ValueError): self.validate(request={**self.request, "config": {"filesystemUuid": "foreign"}})

    def test_format_resolver_never_enables_blank_size_fallback(self):
        calls = [node for node in ast.walk(self.tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "resolve_volume_device"]
        self.assertEqual(1, len(calls))
        option = next(item.value for item in calls[0].keywords if item.arg == "allow_blank_size_fallback")
        self.assertIs(False, ast.literal_eval(option))

if __name__ == "__main__":
    unittest.main()
