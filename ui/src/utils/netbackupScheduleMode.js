// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export const netBackupScheduleModeKey = 'backup.plugin.netbackup.vm.schedule.enabled'
export const netBackupHookTimeoutKey = 'backup.plugin.netbackup.hook.timeout'

export function netBackupScheduleModeError (error, translate, fallback) {
  const data = error?.response?.data || {}
  const detail = data.updateconfigurationresponse?.errortext || data.resetconfigurationresponse?.errortext || data.errorresponse?.errortext || error?.message || fallback
  const hook = typeof detail === 'string' && detail.match(/^NETBACKUP_HOOK_TIMEOUT_([A-Z_]+):\s*(.*)$/)
  const hookCodes = ['INVALID_VALUE', 'GLOBAL_ONLY', 'UNSAFE', 'SYNC_FAILED', 'RECOVERY_PENDING', 'SAVE_UNCONFIRMED']
  if (hook && hookCodes.includes(hook[1])) return translate('message.netbackup.hook.timeout.' + hook[1].toLowerCase(), { detail: hook[2] })
  const match = typeof detail === 'string' && detail.match(/^NETBACKUP_VM_SCHEDULE_MODE_([A-Z_]+):\s*(.*)$/)
  const codes = ['BUSY', 'BACKUP_BUSY', 'CLEANUP_PENDING', 'HOST_BUSY', 'POLICY_ACTIVE', 'NATIVE_BUSY', 'UNCONFIRMED', 'GLOBAL_ONLY', 'INVALID_VALUE']
  return match && codes.includes(match[1]) ? translate('message.netbackup.schedule.mode.' + match[1].toLowerCase(), { detail: match[2] }) : detail
}
