"""Generation commit and crash compensation against isolated synthetic configuration."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage/config_generation.py"
spec = importlib.util.spec_from_file_location("generation", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class GenerationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = self.root / "configuration"
        self.config.mkdir(mode=0o700)
        (self.config / "desired-state").mkdir(mode=0o700)
        self.file = self.config / "desired-state/smb-share-apply.json"
        self.put({"shares": [{"name": "old"}]})
        self.generation = module.Generation(self.root / "generations", self.config)
        self.request = {"instanceUuid": str(uuid.uuid4()), "operationUuid": str(uuid.uuid4()), "revision": 1}

    def tearDown(self):
        self.temp.cleanup()

    def put(self, value):
        self.file.write_text(json.dumps(value))
        self.file.chmod(0o600)

    def complete(self):
        self.generation.execute("begin", self.request)
        self.generation.execute("verify", self.request)
        self.generation.execute("commit", self.request)
        self.generation.execute("finish", self.request)

    def test_commit_requires_verification_and_never_advances_pointer_on_failure(self):
        self.generation.execute("begin", self.request)
        with self.assertRaises(ValueError):
            self.generation.execute("commit", self.request)
        self.assertEqual(0, self.generation.status()["runtimeRevision"])
        self.generation.execute("verify", self.request)
        self.put({"shares": [{"name": "changed-after-verification"}]})
        with self.assertRaises(ValueError):
            self.generation.execute("commit", self.request)
        self.assertFalse(self.generation.current.exists())

    def test_durable_commit_can_compensate_after_management_crash(self):
        self.complete()
        request = {**self.request, "operationUuid": str(uuid.uuid4()), "revision": 2}
        self.generation.execute("begin", request)
        self.put({"shares": [{"name": "new"}]})
        self.generation.execute("verify", request)
        self.generation.execute("commit", request)
        recovered = module.Generation(self.root / "generations", self.config)
        self.assertEqual("PENDING", recovered.status()["generationStatus"])
        with self.assertRaises(ValueError):
            recovered.execute("rollback", request)
        self.put({"shares": [{"name": "old"}]})
        recovered.execute("rollback", request)
        self.assertEqual(1, recovered.status()["runtimeRevision"])
        self.assertEqual("IN_SYNC", recovered.status()["generationStatus"])

    def test_wrong_instance_operation_and_stale_revision_are_rejected(self):
        self.generation.execute("begin", self.request)
        for key in ("instanceUuid", "operationUuid"):
            with self.assertRaises(ValueError):
                self.generation.execute("verify", {**self.request, key: str(uuid.uuid4())})
        self.generation.execute("rollback", self.request)
        self.complete()
        with self.assertRaises(ValueError):
            self.generation.execute("begin", {**self.request, "operationUuid": str(uuid.uuid4())})

    def test_no_plain_secret_is_written_to_generation_history(self):
        self.put({"shares": [{"name": "old", "acls": [{"password": "SYNTHETIC_PASSWORD", "principal": "local"}]}],
                  "chapSecret": "SYNTHETIC_CHAP", "dhchapkey": "SYNTHETIC_DHKEY"})
        self.complete()
        for path in self.generation.root.glob("*.json"):
            text = path.read_text()
            self.assertNotIn("SYNTHETIC", text)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_reapply_timestamps_do_not_create_configuration_drift(self):
        self.put({"shares": [{"name": "old"}], "lastApply": 1})
        self.complete()
        self.put({"shares": [{"name": "old"}], "lastApply": 2})
        self.assertEqual("IN_SYNC", self.generation.status()["generationStatus"])
        self.generation.execute("finish", self.request)

    def test_symlink_or_writable_input_cannot_enter_generation(self):
        self.file.chmod(0o666)
        with self.assertRaises(ValueError):
            self.generation.execute("begin", self.request)
        self.file.unlink()
        other = self.root / "other"
        other.write_text("{}")
        self.file.symlink_to(other)
        with self.assertRaises(ValueError):
            self.generation.execute("begin", self.request)

    def test_real_cli_supports_sealed_stdin_generation_and_lock_exclusion(self):
        cli = SOURCE.parents[2] / "bin/ablestack-storagectl"
        env = dict(os.environ, ABLESTACK_STORAGE_GENERATION_DIR=str(self.root / "generations"),
                   ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config),
                   ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root / "writer.lock"))
        result = subprocess.run(["bash", str(cli), "operation", "generation", "begin", "/dev/stdin"],
                                input=json.dumps(self.request), text=True, capture_output=True, env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("PREPARED", json.loads(result.stdout)["phase"])
        result = subprocess.run(["bash", str(cli), "operation", "generation", "status"],
                                text=True, capture_output=True, env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.request["operationUuid"], json.loads(result.stdout)["pendingOperationUuid"])

    def test_status_accepts_the_empty_payload_file_used_by_the_host_agent(self):
        cli = SOURCE.parents[2] / "bin/ablestack-storagectl"
        empty = self.root / "agent-payload.json"
        empty.write_text("")
        env = dict(os.environ, ABLESTACK_STORAGE_GENERATION_DIR=str(self.root / "generations"),
                   ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config),
                   ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root / "writer.lock"))
        result = subprocess.run(["bash", str(cli), "operation", "generation", "status", str(empty)],
                                text=True, capture_output=True, env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(json.loads(result.stdout)["generationSupported"])
        self.assertFalse((self.root / "writer.lock").exists())

    def test_new_root_adopts_only_the_same_fully_reconciled_verified_generation(self):
        self.complete()
        previous = module.read_json(self.generation.current)
        target = module.Generation(self.root / "target-generation", self.config)
        request = {**self.request, "operationUuid": str(uuid.uuid4()), "revision": 2, "previousGeneration": previous}
        target.execute("adopt", request)
        self.assertEqual(1, target.status()["runtimeRevision"])
        self.assertEqual(previous, target.status()["generation"])
        self.put({"shares": [{"name": "different"}]})
        new_target = module.Generation(self.root / "unverified-generation", self.config)
        with self.assertRaises(ValueError):
            new_target.execute("adopt", request)
        self.assertFalse(new_target.current.exists())

    def seed_request(self):
        self.complete()
        source = self.generation.status()
        return {**self.request, "operationUuid": str(uuid.uuid4()), "revision": 2,
                "sourceKind": "INTERNAL_ROOT_GENERATION", "previousGeneration": source["generation"],
                "configurationDesiredState": source["configurationDesiredState"]}

    def test_new_root_seed_preserves_exact_absence_and_never_adopts_generation(self):
        request = self.seed_request()
        target = module.Generation(self.root / "target-generations", self.root / "target-configuration")
        result = target.execute("seed", request)
        self.assertTrue(result["seeded"])
        self.assertFalse(result["generationAdopted"])
        self.assertEqual(request["previousGeneration"]["configurationSha256"], target.digest())
        self.assertFalse((target.config / "iscsi-targets.json").exists())
        self.assertFalse(target.current.exists())
        target.execute("adopt", request)
        self.assertEqual(1, target.status()["runtimeRevision"])

    def test_root_seed_rejects_unsafe_paths_secrets_and_non_internal_source(self):
        request = self.seed_request()
        for changes in ({"sourceKind": "CONFIGURATION_IMPORT"},
                        {"configurationDesiredState": {**request["configurationDesiredState"], "../../etc/shadow": {}}},
                        {"configurationDesiredState": {**request["configurationDesiredState"], "iscsi-targets.json": {"chapSecret": "SYNTHETIC"}}}):
            target = module.Generation(self.root / str(uuid.uuid4()), self.root / str(uuid.uuid4()))
            with self.assertRaises(ValueError):
                target.execute("seed", {**request, **changes})
            self.assertFalse(target.config.exists())

    def test_root_seed_checksum_mismatch_leaves_target_unchanged(self):
        request = self.seed_request()
        request["configurationDesiredState"]["iscsi-targets.json"] = {}
        target = module.Generation(self.root / "target-generation", self.root / "target-configuration")
        with self.assertRaises(ValueError):
            target.execute("seed", request)
        self.assertFalse(target.config.exists())

    def pending_restore_request(self):
        self.complete()
        frozen = self.generation.execute("frozen", self.request)
        request = {**self.request, "operationUuid": str(uuid.uuid4()), "revision": 2,
                   "previousGeneration": frozen["generation"], "configurationDesiredState": frozen["configurationDesiredState"]}
        self.generation.execute("begin", request)
        return request

    def test_frozen_reads_verified_canonical_presence_without_live_configuration_or_writes(self):
        self.complete()
        before = {path: path.read_bytes() for path in self.generation.root.glob("*.json")}
        self.put({"shares": [{"name": "partially-applied"}]})
        frozen = self.generation.execute("frozen", self.request)
        self.assertEqual("old", frozen["configurationDesiredState"]["desired-state/smb-share-apply.json"]["shares"][0]["name"])
        self.assertIsNone(frozen["configurationDesiredState"]["iscsi-targets.json"])
        self.assertEqual(before, {path: path.read_bytes() for path in self.generation.root.glob("*.json")})
        self.assertEqual(frozen["configurationSha256"], frozen["generation"]["configurationSha256"])

    def test_frozen_wrong_scope_hash_or_unverified_archive_is_rejected(self):
        self.complete()
        for changes in ({"instanceUuid": str(uuid.uuid4())}, {"revision": 2}):
            with self.assertRaises(ValueError):
                self.generation.execute("frozen", {**self.request, **changes})
        path = self.generation.root / (self.request["operationUuid"] + ".json")
        value = module.read_json(path)
        value["desired"]["iscsi-targets.json"] = {}
        module.atomic_json(path, value)
        with self.assertRaises(ValueError): self.generation.execute("frozen", self.request)
        value["phase"] = "PREPARED"
        module.atomic_json(path, value)
        with self.assertRaises(ValueError): self.generation.execute("frozen", self.request)

    def test_frozen_absent_archive_does_not_create_state_or_writer_lock(self):
        cli = SOURCE.parents[2] / "bin/ablestack-storagectl"
        env = dict(os.environ, ABLESTACK_STORAGE_GENERATION_DIR=str(self.root / "absent"),
                   ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config),
                   ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root / "writer.lock"))
        result = subprocess.run(["bash", str(cli), "operation", "generation", "frozen", "/dev/stdin"],
                                input=json.dumps(self.request), text=True, capture_output=True, env=env)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertFalse((self.root / "absent").exists())
        self.assertFalse((self.root / "writer.lock").exists())

    def test_restore_normalizes_absent_files_without_changing_runtime_or_generation_journals(self):
        request = self.pending_restore_request()
        pending = self.generation.pending.read_bytes()
        current = self.generation.current.read_bytes()
        secret = self.config / "secrets"; secret.mkdir(mode=0o700)
        private = secret / "unchanged.json"; private.write_text('{"secret":"SYNTHETIC"}')
        runtime = self.root / "smb.conf"; runtime.write_text("live config unchanged")
        self.put({"shares": [{"name": "new"}]})
        module.atomic_json(self.config / "iscsi-targets.json", {"enabled": True, "targets": []})
        recovered = module.Generation(self.generation.root, self.config)
        result = recovered.execute("restore", request)
        self.assertTrue(result["canonicalRestored"]); self.assertFalse(result["generationAdvanced"])
        self.assertEqual(pending, recovered.pending.read_bytes()); self.assertEqual(current, recovered.current.read_bytes())
        self.assertFalse((self.config / "iscsi-targets.json").exists())
        self.assertEqual('{"secret":"SYNTHETIC"}', private.read_text())
        self.assertEqual("live config unchanged", runtime.read_text())
        recovered.execute("restore", request)
        recovered.execute("rollback", request)
        self.assertEqual("IN_SYNC", recovered.status()["generationStatus"])

    def test_restore_rejects_foreign_previous_arbitrary_paths_or_secret_payload_without_mutation(self):
        request = self.pending_restore_request()
        self.put({"shares": [{"name": "new"}]})
        before = self.file.read_bytes()
        desired = request["configurationDesiredState"]
        for changes in ({"operationUuid": str(uuid.uuid4())},
                        {"previousGeneration": {**request["previousGeneration"], "revision": 99}},
                        {"configurationDesiredState": {**desired, "../../etc/shadow": {}}},
                        {"configurationDesiredState": {**desired, "iscsi-targets.json": {"chapSecret": "SYNTHETIC"}}},
                        {"configurationDesiredState": {**desired, "iscsi-targets.json": {}}}):
            with self.assertRaises(ValueError): self.generation.execute("restore", {**request, **changes})
            self.assertEqual(before, self.file.read_bytes())
            self.assertTrue(self.generation.pending.exists())

    def test_restore_io_failure_reverts_partial_normalization_and_leaves_pending(self):
        request = self.pending_restore_request()
        self.put({"shares": [{"name": "new"}]})
        module.atomic_json(self.config / "iscsi-targets.json", {"enabled": True})
        before = self.generation.files()
        original = module.atomic_json
        def fail_once(path, value):
            if path == self.file and not getattr(fail_once, "failed", False):
                fail_once.failed = True
                raise OSError("injected canonical write failure")
            return original(path, value)
        with patch.object(module, "atomic_json", side_effect=fail_once):
            with self.assertRaises(OSError): self.generation.execute("restore", request)
        self.assertEqual(before, self.generation.files())
        self.assertTrue(self.generation.pending.exists())

    def test_protected_frozen_read_rejects_file_permission_swap_at_open(self):
        self.complete()
        path = self.generation.root / (self.request["operationUuid"] + ".json")
        original = os.open
        def replace_mode(name, flags, *args, **kwargs):
            if name == path.name:
                path.chmod(0o666)
            return original(name, flags, *args, **kwargs)
        with patch.object(module.os, "open", side_effect=replace_mode):
            with self.assertRaises(ValueError): self.generation.execute("frozen", self.request)
        path.chmod(0o600)

    def test_retained_root_aligns_forward_only_after_current_configuration_replay(self):
        self.complete()
        original = module.read_json(self.generation.current)
        next_request = {**self.request, "operationUuid": str(uuid.uuid4()), "revision": 2}
        self.generation.execute("begin", next_request)
        self.put({"shares": [{"name": "latest"}]})
        for action in ("verify", "commit", "finish"):
            self.generation.execute(action, next_request)
        latest = module.read_json(self.generation.current)
        retained = module.Generation(self.root / "retained-generation", self.config)
        module.atomic_json(retained.current, original)
        request = {**self.request, "operationUuid": str(uuid.uuid4()), "revision": 3,
                   "previousGeneration": latest, "expectedPreviousGeneration": original}
        with self.assertRaises(ValueError):
            retained.execute("adopt", request)
        retained.execute("align", request)
        self.assertEqual(2, retained.status()["runtimeRevision"])
        with self.assertRaises(ValueError):
            retained.execute("align", {**request, "previousGeneration": original})

if __name__ == "__main__":
    unittest.main()
