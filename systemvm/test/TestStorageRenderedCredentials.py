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

# Synthetic crypto inputs and private keys are created only in this test process.
from pathlib import Path
import base64, copy, hashlib, importlib.util, json, os, unittest, uuid
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
spec=importlib.util.spec_from_file_location("rendered_credentials",Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage/rendered_credentials.py")
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

class TargetCredentialArtifactTest(unittest.TestCase):
    def setUp(self):
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":5}
        self.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.private=self.key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        self.public=self.key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.acls={domain:str(uuid.uuid4()) for domain in ("SMB","ISCSI","NVMEOF")}
        self.share=str(uuid.uuid4());self.target="iqn.2026-10.local.storage:target";self.nqn="nqn.2026-10.local.storage:target"
        self.desired={name:None for name in mod.CREDENTIAL_CANONICAL_PATHS}
        self.desired[mod.CREDENTIAL_PROTOCOL_PATHS["SMB"]]={"enabled":True,"shares":[{"uuid":self.share,"acls":[{"uuid":self.acls["SMB"],"principal":"testuser","principalType":"LOCAL_USER"}]}]}
        self.desired[mod.CREDENTIAL_PROTOCOL_PATHS["ISCSI"]]={"targets":[{"uuid":str(uuid.uuid4()),"targetName":self.target,"acls":[{"uuid":self.acls["ISCSI"],"principal":"iqn.2026-10.example:client","config":{"chapEnabled":True,"mutualChapEnabled":True}}]}]}
        self.desired[mod.CREDENTIAL_PROTOCOL_PATHS["NVMEOF"]]={"subsystems":[{"uuid":str(uuid.uuid4()),"targetName":self.nqn,"hosts":[{"uuid":self.acls["NVMEOF"],"principal":"nqn.2026-10.example:client","config":{"dhChapEnabled":True,"dhChapCtrlEnabled":True}}]}]}
        self.inputs={"SMB":{self.acls["SMB"]:{"password":"SYNTHETIC_ONLY_A"}},"ISCSI":{self.acls["ISCSI"]:{"chapSecret":"SYNTHETIC_ONLY_B","mutualChapSecret":"SYNTHETIC_ONLY_C"}},"NVMEOF":{self.acls["NVMEOF"]:{"dhChapKey":"SYNTHETIC_ONLY_D","dhChapCtrlKey":"SYNTHETIC_ONLY_E"}}}
        self.source_sha="a"*64;self.aad=self.scope["instanceUuid"]+":"+self.scope["operationUuid"]
        checkpoint=self.encrypt({"schemaVersion":1,"syntheticSource":True})
        self.saved={"schemaVersion":1,"scope":dict(self.scope),"sourceConfigurationSha256":self.source_sha,"publicKey":self.public,"capsule":checkpoint}
        self.request={**self.scope,"checkpointPrivateKey":self.private,"identityCheckpointRef":{"operationUuid":self.scope["operationUuid"],"sha256":checkpoint["sha256"]},"transientCredentials":{"target":{},"previous":{}}}
        source_ref={"kind":"IDENTITY_CHECKPOINT","operationUuid":self.scope["operationUuid"],"sourceConfigurationSha256":self.source_sha}
        self.refs={"SMB":{self.share:dict(source_ref)},"ISCSI":{self.target+"|iqn.2026-10.example:client":dict(source_ref)},"NVMEOF":{self.nqn+"|nqn.2026-10.example:client":dict(source_ref)}}
        self.payload={"schemaVersion":1,"kind":"RENDERED_TARGET_CREDENTIALS","scope":dict(self.scope),"configurationDesiredState":self.desired,"credentials":self.inputs}
        self.artifact_id=str(uuid.uuid4());self.install(self.encrypt(self.payload))
    def encrypt(self,payload,aad=None,raw=None):
        plain=raw if raw is not None else json.dumps(payload,separators=(",",":")).encode()
        key=os.urandom(32);nonce=os.urandom(12);cipher=AESGCM(key).encrypt(nonce,plain,(aad or self.aad).encode())
        wrapped=self.key.public_key().encrypt(key,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
        return {"schemaVersion":1,"scope":aad or self.aad,"wrappedKey":base64.b64encode(wrapped).decode(),"nonce":base64.b64encode(nonce).decode(),"ciphertext":base64.b64encode(cipher).decode(),"sha256":hashlib.sha256(cipher).hexdigest()}
    def install(self,envelope,raw=None):
        self.envelope=envelope
        raw=raw if raw is not None else json.dumps(envelope,separators=(",",":")).encode()
        digest=hashlib.sha256(raw).hexdigest()
        self.request["targetCredentialArtifact"]={"artifactUuid":self.artifact_id,"artifactSha256":digest,"encodedCapsule":base64.b64encode(raw).decode()}
        for domain,values in self.refs.items():
            for ref in values.values():
                ref["targetCredentialVersion"]={"kind":"TARGET_CREDENTIAL_CAPSULE","artifactUuid":self.artifact_id,"artifactSha256":digest,"operationUuid":self.scope["operationUuid"]}
                if domain=="SMB":ref["targetCredentialVersion"]["aclUuids"]=[self.acls["SMB"]]
    def parse(self):
        return mod.credential_target_inputs(self.request,self.desired,self.refs,self.saved,self.source_sha)
    def test_one_artifact_authenticates_all_three_domains_without_any_file_output(self):
        self.assertEqual(self.inputs,self.parse())
    def test_exact_raw_bytes_are_pinned_before_json_normalization(self):
        raw=base64.b64decode(self.request["targetCredentialArtifact"]["encodedCapsule"])+b" "
        self.request["targetCredentialArtifact"]["encodedCapsule"]=base64.b64encode(raw).decode()
        with self.assertRaisesRegex(ValueError,"raw-byte checksum"):self.parse()
    def test_foreign_aad_and_wrong_private_key_are_rejected(self):
        self.install(self.encrypt(self.payload,aad=str(uuid.uuid4())+":"+self.scope["operationUuid"]))
        with self.assertRaisesRegex(ValueError,"AAD scope"):self.parse()
        self.install(self.encrypt(self.payload))
        other=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.request["checkpointPrivateKey"]=other.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        with self.assertRaisesRegex(ValueError,"pinned public key"):self.parse()
    def test_crypto_tamper_is_rejected_even_after_repinning_raw_and_cipher_checksums(self):
        envelope=copy.deepcopy(self.envelope);cipher=bytearray(base64.b64decode(envelope["ciphertext"]));cipher[-1]^=1
        envelope["ciphertext"]=base64.b64encode(cipher).decode();envelope["sha256"]=hashlib.sha256(cipher).hexdigest();self.install(envelope)
        with self.assertRaisesRegex(ValueError,"authentication failed"):self.parse()
    def test_target_and_scope_types_cannot_use_python_boolean_integer_equality(self):
        changed=copy.deepcopy(self.payload);changed["scope"]["revision"]=True;self.install(self.encrypt(changed))
        with self.assertRaises(ValueError):self.parse()
        changed=copy.deepcopy(self.payload);changed["configurationDesiredState"][mod.CREDENTIAL_PROTOCOL_PATHS["SMB"]]["enabled"]=1;self.install(self.encrypt(changed))
        with self.assertRaisesRegex(ValueError,"target declaration"):self.parse()
    def test_foreign_and_inactive_acl_membership_is_rejected_before_replay(self):
        changed=copy.deepcopy(self.payload);foreign=str(uuid.uuid4());changed["credentials"]["ISCSI"]={foreign:{"chapSecret":"SYNTHETIC"}}
        self.install(self.encrypt(changed))
        with self.assertRaisesRegex(ValueError,"foreign or inactive"):self.parse()
        self.desired[mod.CREDENTIAL_PROTOCOL_PATHS["SMB"]]["shares"][0]["acls"][0]["state"]="Disabled";self.install(self.encrypt(self.payload))
        with self.assertRaisesRegex(ValueError,"inactive share ACL"):self.parse()
    def test_smb_password_cannot_bind_another_share_or_group(self):
        self.refs["SMB"][self.share]["targetCredentialVersion"]["aclUuids"]=[str(uuid.uuid4())]
        with self.assertRaisesRegex(ValueError,"foreign or inactive share ACL"):self.parse()
        self.install(self.encrypt(self.payload));self.desired[mod.CREDENTIAL_PROTOCOL_PATHS["SMB"]]["shares"][0]["acls"][0]["principalType"]="AD_GROUP";self.install(self.encrypt(self.payload))
        with self.assertRaisesRegex(ValueError,"authentication policy"):self.parse()
    def test_dual_plaintext_and_artifact_sources_are_rejected(self):
        self.request["transientCredentials"]["target"]=copy.deepcopy(self.inputs)
        with self.assertRaisesRegex(ValueError,"dual-source plaintext"):self.parse()
    def test_source_reference_and_checkpoint_ciphertext_are_pinned(self):
        self.refs["ISCSI"][next(iter(self.refs["ISCSI"]))]["sourceConfigurationSha256"]="b"*64
        with self.assertRaisesRegex(ValueError,"staged source"):self.parse()
        self.refs["ISCSI"][next(iter(self.refs["ISCSI"]))]["sourceConfigurationSha256"]=self.source_sha
        self.request["identityCheckpointRef"]["sha256"]="b"*64
        with self.assertRaisesRegex(ValueError,"pinned ciphertext"):self.parse()
    def test_secret_fields_must_be_enabled_on_the_exact_active_acl(self):
        self.desired[mod.CREDENTIAL_PROTOCOL_PATHS["ISCSI"]]["targets"][0]["acls"][0]["config"]["mutualChapEnabled"]=False;self.install(self.encrypt(self.payload))
        with self.assertRaisesRegex(ValueError,"authentication policy"):self.parse()
    def test_duplicate_json_keys_in_envelope_and_authenticated_plaintext_are_rejected(self):
        raw=json.dumps(self.envelope,separators=(",",":")).encode();raw=b'{"schemaVersion":1,'+raw[1:];self.install(self.envelope,raw)
        with self.assertRaisesRegex(ValueError,"duplicate JSON"):self.parse()
        raw=json.dumps(self.payload,separators=(",",":")).encode();raw=b'{"kind":"RENDERED_TARGET_CREDENTIALS",'+raw[1:]
        self.install(self.encrypt(None,raw=raw))
        with self.assertRaisesRegex(ValueError,"duplicate JSON"):self.parse()
    def test_missing_artifact_or_foreign_operation_version_is_rejected(self):
        self.request.pop("targetCredentialArtifact")
        with self.assertRaisesRegex(ValueError,"no encrypted artifact"):self.parse()
        self.install(self.encrypt(self.payload));self.refs["SMB"][self.share]["targetCredentialVersion"]["operationUuid"]=str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError,"another operation"):self.parse()

    def staged_driver(self):
        import sys, tempfile
        from unittest.mock import patch
        lib=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
        sys.path.insert(0,str(lib))
        from rendered_driver import RenderedDriver
        from rendered_generation import RenderedGeneration,REQUIRED,DOMAINS,rendered_json
        scratch=tempfile.TemporaryDirectory();self.addCleanup(scratch.cleanup);root=Path(scratch.name)
        store=RenderedGeneration(root/"render");driver=RenderedDriver("synthetic-unused-cli",store)
        source_desired={name:None for name in mod.CREDENTIAL_CANONICAL_PATHS}
        self.source_sha=mod.credential_configuration_sha256(source_desired)
        self.saved["sourceConfigurationSha256"]=self.source_sha
        for values in self.refs.values():
            for ref in values.values():ref["sourceConfigurationSha256"]=self.source_sha
        source_scope={**self.scope,"operationUuid":str(uuid.uuid4()),"revision":4,"expectedCurrentRenderedSha256":None}
        files={name:"{}" for name in REQUIRED}
        files.update({"desired-state.json":json.dumps(source_desired),"smb/smb.conf":"[global]\nsecurity = user\n","smb/manifest.json":json.dumps({"shares":[]}),
                      "block/iscsi-plan.json":json.dumps({"targets":[]}),"block/nvmeof-plan.json":json.dumps({"targets":[]})})
        old=store.stage(source_scope,files,lambda *args:{domain:True for domain in DOMAINS})
        source=store.generations/source_scope["operationUuid"];store.publish_pointer(source)
        rendered_json(store.journal,{"scope":old["scope"],"phase":"COMPLETE"})
        files["desired-state.json"]=json.dumps(self.desired)
        files["smb/manifest.json"]=json.dumps({"shares":[{"uuid":self.share,"credentialRefs":self.refs["SMB"][self.share]}]})
        for domain,filename,target,principal in (("ISCSI","block/iscsi-plan.json",self.target,"iqn.2026-10.example:client"),("NVMEOF","block/nvmeof-plan.json",self.nqn,"nqn.2026-10.example:client")):
            files[filename]=json.dumps({"targets":[{"targetName":target,"acls":[{"principal":principal,"credentialRef":self.refs[domain][target+"|"+principal]}]}]})
        target=store.stage({**self.scope,"expectedCurrentRenderedSha256":old["manifestSha256"]},files,lambda *args:{domain:True for domain in DOMAINS})
        rendered_json(driver.checkpoints/(self.scope["operationUuid"]+".json"),self.saved)
        request={**self.request,"renderedManifestSha256":target["manifestSha256"]}
        driver.generation=lambda:{"generation":old["scope"],"configurationSha256":self.source_sha,"pendingOperationUuid":self.scope["operationUuid"],"generationStatus":"IN_SYNC","configurationDesiredState":source_desired}
        events=[]
        def authorize(request):
            events.append(("authorize",None));path=root/"proof.json";rendered_json(path,{"scope":self.scope});return path
        driver.authorize_units=authorize;driver.verify=lambda path:{domain:True for domain in DOMAINS}
        driver.persist_desired=lambda path:events.append(("persist",None));driver.prerequisites.apply=lambda *args:None
        driver.runtime.require_drained=lambda domains:events.append(("drain",domains))
        driver.runtime.replay=lambda path,domain,rollback=False:events.append(("replay",copy.deepcopy(driver.runtime.credentials)))
        return root,driver,request,events,source
    def test_driver_decrypts_before_effects_uses_only_ram_and_clears_credentials_after_activation(self):
        root,driver,request,events,source=self.staged_driver()
        result=driver.execute("render-activate",request)
        self.assertEqual("VERIFIED",result["activation"]["phase"])
        self.assertEqual("authorize",events[0][0])
        self.assertTrue(all(value["target"]==self.inputs for event,value in events if event=="replay"))
        self.assertEqual({},driver.runtime.credentials)
        for path in root.rglob("*"):
            if path.is_file():
                content=path.read_bytes()
                self.assertNotIn(self.private.encode(),content)
                for values in self.inputs.values():
                    for item in values.values():
                        for secret in item.values():self.assertNotIn(secret.encode(),content)
    def test_driver_bad_artifact_and_bad_source_checkpoint_have_zero_authorization_or_pointer_effects(self):
        root,driver,request,events,source=self.staged_driver()
        request["targetCredentialArtifact"]["artifactSha256"]="f"*64
        with self.assertRaises(ValueError):driver.execute("render-activate",request)
        self.assertEqual([],events);self.assertEqual(source,driver.store.pointer());self.assertEqual("COMPLETE",driver.store.read_journal()["phase"])
        request["targetCredentialArtifact"]["artifactSha256"]=self.refs["SMB"][self.share]["targetCredentialVersion"]["artifactSha256"]
        from rendered_generation import rendered_json
        saved={**self.saved,"sourceConfigurationSha256":"e"*64};rendered_json(driver.checkpoints/(self.scope["operationUuid"]+".json"),saved)
        with self.assertRaises(ValueError):driver.execute("render-activate",request)
        self.assertEqual([],events);self.assertEqual(source,driver.store.pointer())
    def test_driver_authorization_failure_discards_heap_credentials_without_publication(self):
        root,driver,request,events,source=self.staged_driver()
        def denied(request):raise ValueError("injected writer ownership failure")
        driver.authorize_units=denied
        with self.assertRaisesRegex(ValueError,"writer ownership"):driver.execute("render-activate",request)
        self.assertEqual({},driver.runtime.credentials);self.assertEqual(source,driver.store.pointer());self.assertEqual([],events)

    def test_source_only_rollback_needs_no_target_artifact_and_never_injects_target_inputs(self):
        root,driver,request,events,source=self.staged_driver()
        driver.execute("render-activate",request);events.clear()
        request.pop("targetCredentialArtifact")
        def restore(request):
            events.append(("restore-source",None))
            self.assertEqual({},driver.runtime.credentials["target"])
            driver.runtime.credentials["previous"]={"SMB":{"syntheticRecoveredAcl":{"password":"RAM_PREVIOUS_ONLY"}}}
        driver.restore_identity=restore
        result=driver.execute("render-rollback",request)
        self.assertEqual("ROLLED_BACK",result["activation"]["phase"]);self.assertEqual(source,driver.store.pointer())
        self.assertTrue(any(event=="restore-source" for event,value in events))
        self.assertTrue(all(value["target"]=={} for event,value in events if event=="replay"));self.assertEqual({},driver.runtime.credentials)
        request["targetCredentialArtifact"]={"untrusted":"ignored"}
        with self.assertRaisesRegex(ValueError,"Source-only rollback"):driver.execute("render-rollback",request)

    def test_source_checkpoint_aead_is_validated_before_any_target_effect(self):
        root,driver,request,events,source=self.staged_driver()
        from rendered_generation import rendered_json
        saved=copy.deepcopy(self.saved)
        nonce=bytearray(base64.b64decode(saved["capsule"]["nonce"]));nonce[0]^=1;saved["capsule"]["nonce"]=base64.b64encode(nonce).decode()
        rendered_json(driver.checkpoints/(self.scope["operationUuid"]+".json"),saved)
        with self.assertRaisesRegex(ValueError,"Source identity checkpoint authentication"):driver.execute("render-activate",request)
        self.assertEqual([],events);self.assertEqual(source,driver.store.pointer())

    def test_protected_rpc_input_requires_actual_memfd_seals_and_owner_mode(self):
        import fcntl
        descriptor=os.memfd_create("synthetic-input-only",os.MFD_ALLOW_SEALING);self.addCleanup(os.close,descriptor)
        os.fchmod(descriptor,0o600);path="/proc/self/fd/"+str(descriptor)
        with self.assertRaises(ValueError):mod.credential_sealed_input(path)
        fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
        mod.credential_sealed_input(path)
        os.fchmod(descriptor,0o644)
        with self.assertRaises(ValueError):mod.credential_sealed_input(path)
        with self.assertRaises(ValueError):mod.credential_sealed_input("/tmp/unsafe-private-input")

if __name__=="__main__":unittest.main()
