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

import { kubernetesIsoListQuery as query, kubernetesIsoListOwner as owner, kubernetesIsoSearchError as error, kubernetesIsoSearchQuery as search, kubernetesIsoDetailQuery as detailQuery, kubernetesIsoListLocation as back, validKubernetesMinimum, compareKubernetesMinimum } from '@/utils/kubernetesIsoSearch'
import { kubernetesIsoListResponse, kubernetesIsoReadParams } from '@/utils/kubernetesIsoModel'
const id = '11111111-1111-4111-8111-111111111111'
const fields = ['keyword', 'zoneid', 'arch', 'minimumsemanticversion', 'minimumkubernetesversionid', 'sortkey', 'sortorder']
const getters = () => ({ userInfo: { id: 'actor', roleid: 'role', roletype: 'User' }, apis: { listKubernetesSupportedVersions: { params: fields.map(name => ({ name })) } }, project: {} })
test('numeric minimum comparisons preserve patch order and server integer bounds without imposing registration minimum', () => {
  for (const v of ['1.10.0', '1.11.0', '1.29.9', '1.29.10', '1.29.11', '1.29.11.1']) expect(validKubernetesMinimum(v)).toBe(true)
  for (const v of ['1.29', 'v1.29.0', '1.30.0-rc', '2147483648.0.0']) expect(validKubernetesMinimum(v)).toBe(false)
  expect(compareKubernetesMinimum('1.29.9', '1.29.10')).toBe(-1)
  expect(compareKubernetesMinimum('1.29.10', '1.29.11')).toBe(-1)
})
test('only catalog filters and paging survive normalization, explicit owner is opaque', () => {
  const g = getters()
  const q = query({ keyword: ' partial ', zoneid: id, arch: 'aarch64', page: 2, pagesize: 10, projectid: id, isofilter: 'self', sortkey: 'semanticversion', tab: 'events' }, 20, g, 'private-session')
  expect(q).toMatchObject({ keyword: 'partial', zoneid: id, arch: 'aarch64', page: '2', pagesize: '10', sortorder: 'asc' })
  expect(q.projectid).toBeUndefined(); expect(q.isofilter).toBeUndefined(); expect(q.tab).toBeUndefined()
  expect(JSON.stringify(q)).not.toContain('private-session')
})
test.each([{ minimumsemanticversion: '1.30' }, { minimumsemanticversion: '1.30.0', minimumkubernetesversionid: id }, { minimumkubernetesversionid: 'bad' }, { zoneid: '-1' }, { sortkey: 'isourl' }, { sortkey: 'name', sortorder: 'random' }, { sortorder: 'desc' }])('invalid predicate is not silently dropped into a broader read: %j', q => expect(error(q, getters())).toBe('message.kubernetes.iso.search.invalid'))
test('schema capability does not advertise global sort or ID minimum from an unavailable description', () => {
  const g = getters(); g.apis.listKubernetesSupportedVersions.params = []
  expect(error({ minimumkubernetesversionid: id }, g)).toBe('message.kubernetes.iso.search.unsupported')
  delete g.apis.listKubernetesSupportedVersions.params
  expect(error({ sortkey: 'semanticversion' }, g)).toBe('message.kubernetes.iso.search.unsupported')
  expect(error({}, g)).toBe('')
})
test('keyword search preserves combined filters/sort while advanced mode replaces only the minimum criteria', () => {
  const g = getters(); const old = query({ keyword: 'old', minimumsemanticversion: '1.30.0', zoneid: id, sortkey: 'semanticversion', sortorder: 'desc', page: 3, pagesize: 10 }, 20, g, 'session')
  expect(search(old, { searchQuery: 'new' }, 10, g, 'session')).toMatchObject({ keyword: 'new', minimumsemanticversion: '1.30.0', zoneid: id, sortkey: 'semanticversion', page: '1' })
  const q = search(old, { minimumkubernetesversionid: id, arch: 'x86_64' }, 10, g, 'session')
  expect(q.minimumsemanticversion).toBeUndefined(); expect(q).toMatchObject({ keyword: 'old', minimumkubernetesversionid: id, arch: 'x86_64', page: '1', pagesize: '10' })
  expect(search(q, { reset: true, searchQuery: '' }, 10, g, 'session')).toEqual(query({}, 10, g, 'session'))
})
test('detail and breadcrumb restore the same combined global-sort/page state', () => {
  const g = getters(); const q = query({ keyword: 'part', minimumkubernetesversionid: id, arch: 'aarch64', zoneid: id, page: 2, pagesize: 10, sortkey: 'semanticversion', sortorder: 'desc' }, 20, g, 'session')
  const route = { query: detailQuery(q, g, 'session') }
  expect(back(route, g, 'session')).toEqual({ path: '/kubernetesiso', query: q })
  expect(kubernetesIsoReadParams(route.query, id)).toEqual({ id })
})
test.each(['account', 'role', 'project', 'session', 'permission'])('old return URL and list state cannot cross %s ownership', change => {
  const g = getters(); const original = query({ keyword: 'old', page: 3, minimumsemanticversion: '1.30.0' }, 20, g, 'old-session')
  const route = { query: detailQuery(original, g, 'old-session') }
  if (change === 'account') g.userInfo.id = 'new-account'
  if (change === 'role') g.userInfo.roleid = 'new-role'
  if (change === 'project') g.project = { id }
  if (change === 'permission') g.apis.listZones = {}
  const session = change === 'session' ? 'new-session' : 'old-session'
  expect(owner(g, session)).not.toBe(original.kisoowner)
  expect(back(route, g, session)).toEqual({ path: '/kubernetesiso' })
  expect(query(original, 20, g, session)).toEqual(query({}, 20, g, session))
})
test('detail links use one calculation of the list owner', () => {
  const g = getters()
  const readApis = jest.fn(() => g.apis)
  const source = { ...g, get apis () { return readApis() } }
  const details = detailQuery({ keyword: 'stable', page: '2' }, source, 'session')
  expect(readApis).toHaveBeenCalledTimes(1)
  expect(JSON.parse(details.kisoreturn)).toMatchObject({ kisoowner: details.kisoowner, keyword: 'stable', page: '2' })
})
test.each(['[]', 'null', 'not-json'])('malformed return state %s falls back to the catalog', value => {
  const g = getters()
  expect(back({ query: { kisoreturn: value, kisoowner: owner(g, 'session') } }, g, 'session')).toEqual({ path: '/kubernetesiso' })
})
test('empty out-of-range pages retain the known total for recovery, unlike malformed first-page responses', () => {
  const json = { listkubernetessupportedversionsresponse: { count: 11, kubernetessupportedversion: [] } }
  expect(() => kubernetesIsoListResponse(json)).toThrow()
  expect(kubernetesIsoListResponse(json, {}, '', { allowEmptyPage: true })).toMatchObject({ rows: [], count: 11, countKnown: true })
})
