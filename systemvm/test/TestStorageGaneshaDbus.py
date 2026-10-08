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

from pathlib import Path
import ast,json,os,re,sys,tempfile,time,unittest
from unittest.mock import Mock,mock_open
from types import SimpleNamespace
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import ganesha_dbus as dbus_module
from ganesha_dbus import GaneshaDbus

class StorageGaneshaDbusTest(unittest.TestCase):
    def setUp(self):
        self.owner=123;self.exports={1001:{'exportId':1001,'path':'/export/share','pseudo':'/share','fsal':'VFS','mountPathPseudo':False}}
        self.calls=[];outer=self
        class Object:
            def get_dbus_method(self,name,interface):
                def call(*args,**kwargs):
                    outer.calls.append((name,args,kwargs['timeout']))
                    if name=='GetConnectionUnixProcessID':return outer.owner
                    if name=='ShowExports':return ((0,0),[(number,row['pseudo'] if row['mountPathPseudo'] else row['path']) for number,row in outer.exports.items()])
                    if name=='DisplayExport':
                        row=outer.exports[args[0]];return (row['exportId'],row['path'],row['pseudo'] if row['mountPathPseudo'] else row['path'],'',row.get('clientRows',[]))
                    if name=='UpdateExport':return 'Exports updated'
                    raise AssertionError(name)
                return call
        class Bus:
            def get_object(self,*args):return Object()
        self.adapter=GaneshaDbus(Bus());self.previous=list(self.exports.values());self.name='org.ganesha.nfsd'

    def test_exact_owned_dynamic_update_rechecks_inventory_and_never_restarts_a_process(self):
        result=self.adapter.update(self.name,123,'/immutable/candidate.conf',self.previous,self.previous)
        self.assertEqual('DBUS_DYNAMIC_UPDATE_VERIFIED',result['activationStrategy']);self.assertFalse(result['restartPerformed'])
        self.assertEqual(1,len([call for call in self.calls if call[0]=='UpdateExport']));self.assertTrue(all(0<call[2]<=5 for call in self.calls))
        self.assertEqual(2,len([call for call in self.calls if call[0]=='GetConnectionUnixProcessID']))

    def test_foreign_owner_immutable_binding_or_removal_rejects_before_export_mutation(self):
        self.owner=456
        with self.assertRaises(ValueError):self.adapter.update(self.name,123,'/candidate',self.previous,self.previous)
        self.assertFalse(any(call[0]=='UpdateExport' for call in self.calls))
        for target in ([{**self.previous[0],'path':'/foreign'}],[]):
            self.calls.clear()
            with self.assertRaises(ValueError):self.adapter.update(self.name,123,'/candidate',self.previous,target)
            self.assertFalse(self.calls)

    def test_readback_mismatch_after_update_never_claims_success(self):
        original=self.adapter.call
        def call(manager,method,*args):
            result=original(manager,method,*args)
            if method=='UpdateExport':self.exports[1001]={**self.exports[1001],'path':'/unexpected'}
            return result
        self.adapter.call=call
        with self.assertRaises(ValueError):self.adapter.update(self.name,123,'/candidate',self.previous,self.previous)

    def test_expired_total_budget_and_unexpected_bus_name_are_not_accepted(self):
        self.adapter.deadline=time.monotonic()-1
        with self.assertRaises(TimeoutError):self.adapter.owned_manager(self.name,123)
        with self.assertRaises(ValueError):self.adapter.owned_manager('../unrelated',123)

    def test_configuration_handles_quoted_braces_and_rejects_duplicate_or_truncated_binding(self):
        config='NFS_Core_Param { NFS_Port = 2049; }\nEXPORT {\n Export_Id = 1001;\n Path = "/export/{share}";\n Pseudo = "/share";\n FSAL {\n Name = VFS;\n }\n}\n'
        observed=self.adapter.configuration(config)
        self.assertEqual('/export/{share}',observed['exports'][0]['path'])
        for malformed in (config+config[config.index('EXPORT'):],config[:-3]):
            with self.assertRaises(ValueError):self.adapter.configuration(malformed)
        self.calls.clear()
        with self.assertRaisesRegex(ValueError,'global/listener'):
            self.adapter.transition(self.name,123,'/candidate',config,config.replace('2049','2050'))
        self.assertFalse(self.calls)

    def test_policy_only_transition_keeps_exact_binding_and_rechecks_actual_owner(self):
        config='NFS_Core_Param { NFS_Port = 2049; }\nEXPORT {\n Export_Id = 1001;\n Path = "/export/share";\n Pseudo = "/share";\n Access_Type = RO;\n FSAL {\n Name = VFS;\n }\n}\n'
        observed=self.adapter.transition(self.name,123,'/candidate',config,config.replace('RO','RW'))
        self.assertEqual('DBUS_DYNAMIC_UPDATE_VERIFIED',observed['activationStrategy'])

    def test_installed_nfs_rendered_path_keeps_a_known_running_endpoint_without_restart(self):
        cli=LIB.parents[5]/'systemvm/debian/usr/local/bin/ablestack-storagectl'
        blocks=re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)
        tree=ast.parse(next(value for value in blocks if 'def rendered_ganesha_dynamic_candidates(' in value))
        names={'rendered_ganesha_dynamic_candidates','start_ganesha_endpoints'}
        definitions=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in names]
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);source=directory/'source';source.mkdir();(source/'nfs').mkdir();run=directory/'run';run.mkdir()
            key='known';conf=directory/'known.conf'
            config='NFS_Core_Param { NFS_Port = 2049; }\nEXPORT {\n Export_Id = 1001;\n Path = "/export/share";\n Pseudo = "/share";\n FSAL {\n Name = VFS;\n }\n}\n'
            conf.write_text(config);(source/'nfs/manifest.json').write_text(json.dumps({'endpoints':[{'legacyUnitKey':key,'configurationPath':'nfs/known.conf'}]}));(source/'nfs/known.conf').write_text(config)
            pidfile=run/'known.pid';pidfile.write_text('123');before=pidfile.stat()
            class ProcessPath:
                def read_bytes(self):return b'/usr/bin/ganesha.nfsd'+bytes([0])+b'-f'+bytes([0])+str(conf).encode()+bytes([0])
            def paths(value):return ProcessPath() if str(value).startswith('/proc/') else Path(value)
            process=Mock();process.run.return_value=Mock(returncode=0,stdout='123');adapter=Mock()
            adapter.owned_endpoint_manager.return_value=(self.name,Mock());adapter.configuration.side_effect=self.adapter.configuration;adapter.budget.return_value=1;adapter.name.return_value=self.name
            adapter.transition.return_value={'activationStrategy':'DBUS_DYNAMIC_UPDATE_VERIFIED','restartPerformed':False}
            commands=Mock();commands.which.side_effect=lambda name:'/usr/bin/ganesha.nfsd' if name=='ganesha.nfsd' else None
            network=Mock();network.create_connection.return_value.__enter__=Mock();network.create_connection.return_value.__exit__=Mock()
            ns={'nfs_operation_deadline':time.monotonic()+30,'ganesha_remaining':lambda maximum=10:maximum,'rendered_context':(source,{}),'rendered_from_context':(source,{}),'Path':paths,'json':json,'os':os,'time':time,
                'subprocess':process,'GaneshaDbus':Mock(return_value=adapter),'shutil':commands,'socket':network,'ganesha_run_dir':str(run),
                'root_maintenance_nfs_boundary':lambda:False,'stop_owned_rendered_ganesha':Mock(side_effect=AssertionError('unexpected stop')),'stop_default_ganesha_service':Mock(side_effect=AssertionError('default restart')),'stop_ganesha_endpoints':Mock(),
                'ensure_ganesha_runtime_dirs':Mock(),'ensure_rpcbind_started':lambda:True,'managed_ganesha_process':lambda pid:True,
                'process_exists':lambda pid:True,'tail_file':lambda *args:''}
            exec(compile(ast.Module(body=definitions,type_ignores=[]),str(cli),'exec'),ns)
            result=ns['start_ganesha_endpoints']([(key,str(conf),'10.10.13.240',2049,'V4_ONLY')])
            self.assertEqual('DBUS_DYNAMIC_UPDATE_VERIFIED',result[0]['activationStrategy']);self.assertFalse(result[0]['restartPerformed'])
            self.assertEqual(before,pidfile.stat());adapter.transition.assert_called_once()
            self.assertEqual(['show'],[call.args[0][1] for call in process.run.call_args_list])

    def test_legacy_bus_fallback_requires_same_pid_and_prefix_only_change_keeps_global_policy(self):
        adapter=GaneshaDbus();adapter.owned_manager=Mock(side_effect=[ValueError('unknown dedicated name'),'manager'])
        name,manager=adapter.owned_endpoint_manager('10.10.13.240',2049,123)
        self.assertEqual('org.ganesha.nfsd',name);self.assertEqual('manager',manager)
        self.assertEqual([123,123],[call.args[1] for call in adapter.owned_manager.call_args_list])
        adapter.owned_manager=Mock(side_effect=ValueError('foreign PID'))
        with self.assertRaises(ValueError):adapter.owned_endpoint_manager('10.10.13.240',2049,123)
        content='NFS_Core_Param {\n NFS_Port = 2049;\n Protocols = 4;\n}\n'
        prefixed=content.replace(' Protocols',' Dbus_Name_Prefix = "org.ablestack.storage.ganesha.e'+'a'*24+'";\n Protocols')
        self.assertEqual(self.adapter.configuration(content)['globalSha256'],self.adapter.configuration(prefixed)['globalSha256'])
        with self.assertRaises(ValueError):self.adapter.configuration(prefixed.replace('org.ablestack.storage.ganesha','org.foreign'))

    def test_export_removal_requires_exact_root_maintenance_and_preflights_before_owned_stop(self):
        cli=LIB.parents[5]/'systemvm/debian/usr/local/bin/ablestack-storagectl'
        tree=ast.parse(next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S) if 'def rendered_ganesha_dynamic_candidates(' in value))
        node=next(item for item in tree.body if isinstance(item,ast.FunctionDef) and item.name=='rendered_ganesha_dynamic_candidates')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'nfs').mkdir();(root/'nfs/manifest.json').write_text(json.dumps({'endpoints':[{'legacyUnitKey':'owned','configurationPath':'nfs/owned.conf'}]}))
            stop=Mock();ns={'rendered_context':(root,{}),'rendered_from_context':(root,{}),'json':json,'Path':Path,'time':time,'os':os,'ganesha_conf_dir':'/etc/ganesha/ablestack-storage','GaneshaDbus':Mock(),'stop_owned_rendered_ganesha':stop,'root_maintenance_nfs_boundary':lambda:False}
            exec(compile(ast.Module(body=[node],type_ignores=[]),str(cli),'exec'),ns)
            with self.assertRaises(ValueError):ns['rendered_ganesha_dynamic_candidates']([])
            stop.assert_not_called()
            ns['root_maintenance_nfs_boundary']=lambda:True
            self.assertEqual({},ns['rendered_ganesha_dynamic_candidates']([]))
            self.assertEqual(True,stop.call_args_list[0].kwargs['inspect_only']);self.assertTrue(stop.call_args_list[1].kwargs['remove_configuration'])
            self.assertEqual(2,stop.call_count)

    def test_same_path_with_stale_client_access_or_squash_never_passes_dynamic_readback(self):
        desired={'exportId':1001,'path':'/export/share','pseudo':'/share','fsal':'VFS','mountPathPseudo':False,
                 'clients':[{'client':'10.10.0.0/16','options':0x1e2,'anonUid':65534,'anonGid':65534}]}
        self.exports[1001]={**desired,'clientRows':[('10.10.0.0/16',4,0,0,4,65534,65534,0,0x1e2,0x1ff)]}
        self.assertTrue(self.adapter.verify(self.adapter.owned_manager(self.name,123),[desired]))
        for options in (0xa2,0x1e0):
            self.exports[1001]['clientRows']=[('10.10.0.0/16',4,0,0,4,65534,65534,0,options,0x1ff)]
            with self.assertRaisesRegex(ValueError,'access/squash'):self.adapter.verify(self.adapter.owned_manager(self.name,123),[desired])

    def test_100_export_dbus_readback_shares_one_fake_clock_deadline(self):
        self.exports={1000+index:{'exportId':1000+index,'path':'/export/share'+str(index),'pseudo':'/share'+str(index),'fsal':'VFS','mountPathPseudo':False} for index in range(100)}
        clock=[0.0];self.adapter.deadline=1
        original=self.adapter.call
        def timed(manager,method,*args):
            result=original(manager,method,*args);clock[0]+=.05
            return result
        self.adapter.call=timed
        with unittest.mock.patch.object(dbus_module.time,'monotonic',lambda:clock[0]):
            manager=self.adapter.owned_manager(self.name,123)
            with self.assertRaises(TimeoutError):self.adapter.verify(manager,list(self.exports.values()))
        self.assertLessEqual(clock[0],1.05);self.assertLess(len([item for item in self.calls if item[0]=='DisplayExport']),100)

    def test_100_cold_endpoint_starts_stop_at_one_shared_fake_clock_budget(self):
        cli=LIB.parents[5]/'systemvm/debian/usr/local/bin/ablestack-storagectl'
        tree=ast.parse(next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S) if 'def start_ganesha_endpoints(' in value))
        definitions=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in ('ganesha_remaining','start_ganesha_endpoints')]
        clock=[0.0];commands=[]
        def run(args,**kwargs):
            commands.append(args);clock[0]+=.1
            return SimpleNamespace(returncode=0,stdout='123',stderr='')
        process=SimpleNamespace(run=run,PIPE=-1,DEVNULL=-3)
        system=SimpleNamespace(**{name:getattr(os,name) for name in dir(os) if name!='path'})
        system.path=SimpleNamespace(isdir=lambda path:True,exists=lambda path:False,join=os.path.join)
        ns={'time':SimpleNamespace(monotonic=lambda:clock[0],time=lambda:clock[0],sleep=lambda interval:None),
            'nfs_operation_deadline':1.0,'os':system,'subprocess':process,'shutil':SimpleNamespace(which=lambda name:'/usr/bin/'+name),
            'rendered_ganesha_dynamic_candidates':lambda configs:{},'stop_default_ganesha_service':lambda:None,'stop_ganesha_endpoints':lambda **kwargs:None,
            'ensure_ganesha_runtime_dirs':lambda:None,'ensure_rpcbind_started':lambda:True,'ganesha_run_dir':'/unused','open':mock_open(),
            'tail_file':lambda *args:'','rendered_context':None}
        exec(compile(ast.Module(body=definitions,type_ignores=[]),str(cli),'exec'),ns)
        configs=[('owned'+str(index),'/unused/'+str(index)+'.conf','10.10.13.240',2049+index,'V4_ONLY') for index in range(100)]
        with self.assertRaises(TimeoutError):ns['start_ganesha_endpoints'](configs)
        self.assertLessEqual(clock[0],1.1);self.assertLess(len([row for row in commands if len(row)>1 and row[1]=='restart']),100)

if __name__=='__main__':unittest.main()
