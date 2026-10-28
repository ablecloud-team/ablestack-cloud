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

"""PowerShell selection must never resubmit a failed Veeam operation."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
TRANSPORT = r'''
import base64, json, os, sys
from pathlib import Path
command = sys.argv[1]
script = ""
if " -EncodedCommand " in command:
    script = base64.b64decode(command.rsplit(" ", 1)[1]).decode("utf-16-le")
elif " -File " in command:
    script = (Path(os.environ["TEST_ROOT"]) / "copied.ps1").read_text()
with (Path(os.environ["TEST_ROOT"]) / "calls").open("a") as stream:
    stream.write(json.dumps(dict(command=command, script=script)) + "\n")
if command.startswith("del "):
    sys.exit(0)
if "ABLESTACK_POWERSHELL7_READY" in script:
    index = 0 if command.startswith('"C:') else 1 if command.startswith("pwsh.exe ") else 2
    if index < int(os.environ["TEST_MISSING"]):
        print("executable or module unavailable", file=sys.stderr)
        sys.exit(1)
    print("ABLESTACK_POWERSHELL7_READY")
else:
    if os.environ["TEST_FAIL"] == "true":
        print("submission response unconfirmed", file=sys.stderr)
        sys.exit(1)
    if os.environ["TEST_QUERY"] != "true":
        print("accepted")
'''


class VeeamPowerShellTest(unittest.TestCase):
    def run_hook(self, missing=0, fail=False, large=False, query=False, upload_failure=False, payload=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            transport = root / "transport.py"
            transport.write_text(TRANSPORT)
            script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
VEEAM_SSH_HOST=vbr-test
mold_backup_notify_log() { printf '%s\n' "$*" >&2; }
base64() { python3 -c 'import base64,sys; sys.stdout.write(base64.b64encode(sys.stdin.buffer.read()).decode())'; }
mold_backup_veeam_ssh_cmd() { python3 "$TEST_TRANSPORT" "$1"; }
mold_backup_veeam_scp() { [[ "$TEST_UPLOAD_FAILURE" == false ]] || return 1; cp "$1" "$TEST_ROOT/copied.ps1"; }
if [[ "$TEST_QUERY" == true ]]; then
  mold_backup_query_veeam_backups
else
  mold_backup_veeam_ssh_ps_capture "$TEST_SCRIPT"
fi
'''
            payload = payload or "Start-VBRComputerBackupJob -Job $job -RunAsync"
            if large:
                payload += "\n# " + "padding" * 500
            env = dict(os.environ, TEST_ROOT=str(root), TEST_LIBRARY=str(ROOT / "veeam/mold-backup.lib.sh"),
                       TEST_TRANSPORT=str(transport), TEST_MISSING=str(missing), TEST_FAIL=str(fail).lower(),
                       TEST_QUERY=str(query).lower(), TEST_SCRIPT=payload,
                       TEST_UPLOAD_FAILURE=str(upload_failure).lower())
            result = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=20)
            calls = [json.loads(line) for line in (root / "calls").read_text().splitlines()] if (root / "calls").exists() else []
            return result, calls

    def test_standard_installation_is_preferred_and_the_action_runs_once(self):
        result, calls = self.run_hook()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("accepted", result.stdout)
        self.assertEqual(2, len(calls))
        self.assertTrue(calls[0]["command"].startswith('"C:\\Program Files\\PowerShell\\7\\pwsh.exe" '))
        self.assertNotIn("Start-VBRComputerBackupJob", calls[0]["script"])
        action = calls[1]["script"]
        self.assertLess(action.index("$ErrorActionPreference='Stop'"), action.index("Import-Module"))
        self.assertLess(action.index("-ErrorAction Stop"), action.index("Start-VBRComputerBackupJob"))

    def test_path_candidates_are_tried_only_by_the_probe(self):
        for missing in (1, 2):
            with self.subTest(missing=missing):
                result, calls = self.run_hook(missing=missing)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(missing + 2, len(calls))
                self.assertTrue(calls[-1]["command"].startswith("pwsh.exe " if missing == 1 else "pwsh "))
                self.assertEqual(1, sum("Start-VBRComputerBackupJob" in call["script"] for call in calls))

    def test_missing_power_shell_or_module_never_executes_the_action(self):
        result, calls = self.run_hook(missing=3)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(3, len(calls))
        self.assertIn("PowerShell 7", result.stderr)
        self.assertFalse(any("Start-VBRComputerBackupJob" in call["script"] for call in calls))
        self.assertFalse(any(call["command"].startswith("powershell") for call in calls))

    def test_encoded_action_failure_does_not_try_another_launcher(self):
        result, calls = self.run_hook(fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, len(calls))
        self.assertEqual(1, sum("Start-VBRComputerBackupJob" in call["script"] for call in calls))

    def test_file_action_failure_does_not_fall_back_or_resubmit(self):
        result, calls = self.run_hook(large=True, fail=True)
        self.assertNotEqual(0, result.returncode)
        actions = [call for call in calls if "Start-VBRComputerBackupJob" in call["script"]]
        self.assertEqual(1, len(actions))
        self.assertIn(" -File ", actions[0]["command"])
        self.assertTrue(calls[-1]["command"].startswith("del "))
        self.assertFalse(any(call["command"].startswith("powershell") for call in calls))

    def test_backup_session_watcher_uses_the_same_selection(self):
        result, calls = self.run_hook(missing=1, query=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, sum("Get-VBRComputerBackupJobSession" in call["script"] for call in calls))
        self.assertTrue(next(call for call in calls if "Get-VBRComputerBackupJobSession" in call["script"])["command"].startswith("pwsh.exe "))

    def test_encoded_length_accounts_for_supplementary_unicode_characters(self):
        result, calls = self.run_hook(payload="Write-Output '" + "😀" * 1100 + "'")
        self.assertEqual(0, result.returncode, result.stderr)
        action = next(call for call in calls if "Write-Output '😀" in call["script"])
        self.assertIn(" -File ", action["command"])
        self.assertTrue(all(len(call["command"]) <= 6000 for call in calls))

    def test_upload_failure_never_falls_back_to_an_oversized_command(self):
        result, calls = self.run_hook(large=True, upload_failure=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("oversized command was not executed", result.stderr)
        self.assertFalse(any("Start-VBRComputerBackupJob" in call["script"] for call in calls))
        self.assertTrue(all(len(call["command"]) <= 6000 for call in calls))


if __name__ == "__main__":
    unittest.main()
