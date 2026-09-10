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

import DrProtectionInfoTab from '@/views/infra/dr/DrProtectionInfoTab.vue'

describe('DrProtectionInfoTab terminal projection', () => {
  const projection = DrProtectionInfoTab.computed.protectionPlan

  it('prefers a succeeded failback run over stale syncing runtime state', () => {
    const result = projection.call({
      plan: { state: 'READY', name: 'plan-a' },
      currentRun: { runtype: 'FAILBACK', state: 'SUCCEEDED' },
      currentProtectionRuntime: {
        state: 'SYNCING',
        runtimestate: 'SYNCING',
        runtimestep: 'protection-resuming',
        runtimeprogress: 40,
        latestcompletedtransferpayloadbytes: 4096
      }
    })

    expect(result.state).toBe('READY')
    expect(result.runtimestate).toBe('READY')
    expect(result.runtimestep).toBe('target-checkpoint-ready')
    expect(result.runtimeprogress).toBe(100)
    expect(result.runtimefailbackphase).toBe('COMPLETED')
    expect(result.latestcompletedtransferpayloadbytes).toBe(4096)
  })

  it('keeps live runtime state while failback is still running', () => {
    const result = projection.call({
      plan: { state: 'SYNCING' },
      currentRun: { runtype: 'FAILBACK', state: 'RUNNING' },
      currentProtectionRuntime: { runtimestate: 'SYNCING', runtimeprogress: 40 }
    })

    expect(result.runtimestate).toBe('SYNCING')
    expect(result.runtimeprogress).toBe(40)
  })

  it('uses the latest completed cycle for idle transfer and commit evidence', () => {
    const latestCycle = {
      id: 'cycle-299',
      sequence: 299,
      state: 'READY',
      commitstate: 'LOCAL_DURABLE',
      transferpayloadbytes: 18939904
    }
    const transferBytes = DrProtectionInfoTab.computed.displayTransferPayloadBytes.call({
      hasActiveSyncCycle: false,
      latestCompletedSyncCycle: latestCycle,
      currentProtectionRuntime: { transferpayloadbytes: 6684672 },
      currentRun: {},
      currentSyncCycle: {}
    })
    const durable = DrProtectionInfoTab.computed.completedCycleDurable.call({
      hasActiveSyncCycle: false,
      latestCompletedSyncCycle: latestCycle
    })

    expect(transferBytes).toBe(18939904)
    expect(durable).toBe(true)
  })

  it('displays the Cloud canonical cycle sequence after worker relocation', () => {
    const sequence = DrProtectionInfoTab.computed.latestCompletedCycleSequence.call({
      latestCompletedSyncCycle: { sequence: 16, canonicalsequence: 758 }
    })

    expect(sequence).toBe(758)
  })

  it('shows the latest durable cycle while source placement is recovering', () => {
    const context = {
      plan: { runtimeerrorcode: 'DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE' },
      protectionPlan: { runtimeerrorcode: 'DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE' },
      currentRun: { id: 'stale-run', state: 'RUNNING' },
      currentSyncCycle: { id: 'stale-cycle', state: 'TRANSFERRING' },
      latestCompletedSyncCycle: { id: 'cycle-803', nbdteardownstate: 'DRAINED' },
      currentProtectionRuntime: { nbdteardownstate: 'FAILED' }
    }
    const recovering = DrProtectionInfoTab.computed.placementRecoveryActive.call(context)
    const active = DrProtectionInfoTab.computed.hasActiveSyncCycle.call({
      placementRecoveryActive: recovering,
      currentSyncCycle: context.currentSyncCycle
    })
    const nbd = DrProtectionInfoTab.computed.currentNbdTeardownState.call({
      placementRecoveryActive: recovering,
      latestCompletedSyncCycle: context.latestCompletedSyncCycle,
      currentProtectionRuntime: context.currentProtectionRuntime,
      currentSyncCycle: context.currentSyncCycle
    })

    expect(recovering).toBe(true)
    expect(active).toBe(false)
    expect(nbd).toBe('DRAINED')
  })

  it('normalizes serialized recovery fields and hides a superseded hardware error', () => {
    const context = {
      protectionPlan: { state: 'ERROR' },
      currentRun: { state: 'FAILED', runtype: 'RECOVER_SYNC' },
      currentProtectionRuntime: {
        errorcode: 'DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE',
        schedulerhealthstate: 'WAITING_SOURCE'
      }
    }
    const recovering = DrProtectionInfoTab.computed.placementRecoveryActive.call(context)
    const displayedError = DrProtectionInfoTab.computed.displayLastError.call({
      placementRecoveryActive: recovering,
      lastError: 'SOURCE_HARDWARE_CHANGED: stale placement-bound fingerprint'
    })

    expect(recovering).toBe(true)
    expect(displayedError).toBe('')
  })

  it('hides stale failure metadata for a completed failback session', () => {
    const succeeded = DrProtectionInfoTab.computed.failbackTerminalSucceeded.call({
      failbackSession: { state: 'COMPLETED', failedcomponent: 'ftctl' }
    })
    const visible = DrProtectionInfoTab.computed.hasFailbackFailureMetadata.call({
      failbackTerminalSucceeded: succeeded,
      failbackSession: { state: 'COMPLETED', failedcomponent: 'ftctl' }
    })

    expect(succeeded).toBe(true)
    expect(visible).toBe(false)
  })

  it('identifies a pristine new plan as waiting for initial synchronization', () => {
    const pending = DrProtectionInfoTab.computed.initialSyncPending.call({
      plan: { state: 'NEW' },
      currentRun: {},
      currentSyncCycle: {},
      latestCompletedCheckpoint: {},
      replicas: []
    })

    expect(pending).toBe(true)
  })

  it('does not call an active or previously materialized plan initial', () => {
    const pending = DrProtectionInfoTab.computed.initialSyncPending.call({
      plan: { state: 'NEW' },
      currentRun: { id: 'run-1' },
      currentSyncCycle: {},
      latestCompletedCheckpoint: {},
      replicas: []
    })

    expect(pending).toBe(false)
  })
})
