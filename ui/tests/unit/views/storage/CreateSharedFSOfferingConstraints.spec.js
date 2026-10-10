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

import CreateSharedFS from '@/views/storage/CreateSharedFS'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const a = '11111111-1111-4111-8111-111111111111'
const b = '22222222-2222-4222-8222-222222222222'
const c = '33333333-3333-4333-8333-333333333333'
const rootId = '44444444-4444-4444-8444-444444444444'
const rootOffering = (id, extra = {}) => ({ id, provisioningtype: 'sparse', diskofferingid: rootId, ...extra })
const context = () => ({
  selectedZone: { id: 'zone-a' },
  owner: { domainid: 'domain', account: 'admin' },
  form: { serviceofferingid: '' },
  serviceOfferingRequestToken: 0,
  serviceOfferingReadError: false,
  serviceofferingLoading: false,
  serviceofferings: [],
  offeringRequirements: null,
  serviceOfferingScope: null,
  $t: key => key,
  templateOwnerScope: CreateSharedFS.methods.templateOwnerScope,
  hasSparseNewRootOffering: CreateSharedFS.methods.hasSparseNewRootOffering,
  isSelectableNewRootOffering: CreateSharedFS.methods.isSelectableNewRootOffering,
  assertNewRootOffering: CreateSharedFS.methods.assertNewRootOffering
})
const offers = items => ({ listserviceofferingsresponse: { serviceoffering: items } })
const constraints = items => ({ liststorageserviceofferingconstraintsresponse: { storageserviceofferingconstraint: items } })

describe('SharedFS authoritative offering constraints', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
  it('keeps incompatible options visible and selects only a server-approved offering', async () => {
    getAPI.mockImplementation(command => Promise.resolve(command === 'listServiceOfferings'
      ? offers([rootOffering(a), rootOffering(b)])
      : constraints([
        { id: a, compatible: false, reasons: ['ZONE_DYNAMIC_SCALE_DISABLED'] },
        { id: b, compatible: true, reasons: [], minimumcpu: 2, minimummemory: 1024 }
      ])))
    const vm = context()
    await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    expect(vm.serviceofferings).toHaveLength(2)
    expect(vm.serviceofferings[0].compatibility.compatible).toBe(false)
    expect(vm.form.serviceofferingid).toBe(b)
    expect(getAPI.mock.calls[1][1]).toEqual({ zoneid: 'zone-a', serviceofferingids: a + ',' + b })
  })
  it('does not allow an unchecked offering when constraint discovery fails', async () => {
    getAPI.mockImplementation(command => command === 'listServiceOfferings'
      ? Promise.resolve(offers([rootOffering(a)]))
      : Promise.reject(new Error('constraints unavailable')))
    const vm = context()
    await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    expect(vm.serviceofferings[0].id).toBe(a)
    expect(vm.serviceofferings[0].compatibility).toBeNull()
    expect(vm.form.serviceofferingid).toBe('')
    expect(vm.serviceOfferingReadError).toBe(true)
    expect(vm.serviceofferingLoading).toBe(false)
  })
  it('ignores a late offering response for a previously selected zone', async () => {
    let completeOldZone
    getAPI.mockImplementation((command, params) => {
      if (command === 'listServiceOfferings' && params.zoneid === 'zone-a') {
        return new Promise(resolve => { completeOldZone = resolve })
      }
      return Promise.resolve(command === 'listServiceOfferings'
        ? offers([rootOffering(b)])
        : constraints([{ id: b, compatible: true, reasons: [] }]))
    })
    const vm = context()
    const previous = CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    vm.selectedZone = { id: 'zone-b' }
    await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    completeOldZone(offers([rootOffering(a)]))
    await previous
    expect(vm.form.serviceofferingid).toBe(b)
    expect(vm.serviceofferings.map(item => item.id)).toEqual([b])
    expect(vm.serviceofferingLoading).toBe(false)
  })
  it('discards outstanding work when the creation dialog is disposed', async () => {
    let complete
    getAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
    const vm = context()
    const pending = CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    CreateSharedFS.beforeUnmount.call(vm)
    complete(offers([{ id: 'late' }]))
    await pending
    expect(vm.serviceofferings).toEqual([])
    expect(getAPI).toHaveBeenCalledTimes(1)
  })
})

describe('SharedFS creation ROOT provisioning constraints', () => {
  beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

  it('skips a CPU-compatible THIN root and defaults to an authoritative SPARSE or FAT root', async () => {
    for (const provisioningtype of ['sparse', 'fat']) {
      getAPI.mockImplementation(command => Promise.resolve(command === 'listServiceOfferings'
        ? offers([rootOffering(a, { provisioningtype: 'thin' }), rootOffering(b, { provisioningtype })])
        : constraints([{ id: a, compatible: true }, { id: b, compatible: true }])))
      const vm = context(); await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
      expect(vm.form.serviceofferingid).toBe(b)
      expect(vm.isSelectableNewRootOffering(vm.serviceofferings[0])).toBe(false)
      expect(vm.isSelectableNewRootOffering(vm.serviceofferings[1])).toBe(true)
    }
  })

  it('does not default when ROOT provisioning is thin or unknown even if compute constraints pass', async () => {
    getAPI.mockImplementation(command => Promise.resolve(command === 'listServiceOfferings'
      ? offers([rootOffering(a, { provisioningtype: 'thin' }), rootOffering(b, { provisioningtype: undefined })])
      : constraints([{ id: a, compatible: true }, { id: b, compatible: true }])))
    const vm = context(); await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    expect(vm.form.serviceofferingid).toBe('')
    expect(vm.serviceofferings.every(item => !vm.isSelectableNewRootOffering(item))).toBe(true)
  })

  it('requires literal provisioning and canonical SO/linked ROOT DO UUIDs', () => {
    const vm = context()
    for (const extra of [{ provisioningtype: ['sparse'] }, { provisioningtype: true }, { provisioningtype: 'sparse ' }, { diskofferingid: undefined }, { diskofferingid: 1 }, { diskofferingid: 'foreign' }, { id: 1 }]) expect(vm.hasSparseNewRootOffering(rootOffering(a, extra))).toBe(false)
    expect(vm.hasSparseNewRootOffering(rootOffering(a, { provisioningtype: 'SPARSE' }))).toBe(true)
    expect(vm.hasSparseNewRootOffering(rootOffering(a, { provisioningtype: 'FAT' }))).toBe(true)
  })

  it('rejects string compatibility instead of treating it as approved', async () => {
    getAPI.mockImplementation(command => Promise.resolve(command === 'listServiceOfferings' ? offers([rootOffering(a)]) : constraints([{ id: a, compatible: 'true' }])))
    const vm = context(); await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    expect(vm.form.serviceofferingid).toBe('')
    expect(vm.isSelectableNewRootOffering(vm.serviceofferings[0])).toBe(false)
  })

  it('revalidates the selected ROOT provisioning immediately before POST', () => {
    const vm = context(); vm.serviceOfferingScope = vm.templateOwnerScope(); vm.serviceofferings = [rootOffering(a, { compatibility: { compatible: true } })]
    expect(() => vm.assertNewRootOffering(a)).not.toThrow()
    vm.serviceofferings[0].provisioningtype = 'thin'
    expect(() => vm.assertNewRootOffering(a)).toThrow('message.storage.disk.sparse.required')
  })

  it('rejects unknown/foreign IDs and incomplete or failed offering observations before POST', () => {
    for (const extra of [{}, { serviceofferingLoading: true }, { serviceOfferingReadError: true }, { serviceOfferingScope: null }]) {
      const vm = context(); vm.serviceOfferingScope = vm.templateOwnerScope(); vm.serviceofferings = [rootOffering(a, { compatibility: { compatible: true } })]; Object.assign(vm, extra)
      if (Object.keys(extra).length) expect(() => vm.assertNewRootOffering(a)).toThrow('message.storage.disk.sparse.required')
      expect(() => vm.assertNewRootOffering(c)).toThrow('message.storage.disk.sparse.required')
      expect(() => vm.assertNewRootOffering(1)).toThrow('message.storage.disk.sparse.required')
    }
  })

  it('discards an old owner response in the same zone', async () => {
    let finish; getAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const vm = context(); const pending = CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    vm.owner = { domainid: 'other-domain', account: 'other' }; finish(offers([rootOffering(a)])); await pending
    expect(vm.form.serviceofferingid).toBe('')
    expect(vm.serviceofferings).toEqual([])
    expect(vm.serviceOfferingScope).toBeNull()
  })

  it('does not reuse a previous owner selection when scope changes after discovery', () => {
    const vm = context(); vm.serviceOfferingScope = vm.templateOwnerScope(); vm.serviceofferings = [rootOffering(a, { compatibility: { compatible: true } })]
    vm.owner.account = 'other'
    expect(() => vm.assertNewRootOffering(a)).toThrow('message.storage.disk.sparse.required')
  })

  it('enforces the new ROOT guard through the real submit method before any create API call', async () => {
    const vm = context(); vm.serviceOfferingScope = vm.templateOwnerScope(); vm.serviceofferings = [rootOffering(a, { provisioningtype: 'thin', compatibility: { compatible: true } })]
    Object.assign(vm, { formRef: { value: { validate: jest.fn().mockResolvedValue(), scrollToField: jest.fn() } }, syncInitialNfsPath: jest.fn(), handleRemoveFields: () => ({}), buildCreateSharedFsRequest: () => ({ serviceofferingid: a }), clearInitialAdCredentials: jest.fn(), $notifyError: jest.fn() })
    CreateSharedFS.methods.handleSubmit.call(vm)
    for (let step = 0; step < 20; step++) { await Promise.resolve(); if (vm.$notifyError.mock.calls.length) break }
    expect(postAPI).not.toHaveBeenCalled()
    expect(vm.$notifyError).toHaveBeenCalledTimes(1)
    expect(vm.$notifyError.mock.calls[0][0].message).toBe('message.storage.disk.sparse.required')
  })
})
