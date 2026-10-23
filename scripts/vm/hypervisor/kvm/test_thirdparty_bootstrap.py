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

"""Parent Jobs carry only owned markers and never wait for large image generation."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("thirdparty_bootstrap", Path(__file__).with_name("thirdparty_bootstrap.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / "stage/i-2-1-VM/point/.volume-bootstrap"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("backup-one\n")
        self.payload = self.root / "stage/bootstrap/host/policy"

    def test_publish_retry_and_retire_leave_original_marker_and_images(self):
        image = self.source.with_name("disk.qcow2")
        image.write_text("disk data")
        marker = Path(bootstrap.publish(self.payload, self.source, "backup-one"))
        self.assertEqual(str(marker), bootstrap.publish(self.payload, self.source, "backup-one"))
        self.assertEqual("backup-one", json.loads(marker.read_text())["backupUuid"])
        bootstrap.retire(self.payload, "backup-one")
        bootstrap.retire(self.payload, "backup-one")
        self.assertFalse(marker.exists())
        self.assertTrue(self.source.exists())
        self.assertTrue(image.exists())

    def test_wrong_backup_symlinks_and_unowned_markers_are_rejected(self):
        with self.assertRaises(ValueError):
            bootstrap.publish(self.payload, self.source, "different-backup")
        marker = Path(bootstrap.publish(self.payload, self.source))
        marker.write_text('{"version":1,"backupUuid":"different-backup"}')
        with self.assertRaises(ValueError):
            bootstrap.retire(self.payload, "backup-one")
        marker.unlink()
        marker.symlink_to(self.source)
        with self.assertRaises(OSError):
            bootstrap.retire(self.payload, "backup-one")
        self.assertEqual("backup-one\n", self.source.read_text())

    def test_netbackup_accepts_bootstrap_while_source_pipeline_is_in_progress(self):
        self.source.with_name(".staging.inprogress").touch()
        common = Path(__file__).with_name("netbackup-host-common.sh")
        env = dict(os.environ, TEST_COMMON=str(common), BACKUP_STAGING_ROOT=str(self.root / "stage"))
        command = 'source "$TEST_COMMON"; discover_completed_vm_backup_path i-2-1-VM 0'
        result = subprocess.run(["bash", "-c", command], capture_output=True, text=True, env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(self.source.parent), result.stdout)
        self.source.unlink()
        self.source.with_name(".staging.complete").touch()
        result = subprocess.run(["bash", "-c", command], capture_output=True, text=True, env=env)
        self.assertNotEqual(0, result.returncode)

    def test_netbackup_stale_parent_path_fails_before_requesting_a_backup(self):
        common = Path(__file__).with_name("netbackup-host-common.sh")
        env = dict(os.environ, TEST_COMMON=str(common), BACKUP_STAGING_ROOT=str(self.root / "stage"))
        command = '''source "$TEST_COMMON"
POLICY_SAFE=host-policy
NETBACKUP_PARENT_PAYLOAD_PATH=/old/parent
invoke_mold_api() { echo UNEXPECTED_API >&2; return 1; }
run_stage_vm_backup i-2-1-VM
'''
        result = subprocess.run(["bash", "-c", command], capture_output=True, text=True, env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Rerun netbackup-host-config.py", result.stdout)
        self.assertNotIn("UNEXPECTED_API", result.stderr)


if __name__ == "__main__":
    unittest.main()
