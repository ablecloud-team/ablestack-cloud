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
# NetBackup-style layout: /etc/ablestack/veeam/<job>.conf, host staging /tmp/mold/veeam

ABLESTACK_VEEAM_ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
MOLD_BACKUP_ETC_DIR="${MOLD_BACKUP_ETC_DIR:-${ABLESTACK_VEEAM_ETC_DIR}}"
MOLD_BACKUP_CONF="${MOLD_BACKUP_CONF:-}"
VEEAM_HOST_BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
# Veeam Agent SelectedFiles root. Mold stage (VEEAM_HOST_BACKUP_PATH) may hold large RBD
# .raw/.rbdiff for restore; Agent SyncDirs must only see this payload tree (markers / qcow2).
VEEAM_AGENT_PAYLOAD_PATH="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
CVT_BACKUP_SCRIPT="${CVT_BACKUP_SCRIPT:-}"
# Veeam host-mode disk export (libvirt → /tmp/mold/veeam). Never use Commvault kvm/ablestack_cvtbackup.sh.
HOST_EXPORT_SCRIPT="${HOST_EXPORT_SCRIPT:-${CVT_BACKUP_SCRIPT:-}}"

# Strip ASCII + Unicode curly/smart quotes from conf/UI-pasted values.
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

mold_backup_is_shared_provider_script() {
  # NAS/NetBackup/Commvault originals live next to other kvm scripts; Veeam must not reuse them.
  case "$1" in
    */hypervisor/kvm/ablestack_nasbackup.sh|*/hypervisor/kvm/ablestack_cvtbackup.sh) return 0 ;;
    *) return 1 ;;
  esac
}

mold_backup_resolve_host_export_script() {
  local cand
  if mold_backup_is_shared_provider_script "${HOST_EXPORT_SCRIPT:-}"; then
    HOST_EXPORT_SCRIPT=""
  fi
  if mold_backup_is_shared_provider_script "${CVT_BACKUP_SCRIPT:-}"; then
    CVT_BACKUP_SCRIPT=""
  fi
  for cand in \
    "${HOST_EXPORT_SCRIPT}" \
    "${CVT_BACKUP_SCRIPT}" \
    "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_veeam_host_export.sh" \
    "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_cvtbackup.sh" \
    "/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/ablestack_veeam_host_export.sh" \
    "/usr/share/mold/backup/veeam/ablestack_veeam_host_export.sh"; do
    [[ -n "$cand" && -x "$cand" ]] || continue
    mold_backup_is_shared_provider_script "$cand" && continue
    HOST_EXPORT_SCRIPT="$cand"
    CVT_BACKUP_SCRIPT="$cand"
    return 0
  done
  return 1
}

mold_backup_resolve_nas_backup_script() {
  local cand
  if mold_backup_is_shared_provider_script "${NAS_BACKUP_SCRIPT:-}"; then
    NAS_BACKUP_SCRIPT=""
  fi
  for cand in \
    "${NAS_BACKUP_SCRIPT}" \
    "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_veeam_nasbackup.sh" \
    "/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/ablestack_veeam_nasbackup.sh" \
    "/usr/share/mold/backup/veeam/ablestack_veeam_nasbackup.sh" \
    "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_nasbackup.sh"; do
    [[ -n "$cand" && -x "$cand" ]] || continue
    mold_backup_is_shared_provider_script "$cand" && continue
    NAS_BACKUP_SCRIPT="$cand"
    return 0
  done
  return 1
}
VEEAM_PROVIDER_NAME="${VEEAM_PROVIDER_NAME:-ablestack-veeam}"

# Resolve config: per-job .conf (when VEEAM_JOB_NAME set) → MOLD_BACKUP_CONF → defaults.
# Job conf must win over a previously cached MOLD_BACKUP_CONF (pre/post used to load
# mold-backup.conf first and then stick on that path for the real job load).
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
    # Guest Veeam jobs: Mold VM 10-10-254-70 → shared KVM policy conf
    if [[ "$job" == Mold\ VM\ * ]]; then
      if [[ -f "${ABLESTACK_VEEAM_ETC_DIR}/Mold_Guest_Backup.conf" ]]; then
        echo "${ABLESTACK_VEEAM_ETC_DIR}/Mold_Guest_Backup.conf"
        return 0
      fi
    elif [[ "$job" == Mold\ * ]]; then
      # Host Veeam job: Mold ablecube31-2 → Mold_Host_Backup.conf
      if [[ -f "${ABLESTACK_VEEAM_ETC_DIR}/Mold_Host_Backup.conf" ]]; then
        echo "${ABLESTACK_VEEAM_ETC_DIR}/Mold_Host_Backup.conf"
        return 0
      fi
    fi
  fi
  if [[ -n "${MOLD_BACKUP_CONF:-}" && -f "$MOLD_BACKUP_CONF" ]]; then
    echo "$MOLD_BACKUP_CONF"
    return 0
  fi
  for candidate in \
    "${ABLESTACK_VEEAM_ETC_DIR}/Mold_Guest_Backup.conf" \
    "${ABLESTACK_VEEAM_ETC_DIR}/mold-backup.conf" \
    "/etc/mold/backup/veeam/mold-backup.conf" \
    "${MOLD_BACKUP_ETC_DIR}/mold-backup.conf"; do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

# Allow runtime override: BACKUP_OPERATION=backup (Veeam post-notify path)
mold_backup_load_config() {
  local resolved
  local _preserve_backup_id="${BACKUP_ID:-}"
  local _preserve_restore_source="${RESTORE_SOURCE:-}"
  local _preserve_vm_name="${VM_NAME:-}"
  local _preserve_vm_uuid="${VM_UUID:-}"
  # Do NOT preserve ambient VM_INCLUDE: mold-backup.env / an early default conf load
  # (Job N-1) must not override the per-job .conf selected by VEEAM_JOB_NAME.
  # Single-VM scope is applied later via pre-notify $4, not via env bleed.
  VEEAM_JOB_NAME="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-}")"
  resolved="$(mold_backup_resolve_conf_path "${VEEAM_JOB_NAME:-}")" || {
    echo "Config not found — run veeam_config.sh or install.sh" >&2
    return 1
  }
  MOLD_BACKUP_CONF="$resolved"
  # shellcheck source=/dev/null
  source "$MOLD_BACKUP_CONF"
  # Caller-provided restore selectors must survive sourcing the job conf.
  [[ -n "${_preserve_backup_id}" ]] && BACKUP_ID="${_preserve_backup_id}"
  [[ -n "${_preserve_restore_source}" ]] && RESTORE_SOURCE="${_preserve_restore_source}"
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
  MOLD_DATADISK_PATH="$(mold_backup_strip_wrapping_quotes "${MOLD_DATADISK_PATH:-}")"
  BACKUP_REPO_ADDRESS="$(mold_backup_strip_wrapping_quotes "${BACKUP_REPO_ADDRESS:-}")"

  LOG_FILE="${LOG_FILE:-/var/log/mold/backup-veeam.log}"
  LOG_TAG="${LOG_TAG:-mold-veeam-backup}"
  NAS_BACKUP_SCRIPT="${NAS_BACKUP_SCRIPT:-/etc/ablestack/veeam/ablestack_veeam_nasbackup.sh}"
  mold_backup_resolve_nas_backup_script || true
  IMPORT_MODE="${IMPORT_MODE:-auto}"
  BACKUP_MODE="${BACKUP_MODE:-host}"
  VM_INCLUDE="${VM_INCLUDE:-*}"
  VM_EXCLUDE="${VM_EXCLUDE:-}"
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
  VEEAM_AGENT_PAYLOAD_PATH="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
  NAS_REPO_MOUNT="${NAS_REPO_MOUNT:-}"
  SOURCE_DISK_FORMAT="${SOURCE_DISK_FORMAT:-vmdk}"
  BOOTSTRAP_CHECKPOINT="${BOOTSTRAP_CHECKPOINT:-true}"
  QUIESCE_VM="${QUIESCE_VM:-false}"
  BACKUP_OPERATION="${BACKUP_OPERATION:-seed-import}"
  CLEANUP_STAGING_AFTER_BACKUP="${CLEANUP_STAGING_AFTER_BACKUP:-false}"
  CLEANUP_STAGING_ON_ERROR="${CLEANUP_STAGING_ON_ERROR:-false}"
  BACKUP_OFFERING_NAME="${BACKUP_OFFERING_NAME:-VeeamBackup}"
  BACKUP_REPO_TYPE="${BACKUP_REPO_TYPE:-local}"
  BACKUP_REPO_NAME="${BACKUP_REPO_NAME:-Ablestack Data Disk}"
  BACKUP_REPO_PROVIDER="${BACKUP_REPO_PROVIDER:-localfs}"
  MOLD_DATADISK_PATH="${MOLD_DATADISK_PATH:-}"
  BACKUP_STORAGE_MODE="${BACKUP_STORAGE_MODE:-datadisk}"
  BACKUP_STORAGE_ENGINE="${BACKUP_STORAGE_ENGINE:-auto}"
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
  VEEAM_UI_RESTORE_SOURCE="${VEEAM_UI_RESTORE_SOURCE:-mold-only}"
  RESTORE_LOCK_DIR="${RESTORE_LOCK_DIR:-}"
  VEEAM_RESTORE_WATCH_WINDOW_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}"
  VEEAM_TRIGGER_FLR_ON_MOLD_RESTORE="${VEEAM_TRIGGER_FLR_ON_MOLD_RESTORE:-true}"
  # FLR/restore-watch must use the Veeam-selected RP/checkpoint. Latest-only fallback is opt-in.
  RESTORE_ALLOW_LATEST_FALLBACK="${RESTORE_ALLOW_LATEST_FALLBACK:-false}"
  # Veeam UI FLR gives an RP GUID that is often missing from Mold details (Agent jobs).
  # Map RP CreationTime → nearest Mold checkpoint by default.
  RESTORE_RP_TIME_MATCH="${RESTORE_RP_TIME_MATCH:-true}"
  # Agent backup snap→RP finish can span hours; 3h keeps UI FLR time-match usable.
  RESTORE_RP_MATCH_MAX_DELTA_SEC="${RESTORE_RP_MATCH_MAX_DELTA_SEC:-10800}"

  mold_backup_resolve_api_secret
  [[ -n "${MOLD_BACKUP_OPERATION:-}" ]] && BACKUP_OPERATION="${MOLD_BACKUP_OPERATION}"
  mold_backup_supplement_guest_config
  mold_backup_apply_datadisk_profile
  # Guest mode is bidirectional: Mold UI backup must start the matching Veeam Agent job.
  if [[ "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
    VEEAM_TRIGGER_ENABLED=true
    case "${VEEAM_TRIGGER_METHOD:-}" in
      ''|auto) VEEAM_TRIGGER_METHOD=ssh ;;
    esac
  else
    VEEAM_TRIGGER_METHOD="${VEEAM_TRIGGER_METHOD:-auto}"
  fi
  return 0
}

# Datadisk + Veeam E:\opt1\veeam\<host>: Mold backups on KVM data disk (no NAS mount/restore).
mold_backup_apply_datadisk_profile() {
  if [[ "${BACKUP_STORAGE_MODE:-}" != "datadisk" && "${BACKUP_REPO_TYPE:-}" != "local" ]]; then
    return 0
  fi
  [[ -z "${KVM_HOSTNAME:-}" ]] && KVM_HOSTNAME="$(hostname -s 2>/dev/null || hostname)"
  BACKUP_REPO_TYPE=local
  BACKUP_REPO_PROVIDER="${BACKUP_REPO_PROVIDER:-localfs}"
  local disk="${MOLD_DATADISK_PATH:-${BACKUP_REPO_ADDRESS:-/data/backup}}"
  if [[ "$disk" == *glue-gfs* && -d /data/backup ]]; then
    disk="/data/backup"
  fi
  MOLD_DATADISK_PATH="$disk"
  BACKUP_REPO_ADDRESS="${MOLD_DATADISK_PATH}"
  BACKUP_REPO_NAME="${BACKUP_REPO_NAME:-Ablestack Data Disk}"
  NAS_REPO_MOUNT=""
  # 복원: datadisk bind-mount만 사용 (NAS/GFS 마운트·Veeam chain export 없음)
  RESTORE_SOURCE="mold-only"
  VEEAM_UI_RESTORE_SOURCE="mold-only"
  RESTORE_WATCH_TRIGGER_MOLD="${RESTORE_WATCH_TRIGGER_MOLD:-true}"
}

mold_backup_is_datadisk_mode() {
  [[ "${BACKUP_STORAGE_MODE:-}" == "datadisk" || "${BACKUP_REPO_TYPE:-}" == "local" ]]
}

mold_backup_datadisk_root() {
  mold_backup_apply_datadisk_profile
  echo "${MOLD_DATADISK_PATH:-${BACKUP_REPO_ADDRESS:-/data/backup}}"
}

# Read one KEY=value from an env file (strips optional quotes).
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

# Guest hooks use per-job .conf; VM_TARGETS often lives only in mold-backup.env.
mold_backup_vm_targets_merge() {
  local combined="" pair name ip out="" k
  declare -A _vm_target_map=()
  for combined in "$1" "$2"; do
    [[ -n "$combined" ]] || continue
    IFS=',' read -ra _pairs <<<"${combined// /}"
    for pair in "${_pairs[@]}"; do
      pair="${pair// /}"
      name="${pair%%:*}"
      ip="${pair#*:}"
      [[ -n "$name" && -n "$ip" && "$ip" != "$name" ]] || continue
      _vm_target_map["$name"]="$ip"
    done
  done
  for k in "${!_vm_target_map[@]}"; do
    [[ -n "$out" ]] && out+=","
    out+="${k}:${_vm_target_map[$k]}"
  done
  echo "$out"
}

# Write or update KEY="value" in a job .conf (best-effort; used to persist VM_TARGETS).
mold_backup_upsert_conf_var() {
  local conf="$1" key="$2" val="$3"
  [[ -f "$conf" && -n "$key" && -n "$val" ]] || return 0
  val="${val//\"/\\\"}"
  if grep -qE "^${key}=" "$conf" 2>/dev/null; then
    sed -i "s#^${key}=.*#${key}=\"${val}\"#" "$conf"
  else
    echo "${key}=\"${val}\"" >> "$conf"
  fi
}

mold_backup_supplement_guest_config() {
  local env_file key val env_targets
  for env_file in \
    "${ABLESTACK_VEEAM_ETC_DIR}/mold-backup.env" \
    "${MOLD_BACKUP_ETC_DIR}/mold-backup.env" \
    "$(dirname "${BASH_SOURCE[0]}")/mold-backup.env"; do
    [[ -f "$env_file" ]] || continue
    env_targets="$(mold_backup_read_env_var VM_TARGETS "$env_file" 2>/dev/null || true)"
    if [[ -n "$env_targets" ]]; then
      if [[ -n "${VM_TARGETS:-}" ]]; then
        VM_TARGETS="$(mold_backup_vm_targets_merge "$VM_TARGETS" "$env_targets")"
      else
        VM_TARGETS="$env_targets"
      fi
    fi
    for key in VEEAM_SSH_HOST VEEAM_SSH_USER VEEAM_SSH_KEY VEEAM_GUEST_JOB_PREFIX \
      VEEAM_TRIGGER_ENABLED VEEAM_TRIGGER_METHOD VEEAM_TRIGGER_TTL \
      VEEAM_AUTO_ACTIVE_FULL_ON_EMPTY \
      VEEAM_USERNAME VEEAM_PASSWORD VEEAM_API_URL; do
      [[ -n "${!key:-}" ]] && continue
      val="$(mold_backup_read_env_var "$key" "$env_file" 2>/dev/null || true)"
      [[ -n "$val" ]] && export "$key=$val"
    done
    break
  done
  # Persist merged VM_TARGETS into guest policy conf so hooks do not depend on env alone.
  if [[ -n "${VM_TARGETS:-}" && "${MOLD_BACKUP_CONF:-}" == *Mold_Guest_Backup.conf ]]; then
    if ! grep -qE '^[[:space:]]*VM_TARGETS=' "$MOLD_BACKUP_CONF" 2>/dev/null; then
      mold_backup_upsert_conf_var "$MOLD_BACKUP_CONF" VM_TARGETS "$VM_TARGETS"
    fi
  fi
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
  local secret_script="${MOLD_BACKUP_ETC_DIR}/mold-backup-secret.sh"
  [[ -x "$secret_script" ]] || secret_script="$(dirname "${BASH_SOURCE[0]}")/mold-backup-secret.sh"
  if [[ ! -x "$secret_script" ]]; then
    mold_backup_log warn "Cannot decrypt API secret: mold-backup-secret.sh not found"
    return 0
  fi
  MOLD_API_SECRET=$("$secret_script" decrypt --enc-file "${MOLD_API_SECRET_ENC_FILE}" --key-file "${MOLD_SECRET_KEY_FILE}") \
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

mold_backup_resolve_vm_name() {
  if [[ -n "${VM_NAME:-}" ]]; then
    return 0
  fi
  mold_backup_require_var VM_UUID
  local json name
  json=$(mold_backup_cmk_run listVirtualMachines "id=${VM_UUID}" 2>/dev/null) || true
  name=$(echo "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    vms = d.get('listvirtualmachinesresponse', {}).get('virtualmachine', [])
    if isinstance(vms, dict): vms = [vms]
    print(vms[0].get('instancename','') if vms else '')
except Exception:
    print('')
" 2>/dev/null)
  if [[ -n "$name" ]]; then
    VM_NAME="$name"
    mold_backup_log info "Resolved VM_NAME=${VM_NAME} from API"
    return 0
  fi
  mold_backup_die "VM_NAME is empty and could not be resolved from VM_UUID via API"
}

mold_backup_check_libvirt_vm() {
  mold_backup_resolve_vm_name
  if ! virsh -c qemu:///system dominfo "${VM_NAME}" >/dev/null 2>&1; then
    mold_backup_die "Libvirt domain [${VM_NAME}] not found on this host"
  fi
  mold_backup_log info "Libvirt domain [${VM_NAME}] is ready"
}

mold_backup_get_live_disk_paths() {
  mold_backup_log info "Resolving live libvirt disk paths (file + RBD)"
  mold_backup_get_all_disk_paths
}

mold_backup_get_all_disk_paths() {
  mold_backup_resolve_vm_name
  local paths=()
  local target
  while IFS= read -r target; do
    [[ -z "$target" ]] && continue
    paths+=("$target")
  done < <(virsh -c qemu:///system domblklist "${VM_NAME}" --details 2>/dev/null | awk '/disk/ {print $4}')
  if [[ ${#paths[@]} -eq 0 ]]; then
    mold_backup_die "No disks found for VM ${VM_NAME}"
  fi
  (IFS=,; echo "${paths[*]}")
}

mold_backup_has_rbd_disk() {
  local csv="${1:-}"
  [[ "$csv" == rbd:* ]] && return 0
  [[ "$csv" == *",rbd:"* ]] && return 0
  [[ "$csv" == *"protocol=rbd"* ]] && return 0
  # HCI often maps Ceph images via krbd: /dev/rbd/<pool>/<image>
  [[ "$csv" == /dev/rbd/* ]] && return 0
  [[ "$csv" == *"/dev/rbd/"* ]] && return 0
  return 1
}

# True if domain XML has any disk driver type=raw (HCI RBD often appears as raw).
mold_backup_domain_has_raw_disk() {
  local vm="$1"
  virsh -c qemu:///system dumpxml "$vm" 2>/dev/null \
    | grep -qiE "<driver[^>]*type=['\"]raw['\"]" \
    && return 0
  return 1
}

# True if domain uses RBD network disks (even when domblklist path is not rbd:...).
mold_backup_domain_has_rbd_disk() {
  local vm="$1" csv="${2:-}"
  [[ -z "$csv" ]] && csv="$(virsh -c qemu:///system domblklist "$vm" --details 2>/dev/null | awk '/disk/ {print $4}' | paste -sd, -)"
  mold_backup_has_rbd_disk "$csv" && return 0
  virsh -c qemu:///system dumpxml "$vm" 2>/dev/null \
    | grep -qiE "protocol=['\"]rbd['\"]|<source[^>]*protocol=['\"]rbd|/dev/rbd/" \
    && return 0
  return 1
}

# Prefer rbd: URIs from dumpxml / krbd device paths.
mold_backup_domain_rbd_disk_paths() {
  local vm="$1"
  {
    virsh -c qemu:///system dumpxml "$vm" 2>/dev/null | python3 -c "
import sys, re
xml = sys.stdin.read()
# <source protocol='rbd' name='pool/image'>
for m in re.finditer(r\"protocol=['\\\"]rbd['\\\"][^>]*name=['\\\"]([^'\\\"]+)['\\\"]\", xml, re.I):
    print('rbd:' + m.group(1))
for m in re.finditer(r\"name=['\\\"]([^'\\\"]+)['\\\"][^>]*protocol=['\\\"]rbd['\\\"]\", xml, re.I):
    print('rbd:' + m.group(1))
# <source dev='/dev/rbd/pool/image'>
for m in re.finditer(r\"dev=['\\\"](/dev/rbd/[^'\\\"]+)['\\\"]\", xml, re.I):
    print(m.group(1))
"
    virsh -c qemu:///system domblklist "$vm" --details 2>/dev/null \
      | awk '/disk/ && \$4 ~ /^\\/dev\\/rbd\\// {print \$4}'
  } 2>/dev/null | awk 'NF && !seen[$0]++'
}

# auto | qcow2 (GFS/file) | rbd (HCI/Ceph primary)
mold_backup_detect_storage_engine() {
  local disk_paths_csv="${1:-}"
  case "${BACKUP_STORAGE_ENGINE:-auto}" in
    qcow2|rbd) echo "${BACKUP_STORAGE_ENGINE}"; return 0 ;;
  esac
  if mold_backup_has_rbd_disk "$disk_paths_csv"; then
    echo "rbd"
  else
    echo "qcow2"
  fi
}

mold_backup_parse_rbd_volume_id() {
  local uri="$1" image=""
  [[ -n "$uri" ]] || return 0
  if [[ "$uri" == rbd:* ]]; then
    image="${uri#rbd:}"
    image="${image%%:*}"
  elif [[ "$uri" == /dev/rbd/* ]]; then
    # /dev/rbd/<pool>/<image>
    image="${uri##*/}"
  elif [[ "$uri" == rbd/* ]]; then
    image="${uri##*/}"
  fi
  echo "$image"
}

mold_backup_disk_target_kind() {
  local target
  target="$(echo "${1:-}" | tr '[:upper:]' '[:lower:]')"
  case "$target" in
    vda|sda|hda) echo "root" ;;
    *) echo "datadisk" ;;
  esac
}

# Lines: target|source_path (libvirt domblklist)
mold_backup_list_disk_specs() {
  mold_backup_resolve_vm_name
  virsh -c qemu:///system domblklist "${VM_NAME}" --details 2>/dev/null \
    | awk '/disk/ {print $3 "|" $4}'
}

mold_backup_qcow2_volume_id_from_path() {
  local path="$1" base uuid
  base="$(basename "$path")"
  uuid="${base%.qcow2}"
  uuid="${uuid%.raw}"
  if [[ "$uuid" =~ ^[0-9a-fA-F-]{36}$ ]]; then
    echo "$uuid"
    return 0
  fi
  echo ""
}

mold_backup_is_vm_running() {
  mold_backup_resolve_vm_name
  local state
  state=$(virsh -c qemu:///system dominfo "${VM_NAME}" 2>/dev/null | awk -F: '/^State:/ {gsub(/^[ \t]+/, "", $2); print $2; exit}')
  [[ "$state" == "running" ]]
}

mold_backup_clean_repo_address() {
  local addr="${BACKUP_REPO_ADDRESS}"
  addr="${addr#nfs://}"
  addr="${addr#cifs://}"
  echo "$addr"
}

mold_backup_meta_field() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || return 1
  grep -E "^${key}=" "$file" 2>/dev/null | head -1 | cut -d= -f2-
}

mold_backup_with_repo_mount() {
  local callback="$1"
  if mold_backup_is_datadisk_mode; then
    case "${BACKUP_REPO_TYPE:-local}" in
      nfs|cifs|glusterfs)
        mold_backup_die "datadisk mode: NAS/network restore disabled — use BACKUP_REPO_TYPE=local and MOLD_DATADISK_PATH=${MOLD_DATADISK_PATH:-/data/backup}"
        ;;
    esac
  fi
  if [[ -n "${NAS_REPO_MOUNT:-}" && -d "${NAS_REPO_MOUNT}" ]]; then
    "$callback" "${NAS_REPO_MOUNT}"
    return $?
  fi

  # Local data disk repo: BACKUP_REPO_ADDRESS is a directory on this host.
  case "${BACKUP_REPO_TYPE:-nfs}" in
    local|dir|localfs)
      local local_dir
      local_dir="$(mold_backup_clean_repo_address)"
      [[ -d "$local_dir" ]] || mold_backup_die "Local backup directory not found: ${local_dir}"
      "$callback" "$local_dir"
      return $?
      ;;
  esac

  mold_backup_require_var BACKUP_REPO_TYPE
  mold_backup_require_var BACKUP_REPO_ADDRESS
  [[ -x "${NAS_BACKUP_SCRIPT}" ]] || mold_backup_die "NAS backup script not found: ${NAS_BACKUP_SCRIPT}"

  local mount_point repo_addr nas_type mount_opts mopts=()
  mount_point=$(mktemp -d -t moldbackup.XXXXX)
  repo_addr=$(mold_backup_clean_repo_address)
  nas_type="${BACKUP_REPO_TYPE}"
  mount_opts="${BACKUP_REPO_MOUNT_OPTS:-}"
  if [[ "$nas_type" == "cifs" && -n "$mount_opts" ]]; then
    mount_opts="${mount_opts},nobrl"
  elif [[ "$nas_type" == "cifs" ]]; then
    mount_opts="nobrl"
  fi
  [[ -n "$mount_opts" ]] && mopts=(-o "$mount_opts")

  if ! mount -t "${nas_type}" "${repo_addr}" "${mount_point}" "${mopts[@]}" 2>/dev/null; then
    rmdir "${mount_point}" 2>/dev/null || true
    mold_backup_die "Failed to mount NAS repository ${repo_addr} for parent lookup"
  fi

  local rc=0
  "$callback" "${mount_point}" || rc=$?
  umount "${mount_point}" 2>/dev/null || true
  rmdir "${mount_point}" 2>/dev/null || true
  return "$rc"
}

# Sets PARENT_BACKUP_DIR_REL, PARENT_CHECKPOINT_NAME, PARENT_CHECKPOINT_PATH_REL, PARENT_BACKUP_FILES.
mold_backup_find_latest_nas_parent() {
  local mount_point="$1"
  mold_backup_resolve_vm_name

  local vm_dir="${mount_point}/${VM_NAME}"
  [[ -d "$vm_dir" ]] || {
    mold_backup_log err "No backup directory for VM on NAS: ${vm_dir}"
    return 1
  }

  local latest_name="" latest_dir="" d base
  for d in "${vm_dir}"/*; do
    [[ -d "$d" ]] || continue
    base=$(basename "$d")
    if [[ -f "${d}/veeam-seed.meta" || -d "${d}/checkpoints" ]]; then
      if [[ -z "$latest_name" || "$base" > "$latest_name" ]]; then
        latest_name="$base"
        latest_dir="$d"
      fi
    fi
  done

  [[ -n "$latest_dir" ]] || {
    mold_backup_log err "No seed or checkpoint backup found under ${vm_dir}"
    return 1
  }

  PARENT_BACKUP_DIR_REL="${VM_NAME}/${latest_name}"
  PARENT_CHECKPOINT_NAME=""
  PARENT_CHECKPOINT_PATH_REL=""
  PARENT_BACKUP_FILES=""

  if [[ -f "${latest_dir}/veeam-seed.meta" ]]; then
    PARENT_CHECKPOINT_NAME=$(mold_backup_meta_field "${latest_dir}/veeam-seed.meta" checkpoint_name || true)
    PARENT_BACKUP_FILES=$(mold_backup_meta_field "${latest_dir}/veeam-seed.meta" backup_files || true)
  elif [[ -f "${latest_dir}/rbd-backup.meta" ]]; then
    PARENT_CHECKPOINT_NAME=$(mold_backup_meta_field "${latest_dir}/rbd-backup.meta" checkpoint_name || true)
    PARENT_BACKUP_FILES=$(mold_backup_meta_field "${latest_dir}/rbd-backup.meta" backup_files || true)
  fi
  [[ -z "$PARENT_CHECKPOINT_NAME" ]] && PARENT_CHECKPOINT_NAME="$latest_name"

  if [[ -f "${latest_dir}/checkpoints/${PARENT_CHECKPOINT_NAME}.xml" ]]; then
    PARENT_CHECKPOINT_PATH_REL="${PARENT_BACKUP_DIR_REL}/checkpoints/${PARENT_CHECKPOINT_NAME}.xml"
  elif [[ -f "${latest_dir}/checkpoints/${PARENT_CHECKPOINT_NAME}.meta" ]]; then
    PARENT_CHECKPOINT_PATH_REL="${PARENT_BACKUP_DIR_REL}/checkpoints/${PARENT_CHECKPOINT_NAME}.meta"
  else
    mold_backup_log warn "Parent checkpoint file not found under ${latest_dir}/checkpoints; incremental may fail"
    PARENT_CHECKPOINT_PATH_REL="${PARENT_BACKUP_DIR_REL}/checkpoints/${PARENT_CHECKPOINT_NAME}.xml"
  fi

  mold_backup_log info "Local backup repo parent backup=${PARENT_BACKUP_DIR_REL} checkpoint=${PARENT_CHECKPOINT_NAME}"
  return 0
}

mold_backup_run_local_incremental_on_mount() {
  local mount_point="$1"
  mold_backup_find_latest_nas_parent "$mount_point" || mold_backup_die "Cannot resolve NAS parent for incremental backup"

  mold_backup_require_var BACKUP_REPO_TYPE
  mold_backup_require_var BACKUP_REPO_ADDRESS
  [[ -x "${NAS_BACKUP_SCRIPT}" ]] || mold_backup_die "NAS backup script not found: ${NAS_BACKUP_SCRIPT}"

  local disk_paths backup_path checkpoint backup_files repo_addr op quiesce btype
  disk_paths=$(mold_backup_get_all_disk_paths)
  backup_path=$(mold_backup_generate_backup_path)
  checkpoint="${backup_path##*/}"
  btype="FULL"
  [[ -n "${PARENT_BACKUP_DIR_REL:-}" ]] && btype="INCREMENTAL"
  if [[ -n "${PARENT_BACKUP_FILES:-}" ]]; then
    backup_files="${PARENT_BACKUP_FILES}"
    if mold_backup_has_rbd_disk "$disk_paths" && [[ "$btype" == "INCREMENTAL" ]]; then
      backup_files="${backup_files//.raw/.rbdiff}"
    fi
  else
    backup_files=$(mold_backup_build_backup_files "$disk_paths" "$btype")
  fi
  repo_addr=$(mold_backup_clean_repo_address)
  quiesce="${QUIESCE_VM:-false}"

  if mold_backup_has_rbd_disk "$disk_paths"; then
    op="backup-rbd"
    mold_backup_log info "Storage engine=rbd (HCI/Ceph primary)"
  elif mold_backup_is_vm_running; then
    op="backup-running"
  else
    mold_backup_die "VM ${VM_NAME} is not running; local incremental needs backup-running (start VM or use BACKUP_MODE=api)"
  fi

  mold_backup_log info "Local datadisk incremental op=${op} path=${backup_path} parent=${PARENT_BACKUP_DIR_REL}"
  "${NAS_BACKUP_SCRIPT}" \
    -o "${op}" \
    -v "${VM_NAME}" \
    -t "${BACKUP_REPO_TYPE}" \
    -s "${repo_addr}" \
    -m "${BACKUP_REPO_MOUNT_OPTS:-}" \
    -p "${backup_path}" \
    -b "INCREMENTAL" \
    -c "${checkpoint}" \
    -r "${PARENT_BACKUP_DIR_REL}" \
    -i "${PARENT_CHECKPOINT_NAME}" \
    -j "${PARENT_CHECKPOINT_PATH_REL}" \
    -q "${quiesce}" \
    -f "${backup_files}" \
    -d "${disk_paths}" \
    || mold_backup_die "ablestack_veeam_nasbackup.sh ${op} failed"
}

mold_backup_run_local_incremental() {
  mold_backup_with_repo_mount mold_backup_run_local_incremental_on_mount
}

mold_backup_run_backup() {
  case "${BACKUP_MODE}" in
    host)
      mold_backup_die "host mode uses pre-notify/post-notify hooks, not mold_backup_run_backup"
      ;;
    api)
      mold_backup_api_create_backup
      ;;
    local)
      mold_backup_run_local_incremental
      ;;
    auto)
      if mold_backup_cmk_bin >/dev/null 2>&1; then
        mold_backup_api_create_backup || {
          mold_backup_log warn "API incremental backup failed, trying local NAS script"
          mold_backup_run_local_incremental
        }
      else
        mold_backup_run_local_incremental
      fi
      ;;
    *)
      mold_backup_die "Invalid BACKUP_MODE=${BACKUP_MODE} (use api|local|auto)"
      ;;
  esac
}

mold_backup_check_staging() {
  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" ]]; then
    mold_backup_log info "FileLevel Veeam backup: staging VMDK optional (NAS seed uses live libvirt disks)"
    return 0
  fi
  if [[ -n "${STAGING_DISK_PATHS:-}" ]]; then
    mold_backup_log info "Using STAGING_DISK_PATHS from config"
    return 0
  fi
  mold_backup_require_var STAGING_PATH
  if [[ ! -d "${STAGING_PATH}" ]]; then
    mold_backup_die "Staging directory not found: ${STAGING_PATH}"
  fi
  mold_backup_log info "Staging directory OK: ${STAGING_PATH}"
}

# Disk paths for NAS seed: staging VMDK files, or live libvirt disks (FileLevel Agent).
mold_backup_resolve_seed_disk_paths() {
  local staging
  staging=$(mold_backup_list_staging_disks)
  if [[ -n "$staging" ]]; then
    echo "$staging"
    return 0
  fi
  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" ]]; then
    mold_backup_log info "No staging disks; using live libvirt disk paths for seed import"
    mold_backup_get_live_disk_paths
    return 0
  fi
  return 1
}

mold_backup_list_staging_disks() {
  if [[ -n "${STAGING_DISK_PATHS:-}" ]]; then
    echo "${STAGING_DISK_PATHS}"
    return 0
  fi
  find "${STAGING_PATH}" -maxdepth 3 -type f \( -name '*.vmdk' -o -name '*.flat' -o -name '*.qcow2' -o -name '*.raw' \) 2>/dev/null \
    | sort | paste -sd, -
}

mold_backup_generate_backup_path() {
  if [[ -n "${BACKUP_PATH:-}" ]]; then
    echo "${BACKUP_PATH}"
    return 0
  fi
  mold_backup_resolve_vm_name
  echo "${VM_NAME}/$(date '+%Y.%m.%d.%H.%M.%S.%3N')"
}

mold_backup_build_backup_files() {
  local disk_paths_csv="$1"
  local backup_type="${2:-FULL}"
  local -a out=()
  if [[ -n "${BACKUP_FILES:-}" ]]; then
    echo "${BACKUP_FILES}"
    return 0
  fi
  local engine suffix target path kind vol_id
  engine="$(mold_backup_detect_storage_engine "$disk_paths_csv")"
  suffix=".qcow2"
  [[ "$backup_type" == "INCREMENTAL" ]] && suffix=".rbdiff" || true
  if [[ "$engine" == "rbd" ]]; then
    [[ "$backup_type" == "INCREMENTAL" ]] && suffix=".rbdiff" || suffix=".raw"
    while IFS='|' read -r target path; do
      [[ -n "$path" ]] || continue
      vol_id="$(mold_backup_parse_rbd_volume_id "$path")"
      [[ -n "$vol_id" ]] || vol_id="$(basename "$path")"
      kind="$(mold_backup_disk_target_kind "$target")"
      out+=("${kind}.${vol_id}${suffix}")
    done < <(mold_backup_list_disk_specs)
  else
    local i=0
    while IFS='|' read -r target path; do
      [[ -n "$path" ]] || continue
      vol_id="$(mold_backup_qcow2_volume_id_from_path "$path")"
      kind="$(mold_backup_disk_target_kind "$target")"
      if [[ -n "$vol_id" ]]; then
        out+=("${kind}.${vol_id}.qcow2")
      else
        out+=("disk-${i}.qcow2")
        i=$((i + 1))
      fi
    done < <(mold_backup_list_disk_specs)
    if [[ ${#out[@]} -eq 0 ]]; then
      local -a disks
      IFS=, read -ra disks <<< "$disk_paths_csv"
      local j=0
      for _ in "${disks[@]}"; do
        out+=("disk-${j}.qcow2")
        j=$((j + 1))
      done
    fi
  fi
  [[ ${#out[@]} -gt 0 ]] || mold_backup_die "Cannot build backup file names for disks: ${disk_paths_csv}"
  (IFS=,; echo "${out[*]}")
}

mold_backup_veeam_export_ssh() {
  [[ "${ENABLE_VEEAM_SSH_EXPORT}" == "true" ]] || return 0
  mold_backup_require_var VEEAM_SSH_HOST
  mold_backup_require_var VEEAM_RESTORE_POINT_ID
  local remote_staging="${VEEAM_STAGING_PATH_ON_SERVER:-${STAGING_PATH}}"
  mold_backup_log info "Triggering Veeam FLR export on ${VEEAM_SSH_HOST}"
  ssh -i "${VEEAM_SSH_KEY}" -o StrictHostKeyChecking=no "${VEEAM_SSH_USER}@${VEEAM_SSH_HOST}" powershell -Command "
    Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
    \$rp = Get-VBRRestorePoint | Where-Object { \$_.Id -eq '${VEEAM_RESTORE_POINT_ID}' -or \$_.Id.Guid -eq '${VEEAM_RESTORE_POINT_ID}' }
    if (-not \$rp) { exit 1 }
    New-Item -ItemType Directory -Force -Path '${remote_staging}' | Out-Null
    \$session = Start-VBRFLRSession -RestorePoint \$rp
    Get-VBRFLRItem -Session \$session | Where-Object { \$_.Type -eq 'HardDisk' } | ForEach-Object {
      Copy-VBRFLRItem -FLRSession \$session -Item \$_ -Destination (Join-Path '${remote_staging}' (\$_.Name + '.vmdk'))
    }
    Stop-VBRFLRSession -Session \$session
  " || mold_backup_die "Veeam SSH export failed"
}

mold_backup_run_local_seed_import() {
  mold_backup_require_var BACKUP_REPO_TYPE
  mold_backup_require_var BACKUP_REPO_ADDRESS
  [[ -x "${NAS_BACKUP_SCRIPT}" ]] || mold_backup_die "NAS backup script not found: ${NAS_BACKUP_SCRIPT}"

  mold_backup_resolve_vm_name
  local disk_paths staging checkpoint backup_path backup_files repo_addr source_format btype
  disk_paths=$(mold_backup_get_all_disk_paths)
  staging=$(mold_backup_resolve_seed_disk_paths) || mold_backup_die "No seed disk paths (staging or live libvirt disks)"
  backup_path=$(mold_backup_generate_backup_path)
  checkpoint="${backup_path##*/}"
  btype="FULL"
  backup_files=$(mold_backup_build_backup_files "$disk_paths" "$btype")

  repo_addr="${BACKUP_REPO_ADDRESS}"
  repo_addr="${repo_addr#nfs://}"
  repo_addr="${repo_addr#cifs://}"

  source_format="${SOURCE_DISK_FORMAT:-vmdk}"
  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" ]]; then
    local first_seed="${staging%%,*}"
    if [[ -f "$first_seed" ]] && command -v qemu-img >/dev/null 2>&1; then
      if qemu-img info "$first_seed" 2>/dev/null | grep -q 'file format: qcow2'; then
        source_format="qcow2"
      elif qemu-img info "$first_seed" 2>/dev/null | grep -q 'file format: raw'; then
        source_format="raw"
      fi
    fi
  fi

  mold_backup_log info "Local datadisk seed import path=${backup_path} checkpoint=${checkpoint} source_format=${source_format}"
  "${NAS_BACKUP_SCRIPT}" \
    -o import-veeam-seed \
    -v "${VM_NAME}" \
    -t "${BACKUP_REPO_TYPE}" \
    -s "${repo_addr}" \
    -m "${BACKUP_REPO_MOUNT_OPTS:-}" \
    -p "${backup_path}" \
    -c "${checkpoint}" \
    -f "${backup_files}" \
    -d "${disk_paths}" \
    --staging-disks "${staging}" \
    --source-format "${source_format}" \
    --veeam-restore-point "${VEEAM_RESTORE_POINT_ID}" \
    --bootstrap-checkpoint "${BOOTSTRAP_CHECKPOINT}" \
    || mold_backup_die "ablestack_veeam_nasbackup.sh import-veeam-seed failed"
}

mold_backup_api_import_seed() {
  mold_backup_require_var VM_UUID
  local staging backup_name
  staging=$(mold_backup_resolve_seed_disk_paths) || mold_backup_die "No seed disk paths for API import"
  backup_name="$(mold_backup_api_build_backup_name_for_vm "${VM_UUID}" "${VM_NAME:-}")"
  if mold_backup_cmk_supports importAblestackVeeamBackupSeed 2>/dev/null; then
    mold_backup_cmk_run importAblestackVeeamBackupSeed \
    "virtualmachineid=${VM_UUID}" \
    "name=${backup_name}" \
    "veeamrestorepointid=${VEEAM_RESTORE_POINT_ID}" \
    "stagingdiskpaths=${staging}" \
    "sourcediskformat=${SOURCE_DISK_FORMAT}" \
    "bootstrapcheckpoint=${BOOTSTRAP_CHECKPOINT}"
    return 0
  fi
  mold_backup_die "importAblestackVeeamBackupSeed API not available in cloudmonkey/cmk"
}

mold_backup_api_create_backup() {
  mold_backup_api_create_veeam_backup
}

mold_backup_api_restore() {
  mold_backup_require_var BACKUP_ID
  local json job_id
  # Existing-VM restore requires Stopped; FLR→Mold auto-stops unless RESTORE_AUTO_STOP=false.
  if [[ -n "${VM_NAME:-}" ]]; then
    mold_backup_api_ensure_vm_stopped_for_restore "$VM_NAME" || return 1
  fi
  json=$(mold_backup_cmk_run restoreAblestackVeeamBackup "id=${BACKUP_ID}" 2>/dev/null) \
    || json=$(mold_backup_cmk_run restoreBackup "id=${BACKUP_ID}" 2>/dev/null) \
    || return 1
  job_id="$(mold_backup_api_json_field "$json" "restoreablestackveeambackupresponse.jobid")"
  [[ -z "$job_id" ]] && job_id="$(mold_backup_api_json_field "$json" "restorebackupresponse.jobid")"
  if [[ -n "$job_id" ]]; then
    mold_backup_notify_log info "restoreAblestackVeeamBackup job=${job_id}; waiting for MS/agent restore"
    mold_backup_api_wait_async_job "$job_id" 3600 || return 1
    mold_backup_notify_log info "Restore async job completed: ${job_id}"
    return 0
  fi
  return 0
}

mold_backup_cmk_supports() {
  local cmd="$1"
  local cmk
  cmk=$(mold_backup_cmk_bin) || return 1
  "${cmk}" -h 2>/dev/null | grep -q "${cmd}" || return 1
}

mold_backup_api_create_veeam_backup() {
  mold_backup_require_var VM_UUID
  local backup_name args interval_type
  backup_name="$(mold_backup_api_build_backup_name_for_vm "${VM_UUID}" "${VM_NAME:-}")"
  args=("virtualmachineid=${VM_UUID}" "name=${backup_name}")
  [[ "${QUIESCE_VM}" == "true" ]] && args+=("quiescevm=true")
  # Veeam Job (server-side) → Mold UI interval type is always EXTERNAL unless overridden.
  interval_type="$(mold_backup_resolve_veeam_interval_type "${VEEAM_SCHEDULE_NAME:-${SCHEDULE:-default}}")"
  [[ -n "$interval_type" ]] && args+=("intervaltype=${interval_type}")
  mold_backup_cmk_run createAblestackVeeamBackup "${args[@]}" \
    || mold_backup_cmk_run createBackup "${args[@]}"
}

# Map Veeam pre-notify schedule arg / config to Mold backup intervaltype.
# Veeam-triggered backups are not Mold schedules → UI shows EXTERNAL (not DAILY/HOURLY).
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

mold_backup_cleanup_staging() {
  [[ "${CLEANUP_STAGING_AFTER_BACKUP}" == "true" ]] || return 0
  # Guest VM mode: no Windows FLR staging on KVM hypervisor.
  if [[ "${BACKUP_MODE:-}" =~ ^(guest|veeam-guest)$ ]]; then
    return 0
  fi
  if [[ -n "${STAGING_DISK_PATHS:-}" ]]; then
    mold_backup_log info "Skipping staging dir cleanup (STAGING_DISK_PATHS set)"
    return 0
  fi
  if [[ -z "${STAGING_PATH:-}" ]]; then
    mold_backup_log info "Skipping staging cleanup (STAGING_PATH not set)"
    return 0
  fi
  if [[ ! -d "${STAGING_PATH}" ]]; then
    mold_backup_log info "Staging path already absent: ${STAGING_PATH}"
    return 0
  fi
  # STAGING_PATH is often the same as VEEAM_HOST_BACKUP_PATH (/tmp/mold/veeam).
  # Deleting *.raw/*.meta there destroys the Mold RBD FULL base and breaks restore.
  local host_stage="${VEEAM_HOST_BACKUP_PATH:-}"
  if [[ -n "$host_stage" ]]; then
    local stage_real host_real
    stage_real="$(readlink -f "${STAGING_PATH}" 2>/dev/null || echo "${STAGING_PATH}")"
    host_real="$(readlink -f "${host_stage}" 2>/dev/null || echo "${host_stage}")"
    if [[ "$stage_real" == "$host_real" || "$stage_real" == "$host_real"/* || "$host_real" == "$stage_real"/* ]]; then
      mold_backup_notify_log info "Skip staging cleanup: path is Mold durable backup store (${STAGING_PATH})"
      return 0
    fi
  fi
  mold_backup_log info "Cleaning staging directory: ${STAGING_PATH}"
  find "${STAGING_PATH}" -mindepth 1 -maxdepth 3 \( -name '*.vmdk' -o -name '*.flat' -o -name '*.qcow2' -o -name '*.raw' -o -name '*.meta' \) -delete 2>/dev/null || true
  find "${STAGING_PATH}" -mindepth 1 -maxdepth 2 -type d -empty -delete 2>/dev/null || true
}

mold_backup_import_seed() {
  mold_backup_check_staging
  mold_backup_require_var VEEAM_RESTORE_POINT_ID
  case "${IMPORT_MODE}" in
    api)
      mold_backup_api_import_seed
      ;;
    local)
      mold_backup_run_local_seed_import
      ;;
    auto)
      if mold_backup_cmk_bin >/dev/null 2>&1; then
        mold_backup_api_import_seed || {
          mold_backup_log warn "API import failed, trying local NAS import"
          mold_backup_run_local_seed_import
        }
      else
        mold_backup_run_local_seed_import
      fi
      ;;
    *)
      mold_backup_die "Invalid IMPORT_MODE=${IMPORT_MODE} (use api|local|auto)"
      ;;
  esac
}

mold_backup_run_operation() {
  case "${BACKUP_OPERATION}" in
    seed-import)
      mold_backup_veeam_export_ssh
      mold_backup_import_seed
      ;;
    backup)
      mold_backup_run_backup
      ;;
    restore)
      mold_backup_require_var BACKUP_ID
      mold_backup_api_restore
      ;;
    *)
      mold_backup_die "Unknown BACKUP_OPERATION=${BACKUP_OPERATION}"
      ;;
  esac
}

# --- NetBackup-style policy/job hooks (bpstart / bpend / restore_notify) ---

mold_backup_notify_log() {
  local level="$1"
  shift
  LOG_FILE="${LOG_FILE:-/var/log/mold/veeam-hook.log}"
  LOG_TAG="${LOG_TAG:-mold-veeam-hook}"
  mold_backup_log "$level" "$@"
}

mold_backup_state_dir() {
  echo "${ABLESTACK_VEEAM_ETC_DIR}/state"
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

# Restore-watch target: libvirt domain and/or Mold VM on this hypervisor (shut-off OK).
mold_backup_vm_restorable_on_local_host() {
  local vm="$1"
  [[ -n "$vm" ]] || return 1
  if mold_backup_domain_exists "$vm" 2>/dev/null; then
    return 0
  fi
  local vm_id
  vm_id="$(mold_backup_api_get_vm_id "$vm" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || return 1
  mold_backup_vm_owned_by_local_host "$vm" 2>/dev/null
}

# Pre-notify/list-backups targets: running VMs, plus explicit VM_INCLUDE names (even if shut off).
mold_backup_list_target_domains() {
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

# Always skip infrastructure / system domains unless VM_AUTO_EXCLUDE=false.
# Patterns: scvm*, CloudStack r-/s-/v-*-VM, *ablestack-template*.
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

# Match exclude token: exact name, or bash glob if token contains *.
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

mold_backup_api_update_config_if_needed() {
  local name="$1" value="$2"
  local current
  current="$(mold_backup_api_list_config_value "$name" 2>/dev/null || true)"
  [[ "$current" == "$value" ]] && return 0
  mold_backup_cmk_run updateConfiguration "name=${name}" "value=${value}" >/dev/null \
    || mold_backup_notify_log warn "updateConfiguration ${name} failed (may need admin API key)"
}

# Append provider to comma-separated backup.framework.provider.plugin without replacing others.
# Example: "dummy,nas,netbackup" + ablestack-veeam → "dummy,nas,netbackup,ablestack-veeam"
mold_backup_api_append_provider_plugin() {
  local provider="${1:-${VEEAM_PROVIDER_NAME:-ablestack-veeam}}"
  local current updated
  current="$(mold_backup_api_list_config_value "backup.framework.provider.plugin" 2>/dev/null || true)"
  updated="$(python3 -c "
import sys
cur = (sys.argv[1] or '').strip()
want = (sys.argv[2] or '').strip()
items = [i.strip() for i in cur.split(',') if i.strip()]
lowered = {i.lower() for i in items}
if want and want.lower() not in lowered:
    items.append(want)
print(','.join(items))
" "${current}" "${provider}" 2>/dev/null || echo "${provider}")"
  [[ -n "$updated" ]] || updated="$provider"
  if [[ "$updated" == "$current" ]]; then
    mold_backup_notify_log info "backup.framework.provider.plugin already contains ${provider} (${current})"
    return 0
  fi
  mold_backup_notify_log info "backup.framework.provider.plugin: '${current}' → '${updated}'"
  mold_backup_api_update_config_if_needed "backup.framework.provider.plugin" "$updated"
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

mold_backup_api_update_cluster_config_if_needed() {
  local name="$1" value="$2" cluster_id="$3"
  local current
  [[ -n "$cluster_id" ]] || return 0
  current="$(mold_backup_api_list_cluster_config_value "$name" "$cluster_id" 2>/dev/null || true)"
  [[ "$current" == "$value" ]] && return 0
  mold_backup_cmk_run updateConfiguration "name=${name}" "value=${value}" "clusterid=${cluster_id}" >/dev/null \
    && mold_backup_notify_log info "Enabled ${name}=${value} for cluster ${cluster_id}" \
    || mold_backup_notify_log warn "updateConfiguration ${name} clusterid=${cluster_id} failed (admin API key required)"
}

# kvm.incremental.backup defaults to false at cluster scope — MS always chooses FULL without this.
mold_backup_api_ensure_cluster_incremental_backup() {
  local json cluster_id
  [[ -n "${ZONE_ID:-}" ]] || return 0
  json=$(mold_backup_cmk_run listClusters "zoneid=${ZONE_ID}" 2>/dev/null) || return 0
  while IFS= read -r cluster_id; do
    [[ -z "$cluster_id" ]] && continue
    mold_backup_api_update_cluster_config_if_needed "kvm.incremental.backup" "true" "$cluster_id"
  done < <(printf '%s\n' "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    cs = d.get('listclustersresponse', {}).get('cluster', [])
    if isinstance(cs, dict): cs = [cs]
    for c in cs:
        cid = c.get('id')
        if cid:
            print(cid)
except Exception:
    pass
" 2>/dev/null)
}

mold_backup_api_ensure_global_settings() {
  local chain_size
  local stage_root
  mold_backup_api_update_config_if_needed "backup.framework.enabled" "true"
  mold_backup_api_update_config_if_needed "backup.enable.attach.detach.of.volumes" "true"
  # Append ablestack-veeam; do not wipe existing providers (dummy,nas,netbackup,...).
  mold_backup_api_append_provider_plugin "${VEEAM_PROVIDER_NAME:-ablestack-veeam}"
  [[ -n "${VEEAM_URL:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.url" "${VEEAM_URL}"
  [[ -n "${VEEAM_USERNAME:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.username" "${VEEAM_USERNAME}"
  [[ -n "${VEEAM_PASSWORD:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.password" "${VEEAM_PASSWORD}"
  stage_root="${VEEAM_HOST_BACKUP_PATH:-${STAGING_PATH:-/tmp/mold/veeam}}"
  if [[ -n "$stage_root" ]]; then
    mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.stage.root.path" "$stage_root"
  fi
  # Align Mold FULL↔incremental switch with host hook VEEAM_MAX_CHAIN when set.
  chain_size="${BACKUP_CHAIN_SIZE:-${VEEAM_MAX_CHAIN:-}}"
  if [[ -n "$chain_size" && "$chain_size" =~ ^[0-9]+$ && "$chain_size" -gt 0 ]]; then
    mold_backup_api_update_config_if_needed "backup.chain.size" "$chain_size"
  fi
  mold_backup_api_ensure_cluster_incremental_backup
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

mold_backup_api_list_backup_repositories() {
  local -a args=()
  [[ -n "${ZONE_ID:-}" ]] && args+=("zoneid=${ZONE_ID}")
  mold_backup_cmk_run listBackupRepositories "${args[@]}" 2>/dev/null
}

# First zone UUID (listZones) — used by veeam_config.sh auto-fill.
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

# Resolve ZONE_ID; if missing/invalid, refresh from listZones.
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

# Prefer ablestack-veeam if loaded on MS; else stock/custom "veeam".
# IMPORTANT: provider name stored on the offering must be ablestack-veeam for
# importAblestackVeeamBackupSeed. The display name "veeam" (Ablestack Veeam+NAS)
# is a different bean and requires a NAS backup repository.
mold_backup_api_detect_veeam_provider() {
  local json names
  # Probe: listBackupProviderOfferings with ablestack-veeam (even if listBackupProviders hides it via display-name merge)
  if [[ -n "${ZONE_ID:-}" ]]; then
    json=$(mold_backup_cmk_run listBackupProviderOfferings "provider=ablestack-veeam" "zoneid=${ZONE_ID}" 2>/dev/null || true)
    if echo "$json" | grep -qE '"externalid"[[:space:]]*:[[:space:]]*"veeam"|"name"[[:space:]]*:[[:space:]]*"veeam"'; then
      echo "ablestack-veeam"
      return 0
    fi
    # Any successful non-error response that is not clearly NAS-only
    if ! echo "$json" | grep -qiE 'errortext|errorcode'; then
      if echo "$json" | grep -qiE 'backupoffering' && ! echo "$json" | grep -qiE '"provider"[[:space:]]*:[[:space:]]*"nas"'; then
        echo "ablestack-veeam"
        return 0
      fi
    fi
  fi
  json=$(mold_backup_cmk_run listBackupProviders 2>/dev/null || true)
  names="$(echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    ps = d.get('listbackupprovidersresponse', {}).get('providers', [])
    if isinstance(ps, dict):
        ps = [ps]
    print(' '.join([(p.get('name') or '').lower() for p in ps]))
except Exception:
    pass
" 2>/dev/null || true)"
  if [[ " $names " == *" ablestack-veeam "* ]]; then
    echo "ablestack-veeam"
    return 0
  fi
  # Force ablestack-veeam when explicitly requested (list API may only show display name veeam)
  if [[ "${FORCE_ABLESTACK_VEEAM_PROVIDER:-false}" == "true" ]]; then
    echo "ablestack-veeam"
    return 0
  fi
  if [[ " $names " == *" veeam "* ]]; then
    echo "veeam"
    return 0
  fi
  echo "${VEEAM_PROVIDER_NAME:-veeam}"
}

# Pick externalid for Veeam family: never use NAS repository UUID.
# ablestack-veeam / Ablestack KVM veeam plugins accept literal "veeam".
mold_backup_api_pick_offering_external_id() {
  local provider="${1:-${VEEAM_PROVIDER_NAME}}"
  local json ext
  if [[ -n "${OFFERING_EXTERNAL_ID:-}" && "${OFFERING_EXTERNAL_ID}" != "8a4d0113-529b-40eb-9b9d-59e0d3f2d69d" ]]; then
    # Allow explicit override except the known NAS repo UUID mistake
    if [[ "${OFFERING_EXTERNAL_ID}" != *"nas"* ]]; then
      case "${OFFERING_EXTERNAL_ID}" in
        veeam|netbackup) echo "$OFFERING_EXTERNAL_ID"; return 0 ;;
      esac
      # UUID that is NOT already an imported NAS offering — still prefer literal veeam for veeam family
      :
    fi
  fi
  case "$provider" in
    ablestack-veeam|veeam)
      # Prefer fixed external id used by Ablestack KVM Veeam plugins
      echo "veeam"
      return 0
      ;;
  esac
  json=$(mold_backup_cmk_run listBackupProviderOfferings "provider=${provider}" "zoneid=${ZONE_ID}" 2>/dev/null || true)
  ext="$(echo "$json" | python3 -c "
import json, sys
provider = (sys.argv[1] or '').lower()
try:
    d = json.load(sys.stdin)
    offs = d.get('listbackupproviderofferingsresponse', {}).get('backupoffering', [])
    if isinstance(offs, dict):
        offs = [offs]
    for o in offs:
        p = (o.get('provider') or '').lower()
        n = (o.get('name') or '')
        e = o.get('externalid') or o.get('id') or ''
        if p in ('nas', 'ablestack-nas') or 'NAS' in n:
            continue
        if provider and p and p != provider and not (
            provider in ('veeam', 'ablestack-veeam') and p in ('veeam', 'ablestack-veeam')
        ):
            continue
        if e:
            print(e)
            raise SystemExit
except Exception:
    pass
" "$provider" 2>/dev/null || true)"
  [[ -n "$ext" ]] && { echo "$ext"; return 0; }
  echo "${OFFERING_EXTERNAL_ID:-veeam}"
}

# First backup repository NFS/CIFS address — used by veeam_config.sh auto-fill.
mold_backup_api_first_repo_address() {
  local json name="${1:-}"
  json=$(mold_backup_api_list_backup_repositories) || return 1
  echo "$json" | python3 -c "
import json, sys
name = sys.argv[1] if len(sys.argv) > 1 else ''
try:
    d = json.load(sys.stdin)
    repos = d.get('listbackuprepositoriesresponse', {}).get('backuprepository', [])
    if isinstance(repos, dict):
        repos = [repos]
    if name:
        for r in repos:
            if r.get('name') == name:
                print(r.get('address', ''))
                sys.exit(0)
    if repos:
        print(repos[0].get('address', ''))
except Exception:
    pass
" "$name" 2>/dev/null
}

# importBackupOffering externalid MUST equal backup repository UUID (see BackupRepositoryDaoImpl.findByBackupOfferingId).
mold_backup_api_find_backup_repository_uuid() {
  local json name="${1:-}"
  json=$(mold_backup_api_list_backup_repositories) || return 1
  echo "$json" | python3 -c "
import json, sys
name = sys.argv[1] if len(sys.argv) > 1 else ''
try:
    d = json.load(sys.stdin)
    repos = d.get('listbackuprepositoriesresponse', {}).get('backuprepository', [])
    if isinstance(repos, dict): repos = [repos]
    if name:
        for r in repos:
            if r.get('name') == name:
                print(r.get('id', ''))
                sys.exit(0)
    if repos:
        print(repos[0].get('id', ''))
except Exception:
    pass
" "$name" 2>/dev/null
}

mold_backup_api_find_backup_repository_uuid_by_address() {
  local address="$1" json
  [[ -n "$address" ]] || return 1
  address="${address#nfs://}"
  address="${address#cifs://}"
  json=$(mold_backup_api_list_backup_repositories) || return 1
  echo "$json" | python3 -c "
import json, sys
want = sys.argv[1]
def norm(a):
    if not a: return ''
    a = a.strip()
    for p in ('nfs://', 'cifs://'):
        if a.startswith(p):
            a = a[len(p):]
    return a
want = norm(want)
try:
    d = json.load(sys.stdin)
    repos = d.get('listbackuprepositoriesresponse', {}).get('backuprepository', [])
    if isinstance(repos, dict): repos = [repos]
    for r in repos:
        if norm(r.get('address', '')) == want:
            print(r.get('id', ''))
            sys.exit(0)
except Exception:
    pass
" "$address" 2>/dev/null
}

# True when id is a backup repository UUID (not a backup offering id).
mold_backup_api_repository_exists() {
  local want="$1" json
  [[ -n "$want" ]] || return 1
  json=$(mold_backup_api_list_backup_repositories 2>/dev/null) || return 1
  echo "$json" | python3 -c "
import json, sys
want = sys.argv[1]
try:
    d = json.load(sys.stdin)
    repos = d.get('listbackuprepositoriesresponse', {}).get('backuprepository', [])
    if isinstance(repos, dict):
        repos = [repos]
    for r in repos:
        if r.get('id') == want:
            sys.exit(0)
except Exception:
    pass
sys.exit(1)
" "$want"
}

# Create Mold backup repository when BACKUP_REPO_ADDRESS is set (addBackupRepository).
mold_backup_api_ensure_repository() {
  local repo_id name addr repo_type args json err
  repo_id="${BACKUP_REPOSITORY_UUID:-}"
  if [[ -n "$repo_id" ]]; then
    if mold_backup_api_repository_exists "$repo_id"; then
      echo "$repo_id"
      return 0
    fi
    mold_backup_notify_log warn "BACKUP_REPOSITORY_UUID=${repo_id} is not a repository id (maybe an offering id?) — resolving from address/name"
    repo_id=""
  fi

  name="${BACKUP_REPO_NAME:-Ablestack Veeam NAS}"
  if [[ -n "${BACKUP_REPO_ADDRESS:-}" ]]; then
    addr="$(mold_backup_clean_repo_address)"
    repo_id="$(mold_backup_api_find_backup_repository_uuid_by_address "$addr" 2>/dev/null || true)"
    [[ -n "$repo_id" ]] && { echo "$repo_id"; return 0; }
  fi
  repo_id="$(mold_backup_api_find_backup_repository_uuid "$name" 2>/dev/null || true)"
  [[ -n "$repo_id" ]] && { echo "$repo_id"; return 0; }
  repo_id="$(mold_backup_api_find_backup_repository_uuid 2>/dev/null || true)"
  [[ -n "$repo_id" ]] && { echo "$repo_id"; return 0; }

  [[ -n "${BACKUP_REPO_ADDRESS:-}" ]] || {
    mold_backup_notify_log err "No backup repository — set BACKUP_REPO_ADDRESS in conf or create in Mold UI"
    return 1
  }
  [[ -n "${ZONE_ID:-}" ]] || {
    mold_backup_notify_log err "ZONE_ID required to addBackupRepository"
    return 1
  }

  addr="$(mold_backup_clean_repo_address)"
  repo_type="${BACKUP_REPO_TYPE:-nfs}"
  args=(
    "name=${name}"
    "address=${addr}"
    "type=${repo_type}"
    "zoneid=${ZONE_ID}"
  )
  [[ -n "${BACKUP_REPO_MOUNT_OPTS:-}" ]] && args+=("mountoptions=${BACKUP_REPO_MOUNT_OPTS}")
  [[ -n "${BACKUP_REPO_PROVIDER:-}" ]] && args+=("provider=${BACKUP_REPO_PROVIDER}")

  mold_backup_notify_log info "addBackupRepository name=${name} address=${addr} type=${repo_type}"
  if ! json=$(mold_backup_cmk_run addBackupRepository "${args[@]}" 2>&1); then
    err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
    mold_backup_notify_log err "addBackupRepository failed${err:+: ${err}}"
    repo_id="$(mold_backup_api_find_backup_repository_uuid_by_address "$addr" 2>/dev/null || true)"
    [[ -n "$repo_id" ]] && { echo "$repo_id"; return 0; }
    return 1
  fi
  err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
  if [[ -n "$err" ]]; then
    mold_backup_notify_log err "addBackupRepository failed: ${err}"
    repo_id="$(mold_backup_api_find_backup_repository_uuid_by_address "$addr" 2>/dev/null || true)"
    [[ -n "$repo_id" ]] && { echo "$repo_id"; return 0; }
    return 1
  fi
  repo_id="$(mold_backup_api_json_field "$json" "addbackuprepositoryresponse.backuprepository.id")"
  [[ -n "$repo_id" ]] || repo_id="$(mold_backup_api_find_backup_repository_uuid "$name" 2>/dev/null || true)"
  [[ -n "$repo_id" ]] || {
    mold_backup_notify_log err "addBackupRepository returned no repository id"
    return 1
  }
  mold_backup_notify_log info "Backup repository ready id=${repo_id}"
  echo "$repo_id"
}

# Ensure backup offering exists. Datadisk/host mode: no Mold NAS repository —
# Prefer MS-loaded provider (ablestack-veeam or veeam) + valid ZONE_ID.
mold_backup_api_ensure_backup_resources() {
  local offering_id repo_id
  mold_backup_api_ensure_zone_id || return 1
  VEEAM_PROVIDER_NAME="$(mold_backup_api_detect_veeam_provider)"
  export VEEAM_PROVIDER_NAME
  mold_backup_notify_log info "Using backup provider=${VEEAM_PROVIDER_NAME}"

  if mold_backup_is_datadisk_mode || [[ "${VEEAM_PROVIDER_NAME}" == "ablestack-veeam" || "${VEEAM_PROVIDER_NAME}" == "veeam" ]]; then
    OFFERING_EXTERNAL_ID="$(mold_backup_api_pick_offering_external_id "${VEEAM_PROVIDER_NAME}")"
    export OFFERING_EXTERNAL_ID
    offering_id="$(mold_backup_api_ensure_offering)" || true
    offering_id="$(echo "$offering_id" | awk '/^[0-9a-fA-F-]{36}$/{print; exit}')"
    if [[ -n "$offering_id" ]]; then
      mold_backup_notify_log info "Backup offering ready id=${offering_id} provider=${VEEAM_PROVIDER_NAME} externalid=${OFFERING_EXTERNAL_ID}"
      echo "$offering_id"
      return 0
    fi
    mold_backup_notify_log err "importBackupOffering failed for ${VEEAM_PROVIDER_NAME} (see API error above). Need: Root Admin API key, valid ZONE_ID, provider enabled"
    return 1
  fi
  repo_id="$(mold_backup_api_ensure_repository)" || true
  repo_id="$(echo "$repo_id" | awk '/^[0-9a-fA-F-]{36}$/{print; exit}')"
  if [[ -n "$repo_id" ]]; then
    BACKUP_REPOSITORY_UUID="$repo_id"
    OFFERING_EXTERNAL_ID="${OFFERING_EXTERNAL_ID:-$repo_id}"
  fi
  offering_id="$(mold_backup_api_ensure_offering)" || true
  offering_id="$(echo "$offering_id" | awk '/^[0-9a-fA-F-]{36}$/{print; exit}')"
  [[ -n "$offering_id" ]] && { echo "$offering_id"; return 0; }
  return 1
}

mold_backup_api_get_offering_external_id() {
  local offering_id="$1" json
  json=$(mold_backup_api_list_backup_offerings 2>/dev/null) || return 1
  echo "$json" | python3 -c "
import json, sys
oid = sys.argv[1]
try:
    d = json.load(sys.stdin)
    offs = d.get('listbackupofferingsresponse', {}).get('backupoffering', [])
    if isinstance(offs, dict): offs = [offs]
    for o in offs:
        if o.get('id') == oid:
            print(o.get('externalid', ''))
            break
except Exception:
    pass
" "$offering_id" 2>/dev/null
}

# True if offering provider is ablestack-veeam / veeam family.
mold_backup_api_offering_is_veeam() {
  local offering_id="$1" json provider
  [[ -n "$offering_id" ]] || return 1
  json=$(mold_backup_api_list_backup_offerings 2>/dev/null) || return 0
  provider="$(echo "$json" | python3 -c "
import json, sys
oid = sys.argv[1]
try:
    d = json.load(sys.stdin)
    offs = d.get('listbackupofferingsresponse', {}).get('backupoffering', [])
    if isinstance(offs, dict):
        offs = [offs]
    for o in offs:
        if o.get('id') == oid:
            print((o.get('provider') or '').lower())
            break
except Exception:
    pass
" "$offering_id" 2>/dev/null || true)"
  case "$provider" in
    ablestack-veeam|veeam) return 0 ;;
    "") return 0 ;;  # cannot list offerings — let MS decide
    *) return 1 ;;
  esac
}

mold_backup_api_validate_offering_repository() {
  local offering_id="$1"
  if mold_backup_is_datadisk_mode; then
    [[ -n "$offering_id" ]] && return 0
    return 1
  fi
  local ext_id repo_id
  ext_id="$(mold_backup_api_get_offering_external_id "$offering_id" 2>/dev/null || true)"
  repo_id="${BACKUP_REPOSITORY_UUID:-}"
  [[ -n "$repo_id" ]] || repo_id="$(mold_backup_api_find_backup_repository_uuid "${BACKUP_REPO_NAME:-}" 2>/dev/null || true)"
  [[ -n "$repo_id" ]] || repo_id="$(mold_backup_api_find_backup_repository_uuid 2>/dev/null || true)"
  [[ -n "$repo_id" ]] || {
    mold_backup_notify_log err "No backup repository in zone — create one in Mold UI (Infrastructure → Backup Repositories)"
    return 1
  }
  [[ "$ext_id" == "$repo_id" ]] && return 0
  mold_backup_notify_log err "Backup offering externalid=${ext_id:-<empty>} does not match repository id=${repo_id}. Re-import offering with: externalid=${repo_id} (or set BACKUP_REPOSITORY_UUID in conf)"
  return 1
}

mold_backup_api_log_repository_hint() {
  local msg="$1"
  [[ "$msg" == *"backup repository"* ]] || return 0
  local repo_id
  repo_id="$(mold_backup_api_find_backup_repository_uuid 2>/dev/null || true)"
  mold_backup_notify_log err "Fix: importBackupOffering externalid must equal backup repository UUID${repo_id:+ (${repo_id})}"
}

mold_backup_api_log_agent_import_seed_hint() {
  local msg="$1"
  [[ "$msg" == *UnsupportedAnswer* ]] || return 0
  mold_backup_notify_log err "KVM mold-agent does not handle AblestackVeeamImportSeedCommand. Update mold-agent to a build that includes LibvirtAblestackVeeamImportSeedCommandWrapper, then: systemctl restart cloudstack-agent"
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
  # Do NOT alias ablestack-veeam ↔ veeam: provider=veeam is the NAS-hybrid bean
  # and fails seed import with "No valid backup repository found for the VM".
  echo "$json" | python3 -c "
import json, sys
provider = (sys.argv[1] or '').lower()
name = sys.argv[2] if len(sys.argv) > 2 else ''
aliases = {provider}
if provider == 'ablestack-nas':
    aliases.update(['ablestack-nas', 'nas'])
try:
    d = json.load(sys.stdin)
    offs = d.get('listbackupofferingsresponse', {}).get('backupoffering', [])
    if isinstance(offs, dict): offs = [offs]
    if name:
        for o in offs:
            if o.get('name') == name and (o.get('provider') or '').lower() in aliases:
                print(o.get('id', ''))
                sys.exit(0)
    for o in offs:
        p = (o.get('provider') or '').lower()
        if p in aliases:
            print(o.get('id', ''))
            break
except Exception:
    pass
" "$provider" "$offering_name" 2>/dev/null
}

mold_backup_api_ensure_offering() {
  local offering_id json err want_ext ext_id bad_id bad_provider
  mold_backup_api_ensure_zone_id || return 1
  [[ -n "${VEEAM_PROVIDER_NAME:-}" ]] || VEEAM_PROVIDER_NAME="$(mold_backup_api_detect_veeam_provider)"
  # Prefer ablestack-veeam when forced; otherwise keep MS display name (often "veeam").
  if [[ "${FORCE_ABLESTACK_VEEAM_PROVIDER:-false}" == "true" ]]; then
    VEEAM_PROVIDER_NAME=ablestack-veeam
    export VEEAM_PROVIDER_NAME
  fi
  offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" "$(mold_backup_offering_name)" 2>/dev/null || true)"
  [[ -n "$offering_id" ]] || offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
  # Name-only fallback (lab MS may register Ablestack plugin as provider=veeam).
  if [[ -z "$offering_id" ]]; then
    offering_id="$(mold_backup_api_find_offering_id veeam "$(mold_backup_offering_name)" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] && VEEAM_PROVIDER_NAME=veeam && export VEEAM_PROVIDER_NAME
  fi
  if [[ -z "$offering_id" ]]; then
    offering_id="$(mold_backup_api_find_offering_id ablestack-veeam "$(mold_backup_offering_name)" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] && VEEAM_PROVIDER_NAME=ablestack-veeam && export VEEAM_PROVIDER_NAME
  fi
  want_ext="$(mold_backup_api_pick_offering_external_id "${VEEAM_PROVIDER_NAME}")"
  OFFERING_EXTERNAL_ID="$want_ext"
  # Warn if a same-name offering exists under wrong provider=veeam (NAS hybrid).
  if [[ -z "$offering_id" ]]; then
    bad_id="$(mold_backup_api_find_offering_id veeam "$(mold_backup_offering_name)" 2>/dev/null || true)"
    if [[ -n "$bad_id" && "${VEEAM_PROVIDER_NAME}" == "ablestack-veeam" ]]; then
      mold_backup_notify_log err "Found '$(mold_backup_offering_name)' with provider=veeam (id=${bad_id}) — use VEEAM_PROVIDER_NAME=veeam or FORCE_ABLESTACK_VEEAM_PROVIDER=false"
      mold_backup_notify_log err "Or delete that offering and re-import as ablestack-veeam: bash diagnose-mold-veeam-offering.sh --import"
      return 1
    fi
  fi
  if [[ -n "$offering_id" && -n "$want_ext" ]]; then
    ext_id="$(mold_backup_api_get_offering_external_id "$offering_id" 2>/dev/null || true)"
    if [[ -n "$ext_id" && "$ext_id" != "$want_ext" ]]; then
      mold_backup_notify_log warn "Backup offering id=${offering_id} externalid=${ext_id} != ${want_ext}"
      mold_backup_notify_log warn "Delete '$(mold_backup_offering_name)' in Mold UI then re-run ensure (or set OFFERING_EXTERNAL_ID=${ext_id})"
      offering_id=""
    fi
  fi
  [[ -n "$offering_id" ]] && { echo "$offering_id"; return 0; }
  [[ -n "${ZONE_ID:-}" ]] || {
    mold_backup_notify_log err "ZONE_ID required to importBackupOffering"
    return 1
  }
  local name
  name="$(mold_backup_offering_name)"
  local ext_id="$want_ext"
  if [[ -z "$ext_id" ]]; then
    ext_id="$(mold_backup_api_find_backup_repository_uuid 2>/dev/null || true)"
  fi
  [[ -n "$ext_id" ]] || {
    mold_backup_notify_log err "No externalid for importBackupOffering — listBackupProviderOfferings returned empty (configure Veeam URL or set OFFERING_EXTERNAL_ID)"
    return 1
  }
  local retention="${RETENTION_PERIOD:-P7D}"
  local args=(
    "name=${name}"
    "description=Ablestack Veeam backup offering (${name})"
    "provider=${VEEAM_PROVIDER_NAME}"
    "externalid=${ext_id}"
    "zoneid=${ZONE_ID}"
    "allowuserdrivenbackups=false"
    "retentionperiod=${retention}"
  )
  mold_backup_notify_log info "importBackupOffering name=${name} provider=${VEEAM_PROVIDER_NAME} externalid=${ext_id} zone=${ZONE_ID}"
  if ! json=$(mold_backup_cmk_run importBackupOffering "${args[@]}" 2>&1); then
    err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
    mold_backup_notify_log err "importBackupOffering failed${err:+: ${err}}"
    [[ -n "$err" ]] || mold_backup_notify_log err "importBackupOffering raw: ${json:0:500}"
    job_id="$(mold_backup_api_json_field "$json" "importbackupofferingresponse.jobid")"
    if [[ -n "$job_id" ]]; then
      mold_backup_notify_log info "importBackupOffering async job=${job_id}; checking result"
      local job_json job_err
      job_json=$(mold_backup_api_wait_async_job "$job_id" 120 2>&1) || true
      job_err="$(mold_backup_api_extract_async_job_error "$job_json" 2>/dev/null || true)"
      [[ -n "$job_err" ]] && mold_backup_notify_log err "importBackupOffering async: ${job_err}"
      offering_id="$(mold_backup_api_json_field "$job_json" "queryasyncjobresultresponse.jobresult.backupoffering.id")"
      [[ -n "$offering_id" ]] && { echo "$offering_id"; return 0; }
    fi
    offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" "$name" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] || offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] && { echo "$offering_id"; return 0; }
    return 1
  fi
  err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
  if [[ -n "$err" ]]; then
    mold_backup_notify_log err "importBackupOffering failed: ${err}"
    offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] && { echo "$offering_id"; return 0; }
    return 1
  fi
  offering_id="$(mold_backup_api_json_field "$json" "importbackupofferingresponse.backupoffering.id")"
  local job_id
  job_id="$(mold_backup_api_json_field "$json" "importbackupofferingresponse.jobid")"
  if [[ -z "$offering_id" && -n "$job_id" ]]; then
    mold_backup_notify_log info "importBackupOffering async job=${job_id}; waiting"
    json=$(mold_backup_api_wait_async_job "$job_id" 300) || return 1
    offering_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backupoffering.id")"
    [[ -z "$offering_id" ]] && offering_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.id")"
  fi
  [[ -n "$offering_id" ]] || offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
  [[ -n "$offering_id" ]] || {
    mold_backup_notify_log err "importBackupOffering returned no offering id (async job may still be running; check listBackupOfferings)"
    return 1
  }
  echo "$offering_id"
}

mold_backup_safe_job_name() {
  echo "$1" | tr ' /' '__'
}

mold_backup_registry_dir() {
  echo "${ABLESTACK_VEEAM_ETC_DIR}/registry"
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

mold_backup_api_get_vm_offering_id() {
  local instance_name="$1" json
  json=$(mold_backup_api_get_vm_record "$instance_name") || return 1
  mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.backupofferingid"
}

# Mold UI backup name: {vm-hostname}-{yyyy-MM-ddTHH:mm:ss+0000} (same as netbackup / getBackupNameFromVM)
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

mold_backup_api_assign_offering_if_needed() {
  local vm_id="$1" offering_id="$2"
  local json current
  json=$(mold_backup_cmk_run listVirtualMachines "id=${vm_id}" 2>/dev/null) || return 1
  current="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.backupofferingid")"
  [[ "$current" == "$offering_id" ]] && return 0
  # Do not replace an existing (possibly non-Veeam) offering — caller must skip.
  if [[ -n "$current" ]]; then
    return 1
  fi
  mold_backup_cmk_run assignVirtualMachineToBackupOffering "virtualmachineid=${vm_id}" "backupofferingid=${offering_id}" >/dev/null \
    || mold_backup_notify_log warn "assignVirtualMachineToBackupOffering failed for vm=${vm_id}"
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
      mold_backup_api_log_repository_hint "$job_err"
      mold_backup_api_log_agent_import_seed_hint "$job_err"
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

# Mold rejects restore while the existing VM is Running.
# STOP ONLY for restore (FLR→Mold / restore-notify). Backup / pre-notify / post-notify
# must never call this — Running VMs stay Running during RBD/qcow2 host export.
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
  backup_name="$(mold_backup_api_build_backup_name_for_vm "$vm_id" "$vm_label")"
  ids_before="$(mold_backup_api_list_backup_ids "$vm_id" 2>/dev/null || true)"
  interval_type="$(mold_backup_resolve_veeam_interval_type "${VEEAM_SCHEDULE_NAME:-${SCHEDULE:-default}}")"
  veeam_job="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-${JOB_NAME:-}}")"
  mold_backup_notify_log info "createAblestackVeeamBackup vm=${vm_id} name=${backup_name} interval=${interval_type} job=${veeam_job:-n/a} (MS→agent NAS backup)"
  if [[ -n "$veeam_job" ]]; then
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
    mold_backup_notify_log info "createAblestackVeeamBackup job=${job_id}; waiting for MS/NAS backup"
    json=$(mold_backup_api_wait_async_job "$job_id" 1200) || return 1
    backup_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.id")"
    backup_type="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.type")"
    if [[ -z "$backup_id" ]]; then
      local latest
      # Wait for a NEW backup id (not in ids_before) to reach BackedUp — do not reuse older FULL.
      latest="$(mold_backup_api_wait_new_backup_for_vm "$vm_id" "$ids_before" 900 2>/dev/null || true)"
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

# Wait until a backup id not in ids_before reaches BackedUp. Prints "id|type".
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

# status_filter: backedup | backingup | any
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

# Backward-compatible alias
mold_backup_api_find_latest_backed_up_backup_for_vm() {
  mold_backup_api_find_latest_backup_for_vm "$1" "backedup"
}

# Step 4 — environment check: target VM + incremental chain count (설계: 대상머신, Chain 수)
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

mold_backup_api_check_vm_environment() {
  local vm_id="$1" vm_name="$2"
  local json chain_size max_chain offering_id
  max_chain="${VEEAM_MAX_CHAIN:-${BACKUP_CHAIN_SIZE:-10}}"
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || {
    mold_backup_notify_log info "env vm=${vm_name} id=${vm_id} chain=0 max=${max_chain} (no BackedUp backups)"
    return 0
  }
  chain_size=$(mold_backup_api_count_backed_up_from_json "$json")
  mold_backup_notify_log info "env vm=${vm_name} id=${vm_id} chain=${chain_size} max=${max_chain}"
  if [[ "$chain_size" -ge "$max_chain" ]]; then
    mold_backup_notify_log warn "Chain size ${chain_size} >= max ${max_chain}; next backup may be full"
  fi
}

mold_backup_api_veeam_backup_count() {
  local vm_id="$1"
  local json
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || {
    echo 0
    return 0
  }
  mold_backup_api_count_backed_up_from_json "$json"
}

# createAblestackVeeamBackup async job returns SuccessResponse (no backup id in jobresult).
# (implementation: mold_backup_api_find_latest_backup_for_vm / alias above)

mold_backup_collect_host_staging_paths() {
  local host_dir="$1"
  local -a files=()
  local f seen="" key
  [[ -d "$host_dir" ]] || return 1
  while IFS= read -r -d '' f; do
    key=",${f},"
    [[ "$seen" == *"$key"* ]] && continue
    seen="${seen}${key}"
    files+=("$f")
  done < <(find "$host_dir" -maxdepth 1 -type f \( -name 'disk-*' -o -name '*.qcow2' -o -name '*.qcow' -o -name '*.vmdk' -o -name '*.raw' -o -name '*.rbdiff' \) -print0 2>/dev/null)
  [[ ${#files[@]} -gt 0 ]] || return 1
  (IFS=,; echo "${files[*]}")
}

mold_backup_detect_staging_source_format() {
  local staging_paths="$1"
  local first="${staging_paths%%,*}"
  [[ -n "$first" && -f "$first" ]] || { echo "qcow2"; return 0; }
  if command -v qemu-img >/dev/null 2>&1; then
    if qemu-img info "$first" 2>/dev/null | grep -q 'file format: raw'; then
      echo "raw"
      return 0
    fi
    if qemu-img info "$first" 2>/dev/null | grep -q 'file format: qcow2'; then
      echo "qcow2"
      return 0
    fi
  fi
  case "$first" in
    *.raw) echo "raw" ;;
    *.vmdk) echo "vmdk" ;;
    *) echo "qcow2" ;;
  esac
}

# cmk may prefix stderr noise when captured with 2>&1; keep the JSON object only.
mold_backup_api_sanitize_json() {
  local raw="$1"
  printf '%s' "$raw" | python3 -c "
import json, sys
raw = sys.stdin.read()
start = raw.find('{')
if start < 0:
    print(raw)
    sys.exit(0)
blob = raw[start:]
for end in range(len(blob), 0, -1):
    try:
        d = json.loads(blob[:end])
        print(json.dumps(d))
        sys.exit(0)
    except Exception:
        pass
print(blob)
" 2>/dev/null
}

# Parse importAblestackVeeamBackupSeed response; waits on jobid when present.
# Prints backup_id|type on stdout.
mold_backup_api_finish_import_seed_response() {
  local json="$1"
  local job_id backup_id backup_type err
  json="$(mold_backup_api_sanitize_json "$json")"
  job_id="$(mold_backup_api_json_field "$json" "importablestackveeambackupseedresponse.jobid")"
  backup_id="$(mold_backup_api_json_field "$json" "importablestackveeambackupseedresponse.backup.id")"
  [[ -z "$backup_id" ]] && backup_id="$(mold_backup_api_json_field "$json" "importablestackveeambackupseedresponse.id")"
  backup_type="$(mold_backup_api_json_field "$json" "importablestackveeambackupseedresponse.backup.type")"
  [[ -z "$backup_type" ]] && backup_type="$(mold_backup_api_json_field "$json" "importablestackveeambackupseedresponse.type")"
  if [[ -n "$job_id" ]]; then
    mold_backup_notify_log info "importAblestackVeeamBackupSeed job=${job_id}; waiting for NAS seed import"
    json=$(mold_backup_api_wait_async_job "$job_id" 1200) || return 1
    json="$(mold_backup_api_sanitize_json "$json")"
    backup_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.id")"
    backup_type="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.type")"
    [[ -z "$backup_id" ]] && backup_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.id")"
    [[ -z "$backup_type" ]] && backup_type="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.type")"
  fi
  [[ -n "$backup_id" ]] && { echo "${backup_id}|${backup_type:-User}"; return 0; }
  err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
  mold_backup_notify_log err "importAblestackVeeamBackupSeed: no backup id in response${err:+ — ${err}} (raw=${json:0:240})"
  return 1
}

mold_backup_api_import_seed_and_wait() {
  local vm_id="$1" staging_paths="$2" source_format="${3:-qcow2}" vm_label="${4:-}"
  local json backup_name
  backup_name="$(mold_backup_api_build_backup_name_for_vm "$vm_id" "$vm_label")"
  mold_backup_notify_log info "importAblestackVeeamBackupSeed vm=${vm_id} name=${backup_name} (host staging, no MS→Veeam API)"
  json=$(mold_backup_cmk_run importAblestackVeeamBackupSeed \
    "virtualmachineid=${vm_id}" \
    "name=${backup_name}" \
    "stagingdiskpaths=${staging_paths}" \
    "sourcediskformat=${source_format}" \
    "bootstrapcheckpoint=true" 2>/dev/null) || return 1
  mold_backup_api_finish_import_seed_response "$json"
}

# Import NAS seed from KVM host staging; optionally tag the Veeam restore point on the backup.
mold_backup_api_import_staging_rp_seed_and_wait() {
  local vm_id="$1" staging_paths="$2" source_format="${3:-qcow2}" rp_id="${4:-}" vm_label="${5:-}"
  local json backup_name err
  [[ -n "$staging_paths" ]] || return 1
  backup_name="$(mold_backup_api_build_backup_name_for_vm "$vm_id" "$vm_label")"
  mold_backup_notify_log info "importAblestackVeeamBackupSeed vm=${vm_id} rp=${rp_id:-n/a} name=${backup_name} (host staging)"
  local -a api_args=(
    "virtualmachineid=${vm_id}"
    "name=${backup_name}"
    "stagingdiskpaths=${staging_paths}"
    "sourcediskformat=${source_format}"
    "bootstrapcheckpoint=true"
  )
  [[ -n "$rp_id" ]] && api_args+=("veeamrestorepointid=${rp_id}")
  if ! json=$(mold_backup_cmk_run importAblestackVeeamBackupSeed "${api_args[@]}" 2>/dev/null); then
    err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
    mold_backup_notify_log err "importAblestackVeeamBackupSeed failed: ${err:-${json:0:200}}"
    return 1
  fi
  mold_backup_api_finish_import_seed_response "$json"
}

# Import NAS seed from a Veeam restore point (MS exports disks from Veeam; no KVM staging).
mold_backup_api_import_rp_seed_and_wait() {
  local vm_id="$1" rp_id="$2" vm_label="${3:-}"
  local json backup_name err
  [[ -n "$rp_id" ]] || return 1
  backup_name="$(mold_backup_api_build_backup_name_for_vm "$vm_id" "$vm_label")"
  mold_backup_notify_log info "importAblestackVeeamBackupSeed vm=${vm_id} rp=${rp_id} name=${backup_name} (MS→Veeam export)"
  if ! json=$(mold_backup_cmk_run importAblestackVeeamBackupSeed \
    "virtualmachineid=${vm_id}" \
    "name=${backup_name}" \
    "veeamrestorepointid=${rp_id}" \
    "bootstrapcheckpoint=true" 2>/dev/null); then
    err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
    mold_backup_notify_log err "importAblestackVeeamBackupSeed (MS→Veeam) failed: ${err:-${json:0:200}}"
    return 1
  fi
  mold_backup_api_finish_import_seed_response "$json"
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

mold_backup_api_get_backup_type() {
  local backup_id="$1" json
  json=$(mold_backup_api_list_backup_details "$backup_id") || return 1
  echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listbackupsresponse', {}).get('backup', {})
    if isinstance(b, list):
        b = b[0] if b else {}
    print(b.get('type', '') or '')
except Exception:
    print('')
" 2>/dev/null
}

mold_backup_api_get_parent_backup_id() {
  local backup_id="$1"
  mold_backup_api_backup_detail_field "$backup_id" "nas.parent.backup.uuid"
}

mold_backup_api_is_full_backup() {
  local backup_id="$1" btype parent
  btype="$(mold_backup_api_get_backup_type "$backup_id" 2>/dev/null || true)"
  case "${btype^^}" in
    INCREMENTAL) return 1 ;;
    FULL) return 0 ;;
  esac
  parent="$(mold_backup_api_get_parent_backup_id "$backup_id" 2>/dev/null || true)"
  [[ -z "$parent" ]]
}

# Build restore chain oldest → newest (설계 5: inc 복원 시 필요한 백업본 배열)
mold_backup_api_build_restore_chain() {
  local backup_id="$1"
  local -a chain=()
  local current="$backup_id" parent visited=""
  while [[ -n "$current" ]]; do
    if [[ ",${visited}," == *",${current},"* ]]; then
      mold_backup_notify_log err "Restore chain cycle at backup ${current}"
      return 1
    fi
    visited="${visited},${current}"
    chain=("$current" "${chain[@]}")
    parent="$(mold_backup_api_get_parent_backup_id "$current" 2>/dev/null || true)"
    current="$parent"
  done
  (IFS=,; echo "${chain[*]}")
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

# Canonical per-VM latest backup id (used by restore-watch / Mold restore).
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

# Persist Veeam restore point → Mold backup_id (per VM) for FLR restore-watch.
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

mold_backup_registry_get_backup_id_by_rp() {
  local vm="$1" rp_id="$2"
  local norm_rp map_file line bid reg_dir
  [[ -n "$vm" && -n "$rp_id" ]] || return 1
  norm_rp="$(mold_backup_normalize_rp_id "$rp_id")"
  [[ -n "$norm_rp" ]] || return 1
  reg_dir="$(mold_backup_registry_dir)"
  if [[ -f "${reg_dir}/${vm}.rp-${norm_rp}.backup-id" ]]; then
    bid="$(tr -d '[:space:]' <"${reg_dir}/${vm}.rp-${norm_rp}.backup-id" 2>/dev/null || true)"
    [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  fi
  map_file="$(mold_backup_registry_rp_map_file)"
  if [[ -f "$map_file" ]]; then
    line="$(grep -E " rp=${norm_rp} vm=${vm} " "$map_file" 2>/dev/null | tail -1 || true)"
    bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
    [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  fi
  if [[ -d "$reg_dir" ]]; then
    line="$(grep -hE "vm=${vm}.*backup_id=.*rp=${norm_rp}|vm=${vm}.*rp=${norm_rp}.*backup_id=" "${reg_dir}"/*.log 2>/dev/null | tail -1 || true)"
    bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
    [[ -n "$bid" ]] && echo "$bid"
  fi
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

mold_backup_registry_get_backup_id_by_checkpoint() {
  local vm="$1" ckpt="$2" reg_dir line bid safe
  [[ -n "$vm" && -n "$ckpt" ]] || return 1
  reg_dir="$(mold_backup_registry_dir)"
  safe="$(printf '%s' "$ckpt" | tr -c 'A-Za-z0-9._-' '_')"
  if [[ -n "$safe" && -f "${reg_dir}/${vm}.ckpt-${safe}.backup-id" ]]; then
    bid="$(tr -d '[:space:]' <"${reg_dir}/${vm}.ckpt-${safe}.backup-id" 2>/dev/null || true)"
    [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  fi
  if [[ -f "${reg_dir}/checkpoint-backup.map" ]]; then
    line="$(grep -E " vm=${vm} ckpt=${ckpt} " "${reg_dir}/checkpoint-backup.map" 2>/dev/null | tail -1 || true)"
    bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
    [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  fi
  line="$(grep -hE "vm=${vm}.*backup_id=.*${ckpt}|backup_id=.*vm=${vm}.*${ckpt}|path=.*/${vm}/${ckpt}" \
    "${reg_dir}"/*.log /var/log/mold/veeam-hook.log 2>/dev/null | tail -1 || true)"
  bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
  [[ -n "$bid" ]] && echo "$bid"
}

mold_backup_registry_get_vm_backup_id() {
  local vm="$1" map_file line bid
  [[ -n "$vm" ]] || return 1
  if [[ -f "$(mold_backup_registry_dir)/${vm}.latest-backup-id" ]]; then
    bid="$(tr -d '[:space:]' < "$(mold_backup_registry_dir)/${vm}.latest-backup-id" 2>/dev/null || true)"
    [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  fi
  map_file="$(mold_backup_vm_backup_id_map_file)"
  [[ -f "$map_file" ]] || return 1
  line="$(grep -E "^[^ ]* vm=${vm} " "$map_file" 2>/dev/null | tail -1 || true)"
  bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
  [[ -n "$bid" ]] && echo "$bid"
}

# Legacy no-op (windows.conf / PowerShell automation removed).
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

# Record a restore event in the Mold-side registry (separate file per job, suffix .restore.log).
# event: source (veeam|mold), session id, restore point/end time. Used to reflect a restore
# performed directly in the Veeam UI back into Mold's state view.
mold_backup_registry_save_restore() {
  local job="$1" vm_name="$2" source="${3:-veeam}" session="${4:-}" detail="${5:-}" status="${6:-restored}"
  local reg_dir reg_file
  reg_dir="$(mold_backup_registry_dir)"
  mkdir -p "$reg_dir"
  reg_file="${reg_dir}/$(mold_backup_safe_job_name "$job").restore.log"
  echo "$(date -Iseconds) job=${job} vm=${vm_name} source=${source} session=${session} detail=${detail} status=${status}" >> "$reg_file"
  mold_backup_notify_log info "Saved restore registry: vm=${vm_name} source=${source} session=${session} status=${status}"
}

mold_backup_process_vm_pre_notify() {
  local vm_name="$1" offering_id="$2" state_file="$3"
  local vm_id backup_result backup_id backup_type host_path

  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || {
    mold_backup_notify_log err "No Mold VM id for ${vm_name}"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} status=fail reason=no-vm-id"
    return 1
  }

  mold_backup_api_check_vm_environment "$vm_id" "$vm_name"

  local vm_offering json
  json=$(mold_backup_cmk_run listVirtualMachines "id=${vm_id}" 2>/dev/null || true)
  vm_offering="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.backupofferingid")"

  # Existing non-Veeam offering → skip (never overwrite / remove).
  if [[ -n "$vm_offering" ]] && ! mold_backup_api_offering_is_veeam "$vm_offering"; then
    mold_backup_notify_log info "Skip ${vm_name}: already has non-Veeam offering ${vm_offering}"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=skip reason=existing-offering"
    return 2
  fi

  # No offering → assign VeeamBackup once. Already on Veeam → keep.
  if [[ -z "$vm_offering" && -n "$offering_id" ]]; then
    if mold_backup_api_assign_offering_if_needed "$vm_id" "$offering_id"; then
      json=$(mold_backup_cmk_run listVirtualMachines "id=${vm_id}" 2>/dev/null || true)
      vm_offering="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.backupofferingid")"
    fi
  fi
  [[ -n "$vm_offering" ]] || {
    mold_backup_notify_log err "VM ${vm_name} has no backup offering (assign '$(mold_backup_offering_name)' / ${VEEAM_PROVIDER_NAME} in Mold UI)"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=no-offering"
    return 1
  }
  if ! mold_backup_api_offering_is_veeam "$vm_offering"; then
    mold_backup_notify_log info "Skip ${vm_name}: offering ${vm_offering} is not veeam family"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=skip reason=existing-offering"
    return 2
  fi
  mold_backup_notify_log info "Using VM-assigned backup offering id=${vm_offering}"

  # Loop guard (bidirectional): if this Veeam run was itself triggered by a Mold
  # backup, the Mold NAS backup already happened — let Veeam do disk-only and skip
  # createBackup to avoid re-triggering Mold.
  if mold_backup_trigger_active "mold-active" "$vm_name"; then
    mold_backup_trigger_clear "mold-active" "$vm_name"
    mold_backup_notify_log info "mold-active marker present for ${vm_name}: Mold already backed up; Veeam disk-only (skip createBackup)"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=success reason=mold-triggered"
    return 0
  fi
  # Mark this VM as Veeam-driven so the Mold→Veeam hook does not start Veeam again.
  mold_backup_trigger_mark "veeam-active" "$vm_name"

  local chain_count staging_paths source_format
  chain_count="$(mold_backup_api_veeam_backup_count "$vm_id")"

  # Host Agent file-level:
  #  - RBD (any chain): Mold-native createAblestackVeeamBackup (snap + export/diff for Mold
  #    restore). Veeam Agent gets a tiny marker only — never SyncDirs sparse .raw/.rbdiff.
  #  - qcow2 first (no BackedUp): host export → importSeed → FULL (BackedUp)
  #  - qcow2 next: createAblestackVeeamBackup → agent TakeBackup → INCREMENTAL
  #    Do NOT call importSeed again (always FULL) and do NOT race a second host export.
  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" ]] && mold_backup_domain_has_rbd_disk "$vm_name"; then
    if ! mold_backup_domain_exists "$vm_name" 2>/dev/null; then
      mold_backup_notify_log err "File-level RBD: ${vm_name} is not Running in libvirt — start the VM then retry"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=vm-not-running"
      return 1
    fi
    mold_backup_api_validate_offering_repository "$vm_offering" || {
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=offering-repo-mismatch"
      return 1
    }
    mold_backup_notify_log info "File-level RBD: Mold-native createAblestackVeeamBackup (snap/diff; Veeam marker only; chain=${chain_count})"
    backup_result="$(mold_backup_api_create_veeam_and_wait "$vm_id" "$vm_name" || true)"
    if [[ -z "$backup_result" ]]; then
      mold_backup_notify_log err "Mold RBD backup failed for ${vm_name} (agent TakeBackup / BackedUp wait)"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=create-backup"
      return 1
    fi
    backup_id="${backup_result%%|*}"
    backup_type="${backup_result#*|}"
    host_path="$(mold_backup_api_backup_external_id "$vm_id" "$backup_id" 2>/dev/null || true)"
    [[ -z "$host_path" ]] && host_path="${VEEAM_HOST_BACKUP_PATH}/${vm_name}"
    mold_backup_state_write_line "$state_file" \
      "vm=${vm_name} id=${vm_id} backup_id=${backup_id} type=${backup_type} path=${host_path} status=success"
    mold_backup_publish_for_veeam_agent "$vm_name" "$host_path"
    mold_backup_notify_log info "Pre-notify OK vm=${vm_name} backup_id=${backup_id} type=${backup_type} path=${host_path} (mold-native RBD)"
    return 0
  fi

  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" && "${chain_count:-0}" -eq 0 ]]; then
    mold_backup_notify_log info "File-level first backup: host export → importAblestackVeeamBackupSeed"
    if ! host_path=$(mold_backup_run_host_export "$vm_name" "1" 2>/dev/null); then
      mold_backup_notify_log err "Host export failed for ${vm_name} (seed bootstrap; check /var/log/mold/veeam-hook.log and host-export output)"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=host-export"
      return 1
    fi
    if [[ ! -d "$host_path" ]]; then
      mold_backup_notify_log err "Host export returned invalid path (not a directory): ${host_path}"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=host-export"
      return 1
    fi
    staging_paths="$(mold_backup_collect_host_staging_paths "$host_path" 2>/dev/null || true)"
    if [[ -z "$staging_paths" ]]; then
      mold_backup_notify_log err "No staging disk files under ${host_path}"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=no-staging-disks"
      return 1
    fi
    source_format="$(mold_backup_detect_staging_source_format "$staging_paths")"
    mold_backup_api_validate_offering_repository "$vm_offering" || {
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=offering-repo-mismatch"
      return 1
    }
    backup_result="$(mold_backup_api_import_seed_and_wait "$vm_id" "$staging_paths" "$source_format" "$vm_name" 2>/dev/null || true)"
    if [[ -z "$backup_result" ]]; then
      mold_backup_notify_log err "importAblestackVeeamBackupSeed failed for ${vm_name}"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=import-seed"
      return 1
    fi
    backup_id="${backup_result%%|*}"
    backup_type="${backup_result#*|}"
    mold_backup_state_write_line "$state_file" \
      "vm=${vm_name} id=${vm_id} backup_id=${backup_id} type=${backup_type} path=${host_path} status=success"
    mold_backup_publish_for_veeam_agent "$vm_name" "$host_path"
    mold_backup_notify_log info "Pre-notify OK vm=${vm_name} backup_id=${backup_id} type=${backup_type} path=${host_path} (seed import)"
    return 0
  fi

  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" && "${chain_count:-0}" -gt 0 ]]; then
    # qcow2 INCREMENTAL needs live libvirt bitmaps (backup-running). Do not proceed if shut off —
    # that would push the KVM agent into STOPPED/dummy mode and can disrupt the guest.
    if ! mold_backup_domain_exists "$vm_name" 2>/dev/null; then
      mold_backup_notify_log err "File-level incremental: ${vm_name} is not Running in libvirt — start the VM then retry (qcow2 INCREMENTAL must not use STOPPED/dummy backup)"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=vm-not-running"
      return 1
    fi
    mold_backup_notify_log info "File-level incremental: createAblestackVeeamBackup (agent TakeBackup; chain=${chain_count})"
    backup_result="$(mold_backup_api_create_veeam_and_wait "$vm_id" "$vm_name" || true)"
    if [[ -z "$backup_result" ]]; then
      mold_backup_notify_log err "Mold incremental backup failed for ${vm_name} (agent TakeBackup / BackedUp wait)"
      mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=create-backup"
      return 1
    fi
    backup_id="${backup_result%%|*}"
    backup_type="${backup_result#*|}"
    # Agent writes under backup externalId (/tmp/mold/veeam/<vm>/<ts>); expose for Veeam file job.
    host_path="$(mold_backup_api_backup_external_id "$vm_id" "$backup_id" 2>/dev/null || true)"
    [[ -z "$host_path" ]] && host_path="${VEEAM_HOST_BACKUP_PATH}/${vm_name}"
    mold_backup_state_write_line "$state_file" \
      "vm=${vm_name} id=${vm_id} backup_id=${backup_id} type=${backup_type} path=${host_path} status=success"
    mold_backup_publish_for_veeam_agent "$vm_name" "$host_path"
    mold_backup_notify_log info "Pre-notify OK vm=${vm_name} backup_id=${backup_id} type=${backup_type} path=${host_path} (agent incremental)"
    return 0
  fi

  backup_result="$(mold_backup_api_create_veeam_and_wait "$vm_id" "$vm_name" || true)"
  if [[ -z "$backup_result" ]]; then
    mold_backup_notify_log err "Mold API backup request failed for ${vm_name} (see Async job failed above or agent.log)"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=create-backup"
    return 1
  fi
  backup_id="${backup_result%%|*}"
  backup_type="${backup_result#*|}"

  mold_backup_notify_log info "Host export starting vm=${vm_name} backup_id=${backup_id}"
  if ! host_path=$(mold_backup_run_host_export "$vm_name" 2>/dev/null); then
    mold_backup_notify_log err "Host export failed for ${vm_name} (backup_id=${backup_id})"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} backup_id=${backup_id} status=fail reason=host-export"
    return 1
  fi
  if [[ ! -d "$host_path" ]]; then
    mold_backup_notify_log err "Host export failed for ${vm_name} (backup_id=${backup_id})"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} backup_id=${backup_id} status=fail reason=host-export"
    return 1
  fi

  mold_backup_state_write_line "$state_file" \
    "vm=${vm_name} id=${vm_id} backup_id=${backup_id} type=${backup_type} path=${host_path} status=success"
  mold_backup_notify_log info "Pre-notify OK vm=${vm_name} backup_id=${backup_id} type=${backup_type} path=${host_path}"
  return 0
}

mold_backup_veeam_restore_chain_to_host() {
  # PowerShell chain export removed — datadisk/mold-only restore only.
  mold_backup_notify_log info "skip Veeam chain export (mold-only / no PowerShell restore-chain)"
  return 0
}

mold_backup_get_domain_disk_paths() {
  local vm_name="$1"
  # Export so command-substitution subshells (build_backup_files → list_disk_specs) see VM_NAME.
  export VM_NAME="$vm_name"
  mold_backup_get_all_disk_paths
}

mold_backup_run_host_export() {
  local vm_name="$1"
  local backup_subdir checkpoint disk_paths backup_files parent_dir parent_ckpt parent_ckpt_path backup_type op
  [[ -n "$vm_name" ]] || {
    mold_backup_notify_log err "Host export: empty vm name"
    return 1
  }
  # Host-wide job conf has empty VM_UUID/VM_NAME; pin the target for all nested helpers.
  export VM_NAME="$vm_name"
  if ! mold_backup_resolve_host_export_script; then
    mold_backup_notify_log err "Host export script not found (ablestack_veeam_host_export.sh). Run veeam/install.sh on this KVM host"
    return 1
  fi

  backup_subdir="${VEEAM_HOST_BACKUP_PATH}/${vm_name}/$(date '+%Y.%m.%d.%H.%M.%S.%3N')"
  checkpoint="$(basename "$backup_subdir")"
  mkdir -p "${VEEAM_HOST_BACKUP_PATH}/${vm_name}"
  disk_paths=$(mold_backup_get_domain_disk_paths "$vm_name")
  [[ -n "$disk_paths" ]] || {
    mold_backup_notify_log err "Host export: no disks for ${vm_name}"
    return 1
  }
  # HCI: prefer explicit rbd: URIs from dumpxml when available
  local rbd_paths
  rbd_paths="$(mold_backup_domain_rbd_disk_paths "$vm_name" | paste -sd, - 2>/dev/null || true)"
  [[ -n "$rbd_paths" ]] && disk_paths="$rbd_paths"

  parent_dir=""
  parent_ckpt=""
  parent_ckpt_path=""
  backup_type="FULL"
  op="backup-running"
  backup_files=$(mold_backup_build_backup_files "$disk_paths" "$backup_type")

  if mold_backup_domain_has_rbd_disk "$vm_name" "$disk_paths"; then
    op="backup-rbd"
    mold_backup_notify_log info "Host export storage engine=rbd (HCI)"
  elif mold_backup_domain_has_raw_disk "$vm_name"; then
    # libvirt checkpoint/bitmap unsupported for raw — host-export FULL without checkpoint
    op="backup-running"
    mold_backup_notify_log info "Host export: raw disk(s) detected — FULL without checkpoint"
  fi

  local latest_parent="${VEEAM_HOST_BACKUP_PATH}/${vm_name}"
  local latest_name="" latest_path="" d base force_full="${2:-0}"
  for d in "${latest_parent}"/*; do
    [[ -d "$d" ]] || continue
    base=$(basename "$d")
    [[ "$base" == "$checkpoint" ]] && continue
    if [[ -f "${d}/checkpoints/${base}.xml" || -f "${d}/checkpoints/${base}.meta" \
          || -f "${d}/rbd-backup.meta" || -f "${d}/veeam-seed.meta" ]]; then
      if [[ -z "$latest_name" || "$base" > "$latest_name" ]]; then
        latest_name="$base"
        latest_path="$d"
      fi
    fi
  done
  if [[ "$force_full" == "1" ]]; then
    backup_type="FULL"
    parent_dir=""
    parent_ckpt=""
    parent_ckpt_path=""
  elif [[ -n "$latest_path" && -f "${latest_path}/checkpoints/${latest_name}.xml" ]]; then
    backup_type="INCREMENTAL"
    parent_dir="${vm_name}/${latest_name}"
    parent_ckpt="$latest_name"
    parent_ckpt_path="${latest_path}/checkpoints/${latest_name}.xml"
    backup_files=$(mold_backup_build_backup_files "$disk_paths" "INCREMENTAL")
  elif [[ -n "$latest_path" && -f "${latest_path}/checkpoints/${latest_name}.meta" ]]; then
    backup_type="INCREMENTAL"
    parent_dir="${vm_name}/${latest_name}"
    parent_ckpt="$latest_name"
    parent_ckpt_path="${latest_path}/checkpoints/${latest_name}.meta"
    backup_files=$(mold_backup_build_backup_files "$disk_paths" "INCREMENTAL")
  elif [[ -n "$latest_path" && -f "${latest_path}/rbd-backup.meta" ]]; then
    backup_type="INCREMENTAL"
    parent_dir="${vm_name}/${latest_name}"
    parent_ckpt="$(mold_backup_meta_field "${latest_path}/rbd-backup.meta" checkpoint_name || echo "$latest_name")"
    parent_ckpt_path="${latest_path}/checkpoints/${parent_ckpt}.meta"
    [[ -f "$parent_ckpt_path" ]] || parent_ckpt_path="${latest_path}/rbd-backup.meta"
    backup_files=$(mold_backup_build_backup_files "$disk_paths" "INCREMENTAL")
  elif [[ -n "$latest_path" ]]; then
    mold_backup_notify_log warn "Prior export missing checkpoint xml under ${latest_path}; forcing FULL"
    backup_type="FULL"
    parent_dir=""
    parent_ckpt=""
    parent_ckpt_path=""
  fi

  mold_backup_notify_log info "Host export vm=${vm_name} path=${backup_subdir} type=${backup_type} op=${op}"
  local export_log
  export_log="$(mktemp "${TMPDIR:-/tmp}/mold-host-export.XXXXXX")"
  if ! "${HOST_EXPORT_SCRIPT}" \
    -o "${op}" \
    -v "${vm_name}" \
    -p "${backup_subdir}" \
    -b "${backup_type}" \
    -c "${checkpoint}" \
    -r "${parent_dir}" \
    -i "${parent_ckpt}" \
    -j "${parent_ckpt_path}" \
    -f "${backup_files}" \
    -d "${disk_paths}" \
    -q "${QUIESCE_VM:-false}" \
    >"$export_log" 2>&1; then
    mold_backup_notify_log err "Host export failed (${HOST_EXPORT_SCRIPT}): $(tail -5 "$export_log" | tr '\n' ' ')"
    rm -f "$export_log"
    return 1
  fi
  rm -f "$export_log"
  echo "${backup_subdir}"
}

# True if host_path looks like an RBD Mold stage (must not be SyncDirs'd as sparse .raw).
mold_backup_host_path_is_rbd() {
  local host_path="${1:-}"
  [[ -n "$host_path" && -d "$host_path" ]] || return 1
  [[ -f "${host_path}/rbd-backup.meta" ]] && return 0
  find "$host_path" -maxdepth 1 -type f \( -name '*.raw' -o -name '*.rbdiff' \) -print -quit 2>/dev/null | grep -q .
}

# Publish Mold backup artifacts for Veeam Agent SyncDirs under VEEAM_AGENT_PAYLOAD_PATH.
# RBD: marker + tiny meta only (Mold keeps .raw/.rbdiff under VEEAM_HOST_BACKUP_PATH for restore).
# QCOW2: hardlink/copy disk files into <vm>/current so Agent sees a stable tree.
mold_backup_publish_for_veeam_agent() {
  local vm_name="$1" host_path="$2"
  local publish legacy meta_f
  [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" ]] || return 0
  [[ -n "$vm_name" && -n "$host_path" && -d "$host_path" ]] || return 0

  publish="${VEEAM_AGENT_PAYLOAD_PATH}/${vm_name}/current"
  mkdir -p "$publish" || return 0
  rm -rf "${publish:?}/"* 2>/dev/null || true

  # Drop legacy hardlinks under stage/.../current that used to expose RBD .raw to SyncDirs.
  legacy="${VEEAM_HOST_BACKUP_PATH}/${vm_name}/current"
  if [[ -d "$legacy" ]]; then
    rm -rf "${legacy:?}/"* 2>/dev/null || true
    rmdir "$legacy" 2>/dev/null || true
  fi

  if mold_backup_host_path_is_rbd "$host_path"; then
    cat > "${publish}/mold-rbd-native.marker" <<EOF
vm=${vm_name}
mode=mold-native-rbd
stage_path=${host_path}
published_at=$(date -Iseconds)
EOF
    for meta_f in rbd-backup.meta domain-config.xml domain.xml veeam-seed.meta staging.complete; do
      [[ -f "${host_path}/${meta_f}" ]] || continue
      cp -a "${host_path}/${meta_f}" "${publish}/" 2>/dev/null || true
    done
    # FLR watch ignores agent-payload mtime changes shortly after our own publish.
    date +%s > "${publish}/.mold-agent-publish" 2>/dev/null || true
    chmod -R a+rX "$publish" 2>/dev/null || true
    mold_backup_notify_log info "Published RBD native marker for Veeam Agent (no .raw/.rbdiff SyncDirs): ${host_path} -> ${publish}"
    # Do NOT Start-VBR/Active Full here — we are already inside the Veeam job
    # that invoked pre-notify. Auto-start caused a second backup after one UI Start.
    return 0
  fi

  # QCOW2 / file-backed: prefer hardlinks over a full copy before Veeam SnapshotRequired.
  if command -v cp >/dev/null 2>&1 && cp -al "${host_path}/." "$publish/" 2>/dev/null; then
    mold_backup_notify_log info "Published staging (hardlink) for Veeam Agent: ${host_path} -> ${publish}"
  elif command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "${host_path}/" "${publish}/" 2>/dev/null || true
    mold_backup_notify_log info "Published staging (rsync) for Veeam Agent: ${host_path} -> ${publish}"
  else
    cp -a "${host_path}/." "$publish/" 2>/dev/null || true
    mold_backup_notify_log info "Published staging (copy) for Veeam Agent: ${host_path} -> ${publish}"
  fi
  date +%s > "${publish}/.mold-agent-publish" 2>/dev/null || true
  chmod -R a+rX "$publish" 2>/dev/null || true
  # Same as RBD: never auto-start another Veeam job from publish.
}

mold_backup_cleanup_host_path() {
  [[ -d "${VEEAM_HOST_BACKUP_PATH}" ]] || return 0
  # File-level Veeam Agent jobs track /tmp/mold/veeam via SyncDirs CBT.
  # Wiping the whole tree (or racing deletes) causes:
  #   "Failed to delete directory [...]; Failed to backup files /tmp/mold/veeam"
  # Default: do not clean for filelevel unless CLEANUP_STAGING_FORCE=true.
  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" && "${CLEANUP_STAGING_FORCE:-false}" != "true" ]]; then
    mold_backup_notify_log info "Skip host-path wipe (filelevel; set CLEANUP_STAGING_FORCE=true to prune)"
    return 0
  fi
  # Only touch this job's VMs — never wipe sibling jobs under the same staging root.
  local vm base d
  base="${VEEAM_HOST_BACKUP_PATH}"
  if [[ -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]]; then
    for vm in ${VM_INCLUDE//,/ }; do
      vm="$(echo "$vm" | xargs)"
      [[ -n "$vm" ]] || continue
      d="${base}/${vm}"
      [[ -d "$d" ]] || continue
      mold_backup_notify_log info "Cleaning host backup path (vm): ${d}"
      # Keep newest checkpoint dir; remove older ones so Veeam SyncDirs stays consistent.
      find "$d" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | tail -n +2 | cut -d' ' -f2- \
        | while IFS= read -r old; do
            [[ -n "$old" ]] || continue
            rm -rf "$old" 2>/dev/null || true
          done
    done
    return 0
  fi
  mold_backup_notify_log info "Cleaning host backup path: ${base}"
  find "${base}" -mindepth 1 -maxdepth 4 -type f -delete 2>/dev/null || true
  find "${base}" -mindepth 1 -maxdepth 3 -type d -empty -delete 2>/dev/null || true
}

# --- Bidirectional Mold<->Veeam trigger loop guard ---
# Short-lived markers under state/triggers/ break the trigger loop:
#   veeam-active-<vm> : set by pre_notify before requesting the Mold backup.
#                       The Mold->Veeam hook skips Start-VBRJob while present.
#   mold-active-<vm>  : set by the Mold->Veeam hook before Start-VBRJob.
#                       pre_notify skips createBackup while present (Veeam disk-only).
#   backup-cooldown   : set by post_notify after clearing veeam-active so restore-watch
#                       does not treat /tmp/mold/veeam/<vm>/ backup staging as FLR.
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

# libvirt VM name -> guest IP, from VM_TARGETS=i-2-5-VM:10.10.254.70,i-2-40-VM:10.10.254.61
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

# guest IP -> Veeam job name (legacy): 10.10.254.70 -> "Mold VM 10-10-254-70"
mold_backup_veeam_job_name_for_ip() {
  local ip="$1" prefix="${VEEAM_GUEST_JOB_PREFIX:-Mold VM}"
  echo "${prefix} ${ip//./-}"
}

# libvirt VM name -> Veeam job name: i-2-61-VM -> "Mold VM i-2-61-VM"
mold_backup_veeam_job_name_for_vm() {
  local vm="$1" prefix="${VEEAM_GUEST_JOB_PREFIX:-Mold VM}"
  echo "${prefix} ${vm}"
}

# Veeam job name -> libvirt VM name: "Mold VM i-2-61-VM" -> i-2-61-VM
mold_backup_vm_name_for_job() {
  local job="$1" prefix="${VEEAM_GUEST_JOB_PREFIX:-Mold VM}"
  [[ "$job" == "${prefix} "* ]] || return 1
  echo "${job#${prefix} }"
}

# guest IP -> libvirt VM name (reverse of mold_backup_vm_guest_ip), from VM_TARGETS.
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

# Resolve the Veeam B&R REST API base (https://host:9419) from explicit or SSH host.
mold_backup_veeam_api_base() {
  if [[ -n "${VEEAM_API_URL:-}" ]]; then
    echo "${VEEAM_API_URL%/}"
    return 0
  fi
  local host="${VEEAM_API_HOST:-${VEEAM_SSH_HOST:-}}"
  [[ -n "$host" ]] || return 1
  echo "https://${host}:${VEEAM_API_PORT:-9419}"
}

# Start a Veeam job via the native VBR REST API (port 9419) — no SSH required.
# Returns 0 on start (or already running), 1 on any failure (caller may fall back to SSH).
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

# Latest objectRestorePoint GUID for a job/computer name via VBR REST (short timeout; safe for post-notify).
# Prints GUID on stdout.
mold_backup_query_veeam_latest_restore_point_rest() {
  local name="${1:-}"
  local api ver user pass token
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
  local enc
  enc="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$name")"
  curl -sk --max-time 20 \
    "${api}/api/v1/objectRestorePoints?nameFilter=${enc}&orderColumn=CreationTime&orderAsc=false" \
    -H "x-api-version: ${ver}" -H "Authorization: Bearer ${token}" 2>/dev/null \
    | python3 -c "
import sys, json, re
want = '''${name}'''.lower()
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
items = d.get('data', d if isinstance(d, list) else [])
for rp in items:
    nm = str(rp.get('name') or '')
    jn = str(rp.get('backupJobName') or rp.get('jobName') or rp.get('backupName') or '')
    hay = (nm + ' ' + jn).lower()
    if want and want not in hay and nm.lower() != want and jn.lower() != want:
        continue
    rid = str(rp.get('id') or '')
    rid = re.sub(r'^urn:uuid:', '', rid, flags=re.I)
    if rid:
        print(rid)
        sys.exit(0)
sys.exit(1)
" 2>/dev/null
}

# Stamp Veeam RP / job onto an existing Mold backup row (catalog sync delete key).
mold_backup_api_update_veeam_backup() {
  local backup_id="$1" rp_id="${2:-}" job_name="${3:-}"
  local -a args=("id=${backup_id}")
  [[ -n "$rp_id" ]] && args+=("veeamrestorepointid=${rp_id}")
  [[ -n "$job_name" ]] && args+=("jobname=${job_name}")
  [[ ${#args[@]} -gt 1 ]] || return 0
  mold_backup_cmk_run updateAblestackVeeamBackup "${args[@]}" >/dev/null 2>&1 \
    || mold_backup_notify_log warn "updateAblestackVeeamBackup failed for backup=${backup_id}"
}

# Run a PowerShell script on Veeam via SSH.
# Small scripts: -EncodedCommand. Large scripts: scp .ps1 + pwsh -File (Windows cmd length limit).
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

# --- Active Full after Remove-from-Disk ---
# Empty Veeam Disk + normal Start ⇒ Agent FileBackup.IsBackupFilesystemExist.
# Auto Active Full is edge-triggered once per empty-Disk episode:
#   - arm when Disk goes NO_BACKUP / IsBackupFilesystemExist
#   - start at most once → set attempted latch
#   - clear latch only when Disk is HAS_BACKUP again, or operator runs active-full
# catalog-sync (~2m) must NOT keep restarting Active Full while Disk stays empty.

mold_backup_veeam_need_active_full_file() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  echo "$(mold_backup_trigger_dir)/need-active-full.$(mold_backup_safe_job_name "$job")"
}

# Latch: Active Full already requested for this empty-Disk episode.
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

# Compat alias used by older deploy snippets / docs.
mold_backup_veeam_mark_active_full_cooldown() {
  mold_backup_veeam_mark_active_full_attempted "$@"
}

mold_backup_veeam_active_full_in_cooldown() {
  mold_backup_veeam_active_full_already_attempted "$@"
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

# True when we should start Active Full now (need flag or NO_BACKUP, and not yet attempted).
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

# If Agent recently failed IsBackupFilesystemExist, arm need-active-full (once per episode).
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

# Start managed Agent job over SSH. full=true → Active Full (-FullBackup).
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

# Local Linux Agent CLI fallback (works when Veeam SSH is down).
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

# If Disk empty / chain broken: Active Full once per episode.
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

# Start a Veeam job over SSH (PowerShell) — fallback when REST cannot manage agent jobs.
# Uses Active Full automatically when Disk is empty / need-active-full is set.
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

# Start the matching Veeam Agent job (Mold->Veeam direction).
# Method: rest (curl, no SSH), ssh (PowerShell), or auto (REST then SSH fallback).
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

# --- Veeam UI restore -> Mold reflect (reverse restore sync) ---
# A restore performed directly in the Veeam UI restores guest data in-place (the Mold
# VM's disks are updated by the guest agent), so Mold storage is already current. To make
# Mold "aware" of it, the KVM host polls Veeam over the existing KVM->Veeam SSH channel for
# recently-completed restore sessions, maps the target computer IP back to a libvirt VM via
# VM_TARGETS, and records the event in the Mold restore registry (+ Mold hook log).
# Loop guard: a Mold-initiated restore sets the mold-restore-active marker, so the watcher
# skips reflecting Mold's own restore (avoids double-recording).

# State dir holding the set of already-reflected Veeam restore session ids.
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

# Query Veeam for restore sessions that completed within the last N minutes.
# Emits one line per session: sessionId|targetIp|endTimeUTC|result|name|backupName|restorePointId
# The target IP/computer is parsed out of the session Options XML (FLR/restore specs put
# IpOrDnsName/MachineName/BackupName there even when the session Name is hostname-based).
# Result is NOT filtered (e.g. an FLR session can end as 'Failed' even though files were
# restored), only completion + recency. Uses the existing KVM->Veeam SSH channel.
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
  if (-not \$rpId) {
    \$opt = [string]\$Session.Options
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
  if (\$rs -notmatch '^(?i)(Success|Warning)\$') { continue }
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
  if (\$epoch -le 0) {
    try { if (Valid-RestoreEnd \$r.CreationTimeUTC) { \$epoch = [int64]([DateTimeOffset]\$r.CreationTimeUTC).ToUnixTimeSeconds() } } catch {}
  }
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
        \$res -match '^(?i)(Success|Warning)\$' -and (
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

# Host Agent FLR restores files back under /tmp/mold/veeam/<vm>/ — detect locally when Veeam SSH returns nothing.
# Emits: sessionId|ip|epoch|result|name|backupName|restorePointId
mold_backup_restore_preflight() {
  local vm owner bid d
  mold_backup_notify_log info "restore preflight: host=$(mold_backup_local_kvm_name) job=${VEEAM_JOB_NAME:-n/a}"
  [[ -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]] || {
    mold_backup_notify_log warn "restore preflight: VM_INCLUDE not set"
    return 0
  }
  for vm in ${VM_INCLUDE//,/ }; do
    vm="$(echo "$vm" | xargs)"
    [[ -n "$vm" ]] || continue
    if mold_backup_domain_exists "$vm" 2>/dev/null; then
      mold_backup_notify_log info "restore preflight: ${vm} libvirt=running (RESTORE_AUTO_STOP=${RESTORE_AUTO_STOP:-true} will stop before Mold restore)"
    elif mold_backup_vm_restorable_on_local_host "$vm" 2>/dev/null; then
      mold_backup_notify_log info "restore preflight: ${vm} Mold Stopped on $(mold_backup_local_kvm_name) — no libvirt domain (normal for Mold; OK to restore)"
    else
      owner="$(mold_backup_api_get_vm_host_name "$vm" 2>/dev/null || echo unknown)"
      mold_backup_notify_log warn "restore preflight: ${vm} not on this host (Mold hostname=${owner}); run restore on owner KVM"
    fi
    d="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}/${vm}"
    if [[ -d "$d" ]] && [[ -n "$(find "$d" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
      mold_backup_notify_log info "restore preflight: ${vm} staging=${d} has files (FLR or backup staging)"
    else
      mold_backup_notify_log info "restore preflight: ${vm} staging=${d} empty — Veeam FLR must complete within restore-watch --since-min window"
    fi
    bid="$(mold_backup_registry_get_vm_backup_id "$vm" 2>/dev/null || true)"
    mold_backup_notify_log info "restore preflight: ${vm} registry backup_id=${bid:-none}"
  done
}

mold_backup_query_local_host_flr() {
  local since_min="${1:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}"
  # Default OFF: backup staging under /tmp/mold/veeam/<vm>/<ts>/ looks like "new files"
  # and was falsely treated as Veeam FLR → auto-stop + Mold restore after every backup.
  # Enable only when you rely on local FLR file drop without Veeam restore sessions.
  [[ "${LOCAL_HOST_FLR_TRIGGER:-false}" == "true" ]] || return 0
  [[ "${BACKUP_MODE:-host}" == "host" ]] || return 0
  [[ -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]] || return 0
  local base="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  local cutoff now vm d epoch newest last state_f sid kvm_ip ckpt rp_id newest_dir
  now=$(date +%s)
  cutoff=$((now - since_min * 60))
  kvm_ip="${KVM_IP:-}"
  [[ -z "$kvm_ip" && -n "${KVM_HOST:-}" && "$KVM_HOST" == *@* ]] && kvm_ip="${KVM_HOST#*@}"
  for vm in ${VM_INCLUDE//,/ }; do
    vm="$(echo "$vm" | xargs)"
    [[ -n "$vm" ]] || continue
    d="${base}/${vm}"
    if [[ ! -d "$d" ]]; then
      mold_backup_notify_log info "local FLR: ${vm} no staging dir ${d}"
      continue
    fi
    mold_backup_trigger_active "veeam-active" "$vm" && continue
    mold_backup_trigger_active "backup-cooldown" "$vm" && continue
    mold_backup_trigger_active "mold-restore-active" "$vm" && continue
    mold_backup_trigger_active "mold-flr-pushed" "$vm" && continue
    newest="$(find "$d" -mindepth 1 \( -type f -o -type d \) -printf '%T@\n' 2>/dev/null | sort -rn | head -1 || true)"
    if [[ -z "$newest" ]]; then
      mold_backup_notify_log info "local FLR: ${vm} staging empty ${d}"
      continue
    fi
    epoch="${newest%.*}"
    [[ "$epoch" =~ ^[0-9]+$ ]] || continue
    if [[ "$epoch" -lt "$cutoff" ]]; then
      mold_backup_notify_log info "local FLR: ${vm} files older than ${since_min}min (mtime epoch=${epoch}; widen --since-min or re-FLR)"
      continue
    fi
    # Skip Mold backup export trees (FULL/INCREMENTAL host/agent output).
    newest_dir="$(find "$d" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2- || true)"
    if [[ -n "$newest_dir" ]]; then
      if [[ -f "${newest_dir}/domain-config.xml" || -f "${newest_dir}/rbd-backup.meta" \
            || -f "${newest_dir}/veeam-seed.meta" || -d "${newest_dir}/checkpoints" ]]; then
        mold_backup_notify_log info "local FLR: ${vm} skip Mold backup export dir $(basename "$newest_dir") (not FLR)"
        continue
      fi
    fi
    state_f="$(mold_backup_state_dir)/restore-watch/flr-${vm}.last-epoch"
    mkdir -p "$(dirname "$state_f")" 2>/dev/null || true
    last=0
    [[ -f "$state_f" ]] && last="$(tr -d '[:space:]' <"$state_f" 2>/dev/null || echo 0)"
    [[ "$epoch" -le "${last:-0}" ]] && continue
    ckpt="$(find "$d" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort -rn | head -1 || true)"
    rp_id=""
    if [[ -n "$ckpt" ]]; then
      local reg_line
      reg_line="$(grep -h "vm=${vm}.*rp=" "$(mold_backup_registry_dir)"/*.log 2>/dev/null | tail -1 || true)"
      rp_id="$(sed -n 's/.*rp=\([^ ]*\).*/\1/p' <<<"$reg_line" | tail -1)"
      [[ -z "$rp_id" ]] && rp_id="$(tr -d '[:space:]' <"$(mold_backup_registry_dir)/${vm}.latest-rp-id" 2>/dev/null || true)"
    fi
    [[ -z "$rp_id" ]] && rp_id="$(mold_backup_query_veeam_rp_near_epoch "${VEEAM_JOB_NAME:-}" "$epoch" 2>/dev/null || true)"
    sid="local-flr-${vm}-${epoch}"
    mold_backup_notify_log info "local FLR detect: vm=${vm} epoch=${epoch} ckpt=${ckpt:-n/a} rp=${rp_id:-n/a}"
    printf '%s|%s|%s|Success|local-flr-%s|%s|%s\n' "$sid" "${kvm_ip:-}" "$epoch" "$vm" "${ckpt:-}" "${rp_id:-}"
  done
}

mold_backup_local_flr_mark_epoch() {
  local vm="$1" epoch="$2" state_f
  [[ -n "$vm" && -n "$epoch" ]] || return 0
  state_f="$(mold_backup_state_dir)/restore-watch/flr-${vm}.last-epoch"
  mkdir -p "$(dirname "$state_f")" 2>/dev/null || true
  echo "$epoch" >"$state_f"
}

# Resolve which job conf owns a libvirt VM name (for agent-payload FLR watch).
mold_backup_conf_for_vm() {
  local vm="$1" f
  [[ -n "$vm" ]] || return 1
  local etc="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
  for f in "${etc}"/"$(hostname -s)".conf "${etc}"/*.conf; do
    [[ -f "$f" ]] || continue
    [[ "$(basename "$f")" == mold-backup.conf ]] && continue
    if grep -Eq "^[[:space:]]*VM_INCLUDE=.*${vm}" "$f" 2>/dev/null; then
      echo "$f"
      return 0
    fi
  done
  return 1
}

# Fingerprint agent payload dir (mtime+size of files, excluding publish stamp).
mold_backup_agent_payload_fingerprint() {
  local d="$1"
  [[ -d "$d" ]] || { echo ""; return 0; }
  find "$d" -type f ! -name '.mold-agent-publish' -printf '%f:%T@:%s\n' 2>/dev/null | sort | md5sum | awk '{print $1}'
}

# Immediate host FLR: Veeam UI restore rewrites /tmp/mold/veeam-agent/<vm>/current.
# Skips our own backup publish (.mold-agent-publish) and active backup/restore markers.
# RBD native marker-only trees are NEVER FLR — pre-notify publishes them after every backup.
mold_backup_agent_payload_is_rbd_native_only() {
  local publish="$1"
  local f
  [[ -d "$publish" && -f "${publish}/mold-rbd-native.marker" ]] || return 1
  while IFS= read -r f; do
    case "$(basename "$f")" in
      .mold-agent-publish|mold-rbd-native.marker|rbd-backup.meta|domain-config.xml|domain.xml|veeam-seed.meta|staging.complete)
        ;;
      *)
        return 1
        ;;
    esac
  done < <(find "$publish" -type f 2>/dev/null)
  return 0
}

mold_backup_try_agent_payload_flr_trigger() {
  local vm="$1"
  local base="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
  local publish="${base}/${vm}/current"
  local quiet_sec="${FLR_WATCH_QUIET_SEC:-2}"
  local publish_grace="${FLR_WATCH_PUBLISH_GRACE_SEC:-600}"
  local state_dir fp_file last_fp fp now pub_ts conf job sid epoch detail backup_id
  [[ -n "$vm" && -d "$publish" ]] || return 0

  if mold_backup_trigger_active "veeam-active" "$vm" 2>/dev/null; then
    return 0
  fi
  if mold_backup_trigger_active "mold-restore-active" "$vm" 2>/dev/null; then
    return 0
  fi
  if mold_backup_trigger_active "mold-flr-pushed" "$vm" 60 2>/dev/null; then
    return 0
  fi

  now=$(date +%s)
  if [[ -f "${publish}/.mold-agent-publish" ]]; then
    pub_ts="$(tr -d '[:space:]' <"${publish}/.mold-agent-publish" 2>/dev/null || echo 0)"
    if [[ "$pub_ts" =~ ^[0-9]+$ ]] && (( now - pub_ts < publish_grace )); then
      return 0
    fi
  fi

  # Debounce: fingerprint must be stable for quiet_sec (FLR may write several files).
  state_dir="$(mold_backup_state_dir)/flr-watch"
  mkdir -p "$state_dir" 2>/dev/null || true
  fp_file="${state_dir}/${vm}.fp"
  fp="$(mold_backup_agent_payload_fingerprint "$publish")"
  [[ -n "$fp" ]] || return 0

  # Backup publish of RBD marker must not look like Veeam Guest FLR.
  if mold_backup_agent_payload_is_rbd_native_only "$publish"; then
    echo "$fp" >"$fp_file"
    rm -f "${state_dir}/${vm}.pending" 2>/dev/null || true
    return 0
  fi

  last_fp=""
  [[ -f "$fp_file" ]] && last_fp="$(tr -d '[:space:]' <"$fp_file" 2>/dev/null || true)"
  if [[ "$fp" == "$last_fp" ]]; then
    return 0
  fi
  # First observation of change — record pending and wait for quiet.
  local pending="${state_dir}/${vm}.pending"
  local pending_fp pending_ts
  if [[ -f "$pending" ]]; then
    pending_fp="$(awk 'NR==1{print; exit}' "$pending" 2>/dev/null || true)"
    pending_ts="$(awk 'NR==2{print; exit}' "$pending" 2>/dev/null || true)"
  else
    pending_fp=""
    pending_ts=""
  fi
  if [[ "$fp" != "$pending_fp" ]]; then
    printf '%s\n%s\n' "$fp" "$now" >"$pending"
    return 0
  fi
  [[ "$pending_ts" =~ ^[0-9]+$ ]] || return 0
  if (( now - pending_ts < quiet_sec )); then
    return 0
  fi

  # Stable change after quiet window → treat as Veeam FLR onto agent payload.
  conf="$(mold_backup_conf_for_vm "$vm" 2>/dev/null || true)"
  if [[ -z "$conf" ]]; then
    mold_backup_notify_log warn "flr-watch: no conf for vm=${vm}; skip"
    echo "$fp" >"$fp_file"
    rm -f "$pending"
    return 0
  fi
  # Load VM's job conf (API keys / job name) without clobbering global forever.
  (
    # shellcheck disable=SC1090
    set -a
    # shellcheck source=/dev/null
    source "$conf"
    set +a
    export MOLD_BACKUP_CONF="$conf"
    mold_backup_load_config 2>/dev/null || true
    mold_backup_resolve_api_secret 2>/dev/null || true
    job="${VEEAM_JOB_NAME:-$(basename "$conf" .conf)}"
    if ! mold_backup_vm_owned_by_local_host "$vm" 2>/dev/null; then
      mold_backup_notify_log info "flr-watch: vm=${vm} not owned by $(mold_backup_local_kvm_name); skip"
      exit 0
    fi
    epoch="$now"
    sid="local-flr-${vm}-${epoch}"
    if mold_backup_restore_session_seen "$sid" 2>/dev/null; then
      exit 0
    fi
    # Resolve the *selected* restore point — never the registry latest.
    # 1) Agent payload checkpoint (FLR rewrote /tmp/mold/veeam-agent/<vm>/current) — fast
    # 2) Veeam restore session RP (UI selection) — may SSH; bounded timeout
    # Same checkpoint as previous is still valid: user often FLR-restores the latest RP.
    ckpt=""
    rp_id=""
    rp_info=""
    prev_ckpt=""
    payload_ckpt=""
    ckpt_file="$(mold_backup_state_dir)/flr-watch/${vm}.ckpt"
    [[ -f "$ckpt_file" ]] && prev_ckpt="$(tr -d '[:space:]' <"$ckpt_file" 2>/dev/null || true)"
    payload_ckpt="$(mold_backup_agent_payload_selected_checkpoint "$vm" 2>/dev/null || true)"
    if [[ -n "$payload_ckpt" ]]; then
      ckpt="$payload_ckpt"
      if [[ "$payload_ckpt" == "$prev_ckpt" ]]; then
        mold_backup_notify_log info "flr-watch: vm=${vm} payload ckpt unchanged (${payload_ckpt}) — still restore selected point after FLR rewrite"
      fi
    fi
    [[ -n "$payload_ckpt" ]] && echo "$payload_ckpt" >"$ckpt_file"
    # RP id is optional when checkpoint is known (index only). Required when ckpt missing.
    if [[ -z "$ckpt" ]]; then
      rp_info="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-25}" \
        mold_backup_query_veeam_selected_flr_rp "$job" "$vm" "${VEEAM_RESTORE_WATCH_WINDOW_MIN:-30}" 2>/dev/null || true)"
    elif [[ "${FLR_WATCH_INDEX_RP:-true}" == "true" ]]; then
      rp_info="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-12}" \
        mold_backup_query_veeam_selected_flr_rp "$job" "$vm" "${VEEAM_RESTORE_WATCH_WINDOW_MIN:-30}" 2>/dev/null || true)"
    fi
    if [[ -n "$rp_info" ]]; then
      rp_id="${rp_info%%|*}"
      _sid="$(cut -d'|' -f3 <<<"$rp_info")"
      [[ -n "$_sid" ]] && sid="$_sid"
    fi
    if [[ -z "$ckpt" && -z "$rp_id" ]]; then
      mold_backup_notify_log err "flr-watch: vm=${vm} FLR detected but no selected checkpoint/RP (payload ckpt=${payload_ckpt:-n/a} prev=${prev_ckpt:-n/a}) — refuse latest fallback"
      exit 1
    fi
    detail="name=agent-payload-flr;end=${epoch};result=Success;backup=${job};source=flr-watch;ckpt=${ckpt:-};rp=${rp_id:-}"
    mold_backup_notify_log info "flr-watch: IMMEDIATE Mold restore vm=${vm} conf=$(basename "$conf") ckpt=${ckpt:-n/a} rp=${rp_id:-n/a}"
    mold_backup_handle_veeam_restore_session "$job" "$vm" "$sid" "$detail" true "${rp_id:-}" \
      && mold_backup_local_flr_mark_epoch "$vm" "$epoch"
  )
  echo "$fp" >"$fp_file"
  rm -f "$pending"
  return 0
}

# One scan of all VM dirs under VEEAM_AGENT_PAYLOAD_PATH.
mold_backup_scan_agent_payload_flr() {
  local base="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
  local vm_dir vm
  [[ -d "$base" ]] || mkdir -p "$base" 2>/dev/null || true
  [[ -d "$base" ]] || return 0
  for vm_dir in "$base"/*; do
    [[ -d "$vm_dir" ]] || continue
    vm="$(basename "$vm_dir")"
    [[ "$vm" == i-*-VM ]] || continue
    mold_backup_try_agent_payload_flr_trigger "$vm" || true
  done
}

# Pick Veeam restore point GUID whose CreationTime is closest to a Unix epoch (FLR time).
mold_backup_query_veeam_rp_near_epoch() {
  local job="$1" epoch="$2"
  [[ -n "${VEEAM_SSH_HOST:-}" && -n "$job" && "$epoch" =~ ^[0-9]+$ ]] || return 1
  local job_esc epoch_ps ps_script ps_enc out
  job_esc="${job//\'/\'\'}"
  epoch_ps="${epoch}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'SilentlyContinue'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
\$target = [int64]${epoch_ps}
\$JobName = '${job_esc}'
\$best = \$null
\$bestDelta = [int64]::MaxValue
\$backups = @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object {
  \$_.JobName -eq \$JobName -or \$_.Name -like "*\$JobName*"
})
foreach (\$b in \$backups) {
  \$rps = @(\$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue)
  foreach (\$rp in \$rps) {
    if (\$null -eq \$rp) { continue }
    \$ct = \$rp.CreationTime
    if (\$null -eq \$ct) { continue }
    \$ep = [int64]([DateTimeOffset]\$ct).ToUnixTimeSeconds()
    \$delta = [math]::Abs(\$ep - \$target)
    if (\$delta -lt \$bestDelta) { \$bestDelta = \$delta; \$best = \$rp }
  }
}
if (-not \$best) { exit 1 }
\$id = \$best.Id
if (\$id -is [guid]) { Write-Output \$id.Guid } else { Write-Output ([string]\$id) }
PS
)"
  ps_enc="$(printf '%s' "$ps_script" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null | base64 -w0 2>/dev/null)"
  [[ -n "$ps_enc" ]] || return 1
  local ssh_key_opt=()
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_key_opt=(-i "${VEEAM_SSH_KEY}")
  out="$(ssh "${ssh_key_opt[@]}" -o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=no \
        "${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}" \
        "pwsh -NoProfile -EncodedCommand ${ps_enc}" 2>/dev/null | tr -d '\r' | head -1)"
  [[ -n "$out" ]] && echo "$out"
}

# Reflect a single Veeam restore session into Mold state for one VM.
mold_backup_reflect_one_restore() {
  local job="$1" vm="$2" sid="$3" detail="$4"
  # Loop guard: Mold itself initiated this restore -> Veeam restore is part of that flow.
  if mold_backup_trigger_active "mold-restore-active" "$vm"; then
    mold_backup_trigger_clear "mold-restore-active" "$vm"
    mold_backup_notify_log info "mold-restore-active for ${vm}: restore initiated by Mold; skip reflect (session=${sid})"
    mold_backup_restore_session_mark_seen "$sid"
    mold_backup_emit_restore_event "mold.restore.skipped.mold-active" "$vm" "session=${sid}"
    return 0
  fi
  mold_backup_registry_save_restore "$job" "$vm" "veeam" "$sid" "$detail" "veeam-restored"
  mold_backup_restore_session_mark_seen "$sid"
  mold_backup_emit_restore_event "veeam.restore.reflected" "$vm" "session=${sid};${detail}"
  mold_backup_notify_log info "Veeam→Mold: reflected restore for ${vm} (session=${sid})"
}

# --- Restore agent: host ownership, cluster lock, Mold API trigger ---

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

mold_backup_api_get_vm_host_name() {
  local vm_name="$1" json host
  json="$(mold_backup_api_get_vm_record "$vm_name" "${ZONE_ID:-}")" || return 1
  host="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.hostname")"
  [[ -n "$host" ]] || return 1
  echo "$host"
}

mold_backup_vm_owned_by_local_host() {
  local vm_name="$1" vm_host local_host
  vm_host="$(mold_backup_api_get_vm_host_name "$vm_name" 2>/dev/null || true)"
  local_host="$(mold_backup_local_kvm_name)"
  if [[ -z "$vm_host" ]]; then
    mold_backup_notify_log warn "restore-agent: no Mold hostname for ${vm_name}; allow local=${local_host}"
    return 0
  fi
  [[ "$vm_host" == "$local_host" ]]
}

mold_backup_restore_lock_dir() {
  local d="${RESTORE_LOCK_DIR:-}"
  if [[ -z "$d" && -n "${BACKUP_REPO_ADDRESS:-}" ]]; then
    d="${BACKUP_REPO_ADDRESS%/}/.mold/restore-locks"
  fi
  if [[ -z "$d" ]]; then
    d="$(mold_backup_state_dir)/restore-locks"
  fi
  mkdir -p "$d" 2>/dev/null || true
  echo "$d"
}

mold_backup_restore_lock_acquire() {
  local vm="$1" lock_file
  lock_file="$(mold_backup_restore_lock_dir)/$(mold_backup_safe_job_name "$vm").lock"
  exec {MOLD_RESTORE_LOCK_FD}>"$lock_file" || return 1
  if ! flock -n "$MOLD_RESTORE_LOCK_FD"; then
    exec {MOLD_RESTORE_LOCK_FD}>&-
    unset MOLD_RESTORE_LOCK_FD
    return 1
  fi
  return 0
}

mold_backup_restore_lock_release() {
  [[ -n "${MOLD_RESTORE_LOCK_FD:-}" ]] || return 0
  flock -u "$MOLD_RESTORE_LOCK_FD" 2>/dev/null || true
  exec {MOLD_RESTORE_LOCK_FD}>&-
  unset MOLD_RESTORE_LOCK_FD
}

mold_backup_events_log_file() {
  local d="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}/events"
  mkdir -p "$d" 2>/dev/null || true
  echo "$d/restore.log"
}

mold_backup_emit_restore_event() {
  local event="$1" vm="$2" detail="${3:-}" host
  host="$(mold_backup_local_kvm_name)"
  echo "$(date -Iseconds) event=${event} host=${host} vm=${vm} ${detail}" >> "$(mold_backup_events_log_file)"
  mold_backup_notify_log info "restore-event ${event} vm=${vm} ${detail}"
}

mold_backup_api_find_backup_by_veeam_rp() {
  local vm_name="$1" rp_id="$2"
  local vm_id json norm_rp bid detail_rp
  [[ -n "$vm_name" && -n "$rp_id" ]] || return 1
  norm_rp="$(mold_backup_normalize_rp_id "$rp_id")"
  [[ -n "$norm_rp" ]] || return 1
  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || return 1
  json="$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
  [[ -n "$json" ]] || return 1
  while IFS= read -r bid; do
    [[ -n "$bid" ]] || continue
    detail_rp="$(mold_backup_api_backup_detail_field "$bid" "ablestack.veeam.restore.point.id" 2>/dev/null || true)"
    [[ -n "$detail_rp" ]] || continue
    if [[ "$(mold_backup_normalize_rp_id "$detail_rp")" == "$norm_rp" ]]; then
      echo "$bid"
      return 0
    fi
  done < <(printf '%s\n' "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict):
        b = [b]
    for x in b:
        if str(x.get('status', '')).lower() == 'backedup' and x.get('id'):
            print(x['id'])
except Exception:
    pass
" 2>/dev/null)
  return 1
}

# Match Mold backup by checkpoint name (detail or external path basename).
mold_backup_api_find_backup_by_checkpoint() {
  local vm_name="$1" ckpt="$2"
  local vm_id json bid
  [[ -n "$vm_name" && -n "$ckpt" ]] || return 1
  # Reject job names accidentally passed as checkpoint
  [[ "$ckpt" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2} ]] || return 1
  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || return 1
  json="$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
  [[ -n "$json" ]] || return 1
  bid="$(printf '%s\n' "$json" | CKPT="$ckpt" python3 -c "
import json, sys, os
ckpt = os.environ.get('CKPT', '')
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict):
        b = [b]
    for x in b:
        if str(x.get('status', '')).lower() != 'backedup':
            continue
        ext = str(x.get('externalid') or x.get('path') or '')
        base = ext.rstrip('/').split('/')[-1] if ext else ''
        if base == ckpt or ext.endswith('/' + ckpt) or ext.endswith(ckpt):
            print(x.get('id') or '')
            break
except Exception:
    pass
" 2>/dev/null)"
  if [[ -n "$bid" ]]; then
    echo "$bid"
    return 0
  fi
  while IFS= read -r bid; do
    [[ -n "$bid" ]] || continue
    local detail_ckpt
    detail_ckpt="$(mold_backup_api_backup_detail_field "$bid" "ablestack.veeam.checkpoint.name" 2>/dev/null || true)"
    if [[ "$detail_ckpt" == "$ckpt" ]]; then
      echo "$bid"
      return 0
    fi
  done < <(printf '%s\n' "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict):
        b = [b]
    for x in b:
        if str(x.get('status', '')).lower() == 'backedup' and x.get('id'):
            print(x['id'])
except Exception:
    pass
" 2>/dev/null)
  return 1
}

# Nearest Mold backup whose checkpoint timestamp is within max_delta of epoch.
mold_backup_api_find_backup_near_epoch() {
  local vm_name="$1" epoch="$2" max_delta="${3:-${RESTORE_RP_MATCH_MAX_DELTA_SEC:-10800}}"
  local vm_id json bid
  [[ -n "$vm_name" && "$epoch" =~ ^[0-9]+$ ]] || return 1
  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || return 1
  json="$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
  [[ -n "$json" ]] || return 1
  bid="$(printf '%s\n' "$json" | EPOCH="$epoch" MAX_DELTA="$max_delta" python3 -c "
import json, sys, os, re
from datetime import datetime
epoch = int(os.environ.get('EPOCH', '0'))
max_delta = int(os.environ.get('MAX_DELTA', '600'))
ckpt_re = re.compile(r'(20\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})(?:\\.(\\d+))?')

def ckpt_epoch(s):
    m = ckpt_re.search(s or '')
    if not m:
        return None
    try:
        return int(datetime(
            int(m.group(1)), int(m.group(2)), int(m.group(3)),
            int(m.group(4)), int(m.group(5)), int(m.group(6))
        ).timestamp())
    except Exception:
        return None

try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict):
        b = [b]
    best_id = ''
    best_delta = None
    for x in b:
        if str(x.get('status', '')).lower() != 'backedup':
            continue
        ext = str(x.get('externalid') or x.get('path') or '')
        ce = ckpt_epoch(ext)
        if ce is None:
            continue
        delta = abs(ce - epoch)
        if delta > max_delta:
            continue
        if best_delta is None or delta < best_delta:
            best_delta = delta
            best_id = x.get('id') or ''
    if best_id:
        print(best_id)
except Exception:
    pass
" 2>/dev/null)"
  [[ -n "$bid" ]] && echo "$bid"
}

# Query Veeam for CreationTime (unix epoch) of a restore point GUID.
# Prefer Get-VBRBackup|Get-VBRRestorePoint (Agent/computer jobs); global Get-VBRRestorePoint often misses them.
mold_backup_query_veeam_rp_creation_epoch() {
  local rp_id="$1" job_hint="${2:-${VEEAM_JOB_NAME:-}}"
  local rp_esc job_esc ps_script out
  [[ -n "${VEEAM_SSH_HOST:-}" && -n "$rp_id" ]] || return 1
  rp_esc="${rp_id//\'/\'\'}"
  job_esc="${job_hint//\'/\'\'}"
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
function Rp-Epoch(\$rp) {
  \$ct = \$rp.CreationTimeUTC
  if (\$null -eq \$ct) { \$ct = \$rp.CreationTime }
  if (\$null -eq \$ct) { return \$null }
  if (\$ct.Kind -eq [DateTimeKind]::Unspecified) {
    \$ct = [DateTime]::SpecifyKind(\$ct, [DateTimeKind]::Utc)
  }
  return [int64]([DateTimeOffset]\$ct).ToUnixTimeSeconds()
}
\$rp = \$null
\$backups = @()
if (\$JobHint) {
  \$cj = Get-VBRComputerBackupJob -Name \$JobHint -ErrorAction SilentlyContinue
  if (\$cj) {
    \$backups += @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object { \$_.JobId -eq \$cj.Id })
  }
  \$backups += @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object {
    \$_.JobName -eq \$JobHint -or \$_.Name -like "*\$JobHint*"
  })
}
if (-not \$backups -or \$backups.Count -eq 0) {
  \$backups = @(Get-VBRBackup -ErrorAction SilentlyContinue)
}
foreach (\$b in \$backups) {
  foreach (\$cand in @(\$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue)) {
    if (Rp-IdMatch \$cand) { \$rp = \$cand; break }
  }
  if (\$rp) { break }
}
if (-not \$rp) {
  \$rp = Get-VBRRestorePoint -ErrorAction SilentlyContinue | Where-Object { Rp-IdMatch \$_ } | Select-Object -First 1
}
if (-not \$rp) { exit 1 }
\$ep = Rp-Epoch \$rp
if (\$null -eq \$ep) { exit 1 }
Write-Output \$ep
PS
)"
  out="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-25}" \
    mold_backup_veeam_ssh_ps_capture "$ps_script" 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+$' | tail -1)"
  [[ -n "$out" ]] && echo "$out"
}

# Local registry: map unix epoch → nearest ${vm}.ckpt-*.backup-id (fast, no Mold API).
mold_backup_registry_find_backup_near_epoch() {
  local vm_name="$1" epoch="$2" max_delta="${3:-${RESTORE_RP_MATCH_MAX_DELTA_SEC:-10800}}"
  local reg_dir f base ckpt ce delta best_id="" best_delta=""
  [[ -n "$vm_name" && "$epoch" =~ ^[0-9]+$ ]] || return 1
  reg_dir="$(mold_backup_registry_dir)"
  shopt -s nullglob
  for f in "${reg_dir}/${vm_name}.ckpt-"*.backup-id; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    ckpt="${base#${vm_name}.ckpt-}"
    ckpt="${ckpt%.backup-id}"
    [[ "$ckpt" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2} ]] || continue
    ce="$(python3 -c "
from datetime import datetime
import sys
p=sys.argv[1].split('.')
try:
  print(int(datetime(int(p[0]),int(p[1]),int(p[2]),int(p[3]),int(p[4]),int(p[5])).timestamp()))
except Exception:
  print('')
" "$ckpt" 2>/dev/null || true)"
    [[ "$ce" =~ ^[0-9]+$ ]] || continue
    delta=$(( ce > epoch ? ce - epoch : epoch - ce ))
    if [[ "$delta" -le "$max_delta" ]]; then
      if [[ -z "$best_delta" || "$delta" -lt "$best_delta" ]]; then
        best_delta="$delta"
        best_id="$(tr -d '[:space:]' <"$f" 2>/dev/null || true)"
      fi
    fi
  done
  shopt -u nullglob
  [[ -n "$best_id" ]] && echo "$best_id"
}

# Recent Veeam FLR/restore session for this job → selected RP id (and optional creation epoch).
# Prints: rp_id|creation_epoch|session_id
mold_backup_query_veeam_selected_flr_rp() {
  local job="$1" vm_name="${2:-}" since_min="${3:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-30}}"
  local line sid sip et result nm bn rp_id epoch_rp
  [[ -n "$job" ]] || return 1
  while IFS='|' read -r sid sip et result nm bn rp_id epoch_rp; do
    [[ -n "$sid" ]] || continue
    # Prefer sessions that look like FLR and match job / host / vm name when possible.
    if [[ -n "$vm_name" && "$nm" != *"$vm_name"* && "$bn" != *"$job"* && "$nm" != FLR_* && "$nm" != *"$job"* ]]; then
      # still accept if RP present and job matches backup name loosely
      [[ "$bn" == *"$job"* || "$nm" == *"$job"* ]] || continue
    fi
    if [[ -z "$rp_id" || "$rp_id" == "n/a" ]]; then
      continue
    fi
    if [[ ! "$epoch_rp" =~ ^[0-9]+$ ]]; then
      epoch_rp="$(mold_backup_query_veeam_rp_creation_epoch "$rp_id" "$job" 2>/dev/null || true)"
    fi
    echo "${rp_id}|${epoch_rp:-}|${sid}"
    return 0
  done < <(mold_backup_query_veeam_restores "$since_min" 2>/dev/null || true)
  return 1
}

# Read selected checkpoint from Veeam Agent payload after FLR rewrite.
mold_backup_agent_payload_selected_checkpoint() {
  local vm="$1"
  local base="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
  local publish="${base}/${vm}/current" ckpt=""
  [[ -n "$vm" && -d "$publish" ]] || return 1
  if [[ -f "${publish}/rbd-backup.meta" ]]; then
    ckpt="$(mold_backup_meta_field "${publish}/rbd-backup.meta" checkpoint_name 2>/dev/null || true)"
  fi
  if [[ -z "$ckpt" && -f "${publish}/veeam-seed.meta" ]]; then
    ckpt="$(mold_backup_meta_field "${publish}/veeam-seed.meta" checkpoint_name 2>/dev/null || true)"
  fi
  if [[ -z "$ckpt" && -f "${publish}/mold-rbd-native.marker" ]]; then
    ckpt="$(sed -n 's/.*checkpoint[^=]*=\([0-9.]\{10,\}\).*/\1/p' "${publish}/mold-rbd-native.marker" 2>/dev/null | head -1 || true)"
    [[ -z "$ckpt" ]] && ckpt="$(grep -Eo '20[0-9]{2}(\.[0-9]{2}){5}(\.[0-9]+)?' "${publish}/mold-rbd-native.marker" 2>/dev/null | head -1 || true)"
  fi
  [[ -n "$ckpt" && "$ckpt" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\. ]] || return 1
  echo "$ckpt"
}

mold_backup_resolve_backup_id_for_vm() {
  local vm_name="$1" job="${2:-${VEEAM_JOB_NAME:-}}" rp_id="${3:-}" ckpt="${4:-}" rp_epoch="${5:-}"
  local backup_id vm_id json selected=false
  # Job name must never be treated as a checkpoint.
  if [[ -n "$ckpt" && ! "$ckpt" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]{2}\. ]]; then
    mold_backup_notify_log warn "restore-watch: ignoring non-checkpoint token as ckpt='${ckpt}'"
    ckpt=""
  fi
  [[ -n "$ckpt" || -n "$rp_id" ]] && selected=true

  if [[ -n "$ckpt" ]]; then
    backup_id="$(mold_backup_registry_get_backup_id_by_checkpoint "$vm_name" "$ckpt" 2>/dev/null || true)"
    if [[ -n "$backup_id" ]]; then
      mold_backup_notify_log info "restore-watch: vm=${vm_name} ckpt=${ckpt} → backup_id=${backup_id} (registry checkpoint)"
      echo "$backup_id"
      return 0
    fi
    backup_id="$(mold_backup_api_find_backup_by_checkpoint "$vm_name" "$ckpt" 2>/dev/null || true)"
    if [[ -n "$backup_id" ]]; then
      mold_backup_notify_log info "restore-watch: vm=${vm_name} ckpt=${ckpt} → backup_id=${backup_id} (Mold API checkpoint)"
      mold_backup_registry_index_checkpoint_backup "$vm_name" "$ckpt" "$backup_id"
      echo "$backup_id"
      return 0
    fi
    mold_backup_notify_log warn "restore-watch: no Mold backup for checkpoint ${ckpt} vm=${vm_name}"
  fi

  if [[ -n "$rp_id" ]]; then
    backup_id="$(mold_backup_registry_get_backup_id_by_rp "$vm_name" "$rp_id" 2>/dev/null || true)"
    if [[ -n "$backup_id" ]]; then
      mold_backup_notify_log info "restore-watch: vm=${vm_name} rp=${rp_id} → backup_id=${backup_id} (registry)"
      echo "$backup_id"
      return 0
    fi
    backup_id="$(mold_backup_api_find_backup_by_veeam_rp "$vm_name" "$rp_id" 2>/dev/null || true)"
    if [[ -n "$backup_id" ]]; then
      mold_backup_notify_log info "restore-watch: vm=${vm_name} rp=${rp_id} → backup_id=${backup_id} (Mold API)"
      mold_backup_registry_index_rp_backup "$vm_name" "$rp_id" "$backup_id" "$job"
      echo "$backup_id"
      return 0
    fi
    # Map RP → Mold backup by RP CreationTime ≈ checkpoint timestamp.
    # Prefer epoch already extracted from the FLR session (avoids extra SSH).
    if [[ "${RESTORE_RP_TIME_MATCH:-true}" == "true" ]]; then
      if [[ ! "$rp_epoch" =~ ^[0-9]+$ ]]; then
        rp_epoch="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-25}" \
          mold_backup_query_veeam_rp_creation_epoch "$rp_id" "$job" 2>/dev/null || true)"
      fi
      if [[ -n "$rp_epoch" ]]; then
        local _ep_cand _tz
        # Try reported epoch, then common TZ mis-interpretations (±8h/±9h).
        for _tz in 0 32400 -32400 28800 -28800; do
          _ep_cand=$((rp_epoch + _tz))
          [[ "$_ep_cand" =~ ^[0-9]+$ ]] || continue
          backup_id="$(mold_backup_registry_find_backup_near_epoch "$vm_name" "$_ep_cand" 2>/dev/null || true)"
          if [[ -z "$backup_id" ]]; then
            backup_id="$(mold_backup_api_find_backup_near_epoch "$vm_name" "$_ep_cand" 2>/dev/null || true)"
          fi
          if [[ -n "$backup_id" ]]; then
            mold_backup_notify_log info "restore-watch: vm=${vm_name} rp=${rp_id} epoch=${rp_epoch} (tz_adj=${_tz}) → backup_id=${backup_id} (nearest checkpoint)"
            mold_backup_registry_index_rp_backup "$vm_name" "$rp_id" "$backup_id" "$job"
            echo "$backup_id"
            return 0
          fi
        done
        mold_backup_notify_log warn "restore-watch: rp=${rp_id} epoch=${rp_epoch} but no Mold checkpoint within ${RESTORE_RP_MATCH_MAX_DELTA_SEC:-10800}s for ${vm_name}"
      else
        mold_backup_notify_log warn "restore-watch: could not resolve CreationTime for rp=${rp_id} (job=${job})"
      fi
    fi
    # Last resort for UI FLR: agent payload still carries the selected Mold checkpoint.
    local payload_ckpt=""
    payload_ckpt="$(mold_backup_agent_payload_selected_checkpoint "$vm_name" 2>/dev/null || true)"
    if [[ -n "$payload_ckpt" ]]; then
      backup_id="$(mold_backup_registry_get_backup_id_by_checkpoint "$vm_name" "$payload_ckpt" 2>/dev/null || true)"
      if [[ -z "$backup_id" ]]; then
        backup_id="$(mold_backup_api_find_backup_by_checkpoint "$vm_name" "$payload_ckpt" 2>/dev/null || true)"
      fi
      if [[ -n "$backup_id" ]]; then
        mold_backup_notify_log info "restore-watch: vm=${vm_name} rp=${rp_id} → backup_id=${backup_id} (agent-payload ckpt=${payload_ckpt})"
        mold_backup_registry_index_rp_backup "$vm_name" "$rp_id" "$backup_id" "$job"
        mold_backup_registry_index_checkpoint_backup "$vm_name" "$payload_ckpt" "$backup_id"
        echo "$backup_id"
        return 0
      fi
    fi
    mold_backup_notify_log warn "restore-watch: no Mold backup for Veeam restore point ${rp_id} vm=${vm_name}"
  fi

  if [[ "$selected" == "true" && "${RESTORE_ALLOW_LATEST_FALLBACK:-false}" != "true" ]]; then
    mold_backup_notify_log err "restore-watch: refusing latest fallback for selected rp/ckpt vm=${vm_name} rp=${rp_id:-n/a} ckpt=${ckpt:-n/a}"
    return 1
  fi

  if [[ -n "${BACKUP_ID:-}" ]]; then
    echo "$BACKUP_ID"
    return 0
  fi
  backup_id="$(mold_backup_registry_get_vm_backup_id "$vm_name" 2>/dev/null || true)"
  if [[ -n "$backup_id" ]]; then
    if [[ "$selected" == "true" ]]; then
      mold_backup_notify_log warn "restore-watch: using latest backup_id=${backup_id} (RESTORE_ALLOW_LATEST_FALLBACK=true)"
    else
      mold_backup_notify_log warn "restore-watch: no selected rp/ckpt; using latest backup_id=${backup_id} vm=${vm_name}"
    fi
    echo "$backup_id"
    return 0
  fi
  local line reg_dir
  reg_dir="$(mold_backup_registry_dir)"
  if [[ -d "$reg_dir" ]]; then
    line="$(grep -h "vm=${vm_name}.*backup_id=" "${reg_dir}"/*.log 2>/dev/null | tail -1 || true)"
    backup_id="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
    [[ -n "$backup_id" ]] && { echo "$backup_id"; return 0; }
  fi
  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || return 1
  json="$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
  backup_id="$(python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict): b = [b]
    backed = [x for x in b if str(x.get('status','')).lower() == 'backedup']
    backed.sort(key=lambda x: x.get('date',''), reverse=True)
    print(backed[0]['id'] if backed else '')
except Exception:
    print('')
" <<<"$json" 2>/dev/null)"
  [[ -n "$backup_id" ]] && echo "$backup_id"
}

# Handle one Veeam restore session: dedup, host check, flock, optional Mold restore API.
mold_backup_handle_veeam_restore_session() {
  local job="$1" vm="$2" sid="$3" detail="$4" trigger_mold="${5:-false}" rp_id="${6:-}"
  local ckpt=""
  # Prefer explicit ckpt=/checkpoint= — never treat backup=<job name> as checkpoint.
  ckpt="$(sed -n 's/.*ckpt=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
  [[ -z "$ckpt" ]] && ckpt="$(sed -n 's/.*checkpoint=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
  ckpt="${ckpt// /}"
  [[ "$ckpt" == "n/a" ]] && ckpt=""
  if [[ -z "$rp_id" && "$detail" == *"rp="* ]]; then
    rp_id="$(sed -n 's/.*rp=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
    rp_id="${rp_id// /}"
    [[ "$rp_id" == "n/a" ]] && rp_id=""
  fi
  local rp_epoch=""
  if [[ "$detail" == *"rp_epoch="* ]]; then
    rp_epoch="$(sed -n 's/.*rp_epoch=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
    rp_epoch="${rp_epoch// /}"
    [[ "$rp_epoch" =~ ^[0-9]+$ ]] || rp_epoch=""
  fi
  # Do not invent an RP from FLR end-time when a checkpoint was already selected —
  # near-epoch matching tends to pick the newest RP, not the UI selection.
  if [[ -z "$rp_id" && -z "$ckpt" && "$detail" == *"end="* ]]; then
    local _ep
    _ep="$(sed -n 's/.*end=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
    if [[ "$_ep" =~ ^[0-9]+$ ]]; then
      rp_id="$(VEEAM_SSH_CMD_TIMEOUT="${RESTORE_VEEAM_SSH_TIMEOUT:-20}" \
        mold_backup_query_veeam_rp_near_epoch "$job" "$_ep" 2>/dev/null || true)"
    fi
  fi
  if mold_backup_restore_session_seen "$sid"; then
    mold_backup_emit_restore_event "mold.restore.skipped.duplicate" "$vm" "session=${sid}"
    return 0
  fi
  if mold_backup_trigger_active "mold-restore-active" "$vm"; then
    mold_backup_trigger_clear "mold-restore-active" "$vm"
    mold_backup_restore_session_mark_seen "$sid"
    mold_backup_emit_restore_event "mold.restore.skipped.mold-active" "$vm" "session=${sid}"
    return 0
  fi
  if [[ "$trigger_mold" != "true" ]]; then
    mold_backup_reflect_one_restore "$job" "$vm" "$sid" "$detail"
    return 0
  fi
  if ! mold_backup_vm_owned_by_local_host "$vm"; then
    local owner
    owner="$(mold_backup_api_get_vm_host_name "$vm" 2>/dev/null || echo unknown)"
    mold_backup_emit_restore_event "mold.restore.skipped.not-owner" "$vm" \
      "session=${sid};owner=${owner};local=$(mold_backup_local_kvm_name)"
    mold_backup_restore_session_mark_seen "$sid"
    return 0
  fi
  if ! mold_backup_restore_lock_acquire "$vm"; then
    mold_backup_emit_restore_event "mold.restore.skipped.locked" "$vm" "session=${sid}"
    return 0
  fi
  local backup_id rc=0
  backup_id="$(mold_backup_resolve_backup_id_for_vm "$vm" "$job" "$rp_id" "$ckpt" "$rp_epoch" 2>/dev/null || true)"
  if [[ -z "$backup_id" ]]; then
    mold_backup_emit_restore_event "mold.restore.failed" "$vm" "session=${sid};reason=no-backup-id;rp=${rp_id:-n/a};ckpt=${ckpt:-n/a};rp_epoch=${rp_epoch:-n/a}"
    mold_backup_restore_lock_release
    return 1
  fi
  export BACKUP_ID="$backup_id" VM_NAME="$vm"
  [[ -n "$rp_id" ]] && export VEEAM_RESTORE_POINT_ID="$rp_id"
  export RESTORE_SOURCE="${RESTORE_SOURCE:-${VEEAM_UI_RESTORE_SOURCE:-mold-only}}"
  mold_backup_notify_log info "Veeam UI restore session=${sid} vm=${vm} rp=${rp_id:-n/a} ckpt=${ckpt:-n/a} → Mold datadisk restore backup_id=${backup_id} (RESTORE_SOURCE=${RESTORE_SOURCE})"
  mold_backup_emit_restore_event "veeam.restore.completed" "$vm" "session=${sid};rp=${rp_id:-n/a};ckpt=${ckpt:-n/a};backup_id=${backup_id};${detail}"
  mold_backup_emit_restore_event "mold.restore.requested" "$vm" "session=${sid};rp=${rp_id:-n/a};ckpt=${ckpt:-n/a};backup_id=${backup_id};source=${RESTORE_SOURCE}"
  mold_backup_trigger_mark "mold-restore-active" "$vm"
  mold_backup_trigger_mark "veeam-restore-active" "$vm"
  if mold_backup_restore_notify "$(hostname -s)" "$job"; then
    mold_backup_registry_save_restore "$job" "$vm" "veeam" "$sid" "${detail};backup_id=${backup_id}" "mold-restored"
    mold_backup_restore_session_mark_seen "$sid"
    mold_backup_emit_restore_event "mold.restore.completed" "$vm" "session=${sid};backup_id=${backup_id}"
  else
    # Always mark seen on failure so restore-watch does not re-stop the same VM every 3min.
    # Set RESTORE_WATCH_RETRY_FAILED=true to allow retries of failed sessions.
    if [[ "${RESTORE_WATCH_RETRY_FAILED:-false}" != "true" ]]; then
      mold_backup_restore_session_mark_seen "$sid"
    fi
    mold_backup_emit_restore_event "mold.restore.failed" "$vm" "session=${sid};backup_id=${backup_id}"
    rc=1
  fi
  mold_backup_trigger_clear "mold-restore-active" "$vm"
  mold_backup_trigger_clear "veeam-restore-active" "$vm"
  mold_backup_restore_lock_release
  return "$rc"
}

# Poll Veeam restore sessions and reflect new ones for VMs we manage (VM_TARGETS).
# When trigger_mold=true, the owning KVM host acquires a cluster flock and calls Mold restore API.
mold_backup_watch_veeam_restores() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local since_min="${2:-${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}}"
  local trigger_mold="${3:-${RESTORE_WATCH_TRIGGER_MOLD:-false}}"
  if [[ -z "${VM_TARGETS:-}" && "${BACKUP_MODE:-host}" == "host" && -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]]; then
  # Host mode: build name:ip pairs from libvirt when VM_TARGETS unset.
  # Stopped VMs have no libvirt domain — virsh fails; keep going with name-only targets.
    local _vm _ip
    VM_TARGETS=""
    for _vm in ${VM_INCLUDE//,/ }; do
      _vm="$(echo "$_vm" | xargs)"
      [[ -n "$_vm" ]] || continue
      _ip="$(virsh -c qemu:///system domifaddr "$_vm" 2>/dev/null | awk '/ipv4/ {print $4; exit}' | cut -d/ -f1 || true)"
      VM_TARGETS+="${VM_TARGETS:+,}${_vm}:${_ip:-${_vm}}"
    done
  fi
  [[ -n "${VM_TARGETS:-}" ]] || {
    mold_backup_notify_log warn "Veeam→Mold(restore): VM_TARGETS empty; set VM_INCLUDE or VM_TARGETS"
    return 0
  }
  [[ "${trigger_mold}" == "true" ]] && mold_backup_restore_preflight
  mold_backup_notify_log info "=== restore-watch job=${job} window=${since_min}min trigger_mold=${trigger_mold} host=$(mold_backup_local_kvm_name) ==="
  local sid sip et result nm bn rp_id rp_epoch matched_ip vm
  local processed=0 handled=0
  while IFS='|' read -r sid sip et result nm bn rp_id rp_epoch; do
    [[ -n "$sid" ]] || continue
    rp_epoch="${rp_epoch// /}"
    [[ "$rp_epoch" == "n/a" ]] && rp_epoch=""
    [[ "$rp_epoch" =~ ^[0-9]+$ ]] || rp_epoch=""
    # Drop SSH/pwsh noise mistaken for sessions (must not trigger stopVirtualMachine).
    if [[ "$sid" == Warning:* || "$sid" == *Permanently\ added* || "$sid" == *known\ hosts* ]]; then
      mold_backup_notify_log info "restore-watch: ignore non-session noise sid='${sid:0:80}'"
      continue
    fi
    # Accept GUID / local-flr-* only (real Veeam or local FLR markers).
    if [[ ! "$sid" =~ ^[0-9a-fA-F-]{8,} && "$sid" != local-flr-* ]]; then
      mold_backup_notify_log info "restore-watch: ignore invalid session id='${sid:0:80}'"
      continue
    fi
    # Never Mold-restore on in-progress FLR (Result=None) — stops VM and drops Veeam session.
    if [[ -n "$result" && ! "$result" =~ ^(Success|Warning)$ ]]; then
      mold_backup_notify_log info "restore-watch: skip session ${sid} result='${result}' (need Success/Warning)"
      continue
    fi
    # Invalid/MinValue end time (e.g. -2208960000) must not trigger restore.
    if [[ -n "$et" ]] && { [[ "$et" =~ ^- ]] || [[ ! "$et" =~ ^[0-9]+$ ]] || (( et < 1000000000 )); }; then
      mold_backup_notify_log info "restore-watch: skip session ${sid} bad end='${et}'"
      continue
    fi
    # Same KVM host can run Job 2 + Job 6 + ablecubeN. FLR sessions must only be
    # handled by the watch whose --job matches the session backup / FLR name.
    # Examples: bn=ablecube2, nm=FLR__ablecube2_, bn=Agent Backup Job 6
    local _flr_job=""
    if [[ "$nm" =~ [Ff][Ll][Rr]_+([^_|]+) ]]; then
      _flr_job="${BASH_REMATCH[1]}"
    elif [[ "$nm" =~ [Ff][Ll][Rr].*_([^_|]+)_*$ ]]; then
      _flr_job="${BASH_REMATCH[1]}"
    fi
    if [[ -n "$job" ]]; then
      local _owns=false
      if [[ -n "$bn" && ( "$bn" == "$job" || "$bn" == *"$job"* ) ]]; then
        _owns=true
      elif [[ -n "$_flr_job" && ( "$_flr_job" == "$job" || "$job" == *"$_flr_job"* ) ]]; then
        _owns=true
      elif [[ -n "$nm" && "$nm" == *"$job"* ]]; then
        _owns=true
      elif [[ -z "$bn" && -z "$_flr_job" ]]; then
        # No job identity in session — only allow if this conf is the host-named job.
        if [[ "$job" == "$(hostname -s)" || "$job" == ablecube* ]]; then
          _owns=true
        fi
      fi
      # Explicit other-job identity → skip (prevents Agent Job 3 claiming ablecube2 FLR).
      if [[ "$_owns" != "true" ]]; then
        if [[ -n "$bn" || -n "$_flr_job" || "$nm" == FLR_* || "$nm" == *FLR* ]]; then
          mold_backup_notify_log info "restore-watch: session ${sid} backup='${bn}' flr_job='${_flr_job:-}' name='${nm}' ≠ job='${job}' (skip; owned by other job)"
          continue
        fi
      fi
    fi
    processed=$((processed+1))
    if mold_backup_restore_session_seen "$sid"; then
      mold_backup_notify_log info "restore-watch: session ${sid} already processed (skip duplicate)"
      continue
    fi
    # Match: prefer the IP parsed from the session Options; fall back to scanning
    # VM_TARGETS IPs (dots or dashes form) against the session name / backup name.
    matched_ip=""
    local host_hit=false
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
        if [[ "$sip" == "$ip" || "$nm" == *"$ip"* || "$nm" == *"$ipd"* || "$bn" == *"$ip"* || "$bn" == *"$ipd"* \
              || ( -n "$vmn" && ( "$nm" == *"$vmn"* || "$bn" == *"$vmn"* ) ) ]]; then
          matched_ip="$ip"
          break
        fi
      done
    fi
    # Host backup (KVM agent e.g. 10.10.31.2): FLR session targets the hypervisor.
    if [[ -z "$matched_ip" && "${BACKUP_MODE:-host}" == "host" ]]; then
      local kvm_ip="${KVM_IP:-}"
      [[ -z "$kvm_ip" && -n "${KVM_HOST:-}" && "$KVM_HOST" == *@* ]] && kvm_ip="${KVM_HOST#*@}"
      [[ -z "$kvm_ip" && -n "${KVM_HOST:-}" && "$KVM_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] && kvm_ip="${KVM_HOST}"
      [[ -z "$kvm_ip" ]] && kvm_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
      local kvm_hn="${KVM_HOSTNAME:-$(hostname -s)}"
      # Require hypervisor identity in session — do NOT match on job name alone
      # (that falsely treated backup sessions as FLR and auto-stopped VMs).
      if [[ -n "$kvm_ip" && ( "$sip" == "$kvm_ip" || "$nm" == *"$kvm_ip"* || "$bn" == *"$kvm_ip"* ) ]]; then
        host_hit=true
      elif [[ -n "$kvm_hn" && ( "$nm" == *"$kvm_hn"* || "$bn" == *"$kvm_hn"* || "$nm" == FLR_* ) ]]; then
        host_hit=true
      fi
      if [[ "$host_hit" == "true" ]]; then
        IFS=',' read -ra _pairs <<<"${VM_TARGETS}"
        local pair vmn ip
        for pair in "${_pairs[@]}"; do
          pair="${pair// /}"
          vmn="${pair%%:*}"
          [[ -n "$vmn" ]] || continue
          if [[ "$nm" == *"$vmn"* || "$bn" == *"$vmn"* ]]; then
            ip="${pair#*:}"
            matched_ip="${ip:-$vmn}"
            break
          fi
        done
        if [[ -z "$matched_ip" && -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]]; then
          local _one
          if [[ -n "${VEEAM_RESTORE_VM:-}" ]]; then
            _one="${VEEAM_RESTORE_VM}"
            mold_backup_notify_log info "restore-watch: host FLR session ${sid} → VEEAM_RESTORE_VM=${_one}"
          else
            _one="$(echo "${VM_INCLUDE}" | tr ',' ' ' | awk '{print $1}')"
            mold_backup_notify_log info "restore-watch: host FLR session ${sid} → VM_INCLUDE=${_one} (set VEEAM_RESTORE_VM for explicit target)"
          fi
          if [[ -n "$_one" ]]; then
            matched_ip="$_one"
            for pair in "${_pairs[@]}"; do
              pair="${pair// /}"
              vmn="${pair%%:*}"
              ip="${pair#*:}"
              [[ "$vmn" == "$_one" && -n "$ip" && "$ip" != "$vmn" ]] && matched_ip="$ip" && break
            done
          fi
        fi
      fi
    fi
    # Agent FLR to hypervisor: only when session already identified as host FLR.
    if [[ -z "$matched_ip" && "$host_hit" == "true" && "${BACKUP_MODE:-host}" == "host" && -n "${VEEAM_RESTORE_VM:-}" ]]; then
      local _pair _vmn _ip
      IFS=',' read -ra _pairs <<<"${VM_TARGETS}"
      for _pair in "${_pairs[@]}"; do
        _pair="${_pair// /}"
        _vmn="${_pair%%:*}"
        _ip="${_pair#*:}"
        [[ "$_vmn" == "${VEEAM_RESTORE_VM}" ]] || continue
        matched_ip="${_ip:-$_vmn}"
        [[ "$matched_ip" == "$_vmn" ]] && matched_ip="$_vmn"
        break
      done
      [[ -z "$matched_ip" ]] && matched_ip="${VEEAM_RESTORE_VM}"
      mold_backup_notify_log info "restore-watch: session ${sid} name='${nm}' → host FLR fallback VEEAM_RESTORE_VM=${VEEAM_RESTORE_VM}"
    fi
    if [[ -z "$matched_ip" ]]; then
      mold_backup_notify_log info "restore-watch: session ${sid} ip='${sip}' name='${nm}' backup='${bn}' rp='${rp_id:-}' — no VM_TARGETS match (skip)"
      continue
    fi
    vm="$(mold_backup_vm_name_for_ip "$matched_ip" 2>/dev/null || true)"
    [[ -n "$vm" ]] || vm="$matched_ip"
    if ! mold_backup_vm_restorable_on_local_host "$vm" 2>/dev/null; then
      mold_backup_notify_log info "restore-watch: session ${sid} vm='${vm}' — not on this Mold host (virsh empty when Stopped is normal)"
      continue
    fi
    if ! mold_backup_domain_exists "$vm" 2>/dev/null; then
      mold_backup_notify_log info "restore-watch: vm=${vm} Mold Stopped (no libvirt) — triggering Mold restoreBackup via API"
    fi
    [[ -n "$rp_id" ]] && mold_backup_notify_log info "restore-watch: session ${sid} vm=${vm} veeam_rp=${rp_id} rp_epoch=${rp_epoch:-n/a}"
    mold_backup_handle_veeam_restore_session "$job" "$vm" "$sid" \
      "name=${nm};end=${et};result=${result};backup=${bn};ip=${matched_ip};rp=${rp_id};rp_epoch=${rp_epoch:-}" "$trigger_mold" "$rp_id" \
      && {
        handled=$((handled+1))
        [[ "$sid" == local-flr-* ]] && mold_backup_local_flr_mark_epoch "$vm" "$et"
      } || mold_backup_notify_log warn "restore-watch: session ${sid} vm=${vm} handle failed (see restore.log)"
  done < <(
    mold_backup_query_veeam_restores "$since_min" 2>/dev/null || true
    mold_backup_query_local_host_flr "$since_min" 2>/dev/null || true
  )
  mold_backup_notify_log info "=== restore-watch done: scanned=${processed} handled=${handled} trigger_mold=${trigger_mold} ==="
  return 0
}

# === Veeam UI backup reflection (backup-watch) =============================
# Mirror of restore-watch for the *backup* direction: poll Veeam backup
# sessions and record new ones into the Mold-side registry, so a backup that
# was started directly from the Veeam console shows up in Mold without any
# per-job pre/post script. Dedup is by Veeam session id (separate state file).

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

# Run PowerShell on Veeam B&R via SSH (UTF-16LE base64 -EncodedCommand).
# Tries pwsh full path, then pwsh.exe, then powershell.exe (Windows OpenSSH PATH quirks).
# Auth: VEEAM_SSH_KEY, else sshpass+VEEAM_SSH_PASSWORD, else SSH_ASKPASS+password, else BatchMode.
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

# Query Veeam B&R for the newest restore point GUID for a guest Agent job.
# Guest Agent backups register under computer IP/hostname in Veeam, not libvirt i-2-XX-VM.
# Tries: computer backup job → backup chain → restore points, then name/IP filters.
# Prints restore point GUID on stdout; returns 1 if none found.
mold_backup_query_veeam_latest_restore_point() {
  local job="$1" vm_name="$2" guest_ip="${3:-}" retries="${4:-6}" attempt rp_id
  for ((attempt=1; attempt<=retries; attempt++)); do
    rp_id="$(mold_backup_query_veeam_latest_restore_point_once "$job" "$vm_name" "$guest_ip" 2>/dev/null || true)"
    [[ -n "$rp_id" ]] && { echo "$rp_id"; return 0; }
    if [[ "$attempt" -lt "$retries" ]]; then
      mold_backup_notify_log info "guest post: restore point not ready (attempt ${attempt}/${retries}); retry in 10s job=${job} vm=${vm_name}"
      sleep 10
    fi
  done
  mold_backup_notify_log warn "guest post: no Veeam restore point after ${retries} attempts job=${job} vm=${vm_name}"
  return 1
}

mold_backup_query_veeam_latest_restore_point_once() {
  local job="$1" vm_name="$2" guest_ip="${3:-}"
  local job_esc vm_esc ip_esc dash_ip ps_script ps_enc out
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "guest post: VEEAM_SSH_HOST not set; cannot query restore points"
    return 1
  }
  job_esc="${job//\'/\'\'}"
  vm_esc="${vm_name//\'/\'\'}"
  ip_esc="${guest_ip//\'/\'\'}"
  dash_ip="${guest_ip//./-}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'SilentlyContinue'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
try { Connect-VBRServer -Server localhost -ErrorAction Stop } catch {}
\$JobName = '${job_esc}'
\$VmName = '${vm_esc}'
\$GuestIp = '${ip_esc}'
\$DashIp = '${dash_ip}'
\$names = @()
if (\$VmName) { \$names += \$VmName }
if (\$GuestIp) { \$names += \$GuestIp; \$names += \$DashIp }
\$rp = \$null
\$job = Get-VBRComputerBackupJob -Name \$JobName -ErrorAction SilentlyContinue
if (\$job) {
  \$backups = @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object { \$_.JobId -eq \$job.Id })
  foreach (\$b in \$backups) {
    \$cand = \$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue |
      Sort-Object CreationTime -Descending | Select-Object -First 1
    if (\$cand) { \$rp = \$cand; break }
  }
}
if (-not \$rp -and \$job) {
  \$sessions = @(Get-VBRComputerBackupJobSession -ErrorAction SilentlyContinue |
    Where-Object { \$_.JobId -eq \$job.Id -and (\$_.Result -eq 'Success' -or \$_.Result -eq 'Warning') } |
    Sort-Object { if (\$null -ne \$_.EndTime) { \$_.EndTime } else { \$_.CreationTime } } -Descending)
  foreach (\$s in \$sessions) {
    if (\$s.BackupId) {
      \$b = Get-VBRBackup -Id \$s.BackupId -ErrorAction SilentlyContinue
      if (\$b) {
        \$cand = \$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue |
          Sort-Object CreationTime -Descending | Select-Object -First 1
        if (\$cand) { \$rp = \$cand; break }
      }
    }
    if (\$s.PointId) {
      \$pid = \$s.PointId
      if (\$pid -is [guid]) { \$pid = \$pid.Guid }
      \$cand = Get-VBRRestorePoint -ErrorAction SilentlyContinue |
        Where-Object { \$_.Id -eq \$pid -or \$_.Id.Guid -eq \$pid } |
        Sort-Object CreationTime -Descending | Select-Object -First 1
      if (\$cand) { \$rp = \$cand; break }
    }
  }
}
if (-not \$rp) {
  \$backups = @(Get-VBRBackup -ErrorAction SilentlyContinue | Where-Object {
    \$_.JobName -eq \$JobName -or \$_.Name -like "*\$JobName*"
  })
  foreach (\$b in \$backups) {
    \$cand = \$b | Get-VBRRestorePoint -ErrorAction SilentlyContinue |
      Sort-Object CreationTime -Descending | Select-Object -First 1
    if (\$cand) { \$rp = \$cand; break }
  }
}
if (-not \$rp) {
  foreach (\$n in \$names) {
    if (-not \$n) { continue }
    \$cand = Get-VBRRestorePoint -ErrorAction SilentlyContinue |
      Where-Object { \$_.VmName -eq \$n -or \$_.Name -eq \$n -or \$_.Name -like "*\$n*" } |
      Sort-Object CreationTime -Descending | Select-Object -First 1
    if (\$cand) { \$rp = \$cand; break }
  }
}
if (-not \$rp) { exit 1 }
\$rpId = \$rp.Id
if (\$rpId -is [guid]) { \$rpId = \$rpId.Guid }
Write-Output \$rpId
PS
)"
  ps_enc="$(printf '%s' "$ps_script" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null | base64 -w0 2>/dev/null)"
  [[ -n "$ps_enc" ]] || return 1
  local out
  out="$(mold_backup_veeam_ssh_encoded "$ps_enc" 2>/dev/null || true)"
  [[ -n "$out" ]] || return 1
  out="${out//$'\r'/}"
  out="$(printf '%s' "$out" | grep -E '^[0-9a-fA-F-]{36}$' | tail -1)"
  [[ -n "$out" ]] && { echo "$out"; return 0; }
  mold_backup_notify_log warn "Veeam restore point query: SSH ok but no GUID (job=${job})"
  return 1
}

# Query Veeam for backup sessions that completed within the last N minutes.
# Emits one line per session: sessionId|targetIp|endEpoch|result|name|jobName
# The target IP is parsed from the session/job name (guest VM jobs are named
# "Mold VM 10-10-254-70" — dash form — so the dash IP is recovered to dots).
# Time-window filtering is applied afterwards in bash on the epoch field, same
# as restore-watch (avoids PowerShell/Veeam timezone quirks).
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

# Reflect a single Veeam backup session into the Mold-side registry for one VM.
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

# Poll Veeam backup sessions and reflect new ones for VMs we manage (VM_TARGETS).
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

# Guest Agent on VM: Veeam pre runs before restore point exists — defer Mold API to post.
mold_backup_guest_pre_notify_vm() {
  local vm_name="$1" offering_id="$2" state_file="$3"
  local vm_id
  vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || {
    mold_backup_notify_log err "No Mold VM id for ${vm_name}"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} status=fail reason=no-vm-id"
    return 1
  }
  [[ -n "$offering_id" ]] && mold_backup_api_assign_offering_if_needed "$vm_id" "$offering_id"
  mold_backup_api_check_vm_environment "$vm_id" "$vm_name"
  if mold_backup_trigger_active "mold-active" "$vm_name"; then
    mold_backup_trigger_clear "mold-active" "$vm_name"
    mold_backup_notify_log info "mold-active for ${vm_name}: skip guest pre (Mold already backed up)"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=success reason=mold-triggered"
    return 0
  fi
  mold_backup_trigger_mark "veeam-active" "$vm_name"
  mold_backup_notify_log info "guest pre: defer Mold backup until Veeam post (restore point not ready yet) vm=${vm_name}"
  mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=pending reason=veeam-guest-pre"
  return 0
}

# After Veeam backup completes, restore point exists — create Mold backup record now.
mold_backup_guest_post_notify_vm() {
  local vm_name="$1" vm_id="$2" job="$3"
  local backup_result backup_id backup_type offering_id guest_ip rp_id chain_count
  local host_path staging_paths source_format vm_offering json
  [[ -n "$vm_id" ]] || vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$vm_id" ]] || {
    mold_backup_notify_log err "guest post: no Mold VM id for ${vm_name}"
    return 1
  }
  offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" "$(mold_backup_offering_name)" 2>/dev/null || true)"
  [[ -n "$offering_id" ]] && mold_backup_api_assign_offering_if_needed "$vm_id" "$offering_id"
  [[ -n "$job" ]] || job="$(mold_backup_veeam_job_name_for_vm "$vm_name")"
  guest_ip="$(mold_backup_vm_guest_ip "$vm_name" 2>/dev/null || true)"
  [[ -n "$guest_ip" ]] || mold_backup_notify_log warn "guest post: VM_TARGETS missing ${vm_name}:ip in conf/env (see mold-backup.env)"
  rp_id="$(mold_backup_query_veeam_latest_restore_point "$job" "$vm_name" "$guest_ip" 2>/dev/null || true)"
  if [[ -z "$rp_id" ]]; then
    mold_backup_notify_log err "guest post: no Veeam restore point for ${vm_name} (job=${job} ip=${guest_ip:-n/a} veeam=${VEEAM_SSH_HOST:-unset}; run Veeam backup to Success first)"
    return 1
  fi
  mold_backup_notify_log info "guest post: Veeam restore point=${rp_id} vm=${vm_name} job=${job}"
  chain_count="$(mold_backup_api_veeam_backup_count "$vm_id")"
  if [[ "${chain_count:-0}" -eq 0 ]]; then
    # Guest SelectedFiles backups have file-level restore points (no exportable disks on Veeam).
    # Seed NAS from live KVM disks (same as host file-level first backup), tag Veeam RP id.
    mold_backup_notify_log info "guest post: first backup — host export + importSeed (SelectedFiles; skip MS→Veeam disk export)"
    if ! host_path=$(mold_backup_run_host_export "$vm_name" "1" 2>/dev/null); then
      mold_backup_notify_log err "guest post: host export failed for ${vm_name} (check ${HOST_EXPORT_SCRIPT:-ablestack_veeam_host_export.sh} and /var/log/mold/veeam-hook.log)"
      return 1
    fi
    if [[ ! -d "$host_path" ]]; then
      mold_backup_notify_log err "guest post: host export invalid path: ${host_path}"
      return 1
    fi
    staging_paths="$(mold_backup_collect_host_staging_paths "$host_path" 2>/dev/null || true)"
    if [[ -z "$staging_paths" ]]; then
      mold_backup_notify_log err "guest post: no staging disks under ${host_path}"
      return 1
    fi
    source_format="$(mold_backup_detect_staging_source_format "$staging_paths")"
    json=$(mold_backup_cmk_run listVirtualMachines "id=${vm_id}" 2>/dev/null || true)
    vm_offering="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.backupofferingid")"
    mold_backup_api_validate_offering_repository "$vm_offering" || return 1
    backup_result="$(mold_backup_api_import_staging_rp_seed_and_wait "$vm_id" "$staging_paths" "$source_format" "$rp_id" "$vm_name" || true)"
  else
    mold_backup_notify_log info "guest post: incremental — createAblestackVeeamBackup vm=${vm_name} (chain=${chain_count})"
    backup_result="$(mold_backup_api_create_veeam_and_wait "$vm_id" "$vm_name" 2>/dev/null || true)"
  fi
  backup_id="${backup_result%%|*}"
  backup_type="${backup_result#*|}"
  if [[ -n "$backup_id" ]]; then
    mold_backup_registry_save_backup "$job" "$vm_name" "$backup_id" "veeam-backed-up rp=${rp_id}" "$rp_id"
    export BACKUP_ID="$backup_id" VM_NAME="$vm_name"
    mold_backup_notify_log info "guest post OK vm=${vm_name} backup_id=${backup_id} type=${backup_type} rp=${rp_id}"
    return 0
  fi
  mold_backup_notify_log err "guest post: Mold API backup failed for ${vm_name} (rp=${rp_id} chain=${chain_count:-0}; check MS/agent.log)"
  return 1
}

mold_backup_pre_notify() {
  local client="${1:-$(hostname -s)}"
  local job="$(mold_backup_normalize_job_name "${2:-${VEEAM_JOB_NAME:-}}")"
  local schedule="${3:-${VEEAM_SCHEDULE_NAME:-default}}"
  local single_vm="${4:-}"
  local saved_include=""
  [[ -n "$job" ]] || mold_backup_die "VEEAM_JOB_NAME is required for pre-notify"
  VEEAM_JOB_NAME="$job"
  mold_backup_load_config || exit 1
  VEEAM_JOB_NAME="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-$job}")"
  job="$VEEAM_JOB_NAME"

  if [[ -n "$single_vm" ]]; then
    saved_include="${VM_INCLUDE:-*}"
    VM_INCLUDE="$single_vm"
    mold_backup_notify_log info "single-vm scope: ${single_vm}"
  fi

  mold_backup_notify_log info "=== pre-notify (설계4: Pre-script + Mold API 백업요청) client=${client} job=${job} schedule=${schedule} ==="
  export MOLD_BACKUP_HOOK="pre-notify"
  export VEEAM_SCHEDULE_NAME="$schedule"
  SCHEDULE="$schedule"
  mkdir -p "$(mold_backup_state_dir)" "${VEEAM_HOST_BACKUP_PATH}" "${VEEAM_AGENT_PAYLOAD_PATH}"

  local offering_id="" vm_name
  local success=0 fail=0 skip=0
  local state_file run_id
  run_id="$(date '+%Y%m%d%H%M%S')"
  state_file="$(mold_backup_state_file_for_job "$job" "$run_id")"
  : > "$state_file"
  echo "run_id=${run_id}" >> "$state_file"

  if mold_backup_cmk_bin >/dev/null 2>&1 || command -v curl >/dev/null 2>&1; then
    mold_backup_api_ensure_global_settings || true
    # Always try to register VeeamBackup (ablestack-veeam, externalid=veeam) — including datadisk/host.
    offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" "$(mold_backup_offering_name)" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] || offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
    if [[ -z "$offering_id" ]]; then
      offering_id="$(mold_backup_api_ensure_backup_resources 2>/dev/null || true)"
    fi
    [[ -n "$offering_id" ]] || {
      mold_backup_notify_log warn "No backup offering '$(mold_backup_offering_name)' for ${VEEAM_PROVIDER_NAME}; check Admin API key, ZONE_ID, and backup.framework.provider.plugin=ablestack-veeam"
    }
  fi

  while IFS= read -r vm_name; do
    [[ -z "$vm_name" ]] && continue
    mold_backup_vm_in_filter "$vm_name" || continue
    mold_backup_notify_log info "Target VM ${vm_name}"

    case "${BACKUP_MODE}" in
      guest|veeam-guest)
        if mold_backup_guest_pre_notify_vm "$vm_name" "$offering_id" "$state_file"; then
          success=$((success + 1))
        else
          fail=$((fail + 1))
        fi
        ;;
      host|policy)
        local _rc=0
        mold_backup_process_vm_pre_notify "$vm_name" "$offering_id" "$state_file" || _rc=$?
        if [[ "$_rc" -eq 0 ]]; then
          success=$((success + 1))
        elif [[ "$_rc" -eq 2 ]]; then
          skip=$((skip + 1))
        else
          fail=$((fail + 1))
        fi
        ;;
      api|local|auto)
        local vm_id backup_result backup_id
        vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
        [[ -n "$vm_id" ]] || { fail=$((fail + 1)); continue; }
        [[ -n "$offering_id" ]] && mold_backup_api_assign_offering_if_needed "$vm_id" "$offering_id"
        mold_backup_api_check_vm_environment "$vm_id" "$vm_name"
        backup_result="$(mold_backup_api_create_veeam_and_wait "$vm_id" "$vm_name" 2>/dev/null || true)"
        backup_id="${backup_result%%|*}"
        if [[ -n "$backup_id" ]]; then
          mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} backup_id=${backup_id} status=success"
          success=$((success + 1))
        else
          fail=$((fail + 1))
        fi
        ;;
      *)
        mold_backup_die "Invalid BACKUP_MODE=${BACKUP_MODE} (use guest|host|policy|api|local|auto)"
        ;;
    esac
  done < <(mold_backup_list_target_domains || true)

  if [[ "$success" -eq 0 && "$fail" -eq 0 && "$skip" -eq 0 ]]; then
    mold_backup_notify_log warn "No target VMs for job=${job} (vm_include=${VM_INCLUDE:-*}). Start VM or set VM_INCLUDE to libvirt name(s)."
    if [[ "${VM_INCLUDE:-*}" != "*" ]]; then
      local _t
      for _t in ${VM_INCLUDE//,/ }; do
        _t="$(echo "$_t" | xargs)"
        [[ -z "$_t" ]] && continue
        if mold_backup_domain_exists "$_t"; then
          if virsh -c qemu:///system dominfo "$_t" 2>/dev/null | grep -q 'State:.*shut off'; then
            mold_backup_notify_log warn "VM ${_t} exists but is shut off — start it for host export: virsh start ${_t}"
          fi
        else
          mold_backup_notify_log warn "VM ${_t} not found in libvirt on $(hostname -s)"
        fi
      done
    fi
  fi

  mold_backup_notify_log info "pre-notify done success=${success} skip=${skip} fail=${fail} state=${state_file}"
  [[ -n "$saved_include" ]] && VM_INCLUDE="$saved_include"
  [[ "$success" -gt 0 ]] && return 0
  # All skipped (existing offerings) is not a hard failure for the Veeam job.
  [[ "$fail" -eq 0 && "$skip" -gt 0 ]] && return 0
  return 1
}

mold_backup_post_notify() {
  local client="${1:-$(hostname -s)}"
  local job="$(mold_backup_normalize_job_name "${2:-${VEEAM_JOB_NAME:-}}")"
  local schedule="${3:-${VEEAM_SCHEDULE_NAME:-default}}"
  [[ -n "$job" ]] || mold_backup_die "VEEAM_JOB_NAME is required for post-notify"
  VEEAM_JOB_NAME="$job"
  mold_backup_load_config || exit 1
  VEEAM_JOB_NAME="$(mold_backup_normalize_job_name "${VEEAM_JOB_NAME:-$job}")"
  job="$VEEAM_JOB_NAME"

  export MOLD_BACKUP_HOOK="post-notify"
  mold_backup_notify_log info "=== post-notify (설계4: Post-script + 백업ID 저장) client=${client} job=${job} ==="
  local state_file line vm_name backup_id status guest_handled=0 guest_fail=0 vm_id reason rc=0
  local host_rp_id=""
  state_file="$(mold_backup_latest_state_file "$job" || true)"

  # Host/filelevel: stamp RP onto Mold backup so BackupSync keeps rows while Veeam RP exists.
  # Agent file-level RPs are usually named by hypervisor IP/hostname (not guest i-*-VM).
  if [[ "${BACKUP_MODE:-host}" == "host" || "${BACKUP_MODE:-host}" == "policy" ]]; then
    local rp_probe
    if [[ "${SKIP_VEEAM_RP_QUERY:-false}" == "true" ]]; then
      mold_backup_notify_log info "post-notify: skip Veeam restore-point query (SKIP_VEEAM_RP_QUERY=true)"
    else
      for rp_probe in \
          "$job" \
          "${KVM_HOSTNAME:-}" \
          "$(hostname -s 2>/dev/null || true)" \
          "${KVM_IP:-}" \
          "$(hostname -I 2>/dev/null | awk '{print $1}')"; do
        [[ -n "$rp_probe" ]] || continue
        host_rp_id="$(mold_backup_query_veeam_latest_restore_point_rest "$rp_probe" 2>/dev/null || true)"
        [[ -n "$host_rp_id" ]] && break
      done
      if [[ -z "$host_rp_id" ]]; then
        # REST objectRestorePoints often misses Agent jobs — PowerShell Get-VBRRestorePoint works.
        for rp_probe in "$job" "${KVM_HOSTNAME:-}" "$(hostname -s 2>/dev/null || true)" "${KVM_IP:-}"; do
          [[ -n "$rp_probe" ]] || continue
          host_rp_id="$(mold_backup_query_veeam_latest_restore_point "$rp_probe" "" "" 1 2>/dev/null || true)"
          [[ -n "$host_rp_id" ]] && break
        done
      fi
      if [[ -n "$host_rp_id" ]]; then
        mold_backup_notify_log info "post-notify: host job Veeam restore point=${host_rp_id}"
      else
        mold_backup_notify_log warn "post-notify: no Veeam restore point found for job=${job} (catalog sync may delete Mold rows after grace)"
      fi
    fi
  fi

  if [[ -f "$state_file" ]]; then
    while IFS= read -r line; do
      [[ "$line" =~ ^vm= ]] || continue
      vm_name="$(mold_backup_state_parse_field "$line" "vm")"
      backup_id="$(mold_backup_state_parse_field "$line" "backup_id")"
      status="$(mold_backup_state_parse_field "$line" "status")"
      vm_id="$(mold_backup_state_parse_field "$line" "id")"
      reason="$(mold_backup_state_parse_field "$line" "reason")"
      # Mold schedule/UI backup → Veeam trigger: Mold NAS backup already exists (HOURLY/MANUAL).
      if [[ "$status" == "success" && "$reason" == "mold-triggered" ]]; then
        mold_backup_notify_log info "guest post: skip for ${vm_name} (Mold backup already done; no duplicate import)"
        guest_handled=1
        if [[ -n "$vm_name" ]]; then
          mold_backup_trigger_clear "veeam-active" "$vm_name"
          # Keep restore-watch from treating fresh staging as FLR after veeam-active clears.
          mold_backup_trigger_mark "backup-cooldown" "$vm_name"
        fi
        continue
      fi
      if [[ "$status" == "pending" && "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
        mold_backup_notify_log info "guest post: pending vm=${vm_name} mode=${BACKUP_MODE} reason=${reason:-veeam-guest-pre}"
        if mold_backup_guest_post_notify_vm "$vm_name" "$vm_id" "$job"; then
          guest_handled=1
        else
          guest_fail=$((guest_fail + 1))
        fi
        if [[ -n "$vm_name" ]]; then
          mold_backup_trigger_clear "veeam-active" "$vm_name"
          mold_backup_trigger_mark "backup-cooldown" "$vm_name"
        fi
        continue
      fi
      # Veeam job for this VM finished — clear the loop-guard marker.
      if [[ -n "$vm_name" ]]; then
        mold_backup_trigger_clear "veeam-active" "$vm_name"
        mold_backup_trigger_mark "backup-cooldown" "$vm_name"
      fi
      if [[ "$status" == "pending" ]]; then
        mold_backup_notify_log warn "post-notify: pending vm=${vm_name} but BACKUP_MODE=${BACKUP_MODE:-host} (expected guest)"
      fi
      [[ "$status" == "success" && -n "$backup_id" ]] || continue
      mold_backup_registry_save_backup "$job" "$vm_name" "$backup_id" "veeam-backed-up rp=${host_rp_id:-n/a}" "$host_rp_id"
      if [[ -n "$host_rp_id" || -n "$job" ]]; then
        mold_backup_api_update_veeam_backup "$backup_id" "$host_rp_id" "$job"
      fi
    done < "$state_file"
  else
    mold_backup_notify_log warn "No state file for job ${job}"
    if [[ "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
      vm_name="$(mold_backup_vm_name_for_job "$job" 2>/dev/null || true)"
      if [[ -n "$vm_name" ]]; then
        mold_backup_notify_log info "guest post: fallback (no state) vm=${vm_name}"
        if mold_backup_guest_post_notify_vm "$vm_name" "" "$job"; then
          guest_handled=1
        else
          guest_fail=$((guest_fail + 1))
        fi
        mold_backup_trigger_clear "veeam-active" "$vm_name"
        mold_backup_trigger_mark "backup-cooldown" "$vm_name"
      fi
    fi
  fi

  if [[ "$guest_handled" -eq 0 && "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
    vm_name="$(mold_backup_vm_name_for_job "$job" 2>/dev/null || true)"
    if [[ -n "$vm_name" ]]; then
      mold_backup_notify_log info "guest post: fallback (no pending state) vm=${vm_name}"
      if mold_backup_guest_post_notify_vm "$vm_name" "" "$job"; then
        guest_handled=1
      else
        guest_fail=$((guest_fail + 1))
      fi
      mold_backup_trigger_clear "veeam-active" "$vm_name"
      mold_backup_trigger_mark "backup-cooldown" "$vm_name"
    fi
  fi

  if [[ "$guest_fail" -gt 0 ]]; then
    mold_backup_notify_log err "post-notify: guest Mold backup failed for job=${job} (restore will not work until post succeeds)"
    rc=1
  else
    rc=0
  fi

  if [[ "$guest_handled" -eq 0 && "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
    mold_backup_notify_log warn "post-notify: no guest VM processed for job=${job} (re-run pre-notify before post, or check state dir)"
  fi

  if [[ "${CLEANUP_STAGING_AFTER_BACKUP}" == "true" ]]; then
    mold_backup_cleanup_host_path
    mold_backup_cleanup_staging
  else
    mold_backup_notify_log info "Skip staging cleanup (CLEANUP_STAGING_AFTER_BACKUP=${CLEANUP_STAGING_AFTER_BACKUP})"
  fi
  [[ -f "$state_file" ]] && rm -f "$state_file"
  mold_backup_notify_log info "=== post-notify done (guest_fail=${guest_fail}) ==="
  return "$rc"
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
  local client="${1:-$(hostname -s)}"
  local job="${2:-${VEEAM_JOB_NAME:-}}"
  export MOLD_BACKUP_HOOK="restore-notify"
  mold_backup_load_config || exit 1
  mold_backup_apply_datadisk_profile
  local restore_source="${RESTORE_SOURCE:-auto}"
  if mold_backup_is_datadisk_mode; then
    restore_source="mold-only"
    RESTORE_SOURCE="mold-only"
  fi
  mold_backup_notify_log info "=== restore-notify client=${client} job=${job} backup_id=${BACKUP_ID:-} vm=${VM_NAME:-} source=${restore_source} ==="
  mold_backup_require_var BACKUP_ID
  [[ -n "${VM_UUID:-}" ]] || {
    [[ -n "${VM_NAME:-}" ]] && VM_UUID="$(mold_backup_api_get_vm_id "$VM_NAME" 2>/dev/null || true)"
  }
  [[ -n "${VM_UUID:-}" ]] || mold_backup_die "restore requires VM_NAME or VM_UUID (individual VM restore)"
  mold_backup_api_verify_backup_for_vm "$BACKUP_ID" "$VM_UUID" || exit 1

  # Loop guard for reverse restore-sync: mark this VM so the Veeam->Mold restore
  # watcher skips reflecting a restore that Mold itself initiated.
  [[ -n "${VM_NAME:-}" ]] && mold_backup_trigger_mark "mold-restore-active" "$VM_NAME"

  if mold_backup_api_is_full_backup "$BACKUP_ID"; then
    mold_backup_notify_log info "Restore type=FULL → Mold restoreBackup (datadisk ${BACKUP_REPO_ADDRESS:-/data/backup})"
    mold_backup_api_restore || return 1
  else
    mold_backup_notify_log info "Restore type=INCREMENTAL → datadisk only (qcow2/raw/rbdiff on ${BACKUP_REPO_ADDRESS:-/data/backup}, no NAS)"
    if [[ "$restore_source" != "mold-only" ]]; then
      mold_backup_veeam_restore_chain_to_host "$BACKUP_ID"
    fi
    mold_backup_api_restore || return 1
  fi

  # Mold → Veeam Guest Files (FLR): when Mold restored (not reflecting an existing FLR).
  if [[ -n "${VM_NAME:-}" ]] \
    && [[ "${VEEAM_TRIGGER_FLR_ON_MOLD_RESTORE:-true}" == "true" ]] \
    && ! mold_backup_trigger_active "veeam-restore-active" "$VM_NAME"; then
    mold_backup_trigger_veeam_flr "$VM_NAME" "${VEEAM_RESTORE_POINT_ID:-}" || \
      mold_backup_notify_log warn "Mold→Veeam FLR trigger failed for ${VM_NAME} (Mold restore already done)"
  fi

  mold_backup_notify_log info "=== restore-notify done ==="
}

# Start Veeam Guest Files (FLR) for the restore point mapped to this VM/backup.
# Used after Mold-initiated restore so Veeam UI FLR runs automatically (bidirectional).
mold_backup_trigger_veeam_flr() {
  local vm="$1" rp_id="${2:-}" dest job_esc rp_esc dest_esc ps_script
  [[ -n "$vm" ]] || return 1
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "Mold→Veeam FLR: VEEAM_SSH_HOST not set"
    return 1
  }
  if [[ -z "$rp_id" ]]; then
    rp_id="$(tr -d '[:space:]' <"$(mold_backup_registry_dir)/${vm}.latest-rp-id" 2>/dev/null || true)"
  fi
  if [[ -z "$rp_id" && -n "${BACKUP_ID:-}" ]]; then
    rp_id="$(grep -F "${BACKUP_ID}" "$(mold_backup_registry_rp_map_file)" 2>/dev/null | head -1 | cut -d= -f1 || true)"
  fi
  [[ -n "$rp_id" ]] || {
    mold_backup_notify_log warn "Mold→Veeam FLR: no restore point id for ${vm} (registry ${vm}.latest-rp-id)"
    return 1
  }
  dest="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}/${vm}"
  job_esc="${VEEAM_JOB_NAME:-}"
  job_esc="${job_esc//\'/\'\'}"
  rp_esc="${rp_id//\'/\'\'}"
  dest_esc="${dest//\'/\'\'}"
  mold_backup_notify_log info "Mold→Veeam FLR: vm=${vm} rp=${rp_id} dest=${dest}"
  mold_backup_trigger_mark "mold-flr-pushed" "$vm"
  mold_backup_local_flr_mark_epoch "$vm" "$(date +%s)"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'Stop'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
\$rp = Get-VBRRestorePoint | Where-Object { \$_.Id -eq '${rp_esc}' -or \$_.Id.Guid -eq '${rp_esc}' } | Select-Object -First 1
if (-not \$rp) { Write-Error "restore point not found: ${rp_esc}"; exit 2 }
\$session = \$null
try {
  \$session = Start-VBRFLRSession -RestorePoint \$rp
  \$items = @(Get-VBRFLRItem -Session \$session -ErrorAction SilentlyContinue)
  \$copied = 0
  foreach (\$item in \$items) {
    if (\$item.Type -eq 'HardDisk' -or \$item.Type -eq 'Directory' -or \$item.Type -eq 'File') {
      try {
        Copy-VBRFLRItem -FLRSession \$session -Item \$item -Destination '${dest_esc}' -ErrorAction SilentlyContinue | Out-Null
        \$copied++
      } catch {}
    }
  }
  Write-Host ("flr-ok rp=${rp_esc} items=" + \$items.Count + " copied=" + \$copied)
} finally {
  if (\$session) { try { Stop-VBRFLRSession -Session \$session } catch {} }
}
PS
)"
  if mold_backup_veeam_ssh_ps "$ps_script"; then
    mold_backup_emit_restore_event "veeam.flr.triggered" "$vm" "rp=${rp_id};dest=${dest}"
    mold_backup_notify_log info "Mold→Veeam FLR: started OK for ${vm}"
    return 0
  fi
  mold_backup_notify_log warn "Mold→Veeam FLR: SSH/PowerShell failed for ${vm}"
  return 1
}

# ---------------------------------------------------------------------------
# Veeam Remove-from-Disk → Mold catalog sync (NetBackup parity)
# Detect missing RPs for VM_INCLUDE, then call syncAblestackVeeamBackups
# (MS deletes Mold rows + artifacts). Mold UI/API deleteBackup is disabled.
# ---------------------------------------------------------------------------

# List live restore points for a computer/host Agent job.
# Prints: rpId|unixEpoch  (one per line)
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

# Prints HAS_BACKUP | NO_BACKUP (or nothing on probe failure).
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

# True when Veeam still has a Disk backup object for this job/host name.
mold_backup_veeam_job_disk_backup_exists() {
  [[ "$(mold_backup_veeam_job_disk_backup_status "$1")" == "HAS_BACKUP" ]]
}

# REST fallback: objectRestorePoints filtered by job/host name.
# Exit 0 on successful API response (including zero RPs). Exit 1 on auth/HTTP/parse failure.
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

# True if Veeam still has this restore point GUID.
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

# Trigger MS catalog sync for one VM (deleteMoldBackupsMissingFromVeeamCatalog).
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

# Detect Veeam Remove-from-Disk for VM_INCLUDE, then trigger MS syncAblestackVeeamBackups
# (NetBackup-style catalog delete). Never calls deleteBackup from the host.
mold_backup_catalog_delete_sync() {
  local job="${1:-${VEEAM_JOB_NAME:-}}"
  local enable="${VEEAM_CATALOG_DELETE_SYNC:-true}"
  local state_dir state_f live_file prev_file live_ids deleted_id vm bid epoch
  local -a vms=()
  local -A sync_vms=()
  [[ "$enable" == "true" ]] || {
    mold_backup_notify_log info "catalog-sync: disabled (VEEAM_CATALOG_DELETE_SYNC=false)"
    return 0
  }
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
