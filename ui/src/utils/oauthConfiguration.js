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

export const oauthConfigApi = 'listOauthProviderConfigurations'
export const oauthRoute = route => route.meta?.name === 'oauthsetting' || /^\/oauthsetting(?:\/|$)/.test(route.path)

const text = value => typeof value === 'string' ? value.trim() : ''
const positive = value => /^\d+$/.test(String(value)) && Number.isSafeInteger(Number(value)) && Number(value) > 0 ? String(Number(value)) : ''

export function oauthListQuery (raw = {}) {
  if (text(raw.id)) return { id: text(raw.id) }
  const query = {}
  if (raw.scope === 'global') query.scope = 'global'
  else if (text(raw.domainid)) query.domainid = text(raw.domainid)
  for (const field of ['provider', 'keyword']) {
    if (text(raw[field])) query[field] = text(raw[field])
  }
  for (const field of ['page', 'pagesize']) {
    if (positive(raw[field])) query[field] = positive(raw[field])
  }
  return query
}

export function oauthSearchQuery (raw, filter) {
  const current = oauthListQuery(raw)
  if (Object.prototype.hasOwnProperty.call(filter, 'searchQuery')) {
    delete current.id
    return oauthListQuery({ ...current, keyword: filter.searchQuery, page: '1' })
  }
  const { id, provider, domainid } = filter
  return oauthListQuery({
    id,
    provider,
    domainid,
    scope: domainid || current.domainid ? 'domain' : current.scope,
    keyword: current.keyword,
    pagesize: current.pagesize,
    page: '1'
  })
}

export function oauthReadParams (route, pagination) {
  const query = oauthListQuery(route.query)
  const id = route.params?.id || query.id
  if (id) return { id }
  return { page: pagination.page, pagesize: pagination.pagesize, ...query }
}

export function oauthListLocation (route) {
  return { path: '/oauthsetting', query: oauthListQuery(route.query) }
}
