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
import CreateSharedFS from '@/views/storage/CreateSharedFS'
import Consent from '@/views/storage/StorageAdMutationConsent'
import { getAPI, postAPI } from '@/api'
import { adMutationScope, requireAdMutationApproval } from '@/utils/storageAdIdentity'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const id = '11111111-1111-4111-8111-111111111111'
const instance = () => ({ id, name: 'owned-service' })
const proof = (extra = {}) => ({ success: true, sideEffects: false, joinState: 'JOINED', domain: 'example.local', realm: 'EXAMPLE.LOCAL', scope: { instanceUuid: id, operationUuid: '33333333-3333-4333-8333-333333333333', revision: 4 }, bootId: '22222222-2222-4222-8222-222222222222', generatedEpoch: Date.now() / 1000, trustVerified: true, identityVerified: true, dnsAliasesVerified: true, adSpnsVerified: true, adIdentity: true, ...extra })
const response = receipt => ({ liststorageservicedomainstatusresponse: { storageidentitydomain: [{ instanceid: id, domainname: 'example.local', joinstate: 'JOINED', healthstate: 'OK', identityreceipt: receipt }] } })
const apiParams = () => ({ fresh: {}, admaintenancewindow: {}, adconfirmation: {}, maintenancewindow: {}, confirmation: {}, identitymode: {} })
const context = (joined = true) => {
  const vm = {
    storageService: { instance: instance(), domains: joined ? [{ instanceid: id, domainname: 'example.local', joinstate: 'JOINED' }] : [] },
    adMutationConsent: { visible: false, instance: {}, scope: '', resolve: null },
    actionLoading: {},
    currentTab: 'SMB',
    protocolWideLayout: false,
    $getApiParams: apiParams,
    $t: key => key,
    $route: { path: '/sharedfs/test' },
    $message: { error: jest.fn() },
    $notifyError: jest.fn(),
    resource: { name: 'wrong-fallback' },
    cleanParams: params => Object.fromEntries(Object.entries(params).filter(([, value]) => value !== undefined)),
    $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }),
    refreshAfterStorageAction: jest.fn().mockResolvedValue()
  }
  for (const name of ['requestAdMutationApproval', 'approveAdMutation', 'cancelAdMutation', 'runStorageAction']) vm[name] = SharedFSTab.methods[name]
  vm.requestAdMutationApproval = jest.fn(async (instance, title, scope) => ({ maintenancewindow: true, confirmation: instance.name, scope }))
  return vm
}
const run = (vm, params = { instanceid: id, name: 'new-own-share' }) => vm.runStorageAction('smbShare', 'createStorageSmbShare', params, 'create share')

describe('JOINED writer one-use SERVICE consent', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset(); getAPI.mockImplementation(async () => response(proof())); postAPI.mockResolvedValue({ createstoragesmbshareresponse: { jobid: 'job-a' } }) })

  it('requires unchecked-by-default literal approval and exact service name in the reused dialog', () => {
    expect(Consent.data()).toEqual({ maintenancewindow: false, confirmation: '' })
    const vm = { instance: instance(), scope: 'review-a', ...Consent.data() }
    expect(Consent.computed.approved.call(vm)).toBe(false)
    for (const change of [{ maintenancewindow: 'true', confirmation: 'owned-service' }, { maintenancewindow: true, confirmation: 'owned-service ' }, { maintenancewindow: true, confirmation: 'wrong-fallback' }]) expect(Consent.computed.approved.call({ ...vm, ...change })).toBe(false)
    expect(Consent.computed.approved.call({ ...vm, maintenancewindow: true, confirmation: 'owned-service' })).toBe(true)
  })

  it('discards local checkbox/name on scope or instance changes', () => {
    const vm = { maintenancewindow: true, confirmation: 'owned-service', reset: Consent.methods.reset }
    Consent.watch.scope.call(vm); expect(vm.maintenancewindow).toBe(false); expect(vm.confirmation).toBe('')
    vm.maintenancewindow = true; vm.confirmation = 'owned-service'; Consent.watch.instance.handler.call(vm)
    expect(vm.maintenancewindow).toBe(false)
  })

  it('reads fresh exact-instance proof before consent and again before POST, then awaits the actual job', async () => {
    const vm = context(); await run(vm)
    expect(getAPI).toHaveBeenCalledTimes(2)
    expect(getAPI.mock.calls.every(call => call[1].instanceid === id && call[1].fresh === true)).toBe(true)
    expect(postAPI).toHaveBeenCalledWith('createStorageSmbShare', { instanceid: id, name: 'new-own-share', admaintenancewindow: true, adconfirmation: 'owned-service', expectedrevision: 4 })
    expect(vm.$pollJob).toHaveBeenCalledTimes(1); expect(vm.refreshAfterStorageAction).toHaveBeenCalledTimes(1)
    expect(vm.adMutationConsent.visible).toBe(false); expect(vm.actionLoading.smbShare).toBe(false)
  })

  it('preserves non-AD request fields and the existing asynchronous refresh callback', async () => {
    const vm = context(false); await run(vm)
    expect(getAPI).not.toHaveBeenCalled(); expect(vm.requestAdMutationApproval).not.toHaveBeenCalled()
    expect(postAPI.mock.calls[0][1]).toEqual({ instanceid: id, name: 'new-own-share' })
    expect(typeof vm.$pollJob.mock.calls[0][0].successMethod).toBe('function')
  })

  it('does not turn delete/restore confirmation into the instance confirmation field', async () => {
    const vm = context(); await run(vm, { confirmation: 'share-label', id: 'own-share-id' })
    expect(postAPI.mock.calls[0][1]).toMatchObject({ confirmation: 'share-label', adconfirmation: 'owned-service' })
  })

  it('blocks missing API fields or unsupported fresh observation before any mutation', async () => {
    for (const params of [{}, { admaintenancewindow: {} }, { admaintenancewindow: {}, adconfirmation: {} }]) {
      const vm = context(); vm.$getApiParams = () => params; await run(vm)
      expect(postAPI).not.toHaveBeenCalled(); expect(vm.requestAdMutationApproval).not.toHaveBeenCalled()
    }
  })

  it('blocks foreign instance IDs and missing exact-instance domain context', async () => {
    const vm = context(); await run(vm, { instanceid: 'foreign' }); expect(postAPI).not.toHaveBeenCalled()
    vm.storageService.domains = []; vm.isSmbAdConfigured = true; vm.isSmbAdJoined = true; await run(vm)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('blocks failed, missing, stale or string-boolean fresh receipts without config fallback', async () => {
    for (const receipt of [undefined, proof({ generatedEpoch: Date.now() / 1000 - 61 }), proof({ trustVerified: 'true' })]) {
      getAPI.mockResolvedValue(response(receipt)); const vm = context(); await run(vm)
      expect(postAPI).not.toHaveBeenCalled(); expect(vm.requestAdMutationApproval).not.toHaveBeenCalled()
    }
    getAPI.mockRejectedValue(new Error('controlled read failure')); await run(context()); expect(postAPI).not.toHaveBeenCalled()
  })

  it('blocks missing, wrong-name or unapproved consent without silently using an ordinary request', async () => {
    for (const approval of [null, { maintenancewindow: false, confirmation: 'owned-service' }, { maintenancewindow: true, confirmation: 'wrong-fallback' }]) {
      const vm = context(); vm.requestAdMutationApproval.mockResolvedValue(approval); await run(vm)
      expect(postAPI).not.toHaveBeenCalled(); expect(vm.actionLoading.smbShare).toBe(false)
    }
  })

  it('ignores new read UUID/epoch but rejects changed stable revision/boot/domain identity after consent', async () => {
    const before = proof(); const after = proof({ scope: { ...before.scope, operationUuid: '44444444-4444-4444-8444-444444444444' }, generatedEpoch: before.generatedEpoch + 1 })
    expect(adMutationScope(instance(), 'action', before)).toBe(adMutationScope(instance(), 'action', after))
    for (const extra of [{ scope: { ...before.scope, revision: 5 } }, { bootId: '44444444-4444-4444-8444-444444444444' }, { domainSid: 'S-1-5-21-1-2-3' }]) {
      getAPI.mockReset().mockResolvedValueOnce(response(before)).mockResolvedValueOnce(response(proof(extra)))
      await run(context()); expect(postAPI).not.toHaveBeenCalled()
    }
  })

  it('does not leak secret parameters into the public review scope', () => {
    const value = adMutationScope(instance(), 'action', proof(), { id: 'own-id', password: 'synthetic-private', dhchapkey: 'synthetic-key', dhchapctrlkey: 'synthetic-controller-key', chapsecret: 'synthetic-secret' })
    expect(value).toContain('own-id'); expect(value).not.toMatch(/synthetic-private|synthetic-key|synthetic-controller-key|synthetic-secret/)
  })

  it('does not reuse a consent on another action or request review', () => {
    const receipt = proof(); const approval = { maintenancewindow: true, confirmation: 'owned-service', scope: adMutationScope(instance(), 'createStorageSmbShare', receipt, { name: 'a' }) }
    expect(() => requireAdMutationApproval(instance(), 'createStorageSmbAcl', receipt, { name: 'a' }, approval)).toThrow()
    expect(() => requireAdMutationApproval(instance(), 'createStorageSmbShare', receipt, { name: 'b' }, approval)).toThrow()
  })

  it('keeps loading until joined job completion and preserves failure without a retry', async () => {
    const vm = context(); let finish; let entered
    const started = new Promise(resolve => { entered = resolve })
    vm.$pollJob.mockImplementation(() => new Promise(resolve => { finish = resolve; entered() }))
    const pending = run(vm); await started
    expect(vm.actionLoading.smbShare).toBe(true); expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled()
    finish({ jobstatus: 2 }); await pending
    expect(postAPI).toHaveBeenCalledTimes(1); expect(vm.refreshAfterStorageAction).not.toHaveBeenCalled(); expect(vm.actionLoading.smbShare).toBe(false)
  })

  it('rejects navigation/name changes while awaiting fresh proof or job completion', async () => {
    const vm = context(); vm.requestAdMutationApproval.mockImplementation(async (_, __, scope) => { vm.storageService.instance.name = 'foreign'; return { maintenancewindow: true, confirmation: 'owned-service', scope } })
    await run(vm); expect(postAPI).not.toHaveBeenCalled()
  })

  it('cancels a pending explicit grant on form, action, service or domain scope invalidation', async () => {
    for (const cancel of [vm => SharedFSTab.watch.forms.handler.call(vm), vm => SharedFSTab.watch['actionModal.type'].call(vm), vm => SharedFSTab.watch['storageService.instance'].handler.call(vm), vm => SharedFSTab.beforeUnmount.call(vm)]) {
      const vm = context(); const pending = SharedFSTab.methods.requestAdMutationApproval.call(vm, instance(), 'title', 'scope-a'); cancel(vm)
      expect(await pending).toBeNull(); expect(vm.adMutationConsent.visible).toBe(false)
    }
  })

  it('blocks a pre-existing stale desired revision before POST', async () => {
    const vm = context(); await run(vm, { instanceid: id, expectedrevision: 3 })
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('keeps raw transport errors and credential request references out of joined error notification', async () => {
    const vm = context(); postAPI.mockRejectedValue({ config: { data: 'synthetic-sensitive-body' } }); await run(vm)
    expect(vm.$notifyError).not.toHaveBeenCalled()
    expect(vm.$message.error).toHaveBeenCalledWith('message.storage.service.ad.receipt.unverified')
    expect(vm.adMutationConsent.resolve).toBeNull()
  })
})

const createContext = () => ({
  $t: key => key,
  $getApiParams: apiParams,
  initialBackingVolumeId: () => 'own-data',
  isSetupServiceSelected: (_, protocol) => protocol === 'SMB',
  joinInitialAdDomain: jest.fn().mockResolvedValue(proof()),
  initialSmbAclParams: () => ({ principaltype: 'AD_USER', principal: 'EXAMPLE\\user' }),
  runStorageServiceSetup: jest.fn().mockResolvedValue({}),
  extractCreatedId: () => 'own-share',
  toCapacityBytes: () => undefined
})
const setup = () => ({ smbidentitymode: 'AD', smbadmaintenancewindow: true, smbadconfirmation: 'owned-service', smbaddomain: 'example.local', smbname: 'own-share' })

describe('initial Create same-instance SMB-only approval', () => {
  beforeEach(() => { getAPI.mockReset().mockResolvedValue(response(proof())) })

  it('forwards already explicit consent only to the same-instance share with inline AD ACL after JOIN and fresh receipt', async () => {
    const vm = createContext(); await CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, setup())
    expect(vm.joinInitialAdDomain).toHaveBeenCalledTimes(1)
    expect(vm.runStorageServiceSetup.mock.calls[0]).toMatchObject(['createStorageSmbShare', { instanceid: id, admaintenancewindow: true, adconfirmation: 'owned-service', principaltype: 'AD_USER' }])
    expect(getAPI).toHaveBeenCalledTimes(1)
  })

  it('blocks unknown common fields, unapproved input or changed receipt before the share request', async () => {
    for (const change of [vm => { vm.$getApiParams = () => ({}) }, vm => { vm.joinInitialAdDomain.mockResolvedValue(proof({ scope: { ...proof().scope, revision: 3 } })) }]) {
      const vm = createContext(); change(vm)
      await expect(CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, setup())).rejects.toThrow()
      expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
    }
    const vm = createContext(); await expect(CreateSharedFS.methods.createInitialFileServices.call(vm, instance(), {}, { ...setup(), smbadmaintenancewindow: false })).rejects.toThrow()
    expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
  })

  it('requires common writer metadata at AD create preflight before any new VM can be requested', () => {
    const vm = { $t: key => key, $getApiParams: () => ({ maintenancewindow: {}, confirmation: {}, identitymode: {}, fresh: {} }) }
    expect(() => CreateSharedFS.methods.requireInitialAdApi.call(vm)).toThrow('message.storage.service.ad.maintenance.unsupported')
  })
})

describe('initial AD per-action explicit consent lifecycle', () => {
  const vmContext = () => ({
    $t: key => key,
    $getApiParams: apiParams,
    initialAdDisposed: false,
    adMutationConsent: { visible: false, instance: {}, scope: '', resolve: null },
    cancelAdMutation: CreateSharedFS.methods.cancelAdMutation,
    runStorageServiceSetup: jest.fn().mockResolvedValue({}),
    requestAdMutationApproval: jest.fn(async (instance, title, scope) => ({ maintenancewindow: true, confirmation: instance.name, scope }))
  })
  beforeEach(() => getAPI.mockReset().mockResolvedValue(response(proof())))

  it('requires a new explicit approval for another supported protocol rather than broadening the SMB grant', async () => {
    const vm = vmContext()
    await CreateSharedFS.methods.runInitialJoinedAction.call(vm, instance(), setup(), 'createStorageIscsiTarget', { instanceid: id, targetname: 'own-target' })
    expect(vm.requestAdMutationApproval).toHaveBeenCalledTimes(1)
    expect(vm.runStorageServiceSetup.mock.calls[0]).toMatchObject(['createStorageIscsiTarget', { admaintenancewindow: true, adconfirmation: 'owned-service', expectedrevision: 4 }])
    expect(getAPI).toHaveBeenCalledTimes(2)
  })

  it('does not POST after user cancellation or disposal while the dialog is pending', async () => {
    for (const disposed of [false, true]) {
      const vm = vmContext(); vm.requestAdMutationApproval.mockImplementation(async () => { vm.initialAdDisposed = disposed; return null })
      await expect(CreateSharedFS.methods.runInitialJoinedAction.call(vm, instance(), setup(), 'createStorageNvmeOfSubsystem', {})).rejects.toThrow()
      expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
    }
  })

  it('blocks closed/disposed creation before fresh read and rejects changed identity after approval', async () => {
    const closed = vmContext(); closed.initialAdDisposed = true
    await expect(CreateSharedFS.methods.runInitialJoinedAction.call(closed, instance(), setup(), 'createStorageIscsiTarget', {})).rejects.toThrow()
    expect(getAPI).not.toHaveBeenCalled(); expect(closed.runStorageServiceSetup).not.toHaveBeenCalled()
    getAPI.mockResolvedValueOnce(response(proof())).mockResolvedValueOnce(response(proof({ bootId: '44444444-4444-4444-8444-444444444444' })))
    const vm = vmContext(); await expect(CreateSharedFS.methods.runInitialJoinedAction.call(vm, instance(), setup(), 'createStorageIscsiTarget', {})).rejects.toThrow()
    expect(vm.runStorageServiceSetup).not.toHaveBeenCalled()
  })

  it('normalizes scope cleanup and controller/CHAP request references on terminal failure', async () => {
    const vm = vmContext(); vm.runStorageServiceSetup.mockRejectedValue(new Error('controlled failure'))
    const parameters = { dhchapctrlkey: 'synthetic-controller-key', chapsecret: 'synthetic-secret' }
    await expect(CreateSharedFS.methods.runInitialJoinedAction.call(vm, instance(), setup(), 'createStorageNvmeOfHostAcl', parameters)).rejects.toThrow()
    expect(parameters.dhchapctrlkey).toBe(''); expect(parameters.chapsecret).toBe(''); expect(vm.adMutationConsent.resolve).toBeNull()
  })
})

describe('interactive Create terminal and dispose boundary', () => {
  it('does not POST any later AD setup command after the creation view was closed', async () => {
    postAPI.mockReset()
    const vm = { initialAdInteractive: true, initialAdDisposed: true, $t: key => key }
    await expect(CreateSharedFS.methods.runStorageServiceSetup.call(vm, 'enableStorageServiceProtocol', {})).rejects.toThrow()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('preserves the ordinary background setup after a non-AD view closes', async () => {
    postAPI.mockReset().mockResolvedValue({ enablestorageserviceprotocolresponse: { success: true } })
    const vm = { initialAdInteractive: false, initialAdDisposed: true, $t: key => key, $store: { getters: { apis: { enableStorageServiceProtocol: {} } } }, cleanParams: values => values }
    await CreateSharedFS.methods.runStorageServiceSetup.call(vm, 'enableStorageServiceProtocol', { instanceid: id })
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('close cancels a pending grant and discards AD input references while normal close remains unchanged', () => {
    for (const interactive of [false, true]) {
      const vm = { initialAdInteractive: interactive, initialAdDisposed: false, cancelAdMutation: jest.fn(), clearInitialAdCredentials: jest.fn(), $emit: jest.fn() }
      CreateSharedFS.methods.closeModal.call(vm)
      expect(vm.$emit).toHaveBeenCalledWith('close-action')
      expect(vm.cancelAdMutation).toHaveBeenCalledTimes(interactive ? 1 : 0)
      expect(vm.initialAdDisposed).toBe(interactive)
    }
  })

  it('dispose settles the pending approval promise and leaves no orphan grant', async () => {
    const vm = { adMutationConsent: { visible: false }, cancelAdMutation: CreateSharedFS.methods.cancelAdMutation, serviceOfferingRequestToken: 0, templateRequestToken: 0 }
    const pending = CreateSharedFS.methods.requestAdMutationApproval.call(vm, instance(), 'action', 'scope')
    CreateSharedFS.beforeUnmount.call(vm)
    expect(await pending).toBeNull(); expect(vm.initialAdDisposed).toBe(true); expect(vm.adMutationConsent.resolve).toBeNull()
  })
})
