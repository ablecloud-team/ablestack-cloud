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
import { shallowMount } from '@vue/test-utils'
import { h } from 'vue'
import ListView from '@/components/view/ListView'
import ko from '@/../public/locales/ko_KR.json'
jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))

test.each([['a'], ['a', 'c']])('checked IDs %j do not change the right clicked B target', (...checked) => {
  const rows = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
  const element = document.createElement('tr')
  element.classList.add('ant-table-row'); element.setAttribute('data-row-key', 'b')
  const vm = { $route: { name: 'vmsnapshot' }, items: rows, actions: [{}], selectedRowKeys: checked.flat(), quickViewEnabled: () => true, generateRowKeyValue: row => row.id, contextMenuActions: [{}], closeContextQuickView: jest.fn() }
  ListView.methods.handleGlobalContextMenu.call(vm, { target: element, clientX: 20, clientY: 40, preventDefault: jest.fn(), stopPropagation: jest.fn() })
  expect(vm.contextQuickViewRecord.id).toBe('b')
  expect(Object.isFrozen(vm.contextQuickViewRecord)).toBe(true)
  rows[1].id = 'replaced'
  expect(vm.contextQuickViewRecord.id).toBe('b')
  const parent = { execAction: jest.fn() }
  ListView.methods.handleContextAction.call({ ...vm, $parent: parent }, { groupAction: true, resource: vm.contextQuickViewRecord })
  expect(parent.execAction.mock.calls[0][1]).toBe(false)
})

function currentCell (current, name = 'vmsnapshot') {
  const record = { id: 'snapshot', current }
  return shallowMount(ListView, {
    props: { columns: [{ key: 'current', dataIndex: 'current' }], items: [record] },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: { $route: { name, path: '/' + name, meta: {} }, $store: { getters: { apis: {}, userInfo: { roletype: 'Admin' } } }, $t: key => ko[key] || key },
      stubs: {
        'a-table': { render () { return h('div', this.$slots.bodyCell({ column: { key: 'current' }, text: current, record })) } }
      }
    }
  })
}

describe('Snapshot current reference cell', () => {
  it.each([[true, '현재 기준점'], [false, '—']])('renders current=%s without an explanatory tooltip', (current, label) => {
    const wrapper = currentCell(current)
    expect(wrapper.text()).toBe(label)
    expect(wrapper.find('a-tooltip-stub').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([true, false])('preserves the status cell for other resources with current=%s', current => {
    const wrapper = currentCell(current, 'snapshot')
    expect(wrapper.findComponent({ name: 'Status' }).props('text')).toBe(String(current))
    wrapper.unmount()
  })
})
