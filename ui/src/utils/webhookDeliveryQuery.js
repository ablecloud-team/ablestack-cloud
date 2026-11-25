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

const searchKeys = {
  searchQuery: 'deliveryKeyword',
  eventtype: 'deliveryEventType',
  managementserverid: 'deliveryManagementServerId'
}
const legacySearchKeys = { searchQuery: 'keyword', eventtype: 'eventtype', managementserverid: 'managementserverid' }

export function webhookDeliverySearch (query) {
  return Object.fromEntries(Object.entries(searchKeys).flatMap(([key, field]) => {
    const value = query[field] ?? (query.tab === 'recent.deliveries' ? query[legacySearchKeys[key]] : undefined)
    return typeof value === 'string' && value.trim() ? [[key, value.trim()]] : []
  }))
}

export function webhookDeliveryQuery (query, search, period, start, end, page, pageSize) {
  const next = { ...query, tab: 'recent.deliveries' }
  for (const [key, field] of Object.entries(searchKeys)) {
    delete next[legacySearchKeys[key]]
    delete next[field]
    if (search[key]) next[field] = search[key]
  }
  delete next.q
  delete next.deliveryStart
  delete next.deliveryEnd
  delete next.deliveryPage
  next.deliveryPeriod = period
  if (period === 'custom' && start) next.deliveryStart = new Date(start).toISOString()
  if (period === 'custom' && end) next.deliveryEnd = new Date(end).toISOString()
  if (page > 1) next.deliveryPage = String(page)
  next.deliveryPageSize = String(pageSize)
  return next
}

export function webhookDetailParams (params, id) {
  const { listall, projectid } = params
  return { id, listall, projectid }
}

const webhookListFields = ['keyword', 'q', 'name', 'id', 'scope', 'state', 'domainid', 'account', 'projectid', 'page', 'pagesize', 'sortkey', 'sortorder']

export function webhookListQuery (query) {
  return Object.fromEntries(webhookListFields.flatMap(key => typeof query?.[key] === 'string' && query[key].trim()
    ? [[key, query[key].trim()]]
    : []))
}

export function webhookListDetailQuery (query) {
  const list = webhookListQuery(query)
  return {
    ...(list.projectid ? { projectid: list.projectid } : {}),
    ...(Object.keys(list).length ? { webhookList: JSON.stringify(list) } : {})
  }
}

export function webhookBackQuery (query) {
  if (typeof query.webhookList === 'string') {
    try {
      return webhookListQuery(JSON.parse(query.webhookList))
    } catch {
      // Keep the explicit project context if an older bookmark has an unusable list query.
    }
  }
  return webhookListQuery({ projectid: query.projectid })
}
