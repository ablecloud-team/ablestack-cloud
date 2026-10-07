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

import Change from '@/views/storage/ChangeSharedFSServiceOffering'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const context = state => {
  const vm = { resource: { id: 'fs', state, zoneid: 'z', serviceofferingid: 'old' }, owner: { account: 'admin', domainid: 'd' }, form: {}, serviceOfferingRequestToken: 0, serviceofferings: [], scalingReadiness: null }
  vm.offeringSelectable = item => Change.methods.offeringSelectable.call(vm, item)
  Object.defineProperty(vm, 'currentOffering', { get: () => Change.computed.currentOffering.call(vm) })
  return vm
}
const old = { id: 'old', cpu: 2, memory: 4096, cpuspeed: 2000 }
const larger = { id: 'larger', cpu: 4, memory: 8192, cpuspeed: 2000 }
const lower = { id: 'lower', cpu: 2, memory: 2048, cpuspeed: 2000 }
function mockApi (ready = true) {
  getAPI.mockImplementation(cmd => Promise.resolve(cmd === 'listServiceOfferings'
    ? { listserviceofferingsresponse: { serviceoffering: [old, lower, larger] } }
    : cmd === 'listStorageServiceOfferingConstraints'
      ? { liststorageserviceofferingconstraintsresponse: { storageserviceofferingconstraint: [old, lower, larger].map(i => ({ id: i.id, compatible: true })) } }
      : { getsharedfilesystemscalingreadinessresponse: { resultjson: JSON.stringify({ ready, currentOffering: old, reasons: [] }) } }))
}
describe('SharedFS effective online scaling', () => {
  beforeEach(() => getAPI.mockReset())
  it('selects only increased resources after authoritative readiness is available', async () => {
    mockApi(); const vm = context('Ready')
    await Change.methods.fetchServiceOfferings.call(vm)
    expect(vm.form.serviceofferingid).toBe('larger')
    expect(vm.offeringSelectable(vm.serviceofferings[1])).toBe(false)
  })
  it('blocks legacy running instances even when the offerings are compatible', async () => {
    mockApi(false); const vm = context('Ready')
    await Change.methods.fetchServiceOfferings.call(vm)
    expect(vm.form.serviceofferingid).toBe('')
    expect(vm.offeringSelectable(vm.serviceofferings[2])).toBe(false)
  })
  it('blocks running changes if readiness discovery fails', async () => {
    mockApi(); const original = getAPI.getMockImplementation()
    getAPI.mockImplementation(cmd => cmd === 'getSharedFileSystemScalingReadiness' ? Promise.reject(new Error('timeout')) : original(cmd))
    const vm = context('Ready'); await Change.methods.fetchServiceOfferings.call(vm)
    expect(vm.scalingReadinessError).toBe(true); expect(vm.form.serviceofferingid).toBe('')
  })
  it('allows a stopped legacy service to move to a compatible larger offering without an online probe', async () => {
    mockApi(); const vm = context('Stopped'); await Change.methods.fetchServiceOfferings.call(vm)
    expect(vm.form.serviceofferingid).toBe('larger'); expect(getAPI).toHaveBeenCalledTimes(2)
  })
  it('ignores a late readiness response after the dialog is disposed', async () => {
    mockApi(); const original = getAPI.getMockImplementation(); let complete
    getAPI.mockImplementation(cmd => cmd === 'getSharedFileSystemScalingReadiness' ? new Promise(resolve => { complete = resolve }) : original(cmd))
    const vm = context('Ready'); const pending = Change.methods.fetchServiceOfferings.call(vm)
    for (let i = 0; i < 5; i++) await Promise.resolve()
    Change.beforeUnmount.call(vm); complete({ getsharedfilesystemscalingreadinessresponse: { resultjson: JSON.stringify({ ready: true }) } })
    await pending; expect(vm.form.serviceofferingid).toBe(''); expect(vm.scalingReadiness).toBeNull()
  })
})
