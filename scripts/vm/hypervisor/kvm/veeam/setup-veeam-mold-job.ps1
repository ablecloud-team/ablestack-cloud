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

# One-shot: KVM hypervisor host backup (Linux Agent on 10.10.31.2).
#   - Backup scope: /tmp/mold/veeam (VM disk export staging from Mold pre-script)
#   - Pre/Post: Guest Processing on KVM Agent (ablestack_veeam_pre/post_notify.sh)
#
# Run on Veeam B&R server (Administrator, PowerShell 7+):
#   .\setup-veeam-mold-job.ps1
#   .\setup-veeam-mold-job.ps1 -ConfPath C:\ProgramData\Mold\backup\veeam\mold-backup.windows.conf
#   .\setup-veeam-mold-job.ps1 -StartJob
#
# Or from Mac/ccvm: bash push-to-veeam.sh --env-file mold-backup.env

param(
    [string]$ConfPath = "",
    [string]$InstallDir = "C:\ProgramData\Mold\backup\veeam",

    [string]$JobName = "",
    [string]$KvmHost = "",
    [string]$AgentHostName = "",
    [string]$BackupPath = "",
    [string]$RepositoryName = "",
    [string]$ProtectionGroupName = "",

    [switch]$StartJob,
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"

if (-not $ConfPath) {
    $ConfPath = $env:MOLD_BACKUP_WINDOWS_CONF
    if (-not $ConfPath) {
        $ConfPath = Join-Path $InstallDir "mold-backup.windows.conf"
    }
}

function Read-MoldWindowsConf {
    param([string]$Path)
    $cfg = @{}
    if (-not (Test-Path $Path)) { return $cfg }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*#' -or $line -eq '') { return }
        if ($line -match '^([^=]+)=(.*)$') {
            $cfg[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"')
        }
    }
    return $cfg
}

$cfg = Read-MoldWindowsConf -Path $ConfPath

if (-not $JobName) { $JobName = $cfg["VEEAM_JOB_NAME"] }
if (-not $KvmHost) { $KvmHost = $cfg["KVM_HOST"] }
if (-not $BackupPath) {
    $BackupPath = $cfg["VEEAM_HOST_BACKUP_PATH"]
}
if (-not $BackupPath) { $BackupPath = "/tmp/mold/veeam" }
if ($BackupPath -match '^[A-Za-z]:[\\/]') {
    throw @"
BackupPath must be a Linux path on the KVM agent (e.g. /tmp/mold/veeam), not a Windows path: $BackupPath

STAGING_PATH in mold-backup.windows.conf is for Windows VM disk export (FLR), not Agent SelectedFiles.
Set VEEAM_HOST_BACKUP_PATH=/tmp/mold/veeam in $ConfPath, or pass -BackupPath '/tmp/mold/veeam'.
"@
}
if (-not $RepositoryName) { $RepositoryName = $cfg["VEEAM_REPO_NAME"] }
if (-not $AgentHostName) { $AgentHostName = $cfg["KVM_HOSTNAME"] }
if (-not $ProtectionGroupName) { $ProtectionGroupName = $cfg["VEEAM_PROTECTION_GROUP_NAME"] }
if (-not $ProtectionGroupName) { $ProtectionGroupName = "Mold KVM Agents" }

if (-not $JobName) { throw "JobName required (VEEAM_JOB_NAME in $ConfPath or -JobName)" }
if (-not $KvmHost) { throw "KvmHost required (KVM_HOST in $ConfPath or -KvmHost)" }

$preScript = $cfg["KVM_PRE_NOTIFY_SCRIPT"]
if (-not $preScript) { $preScript = "/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh" }
$postScript = $cfg["KVM_POST_NOTIFY_SCRIPT"]
if (-not $postScript) { $postScript = "/etc/ablestack/veeam/ablestack_veeam_post_notify.sh" }

$kvmSshUser = $cfg["KVM_SSH_USER"]
if (-not $kvmSshUser) { $kvmSshUser = "root" }
$kvmSshPassword = $cfg["KVM_SSH_PASSWORD"]
if (-not $kvmSshPassword) { $kvmSshPassword = $env:MOLD_KVM_SSH_PASSWORD }
if (-not $kvmSshPassword) {
    throw @"
KVM_SSH_PASSWORD is required for Linux Agent backup on $KvmHost.

Veeam fails with: Cannot find credentials for agent $KvmHost

Set KVM_SSH_PASSWORD in $ConfPath (or MOLD_KVM_SSH_PASSWORD env) and re-run.
"@
}

Write-Host "=== Mold Veeam setup (KVM hypervisor host — Agent on $KvmHost) ==="
Write-Host "  Job      : $JobName"
Write-Host "  KVM      : $KvmHost  (Linux Agent on hypervisor — backs up VM export staging)"
Write-Host "  Agent    : $AgentHostName"
Write-Host "  Scope    : $BackupPath (SelectedFiles — VM disks exported here by Mold Pre-script)"
Write-Host "  Pre      : $preScript"
Write-Host "  Post     : $postScript"
Write-Host "  PG       : $ProtectionGroupName"
if ($cfg["VM_NAME"]) {
    Write-Host "  VM       : $($cfg['VM_NAME']) (set on KVM via veeam_config.sh --vm-include)"
} else {
    Write-Host "  VM       : (set on KVM) veeam_config.sh --vm-include 'i-2-7-VM'"
}
Write-Host "  Conf     : $ConfPath"
Write-Host ""

$createScript = Join-Path $InstallDir "create-veeam-agent-job.ps1"
if (-not (Test-Path $createScript)) {
    $createScript = Join-Path $PSScriptRoot "create-veeam-agent-job.ps1"
}
if (-not (Test-Path $createScript)) {
    throw "create-veeam-agent-job.ps1 not found. Run push-to-veeam.sh first."
}

$createArgs = @{
    JobName               = $JobName
    KvmHost               = $KvmHost
    BackupPath            = $BackupPath
    InstallDir            = $InstallDir
    AgentPreNotifyScript  = $preScript
    AgentPostNotifyScript = $postScript
}
if ($AgentHostName) { $createArgs["AgentHostName"] = $AgentHostName }
if ($RepositoryName) { $createArgs["RepositoryName"] = $RepositoryName }
$createArgs["ProtectionGroupName"] = $ProtectionGroupName
$createArgs["KvmSshUser"] = $kvmSshUser
$createArgs["KvmSshPassword"] = $kvmSshPassword
if ($WhatIf) { $createArgs["WhatIf"] = $true }

& $createScript @createArgs

if ($WhatIf) { exit 0 }

if ($StartJob) {
    Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
    $job = Get-VBRComputerBackupJob -Name $JobName -ErrorAction SilentlyContinue
    if (-not $job) { throw "Job not found after create: $JobName" }
    Write-Host "Starting backup job: $JobName"
    Start-VBRComputerBackupJob -Job $job | Out-Null
    Write-Host "Job started. Check KVM: tail -f /var/log/mold/veeam-hook.log"
}

Write-Host ""
Write-Host "Done. Veeam backs up VM exports under $BackupPath on KVM hypervisor $KvmHost ($AgentHostName)."
Write-Host "On KVM, set VMs on this host:"
Write-Host "  /etc/ablestack/veeam/veeam_config.sh --job-name '$JobName' --backup-mode host --vm-include 'i-2-7-VM'"
