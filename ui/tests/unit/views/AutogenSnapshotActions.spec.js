// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import AutogenView from '@/views/AutogenView'
import { getAPI, postAPI } from '@/api'
import { clearSnapshotJobs, snapshotBusy } from '@/utils/vmSnapshotActions'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
const action = { api: 'createVMSnapshot', resource: Object.freeze({ id: 'vm-b' }), show: vm => vm.state === 'Running', disabled: vm => !!vm.vmsnapshotblockedreason }
let context
beforeEach(() => {
  jest.clearAllMocks(); clearSnapshotJobs()
  context = { $store: { getters: { apis: { createVMSnapshot: {} }, userInfo: { id: 'user' }, project: {} }, state: { user: { token: 'token' } } }, $t: key => key }
  getAPI.mockImplementation((api) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-b', state: 'Running' }] } } : { listvmsnapshotresponse: { vmSnapshot: [], count: 0 } }))
  postAPI.mockResolvedValue({ createvmsnapshotresponse: { jobid: 'job' } })
})
test('create submits the frozen VM UUID after preflight even if mutable form mapping changed', async () => {
  await AutogenView.methods.postSnapshotAwareAction.call(context, action, { virtualmachineid: 'vm-a', name: 'snapshot' })
  expect(postAPI).toHaveBeenCalledWith('createVMSnapshot', { virtualmachineid: 'vm-b', name: 'snapshot' })
})
test('fresh VM eligibility changed before create blocks POST', async () => {
  getAPI.mockImplementation((api) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-b', state: 'Starting' }] } } : { listvmsnapshotresponse: { vmSnapshot: [], count: 0 } }))
  await expect(AutogenView.methods.postSnapshotAwareAction.call(context, action, {})).rejects.toThrow('message.vmsnapshot.vm.state')
  expect(postAPI).not.toHaveBeenCalled()
})
test('project changes during create preflight prevent POST', async () => {
  const original = getAPI.getMockImplementation()
  getAPI.mockImplementation((api, args) => { if (api === 'listVirtualMachines') context.$store.getters.project.id = 'other'; return original(api, args) })
  await expect(AutogenView.methods.postSnapshotAwareAction.call(context, action, {})).rejects.toThrow('message.vmsnapshot.permission')
  expect(postAPI).not.toHaveBeenCalled()
})

test('explicit snapshot bulk delete stays in the toolbar while existing unnamed group actions remain hidden', () => {
  const create = { api: 'createVMSnapshot', listView: true }
  const bulk = { api: 'deleteVMSnapshot', groupAction: true, toolbarLabel: 'label.vmsnapshot.selected.delete' }
  const existingGroup = { api: 'stopVirtualMachine', groupAction: true }
  const view = { actions: [create, bulk, existingGroup], dataView: false, selectedRowKeys: ['one', 'two'], selectedItems: [], resource: {}, $store: { getters: { apis: { createVMSnapshot: {}, deleteVMSnapshot: {}, stopVirtualMachine: {} } } } }
  expect(AutogenView.computed.visibleListActions.call(view)).toEqual([create, bulk])
  view.selectedRowKeys = []
  expect(AutogenView.computed.visibleListActions.call(view)).toEqual([create])
})

test.each([['vmsnapshot', {}, 'message.vmsnapshot.list.empty'], ['vmsnapshot', { current: 'false' }, 'message.vmsnapshot.list.no.results'], ['vmsnapshot', { keyword: 'missing' }, 'message.vmsnapshot.list.no.results'], ['vm', {}, '']])('empty state distinguishes %s %j', (name, query, expected) => {
  expect(AutogenView.computed.snapshotEmptyText.call({ $route: { name, query }, $t: key => key })).toBe(expected)
})

test('lost create response blocks repeat submissions without claiming job failure', async () => {
  postAPI.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true }))
  await expect(AutogenView.methods.postSnapshotAwareAction.call(context, action, {})).rejects.toMatchObject({ trackingStatus: 'unknown' })
  expect(snapshotBusy('vm-b')).toBe(true)
  await expect(AutogenView.methods.postSnapshotAwareAction.call(context, action, {})).rejects.toThrow('message.vmsnapshot.busy')
  expect(postAPI).toHaveBeenCalledTimes(1)
})
