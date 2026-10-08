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

import copy,json,os,sys,tempfile,unittest,uuid,subprocess
from pathlib import Path
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from root_network_bootstrap import RootNetworkBootstrap
from rendered_generation import DESIRED_PATHS

class StorageRootNetworkBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"templateUpgradeUuid":str(uuid.uuid4()),"revision":2}
        self.binding={"macAddress":"02:00:00:00:00:01","primaryIp":"10.10.13.240","prefixlen":24,"gateway":"10.10.13.1"}
        self.declaration={"macAddress":self.binding["macAddress"],"ipAddress":self.binding["primaryIp"],"cidr":"10.10.13.0/24","gateway":self.binding["gateway"]}
        self.request={**self.scope,"primaryBinding":self.binding,"sharedfsNetwork":self.declaration}
        self.marker={"maintenanceKind":"ROOT","bootHeld":True,"scope":self.scope}
        self.generation={"generation":None,"pendingOperationUuid":None,"configurationDesiredState":{name:None for name in DESIRED_PATHS}}
        self.rendered={"current":None,"bootHeld":False};self.calls=[]
        self.links=[{"ifname":"ens3","address":self.binding["macAddress"]}]
        self.addresses=[{"ifname":"ens3","addr_info":[{"family":"inet","scope":"global","local":self.binding["primaryIp"],"prefixlen":24}]}]
        self.routes=[{"dev":"ens3","gateway":self.binding["gateway"]}]
        def run(args,**kwargs):
            self.calls.append(args);value=self.links if args[2]=="link" else self.addresses if "addr" in args else self.routes
            return subprocess.CompletedProcess(args,0,json.dumps(value),"")
        self.bootstrap=RootNetworkBootstrap(self.base/"configuration",run)
    def apply(self):return self.bootstrap.apply(self.request,self.marker,self.generation,self.rendered)
    def test_exact_fresh_primary_writes_only_source_canonical_row_and_replay_is_noop(self):
        result=self.apply();self.assertTrue(result["canonicalPrimaryWritten"]);self.assertFalse(result["networkChanged"])
        path=self.bootstrap.configuration/"sharedfs-network.json";before=path.stat();self.assertEqual(self.declaration,json.loads(path.read_text()));self.assertEqual(0o600,path.stat().st_mode&0o777)
        self.assertFalse(self.apply()["canonicalPrimaryWritten"]);self.assertEqual(before,path.stat())
        self.assertEqual(["sharedfs-network.json"],[path.name for path in self.bootstrap.configuration.iterdir()]);self.assertFalse(any("replace" in args or "flush" in args for args in self.calls))
    def test_foreign_marker_current_generation_rendered_pointer_or_any_other_canonical_row_blocks_before_write(self):
        for mutation in ({"generation":{"generation":self.scope,"pendingOperationUuid":None,"configurationDesiredState":self.generation["configurationDesiredState"]}},
                         {"rendered":{"current":{"scope":self.scope},"bootHeld":False}},
                         {"marker":{**self.marker,"bootHeld":False}}):
            original=(self.generation,self.rendered,self.marker)
            for key,value in mutation.items():setattr(self,key,value)
            with self.assertRaises(ValueError):self.apply()
            self.generation,self.rendered,self.marker=original
        self.generation["configurationDesiredState"]["posix-directory-policies.json"]={}
        with self.assertRaises(ValueError):self.apply()
        self.assertFalse(self.bootstrap.configuration.exists())
    def test_primary_alias_prefix_mac_or_route_change_is_not_repaired_implicitly(self):
        for identity in ({"local":"10.10.13.241"},{"prefixlen":25}):
            self.addresses[0]["addr_info"][0].update(identity)
            with self.assertRaises(ValueError):self.apply()
            self.addresses[0]["addr_info"][0].update(local=self.binding["primaryIp"],prefixlen=24)
        self.addresses[0]["addr_info"].append({"family":"inet","scope":"global","local":"10.10.13.241","prefixlen":24})
        with self.assertRaises(ValueError):self.apply()
        self.addresses[0]["addr_info"].pop();self.routes[0]["gateway"]="10.10.13.2"
        with self.assertRaises(ValueError):self.apply()
        self.assertFalse(self.bootstrap.configuration.exists());self.assertTrue(all(args[0]=="ip" for args in self.calls))
    def test_dhcp_source_null_is_preserved_without_synthesizing_static_config(self):
        self.request["sharedfsNetwork"]=None
        result=self.apply();self.assertIsNone(result["sharedfsNetwork"]);self.assertFalse(result["canonicalPrimaryWritten"]);self.assertFalse(self.bootstrap.configuration.exists())
    def test_actual_fixed_cli_bootstrap_accepts_only_fresh_root_and_keeps_other_six_absent(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl";binaries=self.base/"bin";binaries.mkdir()
        helper=binaries/"ip";fixtures=self.base/"fixture.json";fixtures.write_text(json.dumps({"links":self.links,"addresses":self.addresses,"routes":self.routes}))
        helper.write_text("#!/usr/bin/python3\nimport json,os,sys\nfrom pathlib import Path\nv=json.loads(Path(os.environ['ROOT_PRIMARY_FIXTURE']).read_text())\nprint(json.dumps(v['links'] if sys.argv[2]=='link' else v['addresses'] if 'addr' in sys.argv else v['routes']))\n");helper.chmod(0o755)
        env=dict(os.environ,PATH=str(binaries)+":"+os.environ["PATH"],ROOT_PRIMARY_FIXTURE=str(fixtures),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.base/"cli-config"),
                 ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.base/"maintenance"),ABLESTACK_STORAGE_GENERATION_DIR=str(self.base/"generation"),ABLESTACK_STORAGE_RENDERED_GENERATIONS=str(self.base/"render"),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.base/"writer"))
        def native(args,payload):
            result=subprocess.run([str(cli),*args,"/dev/stdin"],input=json.dumps(payload),env=env,capture_output=True,text=True,timeout=15)
            self.assertEqual(0,result.returncode,result.stderr+result.stdout);return json.loads(result.stdout)
        native(("operation","maintenance","enter"),self.scope)
        result=native(("operation","root-network","bootstrap"),self.request)
        self.assertTrue(result["primaryBindingVerified"]);self.assertFalse(result["networkChanged"])
        self.assertEqual(["sharedfs-network.json"],sorted(path.name for path in (self.base/"cli-config").iterdir()));self.assertFalse((self.base/"generation").exists())

if __name__=="__main__":unittest.main()
