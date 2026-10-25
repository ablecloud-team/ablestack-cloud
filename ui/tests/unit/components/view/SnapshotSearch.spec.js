// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { shallowMount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import SnapshotSearch from '@/components/view/SnapshotSearch'
import { getAPI } from '@/api'
import * as search from '@/utils/storageSnapshotSearch'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
const volume = i => ({ id: 'volume-' + i, name: 'Volume ' + i, account: 'owner', domainid: 'domain', zoneid: 'zone' })
let wrappers; let store; let route
function render (options = {}) {
  const w = shallowMount(SnapshotSearch, {
    global: {
      renderStubDefaultSlot: true,
      mocks: { $store: store, $route: route, $t: key => key },
      directives: { 'ctrl-enter': {} },
      stubs: {
        AForm: !options.realForm,
        AInputSearch: { name: 'AInputSearch', template: '<div><slot name="addonBefore" /></div>' },
        APopover: { name: 'APopover', props: ['visible', 'placement'], template: '<div><slot /><slot v-if="visible" name="content" /></div>' }
      }
    }
  })
  wrappers.push(w); return w
}
beforeEach(() => {
  wrappers = []; jest.clearAllMocks()
  store = reactive({ getters: { userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: { listVolumes: {} } }, state: { user: { token: 'session' } } })
  route = reactive({ name: 'snapshot', path: '/snapshot', params: {}, query: { account: 'owner', domainid: 'domain', zoneid: 'zone' } })
  getAPI.mockResolvedValue({ listvolumesresponse: { count: 0 } })
})
afterEach(() => { wrappers.forEach(w => w.unmount()); jest.useRealTimers() })

test.each(['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY'])('period %s works without a volume and intersects recurring', interval => {
  expect(search.snapshotSearchError({ intervaltype: interval })).toBe('')
  expect(search.snapshotSearchError({ intervaltype: interval, snapshottype: 'RECURRING' })).toBe('')
  expect(search.snapshotSearchError({ intervaltype: interval, snapshottype: 'MANUAL' })).toBe('manual.interval')
})
test.each([
  [{ state: 'BackedUp' }, 'unsupported.state'], [{ locationtype: 'primary' }, 'location.unit'],
  [{ showunique: 'false', locationtype: 'primary', imagestoreid: 'image' }, 'location.store'],
  [{ storageid: 'pool', imagestoreid: 'image' }, 'stores'], [{ account: 'owner', projectid: 'p' }, 'owner.project'],
  [{ snapshottype: ['MANUAL'] }, 'invalid'], [{ intervaltype: 'YEARLY' }, 'invalid'],
  [{ 'tags[0].key': 'qa' }, 'tags'], [{ 'tags[x].key': 'qa' }, 'tags'],
  [{ 'tags[0].key': 'qa', 'tags[0].value': '1', 'tags[1].key': 'qa', 'tags[1].value': '2' }, 'tags']
])('invalid combination %j is explained', (query, error) => expect(search.snapshotSearchError(query)).toBe(error))
test('canonical URL, replace/reset, indexed tags and project/sort/page-size retention', () => {
  const query = { projectid: 'p', sortkey: 'name', sortorder: 'asc', page: '9', volumeid: 'v', account: 'old', state: 'old', 'tags[9].key': 'old', 'tags[9].value': 'old' }
  const form = { keyword: 'new', intervaltype: 'daily', showunique: 'false', locationtype: 'secondary', tags: [{ key: 'qa', value: 'new' }] }
  const result = search.snapshotSearchQuery(query, form, 50)
  expect(result).toEqual({ projectid: 'p', sortkey: 'name', sortorder: 'asc', page: '1', pagesize: '50', keyword: 'new', q: 'new', intervaltype: 'DAILY', showunique: 'false', locationtype: 'secondary', 'tags[0].key': 'qa', 'tags[0].value': 'new' })
  expect(search.snapshotSearchForm(result)).toMatchObject({ keyword: 'new', intervaltype: 'DAILY', tags: form.tags })
  expect(search.snapshotSearchQuery(result, {}, 50)).toEqual({ projectid: 'p', sortkey: 'name', sortorder: 'asc', page: '1', pagesize: '50' })
  expect(search.normalizeSnapshotSearch({ q: 'old' })).toEqual({ keyword: 'old', q: 'old' })
})
test('storage URLs cannot silently become unfiltered for a restricted user', () => {
  expect(search.snapshotSearchPermissionError({ storageid: 'pool' }, { userInfo: { roletype: 'User' }, apis: { listStoragePools: {} } })).toBe('storage.permission')
  expect(search.snapshotSearchPermissionError({ imagestoreid: 'image' }, store.getters)).toBe('storage.permission')
})
test('candidate paging requests 20 + 4 within owner/Zone; VM filter popover and UUID retention', async () => {
  route.query.volumeid = 'deleted-volume'
  getAPI.mockResolvedValueOnce({ listvolumesresponse: { count: 24, volume: Array.from({ length: 20 }, (_, i) => volume(i)) } }).mockResolvedValueOnce({ listvolumesresponse: { count: 24, volume: Array.from({ length: 4 }, (_, i) => volume(i + 20)) } })
  const w = render(); w.vm.open(); await flush()
  const field = w.vm.pickers[0]; await w.vm.load(field, false); await flush()
  expect(w.vm.candidates.volumeid.rows).toHaveLength(24)
  expect(getAPI.mock.calls[1]).toEqual(['listVolumes', { listall: true, page: 2, pagesize: 20, account: 'owner', domainid: 'domain', ignoreproject: true, zoneid: 'zone' }, { preserveOnFailure: true, timeout: 20000 }])
  expect(w.findComponent({ name: 'APopover' }).props('placement')).toBe('bottomRight')
  expect(w.findComponent({ name: 'MoldDialog' }).exists()).toBe(false)
  expect(w.vm.form.volumeid).toBe('deleted-volume')
  expect(w.vm.blocked).toBe(false)
})
test('failure is distinct from empty; retry recovers; denial clears candidates and selection', async () => {
  getAPI.mockRejectedValueOnce(new Error('503'))
  const w = render(); w.vm.open(); await flush(); expect(w.vm.candidates.volumeid.error).toBe('failed'); expect(w.vm.blocked).toBe(true)
  await w.vm.load(w.vm.pickers[0], true); expect(w.vm.candidates.volumeid.error).toBe(''); expect(w.vm.blocked).toBe(false)
  w.vm.form.volumeid = 'selected'; getAPI.mockRejectedValueOnce({ response: { status: 403 } })
  await w.vm.load(w.vm.pickers[0], true); expect(w.vm.form.volumeid).toBeUndefined(); expect(w.vm.candidates.volumeid.error).toBe('denied')
})
test('administrator volume candidates show the volume name, not the backend image path', async () => {
  getAPI.mockResolvedValue({ listvolumesresponse: { count: 1, volume: [{ ...volume(1), path: 'backend-image-uuid' }] } })
  const w = render(); w.vm.open(); await flush()
  expect(w.text()).toContain('Volume 1'); expect(w.text()).not.toContain('backend-image-uuid')
})
test('account candidates request the exact domain without listall forced recursion', async () => {
  store.getters.apis = { listAccounts: {} }
  getAPI.mockResolvedValue({ listaccountsresponse: { count: 1, account: [{ id: 'owner-id', name: 'owner', domainid: 'domain' }] } })
  const w = render(); w.vm.open(); await flush()
  expect(getAPI).toHaveBeenCalledWith('listAccounts', { listall: false, domainid: 'domain', isrecursive: false, page: 1, pagesize: 20 }, { preserveOnFailure: true, timeout: 20000 })
  expect(w.vm.form.account).toBe('owner'); expect(w.vm.blocked).toBe(false)
})
test('late owner and closed-dialog responses never repopulate candidates', async () => {
  let old; getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve }))
  const w = render(); w.vm.open(); await flush(); w.vm.form.account = 'other'; w.vm.change('account'); await flush()
  old({ listvolumesresponse: { count: 1, volume: [volume(1)] } }); await flush()
  expect(w.vm.candidates.volumeid.rows).toEqual([])
  let late; getAPI.mockImplementationOnce(() => new Promise(resolve => { late = resolve }))
  w.vm.load(w.vm.pickers[0], true); w.vm.close(); late({ listvolumesresponse: { count: 1, volume: [volume(1)] } }); await flush()
  expect(w.vm.candidates.volumeid.rows).toEqual([])
})
test('project change invalidates pending lookup and closes owner dialog', async () => {
  let old; getAPI.mockImplementationOnce(() => new Promise(resolve => { old = resolve }))
  const w = render(); w.vm.open(); store.getters.project = { id: 'new-project' }; await flush()
  old({ listvolumesresponse: { count: 1, volume: [volume(1)] } }); await flush()
  expect(w.vm.visible).toBe(false); expect(w.vm.candidates.volumeid.rows).toEqual([])
  w.vm.open(); await flush(); expect(getAPI.mock.calls.at(-1)[1]).toMatchObject({ projectid: 'new-project' }); expect(getAPI.mock.calls.at(-1)[1].account).toBeUndefined()
})
test.each([
  { count: 2, volume: [volume(1), volume(1)] }, { count: 1, volume: [] }, { count: 1, volume: [{ ...volume(1), account: 'foreign' }] }
])('candidate contract/scope violation blocks submission %j', async response => {
  getAPI.mockResolvedValueOnce({ listvolumesresponse: response }); const w = render(); w.vm.open(); await flush(); expect(w.vm.blocked).toBe(true); expect(w.vm.candidates.volumeid.rows).toEqual([])
})
test('changed page count/duplicate row is rejected, rather than incomplete list', async () => {
  getAPI.mockResolvedValueOnce({ listvolumesresponse: { count: 2, volume: [volume(1)] } }).mockResolvedValueOnce({ listvolumesresponse: { count: 2, volume: [volume(1)] } })
  const w = render(); w.vm.open(); await flush(); await w.vm.load(w.vm.pickers[0], false); expect(w.vm.candidates.volumeid.error).toBe('changed'); expect(w.vm.blocked).toBe(true)
})
test('dependent filters normalize and reset emits only explicit reset', async () => {
  const w = render(); w.vm.open(); await flush(); Object.assign(w.vm.form, { intervaltype: 'DAILY', snapshottype: 'MANUAL', showunique: 'true', locationtype: 'primary' })
  w.vm.change('snapshottype'); expect(w.vm.form.intervaltype).toBeUndefined(); w.vm.change('showunique'); expect(w.vm.form.locationtype).toBeUndefined()
  w.vm.form.intervaltype = 'DAILY'; w.vm.change('intervaltype'); expect(w.vm.form.snapshottype).toBe('RECURRING')
  w.vm.apply(); expect(w.emitted('search')[0][0].snapshotSearch.intervaltype).toBe('DAILY')
  w.vm.reset(); expect(w.emitted('search')[1][0]).toEqual({ snapshotSearch: {} })
})

test('native search form submit applies the current filters once and closes the popover', async () => {
  const w = render({ realForm: true }); w.vm.open(); await flush()
  w.vm.form.name = 'exact-snapshot'; w.vm.form.snapshottype = 'MANUAL'
  await w.find('form').trigger('submit'); await flush()
  expect(w.emitted('search')).toHaveLength(1)
  expect(w.emitted('search')[0][0].snapshotSearch).toMatchObject({ name: 'exact-snapshot', snapshottype: 'MANUAL' })
  expect(w.vm.visible).toBe(false)
})
