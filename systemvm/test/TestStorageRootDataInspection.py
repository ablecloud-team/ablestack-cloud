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

import copy,json,os,re,subprocess,tempfile
import importlib.util
from pathlib import Path
import sys
import unittest
import uuid

LIB=Path(__file__).resolve().parents[2]/'systemvm/debian/usr/local/lib/ablestack-storage'
sys.path.insert(0,str(LIB))
import root_data_inspection as module
import volume_identity

class StorageRootDataInspectionTest(unittest.TestCase):
    def setUp(self):
        self.original=volume_identity.by_id_candidates
        volume_identity.by_id_candidates=lambda *args,**kwargs:[]
        self.addCleanup(setattr,volume_identity,'by_id_candidates',self.original)
        self.volume=str(uuid.uuid4())
        self.request={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'templateUpgradeUuid':str(uuid.uuid4()),
                      'volumes':[{'volumeUuid':self.volume,'kind':'FILE_DATA','sizeBytes':1024*1024*1024}]}
        self.root={'name':'vda','path':'/dev/vda','type':'disk','size':5000000000,'serial':'ROOT',
                   'children':[{'name':'vda1','path':'/dev/vda1','type':'part','mountpoint':'/','fstype':'ext4','uuid':'ROOT-FS'}]}
        self.disk={'name':'sdb','path':'/dev/sdb','type':'disk','size':1024*1024*1024,'serial':self.volume,
                   'fstype':'xfs','uuid':'DATA-FS','mountpoint':'/srv/ablestack-storage/volumes/'+self.volume}
        self.mounts=[{'source':'/dev/sdb','target':self.disk['mountpoint'],'fstype':'xfs','uuid':'DATA-FS','options':'rw'}]

    def inspect(self):return module.inspect_data(self.request,[self.root,self.disk],self.mounts)

    def test_exact_serial_filesystem_and_mount_are_observed_without_mutating_input(self):
        import copy
        before=copy.deepcopy(self.disk)
        result=self.inspect()[0]
        self.assertEqual('EXACT',result['mappingStatus'])
        self.assertEqual('DATA-FS',result['filesystemUuid'])
        self.assertEqual(self.volume,result['serial'])
        self.assertEqual(before,self.disk)
        self.assertEqual(self.mounts,result['mounts'])

    def test_raw_unused_disk_is_not_mounted_or_formatted(self):
        self.request['volumes'][0]['kind']='BLOCK_RAW'
        self.disk.pop('mountpoint');self.disk.pop('fstype');self.disk.pop('uuid');self.mounts=[]
        result=self.inspect()[0]
        self.assertIsNone(result['filesystemUuid'])
        self.assertEqual([],result['mounts'])

    def test_root_disk_and_unknown_serial_size_fallback_are_rejected(self):
        self.disk['serial']='UNKNOWN'
        with self.assertRaises(Exception):self.inspect()
        self.root['serial']=self.volume
        with self.assertRaises(Exception):self.inspect()

    def test_filesystem_uuid_only_hijack_without_volume_serial_is_rejected(self):
        self.disk['serial']='UNRELATED-VOLUME'
        self.request['volumes'][0]['filesystemUuid']='DATA-FS'
        with self.assertRaisesRegex(ValueError,'stable volume serial'):
            self.inspect()

    def test_duplicate_volume_or_disk_mapping_and_size_difference_are_rejected(self):
        self.request['volumes'].append(dict(self.request['volumes'][0]))
        with self.assertRaises(ValueError):self.inspect()
        self.request['volumes'].pop();self.disk['size']-=1024
        with self.assertRaises(ValueError):self.inspect()

    def test_declared_filesystem_mismatch_and_multiple_filesystems_are_rejected(self):
        self.request['volumes'][0]['filesystemUuid']='FOREIGN-FS'
        with self.assertRaises(ValueError):self.inspect()
        self.request['volumes'][0].pop('filesystemUuid')
        self.disk['children']=[{'name':'sdb1','path':'/dev/sdb1','type':'part','fstype':'ext4','uuid':'CHILD-FS'}]
        with self.assertRaises(ValueError):self.inspect()

    def test_blank_requires_readonly_signature_absence_and_no_partition_or_filesystem(self):
        self.request['volumes'][0]['kind']='UNUSED';self.mounts=[]
        self.disk.pop('mountpoint');self.disk.pop('fstype');self.disk.pop('uuid')
        def inspect(proof):return module.inspect_data(self.request,[self.root,self.disk],self.mounts,lambda path:proof)[0]
        self.assertTrue(inspect({'available':True,'signatures':[]})['blank'])
        self.assertFalse(inspect({'available':False,'signatures':[]})['blank'])
        self.assertFalse(inspect({'available':True,'signatures':[{'type':'xfs','uuid':'PARTIAL'}]})['blank'])
        self.disk['children']=[{'path':'/dev/sdb1','name':'sdb1','type':'part'}]
        self.assertFalse(inspect({'available':True,'signatures':[]})['blank'])

    def test_signature_probe_uses_only_no_act_and_failed_observation_does_not_claim_blank(self):
        from unittest.mock import patch
        import subprocess
        with patch.object(module.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'{"signatures": []}')) as run:
            self.assertEqual({'available':True,'signatures':[]},module.inspect_signatures('/dev/selected'))
            self.assertEqual(['wipefs','--no-act','--json','/dev/selected'],run.call_args.args[0])
        with patch.object(module.subprocess,'run',side_effect=subprocess.TimeoutExpired('wipefs',8)):
            self.assertFalse(module.inspect_signatures('/dev/selected')['available'])

    def test_partial_uuid_prefix_is_rejected_but_exact_qemu_twenty_character_serial_is_allowed(self):
        token=self.volume.replace("-","")
        for serial in (token[:8],token[:19],token[:21],"prefix"+token):
            self.disk["serial"]=serial
            with self.assertRaises(Exception):self.inspect()
        self.disk["serial"]=token[:20]
        self.assertEqual("EXACT",self.inspect()[0]["mappingStatus"])


    def mixed_devices(self):
        size=20*1024**3
        root={'name':'sdb','path':'/dev/sdb','type':'disk','size':5000000000,'serial':'ROOT',
              'children':[{'name':'sdb6','path':'/dev/sdb6','type':'part','mountpoint':'/','fstype':'ext4','uuid':'ROOT-FS'}]}
        data={'name':'sda','path':'/dev/sda','type':'disk','size':size,'serial':self.volume,
              'fstype':'xfs','uuid':'DATA-FS','mountpoint':'/srv/ablestack-storage/volumes/'+self.volume}
        optical=[{'name':'sr0','path':'/dev/sr0','type':'rom','size':1048576,'fstype':'iso9660','uuid':'CONFIG-ISO'},
                 {'name':'sr1','path':'/dev/sr1','type':'rom','size':0}]
        request=copy.deepcopy(self.request);request['volumes'][0].update(kind='UNUSED',sizeBytes=size)
        mounts=[{'source':data['path'],'target':data['mountpoint'],'fstype':'xfs','uuid':'DATA-FS','options':'rw'}]
        return request,[root,*optical,data],mounts

    def test_real_flatten_root_and_optical_none_parents_keep_mounted_data_exact(self):
        request,devices,mounts=self.mixed_devices();before=copy.deepcopy(devices)
        flattened=volume_identity.flatten_devices(devices)
        by_name={row['name']:row for row in flattened}
        self.assertIs(by_name['sdb'],by_name['sdb']['_parentDisk'])
        self.assertIs(by_name['sda'],by_name['sda']['_parentDisk'])
        self.assertIsNone(by_name['sr0']['_parentDisk']);self.assertIsNone(by_name['sr1']['_parentDisk'])
        self.assertEqual('/dev/sdb',by_name['sdb6']['_parentDisk']['path'])
        result=module.inspect_data(request,devices,mounts,lambda path:{'available':True,'signatures':[{'type':'xfs','uuid':'DATA-FS'}]})[0]
        self.assertEqual('EXACT',result['mappingStatus']);self.assertEqual('/dev/sda',result['observedDevicePath'])
        self.assertEqual(20*1024**3,result['sizeBytes']);self.assertEqual(mounts,result['mounts'])
        self.assertFalse(result['blank']);self.assertFalse(result['partitioned']);self.assertEqual(before,devices)

    def test_optical_none_does_not_weaken_root_exclusion_or_partition_detection(self):
        request,devices,mounts=self.mixed_devices();data=devices[-1]
        for key in ('fstype','uuid','mountpoint'):data.pop(key)
        data['children']=[{'name':'sda1','path':'/dev/sda1','type':'part','size':20*1024**3}]
        result=module.inspect_data(request,devices,[],lambda path:{'available':True,'signatures':[]})[0]
        self.assertTrue(result['partitioned']);self.assertFalse(result['blank'])
        devices[0]['serial']=self.volume;devices[0]['size']=20*1024**3;data['serial']='UNRELATED'
        with self.assertRaises(Exception):module.inspect_data(request,devices,[],lambda path:{'available':True,'signatures':[]})

    def test_production_cli_sealed_stdin_mixed_inventory_uses_only_readonly_metadata_commands(self):
        request,devices,mounts=self.mixed_devices()
        cli=LIB.parents[1]/'bin/ablestack-storagectl'
        source=cli.read_text()
        embedded=source.split("<<'PYROOTDATA'\n",1)[1].split('\nimport sys\nimport time\n',1)[0]
        expected=(LIB/'root_data_inspection.py').read_text()
        expected=expected[expected.index('"""Fresh, strictly identified'):].replace('from volume_identity import (','import sys\nsys.path.insert(0, "/usr/local/lib/ablestack-storage")\nfrom volume_identity import (',1)
        self.assertEqual(expected.rstrip(),embedded[:len(expected.rstrip())])
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);tools=root/'bin';tools.mkdir()
            calls=root/'metadata-calls.jsonl'
            outputs={'lsblk':{'blockdevices':devices},'findmnt':{'filesystems':mounts},'wipefs':{'signatures':[{'type':'xfs','uuid':'DATA-FS'}]}}
            for name,value in outputs.items():
                tool=tools/name
                body="#!/usr/bin/python3\nimport json,sys\nfrom pathlib import Path\n"
                body+="with Path("+repr(str(calls))+").open('a') as log:log.write(json.dumps(sys.argv)+chr(10))\n"
                body+="print("+repr(json.dumps(value))+")\n"
                tool.write_text(body);tool.chmod(0o755)
            lock=root/'absent-writer/lock'
            env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',PYTHONPATH=str(LIB),PATH=str(tools)+':'+os.environ['PATH'],ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(lock))
            result=subprocess.run(['bash',str(cli),'operation','root-data','inspect','/dev/stdin'],input=json.dumps(request).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=15)
            self.assertEqual(0,result.returncode,result.stderr+result.stdout)
            value=json.loads(result.stdout);self.assertIs(value['success'],True)
            self.assertEqual('/dev/sda',value['volumes'][0]['observedDevicePath']);self.assertFalse(value['volumes'][0]['blank'])
            observed=[json.loads(line) for line in calls.read_text().splitlines()]
            self.assertEqual(['lsblk','findmnt','wipefs'],[Path(row[0]).name for row in observed])
            self.assertEqual(['--no-act','--json','/dev/sda'],observed[-1][1:])
            self.assertFalse(lock.parent.exists())

if __name__=='__main__':unittest.main()
