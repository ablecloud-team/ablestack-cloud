// Licensed under the Apache License, Version 2.0. See NOTICE.
export function bootRecoveryObservation (rows, unavailable = false) {
  try {
    const row = rows?.[0]
    if (!row || row.success === false || row.success === 'false') return { value: null, unknown: true }
    const wire = typeof row.resultjson === 'string' ? JSON.parse(row.resultjson) : row.resultjson || row
    const value = wire.bootReconcile
    if (!value || value.schemaVersion !== 1) return { value: null, unknown: true }
    return { value, unknown: unavailable || value.state === 'UNKNOWN' }
  } catch (_) { return { value: null, unknown: true } }
}
export function bootResumeRequest (instance, value) {
  if (!instance?.id || instance.configurationprotection === true || instance.configurationprotection === 'true' ||
      !value?.canResume || !['PAUSED', 'INTERRUPTED'].includes(value.state) ||
      !/^[a-f0-9-]{36}$/.test(value.bootId || '') || !/^[a-f0-9]{64}$/.test(value.desiredHash || '')) return null
  return { instanceid: instance.id, bootid: value.bootId, expecteddesiredhash: value.desiredHash, protocol: 'NFS' }
}
