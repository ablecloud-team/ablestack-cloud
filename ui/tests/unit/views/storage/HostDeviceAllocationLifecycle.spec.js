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

import { api } from '@/api'
import eventBus from '@/config/eventBus'
import ListHostDevicesTab from '@/views/infra/ListHostDevicesTab'
import HostUsbDevicesTransfer from '@/views/storage/HostUsbDevicesTransfer'
import HostHbaDevicesTransfer from '@/views/storage/HostHbaDevicesTransfer'
import HostScsiDevicesTransfer from '@/views/storage/HostScsiDevicesTransfer'

jest.mock('@/api', () => ({ api: jest.fn() }))
jest.mock('@/config/eventBus', () => ({ emit: jest.fn() }))

const devices = {
  pci: { name: '0000:03:00.0', response: 'listhostdevices', update: 'updateHostDevices' },
  usb: { name: '001 Device 002', response: 'listhostusbdevices', update: 'updateHostUsbDevices', component: HostUsbDevicesTransfer },
  hba: { name: 'scsi_host2', response: 'listhosthbadevices', update: 'updateHostHbaDevices', component: HostHbaDevicesTransfer },
  scsi: { name: '/dev/sg2', response: 'listhostscsidevices', update: 'updateHostScsiDevices', component: HostScsiDevicesTransfer },
  vhba: { name: 'scsi_host2_vhba1', response: 'listvhbadevices', update: 'updateHostVhbaDevices' }
}
const inventoryMethods = [
  ['pci', 'updateVmNames'],
  ['usb', 'updateUsbVmNames'],
  ['hba', 'fetchHbaDevices'],
  ['hba', 'updateHbaVmNames'],
  ['scsi', 'fetchScsiDevices'],
  ['vhba', 'updateVhbaVmNames']
]

beforeEach(() => {
  api.mockReset()
  eventBus.emit.mockReset()
})

function mockInventory (type, vmStatus) {
  const device = devices[type]
  const allocatedVm = { id: 'vm-1', name: 'allocated-vm', instancename: 'i-2-42-VM', state: vmStatus }
  api.mockImplementation((command, params) => {
    if (command === 'listVirtualMachines') {
      if (params.id) {
        if (vmStatus === 'lookup-error') return Promise.reject(new Error('temporary VM lookup failure'))
        return Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: vmStatus === 'empty' ? [] : [allocatedVm] } })
      }
      return Promise.resolve({
        listvirtualmachinesresponse: {
          virtualmachine: [
            { ...allocatedVm, state: 'Running' },
            { id: 'vm-2', name: 'available-vm', instancename: 'i-2-43-VM', state: 'Running' }
          ]
        }
      })
    }
    if (command.startsWith('listHost') || command === 'listVhbaDevices') {
      const response = command === 'listHostHbaDevices' ? 'listhosthbadevices' : device.response
      return Promise.resolve({
        [response + 'response']: {
          [response]: [{
            hostdevicesname: [device.name],
            hostdevicestext: ['Device: /dev/sdb SCSI Address: [2:0:0:0]'],
            vmallocations: { [device.name]: 'vm-1' },
            devicetypes: ['physical'],
            parenthbanames: ['']
          }]
        }
      })
    }
    return Promise.resolve({})
  })
}

function contextFor (type) {
  const device = devices[type]
  return {
    resource: { id: 'host-1', hostDevicesName: 'another-device' },
    dataItems: [{ hostDevicesName: device.name, virtualmachineid: 'vm-1', isAssigned: true, vmName: 'cached-vm' }],
    vmNames: { [device.name]: 'cached-vm' },
    selectedDevices: [device.name],
    vmNameLoading: false,
    virtualmachines: [],
    generateXmlConfig: jest.fn().mockReturnValue('<hostdev/>'),
    generateXmlUsbConfig: jest.fn().mockReturnValue('<hostdev/>'),
    generateHbaDeallocationXmlConfig: jest.fn().mockReturnValue('<hostdev/>'),
    generateScsiXmlFromText: jest.fn().mockReturnValue('<hostdev/>'),
    generateVhbaDeallocationXmlConfig: jest.fn().mockReturnValue('<hostdev/>'),
    fetchData: jest.fn(),
    fetchUsbDevices: jest.fn(),
    fetchHbaDevices: jest.fn(),
    updateHbaVmNames: jest.fn(),
    updateVhbaVmNames: jest.fn(),
    detectAllocationState: jest.fn(),
    $nextTick: jest.fn(),
    $forceUpdate: jest.fn(),
    $t: key => key === ' ' ? '' : key,
    $notification: { error: jest.fn(), warning: jest.fn() },
    $notifyError: jest.fn()
  }
}

describe.each(inventoryMethods)('%s inventory through %s', (type, method) => {
  test.each(['lookup-error', 'empty', 'Starting'])('preserves allocation on VM lookup result: %s', async vmStatus => {
    mockInventory(type, vmStatus)
    const context = contextFor(type)

    await ListHostDevicesTab.methods[method].call(context)

    expect(api.mock.calls.some(([command]) => command.startsWith('updateHost'))).toBe(false)
    expect(context.dataItems.find(item => item.hostDevicesName === devices[type].name)).toMatchObject({ virtualmachineid: 'vm-1', isAssigned: true })
    expect(context.$notification.error).not.toHaveBeenCalled()
    expect(context.$notifyError).not.toHaveBeenCalled()
  })

  test('retains cleanup for a VM confirmed to be Expunging', async () => {
    mockInventory(type, 'Expunging')

    await ListHostDevicesTab.methods[method].call(contextFor(type))

    expect(api).toHaveBeenCalledWith(devices[type].update, expect.objectContaining({
      hostdevicesname: devices[type].name,
      virtualmachineid: null
    }))
  })
})

describe.each(['usb', 'hba', 'scsi'])('%s allocation dialog', type => {
  test.each(['lookup-error', 'empty', 'Starting'])('does not release an assigned device on VM lookup result: %s', async vmStatus => {
    mockInventory(type, vmStatus)
    const context = contextFor(type)

    await devices[type].component.methods.refreshVMList.call(context)

    expect(api.mock.calls.some(([command]) => command.startsWith('updateHost'))).toBe(false)
    expect(context.$notifyError).not.toHaveBeenCalled()
    if (type === 'usb') {
      expect(context.virtualmachines.map(vm => vm.id)).toEqual(['vm-2'])
    }
  })
})

test.each([
  ['StartVM', '3', 'fetchUsbDevices'],
  ['StartVM', '2', 'fetchHbaDevices'],
  ['RebootVM', '1', 'fetchData']
])('%s refreshes the active device tab without attaching devices again', async (eventType, activeKey, fetchMethod) => {
  const context = contextFor('usb')
  context.activeKey = activeKey
  ListHostDevicesTab.methods.setupVMEventListeners.call(context)
  const registration = eventBus.emit.mock.calls.map(([, options]) => options).find(options => options.eventType === eventType)

  await registration.callback({ id: 'vm-1' })

  expect(context[fetchMethod]).toHaveBeenCalledTimes(1)
  expect(api).not.toHaveBeenCalled()
})

test('LUN name refresh uses the current device metadata without requesting PCI inventory', async () => {
  const context = {
    dataItems: [{ hostDevicesName: '/dev/mapper/mpatha', virtualmachineid: 'vm-1', isAssigned: true }],
    vmNames: { '/dev/mapper/mpatha': 'allocated-vm' }
  }

  await ListHostDevicesTab.methods.updateDataWithVmNames.call(context)

  expect(context.dataItems[0]).toMatchObject({ vmName: 'allocated-vm', virtualmachineid: 'vm-1', isAssigned: true })
  expect(api).not.toHaveBeenCalled()
})
