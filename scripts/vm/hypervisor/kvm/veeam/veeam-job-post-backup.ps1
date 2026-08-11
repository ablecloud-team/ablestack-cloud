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

# Veeam Backup Job post-script: invoke KVM post-notify (NetBackup bpend equivalent) over SSH.

$ErrorActionPreference = "Stop"

$ConfPath = $env:MOLD_BACKUP_WINDOWS_CONF
if (-not $ConfPath) {
    $ConfPath = "C:\ProgramData\Mold\backup\veeam\mold-backup.windows.conf"
}

function Read-MoldConf {
    param([string]$Path)
    $cfg = @{}
    if (-not (Test-Path $Path)) { throw "Config not found: $Path" }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*#' -or $line -eq '') { return }
        if ($line -match '^([^=]+)=(.*)$') {
            $cfg[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"')
        }
    }
    return $cfg
}

$cfg = Read-MoldConf -Path $ConfPath
$kvmHost = $cfg["KVM_HOST"]
$kvmUser = $cfg["KVM_SSH_USER"]
$jobName = $cfg["VEEAM_JOB_NAME"]
if (-not $kvmHost -or -not $kvmUser) {
    throw "KVM_HOST and KVM_SSH_USER are required in $ConfPath"
}
if (-not $jobName) { throw "VEEAM_JOB_NAME is required in $ConfPath" }

$postScript = $cfg["KVM_POST_NOTIFY_SCRIPT"]
if (-not $postScript) { $postScript = "/etc/ablestack/veeam/ablestack_veeam_post_notify.sh" }

$sshKey = $cfg["KVM_SSH_KEY"]
$sshArgs = @()
if ($sshKey) { $sshArgs += @("-i", $sshKey) }

$remote = "$kvmUser@$kvmHost"
$vmName = $cfg["VM_NAME"]
$vmEnv = ""
if ($vmName) {
    $vmEnv = "VM_INCLUDE='$vmName' VM_NAME='$vmName' "
}
Write-Host "Running post-notify on $remote : $postScript (job=$jobName vm=$vmName)"
& ssh @sshArgs $remote "${vmEnv}bash '$postScript' '$(hostname)' '$jobName'"
if ($LASTEXITCODE -ne 0) { throw "post-notify failed with exit code $LASTEXITCODE" }

exit 0
