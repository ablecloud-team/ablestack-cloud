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

const messages = new Map([
  ['Configuration worker stopped without a completion receipt; inspect host services before retrying', 'worker.stopped'],
  ['Configured hooks or restore watcher timer are unavailable', 'components.unavailable'],
  ['Unable to start host configuration worker', 'worker.start.failed'],
  ['Previous configuration outcome is unconfirmed; inspect the host before retrying', 'previous.unconfirmed'],
  ['Install the NetBackup Client on this Host first', 'client.required'],
  ['Policy Client does not match the selected Mold Host, Zone and Cluster', 'identity.mismatch'],
  ['Shared backup settings changed; refresh this page and retry', 'settings.changed'],
  ['Deactivate the NetBackup Host policy before applying configuration; reactivate it after validation', 'policy.deactivate'],
  ['Host files were written but the restore watcher timer is not active; retry configuration', 'watcher.inactive'],
  ['Host configuration failed; inspect the Host services', 'host.failed'],
  ['Cannot confirm a complete active NetBackup job inventory', 'jobs.unconfirmed'],
  ['An active NetBackup job has no verifiable Client', 'jobs.client.unknown'],
  ['Finish NetBackup jobs on this Client before host configuration', 'jobs.active'],
  ['Only root administrators can configure NetBackup Hosts', 'admin.required'],
  ['Select a registered KVM routing Host', 'host.required'],
  ['Enable ablestack-netbackup in this Zone\'s effective backup provider setting before preparing this Host', 'provider.required'],
  ['NetBackup Host configuration requires ablestack-netbackup in this Zone\'s effective backup provider setting', 'provider.required'],
  ['Connect the KVM Host before inspecting its configuration', 'host.connect'],
  ['The KVM Host must be connected', 'host.connect'],
  ['Choose CHECK or APPLY', 'action.required'],
  ['Specify a valid existing NetBackup policy name (maximum 128 characters)', 'policy.name.invalid'],
  ['Enable the backup framework, Zone NetBackup provider and common staging first; restart Management if required', 'prerequisites'],
  ['Configure the NetBackup URL and API key in Global settings or this Zone\'s overrides first', 'connection.required'],
  ['Register the Zone NetBackup URL and API key first', 'connection.required'],
  ['Register an API key pair for this administrator before preparing Host integration', 'credentials.required'],
  ['Staging settings are being changed; retry shortly', 'staging.busy'],
  ['Finish queued, active and unconfirmed staging work on this Host before configuration', 'staging.active'],
  ['Host configuration response is unconfirmed; refresh its status', 'response.unconfirmed'],
  ['Invalid Host configuration receipt', 'receipt.invalid'],
  ['Use an HTTP(S) API endpoint without credentials, query parameters or fragments', 'url.invalid'],
  ['NetBackup returned a different policy name; configuration was stopped', 'policy.returned.mismatch'],
  ['NetBackup requires an existing Standard policy containing exactly one Client', 'policy.client.required'],
  ['NetBackup policy Client does not match the verified Mold KVM host', 'policy.client.mismatch'],
  ['Cannot uniquely match the policy Client to one Mold-registered local KVM host; check its Client DNS/IP and Mold Host IP', 'host.match.failed'],
  ['The Mold Host matched by the policy Client is not this executing KVM host', 'host.local.mismatch'],
  ['Matched KVM host is missing its UUID, name, zone ID or cluster ID', 'host.identity.missing'],
  ['Mold Host inventory pagination did not terminate', 'host.pagination'],
  ['Keep a Full Backup schedule on the Host policy to dispatch VM requests through the bootstrap hooks', 'schedule.full.required'],
  ['Add schedule mold-transfer with Backup type User Backup to the existing Host policy', 'schedule.transfer.required'],
  ['Open the mold-transfer User Backup start window; use every day 00:00-24:00', 'schedule.window.required'],
  ['Configure common staging and enable backup.thirdparty.staging.enable in Mold, then run this script again.', 'staging.required'],
  ['Common staging local.mode must be MOUNT or DIRECTORY', 'staging.local.mode'],
  ['Common staging root.path and mount.path must specify absolute paths on a separate mounted filesystem.', 'staging.paths.invalid'],
  ['Common staging root.path must be inside mount.path.', 'staging.path.outside'],
  ['Watcher timer is paused, but a polling pass is still running. Wait for it to finish, then rerun configuration.', 'watcher.busy'],
  ['LOCAL staging root must already exist and be writable; validate staging activation in Mold first', 'staging.local.invalid'],
  ['Unable to verify the common staging mount. Check findmnt and the staging filesystem on this host.', 'staging.mount.unknown'],
  ['The common staging filesystem is not mounted on this host. Revalidate staging activation in Mold first.', 'staging.mount.missing']
])

const completionMessages = new Set([
  'Assign the NetBackup offering to the VMs to protect and register their Mold backup schedules',
  'Assign the NetBackup offering to the VMs to protect; no Mold VM schedule is required'
])

const steps = new Set(['PREFLIGHT', 'CLIENT', 'POLICY', 'HOST', 'SCHEDULES', 'STAGING', 'SETTINGS', 'IDLE', 'OFFERING', 'HOST_FILES', 'HOST_DIRECTORIES', 'WATCHER_PAUSE', 'HOOKS_AND_CREDENTIALS', 'RESTORE_WATCHER_FILES', 'BOOTSTRAP', 'CLIENT_SERVICE', 'WATCHER_SERVICE', 'POLICY_PATH', 'FINISHED'])

// Translate existing Host receipts as well as new responses; retain unknown diagnostics.
export function netBackupHostMessage (message, translate, info = {}) {
  if (typeof message !== 'string' || !message) return message || ''
  const text = message.trim()
  if (completionMessages.has(text)) {
    if (info.action === 'CHECK') {
      const configured = info.configured && info.hooksready && info.timeractive
      return translate(configured ? 'message.backup.integration.rechecked' : 'message.backup.integration.checked')
    }
    if (info.state === 'POLICY_PATH_MISMATCH') return translate('message.backup.integration.applied.path')
    return translate(info.vmschedulebackup === false ? 'message.backup.integration.applied.policy' : 'message.backup.integration.applied.schedule')
  }
  if (messages.has(text)) return translate('message.backup.integration.result.' + messages.get(text))
  const http = text.match(/^Cannot read NetBackup policy: HTTP (\d+); check the API key, permissions and policy name$/)
  if (http) return translate(http[1] === '401' ? 'message.backup.integration.policy.auth.failed' : 'message.backup.integration.policy.http.failed', { status: http[1] })
  const policy = text.match(/^Cannot verify NetBackup policy: (.+)$/)
  if (policy) return translate('message.backup.integration.policy.verify.failed', { error: policy[1] })
  const client = text.match(/^NetBackup policy Client '([^']+)' does not resolve to this KVM host's local interfaces$/)
  if (client) return translate('message.backup.integration.policy.client.address', { client: client[1] })
  return message
}

export function netBackupHostStep (value, translate) {
  return steps.has(value) ? translate('label.backup.integration.step.' + value.toLowerCase()) : value
}

export function netBackupHostCheckState (value, translate) {
  const keys = { OK: 'label.success', FAILED: 'label.failed', MISMATCH: 'label.backup.integration.check.mismatch' }
  return keys[value] ? translate(keys[value]) : value
}
