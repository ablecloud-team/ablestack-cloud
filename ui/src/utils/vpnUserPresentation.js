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

export function vpnUserOwner (row, getters = {}) {
  const { apis = {}, userInfo = {} } = getters
  if (row.projectid || row.project) {
    return {
      type: 'label.project',
      name: row.project || row.projectid,
      location: isUuid(row.projectid) && apis.listProjects ? { path: '/project/' + row.projectid } : null
    }
  }
  const domainId = isUuid(row.domainid)
  const canLink = userInfo.roletype && userInfo.roletype !== 'User' && domainId
  return {
    type: 'label.account',
    name: row.account,
    domain: row.domainpath || row.domain || row.domainid,
    location: canLink && row.account && apis.listAccounts
      ? { path: '/account', query: { name: row.account, domainid: row.domainid, dataView: true } }
      : null,
    domainLocation: canLink && apis.listDomains ? { path: '/domain/' + row.domainid, query: { tab: 'details' } } : null
  }
}

export function vpnUserState (state) {
  switch (state) {
    case 'Add': return { color: 'pending', label: 'label.vpn.user.state.add' }
    case 'Active': return { color: 'active', label: 'label.vpn.user.state.active' }
    case 'Revoke': return { color: 'stopping', label: 'label.vpn.user.state.revoke' }
    default: return { color: '', label: 'label.unknown' }
  }
}

export function vpnUserVisibleColumns (columns) {
  return [...new Set(columns.map(key => ['account', 'domain', 'project'].includes(key) ? 'owners' : key))]
}
