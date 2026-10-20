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

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const states = new Set(['SKIPPED_ACL', 'SKIPPED_DYNAMIC'])
export const nfsProbeSkipReasons = ['ACL_RESTRICTED_NO_LOCAL_SOURCE', 'DBUS_DYNAMIC_UPDATE_VERIFIED']
const unknown = endpoint => ({ endpoint, state: 'UNKNOWN', reason: '', clients: [] })
const endpointName = value => typeof value.listenIp === 'string' && value.listenIp.length > 0 && value.listenIp.length <= 64 &&
  Number.isInteger(value.port) && value.port > 0 && value.port <= 65535 ? value.listenIp + ':' + value.port : '—'
const clients = value => Array.isArray(value) && value.length > 0 && value.length <= 4096 &&
  value.every(item => typeof item === 'string' && item.length > 0 && item.length <= 256 && Array.from(item).every(char => char.charCodeAt(0) > 31 && char.charCodeAt(0) !== 127))
const modes = value => value === 'V3V4_DUAL' ? ['NFSv4', 'NFSv3'] : value === 'V4_ONLY' ? ['NFSv4'] : []

// Native verifies the current configuration, process identity, owned listener and
// exact DBus export inventory before projecting this observation into health.
// DB Ready and an empty/legacy probe response are never local mount evidence.
export function storageNfsProbeRows (health, exportUuid, { stale = false, readFailed = false } = {}) {
  const empty = [unknown('—')]
  if (stale || readFailed || typeof exportUuid !== 'string' || !exportUuid ||
      !object(health) || !object(health.nfsGanesha) || !Array.isArray(health.nfsGanesha.endpoints)) return empty
  const rows = []
  const names = new Set()
  for (const endpoint of health.nfsGanesha.endpoints) {
    if (!object(endpoint) || !Array.isArray(endpoint.probeResults)) continue
    const matches = endpoint.probeResults.filter(row => object(row) && row.exportUuid === exportUuid)
    if (!matches.length) continue
    const name = endpointName(endpoint)
    if (names.has(name)) return empty
    names.add(name)
    const result = unknown(name)
    rows.push(result)
    if (name === '—' || matches.length !== 1 || endpoint.probeObservationVerified !== true) continue
    const probe = matches[0]
    // Preserve real failures even when the saved row also carries a skip reason.
    if (probe.success === false || (Array.isArray(probe.probes) && probe.probes.some(value => object(value) && value.success === false))) {
      result.state = 'FAILED'
      continue
    }
    if (endpoint.processRunning !== true || endpoint.listening !== true ||
        endpoint.listenerOwned !== true || endpoint.exportInventoryReady !== true ||
        probe.success !== true || !clients(probe.clients)) continue
    if (probe.probeSkipped === true) {
      const expected = { SKIPPED_ACL: 'ACL_RESTRICTED_NO_LOCAL_SOURCE', SKIPPED_DYNAMIC: 'DBUS_DYNAMIC_UPDATE_VERIFIED' }
      if (!states.has(probe.status) || probe.skipReason !== expected[probe.status] ||
          !Array.isArray(probe.probes) || probe.probes.length) continue
      Object.assign(result, { state: probe.status, reason: probe.skipReason, clients: [...probe.clients] })
      continue
    }
    if (probe.probeSkipped !== undefined && probe.probeSkipped !== false) continue
    if (probe.skipReason !== undefined && probe.skipReason !== null) continue
    if (probe.status !== undefined && !['VERIFIED', 'VISIBLE'].includes(probe.status)) continue
    const expected = modes(probe.protocolMode)
    if (!expected.length || !Array.isArray(probe.probes) || probe.probes.length !== expected.length ||
        new Set(probe.probes.map(value => value?.mode)).size !== expected.length ||
        !expected.every(mode => probe.probes.some(value => object(value) && value.mode === mode &&
          value.success === true && value.cleanupComplete === true))) continue
    Object.assign(result, { state: 'VERIFIED', clients: [...probe.clients] })
  }
  return rows.length ? rows : empty
}
