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

import { asnQuery } from './asnSearch'
import { isoListQuery } from './isoSearch'
import { kubernetesIsoListQuery } from './kubernetesIsoSearch'
import { kmsListApi, kmsQuery } from './kmsSearch'
import { publicIpQuery } from './publicIpSearch'
import { s2sGatewayQuery, vpnConnectionApi } from './s2sGatewaySearch'
import { snapshotPolicyListQuery } from './snapshotPolicyList'
import { normalizePolicySearch, policyProjectScope } from './snapshotPolicySearch'
import { storageSnapshotListQuery } from './storageSnapshotList'
import { normalizeSnapshotSearch } from './storageSnapshotSearch'
import { templateListQuery } from './templateSearch'
import { vnfListQuery } from './vnfSearch'
import { vpnUserListQuery } from './vpnUserSearch'

const normalizers = {
  asnumbers: ({ query }, size, getters) => asnQuery(query, size, getters),
  hsmprofile: (route, size, getters) => kmsQuery(route.query, kmsListApi(route), getters, size),
  iso: ({ query }, size, getters) => isoListQuery(query, size, getters),
  kmskey: (route, size, getters) => kmsQuery(route.query, kmsListApi(route), getters, size),
  kubernetesiso: ({ query }, size, getters, token) => kubernetesIsoListQuery(query, size, getters, token),
  publicip: ({ query }, size, getters) => publicIpQuery(query, size, getters),
  s2svpn: ({ query }, size, getters) => s2sGatewayQuery(query, size, getters),
  s2svpnconn: ({ query }, size, getters) => s2sGatewayQuery(query, size, getters, vpnConnectionApi),
  snapshot: ({ query }, size, getters) => storageSnapshotListQuery(normalizeSnapshotSearch({ ...query, projectid: query.projectid || getters.project?.id || (getters.listAllProjects ? '-1' : '0') })),
  snapshotpolicy: (route, size, getters) => snapshotPolicyListQuery(normalizePolicySearch({ ...route.query, projectid: policyProjectScope(route, getters) }), size),
  template: ({ query }, size) => templateListQuery(query, size),
  vnfapp: ({ query }, size, getters) => vnfListQuery(query, size, getters),
  vpnuser: ({ query }, size, getters) => vpnUserListQuery(query, size, getters)
}

export function listRouteQuery (route, pageSize, getters, token) {
  return normalizers[route.meta?.name || route.name]?.(route, pageSize, getters, token) ?? route.query
}

export function initialListLocation (route, getters, token) {
  const name = route.meta?.name
  if (!token || !Object.prototype.hasOwnProperty.call(normalizers, name) || route.params?.id ||
      Object.keys(route.query).length || !getters.apis?.[route.meta?.permission?.[0]]) return
  const size = Number(getters.defaultListViewPageSize)
  const query = listRouteQuery(route, Number.isInteger(size) && size > 0 ? size : 20, getters, token)
  // Set default query parameters before committing a navigation and rendering its view.
  return { path: route.path, query, hash: route.hash }
}
