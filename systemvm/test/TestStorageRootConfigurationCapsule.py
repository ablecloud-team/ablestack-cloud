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
import copy,sys,unittest,uuid
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from root_configuration_capsule import root_configuration_from_observation,root_configuration_authorize,validate_root_configuration
from rendered_credentials import CREDENTIAL_CANONICAL_PATHS,credential_configuration_sha256
from identity_capsule import encrypt,decrypt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

class StorageRootConfigurationCapsuleTest(unittest.TestCase):
    def setUp(self):
        self.scope={"instanceUuid":str(uuid.uuid4()),"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":8}
        self.desired={name:None for name in CREDENTIAL_CANONICAL_PATHS};self.desired["sharedfs-network.json"]={"macAddress":"00:16:3e:01:02:03","ipAddress":"192.0.2.50","cidr":"192.0.2.0/24"}
        self.sha=credential_configuration_sha256(self.desired)
        self.generation={"instanceUuid":self.scope["instanceUuid"],"operationUuid":str(uuid.uuid4()),"revision":7,"configurationSha256":self.sha}
        self.observed={"generation":self.generation,"generationStatus":"IN_SYNC","pendingOperationUuid":None,"configurationSha256":self.sha,"configurationDesiredState":self.desired}
    def test_native_seven_and_source_generation_travel_inside_real_authenticated_identity_capsule(self):
        value=root_configuration_from_observation(self.scope,self.observed)
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        aad=self.scope["instanceUuid"]+":"+self.scope["operationUuid"]
        capsule=encrypt({"schemaVersion":1,"files":{},"accounts":{},"nvmeHosts":{},"rootSourceConfiguration":value},public,aad)
        restored=decrypt(capsule,private,aad)
        self.assertEqual(self.desired,root_configuration_authorize(restored,self.scope,self.sha));self.assertEqual(self.generation,restored["rootSourceConfiguration"]["sourceGeneration"])
        self.assertNotIn(private,str(capsule));self.assertNotIn("macAddress",str(capsule))
    def test_pending_unverified_or_foreign_source_observation_cannot_create_a_latest_source_claim(self):
        for change in ({"pendingOperationUuid":self.scope["operationUuid"]},{"generationStatus":"RECOVERY_REQUIRED"},{"generation":{**self.generation,"instanceUuid":str(uuid.uuid4())}}):
            with self.assertRaises(ValueError):root_configuration_from_observation(self.scope,{**self.observed,**change})
    def test_retained_authorization_rejects_legacy_missing_header_foreign_scope_and_source_hash(self):
        with self.assertRaises(ValueError):root_configuration_authorize({"schemaVersion":1},self.scope,self.sha)
        payload={"rootSourceConfiguration":root_configuration_from_observation(self.scope,self.observed)}
        with self.assertRaises(ValueError):root_configuration_authorize(payload,{**self.scope,"operationUuid":str(uuid.uuid4())},self.sha)
        with self.assertRaises(ValueError):root_configuration_authorize(payload,self.scope,"f"*64)
    def test_boolean_revision_wrong_seven_hash_or_secret_bearing_declaration_is_not_a_root_header(self):
        valid=root_configuration_from_observation(self.scope,self.observed)
        for change in ("scope","generation","seven","secret"):
            value=copy.deepcopy(valid)
            if change=="scope":value["scope"]["revision"]=True
            elif change=="generation":value["sourceGeneration"]["revision"]=True
            elif change=="seven":value["configurationDesiredState"]["network-endpoints.json"]={"changed":True}
            else:value["configurationDesiredState"]["sharedfs-network.json"]["password"]="SYNTHETIC_ONLY"
            with self.assertRaises(ValueError):validate_root_configuration(value)

if __name__=="__main__":unittest.main()
