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

"""Exercise VBR upload wrappers and common restore/post-hook boundaries."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

LIBRARY = Path(__file__).with_name("veeam") / "mold-backup.lib.sh"


class VeeamVolumeHooksTest(unittest.TestCase):
    def run_hook(self, script, **settings):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, TEST_LIBRARY=str(LIBRARY), TEST_ROOT=directory, **settings)
            return subprocess.run(["bash", "-c", 'set -euo pipefail\nsource "$TEST_LIBRARY"\n' + script],
                                  env=env, text=True, capture_output=True, timeout=10)

    def test_uploaded_script_executes_local_job_hook_without_custom_arguments(self):
        result = self.run_hook(r'''
hook="$TEST_ROOT/job hook with spaces"
printf '#!/bin/bash\nprintf "local-job-host=%%s\\n" "$1"\n' > "$hook"
chmod +x "$hook"
mold_backup_write_agent_hook_wrapper "$TEST_ROOT/upload.sh" "$hook"
bash "$TEST_ROOT/upload.sh"
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("local-job-host=", result.stdout)
        self.assertNotIn("local-job-host=\n", result.stdout)

    def test_provider_registration_preserves_global_and_zone_plugins(self):
        result = self.run_hook(r'''
ZONE_ID=zone-test
mold_backup_api_list_config_value() { echo ablestack-nas; }
mold_backup_notify_log() { :; }
mold_backup_cmk_run() {
  if [[ "$1" == listConfigurations ]]; then
    echo '{"listconfigurationsresponse":{"configuration":[{"value":"ablestack-netbackup"}]}}'
  else
    echo "$*" >> "$TEST_ROOT/config-updates"
  fi
}
mold_backup_api_append_provider_plugin
cat "$TEST_ROOT/config-updates"
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("value=ablestack-nas,ablestack-veeam", result.stdout)
        self.assertIn("value=ablestack-netbackup,ablestack-veeam zoneid=zone-test", result.stdout)
        self.assertNotIn("value=veeam", result.stdout)

    def test_failed_provider_lookup_never_replaces_existing_plugin_list(self):
        result = self.run_hook(r'''
mold_backup_api_list_config_value() { return 1; }
mold_backup_cmk_run() { echo UNEXPECTED_UPDATE >&2; }
mold_backup_api_append_provider_plugin
''')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("UNEXPECTED_UPDATE", result.stderr)

    def restore(self, stop_ok="true", api_ok="true"):
        return self.run_hook(r'''
BACKUP_ID=backup-test VM_NAME=i-2-1-VM
mold_backup_notify_log() { :; }
mold_backup_api_ensure_vm_stopped_for_restore() { echo STOP; [[ "$TEST_STOP_OK" == true ]]; }
mold_backup_cmk_run() {
  echo "API:$*" >> "$TEST_ROOT/api"
  [[ "$TEST_API_OK" == true ]] || return 1
  echo '{"restorebackupresponse":{"jobid":"async-test"}}'
}
trap '[[ ! -f "$TEST_ROOT/api" ]] || cat "$TEST_ROOT/api" >&2' EXIT
MOLD_RESTORE_ASYNC_DETACH=true
mold_backup_api_restore
echo "SUBMITTED:$MOLD_RESTORE_ASYNC_SUBMITTED:$MOLD_RESTORE_ASYNC_JOB_ID"
''', TEST_STOP_OK=stop_ok, TEST_API_OK=api_ok)

    def test_manual_restore_uses_common_api_and_preserves_async_tracking(self):
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("API:restoreBackup id=backup-test", result.stderr)
        self.assertIn("SUBMITTED:true:async-test", result.stdout)
        self.assertEqual(1, result.stderr.count("API:"))

    def test_failed_stop_does_not_submit_restore(self):
        result = self.restore(stop_ok="false")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("API:", result.stderr)

    def test_failed_common_api_does_not_retry_another_restore_api(self):
        result = self.restore(api_ok="false")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, result.stderr.count("API:"))
        self.assertNotIn("SUBMITTED:", result.stdout)

    def test_parent_post_hook_preserves_staging_without_importing_or_stamping_parent_rp(self):
        result = self.run_hook(r'''
state="$TEST_ROOT/parent.state"
echo 'vm=i-2-1-VM backup_id=backup-test status=pipeline' > "$state"
echo preserved > "$TEST_ROOT/volume.qcow2"
mold_backup_load_config() { :; }
mold_backup_notify_log() { :; }
mold_backup_latest_state_file() { echo "$TEST_ROOT/parent.state"; }
mold_backup_trigger_clear() { echo "CLEAR:$*"; }
mold_backup_trigger_mark() { echo "MARK:$*"; }
mold_backup_cmk_run() { echo UNEXPECTED_API >&2; return 1; }
mold_backup_post_notify host-test parent-policy default
test -f "$TEST_ROOT/volume.qcow2"
test ! -e "$state"
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("UNEXPECTED_API", result.stderr)
        self.assertIn("CLEAR:veeam-active i-2-1-VM", result.stdout)

    def test_stale_common_parent_path_fails_before_requesting_a_backup(self):
        result = self.run_hook(r'''
mold_backup_load_config() { :; }
mold_backup_notify_log() { echo "$*" >&2; }
mold_backup_api_external_candidates() { echo UNEXPECTED_API >&2; return 1; }
VEEAM_AGENT_PAYLOAD_MODE=COMMON
VEEAM_HOST_BACKUP_PATH="$TEST_ROOT/new-stage/ablestack-veeam"
VEEAM_AGENT_PAYLOAD_PATH="$TEST_ROOT/old-stage/bootstrap"
KVM_HOSTNAME=host-test
mold_backup_pre_notify host-test parent-policy default
''')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Rerun veeam-host-config.py", result.stderr)
        self.assertNotIn("UNEXPECTED_API", result.stderr)

    def test_parent_post_hook_removes_only_its_owned_marker_copy(self):
        result = self.run_hook(r'''
TEST_ROOT="$(cd "$TEST_ROOT" && pwd -P)"
state="$TEST_ROOT/parent.state"
echo 'vm=i-2-1-VM backup_id=backup-one status=pipeline' > "$state"
mkdir -p "$TEST_ROOT/source"
echo backup-one > "$TEST_ROOT/source/.volume-bootstrap"
echo preserved > "$TEST_ROOT/source/volume.qcow2"
VEEAM_AGENT_PAYLOAD_PATH="$TEST_ROOT/parent-markers"
python3 "$(mold_backup_bootstrap_helper)" publish --payload-root "$VEEAM_AGENT_PAYLOAD_PATH" --source-marker "$TEST_ROOT/source/.volume-bootstrap"
mold_backup_load_config() { :; }
mold_backup_notify_log() { :; }
mold_backup_latest_state_file() { echo "$TEST_ROOT/parent.state"; }
mold_backup_trigger_clear() { :; }
mold_backup_trigger_mark() { :; }
mold_backup_post_notify host-test parent-policy default
test ! -e "$VEEAM_AGENT_PAYLOAD_PATH/backup-one.json"
test -f "$TEST_ROOT/source/.volume-bootstrap"
test -f "$TEST_ROOT/source/volume.qcow2"
''')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_another_hosts_parent_path_is_rejected_before_requesting_a_backup(self):
        result = self.run_hook(r'''
mold_backup_load_config() { :; }
mold_backup_notify_log() { echo "$*" >&2; }
mold_backup_api_external_candidates() { echo UNEXPECTED_API >&2; return 1; }
VEEAM_AGENT_PAYLOAD_MODE=COMMON
VEEAM_HOST_BACKUP_PATH="$TEST_ROOT/stage/ablestack-veeam"
VEEAM_AGENT_PAYLOAD_PATH="$VEEAM_HOST_BACKUP_PATH/bootstrap/another-host/parent-policy"
KVM_HOSTNAME=host-test
mold_backup_pre_notify host-test parent-policy default
''')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Rerun veeam-host-config.py", result.stderr)
        self.assertIn("bootstrap/host-test/parent-policy before backing up", result.stderr)
        self.assertNotIn("UNEXPECTED_API", result.stderr)

    def test_common_parent_path_matches_provisioner_for_unicode_job_names(self):
        result = self.run_hook(r'''
mold_backup_load_config() { :; }
mold_backup_notify_log() { echo "$*" >&2; }
mold_backup_api_external_candidates() { echo VALIDATED_CONFIG >&2; return 1; }
VEEAM_AGENT_PAYLOAD_MODE=COMMON
VEEAM_HOST_BACKUP_PATH="$TEST_ROOT/stage/ablestack-veeam"
VEEAM_AGENT_PAYLOAD_PATH="$VEEAM_HOST_BACKUP_PATH/bootstrap/host-test/___Job"
KVM_HOSTNAME=host-test
mold_backup_pre_notify host-test '백업 Job' default
''')
        self.assertIn("VALIDATED_CONFIG", result.stderr)
        self.assertNotIn("Staging changed", result.stderr)


if __name__ == "__main__":
    unittest.main()
