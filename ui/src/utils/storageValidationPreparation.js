// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.

function invalid () { return new Error('STORAGE_VALIDATION_PREPARATION_UNVERIFIED') }

export function readStorageValidationPreparation (response, command, instanceId, instanceName, now = Date.now()) {
  const envelope = response?.[command.toLowerCase() + 'response'] || response
  const row = envelope?.storageserviceruntime || envelope
  if (!row || row.success !== true || Object.prototype.hasOwnProperty.call(row, 'errorcode')) throw invalid()
  const value = typeof row.resultjson === 'string' ? JSON.parse(row.resultjson) : row.resultjson
  if (!value || typeof value !== 'object' || Array.isArray(value) || value.instanceUuid !== instanceId || value.instanceName !== instanceName ||
      value.productionCapability !== false || typeof value.eligible !== 'boolean' || typeof value.enabled !== 'boolean' ||
      !Number.isSafeInteger(value.profileRevision) || value.profileRevision < 0 || !Array.isArray(value.blockers) || value.blockers.some(reason => typeof reason !== 'string') ||
      !Number.isSafeInteger(value.observedAtMillis) || value.observedAtMillis > now + 5000 || value.observedAtMillis < now - 300000 ||
      !Number.isSafeInteger(value.previewExpiresAtMillis) || value.previewExpiresAtMillis <= now ||
      value.previewExpiresAtMillis <= value.observedAtMillis || value.previewExpiresAtMillis - value.observedAtMillis > 300000 ||
      (value.enabled && value.eligible && (!Number.isSafeInteger(value.expiresAtMillis) || value.expiresAtMillis <= 0))) throw invalid()
  if (value.eligible) {
    if (typeof value.previewToken !== 'string' || !/^[a-f0-9]{64}$/.test(value.previewToken) || !/^[a-f0-9]{64}$/.test(value.expectedCliSha256) ||
        !/^[a-f0-9]{40}$/.test(value.sourceCommit) || !value.vmUuid || !value.bindings || !value.bindings.rootVolumeUuid || !value.bindings.volumes ||
        value.blockers.length > 0) throw invalid()
    const volumes = Object.entries(value.bindings.volumes)
    if (volumes.length !== 5 || volumes.filter(([, row]) => row.type === 'ROOT').length !== 1 ||
        volumes.filter(([, row]) => row.type === 'DATADISK').length !== 4 ||
        !volumes.every(([id, row]) => id && ['SPARSE', 'FAT'].includes(row.provisioningType) && row.attachedToFixture === true && row.ownerAndZoneVerified === true &&
          (row.type === 'ROOT' || row.newDataWithoutBacking === true))) throw invalid()
  }
  return value
}

function canonical (value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]))
  return value
}

export function storageValidationBindings (value) { return JSON.stringify(canonical(value)) }

export function storageValidationScope (value) {
  if (!value) return ''
  return storageValidationBindings([value.instanceUuid, value.instanceName, value.vmUuid, value.previewToken, value.profileRevision, value.expectedCliSha256, value.sourceCommit, value.bindings])
}
