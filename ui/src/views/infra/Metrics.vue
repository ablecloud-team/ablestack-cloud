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
    <a-card class="breadcrumb-card breadcrumb-toolbar content-breadcrumb">
      <div class="breadcrumb-toolbar-row">
        <div class="breadcrumb-toolbar-path">
          <breadcrumb>
            <template #end>
              <a-tooltip placement="bottom">
                <template #title>{{ $t('label.refresh') }}</template>
                <a-button
                  :loading="loading"
                  shape="round"
                  size="small"
                  @click="fetchDetails()">
                  <template #icon><ReloadOutlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
              </a-tooltip>
            </template>
          </breadcrumb>
        </div>
      </div>
    </a-card>
    <a-card>
      <a-row :gutter="12">
        <a-col :md="24" :lg="12" :gutter="12">
          <a-card>
            <template #title>
              {{ $t('label.desc.db.stats') }}
            </template>
            <mold-list-table
              class="metric-card"
              :columns="columns"
              :loading="loading"
              :data-source="dbMetrics"
              :pagination="false"
              size="middle"
              :rowClassName="getRowClassName"
            />
          </a-card>
        </a-col>
        <a-col :md="24" :lg="12" :gutter="12">
          <a-card>
            <template #title>
              {{ $t('label.desc.usage.stats') }}
            </template>
            <mold-list-table
              class="metric-card"
              :columns="columns"
              :data-source="usageMetrics"
              :pagination="false"
              size="middle"
              :rowClassName="getRowClassName"
            />
            <div>
              <p>{{ $t('label.usage.explanation') }}</p>
            </div>
          </a-card>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>

<script>
import MoldListTable from '@/components/view/MoldListTable'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { getAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import Breadcrumb from '@/components/widgets/Breadcrumb'

export default {
  mixins: [listRefreshMixin(['fetchDetails'], { interval: 60000 })],
  name: 'Metrics',
  components: {
    MoldListTable,
    Breadcrumb
  },
  props: {
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      dbMetrics: [],
      usageMetrics: [],
      columns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name',
          width: '30%'
        },
        {
          title: this.$t('label.value'),
          dataIndex: 'value'
        }
      ]
    }
  },
  mounted () {
    this.fetchDetails()
  },
  watch: {
  },
  methods: {
    mapToArray (map) {
      /* eslint-disable no-unused-vars */
      var array = []
      for (var key in map) {
        if (key === 'replicas') {
        // we don't display replica's at this stage as usually they are not used,
        // only some people experimenting with galera use them.
        } else if (key === 'dbloadaverages') {
          map[key].forEach(function (value, i) {
            var metric = {}
            if (i === 0) {
              metric.name = 'queries/second over the latest stats collection period'
            } else {
              metric.name = 'queries/seconds-' + (i + 1)
            }
            metric.value = value
            array.push(metric)
          })
        } else if (key === 'connections') {
          var metric = {}
          metric.name = 'connection attempts since start'
          metric.value = map[key]
          array.push(metric)
        } else if (key === 'uptime') {
          metric = {}
          metric.name = 'uptime in seconds'
          metric.value = map[key]
          array.push(metric)
        } else if (key === 'collectiontime' || key === 'lastheartbeat' || key === 'lastsuccessfuljob') {
          metric = {}
          metric.name = key
          metric.value = this.$toLocaleDate(map[key]) // needs a conversion
          array.push(metric)
        } else {
          metric = {}
          metric.name = key
          metric.value = map[key]
          array.push(metric)
        }
      }
      /* eslint-enable no-unused-vars */
      return array
    },
    async fetchDetails () {
      const request = this.listRequestToken('fetchDetails')
      try {
        const [db, usage] = await Promise.all([getAPI('listDbMetrics'), getAPI('listUsageServerMetrics')])
        if (!this.isListRequestCurrent('fetchDetails', request)) return
        this.dbMetrics = this.mapToArray(db.listdbmetricsresponse.dbMetrics)
        this.usageMetrics = this.mapToArray(usage.listusageservermetricsresponse.usageMetrics)
      } catch (error) {
        if (!this.isListRequestCurrent('fetchDetails', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      }
    },
    fetchUsageListData () {
      this.columns = []
      this.columns.push({
        dataIndex: 'name',
        title: this.$t('label.name'),
        sorter: (a, b) => genericCompare(a?.name || '', b?.name || '')
      })

      this.columns.push({
        dataIndex: 'value',
        title: this.$t('label.value'),
        sorter: (a, b) => genericCompare(a?.value || '', b?.value || '')
      })
    },
    getRowClassName (record, index) {
      if (index % 2 === 0) {
        return 'light-row'
      }
      return 'dark-row'
    }
  }
}
</script>

<style scoped lang="less">
  .breadcrumb-card {
    margin-left: -24px;
    margin-right: -24px;
    margin-top: -18px;
    margin-bottom: 12px;
  }

  :deep(.ant-table-thead) {
    background-color: #f9f9f9;
  }

  :deep(.ant-table-small) > .ant-table-content > .ant-table-body {
    margin: 0;
  }

  :deep(.light-row) {
    background-color: #fff;
  }

  :deep(.dark-row) {
    background-color: #f9f9f9;
  }
  .metric-card {
    margin-left: -24px;
    margin-right: -24px;
    margin-top: -16px;
    margin-bottom: 12px;
    overflow-y: auto;
    margin-bottom: 100px;
  }
</style>
