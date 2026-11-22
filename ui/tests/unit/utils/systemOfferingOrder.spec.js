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

import { canReorderSystemOffering, readSystemOfferingOrder, systemOfferingOrderChanges } from '@/utils/systemOfferingOrder'

const ordering = (...keys) => keys.map((sortkey, i) => ({ id: String(i), sortkey }))
function afterChanges (rows, changes, ascending) {
  const replacement = new Map(changes.map(row => [row.id, row.sortkey]))
  return rows.map(row => ({ ...row, sortkey: replacement.get(row.id) ?? row.sortkey }))
    .sort((a, b) => (ascending ? a.sortkey - b.sortkey : b.sortkey - a.sortkey) || a.id.localeCompare(b.id))
    .map(row => row.id)
}

test('only the unfiltered all-state list allows global ordering', () => {
  expect(canReorderSystemOffering({ state: 'all', filter: 'all', page: '2', pagesize: '20' }, false)).toBe(true)
  expect(canReorderSystemOffering({}, false)).toBe(false)
  expect(canReorderSystemOffering({ state: 'active', filter: 'all' }, false)).toBe(false)
  expect(canReorderSystemOffering({ state: 'all', keyword: 'test' }, false)).toBe(false)
  expect(canReorderSystemOffering({ state: 'all', storagetype: 'local' }, false)).toBe(false)
  expect(canReorderSystemOffering({ state: 'all' }, true)).toBe(false)
})

test('reads through the API pages and rejects duplicate offerings after a changing result', async () => {
  const rows = ordering(4, 8)
  const settings = { listconfigurationsresponse: { configuration: [{ name: 'sortkey.algorithm', value: 'true' }] } }
  const page = row => ({ listserviceofferingsresponse: { count: 2, serviceoffering: [row] } })
  const read = jest.fn().mockResolvedValueOnce(settings).mockResolvedValueOnce(page(rows[0])).mockResolvedValueOnce(page(rows[1]))
  await expect(readSystemOfferingOrder(read)).resolves.toEqual({ rows, ascending: true })
  expect(read).toHaveBeenLastCalledWith('listServiceOfferings', { issystem: true, state: 'all', listall: true, page: 2, pagesize: 100 }, { preserveOnFailure: true })
  const changed = jest.fn().mockResolvedValueOnce(settings).mockResolvedValue(page(rows[0]))
  await expect(readSystemOfferingOrder(changed)).rejects.toThrow('Invalid offering order')
})

test('first and last use a single key when there is room', () => {
  const rows = ordering(5, 8, 11, 14)
  const first = systemOfferingOrderChanges(rows, '3', 'top', true)
  expect(first).toEqual([{ id: '3', sortkey: 4 }])
  expect(afterChanges(rows, first, true)).toEqual(['3', '0', '1', '2'])
  const last = systemOfferingOrderChanges(rows, '1', 'bottom', true)
  expect(last).toEqual([{ id: '1', sortkey: 15 }])
  expect(afterChanges(rows, last, true)).toEqual(['0', '2', '3', '1'])
})

test('moves within a tie without overwriting unrelated key ranges', () => {
  const rows = ordering(0, 4, 4, 4, 10)
  const changes = systemOfferingOrderChanges(rows, '2', 'down', true)
  expect(afterChanges(rows, changes, true)).toEqual(['0', '1', '3', '2', '4'])
  expect(changes.map(c => c.id)).not.toContain('0')
  expect(changes.map(c => c.id)).not.toContain('4')
})

test('descending and integer endpoint order use representable keys', () => {
  const rows = ordering(2147483647, 2147483647, 5, -2147483648)
  const changes = systemOfferingOrderChanges(rows, '2', 'top', false)
  expect(afterChanges(rows, changes, false)).toEqual(['2', '0', '1', '3'])
  expect(changes.every(row => row.sortkey >= -2147483648 && row.sortkey <= 2147483647)).toBe(true)
})
