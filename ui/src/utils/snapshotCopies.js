// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
export function readSnapshotCopyPage (json, snapshotId, pageSize, page = 1) {
  const body = json?.listsnapshotsresponse
  if (!body || (body.snapshot !== undefined && !Array.isArray(body.snapshot))) throw new Error('message.snapshot.copy.incomplete')
  const rows = body.snapshot || []
  const count = body.count ?? (rows.length === 0 ? 0 : NaN)
  if (count > 0 && rows.length === 0 && page <= Math.ceil(count / pageSize)) throw new Error('message.snapshot.copy.incomplete')
  const keys = new Set()
  if (!Number.isInteger(count) || count < rows.length || rows.length > pageSize) throw new Error('message.snapshot.copy.incomplete')
  for (const row of rows) {
    if (row.id !== snapshotId || !row.copy?.id || !['Ready', 'NotReady', 'Unknown'].includes(row.copy.availability) || keys.has(row.copy.id)) throw new Error('message.snapshot.copy.incomplete')
    keys.add(row.copy.id)
  }
  return { rows, count }
}

// Group only the current server page; every reference remains visible in its child table.
export function snapshotCopyZones (rows) {
  const zones = new Map()
  for (const row of rows) {
    const key = 'zone:' + (row.zoneid || 'unknown')
    if (!zones.has(key)) {
      const group = { ...row, key, copies: [] }
      for (const key of ['copy', 'datastoreid', 'datastorename', 'datastoretype', 'datastorestate', 'downloaddetails']) delete group[key]
      zones.set(key, group)
    }
    zones.get(key).copies.push(row)
  }
  return [...zones.values()]
}

export function snapshotCopySize (bytes) {
  if (!Number.isSafeInteger(bytes) || bytes <= 0) return ''
  const gib = bytes >= 2 ** 30
  return (bytes / (gib ? 2 ** 30 : 2 ** 20)).toLocaleString(undefined, { maximumFractionDigits: 2 }) + (gib ? ' GiB' : ' MiB')
}
