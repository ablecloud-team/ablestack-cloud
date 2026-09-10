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

import json
import os
import shlex
import socket
import ssl
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


CONFIG_FILE = Path(os.environ.get("NETBACKUP_WATCHER_CONFIG", "/etc/ablestack/netbackup/restore-watcher.conf"))
DEFAULT_STATE_FILE = Path("/var/lib/ablestack/netbackup/restore-watcher-state.json")
DEFAULT_LOG_FILE = Path("/var/log/netbackup-mold-restore-watcher.log")
NETBACKUP_ACCEPT = "application/vnd.netbackup+json;version=12.0"


def log(message: str) -> None:
    log_file = Path(CONFIG.get("LOG_FILE", str(DEFAULT_LOG_FILE)))
    log_file.parent.mkdir(parents=True, exist_ok=True)
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}"
    with log_file.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def load_key_value_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        raise SystemExit(f"Config file not found: {path}")
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = shlex.split(value.strip())[0] if value.strip() else ""
    return values


CONFIG = load_key_value_file(CONFIG_FILE)


def require_config(name: str) -> str:
    value = CONFIG.get(name, "")
    if not value:
        raise SystemExit(f"{name} must be configured in {CONFIG_FILE}")
    return value


def load_state() -> dict[str, Any]:
    state_file = Path(CONFIG.get("STATE_FILE", str(DEFAULT_STATE_FILE)))
    if not state_file.is_file():
        return {"processed": {}}
    try:
        return json.loads(state_file.read_text(encoding="utf-8"))
    except Exception:
        return {"processed": {}}


def save_state(state: dict[str, Any]) -> None:
    state_file = Path(CONFIG.get("STATE_FILE", str(DEFAULT_STATE_FILE)))
    state_file.parent.mkdir(parents=True, exist_ok=True)
    tmp_file = state_file.with_suffix(".tmp")
    tmp_file.write_text(json.dumps(state, sort_keys=True, indent=2), encoding="utf-8")
    tmp_file.replace(state_file)


def prune_state(state: dict[str, Any]) -> None:
    ttl = int(CONFIG.get("PROCESSED_JOB_TTL_SECONDS", "604800"))
    cutoff = int(time.time()) - ttl
    processed = state.setdefault("processed", {})
    for job_id in list(processed.keys()):
        if processed[job_id].get("permanent"):
            continue
        if int(processed[job_id].get("timestamp", 0)) < cutoff:
            processed.pop(job_id, None)


def netbackup_get(path: str, params: Optional[dict[str, str]] = None) -> Any:
    base_url = require_config("NETBACKUP_URL").rstrip("/")
    api_key = require_config("NETBACKUP_APIKEY")
    query = f"?{urlencode(params)}" if params else ""
    request = Request(f"{base_url}{path}{query}", headers={
        "Accept": NETBACKUP_ACCEPT,
        "Authorization": f"Bearer {api_key}",
        "X-API-Key": api_key,
    })
    try:
        with urlopen(request, timeout=int(CONFIG.get("NETBACKUP_REQUEST_TIMEOUT", "60")), context=netbackup_ssl_context()) as response:
            payload = response.read().decode("utf-8")
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"NetBackup API returned HTTP {exc.code}: {body[:240]}")
    except URLError as exc:
        raise RuntimeError(f"NetBackup API failed: {exc}")
    return json.loads(payload) if payload.strip() else {}


def netbackup_ssl_context() -> Optional[ssl.SSLContext]:
    if not require_ssl_context():
        return None
    if CONFIG.get("NETBACKUP_SSL_VERIFY", "false").lower() == "false":
        return ssl._create_unverified_context()
    ca_file = CONFIG.get("NETBACKUP_CA_FILE", "")
    if ca_file:
        return ssl.create_default_context(cafile=ca_file)
    return ssl.create_default_context()


def require_ssl_context() -> bool:
    return require_config("NETBACKUP_URL").lower().startswith("https://")


def find_strings(node: Any) -> list[str]:
    found: list[str] = []
    if isinstance(node, dict):
        for value in node.values():
            found.extend(find_strings(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(find_strings(item))
    elif isinstance(node, str):
        found.append(node)
    return found


def extract_jobs(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, dict):
        data = payload.get("data")
        if isinstance(data, list):
            return [job for job in data if isinstance(job, dict)]
        if isinstance(data, dict):
            return [data]
        for key in ("jobs", "job", "items"):
            value = payload.get(key)
            if isinstance(value, list):
                return [job for job in value if isinstance(job, dict)]
    if isinstance(payload, list):
        return [job for job in payload if isinstance(job, dict)]
    return []


def get_nested(node: dict[str, Any], *paths: str) -> str:
    for path in paths:
        current: Any = node
        for part in path.split("."):
            if not isinstance(current, dict) or part not in current:
                current = None
                break
            current = current[part]
        if current is not None:
            return str(current)
    return ""


def job_id(job: dict[str, Any]) -> str:
    return get_nested(job, "id", "jobId", "jobid", "data.id", "attributes.jobId")


def job_state(job: dict[str, Any]) -> str:
    return get_nested(job, "state", "status", "attributes.state", "attributes.status", "attributes.jobState").lower()


def job_status_code(job: dict[str, Any]) -> str:
    return get_nested(job, "statusCode", "statuscode", "status", "attributes.statusCode", "attributes.statuscode", "attributes.status")


def parse_timestamp(value: str) -> Optional[float]:
    value = value.strip()
    if not value:
        return None
    if value.isdigit():
        timestamp = float(value)
        if timestamp > 9999999999:
            timestamp = timestamp / 1000
        return timestamp
    try:
        normalized = value.replace("Z", "+00:00")
        return datetime.fromisoformat(normalized).timestamp()
    except ValueError:
        pass
    for pattern in ("%Y-%m-%d %H:%M:%S", "%m/%d/%Y %H:%M:%S", "%Y/%m/%d %H:%M:%S"):
        try:
            return datetime.strptime(value, pattern).replace(tzinfo=timezone.utc).timestamp()
        except ValueError:
            continue
    return None


def job_reference_timestamp(job: dict[str, Any]) -> Optional[float]:
    value = get_nested(
        job,
        "endTime",
        "endtime",
        "ended",
        "completionTime",
        "completeTime",
        "updateTime",
        "updatedTime",
        "lastUpdateTime",
        "startTime",
        "attributes.endTime",
        "attributes.endtime",
        "attributes.ended",
        "attributes.completionTime",
        "attributes.completeTime",
        "attributes.updateTime",
        "attributes.updatedTime",
        "attributes.lastUpdateTime",
        "attributes.startTime",
        "attributes.timestamps.endTime",
        "attributes.timestamps.updateTime",
    )
    return parse_timestamp(value)


def is_recent_restore_job(job: dict[str, Any], job_id_value: str) -> bool:
    window_seconds = int(CONFIG.get("RECENT_JOB_WINDOW_SECONDS", "86400"))
    if window_seconds <= 0:
        return True
    timestamp = job_reference_timestamp(job)
    if timestamp is None:
        return CONFIG.get("REQUIRE_JOB_TIMESTAMP", "false").lower() != "true"
    if timestamp >= time.time() - window_seconds:
        return True
    log(f"Skipping old NetBackup restore jobId=[{job_id_value}] jobTimestamp=[{int(timestamp)}] windowSeconds=[{window_seconds}]")
    return False


def normalize_client_name(value: str) -> str:
    return value.strip().strip('"').strip("'").lower()


def client_name_aliases(value: str) -> set[str]:
    normalized = normalize_client_name(value)
    if not normalized:
        return set()
    aliases = {normalized}
    if "." in normalized:
        aliases.add(normalized.split(".", 1)[0])
    return aliases


def read_bp_conf_client_names() -> set[str]:
    bp_conf = Path(CONFIG.get("NETBACKUP_BP_CONF_PATH", "/usr/openv/netbackup/bp.conf"))
    names: set[str] = set()
    if not bp_conf.is_file():
        return names
    try:
        for raw_line in bp_conf.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip().upper() == "CLIENT_NAME":
                names.update(client_name_aliases(value))
    except OSError:
        return names
    return names


def local_client_aliases() -> set[str]:
    aliases: set[str] = set()
    configured_names = CONFIG.get("NETBACKUP_CLIENT_NAME", "")
    for value in configured_names.split(","):
        aliases.update(client_name_aliases(value))
    for value in (socket.gethostname(), socket.getfqdn()):
        aliases.update(client_name_aliases(value))
    aliases.update(read_bp_conf_client_names())
    return aliases


LOCAL_CLIENT_ALIASES = local_client_aliases()


def destination_client_aliases(job: dict[str, Any]) -> set[str]:
    destination = get_nested(
        job,
        "destinationClient",
        "destinationClientName",
        "destination.client",
        "destination.clientName",
        "destination.host",
        "clientName",
        "workloadDisplayName",
        "attributes.destinationClient",
        "attributes.destinationClientName",
        "attributes.destination.client",
        "attributes.destination.clientName",
        "attributes.destination.host",
        "attributes.recoveryOptions.destinationClient",
        "attributes.job.destinationClient",
        "attributes.clientName",
        "attributes.workloadDisplayName",
    )
    return client_name_aliases(destination)


def is_job_for_local_client(job: dict[str, Any]) -> bool:
    destination_aliases = destination_client_aliases(job)
    if not destination_aliases:
        return True
    return bool(destination_aliases & LOCAL_CLIENT_ALIASES)


def is_successful_restore_job(job: dict[str, Any]) -> bool:
    state = job_state(job)
    status_code = job_status_code(job)
    if state in {"done", "successful", "success", "completed"}:
        return status_code in {"", "0", "1"} or "success" in state
    return status_code == "0" and state not in {"active", "running", "queued", "incomplete", "failed"}


def extract_staging_paths_from_strings(values: list[str]) -> list[str]:
    staging_root = CONFIG.get("NETBACKUP_STAGING_ROOT", "/tmp/mold/netbackup").rstrip("/")
    paths = []
    for value in values:
        normalized = value.rstrip("/")
        if normalized.startswith(staging_root + "/"):
            suffix = normalized[len(staging_root) + 1:]
            parts = [part for part in suffix.split("/") if part]
            if len(parts) >= 2:
                paths.append(f"{staging_root}/{parts[0]}/{parts[1]}")
    return sorted(set(paths), key=len, reverse=True)


def fetch_restore_job_file_list(job_id_value: str) -> list[str]:
    if CONFIG.get("NETBACKUP_JOB_FILE_LISTS_ENABLED", "true").lower() != "true":
        return []
    try:
        path = f"{CONFIG.get('NETBACKUP_JOBS_PATH', '/admin/jobs').rstrip('/')}/{job_id_value}/file-lists"
        payload = netbackup_get(path)
        return find_strings(payload)
    except Exception as exc:
        log(f"FAILED job file-list query jobId=[{job_id_value}] error=[{exc}]")
        return []


def candidate_restore_identifiers(job: dict[str, Any], job_id_value: str) -> list[str]:
    file_list_paths = extract_staging_paths_from_strings(fetch_restore_job_file_list(job_id_value))
    if file_list_paths:
        return file_list_paths

    job_paths = extract_staging_paths_from_strings(find_strings(job))
    if job_paths:
        return job_paths

    restore_backup_ids = get_nested(job, "restoreBackupIDs", "restoreBackupIds", "attributes.restoreBackupIDs", "attributes.restoreBackupIds")
    if restore_backup_ids:
        return sorted(set(value.strip() for value in restore_backup_ids.splitlines() if value.strip()))
    return []


def is_backup_id(identifier: str) -> bool:
    return "/" not in identifier and "\\" not in identifier


def local_existing_restore_identifiers(identifiers: list[str]) -> list[str]:
    return [identifier for identifier in identifiers if is_backup_id(identifier) or Path(identifier).exists()]


def should_process_single_restore_path(external_ids: list[str], job_id_value: str) -> bool:
    if CONFIG.get("PROCESS_SINGLE_RESTORE_PATH_ONLY", "true").lower() != "true":
        return True
    if len(external_ids) == 1:
        return True
    log(f"Skipping NetBackup restore jobId=[{job_id_value}] because it contains multiple restore paths: {external_ids}")
    return False


def invoke_restore_notify(external_id: str, job_id_value: str) -> bool:
    notify_script = require_config("RESTORE_NOTIFY_SCRIPT")
    env = os.environ.copy()
    if CONFIG.get("MOLD_CONFIG_FILE"):
        env["MOLD_CONFIG_FILE"] = CONFIG["MOLD_CONFIG_FILE"]
    if CONFIG.get("MOLD_RESTORE_API_METHOD"):
        env["MOLD_RESTORE_API_METHOD"] = CONFIG["MOLD_RESTORE_API_METHOD"]
    proc = subprocess.run(
        [notify_script, "netbackup-host-restore-watcher", external_id, "restore", job_id_value],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        check=False,
    )
    if proc.returncode != 0:
        log(f"FAILED notify jobId=[{job_id_value}] externalId=[{external_id}] rc=[{proc.returncode}] stderr=[{proc.stderr.strip()}]")
        return False
    notify_output = proc.stdout.strip()
    if notify_output.startswith("SUBMITTED"):
        log(f"Submitted Mold restore from NetBackup watcher jobId=[{job_id_value}] externalId=[{external_id}] result=[{notify_output}]")
    elif notify_output.startswith("SKIPPED"):
        log(f"Skipped Mold restore from NetBackup watcher jobId=[{job_id_value}] externalId=[{external_id}] result=[{notify_output}]")
    else:
        log(f"Handled NetBackup restore watcher notification jobId=[{job_id_value}] externalId=[{external_id}] stdout=[{notify_output}]")
    return True


def fetch_restore_jobs() -> list[dict[str, Any]]:
    params = {}
    job_filter = CONFIG.get("NETBACKUP_JOBS_FILTER", "")
    if job_filter:
        params["filter"] = job_filter
    page_limit = CONFIG.get("NETBACKUP_JOBS_LIMIT", "")
    if page_limit:
        params["page[limit]"] = page_limit

    payload = netbackup_get(CONFIG.get("NETBACKUP_JOBS_PATH", "/admin/jobs"), params)
    return extract_jobs(payload)


def mark_existing_jobs_processed(state: dict[str, Any]) -> None:
    if CONFIG.get("SKIP_EXISTING_JOBS_ON_START", "true").lower() != "true":
        return
    if state.get("initialJobBaselineCompleted"):
        return

    processed = state.setdefault("processed", {})
    marked_count = 0
    for job in fetch_restore_jobs():
        current_job_id = job_id(job)
        if not current_job_id or current_job_id in processed:
            continue
        if not is_successful_restore_job(job):
            continue
        identifiers = candidate_restore_identifiers(job, current_job_id)
        if not identifiers:
            continue
        processed[current_job_id] = {
            "skipped": "initialJobBaseline",
            "externalIds": identifiers,
            "permanent": True,
            "timestamp": int(time.time()),
        }
        marked_count += 1
    state["initialJobBaselineCompleted"] = True
    log(f"Marked existing NetBackup restore jobs as processed on watcher startup count=[{marked_count}]")


def poll_once(state: dict[str, Any]) -> None:
    jobs = fetch_restore_jobs()
    processed = state.setdefault("processed", {})
    for job in jobs:
        current_job_id = job_id(job)
        if not current_job_id or current_job_id in processed:
            continue
        if not is_successful_restore_job(job):
            continue
        if not is_recent_restore_job(job, current_job_id):
            processed[current_job_id] = {
                "skipped": "oldRestoreJob",
                "timestamp": int(time.time()),
            }
            continue
        if not is_job_for_local_client(job):
            processed[current_job_id] = {
                "skipped": "destinationClientMismatch",
                "timestamp": int(time.time()),
            }
            continue
        identifiers = candidate_restore_identifiers(job, current_job_id)
        if not identifiers:
            continue
        if not should_process_single_restore_path(identifiers, current_job_id):
            processed[current_job_id] = {
                "skipped": "multipleRestorePaths",
                "externalIds": identifiers,
                "timestamp": int(time.time()),
            }
            continue
        local_identifiers = local_existing_restore_identifiers(identifiers)
        if not local_identifiers:
            continue
        restore_identifier = local_identifiers[0]
        if invoke_restore_notify(restore_identifier, current_job_id):
            processed[current_job_id] = {
                "externalId": restore_identifier,
                "timestamp": int(time.time()),
            }


def main() -> None:
    interval = int(CONFIG.get("POLL_INTERVAL_SECONDS", "60"))
    once = "--once" in sys.argv
    log(f"Starting NetBackup restore watcher config=[{CONFIG_FILE}] once=[{once}] sslVerify=[{CONFIG.get('NETBACKUP_SSL_VERIFY', 'false')}] caFile=[{CONFIG.get('NETBACKUP_CA_FILE', '')}]")
    state = load_state()
    try:
        mark_existing_jobs_processed(state)
        save_state(state)
    except Exception as exc:
        log(f"FAILED watcher initial baseline error=[{exc}]")
    while True:
        try:
            prune_state(state)
            poll_once(state)
            save_state(state)
        except Exception as exc:
            log(f"FAILED watcher poll error=[{exc}]")
        if once:
            break
        time.sleep(interval)


if __name__ == "__main__":
    main()
