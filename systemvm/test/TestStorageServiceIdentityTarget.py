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

import copy
import ast
import subprocess
import json
import os
from pathlib import Path
import sys
import unittest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import TestStorageServiceIdentitySource as source_tests
from service_identity_target import ServiceIdentityTarget
from service_identity_cipher import ServiceIdentityCipher
from identity_capsule import encrypt,decrypt
from rendered_generation import DOMAINS,DESIRED_PATHS,rendered_json


class StorageServiceIdentityTargetTest(unittest.TestCase):
    def setUp(self):
        self.fixture=source_tests.StorageServiceIdentitySourceTest("test_prestop_capture_contains_only_public_sam_ad_and_existing_policy_metadata")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);f=self.fixture
        f.source.capture(f.scope);f.journal();f.source.stopped(f.scope);source=f.source.export_source(f.scope)
        self.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.public=self.key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.private=self.key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        self.old=encrypt({"schemaVersion":1,"files":{},"accounts":{},"sourceConfigurationSha256":"a"*64},self.public,f.instance+":"+f.operation)
        self.source_cipher=ServiceIdentityCipher(f.maintenance_root);self.source_cipher.retain({**f.scope,"publicKey":self.public},self.old,source)
        self.source_files={str(path):path.read_bytes() for path in (f.source.path(f.scope),self.source_cipher.path(f.scope),f.maintenance_root/"service-maintenance.json")}
        f.generation={**f.generation,"operationUuid":f.operation,"revision":8,"configurationSha256":"c"*64}
        f.actual.update(generation=f.generation,configurationSha256="c"*64)
        f.rendered={**f.rendered,"scope":{key:f.scope[key] for key in ("instanceUuid","operationUuid","revision")},"configurationSha256":"c"*64,"manifestSha256":"d"*64}
        first=next(iter(DESIRED_PATHS));f.desired[first]={"enabled":False};rendered_json(f.pointer/"desired-state.json",f.desired)
        path=f.config/first;path.parent.mkdir(parents=True,exist_ok=True,mode=0o700);rendered_json(path,f.desired[first])
        self.owners=[{"unit":"ablestack-storage-smb@"+"1"*24+".service","pid":200,"startTicks":"33","configurationPath":"/etc/samba/smb.conf","listenerEndpoints":[]}]
        f.active=copy.deepcopy(self.owners);self.calls=[]
        outer=self
        class Controller:
            def remaining(self):return 10
            def prove_unit(self,unit,require_active=True):
                row=next((copy.deepcopy(row) for row in f.active if row["unit"]==unit),None)
                if require_active and row is None:raise ValueError("not active")
                return row
            def run(self,args,**kwargs):
                outer.calls.append(args)
                if args[1]=="stop":f.active=[row for row in f.active if row["unit"]!=args[2]]
                elif args[1]=="start":f.active.append({**outer.owners[0],"pid":300,"startTicks":"44"})
                else:raise AssertionError(args)
        self.target=ServiceIdentityTarget(f.driver,Controller(),f.maintenance_root,f.public,f.writer)
        self.request={**f.scope,"targetConfigurationSha256":"c"*64}
    def test_target_capture_stop_real_aead_cipher_and_resume_are_independent_from_beforejoin_source(self):
        f=self.fixture;self.target.capture(self.request);self.target.quiesce(self.request)
        metadata=self.target.export_target(self.request);self.assertTrue(metadata["serviceTargetStoppedVerified"])
        payload={"schemaVersion":1,"files":{},"accounts":{},"sourceConfigurationSha256":metadata["targetConfigurationSha256"]}
        capsule=encrypt(payload,self.public,f.instance+":"+f.operation)
        receipt=self.target.retain_cipher({**self.request,"publicKey":self.public},capsule)
        self.assertEqual("SERVICE_TARGET_IDENTITY_CHECKPOINT",receipt["kind"]);self.assertEqual("c"*64,receipt["targetConfigurationSha256"])
        self.assertEqual(payload,decrypt(capsule,self.private,f.instance+":"+f.operation));self.assertNotEqual(capsule,self.old)
        frozen={str(path):path.read_bytes() for path in (self.target.path(f.scope),self.target.cipher_path(f.scope))}
        resumed=self.target.resume(self.request);self.assertTrue(resumed["targetRuntimeVerified"])
        self.assertEqual(frozen,{path:Path(path).read_bytes() for path in frozen})
        calls=list(self.calls);again=self.target.resume(self.request);self.assertEqual(resumed,again);self.assertEqual(calls,self.calls)
        cached=self.target.cached_cipher({**self.request,"publicKey":self.public});self.assertEqual(capsule,cached["capsule"])
        self.assertEqual(self.source_files,{path:Path(path).read_bytes() for path in self.source_files})
        self.assertNotIn(self.private,self.target.cipher_path(f.scope).read_text())
    def test_foreign_root_mixed_scope_stale_target_seven_or_failed_all4_reject_before_stop_and_private_export(self):
        f=self.fixture
        for request in ({**self.request,"templateUpgradeUuid":"foreign"},{**self.request,"targetConfigurationSha256":"a"*64}):
            with self.assertRaises(ValueError):self.target.capture(request)
            self.assertEqual([],self.calls)
        f.driver.verify=lambda pointer:{**{name:True for name in DOMAINS},"SMB":False}
        with self.assertRaises(ValueError):self.target.capture(self.request)
        self.assertFalse(self.target.path(f.scope).exists());self.assertEqual([],self.calls)
        original=f.runtime.command
        f.runtime.command=lambda args,payload=None:{"bootHeld":True,"maintenanceKind":"ROOT","scope":f.scope} if args==("operation","maintenance","status") else original(args,payload)
        with self.assertRaises(ValueError):self.target.capture(self.request)
        self.assertEqual([],self.calls)
    def test_wrong_key_source_cipher_swap_pending_or_failed_resume_remains_fenced(self):
        f=self.fixture;self.target.capture(self.request);self.target.quiesce(self.request)
        before=self.target.path(f.scope).read_bytes();self.target.quiesce(self.request)
        self.assertEqual(before,self.target.path(f.scope).read_bytes())
        capsule=encrypt({"schemaVersion":1,"files":{},"accounts":{},"sourceConfigurationSha256":"c"*64},self.public,f.instance+":"+f.operation)
        other=rsa.generate_private_key(public_exponent=65537,key_size=2048).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        with self.assertRaises(ValueError):self.target.retain_cipher({**self.request,"publicKey":other},capsule)
        with self.assertRaises(ValueError):self.target.retain_cipher({**self.request,"publicKey":self.public},self.old)
        self.assertFalse(self.target.cipher_path(f.scope).exists())
        f.actual["pendingOperationUuid"]=f.operation;f.actual["generationStatus"]="PENDING"
        with self.assertRaises(ValueError):self.target.export_target(self.request)
        f.actual["pendingOperationUuid"]=None;f.actual["generationStatus"]="IN_SYNC"
        self.target.retain_cipher({**self.request,"publicKey":self.public},capsule)
        f.driver.verify=lambda path:{name:False for name in DOMAINS}
        with self.assertRaises(ValueError):self.target.resume(self.request)
        self.assertEqual("RECOVERY_REQUIRED",json.loads(self.target.stop_path(f.scope).read_text())["phase"])
        self.assertEqual(self.source_files,{path:Path(path).read_bytes() for path in self.source_files})

    def test_actual_signed_target_rpc_and_export_router_reject_foreign_hold_before_private_collection(self):
        f=self.fixture;cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        payload=f.root/"target-request.json";payload.write_text(json.dumps(self.request))
        env=dict(os.environ,ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(f.root/"actual-writer"),ABLESTACK_STORAGE_LOG_FILE="/dev/null")
        before={str(path):path.read_bytes() for path in f.config.rglob("*") if path.is_file()}
        result=subprocess.run([str(cli),"operation","generation","render-service-capture-target",str(payload)],env=env,capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,result.returncode);self.assertEqual("RENDERED_GENERATION_REJECTED",json.loads(result.stdout)["errorCode"])
        result=subprocess.run([str(cli),"identity","capsule","export-target","/dev/stdin"],input=json.dumps({**self.request,"templateUpgradeUuid":"foreign","publicKey":self.public}),env=env,capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,result.returncode);self.assertFalse(self.target.cipher_path(f.scope).exists())
        self.assertEqual(before,{str(path):path.read_bytes() for path in f.config.rglob("*") if path.is_file()})

    def test_actual_signed_codec_target_branch_uses_real_aead_and_native_target_publisher_without_source_relabel(self):
        f=self.fixture;self.target.capture(self.request);self.target.quiesce(self.request)
        cli=(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_text()
        block=cli.split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0];parsed=ast.parse(block)
        nodes=[node for node in parsed.body if isinstance(node,(ast.FunctionDef,ast.ClassDef,ast.Import,ast.ImportFrom)) or isinstance(node,ast.Assign) and all(isinstance(item,ast.Name) for item in node.targets) and not any(item.id in ("request","action","scope") for item in node.targets)]
        # Execute the actual signed codec functions/classes and export-target
        # branch. RAW fixture is RAM-only; all RSA/GCM/publication is real.
        namespace={"__name__":"signed-target-codec","__file__":"signed-target-codec"}
        exec(compile(ast.Module(body=nodes,type_ignores=[]),"signed-target-codec","exec"),namespace)
        scope={key:f.scope[key] for key in ("instanceUuid","maintenanceUuid","operationUuid","revision")}
        target=self.target
        def command(args,request=None):
            if args[2]=="render-service-target-key-guard":
                old=target.wrapping_key(request);return {"success":True,"scope":scope,"targetWrappingKeyVerified":True,"originalCapsuleSha256":old["sha256"]}
            if args[2]=="render-service-target-cached-cipher":return {"success":True,"cachedTarget":target.cached_cipher(request)}
            if args[2]=="render-service-identity-export-target":return target.export_target(request)
            raise AssertionError(args)
        namespace.update(request={**self.request,"publicKey":self.public},scope=f.instance+":"+f.operation,identity_command=command,
                         collect=lambda *args,**kwargs:{"schemaVersion":1,"files":{},"accounts":{}})
        a=cli.index('elif action=="export-target":');b=cli.index('elif action == "export":',a)
        branch=cli[a+len('elif action=="export-target":'):b]
        source="if True:"+branch
        import contextlib,io
        output=io.StringIO()
        with contextlib.redirect_stdout(output):exec(compile(source,"signed-export-target-branch","exec"),namespace)
        result=json.loads(output.getvalue());self.assertEqual("SERVICE_TARGET_IDENTITY_CHECKPOINT",result["serviceIdentityCheckpoint"]["kind"])
        decoded=decrypt(result["capsule"],self.private,f.instance+":"+f.operation)
        self.assertEqual("c"*64,decoded["sourceConfigurationSha256"]);self.assertNotEqual(self.old,result["capsule"])
        self.assertEqual(self.source_files,{path:Path(path).read_bytes() for path in self.source_files})
        output=io.StringIO()
        with contextlib.redirect_stdout(output):exec(compile(source,"signed-export-target-retry","exec"),namespace)
        self.assertEqual(result,json.loads(output.getvalue()))

    def test_stage_original_fallback_requires_authenticated_previous_manifest_checkpoint_ref_and_same_rsa_key(self):
        f=self.fixture;root=f.root/"independent-target";root.mkdir(mode=0o700)
        target=ServiceIdentityTarget(f.driver,self.target.controller,root,f.public,f.writer)
        f.driver.checkpoints=f.root/"stage-identity-checkpoints";f.driver.checkpoints.mkdir(mode=0o700)
        common={key:f.scope[key] for key in ("instanceUuid","operationUuid","revision")}
        saved={"schemaVersion":1,"scope":common,"sourceConfigurationSha256":"a"*64,"publicKey":self.public,"capsule":self.old}
        rendered_json(f.driver.checkpoints/(f.operation+".json"),saved)
        f.driver.credential_source=lambda request:{"configurationSha256":"a"*64}
        reference={"operationUuid":f.operation,"sha256":self.old["sha256"]}
        request={**self.request,"publicKey":self.public,"identityCheckpointRef":reference}
        self.assertEqual(self.old,target.wrapping_key(request))
        for changed in ({**reference,"sha256":"f"*64},{**reference,"operationUuid":"foreign"}):
            with self.assertRaises(ValueError):target.wrapping_key({**request,"identityCheckpointRef":changed})
        f.driver.credential_source=lambda request:{"configurationSha256":"f"*64}
        with self.assertRaises(ValueError):target.wrapping_key(request)
        self.assertEqual(self.source_files,{path:Path(path).read_bytes() for path in self.source_files})

    def test_target_raw_requires_own_stop_journal_holders_clear_and_immutable_cipher(self):
        f=self.fixture;self.target.capture(self.request)
        with self.assertRaises((ValueError,FileNotFoundError)):self.target.export_target(self.request)
        self.target.quiesce(self.request);f.holders=[{"pid":123,"fd":7,"path":"private"}]
        with self.assertRaises(ValueError):self.target.export_target(self.request)
        f.holders=[]
        capsule=encrypt({"schemaVersion":1,"files":{},"accounts":{},"sourceConfigurationSha256":"c"*64},self.public,f.instance+":"+f.operation)
        request={**self.request,"publicKey":self.public};self.target.retain_cipher(request,capsule)
        second=encrypt({"schemaVersion":1,"files":{},"accounts":{},"sourceConfigurationSha256":"c"*64},self.public,f.instance+":"+f.operation)
        with self.assertRaises(ValueError):self.target.retain_cipher(request,second)
        self.assertEqual(self.source_files,{path:Path(path).read_bytes() for path in self.source_files})

if __name__=="__main__":unittest.main()
