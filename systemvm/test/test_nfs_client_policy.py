# Licensed under the Apache License, Version 2.0. See NOTICE.
# http://www.apache.org/licenses/LICENSE-2.0
"""Policy classification from a real Java desired payload, legacy config bytes,
actual transaction boundary, compensating reload, and ambiguous-result guards."""
import copy
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "debian/usr/local/lib/ablestack-storage"))
import configuration_transaction as tx
import nfs_client_policy_runtime as policy
import nfs_ganesha_render as render
from shared_directory_policy import FileSharePathBindings


def payload():
    old = json.loads((ROOT / "test/fixtures/nfs-1460-actual-desired.json").read_text())
    new = copy.deepcopy(old)
    new["exports"][0]["acls"][0]["principal"] = "10.10.254.228/32"
    new["configurationOperationId"] = str(uuid.uuid4())
    new["posixIntent"] = {"version": 1, "create": False, "fields": {}, "targetUuid": str(uuid.uuid4())}
    return {"instanceUuid": old["instanceUuid"], "operationid": str(uuid.uuid4()),
            "baseRevision": 0, "targetRevision": 1,
            "before": {policy.OP: old}, "candidate": {policy.OP: new}}


class Classification(unittest.TestCase):
    def test_actual_java_payload_client_policy_and_transport_metadata(self):
        self.assertTrue(policy.policy_only(payload()))
        for key, value in (("readOnly", True), ("allSquash", True), ("rootSquash", False), ("anonUid", 123)):
            request = payload()
            request["candidate"][policy.OP]["exports"][0]["config"][key] = value
            self.assertTrue(policy.policy_only(request), key)

    def test_immutable_and_posix_changes_never_select_online_branch(self):
        for key, value in (("backingPath", "/other"), ("volumeMountPath", "/other"),
                           ("filesystemUuid", "other"), ("listenerGroupPorts", [9999]),
                           ("ownerUid", 123), ("ownerGid", 123), ("mode", "0700"),
                           ("recursivePermission", True), ("pathIdentity", {"inode": 1})):
            request = payload()
            request["candidate"][policy.OP]["exports"][0]["config"][key] = value
            self.assertFalse(policy.policy_only(request), key)
        for key, value in (("protocolMode", "V3V4_DUAL"), ("enabled", False),
                           ("nfs4IdentityMappingMode", "NUMERIC"), ("port", 9999)):
            request = payload()
            request["candidate"][policy.OP][key] = value
            self.assertFalse(policy.policy_only(request), key)
        for key in ("fields", "create"):
            request = payload()
            request["candidate"][policy.OP]["posixIntent"][key] = {"mode": "0700"} if key == "fields" else True
            self.assertFalse(policy.policy_only(request))
        for field in ("uuid", "name", "path", "volumeId"):
            request = payload()
            request["candidate"][policy.OP]["exports"][0][field] = "different"
            self.assertFalse(policy.policy_only(request), field)

    def test_creation_other_protocol_and_unknown_acl_intent_keep_existing_path(self):
        for change in ("create", "extra", "unknown"):
            request = payload()
            if change == "create":
                request["before"][policy.OP]["exports"] = []
            elif change == "extra":
                request["candidate"]["smb share apply"] = {}
            else:
                request["candidate"][policy.OP]["exports"][0]["acls"][0]["config"]["secretOption"] = True
            self.assertFalse(policy.policy_only(request), change)

    def test_acl_delete_keeps_documented_initial_default_not_fake_deny_all(self):
        request = payload()
        request["candidate"][policy.OP]["exports"][0]["acls"] = []
        self.assertTrue(policy.policy_only(request))
        exports = render.payload_exports(request["candidate"][policy.OP])
        self.assertEqual("*", exports[("0.0.0.0", 2049)][0]["clients"][0]["clients"])

    def test_common_renderer_preserves_legacy_config_and_client_bytes(self):
        golden = json.loads((ROOT / "test/fixtures/nfs-1460-legacy-render.json").read_text())
        for case in golden["cases"]:
            with self.subTest(case=case["name"]):
                value = case["value"]
                exports = render.payload_exports(value)
                actual = render.render_configurations(exports, tx.NfsIdentityMapping.lines(value))
                self.assertEqual(case["configs"], {str(port): text for (_, port), text in actual.items()})
                for row in value["exports"]:
                    self.assertEqual(case["clients"][row["uuid"]],
                        render.render_clients(row, render.effective_nfs_export_config(row["config"])))


class FakeEngine(policy.ClientPolicies):
    """Only native process/syscall seams are replaced; real files/journal/transaction."""
    def __init__(self, private, root):
        super().__init__(private, root / "config", root / "run", root / "settings")
        self.calls = []
        self.fail_second = False
        self.unconfirmed = False
        self.identity_changed = False
        self.no_ack = False
        for p in (self.config, self.run, self.settings / "desired-state", self.private):
            p.mkdir(parents=True, exist_ok=True, mode=0o700)
    def process(self, key, path, port):
        return {"pid": int(port), "start": "changed" if self.identity_changed else "1", "boot": "same-boot"}
    def directories(self, value):
        return {"physical": {"inode": 42, "acl": "unchanged", "filesystemuuid": "xfs"}}
    def observe(self, snapshot):
        return {"share": [{"port": 2049, "status": "EXTERNAL_CLIENT_REQUIRED",
                           "reason": "LOCAL_ADDRESS_EXCLUDED_BY_ACL", "configurationState": "APPLIED"}]}
    def ack(self, fd, signum, *_):
        pid = self.fd_pid
        self.calls.append(pid)
        log = self.run / (policy.endpoint_key("0.0.0.0", pid) + ".log")
        if self.unconfirmed:
            replacement = log.with_suffix(".new")
            replacement.write_text("new log")
            os.replace(replacement, log)
            self.unconfirmed = False
        elif self.fail_second and pid == 2050:
            with log.open("a") as f:
                f.write(":CONFIG :CRIT :Error while parsing EXPORT entries\n")
            self.fail_second = False
        elif not self.no_ack:
            with log.open("a") as f:
                f.write(":CONFIG :EVENT :Reread exports complete\n")


class EngineAndTransaction(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.req = payload()
        self.engine = FakeEngine(self.root / "op", self.root)
        self.enrich = patch.object(FileSharePathBindings, "enrich_desired", lambda *a: None)
        self.enrich.start()
        self.addCleanup(self.enrich.stop)
        policy.write_file(self.engine.desired, json.dumps(self.req["before"][policy.OP]).encode())
        for (ip, port), text in self.engine.rendered(self.req["before"][policy.OP]).items():
            key = policy.endpoint_key(ip, port)
            policy.write_file(self.engine.config / (key + ".conf"), text.encode(), 0o644)
            (self.engine.run / (key + ".log")).write_text("baseline\n")
        self.snapshot = self.engine.snapshot(self.req)
        def pidfd(pid):
            self.engine.fd_pid = pid
            return os.open("/dev/null", os.O_RDONLY)
        self.openfd = patch.object(os, "pidfd_open", side_effect=pidfd)
        self.signal = patch.object(signal, "pidfd_send_signal", side_effect=self.engine.ack)
        self.openfd.start(); self.signal.start()
        self.addCleanup(self.openfd.stop); self.addCleanup(self.signal.stop)
        self.addCleanup(self.tmp.cleanup)

    def test_two_port_apply_verify_duplicate_and_restore_same_process(self):
        self.engine.apply(self.snapshot)
        self.engine.verify(self.snapshot)
        self.assertEqual([2049, 2050], self.engine.calls)
        self.engine.apply(self.snapshot)
        self.assertEqual([2049, 2050], self.engine.calls)
        self.engine.restore(self.snapshot)
        self.engine.verify(self.snapshot, True)
        self.assertEqual([2049, 2050, 2049, 2050], self.engine.calls)
        self.assertFalse(self.engine.probes.exists())
        self.assertEqual(self.req["before"][policy.OP], json.loads(self.engine.desired.read_text()))

    def adapter(self, private):
        outer = self
        class Adapter(tx.RuntimeAdapter):
            def preflight(self, value):
                self.payload = value
                tx.atomic_json(self.payload_path, value)
            def nfs_client_policy_engine(self):
                outer.engine.private = self.private
                outer.engine.journal = self.private / "nfs-client-policy.json"
                return outer.engine
        return Adapter(private)

    def test_real_transaction_partial_failure_compensates_and_preserves_original(self):
        self.engine.fail_second = True
        result = tx.Transaction(self.root / "transactions", self.adapter).execute("apply", self.req)
        self.assertEqual("ROLLED_BACK", result["state"])
        self.assertEqual("SUCCEEDED", result["rollback"]["state"])
        self.assertEqual("NFS_CLIENT_RELOAD_REJECTED", result["originalfailure"]["code"])
        self.assertEqual(2050, result["originalfailure"]["detail"]["port"])
        self.assertEqual([2049, 2050, 2049, 2050], self.engine.calls)
        second = tx.Transaction(self.root / "transactions", self.adapter).execute("apply", self.req)
        self.assertEqual(result["state"], second["state"])
        self.assertEqual(4, len(self.engine.calls))

    def test_real_transaction_lost_reload_ack_never_claims_not_started_or_replays(self):
        self.engine.unconfirmed = True
        engine = tx.Transaction(self.root / "transactions", self.adapter)
        result = engine.execute("apply", self.req)
        self.assertEqual("RECOVERY_REQUIRED", result["state"])
        self.assertEqual("UNKNOWN", result["rollback"]["state"])
        self.assertEqual("NFS_CLIENT_RELOAD_UNCONFIRMED", result["originalfailure"]["code"])
        self.assertNotIn("started", result["originalfailure"])
        engine.execute("apply", self.req)
        self.assertEqual([2049], self.engine.calls)

    def test_process_config_and_acl_identity_change_never_signal(self):
        self.engine.identity_changed = True
        with self.assertRaisesRegex(policy.PolicyError, "NFS_CLIENT_RUNTIME_IDENTITY_CHANGED"):
            self.engine.apply(self.snapshot)
        self.assertEqual([], self.engine.calls)

    def test_foreign_config_is_not_overwritten(self):
        row = self.snapshot["rows"][0]
        Path(row["config"]).write_text("unowned replacement")
        with self.assertRaisesRegex(policy.PolicyError, "NFS_CLIENT_CONFIG_CHANGED"):
            self.engine.apply(self.snapshot)
        self.assertEqual("unowned replacement", Path(row["config"]).read_text())
        self.assertEqual([], self.engine.calls)

    def test_process_disappearing_at_pidfd_open_keeps_original_port_cause(self):
        with patch.object(os, "pidfd_open", side_effect=ProcessLookupError()):
            with self.assertRaises(policy.PolicyError) as error:
                self.engine.apply(self.snapshot)
        self.assertEqual("NFS_CLIENT_RUNTIME_IDENTITY_CHANGED", error.exception.code)
        self.assertEqual(2049, error.exception.detail["port"])
        self.assertEqual([], self.engine.calls)

    def test_noop_is_verified_without_signal(self):
        self.req["candidate"][policy.OP] = copy.deepcopy(self.req["before"][policy.OP])
        snap = self.engine.snapshot(self.req)
        self.engine.apply(snap)
        self.engine.verify(snap)
        self.assertEqual([], self.engine.calls)

    def test_rolled_back_before_new_journal_is_safe_and_validated(self):
        self.engine.restore(self.snapshot)
        self.engine.verify(self.snapshot, True)
        self.assertEqual([], self.engine.calls)


class Observation(unittest.TestCase):
    def test_reused_non_ganesha_pid_is_observation_unknown_not_inventory_error(self):
        with tempfile.TemporaryDirectory() as root:
            base=Path(root); (base / "0.0.0.0_2049.pid").write_text("1234")
            engine=policy.ClientPolicies(config=base,run=base,settings=base)
            with patch.object(tx.NfsIdentityMapping,"process",side_effect=tx.Rejected("NFS_IDENTITY_PROCESS_INVALID")):
                with self.assertRaisesRegex(policy.PolicyError,"NFS_CLIENT_RUNTIME_IDENTITY_CHANGED"):
                    engine.process("0.0.0.0_2049",base / "0.0.0.0_2049.conf",2049)

    def test_damaged_observation_file_cannot_break_inventory_or_claim_success(self):
        with tempfile.TemporaryDirectory() as root:
            settings=Path(root);(settings / "desired-state").mkdir()
            desired=json.loads((ROOT / "test/fixtures/nfs-1460-actual-desired.json").read_text())
            (settings / "desired-state/nfs-export-apply.json").write_text(json.dumps(desired))
            (settings / "nfs-local-probes.json").write_text("{truncated")
            value=policy.observations({},settings,settings / "config",settings / "run")
            self.assertEqual(2,len(value))
            self.assertTrue(all(r["status"]=="UNKNOWN" for rows in value.values() for r in rows))

    def test_old_observation_is_not_success_and_fresh_boot_can_replace_it(self):
        with tempfile.TemporaryDirectory() as root:
            settings = Path(root)
            old = {"share": [{"port": 2049, "status": "MOUNT_VERIFIED", "observedAt": 1}]}
            (settings / "nfs-local-probes.json").write_text(json.dumps(old))
            value = policy.observations({}, settings, settings / "config", settings / "run")
            self.assertEqual("UNKNOWN", value["share"][0]["status"])
            self.assertEqual("MOUNT_VERIFIED", value["share"][0]["previousStatus"])
            boot = {"state": "READY", "bootId": "current", "updatedAt": "now",
                    "exports": [{"exportUuid": "share", "port": 2049, "state": "EXTERNAL_CLIENT_REQUIRED"}]}
            value = policy.observations(boot, settings, settings / "config", settings / "run")
            self.assertEqual("EXTERNAL_CLIENT_REQUIRED", value["share"][0]["status"])
            self.assertEqual("BOOT_OBSERVATION", value["share"][0]["applyMethod"])


if __name__ == "__main__":
    unittest.main()
