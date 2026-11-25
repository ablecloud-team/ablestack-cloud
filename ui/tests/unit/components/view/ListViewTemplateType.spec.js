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
import ListView from '@/components/view/ListView'
import { getAPI } from '@/api'
jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))
const id = '11111111-1111-4111-8111-111111111111'
function cell (path, type, key = 'templatetype', name = 'Image', params = {}, extraRecord = {}) {
  const record = { id, name, templatetype: type, account: 'admin', domainid: id, zoneid: id, zonename: 'Zone', ...extraRecord }
  const push = jest.fn()
  const wrapper = shallowMount(ListView, {
    props: { columns: [{ key, dataIndex: key }], items: [record] },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: { $route: { name: path.slice(1), path, params, meta: { name: path.slice(1) } }, $router: { push, resolve: () => ({ matched: [{}] }) }, $store: { getters: { apis: {}, userInfo: { roletype: 'Admin' } } }, $t: k => k, $showIcon: () => false },
      stubs: {
        'a-table': { render () { return h('div', { class: 'test-template-cell' }, this.$slots.bodyCell({ column: { key }, text: record[key], record })) } },
        KeyboardTooltip: { props: ['title'], render () { return h('span', { 'data-tooltip': this.title }, this.$slots.default()) } },
        'a-tooltip': { props: ['title'], render () { return h('span', { 'data-tooltip': this.title }, this.$slots.default()) } },
        'a-tag': { render () { return h('span', this.$slots.default()) } },
        'router-link': { props: ['to'], render () { return h('a', { href: this.to.path }, this.$slots.default()) } }
      }
    }
  })
  return { wrapper, push }
}
test.each(['/template', '/vm', '/vnfapp'].flatMap(path => ['USER', 'SYSTEM', 'VNF', 'SHAREDFS', 'BUILTIN'].map(type => [path, type])))('%s %s is visible but cannot navigate or query an enum as a resource', async (path, type) => {
  const { wrapper, push } = cell(path, type)
  expect(wrapper.text()).toBe(type)
  expect(wrapper.find('.test-template-cell').find('a,button,[tabindex]').exists()).toBe(false)
  await wrapper.find('span').trigger('click')
  expect(push).not.toHaveBeenCalled()
  expect(getAPI).not.toHaveBeenCalled()
  wrapper.unmount()
})
test.each([['name', '/template/' + id], ['account', '/account'], ['zonename', '/zone/' + id]])('real %s references keep a link', (key, href) => {
  const { wrapper } = cell('/template', 'USER', key)
  expect(wrapper.find('a').attributes('href')).toContain(href)
  wrapper.unmount()
})

test('template names remain complete link text and full hover content', () => {
  const name = 'qa-t34-primary-information-'.repeat(7)
  const { wrapper } = cell('/template', 'USER', 'name', name)
  expect(wrapper.find('.template-name-link').text()).toBe(name); expect(wrapper.find('[data-tooltip]').attributes('data-tooltip')).toBe(name); expect(wrapper.find('a').attributes('href')).toContain(id); wrapper.unmount()
})
test('template table reserves primary name space and leaves other resources unchanged', () => {
  const ctx = { $route: { meta: { name: 'template' } }, resourcePath: '', columns: [{ key: 'name' }, { key: 'state', width: 140 }, { key: 'ostypename', width: 180 }], isOrderUpdatable: () => true }
  expect(ListView.computed.templateTableScroll.call(ctx)).toEqual({ x: 720 }); ctx.$route.meta.name = 'vm'; expect(ListView.computed.templateTableScroll.call(ctx)).toBeNull()
})

test('KISO names use the fixed icon slot and preserve the complete keyboard tooltip and scoped return link', () => {
  const name = '우분투-긴-카탈로그-이름-Long-name-'.repeat(5)
  const { wrapper } = cell('/kubernetesiso', 'USER', 'name', name)
  expect(wrapper.find('.template-name-icon').exists()).toBe(true)
  expect(wrapper.find('.template-name-link').text()).toBe(name)
  expect(wrapper.find('[data-tooltip]').attributes('data-tooltip')).toBe(name)
  expect(wrapper.find('a').attributes('href')).toContain('/kubernetesiso/' + id)
  expect(wrapper.find('.list-view-table-wrapper').attributes('role')).toBe('region')
  wrapper.unmount()
})
test.each(['desktop', 'mobile'])('KISO %s columns reserve the largest width for the name and preserve sort/selection metadata', device => {
  const sorter = jest.fn()
  const columns = [{ key: 'name', dataIndex: 'name', sorter }, { key: 'state', dataIndex: 'state' }, { key: 'minmemory', dataIndex: 'minmemory' }, { key: 'zonename', dataIndex: 'zonename' }, { key: 'order', dataIndex: 'order' }]
  const ctx = { columns, isOrderUpdatable: () => false, isKubernetesIsoMainList: true, $store: { getters: { device } } }
  const result = ListView.computed.displayColumns.call(ctx)
  expect(result.map(c => c.key)).toEqual(['name', 'state', 'minmemory', 'zonename'])
  expect(result[0].width).toBeGreaterThan(Math.max(...result.slice(1).map(c => c.width)))
  expect(result[0].sorter).toBe(sorter)
  expect(columns[0]).not.toHaveProperty('width')
  expect(ListView.computed.kubernetesIsoTableScroll.call({ ...ctx, displayColumns: result }).x).toBe(result.reduce((sum, c) => sum + c.width, 0))
  ctx.isKubernetesIsoMainList = false
  expect(ListView.computed.displayColumns.call(ctx)[0]).toBe(columns[0])
})

test('KISO column picker follows explicit visibility updates and returns focus on Escape', async () => {
  const input = { focus: jest.fn() }
  const trigger = { focus: jest.fn() }
  const ctx = { columnPickerVisible: false, $refs: { columnPickerContent: { querySelector: () => input }, columnPickerTrigger: { $el: trigger } }, $nextTick: fn => fn() }
  const original = global.requestAnimationFrame
  global.requestAnimationFrame = fn => fn()
  try {
    ListView.methods.columnPickerVisibility.call(ctx, true)
    expect(ctx.columnPickerVisible).toBe(true)
    expect(input.focus).toHaveBeenCalledTimes(1)
    ListView.methods.closeColumnPicker.call(ctx)
    expect(ctx.columnPickerVisible).toBe(false)
    expect(trigger.focus).toHaveBeenCalledTimes(1)
  } finally { global.requestAnimationFrame = original }
})

test('KISO main table rules never apply to the nested detail event table', () => {
  expect(ListView.computed.isKubernetesIsoMainList.call({ $route: { meta: { name: 'kubernetesiso' }, params: { id } }, resourcePath: '' })).toBe(false)
  expect(ListView.computed.isKubernetesIsoMainList.call({ $route: { meta: { name: 'kubernetesiso' }, params: {} }, resourcePath: '' })).toBe(true)
})

test('a Completed detail event uses the standard event Status component instead of a version badge', () => {
  const { wrapper } = cell('/kubernetesiso', 'USER', 'state', 'event', { id }, { state: 'Completed' })
  expect(wrapper.findComponent({ name: 'KubernetesIsoListCell' }).exists()).toBe(false)
  expect(wrapper.findComponent({ name: 'Status' }).props('text')).toBe('Completed')
  expect(wrapper.find('.list-view-table-wrapper').attributes('role')).toBeUndefined()
  wrapper.unmount()
})
test('the KISO main list still renders its Enabled version through the catalog status cell', () => {
  const { wrapper } = cell('/kubernetesiso', 'USER', 'state', 'version', {}, { state: 'Enabled' })
  expect(wrapper.findComponent({ name: 'KubernetesIsoListCell' }).props('field')).toBe('state')
  expect(wrapper.findComponent({ name: 'Status' }).exists()).toBe(false)
  wrapper.unmount()
})
