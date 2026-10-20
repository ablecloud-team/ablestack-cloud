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
"""Held file acceptors are replayed from authenticated source evidence, not hashes alone."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"))
import TestStorageRenderedGeneration as generation_fixture
from rendered_driver import RenderedDriver
from rendered_generation import RenderedGeneration, rendered_json


class StorageHeldFileResumeGenerationTest(unittest.TestCase):
    def setUp(self):
        self.fixture=generation_fixture.StorageRenderedGenerationTest()
        self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        f=self.fixture
        self.request=f.next_scope()
        self.manifest=f.store.stage(self.request,f.files,f.validate)
        self.resume={"schemaVersion":1,"scope":f.store.scope(self.request),"bootId":str(uuid.uuid4()),
            "sourceRenderedManifestSha256":f.manifest["manifestSha256"],
            "sourceConfigurationSha256":f.manifest["configurationSha256"],
            "stoppedReceiptSha256":"a"*64,"stoppedDomains":["NFS"]}

    def test_unchanged_held_nfs_requires_replay_even_when_configuration_hash_is_identical(self):
        f=self.fixture;f.live.pop("NFS")
        with self.assertRaises(generation_fixture.module.NativeRuntimeFailure):
            f.store.activate(self.request,f.replay,f.verify)
        self.assertEqual([],f.calls)
        self.assertNotEqual("VERIFIED",f.store.read_journal()["phase"])

    def test_proven_held_nfs_is_replayed_once_without_touching_unchanged_block_targets(self):
        f=self.fixture;f.live.pop("NFS")
        result=f.store.activate(self.request,f.replay,f.verify,service_source_resume=self.resume)
        self.assertEqual("VERIFIED",result["activation"]["phase"])
        self.assertEqual([("NFS",False)],f.calls)
        self.assertEqual(["NFS"],result["activation"]["changedDomains"])
        self.assertEqual(self.resume,result["activation"]["serviceSourceResume"])

    def test_two_proven_file_domains_resume_without_starting_other_domains(self):
        f=self.fixture
        for domain in ("NFS","SMB"):f.live.pop(domain)
        self.resume["stoppedDomains"]=["NFS","SMB"]
        result=f.store.activate(self.request,f.replay,f.verify,service_source_resume=self.resume)
        self.assertEqual("VERIFIED",result["activation"]["phase"])
        self.assertEqual([("NFS",False),("SMB",False)],f.calls)

    def test_verified_target_reentry_performs_readback_without_replaying_held_domains(self):
        f=self.fixture;f.live.pop("NFS")
        f.store.activate(self.request,f.replay,f.verify,service_source_resume=self.resume)
        f.calls.clear()
        result=f.store.activate(self.request,f.replay,f.verify,service_source_resume=self.resume)
        self.assertEqual("VERIFIED",result["activation"]["phase"])
        self.assertEqual([],f.calls)
        f.store.finalize(self.request,f.generation,f.verify)
        f.calls.clear()
        result=f.store.activate(self.request,f.replay,f.verify)
        self.assertEqual("COMPLETE",result["activation"]["phase"])
        self.assertEqual([],f.calls)

    def test_unheld_unchanged_source_keeps_existing_no_replay_behavior(self):
        f=self.fixture
        result=f.store.activate(self.request,f.replay,f.verify)
        self.assertEqual("VERIFIED",result["activation"]["phase"])
        self.assertEqual([],f.calls)
        self.assertNotIn("serviceSourceResume",result["activation"])

    def test_foreign_scope_source_or_non_file_domain_is_rejected_before_effects(self):
        f=self.fixture
        for values in ({"scope":{**self.resume["scope"],"operationUuid":str(uuid.uuid4())}},
                {"sourceRenderedManifestSha256":"b"*64},{"sourceConfigurationSha256":"c"*64},
                {"stoppedDomains":["ISCSI"]},{"stoppedDomains":["NFS","NFS"]},
                {"stoppedDomains":["SMB","NFS"]},{"reason":"PRIVATE_password"}):
            with self.subTest(values=values):
                with self.assertRaises(ValueError):
                    f.store.activate(self.request,f.replay,f.verify,service_source_resume={**self.resume,**values})
                self.assertEqual([],f.calls)
                self.assertEqual(f.manifest,f.store.inspect(f.store.pointer()))

    def test_interrupted_activation_cannot_acquire_new_stopped_domains(self):
        f=self.fixture;f.live.pop("NFS")
        f.store.activate(self.request,f.replay,f.verify,service_source_resume=self.resume)
        journal=f.store.read_journal();journal["phase"]="ACTIVATING"
        generation_fixture.module.rendered_json(f.store.journal,journal);f.calls.clear()
        changed={**self.resume,"stoppedDomains":["NFS","SMB"]}
        with self.assertRaises(ValueError):
            f.store.activate(self.request,f.replay,f.verify,service_source_resume=changed)
        self.assertEqual([],f.calls)
        self.assertEqual(self.resume,f.store.read_journal()["serviceSourceResume"])


class StorageHeldFileResumeDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":3}
        self.service_scope={**self.scope,"maintenanceUuid":self.scope["operationUuid"]}
        self.source={"generation":{"instanceUuid":self.scope["instanceUuid"],"operationUuid":str(uuid.uuid4()),"revision":2}}
        self.driver=RenderedDriver(ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl",RenderedGeneration(self.root/"render"))
        self.driver.store.read_journal=lambda:{"scope":self.source["generation"],"phase":"COMPLETE"}
        self.checkpoint={"publicKey":"SOURCE_PUBLIC_FIXTURE","capsule":{"sha256":"a"*64}}
        self.driver.recovery_key=Mock(return_value=self.checkpoint)
        self.driver.root_source.canonical_bytes=Mock(return_value={"source":"fixed bytes"})
        self.marker={"maintenanceKind":"SERVICE","bootHeld":True,"scope":self.service_scope}
        self.driver.runtime.command=Mock(return_value=self.marker)
        self.receipt={"scope":self.service_scope,"owners":[{"unit":"ablestack-storage-ganesha@known.service"}]}
        self.native={"sourceStoppedReceipt":self.receipt,"bootId":str(uuid.uuid4()),
            "sourceRendered":{"manifestSha256":"b"*64},"sourceConfigurationSha256":"c"*64}
        from service_identity_cipher import service_cipher_digest
        self.digest=service_cipher_digest(self.receipt)
        self.driver.service_identity.stopped=Mock(return_value={"stoppedReceiptSha256":self.digest})
        path=self.root/"source.json";path.write_text("protected fixture exists")
        self.files=Mock();self.files.source_path.return_value=path
        self.files.authorize.return_value=self.checkpoint;self.files.source.return_value=self.native

    def test_driver_authenticates_original_cipher_and_fresh_stopped_receipt_before_domain_selection(self):
        with patch("rendered_driver.ServiceIdentityCipher",return_value=self.files):
            result=self.driver.held_service_file_resume(self.scope,self.source)
        self.assertEqual(["NFS"],result["stoppedDomains"])
        self.assertEqual(self.scope,result["scope"])
        self.assertEqual(self.digest,result["stoppedReceiptSha256"])
        self.driver.service_identity.stopped.assert_called_once_with(self.service_scope)
        self.assertEqual("SOURCE_PUBLIC_FIXTURE",self.files.authorize.call_args[0][0]["checkpointPublicKey"])

    def test_foreign_unit_or_changed_stop_receipt_cannot_seed_activation(self):
        self.receipt["owners"]=[{"unit":"ssh.service"}]
        from service_identity_cipher import service_cipher_digest
        self.driver.service_identity.stopped.return_value={"stoppedReceiptSha256":service_cipher_digest(self.receipt)}
        with patch("rendered_driver.ServiceIdentityCipher",return_value=self.files):
            with self.assertRaisesRegex(ValueError,"foreign acceptor"):
                self.driver.held_service_file_resume(self.scope,self.source)
        self.receipt["owners"]=[{"unit":"ablestack-storage-ganesha@known.service"}]
        self.driver.service_identity.stopped.return_value={"stoppedReceiptSha256":"d"*64}
        with patch("rendered_driver.ServiceIdentityCipher",return_value=self.files):
            with self.assertRaisesRegex(ValueError,"receipt changed"):
                self.driver.held_service_file_resume(self.scope,self.source)

    def test_interrupted_or_verified_target_never_recollects_itself_as_stopped_source(self):
        for phase in ("ACTIVATING","RECOVERY_REQUIRED","VERIFIED","COMPLETE"):
            self.driver.store.read_journal=lambda:{"scope":self.scope,"phase":phase}
            with patch("rendered_driver.ServiceIdentityCipher",return_value=self.files):
                self.assertIsNone(self.driver.held_service_file_resume(self.scope,self.source))
        self.driver.recovery_key.assert_not_called()
        self.driver.service_identity.stopped.assert_not_called()
        self.driver.runtime.command.assert_not_called()

    def test_unheld_verify_action_dispatches_only_to_fixed_native_source_reader(self):
        self.driver.service_identity.unheld_verify=Mock(return_value={"success":True,"sideEffects":False})
        self.assertEqual({"success":True,"sideEffects":False},
            self.driver.execute("render-service-source-unheld-verify",self.service_scope))
        self.driver.service_identity.unheld_verify.assert_called_once_with(self.service_scope)


if __name__=="__main__":
    unittest.main()
