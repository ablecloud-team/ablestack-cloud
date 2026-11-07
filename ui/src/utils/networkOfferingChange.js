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

export function networkOfferingScope (resource = {}, projectId = '') {
  return JSON.stringify([resource.id, resource.zoneid, resource.type, resource.networkofferingid,
    resource.account, resource.domainid, resource.projectid, projectId])
}

export function networkOfferingCandidateParams (resource) {
  return { networkid: resource.id, zoneid: resource.zoneid, state: 'Enabled', guestiptype: resource.type, forvpc: !!resource.vpcid }
}

export async function readNetworkOfferingCandidates (getAPI, resource) {
  const items = []
  const seen = new Set()
  let page = 1
  let total
  do {
    const json = await getAPI('listNetworkOfferings', { ...networkOfferingCandidateParams(resource), page, pagesize: 500 }, { preserveOnFailure: true })
    const response = json.listnetworkofferingsresponse
    if (!response || response.errorcode) throw new Error(response?.errortext || 'Invalid network offering response')
    const next = response.networkoffering || []
    total = response.count ?? next.length
    if (!next.length && items.length < total) throw new Error('Incomplete network offering response')
    for (const item of next) {
      if (!item.id || seen.has(item.id)) throw new Error('Network offering results changed while paging')
      seen.add(item.id)
    }
    items.push(...next)
    page++
  } while (items.length < total)
  return [...new Map(items.filter(item => item.id && item.state === 'Enabled').map(item => [item.id, item])).values()]
}

export function networkOfferingFacts (offering = {}) {
  const services = [...(offering.service || [])].sort((a, b) => a.name.localeCompare(b.name))
  return {
    name: offering.displaytext || offering.name || offering.id || '—',
    id: offering.id || '—',
    state: offering.state || '—',
    guestiptype: offering.guestiptype || '—',
    forvpc: offering.forvpc === undefined ? '—' : String(offering.forvpc),
    internetprotocol: offering.internetprotocol || 'IPv4',
    ip4routing: offering.ip4routing || '—',
    services: services.map(service => service.name).join(', ') || '—',
    providers: services.map(service => `${service.name}: ${(service.provider || []).map(provider => provider.name).sort().join(', ') || '—'}`).join('; ') || '—',
    sourcenat: services.some(service => service.name === 'SourceNat') ? 'true' : 'false'
  }
}

export function networkOfferingFingerprint (offering) {
  // Include capabilities too: a concurrently edited candidate needs a fresh review.
  return JSON.stringify({
    ...networkOfferingFacts(offering),
    service: (offering.service || []).map(service => ({
      name: service.name,
      providers: (service.provider || []).map(provider => provider.name).sort(),
      capabilities: (service.capability || []).map(capability => [capability.name, capability.value]).sort()
    })).sort((a, b) => a.name.localeCompare(b.name)),
    persistent: offering.ispersistent,
    conserve: offering.conservemode,
    networkrate: offering.networkrate,
    serviceofferingid: offering.serviceofferingid
  })
}
