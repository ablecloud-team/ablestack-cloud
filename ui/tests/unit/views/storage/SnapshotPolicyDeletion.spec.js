// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import Dialog from '@/views/storage/SnapshotPolicyDeleteDialog'
import DeleteAction from '@/views/storage/SnapshotPolicyDeleteAction'
import Autogen from '@/views/AutogenView'
import storage from '@/config/section/storage'
import { getAPI, postAPI } from '@/api'
import { canDeletePolicy, deletionIdentity, readPolicyDeletionTarget, deletionSchedule } from '@/utils/snapshotPolicyDeletion'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
let store; let route; let wrappers; let policies; let volume
const base = () => ({ id: 'fixed', volumeid: 'volume', intervaltype: 0, schedule: '0', timezone: 'UTC', maxsnaps: 2, tags: [{ key: 'keep', value: '한글' }] })
const failure = code => Object.assign(new Error('server ' + code), { response: { status: code } })
function render (deferClose = false) {
  const w = mount(Dialog, {
    props: { resource: { ...base() } },
    global: {
      mocks: { $store: store, $route: route, $t: key => key, $notification: { success: jest.fn() } },
      stubs: { MoldDialog: { props: ['visible', 'afterClose'], watch: { visible (value) { if (!value && !deferClose && this.afterClose) this.afterClose() } }, template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } }
    }
  })
  wrappers.push(w); return w
}
beforeEach(() => {
  wrappers = []; policies = [base(), { ...base(), id: 'other', intervaltype: 1 }]; volume = { id: 'volume', name: '전용 볼륨', account: 'owner', domainid: 'domain', domain: 'ROOT', projectid: 'project', project: '시험 프로젝트', zoneid: 'zone' }
  store = reactive({ getters: { apis: { deleteSnapshotPolicies: {}, listSnapshotPolicies: {}, listVolumes: {} }, userInfo: { id: 'user', roletype: 'User' }, project: { id: 'project' } }, state: { user: { token: 'session' } } }); route = reactive({ path: '/snapshotpolicy' })
  getAPI.mockReset(); postAPI.mockReset()
  getAPI.mockImplementation(cmd => Promise.resolve(cmd === 'listVolumes' ? { listvolumesresponse: { volume: [volume] } } : { listsnapshotpoliciesresponse: { count: policies.length, snapshotpolicy: policies.map(p => ({ ...p })) } }))
  postAPI.mockImplementation(() => { policies = policies.filter(p => p.id !== 'fixed'); return Promise.resolve({ deletesnapshotpoliciesresponse: { success: true } }) })
})
afterEach(async () => { wrappers.forEach(w => w.unmount()); await flushPromises(); document.body.innerHTML = '' })

test('same summary exposes volume, identity, cadence, schedule, retention and ownership; cancellation never writes', async () => {
  const w = render(); await flushPromises()
  for (const value of ['전용 볼륨', 'fixed', 'volume', 'label.hourly', 'UTC', 'owner', 'ROOT', '시험 프로젝트']) expect(w.text()).toContain(value)
  expect(w.vm.acknowledged).toBe(false); await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
  await w.findAll('button').find(b => b.text() === 'label.cancel').trigger('click'); expect(w.emitted('close-action')).toHaveLength(1); expect(postAPI).not.toHaveBeenCalled()
})
test('one confirmed UUID is deleted and read back absent, while the other interval remains', async () => {
  const w = render(); await flushPromises(); w.vm.acknowledged = true; await w.vm.submit()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI).toHaveBeenCalledWith('deleteSnapshotPolicies', { ids: 'fixed' }, { preserveOnFailure: true, timeout: 30000 })
  expect(policies.map(p => p.id)).toEqual(['other']); expect(w.emitted('refresh-data')).toHaveLength(1); expect(w.emitted('close-action')[0][0]).toEqual({ removed: ['fixed'] })
})

test('the action wrapper forwards confirmed deletion before the page can refetch and erase its closing scope', async () => {
  Object.assign(route, { name: 'snapshotpolicy', meta: { name: 'snapshotpolicy' }, path: '/snapshotpolicy/fixed', params: { id: 'fixed' }, query: {} })
  const action = { policyDeleteDialog: true, policyListScope: 'same' }
  const view = { currentAction: action, $route: route, $refs: {}, $nextTick: f => f(), listScope: () => 'same', resource: base(), fetchData: jest.fn(), $router: { replace: jest.fn(), push: jest.fn() }, listRequestVersion: 0, showAction: true, pageSize: 20 }
  const w = mount(DeleteAction, {
    props: { resource: view.resource, currentAction: action, onRefreshData: result => Autogen.methods.refreshActionData.call(view, result), onCloseAction: result => Autogen.methods.closeAction.call(view, result) },
    global: { mocks: { $store: store, $route: route, $t: k => k, $notification: { success: jest.fn() } }, stubs: { MoldDialog: { props: ['visible', 'afterClose'], watch: { visible (value) { if (!value && this.afterClose) this.afterClose() } }, template: '<div><slot /></div>' }, MoldDialogActions: { template: '<div><slot /></div>' } } }
  })
  wrappers.push(w); await flushPromises(); const child = w.findComponent(Dialog)
  child.vm.acknowledged = true; await child.vm.submit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('refresh-data')[0][0]).toEqual({ removed: ['fixed'] })
  expect(view.fetchData).not.toHaveBeenCalled(); expect(view.resource).toEqual({})
  expect(view.$router.replace).toHaveBeenCalledWith({ path: '/snapshotpolicy', query: { page: '1', pagesize: '20', sortkey: 'id', sortorder: 'desc' } })
})
test('parent teardown waits for the close animation and is emitted once', async () => {
  const w = render(true); await flushPromises(); w.vm.close(); await flushPromises()
  expect(w.emitted('close-action')).toBeUndefined(); w.vm.finishClose(); w.vm.finishClose(); expect(w.emitted('close-action')).toHaveLength(1)
})
test('a late close cannot remove rows in another scope or report after disposal', async () => {
  const w = render(true); await flushPromises(); w.vm.complete(); store.getters.project = { id: 'changed' }; await flushPromises()
  w.vm.finishClose(); expect(w.emitted('close-action')[0][0]).toBeUndefined()
  const late = render(true); await flushPromises(); late.vm.close(); const finish = late.vm.finishClose; late.unmount(); finish(); expect(late.emitted('close-action')).toBeUndefined()
})
test.each(['schedule', 'tags'])('a changed %s requires reload and new acknowledgement', async field => {
  const w = render(); await flushPromises(); policies[0] = { ...policies[0], [field]: field === 'schedule' ? '59' : [] }; w.vm.acknowledged = true; await w.vm.submit()
  expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.needsReload).toBe(true); expect(w.vm.acknowledged).toBe(false)
  await w.vm.load(); expect(w.vm.needsReload).toBe(false); expect(w.vm.acknowledged).toBe(false); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test.each(['account', 'projectid', 'name'])('a changed volume %s cannot delete the previously confirmed scope', async field => {
  const w = render(); await flushPromises(); volume = { ...volume, [field]: 'changed' }; w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.error).toBe('message.snapshot.policy.changed')
})
test('a preflight read failure or removed target sends no delete; explicit reload recovers', async () => {
  const w = render(); await flushPromises(); getAPI.mockRejectedValueOnce(failure(503)); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.target.policy.id).toBe('fixed')
  policies = policies.filter(p => p.id !== 'fixed'); await w.vm.load(); expect(w.vm.target).toBeNull(); expect(w.vm.needsReload).toBe(true); expect(postAPI).not.toHaveBeenCalled()
})
test.each(['permission', 'project', 'route', 'token', 'user', 'role', 'target'])('a late preflight after %s change cannot write', async change => {
  const w = render(); await flushPromises(); let release; getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve })); w.vm.acknowledged = true; const pending = w.vm.submit(); await flushPromises()
  await w.vm.submit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.busy).toBe(true)
  if (change === 'permission') delete store.getters.apis.deleteSnapshotPolicies
  if (change === 'project') store.getters.project.id = 'another'
  if (change === 'route') route.path = '/volume/another'
  if (change === 'token') store.state.user.token = 'another'
  if (change === 'user') store.getters.userInfo.id = 'another'
  if (change === 'role') store.getters.userInfo.roletype = 'Admin'
  if (change === 'target') await w.setProps({ resource: { ...base(), id: 'another' } })
  await flushPromises(); release({ listvolumesresponse: { volume: [volume] } }); await pending; expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.target).toBeNull(); expect(w.find('[data-testid="policy-delete-target"]').exists()).toBe(false)
})
test('late initial reads cannot refill a different project', async () => {
  let release; getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve })); const w = render(); store.getters.project.id = 'other'; await flushPromises(); release({ listvolumesresponse: { volume: [volume] } }); await flushPromises(); expect(w.vm.target).toBeNull(); expect(w.vm.allowed).toBe(false)
})
test('readonly or missing read permission performs no lookup or mutation', async () => {
  delete store.getters.apis.listVolumes; const w = render(); await flushPromises(); await w.vm.submit(); expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled(); expect(canDeletePolicy(store.getters.apis)).toBe(false)
})
test('a lost successful response is reconciled without a second delete and busy blocks close/double submit', async () => {
  let release; postAPI.mockImplementationOnce(() => new Promise((resolve, reject) => { release = reject })); const w = render(); await flushPromises(); w.vm.acknowledged = true; const first = w.vm.submit(); await flushPromises(); await w.vm.submit(); w.vm.close(); expect(w.emitted('close-action')).toBeUndefined(); expect(postAPI).toHaveBeenCalledTimes(1)
  policies = policies.filter(p => p.id !== 'fixed'); release(failure(503)); await first; expect(w.vm.uncertain).toBe(true); getAPI.mockRejectedValueOnce(failure(503)); await w.vm.submit(); expect(w.vm.uncertain).toBe(true); expect(w.emitted('refresh-data')).toBeUndefined(); await w.vm.submit(); expect(w.vm.error).toBe('message.snapshot.policy.delete.unverified'); expect(w.emitted('close-action')).toBeUndefined(); expect(w.vm.$notification.success).not.toHaveBeenCalled(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test('a confirmed deletion with a failed verification retries only reads and can then report success', async () => {
  const w = render(); await flushPromises(); postAPI.mockImplementationOnce(() => { policies = []; getAPI.mockRejectedValueOnce(failure(503)); return Promise.resolve({ deletesnapshotpoliciesresponse: { success: true } }) }); w.vm.acknowledged = true; await w.vm.submit(); expect(w.vm.deleteAccepted).toBe(true); expect(w.emitted('close-action')).toBeUndefined(); await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.emitted('close-action')).toHaveLength(1)
})
test('unknown outcome with an existing target only unlocks after reload and fresh confirmation', async () => {
  postAPI.mockRejectedValueOnce(failure(503)); const w = render(); await flushPromises(); w.vm.acknowledged = true; await w.vm.submit(); await w.vm.submit(); expect(w.vm.uncertain).toBe(false); expect(w.vm.needsReload).toBe(true); await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); await w.vm.load(); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(2)
})
test('definitive running-policy 431 retains the summary and requires reload before retry', async () => {
  postAPI.mockRejectedValueOnce(failure(431)); const w = render(); await flushPromises(); w.vm.acknowledged = true; await w.vm.submit(); expect(w.vm.target.policy.id).toBe('fixed'); expect(w.vm.uncertain).toBe(false); await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1); await w.vm.load(); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(2)
})
test('a success flag alone cannot report success while the target still exists', async () => {
  postAPI.mockResolvedValueOnce({ deletesnapshotpoliciesresponse: { success: true } }); const w = render(); await flushPromises(); w.vm.acknowledged = true; await w.vm.submit(); expect(w.emitted('refresh-data')).toBeUndefined(); expect(w.vm.error).toBe('message.snapshot.policy.delete.still.exists')
})
test('an invalid legacy schedule can still be reviewed and deleted', async () => {
  policies[0].schedule = '60'; const w = render(); await flushPromises(); expect(w.vm.schedule).toBe('60'); w.vm.acknowledged = true; await w.vm.submit(); expect(postAPI).toHaveBeenCalledTimes(1)
})
test.each([[0, '0'], [1, '59:23'], [2, '59:23:7'], [3, '59:23:28']])('cadence %s is decoded for review without changing identity', (intervaltype, schedule) => {
  const p = { ...base(), intervaltype, schedule }; expect(deletionSchedule(p, k => k)).toBeTruthy(); expect(deletionIdentity(p)).toEqual({ id: 'fixed', volumeid: 'volume', intervaltype }); expect(Object.isFrozen(deletionIdentity(p))).toBe(true)
})
test('a malformed or incomplete list is never accepted as proof of absence', async () => {
  getAPI.mockImplementation(cmd => Promise.resolve(cmd === 'listVolumes' ? { listvolumesresponse: { volume: [volume] } } : {})); await expect(readPolicyDeletionTarget(getAPI, deletionIdentity(base()), () => true, true)).rejects.toThrow('Missing')
})
test('menu deletion freezes the right-clicked row rather than the old selection and returns to a stable toolbar', () => {
  const row = base(); const root = document.createElement('div'); root.innerHTML = '<div class="autogen-toolbar-actions"><button>refresh</button></div>'; document.body.append(root)
  const action = storage.children.find(c => c.name === 'snapshotpolicy').actions.find(a => a.api === 'deleteSnapshotPolicies'); expect(action.popup && action.selfManagedDialog && action.policyDeleteDialog).toBe(true)
  const view = { $route: route, $store: store, $el: root, listScope: () => 'fixed-scope', storageActionReady: true, storageActionGeneration: 0, selectedItems: [{ id: 'old-selection' }], $nextTick: fn => fn() }
  Autogen.methods.execAction.call(view, { ...action, resource: row }, false); row.id = 'mutated-row'; expect(view.resource.id).toBe('fixed'); expect(view.currentAction.invokedAsGroupAction).toBe(false)
  Autogen.methods.closeAction.call(view); expect(document.activeElement).toBe(root.querySelector('button')); expect(postAPI).not.toHaveBeenCalled()
})
