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

import { templateAvailability, templateObservationContext, safeTemplateReason } from '@/utils/templateAvailability'
const id = '11111111-1111-4111-8111-111111111111'
const zoneA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const zoneB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const row = (state, isready = false, extra = {}) => ({ id, zoneid: zoneA, zonename: 'Zone A', isready, downloaddetails: [{ datastore: 'Store', downloadState: state }], ...extra })
test.each([
  ['DOWNLOAD_ERROR', 'failed'], ['UPLOAD_ERROR', 'failed'], ['ABANDONED', 'failed'], ['LIMIT_REACHED', 'failed'],
  ['DOWNLOAD_IN_PROGRESS', 'progress'], ['UPLOAD_IN_PROGRESS', 'progress'], ['CREATING', 'progress'],
  ['NOT_DOWNLOADED', 'queued'], ['NOT_UPLOADED', 'queued'], ['DOWNLOADED', 'verifying'], ['UPLOADED', 'verifying'], ['CREATED', 'verifying'],
  ['UNKNOWN', 'unknown'], ['BYPASSED', 'unknown'], ['FUTURE_STATE', 'unknown']
])('%s never becomes a generic false-isready pending badge', (state, key) => {
  const record = row(state); const before = JSON.stringify(record)
  expect(templateAvailability(record).key).toBe(key)
  expect(JSON.stringify(record)).toBe(before)
})
test('real failure cause is visible even when the old synthetic state says Not Ready', () => {
  expect(templateAvailability(row('DOWNLOAD_ERROR', false, { status: 'Connection refused', state: 'Not Ready' }))).toMatchObject({ key: 'failed', badge: 'error', reasons: expect.arrayContaining(['Connection refused']) })
})
test('missing progress data and generic Processing are unknown instead of fabricated transfer', () => {
  expect(templateAvailability({ id, isready: false, status: 'Processing' }).key).toBe('unknown')
  expect(templateAvailability({ id }).key).toBe('unknown')
})
test('legacy progress metadata is recognized without claiming readiness', () => {
  expect(templateAvailability({ id, isready: false, status: '64% Downloaded' }).key).toBe('progress')
  expect(templateAvailability({ id, isready: false, status: 'Installing Template' }).key).toBe('progress')
})
test('ready and datastore failure coexist without disabling a backend-ready image', () => {
  expect(templateAvailability(row('DOWNLOAD_ERROR', true))).toMatchObject({ key: 'readyWarning', availability: 'ready' })
  expect(templateAvailability(row('DOWNLOADED', true)).key).toBe('ready')
})
test('download completion alone is not image readiness', () => {
  expect(templateAvailability(row('DOWNLOADED', false)).key).toBe('verifying')
})
test('complete multi-Zone observation distinguishes partial availability and preserves causes', () => {
  const a = row('DOWNLOADED', true); const b = row('DOWNLOAD_ERROR', false, { zoneid: zoneB, status: 'HTTP 404' })
  expect(templateAvailability(a, { templateid: id, rows: [a, b], count: 2, coverage: 'complete' })).toMatchObject({ key: 'partial', readyCount: 1, zoneCount: 2, complete: true, reasons: expect.arrayContaining(['HTTP 404']) })
})
test('a partial page or duplicate Zone cannot claim a complete inventory', () => {
  const a = row('DOWNLOADED', true)
  expect(templateAvailability(a, { templateid: id, rows: [a], count: 2, coverage: 'partial' }).complete).toBe(false)
  expect(templateAvailability(a, { templateid: id, rows: [a, a], count: 2, coverage: 'complete' }).complete).toBe(false)
})
test('foreign template observations are rejected; empty complete observation is unknown', () => {
  const a = row('DOWNLOADED', true)
  expect(templateAvailability(a, { templateid: zoneB, rows: [row('DOWNLOAD_ERROR')] }).key).toBe('ready')
  expect(templateAvailability(a, { templateid: id, rows: [], count: 0, coverage: 'complete' })).toMatchObject({ key: 'unknown', complete: true, zoneCount: 0 })
})
test('diagnostics redact URL userinfo/query and explicit sensitive parameters', () => {
  const url = new URL('https://images.invalid/image.qcow2'); url.username = 'fixture-user'; url.password = String.fromCharCode(120); url.searchParams.set('token', 'fixture-value')
  const result = safeTemplateReason('Unable to download ' + url + ' sessionkey=fixture-value')
  expect(result).toContain('https://images.invalid/image.qcow2')
  expect(result).not.toContain('fixture-user'); expect(result).not.toContain('fixture-value'); expect(result).not.toContain('?')
})
test('observation context follows owner/session/role while ignoring presentation-only tabs', () => {
  const route = { path: '/template/' + id, query: { tab: 'zones' } }; const record = row('DOWNLOADED', true); const getters = { userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listTemplates: {} } }
  const first = templateObservationContext(route, record, getters, 'one')
  expect(templateObservationContext({ ...route, query: { tab: 'details' } }, record, getters, 'one')).toBe(first)
  expect(templateObservationContext(route, record, getters, 'two')).not.toBe(first)
  expect(templateObservationContext(route, record, { ...getters, project: { id: zoneB } }, 'one')).not.toBe(first)
  expect(templateObservationContext(route, record, { ...getters, userInfo: { id: 'user', roletype: 'User' } }, 'one')).not.toBe(first)
})

test.each([false, true])('duplicate datastore rows keep readiness and failure regardless of response order: %s', reverse => {
  const ready = row('DOWNLOADED', true)
  const failed = row('DOWNLOAD_ERROR', false, { status: 'Connection refused' })
  const rows = reverse ? [failed, ready] : [ready, failed]
  const result = templateAvailability(ready, { templateid: id, rows, count: 2, coverage: 'complete' })
  expect(result).toMatchObject({ key: 'readyWarning', availability: 'ready', readyCount: 1, zoneCount: 1, complete: false })
  expect(result.reasons).toContain('Connection refused')
})
