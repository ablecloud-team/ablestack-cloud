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

"""Execute the real generated PowerShell against Linux FLR task fixtures."""

import copy
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parent
PARENT = "11111111-2222-3333-4444-555555555555"
TASK = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
POINT = "12345678-1234-1234-1234-123456789012"
PWSH = os.environ.get("TEST_PWSH") or shutil.which("pwsh")
if not PWSH and Path("/private/tmp/mold-veeam-powershell-7.4.14/pwsh").exists():
    PWSH = "/private/tmp/mold-veeam-powershell-7.4.14/pwsh"

FIXTURE = r'''
$ErrorActionPreference='Stop'
$fixture=Get-Content -LiteralPath $env:TEST_FIXTURE -Raw | ConvertFrom-Json
$parents=@($fixture.parents)
foreach ($parent in $parents) {
  foreach ($field in @('CreationTime', 'CreationTimeUTC', 'EndTime', 'EndTimeUTC')) {
    $parent.$field=[datetime]$parent.$field
  }
  $parent.RestorePoint.CreationTimeUTC=[datetime]$parent.RestorePoint.CreationTimeUTC
  foreach ($task in @($parent.Tasks)) {
    foreach ($field in @('CreationTime', 'EndTime', 'CreationTimeUTC', 'EndTimeUTC')) {
      if ($task.Info.PSObject.Properties[$field] -and $task.Info.$field) { $task.Info.$field=[datetime]$task.Info.$field }
    }
    $task | Add-Member -NotePropertyName Logger -NotePropertyValue ([pscustomobject]@{Records=@($task.Log)})
    $task.Logger | Add-Member -MemberType ScriptMethod -Name GetLog -Value { return [pscustomobject]@{UpdatedRecords=@($this.Records)} }
  }
  if ($parent.HasTaskGetter) {
    $parent | Add-Member -MemberType ScriptMethod -Name GetTaskSessions -Value { return @($this.Tasks) }
  }
  $parent | Add-Member -NotePropertyName Logger -NotePropertyValue ([pscustomobject]@{Records=@($parent.Log)})
  $parent.Logger | Add-Member -MemberType ScriptMethod -Name GetLog -Value { return [pscustomobject]@{UpdatedRecords=@($this.Records)} }
}
function Import-Module { [CmdletBinding()] param([string]$Name) }
function Connect-VBRServer { [CmdletBinding()] param([string]$Server) }
function Get-VBRRestoreSession {
  [CmdletBinding()] param([guid]$Id)
  if ($PSBoundParameters.ContainsKey('Id')) { return @($parents | Where-Object { [guid]$_.Id -eq $Id }) }
  return $parents
}
function Export-VBRAudit {
  [CmdletBinding()] param($From, $To, [string]$FileFullPath)
  @($fixture.audit) | Export-Csv -LiteralPath $FileFullPath -NoTypeInformation
}
'''


@unittest.skipUnless(PWSH, "PowerShell 7 is required (set TEST_PWSH)")
class VeeamRestoreTaskTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        now = time.time()
        self.start = int(now - 90)
        self.end = int(now - 60)
        self.task = dict(Id=TASK, JobSessId=PARENT, Name="host-test", Status="Success", Info=dict(
            Id=TASK, ObjectName="host-test", Status="Success",
            CreationTime=self.local(self.start), EndTime=self.local(self.end)), Log=[])
        self.parent = dict(Id=PARENT, Name="FLR_[host-test]", Result="None", IsCompleted=False,
                           CreationTime="2026-09-01T00:00:00", CreationTimeUTC="2026-09-01T00:00:00Z",
                           EndTime="1900-01-01T00:00:00", EndTimeUTC="1900-01-01T00:00:00Z",
                           RestorePoint=dict(Id=POINT, CreationTimeUTC="2026-09-01T00:00:00Z"),
                           Options='MachineName="192.0.2.1" BackupName="ABLESTACK-M-test"',
                           Tasks=[self.task], HasTaskGetter=True, Log=[])
        self.fixture = dict(parents=[self.parent], audit=[])

    @staticmethod
    def local(epoch):
        return datetime.datetime.fromtimestamp(epoch, ZoneInfo("Asia/Seoul")).replace(tzinfo=None).isoformat()

    def run_powershell(self, proof=False, parent=PARENT, task=TASK):
        capture = self.base / "generated.ps1"
        shell = r'''
set -euo pipefail
source "$TEST_LIBRARY"
VEEAM_SSH_HOST=fixture
mold_backup_notify_log() { :; }
mold_backup_veeam_ssh_ps_capture() { printf '%s\n' "$1" > "$TEST_CAPTURE"; }
if [[ "$TEST_PROOF" == true ]]; then
  mold_backup_query_veeam_restore_proof "$TEST_TASK" "$TEST_PARENT" >/dev/null
else
  mold_backup_query_veeam_restores 10 >/dev/null
fi
'''
        env = dict(os.environ, TEST_LIBRARY=str(ROOT / "veeam/mold-backup.lib.sh"), TEST_CAPTURE=str(capture),
                   TEST_PROOF=str(proof).lower(), TEST_TASK=task, TEST_PARENT=parent, TZ="Asia/Seoul", ProgramData=str(self.base / "program-data"))
        generated = subprocess.run(["bash", "-c", shell], env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, generated.returncode, generated.stderr)
        fixture = self.base / "fixture.json"
        fixture.write_text(json.dumps(self.fixture))
        script = self.base / "run.ps1"
        script.write_text(FIXTURE + "\n" + capture.read_text())
        env["TEST_FIXTURE"] = str(fixture)
        return subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(script)],
                              env=env, capture_output=True, text=True, timeout=20)

    def discover(self):
        result = self.run_powershell()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return [line.split("|") for line in result.stdout.splitlines() if "|" in line]

    def test_completed_copy_is_discovered_while_browser_is_open(self):
        rows = self.discover()
        self.assertEqual(1, len(rows))
        self.assertEqual(TASK, rows[0][0])
        self.assertEqual(self.end, int(rows[0][2]))
        self.assertEqual("Success", rows[0][3])
        self.assertEqual(POINT, rows[0][6])
        self.assertEqual(PARENT, rows[0][8])

    def test_browsing_alone_does_not_request_a_vm_restore(self):
        self.parent.update(Tasks=[], Result="Success", IsCompleted=True)
        self.assertEqual([], self.discover())

    def test_pending_failed_canceled_and_unknown_tasks_are_not_success(self):
        for status in ("None", "InProgress", "Failed", "Canceled", "Unknown", ""):
            with self.subTest(status=status):
                self.task["Status"] = status
                self.task["Info"]["Status"] = status
                self.assertEqual([], self.discover())

    def test_success_requires_real_end_time(self):
        self.task["Info"]["EndTime"] = "1900-01-01T00:00:00"
        self.assertEqual([], self.discover())

    def test_copy_from_another_browser_is_rejected(self):
        self.task["JobSessId"] = POINT
        self.assertEqual([], self.discover())
        self.assertNotEqual(0, self.run_powershell(proof=True).returncode)

    def test_recent_window_uses_copy_end_time_not_browser_creation(self):
        self.task["Info"].update(CreationTime=self.local(time.time() - 2000), EndTime=self.local(time.time() - 1900))
        self.assertEqual([], self.discover())

    def test_completed_parent_does_not_duplicate_copy_or_bypass_pending_task_via_audit(self):
        self.parent.update(Result="Success", IsCompleted=True, EndTime=self.local(self.end), EndTimeUTC=datetime.datetime.fromtimestamp(
            self.end, datetime.timezone.utc).isoformat())
        self.fixture["audit"] = [dict(Operation="FileRestore", Result="Success", SessionUid=PARENT, Details="",
                                      Time=self.local(self.end))]
        self.assertEqual([TASK], [row[0] for row in self.discover()])
        self.task["Status"] = "InProgress"
        self.assertEqual([], self.discover())

    def test_multiple_copies_in_one_browser_keep_distinct_task_ids(self):
        second = copy.deepcopy(self.task)
        second["Id"] = second["Info"]["Id"] = POINT
        self.parent["Tasks"].append(second)
        self.assertEqual([TASK, POINT], [row[0] for row in self.discover()])

    def test_management_owned_copy_is_not_replayed_before_database_callback_commit(self):
        receipt_dir = self.base / "program-data/ABLESTACK/Mold/volume-restore"
        receipt_dir.mkdir(parents=True)
        (receipt_dir / "restore-test-0.json").write_text(json.dumps(dict(
            requestId="restore-test", binding="request|host|address", callbackJobIds=[PARENT], jobId=None)))
        self.assertEqual([], self.discover())

    def test_completion_proof_reads_exact_copy_and_local_task_times(self):
        result = self.run_powershell(proof=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        proof = json.loads(next(line.removeprefix("ABLESTACK_JSON:") for line in result.stdout.splitlines() if line.startswith("ABLESTACK_JSON:")))
        self.assertEqual(TASK, proof["jobId"])
        self.assertEqual(PARENT, proof["parentSessionId"])
        self.assertEqual("Success", proof["result"])
        self.assertEqual(self.start, proof["startedAt"])
        self.assertEqual(self.end, proof["endedAt"])

    def test_task_utc_properties_are_preferred_over_local_times(self):
        self.task["Info"].update(
            CreationTimeUTC=datetime.datetime.fromtimestamp(self.start, datetime.timezone.utc).replace(tzinfo=None).isoformat(),
            EndTimeUTC=datetime.datetime.fromtimestamp(self.end, datetime.timezone.utc).replace(tzinfo=None).isoformat())
        self.assertEqual(self.end, int(self.discover()[0][2]))
        result = self.run_powershell(proof=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        proof = json.loads(next(line.removeprefix("ABLESTACK_JSON:") for line in result.stdout.splitlines() if line.startswith("ABLESTACK_JSON:")))
        self.assertEqual(self.start, proof["startedAt"])
        self.assertEqual(self.end, proof["endedAt"])

    def test_warning_proof_uses_copy_log_not_unrelated_parent_errors(self):
        self.task["Status"] = "Warning"
        self.task["Log"] = [dict(Status="EWarning", Title="Cannot set file permissions", Description="domain-config.xml")]
        self.parent["Log"] = [dict(Status="EFailed", Title="Unrelated previous task failed", Description="")]
        result = self.run_powershell(proof=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        proof = json.loads(next(line.removeprefix("ABLESTACK_JSON:") for line in result.stdout.splitlines() if line.startswith("ABLESTACK_JSON:")))
        self.assertEqual("Warning", proof["result"])
        self.assertEqual(1, len(proof["details"]))
        self.assertEqual("EWarning", proof["details"][0]["status"])

    def test_pending_copy_has_no_completion_proof(self):
        self.task["Status"] = "InProgress"
        self.assertNotEqual(0, self.run_powershell(proof=True).returncode)

    def test_legacy_terminal_session_without_copy_task_api_is_supported(self):
        self.parent.update(HasTaskGetter=False, Result="Success", IsCompleted=True, EndTime=self.local(self.end),
                           EndTimeUTC=datetime.datetime.fromtimestamp(self.end, datetime.timezone.utc).isoformat())
        self.assertEqual([PARENT], [row[0] for row in self.discover()])
        self.assertEqual(0, self.run_powershell(proof=True, parent="", task=PARENT).returncode)

    def run_management_helpers(self, parent=PARENT):
        # Read the actual Java-generated helper commands rather than duplicating
        # the implementation in the fixture. Compilation is checked separately.
        source = ROOT.parents[3] / "plugins/backup/ablestack-veeam/src/main/java/org/apache/cloudstack/backup/ablestackveeam/AblestackVeeamSshClient.java"
        body = source.read_text().split("static String volumeRestoreTaskCommands() {", 1)[1].split(
            "public org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result restoreVolumeArtifact(", 1)[0]
        literals = [json.loads(value) for value in re.findall(r'"(?:\\.|[^"\\])*"', body)]
        self.assertEqual("\n", literals[0])
        fixture = self.base / "fixture.json"
        fixture.write_text(json.dumps(self.fixture))
        script = self.base / "management.ps1"
        script.write_text(FIXTURE + "\n" + "\n".join(literals[1:]) +
                          f"\n$task=Find-RestoreTask '{TASK}' '{parent}'\nRestore-TaskResult $task\n")
        return subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(script)],
                              env=dict(os.environ, TEST_FIXTURE=str(fixture), TZ="Asia/Seoul"),
                              capture_output=True, text=True, timeout=20)

    def test_management_file_restore_reads_task_status_without_result_property(self):
        result = self.run_management_helpers()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("Success", result.stdout.strip())

    def test_management_can_recover_old_task_receipt_without_parent_hint(self):
        result = self.run_management_helpers(parent="")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("Success", result.stdout.strip())

    def test_management_recovery_rejects_another_tasks_parent(self):
        self.task["JobSessId"] = POINT
        self.assertNotEqual(0, self.run_management_helpers().returncode)


if __name__ == "__main__":
    unittest.main()
