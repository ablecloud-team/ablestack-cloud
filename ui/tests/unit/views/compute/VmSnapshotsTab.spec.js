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

import { shallowMount } from '@vue/test-utils'
import VmSnapshotsTab from '@/views/compute/VmSnapshotsTab.vue'
import { getAPI, postAPI } from '@/api'
import { clearSnapshotJobs } from '@/utils/vmSnapshotActions'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/config/section/compute', () => ({ children: [{ name: 'vm', actions: [{ api: 'createVMSnapshot', show: () => true, disabled: () => false }] }] }))
jest.mock('@/utils/listRefresh', () => ({ ...jest.requireActual('@/utils/listRefresh'), canRefreshList: () => true }))
const row = { id: 's1', virtualmachineid: 'v1', state: 'Ready', type: 'Disk', hypervisor: 'KVM' }
const response = rows => ({ listvmsnapshotresponse: { vmSnapshot: rows, count: rows.length } })
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
function mount (apis = { listVMSnapshot: {}, revertToVMSnapshot: {}, deleteVMSnapshot: {} }) {
  return shallowMount(VmSnapshotsTab, {
    props: { resource: { id: 'v1', name: 'VM', state: 'Stopped' } },
    global: { mocks: { $store: { getters: { apis, project: {}, userInfo: { id: 'u' } }, state: { user: { token: 'token' } } }, $route: { path: '/vm/v1', fullPath: '/vm/v1' }, $t: x => x, $toLocaleDate: x => x, $notifyError: jest.fn(), $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }) } }
  })
}
beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks(); clearSnapshotJobs()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  getAPI.mockResolvedValue(response([row]))
})
afterEach(() => jest.useRealTimers())
test('automatically replaces only list data after 10 seconds without a loading overlay', async () => {
  const wrapper = mount(); await flush()
  expect(wrapper.find('h3').exists()).toBe(false)
  expect(wrapper.vm.rows[0].state).toBe('Ready')
  let resolveRequest
  getAPI.mockReturnValue(new Promise(resolve => { resolveRequest = resolve }))
  jest.advanceTimersByTime(10000); await flush()
  expect(getAPI).toHaveBeenCalledTimes(2)
  expect(wrapper.vm.loading).toBe(false)
  resolveRequest(response([{ ...row, state: 'Error' }]))
  await flush()
  expect(wrapper.vm.rows[0].state).toBe('Error')
  wrapper.unmount()
})
test('ignores an old response after switching VM', async () => {
  let resolveRequest
  getAPI.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve }))
  const wrapper = mount()
  getAPI.mockResolvedValue(response([{ ...row, id: 's2', virtualmachineid: 'v2' }]))
  await wrapper.setProps({ resource: { id: 'v2', state: 'Stopped' } }); await flush()
  resolveRequest(response([row])); await flush()
  expect(wrapper.vm.rows[0].id).toBe('s2')
  wrapper.unmount()
})
test('read-only users cannot open or submit mutations', async () => {
  const wrapper = mount({ listVMSnapshot: {} }); await flush()
  wrapper.vm.openAction('deleteVMSnapshot', row)
  await wrapper.vm.submitAction()
  expect(wrapper.vm.selected).toBeNull()
  expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
test('revalidates VM state before submitting and refuses changed eligibility', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.openAction('revertToVMSnapshot', row)
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'v1', state: 'Running' }] } } : response([row])))
  await wrapper.vm.submitAction()
  expect(postAPI).not.toHaveBeenCalled()
  expect(wrapper.vm.$notifyError).toHaveBeenCalled()
  wrapper.unmount()
})
test('submits the snapshot ID once and tracks the job', async () => {
  const wrapper = mount(); await flush()
  wrapper.vm.openAction('deleteVMSnapshot', row)
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'v1', state: 'Stopped' }] } } : response([row])))
  postAPI.mockResolvedValue({ deletevmsnapshotresponse: { jobid: 'job' } })
  const first = wrapper.vm.submitAction(); const second = wrapper.vm.submitAction()
  await Promise.all([first, second])
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('deleteVMSnapshot', { vmsnapshotid: 's1' })
  expect(wrapper.vm.$pollJob).toHaveBeenCalledWith(expect.objectContaining({ jobId: 'job' }))
  expect(wrapper.vm.selected).toBeNull()
  wrapper.unmount()
})
