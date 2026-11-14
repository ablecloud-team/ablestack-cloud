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
import { h, nextTick } from 'vue'
import VpcDialog from '@/components/network/VpcDialog'
const shell = { name: 'MoldDialog', props: ['size', 'visible', 'formActions'], render () { return h('section', {}, [this.$slots.title?.(), this.$slots.default?.(), this.$slots.footer?.()]) } }
function open (expanded) {
  const outside = jest.fn(); const select = jest.fn()
  const w = shallowMount(VpcDialog, { attachTo: document.body, attrs: { size: 'md', visible: true, formActions: true, onKeydown: outside }, slots: { title: '<strong>Title</strong>', footer: '<button disabled>Save</button>', default: () => h('div', { class: expanded ? 'ant-select ant-select-open' : 'ant-select' }, [h('input', { value: 'preserved draft', onKeydown: select })]) }, global: { stubs: { MoldDialog: shell } } })
  return { w, outside, select }
}
afterEach(() => { document.body.innerHTML = '' })
test('Select Escape reaches the field handler while its dialog remains open', async () => {
  const { w, outside, select } = open(true); await w.find('input').trigger('keydown', { key: 'Escape' }); expect(select).toHaveBeenCalledTimes(1); expect(outside).not.toHaveBeenCalled(); expect(w.find('input').element.value).toBe('preserved draft'); w.unmount()
})
test('Escape with no open popup retains the existing modal close path', async () => {
  const { w, outside } = open(false); await w.find('input').trigger('keydown', { key: 'Escape' }); expect(outside).toHaveBeenCalledTimes(1); w.unmount()
})
test('a Select that closes synchronously still consumes Escape before its dialog', async () => {
  const { w, outside, select } = open(true); select.mockImplementation(event => event.target.parentNode.classList.remove('ant-select-open')); await w.find('input').trigger('keydown', { key: 'Escape' }); expect(outside).not.toHaveBeenCalled(); expect(w.find('input').element.value).toBe('preserved draft'); w.unmount()
})
test('the adapter forwards the existing frame, title, footer and busy controls', () => {
  const { w } = open(false); const frame = w.findComponent({ name: 'MoldDialog' }); expect(frame.props()).toMatchObject({ size: 'md', visible: true, formActions: true }); expect(w.find('strong').text()).toBe('Title'); expect(w.find('button').element.disabled).toBe(true); w.unmount()
})
test('unmount restores the still-connected trigger in the same route', async () => {
  const trigger = document.createElement('button'); document.body.appendChild(trigger); trigger.focus()
  const { w } = open(false); w.find('input').element.focus(); w.unmount(); await nextTick(); expect(document.activeElement).toBe(trigger); trigger.remove()
})
test('unmount after navigation does not focus a control from the previous route', async () => {
  const previous = window.location.hash; window.location.hash = '#/vpc/old'
  const trigger = document.createElement('button'); const current = document.createElement('button'); document.body.append(trigger, current); trigger.focus()
  const { w } = open(false); window.location.hash = '#/vpc/new'; current.focus(); w.unmount(); await nextTick(); expect(document.activeElement).toBe(current); window.location.hash = previous; trigger.remove(); current.remove()
})
