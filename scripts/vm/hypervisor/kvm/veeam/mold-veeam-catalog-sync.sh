#!/usr/bin/bash
# Detect Veeam "Remove from Disk" (restore point gone) and trigger MS
# syncAblestackVeeamBackups for VMs in VM_INCLUDE / VEEAM_RESTORE_VM
# (NetBackup-style catalog delete — Mold UI cannot delete Veeam backups).
#
# Invoked by mold-veeam-catalog-sync.timer (every 1–2 min) for near-immediate Mold sync.
#
# Manual:
#   bash /etc/ablestack/veeam/mold-veeam-catalog-sync.sh
#   bash /etc/ablestack/veeam/mold-backup.sh catalog-sync --job ablecube2

set -uo pipefail

ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
MOLD_BACKUP_SH="${ETC_DIR}/mold-backup.sh"

[[ -f "$ENV_FILE" ]] && { set -a; # shellcheck source=/dev/null
  source "$ENV_FILE"; set +a; }

unset KVM_HOSTNAME || true
if [[ "${MOLD_BACKUP_CONF_FORCE:-false}" != "true" ]]; then
  unset MOLD_BACKUP_CONF || true
fi

job_name_from_conf() {
  local conf="$1" job
  job="$(grep -E '^VEEAM_JOB_NAME=' "$conf" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
  [[ -z "$job" ]] && job="$(grep -E '^JOB_NAME=' "$conf" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
  echo "$job"
}

run_one() {
  local conf="$1" job
  job="$(job_name_from_conf "$conf")"
  [[ -n "$job" ]] || return 0
  echo "=== catalog-sync job=${job} conf=$(basename "$conf") host=$(hostname -s) ==="
  export MOLD_BACKUP_CONF="$conf"
  export VEEAM_JOB_NAME="$job"
  export VEEAM_CATALOG_DELETE_SYNC="${VEEAM_CATALOG_DELETE_SYNC:-true}"
  if [[ -x "$MOLD_BACKUP_SH" ]]; then
    "${MOLD_BACKUP_SH}" catalog-sync --job "$job" || true
  else
    # shellcheck source=/dev/null
    source "${ETC_DIR}/mold-backup.lib.sh"
    mold_backup_load_config || true
    mold_backup_catalog_delete_sync "$job" || true
  fi
}

hn="$(hostname -s 2>/dev/null || hostname)"
hn="${hn%%.*}"
declare -a confs=()
[[ -f "${ETC_DIR}/${hn}.conf" ]] && confs+=("${ETC_DIR}/${hn}.conf")

# Host-named conf first; do not scan every Agent_Backup_Job_*.conf for deletes
# unless explicitly enabled (those may share the cube but different VM sets).
if [[ "${VEEAM_CATALOG_SYNC_ALL_CONFS:-false}" == "true" ]]; then
  shopt -s nullglob
  for conf in "${ETC_DIR}"/Agent_Backup_Job_*.conf; do
    confs+=("$conf")
  done
  shopt -u nullglob
fi

if [[ ${#confs[@]} -eq 0 ]]; then
  echo "[catalog-sync] ERROR: no ${hn}.conf under ${ETC_DIR}" >&2
  exit 1
fi

for conf in "${confs[@]}"; do
  run_one "$conf"
done

echo "=== mold-veeam-catalog-sync done ==="
