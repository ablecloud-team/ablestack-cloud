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

import { mount, flushPromises } from '@vue/test-utils'
import { h, markRaw, reactive } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import networkConfig from '@/config/section/network'
import store from '@/store'
import { publicIpRuleTabs, publicIpNicAddresses } from '@/utils/publicIpRules'
import ResourceView from '@/components/view/ResourceView'
import PublicIpResource from '@/views/network/PublicIpResource'
import AutogenView from '@/views/AutogenView'
import { uniqueTabs, selectedNetworkTab, networkTabRouteKey } from '@/utils/networkTabs'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { getAPI } from '@/api'

jest.mock('@/store', () => ({ getters: { apis: {} } }))
jest.mock('@/role', () => ({ isAdmin: () => true, isAdminOrDomainAdmin: () => true }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))
jest.mock('@/components/view/InfoCard', () => ({ render: () => null }))
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const tabs = networkConfig.children.find(r => r.name === 'guestnetwork').tabs
const apis = ['listEgressFirewallRules', 'listRoutingFirewallRules', 'listBgpPeers', 'listIpv6FirewallRules', 'listPublicIpAddresses', 'listRouters', 'listVnfAppliances', 'listVlanIpRanges', 'listNetworkPermissions', 'listEvents', 'listTungstenFabricPolicy', 'listTungstenFabricLogicalRouter', 'runCustomAction', 'listCustomActions']
const base = { id: 'a', type: 'Isolated', account: 'owner', acltype: 'Account', internetprotocol: 'IPv4', service: [{ name: 'SourceNat' }] }
const admin = { roletype: 'Admin', account: 'admin' }
const visible = (record, user = admin) => tabs.filter(t => !t.show || t.show(record, {}, user)).map(t => t.name)
beforeEach(() => { store.getters.apis = Object.fromEntries(apis.map(a => [a, {}])); jest.clearAllMocks() })

test('15 unique fixed keys; Shared display label never becomes identity', () => {
  expect(tabs).toHaveLength(15)
  expect(uniqueTabs(tabs)).toHaveLength(15)
  expect(tabs.every(t => typeof t.name === 'string')).toBe(true)
  const ip = tabs.find(t => t.name === 'public.ip.addresses')
  expect(ip.label({ type: 'Shared' })).toBe('ipaddress')
  expect(ip.label(base)).toBe('public.ip.addresses')
  expect(uniqueTabs([...tabs, ip, { name: () => 'bad' }])).toHaveLength(15)
})
test.each([
  ['NAT', {}, ['egress.rules', 'public.ip.addresses'], ['bgp.peers', 'routing.firewall', 'ip.v6.firewall']],
  ['noSNAT', { service: [], specifyipranges: true }, ['egress.rules', 'public.ip.addresses', 'guest.ip.range'], ['bgp.peers', 'routing.firewall']],
  ['Static', { ip4routing: 'Static' }, ['routing.firewall'], ['bgp.peers', 'egress.rules', 'public.ip.addresses']],
  ['Dynamic', { ip4routing: 'Dynamic' }, ['bgp.peers', 'routing.firewall'], ['egress.rules', 'public.ip.addresses']],
  ['IPv6', { internetprotocol: 'IPv6' }, ['ip.v6.firewall'], ['bgp.peers']],
  ['DualStack', { internetprotocol: 'DualStack' }, ['ip.v6.firewall'], ['bgp.peers']],
  ['VPC id', { vpcid: 'vpc' }, ['details'], ['egress.rules', 'public.ip.addresses', 'ip.v6.firewall', 'network.permissions']],
  ['VPC name', { vpcname: 'tier' }, ['details'], ['egress.rules', 'public.ip.addresses', 'network.permissions']],
  ['L2', { type: 'L2' }, ['network.permissions', 'vnf.appliances', 'events', 'comments'], ['egress.rules', 'public.ip.addresses', 'routing.firewall', 'bgp.peers', 'ip.v6.firewall', 'virtual.routers']],
  ['Shared', { type: 'Shared', specifyipranges: true }, ['public.ip.addresses', 'virtual.routers', 'guest.ip.range'], ['egress.rules', 'routing.firewall', 'bgp.peers', 'ip.v6.firewall']]
])('%s tab eligibility', (label, extra, yes, no) => {
  const keys = visible({ ...base, ...extra })
  yes.forEach(key => expect(keys).toContain(key)); no.forEach(key => expect(keys).not.toContain(key))
})
test.each([undefined, null, ''])('empty VPC fields %p do not hide non-VPC tabs', value => {
  expect(visible({ ...base, vpcid: value, vpcname: value })).toContain('public.ip.addresses')
})
test('BGP is Admin + Isolated + Dynamic + API + non-VPC only', () => {
  const r = { ...base, ip4routing: 'Dynamic' }
  for (const role of ['DomainAdmin', 'User']) expect(visible(r, { roletype: role })).not.toContain('bgp.peers')
  for (const extra of [{ type: 'L2' }, { type: 'Shared' }, { vpcid: 'v' }, { vpcname: 'v' }]) expect(visible({ ...r, ...extra })).not.toContain('bgp.peers')
  delete store.getters.apis.listBgpPeers
  expect(visible(r)).not.toContain('bgp.peers')
})
test('API, owner, project and custom/provider service boundaries', () => {
  expect(visible(base, { roletype: 'User', account: 'other' })).not.toContain('egress.rules')
  expect(visible(base, { roletype: 'User', account: 'owner' })).toContain('egress.rules')
  expect(visible({ ...base, projectid: 'p' }, { roletype: 'User', account: 'other' })).toContain('egress.rules')
  expect(visible({ ...base, service: [{ name: 'CustomAction' }] })).toContain('custom.actions')
  expect(visible({ ...base, zoneid: 'zone', broadcasturi: 'tf://tf' })).toContain('network.policy')
  expect(visible({ ...base, zoneid: 'zone', broadcasturi: 'tf://tf', type: 'Shared' })).not.toContain('network.policy')
  store.getters.apis = {}
  expect(visible(base)).toEqual(['details', 'comments'])
})
test.each(['invalid', '(record)=>{window.bad=1}', ['events'], { bad: true }])('untrusted key %p falls back without evaluation', key => {
  expect(selectedNetworkTab(tabs, key).name).toBe('details')
})
test('Shared label alias maps to the fixed public IP key', () => expect(selectedNetworkTab(tabs, 'ip.addresses').name).toBe('public.ip.addresses'))

const pane = markRaw({ props: ['resource'], render () { return h('span', this.resource.id) } })
const layout = { render () { return h('div', this.$slots.right()) } }
const slot = { render () { return h('div', this.$slots.default()) } }
async function mounted (query = '') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/guestnetwork/:id', component: slot, meta: { name: 'guestnetwork' } }] })
  await router.push('/guestnetwork/a' + query)
  const getters = reactive({ userInfo: admin, apis: { listEvents: {} } })
  const rendered = [{ name: 'details', component: pane }, { name: 'events', component: pane, show: () => !!getters.apis.listEvents }, { name: 'comments', component: pane }]
  const w = mount(ResourceView, { props: { resource: { ...base }, tabs: rendered }, global: { plugins: [router], mocks: { $store: { getters, state: { app: { device: 'desktop' } } }, $t: k => k }, stubs: { ResourceLayout: layout, ACard: slot } } })
  await flushPromises()
  return { w, router, getters }
}
test('real router history keeps filters; hidden key is replaced after permission change', async () => {
  const { w, router, getters } = await mounted('?keyword=a%26b&tab=events')
  expect(w.vm.activeTab).toBe('events')
  await w.findAll('[role=tab]').find(t => t.text() === 'label.comments').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ keyword: 'a&b', tab: 'comments' })
  router.back(); await flushPromises(); expect(w.vm.activeTab).toBe('events')
  router.forward(); await flushPromises(); expect(w.vm.activeTab).toBe('comments')
  await router.push({ query: { tab: 'events', keyword: 'kept' } }); await flushPromises()
  getters.apis = {}; await flushPromises()
  expect(w.vm.activeTab).toBe('details'); expect(router.currentRoute.value.query).toEqual({ tab: 'details', keyword: 'kept' })
  expect(w.findAll('[role=tab]').map(t => t.text())).toEqual(['label.details', 'label.comments'])
  w.unmount()
})
test('deferred resource visibility does not erase a valid direct link while loading', async () => {
  const { w, router } = await mounted('?tab=events')
  await w.setProps({ resource: {}, loading: true }); await flushPromises()
  expect(router.currentRoute.value.query.tab).toBe('events')
  await w.setProps({ resource: base, loading: false }); await flushPromises()
  expect(w.vm.activeTab).toBe('events'); w.unmount()
})
test('tab-only route changes do not requery Autogen, but a real filter does', () => {
  const route = { path: '/guestnetwork/a', fullPath: '/guestnetwork/a', params: { id: 'a' }, query: {}, meta: { name: 'guestnetwork' } }
  const next = { ...route, fullPath: route.path + '?tab=events', query: { tab: 'events' } }
  const vm = { resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn() }
  expect(networkTabRouteKey(route)).toBe(networkTabRouteKey(next))
  AutogenView.watch.$route.call(vm, next, route); expect(vm.fetchData).not.toHaveBeenCalled()
  AutogenView.watch.$route.call(vm, { ...next, fullPath: next.fullPath + '&keyword=x', query: { ...next.query, keyword: 'x' } }, next)
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
})
test('late associated-network response cannot overwrite a newer Public IP network', async () => {
  let first; let second
  getAPI.mockImplementationOnce(() => new Promise(resolve => { first = resolve })).mockImplementationOnce(() => new Promise(resolve => { second = resolve }))
  const vm = { resource: { id: 'ip1', associatednetworkid: 'net1' } }
  ResourceView.methods.fetchData.call(vm)
  vm.resource = { id: 'ip2', associatednetworkid: 'net2' }; ResourceView.methods.fetchData.call(vm)
  second({ listnetworksresponse: { network: [{ id: 'net2' }] } }); await flushPromises()
  first({ listnetworksresponse: { network: [{ id: 'net1' }] } }); await flushPromises()
  expect(vm.networkService.id).toBe('net2')
})
test('late VPC filter result cannot overwrite current Public IP tabs or loading', async () => {
  let first; let second
  const vm = { tabRequestVersion: 0, resource: { id: 'ip1' }, filterTabs: jest.fn().mockImplementationOnce(() => new Promise(resolve => { first = resolve })).mockImplementationOnce(() => new Promise(resolve => { second = resolve })), fetchAction: jest.fn() }
  const a = PublicIpResource.methods.fetchData.call(vm)
  vm.resource = { id: 'ip2' }; const b = PublicIpResource.methods.fetchData.call(vm)
  second([{ name: 'events' }]); await b
  first([{ name: 'vpn' }]); await a
  expect(vm.tabs).toEqual([{ name: 'events' }]); expect(vm.loading).toBe(false); expect(vm.fetchAction).toHaveBeenCalledTimes(1)
})

test('all seven Public IP keys stay fixed and service tabs require their read API', () => {
  const ips = networkConfig.children.find(r => r.name === 'publicip').tabs
  expect(ips.map(t => t.name)).toEqual(['details', 'firewall', 'portforwarding', 'loadbalancing', 'vpn', 'events', 'comments'])
  for (const [name, api] of [['firewall', 'listFirewallRules'], ['portforwarding', 'listPortForwardingRules'], ['loadbalancing', 'listLoadBalancerRules'], ['vpn', 'listRemoteAccessVpns']]) {
    const tab = ips.find(t => t.name === name)
    expect(tab.show({ issourcenat: true })).toBe(false)
    store.getters.apis[api] = {}
    expect(tab.show({ issourcenat: true })).toBe(true)
  }
})
test('Public IP service filter and API condition are both enforced', () => {
  const tab = { show: () => false, networkServiceFilter: () => true }
  const vm = { $route: { meta: { name: 'publicip' } }, $store: { getters: { userInfo: admin } }, resource: base, networkService: { service: [{ name: 'Firewall' }] } }
  expect(ResourceView.methods.showTab.call(vm, tab)).toBe(false)
})

test('a pending same-resource tab read survives tab history changes but cannot survive resource disposal', async () => {
  let finish
  let token
  const route = reactive({ path: '/guestnetwork/a', fullPath: '/guestnetwork/a?tab=events', query: { tab: 'events' }, meta: { name: 'guestnetwork' } })
  const component = { mixins: [listRefreshMixin(['read'])], props: ['resource'], methods: { read () { token = this.listRequestToken('read'); return new Promise(resolve => { finish = resolve }) } }, render: () => h('span') }
  const w = mount(component, { props: { resource: { id: 'a' } }, global: { mocks: { $route: route } } })
  const request = w.vm.read()
  route.query.tab = 'comments'; route.fullPath = '/guestnetwork/a?tab=comments'
  expect(w.vm.isListRequestCurrent('read', token)).toBe(true)
  await w.setProps({ resource: { id: 'b' } })
  expect(w.vm.isListRequestCurrent('read', token)).toBe(false)
  finish(); await request; w.unmount()
  expect(w.vm.listRefreshDisposed).toBe(true)
})

test('resource navigation cannot normalize the destination URL using the previous record', async () => {
  const { w, router } = await mounted('?tab=comments')
  await router.push('/guestnetwork/b?tab=events'); await flushPromises()
  expect(w.vm.networkTabsReady).toBe(false)
  expect(router.currentRoute.value.query.tab).toBe('events')
  await w.setProps({ resource: { ...base, id: 'b' } }); await flushPromises()
  expect(w.vm.networkTabsReady).toBe(true); expect(w.vm.activeTab).toBe('events')
  w.unmount()
})

const ipServices = ['Firewall', 'PortForwarding', 'Lb', 'Vpn'].map(name => ({ name }))
const ipNetwork = { type: 'Isolated', service: ipServices }
const ipMetadata = ['details', 'events', 'comments']

test.each(['Free', 'Reserved', 'Releasing'])('Public IP %s only shows metadata', state => {
  expect(publicIpRuleTabs({ state }, ipNetwork)).toEqual(ipMetadata)
})

test('Public IP tabs follow service, system IP and NAT state', () => {
  const ip = { state: 'Allocated' }
  expect(publicIpRuleTabs(ip, ipNetwork)).toEqual(['details', 'firewall', 'portforwarding', 'loadbalancing', 'vpn', 'events', 'comments'])
  expect(publicIpRuleTabs({ ...ip, isstaticnat: true }, ipNetwork)).toEqual(['details', 'firewall', 'events', 'comments'])
  expect(publicIpRuleTabs({ ...ip, issystem: true }, ipNetwork)).toEqual(ipMetadata)
  expect(publicIpRuleTabs(ip, { ...ipNetwork, type: 'Shared' })).toEqual(ipMetadata)
  expect(publicIpRuleTabs(ip, { ...ipNetwork, ip4routing: 'Static' })).toEqual(ipMetadata)
})

test('VirtualRouter VPN is offered on its Source NAT address', () => {
  const provider = [{ name: 'VirtualRouter' }]
  const network = { ...ipNetwork, service: [...ipNetwork.service.filter(s => s.name !== 'Vpn'), { name: 'Vpn', provider }, { name: 'SourceNat', provider }] }
  expect(publicIpRuleTabs({ state: 'Allocated' }, network)).not.toContain('vpn')
  expect(publicIpRuleTabs({ state: 'Allocated', issourcenat: true }, network)).toContain('vpn')
})

test('non-conserve VPC suppresses competing and unknown PF/LB contexts', () => {
  const ip = { state: 'Allocated', vpcid: 'v' }
  const vpc = { service: ipServices, vpcofferingconservemode: false }
  expect(publicIpRuleTabs(ip, ipNetwork, vpc, { pf: 1, lb: 0 })).toContain('portforwarding')
  expect(publicIpRuleTabs(ip, ipNetwork, vpc, { pf: 1, lb: 0 })).not.toContain('loadbalancing')
  expect(publicIpRuleTabs(ip, ipNetwork, vpc, { pf: null, lb: 1 })).not.toContain('portforwarding')
  expect(publicIpRuleTabs({ ...ip, issourcenat: true }, null, vpc)).not.toContain('portforwarding')
  expect(publicIpRuleTabs(ip, { ...ipNetwork, networkofferingconservemode: true }, vpc, { pf: 1, lb: 1 })).toContain('loadbalancing')
})

test('VM NIC candidates only include selected network primary and secondary addresses', () => {
  const nics = [{ networkid: 'a', ipaddress: '10.1.1.2', secondaryip: [{ ipaddress: '10.1.1.3' }] },
    { networkid: 'b', ipaddress: '10.2.1.2' }]
  expect(publicIpNicAddresses(nics, 'a')).toEqual(['10.1.1.2', '10.1.1.3'])
  expect(publicIpNicAddresses(nics, 'c')).toEqual([])
})
test('current Public IP tab lookup failure reports an error and recovers to default tabs', async () => {
  const error = new Error('read failed')
  const vm = { resource: { id: 'ip' }, tabRequestVersion: 0, defaultTabs: [{ name: 'details' }], filterTabs: async () => { throw error }, $notifyError: jest.fn() }
  await PublicIpResource.methods.fetchData.call(vm)
  expect(vm.tabs).toEqual(vm.defaultTabs); expect(vm.loading).toBe(false); expect(vm.$notifyError).toHaveBeenCalledWith(error)
})
