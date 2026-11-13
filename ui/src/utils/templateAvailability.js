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

const failureStates = new Set(['DOWNLOAD_ERROR', 'UPLOAD_ERROR', 'ABANDONED', 'LIMIT_REACHED'])
const progressStates = new Set(['DOWNLOAD_IN_PROGRESS', 'UPLOAD_IN_PROGRESS', 'CREATING'])
const waitingStates = new Set(['NOT_DOWNLOADED', 'NOT_UPLOADED'])
const completeStates = new Set(['DOWNLOADED', 'UPLOADED', 'CREATED'])
const uuid = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i
const sameId = (a, b) => typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase()
const knownStatus = /^(Download Complete|Successfully Installed|Installing Template|Installing ISO|Bypassed Secondary Storage|Processing|\d+% Downloaded)$/i
const errorStatus = /error|fail|refused|not found|timed?\s*out|unable|denied|checksum|no route|invalid|HTTP\s*[45]\d\d/i
export function safeTemplateReason (value) {
  // API diagnostics may contain control characters; remove them before display.
  // eslint-disable-next-line no-control-regex
  return String(value || '').replace(/[\x00-\x1f\x7f]/g, ' ')
    .replace(/(?:https?|ftp):\/\/[^\s<>"']+/gi, match => {
      try { const url = new URL(match); return url.origin + url.pathname } catch (_) { return '[URL]' }
    })
    .replace(/\b(password|sessionkey|apikey|apisecret|secret|token)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]').slice(0, 500)
}
function presentation (key, availability, reasons, extra = {}) {
  const badge = { ready: 'success', readyWarning: 'warning', partial: 'warning', failed: 'error', progress: 'processing', queued: 'default', verifying: 'warning', unknown: 'default' }[key]
  const state = { ready: 'Ready', readyWarning: 'Warning', partial: 'Warning', failed: 'Error', progress: 'Creating', queued: 'Pending', verifying: 'Unknown', unknown: 'Unknown' }[key]
  return { key, availability, badge, state, labelKey: 'label.template.availability.' + key, reasons: [...new Set(reasons.filter(Boolean))], complete: false, readyCount: 0, zoneCount: 0, ...extra }
}
function rowAvailability (record = {}) {
  const stores = Array.isArray(record.downloaddetails) ? record.downloaddetails : []
  const states = stores.map(store => String(store.downloadState || '').toUpperCase())
  const raw = String(record.status || '').trim()
  const failures = stores.filter(store => failureStates.has(String(store.downloadState || '').toUpperCase()))
  const failed = failures.length > 0 || errorStatus.test(raw)
  const reasons = []
  if (raw && !knownStatus.test(raw)) reasons.push(safeTemplateReason(raw))
  failures.forEach(store => reasons.push(safeTemplateReason([store.datastore, store.downloadState].filter(Boolean).join(': '))))
  const ready = record.isready === true || record.isready === 'true'
  const unavailable = record.isready === false || record.isready === 'false'
  if (ready) return presentation(failed ? 'readyWarning' : 'ready', 'ready', reasons)
  if (failed) return presentation('failed', 'unready', reasons)
  if (states.some(state => progressStates.has(state)) || /^(Installing (Template|ISO)|\d+% Downloaded)$/i.test(raw)) return presentation('progress', 'unready', reasons)
  if (states.some(state => waitingStates.has(state))) return presentation('queued', 'unready', reasons)
  if (states.some(state => completeStates.has(state)) || /^(Download Complete|Successfully Installed)$/i.test(raw)) return presentation('verifying', unavailable ? 'unready' : 'unknown', reasons)
  return presentation('unknown', unavailable ? 'unready' : 'unknown', reasons)
}
// A representative response is never evidence that every Zone is ready.
export function templateAvailability (record = {}, observation = null) {
  const fallback = { ...rowAvailability(record), zoneName: record.zonename || '' }
  if (!observation || !sameId(record.id, observation.templateid) || !Array.isArray(observation.rows)) return fallback
  const rows = observation.rows
  if (rows.some(row => !sameId(row.id, record.id) || !uuid.test(row.zoneid || ''))) return fallback
  const zones = new Map()
  rows.forEach(row => {
    const value = rowAvailability(row); const key = row.zoneid.toLowerCase(); const previous = zones.get(key)
    if (!previous || value.availability === 'ready' || previous.availability !== 'ready') zones.set(key, value)
  })
  const values = [...zones.values()]
  const readyCount = values.filter(value => value.availability === 'ready').length
  const reasons = values.flatMap(value => value.reasons)
  const complete = observation.coverage === 'complete' && Number(observation.count) === rows.length && zones.size === rows.length
  const extra = { complete, readyCount, zoneCount: zones.size, zoneName: '' }
  if (!values.length) return complete ? presentation('unknown', 'unknown', [], extra) : fallback
  if (readyCount && readyCount < values.length) return presentation('partial', 'ready', reasons, extra)
  if (readyCount) return presentation(values.some(value => value.key === 'readyWarning') ? 'readyWarning' : 'ready', 'ready', reasons, extra)
  for (const key of ['failed', 'progress', 'queued', 'verifying', 'unknown']) {
    if (values.some(value => value.key === key)) return presentation(key, key === 'unknown' ? 'unknown' : 'unready', reasons, extra)
  }
  return fallback
}
// Runtime-only ownership/read boundary. Never render or persist this context.
export function templateObservationContext (route, record, getters = {}, session) {
  const query = { ...route?.query }; delete query.tab
  return JSON.stringify([route?.path, query, getters.userInfo?.id, getters.userInfo?.roleid, getters.userInfo?.roletype,
    getters.project?.id, session, Object.keys(getters.apis || {}).sort(), record?.id, record?.zoneid, record?.isready, record?.status, record?.downloaddetails])
}
