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
const snapshots = count => ({ listvmsnapshotresponse: { count } })
const response = rows => ({ listvolumesresponse: { volume: rows } })
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
function mount (apis = { listVolumes: {}, detachVolume: {}, destroyVolume: {} }) {
  return shallowMount(VmVolumesTab, {
    props: { resource: { ...vm } },
    global: { mocks: { $store: { getters: { apis: { listVMSnapshot: {}, ...apis }, project: {}, userInfo: { id: 'user', roletype: 'Admin' } }, state: { user: { token: 'token' } } }, $route: { path: '/vm/vm', fullPath: '/vm/vm?tab=volumes' }, $t: x => x, $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }) } }
  })
}
beforeEach(() => {
  jest.useFakeTimers(); jest.clearAllMocks(); clearVolumeOperations()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVMSnapshot' ? snapshots(0) : response([row])))
})
afterEach(() => jest.useRealTimers())
test('periodic refresh preserves rows and columns while pending and after failure', async () => {
  const wrapper = mount(); await flush()
  const columns = wrapper.vm.columns
  let rejectRequest
  getAPI.mockImplementation(api => api === 'listVMSnapshot' ? Promise.resolve(snapshots(0)) : new Promise((resolve, reject) => { rejectRequest = reject }))
  jest.advanceTimersByTime(10000); await flush()
  expect(wrapper.vm.loading).toBe(false); expect(wrapper.vm.rows).toHaveLength(1); expect(wrapper.vm.columns).toEqual(columns)
  rejectRequest(new Error('offline')); await flush()
  expect(wrapper.vm.rows).toHaveLength(1); expect(wrapper.vm.listRefreshFailed).toBe(true)
  wrapper.unmount()
})
test('same VM parent update does not reset data or refresh presentation', async () => {
  const wrapper = mount(); await flush()
  await wrapper.setProps({ resource: { ...vm, cpuused: '1%' } }); await flush()
  expect(wrapper.vm.rows).toHaveLength(1); expect(getAPI).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})
test('rejects stale response after VM switch', async () => {
  let resolveOld
  getAPI.mockImplementation(api => api === 'listVMSnapshot' ? Promise.resolve(snapshots(0)) : new Promise(resolve => { resolveOld = resolve }))
  const wrapper = mount()
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVMSnapshot' ? snapshots(0) : response([{ ...row, id: 'new-volume', virtualmachineid: 'new-vm' }])))
  await wrapper.setProps({ resource: { ...vm, id: 'new-vm' } }); await flush()
  resolveOld(response([row])); await flush()
  expect(wrapper.vm.rows[0].id).toBe('new-volume'); wrapper.unmount()
})
test('permission is rechecked before mutation and detach defaults to preserve', async () => {
  const wrapper = mount({ listVolumes: {} }); await flush()
  await wrapper.vm.openDetach(row); expect(wrapper.vm.mode).toBe('preserve')
  wrapper.vm.detach(); await flush(); expect(postAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
test('changed attachment prevents detach and subsequent deletion', async () => {
  const wrapper = mount(); await flush()
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVMSnapshot' ? snapshots(0) : api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : response([{ ...row, virtualmachineid: 'other' }])))
  await wrapper.vm.openDetach(row); wrapper.vm.mode = 'expunge'; wrapper.vm.detach(); await flush()
  expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.vm.operation.status).toBe('failed')
  wrapper.unmount()
})

test.each(['existing', 'create'])('passes chosen device ID only to attachVolume (%s)', async flow => {
  const wrapper = mount({ listVolumes: {}, createVolume: {}, attachVolume: {} }); await flush()
  const available = { ...row, virtualmachineid: undefined, zoneid: vm.zoneid, account: vm.account, domainid: vm.domainid }
  getAPI.mockImplementation((api, params) => Promise.resolve(api === 'listVMSnapshot' ? snapshots(0) : api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : response(params.id ? [available] : [{ ...row, deviceid: 1 }])))
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


test.each(['createVolume', 'attachVolume', 'detachVolume'])('snapshots block %s before any mutation', async api => {
  getAPI.mockImplementation(name => Promise.resolve(name === 'listVMSnapshot' ? snapshots(2) : name === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : response([row])))
  const wrapper = mount({ listVolumes: {}, createVolume: {}, attachVolume: {}, detachVolume: {} }); await flush()
  expect(wrapper.vm.reason(api, row)).toBe('message.vmvolume.snapshots.present')
  wrapper.vm.begin([api], row, { name: 'blocked' }); await flush()
  expect(postAPI).not.toHaveBeenCalled()
  expect(wrapper.vm.operation.status).toBe('failed')
  wrapper.unmount()
})

test('unknown snapshots fail closed for missing permission and failed refresh', async () => {
  const wrapper = mount(); await flush()
  delete wrapper.vm.$store.getters.apis.listVMSnapshot
  await wrapper.vm.refreshSnapshots()
  expect(wrapper.vm.snapshotReason).toBe('message.vmvolume.snapshots.unknown')
  wrapper.vm.$store.getters.apis.listVMSnapshot = {}
  getAPI.mockRejectedValue(new Error('offline'))
  await wrapper.vm.fetchData()
  expect(wrapper.vm.rows).toHaveLength(1)
  expect(wrapper.vm.snapshotReason).toBe('message.vmvolume.snapshots.unknown')
  wrapper.unmount()
})

test('pending snapshot request blocks actions and an old VM response cannot unlock new VM', async () => {
  let resolveOld
  getAPI.mockImplementation((api, params) => api === 'listVMSnapshot' && params.virtualmachineid === 'vm' ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve(api === 'listVMSnapshot' ? snapshots(2) : response([])))
  const wrapper = mount()
  expect(wrapper.vm.snapshotReason).toBe('message.vmvolume.snapshots.unknown')
  await wrapper.setProps({ resource: { ...vm, id: 'new-vm' } }); await flush()
  resolveOld(snapshots(0)); await flush()
  expect(wrapper.vm.snapshots).toBe(2)
  wrapper.unmount()
})

test('opening a dialog rechecks snapshots and refresh after last removal restores eligibility', async () => {
  const wrapper = mount({ listVolumes: {}, createVolume: {}, attachVolume: {} }); await flush()
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVMSnapshot' ? snapshots(1) : response([])))
  await wrapper.vm.openCreate()
  expect(wrapper.vm.form).toBe('')
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVMSnapshot' ? snapshots(0) : response([])))
  await wrapper.vm.fetchData()
  await wrapper.vm.openCreate()
  expect(wrapper.vm.form).toBe('create')
  wrapper.unmount()
})

test('snapshot created after volume creation blocks attach and retry reuses the created volume', async () => {
  let count = 0
  const available = { ...row, virtualmachineid: undefined, zoneid: vm.zoneid, account: vm.account, domainid: vm.domainid }
  getAPI.mockImplementation(api => Promise.resolve(api === 'listVMSnapshot' ? snapshots(count) : api === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [vm] } } : response([available])))
  const wrapper = mount({ listVolumes: {}, createVolume: {}, attachVolume: {} }); await flush()
  postAPI.mockImplementation(api => Promise.resolve({ [api.toLowerCase() + 'response']: { jobid: api } }))
  wrapper.vm.$pollJob.mockImplementation(() => { count = 1; return Promise.resolve({ jobstatus: 1, jobresult: { volume: available } }) })
  wrapper.vm.createAndAttach({ name: 'new' }); await flush(); await flush()
  expect(postAPI.mock.calls.map(call => call[0])).toEqual(['createVolume'])
  expect(wrapper.vm.operation.volume.id).toBe(available.id)
  expect(wrapper.vm.operation.status).toBe('failed')
  wrapper.vm.operation.resume(); await flush()
  expect(postAPI).toHaveBeenCalledTimes(1)
  count = 0
  wrapper.vm.operation.resume(); await flush(); await flush()
  expect(postAPI.mock.calls.map(call => call[0])).toEqual(['createVolume', 'attachVolume'])
  wrapper.unmount()
})
