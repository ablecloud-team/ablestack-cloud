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

import { getAPI } from '@/api'

export const selectorTypes = ['ISO', 'TEMPLATE', 'SNAPSHOT', 'VOLUME', 'BACKUP']
export const selectorConfigKey = 'js.interpretation.enabled'
export const selectorPage = 'secondarystorageselector'
export const selectorPageSizes = ['10', '20', '50', '100', '200']
export const selectorApi = {
  list: 'listSecondaryStorageSelectors',
  create: 'createSecondaryStorageSelector',
  update: 'updateSecondaryStorageSelector',
  remove: 'removeSecondaryStorageSelector'
}

const uuidPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const quotedLiteral = /^(['"])([^'"]+)\1;?$/

export function isSelectorUuid (value) {
  return typeof value === 'string' && uuidPattern.test(value)
}

export function selectorListQuery (query = {}) {
  const result = {}
  if (isSelectorUuid(query.zoneid)) result.zoneid = query.zoneid
  if (selectorTypes.includes(query.type)) result.type = query.type
  if (query.showremoved === 'true') result.showremoved = 'true'
  if (typeof query.keyword === 'string' && query.keyword.trim()) result.keyword = query.keyword.trim()
  if (Number.isSafeInteger(Number(query.page)) && Number(query.page) > 0) result.page = String(Number(query.page))
  if (selectorPageSizes.includes(String(query.pagesize))) result.pagesize = String(query.pagesize)
  return result
}

export function fixedSelectorId (rule) {
  if (typeof rule !== 'string') return ''
  const source = rule.trim().replace(/\r\n/g, '\n')
  const literal = quotedLiteral.exec(source)?.[2]
  if (isSelectorUuid(literal)) return literal.toLowerCase()
  const id = /return pool\.id === '([^']+)';/.exec(source)?.[1]
  const omitIndent = script => script.replace(/^[ \t]+/gm, '')
  return isSelectorUuid(id) && omitIndent(source.replace(/;$/, '')) === omitIndent(fixedSelectorRule(id)) ? id.toLowerCase() : ''
}

export function fixedSelectorRule (id) {
  if (!isSelectorUuid(id)) return ''
  return `function findStorageWithSpecificId(pool) {
    return pool.id === '${id}';
}

secondaryStorages.filter(findStorageWithSpecificId)[0].id`
}

export async function readSecondaryStorageSelectors (zoneid, { type, showRemoved = false } = {}) {
  if (!isSelectorUuid(zoneid)) return []
  const params = { zoneid, showremoved: showRemoved }
  if (selectorTypes.includes(type)) params.type = type
  const data = await getAPI(selectorApi.list, params, { preserveOnFailure: true })
  const response = data.listsecondarystorageselectorsresponse
  if (!response || (response.heuristics != null && !Array.isArray(response.heuristics))) throw new Error('Invalid selector list')
  return response.heuristics || []
}

export async function readSelectorStores (zoneid) {
  const json = await getAPI('listImageStores', { zoneid, pagesize: -1 }, { preserveOnFailure: true })
  return (json.listimagestoresresponse.imagestore || []).filter(store => store.zoneid === zoneid && isSelectorUuid(store.id))
}
