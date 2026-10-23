#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Host-script regressions for Mold schedules and legacy configuration selection."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).parent


def candidate(name="i-2-1-VM", scheduled=False, assigned=True):
    value = {"virtualmachineid": "vm-" + name, "instancename": name, "hostid": "host-uuid"}
    if assigned:
        value["backupofferingid"] = "offering-uuid"
    if scheduled:
        value.update(scheduleid="schedule-uuid", quiescevm=False)
    return value


def response(mode, candidates):
    return {"listexternalbackupcandidatesresponse": {
        "externalbackupcandidates": {"selectionmode": mode, "candidate": candidates}}}


class ExternalBackupSelectionTest(unittest.TestCase):
    def run_selection(self, provider, payload, include="*", exclude="", api_failure=False, vm_schedules=""):
        if provider == "netbackup":
            script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
MOLD_VM_CACHE_FILE="$TEST_ROOT/candidates.json"
MOLD_LIST_NETBACKUP_CANDIDATES_API_URL=http://test.invalid/client/api
CLIENT_NAME=host-test POLICY_NAME=parent-policy
VM_INCLUDE="$TEST_INCLUDE" VM_EXCLUDE="$TEST_EXCLUDE"
VM_SCHEDULE_BACKUP="$TEST_VM_SCHEDULE_BACKUP"
log() { :; }
invoke_mold_api() {
  echo "API:$3 ${*:4}" >&2
  [[ "$TEST_API_FAILURE" == False ]] || return 1
  printf '%s' "$TEST_PAYLOAD"
}
list_running_vms() { printf '%s\n' i-2-1-VM i-2-2-VM; }
cache_mold_virtual_machines
list_target_vms
'''
            library = ROOT / "netbackup-host-common.sh"
        else:
            script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
VM_INCLUDE="$TEST_INCLUDE" VM_EXCLUDE="$TEST_EXCLUDE"
VM_SCHEDULE_BACKUP="$TEST_VM_SCHEDULE_BACKUP"
BACKUP_MODE=host VEEAM_PROVIDER_NAME=ablestack-veeam
VEEAM_AGENT_PAYLOAD_PATH="$TEST_ROOT/payload"
mold_backup_load_config() { :; }
mold_backup_local_kvm_name() { echo host-test; }
mold_backup_normalize_job_name() { printf '%s' "$1"; }
mold_backup_notify_log() { echo "$*" >&2; }
mold_backup_cmk_run() {
  echo "API:$*" >&2
  [[ "$TEST_API_FAILURE" == False ]] || return 1
  printf '%s' "$TEST_PAYLOAD"
}
mold_backup_state_dir() { echo "$TEST_ROOT/state"; }
mold_backup_state_file_for_job() { echo "$TEST_ROOT/state/job.state"; }
mold_backup_process_external_candidate_vm() { echo "$1"; }
mold_backup_domain_exists() { return 1; }
mold_backup_api_ensure_backup_resources() { echo UNEXPECTED_AUTO_CREATE >&2; return 1; }
mold_backup_pre_notify host-test parent-policy default
'''
            library = ROOT / "veeam" / "mold-backup.lib.sh"
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, TEST_LIBRARY=str(library), TEST_ROOT=directory,
                       TEST_PAYLOAD=json.dumps(payload), TEST_API_FAILURE=str(api_failure),
                       TEST_VM_SCHEDULE_BACKUP=vm_schedules,
                       TEST_INCLUDE=include, TEST_EXCLUDE=exclude)
            return subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env, timeout=10)

    def test_schedule_targets_ignore_configuration_include_and_exclude(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, response("SCHEDULE", [candidate(scheduled=True)]),
                                            include="i-2-2-VM", exclude="i-2-1-VM")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["i-2-1-VM"], result.stdout.splitlines())

    def test_empty_due_list_does_not_fall_back_to_configuration(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, response("SCHEDULE", []))
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)

    def test_explicit_schedule_mode_ignores_the_exclude_all_sentinel(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, response("SCHEDULE", [candidate(scheduled=True)]),
                                            exclude="*", vm_schedules="true")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["i-2-1-VM"], result.stdout.splitlines())
                self.assertIn("vmschedulebackup", result.stderr)

    def test_explicit_configuration_mode_excludes_all_when_both_filters_are_stars(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, response("CONFIG", [candidate()]), exclude="*", vm_schedules="false")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)

    def test_response_must_match_the_configured_selection_mode(self):
        for provider in ("netbackup", "veeam"):
            for configured, returned in (("true", "CONFIG"), ("false", "SCHEDULE")):
                with self.subTest(provider=provider, configured=configured):
                    result = self.run_selection(provider, response(returned, []), vm_schedules=configured)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", result.stdout)

    def test_no_schedules_uses_configuration_filter_and_includes_unassigned(self):
        payload = response("CONFIG", [candidate(assigned=False), candidate("i-2-2-VM")])
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, payload, include="i-2-1-VM")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["i-2-1-VM"], result.stdout.splitlines())

    def test_configuration_exclusion_is_applied(self):
        payload = response("CONFIG", [candidate(), candidate("i-2-2-VM")])
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, payload, exclude="i-2-1-VM")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["i-2-2-VM"], result.stdout.splitlines())

    def test_schedule_lookup_failure_stops_without_backups(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_selection(provider, response("CONFIG", [candidate()]), api_failure=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertNotIn("UNEXPECTED_AUTO_CREATE", result.stderr)
                self.assertEqual(1, result.stderr.count("API:"))

    def test_invalid_responses_never_enable_configuration_fallback(self):
        invalid = [{}, {"errorresponse": {"errortext": "denied"}},
                   response("UNKNOWN", []), response("CONFIG", None),
                   response("SCHEDULE", [candidate(assigned=False)]),
                   response("SCHEDULE", [candidate(scheduled=True, assigned=False)]),
                   response("CONFIG", [candidate(), candidate()]),
                   response("CONFIG", [candidate(scheduled=True)])]
        for provider in ("netbackup", "veeam"):
            for payload in invalid:
                with self.subTest(provider=provider, payload=payload):
                    result = self.run_selection(provider, payload)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", result.stdout)

    def run_dispatch(self, provider, mode, assigned=True, fail=False, vm_schedules=""):
        data = response(mode, [candidate(scheduled=mode == "SCHEDULE", assigned=assigned)])
        if mode == "CONFIG" and not assigned:
            data["listexternalbackupcandidatesresponse"]["externalbackupcandidates"]["candidate"][0].update(
                backupofferingid=None, scheduleid=None)
        if provider == "netbackup":
            library = ROOT / "netbackup-host-common.sh"
            script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
MOLD_VM_CACHE_FILE="$TEST_ROOT/candidates.json"
printf '%s' "$TEST_CANDIDATES" > "$MOLD_VM_CACHE_FILE"
POLICY_NAME=parent-policy
VM_SCHEDULE_BACKUP="$TEST_VM_SCHEDULE_BACKUP"
log() { :; }
invoke_mold_api() {
  echo "REQUEST:$*" >> "$TEST_ROOT/requests"
  [[ "$TEST_FAIL_DISPATCH" == False ]] || return 1
  echo '{"createnetbackupresponse":{"jobid":"job-uuid"}}'
}
wait_for_mold_async_job() { echo '{}'; }
wait_for_vm_staging_complete() { echo /test/staging; }
run_stage_vm_backup i-2-1-VM
'''
        else:
            library = ROOT / "veeam" / "mold-backup.lib.sh"
            script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
MOLD_EXTERNAL_BACKUP_CANDIDATES="$TEST_CANDIDATES"
VEEAM_JOB_NAME=parent-policy
VM_SCHEDULE_BACKUP="$TEST_VM_SCHEDULE_BACKUP"
mold_backup_api_external_stage_timeout() { echo 60; }
mold_backup_api_build_backup_name_for_vm() { echo test-backup; }
mold_backup_api_list_backup_ids() { :; }
mold_backup_resolve_veeam_interval_type() { echo EXTERNAL; }
mold_backup_normalize_job_name() { printf '%s' "$1"; }
mold_backup_notify_log() { :; }
mold_backup_cmk_run() {
  echo "REQUEST:$*" >&2
  [[ "$TEST_FAIL_DISPATCH" == False ]] || return 1
  echo '{"createablestackveeambackupresponse":{"jobid":"job-uuid"}}'
}
mold_backup_api_wait_async_job() {
  echo '{"queryasyncjobresultresponse":{"jobresult":{"backup":{"id":"backup-uuid","type":"FULL"}}}}'
}
mold_backup_api_create_veeam_and_wait vm-i-2-1-VM i-2-1-VM
'''
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, TEST_LIBRARY=str(library), TEST_ROOT=directory,
                       TEST_CANDIDATES=json.dumps(data["listexternalbackupcandidatesresponse"]["externalbackupcandidates"]),
                       TEST_FAIL_DISPATCH=str(fail))
            env["TEST_VM_SCHEDULE_BACKUP"] = vm_schedules
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env, timeout=10)
            requests = Path(directory) / "requests"
            if requests.exists():
                result.stderr += requests.read_text()
            return result

    def test_dispatch_preserves_selected_schedule_host_and_offering(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_dispatch(provider, "SCHEDULE")
                self.assertEqual(0, result.returncode, result.stderr)
                for value in ("SCHEDULE", "host-uuid", "offering-uuid", "schedule-uuid", "parent-policy"):
                    self.assertIn(value, result.stderr)
                self.assertEqual(1, result.stderr.count("REQUEST:"))

    def test_config_dispatch_allows_server_to_assign_default_offering(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_dispatch(provider, "CONFIG", assigned=False)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("CONFIG", result.stderr)
                self.assertNotIn("backupofferingid", result.stderr)
                self.assertNotIn("scheduleid", result.stderr)

    def test_failed_dispatch_never_retries_without_schedule_context(self):
        for provider in ("netbackup", "veeam"):
            with self.subTest(provider=provider):
                result = self.run_dispatch(provider, "SCHEDULE", fail=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, result.stderr.count("REQUEST:"))

    def test_configured_selection_flag_is_forwarded_at_execution(self):
        for provider in ("netbackup", "veeam"):
            for mode, enabled in (("SCHEDULE", "true"), ("CONFIG", "false")):
                with self.subTest(provider=provider, mode=mode):
                    result = self.run_dispatch(provider, mode, vm_schedules=enabled)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn("vmschedulebackup", result.stderr)
                    self.assertIn(enabled, result.stderr)

    def test_veeam_native_dispatch_retains_bootstrap_and_watcher_guard(self):
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
MOLD_EXTERNAL_BACKUP_CANDIDATES="$TEST_CANDIDATES"
VEEAM_AGENT_PAYLOAD_PATH="$TEST_ROOT/payload"
mold_backup_trigger_active() { return 1; }
mold_backup_trigger_mark() { echo "GUARD:$*" >&2; }
mold_backup_api_create_veeam_and_wait() { echo 'backup-uuid|FULL'; }
mold_backup_api_backup_external_id() { echo "$TEST_ROOT/staging"; }
mold_backup_state_write_line() { echo "$2" >> "$1"; }
mold_backup_process_external_candidate_vm i-2-1-VM "$TEST_ROOT/job.state"
'''
        with tempfile.TemporaryDirectory() as directory:
            staging = Path(directory) / "staging"
            staging.mkdir()
            (staging / ".volume-bootstrap").write_text("backup-uuid")
            data = response("SCHEDULE", [candidate(scheduled=True)])
            env = dict(os.environ, TEST_LIBRARY=str(ROOT / "veeam" / "mold-backup.lib.sh"), TEST_ROOT=directory,
                       TEST_CANDIDATES=json.dumps(data["listexternalbackupcandidatesresponse"]["externalbackupcandidates"]))
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("GUARD:veeam-active i-2-1-VM", result.stderr)
            self.assertEqual("backup-uuid", (Path(directory) / "payload/i-2-1-VM/backup-bootstrap.txt").read_text())
            self.assertIn("status=pipeline", (Path(directory) / "job.state").read_text())


if __name__ == "__main__":
    unittest.main()
