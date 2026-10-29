// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { policySchedule } from './snapshotPolicyPresentation'
import { policyFingerprint, policyFormValues } from './snapshotPolicyEditing'
import { readAllocationList } from './volumeAllocation'

export const canDeletePolicy = apis => ['deleteSnapshotPolicies', 'listSnapshotPolicies', 'listVolumes'].every(api => api in (apis || {}))
export const deletionIdentity = policy => Object.freeze({ id: policy.id, volumeid: policy.volumeid, intervaltype: Number(policy.intervaltype), ...(policy.projectid ? { projectid: policy.projectid } : {}) })
export const deletionOwner = volume => JSON.stringify([volume.id, volume.name, volume.account, volume.domainid, volume.domain, volume.projectid, volume.project, volume.zoneid])
export const deletionFingerprint = target => JSON.stringify([policyFingerprint(target.policy), deletionOwner(target.volume)])

export function deletionSchedule (policy, t) {
  try { policyFormValues(policy) } catch { return String(policy.schedule || '—') }
  return policySchedule(policy, t)
}

// Do not apply creation-form validation: malformed legacy schedules must remain deletable.
export async function readPolicyDeletionTarget (request, identity, current = () => true, presenceOnly = false) {
  if (!identity.id || !identity.volumeid || !Number.isInteger(identity.intervaltype)) throw new Error('message.snapshot.policy.unavailable')
  const volumes = await request('listVolumes', { id: identity.volumeid, listall: true, ...(identity.projectid ? { projectid: identity.projectid } : {}) }, { timeout: 20000 })
  if (!current()) throw new Error('message.snapshot.policy.context.changed')
  const volume = volumes.listvolumesresponse?.volume?.find(v => v.id === identity.volumeid)
  if (!volume || (identity.projectid && volume.projectid !== identity.projectid)) throw new Error('message.snapshot.policy.unavailable')
  const rows = await readAllocationList(request, 'listSnapshotPolicies', 'snapshotpolicy', { volumeid: volume.id, listall: true, ...(volume.projectid ? { projectid: volume.projectid } : { ignoreproject: true }) }, current)
  if (!current() || !rows) throw new Error('message.snapshot.policy.context.changed')
  const matches = rows.filter(p => p.id === identity.id)
  if (!matches.length && presenceOnly) return { policy: null, volume }
  if (matches.length !== 1 || matches[0].volumeid !== volume.id || Number(matches[0].intervaltype) !== identity.intervaltype) throw new Error('message.snapshot.policy.unavailable')
  return { policy: matches[0], volume }
}
