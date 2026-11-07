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

export const guestNetworkSearchKeys = ['keyword', 'name', 'zoneid', 'domainid', 'account', 'type', 'restartrequired', 'displaynetwork', 'tags', 'networkofferingid', 'physicalnetworkid', 'forvpc']
export const guestNetworkRoute = route => route?.meta?.name === 'guestnetwork'
const present = value => value !== undefined && value !== null && value !== ''
const scopeKeys = ['domainid', 'account', 'zoneid', 'projectid']

export function networkSearchQuery (current, values, size) {
  const query = { ...current }
  if (Object.prototype.hasOwnProperty.call(values || {}, 'searchQuery')) {
    delete query.keyword; delete query.q
    if (values.searchQuery) query.keyword = query.q = String(values.searchQuery).trim()
  } else {
    Object.keys(query).filter(k => guestNetworkSearchKeys.includes(k) || k === 'q' || k.startsWith('tags[')).forEach(k => delete query[k])
    Object.entries(values || {}).filter(([k, v]) => present(v) && (guestNetworkSearchKeys.includes(k) || k.startsWith('tags['))).forEach(([k, v]) => { query[k] = typeof v === 'boolean' ? String(v) : v })
  }
  query.page = '1'; query.pagesize = String(size)
  return query
}

export function offeringNetworkQuery (offering, route = {}, getters = {}) {
  const query = Object.fromEntries(scopeKeys.filter(k => present(route.query?.[k])).map(k => [k, route.query[k]]))
  const project = query.projectid || getters.project?.id || (getters.listAllProjects ? '-1' : undefined)
  if (project) query.projectid = project
  return { ...query, networkofferingid: offering.id, page: '1', pagesize: '20' }
}

export function networkListQuery (query = {}) {
  return Object.fromEntries(Object.entries(query).filter(([k, v]) => present(v) && (guestNetworkSearchKeys.includes(k) || ['q', 'projectid', 'filter', 'networkfilter', 'page', 'pagesize', 'sortkey', 'sortorder'].includes(k) || k.startsWith('tags['))))
}
export function networkListLocation (route) {
  let query = route.query || {}
  if (typeof query.networkreturn === 'string' && query.networkreturn.length < 8192) {
    try { const saved = JSON.parse(query.networkreturn); if (saved && !Array.isArray(saved) && typeof saved === 'object') query = saved } catch (_) {}
  }
  return { path: '/guestnetwork', query: networkListQuery(query) }
}
export function networkDetailLocation (route, row, tab) {
  const list = { ...networkListQuery(route.query), ...(!route.query?.projectid && row.projectid ? { projectid: row.projectid } : {}) }
  return { path: '/guestnetwork/' + row.id, query: { ...(list.projectid ? { projectid: list.projectid } : {}), networkreturn: JSON.stringify(list), ...(tab ? { tab } : {}) } }
}

export function readNetworkPage (json, api, field, params) {
  const data = json?.[api.toLowerCase() + 'response']
  if (!data || data.errorcode || (data[field] !== undefined && !Array.isArray(data[field]))) throw new Error('Invalid network response')
  const rows = data[field] || []
  const count = data.count === undefined && !rows.length && Number(params.page) === 1 ? 0 : Number(data.count)
  if (!Number.isSafeInteger(count) || count < 0 || count < rows.length || new Set(rows.map(r => r.id)).size !== rows.length || rows.some(r => !r.id)) throw new Error('Invalid network page')
  if (params.networkofferingid && rows.some(r => r.networkofferingid !== params.networkofferingid)) throw new Error('Offering scope mismatch')
  return { rows, count }
}

export const networkPickerApi = kind => kind === 'networkofferingid' ? { api: 'listNetworkOfferings', field: 'networkoffering', label: 'label.network.offering' } : { api: 'listPhysicalNetworks', field: 'physicalnetwork', label: 'label.physical.network' }
export function networkPickerParams (kind, scope) {
  const keys = kind === 'networkofferingid' ? ['zoneid', 'domainid'] : ['zoneid']
  return Object.fromEntries(keys.filter(k => present(scope[k])).map(k => [k, scope[k]]))
}
