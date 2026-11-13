// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { createJobTracker } from './jobTracker'

const copy = value => JSON.parse(JSON.stringify(value))
const commands = ['createVPCOffering', 'cloneVPCOffering']
const busyStates = ['checking', 'submitting', 'running', 'verifying']
export const vpcOfferingBusy = state => busyStates.includes(state?.status)
export const vpcOfferingBlocked = state => vpcOfferingBusy(state) || ['unknown', 'succeeded'].includes(state?.status)
const options = { preserveOnFailure: true, backgroundJob: true, timeout: 15000 }
const empty = () => ({ status: 'idle', command: '', params: {}, draft: {}, requestId: '', jobId: '', resultId: '', error: '', failure: '', code: '' })
const apiError = error => Object.values(error?.response?.data || {}).find(value => value && typeof value === 'object' && value.errorcode)
const requestId = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

// The receipt and submitted form survive closing/reopening and a page reload.
// No credentials or session token are written to sessionStorage.
export function createVpcOfferingOperation ({ get, post, storage, storageKey, scope, getScope = () => scope, userId, makeId = requestId, pollOptions = {} }) {
  let state = empty()
  let active = true
  let running
  const listeners = new Set()
  const current = () => active && getScope() === scope
  const snapshot = () => copy(state)
  try {
    const saved = JSON.parse(storage.getItem(storageKey) || 'null')
    if (saved) {
      if (saved.version !== 1 || !commands.includes(saved.state?.command) || !saved.state?.params?.name) throw new Error('Invalid receipt')
      state = saved.state
      if (state.status === 'checking') {
        state.status = 'rejected'; state.failure = 'preflight'; state.error = 'message.vpc.offering.operation.preflight'
      } else if (vpcOfferingBusy(state)) {
        state.status = 'unknown'; state.jobId = state.jobId || state.requestId
      }
    }
  } catch (_) { state = { ...empty(), status: 'unknown', failure: 'storage', error: 'message.vpc.offering.operation.storage' } }
  const publish = patch => {
    if (!current()) return false
    state = { ...state, ...patch }
    let saved = true
    try { storage.setItem(storageKey, JSON.stringify({ version: 1, state })) } catch (_) {
      saved = false
      state = { ...state, status: 'unknown', failure: 'storage', error: 'message.vpc.offering.operation.storage' }
    }
    for (const listener of listeners) { try { listener(snapshot()) } catch (_) { console.error('[vpcOffering] observer failed') } }
    return saved
  }
  const read = async (command, params) => {
    if (!current()) throw new Error('Scope changed')
    const response = await get(command, { ...params, ignoreproject: true }, options)
    if (!current()) throw new Error('Scope changed')
    return response?.[command.toLowerCase() + 'response']
  }
  const offerings = async params => {
    const rows = []
    for (let page = 1; page <= 100; page++) {
      const response = await read('listVPCOfferings', { ...params, listall: true, page, pagesize: 100 })
      if (!response || !Array.isArray(response.vpcoffering || []) || response.errorcode) throw new Error('Invalid offering result')
      rows.push(...(response.vpcoffering || []))
      if ((response.vpcoffering || []).length < 100) return rows
    }
    throw new Error('Incomplete offering result')
  }
  const tracker = createJobTracker({
    interval: 1500,
    maxRetries: 2,
    maxDuration: 120000,
    ...pollOptions,
    query: async id => {
      const job = await read('queryAsyncJobResult', { jobid: id })
      if (job?.jobid !== id || (job.cmd && job.cmd.split('.').pop().toLowerCase() !== state.command.toLowerCase() + 'cmd') || (userId && job.userid && job.userid !== userId)) throw new Error('Job scope mismatch')
      return job
    },
    onState: (id, result) => {
      if (!current() || id !== state.jobId) return
      if (result.jobstatus === 0) publish({ status: 'running', error: '', failure: '' })
      else if (result.jobstatus === 2) publish({ status: 'failed', failure: 'job', code: result.jobresult?.errorcode || result.jobresultcode || '', error: result.jobresult?.errortext || 'message.vpc.offering.operation.failed' })
      else if (result.jobstatus === 1) publish({ status: 'verifying', error: '' })
      else if (result.trackingStatus !== 'retrying' && result.trackingStatus !== 'cancelled') publish({ status: 'unknown', failure: 'query', error: 'message.vpc.offering.operation.unknown' })
    }
  })
  const track = retry => {
    if (!current() || !state.jobId) return Promise.resolve(snapshot())
    publish({ status: 'running', error: '', failure: '' })
    running = tracker.track(state.jobId, {}, retry).then(async job => {
      if (!current() || job.jobstatus !== 1) return snapshot()
      const entity = job.jobresult?.vpcoffering
      try {
        // cloneVPCOffering's receipt.id can be the numeric source ID.
        // Only the final job result identifies the new offering.
        if (!entity?.id || entity.name !== state.params.name) throw new Error('Offering result mismatch')
        const rows = await offerings({ id: entity.id })
        if (rows.length !== 1 || rows[0].id !== entity.id || rows[0].name !== state.params.name) throw new Error('Offering verification failed')
        publish({ status: 'succeeded', resultId: entity.id, error: '', failure: '', code: '' })
      } catch (_) { publish({ status: 'unknown', failure: 'result', error: 'message.vpc.offering.operation.result' }) }
      return snapshot()
    })
    return running
  }
  return {
    snapshot,
    subscribe (listener) { listeners.add(listener); listener(snapshot()); return () => listeners.delete(listener) },
    invalidate (clear = false) {
      active = false; listeners.clear(); tracker.clear()
      if (clear) { try { storage.removeItem(storageKey) } catch (_) {} }
    },
    acknowledge () {
      if (!current() || state.status !== 'succeeded') return
      state = empty(); storage.removeItem(storageKey)
      for (const listener of listeners) listener(snapshot())
    },
    run (command, params, draft = {}) {
      if (!current() || !commands.includes(command) || vpcOfferingBlocked(state)) return running || Promise.resolve(snapshot())
      const previousName = state.params.name
      const previousJobFailed = state.status === 'failed'
      publish({ status: 'checking', command, params: copy(params), draft: copy(draft), error: '', code: '' })
      running = (async () => {
        try {
          for (const name of new Set([previousName, params.name].filter(Boolean))) {
            if ((await offerings({ name })).some(row => row.name === name)) {
              publish({ status: previousJobFailed ? 'unknown' : 'rejected', failure: 'existing', error: 'message.vpc.offering.operation.existing' })
              return snapshot()
            }
          }
        } catch (_) { publish({ status: 'rejected', failure: 'preflight', error: 'message.vpc.offering.operation.preflight' }); return snapshot() }
        if (!current()) return snapshot()
        let id
        try { id = makeId() } catch (_) { publish({ status: 'rejected', failure: 'requestId', error: 'message.vpc.offering.operation.request.id' }); return snapshot() }
        if (!publish({ ...empty(), status: 'submitting', command, params: copy(params), draft: copy(draft), requestId: id })) return snapshot()
        let response
        try { response = await post(command, { ...params, customjobid: id, ignoreproject: true }, { preserveOnFailure: true, backgroundJob: true, timeout: 30000 }) } catch (error) {
          const detail = apiError(error)
          const rejected = error?.response?.status >= 400 && error?.response?.status < 500 && detail
          publish({ status: rejected ? 'rejected' : 'unknown', failure: rejected ? 'http' : 'receipt', jobId: rejected ? '' : id, code: detail?.errorcode || '', error: rejected ? detail.errortext || 'message.vpc.offering.operation.failed' : 'message.vpc.offering.operation.receipt' })
          return snapshot()
        }
        if (!current()) return snapshot()
        const receipt = response?.[command.toLowerCase() + 'response']
        if (receipt?.jobid !== id) {
          publish({ status: 'unknown', failure: 'receipt', jobId: id, error: 'message.vpc.offering.operation.receipt' })
          return snapshot()
        }
        publish({ jobId: id })
        return track(false)
      })()
      return running
    },
    recheck () {
      if (!current() || state.status !== 'unknown' || !state.jobId || state.failure === 'storage') return running || Promise.resolve(snapshot())
      return track(true)
    }
  }
}
