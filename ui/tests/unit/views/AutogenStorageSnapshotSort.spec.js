// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import AutogenView from '@/views/AutogenView'
import { snapshotSortKeys, storageSnapshotListQuery, storageSnapshotSortColumn, storageSnapshotSortQuery } from '@/utils/storageSnapshotList'
jest.mock('@/store', () => ({ getters: { userInfo: { roletype: 'Admin' }, listAllProjects: false } }))

const scope = { keyword: 'same', name: 'same', volumeid: 'volume', account: 'owner', domainid: 'domain', projectid: 'project', zoneid: 'zone', storageid: 'pool', imagestoreid: 'store', showunique: 'false', 'tags[0].key': 'qa', 'tags[0].value': 'sort', snapshottype: 'MANUAL', intervaltype: 'HOURLY', locationtype: 'secondary' }

test('legacy/direct links gain a canonical default without dropping search or ACL conditions', () => {
  expect(storageSnapshotListQuery(scope)).toEqual({ ...scope, page: '1', sortkey: 'created', sortorder: 'desc' })
  const query = storageSnapshotListQuery({ ...scope, sortkey: 'NAME', sortorder: 'ASC', page: '02', pagesize: '050' })
  expect(query).toEqual({ ...scope, sortkey: 'name', sortorder: 'asc', page: '2', pagesize: '50' })
  expect(storageSnapshotListQuery(query)).toEqual(query)
})

test.each(snapshotSortKeys)('server-only %s sorter matches URL arrows and resets to first page in both directions', key => {
  const context = { $route: { name: 'snapshot', query: { ...scope, page: '3' } }, pageSize: 50, $router: { push: jest.fn() } }
  for (const [order, direction] of [['ascend', 'asc'], ['descend', 'desc']]) {
    AutogenView.methods.handleTableChange.call(context, {}, {}, { field: key, order })
    const query = context.$router.push.mock.calls.at(-1)[0].query
    expect(query).toEqual({ ...scope, page: '1', pagesize: '50', sortkey: key, sortorder: direction })
    expect(storageSnapshotSortColumn(key, query)).toEqual({ sorter: true, sortOrder: order })
  }
})

test.each(['physicalsize', 'chainsize', 'parentname', 'zonename', 'unknown'])('copy-dependent/derived %s has no misleading local sorting', key => {
  expect(storageSnapshotSortColumn(key, { sortkey: 'name', sortorder: 'asc' })).toEqual({ sorter: false, sortOrder: null })
})

test.each([{ sortkey: 'name desc;--', sortorder: 'asc' }, { sortkey: ['name'], sortorder: [] }, { page: '-1', pagesize: 'NaN' }, { page: '2.5', pagesize: '0' }])('invalid route is normalized without a broken request %j', query => {
  const clean = storageSnapshotListQuery({ ...scope, ...query })
  expect(snapshotSortKeys).toContain(clean.sortkey)
  expect(['asc', 'desc']).toContain(clean.sortorder)
  expect(Number(clean.page)).toBeGreaterThan(0)
  expect(clean.volumeid).toBe('volume')
})

test('clearing a sorter restores created-desc default and retains all scopes', () => {
  expect(storageSnapshotSortQuery(scope, {}, 20)).toEqual({ ...scope, page: '1', pagesize: '20', sortkey: 'created', sortorder: 'desc' })
})

test('page size change resets the snapshot page while normal paging preserves it', () => {
  const context = { $route: { name: 'snapshot', meta: {}, query: { ...scope, sortkey: 'name', sortorder: 'asc' } }, pageSize: 20, $router: { push: jest.fn() } }
  AutogenView.methods.changePage.call(context, 2, 20)
  expect(context.$router.push.mock.calls.at(-1)[0].query.page).toBe(2)
  AutogenView.methods.changePage.call(context, 2, 50)
  expect(context.$router.push.mock.calls.at(-1)[0].query.page).toBe(1)
  AutogenView.methods.changePageSize.call(context, 2, 50)
  expect(context.$router.push.mock.calls.at(-1)[0].query).toEqual({ ...scope, page: 1, pagesize: 50, sortkey: 'name', sortorder: 'asc' })
})

test.each(['same', ''])('keyword search preserves ownership, volume, tags and sort while resetting page (%s)', value => {
  const context = { $route: { name: 'snapshot', query: { ...scope, page: '2', sortkey: 'name', sortorder: 'asc' }, meta: { searchFilters: () => ['name', 'account', 'domainid', 'zoneid', 'tags', 'imagestoreid', 'storageid'] } }, pageSize: 20, searchParams: {}, $router: { push: jest.fn() } }
  AutogenView.methods.onSearch.call(context, { searchQuery: value })
  const expected = { ...scope, page: '1', pagesize: '20', sortkey: 'name', sortorder: 'asc' }
  delete expected.keyword
  if (value) Object.assign(expected, { keyword: value, q: value })
  expect(context.$router.push).toHaveBeenCalledWith({ query: expected })
})
