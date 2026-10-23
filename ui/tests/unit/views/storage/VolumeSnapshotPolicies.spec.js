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

const deleter = () => ({ ...Table.methods, resource: { id: 'first' }, dataSource: [{ id: 'policy', volumeid: 'first' }], canDelete: true, ownerScope: 'first/user/project', disabled: false, actionLoading: false, disposed: false, deleteVersion: 0, $t: key => key, $emit: jest.fn(), $notifyError: jest.fn(), $notification: { success: jest.fn() } })

test('policy deletion is permission/row/context gated and double submission posts only once', async () => {
  const vm = deleter(); let release
  postAPI.mockReturnValue(new Promise(resolve => { release = resolve }))
  vm.canDelete = false; await vm.handleClickDelete({ id: 'policy' })
  vm.canDelete = true; await vm.handleClickDelete({ id: 'not-owned' })
  expect(postAPI).not.toHaveBeenCalled()
  const pending = vm.handleClickDelete({ id: 'policy' }); await vm.handleClickDelete({ id: 'policy' })
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('deleteSnapshotPolicies', { id: 'policy' }, { preserveOnFailure: true })
  vm.ownerScope = 'second/user/project'; release({ deletesnapshotpoliciesresponse: { success: true } }); await pending
  expect(vm.$emit).not.toHaveBeenCalled()
})

test('successful policy removal refreshes the detail table and interval labels include the cadence', async () => {
  const vm = deleter(); postAPI.mockResolvedValue({ deletesnapshotpoliciesresponse: { success: true } })
  await vm.handleClickDelete({ id: 'policy' }); expect(vm.$emit).toHaveBeenCalledWith('refresh')
  expect(vm.scheduleTime({ intervaltype: 3, schedule: '5:9:28' })).toBe('09:05')
  expect(vm.intervalText('3')).toBe('label.monthly')
  expect(Table.computed.columns.call({ $t: k => k, canDelete: false }).some(c => c.key === 'actions')).toBe(false)
})

test('policy validation cannot post after context change and two validations create only one policy', async () => {
  let release; const validated = new Promise(resolve => { release = resolve }); let current = true
  const vm = { actionLoading: false, disabled: false, dialogCurrent: () => current, formRef: { value: { validate: () => validated } }, form: { intervaltype: 'hourly', time: 59, timezone: 'UTC', maxsnaps: 2 }, handleRemoveFields: v => ({ ...v }), volumeId: 'first', tags: [], $emit: jest.fn(), $t: k => k, $notification: { success: jest.fn() }, resetForm: jest.fn(), $notifyError: jest.fn() }
  Schedule.methods.handleSubmit.call(vm); current = false; release(); await flushPromises()
  expect(postAPI).not.toHaveBeenCalled()
  current = true; vm.formRef.value.validate = () => Promise.resolve(); postAPI.mockReturnValue(new Promise(() => {}))
  Schedule.methods.handleSubmit.call(vm); Schedule.methods.handleSubmit.call(vm); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toMatchObject({ volumeid: 'first', schedule: 59 })
})
