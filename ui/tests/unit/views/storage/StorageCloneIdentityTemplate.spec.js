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
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const templateId = '11111111-1111-4111-8111-111111111111'
const otherId = '22222222-2222-4222-8222-222222222222'
const descriptor = () => ({ schemaVersion: 1, kind: 'STORAGE_AD_SEMANTIC_SOURCE', ownerArtifactUuid: templateId, sourceInstanceUuid: otherId, sourceOperationUuid: '33333333-3333-4333-8333-333333333333', sourceConfigurationSha256: 'a'.repeat(64), ciphertextSha256: 'b'.repeat(64), issuerMac: 'c'.repeat(64) })
const template = (extra = {}) => ({ id: templateId, name: 'fresh-system', templatetype: 'SYSTEM', ispublic: true, isready: true, isdynamicallyscalable: true, hypervisor: 'KVM', arch: 'x86_64', zoneid: 'zone-a', details: { 'storage.service.local.identity.seed.absent': 'true' }, ...extra })
const context = (identity = true) => {
  const vm = {
    ...Widget.data(),
    instanceId: 'source-a',
    planTarget: { id: 'artifact-a', metadata: identity ? { adIdentityCoverage: 'VERIFIED_ENCRYPTED_FULL_IDENTITY', adIdentitySourceDescriptor: descriptor() } : {} },
    resource: { account: 'owner', domainid: 'domain-a' },
    targetMode: 'CREATE_NEW',
    clone: { name: 'new-service', zoneid: 'zone-a', serviceofferingid: 'offering-a', size: 20, backingvolumemode: 'NEW' },
    initialVolumeSource: 'source-volume',
    cloneRuntime: 'runtime-a',
    $t: key => key,
    $store: { getters: { apis: { listTemplates: {}, applyStorageServiceConfigRestore: {} }, userInfo: { roletype: 'Admin' } } },
    $getApiParams: () => ({ maintenancewindow: {} })
  }
  for (const [name, method] of Object.entries(Widget.methods)) vm[name] = method
  Object.defineProperty(vm, 'cloneRequiresIdentityTemplate', { get: () => Widget.computed.cloneRequiresIdentityTemplate.call(vm) })
  vm.assertCloneOffering = jest.fn()
  vm.refresh = jest.fn().mockResolvedValue()
  vm.mutation = jest.fn(async command => {
    if (command !== 'planStorageServiceConfigRestore') return {}
    return { planToken: 'sealed-token', metadata: { plan: { targetName: 'new-service', targetMode: 'CREATE_NEW', createNew: { templateid: vm.clone.templateid }, blockers: [], requiredCredentials: [], artifactSha256: 'd'.repeat(64), ...(identity ? { adIdentityRestoreRequiresMaintenance: true, adIdentitySourceDescriptor: descriptor() } : {}) } } }
  })
  return vm
}
const load = vm => vm.loadCloneTemplates()
const ready = async vm => {
  getAPI.mockResolvedValue({ listtemplatesresponse: { template: [template()] } })
  await load(vm); vm.clone.templateid = templateId
}

describe('AD CREATE_NEW explicit public SYSTEM identity template', () => {
  beforeEach(() => getAPI.mockReset())

  it('lists only fresh public SYSTEM Ready KVM scalable x86_64 rows in the selected zone and does not auto-select', async () => {
    getAPI.mockResolvedValue({ listtemplatesresponse: { template: [template(), template({ id: otherId, templatetype: 'USER' })] } })
    const vm = context(); await load(vm)
    expect(vm.cloneOptions.templates.map(row => row.value)).toEqual([templateId])
    expect(vm.clone.templateid).toBeUndefined()
    expect(getAPI).toHaveBeenCalledWith('listTemplates', { zoneid: 'zone-a', hypervisor: 'KVM', system: true, templatefilter: 'all', listall: true, account: 'owner', domainid: 'domain-a' }, { timeout: 15000, preserveOnFailure: true })
  })

  it('rejects unknown/string flags, missing/false seed metadata and foreign/private template categories', () => {
    const vm = context()
    for (const extra of [{ id: 1 }, { id: 'foreign' }, { ispublic: false }, { ispublic: 'true' }, { templatetype: 'USER' }, { templatetype: 'BUILTIN' }, { isready: 'true' }, { isdynamicallyscalable: false }, { hypervisor: 'XenServer' }, { arch: 'aarch64' }, { arch: undefined }, { zoneid: 'other' }, { details: undefined }, { details: { 'storage.service.local.identity.seed.absent': true } }, { details: { 'storage.service.local.identity.seed.absent': 'false' } }]) expect(vm.eligibleCloneIdentityTemplate(template(extra))).toBe(false)
  })

  it('uses executable template permissions for users and the exact project owner context', async () => {
    const vm = context(); vm.resource = { projectid: 'project-a' }; vm.$store.getters.userInfo.roletype = 'User'; await ready(vm)
    expect(getAPI.mock.calls[0][1]).toEqual({ zoneid: 'zone-a', hypervisor: 'KVM', system: true, templatefilter: 'executable', projectid: 'project-a' })
    expect(getAPI.mock.calls[0][1]).not.toHaveProperty('listall')
  })

  it('does not fetch or forward a template field for an ordinary non-AD clone', async () => {
    const vm = context(false); vm.clone.templateid = templateId
    await load(vm); await vm.preparePlan()
    expect(getAPI).not.toHaveBeenCalled()
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    expect(JSON.parse(vm.mutation.mock.calls[0][1].mapping).createNew).not.toHaveProperty('templateid')
  })

  it('blocks missing or malformed source coverage/descriptor without a plain-clone fallback', async () => {
    for (const change of [metadata => { delete metadata.adIdentitySourceDescriptor }, metadata => { delete metadata.adIdentityCoverage }, metadata => { metadata.adIdentityCoverage = 'true' }, metadata => { metadata.adIdentitySourceDescriptor.schemaVersion = '1' }]) {
      const vm = context(); change(vm.planTarget.metadata); await ready(vm); await vm.preparePlan()
      expect(vm.mutation).not.toHaveBeenCalled()
      expect(vm.cloneRequiresIdentityTemplate).toBe(true)
      expect(vm.error).toBe('message.storage.service.ad.receipt.unverified')
    }
  })

  it('blocks missing, foreign or metadata-changed choices before the prepare POST', async () => {
    for (const change of [vm => { delete vm.clone.templateid }, vm => { vm.clone.templateid = otherId }, vm => { vm.cloneTemplateRows[0].details['storage.service.local.identity.seed.absent'] = 'false' }]) {
      const vm = context(); await ready(vm); change(vm); await vm.preparePlan()
      expect(vm.mutation).not.toHaveBeenCalled()
    }
  })

  it('forwards only the explicit template UUID in createNew and requires the sealed plan to retain it', async () => {
    const vm = context(); await ready(vm); await vm.preparePlan()
    const request = JSON.parse(vm.mutation.mock.calls[0][1].mapping)
    expect(request.createNew.templateid).toBe(templateId)
    expect(request.createNew.validationartifactuuid).toBeUndefined()
    expect(request.sourceauth).toBeUndefined()
    expect(vm.planPhase).toBe('REVIEW')
    vm.confirmation = 'new-service'; vm.restoreMaintenance = true; await vm.applyPlan()
    expect(vm.mutation).toHaveBeenCalledTimes(2)
    expect(vm.mutation.mock.calls[1][1].plantoken).toBe('sealed-token')
    expect(vm.mutation.mock.calls[1][1]).not.toHaveProperty('templateid')
  })

  it('blocks failed or unsupported template discovery and clears an earlier selection', async () => {
    for (const unsupported of [false, true]) {
      const vm = context(); await ready(vm)
      if (unsupported) delete vm.$store.getters.apis.listTemplates
      else getAPI.mockRejectedValue(new Error('controlled timeout'))
      await load(vm); await vm.preparePlan()
      expect(vm.cloneOptions.templates).toEqual([])
      expect(vm.clone.templateid).toBeUndefined()
      expect(vm.cloneTemplateError).toBe(true)
      expect(vm.mutation).not.toHaveBeenCalled()
    }
  })

  it('discards an old zone response after a newer discovery', async () => {
    let finish
    getAPI.mockImplementation((_, parameters) => parameters.zoneid === 'zone-a' ? new Promise(resolve => { finish = resolve }) : Promise.resolve({ listtemplatesresponse: { template: [template({ id: otherId, zoneid: 'zone-b' })] } }))
    const vm = context(); const old = load(vm); vm.clone.zoneid = 'zone-b'; await load(vm)
    finish({ listtemplatesresponse: { template: [template()] } }); await old
    expect(vm.cloneOptions.templates.map(row => row.value)).toEqual([otherId])
  })

  it('rejects a pending result from a different owner or authenticated source', async () => {
    for (const change of [vm => { vm.resource.account = 'foreign' }, vm => { vm.planTarget.metadata.adIdentitySourceDescriptor.sourceOperationUuid = otherId }]) {
      let finish; getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
      const vm = context(); const pending = load(vm); change(vm); finish({ listtemplatesresponse: { template: [template()] } }); await pending
      expect(vm.cloneTemplateScope).toBeNull()
      expect(vm.cloneOptions.templates).toEqual([])
    }
  })

  it('invalidates the selection, token and approval when owner, source metadata or selected template changes', async () => {
    for (const callback of [vm => Widget.watch['resource.account'].call(vm), vm => Widget.watch['planTarget.metadata'].handler.call(vm)]) {
      const vm = context(); await ready(vm); await vm.preparePlan(); vm.restoreMaintenance = true; callback(vm)
      expect(vm.clone.templateid).toBeUndefined(); expect(vm.planToken).toBe(''); expect(vm.restoreMaintenance).toBe(false)
    }
    const vm = context(); await ready(vm); await vm.preparePlan(); vm.restoreMaintenance = true; Widget.watch['clone.templateid'].call(vm)
    expect(vm.planToken).toBe(''); expect(vm.restoreMaintenance).toBe(false)
  })

  it('rejects a changed sealed selection or missing seed before apply effects', async () => {
    for (const change of [vm => { vm.plan.createNew.templateid = otherId }, vm => { vm.clone.templateid = otherId }, vm => { delete vm.cloneTemplateRows[0].details['storage.service.local.identity.seed.absent'] }]) {
      const vm = context(); await ready(vm); await vm.preparePlan(); vm.confirmation = 'new-service'; vm.restoreMaintenance = true
      change(vm); vm.reviewedPlan = vm.restoreReviewFingerprint(); await vm.applyPlan()
      expect(vm.mutation).toHaveBeenCalledTimes(1)
      expect(vm.restoreMaintenance).toBe(false)
    }
  })

  it('keeps a newly discovered AD plan in mapping when its original metadata did not provide identity evidence', async () => {
    const vm = context(false)
    vm.mutation.mockResolvedValue({ planToken: 'sealed', metadata: { plan: { blockers: [], requiredCredentials: [], createNew: {}, adIdentitySourceDescriptor: descriptor(), adIdentityRestoreRequiresMaintenance: true } } })
    getAPI.mockResolvedValue({ listtemplatesresponse: { template: [template()] } })
    await vm.preparePlan()
    expect(vm.planPhase).toBe('MAPPING')
    expect(vm.cloneRequiresIdentityTemplate).toBe(true)
    expect(vm.cloneOptions.templates.map(row => row.value)).toEqual([templateId])
    expect(vm.clone.templateid).toBeUndefined()
  })

  it('does not accept an old pending plan after selection, target mode or owner changes', async () => {
    for (const change of [vm => { vm.clone.templateid = otherId }, vm => { vm.targetMode = 'RESTORE_EXISTING' }, vm => { vm.resource.account = 'foreign' }]) {
      const vm = context(); await ready(vm)
      let finish
      vm.mutation.mockImplementation(() => new Promise(resolve => { finish = resolve }))
      const pending = vm.preparePlan(); change(vm)
      finish({ planToken: 'old', metadata: { plan: { blockers: [], requiredCredentials: [] } } }); await pending
      expect(vm.plan).toBeNull(); expect(vm.planToken).toBe(''); expect(vm.error).toBe('message.storage.config.scope.changed')
    }
  })

  it('does not reuse template discovery after close or disposal', async () => {
    for (const close of [vm => vm.closePlan(), vm => Widget.beforeUnmount.call(vm)]) {
      const vm = context(); await ready(vm); close(vm)
      expect(vm.cloneTemplateScope).toBeNull(); expect(vm.cloneTemplateRows).toEqual([])
    }
  })
})
