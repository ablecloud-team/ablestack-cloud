// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import ResourceView from '@/components/view/ResourceView'
import AutogenView from '@/views/AutogenView'
import context from '@/utils/storageDialogContext'
import { volumeVmPath } from '@/utils/storageUi'
import Related from '@/views/storage/StorageRelatedResources'
import { getAPI } from '@/api'
import ActionButton from '@/components/view/ActionButton'
import { Alert, Button } from 'ant-design-vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))

const shell = { props: ['closable', 'keyboard'], template: '<section><header><slot name="title" /></header><main><slot /></main><footer><slot name="footer" /></footer></section>' }
afterEach(() => { document.body.innerHTML = '' })

test('one fixed footer retains form values and handlers, locks dismissal while busy and falls back outside a dialog', async () => {
  const busy = ref(false); const name = ref('kept'); const submit = jest.fn()
  const wrapper = mount({ components: { MoldDialog, MoldDialogActions }, setup: () => ({ busy, name, submit }), template: '<MoldDialog size="lg" form-actions><input v-model="name" /><MoldDialogActions :busy="busy"><button @click="submit">Submit</button></MoldDialogActions></MoldDialog>' }, { attachTo: document.body, global: { mocks: { $t: k => k }, stubs: { 'a-modal': shell } } })
  await flushPromises()
  expect(wrapper.find('main button').exists()).toBe(false)
  expect(wrapper.findAll('footer button')).toHaveLength(1)
  await wrapper.find('footer button').trigger('click'); expect(submit).toHaveBeenCalledTimes(1)
  busy.value = true; await flushPromises()
  expect(wrapper.find('footer fieldset').attributes('disabled')).toBeDefined()
  expect(wrapper.findComponent(shell).props()).toMatchObject({ keyboard: false, closable: false })
  expect(wrapper.find('main input').element.value).toBe('kept')
  busy.value = false; await flushPromises(); expect(wrapper.findComponent(shell).props('closable')).toBe(true)
  wrapper.unmount()
  const fallback = mount(MoldDialogActions, { slots: { default: '<button>Local</button>' } })
  expect(fallback.find('button').text()).toBe('Local'); fallback.unmount()
})

test.each([undefined, null, '', 'undefined', 'null'])('missing VM identity %s produces text rather than a broken navigation', id => {
  const router = { resolve: jest.fn() }
  expect(volumeVmPath({ virtualmachineid: id }, router)).toBeNull()
  expect(router.resolve).not.toHaveBeenCalled()
})
test.each(['Expunging', 'Expunged'])('removed VM state %s has no link', vmstate => {
  expect(volumeVmPath({ virtualmachineid: 'vm-id', vmstate }, { resolve: jest.fn() })).toBeNull()
})
test('valid VM relation respects route permissions and VM type', () => {
  const router = { resolve: jest.fn(() => ({ matched: [{ redirect: '/exception/404' }] })) }
  expect(volumeVmPath({ virtualmachineid: 'vm-id' }, router)).toBeNull()
  router.resolve.mockReturnValue({ matched: [{}] })
  expect(volumeVmPath({ virtualmachineid: 'vm-id' }, router)).toBe('/vm/vm-id')
  expect(volumeVmPath({ virtualmachineid: 'router-id', vmtype: 'DomainRouter' }, router)).toBe('/router/router-id')
})

test.each(['volume', 'computeoffering', 'diskoffering'])('%s tab history and invalid-tab fallback retain the resource and route filters', async name => {
  const component = { render: () => null }
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/' + name + '/:id', component, meta: { name } }, { path: '/vm/:id', component }] })
  await router.push('/' + name + '/target?keyword=kept')
  const vm = { $route: router.currentRoute.value, $router: router, $emit: jest.fn() }
  ResourceView.methods.onTabChange.call(vm, 'events'); await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ keyword: 'kept', tab: 'events' })
  const watchVm = { resetSelection: jest.fn(), fetchData: jest.fn() }
  AutogenView.watch.$route.call(watchVm, router.currentRoute.value, vm.$route)
  expect(watchVm.resetSelection).not.toHaveBeenCalled(); expect(watchVm.fetchData).not.toHaveBeenCalled()
  await router.push('/vm/other'); router.back(); await flushPromises()
  expect(router.currentRoute.value.query.tab).toBe('events')
  const tabs = [{ name: 'details' }, { name: 'events', hidden: true }]
  const detail = { tabs, historyTab: '', showTab: tab => !tab.hidden, $route: { ...router.currentRoute.value, query: { tab: 'events' }, meta: { name, tabs } }, $router: router }
  ResourceView.methods.setActiveTab.call(detail); await flushPromises()
  expect(detail.activeTab).toBe('details'); expect(router.currentRoute.value.query.tab).toBe('details')
})

test.each(['path', 'project', 'user', 'resource'])('late validation is invalid after %s context change or form destruction', key => {
  const vm = { $route: { path: '/volume' }, $store: { getters: { project: { id: 'project' }, userInfo: { id: 'user', roletype: 'Admin' } } }, resource: { id: 'source' }, ...context.methods }
  context.created.call(vm); expect(vm.dialogCurrent()).toBe(true)
  if (key === 'path') vm.$route.path = '/diskoffering'
  if (key === 'project') vm.$store.getters.project.id = 'other'
  if (key === 'user') vm.$store.getters.userInfo.id = 'other'
  if (key === 'resource') vm.resource.id = 'other'
  expect(vm.dialogCurrent()).toBe(false)
  context.created.call(vm); context.beforeUnmount.call(vm); expect(vm.dialogCurrent()).toBe(false)
})

function related () {
  return { ...Related.methods, active: true, disposed: false, loading: false, pending: false, canRead: true, resource: { id: 'offering' }, version: 0, queryScope: 'same-owner-page', definition: { api: 'listVolumes', response: 'listvolumesresponse', rows: 'volume', param: 'diskofferingid' }, page: 2, pagesize: 10, keyword: 'kept', rows: [{ id: 'existing' }], count: 12, loadedScope: 'same-owner-page' }
}
test('related resource read retains the current page on transient failure and replaces it on successful empty response', async () => {
  const vm = related()
  getAPI.mockRejectedValueOnce(new Error('503')); await vm.fetchData()
  expect(getAPI).toHaveBeenLastCalledWith('listVolumes', { diskofferingid: 'offering', listall: true, page: 2, pagesize: 10, keyword: 'kept' }, { preserveOnFailure: true, timeout: 20000 })
  expect(vm.rows).toEqual([{ id: 'existing' }]); expect(vm.count).toBe(12); expect(vm.error).toBe(true)
  vm.page = 1; getAPI.mockResolvedValueOnce({ listvolumesresponse: { count: 0 } }); await vm.fetchData()
  expect(vm.rows).toEqual([]); expect(vm.count).toBe(0); expect(vm.error).toBe(false)
})
test('an old owner response cannot reintroduce private rows after route/context changes', async () => {
  const vm = related(); let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
  const pending = vm.fetchData(); vm.queryScope = 'other-owner'; vm.reset()
  release({ listvolumesresponse: { count: 1, volume: [{ id: 'private-old' }] } }); await pending
  expect(vm.rows).toEqual([]); expect(vm.count).toBe(0)
})
test.each([401, 403, 431, 531])('related resources clear inaccessible rows after permission failure %s', async status => {
  const vm = related(); getAPI.mockRejectedValueOnce({ response: { status } }); await vm.fetchData()
  expect(vm.rows).toEqual([]); expect(vm.count).toBe(0); expect(vm.error).toBe(true)
})
test('out of range related page returns to the last page without publishing an empty stale page', async () => {
  const vm = related(); vm.page = 3; vm.changePage = jest.fn()
  getAPI.mockResolvedValueOnce({ listvolumesresponse: { count: 12, volume: [] } }); await vm.fetchData()
  expect(vm.changePage).toHaveBeenCalledWith(2, 10); expect(vm.rows).toEqual([{ id: 'existing' }])
})

test('refresh retains action entries and bulk selection while disabling mutations until data is current', () => {
  const action = { api: 'resizeVolume', dataView: true }
  const vm = { isStorageUi: true, storageActionReady: false, resource: { id: 'volume' }, actions: [action], dataView: true, $route: { meta: { name: 'volume' } }, $store: { getters: { apis: { resizeVolume: {} } } } }
  expect(AutogenView.computed.visibleDataViewActions.call(vm)).toEqual([action])
  expect(ActionButton.methods.isActionDisabled.call({ ...vm, resourceActionReady: () => false }, action)).toBe(true)
  expect(ActionButton.methods.isActionDisabled.call({ ...vm, dataView: false, resourceActionReady: () => false }, { api: 'deleteVolume', groupAction: true })).toBe(true)
  expect(ActionButton.methods.isActionDisabled.call({ ...vm, dataView: false, resourceActionReady: () => false }, { api: 'createVolume', listView: true })).toBe(false)
  expect(ActionButton.methods.isActionDisabled.call({ ...vm, resourceActionReady: () => true }, action)).toBe(false)
  vm.actionTrigger = 'kept'; AutogenView.methods.execAction.call(vm, action, false)
  expect(vm.actionTrigger).toBe('kept')
})

test('the installed Ant Design Alert renders a retry button that recovers a failed related read', async () => {
  getAPI.mockRejectedValueOnce(new Error('Network Error'))
  const wrapper = mount(Related, { props: { resource: { id: 'volume' }, tab: 'snapshots' }, global: { components: { 'a-alert': Alert, 'a-button': Button }, stubs: { ListView: true, AInputSearch: true, APagination: true }, mocks: { $t: key => key, $route: { path: '/volume/volume', meta: { name: 'volume' }, query: {} }, $store: { getters: { apis: { listSnapshots: {} }, userInfo: { id: 'user' } }, state: { user: { token: 'same-session' } } } } } })
  await flushPromises()
  expect(wrapper.find('.ant-alert').exists()).toBe(true)
  expect(wrapper.find('.ant-alert button').text()).toBe('label.retry')
  getAPI.mockResolvedValueOnce({ listsnapshotsresponse: { count: 1, snapshot: [{ id: 'snapshot', name: 'manual', intervaltype: 'MANUAL' }] } })
  await wrapper.find('.ant-alert button').trigger('click'); await flushPromises()
  expect(wrapper.vm.rows.map(row => row.id)).toEqual(['snapshot'])
  expect(wrapper.find('.ant-alert').exists()).toBe(false)
  wrapper.unmount()
})
