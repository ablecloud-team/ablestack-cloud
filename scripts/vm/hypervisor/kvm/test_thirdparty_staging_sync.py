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

"""Path-only Host updates preserve selection, credentials and unrelated configuration."""
import copy
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("staging_sync", Path(__file__).with_name("thirdparty_staging_sync.py"))
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)


class StagingSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.net = self.root / "netbackup"
        self.veeam = self.root / "veeam"
        self.net.mkdir()
        self.veeam.mkdir()
        self.worker = sync.Synchronizer(self.net, self.veeam)
        self.request = dict(action="PLAN", hostName="ablecube4", providers=["ablestack-netbackup", "ablestack-veeam"],
                            configuration=dict(version=1, type="LOCAL", localMode="DIRECTORY", rootPath="/new-stage", mountPath="/new-stage"))
        self.prepare = patch.object(sync, "prepare_payload").start()
        self.idle = patch.object(sync, "require_watcher_idle").start()
        self.addCleanup(patch.stopall)

    def netbackup(self, root="/old-stage"):
        path = self.net / "netbackup-host-daily_policy.conf"
        path.write_text('# keep this comment\nNETBACKUP_POLICY_NAME="daily policy"\nKVM_HOSTNAME=ablecube4\n'
                        'VM_INCLUDE="i-2-335-VM,i-2-336-VM"\nVM_SCHEDULE_BACKUP=false\nADMIN_APIKEY="unchanged"\n'
                        'SECRET_KEY_FILE=/root/.ssh/ablestack.key\nNETBACKUP_PARENT_PAYLOAD_PATH=' + root + '/ablestack-netbackup/bootstrap/daily_policy\n')
        path.chmod(0o600)
        return path

    def veeam_job(self, mode="COMMON", root="/old-stage"):
        path = self.veeam / "nightly_job.conf"
        path.write_text('VEEAM_JOB_NAME="nightly job"\nKVM_HOSTNAME=ablecube4\nVM_INCLUDE="i-2-336-VM"\n'
                        'MOLD_API_SECRET_ENC_FILE=/etc/ablestack/veeam/secrets/original.enc\n'
                        'VEEAM_AGENT_PAYLOAD_MODE=' + mode + '\nVEEAM_AGENT_PAYLOAD_PATH=' + root + '/ablestack-veeam/bootstrap/ablecube4/nightly_job\n')
        path.chmod(0o600)
        return path

    def apply(self, plan):
        request = copy.deepcopy(self.request)
        request.update(action="APPLY", fingerprint=plan["fingerprint"])
        return self.worker.run(request)

    def test_different_paths_are_changed_without_touching_vm_filters_or_secrets(self):
        net = self.netbackup()
        vee = self.veeam_job()
        original = [net.read_text(), vee.read_text()]
        plan = self.worker.run(self.request)
        self.assertTrue(plan["changed"])
        self.assertEqual(["daily policy", "nightly job"], [row["name"] for row in plan["bindings"]])
        self.assertNotIn("ADMIN_APIKEY", str(plan))
        verified = self.apply(plan)
        self.assertFalse(verified["changed"])
        for path, old in zip([net, vee], original):
            self.assertEqual(old.replace("/old-stage", "/new-stage"), path.read_text())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.assertEqual(2, self.prepare.call_count)

    def test_matching_paths_do_not_rewrite_files_prepare_directories_or_wait_for_services(self):
        path = self.netbackup("/new-stage")
        before = path.stat().st_mtime_ns
        plan = self.worker.run(self.request)
        self.assertFalse(plan["changed"])
        self.apply(plan)
        self.assertEqual(before, path.stat().st_mtime_ns)
        self.prepare.assert_not_called()
        self.idle.assert_not_called()
        self.assertFalse(list(self.net.glob("*.bak")))

    def test_retry_is_idempotent_and_updates_only_remaining_differences(self):
        net = self.netbackup()
        vee = self.veeam_job()
        self.apply(self.worker.run(self.request))
        original = net.stat().st_mtime_ns
        vee.write_text(vee.read_text().replace("/new-stage", "/old-stage"))
        self.prepare.reset_mock()
        self.apply(self.worker.run(self.request))
        self.assertEqual(original, net.stat().st_mtime_ns)
        self.assertEqual(1, self.prepare.call_count)

    def test_explicit_veeam_payload_is_preserved(self):
        path = self.veeam_job("EXPLICIT")
        before = path.read_bytes()
        plan = self.worker.run(self.request)
        self.assertFalse(plan["changed"])
        self.assertFalse(plan["bindings"][0]["managed"])
        self.apply(plan)
        self.assertEqual(before, path.read_bytes())
        self.prepare.assert_not_called()

    def test_concurrent_vm_filter_edit_is_not_overwritten(self):
        path = self.netbackup()
        plan = self.worker.run(self.request)
        path.write_text(path.read_text().replace("i-2-335-VM", "i-2-999-VM"))
        with self.assertRaisesRegex(ValueError, "changed during synchronization"):
            self.apply(plan)
        self.assertIn("i-2-999-VM", path.read_text())
        self.assertIn("/old-stage", path.read_text())

    def test_restore_and_watcher_paths_are_updated_without_replacing_other_settings(self):
        self.netbackup()
        for filename in ("restore.conf", "restore-watcher.conf"):
            path = self.net / filename
            path.write_text('NETBACKUP_STAGING_ROOT=/old-stage/ablestack-netbackup\nSTATE_FILE=/kept/state.json\nCUSTOM_VALUE="unchanged"\n')
        self.apply(self.worker.run(self.request))
        for filename in ("restore.conf", "restore-watcher.conf"):
            content = (self.net / filename).read_text()
            self.assertIn("NETBACKUP_STAGING_ROOT=/new-stage/ablestack-netbackup", content)
            self.assertIn("STATE_FILE=/kept/state.json", content)
            self.assertIn('CUSTOM_VALUE="unchanged"', content)

    def test_symlinked_configuration_is_rejected(self):
        path = self.netbackup()
        target = self.root / "target"
        path.rename(target)
        path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "redirected"):
            self.worker.run(self.request)

    def test_wrong_host_cannot_be_synchronized(self):
        path = self.netbackup()
        path.write_text(path.read_text().replace("KVM_HOSTNAME=ablecube4", "KVM_HOSTNAME=another-host"))
        with self.assertRaisesRegex(ValueError, "Host differs"):
            self.worker.run(self.request)

    def test_missing_exact_policy_name_is_marked_for_external_inventory_resolution(self):
        path = self.netbackup()
        path.write_text(path.read_text().replace('NETBACKUP_POLICY_NAME="daily policy"\n', ''))
        binding = self.worker.run(self.request)["bindings"][0]
        self.assertEqual("daily_policy", binding["name"])
        self.assertFalse(binding["nameIsExact"])

    def test_no_configuration_is_a_first_install_noop(self):
        plan = self.worker.run(self.request)
        self.assertFalse(plan["changed"])
        self.assertEqual([], plan["bindings"])

    def test_config_is_not_sourced_and_dynamic_path_is_rejected(self):
        path = self.netbackup()
        path.write_text(path.read_text() + 'NETBACKUP_PARENT_PAYLOAD_PATH="$(touch /never-run)"\n')
        with self.assertRaisesRegex(ValueError, "Dynamic"):
            self.worker.run(self.request)

    def test_failed_filesystem_validation_preserves_the_original_files(self):
        path = self.netbackup()
        before = path.read_bytes()
        self.prepare.side_effect = ValueError("filesystem unavailable")
        with self.assertRaisesRegex(ValueError, "filesystem unavailable"):
            self.apply(self.worker.run(self.request))
        self.assertEqual(before, path.read_bytes())


class WatcherIdleTest(unittest.TestCase):
    def test_oneshot_starting_or_stopping_watcher_is_waited_for(self):
        results = [SimpleNamespace(returncode=0, stdout=state) for state in ("activating", "deactivating", "inactive")]
        with patch.object(sync.subprocess, "run", side_effect=results) as query, patch.object(sync.time, "sleep") as pause:
            sync.require_watcher_idle("ablestack-netbackup")
        self.assertEqual(3, query.call_count)
        self.assertEqual(2, pause.call_count)

    def test_unknown_watcher_state_does_not_authorize_path_changes(self):
        with patch.object(sync.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout="")):
            with self.assertRaisesRegex(ValueError, "state is unconfirmed"):
                sync.require_watcher_idle("ablestack-veeam")


if __name__ == "__main__":
    unittest.main()
