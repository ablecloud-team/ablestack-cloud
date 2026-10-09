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

import ast
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
import uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
import TestStorageRootAdIdentityAuthority as root_tests
import TestStorageRuntimeUpdater as runtime_tests
from root_ad_imported_authorization import RootAdImportedAuthorization,root_ad_runtime_verified
from root_ad_identity_authority import root_ad_retained_authority


class StorageRootAdImportedAuthorizationTest(unittest.TestCase):
    def setUp(self):
        self.fixture=root_tests.StorageRootAdIdentityAuthorityTest("test_real_aead_retained_root_source_authorizes_only_imported_same_sid_and_config_without_generation_or_effects")
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.source=self.fixture.fixture;f=self.source
        self.runtime=runtime_tests.StorageRuntimeUpdaterTest("test_signed_readback_verifies_exact_release_and_has_no_state_or_target_writes")
        self.runtime.setUp();self.addCleanup(self.runtime.tearDown)
        self.runtime.run_updater("bootstrap")
        staged=self.runtime.stage_transaction("root-target-"+f.marker["scope"]["operationUuid"])
        self.runtime.run_updater("activate",staged)
        observed=self.runtime.run_updater("readback",staged)
        self.pin={key:staged[key] for key in ("bundleVersion","archiveSha256","manifestSha256")};self.pin["updaterSha256"]=observed["updaterSha256"]
        self.root=f.root/"forward-authority"
        self.authorizer=RootAdImportedAuthorization(self.root,self.fixture.configuration,lambda name:self.fixture.identity["machineSid"],lambda files:None,
                                                  runtime_provider=lambda request:self.runtime.run_updater("readback",request))
        self.request={**f.marker["scope"],"capsule":f.capsule,"credentialPrivateKey":f.private,
                      "originalSourceScope":f.marker["scope"],"sourceConfigurationSha256":f.latest_sha,"targetRuntimePin":self.pin}
    def authorize(self,request=None,marker=None):
        return self.authorizer.authorize(request or self.request,marker or self.fixture.marker)
    def files(self):
        return {str(p):p.read_bytes() for p in self.root.rglob("*") if p.is_file()} if self.root.exists() else {}
    def test_real_signed_target_runtime_and_source_aead_issue_immutable_forward_only_root_reference_without_canonical_change(self):
        before={str(p):p.read_bytes() for p in self.fixture.configuration.rglob("*") if p.is_file()}
        result=self.authorize();self.assertTrue(result["rootAdImportedAuthorized"]);self.assertEqual("ROOT_AD_IMPORTED_AUTHORIZATION",result["kind"])
        saved=self.files();self.assertEqual(result,self.authorize());self.assertEqual(saved,self.files())
        with patch("root_ad_identity_authority.root_ad_runtime_verified",side_effect=lambda pin,transaction:root_ad_runtime_verified(pin,transaction,self.authorizer.runtime_provider)):
            actual=root_ad_retained_authority(self.fixture.common,self.fixture.marker,self.fixture.configuration,self.root,result["retainedRootAuthorization"],
                                              sid_reader=lambda name:self.fixture.identity["machineSid"])
        self.assertEqual(self.fixture.identity,actual["identity"]);self.assertEqual(before,{str(p):p.read_bytes() for p in self.fixture.configuration.rglob("*") if p.is_file()})
        self.assertFalse((self.source.generation_root/"current.json").exists())
        self.assertTrue(all(self.source.private.encode() not in raw for raw in saved.values()))
        with self.assertRaises(ValueError):
            root_ad_retained_authority(self.fixture.common,self.fixture.marker,self.fixture.configuration,self.root,self.fixture.reference,sid_reader=lambda name:self.fixture.identity["machineSid"])
    def test_encrypted_identity_checkpoint_exceeding_small_receipt_limit_retries_without_private_plaintext_file(self):
        import base64
        from identity_capsule import encrypt
        payload=dict(self.source.payload);payload["files"]={"/var/lib/samba/private/passdb.tdb":{"mode":0o600,"uid":0,"gid":0,"data":base64.b64encode(b"SYNTHETIC_RAM_ONLY"*32768).decode()}}
        capsule=encrypt(payload,self.source.public,self.request["instanceUuid"]+":"+self.request["operationUuid"])
        request={**self.request,"capsule":capsule}
        result=self.authorize(request);saved=self.files()
        encrypted=next(path for path in self.authorizer.root.glob("*-identity.json"));self.assertGreater(encrypted.stat().st_size,256*1024)
        self.assertEqual(result,self.authorize(request));self.assertEqual(saved,self.files())
        self.assertTrue(all(b"SYNTHETIC_RAM_ONLY" not in raw for raw in saved.values()))

    def test_foreign_source_pin_or_current_runtime_bytes_cannot_publish_forward_authority(self):
        for change in ({"sourceConfigurationSha256":"f"*64},{"targetRuntimePin":{**self.pin,"manifestSha256":"f"*64}},
                       {"originalSourceScope":{**self.request["originalSourceScope"],"instanceUuid":str(uuid.uuid4())}}):
            with self.subTest(field=tuple(change)):
                with self.assertRaises((ValueError,AssertionError)):self.authorize({**self.request,**change})
                self.assertEqual({},self.files())
        entry=(self.runtime.runtime_root/"releases/v2/ablestack-storagectl");entry.write_text("foreign signed release bytes");entry.chmod(0o755)
        with self.assertRaises(AssertionError):self.authorize()
        self.assertEqual({},self.files())
    def test_wrong_original_key_changed_imported_config_or_missing_original_sam_refuses_before_publication(self):
        from TestStorageAdSemanticSource import synthetic_original_source
        other,_,_=synthetic_original_source()
        with self.assertRaises(ValueError):self.authorize({**self.request,"credentialPrivateKey":other["originalSourceCredentialPrivateKey"]})
        self.assertEqual({},self.files())
        self.authorizer.sid_reader=lambda name:"S-1-5-21-1-2-3"
        with self.assertRaises(ValueError):self.authorize()
        self.assertEqual({},self.files())
        self.authorizer.sid_reader=lambda name:self.fixture.identity["machineSid"]
        self.fixture.machine.write_text("foreign public configuration")
        with self.assertRaises(ValueError):self.authorize()
        self.assertEqual({},self.files())
    def test_source_target_scope_swap_or_nonroot_marker_and_changed_published_source_cannot_replace_authority(self):
        for marker in ({**self.fixture.marker,"maintenanceKind":"SERVICE"},{**self.fixture.marker,"bootHeld":False},
                       {**self.fixture.marker,"scope":{**self.fixture.marker["scope"],"operationUuid":str(uuid.uuid4())}}):
            with self.assertRaises(ValueError):self.authorize(marker=marker)
            self.assertEqual({},self.files())
        result=self.authorize();saved=self.files();request={**self.request,"capsule":{**self.request["capsule"],"scope":str(uuid.uuid4())+":"+self.request["operationUuid"]}}
        with self.assertRaises(ValueError):self.authorize(request)
        self.assertEqual(saved,self.files())
        path=next(self.authorizer.root.glob("*-authorization.json"));value=json.loads(path.read_text());value["authorizedBootId"]=str(uuid.uuid4());path.write_text(json.dumps(value));path.chmod(0o600)
        with self.assertRaises(ValueError):self.authorize()
    def test_actual_cli_forward_authorize_rejects_unheld_or_foreign_root_before_identity_publication_and_private_output(self):
        marker_root=self.source.root/"actual-forward-marker";marker_root.mkdir(mode=0o700)
        binaries=self.source.root/"no-forward-net";binaries.mkdir();touched=self.source.root/"network-effect"
        for name in ("net","systemctl"):
            binary=binaries/name;binary.write_text("#!/bin/sh\ntouch '"+str(touched)+"'\nexit 99\n");binary.chmod(0o755)
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        env=dict(os.environ,PATH=str(binaries)+":"+os.environ["PATH"],ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(marker_root),
                 ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.fixture.configuration),ABLESTACK_STORAGE_GENERATION_DIR=str(self.source.generation_root),
                 ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.source.root/"actual-forward-writer"),ABLESTACK_STORAGE_LOG_FILE="/dev/null")
        before={str(p):p.read_bytes() for p in self.fixture.configuration.rglob("*") if p.is_file()}
        variants=(None,{"kind":"ROOT","scope":{**self.request["originalSourceScope"],"operationUuid":str(uuid.uuid4())}})
        for value in variants:
            if value is not None:
                path=marker_root/"template-maintenance.json";path.write_text(json.dumps(value));path.chmod(0o600)
            result=subprocess.run([str(cli),"identity","capsule","ad-authorize-imported","/dev/stdin"],input=json.dumps(self.request),env=env,capture_output=True,text=True,timeout=15)
            self.assertNotEqual(0,result.returncode);self.assertNotIn(self.source.private,result.stdout+result.stderr);self.assertFalse(touched.exists())
            self.assertFalse((marker_root/"root-ad-imported-authorizations").exists())
            self.assertEqual(before,{str(p):p.read_bytes() for p in self.fixture.configuration.rglob("*") if p.is_file()})
            self.assertFalse((self.source.generation_root/"current.json").exists())

    def test_actual_signed_authorize_branch_reuses_real_codec_and_closed_request_with_installed_runtime_readback(self):
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        tree=ast.parse(cli.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0])
        node=next(item for item in ast.walk(tree) if isinstance(item,ast.If) and ast.unparse(item.test)=="action == 'ad-authorize-imported'")
        code=compile(ast.Module(body=node.body,type_ignores=[]),"<signed-forward-root-authorize>","exec")
        namespace={"request":self.request,"RootAdImportedAuthorization":lambda:self.authorizer,"identity_command":lambda args:self.fixture.marker,"json":json}
        output=io.StringIO()
        with contextlib.redirect_stdout(output):exec(code,namespace)
        self.assertTrue(json.loads(output.getvalue())["rootAdImportedAuthorized"]);self.assertNotIn(self.source.private,output.getvalue())
        with self.assertRaises(ValueError):self.authorize({**self.request,"callerIdentity":self.fixture.identity})


if __name__=="__main__":unittest.main()
