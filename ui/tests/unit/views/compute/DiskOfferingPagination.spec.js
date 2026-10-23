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

import { mount } from '@vue/test-utils'
import { Pagination, Input } from 'ant-design-vue'
import Selection from '@/views/compute/wizard/DiskOfferingSelection.vue'
const items = Array.from({ length: 23 }, (_, i) => ({ id: 'disk-' + (i + 1), name: 'Offering ' + String(i + 1).padStart(2, '0'), displaytext: 'Description ' + (i + 1), disksize: i + 1, kvdoenable: false }))
const mountTable = props => mount(Selection, { props: { items, rowCount: 23, allItems: true, ...props }, global: { mocks: { $t: key => key } } })
const page = async (w, n) => { await w.get('.ant-pagination-item[title="' + n + '"]').trigger('click') }

test('server page uses API total and emits a fresh query instead of hiding later offerings', async () => {
  const w = mountTable({ allItems: false, items: items.slice(0, 10) })
  expect(w.findAll('.ant-pagination-item').map(x => x.attributes('title'))).toEqual(['1', '2', '3'])
  await page(w, 2)
  expect(w.emitted('handle-search-filter')).toEqual([[{ page: 2, pageSize: 10, keyword: null }]])
  await w.setProps({ items: items.slice(10, 20) })
  expect(w.findAll('tbody tr[data-row-key]')).toHaveLength(10)
  expect(w.text()).toContain('Offering 20')
  expect(w.text()).not.toContain('label.noselect')
  w.unmount()
})
test.each([{ isRootDiskOffering: false, isIsoSelected: false }, { isRootDiskOffering: true }, { isIsoSelected: true }])('complete collection reaches all 23 items and keeps ROOT/DATA choice rules (%j)', async props => {
  const w = mountTable(props)
  expect(w.findAll('tbody tr[data-row-key]')).toHaveLength(props.isRootDiskOffering || props.isIsoSelected ? 10 : 11)
  expect(w.text().includes('label.noselect')).toBe(!props.isRootDiskOffering && !props.isIsoSelected)
  await page(w, 2)
  expect(w.text()).toContain('Offering 11')
  expect(w.text()).toContain('Offering 20')
  expect(w.text()).not.toContain('Offering 01')
  await page(w, 3)
  expect(w.findAll('tbody tr[data-row-key]')).toHaveLength(3)
  expect(w.text()).toContain('Offering 23')
  expect(w.emitted('handle-search-filter')).toBeUndefined()
  w.unmount()
})
test('selection UUID and metadata survive independent page, size, and search changes', async () => {
  const w = mountTable()
  await page(w, 3)
  await w.get('tr[data-row-key="disk-23"]').trigger('click')
  await w.setProps({ value: 'disk-23' })
  expect(w.emitted('on-selected-disk-size').slice(-1)[0]).toEqual([items[22]])
  await w.findComponent(Pagination).vm.$emit('change', 3, 20); await w.vm.$nextTick()
  expect(w.findAll('tbody tr[data-row-key]')).toHaveLength(21)
  expect(w.get('.ant-pagination-item-active').attributes('title')).toBe('1')
  await w.findComponent(Input.Search).vm.$emit('search', 'Offering 01'); await w.vm.$nextTick()
  expect(w.text()).toContain('Offering 01')
  expect(w.text()).not.toContain('Offering 23')
  expect(w.emitted('select-disk-offering-item')).toEqual([['disk-23']])
  await w.findComponent(Input.Search).vm.$emit('search', ''); await w.vm.$nextTick()
  await page(w, 2)
  expect(w.get('tr[data-row-key="disk-23"] input[type="radio"]').element.checked).toBe(true)
  expect(w.emitted('select-disk-offering-item')).toEqual([['disk-23']])
  w.unmount()
})
test('search description, empty result, page clamp and scope reset preserve safe choices', async () => {
  const w = mountTable({ scopeKey: 'owner-A', value: 'disk-23' })
  await page(w, 3)
  await w.findComponent(Input.Search).vm.$emit('search', 'Description 23'); await w.vm.$nextTick()
  expect(w.text()).toContain('Offering 23')
  expect(w.findComponent(Pagination).props('total')).toBe(1)
  await w.findComponent(Input.Search).vm.$emit('search', 'missing'); await w.vm.$nextTick()
  expect(w.findComponent(Pagination).props('total')).toBe(0)
  expect(w.emitted('select-disk-offering-item')).toEqual([['disk-23']])
  await w.setProps({ scopeKey: 'owner-B', value: '', items: items.slice(0, 2) })
  expect(w.findComponent(Pagination).props('current')).toBe(1)
  expect(w.findComponent(Input.Search).props('value')).toBe('')
  expect(w.text()).not.toContain('Offering 23')
  w.unmount()
})
test('KVDO eligibility is counted before paging and removing a selection clears old disk metadata', async () => {
  const w = mountTable({ value: 'disk-23' })
  await w.setProps({ preFillContent: { kvdoenable: true } })
  expect(w.findComponent(Pagination).props('total')).toBe(0)
  expect(w.emitted('select-disk-offering-item').slice(-1)[0]).toEqual(['0'])
  expect(w.emitted('on-selected-disk-size').slice(-1)[0]).toEqual([{}])
  await w.setProps({ value: '', preFillContent: { kvdoenable: false } })
  await w.get('tr[data-row-key="disk-1"]').trigger('click')
  await w.get('tr[data-row-key="0"]').trigger('click')
  expect(w.emitted('on-selected-disk-size').slice(-1)[0]).toEqual([{}])
  w.unmount()
})
test('loading and failed refresh retain rows, block a new selection and expose retry', async () => {
  const w = mountTable({ value: 'disk-1' })
  const initialEvents = w.emitted('select-disk-offering-item').length
  await w.setProps({ loading: true })
  await w.get('tr[data-row-key="disk-2"]').trigger('click')
  expect(w.emitted('select-disk-offering-item')).toHaveLength(initialEvents)
  await w.setProps({ loading: false, error: 'Lookup failed' })
  expect(w.text()).toContain('Offering 01')
  expect(w.text()).toContain('Lookup failed')
  await w.get('tr[data-row-key="disk-2"]').trigger('click')
  expect(w.emitted('select-disk-offering-item')).toHaveLength(initialEvents)
  await w.get('button').trigger('click')
  expect(w.emitted('retry')).toHaveLength(1)
  w.unmount()
})
