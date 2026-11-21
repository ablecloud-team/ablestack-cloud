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

import { canDeleteVpnUsers, vpnUserDeleteParams, vpnUserDeleteTargets, vpnUserDeleteTargetExists } from '@/utils/vpnUserDeletion'

const account = { id: '00000000-0000-0000-0000-000000000001', username: 'same', account: 'qa', domainid: '00000000-0000-0000-0000-000000000010' }
const project = { id: '00000000-0000-0000-0000-000000000002', username: 'same', projectid: '00000000-0000-0000-0000-000000000020', project: 'qa-project' }
const other = { ...project, id: '00000000-0000-0000-0000-000000000003', projectid: '00000000-0000-0000-0000-000000000030' }

test('maps same-name targets to their own account or project and excludes unselected rows', () => {
  const rows = vpnUserDeleteTargets([project.id, account.id], [account, project, other])
  expect(rows.map(row => row.id)).toEqual([project.id, account.id])
  expect(rows.map(vpnUserDeleteParams)).toEqual([
    { username: 'same', projectid: project.projectid },
    { username: 'same', account: account.account, domainid: account.domainid, ignoreproject: true }
  ])
})

test('rejects stale selections and invalid project or account owners', () => {
  expect(vpnUserDeleteTargets([project.id, account.id], [project])).toEqual([])
  expect(vpnUserDeleteTargets([project.id], [{ ...account, ...project, projectid: '-1' }])).toEqual([])
  expect(vpnUserDeleteTargets([project.id], [{ ...account, ...project, projectid: null }])).toEqual([])
  expect(vpnUserDeleteTargets([account.id], [{ ...account, domainid: null }])).toEqual([])
})

test('rechecks the exact row and owner before submitting a name-based deletion', async () => {
  const [target] = vpnUserDeleteTargets([account.id], [account])
  const response = row => ({ listvpnusersresponse: { count: 1, vpnuser: [row] } })
  const get = jest.fn().mockResolvedValueOnce(response(account)).mockResolvedValueOnce(response(project))
  await expect(vpnUserDeleteTargetExists(get, target)).resolves.toBe(true)
  await expect(vpnUserDeleteTargetExists(get, target)).resolves.toBe(false)
  expect(get).toHaveBeenCalledWith('listVpnUsers', {
    id: account.id, account: account.account, domainid: account.domainid, listall: true, ignoreproject: true
  }, expect.objectContaining({ preserveOnFailure: true }))
})

test('requires mutation and job capabilities for a list-only role', () => {
  expect(canDeleteVpnUsers({ apis: { listVpnUsers: {} } })).toBe(false)
  expect(canDeleteVpnUsers({ apis: { listVpnUsers: {}, removeVpnUser: {}, queryAsyncJobResult: {} } })).toBe(true)
})
