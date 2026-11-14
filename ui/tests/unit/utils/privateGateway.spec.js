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

import { gatewayIp, gatewayMask, gatewayInCidr, gatewayNetworks, gatewayAcls, gatewayErrors, gatewayParams, validGatewayVlan } from '@/utils/privateGateway'
const resource = { id: 'vpc', account: 'owner', domainid: 'domain', zoneid: 'zone' }
const draft = { mode: 'vlan', physicalnetwork: 'physical', vlan: '123', ipaddress: '10.1.1.2', gateway: '10.1.1.1', netmask: '255.255.255.0', acl: 'deny', nat: false, bypass: false }
const state = { physical: [{ id: 'physical', zoneid: 'zone' }], networks: [], acls: [{ id: 'deny', name: 'default_deny' }], gateways: [] }
test.each(['999.18.31.2', '10.1.2', '10.-1.2.3', '1.2.3.4.5', '::1', 'abc'])('rejects malformed IPv4 %s', value => expect(gatewayIp(value)).toBeNull())
test.each(['255.0.255.0', '0.0.0.0', '255.255.255.256'])('rejects invalid mask %s', value => expect(gatewayMask(value)).toBe(false))
test.each(['255.255.255.0', '255.255.255.255', '128.0.0.0'])('retains API valid mask %s', value => expect(gatewayMask(value)).toBe(true))
test.each(['123', 'vlan://123', 'untagged', 'lswitch://network-identifier', '9999'])('retains accepted VLAN/URI %s without imposing an unproved range restriction', value => expect(validGatewayVlan(value)).toBe(true))
test.each(['', '123-456', 'vxlan://123', 'bad value'])('rejects unsupported connection format %s', value => expect(validGatewayVlan(value)).toBe(false))
test('candidate network owner, project, Zone, type and removed state are filtered independently', () => {
  const good = { ...resource, id: 'network', type: 'L2', state: 'Setup' }
  const rows = [good, { ...good, id: 'foreign', account: 'other' }, { ...good, id: 'project', projectid: 'other' }, { ...good, id: 'zone', zoneid: 'other' }, { ...good, id: 'shared', type: 'Shared' }, { ...good, id: 'destroyed', state: 'Destroyed' }]
  expect(gatewayNetworks(rows, resource)).toEqual([good])
  expect(gatewayNetworks(rows, { ...resource, projectid: 'other' })).toEqual([rows[2]])
})
test('only default ACLs and this VPC ACLs are candidates', () => {
  const rows = [{ id: 'deny', name: 'default_deny' }, { id: 'allow', name: 'default_allow' }, { id: 'own', vpcid: 'vpc' }, { id: 'foreign', vpcid: 'other' }, { id: 'named-default', name: 'default_deny', vpcid: 'other' }]
  expect(gatewayAcls(rows, resource)).toEqual(rows.slice(0, 3))
})
test('VLAN and associated network wire modes are mutually exclusive; nonadmin cannot send admin inputs', () => {
  expect(gatewayParams({ ...draft, associatednetworkid: 'hidden' }, resource, true, true)).toMatchObject({ vlan: '123', physicalnetworkid: 'physical', sourcenatsupported: false, bypassvlanoverlapcheck: false, aclid: 'deny' })
  expect(gatewayParams(draft, resource, true, false)).not.toHaveProperty('associatednetworkid')
  const p = gatewayParams({ ...draft, associatednetworkid: 'own', bypass: true }, resource, false, true)
  expect(p).toMatchObject({ associatednetworkid: 'own', account: 'owner', domainid: 'domain' })
  for (const key of ['vlan', 'physicalnetworkid', 'bypassvlanoverlapcheck']) expect(p).not.toHaveProperty(key)
})
test('known duplicate connection/subnet is blocked but distinct VLAN and API accepted IP subnet are retained', () => {
  const gates = [{ ...resource, vpcid: resource.id, vlan: 'vlan://123', gateway: draft.gateway, netmask: draft.netmask, ipaddress: draft.ipaddress }]
  expect(gatewayErrors(draft, resource, { ...state, gateways: gates }, true)).toEqual({ ipaddress: 'duplicate' })
  expect(gatewayErrors({ ...draft, vlan: '124' }, resource, { ...state, gateways: gates }, true)).toEqual({})
  expect(gatewayErrors({ ...draft, ipaddress: '10.2.2.2' }, resource, state, true)).toEqual({})
})
test('associated network CIDR overlap and stale selection are checked before POST', () => {
  const d = { ...draft, mode: 'associated', associatednetworkid: 'own' }
  const s = { ...state, networks: [{ id: 'own', cidr: '10.1.1.0/24' }] }
  expect(gatewayErrors(d, resource, s, false)).toEqual({ ipaddress: 'overlap' })
  expect(gatewayErrors(d, resource, state, false)).toEqual({ associatednetworkid: 'candidate' })
  expect(gatewayInCidr('100.64.0.2', '100.64.0.0/10')).toBe(true)
})
