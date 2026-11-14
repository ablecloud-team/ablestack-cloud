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

export const vpcRestartApis = ['restartVPC', 'listVPCs', 'queryAsyncJobResult', 'listAsyncJobs', 'listVPCOfferings']
export const vpcRestartOptions = ['cleanup', 'makeredundant', 'livepatch']
const domainPath = v => String(v || '').replace(/^\/+|\/+$/g, '')
export const vpcRestartIdentity = v => JSON.stringify(['id', 'zoneid', 'account', 'domainid', 'projectid'].map(k => v?.[k] ?? null))
export const vpcRestartFingerprint = v => JSON.stringify([vpcRestartIdentity(v), v?.vpcofferingid, !!v?.redundantvpcrouter])

export function vpcRestartReason (v, getters = {}) {
  if (!v?.id) return 'missing'
  if (!vpcRestartApis.every(a => a in (getters.apis || {}))) return 'permission'
  const u = getters.userInfo || {}
  const domain = u.roletype === 'DomainAdmin' && ((u.domainid && u.domainid === v.domainid) || (domainPath(u.domainpath) && (domainPath(v.domainpath) + '/').startsWith(domainPath(u.domainpath) + '/')))
  const project = v.projectid && getters.project?.id === v.projectid
  const owner = !v.projectid && v.account === u.account && v.domainid && v.domainid === u.domainid
  if (u.roletype !== 'Admin' && !domain && !project && !owner) return 'permission'
  const selectedProject = getters.project?.id
  if (selectedProject && String(selectedProject) !== '0' && selectedProject !== v.projectid) return 'project'
  if (v.state !== 'Enabled' || (v.jobid && v.jobstatus === 0)) return 'state'
  return ''
}

export function vpcRestartCapabilities (data, params) {
  const vr = data.offering?.service?.some(s => s.provider?.some(p => p.name === 'VpcVirtualRouter')) && !data.offering?.fornsx && !data.offering?.distributedvpcrouter
  return {
    cleanup: params.includes('cleanup') && !!vr,
    makeredundant: params.includes('makeredundant') && !!vr && !data.vpc.redundantvpcrouter && !!data.redundantOffering,
    livepatch: params.includes('livepatch') && !!vr && !!data.networks?.length && !!data.routers?.length && data.routers.every(r => r.state === 'Running')
  }
}

export function vpcRestartOptionReason (data, options, params, retryConversion = false) {
  const caps = vpcRestartCapabilities(data, params)
  if (data.jobs?.length) return 'pending'
  if (data.routers?.some(r => !['Running', 'Stopped'].includes(r.state))) return 'router'
  if (options.cleanup && !caps.cleanup) return 'cleanup'
  if (options.makeredundant && !caps.makeredundant && !retryConversion) return 'redundant'
  if (options.livepatch && (!caps.livepatch || options.cleanup || options.makeredundant)) return 'livepatch'
  return ''
}

export function vpcRestartParams (id, values = {}, params = vpcRestartOptions) {
  const result = { id }
  for (const k of vpcRestartOptions) if (params.includes(k)) result[k] = values[k] === true
  return result
}

export function validVpcRestartJob (job, id, jobId, userId) {
  // Deployed VPC job responses omit jobinstanceid. A submitted customjobid or
  // a receipt restored in the same user/selection scope binds that job locally.
  return job?.jobid === jobId && /(?:^|\.)RestartVPCCmd$/.test(job.cmd || '') &&
    (!job.jobinstanceid || job.jobinstanceid === id) && (!job.jobinstancetype || job.jobinstancetype.toLowerCase() === 'vpc') &&
    (!job.userid || job.userid === userId) && [0, 1, 2].includes(job.jobstatus)
}
