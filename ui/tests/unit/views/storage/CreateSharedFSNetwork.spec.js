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

import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { mount, flushPromises } from '@vue/test-utils'

const invalid = 'message.sharedfs.static.gateway.invalid'
const vm = (ipcidr = '10.10.13.200/16') => {
  const value = { $t: key => key }
  for (const [key, method] of Object.entries(CreateSharedFS.methods)) value[key] = method.bind(value)
  value.initForm()
  Object.defineProperty(value, 'isStaticNetwork', { get: () => value.form.networkmode === 'STATIC' })
  Object.assign(value.form, { networkmode: 'STATIC', ipcidr })
  return value
}

describe('#913 STATIC gateway validation matches the management boundary', () => {
  it.each([
    ['10.10.13.200/16', '10.10.0.1'],
    ['192.168.3.14/24', '192.168.3.1'],
    ['192.168.3.14/0', '1.1.1.1'],
    ['192.168.3.14/0', '0.0.0.1']
  ])('accepts the valid optional router %s -> %s', async (cidr, gateway) => {
    const value = vm(cidr)
    await expect(value.validateStaticGateway(null, gateway)).resolves.toBeUndefined()
  })
  it.each([
    '0.0.0.0', '10.10.13.200', '10.10.0.0', '10.10.255.255',
    '10.11.0.1', '127.0.0.1', '169.254.1.1', '224.0.0.1', '239.255.1.1',
    '240.0.0.1', '255.255.255.255', '10.10.999.1', '10.10.01.1', 'router.local',
    '::1', 123, true, {}, []
  ])('rejects an invalid, special, self or off-subnet gateway %p', async gateway => {
    const value = vm()
    await expect(value.validateStaticGateway(null, gateway)).rejects.toBe(invalid)
  })
  it.each([
    ['10.0.0.0/31', '10.0.0.1'],
    ['10.0.0.1/31', '10.0.0.0'],
    ['10.0.0.1/32', '10.0.0.1'],
    ['10.0.0.1/32', '10.0.0.2']
  ])('keeps the server non-empty /31-/32 gateway rejection %s -> %s', async (cidr, gateway) => {
    await expect(vm(cidr).validateStaticGateway(null, gateway)).rejects.toBe(invalid)
  })
  it.each(['10.0.0.0/31', '10.0.0.1/32', '10.10.13.200/16'])('keeps gateway and DNS optional for %s', async cidr => {
    const value = vm(cidr)
    for (const gateway of ['', ' ', null, undefined]) await expect(value.validateStaticGateway(null, gateway)).resolves.toBeUndefined()
    expect(value.form.dns1).toBe('')
    expect(value.form.dns2).toBe('')
  })
  it('validates against the current CIDR rather than a previously valid one', async () => {
    const value = vm()
    await expect(value.validateStaticGateway(null, '10.10.0.1')).resolves.toBeUndefined()
    value.form.ipcidr = '192.168.3.14/24'
    await expect(value.validateStaticGateway(null, '10.10.0.1')).rejects.toBe(invalid)
  })
  it('wires the production rule to a real AntD form so an invalid router blocks validation', async () => {
    const value = vm()
    value.form.gateway = '10.10.13.200'
    const wrapper = mount({
      data: () => ({ form: value.form, rules: value.rules }),
      template: '<a-form ref="realForm" :model="form" :rules="rules"><a-form-item name="gateway"><a-input v-model:value="form.gateway" /></a-form-item></a-form>'
    })
    try {
      await expect(wrapper.vm.$refs.realForm.validateFields(['gateway'])).rejects.toMatchObject({
        errorFields: [{ name: ['gateway'], errors: [invalid] }]
      })
      await new Promise(resolve => setTimeout(resolve, 0))
      await flushPromises()
      expect(wrapper.find('.ant-form-item-explain-error').text()).toBe(invalid)
      value.form.gateway = '10.10.0.1'
      await expect(wrapper.vm.$refs.realForm.validateFields(['gateway'])).resolves.toEqual({ gateway: '10.10.0.1' })
    } finally { wrapper.unmount() }
  })
})

describe('#913 mode-specific request preserves user inputs without posting hidden static values', () => {
  it('omits every static field in DHCP mode and restores the same values when returning to STATIC', async () => {
    const value = vm()
    Object.assign(value, {
      owner: { domainid: 'domain', account: 'admin' },
      isCustomizedDiskIOps: false,
      createSharedFsSize: () => undefined
    })
    Object.assign(value.form, {
      name: 'network-test',
      networkid: 'network',
      serviceofferingid: 'offering',
      gateway: ' 10.10.0.1 ',
      dns1: '',
      dns2: ''
    })
    expect(value.buildCreateSharedFsRequest(value.form).gateway).toBe('10.10.0.1')
    const preserved = { ipcidr: value.form.ipcidr, gateway: value.form.gateway, dns1: value.form.dns1, dns2: value.form.dns2 }
    value.form.networkmode = 'DHCP'
    await expect(value.validateStaticGateway(null, 'not-a-router')).resolves.toBeUndefined()
    const dhcp = value.buildCreateSharedFsRequest(value.form)
    expect(dhcp.networkmode).toBe('DHCP')
    for (const field of Object.keys(preserved)) expect(dhcp[field]).toBeUndefined()
    expect(Object.fromEntries(Object.keys(preserved).map(key => [key, value.form[key]]))).toEqual(preserved)
    value.form.networkmode = 'STATIC'
    const restored = value.buildCreateSharedFsRequest(value.form)
    expect(restored.networkmode).toBe('STATIC')
    expect(restored.ipcidr).toBe(preserved.ipcidr)
    expect(restored.gateway).toBe('10.10.0.1')
    expect(restored.dns1).toBeUndefined()
    expect(restored.dns2).toBeUndefined()
  })
})
