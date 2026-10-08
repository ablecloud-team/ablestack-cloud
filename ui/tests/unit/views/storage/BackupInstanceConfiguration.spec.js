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

import { shallowMount, flushPromises } from '@vue/test-utils'
import DiskSizeSelection from '@/views/compute/wizard/DiskSizeSelection.vue'
import VolumeDiskOfferingSelectView from '@/views/compute/wizard/VolumeDiskOfferingSelectView.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/components/view/InfoCard', () => ({}))
jest.mock('@/store', () => ({ getters: {} }))
jest.mock('@/utils/mixin.js', () => ({ mixin: {}, mixinDevice: {} }))

// The deployment wizard's child controls are outside these restore option tests.
for (const name of ['OwnershipSelection', 'ComputeOfferingSelection', 'ComputeSelection', 'DiskOfferingSelection',
  'DiskSizeSelection', 'VolumeDiskOfferingSelectView', 'MultiDiskSelection', 'TemplateIsoSelection',
  'AffinityGroupSelection', 'NetworkSelection', 'NetworkConfiguration', 'SshKeyPairSelection',
  'UserDataSelection', 'SecurityGroupSelection']) {
  jest.doMock('@views/compute/wizard/' + name, () => ({}), { virtual: true })
}
const DeployVMFromBackup = require('@/components/view/DeployVMFromBackup.vue').default
const CreateVMFromBackup = require('@/views/storage/CreateVMFromBackup.vue').default

function restoreContext (provider = 'ablestack-nas') {
  const context = {
    dataPreFill: { backupprovider: provider, backupRootDiskSize: 100, backupBootType: 'UEFI', backupBootMode: 'LEGACY', datadisksdetails: [{ deviceid: 1, size: 50 }] },
    form: { rootdisksize: 100, boottype: 'UEFI', bootmode: 'LEGACY', volumesdiskoffering: {} },
    tabKey: 'templateid',
    template: { size: 10 * 1024 ** 3 },
    overrideDiskOffering: { disksize: 100, provisioningtype: 'fat' },
    options: { diskOfferings: [] }
  }
  context.requiresBackupDiskCapacity = DeployVMFromBackup.computed.requiresBackupDiskCapacity.call(context)
  return context
}

test.each(['ablestack-nas', 'ablestack-veeam', 'ablestack-commvault', 'ablestack-netbackup'])('%s requires matching restore capacities', provider => {
  const context = restoreContext(provider)
  expect(context.requiresBackupDiskCapacity).toBe(true)
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(false)
  context.overrideDiskOffering.disksize = 200
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(true)
})

test('custom root sizes, oversized templates and data disk sizes are validated', () => {
  const context = restoreContext()
  context.overrideDiskOffering = { iscustomized: true }
  context.form.rootdisksize = 101
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(true)
  context.form.rootdisksize = 100
  context.template.size = 101 * 1024 ** 3
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(true)
  context.template.size = 10 * 1024 ** 3
  context.form.volumesdiskoffering = { 0: { deviceid: 1, size: 51 } }
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(true)
  context.form.volumesdiskoffering[0].size = 50
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(false)
})

test('other providers retain their existing disk capacity behavior', () => {
  const context = restoreContext('dummy')
  context.overrideDiskOffering.disksize = 200
  expect(context.requiresBackupDiskCapacity).toBe(false)
  expect(DeployVMFromBackup.computed.hasRestoreDiskSizeMismatch.call(context)).toBe(false)
})

test('boot warning reflects firmware and UEFI mode changes', () => {
  const context = restoreContext()
  expect(DeployVMFromBackup.computed.hasBackupBootMismatch.call(context)).toBe(false)
  context.form.bootmode = 'SECURE'
  expect(DeployVMFromBackup.computed.hasBackupBootMismatch.call(context)).toBe(true)
  context.form.boottype = 'BIOS'
  expect(DeployVMFromBackup.computed.hasBackupBootMismatch.call(context)).toBe(true)
  delete context.dataPreFill.backupBootType
  expect(DeployVMFromBackup.computed.hasBackupBootMismatch.call(context)).toBe(false)
})

test('Thin data disk warning survives root offering pagination', () => {
  const context = restoreContext()
  context.form.volumesdiskoffering = { 0: { deviceid: 1, offering: 'data-thin', size: 50, provisioningtype: 'thin' } }
  expect(DeployVMFromBackup.computed.hasThinRestoreOffering.call(context)).toBe(true)
  context.form.volumesdiskoffering[0].provisioningtype = 'fat'
  expect(DeployVMFromBackup.computed.hasThinRestoreOffering.call(context)).toBe(false)
})

test('backup VM settings prefill explicit false flags and backed-up capacity', () => {
  const context = {
    resource: {
      id: 'backup',
      zoneid: 'zone',
      vmdetails: { templateid: 'template', vmsettings: JSON.stringify({ UEFI: 'LEGACY', iothreads: 'false', 'nic.packed.virtqueues.enabled': 'false' }) },
      volumes: JSON.stringify([{ type: 'ROOT', size: 100 * 1024 ** 3, diskOfferingId: 'root' }])
    },
    backupOffering: {},
    backupProvider: 'ablestack-nas',
    dataPreFill: {}
  }
  CreateVMFromBackup.methods.populatePreFillData.call(context)
  expect(context.dataPreFill).toMatchObject({ backupRootDiskSize: 100, rootdisksize: 100, boottype: 'UEFI', bootmode: 'LEGACY', iothreadsenabled: false, nicpackedvirtqueuesenabled: false })
})

const InputNumber = { props: ['min', 'max', 'disabled'], template: '<input />' }
test('shared disk size control remains editable unless the restore capacity is fixed', async () => {
  const wrapper = shallowMount(DiskSizeSelection, {
    props: { isCustomized: true, minDiskSize: 100, inputDecorator: 'rootdisksize' },
    global: { renderStubDefaultSlot: true, mocks: { $t: key => key }, stubs: { 'a-input-number': InputNumber } }
  })
  expect(wrapper.findComponent(InputNumber).props('max')).toBeUndefined()
  expect(wrapper.findComponent(InputNumber).props('disabled')).toBeUndefined()
  await wrapper.setProps({ maxDiskSize: 100 })
  expect(wrapper.findComponent(InputNumber).props('max')).toBe(100)
  expect(wrapper.findComponent(InputNumber).props('disabled')).toBe(true)
  wrapper.unmount()
})

test.each([false, true])('shared data offerings preserve normal selection with fixedCapacity=%s', async fixedCapacity => {
  getAPI.mockResolvedValue({
    listdiskofferingsresponse: {
      diskoffering: [
        { id: 'same', disksize: 50, provisioningtype: 'thin' },
        { id: 'larger', disksize: 100 },
        { id: 'custom', iscustomized: true }
      ]
    }
  })
  const wrapper = shallowMount(VolumeDiskOfferingSelectView, {
    props: { fixedCapacity, items: [{ id: 0, diskofferingid: 'same', deviceid: 1, size: 50, name: 'data' }] },
    global: { mocks: { $t: key => key } }
  })
  await flushPromises()
  expect(wrapper.vm.validOfferings[0].map(offering => offering.id)).toEqual(fixedCapacity ? ['same', 'custom'] : ['same', 'larger', 'custom'])
  const emitted = wrapper.emitted('select-volumes-disk-offering').slice(-1)[0][0][0]
  if (fixedCapacity) expect(emitted.provisioningtype).toBe('thin')
  else expect(emitted).not.toHaveProperty('provisioningtype')
  wrapper.unmount()
})

test.each([false, true])('custom data disk capacity is fixed only with fixedCapacity=%s', async fixedCapacity => {
  getAPI.mockResolvedValue({ listdiskofferingsresponse: { diskoffering: [{ id: 'custom', iscustomized: true }] } })
  const TableSizeCell = {
    props: ['dataSource'],
    template: '<div><slot name="bodyCell" :column="{ key: \'size\' }" :record="dataSource[0]" /></div>'
  }
  const wrapper = shallowMount(VolumeDiskOfferingSelectView, {
    props: { fixedCapacity, items: [{ id: 0, diskofferingid: 'custom', deviceid: 1, size: 50, name: 'data' }] },
    global: { renderStubDefaultSlot: true, mocks: { $t: key => key }, stubs: { 'a-table': TableSizeCell, 'a-input-number': InputNumber } }
  })
  await flushPromises()
  const input = wrapper.findComponent(InputNumber)
  expect(input.props('min')).toBe(50)
  expect(input.props('max')).toBe(fixedCapacity ? 50 : undefined)
  expect(input.props('disabled')).toBe(fixedCapacity ? true : undefined)
  if (!fixedCapacity) {
    input.vm.$emit('change', 100)
    expect(wrapper.emitted('select-volumes-disk-offering').slice(-1)[0][0][0].size).toBe(100)
  }
  wrapper.unmount()
})
