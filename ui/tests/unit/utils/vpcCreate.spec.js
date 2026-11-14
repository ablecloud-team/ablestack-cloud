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

import { readVpcCreateChoices, readVpcCreateAsNumbers, vpcCreateZoneAllowed, vpcCreateOfferingReason, vpcCreateFields, resetVpcCreateFields, buildVpcCreateParams, vpcCreateOfferingFingerprint } from '@/utils/vpcCreate'
import { fixture, copy, zone, offering, owner, getters, response } from '../../fixtures/vpcCreate'
const draft = () => ({ name: ' new-vpc ', cidr: '10.9.0.0/24', start: false, keepmacaddressonpublicnic: true, dns1: '8.8.8.8', ip6dns1: '2001:4860:4860::8888', asnumber: 64512, cidrsize: 28, publicmtu: 1550, bgppeerids: 'not-a-choice', fordisplay: false, arbitrary: 'never sent' })

test.each([{ networktype: 'Basic' }, { securitygroupsenabled: true }, { id: '' }])('rejects ineligible Zone %j', change => {
  expect(vpcCreateZoneAllowed({ ...zone, ...change }, getters())).toBe(false)
})
test('disabled Zone is available only to root admin, as permitted by the server', () => {
  const z = { ...zone, allocationstate: 'Disabled' }; expect(vpcCreateZoneAllowed(z, getters())).toBe(true); expect(vpcCreateZoneAllowed(z, getters('User'))).toBe(false)
})
test.each([[{ state: 'Disabled' }, 'disabled'], [{ fornsx: true }, 'nsx'], [{ zoneid: 'foreign' }, 'zone'], [{ networkmode: 'OTHER' }, 'mode'], [{ internetprotocol: 'IPv6Only' }, 'protocol'], [{ service: [] }, 'invalid']])('reports the actual offering exclusion %j', (change, reason) => {
  expect(vpcCreateOfferingReason({ ...offering, ...change }, zone, getters())).toBe(reason)
})
test('routed and AS requirements are independent of a visible offering label', () => {
  const o = { ...offering, networkmode: 'ROUTED', routingmode: 'Dynamic', specifyasnumber: true }
  expect(vpcCreateOfferingReason(o, { ...zone, routedmodeenabled: false }, getters())).toBe('routed')
  const g = getters(); delete g.apis.listASNumbers; expect(vpcCreateOfferingReason(o, zone, g)).toBe('as.permission')
})
test('empty response and empty array are valid empty candidates; malformed pages fail closed', async () => {
  for (const body of [{}, { count: 0 }, { count: 0, vpcoffering: [] }]) {
    const result = await readVpcCreateChoices(jest.fn().mockResolvedValue({ listvpcofferingsresponse: body }), getters(), owner, zone)
    expect(result).toMatchObject({ rows: [], reason: 'empty' })
  }
  for (const body of [{ count: 1 }, { count: 0, vpcoffering: null }, { count: 1, vpcoffering: {} }]) {
    await expect(readVpcCreateChoices(jest.fn().mockResolvedValue({ listvpcofferingsresponse: body }), getters(), owner, zone)).rejects.toThrow()
  }
})
test('all local exclusions produce incompatible, while missing permissions fail before lookup', async () => {
  const data = fixture(); data.offerings = [{ ...offering, state: 'Disabled' }]
  const get = jest.fn((a, p) => response(data, a, p)); expect((await readVpcCreateChoices(get, getters(), owner, zone)).reason).toBe('incompatible')
  const g = getters(); delete g.apis.listVPCOfferings; get.mockClear(); await expect(readVpcCreateChoices(get, g, owner, zone)).rejects.toThrow('permission'); expect(get).not.toHaveBeenCalled()
})
test('reads candidates in the target owner domain and caches only one request worth of provider queries', async () => {
  const data = fixture(); const get = jest.fn((a, p) => response(data, a, p))
  const result = await readVpcCreateChoices(get, getters(), { projectid: 'p', domainid: 'project-domain' }, zone)
  expect(get.mock.calls.find(([a]) => a === 'listVPCOfferings')[1]).toMatchObject({ domainid: 'project-domain', zoneid: 'z', state: 'Enabled', ignoreproject: true })
  expect(result.rows.every(r => !r.reason && r.diagnostic.status === 'ready')).toBe(true)
  expect(get.mock.calls.filter(([a]) => a === 'listPhysicalNetworks')).toHaveLength(1)
  expect(get.mock.calls.filter(([a]) => a === 'listNetworkServiceProviders')).toHaveLength(1)
  await readVpcCreateChoices(get, getters(), owner, zone); expect(get.mock.calls.filter(([a]) => a === 'listNetworkServiceProviders')).toHaveLength(2)
})
test.each(['Disabled', 'missing-service', 'missing-element'])('known provider failure blocks candidates: %s', async change => {
  const data = fixture(); if (change === 'Disabled') data.providers[0].state = change
  if (change === 'missing-service') data.providers[0].servicelist = []
  if (change === 'missing-element') data.elements = []
  const result = await readVpcCreateChoices((a, p) => response(data, a, p), getters(), owner, zone)
  expect(result.reason).toBe('incompatible'); expect(result.rows.every(r => r.reason === 'provider')).toBe(true)
})
test('restricted diagnostic permissions remain explicit unknown without blocking ordinary users', async () => {
  const data = fixture(); const get = jest.fn((a, p) => response(data, a, p)); const g = getters('User'); delete g.apis.listPhysicalNetworks
  const result = await readVpcCreateChoices(get, g, owner, zone)
  expect(result.rows[0].diagnostic).toMatchObject({ status: 'unknown', blocked: false }); expect(result.reason).toBe('')
  expect(get.mock.calls.some(([a]) => a === 'listPhysicalNetworks')).toBe(false)
})
test('No-DNS and IPv4 transitions remove hidden values and the payload independently omits them', () => {
  const f = draft(); const noDns = { ...offering, service: offering.service.filter(s => s.name !== 'Dns') }
  const p = buildVpcCreateParams(f, owner, zone, noDns, getters())
  expect(p).toEqual({ name: 'new-vpc', zoneid: 'z', vpcofferingid: 'o', account: 'admin', domainid: 'd', start: false, ignoreproject: true, cidr: '10.9.0.0/24', keepmacaddressonpublicnic: true })
  resetVpcCreateFields(f, offering, noDns, zone, getters()); for (const key of ['dns1', 'ip6dns1', 'cidrsize', 'asnumber', 'publicmtu']) expect(f).not.toHaveProperty(key)
  expect(p).not.toHaveProperty('bgppeerids'); expect(p).not.toHaveProperty('fordisplay'); expect(p).not.toHaveProperty('arbitrary')
})
test('Zone and owner resets remove conditional values but keep names and descriptions', () => {
  const f = { ...draft(), displaytext: 'description' }; resetVpcCreateFields(f, offering, null, zone, getters(), true)
  expect(f.name).toBe(' new-vpc '); expect(f.displaytext).toBe('description'); for (const key of ['cidr', 'dns1', 'ip6dns1', 'cidrsize', 'asnumber', 'publicmtu']) expect(f).not.toHaveProperty(key)
})
test('address mode changes clear the old CIDR pair; routed users send only CIDR size', () => {
  const o = { ...offering, networkmode: 'ROUTED' }; const f = draft(); resetVpcCreateFields(f, offering, o, zone, getters('User')); expect(f.cidr).toBeUndefined(); expect(f.cidrsize).toBeUndefined()
  f.cidr = 'stale'; f.cidrsize = 28; const p = buildVpcCreateParams(f, { projectid: 'p', domainid: 'd' }, zone, o, getters('User'))
  expect(p).toMatchObject({ projectid: 'p', cidrsize: 28 }); for (const key of ['account', 'domainid', 'cidr', 'keepmacaddressonpublicnic']) expect(p).not.toHaveProperty(key)
})
test('root routed creation cannot accidentally send both CIDR and size', () => {
  expect(() => buildVpcCreateParams(draft(), owner, zone, { ...offering, networkmode: 'ROUTED' }, getters())).toThrow('cidr.choice')
})
test('NSX resolver suppresses explicit DNS and external Source NAT input; disabling it clears its old flag', () => {
  const o = { ...offering, fornsx: true, service: [{ name: 'Dns', provider: [{ name: 'Nsx' }] }, { name: 'SourceNat', provider: [{ name: 'Nsx' }] }] }; const z = { ...zone, isnsxenabled: true }; const f = { ...draft(), userouteripresolver: true, sourcenatipaddress: '10.1.1.1' }
  const p = buildVpcCreateParams(f, owner, z, o, getters()); expect(p.userouteripresolver).toBe(true); expect(p).not.toHaveProperty('dns1'); expect(p).not.toHaveProperty('sourcenatipaddress')
  resetVpcCreateFields(f, o, offering, zone, getters()); expect(f).not.toHaveProperty('userouteripresolver')
})
test('DualStack DNS remains conditional while external and dynamic environments require an explicit review', () => {
  const o = { ...offering, internetprotocol: 'DualStack' }; expect(vpcCreateFields(o, zone, getters()).advanced).toBe(true)
  expect(buildVpcCreateParams(draft(), owner, zone, o, getters()).ip6dns1).toBe('2001:4860:4860::8888')
})
test.each([67, 1501, 1000.5])('rejects out of range or non-integer MTU %s', mtu => {
  expect(() => buildVpcCreateParams({ ...draft(), publicmtu: mtu }, owner, { ...zone, allowuserspecifyvrmtu: true }, offering, getters())).toThrow('mtu')
})
test('dynamic AS values are actual AS numbers, not indexes, and must remain free in the selected Zone', async () => {
  const data = fixture(); data.asNumbers = [{ id: 'a', asnumber: 64512, zoneid: 'z', allocationstate: 'Free' }, { id: 'b', asnumber: 64513, zoneid: 'other' }, { id: 'c', asnumber: 64514, allocationstate: 'Allocated' }, { id: 'd', asnumber: 64515, allocated: 'date' }, { id: 'e', asnumber: 64516, vpcid: 'v' }]
  const rows = await readVpcCreateAsNumbers((a, p) => response(data, a, p), 'z'); expect(rows.map(r => r.asnumber)).toEqual([64512])
  const o = { ...offering, routingmode: 'Dynamic', specifyasnumber: true }
  expect(buildVpcCreateParams(draft(), owner, zone, o, getters(), rows).asnumber).toBe(64512)
  expect(() => buildVpcCreateParams({ ...draft(), asnumber: 0 }, owner, zone, o, getters(), rows)).toThrow('as.empty')
})
test('service ordering does not create a false changed offering, but provider or state changes do', () => {
  const o = copy(offering); o.service.reverse(); expect(vpcCreateOfferingFingerprint(o)).toBe(vpcCreateOfferingFingerprint(offering))
  o.service[0].provider[0].name = 'Netris'; expect(vpcCreateOfferingFingerprint(o)).not.toBe(vpcCreateOfferingFingerprint(offering))
})
