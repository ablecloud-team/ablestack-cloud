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
"""Legacy import failures expose only fixed source/protocol context, without mutating runtime."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import TestStorageStageDiagnostic as stage_fixture
PRIVATE=stage_fixture.PRIVATE
from rendered_driver import RenderedDriver
from rendered_generation import DOMAINS, NativeRuntimeFailure, native_runtime_diagnostic, sanitized_runtime_diagnostic
from native_render_validation import NativeRenderValidationError, native_validation_diagnostic

CODES=("IMPORT_INPUT_INVALID","IMPORT_CREDENTIAL_BINDING_FAILED","IMPORT_RENDER_FAILED",
       "IMPORT_SOURCE_SCOPE_MISMATCH","IMPORT_CANDIDATE_VALIDATION_FAILED",
       "IMPORT_SOURCE_VERIFY_FAILED","IMPORT_PUBLICATION_FAILED")


class StorageImportDiagnosticTest(unittest.TestCase):
    def setUp(self):
        stage_fixture.StorageStageDiagnosticTest.setUp(self)
        self.scope={key:self.previous[key] for key in ("instanceUuid","operationUuid","revision")}
        self.request={**self.request,**self.scope}
        self.source.update(generationStatus="IN_SYNC",pendingOperationUuid=None)
        self.store.pointer=lambda:None
        self.store.stage=lambda *args:{"scope":self.scope}
        self.driver.retain_legacy_nfs_baseline=lambda files:files
        self.driver.install_boot_guards=lambda:None
        self.publications=[]
        self.store.publish_pointer=lambda path:self.publications.append(path)
        self.store.status=lambda:{"success":True}

    def reject_fixture(self,*args,**kwargs):
        raise ValueError(PRIVATE)

    def assert_failure(self,code,protocol="GENERATION"):
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-import",self.request)
        self.assertEqual({"schemaVersion":1,"protocol":protocol,"phase":"IMPORT","code":code},
                         native_runtime_diagnostic(raised.exception))
        self.assertEqual(code,str(raised.exception))
        self.assertNotIn(PRIVATE,str(raised.exception))
        self.assertIsNone(self.driver.import_failure_code)
        self.assertEqual([] ,self.publications)
        self.assertFalse((self.render/"stage-failures").exists())
        self.assertEqual(self.before,(self.generation_root/"pending.json").read_bytes())
        return raised.exception

    def test_closed_import_contract_rejects_unknown_phase_extra_fields_and_cross_protocol(self):
        for code in CODES:
            value={"schemaVersion":1,"protocol":"GENERATION","phase":"IMPORT","code":code}
            self.assertEqual(value,sanitized_runtime_diagnostic(value))
            for changed in ({**value,"phase":"STAGE"},{**value,"reason":PRIVATE},
                            {**value,"code":"IMPORT_"+PRIVATE},{**value,"schemaVersion":True}):
                self.assertIsNone(sanitized_runtime_diagnostic(changed))
            if code!="IMPORT_RENDER_FAILED":
                self.assertIsNone(sanitized_runtime_diagnostic({**value,"protocol":"ISCSI"}))

    def test_invalid_import_input_has_no_private_exception_or_stage_receipt(self):
        self.request["revision"]=-1
        self.assert_failure("IMPORT_INPUT_INVALID")

    def test_source_private_vault_failure_is_classified_before_renderer(self):
        self.driver.baseline_credential_refs=self.reject_fixture
        self.assert_failure("IMPORT_CREDENTIAL_BINDING_FAILED")

    def test_unknown_renderer_failure_uses_generation_context_without_raw_text(self):
        self.driver.render=self.reject_fixture
        self.assert_failure("IMPORT_RENDER_FAILED")

    def test_actual_render_loop_reports_exact_failed_protocol_with_fixed_code(self):
        self.driver.render=RenderedDriver.render.__get__(self.driver)
        for domain in DOMAINS:
            with self.subTest(domain=domain):
                def nfs(*args):
                    if domain=="NFS":self.reject_fixture()
                    return {}
                def smb(*args):
                    if domain=="SMB":self.reject_fixture()
                    return {}
                def block(value,actual,*args):
                    if domain==actual:self.reject_fixture()
                    return {}
                with patch("rendered_driver.render_nfs_candidate",nfs),patch("rendered_driver.render_smb_candidate",smb),patch("rendered_driver.render_block_candidate",block):
                    self.assert_failure("IMPORT_RENDER_FAILED",domain)

    def test_foreign_source_generation_is_rejected_with_scope_code(self):
        self.store.pointer=lambda:self.render/"source"
        self.manifest["scope"]={**self.scope,"revision":self.scope["revision"]+1}
        self.assert_failure("IMPORT_SOURCE_SCOPE_MISMATCH")

    def test_candidate_validator_rejection_does_not_publish_pointer(self):
        self.store.stage=self.reject_fixture
        self.assert_failure("IMPORT_CANDIDATE_VALIDATION_FAILED")

    def test_live_source_verification_failure_keeps_import_unpublished(self):
        self.driver.verify=self.reject_fixture
        self.assert_failure("IMPORT_SOURCE_VERIFY_FAILED")

    def test_unhealthy_source_verification_boolean_is_classified(self):
        self.driver.verify=lambda path:{domain:False for domain in DOMAINS}
        self.assert_failure("IMPORT_SOURCE_VERIFY_FAILED")

    def test_boot_guard_failure_is_classified_before_pointer_publication(self):
        self.driver.install_boot_guards=self.reject_fixture
        self.assert_failure("IMPORT_PUBLICATION_FAILED")

    def test_malformed_forged_diagnostic_cannot_escape_private_text(self):
        def forged(*args):
            error=ValueError(PRIVATE)
            error.runtime_diagnostic={"schemaVersion":1,"protocol":"ISCSI","phase":"IMPORT",
                                      "code":"IMPORT_RENDER_FAILED","private":PRIVATE}
            raise error
        self.driver.render=forged
        self.assert_failure("IMPORT_RENDER_FAILED")

    def test_typed_validator_and_transport_failures_remain_closed(self):
        def validator(*args):raise NativeRenderValidationError("NFS_VALIDATOR_PARSER_REJECTED")
        self.store.stage=validator
        with self.assertRaises(NativeRenderValidationError) as raised:
            self.driver.execute("render-import",self.request)
        self.assertEqual("NFS_VALIDATOR_PARSER_REJECTED",native_validation_diagnostic(raised.exception)["runtimeValidationCode"])
        def transport(*args):raise NativeRuntimeFailure("GENERATION","NATIVE_CHILD_INPUT_UNAVAILABLE")
        self.store.stage=transport
        with self.assertRaises(NativeRuntimeFailure) as raised:
            self.driver.execute("render-import",self.request)
        self.assertEqual("NATIVE_CHILD_INPUT_UNAVAILABLE",str(raised.exception))
        self.assertIsNone(self.driver.import_failure_code)


if __name__=="__main__":
    unittest.main()
