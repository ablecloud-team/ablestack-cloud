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
CVT_BACKUP_SCRIPT="${CVT_BACKUP_SCRIPT:-/etc/ablestack/veeam/ablestack_cvtbackup.sh}"
VEEAM_PROVIDER_NAME="${VEEAM_PROVIDER_NAME:-ablestack-veeam}"

# Resolve config: MOLD_BACKUP_CONF, or /etc/ablestack/veeam/<job>.conf, or mold-backup.conf
mold_backup_resolve_conf_path() {
  if [[ -n "${MOLD_BACKUP_CONF:-}" && -f "$MOLD_BACKUP_CONF" ]]; then
    echo "$MOLD_BACKUP_CONF"
    return 0
  fi
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
  local _preserve_vm_include="${VM_INCLUDE:-}"
  resolved="$(mold_backup_resolve_conf_path "${VEEAM_JOB_NAME:-}")" || {
    echo "Config not found — run veeam_config.sh or install.sh" >&2
    return 1
  }
  MOLD_BACKUP_CONF="$resolved"
  # shellcheck source=/dev/null
  source "$MOLD_BACKUP_CONF"
  # Caller-provided selectors must survive sourcing the job conf (which may define them).
  [[ -n "${_preserve_backup_id}" ]] && BACKUP_ID="${_preserve_backup_id}"
  [[ -n "${_preserve_restore_source}" ]] && RESTORE_SOURCE="${_preserve_restore_source}"
  [[ -n "${_preserve_vm_name}" ]] && VM_NAME="${_preserve_vm_name}"
  [[ -n "${_preserve_vm_uuid}" ]] && VM_UUID="${_preserve_vm_uuid}"
  [[ -n "${_preserve_vm_include}" && "${_preserve_vm_include}" != "*" ]] && VM_INCLUDE="${_preserve_vm_include}"

  LOG_FILE="${LOG_FILE:-/var/log/mold/backup-veeam.log}"
  LOG_TAG="${LOG_TAG:-mold-veeam-backup}"
  NAS_BACKUP_SCRIPT="${NAS_BACKUP_SCRIPT:-/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/ablestack_nasbackup.sh}"
  IMPORT_MODE="${IMPORT_MODE:-auto}"
  BACKUP_MODE="${BACKUP_MODE:-host}"
  VM_INCLUDE="${VM_INCLUDE:-*}"
  VM_EXCLUDE="${VM_EXCLUDE:-}"
  ZONE_ID="${ZONE_ID:-}"
  RETENTION_PERIOD="${RETENTION_PERIOD:-}"
  VEEAM_URL="${VEEAM_URL:-}"
  VEEAM_USERNAME="${VEEAM_USERNAME:-}"
  VEEAM_PASSWORD="${VEEAM_PASSWORD:-}"
  VEEAM_HOST_BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  STAGING_PATH="${STAGING_PATH:-${VEEAM_HOST_BACKUP_PATH}}"
  NAS_REPO_MOUNT="${NAS_REPO_MOUNT:-}"
  SOURCE_DISK_FORMAT="${SOURCE_DISK_FORMAT:-vmdk}"
  BOOTSTRAP_CHECKPOINT="${BOOTSTRAP_CHECKPOINT:-true}"
  QUIESCE_VM="${QUIESCE_VM:-false}"
  BACKUP_OPERATION="${BACKUP_OPERATION:-seed-import}"
  CLEANUP_STAGING_AFTER_BACKUP="${CLEANUP_STAGING_AFTER_BACKUP:-true}"
  CLEANUP_STAGING_ON_ERROR="${CLEANUP_STAGING_ON_ERROR:-true}"
  BACKUP_OFFERING_NAME="${BACKUP_OFFERING_NAME:-VeeamBackup}"
  BACKUP_REPO_TYPE="${BACKUP_REPO_TYPE:-local}"
  BACKUP_REPO_NAME="${BACKUP_REPO_NAME:-Ablestack Data Disk}"
  BACKUP_REPO_PROVIDER="${BACKUP_REPO_PROVIDER:-localfs}"
  MOLD_DATADISK_PATH="${MOLD_DATADISK_PATH:-}"
  VEEAM_HOST_REPO_ROOT="${VEEAM_HOST_REPO_ROOT:-E:/opt1/veeam}"
  VEEAM_REPO_NAME="${VEEAM_REPO_NAME:-}"
  BACKUP_STORAGE_MODE="${BACKUP_STORAGE_MODE:-datadisk}"
  BACKUP_STORAGE_ENGINE="${BACKUP_STORAGE_ENGINE:-auto}"
  # Mold→Veeam trigger (bidirectional mode C): start the matching Veeam Agent job
  # over SSH after a Mold backup completes. Loop is broken by veeam-active/mold-active markers.
  VEEAM_TRIGGER_ENABLED="${VEEAM_TRIGGER_ENABLED:-false}"
  VEEAM_TRIGGER_TTL="${VEEAM_TRIGGER_TTL:-1800}"
  VEEAM_SSH_HOST="${VEEAM_SSH_HOST:-}"
  VEEAM_SSH_USER="${VEEAM_SSH_USER:-administrator}"
  VEEAM_SSH_KEY="${VEEAM_SSH_KEY:-}"
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
  VEEAM_HOST_REPO_ROOT="${VEEAM_HOST_REPO_ROOT:-E:/opt1/veeam}"
  [[ -z "${VEEAM_REPO_NAME:-}" ]] && VEEAM_REPO_NAME="Mold ${KVM_HOSTNAME}"
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

mold_backup_veeam_host_repo_folder() {
  local host="${KVM_HOSTNAME:-$(hostname -s)}"
  local root="${VEEAM_HOST_REPO_ROOT:-E:/opt1/veeam}"
  echo "${root%/}/${host}"
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
  local csv="$1"
  [[ "$csv" == rbd:* ]] && return 0
  [[ "$csv" == *",rbd:"* ]] && return 0
  return 1
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
    || mold_backup_die "ablestack_nasbackup.sh ${op} failed"
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
    || mold_backup_die "ablestack_nasbackup.sh import-veeam-seed failed"
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
  local backup_name args
  backup_name="$(mold_backup_api_build_backup_name_for_vm "${VM_UUID}" "${VM_NAME:-}")"
  args=("virtualmachineid=${VM_UUID}" "name=${backup_name}")
  [[ "${QUIESCE_VM}" == "true" ]] && args+=("quiescevm=true")
  mold_backup_cmk_run createAblestackVeeamBackup "${args[@]}" \
    || mold_backup_cmk_run createBackup "${args[@]}"
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

mold_backup_vm_in_filter() {
  local vm_name="$1"
  local include="${VM_INCLUDE:-*}"
  local exclude="${VM_EXCLUDE:-}"
  local token

  if [[ -n "$exclude" ]]; then
    IFS=',' read -ra _ex <<< "$exclude"
    for token in "${_ex[@]}"; do
      token="$(echo "$token" | xargs)"
      [[ -z "$token" ]] && continue
      [[ "$vm_name" == "$token" ]] && return 1
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
  mold_backup_api_update_config_if_needed "backup.framework.enabled" "true"
  mold_backup_api_update_config_if_needed "backup.enable.attach.detach.of.volumes" "true"
  mold_backup_api_update_config_if_needed "backup.framework.provider.plugin" "${VEEAM_PROVIDER_NAME}"
  [[ -n "${VEEAM_URL:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.url" "${VEEAM_URL}"
  [[ -n "${VEEAM_USERNAME:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.username" "${VEEAM_USERNAME}"
  [[ -n "${VEEAM_PASSWORD:-}" ]] && mold_backup_api_update_config_if_needed "backup.plugin.ablestack-veeam.password" "${VEEAM_PASSWORD}"
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

# Ensure backup repository + offering exist (NAS/guest). Datadisk host mode uses KVM
# /data/backup only — assign backup offering in Mold UI; no addBackupRepository.
mold_backup_api_ensure_backup_resources() {
  local offering_id repo_id
  if mold_backup_is_datadisk_mode; then
    offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" "$(mold_backup_offering_name)" 2>/dev/null || true)"
    [[ -n "$offering_id" ]] || offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
    if [[ -n "$offering_id" ]]; then
      echo "$offering_id"
      return 0
    fi
    mold_backup_notify_log warn "datadisk mode: assign backup offering '$(mold_backup_offering_name)' (${VEEAM_PROVIDER_NAME}) in Mold UI — Mold backup repository is not auto-created"
    return 1
  fi
  repo_id="$(mold_backup_api_ensure_repository 2>/dev/null || true)"
  if [[ -n "$repo_id" ]]; then
    BACKUP_REPOSITORY_UUID="$repo_id"
    OFFERING_EXTERNAL_ID="${OFFERING_EXTERNAL_ID:-$repo_id}"
  fi
  offering_id="$(mold_backup_api_ensure_offering 2>/dev/null || true)"
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
  mold_backup_notify_log err "KVM mold-agent does not handle AblestackNasImportVeeamSeedCommand (import-veeam-seed). Update mold-agent to a build that includes LibvirtAblestackNasImportVeeamSeedCommandWrapper, ensure ablestack_nasbackup.sh supports -o import-veeam-seed, then: systemctl restart cloudstack-agent"
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
  echo "$json" | python3 -c "
import json, sys
provider = (sys.argv[1] or '').lower()
name = sys.argv[2] if len(sys.argv) > 2 else ''
aliases = {provider}
if provider in ('ablestack-veeam', 'veeam'):
    aliases.update(['ablestack-veeam', 'veeam'])
if provider == 'ablestack-nas':
    aliases.update(['ablestack-nas', 'nas'])
try:
    d = json.load(sys.stdin)
    offs = d.get('listbackupofferingsresponse', {}).get('backupoffering', [])
    if isinstance(offs, dict): offs = [offs]
    if name:
        for o in offs:
            if o.get('name') == name:
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
  local offering_id json err want_ext ext_id
  offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" 2>/dev/null || true)"
  want_ext="${OFFERING_EXTERNAL_ID:-${BACKUP_REPOSITORY_UUID:-}}"
  if [[ -n "$offering_id" && -n "$want_ext" ]]; then
    ext_id="$(mold_backup_api_get_offering_external_id "$offering_id" 2>/dev/null || true)"
    if [[ -n "$ext_id" && "$ext_id" != "$want_ext" ]]; then
      mold_backup_notify_log warn "Backup offering id=${offering_id} externalid=${ext_id} != repository ${want_ext}"
      mold_backup_notify_log warn "Delete '$(mold_backup_offering_name)' in Mold UI (Infrastructure → Backup Offerings) then re-run: mold_backup_api_ensure_backup_resources"
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
  local ext_id="${OFFERING_EXTERNAL_ID:-${BACKUP_REPOSITORY_UUID:-}}"
  if [[ -z "$ext_id" ]]; then
    ext_id="$(mold_backup_api_find_backup_repository_uuid 2>/dev/null || true)"
  fi
  [[ -n "$ext_id" ]] || {
    mold_backup_notify_log err "No backup repository UUID for importBackupOffering — create Backup Repository in Mold UI or set BACKUP_REPOSITORY_UUID"
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
  if ! json=$(mold_backup_cmk_run importBackupOffering "${args[@]}" 2>&1); then
    err="$(mold_backup_api_extract_error "$json" 2>/dev/null || true)"
    mold_backup_notify_log err "importBackupOffering failed${err:+: ${err}}"
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

mold_backup_api_create_veeam_and_wait() {
  local vm_id="$1" vm_label="${2:-}"
  local json job_id backup_id backup_type backup_name
  backup_name="$(mold_backup_api_build_backup_name_for_vm "$vm_id" "$vm_label")"
  mold_backup_notify_log info "createAblestackVeeamBackup vm=${vm_id} name=${backup_name} (MS→agent NAS backup)"
  json=$(mold_backup_cmk_run createAblestackVeeamBackup "virtualmachineid=${vm_id}" "name=${backup_name}" 2>/dev/null) \
    || json=$(mold_backup_cmk_run createBackup "virtualmachineid=${vm_id}" "name=${backup_name}" 2>/dev/null) \
    || return 1
  job_id="$(mold_backup_api_json_field "$json" "createablestackveeambackupresponse.jobid")"
  [[ -z "$job_id" ]] && job_id="$(mold_backup_api_json_field "$json" "createbackupresponse.jobid")"
  if [[ -n "$job_id" ]]; then
    mold_backup_notify_log info "createAblestackVeeamBackup job=${job_id}; waiting for MS/NAS backup"
    json=$(mold_backup_api_wait_async_job "$job_id" 1200) || return 1
    backup_id="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.id")"
    backup_type="$(mold_backup_api_json_field "$json" "queryasyncjobresultresponse.jobresult.backup.type")"
    if [[ -z "$backup_id" ]]; then
      local latest
      latest="$(mold_backup_api_find_latest_backed_up_backup_for_vm "$vm_id" 2>/dev/null || true)"
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
  max_chain="${VEEAM_MAX_CHAIN:-7}"
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
mold_backup_api_find_latest_backed_up_backup_for_vm() {
  local vm_id="$1" json
  json=$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null) || return 1
  printf '%s\n' "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict):
        b = [b]
    backed = [x for x in b if str(x.get('status', '')).lower() == 'backedup']
    if not backed:
        sys.exit(1)
    latest = max(backed, key=lambda x: x.get('created') or '')
    bid = latest.get('id', '')
    btype = latest.get('type', 'User')
    if not bid:
        sys.exit(1)
    print(f\"{bid}|{btype}\")
except Exception:
    sys.exit(1)
" 2>/dev/null
}

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
  done < <(find "$host_dir" -maxdepth 1 -type f \( -name 'disk-*' -o -name '*.qcow2' -o -name '*.qcow' -o -name '*.vmdk' -o -name '*.raw' \) -print0 2>/dev/null)
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

mold_backup_registry_get_backup_id_by_checkpoint() {
  local vm="$1" ckpt="$2" reg_dir line bid
  [[ -n "$vm" && -n "$ckpt" ]] || return 1
  reg_dir="$(mold_backup_registry_dir)"
  line="$(grep -hE "vm=${vm}.*backup_id=.*${ckpt}" "${reg_dir}"/*.log 2>/dev/null | tail -1 || true)"
  bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
  [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  line="$(grep "backup_id=.*${vm}.*${ckpt}\|${vm}/${ckpt}" /var/log/mold/veeam-hook.log 2>/dev/null | tail -1 || true)"
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

mold_backup_registry_get_backup_id_by_checkpoint() {
  local vm="$1" ckpt="$2" reg_dir line bid
  [[ -n "$vm" && -n "$ckpt" ]] || return 1
  reg_dir="$(mold_backup_registry_dir)"
  line="$(grep -hE "vm=${vm}.*backup_id=.*${ckpt}|backup_id=.*vm=${vm}.*${ckpt}" "${reg_dir}"/*.log 2>/dev/null | tail -1 || true)"
  bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
  [[ -n "$bid" ]] && { echo "$bid"; return 0; }
  line="$(grep -rh "path=.*${vm}/${ckpt}\|${vm}/${ckpt}" /var/log/mold/veeam-hook.log 2>/dev/null | grep backup_id | tail -1 || true)"
  bid="$(sed -n 's/.*backup_id=\([^ ]*\).*/\1/p' <<<"$line" | tail -1)"
  [[ -n "$bid" ]] && echo "$bid"
}

# Keep mold-backup.windows.conf VM_BACKUP_IDS in sync for Veeam restore-watch.
mold_backup_sync_vm_backup_ids_conf() {
  local vm="$1" backup_id="$2"
  local win_conf="${ABLESTACK_VEEAM_ETC_DIR}/mold-backup.windows.conf"
  local existing entry new_val
  [[ -n "$vm" && -n "$backup_id" && -f "$win_conf" ]] || return 0
  entry="${vm}:${backup_id}"
  existing="$(grep -E '^VM_BACKUP_IDS=' "$win_conf" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  if [[ -z "$existing" ]]; then
    echo "VM_BACKUP_IDS=\"${entry}\"" >> "$win_conf"
    return 0
  fi
  new_val="$existing"
  if [[ "$existing" == *"${vm}:"* ]]; then
    new_val="$(echo "$existing" | sed -E "s#${vm}:[^,;]*#${vm}:${backup_id}#g")"
  else
    new_val="${existing},${entry}"
  fi
  if grep -qE '^VM_BACKUP_IDS=' "$win_conf" 2>/dev/null; then
    sed -i "s#^VM_BACKUP_IDS=.*#VM_BACKUP_IDS=\"${new_val}\"#" "$win_conf"
  else
    echo "VM_BACKUP_IDS=\"${new_val}\"" >> "$win_conf"
  fi
}

mold_backup_registry_save_backup() {
  local job="$1" vm_name="$2" backup_id="$3" status="${4:-success}" rp_id="${5:-}"
  local reg_dir reg_file
  reg_dir="$(mold_backup_registry_dir)"
  mkdir -p "$reg_dir"
  reg_file="${reg_dir}/$(mold_backup_safe_job_name "$job").log"
  echo "$(date -Iseconds) job=${job} vm=${vm_name} backup_id=${backup_id} status=${status}" >> "$reg_file"
  mold_backup_registry_set_vm_backup_id "$vm_name" "$backup_id" "$rp_id" "$job"
  mold_backup_notify_log info "Saved backup registry: vm=${vm_name} backup_id=${backup_id} status=${status}"
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
  if [[ -z "$offering_id" && -n "$vm_offering" ]]; then
    offering_id="$vm_offering"
    mold_backup_notify_log info "Using VM-assigned backup offering id=${offering_id}"
  fi
  if [[ -n "$offering_id" ]]; then
    mold_backup_api_assign_offering_if_needed "$vm_id" "$offering_id"
    json=$(mold_backup_cmk_run listVirtualMachines "id=${vm_id}" 2>/dev/null || true)
    vm_offering="$(mold_backup_api_json_field "$json" "listvirtualmachinesresponse.virtualmachine.backupofferingid")"
  fi
  [[ -n "$vm_offering" ]] || {
    mold_backup_notify_log err "VM ${vm_name} has no backup offering (assign '$(mold_backup_offering_name)' / ${VEEAM_PROVIDER_NAME} in Mold UI)"
    mold_backup_state_write_line "$state_file" "vm=${vm_name} id=${vm_id} status=fail reason=no-offering"
    return 1
  }

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

  if [[ "${VEEAM_BACKUP_MODE:-}" == "filelevel" && "${chain_count:-0}" -eq 0 ]]; then
    mold_backup_notify_log info "First file-level backup: host export → importAblestackVeeamBackupSeed (skip MS→Veeam on seed)"
    if ! host_path=$(mold_backup_run_host_export "$vm_name" "1" 2>/dev/null); then
      mold_backup_notify_log err "Host export failed for ${vm_name} (seed bootstrap; check /var/log/mold/veeam-hook.log and cvtbackup output)"
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
    mold_backup_notify_log info "Pre-notify OK vm=${vm_name} backup_id=${backup_id} type=${backup_type} path=${host_path} (seed import)"
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
  local backup_id="$1"
  local chain rp_ids rp_id script_dir ps1
  chain="$(mold_backup_api_build_restore_chain "$backup_id")" || return 1
  rp_ids=""
  local bid
  for bid in ${chain//,/ }; do
    [[ -z "$bid" ]] && continue
    rp_id="$(mold_backup_api_backup_detail_field "$bid" "ablestack.veeam.restore.point.id" 2>/dev/null || true)"
    [[ -n "$rp_id" ]] && rp_ids="${rp_ids},${rp_id}"
  done
  rp_ids="${rp_ids#,}"
  [[ -n "$rp_ids" ]] || {
    mold_backup_notify_log warn "No Veeam restore point ids in chain; datadisk restore only"
    return 0
  }
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "VEEAM_SSH_HOST not set; skip external Veeam chain restore"
    return 0
  }
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  ps1="${script_dir}/veeam-restore-chain.ps1"
  [[ -f "$ps1" ]] || ps1="/usr/share/mold/backup/veeam/veeam-restore-chain.ps1"
  [[ -f "$ps1" ]] || {
    mold_backup_notify_log warn "veeam-restore-chain.ps1 not found; datadisk restore only (no Veeam chain export)"
    return 0
  }
  mold_backup_notify_log info "Veeam chain restore RPs=${rp_ids} → ${VEEAM_HOST_BACKUP_PATH}"
  ssh -i "${VEEAM_SSH_KEY:-/root/.ssh/id_rsa}" -o StrictHostKeyChecking=no \
    "${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}" \
    "powershell -ExecutionPolicy Bypass -File '${ps1}' -RestorePointIds '${rp_ids}' -DestinationPath '${VEEAM_HOST_BACKUP_PATH}'" \
    || mold_backup_notify_log warn "Veeam chain restore script failed (continuing Mold restore)"
}

mold_backup_get_domain_disk_paths() {
  local vm_name="$1"
  VM_NAME="$vm_name"
  mold_backup_get_all_disk_paths
}

mold_backup_run_host_export() {
  local vm_name="$1"
  local backup_subdir checkpoint disk_paths backup_files parent_dir parent_ckpt parent_ckpt_path backup_type op
  [[ -x "${CVT_BACKUP_SCRIPT}" ]] || {
    mold_backup_notify_log err "Host backup script not found: ${CVT_BACKUP_SCRIPT} (run veeam/install.sh on this KVM host)"
    return 1
  }

  backup_subdir="${VEEAM_HOST_BACKUP_PATH}/${vm_name}/$(date '+%Y.%m.%d.%H.%M.%S.%3N')"
  checkpoint="$(basename "$backup_subdir")"
  mkdir -p "${VEEAM_HOST_BACKUP_PATH}/${vm_name}"
  disk_paths=$(mold_backup_get_domain_disk_paths "$vm_name")
  parent_dir=""
  parent_ckpt=""
  parent_ckpt_path=""
  backup_type="FULL"
  op="backup-running"
  backup_files=$(mold_backup_build_backup_files "$disk_paths" "$backup_type")

  if mold_backup_has_rbd_disk "$disk_paths"; then
    op="backup-rbd"
    mold_backup_notify_log info "Host export storage engine=rbd (HCI)"
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
  local cvt_log
  cvt_log="$(mktemp "${TMPDIR:-/tmp}/mold-cvt.XXXXXX")"
  if ! "${CVT_BACKUP_SCRIPT}" \
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
    >"$cvt_log" 2>&1; then
    mold_backup_notify_log err "Host export cvtbackup failed: $(tail -5 "$cvt_log" | tr '\n' ' ')"
    rm -f "$cvt_log"
    return 1
  fi
  rm -f "$cvt_log"
  echo "${backup_subdir}"
}

mold_backup_cleanup_host_path() {
  [[ -d "${VEEAM_HOST_BACKUP_PATH}" ]] || return 0
  mold_backup_notify_log info "Cleaning host backup path: ${VEEAM_HOST_BACKUP_PATH}"
  find "${VEEAM_HOST_BACKUP_PATH}" -mindepth 1 -maxdepth 4 -type f -delete 2>/dev/null || true
  find "${VEEAM_HOST_BACKUP_PATH}" -mindepth 1 -maxdepth 3 -type d -empty -delete 2>/dev/null || true
}

# --- Bidirectional Mold<->Veeam trigger loop guard ---
# Two short-lived markers under state/triggers/ break the trigger loop:
#   veeam-active-<vm> : set by pre_notify before requesting the Mold backup.
#                       The Mold->Veeam hook skips Start-VBRJob while present.
#   mold-active-<vm>  : set by the Mold->Veeam hook before Start-VBRJob.
#                       pre_notify skips createBackup while present (Veeam disk-only).
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

# Run a short PowerShell script on Veeam via SSH (UTF-16LE EncodedCommand).
mold_backup_veeam_ssh_ps() {
  local ps_script="$1"
  local ps_enc
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || return 1
  ps_enc="$(printf '%s' "$ps_script" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null | base64 -w0 2>/dev/null)"
  [[ -n "$ps_enc" ]] || return 1
  mold_backup_veeam_ssh_encoded "$ps_enc" >/dev/null
}

# Start a Veeam job over SSH (PowerShell) — fallback when REST cannot manage agent jobs.
mold_backup_trigger_veeam_job_ssh() {
  local vm="$1" job="$2" job_esc ps_script
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || {
    mold_backup_notify_log warn "Mold→Veeam(SSH): VEEAM_SSH_HOST not set"
    return 1
  }
  job_esc="${job//\'/\'\'}"
  ps_script="$(cat <<PS
\$ErrorActionPreference = 'Stop'
Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue
\$j = Get-VBRComputerBackupJob -Name '${job_esc}'
if (-not \$j) { Write-Error "job not found: ${job_esc}"; exit 2 }
if (\$j.IsRunning) { Write-Host 'already running'; exit 0 }
Start-VBRComputerBackupJob -Job \$j -RunAsync | Out-Null
Write-Host 'started'
PS
)"
  if mold_backup_veeam_ssh_ps "$ps_script"; then
    mold_backup_notify_log info "Mold→Veeam(SSH): job '${job}' start requested OK"
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
  param([string]\$Sid, [string]\$Ip, [long]\$Epoch, [string]\$Result, [string]\$Name, [string]\$Backup, [string]\$RpId)
  if (-not \$Sid) { return }
  if (\$emitted.ContainsKey(\$Sid)) { return }
  \$emitted[\$Sid] = \$true
  \$n = (\$Name -replace '[\|\r\n]',' ')
  \$b = (\$Backup -replace '[\|\r\n]',' ')
  \$r = (\$Result -replace '[\|\r\n]',' ')
  \$rp = (\$RpId -replace '[\|\r\n]','').Trim()
  "\$Sid|\$Ip|\$Epoch|\$r|\$n|\$b|\$rp"
}
function Get-RpFromSession {
  param(\$Session)
  \$rpId = ''
  try {
    if (\$null -ne \$Session.RestorePoint) {
      \$x = \$Session.RestorePoint.Id
      if (\$null -ne \$x) { if (\$x -is [guid]) { \$rpId = \$x.Guid } else { \$rpId = [string]\$x } }
    }
  } catch {}
  if (-not \$rpId) {
    \$opt = [string]\$Session.Options
    foreach (\$pat in @('RestorePointId="([^"]+)"','ObjectRestorePointId="([^"]+)"','PointId="([^"]+)"')) {
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
  return \$rpId
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
  \$hasEnd = (\$null -ne \$r.EndTime -or \$null -ne \$r.EndTimeUTC)
  \$done = \$false
  try { \$done = [bool]\$r.IsCompleted } catch { \$done = \$hasEnd }
  if (-not \$done -and -not \$hasEnd) { continue }
  if (-not \$hasEnd -and \$rs -match '^(?i)(Failed|None)\$') { continue }
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
  \$rpId = Get-RpFromSession -Session \$r
  \$nm = [string]\$r.Name
  \$epoch = 0
  \$etObj = if (\$null -ne \$r.EndTime) { \$r.EndTime } else { \$r.EndTimeUTC }
  try { if (\$null -ne \$etObj) { \$epoch = [int64]([DateTimeOffset]\$etObj).ToUnixTimeSeconds() } } catch { \$epoch = 0 }
  if (\$epoch -eq 0) {
    try { if (\$null -ne \$r.CreationTimeUTC) { \$epoch = [int64]([DateTimeOffset]\$r.CreationTimeUTC).ToUnixTimeSeconds() } } catch {}
  }
  \$sid = [string]\$r.Id
  if (\$r.Id -is [guid]) { \$sid = \$r.Id.Guid }
  Emit-RestoreLine -Sid \$sid -Ip \$ip -Epoch \$epoch -Result \$rs -Name \$nm -Backup \$bn -RpId \$rpId
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
        \$rsess = \$null
        try { \$rsess = Get-VBRRestoreSession -Id \$sid -ErrorAction SilentlyContinue } catch {}
        if (\$rsess) { \$rpId = Get-RpFromSession -Session \$rsess }
        if (-not \$rpId) {
          \$m = [regex]::Match(\$det, "restorePointId='([^']+)'")
          if (\$m.Success) { \$rpId = \$m.Groups[1].Value }
        }
        Emit-RestoreLine -Sid \$sid -Ip \$ip -Epoch \$epoch -Result ([string]\$_.Result) -Name \$nm -Backup '' -RpId \$rpId
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
  local ps_enc
  ps_enc="$(printf '%s' "$ps_script" | iconv -f UTF-8 -t UTF-16LE 2>/dev/null | base64 -w0 2>/dev/null)"
  [[ -n "$ps_enc" ]] || { mold_backup_notify_log warn "Veeam→Mold(restore): failed to encode PS script"; return 1; }
  # Veeam.Backup.PowerShell needs PS 7+: full-path pwsh first (SSH often only has
  # Windows PowerShell 5.1 "powershell", which cannot Import-Module Veeam).
  # Also retry SSH itself: ARP/L2 to the Veeam host intermittently flaps.
  local attempt out rc ps_launcher
  local -a ps_launchers=(
    '"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -EncodedCommand'
    'pwsh.exe -NoProfile -EncodedCommand'
    'pwsh -NoProfile -EncodedCommand'
  )
  for attempt in 1 2 3; do
    for ps_launcher in "${ps_launchers[@]}"; do
      out="$(ssh "${ssh_key_opt[@]}" -o BatchMode=yes -o ConnectTimeout=30 "${ssh_host_opts[@]}" \
              "${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}" \
              "${ps_launcher} ${ps_enc}" 2>/dev/null)"
      rc=$?
      if [[ $rc -eq 0 ]]; then
        # PowerShell emits CRLF; strip CR so it doesn't end up in the last field.
        out="$(printf '%s' "$out" | tr -d '\r')"
        if [[ -z "${out//[[:space:]]/}" ]]; then
          mold_backup_notify_log info "Veeam→Mold(restore): SSH ok (pwsh), 0 sessions from Get-VBRRestoreSession/audit (window=${since_min}min)"
        else
          local _raw_n
          _raw_n="$(printf '%s\n' "$out" | grep -c '|' 2>/dev/null || echo 0)"
          mold_backup_notify_log info "Veeam→Mold(restore): raw session lines=${_raw_n}"
        fi
        # Apply the time window here (3rd field = Unix epoch seconds). Lines without a
        # numeric epoch are kept (fail-open) so we never silently drop real sessions.
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
    done
    mold_backup_notify_log warn "Veeam→Mold(restore): SSH/pwsh query attempt ${attempt} failed (rc=${rc}); retrying"
    sleep 3
  done
  mold_backup_notify_log warn "Veeam→Mold(restore): SSH query failed after retries (need pwsh 7+ path)"
  return 1
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
      mold_backup_notify_log info "restore preflight: ${vm} libvirt=running (stop VM in Mold UI before restore)"
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
  [[ "${BACKUP_MODE:-host}" == "host" ]] || return 0
  [[ -n "${VM_INCLUDE:-}" && "${VM_INCLUDE}" != "*" ]] || return 0
  local base="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  local cutoff now vm d epoch newest last state_f sid kvm_ip ckpt rp_id
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
    mold_backup_trigger_active "mold-restore-active" "$vm" && continue
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
  if [[ -n "${KVM_HOSTNAME:-}" ]]; then
    echo "$KVM_HOSTNAME"
    return 0
  fi
  local agent_props="/etc/cloudstack/agent/agent.properties" h
  if [[ -f "$agent_props" ]]; then
    h="$(grep -E '^host\.name=' "$agent_props" 2>/dev/null | tail -1 | cut -d= -f2-)"
    h="${h//$'\r'/}"
    [[ -n "$h" ]] && { echo "$h"; return 0; }
    h="$(grep -E '^resource=' "$agent_props" 2>/dev/null | tail -1 | cut -d= -f2-)"
    h="${h//$'\r'/}"
    [[ -n "$h" ]] && { echo "$h"; return 0; }
    h="$(grep -E '^host=' "$agent_props" 2>/dev/null | tail -1 | cut -d= -f2-)"
    h="${h//$'\r'/}"
    # host= is often MS resource id (e.g. 10.10.31.20@static), not hypervisor name — skip @ forms
    if [[ -n "$h" && "$h" != *@* ]]; then
      echo "$h"
      return 0
    fi
  fi
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

mold_backup_resolve_backup_id_for_vm() {
  local vm_name="$1" job="${2:-${VEEAM_JOB_NAME:-}}" rp_id="${3:-}" ckpt="${4:-}"
  local line backup_id vm_id json
  if [[ -n "$ckpt" ]]; then
    backup_id="$(mold_backup_registry_get_backup_id_by_checkpoint "$vm_name" "$ckpt" 2>/dev/null || true)"
    if [[ -n "$backup_id" ]]; then
      mold_backup_notify_log info "restore-watch: vm=${vm_name} ckpt=${ckpt} → backup_id=${backup_id} (registry checkpoint)"
      echo "$backup_id"
      return 0
    fi
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
    mold_backup_notify_log warn "restore-watch: no Mold backup for Veeam restore point ${rp_id} vm=${vm_name}; using latest"
  fi
  if [[ -n "${BACKUP_ID:-}" ]]; then
    echo "$BACKUP_ID"
    return 0
  fi
  backup_id="$(mold_backup_registry_get_vm_backup_id "$vm_name" 2>/dev/null || true)"
  [[ -n "$backup_id" ]] && { echo "$backup_id"; return 0; }
  local reg_dir
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
  if [[ -z "$rp_id" && "$detail" == *"rp="* ]]; then
    rp_id="$(sed -n 's/.*rp=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
    rp_id="${rp_id// /}"
  fi
  if [[ -z "$rp_id" && "$detail" == *"end="* ]]; then
    local _ep
    _ep="$(sed -n 's/.*end=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
    if [[ "$_ep" =~ ^[0-9]+$ ]]; then
      rp_id="$(mold_backup_query_veeam_rp_near_epoch "$job" "$_ep" 2>/dev/null || true)"
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
  local backup_id rc=0 ckpt=""
  ckpt="$(sed -n 's/.*backup=\([^;]*\).*/\1/p' <<<"$detail" | tail -1)"
  ckpt="${ckpt// /}"
  backup_id="$(mold_backup_resolve_backup_id_for_vm "$vm" "$job" "$rp_id" "$ckpt" 2>/dev/null || true)"
  if [[ -z "$backup_id" ]]; then
    mold_backup_emit_restore_event "mold.restore.failed" "$vm" "session=${sid};reason=no-backup-id;rp=${rp_id:-n/a}"
    mold_backup_restore_lock_release
    return 1
  fi
  export BACKUP_ID="$backup_id" VM_NAME="$vm"
  [[ -n "$rp_id" ]] && export VEEAM_RESTORE_POINT_ID="$rp_id"
  export RESTORE_SOURCE="${RESTORE_SOURCE:-${VEEAM_UI_RESTORE_SOURCE:-mold-only}}"
  mold_backup_notify_log info "Veeam UI restore session=${sid} vm=${vm} rp=${rp_id:-n/a} → Mold datadisk restore backup_id=${backup_id} (RESTORE_SOURCE=${RESTORE_SOURCE})"
  mold_backup_emit_restore_event "veeam.restore.completed" "$vm" "session=${sid};rp=${rp_id:-n/a};backup_id=${backup_id};${detail}"
  mold_backup_emit_restore_event "mold.restore.requested" "$vm" "session=${sid};rp=${rp_id:-n/a};backup_id=${backup_id};source=${RESTORE_SOURCE}"
  mold_backup_trigger_mark "mold-restore-active" "$vm"
  if mold_backup_restore_notify "$(hostname -s)" "$job"; then
    mold_backup_registry_save_restore "$job" "$vm" "veeam" "$sid" "${detail};backup_id=${backup_id}" "mold-restored"
    mold_backup_restore_session_mark_seen "$sid"
    mold_backup_emit_restore_event "mold.restore.completed" "$vm" "session=${sid};backup_id=${backup_id}"
  else
    mold_backup_emit_restore_event "mold.restore.failed" "$vm" "session=${sid};backup_id=${backup_id}"
    rc=1
  fi
  mold_backup_trigger_clear "mold-restore-active" "$vm"
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
    local _vm _ip
    VM_TARGETS=""
    for _vm in ${VM_INCLUDE//,/ }; do
      _vm="$(echo "$_vm" | xargs)"
      [[ -n "$_vm" ]] || continue
      _ip="$(virsh -c qemu:///system domifaddr "$_vm" 2>/dev/null | awk '/ipv4/ {print $4; exit}' | cut -d/ -f1)"
      VM_TARGETS+="${VM_TARGETS:+,}${_vm}:${_ip:-${_vm}}"
    done
  fi
  [[ -n "${VM_TARGETS:-}" ]] || {
    mold_backup_notify_log warn "Veeam→Mold(restore): VM_TARGETS empty; set VM_INCLUDE or VM_TARGETS"
    return 0
  }
  [[ "${trigger_mold}" == "true" ]] && mold_backup_restore_preflight
  mold_backup_notify_log info "=== restore-watch job=${job} window=${since_min}min trigger_mold=${trigger_mold} host=$(mold_backup_local_kvm_name) ==="
  local sid sip et result nm bn rp_id matched_ip vm
  local processed=0 handled=0
  while IFS='|' read -r sid sip et result nm bn rp_id; do
    [[ -n "$sid" ]] || continue
    processed=$((processed+1))
    if mold_backup_restore_session_seen "$sid"; then
      mold_backup_notify_log info "restore-watch: session ${sid} already processed (skip duplicate)"
      continue
    fi
    # Match: prefer the IP parsed from the session Options; fall back to scanning
    # VM_TARGETS IPs (dots or dashes form) against the session name / backup name.
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
      local host_hit=false
      if [[ -n "$kvm_ip" && ( "$sip" == "$kvm_ip" || "$nm" == *"$kvm_ip"* || "$bn" == *"$kvm_ip"* ) ]]; then
        host_hit=true
      elif [[ -n "$kvm_hn" && ( "$nm" == *"$kvm_hn"* || "$bn" == *"$kvm_hn"* || "$bn" == *"$job"* ) ]]; then
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
    # Agent FLR to hypervisor: audit/session metadata often lacks libvirt VM name — use explicit target.
    if [[ -z "$matched_ip" && "${BACKUP_MODE:-host}" == "host" && -n "${VEEAM_RESTORE_VM:-}" ]]; then
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
    [[ -n "$rp_id" ]] && mold_backup_notify_log info "restore-watch: session ${sid} vm=${vm} veeam_rp=${rp_id}"
    mold_backup_handle_veeam_restore_session "$job" "$vm" "$sid" \
      "name=${nm};end=${et};result=${result};backup=${bn};ip=${matched_ip};rp=${rp_id}" "$trigger_mold" "$rp_id" \
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
mold_backup_veeam_ssh_encoded() {
  local ps_enc="$1"
  local attempt out rc last_err="" ps_launcher
  [[ -n "${VEEAM_SSH_HOST:-}" ]] || return 1
  [[ -n "$ps_enc" ]] || return 1
  local ssh_key_opt=()
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_key_opt=(-i "${VEEAM_SSH_KEY}")
  local -a ssh_host_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
  if [[ "${VEEAM_SSH_STRICT_HOSTKEY:-false}" == "true" ]]; then
    ssh_host_opts=(-o StrictHostKeyChecking=accept-new)
  fi
  local -a ps_launchers=(
    '"C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -EncodedCommand'
    'pwsh.exe -NoProfile -EncodedCommand'
    'pwsh -NoProfile -EncodedCommand'
    'powershell.exe -NoProfile -EncodedCommand'
  )
  for attempt in 1 2 3; do
    for ps_launcher in "${ps_launchers[@]}"; do
      out="$(ssh -n "${ssh_key_opt[@]}" -o BatchMode=yes -o ConnectTimeout=30 "${ssh_host_opts[@]}" \
              "${VEEAM_SSH_USER:-administrator}@${VEEAM_SSH_HOST}" \
              "${ps_launcher} ${ps_enc}" 2>&1)" && rc=0 || rc=$?
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
      mold_backup_notify_log err "guest post: host export failed for ${vm_name} (check ${CVT_BACKUP_SCRIPT} and /var/log/mold/veeam-hook.log)"
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
  local job="${2:-${VEEAM_JOB_NAME:-}}"
  local schedule="${3:-${VEEAM_SCHEDULE_NAME:-default}}"
  local single_vm="${4:-}"
  local saved_include=""
  [[ -n "$job" ]] || mold_backup_die "VEEAM_JOB_NAME is required for pre-notify"
  VEEAM_JOB_NAME="$job"
  mold_backup_load_config || exit 1

  if [[ -n "$single_vm" ]]; then
    saved_include="${VM_INCLUDE:-*}"
    VM_INCLUDE="$single_vm"
    mold_backup_notify_log info "single-vm scope: ${single_vm}"
  fi

  mold_backup_notify_log info "=== pre-notify (설계4: Pre-script + Mold API 백업요청) client=${client} job=${job} schedule=${schedule} ==="
  mkdir -p "$(mold_backup_state_dir)" "${VEEAM_HOST_BACKUP_PATH}"

  local offering_id="" vm_name
  local success=0 fail=0
  local state_file run_id
  run_id="$(date '+%Y%m%d%H%M%S')"
  state_file="$(mold_backup_state_file_for_job "$job" "$run_id")"
  : > "$state_file"
  echo "run_id=${run_id}" >> "$state_file"

  if mold_backup_cmk_bin >/dev/null 2>&1 || command -v curl >/dev/null 2>&1; then
    mold_backup_api_ensure_global_settings || true
    offering_id="$(mold_backup_api_find_offering_id "${VEEAM_PROVIDER_NAME}" "$(mold_backup_offering_name)" 2>/dev/null || true)"
    if [[ -z "$offering_id" ]]; then
      if ! mold_backup_api_list_backup_offerings >/dev/null 2>&1; then
        mold_backup_notify_log err "Cannot list/import backup offerings (check API key/secret or MS DB schema; see mold-ms-backup-schema-fix.sql)"
      else
        offering_id="$(mold_backup_api_ensure_backup_resources 2>/dev/null || true)"
      fi
    fi
    [[ -n "$offering_id" ]] || {
      if mold_backup_is_datadisk_mode; then
        mold_backup_notify_log warn "No backup offering '$(mold_backup_offering_name)' for ${VEEAM_PROVIDER_NAME}; assign in Mold UI (datadisk mode does not auto-create backup repository)"
      else
        mold_backup_notify_log warn "No backup offering '$(mold_backup_offering_name)' for ${VEEAM_PROVIDER_NAME}; set BACKUP_REPO_ADDRESS + ZONE_ID and re-run veeam_config.sh"
      fi
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
        if mold_backup_process_vm_pre_notify "$vm_name" "$offering_id" "$state_file"; then
          success=$((success + 1))
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

  if [[ "$success" -eq 0 && "$fail" -eq 0 ]]; then
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

  mold_backup_notify_log info "pre-notify done success=${success} fail=${fail} state=${state_file}"
  [[ -n "$saved_include" ]] && VM_INCLUDE="$saved_include"
  [[ "$success" -gt 0 ]] && return 0
  return 1
}

mold_backup_post_notify() {
  local client="${1:-$(hostname -s)}"
  local job="${2:-${VEEAM_JOB_NAME:-}}"
  local schedule="${3:-${VEEAM_SCHEDULE_NAME:-default}}"
  [[ -n "$job" ]] || mold_backup_die "VEEAM_JOB_NAME is required for post-notify"
  VEEAM_JOB_NAME="$job"
  mold_backup_load_config || exit 1

  mold_backup_notify_log info "=== post-notify (설계4: Post-script + 백업ID 저장) client=${client} job=${job} ==="
  local state_file line vm_name backup_id status guest_handled=0 guest_fail=0 vm_id reason rc=0
  local host_rp_id=""
  state_file="$(mold_backup_latest_state_file "$job" || true)"

  if [[ "${BACKUP_MODE:-host}" == "host" || "${BACKUP_MODE:-host}" == "policy" ]]; then
    host_rp_id="$(mold_backup_query_veeam_latest_restore_point "$job" "" "" 6 2>/dev/null || true)"
    [[ -n "$host_rp_id" ]] && mold_backup_notify_log info "post-notify: host job Veeam restore point=${host_rp_id}"
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
        [[ -n "$vm_name" ]] && mold_backup_trigger_clear "veeam-active" "$vm_name"
        continue
      fi
      if [[ "$status" == "pending" && "${BACKUP_MODE}" =~ ^(guest|veeam-guest)$ ]]; then
        mold_backup_notify_log info "guest post: pending vm=${vm_name} mode=${BACKUP_MODE} reason=${reason:-veeam-guest-pre}"
        if mold_backup_guest_post_notify_vm "$vm_name" "$vm_id" "$job"; then
          guest_handled=1
        else
          guest_fail=$((guest_fail + 1))
        fi
        [[ -n "$vm_name" ]] && mold_backup_trigger_clear "veeam-active" "$vm_name"
        continue
      fi
      # Veeam job for this VM finished — clear the loop-guard marker.
      [[ -n "$vm_name" ]] && mold_backup_trigger_clear "veeam-active" "$vm_name"
      if [[ "$status" == "pending" ]]; then
        mold_backup_notify_log warn "post-notify: pending vm=${vm_name} but BACKUP_MODE=${BACKUP_MODE:-host} (expected guest)"
      fi
      [[ "$status" == "success" && -n "$backup_id" ]] || continue
      mold_backup_registry_save_backup "$job" "$vm_name" "$backup_id" "veeam-backed-up rp=${host_rp_id:-n/a}" "$host_rp_id"
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

  mold_backup_cleanup_host_path
  mold_backup_cleanup_staging
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
  [[ -n "$owner" ]] || return 0
  [[ "$owner" == "$vm_id" ]] || {
    mold_backup_notify_log err "Backup ${backup_id} belongs to VM ${owner}, not ${vm_id}"
    return 1
  }
  return 0
}

mold_backup_restore_notify() {
  local client="${1:-$(hostname -s)}"
  local job="${2:-${VEEAM_JOB_NAME:-}}"
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
    mold_backup_api_restore
  else
    mold_backup_notify_log info "Restore type=INCREMENTAL → datadisk only (qcow2/raw/rbdiff on ${BACKUP_REPO_ADDRESS:-/data/backup}, no NAS)"
    if [[ "$restore_source" != "mold-only" ]]; then
      mold_backup_veeam_restore_chain_to_host "$BACKUP_ID"
    fi
    mold_backup_api_restore
  fi
  mold_backup_notify_log info "=== restore-notify done ==="
}
