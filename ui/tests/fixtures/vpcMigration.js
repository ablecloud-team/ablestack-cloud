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

export const copy = data => JSON.parse(JSON.stringify(data))
export function fixture () {
  const services = ['Dns', 'Dhcp', 'SourceNat', 'NetworkACL'].map(name => ({ name, provider: [{ name: 'VpcVirtualRouter' }] }))
  const offering = { id: 'o1', name: 'Source offering', state: 'Enabled', redundantvpcrouter: false, service: services, conservemode: false }
  const tierOffering = { id: 'n1', name: 'Source Tier offering', state: 'Enabled', forvpc: true, guestiptype: 'Isolated', traffictype: 'Guest', service: services, conservemode: false }
  const vpc = { id: 'v', name: 'VPC', zoneid: 'z', account: 'owner', domainid: 'd', state: 'Enabled', vpcofferingid: 'o1', cidr: '10.1.0.0/16', networkdomain: 'fixture.local', tags: [], fordisplay: true, redundantvpcrouter: false }
  const tiers = ['t1', 't2'].map((id, i) => ({ network: { id, name: 'Tier ' + id, vpcid: 'v', zoneid: 'z', account: 'owner', domainid: 'd', networkofferingid: 'n1', state: 'Implemented', related: id, displaynetwork: true, type: 'Isolated', traffictype: 'Guest', cidr: '10.1.' + i + '.0/24', gateway: '10.1.' + i + '.1', netmask: '255.255.255.0', physicalnetworkid: 'ph' }, source: null, offering: copy(tierOffering), locked: false }))
  return {
    vpc,
    offering,
    offerings: [offering, { ...copy(offering), id: 'o2', name: 'Destination offering' }],
    networkOfferings: [tierOffering, { ...copy(tierOffering), id: 'n2', name: 'Destination Tier offering' }],
    previous: null,
    checkpoint: '',
    tiers,
    physical: [{ id: 'ph', name: 'Physical network', state: 'Enabled' }],
    traffic: { ph: [{ id: 'traffic', traffictype: 'Guest', isolationmethods: 'VLAN' }] },
    jobs: [],
    routers: [{ id: 'router', name: 'Router', state: 'Running' }],
    publicIps: [{ id: 'ip', ipaddress: '192.0.2.10', issourcenat: true, isstaticnat: false }],
    privateGateways: [],
    vpnGateways: [],
    routes: [],
    vms: [{ id: 'vm', name: 'VM', state: 'Running', nic: [{ id: 'nic', networkid: 't1', ipaddress: '10.1.0.10', macaddress: '02:00:00:00:00:01' }] }]
  }
}

export function partial (context) {
  const c = copy(context)
  c.previous = { ...copy(c.vpc), id: 'old-vpc', fordisplay: false }; c.checkpoint = '41'
  c.vpc.vpcofferingid = 'o2'; c.vpc.tags = [{ key: 'migration', value: '41' }]; c.offering = c.offerings[1]
  c.tiers[0].network.networkofferingid = 'n2'; c.tiers[0].offering = c.networkOfferings[1]; c.tiers[0].locked = true
  c.tiers[1].network.vpcid = 'old-vpc'
  return c
}

export function completed (context, plan) {
  const c = copy(context); c.previous = null; c.checkpoint = ''; c.vpc.tags = []; c.vpc.vpcofferingid = plan.offeringId; c.offering = c.offerings.find(o => o.id === plan.offeringId)
  c.tiers.forEach(row => { const p = plan.rows.find(p => p.id === row.network.id); row.network.vpcid = 'v'; row.network.networkofferingid = p.offeringId; row.network.physicalnetworkid = p.physicalId; row.network.related = row.network.id; row.offering = c.networkOfferings.find(o => o.id === p.offeringId); row.source = null; row.locked = false })
  return c
}
