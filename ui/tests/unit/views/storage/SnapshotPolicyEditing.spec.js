// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { webcrypto } from 'crypto'
import Form from '@/views/storage/FormSchedule'
import Dialog from '@/views/storage/SnapshotPolicyActionDialog'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/utils/timezone', () => ({ timeZone: () => Promise.resolve([]) }))
let store; let route; let wrappers
const originalCrypto = Object.getOwnPropertyDescriptor(global, 'crypto')
beforeAll(() => Object.defineProperty(global, 'crypto', { configurable: true, value: webcrypto }))
afterAll(() => { if (originalCrypto) Object.defineProperty(global, 'crypto', originalCrypto); else delete global.crypto })
const volume = { id: 'volume', account: 'owner', domainid: 'domain', zoneid: 'primary', zonename: 'Zone', name: '시험 볼륨' }
const base = () => ({ id: 'policy', volumeid: 'volume', intervaltype: 0, schedule: '0', timezone: 'UTC', maxsnaps: 3, fordisplay: false, zone: [{ id: 'primary' }, { id: 'extra', name: 'Extra' }], tags: [{ key: 'keep', value: '한글' }, { key: 'remove', value: 'yes' }] })
function mocks () { return { $store: store, $route: route, $t: k => k, $getApiParams: () => [], $notifyError: jest.fn(), $notification: { success: jest.fn() } } }
function render (policy = base(), validateEdit = jest.fn().mockResolvedValue()) {
  const wrapper = mount(Form, { props: { resource: volume, policy, validateEdit, resourceType: 'Volume', dataSource: [policy] }, attachTo: document.body, global: { mocks: mocks(), stubs: { ResourceIcon: true } } })
  wrappers.push(wrapper); return wrapper
}
function dialog (mode) {
  const wrapper = shallowMount(Dialog, { props: { resource: base(), currentAction: { policyMode: mode } }, global: { mocks: mocks(), stubs: { MoldDialog: { template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } } } })
  wrappers.push(wrapper); return wrapper
}
beforeEach(() => {
  wrappers = []; route = reactive({ path: '/snapshotpolicy' })
  store = reactive({ getters: { userInfo: { id: 'user', roletype: 'Admin' }, project: {}, apis: Object.fromEntries(['createSnapshotPolicy', 'createTags', 'listCapabilities', 'listAccounts', 'listResourceLimits', 'listSnapshotPolicies', 'listVolumes', 'listZones', 'updateSnapshotPolicy', 'queryAsyncJobResult'].map(k => [k, {}])) }, state: { user: { token: 'session' } } })
  postAPI.mockReset(); getAPI.mockReset(); postAPI.mockResolvedValue({ createsnapshotpolicyresponse: { snapshotpolicy: base() } })
  getAPI.mockImplementation(cmd => Promise.resolve({
    listSnapshotPolicies: { listsnapshotpoliciesresponse: { snapshotpolicy: [base()] } },
    listVolumes: { listvolumesresponse: { volume: [volume] } },
    listZones: { listzonesresponse: { zone: [{ id: 'primary' }, { id: 'extra', name: 'Extra' }] } },
    listCapabilities: { listcapabilitiesresponse: { capability: { snapshotpolicymax: { hourly: 8, daily: 8, weekly: 8, monthly: 8 } } } },
    listAccounts: { listaccountsresponse: { account: [{ name: 'owner', domainid: 'domain', accounttype: 1 }] } },
    listResourceLimits: { listresourcelimitsresponse: { resourcelimit: [] } },
    queryAsyncJobResult: { queryasyncjobresultresponse: { jobstatus: 1 } }
  }[cmd] || {}))
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises(); document.body.innerHTML = '' })

test.each([[0, '0'], [1, '59:23'], [2, '59:23:7'], [3, '59:23:28']])('actual form preloads interval %s, pins identity and preserves stopped state and tags', async (intervaltype, schedule) => {
  const policy = { ...base(), intervaltype, schedule }; const w = render(policy); await flushPromises()
  expect(w.vm.form.intervaltype).toBe(['hourly', 'daily', 'weekly', 'monthly'][intervaltype]); expect(w.vm.handleShowButton()).toBe(true)
  expect(w.vm.isIntervalDisabled(w.vm.form.intervaltype)).toBe(false); expect(w.vm.isIntervalDisabled(['daily', 'weekly', 'monthly', 'hourly'][intervaltype])).toBe(true)
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI.mock.calls[0][1]).toEqual(expect.objectContaining({ id: 'policy', volumeid: 'volume', fordisplay: false, schedule: intervaltype === 0 ? 0 : schedule, 'tags[0].key': 'keep', 'tags[1].key': 'remove' }))
  expect(postAPI.mock.calls[0][1]).not.toHaveProperty('zoneids'); expect(postAPI.mock.calls[0][1]).not.toHaveProperty('clearzoneids')
})
test('removing one/all existing tags uses full replacement on the same policy; a failed save retains the draft', async () => {
  const w = render(); await flushPromises(); w.vm.handleDeleteTag(w.vm.tags[1]); await w.vm.handleSubmit()
  expect(postAPI.mock.calls[0][1]['tags[1].key']).toBeUndefined(); expect(postAPI.mock.calls[0][1]['tags[0].key']).toBe('keep')
  w.vm.handleDeleteTag(w.vm.tags[0]); postAPI.mockRejectedValueOnce(new Error('server 431')); await w.vm.handleSubmit()
  expect(w.vm.tags).toEqual([]); expect(w.vm.form.time).toBe(0); expect(w.vm.form.maxsnaps).toBe(3)
  expect(postAPI.mock.calls[1][1]).not.toHaveProperty('tags[0].key'); expect(w.vm.$notifyError).toHaveBeenCalled()
})
test('lack of tag API permission preserves read-only existing tags during a schedule edit', async () => {
  delete store.getters.apis.createTags; const w = render(); await flushPromises(); expect(w.vm.canEditTags).toBe(false)
  w.vm.form.time = 15; await w.vm.handleSubmit(); expect(postAPI.mock.calls[0][1]['tags[0].key']).toBe('keep')
})
test('only an explicit selection change sends additional-zone clearing; failed lookup preserves selections', async () => {
  const w = render(); await flushPromises(); w.vm.form.zoneids = []; await w.vm.handleSubmit(); expect(postAPI.mock.calls[0][1]).toHaveProperty('clearzoneids', true)
  postAPI.mockClear(); w.vm.form.zoneids = ['extra']; w.vm.zoneError = true; await w.vm.handleSubmit(); expect(postAPI.mock.calls[0][1]).not.toHaveProperty('clearzoneids')
  postAPI.mockClear(); w.vm.form.zoneids = []; await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.$notifyError).toHaveBeenCalled()
})
test('a delayed preflight cannot duplicate submissions or write after permission loss', async () => {
  let finish; const preflight = jest.fn(() => new Promise(resolve => { finish = resolve })); const w = render(base(), preflight); await flushPromises()
  const first = w.vm.handleSubmit(); await flushPromises(); await w.vm.handleSubmit(); expect(preflight).toHaveBeenCalledTimes(1)
  delete store.getters.apis.createSnapshotPolicy; finish(); await first; expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.form.time).toBe(0)
})
test('a changed server policy rejects the draft preflight without writing or clearing the form', async () => {
  const w = render(base(), jest.fn().mockRejectedValue(new Error('changed'))); await flushPromises(); w.vm.form.time = 59; await w.vm.handleSubmit()
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.form.time).toBe(59); expect(w.vm.tags).toHaveLength(2)
})
test('inputs stay disabled during preflight and revoked tag permission prevents the captured tag draft from posting', async () => {
  let finish; const preflight = jest.fn(() => new Promise(resolve => { finish = resolve })); const w = render(base(), preflight); await flushPromises()
  w.vm.tags.push({ key: 'draft', value: 'not-authorized-after-revoke' }); const pending = w.vm.handleSubmit(); await flushPromises()
  expect(w.findAll('input.ant-input-number-input').every(input => input.attributes('disabled') !== undefined)).toBe(true)
  delete store.getters.apis.createTags; await flushPromises(); finish(); await pending
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.tags.map(t => t.key)).toEqual(['keep', 'remove']); expect(w.vm.$notifyError).toHaveBeenCalled()
})
test('an ownership change during an open edit is detected before writing', async () => {
  const w = dialog('edit'); await flushPromises()
  getAPI.mockImplementation(cmd => Promise.resolve(cmd === 'listSnapshotPolicies' ? { listsnapshotpoliciesresponse: { snapshotpolicy: [base()] } } : { listvolumesresponse: { volume: [{ ...volume, account: 'new-owner' }] } }))
  await expect(w.vm.validateEdit()).rejects.toThrow('message.snapshot.policy.changed'); expect(postAPI).not.toHaveBeenCalled()
})
test('a readonly caller makes no dialog reads or mutations', async () => {
  delete store.getters.apis.createSnapshotPolicy; const w = dialog('edit'); await flushPromises(); expect(w.vm.allowed).toBe(false); expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled()
})

test('explicit reload after an ambiguous save mounts a form from the completed fresh response', async () => {
  const w = mount(Dialog, { props: { resource: base(), currentAction: { policyMode: 'edit' } }, global: { mocks: mocks(), stubs: { ResourceIcon: true, MoldDialog: { template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } } } })
  wrappers.push(w); await flushPromises()
  expect(w.findComponent(Form).vm.form.maxsnaps).toBe(3)
  w.findComponent(Form).vm.form.maxsnaps = 7
  let finish
  getAPI.mockImplementation(cmd => cmd === 'listSnapshotPolicies'
    ? new Promise(resolve => { finish = resolve })
    : Promise.resolve(cmd === 'listVolumes' ? { listvolumesresponse: { volume: [volume] } } : {}))
  const reload = w.vm.load(); await flushPromises()
  expect(w.findComponent(Form).exists()).toBe(false)
  finish({ listsnapshotpoliciesresponse: { snapshotpolicy: [{ ...base(), maxsnaps: 5, tags: [{ key: 'fresh', value: 'committed' }] }] } })
  await reload; await flushPromises()
  expect(w.findComponent(Form).vm.form.maxsnaps).toBe(5)
  expect(w.findComponent(Form).vm.tags).toEqual([{ key: 'fresh', value: 'committed' }])
  expect(postAPI).not.toHaveBeenCalled()
})
test('latest-context rejection and failed reads are recoverable without using a previous volume', async () => {
  const w = dialog('edit'); await flushPromises(); expect(w.vm.policy.id).toBe('policy'); store.getters.project = { id: 'other' }; await flushPromises()
  expect(w.vm.policy).toBeNull(); expect(w.vm.allowed).toBe(false); await w.vm.load(); expect(w.vm.error).toBe('message.snapshot.policy.context.changed')
})
test('pause query failure retains the accepted job and retries its status instead of submitting again', async () => {
  getAPI.mockImplementation(cmd => Promise.resolve(cmd === 'listSnapshotPolicies' ? { listsnapshotpoliciesresponse: { snapshotpolicy: [{ ...base(), fordisplay: true }] } } : cmd === 'listVolumes' ? { listvolumesresponse: { volume: [volume] } } : { queryasyncjobresultresponse: { jobstatus: null } }))
  postAPI.mockResolvedValue({ updatesnapshotpolicyresponse: { jobid: 'accepted-job' } }); const w = dialog('pause'); await flushPromises(); w.vm.acknowledged = true
  await w.vm.submit(); expect(w.vm.jobId).toBe('accepted-job'); expect(w.vm.error).toBe('message.snapshot.policy.job.unknown'); await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('administrative scheduling waits for the job and verifies final display state', async () => {
  let enabled = true
  getAPI.mockImplementation(cmd => Promise.resolve(cmd === 'listSnapshotPolicies' ? { listsnapshotpoliciesresponse: { snapshotpolicy: [{ ...base(), fordisplay: enabled }] } } : cmd === 'listVolumes' ? { listvolumesresponse: { volume: [volume] } } : { queryasyncjobresultresponse: { jobstatus: 1 } }))
  postAPI.mockImplementation(() => { enabled = false; return Promise.resolve({ updatesnapshotpolicyresponse: { jobid: 'job' } }) })
  const w = dialog('pause'); await flushPromises(); w.vm.acknowledged = true; await w.vm.submit()
  expect(postAPI).toHaveBeenCalledWith('updateSnapshotPolicy', expect.objectContaining({ id: 'policy', fordisplay: false }), { preserveOnFailure: true }); expect(w.emitted('refresh-data')).toHaveLength(1); expect(w.emitted('close-action')).toHaveLength(1)
})
