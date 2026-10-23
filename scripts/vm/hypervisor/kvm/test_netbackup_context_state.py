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
