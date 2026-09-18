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

import { startNicOperation, clearNicOperations, nicActionReason, nicAddressParams, nicOwner, nicStateAction } from '@/utils/vmNicActions'
const flush = async () => { for (let i = 0; i < 90; i++) await Promise.resolve() }
const vm = { id: 'vm', state: 'Running', hypervisor: 'KVM', account: 'owner', domainid: 'domain' }
const nic = { id: 'nic', type: 'Shared', ipaddress: '10.0.0.2', macaddress: '02:00:00:00:00:01', enabled: true, linkstate: true }
const context = { snapshots: 0, zone: { networktype: 'Advanced' } }
const deps = () => ({ current: () => true, refresh: jest.fn(), validate: jest.fn().mockResolvedValue(), reconcile: jest.fn().mockResolvedValue(true), submit: jest.fn().mockImplementation(op => Promise.resolve({ [op.steps[op.stage].toLowerCase() + 'response']: { jobid: 'job-' + op.stage } })), poll: jest.fn().mockResolvedValue({ jobstatus: 1 }) })
beforeEach(clearNicOperations)
test('one state action is selected for each hypervisor and permission set', () => {
  const apis = { updateVmNic: {}, UpdateVmNicLinkState: {} }
  expect(nicStateAction(vm, apis)).toBe('updateVmNic')
  expect(nicStateAction({ ...vm, hypervisor: 'VMware' }, apis)).toBe('UpdateVmNicLinkState')
  expect(nicStateAction(vm, { UpdateVmNicLinkState: {} })).toBe('UpdateVmNicLinkState')
  expect(nicStateAction(vm, {})).toBeNull()
})
test('topology requires known context and respects snapshots, basic, default, VM state and external hypervisor', () => {
  expect(nicActionReason('addNicToVirtualMachine', null, vm)).toBeTruthy()
  expect(nicActionReason('addNicToVirtualMachine', null, vm, context)).toBe('')
  expect(nicActionReason('createNetwork', null, vm, { ...context, snapshots: 1 })).toBe('message.vmnic.snapshots')
  expect(nicActionReason('removeNicFromVirtualMachine', nic, vm, { ...context, zone: { networktype: 'Basic' } })).toBe('message.vmnic.basic')
  expect(nicActionReason('addNicToVirtualMachine', null, vm, { ...context, zone: { networktype: 'Basic' }, rows: [] })).toBe('')
  expect(nicActionReason('removeNicFromVirtualMachine', { ...nic, isdefault: true }, vm, context)).toBe('message.vmnic.default')
  for (const update of [{ state: 'Starting' }, { hypervisor: 'External' }]) expect(nicActionReason('addNicToVirtualMachine', null, { ...vm, ...update }, context)).toBeTruthy()
})
test('enabled and link gates are independent; address changes require network services or stopped VM', () => {
  expect(nicActionReason('updateVmNicIp', { ...nic, type: 'L2' }, vm, { ...context, network: { type: 'L2' } })).toBe('message.vmnic.mac.stop')
  expect(nicActionReason('updateVmNicIp', { ...nic, type: 'L2' }, { ...vm, state: 'Stopped' }, context)).toBe('')
  expect(nicActionReason('updateVmNic', nic, { ...vm, hypervisor: 'VMware' }, context)).toBe('message.vmnic.kvm')
  expect(nicActionReason('UpdateVmNicLinkState', { ...nic, linkstate: undefined }, vm, context)).toBeTruthy()
  expect(nicActionReason('updateVmNicIp', nic, vm, { ...context, network: { service: [{ name: 'Dhcp' }] } })).toBe('message.vmnic.stop')
  expect(nicActionReason('updateVmNicIp', nic, vm, { ...context, network: { type: 'L2' } })).toBe('')
  expect(nicActionReason('updateVmNicIp', nic, { ...vm, state: 'Stopped' }, context)).toBe('')
  expect(nicActionReason('addIpToNic', { ...nic, type: 'L2' }, vm, context)).toBe('message.vmnic.l2')
})
test('MAC-only preserves IPv4; L2 omits IPv4 and project owner excludes account', () => {
  expect(nicAddressParams(nic, { macaddress: '02:00:00:00:00:02' })).toEqual({ nicid: 'nic', ipaddress: '10.0.0.2', macaddress: '02:00:00:00:00:02' })
  expect(nicAddressParams({ ...nic, type: 'L2' }, { ipaddress: '10.0.0.8' })).toEqual({ nicid: 'nic' })
  expect(nicOwner({ ...vm, projectid: 'p' })).toEqual({ projectid: 'p' })
})
test('sync create retains network, retries attach only and never duplicates clicks', async () => {
  const d = deps(); d.submit.mockResolvedValueOnce({ createnetworkresponse: { network: { id: 'new' } } })
  d.poll.mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'snapshots' } }).mockResolvedValue({ jobstatus: 1 })
  const op = startNicOperation('k', { steps: ['createNetwork', 'addNicToVirtualMachine'] }, d)
  expect(startNicOperation('k', { steps: ['createNetwork'] }, d)).toBe(op)
  await flush(); expect(op.network.id).toBe('new'); expect(op.stage).toBe(1); expect(op.status).toBe('failed')
  op.resume(); op.resume(); await flush(); expect(op.status).toBe('complete'); expect(d.submit).toHaveBeenCalledTimes(3)
})
test('job poll interruption retries same job without another write', async () => {
  const d = deps(); d.poll.mockRejectedValueOnce(new Error('lost')).mockResolvedValue({ jobstatus: 1 })
  const op = startNicOperation('k', { steps: ['updateVmNic'] }, d)
  await flush(); expect(op.status).toBe('unknown'); op.resume(); await flush()
  expect(op.status).toBe('complete'); expect(d.submit).toHaveBeenCalledTimes(1)
})
test('successful job waits for readback and retries only reconciliation', async () => {
  const d = deps(); d.reconcile.mockResolvedValueOnce(false).mockResolvedValue(true)
  const op = startNicOperation('k', { steps: ['updateVmNic'] }, d)
  await flush(); expect(op.status).toBe('unknown'); expect(op.accepted).toBe(true)
  op.resume(); await flush(); expect(op.status).toBe('complete'); expect(d.submit).toHaveBeenCalledTimes(1); expect(d.poll).toHaveBeenCalledTimes(1)
})
test('lost create response never reissues mutation and remains unknown without an ID', async () => {
  const d = deps(); d.submit.mockRejectedValue(new Error('connection lost')); d.reconcile.mockResolvedValue(false)
  const op = startNicOperation('k', { steps: ['createNetwork', 'addNicToVirtualMachine'] }, d)
  await flush(); op.resume(); await flush(); expect(op.status).toBe('unknown'); expect(d.submit).toHaveBeenCalledTimes(1)
})
test('security change prevents further steps', async () => {
  const d = deps(); let finish; d.poll.mockReturnValue(new Promise(resolve => { finish = resolve }))
  startNicOperation('k', { steps: ['addNicToVirtualMachine', 'updateDefaultNicForVirtualMachine'] }, d)
  await flush(); clearNicOperations(); finish({ jobstatus: 1 }); await flush(); expect(d.submit).toHaveBeenCalledTimes(1)
})
test('secondary IP uses its legacy response envelope', async () => {
  const d = deps(); d.submit.mockResolvedValue({ addiptovmnicresponse: { jobid: 'secondary' } })
  const op = startNicOperation('k', { steps: ['addIpToNic'] }, d); await flush()
  expect(op.status).toBe('complete'); expect(d.poll).toHaveBeenCalledTimes(1)
})
test('failed validation never submits and can abandon confirmed failure', async () => {
  const d = deps(); d.validate.mockRejectedValue(new Error('permission revoked'))
  const op = startNicOperation('k', { steps: ['removeNicFromVirtualMachine'] }, d); await flush()
  expect(op.status).toBe('failed'); expect(d.submit).not.toHaveBeenCalled(); op.abandon()
  expect(startNicOperation('k', { steps: ['updateVmNic'] }, deps())).not.toBe(op)
  await flush()
})
test('server API errors preserve the cause and allow retry of the rejected step', async () => {
  const d = deps(); d.submit.mockRejectedValueOnce({ response: { data: { addnictovirtualmachineresponse: { errorcode: 431, errortext: 'NIC already attached' } } } })
  const op = startNicOperation('k', { steps: ['addNicToVirtualMachine'] }, d); await flush()
  expect(op.status).toBe('failed'); expect(op.error).toBe('NIC already attached')
  op.resume(); await flush(); expect(op.status).toBe('complete')
})
test('default change failure preserves the attached NIC and never attaches twice', async () => {
  const d = deps(); d.poll.mockResolvedValueOnce({ jobstatus: 1 }).mockResolvedValueOnce({ jobstatus: 2, jobresult: { errortext: 'denied' } }).mockResolvedValue({ jobstatus: 1 })
  const op = startNicOperation('k', { steps: ['addNicToVirtualMachine', 'updateDefaultNicForVirtualMachine'], nic: { id: 'attached' } }, d); await flush()
  expect(op.stage).toBe(1); expect(op.nic.id).toBe('attached')
  op.resume(); await flush(); expect(op.status).toBe('complete'); expect(d.submit).toHaveBeenCalledTimes(3)
})
test('lost write response can advance only after positive readback without resubmission', async () => {
  const d = deps(); d.submit.mockRejectedValue(new Error('lost response')); d.reconcile.mockResolvedValue(true)
  const op = startNicOperation('k', { steps: ['addNicToVirtualMachine'] }, d); await flush()
  expect(op.status).toBe('unknown'); op.resume(); await flush()
  expect(op.status).toBe('complete'); expect(d.submit).toHaveBeenCalledTimes(1); expect(d.poll).not.toHaveBeenCalled()
})
