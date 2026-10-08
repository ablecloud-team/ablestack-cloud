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

import ast
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'

class StorageSmbEndpointLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.restore_called=False
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'state'
        self.state.mkdir(mode=0o750)
        self.unit_path = self.root / 'endpoint.service'
        self.legacy = {('10.1.1.10',445)}
        self.active = set()
        self.calls = []
        self.fail_start = None
        self.fail_verify = False
        self.busy = set()
        self.config = self.root / 'smb.conf'
        self.config.write_bytes(b'previous configuration bytes')
        self.previous_config = self.config.read_bytes()
        self.rows = [{'listenIp':'10.1.1.10','port':445},{'listenIp':'10.1.1.11','port':445}]
        def mapped(path):
            return str(self.unit_path) if str(path) == '/etc/systemd/system/ablestack-storage-smb@.service' else path
        proxy_path = SimpleNamespace(**{name:getattr(os.path,name) for name in ('join','dirname','exists')})
        proxy_path.lexists = lambda path: os.path.lexists(mapped(path))
        proxy = SimpleNamespace(**{name:getattr(os,name) for name in ('makedirs','geteuid','fdopen','fsync','fchmod','listdir','unlink')})
        proxy.replace = lambda source,target: os.replace(mapped(source),mapped(target))
        proxy.path = proxy_path
        proxy.lstat = lambda path: os.lstat(mapped(path))
        proxy.chmod = lambda path, mode: os.chmod(mapped(path),mode)
        def run(args, **kwargs):
            self.calls.append(args)
            if args[:2] == ['systemctl','start']:
                key = args[2].split('@')[1].split('.')[0]
                self.active.add(key)
                if key == self.fail_start:
                    raise subprocess.CalledProcessError(1,args)
            if args[:2] == ['systemctl','stop']:
                self.active.discard(args[2].split('@')[1].split('.')[0])
            return SimpleNamespace(returncode=0)
        def sockets():
            result = set(self.legacy)
            for key in self.active:
                record = json.loads((self.state / 'smb-endpoint-listeners' / (key+'.json')).read_text())
                result.add((record['listenIp'],record['port']))
            return result
        def verify(rows, timeout=10):
            if self.fail_verify and not self.restore_called:
                raise RuntimeError('injected listener failure')
            assert all((row['listenIp'],row['port']) in sockets() for row in rows)
            return [{'success':True} for row in rows]
        self.ns = dict(hashlib=hashlib,ipaddress=ipaddress,json=json,os=proxy,stat=stat,re=re,
                       state_dir=str(self.state),
                       tempfile=SimpleNamespace(mkstemp=lambda **kwargs:tempfile.mkstemp(**{**kwargs,'dir':str(self.root) if kwargs.get('dir')=='/etc/systemd/system' else kwargs.get('dir')})),run=run,
                       subprocess=SimpleNamespace(run=run,DEVNULL=-3),
                       open=lambda path,*args,**kwargs:open(mapped(path),*args,**kwargs),
                       smb_owned_listening_sockets=sockets,smb_legacy_listening_sockets=lambda:set(self.legacy),verify_smb_endpoint_listeners=verify,
                       rollback_uncommitted_managed_identities=lambda:(self.calls.append(["restore-global-config"]),setattr(self,'restore_called',True)),
                       smb_previous_files={str(self.config):self.previous_config},smb_previous_modes={str(self.config):0o644},
                       identity_apply_verified=False,created_identity_users=[],created_identity_groups=[],run_optional=run,
                       smb_budget=lambda maximum=30:maximum,shutil=SimpleNamespace(which=lambda binary:binary),
                       smb_endpoint_connections=lambda ip,port:int((ip,port) in self.busy),
                       smb_unit_active=lambda unit: unit.split('@')[1].split('.')[0] in self.active)
        source = SOURCE.read_text()
        start=source.index('def reconcile_smb_endpoint_units(')
        end=source.index('def install_validated_smb_config(',start)
        exec(compile(ast.parse(source[start:end]),str(SOURCE),'exec'),self.ns)
        key_start=source.index('def smb_listener_key(')
        key_end=source.index('def smb_endpoint_connections(',key_start)
        nodes=[node for node in ast.parse(source[key_start:key_end]).body if isinstance(node,ast.FunctionDef) and node.name=='smb_listener_key']
        exec(compile(ast.Module(body=nodes,type_ignores=[]),str(SOURCE),'exec'),self.ns)
        rollback_start=source.index('def rollback_uncommitted_managed_identities():')
        rollback_end=source.index('atexit.register(rollback_uncommitted_managed_identities)',rollback_start)
        exec(compile(ast.parse(source[rollback_start:rollback_end]),str(SOURCE),'exec'),self.ns)
        actual_rollback=self.ns['rollback_uncommitted_managed_identities']
        def restore_config():
            self.calls.append(['restore-global-config'])
            actual_rollback()
            self.restore_called=True
        self.ns['rollback_uncommitted_managed_identities']=restore_config
        self.a=self.ns['smb_listener_key']('10.1.1.10',445)
        self.b=self.ns['smb_listener_key']('10.1.1.11',445)

    def apply(self,rows=None):
        return self.ns['reconcile_smb_endpoint_units'](self.rows if rows is None else rows)

    def test_add_b_preserves_legacy_a_and_same_request_does_not_restart_either(self):
        self.apply()
        self.assertEqual({self.b},self.active)
        self.assertEqual({('10.1.1.10',445)},self.legacy)
        first=list(self.calls)
        self.apply()
        self.assertEqual(1,sum(call[:2]==['systemctl','start'] for call in self.calls))
        self.assertFalse(any(call[:2]==['systemctl','stop'] for call in self.calls))
        self.assertIn(['systemctl','disable','smbd.service'],first)

    def test_selective_b_removal_preserves_a_acceptor(self):
        self.apply()
        self.calls.clear()
        self.apply(self.rows[:1])
        self.assertEqual(set(),self.active)
        self.assertEqual([['systemctl','stop','ablestack-storage-smb@'+self.b+'.service']],
                         [call for call in self.calls if call[:2]==['systemctl','stop']])
        self.assertEqual({('10.1.1.10',445)},self.legacy)

    def test_busy_b_removal_is_rejected_and_previous_registry_and_unit_are_restored(self):
        self.apply()
        self.busy.add(('10.1.1.11',445))
        with self.assertRaisesRegex(RuntimeError,'drain'):
            self.apply(self.rows[:1])
        self.assertEqual({self.b},self.active)
        self.assertTrue((self.state/'smb-endpoint-listeners'/(self.b+'.json')).exists())
        self.assertEqual('ROLLED_BACK',json.loads((self.state/'smb-endpoint-journal.json').read_text())['phase'])

    def test_new_master_partial_start_failure_stops_only_that_master(self):
        self.fail_start=self.b
        with self.assertRaises(subprocess.CalledProcessError):
            self.apply()
        self.assertEqual(set(),self.active)
        self.assertEqual({('10.1.1.10',445)},self.legacy)
        self.assertFalse(list((self.state/'smb-endpoint-listeners').glob('*.json')))

    def test_listener_verification_failure_restores_previous_units_and_records(self):
        self.fail_verify=True
        with self.assertRaises(RuntimeError):
            self.apply()
        self.assertEqual(set(),self.active)
        self.assertFalse(list((self.state/'smb-endpoint-listeners').glob('*.json')))

    def test_failed_probe_restores_exact_previous_config_before_prior_endpoint_readback(self):
        self.config.write_bytes(b'target configuration bytes')
        self.fail_verify=True
        with self.assertRaises(RuntimeError):
            self.apply()
        self.assertEqual(self.previous_config,self.config.read_bytes())
        self.assertEqual('ROLLED_BACK',json.loads((self.state/'smb-endpoint-journal.json').read_text())['phase'])

    def test_failed_previous_config_hash_verification_marks_recovery_required(self):
        self.fail_verify=True
        def failed_restore():
            self.restore_called=True
            self.config.write_bytes(b'injected wrong rollback bytes')
        self.ns['rollback_uncommitted_managed_identities']=failed_restore
        with self.assertRaisesRegex(RuntimeError,'manual recovery'):
            self.apply()
        self.assertEqual('RECOVERY_REQUIRED',json.loads((self.state/'smb-endpoint-journal.json').read_text())['phase'])

    def test_reboot_reconcile_recreates_exact_acceptors_after_aliases_are_ready(self):
        self.apply()
        self.active.clear()
        self.legacy.clear()
        self.calls.clear()
        self.apply()
        self.assertEqual({self.a,self.b},self.active)
        self.assertEqual(2,sum(call[:2]==['systemctl','start'] for call in self.calls))

    def test_many_slow_unit_starts_share_apply_budget_and_leave_time_for_verified_rollback(self):
        self.legacy.clear()
        self.rows=[{'listenIp':'10.1.1.'+str(index),'port':445} for index in range(10,110)]
        clock=[0.0]
        self.ns.update(time=SimpleNamespace(monotonic=lambda:clock[0]),smb_operation_deadline=300,
                       smb_apply_deadline=240,smb_rollback_active=False)
        source=SOURCE.read_text()
        start=source.index('def smb_budget(maximum=30):')
        end=source.index('payload_path = sys.argv[1]',start)
        exec(compile(ast.parse(source[start:end]),str(SOURCE),'exec'),self.ns)
        fake_run=self.ns['run']
        def timed_run(args,**kwargs):
            delay=20 if args[:2]==['systemctl','start'] else 1 if args[:2]==['systemctl','stop'] else 0
            allowed=kwargs.get('timeout',30)
            clock[0]+=min(delay,allowed)
            if delay>allowed:
                raise subprocess.TimeoutExpired(args,allowed)
            return fake_run(args,**kwargs)
        self.ns['subprocess']=SimpleNamespace(run=timed_run,DEVNULL=-3)
        run_start=source.index('def run(command, **kwargs):',source.index('apply_smb_shares() {'))
        run_end=source.index('def run_optional(command, **kwargs):',run_start)
        exec(compile(ast.parse(source[run_start:run_end]),str(SOURCE),'exec'),self.ns)
        with self.assertRaisesRegex(RuntimeError,'deadline'):
            self.apply()
        self.assertLessEqual(clock[0],300)
        self.assertLess(sum(call[:2]==['systemctl','start'] for call in self.calls),100)
        self.assertEqual(set(),self.active)
        self.assertEqual('ROLLED_BACK',json.loads((self.state/'smb-endpoint-journal.json').read_text())['phase'])

    def test_shared_legacy_acceptor_removal_is_blocked_while_other_endpoint_remains(self):
        self.legacy.add(('10.1.1.11',445))
        with self.assertRaisesRegex(RuntimeError,'maintenance'):
            self.apply(self.rows[:1])
        self.assertFalse((self.state/'smb-endpoint-listeners').exists())
        self.assertFalse(self.calls)

    def test_wildcard_overlap_is_rejected_before_starting_or_writing_registry(self):
        self.legacy={('0.0.0.0',445)}
        with self.assertRaisesRegex(RuntimeError,'drain'):
            self.apply()
        self.assertFalse((self.state/'smb-endpoint-listeners').exists())

if __name__ == '__main__':
    unittest.main()
