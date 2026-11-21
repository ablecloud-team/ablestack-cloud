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

import { readVpnUsers, vpnUserListLocation, vpnUserListQuery, vpnUserReadParams, vpnUserSearchFields, vpnUserSearchQuery } from '@/utils/vpnUserSearch'

const project = '00000000-0000-0000-0000-000000000001'
const domain = '00000000-0000-0000-0000-000000000002'
const id = '00000000-0000-0000-0000-000000000003'
const apis = {
  listVpnUsers: { params: ['projectid', 'keyword', 'username', 'domainid', 'account'].map(name => ({ name })) },
  listProjects: {},
  listDomains: {},
  listAccounts: {}
}
const getters = { apis, defaultListViewPageSize: 20 }

test('honors the explicit global scope for history without overriding the selected header project', () => {
  expect(vpnUserListQuery({}, 20, { ...getters, listAllProjects: true }).projectid).toBe('-1')
  expect(vpnUserListQuery({ projectid: '0' }, 20, { ...getters, listAllProjects: true }).projectid).toBe('0')
  expect(vpnUserListQuery({ projectid: '-1', page: 4, q: 'old', domainid: domain }, 20, { ...getters, project: { id: project } }))
    .toEqual({ projectid: project, page: '1', pagesize: '20' })
})

test('limits filters to available parameters, owner lookups and compatible scope', () => {
  expect(vpnUserSearchFields('-1', getters)).toEqual(['username', 'domainid'])
  expect(vpnUserSearchFields(project, getters)).toEqual(['username'])
  expect(vpnUserSearchFields('0', { apis: { listVpnUsers: apis.listVpnUsers } })).toEqual(['username'])
  expect(vpnUserListQuery({ account: 'owner' }, 20, getters)).not.toHaveProperty('account')
  expect(vpnUserListQuery({ domainid: ['bad'], account: 'owner' }, 20, getters)).not.toHaveProperty('domainid')
})

test('combines keyword and exact filters, resets page and preserves list location', () => {
  const current = { projectid: '0', q: '  qa  ', page: '3', pagesize: '50' }
  const applied = vpnUserSearchQuery(current, { filters: { username: ' qa-1 ', domainid: domain, account: 'owner' } }, 20, getters)
  expect(applied).toEqual({ projectid: '0', q: 'qa', username: 'qa-1', domainid: domain, account: 'owner', page: '1', pagesize: '50' })
  expect(vpnUserListLocation({ query: { ...applied, page: '2' } }, getters).query.page).toBe('2')
  expect(vpnUserSearchQuery(applied, { searchQuery: 'next' }, 20, getters)).toMatchObject({ q: 'next', username: 'qa-1' })
  expect(vpnUserSearchQuery(applied, { reset: true }, 20, getters)).toEqual({ projectid: '0', page: '1', pagesize: '50' })
})

test('uses project-only listing and omits list filters from detail requests', () => {
  const all = { projectid: '-1', q: 'qa', username: 'qa-1', domainid: domain, page: '3', pagesize: '20' }
  expect(vpnUserReadParams(all)).toEqual({
    projectid: '-1', listall: false, keyword: 'qa', username: 'qa-1', domainid: domain, isrecursive: false, page: 3, pagesize: 20
  })
  expect(vpnUserReadParams(all, id)).toEqual({ projectid: '-1', listall: false, id })
  expect(vpnUserReadParams({ projectid: '0', page: '1', pagesize: '20' })).toEqual({ ignoreproject: true, listall: true, page: 1, pagesize: 20 })
})

test('distinguishes successful empty responses from errors and out-of-scope rows', async () => {
  const query = { projectid: '-1', page: '1', pagesize: '20' }
  const get = jest.fn().mockResolvedValueOnce({ listvpnusersresponse: {} })
    .mockResolvedValueOnce({ listvpnusersresponse: { count: 1, vpnuser: [{ id, username: 'outside' }] } })
    .mockRejectedValueOnce(new Error('lookup failed'))
    .mockResolvedValueOnce({ listvpnusersresponse: { count: 1, vpnuser: [{ id, username: 'inside', projectid: project }] } })
  await expect(readVpnUsers(get, query)).resolves.toEqual({ listvpnusersresponse: { count: 0, vpnuser: [] } })
  await expect(readVpnUsers(get, query)).rejects.toThrow('unexpected scope')
  await expect(readVpnUsers(get, query)).rejects.toThrow('lookup failed')
  await expect(readVpnUsers(get, query)).resolves.toHaveProperty('listvpnusersresponse.count', 1)
})
