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
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import ablestack_nas_source_guard as nas
import thirdparty_stopped_backup_guard as guard
import thirdparty_volume_backup as volume


class NasSourceGuardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "backup"
        self.job = self.root / "jobs" / "backup-1"
        self.job.mkdir(parents=True)
        self.start_root = self.root / "backup-start"
        self.directory = self.start_root / "source-guards" / "vm"
        self.directory.mkdir(parents=True)
        self.plan = {"version": 1, "jobId": "backup-1", "hostId": 4, "backupPath": "vm/point",
                     "protectStoppedSource": True, "nasSource": {"vmName": "vm", "engine": "RBD_DIFF",
                     "checkpointName": "point", "backupType": "FULL", "volumeCount": 2, "requestDigest": "a" * 64}}
        self.source = {"backupStartPlan": self.plan, "vmName": "vm", "backupPath": "vm/point", "backupType": "FULL",
                       "checkpointName": "point", "rbd": True, "diskPaths": ["rbd:pool/root", "rbd:pool/data"],
                       "timeout": 300, "script": [sys.executable, "-c", "pass"]}
        self.file = self.job / "nas-source-plan.json"
        self.file.write_text(json.dumps(self.source))
        self.proof_file = self.directory / "backup-1.json"
        self.proof_file.write_text(json.dumps({"version": 1, "plan": self.plan, "phase": "PROTECTED", "released": False}))
        self.receipt = self.start_root / "backup-1.json"
        self.receipt.write_text(json.dumps({"version": 1, "plan": self.plan, "state": "DISPATCHED"}))
        self.root_patch = patch.object(guard, "ROOT", self.start_root / "source-guards")
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.path_patch = patch.object(volume, "Path", side_effect=lambda value, *parts:
                self.start_root if str(value) == "/var/lib/ablestack/backup/backup-start" else Path(value, *parts))
        self.path_patch.start()
        self.addCleanup(self.path_patch.stop)

    def proof(self):
        return json.loads(self.proof_file.read_text())

    def begin(self):
        with patch.object(nas, "guest_is_off_or_absent", return_value=True):
            nas.begin(self.file, self.source)

    def qcow2(self):
        self.source["rbd"] = False
        self.source["dummyName"] = "DUMMY-NAS-backup-1"
        self.plan["nasSource"]["engine"] = "QCOW2"
        self.file.write_text(json.dumps(self.source))
        self.receipt.write_text(json.dumps({"version": 1, "plan": self.plan, "state": "DISPATCHED"}))
        self.proof_file.write_text(json.dumps({"version": 1, "plan": self.plan, "phase": "PROTECTED", "released": False}))

    def test_begin_claims_exact_receipt_once_and_retains_vm_start_protection(self):
        self.begin()
        self.assertEqual("STARTED", json.loads(self.receipt.read_text())["state"])
        original = (self.job / "volume-source-start.json").read_text()
        with self.assertRaisesRegex(RuntimeError, "already initialized"):
            self.begin()
        self.assertEqual(original, (self.job / "volume-source-start.json").read_text())
        self.assertFalse(self.proof()["released"])

    def test_delayed_worker_cannot_start_after_abort_fence(self):
        receipt = json.loads(self.receipt.read_text())
        receipt["state"] = "START_FAILED"
        self.receipt.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(RuntimeError, "permanently blocked"):
            self.begin()
        self.assertFalse((self.job / "volume-source-start.json").exists())

    def test_active_original_vm_prevents_source_io(self):
        with patch.object(nas, "guest_is_off_or_absent", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "source is active"):
                nas.begin(self.file, self.source)
        self.assertEqual("NOT_STARTED", json.loads((self.job / "volume-source-start.json").read_text())["state"])
        self.assertFalse(self.proof()["released"])

    def test_rbd_releases_only_after_every_snapshot_is_confirmed(self):
        self.begin()
        checked = []

        def run(args, timeout):
            self.assertFalse(self.proof()["released"])
            checked.append(args[-1])
            return '[{"name":"point"}]'

        with patch.object(nas, "run", side_effect=run), patch.object(nas, "guest_is_off_or_absent", return_value=True):
            nas.snapshots_complete(self.file, self.source)
        self.assertEqual(["pool/root", "pool/data"], checked)
        self.assertTrue(self.proof()["released"])
        self.assertEqual("SNAPSHOTS_COMPLETE", self.proof()["phase"])

    def test_missing_second_snapshot_retains_start_protection(self):
        self.begin()
        with patch.object(nas, "run", side_effect=['[{"name":"point"}]', "[]"]), \
                patch.object(nas, "guest_is_off_or_absent", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "snapshot is absent"):
                nas.snapshots_complete(self.file, self.source)
        self.assertFalse(self.proof()["released"])

    def test_qcow2_cannot_use_rbd_snapshot_phase_to_release(self):
        self.qcow2()
        self.begin()
        with self.assertRaisesRegex(RuntimeError, "QCOW2 cannot release"):
            nas.snapshots_complete(self.file, self.source)
        self.assertFalse(self.proof()["released"])

    def test_qcow2_destroy_ack_requires_confirmed_qemu_exit(self):
        self.qcow2()
        self.begin()
        with patch.object(nas, "no_source_controls"), patch.object(nas, "guest_is_off_or_absent", return_value=False), \
                patch.object(nas, "run"), patch.object(nas, "confirm_dummy_terminated", side_effect=RuntimeError("QEMU remains active")):
            with self.assertRaisesRegex(RuntimeError, "QEMU remains active"):
                nas.cleanup(self.file, self.source)
        self.assertFalse(self.proof()["released"])
        with patch.object(nas, "no_source_controls"), patch.object(nas, "guest_is_off_or_absent", return_value=True), \
                patch.object(nas, "confirm_dummy_terminated"):
            nas.cleanup(self.file, self.source)
        self.assertEqual("SOURCE_CLEANED", self.proof()["phase"])

    def test_failed_partial_rbd_cleanup_does_not_release(self):
        self.begin()
        with patch.object(nas, "no_source_controls"), patch.object(nas, "run", side_effect=[
                '[{"name":"point"}]', RuntimeError("snapshot remove failed")]):
            with self.assertRaisesRegex(RuntimeError, "remove failed"):
                nas.cleanup(self.file, self.source)
        self.assertFalse(self.proof()["released"])

    def test_successful_partial_rbd_cleanup_confirms_absence_before_release(self):
        self.begin()
        with patch.object(nas, "no_source_controls"), patch.object(nas, "run", side_effect=[
                '[{"name":"point"}]', "", "[]", "[]", "[]"]):
            nas.cleanup(self.file, self.source)
        self.assertEqual("SOURCE_CLEANED", self.proof()["phase"])

    def test_successful_script_without_snapshot_phase_is_not_accepted(self):
        with patch.object(nas, "guest_is_off_or_absent", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "completion is unconfirmed"):
                nas.run_backup(self.file, self.source)
        self.assertFalse(self.proof()["released"])

    def test_interrupted_script_retains_protection_until_reconciliation(self):
        child = Mock()
        child.wait.side_effect = InterruptedError("interrupted")
        with patch.object(nas, "guest_is_off_or_absent", return_value=True), patch.object(nas.subprocess, "Popen", return_value=child):
            with self.assertRaises(InterruptedError):
                nas.run_backup(self.file, self.source)
        child.terminate.assert_called_once()
        self.assertFalse(self.proof()["released"])

    def test_unknown_dummy_identity_is_rejected(self):
        self.qcow2()
        self.source["dummyName"] = "another-vm"
        self.file.write_text(json.dumps(self.source))
        with self.assertRaisesRegex(RuntimeError, "dummy identity differs"):
            nas.load(self.file)


class NasRbdPhaseOrderTest(unittest.TestCase):
    def execute(self, scenario):
        source = Path(__file__).with_name("ablestack_nasbackup.sh").read_text()
        functions = source.split("\nwhile [[ $# -gt 0 ]]; do", 1)[0].replace(
            'source "$(dirname "${BASH_SOURCE[0]}")/ablestack_backup_safety.sh"', 'source "$TEST_SAFETY_SCRIPT"')
        script = functions + r'''
trap - EXIT ERR
log() { :; }
VM=vm
BACKUP_DIR=vm/point
BACKUP_TYPE=FULL
CHECKPOINT_NAME=point
DISK_PATHS=rbd:pool/root,rbd:pool/data
BACKUP_FILES=root.raw,data.raw
logFile="$TEST_DIRECTORY/agent.log"
mount_operation() {
  mount_point="$TEST_DIRECTORY/repository"
  dest="$mount_point/$BACKUP_DIR"
  mkdir -p "$dest"
}
mark_backup_in_progress() { :; }
backup_domain_information() { :; }
cleanup_created_rbd_snapshots() { :; }
cleanup_parent_rbd_snapshot_after_success() { :; }
cleanup() { :; }
timeout() { shift; "$@"; }
rbd() {
  if [[ "$1 $2" == 'snap create' ]]; then
    echo "snapshot:$3" >> "$TEST_DIRECTORY/events"
    if [[ "$TEST_SCENARIO" == second-fails && "$3" == pool/data@point ]]; then return 1; fi
  fi
  return 0
}
nas_rbd_snapshots_complete() {
  local count
  count=$(awk '/^snapshot:/ {n++} END {print n+0}' "$TEST_DIRECTORY/events")
  [[ "$count" == 2 ]] || return 9
  [[ "$TEST_SCENARIO" != callback-fails ]] || return 8
  echo release >> "$TEST_DIRECTORY/events"
}
get_rbd_image_size_bytes() { echo 1024; }
run_rbd_export_with_progress() { echo export >> "$TEST_DIRECTORY/events"; touch "$2"; }
mark_backup_complete() { echo complete >> "$TEST_DIRECTORY/events"; }
stat() { echo 1; }
sync() { :; }
umount() { :; }
rmdir() { :; }
backup_rbd_volumes
'''
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                    env=dict(os.environ, TEST_DIRECTORY=directory, TEST_SCENARIO=scenario,
                                             TEST_SAFETY_SCRIPT=str(Path(__file__).with_name("ablestack_backup_safety.sh"))))
            events = Path(directory, "events").read_text().splitlines()
            return result, events

    def test_every_disk_snapshot_precedes_release_and_first_export(self):
        result, events = self.execute("success")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["snapshot:pool/root@point", "snapshot:pool/data@point", "release", "export", "export", "complete"], events)

    def test_second_snapshot_failure_never_releases_or_exports(self):
        result, events = self.execute("second-fails")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("release", events)
        self.assertNotIn("export", events)

    def test_unconfirmed_release_never_starts_export(self):
        result, events = self.execute("callback-fails")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("release", events)
        self.assertNotIn("export", events)


if __name__ == "__main__":
    unittest.main()
