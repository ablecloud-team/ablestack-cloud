// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Select from '@/components/view/VolumeSearchSelect'
import PolicySearch from '@/components/view/SnapshotPolicySearch'
import Search from '@/components/view/SearchView'
import Filter from '@/components/view/SearchFilter'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
let wrappers; let store; let route
const row = (id, extra = {}) => ({ id, name: 'SP02-한글-Volume', account: 'owner', domain: 'ROOT', zonename: 'Zone', vmdisplayname: 'VM', ...extra })
const response = (rows, count = rows.length) => ({ listvolumesresponse: { volume: rows, count } })
const waitSearch = async () => { await new Promise(resolve => setTimeout(resolve, 270)); await flushPromises() }
function render (props = {}, component = Select) {
  const w = mount(component, { props, attachTo: document.body, global: { mocks: { $store: store, $route: route, $t: key => key, $notifyError: jest.fn() }, stubs: { ResourceIcon: true, Status: true, APopover: { template: '<div><slot name="content"/><slot/></div>' } } } })
  wrappers.push(w); return w
}
beforeEach(() => {
  wrappers = []; getAPI.mockReset()
  store = reactive({ getters: { userInfo: { id: 'owner', roletype: 'User' }, project: {}, apis: { listVolumes: {} }, listAllProjects: false }, state: { user: { token: 'session' } } })
  route = reactive({ name: 'snapshotpolicy', path: '/snapshotpolicy', query: {}, meta: { name: 'snapshotpolicy' } })
  getAPI.mockResolvedValue(response([row('a'), row('b')]))
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises(); document.body.innerHTML = '' })

test('actual AntD search uses the registered API and keyword, and duplicate names submit the exact UUID', async () => {
  const w = render(); await flushPromises()
  expect(getAPI.mock.calls[0]).toEqual(['listVolumes', { listall: true, page: 1, pagesize: 20 }, { preserveOnFailure: true, timeout: 20000 }])
  await w.find('input[role="combobox"]').setValue('한글'); await waitSearch()
  expect(getAPI.mock.calls[1][1]).toEqual({ listall: true, keyword: '한글', page: 1, pagesize: 20 })
  expect(getAPI.mock.calls.every(call => !('isencrypted' in call[1]))).toBe(true)
  const second = Array.from(document.querySelectorAll('.ant-select-item-option')).find(e => e.textContent.endsWith('b'))
  expect(second.textContent).toContain('VM · owner · ROOT · Zone · b')
  second.click(); await flushPromises()
  expect(w.emitted('update:value').at(-1)).toEqual(['b'])
  expect(w.find('.volume-search-target').text()).toContain('b')
})
test('more button fetches a later page and keeps its UUID', async () => {
  getAPI.mockResolvedValueOnce(response(Array.from({ length: 20 }, (_, i) => row('page1-' + i)), 21)).mockResolvedValueOnce(response([row('page2')], 21))
  const w = render(); await flushPromises()
  await w.findAll('button').find(b => b.text() === 'message.snapshot.search.more').trigger('click'); await flushPromises()
  expect(getAPI.mock.calls[1][1]).toMatchObject({ page: 2, pagesize: 20 })
  expect(w.vm.rows.at(-1).id).toBe('page2')
  w.vm.select({ value: 'page2' }); expect(w.emitted('update:value').at(-1)).toEqual(['page2'])
})
test('changing count or duplicate page results require a clean first-page retry', async () => {
  getAPI.mockResolvedValueOnce(response(Array.from({ length: 20 }, (_, i) => row('v' + i)), 21)).mockResolvedValueOnce(response([row('v0')], 21))
  const w = render(); await flushPromises(); await w.vm.load(false)
  expect(w.vm.error).toBe(true); expect(w.vm.rows).toEqual([])
  getAPI.mockResolvedValueOnce(response([row('fresh')]))
  await w.findAll('button').find(b => b.text() === 'label.retry').trigger('click'); await flushPromises()
  expect(getAPI.mock.calls.at(-1)[1].page).toBe(1); expect(w.vm.rows[0].id).toBe('fresh')
})
test('failed reads differ from empty search and retry keeps authentication and keyword', async () => {
  getAPI.mockRejectedValueOnce(new Error('offline'))
  const w = render(); await flushPromises(); expect(w.vm.error).toBe(true); expect(w.emitted('blocked').at(-1)).toEqual([true])
  w.vm.search('absent'); getAPI.mockResolvedValueOnce(response([])); await waitSearch()
  expect(w.vm.error).toBe(false); expect(w.vm.rows).toEqual([]); expect(w.vm.keyword).toBe('absent'); expect(w.emitted('blocked').at(-1)).toEqual([false]); expect(store.state.user.token).toBe('session')
})
test('a new search immediately invalidates old options and ignores reversed replies', async () => {
  let old; getAPI.mockReturnValueOnce(new Promise(resolve => { old = resolve }))
  const w = render(); await flushPromises(); w.vm.search('new'); expect(w.vm.options).toEqual([])
  old(response([row('private-old')])); await flushPromises(); expect(w.vm.rows).toEqual([])
  getAPI.mockResolvedValueOnce(response([row('new')])); await waitSearch(); expect(w.vm.rows.map(v => v.id)).toEqual(['new'])
})
test.each(['login', 'project', 'permission', 'path'])('late replies cannot cross a %s scope change', async change => {
  const pending = []; getAPI.mockImplementation(() => new Promise(resolve => pending.push(resolve)))
  const w = render(); await flushPromises()
  if (change === 'login') store.state.user.token = 'new-session'
  if (change === 'project') store.getters.project = { id: 'new-project' }
  if (change === 'permission') store.getters.apis = {}
  if (change === 'path') route.path = '/other'
  await flushPromises(); pending[0](response([row('private-old')])); await flushPromises()
  expect(w.vm.rows).toEqual([]); expect(w.emitted('update:value').at(-1)).toEqual([undefined])
})
test('explicit project/account scopes are forwarded and mismatched API rows are rejected', async () => {
  const w = render({ scope: { projectid: 'project' } }); await flushPromises()
  expect(getAPI.mock.calls[0][1].projectid).toBe('project'); expect(w.vm.error).toBe(true); expect(w.vm.rows).toEqual([])
  getAPI.mockResolvedValue(response([row('scoped', { projectid: 'project' })])); await w.vm.load(true); expect(w.vm.rows[0].id).toBe('scoped')
  await w.setProps({ scope: { account: 'other', domainid: 'domain' } }); await flushPromises()
  expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ account: 'other', domainid: 'domain' }); expect(w.vm.rows).toEqual([])
})
test('URL-selected volume outside the first page is restored by a scoped ID lookup', async () => {
  getAPI.mockResolvedValueOnce(response([row('first', { projectid: 'project' })])).mockResolvedValueOnce(response([row('selected', { projectid: 'project' })]))
  const w = render({ value: 'selected', scope: { projectid: 'project' } }); await flushPromises()
  expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ id: 'selected', projectid: 'project' }); expect(w.vm.selected.id).toBe('selected'); expect(w.vm.blocked).toBe(false)
})
test('an inaccessible selected ID warns and blocks submission but can be cleared', async () => {
  getAPI.mockResolvedValue(response([])); const w = render({ value: 'inaccessible' }); await flushPromises()
  expect(w.text()).toContain('message.volume.search.unavailable'); expect(w.vm.blocked).toBe(true)
  w.vm.select(undefined); expect(w.emitted('update:value').at(-1)).toEqual([undefined])
})
test('unmount cancels debounce and discards in-flight rows', async () => {
  let finish; getAPI.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const w = render(); await flushPromises(); const vm = w.vm; w.unmount(); wrappers = []; finish(response([row('late')])); await flushPromises(); expect(vm.rows).toEqual([])
})
test('SearchView applies and resets the selected UUID while isencrypted stays a boolean without a lookup', async () => {
  route.meta.params = { listall: true }
  const w = render({ apiName: 'listSnapshotPolicies', searchFilters: ['volumeid'] }, Search)
  const selected = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  getAPI.mockResolvedValue(response([row(selected)]))
  const policySearch = w.findComponent(PolicySearch); policySearch.vm.open(); await flushPromises()
  expect(route.query).toEqual({})
  const picker = w.findComponent(Select); picker.vm.select({ value: selected }); await flushPromises(); policySearch.vm.apply(); await flushPromises()
  expect(w.emitted('search').at(-1)).toEqual([{ policySearch: { volumeid: selected } }])
  policySearch.vm.reset(); await flushPromises(); expect(w.emitted('search').at(-1)).toEqual([{ policySearch: {} }])
  const count = getAPI.mock.calls.length
  const bool = render({ apiName: 'listVolumes', searchFilters: ['isencrypted'] }, Search)
  bool.vm.visibleFilter = true; await flushPromises(); expect(getAPI.mock.calls.length).toBe(count)
  for (const value of [true, false]) { bool.vm.form.isencrypted = value; bool.vm.handleSubmit(); await flushPromises(); expect(bool.emitted('search').at(-1)).toEqual([{ isencrypted: value }]) }
  for (const value of [true, false]) { route.query = { isencrypted: String(value) }; bool.vm.fillFormFieldValues(); expect(bool.vm.form.isencrypted).toBe(value) }
})
test('policy filter chips resolve a scoped name without replacing their raw UUID', async () => {
  route.query = { projectid: 'project' }; getAPI.mockResolvedValue(response([row('b', { name: 'volume name', projectid: 'project' })]))
  const w = render({ apiName: 'listSnapshotPolicies', filters: [{ key: 'volumeid', value: 'b' }] }, Filter); await flushPromises()
  expect(w.vm.searchFilters[0]).toMatchObject({ value: 'volume name', rawValue: 'b' }); expect(getAPI.mock.calls[0][1]).toMatchObject({ id: 'b', projectid: 'project' })
})
