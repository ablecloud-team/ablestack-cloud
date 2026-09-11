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

function accountCell (record, path = '/vm', roletype = 'Admin') {
  return shallowMount(ListView, {
    props: { columns: [{ key: 'account', dataIndex: 'account' }], items: [record] },
    global: {
      provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
      mocks: { $route: { path, meta: {} }, $store: { getters: { apis: {}, userInfo: { roletype } } }, $t: key => key },
      stubs: {
        'a-table': { render () { return h('div', this.$slots.bodyCell({ column: { key: 'account' }, text: record.account, record })) } },
        'router-link': { props: ['to'], template: '<a :data-target="JSON.stringify(to)"><slot /></a>' }
      }
    }
  })
}

describe('Account column navigation', () => {
  it('links directly by account ID', () => {
    const wrapper = accountCell({ account: 'Alice', accountid: 'a1' })
    expect(JSON.parse(wrapper.find('a').attributes('data-target'))).toEqual({ path: '/account/a1' })
    wrapper.unmount()
  })
  it('uses a name/domain query for administrator fallback', () => {
    const wrapper = accountCell({ account: 'Alice', domainid: 'd1' })
    expect(JSON.parse(wrapper.find('a').attributes('data-target')).query).toEqual({ name: 'Alice', domainid: 'd1', dataView: true })
    wrapper.unmount()
  })
  it('shows plain text for a regular user without an account ID', () => {
    const wrapper = accountCell({ account: 'Alice' }, '/vm', 'User')
    expect(wrapper.text()).toContain('Alice')
    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each(['/vm', '/quotasummary'])('retains a project account label on %s', path => {
    const wrapper = accountCell({ account: 'PrjAcct-1', accountid: 'p1', projectname: 'Europa project' }, path)
    expect(wrapper.text()).toContain('Europa project (label.project)')
    expect(wrapper.find('a').exists()).toBe(path === '/quotasummary')
    if (path === '/quotasummary') expect(JSON.parse(wrapper.find('a').attributes('data-target'))).toEqual({ path: '/quotasummary/p1' })
    wrapper.unmount()
  })
})
