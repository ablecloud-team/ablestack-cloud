// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import moment from 'moment'
import { policyIntervals, policyFieldErrors } from './snapshotPolicyInput'

export const canEditPolicy = apis => ['createSnapshotPolicy', 'listSnapshotPolicies', 'listVolumes'].every(api => api in apis)
export const canManagePolicy = getters => getters.userInfo?.roletype === 'Admin' &&
  ['updateSnapshotPolicy', 'listSnapshotPolicies', 'listVolumes', 'queryAsyncJobResult'].every(api => api in (getters.apis || {}))

export function policyJobId () {
  const bytes = new Uint8Array(16); crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = [...bytes].map(v => v.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function policyFormValues (policy) {
  const intervaltype = policyIntervals[Number(policy.intervaltype)]
  const parts = String(policy.schedule).split(':')
  if (!intervaltype || parts.length !== (intervaltype === 'hourly' ? 1 : intervaltype === 'daily' ? 2 : 3) || parts.some(v => !/^\d{1,2}$/.test(v))) throw new Error('message.snapshot.policy.unavailable')
  if (Number(parts[0]) > 59 || (parts.length > 1 && Number(parts[1]) > 23) ||
    (parts.length === 3 && (Number(parts[2]) < 1 || Number(parts[2]) > (intervaltype === 'weekly' ? 7 : 28)))) throw new Error('message.snapshot.policy.unavailable')
  const form = {
    intervaltype,
    timezone: policy.timezone,
    maxsnaps: policy.maxsnaps,
    time: Number(parts[0]),
    timeSelect: moment({ hour: Number(parts[1] || 0), minute: Number(parts[0]), second: 0, millisecond: 0 }),
    'day-of-week': Number(parts[2]) - 1,
    'day-of-month': Number(parts[2])
  }
  if (!policy.timezone || policyFieldErrors(form).length) throw new Error('message.snapshot.policy.unavailable')
  return form
}

export function policyFingerprint (policy) {
  return JSON.stringify([policy.id, policy.volumeid, Number(policy.intervaltype), policy.schedule, policy.timezone, policy.maxsnaps, policy.fordisplay,
    (policy.tags || []).map(t => [t.key, t.value]).sort((a, b) => a[0].localeCompare(b[0])),
    (policy.zone || []).map(z => z.id).sort(), (policy.storage || []).map(s => s.id).sort()])
}

export async function freshPolicy (request, target, current = () => true) {
  const json = await request('listSnapshotPolicies', { id: target.id, volumeid: target.volumeid, listall: true }, { timeout: 20000 })
  if (!current()) throw new Error('message.snapshot.policy.context.changed')
  const rows = json?.listsnapshotpoliciesresponse?.snapshotpolicy
  const policy = Array.isArray(rows) && rows.length === 1 ? rows[0] : null
  if (!policy || policy.id !== target.id || policy.volumeid !== target.volumeid || Number(policy.intervaltype) !== Number(target.intervaltype)) throw new Error('message.snapshot.policy.unavailable')
  policyFormValues(policy)
  return policy
}

export const extraPolicyZones = (policy, volume) => (policy.zone || []).filter(z => z.id !== volume.zoneid).map(z => z.id).sort()

export function editingZoneParams (policy, volume, selected, unavailable) {
  const original = extraPolicyZones(policy, volume); const chosen = [...(selected || [])].sort()
  if (JSON.stringify(original) === JSON.stringify(chosen)) return {}
  if (unavailable) throw new Error('message.snapshot.zones.failed')
  return chosen.length ? { zoneids: chosen.join(',') } : { clearzoneids: true }
}
