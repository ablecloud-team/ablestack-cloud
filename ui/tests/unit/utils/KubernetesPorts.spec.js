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

import { nodeSshPorts, clusterManagementPorts, listAllKubernetesPortRules, listKubernetesClustersForIp } from '@/utils/kubernetesPorts'

const rule = (id, publicport, privateport = 22) => ({ virtualmachineid: id, protocol: 'tcp', publicport, privateport, state: 'Active' })
const vms = [{ id: 'new-worker' }, { id: 'control', iscontrolnode: true }, { id: 'etcd', isetcdnode: true }, { id: 'worker' }]
const rules = [rule('control', 2222), rule('worker', 2230), rule('new-worker', 2225), rule('etcd', 50007), rule('control', 6443, 6443)]

test('shuffled VM order and SSH gaps use UUID mappings including separate etcd ports', () => {
  expect(vms.map(vm => nodeSshPorts(vm, {}, rules))).toEqual([[2225], [2222], [50007], [2230]])
  expect(clusterManagementPorts([{ virtualmachines: vms }], rules)).toEqual([2222, 2225, 2230, 6443, 50007])
})

test('multiple active SSH mappings are sorted and deduplicated; missing mappings are unknown', () => {
  expect(nodeSshPorts({ id: 'control' }, {}, [...rules, rule('control', 8022), rule('control', 2222)])).toEqual([2222, 8022])
  expect(nodeSshPorts({ id: 'missing' }, {}, rules)).toEqual([])
  expect(nodeSshPorts({}, {}, [{ protocol: 'tcp', privateport: 22, publicport: 2222 }])).toEqual([])
})

test.each([{ type: 'Shared' }, { ip4routing: true }])('direct routing uses port 22: %p', network => {
  expect(nodeSshPorts(vms[0], network, [])).toEqual([22])
})

test('revoke, UDP, port ranges, non-SSH mappings and unrelated VM rules are excluded', () => {
  const rejected = [
    { ...rule('worker', 2223), state: 'Revoke' },
    { ...rule('worker', 2224), protocol: 'udp' },
    { ...rule('worker', 2226), privateendport: 23 },
    { ...rule('worker', 2227), publicendport: 2228 },
    rule('worker', 0), rule('worker', 65536), rule('worker', 18080, 8080), rule('other-cluster', 2229),
    rule('worker', 6444, 6443)
  ]
  expect(nodeSshPorts({ id: 'worker' }, {}, rejected)).toEqual([])
  expect(clusterManagementPorts([{ virtualmachines: vms }], rejected)).toEqual([])
})

test('all PF pages are read so late etcd/worker mappings are retained', async () => {
  const api = jest.fn()
    .mockResolvedValueOnce({ listportforwardingrulesresponse: { count: 2, portforwardingrule: [rules[0]] } })
    .mockResolvedValueOnce({ listportforwardingrulesresponse: { count: 2, portforwardingrule: [rules[3]] } })
  expect(await listAllKubernetesPortRules(api, 'source-ip')).toEqual([rules[0], rules[3]])
  expect(api.mock.calls.map(call => call[1].page)).toEqual([1, 2])
  expect(api.mock.calls[1][1].ipaddressid).toBe('source-ip')
})

test('network sharing does not claim unrelated public IP rules as cluster management ports', async () => {
  const api = jest.fn().mockResolvedValue({
    listkubernetesclustersresponse: {
      kubernetescluster: [
        { id: 'ours', ipaddressid: 'source-ip', networkid: 'network' },
        { id: 'other', ipaddressid: 'other-ip', networkid: 'network' }
      ]
    }
  })
  expect((await listKubernetesClustersForIp(api, 'source-ip')).map(cluster => cluster.id)).toEqual(['ours'])
})
