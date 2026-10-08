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

"""Regression tests for backup polling, uncertain abort and owned freeze cleanup."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import thirdparty_volume_backup as volume


class ShellSafetyTest(unittest.TestCase):
    def run_shell(self, body):
        with tempfile.TemporaryDirectory() as directory:
            script = r"""
VM=i-2-45-VM
QUIESCE=false
ABLESTACK_BACKUP_JOB_DIR="$TEST_JOB_DIR"
log() { :; }
source "$TEST_SAFETY_SCRIPT"
trap - EXIT
""" + body
            return subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                  env=dict(os.environ, TEST_JOB_DIR=directory,
                                           TEST_SAFETY_SCRIPT=str(Path(__file__).with_name("ablestack_backup_safety.sh"))))

    def test_abort_accepted_but_job_active_retains_payload_and_ownership(self):
        result = self.run_shell(r"""
touch "$ABLESTACK_BACKUP_JOB_DIR/payload"
virsh() {
  case "$*" in
    *domjobinfo*) echo 'Job type: Unbounded' ;;
    *domjobabort*) return 0 ;;
  esac
}
ablestack_backup_job_started
if ablestack_backup_cleanup_safe; then exit 1; fi
[[ "$BACKUP_JOB_MAY_BE_ACTIVE" == 1 && -f "$ABLESTACK_BACKUP_JOB_DIR/payload" && -f "$ABLESTACK_BACKUP_JOB_DIR/backup-domain" ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_cleanup_requires_post_abort_none(self):
        result = self.run_shell(r"""
active=1
virsh() {
  case "$*" in
    *domjobinfo*) if [[ "$active" == 1 ]]; then echo 'Job type: Unbounded'; else echo 'Job type: None'; fi ;;
    *domjobabort*) active=0 ;;
  esac
}
ablestack_backup_job_started
ablestack_backup_cleanup_safe || exit 1
[[ "$BACKUP_JOB_MAY_BE_ACTIVE" == 0 ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_unavailable_job_info_never_means_stopped(self):
        result = self.run_shell(r"""
virsh() { return 124; }
ablestack_backup_job_started
if ablestack_backup_cleanup_safe; then exit 1; fi
[[ "$BACKUP_JOB_MAY_BE_ACTIVE" == 1 ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_freeze_timeout_still_attempts_owned_thaw(self):
        result = self.run_shell(r"""
QUIESCE=true
virsh() {
  case "$*" in
    *guest-fsfreeze-status*) echo '{"return":"thawed"}' ;;
    *guest-fsfreeze-freeze*) [[ -f "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending" ]] || exit 9; return 124 ;;
    *guest-fsfreeze-thaw*) touch "$ABLESTACK_BACKUP_JOB_DIR/thaw-attempted"; echo '{"return":1}' ;;
  esac
}
ablestack_freeze_guest || exit 1
[[ "$BACKUP_GUEST_FREEZE_PENDING" == 0 && -f "$ABLESTACK_BACKUP_JOB_DIR/thaw-attempted" && ! -f "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending" ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_foreign_guest_freeze_is_not_thawed(self):
        result = self.run_shell(r"""
QUIESCE=true
virsh() {
  case "$*" in
    *guest-fsfreeze-status*) echo '{"return":"frozen"}' ;;
    *) touch "$ABLESTACK_BACKUP_JOB_DIR/unexpected" ;;
  esac
}
ablestack_freeze_guest && ablestack_thaw_guest || exit 1
[[ ! -f "$ABLESTACK_BACKUP_JOB_DIR/unexpected" && ! -f "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending" ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_virsh_timeout_does_not_replace_transfer_budget(self):
        result = self.run_shell(r"""
LIBVIRT_CONTROL_TIMEOUT_SECONDS=1
DATA_OPERATION_TIMEOUT_SECONDS=3600
timeout() { printf '%s\n' "$*" > "$ABLESTACK_BACKUP_JOB_DIR/control-args"; }
virsh -c qemu:///system domjobinfo "$VM"
[[ "$DATA_OPERATION_TIMEOUT_SECONDS" == 3600 ]] || exit 1
[[ "$(cat "$ABLESTACK_BACKUP_JOB_DIR/control-args")" == '-k 5s 1s virsh -c qemu:///system domjobinfo i-2-45-VM' ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_completed_stats_while_backup_active_does_not_abort(self):
        result = self.run_shell(r"""
DATA_OPERATION_TIMEOUT_SECONDS=3600
printf '0' > "$ABLESTACK_BACKUP_JOB_DIR/polls"
virsh() {
  local polls
  polls=$(cat "$ABLESTACK_BACKUP_JOB_DIR/polls")
  case "$*" in
    *domjobinfo*--completed*)
      [[ "$*" == *--keep-completed* ]] || return 9
      if [[ "$polls" -lt 2 ]]; then echo 'Job type: None'; else echo 'Job type: Completed'; fi ;;
    *domjobinfo*)
      if [[ "$polls" -lt 2 ]]; then echo 'Job type: Unbounded'; else echo 'Job type: None'; fi ;;
    *domjobabort*) touch "$ABLESTACK_BACKUP_JOB_DIR/unexpected-abort" ;;
  esac
}
sleep() {
  [[ "$BACKUP_JOB_MAY_BE_ACTIVE" == 1 ]] || exit 8
  printf '%s' "$(( $(cat "$ABLESTACK_BACKUP_JOB_DIR/polls") + 1 ))" > "$ABLESTACK_BACKUP_JOB_DIR/polls"
}
ablestack_backup_job_started
ablestack_wait_for_backup || exit 1
[[ "$(cat "$ABLESTACK_BACKUP_JOB_DIR/polls")" == 2 && "$BACKUP_JOB_MAY_BE_ACTIVE" == 0 && ! -f "$ABLESTACK_BACKUP_JOB_DIR/unexpected-abort" ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_completed_stats_are_not_used_while_current_job_is_active(self):
        result = self.run_shell(r"""
DATA_OPERATION_TIMEOUT_SECONDS=3600
virsh() {
  case "$*" in
    *domjobinfo*--completed*)
      [[ -f "$ABLESTACK_BACKUP_JOB_DIR/current-job-ended" ]] || touch "$ABLESTACK_BACKUP_JOB_DIR/premature-completed-query"
      echo 'Job type: Completed' ;;
    *domjobinfo*)
      if [[ -f "$ABLESTACK_BACKUP_JOB_DIR/current-job-ended" ]]; then echo 'Job type: None'; else echo 'Job type: Bounded'; fi ;;
  esac
}
sleep() { touch "$ABLESTACK_BACKUP_JOB_DIR/current-job-ended"; }
ablestack_backup_job_started
ablestack_wait_for_backup || exit 1
[[ -f "$ABLESTACK_BACKUP_JOB_DIR/current-job-ended" && ! -f "$ABLESTACK_BACKUP_JOB_DIR/premature-completed-query" && "$BACKUP_JOB_MAY_BE_ACTIVE" == 0 ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_idle_backup_requires_completed_result(self):
        for status in ('None', 'Failed', 'Cancelled', ''):
            with self.subTest(status=status):
                result = self.run_shell(r"""
DATA_OPERATION_TIMEOUT_SECONDS=3600
virsh() {
  case "$*" in
    *domjobinfo*--completed*) echo 'Job type: STATUS' ;;
    *domjobinfo*) echo 'Job type: None' ;;
  esac
}
sleep() { exit 9; }
ablestack_backup_job_started
if ablestack_wait_for_backup; then exit 1; fi
[[ "$BACKUP_JOB_MAY_BE_ACTIVE" == 1 ]]
""".replace('STATUS', status))
                self.assertEqual(0, result.returncode, result.stderr)

    def test_failed_status_query_never_marks_backup_complete(self):
        for query in ('current', 'completed'):
            with self.subTest(query=query):
                result = self.run_shell(r"""
DATA_OPERATION_TIMEOUT_SECONDS=3600
virsh() {
  case "$*" in
    *domjobinfo*--completed*) return 124 ;;
    *domjobinfo*) CURRENT_QUERY ;;
  esac
}
ablestack_backup_job_started
if ablestack_wait_for_backup; then exit 1; fi
[[ "$BACKUP_JOB_MAY_BE_ACTIVE" == 1 ]]
""".replace('CURRENT_QUERY', 'return 124' if query == 'current' else "echo 'Job type: None'"))
                self.assertEqual(0, result.returncode, result.stderr)

    def test_active_backup_wait_uses_transfer_deadline(self):
        result = self.run_shell(r"""
DATA_OPERATION_TIMEOUT_SECONDS=2
elapsed=0
date() { printf '%s\n' "$elapsed"; }
virsh() {
  [[ "$*" != *--completed* ]] || exit 9
  echo 'Job type: Unbounded'
}
sleep() { elapsed=$((elapsed + 1)); }
ablestack_backup_job_started
if ablestack_wait_for_backup; then exit 1; fi
[[ "$elapsed" == 2 && "$BACKUP_JOB_MAY_BE_ACTIVE" == 1 ]]
""")
        self.assertEqual(0, result.returncode, result.stderr)


class VolumeSafetyTest(unittest.TestCase):
    def test_close_attempts_abort_even_when_guest_thaw_fails(self):
        worker = object.__new__(volume.Backup)
        worker.success, worker.pull, worker.dummy = False, True, False
        worker.domain = "i-2-45-VM"
        worker.thaw_guest = Mock(side_effect=RuntimeError("Guest unavailable"))
        with patch.object(volume, "run", side_effect=["", "Job type: None"]) as run:
            with self.assertRaisesRegex(RuntimeError, "thaw remains pending"):
                worker.close()
            self.assertIn("domjobabort", run.call_args_list[0].args[0])

    def test_abort_accepted_but_job_active_is_still_pending(self):
        worker = object.__new__(volume.Backup)
        worker.success, worker.pull, worker.dummy = False, True, False
        worker.domain = "i-2-45-VM"
        worker.thaw_guest = Mock()
        with patch.object(volume, "run", side_effect=["", "Job type: Unbounded"]):
            worker.close()
        self.assertTrue(worker.pull)

    def test_host_restart_can_clear_owned_freeze_after_verified_shutdown(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / "engine.json"
            receipt.write_text(json.dumps({"freezePending": True}))
            with patch.object(volume, "run", side_effect=[RuntimeError("No agent"), "shut off"]):
                volume.thaw_owned_guest(receipt, "i-2-45-VM")
            self.assertFalse(json.loads(receipt.read_text())["freezePending"])

    def test_running_unresponsive_guest_retains_freeze_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / "engine.json"
            receipt.write_text(json.dumps({"freezePending": True}))
            with patch.object(volume, "run", side_effect=[RuntimeError("No agent"), "running"]):
                with self.assertRaises(RuntimeError):
                    volume.thaw_owned_guest(receipt, "i-2-45-VM")
            self.assertTrue(json.loads(receipt.read_text())["freezePending"])


if __name__ == "__main__":
    unittest.main()
