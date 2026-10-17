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

import { mount } from '@vue/test-utils'
import fs from 'fs'
import path from 'path'
import SharedFSTab from '@/views/storage/SharedFSTab'

// Compile the actual production NFS table fragment so a stale hardcoded scroll
// prop cannot pass a test that only repeats the desired table options.
const source = fs.readFileSync(path.resolve(__dirname, '../../../../src/views/storage/SharedFSTab.vue'), 'utf8')
const columnsBinding = source.indexOf(':columns="nfsExportColumns"')
const tableStart = source.lastIndexOf('<a-table', columnsBinding)
const tableEnd = source.indexOf('</a-table>', columnsBinding) + '</a-table>'.length
const productionNfsTable = source.slice(tableStart, tableEnd)
const render = device => mount({
  template: productionNfsTable,
  components: SharedFSTab.components,
  data: () => ({ device, nfsExportRows: [] }),
  computed: {
    nfsExportColumns: SharedFSTab.computed.nfsExportColumns,
    nfsExportScrollWidth: SharedFSTab.computed.nfsExportScrollWidth
  },
  methods: { storageTableLocale: () => ({ emptyText: 'No exports' }) }
}, { global: { mocks: { $t: key => key } } })
// AntD's public ATable forwards attrs to its real InteralTable component.
const exportTable = wrapper => wrapper.findAllComponents({ name: 'InteralTable' })
  .find(component => component.props('columns')?.some(column => column.key === 'localProbe'))

describe('#918 NFS export verification column visibility', () => {
  it.each(['desktop', 'tablet', 'mobile'])('supplies the full width of all NFS export columns to the production table on %s', device => {
    const wrapper = render(device)
    try {
      const table = exportTable(wrapper)
      expect(table).toBeDefined()
      const columns = table.props('columns')
      expect(columns).toHaveLength(14)
      expect(columns.find(column => column.key === 'localProbe').width).toBe(340)
      expect(table.props('scroll').x).toBe(2870)
      expect(table.props('scroll').x).toBe(columns.reduce((total, column) => total + column.width, 0))
    } finally { wrapper.unmount() }
  })

  it.each(['tablet', 'mobile'])('allows the action column to scroll instead of covering local verification on %s', device => {
    const wrapper = render(device)
    try {
      const columns = exportTable(wrapper).props('columns')
      expect(columns.find(column => column.key === 'actions').fixed).toBeUndefined()
      expect(columns.find(column => column.key === 'name').fixed).toBe('left')
      expect(columns.map(column => column.key).slice(-3)).toEqual(['state', 'localProbe', 'actions'])
    } finally { wrapper.unmount() }
  })

  it('keeps existing desktop actions fixed without changing their labels or width', () => {
    const wrapper = render('desktop')
    try {
      const actions = exportTable(wrapper).props('columns').find(column => column.key === 'actions')
      expect(actions.fixed).toBe('right')
      expect(actions.width).toBe(310)
      expect(actions.title).toBe('label.actions')
    } finally { wrapper.unmount() }
  })
})
