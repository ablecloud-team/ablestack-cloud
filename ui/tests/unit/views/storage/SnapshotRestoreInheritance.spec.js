// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
import '../../utils/cryptoRandom'
import CreateVolume from '@/views/storage/CreateVolume.vue'
import { snapshotRestoreInheritance, pureSnapshotRestoreParams } from '@/utils/snapshotRestore'

const restore = { available: true, size: 1073741824, diskofferingid: 'captured', diskofferingname: 'fixed 1GiB' }
test('uses captured size and offering rather than current source or first offering', () => {
  const context = { record: { virtualsize: 2147483648 }, source: { sourceavailable: false, restore } }
  expect(snapshotRestoreInheritance(context)).toEqual({ ...restore, sizeGiB: 1 })
})
test.each([undefined, {}, { ...restore, size: 0 }, { ...restore, size: '1073741824' }, { ...restore, diskofferingid: '' }, { ...restore, available: false }])('incomplete restore contract blocks submission: %p', restore => {
  expect(snapshotRestoreInheritance({ source: { restore } })).toBeNull()
})
test.each(['ROOT', 'DATADISK'])('%s restoration never chooses the first offering or requests ignored inputs', async volumetype => {
  const ctx = { createVolumeFromSnapshot: true, offeringRequestId: 0, resource: { volumetype }, offerings: [{ id: 'first', iscustomized: true }], customDiskOffering: true, isCustomizedDiskIOps: true, form: { size: '2', diskofferingid: 'first', miniops: 10, maxiops: 20, kmskeyid: 'key', zoneid: 'zone' } }
  await CreateVolume.methods.fetchDiskOfferings.call(ctx, 'zone')
  expect(ctx.offerings).toEqual([]); expect(ctx.customDiskOffering).toBe(false); expect(ctx.isCustomizedDiskIOps).toBe(false)
  expect(ctx.form).toEqual({ zoneid: 'zone' })
  const values = { name: 'restore', zoneid: 'zone', size: 2, diskofferingid: 'first', miniops: 1, maxiops: 2, kmskeyid: 'key', storageid: 'pool', projectid: 'project' }
  expect(pureSnapshotRestoreParams(values, 'fixed')).toEqual({ name: 'restore', zoneid: 'zone', snapshotid: 'fixed', projectid: 'project' })
  expect(values.size).toBe(2)
})
test('only snapshot restore requires the authoritative inheritance context', () => {
  expect(CreateVolume.computed.snapshotRestoreBlocked.call({ createVolumeFromSnapshot: true, snapshotRestore: null })).toBe(true)
  expect(CreateVolume.computed.snapshotRestoreBlocked.call({ createVolumeFromSnapshot: true, snapshotRestore: restore })).toBe(false)
  expect(CreateVolume.computed.snapshotRestoreBlocked.call({ createVolumeFromSnapshot: false, snapshotRestore: null })).toBe(false)
})

test('attachment selection becomes required when enabled and does not block standalone restoration', () => {
  const ctx = { attachVolume: false, virtualmachines: [], rules: {}, form: { zoneid: 'zone' }, $t: x => x, $getApiParams: jest.fn(), fetchVirtualMachines: jest.fn(), validateDeviceId: jest.fn(), formRef: { value: { clearValidate: jest.fn() } } }
  CreateVolume.methods.onChangeAttachToVM.call(ctx)
  expect(ctx.rules.virtualmachineid[0].required).toBe(true); expect(ctx.rules.deviceid[0].validator).toBe(ctx.validateDeviceId)
  expect(ctx.fetchVirtualMachines).toHaveBeenCalledWith('zone')
  CreateVolume.methods.onChangeAttachToVM.call(ctx)
  expect(ctx.rules).toEqual({}); expect(ctx.formRef.value.clearValidate).toHaveBeenCalledWith(['virtualmachineid', 'deviceid'])
})
