// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
import { shallowMount, flushPromises } from '@vue/test-utils'
import MoldDialog from '@/components/view/MoldDialog'
const modal = { name: 'AModal', props: ['width', 'maskClosable', 'wrapProps', 'wrapClassName', 'maskStyle'], template: '<section class="ant-modal"><slot name="title" /><main><slot /></main><footer><slot name="footer" /></footer></section>' }
function mount (props = {}, extra = {}) { return shallowMount(MoldDialog, { props, ...extra, global: { mocks: { $t: key => key }, stubs: { 'a-modal': modal } }, slots: { default: '<input />', footer: '<button>Submit</button>' } }) }
test.each([['sm', 480], ['md', 720], ['lg', 960], ['xl', 1120]])('shared %s preset exposes one body and an independent footer at width %s', (size, width) => {
  const wrapper = mount({ size }); expect(wrapper.findComponent(modal).props('width')).toBe(width); expect(wrapper.classes()).toContain('mold-dialog-standard'); expect(wrapper.find('main input').exists()).toBe(true); expect(wrapper.find('footer button').text()).toBe('Submit'); expect(wrapper.findComponent(modal).props('maskClosable')).toBe(false); expect(wrapper.findComponent(modal).props('wrapClassName')).toBe('mold-dialog-standard-wrap'); expect(wrapper.findComponent(modal).props('maskStyle')).toEqual({ width: '100vw' }); expect(wrapper.findComponent(modal).props('wrapProps')).toEqual({ 'aria-modal': true }); wrapper.unmount()
})
test('legacy dialog width and custom width remain unchanged without a preset', () => {
  const wrapper = mount(); expect(wrapper.findComponent(modal).props('width')).toBe(760); expect(wrapper.classes()).not.toContain('mold-dialog-standard'); wrapper.unmount()
  const custom = mount({ width: 600 }); expect(custom.findComponent(modal).props('width')).toBe(600); custom.unmount()
})
test('shared cancel event remains the closing path', () => {
  const wrapper = mount(); wrapper.findComponent(modal).vm.$emit('cancel'); expect(wrapper.emitted('cancel')).toHaveLength(1); wrapper.unmount()
})

test('snapshot focus guard keeps removed-input focus inside its own modal', async () => {
  const wrapper = mount({ size: 'md', focusGuard: true }, { attachTo: document.body }); await flushPromises()
  const owner = wrapper.find('.ant-modal').element; jest.spyOn(owner, 'getClientRects').mockReturnValue([{ width: 100, height: 100 }])
  const input = wrapper.find('input').element; input.focus(); input.remove(); await flushPromises()
  expect(document.activeElement).toBe(wrapper.find('.mold-dialog-focus-content').element); wrapper.unmount()
})
test('snapshot focus guard never steals focus from another live control or portal', async () => {
  const wrapper = mount({ size: 'md', focusGuard: true }, { attachTo: document.body }); await flushPromises()
  jest.spyOn(wrapper.find('.ant-modal').element, 'getClientRects').mockReturnValue([{ width: 100, height: 100 }])
  const control = document.createElement('button'); document.body.appendChild(control); control.focus()
  wrapper.find('footer button').element.disabled = true; await flushPromises(); expect(document.activeElement).toBe(control)
  wrapper.unmount(); control.remove()
})
