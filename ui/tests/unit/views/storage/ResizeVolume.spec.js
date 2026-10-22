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

import { shallowMount, flushPromises } from '@vue/test-utils'
import Resize from '@/views/storage/ResizeVolume.vue'
import { getAPI, postAPI } from '@/api'
import { readVolumeResizeContract, volumeResizeParams, volumeResizeIops, volumeResizeSizeReason, validateVolumeResizeSnapshot } from '@/utils/volumeResize'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const v = { id: 'v1', type: 'DATADISK', name: 'target', state: 'Ready', size: 1073741824, miniops: 100, maxiops: 200, diskofferingid: 'target-offering', storageid: 'pool', path: 'image', account: 'owner', domainid: 'domain' }
const offering = { id: v.diskofferingid, name: 'target-custom', iscustomized: true, iscustomizediops: true }
const contract = { kind: 'disk', offering }
const params = { size: {}, miniops: {}, maxiops: {}, shrinkok: {}, automigrate: {} }
const form = () => ({ size: '1', miniops: '100', maxiops: '200', shrinkok: false, automigrate: false })
test('exact ID is used regardless of an unrelated first offering and more than ten offerings', async () => {
  const api = jest.fn().mockResolvedValue({ listdiskofferingsresponse: { diskoffering: [offering] } })
  expect(await readVolumeResizeContract(api, v)).toEqual(contract)
  expect(api).toHaveBeenCalledWith('listDiskOfferings', { id: v.diskofferingid, state: 'all', listall: true }, { preserveOnFailure: true })
})
test.each([[], [{ ...offering, id: 'first-fixed' }], [offering, offering]].map(rows => [rows]))('empty, foreign or ambiguous offering fails closed: %p', async rows => {
  await expect(readVolumeResizeContract(jest.fn().mockResolvedValue({ listdiskofferingsresponse: { diskoffering: rows } }), v)).rejects.toThrow('offering')
})
test('compute-only ROOT resolves VM UUID rather than numeric volume serviceofferingid', async () => {
  const api = jest.fn()
    .mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm1', serviceofferingid: 'service-uuid' }] } })
    .mockResolvedValueOnce({ listserviceofferingsresponse: { serviceoffering: [{ id: 'service-uuid', name: 'compute', diskofferingid: 'hidden-disk', diskofferingcomputeonly: true }] } })
  const root = { ...v, type: 'ROOT', diskofferingid: undefined, virtualmachineid: 'vm1', serviceofferingid: '12', serviceofferingname: 'compute' }
  const c = await readVolumeResizeContract(api, root)
  expect(c.kind).toBe('compute'); expect(api.mock.calls[1][1].id).toBe('service-uuid')
  expect(volumeResizeIops(root, c)).toBe(true)
})
test('stale root compute relationship and missing data disk ID fail closed', async () => {
  await expect(readVolumeResizeContract(jest.fn(), { ...v, diskofferingid: undefined })).rejects.toThrow('offering')
  const api = jest.fn().mockResolvedValueOnce({ listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm1', serviceofferingid: 's' }] } }).mockResolvedValueOnce({ listserviceofferingsresponse: { serviceoffering: [{ id: 's', diskofferingid: 'd', name: 'other' }] } })
  await expect(readVolumeResizeContract(api, { ...v, type: 'ROOT', diskofferingid: undefined, virtualmachineid: 'vm1', serviceofferingname: 'original' })).rejects.toThrow('offering')
})
test('size-only, IOPS-only, simultaneous, single IOPS change and zero preserve exact semantics', () => {
  expect(volumeResizeParams(v, { ...form(), size: '2' }, contract, params)).toEqual({ id: 'v1', size: 2 })
  expect(volumeResizeParams(v, { ...form(), miniops: '200', maxiops: '400' }, contract, params)).toEqual({ id: 'v1', miniops: 200, maxiops: 400 })
  expect(volumeResizeParams(v, { ...form(), size: '2', maxiops: '400', automigrate: true }, contract, params)).toEqual({ id: 'v1', size: 2, maxiops: 400, automigrate: true })
  expect(volumeResizeParams(v, { ...form(), miniops: '0' }, contract, params)).toEqual({ id: 'v1', miniops: 0 })
  expect(volumeResizeParams(v, { ...form(), size: '', miniops: '', maxiops: '' }, contract, params)).toEqual({ id: 'v1' })
})
test.each(['-1', '1.5', 'NaN', '1e3', 'Infinity', '9007199254740992'])('invalid IOPS %s is rejected', value => {
  expect(() => volumeResizeParams(v, { ...form(), miniops: value }, contract, params)).toThrow('integer')
})
test.each(['0', '-1', '1.1', '1e3', '9007199254740991'])('invalid size %s is rejected', value => {
  expect(() => volumeResizeParams(v, { ...form(), size: value }, contract, params)).toThrow('integer')
})
test('ordering is checked against preserved values; absent pair cannot be created partially', () => {
  expect(() => volumeResizeParams(v, { ...form(), miniops: '201', maxiops: '' }, contract, params)).toThrow('iops')
  expect(() => volumeResizeParams({ ...v, miniops: undefined, maxiops: undefined }, { size: '1', miniops: '1', maxiops: '' }, contract, params)).toThrow('iops')
})
test('fixed IOPS is read-only and explicit allocation blocks size but permits IOPS-only', () => {
  const fixed = { ...contract, offering: { ...offering, iscustomizediops: false } }
  expect(volumeResizeIops(v, fixed)).toBe(false)
  expect(() => volumeResizeParams(v, { ...form(), maxiops: '300' }, fixed, params)).toThrow('forbidden')
  const explicit = { ...v, allocation: { contractversion: '1', requestedformat: 'raw', requestedpolicy: 'off' } }
  expect(() => volumeResizeParams(explicit, { ...form(), size: '2' }, contract, params)).toThrow('lifecycle')
  expect(volumeResizeParams(explicit, { ...form(), maxiops: '300' }, contract, params)).toEqual({ id: 'v1', maxiops: 300 })
  expect(volumeResizeSizeReason(v, { kind: 'disk', offering: { disksizestrictness: true } })).toContain('strict')
  expect(volumeResizeSizeReason(v, { kind: 'compute', offering: { rootdisksize: 10 } })).toBe('')
})
test('shrink requires supported format and acknowledgement, and hidden API fields are never sent', () => {
  const large = { ...v, size: 2147483648, allocation: { actualformat: 'qcow2' } }
  expect(() => volumeResizeParams(large, { ...form(), shrinkok: true }, contract, params)).toThrow('shrink')
  expect(() => volumeResizeParams({ ...large, allocation: { actualformat: 'raw' } }, form(), contract, params)).toThrow('shrink.confirm')
  expect(volumeResizeParams({ ...large, allocation: { actualformat: 'raw' } }, { ...form(), shrinkok: true }, contract, params)).toEqual({ id: 'v1', size: 1, shrinkok: true })
  expect(() => volumeResizeParams(v, { ...form(), maxiops: '300' }, contract, { size: {} })).toThrow('forbidden')
})
test.each(['size', 'miniops', 'maxiops', 'diskofferingid', 'path', 'chaininfo', 'storageid', 'state', 'virtualmachineid', 'account', 'projectid'])('stale %s fails pre-submit', key => {
  expect(() => validateVolumeResizeSnapshot(v, { ...v, [key]: 'changed' }, contract, contract)).toThrow('changed')
})
let store, route, poll
function mount (resource = v) {
  store = { getters: { userInfo: { id: 'actor', roletype: 'Admin' }, project: null } }; route = { path: '/volume/' + resource.id }
  return shallowMount(Resize, { props: { resource: { ...resource } }, global: { directives: { 'ctrl-enter': {} }, mocks: { $t: x => x, $getApiParams: () => params, $store: store, $route: route, $pollJob: (...args) => poll(...args) }, stubs: { 'a-form': { template: '<form><slot/></form>' }, 'a-form-item': { template: '<div><slot/></div>' }, 'a-button': { template: '<button><slot/></button>' } } } })
}
beforeEach(() => {
  getAPI.mockReset(); postAPI.mockReset()
  getAPI.mockImplementation(async cmd => cmd === 'listVolumes' ? { listvolumesresponse: { volume: [{ ...v }] } } : { listdiskofferingsresponse: { diskoffering: [{ ...offering }] } })
  postAPI.mockResolvedValue({ resizevolumeresponse: { jobid: 'job-1' } }); poll = jest.fn().mockResolvedValue({ jobstatus: 1 })
})
test('current values initialize and unchanged confirmation performs no mutation', async () => {
  const w = mount(); await flushPromises(); expect(w.vm.form).toMatchObject(form())
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.emitted('close-action')).toHaveLength(1); w.unmount()
})
test('read failure and empty response retain input and retry capability', async () => {
  getAPI.mockRejectedValueOnce(new Error('network disconnected'))
  const w = mount(); await flushPromises(); expect(w.vm.errorText).toBe('network disconnected'); expect(w.vm.contract).toBe(null)
  expect(w.findAll('button').some(button => button.text() === 'label.retry')).toBe(true)
  w.vm.form.maxiops = '400'; await w.vm.fetchData(); expect(w.vm.form.maxiops).toBe('400')
  expect(w.vm.contract).toEqual(contract); w.unmount()
})
test('double submit waits for known job result and sends only changed keys', async () => {
  let done; poll.mockImplementation(() => new Promise(resolve => { done = resolve }))
  const w = mount(); await flushPromises(); w.vm.form.maxiops = '400'
  const request = w.vm.handleSubmit(); w.vm.handleSubmit(); await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1); expect(postAPI.mock.calls[0][1]).toEqual({ id: 'v1', maxiops: 400 })
  expect(w.emitted('close-action')).toBeUndefined(); done({ jobstatus: 1 }); await request
  expect(w.emitted('refresh-data')).toHaveLength(1); w.unmount()
})
test('permission and terminal job failures retain inputs and reasons', async () => {
  const w = mount(); await flushPromises(); w.vm.form.maxiops = '400'
  postAPI.mockRejectedValueOnce({ response: { status: 403, data: { errorresponse: { errortext: 'Permission removed' } } } })
  await w.vm.handleSubmit(); expect(w.vm.form.maxiops).toBe('400'); expect(w.vm.errorText).toBe('Permission removed'); expect(w.vm.submissionUnknown).toBe(false)
  poll.mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'Snapshot prevents resize' } })
  await w.vm.handleSubmit(); expect(w.vm.errorText).toBe('Snapshot prevents resize'); expect(w.emitted('close-action')).toBeUndefined(); w.unmount()
})
test('unknown poll retries same job; unknown submit cannot send another mutation', async () => {
  const w = mount(); await flushPromises(); w.vm.form.maxiops = '400'
  poll.mockResolvedValueOnce({ jobstatus: null }); await w.vm.handleSubmit(); expect(w.vm.jobId).toBe('job-1')
  await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(1)
  await w.vm.resumeJob(); expect(poll.mock.calls[1][0]).toMatchObject({ jobId: 'job-1', batchKey: 'job-1', retry: true }); w.unmount()
  postAPI.mockRejectedValueOnce(new Error('connection lost'))
  const other = mount(); await flushPromises(); other.vm.form.maxiops = '400'; await other.vm.handleSubmit(); expect(other.vm.submissionUnknown).toBe(true)
  const count = postAPI.mock.calls.length; await other.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(count); other.unmount()
})
test('stale target and project transition cannot submit', async () => {
  const w = mount(); await flushPromises(); w.vm.form.maxiops = '400'
  getAPI.mockResolvedValueOnce({ listvolumesresponse: { volume: [{ ...v, miniops: 150 }] } })
  await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.errorText).toContain('changed')
  store.getters.project = { id: 'foreign-project' }; await w.vm.handleSubmit(); expect(postAPI).not.toHaveBeenCalled(); expect(w.vm.errorText).toContain('context'); w.unmount()
})
