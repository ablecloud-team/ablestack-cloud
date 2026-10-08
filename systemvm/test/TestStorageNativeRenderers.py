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

import copy,importlib.util,json
from pathlib import Path
import tempfile
import unittest
import uuid

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'systemvm/debian/usr/local/lib/ablestack-storage/native_renderers.py'
CLI=ROOT/'systemvm/debian/usr/local/bin/ablestack-storagectl'
spec=importlib.util.spec_from_file_location('renderer',SOURCE);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class StorageNativeRenderersTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'existing';self.path.mkdir();self.file=self.path/'data';self.file.write_text('unchanged');self.file.chmod(0o640)
        self.instance=str(uuid.uuid4());self.export=str(uuid.uuid4());self.volume=str(uuid.uuid4());self.backing='/srv/ablestack-storage/volumes/'+self.volume+'/share'
        self.nfs={'enabled':True,'idMappingMode':'NUMERIC','exports':[{'uuid':self.export,'name':'shared','exportPath':'shared','path':'/export/shared',
            'config':{'backingPath':self.backing,'volumeMountPath':'/srv/ablestack-storage/volumes/'+self.volume,'relativeSharePath':'share'},
            'acls':[{'principal':'10.10.0.0/16','permission':'READ_WRITE'}]}]}
        self.smb={'enabled':True,'instanceUuid':self.instance,'listeners':[{'listenIp':'10.10.13.240','port':445},{'listenIp':'10.10.13.241','port':445}],
                  'shares':[{'uuid':self.export,'name':'shared','path':self.backing,'config':{'createMask':'0640'},'acls':[{'uuid':str(uuid.uuid4()),'principalType':'LOCAL_USER','principal':'example','permission':'READ_WRITE','password':'SYNTHETIC_NEVER_PERSIST'}]}]}

    def test_nfs_uses_existing_serializer_and_produces_immutable_text_without_data_changes(self):
        original=copy.deepcopy(self.nfs);before=self.file.stat()
        files=m.render_nfs_candidate(self.nfs,CLI)
        config=next(value for name,value in files.items() if name.endswith('.conf'))
        self.assertIn('Only_Numeric_Owners = true',config);self.assertIn('10.10.0.0/16',config)
        self.assertIn('Root_Squash',config);self.assertIn('Pseudo = "/shared"',config)
        self.assertEqual(original,self.nfs);self.assertEqual(before,self.file.stat());self.assertEqual('unchanged',self.file.read_text())

    def test_smb_renders_both_acceptor_records_and_never_persists_transient_password(self):
        original=copy.deepcopy(self.smb);before=self.file.stat()
        files=m.render_smb_candidate(self.smb,CLI)
        self.assertEqual(2,len([name for name in files if name.startswith('smb/listeners/')]))
        self.assertIn('interfaces = lo 10.10.13.240 10.10.13.241',files['smb/smb.conf'])
        self.assertIn('create mask = 0640',files['smb/smb.conf'])
        self.assertTrue(all('SYNTHETIC' not in value for value in files.values()))
        self.assertEqual(original,self.smb);self.assertEqual(before,self.file.stat())

    def test_overlap_is_rejected_and_joined_ad_renders_existing_kerberos_contract(self):
        with self.assertRaises(ValueError):m.render_smb_candidate({**self.smb,'listeners':[{'listenIp':'0.0.0.0','port':445},{'listenIp':'10.10.13.240','port':445}]},CLI)
        rendered=m.render_smb_candidate({**self.smb,'identityDomain':{'domainName':'ablestack.local','joinState':'JOINED'}},CLI)
        self.assertIn('realm = ABLESTACK.LOCAL',rendered['smb/smb.conf'])
        self.assertIn('security = ADS',rendered['smb/smb.conf'])
        self.assertIn('kerberos method = secrets and keytab',rendered['smb/smb.conf'])

    def test_block_plan_requires_exact_unmounted_mapping_and_durable_auth_reference(self):
        target={'uuid':str(uuid.uuid4()),'volumeUuid':self.volume,'volumeSizeBytes':20*(1<<30),'targetName':'iqn.2026-10.local.storage:target',
                'acls':[{'uuid':str(uuid.uuid4()),'principal':'iqn.2026-10.test.example:client','config':{'chapEnabled':True,'chapUsername':'example'}}]}
        payload={'targets':[target]}
        observed={'devicePath':'/dev/verified','matchedBy':'VOLUME_SERIAL','serial':self.volume.replace('-','')[:20],'sizeBytes':20*(1<<30),'rootDevice':False,'mounted':False}
        resolver=lambda item:observed
        with self.assertRaises(ValueError):m.render_block_candidate(payload,'ISCSI',resolver)
        refs={target['targetName']+'|'+target['acls'][0]['principal']:{'version':str(uuid.uuid4())}}
        result=m.render_block_candidate(payload,'ISCSI',resolver,refs)
        self.assertIn('credentialRef',result['block/iscsi-plan.json']);self.assertNotIn('SYNTHETIC',result['block/iscsi-plan.json'])
        for changes in ({'matchedBy':'FILESYSTEM_UUID'},{'mounted':True},{'rootDevice':True},{'sizeBytes':512}):
            with self.assertRaises(ValueError):m.render_block_candidate(payload,'ISCSI',lambda item:{**observed,**changes},refs)

    def test_merged_iqn_preserves_all_distinct_luns_and_requested_endpoint_groups(self):
        one={'uuid':str(uuid.uuid4()),'volumeUuid':self.volume,'volumeSizeBytes':1024,'targetName':'iqn.2026-10.local.storage:shared', 'lunOrNamespace':0,'config':{'listenerGroupPorts':[3260]}}
        two={**one,'uuid':str(uuid.uuid4()),'volumeUuid':str(uuid.uuid4()),'lunOrNamespace':1,'config':{'listenerGroupPorts':[3261]}}
        resolver=lambda item:{'devicePath':'/dev/'+item['volumeUuid'],'matchedBy':'VOLUME_SERIAL','serial':item['volumeUuid'],'sizeBytes':1024,'mounted':False,'rootDevice':False}
        payload={'listeners':[{'listenIp':'10.10.13.240','port':3260},{'listenIp':'10.10.13.241','port':3261}], 'targets':[one,two]}
        plan=json.loads(m.render_block_candidate(payload,'ISCSI',resolver)['block/iscsi-plan.json'])
        self.assertEqual(1,len(plan['targets']));self.assertEqual({0,1},{item['number'] for item in plan['targets'][0]['backstores']})
        self.assertEqual({3260,3261},{item['port'] for item in plan['targets'][0]['listeners']})
        two['lunOrNamespace']=0
        with self.assertRaises(ValueError):m.render_block_candidate(payload,'ISCSI',resolver)

    def test_nfs_selected_addresses_keep_exact_export_scope_and_dedicated_dbus_names(self):
        payload=copy.deepcopy(self.nfs);payload['exports'][0]['config'].update({'endpointMode':'SELECTED','listenIps':['10.10.13.240','10.10.13.241']})
        files=m.render_nfs_candidate(payload,CLI);manifest=json.loads(files['nfs/manifest.json'])
        self.assertEqual({'10.10.13.240','10.10.13.241'},{row['listenIp'] for row in manifest['endpoints']})
        prefixes=set()
        for row in manifest['endpoints']:
            content=files[row['configurationPath']]
            self.assertIn('Bind_addr = '+row['listenIp'],content)
            prefixes.add(next(line.strip() for line in content.splitlines() if 'Dbus_Name_Prefix' in line))
        self.assertEqual(2,len(prefixes))
        other=copy.deepcopy(payload['exports'][0]);other['uuid']=str(uuid.uuid4());other['name']='other';other['exportPath']='other';other['path']='/export/other';other['config'].pop('endpointMode');other['config'].pop('listenIps');payload['exports'].append(other)
        with self.assertRaisesRegex(ValueError,'overlap'):m.render_nfs_candidate(payload,CLI)

if __name__=='__main__':unittest.main()
