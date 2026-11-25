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

"""Exercise NAS checkpoint preparation without mounting storage or calling libvirt."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class NasCheckpointPreparationTest(unittest.TestCase):
    def run_checkpoint_chain(self, parents, known=(), selected="stopped", extra=()):
        source = Path(__file__).with_name("ablestack_nasbackup.sh").read_text()
        functions = source.split("\nwhile [[ $# -gt 0 ]]; do", 1)[0].replace(
            'source "$(dirname "${BASH_SOURCE[0]}")/ablestack_backup_safety.sh"',
            'source "$TEST_SAFETY_SCRIPT"')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for point, name, parent in [(name, name, parent) for name, parent in parents.items()] + list(extra):
                path = root / "vm" / point / "checkpoints" / (name + ".xml")
                path.parent.mkdir(parents=True, exist_ok=True)
                ancestor = "<parent><name>" + parent + "</name></parent>" if parent else ""
                path.write_text("<domaincheckpoint><name>" + name + "</name>" + ancestor + "</domaincheckpoint>")
            (root / "registered").write_text("".join(name + "\n" for name in known))
            script = functions + r'''
trap - EXIT
trap - ERR
logFile="$TEST_JOB_DIR/agent.log"
PARENT_CHECKPOINT_NAME="$TEST_SELECTED"
virsh() {
  local previous="" name="" file="" argument
  for argument in "$@"; do
    [[ "$previous" != --checkpointname ]] || name="$argument"
    [[ "$previous" != --xmlfile ]] || file="$argument"
    previous="$argument"
  done
  case "$*" in
    *checkpoint-info*) grep -Fxq -- "$name" "$TEST_JOB_DIR/registered" ;;
    *checkpoint-list*) cat "$TEST_JOB_DIR/registered" ;;
    *checkpoint-create*)
      local parsed parent
      parsed=$(python3 -c 'import sys,xml.etree.ElementTree as E;r=E.parse(sys.argv[1]).getroot();print(r.findtext("name")+"\t"+(r.findtext("parent/name") or ""))' "$file")
      IFS=$'\t' read -r name parent <<< "$parsed"
      if [[ -n "$parent" ]] && ! grep -Fxq -- "$parent" "$TEST_JOB_DIR/registered"; then
        echo "error: parent $parent for moment $name not found" >&2
        return 1
      fi
      printf '%s\n' "$name" >> "$TEST_JOB_DIR/registered"
      printf '%s\n' "$name" >> "$TEST_JOB_DIR/created"
      ;;
  esac
}
redefine_checkpoint_if_needed vm "$TEST_JOB_DIR/vm/$TEST_SELECTED/checkpoints/$TEST_SELECTED.xml"
'''
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                    env=dict(os.environ, TEST_JOB_DIR=directory, TEST_SELECTED=selected,
                                             TEST_SAFETY_SCRIPT=str(Path(__file__).with_name("ablestack_backup_safety.sh"))))
            created = (root / "created").read_text().splitlines() if (root / "created").exists() else []
            return result, created

    def test_running_vm_restores_stopped_checkpoint_ancestors_from_earlier_backup_directories(self):
        result, created = self.run_checkpoint_chain({"full": None, "running": "full", "stopped": "running"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["full", "running", "stopped"], created)

    def test_registered_ancestor_does_not_require_its_old_xml(self):
        result, created = self.run_checkpoint_chain({"stopped": "running"}, known=("running",))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["stopped"], created)

    def test_missing_ancestor_fails_before_partial_registration(self):
        result, created = self.run_checkpoint_chain({"running": "missing", "stopped": "running"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing parent checkpoint XML missing", result.stdout)
        self.assertEqual([], created)

    def test_parent_cycle_fails_before_registration(self):
        result, created = self.run_checkpoint_chain({"running": "stopped", "stopped": "running"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Checkpoint parent cycle", result.stdout)
        self.assertEqual([], created)

    def test_conflicting_ancestor_copies_are_rejected(self):
        result, created = self.run_checkpoint_chain({"full": None, "running": "full", "stopped": "running"},
                                                  extra=(("conflicting", "running", "another-full"),))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Conflicting parent checkpoint XML running", result.stdout)
        self.assertEqual([], created)

    def run_backup(self, checkpoint_state, cleanup_status=0):
        source = Path(__file__).with_name("ablestack_nasbackup.sh").read_text()
        functions = source.split("\nwhile [[ $# -gt 0 ]]; do", 1)[0].replace(
            'source "$(dirname "${BASH_SOURCE[0]}")/ablestack_backup_safety.sh"',
            'source "$TEST_SAFETY_SCRIPT"')
        with tempfile.TemporaryDirectory() as directory:
            script = functions + r"""
trap - EXIT
trap - ERR
logFile="$TEST_JOB_DIR/agent.log"
VM=vm-1
BACKUP_TYPE=INCREMENTAL
CHECKPOINT_NAME=child-checkpoint
PARENT_CHECKPOINT_NAME=parent-checkpoint
PARENT_CHECKPOINT_PATH=parent/checkpoints/parent-checkpoint.xml
mount_point="$TEST_JOB_DIR/mount"
dest="$mount_point/child"
mkdir -p "$mount_point/parent/checkpoints"
printf '<domaincheckpoint><name>parent-checkpoint</name></domaincheckpoint>' > "$mount_point/$PARENT_CHECKPOINT_PATH"
check_backup_sources() { :; }
mount_operation() { :; }
mark_backup_in_progress() { :; }
parent_qcow2_bitmap_exists_on_all_disks() { return 0; }
cleanup() {
  touch "$TEST_JOB_DIR/cleanup-called"
  [[ "$TEST_CLEANUP_STATUS" == 0 ]] || exit "$TEST_CLEANUP_STATUS"
  rmdir "$dest/checkpoints" "$dest"
}
virsh() {
  case "$*" in
    *checkpoint-info*) [[ "$TEST_CHECKPOINT_STATE" == existing ]] ;;
    *checkpoint-create*)
      touch "$TEST_JOB_DIR/redefine-called"
      if [[ "$TEST_CHECKPOINT_STATE" == rejected ]]; then
        echo 'error: checkpoint parent is missing' >&2
        return 1
      fi ;;
    *domblklist*) printf ' file disk vda /primary/root.qcow2\n' ;;
    *backup-begin*) touch "$TEST_JOB_DIR/unexpected-backup-start" ;;
  esac
}
ablestack_freeze_guest() {
  # Stop after XML preparation, before any backup or guest operation.
  touch "$TEST_JOB_DIR/xml-prepared"
  cp "$dest/backup.xml" "$TEST_JOB_DIR/prepared-backup.xml"
  cp "$dest/checkpoint.xml" "$TEST_JOB_DIR/prepared-checkpoint.xml"
  exit 42
}
backup_running_vm
"""
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                    env=dict(os.environ, TEST_JOB_DIR=directory,
                                             TEST_CHECKPOINT_STATE=checkpoint_state,
                                             TEST_CLEANUP_STATUS=str(cleanup_status),
                                             TEST_SAFETY_SCRIPT=str(Path(__file__).with_name("ablestack_backup_safety.sh"))))
            files = {path.name: path.read_text() for path in Path(directory).iterdir() if path.is_file()}
            return result, files

    def test_failed_redefinition_stops_before_writing_to_cleaned_directory(self):
        result, files = self.run_backup("rejected")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("Failed to redefine checkpoint parent-checkpoint", result.stdout)
        self.assertIn("error: checkpoint parent is missing", result.stdout)
        self.assertIn("error: checkpoint parent is missing", files["agent.log"])
        self.assertIn("cleanup-called", files)
        self.assertNotIn("xml-prepared", files)
        self.assertNotIn("unexpected-backup-start", files)
        self.assertNotIn("No such file or directory", result.stderr)

    def test_successful_redefinition_reaches_incremental_xml_preparation(self):
        result, files = self.run_backup("accepted")
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertIn("redefine-called", files)
        self.assertIn("<incremental>parent-checkpoint</incremental>", files["prepared-backup.xml"])
        self.assertIn("child-checkpoint", files["prepared-checkpoint.xml"])
        self.assertNotIn("cleanup-called", files)

    def test_existing_checkpoint_does_not_need_redefinition(self):
        result, files = self.run_backup("existing")
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertIn("xml-prepared", files)
        self.assertNotIn("redefine-called", files)

    def test_cleanup_failure_preserves_its_distinct_exit_status(self):
        result, files = self.run_backup("rejected", cleanup_status=20)
        self.assertEqual(20, result.returncode, result.stderr)
        self.assertIn("cleanup-called", files)
        self.assertNotIn("xml-prepared", files)


if __name__ == "__main__":
    unittest.main()
