#!/usr/bin/bash
# Poll Veeam for completed restore sessions and trigger Mold restoreBackup on this KVM host.
# Matches the Veeam UI restore point (FLR) to the corresponding Mold backup_id.
# Invoked by mold-veeam-restore-agent.timer (every 3 min).
#
# Flow: Veeam UI Guest files restore (FLR) on host job → this agent → Mold datadisk restore
#
# Manual:
#   bash /etc/ablestack/veeam/mold-veeam-restore-agent.sh
#   bash /etc/ablestack/veeam/mold-backup.sh restore-watch --job 'Mold ablecube31-2' --trigger-mold --since-min 30

set -euo pipefail

ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
ENV_FILE="${ETC_DIR}/mold-backup.env"
HOST_CONF="${MOLD_BACKUP_CONF:-${ETC_DIR}/Mold_Host_Backup.conf}"
SINCE_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-10}"

[[ -f "$ENV_FILE" ]] && { set -a; # shellcheck source=/dev/null
  source "$ENV_FILE"; set +a; }

export MOLD_BACKUP_CONF="$HOST_CONF"
mkdir -p "${ETC_DIR}/events" "${ETC_DIR}/registry" "${ETC_DIR}/state" 2>/dev/null || true

job=""
if [[ -f "$HOST_CONF" ]]; then
  job="$(grep -E '^VEEAM_JOB_NAME=' "$HOST_CONF" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
fi
job="${job:-${VEEAM_JOB_NAME:-Mold ablecube31-2}}"

exec "${ETC_DIR}/mold-backup.sh" restore-watch \
  --job "$job" \
  --since-min "${SINCE_MIN}" \
  --trigger-mold
