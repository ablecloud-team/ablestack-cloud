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

import { applyProjectSearch, projectDetailLocation, projectListLocation, projectListParams, projectMemberFilters, projectSearchError, projectSearchQuery } from '@/utils/projectSearch'
import AccountsTab from '@/views/project/AccountsTab.vue'

const root = { userInfo: { roletype: 'Admin', id: 'admin' }, apis: { listDomains: {}, listAccounts: {}, listUsers: {} } }
const list = {
  meta: { name: 'project' },
  params: {},
  query: {
    q: 'test', displaytext: 'exact description', 'tags[0].key': 'key', 'tags[0].value': 'value', sortkey: 'name', sortorder: 'desc', page: '2', pagesize: '1'
  }
}
const details = { meta: { name: 'project' }, params: { id: 'p1' }, query: { tab: 'accounts' } }

describe('project filters and return context', () => {
  it('keeps partial keyword separate from exact fields and omits UI-only sort parameters', () => {
    const params = projectListParams(list.query, { showIcon: true, listall: true }, root)
    expect(params).toMatchObject({ keyword: 'test', displaytext: 'exact description', page: '2', pagesize: '1', showIcon: true })
    expect(params.q).toBeUndefined()
    expect(params.sortkey).toBeUndefined()
    expect(params.sortorder).toBeUndefined()
  })

  it('requires an allowed domain for account and username lookup', () => {
    const raw = { domainid: 'd', account: 'a', username: 'u', state: 'Active' }
    expect(projectSearchQuery(raw, root)).toEqual(raw)
    expect(projectSearchQuery(raw, { ...root, apis: { listDomains: {}, listAccounts: {} } })).toEqual({ domainid: 'd', account: 'a', state: 'Active' })
    expect(projectSearchQuery(raw, { ...root, userInfo: { roletype: 'User' } })).toEqual({ state: 'Active' })
    expect(projectSearchQuery({ account: 'a', username: 'u' }, root)).toEqual({})
  })

  it('returns to page one when a filter changes and keeps page size and sort', () => {
    expect(applyProjectSearch(list.query, { state: 'Suspended', q: 'test' }, root)).toEqual({
      state: 'Suspended', q: 'test', page: '1', pagesize: '1', sortkey: 'name', sortorder: 'desc'
    })
  })

  it('restores only the search state saved for the same project, view and session', () => {
    const history = projectDetailLocation(list, { id: 'p1' }, root, 'test-token').state
    expect(JSON.stringify(history)).not.toContain('test-token')
    expect(projectListLocation(details, history, root, 'test-token').query).toEqual(list.query)
    expect(projectListLocation(details, history, root, 'other-token').query).toEqual({})
    expect(projectListLocation(details, history, { ...root, project: { id: 'another-project' } }, 'test-token').query).toEqual({})
    expect(projectListLocation({ ...details, params: { id: 'p2' } }, history, root, 'test-token').query).toEqual({})
  })

  it('validates UUID and tag pairs before changing the list', () => {
    expect(projectSearchError({ id: 'not-a-uuid' }, () => false)).toBe('message.project.search.uuid.invalid')
    expect(projectSearchError({ 'tags[0].key': 'only-key' }, () => true)).toBe('message.project.search.tag.pair')
    expect(projectSearchError({ 'tags[0].key': 'key', 'tags[0].value': 'value' }, () => true)).toBe('')
  })

  it('resets member paging and drops the previous results for a changed filter', () => {
    const vm = {
      membershipLocked: false,
      memberFilters: { keyword: 'old' },
      members: [{}],
      itemCount: 5,
      page: 2,
      fetchProjectAccounts: jest.fn()
    }
    AccountsTab.methods.applyMemberFilters.call(vm, { keyword: ' new ', projectroleid: 'r1', irrelevant: 'x' })
    expect(vm.memberFilters).toEqual({ keyword: 'new', projectroleid: 'r1' })
    expect(vm.members).toEqual([])
    expect(vm.page).toBe(1)
    expect(vm.fetchProjectAccounts).toHaveBeenCalledTimes(1)
    expect(projectMemberFilters({ account: ' a ', username: 'u', role: 'Admin', keyword: ' ' })).toEqual({ account: 'a', username: 'u', role: 'Admin' })
  })
})
