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
import copy,json,sys,unittest,uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import TestStorageServiceIdentityCipher as cipher_tests
from rendered_driver import RenderedDriver
from rendered_generation import DESIRED_PATHS,rendered_json


class StorageAdInverseActivationTest(unittest.TestCase):
    def setUp(self):
        self.fixture=cipher_tests.StorageServiceIdentityCipherTest("test_stage_reuses_exact_beforejoin_source_cipher_after_private_joined_state_changes")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);f=self.fixture
        self.scope=f.fixture.scope;self.common=f.scope;self.native=f.cipher.source(self.scope);self.original=f.cipher.read(f.cipher.path(self.scope))["identityCheckpoint"]
        self.root=f.fixture.root/"inverse-boundary";self.root.mkdir(mode=0o700);self.generations=self.root/"generations";self.generations.mkdir(mode=0o700)
        self.previous=str(uuid.uuid4());self.oldpath=self.generations/self.previous;self.oldpath.mkdir(mode=0o700);self.targetpath=self.generations/self.scope["operationUuid"];self.targetpath.mkdir(mode=0o700)
        self.previous_manifest={"scope":self.native["sourceGeneration"],"manifestSha256":self.native["sourceRendered"]["manifestSha256"],"configurationSha256":self.native["sourceConfigurationSha256"]}
        self.target_manifest={"scope":self.common,"manifestSha256":"d"*64,"configurationSha256":"c"*64,"previousRenderedSha256":self.previous_manifest["manifestSha256"]}
        self.target_desired={key:None for key in DESIRED_PATHS};self.target_desired["smb-share-apply.json"]={"enabled":True}
        rendered_json(self.targetpath/"desired-state.json",self.target_desired)
        self.journal={"scope":self.common,"phase":"ACTIVATING","targetSha256":"d"*64,"previousSha256":self.previous_manifest["manifestSha256"],"previousOperationUuid":self.previous}
        self.actual={"generation":self.native["sourceGeneration"],"pendingOperationUuid":self.scope["operationUuid"],"generationStatus":"PENDING","configurationSha256":"c"*64,"configurationDesiredState":self.target_desired}
        self.driver=object.__new__(RenderedDriver);self.driver.generation=lambda:self.actual;self.driver.checkpoints=self.root/"checkpoints";self.driver.checkpoints.mkdir(mode=0o700)
        rendered_json(self.driver.checkpoints/(self.scope["operationUuid"]+".json"),self.original)
        outer=self
        class Store:
            generations=outer.generations
            def scoped_activation(inner,common):
                if common!=outer.journal["scope"]:raise ValueError("foreign activation scope")
                return outer.journal
            def inspect(inner,path):return outer.target_manifest if path==outer.targetpath else outer.previous_manifest
            def pointer(inner):return outer.targetpath
        self.driver.store=Store();self.driver.runtime=type("Runtime",(),{"command":lambda ignored,args:f.fixture.maintenance.status()})()
        self.patch=patch("rendered_driver.ServiceIdentityCipher",return_value=f.cipher);self.patch.start();self.addCleanup(self.patch.stop)
    def test_owned_postactivation_target_is_only_current_boundary_and_original_source_bytes_generation_cipher_stay_original(self):
        before={str(path):path.read_bytes() for path in (self.fixture.cipher.source_path(self.scope),self.fixture.cipher.path(self.scope))}
        result=self.driver.ad_join_inverse_source_guard(self.scope)
        self.assertTrue(result["inverseOriginalSourceVerified"]);self.assertEqual("OWNED_ACTIVATION_TARGET",result["currentBoundary"])
        self.assertEqual("a"*64,result["sourceConfigurationSha256"]);self.assertEqual("c"*64,result["currentConfigurationSha256"])
        self.assertEqual(before,{path:Path(path).read_bytes() for path in before})
        self.actual["configurationSha256"]="a"*64;self.assertEqual("ORIGINAL_SOURCE",self.driver.ad_join_inverse_source_guard(self.scope)["currentBoundary"])
    def test_foreign_previous_target_scope_pending_generation_or_key_cipher_never_authorizes_original_inverse(self):
        original=copy.deepcopy(self.actual)
        for change in ({"generation":{**self.native["sourceGeneration"],"revision":999}},{"pendingOperationUuid":str(uuid.uuid4())},{"configurationSha256":"f"*64}):
            self.actual={**original,**change}
            with self.assertRaises(ValueError):self.driver.ad_join_inverse_source_guard(self.scope)
        self.actual=original
        self.previous_manifest["configurationSha256"]="e"*64
        with self.assertRaises(ValueError):self.driver.ad_join_inverse_source_guard(self.scope)
        self.previous_manifest["configurationSha256"]="a"*64
        saved=copy.deepcopy(self.original);saved["publicKey"]="foreign key";rendered_json(self.driver.checkpoints/(self.scope["operationUuid"]+".json"),saved)
        with self.assertRaises(ValueError):self.driver.ad_join_inverse_source_guard(self.scope)
    def test_wrong_actual_target_seven_or_unowned_activation_phase_cannot_be_treated_as_source(self):
        self.actual["configurationDesiredState"]={key:None for key in DESIRED_PATHS}
        with self.assertRaises(ValueError):self.driver.ad_join_inverse_source_guard(self.scope)
        self.actual["configurationDesiredState"]=self.target_desired;self.journal["phase"]="COMPLETE"
        with self.assertRaises(ValueError):self.driver.ad_join_inverse_source_guard(self.scope)


if __name__=="__main__":unittest.main()
