#!/bin/bash

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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/netbackup-host-common.sh"

usage() {
  cat <<'EOF'
Usage:
  netbackup-host-bpstart-notify.sh [policy] [schedule] [client] [timestamp]

Environment overrides:
  STATE_ROOT       Default: /var/lib/ablestack/netbackup
  LOG_FILE         Default: /var/log/cloudstack/agent/agent.log
  NOTE             Increase NetBackup BPSTART_TIMEOUT/CLIENT_READ_TIMEOUT for large API-driven backups.
  POLICY_NAME
  SCHEDULE_NAME
  CLIENT_NAME
  SESSION_TIMESTAMP
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

cleanup_failed_pre_run() {
  local rc=$?
  if [[ ${rc} -ne 0 ]]; then
    clear_context_in_progress
    rm -f "${MOLD_VM_CACHE_FILE:-}"
  fi
  exit ${rc}
}

ensure_runtime_dirs
cleanup_stale_transient_state
resolve_context "${1:-}" "${2:-}" "${3:-}" "${4:-}"
# Child policies protect already prepared artifacts. They must never recursively ask Mold for a backup.
if [[ "${POLICY_NAME}" =~ ^ABLESTACK-[[:xdigit:]-]{36}-(metadata|[[:xdigit:]-]{36})$ ]]; then
  exit 0
fi
cleanup_runtime_history
load_policy_schedule_config
acquire_lock
sanity_checks
mark_context_in_progress
trap cleanup_failed_pre_run EXIT
initialize_runtime_cache
init_runtime_state
cache_mold_virtual_machines

write_state_file "${CONTEXT_FILE}" \
  POLICY_NAME "${POLICY_NAME}" \
  SCHEDULE_NAME "${SCHEDULE_NAME}" \
  CLIENT_NAME "${CLIENT_NAME}" \
  SESSION_TIMESTAMP "${SESSION_TIMESTAMP}" \
  BACKUP_ID "${BACKUP_ID}" \
  BACKUP_TIME "${BACKUP_TIME}" \
  UNIX_BACKUP_TIME "${UNIX_BACKUP_TIME}" \
  RUNTIME_FILE "${RUNTIME_FILE}"

log -ne "NetBackup pre-backup API dispatch start policy=${POLICY_NAME} schedule=${SCHEDULE_NAME} client=${CLIENT_NAME} timestamp=${SESSION_TIMESTAMP}"

vm_count=0
success_count=0
failed_count=0
while IFS= read -r vm_name; do
  [[ -z "${vm_name}" ]] && continue
  vm_count=$((vm_count + 1))
  if result="$(run_stage_vm_backup "${vm_name}")"; then
    IFS=$'\t' read -r stage_status vm_id job_id backup_path error_text <<< "${result}"
    append_runtime_vm_result "${vm_name}" "SUCCESS" "${vm_id}" "${job_id}" "${backup_path}" ""
    success_count=$((success_count + 1))
    log -ne "NetBackup pre-backup VM success vm=${vm_name} vmId=${vm_id} jobId=${job_id} backupPath=${backup_path}"
  else
    IFS=$'\t' read -r stage_status vm_id job_id backup_path error_text <<< "${result}"
    append_runtime_vm_result "${vm_name}" "FAILED" "${vm_id}" "${job_id}" "${backup_path}" "${error_text}"
    failed_count=$((failed_count + 1))
    log -ne "NetBackup pre-backup VM failed vm=${vm_name} vmId=${vm_id} jobId=${job_id} error=${error_text}"
    continue
  fi
done < <(list_target_vms)

if [[ ${vm_count} -eq 0 ]]; then
  update_runtime_status "MOLD_BACKUP_SKIPPED_NO_TARGET"
  log -ne "No target running VMs found on host for NetBackup staging; skipping successfully"
  exit 0
fi

if [[ ${success_count} -gt 0 && ${failed_count} -eq 0 ]]; then
  update_runtime_status "MOLD_BACKUP_READY_ALL"
elif [[ ${success_count} -gt 0 ]]; then
  update_runtime_status "MOLD_BACKUP_READY_PARTIAL"
else
  update_runtime_status "MOLD_BACKUP_FAILED_ALL"
  exit 1
fi

sync
log -ne "NetBackup pre-backup API dispatch complete count=${vm_count} success=${success_count} failed=${failed_count}"
trap - EXIT
clear_context_in_progress
