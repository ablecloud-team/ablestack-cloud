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

# Create (or update) a Veeam Linux Agent file-level backup job for Mold integration.
# Idempotent: safe to re-run. Registers Pre/Post bash hooks via install-veeam-job.ps1.
#
# Prerequisite: Veeam Agent for Linux on KVM host, connected to this B&R server.
#
# Example (run on Veeam B&R server as Administrator, PowerShell 7+):
#   .\create-veeam-agent-job.ps1 `
#     -JobName "Mold KVM Backup" `
#     -KvmHost "10.10.31.2" `
#     -AgentHostName "ablecube31-2" `
#     -BackupPath "/tmp/mold/veeam" `
#     -RepositoryName "Default Backup Repository"

param(
    [Parameter(Mandatory = $true)]
    [string]$JobName,

    [Parameter(Mandatory = $true)]
    [string]$KvmHost,

    [string]$AgentHostName = "",
    [string]$BackupPath = "/tmp/mold/veeam",
    [string]$RepositoryName = "",

    [string]$InstallDir = "C:\ProgramData\Mold\backup\veeam",
  # Custom PG for PowerShell job creation (not "Manually Added").
    [string]$ProtectionGroupName = "Mold KVM Agents",

    [string]$AgentPreNotifyScript = "/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh",
    [string]$AgentPostNotifyScript = "/etc/ablestack/veeam/ablestack_veeam_post_notify.sh",

    # Linux SSH for Protection Group (required — temporary certificate fails with
    # "Cannot find credentials for agent <ip>" on managed Linux agents).
    [string]$KvmSshUser = "root",
    [string]$KvmSshPassword = "",

    [switch]$SkipScriptRegistration,
    [switch]$StartJob,
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "Veeam.Backup.PowerShell requires PowerShell 7+. Run: pwsh -File $PSCommandPath"
}

if (-not $KvmSshPassword -and $env:MOLD_KVM_SSH_PASSWORD) {
    $KvmSshPassword = $env:MOLD_KVM_SSH_PASSWORD
}
if (-not $KvmSshUser) { $KvmSshUser = "root" }

function Write-MoldVeeamInfo([string]$Message) { Write-Host "[mold-veeam] $Message" }
function Write-MoldVeeamWarn([string]$Message) { Write-Warning "[mold-veeam] $Message" }

function Connect-MoldVeeamSession {
    if (-not (Get-Command Connect-VBRServer -ErrorAction SilentlyContinue)) { return }
    $sessions = @()
    try { $sessions = @(Get-VBRServerSession -ErrorAction SilentlyContinue) } catch { }
    if ($sessions.Count -eq 0) {
        Write-MoldVeeamInfo "Connecting to local Veeam B&R (localhost)..."
        Connect-VBRServer -Server localhost | Out-Null
    }
}

function Invoke-MoldVeeamCmdlet {
    param(
        [scriptblock]$Command,
        [string]$Context = "Veeam cmdlet"
    )
    try {
        return & $Command
    } catch {
        if ($_.Exception.Message -match 'Identity service') {
            throw @"
$Context failed: Veeam Identity service is unreachable.

On this B&R server (Administrator pwsh):
  Get-Service VeeamBackupSvc, VeeamBrokerSvc | Restart-Service
  Connect-VBRServer -Server localhost
  Get-VBRProtectionGroup | Select-Object -First 3 Name

Then re-run setup-veeam-mold-job.ps1
"@
        }
        throw
    }
}

function Get-MoldVeeamProtectionGroupByName {
    param([string]$Name)
    if (-not $Name) { return $null }
    try {
        return Get-VBRProtectionGroup -Name $Name -ErrorAction Stop
    } catch {
        if ($_.Exception.Message -match 'does not exist|not found|Cannot find') {
            return $null
        }
        if ($_.Exception.Message -match 'Identity service') {
            throw @"
Get-VBRProtectionGroup -Name '$Name' failed: Veeam Identity service is unreachable.

On this B&R server (Administrator pwsh):
  Get-Service VeeamBackupSvc, VeeamBrokerSvc | Restart-Service
  Connect-VBRServer -Server localhost
  Get-VBRProtectionGroup | Select-Object -First 3 Name

Then re-run setup-veeam-mold-job.ps1
"@
        }
        throw
    }
}

function Get-MoldVeeamPropertyValue {
    param($Object, [string[]]$Names)
    foreach ($name in $Names) {
        if ($null -eq $Object) { continue }
        if ($Object.PSObject.Properties.Name -contains $name) {
            $val = $Object.$name
            if ($null -ne $val -and "$val" -ne "") { return "$val" }
        }
    }
    return ""
}

function Test-MoldVeeamHostMatch {
    param($Computer, [string]$HostIp, [string]$HostName)
    $candidates = @(
        (Get-MoldVeeamPropertyValue $Computer @("Name", "ComputerName", "HostName", "DnsName"))
        (Get-MoldVeeamPropertyValue $Computer @("IPAddress", "IpAddress", "Address"))
        (Get-MoldVeeamPropertyValue $Computer @("DisplayName", "Description"))
    ) | Where-Object { $_ -ne "" }

    foreach ($c in $candidates) {
        if ($HostName -and ($c -eq $HostName -or $c -like "*$HostName*")) { return $true }
        if ($HostIp -and ($c -eq $HostIp -or $c -like "*$HostIp*")) { return $true }
    }
    return $false
}

function Get-MoldVeeamDiscoveredComputers {
    param([string]$GroupName = "")

    if ($GroupName) {
        $group = Get-MoldVeeamProtectionGroupByName -Name $GroupName
        if ($group) {
            return @(
                Invoke-MoldVeeamCmdlet -Context "Get-VBRDiscoveredComputer (group $GroupName)" {
                    Get-VBRDiscoveredComputer -ProtectionGroup $group -ErrorAction SilentlyContinue
                }
            )
        }
        Write-MoldVeeamWarn "Protection group not found: $GroupName"
    }

    return @(
        Invoke-MoldVeeamCmdlet -Context "Get-VBRDiscoveredComputer" {
            Get-VBRDiscoveredComputer -ErrorAction SilentlyContinue
        }
    )
}

function Format-MoldVeeamDiscoveredComputerLine {
    param($Computer)
    $name = Get-MoldVeeamPropertyValue $Computer @("Name", "ComputerName", "HostName")
    $ip = Get-MoldVeeamPropertyValue $Computer @("IPAddress", "IpAddress", "Address")
    if ($name -and $ip) { return "${name} (${ip})" }
    if ($name) { return $name }
    if ($ip) { return $ip }
    return ($Computer | Out-String).Trim()
}

function Find-MoldVeeamDiscoveredComputer {
    param([string]$HostIp, [string]$HostName, [string]$GroupName)

    $computers = @(Get-MoldVeeamDiscoveredComputers -GroupName $GroupName)

    $match = $computers | Where-Object { Test-MoldVeeamHostMatch -Computer $_ -HostIp $HostIp -HostName $HostName } | Select-Object -First 1
    if ($match) { return $match }

    if ($HostName) {
        $match = $computers | Where-Object {
            $n = Get-MoldVeeamPropertyValue $_ @("Name", "ComputerName", "HostName")
            $n -and ($n -eq $HostName)
        } | Select-Object -First 1
        if ($match) { return $match }
    }

    return $null
}

function Test-MoldVeeamHostInContainer {
    param($Container, [string[]]$Targets)
    $hostNames = @()
    if ($Container -and $Container.CustomCredentials) {
        foreach ($c in @($Container.CustomCredentials)) {
            $h = Get-MoldVeeamPropertyValue $c @("HostName", "Name")
            if ($h) { $hostNames += $h }
        }
    }
    foreach ($t in $Targets) {
        if ($hostNames -contains $t) { return $true }
        if (($hostNames | Where-Object { $_ -like "*$t*" }).Count -gt 0) { return $true }
    }
    return $false
}

function Ensure-MoldVeeamLinuxKvmCredentialRecord {
    param(
        [string]$User,
        [string]$Password
    )
    if (-not $Password) {
        throw "KvmSshPassword is required (KVM_SSH_PASSWORD in mold-backup.windows.conf or -KvmSshPassword)"
    }

    $desc = "Mold KVM Linux SSH ($User)"
    $records = @(Get-VBRCredentials -ErrorAction SilentlyContinue)
    $match = $records | Where-Object {
        "$($_.Description)" -like "*Mold KVM Linux SSH*"
    } | Select-Object -First 1

    if (-not $match) {
        $match = $records | Where-Object {
            (Get-MoldVeeamPropertyValue $_ @("User", "UserName")) -eq $User
        } | Select-Object -First 1
    }

    if ($match) {
        Write-MoldVeeamInfo "Updating Veeam credentials record for KVM Linux user: $User"
        try {
            Set-VBRCredentials -Credential $match -User $User -Password $Password -Description $desc -SshPort 22 | Out-Null
        } catch {
            Set-VBRCredentials -Credential $match -Password $Password | Out-Null
        }
        return $match
    }

    Write-MoldVeeamInfo "Creating Veeam Linux credentials record for KVM user: $User"
    return Add-VBRCredentials -Type Linux -User $User -Password $Password -Description $desc -SshPort 22
}

function New-MoldVeeamLinuxKvmComputerCredential {
    param(
        [string]$HostIp,
        $VeeamCredentials,
        [string]$User
    )

    $last = ""
    $credName = Get-MoldVeeamPropertyValue $VeeamCredentials @("User", "UserName", "Name")
    if (-not $credName) { $credName = $User }

    $attempts = @(
        { New-VBRIndividualComputerCustomCredentials -HostName $HostIp -Credentials $VeeamCredentials },
        { New-VBRIndividualComputerCustomCredentials -HostName $HostIp -Credentials $credName }
    )

    foreach ($attempt in $attempts) {
        try {
            $cred = & $attempt
            Write-MoldVeeamInfo "PG computer OK: ${User}@${HostIp}"
            return $cred
        } catch {
            $last = $_.Exception.Message
        }
    }
    throw "New-VBRIndividualComputerCustomCredentials failed for ${User}@${HostIp}: $last"
}

function Ensure-MoldVeeamAgentProtectionGroup {
    param(
        [string]$HostIp,
        [string]$HostName,
        [string]$GroupName,
        [string]$KvmSshUser = "root",
        [string]$KvmSshPassword = ""
    )

    $targets = @($HostIp)
    if ($HostName -and $HostName -ne $HostIp) { $targets += $HostName }

    $pg = Get-MoldVeeamProtectionGroupByName -Name $GroupName

    if ($KvmSshPassword) {
        Write-MoldVeeamInfo "Protection group: $GroupName (Individual computers + Linux SSH for KVM)"
        $veeamCredRecord = Ensure-MoldVeeamLinuxKvmCredentialRecord -User $KvmSshUser -Password $KvmSshPassword

        $targetIps = @($HostIp)
        if ($pg) {
            $existingIps = @(
                Get-VBRDiscoveredComputer -ProtectionGroup $pg -ErrorAction SilentlyContinue | ForEach-Object {
                    $ip = $null
                    if ($_.IPAddress) { $ip = @($_.IPAddress)[0] }
                    if (-not $ip) { $ip = $_.Name }
                    $ip
                } | Where-Object { $_ -match '^\d{1,3}(\.\d{1,3}){3}$' }
            )
            if ($existingIps.Count -gt 0) {
                Write-MoldVeeamInfo "Existing PG computers kept: $($existingIps -join ', ')"
            }
            $targetIps = @($existingIps + $HostIp | Select-Object -Unique)
        }

        $creds = $targetIps | ForEach-Object {
            New-MoldVeeamLinuxKvmComputerCredential -HostIp $_ -VeeamCredentials $veeamCredRecord -User $KvmSshUser
        }
        $container = New-VBRIndividualComputerContainer -CustomCredentials $creds

        if (-not $pg) {
            $pg = Add-VBRProtectionGroup -Name $GroupName `
                -Description "Mold KVM Linux agents (Linux SSH credentials)" `
                -Container $container
        } else {
            Set-VBRProtectionGroup -ProtectionGroup $pg -Container $container | Out-Null
            $pg = Get-MoldVeeamProtectionGroupByName -Name $GroupName
        }
    } else {
        Write-MoldVeeamWarn @"
KvmSshPassword not set — using temporary certificate (often fails with:
  Cannot find credentials for agent $HostIp

Set KVM_SSH_PASSWORD in mold-backup.windows.conf and re-run setup-veeam-mold-job.ps1
"@
        if (-not $pg) {
            Write-MoldVeeamInfo "Creating protection group: $GroupName (Linux, certificate auth)"
            $creds = @($HostIp) | ForEach-Object {
                New-VBRIndividualComputerCustomCredentials -HostName $_ -UseTemporaryCertificate
            }
            $container = New-VBRIndividualComputerContainer -CustomCredentials $creds
            $pg = Add-VBRProtectionGroup -Name $GroupName `
                -Description "Mold KVM Linux agents — used by setup-veeam-mold-job.ps1" `
                -Container $container
        } elseif (-not (Test-MoldVeeamHostInContainer -Container $pg.Container -Targets $targets)) {
            Write-MoldVeeamInfo "Adding $HostIp to protection group: $GroupName"
            $comps = @($pg.Container.CustomCredentials)
            $comps += New-VBRIndividualComputerCustomCredentials -HostName $HostIp -UseTemporaryCertificate
            $newContainer = Set-VBRIndividualComputerContainer -Container $pg.Container -CustomCredentials $comps
            Set-VBRProtectionGroup -ProtectionGroup $pg -Container $newContainer | Out-Null
            $pg = Get-MoldVeeamProtectionGroupByName -Name $GroupName
        }
    }

    Write-MoldVeeamInfo "Rescanning protection group: $GroupName"
    Rescan-VBREntity -Entity $pg | Out-Null
    return $pg
}

function Get-MoldVeeamSingleObject {
    param($Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Array]) {
        if ($Value.Count -eq 0) { return $null }
        return $Value[0]
    }
    return $Value
}

function Get-MoldVeeamBackupRepository {
    param([string]$PreferredName)

    $candidates = @()
    if ($PreferredName) {
        $candidates = @(Get-VBRBackupRepository -Name $PreferredName -ErrorAction SilentlyContinue)
    }
    if ($candidates.Count -eq 0) {
        $candidates = @(Get-VBRBackupRepository -ErrorAction SilentlyContinue)
    }
    if ($candidates.Count -eq 0) {
        throw "No Veeam backup repository found. Run: Get-VBRBackupRepository | Select-Object Name, Id"
    }

    $pick = $candidates | Where-Object { $_.Name -like "*Default*" } | Select-Object -First 1
    if (-not $pick) { $pick = $candidates | Select-Object -First 1 }
    $pick = Get-MoldVeeamSingleObject $pick
    if (-not $pick -or -not $pick.Name) {
        throw "Could not resolve a backup repository object."
    }

    # Re-fetch by exact name — avoids pipeline/array wrapper issues with Add-VBRComputerBackupJob.
    $repo = Get-MoldVeeamSingleObject (Get-VBRBackupRepository -Name $pick.Name -ErrorAction SilentlyContinue)
    if (-not $repo) { $repo = $pick }

    Write-MoldVeeamInfo "Using backup repository: $($repo.Name) (type=$($repo.GetType().Name))"
    return $repo
}

function Get-MoldVeeamBackupServerName {
    $server = $null
    try { $server = Get-MoldVeeamSingleObject (Get-VBRServer -ErrorAction SilentlyContinue) } catch { }
    if ($server) {
        foreach ($key in @("Name", "DnsHostName", "DisplayName")) {
            if ($server.PSObject.Properties.Name -contains $key -and $server.$key) {
                return "$($server.$key)"
            }
        }
    }
    return $env:COMPUTERNAME
}

function New-MoldVeeamComputerBackupJob {
    param(
        [string]$JobName,
        [string]$Description,
        $BackupObject,
        $Scope,
        $Repository
    )

    $common = @{
        OSPlatform           = "Linux"
        Type                 = "Server"
        Mode                 = "ManagedByBackupServer"
        Name                 = $JobName
        Description          = $Description
        BackupObject         = $BackupObject
        BackupType           = "SelectedFiles"
        SelectedFilesOptions = $Scope
    }

    try {
        Add-VBRComputerBackupJob @common -BackupRepository $Repository | Out-Null
        Write-MoldVeeamInfo "Created Agent backup job via -BackupRepository"
        return
    } catch {
        $msg = $_.Exception.Message
        if ($msg -notmatch 'BackupRepository|Destination') { throw }
        Write-MoldVeeamWarn "Add-VBRComputerBackupJob -BackupRepository failed: $msg"
    }

    $serverName = Get-MoldVeeamBackupServerName
    Write-MoldVeeamInfo "Retrying with -DestinationOptions (BackupServerName=$serverName)"
    $destination = New-VBRComputerDestinationOptions `
        -OSPlatform Linux `
        -BackupRepository $Repository `
        -BackupServerName $serverName
    Add-VBRComputerBackupJob @common -DestinationOptions $destination -BackupRepository $Repository | Out-Null
    Write-MoldVeeamInfo "Created Agent backup job via -DestinationOptions + -BackupRepository"
}

function Assert-MoldVeeamLinuxAgentPath {
    param([string]$Path)
    if ($Path -match '^[A-Za-z]:[\\/]') {
        throw "Linux Agent SelectedFiles path must be on the KVM host (e.g. /tmp/mold/veeam), not: $Path"
    }
    if (-not $Path.StartsWith("/")) {
        throw "Linux Agent SelectedFiles path must be absolute (start with /): $Path"
    }
}

function New-MoldVeeamFileLevelScope {
    param([string]$Path)
    Assert-MoldVeeamLinuxAgentPath -Path $Path
    New-VBRSelectedFilesBackupOptions -OSPlatform Linux -BackupSelectedFiles -SelectedFiles $Path
}

function Update-MoldVeeamAgentJobScope {
    param($Job, [string]$Path)
    $scope = New-MoldVeeamFileLevelScope -Path $Path
    Set-VBRComputerBackupJob -Job $Job -BackupType SelectedFiles -SelectedFilesOptions $scope | Out-Null
    Write-MoldVeeamInfo "Updated job scope to file-level path: $Path"
}

if (-not $AgentHostName) {
    try {
        $resolved = [System.Net.Dns]::GetHostEntry($KvmHost)
        if ($resolved.HostName) {
            $AgentHostName = ($resolved.HostName -split '\.')[0]
        }
    } catch {
        $AgentHostName = $KvmHost
    }
}

Write-MoldVeeamInfo "Job=$JobName KVM=$KvmHost agentHost=$AgentHostName path=$BackupPath (script=mold-veeam-v3)"

Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
Connect-MoldVeeamSession

# PowerShell cannot add individual computers from "Manually Added" — use a dedicated PG.
$protectionGroup = Ensure-MoldVeeamAgentProtectionGroup `
    -HostIp $KvmHost `
    -HostName $AgentHostName `
    -GroupName $ProtectionGroupName `
    -KvmSshUser $KvmSshUser `
    -KvmSshPassword $KvmSshPassword

$discovered = Find-MoldVeeamDiscoveredComputer -HostIp $KvmHost -HostName $AgentHostName -GroupName $ProtectionGroupName
if ($discovered) {
    $discName = Get-MoldVeeamPropertyValue $discovered @("Name", "ComputerName", "HostName")
    Write-MoldVeeamInfo "Found discovered computer in '$ProtectionGroupName': $discName"
} else {
    $all = @(Get-MoldVeeamDiscoveredComputers)
    $knownList = if ($all.Count -gt 0) {
        ($all | ForEach-Object { Format-MoldVeeamDiscoveredComputerLine $_ }) -join "`n  - "
    } else {
        "(none)"
    }
    Write-MoldVeeamWarn @"
KVM not yet visible in '$ProtectionGroupName' after rescan.
Job will still be created with protection group '$ProtectionGroupName'.

On KVM, ensure agent is connected to B&R:
  veeamconfig vbrserver add --name vbr01 --address <B&R-IP> --port 10006 --login administrator --password '...'
  veeamconfig vbrserver list

Open firewall on KVM from B&R: tcp/6160, tcp/10006

Known discovered computers:
  - $knownList
"@
}

$repository = Get-MoldVeeamBackupRepository -PreferredName $RepositoryName
$scope = New-MoldVeeamFileLevelScope -Path $BackupPath
$backupObject = @($protectionGroup)
Write-MoldVeeamInfo "Backup object: protection group '$ProtectionGroupName' (not Manually Added computer)"
Write-MoldVeeamInfo "Backup repository: $($repository.Name)"

$existing = Get-VBRComputerBackupJob -Name $JobName -ErrorAction SilentlyContinue
if ($existing) {
    Write-MoldVeeamInfo "Agent backup job already exists: $JobName"
    if (-not $WhatIf) {
        Update-MoldVeeamAgentJobScope -Job $existing -Path $BackupPath
    }
    } else {
        Write-MoldVeeamInfo "Creating Agent backup job: $JobName"
        if ($WhatIf) {
            Write-MoldVeeamInfo "WhatIf: would call Add-VBRComputerBackupJob"
        } else {
            New-MoldVeeamComputerBackupJob `
                -JobName $JobName `
                -Description "Mold NAS backup + file-level staging ($BackupPath)" `
                -BackupObject $backupObject `
                -Scope $scope `
                -Repository $repository
            Write-MoldVeeamInfo "Created Agent backup job: $JobName"
        }
    }

if ($SkipScriptRegistration) {
    Write-MoldVeeamInfo "SkipScriptRegistration set — done."
    exit 0
}

$installScript = Join-Path $InstallDir "install-veeam-job.ps1"
if (-not (Test-Path $installScript)) {
    $installScript = Join-Path $PSScriptRoot "install-veeam-job.ps1"
}
if (-not (Test-Path $installScript)) {
    throw "install-veeam-job.ps1 not found under $InstallDir or $PSScriptRoot"
}

if ($WhatIf) {
    Write-MoldVeeamInfo "WhatIf: would register Pre/Post via $installScript"
    exit 0
}

function Invoke-MoldVeeamInstallJob {
    param(
        [string]$ScriptPath,
        [string]$JobName,
        [string]$InstallDir,
        [string]$KvmHost,
        [string]$ProtectionGroupName,
        [string]$AgentPreNotifyScript,
        [string]$AgentPostNotifyScript
    )

    $installParams = @{
        JobName               = $JobName
        InstallDir            = $InstallDir
        KvmHost               = $KvmHost
        ProtectionGroupName   = $ProtectionGroupName
        AgentPreNotifyScript  = $AgentPreNotifyScript
        AgentPostNotifyScript = $AgentPostNotifyScript
    }

    $cmd = Get-Command $ScriptPath -ErrorAction Stop
    if ($cmd.Parameters.ContainsKey("LinuxAgent")) {
        $installParams["LinuxAgent"] = $true
    } else {
        Write-MoldVeeamWarn "install-veeam-job.ps1 is outdated (no -LinuxAgent). Update from repo: curl install-veeam-job.ps1"
    }

    & $ScriptPath @installParams
}

Invoke-MoldVeeamInstallJob `
    -ScriptPath $installScript `
    -JobName $JobName `
    -InstallDir $InstallDir `
    -KvmHost $KvmHost `
    -ProtectionGroupName $ProtectionGroupName `
    -AgentPreNotifyScript $AgentPreNotifyScript `
    -AgentPostNotifyScript $AgentPostNotifyScript

if ($StartJob) {
    $jobToRun = Get-VBRComputerBackupJob -Name $JobName -ErrorAction SilentlyContinue
    if ($jobToRun) {
        Write-MoldVeeamInfo "Starting job: $JobName"
        Start-VBRComputerBackupJob -Job $jobToRun | Out-Null
    }
}

Write-MoldVeeamInfo "Done. Scope=SelectedFiles path=$BackupPath (VM export only, not entire host)."
Write-MoldVeeamInfo "KVM: set VM targets — /etc/ablestack/veeam/veeam_config.sh --job-name '$JobName' --vm-include 'i-2-7-VM'"
Write-MoldVeeamInfo "Start: Veeam UI -> Jobs -> $JobName -> Start"
Write-MoldVeeamInfo "KVM log: tail -f /var/log/mold/veeam-hook.log"
