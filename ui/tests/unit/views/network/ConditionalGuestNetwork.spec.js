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

import { guestFeatureStatus, canWriteGuestFeature } from '@/utils/conditionalGuestNetwork'
import { conditionalFirewallRule } from '@/utils/conditionalFirewallRule'

const apiNames = ['listBgpPeers', 'listRoutingFirewallRules', 'listIpv6FirewallRules', 'listVnfAppliances',
  'listTungstenFabricPolicy', 'listTungstenFabricLogicalRouter', 'listCustomActions', 'runCustomAction']
const apis = Object.fromEntries(apiNames.map(api => [api, {}]))
const network = { id: 'net', zoneid: 'zone', type: 'Isolated', ip4routing: 'Dynamic', internetprotocol: 'DualStack', domainid: 'a', account: 'dev' }
const admin = { apis, userInfo: { roletype: 'Admin' } }
const owner = { apis, userInfo: { roletype: 'User', domainid: 'a', account: 'dev' } }

describe('conditional guest network features', () => {
  it('restricts BGP and routing firewalls to the supported standalone network and role', () => {
    expect(guestFeatureStatus('bgp.peers', { ...network, vpcid: '', vpcname: null }, admin)).toBe('available')
    expect(guestFeatureStatus('bgp.peers', network, owner)).toBe('forbidden')
    expect(guestFeatureStatus('bgp.peers', { ...network, ip4routing: 'Static' }, admin)).toBe('unsupported')
    expect(guestFeatureStatus('routing.firewall', { ...network, vpcname: 'vpc' }, admin)).toBe('unsupported')
    expect(guestFeatureStatus('routing.firewall', network, owner)).toBe('available')
    expect(guestFeatureStatus('routing.firewall', network, { ...owner, userInfo: { ...owner.userInfo, domainid: 'other' } })).toBe('forbidden')
    expect(guestFeatureStatus('routing.firewall', { ...network, projectid: 'p1' }, { ...owner, project: { id: 'p2' } })).toBe('forbidden')
    expect(guestFeatureStatus('routing.firewall', { ...network, projectid: 'p1' }, { ...owner, project: { id: 'p1' } })).toBe('available')
  })

  it('keeps IPv6 firewall separate from Shared IPv6 ranges', () => {
    expect(guestFeatureStatus('ip.v6.firewall', network, owner)).toBe('available')
    expect(guestFeatureStatus('ip.v6.firewall', { ...network, type: 'Shared' }, admin)).toBe('unsupported')
    expect(guestFeatureStatus('ip.v6.firewall', { ...network, ip6firewall: false }, admin)).toBe('unsupported')
    expect(guestFeatureStatus('ip.v6.firewall', network, { ...admin, apis: {} })).toBe('forbidden')
  })

  it('requires Tungsten context, but supports the VNF association lookup on all guest types', () => {
    for (const type of ['Isolated', 'L2', 'Shared']) {
      const tf = { ...network, type, broadcasturi: 'tf://tf' }
      for (const key of ['network.policy', 'tungsten.logical.router']) {
        expect(guestFeatureStatus(key, tf, admin)).toBe(type === 'Shared' ? 'unsupported' : 'available')
        expect(guestFeatureStatus(key, { ...tf, broadcasturi: 'vlan://8' }, admin)).toBe('unsupported')
      }
      expect(guestFeatureStatus('vnf.appliances', tf, admin)).toBe('available')
    }
    expect(guestFeatureStatus('vnf.appliances', { ...network, traffictype: 'Management' }, admin)).toBe('unsupported')
  })

  it('requires a CustomAction service and independently gates execution', () => {
    const custom = { ...network, service: [{ name: 'CustomAction' }] }
    const readOnly = { ...owner, apis: { listCustomActions: {} } }
    expect(guestFeatureStatus('custom.actions', network, owner)).toBe('unsupported')
    expect(guestFeatureStatus('custom.actions', custom, readOnly)).toBe('available')
    expect(canWriteGuestFeature('custom.actions', 'runCustomAction', custom, readOnly)).toBe(false)
    expect(canWriteGuestFeature('custom.actions', 'runCustomAction', custom, owner)).toBe(true)
    expect(canWriteGuestFeature('custom.actions', 'runCustomAction', { ...custom, state: 'Shutdown' }, owner)).toBe(false)
  })
})

describe('routing and IPv6 rule submissions', () => {
  const tcp = { protocol: 'tcp', traffictype: 'ingress', cidrlist: '192.0.2.0/24, 198.51.100.0/24', startport: '443', endport: '443' }
  it('binds the active network and drops values for an inactive protocol', () => {
    expect(conditionalFirewallRule({ ...tcp, networkid: 'old', icmpcode: 0 }, 'new')).toEqual({
      networkid: 'new', protocol: 'tcp', traffictype: 'ingress', cidrlist: '192.0.2.0/24,198.51.100.0/24', startport: 443, endport: 443
    })
    expect(conditionalFirewallRule({ protocol: 'icmp', traffictype: 'ingress', icmptype: 128, icmpcode: 0, startport: '1', cidrlist: '2001:db8::/64' }, 'new', true)).toEqual({
      networkid: 'new', protocol: 'icmp', traffictype: 'ingress', icmptype: 128, icmpcode: 0, cidrlist: '2001:db8::/64'
    })
  })
  it('rejects inverted ports, wrong address families and malformed blocks', () => {
    expect(conditionalFirewallRule({ ...tcp, endport: '80' }, 'new')).toBeNull()
    expect(conditionalFirewallRule(tcp, 'new', true)).toBeNull()
    expect(conditionalFirewallRule({ ...tcp, destcidrlist: '192.0.999.0/24' }, 'new')).toBeNull()
    expect(conditionalFirewallRule({ ...tcp, cidrlist: '2001:db8::/129' }, 'new', true)).toBeNull()
  })
})
