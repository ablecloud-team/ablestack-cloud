// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.

// A VM lifecycle state is never a storage-service preparation receipt.
export const storagePreparationReasons = [
  'INSTANCE_NOT_ATTACHED', 'VM_NOT_RUNNING', 'CURRENT_BOOT_UNOBSERVED', 'GENERATION_OBSERVATION_INVALID',
  'BOOT_CHANGED_OR_STALE', 'HEALTH_OBSERVATION_STALE', 'BOOT_OBSERVATION_INVALID', 'BOOT_NOT_STARTED',
  'BOOT_MAINTENANCE_HELD', 'BOOT_RECONCILE_FAILED', 'BOOT_RECONCILING', 'BOOT_READY_PROOF_INVALID',
  'MAINTENANCE_UNOBSERVED', 'WRITER_UNOBSERVED', 'BOOT_CHILD_ACTIVE', 'WRITER_ACTIVE',
  'FORMATTER_ACTIVE_OR_UNOBSERVED', 'NATIVE_GENERATION_RECOVERY_REQUIRED', 'CURRENT_BOOT_READY',
  'NATIVE_OBSERVATION_UNAVAILABLE', 'OBSERVATION_WINDOW_EXPIRED', 'TARGET_RUNTIME_UNAVAILABLE',
  'CONFIGURATION_RECOVERY_REQUIRED', 'HEALTH_BOOT_METADATA_PENDING', 'HEALTH_CACHE_PREVIOUS_BOOT',
  'BOOT_CHANGED_DURING_OBSERVATION', 'BOOT_RECONCILE_STATUS_PENDING', 'HEALTH_OBSERVATION_INVALID',
  'HEALTH_RPC_OBSERVATION_FAILED', 'HEALTH_BOOT_OBSERVATION_INVALID', 'HEALTH_BOOT_OBSERVATION_INCONSISTENT', 'HEALTH_OBSERVATION_FAILED'
]

const statuses = new Set(['READY', 'WAITING_VM', 'WAITING_BOOT', 'WAITING_WRITER', 'BLOCKED', 'OBSERVATION_UNAVAILABLE'])

function unverified () {
  const error = new Error('STORAGE_PREPARATION_UNVERIFIED')
  error.code = 'STORAGE_PREPARATION_UNVERIFIED'
  return error
}

export function readStoragePreparation (response, expected, now = Date.now()) {
  const envelope = response?.getstorageservicepreparationstatusresponse
  const value = envelope?.storageservicepreparationstatus
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
    value.errorcode || typeof value.ready !== 'boolean' || !statuses.has(value.status) ||
    !expected?.purpose || value.purpose !== expected.purpose ||
    (expected.sharedfilesystemid && value.sharedfilesystemid !== expected.sharedfilesystemid) ||
    (expected.instanceid && value.id !== expected.instanceid)) throw unverified()
  const observed = value.observedatmillis
  const expires = value.expiresatmillis
  if (!Number.isSafeInteger(observed) || !Number.isSafeInteger(expires) ||
    observed > now + 5000 || expires <= now || expires <= observed || expires - observed > 60000) throw unverified()
  if (value.ready !== (value.status === 'READY') || (value.ready && (typeof value.bootid !== 'string' || !value.bootid || typeof value.id !== 'string' || !value.id))) throw unverified()
  return {
    id: value.id,
    sharedfilesystemid: value.sharedfilesystemid,
    purpose: value.purpose,
    ready: value.ready,
    status: value.status,
    reason: typeof value.reason === 'string' && /^[A-Z][A-Z0-9_]{0,100}$/.test(value.reason) ? value.reason : undefined,
    bootid: value.bootid,
    observedatmillis: observed,
    expiresatmillis: expires,
    runtimeclisha256: typeof value.runtimeclisha256 === 'string' && /^[a-f0-9]{64}$/.test(value.runtimeclisha256) ? value.runtimeclisha256 : undefined,
    diagnostics: readStoragePreparationDiagnostics(value.diagnostics)
  }
}

export function readyAtSameBoot (previous, next, now = Date.now()) {
  return !!(previous?.ready && next?.ready && previous.status === 'READY' && next.status === 'READY' &&
    previous.bootid && previous.bootid === next.bootid &&
    previous.id === next.id && previous.sharedfilesystemid === next.sharedfilesystemid && previous.purpose === next.purpose &&
    previous.expiresatmillis > now && next.expiresatmillis > now && next.observedatmillis > previous.observedatmillis)
}

export function storagePreparationWaitable (status) {
  return ['READY', 'WAITING_VM', 'WAITING_BOOT', 'WAITING_WRITER'].includes(status)
}

const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const diagnosticEnums = {
  healthOrigin: ['CACHE', 'LIVE', 'UNOBSERVED', 'INVALID'],
  healthStatus: ['ok', 'degraded', 'error', 'reconciling', 'INVALID'],
  networkReadinessStatus: ['OK', 'ERROR', 'UNKNOWN', 'INVALID'],
  bootProgressStatus: ['READY', 'RECONCILING', 'HELD', 'FAILED', 'DEGRADED', 'INVALID'],
  failedObservation: ['VM_STATE', 'GENERATION_BEFORE', 'MAINTENANCE', 'DATABASE_WRITER', 'DATA_FORMATTERS', 'HEALTH', 'WRITER_IDLE', 'GENERATION_AFTER']
}

// Diagnostic data never authorizes changes. Keep only closed public fields;
// optional malformed diagnostics must neither permit nor reject readiness.
export function readStoragePreparationDiagnostics (raw) {
  let value = raw
  try {
    if (typeof value === 'string') value = value.length <= 8192 ? JSON.parse(value) : null
  } catch (_) { return {} }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const clean = {}
  for (const key of ['nativeBootBefore', 'nativeBootAfter', 'healthBootId', 'bootProgressBootId']) {
    if (value[key] === null || value[key] === 'INVALID' || uuid(value[key])) clean[key] = value[key]
  }
  for (const [key, allowed] of Object.entries(diagnosticEnums)) {
    if (value[key] === null || allowed.includes(value[key])) clean[key] = value[key]
  }
  for (const key of ['healthSuccess', 'bootProgressPresent']) {
    if (value[key] === null || typeof value[key] === 'boolean') clean[key] = value[key]
  }
  for (const key of ['healthGeneratedEpoch', 'healthAgeMillis', 'observationDurationMillis']) {
    const number = value[key]
    if (number === null || (typeof number === 'number' && Number.isFinite(number) && Math.abs(number) < 1e12 && (key === 'healthAgeMillis' || number >= 0))) clean[key] = number
  }
  return clean
}

export function storagePreparationDiagnosticSnapshot (receipt) {
  if (!receipt) return null
  return {
    id: typeof receipt.id === 'string' ? receipt.id : '',
    sharedfilesystemid: typeof receipt.sharedfilesystemid === 'string' ? receipt.sharedfilesystemid : '',
    status: statuses.has(receipt.status) ? receipt.status : 'OBSERVATION_UNAVAILABLE',
    reason: storagePreparationReasons.includes(receipt.reason) ? receipt.reason : 'UNKNOWN',
    bootid: uuid(receipt.bootid) ? receipt.bootid : null,
    observedatmillis: Number.isSafeInteger(receipt.observedatmillis) ? receipt.observedatmillis : null,
    diagnostics: readStoragePreparationDiagnostics(receipt.diagnostics)
  }
}
