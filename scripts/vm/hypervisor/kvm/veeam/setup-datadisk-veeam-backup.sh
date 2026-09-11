#!/usr/bin/bash
# Mold datadisk (KVM) setup for Veeam host/datadisk + FLR→Mold restore.
#
# Architecture (KVM hypervisor, e.g. ablecube31-2):
#   - Mold qcow2/rbd on KVM data disk (BACKUP_REPO_TYPE=local — no Mold Veeam/NAS repo)
#   - BACKUP_MODE=host: pre/post on KVM export VM disks
#   - Veeam Job / repository: configure in Veeam UI (no PowerShell automation in this package)
#   - Veeam UI Guest files (FLR) → KVM restore agent → Mold restoreBackup
#
# Run on KVM host:
#   bash setup-datadisk-veeam-backup.sh --env-file /etc/ablestack/veeam/mold-backup.env
#   bash setup-datadisk-veeam-backup.sh --datadisk-path /data/backup --kvm-hostname ablecube31-2

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
DATADISK_PATH=""
KVM_HOSTNAME_OVERRIDE=""

die() { echo "ERROR: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --datadisk-path) DATADISK_PATH="$2"; shift 2 ;;
    --kvm-hostname) KVM_HOSTNAME_OVERRIDE="$2"; shift 2 ;;
    --skip-veeam-repo|--register-scripts-only)
      echo "WARN: $1 ignored (Veeam repo/PowerShell automation removed)"
      shift
      ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *) die "Unknown: $1" ;;
  esac
done

[[ -f "$ENV_FILE" ]] || die "Missing $ENV_FILE"
# shellcheck source=/dev/null
set -a && source "$ENV_FILE" && set +a

COMMON_LIB="${SCRIPT_DIR}/mold-guest-common.sh"
[[ -f "$COMMON_LIB" ]] || COMMON_LIB="${ETC_DIR}/mold-guest-common.sh"
[[ -f "$COMMON_LIB" ]] || die "mold-guest-common.sh not found"
MOLD_GUEST_SCRIPT_DIR="$SCRIPT_DIR"
# shellcheck source=mold-guest-common.sh
source "$COMMON_LIB"

# shellcheck source=mold-backup.lib.sh
source "${SCRIPT_DIR}/mold-backup.lib.sh"

host="${KVM_HOSTNAME_OVERRIDE:-${KVM_HOSTNAME:-$(hostname -s)}}"
kvm_ip="$(mold_guest_resolve_kvm_ip_from_env "$ENV_FILE" 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
disk="${DATADISK_PATH:-${MOLD_DATADISK_PATH:-${BACKUP_REPO_ADDRESS:-/data/backup}}}"
if [[ -z "$DATADISK_PATH" && "$disk" == *glue-gfs* ]]; then
  if [[ -d /data/backup ]]; then
    echo "WARN: GFS path ${disk} → /data/backup (datadisk mode; no NAS restore)"
    disk="/data/backup"
  else
    echo "WARN: BACKUP_REPO still on glue-gfs (${disk}). Create /data/backup and re-run:"
    echo "      $0 --datadisk-path /data/backup --env-file ${ENV_FILE}"
  fi
fi
job_name="${VEEAM_JOB_NAME:-Mold ${host}}"
host_conf="${ETC_DIR}/Mold_Host_Backup.conf"

echo "=== Mold datadisk setup (no Veeam repository / PowerShell) ==="
echo "  KVM host     : ${host} (${kvm_ip})"
echo "  Veeam job    : ${job_name} (create/register Pre/Post in Veeam UI)"
echo "  Datadisk path: ${disk}"

mkdir -p "$disk"
chmod 0755 "$disk" 2>/dev/null || true

mold_host_ensure_conf

set_kv() {
  local k="$1" v="$2" f="$host_conf"
  if grep -q "^${k}=" "$f" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=\"${v}\"|" "$f"
  else
    echo "${k}=\"${v}\"" >>"$f"
  fi
}

for f in "$host_conf" "${ETC_DIR}/mold-backup.conf"; do
  [[ -f "$f" ]] || continue
  host_conf="$f"
  set_kv BACKUP_STORAGE_MODE datadisk
  set_kv BACKUP_REPO_TYPE local
  set_kv BACKUP_REPO_PROVIDER localfs
  set_kv BACKUP_REPO_NAME "Ablestack Data Disk"
  set_kv MOLD_DATADISK_PATH "$disk"
  set_kv BACKUP_REPO_ADDRESS "$disk"
  set_kv NAS_REPO_MOUNT ""
  set_kv BACKUP_MODE host
  set_kv KVM_HOSTNAME "$host"
  set_kv KVM_HOST "$kvm_ip"
  set_kv VEEAM_JOB_NAME "$job_name"
  set_kv VEEAM_HOST_BACKUP_PATH "${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  set_kv KVM_PRE_NOTIFY_SCRIPT "${ETC_DIR}/ablestack_veeam_pre_notify.sh"
  set_kv KVM_POST_NOTIFY_SCRIPT "${ETC_DIR}/ablestack_veeam_post_notify.sh"
  set_kv RESTORE_SOURCE mold-only
  set_kv VEEAM_UI_RESTORE_SOURCE mold-only
  set_kv RESTORE_WATCH_TRIGGER_MOLD true
  set_kv BACKUP_STORAGE_ENGINE "${BACKUP_STORAGE_ENGINE:-auto}"
done

env_sync_kv() {
  local k="$1" v="$2"
  if grep -q "^${k}=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=\"${v}\"|" "$ENV_FILE"
  else
    echo "${k}=\"${v}\"" >>"$ENV_FILE"
  fi
}
env_sync_kv BACKUP_STORAGE_MODE datadisk
env_sync_kv BACKUP_REPO_TYPE local
env_sync_kv BACKUP_REPO_PROVIDER localfs
env_sync_kv BACKUP_REPO_NAME "Ablestack Data Disk"
env_sync_kv MOLD_DATADISK_PATH "$disk"
env_sync_kv BACKUP_REPO_ADDRESS "$disk"
env_sync_kv BACKUP_MODE host
env_sync_kv RESTORE_SOURCE mold-only
echo "Synced ${ENV_FILE}: BACKUP_REPO_ADDRESS=${disk}"

grep -E 'BACKUP_MODE|BACKUP_STORAGE|DATADISK|BACKUP_REPO|VEEAM_JOB|VEEAM_HOST|KVM_HOST|KVM_PRE|KVM_POST|RESTORE_' "$host_conf" || true

echo "=== KVM pre/post scripts (ablestack_veeam_*_notify.sh) ==="
mold_host_ensure_kvm_prepost_scripts

echo "=== Enable restore agent (Veeam FLR → Mold datadisk restore) ==="
bash "${SCRIPT_DIR}/enable-veeam-mold-restore.sh" --env-file "$ENV_FILE" \
  ${VM_INCLUDE:+--vm-include "$VM_INCLUDE"} 2>/dev/null \
  || {
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable mold-veeam-restore-agent.timer 2>/dev/null || true
    systemctl start mold-veeam-restore-agent.timer 2>/dev/null || true
    systemctl is-active mold-veeam-restore-agent.timer 2>/dev/null || echo "(timer not installed — run install.sh)"
  }

cat <<EOF

=== Done ===
Target      : KVM hypervisor ${host} (${kvm_ip})
Mold backup : datadisk ${disk} (no Mold Veeam/NAS repository)
Pre/Post   : ${ETC_DIR}/ablestack_veeam_pre_notify.sh | post_notify.sh
Restore     : Veeam FLR → restore agent → Mold restoreBackup (mold-only)

Next:
  1) Assign backup offering in Mold UI (no addBackupRepository)
  2) ${ETC_DIR}/veeam_config.sh --job-name '${job_name}' --backup-mode host --vm-include 'i-2-XX-VM'
  3) In Veeam UI: create Agent job for this KVM; Guest Processing Pre/Post → scripts above
  4) FLR→Mold: ${ETC_DIR}/enable-veeam-mold-restore.sh --vm-include 'i-2-XX-VM'
  5) After backup: cat ${ETC_DIR}/registry/<vm>.latest-backup-id

EOF
