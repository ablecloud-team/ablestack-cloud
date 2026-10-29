// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { getAPI, callAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '12345678-1234-1234-1234-123456789abc'
const row = () => ({ id, volumeid: id, volumename: 'disk', account: 'owner', domainid: id, intervaltype: 2, maxsnaps: 2, schedule: '5:3:2', timezone: 'UTC', schedulingenabled: false, tags: [], zone: [], storage: [], policycontext: { version: 1, volumeid: id, observedat: '2026-10-27T00:00:00Z', schedulingenabled: false, schedulestate: 'PAUSED', historysource: 'RETAINED_SCHEDULER_JOBS', historylimit: 50, currentjobknown: false, pollintervalseconds: 300 } })
const reply = p => ({ listsnapshotpoliciesresponse: { count: 1, snapshotpolicy: [p || row()] } })
let ctx
beforeEach(() => {
  jest.clearAllMocks(); store.getters = { userInfo: { id: 'user', roletype: 'Admin' }, apis: { listSnapshotPolicies: {} }, project: {}, customColumns: {}, defaultListViewPageSize: 20 }; store.state.user.token = 'session'
  ctx = { $store: store, $route: { name: 'snapshotpolicy', params: { id }, path: '/snapshotpolicy/' + id, fullPath: '/snapshotpolicy/' + id, query: {}, meta: { name: 'snapshotpolicy', permission: ['listSnapshotPolicies'], columns: ['intervaltype'], actions: [] } }, $router: { replace: jest.fn(), push: jest.fn() }, $refs: {}, $emit: jest.fn(), $t: k => k, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $nextTick: f => f(), $notifyError: jest.fn() }
  Object.assign(ctx, Autogen.data.call(ctx)); Object.entries(Autogen.methods).forEach(([k, f]) => { ctx[k] = f.bind(ctx) })
  for (const k of ['isSnapshotList', 'isSnapshotDetail', 'isPolicyDetail', 'isSnapshotReadView', 'storageActionReady']) Object.defineProperty(ctx, k, { get: () => Autogen.computed[k].call(ctx) })
  ctx.updateSelectedColumns = jest.fn(); getAPI.mockResolvedValue(reply())
})
test('detail failure and incomplete refresh preserve last data/time and block actions; retry recovers', async () => {
  await ctx.fetchData(); expect(ctx.resource.id).toBe(id); const old = ctx.resource; const time = ctx.listLastUpdated
  getAPI.mockRejectedValueOnce({ response: { status: 503 } }); await ctx.fetchData(); expect(ctx.resource).toBe(old); expect(ctx.storageActionReady).toBe(false); expect(ctx.listLastUpdated).toBe(time)
  getAPI.mockResolvedValueOnce(reply({ ...row(), policycontext: undefined })); await ctx.fetchData(); expect(ctx.resource).toBe(old); expect(ctx.snapshotListError).toBe('message.snapshot.policy.detail.incomplete')
  getAPI.mockResolvedValue(reply()); await ctx.fetchData(); expect(ctx.storageActionReady).toBe(true); expect(callAPI).not.toHaveBeenCalled()
})

test('revoked policy permission clears detail without another request or a late private response', async () => {
  await ctx.fetchData(); let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve })); const old = ctx.fetchData(); await Promise.resolve()
  const count = getAPI.mock.calls.length; store.getters.apis = {}; store.state.user.token = ''
  await ctx.fetchData(); expect(getAPI).toHaveBeenCalledTimes(count); expect(ctx.resource).toEqual({})
  release(reply()); await old; expect(ctx.resource).toEqual({}); expect(ctx.storageActionReady).toBe(false)
  expect(ctx.$notifyError).not.toHaveBeenCalled()
})
test.each([403, 531, 404])('permission or missing %s clears successful data', async status => {
  await ctx.fetchData(); getAPI.mockRejectedValue({ response: { status } }); await ctx.fetchData(); expect(ctx.resource).toEqual({}); expect(ctx.storageActionReady).toBe(false)
})
test.each(['user', 'role', 'token', 'project', 'permissions'])('%s scope transition removes old data and discards late results', async change => {
  await ctx.fetchData(); const first = ctx.listScope(); let complete
  getAPI.mockImplementationOnce(() => new Promise(resolve => { complete = resolve })); const pending = ctx.fetchData(); await Promise.resolve()
  if (change === 'user') store.getters.userInfo.id = 'new'
  if (change === 'role') store.getters.userInfo.roleid = 'new'
  if (change === 'token') store.state.user.token = 'new'
  if (change === 'project') store.getters.project = { id }
  if (change === 'permissions') store.getters.apis.listVolumes = {}
  expect(ctx.listScope()).not.toBe(first); getAPI.mockResolvedValueOnce({ listsnapshotpoliciesresponse: {} }); const newer = ctx.fetchData(); expect(ctx.resource).toEqual({})
  complete(reply()); await pending; await newer; expect(ctx.resource).toEqual({})
})

test('confirmed policy detail deletion waits for close and returns to its filtered list without refetching the removed ID', async () => {
  await ctx.fetchData()
  ctx.$route.query = { projectid: id, keyword: 'disk', volumeid: id, id, tab: 'details', sortkey: 'maxsnaps', sortorder: 'asc', page: '2', pagesize: '50' }
  ctx.currentAction = { policyDeleteDialog: true, policyListScope: ctx.listScope() }; ctx.showAction = true
  ctx.fetchData = jest.fn()
  ctx.refreshActionData({ removed: [id] }); expect(ctx.fetchData).not.toHaveBeenCalled()
  ctx.closeAction({ removed: [id] })
  expect(ctx.$router.replace).toHaveBeenCalledWith({ path: '/snapshotpolicy', query: { projectid: id, keyword: 'disk', q: 'disk', volumeid: id, sortkey: 'maxsnaps', sortorder: 'asc', page: '1', pagesize: '50' } })
  expect(ctx.resource).toEqual({}); expect(ctx.showAction).toBe(false)
})

test('late detail response cannot restore a confirmed deleted policy', async () => {
  await ctx.fetchData(); let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve })); const pending = ctx.fetchData(); await Promise.resolve()
  ctx.currentAction = { policyDeleteDialog: true, policyListScope: ctx.listScope() }; ctx.showAction = true
  ctx.closeAction({ removed: [id] }); release(reply()); await pending
  expect(ctx.resource).toEqual({}); expect(ctx.listRequestPending).toBe(false)
  expect(ctx.$router.replace).toHaveBeenCalledWith(expect.objectContaining({ path: '/snapshotpolicy' }))
})

test('unconfirmed deletion still refreshes and a changed dialog scope cannot navigate away', async () => {
  await ctx.fetchData(); ctx.currentAction = { policyDeleteDialog: true, policyListScope: 'old-scope' }
  ctx.fetchData = jest.fn(); ctx.refreshActionData(); expect(ctx.fetchData).toHaveBeenCalledTimes(1)
  ctx.closeAction({ removed: [id] }); expect(ctx.$router.replace).not.toHaveBeenCalled(); expect(ctx.resource.id).toBe(id)
})

test('other self-managed dialogs retain their refresh arguments', () => {
  ctx.currentAction = { storageSnapshotMode: true }; ctx.fetchData = jest.fn()
  const options = { irefresh: true }; ctx.refreshActionData(options)
  expect(ctx.fetchData).toHaveBeenCalledWith(options)
})

test('same-policy background reads retain the actions trigger but cannot open a mutation', async () => {
  store.getters.apis.createSnapshotPolicy = {}
  await ctx.fetchData()
  const action = { api: 'createSnapshotPolicy', dataView: true, popup: true, snapshotStandardDialog: true }
  ctx.actions = [action]; ctx.dataView = true
  expect(Autogen.computed.visibleDataViewActions.call(ctx)).toEqual([action])
  let release
  getAPI.mockReturnValueOnce(new Promise(resolve => { release = resolve }))
  const pending = ctx.fetchData(); await Promise.resolve()
  const visible = Autogen.computed.visibleDataViewActions.call(ctx)
  expect(visible).toHaveLength(1); expect(visible[0].disabled()).toBe(true)
  ctx.execAction(action, false); expect(ctx.showAction).toBe(false); expect(callAPI).not.toHaveBeenCalled()
  release(reply()); await pending
  expect(Autogen.computed.visibleDataViewActions.call(ctx)).toEqual([action])
})

test.each(['initial', 'error', 'scope', 'permission'])('policy detail actions remain unavailable for %s reads', async state => {
  store.getters.apis.createSnapshotPolicy = {}
  await ctx.fetchData()
  ctx.actions = [{ api: 'createSnapshotPolicy', dataView: true }]; ctx.dataView = true; ctx.listRequestPending = true
  if (state === 'initial') ctx.resource = {}
  if (state === 'error') ctx.listRefreshError = true
  if (state === 'scope') ctx.listLoadedScope = 'different-policy-scope'
  if (state === 'permission') delete store.getters.apis.createSnapshotPolicy
  expect(Autogen.computed.visibleDataViewActions.call(ctx)).toEqual([])
})
