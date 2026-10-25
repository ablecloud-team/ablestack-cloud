// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { getAPI, callAPI } from '@/api'
import { storageSnapshotListQuery } from '@/utils/storageSnapshotList'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))
const id = '12345678-1234-1234-1234-123456789abc'
const row = { id, name: 'before', account: 'admin', domainid: id, intervaltype: 'MANUAL', physicalsize: 1373634, virtualsize: 1073741824, snapshotcontext: { version: 1, sourceavailable: false, revertreason: 'SOURCE_VOLUME_UNAVAILABLE' }, volumeid: id }
const reply = (rows = [row], count = rows.length) => ({ listsnapshotsresponse: { snapshot: rows, count } })
let ctx
beforeEach(() => {
  jest.clearAllMocks()
  store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listSnapshots: {}, deleteSnapshot: {} }, customColumns: {}, features: {}, metrics: false, listAllProjects: false }
  store.state.user.token = 'session'
  const query = storageSnapshotListQuery({ projectid: '0' })
  ctx = { $store: store, $route: { name: undefined, path: '/snapshot', fullPath: '/snapshot?' + new URLSearchParams(query), params: { id }, query, meta: { name: 'snapshot', permission: ['listSnapshots'], columns: ['name', 'intervaltype', 'physicalsize', 'virtualsize'], actions: [{ api: 'deleteSnapshot', dataView: true, groupAction: true, toolbarLabel: 'delete' }], searchFilters: ['name'] } }, $router: { replace: jest.fn(), push: jest.fn() }, $refs: { listview: { resetSelection: jest.fn() } }, $emit: jest.fn(), $t: key => key, $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn() }, $notifyError: jest.fn(), $nextTick: callback => callback() }
  Object.assign(ctx, Autogen.data.call(ctx))
  Object.entries(Autogen.methods).forEach(([key, fn]) => { ctx[key] = fn.bind(ctx) })
  for (const key of ['isSnapshotList', 'isSnapshotCopyList', 'isSnapshotDetail', 'isSnapshotReadView', 'storageActionReady']) Object.defineProperty(ctx, key, { get: () => Autogen.computed[key].call(ctx) })
  ctx.updateSelectedColumns = jest.fn(); getAPI.mockImplementation(async (cmd, args) => reply(args.showunique ? [row] : [{ ...row, copy: { id: 'copy', availability: 'Ready' } }]))
})

test('same detail refresh retains the card and tags until an atomic source/copy response succeeds', async () => {
  await ctx.fetchData(); const old = ctx.resource; let finish; getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const pending = ctx.fetchData({ irefresh: true }); expect(ctx.resource).toBe(old); expect(ctx.listRequestPending).toBe(true); expect(ctx.storageActionReady).toBe(false)
  await Promise.resolve(); finish({ listsnapshotsresponse: {} }); await pending
  expect(ctx.resource).toEqual({}); expect(ctx.listRefreshError).toBe(true); expect(ctx.listLoadedScope).toBeNull()
})
test('503 retains detail while 403 and not-found clear all resource data', async () => {
  await ctx.fetchData(); const old = ctx.resource; getAPI.mockRejectedValue({ response: { status: 503 } }); await ctx.fetchData()
  expect(ctx.resource).toBe(old); expect(ctx.listRefreshError).toBe(true); expect(ctx.storageActionReady).toBe(false)
  getAPI.mockRejectedValue({ response: { status: 403 } }); await ctx.fetchData(); expect(ctx.resource).toEqual({})
})
test('tab changes keep the detail identity scope, while session/project/role/permissions invalidate it', () => {
  const first = ctx.listScope(); ctx.$route.query.tab = 'zones'; ctx.$route.fullPath += '&tab=zones'; expect(ctx.listScope()).toBe(first)
  store.state.user.token = 'new'; expect(ctx.listScope()).not.toBe(first); store.state.user.token = 'session'
  store.getters.userInfo.roleid = 'new'; expect(ctx.listScope()).not.toBe(first); delete store.getters.userInfo.roleid
  store.getters.apis.listVolumes = {}; expect(ctx.listScope()).not.toBe(first)
})
test('detail source/copy GETs use preservation and action readiness follows complete data', async () => {
  await ctx.fetchData(); expect(ctx.resource.id).toBe(id); expect(ctx.resource.snapshotdetail.copies).toHaveLength(1); expect(ctx.storageActionReady).toBe(true)
  expect(getAPI.mock.calls.every(call => call[2]?.preserveOnFailure)).toBe(true); expect(callAPI).not.toHaveBeenCalled()
})
