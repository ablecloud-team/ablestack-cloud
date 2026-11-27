<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

<template>
  <div
    class="mold-list-table"
    :class="{ 'workspace-table': viewportScroll }"
    v-scroll-activity="viewportScroll && { selector: tableScrollSelector, label: $t($route.meta?.title || 'label.items'), scope }">
    <component
      :is="detailTab ? 'DetailTabTable' : 'a-table'"
      v-bind="$attrs"
      table-layout="fixed"
      :columns="tableColumns"
      :data-source="dataSource"
      :scroll="displayScroll"
      :row-key="keyFor"
      :row-selection="selection"
      :custom-row="rowEvents">
      <template #headerCell="cell">
        <slot name="headerCell" v-bind="cell">
          <TableOverflowText v-if="typeof cell.column.title === 'string'" :text="cell.column.title" />
        </slot>
      </template>
      <template #bodyCell="cell">
        <div class="mold-list-cell-line">
          <slot name="bodyCell" v-bind="cell">
            <TableOverflowText v-if="cell.text != null" :text="String(cell.text)" />
          </slot>
        </div>
      </template>
      <template v-if="viewportScroll && selection && !otherSlots.includes('footer')" #footer>
        <span aria-live="polite">{{ $t('label.items.selected.count', [selection.selectedRowKeys.length]) }}</span>
      </template>
      <template v-for="name in otherSlots" :key="name" #[name]="scope">
        <slot :name="name" v-bind="scope || {}"></slot>
      </template>
    </component>
    <ResourceContextMenu
      v-if="context?.entries.length"
      :entries="context.entries"
      :title-override="context.title"
      :position="context.position"
      @close="context = null"
      @execute-entry="runContextEntry" />
  </div>
</template>

<script>
import { changedSelectionAnchor, defaultListContextEntries, isListRowClick, listSelectionForClick, MOLD_SELECTION_WIDTH, moldListRecords, visibleListRowKeys } from '@/utils/moldListSelection'
import { moldListColumns, moldListColumnsWithFixedSelection, moldListScroll } from '@/utils/moldListColumns'
import ResourceContextMenu from '@/components/view/ResourceContextMenu'
import TableOverflowText from '@/components/view/TableOverflowText'
import '@/style/components/view/mold-list-table.css'
import scrollActivity, { tableScrollSelector } from '@/utils/scrollActivity'

export default {
  name: 'MoldListTable',
  directives: { scrollActivity },
  inheritAttrs: false,
  components: { ResourceContextMenu, TableOverflowText },
  props: {
    columns: { type: Array, default: undefined },
    dataSource: { type: Array, default: () => [] },
    rowKey: { type: [String, Function], default: 'key' },
    rowSelection: { type: Object, default: null },
    customRow: { type: Function, default: null },
    scroll: { type: Object, default: null },
    viewportScroll: Boolean,
    detailTab: Boolean,
    multiple: { type: Boolean, default: true },
    showSelection: { type: Boolean, default: true },
    scope: { type: [String, Number], default: '' },
    contextActions: { type: Function, default: null },
    externalContext: Boolean
  },
  emits: ['row-context', 'selection-change'],
  data () {
    return { localKeys: [], selectionAnchor: null, context: null }
  },
  computed: {
    tableScrollSelector () { return tableScrollSelector },
    records () { return moldListRecords(this.dataSource) },
    recordKeys () {
      return new Map(this.records.map((row, index) => {
        const key = typeof this.rowKey === 'function' ? this.rowKey(row, index) : row[this.rowKey]
        return [row, key ?? row.id ?? row.name ?? index]
      }))
    },
    many () { return !!this.rowSelection && this.multiple && this.rowSelection.type !== 'radio' },
    keys () { return this.rowSelection?.selectedRowKeys ?? this.localKeys },
    selection () {
      if (!this.showSelection) return null
      return {
        ...this.rowSelection,
        type: 'checkbox',
        columnWidth: MOLD_SELECTION_WIDTH,
        hideSelectAll: !this.many || this.rowSelection?.hideSelectAll,
        selectedRowKeys: this.keys,
        onChange: this.changeSelection
      }
    },
    otherSlots () { return Object.keys(this.$slots).filter(name => !['headerCell', 'bodyCell'].includes(name)) },
    displayColumns () { return this.columns ? moldListColumns(this.columns) : undefined },
    tableColumns () { return this.showSelection && this.displayColumns ? moldListColumnsWithFixedSelection(this.displayColumns) : this.displayColumns },
    displayScroll () { return this.displayColumns?.length ? moldListScroll(this.displayColumns, this.scroll, this.showSelection) : this.scroll }
  },
  watch: {
    keys () { this.context = null },
    scope () { this.resetSelection() },
    records () { return moldListRecords(this.dataSource) },
    recordKeys () {
      this.context = null
      const available = new Set(this.records.filter(row => !this.disabled(row)).map(this.keyFor))
      if (!available.has(this.selectionAnchor)) this.selectionAnchor = null
      const nextKeys = this.keys.filter(key => available.has(key))
      if (nextKeys.length !== this.keys.length) this.commitSelection(nextKeys)
    }
  },
  deactivated () { this.resetSelection() },
  methods: {
    keyFor (record) { return this.recordKeys.get(record) },
    disabled (record) { return this.rowSelection?.getCheckboxProps?.(record)?.disabled || false },
    commitSelection (keys) {
      const records = this.records.filter(row => keys.includes(this.keyFor(row)))
      if (this.rowSelection?.onChange) this.rowSelection.onChange(keys, records)
      else this.localKeys = keys
      this.$emit('selection-change', keys, records)
    },
    changeSelection (next) {
      const keys = this.many ? next : next.slice(-1)
      this.selectionAnchor = changedSelectionAnchor(this.keys, keys, this.selectionAnchor)
      this.commitSelection(keys)
    },
    resetSelection () {
      this.selectionAnchor = null
      this.context = null
      if (this.keys.length) this.commitSelection([])
    },
    rowEvents (record, index) {
      const existing = this.customRow?.(record, index) || {}
      const key = this.keyFor(record)
      return {
        ...existing,
        tabindex: 0,
        'aria-selected': this.keys.includes(key),
        onMousedown: event => {
          if (existing.onMousedown) existing.onMousedown(event)
          if (this.many && event.shiftKey && isListRowClick(event) && !this.disabled(record)) event.preventDefault()
        },
        onClick: event => {
          if (existing.onClick) existing.onClick(event)
          if (!isListRowClick(event) || this.disabled(record)) return
          const { keys, anchor } = listSelectionForClick({
            current: this.keys,
            key,
            anchor: this.selectionAnchor,
            orderedKeys: event.shiftKey ? visibleListRowKeys(event.currentTarget, this.records, this.keyFor, this.disabled) : [],
            event,
            multiple: this.many
          })
          this.selectionAnchor = anchor
          this.commitSelection(keys)
        },
        onContextmenu: event => this.openContext(record, event, existing.onContextmenu),
        onKeydown: event => {
          if (existing.onKeydown) existing.onKeydown(event)
          if (!event.defaultPrevented && (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10'))) {
            this.openContext(record, event, existing.onContextmenu)
          }
        }
      }
    },
    openContext (record, event, previous) {
      if (this.disabled(record)) return
      this.context = null
      const key = this.keyFor(record)
      const row = event.currentTarget
      const keys = this.keys.includes(key) ? this.keys : [key]
      this.selectionAnchor = key
      if (!this.keys.includes(key)) this.commitSelection(keys)
      const rect = row.getBoundingClientRect()
      const position = event.type === 'keydown' ? { x: rect.left + 24, y: rect.top + 24 } : { x: event.clientX, y: event.clientY }
      const selected = this.records.filter(item => keys.includes(this.keyFor(item)))
      if (this.externalContext) {
        event.preventDefault()
        event.stopPropagation()
        this.$emit('row-context', { event, record, keys, selected, position })
        return
      }
      if (previous) {
        previous(event)
        return
      }
      const title = String(record.displayname || record.name || record.title || record.id || '')
      const supplied = this.contextActions?.(record, selected) || []
      const entries = supplied.length || selected.length !== 1 ? supplied : defaultListContextEntries(row, title, this.$router, this.$copyText)
      if (!entries.length) return
      event.preventDefault()
      event.stopPropagation()
      this.$nextTick(() => {
        if (this.keys.length !== keys.length || keys.some(key => !this.keys.includes(key)) || !this.recordKeys.has(record)) return
        this.context = { entries, title, position }
      })
    },
    runContextEntry (entry) {
      this.context = null
      if (entry.handler) entry.handler()
    }
  }
}
</script>
