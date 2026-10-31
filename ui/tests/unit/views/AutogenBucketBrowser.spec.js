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

import Autogen from '@/views/AutogenView'
import fs from 'fs'
import path from 'path'
import * as Vue from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { compile as compileTemplate } from 'vue/dist/vue.cjs.js'
import ResourceView from '@/components/view/ResourceView'

jest.mock('@/components/view/InfoCard', () => ({ render: () => null }))

const route = (id, query = {}) => ({ path: `/buckets/${id}`, fullPath: `/buckets/${id}?${new URLSearchParams(query)}`, params: { id }, query, meta: { name: 'buckets' } })
const context = () => ({ resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn(), switchProject: jest.fn(), showAction: false, page: 9 })

test('does not refetch bucket metadata for its own browser path normalization or folder query', () => {
  const vm = context()
  Autogen.watch.$route.call(vm, route('A', { tab: 'browser', browserPath: '' }), route('A', { tab: 'browser' }))
  Autogen.watch.$route.call(vm, route('A', { tab: 'browser', browserPath: 'folder/' }), route('A', { tab: 'browser', browserPath: '' }))
  expect(vm.fetchData).not.toHaveBeenCalled()
})

test('fetches fresh bucket metadata when a browser URL changes bucket id', () => {
  const vm = context()
  Autogen.watch.$route.call(vm, route('B', { tab: 'browser' }), route('A', { tab: 'browser' }))
  expect(vm.resetSelection).toHaveBeenCalledTimes(1)
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
  expect(vm.scheduleAutoRefresh).toHaveBeenCalledTimes(1)
  expect(vm.page).toBe(1)
})

test('preserves normal detail navigation metadata refresh', () => {
  const vm = context()
  Autogen.watch.$route.call(vm, route('A', { tab: 'details' }), route('A', { tab: 'browser' }))
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
})

// Render the real parent binding as well as ResourceView: a browser-only emit
// test cannot detect a dropped refresh event between these two components.
test.each(['buckets', 'vm'])('browser retry reaches metadata refresh only for %s', async (name) => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../../src/views/AutogenView.vue'), 'utf8')
  const markup = source.match(/<resource-view[\s\S]*?\/>/)[0].replace(/v-else-if="[^"]*"/, '')
  const render = compileTemplate(markup)
  const Browser = Vue.markRaw({ props: ['resource'], emits: ['refresh'], render () { return Vue.h('button', { onClick: () => this.$emit('refresh') }, this.resource.credentialstate) } })
  const refresh = jest.fn()
  const wrapper = mount({
    components: { ResourceView },
    render,
    data: () => ({ resource: { id: 'bucket', credentialstate: 'NeedsVerification' }, loading: false, listRequestPending: false, isStorageUi: false, isSnapshotDetail: false, isPolicyDetail: false, isSshKeyPairDetail: false, vmGroupQueryReady: true, actions: [] }),
    methods: { handleDataViewAction () {}, fetchData (params) { refresh(params); this.resource = { ...this.resource, credentialstate: 'Verified' } } }
  }, {
    global: {
      mocks: { $route: { path: '/buckets/bucket', params: { id: 'bucket' }, query: { tab: 'browser' }, meta: { name, tabs: [{ name: 'browser', component: Browser }] } }, $router: { push: jest.fn(), replace: jest.fn() }, $t: key => key, $store: { state: { app: { device: 'desktop' } }, getters: { userInfo: {} } } },
      stubs: { ResourceLayout: { render () { return Vue.h('div', this.$slots.right()) } }, ACard: { render () { return Vue.h('div', this.$slots.default()) } } }
    }
  })
  await flushPromises()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  if (name === 'buckets') {
    expect(refresh).toHaveBeenCalledWith({ irefresh: true })
    expect(wrapper.get('button').text()).toBe('Verified')
  } else expect(refresh).not.toHaveBeenCalled()
  wrapper.unmount()
})
