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
import image from '@/config/section/image'
import store from '@/store'
import DetailsTab from '@/components/view/DetailsTab'
import InfoCard from '@/components/view/InfoCard'
import ResourceView from '@/components/view/ResourceView'
import Value from '@/components/widgets/TemplateDetailValue'
import Catalog from '@/views/tools/RuntimeBundleCatalogSummary'
import { getAPI } from '@/api'
import { presentedTemplateValue } from '@/utils/templateDetails'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: { id: 'admin', roletype: 'Admin' }, apis: { listStorageServiceRuntimeBundles: {} } } }))
const config = image.children.find(x => x.name === 'template')
const runtimeTab = config.tabs.find(x => x.name === 'runtime.catalog')
const t = k => ({ 'label.yes': '예', 'label.no': '아니오', 'label.unknown': '확인 필요', 'label.copy': '복사' })[k] || k
beforeEach(() => {
  jest.clearAllMocks()
  store.getters.userInfo = { id: 'admin', roletype: 'Admin' }
  store.getters.apis = { listStorageServiceRuntimeBundles: {} }
  getAPI.mockResolvedValue({ liststorageserviceruntimebundlesresponse: { count: 1, storageserviceruntimebundle: [{ id: 'bundle', version: '1' }] } })
})
test.each(['Admin', 'DomainAdmin', 'User'])('detail fields have a single type and preserve boolean fields for %s', role => {
  store.getters.userInfo.roletype = role
  const fields = config.details()
  expect(fields.filter(x => x === 'templatetype')).toHaveLength(1)
  expect(new Set(fields).size).toBe(fields.length)
  expect(fields).toEqual(expect.arrayContaining(['format', 'sshkeyenabled', 'requireshvm', 'created', 'crossZones']))
})
test.each(['USER', 'VNF', 'SYSTEM', undefined])('runtime tab does not infer Storage Service purpose from %s, names or tags', type => {
  const resource = { templatetype: type, name: 'SharedFS-runtime', templatetag: 'SHAREDFS', profileid: 'known-looking-profile' }
  expect(runtimeTab.show(resource)).toBe(false)
  expect(InfoCard.computed.templateRuntimeToolVisible.call({ resource, $store: store })).toBe(true)
})
test('SHAREDFS runtime is visible only with the root admin API capability', () => {
  const resource = { templatetype: 'SHAREDFS' }
  expect(runtimeTab.show(resource)).toBe(true)
  expect(InfoCard.computed.templateRuntimeToolVisible.call({ resource, $store: store })).toBe(false)
  store.getters.apis = {}; expect(runtimeTab.show(resource)).toBe(false)
  store.getters.apis.listStorageServiceRuntimeBundles = {}; store.getters.userInfo.roletype = 'User'
  expect(runtimeTab.show(resource)).toBe(false)
})
test.each([[true, '예'], [false, '아니오'], ['true', '예'], ['false', '아니오'], [undefined, '확인 필요'], ['UNKNOWN', 'UNKNOWN']])('boolean %s retains its precise meaning', (value, expected) => {
  const w = shallowMount(Value, { props: { field: 'sshkeyenabled', value, label: 'SSH' }, global: { mocks: { $t: t } } })
  expect(w.text()).toBe(expected); w.unmount()
})
test.each(['id', 'checksum', 'url'])('full %s text and exact copy payload survive long values', field => {
  const value = field === 'url' ? 'https://example.test/' + 'a'.repeat(180) : 'abcdef0123456789'.repeat(8)
  const w = shallowMount(Value, { props: { field, value, label: field }, global: { mocks: { $t: t, $message: { success: jest.fn() } } } })
  expect(w.find('.template-detail-value__text').text()).toBe(value)
  expect(w.findComponent({ name: 'TooltipButton' }).props('copyResource')).toBe(value)
  expect(w.findComponent({ name: 'TooltipButton' }).attributes('aria-label')).toBe('복사 ' + field)
  w.unmount()
})
test('the API crossZones field keeps false visible and localized', () => {
  const w = shallowMount(Value, { props: { field: 'crossZones', value: false, label: '크로스 존' }, global: { mocks: { $t: t } } })
  expect(w.text()).toBe('아니오'); w.unmount()
})
test('boolean-looking names are not relabeled and dates keep the locale renderer', () => {
  expect(presentedTemplateValue('name')).toBe(false); expect(presentedTemplateValue('created')).toBe(false)
  expect(presentedTemplateValue('isready')).toBe(true)
})
test.each(['vm', 'iso', 'volume', 'template'])('%s keeps its shared detail field contract', name => {
  const route = { path: '/' + name, meta: { name, details: ['name', 'id', 'created', 'passwordenabled'] } }
  const ctx = { $route: route, detailsTitles: {}, projectname: '', l2Network: false, sharedScope: null }
  expect(DetailsTab.methods.fetchDetails.call(ctx)).toEqual(route.meta.details)
  expect(DetailsTab.methods.getDetailTitle.call(ctx, 'id')).toBe('label.id')
})
function view (type, requested = 'runtime.catalog', ready = true) {
  const resource = ready ? { id: 'template-a', templatetype: type } : {}
  const ctx = { resource, $route: { path: '/template/template-a', params: { id: 'template-a' }, query: { tab: requested, templatereturn: 'keep' }, meta: { name: 'template', tabs: config.tabs } }, $store: store, $router: { replace: jest.fn() }, tabs: config.tabs, networkService: null }
  ctx.templateTabsReady = ResourceView.computed.templateTabsReady.call(ctx)
  ctx.showTab = ResourceView.methods.showTab.bind(ctx)
  return ctx
}
test.each(['USER', 'VNF', 'SYSTEM'])('hidden runtime deep links recover to visible details for %s', type => {
  const ctx = view(type); ResourceView.methods.setActiveTab.call(ctx)
  expect(ctx.activeTab).toBe('details')
  expect(ctx.$router.replace).toHaveBeenCalledWith({ path: ctx.$route.path, query: { tab: 'details', templatereturn: 'keep' } })
})
test('a valid Storage Service runtime link survives; pre-read uncertainty never rewrites URL', () => {
  const ctx = view('SHAREDFS'); ResourceView.methods.setActiveTab.call(ctx)
  expect(ctx.activeTab).toBe('runtime.catalog'); expect(ctx.$router.replace).not.toHaveBeenCalled()
  const pending = view(undefined, 'runtime.catalog', false); ResourceView.methods.setActiveTab.call(pending)
  expect(pending.$router.replace).not.toHaveBeenCalled()
})
test('VNF settings stay gated by their API and recover when hidden', () => {
  const ctx = view('VNF', 'vnf.settings'); ResourceView.methods.setActiveTab.call(ctx); expect(ctx.activeTab).toBe('details')
  store.getters.apis.updateVnfTemplate = {}; ResourceView.methods.setActiveTab.call(ctx); expect(ctx.activeTab).toBe('vnf.settings')
  ctx.resource.templatetype = 'USER'; ResourceView.methods.setActiveTab.call(ctx); expect(ctx.activeTab).toBe('details')
})
function catalog () {
  const c = { $route: { path: '/template/template-a', meta: { name: 'template' } }, resource: { id: 'template-a', templatetype: 'SHAREDFS' }, $store: { getters: store.getters, state: { user: { token: 'first-session' } } }, rows: [], total: 0, page: 1, epoch: 0, busy: false, active: true, error: '', $t: t, $notifyError: jest.fn() }
  Object.defineProperty(c, 'scope', { get: () => Catalog.computed.scope.call(c) })
  Object.defineProperty(c, 'eligible', { get: () => Catalog.computed.eligible.call(c) })
  c.load = Catalog.methods.load.bind(c)
  return c
}
test('catalog is explicitly global and does not submit a guessed template compatibility filter', async () => {
  const c = catalog(); await c.load()
  expect(getAPI).toHaveBeenCalledWith('listStorageServiceRuntimeBundles', { purpose: 'catalog', page: 1, pagesize: 10 })
  expect(c.rows[0].id).toBe('bundle'); expect(c.busy).toBe(false)
})
test.each(['type', 'role', 'capability', 'session', 'project'])('late catalog response cannot cross a changed %s scope', async changed => {
  const c = catalog(); let release; getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = c.load()
  if (changed === 'type') c.resource.templatetype = 'USER'
  if (changed === 'role') c.$store.getters.userInfo.roletype = 'User'
  if (changed === 'capability') c.$store.getters.apis = {}
  if (changed === 'session') c.$store.state.user.token = 'new-session'
  if (changed === 'project') c.$store.getters.project = { id: 'other' }
  c.active = false; Catalog.watch.scope.call(c)
  release({ liststorageserviceruntimebundlesresponse: { count: 1, storageserviceruntimebundle: [{ id: 'stale' }] } }); await pending
  expect(c.rows).toEqual([]); expect(c.total).toBe(0); expect(c.busy).toBe(false)
})
test('older finally cannot clear a newer template catalog spinner', async () => {
  const c = catalog(); let oldDone; let newDone
  getAPI.mockImplementationOnce(() => new Promise(resolve => { oldDone = resolve })).mockImplementationOnce(() => new Promise(resolve => { newDone = resolve }))
  const old = c.load(); c.resource.id = 'template-b'; const next = c.load()
  oldDone({ liststorageserviceruntimebundlesresponse: { count: 1, storageserviceruntimebundle: [{ id: 'stale' }] } }); await old
  expect(c.rows).toEqual([]); expect(c.busy).toBe(true)
  newDone({ liststorageserviceruntimebundlesresponse: { count: 1, storageserviceruntimebundle: [{ id: 'current' }] } }); await next
  expect(c.rows[0].id).toBe('current'); expect(c.busy).toBe(false)
})
test('unmount or deactivation suppresses late catalog failures', async () => {
  const c = catalog(); let fail; getAPI.mockImplementationOnce(() => new Promise((resolve, reject) => { fail = reject }))
  const pending = c.load(); Catalog.deactivated.call(c); fail(new Error('gone')); await pending
  expect(c.$notifyError).not.toHaveBeenCalled(); expect(c.error).toBe(''); expect(c.busy).toBe(false)
})
