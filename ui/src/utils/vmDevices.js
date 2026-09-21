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

export const deviceTypes = {
  pci: ['listHostDevices', 'updateHostDevices'],
  usb: ['listHostUsbDevices', 'updateHostUsbDevices'],
  lun: ['listHostLunDevices', 'updateHostLunDevices'],
  scsi: ['listHostScsiDevices', 'updateHostScsiDevices'],
  hba: ['listHostHbaDevices', 'updateHostHbaDevices'],
  vhba: ['listVhbaDevices', 'updateHostVhbaDevices']
}
export const asArray = value => value == null ? [] : Array.isArray(value) ? value : [value]
export function deviceCandidates (response, type) {
  const body = response[deviceTypes[type][0].toLowerCase() + 'response'] || {}
  const groups = asArray(body[deviceTypes[type][0].toLowerCase()] || body.vhbadevices)
  return groups.flatMap(group => asArray(group.hostdevicesname).map((name, i) => ({
    name,
    text: group.devicedetails?.[name] || asArray(group.hostdevicestext)[i] || '',
    allocation: group.vmallocations?.[name],
    usage: ['lun', 'scsi'].includes(type) ? (group.haspartitions?.[name] ? 'partitioned' : group.deviceusagestatus?.[name] || 'unknown') : 'available',
    parent: asArray(group.parenthbanames)[i] || group.parenthbaname,
    wwnn: asArray(group.wwnns)[i],
    type
  }))).filter(device => type !== 'vhba' || /^scsi_host\d+$/.test(device.parent || ''))
    .map(device => ({ ...device, protected: type === 'usb' && /hub|idrac|integrated.*(nic|keyboard|mouse)/i.test(device.text) }))
}
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]))
export function deviceXml (device) {
  const name = device.name || device.hostdevicesname || ''
  const text = device.text || device.hostdevicestext || ''
  const type = device.type || device.devicetype
  if (type === 'pci') {
    const m = name.match(/^(?:([0-9a-f]{4}):)?([0-9a-f]{2}):([0-9a-f]{2})\.([0-7])(?:\s|$)/i)
    if (m) return `<devices><hostdev mode='subsystem' type='pci' managed='yes'><source><address domain='0x${m[1] || '0000'}' bus='0x${m[2]}' slot='0x${m[3]}' function='0x${m[4]}'/></source></hostdev></devices>`
  } else if (type === 'usb') {
    const m = name.match(/(?:Bus\s*)?(\d+)(?:\s+Device\s+|[:.])(\d+)/i)
    if (m) return `<hostdev mode='subsystem' type='usb' managed='yes'><source><address bus='${Number(m[1])}' device='${Number(m[2])}'/></source></hostdev>`
  } else if (type === 'lun') {
    const base = name.split(' (')[0]
    const byid = name.match(/\(([^)]+)\)/)?.[1]
    const path = byid ? '/dev/disk/by-id/' + byid : base
    if (/^\/dev\/[\w/.:+-]+$/.test(path)) return `<disk type='block' device='lun'><driver name='qemu' type='raw' io='native' cache='none'/><source dev='${escape(path)}'/><target bus='scsi'/></disk>`
  } else {
    const m = (device.address || text || name).match(/(?:\[|SCSI_ADDRESS:\s*|^)(\d+):(\d+):(\d+):(\d+)(?:\]|\s|$)/i)
    if (m) return `<hostdev mode='subsystem' type='scsi'><source><adapter name='scsi_host${m[1]}'/><address bus='${m[2]}' target='${m[3]}' unit='${m[4]}'/></source></hostdev>`
  }
  throw new Error('device-address-unverified')
}
export function vhbaXml (parent) {
  if (!/^scsi_host\d+$/.test(parent)) throw new Error('device-address-unverified')
  return `<device><parent>${parent}</parent><capability type='scsi_host'><capability type='fc_host'/></capability></device>`
}

// Presentation only: preserve the original API identifier and XML source.
export function deviceSummary (device) {
  const name = device.name || device.hostdevicesname || ''
  const text = device.text || device.hostdevicestext || ''
  const type = device.type || device.devicetype
  if (!['scsi', 'lun'].includes(type)) return name
  const path = text.match(/\bDevice:\s*(\/dev\/\S+)/i)?.[1] || name.split(' (')[0]
  const size = text.match(/\bSIZE:\s*(\S+)/i)?.[1]
  const model = text.match(/\bModel:\s*(.*?)(?=\s+(?:Revision|Serial|Device|BY_ID|TRANSPORT|SIZE):|$)/i)?.[1]
  return [path, size, model].filter(Boolean).join(' · ')
}
