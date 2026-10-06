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
import subprocess
import unittest
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
              blkid_value=lambda device, field: "xfs" if field == "TYPE" else "fs-uuid", json=__import__("json"))
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


if __name__ == "__main__":
    unittest.main()
