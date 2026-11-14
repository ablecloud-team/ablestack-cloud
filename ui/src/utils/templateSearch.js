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

export const templateSearchFields = ['name', 'zoneid', 'tags', 'arch', 'oscategoryid', 'templatetype', 'extensionid', 'storageid', 'imagestoreid', 'isready', 'hypervisor', 'forcks', 'userdataid']
const uuid = value => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const scalar = value => typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number'
export function templateListQuery (query = {}, pageSize = 20) {
  const result = {}
  const keys = ['filter', 'templatefilter', 'showunique', 'projectid', 'keyword', 'q', ...templateSearchFields]
  for (const [key, value] of Object.entries(query)) {
    if (!keys.includes(key) && !/^tags\[\d+\]\.(key|value)$/.test(key)) continue
    if (scalar(value) && String(value) !== '') result[key] = String(value)
  }
  const page = Number(query.page)
  const size = Number(query.pagesize || pageSize)
  result.page = String(Number.isSafeInteger(page) && page > 0 ? page : 1)
  result.pagesize = String(Number.isSafeInteger(size) && size > 0 && size <= 500 ? size : 20)
  return result
}
export function templateSearchQuery (query, options, pageSize) {
  const next = { ...query }
  for (const key of Object.keys(next)) if (templateSearchFields.includes(key) || key.startsWith('tags[') || ['keyword', 'q'].includes(key)) delete next[key]
  if (Object.prototype.hasOwnProperty.call(options, 'searchQuery')) {
    if (options.searchQuery) next.keyword = next.q = options.searchQuery
  } else Object.assign(next, options)
  return templateListQuery({ ...next, page: '1', pagesize: pageSize })
}
export function templateSearchError (query, apiParams = {}) {
  for (const key of templateSearchFields.filter(k => k !== 'tags')) {
    if (!(key in query)) continue
    if (!apiParams[key]) return 'message.template.search.unsupported'
    if (['isready', 'forcks'].includes(key) && !['true', 'false'].includes(String(query[key]))) return 'message.template.search.invalid'
    if (['zoneid', 'oscategoryid', 'extensionid', 'storageid', 'imagestoreid', 'userdataid'].includes(key) && !uuid(query[key])) return 'message.template.search.invalid'
  }
  return ''
}
export function templateReadFilter (getters = {}) {
  return ['Admin', 'DomainAdmin', 'ResourceAdmin'].includes(getters.userInfo?.roletype) ? 'all' : 'executable'
}
export async function readTemplateResource (request, params, getters = {}, current = () => true) {
  const options = { preserveOnFailure: true, timeout: 20000 }
  const projectid = params.projectid || (getters.project?.id ? (getters.listAllProjects ? '-1' : getters.project.id) : '')
  const scoped = { ...params, ...(projectid ? { projectid } : {}), templatefilter: templateReadFilter(getters) }
  const result = await request('listTemplates', scoped, options)
  if (!current() || scoped.templatefilter === 'all' || result?.listtemplatesresponse?.template?.length) return result
  return request('listTemplates', { ...scoped, templatefilter: 'shared' }, options)
}
export function templateRequestParams (params, detail = false, getters = {}) {
  if (detail) return { id: params.id, templatefilter: templateReadFilter(getters), ...(params.projectid ? { projectid: params.projectid } : {}), ...(params.showicon ? { showicon: params.showicon } : {}) }
  const result = { listall: true, showunique: 'true', ...templateListQuery(params) }
  delete result.filter; delete result.q
  return result
}
export function templateOwner (getters = {}) {
  return JSON.stringify([getters.userInfo?.id || '', getters.userInfo?.roletype || '', getters.userInfo?.roleid || '', getters.project?.id || '', !!getters.listAllProjects])
}
export function templateDetailLocation (route, record, getters, tab) {
  return { path: '/template/' + record.id, query: { templatereturn: JSON.stringify(templateListQuery(route.query)), templateowner: templateOwner(getters), ...(tab ? { tab } : {}) } }
}
export function templateListLocation (route, getters) {
  try {
    if (route.query.templateowner !== templateOwner(getters)) return { path: '/template' }
    const query = JSON.parse(route.query.templatereturn)
    if (!query || typeof query !== 'object' || Array.isArray(query)) return { path: '/template' }
    return { path: '/template', query: templateListQuery(query) }
  } catch (_) { return { path: '/template' } }
}

export function templateFilterMismatch (rows, params) {
  return rows.some(row => ['isready', 'forcks', 'hypervisor', 'userdataid'].some(key => {
    if (params[key] === undefined) return false
    return row[key] === undefined || String(row[key]).toLowerCase() !== String(params[key]).toLowerCase()
  }))
}
