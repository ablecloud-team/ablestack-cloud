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

# Mold -> Veeam trigger (bidirectional mode C).
# Invoked best-effort by ablestack_nasbackup.sh after a VM backup completes.
# Starts the matching Veeam Agent job over SSH unless the current backup was
# itself triggered by Veeam (veeam-active marker present).
#
# Args: <operation> <vm-libvirt-name> [backup-type]
# Never fail the caller: always exit 0.

OP="${1:-}"
VM="${2:-}"
BACKUP_TYPE="${3:-}"

# Only react to the actual VM backup operation.
case "$OP" in
  backup-running|backup-rbd) ;;
  *) exit 0 ;;
esac
[[ -n "$VM" ]] || exit 0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${SCRIPT_DIR}/mold-backup.lib.sh"
[[ -f "$LIB" ]] || LIB="/etc/ablestack/veeam/mold-backup.lib.sh"
[[ -f "$LIB" ]] || exit 0

# shellcheck source=/dev/null
source "$LIB"
# Guest VMs use Mold_Guest_Backup.conf (VEEAM_TRIGGER_*, VM_TARGETS, VEEAM_SSH_*).
export MOLD_BACKUP_CONF="${MOLD_BACKUP_CONF:-/etc/ablestack/veeam/Mold_Guest_Backup.conf}"
mold_backup_load_config || exit 0

[[ "${VEEAM_TRIGGER_ENABLED:-false}" == "true" ]] || {
  mold_backup_notify_log info "Mold→Veeam trigger disabled (set VEEAM_TRIGGER_ENABLED=true in Mold_Guest_Backup.conf)"
  exit 0
}

# If Veeam started this backup (veeam-active marker), do NOT start Veeam again.
if mold_backup_trigger_active "veeam-active" "$VM"; then
  mold_backup_notify_log info "veeam-active present for ${VM}: Veeam-driven backup, skip Mold→Veeam trigger"
  exit 0
fi

mold_backup_notify_log info "Mold→Veeam trigger hook: vm=${VM} op=${OP}"
mold_backup_trigger_veeam_job "$VM" || mold_backup_notify_log warn "Mold→Veeam trigger failed for ${VM} (see log above)"

exit 0
