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

import { shallowMount } from '@vue/test-utils'
import Summary from '@/components/view/AllocationSummary.vue'
import Editor from '@/components/offering/AllocationPolicyEditor.vue'
import CreateVolume from '@/views/storage/CreateVolume.vue'
import DiskForm from '@/components/offering/DiskOfferingForm.vue'
import MigrateVolume from '@/views/storage/MigrateVolume.vue'
import CloneCompute from '@/views/offering/CloneComputeOffering.vue'
import ComputeForm from '@/components/offering/ComputeOfferingForm.vue'
import { allocationParams } from '@/utils/volumeAllocation'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: { account: 'admin', roletype: 'Admin' }, project: null } }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
const globals = { mocks: { $t: s => s, $bytesToHumanReadableSize: n => n + 'B', $store: { getters: { apis: {} } } }, renderStubDefaultSlot: true }
const request = { contractversion: '1', requestedpolicy: 'full', requestedformat: 'raw', evidence: 'unverified' }
test('requested Full cannot be shown as actually allocated without evidence', () => {
  const w = shallowMount(Summary, { props: { resource: { name: 'FULL', provisioningtype: 'fat', allocation: { ...request, observedallocatedbytes: '999' } }, volume: true }, global: globals })
  expect(w.text()).toContain('label.allocation.unknown'); expect(w.text()).not.toContain('999B')
  w.unmount()
})
test('verified evidence shows method/time and observed bytes independently of logical size', () => {
  const allocation = { ...request, evidence: 'verified', fixedpolicy: 'full', effectivepolicy: 'full', appliedmethod: 'rbd-thick-create', observedallocatedbytes: '123', observedat: '2026-10-20T00:00:00Z' }
  const w = shallowMount(Summary, { props: { resource: { size: 456, allocation }, volume: true }, global: globals })
  expect(w.text()).toContain('123B'); expect(w.text()).toContain('456B'); expect(w.text()).toContain('rbd-thick-create'); expect(w.text()).toContain(allocation.observedat)
  w.unmount()
})
test('legacy clone validation remains unchanged with no capability permission', async () => {
  const w = shallowMount(Editor, { props: { form: { provisioningtype: 'fat' } }, global: globals })
  await expect(w.vm.validate()).resolves.toBeUndefined(); expect(getAPI).not.toHaveBeenCalled(); w.unmount()
})
test('unchanged explicit offering definition can be cloned without a pool binding', async () => {
  const form = { allocationmode: 'explicit', allocationpolicy: 'full', allocationformat: 'raw', allocationoriginal: JSON.stringify({ policy: 'full', format: 'raw' }) }
  const w = shallowMount(Editor, { props: { form }, global: globals })
  await expect(w.vm.validate()).resolves.toBeUndefined()
  await w.setProps({ form: { ...form, allocationpolicy: 'falloc' } })
  await expect(w.vm.validate()).rejects.toThrow('failed'); w.unmount()
})
test('a failed capability refresh preserves fields and blocks submission', async () => {
  const form = { allocationmode: 'explicit', allocationpolicy: 'full', allocationformat: 'raw' }
  const w = shallowMount(Editor, { props: { form }, global: globals }); await w.setData({ pools: [{ id: 'p', type: 'RBD' }], poolId: 'p' })
  getAPI.mockRejectedValue(new Error('offline'))
  await w.vm.query(); expect(form.allocationpolicy).toBe('full'); expect(w.vm.error).toBe(true)
  await expect(w.vm.validate()).rejects.toThrow('failed'); w.unmount()
})
test('old CreateVolume capability cannot clear a later valid result', async () => {
  let release
  const method = CreateVolume.methods.queryAllocation
  const context = { allocationRequestId: 0, createOnStorage: true, explicitAllocation: { format: 'raw', policy: 'full' }, selectedStoragePool: { id: 'p', zoneid: 'z', type: 'RBD' } }
  getAPI.mockImplementation((cmd, args) => cmd === 'listHosts' ? Promise.resolve({ listhostsresponse: { host: [{ id: 'h', state: 'Up', hypervisor: 'KVM' }] } }) : new Promise(resolve => { release = resolve }))
  const old = method.call(context)
  for (let i = 0; i < 20; i++) await Promise.resolve()
  context.allocationRequestId++
  const current = { policies: { full: 'rbd-thick-create' } }
  context.allocationCap = current
  release({ getvolumeallocationcapabilitiesresponse: { volumeallocationcapability: { contractversion: 1, storageid: 'p', hostid: 'h', allocationformat: 'raw', operation: 'create', backend: 'RBD', supported: true, agentpolicies: { full: 'rbd-thick-create' } } } })
  await old
  expect(context.allocationCap).toBe(current)
})

test.each([DiskForm, ComputeForm])('child policy controls survive parent ref filtering for $name', async component => {
  for (const provisioningtype of ['thin', 'sparse', 'fat']) {
    const context = {
      form: { name: 'clone', provisioningtype, allocationmode: 'legacy' },
      $refs: { internalFormRef: { validate: () => Promise.resolve() }, allocationEditor: { validate: () => Promise.resolve() } },
      handleRemoveFields: () => ({ name: 'clone' })
    }
    expect(allocationParams(await component.methods.validate.call(context))).toEqual({ provisioningtype })
    context.form = { name: 'new', allocationmode: 'explicit', allocationpolicy: 'full', allocationformat: 'raw' }
    expect(allocationParams(await component.methods.validate.call(context))).toEqual({ allocationpolicy: 'full', allocationformat: 'raw' })
  }
})

test('explicit allocation migration shows the backend limit and cannot query or submit a copy', () => {
  getAPI.mockClear()
  const w = shallowMount(MigrateVolume, { props: { resource: { id: 'v', allocation: request } }, global: { ...globals, provide: { parentFetchData: () => {} } } })
  expect(w.find('a-alert-stub').attributes('message')).toBe('message.allocation.lifecycle.unsupported')
  expect(getAPI).not.toHaveBeenCalled()
  w.vm.submitForm()
  expect(w.vm.isSubmitted).toBe(false)
  w.unmount()
})

test('cloned compute allocation metadata stays out of editable hypervisor details', () => {
  const context = { form: {} }
  const source = { name: 'Full', allocation: request, serviceofferingdetails: { 'allocation.contract.version': '1', 'allocation.requested.policy': 'full', 'allocation.applied.method': 'rbd-thick-create', custom: 'keep' } }
  CloneCompute.methods.populateFormFromResource.call(context, source)
  expect(context.form.externaldetails).toEqual({ custom: 'keep' })
  expect(context.form.allocationpolicy).toBe('full')
  expect(context.externalDetailsEnabled).toBe(true)
  delete source.serviceofferingdetails.custom
  CloneCompute.methods.populateFormFromResource.call(context, source)
  expect(context.externalDetailsEnabled).toBe(false)
  expect(context.form.externaldetails).toBeUndefined()
})
