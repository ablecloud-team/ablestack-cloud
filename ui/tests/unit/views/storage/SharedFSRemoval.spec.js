// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { shallowMount, mount as realMount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import SharedFSRemoval from '@/views/storage/SharedFSRemoval'
import { getAPI, postAPI } from '@/api'
import { clearSharedfsRemovalIntents, validRemovalPlan, removalImpacts } from '@/utils/sharedfsRemoval'
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const serviceId = '11111111-1111-4111-8111-111111111111'
const dataId = '22222222-2222-4222-8222-222222222222'
const rootId = '33333333-3333-4333-8333-333333333333'
const extraId = '44444444-4444-4444-8444-444444444444'
const hash = 'a'.repeat(64)
const nextHash = 'b'.repeat(64)
function plan (policy = 'PRESERVE_VOLUMES', overrides = {}) {
  const volume = { id: 10, uuid: dataId, type: 'DATADISK', role: 'DEFAULT_DATA', protocols: ['NFS', 'SMB'], accountId: 2, domainId: 1, zoneId: 4, poolId: 8, sizeBytes: 100, attachedVmId: 60, name: 'base-data', state: 'Ready', action: policy === 'PRESERVE_VOLUMES' ? 'DETACH_AND_PRESERVE' : 'DETACH_AND_DELETE' }
  return {
    schemaVersion: 1,
    sharedfsId: 7,
    sharedfsUuid: serviceId,
    vmId: 60,
    policy,
    accountId: 2,
    domainId: 1,
    zoneId: 4,
    planHash: hash,
    vmAction: 'EXPUNGE_VM',
    rootVolumes: [{ id: 9, uuid: rootId, type: 'ROOT', name: 'service-root', sizeBytes: 50, accountId: 2, domainId: 1, zoneId: 4, action: 'DELETE_WITH_VM' }],
    volumes: [volume, { ...volume, id: 11, uuid: extraId, name: 'additional-block-data', role: 'ADDITIONAL_DATA', protocols: ['ISCSI', 'NVME_OF'] }],
    ...overrides
  }
}
function preview (policy = 'PRESERVE_VOLUMES', overrides = {}) {
  const value = plan(policy, overrides)
  return { getsharedfilesystemdeletionplanresponse: { sharedfilesystemdeletionplan: { id: serviceId, planhash: value.planHash, plan: JSON.stringify(value) } } }
}
const wrappers = []
function mount (options = {}) {
  const store = options.store || reactive({ getters: { apis: { getSharedFileSystemDeletionPlan: {}, destroySharedFileSystem: {}, expungeSharedFileSystem: {}, queryAsyncJobResult: {} }, userInfo: { id: 'actor-a' }, project: { id: 'project-a' } }, state: { user: { token: 'runtime-only-test-token' } } })
  const poll = options.poll || jest.fn().mockResolvedValue({ jobstatus: 1 })
  const wrapper = (options.real ? realMount : shallowMount)(SharedFSRemoval, {
    props: { resource: { id: serviceId, name: 'fixture', state: 'Ready', projectid: 'project-a', project: 'fixture-project', account: 'fixture-owner', domainid: 'domain-a', zoneid: 'zone-a', ...options.resource }, currentAction: { api: 'destroySharedFileSystem', label: 'label.destroy.sharedfs', ...options.action } },
    global: { mocks: { $store: store, $t: key => key, $bytesToHumanReadableSize: value => String(value), $notifyError: jest.fn(), $pollJob: poll } }
  })
  wrappers.push(wrapper)
  return wrapper
}
async function selectDelete (wrapper) {
  wrapper.vm.policy = 'DELETE_VOLUMES'; getAPI.mockResolvedValue(preview('DELETE_VOLUMES'))
  await wrapper.vm.refreshPlan()
  wrapper.vm.acknowledged = true; wrapper.vm.confirmation = 'fixture'
}
beforeEach(() => { jest.clearAllMocks(); clearSharedfsRemovalIntents(); getAPI.mockResolvedValue(preview()); postAPI.mockResolvedValue({ destroysharedfilesystemresponse: { jobid: 'job-1' }, expungesharedfilesystemresponse: { jobid: 'job-expunge' } }) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); clearSharedfsRemovalIntents() })

describe('#895 explicit DATA retention and final confirmation', () => {
  it('defaults to preservation, requires an authoritative bound preview, and sends its hash', async () => {
    const wrapper = mount()
    expect(wrapper.vm.policy).toBe('PRESERVE_VOLUMES'); expect(wrapper.vm.canSubmit).toBe(false)
    await flushPromises(); expect(wrapper.vm.canSubmit).toBe(true)
    await wrapper.vm.submit()
    expect(getAPI).toHaveBeenCalledWith('getSharedFileSystemDeletionPlan', { id: serviceId, datavolumepolicy: 'PRESERVE_VOLUMES' }, expect.objectContaining({ preserveOnFailure: true }))
    expect(postAPI).toHaveBeenCalledWith('destroySharedFileSystem', { id: serviceId, datavolumepolicy: 'PRESERVE_VOLUMES', expectedplanhash: hash, forced: true, expunge: false }, expect.objectContaining({ preserveOnFailure: true }))
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })
  it('never sends a mutation when preview retrieval fails', async () => {
    getAPI.mockRejectedValue(new Error('preview unavailable'))
    const wrapper = mount(); await flushPromises(); await wrapper.vm.submit()
    expect(wrapper.vm.readError).toBe(true); expect(postAPI).not.toHaveBeenCalled()
  })
  it('requires exact name, acknowledgement and a separate final delete confirmation', async () => {
    const wrapper = mount(); await flushPromises(); await selectDelete(wrapper)
    wrapper.vm.confirmation = 'wrong'; await wrapper.vm.submit(); expect(wrapper.vm.finalConfirmation).toBe(false)
    wrapper.vm.confirmation = 'fixture'; wrapper.vm.acknowledged = false; await wrapper.vm.submit(); expect(postAPI).not.toHaveBeenCalled()
    wrapper.vm.acknowledged = true; await wrapper.vm.submit()
    expect(wrapper.vm.finalConfirmation).toBe(true); expect(postAPI).not.toHaveBeenCalled()
    await wrapper.vm.execute()
    expect(postAPI).toHaveBeenCalledWith('destroySharedFileSystem', expect.objectContaining({ datavolumepolicy: 'DELETE_VOLUMES', confirmdataloss: 'fixture', expectedplanhash: hash, expunge: false }), expect.any(Object))
  })
  it.each(['expunge', 'confirmation', 'acknowledged'])('invalidates final approval if %s changes', async field => {
    const wrapper = mount(); await flushPromises(); await selectDelete(wrapper); await wrapper.vm.submit()
    wrapper.vm[field] = field === 'confirmation' ? 'other-name' : field === 'expunge'
    await wrapper.vm.execute(); expect(postAPI).not.toHaveBeenCalled()
  })
  it('cancelling final confirmation sends no mutation', async () => {
    const wrapper = mount(); await flushPromises(); await selectDelete(wrapper); await wrapper.vm.submit()
    wrapper.vm.finalConfirmation = false; await wrapper.vm.execute()
    expect(postAPI).not.toHaveBeenCalled()
  })
  it('sends expunge-only parameters through the dedicated SharedFS action', async () => {
    const wrapper = mount({ action: { api: 'expungeSharedFileSystem', label: 'label.expunge.sharedfs' }, resource: { state: 'Destroyed' } }); await flushPromises()
    await selectDelete(wrapper); await wrapper.vm.submit(); await wrapper.vm.execute()
    expect(postAPI).toHaveBeenCalledWith('expungeSharedFileSystem', { id: serviceId, datavolumepolicy: 'DELETE_VOLUMES', expectedplanhash: hash, confirmdataloss: 'fixture' }, expect.any(Object))
  })
  it('ignores late responses from the former policy', async () => {
    let resolveOld; getAPI.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const wrapper = mount(); wrapper.vm.policy = 'DELETE_VOLUMES'; getAPI.mockResolvedValue(preview('DELETE_VOLUMES', { planHash: nextHash }))
    await wrapper.vm.refreshPlan(); resolveOld(preview()); await flushPromises()
    expect(wrapper.vm.plan.planHash).toBe(nextHash); expect(wrapper.vm.plan.policy).toBe('DELETE_VOLUMES')
  })
  it.each(['id', 'name', 'projectid'])('invalidates a preview and final approval after resource %s changes', async field => {
    const wrapper = mount(); await flushPromises(); await selectDelete(wrapper); await wrapper.vm.submit()
    let resolveFresh; getAPI.mockImplementation(() => new Promise(resolve => { resolveFresh = resolve }))
    await wrapper.setProps({ resource: { ...wrapper.props('resource'), [field]: field === 'id' ? extraId : 'changed' } })
    expect(wrapper.vm.canSubmit).toBe(false); expect(wrapper.vm.finalConfirmation).toBe(false)
    await wrapper.vm.execute(); expect(postAPI).not.toHaveBeenCalled()
    resolveFresh(preview()); await flushPromises()
  })
  it('ignores a delayed preview after the active actor/project/session changes', async () => {
    let finishRead; getAPI.mockImplementationOnce(() => new Promise(resolve => { finishRead = resolve }))
    const wrapper = mount(); wrapper.vm.$store.getters.project.id = 'project-b'; await wrapper.vm.$nextTick()
    finishRead(preview('PRESERVE_VOLUMES', { planHash: nextHash })); await flushPromises()
    expect(wrapper.vm.planContext).toBe(wrapper.vm.contextKey)
    expect(wrapper.vm.plan.planHash).toBe(hash)
    expect(wrapper.vm.$store.getters.project.id).toBe('project-b')
    // The old reply cannot replace the separately retrieved current-scope plan.
    expect(getAPI).toHaveBeenCalledTimes(2)
  })
  it.each(['destroySharedFileSystem', 'getSharedFileSystemDeletionPlan', 'queryAsyncJobResult'])('fails closed without %s permission', async api => {
    const store = reactive({ getters: { apis: { destroySharedFileSystem: {}, getSharedFileSystemDeletionPlan: {}, queryAsyncJobResult: {} }, userInfo: { id: 'actor' } }, state: { user: { token: 'test-token' } } })
    delete store.getters.apis[api]
    const wrapper = mount({ store }); await flushPromises(); await wrapper.vm.submit()
    expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled(); expect(wrapper.vm.authorized).toBe(false)
  })
})

describe('#895 partial failure, accepted jobs and ambiguous responses', () => {
  it('keeps the dialog open until the accepted job reaches terminal success', async () => {
    let finish; const poll = jest.fn(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount({ poll }); await flushPromises(); const pending = wrapper.vm.submit(); await flushPromises()
    expect(wrapper.vm.submitting).toBe(true); expect(wrapper.emitted('close-action')).toBeUndefined()
    finish({ jobstatus: 1 }); await pending; expect(wrapper.emitted('close-action')).toHaveLength(1)
  })
  it('rechecks an unknown known job across dialog closure without another POST', async () => {
    const poll = jest.fn().mockResolvedValueOnce({ trackingStatus: 'unknown' }).mockResolvedValueOnce({ jobstatus: 1 })
    const first = mount({ poll }); await flushPromises(); await first.vm.submit()
    expect(first.vm.knownJob).toBe('job-1'); expect(first.vm.operationError).toBe('message.storage.service.removal.unknown')
    first.unmount(); wrappers.splice(wrappers.indexOf(first), 1)
    const second = mount({ poll }); await flushPromises(); await second.vm.submit()
    expect(postAPI).toHaveBeenCalledTimes(1); expect(poll).toHaveBeenCalledTimes(2)
    expect(poll.mock.calls.map(([args]) => args.jobId)).toEqual(['job-1', 'job-1'])
    expect(poll.mock.calls.every(([args]) => args.refreshOnComplete === false)).toBe(true)
    expect(second.emitted('close-action')).toHaveLength(1)
  })
  it.each([{}, null, { jobstatus: 0 }])('never treats unknown/pending poll result %p as completion', async result => {
    const wrapper = mount({ poll: jest.fn().mockResolvedValue(result) }); await flushPromises(); await wrapper.vm.submit()
    expect(wrapper.emitted('close-action')).toBeUndefined(); expect(wrapper.vm.knownJob).toBe('job-1')
    expect(wrapper.vm.operation.status).toBe('unknown')
  })
  it('retains a known job after polling fails and only queries that same job again', async () => {
    const poll = jest.fn().mockRejectedValueOnce(new Error('connection lost')).mockResolvedValueOnce({ jobstatus: 1 })
    const wrapper = mount({ poll }); await flushPromises(); await wrapper.vm.submit(); await wrapper.vm.submit()
    expect(postAPI).toHaveBeenCalledTimes(1); expect(poll.mock.calls.every(([args]) => args.jobId === 'job-1')).toBe(true)
  })
  it.each(['transport', 'missing-job'])('blocks duplicate mutation after an ambiguous %s response, including reopen', async kind => {
    if (kind === 'transport') postAPI.mockRejectedValue(new Error('connection lost'))
    else postAPI.mockResolvedValue({ destroysharedfilesystemresponse: {} })
    const first = mount(); await flushPromises(); await first.vm.submit(); await first.vm.submit()
    expect(first.vm.intentLocked).toBe(true); expect(first.vm.canSubmit).toBe(false)
    first.unmount(); wrappers.splice(wrappers.indexOf(first), 1)
    const second = mount(); await flushPromises(); await second.vm.submit()
    expect(postAPI).toHaveBeenCalledTimes(1); expect(second.emitted('close-action')).toBeUndefined()
  })
  it('shows partial failure, refreshes the frozen preview, and requires renewed destructive approval for explicit retry', async () => {
    const poll = jest.fn().mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'second DATA detach failed; first volume remains preserved' } }).mockResolvedValueOnce({ jobstatus: 1 })
    const wrapper = mount({ poll }); await flushPromises(); await selectDelete(wrapper); await wrapper.vm.submit(); await wrapper.vm.execute()
    expect(wrapper.vm.operation.status).toBe('failed'); expect(wrapper.vm.operation.error).toContain('second DATA detach failed')
    expect(wrapper.emitted('close-action')).toBeUndefined(); expect(wrapper.vm.acknowledged).toBe(false); expect(wrapper.vm.canSubmit).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
    wrapper.vm.confirmation = 'fixture'; wrapper.vm.acknowledged = true; await wrapper.vm.submit()
    expect(postAPI).toHaveBeenCalledTimes(1); await wrapper.vm.execute(); expect(postAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls[1][1].expectedplanhash).toBe(hash)
  })
  it('keeps gateway errors ambiguous instead of authorizing a duplicate mutation', async () => {
    postAPI.mockRejectedValue({ response: { status: 502, data: '<html>Bad gateway</html>' } })
    const wrapper = mount(); await flushPromises(); await wrapper.vm.submit(); await wrapper.vm.submit()
    expect(wrapper.vm.operation.status).toBe('unknown'); expect(postAPI).toHaveBeenCalledTimes(1)
  })
  it('allows fresh review after an explicit API rejection without claiming completion', async () => {
    postAPI.mockRejectedValue({ response: { data: { errorresponse: { errorcode: 431, errortext: 'Preview changed; review again' } } } })
    const wrapper = mount(); await flushPromises(); await wrapper.vm.submit()
    expect(wrapper.vm.operation.status).toBe('failed'); expect(wrapper.vm.operation.error).toContain('Preview changed')
    expect(wrapper.vm.planReady).toBe(true); expect(wrapper.emitted('close-action')).toBeUndefined()
  })
  it('does not close the new resource dialog after an older resource job finishes', async () => {
    let finish; const wrapper = mount({ poll: jest.fn(() => new Promise(resolve => { finish = resolve })) }); await flushPromises()
    const pending = wrapper.vm.submit(); await flushPromises()
    getAPI.mockResolvedValue(preview('PRESERVE_VOLUMES', { sharedfsUuid: extraId }))
    await wrapper.setProps({ resource: { ...wrapper.props('resource'), id: extraId } })
    finish({ jobstatus: 1 }); await pending
    expect(wrapper.emitted('close-action')).toBeUndefined(); expect(postAPI).toHaveBeenCalledTimes(1)
  })
})

describe('#895 authoritative inventory and functional rendering', () => {
  it('keeps ROOT outside the DATA plan and shows default/additional protocol associations', async () => {
    const wrapper = mount({ real: true }); await flushPromises()
    expect(wrapper.vm.impacts.map(row => row.role)).toEqual(['ROOT', 'DEFAULT_DATA', 'ADDITIONAL_DATA'])
    expect(wrapper.vm.plan.volumes.map(row => row.uuid)).not.toContain(rootId)
    const text = document.body.textContent
    expect(text).toContain('service-root'); expect(text).toContain('base-data'); expect(text).toContain('additional-block-data')
    expect(text).toContain('NFS, SMB'); expect(text).toContain('iSCSI, NVMe-oF'); expect(text).toContain('fixture-project')
    const table = wrapper.findComponent({ name: 'InteralTable' })
    expect(table.props('scroll').x).toBe(table.props('columns').reduce((total, column) => total + column.width, 0))
    expect(wrapper.vm.volumeScope(wrapper.vm.impacts[1])).toBe('2 / 1 / 4')
  })
  it('marks legacy frozen ROOT/protocol metadata unobserved instead of guessing', () => {
    const value = plan(); delete value.rootVolumes; delete value.volumes[0].protocols; delete value.volumes[0].role
    const impacts = removalImpacts(value)
    expect(impacts[0]).toMatchObject({ role: 'ROOT_UNKNOWN' }); expect(impacts[0].uuid).toBeUndefined()
    expect(validRemovalPlan(value, { id: serviceId }, 'PRESERVE_VOLUMES')).toBe(true)
  })
  it.each([
    value => { value.sharedfsUuid = extraId },
    value => { value.policy = 'DELETE_VOLUMES' },
    value => { value.planHash = 'not-a-hash' },
    value => { value.volumes[0].type = 'ROOT' },
    value => { value.volumes[0].domainId = 9 },
    value => { value.volumes[0].action = 'DETACH_AND_DELETE' },
    value => { value.volumes[1].uuid = value.volumes[0].uuid },
    value => { value.volumes = null }
  ])('rejects an invalid or mismatched destructive preview', async corrupt => {
    const value = plan(); corrupt(value); getAPI.mockResolvedValue({ getsharedfilesystemdeletionplanresponse: { sharedfilesystemdeletionplan: { plan: JSON.stringify(value) } } })
    const wrapper = mount(); await flushPromises(); await wrapper.vm.submit()
    expect(wrapper.vm.readError).toBe(true); expect(postAPI).not.toHaveBeenCalled()
  })
  it.each(['Destroyed', 'Expunging', 'Expunged', 'Error'])('routes %s retry through SharedFS expunge instead of a new destroy', state => {
    const actions = storage.children.flatMap(section => section.actions || [])
    expect(actions.find(action => action.api === 'destroySharedFileSystem').show({ state })).toBe(false)
    expect(actions.find(action => action.api === 'expungeSharedFileSystem').show({ state })).toBe(true)
    expect(actions.find(action => action.api === 'recoverSharedFileSystem').show({ state })).toBe(state === 'Destroyed')
  })
  it.each([undefined, [], [{ key: 'fixture-tag', value: 'owned' }]])('retains actual compute config protection for SharedFS with tags=%p while allowing normal VMs', tags => {
    const actions = compute.children.flatMap(section => section.actions || [])
    for (const api of ['destroyVirtualMachine', 'expungeVirtualMachine', 'recoverVirtualMachine']) {
      const action = actions.find(item => item.api === api)
      expect(action).toBeDefined()
      const record = { name: 'owned-instance', tags, state: api === 'destroyVirtualMachine' ? 'Running' : 'Destroyed', hypervisor: 'KVM' }
      const store = { features: { allowuserexpungerecovervm: true } }
      expect(action.show({ ...record, vmtype: 'sharedfsvm' }, store)).toBe(false)
      expect(action.show({ ...record, vmtype: 'uservm' }, store)).toBe(true)
    }
  })
})
