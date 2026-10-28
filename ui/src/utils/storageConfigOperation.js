// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information. Licensed under the Apache License,
// Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
import { creationRequestId } from '@/utils/sharedFsBacking'

const configurationApis = new Set([
  'updateStorageNfsIdentityMapping', 'createStorageDirectoryPolicy', 'updateStorageDirectoryPolicy',
  'enableStorageServiceProtocol', 'deleteStorageServiceProtocol',
  ...['NfsExport', 'NfsAcl', 'SmbShare', 'SmbAcl', 'IscsiTarget', 'IscsiAcl',
    'NvmeOfSubsystem', 'NvmeOfNamespace', 'NvmeOfHostAcl'].flatMap(resource =>
    ['create', 'update', 'delete'].map(action => action + 'Storage' + resource))
])
export function isConfigurationApi (api) { return configurationApis.has(api) }
export { creationRequestId as configurationRequestId }

export function decodeConfigurationOperation (wire = {}) {
  // ApiResponseSerializer emits one named object, not a list envelope.
  const response = wire.getstorageserviceconfigurationoperationresponse ||
    wire.resumestorageserviceconfigurationoperationresponse || wire
  const value = response.configurationoperation || response
  return value?.id && value?.state ? value : null
}
export function decodeConfigurationOperations (wire = {}) {
  const response = wire.liststorageserviceconfigurationoperationsresponse || wire
  const value = response.configurationoperation
  return Array.isArray(value) ? value : value ? [value] : []
}
export const terminalConfigurationState = state => ['COMMITTED', 'ROLLED_BACK'].includes(state)
export function configurationRetryAllowed (operation) {
  return !operation || ['ROLLED_BACK', 'SUBMISSION_REJECTED'].includes(operation.state)
}

/** Every observation calls GET only. The caller explicitly owns the one POST. */
export async function observeConfigurationOperation ({
  get, instanceid, requestid, jobid, changed = () => {}, current = () => true,
  sleep = ms => new Promise(resolve => setTimeout(resolve, ms)), maxAttempts = 300
}) {
  let last = null
  let observationAttempts = maxAttempts
  for (let attempt = 0; attempt < observationAttempts && current(); attempt++) {
    try {
      const rows = decodeConfigurationOperations(await get('listStorageServiceConfigurationOperations', { instanceid, requestid }))
      if (!current()) return null
      if (rows.length) {
        last = rows[0]; changed(last)
        const seconds = Number(last.observation?.execution?.observationSeconds || (last.observation?.execution?.softDeadlineAt - last.observation?.execution?.startedAt + 300))
        if (Number.isFinite(seconds)) observationAttempts = Math.min(1200, Math.max(observationAttempts, Math.ceil(seconds / 2)))
        if (last.canresume && !jobid) return last
      }
      // A queue may not have created its operation row yet. Empty list is still queued.
      if (last?.state === 'COMMITTED') {
        if (!jobid || last.result !== undefined) return last
      }
      if (last?.state === 'ROLLED_BACK' || last?.state === 'RECOVERY_REQUIRED') return last
      if (jobid) {
        const wire = await get('queryAsyncJobResult', { jobid })
        if (!current()) return null
        const job = wire.queryasyncjobresultresponse || {}
        if (job.jobstatus === 1 && last?.state === 'COMMITTED') return { ...last, result: job.jobresult }
        // The job can finish after the list snapshot was read. Confirm recovery
        // against the same operation again rather than returning that stale row.
        if ([1, 2].includes(job.jobstatus) && last?.canresume) {
          const freshRows = decodeConfigurationOperations(await get('listStorageServiceConfigurationOperations', { instanceid, requestid }))
          if (!current()) return null
          const fresh = freshRows.find(value => value.id === last.id &&
            (!value.instanceid || value.instanceid === instanceid) &&
            (!value.requestid || value.requestid === requestid))
          if (!fresh) throw new Error('Configuration operation observation unavailable')
          last = fresh; changed(last)
          if (last.state === 'COMMITTED') return { ...last, result: job.jobstatus === 1 ? (job.jobresult ?? last.result) : last.result }
          if (last.state === 'ROLLED_BACK' || last.state === 'RECOVERY_REQUIRED' || last.canresume) return last
        }
        if (job.jobstatus === 2 && !last) {
          const rejected = {
            requestid,
            state: 'SUBMISSION_REJECTED',
            phase: 'VALIDATING',
            originalfailure: { code: 'REQUEST_REJECTED' },
            message: job.jobresult?.errortext
          }
          changed(rejected); return rejected
        }
      }
    } catch (error) {
      if (!current()) return null
      changed({ ...(last || { requestid, state: 'QUEUED' }), observationwarning: true })
    }
    await sleep(2000)
  }
  if (!current()) return null
  const uncertain = { ...(last || { requestid }), state: 'OBSERVATION_UNAVAILABLE', phase: 'OBSERVING' }
  changed(uncertain)
  return uncertain
}

export async function observeConfigurationJob ({ get, jobid, current = () => true, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) }) {
  for (let attempt = 0; attempt < 300 && current(); attempt++) {
    try {
      const wire = await get('queryAsyncJobResult', { jobid })
      if (!current()) return null
      const job = wire.queryasyncjobresultresponse || {}
      if (job.jobstatus === 1 || job.jobstatus === 2) return job
    } catch (error) { if (!current()) return null }
    await sleep(2000)
  }
  return null
}
