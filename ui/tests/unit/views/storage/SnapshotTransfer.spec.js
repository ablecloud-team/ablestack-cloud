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
import { reactive } from 'vue'
import Dialog from '@/views/storage/SnapshotCopyDialog.vue'
import { freshStorageSnapshotContext } from '@/utils/storageSnapshotContext'
import { readSnapshotCopyTargets } from '@/utils/storageSnapshotTransfer'
import { clearStorageSnapshotOperations } from '@/utils/storageSnapshotOperations'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/storageSnapshotContext', () => ({ ...jest.requireActual('@/utils/storageSnapshotContext'), freshStorageSnapshotContext: jest.fn() }))
const row = { zoneid: 'dest', name: 'Destination', eligible: true, reason: '', method: 'SecondaryStorageVM', protocol: 'nfs', storeid: 'secondary' }
const plan = () => ({ snapshotid: 'snapshot', sourcezoneid: 'source', eligible: true, reason: '', scopehash: 'a'.repeat(64), destinations: [{ ...row }] })
let wrapper, store, route, currentPlan
async function mount () {
  wrapper = shallowMount(Dialog, { props: { resource: { id: 'snapshot', name: 'Frozen', volumeid: 'volume' }, sourceZone: { zoneid: 'source' } }, global: { mocks: { $store: store, $route: route, $t: x => x }, stubs: { MoldDialog: { name: 'MoldDialog', props: ['size'], template: '<div><slot /><slot name="footer" /></div>' } } } })
  await flushPromises(); return wrapper.vm
}
beforeEach(() => {
  clearStorageSnapshotOperations(); jest.clearAllMocks(); currentPlan = plan()
  store = reactive({ getters: { userInfo: { id: 'admin', roletype: 'Admin' }, project: {} }, state: { user: { token: 'session' } } }); route = { path: '/snapshot/snapshot' }
  freshStorageSnapshotContext.mockImplementation(async target => ({ target, record: target.record, source: {}, copies: [], reason: currentPlan.eligible ? '' : currentPlan.reason, fingerprint: currentPlan.scopehash, copyplan: currentPlan }))
  postAPI.mockResolvedValue({ copysnapshotresponse: { jobid: 'job' } }); getAPI.mockResolvedValue({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: { snapshot: { id: 'snapshot' } } } })
})
afterEach(() => { if (wrapper) wrapper.unmount(); clearStorageSnapshotOperations() })
test('normal Zone copy exposes no provider-specific controls and submits the pinned Secondary plan', async () => {
  const vm = await mount(); expect(wrapper.findComponent({ name: 'MoldDialog' }).props('size')).toBe('md')
  vm.selected = ['dest']; await vm.submit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toEqual({ id: 'snapshot', sourcezoneid: 'source', destzoneids: 'dest', expectedscopehash: 'a'.repeat(64), customjobid: expect.any(String) })
  const source = require('fs').readFileSync(require('path').resolve(__dirname, '../../../../src/views/storage/SnapshotCopyDialog.vue'), 'utf8')
  expect(source).not.toMatch(/useStorageReplication|storageids|listStoragePools|a-switch/)
})
test('successful empty lookup is distinct from failure and cannot submit', async () => {
  currentPlan.destinations = []; const vm = await mount(); expect(vm.error).toBe(''); expect(vm.available).toEqual([]); expect(vm.blocked).toBe(true); expect(wrapper.html()).toContain('message.snapshot.transfer.empty')
})
test('transient lookup failure preserves review but blocks submission until retry', async () => {
  const vm = await mount(); vm.selected = ['dest']; freshStorageSnapshotContext.mockRejectedValueOnce(new Error('503'))
  await vm.refresh(); expect(vm.error).toBe('message.snapshot.transfer.lookup.failed'); expect(vm.destinations).toHaveLength(1); expect(vm.selected).toEqual(['dest']); expect(vm.blocked).toBe(true)
  await vm.refresh(); expect(vm.error).toBe(''); expect(vm.blocked).toBe(false)
})
test('permission rejection clears previously visible destinations and selections', async () => {
  const vm = await mount(); vm.selected = ['dest']; freshStorageSnapshotContext.mockRejectedValueOnce({ response: { status: 403 } }); await vm.refresh()
  expect(vm.context).toBeNull(); expect(vm.selected).toEqual([]); expect(vm.error).toBe('message.snapshot.action.permission'); expect(vm.blocked).toBe(true)
})
test('completed and unsupported targets cannot be submitted while failed targets can be retried', async () => {
  currentPlan.destinations = [{ ...row, eligible: false, reason: 'ALREADY_PRESENT' }, { ...row, zoneid: 'retry', reason: 'RETRY' }, { ...row, zoneid: 'readonly', eligible: false, reason: 'READ_ONLY_IMAGE_STORE' }]
  const vm = await mount(); expect(vm.available.map(x => x.zoneid)).toEqual(['retry']); vm.selected = ['dest']; expect(vm.blocked).toBe(true); vm.selected = ['retry']; expect(vm.blocked).toBe(false)
})
test('target or store revision change prevents the first mutation', async () => {
  const vm = await mount(); vm.selected = ['dest']; currentPlan = { ...plan(), scopehash: 'b'.repeat(64) }; await vm.submit()
  expect(postAPI).not.toHaveBeenCalled(); expect(vm.error).toBe('message.snapshot.action.destination.changed')
})
test('project switch ignores a late lookup and closes the old dialog', async () => {
  const vm = await mount(); let release; freshStorageSnapshotContext.mockImplementationOnce(() => new Promise(resolve => { release = resolve })); const pending = vm.refresh(); await flushPromises()
  store.getters.project = { id: 'other' }; await flushPromises(); release({ copyplan: { destinations: [{ ...row, name: 'Stale' }] } }); await pending
  expect(vm.closed).toBe(true); expect(vm.context.copyplan.destinations[0].name).toBe('Destination'); expect(postAPI).not.toHaveBeenCalled()
})
test('source without a Ready Secondary copy is explicitly blocked', async () => {
  currentPlan.eligible = false; currentPlan.reason = 'NO_READY_SECONDARY'; const vm = await mount(); vm.selected = ['dest']; expect(vm.blocked).toBe(true); await vm.submit(); expect(postAPI).not.toHaveBeenCalled()
})
test('malformed, mixed-source and duplicate destination contracts fail closed', () => {
  const target = { id: 'snapshot', zoneId: 'source' }
  expect(readSnapshotCopyTargets({ listsnapshotcopytargetsresponse: { snapshotcopytargets: plan() } }, target).destinations).toHaveLength(1)
  for (const bad of [{ ...plan(), snapshotid: 'other' }, { ...plan(), scopehash: 'missing' }, { ...plan(), destinations: [row, row] }, { ...plan(), destinations: [{ ...row, protocol: 'rbd' }] }, { ...plan(), eligible: false }]) expect(() => readSnapshotCopyTargets({ listsnapshotcopytargetsresponse: { snapshotcopytargets: bad } }, target)).toThrow('message.snapshot.action.incomplete')
})

test('Ctrl+Enter submits from the copy selector without relying on a footer keyup listener', async () => {
  const vm = await mount(); vm.selected = ['dest']; let accept
  postAPI.mockReturnValue(new Promise(resolve => { accept = resolve }))
  await wrapper.find('.snapshot-transfer-content').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises()
  await wrapper.find('.snapshot-transfer-content').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  accept({ copysnapshotresponse: { jobid: 'keyboard-job' } }); await flushPromises()
  expect(wrapper.emitted('close-action')).toHaveLength(1)
})
