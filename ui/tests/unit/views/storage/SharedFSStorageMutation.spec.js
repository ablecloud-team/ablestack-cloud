// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import SharedFSTab from '@/views/storage/SharedFSTab'
import { postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const deferred = () => { let complete; const promise = new Promise(resolve => { complete = resolve }); return { promise, resolve: complete } }
const context = () => {
  const vm = {
    resource: { id: 'fs-a', name: 'owned-a' },
    storageService: { instance: { id: 'instance-a', name: 'owned-a' }, domains: [] },
    currentTab: 'iscsi',
    protocolWideLayout: false,
    actionLoading: {},
    actionModal: { visible: true, type: 'editIscsiAcl', context: { id: 'acl-a' }, loading: false },
    $route: { path: '/sharedfs/fs-a' },
    $t: key => key,
    $getApiParams: () => ({ idempotencykey: {}, expectedrevision: {} }),
    $message: { error: jest.fn() },
    $notifyError: jest.fn(),
    cleanParams: parameters => Object.fromEntries(Object.entries(parameters).filter(([, value]) => value !== undefined)),
    $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }),
    refreshAfterStorageAction: jest.fn().mockResolvedValue(),
    refreshStorageOperationHistory: jest.fn(),
    cancelAdMutation: jest.fn()
  }
  for (const name of ['runStorageAction', 'submitActionModal', 'closeActionModal']) vm[name] = SharedFSTab.methods[name]
  return vm
}
const run = (vm, fields = {}) => vm.runStorageAction('editIscsiAcl', 'updateStorageIscsiAcl', { id: 'acl-a', permission: 'READ_ONLY', ...fields }, 'update ACL')

describe('SharedFS configuration mutation terminal state', () => {
  beforeEach(() => { postAPI.mockReset(); postAPI.mockResolvedValue({ updatestorageiscsiaclresponse: { jobid: 'job-a' } }) })

  it('waits for the real non-AD job and keeps one writer until it completes', async () => {
    const vm = context(); const job = deferred()
    vm.$pollJob.mockReturnValue(job.promise)
    const pending = run(vm)
    await Promise.resolve(); await Promise.resolve()
    expect(vm.actionLoading.editIscsiAcl).toBe(true)
    expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled()
    expect(await run(vm)).toBe(false)
    expect(postAPI).toHaveBeenCalledTimes(1)
    job.resolve({ jobstatus: 1 })
    expect(await pending).toBe(true)
    expect(vm.actionLoading.editIscsiAcl).toBe(false)
    expect(vm.refreshAfterStorageAction).toHaveBeenCalledTimes(1)
    expect(vm.refreshStorageOperationHistory).toHaveBeenCalledWith('instance-a')
  })

  it.each([{ jobstatus: 2 }, { jobstatus: 0, trackingStatus: 'unknown' }])('returns false and refreshes actual state after terminal failure or unknown %j', async result => {
    const vm = context(); vm.$pollJob.mockResolvedValue(result)
    expect(await run(vm)).toBe(false)
    expect(vm.refreshAfterStorageAction).toHaveBeenCalledTimes(1)
    expect(vm.refreshStorageOperationHistory).toHaveBeenCalledWith('instance-a')
    expect(vm.$message.error).toHaveBeenCalled()
    expect(vm.$notifyError).not.toHaveBeenCalled()
  })

  it('preserves a failed dialog and serializes duplicate confirmation clicks', async () => {
    const vm = context(); const pending = deferred()
    vm.updateIscsiAcl = jest.fn().mockReturnValue(pending.promise)
    vm.forms = { iscsiAcl: { initiatoriqn: 'iqn.owned', permission: 'READ_ONLY' } }
    const first = vm.submitActionModal()
    await vm.submitActionModal()
    expect(vm.updateIscsiAcl).toHaveBeenCalledTimes(1)
    pending.resolve(false); await first
    expect(vm.actionModal).toMatchObject({ visible: true, loading: false, type: 'editIscsiAcl' })
    expect(vm.forms.iscsiAcl.permission).toBe('READ_ONLY')
  })

  it('does not close a newer form or refresh another resource when an old job finishes', async () => {
    const vm = context(); const job = deferred()
    vm.$pollJob.mockReturnValue(job.promise)
    vm.updateIscsiAcl = () => run(vm)
    const pending = vm.submitActionModal()
    await Promise.resolve(); await Promise.resolve()
    vm.resource = { id: 'fs-b' }; vm.storageService.instance = { id: 'instance-b', name: 'owned-b' }
    vm.actionModal = { visible: true, type: 'smbShare', context: null, loading: false }
    job.resolve({ jobstatus: 1 }); await pending
    expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled()
    expect(vm.refreshStorageOperationHistory).not.toHaveBeenCalled()
    expect(vm.actionModal).toMatchObject({ visible: true, type: 'smbShare', loading: false })
  })

  it('does not refresh after unmount while an accepted mutation is still being tracked', async () => {
    const vm = context(); const job = deferred()
    vm.$pollJob.mockReturnValue(job.promise)
    const pending = run(vm); await Promise.resolve(); await Promise.resolve()
    SharedFSTab.beforeUnmount.call(vm)
    job.resolve({ jobstatus: 1 }); await pending
    expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled()
    expect(vm.refreshStorageOperationHistory).not.toHaveBeenCalled()
  })

  it('reuses the RAM request key for a lost POST response without retaining credentials in state', async () => {
    const vm = context()
    postAPI.mockRejectedValueOnce(new Error('network lost'))
    expect(await run(vm, { chapsecret: 'synthetic-secret' })).toBe(false)
    const first = postAPI.mock.calls[0][1].idempotencykey
    expect(first).toEqual(expect.any(String))
    expect(first).not.toContain('synthetic-secret')
    expect(await run(vm, { chapsecret: 'synthetic-secret' })).toBe(true)
    expect(postAPI.mock.calls[1][1].idempotencykey).toBe(first)
    expect(postAPI.mock.calls[0][2]).toMatchObject({ preserveOnFailure: true, timeout: 15000 })
    expect(JSON.stringify(vm)).not.toContain('synthetic-secret')
  })

  it('uses a new key after a known failed operation instead of replaying ROLLED_BACK', async () => {
    const vm = context(); vm.$pollJob.mockResolvedValueOnce({ jobstatus: 2 })
    expect(await run(vm)).toBe(false)
    const failed = postAPI.mock.calls[0][1].idempotencykey
    expect(await run(vm)).toBe(true)
    expect(postAPI.mock.calls[1][1].idempotencykey).not.toBe(failed)
  })

  it('resumes an unknown known job rather than submitting a second mutation', async () => {
    const vm = context(); vm.$pollJob.mockResolvedValueOnce({ jobstatus: 0, trackingStatus: 'unknown' })
    expect(await run(vm)).toBe(false)
    expect(await run(vm)).toBe(true)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(vm.$pollJob.mock.calls[1][0]).toMatchObject({ jobId: 'job-a', retry: true })
  })

  it('does not let state refresh failures erase a confirmed job success or retry it', async () => {
    const vm = context(); vm.refreshAfterStorageAction.mockRejectedValue(new Error('read failed'))
    expect(await run(vm)).toBe(true)
    expect(vm.refreshStorageOperationHistory).toHaveBeenCalledWith('instance-a')
  })
  it('keeps a newer dialog on the same service open after an earlier dialog was cancelled', async () => {
    const vm = context(); const job = deferred(); vm.$pollJob.mockReturnValue(job.promise)
    vm.updateIscsiAcl = () => run(vm)
    const pending = vm.submitActionModal(); await Promise.resolve(); await Promise.resolve()
    vm.closeActionModal()
    Object.assign(vm.actionModal, { visible: true, type: 'smbShare', loading: false })
    job.resolve({ jobstatus: 1 }); await pending
    expect(vm.actionModal).toMatchObject({ visible: true, type: 'smbShare', loading: false })
  })

  it.each(['storageMutationSecurityScope', '$store.state.user.token'])('invalidates a lost response retry identity at the security boundary %s', async watcher => {
    const vm = context(); postAPI.mockRejectedValueOnce(new Error('lost'))
    expect(await run(vm)).toBe(false)
    const before = postAPI.mock.calls[0][1].idempotencykey
    SharedFSTab.watch[watcher].call(vm)
    expect(await run(vm)).toBe(true)
    expect(postAPI.mock.calls[1][1].idempotencykey).not.toBe(before)
  })

  it('does not attach unsupported idempotency parameters or raw credentials to public failure notifications', async () => {
    const vm = context(); vm.$getApiParams = () => ({})
    postAPI.mockRejectedValue({ config: { data: 'synthetic-secret' }, response: { data: { errortext: 'synthetic-secret' } } })
    expect(await run(vm, { chapsecret: 'synthetic-secret' })).toBe(false)
    expect(postAPI.mock.calls[0][1]).not.toHaveProperty('idempotencykey')
    expect(vm.$notifyError).not.toHaveBeenCalled()
    expect(JSON.stringify(vm.$message.error.mock.calls)).not.toContain('synthetic-secret')
    expect(vm.actionModal.error).toBe('message.job.result.unknown')
  })

  it('keeps an uncertain request key independent of object field order but distinct for edited intent', async () => {
    const vm = context(); postAPI.mockRejectedValue(new Error('lost'))
    await run(vm, { comment: 'a', permission: 'READ_ONLY' })
    const before = postAPI.mock.calls[0][1].idempotencykey
    await vm.runStorageAction('editIscsiAcl', 'updateStorageIscsiAcl', { comment: 'a', permission: 'READ_ONLY', id: 'acl-a' }, 'update ACL')
    expect(postAPI.mock.calls[1][1].idempotencykey).toBe(before)
    await run(vm, { comment: 'b' })
    expect(postAPI.mock.calls[2][1].idempotencykey).not.toBe(before)
  })

  it('does not turn an input validation rejection into a closed dialog or writer', async () => {
    const vm = context()
    vm.actionModal.type = 'iscsiAcl'
    vm.validateIscsiChapForm = () => false
    vm.createIscsiAcl = SharedFSTab.methods.createIscsiAcl
    expect(await vm.submitActionModal()).toBe(false)
    expect(postAPI).not.toHaveBeenCalled()
    expect(vm.actionModal.visible).toBe(true)
  })
})

describe('SharedFS current resource publication after mutation', () => {
  const refreshContext = () => ({
    ...context(),
    storageRefreshGeneration: 1,
    preserveCurrentProtocolRoute: jest.fn(),
    emitWideLayout: jest.fn(),
    fetchRuntime: jest.fn().mockResolvedValue(),
    fetchCollection: jest.fn().mockResolvedValue(),
    fetchAccessRules: jest.fn().mockResolvedValue(),
    fetchBackingVolumes: jest.fn().mockResolvedValue(),
    fetchNvmeStorageSnapshot: jest.fn().mockResolvedValue({ snapshot: 'current' }),
    applyNvmeStorageSnapshot: jest.fn().mockReturnValue(true)
  })

  it.each(['editIscsiTarget', 'editIscsiAcl'])('refreshes target and ACL publication for %s', async action => {
    const vm = refreshContext()
    await SharedFSTab.methods.refreshAfterStorageAction.call(vm, action)
    expect(vm.fetchCollection).toHaveBeenCalledWith('listStorageIscsiTargets', 'iscsiTargets', 'storageiscsitarget')
    expect(vm.fetchAccessRules).toHaveBeenCalledTimes(1)
  })

  it('refreshes all resource collections and ACLs after a failed or successful delete', async () => {
    const vm = refreshContext()
    await SharedFSTab.methods.refreshAfterStorageAction.call(vm, 'deleteConfirm')
    for (const command of ['listStorageNfsExports', 'listStorageSmbShares', 'listStorageIscsiTargets', 'listStorageNvmeOfSubsystems', 'listStorageNvmeOfNamespaces']) expect(vm.fetchCollection.mock.calls.some(call => call[0] === command)).toBe(true)
    expect(vm.fetchAccessRules).toHaveBeenCalledTimes(1)
    expect(vm.fetchBackingVolumes).toHaveBeenCalledTimes(1)
  })

  it('keeps the existing atomic NVMe snapshot publication for subsystem edits', async () => {
    const vm = refreshContext()
    await SharedFSTab.methods.refreshAfterStorageAction.call(vm, 'editNvmeSubsystem')
    expect(vm.fetchNvmeStorageSnapshot).toHaveBeenCalledWith('instance-a')
    expect(vm.applyNvmeStorageSnapshot).toHaveBeenCalledWith({ snapshot: 'current' }, 2)
    expect(vm.fetchAccessRules).toHaveBeenCalledTimes(1)
  })

  it('never restores a departed resource tab when its read finishes late', async () => {
    const vm = refreshContext(); const read = deferred(); vm.fetchRuntime.mockReturnValue(read.promise)
    const pending = SharedFSTab.methods.refreshAfterStorageAction.call(vm, 'editIscsiTarget')
    vm.resource = { id: 'fs-b' }; vm.storageService.instance = { id: 'instance-b' }; vm.currentTab = 'smb'; vm.protocolWideLayout = true
    read.resolve(); await pending
    expect(vm.currentTab).toBe('smb'); expect(vm.protocolWideLayout).toBe(true)
    expect(vm.fetchAccessRules).not.toHaveBeenCalled()
  })
})
