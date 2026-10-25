// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { reactive } from 'vue'
import md5 from 'md5'
import { getAPI, postAPI } from '@/api'
import { snapshotOperationResponse } from '@/utils/storageSnapshotContext'
import { createJobTracker } from '@/utils/jobTracker'
import { snapshotDownloadResult } from '@/utils/snapshotDownload'

export const storageSnapshotOperations = reactive({})
const storageKey = 'mold.snapshot.operations.v1'
const contexts = new Map()
export function snapshotSecurityReplaced (next, before) {
  const current = JSON.parse(next || '[]'); const previous = JSON.parse(before || '[]')
  return !!((previous[0] && current[0] !== previous[0]) || (previous[1] && current[1] !== previous[1]))
}
export const operationScopeKey = scope => md5(scope) // Namespace only; never an authorization decision.
function persist () {
  try {
    const entries = Object.values(storageSnapshotOperations).map(({ id, scope, api, target, keys, status, jobid, requestid, created, error, dismissed, result }) => ({ id, scope, api, target, keys, status, jobid, requestid, created, error, dismissed, result: result ? { jobstatus: result.jobstatus } : null }))
    const pending = entries.filter(entry => ['checking', 'submitting', 'accepted', 'unknown'].includes(entry.status))
    const finished = entries.filter(entry => !['checking', 'submitting', 'accepted', 'unknown'].includes(entry.status)).slice(-100)
    sessionStorage.setItem(storageKey, JSON.stringify([...pending, ...finished]))
  } catch (_) { /* An unavailable browser store does not permit another in-session mutation. */ }
}
export function hydrateSnapshotOperations () {
  try {
    for (const entry of JSON.parse(sessionStorage.getItem(storageKey) || '[]')) {
      if (!entry.id || !entry.scope || !Array.isArray(entry.keys)) continue
      if (['accepted', 'submitting', 'checking'].includes(entry.status)) entry.status = entry.status === 'checking' ? 'rejected' : 'unknown'
      storageSnapshotOperations[entry.id] = entry
    }
  } catch (_) { }
}
hydrateSnapshotOperations()
function requestUuid () {
  const bytes = new Uint8Array(16); crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = [...bytes].map(x => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
export const snapshotOperationActive = status => ['checking', 'submitting', 'accepted', 'unknown'].includes(status)
export function snapshotOperationBusy (scope, keys) {
  const hash = operationScopeKey(scope)
  return Object.values(storageSnapshotOperations).some(entry => entry.scope === hash && snapshotOperationActive(entry.status) && entry.keys.some(key => keys.includes(key)))
}
function setState (entry, status, error = '') { entry.status = status; entry.error = error; persist() }
function resultConfirmed (api, result, forceExpected) {
  const payload = snapshotOperationResponse(api, result.jobresult) || result.jobresult
  if (!payload || typeof payload !== 'object') return false
  if (api === 'deleteSnapshot' && (forceExpected || payload.force)) return payload.force === true && payload.dbcleanupcomplete === true
  if (api === 'createSnapshot' || api === 'revertSnapshot' || api === 'copySnapshot') return !!payload.snapshot?.id || !!payload.snapshot?.[0]?.id || payload.success === true
  if (['createVolume', 'attachVolume'].includes(api)) return !!payload.volume?.id
  if (api === 'createTemplate') return !!payload.template?.id || !!payload.template?.[0]?.id
  if (api === 'extractSnapshot') return !!snapshotDownloadResult(payload)
  return payload.success === true
}
const tracker = createJobTracker({
  interval: 1500,
  query: async id => {
    const context = contexts.get(id)
    if (!context?.securityCurrent()) throw Object.assign(new Error('message.snapshot.action.context.changed'), { response: { status: 403 } })
    const json = await getAPI('queryAsyncJobResult', { jobid: id }, { timeout: 15000, backgroundJob: true, preserveOnFailure: true })
    return json.queryasyncjobresultresponse
  },
  onState: (id, result, meta) => {
    const entry = storageSnapshotOperations[meta.id]
    if (!entry) return
    if ([0, 1, 2].includes(result.jobstatus)) entry.jobid = id
    if (result.jobstatus === 0) setState(entry, 'accepted')
    else if (result.jobstatus === 1 && resultConfirmed(entry.api, result, entry.target.force)) { entry.result = result; setState(entry, 'complete') } else if (result.jobstatus === 2) { entry.result = result; setState(entry, 'failed', result.jobresult?.errortext || 'message.snapshot.action.failed') } else if (result.trackingStatus !== 'retrying') setState(entry, 'unknown', 'message.job.result.unknown')
  }
})
export function querySnapshotOperation (entry, securityCurrent, retry = true) {
  const jobid = entry.jobid || entry.requestid
  if (!jobid || !securityCurrent()) return Promise.resolve(null)
  contexts.set(jobid, { securityCurrent })
  entry.completion = tracker.track(jobid, { id: entry.id }, retry).then(result => {
    if (result.jobstatus === 1 && entry.status !== 'complete') return { ...result, jobstatus: null, trackingStatus: 'unknown' }
    return result
  })
  return entry.completion
}
function definiteRejection (error) {
  const status = error.response?.status
  return status >= 400 && status < 500 && Object.values(error.response?.data || {}).some(value => value?.errorcode)
}
export async function submitSnapshotOperation (options) {
  if (!options.current() || snapshotOperationBusy(options.scope, options.keys)) throw new Error('message.snapshot.action.busy')
  const id = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
  let entry = { id, scope: operationScopeKey(options.scope), api: options.api, target: options.target, keys: options.keys, status: 'checking', jobid: '', requestid: requestUuid(), error: '', created: new Date().toISOString() }
  storageSnapshotOperations[id] = entry; entry = storageSnapshotOperations[id]; persist()
  let params
  try { params = await options.validate() } catch (error) { setState(entry, 'rejected', error.message); error.submissionPrevented = true; throw error }
  if (!options.current()) { setState(entry, 'rejected', 'message.snapshot.action.context.changed'); throw Object.assign(new Error(entry.error), { submissionPrevented: true }) }
  setState(entry, 'submitting')
  try {
    const response = await (options.submit || postAPI)(options.api, { ...params, customjobid: entry.requestid }, { preserveOnFailure: true })
    const data = snapshotOperationResponse(options.api, response)
    if (!data?.jobid) { setState(entry, 'unknown', 'message.snapshot.action.submission.unknown'); if (options.securityCurrent()) querySnapshotOperation(entry, options.securityCurrent, false); return entry }
    entry.jobid = data.jobid
    setState(entry, 'accepted')
    if (options.securityCurrent()) querySnapshotOperation(entry, options.securityCurrent, false)
    else setState(entry, 'unknown', 'message.snapshot.action.context.changed')
  } catch (error) {
    setState(entry, definiteRejection(error) ? 'failed' : 'unknown', error.response?.data && definiteRejection(error) ? Object.values(error.response.data)[0]?.errortext || error.message : 'message.snapshot.action.submission.unknown')
  }
  if (entry.status === 'unknown' && !entry.jobid && !entry.completion && options.securityCurrent()) querySnapshotOperation(entry, options.securityCurrent, false)
  return entry
}
export async function attachSnapshotOperationJob (entry, jobid, acknowledged, securityCurrent) {
  if (entry.requestid && jobid !== entry.requestid) throw new Error('message.snapshot.action.job.mismatch')
  if (!acknowledged || !securityCurrent() || entry.status !== 'unknown' || entry.jobid || !/^[a-f0-9-]{36}$/i.test(jobid)) throw new Error('message.snapshot.action.job.confirm')
  const result = (await getAPI('queryAsyncJobResult', { jobid }, { preserveOnFailure: true, timeout: 15000 })).queryasyncjobresultresponse
  if (!securityCurrent() || ![0, 1, 2].includes(result?.jobstatus)) throw new Error('message.snapshot.action.incomplete')
  const command = entry.api[0].toUpperCase() + entry.api.slice(1) + 'Cmd'
  if (result.cmd && ![command, command + 'ByAdmin'].includes(result.cmd.split('.').pop())) throw new Error('message.snapshot.action.job.mismatch')
  if (['deleteSnapshot', 'revertSnapshot', 'archiveSnapshot', 'copySnapshot'].includes(entry.api) && result.jobinstanceid && result.jobinstanceid !== entry.target.id) throw new Error('message.snapshot.action.job.mismatch')
  const body = snapshotOperationResponse(entry.api, result.jobresult) || result.jobresult
  const source = entry.api === 'createVolume' ? body?.volume?.snapshotid : entry.api === 'createSnapshot' ? body?.snapshot?.volumeid : null
  if (source && source !== entry.target.id) throw new Error('message.snapshot.action.job.mismatch')
  entry.jobid = jobid; setState(entry, 'unknown')
  return querySnapshotOperation(entry, securityCurrent)
}
export function releaseUnknownSnapshotSubmission (entry, acknowledged) {
  if (!acknowledged || entry.status !== 'unknown' || entry.jobid) return false
  setState(entry, 'released'); return true
}
export function pauseSnapshotOperations () { tracker.clear() }
export function clearStorageSnapshotOperations () {
  tracker.clear(); contexts.clear()
  Object.keys(storageSnapshotOperations).forEach(id => delete storageSnapshotOperations[id])
  try { sessionStorage.removeItem(storageKey) } catch (_) { }
}

// Clearing notifications never releases an unresolved resource lease.
export function dismissSnapshotNotifications (scope) {
  const hash = operationScopeKey(scope)
  for (const entry of Object.values(storageSnapshotOperations)) {
    if (entry.scope === hash && !snapshotOperationActive(entry.status)) entry.dismissed = true
  }
  persist()
}
