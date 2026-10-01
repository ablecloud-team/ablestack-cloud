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

# Fixed read-only smoke. Never invokes the action script or service control.
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
# Configuration is data, never decompressed or evaluated as executable code.
$config='__CONFIG_JSON__' | ConvertFrom-Json
$proof=[ordered]@{requestId=$config.requestId;readBundle=$null;actionBundle=$null;readRuntime=$false;actionRuntime=$false;code='CHECK_FAILED'}
$root='C:\Program Files\ABLESTACK Process Tools'
function Matched([string]$profile) {
    foreach($bundle in $config.bundles) {
        if($bundle.profile -cne $profile){continue}
        $valid=$true
        foreach($item in $bundle.sha256.PSObject.Properties) {
            $path=Join-Path $root $item.Name
            if(-not (Test-Path -LiteralPath $path -PathType Leaf)){return $null}
            $file=Get-Item -LiteralPath $path
            if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $file.Length -gt 4194304){throw 'Unsafe adapter'}
            $digest=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            if($digest -cne $item.Value) {
                # Same CRLF/LF compatibility rule as the qemu-owned read bridge.
                if($profile -ceq 'windows-read' -and $item.Name -ceq 'ProcessList.ps1') {
                    $sha=[Security.Cryptography.SHA256]::Create()
                    try {$digest=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText($path).Replace([string][char]13+[char]10,[string][char]10))))).Replace('-','').ToLowerInvariant()}
                    finally {$sha.Dispose()}
                }
                if($digest -cne $item.Value){$valid=$false}
            }
        }
        if($valid){return $bundle.id}
    }
    return $null
}
try {
    if(-not (Test-Path -LiteralPath (Join-Path $root 'ProcessList.ps1'))) {$proof.code='TOOLS_REQUIRED'}
    else {
        $proof.readBundle=Matched $config.readProfile
        if(-not $proof.readBundle){$proof.code='TOOLS_REQUIRED'}
        else {
            $tokens=$null;$errors=$null
            $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'ProcessList.ps1'),[ref]$tokens,[ref]$errors)
            if($errors.Count -gt 0){throw 'Invalid read script'}
            Add-Type -Path (Join-Path $root 'AbleProcessIdentity.dll')
            $self=[AbleProcessIdentity]::Read([uint32]$PID)
            if(-not $self -or -not $self.Start){throw 'Read identity unavailable'}
            $proof.readRuntime=$true;$proof.code='READY'
            try {
                $proof.actionBundle=Matched $config.actionProfile
                if($proof.actionBundle) {
                    $tokens=$null;$errors=$null
                    $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'ProcessAction.ps1'),[ref]$tokens,[ref]$errors)
                    if($errors.Count -gt 0){throw 'Invalid action script'}
                    Add-Type -Path (Join-Path $root 'AbleProcessAction.dll')
                    $null=[AbleProcessAction]::Parse('{}')
                    $proof.actionRuntime=$true
                }
            } catch {$proof.actionRuntime=$false}
        }
    }
} catch {$proof.code='CHECK_FAILED'}
[Console]::WriteLine(($proof | ConvertTo-Json -Depth 5 -Compress))
