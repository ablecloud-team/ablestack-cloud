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

import { getAPI } from '@/api'
import { storageReadDeadline } from '@/utils/storageRead'

export const SMB_CURRENT_RETAIN_MODE = 'RETAIN_CURRENT_LOCAL_IDENTITY_RESTORE_SOURCE_CONFIG'
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const digest = /^[a-f0-9]{64}$/
const publicFactsKeys = ['bootId', 'databaseCount', 'loadedDaemonSidVerified', 'namespaceObserved', 'ownedMasterCount', 'sessionsVerifiedEmpty']
const repairParameters = ['instanceid', 'operationid', 'recoverymode', 'currentreviewhash', 'maintenancewindow', 'confirmation', 'expectedrevision', 'idempotencykey']

export function supportsSmbCurrentRecovery (apis, getParams) {
  return !!apis?.reviewStorageServiceSmbIdentityRecovery && !!apis?.repairStorageServiceSmbIdentity &&
    ['instanceid', 'operationid'].every(name => !!getParams?.('reviewStorageServiceSmbIdentityRecovery')?.[name]) &&
    repairParameters.every(name => !!getParams?.('repairStorageServiceSmbIdentity')?.[name])
}

export function requireSmbCurrentRecoveryReview (value, expected, now = Date.now() / 1000) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || value.kind !== 'CURRENT_LOCAL_SMB_IDENTITY_RECOVERY_REVIEW' ||
    value.schemaVersion !== 1 || value.success !== true || value.sideEffects !== false || value.maintenanceRequired !== true ||
    !uuid.test(expected?.instanceId || '') || !uuid.test(expected?.operationId || '') || !Number.isSafeInteger(expected?.revision) ||
    typeof value.generatedEpoch !== 'number' || !Number.isFinite(value.generatedEpoch) || now - value.generatedEpoch > 60 || value.generatedEpoch - now > 5 ||
    value.scope?.instanceUuid !== expected.instanceId || value.scope?.operationUuid !== expected.operationId || value.scope?.revision !== expected.revision ||
    !Number.isSafeInteger(value.committedRevision) || value.committedRevision < 0 || value.committedRevision !== expected.revision - 1 ||
    !digest.test(value.sourceConfigurationSha256 || '') || !digest.test(value.currentReviewHash || '') ||
    typeof value.approvalStored !== 'boolean' || typeof value.resumeAllowed !== 'boolean' || value.approvalStored !== value.resumeAllowed || typeof value.recoveryPhase !== 'string' || !value.recoveryPhase) {
    throw new Error('SMB_CURRENT_RECOVERY_REVIEW_UNVERIFIED')
  }
  const facts = value.publicFacts
  if (!facts || typeof facts !== 'object' || Array.isArray(facts) || JSON.stringify(Object.keys(facts).sort()) !== JSON.stringify(publicFactsKeys) ||
    !uuid.test(facts.bootId || '') || facts.databaseCount !== 2 || !Number.isSafeInteger(facts.ownedMasterCount) || facts.ownedMasterCount < 1 ||
    facts.sessionsVerifiedEmpty !== true || facts.namespaceObserved !== true || facts.loadedDaemonSidVerified !== false) {
    throw new Error('SMB_CURRENT_RECOVERY_REVIEW_UNVERIFIED')
  }
  return value
}

export function smbCurrentRecoveryReviewPayload (response) {
  const row = response?.reviewstorageservicesmbidentityrecoveryresponse
  if (!row || row.success !== true || row.status !== 'OBSERVED') throw new Error('SMB_CURRENT_RECOVERY_REVIEW_UNVERIFIED')
  try {
    return typeof row.resultjson === 'string' ? JSON.parse(row.resultjson) : row.resultjson
  } catch (error) {
    throw new Error('SMB_CURRENT_RECOVERY_REVIEW_UNVERIFIED')
  }
}

export async function readSmbCurrentRecoveryReview (expected) {
  const response = await storageReadDeadline(getAPI('reviewStorageServiceSmbIdentityRecovery', {
    instanceid: expected.instanceId, operationid: expected.operationId
  }, { preserveOnFailure: true, timeout: 60000 }), 60000)
  return requireSmbCurrentRecoveryReview(smbCurrentRecoveryReviewPayload(response), expected)
}

export function smbCurrentRecoveryParameters (review, expected, approval) {
  requireSmbCurrentRecoveryReview(review, expected)
  if (approval?.maintenancewindow !== true || typeof expected.instanceName !== 'string' || !expected.instanceName || approval.confirmation !== expected.instanceName) {
    throw new Error('SMB_CURRENT_RECOVERY_APPROVAL_REQUIRED')
  }
  return {
    instanceid: expected.instanceId,
    operationid: expected.operationId,
    recoverymode: SMB_CURRENT_RETAIN_MODE,
    currentreviewhash: review.currentReviewHash,
    maintenancewindow: true,
    confirmation: approval.confirmation,
    expectedrevision: review.committedRevision,
    idempotencykey: 'smb-current-retain:' + expected.operationId + ':' + review.currentReviewHash
  }
}

export function requireSmbCurrentRecoveryResult (value, expected, currentReviewHash, status) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || value.success !== true || status !== 'ROLLED_BACK' || value.currentReviewHash !== currentReviewHash || value.phase !== 'CURRENT_LOCAL_IDENTITY_RETAINED_SOURCE_CONFIG_ROLLED_BACK' ||
    value.scope?.instanceUuid !== expected.instanceId || value.scope?.operationUuid !== expected.operationId || value.scope?.revision !== expected.revision ||
    value.currentIdentityRetained !== true ||
    value.originalIdentityRestored !== false || value.originalConfigurationSourceRestored !== true || value.dataChanged !== false) {
    throw new Error('SMB_CURRENT_RECOVERY_RESULT_UNVERIFIED')
  }
  return value
}
