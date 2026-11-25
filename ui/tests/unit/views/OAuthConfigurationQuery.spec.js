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

import { oauthListQuery, oauthReadParams, oauthSearchQuery } from '@/utils/oauthConfiguration'

describe('OAuth configuration query', () => {
  it('requires a domain UUID for a domain-scoped request', () => {
    expect(oauthReadParams({ query: { scope: 'domain' } }, { page: 1, pagesize: 20 })).toEqual({ page: 1, pagesize: 20 })
    expect(oauthSearchQuery({ scope: 'global', keyword: 'team', page: '3' }, { domainid: 'team-id', provider: 'github' })).toEqual({
      domainid: 'team-id', provider: 'github', keyword: 'team', page: '1'
    })
  })

  it('keeps list filters separate from an exact detail read', () => {
    const query = { domainid: 'team-id', keyword: 'team', provider: 'github', page: '2', pagesize: '20' }
    expect(oauthListQuery(query)).toEqual(query)
    expect(oauthReadParams({ params: { id: 'provider-id' }, query }, {})).toEqual({ id: 'provider-id' })
  })
})
