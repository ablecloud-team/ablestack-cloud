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

export function projectLimitValue (value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !value.trim())) return null
  const number = Number(value)
  return Number.isSafeInteger(number) && number >= -1 ? number : null
}

export const projectLimitKey = (type, tag) => JSON.stringify([Number(type), tag || null])

export function projectLimitRows (records) {
  return resourceLimitRows(records, true)
}

export function resourceLimitRows (records, project = false) {
  return records.flatMap(record => {
    const type = Number(record.resourcetype)
    const saved = projectLimitValue(record.max ?? -1)
    if (!Number.isInteger(type) || type < 0 || (project && type === 5) || saved === null) return []
    const tag = record.tag || null
    return [{
      key: projectLimitKey(type, tag),
      type,
      tag,
      name: record.resourcetypename || '',
      saved,
      draft: saved,
      status: '',
      detail: ''
    }]
  }).sort((a, b) => a.type - b.type || (a.tag || '').localeCompare(b.tag || ''))
}

export function projectLimitDirty (row) {
  return projectLimitValue(row.draft) !== row.saved
}

export function projectLimitParams (projectid, row, max) {
  return { projectid, resourcetype: row.type, max, ignoreproject: true, ...(row.tag ? { tag: row.tag } : {}) }
}

const backendTagBound = max => max > 0 ? max : Infinity

export function orderProjectLimitChanges (changes, observed) {
  const priority = change => {
    if (change.row.tag) return 1
    return backendTagBound(change.value) < backendTagBound(observed.get(change.row.key)) ? 2 : 0
  }
  return [...changes].sort((a, b) => a.row.type - b.row.type ||
    priority(a) - priority(b))
}

export function projectLimitTagConflict (row, value, rows, observed) {
  if (row.tag) {
    const base = observed.get(projectLimitKey(row.type, null))
    return value > backendTagBound(base)
  }
  return rows.some(other => other.type === row.type && other.tag && observed.get(other.key) > backendTagBound(value))
}

export function projectLimitResponseMatches (reply, projectid, row, max) {
  const result = reply?.updateresourcelimitresponse?.resourcelimit
  return !!result && result.projectid === projectid &&
    projectLimitKey(result.resourcetype, result.tag) === row.key && projectLimitValue(result.max) === max
}

export function projectLimitEditAllowed (resource, getters) {
  return !!resource?.id && resource.state === 'Active' &&
    ['Admin', 'DomainAdmin'].includes(getters.userInfo?.roletype) &&
    ['listProjects', 'listResourceLimits', 'updateResourceLimit'].every(api => !!getters.apis?.[api])
}
