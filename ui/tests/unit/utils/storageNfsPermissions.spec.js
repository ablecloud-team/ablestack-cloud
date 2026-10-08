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

import { nfsPermissionPreset, recommendedNfsPermissionPatch } from '@/utils/storageNfsPermissions'

describe('NFS permission recommendations', () => {
  it('suggests 0:0 and a conservative directory mode when no squash is selected', () => {
    expect(nfsPermissionPreset({ rootsquash: false, allsquash: false, owneruid: 65534 })).toMatchObject({ policy: 'NO_ROOT_SQUASH', owneruid: 0, ownergid: 0, mode: '0770' })
  })
  it('uses the selected anonymous identity for root or all squash independently', () => {
    expect(nfsPermissionPreset({ rootsquash: true, anonuid: 24567, anongid: 24568 })).toMatchObject({ policy: 'ROOT_SQUASH', owneruid: 24567, ownergid: 24568 })
    expect(nfsPermissionPreset({ rootsquash: false, allsquash: true, anonuid: 24567, anongid: 24568 })).toMatchObject({ policy: 'ALL_SQUASH', owneruid: 24567, ownergid: 24568 })
  })
  it('never proposes an ownership or mode write for a read-only export', () => {
    const input = { readonly: true, rootsquash: false, owneruid: 1234, ownergid: 4321, mode: '0750' }
    expect(nfsPermissionPreset(input)).toMatchObject({ policy: 'READ_ONLY_PRESERVE', applyowner: false })
    expect(recommendedNfsPermissionPatch(input)).toEqual({})
    expect(input).toEqual({ readonly: true, rootsquash: false, owneruid: 1234, ownergid: 4321, mode: '0750' })
  })
  it('requires an explicit patch request and preserves a common directory policy', () => {
    const input = { rootsquash: false, owneruid: 1234, mode: '0750', recursivepermission: true }
    expect(nfsPermissionPreset(input).owneruid).toBe(0)
    expect(input.owneruid).toBe(1234)
    expect(recommendedNfsPermissionPatch(input)).toEqual({ owneruid: 0, ownergid: 0, mode: '0770', recursivepermission: false })
    expect(recommendedNfsPermissionPatch({ ...input, posixpolicyid: 'common-policy' })).toEqual({})
  })
})
