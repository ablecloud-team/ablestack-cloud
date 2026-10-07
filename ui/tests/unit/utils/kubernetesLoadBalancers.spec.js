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

import { listKubernetesNetworkResources, kubernetesLoadBalancerOwner } from '@/utils/kubernetesLoadBalancers'

const cluster = { id: 'cluster', networkid: 'network', account: 'owner', domainid: 'domain', endpoint: 'https://192.0.2.4:6443/', virtualmachines: [{ id: 'control', iscontrolnode: true }, { id: 'worker' }] }
const ip = { id: 'ip', associatednetworkid: 'network', account: 'owner', domainid: 'domain', ipaddress: '192.0.2.4', allocationgeneration: 'new' }
const rule = { id: 'lb', tags: Object.entries({ 'mold.k8s.cluster-uid': 'cluster', 'mold.k8s.service-uid': 'service', 'mold.k8s.network-uid': 'network', 'mold.k8s.ip-generation': 'new', 'mold.k8s.ip-uid': 'ip' }).map(([key, value]) => ({ key, value })) }

test('Provider ownership requires all five tags and current network/account/allocation', () => {
  expect(kubernetesLoadBalancerOwner(cluster, ip, rule, [])).toEqual({ kind: 'service', serviceUID: 'service' })
  for (let i = 0; i < rule.tags.length; i++) expect(kubernetesLoadBalancerOwner(cluster, ip, { ...rule, tags: rule.tags.filter((_, n) => n !== i) }, [])).toBeNull()
  for (const altered of [{ ...ip, allocationgeneration: 'old' }, { ...ip, associatednetworkid: 'foreign' }, { ...ip, account: 'foreign' }, { ...ip, domainid: 'foreign' }, { ...ip, id: 'reused' }]) expect(kubernetesLoadBalancerOwner(cluster, altered, rule, [])).toBeNull()
  expect(kubernetesLoadBalancerOwner(cluster, ip, { ...rule, tags: [...rule.tags, rule.tags[0]] }, [])).toBeNull()
})

test('API ownership requires exact control backends, endpoint and ports; name is not proof', () => {
  const api = { name: 'api-lb', publicport: 6443, privateport: 6443, protocol: 'tcp' }
  expect(kubernetesLoadBalancerOwner(cluster, ip, api, [{ id: 'control' }])?.kind).toBe('api')
  expect(kubernetesLoadBalancerOwner(cluster, ip, api, [{ id: 'worker' }])).toBeNull()
  expect(kubernetesLoadBalancerOwner(cluster, { ...ip, ipaddress: '192.0.2.99' }, api, [{ id: 'control' }])).toBeNull()
  expect(kubernetesLoadBalancerOwner(cluster, ip, { ...api, privateport: 8080 }, [{ id: 'control' }])).toBeNull()
})

test('project scope prevents sibling-project inventory from being classified', () => {
  expect(kubernetesLoadBalancerOwner({ ...cluster, projectid: 'project' }, { ...ip, projectid: 'project' }, rule, [])?.kind).toBe('service')
  expect(kubernetesLoadBalancerOwner({ ...cluster, projectid: 'project' }, { ...ip, projectid: 'other' }, rule, [])).toBeNull()
})

test('pagination includes second page and preserves request scope', async () => {
  const api = jest.fn().mockResolvedValueOnce({ body: { count: 2, rows: [{ id: 'first' }] } }).mockResolvedValueOnce({ body: { count: 2, rows: [{ id: 'second' }] } })
  expect(await listKubernetesNetworkResources(api, 'list', 'body', 'rows', { projectid: 'project', associatednetworkid: 'network' })).toHaveLength(2)
  expect(api.mock.calls[1][1]).toMatchObject({ projectid: 'project', associatednetworkid: 'network', page: 2 })
})

test.each([{ }, { body: { count: 2, rows: [] } }])('missing or incomplete pages fail visibly: %p', async response => {
  await expect(listKubernetesNetworkResources(jest.fn().mockResolvedValue(response), 'list', 'body', 'rows', {})).rejects.toThrow()
})

test('repeated pages fail instead of looping or duplicating rules', async () => {
  const api = jest.fn().mockResolvedValue({ body: { count: 2, rows: [{ id: 'same' }] } })
  await expect(listKubernetesNetworkResources(api, 'list', 'body', 'rows', {})).rejects.toThrow('Repeated')
})
