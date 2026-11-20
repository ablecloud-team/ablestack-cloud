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

"""Use the current Global Hook limit before dispatching any notify worker."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parent
KEY = 'backup.plugin.netbackup.hook.timeout'


class NetBackupHookTimeoutTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        python = self.bin / 'python3'
        python.write_text('#!' + sys.executable + '\n' + '''import json, os, pathlib, sys
if sys.argv[1].endswith('/netbackup_hook_runner.py'):
    pathlib.Path(os.environ['TEST_ROOT'], 'started.json').write_text(json.dumps(sys.argv[1:]))
else:
    os.execv(sys.executable, [sys.executable, *sys.argv[1:]])
''')
        python.chmod(0o700)
        (self.root / 'netbackup-host-policy.conf').write_text('''NETBACKUP_POLICY_NAME=policy
MOLD_URL=http://mold.test/client/api
ADMIN_APIKEY=synthetic-api-key
ADMIN_SECRETKEY=synthetic-secret
''')

    def response(self, value):
        return {'listconfigurationsresponse': {'configuration': [{'name': KEY, 'value': value, 'scope': 'Global'}]}}

    def run_hook(self, response, phase='PRE', failure=False, worker=False):
        (self.root / 'started.json').unlink(missing_ok=True)
        script = r'''source "$TEST_COMMON"
POLICY_NAME=policy
POLICY_SAFE=policy
CONTEXT_KEY=policy__schedule__host
SCRIPT_DIR="$TEST_SCRIPTS"
invoke_mold_api() {
  printf '%s\n' "$*" >> "$TEST_ROOT/requests"
  [[ "$TEST_FAILURE" == false ]] || return 1
  printf '%s' "$TEST_RESPONSE"
}
supervise_netbackup_hook "$TEST_PHASE" /synthetic-worker.sh worker-argument
'''
        return subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=10,
            env=dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], TEST_ROOT=str(self.root),
                CONFIG_ROOT=str(self.root), LOG_FILE=str(self.root / 'hook.log'),
                TEST_COMMON=str(DIRECTORY / 'netbackup-host-common.sh'), TEST_SCRIPTS=str(DIRECTORY),
                TEST_RESPONSE=json.dumps(response), TEST_PHASE=phase, TEST_FAILURE=str(failure).lower(),
                ABLESTACK_NETBACKUP_HOOK_WORKER=phase if worker else '', NETBACKUP_HOOK_TIMEOUT='999'))

    def test_each_pre_and_post_invocation_passes_the_current_global_value_to_the_runner(self):
        for phase, seconds in [('PRE', 86400), ('POST', 172800), ('PRE', 3600)]:
            with self.subTest(phase=phase, seconds=seconds):
                result = self.run_hook(self.response(str(seconds)), phase)
                self.assertEqual(0, result.returncode, result.stderr)
                args = json.loads((self.root / 'started.json').read_text())
                self.assertEqual(str(seconds), args[args.index('--timeout') + 1])
                self.assertEqual(phase, args[args.index('--phase') + 1])
                self.assertEqual(['/synthetic-worker.sh', 'worker-argument'], args[args.index('--') + 1:])
        requests = (self.root / 'requests').read_text().splitlines()
        self.assertEqual(['GET http://mold.test/client/api listConfigurations name ' + KEY] * 3, requests)

    def test_invalid_and_missing_global_values_do_not_use_an_environment_or_fixed_fallback(self):
        documents = [self.response(value) for value in [None, '', '0', '-1', 'invalid', '1.5', True, 1.5, '2147483648']]
        documents += [{'errorresponse': {'errortext': 'denied'}}, {'listconfigurationsresponse': {'configuration': []}}]
        for document in documents:
            with self.subTest(document=document):
                result = self.run_hook(document)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(KEY, result.stderr)
                self.assertFalse((self.root / 'started.json').exists())

    def test_zone_wrong_key_and_ambiguous_responses_cannot_be_mistaken_for_the_global_limit(self):
        for row in [{'name': KEY, 'scope': 'Zone', 'value': '86400'},
                    {'name': 'another.setting', 'value': '86400'}]:
            result = self.run_hook({'listconfigurationsresponse': {'configuration': [row]}})
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((self.root / 'started.json').exists())
        rows = self.response('86400')
        rows['listconfigurationsresponse']['configuration'] *= 2
        self.assertNotEqual(0, self.run_hook(rows).returncode)

    def test_api_failure_stops_before_any_worker_is_started(self):
        result = self.run_hook(self.response('86400'), failure=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Failed to query', result.stderr)
        self.assertFalse((self.root / 'started.json').exists())

    def test_supervised_child_does_not_read_configuration_or_start_another_supervisor(self):
        result = self.run_hook(self.response('86400'), worker=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.root / 'started.json').exists())
        self.assertFalse((self.root / 'requests').exists())


if __name__ == '__main__':
    unittest.main()
