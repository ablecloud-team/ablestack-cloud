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


"""Exercise real native writer locks and read-only NFS preflight control flow."""
import os, json, tempfile, subprocess, unittest, fcntl
from pathlib import Path
SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'
TEXT = SOURCE.read_text()
FUNCTION = TEXT.split('acquire_storage_writer_lock() {', 1)[1].split('json_escape() {', 1)[0]
FUNCTION = 'acquire_storage_writer_lock() {' + FUNCTION

class NativeWriterLockTest(unittest.TestCase):
    def run_lock(self, root, suffix='printf MUTATION_ALLOWED'):
        environment = os.environ.copy()
        environment.pop('ABLESTACK_STORAGE_WRITER_LOCK_FD', None)
        environment['ABLESTACK_STORAGE_WRITER_LOCK_FILE'] = str(Path(root) / 'writer.lock')
        return subprocess.run(['bash', '-c', 'set -euo pipefail\n' + FUNCTION + '\nacquire_storage_writer_lock\n' + suffix], env=environment, capture_output=True, text=True)

    def test_live_guest_writer_blocks_new_mutation_without_waiting(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'writer.lock'
            fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX)
                result = self.run_lock(root)
                self.assertEqual(75, result.returncode)
                self.assertEqual('STORAGE_WRITER_BUSY', json.loads(result.stdout)['errorCode'])
                self.assertNotIn('MUTATION_ALLOWED', result.stdout)
            finally:
                os.close(fd)
            self.assertEqual('MUTATION_ALLOWED', self.run_lock(root).stdout)

    def test_lock_symlink_and_writable_parent_are_rejected_before_open(self):
        with tempfile.TemporaryDirectory() as root:
            other = Path(root) / 'preserved'
            other.write_text('unchanged')
            (Path(root) / 'writer.lock').symlink_to(other)
            self.assertEqual(2, self.run_lock(root).returncode)
            self.assertEqual('unchanged', other.read_text())
            (Path(root) / 'writer.lock').unlink()
            os.chmod(root, 0o777)
            self.assertEqual(2, self.run_lock(root).returncode)
            self.assertFalse((Path(root) / 'writer.lock').exists())

    def test_nested_native_child_reuses_the_inherited_locked_descriptor(self):
        with tempfile.TemporaryDirectory() as root:
            child = Path(root) / 'child.sh'
            child.write_text('set -euo pipefail\n' + FUNCTION + '\nacquire_storage_writer_lock\nprintf NESTED_ALLOWED')
            result = self.run_lock(root, 'bash ' + str(child))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('NESTED_ALLOWED', result.stdout)

    def test_identity_replay_preserves_the_writer_descriptor_through_python(self):
        with tempfile.TemporaryDirectory() as root:
            module = SOURCE.parents[1] / 'lib/ablestack-storage/identity_capsule.py'
            child = Path(root) / 'child.py'
            child.write_text('import importlib.util,json\ns=importlib.util.spec_from_file_location("capsule",'+repr(str(module))+')\nm=importlib.util.module_from_spec(s);s.loader.exec_module(m)\nprint(json.dumps(m.writer_lock_fds()))')
            result = self.run_lock(root, 'python3 ' + str(child))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([9], json.loads(result.stdout))

    def test_nfs_idmapping_probe_never_applies_or_removes_existing_exports(self):
        with tempfile.TemporaryDirectory() as root:
            binary = Path(root) / 'ganesha.nfsd'
            binary.write_text('#!/bin/bash\nprintf "NFS-Ganesha V4.3\n"\n')
            binary.chmod(0o700)
            request = Path(root) / 'request.json'
            request.write_text(json.dumps({'idMappingMode':'NUMERIC'}))
            footer = TEXT[TEXT.index('command="${1:-}"'):]
            script = 'set -euo pipefail\n' + FUNCTION + '\nlog_command() { :; }\nemit_error() { printf ERROR; }\napply_nfs_exports() { printf UNEXPECTED_MUTATION; exit 91; }\n' + footer
            environment = os.environ.copy()
            environment['PATH'] = root + ':' + environment['PATH']
            environment['ABLESTACK_STORAGE_WRITER_LOCK_FILE'] = str(Path(root) / 'writer.lock')
            result = subprocess.run(['bash','-c',script,'storagectl','nfs','idmapping','preflight',str(request)], env=environment, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(json.loads(result.stdout)['success'])
            self.assertNotIn('UNEXPECTED_MUTATION', result.stdout)
            self.assertFalse((Path(root) / 'writer.lock').exists())

if __name__ == '__main__':
    unittest.main()
