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

export const privateGatewayApis = ['createPrivateGateway', 'queryAsyncJobResult', 'listVPCs', 'listPrivateGateways', 'listNetworkACLLists', 'listNetworks']
export const gatewayOwner = r => r.projectid ? { projectid: r.projectid } : { account: r.account, domainid: r.domainid }
export const sameGatewayOwner = (row, r) => (row.projectid || '') === (r.projectid || '') && (r.projectid ? true : row.account === r.account && row.domainid === r.domainid)
export const gatewayIp = text => {
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(text || '')) return null
  const parts = text.split('.')
  if (parts.some(part => part.length > 1 && part[0] === '0')) return null
  const octets = parts.map(Number)
  if (octets.some(n => n > 255)) return null
  return octets.reduce((n, octet) => n * 256 + octet, 0) >>> 0
}
export const gatewayMask = text => {
  const n = gatewayIp(text)
  if (!n) return false
  const inverse = (~n) >>> 0
  return (inverse & (inverse + 1)) === 0
}
export const gatewaySubnet = (ip, mask) => gatewayIp(ip) === null || !gatewayMask(mask) ? null : (gatewayIp(ip) & gatewayIp(mask)) >>> 0
export const gatewayInCidr = (ip, cidr) => {
  const [base, bits] = (cidr || '').split('/')
  if (gatewayIp(base) === null || gatewayIp(ip) === null || !/^\d+$/.test(bits || '') || Number(bits) > 32) return false
  const mask = Number(bits) ? (0xffffffff << (32 - Number(bits))) >>> 0 : 0
  return ((gatewayIp(ip) & mask) >>> 0) === ((gatewayIp(base) & mask) >>> 0)
}
export const gatewayVlan = value => String(value || '').trim().replace(/^vlan:\/\//i, '')
export const validGatewayVlan = value => /^(\d+|untagged)$/i.test(gatewayVlan(value)) || /^lswitch:\/\/[^\s/]+$/i.test(String(value || '').trim())
export function gatewayNetworks (rows, r) {
  return rows.filter(n => sameGatewayOwner(n, r) && n.zoneid === r.zoneid && ['Isolated', 'L2'].includes(n.type) && !['Destroy', 'Destroyed'].includes(n.state))
}
export const gatewayAcls = (rows, r) => rows.filter(a => (!a.vpcid && ['default_deny', 'default_allow'].includes(a.name)) || a.vpcid === r.id)
export function gatewayErrors (draft, resource, state, admin) {
  const errors = {}
  if (gatewayIp(draft.ipaddress) === null) errors.ipaddress = 'ipv4'
  if (gatewayIp(draft.gateway) === null) errors.gateway = 'ipv4'
  if (!gatewayMask(draft.netmask)) errors.netmask = 'netmask'
  if (!state.acls.some(a => a.id === draft.acl)) errors.acl = 'candidate'
  if (draft.mode === 'vlan' && admin) {
    if (!validGatewayVlan(draft.vlan)) errors.vlan = 'vlan'
    if (!state.physical.some(p => p.id === draft.physicalnetwork && p.zoneid === resource.zoneid)) errors.physicalnetwork = 'candidate'
  } else {
    const network = state.networks.find(n => n.id === draft.associatednetworkid)
    if (!network) errors.associatednetworkid = 'candidate'
    else if (gatewayInCidr(draft.ipaddress, network.cidr)) errors.ipaddress = 'overlap'
  }
  const subnet = gatewaySubnet(draft.gateway, draft.netmask)
  if (state.gateways.some(g => {
    if (g.zoneid !== resource.zoneid) return false
    if (draft.mode === 'associated') return g.associatednetworkid === draft.associatednetworkid && g.ipaddress === draft.ipaddress
    return g.vpcid === resource.id && gatewayVlan(g.vlan) === gatewayVlan(draft.vlan) && subnet !== null && gatewaySubnet(g.gateway, g.netmask) === subnet
  })) errors.ipaddress = 'duplicate'
  return errors
}
export function gatewayParams (draft, r, admin, bypassSupported) {
  const params = { vpcid: r.id, ...gatewayOwner(r), ignoreproject: true, ipaddress: draft.ipaddress.trim(), gateway: draft.gateway.trim(), netmask: draft.netmask.trim(), sourcenatsupported: !!draft.nat, aclid: draft.acl }
  if (draft.mode === 'vlan' && admin) {
    params.vlan = draft.vlan.trim(); params.physicalnetworkid = draft.physicalnetwork
    if (bypassSupported) params.bypassvlanoverlapcheck = !!draft.bypass
  } else params.associatednetworkid = draft.associatednetworkid
  return params
}
