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


"""Exercise the actual embedded NFS readiness probe without privileged mutations."""
import ast
import ipaddress
from pathlib import Path
import re
import subprocess
import tempfile
import time
import os
import unittest
from unittest.mock import Mock

SOURCE = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/bin/ablestack-storagectl"


def probe_namespace():
    blocks = re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S)
    block = next(value for value in blocks if "def probe_nfs_export_visibility(" in value)
    tree = ast.parse(block)
    node = next(item for item in tree.body if isinstance(item, ast.FunctionDef)
                and item.name == "probe_nfs_export_visibility")
    process = Mock()
    process.TimeoutExpired = subprocess.TimeoutExpired
    process.PIPE, process.DEVNULL = subprocess.PIPE, subprocess.DEVNULL
    process.run.return_value = subprocess.CompletedProcess(["ip"], 0,
                                                         "2: eth0 inet 10.1.1.9/24 scope global eth0", "")
    ns = dict(ipaddress=ipaddress, subprocess=process, time=time, tempfile=tempfile, os=os,
              payload={}, endpoint_key=lambda ip, port: str(port))
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), ns)
    return ns


class NfsReadinessTest(unittest.TestCase):
    def test_restricted_acl_skips_local_mount_without_disabling_listener(self):
        ns = probe_namespace()
        endpoint = {"listenIp": "0.0.0.0", "port": 2049, "listening": True}
        export = {"uuid": "export", "name": "restricted", "pseudo": "/restricted",
                  "clients": [{"clients": "10.9.0.0/24"}]}
        failures = ns["probe_nfs_export_visibility"]([endpoint], {("0.0.0.0", 2049): [export]})
        self.assertEqual([], failures)
        self.assertTrue(endpoint["listening"])
        self.assertTrue(endpoint["probeSuccess"])
        self.assertIsNone(endpoint["exportsVisible"])
        self.assertEqual("SKIPPED_ACL", endpoint["probeResults"][0]["status"])
        self.assertEqual(1, ns["subprocess"].run.call_count)

    def test_eligible_client_probe_is_bounded_and_cleans_up_without_recursion(self):
        ns = probe_namespace()
        with tempfile.TemporaryDirectory() as tmp:
            ns["nfs_probe_root"] = tmp
            ns["subprocess"].run.side_effect = [
                subprocess.CompletedProcess(["ip"], 0, "2: eth0 inet 10.1.1.9/24 scope global eth0", ""),
                subprocess.TimeoutExpired(["mount"], 15),
                subprocess.CompletedProcess(["umount"], 0, "", ""),
            ]
            endpoint = {"listenIp": "0.0.0.0", "port": 2049, "listening": True}
            export = {"uuid": "export", "pseudo": "/export", "clients": [{"clients": "*"}]}
            failures = ns["probe_nfs_export_visibility"]([endpoint], {("0.0.0.0", 2049): [export]})
            self.assertEqual([], failures)
            self.assertEqual("PROBE_PENDING", endpoint["probeResults"][0]["status"])
            self.assertLessEqual(ns["subprocess"].run.call_args_list[1].kwargs["timeout"], 15)
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_cleanup_timeout_preserves_original_probe_result(self):
        ns = probe_namespace()
        with tempfile.TemporaryDirectory() as tmp:
            ns["nfs_probe_root"] = tmp
            ns["subprocess"].run.side_effect = [
                subprocess.CompletedProcess(["ip"], 0, "2: eth0 inet 10.1.1.9/24 scope global eth0", ""),
                subprocess.TimeoutExpired(["mount"], 15),
                subprocess.TimeoutExpired(["umount"], 10),
            ]
            endpoint = {"listenIp": "0.0.0.0", "port": 2049, "listening": True}
            export = {"uuid": "export", "pseudo": "/export", "clients": [{"clients": "*"}]}
            failures = ns["probe_nfs_export_visibility"]([endpoint], {("0.0.0.0", 2049): [export]})
            self.assertEqual([], failures)
            result = endpoint["probeResults"][0]
            self.assertEqual("PROBE_PENDING", result["status"])
            self.assertTrue(result["cleanupPending"])
            self.assertTrue(endpoint["listening"])


if __name__ == "__main__":
    unittest.main()
