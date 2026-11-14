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

import { shallowMount } from '@vue/test-utils'
import { h } from 'vue'
import TemplateAvailability from '@/components/widgets/TemplateAvailability.vue'
const record = { id: 'image', zonename: 'Zone A', isready: false, status: 'Connection refused', downloaddetails: [{ downloadState: 'DOWNLOAD_ERROR' }] }
function mount (props = {}) { return shallowMount(TemplateAvailability, { props: { record, ...props }, global: { mocks: { $t: (key, params) => key + (params ? JSON.stringify(params) : '') }, stubs: { 'a-badge': { props: ['text', 'status'], render () { return h('span', { 'data-status': this.status }, this.text) } }, 'a-button': { render () { return h('button', this.$slots.default()) } } } } }) }
test('list status keeps a visible failure reason and no global Zone claim', () => {
  const wrapper = mount({ compact: true })
  expect(wrapper.text()).toContain('label.template.availability.failed'); expect(wrapper.text()).toContain('Connection refused')
  expect(wrapper.text()).toContain('label.template.availability.zone'); expect(wrapper.text()).not.toContain('label.template.availability.zones')
  wrapper.unmount()
})
test('detail reason navigation emits only an inspection event', async () => {
  const wrapper = mount({ inspectable: true })
  await wrapper.find('button').trigger('click'); expect(wrapper.emitted('show-zones')).toHaveLength(1)
  await wrapper.setProps({ record: { ...record, status: 'Download Complete', isready: true, downloaddetails: [] } })
  expect(wrapper.find('button').exists()).toBe(false); expect(wrapper.text()).not.toContain('Connection refused')
  wrapper.unmount()
})

test('single-line list status keeps complete cause and scope on hover without extra rows', () => {
  const wrapper = mount({ compact: true, singleLine: true }); expect(wrapper.text()).toContain('label.template.availability.failed'); expect(wrapper.text()).not.toContain('Connection refused'); expect(wrapper.attributes('title')).toContain('Connection refused'); expect(wrapper.attributes('title')).toContain('Zone A'); wrapper.unmount()
})
