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

import { isProjectAdministrator } from './projectMembership'

const allowedStates = {
  activateProject: ['Suspended'],
  suspendProject: ['Active'],
  deleteProject: ['Active', 'Suspended', 'Disabled']
}

export function projectActionReason (api, row, getters) {
  if (!row?.id || !isProjectAdministrator(row, getters.userInfo)) return 'message.project.bulk.denied'
  if (api === 'deleteProject' && row.id === getters.project?.id) return 'message.project.bulk.current.project'
  if (!allowedStates[api]?.includes(row.state)) return 'message.project.bulk.state.unavailable'
  return ''
}

export function projectBulkAvailable (api, rows, getters) {
  return Boolean(getters.apis?.[api] && getters.apis.listProjects && getters.apis.queryAsyncJobResult &&
    rows?.some(row => !projectActionReason(api, row, getters)))
}

export function projectBulkTargets (api, keys, rows, getters) {
  if (!keys.length || new Set(keys).size !== keys.length) return []
  const selected = keys.map(id => rows.find(row => row.id === id))
  if (selected.some(row => !row) || !projectBulkAvailable(api, selected, getters)) return []
  return selected.map(row => Object.freeze({
    id: row.id,
    name: row.name,
    domain: row.domain,
    domainid: row.domainid,
    state: row.state,
    reason: projectActionReason(api, row, getters)
  }))
}

export function projectBulkScope (route, getters, token, keys) {
  const user = getters.userInfo
  return JSON.stringify([
    route.fullPath, user?.id, user?.roleid, user?.roletype, user?.domainid, token,
    getters.project?.id, getters.listAllProjects, [...keys].sort(),
    ['activateProject', 'suspendProject', 'deleteProject', 'listProjects', 'queryAsyncJobResult'].map(api => !!getters.apis?.[api]),
    supportsProjectCleanup(getters)
  ])
}

export function supportsProjectCleanup (getters) {
  return !!getters.apis?.deleteProject?.params?.some(param => param.name === 'cleanup')
}

export function projectBulkParams (api, target, cleanup, supportsCleanup) {
  const params = { id: target.id, ignoreproject: true }
  if (api === 'deleteProject' && supportsCleanup) params.cleanup = cleanup === true
  return params
}

export function currentProjectReason (api, original, latest, getters) {
  if (!latest || original.id !== latest.id || original.name !== latest.name ||
    original.domainid !== latest.domainid || original.state !== latest.state) return 'message.project.bulk.target.changed'
  return projectActionReason(api, latest, getters)
}

export function projectBulkRequestError (error) {
  const status = Number(error.response?.status)
  const detail = Object.values(error.response?.data || {}).find(value => typeof value?.errortext === 'string')?.errortext
  return { definitive: !!detail || (status >= 400 && status < 500), detail: detail || '' }
}

export function projectBulkJobId (api, response) {
  // Existing Mold versions return "activaterojectresponse" for activateProject.
  return response?.[api.toLowerCase() + 'response']?.jobid ||
    (api === 'activateProject' && response?.activaterojectresponse?.jobid) || ''
}

export function projectBulkJobSucceeded (api, target, result) {
  if (result?.jobstatus !== 1) return false
  if (api === 'deleteProject') return result.jobresult?.success === true
  const project = result.jobresult?.project
  return project?.id === target.id && project.state === (api === 'activateProject' ? 'Active' : 'Suspended')
}
