// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { userDataDeleteIdentity, userDataDeleteParams } from '@/utils/userDataDeletion'

export { userDataDeleteTargets as cniDeleteTargets, userDataDeleteParams as cniDeleteParams, userDataDeleteLookup as cniDeleteLookup, userDataDeleteError as cniDeleteError } from '@/utils/userDataDeletion'

export function cniDeleteIdentity (json, target, byName = false) {
  const body = json?.listcniconfigurationresponse
  if (!body || json.errorresponse || body.errorcode || !userDataDeleteParams(target)) return 'error'
  const rows = body.cniconfig ?? []; const count = body.count ?? (rows.length === 0 ? 0 : undefined)
  if (!Array.isArray(rows) || !Number.isSafeInteger(count) || count < 0 || count !== rows.length) return 'error'
  const uuid = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i
  if (rows.some(row => !uuid.test(row?.id || '') || row.name !== target.name || row.domainid !== target.domainid ||
    (target.projectid ? row.projectid !== target.projectid : row.projectid || row.account !== target.account))) return 'mismatch'
  // The owner-scoped name fallback must be complete before absence is confirmed.
  return userDataDeleteIdentity({ listuserdataresponse: { userdata: rows, count } }, target, byName)
}

const listKeys = ['id', 'q', 'keyword', 'name', 'account', 'domainid', 'projectid', 'listall', 'isrecursive', 'page', 'pagesize', 'sortkey', 'sortorder', 'filter']
export function cniListQuery (query = {}) {
  return Object.fromEntries(listKeys.filter(key => typeof query[key] === 'string' && query[key].length <= 1024).map(key => [key, query[key]]))
}
export function cniListLocation (route) {
  let query = route.query || {}
  if (typeof query.cnireturn === 'string' && query.cnireturn.length <= 8192) {
    try { const parsed = JSON.parse(query.cnireturn); if (parsed && !Array.isArray(parsed) && typeof parsed === 'object') query = parsed } catch (_) {}
  }
  return { path: '/cniconfiguration', query: cniListQuery(query) }
}
export function cniDetailLink (route, target, tab) {
  return { path: '/cniconfiguration/' + target.id, query: { cnireturn: JSON.stringify(cniListQuery(route.query)), ...(tab ? { tab } : {}) } }
}
