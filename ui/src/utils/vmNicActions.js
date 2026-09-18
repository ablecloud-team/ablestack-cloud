// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { reactive } from 'vue'

export const nicOperations = reactive({})
export const clearNicOperations = () => Object.keys(nicOperations).forEach(key => delete nicOperations[key])
export const nicOwner = vm => vm.projectid ? { projectid: vm.projectid } : { account: vm.account, domainid: vm.domainid }
// Both commands change the KVM interface link. Offer one state action, not two.
export const nicStateAction = (vm, apis) => vm.hypervisor === 'KVM' && 'updateVmNic' in apis ? 'updateVmNic' : 'UpdateVmNicLinkState' in apis ? 'UpdateVmNicLinkState' : null
const topology = ['createNetwork', 'addNicToVirtualMachine', 'removeNicFromVirtualMachine', 'updateDefaultNicForVirtualMachine']

export function nicActionReason (api, nic, vm, context = {}) {
  if (!vm || !['Running', 'Stopped'].includes(vm.state) || vm.hypervisor === 'External') return 'message.vmnic.unavailable'
  if (topology.includes(api)) {
    if (context.snapshots == null || !context.zone) return 'message.vmnic.context'
    if (context.snapshots) return 'message.vmnic.snapshots'
    if (context.zone.networktype === 'Basic' && !(api === 'addNicToVirtualMachine' && context.rows?.length === 0)) return 'message.vmnic.basic'
  }
  if (['createNetwork', 'addNicToVirtualMachine'].includes(api)) return ''
  if (!nic?.id) return 'message.vmnic.context'
  if (['removeNicFromVirtualMachine', 'updateDefaultNicForVirtualMachine'].includes(api) && nic.isdefault) return 'message.vmnic.default'
  if (api === 'updateVmNic' && vm.hypervisor !== 'KVM') return 'message.vmnic.kvm'
  if (api === 'UpdateVmNicLinkState' && (!context.zone || context.zone.networktype === 'Basic' || typeof nic.linkstate !== 'boolean')) return 'message.vmnic.context'
  if (api === 'updateVmNic' && typeof nic.enabled !== 'boolean') return 'message.vmnic.context'
  if (['addIpToNic', 'removeIpFromNic'].includes(api) && nic.type === 'L2') return 'message.vmnic.l2'
  if (api === 'updateVmNicIp' && vm.state !== 'Stopped' && nic.type === 'L2') return 'message.vmnic.mac.stop'
  if (api === 'updateVmNicIp' && vm.state !== 'Stopped' && context.network?.type !== 'L2') {
    if (!context.network || !Array.isArray(context.network.service)) return 'message.vmnic.context'
    if (context.network.service.length) return 'message.vmnic.stop'
  }
  return ''
}

export function nicAddressParams (nic, values) {
  const params = { nicid: nic.id }
  // Omitting IPv4 on this API can request a new allocation, even for a MAC-only edit.
  if (nic.type !== 'L2') params.ipaddress = (values.ipaddress || nic.ipaddress || '').trim()
  if (values.macaddress) params.macaddress = values.macaddress.trim()
  return params
}

// Store the operation outside the component so tab changes cannot duplicate writes.
// Synchronous createNetwork, asynchronous NIC writes and reconciliation are distinct.
export function startNicOperation (key, options, deps) {
  if (nicOperations[key] && nicOperations[key].status !== 'complete') return nicOperations[key]
  nicOperations[key] = { ...options, stage: 0, status: 'running', jobId: null, error: '', checking: false, accepted: false }
  const op = nicOperations[key]
  const current = () => nicOperations[key] === op && deps.current()
  const fail = (state, error) => { if (current()) { op.status = state; op.error = error?.message || String(error || 'message.vmnic.failed') } }
  const drive = async () => {
    if (!current() || op.checking) return
    op.checking = true
    try {
      while (current() && op.stage < op.steps.length) {
        const api = op.steps[op.stage]
        op.status = 'running'; op.error = ''
        if (!op.accepted && !op.jobId) {
          try { await deps.validate(op) } catch (e) { fail('failed', e); return }
          if (!current()) return
          let response
          try { response = await deps.submit(op) } catch (e) {
            const apiError = Object.values(e?.response?.data || {}).find(value => value?.errorcode && value?.errortext)
            fail(apiError ? 'failed' : 'unknown', apiError?.errortext || e); return
          }
          if (!current()) return
          const data = response[api === 'addIpToNic' ? 'addiptovmnicresponse' : api.toLowerCase() + 'response']
          if (api === 'createNetwork' && data?.network?.id) {
            op.network = data.network; op.accepted = true
          } else if (data?.jobid) {
            op.jobId = data.jobid
          } else { fail('unknown', 'message.job.result.unknown'); return }
        }
        if (op.jobId && !op.accepted) {
          let result
          try { result = await deps.poll(op) } catch (e) { fail('unknown', e); return }
          if (!current()) return
          if (result.jobstatus === 2) { op.jobId = null; fail('failed', result.jobresult?.errortext); return }
          if (result.jobstatus !== 1) { fail('unknown', 'message.job.result.unknown'); return }
          op.accepted = true
        }
        try {
          if (!(await deps.reconcile(op))) { fail('unknown', 'message.vmnic.reconcile'); return }
        } catch (e) { fail('unknown', e); return }
        if (!current()) return
        op.stage++; op.jobId = null; op.accepted = false
        deps.refresh()
      }
      if (current()) { op.status = 'complete'; deps.refresh() }
    } finally { op.checking = false }
  }
  op.resume = async () => {
    if (!current() || op.checking || op.status === 'running') return
    if (op.status === 'failed' || op.jobId || op.accepted) return drive()
    // Submission response lost: only positive resource evidence may advance it.
    op.checking = true
    try {
      if (await deps.reconcile(op)) { op.accepted = true } else fail('unknown', 'message.vmnic.reconcile')
    } catch (e) { fail('unknown', e) } finally { op.checking = false }
    if (op.accepted) return drive()
  }
  op.abandon = () => { if (current() && op.status === 'failed' && !op.checking) delete nicOperations[key] }
  drive()
  return op
}
