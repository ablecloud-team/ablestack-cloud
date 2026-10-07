"""Generation commit and crash compensation against isolated synthetic configuration."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

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
