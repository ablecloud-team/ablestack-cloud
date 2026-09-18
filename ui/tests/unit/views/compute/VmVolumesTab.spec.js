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
import VmVolumesTab from '@/views/compute/VmVolumesTab.vue'
import { getAPI, postAPI } from '@/api'
import { clearVolumeOperations } from '@/utils/vmVolumeActions'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/views/storage/CreateVolume.vue', () => ({ render: () => null }))
jest.mock('@/utils/listRefresh', () => ({ ...jest.requireActual('@/utils/listRefresh'), canRefreshList: () => true }))
const vm = { id: 'vm', name: 'VM', state: 'Running', zoneid: 'zone', account: 'admin', domainid: 'domain' }
const row = { id: 'volume', name: 'data', type: 'DATADISK', state: 'Ready', virtualmachineid: 'vm', size: 1073741824 }
const response = rows => ({ listvolumesresponse: { volume: rows } })
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
function mount (apis = { listVolumes: {}, detachVolume: {}, destroyVolume: {} }) {
  return shallowMount(VmVolumesTab, {
    props: { resource: { ...vm } },
    global: { mocks: { $store: { getters: { apis, project: {}, userInfo: { id: 'user', roletype: 'Admin' } }, state: { user: { token: 'token' } } }, $route: { path: '/vm/vm', fullPath: '/vm/vm?tab=volumes' }, $t: x => x, $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }) } }
  })
}
beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks(); clearVolumeOperations()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  getAPI.mockResolvedValue(response([row]))
})
afterEach(() => jest.useRealTimers())
test('periodic refresh preserves rows and columns while pending and after failure', async () => {
  const wrapper = mount(); await flush()
  const columns = wrapper.vm.columns
  let rejectRequest
  getAPI.mockReturnValue(new Promise((resolve, reject) => { rejectRequest = reject }))
  jest.advanceTimersByTime(10000); await flush()
  expect(wrapper.vm.loading).toBe(false); expect(wrapper.vm.rows).toHaveLength(1); expect(wrapper.vm.columns).toEqual(columns)
  rejectRequest(new Error('offline')); await flush()
  expect(wrapper.vm.rows).toHaveLength(1); expect(wrapper.vm.listRefreshFailed).toBe(true)
  wrapper.unmount()
})
test('same VM parent update does not reset data or refresh presentation', async () => {
  const wrapper = mount(); await flush()
  await wrapper.setProps({ resource: { ...vm, cpuused: '1%' } }); await flush()
  expect(wrapper.vm.rows).toHaveLength(1); expect(getAPI).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})
test('rejects stale response after VM switch', async () => {
  let resolveOld
  getAPI.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
  const wrapper = mount()
  getAPI.mockResolvedValue(response([{ ...row, id: 'new-volume', virtualmachineid: 'new-vm' }]))
  await wrapper.setProps({ resource: { ...vm, id: 'new-vm' } }); await flush()
  resolveOld(response([row])); await flush()
  expect(wrapper.vm.rows[0].id).toBe('new-volume'); wrapper.unmount()
})
test('permission is rechecked before mutation and detach defaults to preserve', async () => {
  const wrapper = mount({ listVolumes: {} }); await flush()
  wrapper.vm.openDetach(row); expect(wrapper.vm.mode).toBe('preserve')
  wrapper.vm.detach(); await flush(); expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
test('changed attachment prevents detach and subsequent deletion', async () => {
  const wrapper = mount(); await flush()
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : response([{ ...row, virtualmachineid: 'other' }])))
  wrapper.vm.openDetach(row); wrapper.vm.mode = 'expunge'; wrapper.vm.detach(); await flush()
  expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.vm.operation.status).toBe('failed')
  wrapper.unmount()
})

test.each(['existing', 'create'])('passes chosen device ID only to attachVolume (%s)', async flow => {
  const wrapper = mount({ listVolumes: {}, createVolume: {}, attachVolume: {} }); await flush()
  const available = { ...row, virtualmachineid: undefined, zoneid: vm.zoneid, account: vm.account, domainid: vm.domainid }
  getAPI.mockImplementation((api, params) => Promise.resolve(api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : response(params.id ? [available] : [{ ...row, deviceid: 1 }])))
  postAPI.mockImplementation(api => Promise.resolve({ [api.toLowerCase() + 'response']: { jobid: api } }))
  wrapper.vm.$pollJob.mockResolvedValue({ jobstatus: 1, jobresult: { volume: available } })
  if (flow === 'existing') {
    wrapper.vm.candidates = [available]; wrapper.vm.attachId = available.id; wrapper.vm.attachDeviceId = 6
    wrapper.vm.attachExisting()
  } else wrapper.vm.createAndAttach({ name: 'new', deviceid: 6 })
  await flush()
  expect(postAPI).toHaveBeenCalledWith('attachVolume', { id: available.id, virtualmachineid: vm.id, deviceid: 6 })
  if (flow === 'create') expect(postAPI.mock.calls.find(call => call[0] === 'createVolume')[1]).not.toHaveProperty('deviceid')
  wrapper.unmount()
})
