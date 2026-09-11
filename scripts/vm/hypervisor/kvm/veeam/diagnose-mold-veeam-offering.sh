#!/usr/bin/bash
# Diagnose / register Veeam backup offering against whatever provider MS actually has.
#   bash /etc/ablestack/veeam/diagnose-mold-veeam-offering.sh
#   bash /etc/ablestack/veeam/diagnose-mold-veeam-offering.sh --import
set -euo pipefail
set +H

DO_IMPORT=false
[[ "${1:-}" == "--import" ]] && DO_IMPORT=true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
# shellcheck source=mold-backup.lib.sh
source "${SCRIPT_DIR}/mold-backup.lib.sh"

export VEEAM_JOB_NAME="${VEEAM_JOB_NAME:-Agent Backup Job 1}"
if [[ -f "${ETC_DIR}/mold-backup.env" ]]; then
  # shellcheck source=/dev/null
  set -a && source "${ETC_DIR}/mold-backup.env" && set +a
fi
mold_backup_load_config 2>/dev/null || true

echo "=== Config (before fix) ==="
echo "  MOLD_API_URL=${MOLD_API_URL:-}"
echo "  ZONE_ID=${ZONE_ID:-}"
echo "  API key prefix: ${MOLD_API_KEY:0:12}..."

echo ""
echo "=== listZones (fix ZONE_ID) ==="
mold_backup_api_ensure_zone_id || true
echo "  ZONE_ID=${ZONE_ID:-}"

echo ""
echo "=== listBackupProviders → pick provider ==="
VEEAM_PROVIDER_NAME="$(mold_backup_api_detect_veeam_provider)"
export VEEAM_PROVIDER_NAME
echo "  PROVIDER=${VEEAM_PROVIDER_NAME}"
mold_backup_cmk_run listBackupProviders 2>&1 | head -c 1500 || true

echo ""
echo "=== listBackupProviderOfferings provider=${VEEAM_PROVIDER_NAME} ==="
mold_backup_cmk_run listBackupProviderOfferings "provider=${VEEAM_PROVIDER_NAME}" "zoneid=${ZONE_ID}" 2>&1 | head -c 2500 || true
OFFERING_EXTERNAL_ID="$(mold_backup_api_pick_offering_external_id "${VEEAM_PROVIDER_NAME}")"
export OFFERING_EXTERNAL_ID
echo "  picked externalid=${OFFERING_EXTERNAL_ID}"
if [[ "$OFFERING_EXTERNAL_ID" == "8a4d0113-529b-40eb-9b9d-59e0d3f2d69d" ]] || echo "$OFFERING_EXTERNAL_ID" | grep -qi nas; then
  echo "  WARN: externalid looks like NAS repo — forcing externalid=veeam"
  OFFERING_EXTERNAL_ID=veeam
  export OFFERING_EXTERNAL_ID
fi

echo ""
echo "=== listBackupOfferings (zone) ==="
mold_backup_cmk_run listBackupOfferings "zoneid=${ZONE_ID}" 2>&1 | head -c 2000 || true

if [[ "$DO_IMPORT" == "true" ]]; then
  echo ""
  echo "=== importBackupOffering provider=${VEEAM_PROVIDER_NAME} externalid=${OFFERING_EXTERNAL_ID} ==="
  # If an offering named VeeamBackup exists under wrong provider=veeam (NAS hybrid), delete tip:
  echo "  Tip: Mold UI에서 provider=veeam 인 'VeeamBackup' 이 있으면 삭제 후 재import (ablestack-veeam 필요)"
  export OFFERING_EXTERNAL_ID VEEAM_PROVIDER_NAME ZONE_ID
  # Prefer explicit ablestack-veeam for seed import path
  VEEAM_PROVIDER_NAME=ablestack-veeam
  export VEEAM_PROVIDER_NAME
  oid="$(mold_backup_api_ensure_backup_resources)" || true
  echo "  offering_id=${oid:-FAILED}"
  if [[ -n "${oid:-}" && -f "${ETC_DIR}/mold-backup.env" ]]; then
    sed -i "s|^ZONE_ID=.*|ZONE_ID='${ZONE_ID}'|" "${ETC_DIR}/mold-backup.env" || true
    grep -q '^VEEAM_PROVIDER_NAME=' "${ETC_DIR}/mold-backup.env" 2>/dev/null \
      && sed -i "s|^VEEAM_PROVIDER_NAME=.*|VEEAM_PROVIDER_NAME='ablestack-veeam'|" "${ETC_DIR}/mold-backup.env" \
      || echo "VEEAM_PROVIDER_NAME='ablestack-veeam'" >> "${ETC_DIR}/mold-backup.env"
    grep -q '^OFFERING_EXTERNAL_ID=' "${ETC_DIR}/mold-backup.env" 2>/dev/null \
      && sed -i "s|^OFFERING_EXTERNAL_ID=.*|OFFERING_EXTERNAL_ID='${OFFERING_EXTERNAL_ID}'|" "${ETC_DIR}/mold-backup.env" \
      || echo "OFFERING_EXTERNAL_ID='${OFFERING_EXTERNAL_ID}'" >> "${ETC_DIR}/mold-backup.env"
    echo "  updated ${ETC_DIR}/mold-backup.env"
  fi
  if [[ -z "${oid:-}" ]]; then
    echo ""
    echo "=== If error mentions repository / provider ==="
    echo "  1) Mold UI → Backup Offerings → delete 'VeeamBackup' if provider shows as veeam (NAS hybrid)"
    echo "  2) Re-run: bash $0 --import   # creates provider=ablestack-veeam"
    echo "  3) Re-assign VMs to the new offering"
  fi
fi

echo ""
echo "=== MS note ==="
echo "  ZONE_ID: beb3cc2a-a5af-417f-a17e-85410490eedc"
echo "  Seed import needs offering provider=ablestack-veeam (NOT display-name veeam/NAS hybrid)"
echo "  externalid=veeam"
echo "  등록: bash $0 --import"
