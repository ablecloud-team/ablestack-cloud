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

"""Context ownership recovery and failed PRE/POST isolation."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

DIRECTORY = Path(__file__).resolve().parent


class NetBackupContextStateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"
        self.context = self.state / "contexts/policy__schedule__host"
        self.context.parent.mkdir(parents=True)
        self.marker = self.context.with_suffix(".inprogress")
        self.envfile = self.context.with_suffix(".env")
        self.marker.write_text("BACKUP_ID=old\nCONTEXT_PRE_OWNER_PID=999\nCONTEXT_PRE_OWNER_TOKEN=abcd-1234:99\n")
        self.envfile.write_text("BACKUP_ID=old\n")
        self.payload = self.root / "disk.raw"
        self.payload.write_text("saved payload")
        bindir = self.root / "bin"
        bindir.mkdir()
        flock = bindir / "flock"
        flock.write_text("#!/bin/sh\nexit 0\n")
        flock.chmod(0o755)
        self.env = dict(os.environ, STATE_ROOT=str(self.state), LOG_FILE=str(self.root / "hook.log"),
                        ABLESTACK_NETBACKUP_HOOK_WORKER="POST",
                        BACKUP_ID="new", TEST_COMMON=str(DIRECTORY / "netbackup-host-common.sh"),
                        PATH=str(bindir) + os.pathsep + os.environ["PATH"])

    def pre(self, identity="absent", failure=False):
        script = r'''source "$TEST_COMMON"
resolve_context policy schedule host
ensure_runtime_dirs
context_process_identity() {
  if [[ "$1" == 999 ]]; then
    [[ "$TEST_IDENTITY" != error ]] || return 2
    printf '%s' "$TEST_IDENTITY"
  else
    printf 'abcd-1234:101'
  fi
}
mark_context_in_progress
'''
        result = subprocess.run(["bash", "-c", script], env=dict(self.env, TEST_IDENTITY=identity),
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(failure, result.returncode != 0, result.stdout + result.stderr)
        self.assertEqual("saved payload", self.payload.read_text())
        return result

    def context_files(self, policy, schedule, client="ablecube1", state=None):
        script = r'''
set -euo pipefail
source "$TEST_COMMON"
POLICY_NAME=""
SCHEDULE_NAME=""
CLIENT_NAME=""
resolve_context "$TEST_POLICY" "$TEST_SCHEDULE" "$TEST_CLIENT" fixed-time
ensure_runtime_dirs
mkdir -p "$RUNTIME_DIR"
printf 'lock' > "$LOCK_FILE"
printf 'context' > "$CONTEXT_FILE"
printf 'marker' > "$(context_in_progress_file)"
printf '[]' > "$STATE_ROOT/sessions/${CONTEXT_KEY}.listVirtualMachines.json"
printf '%s\n' "$CONTEXT_KEY" "$POLICY_NAME" "$SCHEDULE_NAME" "$CLIENT_NAME"
'''
        return subprocess.run(["bash", "-c", script], env=dict(self.env, STATE_ROOT=str(state or self.state),
            TEST_POLICY=policy, TEST_SCHEDULE=schedule, TEST_CLIENT=client), text=True, capture_output=True, timeout=10)

    def test_customer_policy_and_custom_request_schedule_keep_existing_context_names(self):
        policy = "ablecube1-poc-policy"
        result = self.context_files(policy, "mold-vm-request")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(policy + "__mold-vm-request__ablecube1", result.stdout.splitlines()[0])

    def test_maximum_policy_schedule_and_client_names_create_bounded_distinct_state_files(self):
        keys = []
        names = ("p" * 128, "s" * 128, "c" * 128)
        variants = [names, ("p" * 127 + "q", names[1], names[2]),
                    (names[0], "s" * 127 + "t", names[2]), (names[0], names[1], "c" * 127 + "d")]
        for i, values in enumerate(variants):
            with self.subTest(values=values):
                root = self.root / ("long-" + str(i))
                result = self.context_files(*values, state=root)
                self.assertEqual(0, result.returncode, result.stderr)
                key, policy, schedule, client = result.stdout.splitlines()
                self.assertEqual(values, (policy, schedule, client))
                self.assertTrue(key.startswith("ctx-"))
                self.assertLessEqual(len(key), 68)
                keys.append(key)
                for path in root.rglob("*"):
                    self.assertLessEqual(len(path.name.encode()), 255)
        self.assertEqual(4, len(set(keys)))
        again = self.context_files(*names, state=self.root / "repeat")
        self.assertEqual(keys[0], again.stdout.splitlines()[0])

    def test_existing_long_name_pending_context_is_preserved_before_changing_keys(self):
        policy, schedule, client = "p" * 128, "s" * 94, "ablecube1"
        raw = policy + "__" + schedule + "__" + client
        marker = self.state / "contexts" / (raw + ".inprogress")
        marker.write_text("legacy-owner")
        result = self.context_files(policy, schedule, client)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("legacy long-name", result.stderr)
        self.assertEqual("legacy-owner", marker.read_text())

    def test_dead_pre_owner_archives_its_context_and_allows_a_new_request(self):
        self.pre()
        archive = next(self.context.parent.glob("recovered-*"))
        self.assertIn("BACKUP_ID=old", (archive / self.envfile.name).read_text())
        self.assertIn("BACKUP_ID=new", self.marker.read_text())
        self.assertFalse(self.envfile.exists())

    def test_reused_pid_is_not_mistaken_for_the_original_owner(self):
        self.pre(identity="abcd-1234:100")
        self.assertEqual(1, len(list(self.context.parent.glob("recovered-*"))))

    def test_live_owner_and_failed_identity_query_preserve_the_context(self):
        original = self.marker.read_text()
        for identity in ("abcd-1234:99", "error"):
            self.pre(identity=identity, failure=True)
            self.assertEqual(original, self.marker.read_text())
            self.assertTrue(self.envfile.exists())
        self.assertFalse(list(self.context.parent.glob("recovered-*")))

    def test_legacy_marker_and_same_backup_are_never_automatically_archived(self):
        original = self.marker.read_text()
        self.marker.write_text("SESSION_TIMESTAMP=legacy\n")
        self.pre(failure=True)
        self.marker.write_text(original)
        self.env["BACKUP_ID"] = "old"
        self.pre(failure=True)
        self.assertFalse(list(self.context.parent.glob("recovered-*")))

    def test_other_context_identity_is_not_archived(self):
        self.envfile.write_text("BACKUP_ID=different\n")
        self.pre(failure=True)
        self.assertIn("different", self.envfile.read_text())

    def test_same_backup_retry_requires_proof_of_the_exact_retired_pre_owner(self):
        self.env.update(BACKUP_ID="old", ABLESTACK_NETBACKUP_RETIRED_PRE_PID="999",
                        ABLESTACK_NETBACKUP_RETIRED_PRE_TOKEN="abcd-1234:98")
        self.pre(failure=True)
        self.env["ABLESTACK_NETBACKUP_RETIRED_PRE_TOKEN"] = "abcd-1234:99"
        self.pre()
        self.assertEqual(1, len(list(self.context.parent.glob("recovered-*"))))

    def test_late_post_with_reused_ids_cannot_finalize_the_current_attempt(self):
        import netbackup_hook_runner as runner
        record = dict(context="policy__schedule__host", backupId="new", jobId="job", state="COMPLETED",
                      netbackupCaller=dict(pid=99999999, token="other-client-start"))
        runner.atomic(runner.paths(self.state, "policy__schedule__host", "PRE")[0], record)
        for status in ("0", "73", "74", "150"):
            result = subprocess.run(["bash", str(DIRECTORY / "netbackup-host-bpend-notify.sh"),
                                     "policy", "schedule", "host", status], env=self.env,
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("BACKUP_ID=old\n", self.envfile.read_text())
            self.assertTrue(self.marker.exists())
        self.assertNotIn("Mold API", (self.root / "hook.log").read_text())

    def test_failed_or_timed_out_pre_post_never_updates_the_previous_backup(self):
        for status in ("73", "74"):
            result = subprocess.run(["bash", str(DIRECTORY / "netbackup-host-bpend-notify.sh"),
                                     "policy", "schedule", "host", status], env=self.env,
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("BACKUP_ID=old\n", self.envfile.read_text())
            self.assertTrue(self.marker.exists())
        self.assertNotIn("Mold API", (self.root / "hook.log").read_text())

    def test_post_with_another_backup_identity_preserves_saved_state(self):
        result = subprocess.run(["bash", str(DIRECTORY / "netbackup-host-bpend-notify.sh"),
                                 "policy", "schedule", "host", "0"], env=self.env,
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("BACKUP_ID=old\n", self.envfile.read_text())
        self.assertIn("differs from the saved context", (self.root / "hook.log").read_text())


if __name__ == "__main__":
    unittest.main()
