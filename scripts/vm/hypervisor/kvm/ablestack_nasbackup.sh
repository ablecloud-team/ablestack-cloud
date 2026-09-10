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

set -eo pipefail

# CloudStack B&R NAS Backup and Recovery Tool for KVM

# TODO: do libvirt/logging etc checks

### Declare variables ###

OP=""
VM=""
NAS_TYPE=""
NAS_ADDRESS=""
MOUNT_OPTS=""
MOUNT_TIMEOUT=0
BACKUP_DIR=""
BACKUP_TYPE=""
CHECKPOINT_NAME=""
PARENT_BACKUP_DIR=""
PARENT_CHECKPOINT_NAME=""
PARENT_CHECKPOINT_PATH=""
BACKUP_FILES=""
DISK_PATHS=""
QUIESCE=""
BACKUP_BANDWIDTH_LIMIT_MBPS=0
FORCED="false"
CLEANUP_CHECKPOINT_NAMES=""
logFile="/var/log/cloudstack/agent/agent.log"
UNMOUNT_TIMEOUT=60
CREATED_RBD_SNAPSHOTS=()

EXIT_CLEANUP_FAILED=20
IN_PROGRESS_MARKER=".backup.inprogress"
COMPLETE_MARKER=".backup.complete"

log() {
  [[ "$verb" -eq 1 ]] && builtin echo "$@"
  if [[ "$1" == "-ne"  || "$1" == "-e" || "$1" == "-n" ]]; then
    builtin echo -e "$(date '+%Y-%m-%d %H-%M-%S>')" "${@: 2}" >> "$logFile"
  else
    builtin echo "$(date '+%Y-%m-%d %H-%M-%S>')" "$@" >> "$logFile"
  fi
}

log_unhandled_error() {
  local status=$?
  local line="$1"
  log -ne "FAILED unhandled error status=[$status] line=[$line] op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] checkpoint=[$CHECKPOINT_NAME] mountPoint=[$mount_point]"
}

trap 'log_unhandled_error "$LINENO"' ERR

vercomp() {
  local IFS=.
  local i ver1=($1) ver2=($3)

  # Compare each segment of the version numbers
  for ((i=0; i<${#ver1[@]}; i++)); do
      if [[ -z ${ver2[i]} ]]; then
          ver2[i]=0
      fi

      if ((10#${ver1[i]} > 10#${ver2[i]})); then
          return  0 # Version 1 is greater
      elif ((10#${ver1[i]} < 10#${ver2[i]})); then
          return 2  # Version 2 is greater
      fi
  done
  return 0  # Versions are equal
}

sanity_checks() {
  hvVersion=$(virsh version | grep hypervisor | awk '{print $(NF)}')
  libvVersion=$(virsh version | grep libvirt | awk '{print $(NF)}' | tail -n 1)
  apiVersion=$(virsh version | grep API | awk '{print $(NF)}')

  vercomp "$hvVersion" ">=" "4.2.0"
  hvStatus=$?
  vercomp "$libvVersion" ">=" "7.2.0"
  libvStatus=$?

  if [[ $hvStatus -eq 0 && $libvStatus -eq 0 ]]; then
    log -ne "Success... [ QEMU: $hvVersion Libvirt: $libvVersion apiVersion: $apiVersion ]"
  else
    echo "Failure... Your QEMU version $hvVersion or libvirt version $libvVersion is unsupported. Consider upgrading to the required minimum version of QEMU: 4.2.0 and Libvirt: 7.2.0"
    exit 1
  fi
}

resume_vm_if_paused() {
  [[ -z "$VM" ]] && return 0

  local vm_state
  vm_state=$(virsh -c qemu:///system domstate "$VM" 2>/dev/null || true)
  if [[ "$vm_state" == "paused" ]]; then
    log -ne "VM [$VM] is paused after backup failure; trying to resume"
    virsh -c qemu:///system resume "$VM" >> "$logFile" 2>&1 || log -ne "WARNING: failed to resume paused VM [$VM]"
  fi
}

apply_backup_bandwidth_limit() {
  local bandwidth_limit_mbps="${BACKUP_BANDWIDTH_LIMIT_MBPS:-0}"

  if ! [[ "$bandwidth_limit_mbps" =~ ^[0-9]+$ ]] || [[ "$bandwidth_limit_mbps" -le 0 ]]; then
    return 0
  fi

  local bandwidth_limit_mibps=$(( (bandwidth_limit_mbps + 7) / 8 ))
  [[ "$bandwidth_limit_mibps" -lt 1 ]] && bandwidth_limit_mibps=1

  while IFS='|' read -r disk target; do
    [[ -z "$disk" ]] && continue

    local attempt
    local blockjob_output=""
    local bandwidth_applied=0
    for attempt in 1 2 3 4 5; do
      if blockjob_output=$(virsh -c qemu:///system blockjob "$VM" "$disk" --bandwidth "$bandwidth_limit_mibps" 2>&1); then
        log -ne "Applied backup bandwidth limit vm=[$VM] disk=[$disk] limitMbps=[$bandwidth_limit_mbps] virshLimitMiBps=[$bandwidth_limit_mibps] attempt=[$attempt]"
        bandwidth_applied=1
        break
      fi
      sleep 1
    done

    if [[ "$bandwidth_applied" -ne 1 ]] && [[ "$blockjob_output" == *"does not have an active block job"* || "$blockjob_output" == *"No current block job"* ]]; then
      log -ne "Skipped backup bandwidth limit vm=[$VM] disk=[$disk] limitMbps=[$bandwidth_limit_mbps] virshLimitMiBps=[$bandwidth_limit_mibps] reason=[No active block job; backup may have already completed]"
    elif [[ "$bandwidth_applied" -ne 1 ]]; then
      log -ne "WARNING failed to apply backup bandwidth limit vm=[$VM] disk=[$disk] limitMbps=[$bandwidth_limit_mbps] virshLimitMiBps=[$bandwidth_limit_mibps] output=[${blockjob_output:-Unknown error}]"
    fi
  done < <(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')
}

### Operation methods ###

backup_running_vm() {
  mount_operation
  mkdir -p "$dest" || { echo "Failed to create backup directory $dest"; exit 1; }
  mkdir -p "$dest/checkpoints" || { echo "Failed to create checkpoint directory $dest/checkpoints"; exit 1; }
  mark_backup_in_progress

  local parent_checkpoint_file=""
  if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_PATH" ]]; then
    parent_checkpoint_file="$mount_point/$PARENT_CHECKPOINT_PATH"
    if ! parent_qcow2_bitmap_exists_on_all_disks; then
      echo "Parent qcow2 bitmap $PARENT_CHECKPOINT_NAME not found on all disks"
      cleanup
      exit 1
    fi
    redefine_checkpoint_if_needed "$VM" "$parent_checkpoint_file"
  fi

  echo "<domainbackup mode='push'>" > "$dest/backup.xml"
  if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
    echo "<incremental>$PARENT_CHECKPOINT_NAME</incremental>" >> "$dest/backup.xml"
  fi
  echo "<disks>" >> "$dest/backup.xml"
  echo "<domaincheckpoint><name>$CHECKPOINT_NAME</name><disks>" > "$dest/checkpoint.xml"
  local index=0
  while IFS='|' read -r disk target; do
    [[ -z "$disk" ]] && continue
    local backup_file
    backup_file=$(get_backup_file_by_index "$index" "$(basename "$target").qcow2")
    echo "<disk name='$disk' backup='yes' type='file'><target file='$dest/$backup_file' /><driver type='qcow2'/></disk>" >> "$dest/backup.xml"
    echo "<disk name='$disk' checkpoint='bitmap'/>" >> "$dest/checkpoint.xml"
    index=$((index + 1))
  done < <(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')
  echo "</disks></domainbackup>" >> "$dest/backup.xml"
  echo "</disks></domaincheckpoint>" >> "$dest/checkpoint.xml"

  local thaw=0
  if [[ ${QUIESCE} == "true" ]]; then
    if virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-freeze"}' > /dev/null 2>/dev/null; then
      thaw=1
    fi
  fi

  # Start push backup
  local backup_begin=0
  local backup_begin_output=""
  if backup_begin_output=$(virsh -c qemu:///system backup-begin --domain "$VM" --backupxml "$dest/backup.xml" --checkpointxml "$dest/checkpoint.xml" 2>&1); then
    backup_begin=1;
  fi

  if [[ $thaw -eq 1 ]]; then
    if ! response=$(virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-thaw"}' 2>&1 > /dev/null); then
      echo "Failed to thaw the filesystem for vm $VM: $response"
      cleanup
      exit 1
    fi
  fi

  if [[ $backup_begin -ne 1 ]]; then
    log -ne "FAILED libvirt backup-begin vm=[$VM] checkpoint=[$CHECKPOINT_NAME] output=[${backup_begin_output:-Unknown error}]"
    resume_vm_if_paused
    cleanup
    exit 1
  fi

  apply_backup_bandwidth_limit

  backup_domain_information "$VM"

  local wait_count=0
  while true; do
    status=$(virsh -c qemu:///system domjobinfo "$VM" --completed --keep-completed | awk '/Job type:/ {print $3}')
    case "$status" in
      Completed)
        break ;;
      Failed)
        log -ne "FAILED libvirt backup job vm=[$VM] checkpoint=[$CHECKPOINT_NAME]"
        echo "Virsh backup job failed"
        resume_vm_if_paused
        cleanup
        exit 1 ;;
    esac
    wait_count=$((wait_count + 1))
    if (( wait_count == 12 || wait_count % 120 == 0 )); then
      log -ne "WAIT libvirt backup job pending vm=[$VM] checkpoint=[$CHECKPOINT_NAME] elapsedSeconds=[$((wait_count * 5))] status=[${status:-unknown}]"
    fi
    sleep 5
  done

  if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_BACKUP_DIR" ]]; then
    local index=0
    while IFS='|' read -r disk target; do
      [[ -z "$disk" ]] && continue
      local backup_file
      backup_file=$(get_backup_file_by_index "$index" "$(basename "$target").qcow2")
      output="$dest/$backup_file"
      parent="../$(basename "$PARENT_BACKUP_DIR")/$backup_file"
      if ! qemu-img rebase -u -F qcow2 -b "$parent" "$output" >> "$logFile" 2> >(cat >&2); then
        log -ne "FAILED qemu-img rebase output=[$output] parent=[$parent]"
        echo "qemu-img rebase failed for $output with parent $parent"
        cleanup
        exit 1
      fi
      index=$((index + 1))
    done < <(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')
  fi

  cleanup_parent_qcow2_bitmap_after_success
  dump_checkpoint_xml "$VM"
  rm -f "$dest/backup.xml"
  rm -f "$dest/checkpoint.xml"
  sync
  mark_backup_complete

  # Print statistics
  virsh -c qemu:///system domjobinfo "$VM" --completed
  du -sb "$dest" | cut -f1

  timeout "$UNMOUNT_TIMEOUT" umount "$mount_point" 2>>"$logFile" || { log "WARNING: umount of $mount_point failed or timed out"; true; }
  rmdir "$mount_point" 2>>"$logFile" || { log "WARNING: rmdir of $mount_point failed"; true; }
}

backup_rbd_volumes() {
  log -ne "Entered backup_rbd_volumes with DISK_PATHS=[$DISK_PATHS], BACKUP_FILES=[$BACKUP_FILES], BACKUP_DIR=[$BACKUP_DIR]"
  mount_operation
  mkdir -p "$dest" || { echo "Failed to create backup directory $dest"; exit 1; }
  mark_backup_in_progress

  backup_domain_information "$VM"
  trap 'log -ne "FAILED RBD backup unexpected error line=[$LINENO] op=[$OP] vm=[$VM] checkpoint=[$CHECKPOINT_NAME]"; cleanup_created_rbd_snapshots' ERR
  trap 'log -ne "FAILED RBD backup interrupted op=[$OP] vm=[$VM] checkpoint=[$CHECKPOINT_NAME]"; cleanup_created_rbd_snapshots; exit 1' INT TERM

  local index=0
  while IFS= read -r disk; do
    log -ne "Loop disk raw value=[$disk]"
    [[ -z "$disk" ]] && continue

    parse_rbd_uri "$disk"
    log -ne "Parsed disk [$disk] -> RBD_IMAGE=[$RBD_IMAGE], MON=[$RBD_MON_HOST], USER=[$RBD_USER]"

    if [[ -z "$RBD_IMAGE" ]]; then
      echo "Unable to parse RBD disk path: $disk"
      cleanup
      exit 1
    fi

    build_rbd_cmd
    log -ne "Built RBD command: ${RBD_CMD[*]}"

    local backup_file
    backup_file=$(get_backup_file_by_index "$index" "${RBD_IMAGE##*/}.raw")
    local output="$dest/$backup_file"
    local current_snapshot="${CHECKPOINT_NAME}"

    log -ne "Resolved backup file [$backup_file], destination [$output]"
    log -ne "Starting RBD backup for disk path [$disk], resolved image [$RBD_IMAGE], output [$output]"

    if ! timeout 30s "${RBD_CMD[@]}" info "$RBD_IMAGE" >> "$logFile" 2>&1; then
      log -ne "FAILED RBD image access check image=[$RBD_IMAGE] timeout=[30s]"
      echo "Failed to access RBD image $RBD_IMAGE"
      cleanup_created_rbd_snapshots
      cleanup
      exit 1
    fi

    if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
      if ! timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>>"$logFile" | awk 'NR>1 {print $2}' | grep -Fxq "$PARENT_CHECKPOINT_NAME"; then
        log -ne "FAILED RBD parent snapshot check image=[$RBD_IMAGE] parentSnapshot=[$PARENT_CHECKPOINT_NAME]"
        echo "Parent RBD snapshot ${RBD_IMAGE}@${PARENT_CHECKPOINT_NAME} not found for incremental backup"
        cleanup_created_rbd_snapshots
        cleanup
        exit 1
      fi
    fi

    if ! timeout 30s "${RBD_CMD[@]}" snap create "${RBD_IMAGE}@${current_snapshot}" >> "$logFile" 2>&1; then
      log -ne "FAILED RBD snapshot create image=[$RBD_IMAGE] snapshot=[$current_snapshot] timeout=[30s]"
      echo "Failed to create RBD snapshot ${RBD_IMAGE}@${current_snapshot}"
      cleanup_created_rbd_snapshots
      cleanup
      exit 1
    fi
    record_created_rbd_snapshot "$disk" "$current_snapshot"

    if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
      local export_start
      export_start=$(date +%s)
      if ! timeout 6h "${RBD_CMD[@]}" export-diff --from-snap "$PARENT_CHECKPOINT_NAME" "${RBD_IMAGE}@${current_snapshot}" "$output" >> "$logFile" 2>&1; then
        log -ne "FAILED RBD export-diff image=[$RBD_IMAGE] snapshot=[$current_snapshot] output=[$output] elapsedSeconds=[$(($(date +%s) - export_start))] timeout=[6h]"
        echo "Failed to export incremental RBD diff for ${RBD_IMAGE}@${current_snapshot}"
        cleanup_created_rbd_snapshots
        cleanup
        exit 1
      fi
    else
      local export_start
      export_start=$(date +%s)
      if ! timeout 6h "${RBD_CMD[@]}" export "${RBD_IMAGE}@${current_snapshot}" "$output" >> "$logFile" 2>&1; then
        log -ne "FAILED RBD export image=[$RBD_IMAGE] snapshot=[$current_snapshot] output=[$output] elapsedSeconds=[$(($(date +%s) - export_start))] timeout=[6h]"
        echo "Failed to export full RBD snapshot ${RBD_IMAGE}@${current_snapshot}"
        cleanup_created_rbd_snapshots
        cleanup
        exit 1
      fi
    fi

    log -ne "Finished exporting backup file [$output] size=[$(stat -c %s "$output" 2>/dev/null)]"
    stat -c %s "$output"
    index=$((index + 1))
  done < <(split_csv "$DISK_PATHS")

  write_rbd_backup_metadata "$BACKUP_TYPE" "$CHECKPOINT_NAME" "$PARENT_CHECKPOINT_NAME"
  cleanup_parent_rbd_snapshot_after_success
  trap - ERR
  trap - INT TERM
  CREATED_RBD_SNAPSHOTS=()

  sync
  mark_backup_complete
  log -ne "RBD backup completed checkpoint=[$CHECKPOINT_NAME] parent=[$PARENT_CHECKPOINT_NAME]"
  timeout "$UNMOUNT_TIMEOUT" umount "$mount_point" 2>>"$logFile" || { log "WARNING: umount of $mount_point failed or timed out"; true; }
  rmdir "$mount_point" 2>>"$logFile" || { log "WARNING: rmdir of $mount_point failed"; true; }
}

backup_domain_information() {
  local vm_name="$1"

  [[ -z "$vm_name" ]] && return 0

  mkdir -p "$dest/checkpoints" || {
    echo "Failed to create checkpoint directory $dest/checkpoints"
    exit 1
  }

  if virsh -c qemu:///system dominfo "$vm_name" > /dev/null 2>&1; then
    virsh -c qemu:///system dumpxml "$vm_name" > "$dest/domain-config.xml" 2>/dev/null || true
    virsh -c qemu:///system dominfo "$vm_name" > "$dest/dominfo.xml" 2>/dev/null || true
    virsh -c qemu:///system domiflist "$vm_name" > "$dest/domiflist.xml" 2>/dev/null || true
    virsh -c qemu:///system domblklist "$vm_name" > "$dest/domblklist.xml" 2>/dev/null || true

    if [[ -n "$CHECKPOINT_NAME" ]]; then
      cat > "$dest/checkpoints/$CHECKPOINT_NAME.meta" <<EOF
checkpoint_name=$CHECKPOINT_NAME
backup_type=$BACKUP_TYPE
vm_name=$vm_name
disk_paths=$DISK_PATHS
backup_files=$BACKUP_FILES
EOF
    fi

    log -ne "Backed up domain information for VM [$vm_name]"
  else
    log -ne "VM [$vm_name] not found in libvirt; skipped domain metadata backup"
  fi
}

has_child_backup() {
  local checkpoint_name="$1"

  [[ -z "$checkpoint_name" ]] && return 1

  grep -R -q "^parent_checkpoint_name=$checkpoint_name$" "$mount_point"/*/rbd-backup.meta 2>/dev/null
}

has_child_checkpoint() {
  local checkpoint_name="$1"

  [[ -z "$checkpoint_name" ]] && return 1

  find "$mount_point" -path "$dest" -prune -o -type f -name "*.xml" -print 2>/dev/null \
    | xargs grep -F -l "<parent>" 2>/dev/null \
    | xargs grep -F -l "<name>$checkpoint_name</name>" 2>/dev/null \
    | grep -q .
}

delete_rbd_snapshot_if_unreferenced() {
  local disk_paths="$1"
  local checkpoint_name="$2"

  [[ -z "$checkpoint_name" ]] && return 0

  if has_child_backup "$checkpoint_name"; then
    log -ne "Skip snapshot delete [$checkpoint_name] (child exists)"
    return 0
  fi

  while IFS= read -r disk; do
    [[ -z "$disk" ]] && continue
    parse_rbd_uri "$disk"
    build_rbd_cmd

    if [[ -n "$RBD_IMAGE" ]]; then
      log -ne "Deleting snapshot [${RBD_IMAGE}@${checkpoint_name}]"
      "${RBD_CMD[@]}" snap rm "${RBD_IMAGE}@${checkpoint_name}" >> "$logFile" 2>&1 || true
    fi
  done < <(split_csv "$disk_paths")
}

delete_libvirt_checkpoint_if_unreferenced() {
  local checkpoint_name="$1"
  local vm_name

  [[ -z "$checkpoint_name" ]] && return 0

  if has_child_checkpoint "$checkpoint_name"; then
    log -ne "Skip libvirt checkpoint delete [$checkpoint_name] (child exists)"
    return 0
  fi

  vm_name="${VM:-$(basename "$(dirname "$dest")")}"
  if [[ -z "$vm_name" ]]; then
    return 0
  fi

  if virsh -c qemu:///system dominfo "$vm_name" > /dev/null 2>&1 \
      && virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$checkpoint_name" > /dev/null 2>&1; then
    log -ne "Deleting libvirt checkpoint [$checkpoint_name] from VM [$vm_name]"
    if ! virsh -c qemu:///system checkpoint-delete --domain "$vm_name" --checkpointname "$checkpoint_name" >> "$logFile" 2>&1; then
      log -ne "Failed to delete libvirt checkpoint [$checkpoint_name] from VM [$vm_name]; removing metadata only"
      virsh -c qemu:///system checkpoint-delete --domain "$vm_name" --checkpointname "$checkpoint_name" --metadata >> "$logFile" 2>&1 || true
    fi
  fi

  delete_qcow2_bitmap_if_present "$vm_name" "$checkpoint_name"
}

delete_qcow2_bitmap_if_present() {
  local vm_name="$1"
  local checkpoint_name="$2"
  local removed=0
  local node

  [[ -z "$vm_name" || -z "$checkpoint_name" ]] && return 0

  while IFS= read -r node; do
    [[ -z "$node" ]] && continue
    if virsh -c qemu:///system qemu-monitor-command "$vm_name" \
        "{\"execute\":\"block-dirty-bitmap-remove\",\"arguments\":{\"node\":\"$node\",\"name\":\"$checkpoint_name\"}}" \
        > /dev/null 2>>"$logFile"; then
      removed=$((removed + 1))
    else
      log -ne "Failed to remove qcow2 bitmap [$checkpoint_name] on node [$node] (non-fatal)"
    fi
  done < <(
    virsh -c qemu:///system qemu-monitor-command "$vm_name" '{"execute":"query-block"}' 2>/dev/null | python3 -c '
import sys, json
target = sys.argv[1]
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
seen = set()
for dev in data.get("return", []) or []:
    inserted = dev.get("inserted") or {}
    node = inserted.get("node-name")
    if not node or node in seen:
        continue
    if any((bitmap or {}).get("name") == target for bitmap in (inserted.get("dirty-bitmaps") or [])):
        seen.add(node)
        print(node)
' "$checkpoint_name" 2>/dev/null || true
  )

  if [[ "$removed" -gt 0 ]]; then
    log -ne "Removed qcow2 bitmap [$checkpoint_name] from [$removed] disk(s)"
  fi
}

cleanup_unreferenced_qcow2_bitmaps() {
  local vm_name
  local checkpoint_name

  [[ -z "$CLEANUP_CHECKPOINT_NAMES" ]] && return 0

  vm_name="${VM:-$(basename "$(dirname "$dest")")}"
  [[ -z "$vm_name" ]] && return 0

  while IFS= read -r checkpoint_name; do
    [[ -z "$checkpoint_name" ]] && continue
    log -ne "Cleaning up unreferenced qcow2 bitmap [$checkpoint_name] from VM [$vm_name]"
    delete_qcow2_bitmap_if_present "$vm_name" "$checkpoint_name"
  done < <(split_csv "$CLEANUP_CHECKPOINT_NAMES")
}

delete_backup() {
  mount_operation

  if [[ -f "$dest/rbd-backup.meta" ]]; then
    source "$dest/rbd-backup.meta"

    log -ne "Deleting backup with metadata [$dest]"

    if [[ "$FORCED" != "true" ]] && has_child_backup "$checkpoint_name"; then
      echo "Cannot delete backup [$backup_dir]: child backup exists"
      umount "$mount_point"
      rmdir "$mount_point"
      exit 1
    fi

    delete_rbd_snapshot_if_unreferenced "$disk_paths" "$checkpoint_name"
  elif [[ -n "$CHECKPOINT_NAME" && -n "$DISK_PATHS" ]]; then
    log -ne "Deleting backup using command metadata [$dest]"
    delete_rbd_snapshot_if_unreferenced "$DISK_PATHS" "$CHECKPOINT_NAME"
  elif [[ -n "$CHECKPOINT_NAME" ]]; then
    log -ne "Deleting file-backed backup using command metadata [$dest]"
    delete_libvirt_checkpoint_if_unreferenced "$CHECKPOINT_NAME"
  fi

  cleanup_unreferenced_qcow2_bitmaps
  rm -frv "$dest" || { echo "Failed to delete $dest"; exit 1; }
  if [[ -e "$dest" ]]; then
    echo "Backup directory still exists after delete: $dest"
    exit 1
  fi
  sync
  umount "$mount_point"
  rmdir "$mount_point"
}

get_backup_stats() {
  mount_operation

  echo $mount_point
  df -P $mount_point 2>/dev/null | awk 'NR==2 {print $2, $3}'
  umount $mount_point
  rmdir $mount_point
}

inspect_backup() {
  mount_operation

  local required_files_present=true
  local backup_path_exists=false
  local complete=false
  local in_progress=false
  local missing_files=""

  if [[ -d "$dest" ]]; then
    backup_path_exists=true
  fi
  if [[ -f "$dest/$COMPLETE_MARKER" ]]; then
    complete=true
  fi
  if [[ -f "$dest/$IN_PROGRESS_MARKER" ]]; then
    in_progress=true
  fi

  while IFS= read -r backup_file; do
    [[ -z "$backup_file" ]] && continue
    if [[ ! -s "$dest/$backup_file" ]]; then
      required_files_present=false
      missing_files="${missing_files}${missing_files:+,}${backup_file}"
    fi
  done < <(split_csv "$BACKUP_FILES")

  if [[ -n "$CHECKPOINT_NAME" && ! -f "$dest/checkpoints/$CHECKPOINT_NAME.xml" && ! -f "$dest/checkpoints/$CHECKPOINT_NAME.meta" ]]; then
    required_files_present=false
    missing_files="${missing_files}${missing_files:+,}checkpoints/$CHECKPOINT_NAME"
  fi

  echo "backupPathExists=$backup_path_exists"
  echo "complete=$complete"
  echo "inProgress=$in_progress"
  echo "requiredFilesPresent=$required_files_present"
  echo "size=$(du -sb "$dest" 2>/dev/null | cut -f1 || echo 0)"
  echo "missingFiles=$missing_files"

  umount "$mount_point"
  rmdir "$mount_point"
}

mount_operation() {
  mount_point=$(mktemp -d -t csbackup.XXXXX)
  dest="$mount_point/${BACKUP_DIR}"
  if [ ${NAS_TYPE} == "cifs" ]; then
    MOUNT_OPTS="${MOUNT_OPTS},nobrl"
  fi
  log -ne "Mounting ${NAS_TYPE} store [${NAS_ADDRESS}] at [${mount_point}] with timeout [${MOUNT_TIMEOUT}]"
  set +e
  if [[ "$MOUNT_TIMEOUT" -gt 0 ]]; then
    timeout -k 5s "${MOUNT_TIMEOUT}s" mount -t ${NAS_TYPE} ${NAS_ADDRESS} ${mount_point} $([[ ! -z "${MOUNT_OPTS}" ]] && echo -o ${MOUNT_OPTS}) 2>&1 | tee -a "$logFile"
  else
    mount -t ${NAS_TYPE} ${NAS_ADDRESS} ${mount_point} $([[ ! -z "${MOUNT_OPTS}" ]] && echo -o ${MOUNT_OPTS}) 2>&1 | tee -a "$logFile"
  fi
  mount_status=${PIPESTATUS[0]}
  set -e
  if [ $mount_status -eq 0 ]; then
      log -ne "Successfully mounted ${NAS_TYPE} store [${NAS_ADDRESS}] at [${mount_point}]"
  else
      log -ne "FAILED NAS mount type=[$NAS_TYPE] address=[$NAS_ADDRESS] mountPoint=[$mount_point] timeout=[$MOUNT_TIMEOUT] exitCode=[$mount_status]"
      echo "Failed to mount ${NAS_TYPE} store at ${mount_point}"
      rmdir "$mount_point" 2>>"$logFile" || { log "WARNING: rmdir of $mount_point failed after mount failure"; true; }
      exit $mount_status
  fi
}

mark_backup_in_progress() {
  rm -f "$dest/$COMPLETE_MARKER"
  printf 'started_at=%s\nvm=%s\ncheckpoint=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$VM" "$CHECKPOINT_NAME" > "$dest/$IN_PROGRESS_MARKER"
  sync "$dest/$IN_PROGRESS_MARKER" 2>/dev/null || true
}

mark_backup_complete() {
  local tmp_marker="$dest/$COMPLETE_MARKER.tmp"
  printf 'completed_at=%s\nvm=%s\ncheckpoint=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$VM" "$CHECKPOINT_NAME" > "$tmp_marker"
  mv -f "$tmp_marker" "$dest/$COMPLETE_MARKER"
  rm -f "$dest/$IN_PROGRESS_MARKER"
  sync "$dest/$COMPLETE_MARKER" 2>/dev/null || true
}

cleanup() {
  local status=0

  rm -rf "$dest" || { echo "Failed to delete $dest"; status=1; }
  if [[ -e "$dest" ]]; then
    echo "Backup directory still exists after cleanup: $dest"
    status=1
  fi
  timeout "$UNMOUNT_TIMEOUT" umount "$mount_point" 2>>"$logFile" || { log "WARNING: umount of $mount_point failed or timed out"; status=1; }
  rmdir "$mount_point" 2>>"$logFile" || { log "WARNING: rmdir of $mount_point failed"; status=1; }

  if [[ $status -ne 0 ]]; then
    log -ne "FAILED cleanup dest=[$dest] mountPoint=[$mount_point] status=[$status]"
    echo "Backup cleanup failed"
    exit $EXIT_CLEANUP_FAILED
  fi
}

split_csv() {
  tr ',' '\n' <<< "$1"
}

is_rbd_disk_path() {
  local disk_path="$1"
  [[ "$disk_path" == rbd:* || "$disk_path" == rbd/* ]]
}

get_backup_file_by_index() {
  local index="$1"
  local fallback="$2"
  if [[ -z "$BACKUP_FILES" ]]; then
    echo "$fallback"
    return
  fi
  local current=0
  while IFS= read -r value; do
    if [[ "$current" -eq "$index" ]]; then
      echo "$value"
      return
    fi
    current=$((current + 1))
  done < <(split_csv "$BACKUP_FILES")
  echo "$fallback"
}

dump_checkpoint_xml() {
  local vm_name="$1"
  if [[ -n "$CHECKPOINT_NAME" ]]; then
    virsh -c qemu:///system checkpoint-dumpxml --domain "$vm_name" --checkpointname "$CHECKPOINT_NAME" --no-domain > "$dest/checkpoints/$CHECKPOINT_NAME.xml" 2>/dev/null || true
  fi
}

redefine_checkpoint_if_needed() {
  local vm_name="$1"
  local checkpoint_file="$2"
  if [[ -z "$PARENT_CHECKPOINT_NAME" || -z "$checkpoint_file" || ! -f "$checkpoint_file" ]]; then
    return
  fi
  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$PARENT_CHECKPOINT_NAME" > /dev/null 2>&1; then
    return
  fi
  if ! virsh -c qemu:///system checkpoint-create --domain "$vm_name" --xmlfile "$checkpoint_file" --redefine > /dev/null 2>&1; then
    echo "Failed to redefine checkpoint $PARENT_CHECKPOINT_NAME on domain $vm_name"
    cleanup
  fi
}

parent_qcow2_bitmap_exists_on_all_disks() {
  [[ "$BACKUP_TYPE" != "INCREMENTAL" || -z "$PARENT_CHECKPOINT_NAME" ]] && return 0

  local disk_count
  local bitmap_count
  disk_count=$(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '$2=="disk"{c++} END{print c+0}')
  bitmap_count=$(virsh -c qemu:///system qemu-monitor-command "$VM" '{"execute":"query-block"}' 2>/dev/null | python3 -c '
import sys, json
target = sys.argv[1]
try:
    data = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
files = set()
for dev in data.get("return", []) or []:
    inserted = dev.get("inserted") or {}
    f = inserted.get("file")
    if not f:
        continue
    if any((bitmap or {}).get("name") == target for bitmap in (inserted.get("dirty-bitmaps") or [])):
        files.add(f)
print(len(files))
' "$PARENT_CHECKPOINT_NAME" 2>/dev/null || echo 0)

  [[ "$disk_count" -gt 0 && "$bitmap_count" -ge "$disk_count" ]]
}

cleanup_parent_qcow2_bitmap_after_success() {
  [[ "$BACKUP_TYPE" != "INCREMENTAL" || -z "$PARENT_CHECKPOINT_NAME" ]] && return

  local expected=0
  local removed=0
  local node

  while IFS= read -r node; do
    [[ -z "$node" ]] && continue
    expected=$((expected + 1))
    if virsh -c qemu:///system qemu-monitor-command "$VM" \
        "{\"execute\":\"block-dirty-bitmap-remove\",\"arguments\":{\"node\":\"$node\",\"name\":\"$PARENT_CHECKPOINT_NAME\"}}" \
        > /dev/null 2>>"$logFile"; then
      removed=$((removed + 1))
    else
      log -ne "Failed to remove previous qcow2 parent bitmap [$PARENT_CHECKPOINT_NAME] on node [$node] (non-fatal)"
    fi
  done < <(
    virsh -c qemu:///system qemu-monitor-command "$VM" '{"execute":"query-block"}' 2>/dev/null | python3 -c '
import sys, json
target = sys.argv[1]
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
seen = set()
for dev in data.get("return", []) or []:
    inserted = dev.get("inserted") or {}
    node = inserted.get("node-name")
    if not node or node in seen:
        continue
    if any((bitmap or {}).get("name") == target for bitmap in (inserted.get("dirty-bitmaps") or [])):
        seen.add(node)
        print(node)
' "$PARENT_CHECKPOINT_NAME" 2>/dev/null || true
  )

  if [[ "$expected" -gt 0 && "$removed" -eq "$expected" ]]; then
    log -ne "Removed previous qcow2 parent bitmap [$PARENT_CHECKPOINT_NAME] from [$removed] disk(s)"
  fi
}

parse_rbd_uri() {
  local uri="$1"
  log -ne "parse_rbd_uri called with uri=[$uri]"

  RBD_IMAGE=""
  RBD_MON_HOST=""
  RBD_USER=""
  RBD_KEY=""

  if [[ "$uri" == rbd:* ]]; then
    local payload="${uri#rbd:}"
    if [[ "$payload" == *":mon_host="* ]]; then
      RBD_IMAGE="${payload%%:mon_host=*}"
      local mon_part="${payload#*:mon_host=}"
      RBD_MON_HOST="${mon_part%%:auth_supported=*}"
      RBD_MON_HOST="${RBD_MON_HOST//\\;/,}"
      RBD_MON_HOST="${RBD_MON_HOST//\\:/:}"
    else
      RBD_IMAGE="${payload%%:*}"
    fi

    if [[ "$payload" == *":id="* ]]; then
      local id_part="${payload#*:id=}"
      RBD_USER="${id_part%%:*}"
    fi

    if [[ "$payload" == *":key="* ]]; then
      local key_part="${payload#*:key=}"
      RBD_KEY="${key_part%%:*}"
    fi
  elif [[ "$uri" == rbd/* ]]; then
    RBD_IMAGE="$uri"
  else
    echo "Invalid RBD disk path: $uri"
    cleanup
  fi

  if [[ -z "$RBD_IMAGE" ]]; then
    echo "Failed to parse RBD image from uri: $uri"
    cleanup
  fi

  log -ne "Parsed RBD uri -> IMAGE=[$RBD_IMAGE], MON=[$RBD_MON_HOST], USER=[$RBD_USER]"
}

build_rbd_cmd() {
  RBD_CMD=(rbd)
  if [[ -n "$RBD_MON_HOST" ]]; then
    RBD_CMD+=(-m "$RBD_MON_HOST")
  fi
  if [[ -n "$RBD_USER" ]]; then
    RBD_CMD+=(--id "$RBD_USER")
  fi
  if [[ -n "$RBD_KEY" ]]; then
    RBD_CMD+=(--key "$RBD_KEY")
  fi
}

record_created_rbd_snapshot() {
  local disk_path="$1"
  local checkpoint_name="$2"
  [[ -z "$disk_path" || -z "$checkpoint_name" ]] && return
  CREATED_RBD_SNAPSHOTS+=("${disk_path}|${checkpoint_name}")
}

cleanup_created_rbd_snapshots() {
  [[ "${#CREATED_RBD_SNAPSHOTS[@]}" -eq 0 ]] && return

  local snapshot_entry
  for snapshot_entry in "${CREATED_RBD_SNAPSHOTS[@]}"; do
    local disk_path="${snapshot_entry%%|*}"
    local checkpoint_name="${snapshot_entry#*|}"
    [[ -z "$disk_path" || -z "$checkpoint_name" ]] && continue

    parse_rbd_uri "$disk_path"
    build_rbd_cmd
    if timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>/dev/null | awk 'NR>1 {print $2}' | grep -Fxq "$checkpoint_name"; then
      log -ne "Cleaning failed RBD backup snapshot [${RBD_IMAGE}@${checkpoint_name}]"
      "${RBD_CMD[@]}" snap rm "${RBD_IMAGE}@${checkpoint_name}" >> "$logFile" 2>&1 || true
    fi
  done
  CREATED_RBD_SNAPSHOTS=()
}

cleanup_parent_rbd_snapshot_after_success() {
  [[ "$BACKUP_TYPE" != "INCREMENTAL" || -z "$PARENT_CHECKPOINT_NAME" ]] && return
  [[ "$PARENT_CHECKPOINT_NAME" == "$CHECKPOINT_NAME" ]] && return

  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    parse_rbd_uri "$disk_path"
    build_rbd_cmd

    if timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>/dev/null | awk 'NR>1 {print $2}' | grep -Fxq "$PARENT_CHECKPOINT_NAME"; then
      log -ne "Deleting previous RBD parent snapshot after successful backup [${RBD_IMAGE}@${PARENT_CHECKPOINT_NAME}]"
      if ! "${RBD_CMD[@]}" snap rm "${RBD_IMAGE}@${PARENT_CHECKPOINT_NAME}" >> "$logFile" 2>&1; then
        log -ne "Failed to delete previous RBD parent snapshot [${RBD_IMAGE}@${PARENT_CHECKPOINT_NAME}]"
      fi
    fi
  done < <(split_csv "$DISK_PATHS")
}

write_rbd_backup_metadata() {
  local backup_type="$1"
  local checkpoint_name="$2"
  local parent_checkpoint_name="$3"

  cat > "$dest/rbd-backup.meta" <<EOF
vm_name=$VM
backup_type=$backup_type
checkpoint_name=$checkpoint_name
parent_checkpoint_name=$parent_checkpoint_name
disk_paths=$DISK_PATHS
backup_files=$BACKUP_FILES
backup_dir=$BACKUP_DIR
EOF

  log -ne "Wrote RBD backup metadata to [$dest/rbd-backup.meta]"
}

function usage {
  echo ""
  echo "Usage: $0 -o <operation> -v|--vm <domain name> -t <storage type> -s <storage address> -m <mount options> -w <mount timeout seconds> -p <backup path> -b <FULL|INCREMENTAL> -c <checkpoint name> -r <parent backup path> -i <parent checkpoint name> -j <parent checkpoint path> -f <backup files> -d <disks path> -q|--quiesce <true|false> --bandwidth-limit-mbps <mbps> -x|--forced <true|false>"
  echo ""
  exit 1
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -o|--operation)
      OP="$2"
      shift
      shift
      ;;
    -v|--vm)
      VM="$2"
      shift
      shift
      ;;
    -t|--type)
      NAS_TYPE="$2"
      shift
      shift
      ;;
    -s|--storage)
      NAS_ADDRESS="$2"
      shift
      shift
      ;;
    -m|--mount)
      MOUNT_OPTS="$2"
      shift
      shift
      ;;
    -w|--mount-timeout)
      MOUNT_TIMEOUT="$2"
      shift
      shift
      ;;
    -p|--path)
      BACKUP_DIR="$2"
      shift
      shift
      ;;
    -b|--backuptype)
      BACKUP_TYPE="$2"
      shift
      shift
      ;;
    -c|--checkpoint)
      CHECKPOINT_NAME="$2"
      shift
      shift
      ;;
    -r|--parentpath)
      PARENT_BACKUP_DIR="$2"
      shift
      shift
      ;;
    -i|--parentcheckpoint)
      PARENT_CHECKPOINT_NAME="$2"
      shift
      shift
      ;;
    -j|--parentcheckpointpath)
      PARENT_CHECKPOINT_PATH="$2"
      shift
      shift
      ;;
    -f|--backupfiles)
      BACKUP_FILES="$2"
      shift
      shift
      ;;
    -q|--quiesce)
      QUIESCE="$2"
      shift
      shift
      ;;
    --bandwidth-limit-mbps)
      BACKUP_BANDWIDTH_LIMIT_MBPS="$2"
      shift
      shift
      ;;
    -x|--forced)
      FORCED="$2"
      shift
      shift
      ;;
    -C|--cleanupcheckpoints)
      CLEANUP_CHECKPOINT_NAMES="$2"
      shift
      shift
      ;;
    -d|--diskpaths)
      DISK_PATHS="$2"
      shift
      shift
      ;;
    -h|--help)
      usage
      shift
      ;;
    *)
      echo "Invalid option: $1"
      usage
      ;;
  esac
done

# Perform Initial sanity checks
sanity_checks

log -ne "nasbackup.sh start op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] nasType=[$NAS_TYPE] nasAddress=[$NAS_ADDRESS] mountTimeout=[$MOUNT_TIMEOUT] backupType=[$BACKUP_TYPE] checkpoint=[$CHECKPOINT_NAME] parentBackup=[$PARENT_BACKUP_DIR] parentCheckpoint=[$PARENT_CHECKPOINT_NAME] diskPaths=[$DISK_PATHS] backupFiles=[$BACKUP_FILES] bandwidthLimitMbps=[$BACKUP_BANDWIDTH_LIMIT_MBPS]"

if [ "$OP" = "backup-running" ]; then
  backup_running_vm
elif [ "$OP" = "backup-rbd" ]; then
  backup_rbd_volumes
elif [ "$OP" = "delete" ]; then
  delete_backup
elif [ "$OP" = "stats" ]; then
  get_backup_stats
elif [ "$OP" = "inspect" ]; then
  inspect_backup
fi
