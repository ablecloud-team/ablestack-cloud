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
        self.effective={'success':True,'filesystemUuid':self.filesystem,'directoryIdentity':{**self.identity,'effectiveMode':'0770'},'uuid':self.policy}
        self.row={'request':self.request,'config':self.request['config'],'effective':self.effective}
        self.target={**self.source,'posix-directory-policies.json':{self.policy:self.row}}
        self.observed={'success':True,'filesystemUuid':self.filesystem,'directoryIdentity':self.identity,'uuid':self.policy}
        outer=self
        class Runtime:
            def command(self,args,payload):
                outer.calls.append((args,payload))
                if args==('posix','directory','plan'):
                    return {'success':True,'sideEffects':False,'postApplyReceiptRequired':True,'beforeDirectoryIdentity':outer.observed['directoryIdentity'],
                            'predictedDirectoryIdentity':outer.effective['directoryIdentity'],'configurationDesiredRow':outer.row}
                return outer.observed if args[0]=='posix' else {'success':True,'sideEffects':False,'bindingsValidated':True,'endpoints':[]}
        self.adapter=RenderedPrerequisites(Runtime())

    def test_stage_collects_readonly_exact_directory_and_mac_proofs_without_apply(self):
        before=copy.deepcopy(self.target)
        result=self.adapter.snapshot(self.source,self.target,[])
        self.assertEqual(self.observed,result['directories'][self.policy]);self.assertEqual(before,self.target)
        self.assertEqual([('posix','directory','inspect'),('posix','directory','plan'),('network','endpoints','inspect')],[args for args,payload in self.calls])

    def test_changed_preview_inode_or_filesystem_never_passes_pure_stage(self):
        for changes in ({'directoryIdentity':{**self.identity,'inode':99}},{'filesystemUuid':str(uuid.uuid4())}):
            self.observed={**self.observed,**changes}
            with self.assertRaises(ValueError):self.adapter.snapshot(self.source,self.target,[])
            self.assertTrue(all(args[-1]=='inspect' for args,payload in self.calls))

    def test_guessed_post_effective_policy_is_rejected_by_the_native_pure_plan(self):
        target=copy.deepcopy(self.target);target['posix-directory-policies.json'][self.policy]['effective']['directoryIdentity']['effectiveUid']=1234
        with self.assertRaises(ValueError):self.adapter.snapshot(self.source,target,[])
        self.assertFalse(any(args[-1] in ('apply','restore') for args,payload in self.calls))

    def test_policy_removal_verifies_post_identity_without_reusing_pre_apply_preview(self):
        row=copy.deepcopy(self.row);row['effective']['directoryIdentity']=self.identity
        row['request']['expectedDirectoryIdentity']={**self.identity,'effectiveUid':65534}
        source={**self.source,'posix-directory-policies.json':{self.policy:row}}
        result=self.adapter.snapshot(source,self.source,[])
        self.assertEqual(self.observed,result['directories'][self.policy]);self.assertFalse(result['directoryPlans'])
        self.assertFalse(any(args[-1]=='plan' for args,payload in self.calls))

    def test_primary_transition_or_unbound_policy_path_is_not_silently_activated(self):
        with self.assertRaises(ValueError):self.adapter.snapshot(self.source,{**self.target,'sharedfs-network.json':{'mode':'STATIC'}},[])
        wrong={**self.target,'posix-directory-policies.json':{str(uuid.uuid4()):self.target['posix-directory-policies.json'][self.policy]}}
        with self.assertRaises(ValueError):self.adapter.snapshot(self.source,wrong,[])

    def test_root_transfer_stage_and_activate_only_reobserve_receipt_without_permission_or_canonical_write(self):
        import hashlib,json,tempfile
        from pathlib import Path
        from rendered_generation import rendered_json
        root_scope={"instanceUuid":self.request["instanceUuid"],"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":10}
        context={"rootScope":root_scope,"sourceConfigurationSha256":"f"*64}
        actual={**self.identity,"device":99,"effectiveMode":"0770"};self.observed={**self.observed,"directoryIdentity":actual}
        self.request["expectedDirectoryIdentity"]={**self.identity,"effectiveUid":65534}
        plan={"success":True,"sideEffects":False,"transferOnly":True,"postApplyReceiptRequired":True,"postApplyReceiptVerified":True,
              "beforeDirectoryIdentity":actual,"predictedDirectoryIdentity":actual,"configurationDesiredRow":self.row,**context,"dataPermissionsChanged":False,"canonicalDesiredStateChanged":False}
        def command(args,payload):
            self.calls.append((args,payload))
            if args==("posix","directory","attest-plan"):
                self.assertEqual({"canonicalPolicyRow":self.row,**context},payload);return plan
            if args==("posix","directory","inspect"):return self.observed
            if args==("network","endpoints","inspect"):return {"sideEffects":False,"bindingsValidated":True,"endpoints":[]}
            raise AssertionError("Unexpected DATA/canonical mutation "+str(args))
        self.adapter.runtime.command=command
        proof=self.adapter.snapshot(self.source,self.target,[],context)
        self.assertEqual(plan,proof["directoryPlans"][self.policy])
        with tempfile.TemporaryDirectory() as scratch:
            target=Path(scratch)/"target";source=Path(scratch)/"source";target.mkdir(mode=0o700);source.mkdir(mode=0o700)
            rendered_json(target/"desired-state.json",self.target);rendered_json(source/"desired-state.json",self.source);rendered_json(target/"prerequisites.json",proof)
            self.adapter.apply(target,source,False);self.adapter.apply(target,source,True)
        self.assertFalse(any(args[-1] in ("apply","restore","forget") for args,payload in self.calls))

if __name__=='__main__':unittest.main()
