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

export function attachedIsos (vm = {}) {
  const rows = Array.isArray(vm.isos) && vm.isos.length ? vm.isos : vm.isoid ? [{ id: vm.isoid, name: vm.isoname, displaytext: vm.isodisplaytext, deviceseq: 3 }] : []
  return [...new Map(rows.filter(row => row.id).map(row => [row.id, row])).values()].sort((a, b) => (a.deviceseq || 0) - (b.deviceseq || 0))
}
export function isoCapacity (vm = {}) { return Math.max(0, Number(vm.isomaxcount ?? (vm.hypervisor === 'KVM' ? 2 : 1)) - attachedIsos(vm).length) }
export function isoActionReason (vm, attach = false) {
  if (!vm || !['Running', 'Stopped'].includes(vm.state) || vm.hypervisor === 'External' || vm.vmtype === 'sharedfsvm') return 'message.vmiso.unavailable'
  if (['Offline', 'Maintenance'].includes(vm.hostcontrolstate)) return 'message.vmiso.host'
  if (attach && isoCapacity(vm) === 0) return 'message.vmiso.full'
  return ''
}
export function isoSlot (iso, vm) {
  if (iso.deviceseq == null) return '—'
  // The API exposes a slot sequence, not a guest device name (IDE/SCSI differs).
  return String(iso.deviceseq)
}
export const isoOperations = reactive({})
export function clearIsoOperations () { Object.keys(isoOperations).forEach(key => delete isoOperations[key]) }

// Retain accepted jobs across tab changes; never resubmit an uncertain operation.
export function startIsoOperation (key, options, deps) {
  if (isoOperations[key]?.running || isoOperations[key]?.items.some(i => i.status === 'unknown')) return isoOperations[key]
  isoOperations[key] = { ...options, items: options.items.map(item => ({ ...item, status: 'pending', jobId: null, error: '' })), running: false }
  const op = isoOperations[key]
  const current = () => isoOperations[key] === op && deps.current()
  const refresh = async () => { try { await deps.refresh() } catch (_) { /* The list reports its own refresh error. */ } }
  const poll = async item => {
    try {
      const result = await deps.poll(item.jobId, item)
      if (!current()) return
      item.status = result.jobstatus === 1 ? 'success' : result.jobstatus === 2 ? 'failed' : 'unknown'
      item.error = result.jobresult?.errortext || (item.status === 'unknown' ? 'message.job.result.unknown' : '')
    } catch (_) { if (current()) { item.status = 'unknown'; item.error = 'message.job.result.unknown' } }
    await refresh()
  }
  const run = async () => {
    if (op.running || !current() || op.items.some(i => i.status === 'unknown')) return
    op.running = true
    try {
      for (const item of op.items) {
        if (!current()) break
        if (item.status !== 'pending') continue
        try { await deps.validate(item) } catch (error) { if (current()) { item.status = 'failed'; item.error = error.message } continue }
        if (!current()) break
        item.status = 'running'
        try {
          const response = await deps.submit(item)
          if (!current()) break
          item.jobId = response?.jobid
          if (!item.jobId) { item.status = 'unknown'; item.error = 'message.job.result.unknown' } else await poll(item)
        } catch (error) {
          if (!current()) break
          const data = error?.response?.data
          const rejection = data && Object.values(data).find(value => value?.errorcode && value?.errortext)
          item.status = rejection ? 'failed' : 'unknown'
          item.error = rejection?.errortext || 'message.job.result.unknown'
        }
        if (item.status === 'unknown') break
      }
    } finally { op.running = false; await refresh() }
  }
  op.retry = async () => {
    if (op.running || op.items.some(i => i.status === 'unknown') || !current()) return
    op.items.filter(i => i.status === 'failed').forEach(i => { i.status = 'pending'; i.error = ''; i.jobId = null })
    await run()
  }
  op.check = async () => {
    if (op.running || !current()) return
    op.running = true
    try { for (const item of op.items.filter(i => i.status === 'unknown' && i.jobId)) { await poll(item); if (!current()) break } } finally { op.running = false }
    await run()
  }
  op.done = run()
  return op
}
