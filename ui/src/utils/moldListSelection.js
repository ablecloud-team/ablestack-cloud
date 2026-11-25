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

export const MOLD_ROW_HEIGHT = 47
export const MOLD_SELECTION_WIDTH = 30
const rowControls = 'a, button, input, select, textarea, label, summary, [contenteditable], [role=button], [role=link], [role=checkbox], [role=radio], [role=menuitem], [role=switch], [role=combobox], .ant-select, .ant-table-row-expand-icon'

export function isListRowClick (event) {
  const target = event.target
  return event.button === 0 && !event.defaultPrevented && target?.closest?.('tr, [role=row]') === event.currentTarget &&
    !target.closest(rowControls)
}

export function changedSelectionAnchor (previous, selected, anchor) {
  const changed = [...selected.filter(key => !previous.includes(key)), ...previous.filter(key => !selected.includes(key))]
  return changed.length ? (changed.length === 1 ? changed[0] : null) : anchor
}

export function listSelectionForClick ({ current, key, anchor, orderedKeys, event, multiple }) {
  if (!multiple) return { keys: [key], anchor: key }
  const extend = event.ctrlKey || event.metaKey
  if (event.shiftKey) {
    const start = orderedKeys.indexOf(anchor)
    const end = orderedKeys.indexOf(key)
    if (start >= 0 && end >= 0) {
      const range = orderedKeys.slice(Math.min(start, end), Math.max(start, end) + 1)
      return { keys: extend ? [...new Set([...current, ...range])] : range, anchor }
    }
    return { keys: extend ? [...new Set([...current, key])] : [key], anchor: key }
  }
  const keys = extend
    ? (current.includes(key) ? current.filter(value => value !== key) : [...current, key])
    : [key]
  return { keys, anchor: key }
}

export function listGroupActionAvailable (action, records, getters) {
  return !!action.groupAction && action.api in (getters.apis || {}) &&
    (!action.groupShow || action.groupShow(records, getters)) &&
    records.every(row => (!action.show || action.show(row, getters)) &&
      (!action.disabled || !action.disabled(row, getters, records)))
}

export function visibleListRowKeys (row, records, rowKey, disabled) {
  const keysByText = new Map(records.filter(record => !disabled(record)).map(record => {
    const key = rowKey(record)
    return [String(key), key]
  }))
  return Array.from(row.parentElement.rows)
    .map(element => keysByText.get(element.dataset.rowKey))
    .filter(key => key !== undefined)
}
