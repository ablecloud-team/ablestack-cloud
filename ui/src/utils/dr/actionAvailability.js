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

import { normalizeActionEligibility } from '@/api/dr'

const ACTIVE_RUN_STATES = ['QUEUED', 'PREPARING', 'DISPATCHING', 'ACCEPTED', 'RUNNING', 'RETRYING', 'CANCEL_REQUESTED']
export const DR_ACTION_CUTOVER_NOT_READY = 'DR_ACTION_CUTOVER_NOT_READY'

export function normalizeActionAvailability (availability = {}) {
  const normalized = {}
  Object.keys(availability || {}).forEach(key => {
    const value = availability[key] || {}
    normalized[String(key).toLowerCase()] = {
      applicable: value.applicable === true,
      enabled: value.enabled === true,
      reasonCode: value.reasoncode || value.reasonCode || '',
      reasonArgs: value.reasonargs || value.reasonArgs || {}
    }
  })
  return normalized
}

export function isActiveDrRun (run = {}) {
  return ACTIVE_RUN_STATES.includes(String(run?.state || '').toUpperCase())
}

export function requiresDisasterFailover (resource = {}) {
  const normalCutoverReady = resource.normalcutoverready ?? resource.normalCutoverReady
  const eligibility = normalizeActionEligibility(
    resource.actioneligibility || resource.actionEligibility || {}
  )
  const availability = normalizeActionAvailability(
    resource.actionavailability || resource.actionAvailability || {}
  ).failover
  return normalCutoverReady === false &&
    eligibility.disasterfailover === true &&
    availability?.applicable === true &&
    availability?.enabled === false &&
    availability?.reasonCode === DR_ACTION_CUTOVER_NOT_READY
}

function authoritySide (resource = {}) {
  return String(
    resource.authorityside ||
    resource.authoritySide ||
    resource.operatingside ||
    resource.operatingSide ||
    resource.activeside ||
    resource.activeSide ||
    'SOURCE'
  ).toUpperCase()
}

function legacyApplicable (key, resource, currentRun) {
  const state = String(resource?.effectivestate || resource?.effectiveState || resource?.state || '').toUpperCase()
  const schedulerDesired = String(resource?.schedulerdesiredstate || resource?.schedulerDesiredState || '').toUpperCase()
  const source = authoritySide(resource) !== 'TARGET'
  const target = !source || ['FAILED_OVER', 'FAILED_OVER_UNPROTECTED'].includes(state)
  const unprotected = state === 'UNPROTECTED'
  const testing = state === 'TESTING' || resource?.testsessionactive === true || resource?.testSessionActive === true
  const ftctlDr = String(resource?.enginebindingtype || resource?.engineBindingType || '').toUpperCase() === 'FTCTL_DR'

  switch (key) {
    case 'update':
    case 'delete':
      return true
    case 'sync':
      return source && !unprotected && state !== 'PAUSED'
    case 'recoversync':
      return source && (resource?.recoverysyncrequired === true || resource?.recoverySyncRequired === true)
    case 'pausesync':
      return source && schedulerDesired !== 'PAUSED' && ['READY', 'SYNCING'].includes(state)
    case 'resumesync':
      return source && (schedulerDesired === 'PAUSED' || state === 'PAUSED')
    case 'testfailover':
      return source && !unprotected && !testing
    case 'stoptestfailover':
      return testing
    case 'failover':
      return source && !testing
    case 'failback':
    case 'reprotect':
      return target
    case 'adoptreplica':
      return !ftctlDr
    case 'releaseprotection':
      return ftctlDr && !unprotected && (
        resource?.releaseready === true ||
        resource?.releaseReady === true ||
        resource?.engineaccepted === true ||
        resource?.engineAccepted === true
      )
    case 'cancelrun':
      return isActiveDrRun(currentRun)
    default:
      return false
  }
}

export function resolveDrActionAvailability (action, resource = {}, currentRun = {}) {
  if (!action?.key) {
    const disabled = typeof action?.disabled === 'function' ? action.disabled(resource) : action?.disabled === true
    const visible = typeof action?.show === 'function' ? action.show(resource) !== false : true
    return { applicable: visible, enabled: visible && !disabled, reasonCode: disabled ? 'DR_ACTION_NOT_ELIGIBLE' : '' }
  }

  const key = String(action.key).toLowerCase()
  // Deletion opens a dialog with a force option; the API validates execution.
  if (key === 'delete') {
    return { applicable: true, enabled: true, reasonCode: '' }
  }
  if (key === 'cancelrun' && !isActiveDrRun(currentRun)) {
    return { applicable: false, enabled: false, reasonCode: '' }
  }
  const typed = normalizeActionAvailability(
    resource.actionavailability || resource.actionAvailability || {}
  )
  if (Object.prototype.hasOwnProperty.call(typed, key)) {
    if (key === 'failover' && requiresDisasterFailover(resource)) {
      return Object.assign({}, typed[key], { enabled: true })
    }
    return typed[key]
  }

  const eligibility = normalizeActionEligibility(
    resource.actioneligibility || resource.actionEligibility || {}
  )
  if (!Object.prototype.hasOwnProperty.call(eligibility, key)) {
    return { applicable: false, enabled: false, reasonCode: 'DR_ACTION_AVAILABILITY_MISSING' }
  }
  const applicable = legacyApplicable(key, resource, currentRun)
  return {
    applicable,
    enabled: applicable && eligibility[key] === true,
    reasonCode: applicable && eligibility[key] !== true ? 'DR_ACTION_NOT_ELIGIBLE' : ''
  }
}

export function drActionReasonMessageKey (reasonCode) {
  const normalized = String(reasonCode || 'DR_ACTION_NOT_ELIGIBLE')
    .toLowerCase()
    .replace(/_/g, '.')
  return `message.${normalized}`
}
