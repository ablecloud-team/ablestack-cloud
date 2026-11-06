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

"""Client-local restores, destination identity and durable submission recovery."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('netbackup_volume_restore', Path(__file__).with_name('netbackup-volume-restore.py'))
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class NetBackupVolumeRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.id = 'restore-adc49c78-f92c-4339-9f6c-6a792ae34904'
        self.file = self.root / 'netbackup-restore-0.json'
        self.plan = dict(server='netbackup', destinationClient='ablecube1', request=dict(
            jobId=self.id, sequence=0, destination=str(self.root / 'restored volume.qcow2'),
            artifact=dict(sourceHost='ablecube4', path='/mnt/glue-gfs/backup/root.disk.qcow2',
                          externalId='ablecube4_1792958521', backupTime='2026-10-25T20:02:01.000Z')))
        self.file.write_text(json.dumps(self.plan))
        self.executable = self.root / 'bprestore'
        self.executable.touch(mode=0o700)
        self.client_command = self.root / 'bpclntcmd'
        self.client_command.touch(mode=0o700)
        self.addCleanup(patch.stopall)
        patch.object(restore, 'BPRESTORE', self.executable).start()
        patch.object(restore, 'BPCLNTCMD', self.client_command).start()
        patch.object(restore, 'CONTROL_ROOT', self.root / 'user_ops').start()
        self.lookup = 'expecting response from server netbackup\nablecube1 ablecube1 192.168.1.21 40423\n'
        self.run = patch.object(restore.subprocess, 'run', side_effect=self.execute).start()

    def execute(self, args, **kwargs):
        if Path(args[0]).name == 'bpclntcmd':
            return subprocess.CompletedProcess(args, 0, stdout=self.lookup, stderr='')
        kwargs['stdout'].write('390\n')
        return subprocess.CompletedProcess(args, 0)

    def submit(self, recover=False):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            restore.restore(self.file, self.plan, recover)
        return output.getvalue()

    def restore_calls(self):
        return [call for call in self.run.call_args_list if Path(call.args[0][0]).name == 'bprestore']

    def test_cross_client_restore_uses_the_original_client_and_the_local_destination(self):
        self.assertIn('ABLESTACK_JOB_ID=390', self.submit())
        args = self.restore_calls()[0].args[0]
        self.assertEqual('ablecube4', args[args.index('-C') + 1])
        self.assertNotIn('-D', args)
        self.assertEqual('netbackup', args[args.index('-S') + 1])
        self.assertEqual('10/25/2026 20:02:01', args[args.index('-s') + 1])
        self.assertEqual(args[args.index('-s') + 1], args[args.index('-e') + 1])
        self.assertEqual(self.plan['request']['artifact']['path'] + '\n', Path(args[args.index('-f') + 1]).read_text())
        self.assertEqual('change %s to %s\n' % (self.plan['request']['artifact']['path'], self.plan['request']['destination']),
                         Path(args[args.index('-R') + 1]).read_text())
        self.assertEqual('390', json.loads(self.file.with_suffix('.receipt.json').read_text())['jobId'])

    def test_same_client_restore_also_uses_client_local_mode(self):
        self.plan['request']['artifact']['sourceHost'] = 'ablecube1'
        self.assertIn('ABLESTACK_JOB_ID=390', self.submit())
        args = self.restore_calls()[0].args[0]
        self.assertEqual('ablecube1', args[args.index('-C') + 1])
        self.assertNotIn('-D', args)

    def test_wrong_or_unconfirmed_local_client_cannot_submit_or_create_an_intent(self):
        for lookup in ('expecting response from server netbackup\nablecube2 ablecube2 192.168.1.22 40423\n',
                       'expecting response from server netbackup\n',
                       'ablecube1 ablecube1 192.168.1.21 40423\nablecube2 ablecube2 192.168.1.22 40424\n'):
            with self.subTest(lookup=lookup):
                self.lookup = lookup
                with self.assertRaisesRegex(RuntimeError, 'local.*Client'):
                    self.submit()
                self.assertEqual([], self.restore_calls())
                self.assertFalse(self.file.with_suffix('.intent.json').exists())

    def test_failed_or_timed_out_identity_lookup_cannot_submit(self):
        for outcome in (subprocess.CompletedProcess([], 25, stdout='', stderr='cannot connect'),
                        subprocess.TimeoutExpired(['bpclntcmd', '-pn'], 10)):
            with self.subTest(outcome=outcome):
                self.run.reset_mock()
                self.run.side_effect = outcome if isinstance(outcome, Exception) else None
                self.run.return_value = outcome
                with self.assertRaisesRegex(RuntimeError, 'local.*Client'):
                    self.submit()
                self.assertEqual([], self.restore_calls())
                self.assertFalse(self.file.with_suffix('.intent.json').exists())

    def test_saved_job_receipt_recovery_does_not_repeat_lookup_or_restore(self):
        self.submit()
        self.run.reset_mock()
        self.run.side_effect = AssertionError('must not repeat a submitted request')
        self.assertIn('ABLESTACK_JOB_ID=390', self.submit(recover=True))
        self.run.assert_not_called()

    def test_response_loss_recovers_the_original_job_without_resubmitting(self):
        def interrupted(args, **kwargs):
            result = self.execute(args, **kwargs)
            if Path(args[0]).name == 'bprestore':
                raise subprocess.TimeoutExpired(args, kwargs['timeout'])
            return result
        self.run.side_effect = interrupted
        with self.assertRaises(subprocess.TimeoutExpired):
            self.submit()
        self.assertTrue(self.file.with_suffix('.intent.json').exists())
        self.assertFalse(self.file.with_suffix('.receipt.json').exists())
        self.run.reset_mock()
        self.run.side_effect = AssertionError('must recover the existing log')
        self.assertIn('ABLESTACK_JOB_ID=390', self.submit(recover=True))
        self.run.assert_not_called()

    def test_changed_saved_plan_is_not_adopted_on_recovery(self):
        self.submit()
        changed = copy.deepcopy(self.plan)
        changed['destinationClient'] = 'ablecube2'
        self.run.reset_mock()
        with self.assertRaisesRegex(RuntimeError, 'another request'):
            restore.restore(self.file, changed, True)
        self.run.assert_not_called()

    def test_preflight_failure_is_a_durable_non_submission(self):
        self.lookup = 'expecting response from server netbackup\nablecube2 ablecube2 192.168.1.22 40423\n'
        for recover in (False, True):
            with self.subTest(recover=recover):
                argv = ['netbackup-volume-restore.py', '--plan-file', str(self.file)] + (['--recover-only'] if recover else [])
                with patch.object(sys, 'argv', argv), contextlib.redirect_stdout(io.StringIO()) as output:
                    restore.main()
                saved = json.loads(output.getvalue().split('ABLESTACK_NOT_SUBMITTED=', 1)[1])
                self.assertTrue(saved['notSubmitted'])
                self.assertIn('local', saved['failure'])
        self.assertEqual([], self.restore_calls())
        self.assertFalse(self.file.with_suffix('.intent.json').exists())


if __name__ == '__main__':
    unittest.main()
