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

# One-shot KVM host setup for Ablestack Veeam (hooks + env + datadisk + restore agent).
#
#   bash setup-ablestack-veeam.sh \
#     --api-key KEY --api-secret 'SECRET' \
#     --veeam-host 192.168.1.240 --veeam-password 'Ablecloud1!' \
#     --job-name 'Mold ablecube4'
# Optional: --datadisk-path /data/backup --offering-name VeeamBackup
#           --vm-exclude scvm --backup-chain-size 10
#
# Does not create the Veeam Agent Job or register Pre/Post (do that once in Veeam UI).
# Catalog / Remove-from-Disk sync is MS BackupSyncTask.syncBackups() — no host catalog-sync timer.

set -euo pipefail
set +H

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
DATADISK_PATH=""
SKIP_INSTALL=false
BOOTSTRAP_ARGS=()

die() { echo "ERROR: $*" >&2; exit 1; }

usage() {
  sed -n '19,30p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --datadisk-path) DATADISK_PATH="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --skip-install) SKIP_INSTALL=true; shift ;;
    --api-key|--api-secret|--job-name|--offering-name|--zone-id|--mold-url|--kvm-ip|--kvm-hostname|--veeam-host|--veeam-user|--veeam-password|--veeam-ssh-user|--vm-exclude|--backup-chain-size|--max-chain|--host-backup-path|--stage-root-path|--agent-payload-path|--env-out)
      BOOTSTRAP_ARGS+=("$1" "$2")
      shift 2
      ;;
    --no-auto-exclude)
      BOOTSTRAP_ARGS+=("$1")
      shift
      ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

has_bootstrap_cred=false
for ((i = 0; i < ${#BOOTSTRAP_ARGS[@]}; i++)); do
  [[ "${BOOTSTRAP_ARGS[$i]}" == "--api-key" ]] && has_bootstrap_cred=true
done

echo "=== 1/3 install Veeam hooks ==="
if [[ "$SKIP_INSTALL" != "true" ]]; then
  bash "${SCRIPT_DIR}/install.sh"
else
  echo "skip install.sh"
fi

echo "=== 2/3 env + job conf ==="
if [[ "$has_bootstrap_cred" == "true" ]]; then
  # Keep bootstrap output on the same env file used by datadisk setup.
  if [[ " ${BOOTSTRAP_ARGS[*]} " != *" --env-out "* ]]; then
    BOOTSTRAP_ARGS+=(--env-out "$ENV_FILE")
  fi
  bash "${SCRIPT_DIR}/bootstrap-host-veeam-env.sh" "${BOOTSTRAP_ARGS[@]}"
elif [[ -f "$ENV_FILE" ]]; then
  echo "Using existing ${ENV_FILE} (no --api-key; skip bootstrap)"
else
  die "Need --api-key/--api-secret (or an existing ${ENV_FILE})"
fi

echo "=== 3/3 datadisk + restore agent ==="
datadisk_args=(--env-file "$ENV_FILE")
[[ -n "$DATADISK_PATH" ]] && datadisk_args+=(--datadisk-path "$DATADISK_PATH")
bash "${SCRIPT_DIR}/setup-datadisk-veeam-backup.sh" "${datadisk_args[@]}"

echo ""
echo "=== Ablestack Veeam host setup done ==="
echo "  env     : ${ENV_FILE}"
echo "  restore : mold-veeam-restore-agent.timer (Veeam FLR → Mold restoreBackup)"
echo "  catalog : MS BackupSyncTask.syncBackups() (no host catalog-sync timer)"
echo ""
echo "Veeam UI (once per host Job):"
echo "  1) Agent Job SelectedFiles: /tmp/mold/veeam-agent"
echo "  2) Guest Processing Pre : ${ETC_DIR}/ablestack_veeam_pre_notify.sh"
echo "  3) Guest Processing Post: ${ETC_DIR}/ablestack_veeam_post_notify.sh"
echo "  4) Assign VeeamBackup offering in Mold UI"
