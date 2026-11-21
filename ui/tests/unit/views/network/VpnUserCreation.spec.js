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

import { canChooseVpnUserAccount, canCreateVpnUser, vpnUserCreateFailure, vpnUserCreateParams, vpnUserCredentialError } from '@/utils/vpnUserCreation'

const formatError = field => 'message.vpn.user.add.' + field + '.format'

describe('VPN user registration', () => {
  test.each([
    ['username', '1._', 64, 'a@._-'],
    ['password', '1+=', 32, 'a@+=._-']
  ])('applies the server length, character set and first character to %s', (field, minimum, maximum, allowed) => {
    expect(vpnUserCredentialError(field, '')).toMatch(/required$/)
    expect(vpnUserCredentialError(field, minimum)).toBe('')
    expect(vpnUserCredentialError(field, allowed)).toBe('')
    expect(vpnUserCredentialError(field, 'a'.repeat(maximum))).toBe('')
    for (const value of ['xx', 'a'.repeat(maximum + 1), '_valid', 'a?b', 'a b', 'a한b', 'abc\n']) {
      expect(vpnUserCredentialError(field, value)).toBe(formatError(field))
    }
  })

  it('builds mutually exclusive project, delegated account and caller requests', () => {
    const valid = { username: 'vpn-user', password: 's4mple-credential' }
    const request = { ...valid, ignoreproject: true }
    expect(vpnUserCreateParams(valid, { own: true })).toEqual(request)
    expect(vpnUserCreateParams(valid, { projectid: 'p' })).toEqual({ ...request, projectid: 'p' })
    expect(vpnUserCreateParams(valid, { domainid: 'd', account: 'a' })).toEqual({ ...request, domainid: 'd', account: 'a' })
    for (const owner of [null, {}, { account: 'a' }, { domainid: 'd' }, { own: true, account: 'a' }, { projectid: 'p', account: 'a', domainid: 'd' }]) {
      expect(vpnUserCreateParams(valid, owner)).toBeNull()
    }
    expect(vpnUserCreateParams({ ...valid, username: '-name' }, { own: true })).toBeNull()
  })

  it('uses the active project and API permissions to gate delegation and async submission', () => {
    const apis = {
      addVpnUser: { params: ['username', 'password', 'projectid', 'domainid', 'account'].map(name => ({ name })) },
      queryAsyncJobResult: {},
      listAccounts: {},
      listDomains: {}
    }
    const admin = { apis, userInfo: { roletype: 'Admin' } }
    expect(canCreateVpnUser(admin)).toBe(true)
    expect(canChooseVpnUserAccount(admin)).toBe(true)
    expect(canChooseVpnUserAccount({ ...admin, userInfo: { roletype: 'User' } })).toBe(false)
    expect(canChooseVpnUserAccount({ ...admin, apis: { ...apis, listAccounts: undefined } })).toBe(false)
    expect(canChooseVpnUserAccount({ ...admin, project: { id: 'p' } })).toBe(false)
    expect(canCreateVpnUser({ ...admin, apis: { ...apis, queryAsyncJobResult: undefined } })).toBe(false)
    expect(canCreateVpnUser({ ...admin, project: { id: 'p' }, apis: { ...apis, addVpnUser: { params: apis.addVpnUser.params.filter(p => p.name !== 'projectid') } } })).toBe(false)
  })

  it('uses localized error categories and treats missing confirmation as unknown', () => {
    const error = (status, errortext) => ({ response: { status, data: { errorresponse: { errorcode: status, errortext } } } })
    expect(vpnUserCreateFailure(error(431, 'VPN User with name test is already added for account'))).toBe('duplicate')
    expect(vpnUserCreateFailure(error(532, 'untrusted server text'))).toBe('limit')
    expect(vpnUserCreateFailure(error(431, 'Unable to find account by name'))).toBe('owner')
    expect(vpnUserCreateFailure(error(403, 'untrusted server text'))).toBe('permission')
    expect(vpnUserCreateFailure(error(431, 'untrusted server text'))).toBe('rejected')
    expect(vpnUserCreateFailure(error(503, 'untrusted server text'))).toBe('unknown')
    expect(vpnUserCreateFailure(new Error('connection timeout'))).toBe('unknown')
    expect(vpnUserCreateFailure(null, { errorcode: 530, errortext: 'untrusted server text' })).toBe('job')
  })
})
