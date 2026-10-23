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

"""Veeam backup mode provisioning and runtime configuration ownership regressions."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


VEEAM = Path(__file__).with_name("veeam")


class VeeamBackupModeTest(unittest.TestCase):
    def configure(self, mode, chain="", global_value="true", cluster_value="true", host="host-test", fail_update=False):
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
ZONE_ID=zone-test
KVM_HOST="$TEST_HOST"
mold_backup_notify_log() { :; }
mold_backup_api_list_config_value() {
  echo "READ global $1" >&2
  if [[ "$1" == kvm.incremental.backup ]]; then echo "$TEST_GLOBAL"; else echo 25; fi
}
mold_backup_api_list_cluster_config_value() {
  echo "READ cluster $1 $2" >&2
  echo "$TEST_CLUSTER"
}
mold_backup_cmk_run() {
  if [[ "$1" == listHosts ]]; then
    echo "$TEST_HOSTS_JSON"
  else
    echo "$*" >&2
    [[ "$TEST_FAIL_UPDATE" == False ]]
  fi
}
mold_backup_api_configure_backup_mode "$TEST_MODE" "$TEST_CHAIN"
echo "RESTART=$MOLD_BACKUP_MODE_RESTART_REQUIRED"
'''
        hosts = [{"name": "other-host", "ipaddress": "192.0.2.2", "clusterid": "other-cluster"},
                 {"name": "host-test", "ipaddress": "192.0.2.1", "clusterid": "target-cluster"}]
        env = dict(os.environ, TEST_LIBRARY=str(VEEAM / "mold-backup.lib.sh"), TEST_HOST=host,
                   TEST_MODE=mode, TEST_CHAIN=chain, TEST_GLOBAL=global_value, TEST_CLUSTER=cluster_value,
                   TEST_FAIL_UPDATE=str(fail_update), TEST_HOSTS_JSON=json.dumps({"listhostsresponse": {"host": hosts}}))
        return subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env, timeout=10)

    def test_full_mode_disables_global_and_target_cluster_without_touching_the_chain(self):
        result = self.configure("false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("updateConfiguration name=kvm.incremental.backup value=false\n", result.stderr)
        self.assertIn("updateConfiguration name=kvm.incremental.backup value=false clusterid=target-cluster", result.stderr)
        self.assertIn("RESTART=true", result.stdout)
        self.assertNotIn("kvm.backup.chain.size", result.stdout + result.stderr)
        self.assertNotIn("other-cluster", result.stderr)

    def test_incremental_mode_enables_global_and_cluster_and_sets_the_kvm_chain_key(self):
        result = self.configure("true", chain="8", global_value="false", cluster_value="false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("updateConfiguration name=kvm.incremental.backup value=true\n", result.stderr)
        self.assertIn("value=true clusterid=target-cluster", result.stderr)
        self.assertIn("updateConfiguration name=kvm.backup.chain.size value=8", result.stderr)
        self.assertIn("RESTART=true", result.stdout)

    def test_full_mode_is_idempotent(self):
        result = self.configure("false", global_value="false", cluster_value="false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("updateConfiguration", result.stderr)
        self.assertIn("RESTART=false", result.stdout)

    def test_incremental_mode_without_chain_option_preserves_existing_global_chain(self):
        result = self.configure("true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("kvm.backup.chain.size", result.stdout + result.stderr)

    def test_only_cluster_override_is_changed_when_global_already_matches(self):
        result = self.configure("false", global_value="false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, result.stderr.count("updateConfiguration"))
        self.assertIn("value=false clusterid=target-cluster", result.stderr)

    def test_host_ip_resolves_the_same_target_cluster(self):
        result = self.configure("false", host="192.0.2.1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("clusterid=target-cluster", result.stderr)

    def test_unknown_host_aborts_before_global_configuration_changes(self):
        result = self.configure("false", host="unknown-host")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("updateConfiguration", result.stderr)

    def test_api_update_failure_is_propagated(self):
        result = self.configure("false", fail_update=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("RESTART=", result.stdout)

    def test_runtime_environment_setup_does_not_reset_backup_mode_or_chain(self):
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
KVM_INCREMENTAL_BACKUP=true
VEEAM_MAX_CHAIN=8
BACKUP_CHAIN_SIZE=8
ZONE_ID=zone-test
mold_backup_api_update_config_if_needed() { echo "$*"; }
mold_backup_api_append_provider_plugin() { :; }
mold_backup_cmk_run() { echo "unexpected API call"; return 1; }
mold_backup_api_ensure_global_settings
'''
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                env=dict(os.environ, TEST_LIBRARY=str(VEEAM / "mold-backup.lib.sh")))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("kvm.", result.stdout)
        self.assertNotIn("unexpected API call", result.stdout)

    def test_both_configuration_tools_reject_invalid_modes_and_full_chain_options_before_writes(self):
        cases = [("--incremental-backup", "invalid"),
                 ("--incremental-backup", "false", "--backup-chain-size", "8"),
                 ("--incremental-backup", "true", "--backup-chain-size", "0")]
        for options in cases:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                config_dir = Path(directory) / "not-created"
                result = subprocess.run([sys.executable, str(VEEAM.parent / "veeam-host-config.py"), *options],
                                        capture_output=True, text=True, timeout=10,
                                        env=dict(os.environ, ABLESTACK_VEEAM_ETC_DIR=str(config_dir)))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("--incremental-backup" if options[1] != "true" else "--backup-chain-size", result.stderr)
                self.assertFalse(config_dir.exists())



if __name__ == "__main__":
    unittest.main()
