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

export const ACTIVE_DR_RUN_STATES = [
  'QUEUED', 'PREPARING', 'DISPATCHING', 'ACCEPTED', 'RUNNING', 'RETRYING', 'CANCEL_REQUESTED'
]

export const ACTIVE_DR_SYNC_CYCLE_STATES = [
  'PREPARING', 'SNAPSHOTTING', 'TRANSFERRING', 'COMMITTING', 'RETRYING', 'RUNNING'
]

export function isActiveDrRun (run = {}) {
  return ACTIVE_DR_RUN_STATES.includes(String(run?.state || '').toUpperCase())
}

export function isActiveDrSyncCycle (cycle = {}) {
  return ACTIVE_DR_SYNC_CYCLE_STATES.includes(String(cycle?.state || '').toUpperCase())
}

export function reconcileDrRunProjection (snapshot = {}, liveRuns = [], options = {}) {
  const runs = Array.isArray(liveRuns)
    ? liveRuns.filter(run => run && (run.id || run.uuid))
    : []
  const authoritativeActiveRun = options.authoritativeActiveRun === true
  const snapshotActiveRun = snapshot.activeRun || {}
  const snapshotActiveRunId = String(snapshotActiveRun.id || snapshotActiveRun.uuid || '')
  const matchingLiveRun = snapshotActiveRunId
    ? runs.find(run => String(run.id || run.uuid || '') === snapshotActiveRunId)
    : null
  let activeRun = {}
  if (authoritativeActiveRun && snapshotActiveRunId) {
    activeRun = matchingLiveRun
      ? (isActiveDrRun(matchingLiveRun) ? matchingLiveRun : {})
      : snapshotActiveRun
  } else if (!authoritativeActiveRun) {
    activeRun = runs.find(run => isActiveDrRun(run)) || {}
  }
  const latestOperationRun = runs[0] || snapshot.latestOperationRun || snapshot.latestRun || {}

  return Object.assign({}, snapshot, {
    activeRun,
    activeRunSteps: activeRun.steps || [],
    latestOperationRun,
    latestOperationRunSteps: latestOperationRun.steps || []
  })
}

export function reconcileDrPlanProjection (cachedPlan = {}, livePlan = {}, options = {}) {
  const projection = Object.assign({}, cachedPlan)
  if (options.authoritativeActions !== true) {
    // The detail record is fetched after the cached protection snapshot. Let
    // every live field win so a completed resume/reconcile cannot remain
    // visually PAUSED or keep stale action gates in the UI.
    Object.keys(livePlan || {}).forEach(key => {
      projection[String(key).toLowerCase()] = livePlan[key]
    })
    return projection
  }

  [['lastrun', 'lastRun']].forEach(([normalizedKey, alternateKey]) => {
    const value = livePlan[normalizedKey] !== undefined
      ? livePlan[normalizedKey]
      : livePlan[alternateKey]
    if (value !== undefined) {
      projection[normalizedKey] = value
    }
  })
  return projection
}

export function resolveDrPlanState (plan = {}, currentRun = null) {
  const state = String(plan.state || '').toUpperCase()
  const adminState = String(plan.adminstate || plan.adminState || '').toUpperCase()
  const protection = String(plan.protectionstate || '').toUpperCase()
  if (state === 'UNPROTECTED' || protection === 'UNPROTECTED' ||
      (adminState === 'DISABLED' && state !== 'ERROR')) {
    return 'UNPROTECTED'
  }
  const latestRun = currentRun || plan.lastrun || {}
  const latestRunState = String(latestRun.state || '').toUpperCase()
  const latestRunType = String(latestRun.runtype || latestRun.runType || '').toUpperCase()
  const finiteOperationFailed = latestRunState === 'FAILED' &&
    ['TEST_FAILOVER', 'TEST_CLEANUP'].includes(latestRunType)
  const runtimeError = String(plan.runtimeerrorcode || plan.runtimeErrorCode ||
    plan.errorcode || plan.errorCode ||
    (finiteOperationFailed ? '' : latestRun.runtimeerrorcode) || '').toUpperCase()
  const schedulerHealth = String(plan.schedulerhealth || plan.schedulerHealth ||
    plan.schedulerhealthstate || plan.schedulerHealthState || '').toUpperCase()
  if (['DR_SOURCE_SITE_UNAVAILABLE', 'DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE',
    'DR_QCOW2_OFFLINE_SOURCE_BUSY'].includes(runtimeError) || schedulerHealth === 'WAITING_SOURCE') {
    return 'WAITING_SOURCE_RECOVERY'
  }
  if (['DR_RESOURCE_BUSY', 'DR_NBD_CAPACITY_INVALID', 'DR_TARGET_EXPORT_UNAVAILABLE'].includes(runtimeError) ||
      schedulerHealth === 'WAITING_RESOURCE') {
    return 'WAITING_RESOURCE'
  }
  const replicationActivity = String(plan.replicationactivity || plan.replicationActivity || '').toUpperCase()
  if (runtimeError === 'DR_CBT_RESEED_REQUIRED' || schedulerHealth === 'RECOVERING_BASELINE' || replicationActivity === 'RESEEDING') {
    return 'RECOVERING_BASELINE'
  }
  const recoveryState = String(plan.schedulerrecoverystate || plan.schedulerRecoveryState || '').toUpperCase()
  if (['PENDING', 'RECOVERING'].includes(recoveryState)) {
    return recoveryState === 'PENDING' ? 'RECOVERY_PENDING' : 'RECOVERING'
  }
  if (recoveryState === 'FAILED') {
    return 'RECOVERY_FAILED'
  }
  const protectionPhase = String(plan.protectionphase || plan.protectionPhase || '').toUpperCase()
  const operatingSide = String(plan.operatingside || plan.operatingSide || plan.activeside || '').toUpperCase()
  if (operatingSide === 'TARGET' && protectionPhase) {
    return protectionPhase
  }
  const ownerMatched = plan.ownermatched
  if (['DEAD', 'OWNER_MISMATCH', 'DUPLICATE_WORKER', 'STALE'].includes(schedulerHealth) ||
      (schedulerHealth && ownerMatched === false)) {
    return 'DEGRADED'
  }
  if (protection) {
    return protection
  }
  const effective = String(plan.effectivestate || '').toUpperCase()
  if (effective) {
    return effective
  }
  const runtime = String(plan.runtimestate || latestRun.runtimestate || '').toUpperCase()
  const worker = String(latestRun.workerstate || '').toUpperCase()
  const runError = latestRunState === 'FAILED' && !finiteOperationFailed ? latestRun.errorcode : null
  if (finiteOperationFailed && state) {
    return state
  }
  if (['ERROR', 'FAILED'].includes(runtime) || worker === 'FAILED' || runtimeError || runError) {
    return 'ERROR'
  }
  const readiness = String(plan.readinessstate || '').toUpperCase()
  const materialization = String(plan.targetmaterializationstate || '').toUpperCase()
  if (readiness === 'TARGET_READY' || materialization === 'TARGET_READY') {
    return 'READY'
  }
  if (readiness === 'TARGET_MATERIALIZING' || materialization === 'TARGET_MATERIALIZING') {
    return state === 'READY' ? 'SYNCING' : 'TARGET_MATERIALIZING'
  }
  if (readiness === 'ENGINE_ACCEPTED') {
    return 'ACCEPTED'
  }
  if (readiness === 'DEGRADED') {
    return 'DEGRADED'
  }
  return state || readiness || 'UNKNOWN'
}

export function resolveDrReadinessState (plan = {}) {
  const readiness = String(plan.readinessstate || plan.readinessState || '').toUpperCase()
  return readiness || resolveDrPlanState(plan)
}

export function resolveDrPlanSeverity (plan = {}, currentRun = null) {
  const typed = String(plan.currentseverity || plan.currentSeverity || '').toUpperCase()
  if (['ERROR', 'WARNING', 'INFO', 'NONE'].includes(typed)) {
    return typed
  }
  const run = currentRun || {}
  if (String(plan.projectionintegritystate || '').toUpperCase() === 'INCONSISTENT' ||
      String(run.state || '').toUpperCase() === 'FAILED' ||
      ['ERROR', 'FAILED'].includes(String(plan.protectionstate || plan.effectivestate || '').toUpperCase())) {
    return 'ERROR'
  }
  if (['DEGRADED', 'RPO_EXCEEDED', 'STALE'].includes(String(plan.protectionstate || '').toUpperCase())) {
    return 'WARNING'
  }
  if (String(plan.protectionphase || '').toUpperCase() === 'FAILED_OVER_UNPROTECTED') {
    return 'INFO'
  }
  return 'NONE'
}

export function resolveDrTestCleanupState (plan = {}, latestRun = null) {
  const run = latestRun || plan.lastrun || {}
  const runType = String(run.runtype || run.runType || run.action || '').toUpperCase()
  const runState = String(run.state || '').toUpperCase()
  if (runType !== 'TEST_CLEANUP' || runState !== 'SUCCEEDED') {
    return ''
  }

  const resumeState = resolveDrReplicationResumeState(plan)
  const severity = resolveDrPlanSeverity(plan)
  if (['RESUMED', 'RUNNING'].includes(resumeState)) {
    return 'CLEANED_PROTECTION_READY'
  }
  if (resumeState === 'DEGRADED' || severity === 'ERROR') {
    return 'CLEANED_PROTECTION_DEGRADED'
  }
  return 'CLEANED_PROTECTION_RESUMING'
}

export function resolveDrRpoPresentation (plan = {}) {
  const mode = String(plan.rpoevaluationmode || plan.rpoEvaluationMode || 'LIVE').toUpperCase()
  const seconds = plan.displayrposeconds !== undefined && plan.displayrposeconds !== null
    ? plan.displayrposeconds
    : plan.rpoageseconds !== undefined && plan.rpoageseconds !== null
      ? plan.rpoageseconds
      : plan.targetreadyrposeconds
  return {
    mode,
    seconds,
    asOf: plan.rpoasof || plan.rpoAsOf || null,
    status: String(plan.rpostatus || plan.rpoStatus || 'UNKNOWN').toUpperCase()
  }
}

export function hasDrSourceAuthority (plan = {}) {
  const side = String(plan.operatingside || plan.operatingSide || plan.activeside || plan.activeSide || 'SOURCE').toUpperCase()
  const state = String(plan.state || '').toUpperCase()
  return side !== 'TARGET' && state !== 'FAILED_OVER'
}

export function resolveDrReplicationResumeState (plan = {}) {
  const recoveryState = String(plan.schedulerrecoverystate || plan.schedulerRecoveryState || '').toUpperCase()
  const schedulerHealth = String(plan.schedulerhealth || '').toUpperCase()
  const schedulerState = String(plan.schedulerstate || '').toUpperCase()
  const protectionState = String(plan.protectionstate || '').toUpperCase()
  const replicationActivity = String(plan.replicationactivity || '').toUpperCase()
  const controlState = String(plan.controlstate || '').toUpperCase()
  if (['DEAD', 'OWNER_MISMATCH', 'DUPLICATE_WORKER', 'STALE'].includes(schedulerHealth) || plan.ownermatched === false) {
    return 'DEGRADED'
  }
  if (recoveryState === 'REQUIRED') {
    return 'RECOVERY_REQUIRED'
  }
  if (protectionState === 'PAUSED' || schedulerState === 'PAUSED' || controlState === 'PAUSED') {
    return 'PAUSED'
  }
  if (schedulerHealth === 'HEALTHY' && schedulerState === 'RUNNING' && plan.ownermatched !== false) {
    return ['RUNNING', 'TRANSFERRING', 'CHECKPOINTING'].includes(replicationActivity) ? 'RUNNING' : 'RESUMED'
  }
  return 'UNKNOWN'
}
