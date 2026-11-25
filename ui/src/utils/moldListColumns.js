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

import { MOLD_SELECTION_WIDTH } from './moldListSelection'

const compactWidth = {
  filtercolumn: 44, state: 112, status: 112, type: 112, arch: 106, ostype: 132, created: 156,
  qemuagentversion: 106, templatetype: 110, format: 92, order: 124, actions: 108,
  allocated: 156, cores: 90, cpunumber: 100, size: 108, memory: 108, vlan: 90
}

export function moldListColumns (columns, fixedWidths = false) {
  return columns.map((column, index) => {
    const key = String(column.key || column.dataIndex || '')
    const titleWidth = typeof column.title === 'string' ? Math.min(192, 40 + column.title.length * 9) : 0
    const fallback = index === 0 ? 240 : Math.max(compactWidth[key] || 126, titleWidth)
    const declared = Number(column.width)
    const width = fixedWidths || declared > 48 || key === 'filtercolumn' ? (declared || fallback) : fallback
    return {
      ...column,
      width,
      customHeaderCell: (...args) => ({
        ...(typeof column.customHeaderCell === 'function' ? column.customHeaderCell(...args) : {}),
        title: typeof column.title === 'string' ? column.title : undefined
      })
    }
  })
}

export function moldListScroll (columns, existingScroll) {
  return {
    ...existingScroll,
    x: Math.max(Number(existingScroll?.x) || 0, columns.reduce((width, column) => width + (Number(column.width) || 126), MOLD_SELECTION_WIDTH))
  }
}
