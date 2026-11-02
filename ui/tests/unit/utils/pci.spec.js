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

import { generatePciDeviceXml, matchesPciDeviceXml } from '@/utils/pci'

describe('PCI host device XML', () => {
  test.each([
    ['0000:03:00.0', ['0x0000', '0x03', '0x00', '0x0']],
    ['03:00.0', ['0x0000', '0x03', '0x00', '0x0']],
    ['0001:0a:1f.7 Ethernet controller', ['0x0001', '0x0a', '0x1f', '0x7']],
    ['ABCD:AF:1E.3', ['0xabcd', '0xaf', '0x1e', '0x3']],
    ['  1:a:f.2  VGA controller', ['0x0001', '0x0a', '0x0f', '0x2']]
  ])('maps domain, bus, slot and function without shifting: %s', (name, expected) => {
    const xml = generatePciDeviceXml(name)
    const document = new DOMParser().parseFromString(xml, 'text/xml')
    const address = document.querySelector('hostdev > source > address')

    expect(['domain', 'bus', 'slot', 'function'].map(field => address.getAttribute(field))).toEqual(expected)
    expect(document.documentElement.tagName).toBe('hostdev')
    expect(xml).not.toContain('undefined')
    expect(document.querySelector('devices')).toBeNull()
  })

  test.each([null, '', '0000:03:00', '0000:03:20.0', '0000:03:00.8', '0000:03:00.0invalid'])('rejects an invalid address before producing XML: %s', name => {
    expect(() => generatePciDeviceXml(name)).toThrow('Invalid PCI address')
  })

  test('matches wrapped legacy XML with reordered attributes and double quotes', () => {
    const xml = '<devices><hostdev type="pci"><source><address function="0x7" slot="0x1f" bus="0x0a" domain="0x0001"/></source></hostdev></devices>'
    expect(matchesPciDeviceXml(xml, '0001:0a:1f.7')).toBe(true)
    expect(matchesPciDeviceXml(xml, '0000:0a:1f.7')).toBe(false)
    expect(matchesPciDeviceXml(xml, '0001:0a:1f.6')).toBe(false)
  })

  test('matches a domain-zero source whose domain attribute is omitted', () => {
    const xml = '<hostdev type="pci"><source><address bus="0x3" slot="0x0" function="0x0"/></source></hostdev>'
    expect(matchesPciDeviceXml(xml, '03:00.0')).toBe(true)
  })

  test('matches decimal XML attributes against hexadecimal PCI addresses', () => {
    const xml = '<hostdev type="pci"><source><address domain="1" bus="10" slot="31" function="7"/></source></hostdev>'
    expect(matchesPciDeviceXml(xml, '0001:0a:1f.7')).toBe(true)
  })

  test('uses the host source address instead of the guest PCI address', () => {
    const xml = '<hostdev type="pci"><source><address domain="0" bus="4" slot="0" function="0"/></source><address domain="0" bus="3" slot="0" function="0"/></hostdev>'
    expect(matchesPciDeviceXml(xml, '0000:03:00.0')).toBe(false)
  })
})
