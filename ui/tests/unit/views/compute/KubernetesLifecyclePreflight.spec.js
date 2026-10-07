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
import KubernetesNodeNotice from '@/views/compute/KubernetesNodeNotice.vue'
import KubernetesLoadBalancers from '@/views/compute/KubernetesLoadBalancers.vue'
import KubernetesStoragePreflight from '@/views/compute/KubernetesStoragePreflight.vue'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
beforeEach(() => getAPI.mockReset())

test('VM warning uses exact cluster membership, not an instance-name prefix', async () => {
  const state = { resource: { id: 'vm', account: 'owner', domainid: 'domain' }, request: 0, $store: { getters: { apis: { listKubernetesClusters: {} } } } }
  getAPI.mockResolvedValueOnce({
    listkubernetesclustersresponse: {
      count: 2,
      kubernetescluster: [
        { id: 'foreign', name: 'vm-prefix', clustertype: 'CloudManaged', virtualmachines: [{ id: 'other' }] },
        { id: 'actual', clustertype: 'CloudManaged', virtualmachines: [{ id: 'vm' }] }
      ]
    }
  })
  await KubernetesNodeNotice.methods.fetchCluster.call(state)
  expect(state.cluster.id).toBe('actual')
  expect(getAPI.mock.calls[0][1]).toMatchObject({ account: 'owner', domainid: 'domain' })
})

test('VM lookup failure and stale response cannot claim unmanaged or replace a new VM', async () => {
  const state = { resource: { id: 'vm' }, request: 0, $store: { getters: { apis: { listKubernetesClusters: {} } } } }
  getAPI.mockRejectedValueOnce(new Error('Denied'))
  await KubernetesNodeNotice.methods.fetchCluster.call(state)
  expect(state.failed).toBe(true)
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const request = KubernetesNodeNotice.methods.fetchCluster.call(state)
  state.resource = { id: 'new-vm' }
  finish({ listkubernetesclustersresponse: { count: 1, kubernetescluster: [{ id: 'old', clustertype: 'CloudManaged', virtualmachines: [{ id: 'vm' }] }] } })
  await request
  expect(state.cluster).toBeNull()
})

test('LB read failure clears previous rules and leaves a visible incomplete state', async () => {
  const state = { resource: { id: 'cluster', networkid: 'network' }, request: 0, rows: [{ id: 'stale' }], $notifyError: jest.fn() }
  getAPI.mockRejectedValueOnce(new Error('Forbidden'))
  await KubernetesLoadBalancers.methods.fetchRules.call(state)
  expect(state.rows).toEqual([])
  expect(state.failed).toBe(true)
  expect(state.busy).toBe(false)
})

test('late LB response cannot replace a newly selected cluster', async () => {
  const state = { resource: { id: 'old', networkid: 'network' }, request: 0, rows: [], $notifyError: jest.fn() }
  let finish
  getAPI.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const request = KubernetesLoadBalancers.methods.fetchRules.call(state)
  state.resource = { id: 'new', networkid: 'new-network' }
  finish({ listnetworksresponse: { count: 1, network: [{ id: 'network', type: 'Shared' }] } })
  await request
  expect(state.rows).toEqual([])
})

test('VPC access view preserves ingress allow/deny priority without using unrelated egress or ports', () => {
  const acl = KubernetesLoadBalancers.methods.accessRules([
    { id: 'all', number: 99, traffictype: 'Ingress', protocol: 'all', action: 'Deny', cidrlist: '0.0.0.0/0' },
    { id: 'allow', number: 10, traffictype: 'Ingress', protocol: 'tcp', startport: 18097, endport: 18097, action: 'Allow', cidrlist: '10.0.0.0/8' },
    { id: 'other', number: 1, traffictype: 'Ingress', protocol: 'tcp', startport: 443, endport: 443, action: 'Allow' },
    { id: 'egress', number: 2, traffictype: 'Egress', protocol: 'all', action: 'Allow' }
  ], { publicport: 18097, protocol: 'tcp' }, true)
  expect(acl).toHaveLength(2)
  expect(acl[0]).toContain('#10 Allow 10.0.0.0/8')
  expect(acl[1]).toContain('#99 Deny 0.0.0.0/0')
})

test('unreadable router storage is shown as unverified and request cleanup still completes', async () => {
  const state = { offerings: [], network: { id: 'network', type: 'Isolated' }, request: 0, $store: { getters: { apis: { listRouters: {} } } }, $t: k => k }
  getAPI.mockRejectedValueOnce(new Error('Denied'))
  await KubernetesStoragePreflight.methods.fetchPlacement.call(state)
  expect(state.warning).toBe(true)
  expect(state.busy).toBe(false)
})
