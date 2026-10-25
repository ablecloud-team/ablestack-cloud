// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import '../../utils/cryptoRandom'
import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Events from '@/components/view/EventsTab.vue'
import Notes from '@/components/view/AnnotationsTab.vue'
import Zones from '@/views/storage/SnapshotZones.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: {} }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
let wrapper, store, route, pending
const row = (id = 'note', entityid = 'A') => ({ id, entityid, entitytype: 'SNAPSHOT', annotation: id, userid: 'user' })
async function mount (component = Notes, renderEditor = false) {
  store = reactive({ getters: { apis: { listEvents: {}, listAnnotations: {}, addAnnotation: {}, removeAnnotation: {}, updateAnnotationVisibility: {}, listSnapshots: {}, listZones: {}, copySnapshot: {}, deleteSnapshot: {} }, userInfo: { id: 'user', roleid: 'role', roletype: 'Admin' }, project: {} }, state: { user: { token: 'session' } } })
  route = reactive({ path: '/snapshot/A', fullPath: '/snapshot/A?tab=comments', query: { tab: 'comments' }, meta: { name: 'snapshot', resourceType: 'Snapshot' } })
  wrapper = shallowMount(component, { props: { resource: { id: 'A', state: 'BackedUp', intervaltype: 'MANUAL' }, resourceType: 'Snapshot', loading: false }, global: { stubs: renderEditor ? { ASpin: { template: '<div><slot /></div>' }, AComment: { template: '<div><slot name="content" /></div>' }, ATextarea: { props: ['value'], template: '<textarea :value="value" />' } } : {}, provide: { parentFetchData: jest.fn() }, mocks: { $store: store, $route: route, $t: x => x, $toLocaleDate: x => x, $localStorage: { get: () => null, set: jest.fn() }, $notifyError: jest.fn() } } })
  await flushPromises(); return wrapper.vm
}
beforeEach(() => {
  jest.clearAllMocks(); pending = null
  getAPI.mockImplementation((command, args) => {
    if (pending) return new Promise(resolve => { pending.resolve = resolve })
    if (command === 'listAnnotations') return Promise.resolve({ listannotationsresponse: { count: 1, annotation: [row('note', args.entityid)] } })
    if (command === 'listEvents') return Promise.resolve({ listeventsresponse: { count: 1, event: [{ id: 'event', resourceid: args.resourceid }] } })
    if (command === 'listZones') return Promise.resolve({ listzonesresponse: {} })
    return Promise.resolve({ listsnapshotsresponse: { count: 1, snapshot: [{ id: args.id, zoneid: 'zone', zonename: 'Zone', datastoreid: 'store', datastorename: 'Secondary', state: 'BackedUp', intervaltype: 'MANUAL', copy: { id: 'copy', role: 'Image', availability: 'Ready', state: 'Ready', virtualsize: 0 } }] } })
  })
  postAPI.mockResolvedValue({})
})
afterEach(() => wrapper?.unmount())
test.each([Notes, Events])('$name retains rows on transient refresh failure and removes them on access denial', async component => {
  const vm = await mount(component); const method = component === Notes ? 'getAnnotations' : 'fetchEvents'; const key = component === Notes ? 'notes' : 'events'
  getAPI.mockRejectedValueOnce({ response: { status: 503 } }); await vm[method](); expect(vm[key]).toHaveLength(1); expect(vm.readDenied).toBe(false); expect(vm.readError).toBeTruthy()
  getAPI.mockRejectedValueOnce({ response: { status: 403 } }); await vm[method](); expect(vm[key]).toEqual([]); expect(vm.loadedContext).toBe(''); expect(vm.readDenied).toBe(true)
})
test.each([Notes, Events])('$name ignores an old response after a role permission change', async component => {
  const vm = await mount(component); const method = component === Notes ? 'getAnnotations' : 'fetchEvents'; const key = component === Notes ? 'notes' : 'events'; const old = {}; pending = old; const request = vm[method](); await flushPromises(); pending = null
  delete store.getters.apis[component === Notes ? 'listAnnotations' : 'listEvents']; await flushPromises()
  old.resolve(component === Notes ? { listannotationsresponse: { count: 1, annotation: [row('old')] } } : { listeventsresponse: { count: 1, event: [{ id: 'old' }] } }); await request
  expect(vm[key]).toEqual([]); expect(vm.loadedContext).toBe('')
})
test('events server search resets the page and sends the same fixed resource and project', async () => {
  const vm = await mount(Events); await wrapper.setProps({ resource: { id: 'A', projectid: 'project' } }); vm.page = 3; vm.eventSearch = 'SNAPSHOT.EXTRACT'; await vm.searchEvents()
  expect(vm.page).toBe(1); expect(getAPI).toHaveBeenLastCalledWith('listEvents', expect.objectContaining({ resourceid: 'A', projectid: 'project', keyword: 'SNAPSHOT.EXTRACT', page: 1 }), expect.objectContaining({ timeout: 20000 }))
})
test('comment delete/visibility completion from the old target cannot refresh or clear the new target', async () => {
  const vm = await mount(); let release; postAPI.mockReturnValue(new Promise(resolve => { release = resolve })); const operation = vm.deleteNote(vm.notes[0]); await wrapper.setProps({ resource: { id: 'B' } }); await flushPromises(); vm.annotation = 'new target draft'; getAPI.mockClear(); release({}); await operation
  expect(vm.annotation).toBe('new target draft'); expect(vm.notes[0].entityid).toBe('B'); expect(getAPI).not.toHaveBeenCalled()
})
test('a write failure retains comment draft and a duplicate write is blocked', async () => {
  const vm = await mount(); vm.annotation = 'draft'; let fail; postAPI.mockReturnValue(new Promise((resolve, reject) => { fail = reject })); const request = vm.saveNote(); await vm.saveNote(); expect(postAPI).toHaveBeenCalledTimes(1); fail(new Error('503')); await request
  expect(vm.annotation).toBe('draft'); expect(vm.mutationError).toBeTruthy(); expect(vm.annotationSaving).toBe(false)
})
test('comment mutation cannot target a removed or unrelated visible row', async () => {
  const vm = await mount(); await vm.deleteNote(row('foreign')); await vm.updateVisibility(row('foreign')); expect(postAPI).not.toHaveBeenCalled()
})
test('Zone page search keeps all copies of a matching Zone and never changes server count', async () => {
  const vm = await mount(Zones); expect(vm.copySize(vm.dataSource[0])).toBe('0 B'); vm.keyword = 'Secondary'; await flushPromises(); expect(vm.visibleZoneGroups).toHaveLength(1); expect(vm.itemCount).toBe(1); vm.setSelection(['zone:zone']); vm.keyword = 'missing'; await flushPromises(); expect(vm.visibleZoneGroups).toEqual([]); expect(vm.selectedRowKeys).toEqual([]); expect(vm.itemCount).toBe(1)
})
test('Zone updates block writes while the same-page read is pending', async () => {
  const vm = await mount(Zones); const old = {}; pending = old; const request = vm.fetchData(); await flushPromises(); expect(vm.actionsBlocked).toBe(true); vm.showCopySnapshot(vm.zoneGroups[0]); expect(vm.copyRecord).toBeNull(); pending = null; old.resolve({ listsnapshotsresponse: { count: 0 } }); await request
})
test('Zone column controls retain one visible data column', async () => {
  const vm = await mount(Zones); for (const k of vm.columnKeys) vm.toggleColumn(k); expect(vm.visibleColumns).toHaveLength(1); expect(vm.columns.length).toBeGreaterThan(0)
})

test('Zone bulk and single-row entry share the stable snapshot state guard', async () => {
  const vm = await mount(Zones); vm.setSelection(['zone:zone']); await wrapper.setProps({ resource: { id: 'A', state: 'Creating' } }); expect(vm.actionsBlocked).toBe(true); vm.bulkActionConfirmation(); vm.showCopySnapshot(vm.zoneGroups[0]); expect(vm.safetyDeleteAction).toBeNull(); expect(vm.copyRecord).toBeNull()
})

test('a new Zone scope clears an old failure notice before the new read finishes', async () => {
  const vm = await mount(Zones); vm.listRefreshFailed = true; vm.copyReadError = 'old failure'; vm.fetchData = jest.fn(); vm.resetCopyRead(); expect(vm.listRefreshFailed).toBe(false); expect(vm.copyReadError).toBe(''); expect(vm.fetchData).toHaveBeenCalledTimes(1)
})

test('shared events table keeps readable column widths instead of squeezing status into one-character lines', async () => {
  const vm = await mount(Events); expect(vm.columns.every(column => column.width >= 70)).toBe(true); const ListView = require('@/components/view/ListView.vue').default; expect(ListView.props.tableScroll.default).toBeNull()
})

test('Ctrl+Enter submits from the textarea while keeping the toolbar outside the editor', async () => {
  const vm = await mount(Notes, true); vm.annotation = 'keyboard draft'; await wrapper.vm.$nextTick(); await wrapper.find('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises()
  expect(postAPI).toHaveBeenCalledWith('addAnnotation', expect.objectContaining({ entityid: 'A', annotation: 'keyboard draft' }), expect.any(Object)); expect(vm.annotation).toBe('')
})
