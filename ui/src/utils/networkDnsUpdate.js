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

const families = [
  { key: 'ipv4', fields: ['dns1', 'dns2'] },
  { key: 'ipv6', fields: ['ip6dns1', 'ip6dns2'] }
]
export const networkDnsFields = families.flatMap(family => family.fields)

function dnsValue (value, ipv6) {
  const text = String(value ?? '').trim()
  if (ipv6 && text) {
    // Compare equivalent compressed/expanded IPv6 literals without a DNS lookup.
    try { return new URL(`http://[${text}]/`).hostname.slice(1, -1).toLowerCase() } catch (_) { return text.toLowerCase() }
  }
  return text
}

export function networkDnsSupport (resource = {}, offering = {}, apiParams = {}) {
  const supported = ['Isolated', 'Shared'].includes(resource.type) && !resource.vpcid &&
    (offering.service || []).some(service => service.name === 'Dns')
  const ipv6 = ['IPv6', 'DualStack'].includes(offering.internetprotocol) || (resource.type === 'Shared' && !!resource.ip6cidr)
  return {
    ipv4: supported && families[0].fields.every(field => field in apiParams),
    ipv6: supported && !!ipv6 && families[1].fields.every(field => field in apiParams)
  }
}

export function buildNetworkDnsParams (form, original, support) {
  const params = {}
  for (const { key, fields } of families) {
    if (!support[key]) continue
    const ipv6 = key === 'ipv6'
    const changed = fields.some(field => form[field] !== undefined && form[field] !== null &&
      dnsValue(form[field], ipv6) !== dnsValue(original[field], ipv6))
    if (!changed) continue
    // Undefined is untouched; empty is an explicit reset and must reach the API.
    for (const field of fields) params[field] = dnsValue(form[field] ?? original[field], ipv6)
  }
  return params
}

export function networkDnsNeedsZone (params) {
  return families.some(({ fields }) => params[fields[0]] === '')
}

export function networkDnsMatches (network, params, zone = {}) {
  return families.every(({ key, fields }) => {
    if (!(fields[0] in params)) return true
    // Cloud resolves DNS as a pair: a blank primary inherits the zone pair.
    const expected = params[fields[0]] === '' ? zone : params
    return fields.every(field => dnsValue(network[field], key === 'ipv6') === dnsValue(expected[field], key === 'ipv6'))
  })
}
