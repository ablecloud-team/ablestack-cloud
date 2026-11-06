# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

# Shared by ABLESTACK NAS, Veeam, Commvault and NetBackup host scripts.
LIBVIRT_CONTROL_TIMEOUT_SECONDS="${ABLESTACK_LIBVIRT_CONTROL_TIMEOUT_SECONDS:-30}"
BACKUP_GUEST_FREEZE_PENDING=0
BACKUP_JOB_MAY_BE_ACTIVE=0

virsh() {
  LC_ALL=C timeout -k 5s "${LIBVIRT_CONTROL_TIMEOUT_SECONDS}s" virsh "$@"
}

ablestack_guest_is_thawed() {
  local state
  state=$(virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-status"}') || return 1
  python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("return") == "thawed" else 1)' <<< "$state"
}

ablestack_freeze_guest() {
  [[ "$QUIESCE" != "true" ]] && return 0
  # Never thaw a filesystem frozen by another operator or job.
  if ! ablestack_guest_is_thawed; then
    log -ne "WARNING guest quiesce unavailable; no freeze request was sent vm=[$VM]"
    return 0
  fi
  BACKUP_GUEST_FREEZE_PENDING=1
  if [[ -n "${ABLESTACK_BACKUP_JOB_DIR:-}" ]]; then
    printf '%s\n' "$VM" > "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending"
  fi
  if ! virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-freeze"}'; then
    # A timeout can occur after the guest accepted the freeze request.
    ablestack_thaw_guest || return 1
  fi
}

ablestack_thaw_guest() {
  [[ "$BACKUP_GUEST_FREEZE_PENDING" -ne 1 ]] && return 0
  if virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-thaw"}' || ablestack_guest_is_thawed; then
    BACKUP_GUEST_FREEZE_PENDING=0
    if [[ -n "${ABLESTACK_BACKUP_JOB_DIR:-}" ]]; then
      rm -f "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending"
    fi
    return 0
  fi
  log -ne "FAILED guest filesystem thaw remains pending vm=[$VM]; job evidence retained"
  return 1
}

ablestack_backup_job_started() {
  # Record before backup-begin: its response can be lost after QEMU starts IO.
  BACKUP_JOB_MAY_BE_ACTIVE=1
  if [[ -n "${ABLESTACK_BACKUP_JOB_DIR:-}" ]]; then
    printf '%s\n' "$VM" > "$ABLESTACK_BACKUP_JOB_DIR/backup-domain"
  fi
}

ablestack_backup_job_inactive() {
  local info
  info=$(virsh -c qemu:///system domjobinfo "$VM") || return 1
  [[ "$info" =~ Job\ type:[[:space:]]+None([[:space:]]|$) ]]
}

ablestack_backup_cleanup_safe() {
  ablestack_thaw_guest || return 1
  [[ "$BACKUP_JOB_MAY_BE_ACTIVE" -ne 1 ]] && return 0
  if ! ablestack_backup_job_inactive; then
    virsh -c qemu:///system domjobabort --domain "$VM" || true
    if ! ablestack_backup_job_inactive; then
      log -ne "FAILED source job termination is unconfirmed vm=[$VM]; backup files and checkpoints retained"
      return 1
    fi
  fi
  BACKUP_JOB_MAY_BE_ACTIVE=0
}

ablestack_wait_for_backup() {
  local deadline=$(( $(date +%s) + DATA_OPERATION_TIMEOUT_SECONDS ))
  local info active_status status
  while (( $(date +%s) < deadline )); do
    # --completed queries the last finished job, not the currently running backup.
    # Its None result while IO is active must never trigger cleanup or abort.
    info=$(virsh -c qemu:///system domjobinfo "$VM") || {
      log -ne "FAILED active backup status is unavailable vm=[$VM]"
      return 1
    }
    active_status=$(awk '/Job type:/ {print $3}' <<< "$info")
    case "$active_status" in
      Bounded|Unbounded) ;;
      None)
        info=$(virsh -c qemu:///system domjobinfo "$VM" --completed --keep-completed) || {
          log -ne "FAILED completed backup status is unavailable vm=[$VM]"
          return 1
        }
        status=$(awk '/Job type:/ {print $3}' <<< "$info")
        if [[ "$status" == "Completed" ]]; then
          BACKUP_JOB_MAY_BE_ACTIVE=0
          return 0
        fi
        log -ne "FAILED backup did not complete vm=[$VM] activeStatus=[$active_status] status=[$status]"
        return 1
        ;;
      *) log -ne "FAILED active backup status is unexpected vm=[$VM] status=[$active_status]"; return 1 ;;
    esac
    sleep 5
  done
  log -ne "FAILED backup exceeded wall-clock timeout vm=[$VM] timeoutSeconds=[$DATA_OPERATION_TIMEOUT_SECONDS]"
  return 1
}

ablestack_backup_exit() {
  local status=$?
  trap - EXIT
  # Exit/TERM never deletes payloads. Management retries cleanup after termination proof.
  if ! ablestack_backup_cleanup_safe; then
    [[ "$status" -eq 0 ]] && status=20
  fi
  exit "$status"
}

trap 'exit 130' INT
trap 'exit 143' TERM
trap ablestack_backup_exit EXIT
