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
  <div class="network-routers-tab">
    <div class="detail-tab-toolbar">
      <a-button :loading="fetchLoading || listRefreshing > 0" @click="fetchData">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert
      v-if="listRefreshFailed"
      class="network-routers-alert"
      type="warning"
      show-icon
      :message="$t(loaded ? 'message.list.refresh.stale' : 'message.request.failed')" />
    <a-table
      size="middle"
      :scroll="{ x: 850 }"
      :columns="columns"
      :dataSource="routers"
      :rowKey="item => item.id"
      :pagination="false"
      :loading="fetchLoading"
    >
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'name'">
          <router-link :to="{ path: '/router/' + record.id }" >{{ text }}</router-link>
        </template>
        <template v-if="column.key === 'status'">
          <status class="status" :text="record.state" displayText />
        </template>
        <template v-if="column.key === 'requiresupgrade'">
          {{ record.requiresupgrade ? $t('label.yes') : $t('label.no') }}
        </template>
        <template v-if="column.key === 'isredundantrouter'">
          {{ record.isredundantrouter ? (record.redundantstate || '—') : $t('label.no') }}
        </template>
        <template v-if="column.key === 'hostname'">
          <router-link v-if="record.hostid" :to="{ path: '/host/' + record.hostid }">{{ record.hostname || record.hostid }}</router-link>
          <span v-else>—</span>
        </template>
      </template>
      <template v-if="listRefreshFailed && !loaded" #emptyText>—</template>
    </a-table>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { detailTabContext } from '@/utils/detailTabData'

import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => vm.listContext })],
  name: 'RoutersTab',
  components: {
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
      fetchLoading: false,
      loaded: false,
      routers: [],
      columns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'status',
          title: this.$t('label.status'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.guestipaddress'),
          dataIndex: 'guestipaddress'
        },
        {
          title: this.$t('label.publicip'),
          dataIndex: 'publicip'
        },
        {
          title: this.$t('label.version'),
          dataIndex: 'version'
        },
        {
          key: 'requiresupgrade',
          title: this.$t('label.requiresupgrade'),
          dataIndex: 'requiresupgrade'
        },
        {
          key: 'isredundantrouter',
          title: this.$t('label.isredundantrouter'),
          dataIndex: 'isredundantrouter'
        },
        {
          key: 'hostname',
          title: this.$t('label.hostname'),
          dataIndex: 'hostname'
        }
      ]
    }
  },
  created () {
    this.fetchData()
  },
  computed: {
    listContext () { return detailTabContext(this, ['listRouters']) }
  },
  watch: {
    listContext () {
      this.routers = []
      this.loaded = false
      this.listRefreshFailed = false
      this.fetchData()
    }
  },
  methods: {
    fetchData () {
      if (!this.resource.id || !('listRouters' in this.$store.getters.apis)) return
      const listRequest = this.listRequestToken('fetchData')
      const params = {
        listAll: true,
        ignoreproject: true,
        ...(this.resource.projectid ? { projectid: this.resource.projectid } : {})
      }
      if (this.$route.fullPath.startsWith('/vpc')) {
        params.vpcid = this.resource.id
      } else {
        params.networkid = this.resource.id
      }
      this.fetchLoading = !listRequest.loaded
      return getAPI('listRouters', params, { preserveOnFailure: true }).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.routers = json.listroutersresponse.router || []
        this.loaded = true
      }).catch(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.fetchLoading = false
      })
    }
  }
}
</script>
<style lang="scss" scoped>
.network-routers-tab { min-width: 0; }
.network-routers-alert { margin-bottom: 16px; }

.status {
  margin-top: -5px;

  &--end {
    margin-left: 5px;
  }
}
</style>
