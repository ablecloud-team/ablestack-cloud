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
import { reactive, nextTick } from 'vue'
import HeaderNotice from '@/components/header/HeaderNotice.vue'
import Dialog from '@/components/view/StorageSnapshotJobDialog.vue'
import AutogenView from '@/views/AutogenView.vue'
import { getAPI, postAPI } from '@/api'
import { snapshotSecurityScope } from '@/utils/storageSnapshotContext'
import { storageSnapshotOperations, operationScopeKey, clearStorageSnapshotOperations, hydrateSnapshotOperations, snapshotOperationBusy } from '@/utils/storageSnapshotOperations'
import { snapshotOperationAffectsRoute } from '@/utils/storageSnapshotNotices'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
let store, scope, wrapper
function entry (id = 'one', status = 'unknown') {
  storageSnapshotOperations[id] = { id, scope: operationScopeKey(scope), api: 'createVolume', target: { id: 'original', name: 'Original', kind: 'snapshot' }, keys: ['snapshot:original', 'volume:source'], status, requestid: 'aabbccdd-0000-4000-8000-111111111111', jobid: '', created: '2026-10-23T02:00:00Z' }
  return storageSnapshotOperations[id]
}
function mount (component, props = {}) {
  wrapper = shallowMount(component, { props, global: { mocks: { $store: store, $i18n: { locale: 'ko_KR' }, $t: x => x }, stubs: { 'a-popover': { template: '<div><slot name="content"/><slot/></div>' } } } })
  return wrapper
}
beforeEach(() => {
  clearStorageSnapshotOperations(); jest.clearAllMocks()
  store = reactive({ getters: { apis: {}, headerNotices: [], userInfo: { id: 'owner', roletype: 'Admin', account: 'admin', domainid: 'domain' }, project: {} }, state: { user: { token: 'session' } }, commit: jest.fn((_, value) => { store.getters.headerNotices = value }) })
  scope = snapshotSecurityScope(store)
  getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { volume: { id: 'restored' } } } })
})
afterEach(() => { if (wrapper) wrapper.unmount(); clearStorageSnapshotOperations() })
test('notifications merge localized registry entries without mutating or duplicating generic notices', () => {
  const e = entry(); e.jobid = 'known'
  store.getters.headerNotices = [{ key: 'old', title: 'Generic' }, { key: 'known', title: 'Duplicate' }]
  const before = [...store.getters.headerNotices]
  mount(HeaderNotice)
  expect(wrapper.classes()).toContain('header-notice')
  expect(wrapper.vm.notices.map(n => n.title)).toEqual(['label.action.create.volume', 'Generic'])
  expect(store.getters.headerNotices).toEqual(before)
  expect(wrapper.vm.notices[0].targetName).toBe('Original')
})
test.each(['checking', 'submitting', 'accepted', 'unknown'])('clear list retains %s lease, including after F5', status => {
  entry('pending', status); entry('complete', 'complete'); entry('failed', 'failed')
  store.getters.headerNotices = [{ key: 'uncertain', status: 'unknown' }, { key: 'done', status: 'done' }]
  mount(HeaderNotice); wrapper.vm.clearJobs()
  expect(wrapper.vm.notices.filter(n => n.operationId).map(n => n.operationId)).toEqual(['pending'])
  expect(snapshotOperationBusy(scope, ['snapshot:original'])).toBe(true)
  expect(store.getters.headerNotices).toEqual([{ key: 'uncertain', status: 'unknown' }])
  hydrateSnapshotOperations()
  expect(storageSnapshotOperations.complete.dismissed).toBe(true)
  // A preflight interrupted by F5 is rejected; every possibly submitted job stays blocked.
  expect(snapshotOperationBusy(scope, ['snapshot:original'])).toBe(status !== 'checking')
})
test('account or project scope hides jobs and closes the selected dialog', async () => {
  entry(); mount(HeaderNotice); wrapper.vm.openOperation('one')
  store.getters.project.id = 'another'; await nextTick()
  expect(wrapper.vm.selectedOperation).toBe(''); expect(wrapper.vm.notices).toEqual([])
  delete store.getters.project.id; await nextTick()
  expect(wrapper.vm.notices).toHaveLength(1)
})
test('job detail selects a single frozen target and never calls the generic job poller or mutation API', async () => {
  entry(); entry('two')
  mount(Dialog, { entryId: 'one' }); await wrapper.vm.check()
  expect(wrapper.vm.entry.target.id).toBe('original')
  expect(wrapper.vm.resultPath).toBe('/volume/restored')
  expect(getAPI.mock.calls[0][1]).toEqual({ jobid: 'aabbccdd-0000-4000-8000-111111111111' })
  expect(postAPI).not.toHaveBeenCalled(); expect(storageSnapshotOperations.two.status).toBe('unknown')
})
test('completed job result links are restored through the same job after F5', async () => {
  const e = entry('one', 'complete'); e.jobid = 'existing'; e.result = { jobstatus: 1 }
  mount(Dialog, { entryId: 'one' }); await flushPromises()
  expect(wrapper.vm.resultPath).toBe('/volume/restored'); expect(getAPI).toHaveBeenCalledTimes(1); expect(postAPI).not.toHaveBeenCalled()
})
test('query failure and dialog close retain the lease', async () => {
  const e = entry(); getAPI.mockRejectedValue({ response: { status: 403 } })
  mount(Dialog, { entryId: 'one' }); await wrapper.vm.check(); wrapper.vm.close()
  expect(e.status).toBe('unknown'); expect(wrapper.vm.errorText).toBe('message.snapshot.job.result.unknown'); expect(snapshotOperationBusy(scope, e.keys)).toBe(true)
  expect(wrapper.emitted('close')).toHaveLength(1); expect(postAPI).not.toHaveBeenCalled()
})
test('foreign scope cannot query, connect a job or release a lease', async () => {
  const e = entry(); mount(Dialog, { entryId: 'one' }); store.state.user.token = 'replacement'; await nextTick()
  await wrapper.vm.check(); await wrapper.vm.attach(); wrapper.vm.confirmed = true; wrapper.vm.release()
  expect(wrapper.vm.entry).toBe(null); expect(getAPI).not.toHaveBeenCalled(); expect(e.status).toBe('unknown')
})
test('manual release and job connection remain explicit acknowledgements', async () => {
  const e = entry(); mount(Dialog, { entryId: 'one' })
  wrapper.vm.release(); expect(e.status).toBe('unknown')
  wrapper.vm.jobId = 'bbbbbbbb-0000-4000-8000-111111111111'; wrapper.vm.jobConfirmed = true
  await wrapper.vm.attach(); expect(wrapper.vm.error).toBe('message.snapshot.action.job.mismatch'); expect(getAPI).not.toHaveBeenCalled()
  wrapper.vm.confirmed = true; wrapper.vm.release(); expect(e.status).toBe('released')
})
test('force deletion distinguishes database cleanup from physical remnants', () => {
  const e = entry('one', 'complete'); e.api = 'deleteSnapshot'; e.result = { jobstatus: 1, jobresult: { force: true, dbcleanupcomplete: true, physicalcleanupcomplete: false } }
  mount(Dialog, { entryId: 'one' }); expect(wrapper.vm.forceResult.physicalcleanupcomplete).toBe(false); expect(wrapper.vm.resultPath).toBe('')
})
test('terminal refresh is limited to affected snapshot or volume routes', () => {
  const e = entry('one', 'accepted')
  expect(snapshotOperationAffectsRoute(e, { name: 'vm', params: { id: 'elsewhere' } })).toBe(false)
  expect(snapshotOperationAffectsRoute(e, { name: 'snapshot', params: { id: 'another' } })).toBe(false)
  expect(snapshotOperationAffectsRoute(e, { name: 'snapshot', params: { id: 'original' } })).toBe(true)
  expect(snapshotOperationAffectsRoute(e, { name: 'volume', params: {} })).toBe(true)
  const view = { $route: { name: 'snapshot', params: { id: 'original' } }, fetchData: jest.fn() }
  AutogenView.watch.snapshotOperationCompletions.call(view, [{ ...e, status: 'complete' }], [{ ...e }])
  expect(view.fetchData).toHaveBeenCalledWith({ irefresh: true })
  view.fetchData.mockClear(); view.$route.name = 'vm'
  AutogenView.watch.snapshotOperationCompletions.call(view, [{ ...e, status: 'complete' }], [{ ...e }])
  expect(view.fetchData).not.toHaveBeenCalled()
})

test('an unresolved result remains visible even if its old terminal notification was dismissed', () => {
  const e = entry('one', 'complete'); mount(HeaderNotice); wrapper.vm.clearJobs(); e.status = 'unknown'
  expect(wrapper.vm.notices[0].operationId).toBe('one')
  expect(snapshotOperationBusy(scope, e.keys)).toBe(true)
})

test('an incomplete force response never claims database cleanup completion', () => {
  const e = entry(); e.api = 'deleteSnapshot'; e.result = { jobstatus: 1, jobresult: { force: true, physicalcleanupcomplete: false } }
  mount(Dialog, { entryId: 'one' }); expect(wrapper.vm.forceResult).toBe(null)
})

test('download result exposes escaped structured metadata and a safe link in the common dialog', () => {
  const e = entry('download', 'complete'); e.api = 'extractSnapshot'; e.result = { jobstatus: 1, jobresult: { snapshot: { url: 'http://ssvm/backup.raw', filename: 'backup.raw', format: 'RAW', size: 1073741824, physicalsize: 1073741824 } } }
  wrapper = shallowMount(Dialog, { props: { entryId: 'download' }, global: { mocks: { $store: store, $i18n: { locale: 'ko_KR' }, $t: x => x }, stubs: { MoldDialog: { template: '<div><slot /></div>' } } } })
  expect(wrapper.vm.download.filename).toBe('backup.raw')
  expect(wrapper.vm.resultPath).toBe('')
  const link = wrapper.find('[data-testid="snapshot-download-link"]')
  expect(link.attributes('rel')).toBe('noopener noreferrer'); expect(link.attributes('download')).toBe('backup.raw')
  e.result.jobresult.snapshot.url = 'javascript:alert(1)'
  expect(wrapper.vm.download).toBeNull()
})
