# Create / ensure a host-level Veeam backup repository on local disk (E:\opt1\veeam\<kvm-host>).
# All guest Agent jobs for one KVM hypervisor share this repo (not per-VM folders on E:).
#
#   pwsh -File setup-veeam-host-repo.ps1 -KvmHostname ablecube31-2
#   pwsh -File setup-veeam-host-repo.ps1 -KvmHostname ablecube31-2 -HostRepoRoot "E:\opt1\veeam"

param(
    [Parameter(Mandatory = $true)]
    [string]$KvmHostname,

    [string]$HostRepoRoot = "E:\opt1\veeam",

    [string]$RepositoryName = ""
)

$ErrorActionPreference = "Stop"

if (-not $RepositoryName) {
    $RepositoryName = "Mold $KvmHostname"
}

$folder = Join-Path $HostRepoRoot $KvmHostname
New-Item -ItemType Directory -Force -Path $folder | Out-Null
Write-Host "Host repo folder: $folder"

Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction SilentlyContinue | Out-Null } catch {}

function Get-MoldRepoPathString {
    param($Repo)
    if ($null -eq $Repo) { return "" }
    foreach ($key in @("Path", "Folder", "Location")) {
        if ($Repo.PSObject.Properties.Name -contains $key -and $Repo.$key) {
            return [string]$Repo.$key
        }
    }
    return ""
}

$repo = Get-VBRBackupRepository -Name $RepositoryName -ErrorAction SilentlyContinue
if ($repo) {
    Write-Host "Repository already exists: $RepositoryName"
    Write-Output $RepositoryName
    exit 0
}

$created = $false
$errors = @()

function Try-AddRepo {
    param([scriptblock]$Action, [string]$Label)
    try {
        $r = & $Action
        if ($r) { return $r }
        $r = Get-VBRBackupRepository -Name $RepositoryName -ErrorAction SilentlyContinue
        if ($r) { return $r }
    } catch {
        $script:errors += "${Label}: $($_.Exception.Message)"
    }
    return $null
}

$repo = Try-AddRepo { Add-VBRBackupRepository -Name $RepositoryName -Folder $folder } "Folder"
if (-not $repo) {
    $repo = Try-AddRepo { Add-VBRBackupRepository -Name $RepositoryName -Path $folder } "Path"
}
if (-not $repo) {
    $repo = Try-AddRepo { Add-VBRBackupRepository -Name $RepositoryName -Folder $folder -Type WinLocal } "WinLocal"
}

if (-not $repo) {
    $normalized = $folder.TrimEnd('\')
    $repo = Get-VBRBackupRepository -ErrorAction SilentlyContinue | Where-Object {
        $p = Get-MoldRepoPathString $_
        $p -and ($p -eq $folder -or $p -eq $normalized -or $p -like "*\$KvmHostname")
    } | Select-Object -First 1
}

if ($repo) {
    Write-Host "Using repository: $($repo.Name) -> $folder"
    Write-Output $RepositoryName
    exit 0
}

Write-Error @"
Failed to create Veeam repository '$RepositoryName' at '$folder'.
Create manually in Veeam UI: Backup Infrastructure -> Backup Repositories -> Add -> Windows -> $folder
Errors:
$($errors -join "`n")
"@
exit 1
