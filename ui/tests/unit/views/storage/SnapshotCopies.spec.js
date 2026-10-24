// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
import '../../utils/cryptoRandom'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Zones from '@/views/storage/SnapshotZones.vue'
import { readSnapshotCopyPage, snapshotCopyZones, snapshotCopySize } from '@/utils/snapshotCopies'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
const row = (copy, role = 'Image', zone = 'z', id = 's') => ({ id, name: 'snapshot', zoneid: zone, zonename: zone, state: 'BackedUp', datastoreid: copy, copy: { id: copy, role, state: 'Ready', availability: 'Ready', datastoreknown: true, virtualsize: 1073741824 } })
const response = (rows, count = rows.length) => ({ listsnapshotsresponse: { count, snapshot: rows } })
let wrapper, store, deferred
async function mount () {
  store = reactive({ getters: { apis: { listSnapshots: {}, listZones: {}, listStoragePools: {}, listImageStores: {}, copySnapshot: {}, deleteSnapshot: {} }, userInfo: { id: 'admin', roletype: 'Admin' }, project: {} }, state: { user: { token: 'session' } } })
  wrapper = shallowMount(Zones, { props: { resource: { id: 's', state: 'BackedUp' } }, global: { mocks: { $store: store, $route: { path: '/snapshot/s', fullPath: '/snapshot/s?tab=zones', query: { tab: 'zones' } }, $t: x => x, $getApiParams: () => new Proxy({}, { get: () => ({ description: '' }) }), $notifyError: jest.fn() } } })
  await flushPromises(); return wrapper.vm
}
beforeEach(() => {
  jest.clearAllMocks(); deferred = null
  getAPI.mockImplementation((api, args) => {
    if (api === 'listZones') return Promise.resolve({ listzonesresponse: { zone: [] } })
    if (deferred) return new Promise(resolve => { deferred.resolve = resolve })
    return Promise.resolve(response([row('primary', 'Primary', 'z', args.id), row('secondary', 'Image', 'z', args.id)]))
  })
})
afterEach(() => { if (wrapper) wrapper.unmount() })
test('same Zone primary/secondary are both visible and only the Zone is selectable', async () => {
  const vm = await mount(); expect(vm.dataSource).toHaveLength(2); expect(vm.zoneGroups).toHaveLength(1); expect(vm.zoneGroups[0].copies.map(x => x.copy.id)).toEqual(['primary', 'secondary']); expect(vm.itemCount).toBe(2)
  vm.setSelection(['primary']); expect(vm.selectedItems).toEqual([])
  vm.setSelection(['zone:z']); vm.bulkActionConfirmation(); expect(vm.safetyDeleteAction.zoneScope).toBe(true); expect(vm.safetyDeleteAction.storageSnapshotTargets).toHaveLength(1); expect(vm.safetyDeleteAction.storageSnapshotTargets[0].zoneid).toBe('z'); expect(vm.safetyDeleteAction.storageSnapshotTargets[0].datastoreid).toBeUndefined()
})
test('server page is never deduplicated or replaced by a client-wide fetch', async () => {
  const vm = await mount(); getAPI.mockImplementation((api, args) => Promise.resolve(api === 'listZones' ? { listzonesresponse: {} } : response([row('last', 'Image')], 11)))
  vm.handleChangePage(2, 10); await flushPromises(); expect(vm.dataSource.map(x => x.copy.id)).toEqual(['last']); expect(vm.itemCount).toBe(11); expect(vm.page).toBe(2)
  expect(getAPI).toHaveBeenCalledWith('listSnapshots', expect.objectContaining({ page: 2, pagesize: 10, showunique: false }), { preserveOnFailure: true, timeout: 20000 })
})
test('copy removal clamps a now-empty last page and reloads its valid page', async () => {
  const vm = await mount(); vm.page = 2
  getAPI.mockImplementation((api, args) => api === 'listZones' ? Promise.resolve({ listzonesresponse: {} }) : Promise.resolve(args.page === 2 ? response([], 1) : response([row('kept')], 1)))
  await vm.fetchData(); await flushPromises(); expect(vm.page).toBe(1); expect(vm.dataSource[0].copy.id).toBe('kept'); expect(vm.itemCount).toBe(1)
})
test('a failed refresh retains the same page and count, and retry replaces them', async () => {
  const vm = await mount(); getAPI.mockRejectedValueOnce(new Error('503')); await vm.fetchData(); expect(vm.listRefreshFailed).toBe(true); expect(vm.dataSource).toHaveLength(2); expect(vm.itemCount).toBe(2)
  getAPI.mockResolvedValueOnce(response([], 0)); await vm.fetchData(); expect(vm.dataSource).toEqual([]); expect(vm.itemCount).toBe(0); expect(vm.listRefreshFailed).toBe(false)
})
test('late rows from a prior snapshot or owner cannot populate the new context', async () => {
  const vm = await mount(); const old = {}; deferred = old; const pending = vm.fetchData(); await flushPromises(); deferred = null
  await wrapper.setProps({ resource: { id: 'other', state: 'BackedUp' } }); await flushPromises(); old.resolve(response([row('stale')])); await pending
  expect(vm.dataSource.every(x => x.id === 'other')).toBe(true); expect(vm.selectedRowKeys).toEqual([])
})
test('project switch clears the old page and passes the new project explicitly', async () => {
  const vm = await mount(); vm.setSelection(['zone:z']); store.getters.project = { id: 'project' }; await flushPromises()
  expect(vm.selectedRowKeys).toEqual([]); expect(getAPI).toHaveBeenCalledWith('listSnapshots', expect.objectContaining({ projectid: 'project', page: 1 }), expect.any(Object))
})
test('permission removal clears rows without submitting another read or mutation', async () => {
  const vm = await mount(); getAPI.mockClear(); store.getters.apis = {}; await flushPromises(); expect(vm.dataSource).toEqual([]); expect(vm.itemCount).toBe(0); expect(getAPI).not.toHaveBeenCalled()
})
test('Zone and datastore links are distinct and restricted metadata never generates guessed links', async () => {
  const vm = await mount(); expect(vm.storePath(row('pool', 'Primary'))).toBe('/storagepool/pool'); expect(vm.storePath(row('store'))).toBe('/imagestore/store')
  expect(vm.storePath({ copy: { role: 'Primary' } })).toBe(''); store.getters.apis = { listSnapshots: {} }; expect(vm.storePath(row('pool', 'Primary'))).toBe('')
})
test('unknown Zone reference is retained but cannot become a mutation target', async () => {
  const vm = await mount(); vm.dataSource = [row('unknown', null, null)]; vm.setSelection(['zone:unknown']); vm.bulkActionConfirmation(); expect(vm.zoneGroups).toHaveLength(1); expect(vm.selectedItems).toEqual([]); expect(vm.safetyDeleteAction).toBeNull()
})
test('copy dialog uses the VM standard wrapper and footer', () => {
  const source = require('fs').readFileSync(require('path').resolve(__dirname, '../../../../src/views/storage/SnapshotCopyDialog.vue'), 'utf8')
  expect(source).toMatch(/<MoldDialog[\s\S]*?size="md"[\s\S]*?form-actions/); expect(source).toContain('<template #footer>'); expect(source).not.toContain('<a-modal')
})
test('page parser rejects mixed targets, duplicate references and incomplete contracts', () => {
  for (const rows of [[row('a'), row('a')], [row('a', 'Image', 'z', 'other')], [{ id: 's' }]]) expect(() => readSnapshotCopyPage(response(rows), 's', 10)).toThrow()
  expect(() => readSnapshotCopyPage({ listsnapshotsresponse: { count: 5 } }, 's', 10)).toThrow()
})
test('multiple Zones and missing sizes are represented without dropping references or fabricating zero', () => {
  expect(snapshotCopyZones([row('a'), row('b', 'Primary'), row('c', 'Image', 'other')]).map(x => x.copies.length)).toEqual([2, 1])
  expect(snapshotCopySize(null)).toBe(''); expect(snapshotCopySize(0)).toBe(''); expect(snapshotCopySize(1073741824)).toBe('1 GiB')
})

test('cancelling a bulk Zone deletion cannot turn a later single-Zone action into an empty or old bulk target', async () => {
  const vm = await mount(); vm.setSelection(['zone:z']); vm.bulkActionConfirmation(); vm.safetyDeleteAction = null; vm.resetSelection()
  vm.onShowDeleteModal(vm.zoneGroups[0]); expect(vm.safetyDeleteAction.storageSnapshotTargets).toHaveLength(1); expect(vm.safetyDeleteAction.storageSnapshotTargets[0].zoneid).toBe('z')
})

test('an in-flight response cannot restore rows after read permission is removed', async () => {
  const vm = await mount(); const old = {}; deferred = old; const pending = vm.fetchData(); await flushPromises(); deferred = null
  store.getters.apis = {}; await flushPromises(); old.resolve(response([row('revoked')])); await pending
  expect(vm.dataSource).toEqual([]); expect(vm.itemCount).toBe(0); expect(vm.fetchLoading).toBe(false)
})
test('permission rejection clears prior rows while a transient failure retains them', async () => {
  const vm = await mount(); vm.setSelection(['zone:z']); getAPI.mockRejectedValueOnce({ response: { status: 403 } }); await vm.fetchData()
  expect(vm.copyReadDenied).toBe(true); expect(vm.dataSource).toEqual([]); expect(vm.itemCount).toBe(0); expect(vm.selectedRowKeys).toEqual([])
})
