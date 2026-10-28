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

"""Veeam external restore outcomes and warning metadata validation regressions."""

import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


LIBRARY = Path(__file__).with_name("veeam") / "mold-backup.lib.sh"
SESSION = "12345678-1234-1234-1234-123456789012"
POINT = "87654321-1234-1234-1234-123456789012"


class VeeamRestoreWatcherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / "i-2-218-VM/2026.10.20.10.00.00.000"
        self.root.mkdir(parents=True)
        self.expected = dict(matched=True, tracked=False, restoreallowed=True, provider="ablestack-veeam", artifacttype="METADATA",
                             id="backup-test", vmname=self.root.parent.name, virtualmachineid="vm-test", timestamp=self.root.name,
                             metadatapath=str(self.root))
        self.proof = dict(jobId=SESSION, result="Warning", startedAt=time.time() - 30, endedAt=time.time() - 1,
                          detailsAvailable=True, details=[dict(status="Warning", message="Cannot set file permissions on " + str(self.root / "domain-config.xml"))])
        self.manifest = dict(version=1, provider="ablestack-veeam", backupUuid="backup-test", vmName=self.root.parent.name,
                             timestamp=self.root.name, complete=True, volumes=[dict(uuid="root-volume", engine="QCOW2", chain=[dict(
                                 backupUuid="backup-test", path=str(self.root / "root.root-volume.qcow2"), completed=True,
                                 externalId="volume-restore-point", jobId="volume-job")])])
        self.write_metadata()

    def write_metadata(self):
        (self.root / "backup-manifest.json").write_text(json.dumps(self.manifest))
        (self.root / "domain-config.xml").write_text('<domain><name>i-2-218-VM</name><uuid>vm-test</uuid></domain>')
        (self.root / ".staging.complete").write_text("volume_pipeline=true\nbackup_uuid=backup-test\n")

    def watch(self, result="Warning", end=None, sid=SESSION, point=POINT, proof=None, direct=False, twice=False, parent=""):
        line = "|".join((sid, "", str(int(time.time())) if end is None else end, result, "metadata", "ABLESTACK-backup-test-metadata", point, "", parent))
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
mold_backup_notify_log() { echo "$*" >&2; }
mold_backup_restore_preflight() { :; }
mold_backup_local_kvm_name() { echo host-test; }
mold_backup_initialize_restore_watch_baseline() { :; }
mold_backup_query_veeam_restores() { echo "$TEST_SESSION"; }
mold_backup_query_local_host_flr() { :; }
mold_backup_query_veeam_restore_proof() { echo "$TEST_PROOF"; }
mold_backup_restore_session_seen() { [[ -f "$TEST_SEEN" ]] && grep -qxF "$1" "$TEST_SEEN"; }
mold_backup_restore_session_mark_seen() { printf '%s\n' "$1" >> "$TEST_SEEN"; }
mold_backup_emit_restore_event() { echo "EVENT $*" >&2; }
mold_backup_cmk_run() {
  echo "API $1" >> "$TEST_CALLS"
  if [[ "$1" == restoreBackupArtifact ]]; then
    printf '%s\n' "$@" > "$TEST_ACCEPTED"
  fi
  if [[ "$1" == resolveBackupArtifact ]]; then echo "$TEST_RESOLUTION";
  elif [[ "$1" == rejectBackupArtifactRestore ]]; then echo '{"rejectbackupartifactrestoreresponse":{"success":true}}';
  elif [[ "$1" == restoreBackupArtifact ]]; then echo '{"restorebackupartifactresponse":{"jobid":"async-test"}}';
  else echo "Unexpected API call: $1" >&2; return 1; fi
}
if [[ "$TEST_DIRECT" == true ]]; then
  mold_backup_handle_veeam_restore_session test-job i-2-218-VM "$TEST_SID" 'result=Success' true || true
else
  mold_backup_watch_veeam_restores test-job 60 true
  if [[ "$TEST_TWICE" == true ]]; then mold_backup_watch_veeam_restores test-job 60 true; fi
fi
'''
        env = dict(os.environ, TEST_LIBRARY=str(LIBRARY), TEST_SESSION=line, TEST_PROOF=json.dumps(self.proof if proof is None else proof),
                   TEST_RESOLUTION=json.dumps(dict(resolvebackupartifactresponse=dict(backupartifact=self.expected))),
                   TEST_SEEN=str(self.base / "seen"), TEST_CALLS=str(self.base / "calls"), TEST_ACCEPTED=str(self.base / "accepted"),
                   TEST_SID=sid, TEST_DIRECT=str(direct).lower(), TEST_TWICE=str(twice).lower(),
                   VM_TARGETS="", VM_INCLUDE="", BACKUP_MODE="host")
        output = subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env, timeout=20)
        self.assertEqual(0, output.returncode, output.stdout + output.stderr)
        return output.stderr + ((self.base / "calls").read_text() if (self.base / "calls").exists() else "")

    def assert_blocked(self, **kwargs):
        output = self.watch(**kwargs)
        self.assertNotIn("API restoreBackupArtifact", output)
        self.assertFalse((self.base / "seen").exists(), output)
        return output

    def test_success_submits_once_and_is_deduplicated(self):
        output = self.watch(result="Success", twice=True)
        self.assertEqual(1, output.count("API restoreBackupArtifact"))

    def test_completed_copy_tasks_in_one_open_browser_are_independently_deduplicated(self):
        for index, task_id in enumerate((POINT, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), 1):
            proof = dict(self.proof, jobId=task_id, parentSessionId=SESSION, result="Success")
            output = self.watch(result="Success", sid=task_id, parent=SESSION, proof=proof, twice=True)
            self.assertEqual(index, output.count("API restoreBackupArtifact"))

    def test_running_vm_rejection_uses_completed_copy_task_without_stopping_vm(self):
        self.expected.update(restoreallowed=False, reason="The original VM must be Stopped before restoring metadata in the external UI")
        proof = dict(self.proof, jobId=POINT, parentSessionId=SESSION, result="Success")
        output = self.watch(result="Success", sid=POINT, parent=SESSION, proof=proof, twice=True)
        self.assertEqual(1, output.count("API rejectBackupArtifactRestore"))
        self.assertNotIn("API restoreBackupArtifact", output)
        self.assertNotIn("stopVirtualMachine", output)
        self.assertIn("mold.restore.skipped.volume-pipeline", output)

    def test_copy_task_proof_must_belong_to_the_exact_browser(self):
        proof = dict(self.proof, jobId=POINT, parentSessionId=POINT, result="Success")
        self.assert_blocked(result="Success", sid=POINT, parent=SESSION, proof=proof)

    def test_pre_upgrade_parent_receipt_prevents_replaying_its_copy_task(self):
        (self.base / "seen").write_text(SESSION + "\n")
        proof = dict(self.proof, jobId=POINT, parentSessionId=SESSION, result="Success")
        output = self.watch(result="Success", sid=POINT, parent=SESSION, proof=proof)
        self.assertNotIn("API restoreBackupArtifact", output)

    def test_accepted_restore_includes_verified_completion_proof_for_failure_cleanup(self):
        self.assertIn("API restoreBackupArtifact", self.watch(result="Success"))
        params = dict(line.split("=", 1) for line in (self.base / "accepted").read_text().splitlines()[1:])
        proof = json.loads(params["proof"])
        self.assertEqual(SESSION, params["jobid"])
        self.assertEqual(POINT, params["externalid"])
        self.assertEqual("host-test", proof["hostName"])
        self.assertEqual(int(self.proof["startedAt"] * 1000), proof["startedAt"])
        self.assertEqual(int(self.proof["endedAt"] * 1000), proof["endedAt"])

    def test_success_without_exact_completion_proof_is_retried(self):
        for field, value in (("jobId", POINT), ("endedAt", 0), ("result", "Failed")):
            with self.subTest(field=field):
                self.assert_blocked(result="Success", proof=dict(self.proof, **{field: value}))

    def test_verified_attribute_warning_starts_volume_restore(self):
        output = self.watch()
        self.assertIn("API restoreBackupArtifact", output)
        self.assertIn("VERIFIED", output)
        self.assertTrue((self.base / "seen").exists())

    def test_failed_permission_setting_is_allowed_only_as_a_warning_with_valid_metadata(self):
        proof = dict(self.proof, details=[dict(status="Warning", message="Failed to restore file permissions on domain-config.xml")])
        self.assertIn("API restoreBackupArtifact", self.watch(proof=proof))

    def test_warning_offline_raw_full_metadata_accepts_mold_vm_descriptor(self):
        self.manifest["backupType"] = "FULL"
        self.manifest["volumes"][0]["engine"] = "OFFLINE_FILE"
        self.manifest["volumes"][0]["chain"][0]["path"] = str(self.root / "root.raw")
        self.manifest["offlineGuard"] = {"version": 1, "operation": "BACKUP", "jobId": "backup-test", "vmUuid": "vm-test"}
        self.write_metadata()
        self.assertIn("API restoreBackupArtifact", self.watch())

    def test_offline_raw_incremental_metadata_is_rejected(self):
        self.manifest["backupType"] = "INCREMENTAL"
        self.manifest["volumes"][0]["engine"] = "OFFLINE_FILE"
        self.manifest["offlineGuard"] = {"version": 1, "operation": "BACKUP", "jobId": "backup-test", "vmUuid": "vm-test"}
        self.write_metadata()
        self.assertIn("independent Full", self.assert_blocked())

    def test_warning_rbd_metadata_does_not_require_disk_payloads(self):
        self.manifest["volumes"][0]["engine"] = "RBD_DIFF"
        self.manifest["volumes"][0]["chain"][0]["path"] = str(self.root / "root.root-volume.raw")
        self.write_metadata()
        self.assertIn("API restoreBackupArtifact", self.watch())

    def test_warning_metadata_uses_current_destination_and_original_catalog_paths(self):
        moved = self.base / "new-stage/ablestack-veeam" / self.root.parent.name / self.root.name
        moved.parent.mkdir(parents=True)
        self.root.rename(moved)
        self.expected["metadatarestorepath"] = str(moved)
        self.assertIn("API restoreBackupArtifact", self.watch())

    def test_warning_metadata_in_two_fresh_destinations_is_ambiguous(self):
        moved = self.base / "new-stage/ablestack-veeam" / self.root.parent.name / self.root.name
        moved.mkdir(parents=True)
        for source in self.root.iterdir():
            (moved / source.name).write_bytes(source.read_bytes())
        self.expected["metadatarestorepath"] = str(moved)
        self.assertIn("ambiguous", self.assert_blocked())

    def test_failed_canceled_unknown_and_empty_results_never_trigger(self):
        for result in ("Failed", "Canceled", "Cancelled", "None", "", "Unknown"):
            with self.subTest(result=result):
                self.assert_blocked(result=result)

    def test_missing_and_invalid_end_time_never_trigger(self):
        for end in ("", "-2208960000", "0", "n/a"):
            with self.subTest(end=end):
                self.assert_blocked(result="Success", end=end)

    def test_synthetic_file_drop_success_does_not_trigger_restore(self):
        self.assert_blocked(result="Success", sid="local-flr-i-2-218-VM-1792458000")

    def test_warning_without_detailed_log_is_blocked(self):
        for details in ([], ["Restore completed with warnings"], ["Unknown restore error"]):
            with self.subTest(details=details):
                self.assert_blocked(proof=dict(self.proof, details=[dict(status="Warning", message=line) for line in details]))
        self.assert_blocked(proof=dict(self.proof, detailsAvailable=False))
        self.assert_blocked(proof={})

    def test_data_errors_are_not_hidden_by_an_attribute_warning(self):
        for detail in ("cannot set permissions; checksum mismatch", "cannot set permissions on root.qcow2",
                       "failed to read domain-config.xml; cannot set permissions", "partially restored 1 files; chmod failed",
                       "failed to restore domain-config.xml; cannot set permissions"):
            with self.subTest(detail=detail):
                self.assert_blocked(proof=dict(self.proof, details=[dict(status="Warning", message=detail)]))

    def test_failed_log_records_cannot_be_accepted_as_attribute_warnings(self):
        for severity in ("Failed", "Error", "Unknown", ""):
            with self.subTest(severity=severity):
                self.assert_blocked(proof=dict(self.proof, details=[dict(status=severity, message="Cannot set file permissions")]))

    def test_exact_remote_session_must_still_have_terminal_warning(self):
        for field, value in (("jobId", POINT), ("result", "Failed"), ("result", "None"), ("endedAt", 0), ("startedAt", 0)):
            with self.subTest(field=field, value=value):
                self.assert_blocked(proof=dict(self.proof, **{field: value}))

    def test_missing_or_empty_required_metadata_blocks_warning(self):
        for name in ("backup-manifest.json", "domain-config.xml", ".staging.complete"):
            with self.subTest(name=name):
                path = self.root / name
                value = path.read_bytes()
                path.unlink()
                self.assert_blocked()
                path.write_bytes(b"")
                self.assert_blocked()
                path.write_bytes(value)

    def test_stale_metadata_cannot_be_reused_for_warning(self):
        self.assert_blocked(proof=dict(self.proof, startedAt=time.time() + 60, endedAt=time.time() + 61))

    def test_mismatched_manifest_identity_is_blocked(self):
        for field, value in (("backupUuid", "other"), ("vmName", "other"), ("timestamp", "other"), ("complete", False), ("provider", "ablestack-netbackup")):
            original = copy.deepcopy(self.manifest)
            with self.subTest(field=field):
                self.manifest[field] = value
                self.write_metadata()
                self.assert_blocked()
            self.manifest = original
        self.write_metadata()

    def test_unconfirmed_volume_reference_is_blocked(self):
        self.manifest["volumes"][0]["chain"][0]["completed"] = False
        self.write_metadata()
        self.assert_blocked()

    def test_xml_and_completion_marker_must_match_selected_vm_and_backup(self):
        (self.root / "domain-config.xml").write_text('<domain><name>i-2-218-VM</name><uuid>other-vm</uuid></domain>')
        self.assert_blocked()
        self.write_metadata()
        (self.root / ".staging.complete").write_text("volume_pipeline=true\nbackup_uuid=other\n")
        self.assert_blocked()

    def test_metadata_symlinks_are_not_accepted(self):
        path = self.root / "domain-config.xml"
        target = self.base / "domain.xml"
        path.rename(target)
        path.symlink_to(target)
        self.assert_blocked()

    def test_blocked_warning_is_rechecked_when_required_files_become_available(self):
        (self.root / "domain-config.xml").unlink()
        self.assert_blocked()
        self.write_metadata()
        self.assertIn("API restoreBackupArtifact", self.watch())

    def test_already_tracked_restore_never_submits_another_vm_restore(self):
        self.expected["tracked"] = True
        output = self.watch()
        self.assertNotIn("API restoreBackupArtifact", output)
        self.assertIn("skipped.volume-pipeline", output)

    def test_warning_cannot_fall_back_to_legacy_restore(self):
        self.expected["matched"] = False
        self.assert_blocked()

    def test_running_vm_is_skipped_without_retrying_the_same_restore_session(self):
        self.expected["restoreallowed"] = False
        self.expected["reason"] = "The original VM must be Stopped before restoring metadata in the external UI"
        output = self.watch(result="Success", twice=True)
        self.assertEqual(1, output.count("API resolveBackupArtifact"))
        self.assertEqual(1, output.count("API rejectBackupArtifactRestore"))
        self.assertNotIn("API restoreBackupArtifact", output)
        self.assertIn(self.expected["reason"], output)
        self.assertTrue((self.base / "seen").exists())

    def test_rejected_restore_without_confirmed_completion_remains_retryable(self):
        self.expected["restoreallowed"] = False
        self.expected["reason"] = "VM must be Stopped"
        output = self.assert_blocked(result="Success", proof=dict(self.proof, endedAt=0))
        self.assertNotIn("API rejectBackupArtifactRestore", output)

    def test_direct_callback_requeries_result_instead_of_trusting_success_text(self):
        self.assert_blocked(result="Failed", direct=True)
        self.assertIn("API restoreBackupArtifact", self.watch(result="Success", direct=True))

    def test_direct_local_file_callback_cannot_invent_success(self):
        self.assert_blocked(result="Success", sid="local-flr-i-2-218-VM-1792458000", direct=True)


if __name__ == "__main__":
    unittest.main()
