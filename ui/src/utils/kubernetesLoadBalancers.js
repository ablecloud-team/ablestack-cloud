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

// Read every page. A missing/failed response is not an empty successful inventory.
export async function listKubernetesNetworkResources (getAPI, command, responseKey, itemKey, params) {
  const all = []
  const ids = new Set()
  for (let page = 1; ; page++) {
    const response = await getAPI(command, { ...params, listAll: true, page, pagesize: 100 })
    const body = response[responseKey]
    if (!body || body.errorcode) throw new Error('Kubernetes network inventory is unavailable')
    const items = body[itemKey] || []
    if (!Array.isArray(items)) throw new Error('Invalid Kubernetes network inventory')
    for (const item of items) {
      const id = item.id || item.loadbalancerruleinstance?.id
      if (!id || ids.has(id)) throw new Error('Repeated or unidentified Kubernetes network resource')
      ids.add(id)
      all.push(item)
    }
    const count = Number(body.count)
    if (Number.isFinite(count) && count > all.length && !items.length) throw new Error('Incomplete Kubernetes network inventory')
    if (!items.length || (Number.isFinite(count) ? all.length >= count : items.length < 100)) return all
  }
}

export function clusterApiAddress (cluster) {
  try { return new URL(cluster.endpoint).hostname.replace(/^\[|\]$/g, '') } catch (_) { return '' }
}

export function kubernetesLoadBalancerOwner (cluster, ip, rule, backends) {
  if (!cluster.id || !cluster.networkid || ip.associatednetworkid !== cluster.networkid) return null
  if (cluster.projectid ? ip.projectid !== cluster.projectid : ip.account !== cluster.account || ip.domainid !== cluster.domainid) return null
  const tags = new Map()
  for (const tag of rule.tags || []) {
    if (tags.has(tag.key)) return null
    tags.set(tag.key, tag.value)
  }
  const serviceUID = tags.get('mold.k8s.service-uid')
  if (serviceUID && ip.allocationgeneration &&
      tags.get('mold.k8s.cluster-uid') === cluster.id &&
      tags.get('mold.k8s.network-uid') === cluster.networkid &&
      tags.get('mold.k8s.ip-uid') === ip.id &&
      tags.get('mold.k8s.ip-generation') === ip.allocationgeneration) return { kind: 'service', serviceUID }
  // Never infer API ownership from a user-controlled rule name or port alone.
  const controls = (cluster.virtualmachines || []).filter(vm => vm.iscontrolnode).map(vm => vm.id).sort()
  const actual = [...new Set(backends.map(vm => vm.id))].sort()
  if (!tags.size && ip.ipaddress === clusterApiAddress(cluster) &&
      Number(rule.publicport) === 6443 && Number(rule.privateport) === 6443 &&
      String(rule.protocol || 'tcp').toLowerCase() === 'tcp' && controls.length &&
      JSON.stringify(actual) === JSON.stringify(controls)) return { kind: 'api', serviceUID: '' }
  return null
}
