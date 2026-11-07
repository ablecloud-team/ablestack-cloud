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

import { networkSearchQuery, offeringNetworkQuery, networkListLocation, networkDetailLocation, readNetworkPage, networkPickerParams } from '@/utils/guestNetworkSearch'
test.each([true, false, 'true', 'false'])('VPC %s survives search URL and breadcrumb return', value => {
  const query = networkSearchQuery({ projectid: 'project', filter: 'shared', page: '4', sortkey: 'name' }, { forvpc: value, networkofferingid: 'offering', physicalnetworkid: 'physical', account: 'owner', domainid: 'domain', zoneid: 'zone' }, 20)
  expect(query.forvpc).toBe(String(value)); expect(query.page).toBe('1'); expect(query.projectid).toBe('project')
  expect(networkListLocation(networkDetailLocation({ query }, { id: 'network' }, 'details'))).toEqual({ path: '/guestnetwork', query })
})
test('basic search keeps scope and advanced chips; explicit reset clears filters only', () => {
  const previous = { networkofferingid: 'offering', physicalnetworkid: 'physical', forvpc: 'false', account: 'owner', domainid: 'domain', zoneid: 'zone', projectid: 'project', filter: 'all', page: '4', pagesize: '20' }
  expect(networkSearchQuery(previous, { searchQuery: 'part' }, 20)).toEqual({ ...previous, page: '1', keyword: 'part', q: 'part' })
  expect(networkSearchQuery(previous, {}, 20)).toEqual({ projectid: 'project', filter: 'all', page: '1', pagesize: '20' })
  expect(networkSearchQuery(previous, { forvpc: undefined, networkofferingid: 'new' }, 20)).not.toHaveProperty('forvpc')
})
test('offering link preserves owner/zone/project scope without carrying the offering name search', () => {
  const q = offeringNetworkQuery({ id: 'disabled' }, { query: { account: 'owner', domainid: 'domain', zoneid: 'zone', projectid: 'project', name: 'offering-name', page: '8' } })
  expect(q).toEqual({ account: 'owner', domainid: 'domain', zoneid: 'zone', projectid: 'project', networkofferingid: 'disabled', page: '1', pagesize: '20' })
  expect(offeringNetworkQuery({ id: 'id' }, {}, { project: { id: 'selected' } }).projectid).toBe('selected')
  expect(offeringNetworkQuery({ id: 'id' }, {}, { listAllProjects: true }).projectid).toBe('-1')
})
test('return link accepts known query fields only and cannot redirect to another application', () => {
  expect(networkListLocation({ query: { networkreturn: JSON.stringify({ networkofferingid: 'id', forvpc: false, redirect: 'https://example.invalid', tab: 'events' }) } })).toEqual({ path: '/guestnetwork', query: { networkofferingid: 'id', forvpc: false } })
})
test('physical-network lookup carries only the supported zone filter', () => {
  const scope = { domainid: 'domain', account: 'owner', zoneid: 'zone', projectid: 'project' }
  expect(networkPickerParams('physicalnetworkid', scope)).toEqual({ zoneid: 'zone' })
  expect(networkPickerParams('networkofferingid', scope)).toEqual({ domainid: 'domain', zoneid: 'zone' })
})
test('count comes from the server, never the length of the first page', () => {
  const rows = Array.from({ length: 20 }, (_, i) => ({ id: String(i), networkofferingid: 'offering' }))
  expect(readNetworkPage({ listnetworksresponse: { count: 80, network: rows } }, 'listNetworks', 'network', { networkofferingid: 'offering', page: 1 })).toEqual({ rows, count: 80 })
  expect(readNetworkPage({ listnetworksresponse: {} }, 'listNetworks', 'network', { page: 1 })).toEqual({ rows: [], count: 0 })
})
test.each([
  { count: 1, network: [{ id: 'id', networkofferingid: 'other' }] },
  { network: [{ id: 'id', networkofferingid: 'offering' }] },
  { count: 2, network: [{ id: 'id' }, { id: 'id' }] },
  { errorcode: 403 }, { count: -1 }, { count: 1, network: {} }
])('malformed, duplicated or wrong-offering response cannot become usage data: %j', data => {
  expect(() => readNetworkPage({ listnetworksresponse: data }, 'listNetworks', 'network', { networkofferingid: 'offering', page: 1 })).toThrow()
})

test('project scope is present on the detail URL as well as the saved list return', () => {
  for (const projectid of ['project', '-1', '0']) {
    const detail = networkDetailLocation({ query: { projectid, forvpc: 'false' } }, { id: 'network', projectid: 'project' })
    expect(detail.query.projectid).toBe(projectid); expect(networkListLocation(detail).query).toEqual({ projectid, forvpc: 'false' })
  }
  const detail = networkDetailLocation({ query: {} }, { id: 'network', projectid: 'project' })
  expect(detail.query.projectid).toBe('project'); expect(networkListLocation(detail).query.projectid).toBe('project')
})
