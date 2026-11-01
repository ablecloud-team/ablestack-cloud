// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
export function smbFileIdentity (config) {
  const raw = typeof config === 'string' ? JSON.parse(config || '{}') : config || {}
  const v = raw.fileIdentity
  if (v === undefined) return { mode: 'AUTHENTICATED', uid: null, gid: null }
  if (!v || v.version !== 1 || !['AUTHENTICATED', 'FIXED'].includes(v.mode)) throw new Error('SMB_FIXED_IDENTITY_INVALID')
  const value = { mode: v.mode, uid: v.uid ?? null, gid: v.gid ?? null }
  validateIdentity(value)
  return value
}
export function validateIdentity (value) {
  if (!value || !['AUTHENTICATED', 'FIXED'].includes(value.mode)) throw new Error('SMB_FIXED_IDENTITY_INVALID')
  if (value.mode !== 'FIXED') return
  for (const n of [value.uid, value.gid]) if (!Number.isInteger(n) || n < 1000 || n > 65533 || (n >= 61184 && n <= 65519)) throw new Error('SMB_FIXED_IDENTITY_PROTECTED_ID')
}
export function smbIdentityParameters (baseline, value) {
  validateIdentity(value)
  const before = baseline || { mode: 'AUTHENTICATED', uid: null, gid: null }
  const result = {}
  if (before.mode !== value.mode) result.fileidentitymode = value.mode
  if (value.mode === 'FIXED') for (const key of ['uid', 'gid']) if (before.mode !== 'FIXED' || before[key] !== value[key]) result['fixed' + key] = value[key]
  return result
}
export function smbIdentityIntent (baseline, value) {
  const params = smbIdentityParameters(baseline, value)
  const patch = {}
  if (params.fileidentitymode !== undefined) patch.mode = params.fileidentitymode
  if (params.fixeduid !== undefined) patch.uid = params.fixeduid
  if (params.fixedgid !== undefined) patch.gid = params.fixedgid
  return patch
}
