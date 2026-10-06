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

// Use persisted forwarding rules; VM array order does not define SSH ports.
export function nodeSshPorts (vm, network, rules = []) {
  if (network?.type === 'Shared' || network?.ip4routing) return [22]
  return managementPortsForVm(vm, rules, 22)
}

function managementPortsForVm (vm, rules, privatePort) {
  if (!vm?.id) return []
  return [...new Set(rules.filter(rule => {
    const publicPort = Number(rule.publicport)
    return rule.virtualmachineid === vm.id &&
      String(rule.protocol).toLowerCase() === 'tcp' &&
      String(rule.state).toLowerCase() !== 'revoke' &&
      Number(rule.privateport) === privatePort &&
      Number(rule.privateendport ?? rule.privateport) === privatePort &&
      Number(rule.publicendport ?? rule.publicport) === publicPort &&
      Number.isInteger(publicPort) && publicPort > 0 && publicPort <= 65535
  }).map(rule => Number(rule.publicport)))].sort((a, b) => a - b)
}

export function clusterManagementPorts (clusters, rules) {
  return [...new Set(clusters.flatMap(cluster => (cluster.virtualmachines || []).flatMap(vm => [
    ...managementPortsForVm(vm, rules, 22),
    ...(vm.iscontrolnode ? managementPortsForVm(vm, rules, 6443) : [])
  ])))].sort((a, b) => a - b)
}

export async function listAllKubernetesPortRules (getAPI, ipaddressid) {
  return listAll(getAPI, 'listPortForwardingRules', 'listportforwardingrulesresponse', 'portforwardingrule', { ipaddressid })
}

export async function listKubernetesClustersForIp (getAPI, ipaddressid) {
  const clusters = await listAll(getAPI, 'listKubernetesClusters', 'listkubernetesclustersresponse', 'kubernetescluster', {})
  return clusters.filter(cluster => cluster.ipaddressid === ipaddressid)
}

async function listAll (getAPI, command, responseKey, itemKey, params) {
  const all = []
  for (let page = 1; ; page++) {
    const response = await getAPI(command, { ...params, listAll: true, page, pagesize: 100 })
    const body = response[responseKey] || {}
    const items = body[itemKey] || []
    all.push(...items)
    if (!items.length || all.length >= Number(body.count || items.length)) return all
  }
}
