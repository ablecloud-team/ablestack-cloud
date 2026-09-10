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

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional
from urllib.parse import quote_plus
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError


HOOK_OUTPUT_DIR = Path(os.environ.get("HOOK_OUTPUT_DIR", "/usr/openv/netbackup/bin"))
CONFIG_OUTPUT_DIR = Path(os.environ.get("CONFIG_OUTPUT_DIR", "/etc/ablestack/netbackup"))
SECRET_OUTPUT_DIR = Path(os.environ.get("SECRET_OUTPUT_DIR", "/etc/ablestack/netbackup/secrets"))
BACKUP_STAGING_ROOT = Path(os.environ.get("BACKUP_STAGING_ROOT", "/tmp/mold/netbackup"))
NETBACKUP_STAGE_ROOT_CONFIG_NAME = "backup.plugin.netbackup.stage.root.path"
WATCHER_OUTPUT_PATH = Path(os.environ.get("WATCHER_OUTPUT_PATH", "/usr/local/sbin/netbackup-host-restore-watcher"))
RESTORE_NOTIFY_OUTPUT_PATH = Path(os.environ.get("RESTORE_NOTIFY_OUTPUT_PATH", "/usr/local/sbin/netbackup-host-restore-notify"))
WATCHER_SERVICE_PATH = Path(os.environ.get("WATCHER_SERVICE_PATH", "/etc/systemd/system/netbackup-host-restore-watcher.service"))
LOGROTATE_CONFIG_PATH = Path(os.environ.get("LOGROTATE_CONFIG_PATH", "/etc/logrotate.d/ablestack-netbackup"))
WATCHER_CONFIG_PATH = Path(os.environ.get("WATCHER_CONFIG_PATH", str(CONFIG_OUTPUT_DIR / "restore-watcher.conf")))
RESTORE_CONFIG_PATH = Path(os.environ.get("RESTORE_CONFIG_PATH", str(CONFIG_OUTPUT_DIR / "restore.conf")))
PRE_HELPER_PATH = os.environ.get("PRE_HELPER_PATH", "/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/netbackup-host-bpstart-notify.sh")
POST_HELPER_PATH = os.environ.get("POST_HELPER_PATH", "/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/netbackup-host-bpend-notify.sh")
HOOK_LOG_PATH = os.environ.get("HOOK_LOG_PATH", "/var/log/netbackup-mold-hook.log")
WATCHER_LOG_PATH = os.environ.get("WATCHER_LOG_PATH", "/var/log/netbackup-mold-restore-watcher.log")
RESTORE_LOG_PATH = os.environ.get("RESTORE_LOG_PATH", "/var/log/netbackup-mold-restore.log")
SECRET_KEY_FILE = Path(os.environ.get("SECRET_KEY_FILE", "/root/.ssh/ablestack.key"))
NETBACKUP_BP_CONF_PATH = Path(os.environ.get("NETBACKUP_BP_CONF_PATH", "/usr/openv/netbackup/bp.conf"))
NETBACKUP_SERVICE_NAME = os.environ.get("NETBACKUP_SERVICE_NAME", "netbackup")
MOLD_API_RESPONSE_FORMAT = "json"
NETBACKUP_PROVIDER_DISPLAY_NAME = "netbackup"
NETBACKUP_PROVIDER_CANONICAL_NAME = "ablestack-netbackup"
NETBACKUP_OFFERING_NAME = "NetBackup"
NETBACKUP_OFFERING_DESCRIPTION = "Ablestack NetBackup backup offering"
NETBACKUP_OFFERING_EXTERNAL_ID = "netbackup"
SCRIPT_DIR = Path(__file__).resolve().parent
POLICY_TEMPLATE_PATH = Path(os.environ.get("POLICY_TEMPLATE_PATH", str(SCRIPT_DIR / "netbackup-host-policy.conf")))


def log_step(message: str) -> None:
    print(f"\n== {message} ==", file=sys.stderr)


def log_info(message: str) -> None:
    print(f"[INFO] {message}", file=sys.stderr)


def log_ok(message: str) -> None:
    print(f"[ OK ] {message}", file=sys.stderr)


def log_api(method: str, command_name: str) -> None:
    print(f"  -> Mold API {method.upper()} {command_name}", file=sys.stderr)


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def backup_existing_file(target: Path) -> None:
    if not target.exists():
        return
    stamp = time.strftime("%Y%m%d%H%M%S")
    backup = target.with_name(f"{target.name}.bak.{stamp}")
    shutil.move(str(target), str(backup))
    print(f"Backed up existing file: {target} -> {backup}")


def copy_existing_file_backup(target: Path) -> None:
    if not target.exists():
        return
    stamp = time.strftime("%Y%m%d%H%M%S")
    backup = target.with_name(f"{target.name}.bak.{stamp}")
    shutil.copy2(str(target), str(backup))
    print(f"Backed up existing file: {target} -> {backup}")


def validate_secret_key_file() -> None:
    if not SECRET_KEY_FILE.is_file():
        fail(f"Secret key file not found: {SECRET_KEY_FILE}")
    mode = stat.S_IMODE(SECRET_KEY_FILE.stat().st_mode)
    if mode != 0o600:
        fail(f"Secret key file must have permission 600: {SECRET_KEY_FILE} (current: {oct(mode)[2:]})")


def validate_custom_secret_key_file(secret_key_file: Path) -> None:
    if not secret_key_file.is_file():
        fail(f"Secret key file not found: {secret_key_file}")
    mode = stat.S_IMODE(secret_key_file.stat().st_mode)
    if mode != 0o600:
        fail(f"Secret key file must have permission 600: {secret_key_file} (current: {oct(mode)[2:]})")


def sanitize_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "_", value)


def url_encode(value: str) -> str:
    return quote_plus(value)


def build_api_params(command_name: str, params: dict[str, str]) -> str:
    tokens = [f"command={url_encode(command_name)}"]
    for key, value in params.items():
        tokens.append(f"{key}={url_encode(str(value))}")
    tokens.append(f"response={url_encode(MOLD_API_RESPONSE_FORMAT)}")
    return "&".join(tokens)


def build_signed_url(base_url: str, api_params: str, api_key: str, secret_key: str) -> str:
    sorted_params = [f"apikey={quote_plus(api_key).lower()}"]
    for token in api_params.split("&"):
        key, value = token.split("=", 1)
        sorted_params.append(f"{key.lower()}={value.replace('+', '%20').lower()}")
    sorted_url = "&".join(sorted(sorted_params))
    signature = base64.b64encode(hmac.new(secret_key.encode(), sorted_url.encode(), hashlib.sha256).digest()).decode()
    encoded_signature = quote_plus(signature)
    return f"{base_url}?{api_params}&apiKey={quote_plus(api_key)}&signature={encoded_signature}"


def invoke_mold_api(method: str, command_name: str, params: dict[str, str], mold_url: str, api_key: str, secret_key: str) -> dict:
    log_api(method, command_name)
    api_params = build_api_params(command_name, params)
    signed_url = build_signed_url(mold_url, api_params, api_key, secret_key)
    request = Request(signed_url, method=method.upper(), headers={
        "Accept": "application/json",
        "Content-type": "application/x-www-form-urlencoded",
    })
    try:
        with urlopen(request, timeout=300) as response:
            raw = response.read().decode("utf-8")
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        fail(f"Mold API call failed: command={command_name} method={method} code={exc.code} body={body[:240]}")
    except URLError as exc:
        fail(f"Mold API call failed: command={command_name} method={method} error={exc}")

    if not raw.strip():
        fail(f"Mold API returned empty response: command={command_name} method={method}")
    try:
        return json.loads(raw)
    except Exception as exc:
        preview = raw.strip().replace("\n", "\\n")
        if len(preview) > 240:
            preview = preview[:240] + "..."
        fail(f"Failed to parse Mold API response as JSON: {exc}\nResponse preview: {preview}")
    return {}


def wait_for_async_job(job_id: str, mold_url: str, api_key: str, secret_key: str, *, description: str,
                       timeout_seconds: int = 300, poll_interval_seconds: int = 5) -> dict:
    if not job_id:
        fail(f"Missing async job ID for {description}")

    deadline = time.time() + timeout_seconds
    log_info(f"Waiting for async Mold job: {description} (jobid={job_id})")

    while time.time() < deadline:
        data = invoke_mold_api("GET", "queryAsyncJobResult", {
            "jobid": job_id,
        }, mold_url, api_key, secret_key)
        response = data.get("queryasyncjobresultresponse", {})
        job_status = int(response.get("jobstatus", 0) or 0)

        if job_status == 0:
            time.sleep(poll_interval_seconds)
            continue

        if job_status == 1:
            log_ok(f"Async Mold job completed: {description} (jobid={job_id})")
            return response

        job_result = response.get("jobresult", {})
        error_text = response.get("errortext") or response.get("jobresultcode")
        if isinstance(job_result, dict):
            error_text = job_result.get("errortext") or job_result.get("error") or error_text
        fail(f"Async Mold job failed: description={description} jobid={job_id} error={error_text}")

    fail(f"Timed out waiting for async Mold job: description={description} jobid={job_id}")
    return {}


def get_configuration_value(config_name: str, mold_url: str, api_key: str, secret_key: str,
                            zone_id: Optional[str] = None, cluster_id: Optional[str] = None) -> str:
    params = {"listAll": "true", "pagesize": "20", "page": "1", "name": config_name}
    if zone_id:
        params["zoneid"] = zone_id
    if cluster_id:
        params["clusterid"] = cluster_id
    data = invoke_mold_api("GET", "listConfigurations", params, mold_url, api_key, secret_key)
    configs = data.get("listconfigurationsresponse", {}).get("configuration", [])
    if isinstance(configs, dict):
        configs = [configs]
    for cfg in configs:
        if str(cfg.get("name", "")).lower() == config_name.lower():
            return str(cfg.get("value", ""))
    return ""


def update_configuration_value(config_name: str, config_value: str, mold_url: str, api_key: str, secret_key: str,
                               zone_id: Optional[str] = None, cluster_id: Optional[str] = None) -> None:
    params = {"name": config_name, "value": config_value}
    if zone_id:
        params["zoneid"] = zone_id
    if cluster_id:
        params["clusterid"] = cluster_id
    invoke_mold_api("POST", "updateConfiguration", params, mold_url, api_key, secret_key)


def append_provider_if_missing(provider_list: str, provider_name: str) -> str:
    items = [item.strip() for item in provider_list.split(",") if item.strip()]
    lowered = {item.lower() for item in items}
    if provider_name.lower() not in lowered:
        items.append(provider_name)
    return ",".join(items)


def resolve_host_context(policy_name: str, mold_url: str, api_key: str, secret_key: str) -> tuple[str, str]:
    log_step("Resolve Host Context")
    log_info(f"Resolving zone and cluster IDs using policy/host name={policy_name}")
    data = invoke_mold_api("GET", "listHosts", {
        "listAll": "true",
        "pagesize": "500",
        "page": "1",
        "type": "Routing",
        "keyword": policy_name,
    }, mold_url, api_key, secret_key)
    hosts = data.get("listhostsresponse", {}).get("host", [])
    if isinstance(hosts, dict):
        hosts = [hosts]
    exact = next((host for host in hosts if str(host.get("name", "")).lower() == policy_name.lower()), None)
    if exact is None and hosts:
        exact = hosts[0]
    if not exact or not exact.get("zoneid"):
        fail(f"Unable to resolve zone ID from host/policy name '{policy_name}' via listHosts.")
    zone_id = str(exact["zoneid"])
    cluster_id = str(exact.get("clusterid", "") or "")
    if not cluster_id:
        fail(f"Unable to resolve cluster ID from host/policy name '{policy_name}' via listHosts.")
    log_ok(f"Resolved zone ID: {zone_id}")
    log_ok(f"Resolved cluster ID: {cluster_id}")
    return zone_id, cluster_id


def ensure_backup_framework_configuration(zone_id: str, cluster_id: str, args: argparse.Namespace) -> bool:
    restart_required = False
    log_step("Configure Mold")

    log_info("Checking global configuration: backup.framework.enabled")
    current = get_configuration_value("backup.framework.enabled", args.mold_url, args.admin_apikey, args.admin_secretkey)
    if current.lower() != "true":
        log_info("Updating global configuration: backup.framework.enabled=true")
        update_configuration_value("backup.framework.enabled", "true", args.mold_url, args.admin_apikey, args.admin_secretkey)
        print("Updated global configuration: backup.framework.enabled=true")
        restart_required = True

    log_info("Checking zone configuration: backup.framework.enabled")
    current = get_configuration_value("backup.framework.enabled", args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
    if current.lower() != "true":
        log_info("Updating zone configuration: backup.framework.enabled=true")
        update_configuration_value("backup.framework.enabled", "true", args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
        print(f"Updated zone configuration: backup.framework.enabled=true (zoneid={zone_id})")
        restart_required = True

    log_info("Checking cluster configuration: kvm.incremental.backup")
    current = get_configuration_value("kvm.incremental.backup", args.mold_url, args.admin_apikey, args.admin_secretkey, cluster_id=cluster_id)
    if current.lower() != "true":
        log_info("Updating cluster configuration: kvm.incremental.backup=true")
        update_configuration_value("kvm.incremental.backup", "true", args.mold_url, args.admin_apikey, args.admin_secretkey, cluster_id=cluster_id)
        print(f"Updated cluster configuration: kvm.incremental.backup=true (clusterid={cluster_id})")
        restart_required = True

    if args.backup_chain_size is not None:
        desired_chain_size = str(args.backup_chain_size)
        log_info("Checking global configuration: backup.chain.size")
        current = get_configuration_value("backup.chain.size", args.mold_url, args.admin_apikey, args.admin_secretkey)
        if current != desired_chain_size:
            log_info(f"Updating global configuration: backup.chain.size={desired_chain_size}")
            update_configuration_value("backup.chain.size", desired_chain_size, args.mold_url, args.admin_apikey, args.admin_secretkey)
            print(f"Updated global configuration: backup.chain.size={desired_chain_size}")

    desired_stage_root = str(BACKUP_STAGING_ROOT)
    log_info(f"Checking global configuration: {NETBACKUP_STAGE_ROOT_CONFIG_NAME}")
    current = get_configuration_value(NETBACKUP_STAGE_ROOT_CONFIG_NAME, args.mold_url, args.admin_apikey, args.admin_secretkey)
    if current != desired_stage_root:
        log_info(f"Updating global configuration: {NETBACKUP_STAGE_ROOT_CONFIG_NAME}={desired_stage_root}")
        update_configuration_value(NETBACKUP_STAGE_ROOT_CONFIG_NAME, desired_stage_root, args.mold_url, args.admin_apikey, args.admin_secretkey)
        print(f"Updated global configuration: {NETBACKUP_STAGE_ROOT_CONFIG_NAME}={desired_stage_root}")

    log_info("Checking zone configuration: backup.framework.provider.plugin")
    current = get_configuration_value("backup.framework.provider.plugin", args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
    updated = append_provider_if_missing(current, NETBACKUP_PROVIDER_DISPLAY_NAME)
    if updated != current:
        log_info(f"Updating zone configuration: backup.framework.provider.plugin={updated}")
        update_configuration_value("backup.framework.provider.plugin", updated, args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
        print(f"Updated zone configuration: backup.framework.provider.plugin={updated} (zoneid={zone_id})")

    log_info("Checking zone configuration: backup.plugin.netbackup.url")
    current = get_configuration_value("backup.plugin.netbackup.url", args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
    if current != args.netbackup_url:
        log_info(f"Updating zone configuration: backup.plugin.netbackup.url={args.netbackup_url}")
        update_configuration_value("backup.plugin.netbackup.url", args.netbackup_url, args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
        print(f"Updated zone configuration: backup.plugin.netbackup.url={args.netbackup_url} (zoneid={zone_id})")

    log_info("Checking zone configuration: backup.plugin.netbackup.apikey")
    current = get_configuration_value("backup.plugin.netbackup.apikey", args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
    if current != args.netbackup_apikey:
        log_info("Updating zone configuration: backup.plugin.netbackup.apikey=<hidden>")
        update_configuration_value("backup.plugin.netbackup.apikey", args.netbackup_apikey, args.mold_url, args.admin_apikey, args.admin_secretkey, zone_id)
        print(f"Updated zone configuration: backup.plugin.netbackup.apikey=<hidden> (zoneid={zone_id})")

    return restart_required


def ensure_netbackup_offering(zone_id: str, args: argparse.Namespace) -> None:
    log_step("Ensure Backup Offering")
    log_info(f"Checking existing NetBackup backup offerings for zoneid={zone_id}")
    data = invoke_mold_api("GET", "listBackupOfferings", {
        "listall": "true",
        "page": "1",
        "pagesize": "500",
    }, args.mold_url, args.admin_apikey, args.admin_secretkey)
    offerings = data.get("listbackupofferingsresponse", {}).get("backupoffering", [])
    if isinstance(offerings, dict):
        offerings = [offerings]
    for offering in offerings:
        provider = str(offering.get("provider", "")).lower()
        offering_zone = str(offering.get("zoneid", "") or offering.get("zoneId", ""))
        if provider in {NETBACKUP_PROVIDER_DISPLAY_NAME, NETBACKUP_PROVIDER_CANONICAL_NAME} and offering_zone == zone_id:
            print(f"NetBackup backup offering already exists for zoneid={zone_id}")
            return

    log_info(f"Importing NetBackup backup offering for zoneid={zone_id}")
    data = invoke_mold_api("POST", "importBackupOffering", {
        "name": NETBACKUP_OFFERING_NAME,
        "description": NETBACKUP_OFFERING_DESCRIPTION,
        "provider": NETBACKUP_PROVIDER_DISPLAY_NAME,
        "externalid": NETBACKUP_OFFERING_EXTERNAL_ID,
        "allowuserdrivenbackups": "false",
        "zoneid": zone_id,
    }, args.mold_url, args.admin_apikey, args.admin_secretkey)
    response = data.get("importbackupofferingresponse", {})
    job_id = str(response.get("jobid", "") or "")
    wait_for_async_job(
        job_id,
        args.mold_url,
        args.admin_apikey,
        args.admin_secretkey,
        description=f"importBackupOffering zoneid={zone_id}",
    )

    verify = invoke_mold_api("GET", "listBackupOfferings", {
        "listall": "true",
        "page": "1",
        "pagesize": "500",
    }, args.mold_url, args.admin_apikey, args.admin_secretkey)
    offerings = verify.get("listbackupofferingsresponse", {}).get("backupoffering", [])
    if isinstance(offerings, dict):
        offerings = [offerings]
    for offering in offerings:
        provider = str(offering.get("provider", "")).lower()
        offering_zone = str(offering.get("zoneid", "") or offering.get("zoneId", ""))
        if provider in {NETBACKUP_PROVIDER_DISPLAY_NAME, NETBACKUP_PROVIDER_CANONICAL_NAME} and offering_zone == zone_id:
            print(f"Imported NetBackup backup offering for zoneid={zone_id}")
            return
    fail(f"importBackupOffering async job completed but NetBackup backup offering was not found for zoneid={zone_id}")


def write_hook(path: Path, comment_name: str, helper_path: str, is_post: bool) -> None:
    backup_existing_file(path)
    if is_post:
        content = f"""#!/bin/sh

# Generated by netbackup-host-config.py: {comment_name}

CLIENT="${{1:-}}"
POLICY="${{2:-}}"
SCHEDULE="${{3:-}}"
TYPE="${{4:-}}"
STATUS="${{5:-0}}"

LOG="{HOOK_LOG_PATH}"
HELPER="{helper_path}"

log_line() {{
  printf '%s %s\\n' "$(date '+%F %T')" "$1" >> "$LOG" 2>&1
}}

if [ $# -lt 4 ]; then
  log_line "POST invalid-args argc=$# client=$CLIENT policy=$POLICY schedule=$SCHEDULE type=$TYPE status=$STATUS"
  exit 2
fi

if [ ! -x "$HELPER" ]; then
  log_line "POST helper-missing path=$HELPER"
  exit 3
fi

log_line "POST start client=$CLIENT policy=$POLICY schedule=$SCHEDULE type=$TYPE status=$STATUS"
"$HELPER" "$POLICY" "$SCHEDULE" "$CLIENT" "$STATUS" >> "$LOG" 2>&1
RC=$?
log_line "POST end rc=$RC client=$CLIENT policy=$POLICY schedule=$SCHEDULE type=$TYPE status=$STATUS"
exit $RC
"""
    else:
        content = f"""#!/bin/sh

# Generated by netbackup-host-config.py: {comment_name}

CLIENT="${{1:-}}"
POLICY="${{2:-}}"
SCHEDULE="${{3:-}}"
TYPE="${{4:-}}"

LOG="{HOOK_LOG_PATH}"
HELPER="{helper_path}"

log_line() {{
  printf '%s %s\\n' "$(date '+%F %T')" "$1" >> "$LOG" 2>&1
}}

if [ $# -lt 4 ]; then
  log_line "PRE invalid-args argc=$# client=$CLIENT policy=$POLICY schedule=$SCHEDULE type=$TYPE"
  exit 2
fi

if [ ! -x "$HELPER" ]; then
  log_line "PRE helper-missing path=$HELPER"
  exit 3
fi

log_line "PRE start client=$CLIENT policy=$POLICY schedule=$SCHEDULE type=$TYPE"
"$HELPER" "$POLICY" "$SCHEDULE" "$CLIENT" >> "$LOG" 2>&1
RC=$?
log_line "PRE end rc=$RC client=$CLIENT policy=$POLICY schedule=$SCHEDULE type=$TYPE"
exit $RC
"""
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def render_policy_template(template_path: Path, replacements: dict[str, str]) -> str:
    if not template_path.is_file():
        fail(f"Policy template file not found: {template_path}")

    lines = template_path.read_text(encoding="utf-8").splitlines()
    rendered = []
    seen = set()
    pattern = re.compile(r"^([A-Z0-9_]+)=(.*)$")

    for line in lines:
        match = pattern.match(line)
        if not match:
            rendered.append(line)
            continue

        key = match.group(1)
        if key in replacements:
            rendered.append(f'{key}="{replacements[key]}"' if key in {"VM_INCLUDE", "VM_EXCLUDE", "MOLD_URL", "ADMIN_APIKEY"} else f"{key}={replacements[key]}")
            seen.add(key)
        else:
            rendered.append(line)

    for key, value in replacements.items():
        if key not in seen and key not in {"VM_INCLUDE", "VM_EXCLUDE", "MOLD_URL", "ADMIN_APIKEY"}:
            rendered.append(f"{key}={value}")
        elif key not in seen:
            rendered.append(f'{key}="{value}"')

    return "\n".join(rendered) + "\n"


def write_config_file(path: Path, args: argparse.Namespace) -> None:
    backup_existing_file(path)
    content = render_policy_template(POLICY_TEMPLATE_PATH, {
        "VM_INCLUDE": args.vm_include,
        "VM_EXCLUDE": args.vm_exclude,
        "MOLD_URL": args.mold_url,
        "ADMIN_APIKEY": args.admin_apikey,
    })
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def write_restore_config(path: Path, args: argparse.Namespace, secret_path: Path) -> None:
    backup_existing_file(path)
    content = "\n".join([
        f'MOLD_URL="{args.mold_url}"',
        f'ADMIN_APIKEY="{args.admin_apikey}"',
        f'MOLD_SECRET_FILE="{secret_path}"',
        f'SECRET_KEY_FILE="{SECRET_KEY_FILE}"',
        'SECRET_HELPER="/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/netbackup-host-secret-helper.sh"',
        f'LOG_FILE="{RESTORE_LOG_PATH}"',
        f'NETBACKUP_STAGING_ROOT="{BACKUP_STAGING_ROOT}"',
        "",
    ])
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def write_watcher_config(path: Path, args: argparse.Namespace) -> None:
    backup_existing_file(path)
    content = "\n".join([
        f'NETBACKUP_URL="{args.netbackup_url}"',
        f'NETBACKUP_APIKEY="{args.netbackup_apikey}"',
        'NETBACKUP_SSL_VERIFY="false"',
        'NETBACKUP_CA_FILE=""',
        f'NETBACKUP_STAGING_ROOT="{BACKUP_STAGING_ROOT}"',
        f'RESTORE_NOTIFY_SCRIPT="{RESTORE_NOTIFY_OUTPUT_PATH}"',
        f'MOLD_CONFIG_FILE="{RESTORE_CONFIG_PATH}"',
        f'LOG_FILE="{WATCHER_LOG_PATH}"',
        'STATE_FILE="/var/lib/ablestack/netbackup/restore-watcher-state.json"',
        'POLL_INTERVAL_SECONDS="60"',
        'NETBACKUP_CLIENT_NAME=""',
        'NETBACKUP_BP_CONF_PATH="/usr/openv/netbackup/bp.conf"',
        'PROCESS_SINGLE_RESTORE_PATH_ONLY="true"',
        'RECENT_JOB_WINDOW_SECONDS="86400"',
        'REQUIRE_JOB_TIMESTAMP="false"',
        'SKIP_EXISTING_JOBS_ON_START="true"',
        'NETBACKUP_JOBS_PATH="/admin/jobs"',
        'NETBACKUP_JOB_FILE_LISTS_ENABLED="true"',
        'NETBACKUP_JOBS_FILTER="jobType eq \'RESTORE\'"',
        'NETBACKUP_JOBS_LIMIT="100"',
        'PROCESSED_JOB_TTL_SECONDS="604800"',
        "",
    ])
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def install_watcher_files() -> None:
    watcher_source = SCRIPT_DIR / "netbackup-host-restore-watcher.py"
    restore_notify_source = SCRIPT_DIR / "netbackup-host-restore-notify"
    if not watcher_source.is_file():
        fail(f"Required watcher source file not found: {watcher_source}")
    if not restore_notify_source.is_file():
        fail(f"Required restore notify source file not found: {restore_notify_source}")

    WATCHER_OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESTORE_NOTIFY_OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(str(watcher_source), str(WATCHER_OUTPUT_PATH))
    shutil.copy2(str(restore_notify_source), str(RESTORE_NOTIFY_OUTPUT_PATH))
    WATCHER_OUTPUT_PATH.chmod(0o755)
    RESTORE_NOTIFY_OUTPUT_PATH.chmod(0o755)


def write_watcher_service() -> None:
    backup_existing_file(WATCHER_SERVICE_PATH)
    content = f"""[Unit]
Description=AbleStack NetBackup WebUI restore watcher
After=network-online.target libvirtd.service mold-agent.service mold.service
Wants=network-online.target mold-agent.service mold.service

[Service]
Type=simple
Environment=NETBACKUP_WATCHER_CONFIG={WATCHER_CONFIG_PATH}
ExecStart={WATCHER_OUTPUT_PATH}
Restart=always
RestartSec=30

[Install]
WantedBy=multi-user.target
"""
    WATCHER_SERVICE_PATH.write_text(content, encoding="utf-8")
    WATCHER_SERVICE_PATH.chmod(0o644)


def write_logrotate_config() -> None:
    backup_existing_file(LOGROTATE_CONFIG_PATH)
    content = f"""{HOOK_LOG_PATH}
{RESTORE_LOG_PATH}
{WATCHER_LOG_PATH} {{
    daily
    maxsize 50M
    rotate 14
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
    create 0640 root root
}}
"""
    LOGROTATE_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOGROTATE_CONFIG_PATH.write_text(content, encoding="utf-8")
    LOGROTATE_CONFIG_PATH.chmod(0o644)


def enable_watcher_service() -> None:
    if not shutil.which("systemctl"):
        print(f"systemctl not found. Please enable watcher manually: {WATCHER_OUTPUT_PATH}")
        return
    subprocess.run(["systemctl", "daemon-reload"], check=True)
    subprocess.run(["systemctl", "enable", "--now", WATCHER_SERVICE_PATH.name], check=True)
    print(f"Enabled and started watcher service: {WATCHER_SERVICE_PATH.name}")


def write_encrypted_secret_file(path: Path, secret: str, secret_key_file: Path = SECRET_KEY_FILE,
                                skip_permission_validation: bool = False) -> None:
    if not skip_permission_validation:
        if secret_key_file == SECRET_KEY_FILE:
            validate_secret_key_file()
        else:
            validate_custom_secret_key_file(secret_key_file)
    elif not secret_key_file.is_file():
        fail(f"Secret key file not found: {secret_key_file}")
    backup_existing_file(path)
    helper_env = {**os.environ, "SECRET_KEY_FILE": str(secret_key_file)}
    if skip_permission_validation:
        helper_env["SKIP_SECRET_KEY_PERMISSION_VALIDATION"] = "1"
    proc = subprocess.run(
        [sys.executable, str(SCRIPT_DIR / "netbackup-host-secret-helper.py"), "encrypt", str(path)],
        input=secret.encode(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        env=helper_env,
    )
    if proc.returncode != 0:
        fail(f"Failed to encrypt ADMIN_SECRETKEY: {proc.stderr.decode(errors='replace')}")
    path.chmod(0o600)


def set_bp_conf_value(path: Path, key: str, value: str) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    pattern = re.compile(rf"^\s*{re.escape(key)}\s*=")
    replaced = False
    new_lines = []
    for line in lines:
        if pattern.match(line):
            new_lines.append(f"{key} = {value}")
            replaced = True
        else:
            new_lines.append(line)
    if not replaced:
        new_lines.append(f"{key} = {value}")
    path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")


def apply_netbackup_bp_conf() -> bool:
    if not NETBACKUP_BP_CONF_PATH.is_file():
        print(f"NetBackup bp.conf not found: {NETBACKUP_BP_CONF_PATH}")
        return False
    copy_existing_file_backup(NETBACKUP_BP_CONF_PATH)
    set_bp_conf_value(NETBACKUP_BP_CONF_PATH, "BPSTART_TIMEOUT", "21600")
    set_bp_conf_value(NETBACKUP_BP_CONF_PATH, "BPEND_TIMEOUT", "21600")
    set_bp_conf_value(NETBACKUP_BP_CONF_PATH, "CLIENT_READ_TIMEOUT", "21600")
    set_bp_conf_value(NETBACKUP_BP_CONF_PATH, "CLIENT_CONNECT_TIMEOUT", "1800")
    set_bp_conf_value(NETBACKUP_BP_CONF_PATH, "SERVER_CONNECT_TIMEOUT", "1800")
    print(f"Updated NetBackup config: {NETBACKUP_BP_CONF_PATH}")
    return True


def ensure_backup_staging_root() -> None:
    BACKUP_STAGING_ROOT.mkdir(parents=True, exist_ok=True)
    try:
        BACKUP_STAGING_ROOT.chmod(0o755)
    except PermissionError:
        # Directory may already exist with stricter ownership-based permissions.
        pass


def apply_permissions() -> None:
    for directory in (HOOK_OUTPUT_DIR, CONFIG_OUTPUT_DIR, SECRET_OUTPUT_DIR, BACKUP_STAGING_ROOT):
        if not directory.exists():
            continue
        try:
            directory.chmod(0o755)
        except PermissionError:
            pass

    for path in HOOK_OUTPUT_DIR.glob("bpstart_notify.*"):
        if path.is_file():
            try:
                path.chmod(0o755)
            except PermissionError:
                pass

    for path in HOOK_OUTPUT_DIR.glob("bpend_notify.*"):
        if path.is_file():
            try:
                path.chmod(0o755)
            except PermissionError:
                pass

    for path in CONFIG_OUTPUT_DIR.glob("netbackup-host-*.conf"):
        if path.is_file():
            try:
                path.chmod(0o600)
            except PermissionError:
                pass

    for path in (RESTORE_CONFIG_PATH, WATCHER_CONFIG_PATH):
        if path.is_file():
            try:
                path.chmod(0o600)
            except PermissionError:
                pass

    secret_path = SECRET_OUTPUT_DIR / "secret.enc"
    if secret_path.is_file():
        try:
            secret_path.chmod(0o600)
        except PermissionError:
            pass


def restart_netbackup_service() -> None:
    if shutil.which("systemctl"):
        subprocess.run(["systemctl", "restart", NETBACKUP_SERVICE_NAME], check=True)
        print(f"Restarted NetBackup service via systemctl: {NETBACKUP_SERVICE_NAME}")
        return
    if shutil.which("service"):
        subprocess.run(["service", NETBACKUP_SERVICE_NAME, "restart"], check=True)
        print(f"Restarted NetBackup service via service: {NETBACKUP_SERVICE_NAME}")
        return
    print(f"NetBackup service restart command not found. Please restart manually: {NETBACKUP_SERVICE_NAME}")


def generate_host_outputs(zone_id: str, args: argparse.Namespace) -> None:
    log_step("Generate Host Files")
    log_info("Generating host-side NetBackup hook/config/watcher files")
    HOOK_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    SECRET_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    WATCHER_SERVICE_PATH.parent.mkdir(parents=True, exist_ok=True)
    LOGROTATE_CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)

    policy_safe = sanitize_name(args.policy_name)
    pre_hook = HOOK_OUTPUT_DIR / f"bpstart_notify.{policy_safe}"
    post_hook = HOOK_OUTPUT_DIR / f"bpend_notify.{policy_safe}"
    config_path = CONFIG_OUTPUT_DIR / f"netbackup-host-{policy_safe}.conf"
    secret_path = SECRET_OUTPUT_DIR / "secret.enc"

    write_hook(pre_hook, f"bpstart_notify.{policy_safe}", PRE_HELPER_PATH, False)
    write_hook(post_hook, f"bpend_notify.{policy_safe}", POST_HELPER_PATH, True)
    write_config_file(config_path, args)
    write_encrypted_secret_file(secret_path, args.admin_secretkey)
    write_restore_config(RESTORE_CONFIG_PATH, args, secret_path)
    write_watcher_config(WATCHER_CONFIG_PATH, args)
    install_watcher_files()
    write_watcher_service()
    write_logrotate_config()
    ensure_backup_staging_root()
    apply_permissions()
    enable_watcher_service()
    if apply_netbackup_bp_conf():
        restart_netbackup_service()
    else:
        print("Skipped NetBackup service restart because bp.conf was not found.")

    print("\nGenerated host files:")
    print(f"  PRE hook   : {pre_hook}")
    print(f"  POST hook  : {post_hook}")
    print(f"  Config     : {config_path}")
    print(f"  Secret(enc): {secret_path}")
    print(f"  Restore cfg: {RESTORE_CONFIG_PATH}")
    print(f"  Watcher cfg: {WATCHER_CONFIG_PATH}")
    print(f"  Watcher    : {WATCHER_OUTPUT_PATH}")
    print(f"  Service    : {WATCHER_SERVICE_PATH}")
    print(f"  Logrotate  : {LOGROTATE_CONFIG_PATH}")
    print(f"  Staging dir: {BACKUP_STAGING_ROOT}")
    print(f"  Mold config: {NETBACKUP_STAGE_ROOT_CONFIG_NAME}={BACKUP_STAGING_ROOT}")
    print(f"  Zone ID    : {zone_id}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="NetBackup configuration helper")
    parser.add_argument("--policy-name")
    parser.add_argument("--vm-include")
    parser.add_argument("--vm-exclude", default="")
    parser.add_argument("--backup-chain-size", type=int,
                        help="Update global backup.chain.size.")
    parser.add_argument("--backup-staging-root", default=str(BACKUP_STAGING_ROOT),
                        help="Local NetBackup staging directory used by host hooks and Mold backup.plugin.netbackup.stage.root.path.")
    parser.add_argument("--mold-url", required=True)
    parser.add_argument("--admin-apikey", required=True)
    parser.add_argument("--admin-secretkey", required=True)
    parser.add_argument("--netbackup-url")
    parser.add_argument("--netbackup-apikey")
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    global BACKUP_STAGING_ROOT
    if not args.policy_name:
        fail("--policy-name is required")
    if not args.vm_include:
        args.vm_include = "*"
    if args.backup_chain_size is not None and args.backup_chain_size <= 0:
        fail("--backup-chain-size must be a positive integer")
    if not args.backup_staging_root:
        fail("--backup-staging-root is required")
    BACKUP_STAGING_ROOT = Path(args.backup_staging_root).expanduser()
    if not BACKUP_STAGING_ROOT.is_absolute():
        fail("--backup-staging-root must be an absolute path")
    BACKUP_STAGING_ROOT = BACKUP_STAGING_ROOT.resolve(strict=False)
    if not args.netbackup_url:
        fail("--netbackup-url is required")
    if not args.netbackup_apikey:
        fail("--netbackup-apikey is required")
    validate_secret_key_file()


def main() -> None:
    args = parse_args()
    validate_args(args)

    log_step("NetBackup Configuration")
    log_info("Starting NetBackup host configuration")

    zone_id, cluster_id = resolve_host_context(args.policy_name, args.mold_url, args.admin_apikey, args.admin_secretkey)
    restart_required = ensure_backup_framework_configuration(zone_id, cluster_id, args)
    if restart_required:
        fail("Updated non-dynamic backup configuration. Restart the Mold management server, then run this script again to import the NetBackup offering.")
    ensure_netbackup_offering(zone_id, args)
    log_ok("Completed Mold API configuration for NetBackup host")
    generate_host_outputs(zone_id, args)

    log_ok("Completed NetBackup host configuration")


if __name__ == "__main__":
    main()
