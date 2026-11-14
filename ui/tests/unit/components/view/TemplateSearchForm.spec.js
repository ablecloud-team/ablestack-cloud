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

import Search from '@/components/view/SearchView.vue'
import Chips from '@/components/view/SearchFilter.vue'
import { flushPromises } from '@vue/test-utils'
const id = '11111111-1111-4111-8111-111111111111'
const context = () => {
  const c = { apiName: 'listTemplates', fields: [], form: {}, rules: {}, searchFilters: ['isready', 'hypervisor', 'forcks', 'userdataid'], volumeOwnerContext: 'owner-a', $route: { name: 'template', meta: { name: 'template' }, query: { isready: 'false', hypervisor: 'KVM', forcks: 'false', userdataid: id } }, $store: { getters: { apis: {} } }, $getApiParams: () => ({ isready: {}, hypervisor: {}, forcks: {}, userdataid: {} }), $t: key => key, $isValidUuid: value => value === id, $notifyError: jest.fn() }
  Object.entries(Search.methods).forEach(([key, fn]) => { c[key] = fn.bind(c) }); return c
}
test('template boolean filters are nullable selects and restore explicit false from URL', () => {
  const c = context(); const keys = c.initFields(); expect(keys).toEqual(c.searchFilters); c.fetchStaticFieldData(keys); c.fillFormFieldValues()
  expect(c.fields.filter(f => ['isready', 'forcks'].includes(f.name)).every(f => f.type === 'list')).toBe(true); expect(c.form.isready).toBe('false'); expect(c.form.forcks).toBe('false'); expect(c.form.userdataid).toBe(id)
  expect(c.fields.find(f => f.name === 'isready').opts.map(o => o.id)).toEqual(['true', 'false'])
})
test('unsupported capabilities do not expose nonfunctional template filters', () => { const c = context(); c.$getApiParams = () => ({}); expect(c.initFields()).toEqual([]) })
test('exact name label and boolean chips communicate filter meaning', () => {
  const c = context(); expect(c.retrieveFieldLabel('name')).toBe('label.template.name.exact')
  expect(Chips.methods.getStaticFieldValue.call({ apiName: 'listTemplates', $t: key => key }, 'isready', 'false')).toBe('label.no')
})
test('late lookup cannot populate another owner or rebuilt form', async () => {
  const c = context(); c.fields = [{ name: 'zoneid', opts: [] }]; let release; c.fetchZones = () => new Promise(resolve => { release = resolve })
  const pending = c.fetchDynamicFieldData(['zoneid']); c.volumeOwnerContext = 'owner-b'; release({ type: 'zoneid', data: [{ id, name: 'old owner zone' }] }); await pending; await flushPromises(); expect(c.fields[0].opts).toEqual([])
  c.fetchZones = async () => ({ type: 'zoneid', data: [{ id, name: 'current' }] }); await c.fetchDynamicFieldData(['zoneid']); await flushPromises(); expect(c.fields[0].opts).toEqual([{ id, name: 'current' }])
})

test('Ctrl+Enter validation failure is handled and retains the editable draft', async () => {
  const c = context(); c.form.userdataid = 'invalid'; c.formRef = { value: { validate: jest.fn().mockRejectedValue({ errorFields: [{ name: ['userdataid'] }] }), scrollToField: jest.fn() } }; c.$emit = jest.fn(); c.handleSubmit(); await flushPromises(); expect(c.form.userdataid).toBe('invalid'); expect(c.formRef.value.scrollToField).toHaveBeenCalledWith(['userdataid']); expect(c.$emit).not.toHaveBeenCalled()
})

test('same-owner late choices preserve a newly typed template search draft', async () => {
  const c = context(); c.fields = [{ name: 'name', type: 'input', opts: [] }, { name: 'zoneid', type: 'list', opts: [] }]; c.$route.query.name = 'old query'; let release; c.fetchZones = () => new Promise(resolve => { release = resolve })
  const pending = c.fetchDynamicFieldData(['zoneid']); c.form.name = 'new draft'; release({ type: 'zoneid', data: [{ id, name: 'Zone' }] }); await pending; await flushPromises(); expect(c.form.name).toBe('new draft'); expect(c.fields[1].opts).toEqual([{ id, name: 'Zone' }])
})
