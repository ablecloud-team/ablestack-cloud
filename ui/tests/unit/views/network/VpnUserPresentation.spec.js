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

import { vpnUserOwner, vpnUserState, vpnUserVisibleColumns } from '@/utils/vpnUserPresentation'
import { vpnUserDeleteParams, vpnUserDeleteTarget } from '@/utils/vpnUserDeletion'

const projectid = '00000000-0000-0000-0000-000000000001'
const domainid = '00000000-0000-0000-0000-000000000002'
const id = '00000000-0000-0000-0000-000000000003'
const getters = { apis: { listProjects: {}, listDomains: {}, listAccounts: {} }, userInfo: { roletype: 'Admin' } }

test('presents the same project and account owner from list rows and frozen deletion data', () => {
  const project = { id, username: 'same', projectid, project: 'Lab project', account: 'PrjAcct-lab', domainid, state: 'Add' }
  const account = { id, username: 'same', account: 'vpn-team', domainid, domain: 'Team', domainpath: 'ROOT/Team', state: 'Active' }
  for (const row of [project, account]) {
    const target = vpnUserDeleteTarget(row)
    expect(vpnUserOwner(target, getters)).toEqual(vpnUserOwner(row, getters))
    expect(target.state).toBe(row.state)
  }
  expect(vpnUserDeleteParams(vpnUserDeleteTarget(project))).toEqual({ username: 'same', projectid })
  expect(vpnUserDeleteParams(vpnUserDeleteTarget(account))).toEqual({ username: 'same', account: account.account, domainid, ignoreproject: true })
})

test('uses existing owner routes only with valid IDs and matching permission', () => {
  const account = { account: 'vpn-team', domainid }
  expect(vpnUserOwner(account, getters)).toEqual({
    type: 'label.account',
    name: 'vpn-team',
    domain: domainid,
    location: { path: '/account', query: { name: 'vpn-team', domainid, dataView: true } },
    domainLocation: { path: '/domain/' + domainid, query: { tab: 'details' } }
  })
  expect(vpnUserOwner(account, { ...getters, userInfo: { roletype: 'User' } })).toMatchObject({ location: null, domainLocation: null })
  expect(vpnUserOwner({ projectid }, getters)).toEqual({ type: 'label.project', name: projectid, location: { path: '/project/' + projectid } })
  expect(vpnUserOwner({ projectid }).location).toBeNull()
  expect(vpnUserOwner({ projectid: '-1' }, getters).location).toBeNull()
})

test('localizes server statuses and merges previously selected owner columns', () => {
  expect(['Add', 'Active', 'Revoke', 'Future'].map(state => vpnUserState(state).label)).toEqual([
    'label.vpn.user.state.add', 'label.vpn.user.state.active', 'label.vpn.user.state.revoke', 'label.unknown'
  ])
  expect(vpnUserVisibleColumns(['username', 'state', 'account', 'domain', 'project'])).toEqual(['username', 'state', 'owners'])
  expect(vpnUserVisibleColumns(['username', 'state', 'owners'])).toEqual(['username', 'state', 'owners'])
})
