// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { policyFormValues, policyFingerprint, freshPolicy, editingZoneParams, canEditPolicy, canManagePolicy } from '@/utils/snapshotPolicyEditing'
import { encodePolicySchedule } from '@/utils/snapshotPolicyInput'

const policy = { id: 'policy', volumeid: 'volume', intervaltype: 0, schedule: '0', timezone: 'UTC', maxsnaps: 3, fordisplay: true, tags: [{ key: 'keep', value: '한글' }], zone: [{ id: 'primary' }, { id: 'extra' }] }

test.each([[0, '0', 0], [0, '59', 59], [1, '00:00', '00:00'], [1, '59:23', '59:23'], [2, '00:00:1', '00:00:1'], [2, '59:23:7', '59:23:7'], [3, '00:00:1', '00:00:1'], [3, '59:23:28', '59:23:28']])('existing interval %s schedule %s round trips without losing zero', (intervaltype, schedule, result) => {
  expect(encodePolicySchedule(policyFormValues({ ...policy, intervaltype, schedule }))).toBe(result)
})
test.each([[0, '60'], [1, '0:24'], [2, '0:0:0'], [3, '0:0:29'], [3, '0:0:1.5'], [4, '0'], [1, '0:0:1']])('bad persisted schedule %s/%s is not normalized into an edit', (intervaltype, schedule) => {
  expect(() => policyFormValues({ ...policy, intervaltype, schedule })).toThrow('message.snapshot.policy.unavailable')
})
test('tag order is not a concurrent change; values, schedule, zones and display are', () => {
  const p = { ...policy, tags: [...policy.tags, { key: 'second', value: '2' }] }
  expect(policyFingerprint(p)).toBe(policyFingerprint({ ...p, tags: [...p.tags].reverse() }))
  for (const change of [{ schedule: '5' }, { fordisplay: false }, { tags: [] }, { zone: [] }]) expect(policyFingerprint(p)).not.toBe(policyFingerprint({ ...p, ...change }))
})
test('zone omission preserves existing metadata; explicit clear only applies after successful lookup', () => {
  const volume = { zoneid: 'primary' }
  expect(editingZoneParams(policy, volume, ['extra'], true)).toEqual({})
  expect(() => editingZoneParams(policy, volume, [], true)).toThrow('message.snapshot.zones.failed')
  expect(editingZoneParams(policy, volume, [], false)).toEqual({ clearzoneids: true })
  expect(editingZoneParams(policy, volume, ['other'], false)).toEqual({ zoneids: 'other' })
})
test('fresh reads require the exact policy, volume and immutable interval; late context responses are rejected', async () => {
  const request = jest.fn().mockResolvedValue({ listsnapshotpoliciesresponse: { snapshotpolicy: [policy] } })
  await expect(freshPolicy(request, policy)).resolves.toEqual(policy)
  expect(request).toHaveBeenCalledWith('listSnapshotPolicies', { id: 'policy', volumeid: 'volume', listall: true }, { timeout: 20000 })
  for (const change of [{ id: 'other' }, { volumeid: 'other' }, { intervaltype: 1 }]) await expect(freshPolicy(request, { ...policy, ...change })).rejects.toThrow()
  await expect(freshPolicy(request, policy, () => false)).rejects.toThrow('message.snapshot.policy.context.changed')
  request.mockResolvedValue({ listsnapshotpoliciesresponse: { snapshotpolicy: [] } }); await expect(freshPolicy(request, policy)).rejects.toThrow()
})
test('read-only and non-admin callers cannot expose administrative scheduling', () => {
  expect(canEditPolicy({ listVolumes: {}, listSnapshotPolicies: {} })).toBe(false)
  expect(canEditPolicy({ createSnapshotPolicy: {}, listVolumes: {}, listSnapshotPolicies: {} })).toBe(true)
  const apis = { updateSnapshotPolicy: {}, listSnapshotPolicies: {}, listVolumes: {}, queryAsyncJobResult: {} }
  expect(canManagePolicy({ apis, userInfo: { roletype: 'User' } })).toBe(false)
  expect(canManagePolicy({ apis, userInfo: { roletype: 'Admin' } })).toBe(true)
  delete apis.queryAsyncJobResult; expect(canManagePolicy({ apis, userInfo: { roletype: 'Admin' } })).toBe(false)
})
