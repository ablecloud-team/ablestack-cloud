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
import { h, reactive } from 'vue'
import Routes from '@/views/network/StaticRoutesTab'
import VpcTab from '@/views/network/VpcTab'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import ResourceView from '@/components/view/ResourceView'
import Context from '@/components/network/VpcActionContext'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const wrappers = []
const resource = { id: 'vpc-1', name: '긴 한글 VPC 대상', account: 'owner', domainid: 'domain', zoneid: 'zone' }
const slot = { render () { return h('div', this.$slots.default?.()) } }
const form = { setup (_, { expose, slots }) { expose({ validate: () => Promise.resolve(), scrollToField: jest.fn() }); return () => h('form', slots.default?.()) } }
let poll, getters, route, router
beforeEach(() => {
  jest.clearAllMocks()
  poll = jest.fn()
  getters = reactive({ apis: Object.fromEntries(['createStaticRoute', 'listStaticRoutes', 'createTags', 'listTags', 'deleteTags', 'listPrivateGateways', 'listNetworkACLLists', 'createNetworkACLList'].map(api => [api, {}])), userInfo: { id: 'admin', roletype: 'Admin' }, project: {} })
  route = reactive({ path: '/vpc/vpc-1', fullPath: '/vpc/vpc-1?tab=pgw', query: { tab: 'pgw', domainid: 'domain' }, meta: { name: 'vpc' }, params: { id: 'vpc-1' } })
  router = { push: jest.fn(), replace: jest.fn() }
  getAPI.mockImplementation(api => Promise.resolve({ [api.toLowerCase() + 'response']: {} }))
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()) })
async function open (component = Routes) {
  const w = shallowMount(component, { props: { resource }, global: { mocks: { $t: k => k, $store: { getters, state: { user: {}, app: { device: 'desktop' } } }, $route: route, $router: router, $pollJob: poll, $notifyError: jest.fn() }, stubs: { ASpin: slot, ATabs: slot, ATabPane: slot, AForm: form, MoldDialog: slot, MoldDialogActions: slot, RouterLink: true } } })
  wrappers.push(w); await flushPromises(); return w
}
test('route form rejects empty input locally, then sends exactly once while pending', async () => {
  const w = await open(); w.vm.openCreate(); await w.vm.handleAdd(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.routeError).toBe('label.required')
  let resolvePending; postAPI.mockImplementation(() => new Promise(resolve => { resolvePending = resolve }))
  w.vm.newRoute = '172.31.0.0/24'; w.vm.nexthop = '10.1.0.2'; w.vm.handleAdd(); w.vm.handleAdd()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI).toHaveBeenCalledWith('createStaticRoute', { cidr: '172.31.0.0/24', nexthop: '10.1.0.2', vpcid: 'vpc-1' })
  w.vm.closeCreate(); expect(w.vm.createVisible).toBe(true)
  resolvePending({ createstaticrouteresponse: { jobid: 'job' } }); await flushPromises(); expect(w.vm.createVisible).toBe(true)
  poll.mock.calls[0][0].successMethod(); expect(w.vm.createVisible).toBe(false); expect(w.vm.newRoute).toBeNull(); expect(w.vm.nexthop).toBeNull()
})
test('synchronous route error preserves the same modal and both input values for correction', async () => {
  const w = await open(); w.vm.openCreate(); w.vm.newRoute = 'bad'; w.vm.nexthop = '10.1.0.2'
  postAPI.mockRejectedValue({ response: { data: { errorresponse: { errortext: 'invalid CIDR' } } } }); await w.vm.handleAdd()
  expect(w.vm.routeError).toBe('invalid CIDR'); expect(w.vm.newRoute).toBe('bad'); expect(w.vm.nexthop).toBe('10.1.0.2'); expect(w.vm.routeSubmitting).toBe(false); expect(w.vm.createVisible).toBe(true)
})
test('job failure preserves route inputs and an explicit retry posts only the corrected request', async () => {
  const w = await open(); w.vm.openCreate(); w.vm.newRoute = '172.31.0.0/24'; postAPI.mockResolvedValue({ createstaticrouteresponse: { jobid: 'job' } }); await w.vm.handleAdd()
  poll.mock.calls[0][0].errorMethod(); expect(w.vm.createVisible).toBe(true); expect(w.vm.newRoute).toBe('172.31.0.0/24'); expect(w.vm.routeSubmitting).toBe(false)
  w.vm.newRoute = '172.32.0.0/24'; await w.vm.handleAdd(); expect(postAPI).toHaveBeenCalledTimes(2)
})
test('private gateway route keeps gatewayid and never sends vpcid/nexthop', async () => {
  route.path = '/privategw/gateway'; route.fullPath = route.path; route.meta.name = 'privategw'
  const w = await open(); w.vm.newRoute = '172.31.0.0/24'; w.vm.nexthop = 'ignored'; postAPI.mockResolvedValue({ createstaticrouteresponse: { jobid: 'job' } }); await w.vm.handleAdd()
  expect(postAPI).toHaveBeenCalledWith('createStaticRoute', { cidr: '172.31.0.0/24', gatewayid: 'vpc-1' })
})
test('project switch closes route/tag dialogs and ignores a late success for the previous context', async () => {
  const w = await open(); w.vm.openCreate(); w.vm.newRoute = '172.31.0.0/24'; postAPI.mockResolvedValue({ createstaticrouteresponse: { jobid: 'job' } }); await w.vm.handleAdd()
  getters.project = { id: 'different' }; await flushPromises(); expect(w.vm.createVisible).toBe(false); expect(w.vm.tagsModalVisible).toBe(false)
  w.vm.newRoute = 'new context draft'; poll.mock.calls[0][0].successMethod(); expect(w.vm.newRoute).toBe('new context draft')
})
test('route refresh retains loaded data on failure and retry replaces it only on success', async () => {
  getAPI.mockResolvedValue({ liststaticroutesresponse: { staticroute: [{ id: 'route' }] } }); const w = await open()
  getAPI.mockRejectedValue(new Error('503')); await w.vm.fetchData(); expect(w.vm.routes).toEqual([{ id: 'route' }]); expect(w.vm.listRefreshFailed).toBe(true)
  getAPI.mockResolvedValue({ liststaticroutesresponse: {} }); await w.vm.fetchData(); expect(w.vm.routes).toEqual([]); expect(w.vm.listRefreshFailed).toBe(false)
})
test('route and tag keyboard handlers cannot bypass missing API permissions', async () => {
  const w = await open(); w.vm.newRoute = '172.31.0.0/24'; delete getters.apis.createStaticRoute; delete getters.apis.createTags
  await w.vm.handleAdd(); w.vm.handleAddTag(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.tagsLoading).toBe(false)
})
test('tag validation failure releases busy state and retains draft', async () => {
  const w = await open(); w.vm.formRef.value = { validate: () => Promise.reject(Object.assign(new Error('required'), { errorFields: [{ name: ['key'] }] })), scrollToField: jest.fn() }
  w.vm.form.value = 'preserved'; w.vm.handleAddTag(); await flushPromises(); expect(w.vm.tagsLoading).toBe(false); expect(w.vm.form.value).toBe('preserved'); expect(postAPI).not.toHaveBeenCalled()
})
test('late tag lookup cannot replace the selected route after context change', async () => {
  const w = await open(); let resolvePending; getAPI.mockImplementation(() => new Promise(resolve => { resolvePending = resolve })); w.vm.openTagsModal({ id: 'old' }); getters.project = { id: 'new' }; await flushPromises()
  resolvePending({ listtagsresponse: { tag: [{ key: 'old', value: 'old' }] } }); await flushPromises(); expect(w.vm.tags).toEqual([]); expect(w.vm.tagsModalVisible).toBe(false)
})
test('tag submit stays busy until the job finishes and preserves draft after HTTP rejection', async () => {
  const w = await open(); w.vm.selectedRule = { id: 'route' }; w.vm.formRef.value = { validate: () => Promise.resolve() }; Object.assign(w.vm.form, { key: 'purpose', value: 'draft' })
  postAPI.mockResolvedValue({ createtagsresponse: { jobid: 'tag-job' } }); await w.vm.handleAddTag(); await w.vm.handleAddTag()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.tagsLoading).toBe(true)
  poll.mock.calls[0][0].errorMethod(); expect(w.vm.tagsLoading).toBe(false); expect(w.vm.form.value).toBe('draft')
  postAPI.mockRejectedValue({ response: { data: { errorresponse: { errortext: 'permission changed' } } } }); await w.vm.handleAddTag()
  expect(w.vm.tagsLoading).toBe(false); expect(w.vm.tagError).toBe('permission changed'); expect(w.vm.form.key).toBe('purpose')
})
test('late tag mutation completion cannot refresh a different context', async () => {
  const w = await open(); w.vm.selectedRule = { id: 'route' }; postAPI.mockResolvedValue({ deletetagsresponse: { jobid: 'tag-job' } }); await w.vm.handleDeleteTag({ key: 'a', value: 'b' })
  getters.project = { id: 'other' }; await flushPromises(); getAPI.mockClear(); poll.mock.calls[0][0].successMethod(); expect(getAPI).not.toHaveBeenCalled()
})
test('VPC tab change uses the router and preserves non-tab query filters', async () => {
  const w = await open(VpcTab); w.vm.handleChangeTab('acl'); expect(router.push).toHaveBeenCalledWith({ path: '/vpc/vpc-1', query: { tab: 'acl', domainid: 'domain' } })
  route.query.tab = 'pgw'; route.fullPath += '&back=1'; await flushPromises(); expect(w.vm.currentTab).toBe('pgw'); expect(getAPI).toHaveBeenCalledWith('listPrivateGateways', expect.objectContaining({ vpcid: 'vpc-1' }))
})
test('unavailable conditional VPC deep link falls back to details in the URL', async () => {
  route.query.tab = 'bgppeers'; const w = await open(VpcTab); expect(w.vm.currentTab).toBe('details'); expect(router.replace).toHaveBeenCalledWith({ path: '/vpc/vpc-1', query: { tab: 'details', domainid: 'domain' } })
})
test('VPC ACL synchronous result closes once without polling an undefined job', async () => {
  const w = await open(VpcTab); w.vm.handleOpenModals('networkAcl'); await flushPromises(); w.vm.form.name = 'acl'; postAPI.mockResolvedValue({ createnetworkacllistresponse: { id: 'acl' } })
  const first = w.vm.handleNetworkAclFormSubmit(); w.vm.handleNetworkAclFormSubmit(); await first
  expect(postAPI).toHaveBeenCalledTimes(1); expect(poll).not.toHaveBeenCalled(); expect(w.vm.modals.networkAcl).toBe(false); expect(w.vm.aclSubmitting).toBe(false)
})
test('VPC ACL API error preserves name/description and displays the rejection', async () => {
  const w = await open(VpcTab); w.vm.handleOpenModals('networkAcl'); await flushPromises(); Object.assign(w.vm.form, { name: 'acl', description: 'draft' }); postAPI.mockRejectedValue({ response: { data: { errorresponse: { errortext: 'rejected' } } } })
  await w.vm.handleNetworkAclFormSubmit(); expect(w.vm.modals.networkAcl).toBe(true); expect(w.vm.form.name).toBe('acl'); expect(w.vm.form.description).toBe('draft'); expect(w.vm.aclError).toBe('rejected')
})
test.each(['vpcoffering', 'privategw'])('%s shared tabs push a real route entry', name => {
  const context = { conditionalTabNotice: '', activeTab: 'details', wideLayout: true, $route: { path: '/' + name + '/id', query: { other: 'kept' }, meta: { name } }, $router: router, $emit: jest.fn() }
  ResourceView.methods.onTabChange.call(context, 'events'); expect(router.push).toHaveBeenCalledWith({ path: '/' + name + '/id', query: { other: 'kept', tab: 'events' } })
})
test('private gateway breadcrumb returns to its parent VPC gateway tab and updates parent identity', async () => {
  route.meta.name = 'privategw'; route.matched = [{ path: '/', name: 'index', meta: { icon: 'home' } }, { path: '/privategw', name: 'privategw', meta: {} }, { path: '/privategw/:id', meta: {} }]
  const w = shallowMount(Breadcrumb, { props: { resource: { id: 'gateway', vpcid: 'parent' } }, global: { mocks: { $route: route, $t: k => k } } }); wrappers.push(w)
  expect(w.vm.breadList.filter(item => item.vpcParent)).toHaveLength(1); expect(w.vm.breadcrumbLocation(w.vm.breadList.find(item => item.vpcParent))).toEqual({ path: '/vpc/parent', query: { tab: 'pgw' } })
  await w.setProps({ resource: { id: 'gateway2', vpcid: 'parent2' } }); expect(w.vm.breadList.find(item => item.vpcParent).path).toBe('/vpc/parent2')
})
test('dialog context keeps long UUID/name/project ownership visible as text', () => {
  const w = shallowMount(Context, { props: { resources: [{ ...resource, projectid: 'project', project: '프로젝트', domainpath: 'ROOT/자식', name: '<script>long</script>', vpcid: 'parent' }] }, global: { mocks: { $t: k => k }, stubs: { RouterLink: { props: ['to'], render () { return h('a', { href: this.to.path }, this.$slots.default?.()) } } } } }); wrappers.push(w)
  expect(w.text()).toContain('<script>long</script>'); expect(w.text()).toContain('프로젝트'); expect(w.text()).toContain('ROOT/자식'); expect(w.find('script').exists()).toBe(false); expect(w.find('a').attributes('href')).toBe('/vpc/parent')
})
