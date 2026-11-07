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
import { shallowMount, flushPromises } from '@vue/test-utils'
import { getAPI, postAPI } from '@/api'
import Diagnostics from '@/components/network/NetworkOfferingDiagnostics.vue'
import Isolated from '@/views/network/CreateIsolatedNetworkForm.vue'
import L2 from '@/views/network/CreateL2NetworkForm.vue'
import Shared from '@/views/network/CreateSharedNetworkForm.vue'
import { diagnosticApis, diagnoseNetworkOffering, readDiagnosticList, vpcSupportsTierOffering } from '@/utils/networkOfferingDiagnostics'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const apis = Object.fromEntries([...diagnosticApis, 'listVirtualRouterElements'].map(name => [name, {}]))
const service = name => ({ name, provider: [{ name: 'VpcVirtualRouter' }] })
const offering = { id: 'offering', state: 'Enabled', guestiptype: 'Isolated', service: [service('Dns'), service('Firewall')] }
const physical = { id: 'physical', zoneid: 'zone', name: 'Guest', state: 'Enabled' }
const provider = { id: 'provider', name: 'VpcVirtualRouter', state: 'Enabled', servicelist: ['Dns', 'Firewall'] }
const element = { id: 'element', nspid: provider.id, enabled: true }
const response = (command, key, items, count = items.length) => ({ [command.toLowerCase() + 'response']: { count, [key]: items } })
const defer = () => { let finish; const promise = new Promise(resolve => { finish = resolve }); return { promise, resolve: finish } }
let rows
beforeEach(() => {
  rows = { listNetworkOfferings: ['networkoffering', [{ ...offering }]], listPhysicalNetworks: ['physicalnetwork', [{ ...physical }]], listTrafficTypes: ['traffictype', [{ id: 'traffic', traffictype: 'Guest' }]], listNetworkServiceProviders: ['networkserviceprovider', [{ ...provider }]], listVirtualRouterElements: ['virtualrouterelement', [{ ...element }]] }
  getAPI.mockReset().mockImplementation(command => Promise.resolve(response(command, ...rows[command])))
  postAPI.mockReset()
})
const run = (overrides = {}) => diagnoseNetworkOffering(getAPI, apis, { zoneId: 'zone', offering, ...overrides })

test('enabled provider must support every required service, including the second service', async () => {
  rows.listNetworkServiceProviders[1][0].servicelist = ['Dns']
  const result = await run()
  expect(result.blocked).toBe(true)
  expect(result.issues).toEqual([{ service: 'Firewall', provider: 'VpcVirtualRouter', reason: 'missing.service' }])
})
test('supported configuration resolves one Guest physical network and its router element', async () => {
  expect(await run()).toMatchObject({ status: 'ready', blocked: false, network: { id: 'physical' } })
  expect(postAPI).not.toHaveBeenCalled()
  expect(getAPI.mock.calls.every(([, params, options]) => params.page && params.pagesize && options.preserveOnFailure)).toBe(true)
})
test.each(['missing.provider', 'disabled.provider', 'disabled.element', 'disabled.network'])('%s blocks preflight', async reason => {
  if (reason === 'missing.provider') rows.listNetworkServiceProviders[1] = []
  if (reason === 'disabled.provider') rows.listNetworkServiceProviders[1][0].state = 'Disabled'
  if (reason === 'disabled.element') rows.listVirtualRouterElements[1][0].enabled = false
  if (reason === 'disabled.network') rows.listPhysicalNetworks[1][0].state = 'Disabled'
  const result = await run()
  expect(result.blocked).toBe(true)
  expect(result.issues.some(issue => issue.reason === reason)).toBe(true)
})
test('no permission is unknown and delegates the final decision to the server without forbidden reads', async () => {
  expect(await diagnoseNetworkOffering(getAPI, {}, { zoneId: 'zone', offering })).toMatchObject({ status: 'unknown', blocked: false })
  expect(getAPI).not.toHaveBeenCalled()
})
test('a service-free L2 offering still needs an enabled Guest network, not NAT services', async () => {
  const l2 = { ...offering, guestiptype: 'L2', service: [] }
  rows.listNetworkOfferings[1] = [l2]
  rows.listNetworkServiceProviders[1] = []
  expect(await run({ offering: l2 })).toMatchObject({ status: 'ready', checks: [] })
  expect(getAPI.mock.calls.some(([command]) => command === 'listVirtualRouterElements')).toBe(false)
})
test('explicit physical network is bounded by zone and Guest traffic', async () => {
  expect(await run({ physicalNetworkId: 'another-zone' })).toMatchObject({ blocked: true, issues: [{ reason: 'missing.network' }] })
})
test('multiple Guest networks require an unambiguous tag match', async () => {
  rows.listPhysicalNetworks[1].push({ ...physical, id: 'second' })
  expect(await run()).toMatchObject({ blocked: true, issues: [{ reason: 'ambiguous' }] })
  rows.listPhysicalNetworks[1][1].tags = ['dedicated']
  const tagged = { ...offering, tags: 'dedicated' }; rows.listNetworkOfferings[1] = [tagged]
  expect(await run({ offering: tagged })).toMatchObject({ status: 'ready', network: { id: 'second' } })
})
test.each(['disabled.offering', 'changed.offering'])('rechecks selected offering and blocks %s', async reason => {
  if (reason === 'disabled.offering') rows.listNetworkOfferings[1][0].state = 'Disabled'
  else rows.listNetworkOfferings[1][0].service = [service('Dns')]
  expect(await run()).toMatchObject({ blocked: true, issues: [{ reason }] })
})
test('automatic provider resolution is unknown rather than a false success', async () => {
  const auto = { ...offering, service: [{ name: 'Connectivity' }] }; rows.listNetworkOfferings[1] = [auto]
  expect(await run({ offering: auto })).toMatchObject({ status: 'unknown', blocked: false })
})
test('paging refuses partial or duplicated lists', async () => {
  getAPI.mockResolvedValueOnce(response('listPhysicalNetworks', 'physicalnetwork', [physical], 2)).mockResolvedValueOnce(response('listPhysicalNetworks', 'physicalnetwork', [], 2))
  await expect(readDiagnosticList(getAPI, 'listPhysicalNetworks', {}, 'physicalnetwork')).rejects.toThrow('Incomplete')
  getAPI.mockResolvedValueOnce(response('listPhysicalNetworks', 'physicalnetwork', [physical], 2)).mockResolvedValueOnce(response('listPhysicalNetworks', 'physicalnetwork', [physical], 2))
  await expect(readDiagnosticList(getAPI, 'listPhysicalNetworks', {}, 'physicalnetwork')).rejects.toThrow('changed')
})
test('component reports a read failure and recovers through explicit recheck', async () => {
  getAPI.mockRejectedValueOnce(new Error('read failed'))
  const w = shallowMount(Diagnostics, { props: { offering, zoneId: 'zone' }, global: { mocks: { $t: k => k, $store: { getters: { apis } } } } })
  await flushPromises()
  expect(w.vm.report).toMatchObject({ status: 'failed', blocked: true })
  expect(await w.vm.validate()).toBe(true)
  expect(w.vm.report.status).toBe('ready')
  w.unmount()
})
test('a late response for a prior offering cannot replace the current result', async () => {
  const slow = defer(); getAPI.mockReturnValueOnce(slow.promise)
  const w = shallowMount(Diagnostics, { props: { offering, zoneId: 'zone' }, global: { mocks: { $t: k => k, $store: { getters: { apis } } } } })
  await w.setProps({ physicalNetworkId: 'missing' }); await flushPromises()
  expect(w.vm.report.issues[0].reason).toBe('missing.network')
  slow.resolve(response('listNetworkOfferings', 'networkoffering', [offering])); await flushPromises()
  expect(w.vm.report.issues[0].reason).toBe('missing.network')
  w.unmount()
})
function sharedCreateState (vm, component) {
  if (component !== Shared) return
  Object.assign(vm, {
    form: { family: 'ipv4', gateway: '192.0.2.1', netmask: '255.255.255.0', startip: '192.0.2.10' },
    apiParams: {},
    owner: { acltype: 'domain' },
    selectedZone: { id: 'zone' },
    submitFingerprint: Shared.methods.submitFingerprint
  })
}

test.each([Isolated, L2, Shared])('$name creation refuses a failed or superseded preflight', async component => {
  const slow = defer()
  const vm = { canSubmit: true, actionLoading: false, providerCheck: { blocked: false }, formRef: { value: { validate: () => Promise.resolve() } }, form: {}, selectedZone: { id: 'zone' }, selectedNetworkOffering: offering, $refs: { offeringDiagnostics: { validate: () => slow.promise } } }
  sharedCreateState(vm, component)
  component.methods.handleSubmit.call(vm)
  await flushPromises()
  expect(vm.actionLoading).toBe(true)
  vm.selectedZone = { id: 'changed' }
  slow.resolve(true); await flushPromises()
  expect(vm.actionLoading).toBe(false)
  expect(postAPI).not.toHaveBeenCalled()
})

test.each([Isolated, L2, Shared])('$name creation sends no write when provider recheck fails', async component => {
  const vm = { canSubmit: true, actionLoading: false, providerCheck: { blocked: false }, formRef: { value: { validate: () => Promise.resolve() } }, form: {}, $refs: { offeringDiagnostics: { validate: () => Promise.resolve(false) } } }
  sharedCreateState(vm, component)
  component.methods.handleSubmit.call(vm)
  await flushPromises()
  expect(vm.actionLoading).toBe(false)
  expect(postAPI).not.toHaveBeenCalled()
})

test('Isolated creation ignores offerings returned for the previous owner', async () => {
  const slow = defer()
  const first = { id: 'old-offering', fornsx: false }
  const current = { id: 'current-offering', fornsx: false }
  getAPI.mockReset().mockReturnValueOnce(slow.promise)
    .mockResolvedValueOnce(response('listNetworkOfferings', 'networkoffering', [current]))
  const vm = {
    ...Isolated.methods,
    $store: { getters: { apis: {} } },
    ownerReady: true,
    owner: { domainid: 'old' },
    selectedZone: { id: 'zone', isnsxenabled: false },
    offeringRequest: 0,
    asNumberRequest: 0,
    compatibleVpcs: [],
    form: {}
  }

  const previous = vm.updateOfferings()
  vm.owner = { domainid: 'current' }
  await vm.updateOfferings()
  slow.resolve(response('listNetworkOfferings', 'networkoffering', [first]))
  await previous

  expect(vm.networkOfferings).toEqual([current])
  expect(vm.form.networkofferingid).toBe(current.id)
  expect(vm.selectedNetworkOffering).toBe(current)
  expect(vm.networkOfferingLoading).toBe(false)
})

test('a tier offering requires the same service and provider in the VPC', () => {
  const service = (name, provider) => ({ name, provider: [{ name: provider }] })
  const vpc = { service: [service('NetworkACL', 'VpcVirtualRouter'), service('SourceNat', 'VpcVirtualRouter')] }
  expect(vpcSupportsTierOffering(vpc, { service: [service('Firewall', 'VpcVirtualRouter')] })).toBe(false)
  expect(vpcSupportsTierOffering(vpc, { service: [service('SourceNat', 'VirtualRouter')] })).toBe(false)
  expect(vpcSupportsTierOffering(vpc, { service: [service('SourceNat', 'VpcVirtualRouter'), service('Gateway', 'VpcVirtualRouter')] })).toBe(true)
})
