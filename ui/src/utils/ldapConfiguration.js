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

export const ldapConfigApi = 'listLdapConfigurations'
export const ldapConfigRoute = route => route.meta?.name === 'ldapsetting' || route.path === '/ldapsetting'

const text = value => typeof value === 'string' ? value.trim() : ''
const positive = value => /^\d+$/.test(String(value)) && Number.isSafeInteger(Number(value)) && Number(value) > 0 ? String(Number(value)) : ''

export function ldapListQuery (raw = {}) {
  const id = text(raw.id)
  if (id) return { id }
  const query = {}
  if (raw.scope === 'global') query.scope = 'global'
  else if (text(raw.domainid)) query.domainid = text(raw.domainid)
  for (const field of ['hostname', 'port', 'keyword']) {
    if (text(raw[field])) query[field] = text(raw[field])
  }
  for (const field of ['page', 'pagesize']) {
    if (positive(raw[field])) query[field] = positive(raw[field])
  }
  return query
}

export function ldapRemovedListQuery (raw, removedId) {
  const query = ldapListQuery(raw)
  if (query.id === removedId) delete query.id
  return query
}

export function ldapEndpointLabel (resource) {
  return resource.hostname && resource.port ? resource.hostname + ':' + resource.port : resource.hostname || resource.id
}

export function ldapSearchQuery (raw, selection) {
  const current = ldapListQuery(raw)
  if (Object.prototype.hasOwnProperty.call(selection, 'searchQuery')) {
    delete current.id
    return ldapListQuery({ ...current, keyword: selection.searchQuery, page: '1' })
  }
  const { id, domainid, hostname, port } = selection
  return ldapListQuery({
    id,
    domainid,
    hostname,
    port,
    scope: domainid ? undefined : current.scope,
    keyword: current.keyword,
    pagesize: current.pagesize,
    page: '1'
  })
}

export function ldapReadParams (route, params) {
  const query = ldapListQuery(route.query)
  const id = route.params?.id || query.id
  if (id) return { id }
  const { scope, page, pagesize, ...filters } = query
  return { ...filters, listall: scope !== 'global', page: page || params.page, pagesize: pagesize || params.pagesize }
}

export async function ldapDomainPaths (getAPI, rows, canReadDomains, globalLabel) {
  const paths = new Map()
  if (canReadDomains) {
    const ids = [...new Set(rows.map(row => row.domainid).filter(Boolean))]
    await Promise.all(ids.map(async id => {
      try {
        const response = await getAPI('listDomains', { id, details: 'min' }, { preserveOnFailure: true, timeout: 5000 })
        const domain = response.listdomainsresponse?.domain?.find(item => item.id === id)
        if (domain) paths.set(id, domain.path || domain.name)
      } catch {
        // The UUID still identifies a domain whose name cannot be resolved.
      }
    }))
  }
  return rows.map(row => ({ ...row, domainpath: row.domainid ? paths.get(row.domainid) || row.domainid : globalLabel }))
}
