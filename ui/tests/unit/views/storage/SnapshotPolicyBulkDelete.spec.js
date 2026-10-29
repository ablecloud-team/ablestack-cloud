// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/storage/SnapshotPolicyBulkDeleteDialog'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
let store; let route; let policies; let wrappers
const base = (id, volume, interval = 0) => ({ id, volumeid: volume, intervaltype: interval, schedule: interval ? '5:3' : '5', timezone: 'UTC', maxsnaps: 2, tags: [{ key: 'keep', value: '한글' }] })
const failure = () => Object.assign(new Error('503'), { response: { status: 503 } })
function render (targets = policies.slice(0, 2)) {
  const w = mount(Dialog, { props: { resource: targets[0], currentAction: { invokedAsGroupAction: true, policyDeleteTargets: targets.map(p => ({ ...p })) } }, global: { mocks: { $store: store, $route: route, $t: key => key, $notification: { success: jest.fn() } }, stubs: { MoldDialog: { props: ['visible', 'afterClose'], watch: { visible (value) { if (!value) this.afterClose() } }, template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } } } })
  wrappers.push(w); return w
}
beforeEach(() => {
  wrappers = []; policies = [base('first', 'v1'), base('second', 'v2', 1), base('untouched', 'v2', 2)]
  store = reactive({ getters: { userInfo: { id: 'user', roletype: 'Admin' }, apis: { listSnapshotPolicies: {}, listVolumes: {}, deleteSnapshotPolicies: {} }, project: {}, listAllProjects: false }, state: { user: { token: 'session' } } }); route = reactive({ path: '/snapshotpolicy', fullPath: '/snapshotpolicy?page=1' })
  getAPI.mockReset(); postAPI.mockReset()
  getAPI.mockImplementation(async (cmd, params) => cmd === 'listVolumes' ? { listvolumesresponse: { volume: [{ id: params.id, name: '전용 ' + params.id, account: 'owner', domainid: 'domain' }] } } : { listsnapshotpoliciesresponse: { snapshotpolicy: policies.filter(p => p.volumeid === params.volumeid), count: policies.filter(p => p.volumeid === params.volumeid).length } })
  postAPI.mockImplementation(async (_, params) => { policies = policies.filter(p => !params.ids.split(',').includes(p.id)); return { deletesnapshotpoliciesresponse: { success: true } } })
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises() })
test('capture targets, require acknowledgement, and cancellation never mutates', async () => {
  const w = render(); await flushPromises(); expect(w.vm.ready.map(e => e.identity.id)).toEqual(['first', 'second']); expect(w.text()).toContain('first'); expect(w.text()).toContain('second'); expect(w.text()).not.toContain('untouched')
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); w.vm.close(); expect(w.emitted('refresh-data')).toBeUndefined()
})
test('one ids request deletes only the captured IDs after full preflight and readback', async () => {
  const w = render(); await flushPromises(); policies.push(base('added-later', 'v1', 3)); w.vm.acknowledged = true; await w.vm.submit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI).toHaveBeenCalledWith('deleteSnapshotPolicies', { ids: 'first,second' }, { preserveOnFailure: true, timeout: 30000 })
  expect(policies.map(p => p.id)).toEqual(['untouched', 'added-later']); expect(w.vm.removed).toHaveLength(2); expect(w.emitted('refresh-data')).toHaveLength(1); expect(w.emitted('close-action')[0][0]).toEqual({ removed: ['first', 'second'], clearSelection: true })
})
test.each(['schedule', 'tags', 'maxsnaps'])('changed %s blocks the entire write until reload and new acknowledgement', async field => {
  const w = render(); await flushPromises(); policies[1] = { ...policies[1], [field]: field === 'tags' ? [] : field === 'schedule' ? '9:4' : 3 }; w.vm.acknowledged = true; await w.vm.submit()
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.needsReload).toBe(true); expect(w.vm.acknowledged).toBe(false); await w.vm.load(); expect(w.vm.needsReload).toBe(false); expect(w.vm.acknowledged).toBe(false)
})
test('preflight read failure preserves the summary and never deletes any target', async () => {
  const w = render(); await flushPromises(); getAPI.mockRejectedValueOnce(failure()); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.entries[0].target.volume.id).toBe('v1'); expect(w.vm.needsReload).toBe(true)
})
test('runtime partial failure separates absent-unconfirmed from present and retries only reviewed survivors', async () => {
  const w = render(); await flushPromises(); postAPI.mockImplementationOnce(async () => { policies = policies.filter(p => p.id !== 'first'); throw failure() }); w.vm.acknowledged = true; await w.vm.submit()
  expect(w.vm.removed).toHaveLength(0); expect(w.vm.unknown.map(e => e.identity.id)).toEqual(['first']); expect(w.vm.retained.map(e => e.identity.id)).toEqual(['second']); expect(w.vm.results).toBe(true); expect(w.vm.$notification.success).not.toHaveBeenCalled()
  await w.vm.reconcile(); expect(postAPI).toHaveBeenCalledTimes(1); await w.vm.prepareRetry(); expect(w.vm.ready.map(e => e.identity.id)).toEqual(['second']); expect(w.vm.acknowledged).toBe(false)
  await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI.mock.calls[1][1]).toEqual({ ids: 'second' }); expect(w.vm.unknown.map(e => e.identity.id)).toEqual(['first'])
})
test('lost success response cannot promote absence to success or resend absent IDs', async () => {
  const w = render(); await flushPromises(); postAPI.mockImplementationOnce(async (_, params) => { policies = policies.filter(p => !params.ids.split(',').includes(p.id)); throw failure() }); w.vm.acknowledged = true; await w.vm.submit(); await w.vm.reconcile()
  expect(w.vm.unknown).toHaveLength(2); expect(w.vm.removed).toHaveLength(0); expect(w.vm.retained).toHaveLength(0); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.$notification.success).not.toHaveBeenCalled()
})
test('accepted deletion with a failed readback retries only reads', async () => {
  const w = render(); await flushPromises(); const read = getAPI.getMockImplementation(); postAPI.mockImplementationOnce(async (_, params) => { policies = policies.filter(p => !params.ids.split(',').includes(p.id)); getAPI.mockRejectedValue(failure()); return { deletesnapshotpoliciesresponse: { success: true } } }); w.vm.acknowledged = true; await w.vm.submit(); expect(w.vm.unknown).toHaveLength(2)
  getAPI.mockImplementation(read); await w.vm.reconcile(); expect(w.vm.removed).toHaveLength(2); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('busy blocks duplicate submit and close during a real outstanding request', async () => {
  const w = render(); await flushPromises(); let finish; postAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve })); w.vm.acknowledged = true; const pending = w.vm.submit(); await flushPromises(); await w.vm.submit(); w.vm.close(); expect(w.vm.closed).toBe(false); expect(postAPI).toHaveBeenCalledTimes(1); finish({ deletesnapshotpoliciesresponse: { success: true } }); await pending
})
test.each(['project', 'route', 'token', 'user', 'role', 'permission'])('a late preflight after %s transition cannot write or restore prior metadata', async change => {
  const w = render(); await flushPromises(); let release; getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve })); w.vm.acknowledged = true; const pending = w.vm.submit(); await flushPromises()
  if (change === 'project') store.getters.project.id = 'different'
  if (change === 'route') route.fullPath += '&page=2'
  if (change === 'token') store.state.user.token = 'different'
  if (change === 'user') store.getters.userInfo.id = 'different'
  if (change === 'role') store.getters.userInfo.roleid = 'different'
  if (change === 'permission') delete store.getters.apis.deleteSnapshotPolicies
  await flushPromises(); release({ listvolumesresponse: { volume: [{ id: 'v1', name: 'old' }] } }); await pending; expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.entries.every(e => !e.target)).toBe(true); expect(w.vm.allowed).toBe(false)
})
