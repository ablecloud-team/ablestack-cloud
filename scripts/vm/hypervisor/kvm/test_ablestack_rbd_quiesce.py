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

"""Exercise the NAS RBD script with real quiesce/exit logic and fake IO."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS = ("ablestack_nasbackup.sh",)


class RbdQuiesceScriptTest(unittest.TestCase):
    def exercise(self, name, scenario="success", quiesce=True):
        source = Path(__file__).with_name(name).read_text()
        functions = source.split("\nwhile [[ $# -gt 0 ]]; do", 1)[0].replace(
            'source "$(dirname "${BASH_SOURCE[0]}")/ablestack_backup_safety.sh"', 'source "$TEST_SAFETY_SCRIPT"')
        script = functions + r'''
log() { :; }
logFile="$TEST_DIRECTORY/agent.log"
VM=vm
QUIESCE="$TEST_QUIESCE"
BACKUP_DIR=vm/point
BACKUP_TYPE=FULL
CHECKPOINT_NAME=point
DISK_PATHS=rbd:pool/root,rbd:pool/data
BACKUP_FILES=root.raw,data.raw
ABLESTACK_BACKUP_JOB_DIR="$TEST_DIRECTORY/job"
mount_point="$TEST_DIRECTORY/repository"
dest="$mount_point/$BACKUP_DIR"
mkdir -p "$ABLESTACK_BACKUP_JOB_DIR" "$dest"
[[ "$TEST_SCENARIO" != foreign ]] || touch "$TEST_DIRECTORY/frozen"
mount_operation() { :; }
mark_backup_in_progress() { :; }
mark_staging_in_progress() { :; }
backup_domain_information() { :; }
cleanup_parent_rbd_snapshot_after_success() { :; }
write_rbd_backup_metadata() { echo "metadata:$BACKUP_GUEST_QUIESCE_STATE" >> "$TEST_DIRECTORY/events"; }
write_rbd_checkpoint_metadata() { :; }
cleanup_created_rbd_snapshots() {
  [[ ! -f "$TEST_DIRECTORY/frozen" ]] || { echo cleanup-while-frozen >> "$TEST_DIRECTORY/events"; return 9; }
  echo snapshot-cleanup >> "$TEST_DIRECTORY/events"
}
cleanup() { ablestack_backup_cleanup_safe || exit 20; echo cleanup >> "$TEST_DIRECTORY/events"; }
virsh() {
  case "$*" in
    *domstate*) if [[ "$TEST_SCENARIO" == stopped ]]; then echo 'shut off'; else echo running; fi ;;
    *guest-fsfreeze-status*)
      [[ "$TEST_SCENARIO" != no-agent ]] || return 124
      if [[ -f "$TEST_DIRECTORY/frozen" ]]; then echo '{"return":"frozen"}'; else echo '{"return":"thawed"}'; fi ;;
    *guest-fsfreeze-freeze*)
      [[ -f "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending" ]] || return 9
      echo freeze >> "$TEST_DIRECTORY/events"
      touch "$TEST_DIRECTORY/frozen"
      [[ "$TEST_SCENARIO" != freeze-timeout ]] || return 124
      if [[ "$TEST_SCENARIO" == freeze-json-error ]]; then echo '{"error":{"class":"GenericError"}}'; else echo '{"return":2}'; fi ;;
    *guest-fsfreeze-thaw*)
      echo thaw >> "$TEST_DIRECTORY/events"
      [[ "$TEST_SCENARIO" != thaw-stuck ]] || { echo '{"return":2}'; return 0; }
      rm -f "$TEST_DIRECTORY/frozen"
      [[ "$TEST_SCENARIO" != thaw-response-lost ]] || return 124
      echo '{"return":2}' ;;
    *) return 9 ;;
  esac
}
export_payload() {
  [[ ! -f "$TEST_DIRECTORY/frozen" ]] || return 9
  echo export >> "$TEST_DIRECTORY/events"
  touch "$1"
}
timeout() { if [[ "$1" == -k ]]; then shift 2; fi; shift; "$@"; }
rbd() {
  case "$1 $2" in
    'info '*) echo "preflight:$2" >> "$TEST_DIRECTORY/events" ;;
    'snap create')
      echo "snapshot:$3" >> "$TEST_DIRECTORY/events"
      if [[ "$TEST_SCENARIO" == term ]]; then kill -TERM $$; fi
      if [[ "$TEST_SCENARIO" == second-fails && "$3" == pool/data@point ]]; then return 1; fi
      if [[ "$TEST_SCENARIO" == auto-thaw && "$3" == pool/data@point ]]; then rm -f "$TEST_DIRECTORY/frozen"; fi ;;
    'export '*) export_payload "${@: -1}" ;;
    *) return 9 ;;
  esac
}
nas_rbd_snapshots_complete() { echo release >> "$TEST_DIRECTORY/events"; }
get_rbd_image_size_bytes() { echo 1024; }
run_rbd_export_with_progress() { export_payload "$2"; }
mark_backup_complete() { echo complete >> "$TEST_DIRECTORY/events"; }
mark_staging_complete() { echo complete >> "$TEST_DIRECTORY/events"; }
stat() { echo 1; }
sync() { :; }
umount() { :; }
rmdir() { :; }
backup_rbd_volumes
echo "done:$BACKUP_GUEST_QUIESCE_STATE" >> "$TEST_DIRECTORY/events"
'''
        with tempfile.TemporaryDirectory() as directory:
            events_file = Path(directory, "events")
            events_file.touch()
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                env=dict(os.environ, TEST_DIRECTORY=directory, TEST_SCENARIO=scenario,
                         TEST_QUIESCE=str(quiesce).lower(), TEST_SAFETY_SCRIPT=str(Path(__file__).with_name("ablestack_backup_safety.sh"))))
            return result, events_file.read_text().splitlines(), Path(directory, "job/guest-freeze.pending").exists()

    def test_all_sources_preflight_before_freeze_and_all_snapshots_before_thaw_and_export(self):
        for name in SCRIPTS:
            with self.subTest(provider=name):
                result, events, pending = self.exercise(name)
                self.assertEqual(0, result.returncode, result.stderr + str(events))
                self.assertEqual(["preflight:pool/root", "preflight:pool/data", "freeze",
                                  "snapshot:pool/root@point", "snapshot:pool/data@point", "thaw"], events[:6])
                self.assertLess(events.index("thaw"), events.index("export"))
                self.assertEqual(2, events.count("export"))
                self.assertEqual("done:VERIFIED", events[-1])
                self.assertFalse(pending)

    def test_disabled_option_and_stopped_vm_skip_freeze(self):
        for name in SCRIPTS:
            for scenario, enabled, expected in (("no-agent", False, "NOT_REQUESTED"), ("stopped", True, "NOT_APPLICABLE")):
                with self.subTest(provider=name, scenario=scenario):
                    result, events, pending = self.exercise(name, scenario, enabled)
                    self.assertEqual(0, result.returncode, result.stderr + str(events))
                    self.assertNotIn("freeze", events)
                    self.assertNotIn("thaw", events)
                    self.assertEqual("done:" + expected, events[-1])
                    self.assertFalse(pending)

    def test_requested_freeze_failure_stops_before_snapshot(self):
        for name in SCRIPTS:
            for scenario in ("no-agent", "freeze-timeout", "freeze-json-error"):
                with self.subTest(provider=name, scenario=scenario):
                    result, events, pending = self.exercise(name, scenario)
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse(any(event.startswith("snapshot:") for event in events))
                    self.assertNotIn("export", events)
                    self.assertNotIn("cleanup-while-frozen", events)
                    self.assertFalse(pending)
                    if scenario != "no-agent":
                        self.assertIn("thaw", events)

    def test_foreign_freeze_is_never_thawed_or_used(self):
        for name in SCRIPTS:
            with self.subTest(provider=name):
                result, events, pending = self.exercise(name, "foreign")
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("freeze", events)
                self.assertNotIn("thaw", events)
                self.assertNotIn("export", events)
                self.assertFalse(pending)

    def test_snapshot_error_term_or_expired_freeze_never_exports(self):
        for name in SCRIPTS:
            for scenario in ("second-fails", "term", "auto-thaw"):
                with self.subTest(provider=name, scenario=scenario):
                    result, events, pending = self.exercise(name, scenario)
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn("export", events)
                    self.assertNotIn("cleanup-while-frozen", events)
                    self.assertFalse(pending)
                    if scenario != "auto-thaw":
                        self.assertIn("thaw", events)
                    if scenario == "term":
                        self.assertEqual(143, result.returncode)

    def test_unconfirmed_thaw_retains_receipt_and_prevents_export_and_cleanup(self):
        for name in SCRIPTS:
            with self.subTest(provider=name):
                result, events, pending = self.exercise(name, "thaw-stuck")
                self.assertEqual(20, result.returncode)
                self.assertIn("thaw", events)
                self.assertTrue(pending)
                self.assertNotIn("export", events)
                self.assertNotIn("snapshot-cleanup", events)

    def test_lost_thaw_response_with_confirmed_thawed_status_can_complete(self):
        for name in SCRIPTS:
            with self.subTest(provider=name):
                result, events, pending = self.exercise(name, "thaw-response-lost")
                self.assertEqual(0, result.returncode, result.stderr + str(events))
                self.assertEqual(1, events.count("thaw"))
                self.assertEqual("done:VERIFIED", events[-1])
                self.assertFalse(pending)


if __name__ == "__main__":
    unittest.main()
