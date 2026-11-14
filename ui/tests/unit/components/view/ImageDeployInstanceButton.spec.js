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

import { reactive } from 'vue'
import { shallowMount, flushPromises } from '@vue/test-utils'
import ImageDeployInstanceButton from '@/components/view/ImageDeployInstanceButton'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const mountImage = (name, resource) => shallowMount(ImageDeployInstanceButton, {
  props: { resource },
  global: { mocks: { $route: { meta: { name } }, $t: key => key, $notifyError: jest.fn(), $router: { push: jest.fn() }, $store: reactive({ getters: { userInfo: { id: 'admin', roletype: 'Admin' }, apis: { deployVirtualMachine: {} }, project: {} }, state: { user: { token: 'session' } } }) } }
})

beforeEach(() => {
  getAPI.mockReset().mockResolvedValue({ listtemplatesresponse: { template: [{ zoneid: 'zone', zonename: 'Zone' }] }, listisosresponse: { iso: [{ zoneid: 'zone' }] }, listzonesresponse: { zone: [{ id: 'zone' }] } })
})

describe('Image deploy readiness', () => {
  it.each([['template', false, false], ['iso', false, true], ['iso', true, false]])('hides unavailable %s images (ready=%s, bootable=%s)', async (name, isready, bootable) => {
    const wrapper = mountImage(name, { id: 'image', isready, bootable })
    expect(wrapper.find('a-dropdown-button').exists()).toBe(false)
    if (!isready) expect(getAPI).not.toHaveBeenCalled()
    await flushPromises()
    wrapper.unmount()
  })
  it.each(['template', 'iso'])('loads zones for a ready bootable %s', async name => {
    const wrapper = mountImage(name, { id: 'image', isready: true, bootable: true })
    await flushPromises()
    expect(wrapper.vm.allowed).toBe(true)
    expect(getAPI).toHaveBeenCalledWith(name === 'iso' ? 'listIsos' : 'listTemplates', expect.objectContaining({ id: 'image' }), ...(name === 'template' ? [expect.any(Object)] : []))
    expect(wrapper.emitted('update-zones')).toBeTruthy()
    await wrapper.setProps({ resource: { id: 'image', isready: false } })
    expect(wrapper.vm.allowed).toBeFalsy()
    wrapper.unmount()
  })
})

test('empty image result never turns an unscoped Zone list into deploy targets', async () => {
  getAPI.mockResolvedValue({ listtemplatesresponse: {} })
  const w = mountImage('template', { id: 'image', isready: true }); await flushPromises()
  expect(w.vm.zones).toEqual([]); expect(getAPI.mock.calls.some(c => c[0] === 'listZones')).toBe(false)
  w.vm.handleMainClick(); expect(w.vm.$router.push).not.toHaveBeenCalled(); w.unmount()
})
test('single Zone main button routes to the actual image; multiple Zones retain explicit choice', async () => {
  const w = mountImage('template', { id: 'image', isready: true, arch: 'x86_64' }); await flushPromises()
  w.vm.handleMainClick(); expect(w.vm.$router.push).toHaveBeenCalledWith({ path: '/action/deployVirtualMachine', query: { zoneid: 'zone', templateid: 'image', arch: 'x86_64' } })
  w.vm.$router.push.mockClear(); w.vm.zones = [{ id: 'zone' }, { id: 'other' }]; w.vm.handleMainClick()
  expect(w.vm.zoneMenuVisible).toBe(true); expect(w.vm.$router.push).not.toHaveBeenCalled()
  w.vm.handleDeployInstanceMenu({ key: 'invalid' }); expect(w.vm.$router.push).not.toHaveBeenCalled(); w.unmount()
})
test('real API withdrawal removes creation controls and clears old Zone results', async () => {
  const w = mountImage('template', { id: 'image', isready: true }); await flushPromises()
  w.vm.$store.getters.apis = {}; await w.vm.fetchResourceData()
  expect(w.vm.allowed).toBe(false); expect(w.vm.zones).toEqual([]); w.vm.handleMainClick(); expect(w.vm.$router.push).not.toHaveBeenCalled(); w.unmount()
})
test('late image reply cannot restore another account or project Zone targets', async () => {
  let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve }))
  const w = mountImage('template', { id: 'image', isready: true })
  w.vm.$store.getters.project = { id: 'new-project' }; done({ listtemplatesresponse: { template: [{ zoneid: 'old' }] } }); await flushPromises()
  expect(w.vm.zones.some(z => z.id === 'old')).toBe(false); expect(getAPI.mock.calls.some(c => c[0] === 'listZones' && c[1].ids === 'old')).toBe(false); w.unmount()
})
