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

const ipv4 = value => {
  if (typeof value !== 'string') return null
  const parts = value.split('.')
  if (parts.length !== 4 || !parts.every(part => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)) return null
  return parts.reduce((result, part) => result * 256 + Number(part), 0)
}

// Match SharedFSServiceImpl: /31 and /32 allow no gateway, but their endpoints
// are still rejected as routers. DNS and the gateway remain optional.
export function validSharedFsGateway (ipcidr, gateway) {
  if (gateway === undefined || gateway === null || (typeof gateway === 'string' && gateway.trim() === '')) return true
  if (typeof ipcidr !== 'string' || typeof gateway !== 'string') return false
  const [ip, prefix, ...extra] = ipcidr.trim().split('/')
  const address = ipv4(ip)
  const router = ipv4(gateway.trim())
  if (extra.length || address === null || router === null || !/^\d+$/.test(prefix || '') || Number(prefix) > 32) return false
  const span = Math.pow(2, 32 - Number(prefix))
  const network = Math.floor(address / span) * span
  const broadcast = network + span - 1
  return Math.floor(router / span) * span === network && router !== address && router !== network && router !== broadcast &&
    router !== 0 && Math.floor(router / 16777216) !== 127 && Math.floor(router / 65536) !== 0xa9fe && Math.floor(router / 268435456) < 14
}

export const networkMismatchCodes = [
  'INVALID_STATE', 'INTERFACE_UNAVAILABLE', 'ADDRESS_MISMATCH', 'GATEWAY_MISMATCH',
  'UNEXPECTED_DEFAULT_ROUTE', 'DNS_MISMATCH', 'DHCP_UNIT_NOT_MASKED',
  'DHCP_CLIENT_RUNNING', 'DHCP_ADDRESS_UNAVAILABLE', 'OBSERVATION_UNAVAILABLE',
  'CONNECTED_ROUTE_MISMATCH', 'DHCP_UNIT_NOT_RUNNING'
]
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const nullableText = value => value === null || (typeof value === 'string' && value.length > 0)
const nullableBoolean = value => value === null || typeof value === 'boolean'
const textList = value => Array.isArray(value) && value.every(item => typeof item === 'string' && item.length > 0)
const mode = value => ['STATIC', 'DHCP', 'UNKNOWN'].includes(value)

// Validate the public observation shape only. Native is the authority for route,
// DHCP and DNS consistency; malformed or contradictory success is never healthy.
export function storageNetworkObservation (value) {
  if (!plain(value) || value.schemaVersion !== 1 || value.kind !== 'SHAREDFS_NETWORK_READINESS' ||
      !['OK', 'ERROR', 'UNKNOWN'].includes(value.status) || typeof value.success !== 'boolean' ||
      !mode(value.mode) || typeof value.observedEpoch !== 'number' || !Number.isFinite(value.observedEpoch) ||
      value.observedEpoch <= 0 || value.observedEpoch > 8640000000000 || !nullableText(value.bootId) ||
      !plain(value.expected) || !plain(value.actual) || !textList(value.mismatches)) return null
  // The registered health wire omits null expected values. Keep this limited to
  // each explicit mode's optional configuration, never missing observed fields.
  const optional = value.expected.mode === 'DHCP' ? ['macAddress', 'ipAddress', 'cidr', 'gateway']
    : value.expected.mode === 'STATIC' ? ['gateway'] : []
  const omitted = optional.filter(key => !Object.prototype.hasOwnProperty.call(value.expected, key))
  let expected = value.expected
  if (omitted.length) {
    expected = { ...expected }
    for (const key of omitted) expected[key] = null
  }
  const actual = value.actual
  if (!mode(expected.mode) || !mode(actual.mode) ||
      !['macAddress', 'ipAddress', 'cidr', 'gateway'].every(key => nullableText(expected[key])) ||
      !textList(expected.dns) || !['interfaceName', 'macAddress'].every(key => nullableText(actual[key])) ||
      !Array.isArray(actual.addresses) || !actual.addresses.every(address => plain(address) &&
        typeof address.ipAddress === 'string' && address.ipAddress.length > 0 &&
        Number.isInteger(address.prefixLength) && address.prefixLength >= 0 && address.prefixLength <= 32) ||
      !Array.isArray(actual.defaultRoutes) || !actual.defaultRoutes.every(route => plain(route) &&
        nullableText(route.gateway) && typeof route.interfaceName === 'string' && route.interfaceName.length > 0) ||
      !textList(actual.dns) || !plain(actual.dhcpUnit) || !nullableText(actual.dhcpUnit.name) ||
      !nullableBoolean(actual.dhcpUnit.active) || !nullableBoolean(actual.dhcpUnit.masked) ||
      !(actual.dhcpProcessCount === null || (Number.isInteger(actual.dhcpProcessCount) && actual.dhcpProcessCount >= 0))) return null
  if ((value.status === 'OK') !== value.success || (value.status === 'OK' && (value.mismatches.length ||
      value.mode === 'UNKNOWN' || expected.mode === 'UNKNOWN' || actual.mode === 'UNKNOWN' || value.mode !== expected.mode || actual.mode !== value.mode ||
      actual.interfaceName === null || actual.macAddress === null || actual.addresses.length === 0 ||
      actual.dhcpUnit.active === null || actual.dhcpUnit.masked === null || actual.dhcpProcessCount === null))) return null
  return omitted.length ? { ...value, expected } : value
}

export function storageNetworkStatus (value, { stale = false, readFailed = false } = {}) {
  const observation = storageNetworkObservation(value)
  if (!observation || stale || readFailed || observation.mismatches.some(code => !networkMismatchCodes.includes(code))) return 'UNKNOWN'
  return observation.status
}

export function storageNetworkObservedTime (value) {
  const observation = storageNetworkObservation(value)
  return observation ? new Date(observation.observedEpoch * 1000).toISOString() : '—'
}
