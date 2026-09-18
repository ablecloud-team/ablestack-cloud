#!/bin/bash

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

set -euo pipefail

STATE_ROOT_DEFAULT="/var/lib/ablestack/netbackup"
LOG_FILE_DEFAULT="/var/log/cloudstack/agent/agent.log"
LIBVIRT_URI_DEFAULT="qemu:///system"
CONFIG_ROOT_DEFAULT="/etc/ablestack/netbackup"
SECRET_HELPER_DEFAULT="/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/netbackup-host-secret-helper.sh"
SECRET_SUBDIR_DEFAULT="secrets"
BACKUP_STAGING_ROOT_DEFAULT="/tmp/mold/netbackup"
NETBACKUP_STAGE_ROOT_CONFIG_NAME="backup.plugin.netbackup.stage.root.path"
BACKUP_DATA_OPERATION_TIMEOUT_CONFIG_NAME="backup.data.operation.timeout"

CONFIG_ROOT="${CONFIG_ROOT:-$CONFIG_ROOT_DEFAULT}"
STATE_ROOT="${STATE_ROOT:-$STATE_ROOT_DEFAULT}"
LOG_FILE="${LOG_FILE:-$LOG_FILE_DEFAULT}"
LIBVIRT_URI="${LIBVIRT_URI:-$LIBVIRT_URI_DEFAULT}"
SECRET_HELPER="${SECRET_HELPER:-$SECRET_HELPER_DEFAULT}"
SECRET_SUBDIR="${SECRET_SUBDIR:-$SECRET_SUBDIR_DEFAULT}"
BACKUP_STAGING_ROOT="${BACKUP_STAGING_ROOT:-$BACKUP_STAGING_ROOT_DEFAULT}"

VM_INCLUDE="${VM_INCLUDE:-*}"
VM_EXCLUDE="${VM_EXCLUDE:-}"
MOLD_URL="${MOLD_URL:-}"
ADMIN_APIKEY="${ADMIN_APIKEY:-}"
ADMIN_SECRETKEY="${ADMIN_SECRETKEY:-}"
MOLD_CREATE_BACKUP_API_URL="${MOLD_CREATE_BACKUP_API_URL:-}"
MOLD_CREATE_BACKUP_API_METHOD="${MOLD_CREATE_BACKUP_API_METHOD:-POST}"
MOLD_LIST_VMS_API_URL="${MOLD_LIST_VMS_API_URL:-}"
MOLD_LIST_VMS_API_METHOD="${MOLD_LIST_VMS_API_METHOD:-GET}"
MOLD_LIST_NETBACKUP_CANDIDATES_API_URL="${MOLD_LIST_NETBACKUP_CANDIDATES_API_URL:-}"
MOLD_LIST_NETBACKUP_CANDIDATES_API_METHOD="${MOLD_LIST_NETBACKUP_CANDIDATES_API_METHOD:-GET}"
MOLD_QUERY_ASYNC_JOB_API_URL="${MOLD_QUERY_ASYNC_JOB_API_URL:-}"
MOLD_API_RESPONSE_FORMAT="${MOLD_API_RESPONSE_FORMAT:-json}"
MOLD_API_SKIP_TLS_VERIFY="${MOLD_API_SKIP_TLS_VERIFY:-false}"
MOLD_ASYNC_JOB_POLL_INTERVAL="${MOLD_ASYNC_JOB_POLL_INTERVAL:-5}"
MOLD_ASYNC_JOB_TIMEOUT="${MOLD_ASYNC_JOB_TIMEOUT:-7200}"
NETBACKUP_STAGING_POLL_INTERVAL="${NETBACKUP_STAGING_POLL_INTERVAL:-${MOLD_ASYNC_JOB_POLL_INTERVAL}}"
NETBACKUP_STAGING_TIMEOUT="${NETBACKUP_STAGING_TIMEOUT:-43800}"
NETBACKUP_TRANSIENT_STATE_RETENTION_MINUTES="${NETBACKUP_TRANSIENT_STATE_RETENTION_MINUTES:-1440}"
NETBACKUP_RUNTIME_MAX_FILES="${NETBACKUP_RUNTIME_MAX_FILES:-14}"

verb="${verb:-0}"
POLICY_NAME="${POLICY_NAME:-}"
SCHEDULE_NAME="${SCHEDULE_NAME:-}"
CLIENT_NAME="${CLIENT_NAME:-}"
SESSION_TIMESTAMP="${SESSION_TIMESTAMP:-}"
JOB_STATUS="${JOB_STATUS:-}"
NETBACKUP_REQUIRE_JOB_SUCCESS="${NETBACKUP_REQUIRE_JOB_SUCCESS:-true}"
NETBACKUP_COMMIT_ON_CLIENT_STATUS_ZERO="${NETBACKUP_COMMIT_ON_CLIENT_STATUS_ZERO:-false}"
NETBACKUP_JOB_ID="${NETBACKUP_JOB_ID:-${NB_JOBID:-${JOB_ID:-}}}"
NETBACKUP_SUCCESS_CONFIRM_CMD="${NETBACKUP_SUCCESS_CONFIRM_CMD:-}"
MOLD_VM_CACHE_FILE=""
BACKUP_ID="${BACKUP_ID:-${BACKUPID:-}}"
BACKUP_TIME="${BACKUP_TIME:-${BACKUPTIME:-}}"
UNIX_BACKUP_TIME="${UNIX_BACKUP_TIME:-${UNIXBACKUPTIME:-}}"
RUNTIME_FILE=""
LAST_MOLD_JOB_ID=""
LAST_MOLD_FINAL_RESPONSE=""

if [[ "${NETBACKUP_JOB_ID}" == "0" ]]; then
  NETBACKUP_JOB_ID=""
fi

log() {
  local ts
  ts="$(date '+%Y-%m-%d %H-%M-%S>')"
  [[ "${verb}" -eq 1 ]] && builtin echo "$@"
  if [[ "${1:-}" == "-ne" || "${1:-}" == "-e" || "${1:-}" == "-n" ]]; then
    builtin echo -e "${ts}" "${@:2}" >> "${LOG_FILE}"
  else
    builtin echo "${ts}" "$@" >> "${LOG_FILE}"
  fi
}

fail() {
  builtin echo "$*" >&2
  log -ne "$*"
  exit 1
}

ensure_runtime_dirs() {
  mkdir -p "${STATE_ROOT}/contexts" \
           "${STATE_ROOT}/locks" \
           "${STATE_ROOT}/sessions" \
           "${STATE_ROOT}/runtime"
}

cleanup_stale_transient_state() {
  local retention_minutes="${NETBACKUP_TRANSIENT_STATE_RETENTION_MINUTES}"
  local cleaned=0
  local file=""

  [[ "${retention_minutes}" =~ ^[1-9][0-9]*$ ]] || return 0

  while IFS= read -r file; do
    [[ -n "${file}" ]] || continue
    rm -f "${file}"
    cleaned=$((cleaned + 1))
    log -ne "Removed stale NetBackup transient state file ${file}"
  done < <(find "${STATE_ROOT}/contexts" "${STATE_ROOT}/sessions" -type f -mmin "+${retention_minutes}" 2>/dev/null)

  if (( cleaned > 0 )); then
    log -ne "Cleaned stale NetBackup transient state files count=${cleaned} retentionMinutes=${retention_minutes}"
  fi
}

cleanup_runtime_history() {
  local max_files="${NETBACKUP_RUNTIME_MAX_FILES}"
  local cleaned=0
  local runtime_dir=""
  local file=""
  local -a runtime_files=()

  [[ "${max_files}" =~ ^[1-9][0-9]*$ ]] || return 0
  [[ -n "${POLICY_SAFE:-}" ]] || return 0

  runtime_dir="${STATE_ROOT}/runtime/${POLICY_SAFE}"
  [[ -d "${runtime_dir}" ]] || return 0
  mapfile -d '' -t runtime_files < <(find "${runtime_dir}" -maxdepth 1 -type f -name '*.json' -print0 2>/dev/null)
  (( ${#runtime_files[@]} > max_files )) || return 0

  while IFS= read -r file; do
    [[ -n "${file}" ]] || continue
    rm -f "${file}"
    cleaned=$((cleaned + 1))
    log -ne "Removed old NetBackup runtime file ${file}"
  done < <(ls -1t "${runtime_files[@]}" 2>/dev/null | awk -v keep="${max_files}" 'NR > keep')

  if (( cleaned > 0 )); then
    log -ne "Cleaned old NetBackup runtime files count=${cleaned} policy=${POLICY_NAME:-${POLICY_SAFE}} keep=${max_files}"
  fi
}

sanitize_name() {
  local value="$1"
  value="${value//[^A-Za-z0-9._-]/_}"
  builtin echo "${value}"
}

generate_timestamp() {
  date '+%Y.%m.%d.%H.%M.%S.%3N'
}

write_state_file() {
  local path="$1"
  shift
  : > "${path}"
  while [[ $# -gt 1 ]]; do
    local key="$1"
    local value="$2"
    shift 2
    printf '%s=%q\n' "${key}" "${value}" >> "${path}"
  done
}

load_state_file() {
  local path="$1"
  [[ -f "${path}" ]] || return 1
  # shellcheck disable=SC1090
  source "${path}"
}

resolve_context() {
  local arg_policy="${1:-}"
  local arg_schedule="${2:-}"
  local arg_client="${3:-}"
  local arg_timestamp="${4:-}"

  if [[ -n "${arg_policy}" && -z "${POLICY_NAME}" ]]; then
    POLICY_NAME="${arg_policy}"
  fi
  if [[ -n "${arg_schedule}" && -z "${SCHEDULE_NAME}" ]]; then
    SCHEDULE_NAME="${arg_schedule}"
  fi
  if [[ -n "${arg_client}" && -z "${CLIENT_NAME}" ]]; then
    CLIENT_NAME="${arg_client}"
  fi
  if [[ -n "${arg_timestamp}" && -z "${SESSION_TIMESTAMP}" ]]; then
    SESSION_TIMESTAMP="${arg_timestamp}"
  fi

  POLICY_NAME="${POLICY_NAME:-${NB_ORA_POLICY:-manual}}"
  SCHEDULE_NAME="${SCHEDULE_NAME:-${NB_ORA_SCHEDULE:-manual}}"
  CLIENT_NAME="${CLIENT_NAME:-$(hostname -s)}"
  SESSION_TIMESTAMP="${SESSION_TIMESTAMP:-$(generate_timestamp)}"
  BACKUP_ID="${BACKUP_ID:-${UNIX_BACKUP_TIME:-${SESSION_TIMESTAMP}}}"

  POLICY_SAFE="$(sanitize_name "${POLICY_NAME}")"
  SCHEDULE_SAFE="$(sanitize_name "${SCHEDULE_NAME}")"
  CLIENT_SAFE="$(sanitize_name "${CLIENT_NAME}")"
  BACKUP_ID_SAFE="$(sanitize_name "${BACKUP_ID}")"
  CONTEXT_KEY="${POLICY_SAFE}__${SCHEDULE_SAFE}__${CLIENT_SAFE}"
  LOCK_FILE="${STATE_ROOT}/locks/${CONTEXT_KEY}.lock"
  CONTEXT_FILE="${STATE_ROOT}/contexts/${CONTEXT_KEY}.env"
  RUNTIME_DIR="${STATE_ROOT}/runtime/${POLICY_SAFE}"
  RUNTIME_FILE="${RUNTIME_DIR}/${BACKUP_ID_SAFE}.json"
}

context_in_progress_file() {
  builtin echo "${STATE_ROOT}/contexts/${CONTEXT_KEY}.inprogress"
}

mark_context_in_progress() {
  local marker
  marker="$(context_in_progress_file)"
  if [[ -e "${marker}" ]]; then
    fail "NetBackup staging context is already initialized for ${CONTEXT_KEY}. Disable multistreaming for this policy/schedule or clean stale state after verification: ${marker}"
  fi
  write_state_file "${marker}" \
    POLICY_NAME "${POLICY_NAME}" \
    SCHEDULE_NAME "${SCHEDULE_NAME}" \
    CLIENT_NAME "${CLIENT_NAME}" \
    SESSION_TIMESTAMP "${SESSION_TIMESTAMP}"
}

clear_context_in_progress() {
  rm -f "$(context_in_progress_file)"
}

init_runtime_state() {
  mkdir -p "${RUNTIME_DIR}"
  python3 - "${RUNTIME_FILE}" "${BACKUP_ID}" "${POLICY_NAME}" "${SCHEDULE_NAME}" "${CLIENT_NAME}" \
    "${SESSION_TIMESTAMP}" "${BACKUP_STAGING_ROOT}" "${BACKUP_TIME}" "${UNIX_BACKUP_TIME}" <<'PY'
import json
import sys

path, backup_id, policy, schedule, client, session_ts, backup_root, backup_time, unix_backup_time = sys.argv[1:10]
payload = {
    "backupid": backup_id,
    "policy": policy,
    "schedule": schedule,
    "client": client,
    "session_timestamp": session_ts,
    "backup_time": backup_time,
    "unix_backup_time": unix_backup_time,
    "status": "INITIALIZING",
    "success_count": 0,
    "failed_count": 0,
    "backup_root": backup_root,
    "vm_results": [],
}
with open(path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
PY
}

append_runtime_vm_result() {
  local vm_name="$1"
  local status="$2"
  local vm_id="${3:-}"
  local job_id="${4:-}"
  local backup_path="${5:-}"
  local error_text="${6:-}"

  python3 - "${RUNTIME_FILE}" "${vm_name}" "${status}" "${vm_id}" "${job_id}" "${backup_path}" "${error_text}" <<'PY'
import json
import sys

path, vm_name, status, vm_id, job_id, backup_path, error_text = sys.argv[1:8]
with open(path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)

item = {
    "vm": vm_name,
    "status": status,
}
if vm_id:
    item["vm_id"] = vm_id
if job_id:
    item["jobid"] = job_id
if backup_path:
    item["backup_path"] = backup_path
if error_text:
    item["error"] = error_text

payload.setdefault("vm_results", []).append(item)
if status == "SUCCESS":
    payload["success_count"] = int(payload.get("success_count", 0)) + 1
else:
    payload["failed_count"] = int(payload.get("failed_count", 0)) + 1

with open(path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
PY
}

update_runtime_status() {
  local status="$1"
  python3 - "${RUNTIME_FILE}" "${status}" <<'PY'
import json
import sys

path, status = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
payload["status"] = status
with open(path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
PY
}

read_runtime_count() {
  local key="$1"
  python3 - "${RUNTIME_FILE}" "${key}" <<'PY'
import json
import sys

path, key = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
print(int(payload.get(key, 0)), end="")
PY
}

list_runtime_success_paths() {
  python3 - "${RUNTIME_FILE}" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
for item in payload.get("vm_results", []):
    if item.get("status") == "SUCCESS" and item.get("backup_path"):
        print(item["backup_path"])
PY
}

list_runtime_success_vm_refs() {
  python3 - "${RUNTIME_FILE}" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
for item in payload.get("vm_results", []):
    if item.get("status") == "SUCCESS" and item.get("vm_id") and item.get("backup_path"):
        vm_id = item.get("vm_id", "")
        backup_path = item.get("backup_path", "")
        print(f"{vm_id}\t{backup_path}")
PY
}

runtime_has_successful_vm_results() {
  local success_count=0
  [[ -n "${RUNTIME_FILE}" && -f "${RUNTIME_FILE}" ]] || return 1
  success_count="$(read_runtime_count "success_count" 2>/dev/null || echo 0)"
  [[ "${success_count}" =~ ^[0-9]+$ ]] || success_count=0
  (( success_count > 0 ))
}

discover_vm_backup_path() {
  local vm_name="$1"
  local vm_root="${BACKUP_STAGING_ROOT}/${vm_name}"
  [[ -d "${vm_root}" ]] || return 1

  python3 - "${vm_root}" <<'PY'
import os
import sys

root = sys.argv[1]
candidates = []
for entry in os.scandir(root):
    candidates.append((entry.stat().st_mtime, entry.path))

if not candidates:
    sys.exit(1)

candidates.sort(key=lambda item: item[0], reverse=True)
print(candidates[0][1], end="")
PY
}

discover_completed_vm_backup_path() {
  local vm_name="$1"
  local min_epoch="${2:-0}"
  local vm_root="${BACKUP_STAGING_ROOT}/${vm_name}"
  [[ -d "${vm_root}" ]] || return 1

  python3 - "${vm_root}" "${min_epoch}" <<'PY'
import os
import sys

root = sys.argv[1]
try:
    min_epoch = int(sys.argv[2])
except Exception:
    min_epoch = 0

candidates = []
threshold = max(0, min_epoch - 5)
for entry in os.scandir(root):
    if not entry.is_dir(follow_symlinks=False):
        continue
    complete_marker = os.path.join(entry.path, ".staging.complete")
    inprogress_marker = os.path.join(entry.path, ".staging.inprogress")
    if not os.path.isfile(complete_marker):
        continue
    try:
        marker_mtime = os.stat(complete_marker).st_mtime
        dir_mtime = entry.stat().st_mtime
    except OSError:
        continue
    if marker_mtime < threshold and dir_mtime < threshold:
        continue
    if os.path.exists(inprogress_marker):
        continue
    candidates.append((marker_mtime, dir_mtime, entry.path))

if not candidates:
    sys.exit(1)

candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
print(candidates[0][2], end="")
PY
}

wait_for_vm_staging_complete() {
  local vm_name="$1"
  local stage_start_epoch="$2"
  local wait_start_epoch
  local last_log_epoch
  local backup_path=""

  wait_start_epoch="$(date +%s)"
  last_log_epoch=0

  while true; do
    backup_path="$(discover_completed_vm_backup_path "${vm_name}" "${stage_start_epoch}" 2>/dev/null || true)"
    if [[ -n "${backup_path}" ]]; then
      printf '%s' "${backup_path}"
      return 0
    fi

    local now_epoch
    now_epoch="$(date +%s)"
    if (( now_epoch - wait_start_epoch >= NETBACKUP_STAGING_TIMEOUT )); then
      fail "Timed out waiting for NetBackup staging complete marker for vm=${vm_name} under ${BACKUP_STAGING_ROOT}/${vm_name} after ${NETBACKUP_STAGING_TIMEOUT}s"
    fi

    if (( last_log_epoch == 0 || now_epoch - last_log_epoch >= 60 )); then
      log -ne "Waiting for NetBackup staging complete marker vm=${vm_name} root=${BACKUP_STAGING_ROOT}/${vm_name} elapsedSeconds=$((now_epoch - wait_start_epoch))"
      last_log_epoch="${now_epoch}"
    fi

    sleep "${NETBACKUP_STAGING_POLL_INTERVAL}"
  done
}

policy_config_file_path() {
  builtin echo "${CONFIG_ROOT}/netbackup-host-${POLICY_SAFE}.conf"
}

resolve_config_file_path() {
  local policy_config_file

  policy_config_file="$(policy_config_file_path)"

  builtin echo "${policy_config_file}"
}

resolve_secret_file_path() {
  builtin echo "${CONFIG_ROOT}/${SECRET_SUBDIR}/secret.enc"
}

load_admin_secretkey() {
  local secret_file
  secret_file="$(resolve_secret_file_path)"

  if [[ -n "${ADMIN_SECRETKEY}" ]]; then
    return 0
  fi
  if [[ ! -f "${secret_file}" ]]; then
    fail "ADMIN_SECRETKEY is not set and encrypted secret file was not found: ${secret_file}"
  fi
  if [[ ! -x "${SECRET_HELPER}" ]]; then
    fail "Secret helper not found or not executable: ${SECRET_HELPER}"
  fi

  ADMIN_SECRETKEY="$("${SECRET_HELPER}" decrypt "${secret_file}")" || fail "Failed to decrypt NetBackup secret file ${secret_file}"
  [[ -n "${ADMIN_SECRETKEY}" ]] || fail "Decrypted ADMIN_SECRETKEY is empty."
}

load_backup_staging_root_from_mold() {
  local response
  local configured_root

  response="$(invoke_mold_api \
    "${MOLD_LIST_VMS_API_METHOD}" \
    "${MOLD_LIST_VMS_API_URL}" \
    "listConfigurations" \
    "name" "${NETBACKUP_STAGE_ROOT_CONFIG_NAME}")" || \
    fail "Failed to query Mold global configuration ${NETBACKUP_STAGE_ROOT_CONFIG_NAME}"

  configured_root="$(extract_json_value_by_key "${response}" "value" || true)"
  if [[ -z "${configured_root}" ]]; then
    log -ne "Mold global configuration ${NETBACKUP_STAGE_ROOT_CONFIG_NAME} is blank; using default ${BACKUP_STAGING_ROOT_DEFAULT}"
    BACKUP_STAGING_ROOT="${BACKUP_STAGING_ROOT_DEFAULT}"
    return 0
  fi
  if [[ "${configured_root}" != /* ]]; then
    fail "Invalid ${NETBACKUP_STAGE_ROOT_CONFIG_NAME}=${configured_root}. It must be an absolute path."
  fi

  BACKUP_STAGING_ROOT="${configured_root}"
  log -ne "Loaded ${NETBACKUP_STAGE_ROOT_CONFIG_NAME}=${BACKUP_STAGING_ROOT}"
}

load_staging_timeout_from_data_operation_config() {
  local response
  local configured_timeout

  response="$(invoke_mold_api \
    "${MOLD_LIST_VMS_API_METHOD}" \
    "${MOLD_LIST_VMS_API_URL}" \
    "listConfigurations" \
    "name" "${BACKUP_DATA_OPERATION_TIMEOUT_CONFIG_NAME}")" || \
    fail "Failed to query Mold global configuration ${BACKUP_DATA_OPERATION_TIMEOUT_CONFIG_NAME}"

  configured_timeout="$(extract_json_value_by_key "${response}" "value" || true)"
  if [[ -z "${configured_timeout}" ]]; then
    log -ne "Mold global configuration ${BACKUP_DATA_OPERATION_TIMEOUT_CONFIG_NAME} is blank; using staging timeout ${NETBACKUP_STAGING_TIMEOUT}s"
    return 0
  fi
  if [[ ! "${configured_timeout}" =~ ^[1-9][0-9]*$ ]]; then
    fail "Invalid ${BACKUP_DATA_OPERATION_TIMEOUT_CONFIG_NAME}=${configured_timeout}. It must be a positive integer."
  fi

  NETBACKUP_STAGING_TIMEOUT="$((configured_timeout + 600))"
  log -ne "Loaded ${BACKUP_DATA_OPERATION_TIMEOUT_CONFIG_NAME}=${configured_timeout}; stagingTimeout=${NETBACKUP_STAGING_TIMEOUT}"
}

load_policy_schedule_config() {
  local config_file
  config_file="$(resolve_config_file_path)"

  VM_INCLUDE="*"
  VM_EXCLUDE=""
  MOLD_URL=""
  ADMIN_APIKEY=""

  if [[ -f "${config_file}" ]]; then
    # shellcheck disable=SC1090
    source "${config_file}"
    log -ne "Loaded NetBackup config: ${config_file}"
  else
    log -ne "No NetBackup config found for policy=${POLICY_NAME}"
  fi

  VM_INCLUDE="${VM_INCLUDE:-*}"
  VM_EXCLUDE="${VM_EXCLUDE:-}"
  MOLD_CREATE_BACKUP_API_URL="${MOLD_CREATE_BACKUP_API_URL:-${MOLD_URL}}"
  MOLD_LIST_VMS_API_URL="${MOLD_LIST_VMS_API_URL:-${MOLD_URL}}"
  MOLD_LIST_NETBACKUP_CANDIDATES_API_URL="${MOLD_LIST_NETBACKUP_CANDIDATES_API_URL:-${MOLD_LIST_VMS_API_URL}}"
  MOLD_QUERY_ASYNC_JOB_API_URL="${MOLD_QUERY_ASYNC_JOB_API_URL:-${MOLD_URL}}"

  [[ -n "${MOLD_CREATE_BACKUP_API_URL}" ]] || fail "MOLD_URL or MOLD_CREATE_BACKUP_API_URL must be configured."
  [[ -n "${MOLD_LIST_VMS_API_URL}" ]] || fail "MOLD_URL or MOLD_LIST_VMS_API_URL must be configured."
  [[ -n "${MOLD_LIST_NETBACKUP_CANDIDATES_API_URL}" ]] || fail "MOLD_URL or MOLD_LIST_NETBACKUP_CANDIDATES_API_URL must be configured."
  [[ -n "${MOLD_QUERY_ASYNC_JOB_API_URL}" ]] || fail "MOLD_URL or MOLD_QUERY_ASYNC_JOB_API_URL must be configured."
  [[ -n "${ADMIN_APIKEY}" ]] || fail "ADMIN_APIKEY must be configured."
  [[ "${MOLD_ASYNC_JOB_POLL_INTERVAL}" =~ ^[1-9][0-9]*$ ]] || fail "Invalid MOLD_ASYNC_JOB_POLL_INTERVAL=${MOLD_ASYNC_JOB_POLL_INTERVAL}. It must be a positive integer."
  [[ "${MOLD_ASYNC_JOB_TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || fail "Invalid MOLD_ASYNC_JOB_TIMEOUT=${MOLD_ASYNC_JOB_TIMEOUT}. It must be a positive integer."
  [[ "${NETBACKUP_STAGING_POLL_INTERVAL}" =~ ^[1-9][0-9]*$ ]] || fail "Invalid NETBACKUP_STAGING_POLL_INTERVAL=${NETBACKUP_STAGING_POLL_INTERVAL}. It must be a positive integer."
  [[ "${NETBACKUP_STAGING_TIMEOUT}" =~ ^[1-9][0-9]*$ ]] || fail "Invalid NETBACKUP_STAGING_TIMEOUT=${NETBACKUP_STAGING_TIMEOUT}. It must be a positive integer."

  load_admin_secretkey
  load_backup_staging_root_from_mold
  load_staging_timeout_from_data_operation_config

  log -ne "VM selection include=${VM_INCLUDE} exclude=${VM_EXCLUDE}"
}

match_csv_glob() {
  local value="$1"
  local patterns="$2"
  local pattern

  [[ -z "${patterns}" ]] && return 1
  while IFS= read -r pattern; do
    pattern="${pattern#"${pattern%%[![:space:]]*}"}"
    pattern="${pattern%"${pattern##*[![:space:]]}"}"
    [[ -z "${pattern}" ]] && continue
    if [[ "${value}" == ${pattern} ]]; then
      return 0
    fi
  done < <(tr ',' '\n' <<< "${patterns}")
  return 1
}

vm_is_selected() {
  local vm_name="$1"
  if match_csv_glob "${vm_name}" "${VM_EXCLUDE}"; then
    return 1
  fi
  if match_csv_glob "${vm_name}" "${VM_INCLUDE}"; then
    return 0
  fi
  return 1
}

list_mold_virtual_machine_names() {
  [[ -f "${MOLD_VM_CACHE_FILE}" ]] || fail "Mold VM cache file not found: ${MOLD_VM_CACHE_FILE}"

  python3 - "$MOLD_VM_CACHE_FILE" <<'PY'
import json
import sys

cache_path = sys.argv[1]

with open(cache_path, "r", encoding="utf-8") as fh:
    data = json.load(fh)

names = []
seen = set()

def walk(node):
    if isinstance(node, dict):
        name = node.get("instancename")
        if isinstance(name, str) and name and name not in seen:
            names.append(name)
            seen.add(name)
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for item in node:
            walk(item)

walk(data)

for name in names:
    print(name)
PY
}

list_running_vms() {
  virsh -c "${LIBVIRT_URI}" list --name --state-running | sed '/^$/d'
}

vm_exists_in_mold_cache() {
  local vm_name="$1"
  local vm_id=""

  vm_id="$(lookup_mold_vm_id "${vm_name}" 2>/dev/null || true)"
  [[ -n "${vm_id}" ]]
}

list_target_vms() {
  local vm_name
  while IFS= read -r vm_name; do
    [[ -z "${vm_name}" ]] && continue
    if ! vm_is_selected "${vm_name}"; then
      log -ne "Skipping VM ${vm_name}: not selected by VM_INCLUDE/VM_EXCLUDE"
      continue
    fi

    if vm_exists_in_mold_cache "${vm_name}"; then
      builtin echo "${vm_name}"
    else
      log -ne "Skipping VM ${vm_name}: not found in Mold listVirtualMachines response"
    fi
  done < <(list_running_vms)
}

acquire_lock() {
  exec 9>"${LOCK_FILE}"
  if ! flock -n 9; then
    fail "Another NetBackup staging operation is already running for context ${CONTEXT_KEY}"
  fi
}

sanity_checks() {
  command -v virsh >/dev/null 2>&1 || fail "virsh command not found"
  command -v curl >/dev/null 2>&1 || fail "curl command not found"
  command -v python3 >/dev/null 2>&1 || fail "python3 command not found"
  command -v openssl >/dev/null 2>&1 || fail "openssl command not found"
}

netbackup_job_success_confirmed() {
  [[ "${JOB_STATUS}" == "0" ]] || return 1

  if [[ -n "${NETBACKUP_SUCCESS_CONFIRM_CMD}" ]]; then
    export POLICY_NAME SCHEDULE_NAME CLIENT_NAME SESSION_TIMESTAMP JOB_STATUS NETBACKUP_JOB_ID
    bash -c "${NETBACKUP_SUCCESS_CONFIRM_CMD}" >/dev/null 2>&1 && return 0
    return 1
  fi

  if [[ -n "${NETBACKUP_JOB_ID}" ]] && command -v bpdbjobs >/dev/null 2>&1; then
    local job_status
    job_status=$(bpdbjobs -most_columns -jobid "${NETBACKUP_JOB_ID}" 2>/dev/null | awk -F, 'NR==1 {print $4}')
    [[ "${job_status}" == "0" ]] && return 0
    return 1
  fi

  if runtime_has_successful_vm_results; then
    log -ne "NetBackup client status is 0 and runtime JSON contains successful VM backup results. Proceeding with metadata update and cleanup."
    return 0
  fi

  if [[ "${NETBACKUP_REQUIRE_JOB_SUCCESS}" == "true" ]]; then
    log -ne "NetBackup overall job success was not independently confirmed. Set NETBACKUP_SUCCESS_CONFIRM_CMD, set NETBACKUP_JOB_ID with bpdbjobs access, or set NETBACKUP_COMMIT_ON_CLIENT_STATUS_ZERO=true to allow client-status-only completion."
    return 1
  fi

  [[ "${NETBACKUP_COMMIT_ON_CLIENT_STATUS_ZERO}" == "true" ]]
}

initialize_runtime_cache() {
  MOLD_VM_CACHE_FILE="${STATE_ROOT}/sessions/${CONTEXT_KEY}.listVirtualMachines.json"
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "${value}"
}

tsv_escape() {
  local value="$1"
  value="${value//$'\t'/ }"
  value="${value//$'\n'/ }"
  value="${value//$'\r'/ }"
  printf '%s' "${value}"
}

url_encode() {
  local value="$1"
  python3 - "$value" <<'PY'
import sys
from urllib.parse import quote_plus

print(quote_plus(sys.argv[1]), end="")
PY
}

build_mold_api_params() {
  local command_name="$1"
  shift

  local param_string="command=$(url_encode "${command_name}")"
  while [[ $# -gt 1 ]]; do
    local key="$1"
    local value="$2"
    shift 2
    param_string="${param_string}&${key}=$(url_encode "${value}")"
  done

  param_string="${param_string}&response=$(url_encode "${MOLD_API_RESPONSE_FORMAT}")"
  printf '%s' "${param_string}"
}

sign_mold_request() {
  local request="$1"
  local signature

  signature="$(printf '%s' "${request}" | openssl dgst -sha256 -hmac "${ADMIN_SECRETKEY}" -binary | openssl base64 -A)" || fail "Failed to sign Mold API request"
  url_encode "${signature}"
}

build_mold_signed_url() {
  local base_url="$1"
  local api_params="$2"

  python3 - "$base_url" "$api_params" "$ADMIN_APIKEY" "$ADMIN_SECRETKEY" <<'PY'
import base64
import hashlib
import hmac
import sys
from urllib.parse import quote_plus

base_url = sys.argv[1]
api_params = sys.argv[2]
api_key = sys.argv[3]
secret_key = sys.argv[4]

sorted_params = [f"apikey={quote_plus(api_key).lower()}"]
for token in api_params.split("&"):
    key, value = token.split("=", 1)
    sorted_params.append(f"{key.lower()}={value.lower()}")

sorted_url = "&".join(sorted(sorted_params))
signature = base64.b64encode(
    hmac.new(secret_key.encode(), sorted_url.encode(), hashlib.sha256).digest()
).decode()
encoded_signature = quote_plus(signature)
final_url = f"{base_url}?{api_params}&apiKey={quote_plus(api_key)}&signature={encoded_signature}"
print(final_url, end="")
PY
}

invoke_mold_api() {
  local method="$1"
  local url="$2"
  local command_name="$3"
  shift 3
  local api_params
  local signed_url
  local response=""

  api_params="$(build_mold_api_params "${command_name}" "$@")"
  signed_url="$(build_mold_signed_url "${url}" "${api_params}")" || fail "Failed to build signed Mold API URL for command=${command_name}"

  local curl_args=(
    --silent
    --show-error
    --fail
    -X "${method}"
    -H "Accept: application/json"
    -H "Content-type: application/x-www-form-urlencoded"
  )

  if [[ "${MOLD_API_SKIP_TLS_VERIFY}" == "true" && "${signed_url}" == https://* ]]; then
    curl_args+=(-k)
  fi

  response="$(curl "${curl_args[@]}" "${signed_url}")" || fail "Mold API call failed: method=${method} command=${command_name} url=${url}"
  printf '%s' "${response}"
}

extract_json_value_by_key() {
  local payload="$1"
  local key_name="$2"

  python3 - "$payload" "$key_name" <<'PY'
import json
import sys

payload = sys.argv[1]
target_key = sys.argv[2].lower()

try:
    data = json.loads(payload)
except Exception:
    sys.exit(1)

def walk(node):
    if isinstance(node, dict):
        for key, value in node.items():
            if str(key).lower() == target_key:
                if isinstance(value, (dict, list)):
                    print(json.dumps(value), end="")
                elif value is None:
                    print("", end="")
                else:
                    print(str(value), end="")
                return True
            if walk(value):
                return True
    elif isinstance(node, list):
        for item in node:
            if walk(item):
                return True
    return False

if not walk(data):
    sys.exit(1)
PY
}

wait_for_mold_async_job() {
  local operation_name="$1"
  local job_id="$2"
  local start_time
  start_time="$(date +%s)"

  while true; do
    local response
    response="$(invoke_mold_api "${MOLD_LIST_VMS_API_METHOD}" "${MOLD_QUERY_ASYNC_JOB_API_URL}" "queryAsyncJobResult" "jobid" "${job_id}")" || \
      fail "Failed to query async job status for ${operation_name} jobId=${job_id}"

    local job_status=""
    job_status="$(extract_json_value_by_key "${response}" "jobstatus" 2>/dev/null || true)"

    case "${job_status}" in
      1)
        LAST_MOLD_FINAL_RESPONSE="${response}"
        printf '%s' "${response}"
        return 0
        ;;
      2)
        local error_text=""
        error_text="$(extract_json_value_by_key "${response}" "errortext" 2>/dev/null || true)"
        [[ -n "${error_text}" ]] || error_text="$(extract_json_value_by_key "${response}" "jobresult" 2>/dev/null || true)"
        fail "Mold async job failed for ${operation_name} jobId=${job_id}: ${error_text:-unknown error}"
        ;;
      0|"")
        ;;
      *)
        log -ne "Unexpected Mold async job status for ${operation_name} jobId=${job_id}: ${job_status}"
        ;;
    esac

    if (( $(date +%s) - start_time >= MOLD_ASYNC_JOB_TIMEOUT )); then
      fail "Timed out waiting for Mold async job ${operation_name} jobId=${job_id} after ${MOLD_ASYNC_JOB_TIMEOUT}s"
    fi

    sleep "${MOLD_ASYNC_JOB_POLL_INTERVAL}"
  done
}

cache_mold_virtual_machines() {
  [[ -n "${MOLD_VM_CACHE_FILE}" ]] || fail "MOLD_VM_CACHE_FILE is not initialized."
  if [[ -f "${MOLD_VM_CACHE_FILE}" ]]; then
    return 0
  fi

  log -ne "Calling Mold listNetBackupBackupCandidates API url=${MOLD_LIST_NETBACKUP_CANDIDATES_API_URL} host=${CLIENT_NAME} policy=${POLICY_NAME} claim=true"
  invoke_mold_api \
    "${MOLD_LIST_NETBACKUP_CANDIDATES_API_METHOD}" \
    "${MOLD_LIST_NETBACKUP_CANDIDATES_API_URL}" \
    "listNetBackupBackupCandidates" \
    "hostname" "${CLIENT_NAME}" \
    "policyid" "${POLICY_NAME}" \
    "claim" "true" \
    "pagesize" "500" \
    "page" "1" > "${MOLD_VM_CACHE_FILE}"
}

lookup_mold_vm_id() {
  local vm_name="$1"
  [[ -f "${MOLD_VM_CACHE_FILE}" ]] || fail "Mold VM cache file not found: ${MOLD_VM_CACHE_FILE}"

  python3 - "$MOLD_VM_CACHE_FILE" "$vm_name" <<'PY'
import json
import sys

cache_path = sys.argv[1]
vm_name = sys.argv[2]

with open(cache_path, "r", encoding="utf-8") as fh:
    data = json.load(fh)

matches = []

def walk(node):
    if isinstance(node, dict):
        if str(node.get("instancename", "")) == vm_name and "id" in node:
            matches.append(str(node["id"]))
        elif str(node.get("instancename", "")) == vm_name and "virtualmachineid" in node:
            matches.append(str(node["virtualmachineid"]))
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for item in node:
            walk(item)

walk(data)

if matches:
    print(matches[0], end="")
PY
}

lookup_mold_vm_schedule_id() {
  local vm_name="$1"
  [[ -f "${MOLD_VM_CACHE_FILE}" ]] || fail "Mold VM cache file not found: ${MOLD_VM_CACHE_FILE}"

  python3 - "$MOLD_VM_CACHE_FILE" "$vm_name" <<'PY'
import json
import sys

cache_path = sys.argv[1]
vm_name = sys.argv[2]

with open(cache_path, "r", encoding="utf-8") as fh:
    data = json.load(fh)

matches = []

def walk(node):
    if isinstance(node, dict):
        if str(node.get("instancename", "")) == vm_name and "scheduleid" in node:
            matches.append(str(node["scheduleid"]))
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for item in node:
            walk(item)

walk(data)

if matches:
    print(matches[0], end="")
PY
}

run_stage_vm_backup() {
  local vm_name="$1"
  local vm_id=""
  local schedule_id=""
  local job_id=""
  local backup_path=""
  local stage_start_epoch=""
  local initial_response=""
  local final_response=""
  local error_text=""
  local -a api_args=()

  vm_id="$(lookup_mold_vm_id "${vm_name}" 2>/dev/null || true)"
  if [[ -z "${vm_id}" ]]; then
    printf 'FAILED\t\t\t\t%s\n' "$(tsv_escape "Unable to resolve Mold VM id for instance name ${vm_name}")"
    return 1
  fi

  schedule_id="$(lookup_mold_vm_schedule_id "${vm_name}" 2>/dev/null || true)"

  api_args=(
    "virtualmachineid" "${vm_id}"
    "policyid" "${POLICY_NAME}"
  )
  if [[ -n "${schedule_id}" ]]; then
    api_args+=("scheduleid" "${schedule_id}")
  fi

  log -ne "Calling Mold createNetBackup API for vm=${vm_name} vmId=${vm_id} scheduleId=${schedule_id} url=${MOLD_CREATE_BACKUP_API_URL}"
  stage_start_epoch="$(date +%s)"
  if ! initial_response="$(invoke_mold_api \
    "${MOLD_CREATE_BACKUP_API_METHOD}" \
    "${MOLD_CREATE_BACKUP_API_URL}" \
    "createNetBackup" \
    "${api_args[@]}" 2>&1)"; then
    printf 'FAILED\t%s\t\t\t%s\n' "${vm_id}" "$(tsv_escape "${initial_response}")"
    return 1
  fi

  job_id="$(extract_json_value_by_key "${initial_response}" "jobid" 2>/dev/null || true)"
  if [[ -z "${job_id}" ]]; then
    printf 'FAILED\t%s\t\t\t%s\n' "${vm_id}" "$(tsv_escape "Mold createNetBackup API did not return jobid for vm=${vm_name}")"
    return 1
  fi

  log -ne "Waiting for Mold createNetBackup async job vm=${vm_name} jobId=${job_id}"
  if ! final_response="$(wait_for_mold_async_job "createNetBackup" "${job_id}" 2>&1)"; then
    error_text="${final_response}"
    printf 'FAILED\t%s\t%s\t\t%s\n' "${vm_id}" "${job_id}" "$(tsv_escape "${error_text}")"
    return 1
  fi

  log -ne "Waiting for NetBackup staging completion vm=${vm_name} vmId=${vm_id} jobId=${job_id}"
  if ! backup_path="$(wait_for_vm_staging_complete "${vm_name}" "${stage_start_epoch}" 2>&1)"; then
    error_text="${backup_path}"
    printf 'FAILED\t%s\t%s\t\t%s\n' "${vm_id}" "${job_id}" "$(tsv_escape "${error_text}")"
    return 1
  fi
  if [[ -z "${backup_path}" ]]; then
    printf 'FAILED\t%s\t%s\t\t%s\n' "${vm_id}" "${job_id}" "$(tsv_escape "No completed backup path found under ${BACKUP_STAGING_ROOT}/${vm_name} for vm=${vm_name}")"
    return 1
  fi

  printf 'SUCCESS\t%s\t%s\t%s\t\n' "${vm_id}" "${job_id}" "${backup_path}"
}
