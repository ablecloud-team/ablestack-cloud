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

# Poll Veeam for completed restore sessions and trigger Mold restoreBackup on this KVM host.
# Matches the Veeam UI restore point (FLR) to the corresponding Mold backup_id.
# Invoked by mold-veeam-restore-agent.timer (every 3 min).
#
# Flow: Veeam UI Guest files restore (FLR)
#   → this agent (per job conf: ablecubeN.conf + Agent_Backup_Job_*.conf)
#   → ensure VM Stopped (RESTORE_AUTO_STOP)
#   → exact metadata artifact resolution → Mold restoreBackupArtifact
#
# Manual:
#   bash /usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/veeam/mold-veeam-restore-agent.sh
#   bash /usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/veeam/mold-backup.sh restore-watch --job 'ablecube2' --trigger-mold --since-min 30

# Avoid set -e: Stopped VMs make virsh fail and would abort the FLR→Mold path.
set -uo pipefail

ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ETC_DIR}/mold-backup.env"
SINCE_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-30}"
MOLD_BACKUP_SH="${SCRIPT_DIR}/mold-backup.sh"

[[ -f "$ENV_FILE" ]] && { set -a; # shellcheck source=/dev/null
  source "$ENV_FILE"; set +a; }
STATE_DIR="${ABLESTACK_VEEAM_STATE_DIR:-/var/lib/ablestack/veeam}"
export ABLESTACK_VEEAM_STATE_DIR="$STATE_DIR"

# Unset stale KVM_HOSTNAME from env so ownership uses live hostname.
unset KVM_HOSTNAME || true
# Do not inherit a forced single-job conf from a stale systemd Environment= line
# unless the operator explicitly set MOLD_BACKUP_CONF_FORCE=true.
if [[ "${MOLD_BACKUP_CONF_FORCE:-false}" != "true" ]]; then
  unset MOLD_BACKUP_CONF || true
fi

mkdir -p "${STATE_DIR}/events" "${STATE_DIR}/registry" "${STATE_DIR}/state" 2>/dev/null || true

job_name_from_conf() {
  # Generated configurations are root-owned shell assignments, including quoted Job names.
  ( unset VEEAM_JOB_NAME; source "$1"; printf '%s' "${VEEAM_JOB_NAME:-}" )
}

run_restore_watch() {
  local conf="$1" job
  job="$(job_name_from_conf "$conf")"
  [[ -n "$job" ]] || {
    echo "[restore-agent] skip $(basename "$conf"): no VEEAM_JOB_NAME/JOB_NAME" >&2
    return 0
  }
  echo "=== restore-watch job=${job} conf=$(basename "$conf") window=${SINCE_MIN}min trigger_mold=true host=$(hostname -s) ==="
  # Each Job has its own authentication and metadata restore identity.
  export MOLD_BACKUP_CONF="$conf"
  export VEEAM_JOB_NAME="$job"
  export RESTORE_WATCH_TRIGGER_MOLD=true
  # The timer service only bridges completed Veeam restores into Mold. The
  # management async job and detached host restore own the long-running work.
  export MOLD_RESTORE_ASYNC_DETACH=true
  # Ensure stop-before-restore unless the job conf explicitly disables it.
  if ! grep -qE '^RESTORE_AUTO_STOP=' "$conf" 2>/dev/null; then
    export RESTORE_AUTO_STOP=true
  fi
  bash "${MOLD_BACKUP_SH}" restore-watch \
    --job "$job" \
    --since-min "${SINCE_MIN}" \
    --trigger-mold || true
}

# Every configured Job is watched, including custom names. Backup copies do not end in .conf.
declare -a job_confs=()
shopt -s nullglob
for conf in "${ETC_DIR}"/*.conf; do
  [[ -n "$(job_name_from_conf "$conf")" ]] && job_confs+=("$conf")
done
shopt -u nullglob
if [[ ${#job_confs[@]} -eq 0 ]]; then
  echo "[restore-agent] ERROR: no Job conf; run veeam-host-config.py" >&2
  exit 1
fi

for conf in "${job_confs[@]}"; do
  run_restore_watch "$conf"
done

echo "=== mold-backup.sh restore-watch done ==="
if [[ -d "${STATE_DIR}/registry" ]]; then
  echo "=== restore registry (last 10) ==="
  find "${STATE_DIR}/registry" -name '*.log' -type f -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn | head -3 | while read -r _ p; do
        tail -n 5 "$p" 2>/dev/null || true
      done
fi
if [[ -f "${STATE_DIR}/events/restore.log" ]]; then
  echo "=== restore events (last 10) ==="
  tail -n 10 "${STATE_DIR}/events/restore.log" 2>/dev/null || true
fi
