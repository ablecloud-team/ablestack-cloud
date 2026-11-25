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

export const hostSearchFields = [
  'name', 'zoneid', 'podid', 'clusterid', 'arch', 'hypervisor',
  'outofbandmanagementenabled', 'outofbandmanagementpowerstate', 'version', 'managementserverid', 'storageaccessgroup'
]

export const hostSearchLabels = {
  outofbandmanagementenabled: 'label.outofbandmanagement.enable',
  outofbandmanagementpowerstate: 'label.powerstate',
  managementserverid: 'label.managementservername',
  storageaccessgroup: 'label.storageaccessgroups'
}

const connectionStates = {
  up: 'Up',
  down: 'Down',
  disconnected: 'Disconnected',
  alert: 'Alert'
}
const allocationStates = {
  enabled: 'Enabled',
  disabled: 'Disabled',
  maintenance: 'Maintenance'
}
const listKeys = new Set([
  ...hostSearchFields, 'keyword', 'q', 'filter', 'state', 'resourcestate',
  'sortkey', 'sortorder', 'page', 'pagesize'
])

export function hostListQuery (query = {}) {
  return Object.fromEntries(Object.entries(query).flatMap(([key, value]) => {
    if (!listKeys.has(key) || !['string', 'number', 'boolean'].includes(typeof value)) return []
    const text = String(value).trim()
    return text ? [[key, text]] : []
  }))
}

export function hostSearchQuery (query, input, pageSize) {
  const next = { ...hostListQuery(query), page: '1', pagesize: String(pageSize) }
  if (Object.prototype.hasOwnProperty.call(input, 'searchQuery')) {
    delete next.name
    delete next.keyword
    delete next.q
    const keyword = input.searchQuery?.trim()
    if (keyword) next.keyword = next.q = keyword
  } else {
    hostSearchFields.forEach(key => { delete next[key] })
    Object.assign(next, hostListQuery(input))
    if (next.name || input.hostReset) {
      delete next.keyword
      delete next.q
    }
  }
  return next
}

export function hostStatusQuery (query, filter) {
  const next = { ...hostListQuery(query), page: '1' }
  delete next.state
  delete next.resourcestate
  delete next.filter
  if (connectionStates[filter]) next.state = next.filter = filter
  if (allocationStates[filter]) next.resourcestate = next.filter = filter
  return next
}

export function hostReadParams (query, { id, page, pagesize, listall, type }, apiParams = {}) {
  if (id) return { id, type }
  const safe = hostListQuery(query)
  const filters = Object.fromEntries(hostSearchFields.filter(key => apiParams[key] && safe[key] !== undefined).map(key => [key, safe[key]]))
  if (apiParams.keyword && (safe.keyword || safe.q)) filters.keyword = safe.keyword || safe.q
  const state = connectionStates[(safe.state || '').toLowerCase()]
  const resourceState = allocationStates[(safe.resourcestate || '').toLowerCase()]
  if (apiParams.state && state) filters.state = state
  if (apiParams.resourcestate && resourceState) filters.resourcestate = resourceState
  return { ...filters, page, pagesize, listall, type }
}
