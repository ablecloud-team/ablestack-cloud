#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Read-only baseline validation must never repair/delete bitmaps or launch a backup."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import thirdparty_volume_backup as backup


class IncrementalSourceInspectionTest(unittest.TestCase):
    def setUp(self):
        self.plan = {"vmName": "i-2-10-VM", "stopped": True, "engine": "QCOW2",
                     "checkpointName": "parent", "diskPaths": ["/primary/root", "/primary/data"],
                     "checkpointXml": '<domaincheckpoint><name>parent</name><disks>'
                     '<disk name="vda" checkpoint="bitmap" bitmap="parent"/>'
                     '<disk name="vdb" checkpoint="bitmap" bitmap="parent"/>'
                     '</disks></domaincheckpoint>'}
        self.bitmap = {"name": "parent", "flags": ["auto"]}

    def run_command(self, args, timeout=300):
        if args[0] == "virsh":
            return "shut off\n"
        if args[0] == "qemu-img":
            return json.dumps([{"format": "qcow2", "format-specific": {"data": {"bitmaps": [self.bitmap]}}}])
        if args[0] == "rbd":
            return '[{"name":"parent"}]'
        raise AssertionError(args)

    def test_multidisk_qcow2_with_the_same_bitmap_name_on_each_disk_is_valid(self):
        with patch.object(backup, "run", side_effect=self.run_command) as run:
            proof = backup.inspect_incremental_source(self.plan)
        self.assertTrue(proof["validated"])
        self.assertEqual(2, proof["volumeCount"])
        for call in run.call_args_list:
            args = call.args[0]
            self.assertNotIn("--force-share", args)
            self.assertNotIn("--redefine", args)
            self.assertNotIn("--remove", args)

    def test_missing_busy_disabled_and_inconsistent_bitmaps_are_rejected(self):
        for bitmap in ({"name": "other", "flags": ["auto"]}, {"name": "parent", "flags": []},
                       {"name": "parent", "flags": ["auto", "inconsistent"]},
                       {"name": "parent", "flags": ["auto"], "busy": True},
                       {"name": "parent", "flags": ["auto", "in-use"]}):
            with self.subTest(bitmap=bitmap):
                self.bitmap = bitmap
                with patch.object(backup, "run", side_effect=self.run_command):
                    with self.assertRaises(RuntimeError):
                        backup.inspect_incremental_source(self.plan)

    def test_a_bitmap_in_a_backing_layer_does_not_prove_the_active_baseline(self):
        def run(args, timeout=300):
            if args[0] == "virsh":
                return "shut off\n"
            return json.dumps([{"format": "qcow2"}, {"format": "qcow2",
                              "format-specific": {"data": {"bitmaps": [self.bitmap]}}}])
        with patch.object(backup, "run", side_effect=run):
            with self.assertRaises(RuntimeError):
                backup.inspect_incremental_source(self.plan)

    def test_rbd_checks_every_parent_snapshot_without_creating_or_removing_one(self):
        self.plan.update(engine="RBD_DIFF", diskPaths=["rbd:pool/root", "rbd:pool/data"])
        with patch.object(backup, "run", side_effect=self.run_command) as run:
            self.assertTrue(backup.inspect_incremental_source(self.plan)["validated"])
        snapshots = [call.args[0] for call in run.call_args_list if call.args[0][0] == "rbd"]
        self.assertEqual(2, len(snapshots))
        self.assertTrue(all(args[1:3] == ["snap", "ls"] for args in snapshots))

    def test_missing_parent_rbd_snapshot_cannot_be_accepted(self):
        self.plan.update(engine="RBD_DIFF", diskPaths=["rbd:pool/root"])
        with patch.object(backup, "run", side_effect=lambda args, timeout=300: "shut off" if args[0] == "virsh" else "[]"):
            with self.assertRaises(RuntimeError):
                backup.inspect_incremental_source(self.plan)

    def test_running_vm_or_unavailable_libvirt_blocks_offline_inspection(self):
        with patch.object(backup, "run", return_value="running"):
            with self.assertRaises(RuntimeError):
                backup.inspect_incremental_source(self.plan)
        with patch.object(backup, "run", side_effect=RuntimeError("unreachable")):
            with self.assertRaises(RuntimeError):
                backup.inspect_incremental_source(self.plan)

    def test_checkpoint_identity_must_match(self):
        self.plan["checkpointName"] = "another"
        with patch.object(backup, "run", side_effect=self.run_command):
            with self.assertRaises(RuntimeError):
                backup.inspect_incremental_source(self.plan)

    def test_cli_failure_never_creates_engine_state_or_exposes_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory) / "inspection.json"
            plan.write_text(json.dumps({"diskPaths": ["rbd:pool/root:key=synthetic-secret"]}))
            result = subprocess.run([sys.executable, str(Path(backup.__file__)), "--plan-file", str(plan),
                                     "--inspect-incremental"], text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("synthetic-secret", result.stderr + result.stdout)
            self.assertEqual([plan], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
