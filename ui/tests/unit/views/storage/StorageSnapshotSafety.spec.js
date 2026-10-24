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
import { shallowMount } from '@vue/test-utils'
import Safety from '@/components/view/StorageSnapshotSafety.vue'
const context = { source: { sourceavailable: false }, record: {}, target: {} }
function mount (props) {
  return shallowMount(Safety, { props, global: { mocks: { $t: key => key }, stubs: { 'a-spin': { template: '<div><slot/></div>' } } } })
}
test('a valid compact context has no empty spinner during submission', () => {
  const wrapper = mount({ compact: true, context: { ...context, source: {} }, loading: true })
  expect(wrapper.find('section').exists()).toBe(false)
})
test('a persisted pending lease replaces the source recovery hint with query guidance', () => {
  const wrapper = mount({ compact: true, context, pending: true })
  const alerts = wrapper.findAll('a-alert-stub')
  expect(alerts).toHaveLength(1)
  expect(alerts[0].attributes('message')).toBe('message.snapshot.dialog.pending')
  expect(wrapper.find('a-descriptions-stub').exists()).toBe(false)
})
test('a preflight error keeps the retry action available over the pending hint', () => {
  const wrapper = mount({ compact: true, context, pending: true, error: 'query failed' })
  expect(wrapper.findAll('a-alert-stub')).toHaveLength(1)
  expect(wrapper.find('a-alert-stub').attributes('message')).toBe('query failed')
})
