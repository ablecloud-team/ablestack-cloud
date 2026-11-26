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
import { DetailTabPagination, DetailTabTable } from '@/components/view/detailTabList'

const table = {
  name: 'ATable',
  props: ['pagination'],
  render () { return null }
}

const options = enabled => ({
  provide: { resourceViewportEnabled: () => enabled },
  mocks: { $t: value => value, $route: { path: '/test/record', query: {} } }
})

test('a page size change invokes one handler and uses the tab’s existing size reset', () => {
  const onChange = jest.fn()
  const onShowSizeChange = jest.fn()
  const wrapper = mount(DetailTabPagination, {
    attrs: { total: 50, 'page-size': 10, onChange, onShowSizeChange },
    global: options(true)
  })
  const pager = wrapper.getComponent({ name: 'APagination' })
  pager.vm.$emit('showSizeChange', 1, 20)
  pager.vm.$emit('change', 1, 20)
  expect(onChange).not.toHaveBeenCalled()
  expect(onShowSizeChange).toHaveBeenCalledTimes(1)
  expect(onShowSizeChange).toHaveBeenLastCalledWith(1, 20)
  pager.vm.$emit('change', 2, 10)
  expect(onChange).toHaveBeenCalledTimes(1)
  expect(onChange).toHaveBeenCalledWith(2, 10)
  wrapper.unmount()
})

test('an empty standalone paginator renders disabled without hiding itself', () => {
  const wrapper = mount(DetailTabPagination, {
    attrs: { total: 0, hideOnSinglePage: true },
    global: options(true)
  })
  expect(wrapper.getComponent({ name: 'APagination' }).props()).toMatchObject({
    total: 0,
    disabled: true,
    hideOnSinglePage: false
  })
  wrapper.unmount()
})

test('a native table keeps its paging and supplies a real zero-count fallback', () => {
  const wrapper = mount(DetailTabTable, {
    props: { table, pagination: { pageSize: 20, hideOnSinglePage: true } },
    attrs: { dataSource: [] },
    global: options(true)
  })
  expect(wrapper.classes()).toContain('detail-tab-table')
  expect(wrapper.getComponent({ name: 'ATable' }).props('pagination')).toMatchObject({
    pageSize: 20,
    hideOnSinglePage: false,
    position: ['bottomRight']
  })
  expect(wrapper.getComponent({ name: 'APagination' }).props()).toMatchObject({ total: 0, pageSize: 20 })
  wrapper.unmount()
})

test('table use outside the detail viewport preserves the original pager options', () => {
  const pagination = { pageSize: 20, hideOnSinglePage: true }
  const wrapper = mount(DetailTabTable, { props: { table, pagination }, global: options(false) })
  expect(wrapper.getComponent({ name: 'ATable' }).props('pagination')).toEqual(pagination)
  expect(wrapper.findComponent({ name: 'APagination' }).exists()).toBe(false)
  wrapper.unmount()
})
