// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount, flushPromises } from '@vue/test-utils'
import Editor from '@/views/storage/UpdateVolume.vue'
import { volumeEditParams, volumePathEditReason, validateVolumeEditSnapshot, canDeleteVolume, volumeDeleteReason } from '@/utils/volumeEdit'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const volume = { id: 'vol-1', name: 'before', deleteprotection: false, path: 'image-original', chaininfo: 'chain-original', storageid: 'pool-1', state: 'Ready', type: 'DATADISK', account: 'owner', domainid: 'domain-1' }
const admin = { admin: true, params: { name: {}, path: {}, deleteprotection: {} } }
const values = () => ({ name: volume.name, deleteprotection: false, path: volume.path, pathEnabled: false })
test.each(['User', 'DomainAdmin', 'Project'])('%s sends only the changed public fields', role => {
  const ctx = { ...admin, admin: false, params: { name: {}, deleteprotection: {} } }
  expect(volumeEditParams(volume, { ...values(), name: 'after' }, ctx)).toEqual({ id: volume.id, name: 'after' })
  expect(volumeEditParams(volume, { ...values(), deleteprotection: true }, ctx)).toEqual({ id: volume.id, deleteprotection: true })
  expect(volumePathEditReason(volume, ctx)).toBe('message.volume.edit.path.admin')
})
test('unmodified controls produce no mutation; false is an explicit protection change', () => {
  expect(volumeEditParams(volume, values(), admin)).toEqual({ id: volume.id })
  expect(volumeEditParams({ ...volume, deleteprotection: true }, values(), admin)).toEqual({ id: volume.id, deleteprotection: false })
})
test.each(['', ' ', '\t'])('blank changed name/path is rejected: %p', blank => {
  expect(() => volumeEditParams(volume, { ...values(), name: blank }, admin)).toThrow('name.required')
  expect(() => volumeEditParams(volume, { ...values(), pathEnabled: true, path: blank }, admin)).toThrow('path.required')
})
test('hidden path is not sent and unchanged path is omitted', () => {
  expect(volumeEditParams(volume, { ...values(), path: 'ignored' }, admin)).toEqual({ id: volume.id })
  expect(volumeEditParams(volume, { ...values(), pathEnabled: true }, admin)).toEqual({ id: volume.id })
})
test.each([
  [{ state: 'Migrating' }, 'state'], [{ kvdoenable: true }, 'unsupported'],
  [{ allocation: { contractversion: '1' } }, 'unsupported'],
  [{ virtualmachineid: 'vm-1', vmstate: 'Running' }, 'stopped'],
  [{ virtualmachineid: 'vm-1' }, 'stopped']
])('path is blocked for %p', (patch, key) => {
  expect(volumePathEditReason({ ...volume, ...patch }, admin)).toContain(key)
  expect(() => volumeEditParams({ ...volume, ...patch }, { ...values(), pathEnabled: true, path: 'new' }, admin)).toThrow()
})
test.each(['Allocated', 'Ready', 'Uploaded'])('authorized path in %s is allowed', state => {
  const v = { ...volume, state, virtualmachineid: 'vm', vmstate: 'Stopped' }
  expect(volumePathEditReason(v, admin)).toBe('')
  expect(volumeEditParams(v, { ...values(), pathEnabled: true, path: 'new-reference' }, admin)).toEqual({ id: volume.id, path: 'new-reference' })
})
test.each(['path', 'chaininfo', 'storageid', 'virtualmachineid', 'account', 'domainid', 'projectid'])('stale %s prevents path mutation', key => {
  expect(() => validateVolumeEditSnapshot(volume, { ...volume, [key]: 'changed' }, { id: volume.id, path: 'new' }, admin)).toThrow()
})
test('unchanged protection is preserved even if another editor changed it', () => {
  expect(() => validateVolumeEditSnapshot(volume, { ...volume, deleteprotection: true }, { id: volume.id, name: 'after' }, admin)).not.toThrow()
  expect(() => validateVolumeEditSnapshot(volume, { ...volume, name: 'someone-else' }, { id: volume.id, name: 'after' }, admin)).toThrow('changed')
})
test('missing/foreign target and unsupported fields are rejected', () => {
  expect(() => validateVolumeEditSnapshot(volume, null, {}, admin)).toThrow()
  expect(() => validateVolumeEditSnapshot(volume, { ...volume, id: 'other' }, {}, admin)).toThrow()
  expect(() => volumeEditParams(volume, { ...values(), name: 'after' }, { ...admin, params: {} })).toThrow('forbidden')
})
test('mixed protected bulk selection is blocked, and valid unprotected selection remains available', () => {
  expect(volumeDeleteReason([volume, { ...volume, id: 'v2', deleteprotection: true }], {})).toBe('message.volume.delete.protected')
  expect(volumeDeleteReason([volume], { userInfo: { roletype: 'Admin' } })).toBe('')
  expect(volumeDeleteReason([{ ...volume, virtualmachineid: 'vm' }], {})).toContain('unavailable')
  expect(volumeDeleteReason([{ ...volume, clonefastflattenstatus: 'running' }], {})).toContain('flatten')
  expect(canDeleteVolume({ ...volume, state: 'Destroy' }, { userInfo: { roletype: 'User' }, features: {} })).toBe(false)
  expect(canDeleteVolume({ ...volume, state: 'Destroy' }, { userInfo: { roletype: 'DomainAdmin' }, features: {} })).toBe(true)
  expect(canDeleteVolume(volume, { userInfo: { roletype: 'User' }, features: {} })).toBe(false)
  expect(canDeleteVolume(volume, { userInfo: { roletype: 'User' }, features: { allowuserexpungerecovervolume: true } })).toBe(true)
  expect(volumeDeleteReason([volume], { userInfo: { roletype: 'User' }, features: {} }, 'destroyVolume')).toBe('')
})
let poll, store, route
function mount (role = 'Admin', resource = volume) {
  store = { getters: { userInfo: { id: 'actor-1', roletype: role }, project: null } }
  route = { path: '/volume/' + resource.id }
  return shallowMount(Editor, {
    props: { resource: { ...resource } },
    global: {
      directives: { 'ctrl-enter': {} },
      mocks: { $t: s => s, $store: store, $route: route, $getApiParams: () => ({ name: {}, deleteprotection: {}, ...(role === 'Admin' ? { path: {} } : {}) }), $pollJob: (...args) => poll(...args) },
      stubs: { 'a-form': { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve(), scrollToField: () => {} } }, 'a-form-item': { template: '<div><slot/></div>' }, 'a-button': { template: '<button><slot/></button>' } }
    }
  })
}
beforeEach(() => {
  getAPI.mockReset(); postAPI.mockReset()
  getAPI.mockResolvedValue({ listvolumesresponse: { volume: [{ ...volume }] } })
  postAPI.mockResolvedValue({ updatevolumeresponse: { jobid: 'job-1' } })
  poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
})
test('editor initializes current values without a disk-offering lookup', () => {
  const w = mount('User'); expect(w.vm.form.name).toBe('before'); expect(w.vm.form.deleteprotection).toBe(false)
  expect(w.vm.pathAvailable).toBe(false); expect(getAPI).not.toHaveBeenCalled(); w.unmount()
})
test('successful edit waits for completion and refreshes only the captured resource', async () => {
  let finish; poll.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const w = mount(); w.vm.form.name = 'after'
  const pending = w.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('updateVolume', { id: 'vol-1', name: 'after' }, { preserveOnFailure: true })
  expect(w.emitted('close-action')).toBeUndefined(); expect(w.vm.loading).toBe(true)
  finish({ jobstatus: 1 }); await pending
  expect(w.emitted('refresh-data')).toHaveLength(1); expect(w.emitted('close-action')).toHaveLength(1); w.unmount()
})
test('duplicate submit during validation/read sends exactly one mutation', async () => {
  const w = mount(); w.vm.form.name = 'after'
  await Promise.all([w.vm.handleSubmit(), w.vm.handleSubmit()])
  expect(postAPI).toHaveBeenCalledTimes(1); w.unmount()
})
test('network and permission failures preserve input and target for an explicit retry', async () => {
  const w = mount('DomainAdmin'); w.vm.form.name = 'after'; w.vm.form.deleteprotection = true
  postAPI.mockRejectedValueOnce({ response: { status: 403, data: { updatevolumeresponse: { errortext: 'permission denied evidence' } } } })
  await w.vm.handleSubmit(); expect(w.vm.errorText).toBe('permission denied evidence')
  expect(w.vm.form.name).toBe('after'); expect(w.vm.form.deleteprotection).toBe(true); expect(w.vm.target.id).toBe('vol-1'); expect(w.emitted('close-action')).toBeUndefined()
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(w.emitted('refresh-data')).toHaveLength(1); w.unmount()
})
test('known job with unknown result is polled again without another update', async () => {
  const w = mount(); w.vm.form.name = 'after'; poll.mockResolvedValueOnce({ jobstatus: null, trackingStatus: 'unknown' })
  await w.vm.handleSubmit(); expect(w.vm.jobId).toBe('job-1')
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
  await w.vm.resumeJob(); expect(poll.mock.calls[1][0]).toMatchObject({ jobId: 'job-1', batchKey: 'job-1', retry: true }); expect(postAPI).toHaveBeenCalledTimes(1); w.unmount()
})
test('async job failure keeps the form open with the server reason', async () => {
  const w = mount(); w.vm.form.name = 'after'; poll.mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'stale state rejection' } })
  await w.vm.handleSubmit(); expect(w.vm.jobId).toBe(null); expect(w.vm.errorText).toBe('stale state rejection'); expect(w.emitted('close-action')).toBeUndefined(); w.unmount()
})
test('a stale changed field is rejected before submission', async () => {
  const w = mount(); w.vm.form.name = 'after'; getAPI.mockResolvedValueOnce({ listvolumesresponse: { volume: [{ ...volume, name: 'other editor' }] } })
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.errorText).toContain('changed'); expect(w.vm.form.name).toBe('after'); w.unmount()
})
test('target/project changes while reading cannot mutate a different resource', async () => {
  let finish; getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const w = mount(); w.vm.form.name = 'after'; const pending = w.vm.handleSubmit(); await flushPromises()
  store.getters.project = { id: 'other-project' }; finish({ listvolumesresponse: { volume: [volume] } }); await pending
  expect(postAPI).not.toHaveBeenCalled(); expect(w.emitted('refresh-data')).toBeUndefined(); w.unmount()
})
test('a previously successful ambiguous request is reconciled without resubmission', async () => {
  const w = mount(); w.vm.form.name = 'after'; getAPI.mockResolvedValueOnce({ listvolumesresponse: { volume: [{ ...volume, name: 'after' }] } })
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.emitted('refresh-data')).toHaveLength(1); w.unmount()
})
test('project lookup includes project ID while mutation contains only ID and changed fields', async () => {
  const resource = { ...volume, projectid: 'project-1' }; const w = mount('User', resource)
  getAPI.mockResolvedValueOnce({ listvolumesresponse: { volume: [resource] } }); w.vm.form.deleteprotection = true
  await w.vm.handleSubmit()
  expect(getAPI.mock.calls[0][1]).toMatchObject({ id: 'vol-1', projectid: 'project-1' })
  expect(postAPI.mock.calls[0][1]).toEqual({ id: 'vol-1', deleteprotection: true }); w.unmount()
})
