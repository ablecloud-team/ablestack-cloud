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
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { compile as compileTemplate } from 'vue/dist/vue.cjs.js'
import ResourceView from '@/components/view/ResourceView'
import BulkActionProgress from '@/components/view/BulkActionProgress'

jest.mock('@/components/view/InfoCard', () => ({ render: () => null }))

const route = (id, query = {}) => ({ path: `/buckets/${id}`, fullPath: `/buckets/${id}?${new URLSearchParams(query)}`, params: { id }, query, meta: { name: 'buckets' } })
const context = () => ({ resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn(), switchProject: jest.fn(), showAction: false, page: 9 })

test('does not refetch bucket metadata for its own browser path normalization or folder query', () => {
  const vm = context()
  Autogen.watch.$route.handler.call(vm, route('A', { tab: 'browser', browserPath: '' }), route('A', { tab: 'browser' }))
  Autogen.watch.$route.handler.call(vm, route('A', { tab: 'browser', browserPath: 'folder/' }), route('A', { tab: 'browser', browserPath: '' }))
  expect(vm.fetchData).not.toHaveBeenCalled()
})

test('fetches fresh bucket metadata when a browser URL changes bucket id', () => {
  const vm = context()
  Autogen.watch.$route.handler.call(vm, route('B', { tab: 'browser' }), route('A', { tab: 'browser' }))
  expect(vm.resetSelection).toHaveBeenCalledTimes(1)
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
  expect(vm.scheduleAutoRefresh).toHaveBeenCalledTimes(1)
  expect(vm.page).toBe(1)
})

test('preserves normal detail navigation metadata refresh', () => {
  const vm = context()
  Autogen.watch.$route.handler.call(vm, route('A', { tab: 'details' }), route('A', { tab: 'browser' }))
  expect(vm.fetchData).toHaveBeenCalledTimes(1)
})

// Render the real parent binding as well as ResourceView: a browser-only emit
// test cannot detect a dropped refresh event between these two components.
test.each(['buckets', 'objectstore', 'vm'])('browser retry reaches metadata refresh only for %s', async (name) => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../../src/views/AutogenView.vue'), 'utf8')
  const markup = source.match(/<resource-view[\s\S]*?\/>/)[0].replace(/v-else-if="[^"]*"/, '')
  const render = compileTemplate(markup)
  const Browser = Vue.markRaw({ props: ['resource'], emits: ['refresh'], render () { return Vue.h('button', { onClick: () => this.$emit('refresh') }, this.resource.credentialstate) } })
  const refresh = jest.fn()
  const wrapper = mount({
    components: { ResourceView },
    render,
    data: () => ({ resource: { id: 'bucket', credentialstate: 'NeedsVerification' }, loading: false, listRequestPending: false, isStorageUi: false, isObjectStorageUi: false, isSnapshotDetail: false, isPolicyDetail: false, isSshKeyPairDetail: false, vmGroupQueryReady: true, actions: [] }),
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
  if (['buckets', 'objectstore'].includes(name)) {
    expect(refresh).toHaveBeenCalledWith({ irefresh: true })
    expect(wrapper.get('button').text()).toBe('Verified')
  } else expect(refresh).not.toHaveBeenCalled()
  wrapper.unmount()
})

test('mixed selected buckets expose the explicitly named empty-only group deletion', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../../src/config/section/storage.js'), 'utf8')
  // Exercise the real action configuration through the actual shared visibility logic.
  const block = source.slice(source.indexOf("api: 'deleteBucket'"))
  const action = {
    api: 'deleteBucket',
    toolbarLabel: block.match(/toolbarLabel: '([^']+)'/)[1],
    groupAction: /groupAction: true/.test(block.split('groupMap:')[0])
  }
  const ctx = {
    actions: [action],
    dataView: false,
    selectedRowKeys: ['empty', 'nonempty'],
    selectedItems: [{ id: 'empty' }, { id: 'nonempty' }],
    $store: { getters: { apis: { deleteBucket: {} } } }
  }
  expect(Autogen.computed.visibleListActions.call(ctx)).toEqual([action])
  expect(action.toolbarLabel).toBe('label.bucket.delete.empty')
})

describe.each([['progress', BulkActionProgress]])('bucket bulk %s dialog', (_, component) => {
  const Modal = { name: 'BucketModalProbe', props: ['width', 'wrapClassName'], render () { return Vue.h('section', this.$slots.default?.()) } }
  const Table = { name: 'BucketTableProbe', props: ['scroll'], render () { return Vue.h('div') } }
  const open = routePath => shallowMount(component, {
    props: { showConfirmationAction: true, showGroupActionModal: true, action: 'deleteBucket', message: { title: 'label.bucket.delete', confirmMessage: 'message.bucket.delete' }, selectedItems: [{ id: 'fixture', name: 'fixture', status: 'failed' }], selectedRowKeys: ['fixture'], selectedColumns: [] },
    global: { mocks: { $route: { path: routePath, meta: routePath === '/buckets' ? {} : { name: 'buckets' } }, $t: key => key, $config: {} }, provide: { parentFetchData: jest.fn(() => Promise.resolve()) }, stubs: { 'a-modal': Modal, 'a-table': Table } }
  })
  test('uses responsive VM dialog styling for the actual bucket path even without meta.name', () => {
    const view = open('/buckets')
    expect(view.findComponent(Modal).props('width')).toBe('min(720px, calc(100vw - 32px))')
    expect(view.findComponent(Modal).attributes('class')).toContain('object-storage-dialog')
    expect(view.findComponent(Table).props('scroll')).toEqual({ x: 760 })
    view.unmount()
  })
  test('preserves the other menu presentation regardless of meta.name', () => {
    const view = open('/vm')
    expect(view.findComponent(Modal).props('width')).toBe('50vw')
    expect(view.findComponent(Modal).attributes('class') || '').not.toContain('object-storage-dialog')
    expect(view.findComponent(Table).props('scroll')).toBeUndefined()
    view.unmount()
  })
})

describe('actual Autogen bucket group confirmation', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../../src/views/AutogenView.vue'), 'utf8')
  const marker = source.search(/:width="[^"]*isBucketGroupDelete/)
  const start = source.lastIndexOf('<', marker)
  const readOpening = offset => source.slice(offset).match(/^<(?:[^"'<>]|"[^"]*"|'[^']*')*>/)[0]
  const opening = readOpening(start).replace(/\s+v-else(?=\s|>)/, '')
  const end = start + readOpening(start).length
  const tableStart = source.indexOf('<a-table', end)
  const table = readOpening(tableStart)
  const closing = '</' + opening.match(/^<([A-Za-z][\w-]*)/)[1] + '>'
  const Modal = { name: 'ActualGenericDialogProbe', props: ['width', 'size'], render () { return Vue.h('section', this.$slots.default?.()) } }
  const Table = { name: 'ActualGenericTableProbe', props: ['scroll'], render () { return Vue.h('div') } }
  const Host = {
    data: () => ({ isStorageUi: false, isGuestNetworkDelete: false, currentAction: { api: 'deleteBucket', invokedAsGroupAction: true }, showAction: true, modalWidth: '50vw', actionLoading: false, selectedRowKeys: ['fixture'], selectedItems: [{ id: 'fixture' }], chosenColumns: [] }),
    computed: { isBucketGroupDelete: Autogen.computed.isBucketGroupDelete },
    methods: { getOkProps: () => ({}), getCancelProps: () => ({}), cancelAction () {} },
    render: compileTemplate(opening + table + '</a-table>' + closing)
  }
  test.each(['/buckets', '/vm'])('actual generic popup bindings follow %s scope', routePath => {
    const view = mount(Host, { global: { mocks: { $route: { path: routePath, meta: {} } }, stubs: { MoldDialog: Modal, 'a-modal': Modal, 'a-table': Table } } })
    const bucket = routePath === '/buckets'
    expect(view.findComponent(Modal).props('width')).toBe(bucket ? 'min(720px, calc(100vw - 32px))' : '50vw')
    expect(view.findComponent(Modal).props('size')).toBe(bucket ? 'md' : undefined)
    expect(view.findComponent(Modal).attributes('class') || '').toContain(bucket ? 'object-storage-dialog' : 'mold-dialog')
    expect(view.findComponent(Table).props('scroll')).toEqual(bucket ? { x: 760 } : undefined)
    view.unmount()
  })
})

test('bucket list scope invalidates a late response after role, session or project changes', () => {
  const ctx = { isObjectStorageUi: true, $route: { path: '/buckets', fullPath: '/buckets?objectstorageid=A', params: {}, query: { objectstorageid: 'A' }, meta: { name: 'buckets' } }, $store: { state: { user: { token: 'first' } }, getters: { userInfo: { id: 'same-user', roleid: 'admin-role', roletype: 'Admin' }, project: {}, metrics: false, listAllProjects: false } } }
  const first = Autogen.methods.listScope.call(ctx)
  ctx.$store.getters.userInfo.roletype = 'User'; expect(Autogen.methods.listScope.call(ctx)).not.toBe(first)
  ctx.$store.getters.userInfo.roletype = 'Admin'; ctx.$store.state.user.token = 'second'; expect(Autogen.methods.listScope.call(ctx)).not.toBe(first)
  ctx.$store.state.user.token = 'first'; ctx.$store.getters.project.id = 'project'; expect(Autogen.methods.listScope.call(ctx)).not.toBe(first)
})

test('detail actions stay qualified through tab and S3 folder navigation but invalidate real targets and scope', () => {
  const ctx = { isObjectStorageUi: true, $route: route('A', { tab: 'browser' }), $store: { state: { user: { token: 'session' } }, getters: { userInfo: { id: 'admin', roleid: 'root', roletype: 'Admin' }, project: {} } } }
  const scope = () => Autogen.methods.listScope.call(ctx)
  const initial = scope()
  ctx.$route = route('A', { tab: 'browser', browserPath: 'folder/' }); expect(scope()).toBe(initial)
  ctx.$route = route('A', { tab: 'details', browserPath: '' }); expect(scope()).toBe(initial)
  expect(Autogen.computed.storageActionReady.call({ listRequestPending: false, listRefreshError: false, listLoadedScope: initial, listScope: scope })).toBe(true)
  ctx.$route = route('B', { tab: 'browser' }); expect(scope()).not.toBe(initial)
  ctx.$route = route('A', { account: 'other' }); expect(scope()).not.toBe(initial)
  ctx.$route = route('A'); ctx.$store.getters.userInfo.roleid = 'restricted'; expect(scope()).not.toBe(initial)
})
