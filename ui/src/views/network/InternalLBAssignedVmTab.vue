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
  <div class="internal-lb-backends lb-backend-removal" :data-list-editing="lbBackendBusy">
    <network-rules-toolbar :show-add="false" :loading="fetchLoading || listRefreshing > 0 || lbBackendBusy" @refresh="fetchData" />
    <network-mutation-status :operation="lbBackendOperation" @recheck="recheckLbBackendRemoval" />
    <a-alert v-if="listRefreshFailed" type="warning" show-icon role="status" :message="$t('message.list.refresh.stale')" />
    <detail-tab-table
      v-show="!lbBackendSuspended"
      size="small"
      :loading="fetchLoading"
      :scroll="{ x: 640 }"
      :columns="columns"
      :dataSource="assignedVms"
      :rowKey="item => item.id"
      :pagination="false"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'displayname'">
          <router-link :to="{ path: '/vm/' + record.id }">{{ record.displayname || record.name }}</router-link>
        </template>
        <template v-if="column.key === 'ipaddress'">
          <span v-for="nic in record.nic" :key="nic.id">
            <span v-if="nic.networkid === resource.networkid">
              {{ nic.ipaddress }} <br/>
            </span>
          </span>
        </template>
        <template v-if="column.key === 'remove'">
          <tooltip-button
            :tooltip="$t('label.remove.vm.from.lb')"
            type="primary"
            :danger="true"
            icon="delete-outlined"
            :disabled="!canRemoveInternalBackend(record)"
            :loading="lbBackendBusy && lbBackendOperation.params.virtualmachineids === record.id"
            @onClick="removeVmFromLB(record)" />
        </template>
      </template>
    </detail-tab-table>
    <detail-tab-pagination
      class="row-element pagination"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalInstances"
      :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="['10', '20', '40', '80', '100']"
      @change="changePage"
      @showSizeChange="changePageSize"
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </detail-tab-pagination>
  </div>
</template>
<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { lbBackendRemovalMixin } from '@/utils/lbBackendRemoval'
import { loadBalancerIsProtected } from '@/utils/networkRuleForm'

import { getAPI } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import NetworkMutationStatus from '@/components/view/NetworkMutationStatus'

export default {
  mixins: [listRefreshMixin(['fetchData'], { scopeKey: vm => [vm.lbBackendScope, vm.lbBackendVersion, vm.lbBackendSuspended, vm.page, vm.pageSize] }), lbBackendRemovalMixin],
  name: 'InternalLBAssignedVmTab',
  components: {
    TooltipButton,
    NetworkRulesToolbar,
    NetworkMutationStatus
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      fetchLoading: false,
      assignedVms: [],
      page: 1,
      pageSize: 10,
      totalInstances: 0,
      columns: [
        {
          key: 'displayname',
          title: this.$t('label.name'),
          dataIndex: 'displayname',
          width: 320
        },
        {
          key: 'ipaddress',
          title: this.$t('label.ipaddress'),
          dataIndex: 'ipaddress',
          width: 240
        },
        {
          key: 'remove',
          title: this.$t('label.actions'),
          width: 80,
          fixed: 'right'
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
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      this.fetchLoading = !listRequest.loaded
      return getAPI('listLoadBalancerRuleInstances', {
        id: this.resource.id,
        ignoreproject: true,
        ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}),
        page: this.page,
        pagesize: this.pageSize
      }, { preserveOnFailure: true }).then(response => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.totalInstances = response.listloadbalancerruleinstancesresponse.count || 0
        this.assignedVms = response.listloadbalancerruleinstancesresponse.loadbalancerruleinstance || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      })
    },
    refreshLbBackends () { return this.fetchData() },
    canRemoveInternalBackend (vm) {
      return !!(this.lbBackendAllowed && !this.fetchLoading && !this.listRefreshFailed && !this.listRefreshing &&
        this.resource?.id && !this.resource.autoscalevmgroup && !loadBalancerIsProtected(this.resource) &&
        this.assignedVms.some(item => item.id === vm?.id && item.nic?.some(nic => nic.networkid === this.resource.networkid)))
    },
    removeVmFromLB (vm) {
      if (!this.canRemoveInternalBackend(vm)) return false
      return this.removeLbBackend({
        id: this.resource.id,
        virtualmachineids: vm.id
      })
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    changePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    }
  }
}
</script>
<style lang="scss" scoped>
.internal-lb-backends :deep(.ant-table-cell-fix-right) { background: var(--ui-bg-surface) !important; }
.internal-lb-backends :deep(.ant-table-cell) { overflow-wrap: anywhere; }
</style>
