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
if os.environ.get("NFS_CLIENT_POLICY_CANDIDATE"):
    sys.path.insert(0, os.environ["NFS_CLIENT_POLICY_CANDIDATE"])
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
            def path_bindings(self, restoring=False):
                # Same filesystem observation seam as FakeEngine.directories().
                # Keep real receipt ownership, persistence and verification logic.
                bindings = FileSharePathBindings(journal=self.private / "pathbindings.json")
                rows = outer.req["before"][policy.OP]["exports"]
                records = {}
                for row in rows:
                    if not bindings.bound(row):
                        continue
                    config = row["config"]
                    records[row["uuid"]] = {"resourceuuid": row["uuid"],
                        "volumeuuid": Path(config["volumeMountPath"]).name,
                        "relativepath": config["relativeSharePath"], "pathIdentity": config["pathIdentity"],
                        "anchor": str(bindings.anchor(row["uuid"], config["pathIdentity"])), "protocol": "NFS"}
                bindings.registered = lambda: {"version": 1, "records": copy.deepcopy(records)}
                bindings.observe = lambda row: {"status": "VERIFIED"}
                return bindings
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


    def ordered_request(self):
        request = payload()
        # Creation order deliberately differs from UUID order, including CLIENT precedence.
        request["before"][policy.OP]["exports"].reverse()
        rules = [
            {"uuid": "z-broad-first", "principalType": "CIDR", "principal": "192.0.2.0/24",
             "permission": "READ_ONLY", "state": "Ready", "config": {"rootSquash": True}},
            {"uuid": "a-specific-second", "principalType": "CIDR", "principal": "192.0.2.12/32",
             "permission": "READ_WRITE", "state": "Ready", "config": {"rootSquash": True}}
        ]
        request["before"][policy.OP]["exports"][0]["acls"] = rules
        request["candidate"][policy.OP] = copy.deepcopy(request["before"][policy.OP])
        request["candidate"][policy.OP]["exports"][0]["acls"][1]["config"]["allSquash"] = True
        request["candidate"][policy.OP]["configurationOperationId"] = request["operationid"]
        request["candidate"][policy.OP]["posixIntent"] = {"create": False, "fields": {}}
        policy.write_file(self.engine.desired, json.dumps(request["before"][policy.OP]).encode())
        for (ip, port), text in self.engine.rendered(request["before"][policy.OP]).items():
            policy.write_file(self.engine.config / (policy.endpoint_key(ip, port) + ".conf"), text.encode(), 0o644)
        return request

    def test_save_reload_real_boot_renderer_followup_and_rollback_keep_order(self):
        request = self.ordered_request()
        transaction = tx.Transaction(self.root / "transactions", self.adapter)
        result = transaction.execute("apply", request)
        self.assertEqual("VERIFIED", result["state"], result)
        transaction.execute("commit", request)
        saved = json.loads(self.engine.desired.read_text())
        wanted = copy.deepcopy(request["candidate"][policy.OP])
        wanted.pop("configurationOperationId"); wanted.pop("posixIntent")
        self.assertEqual(wanted["exports"], saved["exports"])
        # This is the renderer imported by the real CLI boot-prepare path.
        boot_configs = render.render_configurations(render.payload_exports(saved), tx.NfsIdentityMapping.lines(saved))
        self.assertEqual(self.engine.rendered(wanted), boot_configs)
        for (ip, port), text in boot_configs.items():
            policy.write_file(self.engine.config / (policy.endpoint_key(ip, port) + ".conf"), text.encode(), 0o644)
        process = self.engine.process
        self.engine.process = lambda key, path, port: {**process(key, path, port), "boot": "after-restart", "start": "2"}
        follow = {**request, "operationid": str(uuid.uuid4()), "baseRevision": 1, "targetRevision": 2,
                  "before": {policy.OP: wanted}, "candidate": {policy.OP: copy.deepcopy(wanted)}}
        follow["candidate"][policy.OP]["exports"][0]["acls"][1]["permission"] = "READ_ONLY"
        result = transaction.execute("apply", follow)
        self.assertEqual("VERIFIED", result["state"], result)
        transaction.execute("commit", follow)
        committed = self.engine.desired.read_bytes()
        failed = {**follow, "operationid": str(uuid.uuid4()), "baseRevision": 2, "targetRevision": 3,
                  "before": copy.deepcopy(follow["candidate"]), "candidate": copy.deepcopy(follow["candidate"])}
        failed["candidate"][policy.OP]["exports"][0]["acls"][1]["permission"] = "READ_WRITE"
        self.engine.fail_second = True
        result = transaction.execute("apply", failed)
        self.assertEqual("ROLLED_BACK", result["state"], result)
        self.assertEqual("SUCCEEDED", result["rollback"]["state"])
        self.assertEqual(committed, self.engine.desired.read_bytes())

    def test_verify_detects_sorted_acl_tampering_even_if_unordered_values_match(self):
        request = self.ordered_request()
        snapshot = self.engine.snapshot(request)
        self.engine.apply(snapshot)
        saved = json.loads(self.engine.desired.read_text())
        saved["exports"][0]["acls"].reverse()
        policy.write_file(self.engine.desired, json.dumps(saved).encode())
        with self.assertRaisesRegex(policy.PolicyError, "NFS_CLIENT_DESIRED_CHANGED"):
            self.engine.verify(snapshot)

    def test_validation_keeps_safe_original_port_and_stage(self):
        request = self.ordered_request()
        path = self.engine.config / "0.0.0.0_2049.conf"
        path.write_text("foreign content")
        before = path.read_bytes()
        result = tx.Transaction(self.root / "transactions", self.adapter).execute("apply", request)
        self.assertEqual("ROLLED_BACK", result["state"])
        self.assertEqual("NOT_NEEDED", result["rollback"]["state"])
        self.assertEqual("NFS_CLIENT_CONFIG_CHANGED", result["originalfailure"]["code"])
        self.assertEqual(2049, result["originalfailure"]["detail"]["port"])
        self.assertEqual("NFS_CLIENT_POLICY", result["originalfailure"]["detail"]["stage"])
        self.assertEqual(before, path.read_bytes())
        self.assertEqual([], self.engine.calls)


    def test_explicit_verified_resume_revalidates_missing_receipt_without_reapply(self):
        transaction = tx.Transaction(self.root / "transactions", self.adapter)
        result = transaction.execute("apply", self.req)
        self.assertEqual("VERIFIED",result["state"])
        calls = list(self.engine.calls)
        private = self.root / "transactions" / self.req["instanceUuid"] / self.req["operationid"]
        (private / "pathbindings.json").unlink()
        result = transaction.execute("resume", self.req)
        self.assertEqual("VERIFIED",result["state"])
        self.assertEqual(self.req["operationid"],result["pathbindings"]["operationid"])
        self.assertEqual(calls,self.engine.calls)


class ExistingBindingReceipts(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.engine = FileSharePathBindings(managed=self.base / "volumes", root=self.base / "bindings",
                                           journal=self.base / "private/receipt.json")
        self.value = {"instanceUuid": str(uuid.uuid4()), "operationid": str(uuid.uuid4())}
        identity = {"filesystemUuid": str(uuid.uuid4()), "volumeRootInode": 128, "inode": 456}
        volume = str(uuid.uuid4())
        self.row = {"uuid": str(uuid.uuid4()), "state": "Ready", "config": {"pathBindingVersion": 1,
            "relativeSharePath": "export/sibling", "volumeMountPath": str(self.engine.managed / volume), "pathIdentity": identity}}
        self.entry = {"resourceuuid": self.row["uuid"], "volumeuuid": volume, "relativepath": "export/sibling",
            "pathIdentity": identity, "anchor": str(self.engine.anchor(self.row["uuid"], identity)), "protocol": "NFS"}
        self.registry = {"version": 1, "records": {self.row["uuid"]: self.entry}}
        self.commands = {"nfs export apply": {"exports": [self.row]}}
        self.registered = patch.object(self.engine, "registered", side_effect=lambda: copy.deepcopy(self.registry))
        self.observed = patch.object(self.engine, "observe", return_value={"status": "VERIFIED"})
        self.registered.start(); self.observed.start()
        self.addCleanup(self.registered.stop); self.addCleanup(self.observed.stop)

    def test_existing_receipt_preserves_registry_and_uses_current_operation(self):
        before = copy.deepcopy(self.registry)
        with patch.object(self.engine, "ensure", side_effect=AssertionError("must not repin")):
            receipt = self.engine.retain_existing(self.value, self.commands)
            self.assertEqual(receipt, self.engine.retain_existing(self.value, self.commands))
        self.assertEqual(before, self.registry)
        self.assertEqual(self.value["operationid"], receipt["operationid"])
        self.assertEqual(self.value["instanceUuid"], receipt["instanceUuid"])
        self.assertTrue(receipt["existingOnly"])
        self.assertEqual({k:v for k,v in self.entry.items() if k not in ("anchor", "protocol")}, receipt["bindings"][self.row["uuid"]])

    def test_all_active_bound_rows_are_covered_but_legacy_and_disabled_are_not(self):
        second = copy.deepcopy(self.row);second["uuid"] = str(uuid.uuid4())
        entry = copy.deepcopy(self.entry);entry["resourceuuid"] = second["uuid"]
        entry["anchor"] = str(self.engine.anchor(second["uuid"], entry["pathIdentity"]))
        self.registry["records"][second["uuid"]] = entry
        self.commands["nfs export apply"]["exports"] += [second, {"uuid":"legacy", "config":{}}, {**second,"uuid":"disabled","state":"Disabled"}]
        receipt = self.engine.retain_existing(self.value,self.commands)
        self.assertEqual({self.row["uuid"],second["uuid"]},set(receipt["bindings"]))

    def test_registration_identity_and_anchor_mismatches_remain_rejected(self):
        original = copy.deepcopy(self.registry)
        for key in ("resourceuuid","volumeuuid","relativepath","anchor","pathIdentity","protocol"):
            self.registry = copy.deepcopy(original)
            self.registry["records"][self.row["uuid"]][key] = "foreign"
            with self.subTest(key=key), self.assertRaisesRegex(Exception,"DIRECTORY_BINDING_RECEIPT_MISMATCH"):
                self.engine.retain_existing(self.value,self.commands)
        self.assertFalse(self.engine.journal.exists())

    def test_existing_receipt_operation_or_instance_cannot_be_reused(self):
        self.engine.retain_existing(self.value,self.commands)
        for key in ("operationid","instanceUuid"):
            with self.subTest(key=key), self.assertRaisesRegex(Exception,"DIRECTORY_BINDING_RECEIPT_MISMATCH"):
                self.engine.retain_existing({**self.value,key:str(uuid.uuid4())},self.commands)

    def test_actual_observation_failure_is_not_hidden_by_registered_receipt(self):
        with patch.object(self.engine,"observe",return_value={"status":"UNAVAILABLE","reason":"DIRECTORY_BINDING_IDENTITY_CHANGED"}):
            with self.assertRaisesRegex(Exception,"DIRECTORY_BINDING_IDENTITY_CHANGED"):
                self.engine.retain_existing(self.value,self.commands)
        self.assertFalse(self.engine.journal.exists())


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
