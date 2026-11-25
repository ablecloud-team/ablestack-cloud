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

import en from '../../../public/locales/en.json'
import ko from '../../../public/locales/ko_KR.json'

const ldapKeys = locale => Object.keys(locale).filter(key => key.startsWith('label.ldap.') || key.startsWith('message.ldap.')).sort()

describe('LDAP configuration localization', () => {
  it('provides the same LDAP keys in English and Korean', () => {
    expect(ldapKeys(ko)).toEqual(ldapKeys(en))
    for (const key of ldapKeys(en)) {
      expect(en[key].trim()).not.toBe('')
      expect(ko[key].trim()).not.toBe('')
    }
  })

  it('localizes operational instructions and errors while preserving DNS examples', () => {
    for (const key of [
      'message.ldap.host.help', 'message.ldap.port.help', 'message.ldap.scope.help', 'message.ldap.delete.confirm',
      'message.ldap.add.duplicate', 'message.ldap.add.bind', 'message.ldap.list.failed', 'message.ldap.list.empty'
    ]) {
      expect(ko[key]).toMatch(/[가-힣]/)
      expect(ko[key]).not.toBe(en[key])
    }
    expect(en['label.ldap.host.placeholder']).toBe(ko['label.ldap.host.placeholder'])
  })
})
