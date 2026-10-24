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
import { shallowMount, flushPromises } from '@vue/test-utils'
import Dialog from '@/views/storage/StorageSnapshotActionDialog.vue'
import { freshStorageSnapshotContext } from '@/utils/storageSnapshotContext'
import { submitSnapshotOperation, clearStorageSnapshotOperations } from '@/utils/storageSnapshotOperations'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/storageSnapshotContext', () => ({ ...jest.requireActual('@/utils/storageSnapshotContext'), freshStorageSnapshotContext: jest.fn() }))
let wrapper, store, route
const row = { id: 'selected', name: 'original', volumeid: 'volume' }
beforeEach(() => {
  clearStorageSnapshotOperations(); jest.clearAllMocks()
  store = { getters: { userInfo: { id: 'admin', roletype: 'Admin' }, project: {} }, state: { user: { token: 'session' } } }
  route = { path: '/snapshot' }
  freshStorageSnapshotContext.mockImplementation(async target => ({ target, record: target.record, source: {}, fingerprint: 'initial', reason: '', diagnosis: { scopehash: 'hash' } }))
  postAPI.mockResolvedValue({ deletesnapshotresponse: { jobid: 'job' } })
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { success: true } } })
})
afterEach(() => { if (wrapper) wrapper.unmount(); clearStorageSnapshotOperations() })
async function mount (rows = [row]) {
  wrapper = shallowMount(Dialog, { props: { currentAction: { api: 'deleteSnapshot', storageSnapshotTargets: rows } }, global: { mocks: { $store: store, $route: route, $t: x => x } } })
  await flushPromises(); return wrapper
}
test('selection and resource replacement cannot retarget a confirmed mutation', async () => {
  await mount(); await wrapper.setData({ acknowledged: true })
  await wrapper.setProps({ resource: { id: 'different' }, currentAction: { api: 'revertSnapshot', label: 'label.action.revert.snapshot', storageSnapshotTargets: [{ id: 'different' }] } })
  expect(wrapper.vm.actionLabel).toBe('label.action.delete.snapshot')
  expect(wrapper.vm.isRestore).toBe(false)
  await wrapper.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0]).toEqual(['deleteSnapshot', { id: 'selected', expectedscopehash: 'hash', customjobid: expect.any(String) }, { preserveOnFailure: true }])
})
test('fresh state drift blocks all selected mutations before the first POST', async () => {
  await mount([row, { ...row, id: 'second' }]); await wrapper.setData({ acknowledged: true })
  freshStorageSnapshotContext.mockImplementation(async target => ({ target, record: target.record, source: {}, fingerprint: target.id === 'second' ? 'changed' : 'initial' }))
  await wrapper.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.vm.error).toBe('message.snapshot.action.changed')
})
test.each(['route', 'project', 'login'])('%s switch during preflight prevents mutation', async change => {
  await mount(); await wrapper.setData({ acknowledged: true })
  freshStorageSnapshotContext.mockImplementation(async target => {
    if (change === 'route') route.path = '/vm'
    if (change === 'project') store.getters.project.id = 'other'
    if (change === 'login') store.state.user.token = 'other'
    return { target, record: target.record, source: {}, fingerprint: 'initial' }
  })
  await wrapper.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('unknown first delivery halts the remaining bulk operations and reopening cannot resubmit', async () => {
  await mount([row, { ...row, id: 'second' }]); await wrapper.setData({ acknowledged: true })
  postAPI.mockRejectedValue(new Error('lost response')); getAPI.mockRejectedValue({ response: { status: 403 } })
  await wrapper.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
  await wrapper.setData({ acknowledged: true }); await wrapper.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
  expect(wrapper.vm.results[0].status).toBe('unknown')
})
test('failed refresh disables confirmation until a new successful review', async () => {
  await mount(); freshStorageSnapshotContext.mockRejectedValue(new Error('unavailable'))
  await wrapper.vm.refresh(); expect(wrapper.vm.blocked).toBe(true)
})
test('another entry point holds the resource while its validation is pending', async () => {
  await mount(); let release
  const pending = submitSnapshotOperation({ api: 'deleteSnapshot', scope: wrapper.vm.security, target: row, keys: ['snapshot:selected'], current: () => true, securityCurrent: () => true, validate: () => new Promise(resolve => { release = resolve }) })
  await wrapper.setData({ acknowledged: true }); await wrapper.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
  release({ id: 'selected' }); await pending
})

test('download uses VM dialog size and fresh frozen source with one async submission', async () => {
  const snapshot = { ...row, zoneid: 'zone' }
  wrapper = shallowMount(Dialog, { props: { currentAction: { api: 'extractSnapshot', label: 'label.action.download.snapshot' }, resource: snapshot }, global: { mocks: { $store: store, $route: route, $t: x => x }, stubs: { MoldDialog: { name: 'MoldDialog', props: ['size'], template: '<div><slot /></div>' } } } })
  await flushPromises()
  expect(wrapper.vm.isStandard).toBe(true); expect(wrapper.vm.isDownload).toBe(true)
  expect(wrapper.findComponent({ name: 'StorageSnapshotSafety' }).props('recoveryMessage')).toBe('message.snapshot.download.source.absent')
  expect(wrapper.findComponent({ name: 'MoldDialog' }).props('size')).toBe('md')
  postAPI.mockResolvedValue({ extractsnapshotresponse: { jobid: 'download-job' } })
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { snapshot: { url: 'http://ssvm/file.qcow2', filename: 'file.qcow2', format: 'QCOW2', size: 1073741824, physicalsize: 2490368 } } } })
  await wrapper.vm.submit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toEqual({ id: 'selected', zoneid: 'zone', expectedscopehash: 'hash', customjobid: expect.any(String) })
  expect(wrapper.emitted('close-action')).toHaveLength(1)
})

test('archive uses the VM standard, requires acknowledgement and submits one pinned async job', async () => {
  freshStorageSnapshotContext.mockImplementation(async target => ({ target, record: target.record, source: {}, reason: '', fingerprint: 'initial', diagnosis: { scopehash: 'hash', eligible: true, reason: 'REUSE_SECONDARY' } }))
  wrapper = shallowMount(Dialog, { props: { resource: row, currentAction: { api: 'archiveSnapshot', label: 'label.action.archive.snapshot' } }, global: { mocks: { $store: store, $route: route, $t: x => x }, stubs: { MoldDialog: { name: 'MoldDialog', props: ['size'], template: '<div><slot /><slot name="footer" /></div>' } } } })
  await flushPromises(); expect(wrapper.vm.isArchive).toBe(true); expect(wrapper.findComponent({ name: 'MoldDialog' }).props('size')).toBe('md')
  await wrapper.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
  await wrapper.setData({ acknowledged: true }); postAPI.mockResolvedValue({ createsnapshotresponse: { jobid: 'archive-job' } }); await wrapper.vm.submit(); await flushPromises()
  expect(postAPI.mock.calls[0]).toEqual(['archiveSnapshot', { id: 'selected', expectedscopehash: 'hash', customjobid: expect.any(String) }, { preserveOnFailure: true }]); expect(wrapper.emitted('close-action')).toHaveLength(1)
})

test('unsupported archive diagnosis blocks confirmation without posting a mutation', async () => {
  freshStorageSnapshotContext.mockImplementation(async target => ({ target, record: target.record, source: {}, reason: 'AMBIGUOUS_REFERENCES', fingerprint: 'initial', diagnosis: { scopehash: 'hash', eligible: false, reason: 'AMBIGUOUS_REFERENCES' } }))
  wrapper = shallowMount(Dialog, { props: { resource: row, currentAction: { api: 'archiveSnapshot' } }, global: { mocks: { $store: store, $route: route, $t: x => x } } }); await flushPromises(); await wrapper.setData({ acknowledged: true }); await wrapper.vm.submit()
  expect(wrapper.vm.blocked).toBe(true); expect(postAPI).not.toHaveBeenCalled()
})
