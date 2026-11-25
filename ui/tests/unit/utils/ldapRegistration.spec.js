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

import { ldapEndpointExists, ldapRegistration, ldapRegistrationFailure } from '@/utils/ldapRegistration'

describe('LDAP server registration', () => {
  it('validates DNS, IP and numeric TCP port without replacing input', () => {
    for (const hostname of ['ldap.example.org', '127.0.0.1', '[2001:db8::1]']) {
      expect(ldapRegistration({ hostname, port: '65535', scope: 'global' }, false))
        .toEqual({ errors: {}, params: { hostname, port: 65535 } })
    }
    const { errors } = ldapRegistration({ hostname: 'ldap://wrong.test:389/path', port: '0', scope: 'domain' }, false)
    expect(Object.keys(errors)).toEqual(['hostname', 'port', 'domainid'])
  })

  it('adds domain only for a selected domain and trims hostname and port', () => {
    expect(ldapRegistration({ hostname: ' example.org ', port: ' 389 ', scope: 'domain', domainid: 'd' }, true))
      .toEqual({ errors: {}, params: { hostname: 'example.org', port: 389, domainid: 'd' } })
  })

  it('looks for matching endpoint identity in the same scope after an uncertain response', async () => {
    const getAPI = jest.fn().mockResolvedValue({
      ldapconfigurationresponse: { LdapConfiguration: [{ hostname: 'host', port: 389, domainid: 'd' }] }
    })
    expect(await ldapEndpointExists(getAPI, { hostname: 'host', port: 389, domainid: 'd' })).toBe(true)
    expect(await ldapEndpointExists(getAPI, { hostname: 'host', port: 389 })).toBe(false)
  })

  it('identifies registration errors by code', () => {
    const response = code => ({ response: { data: { ldapconfigurationresponse: { errorcode: 530, cserrorcode: code } } } })
    expect(ldapRegistrationFailure(response(4562))).toBe('message.ldap.add.duplicate')
    expect(ldapRegistrationFailure(response(4563))).toBe('message.ldap.add.bind')
    expect(ldapRegistrationFailure(response(9999))).toBe('message.ldap.add.failed')
    expect(ldapRegistrationFailure(new Error())).toBe('message.ldap.add.unknown')
  })
})
