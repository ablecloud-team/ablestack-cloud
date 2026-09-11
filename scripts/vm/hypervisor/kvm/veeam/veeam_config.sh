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

# NetBackup-style Veeam + Mold host configuration (veeam_config.sh).
# Creates /etc/ablestack/veeam/<JOB>.conf, encrypts API secret, optional Mold global settings.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
SHARE_DIR="${MOLD_BACKUP_SHARE_DIR:-/usr/share/mold/backup/veeam}"
SECRET_SCRIPT="${SCRIPT_DIR}/mold-backup-secret.sh"
DEFAULT_SECRET_KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"

JOB_NAME=""
BACKUP_OFFERING_NAME="VeeamBackup"
VM_INCLUDE=""
VM_EXCLUDE=""
MAX_CHAIN="10"
MOLD_API_URL=""
MOLD_API_KEY=""
MOLD_API_SECRET=""
ZONE_ID=""
RETENTION_PERIOD="P7D"
VEEAM_URL=""
VEEAM_USERNAME=""
VEEAM_PASSWORD=""
BACKUP_MODE="host"
IMPORT_MODE="auto"

VEEAM_SSH_HOST=""
VEEAM_SSH_USER="administrator"
VEEAM_SSH_KEY="/root/.ssh/veeam_id_rsa"
RESTORE_SOURCE="auto"
KVM_HOST=""
KVM_SSH_USER="root"
KVM_SSH_KEY=""
KVM_SSH_PASSWORD=""
VM_NAME_CFG=""
VM_UUID_CFG=""
BACKUP_REPO_TYPE="local"
BACKUP_REPO_NAME="Ablestack Data Disk"
BACKUP_REPO_ADDRESS=""
BACKUP_REPO_MOUNT_OPTS=""
BACKUP_REPO_PROVIDER="localfs"
NAS_REPO_MOUNT=""
BACKUP_STORAGE_MODE="datadisk"
MOLD_DATADISK_PATH="/data/backup"
ENCRYPT_SECRET="true"
SECRET_KEY_FILE=""
VEEAM_BACKUP_TARGET=""
VM_TARGETS=""
VEEAM_GUEST_JOB_PREFIX="Mold VM"
GUEST_VM_SSH_USER=""
GUEST_VM_SSH_PASSWORD=""
RUN_INSTALL="false"
CONFIGURE_MOLD="true"
ENV_FILE=""
DEFAULT_ENV_FILE="${ETC_DIR}/mold-backup.env"
MOLD_API_SECRET_ENC_FILE_CANDIDATE=""

usage() {
  cat <<'EOF'
Usage: veeam_config.sh [options]

Required:
  --job-name NAME         Veeam backup job / policy name (conf file name)

Auto-filled (when omitted) from, in order:
  1) CLI options
  2) --env-file or /etc/ablestack/veeam/mold-backup.env
  3) Existing /etc/ablestack/veeam/*.conf
  4) secrets/secret.enc + /root/.ssh/ablestack.key
  5) Mold API listZones / listBackupRepositories
  6) /etc/cloudstack/agent/agent.properties (Mold API URL)

Common optional overrides:
  --offering-name NAME    Mold backup offering name (default: VeeamBackup)
  --mold-url URL          Mold API URL (http://<ms>:8080/client/api or https://<ms>/client/api)
  --api-key KEY           Mold API key
  --api-secret SECRET     Mold API secret
  --zone-id UUID          Zone for importBackupOffering
  --nas-repo ADDR         NAS repo address (host:/export; nfs:// prefix optional)
  --env-file PATH         Env file (default: /etc/ablestack/veeam/mold-backup.env)

Other optional:
  --vm-include LIST       Comma-separated libvirt names (* = all running, default)
  --vm-exclude LIST       Extra libvirt names/globs to skip (auto-exclude still applies)
  --vm-name NAME          libvirt name (mold-backup.sh status 등)
  --vm-uuid UUID          Mold VM UUID
  --max-chain N           Max incremental chain (default: 10; also sets Mold backup.chain.size)
  --backup-chain-size N   Alias of --max-chain (Mold Global backup.chain.size)
  --retention PERIOD      Backup offering retention (default: P7D)
  --backup-mode MODE      host|api|local|auto (default: host = NetBackup-style /tmp/mold/veeam)
  --host-backup-path PATH KVM stage root (default: /tmp/mold/veeam); also sets Mold backup.plugin.ablestack-veeam.stage.root.path
  --stage-root-path PATH  Alias of --host-backup-path
  --backup-target MODE    host only (guest mode removed)
  --veeam-url URL         Mold zone setting backup.plugin.ablestack-veeam.url
  --veeam-user USER       Mold zone setting backup.plugin.ablestack-veeam.username
  --veeam-password PASS   Mold zone setting backup.plugin.ablestack-veeam.password
  --nas-repo ADDR         NAS repo address (host:/export; nfs:// prefix optional)
  --repo-name NAME        Mold backup repository name (default: Ablestack Veeam NAS)
  --repo-type TYPE        nfs|cifs (default: nfs)
  --repo-mount-opts OPTS  Mount options for addBackupRepository
  --kvm-host IP           KVM host for Veeam post-job SSH
  --kvm-ssh-user USER     (default: root)
  --kvm-ssh-key PATH      SSH private key
  --no-configure-mold     Skip listConfigurations/updateConfiguration calls
  --no-encrypt-secret     Store API secret in plain text (not recommended)
  --secret-key-file PATH  Passphrase file (default: /root/.ssh/ablestack.key)
  --env-file PATH         Credentials/env file (default: /etc/ablestack/veeam/mold-backup.env)
  --install               Run install.sh after generating configs
  -h, --help

Example (minimal — reads mold-backup.env + existing conf):
  veeam_config.sh --job-name "Mold KVM Backup" --kvm-host 10.10.31.2

Example (explicit):
  veeam_config.sh \
    --job-name "VeeamBackup" \
    --offering-name "VeeamBackup" \
    --mold-url http://10.10.31.20:8080/client/api \
    --api-key KEY --api-secret 'SECRET' \
    --zone-id <zone-uuid> \
    --vm-include "i-2-3-VM,i-2-7-VM" \
    --max-chain 10 \
    --veeam-url https://veeam:9398/api/ \
    --kvm-host 10.10.31.30 \
    --install
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }

veeam_set_if_empty() {
  local name="$1" value="$2"
  [[ -z "${value}" ]] && return 0
  [[ -n "${!name:-}" ]] && return 0
  printf -v "$name" '%s' "$value"
}

veeam_config_read_conf_var() {
  local file="$1" key="$2" line val
  [[ -f "$file" ]] || return 1
  line="$(grep -E "^${key}=" "$file" 2>/dev/null | tail -1)" || return 1
  val="${line#*=}"
  val="${val%$'\r'}"
  if [[ "$val" == \"*\" ]]; then
    val="${val#\"}"; val="${val%\"}"
  elif [[ "$val" == \'*\' ]]; then
    val="${val#\'}"; val="${val%\'}"
  fi
  [[ -n "$val" ]] || return 1
  echo "$val"
}

veeam_config_import_env_file() {
  local f="$1" line key val
  [[ -f "$f" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$line" || "$line" != *"="* ]] && continue
    key="${line%%=*}"
    key="$(echo "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    val="${line#*=}"
    val="$(echo "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [[ "$val" == \"*\" ]]; then val="${val#\"}"; val="${val%\"}"; fi
    if [[ "$val" == \'*\' ]]; then val="${val#\'}"; val="${val%\'}"; fi
    case "$key" in
      VEEAM_USER) [[ -n "$val" ]] && VEEAM_USERNAME="$val" ;;
      KVM_IP) veeam_set_if_empty KVM_HOST "$val" ;;
      VEEAM_JOB_NAME) veeam_set_if_empty JOB_NAME "$val" ;;
      MOLD_API_URL) [[ -n "$val" ]] && MOLD_API_URL="$val" ;;
      MOLD_API_KEY) [[ -n "$val" ]] && MOLD_API_KEY="$val" ;;
      MOLD_API_SECRET) [[ -n "$val" ]] && MOLD_API_SECRET="$val" ;;
      ZONE_ID) [[ -n "$val" ]] && ZONE_ID="$val" ;;
      JOB_NAME) veeam_set_if_empty JOB_NAME "$val" ;;
      BACKUP_OFFERING_NAME) veeam_set_if_empty BACKUP_OFFERING_NAME "$val" ;;
      VM_INCLUDE) veeam_set_if_empty VM_INCLUDE "$val" ;;
      VM_EXCLUDE) veeam_set_if_empty VM_EXCLUDE "$val" ;;
      VM_AUTO_EXCLUDE) [[ -n "$val" ]] && VM_AUTO_EXCLUDE="$val" ;;
      VM_NAME) veeam_set_if_empty VM_NAME_CFG "$val" ;;
      VM_UUID) veeam_set_if_empty VM_UUID_CFG "$val" ;;
      MAX_CHAIN) [[ -n "$val" ]] && MAX_CHAIN="$val" ;;
      VEEAM_MAX_CHAIN) [[ -n "$val" ]] && MAX_CHAIN="$val" ;;
      BACKUP_CHAIN_SIZE) [[ -n "$val" ]] && MAX_CHAIN="$val" ;;
      RETENTION_PERIOD) [[ -n "$val" ]] && RETENTION_PERIOD="$val" ;;
      BACKUP_MODE) veeam_set_if_empty BACKUP_MODE "$val" ;;
      IMPORT_MODE) [[ -n "$val" ]] && IMPORT_MODE="$val" ;;
      VEEAM_URL) [[ -n "$val" ]] && VEEAM_URL="$val" ;;
      VEEAM_USERNAME) [[ -n "$val" ]] && VEEAM_USERNAME="$val" ;;
      VEEAM_PASSWORD) [[ -n "$val" ]] && VEEAM_PASSWORD="$val" ;;
      VEEAM_SSH_HOST) [[ -n "$val" ]] && VEEAM_SSH_HOST="$val" ;;
      VEEAM_SSH_USER) [[ -n "$val" ]] && VEEAM_SSH_USER="$val" ;;
      VEEAM_SSH_KEY) [[ -n "$val" ]] && VEEAM_SSH_KEY="$val" ;;
      KVM_HOST) veeam_set_if_empty KVM_HOST "$val" ;;
      KVM_SSH_USER) [[ -n "$val" ]] && KVM_SSH_USER="$val" ;;
      KVM_SSH_KEY) [[ -n "$val" ]] && KVM_SSH_KEY="$val" ;;
      KVM_SSH_PASSWORD) [[ -n "$val" ]] && KVM_SSH_PASSWORD="$val" ;;
      BACKUP_REPO_ADDRESS) [[ -n "$val" ]] && BACKUP_REPO_ADDRESS="$val" ;;
      BACKUP_REPO_NAME) [[ -n "$val" ]] && BACKUP_REPO_NAME="$val" ;;
      BACKUP_REPO_TYPE) [[ -n "$val" ]] && BACKUP_REPO_TYPE="$val" ;;
      BACKUP_REPO_MOUNT_OPTS) [[ -n "$val" ]] && BACKUP_REPO_MOUNT_OPTS="$val" ;;
      NAS_REPO_MOUNT) [[ -n "$val" ]] && NAS_REPO_MOUNT="$val" ;;
      VEEAM_BACKUP_TARGET) veeam_set_if_empty VEEAM_BACKUP_TARGET "$val" ;;
      VM_TARGETS) [[ -n "$val" ]] && VM_TARGETS="$val" ;;
      VEEAM_GUEST_JOB_PREFIX) [[ -n "$val" ]] && VEEAM_GUEST_JOB_PREFIX="$val" ;;
      GUEST_VM_SSH_USER) [[ -n "$val" ]] && GUEST_VM_SSH_USER="$val" ;;
      GUEST_VM_SSH_PASSWORD) [[ -n "$val" ]] && GUEST_VM_SSH_PASSWORD="$val" ;;
    esac
  done < "$f"
}

veeam_config_load_from_conf() {
  local file="$1" v
  [[ -f "$file" ]] || return 0
  veeam_set_if_empty MOLD_API_URL "$(veeam_config_read_conf_var "$file" MOLD_API_URL || true)"
  veeam_set_if_empty MOLD_API_KEY "$(veeam_config_read_conf_var "$file" MOLD_API_KEY || true)"
  veeam_set_if_empty MOLD_API_SECRET "$(veeam_config_read_conf_var "$file" MOLD_API_SECRET || true)"
  v="$(veeam_config_read_conf_var "$file" MOLD_API_SECRET_ENC_FILE || true)"
  [[ -n "$v" ]] && MOLD_API_SECRET_ENC_FILE_CANDIDATE="$v"
  veeam_set_if_empty SECRET_KEY_FILE "$(veeam_config_read_conf_var "$file" MOLD_SECRET_KEY_FILE || true)"
  veeam_set_if_empty ZONE_ID "$(veeam_config_read_conf_var "$file" ZONE_ID || true)"
  veeam_set_if_empty BACKUP_OFFERING_NAME "$(veeam_config_read_conf_var "$file" BACKUP_OFFERING_NAME || true)"
  veeam_set_if_empty VM_INCLUDE "$(veeam_config_read_conf_var "$file" VM_INCLUDE || true)"
  veeam_set_if_empty VM_EXCLUDE "$(veeam_config_read_conf_var "$file" VM_EXCLUDE || true)"
  veeam_set_if_empty VM_NAME_CFG "$(veeam_config_read_conf_var "$file" VM_NAME || true)"
  veeam_set_if_empty VM_UUID_CFG "$(veeam_config_read_conf_var "$file" VM_UUID || true)"
  veeam_set_if_empty MAX_CHAIN "$(veeam_config_read_conf_var "$file" VEEAM_MAX_CHAIN || true)"
  veeam_set_if_empty RETENTION_PERIOD "$(veeam_config_read_conf_var "$file" RETENTION_PERIOD || true)"
  veeam_set_if_empty VEEAM_URL "$(veeam_config_read_conf_var "$file" VEEAM_URL || true)"
  veeam_set_if_empty VEEAM_USERNAME "$(veeam_config_read_conf_var "$file" VEEAM_USERNAME || true)"
  veeam_set_if_empty VEEAM_PASSWORD "$(veeam_config_read_conf_var "$file" VEEAM_PASSWORD || true)"
  veeam_set_if_empty VEEAM_SSH_HOST "$(veeam_config_read_conf_var "$file" VEEAM_SSH_HOST || true)"
  veeam_set_if_empty BACKUP_REPO_ADDRESS "$(veeam_config_read_conf_var "$file" BACKUP_REPO_ADDRESS || true)"
  veeam_set_if_empty BACKUP_REPO_NAME "$(veeam_config_read_conf_var "$file" BACKUP_REPO_NAME || true)"
  veeam_set_if_empty BACKUP_REPO_TYPE "$(veeam_config_read_conf_var "$file" BACKUP_REPO_TYPE || true)"
  veeam_set_if_empty BACKUP_REPO_MOUNT_OPTS "$(veeam_config_read_conf_var "$file" BACKUP_REPO_MOUNT_OPTS || true)"
  veeam_set_if_empty NAS_REPO_MOUNT "$(veeam_config_read_conf_var "$file" NAS_REPO_MOUNT || true)"
}

veeam_config_auto_mold_url() {
  local url
  [[ -n "$MOLD_API_URL" ]] && {
    if [[ "$MOLD_API_URL" != *"/client/api" ]]; then
      # shellcheck source=mold-guest-common.sh
      source "${SCRIPT_DIR}/mold-guest-common.sh"
      MOLD_API_URL="$(mold_guest_normalize_mold_api_url "$MOLD_API_URL")"
    fi
    return 0
  }
  # shellcheck source=mold-guest-common.sh
  source "${SCRIPT_DIR}/mold-guest-common.sh"
  url="$(mold_guest_discover_mold_api_url_from_agent || true)"
  [[ -n "$url" ]] || return 1
  MOLD_API_URL="$url"
  return 0
}

veeam_config_resolve_api_secret() {
  local enc key
  [[ -n "$MOLD_API_SECRET" ]] && return 0
  enc="${MOLD_API_SECRET_ENC_FILE_CANDIDATE:-${ETC_DIR}/secrets/secret.enc}"
  key="${SECRET_KEY_FILE:-$DEFAULT_SECRET_KEY_FILE}"
  [[ -f "$enc" && -f "$key" && -x "$SECRET_SCRIPT" ]] || return 0
  MOLD_API_SECRET="$("$SECRET_SCRIPT" decrypt --enc-file "$enc" --key-file "$key" 2>/dev/null || true)"
}

veeam_config_autofill_defaults() {
  local env_path conf_path safe_job f

  for env_path in \
    "${ENV_FILE}" \
    "${DEFAULT_ENV_FILE}" \
    "${SCRIPT_DIR}/mold-backup.env" \
    "${SHARE_DIR}/mold-backup.env"; do
    [[ -n "$env_path" && -f "$env_path" ]] || continue
    veeam_config_import_env_file "$env_path"
  done

  safe_job=""
  if [[ -n "$JOB_NAME" ]]; then
    safe_job="$(echo "$JOB_NAME" | tr ' /' '__')"
    veeam_config_load_from_conf "${ETC_DIR}/${safe_job}.conf"
    veeam_config_load_from_conf "${ETC_DIR}/${JOB_NAME}.conf"
  fi
  veeam_config_load_from_conf "${ETC_DIR}/mold-backup.conf"

  if [[ -z "$MOLD_API_KEY" || -z "$MOLD_API_SECRET" || -z "$ZONE_ID" || -z "$BACKUP_REPO_ADDRESS" ]]; then
    for conf_path in "${ETC_DIR}"/*.conf; do
      [[ -f "$conf_path" ]] || continue
      [[ "$conf_path" == *mold-backup.windows.conf ]] && continue
      veeam_config_load_from_conf "$conf_path"
    done
  fi

  veeam_config_resolve_api_secret
  veeam_config_auto_mold_url
  [[ -n "$KVM_HOST" ]] || KVM_HOST="$(hostname -I 2>/dev/null | awk '{print $1}')"

  if [[ -n "$MOLD_API_URL" && -n "$MOLD_API_KEY" && -n "$MOLD_API_SECRET" ]]; then
  # shellcheck source=mold-backup.lib.sh
    source "${SCRIPT_DIR}/mold-backup.lib.sh"
    export MOLD_API_URL MOLD_API_KEY MOLD_API_SECRET ZONE_ID BACKUP_REPO_NAME BACKUP_REPO_ADDRESS
    if [[ -z "$ZONE_ID" ]]; then
      ZONE_ID="$(mold_backup_api_first_zone_id 2>/dev/null || true)"
    fi
    if [[ -z "$BACKUP_REPO_ADDRESS" ]]; then
      if [[ "${BACKUP_STORAGE_MODE:-datadisk}" == "datadisk" ]]; then
        BACKUP_REPO_ADDRESS="${MOLD_DATADISK_PATH:-/data/backup}"
      else
        BACKUP_REPO_ADDRESS="$(mold_backup_api_first_repo_address "${BACKUP_REPO_NAME}" 2>/dev/null || true)"
      fi
    fi
  fi
}

veeam_config_print_autofill_summary() {
  echo "Configuration sources:"
  [[ -f "${ENV_FILE:-$DEFAULT_ENV_FILE}" ]] && echo "  env: ${ENV_FILE:-$DEFAULT_ENV_FILE}"
  echo "  API URL: ${MOLD_API_URL:-<missing>}"
  [[ -n "${MOLD_API_KEY:-}" ]] && echo "  API key: ${MOLD_API_KEY:0:12}..."
  [[ -n "${MOLD_API_SECRET:-}" ]] && echo "  API secret: (loaded)"
  echo "  Zone: ${ZONE_ID:-<missing>}"
  echo "  NAS repo: ${BACKUP_REPO_ADDRESS:-<missing>}"
  echo "  KVM host: ${KVM_HOST:-<missing>}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --job-name) JOB_NAME="$2"; shift 2 ;;
    --offering-name) BACKUP_OFFERING_NAME="$2"; shift 2 ;;
    --vm-include) VM_INCLUDE="$2"; shift 2 ;;
    --vm-exclude) VM_EXCLUDE="$2"; shift 2 ;;
    --vm-name) VM_NAME_CFG="$2"; shift 2 ;;
    --vm-uuid) VM_UUID_CFG="$2"; shift 2 ;;
    --max-chain|--backup-chain-size) MAX_CHAIN="$2"; shift 2 ;;
    --mold-url) MOLD_API_URL="$2"; shift 2 ;;
    --api-key) MOLD_API_KEY="$2"; shift 2 ;;
    --api-secret) MOLD_API_SECRET="$2"; shift 2 ;;
    --zone-id) ZONE_ID="$2"; shift 2 ;;
    --retention) RETENTION_PERIOD="$2"; shift 2 ;;
    --backup-mode) BACKUP_MODE="$2"; shift 2 ;;
    --host-backup-path|--stage-root-path) VEEAM_HOST_BACKUP_PATH="$2"; shift 2 ;;
    --backup-target) VEEAM_BACKUP_TARGET="$2"; shift 2 ;;
    --vm-targets) die "Guest-mode --vm-targets removed. Use --vm-include with host/datadisk mode." ;;
    --guest-job-prefix) die "Guest-mode --guest-job-prefix removed." ;;
    --veeam-url) VEEAM_URL="$2"; shift 2 ;;
    --veeam-user) VEEAM_USERNAME="$2"; shift 2 ;;
    --veeam-password) VEEAM_PASSWORD="$2"; shift 2 ;;
    --veeam-ssh-host) VEEAM_SSH_HOST="$2"; shift 2 ;;
    --veeam-ssh-user) VEEAM_SSH_USER="$2"; shift 2 ;;
    --veeam-ssh-key) VEEAM_SSH_KEY="$2"; shift 2 ;;
    --nas-repo) BACKUP_REPO_ADDRESS="$2"; shift 2 ;;
    --repo-name) BACKUP_REPO_NAME="$2"; shift 2 ;;
    --repo-type) BACKUP_REPO_TYPE="$2"; shift 2 ;;
    --repo-mount-opts) BACKUP_REPO_MOUNT_OPTS="$2"; shift 2 ;;
    --nas-repo-mount) NAS_REPO_MOUNT="$2"; shift 2 ;;
    --kvm-host) KVM_HOST="$2"; shift 2 ;;
    --kvm-ssh-user) KVM_SSH_USER="$2"; shift 2 ;;
    --kvm-ssh-key) KVM_SSH_KEY="$2"; shift 2 ;;
    --no-configure-mold) CONFIGURE_MOLD="false"; shift ;;
    --no-encrypt-secret) ENCRYPT_SECRET="false"; shift ;;
    --secret-key-file) SECRET_KEY_FILE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --install) RUN_INSTALL="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

veeam_config_autofill_defaults

if [[ "${VEEAM_BACKUP_TARGET:-host}" == "guest" || "${BACKUP_MODE:-}" =~ ^(guest|veeam-guest)$ ]]; then
  die "Guest-mode Veeam jobs were removed. Use --backup-target host / --backup-mode host (datadisk)."
fi

# Host mode (default): one Veeam Agent job on this KVM hypervisor (e.g. 10.10.31.2).
if [[ -z "$JOB_NAME" ]]; then
  JOB_NAME="Mold ${KVM_HOSTNAME:-$(hostname -s)}"
  BACKUP_MODE="host"
fi

if [[ -z "$JOB_NAME" ]]; then
  die "--job-name is required. Edit ${ENV_FILE:-$DEFAULT_ENV_FILE} and set JOB_NAME=... (or VEEAM_JOB_NAME=...), or pass --job-name 'Mold Guest Backup'. Shell variables are not read unless exported and listed in --env-file."
fi
[[ -n "$MOLD_API_URL" && -n "$MOLD_API_KEY" && -n "$MOLD_API_SECRET" ]] \
  || die "API credentials missing — set MOLD_API_KEY/MOLD_API_SECRET in ${DEFAULT_ENV_FILE} or pass --api-key/--api-secret"
[[ -n "$ZONE_ID" ]] \
  || die "zone-id missing — set ZONE_ID in ${DEFAULT_ENV_FILE}, pass --zone-id, or ensure listZones API is reachable"

veeam_config_print_autofill_summary
echo ""

if [[ -n "$BACKUP_REPO_ADDRESS" ]]; then
  if [[ "$BACKUP_REPO_ADDRESS" == nfs://* ]]; then
    BACKUP_REPO_TYPE="nfs"
  elif [[ "$BACKUP_REPO_ADDRESS" == cifs://* ]]; then
    BACKUP_REPO_TYPE="cifs"
  fi
  BACKUP_REPO_ADDRESS="${BACKUP_REPO_ADDRESS#nfs://}"
  BACKUP_REPO_ADDRESS="${BACKUP_REPO_ADDRESS#cifs://}"
fi

# Datadisk mode: Mold API may still point at glue-gfs; KVM backups go to /data/backup.
if [[ "${BACKUP_STORAGE_MODE:-datadisk}" == "datadisk" && "${BACKUP_REPO_ADDRESS:-}" == *glue-gfs* ]]; then
  if [[ -d /data/backup ]]; then
    echo "WARN: API/GFS repo ${BACKUP_REPO_ADDRESS} → /data/backup (datadisk mode; no NAS restore)"
    BACKUP_REPO_ADDRESS="/data/backup"
    MOLD_DATADISK_PATH="/data/backup"
  fi
fi

install -d -m 0700 "${ETC_DIR}" "${ETC_DIR}/secrets" "${ETC_DIR}/state" "${ETC_DIR}/registry"

ENC_FILE="${ETC_DIR}/secrets/secret.enc"
MOLD_API_SECRET_LINE=""
MOLD_API_SECRET_ENC_FILE_LINE=""
MOLD_SECRET_KEY_FILE_LINE=""

if [[ "$ENCRYPT_SECRET" == "true" ]]; then
  [[ -x "$SECRET_SCRIPT" ]] || chmod +x "$SECRET_SCRIPT"
  KEY_FILE="${SECRET_KEY_FILE:-$DEFAULT_SECRET_KEY_FILE}"
  [[ -f "$KEY_FILE" ]] || die "Secret key file not found: $KEY_FILE"
  "$SECRET_SCRIPT" encrypt --secret "$MOLD_API_SECRET" --key-file "$KEY_FILE" --out "$ENC_FILE" >/dev/null
  MOLD_API_SECRET_LINE='MOLD_API_SECRET=""'
  MOLD_API_SECRET_ENC_FILE_LINE="MOLD_API_SECRET_ENC_FILE=\"${ENC_FILE}\""
  MOLD_SECRET_KEY_FILE_LINE="MOLD_SECRET_KEY_FILE=\"${KEY_FILE}\""
else
  MOLD_API_SECRET_LINE="MOLD_API_SECRET=\"${MOLD_API_SECRET}\""
  MOLD_API_SECRET_ENC_FILE_LINE='MOLD_API_SECRET_ENC_FILE=""'
  MOLD_SECRET_KEY_FILE_LINE='MOLD_SECRET_KEY_FILE=""'
fi

safe_job="$(echo "$JOB_NAME" | tr ' /' '__')"
CONF="${ETC_DIR}/${safe_job}.conf"
cat > "$CONF" <<EOF
# Generated by veeam_config.sh on $(date -Iseconds)
MOLD_API_URL="${MOLD_API_URL}"
MOLD_API_KEY="${MOLD_API_KEY}"
${MOLD_API_SECRET_LINE}
${MOLD_API_SECRET_ENC_FILE_LINE}
${MOLD_SECRET_KEY_FILE_LINE}

VEEAM_JOB_NAME="${JOB_NAME}"
BACKUP_OFFERING_NAME="${BACKUP_OFFERING_NAME}"
VEEAM_SCHEDULE_NAME="default"
VEEAM_BACKUP_INTERVAL_TYPE="EXTERNAL"
VEEAM_MAX_CHAIN="${MAX_CHAIN}"
ZONE_ID="${ZONE_ID}"
RETENTION_PERIOD="${RETENTION_PERIOD}"

VM_INCLUDE="${VM_INCLUDE:-*}"
VM_EXCLUDE="${VM_EXCLUDE}"
VM_AUTO_EXCLUDE="${VM_AUTO_EXCLUDE:-true}"

VEEAM_URL="${VEEAM_URL}"
VEEAM_USERNAME="${VEEAM_USERNAME}"
VEEAM_PASSWORD="${VEEAM_PASSWORD}"
VEEAM_SSH_HOST="${VEEAM_SSH_HOST}"
VEEAM_SSH_USER="${VEEAM_SSH_USER}"
VEEAM_SSH_KEY="${VEEAM_SSH_KEY}"
RESTORE_SOURCE="auto"
KVM_HOSTNAME="${KVM_HOSTNAME:-}"
RESTORE_WATCH_TRIGGER_MOLD="${RESTORE_WATCH_TRIGGER_MOLD:-true}"
VEEAM_UI_RESTORE_SOURCE="${VEEAM_UI_RESTORE_SOURCE:-mold-only}"
RESTORE_LOCK_DIR="${RESTORE_LOCK_DIR:-}"
VEEAM_RESTORE_WATCH_WINDOW_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-10}"

# Bidirectional (mode C): Mold backup completion -> start matching Veeam Agent job.
VEEAM_TRIGGER_ENABLED="${VEEAM_TRIGGER_ENABLED:-$([[ "${BACKUP_MODE}" == guest ]] && echo true || echo false)}"
VEEAM_TRIGGER_TTL="${VEEAM_TRIGGER_TTL:-1800}"
VEEAM_TRIGGER_METHOD="${VEEAM_TRIGGER_METHOD:-$([[ "${BACKUP_MODE}" == guest ]] && echo ssh || echo auto)}"
VEEAM_API_URL="${VEEAM_API_URL:-}"
VEEAM_API_VERSION="${VEEAM_API_VERSION:-1.2-rev0}"
VEEAM_API_USER="${VEEAM_API_USER:-${VEEAM_USERNAME:-}}"
VEEAM_API_PASSWORD="${VEEAM_API_PASSWORD:-${VEEAM_PASSWORD:-}}"
VM_TARGETS="${VM_TARGETS:-}"
VEEAM_GUEST_JOB_PREFIX="${VEEAM_GUEST_JOB_PREFIX:-Mold VM}"

VEEAM_HOST_BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
STAGING_PATH="${VEEAM_HOST_BACKUP_PATH}"
VEEAM_AGENT_PAYLOAD_PATH="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
VEEAM_BACKUP_MODE="filelevel"
BACKUP_MODE="${BACKUP_MODE}"
IMPORT_MODE="${IMPORT_MODE}"

BACKUP_REPO_TYPE="${BACKUP_REPO_TYPE}"
BACKUP_REPO_NAME="${BACKUP_REPO_NAME}"
BACKUP_REPO_ADDRESS="${BACKUP_REPO_ADDRESS}"
BACKUP_REPO_MOUNT_OPTS="${BACKUP_REPO_MOUNT_OPTS}"
BACKUP_REPO_PROVIDER="${BACKUP_REPO_PROVIDER}"
NAS_REPO_MOUNT="${NAS_REPO_MOUNT}"
BACKUP_STORAGE_MODE="${BACKUP_STORAGE_MODE:-datadisk}"
MOLD_DATADISK_PATH="${MOLD_DATADISK_PATH:-${BACKUP_REPO_ADDRESS}}"

BACKUP_ID=""
VM_UUID="${VM_UUID_CFG}"
VM_NAME="${VM_NAME_CFG}"

CLEANUP_STAGING_AFTER_BACKUP="false"
CLEANUP_STAGING_ON_ERROR="true"

NAS_BACKUP_SCRIPT="/etc/ablestack/veeam/ablestack_veeam_nasbackup.sh"
HOST_EXPORT_SCRIPT="/etc/ablestack/veeam/ablestack_veeam_host_export.sh"
CVT_BACKUP_SCRIPT="${HOST_EXPORT_SCRIPT}"
LOG_FILE="/var/log/mold/veeam-hook.log"
LOG_TAG="mold-veeam-hook"
EOF
chmod 0600 "$CONF"

# Job-specific hook wrappers (NetBackup bpstart_notify.<policy> pattern)
HOOKS_DIR="${ETC_DIR}/hooks"
install -d -m 0755 "${HOOKS_DIR}"
for hook in pre post restore; do
  target="${HOOKS_DIR}/${hook}-notify.${safe_job}"
  cat > "$target" <<EOF
#!/usr/bin/bash
exec "${ETC_DIR}/ablestack_veeam_${hook}_notify.sh" "\$1" "${JOB_NAME}" "\${3:-default}" "\${4:-}"
EOF
  chmod 0755 "$target"
done
target="${HOOKS_DIR}/restore-event.${safe_job}"
cat > "$target" <<EOF
#!/usr/bin/bash
exec "${ETC_DIR}/ablestack_veeam_restore_event.sh" "\$@"
EOF
chmod 0755 "$target"

# Windows UI wrappers (copy to C:\ablestack\veeam on Veeam server, select in Guest Processing).
# Same pattern as ablecube2-pre/post-notify.sh — SSH from Veeam into this KVM.
kvm_ssh_host="${KVM_HOST:-}"
[[ -n "$kvm_ssh_host" ]] || kvm_ssh_host="$(hostname -I 2>/dev/null | awk '{print $1}')"
[[ -n "$kvm_ssh_host" ]] || kvm_ssh_host="$(hostname -s)"
for hook in pre post; do
  win_wrap="${ETC_DIR}/${safe_job}-${hook}-notify.sh"
  cat > "$win_wrap" <<EOF
#!/usr/bin/bash
# Veeam UI ${hook}-job wrapper — place on Veeam as C:\\\\ablestack\\\\veeam\\\\${safe_job}-${hook}-notify.sh
# Args from Veeam: CLIENT JOB [SCHEDULE] [TYPE]
CLIENT="\${1:-\$(hostname -s 2>/dev/null || hostname)}"
JOB="${JOB_NAME}"
SCHEDULE="\${3:-default}"
TYPE="\${4:-}"
exec ssh -o StrictHostKeyChecking=no root@${kvm_ssh_host} \\
  '${ETC_DIR}/ablestack_veeam_${hook}_notify.sh' "\$CLIENT" "\$JOB" "\$SCHEDULE" "\$TYPE"
EOF
  chmod 0755 "$win_wrap"
done

MANIFEST="${ETC_DIR}/${safe_job}.manifest"
cat > "$MANIFEST" <<EOF
# Ablestack Veeam manifest — $(date -Iseconds)
job_name=${JOB_NAME}
vm_include=${VM_INCLUDE}
vm_exclude=${VM_EXCLUDE}
host_backup_path=${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}
agent_payload_path=${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}
backup_mode=${BACKUP_MODE}
kvm_host=${KVM_HOST}
host_export_script=${ETC_DIR}/ablestack_veeam_host_export.sh

# Veeam Agent Job SelectedFiles MUST be ${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}
# (not the Mold stage root). RBD sparse .raw/.rbdiff stay under host_backup_path for Mold restore.
# Guest Processing → Pre/Post: copy Windows wrappers to Veeam C:\\ablestack\\veeam\\ then select:
#   ${ETC_DIR}/${safe_job}-pre-notify.sh
#   ${ETC_DIR}/${safe_job}-post-notify.sh
EOF
if [[ "$CONFIGURE_MOLD" == "true" ]]; then
  # shellcheck source=mold-backup.lib.sh
  source "${SCRIPT_DIR}/mold-backup.lib.sh"
  export MOLD_BACKUP_CONF="$CONF"
  export VEEAM_JOB_NAME="$JOB_NAME"
  export VEEAM_MAX_CHAIN="$MAX_CHAIN"
  export BACKUP_CHAIN_SIZE="$MAX_CHAIN"
  export VEEAM_HOST_BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  export STAGING_PATH="${VEEAM_HOST_BACKUP_PATH}"
  mold_backup_load_config || true
  if mold_backup_cmk_bin >/dev/null 2>&1 || command -v curl >/dev/null 2>&1; then
    mold_backup_api_ensure_global_settings || true
    # Host/datadisk: importBackupOffering(provider=ablestack-veeam, externalid=veeam). No NAS repo.
    if mold_backup_api_ensure_backup_resources >/dev/null; then
      mold_backup_notify_log info "Mold backup offering registered (or already present)"
    else
      mold_backup_notify_log warn "importBackupOffering failed — Admin API key + ZONE_ID + ablestack-veeam provider enabled required"
    fi
  fi
fi

echo "Created:"
echo "  ${CONF}"
echo "  ${MANIFEST}"
echo "  ${HOOKS_DIR}/pre-notify.${safe_job}"
echo "  ${ETC_DIR}/${safe_job}-pre-notify.sh   (copy → Veeam C:\\ablestack\\veeam\\)"
echo "  ${ETC_DIR}/${safe_job}-post-notify.sh  (copy → Veeam C:\\ablestack\\veeam\\)"
echo ""
if [[ "${VEEAM_BACKUP_TARGET:-host}" == "guest" ]]; then
  die "Guest-mode Veeam jobs were removed. Use --backup-target host (datadisk) instead."
else
  echo "Veeam job SelectedFiles (Agent payload): ${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}/"
  echo "Mold stage root (restore artifacts, not for SyncDirs): ${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}/"
  echo "Next: copy ${safe_job}-pre/post-notify.sh to Veeam C:\\ablestack\\veeam\\ and select in Guest Processing"
  echo "FLR→Mold: bash ${ETC_DIR}/enable-veeam-mold-restore.sh"
fi

if [[ "$RUN_INSTALL" == "true" && -x "${SCRIPT_DIR}/install.sh" ]]; then
  ABLESTACK_VEEAM_ETC_DIR="$ETC_DIR" bash "${SCRIPT_DIR}/install.sh"
fi

echo "Done."
