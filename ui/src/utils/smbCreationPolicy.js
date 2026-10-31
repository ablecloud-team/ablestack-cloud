// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
export const smbModeDefaults = Object.freeze({ createMask: '0660', directoryMask: '0770', forceCreateMode: '0000', forceDirectoryMode: '0000' })
export function smbCreationModes (config) {
  const value = typeof config === 'string' ? JSON.parse(config || '{}') : config || {}
  const result = {}
  for (const [key, fallback] of Object.entries(smbModeDefaults)) {
    const raw = value[key] ?? value[key.toLowerCase()] ?? fallback
    if (typeof raw !== 'string' || !/^0?[0-7]{3}$/.test(raw)) throw new Error('SMB_CREATION_MODE_INVALID')
    result[key] = parseInt(raw, 8).toString(8).padStart(4, '0')
  }
  for (const [force, mask] of [['forceCreateMode', 'createMask'], ['forceDirectoryMode', 'directoryMask']]) {
    if (parseInt(result[force], 8) & ~parseInt(result[mask], 8)) throw new Error('SMB_FORCE_MODE_OUTSIDE_MASK')
  }
  return result
}
export function smbCreationDelta (baseline, form) {
  const before = smbCreationModes(baseline); const after = smbCreationModes(form)
  return Object.fromEntries(Object.keys(after).filter(key => before[key] !== after[key]).map(key => [key.toLowerCase(), after[key]]))
}
export function smbCreationIntent (row, form) {
  const patch = smbCreationDelta(row.config, form)
  return { api: 'updateStorageSmbShare', resourceid: row.id, smbcreation: Object.fromEntries(Object.keys(smbModeDefaults).filter(key => patch[key.toLowerCase()] !== undefined).map(key => [key, patch[key.toLowerCase()]])) }
}
export function smbSimpleOptionPatch (baseline, current) {
  if (!baseline) return null
  const safe = ['readonly', 'browseable', 'inheritowner']
  const changed = Object.keys(current).filter(key => !['inheritownerbaseline', 'baseline'].includes(key) && JSON.stringify(current[key]) !== JSON.stringify(baseline[key]))
  if (!changed.length || changed.some(key => !safe.includes(key))) return null
  return Object.fromEntries(changed.map(key => [key, current[key]]))
}
