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

# CloudStack B&R Veeam and Recovery Tool for KVM

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
STAGING_DISK_PATHS=""
SOURCE_FORMAT="vmdk"
VEEAM_RESTORE_POINT_ID=""
BOOTSTRAP_CHECKPOINT="true"
logFile="/var/log/cloudstack/agent/agent.log"
CREATED_RBD_SNAPSHOTS=()

EXIT_CLEANUP_FAILED=20

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
  redefine_checkpoint_chain_if_needed "$vm_name" "$checkpoint_file" ""
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

get_checkpoint_name_from_file() {
  local checkpoint_file="$1"
  basename "$checkpoint_file" .xml
}

get_parent_checkpoint_name_from_file() {
  local checkpoint_file="$1"
  awk '
    /<parent>/ { in_parent=1 }
    in_parent && /<name>/ {
      value=$0
      sub(/^.*<name>/, "", value)
      sub(/<\/name>.*$/, "", value)
      print value
      exit
    }
    /<\/parent>/ { in_parent=0 }
  ' "$checkpoint_file"
}

find_checkpoint_file() {
  local checkpoint_dir="$1"
  local checkpoint_name="$2"
  find "$checkpoint_dir" -maxdepth 1 -type f -name "${checkpoint_name}.xml" -print -quit
}

redefine_checkpoint_chain_if_needed() {
  local vm_name="$1"
  local checkpoint_file="$2"
  local visited="$3"
  local checkpoint_name
  local parent_checkpoint_name
  local parent_checkpoint_file

  if [[ -z "$checkpoint_file" || ! -f "$checkpoint_file" ]]; then
    return
  fi

  checkpoint_name="$(get_checkpoint_name_from_file "$checkpoint_file")"
  if [[ ",$visited," == *",$checkpoint_name,"* ]]; then
    return
  fi
  visited="${visited:+$visited,}$checkpoint_name"

  parent_checkpoint_name="$(get_parent_checkpoint_name_from_file "$checkpoint_file")"
  if [[ -n "$parent_checkpoint_name" ]]; then
    parent_checkpoint_file="$(find_checkpoint_file "$(dirname "$checkpoint_file")" "$parent_checkpoint_name")"
    if [[ -z "$parent_checkpoint_file" ]]; then
      echo "Missing parent checkpoint file $parent_checkpoint_name for checkpoint $checkpoint_name"
      cleanup
      exit 1
    fi
    redefine_checkpoint_chain_if_needed "$vm_name" "$parent_checkpoint_file" "$visited"
  fi

  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$checkpoint_name" > /dev/null 2>&1; then
    return
  fi
  if ! virsh -c qemu:///system checkpoint-create --domain "$vm_name" --xmlfile "$checkpoint_file" --redefine > /dev/null 2>&1; then
    echo "Failed to redefine checkpoint $checkpoint_name on domain $vm_name"
    cleanup
    exit 1
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
    # mon_host uses escaped separators (scvm1\:6789\;scvm2\:6789). Do NOT use
    # :mon_host=([^:]*) — that stops at the first ':' inside scvm1\:6789 → MON=scvm1\
    if [[ "$payload" == *":mon_host="* ]]; then
      RBD_IMAGE="${payload%%:mon_host=*}"
      local mon_part="${payload#*:mon_host=}"
      RBD_MON_HOST="${mon_part%%:auth_supported=*}"
      RBD_MON_HOST="${RBD_MON_HOST%%:id=*}"
      RBD_MON_HOST="${RBD_MON_HOST%%:key=*}"
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
  # Keep parent RBD snapshots. Mold restore uses snap rollback (and re-export-diff needs the parent).
  # Deleting parents after INC left only the tip snap and broke FULL/.raw-missing restores.
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
        echo "Virsh backup job failed"; cleanup ;;
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
}

backup_rbd_volumes() {
  mkdir -p "$dest/checkpoints" || { echo "Failed to create backup directory $dest"; exit 1; }
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
}

has_child_backup() {
  local checkpoint_name="$1"
  [[ -z "$checkpoint_name" ]] && return 1
  grep -R -q "^parent_checkpoint_name=$checkpoint_name$" "$(dirname "$dest")"/*/rbd-backup.meta 2>/dev/null
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

has_child_checkpoint() {
  local checkpoint_name="$1"
  [[ -z "$checkpoint_name" ]] && return 1
  find "$(dirname "$dest")" -path "$(dirname "$dest")/$(basename "$dest")" -prune -o -type f -name "*.xml" -print 2>/dev/null \
    | xargs grep -F -l "<parent>" 2>/dev/null \
    | xargs grep -F -l "<name>$checkpoint_name</name>" 2>/dev/null \
    | grep -q .
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

qcow2_checkpoint_bitmap_exists_on_disks() {
  local name="$1"
  local disk_count bitmap_count
  [[ -z "$name" || -z "$VM" ]] && return 1
  disk_count=$(virsh -c qemu:///system domblklist "$VM" --details 2>/dev/null | awk '/disk/ {c++} END{print c+0}')
  [[ "$disk_count" -gt 0 ]] || return 1
  bitmap_count=$(virsh -c qemu:///system qemu-monitor-command "$VM" '{"execute":"query-block"}' 2>/dev/null | python3 -c '
import json,sys
name=sys.argv[1]
try:
  data=json.load(sys.stdin)
except Exception:
  print(0); raise SystemExit
count=0
for b in data.get("return", []):
  inserted=b.get("inserted") or {}
  for bm in inserted.get("dirty-bitmaps") or []:
    if bm.get("name")==name:
      count += 1
      break
print(count)
' "$name" 2>/dev/null || echo 0)
  [[ "$bitmap_count" -ge "$disk_count" ]]
}

copy_staging_checkpoint_xml_if_present() {
  local staging_disk staging_dir staging_xml
  staging_disk="$(echo "$STAGING_DISK_PATHS" | awk -F',' '{print $1}' | tr -d '[:space:]')"
  [[ -n "$staging_disk" ]] || return 1
  staging_dir="$(dirname "$staging_disk")"
  staging_xml="${staging_dir}/checkpoints/${CHECKPOINT_NAME}.xml"
  if [[ -f "$staging_xml" ]]; then
    mkdir -p "$dest/checkpoints"
    cp -a "$staging_xml" "$dest/checkpoints/$CHECKPOINT_NAME.xml"
    strip_checkpoint_parent_from_xml "$dest/checkpoints/$CHECKPOINT_NAME.xml"
    log -ne "Reused host-export checkpoint XML [$staging_xml] as seed checkpoint [$CHECKPOINT_NAME]"
    return 0
  fi
  return 1
}

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

  if [[ -z "$CHECKPOINT_NAME" ]]; then
    echo "Checkpoint name is required for QCOW2 seed bootstrap"
    return 1
  fi

  # Prefer the live libvirt checkpoint from host-export (same name as dirty bitmap).
  if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$CHECKPOINT_NAME" > /dev/null 2>&1; then
    dump_checkpoint_xml "$vm_name"
    strip_checkpoint_parent_from_xml "$dest/checkpoints/$CHECKPOINT_NAME.xml"
    log -ne "Reused existing libvirt checkpoint (seed) [$CHECKPOINT_NAME] on VM [$vm_name]"
    return 0
  fi

  # Host-export already wrote checkpoints/<ts>.xml next to staging disks — reuse it.
  if copy_staging_checkpoint_xml_if_present; then
    return 0
  fi

  while IFS='|' read -r disk _target; do
    [[ -z "$disk" ]] && continue
    diskspec_args+=(--diskspec "${disk},bitmap=${CHECKPOINT_NAME}")
  done < <(virsh -c qemu:///system domblklist "$vm_name" --details 2>/dev/null | awk '/disk/ {print $3 "|" $4}')

  if [[ ${#diskspec_args[@]} -eq 0 ]]; then
    echo "No disks found for checkpoint bootstrap on VM $vm_name"
    return 1
  fi

  if virsh -c qemu:///system checkpoint-create-as "$vm_name" "$CHECKPOINT_NAME" \
      "${diskspec_args[@]}" >> "$logFile" 2>&1; then
    dump_checkpoint_xml "$vm_name"
    strip_checkpoint_parent_from_xml "$dest/checkpoints/$CHECKPOINT_NAME.xml"
    log -ne "Bootstrapped libvirt checkpoint (seed) [$CHECKPOINT_NAME] on VM [$vm_name]"
    return 0
  fi

  # create-as often fails when host-export already created the dirty bitmap under this name.
  # If the bitmap is present, keep Mold INCREMENTAL-capable by requiring XML from staging or dump.
  if qcow2_checkpoint_bitmap_exists_on_disks "$CHECKPOINT_NAME"; then
    if copy_staging_checkpoint_xml_if_present; then
      return 0
    fi
    if virsh -c qemu:///system checkpoint-info --domain "$vm_name" --checkpointname "$CHECKPOINT_NAME" > /dev/null 2>&1; then
      dump_checkpoint_xml "$vm_name"
      strip_checkpoint_parent_from_xml "$dest/checkpoints/$CHECKPOINT_NAME.xml"
      return 0
    fi
    echo "QCOW2 seed bitmap [$CHECKPOINT_NAME] exists on disks but checkpoint XML is missing; cannot stamp INCREMENTAL parent"
    return 1
  fi

  echo "Failed to bootstrap QCOW2 checkpoint [$CHECKPOINT_NAME] on VM [$vm_name] (create-as failed and bitmap missing)"
  return 1
}

import_veeam_seed() {
  log -ne "Entered import_veeam_seed staging=[$STAGING_DISK_PATHS] backupDir=[$BACKUP_DIR]"
  mkdir -p "$dest" "$dest/checkpoints" || { echo "Failed to create backup directory $dest"; exit 1; }

  if [[ -z "$STAGING_DISK_PATHS" ]]; then
    echo "Staging disk paths are required for import-seed"
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
      if ! bootstrap_qcow2_checkpoint_seed "$VM"; then
        echo "Failed to bootstrap QCOW2 checkpoint [$CHECKPOINT_NAME] for import-seed"
        cleanup
      fi
    else
      dump_checkpoint_xml "$VM"
    fi
  fi

  sync
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
    --staging-disks) STAGING_DISK_PATHS="$2"; shift; shift ;;
    --source-format) SOURCE_FORMAT="$2"; shift; shift ;;
    --veeam-restore-point) VEEAM_RESTORE_POINT_ID="$2"; shift; shift ;;
    --bootstrap-checkpoint) BOOTSTRAP_CHECKPOINT="$2"; shift; shift ;;
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

log -ne "ablestack_veeam.sh start op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] backupType=[$BACKUP_TYPE] checkpoint=[$CHECKPOINT_NAME] parentBackup=[$PARENT_BACKUP_DIR] parentCheckpoint=[$PARENT_CHECKPOINT_NAME] diskPaths=[$DISK_PATHS] backupFiles=[$BACKUP_FILES]"

if [[ "$OP" == "backup-running" ]]; then
  backup_running_vm
elif [[ "$OP" == "backup-rbd" ]]; then
  backup_rbd_volumes
elif [[ "$OP" == "delete" ]]; then
  delete_backup
elif [[ "$OP" == "import-seed" ]]; then
  import_veeam_seed
else
  echo "Unsupported operation: $OP"
  exit 1
fi
