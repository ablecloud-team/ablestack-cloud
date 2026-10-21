// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import { shallowMount, mount as mountFull, flushPromises } from '@vue/test-utils'
import Update from '@/views/offering/UpdateDiskOffering.vue'
import Qos from '@/components/offering/DiskQosFields.vue'
import DiskForm from '@/components/offering/DiskOfferingForm.vue'
import ComputeForm from '@/components/offering/ComputeOfferingForm.vue'
import CloneDisk from '@/views/offering/CloneDiskOffering.vue'
import CloneCompute from '@/views/offering/CloneComputeOffering.vue'
import AddCompute from '@/views/offering/AddComputeOffering.vue'
import { qosFields, qosGroups, qosForm, qosError, qosParams, performanceParams } from '@/utils/offeringQos'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/role', () => ({ isAdmin: () => true }))
const record = { id: 'o', name: 'Offering', displaytext: 'Keep', cachemode: 'none', state: 'Inactive', iscustomizediops: true, tags: 'one,two', diskBytesReadRate: 100, diskBytesReadRateMax: 200, diskBytesReadRateMaxLength: 2 }
const params = Object.fromEntries(['name', 'displaytext', 'tags', 'cachemode', ...qosFields.map(field => field.api)].map(name => [name, { name }]))
const response = value => ({ listdiskofferingsresponse: { diskoffering: [value] } })
const globals = { directives: { focus: {}, 'ctrl-enter': {} }, mocks: { $t: key => key, $message: { success: jest.fn() }, $store: { getters: { apis: { updateDiskOffering: { params: Object.values(params) } }, userInfo: { roletype: 'Admin' } } } }, stubs: { 'a-spin': { template: '<div><slot/></div>' }, 'a-form': { template: '<form><slot/></form>', methods: { validate: () => Promise.resolve() } }, 'a-form-item': { template: '<div><slot name="label"/><slot/></div>' }, DiskQosFields: { template: '<div/>', methods: { validate: () => Promise.resolve() } } } }
const mount = () => shallowMount(Update, { props: { resource: { id: 'o', name: 'stale' } }, global: globals })
beforeEach(() => { getAPI.mockReset(); postAPI.mockReset(); getAPI.mockResolvedValue(response({ ...record })); postAPI.mockResolvedValue({ updatediskofferingresponse: { diskoffering: record } }) })

test('all twelve response values, including numeric zero, survive form conversion', () => {
  const resource = Object.fromEntries(qosFields.map((field, index) => [field.response, index]))
  const values = qosForm(resource)
  qosFields.forEach((field, index) => expect(values[field.form]).toBe(String(index)))
  expect(qosForm({}).diskbytesreadrate).toBe('')
})
test.each(['-1', '1.1', '1e3', 'Infinity', '9007199254740992'])('invalid QoS %s cannot pass validation', value => {
  expect(qosError({ diskbytesreadrate: value })).toBe('message.offering.qos.integer')
})
test.each(qosGroups)('$api checks effective burst relationships', group => {
  expect(qosError({ [group.form + 'max']: '100' })).toBe('message.offering.qos.burst')
  expect(qosError({ [group.form]: '100', [group.form + 'max']: '99' })).toBe('message.offering.qos.burst')
  expect(qosError({ [group.form]: '100', [group.form + 'maxlength']: '1' })).toBe('message.offering.qos.length')
  expect(qosError({ [group.form]: '100', [group.form + 'max']: '100', [group.form + 'maxlength']: '1' })).toBe('')
  expect(qosError({ [group.form]: '0', [group.form + 'max']: '0', [group.form + 'maxlength']: '0' })).toBe('')
})
test('QoS payload preserves zero and filters unsupported and hidden fields', () => {
  expect(qosParams({ diskbytesreadrate: 0, diskbyteswriterate: '999' }, { bytesreadrate: {} })).toEqual({ bytesreadrate: '0' })
  expect(performanceParams({ qostype: '', diskbytesreadrate: '999', diskiopsmin: '888', writecachetype: 'none' })).toEqual({ cachemode: 'none' })
  expect(performanceParams({ qostype: 'hypervisor', diskbytesreadrate: 0, diskiopsmin: 999, shareable: true, cachemode: 'writeback' })).toEqual({ cachemode: 'none', bytesreadrate: '0' })
  expect(performanceParams({ qostype: 'storage', diskiopsmin: 100, diskiopsmax: 200, iscustomizeddiskiops: false })).toEqual({ customizediops: false, miniops: '100', maxiops: '200' })
  expect(performanceParams({ qostype: 'hypervisor', diskbytesreadrate: '999', cachemode: 'writeback' }, true, true)).toEqual({})
})
test('clone none clears source throttles and storage IOPS instead of inheriting hidden settings', () => {
  const values = performanceParams({ qostype: '', cachemode: 'none', diskbytesreadrate: '999' }, false, true)
  qosFields.forEach(field => expect(values[field.api]).toBe('0'))
  expect(values).toMatchObject({ customizediops: false, miniops: '0', maxiops: '0' })
})
test('storage maximum IOPS cannot be mistaken for a hypervisor burst maximum', () => {
  const context = { form: { qostype: '', diskiopsmin: '100', diskiopsmax: '200' } }
  DiskForm.methods.normalizeQosType.call(context)
  expect(context.form.qostype).toBe('storage')
  expect(performanceParams(context.form, false, true)).toMatchObject({ miniops: '100', maxiops: '200', customizediops: false })
})
test.each([DiskForm, ComputeForm])('child QoS values survive parent ref filtering for $name', async component => {
  const context = { form: { name: 'New', qostype: 'hypervisor', diskbytesreadrate: 0, diskiopsreadratemax: '200', computeonly: false }, $refs: { internalFormRef: { validate: () => Promise.resolve() }, qosEditor: { validate: () => Promise.resolve() } }, handleRemoveFields: () => ({ name: 'New' }) }
  const values = await component.methods.validate.call(context)
  expect(values).toMatchObject(context.form)
})
test.each([AddCompute, CloneCompute])('actual compute form accepts zero QoS in $name while CPU rules stay positive', async component => {
  const positive = { validator: (rule, value) => Number(value) > 0 ? Promise.resolve() : Promise.reject(new Error('positive required')) }
  const context = { $t: key => key, naturalNumberRule: positive }
  component.methods.initForm.call(context)
  const zero = Object.fromEntries(qosFields.map(field => [field.form, '0']))
  const w = mountFull({
    components: { Qos },
    data: () => ({ zero, params, rules: context.rules }),
    template: '<a-form ref="form" :model="zero" :rules="rules"><Qos ref="qos" :form="zero" :api-params="params" /></a-form>'
  }, { global: { mocks: { $t: key => key } } })
  await expect(w.vm.$refs.qos.validate()).resolves.toBeUndefined()
  await expect(w.vm.$refs.form.validate()).resolves.toBeDefined()
  expect(context.rules.cpunumber).toContainEqual(positive)
  w.unmount()
})
test.each([CloneDisk, CloneCompute])('clone $name retains complete bursts and numeric zero', component => {
  const context = { form: {} }
  component.methods.populateFormFromResource.call(context, { ...record, diskIopsReadRate: 0, diskIopsWriteRate: 10, diskIopsWriteRateMax: 20, diskIopsWriteRateMaxLength: 3 })
  expect(context.form).toMatchObject({ diskbytesreadrate: '100', diskbytesreadratemax: '200', diskbytesreadratemaxlength: '2', diskiopsreadrate: '0', diskiopswriterate: '10', diskiopswriteratemax: '20', diskiopswriteratemaxlength: '3' })
})
test.each([CloneDisk, CloneCompute])('clone $name cannot submit using an unread or stale source', async component => {
  const context = { resource: { id: 'o' }, form: { name: 'Preserve' }, $t: key => key, populateFormFromResource: jest.fn(), resolveComputeOnlyState: jest.fn() }
  getAPI.mockRejectedValueOnce(new Error('source unavailable'))
  await component.methods.fetchSourceOffering.call(context)
  expect(context.sourceReady).toBe(false); expect(context.form.name).toBe('Preserve'); expect(context.sourceError).toBe('source unavailable')
  expect(context.populateFormFromResource).not.toHaveBeenCalled()
})
test('edit loads fresh data and sends changed supported values only, retaining scope and Inactive state', async () => {
  const w = mount(); await flushPromises()
  expect(getAPI).toHaveBeenCalledWith('listDiskOfferings', { id: 'o', listall: true, state: 'all' }, { preserveOnFailure: true })
  expect(w.vm.form.name).toBe('Offering')
  w.vm.form.diskbytesreadrate = '150'; w.vm.form.cachemode = 'writeback'
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('updateDiskOffering', { id: 'o', bytesreadrate: '150', cachemode: 'writeback' }, { preserveOnFailure: true })
  expect(w.emitted('refresh-data')).toHaveLength(1); w.unmount()
})
test('clearing a burst sends both explicit zeros', async () => {
  const w = mount(); await flushPromises()
  w.vm.form.diskbytesreadratemax = '0'; w.vm.form.diskbytesreadratemaxlength = '0'
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('updateDiskOffering', { id: 'o', bytesreadratemax: '0', bytesreadratemaxlength: '0' }, { preserveOnFailure: true }); w.unmount()
})

test('blank edit fields preserve current burst values while explicit zero still validates the resulting combination', async () => {
  const w = mount(); await flushPromises()
  w.vm.form.diskbytesreadrate = ''; w.vm.form.diskbytesreadratemax = ''
  w.vm.form.name = 'Preserve limits'
  expect(w.vm.qosValidation).toBe('')
  expect(qosError(w.vm.form, w.vm.original)).toBe('')
  await w.vm.handleSubmit()
  expect(postAPI).toHaveBeenCalledWith('updateDiskOffering', { id: 'o', name: 'Preserve limits' }, { preserveOnFailure: true })
  w.vm.form.diskbytesreadrate = '0'
  expect(w.vm.qosValidation).toBe('message.offering.qos.burst')
  w.vm.form.diskbytesreadrate = ''; w.vm.form.diskbytesreadratemax = '0'
  expect(w.vm.qosValidation).toBe('message.offering.qos.length')
  w.unmount()
})
test('duplicate clicks are locked before validation resolves', async () => {
  let finish
  postAPI.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const w = mount(); await flushPromises(); w.vm.form.name = 'Changed'
  const first = w.vm.handleSubmit(); const second = w.vm.handleSubmit()
  await flushPromises(); expect(postAPI).toHaveBeenCalledTimes(1)
  finish({}); await Promise.all([first, second]); w.unmount()
})
test('read failure blocks saving and retry restores fresh values', async () => {
  getAPI.mockRejectedValueOnce(new Error('read failed'))
  const w = mount(); await flushPromises(); await w.vm.handleSubmit()
  expect(w.vm.readError).toBe('read failed'); expect(postAPI).not.toHaveBeenCalled()
  await w.vm.load(); expect(w.vm.loaded).toBe(true); w.unmount()
})
test('server error retains edits; unknown response blocks duplicate saves until reload', async () => {
  const w = mount(); await flushPromises(); w.vm.form.name = 'Keep me'
  postAPI.mockRejectedValueOnce({ response: { data: { errorresponse: { errortext: 'denied' } } } })
  await w.vm.handleSubmit(); expect(w.vm.form.name).toBe('Keep me'); expect(w.vm.submitError).toBe('denied'); expect(w.vm.unknownResult).toBe(false)
  postAPI.mockRejectedValueOnce(new Error('lost response'))
  await w.vm.handleSubmit(); await w.vm.handleSubmit(); expect(postAPI).toHaveBeenCalledTimes(2); expect(w.vm.unknownResult).toBe(true)
  await w.vm.load(); expect(w.vm.unknownResult).toBe(false); w.unmount()
})
test('old read cannot overwrite a different offering', async () => {
  let release
  getAPI.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const w = mount(); await w.setProps({ resource: { id: 'new', name: 'New' } })
  getAPI.mockResolvedValue(response({ ...record, id: 'new', name: 'Latest' }))
  await w.vm.load(); release(response(record)); await flushPromises()
  expect(w.vm.form.name).toBe('Latest'); w.unmount()
})
test('QoS field rendering uses API-supported fields and validates safe values', async () => {
  const w = shallowMount(Qos, { props: { form: { diskbytesreadrate: '-1' }, apiParams: { bytesreadrate: {} } }, global: { ...globals, stubs: { 'a-form-item': { template: '<div><slot name="label"/><slot/></div>' } } } })
  expect(w.findAll('a-input-stub')).toHaveLength(1)
  await expect(w.vm.validate()).rejects.toThrow('message.offering.qos.integer'); w.unmount()
})
