// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { h } from 'vue'
import Clone from '@/views/offering/CloneVpcOffering'
import { cloneBoolean, vpcCloneDefaults, buildVpcCloneCapabilityParams, mixedVpcCloneCapabilities } from '@/utils/vpcOfferingClone'
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))

const services = ['Dns', 'SourceNat', 'Lb'].map(name => ({ name, provider: [{ name: 'VpcVirtualRouter' }] }))
const source = { id: 'enabled', name: 'Enabled source', state: 'Enabled', conservemode: true, internetprotocol: 'IPv4', networkmode: 'NATTED', routingmode: 'Static', service: services }
const pass = { render () { return h('div', this.$slots.default?.()) } }
const form = { methods: { validate: () => Promise.resolve(), scrollToField: jest.fn() }, render () { return h('form', this.$slots.default?.()) } }
const list = { props: ['dataSource'], render () { return h('ul', this.dataSource.map(item => h('li', this.$slots.renderItem({ item })))) } }
let wrappers
const reply = command => ({
  [command.toLowerCase() + 'response']: {
    domain: [{ id: 'root', name: 'ROOT' }],
    zone: [{ id: 'zone', name: 'Zone' }],
    serviceoffering: [{ id: 'router', name: 'Router' }],
    configuration: [{ value: 'true' }]
  }
})

beforeEach(() => {
  wrappers = []; jest.clearAllMocks()
  getAPI.mockImplementation(command => Promise.resolve(reply(command)))
  postAPI.mockResolvedValue({ clonevpcofferingresponse: { jobid: 'job' } })
})
afterEach(() => wrappers.forEach(wrapper => wrapper.unmount()))

async function open (resource = source, real = false) {
  const wrapper = (real ? mount : shallowMount)(Clone, {
    props: { resource: JSON.parse(JSON.stringify(resource)) },
    global: {
      mocks: { $t: key => key, $getApiParams: () => new Proxy({}, { get: () => ({ description: 'description' }) }), $message: { success: jest.fn() }, $notifyError: jest.fn() },
      stubs: { AForm: real ? false : form, AFormItem: real ? false : pass, ASpin: pass, AList: list, AListItem: pass, ARow: pass, ACol: pass }
    }
  })
  wrappers.push(wrapper); await flushPromises()
  return wrapper
}
async function submit (wrapper) {
  wrapper.vm.handleSubmit(); await flushPromises()
  return postAPI.mock.calls.at(-1)?.[1]
}

test.each(['Enabled', 'Disabled'])('initial switch and untouched request match the %s source', async state => {
  const wrapper = await open({ ...source, state }, true)
  expect(wrapper.get('#form_item_enable').attributes('aria-checked')).toBe(String(state === 'Enabled'))
  const params = await submit(wrapper)
  expect(params.enable).toBe(state === 'Enabled')
  expect(params.conservemode).toBe(true)
})

test.each(['Enabled', 'Disabled'].flatMap(state => [true, false, 'on-off'].map(action => [state, action])))('%s source retains explicit %s after service and mode refresh', async (state, action) => {
  const wrapper = await open({ ...source, state })
  wrapper.vm.form.name = 'Edited clone'
  wrapper.vm.form.displaytext = 'Edited description'
  wrapper.vm.form.enable = action === 'on-off' ? true : action
  if (action === 'on-off') wrapper.vm.form.enable = false
  wrapper.vm.handleSupportedServiceChange('Lb', false, ['VpcVirtualRouter'])
  wrapper.vm.handleForNetworkModeChange('NATTED')
  await flushPromises()
  expect(wrapper.vm.form.name).toBe('Edited clone')
  expect(wrapper.vm.selectedServiceProviderMap.Lb).toBeUndefined()
  const params = await submit(wrapper)
  expect(params).toMatchObject({ name: 'Edited clone', displaytext: 'Edited description', enable: action === true })
  expect(params.supportedservices).not.toContain('Lb')
})

test('conserve false is explicitly preserved and unknown source flags remain inheritance', async () => {
  const wrapper = await open({ ...source, conservemode: false })
  expect(wrapper.vm.form.redundantrouter).toBe('inherit')
  const params = await submit(wrapper)
  expect(params.conservemode).toBe(false)
  expect(Object.keys(params).some(key => key.startsWith('serviceCapabilityList'))).toBe(false)
  expect(vpcCloneDefaults({}).enable).toBeUndefined()
  expect(cloneBoolean('inherit')).toBeUndefined()
})

test('restricted domain/zone IDs are initialized by ID and submitted without option-index conversion', async () => {
  const wrapper = await open({ ...source, domainid: 'root', zoneid: 'zone', serviceofferingid: 'router' }, true)
  expect(wrapper.vm.form).toMatchObject({ ispublic: false, domainid: ['root'], zoneid: ['zone'], serviceofferingid: 'router', routingmode: 'static' })
  expect(wrapper.get('#form_item_ispublic').attributes('disabled')).toBeDefined()
  const params = await submit(wrapper)
  expect(params).toMatchObject({ domainid: 'root', zoneid: 'zone', serviceofferingid: 'router' })
  await expect(wrapper.vm.rules.zoneid[0].validator(null, [])).rejects.toBe('message.clone.vpc.scope.inherit')
  await expect(wrapper.vm.rules.zoneid[0].validator(null, ['all'])).rejects.toBe('message.clone.vpc.scope.inherit')
})

test('source changes reset all previous edits and ignore an earlier service response', async () => {
  const pending = []
  getAPI.mockImplementation(command => command === 'listSupportedNetworkServices' ? new Promise(resolve => pending.push(resolve)) : Promise.resolve(reply(command)))
  const wrapper = await open({ ...source, domainid: 'root', zoneid: 'zone', serviceofferingid: 'router' })
  wrapper.vm.form.name = 'Old edit'; wrapper.vm.form.enable = true; wrapper.vm.form.redundantrouter = 'true'
  await wrapper.setProps({ resource: { ...source, id: 'disabled', name: 'Disabled source', state: 'Disabled', conservemode: false } })
  await flushPromises()
  pending[1](reply('listSupportedNetworkServices')); await flushPromises()
  pending[0](reply('listSupportedNetworkServices')); await flushPromises()
  expect(wrapper.vm.form).toMatchObject({ name: 'Disabled source - Clone', enable: false, domainid: [], zoneid: [], ispublic: true, redundantrouter: 'inherit' })
  expect(wrapper.vm.form.serviceofferingid).toBeUndefined()
  expect((await submit(wrapper))).toMatchObject({ sourceofferingid: 'disabled', enable: false, conservemode: false })
})

test('HTTP failure retry retains false and source values without repopulating the form', async () => {
  const wrapper = await open()
  wrapper.vm.form.name = 'Retry clone'; wrapper.vm.form.enable = false
  postAPI.mockRejectedValueOnce(new Error('HTTP failure'))
  const first = await submit(wrapper)
  expect(postAPI.mock.calls[0][2]).toEqual({ preserveOnFailure: true })
  expect(wrapper.vm.loading).toBe(false)
  expect(wrapper.emitted('close-action')).toBeUndefined()
  const second = await submit(wrapper)
  expect(second).toEqual(first)
  expect(second.enable).toBe(false)
  expect(postAPI).toHaveBeenCalledTimes(2)
})

test('mode transition removes unrelated capabilities while retaining edited scalar fields', async () => {
  const wrapper = await open()
  wrapper.vm.form.enable = false; wrapper.vm.form.name = 'Mode clone'; wrapper.vm.form.redundantrouter = 'true'
  wrapper.vm.handleForNetworkModeChange('ROUTED'); await flushPromises()
  const params = await submit(wrapper)
  expect(params).toMatchObject({ name: 'Mode clone', enable: false, networkmode: 'ROUTED' })
  expect(Object.keys(params).some(key => key.startsWith('serviceCapabilityList'))).toBe(false)
  expect(wrapper.vm.selectedServiceProviderMap.SourceNat).toBeUndefined()
})

test('a routed Gateway can explicitly disable redundancy without an unrelated SourceNat entry', async () => {
  const wrapper = await open({ ...source, networkmode: 'ROUTED', service: [{ name: 'Gateway', provider: [{ name: 'VpcVirtualRouter' }] }] })
  wrapper.vm.form.redundantrouter = 'false'
  expect(await submit(wrapper)).toMatchObject({
    'serviceCapabilityList[0].service': 'Gateway',
    'serviceCapabilityList[0].capabilitytype': 'RedundantRouter',
    'serviceCapabilityList[0].capabilityvalue': false
  })
})

test('clone capability overrides serialize false and skip inheritance or absent services', () => {
  const params = {}
  buildVpcCloneCapabilityParams(params, { redundantrouter: 'false', regionlevelvpc: 'inherit', distributedrouter: 'false' }, { SourceNat: ['VpcVirtualRouter'], Connectivity: ['Ovs'] }, true)
  expect(params).toEqual({
    'serviceCapabilityList[0].service': 'Connectivity',
    'serviceCapabilityList[0].capabilitytype': 'DistributedRouter',
    'serviceCapabilityList[0].capabilityvalue': false,
    'serviceCapabilityList[1].service': 'SourceNat',
    'serviceCapabilityList[1].capabilitytype': 'RedundantRouter',
    'serviceCapabilityList[1].capabilityvalue': false
  })
  const unrelated = {}
  buildVpcCloneCapabilityParams(unrelated, { redundantrouter: 'true', serviceofferingid: 'router' }, { Dns: ['ConfigDrive'] }, false)
  expect(unrelated).toEqual({})
})

test('mixed inheritance and explicit capability replacement is blocked before losing an unknown source value', async () => {
  const wrapper = await open()
  wrapper.vm.handleSupportedServiceChange('Connectivity', true, ['Ovs'])
  wrapper.vm.form.regionlevelvpc = 'false'
  wrapper.vm.form.distributedrouter = 'false'
  expect(mixedVpcCloneCapabilities(wrapper.vm.form, wrapper.vm.selectedServiceProviderMap)).toBe(true)
  await expect(wrapper.vm.rules.serviceproviders[0].validator()).rejects.toBe('message.clone.vpc.capabilities.explicit')
  wrapper.vm.form.redundantrouter = 'false'
  await expect(wrapper.vm.rules.serviceproviders[0].validator()).resolves.toBeUndefined()
})
