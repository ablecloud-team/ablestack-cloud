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

import { h } from 'vue'
import { shallowMount } from '@vue/test-utils'
import { api } from '@/api'
import ListHostDevicesTab from '@/views/infra/ListHostDevicesTab'
import HostLunDevicesTransfer from '@/views/storage/HostLunDevicesTransfer'

jest.mock('@/api', () => ({ api: jest.fn() }))

const device = { hostDevicesName: '/dev/sdb', hostDevicesText: 'HAS_PARTITIONS: true' }
const warningKey = 'message.warning.lun.device.partitions'
const passthrough = { template: '<div><slot /></div>' }
let wrappers

beforeEach(() => {
  wrappers = []
  jest.clearAllMocks()
  api.mockReset()
})

describe('LUN allocations during VM reboot', () => {
  function mockInventory (vmStatus) {
    api.mockImplementation((command, params) => {
      if (command === 'listHostLunDevices') {
        if (params.lunpathmode === 'multipath') {
          return Promise.resolve({ listhostlundevicesresponse: { listhostlundevices: [] } })
        }
        return Promise.resolve({
          listhostlundevicesresponse: {
            listhostlundevices: [{
              hostdevicesname: [device.hostDevicesName],
              hostdevicestext: [device.hostDevicesText],
              vmallocations: { [device.hostDevicesName]: 'vm-1' }
            }]
          }
        })
      }
      if (command === 'listVirtualMachines') {
        if (params.id && vmStatus === 'failed') {
          return Promise.reject(new Error('VM lookup temporarily unavailable'))
        }
        const vms = params.id && vmStatus !== 'missing'
          ? [{ id: 'vm-1', name: 'test-vm', state: vmStatus }]
          : []
        return Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: vms } })
      }
      return Promise.resolve({})
    })
  }

  test.each(['failed', 'missing', 'Starting'])('inventory refresh preserves allocation when VM lookup is %s', async vmStatus => {
    mockInventory(vmStatus)
    const context = {
      resource: { id: 'host-1' },
      vmNames: { [device.hostDevicesName]: 'test-vm' },
      generateXmlLunConfig: jest.fn(),
      updateDataWithVmNames: jest.fn(),
      $notification: { error: jest.fn() }
    }

    await ListHostDevicesTab.methods.fetchLunDevices.call(context)

    expect(context.dataItems[0]).toMatchObject({ hostDevicesName: device.hostDevicesName, virtualmachineid: 'vm-1', isAssigned: true })
    expect(api.mock.calls.some(([command]) => command === 'updateHostLunDevices')).toBe(false)
    expect(context.$notification.error).not.toHaveBeenCalled()
  })

  test.each(['failed', 'missing', 'Starting'])('allocation dialog refresh does not detach a VM whose lookup is %s', async vmStatus => {
    mockInventory(vmStatus)
    const context = {
      resource: { id: 'host-1', ...device },
      detectAllocationState: jest.fn().mockResolvedValue(),
      generateXmlConfig: jest.fn(),
      $notifyError: jest.fn()
    }

    await HostLunDevicesTransfer.methods.refreshVMList.call(context)

    expect(api.mock.calls.some(([command]) => command === 'updateHostLunDevices')).toBe(false)
    expect(context.$notifyError).not.toHaveBeenCalled()
  })
})

afterEach(() => wrappers.forEach(wrapper => wrapper.unmount()))

function mountInventory (record, methods = {}) {
  const wrapper = shallowMount({
    ...ListHostDevicesTab,
    created: undefined,
    methods: { ...ListHostDevicesTab.methods, ...methods }
  }, {
    props: { resource: { id: 'host-1' } },
    data: () => ({ activeKey: '4', dataItems: [record] }),
    global: {
      mocks: { $t: key => key },
      stubs: {
        'a-tabs': passthrough,
        'a-tab-pane': {
          render () {
            return this.$.vnode.key === '4' ? h('div', this.$slots.default()) : null
          }
        },
        'a-table': {
          props: ['dataSource'],
          template: '<div class="lun-actions"><template v-for="record in dataSource"><slot name="bodyCell" :column="{ key: \'action\' }" :record="record" /></template></div>'
        },
        'a-button': { template: '<button @click="$emit(\'click\')"><slot name="icon" /></button>' },
        'a-modal': true
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}

function mountTransfer (hasPartitions) {
  const wrapper = shallowMount({ ...HostLunDevicesTransfer, created: undefined, watch: {} }, {
    props: { resource: { id: 'host-1', ...device, hasPartitions } },
    global: {
      mocks: { $t: key => key },
      stubs: {
        'a-spin': passthrough,
        'a-form': passthrough,
        'a-alert': { props: ['message'], template: '<div role="alert">{{ message }}<slot name="message" /></div>' }
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('LUN devices with partitions', () => {
  test.each([
    { ...device, hasPartitions: true },
    { ...device, hasPartitions: false },
    { ...device, hostDevicesText: '', hasPartitions: true }
  ])('shows an allocation action and carries partition metadata into the dialog: %s', async record => {
    const wrapper = mountInventory(record)

    await wrapper.get('.lun-actions button').trigger('click')

    expect(wrapper.vm.showAddModal).toBe(true)
    expect(wrapper.vm.selectedResource).toMatchObject({ id: 'host-1', hostDevicesName: device.hostDevicesName, hasPartitions: true })
  })

  test('keeps the deallocation action available for an assigned partitioned device', async () => {
    const deallocateLunDevice = jest.fn()
    const record = { ...device, virtualmachineid: 'vm-1', isAssigned: true }
    const wrapper = mountInventory(record, { deallocateLunDevice })

    await wrapper.get('.lun-actions button').trigger('click')

    expect(deallocateLunDevice).toHaveBeenCalledWith(expect.objectContaining(record))
    expect(wrapper.vm.showAddModal).toBe(false)
  })

  test.each([true, false])('shows the partition warning only when partitions exist: %s', hasPartitions => {
    const wrapper = mountTransfer(hasPartitions)

    expect(wrapper.text().includes(warningKey)).toBe(hasPartitions)
  })

  test('submits a partitioned whole disk through the existing allocation API', async () => {
    api.mockResolvedValue({})
    const context = {
      resource: { id: 'host-1', ...device, hasPartitions: true },
      form: { virtualmachineid: 'vm-1' },
      virtualmachines: [{ id: 'vm-1', name: 'test-vm' }],
      tryDetachScsiIfAllocatedByAddress: jest.fn().mockResolvedValue(false),
      generateXmlConfig: HostLunDevicesTransfer.methods.generateXmlConfig,
      $t: key => key,
      $message: { success: jest.fn() },
      $notifyError: jest.fn(),
      $emit: jest.fn()
    }

    await HostLunDevicesTransfer.methods.handleSubmit.call(context)

    expect(api).toHaveBeenCalledWith('updateHostLunDevices', expect.objectContaining({
      hostid: 'host-1',
      hostdevicesname: '/dev/sdb',
      virtualmachineid: 'vm-1',
      xmlconfig: expect.stringContaining("<source dev='/dev/sdb'/>"),
      isattach: true
    }))
    expect(context.$notifyError).not.toHaveBeenCalled()
    expect(context.$emit).toHaveBeenCalledWith('allocation-completed')
  })
})
