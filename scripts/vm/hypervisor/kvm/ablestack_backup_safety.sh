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
BACKUP_GUEST_QUIESCE_STATE=NOT_REQUESTED

virsh() {
  LC_ALL=C timeout -k 5s "${LIBVIRT_CONTROL_TIMEOUT_SECONDS}s" virsh "$@"
}

ablestack_guest_has_freeze_state() {
  local state
  state=$(virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-status"}') || return 1
  python3 -c 'import json,sys; value=json.load(sys.stdin); sys.exit(0 if isinstance(value, dict) and value.get("return") == sys.argv[1] else 1)' "$1" <<< "$state"
}

ablestack_guest_is_thawed() {
  ablestack_guest_has_freeze_state thawed
}

ablestack_guest_is_off_or_absent() {
  local state domains
  if state=$(virsh -c qemu:///system domstate "$VM" 2>/dev/null); then
    [[ "$state" == "shut off" ]]
  else
    domains=$(virsh -c qemu:///system list --all --name) || return 1
    ! grep -Fxq -- "$VM" <<< "$domains"
  fi
}

ablestack_freeze_rbd_guest() {
  [[ "$QUIESCE" != "true" ]] && return 0
  local state
  if state=$(virsh -c qemu:///system domstate "$VM" 2>/dev/null); then
    if [[ "$state" == "shut off" ]]; then BACKUP_GUEST_QUIESCE_STATE=NOT_APPLICABLE; return 0; fi
    if [[ "$state" != "running" ]]; then
      log -ne "FAILED RBD guest state does not permit filesystem freeze vm=[$VM] state=[$state]"
      return 1
    fi
  elif ablestack_guest_is_off_or_absent; then
    BACKUP_GUEST_QUIESCE_STATE=NOT_APPLICABLE
    return 0
  else
    log -ne "FAILED RBD guest state is unconfirmed vm=[$VM]"
    return 1
  fi
  ablestack_freeze_guest
}

ablestack_freeze_guest() {
  [[ "$QUIESCE" != "true" ]] && return 0
  BACKUP_GUEST_QUIESCE_STATE=FAILED
  if [[ -z "${ABLESTACK_BACKUP_JOB_DIR:-}" || ! -d "$ABLESTACK_BACKUP_JOB_DIR" ]]; then
    log -ne "FAILED GUEST_FREEZE_UNCONFIRMED vm=[$VM]; durable backup job directory is required"
    return 1
  fi
  # Never thaw a filesystem frozen by another operator or job.
  if ! ablestack_guest_is_thawed; then
    log -ne "FAILED GUEST_FREEZE_UNCONFIRMED vm=[$VM]; Guest Agent unavailable or filesystems already frozen"
    return 1
  fi
  local pending="$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending"
  printf '%s\n' "$VM" > "$pending.tmp" || return 1
  sync "$pending.tmp" || return 1
  mv -f "$pending.tmp" "$pending" || return 1
  sync "$ABLESTACK_BACKUP_JOB_DIR" || return 1
  BACKUP_GUEST_FREEZE_PENDING=1
  local response
  if ! response=$(virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-freeze"}') ||
      ! python3 -c 'import json,sys; value=json.load(sys.stdin); count=value.get("return") if isinstance(value, dict) else None; sys.exit(0 if type(count) is int and count > 0 else 1)' <<< "$response" ||
      ! ablestack_guest_has_freeze_state frozen; then
    # A timeout can occur after the guest accepted the freeze request.
    log -ne "FAILED GUEST_FREEZE_UNCONFIRMED vm=[$VM]; attempting owned thaw"
    ablestack_thaw_guest || return 1
    return 1
  fi
  BACKUP_GUEST_QUIESCE_STATE=FROZEN
  return 0
}

ablestack_verify_guest_frozen() {
  [[ "$BACKUP_GUEST_FREEZE_PENDING" -ne 1 ]] && return 0
  if ! ablestack_guest_has_freeze_state frozen; then
    BACKUP_GUEST_QUIESCE_STATE=FAILED
    log -ne "FAILED GUEST_FREEZE_LOST vm=[$VM]; freeze window ended or its status is unconfirmed"
    return 1
  fi
}

ablestack_finish_guest_quiesce() {
  local result=0
  ablestack_verify_guest_frozen || result=1
  ablestack_thaw_guest || return 20
  if [[ "$result" -eq 0 && "$BACKUP_GUEST_QUIESCE_STATE" == FROZEN ]]; then
    BACKUP_GUEST_QUIESCE_STATE=VERIFIED
  fi
  return "$result"
}

# Used by the four ABLESTACK providers' directory-based RBD workers. Validate
# every source before freezing, capture the whole set, then thaw before export.
ablestack_prepare_rbd_snapshots() {
  local disk_path result=0
  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    parse_rbd_uri "$disk_path"
    build_rbd_cmd
    if ! timeout 30s "${RBD_CMD[@]}" info "$RBD_IMAGE" >> "$logFile" 2>&1; then
      log -ne "FAILED RBD image access check image=[$RBD_IMAGE]"
      return 1
    fi
    if [[ "$BACKUP_TYPE" == INCREMENTAL && -n "$PARENT_CHECKPOINT_NAME" ]]; then
      if ! timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>>"$logFile" | awk 'NR>1 {print $2}' | grep -Fxq "$PARENT_CHECKPOINT_NAME"; then
        log -ne "FAILED RBD parent snapshot check image=[$RBD_IMAGE] parent=[$PARENT_CHECKPOINT_NAME]"
        return 1
      fi
    fi
  done < <(split_csv "$DISK_PATHS")
  ablestack_freeze_rbd_guest || return 1
  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    ablestack_verify_guest_frozen || { result=1; break; }
    parse_rbd_uri "$disk_path"
    build_rbd_cmd
    if ! timeout -k 5s 30s "${RBD_CMD[@]}" snap create "${RBD_IMAGE}@${CHECKPOINT_NAME}" >> "$logFile" 2>&1; then
      log -ne "FAILED RBD snapshot create image=[$RBD_IMAGE] checkpoint=[$CHECKPOINT_NAME]"
      result=1
      break
    fi
    record_created_rbd_snapshot "$disk_path" "$CHECKPOINT_NAME"
  done < <(split_csv "$DISK_PATHS")
  if [[ "$result" -eq 0 ]]; then
    ablestack_finish_guest_quiesce || return $?
  else
    ablestack_thaw_guest || return 20
  fi
  return "$result"
}

ablestack_thaw_guest() {
  [[ "$BACKUP_GUEST_FREEZE_PENDING" -ne 1 ]] && return 0
  local attempt confirmed=false
  for attempt in 1 2 3; do
    if ablestack_guest_is_thawed; then confirmed=true; break; fi
    # Even a failed status request must be followed by an owned thaw attempt.
    virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-thaw"}' ||
      log -ne "WARNING guest filesystem thaw command failed vm=[$VM] attempt=[$attempt]"
  done
  if [[ "$confirmed" == "true" ]] || ablestack_guest_is_thawed || ablestack_guest_is_off_or_absent; then
    if [[ -n "${ABLESTACK_BACKUP_JOB_DIR:-}" ]]; then
      rm -f "$ABLESTACK_BACKUP_JOB_DIR/guest-freeze.pending" || return 1
    fi
    BACKUP_GUEST_FREEZE_PENDING=0
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
  local info active_status status processed data_bytes file_bytes now advanced
  local last_data=-1 last_file=-1 last_progress_at measured=0
  local progress_warning=0
  last_progress_at=$(date +%s)
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
    # Successful polling is not data progress. Normalize virsh's native processed counter to bytes.
    processed=$(awk '/^[[:space:]]*(Data|File) processed:/ {
      scale=1; if ($4=="KiB" || $4=="kB") scale=1024;
      if ($4=="MiB" || $4=="MB") scale=1024*1024;
      if ($4=="GiB" || $4=="GB") scale=1024*1024*1024;
      if ($4=="TiB" || $4=="TB") scale=1024*1024*1024*1024;
      if ($3 ~ /^[0-9]+([.][0-9]+)?$/ && $4 ~ /^(B|bytes|KiB|kB|MiB|MB|GiB|GB|TiB|TB)$/) {
        value=sprintf("%.0f", $3*scale); if ($1=="Data") data=value; else file=value;
      }
    } END {printf "%s|%s", data, file}' <<< "$info")
    IFS='|' read -r data_bytes file_bytes <<< "$processed"
    now=$(date +%s)
    advanced=0
    if [[ -n "$data_bytes" ]] && (( data_bytes > last_data )); then
      last_data=$data_bytes
      if (( data_bytes > 0 )); then advanced=1; fi
    fi
    if [[ -n "$file_bytes" ]] && (( file_bytes > last_file )); then
      last_file=$file_bytes
      if (( file_bytes > 0 )); then advanced=1; fi
    fi
    if (( advanced == 1 )); then
      measured=1
      last_progress_at=$now
      progress_warning=0
    elif (( measured == 1 && now - last_progress_at >= 1800 )) && [[ -n "$data_bytes$file_bytes" ]]; then
      log -ne "FAILED BACKUP_DATA_NO_PROGRESS vm=[$VM] no measurable progress for 1800 seconds; source termination must be confirmed"
      return 1
    elif (( progress_warning == 0 && now - last_progress_at >= 1800 )); then
      log -ne "WARNING BACKUP_PROGRESS_UNAVAILABLE vm=[$VM] native progress is unavailable; execution timeout remains in effect"
      progress_warning=1
    fi
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
