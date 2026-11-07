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

/* global BigInt */

export const rangeFields = ['startip', 'endip', 'gateway', 'netmask', 'startipv6', 'endipv6', 'ip6gateway', 'ip6cidr']
export const rangeApis = ['listNetworks', 'listVlanIpRanges', 'createVlanIpRange', 'updateVlanIpRange', 'deleteVlanIpRange']

export function guestIpRangeSupported (network = {}) {
  if (!['Shared', 'Isolated'].includes(network.type) || network.specifyipranges !== true ||
    !Array.isArray(network.service) || network.service.some(s => !s || typeof s.name !== 'string' || !s.name)) return false
  if (network.type === 'Shared') return true
  return !network.vpcid && !network.vpcname && !network.ip4routing && !network.ip6routing &&
    !network.service.some(s => s.name === 'SourceNat')
}

export const guestIpRangeVisible = (network, apis = {}) => 'listVlanIpRanges' in apis && guestIpRangeSupported(network)
export const rangeProject = network => network.projectid ? { projectid: network.projectid } : { ignoreproject: true }
export const rangeQuery = network => ({ networkid: network.id, zoneid: network.zoneid, forvirtualnetwork: false, ...rangeProject(network) })
export const rangeScope = (network, getters = {}, token) => JSON.stringify([
  network.id, network.zoneid, network.type, network.specifyipranges, network.networkofferingid,
  network.service, network.vpcid, network.vpcname, network.ip4routing, network.ip6routing,
  network.projectid, network.account, network.domainid, getters.project?.id, getters.userInfo?.id, token,
  rangeApis.map(api => api in (getters.apis || {}))
])

export function canonicalRangeValue (field, value) {
  const text = String(value ?? '').trim()
  if (!text || !['startipv6', 'endipv6', 'ip6gateway', 'ip6cidr'].includes(field)) return text
  const [ip, prefix] = text.split('/')
  try {
    const address = new URL(`http://[${ip}]/`).hostname.slice(1, -1).toLowerCase()
    return field === 'ip6cidr' ? address + '/' + Number(prefix) : address
  } catch (_) { return text.toLowerCase() }
}
export const rangeSignature = row => JSON.stringify([row.id, row.networkid, row.projectid, ...rangeFields.map(f => canonicalRangeValue(f, row[f]))])
export const rangeMatches = (row, params) => rangeFields.every(field => !(field in params) || canonicalRangeValue(field, row[field]) === canonicalRangeValue(field, params[field]))

export function ipv4 (value) {
  if (!/^(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}$/.test(value)) return null
  const octets = value.split('.').map(Number)
  return octets.some(n => n > 255) ? null : octets.reduce((n, octet) => n * 256 + octet, 0)
}
export function ipv6 (value) {
  if (!/^[\da-f:.]+$/i.test(value) || !value.includes(':')) return null
  try {
    const text = new URL(`http://[${value}]/`).hostname.slice(1, -1)
    const parts = text.split('::').map(side => side ? side.split(':') : [])
    const words = parts.length === 2 ? [...parts[0], ...Array(8 - parts[0].length - parts[1].length).fill('0'), ...parts[1]] : parts[0]
    return words.reduce((n, word) => n * BigInt(65536) + BigInt(parseInt(word, 16)), BigInt(0))
  } catch (_) { return null }
}

export function buildRangeParams (form, network, existing) {
  if (!['ipv4', 'ipv6', 'dual'].includes(form.family)) throw new Error('message.guest.range.required.family')
  const params = existing ? { id: existing.id, ...rangeProject(network) } : { ...rangeQuery(network), forvirtualnetwork: false }
  for (const family of ['ipv4', 'ipv6']) {
    if (form.family !== family && form.family !== 'dual') continue
    const fields = family === 'ipv4' ? rangeFields.slice(0, 4) : rangeFields.slice(4)
    for (const field of fields) params[field] = String(form[field] ?? '').trim()
    const start = fields[0]; const end = fields[1]; const gateway = fields[2]
    params[end] ||= params[start]
    if (fields.some(f => !params[f])) throw new Error('message.guest.range.required.family')
    const number = family === 'ipv4' ? ipv4 : ipv6
    const a = number(params[start]); const b = number(params[end]); const g = number(params[gateway])
    if (a === null || b === null || g === null) throw new Error('message.guest.range.invalid.address')
    if (a > b || (g >= a && g <= b)) throw new Error('message.guest.range.invalid.order')
    if (family === 'ipv4') {
      const mask = ipv4(params.netmask)
      const host = mask === null ? null : (4294967295 - mask)
      if (host === null || (BigInt(host) & (BigInt(host) + BigInt(1))) !== BigInt(0)) throw new Error('message.error.netmask')
      if (((a & mask) >>> 0) !== ((g & mask) >>> 0) || ((b & mask) >>> 0) !== ((g & mask) >>> 0)) throw new Error('message.guest.range.subnet')
    } else {
      const [address, prefix] = params.ip6cidr.split('/')
      const size = Number(prefix); const base = ipv6(address)
      if (base === null || !/^\d+$/.test(prefix || '') || size > 64) throw new Error('message.guest.range.invalid.cidr')
      const shift = BigInt(128 - size)
      if ([a, b, g].some(n => (n >> shift) !== (base >> shift))) throw new Error('message.guest.range.subnet')
    }
  }
  return params
}

export function guestRangesOverlap (left, right) {
  return [
    ['startip', 'endip', ipv4],
    ['startipv6', 'endipv6', ipv6]
  ].some(([start, end, parse]) => {
    if (!left[start] || !right[start]) return false
    const l = parse(left[start]); const r = parse(right[start])
    const lEnd = parse(left[end] || left[start]); const rEnd = parse(right[end] || right[start])
    return l !== null && r !== null && lEnd !== null && rEnd !== null && l <= rEnd && r <= lEnd
  })
}

export async function listAllGuestRanges (getAPI, network) {
  const rows = []; const seen = new Set()
  for (let page = 1; page <= 100; page++) {
    const response = await getAPI('listVlanIpRanges', { ...rangeQuery(network), page, pagesize: 500 }, { preserveOnFailure: true })
    const body = response?.listvlaniprangesresponse
    if (!body || (body.vlaniprange !== undefined && !Array.isArray(body.vlaniprange))) throw new Error('message.guest.range.read.failed')
    const batch = body.vlaniprange || []
    for (const row of batch) {
      if (!row.id || row.networkid !== network.id || seen.has(row.id)) throw new Error('message.guest.range.read.failed')
      seen.add(row.id); rows.push(row)
    }
    if (body.count !== undefined && (!Number.isInteger(Number(body.count)) || Number(body.count) < rows.length)) throw new Error('message.guest.range.read.failed')
    if (body.count !== undefined && rows.length === Number(body.count)) return rows
    if (batch.length < 500) {
      if (body.count !== undefined && rows.length !== Number(body.count)) throw new Error('message.guest.range.read.failed')
      return rows
    }
  }
  throw new Error('message.guest.range.read.failed')
}

export async function currentRangeNetwork (getAPI, resource) {
  const json = await getAPI('listNetworks', { id: resource.id, listall: true, ...rangeProject(resource) }, { preserveOnFailure: true })
  const network = json?.listnetworksresponse?.network?.find(n => n.id === resource.id)
  if (!network || !guestIpRangeSupported(network) || network.zoneid !== resource.zoneid ||
    (network.projectid || '') !== (resource.projectid || '') || (network.account || '') !== (resource.account || '') ||
    (network.domainid || '') !== (resource.domainid || '')) throw new Error('message.guest.range.scope.changed')
  return network
}

export function rangeError (error, translate) {
  const body = error?.response?.data
  const detail = body && Object.values(body).find(value => value && typeof value === 'object' && value.errortext)?.errortext
  return detail || (error?.message?.startsWith('message.') ? translate(error.message) : error?.message) || translate('message.guest.range.read.failed')
}
