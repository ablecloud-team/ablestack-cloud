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

<template>
  <div class="events-tab">
    <div class="detail-tab-toolbar">
      <a-button :loading="tabLoading || listRefreshing > 0" @click="fetchData">
        <template #icon><reload-outlined /></template>{{ $t('label.refresh') }}
      </a-button>
      <a-dropdown :trigger="['click']">
        <a-button class="detail-tab-toolbar-right">
          <template #icon><setting-outlined /></template>{{ $t('label.select.columns') }}
        </a-button>
        <template #overlay>
          <a-menu>
            <a-menu-item v-for="key in columnKeys" :key="key" @click="updateSelectedColumns(key)">
              <a-checkbox :checked="selectedColumnKeys.includes(key)" />
              {{ $t('label.' + key) }}
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
    <a-alert
      v-if="isKubernetesCluster"
      class="events-order-help"
      type="info"
      show-icon
      :message="$t('message.cks.events.order')"
      :description="$t('message.cks.events.record.created')" />
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <list-view
      :loading="tabLoading"
      :columns="columns"
      :items="events"
      :actions="actions"
      :columnKeys="columnKeys"
      :selectedColumns="selectedColumnKeys"
      ref="listview"
      @update-selected-columns="updateSelectedColumns"
      @refresh="this.fetchData"/>
      <a-pagination
        class="detail-tab-pagination"
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="totalCount"
        :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
          `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.showing')}` :
          `${$t('label.showing')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="pageSizeOptions"
        @change="changePage"
        @showSizeChange="changePage"
        showSizeChanger
        showQuickJumper>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import ListView from '@/components/view/ListView'

const EVENTS_TAB_COLUMNS_KEY = 'events_tab_columns'

export default {
  mixins: [listRefreshMixin(['fetchEvents'])],
  name: 'EventsTab',
  components: {
    ListView
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    resourceType: {
      type: String,
      required: true
    },
    loading: {
      type: Boolean,
      required: true
    }
  },
  data () {
    return {
      tabLoading: false,
      columnKeys: ['level', 'type', 'state', 'description', 'username', 'account', 'domain', 'created'],
      selectedColumnKeys: [],
      columns: [],
      cols: [],
      events: [],
      actions: [],
      page: 1,
      pageSize: 20,
      totalCount: 0,
      resourceTypeValue: ''
    }
  },
  computed: {
    isKubernetesCluster () {
      return this.resourceType === 'KubernetesCluster'
    },
    pageSizeOptions () {
      var sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    }
  },
  created () {
    this.setDefaultColumns()
    this.pageSize = this.pageSizeOptions[0] * 1
    this.fetchData()
  },
  watch: {
    resource: {
      handler () {
        this.fetchEvents()
      }
    }
  },
  methods: {
    setDefaultColumns () {
      const savedColumns = this.$localStorage.get(EVENTS_TAB_COLUMNS_KEY)
      if (savedColumns && Array.isArray(savedColumns) && savedColumns.length > 0) {
        this.selectedColumnKeys = savedColumns
      } else {
        this.selectedColumnKeys = this.columnKeys.filter(x => x !== 'description')
      }
      this.updateColumns()
    },
    fetchData () {
      this.fetchEvents()
    },
    fetchEvents () {
      const listRequest = this.listRequestToken('fetchEvents')

      if (!this.resource.id) {
        return
      }
      const params = {
        page: this.page,
        pagesize: this.pageSize,
        resourceid: this.resource.id,
        resourcetype: this.resourceType,
        listall: true
      }
      this.tabLoading = !listRequest.loaded
      return getAPI('listEvents', params).then(json => {
        if (!this.isListRequestCurrent('fetchEvents', listRequest)) return
        this.totalCount = json?.listeventsresponse?.count || 0
        this.events = json?.listeventsresponse?.event || []
        this.tabLoading = false
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchEvents', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchEvents', listRequest)) return
        this.tabLoading = false
      })
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    updateSelectedColumns (key) {
      if (this.selectedColumnKeys.includes(key)) {
        this.selectedColumnKeys = this.selectedColumnKeys.filter(x => x !== key)
      } else {
        this.selectedColumnKeys.push(key)
      }
      this.$localStorage.set(EVENTS_TAB_COLUMNS_KEY, this.selectedColumnKeys)
      this.updateColumns()
    },
    updateColumns () {
      this.columns = []
      for (var columnKey of this.columnKeys) {
        if (!this.selectedColumnKeys.includes(columnKey)) continue
        this.columns.push({
          key: columnKey,
          title: this.$t('label.' + String(columnKey).toLowerCase()),
          dataIndex: columnKey,
          sorter: (a, b) => { return genericCompare(a[columnKey] || '', b[columnKey] || '') },
          ...(this.isKubernetesCluster && columnKey === 'created' ? { defaultSortOrder: 'descend' } : {}),
          ...(this.isKubernetesCluster && columnKey === 'state'
            ? { stateLabel: record => record.state === 'Created' ? this.$t('state.cks.eventrecord.created') : undefined }
            : {})
        })
      }
    }
  }
}
</script>

<style scoped lang="scss">
@import '@/style/components/view/DetailTab.scss';
.events-order-help { margin-bottom: 16px; }
</style>
