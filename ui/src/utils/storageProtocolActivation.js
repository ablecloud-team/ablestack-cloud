// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
import { storageNetworkObservation, storageNetworkStatus } from '@/utils/storageNetworkReadiness'

export const protocolAddressObservationMaxAge = 30

// Management NIC/configuration entries are choices, not guest observation proof.
// Protocol port changes never alter this read-only address assessment.
export function protocolAddressObservation ({ readiness, currentBootId, ip, stale = false, readFailed = false, now = Date.now() / 1000 }) {
  const observation = storageNetworkObservation(readiness)
  if (!ip) return { status: 'EMPTY', observedEpoch: observation?.observedEpoch || null }
  const fresh = observation && !stale && !readFailed && typeof currentBootId === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(currentBootId) &&
    observation.bootId === currentBootId && observation.observedEpoch <= now + 5 && now - observation.observedEpoch <= protocolAddressObservationMaxAge
  if (!fresh) return { status: 'UNKNOWN', observedEpoch: observation?.observedEpoch || null }
  const status = storageNetworkStatus(readiness)
  if (status !== 'OK') return { status, observedEpoch: observation.observedEpoch }
  return {
    status: observation.actual.addresses.some(address => address.ipAddress === ip) ? 'OBSERVED' : 'NOT_OBSERVED',
    observedEpoch: observation.observedEpoch
  }
}

export function protocolActivationFailurePresentation (result) {
  const body = result?.jobresult || result?.response?.data || result || {}
  const nested = body && typeof body === 'object' ? Object.values(body).slice(0, 16).filter(value => value && typeof value === 'object' && !Array.isArray(value)).map(value => value.errortext) : []
  const values = [typeof body === 'string' ? body : null, body.errortext, body.errorresponse?.errortext, ...nested, body.code, body.reason, body.message, result?.message]
  const text = values.filter(value => typeof value === 'string').map(value => value.slice(0, 8192)).join(' ')
  const token = code => new RegExp('(?:^|[^A-Z0-9_])' + code + '(?=$|[^A-Z0-9_])').test(text)
  const unsupported = token('ENDPOINT_PREPARATION_UNSUPPORTED')
  const operation = text.match(/Storage Service operation ([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?= )/i)
  // A capability recheck can reject an already admitted writer. Keep its actual
  // terminal state, without inferring rollback from a prewriter HTTP rejection.
  const operationState = operation ? text.slice(operation.index + operation[0].length + 1).match(/^(RECOVERY_REQUIRED|ROLLED_BACK|BLOCKED)(?=[:\s]|$)/)?.[1] : null
  const cause = [
    ['ENDPOINT_PREPARATION_UNSUPPORTED', 'unsupported'],
    ['NFS_SOURCE_CHANGED', 'source'],
    ['ENDPOINT_PREPARATION_FAILED', 'address'],
    ['NFS_RUNTIME_NOT_READY', 'runtime']
  ].find(([code]) => token(code))
  const restoreFailed = token('ENDPOINT_SOURCE_RESTORE_FAILED')
  const recoveryRequired = restoreFailed || (unsupported ? operationState === 'RECOVERY_REQUIRED' : ['BLOCKED', 'RECOVERY_REQUIRED', 'ROLLBACK_FAILED'].some(token))
  return {
    unsupported,
    operationId: operation ? operation[1].toLowerCase() : null,
    restoreFailed,
    messageKey: cause ? 'message.storage.service.activation.failure.' + cause[1] : (restoreFailed ? 'message.storage.service.activation.failure.source.restore' : null),
    recoveryMessageKey: restoreFailed && cause ? 'message.storage.service.activation.failure.source.restore' : null,
    recoveryRequired,
    rolledBack: !recoveryRequired && (unsupported ? operationState === 'ROLLED_BACK' : token('ROLLED_BACK'))
  }
}
