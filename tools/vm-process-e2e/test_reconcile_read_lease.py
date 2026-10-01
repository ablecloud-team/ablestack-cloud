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
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import reconcile_read_lease as r

VM = '11111111-1111-4111-8111-111111111111'
REQUEST = '22222222-2222-4222-8222-222222222222'


class ReconcileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.root.chmod(0o700)
        for path in (self.root/'locks', self.root/VM):
            path.mkdir(mode=0o700)
        (self.root/'locks'/(VM+'.lock')).touch(mode=0o600)
        self.marker = self.root/VM/('q4-read-'+REQUEST+'.json')
        self.record = dict(kind='q4-read-lease', stage='UNKNOWN', vmUuid=VM,
                           requestId=REQUEST, guestExecPid=123, ownerPid=99999999,
                           ownerStartTicks='123', hostBootId=Path('/proc/sys/kernel/random/boot_id').read_text().strip())
        self.marker.write_text(json.dumps(self.record))
        self.marker.chmod(0o600)
        self.addCleanup(self.temp.cleanup)

    def test_inspection_does_not_consume_status_or_remove_marker(self):
        with patch.object(r, 'virsh') as query:
            self.assertFalse(r.reconcile(VM, REQUEST, root=self.root)['removed'])
            query.assert_not_called()
        self.assertTrue(self.marker.exists())

    def test_confirmed_exit_removes_only_original_marker(self):
        other=self.root/VM/'another-operation.json';other.write_text('{}')
        with patch.object(r, 'virsh', side_effect=['i-2-C7-VM', VM, '{"return":{"exited":true}}']):
            result=r.reconcile(VM, REQUEST, True, self.root)
        self.assertTrue(result['removed']);self.assertFalse(self.marker.exists());self.assertTrue(other.exists())

    def test_running_guest_execution_keeps_marker(self):
        with patch.object(r, 'virsh', side_effect=['i-2-C7-VM', VM, '{"return":{"exited":false}}']):
            with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
        self.assertTrue(self.marker.exists())

    def test_malformed_status_keeps_the_original_marker(self):
        with patch.object(r, 'virsh', side_effect=['i-2-C7-VM', VM, '{"return":null}']):
            with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
        self.assertTrue(self.marker.exists())

    def test_reaped_execution_id_is_not_completion_evidence(self):
        with patch.object(r, 'virsh', side_effect=['i-2-C7-VM', VM, '{"error":{"desc":"PID not found"}}']):
            with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
        self.assertTrue(self.marker.exists())

    def test_live_original_reader_refused_before_status_query(self):
        with patch.object(r, 'owner_alive', return_value=True),patch.object(r,'virsh') as query:
            with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
            query.assert_not_called()

    def test_unknown_dispatch_without_pid_never_cleans(self):
        self.record['guestExecPid']=None;self.marker.write_text(json.dumps(self.record))
        with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
        self.assertTrue(self.marker.exists())

    def test_replacement_marker_survives(self):
        def query(*args):
            if args[0]=='domname':return 'i-2-C7-VM'
            if args[0]=='domuuid':return VM
            self.marker.write_text('{}');return '{"return":{"exited":true}}'
        with patch.object(r,'virsh',side_effect=query):
            with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
        self.assertEqual(self.marker.read_text(),'{}')

    def test_existing_readable_lock_uses_same_guard_contract(self):
        (self.root/"locks"/(VM+".lock")).chmod(0o644)
        self.assertFalse(r.reconcile(VM, REQUEST, root=self.root)["removed"])

    def test_writable_lock_is_refused(self):
        (self.root/"locks"/(VM+".lock")).chmod(0o666)
        with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)

    def test_symlink_is_refused(self):
        real=self.root/'real';self.marker.rename(real);self.marker.symlink_to(real)
        with self.assertRaises(r.ReconcileError):r.reconcile(VM, REQUEST, True, self.root)
        self.assertTrue(real.exists())


if __name__ == '__main__':unittest.main()
