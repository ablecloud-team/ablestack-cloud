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
    <resource-table-toolbar :loading="loading || listRefreshing > 0" @refresh="fetchData" />
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <detail-tab-table
      size="small"
      :columns="columns"
      :data-source="securityChecks"
      :loading="loading"
      :pagination="{ defaultPageSize: 10, showSizeChanger: true }"
      :row-key="record => record.checkname"
      :scroll="{ x: 650 }">
      <template #bodyCell="{ column, text, record }">
        <status v-if="column.key === 'status'" :text="record.success === true ? 'True' : 'False'" display-text />
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
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => detailTabRouteKey(vm.$route) })],
  name: 'SecurityCheckTab',
  components: { ResourceTableToolbar, TableOverflowText, Status },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      securityChecks: [],
      loading: false,
      columns: [
        {
          title: this.$t('label.security.check.name'),
          dataIndex: 'checkname'
        },
        {
          key: 'status',
          title: this.$t('label.security.check.success')
        },
        {
          title: this.$t('label.security.check.last.updated'),
          dataIndex: 'lastupdated'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'details'
        }
      ]
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    'resource.id' () {
      this.securityChecks = []
      this.fetchData()
    }
  },
  methods: {
    fetchData () {
      if (!this.resource.id) return
      const listRequest = this.listRequestToken('fetchData')
      this.loading = !listRequest.loaded
      return getAPI('getSecurityCheck', { managementserverid: this.resource.id }, { preserveOnFailure: true }).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.securityChecks = json.getsecuritycheckresponse?.securitychecks?.securitychecks || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.loading = false
      })
    }
  }
}
</script>
