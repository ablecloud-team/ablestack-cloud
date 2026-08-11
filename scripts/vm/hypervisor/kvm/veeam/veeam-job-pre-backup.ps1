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

# Veeam Backup Job pre-script: record restore point id; optionally export disks to staging.
# FileLevel Agent backups: no VMDK export — KVM seed uses live libvirt disks (VEEAM_BACKUP_MODE=filelevel).
# Configure: C:\ProgramData\Mold\backup\veeam\mold-backup.windows.conf

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "Veeam.Backup.PowerShell requires PowerShell 7+. Run: pwsh -File $PSCommandPath"
}

$ConfPath = $env:MOLD_BACKUP_WINDOWS_CONF
if (-not $ConfPath) {
    $ConfPath = "C:\ProgramData\Mold\backup\veeam\mold-backup.windows.conf"
}

function Read-MoldConf {
    param([string]$Path)
    $cfg = @{}
    if (-not (Test-Path $Path)) {
        throw "Config not found: $Path"
    }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*#' -or $line -eq '') { return }
        if ($line -match '^([^=]+)=(.*)$') {
            $cfg[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"')
        }
    }
    return $cfg
}

function Write-MoldConfValue {
    param([string]$Path, [string]$Key, [string]$Value)
    $content = Get-Content $Path -ErrorAction SilentlyContinue
    if (-not $content) { $content = @() }
    $found = $false
    $newContent = foreach ($line in $content) {
        if ($line -match "^\s*$([regex]::Escape($Key))\s*=") {
            $found = $true
            "$Key=`"$Value`""
        } else { $line }
    }
    if (-not $found) { $newContent += "$Key=`"$Value`"" }
    Set-Content -Path $Path -Value $newContent -Encoding UTF8
}

$cfg = Read-MoldConf -Path $ConfPath
$vmName = $cfg["VM_NAME"]
if (-not $vmName) { $vmName = $env:VEEAM_VM_NAME }

$jobName = $cfg["VEEAM_JOB_NAME"]
if (-not $jobName) { $jobName = $env:VEEAM_JOB_NAME }

$staging = $cfg["STAGING_PATH"]
if (-not $staging) { $staging = $cfg["VEEAM_HOST_BACKUP_PATH"] }
if (-not $staging) { $staging = "/tmp/mold/veeam" }

Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue

# NetBackup-style: multi-VM job — pre-notify only (no VMDK export)
if ($cfg["VEEAM_BACKUP_MODE"] -eq "filelevel" -and $cfg["KVM_HOST"] -and $cfg["KVM_SSH_USER"] -and $jobName -and -not $vmName) {
    $preScript = $cfg["KVM_PRE_NOTIFY_SCRIPT"]
    if (-not $preScript) { $preScript = "/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh" }
    $sshKey = $cfg["KVM_SSH_KEY"]
    $sshArgs = @()
    if ($sshKey) { $sshArgs += @("-i", $sshKey) }
    $remote = "$($cfg['KVM_SSH_USER'])@$($cfg['KVM_HOST'])"
    Write-Host "Running pre-notify (multi-VM) on $remote : $preScript (job=$jobName)"
    & ssh @sshArgs $remote "bash '$preScript' '$(hostname)' '$jobName'"
    if ($LASTEXITCODE -ne 0) { throw "pre-notify failed with exit code $LASTEXITCODE" }
    exit 0
}

if (-not $vmName) { throw "VM_NAME is required in $ConfPath or VEEAM_VM_NAME env" }

$backups = @()
if ($jobName) {
    # Agent jobs: Veeam requires a trailing wildcard on backup name.
    $backups = @(Get-VBRBackup -Name "${jobName}*" -ErrorAction SilentlyContinue)
}
if ($backups.Count -eq 0) {
    $backups = @(Get-VBRBackup | Where-Object { $_.JobType -eq "EpAgentBackup" })
}
if ($backups.Count -eq 0) {
    throw "No Veeam backups found. Set VEEAM_JOB_NAME in $ConfPath (e.g. Agent Backup Job 1)."
}

$backup = $null
$vm = $null
foreach ($b in $backups) {
    $objects = @(Get-VBRBackupObject -Backup $b -ErrorAction SilentlyContinue)
    $match = $objects | Where-Object { $_.Name -eq $vmName } | Select-Object -First 1
    if ($match) {
        $backup = $b
        $vm = $match
        break
    }
}
if (-not $vm) {
    $known = foreach ($b in $backups) {
        Get-VBRBackupObject -Backup $b -ErrorAction SilentlyContinue | ForEach-Object { $_.Name }
    }
    $known = ($known | Sort-Object -Unique) -join ", "
    throw "Veeam backup object not found for VM_NAME=$vmName. Known names: $known"
}

# Start-VBRRestoreVirtualDisks expects COib from Get-VBRRestorePoint (not Get-VBRObjectRestorePoint).
$rp = $backup | Get-VBRRestorePoint -ErrorAction SilentlyContinue |
    Sort-Object CreationTime -Descending |
    Select-Object -First 1
if (-not $rp) {
    $rp = $vm | Get-VBRRestorePoint -ErrorAction SilentlyContinue |
        Sort-Object CreationTime -Descending |
        Select-Object -First 1
}
if (-not $rp) {
    $rp = $backup | Get-VBRObjectRestorePoint -Name $vmName |
        Sort-Object CreationTime -Descending |
        Select-Object -First 1
}
if (-not $rp) {
    $rp = Get-VBRObjectRestorePoint -Backup $backup |
        Where-Object { $_.Name -eq $vmName } |
        Sort-Object CreationTime -Descending |
        Select-Object -First 1
}
if (-not $rp) { throw "No restore point for VM: $vmName (backup: $($backup.Name))" }

$rpId = $rp.Id
if ($rpId -is [guid]) { $rpId = $rpId.Guid }

$rpOib = $backup | Get-VBRRestorePoint -ErrorAction SilentlyContinue |
    Where-Object { $_.Id -eq $rpId -or $_.Id.Guid -eq $rpId } |
    Select-Object -First 1
if (-not $rpOib) {
    $rpOib = Get-VBRRestorePoint -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -eq $rpId -or $_.Id.Guid -eq $rpId } |
        Select-Object -First 1
}
if (-not $rpOib) { $rpOib = $rp }

function Test-MoldVeeamFileLevelRestorePoint {
    param($RestorePoint)
    $text = $RestorePoint | Format-List * -Force | Out-String
    if ($text -match 'BackupMode\s*:\s*FileLevel') { return $true }
    if ($text -match 'COibAuxDataLinuxBackup') { return $true }
    if ($text -match 'ItemType\s*:\s*LinuxPhysicalDisk' -and $text -match 'IncludePaths') { return $true }
    return $false
}

$backupMode = $cfg["VEEAM_BACKUP_MODE"]
if (-not $backupMode) { $backupMode = "auto" }
$isFileLevel = ($backupMode -eq "filelevel") -or (
    $backupMode -eq "auto" -and (Test-MoldVeeamFileLevelRestorePoint -RestorePoint $rpOib)
)

if ($isFileLevel) {
    Write-Host "VEEAM_BACKUP_MODE=filelevel: NetBackup-style pre-notify on KVM (host path /tmp/mold/veeam)."
    Write-MoldConfValue -Path $ConfPath -Key "VEEAM_RESTORE_POINT_ID" -Value $rpId
    Write-Host "VEEAM_RESTORE_POINT_ID=$rpId written to $ConfPath"
    if ($cfg["KVM_HOST"] -and $cfg["KVM_SSH_USER"] -and $jobName) {
        $preScript = $cfg["KVM_PRE_NOTIFY_SCRIPT"]
        if (-not $preScript) { $preScript = "/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh" }
        $sshKey = $cfg["KVM_SSH_KEY"]
        $sshArgs = @()
        if ($sshKey) { $sshArgs += @("-i", $sshKey) }
        $remote = "$($cfg['KVM_SSH_USER'])@$($cfg['KVM_HOST'])"
        Write-Host "Running pre-notify on $remote : $preScript (job=$jobName)"
        & ssh @sshArgs $remote "bash '$preScript' '$(hostname)' '$jobName'"
        if ($LASTEXITCODE -ne 0) { throw "pre-notify failed with exit code $LASTEXITCODE" }
    }
    exit 0
}

New-Item -ItemType Directory -Force -Path $staging | Out-Null
Get-ChildItem -Path $staging -File -Recurse -Include *.vmdk,*.vhd,*.vhdx -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

function Resolve-MoldVeeamExportServer {
    param([hashtable]$Cfg)
    $configured = $Cfg["STAGING_EXPORT_SERVER"]
    if ($configured) {
        $s = Get-VBRServer -Type Windows -Name $configured -ErrorAction SilentlyContinue
        if ($s) { return $s }
    }
    $names = @($env:COMPUTERNAME)
    if ($env:USERDNSDOMAIN) {
        $names += "$($env:COMPUTERNAME).$($env:USERDNSDOMAIN)"
    }
    foreach ($n in $names) {
        $s = Get-VBRServer -Type Windows -Name $n -ErrorAction SilentlyContinue
        if ($s) { return $s }
    }
    $localhost = Get-VBRLocalhost -ErrorAction SilentlyContinue
    if ($localhost) { return $localhost }
    Get-VBRServer -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Export-MoldVeeamDisksToStaging {
    param(
        [Parameter(Mandatory = $true)]$RestorePoint,
        [Parameter(Mandatory = $true)][string]$StagingPath,
        [hashtable]$Cfg = @{}
    )
    if (Get-Command Start-VBRFLRSession -ErrorAction SilentlyContinue) {
        $session = Start-VBRFLRSession -RestorePoint $RestorePoint
        try {
            $items = Get-VBRFLRItem -Session $session | Where-Object { $_.Type -eq "HardDisk" }
            foreach ($item in $items) {
                $dest = Join-Path $StagingPath ($item.Name + ".vmdk")
                Copy-VBRFLRItem -FLRSession $session -Item $item -Destination $dest
                Write-Host "Exported $($item.Name) -> $dest"
            }
        } finally {
            Stop-VBRFLRSession -Session $session
        }
        return
    }
    if (Get-Command Start-VBRRestoreVirtualDisks -ErrorAction SilentlyContinue) {
        $server = Resolve-MoldVeeamExportServer -Cfg $Cfg
        if (-not $server) {
            throw "No Windows managed server for export. Add $($env:COMPUTERNAME) under Backup Infrastructure > Managed Servers, or set STAGING_EXPORT_SERVER in mold-backup.windows.conf"
        }
        if (-not (Test-Path -LiteralPath $StagingPath)) {
            New-Item -ItemType Directory -Force -Path $StagingPath | Out-Null
        }
        Write-Host "Exporting disks via Start-VBRRestoreVirtualDisks -> $StagingPath (server: $($server.Name))"
        try {
            Start-VBRRestoreVirtualDisks -RestorePoint $RestorePoint -Server $server `
                -Path $StagingPath -RestoreDiskType Vmdk | Out-Null
        } catch {
            throw "Start-VBRRestoreVirtualDisks failed on server '$($server.Name)': $_. Ensure the folder exists, Veeam service account has write access, and the server is a Managed Windows server in Veeam console."
        }
        Get-ChildItem -Path $StagingPath -Recurse -Include *.vmdk,*.vhd,*.vhdx -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Host "Exported $($_.FullName)" }
        return
    }
    throw "No supported Veeam export cmdlet (Start-VBRFLRSession / Start-VBRRestoreVirtualDisks)"
}

Export-MoldVeeamDisksToStaging -RestorePoint $rpOib -StagingPath $staging -Cfg $cfg
$exported = Get-ChildItem -Path $staging -Recurse -Include *.vmdk,*.vhd,*.vhdx -ErrorAction SilentlyContinue
if (-not $exported) {
    throw "No virtual disk files exported under $staging"
}

Write-MoldConfValue -Path $ConfPath -Key "VEEAM_RESTORE_POINT_ID" -Value $rpId
Write-Host "VEEAM_RESTORE_POINT_ID=$rpId written to $ConfPath"

# Optional: push restore point id to KVM host config over SSH
if ($cfg["KVM_HOST"] -and $cfg["KVM_SSH_USER"]) {
    $kvmConf = $cfg["KVM_MOLD_BACKUP_CONF"]
    if (-not $kvmConf) { $kvmConf = "/etc/mold/backup/veeam/mold-backup.conf" }
    $sshKey = $cfg["KVM_SSH_KEY"]
    $sshArgs = @()
    if ($sshKey) { $sshArgs += @("-i", $sshKey) }
    $remote = "$($cfg['KVM_SSH_USER'])@$($cfg['KVM_HOST'])"
    $cmd = "grep -q '^VEEAM_RESTORE_POINT_ID=' '$kvmConf' 2>/dev/null && sed -i 's|^VEEAM_RESTORE_POINT_ID=.*|VEEAM_RESTORE_POINT_ID=`"$rpId`"|' '$kvmConf' || echo 'VEEAM_RESTORE_POINT_ID=`"$rpId`"' >> '$kvmConf'"
    & ssh @sshArgs $remote $cmd
    Write-Host "Updated VEEAM_RESTORE_POINT_ID on KVM host $remote"
}

exit 0
