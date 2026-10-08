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

# Windows PowerShell 5.1 / Windows Server 2022.
# Execute ONE stage, shut down normally, and complete its NAS backup before the next stage.
# This script creates test files only; it never partitions/formats disks or requests backups.
# Sizes use PowerShell binary suffixes (1GB = 1 GiB).
# DATA: FULL = 500GiB; INC1 = +100GiB; INC2 = rewrite 50GiB, delete 100GiB, add 50GiB;
# INC3 = rewrite 50GiB, add 50GiB. Final DATA file size = 600GiB.
# Write sizes are not guarantees of changed-block counts or backup file sizes.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('FULL', 'INC1', 'INC2', 'INC3', 'VERIFY')]
    [string]$Stage,

    [ValidatePattern('^[A-Za-z]$')]
    [string]$DataDrive = 'D',

    [string]$ExpectedManifest
)

$ErrorActionPreference = 'Stop'
$DataDrive = $DataDrive.ToUpperInvariant()
$RootDrive = $env:SystemDrive.Substring(0, 1).ToUpperInvariant()
$RootDir = Join-Path ($env:SystemDrive + '\') 'NasRestoreTest\Root'
$DataDir = Join-Path ($DataDrive + ':\') 'NasRestoreTest\Data'
$ResultDir = Join-Path ($env:SystemDrive + '\') 'NasRestoreTest\Results'
$StatePath = Join-Path $ResultDir 'state.json'
$Dataset = 'DATA500-CHANGED100-V1'

if ($DataDrive -eq $RootDrive) {
    throw 'DataDrive must be the separate 1000GB data volume, not the Windows system volume.'
}
$DataVolume = Get-Volume -DriveLetter $DataDrive
if ($DataVolume.DriveType -ne 'Fixed' -or $DataVolume.Size -lt 900GB) {
    throw 'DataDrive must identify the approximately 1000GB fixed data volume. Check Get-Volume; D: may be a DVD drive.'
}

function Write-TestFile {
    param(
        [string]$Path,
        [long]$Bytes,
        [long]$Offset = 0,
        [switch]$Patch,
        [switch]$Zero
    )
    if ($Bytes -le 0 -or $Offset -lt 0) {
        throw 'Invalid write size or offset.'
    }
    if (-not $Patch -and $Offset -ne 0) {
        throw 'New files must start at offset zero.'
    }
    if (-not $Patch) {
        $Volume = Get-Volume -DriveLetter ($Path.Substring(0, 1))
        if ($Volume.SizeRemaining -lt ($Bytes + 2GB)) {
            throw "Insufficient guest free space to create $Path with 2GiB headroom."
        }
    }
    $Stream = $null
    $Random = $null
    try {
        $Mode = if ($Patch) { [IO.FileMode]::Open } else { [IO.FileMode]::CreateNew }
        $Stream = [IO.File]::Open($Path, $Mode, [IO.FileAccess]::Write, [IO.FileShare]::None)
        if ($Patch -and ($Offset -gt $Stream.Length -or $Bytes -gt ($Stream.Length - $Offset))) {
            throw "Patch exceeds existing file length: $Path"
        }
        [void]$Stream.Seek($Offset, [IO.SeekOrigin]::Begin)
        $Buffer = [byte[]]::new(8MB)
        if (-not $Zero) {
            $Random = [Security.Cryptography.RandomNumberGenerator]::Create()
        }
        [long]$Written = 0
        [long]$NextReport = 1GB
        while ($Written -lt $Bytes) {
            if (-not $Zero) {
                $Random.GetBytes($Buffer)
            }
            $Count = [int][Math]::Min([long]($Buffer.Length), [long]($Bytes - $Written))
            $Stream.Write($Buffer, 0, $Count)
            $Written += $Count
            if ($Written -ge $NextReport -or $Written -eq $Bytes) {
                Write-Progress -Activity $Path -Status ("{0:N2} / {1:N2} GiB" -f ($Written / 1GB), ($Bytes / 1GB)) `
                    -PercentComplete ([int](100.0 * $Written / $Bytes))
                $NextReport += 1GB
            }
        }
        $Stream.Flush($true)
    } finally {
        if ($null -ne $Stream) { $Stream.Dispose() }
        if ($null -ne $Random) { $Random.Dispose() }
        Write-Progress -Activity $Path -Completed
    }
    Write-Host "Written: $Path"
}

function Get-TestManifest {
    foreach ($Disk in @('ROOT', 'DATA')) {
        $Directory = if ($Disk -eq 'ROOT') { $RootDir } else { $DataDir }
        foreach ($File in (Get-ChildItem -LiteralPath $Directory -File | Sort-Object Name)) {
            $Hash = Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256
            [pscustomobject]@{
                Disk = $Disk
                File = $File.Name
                Length = $File.Length
                SHA256 = $Hash.Hash
            }
        }
    }
}

if ($Stage -eq 'VERIFY') {
    if ([string]::IsNullOrWhiteSpace($ExpectedManifest) -or -not (Test-Path -LiteralPath $ExpectedManifest -PathType Leaf)) {
        throw 'Supply -ExpectedManifest with the INC3.csv saved OUTSIDE the source VM before its INC3 backup.'
    }
    $Expected = @(Import-Csv -LiteralPath $ExpectedManifest)
    if ($Expected.Count -eq 0) { throw 'Expected manifest is empty.' }
    $Actual = @(Get-TestManifest)
    $ExpectedKeys = @($Expected | ForEach-Object { $_.Disk + '/' + $_.File })
    foreach ($Row in $Expected) {
        $Matches = @($Actual | Where-Object { $_.Disk -eq $Row.Disk -and $_.File -eq $Row.File })
        if ($Matches.Count -ne 1 -or $Matches[0].Length -ne [long]$Row.Length -or $Matches[0].SHA256 -ne $Row.SHA256) {
            throw "Missing file, size mismatch, or SHA256 mismatch: $($Row.Disk)/$($Row.File)"
        }
        Write-Host "OK: $($Row.Disk)/$($Row.File)"
    }
    foreach ($Row in $Actual) {
        if (($Row.Disk + '/' + $Row.File) -notin $ExpectedKeys) {
            throw "Unexpected restored file: $($Row.Disk)/$($Row.File)"
        }
    }
    foreach ($Path in @((Join-Path $RootDir 'delete.bin'), (Join-Path $DataDir 'delete.bin'))) {
        if (Test-Path -LiteralPath $Path) { throw "Deleted file unexpectedly exists: $Path" }
    }
    if ((Get-Content -LiteralPath (Join-Path $RootDir 'stage.txt') -Raw).Trim() -ne 'INC3') {
        throw 'Restored stage marker is not INC3.'
    }
    Write-Host 'PASS: both disks match the external INC3 manifest; deleted files are absent.'
    exit 0
}

if ($Stage -eq 'FULL') {
    if ((Test-Path -LiteralPath $RootDir) -or (Test-Path -LiteralPath $DataDir) -or (Test-Path -LiteralPath $StatePath)) {
        throw 'FULL requires a fresh test dataset. Existing data will not be overwritten.'
    }
    [void][IO.Directory]::CreateDirectory($RootDir)
    [void][IO.Directory]::CreateDirectory($DataDir)
    [void][IO.Directory]::CreateDirectory($ResultDir)
} else {
    if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) {
        throw 'Previous stage state is missing; start with FULL on the source VM.'
    }
    $State = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
    if ($State.Dataset -ne $Dataset) {
        throw 'Previous data belongs to a different test dataset. Use a fresh dataset and complete FULL with this script first.'
    }
    $Previous = @{ INC1 = 'FULL'; INC2 = 'INC1'; INC3 = 'INC2' }[$Stage]
    if ($State.Stage -ne $Previous -or $State.DataDrive -ne $DataDrive) {
        throw "Unexpected previous stage or drive. $Stage requires completed data stage $Previous on the same data volume."
    }
}

switch ($Stage) {
    'FULL' {
        Write-TestFile -Path (Join-Path $DataDir 'keep.bin') -Bytes 180GB
        Write-TestFile -Path (Join-Path $DataDir 'modify.bin') -Bytes 200GB
        Write-TestFile -Path (Join-Path $DataDir 'delete.bin') -Bytes 100GB
        Write-TestFile -Path (Join-Path $DataDir 'zeros.bin') -Bytes 20GB -Zero
        Write-TestFile -Path (Join-Path $RootDir 'root.bin') -Bytes 256MB
        Write-TestFile -Path (Join-Path $RootDir 'delete.bin') -Bytes 128MB
    }
    'INC1' {
        Write-TestFile -Path (Join-Path $DataDir 'inc1.bin') -Bytes 100GB
        Write-TestFile -Path (Join-Path $RootDir 'inc1.bin') -Bytes 128MB
    }
    'INC2' {
        Write-TestFile -Path (Join-Path $DataDir 'modify.bin') -Bytes 50GB -Patch
        Remove-Item -LiteralPath (Join-Path $DataDir 'delete.bin')
        Remove-Item -LiteralPath (Join-Path $RootDir 'delete.bin')
        Write-TestFile -Path (Join-Path $DataDir 'inc2.bin') -Bytes 50GB
        Write-TestFile -Path (Join-Path $RootDir 'root.bin') -Bytes 64MB -Patch
    }
    'INC3' {
        Write-TestFile -Path (Join-Path $DataDir 'modify.bin') -Bytes 40GB -Offset 160GB -Patch
        Write-TestFile -Path (Join-Path $DataDir 'inc1.bin') -Bytes 10GB -Offset 90GB -Patch
        Write-TestFile -Path (Join-Path $DataDir 'inc3.bin') -Bytes 50GB
        Write-TestFile -Path (Join-Path $RootDir 'root.bin') -Bytes 64MB -Offset 192MB -Patch
    }
}

Set-Content -LiteralPath (Join-Path $RootDir 'stage.txt') -Value $Stage -Encoding ASCII
$ManifestPath = Join-Path $ResultDir ($Stage + '.csv')
Write-Host 'Computing SHA256 for all current test files. Wait for this to finish.'
Get-TestManifest | Export-Csv -LiteralPath $ManifestPath -NoTypeInformation -Encoding UTF8
[pscustomobject]@{ Stage = $Stage; DataDrive = $DataDrive; Dataset = $Dataset } | ConvertTo-Json |
    Set-Content -LiteralPath $StatePath -Encoding UTF8
Write-Host "DATA STAGE COMPLETE: $Stage"
Write-Host "Manifest: $ManifestPath"
Write-Host "Save the manifest outside the VM, shut down normally, and complete the $Stage NAS backup before continuing."
Get-Volume -DriveLetter $RootDrive, $DataDrive |
    Select-Object DriveLetter, FileSystemType, Size, SizeRemaining
