// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { isFastClonePowerOperationBlocked, isFastCloneFlattenStatusVisible, getFastClonePhaseLabel } from '@/utils/fastClone'
import { hasExtraConfigDisk, isCloneBlockedByExtraConfigDisk } from '@/utils/vmClone'
import compute from '@/config/section/compute'

describe('SharedMountPoint clone controls', () => {
  const actions = compute.children.find(section => section.name === 'vm').actions

  test.each(['source_ready', 'clone_ready', 'clone_paused'])('allows confirmed power operations in %s', phase => {
    const record = { clonefastphase: phase, clonefaststatus: 'running', clonefastpowerallowed: true }
    expect(isFastClonePowerOperationBlocked(record, [])).toBe(false)
    expect(actions.find(action => action.api === 'startVirtualMachine').disabled(record, {}, [])).toBe(false)
  })

  test.each(['source_failed', 'clone_failed', 'clone_transitioning', 'clone_preparing'])('blocks unconfirmed power operations in %s', phase => {
    const record = { clonefastphase: phase, clonefastpowerallowed: false }
    expect(isFastClonePowerOperationBlocked(record, [])).toBe(true)
    const start = actions.find(action => action.api === 'startVirtualMachine')
    expect(start.tooltip(record, {}, [])).toBeTruthy()
  })

  test('blocks a batch if one selected VM requires recovery', () => {
    const ready = { clonefastphase: 'clone_ready', clonefastpowerallowed: true }
    const failed = { clonefastphase: 'clone_failed', clonefastpowerallowed: false }
    expect(isFastClonePowerOperationBlocked(ready, [ready, failed])).toBe(true)
  })

  test('keeps legacy stopped pending clones startable', () => {
    const pending = { clonefaststatus: 'pending' }
    expect(isFastClonePowerOperationBlocked(pending, [], true)).toBe(false)
    expect(isFastClonePowerOperationBlocked(pending, [])).toBe(true)
  })

  test('hides source-ready flatten status but shows failed source recovery', () => {
    expect(isFastCloneFlattenStatusVisible({ clonefastphase: 'source_ready' })).toBe(false)
    expect(isFastCloneFlattenStatusVisible({ clonefastphase: 'source_failed' })).toBe(true)
    expect(getFastClonePhaseLabel({ clonefastphase: 'clone_failed' })).toBe('label.clone.phase.clone_failed')
  })

  test.each([
    '<disk type="file" device="disk"><source file="/shared/data" /></disk>',
    '<disk device="lun" />',
    '<hostdev type="scsi" />',
    '<disk type="file"'
  ])('blocks unmanaged extra-config disks: %s', xml => {
    const record = { details: { 'extraconfig-1': xml }, state: 'Stopped' }
    expect(hasExtraConfigDisk(record)).toBe(true)
    const clone = actions.find(action => action.api === 'cloneVirtualMachine')
    expect(clone.disabled(record, {}, [])).toBe(true)
    expect(clone.tooltip(record, {}, [])).toBe('message.clone.extraconfig.disk.blocked')
  })

  test('allows extra-config optical drives and non-storage devices', () => {
    expect(hasExtraConfigDisk({ details: { extraconfig: '<disk device="cdrom" /><controller type="scsi" />' } })).toBe(false)
  })

  test('blocks selected unmanaged disks without blocking ordinary VMs', () => {
    expect(isCloneBlockedByExtraConfigDisk({}, [{ details: { extraconfig: '<disk />' } }])).toBe(true)
    expect(isCloneBlockedByExtraConfigDisk({}, [{}])).toBe(false)
  })
})
