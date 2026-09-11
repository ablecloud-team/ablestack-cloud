#!/usr/bin/bash
# Enable Veeam UI restore → Mold datadisk restore chain on this KVM host.
#
#   bash enable-veeam-mold-restore.sh
#   bash enable-veeam-mold-restore.sh --vm-include 'i-2-62-VM,i-2-64-VM'
#   bash enable-veeam-mold-restore.sh --restore-vm i-2-62-VM   # FLR 시 복원할 VM (host job, 다중 VM일 때)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
HOST_CONF="${ETC_DIR}/Mold_Host_Backup.conf"
VM_INCLUDE_ARG=""
RESTORE_VM=""

die() { echo "ERROR: $*" >&2; exit 1; }

SHARE_DIR="${MOLD_BACKUP_SHARE_DIR:-/usr/share/mold/backup/veeam}"

safe_install() {
  local mode="$1" src="$2" dst="$3"
  [[ -f "$src" ]] || die "Missing source file: $src"
  if [[ -e "$src" && -e "$dst" ]] && [[ "$(stat -c '%d:%i' "$src" 2>/dev/null)" == "$(stat -c '%d:%i' "$dst" 2>/dev/null)" ]]; then
    chmod "$mode" "$dst" 2>/dev/null || true
    return 0
  fi
  install -m "$mode" "$src" "$dst"
}

resolve_bundle_file() {
  local name="$1" d
  for d in "${SCRIPT_DIR}" "${ETC_DIR}" "${SHARE_DIR}" "/tmp/veeam-install"; do
    [[ -f "${d}/${name}" ]] && { echo "${d}/${name}"; return 0; }
  done
  if [[ -f "/etc/systemd/system/${name}" ]]; then
    echo "/etc/systemd/system/${name}"
    return 0
  fi
  die "Missing ${name} — run: bash ${ETC_DIR}/install.sh  or push-to-kvm.sh from Git repo"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --vm-include) VM_INCLUDE_ARG="$2"; shift 2 ;;
    --restore-vm) RESTORE_VM="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *) die "Unknown: $1" ;;
  esac
done

[[ -f "$HOST_CONF" ]] || die "Missing ${HOST_CONF} — run setup-datadisk-veeam-backup.sh first"

# shellcheck source=/dev/null
[[ -f "$ENV_FILE" ]] && set -a && source "$ENV_FILE" && set +a

set_kv() {
  local k="$1" v="$2" f="$HOST_CONF"
  if grep -q "^${k}=" "$f" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=\"${v}\"|" "$f"
  else
    echo "${k}=\"${v}\"" >>"$f"
  fi
}

job="${VEEAM_JOB_NAME:-Mold ablecube31-2}"
[[ -n "$VM_INCLUDE_ARG" ]] && set_kv VM_INCLUDE "$VM_INCLUDE_ARG"
[[ -n "$RESTORE_VM" ]] && set_kv VEEAM_RESTORE_VM "$RESTORE_VM"

set_kv RESTORE_WATCH_TRIGGER_MOLD true
set_kv VEEAM_UI_RESTORE_SOURCE mold-only
set_kv RESTORE_SOURCE mold-only
set_kv BACKUP_MODE host
set_kv VEEAM_JOB_NAME "$job"
set_kv VEEAM_RESTORE_WATCH_WINDOW_MIN "${VEEAM_RESTORE_WATCH_WINDOW_MIN:-10}"
[[ -n "${VEEAM_SSH_HOST:-}" ]] && set_kv VEEAM_SSH_HOST "$VEEAM_SSH_HOST"

echo "=== Mold Veeam restore agent (KVM) ==="
grep -E 'VEEAM_JOB|VM_INCLUDE|VEEAM_RESTORE_VM|RESTORE_|VEEAM_SSH|BACKUP_MODE' "$HOST_CONF" || true

bash "${SCRIPT_DIR}/install.sh" 2>/dev/null || bash "${ETC_DIR}/install.sh" 2>/dev/null || true

mkdir -p "${ETC_DIR}/events" "${ETC_DIR}/registry" "${ETC_DIR}/state" 2>/dev/null || true
touch "${ETC_DIR}/events/restore.log" 2>/dev/null || true

agent_sh="$(resolve_bundle_file mold-veeam-restore-agent.sh)"
agent_svc="$(resolve_bundle_file mold-veeam-restore-agent.service)"
agent_timer="$(resolve_bundle_file mold-veeam-restore-agent.timer)"

safe_install 0755 "$agent_sh" "${ETC_DIR}/mold-veeam-restore-agent.sh"
safe_install 0644 "$agent_svc" "${ETC_DIR}/mold-veeam-restore-agent.service"
safe_install 0644 "$agent_timer" "${ETC_DIR}/mold-veeam-restore-agent.timer"
safe_install 0644 "$agent_svc" /etc/systemd/system/mold-veeam-restore-agent.service
safe_install 0644 "$agent_timer" /etc/systemd/system/mold-veeam-restore-agent.timer

systemctl daemon-reload
# Legacy timer without --trigger-mold — disable to avoid duplicate polls.
systemctl disable mold-veeam-restore-watch.timer 2>/dev/null || true
systemctl stop mold-veeam-restore-watch.timer 2>/dev/null || true
systemctl enable mold-veeam-restore-agent.timer
systemctl start mold-veeam-restore-agent.timer

echo ""
echo "Enabled: mold-veeam-restore-agent.timer ($(systemctl is-active mold-veeam-restore-agent.timer 2>/dev/null || echo unknown))"
echo ""
cat <<EOF
=== Veeam UI → Mold restore (operator) ===
  1) Mold UI: 대상 VM **Stopped**
  2) Veeam UI: Backups → job "${job}" → 원하는 **Restore Point** 선택 → Restore → Guest files (FLR)
  3) ≤3분 대기 (timer) 또는 수동:
       bash ${ETC_DIR}/mold-veeam-restore-agent.sh
     → Veeam 세션의 restore point id로 Mold backup_id를 찾아 restoreBackup 호출
  4) 로그: tail -f ${ETC_DIR}/events/restore.log
           tail -f /var/log/mold/veeam-hook.log
  5) Mold UI: VM Start

다중 VM 호스트 job: FLR 대상 VM 지정
  bash $0 --restore-vm i-2-62-VM --vm-include 'i-2-62-VM,i-2-64-VM'

EOF
