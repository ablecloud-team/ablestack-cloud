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

import KubernetesServiceTab from '@/views/compute/KubernetesServiceTab.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const refresh = resource => {
  const state = { resource, virtualmachines: [{ id: 'previous' }], instanceLoading: false }
  KubernetesServiceTab.methods.fetchInstances.call(state)
  return state
}

test('a VM losing NICs during scale-down cannot break other rows or leave loading active', () => {
  const resource = {
    virtualmachines: [
      { id: 'worker', state: 'Running', nic: [{ ipaddress: '10.1.0.5' }] },
      { id: 'removed', state: 'Expunging', ipaddress: 'stale', nic: [] },
      { id: 'joining', state: 'Starting' }
    ]
  }
  const original = JSON.parse(JSON.stringify(resource))
  const state = refresh(resource)
  expect(state.instanceLoading).toBe(false)
  expect(state.virtualmachines.map(vm => [vm.id, vm.state, vm.ipaddress])).toEqual([
    ['worker', 'Running', '10.1.0.5'], ['removed', 'Expunging', ''], ['joining', 'Starting', '']
  ])
  expect(resource).toEqual(original)
  state.resource = { virtualmachines: [resource.virtualmachines[0]] }
  KubernetesServiceTab.methods.fetchInstances.call(state)
  expect(state.virtualmachines.map(vm => vm.id)).toEqual(['worker'])
})

test('VM addresses use default guest NIC while maintaining control, external, etcd row order', () => {
  const state = refresh({
    virtualmachines: [
      { id: 'etcd', isetcdnode: true, nic: [{ ipaddress: '10.1.0.9' }] },
      { id: 'external', isexternalnode: true, nic: null },
      { id: 'control', iscontrolnode: true, nic: [{ ipaddress: '192.0.2.1' }, { isdefault: true, ipaddress: '10.1.0.2' }] },
      { id: 'worker', nic: [{ ipaddress: '10.1.0.5' }] }
    ]
  })
  expect(state.virtualmachines.map(vm => [vm.id, vm.ipaddress])).toEqual([
    ['control', '10.1.0.2'], ['worker', '10.1.0.5'], ['external', ''], ['etcd', '10.1.0.9']
  ])
})

test.each([{}, { virtualmachines: null }, { virtualmachines: [] }])('missing initial VM inventory clears old rows: %p', resource => {
  const state = refresh(resource)
  expect(state.virtualmachines).toEqual([])
  expect(state.instanceLoading).toBe(false)
})
