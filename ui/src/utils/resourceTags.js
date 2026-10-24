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
import md5 from 'md5'
import { createJobTracker } from '@/utils/jobTracker'

const prefix = 'resource.tags.v1:'
const copy = value => JSON.parse(JSON.stringify(value))
const operations = new Map()
let activeIdentity

export const tagOperationBlocked = state => ['preparing', 'submitting', 'pending', 'retrying', 'unknown'].includes(state?.status)
export function tagIdentity (store) {
  const user = store.getters.userInfo || {}
  return JSON.stringify([store.getters.server, user.id, user.accountid, user.account, user.domainid, user.roletype, store.state?.user?.token])
}
export function tagContext (store, resource, type) {
  return JSON.stringify([tagIdentity(store), store.getters.project?.id || '', !!store.getters.allProjects,
    type, resource?.id, resource?.account, resource?.domainid, resource?.projectid || ''])
}
export function tagTargetParams (resource, type) {
  const projectId = type === 'Project' ? resource.id : resource.projectid
  return { resourcetype: type, ...(projectId ? { projectid: projectId } : { ignoreproject: true }) }
}
export function tagApiError (error) {
  return Object.values(error?.response?.data || {}).find(value => value?.errorcode)?.errortext || ''
}
export function tagAccessError (error) {
  return [401, 403].includes(error?.response?.status) || /permission|access denied|not authorized|session expired/i.test(tagApiError(error))
}
export async function readResourceTags (get, resource, type, current = () => true) {
  const all = []; const seen = new Set(); let total
  for (let page = 1; page <= 100; page++) {
    if (!current()) throw new Error('message.tags.scope.changed')
    const data = (await get('listTags', { ...tagTargetParams(resource, type), resourceid: resource.id, listall: true, page, pagesize: 100 }, { preserveOnFailure: true, timeout: 15000 })).listtagsresponse
    if (!current()) throw new Error('message.tags.scope.changed')
    const rows = data?.tag || []
    const count = Number(data?.count ?? (rows.length ? NaN : 0))
    if (!data || !Array.isArray(rows) || !Number.isInteger(count) || count < 0 || (total !== undefined && total !== count)) throw new Error('message.tags.read.incomplete')
    total = count
    for (const row of rows) {
      const key = JSON.stringify([row.key, row.value])
      if (row.resourceid !== resource.id || row.resourcetype !== type || typeof row.key !== 'string' || typeof row.value !== 'string' || seen.has(key)) throw new Error('message.tags.read.incomplete')
      seen.add(key); all.push(row)
    }
    if (all.length === total) return all
    if (!rows.length || all.length > total) break
  }
  throw new Error('message.tags.read.incomplete')
}

export function clearResourceTagOperations (storage) {
  for (const op of operations.values()) op.invalidate()
  operations.clear(); activeIdentity = undefined
  if (storage) {
    for (const key of Object.keys(storage)) if (key.startsWith(prefix)) storage.removeItem(key)
  }
}

// Only receipts are resumed. Unknown requests are never submitted automatically.
// The session storage key hashes the security identity; no token is stored in a receipt.
export function getResourceTagOperation ({ identity, context, getIdentity, get, post, storage, interval = 1500, maxRetries = 2, maxDuration = 120000 }) {
  const identityKey = md5(identity)
  try {
    if ((activeIdentity && activeIdentity !== identity) || (storage?.getItem(prefix + 'identity') && storage.getItem(prefix + 'identity') !== identityKey)) clearResourceTagOperations(storage)
    if (storage) storage.setItem(prefix + 'identity', identityKey)
  } catch (_) { /* A write is checked again before any mutation is submitted. */ }
  activeIdentity = identity
  const key = prefix + identityKey + ':' + md5(context)
  if (operations.has(key)) return operations.get(key)
  let valid = true; let paused = true; let running; let trackingToken = 0
  const bindings = new Map()
  let state = { status: 'idle', command: '', params: {}, jobId: '', error: '', reason: '', sequence: 0 }
  try {
    const previous = JSON.parse(storage?.getItem(key) || 'null')
    if (previous?.version === 1 && ['createTags', 'deleteTags'].includes(previous.state?.command)) {
      state = previous.state
      if (tagOperationBlocked(state)) state = { ...state, status: 'unknown', reason: 'restored' }
    }
  } catch (_) { state = { ...state, status: 'unknown', reason: 'storage', error: 'message.tags.storage.failed' } }
  const listeners = new Set()
  const alive = () => valid && getIdentity() === identity
  const current = () => alive() && !paused && [...bindings.values()].some(check => check())
  const snapshot = () => copy(state)
  const publish = patch => {
    if (!alive()) return false
    state = { ...state, ...patch }
    let saved = true
    try { if (storage) storage.setItem(key, JSON.stringify({ version: 1, state })) } catch (_) { saved = false }
    if (current()) for (const listener of listeners) { try { listener(snapshot()) } catch (_) { console.error('[resourceTags] observer failed') } }
    return saved
  }
  const receive = (id, result, meta) => {
    if (!current() || id !== state.jobId || meta?.token !== trackingToken) return
    if (result.jobstatus === 1) publish({ status: 'succeeded', error: '', reason: '' })
    else if (result.jobstatus === 2) publish({ status: 'failed', error: result.jobresult?.errortext || 'message.tags.job.failed', reason: 'job' })
    else if (result.trackingStatus === 'retrying') publish({ status: 'retrying' })
    else if (result.trackingStatus) publish({ status: 'unknown', error: tagApiError(result.error), reason: result.trackingStatus })
    else publish({ status: 'pending' })
  }
  const tracker = createJobTracker({
    interval,
    maxRetries,
    maxDuration,
    onState: receive,
    query: async jobId => {
      if (!current()) throw Object.assign(new Error('scope'), { response: { status: 403 } })
      return (await get('queryAsyncJobResult', { jobid: jobId }, { backgroundJob: true, preserveOnFailure: true, timeout: 15000 })).queryasyncjobresultresponse
    }
  })
  const track = () => {
    if (!current() || !state.jobId) return Promise.resolve(snapshot())
    publish({ status: 'pending', error: '', reason: '' })
    const id = state.jobId; const token = ++trackingToken
    running = tracker.track(id, { token }, true).then(result => { receive(id, result, { token }); return snapshot() })
    return running
  }
  const op = {
    snapshot,
    bind (check) {
      const id = Symbol('view')
      bindings.set(id, check); paused = false
      return () => { bindings.delete(id); if (![...bindings.values()].some(check => check())) op.pause() }
    },
    subscribe (listener) { listeners.add(listener); listener(snapshot()); return () => listeners.delete(listener) },
    pause () {
      paused = true
      trackingToken++
      tracker.clear()
      if (state.status === 'preparing') publish({ status: 'failed', reason: 'prepare', error: 'message.tags.scope.changed' })
      else if (tagOperationBlocked(state)) publish({ status: 'unknown', reason: 'scope' })
    },
    invalidate () { valid = false; paused = true; trackingToken++; tracker.clear(); listeners.clear(); bindings.clear() },
    resume () {
      if (['pending', 'retrying'].includes(state.status)) return running || Promise.resolve(snapshot())
      return state.status === 'unknown' ? track() : Promise.resolve(snapshot())
    },
    run (command, params, prepare = async () => {}) {
      if (!current() || tagOperationBlocked(state) || !['createTags', 'deleteTags'].includes(command)) return running || Promise.resolve(snapshot())
      const frozen = copy(params)
      if (!publish({ status: 'preparing', command, params: frozen, jobId: '', error: '', reason: '', sequence: state.sequence + 1 })) {
        publish({ status: 'failed', error: 'message.tags.storage.failed', reason: 'storage' })
        return Promise.resolve(snapshot())
      }
      running = (async () => {
        try { await prepare() } catch (error) {
          if (current()) publish({ status: 'failed', error: tagApiError(error) || error.message || 'message.tags.read.failed', reason: 'prepare' })
          return snapshot()
        }
        if (!current()) return snapshot()
        if (!publish({ status: 'submitting' })) {
          publish({ status: 'failed', error: 'message.tags.storage.failed', reason: 'storage' })
          return snapshot()
        }
        let result
        try {
          result = (await post(command, copy(frozen), { preserveOnFailure: true, backgroundJob: true, timeout: 30000 }))?.[command.toLowerCase() + 'response']
        } catch (error) {
          const message = tagApiError(error)
          const rejected = message && ![502, 503, 504].includes(error?.response?.status)
          publish({ status: rejected ? 'failed' : 'unknown', error: message, reason: rejected ? 'api' : 'submission' })
          return snapshot()
        }
        if (!alive()) return snapshot()
        if (result?.jobid) {
          publish({ jobId: String(result.jobid), status: current() ? 'pending' : 'unknown', reason: current() ? '' : 'scope' })
          return current() ? track() : snapshot()
        }
        publish(result?.success === true
          ? { status: 'succeeded', error: '', reason: '' }
          : { status: 'unknown', error: '', reason: 'submission' })
        return snapshot()
      })()
      return running
    }
  }
  operations.set(key, op)
  return op
}
