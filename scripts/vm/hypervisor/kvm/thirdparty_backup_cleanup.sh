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

# Delete retained backup staging data and source checkpoint metadata only.
# New backups for these providers use thirdparty_volume_backup.py.
PROVIDER=""
VM=""
BACKUP_DIR=""
CHECKPOINT_NAME=""
CLEANUP_CHECKPOINT_NAMES=""
DISK_PATHS=""
FORCED="false"
EXIT_CLEANUP_FAILED=20
logFile="/var/log/cloudstack/agent/agent.log"

log() {
  [[ "$verb" -eq 1 ]] && builtin echo "$@"
  if [[ "$1" == "-ne" || "$1" == "-e" || "$1" == "-n" ]]; then
    builtin echo -e "$(date '+%Y-%m-%d %H-%M-%S>')" "${@: 2}" >> "$logFile"
  else
    builtin echo "$(date '+%Y-%m-%d %H-%M-%S>')" "$@" >> "$logFile"
  fi
}


split_csv() {
  tr ',' '\n' <<< "$1"
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
      RBD_MON_HOST="${mon_part%%:auth_client_required=*}"
      RBD_MON_HOST="${RBD_MON_HOST%%:auth_supported=*}"
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
    exit "$EXIT_CLEANUP_FAILED"
  fi

  if [[ -z "$RBD_IMAGE" ]]; then
    echo "Failed to parse RBD image from uri: $uri"
    exit "$EXIT_CLEANUP_FAILED"
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


has_child_backup() {
  local checkpoint_name="$1"
  [[ -z "$checkpoint_name" ]] && return 1
  grep -R -q "^parent_checkpoint_name=$checkpoint_name$" "$(dirname "$dest")"/*/rbd-backup.meta 2>/dev/null
}


rbd_snapshot_exists() {
  local checkpoint_name="$1"
  local snapshots present
  if ! snapshots="$(timeout 30s "${RBD_CMD[@]}" snap ls "$RBD_IMAGE" --format json 2>>"$logFile")"; then
    log -ne "Failed to query RBD snapshots [$RBD_IMAGE]; cleanup remains pending"
    return "$EXIT_CLEANUP_FAILED"
  fi
  if ! present="$(python3 -c '
import json, sys
snapshots = json.load(sys.stdin)
if not isinstance(snapshots, list) or any(not isinstance(item, dict) or not isinstance(item.get("name"), str) for item in snapshots):
    raise ValueError("Invalid RBD snapshot inventory")
print("true" if any(item["name"] == sys.argv[1] for item in snapshots) else "false")
' "$checkpoint_name" <<< "$snapshots")"; then
    log -ne "Invalid RBD snapshot inventory [$RBD_IMAGE]; cleanup remains pending"
    return "$EXIT_CLEANUP_FAILED"
  fi
  [[ "$present" == true ]]
}


delete_rbd_snapshot_if_unreferenced() {
  local disk_paths="$1"
  local checkpoint_name="$2"

  [[ -z "$checkpoint_name" ]] && return 0

  if has_child_backup "$checkpoint_name"; then
    log -ne "Skip snapshot delete [$checkpoint_name] (child exists)"
    return "$EXIT_CLEANUP_FAILED"
  fi

  while IFS= read -r disk_path; do
    [[ -z "$disk_path" ]] && continue
    parse_rbd_uri "$disk_path"
    build_rbd_cmd

    local inventory_status=0
    rbd_snapshot_exists "$checkpoint_name" || inventory_status=$?
    if [[ "$inventory_status" -eq 0 ]]; then
      log -ne "Deleting snapshot [${RBD_IMAGE}@${checkpoint_name}]"
      if ! timeout 30s "${RBD_CMD[@]}" snap rm "${RBD_IMAGE}@${checkpoint_name}" >> "$logFile" 2>&1; then
        log -ne "Failed to delete RBD snapshot [${RBD_IMAGE}@${checkpoint_name}]; cleanup remains pending"
        return "$EXIT_CLEANUP_FAILED"
      fi
      inventory_status=0
      rbd_snapshot_exists "$checkpoint_name" || inventory_status=$?
      if [[ "$inventory_status" -ne 1 ]]; then
        log -ne "RBD snapshot absence is unconfirmed [${RBD_IMAGE}@${checkpoint_name}]; cleanup remains pending"
        return "$EXIT_CLEANUP_FAILED"
      fi
    elif [[ "$inventory_status" -ne 1 ]]; then
      return "$EXIT_CLEANUP_FAILED"
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


validate_cleanup_path() {
  python3 "$(dirname -- "$0")/thirdparty_staging_cleanup.py" --provider "$PROVIDER" --path "$dest"
}


delete_backup() {
  validate_cleanup_path || exit $EXIT_CLEANUP_FAILED
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
  validate_cleanup_path || exit $EXIT_CLEANUP_FAILED
  rm -frv "$dest"
  sync
}


usage() {
  echo "Usage: $0 --provider <ablestack-commvault|ablestack-netbackup|ablestack-veeam> -p <backup path> [-v VM] [-c checkpoint] [-C cleanup checkpoints] [-d disk paths] [-x true|false]"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider) PROVIDER="$2"; shift 2 ;;
    -v|--vm) VM="$2"; shift 2 ;;
    -p|--path) BACKUP_DIR="$2"; shift 2 ;;
    -c|--checkpoint) CHECKPOINT_NAME="$2"; shift 2 ;;
    -C|--cleanupcheckpoints) CLEANUP_CHECKPOINT_NAMES="$2"; shift 2 ;;
    -d|--diskpaths) DISK_PATHS="$2"; shift 2 ;;
    -x|--forced) FORCED="$2"; shift 2 ;;
    *) usage ;;
  esac
done

case "$PROVIDER" in
  ablestack-commvault|ablestack-netbackup|ablestack-veeam) ;;
  *) usage ;;
esac
[[ -n "$BACKUP_DIR" ]] || usage
dest="$BACKUP_DIR"
delete_backup
