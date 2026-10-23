// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
import { mount, shallowMount, flushPromises } from '@vue/test-utils'
import Sections from '@/components/offering/OfferingFormSections'
import Group from '@/components/offering/OfferingAdvancedGroup'
import Editor from '@/components/offering/AllocationPolicyEditor'
import Definition from '@/components/offering/OfferingDiskDefinition'
import Qos from '@/components/offering/DiskQosFields'
import { reactive } from 'vue'
import { qosFields } from '@/utils/offeringQos'
import DiskForm from '@/components/offering/DiskOfferingForm'
import ComputeForm from '@/components/offering/ComputeOfferingForm'
import AddDisk from '@/views/offering/AddDiskOffering'
import AddCompute from '@/views/offering/AddComputeOffering'
import { newOfferingAllocation, allocationParams } from '@/utils/volumeAllocation'
import { storageDialogSize } from '@/utils/storageUi'
import { getAPI, postAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const apiParams = { allocationformat: {}, allocationpolicy: {} }
const testGlobals = { mocks: { $t: key => key } }
beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })

test('new offering dialogs use the compact standard shell while other operation sizes remain unchanged', () => {
  expect(storageDialogSize('createServiceOffering')).toBe('md')
  expect(storageDialogSize('createDiskOffering')).toBe('md')
  expect(storageDialogSize('migrateVolume')).toBe('lg')
  expect(storageDialogSize('cloneDiskOffering')).toBe('lg')
})

test('new supported offerings default to Europa while older API schemas keep legacy values', () => {
  expect(allocationParams(newOfferingAllocation(apiParams))).toEqual({ allocationpolicy: 'off', allocationformat: 'raw' })
  for (const old of [{}, { allocationpolicy: {} }, { allocationformat: {} }]) {
    expect(allocationParams(newOfferingAllocation(old))).toEqual({ provisioningtype: 'thin' })
  }
})

test('Europa is first, needs no storage/host lookup and preserves administrator intent across valid format changes', async () => {
  const form = { ...newOfferingAllocation(apiParams), allocationpolicy: 'full', provisioningtype: 'fat' }
  const w = mount(Editor, { props: { form, apiParams, onChange: values => Object.assign(form, values) }, global: testGlobals })
  expect(w.findAll('input[type="radio"]').map(input => input.element.value)).toEqual(['explicit', 'legacy'])
  await expect(w.vm.validate()).resolves.toBeUndefined()
  w.vm.changeFormat('qcow2'); await w.vm.$nextTick()
  expect(form).toMatchObject({ allocationformat: 'qcow2', allocationpolicy: 'full', provisioningtype: 'fat' })
  expect(getAPI).not.toHaveBeenCalled()
  expect(w.text()).not.toContain('label.allocation.reference.pool')
  w.unmount()
})

test('switching RAW Thin to QCOW2 uses metadata and switching back uses off without altering legacy values', async () => {
  const form = { ...newOfferingAllocation(apiParams), provisioningtype: 'sparse' }
  const w = shallowMount(Editor, { props: { form, apiParams, onChange: values => Object.assign(form, values) }, global: testGlobals })
  w.vm.changeFormat('qcow2'); await w.vm.$nextTick()
  expect(allocationParams(form)).toEqual({ allocationpolicy: 'metadata', allocationformat: 'qcow2' })
  w.vm.changeMode('legacy'); await w.vm.$nextTick()
  expect(allocationParams(form)).toEqual({ provisioningtype: 'sparse' })
  w.vm.changeMode('explicit'); w.vm.changeFormat('raw'); await w.vm.$nextTick()
  expect(allocationParams(form)).toEqual({ allocationpolicy: 'off', allocationformat: 'raw' })
  w.unmount()
})

test.each([
  { allocationformat: 'raw', allocationpolicy: 'metadata' },
  { allocationformat: 'qcow2', allocationpolicy: 'off' },
  { allocationformat: 'raw', allocationpolicy: 'unknown' },
  { allocationformat: 'vmdk', allocationpolicy: 'full' },
  { storagetype: 'local' }, { encryptdisk: true }
])('invalid creation definition %j is blocked without a runtime query', async invalid => {
  const w = shallowMount(Editor, { props: { apiParams, form: { ...newOfferingAllocation(apiParams), ...invalid } }, global: testGlobals })
  await expect(w.vm.validate()).rejects.toThrow('message.offering.allocation.invalid')
  expect(getAPI).not.toHaveBeenCalled(); w.unmount()
})

test('tab changes keep every field mounted, retain input and reveal an invalid advanced field inside its collapsed group', async () => {
  const w = mount({
    components: { Sections, Group },
    data: () => ({ name: 'Initial', tags: 'gfs2', rate: '1000' }),
    template: '<Sections ref="sections" enabled><template #basic><input id="form_item_name" v-model="name" /></template><template #volume><input id="form_item_tags" v-model="tags" /></template><template #advanced><Group enabled title="Advanced"><input id="form_item_networkrate" v-model="rate" /><div class="ant-form-item"><label for="form_item_zoneid">Zone</label><input id="custom-zone" /></div></Group></template></Sections>'
  }, { global: testGlobals })
  await flushPromises()
  await w.get('#form_item_name').setValue('Keep this name')
  await w.findAll('[role="tab"]')[1].trigger('click'); await flushPromises()
  await w.get('#form_item_tags').setValue('gfs2,ssd')
  await w.findAll('[role="tab"]')[0].trigger('click'); await flushPromises()
  expect(w.get('#form_item_name').element.value).toBe('Keep this name')
  expect(w.get('#form_item_tags').element.value).toBe('gfs2,ssd')
  await w.vm.$refs.sections.revealField('networkrate')
  expect(w.vm.$refs.sections.active).toBe('advanced')
  expect(w.get('details').element.open).toBe(true)
  expect(w.get('#form_item_tags').element.value).toBe('gfs2,ssd')
  await w.vm.$refs.sections.reveal('basic')
  w.get('details').element.open = false
  await w.vm.$refs.sections.revealField('zoneid')
  expect(w.vm.$refs.sections.active).toBe('advanced')
  expect(w.get('details').element.open).toBe(true)
  w.unmount()
})

test.each([DiskForm, ComputeForm])('validation in $name opens the policy or QoS tab and never drops values from other tabs', async component => {
  const reveal = jest.fn(); const context = { form: { name: 'Keep', allocationmode: 'explicit', allocationformat: 'raw', allocationpolicy: 'full' }, $refs: { sections: { reveal }, allocationEditor: { validate: jest.fn().mockRejectedValue(new Error('policy')) } } }
  await expect(component.methods.validate.call(context)).rejects.toThrow('policy')
  expect(reveal).toHaveBeenCalledWith('volume')
  context.$refs.allocationEditor.validate.mockResolvedValue()
  context.$refs.qosEditor = { validate: jest.fn().mockRejectedValue(new Error('qos')) }
  await expect(component.methods.validate.call(context)).rejects.toThrow('qos')
  expect(reveal).toHaveBeenCalledWith('advanced')
  expect(context.form.name).toBe('Keep')
})

test.each([AddDisk, AddCompute])('$name sends the Europa definition once with session-preserving error handling', async component => {
  const values = { name: 'New', ispublic: true, computeonly: true, offeringtype: 'fixed', cpunumber: '1', cpuspeed: '1000', memory: '256', networkrate: 10000, rootdisksize: 1, ...newOfferingAllocation(apiParams) }
  let complete
  postAPI.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const form = { validate: () => Promise.resolve(values) }
  const context = { loading: false, formRef: { value: form }, $refs: { formRef: form }, $t: key => key, $message: { success: jest.fn(), error: jest.fn() }, $emit: jest.fn(), closeAction: jest.fn(), domains: [], zones: [] }
  component.methods.handleSubmit.call(context)
  component.methods.handleSubmit.call(context)
  await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  const [command, params, options] = postAPI.mock.calls[0]
  expect(command).toBe(component === AddDisk ? 'createDiskOffering' : 'createServiceOffering')
  expect(params).toMatchObject({ name: 'New', allocationformat: 'raw', allocationpolicy: 'off' })
  if (component === AddCompute) expect(params).toMatchObject({ networkrate: 10000, rootdisksize: 1 })
  expect(params).not.toHaveProperty('provisioningtype')
  expect(params).not.toHaveProperty('storageid')
  expect(options).toEqual({ preserveOnFailure: true })
  complete({}); await flushPromises()
  expect(context.closeAction).toHaveBeenCalledTimes(1)
})

test('linked offering summary shows definition and tags rather than fabricated allocation evidence', () => {
  const w = shallowMount(Definition, { props: { resource: { tags: 'gfs2,ssd', allocation: { contractversion: '1', requestedformat: 'qcow2', requestedpolicy: 'metadata', evidence: 'unverified' } } }, global: testGlobals })
  expect(w.text()).toContain('QCOW2'); expect(w.text()).toContain('metadata'); expect(w.text()).toContain('gfs2,ssd')
  expect(w.text()).not.toContain('appliedmethod'); w.unmount()
})

test('creation QoS keeps four base limits separate from mounted burst inputs and preserves values when collapsed', async () => {
  const form = reactive({})
  const schema = Object.fromEntries(qosFields.map(field => [field.api, {}]))
  const w = mount({ components: { Qos }, setup: () => ({ form, schema }), template: '<a-form :model="form"><Qos ref="qos" :form="form" :api-params="schema" compact /></a-form>' }, { global: testGlobals })
  expect(w.findAll('.qos-base-groups input')).toHaveLength(4)
  expect(w.findAll('.qos-burst input')).toHaveLength(8)
  expect(w.get('details').element.open).toBe(false)
  await w.get('input[data-qos="bytesreadrate"]').setValue('100')
  w.get('details').element.open = true
  await w.get('input[data-qos="bytesreadratemax"]').setValue('200')
  await w.get('input[data-qos="bytesreadratemaxlength"]').setValue('2')
  w.get('details').element.open = false
  await expect(w.vm.$refs.qos.validate()).resolves.toBeUndefined()
  expect(form).toMatchObject({ diskbytesreadrate: '100', diskbytesreadratemax: '200', diskbytesreadratemaxlength: '2' })
  w.unmount()
})

test('an invalid burst opens its controls so creation cannot fail behind a closed disclosure', async () => {
  const form = reactive({ diskbytesreadrate: '100', diskbytesreadratemax: '0', diskbytesreadratemaxlength: '1' })
  const schema = Object.fromEntries(qosFields.map(field => [field.api, {}]))
  const w = mount({ components: { Qos }, setup: () => ({ form, schema }), template: '<a-form :model="form"><Qos ref="qos" :form="form" :api-params="schema" compact /></a-form>' }, { global: testGlobals })
  expect(w.get('details').element.open).toBe(true)
  await expect(w.vm.$refs.qos.validate()).rejects.toThrow('message.offering.qos.length')
  w.unmount()
})
