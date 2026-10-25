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
import '../../utils/cryptoRandom'
import { shallowMount } from '@vue/test-utils'
import Recovery from '@/views/storage/SnapshotDeletionRecovery.vue'
import { getAPI, postAPI } from '@/api'
import { clearStorageSnapshotOperations } from '@/utils/storageSnapshotOperations'
import storageSection from '@/config/section/storage'
jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: {} } }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const id = '00000000-0000-0000-0000-000000000001'
const zoneid = '00000000-0000-0000-0000-000000000002'
const preview = { snapshotid: id, name: 'Disposable fixture', state: 'Destroyed', scopehash: 'revision', reference: [{ referenceid: 7, state: 'Destroying', storename: 'Secondary' }] }
let wrapper
function mount (roletype) {
  wrapper = shallowMount(Recovery, { global: { renderStubDefaultSlot: true, mocks: { $t: x => x, $route: { path: '/snapshot' }, $store: { getters: { userInfo: { id: 'admin', roletype }, project: {} } } } } })
  return wrapper
}
async function diagnosed () {
  mount(); await wrapper.setData({ snapshotId: id, zoneId: zoneid })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'diagnosis' } })
  getAPI.query.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: preview } })
  await wrapper.vm.diagnose()
}
beforeEach(() => {
  clearStorageSnapshotOperations(); getAPI.mockReset(); postAPI.mockReset()
  const query = jest.fn()
  getAPI.mockImplementation((api, args) => api === 'listApis' ? Promise.resolve({ listapisresponse: { api: [{ name: args.name }] } }) : query(api, args))
  getAPI.query = query
})
afterEach(() => wrapper?.unmount())

test('diagnosis does not perform deletion and requires confirmation', async () => {
  await diagnosed()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('deleteSnapshot', { id, zoneid, dryrun: true })
  expect(wrapper.vm.preview).toEqual(preview)
  expect(wrapper.vm.canRecover).toBe(false)
})
test('input changes invalidate the inspected target and confirmation', async () => {
  await diagnosed(); await wrapper.setData({ acknowledged: true })
  expect(wrapper.vm.canRecover).toBe(true)
  await wrapper.setData({ zoneId: '' })
  expect(wrapper.vm.preview).toBeNull()
  expect(wrapper.vm.canRecover).toBe(false)
})
test('late diagnosis cannot overwrite a new target', async () => {
  mount(); await wrapper.setData({ snapshotId: id })
  let complete
  postAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve }))
  const pending = wrapper.vm.diagnose()
  await wrapper.setData({ snapshotId: zoneid })
  complete({ deletesnapshotresponse: preview }); await pending
  expect(wrapper.vm.preview).toBeNull()
})
test('execution submits only the inspected scope and revision once', async () => {
  await diagnosed(); await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: preview }).mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'delete-job' } })
  getAPI.query.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { success: true } } })
  const first = wrapper.vm.recover(); await wrapper.vm.recover(); await first
  expect(postAPI).toHaveBeenLastCalledWith('deleteSnapshot', { id, zoneid, expectedscopehash: 'revision', customjobid: expect.any(String) }, { preserveOnFailure: true })
  expect(postAPI).toHaveBeenCalledTimes(3)
  expect(wrapper.vm.completed).toBe(true)
  expect(wrapper.emitted('refresh-data')).toHaveLength(1)
})
test('unknown job results retain the job and query again without resubmitting', async () => {
  await diagnosed(); await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: preview }).mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'delete-job' } })
  getAPI.query.mockRejectedValue({ response: { status: 403 } })
  await wrapper.vm.recover()
  expect(wrapper.vm.pendingJob).toBe('delete-job')
  expect(wrapper.vm.canRecover).toBe(false)
  await wrapper.vm.recover()
  getAPI.query.mockReset().mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { success: true } } })
  await wrapper.vm.checkResult()
  expect(postAPI).toHaveBeenCalledTimes(3)
  expect(wrapper.vm.completed).toBe(true)
})
test('terminal job failure clears confirmation and requires a new diagnosis', async () => {
  await diagnosed(); await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: preview }).mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'delete-job' } })
  getAPI.query.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'store unavailable' } } })
  await wrapper.vm.recover()
  expect(wrapper.vm.pendingJob).toBe('')
  expect(wrapper.vm.preview).toBeNull()
  expect(wrapper.vm.error).toBe('store unavailable')
})
test('unknown submission without a job id cannot resubmit', async () => {
  await diagnosed(); await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: preview }).mockRejectedValueOnce(new Error('connection closed'))
  await wrapper.vm.recover(); await wrapper.vm.recover()
  expect(wrapper.vm.canRecover).toBe(false)
  expect(postAPI).toHaveBeenCalledTimes(3)
})
test('invalid UUID is rejected before any API request', async () => {
  mount(); await wrapper.setData({ snapshotId: 'wrong' }); await wrapper.vm.diagnose()
  expect(postAPI).not.toHaveBeenCalled()
})
test('project change disables execution and ignores diagnosis', async () => {
  await diagnosed(); await wrapper.setData({ acknowledged: true })
  wrapper.vm.$store.getters.project = { id: 'other' }
  expect(wrapper.vm.dialogCurrent()).toBe(false)
  await wrapper.vm.recover()
  expect(postAPI).toHaveBeenCalledTimes(1)
})

test('recovery action belongs only to the snapshot list and preserves normal detail deletion', () => {
  const snapshot = storageSection.children.find(x => x.name === 'snapshot')
  const volume = storageSection.children.find(x => x.name === 'volume')
  expect(volume.actions.some(x => x.label === 'label.snapshot.recovery')).toBe(false)
  const action = snapshot.actions.find(x => x.label === 'label.snapshot.recovery')
  expect(action.listView).toBe(true)
  expect(action.dataView).not.toBe(true)
  const normal = snapshot.actions.find(x => x.label === 'label.action.delete.snapshot')
  expect(normal.dataView).toBe(true)
  expect(normal.groupAction).toBe(true)
})

async function forcedDiagnosis () {
  mount('Admin'); await wrapper.setData({ snapshotId: id, force: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: { ...preview, state: 'BackedUp', force: true } })
  await wrapper.vm.diagnose()
}
test('forced diagnosis and execution preserve explicit force mode and confirmation', async () => {
  await forcedDiagnosis()
  expect(postAPI).toHaveBeenCalledWith('deleteSnapshot', { id, force: true, dryrun: true })
  expect(wrapper.vm.canRecover).toBe(false)
  await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: { ...preview, force: true } }).mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'force-job' } })
  getAPI.query.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { deletesnapshotresponse: { force: true, state: 'Destroyed', physicalcleanupcomplete: true, dbcleanupcomplete: true } } } })
  await wrapper.vm.recover()
  expect(postAPI).toHaveBeenLastCalledWith('deleteSnapshot', { id, force: true, expectedscopehash: 'revision', customjobid: expect.any(String) }, { preserveOnFailure: true })
  expect(wrapper.vm.completed).toBe(true)
  expect(wrapper.vm.dbOnlyResult).toBe(false)
  expect(wrapper.vm.preview.state).toBe('Destroyed')
  expect(wrapper.vm.preview.reference[0].state).toBe('Destroyed')
})
test('database-only forced result is retained and displayed as unverified physical cleanup', async () => {
  await forcedDiagnosis(); await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: { ...preview, force: true } }).mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'force-job' } })
  getAPI.query.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { deletesnapshotresponse: { force: true, state: 'Destroyed', physicalcleanupcomplete: false, dbcleanupcomplete: true, pendingcleanup: 1, cleanupresult: [{ referenceid: 7, role: 'Primary', action: 'DB_ONLY' }] } } } })
  await wrapper.vm.recover()
  expect(wrapper.vm.dbOnlyResult).toBe(true)
  expect(wrapper.vm.executionResult.pendingcleanup).toBe(1)
  expect(wrapper.vm.pendingJob).toBe('')
  expect(wrapper.vm.canRecover).toBe(false)
})
test('changing force mode invalidates the inspected target and acknowledgement', async () => {
  await forcedDiagnosis(); await wrapper.setData({ acknowledged: true })
  expect(wrapper.vm.canRecover).toBe(true)
  await wrapper.setData({ force: false })
  expect(wrapper.vm.preview).toBeNull()
  expect(wrapper.vm.canRecover).toBe(false)
})
test('normal owner cannot request forced diagnosis', async () => {
  mount('User'); await wrapper.setData({ snapshotId: id, force: true })
  await wrapper.vm.diagnose()
  expect(postAPI).not.toHaveBeenCalled()
  expect(wrapper.vm.error).toBe('message.snapshot.force.admin')
})
test('legacy normal response cannot authorize a forced operation', async () => {
  mount('Admin'); await wrapper.setData({ snapshotId: id, force: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: preview })
  await wrapper.vm.diagnose()
  expect(wrapper.vm.preview).toBeNull()
  expect(wrapper.vm.canRecover).toBe(false)
})

test('a normal success response cannot claim completion of forced database cleanup', async () => {
  await forcedDiagnosis(); await wrapper.setData({ acknowledged: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: { ...preview, force: true } }).mockResolvedValueOnce({ deletesnapshotresponse: { jobid: 'force-job' } })
  getAPI.query.mockResolvedValueOnce({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { success: true } } })
  await wrapper.vm.recover()
  expect(wrapper.vm.completed).toBe(false)
  expect(wrapper.vm.pendingJob).toBe('force-job')
  expect(wrapper.vm.canRecover).toBe(false)
})

test('unmapped forced diagnosis explains database cleanup instead of a physical no-op', async () => {
  mount('Admin'); await wrapper.setData({ snapshotId: id, force: true })
  postAPI.mockResolvedValueOnce({ deletesnapshotresponse: { ...preview, force: true, reference: [], cleanupresult: [{ referenceid: 0, action: 'DB_ONLY', reason: 'NO_IDENTIFIABLE_STORE_REFERENCE' }] } })
  await wrapper.vm.diagnose()
  expect(wrapper.vm.unmappedPlan).toBe(true)
  expect(wrapper.vm.canRecover).toBe(false)
})
