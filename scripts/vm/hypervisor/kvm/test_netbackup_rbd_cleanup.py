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

"""RBD cleanup must confirm absence before permitting staging/record deletion."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


def cleanup_functions():
    source = Path(__file__).with_name("ablestack_netbackup.sh").read_text()
    return "\n".join(name + "() {" + source.split("\n" + name + "() {", 1)[1].split("\n}\n", 1)[0] + "\n}"
                     for name in ("rbd_snapshot_exists", "delete_rbd_snapshot_if_unreferenced"))


class NetBackupRbdCleanupTest(unittest.TestCase):
    def cleanup(self, mode):
        script = r'''
set -euo pipefail
EXIT_CLEANUP_FAILED=20
logFile="$ROOT/log"
log() { :; }
timeout() { shift; "$@"; }
split_csv() { printf '%s\n' "$1"; }
has_child_backup() { [[ "$MODE" == child ]]; }
parse_rbd_uri() { RBD_IMAGE="$1"; }
build_rbd_cmd() { RBD_CMD=(mock_rbd); }
mock_rbd() {
  printf '%s\n' "$*" >> "$ROOT/calls"
  if [[ "$2" == ls ]]; then
    [[ "$MODE" != list_failure ]] || return 5
    if [[ "$MODE" == malformed ]]; then
      echo 'not-json'
    elif [[ "$MODE" == absent || -f "$ROOT/removed" ]]; then
      echo '[]'
    else
      echo '[{"id":1,"name":"cp"},{"id":2,"name":"keep-other-snapshot"}]'
    fi
  else
    [[ "$MODE" != remove_failure ]] || return 5
    if [[ "$MODE" != still_present ]]; then touch "$ROOT/removed"; fi
  fi
}
''' + cleanup_functions() + r'''
delete_rbd_snapshot_if_unreferenced "pool/image" "cp"
echo CLEANUP_CONFIRMED
'''
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(["bash", "-c", script], env=dict(os.environ, ROOT=directory, MODE=mode),
                                    capture_output=True, text=True, timeout=10)
            calls = Path(directory, "calls")
            return result, calls.read_text() if calls.exists() else ""

    def test_success_deletes_only_the_owned_snapshot_and_confirms_absence(self):
        result, calls = self.cleanup("success")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("CLEANUP_CONFIRMED", result.stdout)
        self.assertEqual(2, calls.count("snap ls"))
        self.assertIn("snap rm pool/image@cp", calls)
        self.assertNotIn("snap rm pool/image@keep-other-snapshot", calls)

    def test_already_absent_snapshot_is_idempotent_without_removal(self):
        result, calls = self.cleanup("absent")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("snap rm", calls)

    def test_query_or_removal_failure_never_reports_completed_cleanup(self):
        for mode in ("list_failure", "malformed", "remove_failure", "still_present", "child"):
            with self.subTest(mode=mode):
                result, calls = self.cleanup(mode)
                self.assertEqual(20, result.returncode, result.stderr)
                self.assertNotIn("CLEANUP_CONFIRMED", result.stdout)
                if mode in ("list_failure", "malformed", "child"):
                    self.assertNotIn("snap rm", calls)


if __name__ == "__main__":
    unittest.main()
