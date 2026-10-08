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
const a = '11111111-1111-4111-8111-111111111111'
const b = '22222222-2222-4222-8222-222222222222'
const root = '33333333-3333-4333-8333-333333333333'
const offering = (id, extra = {}) => ({ id, name: id, provisioningtype: 'sparse', diskofferingid: root, ...extra })
const context = () => {
  const vm = {
    instanceId: 'source-a',
    planTarget: { id: 'artifact-a' },
    resource: { account: 'owner', domainid: 'domain-a' },
    clone: { zoneid: 'zone-a', name: 'new-target', size: 20 },
    cloneOptionToken: 0,
    cloneOptionScope: null,
    cloneOfferingRows: [],
    cloneOfferingLoading: false,
    cloneOfferingError: false,
    cloneOptions: { networks: [{ value: 'old' }], offerings: [{ value: 'old' }], disks: [], pools: [], volumes: [] },
    $t: key => key,
    $store: { getters: {} },
    volumeMapping: {},
    newVolumeSpecs: {},
    targetMode: 'CREATE_NEW',
    initialVolumeSource: 'initial',
    cloneRuntime: 'runtime-a',
    mutation: jest.fn().mockResolvedValue({ metadata: { plan: { blockers: [], requiredCredentials: [] } }, planToken: 'new-plan' })
  }
  for (const name of ['cloneDiscoveryScope', 'sparseCloneRootOffering', 'clearCloneZoneOptions', 'assertCloneOffering', 'options']) vm[name] = Widget.methods[name]
  return vm
}
const mock = (rows, constraints = rows.map(row => ({ id: row.id, compatible: true }))) => getAPI.mockImplementation(async command => {
  if (command === 'listServiceOfferings') return { listserviceofferingsresponse: { serviceoffering: rows } }
  if (command === 'listStorageServiceOfferingConstraints') return { liststorageserviceofferingconstraintsresponse: { storageserviceofferingconstraint: constraints } }
  if (command === 'listNetworks') return { listnetworksresponse: { network: [] } }
  if (command === 'listDiskOfferings') return { listdiskofferingsresponse: { diskoffering: [] } }
  if (command === 'listStoragePools') return { liststoragepoolsresponse: { storagepool: [] } }
  return { listvolumesresponse: { volume: [] } }
})
const load = vm => Widget.methods.loadCloneZoneOptions.call(vm)
const plan = vm => Widget.methods.preparePlan.call(vm)

describe('CREATE_NEW clone ROOT and compute offering discovery', () => {
  beforeEach(() => getAPI.mockReset())

  it('clears old arrays/default immediately and chooses only SPARSE/FAT plus approved compute constraints', async () => {
    for (const provisioningtype of ['sparse', 'fat']) {
      mock([offering(a, { provisioningtype: 'thin' }), offering(b, { provisioningtype })])
      const vm = context(); const pending = load(vm)
      expect(vm.cloneOptions.offerings).toEqual([])
      expect(vm.clone.serviceofferingid).toBeUndefined()
      await pending
      expect(vm.cloneOptions.offerings.map(row => row.value)).toEqual([b])
      expect(vm.clone.serviceofferingid).toBe(b)
    }
  })

  it('does not allow compute failures, unchecked IDs or string compatibility', async () => {
    for (const constraints of [[{ id: a, compatible: false }], [], [{ id: a, compatible: 'true' }]]) {
      mock([offering(a)], constraints); const vm = context(); await load(vm)
      expect(vm.cloneOptions.offerings).toEqual([])
      expect(vm.clone.serviceofferingid).toBeUndefined()
      await plan(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
    }
  })

  it('requires literal provisioning and exact linked ROOT UUID', () => {
    const vm = context()
    for (const extra of [{ provisioningtype: ['sparse'] }, { provisioningtype: 'unknown' }, { provisioningtype: true }, { diskofferingid: undefined }, { diskofferingid: 'foreign' }, { id: 1 }]) expect(vm.sparseCloneRootOffering(offering(a, extra))).toBe(false)
  })

  it('uses the target owner/project context for normal offering discovery', async () => {
    mock([offering(a)]); const vm = context(); vm.resource = { projectid: 'project-a' }; await load(vm)
    const request = getAPI.mock.calls.find(call => call[0] === 'listServiceOfferings')[1]
    expect(request).toEqual({ zoneid: 'zone-a', issystem: false, projectid: 'project-a' })
    expect(getAPI.mock.calls.find(call => call[0] === 'listStorageServiceOfferingConstraints')[1]).toEqual({ zoneid: 'zone-a', serviceofferingids: a })
  })

  it('keeps arrays empty after an offering/constraints read failure and prevents a plan POST', async () => {
    for (const failed of ['listServiceOfferings', 'listStorageServiceOfferingConstraints']) {
      mock([offering(a)]); const normal = getAPI.getMockImplementation()
      getAPI.mockImplementation((command, parameters) => command === failed ? Promise.reject(new Error('controlled failure')) : normal(command, parameters))
      const vm = context(); await load(vm); await plan(vm)
      expect(vm.cloneOptions.offerings).toEqual([])
      expect(vm.cloneOfferingError).toBe(true)
      expect(vm.mutation).not.toHaveBeenCalled()
    }
  })

  it('discards a prior-zone response after newer discovery completes', async () => {
    mock([offering(b)]); const normal = getAPI.getMockImplementation(); let finish
    getAPI.mockImplementation((command, parameters) => command === 'listServiceOfferings' && parameters.zoneid === 'zone-a'
      ? new Promise(resolve => { finish = resolve }) : normal(command, parameters))
    const vm = context(); const old = load(vm); vm.clone.zoneid = 'zone-b'; await load(vm)
    finish({ listserviceofferingsresponse: { serviceoffering: [offering(a)] } }); await old
    expect(vm.cloneOptions.offerings.map(row => row.value)).toEqual([b])
    expect(vm.clone.serviceofferingid).toBe(b)
  })

  it('discards an old owner result even in the same zone', async () => {
    mock([offering(a)]); const normal = getAPI.getMockImplementation(); let finish
    getAPI.mockImplementation((command, parameters) => command === 'listServiceOfferings'
      ? new Promise(resolve => { finish = resolve }) : normal(command, parameters))
    const vm = context(); const old = load(vm); vm.resource.account = 'other'; finish({ listserviceofferingsresponse: { serviceoffering: [offering(a)] } }); await old
    expect(vm.cloneOptions.offerings).toEqual([])
    expect(vm.cloneOptionScope).toBeNull()
  })

  it('clears selections and invalidates requests when owner context changes', () => {
    const vm = context(); vm.clone.serviceofferingid = a; vm.cloneOptionScope = vm.cloneDiscoveryScope()
    Widget.watch['resource.account'].call(vm)
    expect(vm.cloneOptions.offerings).toEqual([])
    expect(vm.clone.serviceofferingid).toBeUndefined()
    expect(vm.cloneOptionScope).toBeNull()
  })

  it('rechecks a foreign selected ID or changed provisioning before CREATE_NEW plan effects', async () => {
    for (const change of [vm => { vm.clone.serviceofferingid = b }, vm => { vm.cloneOfferingRows[0].provisioningtype = 'thin' }, vm => { vm.resource.account = 'other' }]) {
      mock([offering(a)]); const vm = context(); await load(vm); change(vm); await plan(vm)
      expect(vm.mutation).not.toHaveBeenCalled()
    }
  })

  it('sends the validated selection while preserving planned NEW allocations', async () => {
    mock([offering(a)]); const vm = context(); await load(vm)
    vm.clone.diskofferingid = 'data-sparse'; vm.volumeMapping = { other: 'NEW' }; vm.newVolumeSpecs = { other: { diskofferingid: 'data-sparse', sizeGiB: 40 } }
    await plan(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    const request = JSON.parse(vm.mutation.mock.calls[0][1].mapping)
    expect(request.createNew.serviceofferingid).toBe(a)
    expect(request.volumes).toEqual({ initial: 'NEW', other: 'NEW' })
    expect(request.newVolumes.other.dataPolicy).toBe('PRESERVE')
  })

  it('does not apply new ROOT checks to existing-target restore plans', async () => {
    const vm = context(); vm.targetMode = 'RESTORE_EXISTING'
    await plan(vm)
    expect(vm.mutation).toHaveBeenCalledTimes(1)
    expect(vm.mutation.mock.calls[0][1].targetmode).toBe('RESTORE_EXISTING')
  })

  it('does not reuse a closed or disposed discovery result', async () => {
    mock([offering(a)]); const vm = context(); await load(vm)
    Widget.beforeUnmount.call(vm)
    expect(vm.cloneOptionToken).toBeGreaterThan(0)
    expect(vm.cloneOptionScope).toBeNull()
    expect(() => vm.assertCloneOffering()).toThrow('message.storage.disk.sparse.required')
  })
})
