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

# Mold + Veeam backup operations on KVM.
#
#   backup-full     전체 VM 백업 (VM_INCLUDE=* 또는 목록)
#   list-backups    VM별 BackedUp 백업 목록
#   restore         개별 VM 복원 (--vm-name + --backup-id)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ABLESTACK_VEEAM_ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
# shellcheck source=mold-backup.lib.sh
source "${SCRIPT_DIR}/mold-backup.lib.sh"

CMD="${1:-}"
shift || true

JOB_NAME="${VEEAM_JOB_NAME:-VeeamBackup}"
BACKUP_ID="${BACKUP_ID:-}"
VM_NAME_ARG=""
VM_UUID_ARG=""
CLIENT="$(hostname -s)"
DRY_RUN="${DRY_RUN:-false}"
RESTORE_SOURCE="${RESTORE_SOURCE:-}"
SINCE_MIN="${VEEAM_RESTORE_WATCH_WINDOW_MIN:-60}"
TRIGGER_MOLD="${RESTORE_WATCH_TRIGGER_MOLD:-false}"
RESTORE_EVENT=""
RESTORE_EVENT_ARGS=()

usage() {
  cat <<'EOF'
Usage: mold-backup.sh <command> [options]

Commands:
  backup            Pre: Mold API + /tmp/mold/veeam export (대상: VM_INCLUDE)
  backup-complete   Post: registry + staging 정리
  backup-full       backup + backup-complete (전체/다중 VM 한 번에)
  list-backups      VM별 BackedUp 백업 목록 (개별 복원용 ID 확인)
  restore           개별 VM 복원 (--vm-name + --backup-id, VM 정지 권장)
  restore-notify    restore 와 동일 (ablestack_veeam_restore_notify.sh 호출)
  restore-watch     Veeam UI 복원 감지 → (옵션) Mold datadisk restoreBackup 호출
  restore-event     복원 이벤트 수동 주입 (veeam.restore.completed | mold.restore.manual)
  backup-watch      Veeam UI에서 직접 백업한 세션을 감지해 Mold 상태에 반영
  status            한 VM 백업 + registry

Options:
  --job NAME              Job conf 이름 (default: VeeamBackup)
  --backup-id UUID        restore 시 백업 ID
  --vm-name NAME          libvirt 이름 (restore/status/list)
  --vm-uuid UUID          Mold VM UUID
  --restore-source SRC    복원 소스: auto | veeam | mold-only (default: auto)
                          auto     = FULL은 Mold, INCREMENTAL은 Veeam 체인 회수 후 Mold 복원
                          veeam    = Veeam 체인을 호스트로 회수 후 Mold 복원
                          mold-only= datadisk 백업만으로 복원 (NAS·Veeam chain export 없음)
  --since-min N           restore-watch/backup-watch 조회 창(분, default: 60)
  --trigger-mold          restore-watch: VM 소유 호스트만 Mold restoreBackup 호출
  -n, --dry-run

Mold backup offering: BACKUP_OFFERING_NAME=VeeamBackup (veeam_config --offering-name)

Examples:
  # 전체 실행 중 VM 백업
  mold-backup.sh backup-full --job VeeamBackup

  # VM별 백업 ID 확인
  mold-backup.sh list-backups --job VeeamBackup

  # i-2-11-VM 만 복원 (auto: FULL=Mold, INCREMENTAL=Veeam 회수 후 Mold)
  virsh shutdown i-2-11-VM
  mold-backup.sh restore --job VeeamBackup --vm-name i-2-11-VM --backup-id <uuid>

  # Veeam 리포지토리에서 복원 (Veeam 체인 회수 후 Mold 복원)
  mold-backup.sh restore --job VeeamBackup --vm-name i-2-11-VM --backup-id <uuid> --restore-source veeam

  # Mold datadisk만으로 복원 (NAS/Veeam chain 없음)
  mold-backup.sh restore --job VeeamBackup --vm-name i-2-11-VM --backup-id <uuid> --restore-source mold-only

  # Veeam UI 복원 감지 (+ --trigger-mold 시 KVM이 datadisk restoreBackup 실행)
  mold-backup.sh restore-watch --job Mold_Guest_Backup --since-min 10 --trigger-mold

  # 이벤트 직접 주입 (Veeam PS1 SSH push)
  mold-backup.sh restore-event --job Mold_Guest_Backup veeam.restore.completed <session-id> i-2-63-VM

  # Veeam UI에서 직접 백업한 내역을 Mold에 반영 (cron/systemd timer로 주기 실행 권장)
  mold-backup.sh backup-watch --job VeeamBackup --since-min 60
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --job) JOB_NAME="$2"; shift 2 ;;
    --backup-id) BACKUP_ID="$2"; shift 2 ;;
    --vm-name) VM_NAME_ARG="$2"; shift 2 ;;
    --vm-uuid) VM_UUID_ARG="$2"; shift 2 ;;
    --restore-source) RESTORE_SOURCE="$2"; shift 2 ;;
    --since-min) SINCE_MIN="$2"; shift 2 ;;
    --trigger-mold) TRIGGER_MOLD=true; shift ;;
    -n|--dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *)
      if [[ "$CMD" == "restore-event" ]]; then
        RESTORE_EVENT_ARGS+=("$1")
        shift
      else
        die "Unknown option: $1"
      fi
      ;;
  esac
done

[[ -n "$CMD" ]] || { usage; exit 1; }

export VEEAM_JOB_NAME="$JOB_NAME"
mold_backup_load_config || die "Job config not found — run veeam_config.sh first"

[[ -n "$VM_NAME_ARG" ]] && export VM_NAME="$VM_NAME_ARG"
[[ -n "$VM_UUID_ARG" ]] && export VM_UUID="$VM_UUID_ARG"
[[ -n "$BACKUP_ID" ]] && export BACKUP_ID
[[ -n "${RESTORE_SOURCE:-}" ]] && export RESTORE_SOURCE

run_pre() {
  echo "=== backup (pre-notify) job=${JOB_NAME} offering=$(mold_backup_offering_name) vm_include=${VM_INCLUDE:-*} ==="
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] ablestack_veeam_pre_notify.sh ${CLIENT} ${JOB_NAME}"
    return 0
  fi
  "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_veeam_pre_notify.sh" "$CLIENT" "$JOB_NAME"
}

run_post() {
  echo "=== backup-complete job=${JOB_NAME} ==="
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] ablestack_veeam_post_notify.sh ${CLIENT} ${JOB_NAME}"
    return 0
  fi
  "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_veeam_post_notify.sh" "$CLIENT" "$JOB_NAME"
}

# Mold-initiated bidirectional (mode C): after a Mold backup, start the matching Veeam
# Agent job(s) over SSH. mold_backup_trigger_veeam_job sets the mold-active marker so the
# Veeam-driven pre_notify skips a duplicate createAblestackVeeamBackup (one Mold record).
# Requires VEEAM_TRIGGER_ENABLED/METHOD/SSH_* and VM_TARGETS in the loaded job conf.
run_veeam_trigger() {
  if [[ "${VEEAM_TRIGGER_ENABLED:-false}" != "true" ]]; then
    return 0
  fi
  echo "=== Mold→Veeam trigger (job=${JOB_NAME}) ==="
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] mold_backup_trigger_veeam_job <vm> for VM_INCLUDE=${VM_INCLUDE:-*}"
    return 0
  fi
  local vm
  local -a _domains=()
  while IFS= read -r vm; do
    [[ -z "$vm" ]] && continue
    _domains+=("$vm")
  done < <(mold_backup_list_target_domains || true)
  for vm in "${_domains[@]}"; do
    mold_backup_vm_in_filter "$vm" || continue
    mold_backup_trigger_veeam_job "$vm" </dev/null || true
  done
}

run_restore() {
  [[ -n "$BACKUP_ID" ]] || die "restore requires --backup-id or BACKUP_ID"
  [[ -n "${VM_NAME:-}${VM_UUID:-}" ]] || die "restore requires --vm-name or --vm-uuid (individual VM)"
  case "${RESTORE_SOURCE:-auto}" in
    auto|veeam|mold-only) ;;
    *) die "--restore-source must be auto | veeam | mold-only (got: ${RESTORE_SOURCE})" ;;
  esac
  export BACKUP_ID VM_NAME VM_UUID
  [[ -n "${RESTORE_SOURCE:-}" ]] && export RESTORE_SOURCE
  echo "=== restore vm=${VM_NAME:-${VM_UUID}} backup_id=${BACKUP_ID} source=${RESTORE_SOURCE:-auto} ==="
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] ablestack_veeam_restore_notify.sh"
    return 0
  fi
  "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_veeam_restore_notify.sh" "$CLIENT" "$JOB_NAME"
}

# Veeam UI에서 직접 수행한 복원을 감지해 Mold 상태(registry/로그)에 반영.
# 이미 검증된 KVM→Veeam SSH 채널로 최근 완료된 복원 세션을 조회하고,
# 대상 게스트 IP를 VM_TARGETS로 libvirt VM에 역매핑해 기록한다.
run_restore_watch() {
  if [[ "${VEEAM_TRIGGER_ENABLED:-false}" != "true" && -z "${VEEAM_SSH_HOST:-}" ]]; then
    die "restore-watch needs VEEAM_SSH_HOST (KVM→Veeam SSH) in job conf"
  fi
  export RESTORE_WATCH_TRIGGER_MOLD="$TRIGGER_MOLD"
  echo "=== restore-watch job=${JOB_NAME} window=${SINCE_MIN}min trigger_mold=${TRIGGER_MOLD} host=$(hostname -s) ==="
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] mold_backup_watch_veeam_restores ${JOB_NAME} ${SINCE_MIN} ${TRIGGER_MOLD}"
    return 0
  fi
  mold_backup_watch_veeam_restores "$JOB_NAME" "$SINCE_MIN" "$TRIGGER_MOLD"
  local safe_job reg events
  safe_job="$(mold_backup_safe_job_name "$JOB_NAME")"
  reg="${ABLESTACK_VEEAM_ETC_DIR}/registry/${safe_job}.restore.log"
  events="$(mold_backup_events_log_file 2>/dev/null || echo "")"
  if [[ -f "$reg" ]]; then
    echo "=== restore registry (last 10) ==="
    tail -10 "$reg"
  fi
  if [[ -n "$events" && -f "$events" ]]; then
    echo "=== restore events (last 10) ==="
    tail -10 "$events"
  fi
}

run_restore_event() {
  [[ $# -ge 1 ]] || die "restore-event needs: <event> [session-id] [vm-name] [detail]"
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] ablestack_veeam_restore_event.sh $*"
    return 0
  fi
  "${ABLESTACK_VEEAM_ETC_DIR}/ablestack_veeam_restore_event.sh" "$@"
}

# Veeam UI에서 직접 수행한 백업을 감지해 Mold 상태(registry/로그)에 반영.
# restore-watch와 동일하게 KVM→Veeam SSH로 최근 완료된 백업 세션을 조회하고,
# 대상 게스트 IP를 VM_TARGETS로 libvirt VM에 역매핑해 기록한다.
run_backup_watch() {
  if [[ "${VEEAM_TRIGGER_ENABLED:-false}" != "true" && -z "${VEEAM_SSH_HOST:-}" ]]; then
    die "backup-watch needs VEEAM_SSH_HOST (KVM→Veeam SSH) in job conf"
  fi
  echo "=== backup-watch job=${JOB_NAME} window=${SINCE_MIN}min ==="
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] mold_backup_watch_veeam_backups ${JOB_NAME} ${SINCE_MIN}"
    return 0
  fi
  mold_backup_watch_veeam_backups "$JOB_NAME" "$SINCE_MIN"
  local safe_job reg
  safe_job="$(mold_backup_safe_job_name "$JOB_NAME")"
  reg="${ABLESTACK_VEEAM_ETC_DIR}/registry/${safe_job}.log"
  if [[ -f "$reg" ]]; then
    echo "=== backup registry (last 10) ==="
    tail -10 "$reg"
  fi
}

run_list_backups() {
  mold_backup_require_var MOLD_API_URL
  mold_backup_require_var MOLD_API_KEY
  mold_backup_require_var MOLD_API_SECRET
  echo "=== BackedUp backups (offering=$(mold_backup_offering_name)) ==="
  local vm_name vm_id json
  while IFS= read -r vm_name; do
    [[ -z "$vm_name" ]] && continue
    mold_backup_vm_in_filter "$vm_name" || continue
    vm_id="$(mold_backup_api_get_vm_id "$vm_name" 2>/dev/null || true)"
    [[ -n "$vm_id" ]] || { echo "${vm_name}: (no Mold VM id)"; continue; }
    json="$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
    echo "--- ${vm_name} (${vm_id}) ---"
    echo "$json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    b = d.get('listablestackveeambackupsresponse', {}).get('backup', [])
    if isinstance(b, dict): b = [b]
    backed = [x for x in b if str(x.get('status','')).lower() == 'backedup']
    if not backed:
        print('  (no BackedUp backups)')
    for x in backed:
        print(f\"  restore: mold-backup.sh restore --vm-name {sys.argv[1]} --backup-id {x.get('id')}  # {x.get('created')} {x.get('type')}\")
except Exception as e:
    print('  (parse error)', e)
" "$vm_name" 2>/dev/null || echo "$json" | head -c 200
  done < <(mold_backup_list_target_domains || true)
  local safe_job reg
  safe_job="$(mold_backup_safe_job_name "$JOB_NAME")"
  reg="${ABLESTACK_VEEAM_ETC_DIR}/registry/${safe_job}.log"
  if [[ -f "$reg" ]]; then
    echo "=== registry (last backup_id per VM) ==="
    tail -30 "$reg"
  fi
}

run_status() {
  mold_backup_require_var MOLD_API_URL
  mold_backup_require_var MOLD_API_KEY
  mold_backup_require_var MOLD_API_SECRET
  local vm_id="${VM_UUID:-}"
  if [[ -z "$vm_id" && -n "${VM_NAME:-}" ]]; then
    vm_id="$(mold_backup_api_get_vm_id "$VM_NAME" 2>/dev/null || true)"
  fi
  [[ -n "$vm_id" ]] || die "status needs --vm-name, --vm-uuid, or VM_NAME in conf"

  echo "=== Mold backups VM ${vm_id} ==="
  mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" \
    | python3 -m json.tool 2>/dev/null || true
  local json cnt
  json="$(mold_backup_cmk_run listAblestackVeeamBackups "virtualmachineid=${vm_id}" 2>/dev/null || true)"
  cnt="$(mold_backup_api_count_backed_up_from_json "$json" 2>/dev/null || echo "?")"
  echo "BackedUp chain count: ${cnt}"
  local safe_job reg
  safe_job="$(mold_backup_safe_job_name "$JOB_NAME")"
  reg="${ABLESTACK_VEEAM_ETC_DIR}/registry/${safe_job}.log"
  echo "=== registry ==="
  [[ -f "$reg" ]] && grep "vm=${VM_NAME:-}" "$reg" 2>/dev/null | tail -5 || tail -10 "$reg" 2>/dev/null || echo "(none)"
  local rreg="${ABLESTACK_VEEAM_ETC_DIR}/registry/${safe_job}.restore.log"
  echo "=== restore registry (Veeam UI 복원 반영) ==="
  if [[ -f "$rreg" ]]; then
    grep "vm=${VM_NAME:-}" "$rreg" 2>/dev/null | tail -5 || tail -10 "$rreg" 2>/dev/null
  else
    echo "(none)"
  fi
}

case "$CMD" in
  backup|pre) run_pre ;;
  backup-complete|post) run_post ;;
  backup-full|full)
    run_pre
    run_post
    run_veeam_trigger
    ;;
  restore|restore-notify) run_restore ;;
  restore-watch|watch-restore) run_restore_watch ;;
  restore-event) run_restore_event "${RESTORE_EVENT_ARGS[@]}" ;;
  backup-watch|watch-backup) run_backup_watch ;;
  list-backups|list) run_list_backups ;;
  status) run_status ;;
  -h|--help|help) usage ;;
  *) die "Unknown command: ${CMD}" ;;
esac

echo "=== mold-backup.sh ${CMD} done ==="
