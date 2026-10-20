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
import { getAPI, postAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const zone = '11111111-1111-4111-8111-111111111111'
const systemId = '22222222-2222-4222-8222-222222222222'
const userId = '33333333-3333-4333-8333-333333333333'
const systemTemplate = (extra = {}) => ({
  id: systemId,
  name: 'ready-private-system',
  templatetype: 'SYSTEM',
  ispublic: false,
  isready: true,
  isdynamicallyscalable: true,
  hypervisor: 'KVM',
  arch: 'x86_64',
  format: 'QCOW2',
  zoneid: zone,
  checksum: 'a'.repeat(64),
  ...extra
})
const userTemplate = (extra = {}) => systemTemplate({
  id: userId,
  name: 'owned-private-user',
  templatetype: 'USER',
  account: 'owner',
  domainid: 'domain',
  ...extra
})
const response = rows => ({ listtemplatesresponse: { template: rows } })
const deferred = () => {
  let complete
  const promise = new Promise(resolve => { complete = resolve })
  return { promise, resolve: complete }
}
const context = (mode = 'NEW') => {
  const data = Widget.data()
  const vm = {
    ...data,
    targetMode: 'CREATE_NEW',
    cloneTemplateSelection: 'EXPLICIT',
    cloneRequiresIdentityTemplate: false,
    instanceId: 'instance',
    planTarget: { id: 'artifact' },
    clone: { ...data.clone, zoneid: zone, backingvolumemode: mode },
    resource: { account: 'owner', domainid: 'domain' },
    $store: { getters: { project: null, userInfo: { roletype: 'Admin' } } },
    $t: value => value,
    can: () => true
  }
  for (const [name, method] of Object.entries(Widget.methods)) vm[name] = method.bind(vm)
  vm.can = () => true
  return vm
}

describe('ordinary CREATE_NEW template discovery', () => {
  beforeEach(() => {
    getAPI.mockReset()
    postAPI.mockReset()
  })

  it.each(['NEW', 'EXISTING'])('lists accessible private and public SYSTEM images for %s DATA', async mode => {
    const vm = context(mode)
    const privateSystem = systemTemplate()
    const publicSystem = systemTemplate({ id: '44444444-4444-4444-8444-444444444444', ispublic: true })
    getAPI.mockImplementation((_, params) => Promise.resolve(response(params.templatetype === 'SYSTEM'
      ? [privateSystem, publicSystem]
      : [privateSystem, userTemplate()])))
    await vm.loadCloneTemplates()
    expect(vm.cloneTemplateRows.map(row => row.id)).toEqual(mode === 'NEW' ? [systemId, userId, publicSystem.id] : [systemId, publicSystem.id])
    expect(vm.cloneTemplateRows.filter(row => row.id === systemId)).toHaveLength(1)
    expect(getAPI).toHaveBeenCalledWith('listTemplates', expect.objectContaining({ templatetype: 'SYSTEM', zoneid: zone }), expect.any(Object))
    expect(getAPI.mock.calls.every(([, params]) => !Object.prototype.hasOwnProperty.call(params, 'system'))).toBe(true)
    expect(vm.clone.templateid).toBeUndefined()
    vm.clone.templateid = systemId
    expect(() => vm.assertOrdinaryCloneTemplate()).not.toThrow()
    expect(postAPI).not.toHaveBeenCalled()
  })

  it.each([
    { isready: false },
    { isdynamicallyscalable: false },
    { arch: 'aarch64' },
    { hypervisor: 'VMware' },
    { format: 'RAW' },
    { checksum: '' },
    { removed: '2026-10-19' },
    { templatestate: 'Inactive' },
    { zoneid: 'other' },
    { id: 'malformed' }
  ])('keeps common SYSTEM compatibility checks for %j', extra => {
    expect(context('EXISTING').eligibleOrdinaryCloneTemplate(systemTemplate(extra))).toBe(false)
  })

  it('restricts private USER to owned NEW DATA and still requires its approval', async () => {
    const row = userTemplate()
    getAPI.mockResolvedValue(response([row]))
    const vm = context()
    await vm.loadCloneTemplates()
    vm.clone.templateid = row.id
    expect(() => vm.assertOrdinaryCloneTemplate()).toThrow('message.storage.config.clone.template.approval.required')
    vm.clone.validationartifactuuid = '55555555-5555-4555-8555-555555555555'
    vm.clone.validationartifactsha256 = 'b'.repeat(64)
    expect(() => vm.assertOrdinaryCloneTemplate()).not.toThrow()
    expect(context('EXISTING').eligibleOrdinaryCloneTemplate(row)).toBe(false)
    for (const extra of [{ account: 'foreign' }, { domainid: 'foreign' }, { ispublic: true }]) {
      expect(vm.eligibleOrdinaryCloneTemplate(userTemplate(extra))).toBe(false)
    }
  })

  it('invalidates a USER selection and review when switching to existing DATA without clearing volume/network choices', async () => {
    const vm = context()
    getAPI.mockResolvedValue(response([systemTemplate(), userTemplate()]))
    await vm.loadCloneTemplates()
    Object.assign(vm.clone, { templateid: userId, validationartifactuuid: 'approval', validationartifactsha256: 'digest', networkid: 'network' })
    Object.assign(vm, { planPhase: 'REVIEW', planToken: 'review-token', reviewedPlan: 'fingerprint', confirmation: 'confirmed', credentialValues: { rule: { password: 'synthetic' } }, restoreMaintenance: true, volumeMapping: { original: 'preserved-data' } })
    vm.clone.backingvolumemode = 'EXISTING'
    await Widget.watch['clone.backingvolumemode'].call(vm)
    expect(vm.cloneTemplateRows.map(row => row.id)).toEqual([systemId])
    expect(vm.clone.templateid).toBeUndefined()
    expect(vm.clone.validationartifactuuid).toBeUndefined()
    expect(vm.clone.validationartifactsha256).toBeUndefined()
    expect(vm.planPhase).toBe('MAPPING')
    expect(vm.planToken).toBe('')
    expect(vm.reviewedPlan).toBe('')
    expect(vm.confirmation).toBe('')
    expect(vm.credentialValues).toEqual({})
    expect(vm.restoreMaintenance).toBe(false)
    expect(vm.clone.networkid).toBe('network')
    expect(vm.volumeMapping).toEqual({ original: 'preserved-data' })
  })

  it('retains a still-eligible SYSTEM selection across DATA modes and rejects a mode change before review refresh', async () => {
    const vm = context()
    getAPI.mockResolvedValue(response([systemTemplate()]))
    await vm.loadCloneTemplates()
    vm.clone.templateid = systemId
    vm.clone.backingvolumemode = 'EXISTING'
    expect(() => vm.assertOrdinaryCloneTemplate()).toThrow('message.storage.config.clone.template.required')
    await Widget.watch['clone.backingvolumemode'].call(vm)
    expect(vm.clone.templateid).toBe(systemId)
    expect(vm.cloneTemplateBackingMode).toBe('EXISTING')
    expect(() => vm.assertOrdinaryCloneTemplate()).not.toThrow()
  })

  it('ignores a delayed NEW response after the EXISTING candidate list has completed', async () => {
    const vm = context()
    const old = [deferred(), deferred()]
    getAPI.mockImplementationOnce(() => old[0].promise).mockImplementationOnce(() => old[1].promise)
    const first = vm.loadCloneTemplates()
    vm.clone.backingvolumemode = 'EXISTING'
    getAPI.mockResolvedValue(response([systemTemplate()]))
    await Widget.watch['clone.backingvolumemode'].call(vm)
    old[0].resolve(response([userTemplate()]))
    old[1].resolve(response([userTemplate()]))
    await first
    expect(vm.cloneTemplateRows.map(row => row.id)).toEqual([systemId])
    expect(vm.cloneTemplateBackingMode).toBe('EXISTING')
    expect(vm.cloneTemplateLoading).toBe(false)
  })

  it.each([{ cloneRequiresIdentityTemplate: true }, { cloneResume: { targetName: 'recorded-target' } }, { cloneResumeUnavailable: true }])('keeps AD and frozen resume state out of the ordinary mode watcher: %j', async extra => {
    const vm = Object.assign(context(), extra, { planToken: 'frozen', reviewedPlan: 'review' })
    await Widget.watch['clone.backingvolumemode'].call(vm)
    expect(vm.planToken).toBe('frozen')
    expect(vm.reviewedPlan).toBe('review')
    expect(getAPI).not.toHaveBeenCalled()
  })
})
