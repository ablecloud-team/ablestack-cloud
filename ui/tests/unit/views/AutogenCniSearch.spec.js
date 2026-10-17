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
import Autogen from '@/views/AutogenView'
import Search from '@/components/view/SearchView'
import store from '@/store'
import { callAPI, getAPI } from '@/api'
import { cniLibraryParams, userDataLibraryParams } from '@/utils/userDataContent'
import { cniDetailLink, cniListLocation } from '@/utils/cniDeletion'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '12345678-1234-1234-1234-123456789abc'
const row = { id, name: 'fixture', account: 'admin', domainid: id }
let c
function route (query = {}, name = 'cniconfiguration') {
  c.$route = { name, path: '/' + name, fullPath: '/' + name + '?' + new URLSearchParams(query), params: {}, query, meta: { name, permission: [name === 'userdata' ? 'listUserData' : 'listCniConfiguration'], columns: ['name', 'id', 'account', 'domain'], actions: [], searchFilters: ['id', 'name', 'domainid', 'account'], customParamHandler: name === 'userdata' ? userDataLibraryParams : cniLibraryParams } }
}
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listCniConfiguration: {}, listUserData: {}, listAccounts: {}, listDomains: {}, createAccount: {} }, customColumns: {}, metrics: false, listAllProjects: false }
  c = { $store: store, $router: { replace: jest.fn(), push: jest.fn() }, $refs: {}, $emit: jest.fn(), $t: k => k, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn(), error: jest.fn() }, $notifyError: jest.fn(), $nextTick: fn => fn() }
  route(); Object.assign(c, Autogen.data.call(c)); Object.entries(Autogen.methods).forEach(([k, fn]) => { c[k] = fn.bind(c) }); c.updateSelectedColumns = jest.fn()
  getAPI.mockResolvedValue({ listcniconfigurationresponse: { count: 80, cniconfig: [row] } })
  callAPI.mockResolvedValue({ listuserdataresponse: { count: 80, userdata: [row] } })
})
test.each([['keyword', id, { id }], ['keyword', 'fixture', { keyword: 'fixture' }], ['name', 'fixture', { name: 'fixture' }], ['id', id, { id }]])('CNI %s=%s reaches the existing API contract', async (key, value, expected) => {
  route({ [key]: value, q: value, account: 'admin', domainid: id, page: '2', pagesize: '20', sortkey: 'name', sortorder: 'asc', tab: 'details', unexpected: 'excluded' })
  await c.fetchData()
  expect(getAPI).toHaveBeenCalledWith('listCniConfiguration', { ...expected, account: 'admin', domainid: id, listall: true, page: 2, pagesize: 20 }, { preserveOnFailure: true, timeout: 20000 })
  expect(c.columns.find(x => x.key === 'name').sortOrder).toBe('ascend')
  expect(c.hasProjectId).toBe(true)
})
test('CNI project switch exposes the common all-projects pipeline without changing UserData', async () => {
  store.getters.listAllProjects = true; await c.fetchData(); expect(getAPI.mock.calls[0][1].projectid).toBe('-1')
  getAPI.mockClear(); store.getters.project = { id }; route({ projectid: id }); await c.fetchData(); expect(getAPI.mock.calls[0][1].projectid).toBe(id)
  callAPI.mockClear(); route({ keyword: id, sortkey: 'name', sortorder: 'asc' }, 'userdata'); await c.fetchData(); expect(callAPI.mock.calls[0]).toEqual(['listUserData', expect.objectContaining({ id })]); expect(callAPI.mock.calls[0][1].sortkey).toBeUndefined(); expect(c.hasProjectId).toBe(true)
})
test('basic search replaces advanced filters and resets the page while retaining project/size/sort', () => {
  route({ id, name: 'old', account: 'old', domainid: id, projectid: id, page: '4', pagesize: '20', sortkey: 'name', sortorder: 'asc' })
  c.pageSize = 20; c.onSearch({ searchQuery: id })
  expect(c.$router.push).toHaveBeenCalledWith({ query: { keyword: id, q: id, projectid: id, page: '1', pagesize: '20', sortkey: 'name', sortorder: 'asc' } })
})
test('advanced/reset/remove-filter retain pagination size and clear obsolete search state', () => {
  route({ keyword: 'old', q: 'old', page: '4', pagesize: '20' }); c.onSearch({ name: 'exact', account: 'admin', domainid: id })
  expect(c.$router.push.mock.calls[0][0].query).toEqual({ name: 'exact', account: 'admin', domainid: id, page: '1', pagesize: '20' })
  route({ id, name: 'exact', page: '4', pagesize: '20' }); c.removeFilter({ key: 'id' }); expect(c.$router.push.mock.calls[1][0].query).toEqual({ name: 'exact', page: '1', pagesize: '20' })
  c.onSearch({}); expect(c.$router.push.mock.calls[2][0].query).toEqual({ page: '1', pagesize: '20' })
})
test('CNI page-size changes reset to page one, page navigation retains filters and sort', () => {
  route({ keyword: 'part', q: 'part', sortkey: 'name', sortorder: 'asc' }); c.changePage(3, 20)
  expect(c.$router.push.mock.calls[0][0].query.page).toBe(3)
  c.changePage(3, 50); expect(c.$router.push.mock.calls[1][0].query.page).toBe(1)
  c.changePageSize(3, 50); expect(c.$router.push.mock.calls[2][0].query.page).toBe(1)
  c.handleTableChange({}, {}, { field: 'name', order: 'descend' }); expect(c.$router.push.mock.calls[3][0].query).toMatchObject({ keyword: 'part', sortkey: 'name', sortorder: 'desc' })
})
test('UUID advanced/basic search survives detail return and delete navigation', () => {
  for (const query of [{ id, page: '2', pagesize: '20' }, { keyword: id, q: id, sortkey: 'name', sortorder: 'asc' }]) {
    const link = cniDetailLink({ query }, row, 'cni.content')
    expect(cniListLocation(link)).toEqual({ path: '/cniconfiguration', query })
  }
})
test('late CNI list response cannot replace a newer search result', async () => {
  let complete; getAPI.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const old = c.fetchData(); route({ keyword: 'new' }); await c.fetchData(); complete({ listcniconfigurationresponse: { count: 1, cniconfig: [{ ...row, name: 'stale' }] } }); await old
  expect(c.items[0].name).toBe('fixture')
})
function searchContext () {
  const s = { $route: c.$route, $store: store, $t: k => k, $message: { error: jest.fn() }, $emit: jest.fn(), apiName: 'listCniConfiguration', searchFilters: ['id', 'name', 'domainid', 'account'] }
  Object.assign(s, Search.data.call(s)); Object.entries(Search.methods).forEach(([k, fn]) => { s[k] = fn.bind(s) }); s.form = {}; s.formRef = { value: { validate: () => Promise.resolve(), resetFields: jest.fn() } }; return s
}
test('advanced form has actual UUID/name inputs and permission-scoped owner fields', () => {
  const s = searchContext(); s.initFields(); expect(s.fields.map(x => x.name)).toEqual(['id', 'name', 'domainid', 'account']); expect(s.fields.find(x => x.name === 'id').type).toBe('input')
  delete store.getters.apis.listDomains; delete store.getters.apis.listAccounts; s.initFields(); expect(s.fields.map(x => x.name)).toEqual(['id', 'name'])
})
test('advanced submit and reset emit the actual entered values', async () => {
  const s = searchContext(); s.form = { id, name: 'exact', account: '', domainid: undefined }; s.handleSubmit(); await Promise.resolve(); expect(s.$emit).toHaveBeenCalledWith('search', { id, name: 'exact' })
  s.fields = []; await s.onClear(); expect(s.$emit).toHaveBeenLastCalledWith('search', {})
})
test('CNI domain changes clear the selected account and reject late account options', async () => {
  const s = searchContext(); s.fields = [{ name: 'account', opts: [] }]; s.form = { domainid: id, account: 'old' }; let complete
  getAPI.mockImplementationOnce(() => new Promise(resolve => { complete = resolve })).mockResolvedValue({ listaccountsresponse: { account: [{ name: 'current', domainid: 'new' }] } })
  const old = s.fetchCniOwnerOptions('account'); s.form.domainid = 'new'; s.onSelectFieldChange('domainid'); expect(s.form.account).toBeUndefined(); await Promise.resolve(); await Promise.resolve()
  complete({ listaccountsresponse: { account: [{ name: 'old', domainid: id }] } }); await old
  expect(s.fields[0].opts.map(x => x.name)).toEqual(['current']); expect(s.fields[0].loading).toBe(false)
})

test('CNI reload/Back restores advanced-filter state from the canonical route before API completion', () => {
  const s = searchContext(); s.$route = { ...s.$route, query: { id } }; s.searchParams = {}; s.updateIsFiltered(); expect(s.isFiltered).toBe(true)
  s.$route = { ...s.$route, query: {} }; s.searchParams = { id }; s.updateIsFiltered(); expect(s.isFiltered).toBe(false)
  s.apiName = 'listUserData'; s.updateIsFiltered(); expect(s.isFiltered).toBe(true)
})

test.each([431, 432, 503])('first CNI HTTP %i failure keeps the search route recoverable and distinguishes errors from empty success', async status => {
  route({ keyword: id, q: id }); getAPI.mockRejectedValueOnce({ message: 'API rejected', response: { status, data: { errorresponse: { errorcode: status } } } })
  await c.fetchData(); expect(c.listRefreshError).toBe(true); expect(c.listLoadedScope).toBeNull(); expect(c.items).toEqual([]); expect(c.$router.push).not.toHaveBeenCalled()
  route({ keyword: 'valid' }); await c.fetchData(); expect(c.listRefreshError).toBe(false); expect(c.items[0].id).toBe(id)
})
test('CNI same-query transient failure retains verified rows and clears selection, permission/absence removes old data', async () => {
  await c.fetchData(); c.selectedRowKeys = [id]; getAPI.mockRejectedValueOnce({ response: { status: 503 } }); await c.fetchData(); expect(c.items[0].id).toBe(id); expect(c.listRefreshError).toBe(true); expect(c.selectedRowKeys).toEqual([])
  getAPI.mockRejectedValueOnce({ response: { status: 431 } }); await c.fetchData(); expect(c.items).toEqual([]); expect(c.resource).toEqual({}); expect(c.listLoadedScope).toBeNull(); expect(c.$router.push).not.toHaveBeenCalled()
})
