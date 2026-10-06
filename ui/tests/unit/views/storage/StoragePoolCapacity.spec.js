// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

/** @jest-environment jsdom */
import { shallowMount } from '@vue/test-utils'
import { storagePoolCapacities, formatStorageCapacity } from '@/utils/storagePoolCapacity'
import StoragePoolCapacity from '@/views/storage/StoragePoolCapacity.vue'
import CreateVolume from '@/views/storage/CreateVolume.vue'
import { getAPI } from '@/api'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: {}, project: null } }))

const GiB = 1024 ** 3
const pool = { id: 'pool', name: 'Primary', state: 'Up', type: 'SharedMountPoint', managed: false, capacitybytes: 1000 * GiB, disksizeallocated: 1200 * GiB, disksizeused: 200 * GiB }

test('physical free space uses actual usage and preserves overprovisioned allocation', () => {
  expect(storagePoolCapacities(pool)).toEqual({ total: 1000 * GiB, allocated: 1200 * GiB, available: 800 * GiB })
})
test.each([null, undefined, '', 'bad', -1, Infinity, NaN, false])('unknown or invalid values are not displayed as zero (%s)', value => {
  expect(formatStorageCapacity(value)).toBe('—')
})
test('zero, large values, compatibility totals and small positive values remain distinct', () => {
  expect(formatStorageCapacity(0)).toBe('0 GiB')
  expect(formatStorageCapacity(1024 ** 4)).toBe('1 TiB')
  expect(formatStorageCapacity(2048.5 * 1024 ** 4)).toBe('2,048.5 TiB')
  expect(formatStorageCapacity(1024)).toBe('< 0.01 GiB')
  expect(storagePoolCapacities({ ...pool, capacitybytes: null, disksizetotal: 1000 * GiB }).total).toBe(1000 * GiB)
})
test.each([{ managed: true }, { managed: undefined }, { type: 'UnknownProvider' }, { disksizeused: null }])('unconfirmed physical capacity stays unknown (%j)', change => {
  expect(storagePoolCapacities({ ...pool, ...change }).available).toBeNull()
})
test('summary renders independent values and explains reservation and physical meaning', () => {
  const wrapper = shallowMount(StoragePoolCapacity, {
    props: { pool, summary: true },
    global: { mocks: { $t: key => key } }
  })
  expect(wrapper.findAll('.capacity-value').map(el => el.text())).toEqual(['1,000 GiB', '1.17 TiB', '800 GiB'])
  expect(wrapper.find('.capacity-caption').attributes('title')).toBe('message.volume.storage.capacity.tooltip')
  wrapper.unmount()
})

function context () {
  return {
    storageRequestId: 0,
    storageLoading: false,
    storageFetchError: false,
    storagePools: [pool],
    form: { storageid: 'pool', name: 'Keep typed name', size: '17' },
    $notifyError: jest.fn()
  }
}
beforeEach(() => getAPI.mockReset())
test('storage refresh preserves inputs, selected UUID and old capacity until the response arrives', async () => {
  const vm = context()
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = CreateVolume.methods.fetchStoragePools.call(vm, 'zone')
  expect(vm.storageLoading).toBe(true)
  expect(vm.storagePools).toEqual([pool])
  expect(vm.form).toMatchObject({ storageid: 'pool', name: 'Keep typed name', size: '17' })
  finish({ liststoragepoolsresponse: { storagepool: [{ ...pool, disksizeused: 250 * GiB }, { ...pool, id: 'down', state: 'Maintenance' }] } })
  await pending
  expect(vm.storagePools).toHaveLength(1)
  expect(vm.form.storageid).toBe('pool')
  expect(vm.storageLoading).toBe(false)
})
test('a late old-zone result cannot replace the current storage selection or capacity', async () => {
  const vm = context()
  let finishOld
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
  const old = CreateVolume.methods.fetchStoragePools.call(vm, 'old-zone')
  getAPI.mockResolvedValueOnce({ liststoragepoolsresponse: { storagepool: [{ ...pool, id: 'current' }] } })
  const current = CreateVolume.methods.fetchStoragePools.call(vm, 'current-zone')
  await current
  vm.form.storageid = 'current'
  finishOld({ liststoragepoolsresponse: { storagepool: [pool] } })
  await old
  expect(vm.storagePools[0].id).toBe('current')
  expect(vm.form.storageid).toBe('current')
})
test('fetch failure remains distinct from no candidates and does not erase typed data', async () => {
  const vm = context()
  getAPI.mockRejectedValueOnce(new Error('unavailable'))
  await CreateVolume.methods.fetchStoragePools.call(vm, 'zone')
  expect(vm.storageFetchError).toBe(true)
  expect(vm.storagePools).toEqual([pool])
  expect(vm.form.storageid).toBe('pool')
  expect(vm.storageLoading).toBe(false)
})

test('an empty current response clears an invalid pool UUID without losing entered volume fields', async () => {
  const vm = context()
  getAPI.mockResolvedValueOnce({ liststoragepoolsresponse: {} })
  await CreateVolume.methods.fetchStoragePools.call(vm, 'zone')
  expect(vm.storagePools).toEqual([])
  expect(vm.form.storageid).toBeUndefined()
  expect(vm.form.name).toBe('Keep typed name')
  expect(vm.form.size).toBe('17')
  expect(vm.storageFetchError).toBe(false)
  expect(vm.storageLoading).toBe(false)
})
test('clearing the zone invalidates an outstanding response and keeps the pool list empty', async () => {
  const vm = context()
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = CreateVolume.methods.fetchStoragePools.call(vm, 'zone')
  await CreateVolume.methods.fetchStoragePools.call(vm, null)
  finish({ liststoragepoolsresponse: { storagepool: [pool] } })
  await pending
  expect(vm.storagePools).toEqual([])
  expect(vm.form.storageid).toBeUndefined()
  expect(vm.storageLoading).toBe(false)
})
