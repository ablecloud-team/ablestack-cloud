// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
import { snapshotCopyList } from '@/utils/storageSnapshotSearch'

export function snapshotListSize (value) {
  if ((typeof value !== 'number' && typeof value !== 'string') || (typeof value === 'string' && !/^\d+(?:\.\d+)?$/.test(value))) return null
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return null
  if (bytes === 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']
  const index = Math.max(0, Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1))
  return `${(bytes / Math.pow(1024, index)).toFixed(index ? 2 : 0)} ${units[index]}`
}

export function snapshotListInterval (value) {
  const known = ['MANUAL', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'INTERNAL']
  const interval = typeof value === 'string' ? value.toUpperCase() : ''
  return known.includes(interval) ? 'label.snapshot.list.interval.' + interval.toLowerCase() : 'label.snapshot.list.unknown'
}

// A snapshot keyword/tag does not describe its source volume. Keep only owner/Zone scope.
export function snapshotSourceVolumeRoute (route) {
  const query = Object.fromEntries(['projectid', 'zoneid', 'domainid', 'account', 'isrecursive'].filter(key => typeof route.query?.[key] === 'string' && route.query[key]).map(key => [key, route.query[key]]))
  if (query.projectid === '0') delete query.projectid
  return { path: '/volume', query }
}

export function snapshotListPage (json, route, page, pageSize) {
  const data = json?.listsnapshotsresponse
  const rows = data?.snapshot || []
  const count = Number(data?.count ?? (rows.length ? NaN : 0))
  const fail = () => { throw new Error('message.snapshot.list.incomplete') }
  if (!data || !Array.isArray(rows) || !Number.isSafeInteger(count) || count < 0 || rows.length > pageSize) fail()
  const expected = Math.min(pageSize, Math.max(0, count - (page - 1) * pageSize))
  if (rows.length !== expected) fail()
  const keys = rows.map(row => snapshotCopyList(route) ? row.copy?.id : row.id)
  if (keys.some(key => typeof key !== 'string' || !key) || new Set(keys).size !== keys.length || rows.some(row => typeof row.id !== 'string' || !row.id)) fail()
  return { rows, count }
}

export function snapshotListColumns (selected, available) {
  const previous = available.filter(key => key !== 'virtualsize')
  if (selected.length !== previous.length || previous.some(key => !selected.includes(key))) return selected
  const next = [...selected]
  const physicalIndex = next.indexOf('physicalsize')
  next.splice(physicalIndex < 0 ? next.length : physicalIndex + 1, 0, 'virtualsize')
  return next
}

// Copy queries expose per-copy metrics separately. Legacy top-level zero is not a copy measurement.
export function snapshotListSizeValue (record, key) {
  return record.copy ? record.copy[key] : record[key]
}

const columnStorageKey = user => 'SNAPSHOT_LIST_COLUMNS_V1:' + user
export function loadSnapshotListColumns (storage, user, copies, fallback, available) {
  if (!storage || !user) return fallback
  const saved = storage.get(columnStorageKey(user))
  const mode = copies ? 'copies' : 'logical'
  if (saved?.version === 1 && Array.isArray(saved[mode])) return [...new Set(saved[mode])].filter(key => available.includes(key))
  const selected = snapshotListColumns([...new Set(fallback)].filter(key => available.includes(key)), available)
  saveSnapshotListColumns(storage, user, copies, selected)
  return selected
}
export function saveSnapshotListColumns (storage, user, copies, selected) {
  if (!storage || !user) return
  const key = columnStorageKey(user); const saved = storage.get(key)
  const next = saved?.version === 1 ? { ...saved } : { version: 1 }
  next[copies ? 'copies' : 'logical'] = [...new Set(selected)]
  storage.set(key, next)
}
