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

import copy,json,os,subprocess
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

    def test_actual_smb_case_returns_exactly_one_success_json_without_share_domain_fallthrough(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        source=cli.read_text();start=source.index('  smb)\n',source.index('command="${1:-}"'));end=source.index('  iscsi)\n',start)
        case=source[start:end]
        payload=Path(self.temp.name)/'payload.json';payload.write_text('{}')
        script='set -euo pipefail\ncommand=smb\nsubcommand=identity\naction=inspect\npayload='+str(payload)+'\nsmb_identity_command() { printf \'%s\\n\' \'{"success":true,"fixture":true}\'; }\nemit_error() { printf \'UNEXPECTED_ERROR\\n\'; }\nlog_command() { printf \'UNEXPECTED_LOG\\n\'; }\ncase "$command" in\n'+case+'esac\n'
        result=subprocess.run(['bash'],input=script,capture_output=True,text=True,timeout=5)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual({'success':True,'fixture':True},json.loads(result.stdout))
        self.assertEqual(1,len(result.stdout.splitlines()))

    def test_start_ack_before_socket_and_identity_readiness_polls_without_repeated_service_actions(self):
        request=self.prepare_rebind();observations=[]
        def inspect(*args,**kwargs):
            if not self.calls or self.calls[-1][1]!='start':return self.before
            observations.append(kwargs.get('allow_missing'))
            if len(observations)==1:return {**self.after,'ownershipVerified':False,'endpointTcpReady':False}
            if len(observations)==2:return {**self.after,'identityDatabaseAligned':False}
            return self.after
        self.handler.inspect=inspect
        with patch.object(self.handler,'open_master_handles',return_value={111:os.open('/dev/null',os.O_RDONLY),222:os.open('/dev/null',os.O_RDONLY)}),patch.object(module.signal,'pidfd_send_signal') as send,patch.object(module.time,'sleep'):
            result=self.handler.rebind(request)
        self.assertEqual(3,result['verification']['readinessAttempts']);self.assertEqual([True,True,True],observations)
        self.assertEqual(2,len([args for args in self.calls if args[1]=='start']));self.assertEqual(6,send.call_count)
        self.assertEqual('COMPLETE',self.handler.repair_journal(self.scope)['phase'])
        self.assertEqual(self.current,result['generation'])

    def test_recovery_retry_already_aligned_waits_for_tcp_without_signals_or_restart(self):
        request=self.prepare_rebind();journal={'scope':self.scope,'expected':request['expected'],'phase':'RECOVERY_REQUIRED'};self.handler.repair_journal(self.scope,journal)
        calls=[]
        def inspect(*args,**kwargs):
            calls.append(1)
            return {**self.after,'endpointTcpReady':len(calls)>=3}
        self.handler.inspect=inspect
        with patch.object(self.handler,'open_master_handles') as handles,patch.object(self.handler,'run') as run,patch.object(module.signal,'pidfd_send_signal') as send,patch.object(module.time,'sleep'):
            result=self.handler.rebind(request)
        self.assertTrue(result['idempotent']);self.assertEqual(3,len(calls));self.assertEqual('COMPLETE',self.handler.repair_journal(self.scope)['phase'])
        handles.assert_not_called();run.assert_not_called();send.assert_not_called()

    def test_readiness_scope_change_or_expired_deadline_never_restarts_a_process_or_claims_ready(self):
        frozen=copy.deepcopy(self.before)
        self.handler.inspect=lambda *args,**kwargs:{**frozen,'configurationSha256':'foreign'}
        with self.assertRaisesRegex(ValueError,'source scope'):self.handler.wait_for_ready(self.scope,frozen)
        self.handler.inspect=lambda *args,**kwargs:{**frozen,'endpointTcpReady':False}
        self.handler.deadline=module.time.monotonic()+.01
        with patch.object(module.signal,'pidfd_send_signal') as send,patch.object(self.handler,'run') as run:
            with self.assertRaises(TimeoutError):self.handler.wait_for_ready(self.scope,frozen)
            send.assert_not_called();run.assert_not_called()


    def unconfigured_fixture(self):
        import hashlib
        self.handler.configuration=Path(self.temp.name)/'configuration'
        (self.handler.configuration/'desired-state').mkdir(parents=True,mode=0o700)
        self.handler.process_root=Path(self.temp.name)/'proc';self.handler.process_root.mkdir(mode=0o700)
        self.scope['revision']=10;self.current['verifiedAt']=1.0
        desired={name:None for name in ('desired-state/nfs-export-apply.json','desired-state/smb-share-apply.json','iscsi-targets.json','nvmeof-subsystems.json','posix-directory-policies.json','network-endpoints.json','sharedfs-network.json')}
        self.current['configurationSha256']=hashlib.sha256(json.dumps(desired,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        def write(path,value):path.write_text(json.dumps(value));path.chmod(0o600)
        write(self.handler.generations/'current.json',self.current)
        write(self.handler.generations/(self.current['operationUuid']+'.json'),{**self.current,'phase':'VERIFIED','desired':desired})
        write(self.handler.generations/'pending.json',{**self.scope,'phase':'PREPARED','previous':self.current,'beforeSha256':self.current['configurationSha256']})
        self.handler.database_identity=lambda:{name:{'path':path,'present':False} for name,path in module.IDENTITY_DATABASES.items()}
        self.handler.identity_holders=lambda:[]
        commands=[]
        def run(args):
            commands.append(args)
            if args[:3]==['ss','-H','-ltnp']:return ''
            if args[0]=='systemctl':return '0\n'
            self.fail('Unconfigured observer invoked identity or service mutation command')
        self.handler.run=run
        configuration=Path(self.temp.name)/'smb.conf';configuration.write_text('[global]\n')
        original=Path
        def paths(value):return configuration if str(value)=='/etc/samba/smb.conf' else original(value)
        return write,desired,commands,patch.object(module,'Path',paths)

    def test_verified_unconfigured_source_absence_receipt_is_restore_safe_without_creating_sam(self):
        write,desired,commands,paths=self.unconfigured_fixture()
        pending=(self.handler.generations/'pending.json').read_bytes()
        with paths:result=self.handler.inspect(self.scope)
        self.assertEqual('UNCONFIGURED_SMB_SOURCE',result['identityBaselineKind'])
        for field in ('success','ownershipVerified','identityRestoreSafe'):self.assertIs(result[field],True)
        self.assertEqual(self.scope,result['scope']);self.assertEqual([],result['ownedEndpoints'])
        self.assertEqual([],result['masters']);self.assertIs(result['sessions']['safeToRebind'],True)
        self.assertTrue(all(row['present'] is False for row in result['databases'].values()))
        self.assertEqual(pending,(self.handler.generations/'pending.json').read_bytes())
        self.assertFalse((self.handler.configuration/'desired-state/smb-share-apply.json').exists())
        self.assertFalse(any(args[0] in ('net','pdbedit','smbstatus') for args in commands))

    def test_unconfigured_source_rejects_changed_original_or_nonabsent_database(self):
        write,desired,commands,paths=self.unconfigured_fixture()
        artifact=self.handler.generations/(self.current['operationUuid']+'.json')
        original=json.loads(artifact.read_text());changed=copy.deepcopy(original);changed['desired']['desired-state/smb-share-apply.json']={'enabled':False,'shares':[]};write(artifact,changed)
        with paths,self.assertRaises(ValueError):self.handler.inspect(self.scope)
        write(artifact,original);self.handler.database_identity=lambda:{'PASSDB':{'present':True}}
        with paths,self.assertRaises(ValueError):self.handler.inspect(self.scope)

    def test_unconfigured_source_rejects_foreign_acceptor_daemon_or_pending_scope(self):
        write,desired,commands,paths=self.unconfigured_fixture()
        self.handler.run=lambda args:'LISTEN 0 128 0.0.0.0:445 0.0.0.0:* users:(("foreign",pid=200,fd=3))\n' if args[0]=='ss' else '0\n'
        with paths,self.assertRaises(ValueError):self.handler.inspect(self.scope)
        self.handler.run=lambda args:'' if args[0]=='ss' else '0\n'
        process=self.handler.process_root/'200';process.mkdir();(process/'comm').write_text('smbd\n')
        with paths,self.assertRaises(ValueError):self.handler.inspect(self.scope)
        (process/'comm').unlink();process.rmdir()
        pending=json.loads((self.handler.generations/'pending.json').read_text());pending['operationUuid']=str(uuid.uuid4());write(self.handler.generations/'pending.json',pending)
        with paths,self.assertRaises(ValueError):self.handler.inspect(self.scope)

if __name__=='__main__':unittest.main()
