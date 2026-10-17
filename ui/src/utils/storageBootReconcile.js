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

export const bootStatuses = ['RECONCILING', 'READY', 'DEGRADED', 'FAILED', 'HELD']
export const bootPhases = ['MOUNT_BACKING', 'RESTORE_ENDPOINTS', 'RESTORE_POSIX', 'START_PROTOCOL_RUNTIME', 'WAIT_RUNTIME_READY', 'PROBE_ENDPOINTS', 'PROBE_EXPORTS', 'APPLY_REMAINING_PROTOCOLS', 'VERIFY_ALL', 'COMPLETE', 'SKIPPED', 'HELD', 'FAILED']
export const bootProtocols = ['NFS', 'SMB', 'ISCSI', 'NVMEOF']
const applyStates = ['PENDING', 'APPLYING', 'APPLIED', 'VERIFIED', 'FAILED', 'NOT_REQUIRED', 'HELD']
export const bootVerificationKinds = ['MOUNT_PROBES_COMPLETE', 'EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS', 'EXACT_RUNTIME_READBACK', 'NOT_REQUIRED', 'UNVERIFIED']
const probeStates = ['NOT_STARTED', 'PENDING', 'VERIFIED', 'FAILED', 'NOT_REQUIRED']
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const number = value => typeof value === 'number' && Number.isFinite(value) && value >= 0
const integer = value => Number.isSafeInteger(value) && value >= 0
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 256 && Array.from(value).every(char => char.charCodeAt(0) >= 32 && char.charCodeAt(0) !== 127)
const exact = (value, keys) => plain(value) && Object.keys(value).length === keys.length && keys.every(key => Object.prototype.hasOwnProperty.call(value, key))
const nullable = (value, keys) => Object.fromEntries(keys.map(key => [key, Object.prototype.hasOwnProperty.call(value, key) ? value[key] : null]))

function protocolObservation (value, protocol, status) {
  if (!exact(value, ['configured', 'applyState', 'listenerReady', 'observationComplete', 'probeState', 'verified', 'completedProbeCount', 'totalProbeCount', 'skippedAclCount', 'detailVerificationKind']) ||
      !['configured', 'listenerReady', 'observationComplete', 'verified'].every(key => typeof value[key] === 'boolean') ||
      !applyStates.includes(value.applyState) || !probeStates.includes(value.probeState) || !bootVerificationKinds.includes(value.detailVerificationKind) ||
      !integer(value.completedProbeCount) || !integer(value.totalProbeCount) || value.completedProbeCount > value.totalProbeCount || value.totalProbeCount > 65536 ||
      !integer(value.skippedAclCount) || value.skippedAclCount > 65536) return false
  if (value.configured && (value.applyState === 'NOT_REQUIRED' || value.probeState === 'NOT_REQUIRED')) return false
  if (value.verified && (!value.observationComplete || value.completedProbeCount !== value.totalProbeCount)) return false
  // An absent protocol can still be applying an empty declaration or failing its
  // absence check. Only READY asserts that this work is finished/not required.
  if (!value.configured) {
    if (value.totalProbeCount !== 0 || value.skippedAclCount !== 0 || !['NOT_REQUIRED', 'UNVERIFIED'].includes(value.detailVerificationKind)) return false
    return status !== 'READY' || (value.applyState === 'NOT_REQUIRED' && value.probeState === 'NOT_REQUIRED' &&
      !value.listenerReady && value.observationComplete && value.verified && value.detailVerificationKind === 'NOT_REQUIRED')
  }
  if (!value.verified) return value.detailVerificationKind === 'UNVERIFIED'
  if (!value.listenerReady || value.applyState !== 'VERIFIED' || value.probeState !== 'VERIFIED') return false
  if (protocol !== 'NFS') return value.skippedAclCount === 0 && value.detailVerificationKind === 'EXACT_RUNTIME_READBACK'
  return value.detailVerificationKind === 'MOUNT_PROBES_COMPLETE'
    ? value.skippedAclCount === 0 && value.totalProbeCount > 0
    : value.detailVerificationKind === 'EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS' && value.skippedAclCount > 0
}

function failureObservation (value) {
  if (value === null) return null
  if (!plain(value)) return undefined
  const optional = ['protocol', 'endpointIp', 'endpointPort', 'exportUuid', 'exportName', 'protocolMode']
  const result = { ...value, ...nullable(value, optional) }
  if (!exact(result, [...optional, 'phase', 'code', 'timedOut', 'retryable', 'cleanup']) ||
      !(result.protocol === null || bootProtocols.includes(result.protocol)) || !bootPhases.includes(result.phase) ||
      !(result.endpointIp === null || (typeof result.endpointIp === 'string' && /^[a-fA-F0-9:.]{1,45}$/.test(result.endpointIp))) ||
      !(result.endpointPort === null || (Number.isSafeInteger(result.endpointPort) && result.endpointPort > 0 && result.endpointPort <= 65535)) ||
      !(result.exportUuid === null || uuid(result.exportUuid)) || !(result.exportName === null || text(result.exportName)) ||
      !(result.protocolMode === null || ['NFS3', 'NFS4'].includes(result.protocolMode)) ||
      typeof result.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(result.code) ||
      typeof result.timedOut !== 'boolean' || typeof result.retryable !== 'boolean' ||
      !exact(result.cleanup, ['complete', 'childActive', 'mountPresent']) ||
      !Object.values(result.cleanup).every(item => typeof item === 'boolean') ||
      (result.cleanup.complete && (result.cleanup.childActive || result.cleanup.mountPresent))) return undefined
  return result
}

// Guest listener readiness and completed detailed verification are different.
// Nullable fields omitted by Gson normalize to null; required fields never do.
export function bootReconcileObservation (health, { instanceId, now = Date.now() / 1000, stale = false, readFailed = false } = {}) {
  if (!plain(health) || !uuid(health.bootId) || !uuid(instanceId) || !number(now) || stale || readFailed ||
      ['stale', 'isStale'].some(key => (health[key] !== undefined && typeof health[key] !== 'boolean') || health[key] === true) ||
      (health.cache !== undefined && (!plain(health.cache) || ['stale', 'isStale'].some(key => (health.cache[key] !== undefined && typeof health.cache[key] !== 'boolean') || health.cache[key] === true))) ||
      !number(health.generatedEpoch) || health.generatedEpoch === 0 ||
      !number(health.staleAfterSeconds) || health.staleAfterSeconds === 0 || health.generatedEpoch > now + 5 ||
      now - health.generatedEpoch > health.staleAfterSeconds || !plain(health.bootReconcile)) return null
  const value = { ...health.bootReconcile, ...nullable(health.bootReconcile, ['instanceUuid', 'lastFailure']) }
  if (!exact(value, ['schemaVersion', 'kind', 'bootId', 'instanceUuid', 'desiredHash', 'status', 'phase', 'attempt', 'elapsedSeconds', 'deadlineSeconds', 'remainingSeconds', 'updatedEpoch', 'heartbeatStaleAfterSeconds', 'runtimeReady', 'detailedVerified', 'protocols', 'lastFailure']) ||
      value.schemaVersion !== 1 || value.kind !== 'STORAGE_BOOT_RECONCILE' || value.bootId !== health.bootId ||
      !(value.instanceUuid === null || value.instanceUuid === instanceId) ||
      typeof value.desiredHash !== 'string' || !/^[a-f0-9]{64}$/.test(value.desiredHash) ||
      !bootStatuses.includes(value.status) || !bootPhases.includes(value.phase) || !integer(value.attempt) ||
      !number(value.elapsedSeconds) || !Number.isSafeInteger(value.deadlineSeconds) || value.deadlineSeconds < 600 || value.deadlineSeconds > 1500 ||
      !number(value.remainingSeconds) || value.remainingSeconds > value.deadlineSeconds || !number(value.updatedEpoch) || value.updatedEpoch <= 0 ||
      value.updatedEpoch > now + 5 || value.heartbeatStaleAfterSeconds !== 15 ||
      typeof value.runtimeReady !== 'boolean' || typeof value.detailedVerified !== 'boolean' ||
      !exact(value.protocols, bootProtocols) || !bootProtocols.every(protocol => protocolObservation(value.protocols[protocol], protocol, value.status))) return null
  if (value.status === 'RECONCILING' && now - value.updatedEpoch > value.heartbeatStaleAfterSeconds) return null
  const configured = Object.values(value.protocols).filter(item => item.configured)
  if ((value.runtimeReady && configured.some(item => !item.listenerReady || !item.observationComplete)) ||
      (value.detailedVerified && configured.some(item => !item.verified)) ||
      (value.status === 'READY' && (!value.runtimeReady || !value.detailedVerified || !['COMPLETE', 'SKIPPED'].includes(value.phase))) ||
      (value.phase === 'SKIPPED' && (configured.length !== 0 || value.status !== 'READY'))) return null
  const lastFailure = failureObservation(value.lastFailure)
  if (lastFailure === undefined) return null
  return { ...value, protocols: Object.fromEntries(bootProtocols.map(key => [key, { ...value.protocols[key] }])), lastFailure }
}
