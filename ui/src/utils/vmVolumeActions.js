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
export const volumeOperations = reactive({})
export function clearVolumeOperations () { Object.keys(volumeOperations).forEach(key => delete volumeOperations[key]) }
export function volumeActionReason (api, volume, vm) {
  if (!vm || !['Running', 'Stopped'].includes(vm.state) || vm.hypervisor === 'External') return 'message.vmvolume.unavailable'
  const flatten = String(volume?.clonefastflattenstatus || volume?.details?.['clone.fast.flatten.status'] || '').toLowerCase()
  if (['pending', 'running'].includes(flatten)) return 'message.sharedmountpoint.clone.flatten.in.progress'
  if (api === 'createVolume') return ''
  if (volume?.type === 'ROOT' && api === 'detachVolume') {
    return vm.state === 'Stopped' && ['KVM', 'XenServer', 'VMware', 'Simulator'].includes(vm.hypervisor) && volume.storageid && volume.virtualmachineid === vm.id && volume.state === 'Ready' ? '' : 'message.vmvolume.root'
  }
  if (!volume || volume.type !== 'DATADISK' || volume.isshared || volume.ismultiattach || volume.type === 'SHARED') return 'message.vmvolume.data.only'
  if (api === 'attachVolume') {
    if (volume.virtualmachineid || !['Ready', 'Allocated', 'Uploaded'].includes(volume.state) || volume.zoneid !== vm.zoneid) return 'message.vmvolume.unavailable'
    if (vm.projectid ? volume.projectid !== vm.projectid : volume.account !== vm.account || volume.domainid !== vm.domainid || volume.projectid) return 'message.vmvolume.owner'
  } else if (api === 'detachVolume') {
    if (volume.virtualmachineid !== vm.id || volume.state !== 'Ready') return 'message.vmvolume.unavailable'
  } else if (api === 'destroyVolume') {
    if (volume.virtualmachineid || volume.state !== 'Ready' || volume.deleteprotection) return 'message.vmvolume.unavailable'
  }
  return ''
}

// 0 is ROOT and 3 is reserved by the server; data-volume workflows cannot use them.
export function volumeDeviceIdReason (value, volumes = []) {
  if (value === undefined || value === null || value === '') return ''
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id < 1 || id === 3) return 'message.vmvolume.device.invalid'
  if (volumes.some(volume => Number(volume.deviceid) === id)) return 'message.vmvolume.device.used'
  return ''
}

// Keep mutations independent of the attached-volume list and tab lifetime.
// An ambiguous submission must never be repeated automatically.
export function startVolumeOperation (key, options, dependencies) {
  const existing = volumeOperations[key]
  if (existing && existing.status !== 'complete') return existing
  volumeOperations[key] = { ...options, stage: 0, status: 'running', jobId: null, error: '', checking: false }
  const operation = volumeOperations[key]
  const current = () => volumeOperations[key] === operation && dependencies.current()
  const fail = (status, error) => { if (current()) { operation.status = status; operation.error = error?.message || String(error || '') } }
  const advance = async result => {
    if (!current() || operation.status === 'complete') return
    if (operation.steps[operation.stage] === 'createVolume') {
      const volume = result.jobresult?.volume
      if (!volume?.id) { fail('unknown', 'message.job.result.unknown'); return }
      operation.volume = volume
    }
    operation.stage++; operation.jobId = null; operation.status = 'running'
    dependencies.refresh()
    await run()
  }
  const poll = async () => {
    if (!current() || operation.checking) return
    operation.checking = true
    try {
      const result = await dependencies.poll(operation)
      if (!current()) return
      if (result.jobstatus === 1) await advance(result)
      else if (result.jobstatus === 2) fail('failed', result.jobresult?.errortext || 'message.vmvolume.failed')
      else fail('unknown', 'message.job.result.unknown')
    } catch (error) { fail('unknown', error) } finally { operation.checking = false }
  }
  const run = async () => {
    if (!current()) return
    if (operation.stage >= operation.steps.length) { operation.status = 'complete'; dependencies.refresh(); return }
    operation.status = 'running'; operation.error = ''
    try { await dependencies.validate(operation) } catch (error) { fail('failed', error); return }
    if (!current()) return
    try {
      const response = await dependencies.submit(operation)
      if (!current()) return
      const data = response[operation.steps[operation.stage].toLowerCase() + 'response']
      if (!data?.jobid) { fail('unknown', 'message.job.result.unknown'); return }
      operation.jobId = data.jobid
    } catch (error) {
      fail(error?.response?.data ? 'failed' : 'unknown', error)
      return
    }
    operation.checking = false
    await poll()
  }
  operation.abandon = () => { if (current() && operation.status === 'failed' && !operation.checking) delete volumeOperations[key] }
  operation.resume = () => {
    if (!current() || operation.checking || operation.status === 'running') return
    if (operation.jobId && operation.status === 'unknown') return poll()
    if (operation.status === 'failed') return run()
  }
  run()
  return operation
}
