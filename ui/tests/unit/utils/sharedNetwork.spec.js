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

import { sharedScopeParams, sharedAddressParams, sharedScopeLabel } from '@/utils/sharedNetwork'

const v4 = { family: 'ipv4', startip: '192.0.2.10', endip: '192.0.2.20', gateway: '192.0.2.1', netmask: '255.255.255.0' }
const v6 = { startipv6: '2001:db8::10', endipv6: '2001:db8::20', ip6gateway: '2001:db8::1', ip6cidr: '2001:db8::/64' }
const api = Object.fromEntries(['dns1', 'dns2', 'ip6dns1', 'ip6dns2', 'routerip', 'routeripv6', 'networkdomain'].map(key => [key, {}]))

describe('Shared network creation contract', () => {
  test('omits stale account and project identifiers from all and domain requests', () => {
    const owner = { manageOwner: true, domainId: 'd1', account: { name: 'other', domainid: 'd2' }, project: { id: 'p2', domainid: 'd2' }, subdomainAccess: true }
    expect(sharedScopeParams({ ...owner, scope: 'all' })).toEqual({ acltype: 'domain' })
    expect(sharedScopeParams({ ...owner, scope: 'domain' })).toEqual({ acltype: 'domain', domainid: 'd1', subdomainaccess: true })
    expect(sharedScopeParams({ ...owner, scope: 'account' })).toBeNull()
    expect(sharedScopeParams({ ...owner, scope: 'project' })).toBeNull()
  })

  test('uses the selected project context or a regular user implicit owner', () => {
    expect(sharedScopeParams({ scope: 'all', fixed: { projectid: 'p1' } })).toEqual({ acltype: 'account', projectid: 'p1' })
    expect(sharedScopeParams({ scope: 'account', manageOwner: false, account: { name: 'other' } })).toEqual({ acltype: 'account' })
    expect(sharedScopeParams({ scope: 'project', project: { id: 'p1', domainid: 'd1' } })).toEqual({ acltype: 'account', projectid: 'p1', domainid: 'd1' })
  })

  test('does not send hidden IPv6, router or DNS fields', () => {
    expect(sharedAddressParams({ ...v4, ...v6, routerip: '192.0.2.10', dns1: '192.0.2.2', ip6dns1: '2001:db8::2', networkdomain: 'example.test' }, { api })).toEqual({
      startip: v4.startip, endip: v4.endip, gateway: v4.gateway, netmask: v4.netmask
    })
  })

  test('validates IPv4 and IPv6 ranges and router membership before posting', () => {
    expect(() => sharedAddressParams({ ...v4, ...v6, family: 'dual' }, { router: true, api })).toThrow('message.shared.ipv6.dns')
    expect(() => sharedAddressParams({ ...v4, ...v6, family: 'ipv6' }, { router: true, api })).toThrow('message.shared.ipv6.router')
    expect(() => sharedAddressParams({ ...v4, routerip: '192.0.2.21' }, { router: true, api })).toThrow('message.shared.router.range')
    expect(() => sharedAddressParams({ ...v4, dns2: '192.0.2.21' }, { dns: true, api })).toThrow('message.shared.dns.primary')
    expect(sharedAddressParams({ ...v4, ...v6, family: 'dual', routeripv6: '2001:db8::20', ip6dns1: '2001:db8::2' }, { router: true, dns: true, api })).toMatchObject({ ...v6, routeripv6: '2001:db8::20', ip6dns1: '2001:db8::2' })
  })

  test('distinguishes root-wide, domain, account and project detail rows', () => {
    expect(sharedScopeLabel({ acltype: 'Domain' })).toBe('label.all')
    expect(sharedScopeLabel({ acltype: 'Domain', domainid: 'root', domainpath: 'ROOT', subdomainaccess: true })).toBe('label.all')
    expect(sharedScopeLabel({ acltype: 'Domain', domainid: 'root', domainpath: 'ROOT', subdomainaccess: false })).toBe('label.domain')
    expect(sharedScopeLabel({ acltype: 'Domain', domainid: 'd1', domainpath: 'ROOT/child', subdomainaccess: true })).toBe('label.domain')
    expect(sharedScopeLabel({ acltype: 'Account' })).toBe('label.account')
    expect(sharedScopeLabel({ acltype: 'Account', projectid: 'p1' })).toBe('label.project')
  })
})
