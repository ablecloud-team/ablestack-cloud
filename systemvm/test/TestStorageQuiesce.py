#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "systemvm/debian/usr/local/bin/ablestack-storagectl"

class StorageQuiesceTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="storage-quiesce-test-"))
        self.bin = self.temp / "bin"
        self.bin.mkdir()
        fake = self.bin / "systemctl"
        fake.write_text(r"""#!/usr/bin/env python3
import os, sys
from pathlib import Path
with open(os.environ['QUIESCE_TEST_LOG'], 'a') as f:
    f.write(' '.join(sys.argv[1:]) + '\n')
if sys.argv[1] == 'list-units':
    print('ablestack-storage-ganesha@10_1_1_2-2049.service loaded active running')
    print('smbd.service loaded active running')
    print('unrelated.service loaded active running')
if sys.argv[1] == 'stop' and os.environ.get('QUIESCE_TEST_FAIL') == sys.argv[2]:
    raise SystemExit(1)
""")
        fake.chmod(0o755)
        self.request = {"instanceUuid": str(uuid.uuid4()), "operationUuid": str(uuid.uuid4()), "revision": 4, "templateUpgradeUuid": str(uuid.uuid4())}
        self.payload = self.temp / "request.json"
        self.payload.write_text(json.dumps(self.request))
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'],
                        ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.temp / 'writer.lock'),
                        ABLESTACK_STORAGE_GENERATION_DIR=str(self.temp / 'generations'),
                        ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.temp / 'maintenance'),
                        QUIESCE_TEST_LOG=str(self.temp / 'calls'))

    def tearDown(self):
        shutil.rmtree(self.temp)

    def run_quiesce(self):
        return subprocess.run([str(CLI), 'operation', 'quiesce', str(self.payload)],
                              env=self.env, capture_output=True, text=True)

    def test_quiesce_stops_only_owned_file_services_and_preserves_block_targets(self):
        result = self.run_quiesce()
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)
        self.assertTrue(payload['quiesced'])
        self.assertTrue(payload['blockTargetsPreserved'])
        self.assertEqual('NORMAL_VM_SHUTDOWN', payload['blockSessionBoundary'])
        calls = (self.temp / 'calls').read_text()
        self.assertNotIn('stop unrelated', calls)
        self.assertNotIn('targetcli', calls)

    def test_partial_service_failure_restores_previously_stopped_units(self):
        self.env['QUIESCE_TEST_FAIL'] = 'smbd.service'
        result = self.run_quiesce()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('QUIESCE_FAILED', json.loads(result.stdout)['errorCode'])
        calls = (self.temp / 'calls').read_text()
        self.assertIn('start ablestack-storage-ganesha@', calls)

    def test_other_pending_operation_is_rejected_before_stopping_services(self):
        generation = self.temp / 'generations'
        generation.mkdir()
        pending = dict(self.request, operationUuid=str(uuid.uuid4()))
        (generation / 'pending.json').write_text(json.dumps(pending))
        result = self.run_quiesce()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.temp / 'calls').exists())

    def test_writer_idle_is_nonblocking_read_only_and_reports_busy_without_creating_files(self):
        import fcntl
        lock=self.temp/'writer.lock'
        command=[str(CLI),'operation','writer-idle']
        empty=subprocess.run(command,env=self.env,capture_output=True,text=True)
        self.assertTrue(json.loads(empty.stdout)['writerIdle'])
        self.assertFalse(lock.exists())
        lock.touch(mode=0o600)
        before=lock.stat()
        with lock.open('a') as held:
            fcntl.flock(held,fcntl.LOCK_EX|fcntl.LOCK_NB)
            busy=subprocess.run(command,env=self.env,capture_output=True,text=True)
        self.assertEqual(0,busy.returncode)
        self.assertFalse(json.loads(busy.stdout)['writerIdle'])
        self.assertEqual(before.st_mtime_ns,lock.stat().st_mtime_ns)

    def test_writer_conflict_prevents_quiesce(self):
        import fcntl
        lock = self.temp / 'writer.lock'
        lock.touch(mode=0o600)
        with lock.open('a') as held:
            fcntl.flock(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_quiesce()
        self.assertEqual(75, result.returncode)
        self.assertFalse((self.temp / 'calls').exists())

if __name__ == '__main__':
    unittest.main()
