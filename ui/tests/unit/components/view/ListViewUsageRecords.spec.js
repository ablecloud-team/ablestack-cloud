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

jest.mock('@/api', () => ({ getAPI: jest.fn().mockResolvedValue({}) }))

function usageCell (key, text, fields = {}) {
  const column = { key }
  const record = { id: 1, ...fields }
  const formatDate = jest.fn(() => '2026-11-24 00:00:00')
  const wrapper = shallowMount(ListView, {
    props: { columns: [column], items: [record] },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: {
        $route: { name: 'usage', path: '/usage', meta: { name: 'usage' } },
        $store: { getters: { apis: {}, userInfo: { roletype: 'Admin' } } },
        $t: key => key,
        $toLocaleDate: formatDate
      },
      stubs: {
        'a-table': { render () { return h('div', this.$slots.bodyCell({ column, text, record })) } },
        TableOverflowText: {
          props: ['text', 'location'],
          render () {
            return h(this.location ? 'a' : 'span', { href: this.location?.path }, this.$slots.default ? this.$slots.default() : String(this.text))
          }
        }
      }
    }
  })
  return { wrapper, formatDate }
}

test.each(['startdate', 'enddate'])('renders the formatted usage %s once', field => {
  const { wrapper, formatDate } = usageCell(field, "2026-11-24'T'00:00:00+0000")
  expect(wrapper.text()).toBe('2026-11-24 00:00:00')
  expect(formatDate).toHaveBeenCalledTimes(1)
  expect(formatDate).toHaveBeenCalledWith('2026-11-24 00:00:00+0000')
  wrapper.unmount()
})

test.each([[0, '0'], [undefined, ''], [null, '']])('preserves raw usage %s', (input, expected) => {
  const { wrapper } = usageCell('rawusage', input)
  expect(wrapper.text()).toBe(expected)
  wrapper.unmount()
})

test('keeps an account link and its full display text', () => {
  const { wrapper } = usageCell('account', 'account-with-a-long-name', { accountid: 'account-id' })
  expect(wrapper.get('a').attributes('href')).toBe('/account/account-id')
  expect(wrapper.get('a').text()).toBe('account-with-a-long-name')
  wrapper.unmount()
})
