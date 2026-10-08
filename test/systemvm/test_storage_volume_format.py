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
              expected_size=10 * (1 << 40), format_deadline=1500, shutil=Mock(which=Mock(return_value="/sbin/mkfs.xfs")),
              run=Mock(return_value=subprocess.CompletedProcess(["wipefs"], 0, '{"signatures":[]}', "")),
              subprocess=command, time=clock, phase=phase, recovery=recovery,
              blkid_value=lambda device, field: "xfs" if field == "TYPE" else "fs-uuid", json=__import__("json"), uuid=uuid, os=os, payload={"provisioningType":"SPARSE"})
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), ns)
    return ns, phases, process


class VolumeFormatTest(unittest.TestCase):
    def test_large_format_survives_multiple_probe_intervals_and_verifies_identity(self):
        ns, phases, process = formatter_namespace()
        self.assertEqual("xfs", ns["format_empty_device"]())
        self.assertEqual("VERIFYING_FILESYSTEM", phases[-1])
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


class VolumeResumeSafetyTest(unittest.TestCase):
    def setUp(self):
        block = next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if "def format_empty_device():" in value)
        nodes = [node for node in ast.parse(block).body if isinstance(node, ast.FunctionDef) and node.name in ("require_resume_request", "require_resume_filesystem")]
        self.ns = {"uuid": uuid, "active_formatter": lambda record: False}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), self.ns)
        volume = str(uuid.uuid4()); self.fs = str(uuid.uuid4())
        self.request = {"instanceUuid":str(uuid.uuid4()), "managerOperationUuid":str(uuid.uuid4()), "revision":3,
                        "volumeUuid":volume, "operationId":"volume-"+volume, "provisioningType":"SPARSE",
                        "importMode":"MOUNT_EXISTING", "resumeOnly":True, "expectedFilesystemUuid":self.fs}
        self.record = {"volumeUuid":volume, "operationId":"volume-"+volume, "formatStarted":True,
                       "filesystem":"xfs", "filesystemUuid":self.fs}

    def test_fixed_resume_accepts_only_same_scoped_known_existing_filesystem(self):
        self.assertTrue(self.ns["require_resume_request"](self.request, True))
        self.ns["require_resume_filesystem"](self.request,self.record,"xfs",self.fs)
        self.assertTrue(self.ns["require_resume_request"]({**self.request,"provisioningType":"THIN"}, True))

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
