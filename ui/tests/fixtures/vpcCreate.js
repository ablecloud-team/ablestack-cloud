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

export const copy = x => JSON.parse(JSON.stringify(x))
export const zone = { id: 'z', name: 'Zone', networktype: 'Advanced', allocationstate: 'Enabled', securitygroupsenabled: false, isnsxenabled: false, routedmodeenabled: true, allowuserspecifyvrmtu: false, routerpublicinterfacemaxmtu: 1500 }
export const offering = { id: 'o', name: 'Default', state: 'Enabled', fornsx: false, internetprotocol: 'IPv4', service: ['Dns', 'Dhcp', 'SourceNat'].map(name => ({ name, provider: [{ name: 'VpcVirtualRouter' }] })) }
export const owner = { account: 'admin', domainid: 'd' }
export const params = 'name displaytext zoneid vpcofferingid cidr cidrsize networkdomain dns1 dns2 ip6dns1 ip6dns2 asnumber publicmtu userouteripresolver sourcenatipaddress keepmacaddressonpublicnic start bgppeerids fordisplay'.split(' ').map(name => ({ name }))
export const getters = role => ({ userInfo: { id: 'u', roleid: 'role', roletype: role || 'Admin', account: 'admin', domainid: 'd' }, project: {}, apis: { createVPC: { params: copy(params) }, ...Object.fromEntries(['listVPCOfferings', 'listZones', 'listProjects', 'queryAsyncJobResult', 'listPhysicalNetworks', 'listTrafficTypes', 'listNetworkServiceProviders', 'listVirtualRouterElements', 'listASNumbers'].map(a => [a, {}])) } })
export function fixture () {
  return {
    zones: [copy(zone), { ...copy(zone), id: 'z2', name: 'Zone 2' }],
    offerings: [copy(offering), { ...copy(offering), id: 'no-dns', name: 'No DNS', service: copy(offering.service.filter(s => s.name !== 'Dns')) }],
    physical: [{ id: 'ph', name: 'Physical', zoneid: 'z', state: 'Enabled' }],
    traffic: [{ id: 'gt', traffictype: 'Guest', isolationmethod: 'VLAN' }],
    providers: [{ id: 'vr', name: 'VpcVirtualRouter', state: 'Enabled', servicelist: ['Dns', 'Dhcp', 'SourceNat'] }],
    elements: [{ id: 'el', nspid: 'vr', enabled: true }],
    asNumbers: [],
    projects: [{ id: 'p', name: 'Project', domainid: 'd', state: 'Active' }]
  }
}
export function response (data, api, query = {}) {
  const map = { listZones: ['zone', data.zones.filter(z => !query.id || z.id === query.id)], listVPCOfferings: ['vpcoffering', data.offerings], listPhysicalNetworks: ['physicalnetwork', data.physical], listTrafficTypes: ['traffictype', data.traffic], listNetworkServiceProviders: ['networkserviceprovider', data.providers], listVirtualRouterElements: ['virtualrouterelement', data.elements], listASNumbers: ['asnumber', data.asNumbers], listProjects: ['project', data.projects] }
  const field = map[api][0]; let rows = map[api][1]
  if (api === 'listPhysicalNetworks' && query.zoneid) rows = rows.map(row => ({ ...row, zoneid: query.zoneid }))
  return { [api.toLowerCase() + 'response']: { count: rows.length, [field]: copy(rows) } }
}
