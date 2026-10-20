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

export const INSTANCE = '11111111-1111-4111-8111-111111111111'
export const BOOT = '22222222-2222-4222-8222-222222222222'
export const NOW = 1792200000
export const bootHealth = () => ({
  bootId: BOOT,
  generatedEpoch: NOW,
  staleAfterSeconds: 30,
  success: false,
  status: 'reconciling',
  bootReconcile: {
    schemaVersion: 1,
    kind: 'STORAGE_BOOT_RECONCILE',
    bootId: BOOT,
    instanceUuid: INSTANCE,
    desiredHash: 'a'.repeat(64),
    status: 'RECONCILING',
    phase: 'PROBE_EXPORTS',
    attempt: 2,
    elapsedSeconds: 21.5,
    deadlineSeconds: 600,
    remainingSeconds: 578.5,
    updatedEpoch: NOW,
    heartbeatStaleAfterSeconds: 15,
    runtimeReady: true,
    detailedVerified: false,
    protocols: {
      NFS: { configured: true, applyState: 'APPLIED', listenerReady: true, observationComplete: true, probeState: 'PENDING', verified: false, completedProbeCount: 1, totalProbeCount: 10, skippedAclCount: 0, detailVerificationKind: 'UNVERIFIED' },
      SMB: { configured: true, applyState: 'VERIFIED', listenerReady: true, observationComplete: true, probeState: 'VERIFIED', verified: true, completedProbeCount: 7, totalProbeCount: 7, skippedAclCount: 0, detailVerificationKind: 'EXACT_RUNTIME_READBACK' },
      ISCSI: { configured: false, applyState: 'NOT_REQUIRED', listenerReady: false, observationComplete: true, probeState: 'NOT_REQUIRED', verified: true, completedProbeCount: 0, totalProbeCount: 0, skippedAclCount: 0, detailVerificationKind: 'NOT_REQUIRED' },
      NVMEOF: { configured: false, applyState: 'NOT_REQUIRED', listenerReady: false, observationComplete: true, probeState: 'NOT_REQUIRED', verified: true, completedProbeCount: 0, totalProbeCount: 0, skippedAclCount: 0, detailVerificationKind: 'NOT_REQUIRED' }
    },
    lastFailure: null
  }
})
