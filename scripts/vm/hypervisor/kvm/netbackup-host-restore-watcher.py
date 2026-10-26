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
import fcntl
import json
import os
import re
import shlex
import socket
import ssl
import stat
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


CONFIG_FILE = Path(os.environ.get("NETBACKUP_WATCHER_CONFIG", "/etc/ablestack/netbackup/restore-watcher.conf"))
DEFAULT_STATE_FILE = Path("/var/lib/ablestack/netbackup/restore-watcher-state.json")
DEFAULT_LOG_FILE = Path("/var/log/netbackup-mold-restore-watcher.log")
DEFAULT_LOCK_FILE = Path("/var/lib/ablestack/netbackup/restore-watcher.lock")
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
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {"processed": {}}
    # Never replace unreadable history with a fresh baseline: completed restore
    # sessions could otherwise be silently skipped or requested again.
    if not isinstance(state, dict) or any(not isinstance(state.get(key, {}), dict)
                                          for key in ("processed", "restoreValidation")):
        raise ValueError(f"Invalid watcher state: {state_file}")
    return state


def save_state(state: dict[str, Any]) -> None:
    state_file = Path(CONFIG.get("STATE_FILE", str(DEFAULT_STATE_FILE)))
    state_file.parent.mkdir(parents=True, exist_ok=True)
    tmp_file = state_file.with_suffix(".tmp")
    with tmp_file.open("w", encoding="utf-8") as handle:
        os.fchmod(handle.fileno(), 0o600)
        json.dump(state, handle, sort_keys=True, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    tmp_file.replace(state_file)
    directory_fd = os.open(state_file.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def prune_state(state: dict[str, Any]) -> None:
    ttl = int(CONFIG.get("PROCESSED_JOB_TTL_SECONDS", "604800"))
    cutoff = int(time.time()) - ttl
    processed = state.setdefault("processed", {})
    for job_id in list(processed.keys()):
        if processed[job_id].get("permanent"):
            continue
        if int(processed[job_id].get("timestamp", 0)) < cutoff:
            processed.pop(job_id, None)
    validation = state.setdefault("restoreValidation", {})
    for job_id_value in list(validation):
        if int(validation[job_id_value].get("timestamp", 0)) < cutoff:
            validation.pop(job_id_value, None)


def netbackup_get(path: str, params: Optional[dict[str, str]] = None) -> Any:
    base_url = require_config("NETBACKUP_URL").rstrip("/")
    api_key = require_config("NETBACKUP_APIKEY")
    query = f"?{urlencode(params)}" if params else ""
    request = Request(f"{base_url}{path}{query}", headers={
        "Accept": NETBACKUP_ACCEPT,
        "Authorization": api_key,
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
    return get_nested(job, "attributes.state", "attributes.jobState", "attributes.jobStatus", "state", "jobState", "jobStatus",
                      "data.attributes.state", "data.attributes.jobState", "data.attributes.jobStatus").strip().lower()


def job_status_code(job: dict[str, Any]) -> str:
    # Both 10.5 and 11.1 expose the final exit code separately from the Job state.
    # Do not infer exit status from words such as SUCCESS, or from a missing value.
    values = set()
    for path in ("attributes.status", "attributes.statusCode", "attributes.statuscode", "statusCode", "statuscode", "status",
                 "data.attributes.status", "data.attributes.statusCode"):
        value = get_nested(job, path).strip()
        if re.fullmatch(r"[0-9]+", value):
            values.add(str(int(value)))
    return next(iter(values)) if len(values) == 1 else ""


def parse_timestamp(value: str) -> Optional[float]:
    value = value.strip()
    if not value:
        return None
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", value):
        timestamp = float(value)
        if timestamp > 9999999999:
            timestamp = timestamp / 1000
        return timestamp
    try:
        normalized = value.replace("Z", "+00:00")
        normalized = re.sub(r"(\.[0-9]{6})[0-9]+(?=[+-]|$)", r"\1", normalized)
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.timestamp()
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
    return restore_job_outcome(job) == "SUCCESS"


def restore_job_outcome(job: dict[str, Any]) -> str:
    """Normalize the Jobs API contract shared by NetBackup 10.5 and 11.1."""
    state = job_state(job)
    status_code = job_status_code(job)
    if state in {"failed", "failure", "error", "canceled", "cancelled", "aborted"}:
        return "FAILED"
    if state in {"active", "running", "queued", "requeued", "suspended", "incomplete"}:
        return "PENDING"
    if state not in {"done", "completed", "succeeded", "successful", "success"} or not status_code:
        return "UNKNOWN"
    return {"0": "SUCCESS", "1": "PARTIAL"}.get(status_code, "FAILED")


def job_api_path(job_id_value: str, suffix: str = "") -> str:
    if not re.fullmatch(r"[0-9]+", job_id_value):
        raise ValueError("Invalid NetBackup Job ID")
    return f"{CONFIG.get('NETBACKUP_JOBS_PATH', '/admin/jobs').rstrip('/')}/{job_id_value}{suffix}"


def fetch_restore_job(job_id_value: str) -> dict[str, Any]:
    jobs = extract_jobs(netbackup_get(job_api_path(job_id_value)))
    if len(jobs) != 1 or job_id(jobs[0]) != job_id_value:
        raise ValueError("NetBackup job details do not match the requested restore Job ID")
    return jobs[0]


def job_log_lines(payload: Any) -> list[str]:
    lines = []
    if isinstance(payload, dict):
        for key, value in payload.items():
            # The Jobs try-logs resource returns data.attributes.log. Also handle
            # a list of attempts without treating IDs or links as error details.
            if key in {"log", "logs", "message", "errorMessage", "statusMessage", "detailedStatus"}:
                lines.extend(find_strings(value))
            elif isinstance(value, (dict, list)):
                lines.extend(job_log_lines(value))
    elif isinstance(payload, list):
        for value in payload:
            lines.extend(job_log_lines(value))
    return [line.strip() for line in lines if line.strip()]


def fetch_restore_job_details(job_id_value: str) -> list[str]:
    lines = job_log_lines(netbackup_get(job_api_path(job_id_value, "/try-logs")))
    if not lines:
        raise ValueError("NetBackup detailed restore log is unavailable or empty")
    return lines


def review_partial_restore_errors(lines: list[str]) -> None:
    if any(re.search(r"\bpartially restored\s+[1-9][0-9]*\s+files?\b", line, re.I) for line in lines):
        raise ValueError("NetBackup reports partially restored file contents")
    problems = [line for line in lines if re.search(r"\b(err|error|ftl|fatal|wrn|warn|warning)\b|permission denied", line, re.I)]
    if not problems:
        raise ValueError("Partial restore has no detailed error explaining status 1")
    optional = {"dominfo.xml", "domiflist.xml", "domblklist.xml", ".volume-bootstrap"}
    for line in problems:
        names = {name.rstrip(".,);]") for name in re.findall(
            r"(?:^|[/\\\s])([^/\\\s:'\"]+\.[^/\\\s:'\"]+)(?=[\s:'\"]|$)", line)}
        if names and names.issubset(optional):
            continue
        # Attribute-only warnings may be accepted after the actual required files
        # are checked. Read/write errors or an unexplained warning remain blocked.
        attribute_warning = re.search(r"(?:set|change|restore|preserve)\s+(?:(?:file|directory|access)\s+)?(?:permissions?|ownership|owner|times?|timestamps?|attributes?|ACL)\b|\b(?:chmod|chown|utime)\b", line, re.I)
        content_error = re.search(r"checksum|corrupt|truncat|I/O error|no space|cannot (?:open|read|write|create)|failed to (?:open|read|write|create)|partially restored|kept existing|\.(?:qcow2|raw|rbdiff)\b", line, re.I)
        if not attribute_warning or content_error:
            raise ValueError("Partial restore contains an unverified content error: " + line)


def read_restored_metadata(path: Path, started_at: float) -> str:
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_size <= 0 or before.st_size > 16 * 1024 * 1024:
        raise ValueError("Required restore metadata is missing, empty or not a regular file: " + str(path))
    # NetBackup preserves backup mtimes; ctime proves that this job touched the file.
    if before.st_ctime < started_at - 5:
        raise ValueError("Required metadata predates this restore Job: " + str(path))
    content = path.read_text(encoding="utf-8")
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
            after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        raise ValueError("Required metadata is still changing: " + str(path))
    return content


def verify_partial_restore_metadata(job: dict[str, Any], selections: list[str]) -> dict[str, Any]:
    started_at = parse_timestamp(get_nested(job, "attributes.startTime", "startTime", "attributes.timestamps.startTime"))
    if started_at is None:
        raise ValueError("Partial restore Job has no start time to distinguish restored files from stale files")
    roots = extract_staging_paths_from_strings(selections)
    if len(roots) != 1:
        raise ValueError("Partial restore must identify exactly one metadata directory")
    root = Path(roots[0])
    stage = Path(CONFIG.get("NETBACKUP_STAGING_ROOT", "/tmp/mold/netbackup")).resolve()
    expected = None
    source_root = root
    if not root.is_dir() or stage not in root.parents:
        expected = resolve_restore_metadata(job)
    elif (root / "backup-manifest.json").is_file():
        candidate = json.loads(read_restored_metadata(root / "backup-manifest.json", started_at))
        if any(Path(v.get("chain", [{}])[-1].get("path", "")).parent != root for v in candidate.get("volumes", [])):
            expected = resolve_restore_metadata(job)
    if expected is not None:
        source_root = Path(expected["metadatapath"])
        destinations = {Path(p) for p in (expected.get("metadatarestorepath"), expected.get("metadatapath")) if p}
        # File lists may describe the original selection rather than its alternate destination.
        if root not in destinations:
            raise ValueError("Restore selection differs from the exact Mold metadata artifact")
        available = [p for p in destinations if (p / "backup-manifest.json").is_file()
                     and (p / "backup-manifest.json").lstat().st_ctime >= started_at - 5]
        if len(available) != 1:
            raise ValueError("Restored metadata destination is missing or ambiguous")
        root = available[0]
    if root.resolve() != root or not root.is_dir() or (expected is None and stage not in root.parents):
        raise ValueError("Partial restore metadata directory is unavailable or redirects outside staging")
    required = [root / name for name in ("backup-manifest.json", "domain-config.xml", ".staging.complete")]
    normalized = {str(Path(value)) for value in selections if value.startswith("/")}
    selected_root = Path(roots[0])
    if str(selected_root) not in normalized and not all(str(selected_root / path.name) in normalized for path in required):
        raise ValueError("NetBackup file selection does not include all required restore metadata")
    texts = [read_restored_metadata(path, started_at) for path in required]
    manifest = json.loads(texts[0])
    if (manifest.get("version") != 1 or manifest.get("provider") != "ablestack-netbackup"
            or manifest.get("complete") is not True or not manifest.get("backupUuid")
            or manifest.get("vmName") != root.parent.name or manifest.get("timestamp") != root.name):
        raise ValueError("Restored manifest is incomplete or belongs to a different backup")
    volumes = manifest.get("volumes")
    if not isinstance(volumes, list) or not volumes:
        raise ValueError("Restored manifest has no volume restore plan")
    ids = set()
    for volume in volumes:
        if (not isinstance(volume, dict) or not volume.get("uuid") or volume["uuid"] in ids
                or volume.get("engine") not in {"QCOW2", "RBD_DIFF", "OFFLINE_FILE"} or not volume.get("chain")):
            raise ValueError("Restored manifest contains an invalid volume restore plan")
        ids.add(volume["uuid"])
        artifact = volume["chain"][-1]
        if (artifact.get("backupUuid") != manifest["backupUuid"] or Path(artifact.get("path", "")).parent != source_root
                or artifact.get("completed") is not True or not artifact.get("externalId") or not artifact.get("jobId")):
            raise ValueError("Restored manifest has an unconfirmed or mismatched volume artifact")
    if any(volume.get("engine") == "OFFLINE_FILE" for volume in volumes):
        guard = manifest.get("offlineGuard") or {}
        if (manifest.get("backupType") != "FULL" or manifest.get("parentBackupUuid")
                or guard.get("version") != 1 or guard.get("operation") != "BACKUP"
                or guard.get("jobId") != manifest["backupUuid"] or not guard.get("vmUuid")
                or any(len(volume["chain"]) != 1 for volume in volumes)):
            raise ValueError("Offline RAW metadata is not a protected independent Full")
    domain = ET.fromstring(texts[1])
    if domain.tag != "domain" or domain.findtext("name") != manifest["vmName"] or not domain.findtext("uuid"):
        raise ValueError("Restored domain XML is invalid or belongs to another VM")
    if manifest.get("offlineGuard") and domain.findtext("uuid") != manifest["offlineGuard"].get("vmUuid"):
        raise ValueError("Offline VM descriptor differs from the recorded Mold identity")
    if expected is not None and (manifest["backupUuid"] != expected.get("id")
            or manifest["vmName"] != expected.get("vmname") or manifest["timestamp"] != expected.get("timestamp")
            or domain.findtext("uuid") != expected.get("virtualmachineid")):
        raise ValueError("Restored metadata differs from the exact Mold backup")
    marker = dict(line.split("=", 1) for line in texts[2].splitlines() if "=" in line)
    if marker.get("volume_pipeline") != "true" or marker.get("backup_uuid") != manifest["backupUuid"]:
        raise ValueError("Restored completion marker does not match the manifest")
    # This UI Job restores metadata only. Mold downloads and verifies every volume
    # itself; requiring image payloads here would reject valid per-volume backups.
    result = {"root": str(root), "backupUuid": manifest["backupUuid"], "requiredFiles": [str(path) for path in required]}
    if expected is not None:
        result["externalId"] = str(source_root)
    return result


def resolve_restore_metadata(job: dict[str, Any]) -> dict[str, Any]:
    identifiers = candidate_restore_identifiers(job, job_id(job))
    if len(identifiers) != 1:
        raise ValueError("Relocated metadata requires one exact catalog selection")
    env = os.environ.copy()
    if CONFIG.get("MOLD_CONFIG_FILE"):
        env["MOLD_CONFIG_FILE"] = CONFIG["MOLD_CONFIG_FILE"]
    proc = subprocess.run([require_config("RESTORE_NOTIFY_SCRIPT"), "netbackup-host-restore-watcher",
                           identifiers[0], "resolve", job_id(job)], capture_output=True, text=True, env=env, timeout=60)
    if proc.returncode:
        raise ValueError("Unable to resolve the exact Mold metadata destination")
    result = json.loads(proc.stdout)["resolvebackupartifactresponse"]["backupartifact"]
    if (result.get("matched") is not True or result.get("tracked") is not False
            or result.get("restoreallowed") is not True or result.get("artifacttype") != "METADATA"
            or result.get("provider") != "ablestack-netbackup" or not result.get("metadatapath")):
        raise ValueError("Mold did not authorize this metadata artifact")
    return result


def extract_staging_paths_from_strings(values: list[str]) -> list[str]:
    staging_root = CONFIG.get("NETBACKUP_STAGING_ROOT", "/tmp/mold/netbackup").rstrip("/")
    paths = []
    payloads = []
    metadata_names = {"backup-manifest.json", "domain-config.xml", "rbd-backup.meta", ".staging.complete",
                      "dominfo.xml", "domiflist.xml", "domblklist.xml", ".volume-bootstrap"}
    for value in values:
        normalized = value.rstrip("/")
        provider_marker = "/ablestack-netbackup/"
        if normalized.startswith(staging_root + "/") or (normalized.startswith("/") and provider_marker in normalized):
            prefix = staging_root if normalized.startswith(staging_root + "/") else normalized.split(provider_marker, 1)[0] + "/ablestack-netbackup"
            suffix = normalized[len(prefix) + 1:]
            parts = [part for part in suffix.split("/") if part]
            if len(parts) >= 2:
                root = f"{prefix}/{parts[0]}/{parts[1]}"
                if not parts[0].startswith("i-") or not parts[0].endswith("-VM") or parts[1] in {".", ".."}:
                    continue
                if len(parts) == 3 and parts[2] not in metadata_names:
                    # Do not collapse a volume child selection into its parent's
                    # metadata directory and accidentally trigger a VM restore.
                    payloads.append(normalized)
                else:
                    paths.append(root)
    return sorted(set(payloads or paths), key=len, reverse=True)


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
    # Catalog image IDs identify individual Full child jobs even when the UI
    # restores them to an alternate destination outside the original staging path.
    for node in (job, job.get("attributes", {})):
        if not isinstance(node, dict):
            continue
        value = node.get("restoreBackupIDs", node.get("restoreBackupIds"))
        ids = value if isinstance(value, list) else str(value or "").splitlines()
        ids = sorted(set(str(item).strip() for item in ids if str(item).strip()))
        if ids and all(re.fullmatch(r"[^\s/\\]+_[0-9]+", item) for item in ids):
            return ids
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


def invoke_restore_notify(external_id: str, job_id_value: str, job: Optional[dict[str, Any]] = None, operation: str = "restore") -> Optional[str]:
    if operation not in {"restore", "cleanup"}:
        raise ValueError("Unsupported external restore notification operation")
    notify_script = require_config("RESTORE_NOTIFY_SCRIPT")
    env = os.environ.copy()
    if CONFIG.get("MOLD_CONFIG_FILE"):
        env["MOLD_CONFIG_FILE"] = CONFIG["MOLD_CONFIG_FILE"]
    if CONFIG.get("MOLD_RESTORE_API_METHOD"):
        env["MOLD_RESTORE_API_METHOD"] = CONFIG["MOLD_RESTORE_API_METHOD"]
    # Only a confirmed external Job may authorize cleanup of its original metadata.
    job = job if job is not None else fetch_restore_job(job_id_value)
    if operation == "cleanup" and (job_id(job) != job_id_value or restore_job_outcome(job) != "SUCCESS"
                                   or get_nested(job, "attributes.jobType", "jobType").upper() != "RESTORE"
                                   or not is_job_for_local_client(job)):
        raise ValueError("Metadata cleanup requires the exact successful external restore Job")
    started = parse_timestamp(get_nested(job, "attributes.startTime", "startTime", "attributes.timestamps.startTime"))
    ended = parse_timestamp(get_nested(job, "attributes.endTime", "endTime", "attributes.timestamps.endTime"))
    if started and ended and ended >= started:
        env["MOLD_EXTERNAL_RESTORE_PROOF"] = json.dumps({
            "hostName": CONFIG.get("MOLD_HOST_NAME") or socket.gethostname().split(".")[0],
            "startedAt": int(started * 1000), "endedAt": int(ended * 1000)})
    else:
        env.pop("MOLD_EXTERNAL_RESTORE_PROOF", None)
    proc = subprocess.run(
        [notify_script, "netbackup-host-restore-watcher", external_id, operation, job_id_value],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        check=False,
    )
    if proc.returncode != 0:
        log(f"FAILED notify jobId=[{job_id_value}] externalId=[{external_id}] rc=[{proc.returncode}] stderr=[{proc.stderr.strip()}]")
        return None
    notify_output = proc.stdout.strip()
    if notify_output.startswith("SUBMITTED"):
        log(f"Submitted Mold restore from NetBackup watcher jobId=[{job_id_value}] externalId=[{external_id}] result=[{notify_output}]")
        return "submitted"
    elif notify_output.startswith("SKIPPED"):
        log(f"Skipped Mold restore from NetBackup watcher jobId=[{job_id_value}] externalId=[{external_id}] result=[{notify_output}]")
        return "skipped"
    else:
        log(f"Handled NetBackup restore watcher notification jobId=[{job_id_value}] externalId=[{external_id}] stdout=[{notify_output}]")
        return "handled"


def fetch_restore_jobs() -> list[dict[str, Any]]:
    params = {}
    job_filter = CONFIG.get("NETBACKUP_JOBS_FILTER", "")
    if job_filter:
        params["filter"] = job_filter
    page_limit = CONFIG.get("NETBACKUP_JOBS_LIMIT", "")
    if page_limit:
        params["page[limit]"] = page_limit

    payload = netbackup_get(CONFIG.get("NETBACKUP_JOBS_PATH", "/admin/jobs"), params)
    listing = payload
    if isinstance(payload, dict):
        listing = next((payload[key] for key in ("data", "jobs", "job", "items") if key in payload), None)
    if not isinstance(listing, (list, dict)):
        raise ValueError("NetBackup Jobs API returned no confirmed job listing")
    entries = [listing] if isinstance(listing, dict) else listing
    if any(not isinstance(job, dict) or not job_id(job) for job in entries):
        raise ValueError("NetBackup Jobs API returned an invalid job listing")
    return entries


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
        if restore_job_outcome(job) not in {"SUCCESS", "PARTIAL", "FAILED"} and job_state(job) not in {
                "done", "completed", "succeeded", "successful", "success"}:
            continue
        # Only Job IDs are needed to retire historical sessions. Fetching every
        # old Job's file list could exhaust the oneshot timeout before baselining.
        processed[current_job_id] = {
            "skipped": "initialJobBaseline",
            "permanent": True,
            "timestamp": int(time.time()),
        }
        marked_count += 1
    state["initialJobBaselineCompleted"] = True
    log(f"Marked existing NetBackup restore jobs as processed on watcher startup count=[{marked_count}]")


def poll_once(state: dict[str, Any]) -> None:
    jobs = fetch_restore_jobs()
    processed = state.setdefault("processed", {})
    validations = state.setdefault("restoreValidation", {})
    for job in jobs:
        current_job_id = job_id(job)
        if not current_job_id or current_job_id in processed:
            continue
        job_type = get_nested(job, "attributes.jobType", "jobType").upper()
        if job_type and job_type != "RESTORE":
            continue
        outcome = restore_job_outcome(job)
        if outcome in {"UNKNOWN", "PARTIAL"}:
            try:
                job = fetch_restore_job(current_job_id)
                outcome = restore_job_outcome(job)
            except Exception as exc:
                validations[current_job_id] = {"status": "WAITING_JOB_DETAILS", "reason": str(exc), "timestamp": int(time.time())}
                log(f"WAITING restore Job details jobId=[{current_job_id}] error=[{exc}]")
                continue
        job_type = get_nested(job, "attributes.jobType", "jobType").upper()
        if job_type and job_type != "RESTORE":
            continue
        if outcome in {"PENDING", "UNKNOWN"}:
            if outcome == "UNKNOWN":
                validations[current_job_id] = {"status": "UNKNOWN_JOB_STATUS", "reason": "No confirmed terminal state and exit code",
                                               "timestamp": int(time.time())}
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
        status_code = job_status_code(job)
        log(f"NetBackup restore completed jobId=[{current_job_id}] state=[{job_state(job)}] statusCode=[{status_code}] outcome=[{outcome}]")
        if outcome == "FAILED":
            try:
                details = fetch_restore_job_details(current_job_id)
            except Exception as exc:
                details = [str(exc)]
            processed[current_job_id] = {"status": "failed", "netbackupState": job_state(job), "netbackupStatusCode": status_code,
                                         "details": details[-50:], "timestamp": int(time.time())}
            log(f"FAILED NetBackup restore jobId=[{current_job_id}] statusCode=[{status_code}] details={json.dumps(details[-50:])}")
            continue
        validation = None
        if outcome == "PARTIAL":
            details = []
            try:
                details = fetch_restore_job_details(current_job_id)
                review_partial_restore_errors(details)
                selections = fetch_restore_job_file_list(current_job_id)
                validation = verify_partial_restore_metadata(job, selections)
                validation.update(status="VERIFIED", netbackupStatusCode="1", details=details[-50:], timestamp=int(time.time()))
                validations[current_job_id] = validation
                log(f"VERIFIED partial NetBackup restore jobId=[{current_job_id}] validation={json.dumps(validation, sort_keys=True)}")
            except Exception as exc:
                validations[current_job_id] = {"status": "PARTIAL_RESTORE_BLOCKED", "netbackupStatusCode": "1", "reason": str(exc),
                                               "details": details[-50:], "timestamp": int(time.time())}
                log(f"BLOCKED partial NetBackup restore jobId=[{current_job_id}] reason=[{exc}] details={json.dumps(details[-50:])}")
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
        # A partial Job may submit only the exact metadata directory verified above,
        # never an unverified catalog ID or a stale directory discovered by proximity.
        restore_identifier = validation.get("externalId", validation["root"]) if validation else local_identifiers[0]
        notification_status = invoke_restore_notify(restore_identifier, current_job_id, job)
        if notification_status:
            processed[current_job_id] = {
                "externalId": restore_identifier,
                "status": notification_status,
                "netbackupStatusCode": status_code,
                "validation": validation,
                "timestamp": int(time.time()),
            }
            validations.pop(current_job_id, None)
            # Persist each accepted notification before looking at another Job.
            # A later request may hang until the oneshot service timeout.
            save_state(state)


def acquire_process_lock() -> Optional[Any]:
    lock_file = Path(CONFIG.get("LOCK_FILE", str(DEFAULT_LOCK_FILE)))
    lock_file.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_file.open("a+", encoding="utf-8")
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        handle.close()
        log(f"Skipping NetBackup restore watcher because another process owns lock=[{lock_file}]")
        return None
    return handle


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Check completed NetBackup UI restores once, then exit.")
    parser.add_argument("--once", action="store_true", help="Run one polling pass (also the default).")
    parser.parse_args(argv)
    phase = "initialization"
    try:
        lock_handle = acquire_process_lock()
        if lock_handle is None:
            return 0
        with lock_handle:
            log(f"Starting NetBackup restore watcher config=[{CONFIG_FILE}] once=[True] sslVerify=[{CONFIG.get('NETBACKUP_SSL_VERIFY', 'false')}] caFile=[{CONFIG.get('NETBACKUP_CA_FILE', '')}]")
            state = load_state()
            phase = "initial baseline"
            mark_existing_jobs_processed(state)
            # Confirm and persist the initial baseline before any restore request.
            # Subsequent timer executions reuse it instead of baselining new Jobs.
            save_state(state)
            phase = "poll"
            prune_state(state)
            poll_once(state)
            save_state(state)
            log("Completed NetBackup restore watcher polling pass")
    except Exception as exc:
        log(f"FAILED watcher {phase} error=[{exc}]")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
