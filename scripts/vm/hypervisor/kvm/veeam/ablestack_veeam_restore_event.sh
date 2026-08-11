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

# Restore event ingress (Veeam PS1 SSH push, restore-watch agent, or manual).
#
# Events:
#   veeam.restore.completed  Veeam UI/Agent restore session finished → Mold datadisk restore
#   mold.restore.manual      Operator/Mold UI path with BACKUP_ID already set
#
# Usage:
#   ablestack_veeam_restore_event.sh veeam.restore.completed <session-id> <vm-name> [detail]
#   BACKUP_ID=<uuid> ablestack_veeam_restore_event.sh mold.restore.manual manual <vm-name>

set -euo pipefail

EVENT="${1:-}"
SESSION_ID="${2:-}"
VM_NAME_E="${3:-}"
DETAIL="${4:-}"
JOB="${VEEAM_JOB_NAME:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=mold-backup.lib.sh
source "${SCRIPT_DIR}/mold-backup.lib.sh"

mold_backup_load_config || exit 1
[[ -n "$JOB" ]] || JOB="${VEEAM_JOB_NAME:-}"

case "$EVENT" in
  veeam.restore.completed)
    [[ -n "$SESSION_ID" && -n "$VM_NAME_E" ]] || {
      mold_backup_notify_log err "restore-event: veeam.restore.completed needs session-id and vm-name"
      exit 1
    }
    mold_backup_handle_veeam_restore_session "$JOB" "$VM_NAME_E" "$SESSION_ID" "$DETAIL" true
    ;;
  mold.restore.manual)
    [[ -n "$VM_NAME_E" ]] || {
      mold_backup_notify_log err "restore-event: mold.restore.manual needs vm-name"
      exit 1
    }
    export VM_NAME="$VM_NAME_E"
    [[ -n "${BACKUP_ID:-}" ]] || mold_backup_die "mold.restore.manual requires BACKUP_ID"
    mold_backup_emit_restore_event "mold.restore.requested" "$VM_NAME_E" "backup_id=${BACKUP_ID};source=manual"
    mold_backup_trigger_mark "mold-restore-active" "$VM_NAME_E"
    mold_backup_restore_notify "$(hostname -s)" "$JOB"
    mold_backup_emit_restore_event "mold.restore.completed" "$VM_NAME_E" "backup_id=${BACKUP_ID}"
    ;;
  *)
    mold_backup_notify_log err "restore-event: unknown event '${EVENT}'"
    exit 1
    ;;
esac
