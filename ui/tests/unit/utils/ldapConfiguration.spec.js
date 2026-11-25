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

import { ldapDomainPaths, ldapListQuery, ldapReadParams, ldapRemovedListQuery, ldapSearchQuery } from '@/utils/ldapConfiguration'

describe('LDAP configuration list query', () => {
  const paging = { page: 1, pagesize: 20 }

  it('distinguishes global from domain and excludes unrelated route filters', () => {
    expect(ldapReadParams({ query: {} }, paging)).toEqual({ ...paging, listall: true })
    expect(ldapReadParams({ query: { scope: 'global', domainid: 'root', projectid: '-1' } }, paging)).toEqual({ ...paging, listall: false })
    expect(ldapReadParams({ query: { domainid: 'root', port: '389', page: '2' } }, paging))
      .toEqual({ listall: true, domainid: 'root', port: '389', page: '2', pagesize: 20 })
  })

  it('dispatches only the UUID for exact lookups and keeps detail and list targets distinct', () => {
    const query = { id: 'list', port: '389', scope: 'global', page: '2' }
    expect(ldapListQuery(query)).toEqual({ id: 'list' })
    expect(ldapReadParams({ query }, paging)).toEqual({ id: 'list' })
    expect(ldapReadParams({ params: { id: 'detail' }, query }, paging)).toEqual({ id: 'detail' })
  })

  it('preserves scope and resets page for a new keyword', () => {
    expect(ldapSearchQuery({ domainid: 'domain', port: '389', page: '4', pagesize: '50' }, { searchQuery: '  ldap. ' }))
      .toEqual({ domainid: 'domain', port: '389', page: '1', pagesize: '50', keyword: 'ldap.' })
    expect(ldapSearchQuery({ scope: 'global', keyword: 'key' }, { domainid: 'domain' }))
      .toEqual({ domainid: 'domain', keyword: 'key', page: '1' })
  })

  it('keeps list filters after deletion and clears only the vanished exact lookup', () => {
    expect(ldapRemovedListQuery({ domainid: 'domain', port: '389', page: '2' }, 'removed'))
      .toEqual({ domainid: 'domain', port: '389', page: '2' })
    expect(ldapRemovedListQuery({ id: 'removed' }, 'removed')).toEqual({})
    expect(ldapRemovedListQuery({ id: 'other' }, 'removed')).toEqual({ id: 'other' })
  })

  it('resolves unique domain IDs and preserves the UUID on a failed lookup', async () => {
    const getAPI = jest.fn((_, { id }) => id === 'first'
      ? Promise.resolve({ listdomainsresponse: { domain: [{ id, path: 'ROOT/QA' }] } })
      : Promise.reject(new Error('Unavailable')))
    const relevant = [{ domainid: 'first' }, { domainid: 'first' }, { domainid: 'second' }, {}]
    expect((await ldapDomainPaths(getAPI, relevant, true, 'Global')).map(row => row.domainpath)).toEqual(['ROOT/QA', 'ROOT/QA', 'second', 'Global'])
    expect(getAPI).toHaveBeenCalledTimes(2)
  })
})
