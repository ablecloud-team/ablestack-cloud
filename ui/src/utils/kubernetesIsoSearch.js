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

import hash from 'hash.js'
import { kubernetesIsoCapability, kubernetesIsoContext } from '@/utils/kubernetesIsoCapabilities'
export const kubernetesIsoSearchFields = ['zoneid', 'arch', 'minimumsemanticversion', 'minimumkubernetesversionid']
export const kubernetesIsoSortFields = ['name', 'semanticversion', 'state', 'mincpunumber', 'minmemory']
const scalar = v => typeof v === 'string' || typeof v === 'number'
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(v)
export function validKubernetesMinimum (value) {
  return typeof value === 'string' && /^[0-9]+(?:\.[0-9]+){2,}$/.test(value) && value.split('.').every(v => Number.isSafeInteger(Number(v)) && Number(v) <= 2147483647)
}
export function compareKubernetesMinimum (a, b) {
  const left = a.split('.').map(Number); const right = b.split('.').map(Number)
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    if ((left[i] || 0) !== (right[i] || 0)) return (left[i] || 0) > (right[i] || 0) ? 1 : -1
  }
  return 0
}
export function kubernetesIsoListOwner (getters = {}, session) { return hash.sha256().update(kubernetesIsoContext(getters, session)).digest('hex') }
export function kubernetesIsoListQuery (query = {}, pageSize = 20, getters = {}, session) {
  const owner = kubernetesIsoListOwner(getters, session)
  const source = query.kisoowner && query.kisoowner !== owner ? {} : query
  const result = {}
  for (const name of ['keyword', ...kubernetesIsoSearchFields, 'sortkey', 'sortorder']) {
    if (scalar(source[name]) && String(source[name]).trim()) result[name] = String(source[name]).trim()
  }
  if (result.sortkey && !result.sortorder) result.sortorder = 'asc'
  const page = Number(source.page); const size = Number(source.pagesize || pageSize)
  result.page = String(Number.isSafeInteger(page) && page > 0 ? page : 1)
  result.pagesize = String(Number.isSafeInteger(size) && size > 0 && size <= 500 ? size : 20)
  result.kisoowner = owner
  return result
}
export function kubernetesIsoSearchError (query, getters = {}) {
  const cap = kubernetesIsoCapability('read', getters)
  if (!cap.available) return 'message.kubernetes.iso.read.forbidden'
  if (!cap.schemaKnown && [...kubernetesIsoSearchFields, 'sortkey', 'sortorder'].some(k => query[k] !== undefined)) return 'message.kubernetes.iso.search.unsupported'
  const keys = ['keyword', ...kubernetesIsoSearchFields, 'sortkey', 'sortorder']
  for (const key of keys) if (query[key] !== undefined && cap.schemaKnown && !cap.parameters[key]) return 'message.kubernetes.iso.search.unsupported'
  if (query.minimumsemanticversion && query.minimumkubernetesversionid ||
      query.minimumsemanticversion && !validKubernetesMinimum(query.minimumsemanticversion) ||
      ['zoneid', 'minimumkubernetesversionid'].some(k => query[k] && !uuid(query[k])) ||
      query.arch && !['x86_64', 'aarch64', 's390x'].includes(query.arch) ||
      query.sortkey && !kubernetesIsoSortFields.includes(query.sortkey) ||
      query.sortorder && !['asc', 'desc'].includes(query.sortorder) ||
      query.sortorder && !query.sortkey) return 'message.kubernetes.iso.search.invalid'
  return ''
}
export function kubernetesIsoSearchQuery (query, opts, pageSize, getters, session) {
  const next = opts.reset ? {} : { ...query }
  if (Object.prototype.hasOwnProperty.call(opts, 'searchQuery')) {
    delete next.keyword
    if (opts.searchQuery) next.keyword = opts.searchQuery
  } else {
    for (const key of kubernetesIsoSearchFields) delete next[key]
    Object.assign(next, opts)
  }
  return kubernetesIsoListQuery({ ...next, page: '1', pagesize: pageSize }, pageSize, getters, session)
}
export function kubernetesIsoDetailQuery (query, getters, session) {
  const list = kubernetesIsoListQuery(query, undefined, getters, session)
  return { kisoreturn: JSON.stringify(list), kisoowner: list.kisoowner }
}
export function kubernetesIsoListLocation (route, getters, session) {
  try {
    if (route.query.kisoowner !== kubernetesIsoListOwner(getters, session) || typeof route.query.kisoreturn !== 'string' || route.query.kisoreturn.length > 8192) return { path: '/kubernetesiso' }
    const query = JSON.parse(route.query.kisoreturn)
    if (!query || typeof query !== 'object' || Array.isArray(query)) return { path: '/kubernetesiso' }
    return { path: '/kubernetesiso', query: kubernetesIsoListQuery(query, undefined, getters, session) }
  } catch (_) { return { path: '/kubernetesiso' } }
}
export function kubernetesIsoFilterMismatch (rows, params) {
  return rows.some(row => params.arch && row.arch !== params.arch ||
    params.zoneid && row.catalog.zone.kind !== 'all' && row.zoneid !== params.zoneid ||
    params.minimumsemanticversion && (!validKubernetesMinimum(row.semanticversion) || compareKubernetesMinimum(row.semanticversion, params.minimumsemanticversion) < 0))
}
