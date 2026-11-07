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

export const usablePublicIp = ip => ip.state === 'Allocated' && !ip.issystem
export const publicIpService = (context, name) => Array.isArray(context?.service) &&
  context.service.some(service => service?.name?.toLowerCase() === name.toLowerCase())

export function publicIpRemoteVpn (ip, network, vpc) {
  if (!usablePublicIp(ip) || ip.isstaticnat || (!vpc && (network?.type !== 'Isolated' || network?.ip4routing))) return false
  const context = vpc || network
  const vpn = context?.service?.find(service => service.name === 'Vpn')
  if (!vpn) return false
  const router = vpc ? 'VpcVirtualRouter' : 'VirtualRouter'
  const hasRouter = service => service?.provider?.some(provider => provider.name?.toLowerCase() === router.toLowerCase())
  if (hasRouter(vpn)) {
    const routerSourceNat = hasRouter(context.service.find(service => service.name === 'SourceNat'))
    if (!vpc || routerSourceNat) return routerSourceNat && !!ip.issourcenat
  }
  return ip.issourcenat || ip.virtualmachinetype === 'DomainRouter' || !ip.hasrules
}

export function publicIpRuleTabs (ip, network, vpc, counts = {}) {
  const tabs = ['details']
  if (!usablePublicIp(ip)) return [...tabs, 'events', 'comments']
  const isolated = network?.type === 'Isolated' && !network.ip4routing
  const service = name => publicIpService(vpc || (isolated ? network : null), name)
  const tierService = name => service(name) && (!network || !vpc || publicIpService(network, name))
  const conserve = network?.networkofferingconservemode ?? vpc?.vpcofferingconservemode
  const exclusive = !!vpc && conserve !== true
  if (service('Firewall')) tabs.push('firewall')
  if (!ip.isstaticnat && !(exclusive && ip.issourcenat)) {
    if (tierService('PortForwarding') && !(exclusive && counts.lb !== 0)) tabs.push('portforwarding')
    if (tierService('Lb') && !(exclusive && counts.pf !== 0)) tabs.push('loadbalancing')
  }
  if (publicIpRemoteVpn(ip, network, vpc)) tabs.push('vpn')
  return [...tabs, 'events', 'comments']
}

export const publicIpScope = ip => ip.projectid ? { projectid: ip.projectid } : {
  ...(ip.account && ip.domainid ? { account: ip.account, domainid: ip.domainid } : {})
}

export function publicIpNicAddresses (nics, networkId) {
  if (!networkId) return []
  return [...new Set((nics || []).filter(nic => nic.networkid === networkId)
    .flatMap(nic => [nic.ipaddress, ...(nic.secondaryip || []).map(ip => ip.ipaddress)])
    .filter(Boolean))]
}
