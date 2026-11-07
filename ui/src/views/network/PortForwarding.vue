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
    <network-rules-toolbar :can-add="'createPortForwardingRule' in $store.getters.apis" :loading="loading || networkMutationBusy" @add="openRuleDialog()" @refresh="fetchData" >
      <a-button
        v-if="(('deletePortForwardingRule' in $store.getters.apis) && this.selectedItems.length > 0)"
        type="primary"
        danger
        @click="bulkActionConfirmation()">
        <template #icon><delete-outlined /></template>
        {{ $t('label.action.bulk.delete.portforward.rules') }}
      </a-button>
    </network-rules-toolbar>
    <a-alert v-if="listRefreshFailed" class="network-rules-guidance" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-table
      size="small"
      style="overflow-y: auto"
      :loading="loading"
      :columns="columns"
      :dataSource="portForwardRules"
      :pagination="false"
      :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange, getCheckboxProps: getCheckboxProps}"
      :rowKey="record => record.id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'privateport'">
          {{ record.privateport }} - {{ record.privateendport }}
        </template>
        <template v-if="column.key === 'publicport'">
          {{ record.publicport }} - {{ record.publicendport }}
        </template>
        <template v-if="column.key === 'protocol'">
          {{ getCapitalise(record.protocol) }}
        </template>
        <template v-if="column.key === 'cidrlist'">
          <span style="white-space: pre-line"> {{ record.cidrlist?.replaceAll(",", "\n") }}</span>
        </template>
        <template v-if="vpcConserveMode && column.key === 'networkid'">
          <router-link
            :to="{ path: '/guestnetwork/' + record.networkid }">
            {{ record.networkname }}
          </router-link>
        </template>
        <template v-if="column.key === 'vm'">
          <div><desktop-outlined/>
            <router-link
              :to="{ path: '/vm/' + record.virtualmachineid }">
              {{ record.virtualmachinename }}</router-link> ({{ record.vmguestip }})</div>
        </template>
        <template v-if="column.key === 'actions'">
          <div class="actions">
            <tooltip-button :tooltip="$t('label.edit')" icon="edit-outlined" :disabled="isProtectedManagementRule(record) || !('updatePortForwardingRule' in $store.getters.apis)" @onClick="openRuleDialog(record)" />
            <tooltip-button
              :tooltip="$t('label.tags')"
              icon="tag-outlined"
              :disabled="!('listTags' in $store.getters.apis)"
              buttonClass="rule-action"
              @onClick="() => openTagsModal(record)" />
            <a-popconfirm :title="$t('label.delete') + '?'" @confirm="deleteRule(record)" :disabled="isProtectedManagementRule(record)">
            <tooltip-button
              :tooltip="isProtectedManagementRule(record) ? $t('message.kubernetes.management.rule.delete.disabled') : $t('label.remove.rule')"
              type="primary"
              :danger="true"
              icon="delete-outlined"
              buttonClass="rule-action"
              :disabled="!('deletePortForwardingRule' in $store.getters.apis) || isProtectedManagementRule(record)"
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
      resource-type="PortForwardingRule"
      @cancel="tagRule = null" />

    <MoldDialog
      class="network-rule-dialog"
      size="lg"
      focus-guard
      :visible="addVmModalVisible"
      :title="$t(editingRule ? 'label.edit' : 'label.network.rule.add')"
      :closable="!networkMutationSubmitting"
      :keyboard="!networkMutationSubmitting"
      @cancel="!networkMutationSubmitting && closeModal()">
      <div>
      <div v-ctrl-enter="addRule"><NetworkRuleContext :resource="resource" /><NetworkMutationStatus :operation="networkMutation" @recheck="recheckNetworkMutation" />
        <div class="form">
          <div class="form__item form__item--full"><div class="form__label">{{ $t('label.protocol') }}</div><a-select v-model:value="newRule.protocol" :disabled="!!editingRule"><a-select-option value="tcp">TCP</a-select-option><a-select-option value="udp">UDP</a-select-option></a-select></div>
          <div class="form__item"><div class="form__label">{{ $t('label.publicport') }} · {{ $t('label.start') }}</div><a-input-number v-model:value="newRule.publicport" :min="1" :max="65535" :disabled="!!editingRule" style="width: 100%" /></div>
          <div class="form__item"><div class="form__label">{{ $t('label.publicport') }} · {{ $t('label.end') }}</div><a-input-number v-model:value="newRule.publicendport" :min="1" :max="65535" :disabled="!!editingRule" style="width: 100%" /></div>
          <div class="form__item"><div class="form__label">{{ $t('label.privateport') }} · {{ $t('label.start') }}</div><a-input-number v-model:value="newRule.privateport" :min="1" :max="65535" style="width: 100%" /></div>
          <div class="form__item"><div class="form__label">{{ $t('label.privateport') }} · {{ $t('label.end') }}</div><a-input-number v-model:value="newRule.privateendport" :min="1" :max="65535" style="width: 100%" /></div>
          <div v-if="isVPC()" class="form__item form__item--full"><div class="form__label">{{ $t('label.sourcecidrlist') }}</div><a-input v-model:value="newRule.cidrlist" /></div>
        </div>
        <div class="network-form-group-label">{{ $t('label.virtualmachine') }} / {{ $t('label.nic') }}</div>

        <span
          v-if="'vpcid' in resource && (!('associatednetworkid' in resource) || vpcConserveMode)">
          <strong>{{ $t('label.select.tier') }} </strong>
          <a-select
            v-focus="'vpcid' in resource && (!('associatednetworkid' in resource) || vpcConserveMode)"
            v-model:value="selectedTier"
            @change="changeTargetTier"
            :placeholder="$t('label.select.tier')"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option
              v-for="tier in tiers.data"
              :loading="tiers.loading"
              :key="tier.id"
              :label="tier.displaytext || ''">
              {{ tier.displaytext }}
            </a-select-option>
          </a-select>
        </span>
        <a-input-search
          v-focus="!('vpcid' in resource && !('associatednetworkid' in resource))"
          class="input-search"
          :placeholder="$t('label.search')"
          v-model:value="searchQuery"
          allowClear
          @search="onSearch" />
        <a-table
          size="small"
          class="list-view"
          :loading="addVmModalLoading"
          :columns="vmColumns"
          :dataSource="vms"
          :pagination="false"
          :rowKey="record => record.id"
          :scroll="{ y: 300 }">
          <template #bodyCell="{ column, text, record }">
            <template v-if="column.key === 'name'">
              <span>
                {{ text }}
              </span>
              <loading-outlined v-if="addVmModalNicLoading"></loading-outlined>
              <a-select
                style="display: block"
                v-else-if="!addVmModalNicLoading && newRule.virtualmachineid === record.id"
                v-model:value="newRule.vmguestip"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option
                  v-for="(nic, nicIndex) in nics"
                  :key="nic"
                  :value="nic"
                  :label="nic">
                  {{ nic }}{{ nicIndex === 0 ? ` (${$t('label.primary')})` : null }}
                </a-select-option>
              </a-select>
            </template>

            <template v-if="column.key === 'state'">
              <status :text="text ? text : ''" displayText></status>
            </template>

            <template v-if="column.key === 'actions'">
              <div style="text-align: center">
                <a-radio-group
                  class="radio-group"
                  :key="record.id"
                  v-model:value="checked"
                  @change="($event) => checked = $event.target.value">
                  <a-radio :value="record.id" @change="e => fetchNics(e)" />
                </a-radio-group>
              </div>
            </template>
          </template>
        </a-table>
        <a-pagination
          class="detail-tab-pagination"
          size="small"
          :current="vmPage"
          :pageSize="vmPageSize"
          :total="vmCount"
          :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="['10', '20', '40', '80', '100']"
          @change="handleChangeVmPage"
          @showSizeChange="handleChangeVmPageSize"
          showSizeChanger>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>
      </div>
      </div>
      <template #footer>
        <a-button :disabled="networkMutationSubmitting" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :loading="networkMutationBusy" :disabled="!nics.includes(newRule.vmguestip) || addVmModalNicLoading || addVmModalLoading || loading || networkMutationBlocked" @click="addRule">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>

    <bulk-action-view
      v-if="showConfirmationAction || showGroupActionModal"
      :showConfirmationAction="showConfirmationAction"
      :showGroupActionModal="showGroupActionModal"
      :items="portForwardRules"
      :selectedRowKeys="selectedRowKeys"
      :selectedItems="selectedItems"
      :columns="columns"
      :selectedColumns="selectedColumns"
      :filterColumns="filterColumns"
      action="deletePortForwardingRule"
      :loading="loading"
      :message="message"
      @group-action="deleteRules"
      @handle-cancel="handleCancel"
      @close-modal="closeModal" />
  </div>
</template>

<script>
import { validForwardPorts } from '@/utils/networkRuleForm'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { networkMutationMixin } from '@/utils/networkMutation'
import NetworkMutationStatus from '@/components/view/NetworkMutationStatus'
import { publicIpNicAddresses, publicIpScope } from '@/utils/publicIpRules'
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
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'

export default {
  mixins: [listRefreshMixin(['fetchPFRules']), networkMutationMixin],
  components: {
    RuleTagsDialog,
    NetworkMutationStatus,
    MoldDialog,
    NetworkRuleContext,
    NetworkRulesToolbar,
    TooltipLabel,
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
      editingRule: null,
      checked: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['State', 'Actions'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.delete.portforward.rules'),
        confirmMessage: this.$t('label.confirm.delete.portforward.rules')
      },
      loading: true,
      portForwardRules: [],
      kubernetesManagementPorts: [],
      kubernetesPortsRequest: 0,
      kubernetesPortsUnavailable: false,
      newRule: {
        protocol: 'tcp',
        privateport: null,
        privateendport: null,
        publicport: null,
        publicendport: null,
        openfirewall: false,
        vmguestip: null,
        virtualmachineid: null
      },
      tagRule: null,
      selectedTier: null,
      nicRequest: 0,
      vmRequest: 0,
      addVmModalVisible: false,
      addVmModalLoading: false,
      addVmModalNicLoading: false,
      vms: [],
      nics: [],
      totalCount: 0,
      page: 1,
      pageSize: 10,
      columns: [
        {
          key: 'privateport',
          title: this.$t('label.privateport')
        },
        {
          key: 'publicport',
          title: this.$t('label.publicport')
        },
        {
          key: 'protocol',
          title: this.$t('label.protocol')
        },
        {
          key: 'cidrlist',
          title: this.$t('label.sourcecidrlist'),
          hidden: !this.isVPC()
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          key: 'vm',
          title: this.$t('label.vm')
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ].filter(item => !item.hidden),
      tiers: {
        loading: false,
        data: []
      },
      vmColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name',
          width: 210
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.displayname'),
          dataIndex: 'displayname'
        },
        {
          title: this.$t('label.account'),
          dataIndex: 'account'
        },
        {
          title: this.$t('label.zone'),
          dataIndex: 'zonename'
        },
        {
          key: 'actions',
          title: this.$t('label.select'),
          dataIndex: 'actions',
          width: 80
        }
      ],
      vmPage: 1,
      vmPageSize: 10,
      vmCount: 0,
      searchQuery: null,
      cidrlist: '',
      vpcConserveMode: false
    }
  },
  computed: {
    hasSelected () {
      return this.selectedRowKeys.length > 0
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createPortForwardingRule')
  },
  created () {
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
    fetchData () {
      this.fetchVpc()
      this.fetchListTiers()
      this.fetchPFRules()
    },
    fetchVpc () {
      if (!this.resource.vpcid) {
        return
      }
      this.vpcConserveMode = false
      getAPI('listVPCs', {
        id: this.resource.vpcid
      }).then(json => {
        this.vpcConserveMode = json.listvpcsresponse?.vpc?.[0].vpcofferingconservemode || false
        if (this.vpcConserveMode) {
          this.columns.splice(this.columns.length - 1, 0, {
            key: 'networkid',
            title: this.$t('label.network')
          })
        }
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    fetchListTiers () {
      if (!this.resource.vpcid) return
      this.tiers.loading = true
      getAPI('listNetworks', {
        supportedservices: 'PortForwarding',
        vpcid: this.resource.vpcid,
        listall: this.resource.vpcid !== null
      }).then(json => {
        this.tiers.data = json.listnetworksresponse.network || []
        if (!this.tiers.data.some(tier => tier.id === this.selectedTier) && this.tiers.data.length > 0) {
          this.selectedTier = this.tiers.data[0].id
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => { this.tiers.loading = false })
    },
    fetchPFRules () {
      const listRequest = this.listRequestToken('fetchPFRules')
      this.loading = !listRequest.loaded
      return getAPI('listPortForwardingRules', {
        listAll: true,
        ipaddressid: this.resource.id,
        page: this.page,
        pageSize: this.pageSize
      }).then(response => {
        if (!this.isListRequestCurrent('fetchPFRules', listRequest)) return

        this.portForwardRules = response.listportforwardingrulesresponse.portforwardingrule || []
        this.totalCount = response.listportforwardingrulesresponse.count || 0
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchPFRules', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchPFRules', listRequest)) return

        this.loading = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchPFRules', listRequest)) return
        this.loading = false
      })
    },
    setSelection (selection) {
      const selectableRows = this.portForwardRules.filter(item => !this.isProtectedManagementRule(item))
      this.selectedRowKeys = selection.filter(id => selectableRows.some(item => item.id === id))
      this.$emit('selection-change', this.selectedRowKeys)
      this.selectedItems = (this.portForwardRules.filter(function (item) {
        return this.selectedRowKeys.indexOf(item.id) !== -1
      }, this))
    },
    getCheckboxProps (record) {
      return {
        disabled: this.isProtectedManagementRule(record)
      }
    },
    rangeIncludesPort (startPort, endPort, targetPort) {
      const start = Number(startPort)
      const end = Number(endPort ?? startPort)
      const target = Number(targetPort)
      if (!Number.isInteger(start) || !Number.isInteger(end) || !Number.isInteger(target)) {
        return false
      }
      const min = Math.min(start, end)
      const max = Math.max(start, end)
      return target >= min && target <= max
    },
    rangeIncludesProtectedPort (startPort, endPort) {
      const protectedPorts = this.effectiveProtectedManagementPorts()
      if (!protectedPorts.length) {
        return false
      }
      return protectedPorts.some(port => this.rangeIncludesPort(startPort, endPort, port))
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
      return ((this.kubernetesPortsUnavailable || this.rangeIncludesProtectedPort(rule.publicport, rule.publicendport)) &&
        (this.rangeIncludesPort(rule.privateport, rule.privateendport, 22) ||
          this.rangeIncludesPort(rule.privateport, rule.privateendport, 6443)))
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
      postAPI('deletePortForwardingRule', { id: rule.id }).then(response => {
        const jobId = response.deleteportforwardingruleresponse.jobid
        eventBus.emit('update-job-details', { jobId, resourceId: null })
        this.$pollJob({
          title: this.$t('label.portforwarding.rule'),
          description: rule.id,
          jobId: jobId,
          successMessage: this.$t('message.success.remove.port.forward'),
          successMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: rule.id, state: 'success' })
            }
            this.fetchData()
          },
          errorMessage: this.$t('message.remove.port.forward.failed'),
          errorMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: rule.id, state: 'failed' })
            }
            this.fetchData()
          },
          loadingMessage: this.$t('message.delete.port.forward.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => this.fetchData(),
          bulkAction: `${this.selectedItems.length > 0}` && this.showGroupActionModal
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchData()
      })
    },
    async addRule () {
      if (this.loading || this.networkMutationBlocked) return
      if (!validForwardPorts(this.newRule)) { this.$notification.error({ message: this.$t('label.required'), description: this.$t('message.network.ports.invalid') }); return }
      if (this.editingRule && this.isProtectedManagementRule(this.editingRule)) { return }
      if (this.addVmModalNicLoading || this.addVmModalLoading || !this.newRule.virtualmachineid || !this.nics.includes(this.newRule.vmguestip)) return
      const networkId = this.targetNetworkId()
      if (!networkId) return
      const api = this.editingRule ? 'updatePortForwardingRule' : 'createPortForwardingRule'
      if (!this.$store.getters.apis[api]) { return }
      const params = this.editingRule
        ? { id: this.editingRule.id, privateport: this.newRule.privateport, privateendport: this.newRule.privateendport, virtualmachineid: this.newRule.virtualmachineid, vmguestip: this.newRule.vmguestip, ...(this.isVPC() ? { cidrlist: this.newRule.cidrlist } : {}) }
        : {
          ipaddressid: this.resource.id,
          networkid: networkId,
          protocol: this.newRule.protocol,
          privateport: this.newRule.privateport,
          privateendport: this.newRule.privateendport,
          publicport: this.newRule.publicport,
          publicendport: this.newRule.publicendport,
          virtualmachineid: this.newRule.virtualmachineid,
          vmguestip: this.newRule.vmguestip,
          openfirewall: this.newRule.openfirewall,
          ...(this.isVPC() ? { cidrlist: this.newRule.cidrlist } : {})
        }
      return this.submitNetworkMutation(api, params, async () => {
        this.closeModal()
        await this.fetchData()
      })
    },
    resetAllRules () {
      this.newRule.protocol = 'tcp'
      this.newRule.privateport = null
      this.newRule.privateendport = null
      this.newRule.publicport = null
      this.newRule.publicendport = null
      this.newRule.openfirewall = false
      this.newRule.cidrlist = null
      this.newRule.vmguestip = null
      this.newRule.virtualmachineid = null
    },
    closeModal () {
      if (this.networkMutationBlocked) { this.addVmModalVisible = false; return }
      this.tagRule = null
      this.nicRequest++
      this.vmRequest++
      this.addVmModalVisible = false
      this.newRule.virtualmachineid = null
      this.addVmModalLoading = false
      this.addVmModalNicLoading = false
      this.showConfirmationAction = false
      this.nics = []
      this.checked = false
    },
    openTagsModal (rule) {
      if (!rule.id || !this.resource.id || !('listTags' in this.$store.getters.apis)) return
      this.tagRule = { id: rule.id, parentId: this.resource.id, canEdit: !this.isProtectedManagementRule(rule) }
    },
    openRuleDialog (rule) {
      if (this.networkMutationBlocked) { this.addVmModalVisible = true; return }
      this.resetNetworkMutation()
      if (rule && this.isProtectedManagementRule(rule)) return
      this.editingRule = rule || null
      this.resetAllRules()
      if (rule) {
        if (this.resource.vpcid && rule.networkid) this.selectedTier = rule.networkid
        this.newRule = { ...rule }
        this.checked = rule.virtualmachineid
      }
      this.openAddVMModal()
      if (rule) this.fetchNics({ target: { value: rule.virtualmachineid } })
    },
    openAddVMModal () {
      if (this.addVmModalLoading) return
      this.addVmModalVisible = true
      this.fetchVirtualMachines()
    },
    targetNetworkId () {
      return this.resource.vpcid && (!this.resource.associatednetworkid || this.vpcConserveMode)
        ? this.selectedTier : this.resource.associatednetworkid
    },
    changeTargetTier () {
      this.nicRequest++
      this.nics = []
      this.checked = false
      this.newRule.virtualmachineid = null
      this.newRule.vmguestip = null
      this.addVmModalNicLoading = false
      this.vmPage = 1
      this.fetchVirtualMachines()
    },
    async fetchNics (e) {
      const vmId = e.target.value
      const networkId = this.targetNetworkId()
      const scope = this.networkMutationScope
      const request = ++this.nicRequest
      const previous = this.newRule.virtualmachineid === vmId ? this.newRule.vmguestip : null
      this.nics = []
      this.newRule.vmguestip = null
      this.newRule.virtualmachineid = vmId
      if (!networkId || !vmId) return
      this.addVmModalNicLoading = true
      const current = () => request === this.nicRequest && !this.networkMutationDisposed &&
        scope === this.networkMutationScope && networkId === this.targetNetworkId()
      try {
        const json = await getAPI('listNics', { virtualmachineid: vmId, networkid: networkId })
        if (!current()) return
        this.nics = publicIpNicAddresses(json.listnicsresponse.nic, networkId)
        this.newRule.vmguestip = this.nics.includes(previous) ? previous : this.nics[0] || null
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.addVmModalNicLoading = false
      }
    },
    async fetchVirtualMachines () {
      const request = ++this.vmRequest
      const networkId = this.targetNetworkId()
      const scope = this.networkMutationScope
      this.vmCount = 0
      this.vms = []
      if (!networkId) {
        this.addVmModalLoading = false
        return
      }
      this.addVmModalLoading = true
      const current = () => request === this.vmRequest && !this.networkMutationDisposed &&
        scope === this.networkMutationScope && networkId === this.targetNetworkId()
      try {
        const response = await getAPI('listVirtualMachines', {
          ...publicIpScope(this.resource),
          listall: true,
          state: 'Present',
          keyword: this.searchQuery,
          page: this.vmPage,
          pagesize: this.vmPageSize,
          networkid: networkId
        })
        if (!current()) return
        this.vmCount = response.listvirtualmachinesresponse.count || 0
        this.vms = response.listvirtualmachinesresponse.virtualmachine || []
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.addVmModalLoading = false
      }
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
    handleChangeVmPage (page, pageSize) {
      this.vmPage = page
      this.vmPageSize = pageSize
      this.fetchVirtualMachines()
    },
    handleChangeVmPageSize (currentPage, pageSize) {
      this.vmPage = currentPage
      this.vmPageSize = pageSize
      this.fetchVirtualMachines()
    },
    onSearch (value) {
      this.searchQuery = value
      this.fetchVirtualMachines()
    },
    isVPC () {
      return !!this.resource.vpcid
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
    margin-right: -20px;
    margin-bottom: 20px;
    flex-direction: column;

    @media (min-width: 760px) {
      flex-direction: row;
    }

    &__item {
      display: flex;
      flex-direction: column;
      /*flex: 1;*/
      padding-right: 20px;
      margin-bottom: 20px;

      @media (min-width: 760px) {
        margin-bottom: 0;
      }

      input,
      .ant-select {
        margin-top: auto;
      }

      &__input-container {
        display: flex;

        input {

          &:not(:last-child) {
            margin-right: 10px;
          }

        }

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

  .vm-modal {

    &__header {
      display: flex;

      span {
        flex: 1;
        font-weight: bold;
        margin-right: 10px;
      }

    }

    &__item {
      display: flex;
      margin-top: 10px;

      span,
      label {
        display: block;
        flex: 1;
        margin-right: 10px;
      }

    }

  }

  .pagination {
    margin-top: 20px;
    text-align: right;
  }

  .list-view {
    overflow-y: auto;
    display: block;
    width: 100%;
  }

  .filter {
    display: block;
    width: 240px;
    margin-bottom: 10px;

    .form__item {
      width: 100%;
    }
  }

  .input-search {
    margin-bottom: 10px;
    width: 50%;
    float: right;
  }
</style>
