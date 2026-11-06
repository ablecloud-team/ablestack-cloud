# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file distributed
# with this work for additional information regarding copyright ownership.
# The ASF licenses this file under the Apache License, Version 2.0
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS.
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid

MODULE = Path(os.environ.get("CONFIGURATION_MODULE", Path(__file__).resolve().parents[1] /
              "debian/usr/local/lib/ablestack-storage/configuration_transaction.py"))
spec = importlib.util.spec_from_file_location("configuration_transaction", MODULE)
configuration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configuration)


class JournalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="configuration-test-")
        self.root = Path(self.temp.name)
        self.instance = str(uuid.uuid4())
        self.operation = str(uuid.uuid4())
        self.calls = []
        self.runtime = {"value": "old"}
        self.failure = None
        self.rollback_failure = False
        outer = self
        class Adapter:
            def __init__(self, private):
                self.private = private
            def preflight(self, payload):
                outer.calls.append("preflight")
                if outer.failure == "VALIDATING":
                    raise configuration.Rejected("INVALID_CANDIDATE")
            def snapshot(self, payload):
                outer.calls.append("snapshot")
            def apply(self, candidate):
                outer.calls.append("apply")
                outer.runtime["value"] = "new"
                if outer.failure == "APPLYING":
                    raise configuration.Rejected("FIRST_FAILURE")
            def verify(self, candidate):
                outer.calls.append("verify")
                if outer.failure == "VERIFYING":
                    raise configuration.Rejected("FIRST_FAILURE")
            def restore(self):
                outer.calls.append("restore")
                if outer.rollback_failure:
                    raise configuration.Rejected("SECOND_FAILURE")
                outer.runtime["value"] = "old"
            def verify_restored(self):
                outer.calls.append("verify_restored")
                if outer.runtime["value"] != "old":
                    raise AssertionError("runtime differs")
            def is_running(self):
                return outer.failure == "RUNNING"
            def observe_completed(self):
                return "UNKNOWN"
        self.transaction = configuration.Transaction(self.root / "journal", Adapter)
        self.payload = {"instanceUuid": self.instance, "operationid": self.operation,
                        "baseRevision": 0, "targetRevision": 1,
                        "before": {"nfs export apply": {"exports": []}},
                        "candidate": {"nfs export apply": {"exports": []}}}

    def tearDown(self):
        self.temp.cleanup()

    def test_four_protocols_apply_failure_rolls_back_and_keeps_first_failure(self):
        for protocol in ("nfs export apply", "smb share apply", "iscsi target apply", "nvmeof subsystem apply"):
            with self.subTest(protocol=protocol):
                self.payload["operationid"] = str(uuid.uuid4())
                self.payload["candidate"] = {protocol: {"items": ["changed"]}}
                self.failure = "APPLYING"
                value = self.transaction.execute("apply", self.payload)
                self.assertEqual("ROLLED_BACK", value["state"])
                self.assertEqual("SUCCEEDED", value["rollback"]["state"])
                self.assertEqual("FIRST_FAILURE", value["originalfailure"]["code"])
                self.assertEqual("old", self.runtime["value"])
                self.assertEqual(value, self.transaction.show(self.payload))

    def test_duplicate_request_has_no_second_apply_even_with_changed_secret(self):
        self.payload["candidate"]["nfs export apply"]["password"] = "transient-one"
        first = self.transaction.execute("apply", self.payload)
        self.payload["candidate"]["nfs export apply"]["password"] = "transient-two"
        self.assertEqual(first, self.transaction.execute("apply", self.payload))
        self.assertEqual(1, self.calls.count("apply"))
        for file in (self.root / "journal").rglob("*"):
            if file.is_file():
                self.assertNotIn("transient-", file.read_text())

    def test_public_payload_conflict_rejected_without_mutation(self):
        self.transaction.execute("apply", self.payload)
        self.payload["candidate"]["nfs export apply"]["enabled"] = False
        with self.assertRaisesRegex(configuration.Rejected, "REQUEST_CONFLICT"):
            self.transaction.execute("apply", self.payload)
        self.assertEqual(1, self.calls.count("apply"))

    def test_verification_failure_and_failed_rollback_are_separate_after_reload(self):
        self.failure = "VERIFYING"
        self.rollback_failure = True
        value = self.transaction.execute("apply", self.payload)
        self.assertEqual("RECOVERY_REQUIRED", value["state"])
        reloaded = configuration.Transaction(self.root / "journal").show(self.payload)
        self.assertEqual("FIRST_FAILURE", reloaded["originalfailure"]["code"])
        self.assertEqual("SECOND_FAILURE", reloaded["rollback"]["code"])

    def test_preflight_failure_does_not_apply_or_restore(self):
        self.failure = "VALIDATING"
        value = self.transaction.execute("apply", self.payload)
        self.assertEqual("NOT_NEEDED", value["rollback"]["state"])
        self.assertEqual(["preflight"], self.calls)

    def test_getter_never_calls_adapter_or_mutates_journal(self):
        self.transaction.execute("apply", self.payload)
        calls = list(self.calls)
        before = {str(p): p.read_bytes() for p in (self.root / "journal").rglob("*") if p.is_file()}
        self.transaction.show(self.payload)
        self.assertEqual(calls, self.calls)
        self.assertEqual(before, {str(p): p.read_bytes() for p in (self.root / "journal").rglob("*") if p.is_file()})

    def test_revision_fence_and_verified_commit(self):
        self.transaction.execute("apply", self.payload)
        self.transaction.execute("commit", self.payload)
        stale = dict(self.payload, operationid=str(uuid.uuid4()))
        with self.assertRaisesRegex(configuration.Rejected, "REVISION_CONFLICT"):
            self.transaction.execute("apply", stale)
        self.assertEqual(1, self.calls.count("apply"))

    def test_new_attempt_is_blocked_while_previous_is_unresolved(self):
        self.transaction.execute("apply", self.payload)
        another = dict(self.payload, operationid=str(uuid.uuid4()))
        with self.assertRaisesRegex(configuration.Rejected, "RECOVERY_REQUIRED"):
            self.transaction.execute("apply", another)

    def test_unknown_or_live_apply_is_never_blindly_replayed_or_rolled_back(self):
        self.failure = "APPLYING"
        self.rollback_failure = True
        self.transaction.execute("apply", self.payload)
        self.failure = "RUNNING"
        calls = list(self.calls)
        value = self.transaction.execute("resume", self.payload)
        self.assertEqual("RECOVERY_REQUIRED", value["state"])
        self.assertEqual(calls, self.calls)

    def test_confirmed_failure_resumes_rollback_not_stale_candidate_success(self):
        self.failure = "VERIFYING"
        self.rollback_failure = True
        first = self.transaction.execute("apply", self.payload)
        self.assertEqual("RECOVERY_REQUIRED", first["state"])
        self.rollback_failure = False
        self.failure = None
        def invalid_observation(adapter):
            raise AssertionError("confirmed failure must not be reclassified from stale child success")
        self.transaction.adapter_factory.observe_completed = invalid_observation
        result = self.transaction.execute("resume", self.payload)
        self.assertEqual("ROLLED_BACK", result["state"])
        self.assertEqual("SUCCEEDED", result["rollback"]["state"])
        self.assertEqual(first["originalfailure"], result["originalfailure"])
        self.assertEqual("old", self.runtime["value"])

    def test_identity_traversal_rejected(self):
        self.payload["operationid"] = "../other"
        with self.assertRaises(configuration.Rejected):
            self.transaction.show(self.payload)


if __name__ == "__main__":
    unittest.main(verbosity=2)
