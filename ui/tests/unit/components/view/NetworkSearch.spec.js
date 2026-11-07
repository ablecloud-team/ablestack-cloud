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

import Picker from '@/components/view/NetworkSearchSelect'
import Usage from '@/views/offering/NetworkOfferingUsage'
import Search from '@/components/view/SearchView'
import Filter from '@/components/view/SearchFilter'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const id = '12345678-1234-1234-1234-123456789abc'
let store
function context (component, props = {}) {
  const c = { $store: store, $route: { path: '/networkoffering/' + id, query: {}, meta: { name: 'guestnetwork' } }, $emit: jest.fn(), $t: k => k, ...props }
  Object.assign(c, component.data.call(c)); Object.entries(component.methods).forEach(([k, fn]) => { c[k] = fn.bind(c) })
  Object.entries(component.computed || {}).forEach(([k, getter]) => Object.defineProperty(c, k, { get: () => getter.call(c), configurable: true }))
  return c
}
const offer = n => ({ id: String(n), name: 'offering-' + n, state: 'Disabled' })
const net = { id: 'network', name: 'visible-network', networkofferingid: id }
beforeEach(() => {
  jest.clearAllMocks(); store = { getters: { apis: { listNetworks: {}, listNetworkOfferings: {}, listPhysicalNetworks: {} }, userInfo: { id: 'admin', roletype: 'Admin' }, project: {}, listAllProjects: false }, state: { user: { token: 'admin-session' } } }
})
test('selected disabled offering outside page one is resolved by UUID and page two is appended', async () => {
  const c = context(Picker, { kind: 'networkofferingid', value: '21', scope: { zoneid: 'zone' } })
  getAPI.mockResolvedValueOnce({ listnetworkofferingsresponse: { count: 21, networkoffering: Array.from({ length: 20 }, (_, i) => offer(i + 1)) } }).mockResolvedValueOnce({ listnetworkofferingsresponse: { count: 1, networkoffering: [offer(21)] } }).mockResolvedValueOnce({ listnetworkofferingsresponse: { count: 21, networkoffering: [offer(21)] } })
  await c.load(true); expect(c.selected.state).toBe('Disabled'); expect(c.rows).toHaveLength(20)
  expect(getAPI.mock.calls[1][1]).toMatchObject({ id: '21', zoneid: 'zone', page: 1 })
  expect(getAPI.mock.calls[0][1]).not.toHaveProperty('state'); expect(getAPI.mock.calls[0][1]).not.toHaveProperty('isavailable')
  await c.load(false); expect(c.rows).toHaveLength(21); expect(c.total).toBe(21)
})
test('an actor change discards an older response and immediately removes its resolved name', async () => {
  const c = context(Picker, { kind: 'networkofferingid', value: undefined, scope: {} }); let old
  getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve })).mockResolvedValueOnce({ listnetworkofferingsresponse: {} })
  const pending = c.load(true); c.selected = offer('secret')
  store.getters.userInfo = { id: 'user', roletype: 'User' }; store.state.user.token = 'user-session'
  Picker.watch.context.call(c); expect(c.selected).toBeNull()
  old({ listnetworkofferingsresponse: { count: 1, networkoffering: [offer('secret')] } }); await pending; await Promise.resolve()
  expect(c.rows).toEqual([]); expect(c.options).toEqual([])
})
test('lookup failure retains the UUID filter but does not invent a label or emit another selection', async () => {
  const c = context(Picker, { kind: 'networkofferingid', value: id, scope: {} })
  getAPI.mockRejectedValueOnce(new Error('read')); await c.load(true)
  expect(c.error).toBe(true); expect(c.value).toBe(id); expect(c.selected).toBeNull(); expect(c.$emit).not.toHaveBeenCalled()
  getAPI.mockResolvedValue({ listnetworkofferingsresponse: {} }); await c.load(true); expect(c.error).toBe(false); expect(c.selected).toBeNull()
})
test('without lookup permission no physical-network request or cached label is exposed', async () => {
  store.getters.apis = { listNetworks: {} }
  const c = context(Picker, { kind: 'physicalnetworkid', value: id, scope: {} }); await c.load(true)
  expect(getAPI).not.toHaveBeenCalled(); expect(c.selected).toBeNull()
})
test('usage uses authorized server count and preserves project/zone in both tab and list links', async () => {
  store.getters.project = { id: 'project' }
  const c = context(Usage, { resource: { id }, $route: { query: { zoneid: 'zone' } } })
  getAPI.mockResolvedValue({ listnetworksresponse: { count: 80, network: [net] } }); await c.load()
  expect(c.total).toBe(80); expect(c.rows).toHaveLength(1)
  expect(getAPI.mock.calls[0][1]).toMatchObject({ networkofferingid: id, projectid: 'project', zoneid: 'zone', page: 1, pagesize: 20 })
  expect(c.location.query.projectid).toBe('project'); expect(c.location.query.networkofferingid).toBe(id)
})
test.each([401, 403, 431, 531])('usage authorization failure %s clears prior rows and counts', async code => {
  const c = context(Usage, { resource: { id } }); c.rows = [net]; c.total = 80; c.loaded = true
  getAPI.mockRejectedValue({ response: { status: code } }); await c.load()
  expect(c.rows).toEqual([]); expect(c.total).toBe(0); expect(c.loaded).toBe(false); expect(c.error).toBe(true)
})
test('same-context read failure preserves rows as stale; retry repeats the exact scoped request', async () => {
  const c = context(Usage, { resource: { id } }); c.rows = [net]; c.total = 80; c.loaded = true
  getAPI.mockRejectedValueOnce({ response: { status: 500 } }).mockResolvedValueOnce({ listnetworksresponse: { count: 1, network: [net] } })
  await c.load(); expect(c.rows).toEqual([net]); expect(c.error).toBe(true); expect(c.pagination.disabled).toBe(true)
  await c.load(); expect(c.total).toBe(1); expect(c.error).toBe(false); expect(getAPI.mock.calls[0][1]).toEqual(getAPI.mock.calls[1][1])
})
test('page changes and project changes cannot display late results from the previous scope', async () => {
  const c = context(Usage, { resource: { id } }); let old
  getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve })).mockResolvedValueOnce({ listnetworksresponse: {} })
  const pending = c.load(); store.getters.project = { id: 'other-project' }; Usage.watch.context.handler.call(c)
  expect(c.loaded).toBe(false); expect(c.rows).toEqual([])
  old({ listnetworksresponse: { count: 1, network: [net] } }); await pending; await Promise.resolve()
  expect(c.rows).toEqual([]); expect(c.total).toBe(0)
})
test('false chip is meaningful and lack of lookup access leaves only the supplied UUID', async () => {
  const c = context(Filter, { apiName: 'listNetworks', filters: [] })
  expect(c.getStaticFieldValue('forvpc', false)).toBe('label.no')
  delete store.getters.apis.listNetworkOfferings
  expect(await c.getDynamicFieldValue('networkofferingid', id)).toBe(id); expect(getAPI).not.toHaveBeenCalled()
})
test('guest search initializes false from URL and never renders an admin-only display filter for User', () => {
  const c = context(Search, { apiName: 'listNetworks', searchFilters: ['keyword', 'networkofferingid', 'forvpc'], $route: { query: { forvpc: 'false' }, meta: { name: 'guestnetwork' } } })
  c.initFields(); c.form = {}; c.fillFormFieldValues(); c.fetchStaticFieldData(['forvpc'])
  expect(c.form.forvpc).toBe('false'); expect(c.fields.find(f => f.name === 'forvpc').opts.map(o => o.id)).toEqual(['true', 'false'])
})

test('changing guest domain or zone clears the dependent owner and physical selection', () => {
  const c = context(Search, { apiName: 'listNetworks', searchFilters: [], $route: { query: {}, meta: { name: 'guestnetwork' } } })
  c.fields = [{ name: 'account' }]; c.form = { domainid: 'new', account: 'old-owner', physicalnetworkid: 'old-physical' }; c.fetchDynamicFieldData = jest.fn()
  c.onSelectFieldChange('domainid'); expect(c.form.account).toBeUndefined(); expect(c.fetchDynamicFieldData).toHaveBeenCalledWith('account')
  c.onSelectFieldChange('zoneid'); expect(c.form.physicalnetworkid).toBeUndefined()
})

test('late option completion never reapplies URL values over a new guest form selection', async () => {
  const c = context(Search, { apiName: 'listNetworks', searchFilters: ['forvpc'], $route: { query: { forvpc: 'true' }, meta: { name: 'guestnetwork' } } })
  c.form = { forvpc: 'false' }; c.fields = []; c.fillFormFieldValues = jest.fn(() => { c.form.forvpc = 'true' })
  await c.fetchDynamicFieldData([]); expect(c.form.forvpc).toBe('false'); expect(c.fillFormFieldValues).not.toHaveBeenCalled()
})

test('logout clears usage and lookup data without sending requests with a revoked session', async () => {
  store.state.user.token = ''; const picker = context(Picker, { kind: 'networkofferingid', value: id, scope: {} }); const usage = context(Usage, { resource: { id } })
  usage.rows = [net]; usage.total = 80; usage.loaded = true
  await picker.load(true); await usage.load(); expect(getAPI).not.toHaveBeenCalled(); expect(usage.rows).toEqual([]); expect(usage.total).toBe(0)
})

test.each([['fetchZones', 'listZones', 'zoneid'], ['fetchDomains', 'listDomains', 'domainid'], ['fetchAccounts', 'listAccounts', 'account']])('cancelled guest %s settles without dereferencing an absent HTTP response', async (method, api, type) => {
  store.getters.apis[api] = {}; const c = context(Search, { apiName: 'listNetworks', searchFilters: [] }); c.$route.meta.name = 'guestnetwork'; c.form = {}
  getAPI.mockRejectedValueOnce({ __CANCEL__: true }); await expect(c[method]('qa')).resolves.toEqual({ type, data: [] })
})
test.each([['fetchZones', 'listZones'], ['fetchDomains', 'listDomains'], ['fetchAccounts', 'listAccounts']])('transport failure in %s rejects the original error and does not hang', async (method, api) => {
  store.getters.apis[api] = {}; const c = context(Search, { apiName: 'listNetworks', searchFilters: [] }); c.form = {}; const error = new Error('offline')
  getAPI.mockRejectedValueOnce(error); await expect(c[method]('qa')).rejects.toBe(error)
})
test('remaining guest owner lookups stop when navigation has left the guest network view', async () => {
  store.getters.apis.listAccounts = {}; const c = context(Search, { apiName: 'listNetworks', searchFilters: [] }); c.$route.meta.name = 'networkoffering'; c.form = {}
  await expect(c.fetchAccounts('qa', true)).resolves.toEqual({ type: 'account', data: [] }); expect(getAPI).not.toHaveBeenCalled()
})
