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

import { getAPI } from '@/api'
import FirewallRules from '@/views/network/FirewallRules.vue'
import PortForwarding from '@/views/network/PortForwarding.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const components = [FirewallRules, PortForwarding]
const state = () => ({
  resource: { id: 'ip' },
  kubernetesPortsRequest: 0,
  kubernetesPortsUnavailable: false,
  kubernetesManagementPorts: [],
  protectedManagementPorts: [],
  $store: { getters: { apis: { listKubernetesClusters: {}, listPortForwardingRules: {} } } }
})
const clusters = { listkubernetesclustersresponse: { kubernetescluster: [{ ipaddressid: 'ip', virtualmachines: [{ id: 'etcd' }] }] } }
const ports = { listportforwardingrulesresponse: { portforwardingrule: [{ virtualmachineid: 'etcd', protocol: 'tcp', publicport: 50007, privateport: 22 }] } }

test.each(components)('$name protects actual etcd/SSH port beyond contiguous VM indexes', async component => {
  getAPI.mockReset().mockResolvedValueOnce(clusters).mockResolvedValueOnce(ports)
  const vm = state()
  await component.methods.fetchKubernetesManagementPorts.call(vm)
  expect(vm.kubernetesManagementPorts).toEqual([50007])
  expect(vm.kubernetesPortsUnavailable).toBe(false)
})

test.each(components)('$name retains protection when a known cluster port lookup is forbidden', async component => {
  getAPI.mockReset().mockResolvedValueOnce(clusters).mockRejectedValueOnce(new Error('Forbidden'))
  const vm = state()
  await component.methods.fetchKubernetesManagementPorts.call(vm)
  expect(vm.kubernetesPortsUnavailable).toBe(true)
  vm.rangeIncludesPort = component.methods.rangeIncludesPort
  vm.rangeIncludesProtectedPort = () => false
  expect(component.methods.isProtectedManagementRule.call(vm, { protocol: 'tcp', publicport: 50007, privateport: 22, startport: 50007 })).toBe(true)
  expect(component.methods.isProtectedManagementRule.call(vm, { protocol: 'udp', publicport: 50007, privateport: 22, startport: 50007 })).toBe(false)
})

test.each(components)('$name ignores stale PF responses after the public IP changes', async component => {
  let finishOld
  let markStarted
  const started = new Promise(resolve => { markStarted = resolve })
  getAPI.mockReset().mockResolvedValueOnce(clusters).mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; markStarted() }))
  const vm = state()
  const first = component.methods.fetchKubernetesManagementPorts.call(vm)
  await started
  vm.resource = { id: 'new-ip' }
  getAPI.mockResolvedValueOnce({ listkubernetesclustersresponse: {} })
  await component.methods.fetchKubernetesManagementPorts.call(vm)
  finishOld(ports)
  await first
  expect(vm.kubernetesManagementPorts).toEqual([])
  expect(vm.kubernetesPortsUnavailable).toBe(false)
})
