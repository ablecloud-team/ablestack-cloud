// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export const snapshotSortKeys = ['created', 'id', 'name', 'state', 'volumename', 'intervaltype', 'virtualsize', 'account', 'domain', 'project']

export function storageSnapshotListQuery (query = {}) {
  const key = typeof query.sortkey === 'string' ? query.sortkey.toLowerCase() : ''
  const order = typeof query.sortorder === 'string' ? query.sortorder.toLowerCase() : ''
  const clean = { ...query, sortkey: snapshotSortKeys.includes(key) ? key : 'created', sortorder: ['asc', 'desc'].includes(order) ? order : 'desc' }
  clean.page = Number.isSafeInteger(Number(query.page)) && Number(query.page) > 0 ? String(Number(query.page)) : '1'
  if (query.pagesize !== undefined) {
    if (Number.isSafeInteger(Number(query.pagesize)) && Number(query.pagesize) > 0) clean.pagesize = String(Number(query.pagesize))
    else delete clean.pagesize
  }
  return clean
}

export function storageSnapshotSortColumn (key, query) {
  const sort = storageSnapshotListQuery(query)
  return { sorter: snapshotSortKeys.includes(key), sortOrder: sort.sortkey === key ? (sort.sortorder === 'asc' ? 'ascend' : 'descend') : null }
}

export function storageSnapshotSortQuery (query, sorter, pageSize) {
  return storageSnapshotListQuery({ ...query, page: '1', pagesize: String(pageSize), sortkey: sorter?.order ? sorter.field : 'created', sortorder: sorter?.order === 'ascend' ? 'asc' : 'desc' })
}
