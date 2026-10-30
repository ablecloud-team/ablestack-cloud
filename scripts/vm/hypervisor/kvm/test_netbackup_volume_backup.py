#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Exact selection and durable no-resubmission tests for NetBackup User Backup."""
import copy
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("netbackup_volume_backup", Path(__file__).with_name("netbackup-volume-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class NetBackupVolumeBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.id = "adc49c78-f92c-4339-9f6c-6a792ae34904"
        self.job = self.root / self.id
        self.job.mkdir()
        self.staging = self.root / "staged volume"
        self.staging.mkdir()
        self.volume = self.staging / "root.raw"
        self.volume.write_bytes(b"volume-data")
        self.file = self.job / "netbackup-backup-test.json"
        self.plan = {"artifact": {"backupUuid": self.id, "path": str(self.volume), "size": 11},
                     "metadata": False, "policy": "prod-host-4", "schedule": "mold-transfer", "server": "primary", "afterJobId": "40"}
        self.executable = self.root / "bpbackup"
        self.executable.touch(mode=0o700)
        self.addCleanup(patch.stopall)
        patch.object(backup, "BPBACKUP", self.executable).start()
        patch.object(backup, "CONTROL_ROOT", self.root / "user_ops").start()
        self.run = patch.object(backup.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)).start()
        self.write_request()

    def write_request(self):
        self.file.write_text(json.dumps(self.plan))
        (self.job / "volume-plan.json").write_text(json.dumps({"manifest": {"backupUuid": self.id}, "backupPath": str(self.staging)}))
        (self.job / "volume-request.json").write_text(json.dumps({"backupUuid": self.id,
                    "artifact": self.plan["artifact"], "metadata": self.plan["metadata"]}))

    def test_exact_file_list_uses_the_existing_policy_and_submission_is_not_completion(self):
        result = backup.submit(self.file, self.plan)
        args = self.run.call_args.args[0]
        self.assertEqual("prod-host-4", args[args.index("-p") + 1])
        self.assertEqual("mold-transfer", args[args.index("-s") + 1])
        selection = Path(args[args.index("-f") + 1])
        self.assertEqual("%d %s\n" % (len(str(self.volume)), self.volume), selection.read_text())
        self.assertNotIn("-i", args)
        self.assertNotIn("-w", args)
        self.assertTrue(result.startswith("userbackup:" + self.id + ":"))
        self.assertNotIn("completed", json.loads(self.file.with_suffix(".receipt.json").read_text()))
        self.assertEqual(0o600, selection.stat().st_mode & 0o777)

    def test_response_loss_and_recovery_after_staging_cleanup_never_repeat_submission(self):
        first = backup.submit(self.file, self.plan)
        self.volume.unlink()
        (self.job / "volume-request.json").unlink()
        self.assertEqual(first, backup.submit(self.file, self.plan, recover_only=True))
        self.assertEqual(first, backup.submit(self.file, self.plan))
        self.assertEqual(1, self.run.call_count)

    def test_intent_is_durable_before_the_client_command_is_invoked(self):
        def execute(*args, **kwargs):
            saved = json.loads(self.file.with_suffix(".intent.json").read_text())
            self.assertEqual(self.plan, saved["plan"])
            self.assertTrue(saved["requestId"].endswith(":40"))
            return subprocess.CompletedProcess([], 0)
        self.run.side_effect = execute
        backup.submit(self.file, self.plan)

    def test_proven_rejection_is_durable_and_read_only_on_recovery(self):
        self.volume.write_bytes(b"changed")
        for recover in (False, True):
            argv = ["netbackup-volume-backup.py", "--plan-file", str(self.file)] + (["--recover-only"] if recover else [])
            with self.subTest(recover=recover), patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()) as output:
                backup.main()
            saved = json.loads(output.getvalue().split("ABLESTACK_NOT_SUBMITTED=", 1)[1])
            self.assertTrue(saved["notSubmitted"])
        self.run.assert_not_called()

    def test_timeout_or_nonzero_exit_preserves_the_attempt_for_exact_job_reconciliation(self):
        for outcome in (subprocess.TimeoutExpired(["bpbackup"], 110), subprocess.CompletedProcess([], 73)):
            with self.subTest(outcome=outcome):
                self.file.with_suffix(".intent.json").unlink(missing_ok=True)
                self.file.with_suffix(".receipt.json").unlink(missing_ok=True)
                self.run.reset_mock()
                self.run.side_effect = outcome if isinstance(outcome, Exception) else None
                self.run.return_value = outcome
                reference = backup.submit(self.file, self.plan)
                self.assertTrue(self.file.with_suffix(".intent.json").exists())
                self.assertEqual(reference, backup.submit(self.file, self.plan, recover_only=True))
                self.assertEqual(1, self.run.call_count)

    def test_missing_receipt_recovery_and_changed_intent_do_not_submit(self):
        with self.assertRaisesRegex(RuntimeError, "will not be resubmitted"):
            backup.submit(self.file, self.plan, recover_only=True)
        self.run.assert_not_called()
        backup.submit(self.file, self.plan)
        changed = copy.deepcopy(self.plan)
        changed["policy"] = "another-policy"
        with self.assertRaisesRegex(RuntimeError, "another artifact"):
            backup.submit(self.file, changed, recover_only=True)
        self.assertEqual(1, self.run.call_count)

    def test_changed_volume_or_request_cannot_be_submitted(self):
        self.volume.write_bytes(b"changed")
        with self.assertRaisesRegex(RuntimeError, "size changed"):
            backup.submit(self.file, self.plan)
        self.run.assert_not_called()
        self.assertFalse(self.file.with_suffix(".intent.json").exists())

    def test_metadata_directory_is_sent_only_after_final_metadata_is_ready(self):
        self.plan["metadata"] = True
        self.plan["artifact"].update(path=str(self.staging), size=0)
        self.write_request()
        # The pipeline omits the default size field for metadata.
        current = json.loads((self.job / "volume-request.json").read_text())
        current["artifact"].pop("size")
        (self.job / "volume-request.json").write_text(json.dumps(current))
        with self.assertRaisesRegex(RuntimeError, "metadata is not ready"):
            backup.submit(self.file, self.plan)
        (self.staging / "backup-manifest.json").write_text("{}")
        (self.staging / ".staging.complete").touch()
        backup.submit(self.file, self.plan)
        self.assertEqual(1, self.run.call_count)

    def test_cancelled_pipeline_or_symlink_cannot_start_an_external_backup(self):
        (self.job / "volume-cancel").touch()
        with self.assertRaisesRegex(RuntimeError, "canceled"):
            backup.submit(self.file, self.plan)
        (self.job / "volume-cancel").unlink()
        target = self.staging / "other.raw"
        self.volume.rename(target)
        self.volume.symlink_to(target)
        with self.assertRaisesRegex(RuntimeError, "without symlinks"):
            backup.submit(self.file, self.plan)
        self.run.assert_not_called()


class NetBackupTransferHookTest(unittest.TestCase):
    def test_transfer_hooks_exit_before_creating_or_cleaning_parent_runtime_state(self):
        for script in ("netbackup-host-bpstart-notify.sh", "netbackup-host-bpend-notify.sh"):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as root:
                state = Path(root) / "state"
                result = subprocess.run(["bash", str(Path(__file__).with_name(script)), "prod-host-4", "mold-transfer", "host-4"],
                        env=dict(os.environ, STATE_ROOT=str(state), LOG_FILE=str(Path(root) / "hook.log")),
                        capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse(state.exists())


if __name__ == "__main__":
    unittest.main()
