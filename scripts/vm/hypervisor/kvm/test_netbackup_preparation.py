#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""A parent Hook follows its accepted VM backup, including failure before bootstrap."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parent
BACKUP = "07d03010-a77d-4e72-82c7-1c6c02d767db"


class NetBackupPreparationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.staging = self.root / "stage"
        self.point = self.staging / "i-2-1-VM" / "current"
        self.point.mkdir(parents=True)
        self.responses = self.root / "responses.json"
        self.api = self.root / "api.py"
        self.api.write_text('''import json, os, pathlib, sys
root = pathlib.Path(os.environ['TEST_ROOT'])
with (root / 'requests').open('a') as output:
    output.write(json.dumps(sys.argv[1:]) + '\\n')
file = root / 'responses.json'
values = json.loads(file.read_text())
value = values[0]
if len(values) > 1: file.write_text(json.dumps(values[1:]))
if value is None: sys.exit(1)
print(json.dumps(value))
''')
        self.env = dict(os.environ, TEST_ROOT=str(self.root), TEST_COMMON=str(DIRECTORY / "netbackup-host-common.sh"),
                        BACKUP_STAGING_ROOT=str(self.staging), LOG_FILE=str(self.root / "hook.log"),
                        TEST_BACKUP=BACKUP)

    def status(self, state="RUNNING", status="BackingUp", backup=BACKUP, nested=False):
        value = dict(id=backup, state=state, status=status, details="original source failure")
        return dict(getbackupjobstatusresponse=dict(backupjobstatus=value) if nested else value)

    def marker(self, backup=BACKUP, point=None):
        point = point or self.point
        point.mkdir(parents=True, exist_ok=True)
        (point / ".volume-bootstrap").write_text(backup + "\n")

    def wait(self, responses, backup=BACKUP):
        self.responses.write_text(json.dumps(responses))
        script = r'''source "$TEST_COMMON"
MOLD_LIST_VMS_API_METHOD=GET
MOLD_QUERY_ASYNC_JOB_API_URL=http://mold.test/client/api
NETBACKUP_STAGING_TIMEOUT=1
NETBACKUP_STAGING_POLL_INTERVAL=0.02
invoke_mold_api() { python3 "$TEST_ROOT/api.py" "$@"; }
wait_for_vm_staging_complete i-2-1-VM 1 "$TEST_BACKUP"
'''
        return subprocess.run(["bash", "-c", script], env=dict(self.env, TEST_BACKUP=backup),
                              text=True, capture_output=True, timeout=8)

    def requests(self):
        return [json.loads(line) for line in (self.root / "requests").read_text().splitlines()]

    def test_failure_before_marker_returns_the_original_reason_without_waiting_for_timeout(self):
        result = self.wait([self.status("FAILED")])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("original source failure", result.stderr)
        self.assertNotIn("Timed out", result.stderr)
        self.assertEqual(1, len(self.requests()))

    def test_cancellation_and_terminal_row_status_cannot_publish_a_stale_marker(self):
        self.marker()
        for state, row in (("CANCELED", "BackingUp"), ("CANCEL_PENDING", "BackingUp"),
                           ("INTERRUPTED", "BackingUp"), ("ERROR", "BackingUp"), ("RUNNING", "Failed")):
            with self.subTest(state=state, row=row):
                result = self.wait([self.status(state, row)])
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn(str(self.point), result.stdout)
                self.assertIn("original source failure", result.stderr)

    def test_waiting_for_a_host_slot_still_releases_the_parent_hook(self):
        self.marker()
        result = self.wait([self.status("WAITING")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(self.point), result.stdout)
        self.assertEqual([["GET", "http://mold.test/client/api", "getBackupJobStatus", "id", BACKUP, "limit", "0"]], self.requests())

    def test_transient_query_failure_retries_without_canceling_or_creating_another_backup(self):
        self.marker()
        result = self.wait([None, self.status()])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(self.requests()))
        self.assertTrue(all(request[2] == "getBackupJobStatus" for request in self.requests()))

    def test_unknown_mismatched_and_invalid_responses_cannot_accept_even_an_existing_marker(self):
        self.marker()
        for response in (self.status("UNKNOWN"), self.status(backup="another-backup"),
                         dict(getbackupjobstatusresponse=None), dict(errorresponse=dict(errortext="denied"))):
            with self.subTest(response=response):
                result = self.wait([response, self.status()])
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(str(self.point), result.stdout)

    def test_persistently_unconfirmed_status_is_bounded_and_never_creates_a_backup(self):
        self.marker()
        result = self.wait([self.status("UNKNOWN")])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Timed out", result.stderr)
        self.assertEqual("getBackupJobStatus", self.requests()[-1][2])

    def test_completed_without_its_marker_does_not_wait_for_or_choose_another_point(self):
        self.marker("old-backup")
        result = self.wait([self.status("BackedUp", "BackedUp")])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("without its expected bootstrap marker", result.stderr)
        self.assertEqual(1, len(self.requests()))

    def test_nested_status_response_is_supported(self):
        self.marker()
        result = self.wait([self.status(nested=True)])
        self.assertEqual(0, result.returncode, result.stderr)

    def test_legacy_response_retains_marker_discovery_without_guessing_an_api_backup_id(self):
        self.marker()
        result = self.wait([], backup="")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.root / "requests").exists())

    def test_exact_marker_wins_over_a_newer_point_and_clock_skew(self):
        self.marker()
        self.marker("old-backup", self.point.parent / "newer")
        marker = self.point / ".volume-bootstrap"
        os.utime(marker, (1, 1))
        os.utime(self.point, (1, 1))
        script = 'source "$TEST_COMMON"; discover_completed_vm_backup_path i-2-1-VM 9999999999 "$TEST_BACKUP"'
        result = subprocess.run(["bash", "-c", script], env=self.env, text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(self.point), result.stdout)

    def test_symlink_marker_is_not_an_owned_bootstrap_receipt(self):
        other = self.root / "other-marker"
        other.write_text(BACKUP + "\n")
        (self.point / ".volume-bootstrap").symlink_to(other)
        result = self.wait([self.status("COMPLETED", "BackedUp")])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("without its expected bootstrap marker", result.stderr)


if __name__ == "__main__":
    unittest.main()
