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
import store from '@/store'
import { getAPI, callAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), callAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'test-session' } } }))
const id = '11111111-1111-4111-8111-111111111111'
const other = '22222222-2222-4222-8222-222222222222'
let ctx
const reply = rows => ({ listtemplatesresponse: { count: rows.length, template: rows } })
function route (target) { Object.assign(ctx.$route, { path: '/template/' + target, fullPath: '/template/' + target, params: { id: target } }) }
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listTemplates: {} }, customColumns: {}, metrics: false, listAllProjects: false }
  ctx = { $store: store, $route: { name: 'template', path: '/template/' + id, fullPath: '/template/' + id, params: { id }, query: {}, meta: { name: 'template', permission: ['listTemplates'], params: { templatefilter: 'all', showunique: true }, columns: ['name', 'templatetype'], actions: [] } }, $router: { replace: jest.fn(), push: jest.fn() }, $refs: {}, $emit: jest.fn(), $t: k => k, $showIcon: () => false, $isValidUuid: value => /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(value), $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn(), error: jest.fn() }, $notifyError: jest.fn(), $nextTick: fn => fn() }
  Object.assign(ctx, Autogen.data.call(ctx))
  Object.entries(Autogen.methods).forEach(([k, fn]) => { ctx[k] = fn.bind(ctx) })
  ctx.updateSelectedColumns = jest.fn()
  getAPI.mockResolvedValue(reply([{ id, name: 'Image' }]))
})
test.each(['USER', 'SYSTEM', 'VNF', 'missing'])('invalid deep link %s is rejected without any API request', async target => {
  route(target); await ctx.fetchData()
  expect(getAPI).not.toHaveBeenCalled(); expect(callAPI).not.toHaveBeenCalled()
  expect(ctx.templateDetailError).toBe('message.template.detail.invalid')
  expect(ctx.resource).toEqual({}); expect(ctx.loading).toBe(false)
})
test('a valid UUID detail is read normally', async () => {
  await ctx.fetchData()
  expect(getAPI.mock.calls[0][0]).toBe('listTemplates')
  expect(getAPI.mock.calls[0][1].id).toBe(id)
  expect(ctx.resource.id).toBe(id); expect(ctx.templateDetailError).toBe('')
})
test.each([{ rows: [] }, { rows: [{ id: other }] }])('removed or mismatched detail has a local list recovery instead of redirect', async ({ rows }) => {
  getAPI.mockResolvedValue(reply(rows)); await ctx.fetchData()
  expect(ctx.templateDetailError).toBe('message.template.detail.unavailable')
  expect(ctx.$router.push).not.toHaveBeenCalled(); expect(ctx.resource).toEqual({})
})
test.each([431, 404, 503])('HTTP %s gives recoverable detail and retry can restore it', async status => {
  getAPI.mockRejectedValueOnce({ response: { status }, message: 'read failure' }); await ctx.fetchData()
  expect(ctx.templateDetailError).toBe(status === 503 ? 'message.template.detail.read.failed' : 'message.template.detail.unavailable')
  expect(ctx.$router.push).not.toHaveBeenCalled()
  await ctx.fetchData({ irefresh: true }); expect(ctx.resource.id).toBe(id); expect(ctx.templateDetailError).toBe('')
})
test('late valid response cannot replace an invalid destination', async () => {
  let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve }))
  const old = ctx.fetchData(); route('USER'); await ctx.fetchData()
  done(reply([{ id, name: 'late' }])); await old
  expect(ctx.resource).toEqual({}); expect(ctx.templateDetailError).toBe('message.template.detail.invalid')
})

test.each(['target', 'project', 'role', 'session'])('late template read cannot cross %s context', async change => {
  let done; getAPI.mockImplementationOnce(() => new Promise(resolve => { done = resolve }))
  const old = ctx.fetchData()
  if (change === 'target') route(other)
  if (change === 'project') store.getters.project = { id: other }
  if (change === 'role') store.getters.userInfo.roletype = 'User'
  if (change === 'session') store.state.user.token = 'new-session'
  const currentId = change === 'target' ? other : id
  getAPI.mockResolvedValue(reply([{ id: currentId, name: 'current' }]))
  await ctx.fetchData(); done(reply([{ id, name: 'stale' }])); await old
  expect(ctx.resource.id).toBe(currentId); expect(ctx.resource.name).toBe('current')
})

function listRoute (query = {}) {
  Object.assign(ctx.$route, { path: '/template', fullPath: '/template?' + new URLSearchParams(query), params: {}, query: { ...query, page: query.page || '1', pagesize: query.pagesize || '20' } })
  ctx.$getApiParams = () => ({ isready: {}, hypervisor: {}, forcks: {}, userdataid: {}, name: {} })
}
test('template combined list filters reach actual read while detail return state does not', async () => {
  listRoute({ isready: 'false', hypervisor: 'KVM', forcks: 'true', userdataid: id, page: '2', pagesize: '50' }); getAPI.mockResolvedValueOnce(reply([{ id, isready: false, hypervisor: 'KVM', forcks: true, userdataid: id }])); await ctx.fetchData()
  expect(getAPI).toHaveBeenCalledWith('listTemplates', expect.objectContaining({ isready: 'false', hypervisor: 'KVM', forcks: 'true', userdataid: id, page: '2', pagesize: '50' }), expect.any(Object))
  route(id); ctx.$route.query = { templatereturn: '{"isready":"false"}', templateowner: 'owner' }; getAPI.mockClear(); await ctx.fetchData(); expect(getAPI.mock.calls[0][1]).toEqual({ id, templatefilter: 'all' })
})
test('unsupported list filter gets local explanation without a misleading broad read', async () => {
  listRoute({ forcks: 'false' }); ctx.$getApiParams = () => ({}); await ctx.fetchData(); expect(getAPI).not.toHaveBeenCalled(); expect(ctx.templateFilterError).toBe('message.template.search.unsupported')
})
test('template search/size change reset page; unrelated resources keep existing behavior', () => {
  listRoute({ name: 'old', page: '3', pagesize: '50' }); ctx.pageSize = 50; ctx.onSearch({ isready: false }); expect(ctx.$router.push).toHaveBeenLastCalledWith({ query: expect.objectContaining({ isready: 'false', page: '1', pagesize: '50' }) })
  ctx.changePage(3, 100); expect(ctx.$router.push).toHaveBeenLastCalledWith({ query: expect.objectContaining({ page: 1, pagesize: 100 }) })
})
test('owner change clears selection and query and invalidates outstanding reads', () => {
  listRoute({ userdataid: id }); ctx.selectedRowKeys = [id]; ctx.items = [{ id }]; const before = ctx.listRequestVersion; Autogen.watch.templateSecurityContext.call(ctx); expect(ctx.listRequestVersion).toBeGreaterThan(before); expect(ctx.selectedRowKeys).toEqual([]); expect(ctx.items).toEqual([]); expect(ctx.$router.replace).toHaveBeenCalledWith({ query: { page: '1', pagesize: '20' } })
})

test('server ignoring a filter never presents broad results as matching', async () => {
  listRoute({ isready: 'false' }); getAPI.mockResolvedValue(reply([{ id, isready: true }])); await ctx.fetchData(); expect(ctx.templateFilterError).toBe('message.template.search.ignored'); expect(ctx.items).toEqual([]); expect(ctx.itemCount).toBe(0)
})
