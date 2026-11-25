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

function stringValue (value) {
  return typeof value === 'string' ? value.trim() : ''
}

function positiveInteger (value, fallback) {
  const number = Number(stringValue(value))
  return Number.isSafeInteger(number) && number > 0 ? number : fallback
}

export function readConfigurationQuery (query, groups, defaultPageSize) {
  const group = groups.find(entry => entry.name === stringValue(query.group))
  const subgroups = group?.subgroup || []
  const subgroup = subgroups.find(entry => entry.name === stringValue(query.subgroup)) || subgroups[0]
  return {
    group: group?.name || '',
    subgroup: subgroup?.name || '',
    filter: stringValue(query.filter),
    name: stringValue(query.name),
    category: stringValue(query.category),
    page: group ? 1 : positiveInteger(query.page, 1),
    pagesize: positiveInteger(query.pagesize, defaultPageSize)
  }
}

export function writeConfigurationQuery (state, defaultPageSize) {
  return {
    group: state.group || undefined,
    subgroup: state.subgroup || undefined,
    filter: stringValue(state.filter) || undefined,
    name: stringValue(state.name) || undefined,
    category: stringValue(state.category) || undefined,
    page: !state.group && state.page > 1 ? String(state.page) : undefined,
    pagesize: state.pagesize !== defaultPageSize ? String(state.pagesize) : undefined
  }
}

export function listConfigurationParams (state) {
  const params = state.group
    ? { group: state.group, pagesize: -1 }
    : { pagesize: state.pagesize, page: state.page }
  if (state.subgroup) params.subgroup = state.subgroup
  if (state.filter) params.keyword = state.filter
  if (state.name) params.name = state.name
  if (state.category) params.category = state.category
  return { listAll: true, ...params }
}
