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

export function parsePciAddress (deviceName) {
  const match = typeof deviceName === 'string' && deviceName.trim().match(/^(?:([0-9a-f]{1,4}):)?([0-9a-f]{1,2}):([0-9a-f]{1,2})\.([0-7])(?=\s|:|$)/i)
  if (!match || parseInt(match[3], 16) > 0x1f) return null

  return {
    domain: '0x' + (match[1] || '0000').toLowerCase().padStart(4, '0'),
    bus: '0x' + match[2].toLowerCase().padStart(2, '0'),
    slot: '0x' + match[3].toLowerCase().padStart(2, '0'),
    func: '0x' + match[4]
  }
}

export function generatePciDeviceXml (deviceName) {
  const address = parsePciAddress(deviceName)
  if (!address) throw new Error('Invalid PCI address: ' + deviceName)
  const { domain, bus, slot, func } = address

  return `<hostdev mode='subsystem' type='pci' managed='yes'>
  <source>
    <address domain='${domain}' bus='${bus}' slot='${slot}' function='${func}'/>
  </source>
</hostdev>`
}

export function matchesPciDeviceXml (xml, deviceName) {
  const address = parsePciAddress(deviceName)
  if (!address || typeof xml !== 'string') return false

  const document = new DOMParser().parseFromString(`<config>${xml}</config>`, 'text/xml')
  if (document.querySelector('parsererror')) return false

  return Array.from(document.querySelectorAll('hostdev[type="pci"] > source > address')).some(source =>
    ['domain', 'bus', 'slot', 'function'].every(field => {
      const actual = source.getAttribute(field) || (field === 'domain' ? '0' : '')
      const expected = address[field === 'function' ? 'func' : field]
      return actual !== '' && Number(actual) === Number(expected)
    })
  )
}
