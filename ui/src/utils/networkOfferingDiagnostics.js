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

import { networkOfferingFingerprint } from '@/utils/networkOfferingChange'

export const diagnosticApis = ['listPhysicalNetworks', 'listTrafficTypes', 'listNetworkServiceProviders']

export async function readDiagnosticList (getAPI, command, params, key) {
  const items = []; const seen = new Set()
  for (let page = 1; page <= 100; page++) {
    const json = await getAPI(command, { ...params, page, pagesize: 500 }, { preserveOnFailure: true })
    const response = json?.[command.toLowerCase() + 'response']
    if (!response || response.errorcode) throw new Error(response?.errortext || 'Invalid diagnostic response')
    const next = response[key] || []
    if (!Array.isArray(next)) throw new Error('Invalid diagnostic list')
    for (const item of next) {
      if (!item.id || seen.has(item.id)) throw new Error('Diagnostic results changed while paging')
      seen.add(item.id); items.push(item)
    }
    if (response.count !== undefined ? items.length >= response.count : next.length < 500) return items
    if (!next.length) throw new Error('Incomplete diagnostic response')
  }
  throw new Error('Diagnostic page limit exceeded')
}

const tags = value => (Array.isArray(value) ? value : String(value || '').split(',')).map(v => v.trim()).filter(Boolean)

// This is a read-only preflight of physical-network configuration. The server
// remains authoritative for offering compatibility, device readiness and creation.
export async function diagnoseNetworkOffering (getAPI, apis, { zoneId, physicalNetworkId, offering }) {
  const unknown = { status: 'unknown', blocked: false, issues: [], checks: [] }
  if (!offering?.id || !zoneId) return { ...unknown, status: 'empty', blocked: true }
  if (!diagnosticApis.every(api => api in (apis || {}))) return unknown
  const fresh = await readDiagnosticList(getAPI, 'listNetworkOfferings', { id: offering.id }, 'networkoffering')
  const selected = fresh.find(item => item.id === offering.id)
  if (!selected || selected.state !== 'Enabled') return { ...unknown, status: 'invalid', blocked: true, issues: [{ reason: 'disabled.offering' }] }
  if (networkOfferingFingerprint(selected) !== networkOfferingFingerprint(offering)) return { ...unknown, status: 'invalid', blocked: true, issues: [{ reason: 'changed.offering' }] }
  const networks = await readDiagnosticList(getAPI, 'listPhysicalNetworks', { zoneid: zoneId }, 'physicalnetwork')
  const traffic = await Promise.all(networks.map(network => readDiagnosticList(getAPI, 'listTrafficTypes', { physicalnetworkid: network.id }, 'traffictype')))
  let matching = networks.filter((network, i) => traffic[i].some(type => type.traffictype === (offering.traffictype || 'Guest')))
  if (physicalNetworkId) matching = matching.filter(network => network.id === physicalNetworkId)
  else if (matching.length > 1) {
    const requested = tags(offering.tags)
    matching = matching.filter(network => requested.length ? tags(network.tags).some(tag => requested.includes(tag)) : !tags(network.tags).length)
  }
  if (matching.length !== 1) return { ...unknown, status: 'invalid', blocked: true, issues: [{ reason: matching.length ? 'ambiguous' : 'missing.network' }] }
  const network = matching[0]
  const providers = await readDiagnosticList(getAPI, 'listNetworkServiceProviders', { physicalnetworkid: network.id }, 'networkserviceprovider')
  const vrNeeded = (offering.service || []).some(s => (s.provider || []).some(p => ['VirtualRouter', 'VpcVirtualRouter'].includes(p.name)))
  const elements = vrNeeded && 'listVirtualRouterElements' in apis
    ? await readDiagnosticList(getAPI, 'listVirtualRouterElements', { physicalnetworkid: network.id }, 'virtualrouterelement') : null
  const checks = []; const issues = []
  if (network.state !== 'Enabled') issues.push({ reason: 'disabled.network' })
  for (const service of offering.service || []) {
    if (!service.provider?.length) {
      checks.push({ service: service.name, reason: 'server.resolve' })
      continue
    }
    for (const entry of service.provider) {
      const provider = providers.find(item => item.name === entry.name)
      const reason = !provider ? 'missing.provider' : provider.state !== 'Enabled' ? 'disabled.provider'
        : !provider.servicelist?.includes(service.name) ? 'missing.service'
          : elements && ['VirtualRouter', 'VpcVirtualRouter'].includes(entry.name) && !elements.some(e => e.nspid === provider.id && e.enabled) ? 'disabled.element' : ''
      const check = { service: service.name, provider: entry.name, reason }
      checks.push(check)
      if (reason) issues.push(check)
    }
  }
  return { status: issues.length ? 'invalid' : checks.some(c => c.reason) ? 'unknown' : 'ready', blocked: !!issues.length, network: { id: network.id, name: network.name, isolation: tags(network.isolationmethods)[0]?.toLowerCase() }, checks, issues }
}

export function vpcSupportsTierOffering (vpc, offering) {
  if (!Array.isArray(vpc.service) || !Array.isArray(offering.service)) return true
  return offering.service.every(service => service.name === 'Gateway' ||
    (service.provider || []).every(provider =>
      vpc.service.some(candidate => candidate.name === service.name &&
        candidate.provider?.some(available => available.name === provider.name))))
}
