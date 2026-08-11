# Veeam post-restore hook: push restore event to KVM restore agent over SSH.
# The KVM agent checks VM ownership + flock before calling Mold restoreBackup.

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
$backupId = $cfg["BACKUP_ID"]
$vmName = $cfg["VM_NAME"]
if (-not $kvmHost -or -not $kvmUser) {
    throw "KVM_HOST and KVM_SSH_USER are required in $ConfPath"
}
if (-not $vmName) {
    throw "VM_NAME is required in $ConfPath for post-restore event"
}

$eventScript = $cfg["KVM_RESTORE_EVENT_SCRIPT"]
if (-not $eventScript) { $eventScript = "/etc/ablestack/veeam/ablestack_veeam_restore_event.sh" }

$sessionId = $env:VEEAM_RESTORE_SESSION_ID
if (-not $sessionId) {
    $sessionId = "veeam-ps1-$(Get-Date -Format 'yyyyMMddHHmmss')"
}

$sshKey = $cfg["KVM_SSH_KEY"]
$sshArgs = @()
if ($sshKey) { $sshArgs += @("-i", $sshKey) }

$remote = "$kvmUser@$kvmHost"
$restoreSource = $cfg["VEEAM_UI_RESTORE_SOURCE"]
if (-not $restoreSource) { $restoreSource = "mold-only" }
$backupEnv = ""
if ($backupId) { $backupEnv = "BACKUP_ID='$backupId' " }
Write-Host "Pushing restore event to $remote (vm=$vmName session=$sessionId source=$restoreSource)"
& ssh @sshArgs $remote "${backupEnv}RESTORE_SOURCE='$restoreSource' VEEAM_JOB_NAME='$jobName' VM_NAME='$vmName' bash '$eventScript' veeam.restore.completed '$sessionId' '$vmName' source=veeam-ui-flr"
if ($LASTEXITCODE -ne 0) { throw "restore-event failed with exit code $LASTEXITCODE" }

exit 0
