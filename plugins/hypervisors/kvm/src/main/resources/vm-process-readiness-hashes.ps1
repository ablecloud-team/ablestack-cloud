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

# Fixed hash observation only. No guest adapter code or environment data is loaded.
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$hashProof=[ordered]@{requestId=('__REQUEST_JSON__'|ConvertFrom-Json);sha256=[ordered]@{};normalizedList=$null}
$root='C:\Program Files\ABLESTACK Process Tools'
foreach($name in @('ProcessList.ps1','AbleProcessIdentity.dll','ProcessAction.ps1','AbleProcessAction.dll')) {
    $path=Join-Path $root $name
    $hashProof.sha256[$name]=$null
    if(-not (Test-Path -LiteralPath $path -PathType Leaf)){continue}
    $file=Get-Item -LiteralPath $path
    if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $file.Length -gt 4194304){throw 'Unsafe adapter'}
    $hashProof.sha256[$name]=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if($name -ceq 'ProcessList.ps1') {
        $sha=[Security.Cryptography.SHA256]::Create()
        try {$hashProof.normalizedList=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText($path).Replace([string][char]13+[char]10,[string][char]10))))).Replace('-','').ToLowerInvariant()}
        finally {$sha.Dispose()}
    }
}
[Console]::WriteLine(($hashProof|ConvertTo-Json -Depth 4 -Compress))
