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

import { networkCollection } from '@/utils/networkLifecycle'
import { diagnosticApis, diagnosePhysicalNetworkOffering } from '@/utils/networkOfferingDiagnostics'
import { gatewayIp } from '@/utils/privateGateway'

export const vpcCreateApis = ['createVPC', 'listVPCOfferings', 'listZones', 'queryAsyncJobResult']
export const vpcCreateFlag = v => v === true || v === 'true'
export const vpcCreateMode = o => o?.networkmode || 'NATTED'
export const vpcCreateRouting = o => o?.routingmode || 'Static'
export const vpcCreateOwnerParams = owner => owner?.projectid ? { projectid: owner.projectid } : { account: owner?.account, domainid: owner?.domainid }
const has = (o, name) => o?.service?.some(s => s.name === name)
const providers = o => (o?.service || []).flatMap(s => (s.provider || []).map(p => p.name))
const parameter = (g, name) => g.apis?.createVPC?.params?.some(p => p.name === name)
const fail = key => { throw new Error('message.vpc.create.' + key) }
const canonical = v => Array.isArray(v) ? v.map(canonical).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k, canonical(v[k])])) : v
export const vpcCreateOfferingFingerprint = o => JSON.stringify(canonical(Object.fromEntries(['id', 'state', 'zoneid', 'domainid', 'networkmode', 'routingmode', 'internetprotocol', 'specifyasnumber', 'fornsx', 'supportsregionLevelvpc', 'redundantvpcrouter', 'service'].map(k => [k, o?.[k] ?? null]))))
export const vpcCreateDiagnosticFingerprint = d => JSON.stringify(canonical(d))
export const vpcCreateZoneFingerprint = z => JSON.stringify(['id', 'networktype', 'allocationstate', 'securitygroupsenabled', 'isnsxenabled', 'routedmodeenabled', 'allowuserspecifyvrmtu', 'routerpublicinterfacemaxmtu'].map(k => z?.[k] ?? null))

export function vpcCreateZoneAllowed (zone, getters) {
  return !!zone?.id && zone.networktype === 'Advanced' && !vpcCreateFlag(zone.securitygroupsenabled) &&
    (zone.allocationstate === 'Enabled' || getters.userInfo?.roletype === 'Admin')
}

export function vpcCreateFields (o, z, g) {
  const external = providers(o).some(p => ['Nsx', 'Netris'].includes(p))
  return {
    routed: vpcCreateMode(o) === 'ROUTED',
    dns: has(o, 'Dns'),
    ipv6: o?.internetprotocol === 'DualStack',
    asNumber: vpcCreateRouting(o) === 'Dynamic' && vpcCreateFlag(o?.specifyasnumber),
    resolver: vpcCreateFlag(o?.fornsx) && vpcCreateFlag(z?.isnsxenabled) && parameter(g, 'userouteripresolver'),
    sourceNat: has(o, 'SourceNat') && !external && parameter(g, 'sourcenatipaddress'),
    mtu: vpcCreateFlag(z?.allowuserspecifyvrmtu) && parameter(g, 'publicmtu'),
    mac: g.userInfo?.roletype === 'Admin' && parameter(g, 'keepmacaddressonpublicnic'),
    advanced: external || o?.internetprotocol === 'DualStack' || vpcCreateRouting(o) === 'Dynamic'
  }
}

export function resetVpcCreateFields (form, previous, offering, zone, getters, full = false) {
  const fields = vpcCreateFields(offering, zone, getters)
  if (full) for (const k of ['cidr', 'cidrsize', 'dns1', 'dns2', 'ip6dns1', 'ip6dns2', 'asnumber', 'publicmtu', 'sourcenatipaddress', 'userouteripresolver']) delete form[k]
  if (!fields.dns || form.userouteripresolver) for (const k of ['dns1', 'dns2']) delete form[k]
  if (!fields.ipv6 || !fields.dns) for (const k of ['ip6dns1', 'ip6dns2']) delete form[k]
  if (!fields.routed) delete form.cidrsize
  if (fields.routed && getters.userInfo?.roletype !== 'Admin') delete form.cidr
  if (previous && vpcCreateMode(previous) !== vpcCreateMode(offering)) { delete form.cidr; delete form.cidrsize }
  if (!fields.asNumber || previous?.id !== offering?.id) delete form.asnumber
  if (!fields.sourceNat) delete form.sourcenatipaddress
  if (!fields.resolver) delete form.userouteripresolver
  if (!fields.mtu) delete form.publicmtu
}

export function vpcCreateOfferingReason (o, zone, getters) {
  if (!o?.id || !Array.isArray(o.service) || !o.service.length) return 'invalid'
  if (o.state !== 'Enabled') return 'disabled'
  if (o.zoneid && !String(o.zoneid).split(',').includes(zone.id)) return 'zone'
  if (vpcCreateFlag(o.fornsx) !== vpcCreateFlag(zone.isnsxenabled)) return 'nsx'
  if (!['NATTED', 'ROUTED'].includes(vpcCreateMode(o))) return 'mode'
  if (vpcCreateMode(o) === 'ROUTED' && !vpcCreateFlag(zone.routedmodeenabled)) return 'routed'
  if (!['IPv4', 'DualStack'].includes(o.internetprotocol || 'IPv4')) return 'protocol'
  const fields = vpcCreateFields(o, zone, getters)
  if (fields.asNumber && (!parameter(getters, 'asnumber') || !getters.apis?.listASNumbers)) return 'as.permission'
  return ''
}

export async function readVpcCreateChoices (get, getters, owner, zone) {
  if (!vpcCreateApis.every(a => getters.apis?.[a])) fail('permission')
  if (!owner?.domainid || (!owner.account && !owner.projectid)) fail('owner')
  if (!vpcCreateZoneAllowed(zone, getters)) fail('zone')
  const offerings = await networkCollection(get, 'listVPCOfferings', 'vpcoffering', { state: 'Enabled', zoneid: zone.id, domainid: owner.domainid, ignoreproject: true })
  // Reuse the existing read-only provider diagnostic. Cache only within this
  // owner/Zone request; a new lookup and the submit preflight get fresh data.
  const cache = new Map()
  const cached = (api, params, options) => {
    const key = api + JSON.stringify(canonical(params))
    if (!cache.has(key)) cache.set(key, get(api, params, options))
    return cache.get(key)
  }
  const hasDiagnostics = diagnosticApis.every(a => getters.apis?.[a])
  const rows = await Promise.all(offerings.map(async offering => {
    let reason = vpcCreateOfferingReason(offering, zone, getters)
    let diagnostic = { status: 'unknown', blocked: false, issues: [], checks: [] }
    if (!reason && hasDiagnostics) diagnostic = await diagnosePhysicalNetworkOffering(cached, getters.apis, { zoneId: zone.id, offering })
    if (diagnostic.blocked) reason = 'provider'
    if (!getters.apis?.listVirtualRouterElements && providers(offering).some(p => p === 'VpcVirtualRouter') && diagnostic.status === 'ready') diagnostic.status = 'unknown'
    return { offering, reason, diagnostic, unverified: vpcCreateFields(offering, zone, getters).advanced }
  }))
  return { rows, reason: !offerings.length ? 'empty' : rows.every(r => r.reason) ? 'incompatible' : '', hasDiagnostics }
}

export async function readVpcCreateAsNumbers (get, zoneId) {
  return (await networkCollection(get, 'listASNumbers', 'asnumber', { zoneid: zoneId, isallocated: false, ignoreproject: true }))
    .filter(row => (!row.zoneid || row.zoneid === zoneId) && !row.allocated && !row.associatednetworkid && !row.vpcid && (!row.allocationstate || row.allocationstate === 'Free') && Number.isSafeInteger(Number(row.asnumber)) && Number(row.asnumber) > 0)
}

// Do not copy the form wholesale. Hidden conditional values, bgppeerids and
// arbitrary API-only attributes must never cross this UI's creation boundary.
export function buildVpcCreateParams (form, owner, zone, offering, getters, asNumbers = []) {
  if (vpcCreateOfferingReason(offering, zone, getters) || !vpcCreateZoneAllowed(zone, getters)) fail('changed')
  if (!form.name?.trim() || !owner?.domainid || (!owner.account && !owner.projectid)) fail('owner')
  const fields = vpcCreateFields(offering, zone, getters)
  const params = { name: form.name.trim(), zoneid: zone.id, vpcofferingid: offering.id, ...vpcCreateOwnerParams(owner), start: !!form.start, ignoreproject: true }
  const nonempty = k => form[k] !== undefined && form[k] !== null && String(form[k]).trim() !== ''
  const add = k => { if (parameter(getters, k) && nonempty(k)) params[k] = typeof form[k] === 'string' ? form[k].trim() : form[k] }
  for (const k of ['displaytext', 'networkdomain']) add(k)
  if (vpcCreateFlag(offering.supportsregionLevelvpc) && !params.networkdomain) fail('networkdomain')
  if (!fields.routed) {
    if (!nonempty('cidr')) fail('cidr')
    add('cidr')
  } else {
    const explicit = getters.userInfo?.roletype === 'Admin' && nonempty('cidr')
    if (explicit && nonempty('cidrsize')) fail('cidr.choice')
    if (explicit) add('cidr')
    else {
      const size = Number(form.cidrsize)
      if (!Number.isInteger(size) || size < 1 || size > 32) fail('cidr.choice')
      params.cidrsize = size
    }
  }
  if (fields.resolver && form.userouteripresolver) params.userouteripresolver = true
  if (fields.dns && !params.userouteripresolver) for (const k of ['dns1', 'dns2']) add(k)
  if (fields.dns && fields.ipv6) for (const k of ['ip6dns1', 'ip6dns2']) add(k)
  if (fields.sourceNat && nonempty('sourcenatipaddress')) {
    if (gatewayIp(String(form.sourcenatipaddress).trim()) === null) fail('ipv4')
    add('sourcenatipaddress')
  }
  if (fields.asNumber) {
    if (!asNumbers.some(row => Number(row.asnumber) === Number(form.asnumber))) fail('as.empty')
    params.asnumber = Number(form.asnumber)
  }
  if (fields.mtu && nonempty('publicmtu')) {
    if (!Number.isInteger(Number(form.publicmtu)) || Number(form.publicmtu) < 68 || Number(form.publicmtu) > Number(zone.routerpublicinterfacemaxmtu || 1500)) fail('mtu')
    params.publicmtu = Number(form.publicmtu)
  }
  if (fields.mac) params.keepmacaddressonpublicnic = !!form.keepmacaddressonpublicnic
  return params
}
