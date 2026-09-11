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

import { shallowMount, flushPromises } from '@vue/test-utils'
import ImageDeployInstanceButton from '@/components/view/ImageDeployInstanceButton'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const mountImage = (name, resource) => shallowMount(ImageDeployInstanceButton, {
  props: { resource },
  global: { mocks: { $route: { meta: { name } }, $t: key => key, $notifyError: jest.fn() } }
})

describe('Image deploy readiness', () => {
  beforeEach(() => {
    getAPI.mockReset().mockResolvedValue({ listtemplatesresponse: { template: [{ zoneid: 'zone', zonename: 'Zone' }] }, listisosresponse: { iso: [{ zoneid: 'zone' }] }, listzonesresponse: { zone: [{ id: 'zone' }] } })
  })
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
    expect(getAPI).toHaveBeenCalledWith(name === 'iso' ? 'listIsos' : 'listTemplates', expect.objectContaining({ id: 'image' }))
    expect(wrapper.emitted('update-zones')).toBeTruthy()
    await wrapper.setProps({ resource: { id: 'image', isready: false } })
    expect(wrapper.vm.allowed).toBeFalsy()
    wrapper.unmount()
  })
})
