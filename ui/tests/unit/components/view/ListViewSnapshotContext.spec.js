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
import AutogenView from '@/views/AutogenView'
import compute from '@/config/section/compute'
import ko from '@/../public/locales/ko_KR.json'
jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))

test.each([[], ['a']])('checked IDs %j do not change a single right clicked B target', (...checked) => {
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

describe('Shared selection context menu', () => {
  const actions = compute.children.find(section => section.name === 'vmsnapshot').actions
  const deletion = actions.find(action => action.api === 'deleteVMSnapshot')
  function menu (name, selection) {
    const rows = ['a', 'b', 'c'].map(id => ({ id, displayname: id.toUpperCase(), virtualmachineid: 'vm-' + id, state: 'Ready' }))
    const context = {
      $route: { name },
      items: rows,
      actions,
      selectedRowKeys: selection,
      selectionList: rows.filter(row => selection.includes(row.id)),
      $store: { getters: { apis: Object.fromEntries(actions.map(action => [action.api, {}])) } },
      $t: (key, args) => key === 'label.items.more' ? `외 ${args[0]}개 항목` : key,
      quickViewEnabled: () => true,
      generateRowKeyValue: row => row.id
    }
    context.selectedItems = context.selectionList
    context.getFirstSelectedItem = () => ListView.methods.getFirstSelectedItem.call(context)
    context.closeContextQuickView = () => ListView.methods.closeContextQuickView.call(context)
    Object.defineProperty(context, 'contextMenuActions', { get: () => ListView.computed.contextMenuActions.call(context) })
    return context
  }

  test.each(['vm', 'vmsnapshot'])('%s uses the selected group even when an unselected row is right clicked', name => {
    const context = menu(name, ['a', 'c'])
    const row = document.createElement('tr')
    row.classList.add('ant-table-row'); row.setAttribute('data-row-key', 'b')
    ListView.methods.handleGlobalContextMenu.call(context, { target: row, clientX: 20, clientY: 40, preventDefault: jest.fn(), stopPropagation: jest.fn() })
    expect(context.contextQuickViewRecord.id).toBe('a')
    expect(ListView.computed.contextMenuTitle.call(context)).toBe('A 외 1개 항목')
    expect(context.contextMenuActions.map(action => action.api)).toEqual(['deleteVMSnapshot'])

    const parent = { selectedItems: context.selectedItems }
    parent.execAction = (action, group) => AutogenView.methods.execAction.call(parent, action, group)
    ListView.methods.handleContextAction.call({ ...context, $parent: parent }, { ...deletion, resource: context.contextQuickViewRecord })
    expect(parent.currentAction.invokedAsGroupAction).toBe(true)
    expect(parent.currentAction.snapshotTargets.map(row => row.id)).toEqual(['a', 'c'])
    context.selectedItems[0].id = 'replacement'
    context.selectedItems.pop()
    expect(parent.currentAction.snapshotTargets.map(row => row.id)).toEqual(['a', 'c'])
    expect(Object.isFrozen(parent.currentAction.snapshotTargets[0])).toBe(true)
  })

  test('multi-selection on the table background uses the same group menu', () => {
    const context = menu('vmsnapshot', ['a', 'c'])
    ListView.methods.handleGlobalContextMenu.call(context, { target: document.createElement('div'), clientX: 20, clientY: 40, preventDefault: jest.fn(), stopPropagation: jest.fn() })
    expect(context.contextQuickViewVisible).toBe(true)
    expect(context.contextQuickViewRecord.id).toBe('a')
  })

  test('an unavailable delete API never exposes the bulk action', () => {
    const context = menu('vmsnapshot', ['a', 'c'])
    delete context.$store.getters.apis.deleteVMSnapshot
    expect(context.contextMenuActions).toEqual([])
  })

  test('a transitional first row cannot hide the batch eligibility review', () => {
    const context = menu('vmsnapshot', ['a', 'c'])
    context.contextQuickViewRecord = { ...context.selectedItems[0], state: 'Creating' }
    expect(context.contextMenuActions.map(action => action.api)).toEqual(['deleteVMSnapshot'])
    expect(deletion.groupShow(context.selectedItems)).toBe(true)
  })

  test.each(['vm', 'vmsnapshot'])('rendered %s menu receives the actual checked rows for group eligibility', async name => {
    const rows = ['a', 'b', 'c'].map(id => ({ id, displayname: id.toUpperCase(), state: 'Ready' }))
    const wrapper = shallowMount(ListView, {
      props: { columns: [], items: rows, actions },
      global: {
        provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
        mocks: {
          $route: { name, path: '/' + name, meta: {} },
          $store: { getters: { apis: Object.fromEntries(actions.map(action => [action.api, {}])), userInfo: { roletype: 'Admin' } } },
          $t: (key, args) => key === 'label.items.more' ? `외 ${args[0]}개 항목` : key
        }
      }
    })
    wrapper.vm.onSelectChange(['a', 'c'], [rows[0], rows[2]])
    await wrapper.vm.$nextTick()
    const element = document.createElement('tr')
    element.classList.add('ant-table-row'); element.setAttribute('data-row-key', 'b')
    wrapper.vm.handleGlobalContextMenu({ target: element, clientX: 20, clientY: 40, preventDefault: jest.fn(), stopPropagation: jest.fn() })
    await wrapper.vm.$nextTick()
    const menu = wrapper.findComponent({ name: 'ResourceContextMenu' })
    expect(menu.exists()).toBe(true)
    expect(menu.props('selectedItems')).toEqual([rows[0], rows[2]])
    expect(menu.props('titleOverride')).toBe('A 외 1개 항목')
    expect(menu.props('actions').map(action => action.api)).toEqual(['deleteVMSnapshot'])
    wrapper.unmount()
  })
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
