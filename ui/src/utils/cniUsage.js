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

const uuid = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i
const same = (a, b) => typeof a === 'string' && typeof b === 'string' && a.toLowerCase() === b.toLowerCase()
const fail = state => { throw Object.assign(new Error('Invalid CNI association response'), { usageState: state }) }
const ownerMatches = (row, source) => same(row.domainid, source.domainid) &&
  (source.projectid ? same(row.projectid, source.projectid) : !row.projectid && row.account === source.account)

export function cniOwnerParams (source) {
  if (!uuid.test(source?.id || '') || (typeof source.name !== 'string' || !source.name.trim()) || !uuid.test(source.domainid || '') ||
      (source.projectid ? !uuid.test(source.projectid) : typeof source.account !== 'string' || !source.account)) return null
  return source.projectid ? { projectid: source.projectid } : { account: source.account, domainid: source.domainid, ignoreproject: true }
}
export function cniIdentityParams (source) {
  const owner = cniOwnerParams(source)
  return owner ? { ...owner, id: source.id, listall: true, page: 1, pagesize: 2 } : null
}
export function cniIdentity (json, source) {
  const body = json?.listcniconfigurationresponse
  if (!body || body.errorcode || json.errorresponse) return 'error'
  const rows = body.cniconfig ?? []; const count = body.count ?? (rows.length === 0 ? 0 : undefined)
  if (!Array.isArray(rows) || !Number.isSafeInteger(count) || count !== rows.length) return 'error'
  if (!rows.length) return 'missing'
  return rows.length === 1 && same(rows[0]?.id, source.id) && rows[0].name === source.name && ownerMatches(rows[0], source) ? 'ready' : 'mismatch'
}
export function cniUsageCapability (apis) {
  if (!apis?.listCniConfiguration || !apis?.listKubernetesClusters) return 'forbidden'
  return Array.isArray(apis.listKubernetesClusters.params) && apis.listKubernetesClusters.params.some(param => param.name === 'cniconfigurationid') ? 'ready' : 'unsupported'
}
export function cniUsageParams (source, { keyword, page = 1, pagesize = 20 } = {}) {
  const owner = cniOwnerParams(source)
  if (!owner) fail('mismatch')
  return { ...owner, cniconfigurationid: source.id, listall: true, page, pagesize, ...(keyword ? { keyword } : {}) }
}
// Membership comes exclusively from the filtered cluster's API response, never a global VM query.
export function cniUsageRows (json, source) {
  if (!cniOwnerParams(source)) fail('mismatch')
  const body = json?.listkubernetesclustersresponse
  if (!body || body.errorcode || json.errorresponse) fail('error')
  const rows = body.kubernetescluster ?? []; const count = body.count ?? (rows.length === 0 ? 0 : undefined)
  if (!Array.isArray(rows) || !Number.isSafeInteger(count) || count < rows.length || count < 0) fail('error')
  const seen = new Set()
  for (const row of rows) {
    if (!uuid.test(row?.id || '') || seen.has(row.id.toLowerCase()) || typeof row.name !== 'string' || !row.name || typeof row.state !== 'string' || !row.state ||
        !same(row.cniconfigurationid, source.id) || !ownerMatches(row, source)) fail('mismatch')
    seen.add(row.id.toLowerCase())
    const nodes = row.virtualmachines ?? []
    if (!Array.isArray(nodes)) fail('error')
    const members = new Set()
    for (const node of nodes) {
      if (!uuid.test(node?.id || '') || members.has(node.id.toLowerCase()) || typeof node.name !== 'string' || !node.name || typeof node.state !== 'string' || !node.state || !ownerMatches(node, source)) fail('mismatch')
      members.add(node.id.toLowerCase())
    }
  }
  return { rows: rows.map(row => ({ ...row, virtualmachines: (row.virtualmachines || []).map(node => ({ ...node, name: node.displayname || node.name })) })), count }
}
export function cniUsageError (error) {
  const body = Object.values(error?.response?.data || {}).find(value => value?.errorcode)
  const code = Number(body?.errorcode || error?.response?.status)
  return error.usageState || ([401, 403, 432, 531].includes(code) ? 'forbidden' : code === 431 ? 'missing' : 'error')
}
