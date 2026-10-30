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

"""Parent policies must validate shared staging before any Mold backup request."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

DIRECTORY = Path(__file__).resolve().parent


class NetBackupStagingAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.events = self.root / "events"
        common = (DIRECTORY / "netbackup-host-common.sh").read_text()
        common += r'''
resolve_context() { POLICY_NAME=ablecube2; POLICY_SAFE=ablecube2; SCHEDULE_NAME=schedule; }
supervise_netbackup_hook() { :; }
ensure_runtime_dirs() { :; }
cleanup_stale_transient_state() { :; }
cleanup_runtime_history() { :; }
load_policy_schedule_config() { BACKUP_STAGING_ROOT="$TEST_ROOT/staging/ablestack-netbackup"; }
acquire_lock() { :; }
sanity_checks() { :; }
mark_context_in_progress() { touch "$TEST_ROOT/inprogress"; }
clear_context_in_progress() { rm -f "$TEST_ROOT/inprogress"; }
initialize_runtime_cache() { :; }
init_runtime_state() { :; }
cache_mold_virtual_machines() { echo VM_QUERY >> "$TEST_ROOT/events"; }
write_state_file() { :; }
log() { echo "$*" >> "$TEST_ROOT/events"; }
update_runtime_status() { echo "$1" >> "$TEST_ROOT/events"; }
list_target_vms() { :; }
read_mold_staging_setting() {
  case "$1" in
    storage.type) echo "${TEST_STORAGE_TYPE:-NFS}";;
    mount.path) echo "$TEST_ROOT/staging";;
    nfs.source) echo '192.168.1.161:/backup';;
    mount.options) echo '';;
    mount.timeout) echo "${TEST_MOUNT_TIMEOUT:-300}";;
  esac
}
'''
        (self.root / "netbackup-host-common.sh").write_text(common)
        shutil.copyfile(DIRECTORY / "netbackup-host-bpstart-notify.sh", self.root / "pre.sh")
        (self.root / "thirdparty_staging.sh").write_text(r'''#!/bin/bash
python3 - "$@" <<'PY'
import json, os, pathlib, sys
root = pathlib.Path(os.environ['TEST_ROOT'])
(root / 'validation.json').write_text(json.dumps(sys.argv[1:]))
if os.environ.get('TEST_UNAVAILABLE') == 'true':
    print('Unable to mount the configured NFS export.', file=sys.stderr)
    sys.exit(1)
pathlib.Path(sys.argv[6]).mkdir(parents=True, exist_ok=True)
print(123456)
PY
''')
        self.payload = self.root / "staging/ablestack-netbackup/bootstrap/ablecube2"
        self.env = dict(os.environ, TEST_ROOT=str(self.root), LOG_FILE=str(self.root / "hook.log"),
                        NETBACKUP_PARENT_PAYLOAD_PATH=str(self.payload),
                        CLIENT_NAME="ablecube2", SESSION_TIMESTAMP="1", BACKUP_ID="ablecube2_1",
                        NETBACKUP_JOB_ID="433", BACKUP_TIME="1", UNIX_BACKUP_TIME="1",
                        CONTEXT_FILE=str(self.root / "context"), RUNTIME_FILE=str(self.root / "runtime"))
        # macOS does not ship GNU timeout. This fixture runs the validator directly;
        # argument/deadline selection is asserted independently below.
        bindir = self.root / "bin"
        bindir.mkdir()
        timeout = bindir / "timeout"
        timeout.write_text('#!/bin/bash\nshift 3\nexec "$@"\n')
        timeout.chmod(0o755)
        self.env['PATH'] = str(bindir) + os.pathsep + os.environ['PATH']

    def run_hook(self, **environment):
        return subprocess.run(["bash", str(self.root / "pre.sh")], env=dict(self.env, **environment),
                              text=True, capture_output=True, timeout=10)

    def test_missing_mount_rejects_even_when_no_vm_is_due_and_clears_context(self):
        for _ in range(3):
            result = self.run_hook(TEST_UNAVAILABLE="true")
            self.assertNotEqual(0, result.returncode, result.stderr)
            self.assertFalse((self.root / "inprogress").exists())
        self.assertFalse(self.payload.exists())
        self.assertNotIn("VM_QUERY", self.events.read_text())
        self.assertIn("MOLD_BACKUP_DEFERRED_STORAGE", self.events.read_text())
        self.assertIn("no Mold backup was requested", self.events.read_text())

    def test_storage_recovery_recreates_only_the_verified_bootstrap_directory(self):
        self.assertNotEqual(0, self.run_hook(TEST_UNAVAILABLE="true").returncode)
        result = self.run_hook()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.payload.is_dir())
        self.assertEqual([], list(self.payload.iterdir()))
        self.assertFalse((self.root / "inprogress").exists())
        self.assertIn("MOLD_BACKUP_SKIPPED_NO_TARGET", self.events.read_text())
        arguments = json.loads((self.root / "validation.json").read_text())
        self.assertEqual(["NFS", str(self.root / "staging"), str(self.root / "staging"),
                          "192.168.1.161:/backup", "", str(self.payload), "15", "0"], arguments)

    def test_old_parent_selection_is_rejected_before_mounting_or_api_dispatch(self):
        result = self.run_hook(NETBACKUP_PARENT_PAYLOAD_PATH="/old-root/bootstrap/ablecube2")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "validation.json").exists())
        self.assertFalse((self.root / "inprogress").exists())

    def test_earlier_configured_mount_timeout_is_retained(self):
        result = self.run_hook(TEST_MOUNT_TIMEOUT="5")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("5", json.loads((self.root / "validation.json").read_text())[6])

    def test_storage_type_is_normalized_like_the_management_configuration(self):
        result = self.run_hook(TEST_STORAGE_TYPE=" nfs ")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("NFS", json.loads((self.root / "validation.json").read_text())[0])

    def test_loading_root_does_not_touch_a_stale_filesystem_before_the_bounded_probe(self):
        script = r'''source "$TEST_ROOT/netbackup-host-common.sh"
invoke_mold_api() {
  case "$5" in
    backup.thirdparty.staging.enable) echo '{"value":"true"}';;
    backup.thirdparty.staging.root.path) echo '{"value":"/staging/one/../jobs"}';;
  esac
}
realpath() { echo UNBOUNDED_PATH_PROBE >> "$TEST_ROOT/events"; return 99; }
load_backup_staging_root_from_mold
printf '%s' "$BACKUP_STAGING_ROOT"
'''
        result = subprocess.run(["bash", "-c", script], env=self.env, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("/staging/jobs/ablestack-netbackup", result.stdout)
        self.assertNotIn("UNBOUNDED_PATH_PROBE", self.events.read_text())


if __name__ == "__main__":
    unittest.main()
