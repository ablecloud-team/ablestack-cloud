// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import { shallowMount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import Policies from '@/views/storage/VolumeSnapshotPolicies'
import Recurring from '@/views/storage/RecurringSnapshotVolume'
import Schedule from '@/views/storage/FormSchedule'
import Table from '@/views/storage/ScheduledSnapshots'
import storage from '@/config/section/storage'
import store from '@/store'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: {} } }))
beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

test('policy editing and scheduling use their target dialog instead of the generic create API route', () => {
  const actions = storage.children.find(child => child.name === 'snapshotpolicy').actions.filter(action => action.policyMode)
  expect(actions.map(action => action.policyMode)).toEqual(['edit', 'pause', 'resume'])
  for (const action of actions) { expect(action.popup).toBe(true); expect(action.selfManagedDialog).toBe(true); expect(action.dataView).toBe(true) }
})

const reader = () => ({ ...Policies.methods, resource: { id: 'first' }, active: true, canRead: true, loading: false, pending: false, disposed: false, ownerScope: 'user/project/first', version: 0, rows: [{ id: 'kept' }], loaded: true, error: false })

test('policies are an independently permission-gated volume detail tab', () => {
  const tab = storage.children.find(child => child.name === 'volume').tabs.find(tab => tab.name === 'recurringsnapshots')
  store.getters.apis = {}; expect(tab.show()).toBe(false)
  store.getters.apis = { listSnapshotPolicies: {} }; expect(tab.show()).toBe(true)
})

test('the detail reads all policies for this volume and replaces a successful empty result', async () => {
  const vm = reader()
  getAPI.mockResolvedValueOnce({ listsnapshotpoliciesresponse: { count: 2, snapshotpolicy: [{ id: 'one' }] } })
    .mockResolvedValueOnce({ listsnapshotpoliciesresponse: { count: 2, snapshotpolicy: [{ id: 'two' }] } })
  await vm.fetchData()
  expect(vm.rows.map(row => row.id)).toEqual(['one', 'two'])
  expect(getAPI.mock.calls[1]).toEqual(['listSnapshotPolicies', { volumeid: 'first', listall: true, page: 2, pagesize: 100 }, { preserveOnFailure: true }])
  getAPI.mockResolvedValueOnce({ listsnapshotpoliciesresponse: { count: 0 } }); await vm.fetchData()
  expect(vm.rows).toEqual([]); expect(vm.error).toBe(false)
})

test('a failed refresh retains policies; retry replaces them and authentication rejection clears them', async () => {
  const vm = reader()
  getAPI.mockRejectedValueOnce(new Error('Network Error')); await vm.fetchData()
  expect(vm.rows).toEqual([{ id: 'kept' }]); expect(vm.error).toBe(true); expect(vm.pending).toBe(false)
  getAPI.mockResolvedValueOnce({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [{ id: 'recovered' }] } }); await vm.fetchData()
  expect(vm.rows[0].id).toBe('recovered'); expect(vm.error).toBe(false)
  getAPI.mockRejectedValueOnce({ response: { status: 403 } }); await vm.fetchData()
  expect(vm.rows).toEqual([]); expect(vm.loaded).toBe(false)
})

test('a background parent refresh after policy creation refreshes the active tab even when loading stays false', async () => {
  const parentPending = ref(false)
  getAPI.mockResolvedValueOnce({ listsnapshotpoliciesresponse: { snapshotpolicy: [{ id: 'existing' }] } })
    .mockResolvedValueOnce({ listsnapshotpoliciesresponse: { snapshotpolicy: [{ id: 'existing' }, { id: 'created' }] } })
  const wrapper = shallowMount(Policies, { props: { resource: { id: 'first' }, tab: 'recurringsnapshots' }, global: { provide: { resourceReadPending: () => parentPending.value }, mocks: { $t: k => k, $route: { path: '/volume/first', fullPath: '/volume/first?tab=recurringsnapshots' }, $store: { getters: { apis: { listSnapshotPolicies: {} }, userInfo: {} }, state: { user: {} } } } } })
  await flushPromises()
  expect(wrapper.vm.rows.map(row => row.id)).toEqual(['existing'])
  parentPending.value = true; await flushPromises()
  expect(getAPI).toHaveBeenCalledTimes(1)
  parentPending.value = false; await flushPromises()
  expect(wrapper.vm.rows.map(row => row.id)).toEqual(['existing', 'created'])
  expect(getAPI).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})

test('late replies cannot refill a different volume or project', async () => {
  const vm = reader(); let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
  const pending = vm.fetchData(); vm.ownerScope = 'other/project/second'; vm.reset()
  release({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [{ id: 'old-private' }] } }); await pending
  expect(vm.rows).toEqual([]); expect(vm.pending).toBe(false)
})

test('a hidden tab, loading parent or revoked read permission makes no request', async () => {
  const vm = reader(); vm.active = false; await vm.fetchData()
  vm.active = true; vm.loading = true; await vm.fetchData()
  vm.loading = false; vm.canRead = false; await vm.fetchData()
  expect(getAPI).not.toHaveBeenCalled(); expect(vm.rows).toEqual([])
})

test('the recurring dialog retains creation state without a policy-list tab or table', async () => {
  getAPI.mockResolvedValue({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [{ id: 'existing', intervaltype: 3 }] } })
  const wrapper = shallowMount(Recurring, { props: { resource: { id: 'first', type: 'DATADISK' } }, global: { provide: { parentFetchData: jest.fn() }, mocks: { $t: k => k, $store: { getters: { userInfo: {}, features: {} }, state: { user: {} } } } } })
  await flushPromises()
  expect(wrapper.findComponent(Schedule).props('dataSource')).toHaveLength(1)
  expect(wrapper.findComponent(Table).exists()).toBe(false)
  expect(wrapper.find('a-tabs-stub').exists()).toBe(false)
  wrapper.unmount()
})

test('a volume delete opens a pinned confirmation without sending a request', () => {
  const row = { id: 'policy', volumeid: 'first', intervaltype: 0 }
  const vm = { ...Table.methods, resource: { id: 'first' }, dataSource: [row], canDelete: true, disposed: false, policyTarget: null }
  vm.handleClickDelete(row); row.id = 'changed'; expect(vm.policyTarget.id).toBe('policy'); expect(vm.policyMode).toBe('delete'); expect(postAPI).not.toHaveBeenCalled()
  vm.handleClickDelete({ id: 'other', volumeid: 'first' }); expect(vm.policyTarget.id).toBe('policy')
  Table.watch.ownerScope.call(vm); expect(vm.policyTarget).toBeNull()
})

test('policy row actions require both permission and exact volume ownership', () => {
  const vm = { ...Table.methods, resource: { id: 'first' }, dataSource: [{ id: 'policy', volumeid: 'first' }], canDelete: false, disposed: false, policyTarget: null }
  vm.handleClickDelete(vm.dataSource[0]); expect(vm.policyTarget).toBeNull(); vm.canDelete = true; vm.handleClickDelete({ id: 'other', volumeid: 'second' }); expect(vm.policyTarget).toBeNull()
  expect(vm.scheduleTime({ intervaltype: 3, schedule: '5:9:28' })).toBe('09:05')
  expect(Table.computed.columns.call({ $t: k => k, $store: { getters: { userInfo: { roletype: 'User' } } }, canDelete: false, canEdit: false, canManage: false }).some(c => c.key === 'actions')).toBe(false)
})

test('after a deleted row disappears, focus waits for refresh and returns to the volume toolbar', () => {
  const section = document.createElement('section'); section.className = 'volume-policy-tab'; section.innerHTML = '<div class="detail-tab-toolbar"><button>refresh</button></div><div class="list-schedule"><button>delete</button></div>'; document.body.append(section)
  const row = { id: 'policy', volumeid: 'first' }; const list = section.querySelector('.list-schedule'); const trigger = list.querySelector('button'); trigger.focus()
  const vm = { ...Table.methods, $el: list, $nextTick (fn) { fn.call(this) }, resource: { id: 'first' }, dataSource: [row], ownerScope: 'owner', canDelete: true, disposed: false, policyTarget: null }
  vm.handleClickDelete(row); trigger.remove(); vm.disabled = true; vm.closePolicyDelete(); expect(vm.pendingDeleteFocus).toBe(true)
  vm.disabled = false; Table.watch.disabled.call(vm, false); expect(document.activeElement).toBe(section.querySelector('button')); expect(vm.pendingDeleteFocus).toBe(false); section.remove()
})

test('a pending deletion focus restore cannot steal focus after an owner switch', () => {
  const button = document.createElement('button'); document.body.append(button); button.focus()
  const vm = { ...Table.methods, ownerScope: 'other', deleteFocusScope: 'previous', pendingDeleteFocus: true, deleteTrigger: button, $nextTick: fn => fn() }
  Table.watch.ownerScope.call(vm); vm.restorePolicyFocus(); expect(document.activeElement).toBe(button); expect(vm.pendingDeleteFocus).toBe(false); button.remove()
})

test('policy validation cannot post after context change and two validations create only one policy', async () => {
  let release; const validated = new Promise(resolve => { release = resolve }); let current = true
  const vm = { actionLoading: false, disabled: false, dialogCurrent: () => current, formRef: { value: { validate: () => validated } }, form: { intervaltype: 'hourly', time: 59, timezone: 'UTC', maxsnaps: 2 }, handleRemoveFields: v => ({ ...v }), volumeId: 'first', tags: [], $emit: jest.fn(), $t: k => k, $notification: { success: jest.fn() }, resetForm: jest.fn(), $notifyError: jest.fn() }
  vm.$store = { getters: { apis: { createSnapshotPolicy: {} } } }
  Schedule.methods.handleSubmit.call(vm); current = false; release(); await flushPromises()
  expect(postAPI).not.toHaveBeenCalled()
  current = true; vm.formRef.value.validate = () => Promise.resolve(); postAPI.mockReturnValue(new Promise(() => {}))
  Schedule.methods.handleSubmit.call(vm); Schedule.methods.handleSubmit.call(vm); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toMatchObject({ volumeid: 'first', schedule: 59 })
})
