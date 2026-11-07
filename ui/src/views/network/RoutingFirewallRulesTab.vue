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
    <NetworkRulesToolbar :can-add="canWrite('createRoutingFirewallRule')" :loading="loading" @add="openRule" @refresh="fetchData" >
      <a-button
        v-if="((canWrite('deleteRoutingFirewallRule')) && this.selectedRowKeys.length > 0)"
        type="primary"
        danger
        @click="bulkActionConfirmation()">
        <template #icon><delete-outlined /></template>
        {{ $t('label.action.bulk.delete.routing.firewall.rules') }}
      </a-button>
    </NetworkRulesToolbar>
    <a-alert v-if="listRefreshFailed" class="network-rules-guidance" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-alert
      class="network-rules-guidance"
      v-if="'egressdefaultpolicy' in resource"
      type="info">
      <template #message>
      <div
        v-html="$t('message.egress.rules.info.for.network').replace('%x', resource.egressdefaultpolicy ? '<b>' + $t('label.allow') + '</b>' :
        '<b>' + $t('label.deny') + '</b>').replace('%y', resource.egressdefaultpolicy ? '<b>' + $t('message.denied') + '</b>' : '<b>' + $t('message.allowed') + '</b>.')" />
      </template>
    </a-alert>
    <MoldDialog
      class="network-rule-dialog"
      size="lg"
      focus-guard
      :visible="addRuleVisible"
      :title="$t('label.network.rule.add')"
      :closable="!loading"
      :keyboard="!loading"
      @cancel="!loading && (addRuleVisible = false)">
      <a-alert v-if="invalidRule" type="error" show-icon :message="$t('message.guest.feature.invalid.rule')" />
      <div class="form conditional-rule-form" v-ctrl-enter="addRule">
        <div class="form__item">
          <div class="form__label">{{ $t('label.sourcecidr') }}</div>
          <a-input v-model:value="newRule.cidrlist" autoFocus></a-input>
        </div>
        <div class="form__item">
          <div class="form__label">{{ $t('label.destcidr') }}</div>
          <a-input v-model:value="newRule.destcidrlist"></a-input>
        </div>
        <div class="form__item">
          <div class="form__label">{{ $t('label.traffictype') }}</div>
          <a-select
            v-model:value="newRule.traffictype"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            @change="val => { handleTrafficTypeChange(val) }" >
            <a-select-option value="ingress" :label="$t('label.ingress')">{{ $t('label.ingress') }}</a-select-option>
            <a-select-option value="egress" :label="$t('label.egress')">{{ $t('label.egress') }}</a-select-option>
          </a-select>
        </div>
        <div class="form__item">
          <div class="form__label">{{ $t('label.protocol') }}</div>
          <a-select
            v-model:value="newRule.protocol"
            style="width: 100%;"
            @change="resetRulePorts"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="opt in protocols" :key="opt" :label="$t('label.' + opt)">
              {{ $t('label.' + opt) }}
            </a-select-option>
          </a-select>
        </div>
        <div v-show="newRule.protocol === 'tcp' || newRule.protocol === 'udp'" class="form__item">
          <div class="form__label">{{ $t('label.startport') }}</div>
          <a-input v-model:value="newRule.startport"></a-input>
        </div>
        <div v-show="newRule.protocol === 'tcp' || newRule.protocol === 'udp'" class="form__item">
          <div class="form__label">{{ $t('label.endport') }}</div>
          <a-input v-model:value="newRule.endport"></a-input>
        </div>
        <div v-show="newRule.protocol === 'icmp'" class="form__item">
          <div class="form__label">{{ $t('label.icmptype') }}</div>
          <a-input v-model:value="newRule.icmptype"></a-input>
        </div>
        <div v-show="newRule.protocol === 'icmp'" class="form__item">
          <div class="form__label">{{ $t('label.icmpcode') }}</div>
          <a-input v-model:value="newRule.icmpcode"></a-input>
        </div>
      </div>
      <template #footer>
        <a-button :disabled="loading" @click="addRuleVisible = false">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="loading" :disabled="!canWrite('createRoutingFirewallRule') || loading" @click="addRule">{{ $t('label.add') }}</a-button>
      </template>
    </MoldDialog>

    <a-table
      size="small"
      style="overflow-y: auto"
      :loading="loading"
      :columns="columns"
      :dataSource="routingRules"
      :pagination="false"
      :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
      :rowKey="record => record.id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'traffictype'">
          {{ record.traffictype }}
        </template>
        <template v-if="column.key === 'protocol'">
          {{ capitalise(record.protocol) }}
        </template>
        <template v-if="column.key === 'startport'">
          {{ record.icmptype || record.startport >= 0 ? record.icmptype || record.startport : 'All' }}
        </template>
        <template v-if="column.key === 'endport'">
          {{ record.icmpcode || record.endport >= 0 ? record.icmpcode || record.endport : 'All' }}
        </template>
        <template v-if="column.key === 'actions'">
          <tooltip-button
            :tooltip="$t('label.delete')"
            :disabled="!(canWrite('deleteRoutingFirewallRule'))"
            type="primary"
            :danger="true"
            icon="delete-outlined"
            buttonClass="rule-action"
            @click="deleteRule(record)" />
        </template>
      </template>
    </a-table>
    <a-pagination
      class="pagination"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalCount"
      :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="['10', '20', '40', '80', '100']"
      @change="handleChangePage"
      @showSizeChange="handleChangePageSize"
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </a-pagination>

    <bulk-action-view
      v-if="showConfirmationAction || showGroupActionModal"
      :showConfirmationAction="showConfirmationAction"
      :showGroupActionModal="showGroupActionModal"
      :items="routingRules"
      :selectedRowKeys="selectedRowKeys"
      :selectedItems="selectedItems"
      :columns="columns"
      :selectedColumns="selectedColumns"
      action="deleteRoutingFirewallRule"
      :loading="loading"
      :message="message"
      @group-action="deleteRules"
      @handle-cancel="handleCancel"
      @close-modal="closeModal" />
  </div>
</template>

<script>
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import { conditionalFirewallRule } from '@/utils/conditionalFirewallRule'
import { canWriteGuestFeature } from '@/utils/conditionalGuestNetwork'
import MoldDialog from '@/components/view/MoldDialog'
import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'
import BulkActionView from '@/components/view/BulkActionView'
import eventBus from '@/config/eventBus'

export default {
  mixins: [listRefreshMixin(['fetchData'])],
  name: 'RoutingFirewallRulesTab',
  components: {
    MoldDialog,
    NetworkRulesToolbar,
    Status,
    TooltipButton,
    BulkActionView
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      addRuleVisible: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['Actions'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.delete.routing.firewall.rules'),
        confirmMessage: this.$t('label.confirm.delete.routing.firewall.rules')
      },
      loading: true,
      invalidRule: false,
      routingRules: [],
      newRule: {
        traffictype: 'ingress',
        protocol: 'tcp',
        cidrlist: null,
        destcidrlist: null,
        networkid: this.resource.id,
        icmptype: null,
        icmpcode: null,
        startport: null,
        endport: null
      },
      totalCount: 0,
      page: 1,
      pageSize: 10,
      columns: [
        {
          title: this.$t('label.sourcecidr'),
          dataIndex: 'cidrlist'
        },
        {
          title: this.$t('label.destcidr'),
          dataIndex: 'destcidrlist'
        },
        {
          key: 'traffictype',
          title: this.$t('label.traffictype')
        },
        {
          key: 'protocol',
          title: this.$t('label.protocol')
        },
        {
          key: 'startport',
          title: this.$t('label.icmptype.start.port')
        },
        {
          key: 'endport',
          title: this.$t('label.icmpcode.end.port')
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ],
      protocols: [
        'tcp',
        'udp',
        'icmp'
      ]
    }
  },
  computed: {
    hasSelected () {
      return this.selectedRowKeys.length > 0
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    resource: function (newItem, oldItem) {
      if (!newItem || !newItem.id) {
        return
      }
      this.fetchData()
    }
  },
  inject: ['parentFetchData', 'resourceActionReady'],
  methods: {
    canWrite (command) {
      return !this.listRefreshFailed && (this.$route.meta.name !== 'guestnetwork' || this.resourceActionReady?.() !== false) &&
        (this.$route.meta.name !== 'guestnetwork' ? command in this.$store.getters.apis : canWriteGuestFeature('routing.firewall', command, this.resource, this.$store.getters))
    },
    openRule () {
      this.resetAllRules()
      this.invalidRule = false
      this.addRuleVisible = true
    },
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      this.loading = !listRequest.loaded
      return getAPI('listRoutingFirewallRules', {
        listAll: true,
        networkid: this.resource.id,
        page: this.page,
        pageSize: this.pageSize
      }).then(response => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.routingRules = response.listroutingfirewallrulesresponse.firewallrule || []
        this.totalCount = response.listroutingfirewallrulesresponse.count || 0
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
    setSelection (selection) {
      this.selectedRowKeys = selection
      this.$emit('selection-change', this.selectedRowKeys)
      this.selectedItems = (this.routingRules.filter(function (item) {
        return selection.indexOf(item.id) !== -1
      }))
    },
    resetSelection () {
      this.setSelection([])
    },
    onSelectChange (selectedRowKeys, selectedRows) {
      this.setSelection(selectedRowKeys)
    },
    bulkActionConfirmation () {
      this.showConfirmationAction = true
      this.selectedColumns = this.columns.filter(column => {
        return !this.filterColumns.includes(column.title)
      })
      this.selectedItems = this.selectedItems.map(v => ({ ...v, status: 'InProgress' }))
    },
    handleCancel () {
      eventBus.emit('update-bulk-job-status', this.selectedItems, false)
      this.showGroupActionModal = false
      this.selectedItems = []
      this.selectedColumns = []
      this.selectedRowKeys = []
      this.parentFetchData()
    },
    deleteRules (e) {
      this.showConfirmationAction = false
      this.selectedColumns.splice(0, 0, {
        key: 'status',
        dataIndex: 'status',
        title: this.$t('label.operation.status'),
        filters: [
          { text: 'In Progress', value: 'InProgress' },
          { text: 'Success', value: 'success' },
          { text: 'Failed', value: 'failed' }
        ]
      })
      if (this.selectedRowKeys.length > 0) {
        this.showGroupActionModal = true
      }
      for (const rule of this.selectedItems) {
        this.deleteRule(rule)
      }
    },
    capitalise (val) {
      if (val === 'all') return this.$t('label.all')
      return val.toUpperCase()
    },
    deleteRule (rule) {
      if (!this.canWrite('deleteRoutingFirewallRule')) return
      this.loading = true
      postAPI('deleteRoutingFirewallRule', { id: rule.id }).then(response => {
        const jobId = response.deleteroutingfirewallruleresponse.jobid
        eventBus.emit('update-job-details', jobId, null)
        this.$pollJob({
          title: this.$t('label.action.delete.routing.firewall.rule'),
          description: rule.id,
          jobId: jobId,
          successMessage: this.$t('message.remove.routing.firewall.rule.success'),
          successMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', this.selectedItems, rule.id, 'success')
            }
            this.fetchData()
          },
          errorMessage: this.$t('message.remove.routing.firewall.rule.failed'),
          errorMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', this.selectedItems, rule.id, 'failed')
            }
            this.fetchData()
          },
          loadingMessage: this.$t('message.remove.routing.firewall.rule.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => this.fetchData(),
          bulkAction: `${this.selectedItems.length > 0}` && this.showGroupActionModal
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchData()
      }).finally(() => {
        this.loading = false
      })
    },
    async addRule () {
      if (this.loading || !this.canWrite('createRoutingFirewallRule')) return
      const params = conditionalFirewallRule(this.newRule, this.resource.id)
      this.invalidRule = !params
      if (!params) return
      this.loading = true
      try {
        const response = await postAPI('createRoutingFirewallRule', params)
        const result = await this.$pollJob({
          jobId: response.createroutingfirewallruleresponse.jobid,
          successMessage: this.$t('message.add.routing.firewall.rule.success'),
          errorMessage: this.$t('message.add.routing.firewall.rule.failed'),
          catchMessage: this.$t('error.fetching.async.job.result')
        })
        if (result.jobstatus === 1) {
          this.addRuleVisible = false
          this.resetAllRules()
        }
        await this.fetchData()
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },
    resetAllRules () {
      this.newRule.traffictype = 'ingress'
      this.newRule.protocol = 'tcp'
      this.newRule.cidrlist = null
      this.newRule.destcidrlist = null
      this.newRule.networkid = this.resource.id
      this.resetRulePorts()
    },
    resetRulePorts () {
      this.newRule.icmptype = null
      this.newRule.icmpcode = null
      this.newRule.startport = null
      this.newRule.endport = null
    },
    closeModal () {
      this.showConfirmationAction = false
    },
    handleChangePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    handleChangePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    },
    handleTrafficTypeChange (trafficType) {
      if (trafficType === 'ingress') {
        if (this.newRule.protocol === 'all') this.newRule.protocol = 'tcp'
        this.protocols = this.protocols.filter(x => x !== 'all')
      } else {
        if (!this.protocols.includes('all')) {
          this.protocols.push('all')
        }
      }
    }
  }
}
</script>

<style scoped lang="scss">
  .conditional-rule-form {
    display: grid;
    gap: 12px;
    grid-template-columns: minmax(0, 1fr);
    @media (min-width: 768px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    :deep(.ant-select) { width: 100%; }
  }

  .rule {

    &-container {
      display: flex;
      width: 100%;
      flex-wrap: wrap;
      margin-right: -20px;
      margin-bottom: -10px;
    }

    &__item {
      padding-right: 20px;
      margin-bottom: 20px;

      @media (min-width: 760px) {
        flex: 1;
      }

    }

    &__title {
      font-weight: bold;
    }

  }

  .add-btn {
    width: 100%;
    padding-top: 15px;
    padding-bottom: 15px;
    height: auto;
  }

  .add-actions {
    display: flex;
    justify-content: flex-end;
    margin-right: -20px;
    margin-bottom: 20px;

    @media (min-width: 760px) {
      margin-top: 20px;
    }

    button {
      margin-right: 20px;
    }

  }

  .rule-action {

    &:not(:last-of-type) {
      margin-right: 10px;
    }

  }

  .pagination {
    margin-top: 20px;
  }
</style>
