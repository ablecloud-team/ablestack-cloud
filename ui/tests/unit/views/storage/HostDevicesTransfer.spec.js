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
import HostDevicesTransfer from '@/views/storage/HostDevicesTransfer'
import ListHostDevicesTab from '@/views/infra/ListHostDevicesTab'
import { matchesPciDeviceXml } from '@/utils/pci'

jest.mock('@/api', () => ({ api: jest.fn() }))

beforeEach(() => api.mockReset())

describe('PCI allocation requests', () => {
  function contextFor (deviceName) {
    return {
      resource: { id: 'host-1', hostDevicesName: deviceName },
      form: { virtualmachineid: 'vm-1' },
      generateXmlConfig: HostDevicesTransfer.methods.generateXmlConfig,
      registerVMEventListener: jest.fn(),
      $t: key => key,
      $message: { success: jest.fn() },
      $notifyError: jest.fn(),
      $emit: jest.fn()
    }
  }

  test.each(['0000:03:00.0 VGA controller', '03:00.0'])('sends correctly parsed PCI XML for server-side persistence: %s', async deviceName => {
    api.mockImplementation(command => Promise.resolve(command === 'listVirtualMachines'
      ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1', details: {} }] } }
      : {}))
    const context = contextFor(deviceName)

    await HostDevicesTransfer.methods.handleSubmit.call(context)

    const request = api.mock.calls.find(([command]) => command === 'updateHostDevices')[1]
    expect(request).toMatchObject({ hostid: 'host-1', virtualmachineid: 'vm-1', hostdevicesname: deviceName })
    expect(matchesPciDeviceXml(request.xmlconfig, '0000:03:00.0')).toBe(true)
    expect(request.xmlconfig).not.toContain('<devices>')
    expect(api.mock.calls.map(([command]) => command)).toEqual(['updateHostDevices'])
    expect(context.$notifyError).not.toHaveBeenCalled()
    expect(context.$emit).toHaveBeenCalledWith('allocation-completed')
  })

  test('does not submit malformed PCI XML', async () => {
    api.mockResolvedValue({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1', details: {} }] } })
    const context = contextFor('0000:03:00')

    await HostDevicesTransfer.methods.handleSubmit.call(context)

    expect(api.mock.calls.some(([command]) => command === 'updateHostDevices' || command === 'updateVirtualMachine')).toBe(false)
    expect(context.$notifyError).toHaveBeenCalledWith(expect.any(Error))
    expect(context.loading).toBe(false)
  })

  test('reports an allocation failure without reporting success', async () => {
    const error = new Error('Host device allocation failed')
    api.mockRejectedValue(error)
    const context = contextFor('0000:03:00.0')

    await HostDevicesTransfer.methods.handleSubmit.call(context)

    expect(context.$notifyError).toHaveBeenCalledWith(error)
    expect(context.$message.success).not.toHaveBeenCalled()
    expect(context.$emit).not.toHaveBeenCalled()
    expect(context.loading).toBe(false)
  })
})

test('PCI removal preserves a device at the same bus and slot in a different domain', async () => {
  const selectedName = '0001:0a:1f.7'
  const selectedXml = '<devices><hostdev type="pci"><source><address function="0x7" domain="0x0001" bus="0x0a" slot="0x1f"/></source></hostdev></devices>'
  const otherXml = selectedXml.replace('0x0001', '0x0002')
  api.mockImplementation(command => Promise.resolve(command === 'listHostDevices'
    ? { listhostdevicesresponse: { listhostdevices: [{ vmallocations: { [selectedName]: 'vm-1' } }] } }
    : command === 'listVirtualMachines'
      ? { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1', state: 'Stopped', details: { 'extraconfig-1': selectedXml, 'extraconfig-2': otherXml } }] } }
      : {}))
  const context = {
    resource: { id: 'host-1' },
    selectedPciDevice: { hostDevicesName: selectedName },
    $t: key => key,
    $notification: { warning: jest.fn() },
    $message: { success: jest.fn() },
    $notifyError: jest.fn(),
    $emit: jest.fn(),
    fetchData: jest.fn()
  }

  await ListHostDevicesTab.methods.handlePciDeviceDelete.call(context)

  const request = api.mock.calls.find(([command]) => command === 'updateVirtualMachine')[1]
  expect(request['details[0].extraconfig-1']).toBeUndefined()
  expect(request['details[0].extraconfig-2']).toBe(otherXml)
  expect(context.$notifyError).not.toHaveBeenCalled()
})
