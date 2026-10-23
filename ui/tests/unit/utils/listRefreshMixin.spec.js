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

import { mount, flushPromises } from '@vue/test-utils'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

describe('explicit list adapters', () => {
  let wrapper
  let calls
  const component = {
    mixins: [listRefreshMixin(['fetchRows'])],
    template: '<div><input v-model="draft"><span v-for="row in rows" :key="row.id">{{ row.name }}</span></div>',
    data: () => ({ page: 1, rows: [], loading: false, draft: '' }),
    methods: {
      fetchRows () {
        const request = this.listRequestToken('fetchRows')
        this.loading = !request.loaded
        return new Promise(resolve => calls.push(resolve)).then(rows => {
          if (!this.isListRequestCurrent('fetchRows', request)) return
          this.rows = rows
        }).finally(() => {
          if (this.isListRequestCurrent('fetchRows', request)) this.loading = false
        })
      }
    }
  }
  beforeEach(() => {
    calls = []
    wrapper = mount(component, { global: { mocks: { $route: { fullPath: '/list' }, $store: { getters: {} } } } })
  })
  afterEach(() => wrapper.unmount())
  it('retains existing rows, nodes and draft without setting loading during refresh', async () => {
    wrapper.vm.fetchRows(); calls[0]([{ id: 'a', name: 'old' }]); await flushPromises()
    const element = wrapper.find('span').element
    await wrapper.find('input').setValue('unsaved')
    wrapper.vm.fetchRows()
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.find('span').text()).toBe('old')
    calls[1]([{ id: 'a', name: 'new' }]); await flushPromises()
    expect(wrapper.find('span').element).toBe(element)
    expect(wrapper.find('span').text()).toBe('new')
    expect(wrapper.vm.draft).toBe('unsaved')
    expect(wrapper.vm.listRefreshing).toBe(0)
  })
  it('coalesces identical requests and rejects stale pages', async () => {
    const first = wrapper.vm.fetchRows()
    expect(wrapper.vm.fetchRows()).toBe(first)
    expect(calls).toHaveLength(1)
    await wrapper.setData({ page: 2 })
    wrapper.vm.fetchRows()
    calls[1]([{ id: 'b', name: 'page two' }]); await flushPromises()
    calls[0]([{ id: 'a', name: 'page one' }]); await flushPromises()
    expect(wrapper.vm.rows[0].id).toBe('b')
  })
  it('does not apply responses after unmount', async () => {
    const vm = wrapper.vm
    vm.fetchRows()
    wrapper.unmount()
    calls[0]([{ id: 'a', name: 'old' }]); await flushPromises()
    expect(vm.rows).toEqual([])
  })
  it('keeps the full URL as the default scope for adapters without an override', async () => {
    wrapper.vm.fetchRows()
    wrapper.vm.$route.fullPath = '/list?filter=other'
    calls[0]([{ id: 'old', name: 'old filter' }]); await flushPromises()
    expect(wrapper.vm.rows).toEqual([])
  })
})
