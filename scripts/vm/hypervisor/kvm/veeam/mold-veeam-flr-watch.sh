#!/usr/bin/bash
# Immediate Veeam FLR → Mold restore on this KVM host.
# Watches VEEAM_AGENT_PAYLOAD_PATH (/tmp/mold/veeam-agent/<vm>/current) for
# stable file changes after Veeam UI Guest File restore, then calls Mold restore.
# Backup publish is ignored via .mold-agent-publish + veeam-active markers.
#
# Runs as mold-veeam-flr-watch.service (long-lived). The 3-minute restore-agent
# timer remains as a SSH-session fallback.

set -euo pipefail

ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
INTERVAL_SEC="${FLR_WATCH_INTERVAL_SEC:-1}"

[[ -f "$ENV_FILE" ]] && { set -a; # shellcheck source=/dev/null
  source "$ENV_FILE"; set +a; }

# Prefer host-named conf for defaults; lib resolves per-VM conf on trigger.
if [[ -z "${MOLD_BACKUP_CONF:-}" ]]; then
  if [[ -f "${ETC_DIR}/$(hostname -s).conf" ]]; then
    export MOLD_BACKUP_CONF="${ETC_DIR}/$(hostname -s).conf"
  elif [[ -f "${ETC_DIR}/Mold_Host_Backup.conf" ]]; then
    export MOLD_BACKUP_CONF="${ETC_DIR}/Mold_Host_Backup.conf"
  fi
fi

# shellcheck source=mold-backup.lib.sh
source "${ETC_DIR}/mold-backup.lib.sh"
mold_backup_load_config || true
mold_backup_resolve_api_secret || true

BASE="${VEEAM_AGENT_PAYLOAD_PATH:-/tmp/mold/veeam-agent}"
mkdir -p "$BASE" "$(mold_backup_state_dir)/flr-watch" 2>/dev/null || true

mold_backup_notify_log info "flr-watch: start host=$(hostname -s) path=${BASE} interval=${INTERVAL_SEC}s"

# Seed fingerprints so existing payload does not look like a fresh FLR.
_seed_vm() {
  local vm_dir="$1" vm publish fp fp_file ckpt ckpt_file
  vm="$(basename "$vm_dir")"
  [[ "$vm" == i-*-VM ]] || return 0
  publish="${vm_dir}/current"
  [[ -d "$publish" ]] || return 0
  fp="$(mold_backup_agent_payload_fingerprint "$publish")"
  [[ -n "$fp" ]] || return 0
  fp_file="$(mold_backup_state_dir)/flr-watch/${vm}.fp"
  echo "$fp" >"$fp_file"
  ckpt="$(mold_backup_agent_payload_selected_checkpoint "$vm" 2>/dev/null || true)"
  if [[ -n "$ckpt" ]]; then
    ckpt_file="$(mold_backup_state_dir)/flr-watch/${vm}.ckpt"
    echo "$ckpt" >"$ckpt_file"
  fi
  rm -f "$(mold_backup_state_dir)/flr-watch/${vm}.pending" 2>/dev/null || true
}
shopt -s nullglob
for _d in "${BASE}"/*; do
  [[ -d "$_d" ]] && _seed_vm "$_d"
done
shopt -u nullglob
mold_backup_notify_log info "flr-watch: seeded existing agent payload fingerprints"

while true; do
  mold_backup_scan_agent_payload_flr || true
  sleep "${INTERVAL_SEC}"
done
