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

import { eligibleProjectCandidates, isProjectAdministrator, memberOperation, projectMemberActions } from '@/utils/projectMembership'
import AccountsTab from '@/views/project/AccountsTab.vue'

const admin = { roletype: 'Admin' }
const project = { id: 'p', domainid: 'domain', owner: [{ account: 'shared', userid: 'owner-user' }] }
const userApis = {
  deleteUserFromProject: {},
  updateProject: { params: ['account', 'userid', 'swapowner', 'roletype'].map(name => ({ name })) }
}

describe('project membership controls', () => {
  it('does not grant a sibling user the user-scoped administrator role', () => {
    const user = { domainid: 'domain', account: 'shared', id: 'peer-user' }
    expect(isProjectAdministrator(project, user)).toBe(false)
    expect(isProjectAdministrator(project, { ...user, id: 'owner-user' })).toBe(true)
  })

  it('uses the complete project owner list and the selected membership API', () => {
    const soleOwner = { userid: 'owner-user', role: 'Admin' }
    expect(projectMemberActions(project, soleOwner, admin, userApis)).toEqual([])
    expect(projectMemberActions(project, { account: 'other', role: 'Regular' }, admin, userApis)).toEqual(['promote', 'transfer'])
    const twoOwners = { ...project, owner: [...project.owner, { account: 'other' }] }
    expect(projectMemberActions(twoOwners, soleOwner, admin, userApis)).toEqual(['demote', 'remove'])
  })

  it('filters disabled, foreign and already covered user memberships', () => {
    const rows = [
      { id: 'matching', domainid: 'domain', state: 'enabled', accountid: 'candidate' },
      { id: 'foreign', domainid: 'other', state: 'enabled', accountid: 'other' },
      { id: 'disabled', domainid: 'domain', state: 'disabled', accountid: 'different' },
      { id: 'direct', domainid: 'domain', state: 'enabled', accountid: 'different' },
      { id: 'covered', domainid: 'domain', state: 'enabled', accountid: 'whole-account' }
    ]
    const members = [{ userid: 'direct' }, { accountid: 'whole-account' }]
    expect(eligibleProjectCandidates('user', rows, members, 'domain').map(user => user.id)).toEqual(['matching'])
  })

  it('requests one page-one load when changing page size', () => {
    const table = { page: 3, pageSize: 10, fetchProjectAccounts: jest.fn() }
    AccountsTab.methods.changePage.call(table, 3, 20)
    AccountsTab.methods.changePage.call(table, 1, 20)
    expect(table).toMatchObject({ page: 1, pageSize: 20 })
    expect(table.fetchProjectAccounts).toHaveBeenCalledTimes(1)
  })

  it('clears a custom role without replacing the member or administrator type', () => {
    expect(memberOperation('project', { userid: 'user' }, 'customRole', '')).toEqual(
      ['updateProject', { id: 'project', userid: 'user', clearprojectrole: true }]
    )
  })
})
