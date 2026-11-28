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
    <resource-table-toolbar :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData" />
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <detail-tab-table
      size="small"
      :columns="columns"
      :data-source="jobs"
      :loading="fetchLoading"
      :row-key="record => record.id || record.jobid"
      :pagination="{ defaultPageSize: 10, showSizeChanger: true }"
      :scroll="{ x: 680 }">
      <template #bodyCell="{ column, text, record }">
        <table-overflow-text v-if="column.dataIndex === 'cmd'" :text="text?.split('.').pop() || '—'" />
        <router-link v-else-if="column.dataIndex === 'account' && record.accountid" :to="'/account/' + record.accountid">{{ text }}</router-link>
        <router-link v-else-if="column.dataIndex === 'domainpath' && record.domainid" :to="{ path: '/domain/' + record.domainid, query: { tab: 'details' } }">{{ text }}</router-link>
        <table-overflow-text v-else :text="String(text ?? '—')" />
      </template>
    </detail-tab-table>
  </div>
</template>

<script>
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import TableOverflowText from '@/components/view/TableOverflowText'
import { detailTabRouteKey, listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'

export default {
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => detailTabRouteKey(vm.$route) })],
  name: 'AsyncJobsTab',
  components: { ResourceTableToolbar, TableOverflowText },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      fetchLoading: false,
      jobs: [],
      columns: [
        {
          title: this.$t('label.command'),
          dataIndex: 'cmd'
        },
        {
          title: this.$t('label.resourcetype'),
          dataIndex: 'jobinstancetype'
        },
        {
          title: this.$t('label.account'),
          dataIndex: 'account'
        },
        {
          title: this.$t('label.domain'),
          dataIndex: 'domainpath'
        },
        {
          title: this.$t('label.created'),
          dataIndex: 'created'
        }
      ]
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    'resource.id' () {
      this.jobs = []
      this.fetchData()
    }
  },
  methods: {
    fetchData () {
      if (!this.resource.id) return
      const listRequest = this.listRequestToken('fetchData')
      this.fetchLoading = !listRequest.loaded
      return getAPI('listAsyncJobs', {
        listall: true,
        isrecursive: true,
        managementserverid: this.resource.id
      }, { preserveOnFailure: true }).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.jobs = json.listasyncjobsresponse.asyncjobs || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (this.isListRequestCurrent('fetchData', listRequest)) this.fetchLoading = false
      })
    }
  }
}
</script>
