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

import base64
import copy
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
from identity_capsule import encrypt
from semantic_ad_source import semantic_original_source,semantic_new_target,semantic_same_target


def synthetic_original_source():
    private=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    pem=private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
    public=private.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    instance=str(uuid.uuid4());operation=str(uuid.uuid4())
    identity={"schemaVersion":1,"domain":"ablestack.local","realm":"ABLESTACK.LOCAL","workgroup":"ABLESTACK","netbiosName":"ORIGINAL",
              "machineSid":"S-1-5-21-9-8-7","domainSid":"S-1-5-21-4-5-6","machineAccountSid":"S-1-5-21-4-5-6-1000","trustVerified":True,
              "servicePrincipals":["cifs/original.ablestack.local","host/original.ablestack.local"],
              "dnsAliases":[{"hostname":"original.ablestack.local","addresses":["10.10.13.239"]}],
              "idmapPolicy":{"default":{"backend":"tdb","range":[10000,60000]},"domain":{"backend":"rid","range":[1000000,1999999],"baseRid":0}},
              "machineConfigurationSha256":"f"*64}
    payload={"schemaVersion":1,"files":{},"accounts":{},"adIdentity":identity,"sourceConfigurationSha256":"a"*64}
    capsule=encrypt(payload,public,instance+":"+operation)
    descriptor={"schemaVersion":1,"kind":"STORAGE_AD_SEMANTIC_SOURCE","ownerArtifactUuid":str(uuid.uuid4()),"sourceInstanceUuid":instance,
                "sourceOperationUuid":operation,"sourceConfigurationSha256":"a"*64,"ciphertextSha256":capsule["sha256"]}
    der=private.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
    descriptor["issuerMac"]=hmac.new(hashlib.sha256(der).digest(),json.dumps(descriptor,sort_keys=True,separators=(",",":")).encode(),hashlib.sha256).hexdigest()
    return {"originalSourceAuthority":descriptor,"originalSourceCapsule":capsule,"originalSourceCredentialPrivateKey":pem},payload,public


class StorageAdSemanticSourceTest(unittest.TestCase):
    def setUp(self):
        self.request,self.payload,self.public=synthetic_original_source()
    def test_real_rsa_aead_original_matches_exact_issuer_mac_scope_and_source_configuration_without_effects(self):
        before=copy.deepcopy(self.request)
        with patch("os.open",side_effect=AssertionError("file open")),patch("subprocess.run",side_effect=AssertionError("process")):
            result=semantic_original_source(self.request)
        self.assertEqual(self.payload,result["payload"]);self.assertEqual(before,self.request)
        self.assertNotIn(self.request["originalSourceCredentialPrivateKey"],json.dumps(result["descriptor"]))
    def test_plain_identity_wrong_descriptor_kind_shape_boolean_and_mac_are_never_original_authority(self):
        for change in ({"sourceIdentity":self.payload["adIdentity"]},
                       {"originalSourceAuthority":{**self.request["originalSourceAuthority"],"schemaVersion":True}},
                       {"originalSourceAuthority":{**self.request["originalSourceAuthority"],"kind":"SAMEVM_ROOT"}},
                       {"originalSourceAuthority":{**self.request["originalSourceAuthority"],"extra":True}},
                       {"originalSourceAuthority":{**self.request["originalSourceAuthority"],"issuerMac":"b"*64}}):
            with self.subTest(fields=tuple(change)),self.assertRaises(ValueError):semantic_original_source({**self.request,**change})
    def test_wrong_original_operation_cipher_digest_or_private_key_refuses(self):
        capsule=self.request["originalSourceCapsule"];other,_,_=synthetic_original_source()
        changes=({"originalSourceCapsule":{**capsule,"scope":str(uuid.uuid4())+":"+str(uuid.uuid4())}},
                 {"originalSourceCapsule":{**capsule,"ciphertext":base64.b64encode(b"forged").decode()}},
                 {"originalSourceCredentialPrivateKey":other["originalSourceCredentialPrivateKey"]})
        for change in changes:
            with self.subTest(fields=tuple(change)),self.assertRaises(ValueError):semantic_original_source({**self.request,**change})
    def test_valid_cipher_for_different_decoded_configuration_or_incomplete_ad_metadata_is_rejected(self):
        for payload in ({**self.payload,"sourceConfigurationSha256":"b"*64},
                        {**self.payload,"adIdentity":{key:value for key,value in self.payload["adIdentity"].items() if key!="machineAccountSid"}}):
            capsule=encrypt(payload,self.public,self.request["originalSourceCapsule"]["scope"])
            request=copy.deepcopy(self.request);request["originalSourceCapsule"]=capsule;request["originalSourceAuthority"]["ciphertextSha256"]=capsule["sha256"]
            private=serialization.load_pem_private_key(request["originalSourceCredentialPrivateKey"].encode(),password=None)
            der=private.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
            values={key:value for key,value in request["originalSourceAuthority"].items() if key!="issuerMac"}
            request["originalSourceAuthority"]["issuerMac"]=hmac.new(hashlib.sha256(der).digest(),json.dumps(values,sort_keys=True,separators=(",",":")).encode(),hashlib.sha256).hexdigest()
            with self.assertRaises(ValueError):semantic_original_source(request)
    def test_new_target_requires_distinct_sam_computer_aliases_and_original_exact_mapping(self):
        original=semantic_original_source(self.request);identity=original["identity"]
        public={**identity,"netbiosName":"TARGET","dnsAliases":[{"hostname":"target.ablestack.local","addresses":["10.10.13.240"]}],
                "servicePrincipals":["cifs/target.ablestack.local","host/target.ablestack.local"]}
        source={"publicLocalMachineSid":"S-1-5-21-1-2-3","adIdentity":None};target=str(uuid.uuid4())
        self.assertEqual(identity,semantic_new_target(original,public,source,target))
        for changed in ({**source,"publicLocalMachineSid":identity["machineSid"]},{**source,"adIdentity":identity}):
            with self.assertRaises(ValueError):semantic_new_target(original,public,changed,target)
        with self.assertRaises(ValueError):semantic_new_target(original,public,source,original["descriptor"]["sourceInstanceUuid"])
        for changed in ({**public,"netbiosName":identity["netbiosName"]},{**public,"dnsAliases":identity["dnsAliases"]},{**public,"idmapPolicy":{}}):
            with self.assertRaises(ValueError):semantic_new_target(original,changed,source,target)

    def test_same_vm_requires_authenticated_same_instance_and_exact_fresh_source_identity(self):
        original=semantic_original_source(self.request);identity=original["identity"]
        source={"publicLocalMachineSid":identity["machineSid"],"adIdentity":identity}
        target=original["descriptor"]["sourceInstanceUuid"]
        self.assertEqual(identity,semantic_same_target(original,identity,source,target))
        with self.assertRaises(ValueError):semantic_same_target(original,identity,source,str(uuid.uuid4()))
        for field in ("machineSid","machineAccountSid","machineConfigurationSha256","idmapPolicy","dnsAliases"):
            with self.subTest(field=field),self.assertRaises(ValueError):
                semantic_same_target(original,identity,{**source,"adIdentity":{**identity,field:None}},target)

    def test_distinct_manager_master_and_checkpoint_rsa_roles_unwrap_then_verify_checkpoint_derived_issuer(self):
        from identity_capsule import decrypt
        master=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        master_private=master.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        master_public=master.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        sealed=encrypt({"checkpointPrivateKey":self.request["originalSourceCredentialPrivateKey"]},master_public,"synthetic-manager-vault")
        original_key=decrypt(sealed,master_private,"synthetic-manager-vault")["checkpointPrivateKey"]
        self.assertNotEqual(master_private,original_key)
        descriptor=self.request["originalSourceAuthority"];values={key:value for key,value in descriptor.items() if key!="issuerMac"}
        master_der=master.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
        wrong_mac=hmac.new(hashlib.sha256(master_der).digest(),json.dumps(values,sort_keys=True,separators=(",",":")).encode(),hashlib.sha256).hexdigest()
        with self.assertRaises(ValueError):semantic_original_source({**self.request,"originalSourceAuthority":{**descriptor,"issuerMac":wrong_mac},"originalSourceCredentialPrivateKey":original_key})
        result=semantic_original_source({**self.request,"originalSourceCredentialPrivateKey":original_key})
        self.assertEqual(self.payload,result["payload"])

    def test_actual_signed_cli_original_decoder_uses_protected_stdin_and_returns_only_public_original_receipt(self):
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl";scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":2}
        request={**scope,**self.request}
        result=subprocess.run([str(cli),"identity","capsule","semantic-original","/dev/stdin"],input=json.dumps(request),capture_output=True,text=True,timeout=15)
        self.assertEqual(0,result.returncode,result.stderr);response=json.loads(result.stdout)
        self.assertEqual(scope,response["scope"]);self.assertEqual(self.payload["adIdentity"],response["originalIdentity"])
        self.assertEqual({"success","scope","originalSourceAuthority","originalIdentity"},set(response))
        self.assertNotIn(self.request["originalSourceCredentialPrivateKey"],result.stdout+result.stderr)
        request["originalSourceAuthority"]={**request["originalSourceAuthority"],"issuerMac":"0"*64}
        result=subprocess.run([str(cli),"identity","capsule","semantic-original","/dev/stdin"],input=json.dumps(request),capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,result.returncode);self.assertNotIn(self.request["originalSourceCredentialPrivateKey"],result.stdout+result.stderr)

if __name__=="__main__":unittest.main()
