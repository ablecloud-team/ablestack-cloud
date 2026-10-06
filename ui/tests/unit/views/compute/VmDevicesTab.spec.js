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
  return shallowMount(VmDevicesTab, { props: { resource }, global: { mocks: { $t: x => x, $store: { getters: { apis: { updateHostUsbDevices: {}, updateHostDevices: {}, updateHostLunDevices: {}, updateHostHbaDevices: {}, updateHostScsiDevices: {} } } }, $message: { success: jest.fn() } } } })
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

const partition = { name: '/dev/mapper/test', type: 'lun', usage: 'partitioned', hasPartitions: true, text: 'SIZE: 128M', safety: { verified: true, status: 'partitioned', path: '/dev/mapper/test', wwn: 'test-wwn', size: '128M', haspartitions: true, mountpoints: [], partitions: [{ path: '/dev/test1', filesystem: 'ext4', size: '127M', mountpoints: [] }] } }
async function readyPartition (w) {
  await w.vm.refresh()
  w.vm.dialog = 'allocate'; w.vm.type = 'lun'; w.vm.candidates = [partition]; w.vm.choice = partition.name
  await flush(); w.vm.ack = true; await flush()
}
test('partition allocation requires acknowledgement and resets on identity, address, host and type changes', async () => {
  const w = mount(); await readyPartition(w)
  expect(w.vm.partitionRisk).toBe(true)
  expect(w.vm.submitDisabled).toBe(false)
  w.vm.candidates = [{ ...partition, safety: { ...partition.safety, wwn: 'replaced' } }]
  await flush(); expect(w.vm.ack).toBe(false); expect(w.vm.submitDisabled).toBe(true)
  for (const [key, value] of [['address', '1:0:0:0'], ['pathMode', 'single'], ['hostId', 'host2'], ['type', 'scsi']]) {
    w.vm.ack = true; w.vm[key] = value; await flush(); expect(w.vm.ack).toBe(false)
  }
  w.unmount()
})
test('partition-aware API includes explicit acknowledgement and final inventory change prevents submit', async () => {
  const w = mount(); await readyPartition(w)
  w.vm.loadCandidates = jest.fn().mockResolvedValue([partition])
  postAPI.mockResolvedValue({})
  await w.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('updateHostLunDevices', expect.objectContaining({ acknowledgepartitionrisk: true, virtualmachineid: 'vm1' }))
  await readyPartition(w); postAPI.mockClear()
  w.vm.loadCandidates.mockResolvedValue([{ ...partition, safety: { ...partition.safety, serial: 'changed' } }])
  await w.vm.submit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.ack).toBe(false)
  expect(w.vm.dialogError).toBe('label.vmdevice.mediumChanged')
  w.unmount()
})
test('HBA uses selected child medium and cannot acknowledge mounted or wrong-adapter children', async () => {
  const w = mount(); await w.vm.refresh()
  w.vm.dialog = 'allocate'; w.vm.type = 'hba'; w.vm.choice = 'scsi_host12'; w.vm.candidates = [{ name: 'scsi_host12', type: 'hba', usage: 'available' }]
  await flush()
  w.vm.scsiDevices = [{ ...partition, type: 'scsi', text: '[12:0:0:1]' }]; w.vm.address = '12:0:0:1'; await flush()
  w.vm.ack = true
  expect(w.vm.partitionRisk).toBe(true); expect(w.vm.submitDisabled).toBe(false)
  w.vm.scsiDevices = [{ ...partition, type: 'scsi', usage: 'mounted', text: '[12:0:0:1]' }]; await flush()
  w.vm.ack = true; expect(w.vm.submitDisabled).toBe(true)
  w.vm.address = '13:0:0:1'; await flush(); w.vm.ack = true; expect(w.vm.submitDisabled).toBe(true)
  w.unmount()
})

test('a mount discovered during submit preflight updates the medium and clears acknowledgement', async () => {
  const w = mount(); await readyPartition(w)
  const mounted = { ...partition, usage: 'mounted', safety: { ...partition.safety, status: 'mounted', mountpoints: ['/mnt/in-use'] } }
  w.vm.loadCandidates = jest.fn().mockResolvedValue([mounted])
  await w.vm.submit()
  expect(postAPI).not.toHaveBeenCalled()
  expect(w.vm.selectedMedium).toEqual(mounted)
  expect(w.vm.ack).toBe(false)
  expect(w.vm.submitDisabled).toBe(true)
  expect(w.vm.dialogError).toBe('label.vmdevice.usage.mounted')
  w.unmount()
})

test('refresh keeps the established reason stable while independently disabling mutation', async () => {
  const w = mount(); await readyPartition(w)
  w.vm.loading = true
  expect(w.vm.blockReason).toBe('')
  expect(w.vm.operationReason).toBe('')
  expect(w.vm.submitDisabled).toBe(true)
  w.vm.loading = false
  w.vm.snapshots = 1
  w.vm.loading = true
  expect(w.vm.blockReason).toBe('label.vmdevice.snapshotBlocked')
  w.unmount()
})
