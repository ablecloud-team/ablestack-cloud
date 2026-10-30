#!/usr/bin/env python3
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

"""Deploy the two public Agent hook wrappers over SSH; never transfer Job credentials."""

import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shlex
import shutil
import subprocess
import tempfile


RESULT_PREFIX = "ABLESTACK_VEEAM_DEPLOY_RESULT="


class ScriptDeploymentError(RuntimeError):
    pass


def deployment_directory(value):
    if not value or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ScriptDeploymentError("--veeam-script-dir requires an absolute directory without control characters")
    windows = bool(re.match(r"^[A-Za-z]:[/\\]", value))
    path = PureWindowsPath(value) if windows else PurePosixPath(value)
    if not path.is_absolute() or str(path) == path.anchor or ".." in path.parts:
        raise ScriptDeploymentError("--veeam-script-dir must be an absolute Windows drive or Linux directory other than its root")
    if windows and any(char in value[2:] for char in ':*?"<>|'):
        raise ScriptDeploymentError("--veeam-script-dir contains an invalid Windows path character")
    if windows and any(part.endswith((".", " ")) for part in path.parts[1:]):
        raise ScriptDeploymentError("--veeam-script-dir cannot contain Windows path components ending in a dot or space")
    # UNC paths are deliberately excluded: the destination is local to the SSH server.
    if value.startswith("//") or (not windows and "\\" in value):
        raise ScriptDeploymentError("--veeam-script-dir must identify a local directory on the Veeam server")
    return ("windows" if windows else "linux"), str(path)


def validate_deployment(args):
    if not args.veeam_script_dir:
        return
    deployment_directory(args.veeam_script_dir)
    if not shutil.which("ssh"):
        raise ScriptDeploymentError("Install the OpenSSH client before using --veeam-script-dir")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.:%_-]*", args.veeam_host or ""):
        raise ScriptDeploymentError("Automatic deployment requires a Veeam hostname or IP without SSH options")
    if not args.veeam_ssh_user or any(ord(char) < 32 or ord(char) == 127 for char in args.veeam_ssh_user):
        raise ScriptDeploymentError("Automatic deployment requires a valid --veeam-ssh-user")
    if args.veeam_ssh_key and not Path(args.veeam_ssh_key).is_file():
        raise ScriptDeploymentError("--veeam-ssh-key does not identify an existing private key file")


# The same program is executed in tests against a local temporary destination.
# Paths and bytes travel in JSON on stdin, never through remote shell expansion.
LINUX_PROGRAM = r'''
import platform
if platform.system() != "Linux":
    raise RuntimeError("Veeam server OS does not match the Linux --veeam-script-dir; no files were deployed")
import base64, fcntl, hashlib, json, os, re, shutil, tempfile, uuid
from datetime import datetime, timezone
from pathlib import Path

directory = Path(p["directory"])
for parent in (*reversed(directory.parents), directory):
    if parent.is_symlink():
        raise RuntimeError("Deployment directory contains a symlink")
directory.mkdir(parents=True, exist_ok=True)
lock_path = directory / ".ablestack-veeam-deploy.lock"
fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
with os.fdopen(fd, "r+") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    entries = p["files"]
    if len(entries) != 2 or len({entry["name"] for entry in entries}) != 2:
        raise RuntimeError("Expected two different hook wrappers")
    for entry in entries:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+-(pre|post)-notify\.sh", entry["name"]):
            raise RuntimeError("Invalid hook wrapper name")
        data = base64.b64decode(entry["content"], validate=True)
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise RuntimeError("Invalid hook wrapper digest")
    owner_path = directory / ("." + entries[0]["name"][:-len("-pre-notify.sh")] + ".owner.json")
    def regular(path):
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise RuntimeError("Refusing to replace a non-regular deployment file")
    regular(owner_path)
    if owner_path.exists() and json.loads(owner_path.read_text()) != p["owner"]:
        raise RuntimeError("Destination filename belongs to another Host or Job; choose a different script directory")
    for entry in entries:
        regular(directory / entry["name"])
    def write(path, data, mode):
        fd, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=str(directory))
        try:
            with os.fdopen(fd, "wb") as stream:
                os.fchmod(stream.fileno(), mode)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, str(path))
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    # Claim the Host/Job identity before changing either wrapper, including on partial failure.
    if not owner_path.exists():
        write(owner_path, json.dumps(p["owner"], sort_keys=True).encode(), 0o600)
    result = []
    for entry in entries:
        target = directory / entry["name"]
        data = base64.b64decode(entry["content"])
        changed = not target.exists() or target.read_bytes() != data
        if changed:
            if target.exists():
                stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
                shutil.copy2(str(target), str(target) + ".bak." + stamp + "." + uuid.uuid4().hex)
            write(target, data, 0o755)
        else:
            target.chmod(0o755)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if digest != entry["sha256"]:
            raise RuntimeError("Remote hook verification failed")
        result.append(dict(name=entry["name"], sha256=digest, changed=changed))
    fd = os.open(str(directory), os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
print("ABLESTACK_VEEAM_DEPLOY_RESULT=" + json.dumps(dict(platform="linux", directory=p["directory"], files=result)))
'''


WINDOWS_PROGRAM = r'''
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Veeam server OS does not match the Windows --veeam-script-dir; no files were deployed'
}
$directory = [IO.Path]::GetFullPath($p.directory)
function Assert-Regular($path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    if ($null -ne $item) {
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refusing to replace a non-regular deployment file'
        }
    }
}
$parent = $directory
while ($parent) {
    if (Test-Path -LiteralPath $parent) {
        $item = Get-Item -LiteralPath $parent -Force
        if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Deployment directory contains a reparse point or non-directory'
        }
    }
    $parent = [IO.Path]::GetDirectoryName($parent)
}
[IO.Directory]::CreateDirectory($directory) | Out-Null
$lockPath = [IO.Path]::Combine($directory, '.ablestack-veeam-deploy.lock')
Assert-Regular $lockPath
$lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $entries = @($p.files)
    if ($entries.Count -ne 2 -or @($entries.name | Select-Object -Unique).Count -ne 2) { throw 'Expected two different hook wrappers' }
    $sha = [Security.Cryptography.SHA256]::Create()
    function Digest($bytes) { ([BitConverter]::ToString($sha.ComputeHash([byte[]]$bytes))).Replace('-', '').ToLowerInvariant() }
    foreach ($entry in $entries) {
        if ($entry.name -cnotmatch '^[A-Za-z0-9_.-]+-(pre|post)-notify\.sh$') { throw 'Invalid hook wrapper name' }
        if ((Digest ([Convert]::FromBase64String($entry.content))) -cne $entry.sha256) { throw 'Invalid hook wrapper digest' }
        Assert-Regular ([IO.Path]::Combine($directory, $entry.name))
    }
    $ownerPath = [IO.Path]::Combine($directory, '.' + ($entries[0].name -replace '-pre-notify\.sh$', '') + '.owner.json')
    Assert-Regular $ownerPath
    if ([IO.File]::Exists($ownerPath)) {
        $owner = [IO.File]::ReadAllText($ownerPath) | ConvertFrom-Json
        if ($owner.host -cne $p.owner.host -or $owner.job -cne $p.owner.job) {
            throw 'Destination filename belongs to another Host or Job; choose a different script directory'
        }
    }
    if (-not [IO.File]::Exists($ownerPath)) {
        $temporary = [IO.Path]::Combine($directory, '.' + [guid]::NewGuid().ToString('N') + '.tmp')
        try {
            [IO.File]::WriteAllText($temporary, ($p.owner | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
            [IO.File]::Move($temporary, $ownerPath)
        } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
    }
    $result = @()
    foreach ($entry in $entries) {
        $target = [IO.Path]::Combine($directory, $entry.name)
        $bytes = [Convert]::FromBase64String($entry.content)
        $changed = -not [IO.File]::Exists($target)
        if (-not $changed) { $changed = (Digest ([IO.File]::ReadAllBytes($target))) -cne $entry.sha256 }
        if ($changed) {
            $temporary = [IO.Path]::Combine($directory, '.' + [guid]::NewGuid().ToString('N') + '.tmp')
            try {
                [IO.File]::WriteAllBytes($temporary, $bytes)
                if ([IO.File]::Exists($target)) {
                    $backup = $target + '.bak.' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ') + '.' + [guid]::NewGuid().ToString('N')
                    [IO.File]::Replace($temporary, $target, $backup)
                } else { [IO.File]::Move($temporary, $target) }
            } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
        }
        $digest = Digest ([IO.File]::ReadAllBytes($target))
        if ($digest -cne $entry.sha256) { throw 'Remote hook verification failed' }
        $result += @{ name = $entry.name; sha256 = $digest; changed = $changed }
    }
    [Console]::Out.WriteLine('ABLESTACK_VEEAM_DEPLOY_RESULT=' + (@{ platform = 'windows'; directory = $p.directory; files = $result } | ConvertTo-Json -Depth 4 -Compress))
} finally { if ($null -ne $sha) { $sha.Dispose() }; $lock.Dispose() }
'''


@contextmanager
def ssh_auth(args):
    environment = os.environ.copy()
    # Do not let a calling shell provide an unrelated askpass program or password.
    for name in ("SSH_ASKPASS", "SSH_ASKPASS_REQUIRE", "ABLESTACK_VEEAM_DEPLOY_PASSWORD"):
        environment.pop(name, None)
    if args.veeam_ssh_key:
        yield ["-i", args.veeam_ssh_key, "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes"], environment
        return
    # The file contains no secret; OpenSSH obtains the password from its environment.
    with tempfile.TemporaryDirectory(prefix="veeam-hook-askpass-") as temporary:
        helper = Path(temporary) / "askpass"
        helper.write_text('#!/bin/sh\nprintf "%s\\n" "$ABLESTACK_VEEAM_DEPLOY_PASSWORD"\n')
        helper.chmod(0o700)
        environment.update(SSH_ASKPASS=str(helper), SSH_ASKPASS_REQUIRE="force",
                           DISPLAY=environment.get("DISPLAY") or ":0",
                           ABLESTACK_VEEAM_DEPLOY_PASSWORD=args.veeam_password)
        yield ["-o", "BatchMode=no", "-o", "PreferredAuthentications=password,keyboard-interactive"], environment


def deploy_hooks(args, etc, host):
    validate_deployment(args)
    platform, directory = deployment_directory(args.veeam_script_dir)
    safe_job = re.sub(r"[^A-Za-z0-9._-]", "_", args.job_name)
    entries = []
    for hook in ("pre", "post"):
        name = safe_job + "-" + hook + "-notify.sh"
        source = etc / name
        if source.is_symlink() or not source.is_file():
            raise ScriptDeploymentError("Generated hook wrapper is missing or is not a regular file: " + name)
        content = source.read_bytes()
        entries.append(dict(name=name, content=base64.b64encode(content).decode(), sha256=hashlib.sha256(content).hexdigest()))
    payload = dict(directory=directory, owner=dict(host=host["name"], job=args.job_name), files=entries,
                   program=WINDOWS_PROGRAM if platform == "windows" else LINUX_PROGRAM)
    if platform == "windows":
        bootstrap = ("$ErrorActionPreference='Stop';try{"
                     "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"
                     "$p=[Console]::In.ReadToEnd()|ConvertFrom-Json;"
                     "& ([scriptblock]::Create($p.program))"
                     "}catch{[Console]::Error.WriteLine($_.Exception.Message);exit 1}")
        encoded = base64.b64encode(bootstrap.encode("utf-16-le")).decode()
        command = "powershell.exe -NoProfile -NonInteractive -EncodedCommand " + encoded
    else:
        command = "python3 -c " + shlex.quote("import json,sys; p=json.load(sys.stdin); exec(p.pop('program'))")
    try:
        with ssh_auth(args) as (auth, environment):
            result = subprocess.run(
                ["ssh", "-T", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15",
                 "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=3", "-o", "NumberOfPasswordPrompts=1",
                 *auth, "-l", args.veeam_ssh_user, args.veeam_host, command],
                input=json.dumps(payload, ensure_ascii=True), text=True, encoding="utf-8", errors="replace",
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment, start_new_session=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise ScriptDeploymentError("Veeam hook deployment timed out; completed files are retained. Rerun with the same --veeam-script-dir") from None
    if result.returncode:
        detail = result.stderr
        for secret in (args.veeam_password, args.admin_apikey, args.admin_secretkey):
            if secret:
                detail = detail.replace(secret, "[redacted]")
        detail = " ".join(detail.split())[:500]
        raise ScriptDeploymentError(f"Veeam hook SSH deployment failed (exit {result.returncode}): {detail}")
    responses = [line[len(RESULT_PREFIX):] for line in result.stdout.splitlines() if line.startswith(RESULT_PREFIX)]
    try:
        if len(responses) != 1:
            raise ValueError("missing deployment receipt")
        receipt = json.loads(responses[0])
        actual = receipt["files"]
        expected = {entry["name"]: entry["sha256"] for entry in entries}
        if (receipt["platform"] != platform or receipt["directory"] != directory or len(actual) != 2
                or {entry["name"]: entry["sha256"] for entry in actual} != expected
                or any(type(entry["changed"]) is not bool for entry in actual)):
            raise ValueError("invalid deployment receipt")
    except (KeyError, TypeError, ValueError):
        raise ScriptDeploymentError("Cannot verify the two Veeam hook files; rerun with the same --veeam-script-dir") from None
    separator = "\\" if platform == "windows" else "/"
    by_name = {entry["name"]: entry for entry in actual}
    return [dict(path=directory + separator + entry["name"], changed=by_name[entry["name"]]["changed"]) for entry in entries]
