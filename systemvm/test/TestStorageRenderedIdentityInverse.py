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

import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
from rendered_driver import RenderedDriver
from rendered_generation import DESIRED_PATHS,rendered_json
from identity_capsule import encrypt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


class StorageRenderedIdentityInverseTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.root.chmod(0o700)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":3}
        self.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.public=self.key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.private=self.key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        self.identity={"schemaVersion":1,"files":{},"accounts":{}}
        self.saved={"scope":self.scope,"sourceConfigurationSha256":"a"*64,"publicKey":self.public,"capsule":encrypt(self.identity,self.public,self.scope["instanceUuid"]+":"+self.scope["operationUuid"])}
        rendered_json(self.root/"desired-state.json",{name:None for name in DESIRED_PATHS})
        self.cli=self.root/"source-storagectl";self.cli.write_bytes((ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_bytes())
        self.driver=object.__new__(RenderedDriver);self.calls=[]
        self.driver.store=type("Store",(),{"scoped_activation":lambda ignored,request:{"changedDomains":["ISCSI"],"startedDomains":["ISCSI"]},
                                         "pointer":lambda ignored:self.root,"inspect":lambda ignored,path:{"configurationSha256":"a"*64}})()
        self.driver.runtime=type("Runtime",(),{"cli":str(self.cli),"credentials":{},"command":lambda ignored,args,payload=None:self.calls.append((args,payload)) or {"success":True}})()
        self.driver.recovery_key=lambda request:self.saved
        self.request={**self.scope,"checkpointPrivateKey":self.private}
    def test_actual_signed_codec_binop_size_constant_allows_real_rsa_aead_source_inverse(self):
        self.driver.restore_identity(self.request)
        self.assertEqual(1,len(self.calls));arguments,payload=self.calls[0]
        self.assertEqual(("identity","capsule","import"),arguments);self.assertEqual(self.saved["capsule"],payload["capsule"])
        self.assertEqual(["ISCSI"],payload["restoreDomains"]);self.assertTrue(payload["deferNvmeReplay"]);self.assertEqual(self.scope["instanceUuid"],payload["instanceUuid"])
        self.assertFalse(any(self.private.encode() in path.read_bytes() for path in self.root.rglob("*") if path.is_file()))
    def test_wrong_private_key_refuses_before_any_identity_import(self):
        wrong=rsa.generate_private_key(public_exponent=65537,key_size=2048).private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        with self.assertRaises(ValueError):self.driver.restore_identity({**self.request,"checkpointPrivateKey":wrong})
        self.assertEqual([],self.calls)
    def test_forged_or_executable_size_constant_refuses_before_decrypt_or_import(self):
        original=self.cli.read_text()
        for expression in ("16 * 1024 * 1024","__import__('os').system('false')","8388608"):
            self.cli.write_text(original.replace("MAX_CAPSULE_BYTES = 8 * 1024 * 1024","MAX_CAPSULE_BYTES = "+expression,1))
            with self.assertRaises(ValueError):self.driver.restore_identity(self.request)
            self.assertEqual([],self.calls)


if __name__=="__main__":unittest.main()
