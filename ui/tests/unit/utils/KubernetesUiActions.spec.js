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

import { externalClusterParams, kubernetesNodeActions } from '@/utils/kubernetesClusterActions'
import { validPortRange, validForwardPorts, loadBalancerIsProtected, selectedBackendMap } from '@/utils/networkRuleForm'

test('external registration excludes every deployment field and uses one ownership scope', () => {
  const payload = externalClusterParams({ name: 'external', zoneid: 'zone', size: 3, serviceofferingid: 'offering', enablecsi: true, autoscalingenabled: true, hypervisor: 'kvm', networkid: 'net', kubernetesversionid: 'version', keypair: 'key', description: 'registered' }, { account: 'alice', domainid: 'domain', projectid: 'project' })
  expect(payload).toEqual({ name: 'external', zoneid: 'zone', clustertype: 'ExternalManaged', networkid: 'net', kubernetesversionid: 'version', keypair: 'key', description: 'registered', projectid: 'project' })
})
test('managed and external primary actions follow cluster state and API permissions', () => {
  const apis = { scaleKubernetesCluster: {}, addNodesToKubernetesCluster: {}, addVirtualMachinesToKubernetesCluster: {}, removeVirtualMachinesFromKubernetesCluster: {} }
  expect(kubernetesNodeActions({ clustertype: 'CloudManaged', state: 'Running' }, apis)).toMatchObject({ primary: 'scaleKubernetesCluster', canPrimary: true, canAdd: true, canRemove: false })
  expect(kubernetesNodeActions({ clustertype: 'ExternalManaged', state: 'Running', virtualmachines: [{ id: 'vm' }] }, apis)).toEqual({ primary: 'addVirtualMachinesToKubernetesCluster', canPrimary: true, canAdd: false, canRemove: true })
  expect(kubernetesNodeActions({ clustertype: 'ExternalManaged', state: 'Running' }, {}).canPrimary).toBe(false)
  expect(kubernetesNodeActions({ clustertype: 'CloudManaged', state: 'Upgrading' }, apis).canPrimary).toBe(false)
})
test.each([[0, 1], [1, 65536], [10, 9], [1.5, 2], [null, 2], ['', 2]])('invalid port range %s..%s', (start, end) => expect(validPortRange(start, end)).toBe(false))
test('single ports and equal forward ranges are accepted; unequal ranges rejected', () => {
  expect(validPortRange(443, null)).toBe(true)
  expect(validForwardPorts({ publicport: 80, publicendport: 82, privateport: 8080, privateendport: 8082 })).toBe(true)
  expect(validForwardPorts({ publicport: 80, publicendport: 82, privateport: 8080, privateendport: 8081 })).toBe(false)
})
test('ownership guard protects verified API/Service rules and unknown reserved tags', () => {
  expect(loadBalancerIsProtected({ id: 'api', tags: [] }, { api: { kind: 'api' } })).toBe(true)
  expect(loadBalancerIsProtected({ id: 'unknown', tags: [{ key: 'mold.k8s.service-uid', value: 'possibly-stale' }] })).toBe(true)
  expect(loadBalancerIsProtected({ id: 'operator', tags: [{ key: 'team', value: 'ui' }] })).toBe(false)
})
test('backend payload uses selected VM and NIC addresses only, including VPC network', () => {
  const selection = { vmA: { ips: ['10.0.0.2', '10.0.0.3'] }, vmB: { ips: ['10.0.0.4'] } }
  expect(selectedBackendMap(selection, true, 'tier')).toEqual({ 'vmidipmap[0].vmid': 'vmA', 'vmidipmap[0].vmip': '10.0.0.2', 'vmidipmap[0].vmnetworkid': 'tier', 'vmidipmap[1].vmid': 'vmA', 'vmidipmap[1].vmip': '10.0.0.3', 'vmidipmap[1].vmnetworkid': 'tier', 'vmidipmap[2].vmid': 'vmB', 'vmidipmap[2].vmip': '10.0.0.4', 'vmidipmap[2].vmnetworkid': 'tier' })
  expect(selectedBackendMap({}, false)).toEqual({})
})
