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

import { getAPI, postAPI } from '@/api'
import ExternalKubernetesNodes from '@/views/compute/ExternalKubernetesNodes'
import ExternalKubernetesCluster from '@/views/compute/ExternalKubernetesCluster'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
beforeEach(() => { getAPI.mockReset(); postAPI.mockReset() })
const cluster = { id: 'cluster', clustertype: 'ExternalManaged', state: 'Running', zoneid: 'zone', account: 'owner', domainid: 'domain', networkid: 'net', virtualmachines: [] }
const nodeContext = removing => ({ removing, resource: cluster, busy: false, failed: false, selected: ['vm1'], isControl: true, $store: { getters: { apis: { addVirtualMachinesToKubernetesCluster: {}, removeVirtualMachinesFromKubernetesCluster: {} } } }, parentFetchData: jest.fn(), $emit: jest.fn(), $notifyError: jest.fn() })
test('external addition uses the synchronous mapping API and explicit control-node role', async () => {
  postAPI.mockResolvedValue({ addvirtualmachinestokubernetesclusterresponse: { success: true } })
  const vm = nodeContext(false)
  await ExternalKubernetesNodes.methods.submit.call(vm)
  expect(postAPI).toHaveBeenCalledWith('addVirtualMachinesToKubernetesCluster', { id: 'cluster', virtualmachineids: 'vm1', iscontrolnode: true })
  expect(vm.parentFetchData).toHaveBeenCalledTimes(1)
  expect(vm.$emit).toHaveBeenCalledWith('close-action')
})
test('external removal does not send managed node or control-role parameters', async () => {
  postAPI.mockResolvedValue({ removevirtualmachinesfromkubernetesclusterresponse: { virtualmachine: [{ id: 'vm1', success: true }] } })
  const vm = nodeContext(true)
  await ExternalKubernetesNodes.methods.submit.call(vm)
  expect(postAPI).toHaveBeenCalledWith('removeVirtualMachinesFromKubernetesCluster', { id: 'cluster', virtualmachineids: 'vm1' })
})
test('no selection and missing role permission prevent mapping submissions', async () => {
  const vm = nodeContext(false)
  vm.selected = []; await ExternalKubernetesNodes.methods.submit.call(vm)
  vm.selected = ['vm1']; vm.$store.getters.apis = {}; await ExternalKubernetesNodes.methods.submit.call(vm)
  expect(postAPI).not.toHaveBeenCalled()
})
test('mapping failure retains dialog and selection for retry', async () => {
  postAPI.mockRejectedValue(new Error('backend rejected role'))
  const vm = nodeContext(false)
  await ExternalKubernetesNodes.methods.submit.call(vm)
  expect(vm.$emit).not.toHaveBeenCalled()
  expect(vm.selected).toEqual(['vm1'])
  expect(vm.busy).toBe(false)
  expect(vm.$notifyError).toHaveBeenCalledTimes(1)
})
test('failed post-create lookup retries the same job rather than creating duplicate registration', async () => {
  postAPI.mockResolvedValue({ createkubernetesclusterresponse: { id: 'registered', jobid: 'job' } })
  getAPI.mockRejectedValueOnce(new Error('transient lookup')).mockResolvedValueOnce({ listkubernetesclustersresponse: { kubernetescluster: [{ ...cluster, id: 'registered' }] } })
  const vm = { form: { name: 'external', zoneid: 'zone' }, owner: {}, busy: false, createJob: null, continueNodes: true, registered: null, $store: { getters: { apis: { createKubernetesCluster: {} } } }, $pollJob: jest.fn().mockResolvedValue({ jobstatus: 1 }), $t: value => value, $notifyError: jest.fn(), parentFetchData: jest.fn(), $emit: jest.fn() }
  await ExternalKubernetesCluster.methods.submit.call(vm)
  expect(vm.createJob).toEqual({ id: 'registered', jobid: 'job' })
  await ExternalKubernetesCluster.methods.submit.call(vm)
  expect(postAPI).toHaveBeenCalledTimes(1)
  expect(vm.$pollJob.mock.calls.map(call => call[0].jobId)).toEqual(['job', 'job'])
  expect(vm.registered.id).toBe('registered')
  expect(vm.$emit).not.toHaveBeenCalled()
})
