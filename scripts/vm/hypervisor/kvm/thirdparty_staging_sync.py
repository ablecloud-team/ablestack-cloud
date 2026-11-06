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

"""Discover and compare-and-set generated staging path fields without sourcing shell configuration."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
import time

PROVIDERS = {"ablestack-netbackup", "ablestack-veeam"}
ASSIGNMENT = re.compile(r"^(\s*(?:export\s+)?)([A-Z][A-Z0-9_]*)(\s*=)(.*?)(\r?\n)?$")


def safe_name(value):
    name = re.sub(r"[^A-Za-z0-9._-]", "_", value)
    if name in {"", ".", ".."}:
        raise ValueError("Invalid policy, Job or Host identity")
    return name


def literal(value, path, key):
    try:
        words = shlex.split(value, comments=True)
    except ValueError:
        raise ValueError(f"Invalid literal {key} in {path.name}") from None
    if len(words) > 1 or any(c in (words[0] if words else "") for c in "\n\r\x00"):
        raise ValueError(f"Invalid literal {key} in {path.name}")
    # Never evaluate shell expressions when discovering a generated path or identity.
    if any(c in value for c in ("$", "`")):
        raise ValueError(f"Dynamic {key} in {path.name} requires host reconfiguration")
    return words[0] if words else ""


def read_config(path, keys):
    if path.is_symlink() or not path.is_file() or path.resolve() != path:
        raise ValueError(f"Refusing non-regular or redirected configuration: {path.name}")
    data = path.read_bytes()
    values = {}
    for line in data.decode("utf-8").splitlines(keepends=True):
        match = ASSIGNMENT.match(line)
        if match and match[2] in keys:
            values[match[2]] = literal(match[4], path, match[2])
    return data, values


def replace_paths(data, replacements):
    lines = []
    seen = set()
    for line in data.decode("utf-8").splitlines(keepends=True):
        match = ASSIGNMENT.match(line)
        if match and match[2] in replacements:
            key = match[2]
            old = literal(match[4], Path("configuration"), key)
            if old != replacements[key]:
                line = match[1] + key + match[3] + shlex.quote(replacements[key]) + (match[5] or "\n")
            seen.add(key)
        lines.append(line)
    for key, value in replacements.items():
        if key not in seen:
            if lines and not lines[-1].endswith("\n"):
                lines[-1] += "\n"
            lines.append(key + "=" + shlex.quote(value) + "\n")
    return "".join(lines).encode("utf-8")


def prepare_payload(configuration, payload):
    helper = Path(__file__).with_name("thirdparty_staging.sh")
    mode = configuration.get("localMode") or "MOUNT"
    args = ["bash", str(helper), configuration["type"], configuration["rootPath"],
            configuration["mountPath"], configuration.get("nfsSource") or "", configuration.get("mountOptions") or "",
            payload, "15", "0"]
    if mode == "DIRECTORY":
        args.append(mode)
    result = subprocess.run(args, capture_output=True, text=True, timeout=22)
    if result.returncode:
        raise ValueError("Unable to prepare synchronized parent path: " + result.stdout.strip()[-1000:] + result.stderr.strip()[-1000:])


def require_watcher_idle(provider):
    unit = "netbackup-host-restore-watcher.service" if provider == "ablestack-netbackup" else "mold-veeam-restore-agent.service"
    until = time.monotonic() + 15
    while True:
        result = subprocess.run(["systemctl", "show", "--property=ActiveState", "--value", unit], capture_output=True, text=True, timeout=3)
        state = result.stdout.strip()
        if result.returncode or state not in {"inactive", "failed", "active", "activating", "reloading", "deactivating", "refreshing", "maintenance"}:
            raise ValueError("Restore watcher state is unconfirmed; retry staging synchronization: " + unit)
        if state in {"inactive", "failed"}:
            return
        if time.monotonic() >= until:
            raise ValueError("Restore watcher is still running; retry staging synchronization: " + unit)
        time.sleep(0.2)


class Synchronizer:
    def __init__(self, netbackup=Path("/etc/ablestack/netbackup"), veeam=Path("/etc/ablestack/veeam")):
        self.netbackup = netbackup
        self.veeam = veeam

    def discover(self, request):
        configuration = request["configuration"]
        root = configuration["rootPath"]
        if (not root.startswith("/") or root == "/" or os.path.normpath(root) != root
                or any(c in root for c in "\r\n\x00")):
            raise ValueError("Invalid staging synchronization root")
        if configuration.get("version") != 1 or configuration["type"] not in {"LOCAL", "NFS", "GFS2"}:
            raise ValueError("Invalid staging synchronization configuration")
        host = request["hostName"]
        if not host or any(c in host for c in "\r\n\x00/"):
            raise ValueError("Invalid staging synchronization Host")
        providers = set(request["providers"])
        if not providers.issubset(PROVIDERS):
            raise ValueError("Unsupported staging synchronization provider")
        files, bindings = [], []

        def register(path, data, replacements):
            files.append((path, data, replace_paths(data, replacements)))

        if "ablestack-netbackup" in providers:
            for path in sorted(self.netbackup.glob("netbackup-host-*.conf")):
                keys = {"NETBACKUP_PARENT_PAYLOAD_PATH", "KVM_HOSTNAME", "NETBACKUP_POLICY_NAME", "BACKUP_STAGING_ROOT"}
                data, values = read_config(path, keys)
                if values.get("KVM_HOSTNAME") != host:
                    raise ValueError("NetBackup configuration Host differs from Mold: " + path.name)
                name = values.get("NETBACKUP_POLICY_NAME") or path.name[len("netbackup-host-"):-len(".conf")]
                if safe_name(name) != path.name[len("netbackup-host-"):-len(".conf")]:
                    raise ValueError("NetBackup policy identity differs from its configuration filename")
                target = str(Path(root) / "ablestack-netbackup" / "bootstrap" / safe_name(name))
                bindings.append(dict(provider="ablestack-netbackup", name=name, nameIsExact=bool(values.get("NETBACKUP_POLICY_NAME")), previousPath=values.get("NETBACKUP_PARENT_PAYLOAD_PATH", ""), path=target, managed=True))
                replacements = {"NETBACKUP_PARENT_PAYLOAD_PATH": target}
                if "BACKUP_STAGING_ROOT" in values:
                    replacements["BACKUP_STAGING_ROOT"] = str(Path(root) / "ablestack-netbackup")
                register(path, data, replacements)
            for filename in ("restore.conf", "restore-watcher.conf"):
                path = self.netbackup / filename
                if not path.exists() and not path.is_symlink():
                    continue
                data, values = read_config(path, {"NETBACKUP_STAGING_ROOT"})
                if "NETBACKUP_STAGING_ROOT" in values:
                    register(path, data, {"NETBACKUP_STAGING_ROOT": str(Path(root) / "ablestack-netbackup")})
        if "ablestack-veeam" in providers:
            for path in sorted(self.veeam.glob("*.conf")):
                keys = {"VEEAM_JOB_NAME", "KVM_HOSTNAME", "VEEAM_AGENT_PAYLOAD_PATH", "VEEAM_AGENT_PAYLOAD_MODE", "VEEAM_HOST_BACKUP_PATH", "STAGING_PATH"}
                data, values = read_config(path, keys)
                if not values.get("VEEAM_JOB_NAME"):
                    if "VEEAM_AGENT_PAYLOAD_PATH" in values:
                        raise ValueError("Veeam parent path has no Job identity: " + path.name)
                    continue
                if values.get("KVM_HOSTNAME") != host:
                    raise ValueError("Veeam configuration Host differs from Mold: " + path.name)
                name = values["VEEAM_JOB_NAME"]
                if safe_name(name) + ".conf" != path.name:
                    raise ValueError("Veeam Job identity differs from its configuration filename")
                mode = values.get("VEEAM_AGENT_PAYLOAD_MODE", "COMMON")
                if mode not in {"COMMON", "EXPLICIT"}:
                    raise ValueError("Unsupported Veeam parent path mode: " + path.name)
                target = str(Path(root) / "ablestack-veeam" / "bootstrap" / safe_name(host) / safe_name(name))
                old = values.get("VEEAM_AGENT_PAYLOAD_PATH", "")
                replacements = {"VEEAM_AGENT_PAYLOAD_PATH": target} if mode == "COMMON" else {}
                bindings.append(dict(provider="ablestack-veeam", name=name, previousPath=old,
                                     path=target if mode == "COMMON" else old, managed=mode == "COMMON"))
                for key in ("VEEAM_HOST_BACKUP_PATH", "STAGING_PATH"):
                    if key in values:
                        replacements[key] = str(Path(root) / "ablestack-veeam")
                register(path, data, replacements)
        fingerprint = hashlib.sha256(json.dumps(dict(configuration=configuration, host=host, providers=sorted(providers)), sort_keys=True).encode())
        for path, before, after in files:
            fingerprint.update(str(path).encode() + b"\x00" + before + b"\x00")
        return dict(version=1, hostName=host, rootPath=root, fingerprint=fingerprint.hexdigest(),
                    changed=any(before != after for path, before, after in files), bindings=bindings), files

    def run(self, request):
        action = request["action"]
        if action not in {"PLAN", "APPLY", "VERIFY"}:
            raise ValueError("Unsupported staging synchronization action")
        plan, files = self.discover(request)
        if action == "PLAN":
            return plan
        if action == "VERIFY":
            if plan["changed"]:
                raise ValueError("Host staging paths still differ from Mold")
            return plan
        if request.get("fingerprint") != plan["fingerprint"]:
            raise ValueError("Host configuration changed during synchronization; retry without overwriting operator changes")
        changed_providers = {binding["provider"] for binding in plan["bindings"] if binding["managed"] and binding["previousPath"] != binding["path"]}
        if plan["changed"] and "ablestack-netbackup" in request["providers"]:
            changed_providers.add("ablestack-netbackup")
        for provider in changed_providers:
            require_watcher_idle(provider)
        for binding in plan["bindings"]:
            if binding["managed"] and binding["previousPath"] != binding["path"]:
                prepare_payload(request["configuration"], binding["path"])
        # Recheck every original immediately before writing. A retry discovers partially applied files anew.
        for path, before, after in files:
            current, unused = read_config(path, set())
            if current != before:
                raise ValueError("Host configuration changed before path update: " + path.name)
        for path, before, after in files:
            if before == after:
                continue
            current, unused = read_config(path, set())
            if current != before:
                raise ValueError("Host configuration changed during path update: " + path.name)
            mode = stat.S_IMODE(path.stat().st_mode)
            backup = path.with_name(path.name + ".staging-sync." + hashlib.sha256(before).hexdigest()[:12] + ".bak")
            if not backup.exists():
                with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode), "wb") as stream:
                    stream.write(before)
                    stream.flush()
                    os.fsync(stream.fileno())
            fd, temporary = tempfile.mkstemp(prefix=".staging-sync-", dir=path.parent)
            try:
                with os.fdopen(fd, "wb") as stream:
                    os.fchmod(stream.fileno(), mode)
                    stream.write(after)
                    stream.flush()
                    os.fsync(stream.fileno())
                current, unused = read_config(path, set())
                if current != before:
                    raise ValueError("Host configuration changed before atomic path update: " + path.name)
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        verified, unused = self.discover(request)
        if verified["changed"]:
            raise ValueError("Host path synchronization could not be verified")
        return verified


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    args = parser.parse_args()
    request = json.loads(args.request)
    # Serialize only this synchronizer; no backup command or arbitrary shell config is executed.
    with open("/var/lock/ablestack-staging-sync.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        print(json.dumps(Synchronizer().run(request)))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit("Staging path synchronization failed: " + str(error))
