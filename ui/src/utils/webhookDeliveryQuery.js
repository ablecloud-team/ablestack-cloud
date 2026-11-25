// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"). You may obtain
// a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.

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
