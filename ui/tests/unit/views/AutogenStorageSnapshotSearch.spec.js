// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import AutogenView from '@/views/AutogenView'
import ListView from '@/components/view/ListView'
import SearchFilter from '@/components/view/SearchFilter'
import { getAPI } from '@/api'
jest.mock('@/store', () => ({ getters: { userInfo: { roletype: 'Admin' } } }))
jest.mock('@/api', () => ({ getAPI: jest.fn() }))

test('advanced search replaces old fields and reset keeps sort/project/page size', () => {
  const context = { $route: { name: 'snapshot', query: { projectid: 'p', sortkey: 'name', sortorder: 'asc', page: '9', volumeid: 'old', 'tags[0].key': 'qa', 'tags[0].value': 'old' } }, pageSize: 50, searchParams: {}, $router: { push: jest.fn() } }
  AutogenView.methods.onSearch.call(context, { snapshotSearch: { intervaltype: 'DAILY', snapshottype: 'RECURRING' } })
  expect(context.$router.push.mock.calls[0][0].query).toEqual({ projectid: 'p', sortkey: 'name', sortorder: 'asc', page: '1', pagesize: '50', intervaltype: 'DAILY', snapshottype: 'RECURRING' })
  AutogenView.methods.onSearch.call(context, { snapshotSearch: {} })
  expect(context.$router.push.mock.calls[1][0].query).toEqual({ projectid: 'p', sortkey: 'name', sortorder: 'asc', page: '1', pagesize: '50' })
})
test.each([{ locationtype: 'primary' }, { state: 'BackedUp' }, { snapshottype: 'MANUAL', intervaltype: 'DAILY' }, { storageid: 'admin-pool' }])('invalid/unsupported URL clears old results without querying %j', query => {
  const context = { $route: { name: 'snapshot', params: {}, query: { ...query, projectid: '0', sortkey: 'created', sortorder: 'desc', page: '1' } }, $store: { getters: { userInfo: { roletype: 'User' }, apis: {} } }, listRequestVersion: 7, items: [{ id: 'old' }], resetSelection: jest.fn(), $router: { replace: jest.fn() } }
  AutogenView.methods.fetchData.call(context)
  expect(context.$router.replace).not.toHaveBeenCalled(); expect(context.items).toEqual([]); expect(context.snapshotSearchFailure).toBeTruthy(); expect(context.listRequestVersion).toBe(8)
})
test('storage copy mode has distinct reference keys and no bulk selection', () => {
  const context = { $route: { name: 'snapshot', query: { showunique: 'false' }, params: {} } }
  expect(ListView.methods.listRowKey.call(context, { id: 'same', copy: { id: 'primary' } })).toBe('primary')
  expect(ListView.methods.listRowKey.call(context, { id: 'same', copy: { id: 'secondary' } })).toBe('secondary')
  expect(ListView.methods.enableGroupAction.call(context)).toBe(false)
  context.$route.query.showunique = 'true'; expect(ListView.methods.listRowKey.call(context, { id: 'same', copy: { id: 'primary' } })).toBe('same')
})
test('project URL changes delegate to the snapshot-specific synchronizer', () => {
  const from = { name: 'snapshot', path: '/snapshot', fullPath: '/snapshot', query: {}, meta: { name: 'snapshot' }, params: {} }
  const to = { ...from, fullPath: '/snapshot?projectid=p', query: { projectid: 'p' } }
  const context = { $route: to, $store: { getters: {} }, resetSelection: jest.fn(), clearAutoRefresh: jest.fn(), fetchData: jest.fn(), scheduleAutoRefresh: jest.fn(), switchProject: jest.fn() }
  AutogenView.watch.$route.call(context, to, from)
  expect(context.fetchData).toHaveBeenCalled(); expect(context.switchProject).toHaveBeenCalledWith('p')
  const syncSnapshotProject = jest.fn()
  AutogenView.methods.switchProject.call({ ...context, syncSnapshotProject }, 'p')
  expect(syncSnapshotProject).toHaveBeenCalledWith('p')
  expect(to.query.projectid).toBe('p')
})
test('project synchronization retains the URL, avoids repeat lookups and restores explicit default scope', async () => {
  const context = { $route: { name: 'snapshot', params: {}, fullPath: '/snapshot?projectid=p', query: { projectid: 'p' } }, $store: { getters: { project: {}, userInfo: { id: 'user' } }, state: { user: { token: 'token' } }, dispatch: jest.fn() }, fetchData: jest.fn(), $notifyError: jest.fn(), $t: key => key }
  context.$store.dispatch.mockImplementation((action, value) => { if (action === 'SetProject') context.$store.getters.project = value; else context.$store.getters.listAllProjects = value })
  getAPI.mockResolvedValue({ listprojectsresponse: { project: [{ id: 'p', name: 'project' }] } })
  await AutogenView.methods.syncSnapshotProject.call(context, 'p')
  expect(context.$store.getters.project.id).toBe('p'); expect(context.$route.query.projectid).toBe('p')
  getAPI.mockClear(); await AutogenView.methods.syncSnapshotProject.call(context, 'p'); expect(getAPI).not.toHaveBeenCalled()
  context.$route.fullPath = '/snapshot?projectid=0'; context.$route.query.projectid = '0'
  await AutogenView.methods.syncSnapshotProject.call(context, '0')
  expect(context.$store.getters.project).toEqual({}); expect(context.$route.query.projectid).toBe('0'); expect(getAPI).not.toHaveBeenCalled()
})
test('a late project lookup cannot switch the newer URL scope', async () => {
  let complete; getAPI.mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
  const context = { $route: { name: 'snapshot', params: {}, fullPath: '/snapshot?projectid=p', query: { projectid: 'p' } }, $store: { getters: { project: {}, userInfo: { id: 'user' } }, state: { user: { token: 'token' } }, dispatch: jest.fn() }, fetchData: jest.fn(), $notifyError: jest.fn() }
  const pending = AutogenView.methods.syncSnapshotProject.call(context, 'p')
  context.$route.fullPath = '/snapshot?projectid=other'; context.$route.query.projectid = 'other'
  complete({ listprojectsresponse: { project: [{ id: 'p', name: 'old' }] } }); await pending
  expect(context.$store.dispatch).not.toHaveBeenCalled(); expect(context.fetchData).not.toHaveBeenCalled()
})
test('all-project toggle is represented in URL, resetting only the page', () => {
  const context = { $route: { name: 'snapshot', params: {}, query: { projectid: '0', keyword: 'qa', page: '3', pagesize: '50', sortkey: 'name' } }, $store: { getters: { project: {} } }, $router: { push: jest.fn() }, fetchData: jest.fn() }
  AutogenView.watch['$store.getters.listAllProjects'].call(context, true)
  expect(context.$router.push).toHaveBeenCalledWith({ query: { ...context.$route.query, projectid: '-1', page: '1' } })
})
test('volume chip lookup retains deleted UUID and sends current ACL/Zone, no widened lookup', async () => {
  const context = { apiName: 'listSnapshots', $route: { query: { account: 'owner', domainid: 'd', zoneid: 'z' } }, $store: { getters: { apis: { listVolumes: {} } } }, $isValidUuid: () => true }
  getAPI.mockResolvedValue({ listvolumesresponse: {} })
  expect(await SearchFilter.methods.getDynamicFieldValue.call(context, 'volumeid', 'deleted')).toBe('deleted')
  expect(getAPI).toHaveBeenLastCalledWith('listVolumes', { id: 'deleted', listall: true, account: 'owner', domainid: 'd', zoneid: 'z' }, { preserveOnFailure: true, timeout: 20000 })
  delete context.$store.getters.apis.listVolumes; getAPI.mockClear()
  expect(await SearchFilter.methods.getDynamicFieldValue.call(context, 'volumeid', 'deleted')).toBe('deleted'); expect(getAPI).not.toHaveBeenCalled()
})
