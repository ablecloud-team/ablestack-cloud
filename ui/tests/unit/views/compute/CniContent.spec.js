// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { mount, flushPromises } from '@vue/test-utils'
import { h, markRaw } from 'vue'
import CniTab from '@/views/compute/CniContent.vue'
import ResourceView from '@/components/view/ResourceView.vue'
import Autogen from '@/views/AutogenView.vue'
import Content from '@/views/compute/UserDataContent.vue'
import { userDataContent, userDataLibraryParams } from '@/utils/userDataContent'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const source = { id: '11111111-1111-4111-8111-111111111111', name: 'library', account: 'admin', domainid: '22222222-2222-4222-8222-222222222222' }
const text = '\ufeff#cloud-config\r\n한글 😀\r\n' + '가'.repeat(5000) + '\n\n'
const encoded = Buffer.from(text).toString('base64')
const result = (userdata = encoded, extra = {}) => ({ listcniconfigurationresponse: { count: 1, cniconfig: [{ ...source, userdata, ...extra }] } })
let c
beforeEach(() => {
  jest.clearAllMocks()
  delete navigator.clipboard
  c = { resource: { ...source }, sourceType: 'cni', loading: false, resourceActionReady: () => true, tab: 'cni.content', $route: { path: '/cniconfiguration/' + source.id }, $store: { getters: { apis: { listCniConfiguration: {} }, userInfo: { id: 'user' }, project: {} }, state: { user: { token: 'token' } } } }
  Object.assign(c, Content.data.call(c)); Object.entries(Content.methods).forEach(([key, fn]) => { c[key] = fn.bind(c) }); Object.entries(Content.computed).forEach(([key, fn]) => { Object.defineProperty(c, key, { get: () => fn.call(c) }) })
  getAPI.mockResolvedValue(result())
})
test('strict UTF-8 preserves Korean emoji BOM CRLF long lines and final newlines byte for byte', () => {
  const value = userDataContent(encoded)
  expect(value.text).toBe(text)
  expect(Buffer.from(value.bytes).equals(Buffer.from(text))).toBe(true)
  expect(value.encoded).toBe(encoded)
})
test.each(['@@', 'a', 'abcd=', 'Zh==', 'YWJj\n', 42])('malformed Base64 %s never becomes raw text', value => { expect(() => userDataContent(value)).toThrow() })
test('binary bytes are preserved, never replaced with Unicode replacement characters', () => { const value = userDataContent('/wAB'); expect(value.text).toBeNull(); expect([...value.bytes]).toEqual([255, 0, 1]) })
test.each([null, undefined, ''])('empty %s is distinct from a read failure', value => { expect(userDataContent(value)).toMatchObject({ empty: true, text: '' }) })
test('UUID keyword becomes exact id while owner/project scope and pagination stay intact', () => {
  expect(userDataLibraryParams({ keyword: source.id, account: 'owner', domainid: source.domainid, projectid: 'project', page: 2, pagesize: 10, sortkey: 'name', sortorder: 'asc', tab: 'details' })).toEqual({ id: source.id, account: 'owner', domainid: source.domainid, projectid: 'project', page: 2, pagesize: 10 })
  expect(userDataLibraryParams({ keyword: 'part', name: 'exact' })).toEqual({ keyword: 'part', name: 'exact' })
})
test('content lookup is fixed to the UUID and owner; source identity is verified before publication', async () => { await c.fetchData(); expect(getAPI.mock.calls[0][1]).toMatchObject({ id: source.id, account: source.account, domainid: source.domainid, ignoreproject: true }); expect(c.status).toBe('ready'); expect(c.display).toBe(text) })
test.each(['name', 'id', 'account', 'domainid'])('changed %s identity is never displayed', async key => { getAPI.mockResolvedValue(result(encoded, { [key]: 'other' })); await c.fetchData(); expect(c.status).toBe('mismatch'); expect(c.content).toBeNull() })
test('unsupported permission and invalid source never issue unfiltered content requests', async () => { delete c.$store.getters.apis.listCniConfiguration; await c.fetchData(); expect(c.status).toBe('unsupported'); expect(getAPI).not.toHaveBeenCalled(); c.$store.getters.apis.listCniConfiguration = {}; c.resource.domainid = ''; await c.fetchData(); expect(c.status).toBe('mismatch'); expect(getAPI).not.toHaveBeenCalled() })
test.each([403, 432, 431, 503])('HTTP %s is not empty content and only recoverable failures keep stale content', async code => { await c.fetchData(); const old = c.content; getAPI.mockRejectedValue({ response: { status: code } }); const pending = c.fetchData(); expect(c.content).toBe(old); expect(c.exportable).toBe(false); await pending; expect(c.status).toBe(code === 503 ? 'error' : code === 431 ? 'missing' : 'forbidden'); expect(c.content).toBe(code === 503 ? old : null); expect(c.exportable).toBe(false) })
test.each(['project', 'user', 'tab', 'source'])('late responses after %s changes cannot publish another scope', async kind => { let release; getAPI.mockImplementation(() => new Promise(resolve => { release = resolve })); const pending = c.fetchData(); if (kind === 'project') c.$store.getters.project = { id: 'other' }; if (kind === 'user') c.$store.getters.userInfo.id = 'other'; if (kind === 'tab') c.tab = 'details'; if (kind === 'source') c.resource.id = source.domainid; release(result()); await pending; expect(c.content).toBeNull() })
test('binary mode and malformed stored data have distinct feedback', async () => { getAPI.mockResolvedValue(result('/wAB')); await c.fetchData(); expect(c.mode).toBe('base64'); expect(c.display).toBe('/wAB'); getAPI.mockResolvedValue(result('!invalid')); await c.fetchData(); expect(c.status).toBe('invalid'); expect(c.content).toBeNull() })
test('copy event receives exact selected text and reports real command failure', async () => {
  await c.fetchData(); let copied
  document.execCommand = jest.fn(() => { const e = new Event('copy', { cancelable: true }); e.clipboardData = { setData: (_, value) => { copied = value } }; document.dispatchEvent(e); return true })
  c.copy(); expect(copied).toBe(text); expect(c.feedbackError).toBe(false)
  c.mode = 'base64'; c.copy(); expect(copied).toBe(encoded)
  document.execCommand.mockReturnValue(false); c.copy(); expect(c.feedbackError).toBe(true)
})
test('download uses original bytes or selected Base64 and a UUID based safe filename', async () => {
  await c.fetchData(); const blobs = []; const names = []
  const oldBlob = global.Blob; global.Blob = jest.fn(parts => { blobs.push(parts); return {} })
  const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () { names.push(this.download) })
  URL.createObjectURL = jest.fn(() => 'blob:test'); URL.revokeObjectURL = jest.fn(); jest.useFakeTimers()
  try { c.download(); expect(Buffer.from(blobs[0][0]).equals(Buffer.from(text))).toBe(true); c.mode = 'base64'; c.download(); expect(blobs[1][0]).toBe(encoded); expect(names).toEqual(['cni-' + source.id + '.txt', 'cni-' + source.id + '.base64.txt']); jest.runAllTimers(); expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2) } finally { global.Blob = oldBlob; click.mockRestore(); jest.useRealTimers() }
})

afterEach(() => { delete navigator.clipboard })
test('modern clipboard waits for the actual write before reporting success', async () => {
  await c.fetchData(); let release
  const writeText = jest.fn(() => new Promise(resolve => { release = resolve }))
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  const pending = c.copy(); expect(writeText).toHaveBeenCalledWith(text); expect(c.feedback).toBe('')
  release(); await pending; expect(c.feedback).toBe('message.success.copy.clipboard')
})
test('rejected modern clipboard falls back to selected text and restores focus without changing exact bytes', async () => {
  await c.fetchData(); let copied
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: jest.fn().mockRejectedValue(new Error('denied')) } })
  const focus = document.createElement('button'); document.body.appendChild(focus); focus.focus()
  document.execCommand = jest.fn(() => { expect(document.activeElement.tagName).toBe('TEXTAREA'); const e = new Event('copy', { cancelable: true }); e.clipboardData = { setData: (_, value) => { copied = value } }; document.dispatchEvent(e); return true })
  await c.copy(); expect(copied).toBe(text); expect(document.activeElement).toBe(focus); expect(document.querySelector('textarea')).toBeNull(); focus.remove()
})
test('late clipboard completion never posts success into a different scope', async () => {
  await c.fetchData(); let release
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => new Promise(resolve => { release = resolve }) } })
  const pending = c.copy(); c.resource.id = source.domainid; release(); await pending; expect(c.feedback).toBe('')
})

test('loading watchers coalesce an already pending content lookup', async () => {
  let release; getAPI.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const pending = c.fetchData(); Content.watch.loading.call(c, false); await c.fetchData(); expect(getAPI).toHaveBeenCalledTimes(1)
  release(result()); await pending; expect(c.content.text).toBe(text)
})

test('CNI uses only its own API and does not require ordinary UserData permission', async () => {
  await c.fetchData(); expect(getAPI.mock.calls[0][0]).toBe('listCniConfiguration'); expect(c.exportable).toBe(true)
  getAPI.mockResolvedValue({ listuserdataresponse: { count: 1, userdata: [{ ...source, userdata: encoded }] } }); await c.fetchData(); expect(c.status).toBe('error'); expect(c.exportable).toBe(false)
})
test.each(['permission', 'loading', 'readiness', 'scope', 'tab'])('export guard immediately rejects stale content after %s change', async kind => {
  await c.fetchData(); expect(c.exportable).toBe(true)
  if (kind === 'permission') delete c.$store.getters.apis.listCniConfiguration
  if (kind === 'loading') c.loading = true
  if (kind === 'readiness') c.resourceActionReady = () => false
  if (kind === 'scope') c.$store.getters.project = { id: 'other' }
  if (kind === 'tab') c.tab = 'details'
  expect(c.exportable).toBe(false)
  document.execCommand = jest.fn(); await c.copy(); c.download(); expect(document.execCommand).not.toHaveBeenCalled()
})
test('project lookup carries exact project and rejects selected foreign project without an API request', async () => {
  c.resource.projectid = source.domainid; c.$store.getters.project = { id: source.domainid }
  getAPI.mockResolvedValue(result(encoded, { projectid: source.domainid })); await c.fetchData(); expect(getAPI.mock.calls[0][1]).toMatchObject({ projectid: source.domainid, id: source.id }); expect(getAPI.mock.calls[0][1].account).toBeUndefined(); expect(c.status).toBe('ready')
  getAPI.mockClear(); c.$store.getters.project.id = source.id; await c.fetchData(); expect(c.status).toBe('mismatch'); expect(c.content).toBeNull(); expect(getAPI).not.toHaveBeenCalled()
})
test('parent lookup pending and failed states never issue a body lookup', async () => {
  c.loading = true; await c.fetchData(); c.loading = false; c.resourceActionReady = () => false; await c.fetchData(); expect(getAPI).not.toHaveBeenCalled()
})
test.each([{}, { listcniconfigurationresponse: { count: 1 } }, { listcniconfigurationresponse: { count: 0, cniconfig: [{}] } }, { listcniconfigurationresponse: { count: 1, cniconfig: 'wrong' } }])('malformed CNI response never exports stale bytes', async json => {
  await c.fetchData(); getAPI.mockResolvedValue(json); await c.fetchData(); expect(c.status).toBe('error'); expect(c.exportable).toBe(false)
})

test('leaving the CNI tab resets pending generation and reentry reads the same UUID again', async () => {
  let release; getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const old = c.fetchData(); c.tab = 'details'; Content.watch.active.call(c, false); expect(c.exportable).toBe(false)
  c.tab = 'cni.content'; getAPI.mockResolvedValue(result()); const resumed = jest.spyOn(c, 'fetchData'); Content.watch.active.call(c, true); await resumed.mock.results[0].value
  expect(c.status).toBe('ready'); release(result('b3RoZXI=')); await old; expect(c.display).toBe(text)
})
test.each(['token', 'unmount'])('%s change discards pending CNI responses', async kind => {
  let release; getAPI.mockImplementation(() => new Promise(resolve => { release = resolve })); const old = c.fetchData()
  if (kind === 'token') c.$store.state.user.token = 'rotated'
  else Content.beforeUnmount.call(c)
  release(result()); await old
  expect(c.content).toBeNull(); expect(c.exportable).toBe(false)
})

test.each(['pending', 'error', 'scope', 'uuid'])('parent CNI detail readiness blocks %s before exports', kind => {
  const parent = { listRequestPending: false, listRefreshError: false, listLoadedScope: 'current', listScope: () => 'current', resource: source, $route: { params: { id: source.id } } }
  expect(Autogen.computed.cniDetailActionReady.call(parent)).toBe(true)
  if (kind === 'pending') parent.listRequestPending = true
  if (kind === 'error') parent.listRefreshError = true
  if (kind === 'scope') parent.listLoadedScope = 'other'
  if (kind === 'uuid') parent.$route.params.id = source.domainid
  expect(Autogen.computed.cniDetailActionReady.call(parent)).toBe(false)
})
test('real ResourceView injection blocks exports on parent refresh/failure and rereads body after recovery', async () => {
  const tabs = [{ name: 'cni.content', component: markRaw(CniTab) }]
  const slot = { render () { return h('div', this.$slots.default()) } }
  const layout = { render () { return h('div', this.$slots.right()) } }
  const wrapper = mount({
    data: () => ({ listRequestPending: false, listRefreshError: false, listLoadedScope: 'current', resource: { ...source } }),
    methods: { listScope: () => 'current' },
    render () { return h(ResourceView, { resource: this.resource, tabs, readyForActions: Autogen.computed.cniDetailActionReady.call(this) }) }
  }, {
    global: {
      mocks: { $route: { path: '/cniconfiguration/' + source.id, params: { id: source.id }, query: { tab: 'cni.content' }, meta: { name: 'cniconfiguration', tabs } }, $router: { push: jest.fn(), replace: jest.fn() }, $t: key => key, $store: { getters: { apis: { listCniConfiguration: {} }, userInfo: {}, project: {} }, state: { app: { device: 'desktop' }, user: { token: 'session' } } } },
      stubs: { ResourceLayout: layout, InfoCard: true, ACard: slot }
    }
  })
  try {
    await flushPromises(); const content = wrapper.findComponent(Content)
    expect(content.vm.exportable).toBe(true); expect(wrapper.get('.content-code').text()).toContain('한글')
    await wrapper.setData({ listRequestPending: true }); await flushPromises()
    expect(content.vm.exportable).toBe(false); expect(wrapper.text()).toContain('message.cni.content.unverified')
    const copy = wrapper.findAll('button').find(b => b.text().includes('label.copy.clipboard')); expect(copy.element.disabled).toBe(true)
    await wrapper.setData({ listRequestPending: false, listRefreshError: true }); await flushPromises(); expect(content.vm.exportable).toBe(false)
    getAPI.mockResolvedValue(result(Buffer.from('new body after parent refresh\r\n').toString('base64')))
    await wrapper.setData({ listRefreshError: false }); await flushPromises()
    expect(content.vm.exportable).toBe(true); expect(content.vm.display).toBe('new body after parent refresh\r\n'); expect(getAPI).toHaveBeenCalledTimes(2)
  } finally { wrapper.unmount() }
})
