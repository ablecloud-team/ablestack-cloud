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
  <a-spin :spinning="fetchLoading">
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <resource-table-toolbar :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData()" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="vms" :pagination="false" :scroll="{ x: 720 }">
      <template #bodyCell="{ column, record, text }">
        <router-link v-if="column.key === 'name'" :to="{ path: '/systemvm/' + record.id }">{{ text }}</router-link>
        <status v-else-if="['state', 'agentstate'].includes(column.key)" :text="text || $t('label.unknown')" display-text />
        <span v-else-if="column.key === 'systemvmtype'">{{ $t(text === 'consoleproxy' ? 'label.console.proxy.vm' : 'label.secondary.storage.vm') }}</span>
        <router-link v-else-if="column.key === 'hostname' && record.hostid && $store.getters.apis.listHosts" :to="{ path: '/host/' + record.hostid }">{{ text }}</router-link>
        <span v-else>{{ text || '—' }}</span>
      </template>
    </a-table>
  </a-spin>
</template>

<script>
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchData'])],
  name: 'SystemVmsTab',
  components: {
    ResourceTableToolbar,
    Status
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      vms: [],
      fetchLoading: false
    }
  },
  computed: {
    columns () {
      return Object.entries({ name: 'name', state: 'vmstate', agentstate: 'agentstate', systemvmtype: 'type', publicip: 'publicip', hostname: 'hostname' })
        .map(([key, label]) => ({ key, dataIndex: key, title: this.$t('label.' + label) }))
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem) {
        if (!newItem || !newItem.id) {
          return
        }
        this.fetchData()
      }
    }
  },
  methods: {
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      this.fetchLoading = !listRequest.loaded
      return getAPI('listSystemVms', { zoneid: this.resource.id }).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.vms = json.listsystemvmsresponse.systemvm || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.fetchLoading = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      })
    }
  }
}
</script>
