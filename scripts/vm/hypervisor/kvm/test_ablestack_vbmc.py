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
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('vbmc_lifecycle', Path(__file__).with_name('ablestack_vbmc.py'))
vbmc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vbmc)


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.patch = patch.object(vbmc, 'ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.item = dict(name='i-2-99-VM', port=6230, token='a' * 36,
                         address='10.10.31.2', cidr='10.10.0.0/16', uuid='test-domain')
        self.path = self.root / '6230.json'
        self.path.write_text(json.dumps(self.item))

    def test_conflicting_owner_cannot_delete(self):
        with patch.object(vbmc, 'run') as run:
            with self.assertRaises(RuntimeError):
                vbmc.execute('delete', self.item['name'], '6230', 'b' * 36,
                             self.item['address'], self.item['cidr'], None)
            run.assert_not_called()
        self.assertTrue(self.path.exists())

    def test_listener_preserves_reservation(self):
        with patch.object(vbmc, 'run', return_value=Mock(stdout='UNCONN 6230')):
            with self.assertRaises(RuntimeError):
                vbmc.delete(self.item, self.path)
        self.assertTrue(self.path.exists())

    def test_firewall_failure_restores_manifest(self):
        with patch.object(vbmc, 'run', return_value=Mock(stdout='')), \
                patch.object(vbmc, 'firewall_rule', side_effect=RuntimeError('failure')):
            with self.assertRaises(RuntimeError):
                vbmc.delete(self.item, self.path)
        self.assertEqual(json.loads(self.path.read_text()), self.item)

    def test_confirmed_delete_removes_manifest(self):
        with patch.object(vbmc, 'run', return_value=Mock(stdout='')), patch.object(vbmc, 'firewall_rule'):
            vbmc.delete(self.item, self.path)
        self.assertFalse(self.path.exists())

    def test_policy_does_not_reload_or_flush_existing_host_rules(self):
        with patch.object(vbmc, 'run', return_value=Mock(returncode=1)) as run:
            vbmc.firewall_rule(self.item)
            commands = [' '.join(c.args[0]) for c in run.call_args_list]
        self.assertTrue(any('! -s 10.10.0.0/16' in c and '-j DROP' in c for c in commands))
        self.assertTrue(any('--permanent' in c for c in commands))
        self.assertFalse(any('--reload' in c or 'flush' in c for c in commands))

    def test_credentials_are_not_process_arguments(self):
        password = 'unit-test-secret-123'
        with patch.object(vbmc, 'run') as run:
            vbmc.vbmc(['add', 'i-2-99-VM'], password)
            self.assertNotIn(password, ' '.join(run.call_args.args[0]))
            self.assertEqual(run.call_args.kwargs['env']['CLOUD_VBMC_PASSWORD'], password)


if __name__ == '__main__':
    unittest.main()
