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

import { reactive } from 'vue'

// Same-page tracking survives tab/dialog reopening; it contains no credentials or persistent storage.
const operations = new Map()
const key = (context, id) => JSON.stringify([context, id])
export const deletionOperation = (context, id) => operations.get(key(context, id))
export const clearDeletion = (context, id) => operations.delete(key(context, id))
export function trackDeletion (context, id) {
  const operation = reactive({ busy: true, accepted: false, status: 'pending' })
  operations.set(key(context, id), operation)
  return operation
}
export function deletionTarget (zone, row) {
  const start = Number(row.startasn); const end = Number(row.endasn)
  if (!/^[0-9]+$/.test(String(row.startasn)) || !/^[0-9]+$/.test(String(row.endasn)) || !zone?.id || row.zoneid !== zone.id || !row.id || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || !Number.isSafeInteger(end - start + 1)) throw Error('context')
  return Object.freeze({ id: row.id, zoneid: zone.id, zonename: zone.name || zone.id, startasn: String(start), endasn: String(end), count: end - start + 1 })
}
export function confirmedList (json, responseName, objectName) {
  const response = json?.[responseName]
  if (['listasnrangesresponse', 'listasnumbersresponse'].includes(responseName) && response &&
      Object.getPrototypeOf(response) === Object.prototype && Object.keys(response).length === 0) {
    return { rows: [], count: 0 }
  }
  if (!response || response.errorcode || !Number.isSafeInteger(response.count) || response.count < 0) throw Error('lookup')
  const rows = response[objectName] || []
  if (!Array.isArray(rows) || rows.length > response.count || (response.count > 0 && !rows.length)) throw Error('lookup')
  return { rows, count: response.count }
}
export async function deletionPreview (read, target, current) {
  const zone = (await read('listZones', { id: target.zoneid }))?.listzonesresponse?.zone?.find(row => row.id === target.zoneid)
  if (!current() || !zone) throw Error('context')
  const ranges = confirmedList(await read('listASNRanges', { zoneid: target.zoneid }), 'listasnrangesresponse', 'asnumberrange')
  if (!current() || ranges.rows.length !== ranges.count || ranges.rows.some(row => row.zoneid !== target.zoneid)) throw Error('lookup')
  const range = ranges.rows.find(row => row.id === target.id)
  if (!range) return { ranges: ranges.rows, absent: true }
  if (String(range.startasn) !== target.startasn || String(range.endasn) !== target.endasn) throw Error('context')
  const params = { zoneid: target.zoneid, asnrangeid: target.id, page: 1, pagesize: 1 }
  const total = confirmedList(await read('listASNumbers', params), 'listasnumbersresponse', 'asnumber')
  if (!current()) throw Error('context')
  const allocated = confirmedList(await read('listASNumbers', { ...params, isallocated: true }), 'listasnumbersresponse', 'asnumber')
  if (!current() || total.count !== target.count || allocated.count > total.count || [...total.rows, ...allocated.rows].some(row => row.zoneid !== target.zoneid || row.asnrangeid !== target.id) || allocated.rows.some(row => row.allocationstate !== 'Allocated')) throw Error('lookup')
  return { ranges: ranges.rows, allocated: allocated.count, absent: false }
}
export async function deletionReconciliation (read, target, current) {
  const observed = await deletionPreview(read, target, current)
  if (!observed.absent) return observed
  // A removed UUID is no longer a valid API filter. Requery the Zone's related ASN list instead.
  const members = confirmedList(await read('listASNumbers', { zoneid: target.zoneid, page: 1, pagesize: 1 }), 'listasnumbersresponse', 'asnumber')
  if (!current() || members.rows.some(row => row.zoneid !== target.zoneid || row.asnrangeid === target.id)) throw Error('lookup')
  return observed
}
