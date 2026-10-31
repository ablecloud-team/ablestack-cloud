// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
export function inheritOwnerParameters (form) {
  return !!form.inheritowner === !!form.inheritownerbaseline ? {} : { inheritowner: !!form.inheritowner }
}
export function inheritanceIntent (form) {
  if (!form.directorypolicyid && !form.inheritowner && !form.inheritownerbaseline) return null
  return { inheritOwner: !!form.inheritowner, ...(form.directorypolicyid ? { directoryPolicyId: form.directorypolicyid } : {}) }
}
export function setgidEnabled (mode) {
  return /^0?[0-7]{3,4}$/.test(String(mode)) ? !!(parseInt(mode, 8) & 0o2000) : null
}
export function setgidParameters (row, enabled) {
  if (!row?.id || row.observationstatus !== 'APPLIED' || row.observation?.status !== 'OBSERVED' ||
      setgidEnabled(row.observation.mode) === null || parseInt(row.mode, 8) !== parseInt(row.observation.mode, 8)) throw new Error('DIRECTORY_POLICY_STALE')
  const mode = ((parseInt(row.observation.mode, 8) & ~0o2000) | (enabled ? 0o2000 : 0)).toString(8).padStart(4, '0')
  return { id: row.id, expectedpolicyrevision: row.policyrevision, mode }
}
export function setgidIntent (row, enabled) {
  const params = setgidParameters(row, enabled)
  return { api: 'updateStorageDirectoryPolicy', resourceid: params.id, expectedpolicyrevision: params.expectedpolicyrevision, posixchanges: { mode: params.mode } }
}
