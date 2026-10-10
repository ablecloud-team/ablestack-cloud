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
import json
import os
from pathlib import Path
import sys
import unittest
import uuid
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import TestStorageServiceIdentitySource as service_source_tests
from service_identity_cipher import ServiceIdentityCipher
from rendered_driver import RenderedDriver
from rendered_generation import rendered_json
from identity_capsule import encrypt,decrypt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


class StorageServiceIdentityCipherTest(unittest.TestCase):
    def setUp(self):
        self.fixture=service_source_tests.StorageServiceIdentitySourceTest("test_prestop_capture_contains_only_public_sam_ad_and_existing_policy_metadata")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        f=self.fixture;f.source.capture(f.scope);f.journal();f.source.stopped(f.scope)
        self.source=f.source.export_source(f.scope);self.scope={key:f.scope[key] for key in ("instanceUuid","operationUuid","revision")}
        self.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.public=self.key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.private=self.key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        self.old={"schemaVersion":1,"files":{"/var/lib/samba/private/secrets.tdb":{"data":base64.b64encode(b"SYNTHETIC_OLD_IDENTITY").decode(),"uid":0,"gid":0,"mode":0o600}},"accounts":{},"sourceConfigurationSha256":"a"*64}
        self.capsule=encrypt(self.old,self.public,self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
        self.cipher=ServiceIdentityCipher(f.maintenance_root)
        self.cipher.retain({**f.scope,"publicKey":self.public},self.capsule,self.source)
        self.request={**self.scope,"checkpointPublicKey":self.public}
        self.driver=object.__new__(RenderedDriver);self.driver.root_source=f.source;self.driver.checkpoints=f.root/"identity-checkpoints"
        self.driver.store=type("Store",(),{"scope":lambda ignored,request:self.scope})()
        self.commands=[]
        def command(args,payload=None):
            self.commands.append(args)
            if args==("operation","maintenance","status"):return f.maintenance.status()
            raise AssertionError("Post-JOIN source RAW/private identity recollection is prohibited")
        self.driver.runtime=type("Runtime",(),{"command":lambda ignored,args,payload=None:command(args,payload)})()
    def test_stage_reuses_exact_beforejoin_source_cipher_after_private_joined_state_changes(self):
        f=self.fixture;rendered_json(f.config/"smb-domain.json",{"state":"JOINED","targetIdentity":True})
        with self.assertRaises(ValueError):f.source.export_source(f.scope)
        result=self.driver.checkpoint(self.request,f.actual)
        self.assertEqual(self.capsule,result["capsule"]);self.assertEqual("a"*64,result["sourceConfigurationSha256"])
        self.assertEqual(self.old,decrypt(result["capsule"],self.private,self.scope["instanceUuid"]+":"+self.scope["operationUuid"]))
        self.assertEqual([("operation","maintenance","status")],self.commands)
        raw=self.cipher.path(f.scope).read_bytes()
        self.assertNotIn(b"SYNTHETIC_OLD_IDENTITY",raw);self.assertNotIn(self.private.encode(),raw)
    def test_wrong_key_changed_canonical_seven_or_foreign_marker_refuses_before_checkpoint_publication(self):
        f=self.fixture;wrong=rsa.generate_private_key(public_exponent=65537,key_size=2048).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        with self.assertRaises(ValueError):self.driver.checkpoint({**self.request,"checkpointPublicKey":wrong},f.actual)
        self.assertFalse(self.driver.checkpoints.exists())
        rendered_json(f.config/"iscsi-targets.json",{"enabled":False})
        with self.assertRaises(ValueError):self.driver.checkpoint(self.request,f.actual)
        self.assertFalse(self.driver.checkpoints.exists());(f.config/"iscsi-targets.json").unlink()
        marker=f.maintenance.status();marker["scope"]={**f.scope,"operationUuid":str(uuid.uuid4())}
        with self.assertRaises(ValueError):self.cipher.authorize(self.request,f.actual,marker,f.source.canonical_bytes())
        self.assertFalse(self.driver.checkpoints.exists())
    def test_missing_beforejoin_cipher_or_tampered_captured_record_cannot_fall_back_to_current_identity(self):
        f=self.fixture;path=self.cipher.path(f.scope);original=path.read_bytes();path.unlink()
        with self.assertRaises(OSError):self.driver.checkpoint(self.request,f.actual)
        self.assertFalse(self.driver.checkpoints.exists());path.write_bytes(original);path.chmod(0o600)
        record=self.cipher.read(self.cipher.source_path(f.scope));record["sourceGeneration"]={**record["sourceGeneration"],"revision":6};rendered_json(self.cipher.source_path(f.scope),record)
        with self.assertRaises(ValueError):self.driver.checkpoint(self.request,f.actual)
        self.assertFalse(self.driver.checkpoints.exists())
    def test_producer_rejects_foreign_scope_hash_or_second_cipher_and_exposes_no_publish_rpc(self):
        f=self.fixture;before=self.cipher.path(f.scope).read_bytes()
        for source in ({**self.source,"scope":{**f.scope,"maintenanceUuid":str(uuid.uuid4())}},{**self.source,"stoppedReceiptSha256":"f"*64}):
            with self.assertRaises(ValueError):self.cipher.retain({**f.scope,"publicKey":self.public},self.capsule,source)
            self.assertEqual(before,self.cipher.path(f.scope).read_bytes())
        second=encrypt(self.old,self.public,self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
        with self.assertRaises(ValueError):self.cipher.retain({**f.scope,"publicKey":self.public},second,self.source)
        self.assertEqual(before,self.cipher.path(f.scope).read_bytes())
        cli=(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_text()
        self.assertNotIn("render-service-retain-identity",cli)


if __name__=="__main__":unittest.main()
