#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
import uuid
import netbackup_hook_timeout as sync


class NetBackupHookTimeoutSyncTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.conf = self.root / 'bp.conf'
        self.original = b'SERVER = primary\nCLIENT_NAME = host\nBPSTART_TIMEOUT = 86400\nBPEND_TIMEOUT = 86400\nCLIENT_READ_TIMEOUT = 86400\nCLIENT_CONNECT_TIMEOUT = 1800\n'
        self.conf.write_bytes(self.original)
        self.conf.chmod(0o640)
        self.context = SimpleNamespace(ROOT=self.root, LOCK=self.root / 'configuration.lock',
            status=Mock(return_value={'state': 'COMPLETED', 'configured': True, 'policyinventorycomplete': True}),
            no_external_jobs=Mock(), setup=SimpleNamespace(NETBACKUP_BP_CONF_PATH=self.conf, restart_netbackup_service=Mock()))
        self.request = dict(operationid=str(uuid.uuid4()), previous=86400, timeout=3600,
            hostname='host', hostip='192.0.2.1', netbackupurl='https://primary/netbackup', netbackupapikey='synthetic-private-key')

    def execute(self, action, **changes):
        return sync.execute(dict(self.request, action='HOOK_TIMEOUT_' + action, **changes), self.context)

    def apply(self):
        self.request['fingerprint'] = self.execute('CHECK')['fingerprint']
        return self.execute('APPLY')

    def test_applies_exactly_three_keys_and_preserves_other_content_and_permissions(self):
        self.assertEqual('APPLIED', self.apply()['state'])
        value = self.conf.read_text()
        self.assertIn('SERVER = primary\nCLIENT_NAME = host\n', value)
        self.assertIn('CLIENT_CONNECT_TIMEOUT = 1800\n', value)
        for key in sync.KEYS:
            self.assertIn(key + ' = 3600\n', value)
        self.assertEqual(0o640, self.conf.stat().st_mode & 0o777)
        self.context.setup.restart_netbackup_service.assert_called_once()
        self.assertNotIn('synthetic-private-key', sync.pending(self.root)[0].read_text())
        sync.ready_for_hook(self.root, 3600, self.conf)
        with self.assertRaises(RuntimeError):
            sync.ready_for_hook(self.root, 86400, self.conf)
        self.assertEqual('COMMITTED', self.execute('COMMIT')['state'])
        self.assertFalse(sync.pending(self.root))

    def test_client_installation_without_completed_host_setup_is_skipped(self):
        self.context.status.return_value.update(configured=False, state='NOT_CONFIGURED')
        self.assertEqual('SKIPPED', self.execute('CHECK')['state'])
        self.context.no_external_jobs.assert_not_called()
        self.context.setup.restart_netbackup_service.assert_not_called()
        self.assertEqual(self.original, self.conf.read_bytes())

    def test_missing_bp_conf_is_not_created(self):
        self.conf.unlink()
        self.assertEqual('SKIPPED', self.execute('CHECK')['state'])
        self.assertFalse(self.conf.exists())
        self.context.setup.restart_netbackup_service.assert_not_called()

    def test_running_or_failed_configuration_cannot_change_files(self):
        for state in ('RUNNING', 'FAILED', 'UNCONFIRMED'):
            self.context.status.return_value['state'] = state
            with self.subTest(state=state), self.assertRaises(ValueError):
                self.execute('CHECK')
        self.assertEqual(self.original, self.conf.read_bytes())

    def test_active_native_client_jobs_stop_before_writing(self):
        self.context.no_external_jobs.side_effect = ValueError('Client busy')
        with self.assertRaisesRegex(ValueError, 'Client busy'):
            self.apply()
        self.assertEqual(self.original, self.conf.read_bytes())
        self.assertFalse(sync.pending(self.root))

    def test_file_drift_after_check_is_preserved(self):
        self.request['fingerprint'] = self.execute('CHECK')['fingerprint']
        manual = self.original + b'OTHER = manually-changed\n'
        self.conf.write_bytes(manual)
        with self.assertRaisesRegex(ValueError, 'changed since preflight'):
            self.execute('APPLY')
        self.assertEqual(manual, self.conf.read_bytes())
        self.context.setup.restart_netbackup_service.assert_not_called()

    def test_repeated_apply_is_idempotent_and_a_lost_response_can_be_rolled_back(self):
        self.apply()
        self.assertEqual('APPLIED', self.execute('APPLY')['state'])
        self.context.setup.restart_netbackup_service.assert_called_once()
        self.assertEqual('ROLLED_BACK', self.execute('ROLLBACK')['state'])
        self.assertEqual(self.original, self.conf.read_bytes())
        self.assertFalse(sync.pending(self.root))

    def test_restart_failure_keeps_a_blocking_journal_and_restores_on_retry(self):
        self.context.setup.restart_netbackup_service.side_effect = [RuntimeError('restart unconfirmed'), None]
        with self.assertRaisesRegex(RuntimeError, 'restart unconfirmed'):
            self.apply()
        with self.assertRaises(RuntimeError):
            sync.ready_for_hook(self.root, 3600, self.conf)
        self.execute('RECOVER')
        self.assertEqual(self.original, self.conf.read_bytes())
        self.assertFalse(sync.pending(self.root))

    def test_recovery_uses_current_global_to_commit_or_restore_an_interrupted_update(self):
        self.apply()
        self.execute('RECOVER', previous=3600)
        self.assertIn('BPSTART_TIMEOUT = 3600\n', self.conf.read_text())
        self.assertFalse(sync.pending(self.root))
        self.context.setup.restart_netbackup_service.assert_called_once()

    def test_rollback_failure_keeps_proof_and_blocks_old_global_hooks_until_recovered(self):
        self.apply()
        self.context.no_external_jobs.side_effect = ValueError('Client became busy')
        with self.assertRaises(ValueError):
            self.execute('ROLLBACK')
        self.assertTrue(sync.pending(self.root))
        with self.assertRaises(RuntimeError):
            sync.ready_for_hook(self.root, 86400, self.conf)
        self.context.no_external_jobs.side_effect = None
        self.execute('RECOVER')
        self.assertEqual(self.original, self.conf.read_bytes())

    def test_recovery_never_overwrites_unrelated_edits_or_an_unexpected_global(self):
        self.apply()
        with self.assertRaisesRegex(ValueError, 'Global value'):
            self.execute('RECOVER', previous=7200)
        manual = self.conf.read_bytes() + b'OTHER = manual\n'
        self.conf.write_bytes(manual)
        with self.assertRaisesRegex(ValueError, 'no longer matches'):
            self.execute('ROLLBACK')
        self.assertEqual(manual, self.conf.read_bytes())
        self.assertTrue(sync.pending(self.root))

    def test_symlink_is_not_followed(self):
        other = self.root / 'unrelated'
        self.conf.rename(other)
        self.conf.symlink_to(other)
        with self.assertRaises(ValueError):
            self.execute('CHECK')
        self.assertEqual(self.original, other.read_bytes())

    def test_unchanged_file_does_not_restart_the_client(self):
        self.apply()
        self.execute('COMMIT')
        self.context.setup.restart_netbackup_service.reset_mock()
        self.request['operationid'] = str(uuid.uuid4())
        self.assertEqual('APPLIED', self.apply()['state'])
        self.context.setup.restart_netbackup_service.assert_not_called()
        self.assertFalse(sync.pending(self.root))

    def test_render_consolidates_duplicate_timeout_entries(self):
        updated = sync.render(self.original + b'BPSTART_TIMEOUT = 300\n', 7200)
        self.assertEqual(1, updated.count(b'BPSTART_TIMEOUT = '))
        self.assertIn(b'BPSTART_TIMEOUT = 7200\n', updated)


if __name__ == '__main__':
    unittest.main()
