# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information.
# The ASF licenses this file under the Apache License, Version 2.0.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for permissions and limitations under the License.

"""Closed failure contracts and real journal writes; no daemon, mount or QGA."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import Mock, patch

LIB = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(LIB))
try:
    import rendered_generation as generation
    import native_render_runtime as runtime
    import nfs_readiness as readiness
finally:
    sys.path.remove(str(LIB))

PRIVATE = "SYNTHETIC_PRIVATE_STDERR_PASSWORD_PATH"


class RuntimeDiagnosticTest(unittest.TestCase):
    def test_all_fixed_pairs_and_protocols_are_accepted(self):
        for code, phase in generation.RUNTIME_CODE_PHASES.items():
            protocols = ("NFS",) if code.startswith("NFS_") or code == "ENDPOINT_LISTENER_ADDRESS_MISMATCH" else generation.RUNTIME_PROTOCOLS
            if code == "RUNTIME_PREPARE_FAILED":
                protocols = ("GENERATION",)
            for protocol in protocols:
                error = generation.NativeRuntimeFailure(protocol, code)
                self.assertEqual({"schemaVersion": 1, "protocol": protocol, "phase": phase, "code": code},
                                 generation.native_runtime_diagnostic(error))
                self.assertNotIn(PRIVATE, str(error))

    def test_invalid_extra_private_and_mismatched_fields_are_rejected(self):
        value = generation.NativeRuntimeFailure("NFS", "ENDPOINT_LISTENER_ADDRESS_MISMATCH").runtime_diagnostic
        candidates = [None, [], PRIVATE, {**value, "private": PRIVATE}, {**value, "code": PRIVATE},
                      {**value, "protocol": "SMB"}, {**value, "phase": "APPLY"},
                      {**value, "schemaVersion": True}, {**value, "schemaVersion": 1.0},
                      {**value, "schemaVersion": "1"}, {**value, "schemaVersion": 2}]
        candidates.extend([{key: field for key, field in value.items() if key != missing} for missing in value])
        for candidate in candidates:
            self.assertIsNone(generation.sanitized_runtime_diagnostic(candidate))
        for protocol, code in (("SMB", "NFS_ALIAS_UNMOUNT_FAILED"), ("NFS", "RUNTIME_PREPARE_FAILED"),
                               ("UNKNOWN", "RUNTIME_APPLY_FAILED"), ("NFS", PRIVATE)):
            with self.assertRaises(ValueError):
                generation.NativeRuntimeFailure(protocol, code)

    def command(self, stdout, returncode=1, arguments=("nfs", "export", "apply")):
        child = Mock(pid=999999, returncode=returncode)
        child.communicate.return_value = (stdout, None)
        native = runtime.NativeRenderedRuntime("/not-executed", Mock(), deadline=time.monotonic() + 30)
        with patch.object(runtime.subprocess, "Popen", return_value=child) as spawn:
            try:
                return native.command(arguments)
            finally:
                self.assertEqual(subprocess.DEVNULL, spawn.call_args.kwargs["stderr"])
                self.assertNotIn(PRIVATE, str(spawn.call_args.args))

    def failure(self, body, code, protocol="NFS", returncode=1):
        with self.assertRaises(generation.NativeRuntimeFailure) as caught:
            self.command(body, returncode, runtime.RENDER_COMMANDS.get(protocol, ("operation", "verify")))
        self.assertEqual(code, caught.exception.runtime_diagnostic["code"])
        self.assertEqual(protocol, caught.exception.runtime_diagnostic["protocol"])
        self.assertNotIn(PRIVATE, str(caught.exception))

    def test_empty_malformed_nonobject_output_is_fixed_transport_failure(self):
        for body in ("", "{", "null", "[]", "\"" + PRIVATE + "\"", "true"):
            self.failure(body, "NATIVE_CHILD_OUTPUT_INVALID")

    def test_unknown_child_failure_does_not_copy_private_stdout(self):
        for protocol in generation.RUNTIME_PROTOCOLS:
            self.failure(json.dumps({"success": False, "reason": PRIVATE, "lastFailure": {"code": PRIVATE}}),
                         "NATIVE_CHILD_FAILED", protocol)

    def test_known_child_readiness_failure_copies_only_fixed_code(self):
        for code in ("ENDPOINT_LISTENER_ADDRESS_MISMATCH", "NFS_ALIAS_UNMOUNT_FAILED", "NFS_ALIAS_UNMOUNT_UNVERIFIED"):
            self.failure(json.dumps({"success": False, "lastFailure": {
                "protocol": "NFS", "code": code, "endpointIp": PRIVATE, "private": PRIVATE}}), code)

    def test_child_runtime_contract_requires_exact_shape_and_matching_protocol(self):
        diagnostic = generation.NativeRuntimeFailure("NFS", "ENDPOINT_LISTENER_ADDRESS_MISMATCH").runtime_diagnostic
        self.failure(json.dumps({"success": False, "runtimeDiagnostic": diagnostic, "reason": PRIVATE}),
                     "ENDPOINT_LISTENER_ADDRESS_MISMATCH")
        self.failure(json.dumps({"success": False, "runtimeDiagnostic": {**diagnostic, "private": PRIVATE}}),
                     "NATIVE_CHILD_FAILED")
        self.failure(json.dumps({"success": False, "runtimeDiagnostic": diagnostic}), "NATIVE_CHILD_FAILED", "SMB")

    def test_nonzero_exit_never_promotes_success(self):
        self.failure('{"success":true}', "NATIVE_CHILD_FAILED")

    def test_success_reply_is_unchanged(self):
        value = {"success": True, "publicValue": 1}
        self.assertEqual(value, self.command(json.dumps(value), 0))

    def test_child_timeout_is_fixed_and_termination_is_bounded(self):
        child = Mock(pid=999999, returncode=None)
        child.communicate.side_effect = [subprocess.TimeoutExpired("private", 1), ("", None)]
        native = runtime.NativeRenderedRuntime("/not-executed", Mock(), deadline=time.monotonic() + 30)
        with patch.object(runtime.subprocess, "Popen", return_value=child), patch.object(runtime.os, "killpg") as kill:
            with self.assertRaises(generation.NativeRuntimeFailure) as caught:
                native.command(("nfs", "export", "apply"))
        self.assertEqual("NATIVE_CHILD_TIMEOUT", caught.exception.runtime_diagnostic["code"])
        self.assertEqual(1, kill.call_count)
        self.assertTrue(all(0 < call.kwargs["timeout"] <= 30 for call in child.communicate.call_args_list))

    def test_listener_initialization_may_finish_within_the_existing_poll_budget(self):
        adapter = Mock()
        adapter.owned_endpoint_manager.return_value = ("owned", Mock())
        adapter.configuration.return_value = {"exports": []}
        endpoint = {"listenIp": "10.10.13.227", "port": 2049, "pid": 42, "protocolMode": "V4_ONLY"}
        subject = readiness.NfsEndpointReadiness.__new__(readiness.NfsEndpointReadiness)
        subject.deadline = 30
        subject.adapter_type = Mock(return_value=adapter)
        subject.snapshot = Mock(return_value=({"pid": 42}, "synthetic config"))
        subject.listener_owned = Mock(side_effect=[False, True])
        with patch.object(readiness.time, "monotonic", return_value=0), patch.object(readiness.time, "sleep") as sleep:
            self.assertIs(endpoint, subject.wait(endpoint, "/not-opened"))
        self.assertTrue(endpoint["listenerOwned"])
        self.assertEqual(2, subject.listener_owned.call_count)
        self.assertEqual(1, sleep.call_count)

    def test_listener_address_mismatch_is_not_reported_as_export_inventory_failure(self):
        adapter = Mock()
        adapter.owned_endpoint_manager.return_value = ("owned", Mock())
        adapter.configuration.return_value = {"exports": []}
        endpoint = {"listenIp": "10.10.13.227", "port": 2049, "pid": 42, "protocolMode": "V4_ONLY"}
        subject = readiness.NfsEndpointReadiness.__new__(readiness.NfsEndpointReadiness)
        subject.deadline = 30
        subject.adapter_type = Mock(return_value=adapter)
        subject.snapshot = Mock(return_value=({"pid": 42}, "synthetic config"))
        subject.listener_owned = Mock(return_value=False)
        with patch.object(readiness.time, "monotonic", side_effect=[0, 1, 31]):
            with self.assertRaises(readiness.NfsReadinessError) as caught:
                subject.wait(endpoint, "/not-opened")
        self.assertEqual("ENDPOINT_LISTENER_ADDRESS_MISMATCH", caught.exception.public["code"])
        self.assertEqual(1, adapter.verify.call_count)


class JournalFailureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = generation.RenderedGeneration(Path(self.temp.name) / "rendered")
        self.store.root.mkdir(mode=0o700)
        self.store.generations.mkdir(mode=0o700)
        self.instance, self.previous, self.target = (str(uuid.uuid4()) for _ in range(3))
        self.request = {"instanceUuid": self.instance, "operationUuid": self.target, "revision": 2}
        self.manifests = {}
        for operation, revision in ((self.previous, 1), (self.target, 2)):
            (self.store.generations / operation).mkdir(mode=0o700)
            self.manifests[operation] = {"scope": {**self.request, "operationUuid": operation, "revision": revision},
                "manifestSha256": operation.replace("-", "") * 2,
                "previousRenderedSha256": self.previous.replace("-", "") * 2 if revision == 2 else None,
                "domainSha256": {domain: ("old" if revision == 1 else "new") for domain in generation.DOMAINS}}
        self.store.inspect = lambda path: self.manifests[Path(path).name] if path else None
        self.store.publish_pointer(self.store.generations / self.previous)

    def tearDown(self):
        self.temp.cleanup()

    def verify(self, path):
        return {domain: True for domain in generation.DOMAINS}

    def assert_private_absent(self):
        self.assertNotIn(PRIVATE, self.store.journal.read_text())
        self.assertEqual(0o600, self.store.journal.stat().st_mode & 0o777)

    def test_forward_failure_survives_successful_inverse(self):
        def replay(path, domain, inverse):
            if not inverse:
                raise generation.NativeRuntimeFailure("NFS", "ENDPOINT_LISTENER_ADDRESS_MISMATCH")
        with self.assertRaises(generation.NativeRuntimeFailure):
            self.store.activate(self.request, replay, self.verify)
        journal = self.store.read_journal()
        self.assertEqual("ROLLED_BACK", journal["phase"])
        self.assertEqual("ENDPOINT_LISTENER_ADDRESS_MISMATCH", journal["forwardFailure"]["code"])
        self.assertNotIn("rollbackFailure", journal)
        self.assertEqual(self.previous, self.store.pointer().name)
        self.assert_private_absent()

    def test_both_failures_and_latest_inverse_progress_are_preserved(self):
        def replay(path, domain, inverse):
            if inverse:
                latest = self.store.scoped_activation(self.request)
                latest["inverseProgressRetained"] = True
                generation.rendered_json(self.store.journal, latest)
                raise generation.NativeRuntimeFailure("NFS", "NFS_ALIAS_UNMOUNT_FAILED")
            raise generation.NativeRuntimeFailure("NFS", "ENDPOINT_LISTENER_ADDRESS_MISMATCH")
        with self.assertRaises(generation.NativeRuntimeFailure) as caught:
            self.store.activate(self.request, replay, self.verify)
        journal = self.store.read_journal()
        self.assertEqual("RECOVERY_REQUIRED", journal["phase"])
        self.assertTrue(journal["inverseProgressRetained"])
        self.assertEqual("ENDPOINT_LISTENER_ADDRESS_MISMATCH", journal["forwardFailure"]["code"])
        self.assertEqual("NFS_ALIAS_UNMOUNT_FAILED", journal["rollbackFailure"]["code"])
        self.assertEqual(journal["forwardFailure"], caught.exception.runtime_diagnostic)
        self.assert_private_absent()

    def test_unknown_exceptions_are_fixed_metadata_without_messages(self):
        def replay(path, domain, inverse):
            raise RuntimeError(PRIVATE)
        with self.assertRaises(generation.NativeRuntimeFailure) as caught:
            self.store.activate(self.request, replay, self.verify)
        journal = self.store.read_journal()
        self.assertEqual("RUNTIME_APPLY_FAILED", journal["forwardFailure"]["code"])
        self.assertEqual("RUNTIME_ROLLBACK_FAILED", journal["rollbackFailure"]["code"])
        self.assertNotIn(PRIVATE, str(caught.exception))
        self.assert_private_absent()

    def test_prerequisite_failure_has_a_fixed_generation_code(self):
        def prerequisites(path, inverse):
            if not inverse:
                raise RuntimeError(PRIVATE)
        with self.assertRaises(generation.NativeRuntimeFailure):
            self.store.activate(self.request, lambda *args: None, self.verify, prerequisites=prerequisites)
        self.assertEqual("RUNTIME_PREPARE_FAILED", self.store.read_journal()["forwardFailure"]["code"])

    def test_forward_verify_failure_remains_separate_from_rollback_verify(self):
        def verify(path):
            return {domain: path.name != self.target for domain in generation.DOMAINS}
        with self.assertRaises(generation.NativeRuntimeFailure):
            self.store.activate(self.request, lambda *args: None, verify)
        journal = self.store.read_journal()
        self.assertEqual("RUNTIME_VERIFY_FAILED", journal["forwardFailure"]["code"])
        self.assertEqual("ROLLED_BACK", journal["phase"])
        self.assertNotIn("rollbackFailure", journal)


if __name__ == "__main__":
    unittest.main()
