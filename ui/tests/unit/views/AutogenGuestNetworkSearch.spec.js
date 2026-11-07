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
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { callAPI, getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '12345678-1234-1234-1234-123456789abc'
const row = { id, name: 'fixture', account: 'admin', domainid: id, networkofferingid: id }
let c
function route (query = {}, name = 'guestnetwork') {
  c.$route = { name, path: '/' + name, fullPath: '/' + name + '?' + new URLSearchParams(query), params: {}, query, meta: { name, permission: ['listNetworks'], columns: ['name', 'id', 'account', 'domain'], actions: [], searchFilters: ['id', 'name', 'domainid', 'account'] } }
}
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listNetworks: {}, listUserData: {}, listAccounts: {}, listDomains: {}, createAccount: {} }, customColumns: {}, metrics: false, listAllProjects: false }
  c = { $store: store, $router: { replace: jest.fn(), push: jest.fn() }, $refs: {}, $emit: jest.fn(), $t: k => k, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn(), error: jest.fn() }, $notifyError: jest.fn(), $nextTick: fn => fn() }
  route(); Object.assign(c, Autogen.data.call(c)); Object.entries(Autogen.methods).forEach(([k, fn]) => { c[k] = fn.bind(c) }); c.updateSelectedColumns = jest.fn(); Object.defineProperty(c, 'guestNetworkAuth', { get: () => Autogen.computed.guestNetworkAuth.call(c) })
  getAPI.mockResolvedValue({ listnetworksresponse: { count: 80, network: [row] } })
  callAPI.mockResolvedValue({ listuserdataresponse: { count: 80, userdata: [row] } })
})
test.each([false, 'false', true, 'true'])('VPC value %s reaches listNetworks with UUID filters and scope', async forvpc => {
  route({ forvpc, networkofferingid: id, physicalnetworkid: 'physical', zoneid: 'zone', domainid: 'domain', account: 'owner', page: '2', pagesize: '20', sortkey: 'name', sortorder: 'asc' })
  await c.fetchData()
  expect(getAPI).toHaveBeenCalledWith('listNetworks', expect.objectContaining({ forvpc: String(forvpc), networkofferingid: id, physicalnetworkid: 'physical', zoneid: 'zone', domainid: 'domain', account: 'owner', page: 2, pagesize: 20, listall: true }), { preserveOnFailure: true, timeout: 20000 })
  expect(getAPI.mock.calls[0][1]).not.toHaveProperty('sortkey'); expect(c.itemCount).toBe(80)
})
test('basic search retains UUID, false and owner filters; removing parent clears dependent filter', () => {
  route({ networkofferingid: id, physicalnetworkid: 'physical', forvpc: 'false', zoneid: 'zone', domainid: 'domain', account: 'owner', projectid: 'project', page: '4', pagesize: '20' })
  c.onSearch({ searchQuery: 'fixture' }); expect(c.$router.push.mock.calls[0][0].query).toMatchObject({ networkofferingid: id, forvpc: 'false', account: 'owner', projectid: 'project', keyword: 'fixture', page: '1' })
  c.removeFilter({ key: 'domainid' }); expect(c.$router.push.mock.calls[1][0].query).not.toHaveProperty('account')
  c.removeFilter({ key: 'zoneid' }); expect(c.$router.push.mock.calls[2][0].query).not.toHaveProperty('physicalnetworkid')
  c.changePage(4, 50); expect(c.$router.push.mock.calls[3][0].query.page).toBe(1)
})
test('same-query failure keeps rows stale; new query clears them before completion', async () => {
  await c.fetchData(); getAPI.mockRejectedValueOnce({ response: { status: 500 } }); await c.fetchData(); expect(c.items).toEqual([row]); expect(c.listRefreshError).toBe(true)
  let finish; getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })); route({ forvpc: 'false' }); const pending = c.fetchData(); expect(c.items).toEqual([])
  finish({ listnetworksresponse: {} }); await pending; expect(c.itemCount).toBe(0); expect(c.listRefreshError).toBe(false)
})
test('role changes reject late root response and remove prior names', async () => {
  await c.fetchData(); let finish; getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })); const pending = c.fetchData()
  store.getters.userInfo = { id: 'other', roletype: 'User' }; store.state.user.token = 'new-session'; getAPI.mockResolvedValueOnce({ listnetworksresponse: {} }); Autogen.watch.guestNetworkAuth.call(c)
  finish({ listnetworksresponse: { count: 80, network: [row] } }); await pending; await Promise.resolve(); expect(c.items).toEqual([])
})
test('wrong offering response becomes an error instead of usage success', async () => {
  route({ networkofferingid: id }); getAPI.mockResolvedValueOnce({ listnetworksresponse: { count: 1, network: [{ ...row, networkofferingid: 'other' }] } }); await c.fetchData(); expect(c.items).toEqual([]); expect(c.listRefreshError).toBe(true)
})

test('canonical guest project URLs keep the UUID and do not pass through the destructive generic switch', async () => {
  route({ projectid: id }); c.syncSnapshotProject = jest.fn().mockResolvedValue()
  await c.switchProject(id); expect(c.syncSnapshotProject).toHaveBeenCalledWith(id); expect(c.$router.replace).not.toHaveBeenCalled()
})
test('all-projects mode does not overwrite an explicit guest project UUID', async () => {
  store.getters.listAllProjects = true; route({ projectid: id }); await c.fetchData(); expect(getAPI.mock.calls[0][1].projectid).toBe(id)
})

test('logout invalidates guest rows without issuing a new unauthorized read', () => {
  c.items = [row]; c.itemCount = 80; store.state.user.token = ''; Autogen.watch.guestNetworkAuth.call(c)
  expect(c.items).toEqual([]); expect(c.itemCount).toBe(0); expect(getAPI).not.toHaveBeenCalled()
})
