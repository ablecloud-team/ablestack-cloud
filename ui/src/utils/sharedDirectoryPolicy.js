// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
export function directoryChanges (row, draft) {
  const result = {}; const observed = row.observation || {}
  for (const key of ['owneruid', 'ownergid', 'mode']) {
    const value = draft[key]
    if (key === 'mode') {
      if (!/^0?[0-7]{3,4}$/.test(String(value))) throw new Error('DIRECTORY_POLICY_INVALID')
    } else if (!Number.isInteger(Number(value)) || value === null || Number(value) < 0 || Number(value) > 65535) throw new Error('DIRECTORY_POLICY_INVALID')
    const normalized = key === 'mode' ? parseInt(value, 8).toString(8).padStart(4, '0') : Number(value)
    const same = x => key === 'mode' ? parseInt(x, 8) === parseInt(normalized, 8) : Number(x) === normalized
    if (!same(row[key]) || (observed.status === 'OBSERVED' && !same(observed[key]))) {
      result[key === 'owneruid' ? 'ownerUid' : key === 'ownergid' ? 'ownerGid' : 'mode'] = normalized
    }
  }
  if (draft.recursive) result.recursivePermission = true
  return result
}
export function directoryIntent (row, draft) {
  if (!row.id) return { api: 'createStorageDirectoryPolicy', volumeid: row.volumeid, path: row.path }
  return { api: 'updateStorageDirectoryPolicy', resourceid: row.id, expectedpolicyrevision: row.policyrevision, posixchanges: directoryChanges(row, draft) }
}
export function directoryParameters (row, draft) {
  if (!row.id) return { volumeid: row.volumeid, path: row.path }
  const change = directoryChanges(row, draft)
  const result = { id: row.id, expectedpolicyrevision: row.policyrevision }
  for (const [key, value] of Object.entries(change)) result[key.toLowerCase()] = value
  return result
}
