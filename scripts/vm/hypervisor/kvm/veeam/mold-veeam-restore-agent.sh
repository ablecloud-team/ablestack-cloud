#!/usr/bin/bash
# Poll Veeam for completed restore sessions and trigger Mold restoreBackup on this KVM host.
# Matches the Veeam UI restore point (FLR) to the corresponding Mold backup_id.
# Invoked by mold-veeam-restore-agent.timer (every 3 min).
#
# Flow: Veeam UI Guest files restore (FLR)
#   → this agent (per job conf: ablecubeN.conf + Agent_Backup_Job_*.conf)
#   → ensure VM Stopped (RESTORE_AUTO_STOP)
#   → Mold restoreAblestackVeeamBackup
#
# Manual:
#   bash /etc/ablestack/veeam/mold-veeam-restore-agent.sh
#   bash /etc/ablestack/veeam/mold-backup.sh restore-watch --job 'ablecube2' --trigger-mold --since-min 30

# Avoid set -e: Stopped VMs make virsh fail and would abort the FLR→Mold path.
set -uo pipefail

ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
SINCE_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-30}"
MOLD_BACKUP_SH="${ETC_DIR}/mold-backup.sh"

[[ -f "$ENV_FILE" ]] && { set -a; # shellcheck source=/dev/null
  source "$ENV_FILE"; set +a; }

# Unset stale KVM_HOSTNAME from env so ownership uses live hostname.
unset KVM_HOSTNAME || true
# Do not inherit a forced single-job conf from a stale systemd Environment= line
# unless the operator explicitly set MOLD_BACKUP_CONF_FORCE=true.
if [[ "${MOLD_BACKUP_CONF_FORCE:-false}" != "true" ]]; then
  unset MOLD_BACKUP_CONF || true
fi

mkdir -p "${ETC_DIR}/events" "${ETC_DIR}/registry" "${ETC_DIR}/state" 2>/dev/null || true

job_name_from_conf() {
  local conf="$1" job
  job="$(grep -E '^VEEAM_JOB_NAME=' "$conf" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
  [[ -z "$job" ]] && job="$(grep -E '^JOB_NAME=' "$conf" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
  echo "$job"
}

run_restore_watch() {
  local conf="$1" job
  job="$(job_name_from_conf "$conf")"
  [[ -n "$job" ]] || {
    echo "[restore-agent] skip $(basename "$conf"): no VEEAM_JOB_NAME/JOB_NAME" >&2
    return 0
  }
  echo "=== restore-watch job=${job} conf=$(basename "$conf") window=${SINCE_MIN}min trigger_mold=true host=$(hostname -s) ==="
  # Each job conf sets VM_INCLUDE / VEEAM_RESTORE_VM / RESTORE_AUTO_STOP for that chain.
  export MOLD_BACKUP_CONF="$conf"
  export VEEAM_JOB_NAME="$job"
  export RESTORE_WATCH_TRIGGER_MOLD=true
  export RESTORE_RP_TIME_MATCH="${RESTORE_RP_TIME_MATCH:-true}"
  # Ensure stop-before-restore unless the job conf explicitly disables it.
  if ! grep -qE '^RESTORE_AUTO_STOP=' "$conf" 2>/dev/null; then
    export RESTORE_AUTO_STOP=true
  fi
  "${MOLD_BACKUP_SH}" restore-watch \
    --job "$job" \
    --since-min "${SINCE_MIN}" \
    --trigger-mold || true
}

# Collect unique job confs:
#  1) hostname.conf (ablecube2.conf) — primary host Agent job for mold-native RBD
#  2) Agent_Backup_Job_*.conf — additional guest/host chains on the same cube
#  3) legacy single conf fallback
declare -a job_confs=()
declare -A seen_conf=()

add_conf() {
  local c="$1"
  [[ -f "$c" ]] || return 0
  [[ -n "${seen_conf[$c]:-}" ]] && return 0
  seen_conf[$c]=1
  job_confs+=("$c")
}

hn="$(hostname -s 2>/dev/null || hostname)"
hn="${hn%%.*}"
add_conf "${ETC_DIR}/${hn}.conf"

shopt -s nullglob
for conf in "${ETC_DIR}"/Agent_Backup_Job_*.conf; do
  add_conf "$conf"
done
shopt -u nullglob

if [[ ${#job_confs[@]} -eq 0 ]]; then
  if [[ -n "${MOLD_BACKUP_CONF:-}" && -f "${MOLD_BACKUP_CONF}" ]]; then
    add_conf "${MOLD_BACKUP_CONF}"
  elif [[ -f "${ETC_DIR}/mold-backup.conf" ]]; then
    add_conf "${ETC_DIR}/mold-backup.conf"
  elif [[ -f "${ETC_DIR}/Mold_Host_Backup.conf" ]]; then
    add_conf "${ETC_DIR}/Mold_Host_Backup.conf"
  else
    echo "[restore-agent] ERROR: no job conf under ${ETC_DIR}" >&2
    exit 1
  fi
fi

for conf in "${job_confs[@]}"; do
  run_restore_watch "$conf"
done

echo "=== mold-backup.sh restore-watch done ==="
if [[ -d "${ETC_DIR}/registry" ]]; then
  echo "=== restore registry (last 10) ==="
  find "${ETC_DIR}/registry" -name '*.log' -type f -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn | head -3 | while read -r _ p; do
        tail -n 5 "$p" 2>/dev/null || true
      done
fi
if [[ -f "${ETC_DIR}/events/restore.log" ]]; then
  echo "=== restore events (last 10) ==="
  tail -n 10 "${ETC_DIR}/events/restore.log" 2>/dev/null || true
fi
