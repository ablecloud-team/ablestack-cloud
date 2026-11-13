// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { h } from 'vue'
import Clone from '@/views/offering/CloneVpcOffering'
import { vpcServiceProviderMap, vpcProviderChoices, sameVpcProviders, buildVpcCloneProviderParams } from '@/utils/vpcOfferingProviders'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))

const service = (name, providers, capability) => ({ name, provider: providers.map(name => ({ name })), ...(capability ? { capability } : {}) })
const resource = { id: 'source', name: 'Original', internetprotocol: 'ipv4', networkmode: 'NATTED', service: [service('Dhcp', ['VpcVirtualRouter']), service('Lb', ['VpcVirtualRouter', 'InternalLbVm']), service('SourceNat', ['VpcVirtualRouter'])] }
const form = { methods: { validate: () => Promise.resolve(), scrollToField: jest.fn() }, render () { return h('form', this.$slots.default?.()) } }
const list = { props: ['dataSource'], render () { return h('ul', this.dataSource.map(item => h('li', this.$slots.renderItem({ item })))) } }
const passthrough = { render () { return h('div', this.$slots.default?.()) } }
let wrappers
beforeEach(() => {
  wrappers = []; jest.clearAllMocks()
  getAPI.mockImplementation(command => Promise.resolve({ [command.toLowerCase() + 'response']: command === 'listServiceOfferings' ? { serviceoffering: [] } : {} }))
  postAPI.mockResolvedValue({ clonevpcofferingresponse: { jobid: 'job' } })
})
afterEach(() => wrappers.forEach(wrapper => wrapper.unmount()))
async function open (source = resource, realForm = false) {
  const wrapper = (realForm ? mount : shallowMount)(Clone, {
    props: { resource: JSON.parse(JSON.stringify(source)) },
    global: {
      mocks: { $t: key => key, $getApiParams: () => new Proxy({}, { get: () => ({ description: 'description' }) }), $message: { success: jest.fn() }, $notifyError: jest.fn() },
      stubs: { AForm: realForm ? false : form, AList: list, ASpin: passthrough, AFormItem: realForm ? false : passthrough, ARow: passthrough, ACol: passthrough, AListItem: passthrough }
    }
  })
  wrappers.push(wrapper); await flushPromises()
  return wrapper
}
function mappings (params) {
  const result = {}
  Object.keys(params).filter(key => /^serviceProviderList.*\.service$/.test(key)).forEach(key => {
    const name = params[key]
    result[name] ||= []
    result[name].push(params[key.replace(/\.service$/, '.provider')])
  })
  return result
}
async function submit (wrapper) {
  wrapper.vm.form.name = 'Copy'
  wrapper.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  return postAPI.mock.calls[0][1]
}

test('unchanged services use inheritance regardless of provider ordering', () => {
  const source = vpcServiceProviderMap(resource.service)
  const selected = { ...source, Lb: ['InternalLbVm', 'VpcVirtualRouter'] }
  const params = {}
  expect(sameVpcProviders(source, selected)).toBe(true)
  buildVpcCloneProviderParams(params, selected, source)
  expect(params).toEqual({})
})

test('changed mappings repeat each service/provider pair and include unaffected services', () => {
  const source = vpcServiceProviderMap(resource.service)
  const selected = { ...source, Dhcp: ['ConfigDrive'] }
  const params = {}
  buildVpcCloneProviderParams(params, selected, source)
  expect(mappings(params)).toEqual(selected)
  expect(params.supportedservices.split(',')).toEqual(Object.keys(source))
  expect(Object.values(params)).not.toContain('VpcVirtualRouter,InternalLbVm')
})

test('unknown source services/providers remain visible and preserve multiple non-LB providers read-only', () => {
  const source = [service('Lb', ['Netscaler', 'InternalLbVm']), service('FutureService', ['FutureOne', 'FutureTwo'])]
  const choices = vpcProviderChoices([{ name: 'Lb', provider: [{ name: 'VpcVirtualRouter' }] }], source)
  expect(choices[0].selectedProviders).toEqual(['Netscaler', 'InternalLbVm'])
  expect(choices[0].provider.map(provider => provider.name)).toEqual(['VpcVirtualRouter', 'Netscaler', 'InternalLbVm'])
  expect(choices[1]).toMatchObject({ name: 'FutureService', preserveOnly: true, selectedProviders: ['FutureOne', 'FutureTwo'] })
  choices[1].selectedProviders.pop()
  expect(source[1].provider).toHaveLength(2)
})

test.each([['VpcVirtualRouter'], ['InternalLbVm'], ['VpcVirtualRouter', 'InternalLbVm']].map(providers => [providers]))('form keeps every original LB provider %j and omits unnecessary maps', async providers => {
  const source = { ...resource, service: [service('Lb', providers), service('SourceNat', ['VpcVirtualRouter'])] }
  const wrapper = await open(source)
  expect(wrapper.vm.selectedServiceProviderMap.Lb).toEqual(providers)
  expect(wrapper.vm.supportedServices.find(service => service.name === 'Lb').selectedProviders).toEqual(providers)
  const params = await submit(wrapper)
  expect(params).toMatchObject({ sourceofferingid: 'source', name: 'Copy' })
  expect(params.supportedservices).toBeUndefined()
  expect(mappings(params)).toEqual({})
})

test('LB control renders both providers in multiple mode while other services remain single', async () => {
  const wrapper = await open()
  const lb = wrapper.get('[aria-label="Lb label.provider"]')
  const dhcp = wrapper.get('[aria-label="Dhcp label.provider"]')
  expect(lb.attributes('mode')).toBe('multiple')
  expect(lb.attributes('value')).toBe('VpcVirtualRouter,InternalLbVm')
  expect(dhcp.attributes('mode')).toBeUndefined()
})

test('uncheck/recheck restores both LB providers and returns to inheritance', async () => {
  const wrapper = await open()
  const lb = wrapper.vm.supportedServices.find(service => service.name === 'Lb')
  wrapper.vm.handleSupportedServiceChange('Lb', false, lb.selectedProviders)
  expect(wrapper.vm.selectedServiceProviderMap.Lb).toBeUndefined()
  wrapper.vm.handleSupportedServiceChange('Lb', true, lb.selectedProviders)
  expect(wrapper.vm.selectedServiceProviderMap.Lb).toEqual(['VpcVirtualRouter', 'InternalLbVm'])
  expect(mappings(await submit(wrapper))).toEqual({})
})

test.each([['VpcVirtualRouter'], ['InternalLbVm']].map(providers => [providers]))('explicit provider change %j is respected without restoring the original', async provider => {
  const wrapper = await open()
  wrapper.vm.handleSupportedServiceChange('Lb', true, provider)
  expect(mappings(await submit(wrapper))).toEqual({ Dhcp: ['VpcVirtualRouter'], Lb: provider, SourceNat: ['VpcVirtualRouter'] })
  expect(wrapper.props('resource')).toEqual(resource)
})

test('service removal transmits all remaining services and never recreates the removed service', async () => {
  const wrapper = await open()
  wrapper.vm.handleSupportedServiceChange('Lb', false, ['VpcVirtualRouter', 'InternalLbVm'])
  const params = await submit(wrapper)
  expect(params.supportedservices).toBe('Dhcp,SourceNat')
  expect(mappings(params)).toEqual({ Dhcp: ['VpcVirtualRouter'], SourceNat: ['VpcVirtualRouter'] })
})

test('redundant capability and VR offering survive provider changes', async () => {
  const source = { ...resource, serviceofferingid: 'router-offering', service: [...resource.service.filter(service => service.name !== 'SourceNat'), service('SourceNat', ['VpcVirtualRouter'], [{ name: 'RedundantRouter', value: 'true' }])] }
  const wrapper = await open(source)
  wrapper.vm.handleSupportedServiceChange('Lb', true, ['InternalLbVm'])
  const params = await submit(wrapper)
  expect(params).toMatchObject({ 'serviceCapabilityList[0].service': 'SourceNat', 'serviceCapabilityList[0].capabilitytype': 'RedundantRouter', 'serviceCapabilityList[0].capabilityvalue': true, serviceofferingid: 'router-offering' })
})

test('empty provider selection stays visible and validation rejects it instead of silently restoring defaults', async () => {
  const wrapper = await open()
  wrapper.vm.handleSupportedServiceChange('Lb', true, [])
  expect(wrapper.vm.selectedServiceProviderMap.Lb).toEqual([])
  await expect(wrapper.vm.rules.serviceproviders[0].validator()).rejects.toBe('message.error.select')
  expect(postAPI).not.toHaveBeenCalled()
})

test('the VR flag checks every provider, including the second provider of a multi-provider service', async () => {
  const wrapper = await open({ ...resource, service: [service('Lb', ['InternalLbVm', 'VpcVirtualRouter'])] })
  expect(wrapper.vm.isVpcVirtualRouterForAtLeastOneService).toBe(true)
  wrapper.vm.handleSupportedServiceChange('Lb', true, ['InternalLbVm'])
  expect(wrapper.vm.isVpcVirtualRouterForAtLeastOneService).toBe(false)
})

test('the real Ant form gives provider inputs independent IDs instead of linking every input to the validation group', async () => {
  const wrapper = await open(resource, true)
  const ids = wrapper.findAll('input[role="combobox"]').map(input => input.attributes('id'))
  expect(ids.length).toBeGreaterThan(3)
  expect(ids.every(Boolean)).toBe(true)
  expect(new Set(ids).size).toBe(ids.length)
  expect(ids).not.toContain('form_item_serviceproviders')
  const serviceIds = wrapper.findAll('.supported-services-container input[id]').map(input => input.attributes('id'))
  expect(new Set(serviceIds).size).toBe(serviceIds.length)
  expect(wrapper.get('#vpc-clone-provider-source-Lb').element.labels[0].textContent).toContain('Lb label.provider')
})
