import contextlib
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
import ast,copy,json,os,re,sys,tempfile,time,unittest,uuid
from unittest.mock import Mock,mock_open
from types import SimpleNamespace
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import ganesha_dbus as dbus_module
from ganesha_dbus import GaneshaDbus
from nfs_readiness import NfsEndpointReadiness
from native_renderers import render_nfs_candidate

class MissingBusName(Exception):
    def get_dbus_name(self):return 'org.freedesktop.DBus.Error.NameHasNoOwner'


class EndpointBus:
    """Model the locked Ganesha consumer, with separate per-name processes."""
    def __init__(self):self.endpoints={};self.calls=[]
    def register(self,content,pid):
        prefix=re.search(r'Dbus_Name_Prefix\s*=\s*"([^"]+)"',content)
        prefix=prefix[1] if prefix else ''
        # src/dbus/dbus_server.c:107..123 in locked 2a57b6d: dots reject.
        name=prefix+'.org.ganesha.nfsd' if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',prefix) else 'org.ganesha.nfsd'
        self.endpoints[name]={'pid':pid,'exports':{row['exportId']:row for row in GaneshaDbus.configuration(content)['exports']}}
        return name
    def get_object(self,name,path):
        bus=self
        class Object:
            def get_dbus_method(self,method,interface):
                def call(*args,**kwargs):
                    bus.calls.append((name,method,args))
                    if method=='GetConnectionUnixProcessID':
                        if args[0] not in bus.endpoints:raise MissingBusName()
                        return bus.endpoints[args[0]]['pid']
                    rows=bus.endpoints[name]['exports']
                    if method=='ShowExports':return ((0,0),[(key,row['pseudo'] if row['mountPathPseudo'] else row['path']) for key,row in rows.items()])
                    if method=='DisplayExport':
                        row=rows[args[0]]
                        clients=[(client['client'],4,0,0,4,client['anonUid'] or 0,client['anonGid'] or 0,0,client['options'],0x1ff) for client in row['clients']]
                        return (row['exportId'],row['path'],row['pseudo'] if row['mountPathPseudo'] else row['path'],'',clients)
                    if method=='UpdateExport':
                        identifier=int(re.fullmatch(r'EXPORT\(Export_Id=([0-9]+)\)',args[1])[1])
                        parsed=GaneshaDbus.configuration(Path(args[0]).read_text())['exports']
                        rows[identifier]=next(row for row in parsed if row['exportId']==identifier)
                        return '1 exports updated'
                    raise AssertionError(method)
                return call
        return Object()


def multiport_configuration():
    payload={'enabled':True,'protocolMode':'V4_ONLY','idMappingMode':'NUMERIC','listenIp':'0.0.0.0','port':2049,'exports':[]}
    for number in range(1,101):
        name='local-'+str(number).zfill(3)
        payload['exports'].append({'uuid':str(uuid.uuid5(uuid.NAMESPACE_OID,name)),'name':name,'exportPath':'/'+name,
            'path':'/export/'+name,'state':'Ready','config':{'endpointMode':'LISTENER_GROUP',
            'listenerGroupPorts':[2049,20490] if number==1 else [2049],'volumeMountPath':'/srv/local/data',
            'backingPath':'/srv/local/data/'+name,'relativeSharePath':name,'anonUid':65534,'anonGid':65534},
            'acls':[{'principal':'192.0.2.0/24','permission':'READ_WRITE','state':'Ready'}]})
    files=render_nfs_candidate(payload,LIB.parents[5]/'systemvm/debian/usr/local/bin/ablestack-storagectl')
    return {row['port']:files[row['configurationPath']] for row in json.loads(files['nfs/manifest.json'])['endpoints']}


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
                    if name=='UpdateExport':return '1 exports updated'
                    raise AssertionError(name)
                return call
        class Bus:
            def get_object(self,*args):return Object()
        self.adapter=GaneshaDbus(Bus());self.previous=list(self.exports.values());self.name='org.ganesha.nfsd'

    def test_exact_owned_dynamic_update_rechecks_inventory_and_never_restarts_a_process(self):
        result=self.adapter.update(self.name,123,'/immutable/candidate.conf',self.previous,self.previous)
        self.assertEqual('DBUS_DYNAMIC_UPDATE_VERIFIED',result['activationStrategy']);self.assertFalse(result['restartPerformed'])
        self.assertEqual(0,len([call for call in self.calls if call[0]=='UpdateExport']));self.assertTrue(all(0<call[2]<=5 for call in self.calls))
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
        with self.assertRaises(ValueError):self.adapter.update(self.name,123,'/candidate',self.previous,[{**self.previous[0],'clients':[]}])

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
        with self.assertRaisesRegex(ValueError,"immutable"):
            self.adapter.transition(self.name,123,'/candidate',config,config.replace('RO','RW'))
        self.assertFalse(self.calls)

    def test_installed_nfs_rendered_path_keeps_a_known_running_endpoint_without_restart(self):
        cli=LIB.parents[5]/'systemvm/debian/usr/local/bin/ablestack-storagectl'
        blocks=re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)
        tree=ast.parse(next(value for value in blocks if 'def rendered_ganesha_dynamic_candidates(' in value))
        names={'rendered_ganesha_dynamic_candidates','start_ganesha_endpoints'}
        definitions=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in names]
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);source=directory/'source';source.mkdir();(source/'nfs').mkdir();run=directory/'run';run.mkdir()
            key='known';conf=directory/'known.conf'
            config='NFS_Core_Param {\n NFS_Port = 2049;\n Dbus_Name_Prefix = "ablestack_storage_ganesha_eaaaaaaaaaaaaaaaaaaaaaaaa";\n}\nEXPORT {\n Export_Id = 1001;\n Path = "/export/share";\n Pseudo = "/share";\n FSAL {\n Name = VFS;\n }\n}\n'
            old_config=config.replace('ablestack_storage_ganesha_e','org.ablestack.storage.ganesha.e')
            conf.write_text(config);(source/'nfs/manifest.json').write_text(json.dumps({'endpoints':[{'legacyUnitKey':key,'configurationPath':'nfs/known.conf'}]}));(source/'nfs/known.conf').write_text(old_config)
            pidfile=run/'known.pid';pidfile.write_text('123');before=pidfile.stat()
            class ProcessPath:
                def read_bytes(self):return b'/usr/bin/ganesha.nfsd'+bytes([0])+b'-f'+bytes([0])+str(conf).encode()+bytes([0])
            def paths(value):return ProcessPath() if str(value).startswith('/proc/') else Path(value)
            process=Mock();process.run.return_value=Mock(returncode=0,stdout='123')
            bus=EndpointBus();custom_name=bus.register(multiport_configuration()[20490],456);bus.register(old_config,123)
            adapter=GaneshaDbus(bus)
            commands=Mock();commands.which.side_effect=lambda name:'/usr/bin/ganesha.nfsd' if name=='ganesha.nfsd' else None
            network=Mock();network.create_connection.return_value.__enter__=Mock();network.create_connection.return_value.__exit__=Mock()
            class Adapter(GaneshaDbus):
                def __init__(self,**kwargs):super().__init__(bus,**kwargs)
            readiness=NfsEndpointReadiness(time.monotonic()+30,Adapter,Mock(),config_root=directory)
            identity={"pid":123,"startTicks":"456","configurationSha256":"a"*64}
            readiness.snapshot=Mock(return_value=(identity,config))
            readiness.reuse=Mock(return_value=None);readiness.remember=Mock();readiness.listener_owned=Mock(return_value=True);readiness.wait=Mock(wraps=readiness.wait)
            ns={'NfsEndpointReadiness':Mock(return_value=readiness),'nfs_control_command':Mock(),'nfs_operation_deadline':time.monotonic()+30,'ganesha_remaining':lambda maximum=10:maximum,'rendered_context':(source,{}),'rendered_from_context':(source,{}),'Path':paths,'json':json,'os':os,'time':time,
                'subprocess':process,'GaneshaDbus':Mock(return_value=adapter),'shutil':commands,'socket':network,'ganesha_run_dir':str(run),
                'root_maintenance_nfs_boundary':lambda:False,'stop_owned_rendered_ganesha':Mock(side_effect=AssertionError('unexpected stop')),'stop_default_ganesha_service':Mock(side_effect=AssertionError('default restart')),'stop_ganesha_endpoints':Mock(),
                'ensure_ganesha_runtime_dirs':Mock(),'ensure_rpcbind_started':lambda:True,'managed_ganesha_process':lambda pid:True,
                'process_exists':lambda pid:True,'tail_file':lambda *args:''}
            exec(compile(ast.Module(body=definitions,type_ignores=[]),str(cli),'exec'),ns)
            result=ns['start_ganesha_endpoints']([(key,str(conf),'10.10.13.240',2049,'V4_ONLY')])
            self.assertEqual('DBUS_DYNAMIC_UPDATE_VERIFIED',result[0]['activationStrategy']);self.assertFalse(result[0]['restartPerformed'])
            self.assertEqual(before,pidfile.stat())
            self.assertEqual([],[name for name,method,_ in bus.calls if method=='UpdateExport'])
            self.assertEqual({'org.ganesha.nfsd',custom_name},set(bus.endpoints))
            self.assertFalse(any(name==custom_name for name,method,_ in bus.calls if method!='GetConnectionUnixProcessID'))
            self.assertEqual(6,readiness.snapshot.call_count);readiness.wait.assert_called_once()
            self.assertEqual(['show'],[call.args[0][1] for call in process.run.call_args_list])

    def test_legacy_bus_fallback_requires_absent_managed_name_and_same_pid(self):
        adapter=GaneshaDbus(bus=object());adapter.owned_manager=Mock(side_effect=[MissingBusName(),'manager'])
        name,manager=adapter.owned_endpoint_manager('192.0.2.10',2049,123)
        self.assertEqual('org.ganesha.nfsd',name);self.assertEqual('manager',manager)
        self.assertEqual([123,123],[call.args[1] for call in adapter.owned_manager.call_args_list])
        for error in (ValueError('foreign PID'),TimeoutError('timeout'),RuntimeError('unknown transport')):
            adapter.owned_manager=Mock(side_effect=[error,'legacy same PID'])
            with self.assertRaises(type(error)):adapter.owned_endpoint_manager('192.0.2.10',2049,123)
            self.assertEqual(1,adapter.owned_manager.call_count)
        adapter.owned_manager=Mock(side_effect=[MissingBusName(),ValueError('foreign legacy PID')])
        with self.assertRaises(ValueError):adapter.owned_endpoint_manager('192.0.2.10',2049,123)

    def test_prefix_compatibility_is_closed_config_only_and_does_not_hide_global_policy(self):
        content='NFS_Core_Param {\n NFS_Port = 2049;\n Protocols = 4;\n}\n'
        values=[content]+[content.replace(' Protocols',' Dbus_Name_Prefix = "'+prefix+'";\n Protocols')
                         for prefix in ('org.ablestack.storage.ganesha.e'+'a'*24,'ablestack_storage_ganesha_e'+'a'*24)]
        self.assertEqual(1,len({self.adapter.configuration(value)['globalSha256'] for value in values}))
        for prefix in ('org.foreign.e'+'a'*24,'ablestack_storage_ganesha_e'+'a'*23,
                       'ablestack_storage_ganesha_e'+'g'*24,'ablestack_storage_ganesha_e'+'a'*24+'.bad'):
            with self.assertRaises(ValueError):self.adapter.configuration(content.replace(' Protocols',' Dbus_Name_Prefix = "'+prefix+'";\n Protocols'))
        with self.assertRaises(ValueError):self.adapter.configuration(values[1].replace(' Protocols',' Dbus_Name_Prefix = "ablestack_storage_ganesha_e'+'a'*24+'";\n Protocols'))
        self.assertNotEqual(self.adapter.configuration(values[1])['globalSha256'],
                            self.adapter.configuration(values[2].replace('2049','20490'))['globalSha256'])
        with self.assertRaises(ValueError):
            self.adapter.owned_manager('org.ablestack.storage.ganesha.e'+'a'*24+'.org.ganesha.nfsd',123)

    def test_actual_multiport_serializer_satisfies_locked_consumer_prefix_and_name_contract(self):
        contents=multiport_configuration();names=set()
        self.assertEqual({2049,20490},set(contents))
        for port,content in contents.items():
            prefix=re.findall(r'Dbus_Name_Prefix\s*=\s*"([^"]+)"',content)
            self.assertEqual(1,len(prefix))
            self.assertRegex(prefix[0],r'^[A-Za-z_][A-Za-z0-9_]*$')
            self.assertEqual(prefix[0]+'.org.ganesha.nfsd',self.adapter.name('0.0.0.0',port))
            names.add(prefix[0]+'.org.ganesha.nfsd')
        self.assertEqual(2,len(names))

    def test_real_readiness_uses_separate_owned_buses_for_100_and_one_export(self):
        contents=multiport_configuration();bus=EndpointBus()
        for port,content in contents.items():bus.register(content,123 if port==2049 else 456)
        readiness=NfsEndpointReadiness(time.monotonic()+30,lambda **kw:GaneshaDbus(bus,**kw),Mock())
        for port,count in ((2049,100),(20490,1)):
            pid=123 if port==2049 else 456;content=contents[port]
            readiness.snapshot=Mock(return_value=({'pid':pid,'configurationSha256':'a'*64},content));readiness.listener_owned=Mock(return_value=True)
            endpoint={'listenIp':'0.0.0.0','port':port,'pid':pid,'protocolMode':'V4_ONLY'}
            result=readiness.wait(endpoint,Path('/unused/'+str(port)+'.conf'))
            self.assertTrue(result['exportInventoryReady']);self.assertEqual(count,len(result['exportBindings']))
            self.assertEqual(2,readiness.snapshot.call_count)
        displayed=[(name,args[0]) for name,method,args in bus.calls if method=='DisplayExport']
        self.assertEqual(100,sum(name==self.adapter.name('0.0.0.0',2049) for name,_ in displayed))
        self.assertEqual(1,sum(name==self.adapter.name('0.0.0.0',20490) for name,_ in displayed))

    def test_multiport_exact_readback_rejects_extra_missing_path_pseudo_and_acl_drift(self):
        contents=multiport_configuration();bus=EndpointBus()
        for port,content in contents.items():bus.register(content,123 if port==2049 else 456)
        adapter=GaneshaDbus(bus);name,manager=adapter.owned_endpoint_manager('0.0.0.0',2049,123)
        wanted=adapter.configuration(contents[2049])['exports'];original=copy.deepcopy(bus.endpoints[name]['exports'])
        identifier=next(iter(original))
        def changed(field,value):
            rows=copy.deepcopy(original);rows[identifier][field]=value;return rows
        missing=copy.deepcopy(original);missing.pop(identifier)
        extra=copy.deepcopy(original);extra[65534]={**original[identifier],'exportId':65534}
        clients=copy.deepcopy(original[identifier]['clients']);clients[0]['options']=0xa2
        uid=copy.deepcopy(original[identifier]['clients']);uid[0]['anonUid']=1
        for label,drift in (('missing',missing),('extra',extra),('path',changed('path','/export/foreign')),
                            ('access/squash',changed('clients',clients)),('anonymous uid',changed('clients',uid))):
            with self.subTest(drift=label):
                bus.endpoints[name]['exports']=drift
                with self.assertRaises(ValueError):adapter.verify(manager,wanted)
        # DisplayExport exposes Pseudo only with mount_path_pseudo. V4_ONLY
        # visibility is separately established by the unchanged mount probe.
        pseudo=copy.deepcopy(original)
        for row in pseudo.values():row['mountPathPseudo']=True
        wanted_pseudo=copy.deepcopy(list(pseudo.values()));pseudo[identifier]['pseudo']='/foreign'
        bus.endpoints[name]['exports']=pseudo
        with self.assertRaises(ValueError):adapter.verify(manager,wanted_pseudo)
        bus.endpoints[name]['exports']=original
        _,custom=adapter.owned_endpoint_manager('0.0.0.0',20490,456)
        with self.assertRaises(ValueError):adapter.verify(custom,wanted)
        self.assertFalse(any(method=='UpdateExport' for _,method,_ in bus.calls))

    def test_foreign_managed_owner_cannot_hide_behind_an_exact_legacy_owner(self):
        content=multiport_configuration()[2049];bus=EndpointBus();name=bus.register(content,456)
        bus.endpoints['org.ganesha.nfsd']={'pid':123,'exports':copy.deepcopy(bus.endpoints[name]['exports'])}
        adapter=GaneshaDbus(bus)
        with self.assertRaises(ValueError):adapter.owned_endpoint_manager('0.0.0.0',2049,123)
        self.assertEqual([name],[args[0] for _,method,args in bus.calls if method=='GetConnectionUnixProcessID'])
        self.assertFalse(any(method=='UpdateExport' for _,method,_ in bus.calls))

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
        system.environ=dict(os.environ,ABLESTACK_STORAGE_RENDERED_REPLAY='fixture-rendered')
        ns={'Path':lambda value:SimpleNamespace(exists=lambda:False),'time':SimpleNamespace(monotonic=lambda:clock[0],time=lambda:clock[0],sleep=lambda interval:None),
            'NfsEndpointReadiness':Mock(return_value=Mock(reuse=Mock(return_value=None))),'GaneshaDbus':Mock(),'nfs_control_command':Mock(),'nfs_operation_deadline':1.0,'contextlib':contextlib,'pending_nfs':None,'os':system,'subprocess':process,'shutil':SimpleNamespace(which=lambda name:'/usr/bin/'+name),
            'rendered_ganesha_dynamic_candidates':lambda configs:{},'stop_default_ganesha_service':lambda:None,'stop_ganesha_endpoints':lambda **kwargs:None,
            'ensure_ganesha_runtime_dirs':lambda:None,'ensure_rpcbind_started':lambda:True,'ganesha_run_dir':'/unused','open':mock_open(),
            'tail_file':lambda *args:'','rendered_context':None}
        exec(compile(ast.Module(body=definitions,type_ignores=[]),str(cli),'exec'),ns)
        configs=[('owned'+str(index),'/unused/'+str(index)+'.conf','10.10.13.240',2049+index,'V4_ONLY') for index in range(100)]
        with self.assertRaises(TimeoutError):ns['start_ganesha_endpoints'](configs)
        self.assertLessEqual(clock[0],1.1);self.assertLess(len([row for row in commands if len(row)>1 and row[1]=='restart']),100)

class StorageGaneshaPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.config=self.root/'configs';self.config.mkdir(mode=0o700)
        self.directory=self.root/'policy';self.directory.mkdir(mode=0o700);self.path=self.directory/'ablestack-storage-ganesha-endpoints.conf'
        self.calls=[];self.bus=Mock()
        self.bus.get_object.return_value.get_dbus_method.return_value.side_effect=lambda **kw:self.calls.append(('reload',kw)) or None
        self.policy_type=getattr(dbus_module,'GaneshaDbusPolicy',None)
        self.assertIsNotNone(self.policy_type,'NFS activation requires an exact endpoint policy preparer')
        self.policy=self.policy_type(config_root=self.config,policy_path=self.path,bus=self.bus)
        # Files remain owned by the real test uid; only the explicit root-entry
        # precondition is isolated so private filesystem tests are portable.
        self.root_check=unittest.mock.patch.object(self.policy,'require_root',return_value=None)
        self.root_check.start();self.addCleanup(self.root_check.stop)
    def name(self,port):return GaneshaDbus(bus=object()).name('0.0.0.0',port)
    def source_config(self):
        p=self.config/'0.0.0.0_2049.conf';p.write_text(multiport_configuration()[2049]);p.chmod(0o600);return p
    def test_root_requirement_and_expired_deadline_refuse_before_publication(self):
        with unittest.mock.patch('os.geteuid',return_value=1):
            with self.assertRaises(ValueError):self.policy_type.require_root()
        self.policy.deadline=time.monotonic()-1
        with self.assertRaises(TimeoutError):self.policy.prepare([('0.0.0.0',2049)])
        self.assertFalse(self.path.exists());self.assertEqual([],self.calls)
    def test_source_target_and_previous_policy_names_are_preserved_with_exact_root_permissions(self):
        self.source_config();self.path.write_bytes(self.policy.render({self.name(2050)}));self.path.chmod(0o644)
        result=self.policy.prepare([('0.0.0.0',20490)])
        self.assertEqual(3,result['endpointNameCount']);self.assertTrue(result['configurationChanged']);self.assertEqual(1,len(self.calls))
        import xml.etree.ElementTree as ET
        root=ET.fromstring(self.path.read_bytes());self.assertEqual(2,len(root))
        for kind,scope,verb in [('deny',{'context':'default'},'deny'),('allow',{'user':'root'},'allow')]:
            p=next(p for p in root if p.attrib==scope)
            self.assertEqual({self.name(x)for x in (2049,20490,2050)},{x.attrib['own']for x in p if 'own'in x.attrib})
            self.assertEqual({self.name(x)for x in (2049,20490,2050)},{x.attrib['send_destination']for x in p if 'send_destination'in x.attrib})
            self.assertTrue(all(x.tag==verb and len(x.attrib)==1 for x in p))
        self.assertNotIn(b'own_prefix',self.path.read_bytes());self.assertNotIn(b'="*"',self.path.read_bytes())
    def test_absent_policy_is_complete_and_durable_before_reload(self):
        seen=[]
        def reload(**kwargs):
            seen.append(self.path.read_bytes())
            self.assertEqual({self.name(2049),self.name(20490)},self.policy.parse(seen[-1]))
        self.bus.get_object.return_value.get_dbus_method.return_value.side_effect=reload
        r=self.policy.prepare([('0.0.0.0',2049),('0.0.0.0',20490)])
        self.assertTrue(r['reloaded']);self.assertEqual(1,len(seen));self.assertEqual(0o644,self.path.stat().st_mode&0o777)
    def test_foreign_policy_symlink_unprotected_mode_and_hardlink_are_refused(self):
        import os
        for shape in ('foreign','symlink','writeable','hardlink'):
            with self.subTest(shape=shape):
                target=self.root/'foreign';target.write_bytes(b'foreign-preserve')
                if shape=='symlink':self.path.symlink_to(target)
                elif shape=='foreign':self.path.write_bytes(b'<busconfig/>')
                else:
                    self.path.write_bytes(self.policy.render({self.name(2049)}))
                    if shape=='writeable':self.path.chmod(0o666)
                    else:os.link(self.path,self.root/'second-link')
                before=self.path.read_bytes()
                with self.assertRaises((ValueError,RuntimeError,OSError)):self.policy.prepare([('0.0.0.0',20490)])
                self.assertEqual(before,self.path.read_bytes());self.assertEqual([],self.calls);self.path.unlink()
                (self.root/'second-link').unlink(missing_ok=True)
    def test_changed_file_before_atomic_publish_is_not_overwritten(self):
        self.path.write_bytes(self.policy.render({self.name(2049)}));self.path.chmod(0o644)
        import tempfile as temporary
        original=temporary.mkstemp
        def race(*args,**kwargs):
            result=original(*args,**kwargs);self.path.write_bytes(b'foreign-race');return result
        with unittest.mock.patch.object(temporary,'mkstemp',side_effect=race):
            with self.assertRaises((ValueError,RuntimeError)):self.policy.prepare([('0.0.0.0',20490)])
        self.assertEqual(b'foreign-race',self.path.read_bytes());self.assertEqual([],self.calls)
    def test_absent_publish_race_is_no_clobber(self):
        import tempfile as temporary
        original=temporary.mkstemp
        def race(*args,**kwargs):
            result=original(*args,**kwargs);self.path.write_bytes(b'foreign-created');return result
        with unittest.mock.patch.object(temporary,'mkstemp',side_effect=race):
            with self.assertRaises((ValueError,RuntimeError,OSError)):self.policy.prepare([('0.0.0.0',2049)])
        self.assertEqual(b'foreign-created',self.path.read_bytes());self.assertEqual([],self.calls)
    def test_reload_failure_restores_old_file_before_second_reload(self):
        old=self.policy.render({self.name(2049)});self.path.write_bytes(old);self.path.chmod(0o600)
        self.bus.get_object.return_value.get_dbus_method.return_value.side_effect=[RuntimeError('injected'),None]
        with self.assertRaises(RuntimeError):self.policy.prepare([('0.0.0.0',20490)])
        self.assertEqual(old,self.path.read_bytes());self.assertEqual(0o600,self.path.stat().st_mode&0o777)
        self.assertEqual(2,self.bus.get_object.return_value.get_dbus_method.return_value.call_count)
    def test_failed_first_reload_removes_only_its_own_new_file(self):
        self.bus.get_object.return_value.get_dbus_method.return_value.side_effect=[RuntimeError('injected'),None]
        with self.assertRaises(RuntimeError):self.policy.prepare([('0.0.0.0',2049)])
        self.assertFalse(self.path.exists());self.assertEqual(2,self.bus.get_object.return_value.get_dbus_method.return_value.call_count)
    def test_failed_reload_does_not_delete_replacement_foreign_file(self):
        def fail(**kwargs):self.path.write_bytes(b'foreign-after-reload');raise RuntimeError('injected')
        self.bus.get_object.return_value.get_dbus_method.return_value.side_effect=fail
        with self.assertRaisesRegex(RuntimeError,'recovery required'):self.policy.prepare([('0.0.0.0',2049)])
        self.assertEqual(b'foreign-after-reload',self.path.read_bytes());self.assertEqual(1,self.bus.get_object.return_value.get_dbus_method.return_value.call_count)
    def test_endpoint_bound_bad_types_and_foreign_config_prefix_refuse_before_publish(self):
        for values in ([('0.0.0.0',True)],[('0.0.0.0',0)],[('not-an-ip',2049)],
                       [('0.0.0.0',2000+i)for i in range(self.policy.MAX_NAMES+1)]):
            with self.subTest(values=len(values)),self.assertRaises((ValueError,RuntimeError)):self.policy.prepare(values)
        p=self.source_config();p.write_text(p.read_text().replace('e43724257a40a01a4c054fe31','e'+'a'*24))
        with self.assertRaises(ValueError):self.policy.prepare([('0.0.0.0',20490)])
        self.assertFalse(self.path.exists());self.assertEqual([],self.calls)
    def test_repeat_prepare_keeps_inode_and_policy_bytes(self):
        self.policy.prepare([('0.0.0.0',2049)])
        before=self.path.stat();raw=self.path.read_bytes()
        r=self.policy.prepare([('0.0.0.0',2049)])
        self.assertFalse(r['configurationChanged']);self.assertEqual(before,self.path.stat());self.assertEqual(raw,self.path.read_bytes())
    def test_policy_lock_symlink_or_busy_refuses_without_reload(self):
        import fcntl
        lock=self.path.with_name('.'+self.path.name+'.lock');other=self.root/'other';other.write_bytes(b'preserve');lock.symlink_to(other)
        with self.assertRaises((ValueError,RuntimeError,OSError)):self.policy.prepare([('0.0.0.0',2049)])
        self.assertEqual(b'preserve',other.read_bytes());lock.unlink();lock.touch(mode=0o600)
        with lock.open('r+')as fd:
            fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with self.assertRaises((ValueError,RuntimeError,OSError)):self.policy.prepare([('0.0.0.0',2049)])
        self.assertFalse(self.path.exists());self.assertEqual([],self.calls)
    def test_pure_render_and_readiness_never_prepare_global_policy(self):
        with unittest.mock.patch.object(self.policy_type,'prepare',side_effect=AssertionError('readonly path wrote policy')):
            contents=multiport_configuration();bus=EndpointBus()
            for port,content in contents.items():bus.register(content,123 if port==2049 else 456)
            a=GaneshaDbus(bus);_,manager=a.owned_endpoint_manager('0.0.0.0',2049,123)
            self.assertTrue(a.verify(manager,a.configuration(contents[2049])['exports']))
        self.assertFalse(self.path.exists());self.assertEqual([],self.calls)
    def test_actual_cli_activation_prepares_policy_before_config_write_and_service_start(self):
        cli=LIB.parents[5]/'systemvm/debian/usr/local/bin/ablestack-storagectl'
        tree=ast.parse(next(x for x in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S)if'def start_ganesha_endpoints('in x))
        attempt=next(x for x in tree.body if isinstance(x,ast.Try)and any(isinstance(n,ast.Call)and isinstance(n.func,ast.Name)and n.func.id=='start_ganesha_endpoints'for n in ast.walk(x)))
        body=[]
        for statement in attempt.body:
            body.append(statement)
            if any(isinstance(n,ast.Call)and isinstance(n.func,ast.Name)and n.func.id=='start_ganesha_endpoints'for n in ast.walk(statement)):break
        events=[];prepared=Mock();prepared.prepare.side_effect=lambda *a:events.append('policy')
        ns={'payload':{},'GaneshaDbusPolicy':Mock(return_value=prepared),'nfs_operation_deadline':time.monotonic()+30,
            'endpoint_exports':{('0.0.0.0',2049):[]},'enabled':True,'default_port':2049,'configs':[],
            'BootCoordinator':SimpleNamespace(attach_existing=lambda:None),
            'write_ganesha_configs':lambda *a:events.append('configs')or[],
            'start_ganesha_endpoints':lambda *a:events.append('start')or[]}
        code=compile(ast.Module(body=body,type_ignores=[]),str(cli),'exec');exec(code,ns)
        self.assertEqual(['policy','configs','start'],events)
        events.clear();prepared.prepare.side_effect=RuntimeError('reload failed')
        with self.assertRaises(RuntimeError):exec(code,ns)
        self.assertEqual([],events)

if __name__=='__main__':unittest.main()
