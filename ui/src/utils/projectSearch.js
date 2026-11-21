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

export const projectStates = ['Active', 'Suspended', 'Disabled']
export const projectSortFields = ['name', 'state', 'displaytext', 'account', 'domain']
const fields = ['id', 'name', 'displaytext', 'state', 'domainid', 'account', 'username', 'tags[0].key', 'tags[0].value']
const text = value => typeof value === 'string' ? value.trim() : ''
const positive = value => {
  const number = Number(value)
  return Number.isSafeInteger(number) && number > 0 ? String(number) : undefined
}

export const isProjectDetailRoute = route => route.meta?.name === 'project' && !!route.params?.id
export const projectScopeFiltersAvailable = getters => ['Admin', 'DomainAdmin', 'ResourceAdmin'].includes(getters.userInfo?.roletype) && !!getters.apis?.listDomains

export function projectSearchQuery (raw = {}, getters) {
  const query = Object.fromEntries(fields.filter(key => text(raw[key])).map(key => [key, text(raw[key])]))
  if (!projectStates.includes(query.state)) delete query.state
  if (!projectScopeFiltersAvailable(getters)) delete query.domainid
  if (!query.domainid || !getters.apis?.listAccounts) delete query.account
  if (!query.domainid || !getters.apis?.listUsers) delete query.username
  if (!query['tags[0].key'] || !query['tags[0].value']) {
    delete query['tags[0].key']
    delete query['tags[0].value']
  }
  const keyword = text(raw.q) || text(raw.keyword)
  if (keyword) query.q = keyword
  const page = positive(raw.page)
  const size = positive(raw.pagesize)
  if (page) query.page = page
  if (size) query.pagesize = size
  if (projectSortFields.includes(raw.sortkey) && ['asc', 'desc'].includes(raw.sortorder)) {
    query.sortkey = raw.sortkey
    query.sortorder = raw.sortorder
  }
  return query
}

export function applyProjectSearch (routeQuery, filters, getters) {
  const { pagesize, sortkey, sortorder } = routeQuery
  return projectSearchQuery({ pagesize, sortkey, sortorder, ...filters, page: '1' }, getters)
}

export function projectListParams (routeQuery, params, getters) {
  const query = projectSearchQuery(routeQuery, getters)
  const keyword = query.q
  for (const key of ['q', 'sortkey', 'sortorder']) delete query[key]
  return {
    listall: params.listall,
    page: params.page,
    pagesize: params.pagesize,
    showIcon: params.showIcon,
    ignoreproject: true,
    ...query,
    ...(keyword ? { keyword } : {})
  }
}

export function projectListScope (getters, token) {
  const user = getters.userInfo
  return token && user?.id ? hash.sha256().update(JSON.stringify([
    token, user.id, user.roleid, user.roletype, user.domainid, getters.project?.id, getters.listAllProjects
  ])).digest('hex') : ''
}

export function projectDetailLocation (route, record, getters, token) {
  const path = '/project/' + encodeURIComponent(record.id)
  const scope = projectListScope(getters, token)
  return scope && route.meta?.name === 'project' && !route.params?.id
    ? { path, state: { projectReturn: { id: record.id, scope, query: projectSearchQuery(route.query, getters) } } }
    : { path }
}

export function projectRouteHistory (route, history, getters, token) {
  const saved = history?.projectReturn
  const scope = projectListScope(getters, token)
  const valid = scope && isProjectDetailRoute(route) && saved?.id === route.params.id && saved.scope === scope
  return { projectReturn: valid ? saved : null }
}

export function projectListLocation (route, history, getters, token) {
  const saved = projectRouteHistory(route, history, getters, token).projectReturn
  return { path: '/project', query: projectSearchQuery(saved?.query, getters), state: { projectReturn: null } }
}

export function projectSearchError (filters, isUuid) {
  if (text(filters.id) && !isUuid(text(filters.id))) return 'message.project.search.uuid.invalid'
  if (Boolean(text(filters['tags[0].key'])) !== Boolean(text(filters['tags[0].value']))) return 'message.project.search.tag.pair'
  return ''
}

export function projectMemberFilters (draft = {}) {
  const fields = ['keyword', 'account', 'username', 'role', 'projectroleid']
  return Object.fromEntries(fields.filter(key => text(draft[key])).map(key => [key, text(draft[key])]))
}
