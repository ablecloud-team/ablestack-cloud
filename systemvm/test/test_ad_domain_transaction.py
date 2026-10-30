# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import json
import tempfile
from pathlib import Path
import unittest
import uuid
from unittest.mock import Mock, patch
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"))
import configuration_transaction as tx
import configuration_preflight as admission
import shared_directory_policy as policies


class AdDomainTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.instance = str(uuid.uuid4())
        self.op = str(uuid.uuid4())
        self.desired = self.root / "desired.json"
        self.desired.write_text(json.dumps({"instanceUuid": self.instance, "shares": []}))
        self.clients = [self.root / name for name in ["resolv.conf", "krb5.conf"]]
        for p in self.clients: p.write_text("original")
        self.adapter = Mock(spec=tx.RuntimeAdapter)
        self.adapter.snapshot_path = self.root / "before.json"
        self.adapter.snapshot.side_effect = lambda value: self.adapter.snapshot_path.write_text("{}")
        self.join = tx.AdDomainJoin(self.root / "ad", lambda private: self.adapter,
            self.root / "configuration", self.desired, self.clients, [self.clients[1]])
        self.value = {"instanceUuid": self.instance, "operationid": self.op, "password": "never-persist",
                      "smbDesiredState": {"instanceUuid": self.instance, "shares": [], "listeners": []}}
        self.member = patch.object(self.join, "member")
        self.member_mock = self.member.start()
        self.addCleanup(self.member.stop)
        for target, key, value in [(policies, "inheritance_protection_version", 1),
                                   (admission, "inspect", {"status": "READY", "checks": []})]:
            p = patch.object(target, key, return_value=value); p.start(); self.addCleanup(p.stop)
        p = patch.object(tx, "checked", return_value=b"verified"); p.start(); self.addCleanup(p.stop)

    def test_verified_only_after_member_snapshot_apply_and_real_engine_verify_contract(self):
        result = self.join.run(self.value)
        self.assertTrue(result["success"])
        self.assertEqual(("JOINED", "VERIFIED", "COMMITTED"),
                         (result["membership"], result["serviceApply"], result["state"]))
        self.assertEqual(["preflight", "snapshot", "apply", "verify"],
                         [v[0] for v in self.adapter.mock_calls])
        self.assertNotIn("never-persist", "".join(p.read_text() for p in self.root.rglob("*.json")))

    def test_post_apply_failure_rolls_back_service_and_retains_membership(self):
        self.adapter.apply.side_effect = tx.Rejected("PROTOCOL_APPLY_FAILED")
        result = self.join.run(self.value)
        self.assertFalse(result["success"])
        self.assertEqual(("JOINED", "FAILED", "ROLLED_BACK", "SUCCEEDED"),
                         (result["membership"], result["serviceApply"], result["state"], result["rollback"]))
        self.adapter.restore.assert_called_once()
        self.adapter.verify_restored.assert_called_once()

    def test_structured_child_failure_code_is_preserved_without_command_output(self):
        def fail(commands):
            private = self.root / "ad" / self.instance / self.op
            tx.atomic_json(private / "runner-result.json", {"result": {"code": "SMB_ENDPOINT_IN_USE", "password": "never-persist"}})
            raise tx.Rejected("PROTOCOL_APPLY_FAILED")
        self.adapter.apply.side_effect = fail
        result = self.join.run(self.value)
        self.assertEqual("SMB_ENDPOINT_IN_USE", result["failureCode"])
        self.assertNotIn("never-persist", json.dumps(result))

    def test_restore_failure_keeps_recovery_blocker_and_diagnostics(self):
        self.adapter.verify.side_effect = tx.Rejected("POST_VERIFY_FAILURE")
        self.adapter.restore.side_effect = tx.Rejected("RESTORE_FAILURE")
        result = self.join.run(self.value)
        self.assertEqual("RECOVERY_REQUIRED", result["state"])
        self.assertEqual("POST_VERIFY_FAILURE", result["failureCode"])
        next_value = {**self.value, "operationid": str(uuid.uuid4())}
        with self.assertRaisesRegex(tx.Rejected, "AD_DOMAIN_RECOVERY_REQUIRED"): self.join.run(next_value)

    def test_failed_membership_restores_client_configuration_and_never_applies_smb(self):
        def failed(value):
            for p in self.clients: p.write_text("candidate")
            raise tx.Rejected("AD_MEMBERSHIP_KINIT_FAILED")
        self.member_mock.side_effect = failed
        result = self.join.run(self.value)
        self.assertEqual(("FAILED", "NOT_APPLIED", "MEMBERSHIP_FAILED"),
                         (result["membership"], result["serviceApply"], result["state"]))
        self.assertTrue(all(p.read_text() == "original" for p in self.clients))
        self.adapter.apply.assert_not_called()

    def test_same_guest_lease_rejects_second_writer_before_membership(self):
        def member(value):
            other = tx.AdDomainJoin(self.root / "ad", lambda p: self.adapter,
                self.root / "configuration", self.desired, self.clients, [self.clients[1]])
            with self.assertRaises(BlockingIOError):
                other.run({**value, "operationid": str(uuid.uuid4())})
        self.member_mock.side_effect = member
        self.assertTrue(self.join.run(self.value)["success"])

    def test_baseline_protection_and_admission_refusal_precede_mutation(self):
        with patch.object(policies, "inheritance_protection_version", return_value=0):
            with self.assertRaisesRegex(tx.Rejected, "AD_DOMAIN_BASELINE_REQUIRED"): self.join.run(self.value)
        with patch.object(admission, "inspect", return_value={"status": "BLOCKED", "checks": [{"code": "DRAIN_REQUIRED", "blocking": True}]}):
            with self.assertRaisesRegex(tx.Rejected, "DRAIN_REQUIRED"): self.join.run(self.value)
        self.member_mock.assert_not_called()
        self.adapter.apply.assert_not_called()

    def test_runtime_activation_cannot_cross_pending_or_corrupt_ad_recovery(self):
        import runtime_updater as updater
        self.join.run(self.value)
        root = self.root / "ad"
        updater.require_ad_domain_idle(root)
        path = root / self.instance / "status.json"
        for content in ('{"state":"RUNNING"}', '{"state":"RECOVERY_REQUIRED"}', '[]', '{broken'):
            path.write_text(content)
            with self.assertRaises(updater.RuntimeUpgradeError) as error:
                updater.require_ad_domain_idle(root)
            self.assertEqual("CONFIGURATION_RECOVERY_REQUIRED", error.exception.code)
        path.write_text('{"state":"COMMITTED"}')
        path.chmod(0o666)
        with self.assertRaises(updater.RuntimeUpgradeError): updater.require_ad_domain_idle(root)

    def test_status_file_is_readonly_bounded_and_private(self):
        self.join.run(self.value)
        path = self.root / "ad" / self.instance / "status.json"
        original = path.read_bytes()
        self.assertEqual("VERIFIED", tx.ad_domain_status(self.instance, self.root / "ad")["serviceApply"])
        self.assertEqual(original, path.read_bytes())
        path.chmod(0o644)
        with self.assertRaisesRegex(tx.Rejected, "AD_DOMAIN_STATUS_UNSAFE"):
            tx.ad_domain_status(self.instance, self.root / "ad")

    def test_missing_kerberos_is_durable_before_image_and_member_can_create_it(self):
        self.clients[1].unlink()
        def member(value):
            self.clients[1].write_text("generated realm")
            return {}
        self.member_mock.side_effect = member
        result = self.join.run(self.value)
        self.assertTrue(result["success"])
        image = self.root / "ad" / self.instance / self.op / "client-before.json"
        self.assertEqual({"exists": False}, json.loads(image.read_text())[str(self.clients[1])])
        self.assertEqual(0o600, image.stat().st_mode & 0o777)
        self.assertEqual("generated realm", self.clients[1].read_text())

    def test_failure_restores_absence_and_existing_file_bytes_mode_and_inode(self):
        self.clients[1].unlink()
        self.clients[0].chmod(0o640)
        inode = self.clients[0].stat().st_ino
        def failed(value):
            for path in self.clients: path.write_text("generated")
            raise tx.Rejected("AD_MEMBERSHIP_KINIT_FAILED")
        self.member_mock.side_effect = failed
        result = self.join.run(self.value)
        self.assertEqual(("MEMBERSHIP_FAILED", "SUCCEEDED"), (result["state"], result["rollback"]))
        self.assertFalse(self.clients[1].exists())
        self.assertEqual(("original", 0o640, inode),
                         (self.clients[0].read_text(), self.clients[0].stat().st_mode & 0o777, self.clients[0].stat().st_ino))
        self.adapter.apply.assert_not_called()

    def test_before_image_can_be_reloaded_without_in_memory_state(self):
        self.clients[1].unlink()
        image = self.root / "private" / "before.json"
        self.join.client_files.snapshot(image)
        for path in self.clients: path.write_text("generated")
        tx.AdClientConfiguration(self.clients, [self.clients[1]]).restore(image)
        self.assertEqual("original", self.clients[0].read_text())
        self.assertFalse(self.clients[1].exists())

    def test_missing_required_dns_and_unsafe_entries_are_rejected_before_member(self):
        self.clients[0].unlink()
        with self.assertRaisesRegex(tx.Rejected, "AD_CLIENT_CONFIGURATION_UNSAFE"): self.join.run(self.value)
        self.clients[0].write_text("original")
        self.clients[1].unlink()
        victim = self.root / "victim"; victim.write_text("unchanged")
        for kind in ["symlink", "hardlink", "world_writable", "fifo"]:
            if self.clients[1].exists() or self.clients[1].is_symlink(): self.clients[1].unlink()
            if kind == "symlink": self.clients[1].symlink_to(victim)
            elif kind == "hardlink": __import__("os").link(victim, self.clients[1])
            elif kind == "fifo": __import__("os").mkfifo(self.clients[1])
            else:
                self.clients[1].write_text("unsafe"); self.clients[1].chmod(0o666)
            with self.assertRaisesRegex(tx.Rejected, "AD_CLIENT_CONFIGURATION_UNSAFE"):
                self.join.run({**self.value, "operationid": str(uuid.uuid4())})
        self.assertEqual("unchanged", victim.read_text())
        self.member_mock.assert_not_called()

    def test_uncertain_join_retains_created_configuration_and_recovery_guard(self):
        self.clients[1].unlink()
        def uncertain(value):
            self.clients[1].write_text("membership may be established")
            raise tx.Rejected("AD_MEMBERSHIP_JOIN_FAILED")
        self.member_mock.side_effect = uncertain
        result = self.join.run(self.value)
        self.assertEqual(("RECOVERY_REQUIRED", "UNKNOWN"), (result["state"], result["membership"]))
        self.assertTrue(self.clients[1].is_file())
        with self.assertRaisesRegex(tx.Rejected, "AD_DOMAIN_RECOVERY_REQUIRED"):
            self.join.run({**self.value, "operationid": str(uuid.uuid4())})

    def test_unsafe_new_entry_is_not_unlinked_and_failed_restore_stays_blocked(self):
        self.clients[1].unlink()
        victim = self.root / "victim"; victim.write_text("unchanged")
        def failed(value):
            self.clients[1].symlink_to(victim)
            raise tx.Rejected("AD_MEMBERSHIP_KINIT_FAILED")
        self.member_mock.side_effect = failed
        result = self.join.run(self.value)
        self.assertEqual(("RECOVERY_REQUIRED", "FAILED"), (result["state"], result["rollback"]))
        self.assertTrue(self.clients[1].is_symlink())
        self.assertEqual("unchanged", victim.read_text())


if __name__ == "__main__":
    unittest.main()
