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
      <a-row class="breadcrumb-toolbar-row">
        <a-col class="breadcrumb-toolbar-path">
          <breadcrumb>
            <template #end>
              <a-tooltip placement="bottom">
                <template #title>{{ $t('label.refresh') }}</template>
                <a-button
                  :loading="loading"
                  shape="round"
                  size="small"
                  @click="fetchData()"
                >
                  <template #icon><ReloadOutlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
              </a-tooltip>
            </template>
          </breadcrumb>
        </a-col>
      </a-row>
    </a-card>

    <div class="row-element">
      <list-view
        :columns="columns"
        :items="items"
        :loading="loading"
        @refresh="fetchData" />
    </div>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { getAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import ListView from '@/components/view/ListView.vue'

export default {
  mixins: [listRefreshMixin(['fetchData'], { interval: 60000 })],
  name: 'CpuSockets',
  components: {
    ListView,
    Breadcrumb
  },
  provide: function () {
    return {
      parentFetchData: this.fetchData,
      parentToggleLoading: () => { this.loading = !this.loading }
    }
  },
  data () {
    return {
      loading: false,
      items: [],
      data: {},
      columns: []
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    '$i18n.global.locale' (to, from) {
      if (to !== from) {
        this.fetchData()
      }
    }
  },
  methods: {
    async fetchData () {
      const request = this.listRequestToken('fetchData')
      this.loading = !request.loaded
      if (!this.columns.length) {
        this.columns = ['name', 'hosts', 'cpusockets'].map(key => ({
          dataIndex: key,
          title: this.$t('label.' + ({ name: 'hypervisor', hosts: 'hosts', cpusockets: 'cpu.sockets' })[key]),
          sorter: (a, b) => genericCompare(a[key] || '', b[key] || '')
        }))
      }
      const types = ['BareMetal', 'Hyperv', 'KVM', 'LXC', 'Ovm3', 'Simulator', 'VMware', 'XenServer']
      const totals = Object.fromEntries(types.map(type => [type, { name: type === 'Hyperv' ? 'Hyper-V' : type, hosts: 0, cpusockets: 0 }]))
      try {
        let page = 1
        let received = 0
        while (true) {
          const json = await getAPI('listHosts', { type: 'routing', details: 'min', page, pagesize: 100 })
          if (!this.isListRequestCurrent('fetchData', request)) return
          const response = json.listhostsresponse
          const hosts = response.host || []
          hosts.forEach(host => {
            const total = totals[host.hypervisor]
            if (total) { total.hosts += 1; total.cpusockets += Number(host.cpusockets) || 0 }
          })
          received += hosts.length
          if (!hosts.length || received >= (response.count || received)) break
          page += 1
        }
        totals.BareMetal.cpusockets = 'N/A'
        totals.LXC.cpusockets = 'N/A'
        this.items = types.map(type => totals[type])
      } catch (error) {
        if (!this.isListRequestCurrent('fetchData', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      } finally {
        if (this.isListRequestCurrent('fetchData', request)) this.loading = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.breadcrumb-card {
  margin-left: -24px;
  margin-right: -24px;
  margin-top: -18px;
  margin-bottom: 12px;
}

.row-element {
  margin-top: 10px;
  margin-bottom: 10px;
}

.ant-breadcrumb {
  vertical-align: text-bottom;
}

.ant-breadcrumb .anticon {
  margin-left: 8px;
}
</style>
