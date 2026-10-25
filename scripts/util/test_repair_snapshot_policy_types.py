# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with this
# work for additional information regarding copyright ownership. The ASF
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations.
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('repair', Path(__file__).with_name('repair-snapshot-policy-types.py'))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = dict(id=100, uuid='snapshot', volume_id=10, account_id=20, domain_id=30,
                             snapshot_type=8, type_description='INTERNAL', status='BackedUp')
        self.volume = dict(id=10, uuid='volume', account_id=20, domain_id=30)
        self.policy = dict(id=8, uuid='policy', volume_id=10, account_id=20, domain_id=30, interval=0)
        self.job = dict(id=50, uuid='job', command=repair.COMMAND, status=1, instance_type='Snapshot', instance_id=100,
                        account_id=2, parameters=dict(id='100', volumeid='volume', policyid='policy', ctxAccountId='2'))

    def assess(self, policies=None, jobs=None, details=None, archives=None):
        return repair.assess(self.snapshot, self.volume, [self.policy] if policies is None else policies,
                             [self.job] if jobs is None else jobs, details or {}, archives or [])

    def test_all_four_intervals_for_live_policy_eight(self):
        for interval in range(4):
            self.policy['interval'] = interval
            result = self.assess()
            self.assertEqual('repair', result['action'])
            self.assertEqual(interval + 3, result['target_type'])

    def test_scheduler_numeric_ids_are_supported(self):
        self.job['parameters'].update(volumeid='10', policyid='8')
        self.assertEqual('repair', self.assess()['action'])

    def test_deleted_policy_requires_archival_proof(self):
        self.assertEqual('skip', self.assess(policies=[])['action'])
        archived = {**self.policy, 'volume_uuid': 'volume', 'evidence_sha256': 'a' * 64}
        self.assertTrue(self.assess(policies=[], archives=[archived])['archived'])

    def test_deleted_numeric_policy_requires_exact_creation_job(self):
        self.job['parameters']['policyid'] = '8'
        archived = {**self.policy, 'volume_uuid': 'volume', 'evidence_sha256': 'a' * 64}
        self.assertEqual('skip', self.assess(policies=[], archives=[archived])['action'])
        archived['creation_jobs'] = ['job']
        self.assertEqual('repair', self.assess(policies=[], archives=[archived])['action'])

    def test_explicit_internal_and_manual_are_never_reclassified(self):
        for intent in ['INTERNAL', 'MANUAL']:
            self.assertEqual('skip', self.assess(details={'snapshot.creation.intent': intent})['action'])

    def test_owner_and_volume_mismatch_are_rejected(self):
        for key in ['account_id', 'domain_id', 'volume_id']:
            other = {**self.policy, key: 999}
            self.assertEqual('skip', self.assess(policies=[other])['action'])
        self.volume['account_id'] = 999
        self.assertEqual('skip', self.assess()['action'])

    def test_job_identity_status_and_parameters_are_required(self):
        for key, value in [('instance_id', 999), ('instance_type', 'Volume'), ('status', 2), ('command', 'other')]:
            self.assertEqual('skip', self.assess(jobs=[{**self.job, key: value}])['action'])
        for key in ['id', 'volumeid', 'policyid', 'ctxAccountId']:
            job = copy.deepcopy(self.job)
            job['parameters'][key] = '999'
            self.assertEqual('skip', self.assess(jobs=[job])['action'])

    def test_conflicting_evidence_is_not_guessed(self):
        other = {**self.policy, 'interval': 1}
        self.assertEqual('ambiguous-policy-evidence', self.assess(policies=[self.policy, other])['reason'])

    def test_inflight_is_skipped_destroyed_history_does_not_resurrect(self):
        self.snapshot['status'] = 'Creating'
        self.assertEqual('skip', self.assess()['action'])
        self.snapshot['status'] = 'Destroyed'
        result = self.assess()
        self.assertEqual('repair', result['action'])
        self.assertEqual('Destroyed', result['snapshot']['status'])

    def test_provenance_conflict_is_skipped(self):
        self.assertEqual('skip', self.assess(details={'snapshot.policy.interval': 'DAILY'})['action'])
        self.assertEqual('skip', self.assess(details={'snapshot.policy.uuid': 'different'})['action'])

    def test_apply_requires_reviewed_hash(self):
        connection = MagicMock()
        with self.assertRaises(ValueError):
            repair.apply(connection, {'version': 1}, 'wrong')
        connection.begin.assert_not_called()

    def test_apply_drift_rolls_back_without_any_update(self):
        connection = MagicMock()
        entry = self.assess()
        plan = {'version': 1, 'archive_evidence': [], 'entries': [entry]}
        changed = {**self.snapshot, 'status': 'Creating'}
        with patch.object(repair, 'fetch', return_value=(changed, self.volume, [self.policy], [self.job], {})):
            with self.assertRaisesRegex(ValueError, 'Evidence changed'):
                repair.apply(connection, plan, repair.digest(plan))
        connection.rollback.assert_called_once()
        connection.commit.assert_not_called()
        connection.cursor.return_value.__enter__.return_value.execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
