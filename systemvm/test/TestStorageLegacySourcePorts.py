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


"""Regression coverage for legacy SOURCE imports and shared TARGET port rendering."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/'systemvm/debian/usr/local/lib/ablestack-storage'
sys.path.insert(0,str(LIB))
from native_renderers import render_block_candidate,render_nfs_candidate
from native_render_runtime import PROTOCOL_FILES
from rendered_driver import RenderedDriver
from rendered_generation import DESIRED_PATHS,DOMAINS,RenderedGeneration

CLI=ROOT/'systemvm/debian/usr/local/bin/ablestack-storagectl'


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()


class StorageLegacySourcePortsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)

    def fixture(self,protocol):
        port=3260 if protocol=='ISCSI' else 4420
        prefix='iqn' if protocol=='ISCSI' else 'nqn'
        item={'uuid':str(uuid.uuid4()),'volumeUuid':str(uuid.uuid4()),'volumeSizeBytes':2*(1<<30),
              'lunOrNamespace':0 if protocol=='ISCSI' else 1,'listenerPorts':str(port),
              'config':{'listenerGroupPorts':[port]}}
        resource={**item,'targetName':prefix+'.2026-10.local.storage:legacy'}
        if protocol=='NVMEOF':
            resource={'uuid':str(uuid.uuid4()),'targetName':prefix+'.2026-10.local.storage:legacy','namespaces':[item]}
        payload={'enabled':True,'listeners':[{'listenIp':'0.0.0.0','port':port},{'listenIp':'10.10.13.228','port':port+1}],
                 'targets' if protocol=='ISCSI' else 'subsystems':[resource]}
        return payload,item if protocol=='NVMEOF' else resource

    def render(self,payload,protocol):
        def resolve(item):
            return {'devicePath':'/dev/test-'+item['volumeUuid'],'matchedBy':'VOLUME_SERIAL',
                    'serial':item['volumeUuid'].replace('-','')[:20],'sizeBytes':item['volumeSizeBytes'],
                    'mounted':False,'rootDevice':False}
        before=canonical(payload)
        try:
            files=render_block_candidate(payload,protocol,resolve)
            return json.loads(files['block/iscsi-plan.json' if protocol=='ISCSI' else 'block/nvmeof-plan.json'])
        finally:self.assertEqual(before,canonical(payload),'Rendering modified the canonical request')

    def test_actual_legacy_csv_and_canonical_array_produce_the_same_bounded_endpoints(self):
        for protocol in ('ISCSI','NVMEOF'):
            with self.subTest(protocol=protocol):
                payload,item=self.fixture(protocol);port=item['config']['listenerGroupPorts'][0]
                legacy=self.render(payload,protocol);item['listenerPorts']=[port]
                current=self.render(payload,protocol)
                self.assertEqual(legacy,current)
                self.assertEqual([{'listenIp':'0.0.0.0','port':port}],legacy['targets'][0]['listeners'])

    def test_legacy_aliases_and_csv_order_are_checked_without_mutation(self):
        for protocol in ('ISCSI','NVMEOF'):
            for container,key in (('item','listenerPorts'),('item','listenerports'),
                                  ('config','listenerGroupPorts'),('config','listenergroupports'),
                                  ('config','listenerPorts'),('config','listenerports')):
                with self.subTest(protocol=protocol,container=container,key=key):
                    payload,item=self.fixture(protocol);port=item['config']['listenerGroupPorts'][0]
                    item.pop('listenerPorts');item['config']={}
                    location=item if container=='item' else item['config']
                    location[key]=' '+str(port+1)+' , '+str(port)+' '
                    item['config']['listenerGroupPorts']=[port,port+1]
                    plan=self.render(payload,protocol)
                    self.assertEqual({port,port+1},{row['port'] for row in plan['targets'][0]['listeners']})

    def test_empty_legacy_aliases_fall_back_to_nonempty_config_and_absence_keeps_all_listeners(self):
        for protocol in ('ISCSI','NVMEOF'):
            for absent in (None,'','   ',[]):
                with self.subTest(protocol=protocol,absent=absent):
                    payload,item=self.fixture(protocol);port=item['config']['listenerGroupPorts'][0]
                    item['listenerPorts']=absent
                    self.assertEqual({port},{row['port'] for row in self.render(payload,protocol)['targets'][0]['listeners']})
                    item['config']['listenerGroupPorts']=absent
                    self.assertEqual({port,port+1},{row['port'] for row in self.render(payload,protocol)['targets'][0]['listeners']})
            payload,item=self.fixture(protocol);item.pop('listenerPorts');item.pop('config')
            self.assertEqual(2,len(self.render(payload,protocol)['targets'][0]['listeners']))

    def test_wrong_types_ranges_duplicates_and_malformed_csv_are_rejected(self):
        for protocol in ('ISCSI','NVMEOF'):
            payload,item=self.fixture(protocol);port=item['config']['listenerGroupPorts'][0]
            invalid=(True,False,port,float(port),{},[True],[float(port)],[str(port)],[None],
                     [0],[65536],[-1],[port,port],'0','65536','-1','+'+str(port),
                     str(port)+',',','+str(port),str(port)+',,'+str(port+1),
                     str(port)+','+str(port),'[ '+str(port)+' ]',str(port)+'.0','1e3')
            for raw in invalid:
                with self.subTest(protocol=protocol,raw=raw):
                    item['listenerPorts']=raw
                    with self.assertRaisesRegex(ValueError,'Block listener group is invalid'):self.render(payload,protocol)
            item['listenerPorts']=[port];item['config']=['invalid']
            with self.assertRaisesRegex(ValueError,'config is invalid'):self.render(payload,protocol)

    def test_nonempty_parallel_alias_conflicts_are_rejected_even_when_both_ports_exist(self):
        for protocol in ('ISCSI','NVMEOF'):
            for container,key in (('item','listenerports'),('config','listenerGroupPorts'),
                                  ('config','listenergroupports'),('config','listenerPorts'),('config','listenerports')):
                with self.subTest(protocol=protocol,container=container,key=key):
                    payload,item=self.fixture(protocol);port=item['config']['listenerGroupPorts'][0]
                    location=item if container=='item' else item['config'];location[key]=[port+1]
                    with self.assertRaisesRegex(ValueError,'representations conflict'):self.render(payload,protocol)

    def test_invalid_parallel_alias_is_never_hidden_by_a_valid_preferred_representation(self):
        for protocol in ('ISCSI','NVMEOF'):
            for container,key in (('item','listenerports'),('config','listenerGroupPorts'),
                                  ('config','listenergroupports'),('config','listenerPorts'),('config','listenerports')):
                for invalid in ([True],['3260'],'3260,',{'port':3260}):
                    with self.subTest(protocol=protocol,container=container,key=key,invalid=invalid):
                        payload,item=self.fixture(protocol)
                        location=item if container=='item' else item['config'];location[key]=invalid
                        with self.assertRaisesRegex(ValueError,'Block listener group is invalid'):self.render(payload,protocol)

    def test_nonexistent_endpoint_is_not_replaced_by_default_or_all_listeners(self):
        for protocol in ('ISCSI','NVMEOF'):
            for raw_kind in ('csv','array'):
                with self.subTest(protocol=protocol,raw_kind=raw_kind):
                    payload,item=self.fixture(protocol);port=item['config']['listenerGroupPorts'][0]+2
                    item['listenerPorts']=str(port) if raw_kind=='csv' else [port]
                    item['config']['listenerGroupPorts']=[port]
                    with self.assertRaisesRegex(ValueError,'unavailable endpoint'):self.render(payload,protocol)

    def driver_fixture(self):
        scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':14}
        desired={name:None for name in DESIRED_PATHS};bindings=[];volumes={}
        for domain in ('NFS','SMB'):
            volume=str(uuid.uuid4());share=str(uuid.uuid4());filesystem=str(uuid.uuid4())
            mount='/srv/ablestack-storage/volumes/'+volume
            bindings.append({'volumeUuid':volume,'sizeBytes':2*(1<<30),'filesystemUuid':filesystem})
            volumes[volume]={'filesystemUuid':filesystem,'mount':mount}
            if domain=='NFS':
                payload={'instanceUuid':scope['instanceUuid'],'enabled':True,'idMappingMode':'NUMERIC',
                    'listeners':[{'listenIp':'0.0.0.0','port':2049}],
                    'exports':[{'uuid':share,'name':'legacy-nfs','exportPath':'legacy-nfs','path':'/export/legacy-nfs',
                        'config':{'backingPath':mount+'/share','volumeMountPath':mount,'relativeSharePath':'share'},
                        'acls':[{'principal':'10.10.13.0/24','permission':'READ_WRITE'}]}]}
            else:
                payload={'instanceUuid':scope['instanceUuid'],'enabled':True,
                    'listeners':[{'listenIp':'0.0.0.0','port':445}],
                    'shares':[{'uuid':share,'name':'legacy-smb','path':mount+'/share','config':{'guestOk':True}}]}
            desired[PROTOCOL_FILES[domain]]=payload
        for domain in ('ISCSI','NVMEOF'):
            payload,item=self.fixture(domain);desired[PROTOCOL_FILES[domain]]=payload
        desired['network-endpoints.json']={'endpoints':[]}
        desired['sharedfs-network.json']={'mode':'STATIC','primaryIp':'10.10.13.228'}
        checksum=hashlib.sha256(canonical(desired)).hexdigest()
        generation={**scope,'configurationSha256':checksum}
        source={'generation':generation,'pendingOperationUuid':None,'generationStatus':'IN_SYNC',
                'configurationSha256':checksum,'configurationDesiredState':copy.deepcopy(desired)}
        request={**scope,'previousGeneration':generation,'configurationDesiredState':desired,
                 'protocolDesiredState':{domain:desired[PROTOCOL_FILES[domain]] for domain in DOMAINS},
                 'fileVolumeBindings':bindings,'expectedBindings':[]}
        driver=RenderedDriver(CLI,RenderedGeneration(self.root/'render'));driver.generation=lambda:source
        driver.nfs_config_root=self.root/'legacy-ganesha';driver.nfs_config_root.mkdir(mode=0o700)
        nfs_files=render_nfs_candidate(desired[PROTOCOL_FILES['NFS']],CLI)
        for endpoint in json.loads(nfs_files['nfs/manifest.json'])['endpoints']:
            path=driver.nfs_config_root/(endpoint['legacyUnitKey']+'.conf')
            path.write_text(nfs_files[endpoint['configurationPath']]);path.chmod(0o600)
        commands=[]
        def command(args,payload=None,*unused,**kwargs):
            commands.append(tuple(args))
            if tuple(args)==('operation','root-data','inspect'):
                row=payload['volumes'][0];volume=row['volumeUuid'];file_volume=volumes.get(volume)
                return {'volumes':[{'observedDevicePath':'/dev/test-'+volume,'serial':volume.replace('-','')[:20],
                    'matchedBy':'VOLUME_SERIAL','sizeBytes':row['sizeBytes'],'mappingStatus':'EXACT',
                    'filesystemUuid':file_volume['filesystemUuid'] if file_volume else None,
                    'mounts':[{'target':file_volume['mount']}] if file_volume else []}]}
            if tuple(args)==('network','endpoints','inspect'):
                return {'sideEffects':False,'bindingsValidated':True}
            raise AssertionError('Unexpected external command: '+str(args))
        driver.runtime.command=command
        driver.validate=lambda path,manifest:{domain:True for domain in DOMAINS}
        driver.verify=lambda path:{domain:True for domain in DOMAINS}
        driver.install_boot_guards=lambda:None
        return driver,request,source,commands

    def test_real_four_domain_source_import_preserves_legacy_canonical_hash_and_bytes(self):
        # Real render, native scope guards, immutable store and import publication.
        # Only external device/network probes, parser/kernel checks and boot units
        # are substitutes: this is not a live listener or UI acceptance test.
        driver,request,source,commands=self.driver_fixture()
        original_request=canonical(request);original_source=canonical(source)
        canonical_files=self.root/'canonical';canonical_files.mkdir(mode=0o700)
        snapshots={}
        for name,value in source['configurationDesiredState'].items():
            path=canonical_files/name.replace('/','_');path.write_bytes(canonical(value));path.chmod(0o600)
            snapshots[path]=path.read_bytes()
        result=driver.execute('render-import',request)
        self.assertEqual(original_request,canonical(request));self.assertEqual(original_source,canonical(source))
        self.assertEqual(source['generation'],{**result['current']['scope'],'configurationSha256':result['current']['configurationSha256']})
        self.assertTrue(result['activation']['baselineImported'])
        self.assertIsNone(source['pendingOperationUuid'])
        imported=json.loads((driver.store.pointer()/'desired-state.json').read_text())
        self.assertEqual(source['configurationDesiredState'],imported)
        self.assertEqual(source['configurationSha256'],hashlib.sha256(canonical(imported)).hexdigest())
        for path,content in snapshots.items():self.assertEqual(content,path.read_bytes())
        for domain,filename,port in (('ISCSI','block/iscsi-plan.json',3260),('NVMEOF','block/nvmeof-plan.json',4420)):
            plan=json.loads((driver.store.pointer()/filename).read_text())
            self.assertEqual([{'listenIp':'0.0.0.0','port':port}],plan['targets'][0]['listeners'])
            payload=imported[PROTOCOL_FILES[domain]]
            item=payload['targets'][0] if domain=='ISCSI' else payload['subsystems'][0]['namespaces'][0]
            self.assertEqual(str(port),item['listenerPorts'])
        self.assertEqual(4,commands.count(('operation','root-data','inspect')))
        self.assertEqual(1,commands.count(('network','endpoints','inspect')))

    def test_real_target_render_and_source_import_use_same_compatibility_without_normalizing_desired_files(self):
        driver,request,source,unused=self.driver_fixture()
        legacy=driver.render(request)
        current_request=copy.deepcopy(request)
        for domain in ('ISCSI','NVMEOF'):
            payload=current_request['configurationDesiredState'][PROTOCOL_FILES[domain]]
            item=payload['targets'][0] if domain=='ISCSI' else payload['subsystems'][0]['namespaces'][0]
            item['listenerPorts']=list(item['config']['listenerGroupPorts'])
            current_request['protocolDesiredState'][domain]=payload
        current_before=canonical(current_request)
        current=driver.render(current_request)
        for filename in ('block/iscsi-plan.json','block/nvmeof-plan.json'):
            self.assertEqual(legacy[filename],current[filename])
        self.assertEqual(current_before,canonical(current_request))
        self.assertNotEqual(legacy['desired-state.json'],current['desired-state.json'])


if __name__=='__main__':unittest.main()
