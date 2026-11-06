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

import fcntl
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import netbackup_hook_runner as runner


class NetBackupHookRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.context = 'policy__schedule__host'
        self.state = self.root / 'state'
        (self.state / 'locks').mkdir(parents=True)
        self.lock = self.state / 'locks' / (self.context + '.lock')
        self.caller = dict(pid=os.getpid(), token=str(os.getpid()) + ':test', name='bpbkar-test', ppid=1)
        self.worker = self.root / 'worker.py'
        self.worker.write_text('''import fcntl, json, os, signal, subprocess, sys, time
from pathlib import Path
root=Path(os.environ['TEST_ROOT'])
lock=(root/'state/locks/policy__schedule__host.lock').open('a+')
fcntl.flock(lock,fcntl.LOCK_EX)
os.set_inheritable(lock.fileno(),True)
if os.environ.get('IGNORE_TERM'):
 signal.signal(signal.SIGTERM,signal.SIG_IGN)
child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],close_fds=False)
(root/'ready').write_text(json.dumps({'pid':os.getpid(),'child':child.pid,
 'retiredPid':os.environ.get('ABLESTACK_NETBACKUP_RETIRED_PRE_PID'),
 'retiredToken':os.environ.get('ABLESTACK_NETBACKUP_RETIRED_PRE_TOKEN')}))
if os.environ.get('FINISH_EARLY'): sys.exit(0)
while True: time.sleep(0.1)
''')
        self.helper = self.root / 'helper.sh'
        self.helper.write_text('exec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(self.worker)) + '\n')
        self.launcher = self.root / 'launch.py'
        self.launcher.write_text('''import os, sys
sys.path.insert(0,sys.argv[1])
import netbackup_hook_runner as runner
def info(pid):
 try: os.kill(pid,0)
 except ProcessLookupError: return None
 return dict(pid=pid,token=str(pid)+':test',name='bpbkar-test',ppid=1)
runner.process_info=info
parent=info(int(os.environ.get('TEST_CALLER_PID',os.getppid())))
runner.caller_chain=lambda:[parent]
raise SystemExit(runner.run_supervised(sys.argv[2],'policy__schedule__host','PRE',sys.argv[4],'job',
 [sys.argv[3]],timeout=float(sys.argv[5]),grace=0.15,poll=0.03))
''')

    def launch(self, backup='backup', timeout=10, **environment):
        with (self.root / 'output').open('a') as output:
            process = subprocess.Popen([sys.executable, str(self.launcher), str(Path(runner.__file__).parent),
                    str(self.state), str(self.helper), backup, str(timeout)],
                    stdout=output, stderr=subprocess.STDOUT,
                    env=dict(os.environ, TEST_ROOT=str(self.root), **environment))
        def cleanup():
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
        self.addCleanup(cleanup)
        return process

    def wait_ready(self):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            record = runner.read_json(runner.paths(self.state, self.context, 'PRE')[0])
            if (self.root / 'ready').exists() and record and record.get('worker'):
                return record
            time.sleep(0.02)
        self.fail((self.root / 'output').read_text())

    def assert_unlocked(self):
        with self.lock.open('a+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def stop_request(self, backup='backup', caller=None, job='job', status='74'):
        with patch.object(runner, 'caller_chain', return_value=[caller or self.caller]):
            return runner.request_stop(self.state, self.context, backup, job, status)

    def test_notify_timeout_reaps_child_lock_holders_without_deleting_payloads(self):
        payload = self.root / 'disk.raw'
        payload.write_text('preserved data')
        process = self.launch()
        self.wait_ready()
        self.assertTrue(self.stop_request())
        self.assertNotEqual(0, process.wait(timeout=5))
        self.assert_unlocked()
        self.assertEqual('preserved data', payload.read_text())
        record = runner.read_json(runner.paths(self.state, self.context, 'PRE')[0])
        self.assertEqual('STOPPED', record['state'], (self.root / 'output').read_text())
        self.assertEqual('POST_STATUS_74', record['reason'])

    def test_user_cancel_stops_its_own_pre_and_a_fresh_retry_can_acquire_the_lock(self):
        first = self.launch()
        original = self.wait_ready()
        self.assertTrue(self.stop_request(status='150'))
        self.assertNotEqual(0, first.wait(timeout=5))
        self.assert_unlocked()
        (self.root / 'ready').unlink()
        second = self.launch(backup='retry')
        current = self.wait_ready()
        self.assertNotEqual(original['attempt'], current['attempt'])
        self.assertFalse(self.stop_request(backup='backup', status='150'))
        self.assertIsNone(second.poll())
        self.assertTrue(self.stop_request(backup='retry', status='150'))
        second.wait(timeout=5)
        self.assert_unlocked()

    def test_late_post_from_an_old_caller_cannot_stop_a_reused_backup_and_job_id(self):
        process = self.launch()
        self.wait_ready()
        old_caller = dict(self.caller, token='previous-process-start')
        self.assertFalse(self.stop_request(caller=old_caller, status='150'))
        self.assertFalse(self.stop_request(job='another-job', status='74'))
        self.assertIsNone(process.poll())

    def test_duplicate_pre_failure_does_not_cancel_the_original_attempt(self):
        original = self.launch()
        record = self.wait_ready()
        duplicate = self.launch(backup='other-backup')
        self.assertNotEqual(0, duplicate.wait(timeout=5))
        self.assertFalse(self.stop_request(backup='other-backup', status='73'))
        self.assertEqual(record['attempt'], runner.read_json(runner.paths(self.state, self.context, 'PRE')[0])['attempt'])
        self.assertIsNone(original.poll())

    def test_deadline_kills_a_term_ignoring_worker_and_releases_inherited_locks(self):
        process = self.launch(timeout=0.4, IGNORE_TERM='1')
        self.wait_ready()
        self.assertNotEqual(0, process.wait(timeout=5))
        self.assert_unlocked()
        self.assertEqual('TIMEOUT', runner.read_json(runner.paths(self.state, self.context, 'PRE')[0])['reason'])

    def test_supervisor_term_reaps_its_worker_group(self):
        process = self.launch()
        self.wait_ready()
        process.send_signal(signal.SIGTERM)
        self.assertNotEqual(0, process.wait(timeout=5))
        self.assert_unlocked()

    def caller_process(self):
        caller = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        def cleanup():
            if caller.poll() is None:
                caller.terminate()
            caller.wait(timeout=5)
        self.addCleanup(cleanup)
        return caller

    def test_caller_exit_stops_the_hook_even_without_a_post_callback(self):
        caller = self.caller_process()
        process = self.launch(TEST_CALLER_PID=str(caller.pid))
        self.wait_ready()
        caller.terminate()
        caller.wait(timeout=5)
        self.assertNotEqual(0, process.wait(timeout=5))
        self.assert_unlocked()
        self.assertEqual('CALLER_EXITED', runner.read_json(runner.paths(self.state, self.context, 'PRE')[0])['reason'])

    def test_fresh_retry_recovers_a_killed_supervisor_with_the_same_backup_id(self):
        caller = self.caller_process()
        original = self.launch(TEST_CALLER_PID=str(caller.pid))
        previous = self.wait_ready()
        original.kill()
        original.wait(timeout=5)
        caller.terminate()
        caller.wait(timeout=5)
        (self.root / 'ready').unlink()
        retry = self.launch()
        record = self.wait_ready()
        evidence = json.loads((self.root / 'ready').read_text())
        self.assertNotEqual(previous['attempt'], record['attempt'])
        self.assertEqual(str(previous['worker']['pid']), evidence['retiredPid'])
        self.assertEqual(previous['worker']['token'], evidence['retiredToken'])
        self.assertTrue(self.stop_request(status='150'))
        retry.wait(timeout=5)
        self.assert_unlocked()

    def test_successful_worker_exit_also_closes_background_child_lock_descriptors(self):
        process = self.launch(FINISH_EARLY='1')
        self.assertEqual(0, process.wait(timeout=5), (self.root / 'output').read_text())
        self.assert_unlocked()

    def test_queued_stop_receipt_is_bound_to_the_exact_attempt(self):
        record = dict(context=self.context, backupId='backup', attempt='new-attempt')
        self.assertFalse(runner.matching_stop(record, dict(record, attempt='old-attempt')))
        self.assertTrue(runner.matching_stop(record, dict(record)))

    def test_late_post_cannot_finalize_a_new_completed_pre_with_reused_ids(self):
        record = dict(context=self.context, backupId='backup', jobId='job', state='COMPLETED',
                      netbackupCaller=dict(pid=self.caller['pid'], token='new-client-start'))
        runner.atomic(runner.paths(self.state, self.context, 'PRE')[0], record)
        with patch.object(runner, 'caller_chain', return_value=[self.caller]):
            self.assertFalse(runner.accept_post(self.state, self.context, 'backup', 'job'))
        with patch.object(runner, 'caller_chain', return_value=[dict(self.caller, token='new-client-start')]):
            self.assertTrue(runner.accept_post(self.state, self.context, 'backup', 'job'))
        self.assertEqual(record, runner.read_json(runner.paths(self.state, self.context, 'PRE')[0]))

    def test_reusing_every_notify_identity_is_rejected_before_a_new_worker_starts(self):
        first = self.launch()
        record = self.wait_ready()
        self.assertTrue(self.stop_request(status='150'))
        first.wait(timeout=5)
        second = self.launch()
        self.assertNotEqual(0, second.wait(timeout=5))
        self.assertEqual(record['attempt'], runner.read_json(runner.paths(self.state, self.context, 'PRE')[0])['attempt'])
        self.assert_unlocked()

    def test_request_stop_cli_keeps_options_out_of_the_worker_command(self):
        with patch.dict(os.environ, STATE_ROOT=str(self.state), BACKUP_ID='backup'), \
                patch.object(runner, 'request_stop', return_value=True) as stop:
            self.assertEqual(0, runner.main(['request-stop', '--context', self.context, '--status', '150']))
        self.assertEqual('150', stop.call_args.args[-1])


if __name__ == '__main__':
    unittest.main()
