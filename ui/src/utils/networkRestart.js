// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

export const restartableNetworkType = row => ['Isolated', 'Shared'].includes(row?.type)
const path = value => String(value || '').replace(/^\/+|\/+$/g, '')

export function networkRestartReason (row, getters = {}) {
  const user = getters.userInfo || {}
  if (!row?.id) return 'missing'
  if (!restartableNetworkType(row)) return 'type'
  if (!('restartNetwork' in (getters.apis || {}))) return 'permission'
  const admin = user.roletype === 'Admin'
  const domainAdmin = user.roletype === 'DomainAdmin' &&
    ((user.domainid && row.domainid === user.domainid) || (path(user.domainpath) && (path(row.domainpath) + '/').startsWith(path(user.domainpath) + '/')))
  const project = row.projectid && row.projectid === getters.project?.id
  const owner = row.account && row.account === user.account && row.domainid && row.domainid === user.domainid
  if (!admin && !domainAdmin && !project && !owner) return 'permission'
  if (row.projectid && !admin && !domainAdmin && !project) return 'project'
  if (row.type === 'Shared' && row.specifyvlan && !admin) return 'shared.admin'
  if (!['Implemented', 'Setup'].includes(row.state) || (row.jobid && row.jobstatus === 0)) return 'state'
  return ''
}

export function networkRestartFingerprint (row) {
  return JSON.stringify(['id', 'type', 'zoneid', 'account', 'domainid', 'projectid', 'vpcid', 'networkofferingid', 'specifyvlan'].map(k => row?.[k] ?? null))
}

export function networkRestartCapabilities (data, params, config = {}) {
  const services = data.offering?.service || data.network?.service || []
  const sourceNat = services.find(s => s.name === 'SourceNat')
  const redundant = sourceNat?.capability?.some(c => c.name === 'RedundantRouter' && String(c.value).toLowerCase() === 'true')
  const vr = services.some(s => s.provider?.some(p => ['VirtualRouter', 'VpcVirtualRouter'].includes(p.name)))
  return {
    cleanup: params.includes('cleanup') && !data.network?.vpcid,
    makeredundant: params.includes('makeredundant') && [true, 'true'].includes(config.allowMakingRouterRedundant) &&
      data.network?.type === 'Isolated' && !data.network?.vpcid && !data.network?.redundantrouter && !!redundant,
    livepatch: params.includes('livepatch') && vr
  }
}

export function networkRestartOptionReason (data, options, params, config) {
  const caps = networkRestartCapabilities(data, params, config)
  if (options.cleanup && !caps.cleanup) return 'cleanup'
  if (options.makeredundant && !caps.makeredundant) return 'redundant'
  if (options.livepatch && (options.cleanup || options.makeredundant || !caps.livepatch)) return 'livepatch'
  if (data.routers?.some(r => ['Starting', 'Stopping', 'Migrating', 'Destroying', 'Expunging'].includes(r.state))) return 'router.busy'
  if (options.livepatch && data.routers && (!data.routers.length || data.routers.some(r => r.state !== 'Running'))) return 'router.running'
  return ''
}

export function networkRestartParams (id, values, params = ['cleanup', 'makeredundant', 'livepatch']) {
  const result = { id }
  for (const key of ['cleanup', 'makeredundant', 'livepatch']) {
    if (params.includes(key)) result[key] = values[key] === true
  }
  return result
}

export function definitiveNetworkRejection (error) {
  const data = error?.response?.data
  return !!(data?.restartnetworkresponse?.errorcode || data?.errorresponse?.errorcode)
}
