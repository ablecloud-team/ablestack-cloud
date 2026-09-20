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

import { deviceCandidates, deviceSummary, deviceXml } from '@/utils/vmDevices'

test('does not present physical FC ports as virtual HBAs even when API type is wrong', () => {
  const response = { listvhbadevicesresponse: { listvhbadevices: [{ hostdevicesname: ['scsi_host2', 'scsi_host9'], parenthbanames: ['pci_0000_a0_00_0', 'scsi_host2'], devicetypes: ['virtual', 'virtual'] }] } }
  expect(deviceCandidates(response, 'vhba').map(d => d.name)).toEqual(['scsi_host9'])
})
test('management USB devices cannot be selected', () => {
  const r = { listhostusbdevicesresponse: { listhostusbdevices: [{ hostdevicesname: ['001:002', '001:003'], hostdevicestext: ['Dell iDRAC Virtual NIC', 'USB Serial Adapter'] }] } }
  expect(deviceCandidates(r, 'usb').map(d => d.protected)).toEqual([true, false])
})
test('malformed addresses fail closed instead of defaulting to another device', () => {
  for (const type of ['pci', 'usb', 'scsi', 'hba', 'vhba', 'lun']) expect(() => deviceXml({ type, name: 'unknown' })).toThrow('device-address-unverified')
})
test('preserves PCI domain and USB decimal bus/device identity', () => {
  expect(deviceXml({ type: 'pci', name: '0001:65:00.1' })).toContain("domain='0x0001'")
  expect(deviceXml({ type: 'usb', name: 'Bus 002 Device 010' })).toContain("bus='2' device='10'")
})
test('SCSI and HBA XML use verified source address including host number', () => {
  expect(deviceXml({ type: 'hba', name: 'scsi_host14', text: 'SCSI_Address: 14:0:2:3' })).toContain("name='scsi_host14'")
  expect(deviceXml({ type: 'scsi', text: '[14:0:2:3]' })).toContain("target='2' unit='3'")
})
test('multipath LUN leaves target selection to backend and preserves source', () => {
  const xml = deviceXml({ type: 'lun', name: '/dev/mapper/mpatha' })
  expect(xml).toContain("dev='/dev/mapper/mpatha'")
  expect(xml).toContain("<target bus='scsi'/>")
})

test('LUN and SCSI keep used and unknown devices visible with blocking usage', () => {
  for (const type of ['lun', 'scsi']) {
    const key = 'listhost' + type + 'devices'
    const group = { hostdevicesname: ['free', 'partition', 'vm', 'unverified'], haspartitions: { partition: true }, deviceusagestatus: { free: 'available', partition: 'available', vm: 'vm-connected' } }
    const candidates = deviceCandidates({ [key + 'response']: { [key]: [group] } }, type)
    expect(candidates.map(d => d.name)).toEqual(group.hostdevicesname)
    expect(candidates.map(d => d.usage)).toEqual(['available', 'partitioned', 'vm-connected', 'unknown'])
  }
})

test('disk display uses lsblk path, capacity and model without changing attachment identity', () => {
  const disk = { type: 'scsi', name: '/dev/sg1 (wwn-123)', text: 'SCSI_Address: [0:0:275:0] Type: disk SIZE: 3.50T Vendor: ATA Model: Model With Spaces Revision: V1 Device: /dev/sdb BY_ID: /dev/disk/by-id/wwn-123' }
  const xml = deviceXml(disk)
  expect(deviceSummary(disk)).toBe('/dev/sdb · 3.50T · Model With Spaces')
  expect(deviceXml(disk)).toBe(xml)
  expect(disk.name).toBe('/dev/sg1 (wwn-123)')
  expect(deviceSummary({ devicetype: 'lun', hostdevicesname: '/dev/mapper/mpatha (wwn-123)', hostdevicestext: 'TYPE: multipath SIZE: 7.3T' })).toBe('/dev/mapper/mpatha · 7.3T')
  expect(deviceSummary({ type: 'scsi', name: '/dev/sg1', text: 'Device: /dev/sdb' })).toBe('/dev/sdb')
})
