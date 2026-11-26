// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import { listGroupActionAvailable, listSelectionForClick, visibleListRowKeys } from '@/utils/moldListSelection'

describe('Mold list selection policy', () => {
  const pick = (event, key, current = ['a', 'b'], anchor = 'b', multiple = true) =>
    listSelectionForClick({ event, key, current, anchor, multiple, orderedKeys: ['d', 'b', 'a', 'c'] })

  it('replaces an existing selection with the ordinary target', () => {
    expect(pick({}, 'c')).toEqual({ keys: ['c'], anchor: 'c' })
  })

  it('uses Ctrl and Command to add and remove rows', () => {
    expect(pick({ ctrlKey: true }, 'c').keys).toEqual(['a', 'b', 'c'])
    expect(pick({ metaKey: true }, 'b').keys).toEqual(['a'])
  })

  it('uses the rendered order for a Shift range and allows extending a range', () => {
    expect(pick({ shiftKey: true }, 'd').keys).toEqual(['d', 'b'])
    expect(pick({ shiftKey: true, ctrlKey: true }, 'c', ['d', 'b']).keys).toEqual(['d', 'b', 'a', 'c'])
    expect(pick({ shiftKey: true }, 'c', [], null)).toEqual({ keys: ['c'], anchor: 'c' })
  })

  it('keeps one row in a list without multiple selection', () => {
    expect(pick({ metaKey: true, shiftKey: true }, 'a', ['d'], 'd', false)).toEqual({ keys: ['a'], anchor: 'a' })
  })

  it('skips non-data and disabled rows when building the visible range', () => {
    const row = { parentElement: { rows: [{ dataset: {} }, { dataset: { rowKey: '2' } }, { dataset: { rowKey: '0' } }, { dataset: { rowKey: '1' } }] } }
    const records = [{ id: 0 }, { id: 1, disabled: true }, { id: 2 }]
    expect(visibleListRowKeys(row, records, item => item.id, item => item.disabled)).toEqual([2, 0])
  })

  it('offers a bulk action only when every selected row meets its permission and state conditions', () => {
    const action = { api: 'work', groupAction: true, groupShow: rows => rows.length > 1, show: row => row.ready, disabled: row => row.locked }
    const getters = { apis: { work: {} } }
    expect(listGroupActionAvailable(action, [{ ready: true }, { ready: true }], getters)).toBe(true)
    expect(listGroupActionAvailable(action, [{ ready: true }, { ready: false }], getters)).toBe(false)
    expect(listGroupActionAvailable(action, [{ ready: true }, { ready: true, locked: true }], getters)).toBe(false)
    expect(listGroupActionAvailable(action, [{ ready: true }, { ready: true }], { apis: {} })).toBe(false)
  })
})
