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
import { h, reactive } from 'vue'
import ListView from '@/components/view/ListView'

jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))

function mountTable ({ order = ['a', 'b', 'c', 'd', 'e'], disabled = [], path = '/vm' } = {}) {
  const items = ['a', 'b', 'c', 'd', 'e'].map(id => ({ id, displayname: id.toUpperCase() }))
  const route = reactive({ name: path.slice(1), path, fullPath: path, meta: { name: path.slice(1) } })
  const linkClick = jest.fn()
  const buttonClick = jest.fn()
  const wrapper = shallowMount(ListView, {
    props: {
      items,
      columns: ['displayname', 'state', 'qemuagentversion', 'templatetype', 'arch', 'resources'].map(key => ({ key, dataIndex: key })),
      actions: [{ api: 'startVirtualMachine', dataView: true, groupAction: true }],
      selectionDisabled: record => disabled.includes(record.id)
    },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: {
        $route: route,
        $store: { getters: { apis: { startVirtualMachine: {} }, userInfo: { roletype: 'Admin' } } },
        $t: key => key
      },
      stubs: {
        'a-table': {
          props: ['customRow', 'rowSelection', 'dataSource'],
          render () {
            const rows = order.map(key => {
              const record = this.dataSource.find(item => item.id === key)
              const selection = this.rowSelection
              const checked = selection.selectedRowKeys.includes(key)
              return h('tr', { ...this.customRow(record), class: 'ant-table-row', 'data-row-key': key }, [
                h('td', [
                  h('span', { class: 'plain' }, record.displayname),
                  h('a', { onClick: linkClick, href: '#link' }, [h('span', { class: 'link-content' }, 'link')]),
                  h('button', { onClick: buttonClick }, [h('span', { class: 'button-content' }, 'button')]),
                  h('label', [
                    h('input', {
                      type: 'checkbox',
                      checked,
                      disabled: selection.getCheckboxProps(record).disabled,
                      onChange: event => selection.onChange(event.target.checked
                        ? [...selection.selectedRowKeys, key]
                        : selection.selectedRowKeys.filter(selected => selected !== key))
                    }),
                    h('span', { class: 'checkbox-label' })
                  ])
                ])
              ])
            })
            return h('table', [h('tbody', rows)])
          }
        }
      }
    }
  })
  return {
    wrapper,
    route,
    linkClick,
    buttonClick,
    row: key => wrapper.get('tr[data-row-key="' + key + '"]'),
    click: (key, modifiers = {}) => wrapper.get('tr[data-row-key="' + key + '"] .plain').trigger('click', modifiers)
  }
}

test('plain left click selects the row and updates the shared selection event', async () => {
  const { wrapper, click, row } = mountTable()
  await click('a')
  await click('c', { ctrlKey: true })
  await click('b')
  expect(wrapper.vm.selectedRowKeys).toEqual(['b'])
  expect(wrapper.emitted('selection-change').at(-1)).toEqual([['b']])
  expect(row('b').get('input').element.checked).toBe(true)
  expect(row('a').get('input').element.checked).toBe(false)
  wrapper.unmount()
})

test.each(['ctrlKey', 'metaKey'])('%s toggles just the clicked row', async modifier => {
  const { wrapper, click } = mountTable()
  await click('a')
  await click('d', { [modifier]: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['a', 'd'])
  await click('a', { [modifier]: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['d'])
  wrapper.unmount()
})

test('Shift uses the displayed sort order and keeps the same anchor for successive ranges', async () => {
  const { wrapper, click } = mountTable({ order: ['d', 'b', 'a', 'e', 'c'], disabled: ['a'] })
  await click('b')
  await click('c', { shiftKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['b', 'e', 'c'])
  await click('d', { shiftKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['d', 'b'])
  wrapper.unmount()
})

test('Command plus Shift adds the displayed range to the selection', async () => {
  const { wrapper, click } = mountTable()
  await click('a')
  await click('e', { metaKey: true })
  await click('c', { metaKey: true, shiftKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['a', 'e', 'c', 'd'])
  wrapper.unmount()
})

test('a page transition clears the prior range anchor', async () => {
  const { wrapper, click, route } = mountTable()
  await click('a')
  route.fullPath = '/vm?page=2'
  await wrapper.vm.$nextTick()
  expect(wrapper.vm.selectedRowKeys).toEqual([])
  await click('e', { shiftKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['e'])
  wrapper.unmount()
})

test('links, buttons, checkbox labels, disabled rows and non-left clicks preserve their own behavior', async () => {
  const { wrapper, row, click, linkClick, buttonClick } = mountTable({ disabled: ['d'] })
  await click('a')
  await row('b').get('.link-content').trigger('click', { ctrlKey: true })
  await row('b').get('.button-content').trigger('click', { shiftKey: true })
  expect(linkClick).toHaveBeenCalledTimes(1)
  expect(buttonClick).toHaveBeenCalledTimes(1)
  await click('c', { button: 1 })
  await click('d')
  expect(wrapper.vm.selectedRowKeys).toEqual(['a'])
  await row('b').get('.checkbox-label').trigger('click')
  expect(wrapper.vm.selectedRowKeys).toEqual(['a', 'b'])
  await click('c', { shiftKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['b', 'c'])
  wrapper.unmount()
})

test('right-click anchors the next Shift range in the same selection state', async () => {
  const { wrapper, click, row } = mountTable()
  await click('a')
  await row('c').get('.plain').trigger('contextmenu')
  await wrapper.vm.$nextTick()
  expect(wrapper.vm.selectedRowKeys).toEqual(['c'])
  expect(wrapper.vm.contextQuickViewRecord.id).toBe('c')
  wrapper.vm.closeContextQuickView()
  await click('e', { shiftKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['c', 'd', 'e'])
  wrapper.unmount()
})

test('a list without group operations selects only the last row', async () => {
  const { wrapper, click } = mountTable({ path: '/vmsnapshot' })
  await click('a')
  await click('b', { ctrlKey: true })
  expect(wrapper.vm.selectedRowKeys).toEqual(['b'])
  wrapper.unmount()
})

test('the VM name receives room from compact secondary columns', () => {
  const { wrapper } = mountTable()
  const widths = Object.fromEntries(wrapper.vm.displayColumns.map(column => [column.key, column.width]))
  expect(widths).toEqual({ displayname: 252, state: 116, qemuagentversion: 92, templatetype: 90, arch: 84, resources: 176 })
  wrapper.unmount()
})
