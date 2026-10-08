# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

import copy,json,os
from pathlib import Path
import sys,tempfile,unittest,uuid
from unittest.mock import patch

LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import smb_identity as module


class StorageSmbIdentityTest(unittest.TestCase):
    def setUp(self):
        self.handler=module.SmbIdentity(Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl");self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.handler.generations=Path(self.temp.name)/"generations";self.handler.generations.mkdir()
        self.scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':9}
        self.current={'instanceUuid':self.scope['instanceUuid'],'operationUuid':str(uuid.uuid4()),'revision':9,'configurationSha256':'a'*64}
        path=self.handler.generations/'current.json';path.write_text(json.dumps(self.current));path.chmod(0o600)
        self.before={'scope':self.scope,'bootId':str(uuid.uuid4()),'generation':self.current,'configurationSha256':'b'*64,'databases':{},'masters':[], 'ownedEndpoints':[],'ownershipVerified':True,'identityDatabaseAligned':True,'sessions':{'safeToRebind':True}}

    def test_repair_preserves_existing_native_generation_and_rejects_foreign_pending(self):
        self.assertEqual(self.current,self.handler.generation(self.scope,writer=True))
        with self.assertRaises(ValueError):self.handler.generation({**self.scope,'revision':10},writer=True)
        path=self.handler.generations/'pending.json';path.write_text(json.dumps({**self.scope,'operationUuid':str(uuid.uuid4())}));path.chmod(0o600)
        with self.assertRaises(ValueError):self.handler.generation(self.scope,writer=True)
        self.assertEqual(self.current,json.loads((self.handler.generations/'current.json').read_text()))

    def test_stale_expected_evidence_or_live_sessions_are_rejected_before_any_signal_or_service_action(self):
        self.handler.inspect=lambda *args,**kwargs:self.before
        for change in ({'bootId':str(uuid.uuid4())},{'databases':{'foreign':{}}},{'configurationSha256':'c'*64}):
            with patch.object(module.os,'kill') as kill, patch.object(self.handler,'run') as run:
                with self.assertRaises(ValueError):self.handler.rebind({**self.scope,'expected':{**self.before,**change}})
                kill.assert_not_called();run.assert_not_called()
        self.before['sessions']['safeToRebind']=False
        with patch.object(module.os,'kill') as kill,patch.object(self.handler,'run') as run:
            with self.assertRaises(ValueError):self.handler.rebind({**self.scope,'expected':copy.deepcopy(self.before)})
            kill.assert_not_called();run.assert_not_called()

    def test_already_aligned_noop_does_not_advance_generation_or_touch_services(self):
        self.handler.inspect=lambda *args,**kwargs:self.before
        with patch.object(module.os,'kill') as kill,patch.object(self.handler,'run') as run:
            result=self.handler.rebind({**self.scope,'expected':self.before})
            self.assertFalse(result['generationAdvanced']);self.assertTrue(result['databasesUnchanged']);self.assertTrue(result['configurationUnchanged'])
            self.assertEqual(self.current,result['verification']['generation']);kill.assert_not_called();run.assert_not_called()

    def test_session_zero_requires_both_kernel_and_samba_lock_views(self):
        endpoints=[{'listenIp':'10.10.13.240','port':445}];masters=[{'lockingDatabasesAligned':True}]
        status=json.dumps({'sessions':{},'tcons':{},'open_files':{}});locks=json.dumps({'open_files':{}})
        self.handler.run=lambda args:status if args==['smbstatus','--json'] else locks if 'smbstatus' in args else ''
        self.assertTrue(self.handler.sessions(endpoints,masters)['safeToRebind'])
        for state in ('ESTAB','SYN-RECV'):
            self.handler.run=lambda args:status if args==['smbstatus','--json'] else locks if 'smbstatus' in args else state+' 0 0 10.10.13.240:445 10.10.13.1:12345'
            self.assertFalse(self.handler.sessions(endpoints,masters)['safeToRebind'])
        self.handler.run=lambda args:status if args==['smbstatus','--json'] else locks if 'smbstatus' in args else ''
        self.assertFalse(self.handler.sessions(endpoints,[{'lockingDatabasesAligned':False}])['safeToRebind'])
        self.handler.run=lambda args:'{}'
        with self.assertRaises(ValueError):self.handler.sessions(endpoints,masters)

    def test_completed_journal_replay_is_idempotent_and_requires_exact_original_evidence(self):
        self.handler.inspect=lambda *args,**kwargs:self.before
        record={'scope':self.scope,'expected':self.before,'phase':'COMPLETE'};self.handler.repair_journal(self.scope,record)
        with patch.object(module.os,'kill') as kill,patch.object(self.handler,'run') as run:
            result=self.handler.rebind({**self.scope,'expected':self.before});self.assertTrue(result['idempotent']);kill.assert_not_called();run.assert_not_called()
        with self.assertRaises(ValueError):self.handler.rebind({**self.scope,'expected':{**self.before,'bootId':str(uuid.uuid4())}})

    def prepare_rebind(self):
        self.handler.configuration=Path(self.temp.name)/'config'
        registry=self.handler.configuration/'smb-endpoint-listeners';registry.mkdir(parents=True)
        self.handler.process_root=Path(self.temp.name)/'processes';self.handler.process_root.mkdir()
        self.handler.unit_path=Path(self.temp.name)/'unit.service';self.handler.unit_path.write_text(self.handler.managed_unit_definition());self.handler.unit_path.chmod(0o644)
        self.before['identityDatabaseAligned']=False;self.before['endpointTcpReady']=True
        self.before['databases']={'PASSDB':{'inode':7,'device':2}}
        self.before['masters']=[{'pid':111,'startTicks':'1','unit':'smbd.service'},{'pid':222,'startTicks':'2','unit':'ablestack-storage-smb@'+'b'*24+'.service'}]
        self.before['ownedEndpoints']=[{'listenIp':'10.10.13.240','port':445,'key':'a'*24,'unit':'ablestack-storage-smb@'+'a'*24+'.service','pid':111}, {'listenIp':'10.10.13.241','port':445,'key':'b'*24,'unit':'ablestack-storage-smb@'+'b'*24+'.service','pid':222}]
        for endpoint in self.before['ownedEndpoints']:
            path=registry/(endpoint['key']+'.json');path.write_text(json.dumps({key:endpoint[key] for key in ('listenIp','port')}));path.chmod(0o600)
        for master in self.before['masters']:
            root=self.handler.process_root/str(master['pid']);root.mkdir();(root/'stat').write_text('111 (smbd) '+' '.join(['S']+['0']*18+[master['startTicks']]))
        self.after=copy.deepcopy(self.before);self.after['identityDatabaseAligned']=True;self.after['endpointTcpReady']=True
        self.calls=[]
        self.handler.run=lambda args:self.calls.append(args) or ''
        self.handler.sessions=lambda *args:{'safeToRebind':True}
        return {'expected':copy.deepcopy(self.before),**self.scope}

    def test_rebind_only_known_acceptors_and_keeps_generation_and_database_identity(self):
        request=self.prepare_rebind();self.handler.inspect=lambda *args,**kwargs:self.after if self.calls and self.calls[-1][1]=='start' else self.before
        with patch.object(self.handler,'open_master_handles',return_value={111:os.open('/dev/null',os.O_RDONLY),222:os.open('/dev/null',os.O_RDONLY)}),patch.object(module.signal,'pidfd_send_signal') as kill:
            result=self.handler.rebind(request)
        self.assertTrue(result['rebound']);self.assertTrue(result['databasesUnchanged']);self.assertFalse(result['generationAdvanced'])
        self.assertEqual(self.current,json.loads((self.handler.generations/'current.json').read_text()))
        self.assertEqual([['systemctl','stop','ablestack-storage-smb@'+'b'*24+'.service'],['systemctl','stop','smbd.service']], [args for args in self.calls if 'stop' in args])
        self.assertEqual([row['unit'] for row in self.before['ownedEndpoints']],[args[-1] for args in self.calls if 'start' in args])
        self.assertEqual('COMPLETE',self.handler.repair_journal(self.scope)['phase'])
        self.assertTrue(any(call.args[1]==module.signal.SIGSTOP for call in kill.call_args_list))

    def test_registry_tamper_is_rejected_before_any_master_signal(self):
        request=self.prepare_rebind();self.handler.inspect=lambda *args,**kwargs:self.before
        record=self.handler.configuration/'smb-endpoint-listeners'/('a'*24+'.json');record.write_text(json.dumps({'listenIp':'10.10.13.250','port':445}));record.chmod(0o600)
        with patch.object(module.os,'kill') as kill:
            with self.assertRaises(ValueError):self.handler.rebind(request)
            kill.assert_not_called()
        self.assertEqual([],self.calls)

    def test_service_start_failure_is_durable_recovery_and_preserves_native_generation(self):
        request=self.prepare_rebind();self.handler.inspect=lambda *args,**kwargs:self.before
        def fail(args):
            self.calls.append(args)
            if args[1]=='start':raise ValueError('injected unit start failure')
            return ''
        self.handler.run=fail
        with patch.object(self.handler,'open_master_handles',return_value={111:os.open('/dev/null',os.O_RDONLY),222:os.open('/dev/null',os.O_RDONLY)}),patch.object(module.signal,'pidfd_send_signal'):
            with self.assertRaises(ValueError):self.handler.rebind(request)
        self.assertEqual('RECOVERY_REQUIRED',self.handler.repair_journal(self.scope)['phase'])
        self.assertEqual(self.current,json.loads((self.handler.generations/'current.json').read_text()))
        # Replay stays bound to the original expected record and can recover
        # missing endpoints; it never allocates a new native generation.
        self.handler.run=lambda args:self.calls.append(args) or ''
        self.handler.inspect=lambda *args,**kwargs:self.after if self.calls and self.calls[-1][1]=='start' else self.before
        with patch.object(self.handler,'open_master_handles',return_value={111:os.open('/dev/null',os.O_RDONLY),222:os.open('/dev/null',os.O_RDONLY)}),patch.object(module.signal,'pidfd_send_signal'):
            result=self.handler.rebind(request)
        self.assertTrue(result['rebound']);self.assertEqual('COMPLETE',self.handler.repair_journal(self.scope)['phase'])

    def test_pidfd_unavailable_or_reused_pid_never_signals_any_process(self):
        request=self.prepare_rebind()
        with patch.object(module.os,'pidfd_open',None),patch.object(module.signal,'pidfd_send_signal') as send:
            with self.assertRaises((ValueError,TypeError)):self.handler.open_master_handles(self.before['masters'])
            send.assert_not_called()
        descriptor=os.open('/dev/null',os.O_RDONLY)
        master={**self.before['masters'][0],'startTicks':'wrong'}
        with patch.object(module.os,'pidfd_open',return_value=descriptor),patch.object(module.signal,'pidfd_send_signal') as send:
            with self.assertRaises(ValueError):self.handler.open_master_handles([master])
            send.assert_not_called()
        with self.assertRaises(OSError):os.fstat(descriptor)

    def test_extra_unit_command_is_rejected_before_pidfd_or_signals(self):
        request=self.prepare_rebind();self.handler.inspect=lambda *args,**kwargs:self.before
        self.handler.unit_path.write_text(self.handler.unit_path.read_text()+'ExecStartPost=/tmp/foreign-command\n')
        with patch.object(self.handler,'open_master_handles') as handles:
            with self.assertRaises(ValueError):self.handler.rebind(request)
            handles.assert_not_called()
        self.assertEqual([],self.calls)

if __name__=='__main__':unittest.main()
