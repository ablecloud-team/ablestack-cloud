// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
import Autogen from '@/views/AutogenView'
import store from '@/store'
import { s2sGatewayQuery } from '@/utils/s2sGatewaySearch'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: {}, state: { user: { token: 'session' } } }))

const id = '11111111-1111-4111-8111-111111111111'
const linked = { id, publicip: '192.0.2.10', account: 'admin', domainid: id, vpcid: id, fordisplay: true }
const reply = row => ({ listvpngatewaysresponse: { count: 1, vpngateway: [row] } })
let c
function route (raw = {}) { const query = s2sGatewayQuery(raw, 20, store.getters); c.$route = { name: 's2svpn', path: '/s2svpn', fullPath: '/s2svpn?' + new URLSearchParams(query), params: {}, query, meta: { name: 's2svpn', permission: ['listVpnGateways'], columns: ['publicip', 'account', 'domain'], actions: [] } } }
beforeEach(() => {
  jest.clearAllMocks()
  store.state = { user: { token: 'session' } }; store.getters = { defaultListViewPageSize: 20, userInfo: { id: 'admin', roletype: 'Admin', domainid: id }, project: {}, apis: { listVpnGateways: {} }, customColumns: {}, metrics: false, listAllProjects: false }
  c = { $store: store, $router: { replace: jest.fn(), push: jest.fn() }, $refs: { listview: { resetSelection: jest.fn() } }, $emit: jest.fn(), $t: key => key, $getApiParams: () => Object.fromEntries(['id', 'vpcid', 'keyword', 'domainid', 'account', 'projectid', 'page', 'pagesize', 'listall', 'fordisplay', 'displayall'].map(k => [k, {}])), $showIcon: () => false, $i18n: { locale: 'ko_KR' }, $message: { info: jest.fn(), error: jest.fn() }, $notifyError: jest.fn(), $nextTick: fn => fn() }
  route(); Object.assign(c, Autogen.data.call(c)); Object.entries(Autogen.methods).forEach(([key, fn]) => { c[key] = fn.bind(c) }); c.dataView = false; c.updateSelectedColumns = jest.fn()
  for (const key of ['publicIpDetail', 'publicIpAuth', 'asnSearchAuth', 'isS2sSearchList', 's2sSearchApi', 's2sGatewaySearchAuth', 's2sDisplayFilterId']) Object.defineProperty(c, key, { get: () => Autogen.computed[key].call(c) })
  getAPI.mockResolvedValue(reply(linked))
})
test('unsupported or invalid filter URL cannot broaden the request', async () => {
  route({ command: 'deleteVpnGateway' }); await c.fetchData(); expect(c.s2sGatewaySearchFailure).toBe('invalid'); expect(getAPI).not.toHaveBeenCalled()
  route({ id: 'wrong' }); await c.fetchData(); expect(c.s2sGatewaySearchFailure).toBe('uuid'); expect(getAPI).not.toHaveBeenCalled()
})
test('same-scope refresh failure retains rows with failure, while scope changes clear them', async () => {
  await c.fetchData(); expect(c.items).toMatchObject([linked])
  getAPI.mockRejectedValueOnce(Error('offline')); await c.fetchData(); expect(c.items).toMatchObject([linked]); expect(c.s2sGatewaySearchFailure).toBe('lookup')
  route({ keyword: 'missing' }); getAPI.mockRejectedValueOnce(Error('offline')); await c.fetchData(); expect(c.items).toEqual([])
})
test('late keyword response cannot replace current rows/count; search resets page but keeps exact filters', async () => {
  let complete; route({ keyword: 'old' }); getAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve })); const old = c.fetchData(); await Promise.resolve(); await Promise.resolve()
  route({ keyword: 'new' }); getAPI.mockResolvedValueOnce({ listvpngatewaysresponse: { count: 0 } }); await c.fetchData()
  complete(reply(linked)); await old; expect(c.items).toEqual([]); expect(c.itemCount).toBe(0)
  route({ id, page: '2' }); c.onSearch({ searchQuery: '192' }); expect(c.$router.push.mock.calls[0][0].query).toMatchObject({ id, keyword: '192', page: '1' })
})
test('a foreign project URL is blocked and a real project switch discards the prior filter', async () => {
  const next = '22222222-2222-4222-8222-222222222222'; route({ projectid: next }); await c.fetchData(); expect(c.s2sGatewaySearchFailure).toBe('scope'); expect(getAPI).not.toHaveBeenCalled()
  route({ keyword: 'old' }); const before = c.s2sGatewaySearchAuth; store.getters.project = { id: next }
  Autogen.watch.s2sGatewaySearchAuth.handler.call(c, c.s2sGatewaySearchAuth, before)
  expect(c.$router.replace.mock.calls[0][0].query).toEqual({ projectid: next, fordisplay: 'all', page: '1', pagesize: '20' }); expect(c.items).toEqual([])
})

test('canonical project URL updates never change the global project or create duplicate filter chips', () => {
  const from = c.$route; route({ keyword: 'new' }); c.switchProject = jest.fn(); c.fetchData = jest.fn(); c.clearAutoRefresh = jest.fn(); c.scheduleAutoRefresh = jest.fn()
  Autogen.watch.$route.handler.call(c, c.$route, from)
  expect(c.fetchData).toHaveBeenCalledTimes(1); expect(c.switchProject).not.toHaveBeenCalled()
  expect(Autogen.computed.activeFiltersList.call(c)).toEqual([])
})

test('permission revocation clears cached rows immediately and rejects an older successful response', async () => {
  await c.fetchData(); expect(c.items).toHaveLength(1)
  let complete; getAPI.mockReturnValueOnce(new Promise(resolve => { complete = resolve })); const pending = c.fetchData(); await Promise.resolve(); await Promise.resolve()
  const before = c.s2sGatewaySearchAuth; store.getters.apis = {}
  Autogen.watch.s2sGatewaySearchAuth.handler.call(c, c.s2sGatewaySearchAuth, before)
  expect(c.items).toEqual([]); expect(c.s2sGatewaySearchFailure).toBe('permission')
  complete(reply(linked)); await pending; expect(c.items).toEqual([]); expect(c.itemCount).toBe(0)
})

test('role and session changes discard the previous applied search', () => {
  route({ keyword: 'owner-specific', id }); const before = c.s2sGatewaySearchAuth; store.getters.userInfo.roletype = 'DomainAdmin'
  Autogen.watch.s2sGatewaySearchAuth.handler.call(c, c.s2sGatewaySearchAuth, before)
  expect(c.$router.replace.mock.calls[0][0].query).toEqual({ projectid: '0', page: '1', pagesize: '20' }); expect(c.items).toEqual([])
})

test('breadcrumb display selector preserves owner and keyword and resets only pagination', () => {
  route({ fordisplay: 'all', page: '3', keyword: 'chosen', domainid: id, account: 'admin' })
  expect(Autogen.computed.filterValue.call(c)).toBe('all')
  c.onSearch = jest.fn(); Autogen.methods.changeFilter.call(c, 'false')
  expect(c.onSearch).toHaveBeenCalledWith({ s2sGatewaySearch: expect.objectContaining({ fordisplay: 'false', keyword: 'chosen', account: 'admin', domainid: id }) })
})
