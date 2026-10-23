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

import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { h } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import LinkedDiskOffering from '@/components/offering/LinkedDiskOffering'
import DetailsTab from '@/components/view/DetailsTab'
import offering from '@/config/section/offering'

jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))
jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: { roletype: 'Admin' } } }))
jest.mock('vue-code-highlight/src/CodeHighlight.vue', () => ({}), { virtual: true })
jest.mock('vue-code-highlight/themes/prism-okaidia.css', () => ({}), { virtual: true })

const disk = { diskofferingid: 'disk-id', diskofferingname: 'same-name' }
const mocks = { $t: key => key, $store: { getters: { apis: { listDiskOfferings: {} } } } }
const link = { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot /></a>' }
function cell (resource, connection = false, apis = { listDiskOfferings: {} }) {
  return mount(LinkedDiskOffering, { props: { resource, connection }, global: { mocks: { ...mocks, $store: { getters: { apis } } }, stubs: { 'router-link': link } } })
}

describe('linked disk offering contract', () => {
  it.each([true, 'true'])('keeps compute-only %s unlinked even when names differ', type => {
    const w = cell({ ...disk, name: 'different-compute', diskofferingcomputeonly: type })
    expect(w.find('a').exists()).toBe(false)
    expect(w.text()).toBe(disk.diskofferingname)
    w.unmount()
  })
  it.each([false, 'false'])('links known general %s even when names are equal', type => {
    const w = cell({ ...disk, name: disk.diskofferingname, diskofferingcomputeonly: type })
    expect(JSON.parse(w.find('a').attributes('data-target'))).toEqual({ path: '/diskoffering/disk-id' })
    w.unmount()
  })
  it.each([undefined, null, 0, 1, 'unknown'])('unknown type %s never claims a general link', type => {
    const w = cell({ ...disk, diskofferingcomputeonly: type }, true)
    expect(w.text()).toContain('label.allocation.unknown')
    expect(w.text()).toContain('message.offering.disk.connection.unknown')
    expect(w.find('a').exists()).toBe(false)
    w.unmount()
  })
  it('keeps a general offering as text when its API is unavailable', () => {
    const w = cell({ ...disk, diskofferingcomputeonly: false }, false, {})
    expect(w.find('a').exists()).toBe(false)
    expect(w.text()).toBe(disk.diskofferingname)
    w.unmount()
  })
  it('responds to refreshed resource and permission data without keeping the old link', async () => {
    const w = cell({ ...disk, diskofferingcomputeonly: false })
    await w.setProps({ resource: { ...disk, diskofferingcomputeonly: true } })
    expect(w.find('a').exists()).toBe(false)
    await w.setProps({ resource: { ...disk } })
    expect(w.find('span[title]').attributes('title')).toBe('message.offering.disk.connection.unknown')
    w.unmount()
  })
  it('keeps the linked column title bound to the API name field', () => {
    const config = offering.children.find(row => row.name === 'computeoffering')
    expect(config.columns).toContainEqual({ customTitle: 'linked.disk.offering', field: 'diskofferingname' })
  })
  it.each([true, false, undefined])('uses the shared contract in the vertical detail rows for %s', async type => {
    const record = { ...disk, ...(type === undefined ? {} : { diskofferingcomputeonly: type }) }
    const w = shallowMount(DetailsTab, {
      props: { resource: record },
      global: {
        mocks: { ...mocks, $route: { path: '/computeoffering/id', meta: { name: 'computeoffering', details: ['diskofferingname', 'diskofferingcomputeonly'] } } },
        stubs: {
          LinkedDiskOffering: false,
          'router-link': link,
          'a-list': { props: ['dataSource'], render () { return h('ul', this.dataSource.map(item => this.$slots.renderItem({ item }))) } },
          'a-list-item': { template: '<li><slot /></li>' }
        }
      }
    })
    await flushPromises()
    expect(w.findAllComponents(LinkedDiskOffering)).toHaveLength(2)
    expect(w.findAll('strong').slice(0, 2).map(x => x.text())).toEqual(['label.diskofferingname', 'label.diskofferingcomputeonly'])
    expect(w.text()).toContain(type === true ? 'label.compute.offering.dedicated.auto.created' : type === false ? 'label.general.disk.offering.link' : 'label.allocation.unknown')
    expect(w.find('a').exists()).toBe(type === false)
    w.unmount()
  })
  it('navigates a general link with the real router and returns to the filtered list', async () => {
    const empty = { render: () => h('div') }
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/computeoffering', component: empty }, { path: '/diskoffering/:id', component: empty }] })
    await router.push('/computeoffering?keyword=fixture&filter=active')
    const w = mount(LinkedDiskOffering, { props: { resource: { ...disk, diskofferingcomputeonly: false } }, global: { mocks, plugins: [router] } })
    await w.get('a').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/diskoffering/disk-id')
    router.back(); await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/computeoffering?keyword=fixture&filter=active')
    w.unmount()
  })
})
