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

import { defineComponent, h, inject, withDirectives } from 'vue'
import { Pagination, Table } from 'ant-design-vue'
import scrollActivity, { tableScrollSelector } from '@/utils/scrollActivity'

const tableSlot = 'detail-tab-table'
const footerSlot = 'detail-table-footer'

const toHandlers = handler => [handler].flat().filter(fn => typeof fn === 'function')

function paginationLabel (vm, total, range) {
  return total > 0 ? vm.$t('message.detail.tab.total', [total, range[0], range[1]]) : vm.$t('label.total') + ' 0 ' + vm.$t('label.items')
}

export const DetailTabPagination = defineComponent({
  name: 'DetailTabPagination',
  inheritAttrs: false,
  props: {
    enabled: { type: Boolean, default: true }
  },
  setup () {
    return { resourceViewportEnabled: inject('resourceViewportEnabled', () => false) }
  },
  render () {
    if (!this.enabled || !this.resourceViewportEnabled()) return h(Pagination, this.$attrs, this.$slots)
    const { onChange, onShowSizeChange, ...attrs } = this.$attrs
    const zero = Number(attrs.total) === 0
    const formatTotal = attrs.showTotal ?? attrs['show-total']
    const change = (page, size) => {
      const currentSize = attrs.pageSize ?? attrs['page-size']
      const sizeListeners = toHandlers(onShowSizeChange)
      const handlers = currentSize != null && Number(currentSize) !== size && sizeListeners.length ? sizeListeners : toHandlers(onChange)
      handlers.forEach(handler => handler(page, size))
    }
    return h(Pagination, {
      ...attrs,
      class: [attrs.class, 'detail-tab-pagination', footerSlot],
      size: 'small',
      hideOnSinglePage: false,
      ...(zero ? { current: 1, disabled: true, showQuickJumper: false } : {}),
      showTotal: (total, range) => total > 0 && formatTotal ? formatTotal(total, range) : paginationLabel(this, total, range),
      onChange: change
    }, this.$slots)
  }
})

export const DetailTabTable = defineComponent({
  name: 'DetailTabTable',
  inheritAttrs: false,
  props: {
    enabled: { type: Boolean, default: true },
    table: { type: [Object, Function], default: null },
    pagination: { type: [Object, Boolean], default: undefined }
  },
  setup () {
    return { resourceViewportEnabled: inject('resourceViewportEnabled', () => false) }
  },
  render () {
    const attrs = this.$attrs
    const tableComponent = this.table || Table
    if (!this.enabled || !this.resourceViewportEnabled()) {
      return h(tableComponent, { ...attrs, pagination: this.pagination }, this.$slots)
    }

    const paged = this.pagination !== false
    const page = paged ? (this.pagination || {}) : {}
    const pagination = paged
      ? {
        ...page,
        class: [page.class, 'detail-tab-pagination', footerSlot],
        position: ['bottomRight'],
        size: 'small',
        hideOnSinglePage: false,
        showTotal: page.showTotal ?? ((total, range) => paginationLabel(this, total, range))
      }
      : false
    const table = h(tableComponent, { ...attrs, pagination }, this.$slots)
    // The Table owns sort/filter and slices rows once, but omits its paginator when total is zero.
    const emptyPager = paged && !(attrs.loading?.spinning ?? attrs.loading)
      ? h('div', { class: 'detail-table-empty-footer' }, [
        h(DetailTabPagination, {
          current: 1,
          total: 0,
          pageSize: page.pageSize ?? page.defaultPageSize ?? 10,
          pageSizeOptions: page.pageSizeOptions,
          showSizeChanger: page.showSizeChanger
        })
      ])
      : null
    const data = attrs.dataSource ?? attrs['data-source']
    const edge = (row) => row?.id ?? row?.key ?? row?.name
    const scope = JSON.stringify([this.$route?.path, this.$route?.query?.tab, page.current, page.pageSize, edge(data?.[0]), edge(data?.at(-1))])
    return withDirectives(h('div', { class: [tableSlot, 'workspace-table'] }, [table, emptyPager]), [
      [scrollActivity, { selector: tableScrollSelector, label: this.$t('label.items'), scope }]
    ])
  }
})
