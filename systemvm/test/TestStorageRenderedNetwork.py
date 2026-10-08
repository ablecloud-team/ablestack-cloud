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
import json,sys,tempfile,unittest,uuid
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import rendered_network as module
from rendered_generation import rendered_json


class StorageRenderedNetworkTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.root.chmod(0o700)
        self.scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':10};self.marker={**self.scope,'templateUpgradeUuid':str(uuid.uuid4())}
        self.source=self.root/'source';self.target=self.root/'target'
        self.binding={'listenIp':'10.10.13.241','primaryIp':'10.10.13.240','macAddress':'02:01:00:cc:00:09','prefixlen':16}
        self.desired=None;self.active=False;self.calls=[];outer=self
        for path,state,bindings in ((self.source,None,[]),(self.target,{'endpoints':[{'listenIp':'10.10.13.241','primaryIp':'10.10.13.240','prefixlen':16}]},[self.binding])):
            path.mkdir(mode=0o700);rendered_json(path/'desired-state.json',{'sharedfs-network.json':{'ipAddress':'10.10.13.240'},'network-endpoints.json':state});rendered_json(path/'prerequisites.json',{'expectedBindings':bindings})
        class Store:
            root=outer.root
            def inspect(self,path):return {'scope':outer.scope,'manifestSha256':'a'*64 if path==outer.source else 'b'*64}
        class Runtime:
            def command(self,args,payload=None):
                outer.calls.append(args)
                if args[1]=='maintenance':return {'success':True,'bootHeld':True,'scope':outer.marker}
                if args[-1]=='inspect':return {'success':True,'sideEffects':False,'bindingsValidated':True,'endpoints':[{**{key:row[key] for key in ('listenIp','macAddress','prefixlen')},'interface':'eth0','active':outer.active} for row in payload['expectedBindings']]}
                if args[-1]=='reconcile':
                    if payload['expectedBindings']:outer.active=True
                    return {'success':True,'bindingReceiptVerified':True}
                raise AssertionError(args)
            def persist_one(self,name,value):outer.desired=value
            def remaining(self,limit):return limit
        self.handler=module.RenderedNetwork(Runtime(),Store())
        self.proof=json.dumps({'scope':self.scope,'maintenanceScope':self.marker}).encode()
        self.real_read=module.rendered_read
        self.reader=lambda path:self.proof if path.name=='writer.json' else self.real_read(path)

    def test_root_added_alias_inverse_restores_original_absence_and_exact_source_receipt(self):
        with patch.object(module,'rendered_read',self.reader):
            self.handler.apply(self.target,self.source)
            self.assertTrue(self.active);self.assertIsNotNone(self.desired)
            def ip(action,row):
                self.assertEqual('del',action);self.assertEqual(self.binding['macAddress'],row['macAddress']);self.active=False
            self.handler.ip=ip
            self.handler.apply(self.target,self.source,rollback=True)
        self.assertFalse(self.active);self.assertIsNone(self.desired)
        journal=json.loads((self.root/'network-transitions'/ (self.scope['operationUuid']+'.json')).read_text())
        self.assertEqual('ROLLED_BACK',journal['phase']);self.assertFalse(journal['before']['10.10.13.241']['active'])

    def test_foreign_marker_and_primary_change_fail_before_any_address_or_desired_mutation(self):
        self.marker={**self.marker,'operationUuid':str(uuid.uuid4())}
        with patch.object(module,'rendered_read',self.reader),self.assertRaises(ValueError):self.handler.apply(self.target,self.source)
        self.assertFalse(self.active);self.assertIsNone(self.desired);self.assertFalse((self.root/'network-transitions').exists())
        rendered_json(self.target/'desired-state.json',{'sharedfs-network.json':{'ipAddress':'10.10.13.242'},'network-endpoints.json':None})
        with self.assertRaises(ValueError):self.handler.apply(self.target,self.source)

    def test_wrong_observed_mac_or_changed_source_pin_never_completes_transition(self):
        original=self.handler.runtime.command
        def wrong(args,payload=None):
            result=original(args,payload)
            if args[-1]=='inspect' and result['endpoints']:result['endpoints'][0]['macAddress']='02:01:00:cc:00:10'
            return result
        self.handler.runtime.command=wrong
        with patch.object(module,'rendered_read',self.reader),self.assertRaises(ValueError):self.handler.apply(self.target,self.source)
        self.assertFalse(self.active);self.assertIsNone(self.desired)

if __name__=='__main__':unittest.main()
