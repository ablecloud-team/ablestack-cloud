// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
const number = value => value === '' || value === undefined || value === null ? null : Number(value)
const mode = value => value ? String(value).trim().replace(/^0+/, '').padStart(4, '0') : null
export function nfsSquashPolicy (config = {}) {
  return config.allSquash || config.allsquash ? 'ALL' : (config.rootSquash ?? config.rootsquash ?? true) ? 'ROOT' : 'NONE'
}
export function nfsSquashFlags (policy) {
  return { rootsquash: policy !== 'NONE', allsquash: policy === 'ALL' }
}
export function nfsPosixRecommendation (policy, readOnly, anonuid, anongid) {
  const mapped = policy !== 'NONE' && !readOnly
  return { owneruid: mapped ? number(anonuid) ?? 65534 : 0, ownergid: mapped ? number(anongid) ?? 65534 : 0, mode: mapped ? '0775' : '0755', recursivepermission: false }
}
export function nfsPermissionDelta (baseline, draft, enabled) {
  if (!enabled) return {}
  const result = {}
  for (const key of ['owneruid', 'ownergid', 'mode']) {
    const normalize = key === 'mode' ? mode : number
    const value = normalize(draft[key])
    if (value !== null && value !== normalize(baseline[key])) result[key] = value
  }
  if (draft.recursivepermission === true) result.recursivepermission = true
  return result
}
export function nfsMappingDelta (baseline, draft) {
  const result = {}
  for (const key of ['anonuid', 'anongid']) {
    const value = number(draft[key])
    if (value !== null && value !== number(baseline[key])) result[key] = value
  }
  return result
}
export function nfsPermissionIntent (params) {
  const result = {}
  for (const [name, key] of Object.entries({ owneruid: 'ownerUid', ownergid: 'ownerGid', mode: 'mode', recursivepermission: 'recursivePermission' })) {
    if (params[name] !== undefined) result[key] = params[name]
  }
  return result
}
export function nfsPosixObservation (wire) {
  if (typeof wire === 'string') {
    try { wire = JSON.parse(wire) } catch (error) { return { status: 'UNAVAILABLE' } }
  }
  return wire && wire.status === 'OBSERVED' && number(wire.owneruid) !== null && number(wire.ownergid) !== null && mode(wire.mode)
    ? { ...wire, owneruid: number(wire.owneruid), ownergid: number(wire.ownergid), mode: mode(wire.mode) }
    : { status: 'UNAVAILABLE' }
}
