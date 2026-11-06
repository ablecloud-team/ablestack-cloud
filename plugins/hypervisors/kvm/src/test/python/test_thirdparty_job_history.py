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
import concurrent.futures
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[6] / "scripts/vm/hypervisor/kvm"))
import thirdparty_job_history as history
import thirdparty_volume_backup as backup


class JobHistoryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        for name in ("backup-start", "staging-admission-closed", "jobs"):
            (self.root / name).mkdir()

    def prepare(self, job_id="backup-1", state="STARTED"):
        receipt = {"version": 1, "state": state, "plan": {"jobId": job_id}, "checkedAt": 1}
        (self.root / "backup-start" / (job_id + ".json")).write_text(json.dumps(receipt))
        (self.root / "backup-start" / (job_id + ".lock")).touch()
        (self.root / "staging-admission-closed" / (job_id + ".closed")).write_text("Staging cleanup completed")
        return receipt

    def test_140_backups_share_one_database_instead_of_420_individual_files(self):
        for number in range(140):
            self.prepare("backup-" + str(number))
        history.compact_retired(self.root)
        history.compact_retired(self.root)
        self.assertEqual(["job-history.sqlite"], sorted(str(path.relative_to(self.root))
                         for path in self.root.rglob("*") if path.is_file()))
        with sqlite3.connect(history.database(self.root)) as connection:
            self.assertEqual(140, connection.execute("SELECT count(*) FROM retired_jobs").fetchone()[0])
        self.assertEqual("STARTED", history.read(self.root, "backup-139")["startReceipt"]["state"])

    def test_active_or_cleanup_pending_records_are_not_compacted(self):
        self.prepare("active")
        (self.root / "jobs/active").mkdir()
        self.prepare("pending")
        (self.root / "jobs/pending.properties").touch()
        history.compact_retired(self.root)
        self.assertIsNone(history.read(self.root, "active"))
        self.assertTrue((self.root / "backup-start/pending.json").is_file())
        with self.assertRaisesRegex(RuntimeError, "Execution records"):
            history.compact(self.root, "active")

    def test_removed_fence_still_blocks_late_admission_and_is_not_recreated(self):
        self.prepare()
        history.compact(self.root, "backup-1")
        job = self.root / "jobs/backup-1"
        self.assertEqual("Staging cleanup completed", backup.admission_closed_reason(job))
        backup.close_admission(job, "late cleanup retry")
        self.assertFalse((self.root / "staging-admission-closed/backup-1.closed").exists())

    def test_commit_survives_an_interruption_before_legacy_files_are_removed(self):
        receipt = self.prepare()
        original = Path.unlink

        def fail(path, *args, **kwargs):
            if path == self.root / "backup-start/backup-1.json":
                raise OSError("interrupted unlink")
            return original(path, *args, **kwargs)

        with mock.patch.object(Path, "unlink", fail):
            with self.assertRaisesRegex(OSError, "interrupted"):
                history.compact(self.root, "backup-1")
        self.assertEqual(receipt, history.read(self.root, "backup-1")["startReceipt"])
        history.compact(self.root, "backup-1")
        self.assertFalse((self.root / "backup-start/backup-1.json").exists())
        self.assertEqual(receipt, history.read(self.root, "backup-1")["startReceipt"])

    def test_conflicting_history_never_replaces_a_retired_identity(self):
        self.prepare()
        original = history.compact(self.root, "backup-1")
        self.prepare(state="START_FAILED")
        with self.assertRaisesRegex(RuntimeError, "differs"):
            history.compact(self.root, "backup-1")
        self.assertEqual(original, history.read(self.root, "backup-1"))
        self.assertTrue((self.root / "backup-start/backup-1.json").exists())

    def test_concurrent_cleanup_keeps_every_retired_job(self):
        for number in range(20):
            self.prepare("backup-" + str(number))
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda number: history.compact(self.root, "backup-" + str(number)), range(20)))
        self.assertEqual(20, len(results))
        for number in range(20):
            self.assertIsNotNone(history.read(self.root, "backup-" + str(number)))

    def test_corrupt_index_does_not_remove_individual_records_or_allow_admission(self):
        self.prepare()
        history.database(self.root).write_text("corrupt index")
        with self.assertRaises(sqlite3.DatabaseError):
            history.compact(self.root, "backup-1")
        with self.assertRaises(sqlite3.DatabaseError):
            backup.admission_closed_reason(self.root / "jobs/backup-1")
        self.assertTrue((self.root / "backup-start/backup-1.json").exists())

    def test_truncated_index_cannot_silently_lose_retired_job_fences(self):
        self.prepare()
        history.database(self.root).touch()
        with self.assertRaises(sqlite3.DatabaseError):
            history.compact(self.root, "backup-1")
        self.assertTrue((self.root / "backup-start/backup-1.json").exists())

    def test_interrupted_initialization_keeps_individual_receipts_and_can_retry(self):
        self.prepare()
        with mock.patch.object(history.os, "link", side_effect=OSError("interrupted publication")):
            with self.assertRaises(OSError):
                history.compact(self.root, "backup-1")
        self.assertFalse(history.database(self.root).exists())
        self.assertTrue((self.root / "backup-start/backup-1.json").exists())
        history.compact(self.root, "backup-1")
        self.assertIsNotNone(history.read(self.root, "backup-1"))

    def test_missing_history_read_does_not_create_files(self):
        self.assertIsNone(history.read(self.root, "missing"))
        self.assertFalse(history.database(self.root).exists())

    def test_late_worker_cannot_claim_an_archived_dispatched_job(self):
        self.prepare(state="DISPATCHED")
        history.compact(self.root, "backup-1")
        job = self.root / "jobs/backup-1"
        job.mkdir()
        manifest = {"backupUuid": "backup-1"}
        plan = {"version": 1, "jobId": "backup-1", "manifest": manifest, "backupPath": "/staging/backup-1"}
        file = job / "volume-plan.json"
        file.write_text(json.dumps({"manifest": manifest, "backupPath": "/staging/backup-1", "backupStartPlan": plan}))
        with self.assertRaisesRegex(RuntimeError, "Retired backup"):
            backup.claim_backup_start(file)
        self.assertFalse((self.root / "backup-start/backup-1.lock").exists())


if __name__ == "__main__":
    unittest.main()
