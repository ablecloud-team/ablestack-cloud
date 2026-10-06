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

import { shallowMount } from '@vue/test-utils'
import { h } from 'vue'
import ListView from '@/components/view/ListView'

jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))
jest.mock('vue-code-highlight/src/CodeHighlight.vue', () => ({}), { virtual: true })
jest.mock('vue-code-highlight/themes/prism-okaidia.css', () => ({}), { virtual: true })

function diskOfferingCell (record) {
  return shallowMount(ListView, {
    props: { columns: [{ key: 'diskofferingname', dataIndex: 'diskofferingname' }], items: [record] },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: { $route: { path: '/computeoffering', meta: {} }, $store: { getters: { apis: {}, userInfo: { roletype: 'Admin' } } }, $t: key => key },
      stubs: {
        'a-table': { render () { return h('div', this.$slots.bodyCell({ column: { key: 'diskofferingname' }, text: record.diskofferingname, record })) } },
        'router-link': { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot /></a>' }
      }
    }
  })
}

describe('Linked disk offering navigation', () => {
  it('renders an internal compute-only disk offering as plain text', () => {
    const wrapper = diskOfferingCell({
      diskofferingid: 'internal-id',
      diskofferingname: '4C-8GB-GFS-HA',
      diskofferingcomputeonly: true
    })

    expect(wrapper.text()).toContain('4C-8GB-GFS-HA')
    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })

  it('retains navigation for a regular disk offering', () => {
    const wrapper = diskOfferingCell({
      diskofferingid: 'regular-id',
      diskofferingname: 'custom-gfs',
      diskofferingcomputeonly: false
    })

    expect(JSON.parse(wrapper.find('a').attributes('data-target'))).toEqual({ path: '/diskoffering/regular-id' })
    wrapper.unmount()
  })
})
