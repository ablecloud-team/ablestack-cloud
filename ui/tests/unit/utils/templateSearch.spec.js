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

import { templateListQuery, templateSearchQuery, templateSearchError, templateRequestParams, templateDetailLocation, templateListLocation } from '@/utils/templateSearch'
const id = '11111111-1111-4111-8111-111111111111'
const getters = { userInfo: { id: 'user', roletype: 'Admin', roleid: id }, project: {}, listAllProjects: false }
test('canonical query preserves false, combined filters, tags and page size but drops unrelated route data', () => {
  expect(templateListQuery({ name: 'exact', isready: false, forcks: 'true', userdataid: id, 'tags[0].key': 'os', 'tags[0].value': 'linux', page: '2', pagesize: '50', action: 'deleteTemplate', templatereturn: '/evil' })).toEqual({ name: 'exact', isready: 'false', forcks: 'true', userdataid: id, 'tags[0].key': 'os', 'tags[0].value': 'linux', page: '2', pagesize: '50' })
})
test.each([{ page: '-1', pagesize: '10000' }, { page: 'NaN', pagesize: 'NaN' }, { page: ['2'], pagesize: [] }])('bad paging is bounded %o', query => { const clean = templateListQuery(query); expect(Number(clean.page)).toBeGreaterThan(0); expect(Number(clean.pagesize)).toBeLessThanOrEqual(500) })
test('advanced replacement and basic keyword search both reset the page and old filters', () => {
  const old = { name: 'old', isready: 'false', forcks: 'true', page: '3', pagesize: '50', filter: 'all', templatefilter: 'all', 'tags[0].key': 'old' }
  expect(templateSearchQuery(old, { isready: false, hypervisor: 'KVM' }, 50)).toEqual({ filter: 'all', templatefilter: 'all', isready: 'false', hypervisor: 'KVM', page: '1', pagesize: '50' })
  const basic = templateSearchQuery(old, { searchQuery: 'partial' }, 20); expect(basic.keyword).toBe('partial'); expect(basic.q).toBe('partial'); expect(basic).not.toHaveProperty('name'); expect(basic).not.toHaveProperty('isready')
})
test('UUID and exact boolean filters are validated before API; unsupported is explicit', () => {
  const supported = { isready: {}, forcks: {}, userdataid: {}, hypervisor: {} }
  expect(templateSearchError({ isready: 'false', forcks: 'true', userdataid: id }, supported)).toBe('')
  expect(templateSearchError({ isready: 'no' }, supported)).toBe('message.template.search.invalid')
  expect(templateSearchError({ userdataid: 'name-is-not-uuid' }, supported)).toBe('message.template.search.invalid')
  expect(templateSearchError({ forcks: 'false' }, {})).toBe('message.template.search.unsupported')
})
test('detail reads never inherit list filters/page or return metadata', () => {
  expect(templateRequestParams({ id, name: 'restrictive', isready: false, forcks: true, templatereturn: '{}', templateowner: 'owner', page: 3 }, true)).toEqual({ id, templatefilter: 'all' })
  expect(templateRequestParams({ templatefilter: 'all', isready: false, q: 'search', keyword: 'search', filter: 'all' })).toMatchObject({ templatefilter: 'all', isready: 'false', keyword: 'search', listall: true }); expect(templateRequestParams({ q: 'x' })).not.toHaveProperty('q')
})
test('detail comments and breadcrumb retain only validated list context for same owner', () => {
  const location = templateDetailLocation({ query: { keyword: 'linux', page: '2', pagesize: '50', isready: 'true', evil: '/external' } }, { id }, getters, 'comments')
  expect(location.path).toBe('/template/' + id); expect(location.query.tab).toBe('comments')
  const back = templateListLocation({ query: location.query }, getters); expect(back).toEqual({ path: '/template', query: { keyword: 'linux', isready: 'true', page: '2', pagesize: '50' } })
  expect(templateListLocation({ query: location.query }, { ...getters, project: { id } })).toEqual({ path: '/template' })
  expect(templateListLocation({ query: location.query }, { ...getters, userInfo: { id: 'other' } })).toEqual({ path: '/template' })
})
test.each(['https://evil.example', '[1]', 'null', '{bad'])('malformed return cannot navigate elsewhere: %s', templatereturn => {
  const location = templateDetailLocation({ query: {} }, { id }, getters); location.query.templatereturn = templatereturn; expect(templateListLocation(location, getters)).toEqual({ path: '/template' })
})
