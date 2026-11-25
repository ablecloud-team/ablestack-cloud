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

export const secondaryStorageSearchFields = ['name', 'zoneid', 'provider', 'protocol', 'readonly']
const fields = [...secondaryStorageSearchFields, 'keyword', 'page', 'pagesize', 'sortkey', 'sortorder']

export function secondaryStorageListQuery (query = {}) {
  return Object.fromEntries(fields.flatMap(key => {
    const value = query[key]
    if (key === 'readonly') return ['true', 'false'].includes(String(value)) ? [[key, String(value)]] : []
    if (!['string', 'number'].includes(typeof value)) return []
    const text = String(value).trim()
    return text ? [[key, text]] : []
  }))
}

export function secondaryStorageSearchQuery (query, input, pageSize) {
  const next = { ...secondaryStorageListQuery(query), page: '1', pagesize: String(pageSize) }
  if ('searchQuery' in input) {
    delete next.keyword
    delete next.name
    if (input.searchQuery?.trim()) next.keyword = input.searchQuery.trim()
    return next
  }
  secondaryStorageSearchFields.forEach(key => { delete next[key] })
  Object.assign(next, secondaryStorageListQuery(input))
  if (next.name || input.secondaryStorageReset) delete next.keyword
  return next
}

export function secondaryStorageReadParams (query, { id, page, pagesize }, supported) {
  if (id) return { id }
  const allowed = secondaryStorageListQuery(query)
  const filters = Object.fromEntries(Object.entries(allowed).filter(([key]) => supported?.[key] && !['page', 'pagesize'].includes(key)))
  // CIFS stores share the NFS image-store provider and differ by protocol.
  if (filters.provider === 'SMB/CIFS') {
    filters.provider = 'NFS'
    if (supported.protocol) filters.protocol = 'cifs'
  }
  return { ...filters, page, pagesize }
}
