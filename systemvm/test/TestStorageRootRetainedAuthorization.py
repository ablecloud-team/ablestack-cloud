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

"""Retained ROOT real AEAD/sealed input and old/latest checkpoint split."""
from pathlib import Path
import copy
import fcntl
import json
import os
import sys
import subprocess
import unittest
import uuid
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from TestStorageRootSourceRecovery import StorageRootSourceRecoveryTest
from root_retained_authorization import RootRetainedAuthorization
from root_configuration_capsule import root_configuration_from_observation
from root_identity_reference import RootIdentityReference
from rendered_credentials import credential_configuration_sha256,credential_sealed_input
from rendered_driver import RenderedDriver
from rendered_generation import DOMAINS,REQUIRED,rendered_json
from identity_capsule import encrypt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


class StorageRootRetainedAuthorizationTest(StorageRootSourceRecoveryTest):
    def setUp(self):
        super().setUp()
        self.old_generation=copy.deepcopy(self.generation);self.old_sha=self.actual["configurationSha256"]
        self.old_pointer=self.driver.store.status()["current"]["manifestSha256"];self.effects=[]
        self.reference_root=self.root/"references";self.generation_root=self.root/"native"
        self.generation_root.mkdir(mode=0o700)
        self.env2=patch.dict(os.environ,{"ABLESTACK_STORAGE_ROOT_IDENTITY_REFERENCES":str(self.reference_root),"ABLESTACK_STORAGE_GENERATION_DIR":str(self.generation_root)})
        self.env2.start();self.addCleanup(self.env2.stop)
        runtime=self.driver.runtime;runtime.cli=str(LIB.parent.parent/"bin/ablestack-storagectl")
        runtime.iscsi_root=self.root/"iscsi";runtime.nvme_root=self.root/"nvmet"
        runtime.remaining=lambda limit:limit
        runtime.iscsi_root.mkdir(mode=0o700);discovery=runtime.iscsi_root/"discovery_auth";discovery.mkdir(mode=0o700)
        for name in ("userid","password","userid_mutual","password_mutual"):(discovery/name).touch(mode=0o600)
        native=runtime.command
        def command(args,payload=None):
            if args==("operation","generation","status"):return self.actual
            if args==("operation","verify"):return {"success":True,"nfsGanesha":{"active":0},"smbRuntime":{"runtimeEndpoints":[]},"listenPorts":{"nfs":False,"smb":False,"nvmeof":True}}
            if args==("operation","root-data","inspect"):
                row=payload["volumes"][0];return {"success":True,"volumes":[{**row,"serial":row["volumeUuid"].replace("-","")[:20],"matchedBy":"VOLUME_SERIAL","mappingStatus":"EXACT","filesystemUuid":self.filesystem}]}
            return native(args)
        runtime.command=command
        self.driver.root_source=self.recovery
        self.retained=RootRetainedAuthorization(self.driver,self.root/"protected")
        self.retained.listeners_clear=lambda:None
        self.latest=copy.deepcopy(self.actual["configurationDesiredState"])
        self.latest["iscsi-targets.json"]={"enabled":False,"targets":[]}
        self.latest_sha=credential_configuration_sha256(self.latest)
        source={"generation":{**self.old_generation,"operationUuid":str(uuid.uuid4()),"configurationSha256":self.latest_sha},
                "configurationSha256":self.latest_sha,"configurationDesiredState":self.latest,"generationStatus":"IN_SYNC","pendingOperationUuid":None}
        header=root_configuration_from_observation(self.marker["scope"],source)
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        self.payload={"schemaVersion":1,"files":{},"accounts":{},"nvmeHosts":{},"sourceConfigurationSha256":self.latest_sha,"rootSourceConfiguration":header}
        self.capsule=encrypt(self.payload,self.public,self.request["instanceUuid"]+":"+self.request["operationUuid"])
        self.volume=str(uuid.uuid4());self.filesystem=str(uuid.uuid4());self.bindings=[{"volumeUuid":self.volume,"sizeBytes":65536,"filesystemUuid":self.filesystem}]

    def captured(self):
        return self.retained.capture({**self.marker["scope"],"expectedRetainedGeneration":self.old_generation,"expectedRetainedRenderedSha256":self.old_pointer})

    def request_auth(self):
        captured=self.captured()
        # Models the authenticated same-op import's native ciphertext reference;
        # no caller outer latest7 is accepted by the authorizer.
        RootIdentityReference().retain(self.marker["scope"],self.capsule,self.latest_sha)
        return {**self.marker["scope"],"baselineRef":captured["baselineRef"],"capsule":self.capsule,"credentialPrivateKey":self.private,
                "originalSourceScope":self.marker["scope"],"sourceConfigurationSha256":self.latest_sha,"fileVolumeBindings":self.bindings}

    def authorize_sealed(self,request):
        fd=os.memfd_create("synthetic-retained-root",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
        try:
            os.fchmod(fd,0o600);os.write(fd,json.dumps(request).encode());os.lseek(fd,0,os.SEEK_SET)
            fcntl.fcntl(fd,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
            credential_sealed_input("/proc/self/fd/"+str(fd))
            return self.retained.authorize(json.loads(os.read(fd,8*1024*1024)))
        finally:os.close(fd)

    def actual_driver(self):
        driver=RenderedDriver.__new__(RenderedDriver);driver.store=self.driver.store;driver.runtime=self.driver.runtime
        driver.root_source=self.recovery;driver.root_retained=self.retained;driver.checkpoints=self.root/"checkpoints"
        driver.verify=lambda path:(_ for _ in ()).throw(AssertionError("historical live runtime must never be verified"))
        driver.validate=lambda *args:{domain:True for domain in DOMAINS}
        def render(request):
            self.assertEqual(self.latest,request["configurationDesiredState"])
            files={name:"{}" for name in REQUIRED};files["desired-state.json"]=json.dumps(self.latest)
            files["block/iscsi-plan.json"]=json.dumps({"protocol":"ISCSI","targets":[]})
            files["block/nvmeof-plan.json"]=json.dumps({"protocol":"NVMEOF","targets":[]})
            return files
        driver.render=render
        return driver

    def stage_request(self,authorized):
        scope={key:self.request[key] for key in ("instanceUuid","operationUuid","revision")}
        rendered_json(self.generation_root/"pending.json",{**scope,"phase":"PREPARED","previous":self.old_generation,"beforeSha256":self.old_sha})
        self.actual.update(pendingOperationUuid=scope["operationUuid"],generationStatus="PENDING")
        return {**scope,"previousGeneration":self.old_generation,"expectedCurrentRenderedSha256":self.old_pointer,"configurationDesiredState":self.latest,
                "protocolDesiredState":{"NFS":None,"SMB":None,"ISCSI":self.latest["iscsi-targets.json"],"NVMEOF":None},
                "credentialRefs":{},"checkpointPublicKey":self.public,"retainedRootAuthorization":authorized["retainedRootAuthorization"]}

    def test_real_aead_sealed_authorization_and_stage_separate_latest_identity_sha_from_old_native_pointer(self):
        before=self.recovery.canonical_bytes();pointer=self.driver.store.pointer()
        authorized=self.authorize_sealed(self.request_auth());self.assertTrue(authorized["retainedRootAuthorized"])
        self.assertEqual(self.old_generation,authorized["retainedGeneration"]);self.assertEqual(self.latest_sha,authorized["latestConfigurationSha256"])
        request=self.stage_request(authorized);driver=self.actual_driver();staged=driver.execute("render-stage",request)
        self.assertEqual("STAGED",staged["phase"]);self.assertEqual(self.old_sha,staged["sourceConfigurationSha256"])
        self.assertEqual(self.latest_sha,staged["identityCheckpointSourceConfigurationSha256"]);self.assertFalse(staged["historicalSourceRuntimeVerified"])
        checkpoint=json.loads((driver.checkpoints/(self.request["operationUuid"]+".json")).read_text())
        self.assertEqual(self.latest_sha,checkpoint["sourceConfigurationSha256"]);self.assertEqual(pointer,self.driver.store.pointer());self.assertEqual(before,self.recovery.canonical_bytes())
        for path in self.root.rglob("*"):
            if path.is_file():self.assertNotIn(self.private.encode(),path.read_bytes())
        self.assertEqual(self.old_generation,json.loads((self.generation_root/"pending.json").read_text())["previous"])

    def test_plaintext_latest7_forged_scope_or_unknown_field_refuses_before_new_authorization_files(self):
        request=self.request_auth();before={path.name for path in self.retained.root.iterdir()}
        target=self.driver.runtime.iscsi_root/"iqn.2026-10.local.storage:live";target.mkdir()
        with self.assertRaisesRegex(ValueError,"kernel block target"):self.retained.capture({**self.marker["scope"],"expectedRetainedGeneration":self.old_generation,"expectedRetainedRenderedSha256":self.old_pointer})
        self.assertEqual(before,{path.name for path in self.retained.root.iterdir()});target.rmdir()
        unknown=self.driver.runtime.iscsi_root/"unknown";unknown.touch()
        with self.assertRaisesRegex(ValueError,"unknown entry"):self.retained.capture({**self.marker["scope"],"expectedRetainedGeneration":self.old_generation,"expectedRetainedRenderedSha256":self.old_pointer})
        unknown.unlink()
        for change in ({"configurationDesiredState":self.latest},{"scope":{"caller":"fake"}},{"templateUpgradeUuid":str(uuid.uuid4())},{"revision":True}):
            with self.subTest(field=tuple(change)):
                with self.assertRaises((ValueError,KeyError)):self.authorize_sealed({**request,**change})
                self.assertEqual(before,{path.name for path in self.retained.root.iterdir()})
        self.assertEqual(self.old_pointer,self.driver.store.status()["current"]["manifestSha256"])
        cli=LIB.parent.parent/"bin/ablestack-storagectl"
        body=cli.read_text().split("<<'PYRENDEREDGENERATION'\n",1)[1].split("\nPYRENDEREDGENERATION",1)[0]
        fd=os.memfd_create("unsealed-retained-negative",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
        try:
            os.fchmod(fd,0o600);os.write(fd,json.dumps(request).encode());os.lseek(fd,0,os.SEEK_SET)
            outcome=subprocess.run([sys.executable,"-","render-root-authorize-retained","/proc/self/fd/"+str(fd),str(cli)],
                                   input=body,pass_fds=(fd,),capture_output=True,text=True,timeout=5)
        finally:os.close(fd)
        self.assertNotEqual(0,outcome.returncode);self.assertNotIn(self.private,outcome.stdout+outcome.stderr)
        self.assertEqual("RENDERED_GENERATION_REJECTED",json.loads(outcome.stdout)["errorCode"])
        self.assertEqual(before,{path.name for path in self.retained.root.iterdir()})

    def test_old_baseline_latest_hash_or_target7_mixing_has_zero_pointer_and_checkpoint_effects(self):
        request=self.request_auth();before=self.recovery.canonical_bytes()
        with self.assertRaises(ValueError):self.authorize_sealed({**request,"sourceConfigurationSha256":self.old_sha})
        authorized=self.authorize_sealed(request);stage=self.stage_request(authorized);driver=self.actual_driver()
        with self.assertRaises(ValueError):driver.execute("render-stage",{**stage,"configurationDesiredState":self.actual["configurationDesiredState"]})
        self.assertFalse(driver.checkpoints.exists());self.assertEqual(before,self.recovery.canonical_bytes())
        self.assertEqual(self.old_pointer,self.driver.store.status()["current"]["manifestSha256"])

    def test_retained_generic_historical_rollback_is_refused_before_crypto_protocol_or_permission_effects(self):
        authorized=self.authorize_sealed(self.request_auth());request=self.stage_request(authorized);driver=self.actual_driver()
        with self.assertRaisesRegex(ValueError,"historical rollback"):driver.execute("render-rollback",request)
        self.assertFalse(driver.checkpoints.exists());self.assertEqual(self.old_pointer,self.driver.store.status()["current"]["manifestSha256"])
        self.assertTrue(self.marker["bootHeld"])


for name in tuple(vars(StorageRootSourceRecoveryTest)):
    if name.startswith("test_") and name not in vars(StorageRootRetainedAuthorizationTest):setattr(StorageRootRetainedAuthorizationTest,name,None)
del StorageRootSourceRecoveryTest

if __name__=="__main__":unittest.main()
