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
