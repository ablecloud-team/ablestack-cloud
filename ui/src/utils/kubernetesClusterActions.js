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

import { partialScaleRecoverySize } from '@/utils/kubernetesScaleRecovery'

export function kubernetesNodeActions (cluster, apis) {
  const external = cluster.clustertype === 'ExternalManaged'
  const stable = ['Created', 'Running', 'Stopped'].includes(cluster.state)
  return {
    primary: external ? 'addVirtualMachinesToKubernetesCluster' : 'scaleKubernetesCluster',
    canPrimary: external ? !!apis.addVirtualMachinesToKubernetesCluster && cluster.state === 'Running'
      : !!apis.scaleKubernetesCluster && (stable || partialScaleRecoverySize(cluster) !== null),
    canAdd: !external && !!apis.addNodesToKubernetesCluster && ['Running', 'Alert'].includes(cluster.state),
    canRemove: external && !!apis.removeVirtualMachinesFromKubernetesCluster && cluster.state === 'Running' && (cluster.virtualmachines || []).length > 0
  }
}

export function externalClusterParams (form, owner = {}) {
  const params = { name: form.name, zoneid: form.zoneid, clustertype: 'ExternalManaged' }
  for (const field of ['description', 'kubernetesversionid', 'networkid', 'keypair']) {
    if (form[field]) params[field] = form[field]
  }
  if (owner.projectid) params.projectid = owner.projectid
  else if (owner.account) { params.account = owner.account; params.domainid = owner.domainid }
  return params
}
