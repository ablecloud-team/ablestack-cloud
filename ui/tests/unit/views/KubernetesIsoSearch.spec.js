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
import Search from '@/components/view/KubernetesIsoSearch'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import ResourceView from '@/components/view/ResourceView'
import { kubernetesIsoListQuery, kubernetesIsoDetailQuery } from '@/utils/kubernetesIsoSearch'
import { getAPI, callAPI } from '@/api'
import store from '@/store'
jest.mock('@/api', () => ({ getAPI: jest.fn(), callAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '11111111-1111-4111-8111-111111111111'
const other = '22222222-2222-4222-8222-222222222222'
const fields = ['keyword', 'zoneid', 'arch', 'minimumsemanticversion', 'minimumkubernetesversionid', 'sortkey', 'sortorder']
const response = (rows, count = rows.length) => ({ listkubernetessupportedversionsresponse: { count, kubernetessupportedversion: rows } })
const row = { id, name: 'Version', semanticversion: '1.36.5', arch: 'x86_64', zoneid: other }
let ctx
function route (query) {
  ctx.$route.query = kubernetesIsoListQuery(query, 20, store.getters, store.state.user.token)
  ctx.$route.fullPath = '/kubernetesiso?' + new URLSearchParams(ctx.$route.query)
}
beforeEach(() => {
  jest.clearAllMocks()
  store.state.user.token = 'session'
  store.getters = { userInfo: { id: 'actor', roleid: 'role', roletype: 'Admin' }, defaultListViewPageSize: 20, project: {}, apis: { listKubernetesSupportedVersions: { params: fields.map(name => ({ name })) } }, customColumns: {}, metrics: false, listAllProjects: false }
  ctx = {
    $store: store,
    $route: { name: 'kubernetesiso', path: '/kubernetesiso', fullPath: '/kubernetesiso', params: {}, query: {}, meta: { name: 'kubernetesiso', permission: ['listKubernetesSupportedVersions'], columns: ['name', 'semanticversion', 'arch'], actions: [] } },
    $router: { replace: jest.fn(), push: jest.fn() },
    $refs: {},
    $emit: jest.fn(),
    $t: k => k,
    $showIcon: () => false,
    $i18n: { locale: 'ko_KR' },
    $message: { info: jest.fn(), error: jest.fn() },
    $notifyError: jest.fn(),
    $nextTick: fn => fn()
  }
  Object.assign(ctx, Autogen.data.call(ctx))
  Object.entries(Autogen.methods).forEach(([k, f]) => { ctx[k] = f.bind(ctx) })
  for (const name of ['kubernetesIsoSecurityContext', 'kubernetesIsoScope']) {
    Object.defineProperty(ctx, name, { get: () => Autogen.computed[name].call(ctx) })
  }
  ctx.updateSelectedColumns = jest.fn()
  ctx.$router.replace.mockImplementation(({ query }) => { route(query); return ctx.fetchData() })
  route({}); getAPI.mockResolvedValue(response([row]))
})
test('combined catalog filters and global sort reach the real read without owner/return/project data', async () => {
  route({ keyword: 'Ver', zoneid: other, arch: 'x86_64', minimumsemanticversion: '1.36.0', sortkey: 'semanticversion', sortorder: 'desc', pagesize: 10 })
  await ctx.fetchData()
  expect(getAPI).toHaveBeenCalledWith('listKubernetesSupportedVersions', expect.objectContaining({ keyword: 'Ver', zoneid: other, arch: 'x86_64', minimumsemanticversion: '1.36.0', sortkey: 'semanticversion', sortorder: 'desc' }), expect.any(Object))
  expect(getAPI.mock.calls[0][1]).not.toHaveProperty('kisoowner')
  expect(ctx.columns.find(c => c.key === 'semanticversion')).toMatchObject({ sorter: true, sortOrder: 'descend' })
  expect(ctx.columns.find(c => c.key === 'arch').sorter).toBe(false)
  expect(callAPI).not.toHaveBeenCalled()
})
test.each([{ minimumsemanticversion: '1.30' }, { minimumsemanticversion: '1.30.0', minimumkubernetesversionid: id }, { minimumkubernetesversionid: 'bad' }])('invalid advanced criteria does not make a broad request', query => {
  route(query)
  return ctx.fetchData().then(() => { expect(getAPI).not.toHaveBeenCalled(); expect(ctx.kubernetesIsoReadError).toBe('searchInvalid') })
})
test('known zero results succeed and out-of-range last page is corrected using filtered count', async () => {
  getAPI.mockResolvedValueOnce(response([])); await ctx.fetchData()
  expect(ctx.itemCount).toBe(0); expect(ctx.kubernetesIsoReadError).toBe('')
  route({ page: 99, pagesize: 10 })
  getAPI.mockResolvedValueOnce(response([], 11)).mockResolvedValueOnce(response([row], 11))
  await ctx.fetchData()
  expect(ctx.$router.replace).toHaveBeenCalledWith({ query: expect.objectContaining({ page: '2' }) })
  expect(ctx.itemCount).toBe(11); expect(ctx.items).toHaveLength(1); expect(ctx.page).toBe(2)
})
test('search, global-sort and page-size changes reset page and retain the other canonical conditions', () => {
  route({ keyword: 'old', minimumsemanticversion: '1.30.0', page: 3, pagesize: 10 }); ctx.pageSize = 10
  ctx.onSearch({ searchQuery: 'new' })
  expect(ctx.$router.push).toHaveBeenLastCalledWith({ query: expect.objectContaining({ keyword: 'new', minimumsemanticversion: '1.30.0', page: '1' }) })
  ctx.handleTableChange({}, {}, { field: 'semanticversion', order: 'descend' })
  expect(ctx.$router.push).toHaveBeenLastCalledWith({ query: expect.objectContaining({ sortkey: 'semanticversion', sortorder: 'desc', page: '1' }) })
  ctx.changePage(3, 50)
  expect(ctx.$router.push).toHaveBeenLastCalledWith({ query: expect.objectContaining({ page: 1, pagesize: 50, minimumsemanticversion: '1.30.0' }) })
})
test('late filtered response cannot replace a newer search result', async () => {
  let finish; getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  route({ keyword: 'old' }); const pending = ctx.fetchData()
  route({ keyword: 'new' }); getAPI.mockResolvedValueOnce(response([{ ...row, name: 'new' }]))
  await ctx.fetchData(); finish(response([{ ...row, name: 'old' }])); await pending
  expect(ctx.items[0].name).toBe('new')
})
test('a server ignoring minimum/arch conditions never presents broad rows as matching results', async () => {
  route({ minimumsemanticversion: '1.36.0', arch: 'aarch64' }); await ctx.fetchData()
  expect(ctx.kubernetesIsoReadError).toBe('invalid'); expect(ctx.items).toEqual([])
})
test('detail tabs use the router and breadcrumb preserves the complete list return query', () => {
  route({ keyword: 'part', page: 2, pagesize: 10, sortkey: 'semanticversion', sortorder: 'desc' })
  const location = { path: '/kubernetesiso/' + row.id, query: kubernetesIsoDetailQuery(ctx.$route.query, store.getters, store.state.user.token) }
  const detail = { ...ctx.$route, path: location.path, params: { id }, query: location.query }
  const c = { $route: detail, $store: store, $router: ctx.$router, $emit: jest.fn() }
  ResourceView.methods.onTabChange.call(c, 'events')
  expect(ctx.$router.push).toHaveBeenLastCalledWith({ path: location.path, query: { ...location.query, tab: 'events' } })
  expect(Breadcrumb.methods.breadcrumbLocation.call(c, { path: '/kubernetesiso' }).query).toMatchObject({ keyword: 'part', page: '2', sortkey: 'semanticversion' })
})
function picker () {
  const c = { ...Search.data(), $route: ctx.$route, $store: store, $t: k => k, $emit: jest.fn(), $refs: { searchForm: { validate: jest.fn().mockResolvedValue() } } }
  Object.entries(Search.methods).forEach(([k, f]) => { c[k] = f.bind(c) })
  Object.entries(Search.computed).forEach(([k, f]) => Object.defineProperty(c, k, { get: () => f.call(c), configurable: true }))
  return c
}
test('registered minimum picker uses only the catalog read and ignores an old lookup after owner change', async () => {
  const c = picker(); let finish
  c.lookupKeyword = 'old-scope-search'; c.minimumMode = 'version'; c.hasMore = true; c.page = 3
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = c.fetchVersions(false); store.getters.userInfo.id = 'next'
  Search.watch.owner.handler.call(c); finish(response([row])); await pending
  expect(c.versions).toEqual([]); expect(c.$emit).not.toHaveBeenCalled()
  expect(c).toMatchObject({ lookupKeyword: '', minimumMode: 'none', hasMore: false, page: 1, loading: false, lookupError: false })
  expect(getAPI.mock.calls.map(call => call[0])).toEqual(['listKubernetesSupportedVersions'])
  getAPI.mockResolvedValueOnce(response([row])); await c.fetchVersions(true)
  expect(getAPI.mock.calls[1][1]).toEqual({ page: 1, pagesize: 50 })
  expect(c.loading).toBe(false)
})
test('minimum mode emits exactly one criterion and a revoked lookup is not an empty successful choice', async () => {
  const c = picker(); c.minimumMode = 'version'; c.form = { minimumsemanticversion: '1.30.0', minimumkubernetesversionid: id }
  await c.submit()
  expect(c.$emit).toHaveBeenCalledWith('search', expect.objectContaining({ minimumkubernetesversionid: id }))
  expect(c.$emit.mock.calls[0][1].minimumsemanticversion).toBeUndefined()
  getAPI.mockRejectedValueOnce(Object.assign(new Error('denied'), { response: { status: 531 } }))
  await c.fetchVersions(true); expect(c.lookupError).toBe(true)
})
test('scope change during asynchronous form validation cannot submit an old draft', async () => {
  const c = picker(); let finish; c.$refs.searchForm.validate.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = c.submit(); store.state.user.token = 'new-session'; Search.watch.owner.handler.call(c); finish(); await pending
  expect(c.$emit).not.toHaveBeenCalled()
})
