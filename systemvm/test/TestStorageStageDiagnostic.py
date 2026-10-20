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
"""Stage failures retain only fixed diagnostics under the matching native writer."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"))
from rendered_driver import RenderedDriver
from rendered_generation import (DESIRED_PATHS, DOMAINS, NativeRuntimeFailure,
    RenderedGeneration, native_runtime_diagnostic, rendered_json,
    sanitized_runtime_diagnostic)
from native_render_validation import NativeRenderValidationError, native_validation_diagnostic

PRIVATE="PRIVATE_password=/private/secret-path/account"
CODES=("STAGE_INPUT_INVALID","STAGE_CREDENTIAL_BINDING_FAILED","STAGE_RENDER_FAILED",
    "STAGE_SOURCE_SCOPE_MISMATCH","STAGE_SOURCE_VERIFY_FAILED",
    "STAGE_SOURCE_IDENTITY_FAILED","STAGE_CANDIDATE_VALIDATION_FAILED")


class StorageStageDiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.render=self.root/"render";self.render.mkdir(mode=0o700)
        self.generation_root=self.root/"generation";self.generation_root.mkdir(mode=0o700)
        self.maintenance_root=self.root/"maintenance";self.maintenance_root.mkdir(mode=0o700)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":3}
        self.desired={name:None for name in DESIRED_PATHS}
        (self.render/"source").mkdir(mode=0o700)
        rendered_json(self.render/"source"/"desired-state.json",self.desired)
        sha=hashlib.sha256(json.dumps(self.desired,sort_keys=True,separators=(",",":")).encode()).hexdigest()
        self.previous={**self.scope,"operationUuid":str(uuid.uuid4()),"revision":2,
            "configurationSha256":sha,"verifiedAt":"source"}
        self.source={"generation":self.previous,"configurationSha256":sha,
            "generationStatus":"PENDING","pendingOperationUuid":self.scope["operationUuid"],
            "configurationDesiredState":self.desired}
        self.manifest={"scope":{key:self.previous[key] for key in self.scope},
            "configurationSha256":sha,"manifestSha256":"a"*64}
        self.pending={**self.scope,"previous":self.previous,"beforeSha256":sha,"phase":"PREPARED"}
        rendered_json(self.generation_root/"pending.json",self.pending)
        self.request={**self.scope,"previousGeneration":self.previous,
            "configurationDesiredState":self.desired,"protocolDesiredState":{domain:None for domain in DOMAINS},
            "credentialRefs":{},"checkpointPublicKey":"fixed-public-fixture"}
        self.store=RenderedGeneration(self.render);self.driver=RenderedDriver(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl",self.store)
        self.store.pointer=lambda:self.render/"source"
        self.store.inspect=lambda path:self.manifest
        self.store.read_journal=lambda:{"phase":"COMPLETE"}
        self.driver.retained_authority=lambda *args,**kwargs:None
        self.driver.imported_root_authority=lambda *args,**kwargs:None
        self.driver.generation=lambda:self.source
        self.driver.render=lambda request:{}
        self.driver.verify=lambda path:{domain:True for domain in DOMAINS}
        self.driver.checkpoint=lambda *args:{"sourceConfigurationSha256":sha,"capsule":{"sha256":"b"*64}}
        self.store.stage=lambda *args:{"manifestSha256":"c"*64,"configurationSha256":sha}
        self.environment=patch.dict(os.environ,ABLESTACK_STORAGE_GENERATION_DIR=str(self.generation_root),
            ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.maintenance_root))
        self.environment.start();self.addCleanup(self.environment.stop)
        self.before=(self.generation_root/"pending.json").read_bytes()

    def receipt(self):
        return self.render/"stage-failures"/(self.scope["operationUuid"]+".json")

    def reject_fixture(self,*args,**kwargs):
        raise ValueError(PRIVATE)

    def assert_fixed_failure(self,code):
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-stage",self.request)
        expected={"schemaVersion":1,"protocol":"GENERATION","phase":"STAGE","code":code}
        self.assertEqual(expected,native_runtime_diagnostic(raised.exception))
        self.assertEqual(code,str(raised.exception))
        self.assertNotIn(PRIVATE,str(raised.exception))
        receipt=json.loads(self.receipt().read_text())
        self.assertEqual({"schemaVersion":1,"kind":"RENDERED_STAGE_FAILURE","scope":self.scope,
            "runtimeDiagnostic":expected},receipt)
        self.assertNotIn(PRIVATE,self.receipt().read_text())
        self.assertNotIn("checkpointPublicKey",self.receipt().read_text())
        self.assertEqual(0o600,self.receipt().stat().st_mode&0o777)
        self.assertEqual(0o700,self.receipt().parent.stat().st_mode&0o777)
        self.assertEqual(self.before,(self.generation_root/"pending.json").read_bytes())
        return raised.exception

    def test_closed_stage_codes_reject_cross_protocol_and_caller_fields(self):
        for code in CODES:
            value={"schemaVersion":1,"protocol":"GENERATION","phase":"STAGE","code":code}
            self.assertEqual(value,sanitized_runtime_diagnostic(value))
            for changed in ({**value,"protocol":"SMB"},{**value,"phase":"VERIFY"},
                    {**value,"reason":PRIVATE},{**value,"code":"STAGE_"+PRIVATE}):
                self.assertIsNone(sanitized_runtime_diagnostic(changed))

    def test_held_source_health_failure_has_fixed_code_and_durable_receipt(self):
        self.driver.verify=self.reject_fixture
        self.driver.checkpoint=lambda *args: self.reject_fixture("checkpoint must not be reached")
        self.assert_fixed_failure("STAGE_SOURCE_VERIFY_FAILED")

    def test_source_scope_failure_cannot_create_foreign_writer_receipt(self):
        self.source["pendingOperationUuid"]=str(uuid.uuid4())
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-stage",self.request)
        self.assertEqual("STAGE_SOURCE_SCOPE_MISMATCH",str(raised.exception))
        self.assertFalse(self.receipt().exists())

    def test_credential_binding_failure_is_closed_and_preserves_source(self):
        self.request["credentialRefs"]={"FOREIGN":PRIVATE}
        self.assert_fixed_failure("STAGE_CREDENTIAL_BINDING_FAILED")

    def test_renderer_exception_does_not_persist_private_reason(self):
        self.driver.render=self.reject_fixture
        self.assert_fixed_failure("STAGE_RENDER_FAILED")

    def test_checkpoint_exception_does_not_persist_private_reason(self):
        self.driver.checkpoint=self.reject_fixture
        self.assert_fixed_failure("STAGE_SOURCE_IDENTITY_FAILED")

    def test_candidate_validation_exception_is_classified_without_exception_text(self):
        self.store.stage=self.reject_fixture
        self.assert_fixed_failure("STAGE_CANDIDATE_VALIDATION_FAILED")

    def test_existing_typed_nfs_validator_failure_is_preserved_in_safe_receipt(self):
        def fail(*args):
            raise NativeRenderValidationError("NFS_VALIDATOR_PARSER_REJECTED")
        self.store.stage=fail
        with self.assertRaises(NativeRenderValidationError) as raised:
            self.driver.execute("render-stage",self.request)
        diagnostic=native_validation_diagnostic(raised.exception)
        self.assertEqual("NFS_VALIDATOR_PARSER_REJECTED",diagnostic["runtimeValidationCode"])
        self.assertEqual(diagnostic,json.loads(self.receipt().read_text())["diagnostic"])
        self.assertNotIn("runtimeDiagnostic",json.loads(self.receipt().read_text()))

    def test_existing_typed_transport_failure_remains_typed(self):
        def fail(*args):
            raise NativeRuntimeFailure("GENERATION","NATIVE_CHILD_INPUT_UNAVAILABLE")
        self.driver.verify=fail
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-stage",self.request)
        self.assertEqual("NATIVE_CHILD_INPUT_UNAVAILABLE",str(raised.exception))
        self.assertEqual("TRANSPORT",json.loads(self.receipt().read_text())["runtimeDiagnostic"]["phase"])

    def test_invalid_input_does_not_create_diagnostic_namespace(self):
        self.request["revision"]=-1
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-stage",self.request)
        self.assertEqual("STAGE_INPUT_INVALID",str(raised.exception))
        self.assertFalse((self.render/"stage-failures").exists())

    def test_failure_receipt_write_error_never_masks_rejection(self):
        self.driver.verify=self.reject_fixture
        with patch("rendered_driver.os.link",side_effect=OSError(PRIVATE)):
            with self.assertRaises(NativeRuntimeFailure) as raised:
                self.driver.execute("render-stage",self.request)
        self.assertEqual("STAGE_SOURCE_VERIFY_FAILED",str(raised.exception))
        self.assertFalse(self.receipt().exists())

    def test_same_writer_preserves_its_first_failure_receipt_on_later_rejection(self):
        self.driver.verify=self.reject_fixture
        self.assert_fixed_failure("STAGE_SOURCE_VERIFY_FAILED")
        original=self.receipt().read_bytes()
        self.driver.verify=lambda path:{domain:True for domain in DOMAINS}
        self.driver.checkpoint=self.reject_fixture
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-stage",self.request)
        self.assertEqual("STAGE_SOURCE_IDENTITY_FAILED",str(raised.exception))
        self.assertEqual(original,self.receipt().read_bytes())
        self.assertEqual([self.receipt()],list(self.receipt().parent.iterdir()))

    def test_success_does_not_write_failure_or_advance_source_in_stage(self):
        result=self.driver.execute("render-stage",self.request)
        self.assertEqual("STAGED",result["phase"])
        self.assertFalse((self.render/"stage-failures").exists())
        self.assertEqual(self.before,(self.generation_root/"pending.json").read_bytes())

    def test_other_actions_keep_original_exception_and_do_not_write_stage_receipt(self):
        self.driver._execute=self.reject_fixture
        with self.assertRaisesRegex(ValueError,"PRIVATE_password"):
            self.driver.execute("render-status")
        self.assertFalse((self.render/"stage-failures").exists())


if __name__=="__main__":
    unittest.main()
