#!/usr/bin/bash
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

# Shared library for Ablestack Veeam backup/restore hooks on KVM hosts.
# NetBackup-style configuration: /etc/ablestack/veeam/<job>.conf; common Mold staging.

ABLESTACK_VEEAM_ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ABLESTACK_VEEAM_STATE_DIR="${ABLESTACK_VEEAM_STATE_DIR:-/var/lib/ablestack/veeam}"
MOLD_BACKUP_ETC_DIR="${MOLD_BACKUP_ETC_DIR:-${ABLESTACK_VEEAM_ETC_DIR}}"
MOLD_BACKUP_CONF="${MOLD_BACKUP_CONF:-}"
VEEAM_HOST_BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
# The provisioner records a dedicated parent marker directory; no implicit local fallback.
VEEAM_AGENT_PAYLOAD_PATH="${VEEAM_AGENT_PAYLOAD_PATH:-}"
# Actual volume staging and disk export are owned by the Mold provider.

mold_backup_strip_wrapping_quotes() {
  local v="$1"
  v="${v//$'\u2018'/}"
  v="${v//$'\u2019'/}"
  v="${v//$'\u201C'/}"
  v="${v//$'\u201D'/}"
  while [[ "$v" == \'*\' || "$v" == \"*\" ]]; do
    v="${v:1:${#v}-2}"
  done
  printf '%s' "$v"
}
mold_backup_normalize_job_name() {
  mold_backup_strip_wrapping_quotes "${1:-}"
}

mold_backup_configure_vm_selection() {
  local value default_mode previous_mode
  default_mode="$(printf '%s' "${VM_SCHEDULE_BACKUP:-false}" | tr '[:upper:]' '[:lower:]')"
  previous_mode="$(printf '%s' "${VM_SCHEDULE_BACKUP_PREVIOUS:-$default_mode}" | tr '[:upper:]' '[:lower:]')"
  [[ "$default_mode" == true || "$default_mode" == false ]] || {
    echo "--vm-schedule-backup must be true or false" >&2
    return 1
  }
  VM_SCHEDULE_BACKUP="$default_mode"
  if [[ "${VM_SCHEDULE_BACKUP_CLI_SET:-false}" != true && -t 0 ]]; then
    while true; do
      read -r -p "Use Mold per-VM backup schedules? [true/false, default: ${default_mode}]: " value || return 1
      value="$(printf '%s' "${value:-$default_mode}" | tr '[:upper:]' '[:lower:]')"
      if [[ "$value" == true || "$value" == false ]]; then
        VM_SCHEDULE_BACKUP="$value"
        break
      fi
      echo "Enter true or false." >&2
    done
  fi
  if [[ "$VM_SCHEDULE_BACKUP" == true ]]; then
    if [[ "${VM_INCLUDE_CLI_SET:-false}" == true || "${VM_EXCLUDE_CLI_SET:-false}" == true ]]; then
      echo "--vm-include/--vm-exclude require --vm-schedule-backup false" >&2
      return 1
    fi
    VM_INCLUDE="*"
    VM_EXCLUDE="*"
  else
    # A schedule-only sentinel must not survive an explicit switch back to host filters.
    if [[ "$previous_mode" == true ]]; then
      [[ "${VM_INCLUDE_CLI_SET:-false}" == true ]] || VM_INCLUDE="*"
      [[ "${VM_EXCLUDE_CLI_SET:-false}" == true ]] || VM_EXCLUDE=""
    fi
    VM_INCLUDE="${VM_INCLUDE:-*}"
    VM_EXCLUDE="${VM_EXCLUDE:-}"
    if [[ -t 0 ]]; then
      if [[ "${VM_INCLUDE_CLI_SET:-false}" != true ]]; then
        read -r -p "VM_INCLUDE [default: ${VM_INCLUDE}]: " value || return 1
        VM_INCLUDE="${value:-$VM_INCLUDE}"
      fi
      if [[ "${VM_EXCLUDE_CLI_SET:-false}" != true ]]; then
        read -r -p "VM_EXCLUDE [default: ${VM_EXCLUDE:-none}]: " value || return 1
        VM_EXCLUDE="${value:-$VM_EXCLUDE}"
      fi
    fi
  fi
}

mold_backup_resolve_conf_path() {
  local job="${VEEAM_JOB_NAME:-${1:-}}"
  if [[ -n "$job" ]]; then
    if [[ -f "${ABLESTACK_VEEAM_ETC_DIR}/${job}.conf" ]]; then
      echo "${ABLESTACK_VEEAM_ETC_DIR}/${job}.conf"
      return 0
    fi
    local safe_job
    safe_job="$(mold_backup_safe_job_name "$job")"
    if [[ -f "${ABLESTACK_VEEAM_ETC_DIR}/${safe_job}.conf" ]]; then
      echo "${ABLESTACK_VEEAM_ETC_DIR}/${safe_job}.conf"
      return 0
    fi
  fi
  if [[ -n "${MOLD_BACKUP_CONF:-}" && -f "$MOLD_BACKUP_CONF" ]]; then
    echo "$MOLD_BACKUP_CONF"
    return 0
  fi
  for candidate in \
    "${ABLESTACK_VEEAM_ETC_DIR}/mold-backup.conf" \
    "${MOLD_BACKUP_ETC_DIR}/mold-backup.conf"; do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

mold_backup_load_config() {
  local resolved
  local _preserve_backup_id="${BACKUP_ID:-}"
  local _preserve_vm_name="${VM_NAME:-}"
  local _preserve_vm_uuid="${VM_UUID:-}"
  # Do NOT preserve ambient VM_INCLUDE: mold-backup.env / an early default conf load
  # (Job N-1) must not override the per-job .conf selected by VEEAM_JOB_NAME.
  # Single-VM scope is applied later via pre-notify $4, not via env bleed.
  VEEAM_JOB_NAME="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-}")"
  resolved="$(mold_backup_resolve_conf_path "${VEEAM_JOB_NAME:-}")" || {
    echo "Config not found — run veeam-host-config.py" >&2
    return 1
  }
  MOLD_BACKUP_CONF="$resolved"
  VM_SCHEDULE_BACKUP=""
  # shellcheck source=/dev/null
  source "$MOLD_BACKUP_CONF"
  # Caller-provided restore selectors must survive sourcing the job conf.
  [[ -n "${_preserve_backup_id}" ]] && BACKUP_ID="${_preserve_backup_id}"
  [[ -n "${_preserve_vm_name}" ]] && VM_NAME="${_preserve_vm_name}"
  [[ -n "${_preserve_vm_uuid}" ]] && VM_UUID="${_preserve_vm_uuid}"

  # Defense: .conf may contain KEY="'value'" from env sync with single-quoted env files.
  MOLD_API_URL="$(mold_backup_strip_wrapping_quotes "${MOLD_API_URL:-}")"
  MOLD_API_KEY="$(mold_backup_strip_wrapping_quotes "${MOLD_API_KEY:-}")"
  MOLD_API_SECRET="$(mold_backup_strip_wrapping_quotes "${MOLD_API_SECRET:-}")"
  ZONE_ID="$(mold_backup_strip_wrapping_quotes "${ZONE_ID:-}")"
  BACKUP_OFFERING_NAME="$(mold_backup_strip_wrapping_quotes "${BACKUP_OFFERING_NAME:-}")"
  VEEAM_SSH_HOST="$(mold_backup_strip_wrapping_quotes "${VEEAM_SSH_HOST:-}")"
  VEEAM_SSH_USER="$(mold_backup_strip_wrapping_quotes "${VEEAM_SSH_USER:-}")"
  VEEAM_SSH_KEY="$(mold_backup_strip_wrapping_quotes "${VEEAM_SSH_KEY:-}")"
  VEEAM_JOB_NAME="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-}")"
  JOB_NAME="$(mold_backup_normalize_job_name "${JOB_NAME:-${VEEAM_JOB_NAME:-}}")"
  [[ -n "$JOB_NAME" && -z "${VEEAM_JOB_NAME:-}" ]] && VEEAM_JOB_NAME="$JOB_NAME"

  LOG_FILE="${LOG_FILE:-/var/log/mold/backup-veeam.log}"
  LOG_TAG="${LOG_TAG:-mold-veeam-backup}"
  BACKUP_MODE="${BACKUP_MODE:-host}"
  VM_INCLUDE="${VM_INCLUDE:-*}"
  VM_EXCLUDE="${VM_EXCLUDE:-}"
  VM_SCHEDULE_BACKUP="$(printf '%s' "${VM_SCHEDULE_BACKUP:-}" | tr '[:upper:]' '[:lower:]')"
  case "$VM_SCHEDULE_BACKUP" in
    ''|true|false) ;;
    *) echo "Invalid VM_SCHEDULE_BACKUP; expected true or false" >&2; return 1 ;;
  esac
  VM_AUTO_EXCLUDE="${VM_AUTO_EXCLUDE:-true}"
  ZONE_ID="${ZONE_ID:-}"
  RETENTION_PERIOD="${RETENTION_PERIOD:-}"
  VEEAM_URL="${VEEAM_URL:-}"
  VEEAM_USERNAME="${VEEAM_USERNAME:-}"
  VEEAM_PASSWORD="${VEEAM_PASSWORD:-}"
  # Host→Veeam SSH for restore-watch / RP lookup (key missing → password/askpass).
  VEEAM_SSH_PASSWORD="${VEEAM_SSH_PASSWORD:-${VEEAM_PASSWORD:-}}"
  VEEAM_HOST_BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  STAGING_PATH="${STAGING_PATH:-${VEEAM_HOST_BACKUP_PATH}}"
  VEEAM_AGENT_PAYLOAD_PATH="${VEEAM_AGENT_PAYLOAD_PATH:-}"
  QUIESCE_VM="${QUIESCE_VM:-false}"
  BACKUP_OFFERING_NAME="${BACKUP_OFFERING_NAME:-VeeamBackup}"
  # Mold→Veeam trigger (bidirectional mode C): start the matching Veeam Agent job
  # over SSH after a Mold backup completes. Loop is broken by veeam-active/mold-active markers.
  VEEAM_TRIGGER_ENABLED="${VEEAM_TRIGGER_ENABLED:-false}"
  VEEAM_TRIGGER_TTL="${VEEAM_TRIGGER_TTL:-1800}"
  # After Remove-from-Disk (empty Veeam Disk), normal Start fails with
  # FileBackup.IsBackupFilesystemExist — auto Active Full (-FullBackup) once.
  # Latch (not timed cooldown): do not restart until Disk returns (HAS_BACKUP)
  # or an operator runs mold-backup.sh active-full (clears the latch).
  # Default false: Veeam UI Start/Pull must run exactly once. Auto Active Full
  # used to call Start-VBR again after pre-notify publish / catalog-sync and
  # looked like a backup loop. Operators can still run: mold-backup.sh active-full
  VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY="${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}"
  VEEAM_SSH_HOST="${VEEAM_SSH_HOST:-}"
  VEEAM_SSH_USER="${VEEAM_SSH_USER:-administrator}"
  VEEAM_SSH_KEY="${VEEAM_SSH_KEY:-}"
  # Drop missing key path so password/askpass auth is used (common lab setup).
  if [[ -n "${VEEAM_SSH_KEY}" && ! -f "${VEEAM_SSH_KEY}" ]]; then
    mold_backup_notify_log warn "VEEAM_SSH_KEY=${VEEAM_SSH_KEY} missing; using VEEAM_SSH_PASSWORD/askpass for Veeam SSH"
    VEEAM_SSH_KEY=""
  fi
  VEEAM_SSH_PASSWORD="${VEEAM_SSH_PASSWORD:-${VEEAM_PASSWORD:-}}"
  # Veeam VBR native REST API (port 9419) — used by VEEAM_TRIGGER_METHOD=rest/auto (no SSH).
  VEEAM_API_URL="${VEEAM_API_URL:-}"
  VEEAM_API_HOST="${VEEAM_API_HOST:-}"
  VEEAM_API_PORT="${VEEAM_API_PORT:-9419}"
  VEEAM_API_VERSION="${VEEAM_API_VERSION:-1.2-rev0}"
  VEEAM_API_USER="${VEEAM_API_USER:-${VEEAM_USERNAME:-}}"
  VEEAM_API_PASSWORD="${VEEAM_API_PASSWORD:-${VEEAM_PASSWORD:-}}"
  VEEAM_GUEST_JOB_PREFIX="${VEEAM_GUEST_JOB_PREFIX:-Mold VM}"
  VM_TARGETS="${VM_TARGETS:-}"
  KVM_HOSTNAME="${KVM_HOSTNAME:-}"
  RESTORE_WATCH_TRIGGER_MOLD="${RESTORE_WATCH_TRIGGER_MOLD:-false}"
  RESTORE_WATCH_SKIP_EXISTING_ON_START="${RESTORE_WATCH_SKIP_EXISTING_ON_START:-true}"
  RESTORE_LOCK_DIR="${RESTORE_LOCK_DIR:-}"
  VEEAM_RESTORE_WATCH_WINDOW_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}"

  mold_backup_resolve_api_secret
  VEEAM_TRIGGER_METHOD="${VEEAM_TRIGGER_METHOD:-auto}"
  mold_backup_load_common_staging || return 1
  return 0
}

mold_backup_read_env_var() {
  local key="$1" file="$2" line val
  [[ -f "$file" ]] || return 1
  line="$(grep -E "^[[:space:]]*${key}=" "$file" 2>/dev/null | head -1)" || return 1
  val="${line#*=}"
  val="${val#\"}"; val="${val%\"}"
  val="${val#\'}"; val="${val%\'}"
  val="${val//$'\r'/}"
  [[ -n "$val" ]] || return 1
  printf '%s' "$val"
}

mold_backup_resolve_api_secret() {
  if [[ -n "${MOLD_API_SECRET:-}" ]]; then
    return 0
  fi
  if [[ -z "${MOLD_API_SECRET_ENC_FILE:-}" ]]; then
    return 0
  fi
  if [[ -z "${MOLD_SECRET_KEY_FILE:-}" ]]; then
    MOLD_SECRET_KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"
  fi
  local secret_script="$(dirname "${BASH_SOURCE[0]}")/mold-backup-secret.sh"
  if [[ ! -f "$secret_script" ]]; then
    mold_backup_log warn "Cannot decrypt API secret: mold-backup-secret.sh not found"
    return 0
  fi
  MOLD_API_SECRET=$(bash "$secret_script" decrypt --enc-file "${MOLD_API_SECRET_ENC_FILE}" --key-file "${MOLD_SECRET_KEY_FILE}") \
    || mold_backup_die "Failed to decrypt MOLD_API_SECRET from ${MOLD_API_SECRET_ENC_FILE}"
  # OpenSSL decrypt may append a newline; breaks CloudStack API HMAC signature.
  MOLD_API_SECRET="${MOLD_API_SECRET//$'\r'/}"
  MOLD_API_SECRET="${MOLD_API_SECRET%"${MOLD_API_SECRET##*[![:space:]]}"}"
}

mold_backup_log() {
  local level="$1"
  shift
  local msg="[$(date '+%Y-%m-%d %H:%M:%S')] [$level] $*"
  echo "$msg" >&2
  mkdir -p "$(dirname "${LOG_FILE}")" 2>/dev/null || true
  echo "$msg" >> "${LOG_FILE}" 2>/dev/null || true
  if command -v logger >/dev/null 2>&1; then
    case "$level" in
      err) logger -t "${LOG_TAG}" -p user.err "$*" ;;
      warn) logger -t "${LOG_TAG}" -p user.warning "$*" ;;
      *) logger -t "${LOG_TAG}" -p user.info "$*" ;;
    esac
  fi
}

mold_backup_die() {
  mold_backup_log err "$@"
  exit 1
}

mold_backup_require_var() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    mold_backup_die "Required config [$name] is not set in ${MOLD_BACKUP_CONF}"
  fi
}

mold_backup_cmk_bin() {
  command -v cmk >/dev/null 2>&1 && echo "cmk" && return 0
  command -v cloudmonkey >/dev/null 2>&1 && echo "cloudmonkey" && return 0
  return 1
}

mold_backup_require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || mold_backup_die "Required command not found: $cmd"
}

mold_backup_cloudstack_api_call() {
  local cmd="$1"
  shift
  mold_backup_require_var MOLD_API_URL
  mold_backup_require_var MOLD_API_KEY
  mold_backup_require_var MOLD_API_SECRET
  mold_backup_require_cmd curl
  mold_backup_require_cmd python3

  # Sign per ApiServer.verifyRequest: sort param names, URLEncode values (+ -> %20),
  # lowercase the full unsigned string, HMAC-SHA256, Base64 signature.
  local url
  url="$(python3 - "$MOLD_API_URL" "$MOLD_API_KEY" "$MOLD_API_SECRET" "$cmd" "$@" <<'PY'
import base64, hashlib, hmac, sys
from urllib.parse import quote, quote_plus, urlsplit, urlunsplit, parse_qsl

api_url, apikey, secret, command, *pairs = sys.argv[1:]

params = {
    "apikey": apikey,
    "command": command,
    "response": "json",
}

for p in pairs:
    if "=" not in p:
        continue
    k, v = p.split("=", 1)
    if k and v:
        params[k] = v

parts = urlsplit(api_url)
base = urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))

for k, v in parse_qsl(parts.query, keep_blank_values=True):
    if k and v and k not in params:
        params[k] = v

def enc_value(v: str) -> str:
    # Match Java URLEncoder.encode(..., UTF_8).replaceAll("\\+", "%20")
    return quote_plus(str(v), safe="").replace("+", "%20")

# Case-sensitive sort (java.util.Collections.sort on param names)
req_items = sorted(params.items(), key=lambda kv: kv[0])
req_query = "&".join([f"{k}={enc_value(v)}" for k, v in req_items])

unsigned = req_query.lower()
sig = base64.b64encode(
    hmac.new(secret.encode("utf-8"), unsigned.encode("utf-8"), hashlib.sha256).digest()
).decode("ascii")

signed = f"{base}?{req_query}&signature={quote(sig, safe='')}"
print(signed)
PY
)" || exit $?

  mold_backup_log info "API: ${cmd} (curl)" >&2
  # Show response body on errors for debugging.
  local tmp rc http_code body api_err
  tmp="$(mktemp)"
  http_code="$(curl -sS --connect-timeout 10 --max-time 120 -o "$tmp" -w '%{http_code}' "$url")" || {
    rc=$?
    rm -f "$tmp"
    return "$rc"
  }
  body="$(cat "$tmp")"
  rm -f "$tmp"
  if [[ "$http_code" -lt 200 || "$http_code" -ge 300 ]]; then
    echo "$body" >&2
    return 22
  fi
  api_err="$(mold_backup_api_extract_error "$body" 2>/dev/null || true)"
  if [[ -n "$api_err" ]]; then
    mold_backup_api_log_ms_schema_hint "$api_err"
    mold_backup_log err "API ${cmd} failed: ${api_err}" >&2
    echo "$body"
    return 1
  fi
  echo "$body"
}

mold_backup_cmk_run() {
  local cmd="$1"
  shift
  mold_backup_require_var MOLD_API_URL
  mold_backup_require_var MOLD_API_KEY
  mold_backup_require_var MOLD_API_SECRET
  local cmk
  cmk=$(mold_backup_cmk_bin) || {
    mold_backup_cloudstack_api_call "$cmd" "$@"
    return $?
  }
  local -a args=(-u "${MOLD_API_URL}" -a "${MOLD_API_KEY}" -s "${MOLD_API_SECRET}" "${cmd}")
  local pair key value
  for pair in "$@"; do
    key="${pair%%=*}"
    value="${pair#*=}"
    [[ -n "$key" && -n "$value" ]] && args+=("${key}=${value}")
  done
  mold_backup_log info "Executing: ${cmk} ${cmd} $*" >&2
  local out rc api_err
  out="$("${cmk}" "${args[@]}" 2>/dev/null)" || rc=$?
  api_err="$(mold_backup_api_extract_error "$out" 2>/dev/null || true)"
  if [[ -n "$api_err" ]]; then
    mold_backup_api_log_ms_schema_hint "$api_err"
    mold_backup_log err "API ${cmd} failed: ${api_err}" >&2
    echo "$out"
    return 1
  fi
  echo "$out"
  return "${rc:-0}"
}

mold_backup_api_restore() {
  mold_backup_require_var BACKUP_ID
  local json job_id
  MOLD_RESTORE_ASYNC_JOB_ID=""
  MOLD_RESTORE_ASYNC_SUBMITTED=false
  # Existing-VM restore requires Stopped; FLR→Mold auto-stops unless RESTORE_AUTO_STOP=false.
  if [[ -n "${VM_NAME:-}" ]]; then
    mold_backup_api_ensure_vm_stopped_for_restore "$VM_NAME" || return 1
  fi
  json=$(mold_backup_cmk_run restoreBackup "id=${BACKUP_ID}" 2>/dev/null) || return 1
  job_id="$(mold_backup_api_json_field "$json" "restorebackupresponse.jobid")"
  if [[ -n "$job_id" ]]; then
    MOLD_RESTORE_ASYNC_JOB_ID="$job_id"
    if [[ "${MOLD_RESTORE_ASYNC_DETACH:-false}" == "true" ]]; then
      MOLD_RESTORE_ASYNC_SUBMITTED=true
      mold_backup_notify_log info "Restore async job submitted: ${job_id}; host restore will continue independently"
      return 0
    fi
    mold_backup_notify_log info "restoreBackup job=${job_id}; waiting for MS/agent restore"
    mold_backup_api_wait_async_job "$job_id" 3600 || return 1
    mold_backup_notify_log info "Restore async job completed: ${job_id}"
    return 0
  fi
  return 0
}

mold_backup_resolve_veeam_interval_type() {
  local schedule="${1:-default}"
  local configured="${VEEAM_BACKUP_INTERVAL_TYPE:-}"
  if [[ -n "$configured" ]]; then
    echo "${configured^^}"
    return 0
  fi
  case "${schedule,,}" in
    external|manual|adhoc|ui|oneshot|hourly|daily|weekly|monthly|default|""|*)
      echo "EXTERNAL"
      ;;
  esac
}

mold_backup_notify_log() {
  local level="$1"
  shift
  LOG_FILE="${LOG_FILE:-/var/log/mold/veeam-hook.log}"
  LOG_TAG="${LOG_TAG:-mold-veeam-hook}"
  mold_backup_log "$level" "$@"
}

mold_backup_state_dir() {
  echo "${ABLESTACK_VEEAM_STATE_DIR}/state"
}

mold_backup_state_file_for_job() {
  local job="$1"
  local run_id="${2:-$(date '+%Y%m%d%H%M%S')}"
  echo "$(mold_backup_state_dir)/${job}.${run_id}.state"
}

mold_backup_latest_state_file() {
  local job="$1"
  local dir found
  dir="$(mold_backup_state_dir)"
  [[ -d "$dir" ]] || return 0
  found="$(ls -1t "${dir}/${job}".*.state 2>/dev/null | head -1 || true)"
  echo "$found"
}

mold_backup_list_running_domains() {
  virsh -c qemu:///system list --name --state-running 2>/dev/null | awk 'NF' || true
}

mold_backup_domain_exists() {
  local vm_name="$1"
  [[ -n "$vm_name" ]] || return 1
  virsh -c qemu:///system dominfo "$vm_name" >/dev/null 2>&1 && return 0
  virsh dominfo "$vm_name" >/dev/null 2>&1
}

mold_backup_list_target_domains() {
  if [[ -n "${MOLD_EXTERNAL_BACKUP_CANDIDATES:-}" ]]; then
    printf '%s' "$MOLD_EXTERNAL_BACKUP_CANDIDATES" | python3 -c '
import json, sys
for candidate in json.load(sys.stdin)["candidate"]:
    print(candidate["instancename"])
'
    return
  fi
  local -a targets=() seen="" vm_name token
  while IFS= read -r vm_name; do
    [[ -z "$vm_name" ]] && continue
    mold_backup_vm_in_filter "$vm_name" || continue
    [[ "$seen" == *"|${vm_name}|"* ]] && continue
    seen="${seen}|${vm_name}|"
    targets+=("$vm_name")
  done < <(mold_backup_list_running_domains)

  local include="${VM_INCLUDE:-*}"
  if [[ "$include" != "*" ]]; then
    IFS=',' read -ra _in <<< "$include"
    for token in "${_in[@]}"; do
      token="$(echo "$token" | xargs)"
      [[ -z "$token" ]] && continue
      mold_backup_vm_in_filter "$token" || continue
      mold_backup_domain_exists "$token" || continue
      [[ "$seen" == *"|${token}|"* ]] && continue
      seen="${seen}|${token}|"
      targets+=("$token")
    done
  fi

  if [[ ${#targets[@]} -eq 0 ]]; then
    return 0
  fi
  printf '%s\n' "${targets[@]}"
}

mold_backup_vm_is_auto_excluded() {
  local vm_name="$1"
  [[ "${VM_AUTO_EXCLUDE:-true}" == "true" ]] || return 1
  case "$vm_name" in
    scvm|scvm*)
      return 0
      ;;
  esac
  # DomR / SSVM / CPVM style instance names
  if [[ "$vm_name" =~ ^[rsv]-[0-9]+-VM$ ]]; then
    return 0
  fi
  case "$vm_name" in
    *ablestack-template*|*ablestack_template*)
      return 0
      ;;
  esac
  return 1
}

mold_backup_vm_matches_exclude_token() {
  local vm_name="$1" token="$2"
  [[ -z "$token" ]] && return 1
  if [[ "$token" == *"*"* || "$token" == *"?"* || "$token" == *"["* ]]; then
    # intentional unquoted pattern on RHS for glob match
    # shellcheck disable=SC2254
    [[ "$vm_name" == $token ]]
    return $?
  fi
  [[ "$vm_name" == "$token" ]]
}

mold_backup_vm_in_filter() {
  local vm_name="$1"
  local include="${VM_INCLUDE:-*}"
  local exclude="${VM_EXCLUDE:-}"
  local token

  if mold_backup_vm_is_auto_excluded "$vm_name"; then
    return 1
  fi

  if [[ -n "$exclude" ]]; then
    IFS=',' read -ra _ex <<< "$exclude"
    for token in "${_ex[@]}"; do
      token="$(echo "$token" | xargs)"
      [[ -z "$token" ]] && continue
      if mold_backup_vm_matches_exclude_token "$vm_name" "$token"; then
        return 1
      fi
    done
  fi

  [[ "$include" == "*" ]] && return 0
  IFS=',' read -ra _in <<< "$include"
  for token in "${_in[@]}"; do
    token="$(echo "$token" | xargs)"
    [[ -z "$token" ]] && continue
    [[ "$vm_name" == "$token" ]] && return 0
  done
  return 1
}

mold_backup_api_json_field() {
  local json="$1" path="$2"
  echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    parts = sys.argv[1].split('.')
    cur = d
    for p in parts:
        if isinstance(cur, list) and cur:
            cur = cur[0]
        if not isinstance(cur, dict):
            cur = None
            break
        cur = cur.get(p)
    if isinstance(cur, list) and cur:
        cur = cur[0]
    print('' if cur is None else cur)
except Exception:
    print('')
" "$path" 2>/dev/null
}

mold_backup_api_list_config_value() {
  local name="$1"
  local json val
  json=$(mold_backup_cmk_run listConfigurations "name=${name}" 2>/dev/null) || return 1
  val=$(echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    cfgs = d.get('listconfigurationsresponse', {}).get('configuration', [])
    if isinstance(cfgs, dict): cfgs = [cfgs]
    print(cfgs[0].get('value','') if cfgs else '')
except Exception:
    print('')
" 2>/dev/null)
  echo "$val"
}

mold_backup_load_common_staging() {
  [[ "${VEEAM_PROVIDER_NAME:-ablestack-veeam}" == "ablestack-veeam" ]] || return 0
  local enabled root
  enabled="$(mold_backup_api_list_config_value "backup.thirdparty.staging.enable")" || return 1
  if [[ "$enabled" != "true" ]]; then
    mold_backup_notify_log err "Configure common staging and enable backup.thirdparty.staging.enable in Mold first."
    return 1
  fi
  root="$(mold_backup_api_list_config_value "backup.thirdparty.staging.root.path")" || return 1
  if [[ "$root" != /* || "$root" == / ]]; then
    mold_backup_notify_log err "backup.thirdparty.staging.root.path must specify an absolute staging directory."
    return 1
  fi
  root="$(realpath -m -- "$root")" || return 1
  VEEAM_HOST_BACKUP_PATH="${root%/}/ablestack-veeam"
  STAGING_PATH="$VEEAM_HOST_BACKUP_PATH"
  export VEEAM_HOST_BACKUP_PATH STAGING_PATH
}

mold_backup_api_external_stage_timeout() {
  local operation_timeout
  operation_timeout="$(mold_backup_api_data_operation_timeout)"
  echo "${VEEAM_EXTERNAL_STAGE_TIMEOUT:-$((operation_timeout + 600))}"
}

mold_backup_api_data_operation_timeout() {
  local configured
  configured="$(mold_backup_api_list_config_value "backup.data.operation.timeout" 2>/dev/null || true)"
  if [[ "$configured" =~ ^[1-9][0-9]*$ ]]; then
    echo "$configured"
    return 0
  fi
  echo "${VEEAM_DATA_OPERATION_TIMEOUT:-43200}"
}

mold_backup_api_update_config_if_needed() {
  local name="$1" value="$2"
  local current
  current="$(mold_backup_api_list_config_value "$name" 2>/dev/null || true)"
  [[ "$current" == "$value" ]] && return 0
  mold_backup_cmk_run updateConfiguration "name=${name}" "value=${value}" >/dev/null \
    || mold_backup_notify_log warn "updateConfiguration ${name} failed (may need admin API key)"
}

mold_backup_api_append_provider_plugin() {
  local provider="${1:-ablestack-veeam}" scope current updated json
  local -a scopes=(global)
  [[ -z "${ZONE_ID:-}" ]] || scopes+=(zone)
  for scope in "${scopes[@]}"; do
    if [[ "$scope" == zone ]]; then
      json="$(mold_backup_cmk_run listConfigurations name=backup.framework.provider.plugin "zoneid=${ZONE_ID}")" || return 1
      current="$(mold_backup_api_json_field "$json" listconfigurationsresponse.configuration.value)"
    else
      current="$(mold_backup_api_list_config_value backup.framework.provider.plugin)" || return 1
    fi
    updated="$(python3 -c 'import sys; items=[x.strip() for x in sys.argv[1].split(",") if x.strip()]; want=sys.argv[2]; print(",".join(items if want.lower() in {x.lower() for x in items} else items+[want]))' "$current" "$provider")" || return 1
    [[ "$updated" != "$current" ]] || continue
    if [[ "$scope" == zone ]]; then
      mold_backup_cmk_run updateConfiguration name=backup.framework.provider.plugin "value=${updated}" "zoneid=${ZONE_ID}" >/dev/null || return 1
    else
      mold_backup_cmk_run updateConfiguration name=backup.framework.provider.plugin "value=${updated}" >/dev/null || return 1
    fi
  done
}

mold_backup_api_list_cluster_config_value() {
  local name="$1" cluster_id="$2"
  local json val
  json=$(mold_backup_cmk_run listConfigurations "name=${name}" "clusterid=${cluster_id}" 2>/dev/null) || return 1
  val=$(echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    cfgs = d.get('listconfigurationsresponse', {}).get('configuration', [])
    if isinstance(cfgs, dict): cfgs = [cfgs]
    print(cfgs[0].get('value','') if cfgs else '')
except Exception:
    print('')
" 2>/dev/null)
  echo "$val"
}

mold_backup_api_ensure_global_settings() {
  mold_backup_api_update_config_if_needed "backup.framework.enabled" "true"
  mold_backup_api_update_config_if_needed "backup.enable.attach.detach.of.volumes" "true"
  # Append ablestack-veeam; do not wipe existing providers (dummy,nas,netbackup,...).
  mold_backup_api_append_provider_plugin ablestack-veeam || return 1
  [[ -n "${VEEAM_URL:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.url" "${VEEAM_URL}"
  [[ -n "${VEEAM_USERNAME:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.username" "${VEEAM_USERNAME}"
  [[ -n "${VEEAM_PASSWORD:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.password" "${VEEAM_PASSWORD}"
  # This also runs from pre-notify. Backup mode and chain limits are configured only
  # by veeam-host-config.py; runtime must retain the current Mold administrator settings.
  return 0
}

mold_backup_api_backup_host_cluster() {
  local json selector
  selector="${KVM_HOST:-${KVM_HOSTNAME:-$(hostname -s)}}"
  selector="${selector#*@}"
  json=$(mold_backup_cmk_run listHosts "type=Routing" "zoneid=${ZONE_ID}" "pagesize=500" "page=1") || return 1
  printf '%s\n' "$json" | python3 -c '
import json, sys
hosts = json.load(sys.stdin).get("listhostsresponse", {}).get("host", [])
if isinstance(hosts, dict):
    hosts = [hosts]
selector = sys.argv[1].lower()
matches = [host for host in hosts if selector in (str(host.get("name", "")).lower(), str(host.get("ipaddress", "")).lower())]
if len(matches) != 1 or not matches[0].get("clusterid"):
    sys.exit("Cannot resolve a unique Mold Host and Cluster for " + selector)
print(matches[0]["clusterid"])
' "$selector"
}

mold_backup_api_configure_backup_mode() {
  local incremental="$1" chain_size="${2:-}" cluster_id scope current
  local -a params
  [[ "$incremental" == true || "$incremental" == false ]] || return 1
  if [[ -n "$chain_size" ]]; then
    [[ "$incremental" == true && "$chain_size" =~ ^[1-9][0-9]*$ ]] || return 1
  fi
  cluster_id="$(mold_backup_api_backup_host_cluster)" || return 1
  MOLD_BACKUP_MODE_RESTART_REQUIRED=false
  for scope in global cluster; do
    params=("name=kvm.incremental.backup" "value=${incremental}")
    if [[ "$scope" == cluster ]]; then
      params+=("clusterid=${cluster_id}")
      current="$(mold_backup_api_list_cluster_config_value kvm.incremental.backup "$cluster_id")" || return 1
    else
      current="$(mold_backup_api_list_config_value kvm.incremental.backup)" || return 1
    fi
    if [[ "$current" != "$incremental" ]]; then
      mold_backup_cmk_run updateConfiguration "${params[@]}" >/dev/null || return 1
      MOLD_BACKUP_MODE_RESTART_REQUIRED=true
      mold_backup_notify_log info "Updated ${scope} kvm.incremental.backup=${incremental}"
    fi
  done
  if [[ -n "$chain_size" ]]; then
    current="$(mold_backup_api_list_config_value kvm.backup.chain.size)" || return 1
    if [[ "$current" != "$chain_size" ]]; then
      mold_backup_cmk_run updateConfiguration "name=kvm.backup.chain.size" "value=${chain_size}" >/dev/null || return 1
    fi
  fi
  return 0
}

mold_backup_api_extract_error() {
  local json="$1"
  echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    err = d.get('errorresponse', {})
    if err:
        print(err.get('errortext', err))
        sys.exit(0)
    for k, v in d.items():
        if k.endswith('response') and isinstance(v, dict) and v.get('errortext'):
            print(v.get('errortext'))
            sys.exit(0)
except Exception:
    pass
" 2>/dev/null
}

mold_backup_api_log_ms_schema_hint() {
  local msg="$1"
  [[ "$msg" == *backup_offering_details* ]] || return 0
  mold_backup_log err "Mold MS DB is missing table cloud.backup_offering_details (schema 4.23+). On MS host run: mysql cloud < mold-ms-backup-schema-fix.sql ; restart management server" >&2
}

mold_backup_api_list_backup_offerings() {
  local json count err
  local -a args=()
  [[ -n "${ZONE_ID:-}" ]] && args+=("zoneid=${ZONE_ID}")
  json=$(mold_backup_cmk_run listBackupOfferings "${args[@]}" 2>/dev/null) || return 1
  err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
  if [[ -n "$err" ]]; then
    mold_backup_api_log_ms_schema_hint "$err"
    mold_backup_notify_log err "listBackupOfferings failed: ${err}"
    return 1
  fi
  count=$(echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    r = d.get('listbackupofferingsresponse', {})
    c = r.get('count')
    if c is None:
        offs = r.get('backupoffering', [])
        if isinstance(offs, dict): offs = [offs]
        c = len(offs)
    print(int(c or 0))
except Exception:
    print(0)
" 2>/dev/null)
  if [[ "${count:-0}" -eq 0 && -n "${ZONE_ID:-}" ]]; then
    json=$(mold_backup_cmk_run listBackupOfferings 2>/dev/null) || return 1
  fi
  echo "$json"
}

mold_backup_api_first_zone_id() {
  local json
  json=$(mold_backup_cmk_run listZones 2>/dev/null) || return 1
  echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    zones = d.get('listzonesresponse', {}).get('zone', [])
    if isinstance(zones, dict):
        zones = [zones]
    if zones:
        print(zones[0].get('id', ''))
except Exception:
    pass
" 2>/dev/null
}

mold_backup_api_ensure_zone_id() {
  local json err
  if [[ -n "${ZONE_ID:-}" ]]; then
    json=$(mold_backup_cmk_run listZones "id=${ZONE_ID}" 2>/dev/null || true)
    err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
    if [[ -z "$err" ]] && echo "$json" | grep -q '"id"'; then
      return 0
    fi
    mold_backup_notify_log warn "ZONE_ID=${ZONE_ID} invalid — refreshing via listZones"
    ZONE_ID=""
  fi
  ZONE_ID="$(mold_backup_api_first_zone_id 2>/dev/null || true)"
  [[ -n "$ZONE_ID" ]] || {
    mold_backup_notify_log err "listZones failed — cannot determine ZONE_ID"
    return 1
  }
  mold_backup_notify_log info "ZONE_ID=${ZONE_ID}"
  return 0
}

mold_backup_api_detect_veeam_provider() {
  echo ablestack-veeam
}


mold_backup_api_ensure_backup_resources() {
  mold_backup_api_ensure_zone_id || return 1
  VEEAM_PROVIDER_NAME=ablestack-veeam
  export VEEAM_PROVIDER_NAME
  mold_backup_api_ensure_offering
}

mold_backup_api_extract_async_job_error() {
  local json="$1"
  echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    r = d.get('queryasyncjobresultresponse', {})
    jr = r.get('jobresult')
    if isinstance(jr, dict):
        if jr.get('errortext'):
            print(jr.get('errortext'))
        elif jr.get('errorresponse', {}).get('errortext'):
            print(jr['errorresponse']['errortext'])
    elif isinstance(jr, str) and jr.strip():
        print(jr.strip())
    if r.get('errortext'):
        print(r.get('errortext'))
except Exception:
    pass
" 2>/dev/null
}

mold_backup_offering_name() {
  echo "${BACKUP_OFFERING_NAME:-VeeamBackup}"
}

mold_backup_api_find_offering_id() {
  local provider="${1:-${VEEAM_PROVIDER_NAME}}"
  local offering_name="${2:-$(mold_backup_offering_name)}"
  local json
  json=$(mold_backup_api_list_backup_offerings) || return 1
  # Match the actual plugin name; stock and Ablestack providers are distinct.
  echo "$json" | python3 -c "
import json, sys
provider = (sys.argv[1] or '').lower()
name = sys.argv[2] if len(sys.argv) > 2 else ''
try:
    d = json.load(sys.stdin)
    offs = d.get('listbackupofferingsresponse', {}).get('backupoffering', [])
    if isinstance(offs, dict): offs = [offs]
    if name:
        for o in offs:
            if o.get('name') == name and (o.get('provider') or '').lower() == provider:
                print(o.get('id', ''))
                sys.exit(0)
        sys.exit(0)
    for o in offs:
        p = (o.get('provider') or '').lower()
        if p == provider:
            print(o.get('id', ''))
            break
except Exception:
    pass
" "$provider" "$offering_name" 2>/dev/null
}

mold_backup_api_ensure_offering() {
  local offering_id json job_id name
  mold_backup_api_ensure_zone_id || return 1
  name="$(mold_backup_offering_name)"
  offering_id="$(mold_backup_api_find_offering_id ablestack-veeam "$name")" || return 1
  if [[ -n "$offering_id" ]]; then
    echo "$offering_id"
    return 0
  fi
  json="$(mold_backup_cmk_run importBackupOffering "name=${name}" "description=Ablestack Veeam volume backup" \
    provider=ablestack-veeam externalid=veeam "zoneid=${ZONE_ID}" allowuserdrivenbackups=false "retentionperiod=${RETENTION_PERIOD:-P7D}")" || return 1
  offering_id="$(mold_backup_api_json_field "$json" importbackupofferingresponse.backupoffering.id)"
  job_id="$(mold_backup_api_json_field "$json" importbackupofferingresponse.jobid)"
  if [[ -z "$offering_id" && -n "$job_id" ]]; then
    json="$(mold_backup_api_wait_async_job "$job_id" 300)" || return 1
    offering_id="$(mold_backup_api_json_field "$json" queryasyncjobresultresponse.jobresult.backupoffering.id)"
  fi
  [[ -n "$offering_id" ]] || offering_id="$(mold_backup_api_find_offering_id ablestack-veeam "$name")" || return 1
  [[ -n "$offering_id" ]] || return 1
  echo "$offering_id"
}

mold_backup_safe_job_name() {
  python3 -c 'import re, sys; print(re.sub(r"[^A-Za-z0-9._-]", "_", sys.argv[1]))' "$1"
}

mold_backup_registry_dir() {
  echo "${ABLESTACK_VEEAM_STATE_DIR}/registry"
}

mold_backup_api_pick_vm_record() {
  local json="$1" lookup="$2"
  echo "$json" | python3 -c "
import json, sys
lookup = sys.argv[1]
try:
    d = json.load(sys.stdin)
    vms = d.get('listvirtualmachinesresponse', {}).get('virtualmachine', [])
    if isinstance(vms, dict): vms = [vms]
    for v in vms:
        if v.get('instancename') == lookup or v.get('name') == lookup:
            print(json.dumps({'listvirtualmachinesresponse': {'count': 1, 'virtualmachine': v}}))
            sys.exit(0)
except Exception:
    pass
sys.exit(1)
" "$lookup" 2>/dev/null
}

mold_backup_api_get_vm_record() {
  local lookup="$1"
  local -a args=("listall=true")
  local json picked
  local try_zone="${2:-}"

  _mold_backup_list_vms() {
    local -a call_args=("listall=true")
    [[ -n "$1" ]] && call_args+=("zoneid=$1")
    mold_backup_cmk_run listVirtualMachines "${call_args[@]}" 2>/dev/null
  }

  if [[ -n "$try_zone" ]]; then
    json="$(_mold_backup_list_vms "$try_zone")" || json=""
    if picked=$(mold_backup_api_pick_vm_record "$json" "$lookup" 2>/dev/null); then
      echo "$picked"
      return 0
    fi
  fi

  json=$(mold_backup_cmk_run listVirtualMachines "listall=true" "name=${lookup}" 2>/dev/null) || true
  if picked=$(mold_backup_api_pick_vm_record "$json" "$lookup" 2>/dev/null); then
    echo "$picked"
    return 0
  fi

  json="$(_mold_backup_list_vms "")" || return 1
  mold_backup_api_pick_vm_record "$json" "$lookup"
}

mold_backup_api_get_vm_id() {
  local instance_name="$1" json
  if [[ "${VM_NAME:-}" == "$instance_name" && -n "${VM_UUID:-}" ]]; then
    echo "$VM_UUID"
    return 0
  fi
  json=$(mold_backup_api_get_vm_record "$instance_name" "${ZONE_ID:-}") || return 1
  mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.id"
}

mold_backup_api_format_backup_name() {
  local vm_label="$1"
  echo "${vm_label}-$(date -u +%Y-%m-%dT%H:%M:%S+0000)"
}

mold_backup_api_get_vm_hostname() {
  local vm_id="$1" json name
  json=$(mold_backup_cmk_run listVirtualMachines "id=${vm_id}" 2>/dev/null) || return 1
  name="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.name")"
  [[ -n "$name" ]] || name="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.instancename")"
  [[ -n "$name" ]] || return 1
  echo "$name"
}

mold_backup_api_build_backup_name_for_vm() {
  local vm_id="$1" fallback_label="${2:-}"
  local vm_label
  # Prefer Mold VM hostname (e.g. backup-test) over libvirt instance name (i-2-7-VM)
  vm_label="$(mold_backup_api_get_vm_hostname "$vm_id" 2>/dev/null || true)"
  [[ -n "$vm_label" ]] || vm_label="$fallback_label"
  [[ -n "$vm_label" ]] || vm_label="$vm_id"
  mold_backup_api_format_backup_name "$vm_label"
}

mold_backup_api_external_candidates() {
  local response
  local -a selection_args=("provider=ablestack-veeam" "hostname=$(mold_backup_local_kvm_name)" "policyid=${VEEAM_JOB_NAME}")
  [[ -z "${VM_SCHEDULE_BACKUP:-}" ]] || selection_args+=("vmschedulebackup=${VM_SCHEDULE_BACKUP}")
  response=$(mold_backup_cmk_run listExternalBackupCandidates "${selection_args[@]}") || {
    mold_backup_notify_log err "Mold schedule lookup failed; configuration fallback is forbidden"
    return 1
  }
  printf '%s' "$response" | python3 -c '
import json, sys
try:
    response = json.load(sys.stdin)["listexternalbackupcandidatesresponse"]["externalbackupcandidates"]
    mode = response["selectionmode"]
    candidates = response["candidate"]
    def require(condition):
        if not condition:
            raise ValueError("Invalid target selection response")
    require(mode in ("SCHEDULE", "CONFIG") and isinstance(candidates, list))
    expected_mode = {"true": "SCHEDULE", "false": "CONFIG"}.get(sys.argv[1])
    require(expected_mode is None or mode == expected_mode)
    seen = set()
    for candidate in candidates:
        require(isinstance(candidate, dict))
        for key in ("virtualmachineid", "instancename", "hostid"):
            require(isinstance(candidate.get(key), str) and candidate[key].strip())
        require(candidate["virtualmachineid"] not in seen)
        seen.add(candidate["virtualmachineid"])
        if mode == "SCHEDULE":
            require(all(isinstance(candidate.get(key), str) and candidate[key].strip()
                        for key in ("scheduleid", "backupofferingid")))
        else:
            require(not candidate.get("scheduleid"))
    print(json.dumps(response))
except (ValueError, KeyError, TypeError, AssertionError):
    sys.exit("Invalid Mold schedule response; configuration fallback is forbidden")
' "${VM_SCHEDULE_BACKUP:-}"
}

mold_backup_external_candidate_field() {
  printf '%s' "$MOLD_EXTERNAL_BACKUP_CANDIDATES" | python3 -c '
import json, sys
response = json.load(sys.stdin)
if not sys.argv[1]:
    value = response[sys.argv[2]]
else:
    candidate = next(c for c in response["candidate"] if c["instancename"] == sys.argv[1])
    value = candidate.get(sys.argv[2])
    if value is None:
        value = ""
print(str(value).lower() if isinstance(value, bool) else value, end="")
' "$1" "$2"
}

mold_backup_process_external_candidate_vm() {
  local vm_name="$1" state_file="$2"
  local vm_id backup_result backup_id host_path wait_seconds=0
  vm_id="$(mold_backup_external_candidate_field "$vm_name" virtualmachineid)" || return 1
  if mold_backup_trigger_active "mold-active" "$vm_name"; then
    mold_backup_trigger_clear "mold-active" "$vm_name"
    mold_backup_notify_log info "Mold already requested backup for ${vm_name}; skipping recursive host dispatch"
    return 0
  fi
  # Preserve the watcher/loop guard while new staging files are being written.
  mold_backup_trigger_mark "veeam-active" "$vm_name"
  backup_result="$(mold_backup_api_create_veeam_and_wait "$vm_id" "$vm_name")" || {
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=external-dispatch-failed"
    return 1
  }
  backup_id="${backup_result%%|*}"
  host_path="$(mold_backup_api_backup_external_id "$vm_id" "$backup_id")" || return 1
  until [[ -n "$host_path" && -f "${host_path}/.volume-bootstrap" ]]; do
    [[ "$wait_seconds" -lt 60 ]] || return 1
    sleep 1
    wait_seconds=$((wait_seconds + 1))
    host_path="$(mold_backup_api_backup_external_id "$vm_id" "$backup_id")" || return 1
  done
  python3 "$(mold_backup_bootstrap_helper)" publish --payload-root "$VEEAM_AGENT_PAYLOAD_PATH" \
    --source-marker "${host_path}/.volume-bootstrap" --backup-uuid "$backup_id" >/dev/null || return 1
  mold_backup_state_write_line "$state_file" \
    "vm=${vm_name} id=${vm_id} backup_id=${backup_id} type=${backup_result#*|} path=${host_path} status=pipeline"
  return 0
}

mold_backup_api_wait_async_job() {
  local job_id="$1" max_wait="${2:-600}"
  local elapsed=0 json status result
  [[ -z "$job_id" ]] && return 1
  while [[ "$elapsed" -lt "$max_wait" ]]; do
    json=$(mold_backup_cmk_run queryAsyncJobResult "jobid=${job_id}" 2>/dev/null) || return 1
    status="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobstatus")"
    if [[ "$status" == "1" ]]; then
      result="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult")"
      echo "$json"
      return 0
    fi
    if [[ "$status" == "2" ]]; then
      local job_err
      job_err="$(mold_backup_api_extract_async_job_error "$json" 2>/dev/null || true)"
      mold_backup_notify_log err "Async job failed: ${job_id}${job_err:+ — ${job_err}}"
      return 1
    fi
    if [[ $((elapsed % 30)) -eq 0 ]]; then
      mold_backup_notify_log info "Async job ${job_id} pending (status=${status:-0}, elapsed=${elapsed}s/${max_wait}s)"
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  mold_backup_notify_log err "Async job timeout: ${job_id}"
  return 1
}

mold_backup_api_get_vm_state() {
  local instance_name="$1" json
  json=$(mold_backup_api_get_vm_record "$instance_name" "${ZONE_ID:-}") || return 1
  mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.state"
}

mold_backup_api_ensure_vm_stopped_for_restore() {
  local vm_name="${1:-${VM_NAME:-}}"
  local auto_stop="${RESTORE_AUTO_STOP:-true}"
  local vm_id state json job_id elapsed max_wait=300
  [[ -n "$vm_name" ]] || return 0
  # Safety: refuse if caller is clearly in a backup hook path.
  if [[ "${MOLD_BACKUP_HOOK:-}" == "pre-notify" || "${MOLD_BACKUP_HOOK:-}" == "post-notify" ]]; then
    mold_backup_notify_log err "ensure-stopped refused: hook=${MOLD_BACKUP_HOOK} must not stop VMs (restore-only)"
    return 1
  fi
  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || {
    mold_backup_notify_log warn "ensure-stopped: cannot resolve Mold id for ${vm_name}"
    return 1
  }
  state="$(mold_backup_api_get_vm_state "$vm_name" 2>/dev/null || true)"
  case "$state" in
    Stopped|Destroyed|Expunging)
      mold_backup_notify_log info "ensure-stopped: ${vm_name} already ${state}"
      return 0
      ;;
  esac
  if [[ "$auto_stop" != "true" ]]; then
    mold_backup_notify_log err "ensure-stopped: ${vm_name} is ${state:-unknown}; stop in Mold UI or set RESTORE_AUTO_STOP=true"
    return 1
  fi
  mold_backup_notify_log info "FLR→Mold: auto-stopping ${vm_name} (state=${state:-unknown}) before restore"
  json=$(mold_backup_cmk_run stopVirtualMachine "id=${vm_id}" "forced=true" 2>/dev/null) \
    || {
      mold_backup_notify_log err "ensure-stopped: stopVirtualMachine failed for ${vm_name}"
      return 1
    }
  job_id="$(mold_backup_api_json_field "$json" "stopvirtualmachineresponse.jobid")"
  if [[ -n "$job_id" ]]; then
    mold_backup_api_wait_async_job "$job_id" 600 >/dev/null || return 1
  fi
  elapsed=0
  while [[ "$elapsed" -lt "$max_wait" ]]; do
    state="$(mold_backup_api_get_vm_state "$vm_name" 2>/dev/null || true)"
    if [[ "$state" == "Stopped" ]]; then
      mold_backup_notify_log info "ensure-stopped: ${vm_name} is Stopped"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  mold_backup_notify_log err "ensure-stopped: timeout waiting for ${vm_name} Stopped (last=${state:-unknown})"
  return 1
}

mold_backup_api_create_veeam_and_wait() {
  local vm_id="$1" vm_label="${2:-}"
  local json job_id backup_id backup_type backup_name ids_before interval_type veeam_job
  local stage_timeout stage_started_at remaining_timeout
  stage_timeout="$(mold_backup_api_external_stage_timeout)"
  [[ "$stage_timeout" =~ ^[1-9][0-9]*$ ]] || stage_timeout=43200
  stage_started_at="$(date +%s)"
  backup_name="$(mold_backup_api_build_backup_name_for_vm "$vm_id" "$vm_label")"
  ids_before="$(mold_backup_api_list_backup_ids "$vm_id" 2>/dev/null || true)"
  interval_type="$(mold_backup_resolve_veeam_interval_type "${VEEAM_SCHEDULE_NAME:-${SCHEDULE:-default}}")"
  veeam_job="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-${JOB_NAME:-}}")"
  mold_backup_notify_log info "createAblestackVeeamBackup vm=${vm_id} name=${backup_name} interval=${interval_type} job=${veeam_job:-n/a} (MS→agent NAS backup)"
  if [[ -n "${MOLD_EXTERNAL_BACKUP_CANDIDATES:-}" ]]; then
    local field value
    local -a execution_args=("virtualmachineid=${vm_id}" "name=${backup_name}" "jobname=${veeam_job}"
      "selectionmode=$(mold_backup_external_candidate_field "" selectionmode)")
    [[ -z "${VM_SCHEDULE_BACKUP:-}" ]] || execution_args+=("vmschedulebackup=${VM_SCHEDULE_BACKUP}")
    for field in hostid backupofferingid scheduleid quiescevm; do
      value="$(mold_backup_external_candidate_field "$vm_label" "$field")" || return 1
      [[ -z "$value" ]] || execution_args+=("${field}=${value}")
    done
    # Never retry without the selected schedule/offering context after an API error.
    json=$(mold_backup_cmk_run createAblestackVeeamBackup "${execution_args[@]}") || return 1
  elif [[ -n "$veeam_job" ]]; then
    json=$(mold_backup_cmk_run createAblestackVeeamBackup "virtualmachineid=${vm_id}" "name=${backup_name}" "intervaltype=${interval_type}" "jobname=${veeam_job}" 2>/dev/null) \
      || json=$(mold_backup_cmk_run createAblestackVeeamBackup "virtualmachineid=${vm_id}" "name=${backup_name}" "intervaltype=${interval_type}" 2>/dev/null) \
      || json=$(mold_backup_cmk_run createBackup "virtualmachineid=${vm_id}" "name=${backup_name}" 2>/dev/null) \
      || return 1
  else
    json=$(mold_backup_cmk_run createAblestackVeeamBackup "virtualmachineid=${vm_id}" "name=${backup_name}" "intervaltype=${interval_type}" 2>/dev/null) \
      || json=$(mold_backup_cmk_run createBackup "virtualmachineid=${vm_id}" "name=${backup_name}" 2>/dev/null) \
      || return 1
  fi
  job_id="$(mold_backup_api_json_field "$json" "createablestackveeambackupresponse.jobid")"
  [[ -z "$job_id" ]] && job_id="$(mold_backup_api_json_field "$json" "createbackupresponse.jobid")"
  if [[ -n "$job_id" ]]; then
    mold_backup_notify_log info "createAblestackVeeamBackup job=${job_id}; waiting for MS/NAS backup timeout=${stage_timeout}s"
    json=$(mold_backup_api_wait_async_job "$job_id" "$stage_timeout") || return 1
    backup_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.id")"
    backup_type="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.type")"
    if [[ -z "$backup_id" ]]; then
      local latest
      # Wait for a NEW backup id (not in ids_before) to reach BackedUp — do not reuse older FULL.
      remaining_timeout=$((stage_timeout - ($(date +%s) - stage_started_at)))
      [[ "$remaining_timeout" -gt 0 ]] || return 1
      latest="$(mold_backup_api_wait_new_backup_for_vm "$vm_id" "$ids_before" "$remaining_timeout" 2>/dev/null || true)"
      if [[ -z "$latest" ]]; then
        latest="$(mold_backup_api_find_latest_backup_for_vm "$vm_id" "backedup" 2>/dev/null || true)"
      fi
      if [[ -n "$latest" ]]; then
        backup_id="${latest%%|*}"
        backup_type="${latest#*|}"
        mold_backup_notify_log info "Resolved backup_id=${backup_id} type=${backup_type} via listAblestackVeeamBackups (API returns SuccessResponse only)"
      fi
    fi
    [[ -n "$backup_id" ]] && { echo "${backup_id}|${backup_type:-User}"; return 0; }
  fi
  backup_id="$(mold_backup_api_json_field "$json" "createablestackveeambackupresponse.backup.id")"
  backup_type="$(mold_backup_api_json_field "$json" "createablestackveeambackupresponse.backup.type")"
  [[ -n "$backup_id" ]] && { echo "${backup_id}|${backup_type:-User}"; return 0; }
  return 1
}

mold_backup_api_list_backup_ids() {
  local vm_id="$1" json
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || return 0
  printf '%s\n' "$json" | python3 -c "
import json,sys
d=json.load(sys.stdin)
b=d.get('listablestackveeambackupsresponse',{}).get('backup',[])
if isinstance(b,dict): b=[b]
print(' '.join(x.get('id','') for x in b if x.get('id')))
" 2>/dev/null
}

mold_backup_api_wait_new_backup_for_vm() {
  local vm_id="$1" ids_before="${2:-}" max_wait="${3:-900}"
  local elapsed=0 latest bid btype st
  while [[ "$elapsed" -le "$max_wait" ]]; do
    latest="$(mold_backup_api_find_latest_backup_for_vm "$vm_id" "any" 2>/dev/null || true)"
    if [[ -n "$latest" ]]; then
      bid="${latest%%|*}"
      btype="${latest#*|}"
      btype="${btype%%|*}"
      if [[ " ${ids_before} " == *" ${bid} "* ]]; then
        :
      else
        st="$(mold_backup_api_backup_status "$vm_id" "$bid" 2>/dev/null || true)"
        st="$(printf '%s' "$st" | tr '[:upper:]' '[:lower:]')"
        if [[ "$st" == "backedup" ]]; then
          echo "${bid}|${btype}"
          return 0
        fi
        if [[ "$st" == "backingup" ]]; then
          local volume_path
          volume_path="$(mold_backup_api_backup_external_id "$vm_id" "$bid" 2>/dev/null || true)"
          if [[ -n "$volume_path" && -f "${volume_path}/.volume-bootstrap" ]]; then
            echo "${bid}|${btype}"
            return 0
          fi
          if [[ $((elapsed % 30)) -eq 0 ]]; then
            mold_backup_notify_log info "Waiting for new backup ${bid} (${btype}) BackingUp→BackedUp (elapsed=${elapsed}s)"
          fi
        elif [[ "$st" == "failed" || "$st" == "error" ]]; then
          mold_backup_notify_log err "New backup ${bid} ended as ${st}"
          return 1
        fi
      fi
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  return 1
}

mold_backup_api_backup_status() {
  local vm_id="$1" backup_id="$2" json
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || return 1
  printf '%s\n' "$json" | BACKUP_ID="$backup_id" python3 -c "
import json,sys,os
bid=os.environ.get('BACKUP_ID','')
d=json.load(sys.stdin)
b=d.get('listablestackveeambackupsresponse',{}).get('backup',[])
if isinstance(b,dict): b=[b]
for x in b:
  if x.get('id')==bid:
    print(x.get('status') or ''); break
" 2>/dev/null
}

mold_backup_api_backup_external_id() {
  local vm_id="$1" backup_id="$2" json
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || return 1
  printf '%s\n' "$json" | BACKUP_ID="$backup_id" python3 -c "
import json,sys,os
bid=os.environ.get('BACKUP_ID','')
d=json.load(sys.stdin)
b=d.get('listablestackveeambackupsresponse',{}).get('backup',[])
if isinstance(b,dict): b=[b]
for x in b:
  if x.get('id')==bid:
    print(x.get('externalid') or x.get('path') or ''); break
" 2>/dev/null
}

mold_backup_api_find_latest_backup_for_vm() {
  local vm_id="$1" status_filter="${2:-backedup}" json
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || return 1
  printf '%s\n' "$json" | STATUS_FILTER="$status_filter" python3 -c "
import json, sys, os
filt=(os.environ.get('STATUS_FILTER') or 'backedup').lower()
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict):
        b = [b]
    if filt != 'any':
        b = [x for x in b if str(x.get('status','')).lower() == filt]
    if not b:
        sys.exit(1)
    latest = max(b, key=lambda x: x.get('created') or '')
    bid = latest.get('id', '')
    btype = latest.get('type', 'User')
    if not bid:
        sys.exit(1)
    print(f\"{bid}|{btype}\")
except Exception:
    sys.exit(1)
" 2>/dev/null
}

mold_backup_api_count_backed_up_from_json() {
  local json="$1"
  printf '%s\n' "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict): b = [b]
    backed = [x for x in b if str(x.get('status', '')).lower() == 'backedup']
    print(len(backed))
except Exception:
    print(0)
" 2>/dev/null
}

mold_backup_api_list_backup_details() {
  local backup_id="$1"
  mold_backup_cmk_run listBackups "id=${backup_id}" "listvmdetails=true" 2>/dev/null
}

mold_backup_api_backup_detail_field() {
  local backup_id="$1" key="$2"
  local json
  json=$(mold_backup_api_list_backup_details "$backup_id") || return 1
  echo "$json" | python3 -c "
import json, sys
key = sys.argv[1]
try:
    d = json.load(sys.stdin)
    b = d.get('listbackupsresponse', {}).get('backup', {})
    if isinstance(b, list):
        b = b[0] if b else {}
    details = b.get('vmdetails') or b.get('vmDetails') or {}
    if isinstance(details, str):
        details = json.loads(details) if details else {}
    val = details.get(key, '')
    if not val and isinstance(b.get('details'), dict):
        val = b['details'].get(key, '')
    print(val or '')
except Exception:
    print('')
" "$key" 2>/dev/null
}

mold_backup_state_write_line() {
  local state_file="$1"
  shift
  echo "$*" >> "$state_file"
}

mold_backup_state_parse_field() {
  local line="$1" key="$2"
  if [[ "$line" != *"${key}="* ]]; then
    echo ""
    return 0
  fi
  local val="${line#*${key}=}"
  val="${val%% *}"
  echo "$val"
}

mold_backup_vm_backup_id_map_file() {
  echo "$(mold_backup_registry_dir)/vm-backup-ids.map"
}

mold_backup_registry_set_vm_backup_id() {
  local vm="$1" backup_id="$2" rp_id="${3:-}" job="${4:-${VEEAM_JOB_NAME:-}}"
  local map_file line key
  [[ -n "$vm" && -n "$backup_id" ]] || return 1
  map_file="$(mold_backup_vm_backup_id_map_file)"
  mkdir -p "$(dirname "$map_file")"
  key="vm=${vm} backup_id=${backup_id}"
  [[ -n "$rp_id" ]] && key="${key} rp=${rp_id}"
  [[ -n "$job" ]] && key="${key} job=${job}"
  if [[ -f "$map_file" ]] && grep -q " vm=${vm} " "$map_file" 2>/dev/null; then
    sed -i "s|.* vm=${vm} .*|$(date -Iseconds) ${key}|" "$map_file"
  else
    echo "$(date -Iseconds) ${key}" >> "$map_file"
  fi
  echo "${backup_id}" > "$(mold_backup_registry_dir)/${vm}.latest-backup-id"
  [[ -n "$rp_id" ]] && echo "${rp_id}" > "$(mold_backup_registry_dir)/${vm}.latest-rp-id"
  [[ -n "$rp_id" ]] && mold_backup_registry_index_rp_backup "$vm" "$rp_id" "$backup_id" "$job"
  mold_backup_sync_vm_backup_ids_conf "$vm" "$backup_id"
}

mold_backup_normalize_rp_id() {
  local rp="${1:-}"
  rp="${rp//\{/}"
  rp="${rp//\}/}"
  rp="$(printf '%s' "$rp" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  echo "$rp"
}

mold_backup_registry_rp_map_file() {
  echo "$(mold_backup_registry_dir)/veeam-rp-backup.map"
}

mold_backup_registry_index_rp_backup() {
  local vm="$1" rp_id="$2" backup_id="$3" job="${4:-${VEEAM_JOB_NAME:-}}"
  local map_file norm_rp
  [[ -n "$vm" && -n "$rp_id" && -n "$backup_id" ]] || return 0
  norm_rp="$(mold_backup_normalize_rp_id "$rp_id")"
  [[ -n "$norm_rp" ]] || return 0
  map_file="$(mold_backup_registry_rp_map_file)"
  mkdir -p "$(dirname "$map_file")"
  if [[ -f "$map_file" ]]; then
    grep -viE " rp=${norm_rp} vm=${vm} " "$map_file" > "${map_file}.tmp" 2>/dev/null || : >"${map_file}.tmp"
    mv -f "${map_file}.tmp" "$map_file" 2>/dev/null || true
  fi
  echo "$(date -Iseconds) rp=${norm_rp} vm=${vm} backup_id=${backup_id} job=${job}" >>"$map_file"
  echo "${backup_id}" >"$(mold_backup_registry_dir)/${vm}.rp-${norm_rp}.backup-id"
}

mold_backup_registry_index_checkpoint_backup() {
  local vm="$1" ckpt="$2" backup_id="$3"
  local reg_dir safe
  [[ -n "$vm" && -n "$ckpt" && -n "$backup_id" ]] || return 0
  reg_dir="$(mold_backup_registry_dir)"
  mkdir -p "$reg_dir"
  # checkpoint names are timestamp-like; strip path separators just in case
  safe="$(printf '%s' "$ckpt" | tr -c 'A-Za-z0-9._-' '_')"
  [[ -n "$safe" ]] || return 0
  echo "${backup_id}" >"${reg_dir}/${vm}.ckpt-${safe}.backup-id"
  echo "$(date -Iseconds) vm=${vm} ckpt=${ckpt} backup_id=${backup_id}" >>"${reg_dir}/checkpoint-backup.map"
}

mold_backup_sync_vm_backup_ids_conf() {
  return 0
}

mold_backup_registry_save_backup() {
  local job="$1" vm_name="$2" backup_id="$3" status="${4:-success}" rp_id="${5:-}" ckpt="${6:-}"
  local reg_dir reg_file
  reg_dir="$(mold_backup_registry_dir)"
  mkdir -p "$reg_dir"
  reg_file="${reg_dir}/$(mold_backup_safe_job_name "$job").log"
  echo "$(date -Iseconds) job=${job} vm=${vm_name} backup_id=${backup_id} status=${status}" >> "$reg_file"
  mold_backup_registry_set_vm_backup_id "$vm_name" "$backup_id" "$rp_id" "$job"
  if [[ -z "$ckpt" ]]; then
    ckpt="$(sed -n 's/.*ckpt=\([^ ]*\).*/\1/p' <<<"$status" | tail -1)"
  fi
  if [[ -z "$ckpt" ]]; then
    ckpt="$(sed -n "s|.*/${vm_name}/\\([0-9.]\\{10,\\}\\).*|\\1|p" <<<"$status" | tail -1)"
  fi
  if [[ -z "$ckpt" ]]; then
    ckpt="$(mold_backup_api_backup_detail_field "$backup_id" "ablestack.veeam.checkpoint.name" 2>/dev/null || true)"
  fi
  if [[ -z "$ckpt" ]]; then
    local ext
    ext="$(mold_backup_api_backup_external_id "$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)" "$backup_id" 2>/dev/null || true)"
    [[ -n "$ext" ]] && ckpt="$(basename "$ext")"
  fi
  [[ -n "$ckpt" ]] && mold_backup_registry_index_checkpoint_backup "$vm_name" "$ckpt" "$backup_id"
  mold_backup_notify_log info "Saved backup registry: vm=${vm_name} backup_id=${backup_id} status=${status} ckpt=${ckpt:-n/a} rp=${rp_id:-n/a}"
}

mold_backup_trigger_dir() {
  local d="$(mold_backup_state_dir)/triggers"
  mkdir -p "$d" 2>/dev/null || true
  echo "$d"
}

mold_backup_trigger_mark() {
  local kind="$1" vm="$2"
  date +%s > "$(mold_backup_trigger_dir)/${kind}.$(mold_backup_safe_job_name "$vm")" 2>/dev/null || true
}

mold_backup_trigger_active() {
  local kind="$1" vm="$2" ttl="${3:-${VEEAM_TRIGGER_TTL:-1800}}"
  local f ts now
  f="$(mold_backup_trigger_dir)/${kind}.$(mold_backup_safe_job_name "$vm")"
  [[ -f "$f" ]] || return 1
  ts="$(cat "$f" 2>/dev/null || echo 0)"
  now="$(date +%s)"
  if (( now - ts > ttl )); then
    rm -f "$f" 2>/dev/null || true
    return 1
  fi
  return 0
}

mold_backup_trigger_clear() {
  rm -f "$(mold_backup_trigger_dir)/${1}.$(mold_backup_safe_job_name "$2")" 2>/dev/null || true
}

mold_backup_vm_guest_ip() {
  local vm="$1" pair name ip targets env_file list
  for list in "${VM_TARGETS:-}"; do
    [[ -n "$list" ]] || continue
    IFS=',' read -ra _pairs <<<"${list}"
    for pair in "${_pairs[@]}"; do
      pair="${pair// /}"
      name="${pair%%:*}"
      ip="${pair#*:}"
      if [[ "$name" == "$vm" && -n "$ip" && "$ip" != "$name" ]]; then
        echo "$ip"
        return 0
      fi
    done
  done
  for env_file in \
    "${ABLESTACK_VEEAM_ETC_DIR}/mold-backup.env" \
    "${MOLD_BACKUP_ETC_DIR}/mold-backup.env" \
    "$(dirname "${BASH_SOURCE[0]}")/mold-backup.env"; do
    [[ -f "$env_file" ]] || continue
    targets="$(mold_backup_read_env_var VM_TARGETS "$env_file" 2>/dev/null || true)"
    [[ -n "$targets" ]] || continue
    IFS=',' read -ra _pairs <<<"${targets}"
    for pair in "${_pairs[@]}"; do
      pair="${pair// /}"
      name="${pair%%:*}"
      ip="${pair#*:}"
      if [[ "$name" == "$vm" && -n "$ip" && "$ip" != "$name" ]]; then
        echo "$ip"
        return 0
      fi
    done
  done
  return 1
}

mold_backup_veeam_job_name_for_vm() {
  local vm="$1" prefix="${VEEAM_GUEST_JOB_PREFIX:-Mold VM}"
  echo "${prefix} ${vm}"
}

mold_backup_vm_name_for_ip() {
  local want="$1" pair name ip
  [[ -n "${VM_TARGETS:-}" ]] || return 1
  IFS=',' read -ra _pairs <<<"${VM_TARGETS}"
  for pair in "${_pairs[@]}"; do
    pair="${pair// /}"
    name="${pair%%:*}"
    ip="${pair#*:}"
    if [[ "$ip" == "$want" && -n "$name" && "$ip" != "$name" ]]; then
      echo "$name"
      return 0
    fi
  done
  return 1
}

mold_backup_veeam_api_base() {
  if [[ -n "${VEEAM_API_URL:-}" ]]; then
    echo "${VEEAM_API_URL%/}"
    return 0
  fi
  local host="${VEEAM_API_HOST:-${VEEAM_SSH_HOST:-}}"
  [[ -n "$host" ]] || return 1
  echo "https://${host}:${VEEAM_API_PORT:-9419}"
}

mold_backup_trigger_veeam_job_rest() {
  local vm="$1" job="$2"
  local api ver user pass token job_id running
  api="$(mold_backup_veeam_api_base)" || {
    mold_backup_notify_log warn "Mold→Veeam(REST): no VEEAM_API_URL/VEEAM_API_HOST/VEEAM_SSH_HOST"
    return 1
  }
  ver="${VEEAM_API_VERSION:-1.2-rev0}"
  user="${VEEAM_API_USER:-${VEEAM_USERNAME:-administrator}}"
  pass="${VEEAM_API_PASSWORD:-${VEEAM_PASSWORD:-}}"
  [[ -n "$pass" ]] || {
    mold_backup_notify_log warn "Mold→Veeam(REST): VEEAM_API_PASSWORD/VEEAM_PASSWORD not set"
    return 1
  }

  token="$(curl -sk --max-time 30 -X POST "${api}/api/oauth2/token" \
    -H "x-api-version: ${ver}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&username=${user}&password=${pass}" 2>/dev/null \
    | python3 -c "import sys,json
try: print(json.load(sys.stdin).get('access_token',''))
except Exception: print('')" 2>/dev/null)"
  [[ -n "$token" ]] || {
    mold_backup_notify_log warn "Mold→Veeam(REST): auth failed at ${api} (check x-api-version='${ver}', user, port 9419)"
    return 1
  }

  # Find the job id + running state by exact name.
  local jobs_json
  jobs_json="$(curl -sk --max-time 30 "${api}/api/v1/jobs" \
    -H "x-api-version: ${ver}" -H "Authorization: Bearer ${token}" 2>/dev/null)"
  read -r job_id running <<<"$(echo "$jobs_json" | python3 -c "
import sys,json
want='''${job}'''
try:
    d=json.load(sys.stdin)
except Exception:
    print(''); sys.exit()
items=d.get('data', d if isinstance(d,list) else [])
for j in items:
    if j.get('name')==want:
        st=str(j.get('status') or j.get('lastResult') or '')
        print(j.get('id',''), 'running' if str(j.get('isRunning','')).lower()=='true' or st.lower()=='running' else 'idle'); break
" 2>/dev/null)"
  [[ -n "$job_id" ]] || {
    mold_backup_notify_log warn "Mold→Veeam(REST): job '${job}' not found in /api/v1/jobs (Agent jobs may need SSH); will fall back"
    return 1
  }
  if [[ "$running" == "running" ]]; then
    mold_backup_notify_log info "Mold→Veeam(REST): job '${job}' already running"
    return 0
  fi

  local http_code
  http_code="$(curl -sk --max-time 30 -o /dev/null -w '%{http_code}' -X POST \
    "${api}/api/v1/jobs/${job_id}/start" \
    -H "x-api-version: ${ver}" -H "Authorization: Bearer ${token}" 2>/dev/null)"
  if [[ "$http_code" =~ ^20[0-9]$ ]]; then
    mold_backup_notify_log info "Mold→Veeam(REST): job '${job}' start accepted (HTTP ${http_code})"
    return 0
  fi
  mold_backup_notify_log warn "Mold→Veeam(REST): start failed for '${job}' (HTTP ${http_code})"
  return 1
}

mold_backup_veeam_scp() {
  local src="$1" dst="$2"
  local ssh_key_opt=() askpass_file=""
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || return 1
  [[ -f "$src" ]] || return 1
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_key_opt=(-i "${VEEAM_SSH_KEY}")
  local -a scp_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
  if [[ "${VEEAM_SSH_STRICT_HOSTKEY:-false}" == "true" ]]; then
    scp_opts=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=30)
  fi
  local remote="${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}"
  if [[ ${#ssh_key_opt[@]} -gt 0 ]]; then
    scp "${scp_opts[@]}" "${ssh_key_opt[@]}" "$src" "${remote}:${dst}"
    return $?
  fi
  if [[ -n "${VEEAM_SSH_PASSWORD:-}" ]] && command -v sshpass >/dev/null 2>&1; then
    SSHPASS="${VEEAM_SSH_PASSWORD}" sshpass -e scp "${scp_opts[@]}" \
      -o PreferredAuthentications=password -o PubkeyAuthentication=no "$src" "${remote}:${dst}"
    return $?
  fi
  if [[ -n "${VEEAM_SSH_PASSWORD:-}" ]]; then
    askpass_file="$(mktemp /tmp/veeam-askpass.XXXXXX)"
    printf '%s\n' '#!/bin/bash' "printf '%s\\n' $(printf '%q' "${VEEAM_SSH_PASSWORD}")" >"$askpass_file"
    chmod 700 "$askpass_file"
    SSH_ASKPASS="$askpass_file" SSH_ASKPASS_REQUIRE=force DISPLAY="${DISPLAY:-:0}" \
      setsid -w scp "${scp_opts[@]}" \
      -o PreferredAuthentications=password -o PubkeyAuthentication=no \
      -o NumberOfPasswordPrompts=1 "$src" "${remote}:${dst}"
    local rc=$?
    rm -f "$askpass_file"
    return $rc
  fi
  scp "${scp_opts[@]}" -o BatchMode=yes "$src" "${remote}:${dst}"
}

mold_backup_veeam_ssh_ps_capture() {
  # Prints remote stdout; returns ssh/pwsh rc.
  local ps_script="$1"
  local ps_enc local_tmp remote_name out rc
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || return 1
  [[ -n "$ps_script" ]] || return 1
  # Prefer file transfer when EncodedCommand would exceed ~6k (Windows CreateProcess limit).
  if (( ${#ps_script} > 2500 )); then
    local_tmp="$(mktemp /tmp/mold-veeam-ps.XXXXXX.ps1)"
    printf '%s\n' "$ps_script" >"$local_tmp"
    remote_name="mold-veeam-$(date +%s)-$$.ps1"
    if mold_backup_veeam_scp "$local_tmp" "C:/Windows/Temp/${remote_name}"; then
      out="$(mold_backup_veeam_ssh_cmd "pwsh -NoProfile -File C:\\Windows\\Temp\\${remote_name}" 2>&1)" && rc=0 || rc=$?
      if [[ $rc -ne 0 ]]; then
        out="$(mold_backup_veeam_ssh_cmd "powershell.exe -NoProfile -File C:\\Windows\\Temp\\${remote_name}" 2>&1)" && rc=0 || rc=$?
      fi
      # Cleanup must not block restore-watch; auth hang previously stuck oneshot for hours.
      VEEAM_SSH_CMD_TIMEOUT="${VEEAM_SSH_CLEANUP_TIMEOUT:-20}" \
        mold_backup_veeam_ssh_cmd "del /f C:\\Windows\\Temp\\${remote_name}" >/dev/null 2>&1 || true
      rm -f "$local_tmp"
      printf '%s' "$out"
      return $rc
    fi
    rm -f "$local_tmp"
  fi
  ps_enc="$(printf '%s' "$ps_script" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null | base64 -w0 2>/dev/null)"
  [[ -n "$ps_enc" ]] || return 1
  mold_backup_veeam_ssh_encoded "$ps_enc"
}

mold_backup_veeam_ssh_ps() {
  local ps_script="$1"
  mold_backup_veeam_ssh_ps_capture "$ps_script" >/dev/null
}

mold_backup_veeam_need_active_full_file() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  echo "$(mold_backup_trigger_dir)/need-active-full.$(mold_backup_safe_job_name "$job")"
}

mold_backup_veeam_active_full_attempted_file() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  echo "$(mold_backup_trigger_dir)/active-full-attempted.$(mold_backup_safe_job_name "$job")"
}

mold_backup_veeam_active_full_already_attempted() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  [[ -f "$(mold_backup_veeam_active_full_attempted_file "$job")" ]]
}

mold_backup_veeam_mark_active_full_attempted() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  [[ -n "$job" ]] || return 0
  date +%s > "$(mold_backup_veeam_active_full_attempted_file "$job")" 2>/dev/null || true
}

mold_backup_veeam_clear_active_full_attempted() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  rm -f "$(mold_backup_veeam_active_full_attempted_file "$job")" 2>/dev/null || true
}

mold_backup_veeam_mark_active_full_cooldown() {
  mold_backup_veeam_mark_active_full_attempted "$@"
}

mold_backup_veeam_mark_need_active_full() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  [[ -n "$job" ]] || return 0
  if mold_backup_veeam_active_full_already_attempted "$job"; then
    return 0
  fi
  date +%s > "$(mold_backup_veeam_need_active_full_file "$job")" 2>/dev/null || true
  mold_backup_notify_log info "Marked need-active-full for job=${job} (empty Disk / broken Agent chain)"
}

mold_backup_veeam_clear_need_active_full() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  rm -f "$(mold_backup_veeam_need_active_full_file "$job")" 2>/dev/null || true
}

mold_backup_veeam_need_active_full() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local status f
  [[ "${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}" == "true" ]] || return 1
  [[ -n "$job" ]] || return 1
  if mold_backup_veeam_active_full_already_attempted "$job"; then
    return 1
  fi
  f="$(mold_backup_veeam_need_active_full_file "$job")"
  [[ -f "$f" ]] && return 0
  status="$(mold_backup_veeam_job_disk_backup_status "$job" 2>/dev/null || true)"
  [[ "$status" == "NO_BACKUP" ]]
}

mold_backup_veeam_arm_active_full_from_agent_log() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local f log=/var/log/veeam/veeamsvc.log status
  [[ "${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}" == "true" ]] || return 1
  [[ -n "$job" && -f "$log" ]] || return 1
  if mold_backup_veeam_active_full_already_attempted "$job"; then
    return 1
  fi
  # Disk already rebuilt — clear latch/flags; do not re-arm from stale log lines.
  status="$(mold_backup_veeam_job_disk_backup_status "$job" 2>/dev/null || true)"
  if [[ "$status" == "HAS_BACKUP" ]]; then
    mold_backup_veeam_clear_need_active_full "$job"
    mold_backup_veeam_clear_active_full_attempted "$job"
    return 1
  fi
  f="$(mold_backup_veeam_need_active_full_file "$job")"
  [[ -f "$f" ]] && return 0
  if [[ "$(find "$log" -mmin -120 -print 2>/dev/null)" == "$log" ]] \
      && grep -q 'IsBackupFilesystemExist' "$log" 2>/dev/null; then
    if grep 'IsBackupFilesystemExist' "$log" 2>/dev/null | tail -5 \
        | grep -qE "$(date '+%d.%m.%Y')|$(date -u '+%d.%m.%Y')" 2>/dev/null; then
      mold_backup_veeam_mark_need_active_full "$job"
      return 0
    fi
  fi
  return 1
}

mold_backup_start_veeam_computer_job_ssh() {
  local job="$1" full="${2:-false}" job_esc ps_script full_switch=""
  [[ -n "${VEEAM_SSH_HOST:-}" && -n "$job" ]] || return 1
  job_esc="${job//\'/\'\'}"
  [[ "$full" == "true" ]] && full_switch=" -FullBackup"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'Stop'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
\$j = Get-VBRComputerBackupJob -Name '${job_esc}'
if (-not \$j) { Write-Error "job not found: ${job_esc}"; exit 2 }
if (\$j.IsRunning) { Write-Host 'already running'; exit 0 }
Start-VBRComputerBackupJob -Job \$j${full_switch} -RunAsync | Out-Null
Write-Host 'started'
PS
)"
  if mold_backup_veeam_ssh_ps "$ps_script"; then
    mold_backup_notify_log info "Veeam job '${job}' start requested OK (activeFull=${full})"
    return 0
  fi
  mold_backup_notify_log warn "Veeam SSH start failed for job='${job}' (activeFull=${full})"
  return 1
}

mold_backup_start_veeam_computer_job_local() {
  local job="$1" full="${2:-false}"
  command -v veeamconfig >/dev/null 2>&1 || return 1
  [[ -n "$job" ]] || return 1
  if [[ "$full" == "true" ]]; then
    veeamconfig job start --name "$job" --activefull >/dev/null 2>&1 \
      && { mold_backup_notify_log info "Local veeamconfig Active Full started for '${job}'"; return 0; }
  else
    veeamconfig job start --name "$job" >/dev/null 2>&1 \
      && { mold_backup_notify_log info "Local veeamconfig start for '${job}'"; return 0; }
  fi
  return 1
}

mold_backup_veeam_ensure_active_full_if_needed() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  [[ -n "$job" ]] || return 0
  [[ "${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}" == "true" ]] || return 0
  if ! mold_backup_veeam_need_active_full "$job"; then
    return 0
  fi
  mold_backup_veeam_mark_need_active_full "$job"
  mold_backup_notify_log info "Ensuring Active Full for job=${job} (empty Disk or IsBackupFilesystemExist)"
  # Latch BEFORE start so a slow/hanging Start cannot be retried by the next catalog-sync.
  mold_backup_veeam_mark_active_full_attempted "$job"
  if mold_backup_start_veeam_computer_job_ssh "$job" true; then
    mold_backup_veeam_clear_need_active_full "$job"
    return 0
  fi
  if mold_backup_start_veeam_computer_job_local "$job" true; then
    mold_backup_veeam_clear_need_active_full "$job"
    return 0
  fi
  mold_backup_notify_log warn "Active Full start failed for job=${job} — latch kept (no auto-retry until HAS_BACKUP or manual active-full)"
  return 1
}

mold_backup_trigger_veeam_job_ssh() {
  local vm="$1" job="$2" full=false
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "Mold→Veeam(SSH): VEEAM_SSH_HOST not set"
    return 1
  }
  if mold_backup_veeam_need_active_full "$job"; then
    full=true
    mold_backup_notify_log info "Mold→Veeam(SSH): Disk empty/broken chain → Active Full for '${job}'"
  fi
  if mold_backup_start_veeam_computer_job_ssh "$job" "$full"; then
    [[ "$full" == "true" ]] && {
      mold_backup_veeam_clear_need_active_full "$job"
      mold_backup_veeam_mark_active_full_cooldown "$job"
    }
    mold_backup_notify_log info "Mold→Veeam(SSH): job '${job}' start requested OK"
    return 0
  fi
  if [[ "$full" == "true" ]] && mold_backup_start_veeam_computer_job_local "$job" true; then
    mold_backup_veeam_clear_need_active_full "$job"
    mold_backup_veeam_mark_active_full_cooldown "$job"
    return 0
  fi
  mold_backup_notify_log warn "Mold→Veeam(SSH): failed to start job '${job}' (check pwsh path / VEEAM_SSH_HOST)"
  return 1
}

mold_backup_trigger_veeam_job() {
  local vm="$1" ip job method rc=1
  [[ "${VEEAM_TRIGGER_ENABLED:-false}" == "true" ]] || return 0
  ip="$(mold_backup_vm_guest_ip "$vm" 2>/dev/null || true)"
  [[ -n "$ip" ]] || {
    mold_backup_notify_log warn "No guest IP for ${vm} in VM_TARGETS; skip Veeam job start"
    return 0
  }
  job="$(mold_backup_veeam_job_name_for_vm "$vm")"
  if [[ "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
    method="${VEEAM_TRIGGER_METHOD:-ssh}"
  else
    method="${VEEAM_TRIGGER_METHOD:-auto}"
  fi

  mold_backup_trigger_mark "mold-active" "$vm"
  mold_backup_notify_log info "Mold→Veeam: starting Veeam job '${job}' for ${vm} (${ip}) method=${method}"

  case "$method" in
    rest) mold_backup_trigger_veeam_job_rest "$vm" "$job"; rc=$? ;;
    ssh)  mold_backup_trigger_veeam_job_ssh  "$vm" "$job"; rc=$? ;;
    auto|*)
      mold_backup_trigger_veeam_job_rest "$vm" "$job"; rc=$?
      if [[ $rc -ne 0 ]]; then
        mold_backup_notify_log info "Mold→Veeam: REST failed/unavailable, trying SSH fallback"
        mold_backup_trigger_veeam_job_ssh "$vm" "$job"; rc=$?
      fi
      ;;
  esac

  if [[ $rc -ne 0 ]]; then
    mold_backup_trigger_clear "mold-active" "$vm"
    mold_backup_notify_log warn "Mold→Veeam: could not start job '${job}' (cleared mold-active for ${vm})"
  fi
  return $rc
}

mold_backup_restore_watch_state() {
  local d="$(mold_backup_state_dir)/restore-watch"
  mkdir -p "$d" 2>/dev/null || true
  echo "$d/processed-sessions"
}

mold_backup_restore_session_seen() {
  local sid="$1" f
  f="$(mold_backup_restore_watch_state)"
  [[ -f "$f" ]] || return 1
  grep -qxF "$sid" "$f" 2>/dev/null
}

mold_backup_restore_session_mark_seen() {
  local sid="$1" f
  f="$(mold_backup_restore_watch_state)"
  echo "$sid" >> "$f" 2>/dev/null || true
  # keep the file bounded
  if [[ -f "$f" ]] && (( $(wc -l <"$f" 2>/dev/null || echo 0) > 2000 )); then
    tail -n 1000 "$f" > "${f}.tmp" 2>/dev/null && mv -f "${f}.tmp" "$f" 2>/dev/null || true
  fi
}

mold_backup_restore_watch_baseline_marker() {
  local state_file
  state_file="$(mold_backup_restore_watch_state)"
  echo "${state_file}.baseline-complete"
}

mold_backup_initialize_restore_watch_baseline() {
  local since_min="${1:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}"
  local marker sid _rest marked=0
  [[ "${RESTORE_WATCH_SKIP_EXISTING_ON_START:-true}" == "true" ]] || return 0
  marker="$(mold_backup_restore_watch_baseline_marker)"
  [[ -f "$marker" ]] && return 0

  while IFS='|' read -r sid _rest; do
    [[ -n "$sid" ]] || continue
    if [[ "$sid" =~ ^[0-9a-fA-F-]{8,}$ || "$sid" == local-flr-* ]]; then
      mold_backup_restore_session_mark_seen "$sid"
      marked=$((marked+1))
    fi
  done < <(
    mold_backup_query_veeam_restores "$since_min" 2>/dev/null || true
  )
  date -u +%FT%TZ > "$marker" 2>/dev/null || true
  mold_backup_notify_log info "Veeam restore watcher initial baseline completed sessions=${marked}"
}

mold_backup_query_veeam_restores() {
  local since_min="${1:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}"
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "Veeam→Mold(restore): VEEAM_SSH_HOST not set"
    return 1
  }
  local ssh_key_opt=()
  if [[ -n "${VEEAM_SSH_KEY:-}" ]]; then
    if [[ -f "${VEEAM_SSH_KEY}" ]]; then
      ssh_key_opt=(-i "${VEEAM_SSH_KEY}")
    else
      mold_backup_notify_log warn "Veeam→Mold(restore): VEEAM_SSH_KEY=${VEEAM_SSH_KEY} missing; using default SSH keys"
    fi
  fi
  local -a ssh_host_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
  if [[ "${VEEAM_SSH_STRICT_HOSTKEY:-false}" == "true" ]]; then
    ssh_host_opts=(-o StrictHostKeyChecking=accept-new)
  fi
  # PowerShell run on the Veeam B&R server: connect (warm-up loop), then emit every
  # completed restore session as "Id|ip|epoch|result|name|backup|rpId". Falls back to
  # Export-VBRAudit when Get-VBRRestoreSession is empty (common for Agent FLR over SSH).
  local ps_script since_min_ps
  since_min_ps="${since_min}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'SilentlyContinue'
\$SinceMin = ${since_min_ps}
\$cutoff = (Get-Date).ToUniversalTime().AddMinutes(-1 * \$SinceMin)
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
\$emitted = @{}
function Emit-RestoreLine {
  param([string]\$Sid, [string]\$Ip, [long]\$Epoch, [string]\$Result, [string]\$Name, [string]\$Backup, [string]\$RpId, [string]\$RpEpoch = '')
  if (-not \$Sid) { return }
  if (\$emitted.ContainsKey(\$Sid)) { return }
  \$emitted[\$Sid] = \$true
  \$n = (\$Name -replace '[\|\r\n]',' ')
  \$b = (\$Backup -replace '[\|\r\n]',' ')
  \$r = (\$Result -replace '[\|\r\n]',' ')
  \$rp = (\$RpId -replace '[\|\r\n]','').Trim()
  \$rpe = (\$RpEpoch -replace '[\|\r\n]','').Trim()
  "\$Sid|\$Ip|\$Epoch|\$r|\$n|\$b|\$rp|\$rpe"
}
function Get-RpInfoFromSession {
  param(\$Session)
  \$rpId = ''
  \$rpEpoch = ''
  function Rp-ToEpoch([datetime]\$ct) {
    if (\$null -eq \$ct) { return '' }
    try {
      # Prefer UTC wall-clock: Unspecified CreationTime from Veeam is often UTC-like.
      if (\$ct.Kind -eq [DateTimeKind]::Utc) {
        return [string]([int64]([DateTimeOffset]\$ct).ToUnixTimeSeconds())
      }
      if (\$ct.Kind -eq [DateTimeKind]::Local) {
        return [string]([int64]([DateTimeOffset]\$ct).ToUnixTimeSeconds())
      }
      # Unspecified: interpret as UTC first (Agent RP CreationTimeUTC path), then Local.
      \$asUtc = [DateTime]::SpecifyKind(\$ct, [DateTimeKind]::Utc)
      return [string]([int64]([DateTimeOffset]\$asUtc).ToUnixTimeSeconds())
    } catch { return '' }
  }
  try {
    if (\$null -ne \$Session.RestorePoint) {
      \$x = \$Session.RestorePoint.Id
      if (\$null -ne \$x) { if (\$x -is [guid]) { \$rpId = \$x.Guid } else { \$rpId = [string]\$x } }
      try {
        \$ct = \$Session.RestorePoint.CreationTimeUTC
        if (\$null -eq \$ct) { \$ct = \$Session.RestorePoint.CreationTime }
        \$rpEpoch = Rp-ToEpoch \$ct
      } catch {}
    }
  } catch {}
  # Agent Host FLR may expose the restore point only through Options.FlrInfo.
  \$opt = [string]\$Session.Options
  if (-not \$rpId -and \$opt) {
    foreach (\$pat in @(
      'RestorePointId="([^"]+)"',
      'ObjectRestorePointId="([^"]+)"',
      'ObjectRestorePointOib="([^"]+)"',
      'PointId="([^"]+)"',
      'restorePointId=''([^'']+)''',
      'RestorePointUid="([^"]+)"',
      'OibId="([^"]+)"'
    )) {
      \$m = [regex]::Match(\$opt, \$pat)
      if (\$m.Success) { \$rpId = \$m.Groups[1].Value; break }
    }
  }
  if (-not \$rpEpoch -and \$opt) {
    foreach (\$pat in @(
      'OibCreationTimeUtc="([^"]+)"',
      'OibDT="([^"]+)"',
      'CreationTimeUtc="([^"]+)"'
    )) {
      \$m = [regex]::Match(\$opt, \$pat)
      if (-not \$m.Success) { continue }
      try {
        \$ct = [datetime]::Parse(\$m.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
        if (\$ct.Kind -eq [DateTimeKind]::Unspecified) {
          \$ct = [DateTime]::SpecifyKind(\$ct, [DateTimeKind]::Utc)
        }
        \$rpEpoch = Rp-ToEpoch \$ct
        if (\$rpEpoch) { break }
      } catch {}
    }
  }
  if (-not \$rpId -and \$null -ne \$Session.RestorePointId) {
    \$x = \$Session.RestorePointId
    if (\$x -is [guid]) { \$rpId = \$x.Guid } else { \$rpId = [string]\$x }
  }
  try {
    if (-not \$rpId -and \$null -ne \$Session.Info -and \$null -ne \$Session.Info.RestorePointId) {
      \$x = \$Session.Info.RestorePointId
      if (\$x -is [guid]) { \$rpId = \$x.Guid } else { \$rpId = [string]\$x }
    }
  } catch {}
  try {
    if (-not \$rpId -and \$null -ne \$Session.Info -and \$null -ne \$Session.Info.ObjectRestorePointId) {
      \$x = \$Session.Info.ObjectRestorePointId
      if (\$x -is [guid]) { \$rpId = \$x.Guid } else { \$rpId = [string]\$x }
    }
  } catch {}
  if (-not \$rpId) {
    try {
      foreach (\$p in @(\$Session.PSObject.Properties)) {
        if (\$p.Name -match '(?i)restorepoint' -and \$null -ne \$p.Value) {
          \$v = \$p.Value
          if (\$v -is [guid]) { \$rpId = \$v.Guid; break }
          if (\$v.PSObject.Properties['Id']) {
            \$x = \$v.Id
            if (\$x -is [guid]) { \$rpId = \$x.Guid } else { \$rpId = [string]\$x }
            if (\$rpId) {
              try {
                \$ct = \$null
                if (\$v.PSObject.Properties['CreationTimeUTC'] -and \$null -ne \$v.CreationTimeUTC) { \$ct = \$v.CreationTimeUTC }
                elseif (\$v.PSObject.Properties['CreationTime'] -and \$null -ne \$v.CreationTime) { \$ct = \$v.CreationTime }
                \$rpEpoch = Rp-ToEpoch \$ct
              } catch {}
              break
            }
          }
        }
      }
    } catch {}
  }
  if (\$rpId -and -not \$rpEpoch) {
    try {
      \$want = \$rpId.Trim('{}').ToLower()
      foreach (\$b in @(Get-VBRBackup -ErrorAction SilentlyContinue)) {
        foreach (\$cand in @(\$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue)) {
          \$id = \$cand.Id; if (\$id -is [guid]) { \$id = \$id.Guid }
          if (([string]\$id).Trim('{}').ToLower() -eq \$want) {
            \$ct = \$cand.CreationTimeUTC
            if (\$null -eq \$ct) { \$ct = \$cand.CreationTime }
            \$rpEpoch = Rp-ToEpoch \$ct
            break
          }
        }
        if (\$rpEpoch) { break }
      }
      if (-not \$rpEpoch) {
        foreach (\$cand in @(Get-VBRObjectRestorePoint -ErrorAction SilentlyContinue)) {
          \$id = \$cand.Id; if (\$id -is [guid]) { \$id = \$id.Guid }
          if (([string]\$id).Trim('{}').ToLower() -eq \$want) {
            \$ct = \$null
            try { if (\$null -ne \$cand.CreationTimeUTC) { \$ct = \$cand.CreationTimeUTC } } catch {}
            if (\$null -eq \$ct) { try { \$ct = \$cand.CreationTime } catch {} }
            \$rpEpoch = Rp-ToEpoch \$ct
            if (\$rpEpoch) { break }
          }
        }
      }
    } catch {}
  }
  return @{ Id = \$rpId; Epoch = \$rpEpoch }
}
function Valid-RestoreEnd([object]\$dt) {
  if (\$null -eq \$dt) { return \$false }
  try { return ([datetime]\$dt).Year -ge 2000 } catch { return \$false }
}
\$sessions = @()
for (\$k = 0; \$k -lt 12; \$k++) {
  try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
  \$sessions = @(Get-VBRRestoreSession)
  if (\$sessions.Count -gt 0) { break }
  Start-Sleep -Milliseconds 700
}
\$sessions = @(Get-VBRRestoreSession)
foreach (\$r in \$sessions) {
  if (\$null -eq \$r) { continue }
  \$rs = [string]\$r.Result
  # In-progress FLR browse sessions report Result=None with EndTime=DateTime.MinValue.
  # Emitting those made Mold stop the VM mid-session and disconnect Veeam UI/agent.
  if (\$rs -notmatch '^(?i)(Success|Warning|Failed|Canceled|Cancelled)\$') { continue }
  \$hasEnd = (Valid-RestoreEnd \$r.EndTime) -or (Valid-RestoreEnd \$r.EndTimeUTC)
  \$done = \$false
  try { \$done = [bool]\$r.IsCompleted } catch { \$done = \$hasEnd }
  if (-not \$done -and -not \$hasEnd) { continue }
  \$opt = [string]\$r.Options
  \$ip = ''
  foreach (\$pat in @('IpOrDnsName="([^"]+)"','MachineName="([^"]+)"','DisplayName="([^"]+)"')) {
    \$m = [regex]::Match(\$opt, \$pat)
    if (\$m.Success -and (\$m.Groups[1].Value -match '^\d{1,3}(\.\d{1,3}){3}\$')) { \$ip = \$m.Groups[1].Value; break }
  }
  if (-not \$ip) {
    \$m = [regex]::Match(\$opt, '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})')
    if (\$m.Success) { \$ip = \$m.Groups[1].Value }
  }
  \$bn = ''
  \$mb = [regex]::Match(\$opt, 'BackupName="([^"]+)"')
  if (\$mb.Success) { \$bn = \$mb.Groups[1].Value }
  \$rpInfo = Get-RpInfoFromSession -Session \$r
  \$rpId = [string]\$rpInfo.Id
  \$rpEpoch = [string]\$rpInfo.Epoch
  \$nm = [string]\$r.Name
  \$epoch = 0
  \$etObj = \$null
  if (Valid-RestoreEnd \$r.EndTime) { \$etObj = \$r.EndTime }
  elseif (Valid-RestoreEnd \$r.EndTimeUTC) { \$etObj = \$r.EndTimeUTC }
  try { if (\$null -ne \$etObj) { \$epoch = [int64]([DateTimeOffset]\$etObj).ToUnixTimeSeconds() } } catch { \$epoch = 0 }
  if (\$epoch -le 0) { continue }
  \$sid = [string]\$r.Id
  if (\$r.Id -is [guid]) { \$sid = \$r.Id.Guid }
  Emit-RestoreLine -Sid \$sid -Ip \$ip -Epoch \$epoch -Result \$rs -Name \$nm -Backup \$bn -RpId \$rpId -RpEpoch \$rpEpoch
}
# Agent / Backup Browser FLR often missing from Get-VBRRestoreSession over SSH — parse audit.
try {
  if (Get-Command Export-VBRAudit -ErrorAction SilentlyContinue) {
    \$tmp = Join-Path \$env:TEMP ("mold-restore-audit-" + [guid]::NewGuid().ToString() + ".csv")
    Export-VBRAudit -From \$cutoff -To (Get-Date).ToUniversalTime() -FileFullPath \$tmp -ErrorAction SilentlyContinue | Out-Null
    if (Test-Path \$tmp) {
      Import-Csv \$tmp | Where-Object {
        \$op = [string]\$_.Operation
        \$res = [string]\$_.Result
        \$res -match '^(?i)(Success|Warning|Failed|Canceled|Cancelled)\$' -and (
          \$op -match '(?i)FileLevel|GuestFile|FileRestore|VmRestore|Restore'
        )
      } | ForEach-Object {
        \$det = [string]\$_.Details
        \$sid = ''
        \$m = [regex]::Match(\$det, "sessionUid='([^']+)'")
        if (\$m.Success) { \$sid = \$m.Groups[1].Value }
        if (-not \$sid) { \$sid = [string]\$_.SessionUid }
        if (-not \$sid) { \$sid = "audit-" + [guid]::NewGuid().ToString() }
        \$ip = ''
        \$m = [regex]::Match(\$det, '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})')
        if (\$m.Success) { \$ip = \$m.Groups[1].Value }
        \$nm = ''
        \$m = [regex]::Match(\$det, "vmName='([^']+)'")
        if (\$m.Success) { \$nm = \$m.Groups[1].Value }
        if (-not \$nm) { \$nm = [string]\$_.Operation }
        \$epoch = 0
        try {
          \$t = [datetime]\$_.Time
          \$epoch = [int64]([DateTimeOffset]\$t).ToUnixTimeSeconds()
        } catch {}
        \$rpId = ''
        \$rpEpoch = ''
        \$rsess = \$null
        try { \$rsess = Get-VBRRestoreSession -Id \$sid -ErrorAction SilentlyContinue } catch {}
        if (\$rsess) {
          \$rpInfo = Get-RpInfoFromSession -Session \$rsess
          \$rpId = [string]\$rpInfo.Id
          \$rpEpoch = [string]\$rpInfo.Epoch
        }
        if (-not \$rpId) {
          \$m = [regex]::Match(\$det, "restorePointId='([^']+)'")
          if (\$m.Success) { \$rpId = \$m.Groups[1].Value }
        }
        Emit-RestoreLine -Sid \$sid -Ip \$ip -Epoch \$epoch -Result ([string]\$_.Result) -Name \$nm -Backup '' -RpId \$rpId -RpEpoch \$rpEpoch
      }
      Remove-Item \$tmp -Force -ErrorAction SilentlyContinue
    }
  }
} catch {}
PS
)"
  # Send the script as a base64 (UTF-16LE) -EncodedCommand: piping a multi-line
  # script to "pwsh -Command -" over stdin intermittently mis-parses and emits
  # nothing. EncodedCommand is immune to quoting/newline issues.
  [[ -n "$ps_script" ]] || { mold_backup_notify_log warn "Veeam→Mold(restore): empty PS script"; return 1; }
  local out rc=0
  # Large restore-query script → file transfer (EncodedCommand hits Windows cmdline limit).
  out="$(mold_backup_veeam_ssh_ps_capture "$ps_script" 2>/dev/null)" && rc=0 || rc=$?
  out="$(printf '%s' "$out" | tr -d '\r')"
  if [[ $rc -ne 0 ]]; then
    mold_backup_notify_log warn "Veeam→Mold(restore): SSH/pwsh query failed (rc=${rc}) — check VEEAM_SSH_PASSWORD/key and pwsh"
    return 1
  fi
  if [[ -z "${out//[[:space:]]/}" ]]; then
    mold_backup_notify_log info "Veeam→Mold(restore): SSH ok (pwsh), 0 sessions from Get-VBRRestoreSession/audit (window=${since_min}min)"
    return 0
  fi
  local _raw_n
  _raw_n="$(printf '%s\n' "$out" | grep -c '|' 2>/dev/null || echo 0)"
  mold_backup_notify_log info "Veeam→Mold(restore): raw session lines=${_raw_n}"
  local now_epoch cutoff line ep
  now_epoch=$(date +%s)
  cutoff=$(( now_epoch - since_min * 60 ))
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    ep="$(printf '%s' "$line" | cut -d'|' -f3)"
    # Drop DateTime.MinValue / negative / nonsense epochs (in-progress FLR sessions).
    if [[ "$ep" =~ ^-?[0-9]+$ ]] && (( ep >= 1000000000 )); then
      [[ "$ep" -ge "$cutoff" ]] && printf '%s\n' "$line"
    fi
  done <<< "$out"
  return 0
}

mold_backup_local_kvm_name() {
  # Prefer live hostname. Conf KVM_HOSTNAME is often copied from another cube and
  # must not make restore-watch skip as not-owner on the real owner host.
  local hn agent_props="/etc/cloudstack/agent/agent.properties" h
  hn="$(hostname -s 2>/dev/null || hostname)"
  hn="${hn%%.*}"
  if [[ -n "${KVM_HOSTNAME:-}" && -n "$hn" && "${KVM_HOSTNAME}" != "$hn" ]]; then
    mold_backup_notify_log warn "KVM_HOSTNAME=${KVM_HOSTNAME} != live hostname ${hn}; using ${hn} for ownership"
  fi
  [[ -n "$hn" ]] && { echo "$hn"; return 0; }
  if [[ -f "$agent_props" ]]; then
    h="$(grep -E '^host\.name=' "$agent_props" 2>/dev/null | tail -1 | cut -d= -f2-)"
    h="${h//$'\r'/}"
    [[ -n "$h" ]] && { echo "$h"; return 0; }
  fi
  [[ -n "${KVM_HOSTNAME:-}" ]] && { echo "$KVM_HOSTNAME"; return 0; }
  hostname -s
}

mold_backup_events_log_file() {
  local d="${ABLESTACK_VEEAM_STATE_DIR}/events"
  mkdir -p "$d" 2>/dev/null || true
  echo "$d/restore.log"
}

mold_backup_emit_restore_event() {
  local event="$1" vm="$2" detail="${3:-}" host
  host="$(mold_backup_local_kvm_name)"
  echo "$(date -Iseconds) event=${event} host=${host} vm=${vm} ${detail}" >> "$(mold_backup_events_log_file)"
  mold_backup_notify_log info "restore-event ${event} vm=${vm} ${detail}"
}

mold_backup_query_veeam_restore_proof() {
  local sid="$1" ps_script
  [[ "$sid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || return 1
  ps_script="$(cat <<PS
\$ErrorActionPreference='Stop'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
Connect-VBRServer -Server localhost -ErrorAction Stop
\$sessions=@(Get-VBRRestoreSession -Id ([guid]'${sid}'))
if (\$sessions.Count -ne 1) { throw 'Exact restore session is unavailable' }
\$s=\$sessions[0]
function Epoch(\$value) {
  if (\$null -eq \$value -or ([datetime]\$value).Year -lt 2000) { return 0 }
  \$dt=[datetime]\$value
  if (\$dt.Kind -eq [DateTimeKind]::Unspecified) { \$dt=[datetime]::SpecifyKind(\$dt,[DateTimeKind]::Utc) }
  return ([DateTimeOffset]\$dt).ToUnixTimeSeconds()
}
\$start=Epoch \$s.CreationTimeUTC
if (-not \$start) { \$start=Epoch \$s.CreationTime }
\$end=Epoch \$s.EndTimeUTC
if (-not \$end) { \$end=Epoch \$s.EndTime }
\$details=@(); \$available=\$false
try {
  \$log=\$s.Logger.GetLog()
  \$records=@(\$log.UpdatedRecords)
  if (\$records.Count -gt 0) {
    \$available=\$true
    \$details=@(\$records | Where-Object { [string]\$_.Status -match '(?i)warning|failed|error' } | ForEach-Object {
      @{status=[string]\$_.Status; message=(([string]\$_.Title)+' '+([string]\$_.Description)).Trim()}
    })
  }
} catch {}
\$value=@{jobId=[string]\$s.Id;result=[string]\$s.Result;startedAt=\$start;endedAt=\$end;detailsAvailable=\$available;details=\$details}
Write-Output ('ABLESTACK_JSON:'+ (\$value | ConvertTo-Json -Compress -Depth 5))
PS
)"
  mold_backup_veeam_ssh_ps_capture "$ps_script" | sed -n 's/^ABLESTACK_JSON://p' | tail -n 1
}

mold_backup_verify_veeam_warning() {
  local sid="$1" resolution="$2" proof output rc=0
  proof="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-25}" mold_backup_query_veeam_restore_proof "$sid")" || proof=""
  output="$(python3 - "$sid" "$resolution" "$proof" <<'PYTHON'
import json
from pathlib import Path
import re
import stat
import sys
import time
import xml.etree.ElementTree as ET

job = {}
try:
    sid, resolution, proof = sys.argv[1:]
    expected = json.loads(resolution)['resolvebackupartifactresponse']['backupartifact']
    job = json.loads(proof)
    if str(job.get('jobId', '')).strip('{}').lower() != sid.lower() or str(job.get('result', '')).lower() != 'warning':
        raise ValueError('Exact restore session has no confirmed Warning result')
    start, end = float(job.get('startedAt', 0)), float(job.get('endedAt', 0))
    if not 1000000000 <= start <= end <= time.time() + 300:
        raise ValueError('Restore session has no valid start and end time')
    details = job.get('details')
    if job.get('detailsAvailable') is not True or not isinstance(details, list) or not details or len(details) > 1000:
        raise ValueError('Detailed warning log is unavailable, empty or too large')
    for record in details:
        if not isinstance(record, dict) or str(record.get('status', '')).lower() not in {'warning', 'ewarning'}:
            raise ValueError('Restore contains a failed/error log record or unrecognized severity')
        message = record.get('message')
        if not isinstance(message, str) or len(message) > 16384:
            raise ValueError('Invalid detailed restore warning')
        # A content error must never be hidden by an attribute warning in the same record.
        if re.search(r'checksum|corrupt|truncat|I/O error|no space|cannot (?:open|read|write|create)|failed to (?:open|read|write|create)|failed to restore (?!(?:(?:file|directory|access) )?(?:permissions?|ownership|owner|times?|timestamps?|attributes?|ACL)\b)|partially restored|kept existing|\.(?:qcow2|raw|rbdiff)\b', message, re.I):
            raise ValueError('Restore contains a file content error: ' + message)
        if not re.search(r'(?:set|change|restore|preserve)\s+(?:(?:file|directory|access)\s+)?(?:permissions?|ownership|owner|times?|timestamps?|attributes?|ACL)\b|\b(?:chmod|chown|utime)\b', message, re.I):
            raise ValueError('Unverified restore warning: ' + message)
    if expected.get('artifacttype') != 'METADATA' or expected.get('provider') != 'ablestack-veeam':
        raise ValueError('Warning restore does not identify a metadata Job')
    source_root = Path(expected.get('metadatapath', ''))
    destinations = {Path(p) for p in (expected.get('metadatarestorepath'), expected.get('metadatapath')) if p}
    available = [p for p in destinations if (p / 'backup-manifest.json').is_file()
                 and (p / 'backup-manifest.json').lstat().st_ctime >= start - 5]
    if len(available) != 1:
        raise ValueError('Restored metadata destination is missing or ambiguous')
    root = available[0]
    if not root.is_absolute() or root.resolve() != root or not root.is_dir():
        raise ValueError('Metadata directory is unavailable or redirects elsewhere')
    def read(name):
        path = root / name
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= 16 * 1024 * 1024 or before.st_ctime < start - 5:
            raise ValueError('Required metadata is missing, stale or invalid: ' + str(path))
        value = path.read_text(encoding='utf-8')
        after = path.lstat()
        if (before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError('Required metadata is still changing: ' + str(path))
        return value
    manifest = json.loads(read('backup-manifest.json'))
    if (manifest.get('version') != 1 or manifest.get('complete') is not True or manifest.get('provider') != expected['provider']
            or manifest.get('backupUuid') != expected.get('id') or manifest.get('vmName') != expected.get('vmname')
            or manifest.get('timestamp') != expected.get('timestamp') or root.name != expected.get('timestamp')
            or root.parent.name != expected.get('vmname')):
        raise ValueError('Restored manifest differs from the selected Mold backup')
    volumes = manifest.get('volumes')
    if not isinstance(volumes, list) or not volumes:
        raise ValueError('Restored manifest has no volumes')
    seen = set()
    for volume in volumes:
        if (not isinstance(volume, dict) or not volume.get('uuid') or volume['uuid'] in seen
                or volume.get('engine') not in {'QCOW2', 'RBD_DIFF', 'OFFLINE_FILE'} or not volume.get('chain')):
            raise ValueError('Invalid volume restore chain')
        seen.add(volume['uuid'])
        artifact = volume['chain'][-1]
        if (artifact.get('backupUuid') != manifest['backupUuid'] or artifact.get('completed') is not True
                or not artifact.get('externalId') or not artifact.get('jobId') or Path(artifact.get('path', '')).parent != source_root):
            raise ValueError('Unconfirmed or mismatched volume artifact')
    if any(volume.get('engine') == 'OFFLINE_FILE' for volume in volumes):
        guard = manifest.get('offlineGuard') or {}
        if (manifest.get('backupType') != 'FULL' or manifest.get('parentBackupUuid')
                or guard.get('version') != 1 or guard.get('operation') != 'BACKUP'
                or guard.get('jobId') != manifest['backupUuid'] or guard.get('vmUuid') != expected.get('virtualmachineid')
                or any(len(volume['chain']) != 1 for volume in volumes)):
            raise ValueError('Offline RAW metadata is not a protected independent Full')
    domain = ET.fromstring(read('domain-config.xml'))
    if (domain.tag != 'domain' or domain.findtext('name') != expected.get('vmname')
            or not expected.get('virtualmachineid') or domain.findtext('uuid') != expected['virtualmachineid']):
        raise ValueError('Restored domain XML differs from the original VM')
    marker = dict(line.split('=', 1) for line in read('.staging.complete').splitlines() if '=' in line)
    if marker.get('volume_pipeline') != 'true' or marker.get('backup_uuid') != expected['id']:
        raise ValueError('Restored completion marker differs from the selected backup')
    print('VERIFIED: attribute warning; metadata matches backup ' + expected['id'] + '; details=' + json.dumps(details, ensure_ascii=True))
except (ValueError, KeyError, TypeError, AttributeError, OSError, ET.ParseError) as exc:
    details = job.get('details') if isinstance(job, dict) else None
    print('BLOCKED: ' + str(exc) + '; details=' + json.dumps(details, ensure_ascii=True)[:2048])
    sys.exit(1)
PYTHON
)" || rc=$?
  mold_backup_notify_log warn "restore-watch: session=${sid} ${output:0:4096}"
  return "$rc"
}

mold_backup_report_rejected_volume_restore() {
  local sid="$1" rp_id="$2" reason="$3" proof host output success
  proof="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-25}" mold_backup_query_veeam_restore_proof "$sid")" || return 2
  host="$(mold_backup_local_kvm_name)"
  proof="$(python3 - "$sid" "$host" "$proof" <<'PYTHON'
import json, math, sys, time
try:
    sid, host, raw = sys.argv[1:]
    value = json.loads(raw)
    start, end = float(value.get('startedAt', 0)), float(value.get('endedAt', 0))
    if (str(value.get('jobId', '')).strip('{}').lower() != sid.split('#')[0].lower()
            or str(value.get('result', '')).lower() not in {'success', 'warning'}
            or not host or not all(map(math.isfinite, (start, end))) or start <= 0 or end < start or end > time.time() + 300):
        raise ValueError('External restore session completion is unconfirmed')
    print(json.dumps(dict(hostName=host, startedAt=int(start * 1000), endedAt=int(end * 1000))))
except (ValueError, TypeError, KeyError) as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
PYTHON
)" || return 2
  output="$(mold_backup_cmk_run rejectBackupArtifactRestore "provider=ablestack-veeam" "externalid=${rp_id}" \
    "jobid=${sid%%#*}" "reason=${reason:-Metadata restore precheck declined}" "proof=${proof}" 2>/dev/null)" || return 2
  success="$(mold_backup_api_json_field "$output" "rejectbackupartifactrestoreresponse.success")"
  [[ "$success" == [Tt][Rr][Uu][Ee] ]]
}

mold_backup_handle_volume_restore_session() {
  local job="$1" sid="$2" rp_id="${3:-}" trigger_mold="${4:-false}" result="${5:-}"
  local json matched tracked allowed reason vm async_id artifact_type
  result="$(printf '%s' "$result" | tr '[:upper:]' '[:lower:]')"
  [[ "$result" == success || "$result" == warning ]] || return 2
  local -a params=("provider=ablestack-veeam" "jobid=${sid%%#*}")
  [[ -z "$rp_id" || "$rp_id" == "n/a" ]] || params+=("externalid=${rp_id}")
  json="$(mold_backup_cmk_run resolveBackupArtifact "${params[@]}" 2>/dev/null)" || return 2
  matched="$(mold_backup_api_json_field "$json" "resolvebackupartifactresponse.backupartifact.matched")"
  [[ -n "$matched" ]] || return 2
  if [[ "$matched" != [Tt][Rr][Uu][Ee] ]]; then
    if [[ "$job" == ABLESTACK-* || "$job" == *ABLESTACK-* ]]; then
      mold_backup_notify_log warn "restore-watch: child session=${sid} rp=${rp_id:-unknown} has no exact Mold artifact; waiting, no timestamp fallback"
      return 2
    fi
    return 1
  fi
  vm="$(mold_backup_api_json_field "$json" "resolvebackupartifactresponse.backupartifact.vmname")"
  tracked="$(mold_backup_api_json_field "$json" "resolvebackupartifactresponse.backupartifact.tracked")"
  allowed="$(mold_backup_api_json_field "$json" "resolvebackupartifactresponse.backupartifact.restoreallowed")"
  reason="$(mold_backup_api_json_field "$json" "resolvebackupartifactresponse.backupartifact.reason")"
  if [[ "$tracked" == [Tt][Rr][Uu][Ee] || "$allowed" != [Tt][Rr][Uu][Ee] ]]; then
    artifact_type="$(mold_backup_api_json_field "$json" "resolvebackupartifactresponse.backupartifact.artifacttype")"
    if [[ "$trigger_mold" == "true" && "$tracked" != [Tt][Rr][Uu][Ee] && "$artifact_type" == "METADATA" ]]; then
      [[ -n "$rp_id" && "$rp_id" != "n/a" ]] || return 2
      mold_backup_report_rejected_volume_restore "$sid" "$rp_id" "$reason" || return 2
    fi
    mold_backup_emit_restore_event "mold.restore.skipped.volume-pipeline" "$vm" "session=${sid};rp=${rp_id};reason=${reason}"
    mold_backup_restore_session_mark_seen "$sid"
    return 0
  fi
  if [[ "$trigger_mold" != "true" ]]; then
    mold_backup_notify_log info "restore-watch: metadata session=${sid} vm=${vm}; enable RESTORE_WATCH_TRIGGER_MOLD to restore its logical backup"
    return 0
  fi
  [[ -n "$rp_id" && "$rp_id" != "n/a" ]] || return 2
  if [[ "$result" == warning ]]; then
    mold_backup_verify_veeam_warning "$sid" "$json" || return 2
  fi
  json="$(mold_backup_cmk_run restoreBackupArtifact "provider=ablestack-veeam" "externalid=${rp_id}" "jobid=${sid%%#*}" 2>/dev/null)" || return 2
  async_id="$(mold_backup_api_json_field "$json" "restorebackupartifactresponse.jobid")"
  [[ -n "$async_id" ]] || return 2
  mold_backup_restore_session_mark_seen "$sid"
  mold_backup_emit_restore_event "mold.restore.submitted" "$vm" "session=${sid};rp=${rp_id};async_job_id=${async_id};source=metadata-ui"
  return 0
}

mold_backup_handle_veeam_restore_session() {
  local job="$1" vm="$2" sid="$3" detail="$4" trigger_mold="${5:-false}" rp_id="${6:-}"
  local session row_sid sip et result nm bn row_rp rp_epoch
  [[ "$sid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || return 1
  session="$(mold_backup_query_veeam_restores "${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}" | awk -F'|' -v sid="$sid" 'tolower($1)==tolower(sid) && !found {print; found=1}')" || return 1
  IFS='|' read -r row_sid sip et result nm bn row_rp rp_epoch <<< "$session"
  result="$(printf '%s' "$result" | tr '[:upper:]' '[:lower:]')"
  [[ "$et" =~ ^[0-9]+$ ]] && (( et >= 1000000000 )) || return 1
  [[ -z "$rp_id" || "$rp_id" == "$row_rp" ]] || return 1
  mold_backup_handle_volume_restore_session "${bn:-$job}" "$sid" "$row_rp" "$trigger_mold" "$result"
}

mold_backup_watch_veeam_restores() {
  local job="${1:-${VEEAM_JOB_NAME:-}}" since_min="${2:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}"
  local trigger_mold="${3:-${RESTORE_WATCH_TRIGGER_MOLD:-false}}"
  local sid sip et result nm bn rp_id rp_epoch pipeline_rc processed=0 handled=0
  mold_backup_initialize_restore_watch_baseline "$since_min"
  while IFS='|' read -r sid sip et result nm bn rp_id rp_epoch; do
    [[ "$sid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || continue
    result="$(printf '%s' "$result" | tr '[:upper:]' '[:lower:]')"
    [[ "$result" == success || "$result" == warning ]] || continue
    [[ "$et" =~ ^[0-9]+$ ]] && (( et >= 1000000000 )) || continue
    mold_backup_restore_session_seen "$sid" && continue
    processed=$((processed + 1))
    pipeline_rc=0
    mold_backup_handle_volume_restore_session "${bn:-$nm}" "$sid" "$rp_id" "$trigger_mold" "$result" || pipeline_rc=$?
    if [[ "$pipeline_rc" == 0 ]]; then
      handled=$((handled + 1))
    else
      mold_backup_notify_log warn "restore-watch: session=${sid} has no eligible exact metadata artifact; automatic restore skipped"
    fi
  done < <(mold_backup_query_veeam_restores "$since_min" 2>/dev/null || true)
  mold_backup_notify_log info "restore-watch job=${job} scanned=${processed} handled=${handled} trigger_mold=${trigger_mold}"
}

mold_backup_backup_watch_state() {
  local d="$(mold_backup_state_dir)/backup-watch"
  mkdir -p "$d" 2>/dev/null || true
  echo "$d/processed-sessions"
}

mold_backup_backup_session_seen() {
  local sid="$1" f
  f="$(mold_backup_backup_watch_state)"
  [[ -f "$f" ]] || return 1
  grep -qxF "$sid" "$f" 2>/dev/null
}

mold_backup_backup_session_mark_seen() {
  local sid="$1" f
  f="$(mold_backup_backup_watch_state)"
  echo "$sid" >> "$f" 2>/dev/null || true
  # keep the file bounded
  if [[ -f "$f" ]] && (( $(wc -l <"$f" 2>/dev/null || echo 0) > 2000 )); then
    tail -n 1000 "$f" > "${f}.tmp" 2>/dev/null && mv -f "${f}.tmp" "$f" 2>/dev/null || true
  fi
}

mold_backup_veeam_ssh_cmd() {
  # Usage: mold_backup_veeam_ssh_cmd <remote-command...>
  # Runs ssh with the same auth policy as mold_backup_veeam_ssh_encoded.
  # Always hard-capped: ConnectTimeout alone does not stop hung password prompts.
  local ssh_key_opt=() askpass_file="" ssh_rc=0
  local ssh_timeout="${VEEAM_SSH_CMD_TIMEOUT:-60}"
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || return 1
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_key_opt=(-i "${VEEAM_SSH_KEY}")
  local -a ssh_host_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
    -o ConnectTimeout=15 -o ServerAliveInterval=5 -o ServerAliveCountMax=3)
  if [[ "${VEEAM_SSH_STRICT_HOSTKEY:-false}" == "true" ]]; then
    ssh_host_opts=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15
      -o ServerAliveInterval=5 -o ServerAliveCountMax=3)
  fi
  local remote="${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}"
  local -a prefix=()
  if command -v timeout >/dev/null 2>&1; then
    prefix=(timeout "$ssh_timeout")
  fi
  if [[ ${#ssh_key_opt[@]} -gt 0 ]]; then
    "${prefix[@]}" ssh -n "${ssh_key_opt[@]}" -o BatchMode=yes "${ssh_host_opts[@]}" "$remote" "$@"
    return $?
  fi
  if [[ -n "${VEEAM_SSH_PASSWORD:-}" ]] && command -v sshpass >/dev/null 2>&1; then
    SSHPASS="${VEEAM_SSH_PASSWORD}" "${prefix[@]}" sshpass -e ssh -n "${ssh_host_opts[@]}" \
      -o PreferredAuthentications=password -o PubkeyAuthentication=no \
      -o NumberOfPasswordPrompts=1 "$remote" "$@"
    return $?
  fi
  if [[ -n "${VEEAM_SSH_PASSWORD:-}" ]]; then
    askpass_file="$(mktemp /tmp/veeam-askpass.XXXXXX)"
    printf '%s\n' '#!/bin/bash' "printf '%s\\n' $(printf '%q' "${VEEAM_SSH_PASSWORD}")" >"$askpass_file"
    chmod 700 "$askpass_file"
    SSH_ASKPASS="$askpass_file" SSH_ASKPASS_REQUIRE=force DISPLAY="${DISPLAY:-:0}" \
      setsid -w "${prefix[@]}" ssh -n "${ssh_host_opts[@]}" \
      -o PreferredAuthentications=password -o PubkeyAuthentication=no \
      -o NumberOfPasswordPrompts=1 "$remote" "$@"
    ssh_rc=$?
    rm -f "$askpass_file"
    return $ssh_rc
  fi
  "${prefix[@]}" ssh -n -o BatchMode=yes "${ssh_host_opts[@]}" "$remote" "$@"
}

mold_backup_veeam_ssh_encoded() {
  local ps_enc="$1"
  local attempt out rc last_err="" ps_launcher
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || return 1
  [[ -n "$ps_enc" ]] || return 1
  local -a ps_launchers=(
    '"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -EncodedCommand'
    'pwsh.exe -NoProfile -EncodedCommand'
    'pwsh -NoProfile -EncodedCommand'
    'powershell.exe -NoProfile -EncodedCommand'
  )
  for attempt in 1 2 3; do
    for ps_launcher in "${ps_launchers[@]}"; do
      out="$(mold_backup_veeam_ssh_cmd "${ps_launcher} ${ps_enc}" 2>&1)" && rc=0 || rc=$?
      if [[ $rc -eq 0 ]]; then
        printf '%s' "$out"
        return 0
      fi
      last_err="$out"
    done
    sleep 2
  done
  last_err="${last_err//$'\r'/}"
  last_err="${last_err//$'\n'/; }"
  mold_backup_notify_log warn "Veeam SSH failed (host=${VEEAM_SSH_HOST} rc=${rc}): ${last_err:0:240}"
  return 1
}

mold_backup_query_veeam_backups() {
  local since_min="${1:-${VEEAM_BACKUP_WATCH_WINDOW_MIN:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}}"
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "Veeam→Mold(backup): VEEAM_SSH_HOST not set"
    return 1
  }
  local ssh_key_opt=()
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_key_opt=(-i "${VEEAM_SSH_KEY}")
  local -a ssh_host_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
  if [[ "${VEEAM_SSH_STRICT_HOSTKEY:-false}" == "true" ]]; then
    ssh_host_opts=(-o StrictHostKeyChecking=accept-new)
  fi
  local ps_script
  ps_script="$(cat <<'PS'
$ErrorActionPreference = 'SilentlyContinue'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
# Warm-up loop (same quirk as restore sessions). Union regular backup sessions
# with Agent (computer) backup job sessions so guest-VM Agent jobs are included.
$sessions = @()
for ($k = 0; $k -lt 12; $k++) {
  try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
  $tmp = @()
  try { $tmp += @(Get-VBRBackupSession) } catch {}
  try { $tmp += @(Get-VBRComputerBackupJobSession) } catch {}
  $sessions = @($tmp)
  if ($sessions.Count -gt 0) { break }
  Start-Sleep -Milliseconds 700
}
$tmp = @()
try { $tmp += @(Get-VBRBackupSession) } catch {}
try { $tmp += @(Get-VBRComputerBackupJobSession) } catch {}
$sessions = @($tmp)
# Dedup by Id inside the loop via a hashtable (a "| Select-Object -Unique"
# reassignment can make the following foreach emit nothing in pwsh).
$seen = @{}
foreach ($s in $sessions) {
  if ($null -eq $s) { continue }
  $sidKey = [string]$s.Id
  if ($seen.ContainsKey($sidKey)) { continue }
  $seen[$sidKey] = $true
  # Completion differs by session type: CBackupSession has IsCompleted, while the
  # Agent VBRSession (Get-VBRComputerBackupJobSession) only exposes State — treat
  # State=Stopped/Completed as done. (Relying on IsCompleted alone skipped every
  # agent session because that property does not exist on VBRSession.)
  $done = $false
  if ($s.IsCompleted -eq $true) { $done = $true }
  $st = [string]$s.State
  if ($st -eq 'Stopped' -or $st -eq 'Completed') { $done = $true }
  if (-not $done) { continue }
  # Agent VBRSession has no JobName; its Name holds the job/computer (dash-IP) name.
  $nm = ([string]$s.Name) -replace '[\|\r\n]',' '
  $jn = ([string]$s.JobName) -replace '[\|\r\n]',' '
  $rs = [string]$s.Result
  $hay = "$nm $jn"
  $ip = ''
  $m = [regex]::Match($hay, '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})')
  if ($m.Success) { $ip = $m.Groups[1].Value }
  if (-not $ip) {
    $md = [regex]::Match($hay, '(\d{1,3}-\d{1,3}-\d{1,3}-\d{1,3})')
    if ($md.Success) { $ip = ($md.Groups[1].Value -replace '-','.') }
  }
  $epoch = 0
  $etObj = if ($null -ne $s.EndTime) { $s.EndTime } else { $s.EndTimeUTC }
  try { if ($null -ne $etObj) { $epoch = [int64]([DateTimeOffset]$etObj).ToUnixTimeSeconds() } } catch { $epoch = 0 }
  "$($s.Id)|$ip|$epoch|$rs|$nm|$jn"
}
PS
)"
  local ps_enc
  ps_enc="$(printf '%s' "$ps_script" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null | base64 -w0 2>/dev/null)"
  [[ -n "$ps_enc" ]] || { mold_backup_notify_log warn "Veeam→Mold(backup): failed to encode PS script"; return 1; }
  local attempt out rc
  for attempt in 1 2 3; do
    out="$(ssh "${ssh_key_opt[@]}" -o BatchMode=yes -o ConnectTimeout=30 "${ssh_host_opts[@]}" \
            "${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}" \
            "pwsh -NoProfile -EncodedCommand ${ps_enc}" 2>/dev/null)"
    rc=$?
    if [[ $rc -eq 0 ]]; then
      out="${out//$'\r'/}"
      local now_epoch cutoff line ep
      now_epoch=$(date +%s)
      cutoff=$(( now_epoch - since_min * 60 ))
      while IFS= read -r line; do
        [[ -n "$line" ]] || continue
        ep="$(printf '%s' "$line" | cut -d'|' -f3)"
        if [[ "$ep" =~ ^[0-9]+$ ]]; then
          [[ "$ep" -ge "$cutoff" ]] && printf '%s\n' "$line"
        else
          printf '%s\n' "$line"
        fi
      done <<< "$out"
      return 0
    fi
    mold_backup_notify_log warn "Veeam→Mold(backup): SSH query attempt ${attempt} failed (rc=${rc}); retrying"
    sleep 3
  done
  mold_backup_notify_log warn "Veeam→Mold(backup): SSH query failed after retries"
  return 1
}

mold_backup_reflect_one_backup() {
  local job="$1" vm="$2" sid="$3" detail="$4"
  # Loop guard: this backup was initiated by Mold itself (Mold->Veeam trigger);
  # the Mold record already exists, so don't double-record.
  if mold_backup_trigger_active "mold-active" "$vm"; then
    mold_backup_trigger_clear "mold-active" "$vm"
    mold_backup_notify_log info "mold-active for ${vm}: backup initiated by Mold; skip reflect (session=${sid})"
    mold_backup_backup_session_mark_seen "$sid"
    return 0
  fi
  mold_backup_registry_save_backup "$job" "$vm" "veeam:${sid}" "veeam-backed-up"
  mold_backup_backup_session_mark_seen "$sid"
  mold_backup_notify_log info "Veeam→Mold: reflected backup for ${vm} (session=${sid}) detail=${detail}"
}

mold_backup_watch_veeam_backups() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local since_min="${2:-${VEEAM_BACKUP_WATCH_WINDOW_MIN:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}}"
  [[ -n "${VM_TARGETS:-}" ]] || {
    mold_backup_notify_log warn "Veeam→Mold(backup): VM_TARGETS empty; nothing to watch"
    return 0
  }
  mold_backup_notify_log info "=== backup-watch job=${job} window=${since_min}min ==="
  local sid sip et result nm jn matched_ip vm
  local processed=0 reflected=0
  while IFS='|' read -r sid sip et result nm jn; do
    [[ -n "$sid" ]] || continue
    processed=$((processed+1))
    mold_backup_backup_session_seen "$sid" && continue
    # Only reflect successful/warning backups (a failed backup is not a restore point).
    case "$(printf '%s' "$result" | tr '[:upper:]' '[:lower:]')" in
      success|warning) ;;
      *)
        mold_backup_notify_log info "backup-watch: session ${sid} result='${result}' (skip non-success)"
        mold_backup_backup_session_mark_seen "$sid"
        continue
        ;;
    esac
    # Match: prefer the IP parsed from the session/job name; fall back to scanning
    # VM_TARGETS IPs (dots or dashes form) against the session / job name.
    matched_ip=""
    if [[ -n "$sip" ]] && mold_backup_vm_name_for_ip "$sip" >/dev/null 2>&1; then
      matched_ip="$sip"
    else
      IFS=',' read -ra _pairs <<<"${VM_TARGETS}"
      local pair ip ipd vmn
      for pair in "${_pairs[@]}"; do
        pair="${pair// /}"
        vmn="${pair%%:*}"
        ip="${pair#*:}"
        [[ -n "$ip" && "$ip" != "$vmn" ]] || continue
        ipd="${ip//./-}"
        # Match by IP (dots/dashes) for legacy "Mold VM <ip>" jobs, or by the
        # libvirt internal name (e.g. i-2-51-VM) for jobs named by internal name.
        if [[ "$sip" == "$ip" || "$nm" == *"$ip"* || "$nm" == *"$ipd"* || "$jn" == *"$ip"* || "$jn" == *"$ipd"* \
              || ( -n "$vmn" && ( "$nm" == *"$vmn"* || "$jn" == *"$vmn"* ) ) ]]; then
          matched_ip="$ip"
          break
        fi
      done
    fi
    if [[ -z "$matched_ip" ]]; then
      mold_backup_notify_log info "backup-watch: session ${sid} ip='${sip}' name='${nm}' job='${jn}' — no VM_TARGETS match (skip)"
      continue
    fi
    vm="$(mold_backup_vm_name_for_ip "$matched_ip" 2>/dev/null || true)"
    [[ -n "$vm" ]] || continue
    mold_backup_reflect_one_backup "$job" "$vm" "$sid" "name=${nm};job=${jn};end=${et};result=${result};ip=${matched_ip}"
    reflected=$((reflected+1))
  done < <(mold_backup_query_veeam_backups "$since_min" || true)
  mold_backup_notify_log info "=== backup-watch done: scanned=${processed} reflected=${reflected} ==="
  return 0
}

mold_backup_bootstrap_helper() {
  local directory="${BASH_SOURCE[0]%/*}"
  printf '%s' "$directory/../thirdparty_bootstrap.py"
}

mold_backup_pre_notify() {
  local client="${1:-$(hostname -s)}" job schedule="${3:-${VEEAM_SCHEDULE_NAME:-default}}"
  local single_vm="${4:-}" saved_include=""
  job="$(mold_backup_normalize_job_name "${2:-${VEEAM_JOB_NAME:-}}")"
  [[ -n "$job" ]] || mold_backup_die "VEEAM_JOB_NAME is required for pre-notify"
  VEEAM_JOB_NAME="$job"
  mold_backup_load_config || return 1
  job="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-$job}")"
  VEEAM_JOB_NAME="$job"
  # Per-artifact jobs are owned by Mold and must not recursively create VM backups.
  [[ ! "$job" =~ ^ABLESTACK-[[:xdigit:]-]{36}-(metadata|[[:xdigit:]-]{36})$ ]] || return 0
  [[ "${VEEAM_PROVIDER_NAME:-ablestack-veeam}" == ablestack-veeam && "${BACKUP_MODE:-host}" =~ ^(host|policy)$ ]] || {
    mold_backup_notify_log err "Veeam hooks require the ablestack-veeam host volume staging configuration"
    return 1
  }
  if [[ -z "$VEEAM_AGENT_PAYLOAD_PATH" ]]; then
    mold_backup_notify_log err "Rerun veeam-host-config.py and configure the parent Job's marker SelectedFiles before backing up."
    return 1
  fi
  if [[ "${VEEAM_AGENT_PAYLOAD_MODE:-EXPLICIT}" == COMMON ]]; then
    local parent_host parent_job expected_payload
    parent_host="$(mold_backup_safe_job_name "${KVM_HOSTNAME:-$client}")"
    parent_job="$(mold_backup_safe_job_name "$job")"
    expected_payload="$VEEAM_HOST_BACKUP_PATH/bootstrap/$parent_host/$parent_job"
    if [[ "$VEEAM_AGENT_PAYLOAD_PATH" != "$expected_payload" ]]; then
      mold_backup_notify_log err "Staging changed. Rerun veeam-host-config.py and update parent Job SelectedFiles to $expected_payload before backing up."
      return 1
    fi
  fi
  local MOLD_EXTERNAL_BACKUP_CANDIDATES external_selection_mode
  MOLD_EXTERNAL_BACKUP_CANDIDATES="$(mold_backup_api_external_candidates)" || return 1
  external_selection_mode="$(mold_backup_external_candidate_field "" selectionmode)" || return 1
  if [[ -n "$single_vm" ]]; then
    saved_include="${VM_INCLUDE:-*}"
    VM_INCLUDE="$single_vm"
  fi
  export MOLD_BACKUP_HOOK=pre-notify VEEAM_SCHEDULE_NAME="$schedule"
  local state_file run_id vm_name success=0 fail=0
  mkdir -p "$(mold_backup_state_dir)" "$VEEAM_AGENT_PAYLOAD_PATH" || return 1
  run_id="$(date '+%Y%m%d%H%M%S')"
  state_file="$(mold_backup_state_file_for_job "$job" "$run_id")"
  printf 'run_id=%s\n' "$run_id" > "$state_file"
  while IFS= read -r vm_name; do
    [[ -n "$vm_name" ]] || continue
    if [[ "$external_selection_mode" != SCHEDULE ]]; then
      mold_backup_vm_in_filter "$vm_name" || continue
    fi
    if mold_backup_process_external_candidate_vm "$vm_name" "$state_file"; then
      success=$((success + 1))
    else
      fail=$((fail + 1))
    fi
  done < <(mold_backup_list_target_domains || true)
  [[ -z "$saved_include" ]] || VM_INCLUDE="$saved_include"
  mold_backup_notify_log info "pre-notify mode=${external_selection_mode} success=${success} fail=${fail} state=${state_file}"
  [[ "$fail" -eq 0 ]]
}

mold_backup_post_notify() {
  local job state_file line vm_name status backup_id
  job="$(mold_backup_normalize_job_name "${2:-${VEEAM_JOB_NAME:-}}")"
  [[ -n "$job" ]] || mold_backup_die "VEEAM_JOB_NAME is required for post-notify"
  VEEAM_JOB_NAME="$job"
  mold_backup_load_config || return 1
  job="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-$job}")"
  [[ ! "$job" =~ ^ABLESTACK-[[:xdigit:]-]{36}-(metadata|[[:xdigit:]-]{36})$ ]] || return 0
  export MOLD_BACKUP_HOOK=post-notify
  state_file="$(mold_backup_latest_state_file "$job" || true)"
  if [[ -f "$state_file" ]]; then
    while IFS= read -r line; do
      [[ "$line" == vm=* ]] || continue
      vm_name="$(mold_backup_state_parse_field "$line" vm)"
      status="$(mold_backup_state_parse_field "$line" status)"
      backup_id="$(mold_backup_state_parse_field "$line" backup_id)"
      [[ -n "$vm_name" ]] || continue
      mold_backup_trigger_clear veeam-active "$vm_name"
      mold_backup_trigger_mark backup-cooldown "$vm_name"
      if [[ -n "$backup_id" && -n "$VEEAM_AGENT_PAYLOAD_PATH" && -f "$VEEAM_AGENT_PAYLOAD_PATH/$backup_id.json" ]]; then
        python3 "$(mold_backup_bootstrap_helper)" retire --payload-root "$VEEAM_AGENT_PAYLOAD_PATH" \
          --backup-uuid "$backup_id" || return 1
      fi
      mold_backup_notify_log info "Parent session finished vm=${vm_name} status=${status}; Mold owns volume transfers and metadata completion"
    done < "$state_file"
    rm -f -- "$state_file"
  fi
  # Retire only parent markers; image payloads and catalog completion belong to Mold.
  mold_backup_notify_log info "post-notify: volume staging and catalog completion remain owned by Mold"
}

mold_backup_api_backup_vm_id() {
  local backup_id="$1" json
  json=$(mold_backup_cmk_run listBackups "id=${backup_id}" 2>/dev/null) || return 1
  mold_backup_api_json_field "$json" "listbackupsresponse.backup.virtualmachineid"
}

mold_backup_api_verify_backup_for_vm() {
  local backup_id="$1" vm_id="$2"
  local owner
  owner="$(mold_backup_api_backup_vm_id "$backup_id" 2>/dev/null || true)"
  # Fail closed: removed/unknown backups previously returned 0 and called restore with a stale id.
  [[ -n "$owner" ]] || {
    mold_backup_notify_log err "Backup ${backup_id} not found or removed; refusing restore for VM ${vm_id}"
    return 1
  }
  [[ "$owner" == "$vm_id" ]] || {
    mold_backup_notify_log err "Backup ${backup_id} belongs to VM ${owner}, not ${vm_id}"
    return 1
  }
  return 0
}

mold_backup_restore_notify() {
  export MOLD_BACKUP_HOOK=restore-notify
  mold_backup_load_config || return 1
  mold_backup_require_var BACKUP_ID
  [[ -n "${VM_UUID:-}" ]] || VM_UUID="$(mold_backup_api_get_vm_id "${VM_NAME:-}" 2>/dev/null || true)"
  [[ -n "${VM_UUID:-}" ]] || { mold_backup_notify_log err "Restore requires VM_NAME or VM_UUID"; return 1; }
  mold_backup_api_verify_backup_for_vm "$BACKUP_ID" "$VM_UUID" || return 1
  mold_backup_api_restore
}

mold_backup_list_veeam_job_restore_points() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local job_esc ps_script out
  [[ -n "${VEEAM_SSH_HOST:-}" && -n "$job" ]] || return 1
  job_esc="${job//\'/\'\'}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'SilentlyContinue'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
\$JobName = '${job_esc}'
\$backs = @()
\$cj = Get-VBRComputerBackupJob -Name \$JobName -ErrorAction SilentlyContinue
if (\$cj) {
  \$backs += @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object { \$_.JobId -eq \$cj.Id })
}
\$backs += @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object {
  \$_.JobName -eq \$JobName -or \$_.Name -eq \$JobName -or \$_.Name -like "*\$JobName*"
})
# Fallback: any restore point whose name/job mentions the host job (Agent host backups).
if (-not \$backs -or \$backs.Count -eq 0) {
  \$backs = @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object {
    ([string]\$_.Name + ' ' + [string]\$_.JobName) -match [regex]::Escape(\$JobName)
  })
}
\$backs = @(\$backs | Select-Object -Unique)
if (-not \$backs -or \$backs.Count -eq 0) {
  # Last resort: emit RPs from global list filtered by job-ish name (may be slow).
  foreach (\$rp in @(Get-VBRRestorePoint -ErrorAction SilentlyContinue)) {
    \$nm = [string]\$rp.Name
    \$jn = ''
    try { \$jn = [string]\$rp.JobName } catch {}
    if (\$nm -notmatch [regex]::Escape(\$JobName) -and \$jn -ne \$JobName) { continue }
    \$id = \$rp.Id
    if (\$id -is [guid]) { \$id = \$id.Guid }
    \$id = ([string]\$id).Trim('{}').ToLower()
    if (-not \$id) { continue }
    \$ct = \$rp.CreationTimeUTC
    if (\$null -eq \$ct) { \$ct = \$rp.CreationTime }
    \$ep = 0
    if (\$null -ne \$ct) {
      if (\$ct.Kind -eq [DateTimeKind]::Unspecified) { \$ct = [DateTime]::SpecifyKind(\$ct, [DateTimeKind]::Utc) }
      try { \$ep = [int64]([DateTimeOffset]\$ct).ToUnixTimeSeconds() } catch { \$ep = 0 }
    }
    Write-Output ("\$id|\$ep")
  }
  exit 0
}
\$seen = @{}
foreach (\$b in \$backs) {
  foreach (\$rp in @(\$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue)) {
    \$id = \$rp.Id
    if (\$id -is [guid]) { \$id = \$id.Guid }
    \$id = ([string]\$id).Trim('{}').ToLower()
    if (-not \$id -or \$seen.ContainsKey(\$id)) { continue }
    \$seen[\$id] = \$true
    \$ct = \$rp.CreationTimeUTC
    if (\$null -eq \$ct) { \$ct = \$rp.CreationTime }
    \$ep = 0
    if (\$null -ne \$ct) {
      if (\$ct.Kind -eq [DateTimeKind]::Unspecified) {
        \$ct = [DateTime]::SpecifyKind(\$ct, [DateTimeKind]::Utc)
      }
      try { \$ep = [int64]([DateTimeOffset]\$ct).ToUnixTimeSeconds() } catch { \$ep = 0 }
    }
    Write-Output ("\$id|\$ep")
  }
}
PS
)"
  out="$(VEEAM_SSH_CMD_TIMEOUT="${VEEAM_CATALOG_SYNC_SSH_TIMEOUT:-45}" \
    mold_backup_veeam_ssh_ps_capture "$ps_script" 2>/dev/null | tr -d '\r')" || out=""
  local lines
  lines="$(printf '%s\n' "$out" | grep -E '^[0-9a-fA-F-]{8,}\|[0-9]+$' || true)"
  if [[ -z "$lines" ]]; then
    lines="$(mold_backup_list_veeam_job_restore_points_rest "$job" 2>/dev/null || true)"
  fi
  printf '%s\n' "$lines"
}

mold_backup_veeam_job_disk_backup_status() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local job_esc ps_script
  [[ -n "${VEEAM_SSH_HOST:-}" && -n "$job" ]] || return 1
  job_esc="${job//\'/\'\'}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'SilentlyContinue'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
\$JobName = '${job_esc}'
\$backs = @()
\$cj = Get-VBRComputerBackupJob -Name \$JobName -ErrorAction SilentlyContinue
if (\$cj) {
  \$backs += @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object { \$_.JobId -eq \$cj.Id })
}
\$backs += @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object {
  \$_.JobName -eq \$JobName -or \$_.Name -eq \$JobName -or \$_.Name -like ("*" + \$JobName + "*")
})
\$backs = @(\$backs | Select-Object -Unique)
if (\$backs -and \$backs.Count -gt 0) { Write-Output 'HAS_BACKUP' } else { Write-Output 'NO_BACKUP' }
PS
)"
  VEEAM_SSH_CMD_TIMEOUT="${VEEAM_CATALOG_SYNC_SSH_TIMEOUT:-30}" \
    mold_backup_veeam_ssh_ps_capture "$ps_script" 2>/dev/null | tr -d '\r' | grep -E '^(HAS_BACKUP|NO_BACKUP)$' | tail -1
}

mold_backup_list_veeam_job_restore_points_rest() {
  local name="${1:-}"
  local api ver user pass token enc http body
  [[ -n "$name" ]] || return 1
  api="$(mold_backup_veeam_api_base)" || return 1
  ver="${VEEAM_API_VERSION:-1.2-rev0}"
  user="${VEEAM_API_USER:-${VEEAM_USERNAME:-administrator}}"
  pass="${VEEAM_API_PASSWORD:-${VEEAM_PASSWORD:-}}"
  [[ -n "$pass" ]] || return 1
  token="$(curl -sk --max-time 15 -X POST "${api}/api/oauth2/token" \
    -H "x-api-version: ${ver}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&username=${user}&password=${pass}" 2>/dev/null \
    | python3 -c "import sys,json
try: print(json.load(sys.stdin).get('access_token',''))
except Exception: print('')" 2>/dev/null)"
  [[ -n "$token" ]] || return 1
  enc="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$name")"
  body="$(mktemp)"
  http="$(curl -sk --max-time 30 -o "$body" -w '%{http_code}' \
    "${api}/api/v1/objectRestorePoints?nameFilter=${enc}&orderColumn=CreationTime&orderAsc=false" \
    -H "x-api-version: ${ver}" -H "Authorization: Bearer ${token}" 2>/dev/null || echo 000)"
  if [[ "$http" != "200" ]]; then
    rm -f "$body"
    return 1
  fi
  python3 -c "
import sys, json
from datetime import datetime, timezone
want = '''${name}'''.lower()
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(2)
items = d.get('data', d if isinstance(d, list) else [])
for rp in items:
    nm = str(rp.get('name') or '')
    jn = str(rp.get('backupJobName') or rp.get('jobName') or rp.get('backupName') or '')
    hay = (nm + ' ' + jn).lower()
    if want and want not in hay and nm.lower() != want and jn.lower() != want:
        continue
    rid = str(rp.get('id') or rp.get('objectRestorePointId') or '').strip('{}').lower()
    if not rid:
        continue
    ep = 0
    for key in ('creationTimeUtc', 'creationTime', 'CreationTimeUTC', 'CreationTime'):
        raw = rp.get(key)
        if not raw:
            continue
        try:
            s = str(raw).replace('Z', '+00:00')
            dt = datetime.fromisoformat(s)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            ep = int(dt.timestamp())
            break
        except Exception:
            pass
    print(f'{rid}|{ep}')
" "$body"
  local rc=$?
  rm -f "$body"
  [[ $rc -eq 0 ]] || return 1
  return 0
}

mold_backup_veeam_rp_exists() {
  local rp_id="$1" job="${2:-${VEEAM_JOB_NAME:-}}"
  local rp_esc job_esc ps_script out
  [[ -n "${VEEAM_SSH_HOST:-}" && -n "$rp_id" ]] || return 1
  rp_esc="${rp_id//\'/\'\'}"
  job_esc="${job//\'/\'\'}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'SilentlyContinue'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
\$want = '${rp_esc}'.Trim('{}').ToLower()
\$JobHint = '${job_esc}'
function Rp-IdMatch(\$rp) {
  if (\$null -eq \$rp) { return \$false }
  \$id = \$rp.Id
  if (\$id -is [guid]) { \$id = \$id.Guid }
  return ([string]\$id).Trim('{}').ToLower() -eq \$want
}
\$found = \$false
\$backs = @()
if (\$JobHint) {
  \$cj = Get-VBRComputerBackupJob -Name \$JobHint -ErrorAction SilentlyContinue
  if (\$cj) { \$backs += @(Get-VBRBackup | Where-Object { \$_.JobId -eq \$cj.Id }) }
  \$backs += @(Get-VBRBackup | Where-Object { \$_.JobName -eq \$JobHint -or \$_.Name -like "*\$JobHint*" })
}
foreach (\$b in \$backs) {
  foreach (\$cand in @(\$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue)) {
    if (Rp-IdMatch \$cand) { \$found = \$true; break }
  }
  if (\$found) { break }
}
if (-not \$found) {
  \$rp = Get-VBRRestorePoint -ErrorAction SilentlyContinue | Where-Object { Rp-IdMatch \$_ } | Select-Object -First 1
  if (\$rp) { \$found = \$true }
}
if (\$found) { Write-Output 'EXISTS' } else { Write-Output 'MISSING' }
PS
)"
  out="$(VEEAM_SSH_CMD_TIMEOUT="${VEEAM_CATALOG_SYNC_SSH_TIMEOUT:-25}" \
    mold_backup_veeam_ssh_ps_capture "$ps_script" 2>/dev/null | tr -d '\r' | grep -E '^(EXISTS|MISSING)$' | tail -1)"
  [[ "$out" == "EXISTS" ]]
}

mold_backup_api_sync_veeam_backups() {
  local vm="$1" vm_id json
  [[ -n "$vm" ]] || return 1
  vm_id="$(mold_backup_api_get_vm_id "$vm" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || {
    mold_backup_notify_log warn "catalog-sync: cannot resolve Mold VM id for ${vm}"
    return 1
  }
  mold_backup_notify_log info "Mold syncAblestackVeeamBackups vm=${vm} id=${vm_id}"
  json="$(mold_backup_cmk_run syncAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
  if printf '%s' "$json" | grep -qiE 'success|true|"jobid"'; then
    return 0
  fi
  if [[ -n "$json" ]] && ! printf '%s' "$json" | grep -qiE 'error|exception|Failed to sync'; then
    return 0
  fi
  mold_backup_notify_log warn "syncAblestackVeeamBackups failed vm=${vm}: ${json:0:240}"
  return 1
}

mold_backup_registry_cleanup_for_backup() {
  local vm="$1" backup_id="$2"
  local reg_dir f
  [[ -n "$vm" && -n "$backup_id" ]] || return 0
  reg_dir="$(mold_backup_registry_dir)"
  [[ -d "$reg_dir" ]] || return 0
  shopt -s nullglob
  for f in "${reg_dir}/${vm}".*.backup-id "${reg_dir}/${vm}".rp-*.backup-id "${reg_dir}/${vm}".ckpt-*.backup-id; do
    [[ -f "$f" ]] || continue
    if [[ "$(tr -d '[:space:]' <"$f" 2>/dev/null)" == "$backup_id" ]]; then
      rm -f "$f" 2>/dev/null || true
    fi
  done
  shopt -u nullglob
}

mold_backup_catalog_delete_sync() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local state_dir state_f live_file prev_file live_ids deleted_id vm bid epoch
  local -a vms=()
  local -A sync_vms=()
  [[ -n "$job" ]] || return 0
  mold_backup_load_config 2>/dev/null || true
  mold_backup_resolve_api_secret 2>/dev/null || true

  if [[ -n "${VEEAM_RESTORE_VM:-}" && "${VEEAM_RESTORE_VM}" != "*" ]]; then
    IFS=',' read -ra vms <<<"${VEEAM_RESTORE_VM// /}"
  elif [[ -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]]; then
    IFS=',' read -ra vms <<<"${VM_INCLUDE// /}"
  else
    mold_backup_notify_log warn "catalog-sync: VM_INCLUDE/VEEAM_RESTORE_VM empty — skip (refuse host-wide sync trigger)"
    return 0
  fi

  state_dir="$(mold_backup_state_dir)/veeam-rp-catalog"
  mkdir -p "$state_dir" 2>/dev/null || true
  state_f="${state_dir}/$(mold_backup_safe_job_name "$job").rps"
  live_file="${state_f}.live"
  prev_file="${state_f}.prev"

  mold_backup_notify_log info "catalog-sync: job=${job} vms=${vms[*]} querying Veeam restore points"
  local list_ok=0 disk_gone=0 disk_probe
  : >"$live_file"
  # Fast trusted path: Disk backup object gone ⇒ full Remove-from-Disk.
  disk_probe="$(mold_backup_veeam_job_disk_backup_status "$job" 2>/dev/null || true)"
  if [[ "$disk_probe" == "NO_BACKUP" ]]; then
    disk_gone=1
    list_ok=1
    : >"$live_file"
    mold_backup_notify_log info "catalog-sync: Veeam Disk backup object gone for job=${job}"
    # Arm flag only — do not Start-VBR here. UI Start/Pull must remain one-shot;
    # empty Disk → operator runs Active Full once (mold-backup.sh active-full) or
    # enables VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY=true intentionally.
    mold_backup_veeam_mark_need_active_full "$job"
    if [[ "${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}" == "true" ]]; then
      mold_backup_veeam_ensure_active_full_if_needed "$job" || true
    else
      mold_backup_notify_log info "catalog-sync: need-active-full armed (auto-start disabled)"
    fi
  elif [[ "$disk_probe" == "HAS_BACKUP" ]]; then
    mold_backup_veeam_clear_need_active_full "$job"
    mold_backup_veeam_clear_active_full_attempted "$job"
    if mold_backup_list_veeam_job_restore_points "$job" >"${live_file}.tmp" 2>/dev/null \
        && grep -qE '^[0-9a-fA-F-]{8,}\|[0-9]+$' "${live_file}.tmp" 2>/dev/null; then
      mv -f "${live_file}.tmp" "$live_file" 2>/dev/null || cp -f "${live_file}.tmp" "$live_file"
      list_ok=1
    elif mold_backup_list_veeam_job_restore_points_rest "$job" >"${live_file}.tmp" 2>/dev/null \
        && grep -qE '^[0-9a-fA-F-]{8,}\|[0-9]+$' "${live_file}.tmp" 2>/dev/null; then
      mv -f "${live_file}.tmp" "$live_file" 2>/dev/null || cp -f "${live_file}.tmp" "$live_file"
      list_ok=1
    else
      mold_backup_notify_log warn "catalog-sync: Disk backup exists but RP list empty/untrusted — skip list-diff"
      list_ok=0
    fi
    rm -f "${live_file}.tmp" 2>/dev/null || true
    if mold_backup_veeam_arm_active_full_from_agent_log "$job"; then
      if [[ "${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}" == "true" ]]; then
        mold_backup_veeam_ensure_active_full_if_needed "$job" || true
      else
        mold_backup_notify_log info "catalog-sync: Agent chain broken flag armed (auto-start disabled)"
      fi
    fi
  else
    mold_backup_notify_log warn "catalog-sync: Disk backup probe failed — skip list-diff this run"
    list_ok=0
    if mold_backup_veeam_arm_active_full_from_agent_log "$job"; then
      if [[ "${VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY:-false}" == "true" ]]; then
        mold_backup_veeam_ensure_active_full_if_needed "$job" || true
      else
        mold_backup_notify_log info "catalog-sync: Agent log armed need-active-full (auto-start disabled)"
      fi
    fi
  fi
  live_ids="$(cut -d'|' -f1 "$live_file" 2>/dev/null | tr '[:upper:]' '[:lower:]' | sort -u)"
  local live_n prev_n
  live_n="$(printf '%s\n' "$live_ids" | grep -c . || true)"
  live_n="${live_n:-0}"
  prev_n="$(cut -d'|' -f1 "$state_f" 2>/dev/null | grep -c . || true)"
  prev_n="${prev_n:-0}"

  # Always probe registry-known RPs for VM_INCLUDE (works even when list APIs return empty).
  local map_file norm_rp
  map_file="$(mold_backup_registry_rp_map_file)"
  for vm in "${vms[@]}"; do
    vm="$(echo "$vm" | xargs)"
    [[ -n "$vm" && "$vm" != "*" ]] || continue
    shopt -s nullglob
    for f in "$(mold_backup_registry_dir)/${vm}".rp-*.backup-id; do
      [[ -f "$f" ]] || continue
      norm_rp="$(basename "$f")"
      norm_rp="${norm_rp#${vm}.rp-}"
      norm_rp="${norm_rp%.backup-id}"
      bid="$(tr -d '[:space:]' <"$f" 2>/dev/null || true)"
      [[ -n "$norm_rp" && -n "$bid" ]] || continue
      if echo "$live_ids" | grep -qx "$norm_rp"; then
        continue
      fi
      if mold_backup_veeam_rp_exists "$norm_rp" "$job" 2>/dev/null; then
        continue
      fi
      mold_backup_notify_log info "catalog-sync: registry RP missing on Veeam rp=${norm_rp} vm=${vm} → queue MS sync"
      sync_vms["$vm"]=1
      mold_backup_emit_restore_event "mold.backup.catalog-sync.pending" "$vm" "rp=${norm_rp};job=${job};source=registry-probe"
    done
    shopt -u nullglob
    if [[ -f "$map_file" ]]; then
      while read -r _line; do
        [[ "$_line" == *" vm=${vm} "* ]] || continue
        norm_rp="$(sed -n 's/.* rp=\([^ ]*\).*/\1/p' <<<"$_line" | tail -1)"
        bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$_line" | tail -1)"
        [[ -n "$norm_rp" && -n "$bid" ]] || continue
        if echo "$live_ids" | grep -qx "$norm_rp"; then
          continue
        fi
        if mold_backup_veeam_rp_exists "$norm_rp" "$job" 2>/dev/null; then
          continue
        fi
        mold_backup_notify_log info "catalog-sync: map RP missing on Veeam rp=${norm_rp} vm=${vm} → queue MS sync"
        sync_vms["$vm"]=1
        mold_backup_emit_restore_event "mold.backup.catalog-sync.pending" "$vm" "rp=${norm_rp};job=${job};source=map-probe"
      done <"$map_file"
    fi
  done

  if [[ ! -f "$state_f" ]]; then
    cp -f "$live_file" "$state_f" 2>/dev/null || true
    mold_backup_notify_log info "catalog-sync: seeded RP catalog (${live_n} points) — list-diff sync armed next run"
    # Still flush any registry-probe syncs found above.
  elif [[ "$list_ok" != "1" ]]; then
    mold_backup_notify_log warn "catalog-sync: untrusted empty/failed RP list — skip list-diff (registry-probe already ran)"
  elif [[ "$disk_gone" == "1" || ( "${live_n}" == "0" && "${prev_n}" != "0" ) ]]; then
    # Full Remove-from-Disk: Disk backup object gone, or trusted-empty live vs non-empty prev catalog.
    cp -f "$state_f" "$prev_file" 2>/dev/null || true
    mold_backup_notify_log info "catalog-sync: Veeam disk/job RPs all gone (was ${prev_n}, disk_gone=${disk_gone}) → queue MS sync for VM_INCLUDE"
    for vm in "${vms[@]}"; do
      vm="$(echo "$vm" | xargs)"
      [[ -n "$vm" && "$vm" != "*" ]] || continue
      sync_vms["$vm"]=1
      mold_backup_emit_restore_event "mold.backup.catalog-sync.pending" "$vm" "job=${job};source=full-disk-delete"
    done
    : >"$state_f"
  else
    cp -f "$state_f" "$prev_file" 2>/dev/null || true
    while IFS= read -r deleted_id; do
      [[ -n "$deleted_id" ]] || continue
      echo "$live_ids" | grep -qx "$deleted_id" && continue
      epoch="$(awk -F'|' -v id="$deleted_id" 'tolower($1)==id {print $2; exit}' "$prev_file" 2>/dev/null || true)"
      mold_backup_notify_log info "catalog-sync: Veeam RP removed id=${deleted_id} epoch=${epoch:-n/a} → queue MS sync for VM_INCLUDE"
      for vm in "${vms[@]}"; do
        vm="$(echo "$vm" | xargs)"
        [[ -n "$vm" && "$vm" != "*" ]] || continue
        sync_vms["$vm"]=1
        mold_backup_emit_restore_event "mold.backup.catalog-sync.pending" "$vm" "rp=${deleted_id};job=${job};source=list-diff"
      done
    done < <(cut -d'|' -f1 "$prev_file" 2>/dev/null | tr '[:upper:]' '[:lower:]' | sort -u)
    cp -f "$live_file" "$state_f" 2>/dev/null || true
  fi

  local synced=0
  for vm in "${!sync_vms[@]}"; do
    if mold_backup_api_sync_veeam_backups "$vm"; then
      synced=$((synced + 1))
      # Drop stale host registry maps for RPs that are no longer on Veeam.
      shopt -s nullglob
      for f in "$(mold_backup_registry_dir)/${vm}".rp-*.backup-id; do
        [[ -f "$f" ]] || continue
        norm_rp="$(basename "$f")"
        norm_rp="${norm_rp#${vm}.rp-}"
        norm_rp="${norm_rp%.backup-id}"
        echo "$live_ids" | grep -qx "$norm_rp" && continue
        mold_backup_veeam_rp_exists "$norm_rp" "$job" 2>/dev/null && continue
        bid="$(tr -d '[:space:]' <"$f" 2>/dev/null || true)"
        [[ -n "$bid" ]] && mold_backup_registry_cleanup_for_backup "$vm" "$bid"
      done
      shopt -u nullglob
      mold_backup_emit_restore_event "mold.backup.catalog-synced" "$vm" "job=${job}"
      mold_backup_notify_log info "catalog-sync: MS sync OK vm=${vm}"
    fi
  done
  mold_backup_notify_log info "catalog-sync: done job=${job} live_rps=${live_n} synced_vms=${synced}"
}

# VBR stores this .sh file on the backup server and uploads it to the Linux Agent.
# It runs locally on the KVM host; no SSH hop or local data export is required.
mold_backup_write_agent_hook_wrapper() {
  local output="$1" hook="$2"
  printf '#!/usr/bin/bash\nset -euo pipefail\nexec %q "$(hostname -s)"\n' "$hook" > "$output"
  chmod 0755 "$output"
}
