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

import VpnCustomerGateway from '@/views/network/VpnCustomerGateway.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const formContext = (initialValues = {}) => ({
  ...VpnCustomerGateway.data(),
  initialValues,
  form: { ...initialValues }
})

beforeEach(() => {
  getAPI.mockResolvedValue({ listcapabilitiesresponse: { capability: {} } })
})

describe('VPN defaults with Europa parameter policy', () => {
  test('new gateways use valid Curve25519 and AES256 defaults', async () => {
    const context = formContext()
    await VpnCustomerGateway.methods.fetchVpnCustomerGatewayParameters.call(context)
    expect(context.form.ikeDh).toBe('Group 31(curve25519)')
    expect(context.form.ikeEncryption).toBe('aes256')
    expect(context.form.espEncryption).toBe('aes256')
    expect(context.form.espHash).toBe('sha256')
    expect(context.allowedDhGroupValues).toEqual(expect.arrayContaining(['modp1024s160', 'modp2048s224', 'modp2048s256', 'curve25519']))
  })

  test('excluded preferred algorithms fall back to allowed choices', async () => {
    getAPI.mockResolvedValue({ listcapabilitiesresponse: { capability: { vpncustomergatewayparameters: {
      excludedencryptionalgorithms: 'aes256',
      excludedhashingalgorithms: 'sha256',
      excludeddhgroups: 'curve25519, modp1024, modp1536'
    } } } })
    const context = formContext()
    await VpnCustomerGateway.methods.fetchVpnCustomerGatewayParameters.call(context)
    expect(context.form.ikeEncryption).toBe('aes128')
    expect(context.form.espHash).toBe('sha1')
    expect(context.form.ikeDh).toBe('Group 14(modp2048)')
  })

  test('editing an existing gateway preserves its selected algorithms', async () => {
    const context = formContext({ ikeDh: 'modp2048', ikeEncryption: 'aes192', espEncryption: 'aes128', espHash: 'sha512' })
    context.form.ikeDh = 'Group 14(modp2048)'
    await VpnCustomerGateway.methods.fetchVpnCustomerGatewayParameters.call(context)
    expect(context.form.ikeDh).toBe('Group 14(modp2048)')
    expect(context.form.ikeEncryption).toBe('aes192')
    expect(context.form.espEncryption).toBe('aes128')
    expect(context.form.espHash).toBe('sha512')
  })
})
