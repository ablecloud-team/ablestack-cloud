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

# Ablestack Veeam host-mode disk export (libvirt FULL/INCREMENTAL → staging).
# Commvault agent path remains scripts/vm/hypervisor/kvm/ablestack_cvtbackup.sh.

# TODO: do libvirt/logging etc checks

### Declare variables ###

OP=""
VM=""
BACKUP_DIR=""
DISK_PATHS=""
QUIESCE=""
BACKUP_TYPE="FULL"
CHECKPOINT_NAME=""
PARENT_BACKUP_DIR=""
PARENT_CHECKPOINT_NAME=""
PARENT_CHECKPOINT_PATH=""
BACKUP_FILES=""
FORCED="false"
CLEANUP_CHECKPOINT_NAMES=""
logFile="/var/log/cloudstack/agent/agent.log"
CREATED_RBD_SNAPSHOTS=()

EXIT_CLEANUP_FAILED=20
STAGING_IN_PROGRESS_MARKER=".staging.inprogress"
STAGING_COMPLETE_MARKER=".staging.complete"

log() {
  [[ "$verb" -eq 1 ]] && builtin echo "$@"
  if [[ "$1" == "-ne" || "$1" == "-e" || "$1" == "-n" ]]; then
    builtin echo -e "$(date '+%Y-%m-%d %H-%M-%S>')" "${@: 2}" >> "$logFile"
  else
    builtin echo "$(date '+%Y-%m-%d %H-%M-%S>')" "$@" >> "$logFile"
  fi
}

log_unhandled_error() {
  local status=$?
  local line="$1"
  log -ne "FAILED unhandled error status=[$status] line=[$line] op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] checkpoint=[$CHECKPOINT_NAME]"
}

trap 'log_unhandled_error "$LINENO"' ERR

vercomp() {
  local IFS=.
  local i ver1=($1) ver2=($3)
  for ((i=0; i<${#ver1[@]}; i++)); do
    if [[ -z ${ver2[i]} ]]; then
      ver2[i]=0
    fi
    if ((10#${ver1[i]} > 10#${ver2[i]})); then
      return 0
    elif ((10#${ver1[i]} < 10#${ver2[i]})); then
      return 2
    fi
  done
  return 0
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

cleanup() {
  local status=0
  rm -rf "$dest" || { echo "Failed to delete $dest"; status=1; }
  if [[ -e "$dest" ]]; then
    echo "Backup directory still exists after cleanup: $dest"
    status=1
  fi
  if [[ $status -ne 0 ]]; then
    log -ne "FAILED cleanup dest=[$dest] status=[$status]"
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
    exit 1
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
  # Keep parent RBD snapshots for Mold snap-rollback restore / re-export-diff.
  [[ "$BACKUP_TYPE" != "INCREMENTAL" || -z "$PARENT_CHECKPOINT_NAME" ]] && return
  [[ "$PARENT_CHECKPOINT_NAME" == "$CHECKPOINT_NAME" ]] && return
  log -ne "Keeping RBD parent snapshot [${PARENT_CHECKPOINT_NAME}] for Mold restore (skip delete after INC)"
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

write_rbd_checkpoint_metadata() {
  local checkpoint_name="$1"
  local parent_checkpoint_name="$2"

  cat > "$dest/checkpoints/$checkpoint_name.meta" <<EOF
checkpoint_name=$checkpoint_name
parent_checkpoint_name=$parent_checkpoint_name
EOF
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

backup_running_vm() {
  mkdir -p "$dest/checkpoints" || { echo "Failed to create backup directory $dest"; exit 1; }
  mark_staging_in_progress
  local parent_checkpoint_file=""
  if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_PATH" ]]; then
    parent_checkpoint_file="$PARENT_CHECKPOINT_PATH"
    if [[ ! -f "$parent_checkpoint_file" ]]; then
      echo "Parent checkpoint file not found for incremental backup: $parent_checkpoint_file"
      cleanup
      exit 1
    fi
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
  local index=0
  for disk in $(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/{print $3}'); do
    local target_file="$dest/$(get_backup_file_by_index "$index")"
    echo "<disk name='$disk' backup='yes' type='file'><driver type='qcow2'/><target file='$target_file'/></disk>" >> "$dest/backup.xml"
    index=$((index + 1))
  done
  echo "</disks></domainbackup>" >> "$dest/backup.xml"

  echo "<domaincheckpoint><name>$CHECKPOINT_NAME</name><disks>" > "$dest/checkpoint.xml"
  for disk in $(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/{print $3}'); do
    echo "<disk name='$disk' checkpoint='bitmap'/>" >> "$dest/checkpoint.xml"
  done
  echo "</disks></domaincheckpoint>" >> "$dest/checkpoint.xml"

  local thaw=0
  if [[ ${QUIESCE} == "true" ]]; then
    if virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-freeze"}' > /dev/null 2>/dev/null; then
      thaw=1
    fi
  fi

  local backup_begin=0
  local backup_begin_output=""
  if backup_begin_output=$(virsh -c qemu:///system backup-begin --domain "$VM" --backupxml "$dest/backup.xml" --checkpointxml "$dest/checkpoint.xml" 2>&1); then
    backup_begin=1
  fi

  if [[ $thaw -eq 1 ]]; then
    virsh -c qemu:///system qemu-agent-command "$VM" '{"execute":"guest-fsfreeze-thaw"}' > /dev/null 2>&1 || true
  fi

  if [[ $backup_begin -ne 1 ]]; then
    log -ne "FAILED libvirt backup-begin vm=[$VM] checkpoint=[$CHECKPOINT_NAME] output=[${backup_begin_output:-Unknown error}]"
    echo "Failed to start libvirt backup for VM [$VM]: ${backup_begin_output:-Unknown error}"
    cleanup
    exit 1
  fi

  backup_domain_information "$VM"

  local wait_count=0
  while true; do
    status=$(virsh -c qemu:///system domjobinfo "$VM" --completed --keep-completed | awk '/Job type:/ {print $3}')
    case "$status" in
      Completed) break ;;
      Failed)
        log -ne "FAILED libvirt backup job vm=[$VM] checkpoint=[$CHECKPOINT_NAME]"
        echo "Virsh backup job failed"; cleanup; exit 1 ;;
    esac
    wait_count=$((wait_count + 1))
    if (( wait_count % 12 == 0 )); then
      log -ne "WAIT libvirt backup job pending vm=[$VM] checkpoint=[$CHECKPOINT_NAME] elapsedSeconds=[$((wait_count * 5))] status=[${status:-unknown}]"
    fi
    sleep 5
  done

  cleanup_parent_qcow2_bitmap_after_success
  dump_checkpoint_xml "$VM"
  rm -f "$dest/backup.xml" "$dest/checkpoint.xml"
  sync
  mark_staging_complete
}

backup_rbd_volumes() {
  mkdir -p "$dest/checkpoints" || { echo "Failed to create backup directory $dest"; exit 1; }
  mark_staging_in_progress
  backup_domain_information "$VM"
  trap 'log -ne "FAILED RBD backup unexpected error line=[$LINENO] op=[$OP] vm=[$VM] checkpoint=[$CHECKPOINT_NAME]"; cleanup_created_rbd_snapshots' ERR
  trap 'log -ne "FAILED RBD backup interrupted op=[$OP] vm=[$VM] checkpoint=[$CHECKPOINT_NAME]"; cleanup_created_rbd_snapshots; exit 1' INT TERM
  local index=0
  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    log -ne "Loop disk raw value=[$disk_path]"
    parse_rbd_uri "$disk_path"
    build_rbd_cmd
    log -ne "Built RBD command: ${RBD_CMD[*]}"

    local output_file="$dest/$(get_backup_file_by_index "$index" "${RBD_IMAGE##*/}.raw")"
    log -ne "Starting RBD backup for disk path [$disk_path], resolved image [$RBD_IMAGE], output [$output_file]"

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

  if ! timeout 30s "${RBD_CMD[@]}" snap create "${RBD_IMAGE}@${CHECKPOINT_NAME}" >> "$logFile" 2>&1; then
    log -ne "FAILED RBD snapshot create image=[$RBD_IMAGE] snapshot=[$CHECKPOINT_NAME] timeout=[30s]"
    echo "Failed to create RBD snapshot ${RBD_IMAGE}@${CHECKPOINT_NAME}"
    cleanup_created_rbd_snapshots
    cleanup
  fi
    record_created_rbd_snapshot "$disk_path" "$CHECKPOINT_NAME"

    if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
      local export_start
      export_start=$(date +%s)
      if ! timeout 6h "${RBD_CMD[@]}" export-diff --from-snap "$PARENT_CHECKPOINT_NAME" "${RBD_IMAGE}@${CHECKPOINT_NAME}" "$output_file" >> "$logFile" 2>&1; then
        log -ne "FAILED RBD export-diff image=[$RBD_IMAGE] snapshot=[$CHECKPOINT_NAME] output=[$output_file] elapsedSeconds=[$(($(date +%s) - export_start))] timeout=[6h]"
        echo "Failed to export incremental RBD diff for ${RBD_IMAGE}@${CHECKPOINT_NAME}"
        cleanup_created_rbd_snapshots
        cleanup
      fi
    else
      local export_start
      export_start=$(date +%s)
      if ! timeout 6h "${RBD_CMD[@]}" export "${RBD_IMAGE}@${CHECKPOINT_NAME}" "$output_file" >> "$logFile" 2>&1; then
        log -ne "FAILED RBD export image=[$RBD_IMAGE] snapshot=[$CHECKPOINT_NAME] output=[$output_file] elapsedSeconds=[$(($(date +%s) - export_start))] timeout=[6h]"
        echo "Failed to export full RBD snapshot ${RBD_IMAGE}@${CHECKPOINT_NAME}"
        cleanup_created_rbd_snapshots
        cleanup
      fi
    fi

    log -ne "Finished exporting backup file [$output_file] size=[$(stat -c %s "$output_file" 2>/dev/null)]"
    index=$((index + 1))
  done < <(split_csv "$DISK_PATHS")

  write_rbd_backup_metadata "$BACKUP_TYPE" "$CHECKPOINT_NAME" "$PARENT_CHECKPOINT_NAME"
  write_rbd_checkpoint_metadata "$CHECKPOINT_NAME" "$PARENT_CHECKPOINT_NAME"
  cleanup_parent_rbd_snapshot_after_success
  trap - ERR
  trap - INT TERM
  CREATED_RBD_SNAPSHOTS=()
  sync
  mark_staging_complete
}

has_child_backup() {
  local checkpoint_name="$1"
  [[ -z "$checkpoint_name" ]] && return 1
  grep -R -q "^parent_checkpoint_name=$checkpoint_name$" "$(dirname "$dest")"/*/rbd-backup.meta 2>/dev/null
}

has_child_checkpoint() {
  local checkpoint_name="$1"
  [[ -z "$checkpoint_name" ]] && return 1
  find "$(dirname "$dest")" -path "$(dirname "$dest")/$(basename "$dest")" -prune -o -type f -name "*.xml" -print 2>/dev/null \
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

  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    parse_rbd_uri "$disk_path"
    build_rbd_cmd

    if timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>/dev/null | awk 'NR>1 {print $2}' | grep -Fxq "$checkpoint_name"; then
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

mark_staging_in_progress() {
  rm -f "$dest/$STAGING_COMPLETE_MARKER"
  printf 'started_at=%s\nvm=%s\ncheckpoint=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$VM" "$CHECKPOINT_NAME" > "$dest/$STAGING_IN_PROGRESS_MARKER"
  sync "$dest/$STAGING_IN_PROGRESS_MARKER" 2>/dev/null || true
}

mark_staging_complete() {
  local tmp_marker="$dest/$STAGING_COMPLETE_MARKER.tmp"
  printf 'completed_at=%s\nvm=%s\ncheckpoint=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$VM" "$CHECKPOINT_NAME" > "$tmp_marker"
  mv -f "$tmp_marker" "$dest/$STAGING_COMPLETE_MARKER"
  rm -f "$dest/$STAGING_IN_PROGRESS_MARKER"
  sync "$dest/$STAGING_COMPLETE_MARKER" 2>/dev/null || true
}

delete_backup() {
  if [[ -f "$dest/rbd-backup.meta" ]]; then
    source "$dest/rbd-backup.meta"

    log -ne "Deleting backup with metadata [$dest]"

    if [[ "$FORCED" != "true" ]] && has_child_backup "$checkpoint_name"; then
      echo "Cannot delete backup [$backup_dir]: child backup exists"
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
  rm -frv "$dest"
  sync
}

usage() {
  echo ""
  echo "Usage: $0 -o <operation> -v|--vm <domain name> -p <backup path> -b <FULL|INCREMENTAL> -c <checkpoint name> -r <parent backup path> -i <parent checkpoint name> -j <parent checkpoint path> -f <backup files> -d <disks path> -q|--quiesce <true|false>"
  echo ""
  exit 1
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -o|--operation) OP="$2"; shift; shift ;;
    -v|--vm) VM="$2"; shift; shift ;;
    -p|--path) BACKUP_DIR="$2"; shift; shift ;;
    -b|--backuptype) BACKUP_TYPE="$2"; shift; shift ;;
    -c|--checkpoint) CHECKPOINT_NAME="$2"; shift; shift ;;
    -r|--parentbackup) PARENT_BACKUP_DIR="$2"; shift; shift ;;
    -i|--parentcheckpoint) PARENT_CHECKPOINT_NAME="$2"; shift; shift ;;
    -j|--parentcheckpointpath) PARENT_CHECKPOINT_PATH="$2"; shift; shift ;;
    -f|--backupfiles) BACKUP_FILES="$2"; shift; shift ;;
    -q|--quiesce) QUIESCE="$2"; shift; shift ;;
    -d|--diskpaths) DISK_PATHS="$2"; shift; shift ;;
    -C|--cleanupcheckpoints) CLEANUP_CHECKPOINT_NAMES="$2"; shift; shift ;;
    -x|--forced) FORCED="$2"; shift; shift ;;
    -h|--help) usage ;;
    *) echo "Invalid option: $1"; usage ;;
  esac
done

if [[ -z "$BACKUP_DIR" ]]; then
  echo "Backup path (-p|--path) is required"
  exit 1
fi

dest="$BACKUP_DIR"
sanity_checks

log -ne "ablestack_veeam_host_export.sh start op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] backupType=[$BACKUP_TYPE] checkpoint=[$CHECKPOINT_NAME] parentBackup=[$PARENT_BACKUP_DIR] parentCheckpoint=[$PARENT_CHECKPOINT_NAME] diskPaths=[$DISK_PATHS] backupFiles=[$BACKUP_FILES]"

if [[ "$OP" == "backup-running" ]]; then
  backup_running_vm
elif [[ "$OP" == "backup-rbd" ]]; then
  backup_rbd_volumes
elif [[ "$OP" == "delete" ]]; then
  delete_backup
else
  echo "Unsupported operation: $OP"
  exit 1
fi
