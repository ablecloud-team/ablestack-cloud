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

import Widget from '@/views/storage/PosixPolicyInheritance'
jest.mock('@/api', () => ({ getAPI: jest.fn() }))

describe('Protocol-neutral POSIX ownership inheritance', () => {
  it('NFS inherits the exact common UID/GID/mode and disables recursive permission changes', () => {
    const emitted = jest.fn(); const vm = { protocol: 'NFS', $emit: emitted, policies: [{ id: 'policy', volumeid: 'volume', relativepath: 'shared', effective: JSON.stringify({ effectiveUid: 1001001, effectiveGid: 1001001, effectiveMode: '2775' }) }] }
    Widget.methods.select.call(vm, 'policy')
    expect(emitted).toHaveBeenCalledWith('patch', { posixpolicyid: 'policy', volumeid: 'volume', relativepath: 'shared', volumemode: 'CURRENT', owneruid: 1001001, ownergid: 1001001, mode: '2775', recursivepermission: false })
  })
  it('SMB refers to the same canonical path and explicitly enables cross protocol sharing', () => {
    const emitted = jest.fn(); const vm = { protocol: 'SMB', $emit: emitted, policies: [{ id: 'policy', volumeid: 'volume', relativepath: 'shared', effective: JSON.stringify({ effectiveMode: '2775' }) }] }
    Widget.methods.select.call(vm, 'policy')
    expect(emitted).toHaveBeenCalledWith('patch', { posixpolicyid: 'policy', volumeid: 'volume', relativepath: 'shared', volumemode: 'CURRENT', directorymode: '2775', crossprotocol: true })
  })
  it('clearing the selection removes its reference through a parent patch', () => {
    const emitted = jest.fn(); Widget.methods.select.call({ policies: [], $emit: emitted }, undefined)
    expect(emitted).toHaveBeenCalledWith('patch', { posixpolicyid: undefined })
  })
})
