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

# CloudStack B&R Commvault Backup and Recovery Tool for KVM

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
logFile="/var/log/cloudstack/agent/agent.log"

EXIT_CLEANUP_FAILED=20

log() {
  [[ "$verb" -eq 1 ]] && builtin echo "$@"
  if [[ "$1" == "-ne" || "$1" == "-e" || "$1" == "-n" ]]; then
    builtin echo -e "$(date '+%Y-%m-%d %H-%M-%S>')" "${@: 2}" >> "$logFile"
  else
    builtin echo "$(date '+%Y-%m-%d %H-%M-%S>')" "$@" >> "$logFile"
  fi
}

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


parse_rbd_uri() {
  local uri="$1"
  log -ne "parse_rbd_uri called with uri=[$uri]"

  RBD_IMAGE=""
  RBD_MON_HOST=""
  RBD_USER=""
  RBD_KEY=""

  if [[ "$uri" == rbd:* ]]; then
    local payload="${uri#rbd:}"
    RBD_IMAGE="${payload%%:*}"

    if [[ "$uri" =~ :mon_host=([^:]*) ]]; then
      RBD_MON_HOST="${BASH_REMATCH[1]}"
      RBD_MON_HOST="${RBD_MON_HOST//\\;/,}"
      RBD_MON_HOST="${RBD_MON_HOST//\\:/:}"
    fi

    if [[ "$uri" =~ :id=([^:]*) ]]; then
      RBD_USER="${BASH_REMATCH[1]}"
    fi

    if [[ "$uri" =~ :key=([^:]*) ]]; then
      RBD_KEY="${BASH_REMATCH[1]}"
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
    echo "Failed to start libvirt backup for VM [$VM]: ${backup_begin_output:-Unknown error}"
    cleanup
    exit 1
  fi

  backup_domain_information "$VM"

  while true; do
    status=$(virsh -c qemu:///system domjobinfo "$VM" --completed --keep-completed | awk '/Job type:/ {print $3}')
    case "$status" in
      Completed) break ;;
      Failed) echo "Virsh backup job failed"; cleanup ;;
    esac
    sleep 5
  done

  dump_checkpoint_xml "$VM"
  rm -f "$dest/backup.xml" "$dest/checkpoint.xml"
  sync
}

backup_rbd_volumes() {
  mkdir -p "$dest/checkpoints" || { echo "Failed to create backup directory $dest"; exit 1; }
  backup_domain_information "$VM"
  local index=0
  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    local created_snapshot=""
    log -ne "Loop disk raw value=[$disk_path]"
    parse_rbd_uri "$disk_path"
    build_rbd_cmd
    log -ne "Built RBD command: ${RBD_CMD[*]}"

    local output_file="$dest/$(get_backup_file_by_index "$index" "${RBD_IMAGE##*/}.raw")"
    log -ne "Starting RBD backup for disk path [$disk_path], resolved image [$RBD_IMAGE], output [$output_file]"

  if ! timeout 30s "${RBD_CMD[@]}" info "$RBD_IMAGE" >> "$logFile" 2>&1; then
    echo "Failed to access RBD image $RBD_IMAGE"
    cleanup
  fi

  if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
    if ! timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" 2>>"$logFile" | awk 'NR>1 {print $2}' | grep -Fxq "$PARENT_CHECKPOINT_NAME"; then
      echo "Parent RBD snapshot ${RBD_IMAGE}@${PARENT_CHECKPOINT_NAME} not found for incremental backup"
      cleanup
    fi
  fi

  if ! timeout 30s "${RBD_CMD[@]}" snap create "${RBD_IMAGE}@${CHECKPOINT_NAME}" >> "$logFile" 2>&1; then
    echo "Failed to create RBD snapshot ${RBD_IMAGE}@${CHECKPOINT_NAME}"
    cleanup
  fi
    created_snapshot="${RBD_IMAGE}@${CHECKPOINT_NAME}"

    if [[ "$BACKUP_TYPE" == "INCREMENTAL" && -n "$PARENT_CHECKPOINT_NAME" ]]; then
      if ! timeout 6h "${RBD_CMD[@]}" export-diff --from-snap "$PARENT_CHECKPOINT_NAME" "${RBD_IMAGE}@${CHECKPOINT_NAME}" "$output_file" >> "$logFile" 2>&1; then
        echo "Failed to export incremental RBD diff for ${RBD_IMAGE}@${CHECKPOINT_NAME}"
        [[ -n "$created_snapshot" ]] && "${RBD_CMD[@]}" snap rm "$created_snapshot" >> "$logFile" 2>&1 || true
        cleanup
      fi
    else
      if ! timeout 6h "${RBD_CMD[@]}" export "${RBD_IMAGE}@${CHECKPOINT_NAME}" "$output_file" >> "$logFile" 2>&1; then
        echo "Failed to export full RBD snapshot ${RBD_IMAGE}@${CHECKPOINT_NAME}"
        [[ -n "$created_snapshot" ]] && "${RBD_CMD[@]}" snap rm "$created_snapshot" >> "$logFile" 2>&1 || true
        cleanup
      fi
    fi

    log -ne "Finished exporting backup file [$output_file] size=[$(stat -c %s "$output_file" 2>/dev/null)]"
    index=$((index + 1))
  done < <(split_csv "$DISK_PATHS")

  write_rbd_backup_metadata "$BACKUP_TYPE" "$CHECKPOINT_NAME" "$PARENT_CHECKPOINT_NAME"
  write_rbd_checkpoint_metadata "$CHECKPOINT_NAME" "$PARENT_CHECKPOINT_NAME"
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
  fi

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

log -ne "ablestack_cvtbackup.sh start op=[$OP] vm=[$VM] backupDir=[$BACKUP_DIR] backupType=[$BACKUP_TYPE] checkpoint=[$CHECKPOINT_NAME] parentBackup=[$PARENT_BACKUP_DIR] parentCheckpoint=[$PARENT_CHECKPOINT_NAME] diskPaths=[$DISK_PATHS] backupFiles=[$BACKUP_FILES]"

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
