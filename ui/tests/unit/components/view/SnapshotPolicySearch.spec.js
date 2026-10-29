// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Search from '@/components/view/SnapshotPolicySearch'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const domain = '11111111-1111-4111-8111-111111111111'
let store; let route; let wrappers
function render () {
  const w = mount(Search, { global: { mocks: { $store: store, $route: route, $t: key => key }, stubs: { APopover: { template: '<div><slot name="content"/><slot/></div>' }, VolumeSearchSelect: { props: ['scope', 'value'], template: '<div>volume picker</div>' } } } })
  wrappers.push(w); return w
}
beforeEach(() => {
  wrappers = []; store = reactive({ getters: { userInfo: { id: 'root', roletype: 'Admin' }, apis: { listVolumes: {}, listDomains: {}, listAccounts: {} }, project: {}, listAllProjects: false }, state: { user: { token: 'session' } } }); route = reactive({ path: '/snapshotpolicy', query: {} })
  getAPI.mockReset(); getAPI.mockImplementation(async cmd => cmd === 'listDomains' ? { listdomainsresponse: { domain: [{ id: domain, name: 'domain', path: 'ROOT/domain' }], count: 1 } } : { listaccountsresponse: { account: [], count: 0 } })
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises() })
test('root filters are scoped, account waits for domain and reset emits a complete replacement', async () => {
  const w = render(); w.vm.open(); await flushPromises(); expect(w.find('#policy-search-domainid').exists()).toBe(true); expect(w.find('#policy-search-fordisplay').exists()).toBe(true); expect(getAPI).toHaveBeenCalledTimes(1)
  w.vm.form.domainid = domain; w.vm.form.volumeid = 'old'; w.vm.change('domainid'); await flushPromises(); expect(w.vm.form.volumeid).toBeUndefined(); expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ domainid: domain })
  w.vm.form.account = 'owner'; w.vm.apply(); expect(w.emitted('search')[0]).toEqual([{ policySearch: { domainid: domain, account: 'owner' } }]); w.vm.reset(); expect(w.emitted('search')[1]).toEqual([{ policySearch: {} }])
})
test.each(['User', 'DomainAdmin'])('%s has only permitted filter controls', async role => {
  store.getters.userInfo.roletype = role; const w = render(); w.vm.open(); await flushPromises(); expect(w.find('#policy-search-fordisplay').exists()).toBe(false); expect(w.find('#policy-search-domainid').exists()).toBe(role === 'DomainAdmin')
})
test('project view suppresses owner controls and keeps volume lookup in that project', async () => {
  route.query.projectid = domain; const w = render(); w.vm.open(); await flushPromises(); expect(w.find('#policy-search-domainid').exists()).toBe(false); expect(w.vm.volumeScope).toEqual({ projectid: domain }); expect(getAPI).not.toHaveBeenCalled()
})
test('lookup failures block application until explicit retry succeeds', async () => {
  getAPI.mockRejectedValueOnce(new Error('503')); const w = render(); w.vm.open(); await flushPromises(); expect(w.vm.blocked).toBe(true); w.vm.apply(); expect(w.emitted('search')).toBeUndefined(); await w.vm.load(w.vm.ownerFields[0], true); expect(w.vm.blocked).toBe(false)
})
test('security transition closes the popover and rejects late owner lookup metadata', async () => {
  let release; getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve })); const w = render(); w.vm.open(); store.state.user.token = 'changed'; await flushPromises(); release({ listdomainsresponse: { count: 1, domain: [{ id: domain, name: 'old' }] } }); await flushPromises(); expect(w.vm.visible).toBe(false); expect(w.vm.candidates.domainid.rows).toEqual([])
})
test('descendant account rows never enter the selected-domain picker or stall raw pagination', async () => {
  const w = render(); w.vm.open(); await flushPromises(); w.vm.form.domainid = domain; const field = w.vm.ownerFields.find(f => f.key === 'account')
  getAPI.mockResolvedValueOnce({ listaccountsresponse: { count: 21, account: Array.from({ length: 20 }, (_, i) => ({ id: 'child-' + i, name: 'child account ' + i, domainid: 'child-domain' })) } })
  await w.vm.load(field, true); expect(w.vm.candidates.account.error).toBe(false); expect(w.vm.candidates.account.rows).toEqual([]); expect(w.vm.candidates.account.seen).toHaveLength(20)
  getAPI.mockResolvedValueOnce({ listaccountsresponse: { count: 21, account: [{ id: 'owner', name: 'owner', domainid: domain }] } })
  await w.vm.load(field, false); expect(w.vm.candidates.account.rows.map(r => r.name)).toEqual(['owner']); expect(w.vm.candidates.account.seen).toHaveLength(21); expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ domainid: domain, isrecursive: false, page: 2 })
})
