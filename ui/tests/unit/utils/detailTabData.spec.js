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

import { detailTabContext, detailTabDenied, readDetailTabPage, translatedSnapshotDownload } from '@/utils/detailTabData'

const page = (rows = [], count = rows.length) => ({ response: { count, row: rows } })
const args = { page: 1, pageSize: 10, resourceId: 'snapshot-A', entityType: 'SNAPSHOT' }
test('valid pages preserve every ID and report the last server page', () => {
  const rows = [{ id: 'a', entityid: 'snapshot-A', entitytype: 'SNAPSHOT' }]
  expect(readDetailTabPage(page(rows), 'response', 'row', args)).toEqual({ rows, count: 1, lastPage: 1 })
  expect(readDetailTabPage({ response: {} }, 'response', 'row', args).count).toBe(0)
  expect(readDetailTabPage(page([], 11), 'response', 'row', { ...args, page: 3 }).lastPage).toBe(2)
})
test.each([{}, { response: { count: 1 } }, page([{ id: 'a' }], 2), page([{ id: 'a' }, { id: 'a' }]), page([{ id: 'a', entityid: 'snapshot-B' }]), page([{ id: 'a', resourceid: 'snapshot-B' }]), page([{ id: 'a', entitytype: 'VM' }]), page([{}]), page([], -1), { response: { count: 0, row: {} } }])('rejects malformed, duplicate or cross-resource response %j', json => {
  expect(() => readDetailTabPage(json, 'response', 'row', args)).toThrow('message.detail.tab.incomplete')
})
test.each([401, 403, 404, 431, 432, 531])('recognizes access or missing-target error %i', code => {
  expect(detailTabDenied({ response: { data: { errorresponse: { errorcode: code } } } })).toBe(true)
})
test('transient failure is distinct from denied data', () => expect(detailTabDenied({ response: { status: 503 } })).toBe(false))
test('scope includes identity, role, token, project and permissions while ignoring presentation tab', () => {
  const vm = { resource: { id: 'a' }, $route: { path: '/snapshot/a', query: { tab: 'events' } }, $store: { getters: { apis: { listEvents: {} }, userInfo: { id: 'u', roleid: 'r' }, project: {} }, state: { user: { token: 's' } } } }
  const before = detailTabContext(vm, ['listEvents'])
  vm.$route.query.tab = 'details'; expect(detailTabContext(vm, ['listEvents'])).toBe(before)
  vm.$store.getters.userInfo.roleid = 'new'; expect(detailTabContext(vm, ['listEvents'])).not.toBe(before)
  vm.$store.getters.userInfo.roleid = 'r'; delete vm.$store.getters.apis.listEvents; expect(detailTabContext(vm, ['listEvents'])).not.toBe(before)
})
test.each(['DOWNLOADED', 'NOT_DOWNLOADED', 'DOWNLOAD_IN_PROGRESS', 'DOWNLOAD_ERROR', 'BYPASSED', 'ABANDONED', 'FUTURE'])('download state %s always has a translatable label', value => {
  expect(translatedSnapshotDownload({ copy: { downloadstate: value } })).toMatch(/^label.snapshot.copy./)
})
