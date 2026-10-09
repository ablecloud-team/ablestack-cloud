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

import fs from 'fs'
import path from 'path'
import { shallowMount } from '@vue/test-utils'
import Widget from '@/views/storage/StorageOperationHistory'
import { getAPI, postAPI } from '@/api'
import { SMB_CURRENT_RETAIN_MODE, requireSmbCurrentRecoveryReview, requireSmbCurrentRecoveryResult } from '@/utils/storageSmbCurrentIdentityRecovery'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

describe('Configuration operation recovery history', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it.each([
    ['ko_KR', ['보존된 원본 설정', '후속 정상 리비전', 'DATA는 포맷하지 않습니다']],
    ['en', ['preserved source configuration', 'later verified revision', 'DATA volumes are not formatted']]
  ])('shows both recovery outcomes in the actual %s confirmation dialog', async (locale, requiredInformation) => {
    const messages = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../public/locales', locale + '.json'), 'utf8'))
    getAPI.mockResolvedValue({ liststorageserviceoperationsresponse: { storageserviceoperation: [] } })
    const wrapper = shallowMount(Widget, {
      props: { instanceId: 'a' },
      global: {
        mocks: { $t: key => messages[key] || key, $store: { getters: { apis: { reconcileStorageServiceOperation: {} } } } },
        stubs: {
          'a-modal': { props: ['visible'], template: '<div v-if="visible" class="recovery-dialog"><slot /></div>' },
          'a-alert': { props: ['message'], template: '<p>{{ message }}</p>' }
        }
      }
    })
    await wrapper.setData({ reconcileTarget: { id: 'failed', state: 'RECOVERY_REQUIRED', diagnostic: 'original cause' } })
    const explanation = wrapper.find('.recovery-dialog').text()
    requiredInformation.forEach(information => expect(explanation).toContain(information))
    expect(explanation).toContain('original cause')
    expect(postAPI).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each(['ROLLED_BACK', 'RECONCILED_SUPERSEDED'])('lets the server resolve %s without client recovery flags', async state => {
    const verified = { id: 'failed', state, diagnostic: 'original cause' }
    postAPI.mockResolvedValue({ reconcilestorageserviceoperationresponse: { jobid: 'job' } })
    getAPI.mockImplementation(command => Promise.resolve(command === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceoperation: verified } } }
      : { liststorageserviceoperationsresponse: { storageserviceoperation: [verified] } }))
    const vm = { canReconcile: true, instanceId: 'a', reconcileTarget: { id: 'failed', state: 'RECOVERY_REQUIRED' }, saving: '', generation: 0, rows: [] }
    vm.refresh = jest.fn(() => Widget.methods.refresh.call(vm))
    await Widget.methods.reconcile.call(vm)
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('reconcileStorageServiceOperation', { operationid: 'failed' })
    expect(vm.rows).toEqual([verified])
    expect(vm.refresh).toHaveBeenCalledTimes(1)
    expect(vm.saving).toBe('')
  })

  it('preserves known diagnostics when a history refresh fails', async () => {
    const vm = { instanceId: 'a', generation: 0, rows: [{ id: 'failed', diagnostic: 'original cause' }], loading: false, readError: false }
    getAPI.mockRejectedValue(new Error('timeout')); await Widget.methods.refresh.call(vm)
    expect(vm.rows[0].diagnostic).toBe('original cause'); expect(vm.readError).toBe(true)
  })
  it('ignores a late operation history from a different instance', async () => {
    let complete; getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { instanceId: 'a', generation: 0, rows: [{ id: 'current' }] }; const pending = Widget.methods.refresh.call(vm)
    vm.instanceId = 'b'; vm.generation++; complete({ liststorageserviceoperationsresponse: { storageserviceoperation: [{ id: 'old' }] } })
    await pending; expect(vm.rows).toEqual([{ id: 'current' }])
  })
  it('distinguishes recovery required from successfully verified current revision', () => {
    expect(Widget.methods.color('RECOVERY_REQUIRED')).toBe('red'); expect(Widget.methods.color('RECONCILED_SUPERSEDED')).toBe('green')
  })
  it('blocks writes without the reconciliation API permission', async () => {
    const vm = { canReconcile: false, reconcileTarget: { id: 'failed' } }
    await Widget.methods.reconcile.call(vm)
    expect(postAPI).not.toHaveBeenCalled()
    expect(Widget.computed.canReconcile.call({ $store: { getters: { apis: {} } } })).toBe(false)
  })
  it('closes the dialog after accepting the async operation and refreshes only its own instance', async () => {
    postAPI.mockResolvedValue({ reconcilestorageserviceoperationresponse: { jobid: 'job' } })
    const vm = { canReconcile: true, instanceId: 'a', reconcileTarget: { id: 'failed' }, saving: '', refresh: jest.fn() }
    getAPI.mockImplementation(() => {
      expect(vm.reconcileTarget).toBe(null)
      return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 1 } })
    })
    await Widget.methods.reconcile.call(vm)
    expect(vm.refresh).toHaveBeenCalledTimes(1); expect(vm.saving).toBe('')
  })
  it('does not show a stale async response on a different instance', async () => {
    let complete; postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = { canReconcile: true, instanceId: 'a', reconcileTarget: { id: 'failed' }, saving: '', refresh: jest.fn() }
    const pending = Widget.methods.reconcile.call(vm); vm.instanceId = 'b'; vm.saving = ''
    complete({ reconcilestorageserviceoperationresponse: { jobid: 'job' } }); await pending
    expect(getAPI).not.toHaveBeenCalled(); expect(vm.refresh).not.toHaveBeenCalled()
  })
})

const currentInstance = '11111111-1111-4111-8111-111111111111'
const currentOperation = '22222222-2222-4222-8222-222222222222'
const currentScope = { instanceId: currentInstance, instanceName: 'own-f1', operationId: currentOperation, revision: 4 }
const currentReview = (overrides = {}) => ({
  kind: 'CURRENT_LOCAL_SMB_IDENTITY_RECOVERY_REVIEW',
  schemaVersion: 1,
  success: true,
  sideEffects: false,
  scope: { instanceUuid: currentInstance, operationUuid: currentOperation, revision: 4 },
  committedRevision: 3,
  sourceConfigurationSha256: 'a'.repeat(64),
  currentReviewHash: 'b'.repeat(64),
  maintenanceRequired: true,
  generatedEpoch: Date.now() / 1000,
  approvalStored: false,
  resumeAllowed: false,
  recoveryPhase: 'RECOVERY_REQUIRED',
  publicFacts: { bootId: '44444444-4444-4444-8444-444444444444', databaseCount: 2, ownedMasterCount: 1, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: false },
  ...overrides
})
const reviewResponse = value => ({ reviewstorageservicesmbidentityrecoveryresponse: { success: true, status: 'OBSERVED', resultjson: JSON.stringify(value) } })
const retainedResult = overrides => ({
  success: true,
  currentReviewHash: 'b'.repeat(64),
  phase: 'CURRENT_LOCAL_IDENTITY_RETAINED_SOURCE_CONFIG_ROLLED_BACK',
  scope: { instanceUuid: currentInstance, operationUuid: currentOperation, revision: 4 },
  currentIdentityRetained: true,
  originalIdentityRestored: false,
  originalConfigurationSourceRestored: true,
  dataChanged: false,
  ...overrides
})
const makeCurrentVm = () => {
  const parameters = Object.fromEntries(['instanceid', 'operationid', 'recoverymode', 'currentreviewhash', 'maintenancewindow', 'confirmation', 'expectedrevision', 'idempotencykey'].map(name => [name, {}]))
  const vm = Object.assign(Widget.data(), Widget.methods, {
    canReconcile: true,
    instanceId: currentInstance,
    instanceName: 'own-f1',
    reconcileTarget: { id: currentOperation, revision: 4, state: 'RECOVERY_REQUIRED' },
    recoveryMode: SMB_CURRENT_RETAIN_MODE,
    currentReview: currentReview(),
    currentMaintenanceApproved: true,
    currentConfirmation: 'own-f1',
    $store: { getters: { apis: { reviewStorageServiceSmbIdentityRecovery: {}, repairStorageServiceSmbIdentity: {} } } },
    $getApiParams: () => parameters,
    $t: key => key,
    refresh: jest.fn()
  })
  Object.defineProperty(vm, 'canCurrentRecovery', { get: () => Widget.computed.canCurrentRecovery.call(vm) })
  Object.defineProperty(vm, 'currentRecoveryCanSubmit', { get: () => Widget.computed.currentRecoveryCanSubmit.call(vm) })
  return vm
}

describe('Explicit CURRENT local SMB recovery consent', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it.each([
    { schemaVersion: '1' }, { sideEffects: 'false' }, { maintenanceRequired: false },
    { scope: { instanceUuid: '33333333-3333-4333-8333-333333333333', operationUuid: currentOperation, revision: 4 } },
    { scope: { instanceUuid: currentInstance, operationUuid: currentOperation, revision: 5 } },
    { currentReviewHash: 'B'.repeat(64) }, { generatedEpoch: 1 }, { committedRevision: 4 }, { committedRevision: 2 },
    { approvalStored: 'true' }, { approvalStored: true, resumeAllowed: false }, { recoveryPhase: null },
    { publicFacts: null }, { publicFacts: { bootId: 'bad', databaseCount: 2, ownedMasterCount: 1, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: false } },
    { publicFacts: { bootId: '44444444-4444-4444-8444-444444444444', databaseCount: '2', ownedMasterCount: 1, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: false } },
    { publicFacts: { bootId: '44444444-4444-4444-8444-444444444444', databaseCount: 2, ownedMasterCount: 0, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: false } },
    { publicFacts: { bootId: '44444444-4444-4444-8444-444444444444', databaseCount: 2, ownedMasterCount: 1, sessionsVerifiedEmpty: false, namespaceObserved: true, loadedDaemonSidVerified: false } },
    { publicFacts: { bootId: '44444444-4444-4444-8444-444444444444', databaseCount: 2, ownedMasterCount: 1, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: true } },
    { publicFacts: { bootId: '44444444-4444-4444-8444-444444444444', databaseCount: 2, ownedMasterCount: 1, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: false, privateValue: 'must-not-display' } }
  ])('rejects unverified or changed public review %p', fields => {
    expect(() => requireSmbCurrentRecoveryReview(currentReview(fields), currentScope)).toThrow('SMB_CURRENT_RECOVERY_REVIEW_UNVERIFIED')
  })
  it.each([
    { currentIdentityRetained: 'true' }, { originalIdentityRestored: true }, { originalConfigurationSourceRestored: false },
    { dataChanged: true }, { currentReviewHash: 'c'.repeat(64) }, { scope: { instanceUuid: currentInstance, operationUuid: '33333333-3333-4333-8333-333333333333', revision: 4 } }
  ])('does not promote an ambiguous recovery result %p', fields => {
    expect(() => requireSmbCurrentRecoveryResult(retainedResult(fields), currentScope, 'b'.repeat(64), 'ROLLED_BACK')).toThrow('SMB_CURRENT_RECOVERY_RESULT_UNVERIFIED')
  })
  it('shows only the approved public summary and clears consent on an instance-name change', async () => {
    const messages = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))
    const parameters = Object.fromEntries(['instanceid', 'operationid', 'recoverymode', 'currentreviewhash', 'maintenancewindow', 'confirmation', 'expectedrevision', 'idempotencykey'].map(name => [name, {}]))
    getAPI.mockResolvedValue({ liststorageserviceoperationsresponse: { storageserviceoperation: [] } })
    const wrapper = shallowMount(Widget, {
      props: { instanceId: currentInstance, instanceName: 'own-f1' },
      global: {
        mocks: { $t: key => messages[key] || key, $getApiParams: () => parameters, $store: { getters: { apis: { reconcileStorageServiceOperation: {}, reviewStorageServiceSmbIdentityRecovery: {}, repairStorageServiceSmbIdentity: {} } } } },
        stubs: {
          'a-modal': { props: ['visible'], template: '<div v-if="visible" class="recovery-dialog"><slot /></div>' },
          'a-alert': { props: ['message'], template: '<p>{{ message }}</p>' },
          'a-form': { template: '<div><slot /></div>' },
          'a-form-item': { template: '<div><slot /></div>' },
          'a-descriptions': { template: '<div><slot /></div>' },
          'a-descriptions-item': { template: '<div><slot /></div>' },
          'a-input': { props: ['value'], template: '<input :value="value" />' }
        }
      }
    })
    wrapper.vm.openRecovery({ id: currentOperation, revision: 4, state: 'RECOVERY_REQUIRED' })
    expect(wrapper.vm.recoveryMode).toBe('ORIGINAL_OPERATION')
    expect(getAPI.mock.calls.every(([command]) => command === 'listStorageServiceOperations')).toBe(true)
    await wrapper.setData({ recoveryMode: SMB_CURRENT_RETAIN_MODE })
    await wrapper.setData({ currentReview: currentReview(), currentMaintenanceApproved: true, currentConfirmation: 'own-f1' })
    expect(wrapper.find('.recovery-dialog').text()).toContain(messages['message.storage.operation.recovery.namespace.only'])
    expect(wrapper.find('.recovery-dialog').text()).not.toContain('S-1-5-21')
    await wrapper.setProps({ instanceName: 'renamed-f1' })
    expect(wrapper.vm.currentMaintenanceApproved).toBe(false); expect(wrapper.vm.currentConfirmation).toBe(''); expect(wrapper.vm.currentReview).toBe(null)
    expect(postAPI).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('cannot reuse a review when the selected operation revision changes during the read', async () => {
    let complete; getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = makeCurrentVm(); const pending = vm.reconcile(); vm.reconcileTarget.revision = 5
    complete(reviewResponse(currentReview())); await pending; expect(postAPI).not.toHaveBeenCalled()
  })
  it('accepts the exact public facts without treating namespace observation as loaded daemon SID', () => {
    const review = requireSmbCurrentRecoveryReview(currentReview(), currentScope)
    expect(review.publicFacts.namespaceObserved).toBe(true)
    expect(review.publicFacts.loadedDaemonSidVerified).toBe(false)
  })
  it('allows a same-hash explicit retry when the first POST never stored an approval', async () => {
    const vm = makeCurrentVm(); getAPI.mockResolvedValue(reviewResponse(currentReview()))
    postAPI.mockRejectedValue(new Error('unavailable'))
    await vm.reconcile(); const first = { ...vm.currentApprovedParameters }; await vm.reconcile()
    expect(postAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls[1]).toEqual(['repairStorageServiceSmbIdentity', first])
  })
  it.each(['', 'other-instance', null])('requires the exact user-entered instance name %p', async name => {
    const vm = makeCurrentVm(); vm.currentConfirmation = name
    await vm.reconcile(); expect(postAPI).not.toHaveBeenCalled(); expect(getAPI).not.toHaveBeenCalled()
  })
  it('does not stop or post without an explicit maintenance checkbox', async () => {
    const vm = makeCurrentVm(); vm.currentMaintenanceApproved = false
    await vm.reconcile(); expect(postAPI).not.toHaveBeenCalled()
  })
  it('blocks partial old API metadata without falling back to original reconcile', async () => {
    const vm = makeCurrentVm(); vm.$getApiParams = () => ({ instanceid: {} })
    await vm.reconcile(); expect(postAPI).not.toHaveBeenCalled()
  })
  it('drops consent when the fresh stable hash changes before the effect', async () => {
    const vm = makeCurrentVm(); getAPI.mockResolvedValue(reviewResponse(currentReview({ currentReviewHash: 'c'.repeat(64) })))
    await vm.reconcile(); expect(postAPI).not.toHaveBeenCalled(); expect(vm.currentMaintenanceApproved).toBe(false)
    expect(vm.currentConfirmation).toBe(''); expect(vm.recoveryMode).toBe(SMB_CURRENT_RETAIN_MODE)
  })
  it('cannot post a late review after the user cancels the dialog', async () => {
    let complete; getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = makeCurrentVm(); const pending = vm.reconcile(); vm.cancelRecovery()
    complete(reviewResponse(currentReview())); await pending; expect(postAPI).not.toHaveBeenCalled(); expect(vm.reconcileTarget).toBe(null)
  })
  it('cannot post after disposal or a different instance takes the view', async () => {
    let complete; getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = makeCurrentVm(); const pending = vm.reconcile(); vm.recoveryDisposed = true; vm.instanceId = '33333333-3333-4333-8333-333333333333'
    complete(reviewResponse(currentReview())); await pending; expect(postAPI).not.toHaveBeenCalled()
  })
  it('keeps CURRENT selected and prints no raw error after a review failure', async () => {
    const vm = makeCurrentVm(); getAPI.mockRejectedValue(new Error('synthetic-private-error'))
    await vm.readCurrentRecoveryReview(); expect(postAPI).not.toHaveBeenCalled(); expect(vm.error).not.toContain('synthetic-private-error')
    expect(vm.recoveryMode).toBe(SMB_CURRENT_RETAIN_MODE); expect(vm.currentMaintenanceApproved).toBe(false)
  })
  it('uses the same approved request after an unknown result, never original reconcile', async () => {
    const vm = makeCurrentVm(); getAPI.mockImplementation(command => Promise.resolve(command === 'reviewStorageServiceSmbIdentityRecovery'
      ? reviewResponse(currentReview({ approvalStored: postAPI.mock.calls.length > 0, resumeAllowed: postAPI.mock.calls.length > 0 })) : { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: { success: true, status: 'ROLLED_BACK', resultjson: JSON.stringify(retainedResult()) } } } }))
    postAPI.mockRejectedValueOnce(new Error('synthetic-private-error')).mockResolvedValueOnce({ repairstorageservicesmbidentityresponse: { jobid: 'job' } })
    await vm.reconcile(); const approved = { ...vm.currentApprovedParameters }
    expect(vm.error).not.toContain('synthetic-private-error'); expect(vm.recoveryMode).toBe(SMB_CURRENT_RETAIN_MODE)
    await vm.reconcile(); expect(postAPI).toHaveBeenCalledTimes(2)
    expect(postAPI.mock.calls.every(([command, params]) => command === 'repairStorageServiceSmbIdentity' && JSON.stringify(params) === JSON.stringify(approved))).toBe(true)
    expect(approved).toMatchObject({ operationid: currentOperation, currentreviewhash: 'b'.repeat(64), confirmation: 'own-f1', maintenancewindow: true, expectedrevision: 3, recoverymode: SMB_CURRENT_RETAIN_MODE })
    expect(vm.refresh).toHaveBeenCalledTimes(1); expect(vm.reconcileTarget).toBe(null)
  })
})

// Public HTTP response captured from the signed 500 runtime; clock is fixed to its observation epoch.
const signed500HttpReviewResponse = () => ({
  reviewstorageservicesmbidentityrecoveryresponse: {
    storageserviceruntime: {
      id: '3480bb2c-99ee-42f2-91cf-715ded5dd35f',
      operation: 'smb identity current-review',
      success: true,
      status: 'OBSERVED',
      details: 'Current local identity may be retained only with explicit maintenance approval; original identity restoration is false',
      resultjson: '{"schemaVersion":1,"kind":"CURRENT_LOCAL_SMB_IDENTITY_RECOVERY_REVIEW","success":true,"sideEffects":false,"scope":{"revision":4,"operationUuid":"cfe87d14-7b32-4b9c-999d-fcdae29dbb3d","instanceUuid":"3480bb2c-99ee-42f2-91cf-715ded5dd35f"},"currentReviewHash":"9ce63eb4dc517d768cc88fe4e2ba9158b43d6904365e3533c7b5eb953fefed00","sourceConfigurationSha256":"3f20d0533dab73092aa11b9005a7c15501c2056a2b026c5dd572eb16b3cb87bd","committedRevision":3,"generatedEpoch":1.791542657791E9,"maintenanceRequired":true,"publicFacts":{"bootId":"dc4b6429-35d3-4224-b199-3a0c6c829de0","databaseCount":2,"ownedMasterCount":2,"sessionsVerifiedEmpty":true,"namespaceObserved":true,"loadedDaemonSidVerified":false},"approvalStored":false,"resumeAllowed":false,"recoveryPhase":"RECOVERY_REQUIRED"}'
    }
  }
})
const signed500PublicReview = () => JSON.parse(signed500HttpReviewResponse().reviewstorageservicesmbidentityrecoveryresponse.storageserviceruntime.resultjson)
const signed500Vm = () => {
  const vm = makeCurrentVm(); const review = signed500PublicReview()
  vm.instanceId = review.scope.instanceUuid
  vm.instanceName = 'epic898-s-b6aa-all4-f1-20261009'
  vm.openRecovery({ id: review.scope.operationUuid, revision: review.scope.revision, state: 'RECOVERY_REQUIRED' })
  vm.recoveryMode = SMB_CURRENT_RETAIN_MODE
  return vm
}

describe('CURRENT recovery actual HTTP runtime envelopes', () => {
  beforeEach(() => {
    getAPI.mockReset(); postAPI.mockReset()
    jest.spyOn(Date, 'now').mockReturnValue((signed500PublicReview().generatedEpoch + 1) * 1000)
  })
  afterEach(() => jest.restoreAllMocks())

  it('loads the actual nested review into the production dialog approval state without posting', async () => {
    const vm = signed500Vm(); const review = signed500PublicReview()
    getAPI.mockResolvedValue(signed500HttpReviewResponse())
    await vm.readCurrentRecoveryReview()
    expect(getAPI).toHaveBeenCalledWith('reviewStorageServiceSmbIdentityRecovery', {
      instanceid: review.scope.instanceUuid, operationid: review.scope.operationUuid
    }, { preserveOnFailure: true, timeout: 60000 })
    expect(vm.currentReview).toEqual(review)
    expect(vm.currentReview.publicFacts).toEqual({ bootId: 'dc4b6429-35d3-4224-b199-3a0c6c829de0', databaseCount: 2, ownedMasterCount: 2, sessionsVerifiedEmpty: true, namespaceObserved: true, loadedDaemonSidVerified: false })
    expect(vm.currentRecoveryCanSubmit).toBe(false)
    vm.currentMaintenanceApproved = true; vm.currentConfirmation = 'other-instance'
    expect(vm.currentRecoveryCanSubmit).toBe(false)
    vm.currentConfirmation = vm.instanceName
    expect(vm.currentRecoveryCanSubmit).toBe(true)
    expect(postAPI).not.toHaveBeenCalled()
  })

  it('keeps nested null/type/error and stale/foreign review guards closed without using parent or cached review fields', async () => {
    const row = signed500HttpReviewResponse().reviewstorageservicesmbidentityrecoveryresponse.storageserviceruntime
    const review = signed500PublicReview()
    const runtimeResponses = [
      null, [row], { ...row, success: 'true' }, { ...row, status: 'RECOVERY_REQUIRED' }, { ...row, resultjson: '{' },
      ...[
        { scope: { ...review.scope, operationUuid: currentOperation } },
        { generatedEpoch: review.generatedEpoch - 61 }, { generatedEpoch: String(review.generatedEpoch) },
        { sourceConfigurationSha256: 'invalid' }, { sideEffects: 'false' },
        { publicFacts: { ...review.publicFacts, loadedDaemonSidVerified: true } }
      ].map(fields => ({ ...row, resultjson: JSON.stringify({ ...review, ...fields }) }))
    ]
    const responses = runtimeResponses.map(value => ({ reviewstorageservicesmbidentityrecoveryresponse: { ...row, storageserviceruntime: value } }))
    responses.push({ errorresponse: { errorcode: 530, errortext: 'synthetic-error-must-not-display' } })
    for (const response of responses) {
      const vm = signed500Vm(); vm.currentReview = review; vm.currentMaintenanceApproved = true; vm.currentConfirmation = vm.instanceName
      getAPI.mockResolvedValue(response)
      await vm.readCurrentRecoveryReview()
      expect(vm.currentReview).toBe(null)
      expect(vm.currentRecoveryCanSubmit).toBe(false)
      expect(vm.currentMaintenanceApproved).toBe(false)
      expect(vm.currentConfirmation).toBe('')
      expect(vm.error).toBe('message.storage.operation.recovery.review.failed')
      expect(vm.recoveryMode).toBe(SMB_CURRENT_RETAIN_MODE)
    }
    expect(postAPI).not.toHaveBeenCalled()
  })

  it.each([false, true])('verifies the production immediate/job=%s wrapped repair result before closing the dialog', async job => {
    const vm = signed500Vm(); const review = signed500PublicReview()
    vm.currentReview = review; vm.currentMaintenanceApproved = true; vm.currentConfirmation = vm.instanceName
    const terminal = { id: vm.instanceId, success: true, status: 'ROLLED_BACK', resultjson: JSON.stringify(retainedResult({ scope: review.scope, currentReviewHash: review.currentReviewHash })) }
    getAPI.mockImplementation(command => Promise.resolve(command === 'queryAsyncJobResult'
      ? { queryasyncjobresultresponse: { jobstatus: 1, jobresult: { storageserviceruntime: terminal } } } : signed500HttpReviewResponse()))
    postAPI.mockResolvedValue({ repairstorageservicesmbidentityresponse: job ? { jobid: 'repair-job' } : { storageserviceruntime: terminal } })
    await vm.reconcile()
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('repairStorageServiceSmbIdentity', {
      instanceid: review.scope.instanceUuid,
      operationid: review.scope.operationUuid,
      recoverymode: SMB_CURRENT_RETAIN_MODE,
      currentreviewhash: review.currentReviewHash,
      maintenancewindow: true,
      confirmation: 'epic898-s-b6aa-all4-f1-20261009',
      expectedrevision: 3,
      idempotencykey: 'smb-current-retain:' + review.scope.operationUuid + ':' + review.currentReviewHash
    })
    expect(getAPI.mock.calls.some(([command]) => command === 'queryAsyncJobResult')).toBe(job)
    expect(vm.refresh).toHaveBeenCalledTimes(1)
    expect(vm.reconcileTarget).toBe(null)
    expect(vm.error).toBe('')
  })

  it.each([false, true])('rejects immediate/job=%s bad typed rows or terminal flags without original-reconcile fallback', async job => {
    const review = signed500PublicReview()
    const result = retainedResult({ scope: review.scope, currentReviewHash: review.currentReviewHash })
    const terminal = { success: true, status: 'ROLLED_BACK', resultjson: JSON.stringify(result) }
    const invalidRows = [
      null, [terminal], { ...terminal, success: 'true' }, { ...terminal, status: 'OBSERVED' },
      ...[
        { currentIdentityRetained: 'true' }, { originalIdentityRestored: true }, { dataChanged: true },
        { scope: { ...review.scope, operationUuid: currentOperation } }, { currentReviewHash: 'c'.repeat(64) }
      ].map(fields => ({ ...terminal, resultjson: JSON.stringify({ ...result, ...fields }) }))
    ]
    for (const invalid of invalidRows) {
      getAPI.mockReset(); postAPI.mockReset()
      const vm = signed500Vm(); vm.currentReview = review; vm.currentMaintenanceApproved = true; vm.currentConfirmation = vm.instanceName
      const wrapped = { ...terminal, storageserviceruntime: invalid }
      getAPI.mockImplementation(command => Promise.resolve(command === 'queryAsyncJobResult'
        ? { queryasyncjobresultresponse: { jobstatus: 1, jobresult: wrapped } } : signed500HttpReviewResponse()))
      postAPI.mockResolvedValue({ repairstorageservicesmbidentityresponse: job ? { jobid: 'repair-job' } : wrapped })
      await vm.reconcile()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI.mock.calls[0][0]).toBe('repairStorageServiceSmbIdentity')
      expect(vm.refresh).not.toHaveBeenCalled()
      expect(vm.reconcileTarget.id).toBe(review.scope.operationUuid)
      expect(vm.recoveryMode).toBe(SMB_CURRENT_RETAIN_MODE)
      expect(vm.error).toBe('message.storage.operation.recovery.current.failed')
    }
  })
})
