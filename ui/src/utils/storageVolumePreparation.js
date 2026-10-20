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

export const preparationPhases = [
  'WAITING_DEVICE', 'VERIFYING_IDENTITY', 'FORMATTING', 'VERIFYING_FILESYSTEM',
  'FILESYSTEM_VERIFIED', 'MOUNTING', 'PERSISTING_MOUNT', 'APPLYING_SERVICE',
  'COMPLETE', 'TIMED_OUT_PENDING_RECONCILE', 'RECOVERY_REQUIRED',
  'RECONCILE_REQUIRED', 'NOT_STARTED', 'IDENTITY_MISMATCH', 'ERROR'
]
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value)
export const preparationNumber = value => typeof value === 'number' && Number.isFinite(value) && value >= 0
export function preparationTime (seconds) {
  if (!preparationNumber(seconds) || seconds <= 0 || seconds > 8640000000000) return '—'
  return new Date(seconds * 1000).toISOString()
}
export function preparationCapacity (bytes) {
  if (!Number.isSafeInteger(bytes) || bytes <= 0) return '—'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']
  const power = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return (bytes / Math.pow(1024, power)).toFixed(2) + ' ' + units[power]
}
export function preparationObservation (response, instance, volume) {
  const envelope = response?.getstorageservicevolumepreparationresponse
  const body = envelope?.storageserviceruntime || envelope
  if (!plain(body) || body.id !== instance || body.success !== true) throw new Error('PREPARATION_OBSERVATION_UNAVAILABLE')
  const value = typeof body.resultjson === 'string' ? JSON.parse(body.resultjson) : body.resultjson
  if (!plain(value) || value.success !== true || !preparationPhases.includes(value.status) || value.operationId !== 'volume-' + volume) throw new Error('PREPARATION_SCOPE_INVALID')
  if (value.operation !== undefined && (!plain(value.operation) || value.operation.volumeUuid !== volume || value.operation.operationId !== value.operationId)) throw new Error('PREPARATION_JOURNAL_SCOPE_INVALID')
  if (value.currentIdentity !== undefined && (!plain(value.currentIdentity) || value.currentIdentity.volumeUuid !== volume)) throw new Error('PREPARATION_IDENTITY_SCOPE_INVALID')
  if (value.observedOperation !== undefined && (!plain(value.observedOperation) || (value.observedOperation.volumeUuid !== undefined && value.observedOperation.volumeUuid !== volume) || (value.observedOperation.operationId !== undefined && value.observedOperation.operationId !== value.operationId))) throw new Error('PREPARATION_PROJECTION_INVALID')
  return value
}
export function preparationResumeAllowed (observation) {
  const value = observation || {}
  const operation = value.operation || {}
  const identity = value.currentIdentity || {}
  return value.resumeAllowed === true && value.filesystemCompletionProven === true &&
    value.currentIdentityStatus === 'EXACT' && identity.mappingStatus === 'EXACT' && identity.matchedBy === 'VOLUME_SERIAL' &&
    value.formatterObservationComplete === true && value.formatterActive === false && value.terminationPending === false && value.stale === false &&
    typeof identity.filesystemUuid === 'string' && !!identity.filesystemUuid &&
    (!operation.filesystemUuid || operation.filesystemUuid === identity.filesystemUuid)
}
export function preparationDiagnostics (observation) {
  return [...new Set([observation?.diagnostic, observation?.operation?.diagnostic, observation?.currentIdentityDiagnostic].filter(value => typeof value === 'string' && value.trim()))].map(value => value.slice(0, 8192)).join('\n\n')
}
