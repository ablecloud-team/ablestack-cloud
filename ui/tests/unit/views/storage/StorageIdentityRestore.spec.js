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

import Widget from '@/views/storage/StorageServiceConfiguration'
const descriptor = () => ({ schemaVersion: 1, kind: 'STORAGE_AD_SEMANTIC_SOURCE', ownerArtifactUuid: '11111111-1111-4111-8111-111111111111', sourceInstanceUuid: '22222222-2222-4222-8222-222222222222', sourceOperationUuid: '33333333-3333-4333-8333-333333333333', sourceConfigurationSha256: 'a'.repeat(64), ciphertextSha256: 'b'.repeat(64), issuerMac: 'c'.repeat(64) })
const context = (identity = true) => {
  const vm = {
    instanceId: 'instance-a',
    planTarget: { id: 'backup-a' },
    planToken: 'review-token',
    confirmation: 'target-service',
    restoreMaintenance: false,
    plan: { targetName: 'target-service', artifactSha256: 'd'.repeat(64), ...(identity ? { adIdentityRestoreRequiresMaintenance: true, adIdentitySourceDescriptor: descriptor() } : {}) },
    credentialValues: { rule: { password: 'synthetic-input' } },
    $t: key => key,
    $getApiParams: () => ({ maintenancewindow: {} }),
    can: () => true,
    mutation: jest.fn().mockResolvedValue({}),
    refresh: jest.fn().mockResolvedValue(),
    validIdentityDescriptor: Widget.methods.validIdentityDescriptor,
    restoreReviewFingerprint: Widget.methods.restoreReviewFingerprint,
    closePlan: Widget.methods.closePlan
  }
  vm.reviewedPlan = vm.restoreReviewFingerprint()
  return vm
}
const apply = vm => Widget.methods.applyPlan.call(vm)

describe('AD identity restore reviewed source and maintenance', () => {
  it('preserves ordinary restore without adding maintenance or source authorization fields', async () => {
    const vm = context(false); await apply(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    expect(vm.mutation.mock.calls[0][1].maintenancewindow).toBeUndefined()
    expect(vm.mutation.mock.calls[0][1].sourceauth).toBeUndefined()
    expect(vm.refresh).toHaveBeenCalledTimes(1)
  })

  it('requires explicit interruption approval without downgrading an identity plan to ordinary restore', async () => {
    const vm = context(); await apply(vm); await apply(vm)
    expect(vm.mutation).not.toHaveBeenCalled()
    expect(vm.plan.adIdentityRestoreRequiresMaintenance).toBe(true)
    expect(vm.error).toBe('message.storage.service.ad.maintenance.required')
  })

  it('sends literal maintenance with the reviewed token/name and never a caller sourceauth JSON', async () => {
    const vm = context(); vm.restoreMaintenance = true
    vm.mutation.mockImplementation(async (api, request) => {
      expect(api).toBe('applyStorageServiceConfigRestore')
      expect(request).toMatchObject({ artifactid: 'backup-a', plantoken: 'review-token', confirmation: 'target-service', maintenancewindow: true })
      expect(request.sourceauth).toBeUndefined()
      expect(request.adIdentitySourceDescriptor).toBeUndefined()
      expect(JSON.parse(request.credentials)).toEqual({ rule: { password: 'synthetic-input' } })
    })
    await apply(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    expect(vm.mutation.mock.calls[0][1].credentials).toBe('')
    expect(vm.restoreMaintenance).toBe(false)
  })

  it('blocks wrong name, missing/string token and changed artifact SHA before effects', async () => {
    for (const extra of [{ confirmation: 'wrong' }, { planToken: '' }, { planToken: true }]) {
      const vm = { ...context(), restoreMaintenance: true, ...extra }; await apply(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
    }
    const changed = context(); changed.restoreMaintenance = true; changed.plan.artifactSha256 = 'e'.repeat(64); await apply(changed)
    expect(changed.mutation).not.toHaveBeenCalled()
    expect(changed.restoreMaintenance).toBe(false)
  })

  it('rejects malformed or mismatched typed plan/descriptor instead of using plaintext restore', async () => {
    for (const mutate of [plan => { plan.adIdentityRestoreRequiresMaintenance = 'true' }, plan => { delete plan.adIdentitySourceDescriptor }, plan => { plan.adIdentityRestoreRequiresMaintenance = false }, plan => { plan.adIdentitySourceDescriptor.schemaVersion = '1' }, plan => { plan.artifactSha256 = ['d'.repeat(64)] }]) {
      const vm = context(); mutate(vm.plan); vm.reviewedPlan = vm.restoreReviewFingerprint(); vm.restoreMaintenance = true; await apply(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
      expect(vm.error).toBe('message.storage.service.ad.receipt.unverified')
    }
  })

  it('rejects missing permission or maintenance schema for both apply and LKG', async () => {
    const denied = context(); denied.restoreMaintenance = true; denied.can = () => false; await apply(denied)
    expect(denied.mutation).not.toHaveBeenCalled()
    for (const lkgPlan of [false, true]) {
      const vm = context(); vm.restoreMaintenance = true; vm.lkgPlan = lkgPlan; vm.$getApiParams = () => ({}); await apply(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
      expect(vm.error).toBe('message.storage.service.ad.maintenance.required')
    }
  })

  it('forwards explicit maintenance for a supported LKG command', async () => {
    const vm = context(); vm.lkgPlan = true; vm.restoreMaintenance = true; await apply(vm)
    expect(vm.mutation.mock.calls[0][0]).toBe('restoreStorageServiceLastKnownGood')
    expect(vm.mutation.mock.calls[0][1].maintenancewindow).toBe(true)
  })

  it('resets approval when a reviewed plan or token changes', () => {
    const vm = context(); vm.restoreMaintenance = true; vm.plan.targetName = 'changed'
    Widget.watch.plan.handler.call(vm)
    expect(vm.restoreMaintenance).toBe(false)
    vm.restoreMaintenance = true; vm.planToken = 'changed-token'; Widget.watch.planToken.call(vm)
    expect(vm.restoreMaintenance).toBe(false)
  })

  it('does not refresh the new service after a pending restore changes scope', async () => {
    const vm = context(); vm.restoreMaintenance = true; vm.mutation.mockImplementation(async () => { vm.instanceId = 'foreign' })
    await apply(vm)
    expect(vm.refresh).not.toHaveBeenCalled()
    expect(vm.mutation.mock.calls[0][1].credentials).toBe('')
  })

  it('preserves failure and clears credentials without automatic retry or fallback', async () => {
    const vm = context(); vm.restoreMaintenance = true; vm.mutation.mockRejectedValue({ config: { data: 'untrusted request details' } })
    await apply(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    expect(vm.refresh).not.toHaveBeenCalled()
    expect(vm.mutation.mock.calls[0][1].credentials).toBe('')
    expect(vm.error).toBe('message.storage.config.failed')
  })

  it('closes the review without reusing the maintenance approval', () => {
    const vm = context(); vm.restoreMaintenance = true; vm.closePlan()
    expect(vm.restoreMaintenance).toBe(false)
    expect(vm.reviewedPlan).toBe('')
    expect(vm.planToken).toBe('')
  })
})
