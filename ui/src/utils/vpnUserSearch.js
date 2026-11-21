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

const isUuid = value => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
const cleanText = value => typeof value === 'string' ? value.trim() : ''
const integer = (value, fallback) => Number.isSafeInteger(Number(value)) && Number(value) > 0 ? String(Number(value)) : String(fallback)

export const vpnUserRoute = route => route?.meta?.name === 'vpnuser'
export const vpnUserHasParam = (getters, name) =>
  !!getters.apis?.listVpnUsers?.params?.some(param => param.name === name)
export const canSwitchVpnUserProjects = getters =>
  !!getters.apis?.listProjects && vpnUserHasParam(getters, 'projectid')

export function vpnUserProject (query, getters) {
  if (isUuid(getters.project?.id)) return getters.project.id
  if (!canSwitchVpnUserProjects(getters)) return '0'
  if (['0', '-1'].includes(query?.projectid)) return query.projectid
  return getters.listAllProjects ? '-1' : '0'
}

export function vpnUserSearchAuth (getters, token) {
  const apis = getters.apis || {}
  const user = getters.userInfo
  return JSON.stringify([token, user?.id, user?.roletype, user?.roleid, user?.domainid, getters.project?.id,
    !!apis.listProjects, !!apis.listDomains, !!apis.listAccounts, !!apis.removeVpnUser, !!apis.queryAsyncJobResult,
    apis.listVpnUsers?.params?.map(param => param.name).sort()])
}

export function vpnUserSearchFields (projectid, getters) {
  const fields = []
  if (vpnUserHasParam(getters, 'username')) fields.push('username')
  if (!['0', '-1'].includes(projectid) || !getters.apis?.listDomains || !vpnUserHasParam(getters, 'domainid')) return fields
  fields.push('domainid')
  if (projectid === '0' && getters.apis.listAccounts && vpnUserHasParam(getters, 'account')) fields.push('account')
  return fields
}

export function vpnUserListQuery (raw, size, getters) {
  const projectid = vpnUserProject(raw, getters)
  const sameScope = !raw.projectid || raw.projectid === projectid
  const result = { projectid }
  if (sameScope) {
    if (vpnUserHasParam(getters, 'keyword') && cleanText(raw.q)) result.q = cleanText(raw.q)
    for (const field of vpnUserSearchFields(projectid, getters)) {
      if (field === 'domainid' && !isUuid(raw.domainid)) continue
      if (field === 'account' && !result.domainid) continue
      const value = cleanText(raw[field])
      if (value) result[field] = value
    }
  }
  result.page = integer(sameScope ? raw.page : 1, 1)
  result.pagesize = integer(raw.pagesize, size || 20)
  return result
}

export function vpnUserSearchQuery (query, input, size, getters) {
  const scope = { projectid: vpnUserProject(query, getters), pagesize: query.pagesize }
  const next = Object.prototype.hasOwnProperty.call(input, 'searchQuery')
    ? { ...query, ...scope, q: input.searchQuery }
    : input.reset ? scope : { ...scope, q: query.q, ...input.filters }
  return vpnUserListQuery({ ...next, page: 1 }, size, getters)
}

export function vpnUserListLocation (route, getters) {
  return { path: '/vpnuser', query: vpnUserListQuery(route.query, getters.defaultListViewPageSize, getters) }
}

export function vpnUserReadParams (query, id) {
  const { projectid, q, username, domainid, account, page, pagesize } = query
  // listall=true upgrades projectid=-1 to include account resources.
  const scope = projectid === '0' ? { ignoreproject: true } : { projectid }
  const base = { ...scope, listall: projectid !== '-1' }
  if (id) return { ...base, id }
  return {
    ...base,
    ...(q ? { keyword: q } : {}),
    ...(username ? { username } : {}),
    ...(domainid ? { domainid, isrecursive: false } : {}),
    ...(account ? { account } : {}),
    page: Number(page),
    pagesize: Number(pagesize)
  }
}

export async function readVpnUsers (get, query, id) {
  const params = vpnUserReadParams(query, id)
  const response = await get('listVpnUsers', params, { preserveOnFailure: true, timeout: 20000 })
  const data = response?.listvpnusersresponse
  if (!data || data.errorcode || (data.vpnuser !== undefined && !Array.isArray(data.vpnuser))) throw new Error('Invalid VPN user response')
  const rows = data.vpnuser || []
  const count = Number(data.count ?? rows.length)
  const belongs = row => {
    const inProject = query.projectid === '0' ? !row.projectid : isUuid(row.projectid) && (query.projectid === '-1' || row.projectid === query.projectid)
    return inProject && (id ? row.id === id : (!params.account || row.account === params.account) && (!params.domainid || row.domainid === params.domainid))
  }
  if (!Number.isSafeInteger(count) || count < rows.length || (id && rows.length > 1) || !rows.every(belongs)) {
    throw new Error('VPN user result has an unexpected scope')
  }
  return { listvpnusersresponse: { count, vpnuser: rows } }
}
