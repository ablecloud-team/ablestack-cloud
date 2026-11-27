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
import store from '@/store'
import { getAPI, callAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const a = '11111111-1111-1111-1111-111111111111'
const b = '22222222-2222-2222-2222-222222222222'
const row = { id: a, name: 'same', account: 'admin', domainid: a }
const reply = (rows = [row], count = rows.length) => ({ listsshkeypairsresponse: { sshkeypair: rows, count } })
let ctx
function route (id = a, query = {}) { ctx.$route = { ...ctx.$route, path: id ? '/ssh/' + encodeURIComponent(id) : '/ssh', fullPath: '/ssh/' + id + '?' + new URLSearchParams(query), params: id ? { id } : {}, query } }
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listSSHKeyPairs: {}, deleteSSHKeyPair: {} }, customColumns: {}, metrics: false, listAllProjects: false }
  store.state.user.token = 'session'
  ctx = { $store: store, $route: { name: 'ssh', path: '/ssh/' + a, fullPath: '/ssh/' + a, params: { id: a }, query: {}, meta: { name: 'ssh', permission: ['listSSHKeyPairs'], columns: ['name', 'fingerprint'], actions: [{ api: 'deleteSSHKeyPair', dataView: true }], searchFilters: ['name', 'account', 'domainid'] } }, $router: { replace: jest.fn(), push: jest.fn() }, $refs: {}, $emit: jest.fn(), $t: key => key, $showIcon: () => false, $isValidUuid: id => /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(id), $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn(), error: jest.fn() }, $notifyError: jest.fn(), $nextTick: callback => callback() }
  Object.assign(ctx, Autogen.data.call(ctx))
  Object.entries(Autogen.methods).forEach(([key, fn]) => { ctx[key] = fn.bind(ctx) })
  for (const key of ['isSshKeyPairDetail', 'sshKeyPairDetailVisible', 'sshKeyPairDetailActionReady', 'isSubmitDisabled']) Object.defineProperty(ctx, key, { get: () => Autogen.computed[key].call(ctx) })
  ctx.updateSelectedColumns = jest.fn()
  ctx.closeAction = jest.fn(() => { ctx.showAction = false })
  getAPI.mockResolvedValue(reply())
  callAPI.mockResolvedValue(reply())
})
test('UUID detail uses a bounded lookup and becomes actionable only after verification', async () => {
  const pending = ctx.fetchData()
  expect(ctx.sshKeyPairDetailActionReady).toBe(false)
  await pending
  expect(getAPI).toHaveBeenCalledWith('listSSHKeyPairs', { id: a, page: 1, pagesize: 2, listall: true, ignoreproject: true }, { preserveOnFailure: true, timeout: 20000 })
  expect(ctx.resource.id).toBe(a)
  expect(ctx.sshKeyPairDetailActionReady).toBe(true)
})
test('same-context refresh retains verified detail but blocks actions during the lookup', async () => {
  await ctx.fetchData(); let complete
  getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const request = ctx.fetchData({ irefresh: true })
  expect(ctx.sshKeyPairDetailVisible).toBe(true)
  expect(ctx.resource.id).toBe(a)
  expect(ctx.sshKeyPairDetailActionReady).toBe(false)
  expect(Autogen.computed.visibleDataViewActions.call(ctx)).toEqual([])
  complete(reply()); await request
  expect(ctx.sshKeyPairDetailActionReady).toBe(true)
})
test.each([{ rows: [{ ...row, id: b }], count: 1, status: 'mismatch' }, { rows: [row, { ...row, id: b }], count: 2, status: 'mismatch' }, { rows: [], count: 0, status: 'missing' }])('unverified reply %p clears detail and blocks entry/submission', async ({ rows, count, status }) => {
  await ctx.fetchData(); ctx.showAction = true
  getAPI.mockResolvedValue(reply(rows, count)); await ctx.fetchData({ irefresh: true })
  expect(ctx.sshKeyPairDetailStatus).toBe(status)
  expect(ctx.resource).toEqual({})
  expect(ctx.sshKeyPairDetailVisible).toBe(false)
  expect(ctx.sshKeyPairDetailActionReady).toBe(false)
  expect(ctx.closeAction).toHaveBeenCalled()
  expect(Autogen.computed.visibleDataViewActions.call(ctx)).toEqual([])
  ctx.execAction({ api: 'deleteSSHKeyPair' }, false)
  ctx.execSubmit({ preventDefault: jest.fn() })
  expect(postAPI).not.toHaveBeenCalled()
})
test('legacy name with multiple owners is an ambiguity error instead of first-row selection', async () => {
  route('same'); getAPI.mockResolvedValue(reply([row, { ...row, id: b, account: 'other' }], 2))
  await ctx.fetchData()
  expect(ctx.sshKeyPairDetailStatus).toBe('ambiguous')
  expect(ctx.resource).toEqual({})
})
test('explicit VM marker treats a UUID-shaped key name as a name', async () => {
  route(a, { keypairname: a, account: 'admin', domainid: a })
  getAPI.mockResolvedValue(reply([{ ...row, id: b, name: a }]))
  await ctx.fetchData()
  expect(getAPI.mock.calls[0][1].name).toBe(a)
  expect(getAPI.mock.calls[0][1].id).toBeUndefined()
  expect(ctx.resource.id).toBe(b)
})
test('explicit ownership ignores unrelated active-project/all-projects injection', async () => {
  store.getters.project = { id: b }; store.getters.listAllProjects = true
  route(a, { account: 'admin', domainid: a }); await ctx.fetchData()
  expect(getAPI.mock.calls[0][1].projectid).toBeUndefined()
  expect(getAPI.mock.calls[0][1].ignoreproject).toBe(true)
  expect(ctx.sshKeyPairDetailActionReady).toBe(true)
})
test.each([{ status: 431, expected: 'missing' }, { status: 432, expected: 'forbidden' }, { status: 503, expected: 'error' }])('lookup HTTP $status is a blocked local state', async ({ status, expected }) => {
  await ctx.fetchData(); getAPI.mockRejectedValue({ response: { status }, message: 'private error' })
  await ctx.fetchData({ irefresh: true })
  expect(ctx.sshKeyPairDetailStatus).toBe(expected)
  expect(ctx.resource).toEqual({})
  expect(ctx.sshKeyPairDetailActionReady).toBe(false)
  expect(ctx.$router.push).not.toHaveBeenCalled()
})
test.each(['target', 'owner', 'project', 'session'])('late response cannot cross a %s change', async change => {
  await ctx.fetchData(); let complete
  getAPI.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const old = ctx.fetchData({ irefresh: true })
  if (change === 'target') route(b)
  if (change === 'owner') route(a, { account: 'other', domainid: a })
  if (change === 'project') store.getters.project = { id: b }
  if (change === 'session') store.state.user.token = 'next'
  const newRow = { ...row, id: change === 'target' ? b : a, account: change === 'owner' ? 'other' : 'admin', ...(change === 'project' ? { projectid: b } : {}) }
  getAPI.mockResolvedValue(reply([newRow])); await ctx.fetchData()
  complete(reply([{ ...row, name: 'stale' }])); await old
  expect(ctx.resource.name).toBe('same')
  expect(ctx.resource).toEqual(newRow)
})
test('tab changes retain the detail context and key readiness', async () => {
  await ctx.fetchData(); const scope = ctx.listScope()
  route(a, { tab: 'comments' }); await ctx.fetchData()
  expect(ctx.listScope()).toBe(scope)
  expect(ctx.sshKeyPairDetailActionReady).toBe(true)
})
test('a stale action resource is rejected even after a valid detail lookup', async () => {
  await ctx.fetchData()
  ctx.execAction({ api: 'deleteSSHKeyPair', resource: { ...row, id: b } }, false)
  expect(ctx.sshKeyPairDetailStatus).toBe('mismatch')
  expect(ctx.showAction).toBe(false)
})
test('switching target during asynchronous form validation cannot submit the previous action', async () => {
  await ctx.fetchData(); let validate
  ctx.formRef = { value: { validate: jest.fn(() => new Promise(resolve => { validate = resolve })) } }
  ctx.currentAction = { api: 'deleteSSHKeyPair', resource: row }
  ctx.execSubmit({ preventDefault: jest.fn() })
  route(b); validate(); await Promise.resolve(); await Promise.resolve()
  expect(postAPI).not.toHaveBeenCalled()
  expect(callAPI).not.toHaveBeenCalled()
})
test('invalid marker and list-query pseudo-detail cannot request an unfiltered detail', async () => {
  route('same', { keypairname: 'other' }); await ctx.fetchData()
  expect(ctx.sshKeyPairDetailStatus).toBe('mismatch')
  expect(getAPI).not.toHaveBeenCalled()
  route('', { dataView: 'true' }); await ctx.fetchData()
  expect(ctx.resource).toEqual({})
  expect(getAPI).not.toHaveBeenCalled()
})
test('ordinary SSH list still uses the common list pipeline', async () => {
  route(''); await ctx.fetchData()
  expect(callAPI).toHaveBeenCalled()
  expect(getAPI).not.toHaveBeenCalled()
  expect(ctx.items).toEqual([{ ...row, key: 0 }])
})
test.each(['created', 'route'].flatMap(hook => ['ssh', 'vm'].map(kind => [hook, kind])))('%s entry in %s preserves SSH owner scope and retains other resource project switching', (hook, kind) => {
  route(a, { projectid: b, domainid: a })
  if (kind === 'vm') { ctx.$route.path = '/vm/' + a; ctx.$route.meta.name = 'vm'; ctx.$route.fullPath = '/vm/' + a + '?projectid=' + b }
  ctx.onListEvent = jest.fn(); ctx.fetchData = jest.fn(); ctx.switchProject = jest.fn()
  ctx.setModalWidthByScreen = jest.fn(); ctx.scheduleAutoRefresh = jest.fn(); ctx.clearAutoRefresh = jest.fn()
  if (hook === 'created') Autogen.created.call(ctx)
  else Autogen.watch.$route.handler.call(ctx, ctx.$route, { path: '/' + kind + '/' + b, fullPath: '/' + kind + '/' + b, query: {}, meta: { name: kind } })
  if (kind === 'ssh') expect(ctx.switchProject).not.toHaveBeenCalled()
  else expect(ctx.switchProject).toHaveBeenCalledWith(b)
  expect(ctx.$route.query.projectid).toBe(b)
})

test.each(['listVirtualMachines', 'listVirtualMachinesMetrics'])('%s account-key links preserve their owner under all-projects selection', async api => {
  ctx.$route = { path: '/vm', fullPath: '/vm?keypair=same&account=admin&domainid=' + a, params: {}, query: { keypair: 'same', account: 'admin', domainid: a }, meta: { name: 'vm', permission: [api], columns: ['name'], actions: [], searchFilters: [] } }
  store.getters.apis[api] = {}; store.getters.listAllProjects = true
  callAPI.mockResolvedValue({ listvirtualmachinesresponse: { count: 0, virtualmachine: [] } })
  await ctx.fetchData()
  const [command, params] = callAPI.mock.calls[0]
  expect(command).toBe(api)
  expect(params).toMatchObject({ keypair: 'same', account: 'admin', domainid: a, ignoreproject: true })
  expect(params.projectid).toBeUndefined()
})
test('project-key VM links override an unrelated all-projects injection', async () => {
  ctx.$route = { path: '/vm', fullPath: '/vm?keypair=same&projectid=' + b, params: {}, query: { keypair: 'same', projectid: b, domainid: a }, meta: { name: 'vm', permission: ['listVirtualMachines'], columns: ['name'], actions: [], searchFilters: [] } }
  store.getters.apis.listVirtualMachines = {}; store.getters.listAllProjects = true
  callAPI.mockResolvedValue({ listvirtualmachinesresponse: { count: 0, virtualmachine: [] } })
  await ctx.fetchData()
  expect(callAPI.mock.calls[0][1].projectid).toBe(b)
})
