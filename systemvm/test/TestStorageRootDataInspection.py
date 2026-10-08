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

if __name__=='__main__':unittest.main()
