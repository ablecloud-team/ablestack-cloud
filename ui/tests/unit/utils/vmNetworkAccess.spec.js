// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { vmNetworkAccessMixin, vmNetworkAccessScope, vmNetworkRouteFamily } from '@/utils/vmNetworkAccess'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const resource = { id: 'vm', zoneid: 'zone', projectid: 'project', nic: [{ id: 'nic', networkid: 'shared', networkname: 'Shared network', isdefault: true }] }
const page = rows => ({ listnetworksresponse: { count: rows.length, network: rows } })
const consumer = autoLoad => ({
  mixins: [vmNetworkAccessMixin({ autoLoad })],
  props: ['resource', 'isStatic'],
  template: '<div><a v-if="vmNetworks.shared" href="/guestnetwork/shared">Shared network</a><span v-else>Shared network</span><span v-if="vmNetworkAccessFailed" role="status">Network lookup failed</span></div>'
})
let wrappers
beforeEach(() => { jest.clearAllMocks(); wrappers = []; getAPI.mockResolvedValue(page([{ id: 'shared' }])) })
afterEach(() => wrappers.forEach(w => w.unmount()))
function setup (autoLoad = true, globals) {
  const store = globals?.store || reactive({ getters: { project: { id: 'project' }, userInfo: { id: 'user', roleid: 'role' }, apis: { listNetworks: {} } }, state: { user: { token: 'session' } } })
  const route = globals?.route || reactive({ path: '/vm/vm', meta: { name: 'vm' } })
  const w = mount(consumer(autoLoad), { props: { resource: { ...resource } }, global: { mocks: { $store: store, $route: route } } })
  wrappers.push(w); return { w, store, route }
}
test('all NICs use one paginated current-project query without a per-network request', async () => {
  const { w } = setup(); await flushPromises()
  expect(w.find('a').exists()).toBe(true)
  expect(getAPI).toHaveBeenCalledTimes(1)
  expect(getAPI).toHaveBeenCalledWith('listNetworks', { zoneid: 'zone', projectid: 'project', listall: true, ignoreproject: true, page: 1, pagesize: 500 }, { preserveOnFailure: true })
})
test('summary and NIC consumers share an in-flight request and authoritative refresh', async () => {
  let finish; getAPI.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const first = setup(); const second = setup(false, first)
  const pending = second.w.vm.fetchVmNetworkAccess(); expect(getAPI).toHaveBeenCalledTimes(1)
  finish(page([{ id: 'shared' }])); await pending; await flushPromises()
  expect(first.w.find('a').exists()).toBe(true); expect(second.w.find('a').exists()).toBe(true)
  getAPI.mockResolvedValue(page([])); await second.w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(first.w.find('a').exists()).toBe(false); expect(second.w.find('a').exists()).toBe(false)
  expect(first.w.text()).toContain('Shared network'); expect(first.w.vm.vmNetworkAccessStatus).toBe('ready')
})
test('rechecks access without clearing the settled summary or permitting navigation', async () => {
  const { w } = setup()
  await flushPromises()
  const settled = w.vm.vmNetworkAccessState.networks
  let finish
  getAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const refresh = w.vm.fetchVmNetworkAccess()
  await flushPromises()
  expect(w.vm.vmNetworkAccessState.status).toBe('ready')
  expect(w.vm.vmNetworkAccessState.networks).toBe(settled)
  expect(w.vm.vmNetworkAccessStatus).toBe('loading')
  expect(w.find('a').exists()).toBe(false)

  finish(page([]))
  await refresh
  expect(w.vm.vmNetworkAccessState.networks).toEqual({})
  expect(w.vm.vmNetworkAccessStatus).toBe('ready')
})

test('keeps the failure notice stable during retry and clears it on success', async () => {
  const { w } = setup()
  await flushPromises()
  getAPI.mockRejectedValueOnce(new Error('offline'))
  await w.vm.fetchVmNetworkAccess()
  let finish
  getAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const retry = w.vm.fetchVmNetworkAccess()
  await flushPromises()
  expect(w.vm.vmNetworkAccessState.status).toBe('error')
  expect(w.vm.vmNetworkAccessState.networks).toEqual({})
  expect(w.vm.vmNetworks).toEqual({})
  finish(page([{ id: 'shared' }]))
  await retry
  expect(w.vm.vmNetworkAccessState.status).toBe('ready')
  expect(w.vm.vmNetworks.shared.id).toBe('shared')
})

test('a failed refresh removes old allowed links, distinguishes absence, and can retry', async () => {
  const { w } = setup(); await flushPromises()
  getAPI.mockRejectedValue(new Error('offline')); await w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(w.find('a').exists()).toBe(false); expect(w.find('[role="status"]').exists()).toBe(true)
  getAPI.mockResolvedValue(page([])); await w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(w.find('[role="status"]').exists()).toBe(false); expect(w.find('a').exists()).toBe(false)
  getAPI.mockResolvedValue(page([{ id: 'shared' }])); await w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(w.find('a').exists()).toBe(true)
})
test.each(['project', 'user', 'login', 'role', 'api', 'route', 'vm', 'unmount'])('late result cannot restore links after %s changes', async change => {
  let finish; getAPI.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const { w, store, route } = setup()
  getAPI.mockResolvedValue(page([]))
  if (change === 'project') store.getters.project = { id: 'other' }
  if (change === 'user') store.getters.userInfo.id = 'other'
  if (change === 'login') store.state.user.token = 'next'
  if (change === 'role') store.getters.userInfo.roleid = 'restricted'
  if (change === 'api') store.getters.apis = {}
  if (change === 'route') route.path = '/vm/other'
  if (change === 'vm') await w.setProps({ resource: { ...resource, id: 'other' } })
  if (change === 'unmount') { w.unmount(); wrappers = [] }
  await flushPromises(); finish(page([{ id: 'shared' }])); await flushPromises()
  if (change !== 'unmount') expect(w.find('a').exists()).toBe(false)
})
test('unmount removes cached permission results before a fresh visit', async () => {
  const first = setup(); await flushPromises(); expect(first.w.find('a').exists()).toBe(true)
  first.w.unmount(); wrappers = []
  getAPI.mockResolvedValue(page([])); const next = setup(); await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(2); expect(next.w.find('a').exists()).toBe(false)
})
test('a new VM route cannot authorize links from a previous VM resource still loading', async () => {
  const { w, route } = setup(); await flushPromises()
  expect(w.find('a').exists()).toBe(true)
  route.path = '/vm/other'; await flushPromises()
  expect(w.find('a').exists()).toBe(false)
  expect(getAPI).toHaveBeenCalledTimes(1)
})
test('all pages must succeed before any allowed link is published', async () => {
  getAPI.mockImplementation(async (api, p) => p.page === 1 ? { listnetworksresponse: { count: 2, network: [{ id: 'shared' }] } } : Promise.reject(new Error('second page failed')))
  const { w } = setup(); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(2)
  expect(w.find('a').exists()).toBe(false); expect(w.vm.vmNetworkAccessFailed).toBe(true)
})
test('static and non-VM summaries never query networks', async () => {
  const { w, route } = setup(false); route.path = '/router/id'; route.meta.name = 'router'; await flushPromises()
  await w.vm.fetchVmNetworkAccess(); expect(getAPI).not.toHaveBeenCalled()
})

test.each(['vm', 'vnfapp'])('%s requires the matching detail route, resource and explicit owner scope', family => {
  const vm = { resource, $store: { getters: { project: { id: 'project' }, userInfo: { id: 'user', roleid: 'role' }, apis: { listNetworks: {} } } }, $route: { path: '/' + family + '/vm', params: { id: 'vm' }, meta: { name: family } } }
  expect(vmNetworkRouteFamily(vm.$route)).toBe(family)
  expect(vmNetworkAccessScope(vm)).not.toBe('')
  vm.$route.params.id = 'different'; expect(vmNetworkAccessScope(vm)).toBe('')
  vm.$route.params.id = 'vm'; vm.resource = { ...resource, id: 'different' }; expect(vmNetworkAccessScope(vm)).toBe('')
  vm.resource = resource; vm.$store.getters.project.id = 'other'; expect(vmNetworkAccessScope(vm)).toBe('')
  vm.$store.getters.project = {}; expect(vmNetworkAccessScope(vm)).not.toBe('')
  vm.isStatic = true; expect(vmNetworkAccessScope(vm)).toBe('')
})

test.each(['/vm', '/vnfapp', '/vm/vm/extra', '/vnfapp/vm/extra', '/vnfapp-copy/vm', '/router/vm'])('a forged VM meta name cannot authorize %s', path => {
  expect(vmNetworkRouteFamily({ path, meta: { name: 'vm' } })).toBe('')
})

test('a conflicting family meta cannot turn a VNF route into a VM lookup scope', () => {
  expect(vmNetworkRouteFamily({ path: '/vnfapp/vm', meta: { name: 'router' } })).toBe('')
  expect(vmNetworkRouteFamily({ path: '/vnfapp/vm', meta: { name: 'vm' } })).toBe('')
})

test('VNF summary and NIC share permission revocation, failed lookup and retry without losing NIC data', async () => {
  const route = reactive({ path: '/vnfapp/vm', params: { id: 'vm' }, meta: { name: 'vnfapp' } })
  const summary = setup(true, { route }); const nics = setup(false, summary); await flushPromises()
  expect(summary.w.vm.vmNetworkAccessManaged).toBe(true)
  expect(summary.w.find('a').exists()).toBe(true); expect(nics.w.find('a').exists()).toBe(true)
  getAPI.mockResolvedValue(page([])); await nics.w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(summary.w.find('a').exists()).toBe(false); expect(nics.w.find('a').exists()).toBe(false)
  expect(nics.w.props('resource').nic).toEqual(resource.nic)
  getAPI.mockRejectedValue(new Error('forbidden')); await nics.w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(summary.w.vm.vmNetworkAccessFailed).toBe(true); expect(nics.w.vm.vmNetworkAccessFailed).toBe(true)
  getAPI.mockResolvedValue(page([{ id: 'shared' }])); await summary.w.vm.fetchVmNetworkAccess(); await flushPromises()
  expect(summary.w.find('a').exists()).toBe(true); expect(nics.w.find('a').exists()).toBe(true)
})

test.each(['project', 'role', 'api', 'login', 'route'])('an old VNF response cannot authorize links after %s changes', async change => {
  let finish; getAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const { w, store, route } = setup(true, { route: reactive({ path: '/vnfapp/vm', params: { id: 'vm' }, meta: { name: 'vnfapp' } }) })
  getAPI.mockResolvedValue(page([]))
  if (change === 'project') store.getters.project.id = 'other'
  if (change === 'role') store.getters.userInfo.roleid = 'restricted'
  if (change === 'api') store.getters.apis = {}
  if (change === 'login') store.state.user.token = 'next'
  if (change === 'route') { route.path = '/vm/vm'; route.meta.name = 'vm' }
  await flushPromises(); finish(page([{ id: 'shared' }])); await flushPromises()
  expect(w.find('a').exists()).toBe(false)
})
