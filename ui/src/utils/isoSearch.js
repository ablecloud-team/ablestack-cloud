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

import { isoBoolean, isoAvailability } from './isoModel'
import { imageAccountScope, invalidImageAccountScope } from './imageAccountScope'

export const isoSearchFields = ['name', 'zoneid', 'tags', 'arch', 'oscategoryid', 'storageid', 'imagestoreid', 'bootable', 'isready', 'ispublic', 'hypervisor', 'userdataid']
export const isoSortFields = ['name', 'state', 'ostypename', 'arch', 'size', 'account', 'project']
const booleans = ['bootable', 'isready', 'ispublic']
const ids = ['zoneid', 'oscategoryid', 'storageid', 'imagestoreid', 'userdataid']
const filters = ['all', 'self', 'shared', 'featured', 'community', 'executable', 'selfexecutable', 'sharedexecutable']
const scalar = v => ['string', 'number', 'boolean'].includes(typeof v)
const object = v => !!v && typeof v === 'object' && !Array.isArray(v)
const uuid = v => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const own = (o, k) => Object.prototype.hasOwnProperty.call(o || {}, k)
const paging = (v, fallback, max = Number.MAX_SAFE_INTEGER) => scalar(v) && /^\d+$/.test(String(v)) && Number.isSafeInteger(Number(v)) && Number(v) > 0 && Number(v) <= max ? String(Number(v)) : String(fallback)
export const isoDefaultFilter = getters => ['Admin', 'DomainAdmin'].includes(getters.userInfo?.roletype) ? 'all' : 'self'
// This is a navigation marker, never authorization or a session credential.
export function isoListOwner (getters = {}) {
  const user = getters.userInfo || {}
  const params = getters.apis?.listIsos?.params
  const fields = Array.isArray(params) ? params.filter(p => p && isoSearchFields.includes(p.name)).map(p => p.name).sort() : []
  return JSON.stringify([user.id || '', user.roleid || '', user.roletype || '', user.account || '', user.domainid || '', getters.project?.id || '', !!getters.listAllProjects, !!getters.apis?.listIsos, fields])
}
export function isoListQuery (query = {}, pageSize = 20, getters = {}) {
  query = object(query) ? query : {}
  const owner = isoListOwner(getters)
  if (query.isoowner !== undefined && query.isoowner !== owner) { query = {}; pageSize = getters.defaultListViewPageSize || 20 }
  const result = {}
  const filter = query.filter ?? query.isofilter ?? (imageAccountScope(query, 'self').domainid ? 'self' : isoDefaultFilter(getters))
  Object.assign(result, imageAccountScope(query, filter))
  result.filter = result.isofilter = scalar(filter) ? String(filter) : '[invalid]'
  const keyword = query.keyword ?? query.q
  if (keyword !== undefined && keyword !== '') result.keyword = result.q = scalar(keyword) ? String(keyword) : '[invalid]'
  for (const key of isoSearchFields.filter(k => k !== 'tags')) {
    const v = query[key]
    if (v !== undefined && v !== '') result[key] = scalar(v) ? String(v) : '[invalid]'
  }
  // Retain only bounded flat tag pairs. Never restore route/action/server scope fields.
  const indices = [...new Set(Object.keys(query).map(k => /^tags\[(\d{1,2})\]\.(key|value)$/.exec(k)?.[1]).filter(Boolean))].sort((a, b) => Number(a) - Number(b)).slice(0, 10)
  indices.forEach((index, n) => {
    for (const field of ['key', 'value']) {
      const v = query['tags[' + index + '].' + field]
      if (v !== undefined) result['tags[' + n + '].' + field] = scalar(v) ? String(v) : '[invalid]'
    }
  })
  if (isoSortFields.includes(query.sortkey) && ['asc', 'desc'].includes(query.sortorder)) { result.sortkey = query.sortkey; result.sortorder = query.sortorder }
  result.page = paging(query.page, 1)
  result.pagesize = paging(query.pagesize ?? pageSize, 20, 500)
  result.isoowner = owner
  return result
}
export function isoSearchQuery (query, options = {}, pageSize, getters) {
  const next = { ...query }
  for (const k of Object.keys(next)) if (isoSearchFields.includes(k) || k.startsWith('tags[') || ['keyword', 'q'].includes(k)) delete next[k]
  if (own(options, 'searchQuery')) {
    if (options.searchQuery) next.keyword = next.q = options.searchQuery
  } else Object.assign(next, options)
  return isoListQuery({ ...next, page: '1', pagesize: pageSize }, pageSize, getters)
}
export function isoSearchError (query, metadata = {}, getters = {}) {
  if (invalidImageAccountScope(query)) return query.account === undefined ? 'message.image.domain.scope.invalid' : 'message.image.account.scope.invalid'
  if (!filters.includes(query.isofilter) || (query.isofilter === 'all' && !['Admin', 'DomainAdmin', 'ResourceAdmin'].includes(getters.userInfo?.roletype))) return 'message.iso.search.invalid'
  for (const key of isoSearchFields.filter(k => k !== 'tags')) {
    if (!own(query, key)) continue
    if (!own(metadata, key)) return 'message.iso.search.unsupported'
    if (booleans.includes(key) && !['true', 'false'].includes(query[key])) return 'message.iso.search.invalid'
    if (ids.includes(key) && !uuid(query[key])) return 'message.iso.search.invalid'
    if (key === 'arch' && !['x86_64', 'aarch64', 's390x'].includes(query[key])) return 'message.iso.search.invalid'
    if (key === 'hypervisor' && !['KVM', 'VMware', 'XenServer', 'Hyperv', 'BareMetal', 'Ovm', 'Ovm3', 'LXC', 'Simulator', 'External', 'None', 'Any'].includes(query[key])) return 'message.iso.search.invalid'
    if (query[key] === '[invalid]') return 'message.iso.search.invalid'
    if (['storageid', 'imagestoreid'].includes(key) && !['Admin', 'DomainAdmin'].includes(getters.userInfo?.roletype)) return 'message.iso.search.unsupported'
  }
  for (const key of Object.keys(query).filter(k => k.startsWith('tags['))) {
    if (!own(metadata, 'tags')) return 'message.iso.search.unsupported'
    const index = key.match(/\[(\d+)\]/)[1]
    if (!query['tags[' + index + '].key']?.trim() || query[key] === '[invalid]') return 'message.iso.search.invalid'
  }
  return ''
}
export function isoRequestParams (params, detail = false, getters = {}) {
  const ownedScope = !detail && imageAccountScope(params, params.filter ?? params.isofilter ?? 'self').domainid
  const projectid = ownedScope ? undefined : getters.listAllProjects ? '-1' : getters.project?.id
  const showicon = isoBoolean(params.showicon)
  const context = { ignoreproject: true, ...(projectid ? { projectid } : {}), ...(showicon !== undefined ? { showicon } : {}) }
  if (detail) return { id: params.id, listall: true, ...context }
  const result = { listall: true, showunique: 'true', ...isoListQuery(params, 20, getters), ...context }
  for (const key of ['filter', 'q', 'isoowner', 'sortkey', 'sortorder']) delete result[key]
  return result
}
export function isoDetailQuery (query, getters) {
  const list = isoListQuery(query, 20, getters)
  return { isoreturn: JSON.stringify(list), isoowner: list.isoowner }
}
export function isoDetailLocation (route, record, getters, tab) {
  return { path: '/iso/' + record.id, query: { ...isoDetailQuery(route.query, getters), ...(tab ? { tab } : {}) } }
}
export function isoListLocation (route, getters) {
  try {
    if (route.query.isoowner !== isoListOwner(getters) || typeof route.query.isoreturn !== 'string' || route.query.isoreturn.length > 16384) return { path: '/iso' }
    const query = JSON.parse(route.query.isoreturn)
    return object(query) ? { path: '/iso', query: isoListQuery(query, 20, getters) } : { path: '/iso' }
  } catch (_) { return { path: '/iso' } }
}
export function isoFilterMismatch (rows, params) {
  return rows.some(row => booleans.some(k => params[k] !== undefined && isoBoolean(row[k]) !== isoBoolean(params[k])) ||
    (params.userdataid && String(row.userdataid || '').toLowerCase() !== params.userdataid.toLowerCase()) ||
    (params.hypervisor && params.hypervisor !== 'None' && String(row.hypervisor || '').toLowerCase() !== params.hypervisor.toLowerCase()))
}
export function isoFilterChips (query, getters = {}) {
  const keys = [...isoSearchFields.filter(k => k !== 'tags'), 'isofilter']
  if (!query.account && imageAccountScope(query, query.isofilter).domainid && !invalidImageAccountScope(query)) keys.unshift('domainid')
  return [...keys.filter(k => own(query, k) && (k !== 'isofilter' || query[k] !== isoDefaultFilter(getters))).map(key => ({ key, value: query[key], isTag: false })),
    ...Object.keys(query).filter(k => /^tags\[\d+\]\.key$/.test(k)).map(k => {
      const tagIdx = k.match(/\[(\d+)\]/)[1]
      return { key: query[k], value: query['tags[' + tagIdx + '].value'] || '', isTag: true, tagIdx }
    })]
}
export function isoColumn (column, query = {}, t = v => v) {
  const widths = { state: 120, ostypename: 148, arch: 96, size: 96, account: 112, project: 120, order: 168, filtercolumn: 28 }
  const sortable = isoSortFields.includes(column.key) && !!column.sorter
  const sorter = !sortable ? false : column.key === 'state' ? (a, b) => String(t(isoAvailability(a).labelKey)).localeCompare(String(t(isoAvailability(b).labelKey))) : column.sorter
  return { ...column, ...(column.key === 'name' ? { width: undefined } : { width: widths[column.key] || 120 }), ellipsis: column.key === 'order' ? false : { showTitle: false }, sorter, sortOrder: sortable && query.sortkey === column.key ? (query.sortorder === 'asc' ? 'ascend' : 'descend') : null }
}
