// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export const snapshotPolicySortKeys = ['id', 'intervaltype', 'maxsnaps', 'schedule', 'timezone', 'volumename']
export const snapshotPolicyListRoute = route => (route?.meta?.name || route?.name) === 'snapshotpolicy' && !route?.params?.id

export function snapshotPolicyListQuery (query = {}, pageSize = 20) {
  const positive = value => typeof value === 'string' || typeof value === 'number' ? Number.isSafeInteger(Number(value)) && Number(value) > 0 && Number(value) <= 2147483647 : false
  const key = typeof query.sortkey === 'string' ? query.sortkey.toLowerCase() : ''
  const order = typeof query.sortorder === 'string' ? query.sortorder.toLowerCase() : ''
  return { ...query, page: positive(query.page) ? String(Number(query.page)) : '1', pagesize: positive(query.pagesize) ? String(Number(query.pagesize)) : String(positive(pageSize) ? Number(pageSize) : 20), sortkey: snapshotPolicySortKeys.includes(key) ? key : 'id', sortorder: ['asc', 'desc'].includes(order) ? order : 'desc' }
}

export function snapshotPolicySortColumn (key, query, translate) {
  const sort = snapshotPolicyListQuery(query)
  return { sorter: snapshotPolicySortKeys.includes(key), sortOrder: sort.sortkey === key ? (sort.sortorder === 'asc' ? 'ascend' : 'descend') : null, ...(key === 'schedule' ? { showSorterTooltip: { title: translate('message.snapshot.policy.schedule.sort') } } : {}) }
}

export function snapshotPolicySortQuery (query, sorter, pageSize) {
  return snapshotPolicyListQuery({ ...query, page: '1', pagesize: pageSize, sortkey: sorter?.order ? sorter.field : 'id', sortorder: sorter?.order === 'ascend' ? 'asc' : 'desc' })
}
