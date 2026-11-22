// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { systemOfferingListQuery } from '@/utils/systemOfferingTypes'

const minKey = -2147483648
const maxKey = 2147483647

export function canReorderSystemOffering (query, tableSorted) {
  const { state, filter, page, pagesize, ...search } = systemOfferingListQuery(query)
  return !tableSorted && (!state || state === 'all') && (!filter || filter === 'all') &&
    (state === 'all' || filter === 'all') && !Object.keys(search).length
}

export async function readSystemOfferingOrder (getAPI) {
  const settings = await getAPI('listConfigurations', { name: 'sortkey.algorithm' }, { preserveOnFailure: true })
  const value = settings.listconfigurationsresponse?.configuration?.find(entry => entry.name === 'sortkey.algorithm')?.value
  if (value !== 'true' && value !== 'false') throw new Error('Missing sortkey.algorithm')
  const ascending = value === 'true'
  const rows = []
  const ids = new Set()
  let count
  let page = 1
  do {
    const response = await getAPI('listServiceOfferings', { issystem: true, state: 'all', listall: true, page, pagesize: 100 }, { preserveOnFailure: true })
    const list = response.listserviceofferingsresponse
    const batch = list?.serviceoffering || []
    count ??= list?.count || 0
    if ((list?.count || 0) !== count || (!batch.length && rows.length < count)) throw new Error('Offering list changed')
    for (const row of batch) {
      if (!row.id || ids.has(row.id) || !Number.isInteger(row.sortkey)) throw new Error('Invalid offering order')
      ids.add(row.id)
      rows.push(row)
    }
    page++
  } while (rows.length < count)
  if (rows.length !== count) throw new Error('Offering list changed')
  return { rows, ascending }
}

export function systemOfferingOrderChanges (rows, id, direction, ascending) {
  const from = rows.findIndex(row => row.id === id)
  if (from < 0) throw new Error('Offering is no longer listed')
  const bounds = { top: 0, bottom: rows.length - 1, up: from - 1, down: from + 1 }
  const to = bounds[direction]
  if (to === from || to < 0 || to >= rows.length) return []

  const sign = ascending ? 1 : -1
  const key = row => sign * row.sortkey
  if (rows.some((row, i) => i > 0 && key(rows[i - 1]) > key(row))) throw new Error('Offering order changed')

  const lowest = ascending ? minKey : -maxKey
  const highest = ascending ? maxKey : -minKey
  if (to === 0 && key(rows[0]) > lowest) return [{ id, sortkey: sign * (key(rows[0]) - 1) }]
  if (to === rows.length - 1 && key(rows.at(-1)) < highest) return [{ id, sortkey: sign * (key(rows.at(-1)) + 1) }]

  const next = rows.slice()
  next.splice(to, 0, next.splice(from, 1)[0])
  let start = Math.min(from, to)
  let end = Math.max(from, to)
  let minimum = start === 0 ? lowest : key(next[start - 1]) + 1
  let maximum = end === next.length - 1 ? highest : key(next[end + 1]) - 1

  // A tied or densely packed range may require a few neighbouring keys to move too.
  // Only extend the rewritten interval as far as necessary to preserve the surrounding order.
  while (maximum - minimum + 1 < end - start + 1) {
    if (end < next.length - 1) {
      end++
      maximum = end === next.length - 1 ? highest : key(next[end + 1]) - 1
    } else if (start > 0) {
      start--
      minimum = start === 0 ? lowest : key(next[start - 1]) + 1
    } else {
      throw new Error('No sort keys available')
    }
  }
  const first = Math.max(minimum, Math.min(key(rows[start]), maximum - (end - start)))
  return next.slice(start, end + 1)
    .map((row, i) => ({ row, sortkey: sign * (first + i) }))
    .filter(({ row, sortkey }) => row.sortkey !== sortkey)
    .map(({ row, sortkey }) => ({ id: row.id, sortkey }))
}
