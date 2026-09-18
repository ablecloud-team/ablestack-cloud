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

import { snapshotActionReason, snapshotBusy, trackSnapshotJob, finishSnapshotJob, clearSnapshotJobs } from '@/utils/vmSnapshotActions'
const disk = { id: 's', virtualmachineid: 'v', state: 'Ready', type: 'Disk', hypervisor: 'KVM' }
beforeEach(clearSnapshotJobs)
test.each([
  ['Stopped', 'Disk', ''], ['Running', 'Disk', 'message.vmsnapshot.stop.first'],
  ['Running', 'DiskAndMemory', ''], ['Stopped', 'DiskAndMemory', 'message.vmsnapshot.start.first'],
  ['Starting', 'Disk', 'message.vmsnapshot.vm.state']
])('restore matrix %s %s', (state, type, expected) => {
  expect(snapshotActionReason('revertToVMSnapshot', { ...disk, type }, { id: 'v', state })).toBe(expected)
})
test('rejects a snapshot belonging to another VM', () => {
  expect(snapshotActionReason('deleteVMSnapshot', disk, { id: 'other' })).toBe('message.vmsnapshot.wrong.vm')
})
test('retains Expunging recovery delete but blocks locally running jobs', () => {
  expect(snapshotActionReason('deleteVMSnapshot', { ...disk, state: 'Expunging' }, { id: 'v' })).toBe('')
  expect(snapshotActionReason('deleteVMSnapshot', disk, { id: 'v' }, true)).toBe('message.vmsnapshot.busy')
})
test('unknown jobs remain locked until result is reconciled, scoped to the VM', () => {
  trackSnapshotJob({ jobId: 'j', action: { api: 'revertToVMSnapshot', resource: disk } })
  finishSnapshotJob('j', { trackingStatus: 'unknown' })
  expect(snapshotBusy('v')).toBe(true)
  expect(snapshotBusy('other')).toBe(false)
  finishSnapshotJob('j', { jobstatus: 1 })
  expect(snapshotBusy('v')).toBe(false)
})
test('creation and cancellation use the VM resource and release on scope change', () => {
  trackSnapshotJob({ jobId: 'j', action: { api: 'createVMSnapshot', resource: { id: 'v' } } })
  expect(snapshotBusy('v')).toBe(true)
  clearSnapshotJobs()
  expect(snapshotBusy('v')).toBe(false)
})
