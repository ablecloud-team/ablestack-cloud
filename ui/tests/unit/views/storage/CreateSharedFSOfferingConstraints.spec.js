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
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const context = () => ({
  selectedZone: { id: 'zone-a' },
  owner: { domainid: 'domain', account: 'admin' },
  form: { serviceofferingid: '' },
  serviceOfferingRequestToken: 0,
  serviceOfferingReadError: false,
  serviceofferingLoading: false,
  serviceofferings: [],
  offeringRequirements: null
})
const offers = items => ({ listserviceofferingsresponse: { serviceoffering: items } })
const constraints = items => ({ liststorageserviceofferingconstraintsresponse: { storageserviceofferingconstraint: items } })

describe('SharedFS authoritative offering constraints', () => {
  beforeEach(() => getAPI.mockReset())
  it('keeps incompatible options visible and selects only a server-approved offering', async () => {
    getAPI.mockImplementation(command => Promise.resolve(command === 'listServiceOfferings'
      ? offers([{ id: 'incompatible' }, { id: 'compatible' }])
      : constraints([
        { id: 'incompatible', compatible: false, reasons: ['ZONE_DYNAMIC_SCALE_DISABLED'] },
        { id: 'compatible', compatible: true, reasons: [], minimumcpu: 2, minimummemory: 1024 }
      ])))
    const vm = context()
    await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    expect(vm.serviceofferings).toHaveLength(2)
    expect(vm.serviceofferings[0].compatibility.compatible).toBe(false)
    expect(vm.form.serviceofferingid).toBe('compatible')
    expect(getAPI.mock.calls[1][1]).toEqual({ zoneid: 'zone-a', serviceofferingids: 'incompatible,compatible' })
  })
  it('does not allow an unchecked offering when constraint discovery fails', async () => {
    getAPI.mockImplementation(command => command === 'listServiceOfferings'
      ? Promise.resolve(offers([{ id: 'visible' }]))
      : Promise.reject(new Error('constraints unavailable')))
    const vm = context()
    await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    expect(vm.serviceofferings[0].id).toBe('visible')
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
        ? offers([{ id: 'zone-b-offer' }])
        : constraints([{ id: 'zone-b-offer', compatible: true, reasons: [] }]))
    })
    const vm = context()
    const previous = CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    vm.selectedZone = { id: 'zone-b' }
    await CreateSharedFS.methods.fetchServiceOfferings.call(vm)
    completeOldZone(offers([{ id: 'stale-offer' }]))
    await previous
    expect(vm.form.serviceofferingid).toBe('zone-b-offer')
    expect(vm.serviceofferings.map(item => item.id)).toEqual(['zone-b-offer'])
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
