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
BACKUP_DIR=""
BACKUP_TYPE=""
CHECKPOINT_NAME=""
PARENT_BACKUP_DIR=""
PARENT_CHECKPOINT_NAME=""
PARENT_CHECKPOINT_PATH=""
BACKUP_FILES=""
DISK_PATHS=""
STAGING_DISK_PATHS=""
SOURCE_FORMAT="vmdk"
VEEAM_RESTORE_POINT_ID=""
BOOTSTRAP_CHECKPOINT="true"
QUIESCE=""
FORCED="false"
CLEANUP_CHECKPOINT_NAMES=""
logFile="/var/log/cloudstack/agent/agent.log"
UNMOUNT_TIMEOUT=60
CREATED_RBD_SNAPSHOTS=()

EXIT_CLEANUP_FAILED=20

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

### Operation methods ###

backup_running_vm() {
  mount_operation
  mkdir -p "$dest" || { echo "Failed to create backup directory $dest"; exit 1; }
  mkdir -p "$dest/checkpoints" || { echo "Failed to create checkpoint directory $dest/checkpoints"; exit 1; }

  local parent_checkpoint_file=""
  if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_PATH" ]]; then
    parent_checkpoint_file="$mount_point/$PARENT_CHECKPOINT_PATH"
    if ! parent_qcow2_bitmap_exists_on_all_disks; then
      echo "Parent qcow2 bitmap $PARENT_CHECKPOINT_NAME not found on all disks"
      cleanup
      exit 1
    fi
    redefine_checkpoint_if_needed "$VM" "$parent_checkpoint_file" "$mount_point"
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
    backup_begin=1
  else
    echo "backup-begin failed for VM $VM: $backup_begin_output" >> "$logFile"
    echo "backup-begin failed for VM $VM: $backup_begin_output"
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
    cleanup
    exit 1
  fi

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
        cleanup ;;
    esac
    wait_count=$((wait_count + 1))
    if (( wait_count % 12 == 0 )); then
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
      fi
      index=$((index + 1))
    done < <(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')
  fi

  cleanup_parent_qcow2_bitmap_after_success
  dump_checkpoint_xml "$VM"
  rm -f "$dest/backup.xml"
  rm -f "$dest/checkpoint.xml"
  sync

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
    fi

    if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
      if ! timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>>"$logFile" | awk 'NR>1 {print $2}' | grep -Fxq "$PARENT_CHECKPOINT_NAME"; then
        log -ne "FAILED RBD parent snapshot check image=[$RBD_IMAGE] parentSnapshot=[$PARENT_CHECKPOINT_NAME]"
        echo "Parent RBD snapshot ${RBD_IMAGE}@${PARENT_CHECKPOINT_NAME} not found for incremental backup"
        cleanup_created_rbd_snapshots
        cleanup
      fi
    fi

    if ! timeout 30s "${RBD_CMD[@]}" snap create "${RBD_IMAGE}@${current_snapshot}" >> "$logFile" 2>&1; then
      log -ne "FAILED RBD snapshot create image=[$RBD_IMAGE] snapshot=[$current_snapshot] timeout=[30s]"
      echo "Failed to create RBD snapshot ${RBD_IMAGE}@${current_snapshot}"
      cleanup_created_rbd_snapshots
      cleanup
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
      fi
    else
      local export_start
      export_start=$(date +%s)
      if ! timeout 6h "${RBD_CMD[@]}" export "${RBD_IMAGE}@${current_snapshot}" "$output" >> "$logFile" 2>&1; then
        log -ne "FAILED RBD export image=[$RBD_IMAGE] snapshot=[$current_snapshot] output=[$output] elapsedSeconds=[$(($(date +%s) - export_start))] timeout=[6h]"
        echo "Failed to export full RBD snapshot ${RBD_IMAGE}@${current_snapshot}"
        cleanup_created_rbd_snapshots
        cleanup
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

mount_operation() {
  mount_point=$(mktemp -d -t csbackup.XXXXX)
  dest="$mount_point/${BACKUP_DIR}"

  # Local data disk: NAS_ADDRESS is a directory already mounted on this host
  # (e.g. a dedicated data disk). Bind-mount it so the rest of the flow
  # (dest/umount/df) keeps working without a network mount.
  case "${NAS_TYPE}" in
    local|dir|localfs)
      if [[ ! -d "${NAS_ADDRESS}" ]]; then
        echo "Local backup directory does not exist: ${NAS_ADDRESS}"
        exit 1
      fi
      mount --bind "${NAS_ADDRESS}" "${mount_point}" 2>&1 | tee -a "$logFile"
      if [ ${PIPESTATUS[0]} -eq 0 ]; then
        log -ne "Successfully bind-mounted local backup dir ${NAS_ADDRESS}"
      else
        echo "Failed to bind-mount local backup dir ${NAS_ADDRESS}"
        exit 1
      fi
      return 0
      ;;
  esac

  if [ ${NAS_TYPE} == "cifs" ]; then
    MOUNT_OPTS="${MOUNT_OPTS},nobrl"
  fi
  mount -t ${NAS_TYPE} ${NAS_ADDRESS} ${mount_point} $([[ ! -z "${MOUNT_OPTS}" ]] && echo -o ${MOUNT_OPTS}) 2>&1 | tee -a "$logFile"
  if [ $? -eq 0 ]; then
      log -ne "Successfully mounted ${NAS_TYPE} store"
  else
      log -ne "FAILED NAS mount type=[$NAS_TYPE] address=[$NAS_ADDRESS] mountPoint=[$mount_point]"
      echo "Failed to mount ${NAS_TYPE} store"
      exit 1
  fi
}

cleanup() {
  local status=0

  rm -rf "$dest" || { echo "Failed to delete $dest"; status=1; }
  if [[ -e "$dest" ]]; then
    echo "Backup directory still exists after cleanup: $dest"
    status=1
  fi
  umount "$mount_point" || { echo "Failed to unmount $mount_point"; status=1; }
  rmdir "$mount_point" || { echo "Failed to remove mount point $mount_point"; status=1; }

  if [[ $status -ne 0 ]]; then
    log -ne "FAILED cleanup dest=[$dest] mountPoint=[$mount_point] status=[$status]"
    echo "Backup cleanup failed"
    exit $EXIT_CLEANUP_FAILED
  fi
  exit 1
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

strip_checkpoint_parent_from_xml() {
  local xml_file="$1"
  [[ -f "$xml_file" ]] || return 0
  python3 - "$xml_file" <<'PY' 2>/dev/null || true
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()
for parent in list(root.findall('parent')):
    root.remove(parent)
tree.write(path, encoding='unicode', xml_declaration=True)
PY
}

get_parent_checkpoint_name_from_xml() {
  local xml_file="$1"
  [[ -f "$xml_file" ]] || return 0
  python3 - "$xml_file" <<'PY' 2>/dev/null
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
    parent = root.find('parent')
    if parent is None:
        sys.exit(0)
    name = parent.findtext('name', '').strip()
    if name:
        print(name)
except Exception:
    pass
PY
}

find_checkpoint_xml_on_nas() {
  local search_root="$1" checkpoint_name="$2"
  [[ -n "$search_root" && -n "$checkpoint_name" && -d "$search_root" ]] || return 1
  find "$search_root" -maxdepth 5 -type f -name "${checkpoint_name}.xml" 2>/dev/null | head -1
}

redefine_checkpoint_chain_if_needed() {
  local vm_name="$1" checkpoint_file="$2" search_root="$3"
  local checkpoint_name parent_name parent_file visited_key

  [[ -n "$checkpoint_file" && -f "$checkpoint_file" ]] || return 0
  checkpoint_name="$(basename "$checkpoint_file" .xml)"
  visited_key="|${checkpoint_name}|"
  if [[ "${REDEFINE_VISITED_CHECKPOINTS:-}" == *"$visited_key"* ]]; then
    return 0
  fi
  REDEFINE_VISITED_CHECKPOINTS="${REDEFINE_VISITED_CHECKPOINTS:-}${visited_key}"

  parent_name="$(get_parent_checkpoint_name_from_xml "$checkpoint_file")"
  if [[ -n "$parent_name" ]]; then
    parent_file="$(find_checkpoint_xml_on_nas "$search_root" "$parent_name")"
    if [[ -n "$parent_file" && -f "$parent_file" ]]; then
      redefine_checkpoint_chain_if_needed "$vm_name" "$parent_file" "$search_root"
    else
      strip_checkpoint_parent_from_xml "$checkpoint_file"
    fi
  fi

  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$checkpoint_name" > /dev/null 2>&1; then
    return 0
  fi
  if ! virsh -c qemu:///system checkpoint-create --domain "$vm_name" --xmlfile "$checkpoint_file" --redefine >> "$logFile" 2>&1; then
    echo "Failed to redefine checkpoint ${checkpoint_name} on domain ${vm_name}"
    cleanup
  fi
}

redefine_checkpoint_if_needed() {
  local vm_name="$1" checkpoint_file="$2" search_root="${3:-}"
  if [[ -z "$PARENT_CHECKPOINT_NAME" || -z "$checkpoint_file" || ! -f "$checkpoint_file" ]]; then
    return
  fi
  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$PARENT_CHECKPOINT_NAME" > /dev/null 2>&1; then
    return
  fi
  REDEFINE_VISITED_CHECKPOINTS=""
  redefine_checkpoint_chain_if_needed "$vm_name" "$checkpoint_file" "${search_root:-$mount_point}"
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

write_veeam_seed_metadata() {
  local backup_engine="$1"
  cat > "$dest/veeam-seed.meta" <<EOF
source_provider=ablestack-veeam
veeam_restore_point_id=$VEEAM_RESTORE_POINT_ID
vm_name=$VM
backup_type=FULL
backup_engine=$backup_engine
checkpoint_name=$CHECKPOINT_NAME
parent_checkpoint_name=
disk_paths=$DISK_PATHS
backup_files=$BACKUP_FILES
backup_dir=$BACKUP_DIR
source_format=$SOURCE_FORMAT
bootstrap_checkpoint=$BOOTSTRAP_CHECKPOINT
imported_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
  log -ne "Wrote Veeam seed metadata to [$dest/veeam-seed.meta]"
}

convert_staging_disk_to_backup() {
  local staging_path="$1"
  local output="$2"

  if [[ ! -f "$staging_path" ]]; then
    echo "Staging disk file not found: $staging_path"
    cleanup
  fi

  case "$SOURCE_FORMAT" in
    qcow2)
      if ! cp -f "$staging_path" "$output"; then
        echo "Failed to copy qcow2 staging disk $staging_path to $output"
        cleanup
      fi
      ;;
    vmdk|flat|raw)
      if ! qemu-img convert -p -O qcow2 "$staging_path" "$output" >> "$logFile" 2>&1; then
        echo "Failed to convert staging disk $staging_path to $output"
        cleanup
      fi
      ;;
    *)
      echo "Unsupported source format: $SOURCE_FORMAT"
      cleanup
      ;;
  esac
}

import_rbd_seed_disk() {
  local staging_path="$1"
  local output="$2"
  local disk_uri="$3"

  if ! qemu-img convert -p -O raw "$staging_path" "$output" >> "$logFile" 2>&1; then
    echo "Failed to convert staging disk $staging_path to $output"
    cleanup
  fi

  parse_rbd_uri "$disk_uri"
  build_rbd_cmd
  if [[ -z "$RBD_IMAGE" ]]; then
    echo "Unable to parse RBD disk path for seed import: $disk_uri"
    cleanup
  fi

  if ! timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>>"$logFile" | awk 'NR>1 {print $2}' | grep -Fxq "$CHECKPOINT_NAME"; then
    if ! timeout 30s "${RBD_CMD[@]}" snap create "${RBD_IMAGE}@${CHECKPOINT_NAME}" >> "$logFile" 2>&1; then
      echo "Failed to create RBD baseline snapshot ${RBD_IMAGE}@${CHECKPOINT_NAME}"
      cleanup
    fi
  fi
}

bootstrap_qcow2_checkpoint() {
  local vm_name="$1"

  if [[ -z "$vm_name" ]]; then
    log -ne "Skip checkpoint bootstrap: VM name not set"
    return 0
  fi

  if ! virsh -c qemu:///system dominfo "$vm_name" > /dev/null 2>&1; then
    log -ne "Skip checkpoint bootstrap: VM [$vm_name] not found in libvirt"
    return 0
  fi

  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$CHECKPOINT_NAME" > /dev/null 2>&1; then
    dump_checkpoint_xml "$vm_name"
    return 0
  fi

  echo "<domainbackup mode='push'>" > "$dest/backup.xml"
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
  done < <(virsh -c qemu:///system domblklist "$vm_name" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')
  echo "</disks></domainbackup>" >> "$dest/backup.xml"
  echo "</disks></domaincheckpoint>" >> "$dest/checkpoint.xml"

  if ! virsh -c qemu:///system backup-begin --domain "$vm_name" --backupxml "$dest/backup.xml" --checkpointxml "$dest/checkpoint.xml" >> "$logFile" 2>&1; then
    echo "Failed to bootstrap checkpoint on VM $vm_name"
    cleanup
  fi

  while true; do
    local status
    status=$(virsh -c qemu:///system domjobinfo "$vm_name" --completed --keep-completed 2>/dev/null | awk '/Job type:/ {print $3}')
    case "$status" in
      Completed) break ;;
      Failed)
        echo "Virsh checkpoint bootstrap job failed for VM $vm_name"
        cleanup ;;
    esac
    sleep 5
  done

  dump_checkpoint_xml "$vm_name"
  rm -f "$dest/backup.xml" "$dest/checkpoint.xml"
  log -ne "Bootstrapped libvirt checkpoint [$CHECKPOINT_NAME] on VM [$vm_name]"
}

# Veeam seed: NAS qcow2 already exists from staging convert — only create live-disk bitmap checkpoint.
bootstrap_qcow2_checkpoint_seed() {
  local vm_name="$1"
  local -a diskspec_args=()
  local disk

  if [[ -z "$vm_name" ]]; then
    log -ne "Skip checkpoint bootstrap: VM name not set"
    return 0
  fi

  if ! virsh -c qemu:///system dominfo "$vm_name" > /dev/null 2>&1; then
    log -ne "Skip checkpoint bootstrap: VM [$vm_name] not found in libvirt"
    return 0
  fi

  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$CHECKPOINT_NAME" > /dev/null 2>&1; then
    dump_checkpoint_xml "$vm_name"
    return 0
  fi

  while IFS='|' read -r disk _target; do
    [[ -z "$disk" ]] && continue
    diskspec_args+=(--diskspec "${disk},bitmap=${CHECKPOINT_NAME}")
  done < <(virsh -c qemu:///system domblklist "$vm_name" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')

  if [[ ${#diskspec_args[@]} -eq 0 ]]; then
    echo "No disks found for checkpoint bootstrap on VM $vm_name"
    cleanup
  fi

  if ! virsh -c qemu:///system checkpoint-create-as "$vm_name" "$CHECKPOINT_NAME" \
      "${diskspec_args[@]}" >> "$logFile" 2>&1; then
    log -ne "Warn: checkpoint-create-as failed for seed on VM [$vm_name] (glue-gfs/raw may not support bitmap); continuing without checkpoint"
    return 0
  fi

  dump_checkpoint_xml "$vm_name"
  strip_checkpoint_parent_from_xml "$dest/checkpoints/$CHECKPOINT_NAME.xml"
  log -ne "Bootstrapped libvirt checkpoint (seed) [$CHECKPOINT_NAME] on VM [$vm_name]"
}

import_veeam_seed() {
  log -ne "Entered import_veeam_seed staging=[$STAGING_DISK_PATHS] backupDir=[$BACKUP_DIR]"
  mount_operation
  mkdir -p "$dest" "$dest/checkpoints" || { echo "Failed to create backup directory $dest"; exit 1; }

  if [[ -z "$STAGING_DISK_PATHS" ]]; then
    echo "Staging disk paths are required for import-veeam-seed"
    cleanup
  fi

  local use_rbd=0
  if [[ -n "$DISK_PATHS" ]]; then
    while IFS= read -r disk_path; do
      [[ -z "$disk_path" ]] && continue
      if is_rbd_disk_path "$disk_path"; then
        use_rbd=1
        break
      fi
    done < <(split_csv "$DISK_PATHS")
  fi

  local backup_engine="QCOW2"
  [[ $use_rbd -eq 1 ]] && backup_engine="RBD_DIFF"

  local index=0
  local staging_index=0
  while IFS= read -r staging_disk; do
    [[ -z "$staging_disk" ]] && continue
    local backup_file live_disk=""
    if [[ -n "$DISK_PATHS" ]]; then
      live_disk=$(split_csv "$DISK_PATHS" | sed -n "$((staging_index + 1))p")
    fi
    if [[ $use_rbd -eq 1 && -n "$live_disk" ]]; then
      backup_file=$(get_backup_file_by_index "$index" "${live_disk##*/}.raw")
    else
      backup_file=$(get_backup_file_by_index "$index" "disk-${index}.qcow2")
    fi
    local output="$dest/$backup_file"
    if [[ $use_rbd -eq 1 ]]; then
      import_rbd_seed_disk "$staging_disk" "$output" "$live_disk"
    else
      convert_staging_disk_to_backup "$staging_disk" "$output"
    fi
    stat -c %s "$output"
    index=$((index + 1))
    staging_index=$((staging_index + 1))
  done < <(split_csv "$STAGING_DISK_PATHS")

  backup_domain_information "$VM"

  if [[ "$backup_engine" == "RBD_DIFF" ]]; then
    write_rbd_backup_metadata "FULL" "$CHECKPOINT_NAME" ""
    cat > "$dest/checkpoints/${CHECKPOINT_NAME}.meta" <<EOF
checkpoint_name=$CHECKPOINT_NAME
backup_type=FULL
vm_name=$VM
disk_paths=$DISK_PATHS
backup_files=$BACKUP_FILES
source_provider=ablestack-veeam
EOF
  else
    write_veeam_seed_metadata "$backup_engine"
    if [[ "$BOOTSTRAP_CHECKPOINT" == "true" ]]; then
      bootstrap_qcow2_checkpoint_seed "$VM"
    else
      dump_checkpoint_xml "$VM"
    fi
  fi

  sync
  umount "$mount_point"
  rmdir "$mount_point"
}

function usage {
  echo ""
  echo "Usage: $0 -o <operation> -v|--vm <domain name> -t <storage type> -s <storage address> -m <mount options> -p <backup path> -b <FULL|INCREMENTAL> -c <checkpoint name> -r <parent backup path> -i <parent checkpoint name> -j <parent checkpoint path> -f <backup files> -d <disks path> -q|--quiesce <true|false> -x|--forced <true|false>"
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
    --staging-disks)
      STAGING_DISK_PATHS="$2"
      shift
      shift
      ;;
    --source-format)
      SOURCE_FORMAT="$2"
      shift
      shift
      ;;
    --veeam-restore-point)
      VEEAM_RESTORE_POINT_ID="$2"
      shift
      shift
      ;;
    --bootstrap-checkpoint)
      BOOTSTRAP_CHECKPOINT="$2"
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

log -ne "nasbackup.sh start op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] backupType=[$BACKUP_TYPE] checkpoint=[$CHECKPOINT_NAME] parentBackup=[$PARENT_BACKUP_DIR] parentCheckpoint=[$PARENT_CHECKPOINT_NAME] diskPaths=[$DISK_PATHS] backupFiles=[$BACKUP_FILES]"

if [ "$OP" = "backup-running" ]; then
  backup_running_vm
elif [ "$OP" = "backup-rbd" ]; then
  backup_rbd_volumes
elif [ "$OP" = "delete" ]; then
  delete_backup
elif [ "$OP" = "stats" ]; then
  get_backup_stats
elif [ "$OP" = "import-veeam-seed" ]; then
  import_veeam_seed
fi

# Optional Mold->Veeam trigger (bidirectional mode C). Best-effort: never affects backup result.
VEEAM_TRIGGER_HOOK="${VEEAM_TRIGGER_HOOK:-/etc/ablestack/veeam/mold-veeam-trigger-hook.sh}"
if [[ -x "$VEEAM_TRIGGER_HOOK" ]]; then
  "$VEEAM_TRIGGER_HOOK" "$OP" "$VM" "$BACKUP_TYPE" >/dev/null 2>&1 || true
fi
