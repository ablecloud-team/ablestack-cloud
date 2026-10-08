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

import copy
from pathlib import Path
import sys,unittest,uuid

LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from rendered_prerequisites import RenderedPrerequisites


class StorageRenderedPrerequisitesTest(unittest.TestCase):
    def setUp(self):
        self.calls=[];self.policy=str(uuid.uuid4());self.volume=str(uuid.uuid4());self.filesystem=str(uuid.uuid4())
        self.identity={'filesystemUuid':self.filesystem,'device':2,'inode':5,'effectiveUid':0,'effectiveGid':0,'effectiveMode':'0755','aclSha256':'a'*64}
        self.request={'uuid':self.policy,'instanceUuid':str(uuid.uuid4()),'volumeUuid':self.volume,'volumeMountPath':'/srv/ablestack-storage/volumes/'+self.volume,'relativePath':'share','config':{'directoryMode':'0770'},'expectedDirectoryIdentity':self.identity,'expectedFilesystemUuid':self.filesystem}
        self.source={'sharedfs-network.json':None,'network-endpoints.json':None,'posix-directory-policies.json':None}
        self.target={**self.source,'posix-directory-policies.json':{self.policy:{'request':self.request,'config':self.request['config']}}}
        self.observed={'success':True,'filesystemUuid':self.filesystem,'directoryIdentity':self.identity,'uuid':self.policy}
        outer=self
        class Runtime:
            def command(self,args,payload):
                outer.calls.append((args,payload))
                return outer.observed if args[0]=='posix' else {'success':True,'sideEffects':False,'bindingsValidated':True,'endpoints':[]}
        self.adapter=RenderedPrerequisites(Runtime())

    def test_stage_collects_readonly_exact_directory_and_mac_proofs_without_apply(self):
        before=copy.deepcopy(self.target)
        result=self.adapter.snapshot(self.source,self.target,[])
        self.assertEqual(self.observed,result['directories'][self.policy]);self.assertEqual(before,self.target)
        self.assertEqual([('posix','directory','inspect'),('network','endpoints','inspect')],[args for args,payload in self.calls])

    def test_changed_preview_inode_or_filesystem_never_passes_pure_stage(self):
        for changes in ({'directoryIdentity':{**self.identity,'inode':99}},{'filesystemUuid':str(uuid.uuid4())}):
            self.observed={**self.observed,**changes}
            with self.assertRaises(ValueError):self.adapter.snapshot(self.source,self.target,[])
            self.assertTrue(all(args[-1]=='inspect' for args,payload in self.calls))

    def test_primary_transition_or_unbound_policy_path_is_not_silently_activated(self):
        with self.assertRaises(ValueError):self.adapter.snapshot(self.source,{**self.target,'sharedfs-network.json':{'mode':'STATIC'}},[])
        wrong={**self.target,'posix-directory-policies.json':{str(uuid.uuid4()):self.target['posix-directory-policies.json'][self.policy]}}
        with self.assertRaises(ValueError):self.adapter.snapshot(self.source,wrong,[])

if __name__=='__main__':unittest.main()
