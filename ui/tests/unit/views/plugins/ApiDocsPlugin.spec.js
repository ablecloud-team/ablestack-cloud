// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { mount, flushPromises } from '@vue/test-utils'
import { h, reactive } from 'vue'
import ApiDocs from '@/views/plugins/ApiDocsPlugin.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))
jest.mock('@/layouts/ResourceLayout', () => ({ render: () => null }))
jest.mock('@/components/widgets/TooltipButton', () => ({ render: () => null }))

const fields = [{ name: 'ipaddress', type: 'string', description: 'Allocated IP address' }]
const params = [{ name: 'zoneid', required: true }, { name: 'podid', required: false }]
const reply = (name = 'acquirePodIpAddress', response = fields) => ({ listapisresponse: { api: [{ name, response }] } })
const Harness = { ...ApiDocs, render () { return h('div', this.selectedResponse.map(row => h('span', row.name))) } }

describe('API documentation response fields', () => {
  let wrapper, store
  const open = () => {
    store = reactive({
      getters: { apis: { acquirePodIpAddress: { params: [...params] }, listZones: { params: [] } }, userInfo: { id: 'admin' }, project: { id: 'project' } },
      state: { user: { token: 'session', discoveryGeneration: 1 } }
    })
    wrapper = mount(Harness, { global: { mocks: { $store: store, $t: key => key } } })
  }
  beforeEach(() => { getAPI.mockReset(); open() })
  afterEach(() => wrapper.unmount())

  it('loads and displays response fields when the persisted API cache contains only request parameters', async () => {
    getAPI.mockResolvedValue(reply())
    await wrapper.vm.showApi('acquirePodIpAddress')
    expect(getAPI).toHaveBeenCalledWith('listApis', { name: 'acquirePodIpAddress' }, { preserveOnFailure: true, timeout: 15000 })
    expect(wrapper.find('span').text()).toBe('ipaddress')
    expect(wrapper.vm.loadingResponse).toBe(false)
    expect(store.getters.apis.acquirePodIpAddress.params).toEqual(params)
  })

  it('uses response fields already supplied by discovery without an additional API call', async () => {
    store.getters.apis.acquirePodIpAddress.response = [{}, ...fields]
    await wrapper.vm.showApi('acquirePodIpAddress')
    expect(getAPI).not.toHaveBeenCalled()
    expect(wrapper.vm.selectedResponse).toEqual(fields)
  })

  it('reuses a selected API response schema without writing it to the shared permission cache', async () => {
    getAPI.mockResolvedValue(reply())
    await wrapper.vm.showApi('acquirePodIpAddress')
    await wrapper.vm.showApi('acquirePodIpAddress')
    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(store.getters.apis.acquirePodIpAddress.response).toBeUndefined()
  })

  it('ignores the late response of a previously selected API', async () => {
    let finish
    getAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve })).mockResolvedValueOnce(reply('listZones', [{ name: 'zone' }]))
    const old = wrapper.vm.showApi('acquirePodIpAddress')
    await wrapper.vm.showApi('listZones')
    finish(reply())
    await old
    expect(wrapper.vm.selectedApi).toBe('listZones')
    expect(wrapper.vm.selectedResponse).toEqual([{ name: 'zone' }])
    expect(wrapper.vm.loadingResponse).toBe(false)
  })

  it('reports failed discovery and allows retry instead of treating the failure as an empty schema', async () => {
    getAPI.mockRejectedValueOnce(new Error('Discovery unavailable')).mockResolvedValueOnce(reply())
    await wrapper.vm.showApi('acquirePodIpAddress')
    expect(wrapper.vm.responseError).toBe('Discovery unavailable')
    expect(wrapper.vm.loadingResponse).toBe(false)
    await wrapper.vm.showApi('acquirePodIpAddress')
    expect(wrapper.vm.responseError).toBe('')
    expect(wrapper.vm.selectedResponse).toEqual(fields)
  })

  it('accepts a documented API with no response fields or request parameters', async () => {
    store.getters.apis.listZones = {}
    getAPI.mockResolvedValue(reply('listZones', []))
    await wrapper.vm.showApi('listZones')
    expect(wrapper.vm.selectedParams).toEqual([])
    expect(wrapper.vm.selectedResponse).toEqual([])
    expect(wrapper.vm.responseError).toBe('')
  })

  it.each(['user', 'project', 'session', 'discovery'])('clears cached documentation and ignores pending responses when %s context changes', async context => {
    let finish
    getAPI.mockResolvedValueOnce(reply()).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    await wrapper.vm.showApi('acquirePodIpAddress')
    const pending = wrapper.vm.showApi('listZones')
    if (context === 'user') store.getters.userInfo.id = 'another-user'
    if (context === 'project') store.getters.project.id = 'another-project'
    if (context === 'session') store.state.user.token = 'another-session'
    if (context === 'discovery') store.state.user.discoveryGeneration += 1
    await flushPromises()
    finish(reply('listZones', [{ name: 'old-context' }]))
    await pending
    expect(wrapper.vm.selectedApi).toBe('')
    expect(wrapper.vm.selectedResponse).toEqual([])
    getAPI.mockResolvedValueOnce(reply())
    await wrapper.vm.showApi('acquirePodIpAddress')
    expect(getAPI).toHaveBeenCalledTimes(3)
  })

  it('ignores a response after leaving the documentation page', async () => {
    let finish
    getAPI.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const vm = wrapper.vm
    const pending = vm.showApi('acquirePodIpAddress')
    wrapper.unmount()
    finish(reply())
    await pending
    expect(vm.selectedResponse).toEqual([])
  })
})
