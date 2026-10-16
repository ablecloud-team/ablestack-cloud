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

import { bootReconcileObservation } from '@/utils/storageBootReconcile'

import { INSTANCE, BOOT, NOW, bootHealth } from '../fixtures/storageBootReconcile'
const parse = (health, extra = {}) => bootReconcileObservation(health, { instanceId: INSTANCE, now: NOW, ...extra })

describe('#914 boot reconcile strict public observation', () => {
  it('keeps runtime listeners ready distinct from a pending detailed NFS probe', () => {
    const result = parse(bootHealth())
    expect(result).toMatchObject({ status: 'RECONCILING', runtimeReady: true, detailedVerified: false })
    expect(result.protocols.NFS).toMatchObject({ probeState: 'PENDING', verified: false, completedProbeCount: 1, totalProbeCount: 10 })
    expect(result).not.toHaveProperty('progress')
  })
  it('normalizes only omitted nullable Gson fields without mutating the wire', () => {
    const value = bootHealth(); delete value.bootReconcile.instanceUuid; delete value.bootReconcile.lastFailure
    const raw = JSON.stringify(value); const result = parse(value)
    expect(result.instanceUuid).toBeNull(); expect(result.lastFailure).toBeNull()
    expect(JSON.stringify(value)).toBe(raw)
  })
  it.each(['schemaVersion', 'kind', 'bootId', 'desiredHash', 'status', 'phase', 'attempt', 'elapsedSeconds', 'deadlineSeconds', 'remainingSeconds', 'updatedEpoch', 'heartbeatStaleAfterSeconds', 'runtimeReady', 'detailedVerified', 'protocols'])('rejects missing required %s', key => {
    const value = bootHealth(); delete value.bootReconcile[key]; expect(parse(value)).toBeNull()
  })
  it.each([
    ['attempt', true], ['attempt', '2'], ['attempt', -1], ['attempt', 1.5],
    ['elapsedSeconds', '21'], ['elapsedSeconds', -1], ['elapsedSeconds', Infinity],
    ['deadlineSeconds', 599], ['deadlineSeconds', 1501], ['remainingSeconds', false], ['remainingSeconds', 601],
    ['updatedEpoch', NaN], ['runtimeReady', 1], ['detailedVerified', 'false'],
    ['phase', 'RAW TRACEBACK'], ['status', 'ok'], ['desiredHash', 'not-a-digest']
  ])('rejects malformed %s=%s', (key, content) => {
    const value = bootHealth(); value.bootReconcile[key] = content; expect(parse(value)).toBeNull()
  })
  it('rejects another instance, mismatched current BOOT, stale heartbeat and a failed read', () => {
    const value = bootHealth(); value.bootReconcile.instanceUuid = BOOT
    expect(parse(value)).toBeNull()
    value.bootReconcile.instanceUuid = INSTANCE; value.bootId = INSTANCE
    expect(parse(value)).toBeNull()
    value.bootId = BOOT
    expect(parse(value, { now: NOW + 16 })).toBeNull()
    expect(parse(value, { stale: true })).toBeNull()
    expect(parse(value, { readFailed: true })).toBeNull()
  })
  it('rejects success that still has pending or incomplete protocol verification', () => {
    const value = bootHealth(); value.bootReconcile.status = 'READY'; value.bootReconcile.phase = 'COMPLETE'; value.bootReconcile.detailedVerified = true
    expect(parse(value)).toBeNull()
    Object.assign(value.bootReconcile.protocols.NFS, { applyState: 'VERIFIED', probeState: 'VERIFIED', verified: true, completedProbeCount: 10, detailVerificationKind: 'MOUNT_PROBES_COMPLETE' })
    expect(parse(value).status).toBe('READY')
    value.bootReconcile.protocols.NFS.observationComplete = false
    expect(parse(value)).toBeNull()
  })
  it('does not accept a protocol count, bool, key or state that was not observed', () => {
    const value = bootHealth(); value.bootReconcile.protocols.NFS.completedProbeCount = 11
    expect(parse(value)).toBeNull()
    value.bootReconcile.protocols.NFS.completedProbeCount = 1; value.bootReconcile.protocols.NFS.listenerReady = 'true'
    expect(parse(value)).toBeNull()
    value.bootReconcile.protocols.NFS.listenerReady = true; delete value.bootReconcile.protocols.SMB
    expect(parse(value)).toBeNull()
  })
})

const declared = require('../fixtures/storageBootReconcileDeclared.json')
describe('#914 older DECLARED v1 wire does not invent unobserved qualification', () => {
  it.each(Object.keys(declared.samples))('marks old producer sample %s without new fields UNKNOWN', name => {
    const sample = declared.samples[name]
    const health = { bootId: sample.bootId, generatedEpoch: sample.updatedEpoch, staleAfterSeconds: 30, bootReconcile: sample }
    expect(bootReconcileObservation(health, { instanceId: sample.instanceUuid || '00000000-0000-0000-0000-000000000002', now: sample.updatedEpoch })).toBeNull()
  })
  it('uses the current health age for terminal freshness, not a forged terminal heartbeat', () => {
    const health = bootHealth()
    Object.assign(health.bootReconcile, { status: 'READY', phase: 'COMPLETE', detailedVerified: true, updatedEpoch: NOW - 600 })
    Object.assign(health.bootReconcile.protocols.NFS, { applyState: 'VERIFIED', probeState: 'VERIFIED', verified: true, completedProbeCount: 10, detailVerificationKind: 'MOUNT_PROBES_COMPLETE' })
    expect(parse(health).status).toBe('READY')
    expect(parse(health, { now: NOW + 31 })).toBeNull()
  })
  it('keeps past timeout history on READY but rejects raw-text codes and contradictory cleanup', () => {
    const value = bootHealth()
    Object.assign(value.bootReconcile, { status: 'READY', phase: 'COMPLETE', detailedVerified: true })
    Object.assign(value.bootReconcile.protocols.NFS, { applyState: 'VERIFIED', probeState: 'VERIFIED', verified: true, completedProbeCount: 10, detailVerificationKind: 'MOUNT_PROBES_COMPLETE' })
    value.bootReconcile.lastFailure = JSON.parse(JSON.stringify(declared.samples.listenerReadyDetailedProbePending.lastFailure))
    expect(parse(value).lastFailure.code).toBe('NFS_PROBE_TIMEOUT')
    value.bootReconcile.lastFailure.code = 'Traceback: private content'
    expect(parse(value)).toBeNull()
    value.bootReconcile.lastFailure.code = 'NFS_PROBE_TIMEOUT'; value.bootReconcile.lastFailure.cleanup.childActive = true
    expect(parse(value)).toBeNull()
  })
})

describe('#914 current monitor freshness is strict', () => {
  it.each([true, 'true', 1])('never accepts stale or malformed monitor flags %s', value => {
    const health = bootHealth(); health.cache = { stale: value }
    expect(parse(health)).toBeNull()
  })
  it('rejects nonpositive/future health times and unknown public boot fields', () => {
    const health = bootHealth(); health.generatedEpoch = 0; expect(parse(health)).toBeNull()
    health.generatedEpoch = NOW + 6; expect(parse(health)).toBeNull()
    health.generatedEpoch = NOW; health.bootReconcile.rawTraceback = 'not public'
    expect(parse(health)).toBeNull()
  })
})

describe('#914 v2 truthful absence work and qualified detailed verification', () => {
  it.each([['RECONCILING', 'APPLYING', 'NOT_STARTED'], ['FAILED', 'FAILED', 'FAILED'], ['HELD', 'HELD', 'PENDING']])('keeps unconfigured %s observation instead of erasing it as UNKNOWN', (status, applyState, probeState) => {
    const value = bootHealth()
    Object.assign(value.bootReconcile, { status, phase: status === 'RECONCILING' ? 'START_PROTOCOL_RUNTIME' : status, runtimeReady: false, detailedVerified: false })
    Object.assign(value.bootReconcile.protocols.NFS, { configured: false, applyState, probeState, listenerReady: false, observationComplete: false, verified: false, completedProbeCount: 0, totalProbeCount: 0 })
    expect(parse(value).status).toBe(status)
  })
  it('retains zero eligible mounts plus positive ACL skips as exact inventory and eligible-mount verification', () => {
    const value = bootHealth()
    Object.assign(value.bootReconcile, { status: 'READY', phase: 'COMPLETE', detailedVerified: true })
    Object.assign(value.bootReconcile.protocols.NFS, { applyState: 'VERIFIED', probeState: 'VERIFIED', verified: true, completedProbeCount: 0, totalProbeCount: 0, skippedAclCount: 3, detailVerificationKind: 'EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS' })
    const result = parse(value)
    expect(result.protocols.NFS).toMatchObject({ skippedAclCount: 3, totalProbeCount: 0, detailVerificationKind: 'EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS' })
  })
  it.each(['skippedAclCount', 'detailVerificationKind'])('never assumes a missing %s means zero or complete', key => {
    const value = bootHealth(); delete value.bootReconcile.protocols.NFS[key]
    expect(parse(value)).toBeNull()
  })
  it('keeps a pending not-configured protocol from becoming READY', () => {
    const value = bootHealth()
    Object.assign(value.bootReconcile, { status: 'READY', phase: 'COMPLETE', detailedVerified: true })
    Object.assign(value.bootReconcile.protocols.NFS, { configured: false, applyState: 'APPLYING', probeState: 'PENDING', verified: true, completedProbeCount: 0, totalProbeCount: 0, detailVerificationKind: 'NOT_REQUIRED' })
    expect(parse(value)).toBeNull()
  })
})

const declaredV2 = require('../fixtures/storageBootReconcileDeclaredV2.json')
describe('#914 actual product-generated v2 wire (DECLARED, not live acceptance)', () => {
  it.each(Object.keys(declaredV2.samples))('accepts the exact product-class sample %s including Gson nullable omission', name => {
    const sample = JSON.parse(JSON.stringify(declaredV2.samples[name]))
    delete sample.instanceUuid
    if (sample.lastFailure === null) delete sample.lastFailure
    else for (const key of Object.keys(sample.lastFailure)) if (sample.lastFailure[key] === null) delete sample.lastFailure[key]
    const health = { bootId: sample.bootId, generatedEpoch: declaredV2.declaredNowEpoch, staleAfterSeconds: 30, bootReconcile: sample }
    const raw = JSON.stringify(health)
    const result = bootReconcileObservation(health, { instanceId: INSTANCE, now: declaredV2.declaredNowEpoch })
    expect(result).toMatchObject({ status: sample.status, phase: sample.phase, runtimeReady: sample.runtimeReady, detailedVerified: sample.detailedVerified, instanceUuid: null })
    expect(result.protocols).toEqual(sample.protocols)
    expect(JSON.stringify(health)).toBe(raw)
  })
  it.each([
    ['skippedAclCount', true], ['skippedAclCount', '0'], ['skippedAclCount', -1],
    ['skippedAclCount', 1.5], ['skippedAclCount', Infinity], ['detailVerificationKind', 'MOUNT_SUCCESS']
  ])('rejects malformed v2 metadata %s=%s', (key, content) => {
    const value = bootHealth(); value.bootReconcile.protocols.NFS[key] = content
    expect(parse(value)).toBeNull()
  })
  it('rejects all-mount completion for ACL skips and all-mount completion without any eligible mount', () => {
    const sample = JSON.parse(JSON.stringify(declaredV2.samples.allAclSkippedExactInventoryOnlyNoMounts))
    const health = { bootId: sample.bootId, generatedEpoch: declaredV2.declaredNowEpoch, staleAfterSeconds: 30, bootReconcile: sample }
    const options = { instanceId: INSTANCE, now: declaredV2.declaredNowEpoch }
    sample.protocols.NFS.detailVerificationKind = 'MOUNT_PROBES_COMPLETE'
    expect(bootReconcileObservation(health, options)).toBeNull()
    sample.protocols.NFS.skippedAclCount = 0
    expect(bootReconcileObservation(health, options)).toBeNull()
  })
})
