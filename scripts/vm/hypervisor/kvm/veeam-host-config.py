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

"""Configure Veeam on each KVM host. Reuse matching resources; back up changed files."""

import argparse
import base64
import configparser
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen
import uuid

from ablestack_backup_secret import decrypt_secret, write_secret
from ablestack_backup_secret_key import SecretKeyError, ensure_key
from veeam_script_deploy import ScriptDeploymentError, deploy_hooks, deployment_directory, validate_deployment


PROVIDER = "ablestack-veeam"
SCRIPT_DIR = Path(__file__).resolve().parent
RUNTIME_DIR = SCRIPT_DIR / "veeam"
RUNTIME_FILES = (
    "mold-backup.lib.sh", "mold-backup.sh", "mold-backup-secret.sh", "mold-backup-secret.py",
    "install-ablestack-secret-key.sh", "diagnose-mold-veeam-offering.sh",
    "ablestack_veeam_pre_notify.sh", "ablestack_veeam_post_notify.sh",
    "ablestack_veeam_restore_notify.sh", "ablestack_veeam_restore_event.sh",
    "mold-veeam-restore-agent.sh",
)
COMMON_FILES = ("ablestack_backup_secret_key.py", "ablestack_backup_secret.py", "thirdparty_bootstrap.py",
                "veeam_script_deploy.py", "veeam-host-config.py")
UNIT_FILES = ("mold-veeam-restore-agent.service", "mold-veeam-restore-agent.timer")


class ConfigurationError(RuntimeError):
    pass


class AsyncJobFailed(ConfigurationError):
    pass


def info(message):
    print(message, file=sys.stderr)


def boolean(value):
    if value.lower() not in ("true", "false"):
        raise argparse.ArgumentTypeError("expected true or false")
    return value.lower() == "true"


def safe_name(value):
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-only", action="store_true", help="RPM: prepare directories and systemd units without configuring Mold or enabling the timer")
    parser.add_argument("--mold-url", help="Mold API URL; default: management host from agent.properties")
    parser.add_argument("--admin-apikey", "--api-key", dest="admin_apikey")
    parser.add_argument("--admin-secretkey", "--api-secret", dest="admin_secretkey")
    parser.add_argument("--job-name", default=socket.gethostname().split(".")[0], help="parent Job name; independent of the Mold host name")
    parser.add_argument("--veeam-host", help="VBR hostname or IP; SSH must be enabled on this server")
    parser.add_argument("--veeam-user", default="administrator")
    parser.add_argument("--veeam-password")
    parser.add_argument("--update-veeam-credentials", action="store_true", help="explicitly replace an existing encrypted Mold Veeam password")
    parser.add_argument("--veeam-ssh-user", help="default: --veeam-user")
    parser.add_argument("--veeam-ssh-key", default="", help="optional SSH private key for the restore watcher and hook deployment")
    parser.add_argument("--veeam-script-dir", help="create this directory on VBR and copy the generated Pre/Post wrappers over SSH; omit for manual copy")
    parser.add_argument("--kvm-hostname", default=socket.gethostname().split(".")[0], help="optional Mold host-name override")
    parser.add_argument("--kvm-ip", help="optional Mold host-IP override")
    parser.add_argument("--zone-id", help="optional verification of the discovered host Zone")
    parser.add_argument("--offering-name", default="VeeamBackup", help="name used only when a matching offering does not exist")
    parser.add_argument("--vm-schedule-backup", type=boolean, default=None)
    parser.add_argument("--vm-include", default=None)
    parser.add_argument("--vm-exclude", default=None)
    parser.add_argument("--incremental-backup", type=boolean, default=True)
    parser.add_argument("--backup-chain-size", type=int, help="optional positive kvm.backup.chain.size; Mold default is 8")
    parser.add_argument("--agent-payload-path", help="optional parent marker directory; default: common staging/ablestack-veeam/bootstrap/<host>/<job>")
    parser.add_argument("--script-timeout", type=int, default=1800)
    parser.add_argument("--secret-key-file", type=Path, default=Path(os.environ.get("ABLESTACK_SECRET_KEY_FILE", "/root/.ssh/ablestack.key")))
    args = parser.parse_args(argv)
    if args.backup_chain_size is not None and (args.backup_chain_size < 1 or not args.incremental_backup):
        parser.error("--backup-chain-size must be positive and requires --incremental-backup true")
    if args.vm_schedule_backup and (args.vm_include is not None or args.vm_exclude is not None):
        parser.error("--vm-include/--vm-exclude require --vm-schedule-backup false")
    if any(c in args.job_name for c in "\r\n\0"):
        parser.error("--job-name cannot contain control characters")
    if not args.job_name.strip() or safe_name(args.job_name) in (".", "..", "mold-backup"):
        parser.error("--job-name must identify a Job and cannot be a reserved configuration name")
    if args.script_timeout < 1:
        parser.error("--script-timeout must be positive")
    if not args.secret_key_file.is_absolute():
        parser.error("--secret-key-file must be absolute")
    if args.veeam_script_dir:
        if args.install_only:
            parser.error("--veeam-script-dir cannot be used with --install-only")
        try:
            deployment_directory(args.veeam_script_dir)
        except ScriptDeploymentError as exc:
            parser.error(str(exc))
    if args.agent_payload_path:
        payload = Path(args.agent_payload_path)
        if not payload.is_absolute() or payload == Path("/") or any(c in args.agent_payload_path for c in "\r\n"):
            parser.error("--agent-payload-path must be an absolute directory other than /")
    if not args.install_only:
        for name in ("admin_apikey", "admin_secretkey", "veeam_host", "veeam_password"):
            if not getattr(args, name):
                parser.error("--" + name.replace("_", "-") + " is required")
        if any(c in args.veeam_host for c in "/@?#\r\n"):
            parser.error("--veeam-host must be a hostname or IP, without a URL or user name")
        args.veeam_ssh_user = args.veeam_ssh_user or args.veeam_user
    return args


def select_vms(args, interactive=None):
    interactive = sys.stdin.isatty() if interactive is None else interactive
    if args.vm_schedule_backup is None:
        try:
            args.vm_schedule_backup = boolean(input("Use Mold per-VM backup schedules? [true/false, default: false]: ").strip() or "false") if interactive else False
        except argparse.ArgumentTypeError:
            raise ConfigurationError("VM schedule selection must be true or false") from None
    if args.vm_schedule_backup:
        if args.vm_include is not None or args.vm_exclude is not None:
            raise ConfigurationError("--vm-include/--vm-exclude require --vm-schedule-backup false")
        args.vm_include = args.vm_exclude = "*"
    else:
        if args.vm_include is None:
            args.vm_include = (input("VM_INCLUDE [default: *]: ").strip() or "*") if interactive else "*"
        if args.vm_exclude is None:
            args.vm_exclude = input("VM_EXCLUDE [default: none]: ").strip() if interactive else ""


def normalize_mold_url(value):
    if "://" not in value:
        value = "http://" + value + ":8080"
    parts = urlsplit(value)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.query or parts.fragment:
        raise ConfigurationError("--mold-url must be an HTTP(S) Mold API address")
    return urlunsplit((parts.scheme, parts.netloc, "/client/api", "", ""))


def discover_mold_url(properties=Path("/etc/cloudstack/agent/agent.properties")):
    try:
        lines = properties.read_text().splitlines()
    except OSError:
        raise ConfigurationError("Cannot read agent.properties; provide --mold-url") from None
    for line in lines:
        if re.match(r"^\s*host\s*=", line):
            return normalize_mold_url(line.split("=", 1)[1].split(",", 1)[0].strip())
    raise ConfigurationError("Management host missing from agent.properties; provide --mold-url")


class MoldApi:
    def __init__(self, args):
        self.url = normalize_mold_url(args.mold_url) if args.mold_url else discover_mold_url()
        self.key = args.admin_apikey
        self.secret = args.admin_secretkey

    def call(self, command, **params):
        data = {"command": command, "response": "json", "apikey": self.key, **params}
        encoded = urlencode(data).replace("+", "%20")
        canonical = "&".join(sorted(part.lower() for part in encoded.split("&")))
        data["signature"] = base64.b64encode(hmac.new(self.secret.encode(), canonical.encode(), hashlib.sha256).digest()).decode()
        request = Request(self.url, data=urlencode(data).encode(), headers={"Accept": "application/json"})
        try:
            with urlopen(request, timeout=300) as response:
                result = json.load(response)
        except HTTPError as exc:
            detail = ""
            if command in ("listHosts", "listBackupOfferings", "listConfigurations"):
                try:
                    error = json.load(exc).get("errorresponse", {})
                    if isinstance(error, dict) and isinstance(error.get("errortext"), str):
                        detail = error["errortext"]
                except (OSError, ValueError, AttributeError):
                    pass
                for secret in (self.key, self.secret):
                    if secret:
                        detail = detail.replace(secret, "[redacted]")
                detail = detail.replace("\r", " ").replace("\n", " ")[:500]
            suffix = f": {detail}" if detail else ""
            raise ConfigurationError(f"Mold API {command} returned HTTP {exc.code}{suffix}") from None
        except (URLError, OSError, ValueError):
            raise ConfigurationError(f"Mold API {command} failed or returned invalid JSON") from None
        body = result.get(command.lower() + "response") if isinstance(result, dict) else None
        if not isinstance(body, dict) or body.get("errorcode") or "errorresponse" in result:
            raise ConfigurationError(f"Mold API {command} returned an error response")
        return body

    def rows(self, command, entity, **params):
        page = 1
        received = 0
        seen_pages = set()
        while True:
            # BaseListCmd requires page and pagesize together. One fits any positive
            # default.page.size limit; continue through every page to keep the full inventory.
            body = self.call(command, listall="true", page=page, pagesize=1, **params)
            rows = body.get(entity, [])
            if isinstance(rows, dict):
                rows = [rows]
            if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
                raise ConfigurationError(f"Mold API {command} returned invalid {entity}")
            if not rows:
                return
            fingerprint = json.dumps(rows, sort_keys=True)
            if fingerprint in seen_pages:
                raise ConfigurationError(f"Mold API {command} repeated a page; cannot confirm the complete {entity} list")
            seen_pages.add(fingerprint)
            yield from rows
            received += len(rows)
            count = body.get("count")
            if isinstance(count, int) and not isinstance(count, bool) and count >= 0 and received >= count:
                return
            page += 1

    def configuration(self, name, **scope):
        for row in self.rows("listConfigurations", "configuration", name=name, **scope):
            if row.get("name") == name:
                return "" if row.get("value") is None else str(row["value"])
        raise ConfigurationError(f"Mold setting {name} is unavailable; apply the matching management-server version first")

    def ensure_configuration(self, name, value, **scope):
        if self.configuration(name, **scope) == value:
            info(f"SKIP setting {name} ({scope or 'global'})")
            return False
        self.call("updateConfiguration", name=name, value=value, **scope)
        info(f"UPDATED setting {name} ({scope or 'global'})")
        return True

    def wait(self, job_id):
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            result = self.call("queryAsyncJobResult", jobid=job_id)
            status = result.get("jobstatus")
            if status == 1:
                return
            if status != 0:
                raise AsyncJobFailed("Mold offering import failed; inspect the async job in Mold")
            time.sleep(5)
        raise ConfigurationError("Mold offering import timed out; rerun to check whether the offering was created")


def host_context(api, args):
    # Never fall back to the first host in a search result.
    matches = [host for host in api.rows("listHosts", "host", type="Routing")
               if (args.kvm_ip and host.get("ipaddress") == args.kvm_ip)
               or (not args.kvm_ip and str(host.get("name", "")).lower() == args.kvm_hostname.lower())]
    if len(matches) != 1 or not matches[0].get("zoneid") or not matches[0].get("clusterid"):
        raise ConfigurationError("Cannot uniquely match this KVM host in Mold; provide --kvm-hostname or --kvm-ip")
    host = matches[0]
    if args.zone_id and args.zone_id != host["zoneid"]:
        raise ConfigurationError("--zone-id differs from the matched KVM host Zone")
    return host


def staging_root(api):
    if api.configuration("backup.thirdparty.staging.enable").lower() != "true":
        raise ConfigurationError("Configure and enable common third-party staging in Mold first")
    root = Path(api.configuration("backup.thirdparty.staging.root.path"))
    mount = Path(api.configuration("backup.thirdparty.staging.mount.path"))
    if not root.is_absolute() or not mount.is_absolute() or mount == Path("/"):
        raise ConfigurationError("Common staging root and mount must be absolute paths on a separate filesystem")
    root, mount = root.resolve(), mount.resolve()
    if root != mount and mount not in root.parents:
        raise ConfigurationError("Common staging root must be inside the configured mount")
    if not os.path.ismount(mount):
        raise ConfigurationError("Common staging filesystem is not mounted on this host")
    return root / PROVIDER


def configure_mold(api, host, args):
    restart = False
    for scope in ({}, {"zoneid": host["zoneid"]}):
        restart |= api.ensure_configuration("backup.framework.enabled", "true", **scope)
        current = api.configuration("backup.framework.provider.plugin", **scope)
        plugins = [item.strip() for item in current.split(",") if item.strip()]
        if PROVIDER not in [item.lower() for item in plugins]:
            api.ensure_configuration("backup.framework.provider.plugin", ",".join(plugins + [PROVIDER]), **scope)
    for scope in ({}, {"clusterid": host["clusterid"]}):
        restart |= api.ensure_configuration("kvm.incremental.backup", str(args.incremental_backup).lower(), **scope)
    if args.backup_chain_size is not None:
        api.ensure_configuration("kvm.backup.chain.size", str(args.backup_chain_size))
    credentials_changed = False
    for name, value in (("url", f"https://{args.veeam_host}:9398/api/"),
                        ("username", args.veeam_user), ("password", args.veeam_password)):
        setting = "backup.plugin.ablestack-veeam." + name
        if name == "password":
            # Secure configuration values are returned encrypted by Mold. They cannot be
            # compared with plaintext. Preserve an existing password unless explicitly rotated
            # or the server/user changed in this run.
            current = api.configuration(setting, zoneid=host["zoneid"])
            if current and not credentials_changed and not args.update_veeam_credentials:
                info("SKIP existing encrypted Veeam password")
                continue
            api.call("updateConfiguration", name=setting, value=value, zoneid=host["zoneid"])
            info("UPDATED Veeam password")
        else:
            credentials_changed |= api.ensure_configuration(setting, value, zoneid=host["zoneid"])
    return restart


def ensure_offering(api, host, args, pending=None, files=None):
    def find():
        return next((row for row in api.rows("listBackupOfferings", "backupoffering")
                     if str(row.get("provider", "")).lower() == PROVIDER and row.get("zoneid") == host["zoneid"]
                     and row.get("externalid") == "veeam"), None)
    existing = find()
    if existing:
        info("SKIP existing Veeam backup offering")
        if pending is not None:
            pending.unlink(missing_ok=True)
        return existing
    if pending is not None and pending.exists():
        result = json.loads(pending.read_text())
        if result.get("zoneid") != host["zoneid"] or not result.get("jobid"):
            raise ConfigurationError("Pending offering import belongs to another Zone or is invalid")
    else:
        result = api.call("importBackupOffering", name=args.offering_name,
                          description="ABLESTACK Veeam backup offering", provider=PROVIDER,
                          externalid="veeam", allowuserdrivenbackups="false", zoneid=host["zoneid"])
        if not result.get("jobid"):
            raise ConfigurationError("Offering import did not return an async job ID")
        if pending is not None:
            files.write(pending, json.dumps({"zoneid": host["zoneid"], "jobid": result["jobid"]}) + "\n")
    try:
        api.wait(result["jobid"])
    except AsyncJobFailed:
        # A confirmed failure can be retried. Keep the ID on timeout/connection errors.
        if pending is not None:
            pending.unlink(missing_ok=True)
        raise
    created = find()
    if not created:
        raise ConfigurationError("Offering import completed but no matching Veeam offering was found")
    if pending is not None:
        pending.unlink(missing_ok=True)
    return created


class FileInstaller:
    """A changed file is copied aside before atomic replacement; reruns skip identical bytes."""
    def __init__(self):
        self.changed = set()

    def backup(self, path):
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise ConfigurationError(f"Refusing to replace a non-regular file: {path}")
        if path.exists():
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            backup = path.with_name(path.name + f".bak.{stamp}.{uuid.uuid4().hex[:8]}")
            shutil.copy2(path, backup)
            info(f"BACKUP {backup}")

    def write(self, path, content, mode=0o600):
        content = content.encode() if isinstance(content, str) else content
        if path.is_symlink():
            raise ConfigurationError(f"Refusing to replace symlink: {path}")
        if path.is_file() and path.read_bytes() == content and stat.S_IMODE(path.stat().st_mode) == mode:
            return False
        path.parent.mkdir(parents=True, exist_ok=True)
        self.backup(path)
        fd, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as stream:
                os.fchmod(stream.fileno(), mode)
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
            directory_fd = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        self.changed.add(path)
        return True

    def secret(self, path, plaintext, key):
        if path.is_symlink():
            raise ConfigurationError(f"Refusing to replace symlink: {path}")
        if path.exists() and decrypt_secret(path, key_file=key, legacy_provider="veeam") == plaintext.encode():
            if stat.S_IMODE(path.stat().st_mode) != 0o600:
                path.chmod(0o600)
            return
        self.backup(path)
        write_secret(path, plaintext.encode(), key_file=key)
        self.changed.add(path)


def paths():
    return {
        "etc": Path(os.environ.get("ABLESTACK_VEEAM_ETC_DIR", "/etc/ablestack/veeam")),
        "state": Path(os.environ.get("ABLESTACK_VEEAM_STATE_DIR", "/var/lib/ablestack/veeam")),
        "units": Path(os.environ.get("SYSTEMD_UNIT_DIR", "/etc/systemd/system")),
        "ini": Path(os.environ.get("VEEAM_INI_PATH", "/etc/veeam/veeam.ini")),
        "logs": Path(os.environ.get("MOLD_LOG_DIR", "/var/log/mold")),
    }


@contextmanager
def configuration_lock(etc):
    etc.mkdir(parents=True, exist_ok=True)
    with (etc / ".configure.lock").open("a") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ConfigurationError("Another Veeam host configuration is running") from None
        yield


def systemd_quote(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%") + '"'


def install_runtime(files, locations, args):
    """Use package-owned executables in place; only units and mutable directories are installed."""
    etc = locations["etc"]
    for name in (*RUNTIME_FILES, *UNIT_FILES):
        source = RUNTIME_DIR / name
        if not source.is_file():
            raise ConfigurationError(f"Runtime file missing: {source}")
    for name in COMMON_FILES:
        if not (SCRIPT_DIR / name).is_file():
            raise ConfigurationError(f"Common helper missing: {name}")
    ensure_key(args.secret_key_file, secret_directories=(etc / "secrets",))
    for name in UNIT_FILES:
        unit = (RUNTIME_DIR / name).read_text()
        if name.endswith(".service"):
            unit = unit.replace("Environment=ABLESTACK_VEEAM_ETC_DIR=/etc/ablestack/veeam",
                                "Environment=" + systemd_quote("ABLESTACK_VEEAM_ETC_DIR=" + str(etc)))
            unit = unit.replace("Environment=ABLESTACK_VEEAM_STATE_DIR=/var/lib/ablestack/veeam",
                                "Environment=" + systemd_quote("ABLESTACK_VEEAM_STATE_DIR=" + str(locations["state"])))
            unit = unit.replace("EnvironmentFile=-/etc/ablestack/veeam/mold-backup.env",
                                "EnvironmentFile=-" + systemd_quote(etc / "mold-backup.env"))
            unit = unit.replace("ExecStart=/usr/bin/bash /usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/veeam/mold-veeam-restore-agent.sh",
                                "ExecStart=/usr/bin/bash " + systemd_quote(RUNTIME_DIR / "mold-veeam-restore-agent.sh"))
        files.write(locations["units"] / name, unit, 0o644)
    for directory in ("hooks", "secrets"):
        (etc / directory).mkdir(mode=0o700, parents=True, exist_ok=True)
    (etc / "secrets").chmod(0o700)
    locations["state"].mkdir(mode=0o700, parents=True, exist_ok=True)
    for directory in ("state", "events", "registry"):
        (locations["state"] / directory).mkdir(mode=0o700, exist_ok=True)


def render_config(values):
    return "# Generated by veeam-host-config.py; runtime backup mode follows Mold.\n" + "".join(
        name + "=" + shlex.quote(str(value)) + "\n" for name, value in values.items())


def write_job(files, locations, args, host, stage, offering):
    etc = locations["etc"]
    safe_job = safe_name(args.job_name)
    payload = args.agent_payload_path or str(Path(stage) / "bootstrap" / safe_name(host["name"]) / safe_job)
    secret = etc / "secrets" / (safe_job + ".enc")
    files.secret(secret, args.admin_secretkey, args.secret_key_file)
    values = {
        "MOLD_API_URL": args.mold_url, "MOLD_API_KEY": args.admin_apikey,
        "MOLD_API_SECRET": "", "MOLD_API_SECRET_ENC_FILE": secret, "MOLD_SECRET_KEY_FILE": args.secret_key_file,
        "VEEAM_JOB_NAME": args.job_name, "ZONE_ID": host["zoneid"], "KVM_HOSTNAME": host["name"],
        "ABLESTACK_VEEAM_STATE_DIR": locations["state"],
        "BACKUP_OFFERING_NAME": offering["name"], "VEEAM_PROVIDER_NAME": PROVIDER,
        "VM_SCHEDULE_BACKUP": str(args.vm_schedule_backup).lower(), "VM_INCLUDE": args.vm_include, "VM_EXCLUDE": args.vm_exclude,
        "VM_AUTO_EXCLUDE": "true", "BACKUP_MODE": "host", "VEEAM_BACKUP_INTERVAL_TYPE": "EXTERNAL",
        "VEEAM_URL": f"https://{args.veeam_host}:9398/api/", "VEEAM_USERNAME": args.veeam_user, "VEEAM_PASSWORD": args.veeam_password,
        "VEEAM_SSH_HOST": args.veeam_host, "VEEAM_SSH_USER": args.veeam_ssh_user, "VEEAM_SSH_KEY": args.veeam_ssh_key,
        "VEEAM_HOST_BACKUP_PATH": stage, "STAGING_PATH": stage, "VEEAM_AGENT_PAYLOAD_PATH": payload,
        "VEEAM_AGENT_PAYLOAD_MODE": "EXPLICIT" if args.agent_payload_path else "COMMON",
        "RESTORE_WATCH_TRIGGER_MOLD": "true", "RESTORE_WATCH_SKIP_EXISTING_ON_START": "true",
        "VEEAM_RESTORE_WATCH_WINDOW_MIN": "10", "RESTORE_AUTO_STOP": "true",
        "LOG_FILE": locations["logs"] / "veeam-hook.log", "LOG_TAG": "mold-veeam-hook",
    }
    files.write(etc / (safe_job + ".conf"), render_config(values))
    # The watcher reads per-Job confs; no forced single Job or duplicated credentials in env.
    files.write(etc / "mold-backup.env", "# Per-Job configuration: /etc/ablestack/veeam/<JOB>.conf\n")
    for hook in ("pre", "post"):
        target = etc / "hooks" / (hook + "-notify." + safe_job)
        script = ("#!/usr/bin/bash\nset -euo pipefail\nexport "
                  + "ABLESTACK_VEEAM_ETC_DIR=" + shlex.quote(str(etc)) + "\nexport "
                  + "ABLESTACK_VEEAM_STATE_DIR=" + shlex.quote(str(locations["state"]))
                  + "\nexec bash " + shlex.quote(str(RUNTIME_DIR / ("ablestack_veeam_" + hook + "_notify.sh")))
                  + ' "$(hostname -s)" ' + shlex.quote(args.job_name) + ' default ""\n')
        files.write(target, script, 0o755)
        # VBR uploads this file to the Linux Agent; execution occurs locally on KVM.
        files.write(etc / (safe_job + "-" + hook + "-notify.sh"), "#!/usr/bin/bash\nexec bash " + shlex.quote(str(target)) + '\n', 0o755)
    Path(payload).mkdir(parents=True, exist_ok=True)
    locations["logs"].mkdir(parents=True, exist_ok=True)
    return payload


def set_agent_timeout(files, ini, timeout, pending=None):
    config = configparser.ConfigParser(interpolation=None, strict=False)
    config.optionxform = str
    config.read(ini)
    if config.has_section("scripts") and config.get("scripts", "timeoutPrePost", fallback="") == str(timeout):
        return False
    # Preserve comments and unrelated Agent settings verbatim.
    original = ini.read_text()
    section = re.search(r"(?m)^\[scripts\][^\n]*\n(?:(?!\[).*(?:\n|$))*", original)
    if section:
        block = section.group()
        if re.search(r"(?m)^\s*timeoutPrePost\s*=", block):
            block = re.sub(r"(?m)^\s*timeoutPrePost\s*=.*$", "timeoutPrePost = " + str(timeout), block)
        else:
            block += "\ntimeoutPrePost = " + str(timeout) + "\n"
        updated = original[:section.start()] + block + original[section.end():]
    else:
        updated = original.rstrip() + "\n\n[scripts]\ntimeoutPrePost = " + str(timeout) + "\n"
    if pending is not None:
        files.write(pending, "Restart Veeam Agent after timeout configuration changes.\n")
    return files.write(ini, updated, stat.S_IMODE(ini.stat().st_mode))


def systemctl(*arguments, check=True):
    result = subprocess.run(["systemctl", *arguments], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and result.returncode:
        raise ConfigurationError("systemctl " + " ".join(arguments) + " failed; inspect the service with journalctl")
    return result.returncode == 0


def activate(files, locations, agent_changed):
    # Reload again on retry even if a previous run installed the units before failing.
    systemctl("daemon-reload")
    pending = locations["etc"] / ".agent-restart-required"
    if agent_changed or pending.exists():
        systemctl("restart", "veeamservice.service")
        pending.unlink(missing_ok=True)
    timer = "mold-veeam-restore-agent.timer"
    if not systemctl("is-enabled", timer, check=False):
        systemctl("enable", timer)
    if not systemctl("is-active", timer, check=False):
        systemctl("start", timer)


def require_root():
    if os.geteuid() != 0:
        raise ConfigurationError("Run this script as root on the KVM host")


def require_cryptography():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: F401
    except ImportError:
        raise ConfigurationError("Install python3-cryptography before configuring the Host") from None


def main(argv=None):
    args = parse_args(argv)
    locations = paths()
    files = FileInstaller()
    phase = "preflight"
    try:
        require_root()
        if not args.install_only:
            select_vms(args)
            require_cryptography()
            if not locations["ini"].is_file():
                raise ConfigurationError("Install Veeam Linux Agent first: /etc/veeam/veeam.ini is missing")
            if not shutil.which("systemctl"):
                raise ConfigurationError("systemctl is required on the KVM host")
            validate_deployment(args)
        with configuration_lock(locations["etc"]):
            if args.install_only:
                phase = "install runtime"
                install_runtime(files, locations, args)
                if shutil.which("systemctl"):
                    systemctl("daemon-reload")
                info(f"Runtime verified at {RUNTIME_DIR}; directories and systemd units prepared. Configure each host with {SCRIPT_DIR / 'veeam-host-config.py'}; timer activation requires completed configuration.")
                return 0
            api = MoldApi(args)
            args.mold_url = api.url
            host = host_context(api, args)
            stage = staging_root(api)
            # Reject collisions before changing remote settings or local credentials.
            conf = locations["etc"] / (safe_name(args.job_name) + ".conf")
            if conf.exists():
                existing = re.search(r"(?m)^VEEAM_JOB_NAME=(.*)$", conf.read_text())
                if not existing or shlex.split(existing[1]) != [args.job_name]:
                    raise ConfigurationError("Existing Job configuration uses the same filename for another Job")
            phase = "configure Mold settings"
            if configure_mold(api, host, args):
                raise ConfigurationError("Mold non-dynamic settings changed. Restart the management server, then rerun this command; applied settings are retained")
            phase = "ensure backup offering"
            offering = ensure_offering(api, host, args, locations["etc"] / ".offering-import.json", files)
            phase = "install runtime"
            install_runtime(files, locations, args)
            phase = "write Job configuration and Hooks"
            payload = write_job(files, locations, args, host, stage, offering)
            phase = "configure Veeam Linux Agent"
            agent_changed = set_agent_timeout(files, locations["ini"], args.script_timeout, locations["etc"] / ".agent-restart-required")
            deployed = []
            if args.veeam_script_dir:
                phase = "deploy Veeam Pre/Post scripts"
                deployed = deploy_hooks(args, locations["etc"], host)
            phase = "activate restore watcher"
            activate(files, locations, agent_changed)
            info("DONE: Veeam host configuration completed. Matching settings and files were reused.")
            if deployed:
                for hook, result in zip(("Pre-job", "Post-job"), deployed):
                    action = "copied and verified" if result["changed"] else "unchanged and verified"
                    info(f"Veeam UI {hook}: {result['path']} ({action}). Select this file under Guest Processing > Scripts.")
                info("Keep this directory and use the same --veeam-script-dir on reruns. Job script registration is a Veeam UI step.")
            else:
                info(f"Veeam UI: copy {locations['etc']}/{safe_name(args.job_name)}-pre-notify.sh and -post-notify.sh to VBR and select them under Guest Processing > Scripts; --veeam-script-dir enables automatic copy.")
            info(f"Parent Job SelectedFiles: {payload}. Configure the Job and its host schedule in Veeam UI.")
            return 0
    except (ConfigurationError, SecretKeyError, ScriptDeploymentError, OSError, ValueError, configparser.Error) as exc:
        info(f"FAILED at {phase}: {exc}")
        info("Completed settings/offerings are retained. Changed local files have .bak copies; correct the failure and rerun the same command.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
