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

export function getFilteredExternalDetails (details) {
  if (!details || typeof details !== 'object') {
    return null
  }
  const prefix = 'External:'
  const result = {}
  for (const key in details) {
    if (key.startsWith(prefix)) {
      result[key.substring(prefix.length)] = details[key]
    }
  }
  return Object.keys(result).length > 0 ? result : null
}

export function customActionResourceTypes (extensionType) {
  return {
    Orchestrator: ['VirtualMachine'],
    NetworkOrchestrator: ['Network', 'Vpc']
  }[extensionType] || []
}

export const extensionTypeLabels = {
  Orchestrator: 'label.extension.type.orchestrator',
  NetworkOrchestrator: 'label.extension.type.network'
}

export function extensionListQuery (query) {
  const result = Object.fromEntries(['id', 'name', 'keyword', 'page', 'pagesize']
    .filter(key => typeof query[key] === 'string' && query[key])
    .map(key => [key, query[key]]))
  const type = query.type || query.filter
  if (extensionTypeLabels[type]) result.type = type
  if (!result.keyword && typeof query.q === 'string' && query.q) result.keyword = query.q
  return result
}

export function extensionActionListQuery (query) {
  return Object.fromEntries(['actionkeyword', 'actiontype', 'actionstate', 'actionpage', 'actionpagesize']
    .filter(key => typeof query[key] === 'string' && query[key])
    .map(key => [key, query[key]]))
}
