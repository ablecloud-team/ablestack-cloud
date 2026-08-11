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

# Run on Veeam Backup & Replication server (Administrator PowerShell).
# 1) Copies Mold Veeam scripts + default config to ProgramData
# 2) Registers Pre-job / Post-job on a Veeam Agent backup job (Guest Processing scripts)
#
# Example:
#   .\install-veeam-job.ps1 -JobName "Rocky Agent Backup" -SourceDir "\\git\...\veeam"
#   .\install-veeam-job.ps1 -JobName "Rocky Agent Backup" -VmName "rocky94" -StagingPath "D:\veeam-staging\rocky94" -KvmHost "10.0.0.10"

param(
    [Parameter(Mandatory = $true)]
    [string]$JobName,

    [string]$SourceDir = $PSScriptRoot,

    [string]$InstallDir = "C:\ProgramData\Mold\backup\veeam",

    [string]$VmName = "",
    [string]$StagingPath = "",
    [string]$KvmHost = "",
    [string]$KvmSshUser = "root",
    [string]$KvmSshKey = "",

    # Linux Agent jobs run pre/post scripts ON THE AGENT HOST (bash), not on this Windows server.
    [switch]$LinuxAgent,
    [string]$AgentPreNotifyScript = "/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh",
    [string]$AgentPostNotifyScript = "/etc/ablestack/veeam/ablestack_veeam_post_notify.sh",
    [string]$ProtectionGroupName = "Mold KVM Agents",

    # Guest VM mode: pre/post SSH to KVM for Mold; scripts run ON the guest agent.
    [switch]$GuestVm,
    [string]$VmLibvirtName = "",
    [string]$VmIp = "",
    $DiscoveredComputer = $null,

    # Only generate the pre/post wrapper scripts into $InstallDir on this Veeam
    # server. Skips Veeam module load, job lookup, and job registration so you
    # can point the Veeam UI Guest Processing scripts at the generated files.
    [switch]$ScriptsOnly,

    [switch]$EnableRestoreScript
)

$ErrorActionPreference = "Stop"

function Get-MoldVeeamGuestWrapperPath {
    param(
        [string]$InstallDir,
        [string]$Phase,
        [string]$VmLibvirtName
    )
    $safeVm = ($VmLibvirtName -replace '[^\w\-]', '_')
    return (Join-Path $InstallDir "mold-guest-${Phase}-${safeVm}.sh")
}

function Write-MoldAgentWrapperScript {
    param(
        [string]$Path,
        [string]$AgentScript,
        [string]$JobName
    )
    $escapedJob = $JobName -replace "'", "'\''"
    $escapedScript = $AgentScript -replace "'", "'\''"
    $content = @"
#!/bin/bash
exec '$escapedScript' "`$(hostname -s)" '$escapedJob'
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $content.Replace("`r`n", "`n"), $utf8NoBom)
}

function Write-MoldGuestKvmSshWrapperScript {
    param(
        [string]$Path,
        [string]$KvmHost,
        [string]$KvmUser,
        [string]$VmLibvirtName,
        [string]$JobName,
        [string]$RemoteScript,
        [switch]$IsPost
    )
    $escapedJob = $JobName -replace "'", "'\''"
    $escapedVm = $VmLibvirtName -replace "'", "'\''"
    $escapedRemote = $RemoteScript -replace "'", "'\''"
    $escapedKvm = $KvmHost -replace "'", "'\''"
    $escapedUser = $KvmUser -replace "'", "'\''"
    $hookPhase = if ($IsPost) { "post" } else { "pre" }
    if ($IsPost) {
        $remoteBody = "MOLD_BACKUP_CONF=/etc/ablestack/veeam/Mold_Guest_Backup.conf VM_INCLUDE='${escapedVm}' VM_NAME='${escapedVm}' bash '${escapedRemote}' \`"`$CLIENT\`" '${escapedJob}' default"
    } else {
        $remoteBody = "MOLD_BACKUP_CONF=/etc/ablestack/veeam/Mold_Guest_Backup.conf VM_INCLUDE='${escapedVm}' bash '${escapedRemote}' \`"`$CLIENT\`" '${escapedJob}' default '${escapedVm}'"
    }
    $content = @"
#!/bin/bash
# Veeam Guest Processing: SSH from guest VM to KVM for Mold pre/post-notify.
GUEST_LOG=/var/log/mold/guest-veeam-hook.log
mkdir -p /var/log/mold 2>/dev/null || true
log() { echo "[`$(date '+%Y-%m-%d %H:%M:%S')] [$hookPhase] `$*" >>"`$GUEST_LOG"; }
SSH=/usr/bin/ssh
[[ -x "`$SSH" ]] || SSH=ssh
CLIENT="`$(hostname -s 2>/dev/null || hostname)"
log "start job=${escapedJob} vm=${escapedVm} kvm=${escapedKvm} client=`$CLIENT"
`$SSH -o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=accept-new '${escapedUser}@${escapedKvm}' \
  "$remoteBody"
rc=`$?
if [[ `$rc -ne 0 ]]; then
  log "kvm notify failed rc=`$rc"
  exit `$rc
fi
log "kvm notify ok"
exit 0
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $content.Replace("`r`n", "`n"), $utf8NoBom)
}

function Clear-MoldVeeamGuestJobIncompatibleOptions {
    param(
        $AgentJob,
        $BackupObject
    )

    if ($BackupObject) {
        try {
            $indexDisable = New-VBRComputerIndexingOptions `
                -BackupObject $BackupObject `
                -OSPlatform Linux `
                -IndexingMode Disable
            Set-VBRComputerBackupJob -Job $AgentJob `
                -EnableIndexing:$false `
                -IndexingOptions @($indexDisable) | Out-Null
            Write-Host "Disabled guest file indexing (SelectedFiles incompatible with IndexIncludedOnly)."
        } catch {
            Write-Warning "Disable indexing: $($_.Exception.Message)"
        }
    } else {
        try {
            Set-VBRComputerBackupJob -Job $AgentJob -EnableIndexing:$false | Out-Null
        } catch {
            Write-Warning "Disable indexing (no BackupObject): $($_.Exception.Message)"
        }
    }

    try {
        Set-VBRComputerBackupJob -Job $AgentJob -EnableApplicationProcessing:$false | Out-Null
    } catch {
        Write-Warning "Disable ApplicationProcessing: $($_.Exception.Message)"
    }
}

function Register-MoldVeeamGuestSelectedFilesScripts {
    param(
        $AgentJob,
        [string]$VmIp,
        $DiscoveredComputer,
        [string]$PreWrapper,
        [string]$PostWrapper
    )

    $backupObject = $DiscoveredComputer
    if (-not $backupObject) {
        $backupObject = $AgentJob.BackupObject
    }
    if ($backupObject -is [System.Array]) {
        $backupObject = $backupObject | Select-Object -First 1
    }
    if (-not $backupObject) {
        throw "BackupObject not found for guest VM ${VmIp}."
    }

    try {
        Rescan-VBREntity -Entity $backupObject | Out-Null
    } catch {
        Write-Warning "Rescan backup object failed (continuing): $($_.Exception.Message)"
    }

    Clear-MoldVeeamGuestJobIncompatibleOptions -AgentJob $AgentJob -BackupObject $backupObject

    $indexDisable = New-VBRComputerIndexingOptions `
        -BackupObject $backupObject `
        -OSPlatform Linux `
        -IndexingMode Disable

    # SelectedFiles: pre-job/post-job (not pre-freeze/post-thaw). ScriptOptions is for Windows agents.
    $scriptProcessing = New-VBRScriptProcessingOptions `
        -ProcessingAction RequireSuccess `
        -ScriptPreJobCommand $PreWrapper `
        -ScriptPostJobCommand $PostWrapper

    $appProcessing = New-VBRApplicationProcessingOptions `
        -Enable `
        -OSPlatform Linux `
        -BackupObject $backupObject `
        -ScriptProcessingOptions $scriptProcessing

    $disabledJobScripts = New-VBRJobScriptOptions -PreScriptEnabled:$false -PostScriptEnabled:$false

    try {
        Set-VBRComputerBackupJob -Job $AgentJob `
            -ScriptOptions $disabledJobScripts `
            -EnableIndexing:$false `
            -IndexingOptions @($indexDisable) `
            -EnableApplicationProcessing `
            -ApplicationProcessingOptions @($appProcessing) | Out-Null
    } catch {
        throw @"
SelectedFiles script registration failed for ${VmIp}: $($_.Exception.Message)

If indexing was enabled in Veeam UI, open Job -> Guest Processing:
  1) Indexing tab -> disable file indexing
  2) Scripts tab -> Job scripts:
     Pre-job : $PreWrapper
     Post-job: $PostWrapper
Then re-run push-to-veeam.sh
"@
    }

    Write-Host "Registered pre-job/post-job on SelectedFiles guest job ${VmIp} (runs on guest -> SSH KVM for Mold)."
    Write-Host "  Pre-job  : $PreWrapper"
    Write-Host "  Post-job : $PostWrapper"
    Write-Host "  On guest, Veeam uploads to /var/lib/veeam/scripts/ and runs as root."
    Write-Host "  Guest log: /var/log/mold/guest-veeam-hook.log on ${VmIp}"
}

function Register-MoldVeeamGuestVmGuestScripts {
    param(
        $AgentJob,
        [string]$JobName,
        [string]$InstallDir,
        [string]$KvmHost,
        [string]$KvmSshUser,
        [string]$VmLibvirtName,
        [string]$VmIp,
        $DiscoveredComputer,
        [string]$AgentPreNotifyScript,
        [string]$AgentPostNotifyScript
    )

    if (-not $VmLibvirtName -or -not $VmIp) {
        throw "GuestVm mode requires -VmLibvirtName and -VmIp"
    }
    if (-not $KvmHost) {
        throw "GuestVm mode requires -KvmHost (Mold trigger on hypervisor)"
    }

    $safeVm = ($VmLibvirtName -replace '[^\w\-]', '_')
    $preDeployed = Join-Path $InstallDir "mold-guest-pre-${safeVm}.sh"
    $postDeployed = Join-Path $InstallDir "mold-guest-post-${safeVm}.sh"
    if ((Test-Path $preDeployed) -and (Test-Path $postDeployed)) {
        $preWrapper = $preDeployed
        $postWrapper = $postDeployed
        Write-Host "Using deployed guest wrappers from KVM (libvirt name):"
    } else {
        $preWrapper = Get-MoldVeeamGuestWrapperPath -InstallDir $InstallDir -Phase "pre" -VmLibvirtName $VmLibvirtName
        $postWrapper = Get-MoldVeeamGuestWrapperPath -InstallDir $InstallDir -Phase "post" -VmLibvirtName $VmLibvirtName
        Write-MoldGuestKvmSshWrapperScript -Path $preWrapper -KvmHost $KvmHost -KvmUser $KvmSshUser `
            -VmLibvirtName $VmLibvirtName -JobName $JobName -RemoteScript $AgentPreNotifyScript
        Write-MoldGuestKvmSshWrapperScript -Path $postWrapper -KvmHost $KvmHost -KvmUser $KvmSshUser `
            -VmLibvirtName $VmLibvirtName -JobName $JobName -RemoteScript $AgentPostNotifyScript -IsPost
        Write-Host "Created guest KVM-SSH wrappers (run on VM ${VmIp}, Mold on ${KvmHost}):"
    }
    Write-Host "  $preWrapper"
    Write-Host "  $postWrapper"

    $backupType = ""
    foreach ($key in @("BackupType", "Type", "BackupJobType")) {
        if ($AgentJob.PSObject.Properties.Name -contains $key -and $AgentJob.$key) {
            $backupType = "$($AgentJob.$key)"
            break
        }
    }

    # SelectedFiles (file-level) Linux jobs: pre-job/post-job via Guest Processing scripts.
    if ($backupType -eq "SelectedFiles") {
        Register-MoldVeeamGuestSelectedFilesScripts `
            -AgentJob $AgentJob `
            -VmIp $VmIp `
            -DiscoveredComputer $DiscoveredComputer `
            -PreWrapper $preWrapper `
            -PostWrapper $postWrapper
        return
    }

    $backupObject = $DiscoveredComputer
    if (-not $backupObject) {
        $backupObject = $AgentJob.BackupObject
    }
    if ($backupObject -is [System.Array]) {
        $backupObject = $backupObject | Select-Object -First 1
    }
    if (-not $backupObject) {
        throw "Job BackupObject not found for guest VM ${VmIp}. Re-run create-veeam-guest-vm-jobs.ps1 after agent rescan."
    }

    try {
        Rescan-VBREntity -Entity $backupObject | Out-Null
    } catch {
        Write-Warning "Rescan backup object failed (continuing): $($_.Exception.Message)"
    }

    $scriptProcessing = New-VBRScriptProcessingOptions `
        -ProcessingAction RequireSuccess `
        -ScriptPrefreezeCommand $preWrapper `
        -ScriptPostthawCommand $postWrapper

    $appProcessing = New-VBRApplicationProcessingOptions `
        -Enable `
        -OSPlatform Linux `
        -BackupObject $backupObject `
        -ScriptProcessingOptions $scriptProcessing

    $disabledJobScripts = New-VBRJobScriptOptions -PreScriptEnabled:$false -PostScriptEnabled:$false

    try {
        Set-VBRComputerBackupJob -Job $AgentJob `
            -ScriptOptions $disabledJobScripts `
            -EnableApplicationProcessing `
            -ApplicationProcessingOptions @($appProcessing) | Out-Null
    } catch {
        throw "Guest Processing registration failed for ${VmIp}: $($_.Exception.Message). For SelectedFiles jobs use pre-job/post-job (re-run push-to-veeam.sh with updated install-veeam-job.ps1)."
    }

    Write-Host "Registered Guest Processing pre-freeze/post-thaw on VM ${VmIp} (runs on guest -> SSH KVM for Mold)."
    Write-Host "  Pre-freeze : $preWrapper"
    Write-Host "  Post-thaw  : $postWrapper"
    Write-Host "  On guest, Veeam uploads to /var/lib/veeam/scripts/ and runs as root."
    Write-Host "  Guest log: /var/log/mold/guest-veeam-hook.log on ${VmIp}"
}

function Register-MoldVeeamLinuxAgentGuestScripts {
    param(
        $AgentJob,
        [string]$JobName,
        [string]$InstallDir,
        [string]$ProtectionGroupName,
        [string]$AgentPreNotifyScript,
        [string]$AgentPostNotifyScript
    )

    $safeJob = ($JobName -replace '[^\w\-]', '_')
    $preWrapper = Join-Path $InstallDir "mold-agent-pre-$safeJob.sh"
    $postWrapper = Join-Path $InstallDir "mold-agent-post-$safeJob.sh"
    Write-MoldAgentWrapperScript -Path $preWrapper -AgentScript $AgentPreNotifyScript -JobName $JobName
    Write-MoldAgentWrapperScript -Path $postWrapper -AgentScript $AgentPostNotifyScript -JobName $JobName
    Write-Host "Created agent wrapper scripts (uploaded to KVM by Veeam on job run):"
    Write-Host "  $preWrapper"
    Write-Host "  $postWrapper"

    $backupObject = $null
    if ($ProtectionGroupName) {
        $backupObject = Get-VBRProtectionGroup -Name $ProtectionGroupName -ErrorAction SilentlyContinue
    }
    if (-not $backupObject -and $AgentJob.BackupObject) {
        $backupObject = @($AgentJob.BackupObject) | Select-Object -First 1
    }
    if (-not $backupObject) {
        throw "Protection group not found for Guest Processing scripts. Pass -ProtectionGroupName or fix job BackupObject."
    }

    if ($backupObject -is [System.Array]) {
        $backupObject = $backupObject | Select-Object -First 1
    }

    Write-Host "Rescanning protection group before guest script registration..."
    Rescan-VBREntity -Entity $backupObject | Out-Null

    # Pre-freeze/post-thaw run ON the Linux guest (Veeam uploads to /var/lib/veeam/scripts/).
    $scriptProcessing = New-VBRScriptProcessingOptions `
        -ProcessingAction RequireSuccess `
        -ScriptPrefreezeCommand $preWrapper `
        -ScriptPostthawCommand $postWrapper

    $appProcessing = New-VBRApplicationProcessingOptions `
        -Enable `
        -OSPlatform Linux `
        -BackupObject $backupObject `
        -ScriptProcessingOptions $scriptProcessing

    # Job-level scripts (Storage -> Advanced -> Scripts) run on Windows B&R — disable them.
    $disabledJobScripts = New-VBRJobScriptOptions -PreScriptEnabled:$false -PostScriptEnabled:$false

    Set-VBRComputerBackupJob -Job $AgentJob `
        -ScriptOptions $disabledJobScripts `
        -EnableApplicationProcessing `
        -ApplicationProcessingOptions @($appProcessing) | Out-Null

    Write-Host "Registered Guest Processing pre/post (run on KVM agent, not this Windows server)."
}

if ($ScriptsOnly) {
    # Generate ONLY the pre/post wrapper scripts on this Veeam server.
    # No Veeam module, no job lookup, no job registration. Point the Veeam UI
    # (Job -> Guest Processing -> Scripts) at the generated files manually.
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $safeJob = ($JobName -replace '[^\w\-]', '_')

    if ($GuestVm.IsPresent) {
        if (-not $VmLibvirtName -or -not $VmIp) {
            throw "ScriptsOnly + GuestVm requires -VmLibvirtName and -VmIp"
        }
        if (-not $KvmHost) {
            throw "ScriptsOnly + GuestVm requires -KvmHost"
        }
        $preWrapper = Get-MoldVeeamGuestWrapperPath -InstallDir $InstallDir -Phase "pre" -VmLibvirtName $VmLibvirtName
        $postWrapper = Get-MoldVeeamGuestWrapperPath -InstallDir $InstallDir -Phase "post" -VmLibvirtName $VmLibvirtName
        Write-MoldGuestKvmSshWrapperScript -Path $preWrapper -KvmHost $KvmHost -KvmUser $KvmSshUser `
            -VmLibvirtName $VmLibvirtName -JobName $JobName -RemoteScript $AgentPreNotifyScript
        Write-MoldGuestKvmSshWrapperScript -Path $postWrapper -KvmHost $KvmHost -KvmUser $KvmSshUser `
            -VmLibvirtName $VmLibvirtName -JobName $JobName -RemoteScript $AgentPostNotifyScript -IsPost
        Write-Host "Generated guest KVM-SSH wrapper scripts (runs on VM ${VmIp}, Mold on ${KvmHost}):"
    } else {
        $preWrapper = Join-Path $InstallDir "mold-agent-pre-$safeJob.sh"
        $postWrapper = Join-Path $InstallDir "mold-agent-post-$safeJob.sh"
        Write-MoldAgentWrapperScript -Path $preWrapper -AgentScript $AgentPreNotifyScript -JobName $JobName
        Write-MoldAgentWrapperScript -Path $postWrapper -AgentScript $AgentPostNotifyScript -JobName $JobName
        Write-Host "Generated agent wrapper scripts (uploaded to the agent host by Veeam on job run):"
    }

    Write-Host "  Pre-freeze : $preWrapper"
    Write-Host "  Post-thaw  : $postWrapper"
    Write-Host ""
    Write-Host "Next (Veeam UI): Job -> Guest Processing -> Applications -> (select VM) Edit ->"
    Write-Host "  Scripts tab -> Pre-freeze script = $preWrapper"
    Write-Host "                 Post-thaw script  = $postWrapper"
    return
}

$files = @(
    "veeam-job-pre-backup.ps1",
    "veeam-job-post-backup.ps1",
    "veeam-job-post-restore.ps1",
    "mold-backup.windows.conf.default"
)

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

$sourceRoot = [System.IO.Path]::GetFullPath($SourceDir).TrimEnd('\', '/')
$installRoot = [System.IO.Path]::GetFullPath($InstallDir).TrimEnd('\', '/')
$skipCopy = ($sourceRoot -ieq $installRoot)
if ($skipCopy) {
    Write-Host "SourceDir equals InstallDir ($installRoot); skipping file copy."
}

# Linux Agent: pre/post run on KVM (bash). Windows PS1 bundle is optional for guest/Linux agent jobs.
$requireWindowsBundle = (-not $LinuxAgent.IsPresent) -and (-not $GuestVm.IsPresent)

if ($GuestVm.IsPresent -and [string]::IsNullOrWhiteSpace($SourceDir)) {
    $SourceDir = $InstallDir
}
if ($GuestVm.IsPresent -and -not (Test-Path (Join-Path $SourceDir "veeam-job-pre-backup.ps1"))) {
  if (Test-Path (Join-Path $InstallDir "veeam-job-pre-backup.ps1")) {
    $SourceDir = $InstallDir
  }
}

foreach ($f in $files) {
    $src = Join-Path $SourceDir $f
    $dest = Join-Path $InstallDir $f
    if (-not (Test-Path $src)) {
        if ($requireWindowsBundle) {
            throw "Missing file: $src"
        }
        Write-Warning "Skipping optional file (Linux Agent mode): $src"
        continue
    }
    if ($skipCopy) {
        continue
    }
    $srcFull = [System.IO.Path]::GetFullPath($src)
    $destFull = [System.IO.Path]::GetFullPath($dest)
    if ($srcFull -ieq $destFull) {
        continue
    }
    Copy-Item -Path $src -Destination $dest -Force
}

$confPath = Join-Path $InstallDir "mold-backup.windows.conf"
$confDefault = Join-Path $InstallDir "mold-backup.windows.conf.default"
if (-not (Test-Path $confPath) -and (Test-Path $confDefault)) {
    Copy-Item $confDefault $confPath
    Write-Host "Created $confPath — edit VM_NAME, STAGING_PATH, KVM_HOST if needed."
}

function Set-ConfValue {
    param([string]$Key, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    $lines = Get-Content $confPath
    $found = $false
    $out = foreach ($line in $lines) {
        if ($line -match "^\s*$([regex]::Escape($Key))\s*=") {
            $found = $true
            "$Key=`"$Value`""
        } else { $line }
    }
    if (-not $found) { $out += "$Key=`"$Value`"" }
    Set-Content -Path $confPath -Value $out -Encoding UTF8
}

Set-ConfValue -Key "VEEAM_JOB_NAME" -Value $JobName
Set-ConfValue -Key "VM_NAME" -Value $VmName
Set-ConfValue -Key "STAGING_PATH" -Value $StagingPath
Set-ConfValue -Key "KVM_HOST" -Value $KvmHost
Set-ConfValue -Key "KVM_SSH_USER" -Value $KvmSshUser
Set-ConfValue -Key "KVM_SSH_KEY" -Value $KvmSshKey

$prePs1 = Join-Path $InstallDir "veeam-job-pre-backup.ps1"
$postPs1 = Join-Path $InstallDir "veeam-job-post-backup.ps1"
$restorePs1 = Join-Path $InstallDir "veeam-job-post-restore.ps1"

Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue

$agentJob = Get-VBRComputerBackupJob -Name $JobName -ErrorAction SilentlyContinue
$job = $null
if (-not $agentJob) {
    $job = Get-VBRJob -Name $JobName -ErrorAction SilentlyContinue
}
if (-not $agentJob -and -not $job) {
    Write-Host "Available Agent backup jobs:"
    Get-VBRComputerBackupJob | Select-Object -ExpandProperty Name
    $createScript = Join-Path $InstallDir "create-veeam-agent-job.ps1"
    if (-not (Test-Path $createScript)) {
        $createScript = Join-Path $PSScriptRoot "create-veeam-agent-job.ps1"
    }
    throw @"
Job not found: $JobName

Create the Agent backup job first (run on this Veeam B&R server):
  pwsh -File '$createScript' -JobName '$JobName' -KvmHost '<kvm-ip>' -AgentHostName '<hostname>'

Or from Git repo / ccvm:
  bash push-to-veeam.sh --env-file mold-backup.env
"@
}

# Linux Agent: pre/post scripts run ON THE AGENT HOST (bash), not on this Windows B&R server.
$useLinuxAgentScripts = $LinuxAgent.IsPresent
if ($agentJob -and -not $useLinuxAgentScripts) {
    $osType = $agentJob.OSPlatform
    if ($osType -eq "Linux") { $useLinuxAgentScripts = $true }
}
if ($useLinuxAgentScripts -and -not $GuestVm.IsPresent) {
    Write-Host "Linux Agent job: Guest Processing scripts -> KVM (Mold VM backup pre/post)."
    Write-Host "Ensure scripts exist on KVM:"
    Write-Host "  $AgentPreNotifyScript"
    Write-Host "  $AgentPostNotifyScript"
    Write-Host "On KVM: /etc/veeam/veeam.ini [scripts] timeoutPrePost = 1800 ; systemctl restart veeamservice"
} else {
    $psExe = "pwsh.exe"
    if (-not (Get-Command $psExe -ErrorAction SilentlyContinue)) {
        Write-Warning "pwsh.exe not found; falling back to powershell.exe (Veeam module may fail on PS 5.1)"
        $psExe = "powershell.exe"
    }
    $preCmd = "$psExe -ExecutionPolicy Bypass -NoProfile -File `"$prePs1`""
    $postCmd = "$psExe -ExecutionPolicy Bypass -NoProfile -File `"$postPs1`""
    $restoreCmd = "$psExe -ExecutionPolicy Bypass -NoProfile -File `"$restorePs1`""
}

if ($agentJob) {
    if ($GuestVm.IsPresent) {
        Register-MoldVeeamGuestVmGuestScripts `
            -AgentJob $agentJob `
            -JobName $JobName `
            -InstallDir $InstallDir `
            -KvmHost $KvmHost `
            -KvmSshUser $KvmSshUser `
            -VmLibvirtName $VmLibvirtName `
            -VmIp $VmIp `
            -DiscoveredComputer $DiscoveredComputer `
            -AgentPreNotifyScript $AgentPreNotifyScript `
            -AgentPostNotifyScript $AgentPostNotifyScript
        $preCmd = "Guest VM ${VmIp} -> SSH KVM Mold pre"
        $postCmd = "Guest VM ${VmIp} -> SSH KVM Mold post"
    } elseif ($useLinuxAgentScripts) {
        Register-MoldVeeamLinuxAgentGuestScripts `
            -AgentJob $agentJob `
            -JobName $JobName `
            -InstallDir $InstallDir `
            -ProtectionGroupName $ProtectionGroupName `
            -AgentPreNotifyScript $AgentPreNotifyScript `
            -AgentPostNotifyScript $AgentPostNotifyScript
        $preCmd = "Guest Processing pre-job -> KVM ($AgentPreNotifyScript)"
        $postCmd = "Guest Processing post-job -> KVM ($AgentPostNotifyScript)"
    } else {
        $scriptOptions = New-VBRJobScriptOptions `
            -PreScriptEnabled `
            -PreCommand $preCmd `
            -PostScriptEnabled `
            -PostCommand $postCmd `
            -Periodicity Cycles `
            -Frequency 1

        Set-VBRComputerBackupJob -Job $agentJob -ScriptOptions $scriptOptions | Out-Null
        Write-Host "Registered Pre/Post scripts on Agent backup job: $JobName"
    }
} elseif ($job) {
    $opts = Get-VBRJobOptions -Job $job
    $base = $opts.JobScriptOptions
    if (-not $base) {
        $base = New-VBRJobScriptOptions
    }
    $newScript = Set-VBRJobScriptOptions -JobScriptOptions $base `
        -PreScriptEnabled -PreCommand $preCmd `
        -PostScriptEnabled -PostCommand $postCmd `
        -Periodicity Cycles -Frequency 1
    $opts.JobScriptOptions = $newScript
    Set-VBRJobOptions -Job $job -Options $opts | Out-Null
    Write-Host "Registered Pre/Post scripts on backup job: $JobName"
}

Write-Host ""
Write-Host "Installed to: $InstallDir"
Write-Host "  Pre-job : $preCmd"
Write-Host "  Post-job: $postCmd"
Write-Host ""
Write-Host "Verify in Veeam UI: Job -> Guest Processing -> Processing Settings -> Scripts (Pre-job / Post-job)"
if ($GuestVm.IsPresent) {
    Write-Host "Guest VM ${VmIp}: ensure passwordless ssh ${KvmSshUser}@${KvmHost}"
    Write-Host "SelectedFiles jobs: Veeam UI -> Job -> Storage -> Advanced -> Scripts (Pre-job / Post-job)"
} elseif ($useLinuxAgentScripts) {
    Write-Host "KVM host: run veeam_config.sh then ensure Veeam job backs up /tmp/mold/veeam"
} else {
    Write-Host "KVM host: run veeam_config.sh for Mold integration"
}
if ($EnableRestoreScript) {
    Write-Host ""
    Write-Host "Post-restore script (run manually after Veeam restore completes):"
    Write-Host "  $restoreCmd"
    Write-Host "Set BACKUP_ID in mold-backup.windows.conf before running."
}
