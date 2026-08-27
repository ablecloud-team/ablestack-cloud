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

import {
  groupActionMenuEntries,
  isDangerAction,
  resolveActionMenuGroup
} from '@/utils/actionMenu'

describe('Utils > actionMenu', () => {
  it('keeps explicitly assigned menu groups', () => {
    expect(resolveActionMenuGroup({ api: 'deleteVolume', menuGroup: 'ACCESS' })).toBe('ACCESS')
  })

  it('classifies common action domains', () => {
    expect(resolveActionMenuGroup({ api: 'createConsoleEndpoint' })).toBe('ACCESS')
    expect(resolveActionMenuGroup({ api: 'createSnapshot' })).toBe('STORAGE')
    expect(resolveActionMenuGroup({ api: 'createBackupSchedule' })).toBe('BACKUP')
    expect(resolveActionMenuGroup({ api: 'updateNetwork' })).toBe('NETWORK')
  })

  it('identifies destructive operations unless explicitly overridden', () => {
    expect(isDangerAction({ api: 'destroyVirtualMachine' })).toBe(true)
    expect(isDangerAction({ api: 'deleteVolume', danger: false })).toBe(false)
  })

  it('places destructive entries in the final group', () => {
    const groups = groupActionMenuEntries([
      { key: 'start', group: 'COMPUTE' },
      { key: 'delete', group: 'DANGER' }
    ])
    expect(groups.map(group => group.key)).toEqual(['COMPUTE', 'DANGER'])
  })
})
