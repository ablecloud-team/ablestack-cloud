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

export const networkTypeLabel = value => ({ Isolated: 'label.network.lifecycle.type.isolated', Shared: 'label.network.lifecycle.type.shared', L2: 'label.network.lifecycle.type.l2' }[value] || value)
export const networkStateLabel = value => ['Allocated', 'Setup', 'Implementing', 'Implemented', 'Shutdown', 'Destroy'].includes(value) ? 'label.network.lifecycle.state.' + value.toLowerCase() : value
export const networkBusy = row => ['Implementing', 'Shutdown', 'Destroy'].includes(row?.state) || !!(row?.jobid && row.jobstatus === 0)
export const networkDeleteFingerprint = row => JSON.stringify(['id', 'type', 'account', 'domainid', 'projectid', 'acltype', 'networkofferingid', 'physicalnetworkid', 'related', 'associatednetworkid', 'specifyvlan'].map(key => row?.[key] ?? null))
const domainPath = value => String(value || '').replace(/^\/+|\/+$/g, '')

export function networkDeleteReason (row, getters = {}) {
  if (!row?.id) return 'missing'
  const user = getters.userInfo || {}
  if (!['deleteNetwork', 'listNetworks', 'queryAsyncJobResult', 'listAsyncJobs'].every(api => api in (getters.apis || {}))) return 'permission'
  const admin = user.roletype === 'Admin'
  const domain = user.roletype === 'DomainAdmin' && ((row.domainid && row.domainid === user.domainid) || (domainPath(user.domainpath) && (domainPath(row.domainpath) + '/').startsWith(domainPath(user.domainpath) + '/')))
  const owner = row.account === user.account && row.domainid && row.domainid === user.domainid
  const project = row.projectid && row.projectid === getters.project?.id
  if (!admin && !domain && !owner && !project) return 'permission'
  if (row.projectid && !admin && !domain && !project) return 'permission'
  if (row.type === 'Shared' && row.specifyvlan && !admin) return 'permission'
  if (!['Isolated', 'Shared', 'L2'].includes(row.type)) return 'type'
  if (row.related && row.related !== row.id) return 'checkpoint'
  if (networkBusy(row)) return 'busy'
  return ''
}

export function networkError (error) {
  const body = Object.values(error?.response?.data || {}).find(value => value && typeof value === 'object' && (value.errorcode || value.errortext))
  return { code: body?.errorcode || '', text: body?.errortext || error?.response?.headers?.['x-description'] || error?.message || '', definitive: !!body?.errorcode }
}

export async function networkCollection (get, api, field, params, key = 'id') {
  const result = []
  for (let page = 1; page <= 100; page++) {
    const json = await get(api, { ...params, page, pagesize: 500 }, { preserveOnFailure: true })
    const body = json?.[api.toLowerCase() + 'response']
    if (!body || body.errorcode || (body[field] !== undefined && !Array.isArray(body[field]))) throw new Error('Invalid ' + api + ' response')
    const rows = body[field] || []
    const count = body.count === undefined && !rows.length && page === 1 ? 0 : Number(body.count)
    if (!Number.isSafeInteger(count) || count < 0 || count < rows.length || rows.some(row => !row[key])) throw new Error('Invalid ' + api + ' page')
    result.push(...rows)
    if (new Set(result.map(row => row[key])).size !== result.length) throw new Error('Duplicate ' + api + ' page')
    if (result.length >= count) return result
    if (!rows.length) throw new Error('Incomplete ' + api + ' page')
  }
  throw new Error('Incomplete ' + api + ' collection')
}

export async function networkJobs (get, id, completed = false) {
  const jobs = await networkCollection(get, 'listAsyncJobs', 'asyncjobs', { resourceid: id, resourcetype: 'Network', ...(completed ? { includecompleted: true } : {}), listall: true, ignoreproject: true }, 'jobid')
  if (jobs.some(job => job.jobinstanceid !== id || String(job.jobinstancetype).toLowerCase() !== 'network' || !(completed ? [0, 1, 2] : [0]).includes(job.jobstatus))) throw new Error('Network job scope mismatch')
  return jobs
}

export const networkPendingJobs = (get, id) => networkJobs(get, id)

export async function networkJob (get, id, jobId) {
  const json = await get('queryAsyncJobResult', { jobid: jobId, ignoreproject: true }, { preserveOnFailure: true })
  const job = json?.queryasyncjobresultresponse
  if (!job || job.jobid !== jobId || job.jobinstanceid !== id || String(job.jobinstancetype).toLowerCase() !== 'network' || ![0, 1, 2].includes(job.jobstatus)) throw new Error('Network job scope mismatch')
  return job
}

export async function readNetworkDeletion (get, row, getters) {
  const params = { listall: true, ignoreproject: true, ...(row.projectid ? { projectid: row.projectid } : {}) }
  const networks = await networkCollection(get, 'listNetworks', 'network', { ...params, id: row.id })
  if (networks.length !== 1 || networks[0].id !== row.id) throw new Error('message.network.lifecycle.reason.missing')
  const network = networks[0]
  const apis = getters.apis || {}
  const optional = async (api, field, query) => api in apis ? networkCollection(get, api, field, { ...params, ...query }) : null
  const [vms, routers, ips, jobs] = await Promise.all([
    optional('listVirtualMachines', 'virtualmachine', { networkid: row.id }),
    optional('listRouters', 'router', network.vpcid ? { vpcid: network.vpcid } : { networkid: row.id }),
    optional('listPublicIpAddresses', 'publicipaddress', { associatednetworkid: row.id }),
    networkJobs(get, row.id, true)
  ])
  if (vms?.some(vm => !vm.nic?.some(nic => nic.networkid === row.id))) throw new Error('VM network scope mismatch')
  if (!network.vpcid && routers?.some(router => (router.guestnetworkid || router.networkid) !== row.id)) throw new Error('Router network scope mismatch')
  const deletions = jobs.filter(job => /(?:^|\.)DeleteNetworkCmd$/.test(job.cmd || '')).sort((a, b) => (Date.parse(b.created) || 0) - (Date.parse(a.created) || 0))
  return { network, vms, routers, ips, jobs: jobs.filter(job => job.jobstatus === 0), lastDeletion: deletions[0] || null }
}
