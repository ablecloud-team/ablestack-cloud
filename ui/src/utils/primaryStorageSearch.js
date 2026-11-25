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

export const primaryStorageSearchFields = ['name', 'zoneid', 'podid', 'clusterid', 'hostid', 'ipaddress', 'path', 'scope', 'storageaccessgroup']

const listKeys = new Set([...primaryStorageSearchFields, 'keyword', 'q', 'status', 'filter', 'sortkey', 'sortorder', 'page', 'pagesize', 'storagecustomstats'])

export function primaryStorageListQuery (query = {}) {
  return Object.fromEntries(Object.entries(query).flatMap(([key, value]) => {
    if (!listKeys.has(key) || !['number', 'string'].includes(typeof value)) return []
    const text = String(value).trim()
    return text ? [[key, text]] : []
  }))
}

export function primaryStorageSearchQuery (current, input, pageSize) {
  const next = { ...primaryStorageListQuery(current), page: '1', pagesize: String(pageSize) }
  if ('searchQuery' in input) {
    delete next.name
    delete next.keyword
    delete next.q
    const keyword = input.searchQuery?.trim()
    if (keyword) next.keyword = next.q = keyword
  } else {
    primaryStorageSearchFields.forEach(key => { delete next[key] })
    Object.assign(next, primaryStorageListQuery(input))
    if (next.name || input.primaryStorageReset) {
      delete next.keyword
      delete next.q
    }
  }
  return next
}

export function primaryStorageStatusQuery (current, filter) {
  const next = { ...primaryStorageListQuery(current), page: '1' }
  delete next.status
  delete next.filter
  if (filter !== 'all') next.status = next.filter = filter
  return next
}

export function primaryStorageReadParams (query, { id, page, pagesize }, apiParams = {}) {
  if (id) return { id }
  const safe = primaryStorageListQuery(query)
  const supported = [...primaryStorageSearchFields, 'status', 'sortkey', 'sortorder', 'storagecustomstats']
  const filters = Object.fromEntries(supported.filter(key => apiParams[key] && safe[key] !== undefined).map(key => [key, safe[key]]))
  if (apiParams.keyword && (safe.keyword || safe.q)) filters.keyword = safe.keyword || safe.q
  return { ...filters, page, pagesize }
}
