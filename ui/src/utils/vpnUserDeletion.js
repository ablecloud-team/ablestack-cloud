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

const isUuid = value => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)

export const canDeleteVpnUsers = getters =>
  ['listVpnUsers', 'removeVpnUser', 'queryAsyncJobResult'].every(api => api in (getters.apis || {}))

export function vpnUserDeleteTarget (row) {
  if (!isUuid(row?.id) || !row.username) return null
  const { id, username, state, projectid, project, account, domainid, domain, domainpath } = row
  if (projectid || project) {
    if (!isUuid(projectid)) return null
    return { id, username, state, projectid, project }
  }
  if (!account || !isUuid(domainid)) return null
  return { id, username, state, account, domainid, domain, domainpath }
}

export function vpnUserDeleteTargets (ids, rows) {
  const targets = [...new Set(ids)].map(id => vpnUserDeleteTarget(rows.find(row => row.id === id)))
  return targets.every(Boolean) ? targets : []
}

export function vpnUserDeleteParams ({ username, projectid, account, domainid }) {
  return projectid ? { username, projectid } : { username, account, domainid, ignoreproject: true }
}

export async function vpnUserDeleteTargetExists (get, target) {
  const { username, ...owner } = vpnUserDeleteParams(target)
  const response = await get('listVpnUsers', { ...owner, id: target.id, listall: true }, { preserveOnFailure: true, timeout: 15000 })
  const rows = response?.listvpnusersresponse?.vpnuser
  if (rows?.length !== 1) return false
  const row = vpnUserDeleteTarget(rows[0])
  return row?.id === target.id && row.username === username && row.projectid === target.projectid &&
    row.account === target.account && row.domainid === target.domainid
}
