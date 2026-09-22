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
  <div>
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <a-input-search
      v-if="showSearch"
      style="width: 25vw;float: right;margin-bottom: 10px; z-index: 8"
      :placeholder="$t('label.search')"
      v-model:value="filter"
      @search="handleSearch"
      v-focus="true" />

    <a-table
      size="small"
      :columns="fetchColumns()"
      :dataSource="dataSource"
      :rowKey="item => item.id"
      :loading="loading"
      :pagination="defaultPagination"
      @change="handleTableChange"
      @handle-search-filter="handleTableChange" >
      <template #bodyCell="{ column, text, record }">
        <div
          v-for="(col, index) in Object.keys(routerlinks({}))"
          :key="index">
          <template v-if="column.key === col">
            <router-link :set="routerlink = routerlinks(record)" :to="{ path: routerlink[col] }" >{{ text }}</router-link>
          </template>

          <template v-else-if="['state', 'status'].includes(column.key)">
            <backup-progress
              v-if="isBackupStatusColumn(column.key)"
              :record="record"
              :statusText="text ? text : ''"
              @capabilities-change="record.capabilities = $event"
              @restore-finished="record.restoreoperationpending = false; record.restorejobstate = $event" />
            <template v-else>
              <status :text="text ? text : ''" />{{ text }}
            </template>
          </template>

          <template v-else-if="column.key === 'created'">
            {{ $toLocaleDate(text) }}
          </template>

          <template v-else-if="column.key === 'size' || column.key === 'virtualsize'">
            {{ $bytesToHumanReadableSize(text) }}
          </template>

          <template v-else>
            {{ text }}
          </template>
        </div>
      </template>
    </a-table>

    <div v-if="!defaultPagination" style="display: block; text-align: right; margin-top: 10px;">
      <a-pagination
        size="small"
        :current="options.page"
        :pageSize="options.pageSize"
        :total="total"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="device === 'desktop' ? ['20', '50', '100', '200'] : ['10', '20', '50', '100', '200']"
        @change="handleTableChange"
        @showSizeChange="handlePageSizeChange"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
  </div>

</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import BackupProgress from '@/components/view/BackupProgress'
import Status from '@/components/widgets/Status'

export default {
  name: 'ListResourceTable',
  components: {
    BackupProgress,
    Status
  },
  mixins: [listRefreshMixin(['fetchData']), mixinDevice],
  props: {
    resource: {
      type: Object,
      default: () => {}
    },
    apiName: {
      type: String,
      default: ''
    },
    routerlinks: {
      type: Function,
      default: () => { return {} }
    },
    params: {
      type: Object,
      default: () => {}
    },
    columns: {
      type: Array,
      required: true
    },
    showSearch: {
      type: Boolean,
      default: true
    },
    items: {
      type: Array,
      default: () => []
    }
  },
  data () {
    return {
      loading: false,
      dataSource: [],
      total: 0,
      filter: '',
      defaultPagination: false,
      options: {
        page: 1,
        pageSize: 10,
        keyword: null
      }
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (newItem !== oldItem) {
          this.fetchData()
        }
      }
    },
    items: {
      deep: true,
      handler (newItem) {
        if (newItem) {
          this.dataSource = newItem
        }
      }
    },
    '$i18n.global.locale' (to, from) {
      if (to !== from) {
        this.fetchData()
      }
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      if (this.items && this.items.length > 0) {
        this.dataSource = this.items
        this.defaultPagination = {
          showSizeChanger: true,
          pageSizeOptions: this.mixinDevice === 'desktop' ? ['20', '50', '100', '200'] : ['10', '20', '50', '100', '200']
        }
        return
      }
      this.loading = !listRequest.loaded
      var params = { ...this.params, ...this.options }
      params.listall = true
      params.response = 'json'
      params.details = 'min'
      return getAPI(this.apiName, params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        var responseName
        var objectName
        for (const key in json) {
          if (key.includes('response')) {
            responseName = key
            break
          }
        }
        for (const key in json[responseName]) {
          if (key === 'count') {
            this.total = json[responseName][key]
            continue
          }
          objectName = key
          break
        }
        this.dataSource = json[responseName][objectName]
        if (!this.dataSource || this.dataSource.length === 0) {
          this.dataSource = []
        }
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.loading = false
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.loading = false
      })
    },
    fetchColumns () {
      var columns = []
      for (const col of this.columns) {
        columns.push({
          key: col,
          dataIndex: col,
          title: this.$t('label.' + col)
        })
      }
      return columns
    },
    isBackupStatusColumn (key) {
      return this.apiName === 'listBackups' && key === 'status'
    },
    handleSearch (value) {
      this.filter = value
      this.options.page = 1
      this.options.pageSize = 10
      this.options.keyword = this.filter
      this.fetchData()
    },
    handleTableChange (page, pagesize) {
      this.options.page = page
      this.options.pageSize = pagesize
      this.fetchData()
    },
    handlePageSizeChange (page, pagesize) {
      this.options.page = 1
      this.options.pageSize = pagesize
      this.fetchData()
    }
  }
}
</script>
