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

import KubernetesAddNodes from '@/views/compute/KubernetesAddNodes'
import KubernetesRemoveNodes from '@/views/compute/KubernetesRemoveNodes'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
for (const [component, method] of [[KubernetesAddNodes, 'addNodesToKubernetesCluster'], [KubernetesRemoveNodes, 'removeNodesFromKubernetesCluster']]) {
  const state = (status) => {
    return { resource: { id: 'cluster', name: 'test' }, form: { nodeids: ['vm'] }, formRef: { value: { validate: jest.fn().mockResolvedValue() } }, loading: false, pendingNodeJob: null, operationIncomplete: false, [method]: jest.fn().mockResolvedValue('job'), $pollJob: jest.fn().mockResolvedValue({ jobstatus: status }), $t: key => key, $notifyError: jest.fn(), parentFetchData: jest.fn(), closeAction: jest.fn() }
  }
  test(component.name + ' keeps selected VM after definite failure and permits a deliberate retry', async () => {
    const vm = state(2)
    await component.methods.handleSubmit.call(vm)
    expect(vm.form.nodeids).toEqual(['vm'])
    expect(vm.operationIncomplete).toBe(true)
    expect(vm.pendingNodeJob).toBe(null)
    expect(vm.closeAction).not.toHaveBeenCalled()
  })
  test(component.name + ' rechecks an unknown job without submitting duplicate node operations', async () => {
    const vm = state(0)
    await component.methods.handleSubmit.call(vm)
    expect(vm.pendingNodeJob).toBe('job')
    vm.$pollJob.mockResolvedValue({ jobstatus: 1 })
    await component.methods.handleSubmit.call(vm)
    expect(vm[method]).toHaveBeenCalledTimes(1)
    expect(vm.closeAction).toHaveBeenCalledTimes(1)
    expect(vm.pendingNodeJob).toBe(null)
  })
}
