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

import { reactive } from 'vue'

export const snapshotJobs = reactive({})
const snapshotApis = ['createVMSnapshot', 'revertToVMSnapshot', 'deleteVMSnapshot', 'createSnapshotFromVMSnapshot']
export function trackSnapshotJob (options) {
  const action = options.action
  if (!snapshotApis.includes(action?.api)) return
  const record = action.resource || {}
  const vmId = action.api === 'createVMSnapshot' ? record.id : record.virtualmachineid
  if (vmId) snapshotJobs[options.jobId] = { vmId, snapshotId: record.id, unknown: false }
}
export function finishSnapshotJob (jobId, result) {
  if (!snapshotJobs[jobId]) return
  if ([1, 2].includes(result.jobstatus) || result.trackingStatus === 'cancelled') delete snapshotJobs[jobId]
  else if (result.trackingStatus === 'unknown') snapshotJobs[jobId].unknown = true
}
export function clearSnapshotJobs () {
  Object.keys(snapshotJobs).forEach(id => delete snapshotJobs[id])
}
export function snapshotBusy (vmId) {
  return Object.values(snapshotJobs).some(job => job.vmId === vmId)
}
export function snapshotActionReason (api, snapshot, vm, busy = false) {
  if (busy) return 'message.vmsnapshot.busy'
  if (vm && snapshot.virtualmachineid !== vm.id) return 'message.vmsnapshot.wrong.vm'
  if (api === 'deleteVMSnapshot') return ['Ready', 'Expunging', 'Error'].includes(snapshot.state) ? '' : 'message.vmsnapshot.not.ready'
  if (snapshot.state !== 'Ready') return 'message.vmsnapshot.not.ready'
  if (api === 'createSnapshotFromVMSnapshot') return snapshot.hypervisor === 'KVM' ? '' : 'message.vmsnapshot.kvm.only'
  if (!vm) return '' // Global snapshot records do not carry the current VM state; server validates it.
  if (snapshot.type === 'Disk' && vm.state === 'Running') return 'message.vmsnapshot.stop.first'
  if (snapshot.type === 'DiskAndMemory' && vm.state === 'Stopped') return 'message.vmsnapshot.start.first'
  if (!['Running', 'Stopped'].includes(vm.state) || !['Disk', 'DiskAndMemory'].includes(snapshot.type)) return 'message.vmsnapshot.vm.state'
  return ''
}
