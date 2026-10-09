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
import copy,json,os,sys,unittest,uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import TestStorageRootAdImportedAuthorization as imported_tests
from root_ad_identity_authority import root_ad_retained_authority
from root_ad_imported_authorization import root_ad_runtime_verified
from root_identity_target import RootIdentityTarget
from root_target_cipher import RootTargetCipher
from identity_capsule import encrypt,decrypt
from rendered_generation import DOMAINS,rendered_json
from root_configuration_capsule import root_configuration_from_observation


class StorageRootIdentityTargetTest(unittest.TestCase):
    def setUp(self):
        self.fixture=imported_tests.StorageRootAdImportedAuthorizationTest("test_real_signed_target_runtime_and_source_aead_issue_immutable_forward_only_root_reference_without_canonical_change")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);f=self.fixture;self.scope=f.request["originalSourceScope"]
        self.auth=f.authorize();self.reference=self.auth["retainedRootAuthorization"];self.root=f.source.root/"root-target"
        self.configuration=f.fixture.configuration
        self.env=patch.dict(os.environ,{"ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.configuration)})
        self.env.start();self.addCleanup(self.env.stop)
        generation={key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")};generation["configurationSha256"]=f.source.latest_sha
        self.actual={"generation":generation,"generationStatus":"IN_SYNC","pendingOperationUuid":None,"configurationSha256":f.source.latest_sha,"configurationDesiredState":f.source.latest}
        self.manifest={"scope":{key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")},"configurationSha256":f.source.latest_sha,"manifestSha256":"b"*64}
        self.pointer=f.source.root/"target-pointer";self.pointer.mkdir(mode=0o700)
        rendered_json(self.pointer/"desired-state.json",f.source.latest)
        for name,value in f.source.latest.items():
            if value is not None:rendered_json(self.configuration/name,value)
        self.driver=type("Driver",(),{})();self.driver.generation=lambda:self.actual;self.driver.verify=lambda path:{key:True for key in DOMAINS}
        self.activation={"scope":self.manifest["scope"],"phase":"VERIFIED","targetSha256":self.manifest["manifestSha256"]}
        self.driver.store=type("Store",(),{"status":lambda ignored:{"current":self.manifest,"bootHeld":self.activation["phase"]=="VERIFIED","activation":self.activation},"pointer":lambda ignored:self.pointer})()
        self.driver.runtime=type("Runtime",(),{"command":lambda ignored,args: f.fixture.marker})()
        self.original=copy.deepcopy(f.fixture.identity)
        self.frozen={"schemaVersion":1,"publicAdIdentity":self.original,"rootSourceConfiguration":root_configuration_from_observation(self.scope,self.actual),"sourcePosixPolicies":{}}
        self.active=[{"unit":"ablestack-storage-smb@"+"1"*24+".service","pid":100,"startTicks":"11"},{"unit":"ablestack-storage-winbind.service","pid":101,"startTicks":"12"}];self.owners=copy.deepcopy(self.active);self.calls=[]
        outer=self
        class Identity:
            def freeze(inner,scope,actual):return copy.deepcopy(outer.frozen)
            def unchanged(inner,scope,actual,frozen):
                if frozen!=outer.frozen:raise ValueError("changed target public metadata")
            def owners(inner):return copy.deepcopy(outer.active)
            def holders(inner):return []
            def listeners_clear(inner,owners):
                if outer.active:raise ValueError("live target listeners")
        class Controller:
            def prove_unit(inner,unit,require_active=True):
                row=next((copy.deepcopy(row) for row in outer.active if row["unit"]==unit),None)
                if require_active and row is None:raise ValueError("inactive target unit")
                return row
            def run(inner,args,**kwargs):
                outer.calls.append(args)
                if args[1]=="stop":outer.active=[row for row in outer.active if row["unit"]!=args[2]]
                elif args[1]=="start":outer.active.append({**next(row for row in outer.owners if row["unit"]==args[2]),"pid":200,"startTicks":"20"})
                else:raise AssertionError(args)
            def remaining(inner):return 10
        controller=Controller()
        class Winbind:
            def start(inner,request):controller.run(["systemctl","start","ablestack-storage-winbind.service"])
        self.target=RootIdentityTarget(self.driver,controller,self.root,Identity(),lambda:None,Winbind())
        def authority(common,marker,reference):
            with patch("root_ad_identity_authority.root_ad_runtime_verified",side_effect=lambda pin,transaction:root_ad_runtime_verified(pin,transaction,f.authorizer.runtime_provider)):
                return root_ad_retained_authority(common,marker,self.configuration,f.root,reference,sid_reader=lambda name:f.fixture.identity["machineSid"])
        self.patch=patch("root_identity_target.root_ad_retained_authority",side_effect=authority);self.patch.start();self.addCleanup(self.patch.stop)
        self.request={**self.scope,"targetConfigurationSha256":f.source.latest_sha,"importedRootAuthorization":self.reference}
        self.source_files={str(path):path.read_bytes() for path in f.authorizer.root.rglob("*") if path.is_file()}
    def test_root_current_target_stop_real_aead_independent_cipher_resume_and_lost_response_preserve_original_source(self):
        f=self.fixture;self.target.capture(self.request);self.target.quiesce(self.request);proof=self.target.export_target(self.request)
        self.assertTrue(proof["rootTargetStoppedVerified"]);self.assertEqual("ROOT",proof["maintenanceKind"])
        self.assertEqual([self.owners[0]["unit"],self.owners[1]["unit"]],[row[2] for row in self.calls])
        payload={"schemaVersion":1,"files":{},"accounts":{},"sourceConfigurationSha256":f.source.latest_sha,"adIdentity":self.original,"rootSourceConfiguration":self.frozen["rootSourceConfiguration"]}
        capsule=encrypt(payload,f.source.public,self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
        request={**self.request,"publicKey":f.source.public}
        receipt=self.target.retain_cipher(request,capsule);self.assertEqual("ROOT_TARGET_IDENTITY_CHECKPOINT",receipt["kind"])
        self.assertEqual(payload,decrypt(capsule,f.source.private,capsule["scope"]))
        old=self.target.wrapping_key(request);self.assertNotEqual(old["sha256"],capsule["sha256"])
        captured=self.target.path(self.scope).read_bytes();resumed=self.target.resume(self.request);self.assertTrue(resumed["targetRuntimeVerified"])
        count=len(self.calls);self.assertEqual(resumed,self.target.resume(self.request));self.assertEqual(count,len(self.calls))
        self.assertEqual(captured,self.target.path(self.scope).read_bytes());self.assertEqual(capsule,self.target.cached_cipher(request)["capsule"])
        self.assertEqual(self.source_files,{path:Path(path).read_bytes() for path in self.source_files})
    def test_root_target_wrong_scope_role_key_or_source_cipher_cannot_publish_or_stop(self):
        for request in ({**self.request,"maintenanceUuid":self.scope["operationUuid"]},{**self.request,"retainedRootAuthorization":self.reference},
                        {**self.request,"targetConfigurationSha256":"f"*64}):
            with self.assertRaises(ValueError):self.target.capture(request)
            self.assertEqual([],self.calls)
        self.target.capture(self.request);self.target.quiesce(self.request)
        wrong=self.fixture.source.private
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        public=rsa.generate_private_key(public_exponent=65537,key_size=2048).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        with self.assertRaises(ValueError):self.target.wrapping_key({**self.request,"publicKey":public})
        with self.assertRaises(ValueError):self.target.retain_cipher({**self.request,"publicKey":self.fixture.source.public},self.target.wrapping_key({**self.request,"publicKey":self.fixture.source.public}))
    def test_actual_signed_root_target_codec_real_crypto_and_publisher_use_current_target_header_and_original_key(self):
        import ast,io,contextlib
        f=self.fixture;self.target.capture(self.request);self.target.quiesce(self.request)
        block=(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(block);nodes=[node for node in tree.body if isinstance(node,(ast.FunctionDef,ast.ClassDef,ast.Import,ast.ImportFrom)) or isinstance(node,ast.Assign) and all(isinstance(item,ast.Name) for item in node.targets) and not any(item.id in ("request","action","scope") for item in node.targets)]
        namespace={"__name__":"signed-root-target","__file__":"signed-root-target"};exec(compile(ast.Module(body=nodes,type_ignores=[]),"<signed-root-target-codec>","exec"),namespace)
        def command(args,request=None):
            if args[2]=="render-root-target-cached-cipher":return {"success":True,"cachedTarget":self.target.cached_cipher(request)}
            if args[2]=="render-root-identity-export-target":return self.target.export_target(request)
            if args[2]=="render-root-target-key-guard":
                original=self.target.wrapping_key(request);return {"success":True,"scope":self.scope,"targetWrappingKeyVerified":True,"originalCapsuleSha256":original["sha256"]}
            raise AssertionError(args)
        publisher=namespace["RootTargetCipher"]
        namespace.update(identity_command=command,collect=lambda *args:{"schemaVersion":1,"files":{},"accounts":{}},RootTargetCipher=lambda:publisher(self.root))
        request={**self.request,"publicKey":f.source.public,"names":[],"nvmeHosts":[],"includePosixPolicyReceipts":True}
        namespace["request"]=request
        branch=next(node for node in ast.walk(tree) if isinstance(node,ast.If) and ast.unparse(node.test)=="action == 'export-root-target'")
        code=compile(ast.Module(body=branch.body,type_ignores=[]),"<signed-root-target-branch>","exec")
        output=io.StringIO()
        with contextlib.redirect_stdout(output):exec(code,namespace)
        result=json.loads(output.getvalue());self.assertEqual("ROOT_TARGET_IDENTITY_CHECKPOINT",result["rootIdentityCheckpoint"]["kind"])
        plaintext=namespace["decrypt"](result["capsule"],f.source.private,result["capsule"]["scope"])
        self.assertEqual(self.frozen["rootSourceConfiguration"],plaintext["rootSourceConfiguration"])
        self.assertEqual(f.source.latest_sha,plaintext["sourceConfigurationSha256"])
        self.assertNotEqual(result["capsule"],self.target.wrapping_key({**self.request,"publicKey":f.source.public}))
        self.assertNotIn(f.source.private,output.getvalue())
    def test_actual_root_target_dispatcher_rejects_service_or_unheld_root_before_stop_or_private_export(self):
        import subprocess
        f=self.fixture;cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        marker_root=f.source.root/"actual-root-target-marker";marker_root.mkdir(mode=0o700)
        request=f.source.root/"actual-root-target.json";request.write_text(json.dumps(self.request));request.chmod(0o600)
        bins=f.source.root/"no-root-target-effect";bins.mkdir();touched=f.source.root/"root-target-effect"
        for name in ("systemctl","net"):
            path=bins/name;path.write_text("#!/bin/sh\ntouch '"+str(touched)+"'\nexit 99\n");path.chmod(0o755)
        env=dict(os.environ,PATH=str(bins)+":"+os.environ["PATH"],ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(marker_root),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.configuration),ABLESTACK_STORAGE_RENDERED_GENERATIONS=str(f.source.root/"absent-rendered"),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(f.source.root/"actual-root-target-writer"),ABLESTACK_STORAGE_LOG_FILE="/dev/null")
        for change in ({},{**self.request,"maintenanceUuid":self.scope["operationUuid"]}):
            request.write_text(json.dumps(change or self.request))
            result=subprocess.run([str(cli),"operation","generation","render-root-capture-target",str(request)],env=env,capture_output=True,text=True,timeout=15)
            self.assertNotEqual(0,result.returncode);self.assertEqual("RENDERED_GENERATION_REJECTED",json.loads(result.stdout)["errorCode"])
            self.assertFalse(touched.exists())
        result=subprocess.run([str(cli),"identity","capsule","export-root-target","/dev/stdin"],input=json.dumps({**self.request,"maintenanceUuid":self.scope["operationUuid"],"publicKey":f.source.public,"includePosixPolicyReceipts":True}),env=env,capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,result.returncode);self.assertFalse(touched.exists())
    def test_root_target_pending_boot_or_changed_current_seven_and_failed_resume_never_publishes_a_false_runtime_receipt(self):
        self.actual["pendingOperationUuid"]=self.scope["operationUuid"]
        with self.assertRaises(ValueError):self.target.capture(self.request)
        self.assertEqual([],self.calls);self.actual["pendingOperationUuid"]=None
        self.target.capture(self.request);self.target.quiesce(self.request)
        self.driver.verify=lambda path:{key:key!="SMB" for key in DOMAINS}
        with self.assertRaises(ValueError):self.target.resume(self.request)
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.target.stop_path(self.scope).read_text())["phase"])

    def test_forward_actual_opaque_authority_requires_initial_root_role_and_preserves_stage_source_key_after_activation(self):
        from rendered_driver import RenderedDriver
        driver=object.__new__(RenderedDriver);driver.store=type("Store",(),{"scope":lambda ignored,request:{key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")},"read_journal":lambda ignored:{"initialRootScope":self.scope}})()
        driver.runtime=self.driver.runtime;driver.checkpoints=self.root/"opaque-stage";driver.checkpoints.mkdir(parents=True,mode=0o700)
        request={**{key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")},"importedRootAuthorization":self.reference,"checkpointPublicKey":self.fixture.source.public}
        real=self.patch
        import root_identity_target
        with patch("rendered_driver.root_ad_retained_authority",side_effect=root_identity_target.root_ad_retained_authority):
            authority=driver.imported_root_authority(request)
            self.assertEqual("ROOT_AD_IMPORTED_AUTHORIZATION",authority["kind"])
            saved=driver.checkpoint(request,{"configurationSha256":"0"*64})
            driver.store.read_journal=lambda:{"phase":"VERIFIED"}
            self.assertEqual(self.fixture.source.latest_sha,driver.credential_source(request)["configurationSha256"])
            self.assertEqual(saved["capsule"],driver.imported_root_authority(request)["identityCheckpoint"]["capsule"])
            with self.assertRaises(ValueError):driver.imported_root_authority({**request,"retainedRootAuthorization":self.reference})
            with self.assertRaises(ValueError):driver.imported_root_authority({**request,"importedRootAuthorization":{**self.reference,"sha256":"f"*64}})

    def test_forward_stage_original_cipher_key_latest_identity_sha_never_recollects_live_database_or_relabels_empty_baseline(self):
        from rendered_driver import RenderedDriver
        driver=object.__new__(RenderedDriver);driver.store=type("Store",(),{"scope":lambda ignored,req:{key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")}})()
        driver.checkpoints=self.root/"stage-checkpoint";driver.checkpoints.mkdir(parents=True,mode=0o700)
        checkpoint=self.fixture.authorizer.optional_read(next(self.fixture.authorizer.root.glob("*-identity.json")))
        authority={"identityCheckpoint":checkpoint,"scope":self.scope}
        driver.imported_root_authority=lambda request:authority
        driver.retained_authority=lambda request:None
        request={**{key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")},"importedRootAuthorization":self.reference,"checkpointPublicKey":self.fixture.source.public}
        driver.runtime=type("Runtime",(),{"command":lambda *args:(_ for _ in ()).throw(AssertionError("RAW recollection reached"))})()
        saved=driver.checkpoint(request,{"configurationSha256":"0"*64})
        self.assertEqual(checkpoint["capsule"],saved["capsule"]);self.assertEqual(checkpoint["sourceConfigurationSha256"],saved["sourceConfigurationSha256"])
        self.assertNotEqual("0"*64,saved["sourceConfigurationSha256"]);self.assertEqual(self.scope,saved["importedRootScope"])


if __name__=="__main__":unittest.main()
