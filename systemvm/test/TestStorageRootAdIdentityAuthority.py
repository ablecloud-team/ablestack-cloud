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

import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import unittest
import uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import TestStorageRootRetainedAuthorization as retained_tests
from TestStorageAdSemanticSource import synthetic_original_source
from root_ad_identity_authority import root_ad_retained_authority
from ad_identity import ad_configuration,AdIdentityRpc
from identity_capsule import encrypt
from rendered_generation import rendered_json


class StorageRootAdIdentityAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.fixture=retained_tests.StorageRootRetainedAuthorizationTest("test_real_aead_sealed_authorization_and_stage_separate_latest_identity_sha_from_old_native_pointer")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);f=self.fixture
        _,payload,_=synthetic_original_source();self.identity=payload["adIdentity"];f.payload["adIdentity"]=self.identity
        f.capsule=encrypt(f.payload,f.public,f.request["instanceUuid"]+":"+f.request["operationUuid"])
        self.authorized=f.authorize_sealed(f.request_auth());self.reference=self.authorized["retainedRootAuthorization"]
        self.configuration=f.root/"imported-ad";self.configuration.mkdir(mode=0o700)
        pure=ad_configuration(self.identity["domain"],self.identity["workgroup"],self.identity["netbiosName"],"[global]\n",["10.10.13.100"],self.identity["idmapPolicy"])
        self.machine=self.configuration/"ad-machine.conf";self.machine.write_text(pure["smbConfiguration"]);self.machine.chmod(0o600)
        digest=hashlib.sha256(self.machine.read_bytes()).hexdigest()
        # Original metadata must authenticate the exact real private config.
        self.identity["machineConfigurationSha256"]=digest;f.payload["adIdentity"]=self.identity
        f.capsule=encrypt(f.payload,f.public,f.request["instanceUuid"]+":"+f.request["operationUuid"])
        # Rebuild only this fixture's prior empty authorization namespace;
        # no product receipt permits replacing a published source authority.
        for path in f.retained.root.glob("*authorization.json"):path.unlink()
        for path in f.retained.root.glob("*identity.json"):path.unlink()
        self.authorized=f.authorize_sealed(f.request_auth());self.reference=self.authorized["retainedRootAuthorization"]
        state={"instanceUuid":f.request["instanceUuid"],"state":"JOINED","joinState":"JOINED","domainName":self.identity["domain"],"realm":self.identity["realm"],
               "workgroup":self.identity["workgroup"],"netbiosName":self.identity["netbiosName"],"idmapPolicy":self.identity["idmapPolicy"],"dnsAliases":self.identity["dnsAliases"],"servicePrincipals":self.identity["servicePrincipals"],
               "identityReceipt":{key:self.identity[key] for key in ("machineSid","domainSid","machineAccountSid")},"machineConfigurationSha256":digest}
        rendered_json(self.configuration/"smb-domain.json",state)
        self.common={key:f.marker["scope"][key] for key in ("instanceUuid","operationUuid","revision")}
        self.marker={"bootHeld":True,"maintenanceKind":"ROOT","scope":f.marker["scope"]}
    def authorize(self,reference=None):
        f=self.fixture
        return root_ad_retained_authority(self.common,self.marker,self.configuration,f.root/"protected",self.reference if reference is None else reference,sid_reader=lambda name:self.identity["machineSid"])
    def test_real_aead_retained_root_source_authorizes_only_imported_same_sid_and_config_without_generation_or_effects(self):
        before={str(path):path.read_bytes() for path in self.configuration.rglob("*") if path.is_file()}
        result=self.authorize();self.assertEqual(self.identity,result["identity"]);self.assertEqual(self.marker["scope"],result["scope"])
        self.assertEqual(before,{str(path):path.read_bytes() for path in self.configuration.rglob("*") if path.is_file()})
        self.assertFalse((self.fixture.generation_root/"current.json").exists())
    def test_bare_root_marker_foreign_scope_boot_or_opaque_reference_never_authorizes_net_or_new_sid(self):
        reference={**self.reference,"sha256":"f"*64}
        with self.assertRaises(ValueError):self.authorize(reference)
        old=self.marker
        self.marker={**old,"maintenanceKind":"SERVICE"}
        with self.assertRaises(ValueError):self.authorize()
        self.marker=old
        record=self.fixture.retained.path(self.reference["authorizationUuid"],"authorization")
        original=record.read_bytes();data=json.loads(original);data["authorizedBootId"]=str(uuid.uuid4());rendered_json(record,data)
        with self.assertRaises(ValueError):self.authorize()
        record.write_bytes(original);record.chmod(0o600)
    def test_actual_scope_method_cold_current_accepts_only_authenticated_record_and_same_boot_without_generation_creation(self):
        f=self.fixture;calls=[]
        def run(args,**kwargs):
            calls.append(args);return __import__("subprocess").CompletedProcess(args,0,json.dumps(self.marker),"")
        rpc=AdIdentityRpc(run,self.configuration,f.generation_root)
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(f.root/"protected")}),patch("root_ad_identity_authority.samba_public_sid",return_value=self.identity["machineSid"]):
            self.assertEqual(self.common,rpc.scope(self.common))
            self.assertFalse((f.generation_root/"current.json").exists())
            with patch("root_ad_identity_authority.samba_public_sid",side_effect=ValueError("missing preexisting SID")):
                with self.assertRaises(ValueError):rpc.scope(self.common)
        self.assertTrue(all(args[1:]==["operation","maintenance","status"] for args in calls))
    def test_wrong_unwrap_key_or_swapped_source_capsule_cannot_publish_root_ad_authority(self):
        f=self.fixture;request=f.request_auth()
        other,_,_=synthetic_original_source()
        request["credentialPrivateKey"]=other["originalSourceCredentialPrivateKey"]
        before={path.name:path.read_bytes() for path in f.retained.root.iterdir() if path.is_file()}
        with self.assertRaises(ValueError):f.authorize_sealed(request)
        self.assertEqual(before,{path.name:path.read_bytes() for path in f.retained.root.iterdir() if path.is_file()})
        identity_path=f.retained.path(self.reference["authorizationUuid"],"identity")
        original=identity_path.read_bytes();data=json.loads(original);data["capsule"]["ciphertext"]="YWJj";rendered_json(identity_path,data)
        with self.assertRaises(ValueError):self.authorize()
        identity_path.write_bytes(original);identity_path.chmod(0o600)

    def test_signed_ad_retain_branch_real_decryption_reaches_only_authenticated_root_consumer(self):
        import ast,io,contextlib
        from identity_capsule import decrypt,validate_payload
        from root_identity_reference import RootIdentityReference
        from ad_lifecycle import AdDomainLifecycle
        f=self.fixture;calls=[]
        class Daemon:
            def marker(inner,request):return self.marker["scope"]
            def scope(inner,request):return self.common
            def start(inner,request):calls.append("OWNED_START")
            def stop(inner,request):calls.append("OWNED_STOP")
        handler=AdDomainLifecycle(str(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"),self.configuration,daemon=Daemon())
        source=(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl").read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(source);node=next(item for item in ast.walk(tree) if isinstance(item,ast.If) and ast.unparse(item.test)=='action == \'ad-retain\'')
        code=compile(ast.Module(body=node.body,type_ignores=[]),"<signed-ad-retain>","exec")
        request={**f.marker["scope"],"capsule":f.capsule,"credentialPrivateKey":f.private,"retainedRootAuthorization":self.reference}
        def command(args,payload=None):
            if args==("operation","maintenance","status"):return self.marker
            self.assertEqual(("identity","domain","retain","/dev/stdin"),args)
            return handler.retain(payload,payload["expectedIdentity"])
        namespace={"request":request,"RootIdentityReference":RootIdentityReference,"identity_command":command,"decrypt":decrypt,"validate_payload":validate_payload,"json":json}
        fresh={**self.identity,"identityVerified":True}
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(f.root/"protected")}),patch("root_ad_identity_authority.samba_public_sid",return_value=self.identity["machineSid"]),patch("ad_lifecycle.AdIdentityRpc") as rpc:
            rpc.return_value.inspect.return_value=fresh
            output=io.StringIO()
            with contextlib.redirect_stdout(output):exec(code,namespace)
            self.assertTrue(json.loads(output.getvalue())["identityPreserved"]);self.assertEqual(["OWNED_START"],calls)
            calls.clear();request["retainedRootAuthorization"]={**self.reference,"sha256":"f"*64}
            with self.assertRaises(ValueError):exec(code,namespace)
            self.assertEqual([],calls)
        self.assertNotIn(f.private,output.getvalue())

    def test_actual_signed_cold_readonly_rpc_bare_root_marker_rejects_before_net_or_sid_creation(self):
        f=self.fixture;root=f.root/"bare-root";root.mkdir(mode=0o700)
        rendered_json(root/"template-maintenance.json",{"scope":self.marker["scope"]})
        binaries=f.root/"no-net";binaries.mkdir();touched=f.root/"network-effect"
        net=binaries/"net";net.write_text("#!/bin/sh\ntouch '"+str(touched)+"'\nexit 99\n");net.chmod(0o755)
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        env=dict(os.environ,PATH=str(binaries)+":"+os.environ["PATH"],ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(root),
                 ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.configuration),ABLESTACK_STORAGE_GENERATION_DIR=str(f.generation_root))
        before={str(path):path.read_bytes() for path in self.configuration.rglob("*") if path.is_file()}
        result=subprocess.run([str(cli),"identity","domain","inspect","/dev/stdin"],input=json.dumps(self.common),env=env,capture_output=True,text=True,timeout=15)
        self.assertNotEqual(0,result.returncode);self.assertEqual("AD_IDENTITY_ATTESTATION_REJECTED",json.loads(result.stdout)["errorCode"])
        self.assertFalse(touched.exists());self.assertFalse((f.generation_root/"current.json").exists())
        self.assertEqual(before,{str(path):path.read_bytes() for path in self.configuration.rglob("*") if path.is_file()})

    def test_foreign_or_changed_imported_machine_config_or_sam_reject_before_start(self):
        before=self.machine.read_bytes();self.machine.write_text("foreign configuration");self.machine.chmod(0o600)
        with self.assertRaises(ValueError):self.authorize()
        self.machine.write_bytes(before);self.machine.chmod(0o600)
        state_path=self.configuration/"smb-domain.json";state=json.loads(state_path.read_text());original=state_path.read_bytes()
        state["dnsAliases"]=[{"hostname":"foreign.ablestack.local","addresses":["10.10.13.241"]}];rendered_json(state_path,state)
        with self.assertRaises(ValueError):self.authorize()
        state_path.write_bytes(original);state_path.chmod(0o600)
        f=self.fixture
        with self.assertRaises(ValueError):
            root_ad_retained_authority(self.common,self.marker,self.configuration,f.root/"protected",self.reference,sid_reader=lambda name:"S-1-5-21-1-2-3")

if __name__=="__main__":unittest.main()
