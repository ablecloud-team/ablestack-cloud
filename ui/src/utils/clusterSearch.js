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

export const clusterStateLabel = (field, value) => {
  if (field === 'allocationstate') {
    return { enabled: 'state.enabled', disabled: 'state.disabled' }[String(value).toLowerCase()] || 'label.unknown'
  }
  return clusterFilterOptions[field]?.find(option => option.id.toLowerCase() === String(value).toLowerCase())?.name || 'label.unknown'
}

export const clusterSearchFields = ['name', 'zoneid', 'podid', 'arch', 'hypervisor', 'clustertype', 'managedstate', 'storageaccessgroup']

export const clusterFilterOptions = {
  clustertype: ['CloudManaged', 'ExternalManaged'].map(id => ({ id, name: 'label.cluster.type.' + id.toLowerCase() })),
  managedstate: ['Managed', 'PrepareUnmanaged', 'Unmanaged', 'PrepareUnmanagedError'].map(id => ({ id, name: 'label.cluster.managed.' + id.toLowerCase() }))
}

const listKeys = new Set([...clusterSearchFields, 'keyword', 'q', 'allocationstate', 'filter', 'sortkey', 'sortorder', 'page', 'pagesize'])
const allocationStates = { enabled: 'Enabled', disabled: 'Disabled' }

export function clusterListQuery (query = {}) {
  return Object.fromEntries(Object.entries(query).flatMap(([key, value]) => {
    if (!listKeys.has(key) || !['number', 'string'].includes(typeof value)) return []
    const text = String(value).trim()
    return text ? [[key, text]] : []
  }))
}

export function clusterSearchQuery (current, input, pageSize) {
  const query = { ...clusterListQuery(current), page: '1', pagesize: String(pageSize) }
  if (Object.prototype.hasOwnProperty.call(input, 'searchQuery')) {
    delete query.name
    delete query.keyword
    delete query.q
    const keyword = input.searchQuery?.trim()
    if (keyword) query.keyword = query.q = keyword
  } else {
    clusterSearchFields.forEach(key => { delete query[key] })
    Object.assign(query, clusterListQuery(input))
    if (query.name || input.clusterReset) {
      delete query.keyword
      delete query.q
    }
  }
  return query
}

export function clusterStatusQuery (current, filter) {
  const next = { ...clusterListQuery(current), page: '1' }
  delete next.allocationstate
  delete next.filter
  if (filter === 'enabled' || filter === 'disabled') {
    next.allocationstate = filter
    next.filter = filter
  }
  return next
}

export function clusterReadParams (query, { id, page, pagesize, showcapacities }, apiParams = {}) {
  if (id) return { id, showcapacities }
  const safe = clusterListQuery(query)
  const filters = Object.fromEntries(clusterSearchFields.filter(key => apiParams[key] && safe[key] !== undefined).map(key => [key, safe[key]]))
  if (apiParams.keyword && (safe.keyword || safe.q)) filters.keyword = safe.keyword || safe.q
  const allocationstate = allocationStates[(safe.allocationstate || '').toLowerCase()]
  if (apiParams.allocationstate && typeof allocationstate === 'string') filters.allocationstate = allocationstate
  return { ...filters, page, pagesize, showcapacities }
}
