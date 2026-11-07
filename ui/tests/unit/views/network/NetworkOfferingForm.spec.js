// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

import { offeringFields, offeringServiceProviders, cloneOfferingServiceParams } from '@/utils/networkOfferingForm'
import { buildServiceCapabilityParams } from '@/composables/useServiceCapabilityParams'

const service = (name, provider, enabled = true) => ({ name, provider: [{ name: provider, enabled }] })
const catalog = [service('SourceNat', 'VirtualRouter'), service('Dhcp', 'VirtualRouter'), service('Lb', 'Netscaler', false)]

test('L2 sends ConfigDrive alone and keeps explicit false security settings', () => {
  const values = { guestiptype: 'l2', userdatal2: true, forvpc: true, conservemode: false, networkmode: 'NATTED', serviceofferingid: 'stale', promiscuousmode: false }
  const providers = offeringServiceProviders(values, { SourceNat: 'VirtualRouter' }, catalog)
  const params = offeringFields(values, providers)
  buildServiceCapabilityParams(params, values, providers, [])
  expect(params).toEqual({
    guestiptype: 'l2',
    'details[0].promiscuousmode': false,
    supportedservices: 'UserData',
    'serviceProviderList[0].service': 'UserData',
    'serviceProviderList[0].provider': 'ConfigDrive'
  })
})

test('routed mode drops NAT and disallowed providers, preserving enabled DHCP', () => {
  const values = { guestiptype: 'isolated', networkmode: 'ROUTED', enable: false, sourcenattype: 'perzone' }
  const providers = offeringServiceProviders(values, { SourceNat: 'VirtualRouter', Dhcp: 'VirtualRouter', Lb: 'Netscaler' }, catalog)
  expect(providers).toEqual({ Dhcp: 'VirtualRouter' })
  expect(offeringFields(values, providers)).toEqual({ guestiptype: 'isolated', networkmode: 'ROUTED', enable: false })
})

test('Shared EIP belongs to StaticNat and sends explicit false for public IP association', () => {
  const params = {}
  buildServiceCapabilityParams(params, { guestiptype: 'shared', elasticip: true, associatepublicip: false }, { StaticNat: 'Netscaler' }, [])
  expect(params['serviceCapabilityList[0].service']).toBe('StaticNat')
  expect(params['serviceCapabilityList[1].capabilityvalue']).toBe(false)
  expect(params.supportedservices).toBe('StaticNat')
})

test('clone omits the original provider map and explicitly drops all services when emptied', () => {
  const source = { service: [{ name: 'Dhcp', provider: [{ name: 'VirtualRouter' }, { name: 'ExternalDhcp' }] }] }
  const unchanged = { supportedservices: 'Dhcp', 'serviceProviderList[0].provider': 'VirtualRouter' }
  cloneOfferingServiceParams(source, { Dhcp: 'VirtualRouter' }, unchanged)
  expect(unchanged).toEqual({})
  const removed = { supportedservices: '' }
  cloneOfferingServiceParams(source, {}, removed)
  expect(removed.dropservices).toBe('Dhcp')
})
