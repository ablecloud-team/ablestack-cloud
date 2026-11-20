#!/usr/bin/env python3
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


"""Agent entry point for durable NetBackup host inspection, preflight and setup."""
import argparse
import contextlib
import fcntl
import io
import json
import os
from pathlib import Path
import shlex
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import ssl

import netbackup_host_setup as setup

ROOT = Path(os.environ.get("NETBACKUP_CONFIGURATION_DIR", "/var/lib/ablestack/netbackup/configuration"))
STATE = ROOT / "state.json"
LOCK = ROOT / "configuration.lock"


def atomic_state(value):
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".state-", dir=ROOT)
    try:
        with os.fdopen(descriptor, "w") as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, STATE)
        directory = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def read_state():
    if not STATE.exists():
        return {"state": "NOT_CONFIGURED"}
    value = json.loads(STATE.read_text())
    if not isinstance(value, dict):
        raise ValueError("Invalid host configuration receipt")
    return value


def config_values(path):
    values = {}
    if not path.is_file():
        return values
    for line in path.read_text().splitlines():
        key, separator, value = line.strip().partition("=")
        if separator and key in {"NETBACKUP_POLICY_NAME", "KVM_HOST_ID", "KVM_HOSTNAME", "NETBACKUP_PARENT_PAYLOAD_PATH", "MOLD_URL"}:
            words = shlex.split(value, comments=True)
            if len(words) == 1:
                values[key] = words[0]
    return values


def timer_active():
    try:
        result = subprocess.run(["systemctl", "is-active", setup.WATCHER_TIMER_PATH.name],
                                capture_output=True, text=True, timeout=5)
        return result.returncode == 0 and result.stdout.strip() == "active"
    except (OSError, subprocess.SubprocessError):
        return False


def status():
    value = read_state()
    from netbackup_hook_timeout import inventory
    updates = inventory(ROOT)
    value.update(hooktimeoutpending=bool(updates))
    configurations = [config_values(path) for path in sorted(setup.CONFIG_OUTPUT_DIR.glob("netbackup-host-*.conf"))]
    policies = [item for item in configurations if item.get("NETBACKUP_POLICY_NAME")]
    # One host may have multiple existing policies; never silently choose a different one.
    policy = value.get("policyname") or (policies[0]["NETBACKUP_POLICY_NAME"] if len(policies) == 1 else "")
    current = next((item for item in policies if item["NETBACKUP_POLICY_NAME"] == policy), {})
    value.update({"configuredpolicies": sorted({item["NETBACKUP_POLICY_NAME"] for item in policies}),
                  "policyinventorycomplete": len(policies) == len(configurations),
                  "policyname": policy, "moldurl": value.get("moldurl") or current.get("MOLD_URL", ""),
                  "configured": bool(current), "clientinstalled": setup.NETBACKUP_BP_CONF_PATH.is_file(),
                  "hooksready": bool(policy) and all((setup.HOOK_OUTPUT_DIR / (name + policy)).is_file()
                      for name in ("bpstart_notify.", "bpend_notify.")),
                  "timeractive": timer_active(),
                  "bootstrappath": value.get("bootstrappath") or current.get("NETBACKUP_PARENT_PAYLOAD_PATH", ""),
                  "configuredbootstrappath": current.get("NETBACKUP_PARENT_PAYLOAD_PATH", "")})
    if value.get("state") == "RUNNING":
        try:
            with LOCK.open("a") as lock:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                value.update(state="UNCONFIRMED", message="Configuration worker stopped without a completion receipt; inspect host services before retrying")
        except BlockingIOError:
            pass
    elif value.get("state") in {"COMPLETED", "CHECKED", "POLICY_PATH_MISMATCH"} and current and not (value["hooksready"] and value["timeractive"]):
        value.update(state="RECONFIGURATION_REQUIRED", message="Configured hooks or restore watcher timer are unavailable")
    return value


def read_request(path):
    path = Path(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError("Configuration request must be a private regular file")
    value = json.loads(path.read_text())
    if value.get("action") not in {"CHECK", "APPLY", "HOOK_TIMEOUT_CHECK", "HOOK_TIMEOUT_APPLY", "HOOK_TIMEOUT_RECOVER",
            "HOOK_TIMEOUT_ROLLBACK", "HOOK_TIMEOUT_COMMIT"} or not value.get("operationid"):
        raise ValueError("Invalid configuration action")
    return value


def start(request_path):
    request = read_request(request_path)
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = LOCK.open("a")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        previous = read_state()
        if previous.get("operationid") == request["operationid"]:
            Path(request_path).unlink(missing_ok=True)
            return previous
        if previous.get("state") == "RUNNING" and request["action"] != "CHECK":
            raise ValueError("Previous configuration outcome is unconfirmed; inspect the host before retrying")
        value = {"state": "RUNNING", "action": request["action"], "operationid": request["operationid"],
                 "policyname": request["policyname"], "moldurl": request["moldurl"], "inspectednetbackupurl": request["netbackupurl"], "step": "PREFLIGHT", "checkedat": int(time.time() * 1000), "check": []}
        atomic_state(value)
        try:
            subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--worker", str(request_path),
                              "--lock-fd", str(lock.fileno())], pass_fds=(lock.fileno(),),
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
        except Exception:
            atomic_state(dict(value, state="FAILED", message="Unable to start host configuration worker"))
            raise
        return value
    finally:
        # The worker inherits this open file description; closing the parent fd preserves its lock.
        lock.close()


def args_for(request):
    return argparse.Namespace(policy_name=request["policyname"], mold_url=request["moldurl"],
        admin_apikey=request["adminapikey"], admin_secretkey=request["adminsecretkey"],
        netbackup_url=request["netbackupurl"], netbackup_apikey=request["netbackupapikey"],
        kvm_hostname=None, kvm_ip=None, vm_schedule_backup=request["vmschedulebackup"],
        incremental_backup=request["incrementalbackup"], backup_chain_size=None,
        backup_staging_root=str(setup.BACKUP_STAGING_ROOT))


def no_external_jobs(args):
    # Client restart affects jobs of other policies too. Query all non-DONE jobs and inspect their Clients.
    params = urlencode({"filter": "state ne 'DONE'", "page[limit]": "100"})
    request = Request(args.netbackup_url.rstrip("/") + "/admin/jobs?" + params,
        headers={"Accept": "application/vnd.netbackup+json;version=12.0", "Authorization": args.netbackup_apikey})
    with urlopen(request, context=ssl._create_unverified_context(), timeout=30) as response:
        document = json.load(response)
    jobs = document.get("data")
    if not isinstance(jobs, list) or len(jobs) >= 100 or document.get("links", {}).get("next") or document.get("meta", {}).get("pagination", {}).get("next"):
        raise ValueError("Cannot confirm a complete active NetBackup job inventory")
    local = setup.local_host_addresses()
    names = {args.netbackup_client.lower(), args.kvm_hostname.lower(), args.kvm_ip.lower()}
    for item in jobs:
        attributes = item.get("attributes", {})
        client = str(attributes.get("clientName", "")).strip()
        state = str(attributes.get("state", "")).upper()
        if state in {"DONE", "COMPLETED", "FAILED", "CANCELLED", "ABORTED"}:
            continue
        if not client:
            raise ValueError("An active NetBackup job has no verifiable Client")
        if client.lower() in names or setup.resolve_addresses(client).intersection(local):
            raise ValueError("Finish NetBackup jobs on this Client before host configuration")


def preflight(request, value):
    args = args_for(request)
    def check(name, operation):
        value["step"] = name
        atomic_state(value)
        operation()
        value["check"].append({"name": name, "state": "OK"})
        atomic_state(value)
    check("CLIENT", lambda: require(setup.NETBACKUP_BP_CONF_PATH.is_file() and
        (setup.HOOK_OUTPUT_DIR / "bpbackup").is_file(), "Install the NetBackup Client on this Host first"))
    check("POLICY", lambda: setup.read_netbackup_policy(args))
    def identity():
        zone, cluster = setup.resolve_host_context(args)
        require(args.kvm_host_id == request["hostid"] and zone == request["zoneid"] and cluster == request["clusterid"],
                "Policy Client does not match the selected Mold Host, Zone and Cluster")
    check("HOST", identity)
    check("SCHEDULES", lambda: setup.check_netbackup_policy(args))
    check("STAGING", lambda: setup.load_common_staging(args, read_only=True))
    def settings():
        setup.read_hook_timeout(args)
        for key, expected, scope in ((setup.NETBACKUP_VM_SCHEDULE_CONFIG_NAME, str(request["vmschedulebackup"]).lower(), {}),
                ("kvm.incremental.backup", str(request["incrementalbackup"]).lower(), {"cluster_id": request["clusterid"]}),
                ("backup.plugin.netbackup.url", request["netbackupurl"], {"zone_id": request["zoneid"]}),
                ("backup.plugin.netbackup.apikey", request["netbackupapikey"], {"zone_id": request["zoneid"]})):
            current = setup.get_configuration_value(key, args.mold_url, args.admin_apikey, args.admin_secretkey, **scope)
            require(current == expected, "Shared backup settings changed; refresh this page and retry")
    check("SETTINGS", settings)
    if request["action"] == "APPLY":
        check("IDLE", lambda: no_external_jobs(args))
    value.update(clientname=args.netbackup_client, stagingpath=str(setup.BACKUP_STAGING_ROOT),
                 bootstrappath=str(setup.BACKUP_STAGING_ROOT / "bootstrap" / args.policy_name))
    return args


def compare_policy_path(args, value):
    if hasattr(args, "verified_policy"):
        del args.verified_policy
    policy = setup.read_netbackup_policy(args)
    selections = policy.get("backupSelections", {}).get("selections", [])
    if not isinstance(selections, list) or any(not isinstance(path, str) for path in selections):
        raise ValueError("NetBackup policy returned invalid Backup Selections")
    expected = value["bootstrappath"]
    value["policyselections"] = selections
    value["policypathmatched"] = selections == [expected]
    value["bootstrappresent"] = Path(expected).is_dir()
    value["check"].append({"name": "POLICY_PATH", "state": "OK" if value["policypathmatched"] else "MISMATCH"})


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run_worker(request_path, lock_fd):
    # Keep the inherited lock alive for every configuration stage, including subprocess calls.
    with os.fdopen(lock_fd, "a"):
        request = read_request(request_path)
        Path(request_path).unlink()  # Never retain API credentials in the durable status receipt.
        value = read_state()
        captured = io.StringIO()
        try:
            with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
                args = preflight(request, value)
                if request["action"] == "APPLY":
                    require(not setup.read_netbackup_policy(args).get("policyAttributes", {}).get("active", False),
                            "Deactivate the NetBackup Host policy before applying configuration; reactivate it after validation")
                    value["step"] = "OFFERING"
                    atomic_state(value)
                    setup.ensure_netbackup_offering(request["zoneid"], args)
                    value["step"] = "HOST_FILES"
                    atomic_state(value)
                    setup.prepare_secret_key()
                    def progress(step):
                        value["step"] = step
                        atomic_state(value)
                    setup.generate_host_outputs(request["zoneid"], args, progress=progress)
                    require(timer_active(), "Host files were written but the restore watcher timer is not active; retry configuration")
                    value["state"] = "COMPLETED"
                else:
                    value["state"] = "CHECKED"
                compare_policy_path(args, value)
                if request["action"] == "APPLY" and not value["policypathmatched"]:
                    value["state"] = "POLICY_PATH_MISMATCH"
                value.update(step="FINISHED", message="Assign the NetBackup offering to the VMs to protect" +
                    (" and register their Mold backup schedules" if request["vmschedulebackup"] else "; no Mold VM schedule is required"))
        except (Exception, SystemExit) as error:
            # Helpers may report failures through SystemExit. Keep actionable diagnostics, never credentials.
            message = str(error) if not isinstance(error, SystemExit) else captured.getvalue().split("[FAIL]")[-1].strip()
            for key in ("adminapikey", "adminsecretkey", "netbackupapikey"):
                secret = request.get(key)
                if secret:
                    message = message.replace(secret, "<hidden>")
            value["check"].append({"name": value.get("step", "PREFLIGHT"), "state": "FAILED"})
            value.update(state="FAILED", message=message[-1000:] or "Host configuration failed; inspect the Host services")
        value["checkedat"] = int(time.time() * 1000)
        atomic_state(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--status", action="store_true")
    group.add_argument("--request")
    group.add_argument("--worker")
    parser.add_argument("--lock-fd", type=int)
    args = parser.parse_args()
    if args.worker:
        run_worker(args.worker, args.lock_fd)
        return
    try:
        if args.status:
            result = status()
        else:
            request = read_request(args.request)
            if request['action'].startswith('HOOK_TIMEOUT_'):
                from netbackup_hook_timeout import execute
                try:
                    result = execute(request, sys.modules[__name__])
                finally:
                    Path(args.request).unlink(missing_ok=True)
            else:
                result = start(args.request)
    except Exception as error:
        result = {"state": "FAILED", "message": str(error)[:500]}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
