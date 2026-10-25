// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { shallowMount } from '@vue/test-utils'
import ListView from '@/components/view/ListView'
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { getAPI, callAPI } from '@/api'
import { storageSnapshotListQuery } from '@/utils/storageSnapshotList'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '12345678-1234-1234-1234-123456789abc'
const row = { id, name: 'before', account: 'admin', domainid: id, intervaltype: 'MANUAL', physicalsize: 1373634, virtualsize: 1073741824 }
const reply = (rows = [row], count = rows.length) => ({ listsnapshotsresponse: { snapshot: rows, count } })
let ctx
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listSnapshots: {}, deleteSnapshot: {} }, customColumns: {}, metrics: false, listAllProjects: false }
  store.state.user.token = 'session'
  const query = storageSnapshotListQuery({ projectid: '0' })
  ctx = { $store: store, $route: { name: 'snapshot', path: '/snapshot', fullPath: '/snapshot?' + new URLSearchParams(query), params: {}, query, meta: { name: 'snapshot', permission: ['listSnapshots'], columns: ['name', 'intervaltype', 'physicalsize', 'virtualsize'], actions: [{ api: 'deleteSnapshot', dataView: true, groupAction: true, toolbarLabel: 'delete' }], searchFilters: ['name'] } }, $router: { replace: jest.fn(), push: jest.fn() }, $refs: { listview: { resetSelection: jest.fn() } }, $emit: jest.fn(), $t: key => key, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn() }, $notifyError: jest.fn(), $nextTick: callback => callback() }
  Object.assign(ctx, Autogen.data.call(ctx))
  Object.entries(Autogen.methods).forEach(([key, fn]) => { ctx[key] = fn.bind(ctx) })
  for (const key of ['isSnapshotList', 'isSnapshotCopyList', 'storageActionReady']) Object.defineProperty(ctx, key, { get: () => Autogen.computed[key].call(ctx) })
  ctx.updateSelectedColumns = jest.fn(); getAPI.mockResolvedValue(reply())
})
test('same-scope refresh retains rows/count/time/selection and uses a bounded preserved GET', async () => {
  await ctx.fetchData(); const old = ctx.items; const time = ctx.listLastUpdated
  ctx.selectedRowKeys = [id]; ctx.selectedItems = [row]
  let complete; getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const pending = ctx.fetchData({ irefresh: true })
  expect(ctx.items).toBe(old); expect(ctx.itemCount).toBe(1); expect(ctx.listLastUpdated).toBe(time); expect(ctx.selectedRowKeys).toEqual([id]); expect(ctx.storageActionReady).toBe(false)
  expect(Autogen.computed.visibleListActions.call(ctx)).toEqual([])
  expect(callAPI).not.toHaveBeenCalled(); expect(getAPI.mock.calls.at(-1)[2]).toEqual({ preserveOnFailure: true, timeout: 20000 })
  complete(reply([{ ...row, name: 'after' }])); await pending
  expect(ctx.items[0].name).toBe('after'); expect(ctx.storageActionReady).toBe(true)
})
test('503 preserves successful rows/time/selection but blocks writes, then retry recovers', async () => {
  await ctx.fetchData(); const old = ctx.items; const time = ctx.listLastUpdated; ctx.selectedRowKeys = [id]
  getAPI.mockRejectedValueOnce({ response: { status: 503, data: { errorresponse: { errortext: 'read unavailable' } } } })
  await ctx.fetchData(); expect(ctx.items).toBe(old); expect(ctx.listLastUpdated).toBe(time); expect(ctx.selectedRowKeys).toEqual([id]); expect(ctx.snapshotListError).toBe('read unavailable'); expect(ctx.storageActionReady).toBe(false); expect(ctx.$notifyError).not.toHaveBeenCalled()
  getAPI.mockResolvedValue(reply()); await ctx.fetchData(); expect(ctx.listRefreshError).toBe(false); expect(ctx.storageActionReady).toBe(true)
})
test.each([401, 403, 431, 432, 531])('permission %i removes previous results and selection', async code => {
  await ctx.fetchData(); ctx.selectedRowKeys = [id]
  getAPI.mockRejectedValue({ response: { status: code } }); await ctx.fetchData()
  expect(ctx.items).toEqual([]); expect(ctx.selectedRowKeys).toEqual([]); expect(ctx.listLoadedScope).toBeNull(); expect(ctx.storageActionReady).toBe(false)
})
test('an incomplete response never changes the prior successful time or rows', async () => {
  await ctx.fetchData(); const old = ctx.items; const time = ctx.listLastUpdated
  getAPI.mockResolvedValue(reply([row], 2)); await ctx.fetchData()
  expect(ctx.items).toBe(old); expect(ctx.listLastUpdated).toBe(time); expect(ctx.snapshotListError).toBe('message.snapshot.list.incomplete'); expect(ctx.listRefreshError).toBe(true)
})
test.each(['keyword', 'page', 'account', 'project', 'session', 'role'])('%s transition clears old data and ignores old response', async change => {
  await ctx.fetchData(); ctx.selectedRowKeys = [id]
  const resolves = []; getAPI.mockImplementation(() => new Promise(resolve => resolves.push(resolve)))
  const old = ctx.fetchData()
  if (change === 'project') { store.getters.project = { id: 'project' }; ctx.$route.query = { ...ctx.$route.query, projectid: 'project' } } else if (change === 'session') store.state.user.token = 'different'
  else if (change === 'role') store.getters.userInfo.roletype = 'User'
  else { ctx.$route.query = { ...ctx.$route.query, [change]: change === 'page' ? '2' : 'new' }; if (change === 'keyword') ctx.$route.query.q = 'new'; ctx.$route.fullPath += '&' + change + '=new' }
  const newer = ctx.fetchData()
  expect(ctx.items).toEqual([]); expect(ctx.selectedRowKeys).toEqual([])
  resolves[0](reply([{ ...row, name: 'late' }])); await old; expect(ctx.items).toEqual([])
  resolves[1]({ listsnapshotsresponse: {} }); await newer; expect(ctx.items).toEqual([])
})
test('last-page shrink redirects using the current filters and sort without fabricated rows', async () => {
  ctx.page = 2; ctx.$route.query.page = '2'; ctx.$route.fullPath += '&page=2'
  getAPI.mockResolvedValue({ listsnapshotsresponse: { count: 3 } }); await ctx.fetchData()
  expect(ctx.$router.replace).toHaveBeenCalledWith({ query: { ...ctx.$route.query, page: '1' } })
})

test('a context menu hidden by pending/error actions does not reopen after recovery', async () => {
  const actions = [{ api: 'deleteSnapshot', dataView: true, groupAction: true }]
  const wrapper = shallowMount(ListView, { props: { columns: [], items: [row], actions }, global: { provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() }, mocks: { $route: { name: 'snapshot', path: '/snapshot', params: {}, query: {}, meta: {} }, $store: { getters: { apis: { deleteSnapshot: {} }, userInfo: { roletype: 'Admin' } } }, $t: key => key }, stubs: { QuickView: true } } })
  await wrapper.setData({ contextQuickViewVisible: true, contextQuickViewRecord: row })
  expect(wrapper.findComponent({ name: 'ResourceContextMenu' }).exists()).toBe(true)
  await wrapper.setProps({ actions: [] }); expect(wrapper.findComponent({ name: 'ResourceContextMenu' }).exists()).toBe(false)
  await wrapper.setProps({ actions }); expect(wrapper.findComponent({ name: 'ResourceContextMenu' }).exists()).toBe(false)
  wrapper.unmount()
})

test('initial failure is an error state, separate from successful empty results', async () => {
  getAPI.mockRejectedValueOnce({ response: { status: 503 } }); await ctx.fetchData()
  expect(ctx.listLoadedScope).toBeNull(); expect(Autogen.computed.snapshotEmptyText.call(ctx)).toBe('message.snapshot.list.read.failed')
  getAPI.mockResolvedValue({ listsnapshotsresponse: {} }); await ctx.fetchData()
  expect(ctx.listLoadedScope).toBe(ctx.listScope()); expect(Autogen.computed.snapshotEmptyText.call(ctx)).toBe('message.snapshot.search.no.results')
})

test('a reloaded snapshot view restores explicit preferences after the common cache was overwritten', async () => {
  const data = new Map(); ctx.$localStorage = { get: key => data.get(key), set: (key, value) => data.set(key, JSON.parse(JSON.stringify(value))) }
  store.getters.customColumns.user = { '/snapshot': ['name', 'intervaltype', 'physicalsize'] }; store.dispatch = jest.fn()
  ctx.updateSelectedColumns = Autogen.methods.updateSelectedColumns.bind(ctx)
  await ctx.fetchData(); expect(ctx.columns.some(c => c.key === 'virtualsize')).toBe(true)
  ctx.updateSelectedColumns('virtualsize'); expect(ctx.columns.some(c => c.key === 'virtualsize')).toBe(false)
  store.getters.customColumns.user['/snapshot'] = ['name', 'intervaltype', 'physicalsize', 'virtualsize']; ctx.listSchemaScope = null
  await ctx.fetchData(); expect(ctx.columns.some(c => c.key === 'virtualsize')).toBe(false)
})
