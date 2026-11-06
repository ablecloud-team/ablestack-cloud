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

"""Reject invalid provisioning inputs and ignore legacy host schedule flags at runtime."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).parent
VEEAM = ROOT / "veeam"


class VmBackupSelectionTest(unittest.TestCase):
    def test_veeam_rejects_invalid_selection_before_writing(self):
        for options in (("--vm-schedule-backup", "invalid"),
                        ("--vm-schedule-backup", "true", "--vm-exclude", "*")):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "not-created"
                result = subprocess.run([sys.executable, str(ROOT / "veeam-host-config.py"), *options],
                                        capture_output=True, text=True, timeout=10,
                                        env=dict(os.environ, ABLESTACK_VEEAM_ETC_DIR=str(destination)))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("--vm-schedule-backup", result.stderr)
                self.assertFalse(destination.exists())

    def test_runtime_load_does_not_inherit_the_previous_jobs_flag(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider), tempfile.TemporaryDirectory() as directory:
                config = Path(directory) / "job.conf"
                config.write_text('VM_SCHEDULE_BACKUP=true\nVM_INCLUDE="*"\nVM_EXCLUDE="*"\n'
                                  'MOLD_URL="http://mold.test/client/api"\nADMIN_APIKEY="synthetic-key"\n')
                if provider == "netbackup":
                    library = ROOT / "netbackup-host-common.sh"
                    body = r'''
resolve_config_file_path() { echo "$TEST_CONFIG"; }
log() { :; }
load_admin_secretkey() { :; }
load_backup_staging_root_from_mold() { :; }
load_staging_timeout_from_data_operation_config() { :; }
load_policy_schedule_config
'''
                else:
                    library = VEEAM / "mold-backup.lib.sh"
                    body = r'''
mold_backup_resolve_conf_path() { echo "$TEST_CONFIG"; }
mold_backup_notify_log() { :; }
mold_backup_resolve_api_secret() { :; }
mold_backup_load_veeam_connection() { :; }
mold_backup_supplement_guest_config() { :; }
mold_backup_apply_datadisk_profile() { :; }
mold_backup_load_common_staging() { :; }
mold_backup_load_config
'''
                script = 'set -euo pipefail\nsource "$TEST_LIBRARY"\n' + body
                script += 'echo "FIRST:${VM_SCHEDULE_BACKUP-}"\nsed "/^VM_SCHEDULE_BACKUP=/d" "$TEST_CONFIG" > "$TEST_CONFIG.next"\nmv "$TEST_CONFIG.next" "$TEST_CONFIG"\n'
                script += body.splitlines()[-1] + '\necho "SECOND:${VM_SCHEDULE_BACKUP-}"\n'
                result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                        env=dict(os.environ, TEST_LIBRARY=str(library), TEST_CONFIG=str(config)))
                self.assertEqual(0, result.returncode, result.stderr)
                expected = ["FIRST:", "SECOND:"]
                self.assertEqual(expected, result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
