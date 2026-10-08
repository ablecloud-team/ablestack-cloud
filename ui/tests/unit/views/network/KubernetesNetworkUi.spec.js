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

import { computed, nextTick, ref } from 'vue'
import { getAPI, postAPI } from '@/api'
import PortForwarding from '@/views/network/PortForwarding'
import FirewallRules from '@/views/network/FirewallRules'
import LoadBalancing from '@/views/network/LoadBalancing'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
beforeEach(() => { postAPI.mockReset(); getAPI.mockReset() })
test('PF edit sends mutable private ports and selected target only', async () => {
  postAPI.mockResolvedValue({ updateportforwardingruleresponse: { jobid: 'job' } })
  const vm = { loading: false, editingRule: { id: 'rule' }, newRule: { protocol: 'tcp', publicport: 80, publicendport: 80, privateport: 8080, privateendport: 8080, virtualmachineid: 'vm', vmguestip: '10.0.0.2' }, resource: { id: 'ip', associatednetworkid: 'net' }, isVPC: () => false, isProtectedManagementRule: () => false, $store: { getters: { apis: { updatePortForwardingRule: {} } } }, $pollJob: jest.fn(), $t: text => text, $notifyError: jest.fn() }
  PortForwarding.methods.addRule.call(vm)
  await Promise.resolve()
  expect(postAPI).toHaveBeenCalledWith('updatePortForwardingRule', { id: 'rule', privateport: 8080, privateendport: 8080, virtualmachineid: 'vm', vmguestip: '10.0.0.2' })
})
test('FW replacement stops after a failed delete and preserves new input', async () => {
  postAPI.mockRejectedValue(new Error('delete failed'))
  const vm = { loading: false, replacingRule: { id: 'old' }, replacementDeleted: false, newRule: { protocol: 'tcp', startport: 80, endport: 80, cidrlist: '10.0.0.1/32' }, $store: { getters: { apis: { createFirewallRule: {}, deleteFirewallRule: {} } } }, isProtectedManagementRule: () => false, $notifyError: jest.fn() }
  await FirewallRules.methods.addRule.call(vm)
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(postAPI).toHaveBeenCalledWith('deleteFirewallRule', { id: 'old' })
  expect(vm.newRule.cidrlist).toBe('10.0.0.1/32')
  expect(vm.loading).toBe(false)
})
test('LB retry assigns targets to the preserved rule and never creates another rule', async () => {
  postAPI.mockResolvedValue({ assigntoloadbalancerruleresponse: { jobid: 'job' } })
  const vm = { loading: false, selectedRule: { id: 'existing' }, selectedBackends: { vm: { ips: ['10.0.0.2'] } }, vpcConserveMode: false, newRule: { protocol: 'tcp' }, assignmentFailed: false, parentToggleLoading: jest.fn(), fetchData: jest.fn(), $t: text => text, $pollJob: options => options.errorMethod(), $notifyError: jest.fn() }
  vm.handleAssignToLBRule = id => LoadBalancing.methods.handleAssignToLBRule.call(vm, id)
  LoadBalancing.methods.handleAddNewRule.call(vm)
  await Promise.resolve()
  expect(postAPI).toHaveBeenCalledWith('assignToLoadBalancerRule', { id: 'existing', 'vmidipmap[0].vmid': 'vm', 'vmidipmap[0].vmip': '10.0.0.2' })
  expect(vm.selectedRule.id).toBe('existing')
  expect(vm.assignmentFailed).toBe(true)
})
test('LB no-target path creates no assignment and does not target all VMs', () => {
  const vm = { selectedBackends: {}, vpcConserveMode: false, newRule: { protocol: 'tcp' }, fetchData: jest.fn(), closeModal: jest.fn(), loading: true }
  LoadBalancing.methods.handleAssignToLBRule.call(vm, 'created')
  expect(postAPI).not.toHaveBeenCalled()
  expect(vm.closeModal).toHaveBeenCalledTimes(1)
})

test('LB creation waits for the create job before assigning any backend', async () => {
  const vm = { creationJob: 'create-job', selectedRule: { id: 'rule' }, $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }), $t: text => text, handleAssignToLBRule: jest.fn() }
  await LoadBalancing.methods.finishRuleCreation.call(vm)
  expect(vm.$pollJob).toHaveBeenCalledWith(expect.objectContaining({ jobId: 'create-job' }))
  expect(vm.handleAssignToLBRule).toHaveBeenCalledWith('rule')
  expect(vm.creationJob).toBe(null)
})
test('an unknown LB creation job retains its ID and does not assign targets', async () => {
  const vm = { creationJob: 'create-job', selectedRule: { id: 'rule' }, loading: true, $pollJob: jest.fn().mockResolvedValue({ jobstatus: 0, trackingStatus: 'unknown' }), $t: text => text, handleAssignToLBRule: jest.fn(), fetchData: jest.fn() }
  await LoadBalancing.methods.finishRuleCreation.call(vm)
  expect(vm.creationJob).toBe('create-job')
  expect(vm.loading).toBe(false)
  expect(vm.handleAssignToLBRule).not.toHaveBeenCalled()
})

test('LB refresh keeps the complete visible snapshot until target lookup finishes', async () => {
  const old = [{ id: 'old', ruleInstances: [{ loadbalancerruleinstance: { id: 'vm-old' } }] }]
  let finish
  getAPI.mockResolvedValueOnce({ listloadbalancerrulesresponse: { count: 1, loadbalancerrule: [{ id: 'new' }] } })
    .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const request = { loaded: true }
  const vm = { resource: { id: 'ip' }, lbRules: old, page: 1, pageSize: 10, $store: { getters: { apis: {} } }, listRequestToken: () => request, isListRequestCurrent: () => true, $notifyError: jest.fn() }
  const refreshing = LoadBalancing.methods.fetchLBRules.call(vm)
  await new Promise(resolve => setImmediate(resolve))
  expect(vm.lbRules).toBe(old)
  expect(vm.loading).toBe(false)
  finish({ listloadbalancerruleinstancesresponse: { lbrulevmidip: [{ loadbalancerruleinstance: { id: 'vm-new' } }] } })
  await refreshing
  expect(vm.lbRules[0].id).toBe('new')
  expect(vm.lbRules[0].ruleInstances[0].loadbalancerruleinstance.id).toBe('vm-new')
})
test('LB refresh failure retains visible targets and reports stale data', async () => {
  const old = [{ id: 'old', ruleInstances: [{ loadbalancerruleinstance: { id: 'vm-old' } }] }]
  getAPI.mockResolvedValueOnce({ listloadbalancerrulesresponse: { loadbalancerrule: [{ id: 'new' }] } })
    .mockRejectedValueOnce(new Error('target lookup failed'))
  const request = { loaded: true }
  const vm = { resource: { id: 'ip' }, lbRules: old, $store: { getters: { apis: {} } }, listRequestToken: () => request, isListRequestCurrent: () => true, $notifyError: jest.fn() }
  await LoadBalancing.methods.fetchLBRules.call(vm)
  expect(vm.lbRules).toBe(old)
  expect(vm.listRefreshFailed).toBe(true)
  expect(request.failed).toBe(true)
  expect(vm.loading).toBe(false)
})

test('LB VM picker headings react to a language change while the component stays mounted', () => {
  const locale = ref('ko')
  const columns = computed(() => LoadBalancing.computed.vmColumns.call({ $t: key => locale.value + ':' + key }))
  expect(columns.value.map(column => column.title)).toContain('ko:label.name')
  locale.value = 'en'
  expect(columns.value.map(column => column.title)).toEqual(['name', 'state', 'displayname', 'account', 'zonename', 'select'].map(field => 'en:label.' + field))
})

test('reopening a SourceBased policy preserves its configured table size and expiry', async () => {
  const vm = { initForm: jest.fn(), form: {}, $t: key => key, stickinessPolicies: [{ lbruleid: 'rule', stickinesspolicy: [{ id: 'policy', name: 'source-policy', methodname: 'SourceBased', params: { tablesize: '10k', expire: '30m' } }] }] }
  LoadBalancing.methods.openStickinessModal.call(vm, 'rule')
  await nextTick()
  expect(vm.form).toMatchObject({ methodname: 'SourceBased', name: 'source-policy', tablesize: '10k', expire: '30m' })
})
