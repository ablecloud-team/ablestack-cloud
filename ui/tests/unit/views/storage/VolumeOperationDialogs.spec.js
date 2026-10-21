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

import { shallowMount, flushPromises } from '@vue/test-utils'
import Migration from '@/views/storage/MigrateVolume.vue'
import Offering from '@/views/storage/ChangeOfferingForVolume.vue'
import { volumeOfferingChangeParams as params } from '@/utils/volumeOfferingChange'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const GiB = 1073741824
const volume = { id: 'v', name: 'target', type: 'DATADISK', state: 'Ready', size: 4 * GiB, miniops: 100, maxiops: 200, diskofferingid: 'old', diskofferingname: 'Current', provisioningtype: 'thin', storageid: 'source', storage: 'Primary-A', format: 'RAW', path: 'image', account: 'owner', domainid: 'd' }
const custom = { id: 'custom', name: 'Custom', iscustomized: true, iscustomizediops: true, provisioningtype: 'thin' }
const fixed = { id: 'fixed', name: 'Fixed', disksize: 4, miniops: 300, maxiops: 600, provisioningtype: 'thin' }
const values = () => ({ size: '4', miniops: '100', maxiops: '200', automigrate: true, shrinkok: false })
const fields = { diskofferingid: {}, size: {}, miniops: {}, maxiops: {}, automigrate: {}, shrinkok: {} }
const pool = { id: 'p', name: 'Primary-B', state: 'Up', suitableformigration: true, disksizetotal: 100, disksizeused: 0 }
let store, route, poll
function mount (component, resource = volume) {
  store = { getters: { userInfo: { id: 'admin', roletype: 'Admin' }, project: null } }; route = { path: '/volume/' + resource.id }
  return shallowMount(component, { props: { resource: { ...resource } }, global: { directives: { 'ctrl-enter': {} }, mocks: { $t: s => s, $bytesToHumanReadableSize: n => n + 'B', $getApiParams: () => fields, $store: store, $route: route, $pollJob: (...args) => poll(...args) }, stubs: { 'a-form': { template: '<form><slot/></form>' }, 'a-form-item': { template: '<div><slot name="label"/><slot/></div>' }, 'a-button': { template: '<button><slot/></button>' } } } })
}
beforeEach(() => {
  getAPI.mockReset(); postAPI.mockReset(); poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
  getAPI.mockImplementation(async cmd => cmd === 'findStoragePoolsForMigration' ? { findstoragepoolsformigrationresponse: { storagepool: [pool], count: 1 } } : cmd === 'listVolumes' ? { listvolumesresponse: { volume: [{ ...volume }] } } : { listdiskofferingsresponse: { diskoffering: [custom, fixed], count: 2 } })
  postAPI.mockResolvedValue({ changeofferingforvolumeresponse: { jobid: 'j' }, migratevolumeresponse: { jobid: 'j' } })
})
test('offering custom values and checked default are sent, including explicit zero', () => {
  expect(params(volume, custom, { ...values(), miniops: '0' }, fields)).toEqual({ id: 'v', diskofferingid: 'custom', size: 4, miniops: 0, maxiops: 200, automigrate: true })
  expect(params(volume, custom, { ...values(), automigrate: false }, fields).automigrate).toBe(false)
})
test('fixed offering never receives stale custom size or IOPS fields', () => {
  expect(params(volume, fixed, { ...values(), size: '9', miniops: '999', maxiops: '9999', shrinkok: true }, fields)).toEqual({ id: 'v', diskofferingid: 'fixed', automigrate: true })
})
test('blank custom values retain current values; missing API fields are not sent', () => {
  expect(params(volume, custom, { ...values(), miniops: '', maxiops: '', size: '' }, fields)).toMatchObject({ size: 4, miniops: 100, maxiops: 200 })
  expect(params(volume, custom, values(), { size: {} })).toEqual({ id: 'v', diskofferingid: 'custom', size: 4 })
  expect(params({ ...volume, kvdoenable: true }, custom, values(), fields).automigrate).toBeUndefined()
})
test.each(['-1', '1.5', '1e3', 'Infinity', '9007199254740992'])('invalid IOPS %s cannot be submitted', value => {
  expect(() => params(volume, custom, { ...values(), miniops: value }, fields)).toThrow('integer')
})
test('pair ordering, size range and policy conversion are rejected', () => {
  expect(() => params(volume, custom, { ...values(), miniops: '201' }, fields)).toThrow('iops')
  expect(() => params(volume, custom, { ...values(), size: '9007199254740991' }, fields)).toThrow('integer')
  expect(() => params(volume, { ...custom, provisioningtype: 'fat' }, values(), fields)).toThrow('conversion')
})
test('shrink requires acknowledgement and supported format for fixed and custom sizes', () => {
  expect(() => params(volume, custom, { ...values(), size: '3' }, fields)).toThrow('shrink.confirm')
  expect(params(volume, custom, { ...values(), size: '3', shrinkok: true }, fields)).toMatchObject({ size: 3, shrinkok: true })
  expect(() => params({ ...volume, format: 'QCOW2' }, { ...fixed, disksize: 3 }, { ...values(), shrinkok: true }, fields)).toThrow('resize.shrink')
})
test('same explicit policy permits unchanged size but blocks size changes', () => {
  const allocation = { contractversion: '1', requestedformat: 'raw', requestedpolicy: 'off' }
  expect(params({ ...volume, allocation }, { ...custom, allocation }, values(), fields)).toMatchObject({ diskofferingid: 'custom', size: 4 })
  expect(() => params({ ...volume, allocation }, { ...fixed, allocation, disksize: 5 }, values(), fields)).toThrow('lifecycle')
})
test('migration collects pages, excludes current pool and avoids Down as default', async () => {
  getAPI.mockImplementation(async (cmd, args) => ({ findstoragepoolsformigrationresponse: { storagepool: args.page === 1 ? [{ ...pool, id: 'source' }, { ...pool, id: 'down', state: 'Down' }] : [pool], count: 3 } }))
  const w = mount(Migration); await flushPromises()
  expect(getAPI.mock.calls.map(c => c[1].page)).toEqual([1, 2]); expect(w.vm.selectedStoragePool.id).toBe('p')
  expect(w.vm.storagePools.map(p => p.id)).toEqual(['down', 'p']); expect(w.vm.freeSize).toBe('100B')
  expect(w.findComponent({ name: 'AllocationSummary' }).exists()).toBe(false); w.unmount()
})
test('replacement offering lookup uses exact selected pool UUID and ignores stale responses', async () => {
  const w = mount(Migration, { ...volume, virtualmachineid: 'vm', vmstate: 'Running' }); await flushPromises()
  let release
  getAPI.mockImplementation((cmd, args) => args.storageid === 'p' ? new Promise(resolve => { release = resolve }) : Promise.resolve({ listdiskofferingsresponse: { diskoffering: [fixed], count: 1 } }))
  w.vm.replaceDiskOffering = true; await flushPromises()
  expect(getAPI.mock.calls[getAPI.mock.calls.length - 1][1].storageid).toBe('p')
  w.vm.storagePools.push({ ...pool, id: 'new' }); w.vm.selectStoragePool('new'); await flushPromises()
  release({ listdiskofferingsresponse: { diskoffering: [custom], count: 1 } }); await flushPromises()
  expect(w.vm.selectedDiskOffering).toBe('fixed'); expect(w.vm.diskOfferingLoading).toBe(false)
  await w.vm.submitForm(); expect(postAPI.mock.calls[0][1]).toEqual({ livemigrate: true, storageid: 'new', volumeid: 'v', newdiskofferingid: 'fixed' }); w.unmount()
})
test('empty or failed pool refresh blocks mutation and retry preserves selection', async () => {
  const w = mount(Migration); await flushPromises(); getAPI.mockRejectedValueOnce(new Error('offline'))
  await w.vm.fetchStoragePools(); expect(w.vm.selectedStoragePool.id).toBe('p'); expect(w.vm.poolError).toBe('offline'); await w.vm.submitForm(); expect(postAPI).not.toHaveBeenCalled()
  await w.vm.fetchStoragePools(); expect(w.vm.canSubmit).toBe(true)
  getAPI.mockResolvedValueOnce({ findstoragepoolsformigrationresponse: { count: 0 } }); await w.vm.fetchStoragePools(); expect(w.vm.canSubmit).toBe(false); w.unmount()
})
test('detached migration omits replacement and explicit allocation never queries or posts', async () => {
  const w = mount(Migration); await flushPromises(); expect(getAPI).toHaveBeenCalledTimes(1); await w.vm.submitForm()
  expect(postAPI.mock.calls[0][1]).toEqual({ livemigrate: false, storageid: 'p', volumeid: 'v' }); w.unmount()
  getAPI.mockClear(); postAPI.mockClear()
  const explicit = mount(Migration, { ...volume, allocation: { contractversion: '1', requestedformat: 'raw', requestedpolicy: 'full' } }); await flushPromises(); await explicit.vm.submitForm(); expect(getAPI).not.toHaveBeenCalled(); expect(postAPI).not.toHaveBeenCalled(); explicit.unmount()
})
test('offering defaults use selected compatible row rather than the incompatible first row', async () => {
  getAPI.mockResolvedValueOnce({ listdiskofferingsresponse: { diskoffering: [{ ...fixed, provisioningtype: 'fat' }, custom], count: 2 } })
  const w = mount(Offering); await flushPromises(); expect(w.vm.selectedOffering).toEqual(custom); expect(w.vm.form).toMatchObject({ ...values(), diskofferingid: 'custom' })
  expect(w.findAll('.volume-operation-target')).toHaveLength(1); expect(w.findComponent({ name: 'AllocationSummary' }).exists()).toBe(false); w.unmount()
})
test('offering read failure retains all fields and retries in the same dialog', async () => {
  getAPI.mockRejectedValueOnce(new Error('offline')); const w = mount(Offering); await flushPromises(); expect(w.vm.offeringError).toBe('offline')
  w.vm.form.miniops = '150'; await w.vm.fetchDiskOfferings(); expect(w.vm.form.miniops).toBe('150'); expect(w.vm.canSubmit).toBe(true); w.unmount()
})
test('fixed offering with no IOPS limits clears existing limits and must show a change', async () => {
  const w = mount(Offering); await flushPromises()
  w.vm.diskOfferings.push({ ...fixed, id: 'unlimited', miniops: null, maxiops: null })
  w.vm.form.diskofferingid = 'unlimited'; await w.vm.$nextTick()
  expect(w.vm.changeSummary).toContain('label.volume.resize.iops.changed')
  const inputs = w.findAllComponents({ name: 'AInput' }).map(input => input.props('value'))
  expect(inputs).toContain('label.volume.operation.iops.unlimited')
  w.unmount()
})
test('offering duplicate submit is blocked until the job finishes, with correct boolean default', async () => {
  let complete; poll.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const w = mount(Offering); await flushPromises(); const pending = w.vm.submitChangeOfferingForVolume(); w.vm.submitChangeOfferingForVolume(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI.mock.calls[0][1]).toMatchObject({ id: 'v', diskofferingid: 'custom', automigrate: true })
  expect(w.emitted('close-action')).toBeUndefined(); complete({ jobstatus: 1 }); await pending; expect(w.emitted('refresh-data')).toHaveLength(1); w.unmount()
})
test('offering stale volume or project context cannot mutate', async () => {
  const w = mount(Offering); await flushPromises(); getAPI.mockResolvedValueOnce({ listvolumesresponse: { volume: [{ ...volume, diskofferingid: 'changed' }] } })
  await w.vm.submitChangeOfferingForVolume(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.errorText).toContain('changed')
  store.getters.project = { id: 'foreign' }; await w.vm.submitChangeOfferingForVolume(); expect(postAPI).not.toHaveBeenCalled(); w.unmount()
})
test.each([Migration, Offering])('unknown submit for $name preserves input and cannot post twice', async component => {
  postAPI.mockRejectedValueOnce(new Error('lost response')); const w = mount(component); await flushPromises()
  const submit = () => component === Migration ? w.vm.submitForm() : w.vm.submitChangeOfferingForVolume()
  await submit(); await submit(); expect(postAPI).toHaveBeenCalledTimes(1); expect(w.vm.submissionUnknown).toBe(true); expect(w.emitted('close-action')).toBeUndefined(); w.unmount()
})
test.each([Migration, Offering])('unknown job for $name resumes the same job without a second POST', async component => {
  poll.mockResolvedValueOnce({ jobstatus: null }); const w = mount(component); await flushPromises()
  await (component === Migration ? w.vm.submitForm() : w.vm.submitChangeOfferingForVolume()); expect(w.vm.jobId).toBe('j'); await w.vm.resumeJob()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(poll.mock.calls[1][0]).toMatchObject({ jobId: 'j', retry: true }); w.unmount()
})
