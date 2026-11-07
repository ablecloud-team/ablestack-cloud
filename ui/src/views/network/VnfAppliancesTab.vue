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
  <section class="network-vnf-appliances">
    <NetworkRulesToolbar :show-add="false" :loading="fetchLoading" @refresh="fetchData" />
    <a-alert
      v-if="listRefreshFailed"
      type="warning"
      show-icon
      :message="$t('message.guest.feature.load.failed')" />
    <a-table
      v-if="!listRefreshFailed || virtualmachines.length > 0"
      size="small"
      :scroll="{ x: 680 }"
      :columns="columns"
      :dataSource="virtualmachines"
      :rowKey="item => item.id"
      :pagination="false"
      :loading="fetchLoading"
    >
      <template #emptyText>{{ $t('message.guest.feature.empty.vnfs') }}</template>
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'name'">
          <router-link :to="{ path: '/vnfapp/' + record.id }" >{{ text }}</router-link>
        </template>
        <template v-if="column.key === 'status'">
          <status class="status" :text="record.state" displayText />
        </template>
        <template v-if="column.key === 'vmip'">
          <status class="status" :text="record.vmip" displayText />
        </template>
        <template v-if="column.key === 'templatename'">
          <router-link v-if="record.templateid && canList('listTemplates')" :to="{ path: '/template/' + record.templateid }" >{{ record.templatename || record.templateid }}</router-link>
          <span v-else>{{ record.templatename || '—' }}</span>
        </template>
        <template v-if="column.key === 'osdisplayname'">
          <status class="status" :text="record.osdisplayname" displayText />
        </template>
        <template v-if="column.key === 'hostname'">
          <router-link v-if="record.hostid && canList('listHosts')" :to="{ path: '/host/' + record.hostid }" >{{ record.hostname || record.hostid }}</router-link>
          <span v-else>{{ record.hostname || '—' }}</span>
        </template>
      </template>
    </a-table>
  </section>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchData'])],
  name: 'VnfAppliancesTab',
  components: {
    Status,
    NetworkRulesToolbar
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
      virtualmachines: [],
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
          title: this.$t('label.ipaddress'),
          dataIndex: 'vmip'
        },
        {
          key: 'templatename',
          title: this.$t('label.templatename'),
          dataIndex: 'templatename'
        },
        {
          title: this.$t('label.osdisplayname'),
          dataIndex: 'osdisplayname'
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
    canList (api) {
      return api in this.$store.getters.apis
    },
    fetchData () {
      if (!this.resource.id || !this.canList('listVnfAppliances')) return
      const listRequest = this.listRequestToken('fetchData')
      var params = {
        details: 'nics,tmpl,vnfnics',
        isVnf: true,
        listAll: true
      }
      if (this.$route.fullPath.startsWith('/vpc')) {
        params.vpcid = this.resource.id
      } else {
        params.networkid = this.resource.id
      }
      this.fetchLoading = !listRequest.loaded
      return getAPI('listVnfAppliances', params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        const vms = json.listvnfappliancesresponse.virtualmachine || []
        this.virtualmachines = vms.map(vm => ({
          ...vm,
          vmip: (vm.nic || []).filter(nic => nic.networkid === this.resource.id).map(nic => nic.ipaddress || nic.ip6address).filter(Boolean).join(', ') || '—'
        }))
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
.network-vnf-appliances {
  min-width: 0;
}

.status {
  margin-top: -5px;

  &--end {
    margin-left: 5px;
  }
}
</style>
