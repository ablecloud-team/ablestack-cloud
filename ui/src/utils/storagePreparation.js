// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.

// A VM lifecycle state is never a storage-service preparation receipt.
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
  return { ...value }
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
