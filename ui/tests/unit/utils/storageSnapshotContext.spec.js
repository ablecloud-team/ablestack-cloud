// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { getAPI, postAPI } from '@/api'
import { freezeSnapshotTarget, freshStorageSnapshotContext, snapshotActionParams, snapshotSecurityScope } from '@/utils/storageSnapshotContext'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const source = () => ({ version: 1, sourceavailable: true, volumeid: 'volume', volumename: 'source', volumestate: 'Ready', virtualmachineid: null, virtualmachinestate: null, createreason: '', revertreason: '' })
let record, target
beforeEach(() => {
  jest.clearAllMocks()
  record = { id: 'snapshot', name: 'fixed', state: 'BackedUp', volumeid: 'volume', account: 'owner', domainid: 'domain', revertable: true, snapshotcontext: source() }
  target = freezeSnapshotTarget(record)
  getAPI.mockImplementation((api, args) => {
    if (api === 'listApis') return Promise.resolve({ listapisresponse: { api: [{ name: args.name }] } })
    if (api === 'listSnapshotCopyTargets') return Promise.resolve({ listsnapshotcopytargetsresponse: { snapshotcopytargets: { snapshotid: 'snapshot', sourcezoneid: args.sourcezoneid, eligible: true, reason: '', scopehash: 'a'.repeat(64), destinations: [] } } })
    if (api === 'listSnapshots') return Promise.resolve({ listsnapshotsresponse: { count: 1, snapshot: args.showunique ? [record] : [{ id: 'snapshot', zoneid: 'zone', datastoreid: 'store', datastorestate: 'Ready' }] } })
    if (api === 'listVolumes') return Promise.resolve({ listvolumesresponse: { count: 1, volume: [{ id: 'volume', name: 'source', state: 'Ready', snapshotcontext: source() }] } })
  })
  postAPI.mockResolvedValue({ deletesnapshotresponse: { snapshotid: 'snapshot', scopehash: 'revision', reference: [] } })
})
test('frozen source and actual API UUID remain identical after selection mutation', async () => {
  const mutable = { ...record }; const frozen = freezeSnapshotTarget(mutable)
  mutable.id = 'other'; mutable.name = 'other'
  const context = await freshStorageSnapshotContext(frozen, 'createVolume')
  expect(context.record.id).toBe('snapshot')
  expect(snapshotActionParams(frozen, 'createVolume', { snapshotid: 'other', name: 'restored' }, context)).toEqual({ snapshotid: 'snapshot', name: 'restored' })
})
test.each(['createVolume', 'createTemplate'])('missing original volume does not block %s restoration', async api => {
  record.snapshotcontext = { ...source(), sourceavailable: false, volumeid: null, revertreason: 'SOURCE_VOLUME_UNAVAILABLE', createreason: 'SOURCE_VOLUME_UNAVAILABLE' }
  const context = await freshStorageSnapshotContext(target, api)
  expect(context.reason).toBe('')
  expect(context.source.sourceavailable).toBe(false)
})
test('missing original prevents revert', async () => {
  record.snapshotcontext = { ...source(), sourceavailable: false, revertreason: 'SOURCE_VOLUME_UNAVAILABLE' }
  expect((await freshStorageSnapshotContext(target, 'revertSnapshot')).reason).toBe('SOURCE_VOLUME_UNAVAILABLE')
})
test('current Running VM state and conflict reason prevent revert', async () => {
  record.snapshotcontext = { ...source(), virtualmachineid: 'vm', virtualmachinestate: 'Running', revertreason: 'VM_MUST_BE_STOPPED' }
  expect((await freshStorageSnapshotContext(target, 'revertSnapshot')).reason).toBe('VM_MUST_BE_STOPPED')
})
test.each(['VM_SNAPSHOTS_EXIST', 'BACKUP_RESTORE_IN_PROGRESS', 'SOURCE_VM_UNAVAILABLE'])('server source reason %s is retained', async reason => {
  record.snapshotcontext.revertreason = reason
  expect((await freshStorageSnapshotContext(target, 'revertSnapshot')).reason).toBe(reason)
})
test('incomplete list fails closed instead of accepting one arbitrary row', async () => {
  getAPI.mockResolvedValue({ listapisresponse: { api: [{ name: 'createVolume' }] }, listsnapshotsresponse: { count: 2, snapshot: [record] } })
  await expect(freshStorageSnapshotContext(target, 'createVolume')).rejects.toThrow('message.snapshot.action.incomplete')
})
test('older backend with no source context cannot silently permit an operation', async () => {
  delete record.snapshotcontext
  await expect(freshStorageSnapshotContext(target, 'revertSnapshot')).rejects.toThrow('message.snapshot.action.incomplete')
})
test.each(['account', 'domainid', 'projectid'])('changed %s aborts identity confirmation', async key => {
  record[key] = 'other'
  await expect(freshStorageSnapshotContext(target, 'createVolume')).rejects.toThrow('message.snapshot.action.context.changed')
})
test('different returned snapshot UUID is never substituted', async () => {
  record.id = 'other'
  await expect(freshStorageSnapshotContext(target, 'createVolume')).rejects.toThrow('message.snapshot.action.unavailable')
})
test('permission is checked on server again', async () => {
  getAPI.mockResolvedValue({ listapisresponse: { api: [] } })
  await expect(freshStorageSnapshotContext(target, 'createVolume')).rejects.toThrow('message.snapshot.action.permission')
  expect(postAPI).not.toHaveBeenCalled()
})
test('security context change during lookup aborts before mutation', async () => {
  let current = true
  getAPI.mockImplementation(() => { current = false; return Promise.resolve({ listapisresponse: { api: [{ name: 'createVolume' }] }, listsnapshotsresponse: { snapshot: [record], count: 1 } }) })
  await expect(freshStorageSnapshotContext(target, 'createVolume', () => current)).rejects.toThrow('message.snapshot.action.context.changed')
  expect(postAPI).not.toHaveBeenCalled()
})
test('delete uses newly inspected hash and exact zone regardless of mutable form', async () => {
  const scoped = freezeSnapshotTarget(record, { zoneId: 'zone' })
  const context = await freshStorageSnapshotContext(scoped, 'deleteSnapshot')
  expect(snapshotActionParams(scoped, 'deleteSnapshot', { id: 'other', zoneid: 'other' }, context)).toEqual({ id: 'snapshot', zoneid: 'zone', expectedscopehash: 'revision' })
})
test('unknown row Zone cannot expand a delete into all-zone deletion', async () => {
  expect((await freshStorageSnapshotContext(freezeSnapshotTarget(record, { zoneId: null }), 'deleteSnapshot')).reason).toBe('UNKNOWN_REFERENCE_ZONE')
})
test('copy freezes its source zone independently of destination form values', async () => {
  const scoped = freezeSnapshotTarget(record, { zoneId: 'zone' })
  const context = await freshStorageSnapshotContext(scoped, 'copySnapshot')
  expect(context.reason).toBe('')
  expect(snapshotActionParams(scoped, 'copySnapshot', { id: 'other', sourcezoneid: 'other', destzoneids: 'destination' }, context)).toEqual({ id: 'snapshot', sourcezoneid: 'zone', destzoneids: 'destination', expectedscopehash: 'a'.repeat(64) })
})
test('create snapshot pins the original volume UUID', async () => {
  const volume = freezeSnapshotTarget({ id: 'volume' }, { kind: 'volume' })
  const context = await freshStorageSnapshotContext(volume, 'createSnapshot')
  expect(snapshotActionParams(volume, 'createSnapshot', { volumeId: 'other' }, context)).toEqual({ volumeid: 'volume' })
})
test('login/session/project/role changes are distinct security scopes', () => {
  const store = { getters: { userInfo: { id: 'user', roletype: 'User' }, project: { id: 'project' } }, state: { user: { token: 'session' } } }
  const scope = snapshotSecurityScope(store)
  store.state.user.token = 'new-session'; expect(snapshotSecurityScope(store)).not.toBe(scope)
  store.state.user.token = 'session'; store.getters.project.id = 'other'; expect(snapshotSecurityScope(store)).not.toBe(scope)
})

test('archive diagnosis accepts its historical response name without changing the posted API', async () => {
  postAPI.mockResolvedValue({ createsnapshotresponse: { snapshotid: 'snapshot', scopehash: 'archive-revision', eligible: true } })
  const context = await freshStorageSnapshotContext(target, 'archiveSnapshot')
  expect(context.reason).toBe(''); expect(context.diagnosis.scopehash).toBe('archive-revision')
  expect(postAPI.mock.calls[0][0]).toBe('archiveSnapshot')
})

test.each(['errorresponse', 'listapisresponse'])('revoked named API discovery in %s becomes an explicit permission reason', async envelope => {
  getAPI.mockRejectedValue({ response: { status: 530, data: { [envelope]: { errortext: 'Api Discovery plugin was unable to find an api by that name or process any apis' } } } })
  await expect(freshStorageSnapshotContext(target, 'createVolume')).rejects.toThrow('message.snapshot.action.permission')
  expect(postAPI).not.toHaveBeenCalled()
})

test('context switch after the first source read does not issue later reads in another scope', async () => {
  let current = true
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation((api, args) => {
    const result = original(api, args)
    if (api === 'listSnapshotCopyTargets') return Promise.resolve({ listsnapshotcopytargetsresponse: { snapshotcopytargets: { snapshotid: 'snapshot', sourcezoneid: args.sourcezoneid, eligible: true, reason: '', scopehash: 'a'.repeat(64), destinations: [] } } })
    if (api === 'listSnapshots') current = false
    return result
  })
  await expect(freshStorageSnapshotContext(target, 'createVolume', () => current)).rejects.toThrow('message.snapshot.action.context.changed')
  expect(getAPI.mock.calls.map(([api]) => api)).toEqual(['listApis', 'listSnapshots'])
  expect(postAPI).not.toHaveBeenCalled()
})
