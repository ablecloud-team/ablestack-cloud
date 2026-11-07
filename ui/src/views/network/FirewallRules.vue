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
    <network-rules-toolbar :can-add="'createFirewallRule' in $store.getters.apis" :loading="loading || networkMutationBusy" @add="openRuleDialog()" @refresh="fetchData" >
      <a-button
        v-if="(('deleteFirewallRule' in $store.getters.apis) && this.selectedItems.length > 0)"
        type="primary"
        danger
        @click="bulkActionConfirmation()">
        <template #icon><delete-outlined /></template>
        {{ $t('label.action.bulk.delete.firewall.rules') }}
      </a-button>
    </network-rules-toolbar>
    <a-alert v-if="listRefreshFailed" class="network-rules-guidance" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <MoldDialog
      class="network-rule-dialog"
      size="lg"
      focus-guard
      :visible="createModalVisible"
      :title="$t(replacingRule ? 'label.network.rule.replace' : 'label.network.rule.add')"
      :closable="!networkMutationSubmitting"
      :keyboard="!networkMutationSubmitting"
      @cancel="!networkMutationSubmitting && (createModalVisible = false)">
      <div>
        <div><NetworkRuleContext :resource="resource" /><NetworkMutationStatus :operation="networkMutation" @recheck="recheckNetworkMutation" />
          <a-alert v-if="replacingRule" class="mold-dialog-summary" type="warning" show-icon :message="$t('message.network.rule.replace')" />
<a-alert v-if="replacementDeleted" class="mold-dialog-summary" type="warning" show-icon :message="$t('message.public.ip.restore.rule')" />
<div class="form" v-ctrl-enter="addRule">
        <div class="form__item form__item--full">
          <div class="form__label">{{ $t('label.sourcecidr') }}</div>
          <a-input v-focus="true" v-model:value="newRule.cidrlist"></a-input>
        </div>
        <div class="form__item form__item--full">
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
            <a-select-option value="tcp" :label="$t('label.tcp')">{{ $t('label.tcp') }}</a-select-option>
            <a-select-option value="udp" :label="$t('label.udp')">{{ $t('label.udp') }}</a-select-option>
            <a-select-option value="icmp" :label="$t('label.icmp')">{{ $t('label.icmp') }}</a-select-option>
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
          <a-select
            v-model:value="newRule.icmptype"
            @change="val => { updateIcmpCodes(val) }"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="(opt) in icmpTypes" :key="opt.index" :label="opt.description">
              {{ opt.index + ' - ' + opt.description }}
            </a-select-option>
          </a-select>
        </div>
        <div v-show="newRule.protocol === 'icmp'" class="form__item">
          <div class="form__label">{{ $t('label.icmpcode') }}</div>
          <a-select
            v-model:value="newRule.icmpcode"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="(opt) in icmpCodes" :key="opt.code" :label="opt.description">
              {{ opt.code + ' - ' + opt.description }}
            </a-select-option>
          </a-select>
        </div>

      </div>
        </div>
      </div>
      <template #footer>
        <a-button :disabled="networkMutationSubmitting" @click="createModalVisible = false">{{ $t('label.cancel') }}</a-button>
        <a-button v-if="replacementDeleted" :disabled="networkMutationBlocked || loading" @click="restoreRule">{{ $t('label.public.ip.restore.rule') }}</a-button>
        <a-button type="primary" :loading="networkMutationBusy" :disabled="loading || networkMutationBlocked" @click="addRule">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
    <a-table
      size="small"
      style="overflow-y: auto"
      :loading="loading"
      :columns="columns"
      :dataSource="firewallRules"
      :pagination="false"
      :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange, getCheckboxProps: getCheckboxProps}"
      :rowKey="record => record.id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'protocol'">
          {{ getCapitalise(record.protocol) }}
        </template>
        <template v-if="column.key === 'startport'">
          {{ record.icmptype >= 0 ? String(record.icmptype): record.startport >= 0 ? String(record.startport): 'All' }}
        </template>
        <template v-if="column.key === 'endport'">
          {{ record.icmpcode >= 0 ? String(record.icmpcode): record.endport >= 0 ? String(record.endport): 'All' }}
        </template>
        <template v-if="column.key === 'actions'">
          <div class="actions">
            <tooltip-button :tooltip="$t('label.network.rule.replace')" icon="edit-outlined" :disabled="isProtectedManagementRule(record) || !!record.tags?.length || !('deleteFirewallRule' in $store.getters.apis) || !('createFirewallRule' in $store.getters.apis)" @onClick="openRuleDialog(record)" />
            <tooltip-button
              :tooltip="$t('label.edit.tags')"
              icon="tag-outlined"
              :disabled="!('listTags' in $store.getters.apis)"
              buttonClass="rule-action"
              @onClick="() => openTagsModal(record)" />
            <a-popconfirm :title="$t('label.delete') + '?'" @confirm="deleteRule(record)" :disabled="isProtectedManagementRule(record)">
            <tooltip-button
              :tooltip="isProtectedManagementRule(record) ? $t('message.kubernetes.management.rule.delete.disabled') : $t('label.delete')"
              type="primary"
              :danger="true"
              icon="delete-outlined"
              buttonClass="rule-action"
              :disabled="!('deleteFirewallRule' in $store.getters.apis) || isProtectedManagementRule(record)"
               />
            </a-popconfirm>
          </div>
        </template>
      </template>
    </a-table>
    <a-pagination
      class="detail-tab-pagination"
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

    <RuleTagsDialog
      v-if="tagRule && tagRule.parentId === resource.id"
      :resource="resource"
      :rule="tagRule"
      resource-type="FirewallRule"
      @cancel="tagRule = null" />

    <bulk-action-view
      v-if="showConfirmationAction || showGroupActionModal"
      :showConfirmationAction="showConfirmationAction"
      :showGroupActionModal="showGroupActionModal"
      :items="firewallRules"
      :selectedRowKeys="selectedRowKeys"
      :selectedItems="selectedItems"
      :columns="columns"
      :selectedColumns="selectedColumns"
      action="deleteFirewallRule"
      :loading="loading"
      :message="message"
      @group-action="deleteRules"
      @handle-cancel="handleCancel"
      @close-modal="closeModal" />
  </div>
</template>

<script>
import { validPortRange } from '@/utils/networkRuleForm'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { conditionalFirewallRule } from '@/utils/conditionalFirewallRule'
import { networkMutationMixin } from '@/utils/networkMutation'
import NetworkMutationStatus from '@/components/view/NetworkMutationStatus'
import MoldDialog from '@/components/view/MoldDialog'

import NetworkRuleContext from '@/components/view/NetworkRuleContext'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import RuleTagsDialog from '@/components/view/RuleTagsDialog.vue'
import { getAPI, postAPI } from '@/api'
import { clusterManagementPorts, listAllKubernetesPortRules, listKubernetesClustersForIp } from '@/utils/kubernetesPorts'
import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'
import BulkActionView from '@/components/view/BulkActionView'
import eventBus from '@/config/eventBus'

export default {
  mixins: [listRefreshMixin(['fetchData']), networkMutationMixin],
  components: {
    RuleTagsDialog,
    NetworkMutationStatus,
    MoldDialog,
    NetworkRuleContext,
    NetworkRulesToolbar,
    Status,
    TooltipButton,
    BulkActionView
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    protectedManagementPorts: {
      type: Array,
      default: () => []
    }
  },
  inject: ['parentFetchData', 'parentToggleLoading'],
  data () {
    return {
      createModalVisible: false,
      replacingRule: null,
      replacementDeleted: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['State', 'Actions'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.delete.firewall.rules'),
        confirmMessage: this.$t('label.confirm.delete.firewall.rules')
      },
      loading: true,
      firewallRules: [],
      kubernetesManagementPorts: [],
      kubernetesPortsRequest: 0,
      kubernetesPortsUnavailable: false,
      newRule: {
        protocol: 'tcp',
        cidrlist: null,
        ipaddressid: this.resource.id,
        icmptype: null,
        icmpcode: null,
        startport: null,
        endport: null
      },
      protocolNumbers: [],
      icmpTypes: [],
      icmpCodes: [],
      tagRule: null,
      totalCount: 0,
      page: 1,
      pageSize: 10,
      columns: [
        {
          title: this.$t('label.sourcecidr'),
          dataIndex: 'cidrlist'
        },
        {
          key: 'protocol',
          title: this.$t('label.protocol')
        },
        {
          key: 'startport',
          title: `${this.$t('label.startport')}/${this.$t('label.icmptype')}`
        },
        {
          key: 'endport',
          title: `${this.$t('label.endport')}/${this.$t('label.icmpcode')}`
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ]
    }
  },
  computed: {
    hasSelected () {
      return this.selectedRowKeys.length > 0
    }
  },
  created () {
    this.fetchNetworkProtocols()
    this.fetchData()
    this.fetchKubernetesManagementPorts()
  },
  watch: {
    'resource.id' () { this.tagRule = null },
    resource: {
      deep: true,
      handler (newItem) {
        if (!newItem || !newItem.id) {
          return
        }
        this.fetchData()
        this.fetchKubernetesManagementPorts()
      }
    }
  },
  methods: {
    async fetchKubernetesManagementPorts () {
      const ipId = this.resource?.id
      const request = ++this.kubernetesPortsRequest
      const current = () => request === this.kubernetesPortsRequest && ipId === this.resource?.id
      this.kubernetesManagementPorts = []
      this.kubernetesPortsUnavailable = Boolean(this.$store.getters.apis.listKubernetesClusters && ipId)
      if (!this.kubernetesPortsUnavailable) return
      let clusters = []
      try {
        clusters = await listKubernetesClustersForIp(getAPI, ipId)
        if (!current()) return
        if (!clusters.length) {
          this.kubernetesPortsUnavailable = false
          return
        }
        this.kubernetesPortsUnavailable = true
        if (!this.$store.getters.apis.listPortForwardingRules) return
        const rules = await listAllKubernetesPortRules(getAPI, ipId)
        if (!current()) return
        this.kubernetesManagementPorts = clusterManagementPorts(clusters, rules)
        this.kubernetesPortsUnavailable = false
      } catch (error) {
        if (current()) this.kubernetesPortsUnavailable = true
      }
    },
    fetchNetworkProtocols () {
      getAPI('listNetworkProtocols', {
        option: 'protocolnumber'
      }).then(json => {
        this.protocolNumbers = json.listnetworkprotocolsresponse?.networkprotocol || []
      })
      getAPI('listNetworkProtocols', {
        option: 'icmptype'
      }).then(json => {
        this.icmpTypes.push({ index: -1, description: this.$t('label.all') })
        const results = json.listnetworkprotocolsresponse?.networkprotocol || []
        for (const result of results) {
          this.icmpTypes.push(result)
        }
      })
    },
    updateIcmpCodes (val) {
      this.newRule.icmpcode = -1
      this.icmpCodes = []
      this.icmpCodes.push({ code: -1, description: this.$t('label.all') })
      const icmpType = this.icmpTypes.find(icmpType => icmpType.index === val)
      if (icmpType && icmpType.details) {
        const icmpTypeDetails = icmpType.details
        for (const k of Object.keys(icmpTypeDetails)) {
          this.icmpCodes.push({ code: parseInt(k), description: icmpTypeDetails[k] })
        }
      }
    },
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      this.loading = !listRequest.loaded
      return getAPI('listFirewallRules', {
        listAll: true,
        ipaddressid: this.resource.id,
        page: this.page,
        pageSize: this.pageSize
      }).then(response => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.firewallRules = response.listfirewallrulesresponse.firewallrule || []
        this.totalCount = response.listfirewallrulesresponse.count || 0
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return

        this.loading = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.loading = false
      })
    },
    setSelection (selection) {
      const selectableRows = this.firewallRules.filter(item => !this.isProtectedManagementRule(item))
      this.selectedRowKeys = selection.filter(id => selectableRows.some(item => item.id === id))
      this.$emit('selection-change', this.selectedRowKeys)
      this.selectedItems = (this.firewallRules.filter(function (item) {
        return this.selectedRowKeys.indexOf(item.id) !== -1
      }, this))
    },
    getCheckboxProps (record) {
      return {
        disabled: this.isProtectedManagementRule(record)
      }
    },
    rangeIncludesProtectedPort (startPort, endPort) {
      const protectedPorts = this.effectiveProtectedManagementPorts()
      if (!protectedPorts.length) {
        return false
      }
      const start = Number(startPort)
      const end = Number(endPort ?? startPort)
      if (!Number.isInteger(start) || !Number.isInteger(end)) {
        return false
      }
      const min = Math.min(start, end)
      const max = Math.max(start, end)
      return protectedPorts.some(port => {
        const protectedPort = Number(port)
        return Number.isInteger(protectedPort) && protectedPort >= min && protectedPort <= max
      })
    },
    effectiveProtectedManagementPorts () {
      return [...new Set([
        ...this.protectedManagementPorts,
        ...this.kubernetesManagementPorts
      ].map(port => Number(port)).filter(port => Number.isInteger(port)))]
    },
    isProtectedManagementRule (rule) {
      if (!rule || String(rule.protocol || '').toLowerCase() !== 'tcp') {
        return false
      }
      return this.kubernetesPortsUnavailable || this.rangeIncludesProtectedPort(rule.startport, rule.endport)
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
      eventBus.emit('update-bulk-job-status', { items: this.selectedItems, action: false })
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
    getCapitalise (val) {
      if (val === 'all') return this.$t('label.all')
      return val.toUpperCase()
    },
    deleteRule (rule) {
      if (this.isProtectedManagementRule(rule)) {
        return
      }
      this.loading = true
      postAPI('deleteFirewallRule', { id: rule.id }).then(response => {
        const jobId = response.deletefirewallruleresponse.jobid
        eventBus.emit('update-job-details', { jobId, resourceId: null })
        this.$pollJob({
          title: this.$t('label.action.delete.firewall'),
          description: rule.id,
          jobId: jobId,
          successMessage: this.$t('message.success.remove.firewall.rule'),
          successMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: rule.id, state: 'success' })
            }
            this.fetchData()
          },
          errorMessage: this.$t('message.remove.firewall.rule.failed'),
          errorMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: rule.id, state: 'failed' })
            }
            this.fetchData()
          },
          loadingMessage: this.$t('message.remove.firewall.rule.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => this.fetchData(),
          bulkAction: `${this.selectedItems.length > 0}` && this.showGroupActionModal
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchData()
      })
    },
    openRuleDialog (rule) {
      if (this.networkMutationBlocked || this.replacementDeleted) { this.createModalVisible = true; return }
      if (rule && (this.isProtectedManagementRule(rule) || rule.tags?.length)) return
      this.resetNetworkMutation()
      this.replacingRule = rule || null
      this.replacementDeleted = false
      this.resetAllRules()
      if (rule) this.newRule = { ipaddressid: this.resource.id, protocol: rule.protocol, cidrlist: rule.cidrlist, startport: rule.startport, endport: rule.endport, icmptype: rule.icmptype, icmpcode: rule.icmpcode }
      this.createModalVisible = true
    },
    async addRule () {
      if (this.loading || this.networkMutationBlocked) return
      if (['tcp', 'udp'].includes(this.newRule.protocol) && !validPortRange(this.newRule.startport, this.newRule.endport)) { this.$notification.error({ message: this.$t('label.required'), description: this.$t('message.network.ports.invalid') }); return }
      if (!this.$store.getters.apis.createFirewallRule || (this.replacingRule && this.isProtectedManagementRule(this.replacingRule))) return
      const params = this.firewallPayload(this.newRule)
      if (!params) {
        this.$notification.error({ message: this.$t('message.guest.feature.invalid.rule') })
        return
      }
      if (this.replacingRule && !this.replacementDeleted) {
        const deleted = await this.submitNetworkMutation('deleteFirewallRule', { id: this.replacingRule.id }, () => { this.replacementDeleted = true })
        if (!deleted) return
      }
      return this.submitNetworkMutation('createFirewallRule', params, this.finishReplacement)
    },
    firewallPayload (rule) {
      const params = conditionalFirewallRule({ ...rule, traffictype: 'ingress' }, this.resource.id)
      if (!params) return null
      const { protocol, cidrlist, startport, endport, icmptype, icmpcode } = params
      return { ipaddressid: this.resource.id, protocol, cidrlist, startport, endport, icmptype, icmpcode }
    },
    async restoreRule () {
      if (!this.replacementDeleted || this.networkMutationBlocked || !this.replacingRule) return
      const params = this.firewallPayload(this.replacingRule)
      if (params) await this.submitNetworkMutation('createFirewallRule', params, this.finishReplacement)
    },
    async finishReplacement () {
      this.replacingRule = null
      this.replacementDeleted = false
      this.createModalVisible = false
      await this.fetchData()
    },
    resetAllRules () {
      this.newRule.protocol = 'tcp'
      this.newRule.cidrlist = null
      this.newRule.ipaddressid = this.resource.id
      this.resetRulePorts()
    },
    resetRulePorts () {
      this.newRule.icmptype = null
      this.newRule.icmpcode = null
      this.newRule.startport = null
      this.newRule.endport = null
    },
    closeModal () {
      this.tagRule = null
      this.showConfirmationAction = false
    },
    openTagsModal (rule) {
      if (!rule.id || !this.resource.id || !('listTags' in this.$store.getters.apis)) return
      this.tagRule = { id: rule.id, parentId: this.resource.id, canEdit: !this.isProtectedManagementRule(rule) }
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
    capitalise (val) {
      return val.toUpperCase()
    }
  }
}
</script>

<style scoped lang="scss">
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

  .form {
    display: flex;
    align-items: flex-end;
    margin-right: -20px;
    flex-direction: column;
    margin-bottom: 20px;

    @media (min-width: 760px) {
      flex-direction: row;
    }

    &__item {
      display: flex;
      flex-direction: column;
      flex: 1;
      padding-right: 20px;
      margin-bottom: 20px;

      @media (min-width: 760px) {
        margin-bottom: 0;
      }

      input,
      .ant-select {
        margin-top: auto;
      }

    }

    &__label {
      font-weight: bold;
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
