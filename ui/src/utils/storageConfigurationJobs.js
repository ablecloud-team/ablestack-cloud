// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { reactive } from 'vue'
import { getAPI } from '@/api'
import { createJobTracker } from '@/utils/jobTracker'
import { removalActor } from '@/utils/sharedfsRemoval'
import { validSharedFsGateway } from '@/utils/storageNetworkReadiness'

// Accepted operations survive dialog/component closure in memory, never in browser storage.
export const configurationIntents = reactive({})
const asyncCommands = new Set(['createStorageServiceConfigBackup', 'verifyStorageServiceConfiguration', 'applyStorageServiceConfigRestore', 'restoreStorageServiceLastKnownGood', 'deleteStorageServiceConfigBackup', 'deleteStorageServiceConfigImport'])
let actor = null
let sequence = 0
export const configurationActor = removalActor
export const configurationScope = vm => JSON.stringify([configurationActor(vm), vm.instanceId, vm.resource?.account || '', vm.resource?.domainid || '', vm.resource?.projectid || ''])
export const activeConfigurationIntent = intent => !!intent && ['submitting', 'running', 'unknown'].includes(intent.status)
export const configurationCommandIsAsync = command => asyncCommands.has(command)
export const validConfigurationJobId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value)
export function clearConfigurationIntents () { tracker.clear(); Object.keys(configurationIntents).forEach(key => delete configurationIntents[key]) }
export function ensureConfigurationActor (value) {
  if (actor !== null && actor !== value) clearConfigurationIntents()
  actor = value
}
export function beginConfigurationIntent (vm, command, artifactId, review = {}) {
  ensureConfigurationActor(configurationActor(vm))
  const scope = configurationScope(vm)
  if (activeConfigurationIntent(configurationIntents[scope])) throw new Error('message.storage.config.job.blocked')
  configurationIntents[scope] = {
    id: ++sequence,
    scope,
    actor: configurationActor(vm),
    instanceId: vm.instanceId,
    command,
    artifactId: typeof artifactId === 'string' ? artifactId : '',
    status: 'submitting',
    jobId: '',
    // Only a non-secret receipt of the reviewed action is retained.
    review: { targetMode: review.targetMode || '', create: review.create?.length || 0, update: review.update?.length || 0, keep: review.keep?.length || 0, preserve: review.preserve?.length || 0 },
    errorCode: null
  }
  return configurationIntents[scope]
}
export function definitiveConfigurationRejection (error, command) {
  const body = error?.response?.data
  const response = body?.errorresponse || body?.[command.toLowerCase() + 'response']
  return !!response && Number.isInteger(response.errorcode) && typeof response.errortext === 'string' && !response.jobid
}
const tracker = createJobTracker({
  interval: 1000,
  maxDuration: 180000,
  query: jobId => {
    const meta = tracker.metadata(jobId)
    if (!meta?.authorized() || configurationIntents[meta.intent.scope] !== meta.intent) throw Object.assign(new Error('scope changed'), { response: { status: 401 } })
    return getAPI('queryAsyncJobResult', { jobid: jobId }, { preserveOnFailure: true, backgroundJob: true, timeout: 15000 }).then(value => value.queryasyncjobresultresponse)
  },
  onState: (jobId, result, { intent }) => {
    if (configurationIntents[intent.scope] !== intent) return
    intent.status = result?.jobstatus === 1 ? 'complete' : result?.jobstatus === 2 ? 'failed' : result?.trackingStatus && result.trackingStatus !== 'retrying' ? 'unknown' : 'running'
    intent.errorCode = result?.jobstatus === 2 && Number.isInteger(result.jobresultcode) ? result.jobresultcode : null
  }
})
export function trackConfigurationIntent (intent, authorized) {
  if (!validConfigurationJobId(intent?.jobId)) return Promise.resolve({ jobstatus: null, trackingStatus: 'unknown' })
  intent.status = 'running'
  return tracker.track(intent.jobId, { intent, authorized }, true)
}
const hidden = /password|secret|token|credential|cipher|private|sourceauth|issuermac|key/i
export function configurationPublicValue (value, field = '', depth = 0) {
  if (hidden.test(field)) return '[REDACTED]'
  if (depth > 8) return '…'
  if (Array.isArray(value)) return value.map(item => configurationPublicValue(item, field, depth + 1))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, configurationPublicValue(item, key, depth + 1)]))
  if (typeof value === 'string' && /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/.test(value)) return '[REDACTED]'
  if (typeof value === 'string' && ['[', '{'].includes(value.trim()[0])) {
    try { return configurationPublicValue(JSON.parse(value), field, depth + 1) } catch (_) {}
  }
  return value
}
export function configurationDifference (current, desired) {
  const before = configurationPublicValue(current || {})
  const after = configurationPublicValue(desired || {})
  const fields = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()
  const display = value => value === undefined ? '—' : typeof value === 'string' ? value : JSON.stringify(value)
  return fields.map(field => ({ field, before: display(before[field]), after: display(after[field]), changed: JSON.stringify(before[field]) !== JSON.stringify(after[field]) }))
}

export function validConfigurationStaticNetwork (clone) {
  const ipv4 = value => typeof value === 'string' && value.split('.').length === 4 && value.split('.').every(part => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)
  const parts = typeof clone.ipcidr === 'string' ? clone.ipcidr.trim().split('/') : []
  if (parts.length !== 2 || !ipv4(parts[0]) || !/^\d+$/.test(parts[1]) || Number(parts[1]) > 32 || !validSharedFsGateway(clone.ipcidr, clone.gateway)) return false
  return ['dns1', 'dns2'].every(field => clone[field] === undefined || clone[field] === null || clone[field] === '' || ipv4(clone[field]))
}
