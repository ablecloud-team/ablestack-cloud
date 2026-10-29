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

# The caller persists protocol/routing before SUBMIT and exact catalog IDs before COMMIT.
# A durable owner spans separate SSH calls, from submission through confirmed Detach.
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:ProgramData 'ABLESTACK/Mold/host-job-transfer'
[IO.Directory]::CreateDirectory($root) | Out-Null
if ($moldPlan.jobName -cnotmatch '^ABLESTACK-H-[a-f0-9]{32}$') { throw 'Invalid fixed Host Job name' }
$ownerPath = Join-Path $root ($moldPlan.jobName + '.owner.json')
$lockPath = Join-Path $root ($moldPlan.jobName + '.lock')
$receiptPath = $null
if ($moldPlan.operation -ne 'READY') {
    $null = [guid]::Parse($moldPlan.key)
    $receiptPath = Join-Path $root ($moldPlan.key + '.json')
}

function Write-Result($value) { Write-Output ('ABLESTACK_JSON:' + ($value | ConvertTo-Json -Depth 20 -Compress)) }
function Save-Json($path, $value) {
    $tmp = $path + '.tmp'
    $bytes = [Text.Encoding]::UTF8.GetBytes(($value | ConvertTo-Json -Depth 20 -Compress))
    $stream = [IO.File]::Open($tmp, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    if ([IO.File]::Exists($path)) {
        [IO.File]::Replace($tmp, $path, [System.Management.Automation.Language.NullString]::Value)
    } else { [IO.File]::Move($tmp, $path) }
}
function Read-Json($path) {
    if ([IO.File]::Exists($path)) { return [IO.File]::ReadAllText($path) | ConvertFrom-Json -AsHashtable }
    return $null
}
function Read-Job {
    $jobs = @(Get-VBRComputerBackupJob -ErrorAction Stop | Where-Object { $_.Name -ceq $moldPlan.jobName })
    if ($jobs.Count -gt 1) { throw 'Fixed Host Job name is ambiguous' }
    if ($jobs.Count -eq 1) {
        $job = $jobs[0]
        if ([string]$job.Description -cne ('Mold fixed Host transfer ' + $moldPlan.jobName)) { throw 'Fixed Host Job ownership differs' }
        if ($receipt -and $receipt.hostJobId -and [string]$job.Id -ne $receipt.hostJobId) { throw 'Fixed Host Job identity changed' }
        return $job
    }
    return $null
}
function Read-Sessions($job) {
    return @(Get-VBRComputerBackupJobSession -ErrorAction Stop | Where-Object { [string]$_.JobId -eq [string]$job.Id })
}
function Assert-Idle($job) {
    if (@(Read-Sessions $job | Where-Object { [string]$_.State -ne 'Stopped' }).Count -ne 0) { throw 'Fixed Host Job is still active' }
}
function Read-Attached($job) {
    return @(Get-VBRBackup -ErrorAction Stop | Where-Object { [string]$_.JobId -eq [string]$job.Id })
}
function Read-ExactBackup($id) {
    $items = @(Get-VBRBackup -ErrorAction Stop | Where-Object { [string]$_.Id -eq $id })
    if ($items.Count -gt 1) { throw 'Backup container identity is ambiguous' }
    if ($items.Count -eq 1) { return $items[0] }
    return $null
}
function Assert-Detached($backup) {
    $id = [string]$backup.JobId
    if ($id -and $id -ne [guid]::Empty.ToString()) { throw 'Backup is still associated with a Job; independence is unconfirmed' }
}
function Assert-Point($backup, $pointId) {
    $points = @(Get-VBRRestorePoint -Backup $backup -ErrorAction Stop)
    if ($points.Count -ne 1 -or [string]$points[0].Id -ne $pointId) { throw 'Independent backup must contain exactly the saved restore point' }
    return $points[0]
}
function Assert-SavedReference {
    if (-not $receipt -or $receipt.phase -notin @('SUCCEEDED', 'DETACHING', 'DETACHED')) { throw 'Successful receipt is missing' }
    foreach ($pair in @(@('jobId','jobId'), @('hostJobId','externalJobId'), @('backupId','externalBackupId'), @('externalId','externalId'))) {
        if (-not $receipt[$pair[0]] -or $receipt[$pair[0]] -ne $moldPlan.artifact.($pair[1])) { throw 'Committed catalog reference differs from the receipt' }
    }
}
function Clear-Owner {
    $current = Read-Json $ownerPath
    if ($current -and $current.binding -ceq $moldPlan.binding -and $current.key -eq $moldPlan.key) { [IO.File]::Delete($ownerPath) }
}
function Return-Receipt {
    if ($receipt.phase -eq 'REJECTED') { Write-Result @{notSubmitted=$true; failure=$receipt.failure}; return }
    Write-Result @{jobId=$receipt.jobId; hostJobId=$receipt.hostJobId; backupId=$receipt.backupId;
        externalId=$receipt.externalId; backupTime=$receipt.backupTime; completed=($receipt.phase -in @('SUCCEEDED','DETACHING','DETACHED'));
        terminal=($receipt.phase -in @('SUCCEEDED','DETACHING','DETACHED','FAILED','DETACHED_FAILED')); failure=$receipt.failure}
}

$receipt = $null
$mutex = $null
try {
    try { $mutex = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch [IO.IOException] {
        if ($moldPlan.operation -eq 'READY') { Write-Result @{ready=$false}; return }
        # No busy proof: another invocation of this same artifact could already have submitted.
        throw 'Fixed Host Job control is busy; retry without resubmitting'
    }
    $owner = Read-Json $ownerPath
    if ($receiptPath) {
        $receipt = Read-Json $receiptPath
        if ($receipt -and ($receipt.binding -cne $moldPlan.binding -or $receipt.protocol -cne $moldPlan.protocol)) { throw 'Artifact receipt binding differs' }
    }
    $job = $null
    if ($moldPlan.operation -notin @('EXISTS','DELETE')) { $job = Read-Job }

    if ($moldPlan.operation -eq 'READY') {
        foreach ($name in @('Copy-VBRComputerBackupJob','Detach-VBRBackup','New-VBRSelectedFilesBackupOptions','Start-VBRComputerBackupJob')) {
            Get-Command $name -ErrorAction Stop | Out-Null
        }
        if (-not (Get-Command Set-VBRComputerBackupJob -ErrorAction Stop).Parameters.ContainsKey('UseSnapshotlessFileLevelBackup')) {
            throw 'Linux staging requires snapshot-less file-level backup support'
        }
        $templates = @(Get-VBRComputerBackupJob -ErrorAction Stop | Where-Object { $_.Name -ceq $moldPlan.template })
        if ($templates.Count -ne 1) { throw 'One scheduling Linux Agent Job is required' }
        $busy = @(Read-Sessions $templates[0] | Where-Object { [string]$_.State -ne 'Stopped' }).Count -ne 0
        # This gate proves only that the scheduling Job/hooks finished. Transfer ownership
        # is checked at SUBMIT; a failed artifact must still be able to poll its own Job.
        Write-Result @{ready=(-not $busy)}
        return
    }

    if ($moldPlan.operation -in @('EXISTS','DELETE')) {
        if ($moldPlan.operation -eq 'DELETE' -and -not $moldPlan.artifact.completed) {
            if (-not $receipt -or $receipt.phase -ne 'DETACHED_FAILED' -or -not $moldPlan.artifact.externalTerminal -or
                    -not $receipt.jobId -or $receipt.jobId -ne $moldPlan.artifact.jobId -or
                    $receipt.hostJobId -ne $moldPlan.artifact.externalJobId -or $receipt.backupId -ne $moldPlan.artifact.externalBackupId) {
                throw 'Failed backup deletion requires its exact terminal and detached receipt'
            }
            if ($receipt.backupAbsent -and -not $receipt.backupId) { Write-Result @{deleted=$true}; return }
            $backup = Read-ExactBackup ([guid]::Parse($receipt.backupId).ToString())
            if ($backup) {
                Assert-Detached $backup
                if (@(Get-VBRRestorePoint -Backup $backup -ErrorAction Stop).Count -gt 1) { throw 'Failed backup has more than one artifact point' }
                if ($moldPlan.expiration) { throw 'Failed backup is still retained by Veeam' }
                Remove-VBRBackup -Backup $backup -FromDisk -Confirm:$false | Out-Null
                if (Read-ExactBackup $receipt.backupId) { throw 'Failed backup deletion has not completed' }
            }
            Write-Result @{deleted=$true}; return
        }
        $backupId = [guid]::Parse($moldPlan.artifact.externalBackupId).ToString()
        $pointId = [guid]::Parse($moldPlan.artifact.externalId).ToString()
        $backup = Read-ExactBackup $backupId
        if (-not $backup) {
            $moved = @(Get-VBRBackup -ErrorAction Stop | Get-VBRRestorePoint -ErrorAction Stop | Where-Object { [string]$_.Id -eq $pointId })
            if ($moved.Count -ne 0) { throw 'Saved point exists under a different backup container' }
            Write-Result @{exists=$false; deleted=$true}; return
        }
        $null = Assert-Point $backup $pointId
        if ($moldPlan.operation -eq 'EXISTS') { Write-Result @{exists=$true}; return }
        Assert-Detached $backup
        if ($moldPlan.expiration) { throw 'Artifact is still retained by Veeam; expiration must not erase it' }
        Remove-VBRBackup -Backup $backup -FromDisk -Confirm:$false | Out-Null
        if (Read-ExactBackup $backupId) { throw 'Exact backup deletion has not completed' }
        Write-Result @{deleted=$true}; return
    }

    if ($moldPlan.operation -eq 'COMMIT') {
        Assert-SavedReference
        if (-not $job) { throw 'Fixed Host Job is missing' }
        $backup = Read-ExactBackup $receipt.backupId
        if (-not $backup) { throw 'Committed backup container is missing' }
        $null = Assert-Point $backup $receipt.externalId
        if ($receipt.phase -ne 'DETACHED') {
            if (-not $owner -or $owner.binding -cne $moldPlan.binding -or $owner.key -ne $moldPlan.key) { throw 'Detach requires this artifact to own the Host Job' }
            Assert-Idle $job
            $attached = @(Read-Attached $job)
            if ($attached.Count -gt 1 -or ($attached.Count -eq 1 -and [string]$attached[0].Id -ne $receipt.backupId)) { throw 'Host Job contains another artifact backup' }
            $receipt.phase = 'DETACHING'; Save-Json $receiptPath $receipt
            # Veeam's warning prompt needs Force in addition to the common Confirm switch.
            if ($attached.Count -eq 1) { Detach-VBRBackup -Backup $backup -Force -Confirm:$false | Out-Null }
            $backup = Read-ExactBackup $receipt.backupId
            if (-not $backup) { throw 'Detach lost the committed backup container' }
            $null = Assert-Point $backup $receipt.externalId
            Assert-Detached $backup
            if (-not (Read-Job)) { throw 'Fixed Host Job disappeared during Detach' }
            $receipt.phase = 'DETACHED'; Save-Json $receiptPath $receipt
        } else { Assert-Detached $backup }
        Clear-Owner
        Write-Result @{retired=$true; externalId=$receipt.externalId}; return
    }

    if ($moldPlan.operation -eq 'ABORT') {
        if (-not $receipt) {
            if ($moldPlan.artifact.jobId -or ($owner -and $owner.binding -ceq $moldPlan.binding)) { throw 'Cannot release an accepted Host Job without its submission receipt' }
            Write-Result @{released=$true}; return
        }
        if ($receipt.phase -in @('DETACHED','DETACHED_FAILED','REJECTED')) { Clear-Owner; Write-Result @{released=$true}; return }
        if (-not $job -or -not $owner -or $owner.binding -cne $moldPlan.binding) { throw 'Failed artifact ownership is unconfirmed' }
        Assert-Idle $job
        if ($receipt.phase -notin @('FAILED','PREPARING')) { throw 'Submission is unconfirmed or successful; reconcile it before releasing the Host Job' }
        $attached = @(Read-Attached $job)
        if ($attached.Count -gt 1) { throw 'Failed artifact backup is ambiguous' }
        if ($attached.Count -eq 1) {
            if (-not $receipt.backupId -or [string]$attached[0].Id -ne $receipt.backupId) { throw 'Failed artifact backup identity is unconfirmed' }
            Detach-VBRBackup -Backup $attached[0] -Force -Confirm:$false | Out-Null
            $backup = Read-ExactBackup $receipt.backupId
            if (-not $backup) { throw 'Failed backup was lost during Detach' }
            Assert-Detached $backup
        }
        $receipt.backupAbsent = -not $receipt.backupId
        $receipt.phase = 'DETACHED_FAILED'; Save-Json $receiptPath $receipt
        Clear-Owner; Write-Result @{released=$true}; return
    }

    if ($moldPlan.operation -notin @('SUBMIT','RECOVER')) { throw 'Unknown Host Job operation' }
    $fresh = $false
    if (-not $receipt) {
        if ($moldPlan.artifact.jobId) { throw 'Accepted session has lost its receipt; reconcile without resubmitting' }
        if ($owner -and $owner.binding -ceq $moldPlan.binding) { throw 'Host Job owner exists without its receipt; reconcile without resubmitting' }
        if ($moldPlan.operation -eq 'RECOVER') {
            # Under the exclusive lock, absence proves Start was never reached by this protocol:
            # every call writes its durable receipt before invocation, and receipts are retained.
            Write-Result @{busy=$true;notSubmitted=$true;failure='No submission receipt exists; retry Host Job admission'}; return
        }
        if ($owner) {
            if ($owner.binding -ceq $moldPlan.binding) { throw 'Host Job owner exists without its receipt; reconcile without resubmitting' }
            Write-Result @{busy=$true;notSubmitted=$true;failure='Waiting for the previous Host artifact to be detached'}; return
        }
        $receipt = @{protocol=$moldPlan.protocol;binding=$moldPlan.binding;phase='PREPARING';jobId=$null;failure=$null}
        Save-Json $receiptPath $receipt
        Save-Json $ownerPath @{binding=$moldPlan.binding;key=$moldPlan.key}
        $fresh = $true
    }
    if ($receipt.phase -in @('SUCCEEDED','DETACHING','DETACHED','FAILED','DETACHED_FAILED','REJECTED')) { Return-Receipt; return }
    if (-not $owner -and -not $fresh) { throw 'Pending artifact has no Host Job owner; reconcile without resubmitting' }
    if ($owner -and ($owner.binding -cne $moldPlan.binding -or $owner.key -ne $moldPlan.key)) { throw 'Another artifact owns the Host Job' }

    if ($receipt.phase -eq 'PREPARING') {
        try {
            if (-not $fresh) { throw 'Preparation was interrupted before submission' }
            $templates = @(Get-VBRComputerBackupJob -ErrorAction Stop | Where-Object { $_.Name -ceq $moldPlan.template })
            if ($templates.Count -ne 1) { throw 'One scheduling Linux Agent Job is required' }
            Assert-Idle $templates[0]
            if (-not $job) {
                Copy-VBRComputerBackupJob -Job $templates[0] -Name $moldPlan.jobName -Description ('Mold fixed Host transfer ' + $moldPlan.jobName) | Out-Null
                $job = Read-Job
            }
            if (-not $job) { throw 'Fixed Host Job creation is unconfirmed' }
            $receipt.hostJobId = [string]$job.Id; Save-Json $receiptPath $receipt
            Assert-Idle $job
            if (@(Read-Attached $job).Count -ne 0) { throw 'Previous Host backup has not been detached' }
            if ([string]$job.RetentionType -ne 'RestoreDays') { throw ('Fixed Host Job requires Days retention (RestoreDays); actual type: ' + [string]$job.RetentionType) }
            $computers = @(Get-VBRDiscoveredComputer -ErrorAction Stop |
                Where-Object { (@($_.IPAddress)+@($_.IpAddresses)) -contains $moldPlan.sourceIp } |
                Group-Object { [string]$_.ObjectId } | ForEach-Object { $_.Group[0] })
            if ($computers.Count -ne 1 -or -not $computers[0].ObjectId) { throw 'Source Host must resolve to one discovered Linux Agent computer' }
            $file = [string]$moldPlan.artifact.path
            if ($moldPlan.metadata) { $files = New-VBRSelectedFilesBackupOptions -OSPlatform Linux -BackupSelectedFiles -SelectedFiles @($file) }
            else {
                $slash = $file.LastIndexOf('/')
                if ($slash -lt 1 -or $slash -eq $file.Length-1) { throw 'Invalid Linux artifact file path' }
                $files = New-VBRSelectedFilesBackupOptions -OSPlatform Linux -BackupSelectedFiles -SelectedFiles @($file.Substring(0,$slash)) -IncludeMask @($file.Substring($slash+1))
            }
            $scripts = New-VBRJobScriptOptions
            Set-VBRComputerBackupJob -Job $job -BackupObject $computers[0] -BackupType SelectedFiles -SelectedFilesOptions $files -ScriptOptions $scripts -EnableSchedule:$false -UseSnapshotlessFileLevelBackup | Out-Null
            $receipt.baseline = @(Read-Sessions $job | Sort-Object CreationTime -Descending | Select-Object -First 16 | ForEach-Object { [string]$_.Id })
        } catch {
            $failure = $_.Exception.Message
            # Disable a partially cloned Job before declaring that no work was submitted.
            $job = Read-Job
            if ($job) {
                Set-VBRComputerBackupJob -Job $job -EnableSchedule:$false -ScriptOptions (New-VBRJobScriptOptions) | Out-Null
                Assert-Idle $job
                if (@(Read-Attached $job).Count -ne 0) { throw 'Preparation failed with an attached backup; preserve owner for reconciliation' }
            }
            $receipt.phase='REJECTED'; $receipt.failure=$failure; Save-Json $receiptPath $receipt
            Clear-Owner; Return-Receipt; return
        }
        $receipt.issuedAt = [DateTime]::UtcNow.ToString('o')
        $receipt.phase = 'SUBMITTING'; Save-Json $receiptPath $receipt
        $sessions = @(Start-VBRComputerBackupJob -Job $job -FullBackup -RunAsync)
        if ($sessions.Count -ne 1) { throw 'Host Job submission returned an ambiguous session; recover without resubmitting' }
        $receipt.jobId = [string]$sessions[0].Id
        $receipt.phase='RUNNING'; Save-Json $receiptPath $receipt
    }
    if (-not $job) { throw 'Pending fixed Host Job is missing' }
    $sessions = @(Read-Sessions $job)
    if ($receipt.phase -eq 'SUBMITTING') {
        $sessions = @($sessions | Where-Object { $receipt.baseline -notcontains [string]$_.Id -and $_.CreationTime.ToUniversalTime() -ge [DateTime]::Parse($receipt.issuedAt).ToUniversalTime() })
        if ($sessions.Count -eq 0) { Write-Result @{jobId=$null}; return }
        if ($sessions.Count -ne 1) { throw 'Host Job recovery session is ambiguous' }
        $receipt.jobId=[string]$sessions[0].Id; $receipt.phase='RUNNING'; Save-Json $receiptPath $receipt
    } else { $sessions = @($sessions | Where-Object { [string]$_.Id -eq $receipt.jobId }) }
    if ($sessions.Count -ne 1 -or [string]$sessions[0].JobId -ne $receipt.hostJobId) { throw 'Session does not match the exact Host Job' }
    $session = $sessions[0]
    if ([string]$session.State -eq 'Stopped') {
        $backups = @(Read-Attached $job)
        if ($backups.Count -gt 1) { throw 'Host Job contains ambiguous backups' }
        if ($backups.Count -eq 1) { $receipt.backupId=[string]$backups[0].Id }
        if ([string]$session.Result -ne 'Success') { $receipt.phase='FAILED'; $receipt.failure='Host artifact backup failed: '+[string]$session.Result }
        elseif ($backups.Count -eq 1) {
            $points = @(Get-VBRRestorePoint -Backup $backups[0] -ErrorAction Stop)
            if ($points.Count -gt 1) { throw 'Host artifact requires exactly one Full restore point' }
            if ($points.Count -eq 1) {
                $receipt.externalId=[string]$points[0].Id; $receipt.backupTime=$points[0].CreationTime.ToUniversalTime().ToString('o'); $receipt.phase='SUCCEEDED'
            }
        }
        Save-Json $receiptPath $receipt
    }
    Return-Receipt
} finally { if ($mutex) { $mutex.Dispose() } }
