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
import AutogenView from '@/views/AutogenView'
import SearchFilter from '@/components/view/SearchFilter'
import compute from '@/config/section/compute'
import store from '@/store'
import en from '@public/locales/en.json'
import ko from '@public/locales/ko_KR.json'

jest.mock('@/store', () => ({ getters: { userInfo: { roletype: 'Admin' }, listAllProjects: false } }))

const snapshot = compute.children.find(section => section.name === 'vmsnapshot')
const view = (query = {}) => ({ $route: { name: 'vmsnapshot', meta: snapshot, query }, filters: snapshot.filters() })

test.each([{}, { filter: 'self' }, { filter: 'unsupported' }, { state: 'Ready' }, { filter: 'error' }])('snapshot filter has a valid default and restores the URL state %j', query => {
  const expected = query.state === 'Ready' ? 'ready' : query.filter === 'error' ? 'error' : 'all'
  expect(AutogenView.computed.filterValue.call(view(query))).toBe(expected)
})

test.each([['ko_KR', ko], ['en', en]])('all snapshot filter options use real %s status translations', (locale, messages) => {
  const context = { ...view(), $t: key => messages[key] || key }
  const labels = snapshot.filters().map(filter => AutogenView.methods.getFilterLabel.call(context, filter))
  expect(labels).toEqual(['label.all', 'state.ready', 'state.creating', 'state.allocated', 'state.reverting', 'state.expunging', 'state.error'].map(key => messages[key]))
  expect(labels.every(label => label && !/^(label|state)\./.test(label))).toBe(true)
})

test.each([['vm', 'running', 'label.running'], ['comment', 'all', 'label.filter.annotations.all']])('existing %s filter labels keep their translation namespace', (name, filter, key) => {
  expect(AutogenView.methods.getFilterLabel.call({ $route: { name, meta: {} }, $t: value => value }, filter)).toBe(key)
})

test('returning to All removes only the snapshot state while preserving search and ACL scope', () => {
  const context = { ...view({ state: 'Error', filter: 'error', domainid: 'domain-a', account: 'account-a', projectid: 'project-a', keyword: 'vm-a', current: 'true', type: 'DiskAndMemory', page: '2' }), pageSize: 20, $router: { push: jest.fn() } }
  AutogenView.methods.changeFilter.call(context, 'ready')
  expect(context.$router.push.mock.calls[0][0].query.state).toBe('Ready')
  context.$route.query = context.$router.push.mock.calls[0][0].query
  AutogenView.methods.changeFilter.call(context, 'all')
  expect(context.$router.push.mock.calls[1][0].query).toEqual({ filter: 'all', domainid: 'domain-a', account: 'account-a', projectid: 'project-a', keyword: 'vm-a', current: 'true', type: 'DiskAndMemory', page: '1', pagesize: '20' })
})

test.each(['Admin', 'DomainAdmin', 'User'])('snapshot columns follow VM account/zone visibility for %s without a domain column', role => {
  store.getters.userInfo.roletype = role
  store.getters.listAllProjects = false
  const columns = snapshot.columns()
  expect(columns).toContain('zonename')
  expect(columns).not.toContain('domain')
  expect(columns.includes('account')).toBe(role !== 'User')
  store.getters.listAllProjects = true
  expect(snapshot.columns()).toContain('project')
})

test.each(['all', 'ready', 'self'])('snapshot filter tags exclude the internal %s selector while preserving the state and search conditions', filter => {
  const query = { filter, state: 'Ready', current: 'true', account: 'account-a' }
  expect(AutogenView.computed.activeFiltersList.call(view(query))).toEqual([
    { key: 'state', value: 'Ready', isTag: false },
    { key: 'current', value: 'true', isTag: false },
    { key: 'account', value: 'account-a', isTag: false }
  ])
})

test('other lists retain their existing filter tags', () => {
  expect(AutogenView.computed.activeFiltersList.call({ $route: { name: 'vm', query: { filter: 'running' } } })).toEqual([{ key: 'filter', value: 'running', isTag: false }])
})

test.each([['ko_KR', ko], ['en', en]])('snapshot state tags use real %s status translations', (locale, messages) => {
  const context = { apiName: snapshot.permission[0], $t: key => messages[key] || key }
  for (const state of ['Ready', 'Creating', 'Allocated', 'Reverting', 'Expunging', 'Error']) {
    const label = SearchFilter.methods.getState.call(context, state)
    expect(label).toBe(messages['state.' + state.toLowerCase()])
    expect(label).not.toMatch(/^(label|state)\./)
  }
})

test('unknown snapshot states remain readable', () => {
  expect(SearchFilter.methods.getState.call({ apiName: snapshot.permission[0], $t: key => key }, 'FutureState')).toBe('FutureState')
})

test('volume state tags retain their existing translation', () => {
  expect(SearchFilter.methods.getState.call({ apiName: 'listVolumes', $t: key => key }, 'Ready')).toBe('label.isready')
})

test('removing the snapshot state tag resets the selector and preserves search and ACL scope', () => {
  const context = { ...view({ filter: 'ready', state: 'Ready', domainid: 'domain-a', account: 'account-a', projectid: 'project-a', keyword: 'vm-a', current: 'true', type: 'DiskAndMemory', page: '2' }), pageSize: 20, $router: { push: jest.fn() } }
  AutogenView.methods.removeFilter.call(context, { key: 'state', value: ko['state.ready'], isTag: false })
  expect(context.$router.push.mock.calls[0][0].query).toEqual({ domainid: 'domain-a', account: 'account-a', projectid: 'project-a', keyword: 'vm-a', current: 'true', type: 'DiskAndMemory', page: '1', pagesize: '20' })
})

test('other lists retain their existing state tag removal behavior', () => {
  const context = { $route: { name: 'vm', query: { filter: 'running', state: 'Running' } }, pageSize: 20, $router: { push: jest.fn() } }
  AutogenView.methods.removeFilter.call(context, { key: 'state', isTag: false })
  expect(context.$router.push.mock.calls[0][0].query).toEqual({ filter: 'running', page: '1', pagesize: '20' })
})

test('sorting metadata is hidden while real snapshot filters remain visible', () => {
  expect(AutogenView.computed.activeFiltersList.call(view({ sortkey: 'displayname', sortorder: 'asc', state: 'Ready', account: 'account-a' }))).toEqual([
    { key: 'state', value: 'Ready', isTag: false },
    { key: 'account', value: 'account-a', isTag: false }
  ])
})

test.each(['displayname', 'state', 'type', 'current', 'created'])('sorting %s preserves page size, resets page and retains search scope', field => {
  const context = { ...view({ page: '2', state: 'Ready', keyword: 'vm-a', account: 'account-a' }), pageSize: 50, $router: { push: jest.fn() } }
  AutogenView.methods.handleTableChange.call(context, {}, {}, { field, order: 'ascend' })
  expect(context.$router.push.mock.calls[0][0].query).toEqual({ page: '1', pagesize: '50', state: 'Ready', keyword: 'vm-a', account: 'account-a', sortkey: field, sortorder: 'asc' })
  AutogenView.methods.handleTableChange.call(context, {}, {}, { field, order: 'descend' })
  expect(context.$router.push.mock.calls[1][0].query.sortorder).toBe('desc')
})

test.each([undefined, 'NaN', '0', '-1', '2.5'])('a page-only or invalid page-size URL keeps the selected page size (%s)', pagesize => {
  const context = { pageSize: 20, resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn() }
  const query = { page: '1', sortkey: 'displayname', sortorder: 'asc', ...(pagesize === undefined ? {} : { pagesize }) }
  AutogenView.watch.$route.call(context, { path: '/vmsnapshot', fullPath: '/vmsnapshot?page=1', query }, { fullPath: '/vmsnapshot' })
  expect(context.pageSize).toBe(20)
  expect(context.page).toBe(1)
  expect(context.fetchData).toHaveBeenCalledTimes(1)
})

test('page size changes without a page parameter are applied independently', () => {
  const context = { pageSize: 20, resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn() }
  AutogenView.watch.$route.call(context, { path: '/vmsnapshot', fullPath: '/vmsnapshot?pagesize=50', query: { pagesize: '50' } }, { fullPath: '/vmsnapshot' })
  expect(context.pageSize).toBe(50)
  expect(context.page).toBe(1)
})
