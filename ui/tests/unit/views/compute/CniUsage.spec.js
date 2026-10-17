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

import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { Table } from 'ant-design-vue'
import Usage from '@/views/compute/CniUsage'
import ResourceView from '@/components/view/ResourceView'
import compute from '@/config/section/compute'
import store from '@/store'
import { cniIdentity, cniIdentityParams, cniUsageCapability, cniUsageParams, cniUsageRows } from '@/utils/cniUsage'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { apis: { listKubernetesClusters: {} }, userInfo: { roletype: 'Admin' } } }))
const id = '11111111-1111-4111-8111-111111111111'
const domainid = '22222222-2222-4222-8222-222222222222'
const clusterid = '33333333-3333-4333-8333-333333333333'
const projectid = '44444444-4444-4444-8444-444444444444'
const nodeid = '55555555-5555-4555-8555-555555555555'
const source = { id, name: 'CNI', account: 'owner', domainid }
const node = { id: nodeid, name: 'node', displayname: 'USER.LOGIN', state: 'Running', account: source.account, domainid, iscontrolnode: true }
const cluster = { id: clusterid, name: 'cluster', state: 'Stopped', account: source.account, domainid, cniconfigurationid: id, virtualmachines: [node] }
const identity = (row = source) => ({ listcniconfigurationresponse: { count: 1, cniconfig: [row] } })
const response = (rows = [cluster], count = rows.length) => ({ listkubernetesclustersresponse: { count, kubernetescluster: rows } })
let c
function setup (resource = source) {
  const x = { resource: { ...resource }, tab: 'cni.usage', resourceActionReady: () => true, $route: { path: '/cniconfiguration/' + id, query: {}, meta: { name: 'cniconfiguration' } }, $router: { push: jest.fn() }, $store: { getters: { apis: { listCniConfiguration: {}, listKubernetesClusters: { params: [{ name: 'cniconfigurationid' }] }, listVirtualMachines: {} }, userInfo: { id: 'user' }, project: {} }, state: { user: { token: 'session' } } }, $t: text => text }
  Object.assign(x, Usage.data.call(x)); Object.entries(Usage.methods).forEach(([key, fn]) => { x[key] = fn.bind(x) }); Object.entries(Usage.computed).forEach(([key, fn]) => { Object.defineProperty(x, key, { get: () => fn.call(x) }) }); return x
}
beforeEach(() => {
  jest.clearAllMocks(); c = setup()
  getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity(c.resource) : response()))
})
test('exact CNI filter and owner are bracketed by identity checks; no VM search is made', async () => {
  await c.fetchData(); expect(c.status).toBe('ready'); expect(c.count).toBe(1); expect(c.rows[0].virtualmachines[0].name).toBe('USER.LOGIN')
  expect(getAPI.mock.calls.map(x => x[0])).toEqual(['listCniConfiguration', 'listKubernetesClusters', 'listCniConfiguration'])
  expect(getAPI.mock.calls[1][1]).toEqual({ cniconfigurationid: id, account: source.account, domainid, ignoreproject: true, listall: true, page: 1, pagesize: 20 })
  expect(getAPI.mock.calls.every(x => x[2].preserveOnFailure)).toBe(true)
})
test('project resource controls usage and identity scope and navigation', async () => {
  c = setup({ ...source, account: undefined, projectid }); c.$store.getters.project = { id: projectid }
  getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity(c.resource) : response([{ ...cluster, account: undefined, projectid, virtualmachines: [{ ...node, account: undefined, projectid }] }])))
  await c.fetchData(); expect(c.status).toBe('ready'); expect(getAPI.mock.calls[1][1]).toEqual({ cniconfigurationid: id, projectid, listall: true, page: 1, pagesize: 20 })
  expect(c.resourceLink('/kubernetes', clusterid)).toEqual({ path: '/kubernetes/' + clusterid, query: { projectid } }); expect(c.resourceLink('/vm', nodeid).query).toEqual({ projectid })
})
test.each(['inactive tab', 'different route', 'different owner view', 'parent unverified', 'invalid CNI'])('%s cannot issue an unfiltered query', async type => {
  if (type === 'inactive tab') c.tab = 'details'; if (type === 'different route') c.$route.path = '/vm/' + id
  if (type === 'different owner view') c.$store.getters.project = { id: projectid }
  if (type === 'parent unverified') c.resourceActionReady = () => false
  if (type === 'invalid CNI') c.resource.domainid = ''
  await c.fetchData(); expect(getAPI).not.toHaveBeenCalled()
})
test.each([undefined, {}, [], [{ name: 'id' }]])('missing CNI filter discovery %p never falls back to an unfiltered list', async params => { c.$store.getters.apis.listKubernetesClusters.params = params; await c.fetchData(); expect(c.status).toBe('unsupported'); expect(getAPI).not.toHaveBeenCalled() })
test.each(['listCniConfiguration', 'listKubernetesClusters'])('loss of %s clears rows and expansion', async api => { await c.fetchData(); c.expand(true, cluster); delete c.$store.getters.apis[api]; getAPI.mockClear(); await c.fetchData(); expect(c.status).toBe('forbidden'); expect(c.rows).toEqual([]); expect(c.expandedKeys).toEqual([]); expect(getAPI).not.toHaveBeenCalled() })
test('node read permission controls expansion without a global node API fallback', async () => { await c.fetchData(); delete c.$store.getters.apis.listVirtualMachines; c.expand(true, cluster); expect(c.canReadNodes).toBe(false); expect(c.expandedKeys).toEqual([]) })
test.each([401, 403, 432, 531])('HTTP %s clears stale data rather than returning zero usage', async status => { await c.fetchData(); getAPI.mockRejectedValue({ response: { status } }); await c.fetchData(); expect(c.status).toBe('forbidden'); expect(c.rows).toEqual([]); expect(c.loadedScope).toBe('') })
test('initial service error is distinct from a successful empty list', async () => { getAPI.mockRejectedValue({ response: { status: 503 } }); await c.fetchData(); expect(c.status).toBe('error'); expect(c.loadedScope).toBe(''); getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity() : response([]))); await c.fetchData(); expect(c.status).toBe('ready'); expect(c.count).toBe(0) })
test.each([true, false])('same scope refresh preserves %s result and retry never sets table loading', async populated => {
  getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity() : response(populated ? [cluster] : [])))
  await c.fetchData(); const old = c.rows; let release; getAPI.mockImplementation(() => new Promise(resolve => { release = resolve })); const p = c.fetchData()
  expect(c.rows).toBe(old); expect(c.tableLoading).toBe(false); expect(c.pending).toBe(true); release(identity()); await flushPromises(); release(response(populated ? [cluster] : [])); await flushPromises(); release(identity()); await p
  getAPI.mockRejectedValue({ response: { status: 503 } }); await c.fetchData(); expect(c.status).toBe('error'); expect(c.rows).toHaveLength(populated ? 1 : 0); expect(c.tableLoading).toBe(false)
  getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity() : response(populated ? [cluster] : []))); await c.fetchData(); expect(c.status).toBe('ready')
})
test.each(['account', 'domain', 'project', 'resource', 'session', 'user', 'query', 'tab', 'unmount'])('%s change discards a delayed response', async field => {
  let release; getAPI.mockImplementation(api => api === 'listCniConfiguration' ? Promise.resolve(identity()) : new Promise(resolve => { release = resolve })); const p = c.fetchData(); await flushPromises()
  if (field === 'account') c.resource.account = 'new'; if (field === 'domain') c.resource.domainid = projectid; if (field === 'project') c.$store.getters.project = { id: projectid }
  if (field === 'resource') c.resource.id = clusterid; if (field === 'session') c.$store.state.user.token = 'next'; if (field === 'user') c.$store.getters.userInfo.id = 'next'; if (field === 'query') c.keyword = 'next'; if (field === 'tab') c.tab = 'details'; if (field === 'unmount') Usage.beforeUnmount.call(c)
  release(response()); await p; expect(c.rows).toEqual([])
})
test.each(['missing', 'mismatch'])('post-query identity %s clears rows and node details', async state => {
  await c.fetchData(); let reads = 0; getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? (++reads === 1 ? identity() : state === 'missing' ? { listcniconfigurationresponse: {} } : identity({ ...source, account: 'new' })) : response()))
  await c.fetchData(); expect(c.status).toBe(state); expect(c.rows).toEqual([])
})
test('query history retains owner and unrelated query; size changes reset page', () => {
  c.$route.query = { projectid, q: 'parent' }; c.search('한글'); expect(c.$router.push.mock.calls[0][0].query).toEqual({ projectid, q: 'parent', tab: 'cni.usage', cnikeyword: '한글', cnipage: '1' })
  c.changePage(2, 10); expect(c.$router.push.mock.calls[1][0].query).toMatchObject({ cnipage: '1', cnipagesize: '10' })
})
test('restored URL validates page, size and keyword and clamps an empty stale page', async () => {
  c.$route.query = { cnipage: '-1', cnipagesize: '999', cnikeyword: ['bad'] }; c.readQuery(); expect([c.page, c.pagesize, c.keyword]).toEqual([1, 20, ''])
  c.$route.query = { cnipage: '2', cnipagesize: '10', cnikeyword: 'cluster' }; c.readQuery(); expect([c.page, c.pagesize, c.keyword]).toEqual([2, 10, 'cluster'])
  getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity() : response([], 1))); await c.fetchData(); expect(c.$router.push.mock.calls[0][0].query.cnipage).toBe('1')
})
test('changing query or source clears row and node caches before starting a new request', async () => { await c.fetchData(); c.expand(true, cluster); getAPI.mockImplementation(() => new Promise(() => {})); c.resource.account = 'other'; Usage.watch.scope.call(c); expect(c.rows).toEqual([]); expect(c.expandedKeys).toEqual([]); expect(c.tableLoading).toBe(true) })
test('tab changes use Vue router history and invisible deep links use a visible fallback', () => {
  const x = { activeTab: '', wideLayout: true, $route: { path: '/cniconfiguration/' + id, query: { projectid }, meta: { name: 'cniconfiguration' } }, $router: { push: jest.fn(), replace: jest.fn() }, $emit: jest.fn() }
  ResourceView.methods.onTabChange.call(x, 'cni.usage'); expect(x.$router.push).toHaveBeenCalledWith({ path: x.$route.path, query: { projectid, tab: 'cni.usage' } }); expect(x.$route.query.tab).toBeUndefined()
  x.tabs = [{ name: 'details' }, { name: 'cni.usage' }]; x.showTab = t => t.name === 'details'; x.$route.query.tab = 'cni.usage'; ResourceView.methods.setActiveTab.call(x); expect(x.activeTab).toBe('details'); expect(x.$router.replace).toHaveBeenCalledWith({ path: x.$route.path, query: { projectid, tab: 'details' } })
})
test('CNI route removes the unsafe VM shortcut, adds a gated usage tab, and preserves ordinary UserData', () => {
  const routes = compute.children || compute; const cni = routes.find(r => r.name === 'cniconfiguration'); const user = routes.find(r => r.name === 'userdata')
  expect(cni.related).toBeUndefined(); expect(cni.tabs.map(t => t.name)).toEqual(['details', 'cni.content', 'cni.usage', 'comments']); expect(cni.tabs.find(t => t.name === 'cni.usage').show()).toBe(true)
  delete store.getters.apis.listKubernetesClusters; expect(cni.tabs.find(t => t.name === 'cni.usage').show()).toBe(false); store.getters.apis.listKubernetesClusters = {}
  expect(user.related[0].param).toBe('userdataid'); expect(user.tabs.some(t => t.name === 'userdata.usage.vms')).toBe(true)
})
test('owner parameters cannot be replaced by options or a global project', () => { const p = cniUsageParams(source, { cniconfigurationid: nodeid, projectid, page: 2 }); expect(p.cniconfigurationid).toBe(id); expect(p.projectid).toBeUndefined(); expect(p.id).toBeUndefined(); expect(p.page).toBe(2); expect(cniIdentityParams(source)).toMatchObject({ id, account: 'owner', domainid, ignoreproject: true }) })
test.each([{}, { ...source, id: '' }, { ...source, domainid: '' }, { ...source, account: '' }, { ...source, projectid: 'bad' }, { ...source, name: {} }])('invalid CNI identity %p cannot construct a query', value => { expect(cniIdentityParams(value)).toBe(null); expect(() => cniUsageParams(value)).toThrow() })
test.each([{}, { listcniconfigurationresponse: { cniconfig: 'bad' } }, { listcniconfigurationresponse: { count: '1', cniconfig: [source] } }])('malformed identity %p is an error', json => { expect(cniIdentity(json, source)).toBe('error') })
test('only the exact CNI identity is accepted', () => { expect(cniIdentity({ listcniconfigurationresponse: {} }, source)).toBe('missing'); expect(cniIdentity(identity({ ...source, id: nodeid }), source)).toBe('mismatch'); expect(cniIdentity(identity(), source)).toBe('ready'); expect(cniUsageCapability({})).toBe('forbidden') })
test.each([response([cluster], '1'), response([cluster], 0), response([cluster, cluster]), response([{ ...cluster, cniconfigurationid: nodeid }]), response([{ ...cluster, account: 'foreign' }]), response([{ ...cluster, projectid }]), response([{ ...cluster, domainid: nodeid }]), response([{ ...cluster, virtualmachines: [{ ...node, account: 'foreign' }] }]), response([{ ...cluster, virtualmachines: [node, node] }]), response([{ ...cluster, virtualmachines: 'bad' }]), response([{ ...cluster, id: 'bad' }]), { errorresponse: { errorcode: 531 } }])('malformed or foreign association is never published', json => { expect(() => cniUsageRows(json, source)).toThrow() })
test('valid empty usage and Created cluster without nodes are distinct', () => { expect(cniUsageRows({ listkubernetesclustersresponse: {} }, source)).toEqual({ rows: [], count: 0 }); expect(cniUsageRows(response([{ ...cluster, virtualmachines: undefined, state: 'Created' }]), source).rows[0].virtualmachines).toEqual([]) })
test('rendered cluster and node links preserve raw text, UUID targets and project scope', async () => {
  window.matchMedia = jest.fn(() => ({ matches: false, addListener: jest.fn(), removeListener: jest.fn() }))
  const raw = '<b>USER.LOGIN</b>'; const r = { ...source, projectid, account: undefined }; const row = { ...cluster, name: raw, account: undefined, projectid, virtualmachines: [{ ...node, account: undefined, projectid }] }
  getAPI.mockImplementation(api => Promise.resolve(api === 'listCniConfiguration' ? identity(r) : response([row])))
  const wrapper = mount(Usage, { props: { resource: r }, global: { mocks: { $route: c.$route, $router: c.$router, $store: c.$store, $t: x => x }, components: { ATable: Table }, stubs: { RouterLink: RouterLinkStub, Status: true, 'a-button': true, 'a-input-search': true, 'a-pagination': true, 'a-alert': true, 'reload-outlined': true } } })
  await flushPromises(); expect(wrapper.text()).toContain(raw); expect(wrapper.find('b').exists()).toBe(false)
  wrapper.vm.expand(true, row); await wrapper.vm.$nextTick(); const links = wrapper.findAllComponents(RouterLinkStub)
  expect(links.map(l => l.props('to'))).toEqual(expect.arrayContaining([{ path: '/kubernetes/' + clusterid, query: { projectid } }, { path: '/vm/' + nodeid, query: { projectid } }]))
  expect(wrapper.text()).toContain('USER.LOGIN'); wrapper.unmount()
})
