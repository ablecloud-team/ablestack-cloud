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
import VmDevicesTab from '@/views/compute/VmDevicesTab.vue'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const resource = { id: 'vm1', state: 'Running', hypervisor: 'KVM', hostid: 'host1' }
const row = { hostuuid: 'host1', hostid: 3, devicetype: 'usb', hostdevicesname: '002:004', hostdevicestext: 'USB Serial Adapter' }
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
function mount () {
  return shallowMount(VmDevicesTab, { props: { resource }, global: { mocks: { $t: x => x, $store: { getters: { apis: { updateHostUsbDevices: {}, updateHostDevices: {} } } }, $message: { success: jest.fn() } } } })
}
beforeEach(() => {
  jest.clearAllMocks()
  getAPI.mockImplementation(name => Promise.resolve(name === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [resource] } } : name === 'listVMSnapshot' ? { listvmsnapshotresponse: { count: 0 } } : { listvmdeviceassignmentsresponse: { vmdeviceassignment: [row] } }))
})
test('read-only refresh preserves records and blocks mutations when snapshot lookup fails', async () => {
  const w = mount(); await w.vm.refresh()
  expect(w.vm.blockReason).toBe('')
  getAPI.mockRejectedValue(new Error('offline'))
  await w.vm.refresh()
  expect(w.vm.rows).toEqual([row])
  expect(w.vm.blockReason).toBe('label.vmdevice.verifyFirst')
  expect(postAPI).not.toHaveBeenCalled()
  w.unmount()
})
test('snapshot existence and PCI running state are independent guards', async () => {
  const w = mount(); await w.vm.refresh()
  expect(w.vm.reason('pci')).toBe('label.vmdevice.stopPci')
  w.vm.snapshots = 1
  expect(w.vm.reason('usb', row)).toBe('label.vmdevice.snapshotBlocked')
  w.unmount()
})
test('VM changes during submit preflight cannot target the new VM', async () => {
  const w = mount(); await w.vm.refresh()
  w.vm.dialog = 'release'; w.vm.selected = row; w.vm.ack = true
  let resolveVm
  getAPI.mockImplementation(name => name === 'listVirtualMachines' ? new Promise(resolve => { resolveVm = resolve }) : Promise.resolve({}))
  const pending = w.vm.submit(); await flush()
  await w.setProps({ resource: { ...resource, id: 'vm2' } })
  resolveVm({ listvirtualmachinesresponse: { virtualmachine: [resource] } })
  await pending
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.submitting).toBe(false)
  w.unmount()
})
