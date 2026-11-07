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
  <div class="network-ip-tab">
    <a-spin :spinning="fetchLoading">
      <div class="network-ip-toolbar">
        <a-button
          v-if="!isShared || canAcquireIp"
          :disabled="!canAcquireIp"
          type="primary"
          @click="onShowAcquireIp">
          <template #icon><plus-outlined /></template>
          {{ $t('label.acquire.new.ip') }}
        </a-button>
        <a-button
          v-if="(('disassociateIpAddress' in $store.getters.apis) && this.selectedRowKeys.length > 0)"
          type="primary"
          danger
          @click="bulkActionConfirmation()">
          <template #icon><delete-outlined /></template>
          {{ $t('label.action.bulk.release.public.ip.address') }}
        </a-button>
        <a-button v-if="!isShared" :loading="fetchLoading || listRefreshing > 0" @click="fetchData">
          <template #icon><reload-outlined /></template>
          {{ $t('label.refresh') }}
        </a-button>
        <tooltip-button v-else :tooltip="$t('label.refresh')" icon="reload-outlined" :loading="fetchLoading || listRefreshing > 0" @onClick="fetchData" />
      </div>
      <a-alert v-if="isShared" type="info" show-icon class="network-ip-refresh-alert" :message="$t('message.shared.guest.addresses')" />
      <a-alert v-if="listRefreshFailed" role="status" type="warning" show-icon class="network-ip-refresh-alert" :message="$t('message.list.refresh.stale')" />
      <div v-if="$route.path.startsWith('/vpc')">
        {{ $t('label.select.tier') + ':' }}
        <a-select
          v-focus="true"
          style="width: 40%; margin-left: 15px;margin-bottom: 15px"
          :loading="fetchLoading"
          defaultActiveFirstOption
          v-model:value="vpcTier"
          @change="handleTierSelect"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option key="all" value="">
            {{ $t('label.view.all') }}
          </a-select-option>
          <a-select-option v-for="network in networksList" :key="network.id" :value="network.id" :label="network.name">
            {{ network.name }}
          </a-select-option>
        </a-select>
      </div>
      <a-table
        size="small"
        :scroll="{ x: 860 }"
        :columns="visibleColumns"
        :dataSource="ips"
        :rowKey="item => item.id"
        :rowSelection="canReleaseIps ? { selectedRowKeys, onChange: onSelectChange, getCheckboxProps: record => ({ disabled: !releasableIp(record) }) } : null"
        :pagination="false" >
        <template #bodyCell="{ column, text, record }">
          <template v-if="column.key === 'ipaddress'">
            <router-link v-if="record.forvirtualnetwork === true" :to="{ path: '/publicip/' + record.id }" >{{ text }}&nbsp;</router-link>
            <div v-else>{{ text }}</div>
            <template v-if="!isShared && record.issourcenat === true">
              <a-tag>{{ $t('label.sourcenat') }}</a-tag>
            </template>
            <template v-else-if="!isShared && record.isstaticnat === true">
              <a-tag>{{ $t('label.staticnat') }}</a-tag>
            </template>
            <template v-else-if="!isShared && record.hasrules === false">
              <tooltip-button
                v-if="canChangeSourceNat(record)"
                :tooltip="$t('label.action.set.as.source.nat.ip')"
                type="primary"
                :danger="false"
                icon="aim-outlined"
                @onClick="showChangeSourceNat(record)"></tooltip-button>
            </template>
            <template v-else-if="!isShared && record.hasrules === true">
              <Tooltip placement="topLeft" :title="$t('message.sourcenatip.change.inhibited')" >
                <a-tag>{{ $t('label.hasrules') }}</a-tag>
              </Tooltip>
            </template>
          </template>

          <template v-if="column.key === 'state'">
            <status :text="record.state" displayText />
          </template>

          <template v-if="column.key === 'virtualmachineid'">
            <network-ip-vm-link :record="record" />
          </template>

          <template v-if="column.key === 'associatednetworkname'">
            <router-link v-if="record.forvirtualnetwork === true" :to="{ path: '/guestnetwork/' + record.associatednetworkid }" > {{ record.associatednetworkname || record.associatednetworkid }} </router-link>
            <div v-else>{{ record.networkname }}</div>
          </template>

          <template v-if="column.key === 'actions'">
            <tooltip-button
              v-if="canReleaseIps && releasableIp(record)"
              :tooltip="$t('label.action.release.ip')"
              type="primary"
              :danger="true"
              icon="delete-outlined"
              @onClick="releaseIpAddress(record)" />
          </template>
        </template>
      </a-table>
      <a-divider/>
      <a-pagination
        class="row-element pagination"
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="totalIps"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="['10', '20', '40', '80', '100']"
        @change="changePage"
        @showSizeChange="changePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </a-spin>
    <MoldDialog
      v-if="showAcquireIp"
      class="network-acquire-dialog"
      size="md"
      focus-guard
      :title="$t('label.acquire.new.ip')"
      :closable="!networkMutationSubmitting"
      :keyboard="!networkMutationSubmitting"
      @cancel="onCloseModal">
      <a-alert :message="$t('message.action.acquire.ip')" type="warning" show-icon />
      <NetworkMutationStatus :operation="networkMutation" @recheck="recheckNetworkMutation" />
      <a-form layout="vertical" style="margin-top: 16px" v-ctrl-enter="acquireIpAddress">
        <a-form-item :label="$t('label.ipaddress')">
          <infinite-scroll-select
            v-focus="true"
            v-model:value="acquireIp"
            api="listPublicIpAddresses"
            :apiParams="listApiParamsForAssociate"
            resourceType="publicipaddress"
            optionValueKey="ipaddress"
            :optionLabelFn="ip => ip.ipaddress + ' (' + ip.state + ')'"
            defaultIcon="environment-outlined"
            :autoSelectFirstOption="true"
            :disabled="networkMutationBlocked"
            @change-option-value="ip => acquireIp = ip" />
        </a-form-item>
      </a-form>
      <template #footer>
        <a-button :disabled="networkMutationSubmitting" @click="onCloseModal">{{ $t('label.cancel') }}</a-button>
        <a-button ref="submit" type="primary" :loading="networkMutationBusy" :disabled="networkMutationBlocked" @click="acquireIpAddress">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
    <MoldDialog
      v-if="sourceNatIp"
      class="network-source-nat-dialog"
      size="md"
      focus-guard
      :title="$t('label.action.set.as.source.nat.ip')"
      :closable="!networkMutationBlocked"
      :keyboard="!networkMutationBlocked"
      @cancel="cancelChangeSourceNat">
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item :label="$t('label.ipaddress')">{{ sourceNatIp.ipaddress }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.network')">{{ resource.name }}</a-descriptions-item>
      </a-descriptions>
      <a-alert class="network-source-nat-warning" :message="$t('message.sourcenatip.change.warning')" type="warning" show-icon />
      <NetworkMutationStatus :operation="networkMutation" @recheck="recheckNetworkMutation" />
      <template #footer>
        <a-button :disabled="networkMutationBlocked" @click="cancelChangeSourceNat">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="networkMutationBusy" :disabled="networkMutationBlocked || !canChangeSourceNat(sourceNatIp)" @click="setSourceNatIp">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
    <bulk-action-view
      v-if="showConfirmationAction || showGroupActionModal"
      :showConfirmationAction="showConfirmationAction"
      :showGroupActionModal="showGroupActionModal"
      :items="ips"
      :selectedRowKeys="selectedRowKeys"
      :selectedItems="selectedItems"
      :columns="columns"
      :selectedColumns="selectedColumns"
      action="disassociateIpAddress"
      :loading="loading"
      :message="message"
      @group-action="releaseIpAddresses"
      @handle-cancel="handleCancel"
      @close-modal="closeModal" />
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { networkMutationMixin } from '@/utils/networkMutation'
import { networkBusy } from '@/utils/networkLifecycle'
import { publicIpService, usablePublicIp } from '@/utils/publicIpRules'
import NetworkMutationStatus from '@/components/view/NetworkMutationStatus'
import MoldDialog from '@/components/view/MoldDialog'

import { getAPI, postAPI } from '@/api'
import Status from '@/components/widgets/Status'
import NetworkIpVmLink from '@/components/widgets/NetworkIpVmLink'
import TooltipButton from '@/components/widgets/TooltipButton'
import BulkActionView from '@/components/view/BulkActionView'
import eventBus from '@/config/eventBus'
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect'

export default {
  mixins: [listRefreshMixin(['fetchData']), networkMutationMixin],
  name: 'IpAddressesTab',
  components: {
    NetworkIpVmLink,
    Status,
    TooltipButton,
    BulkActionView,
    InfiniteScrollSelect,
    NetworkMutationStatus,
    MoldDialog
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
      ips: [],
      ipsTiers: [],
      networksList: [],
      defaultNetwork: '',
      vpcTier: '',
      page: 1,
      pageSize: 10,
      totalIps: 0,
      tiersSelect: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['Actions'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.release.public.ip.address'),
        confirmMessage: this.$t('label.confirm.release.public.ip.addresses')
      },
      columns: [
        {
          key: 'ipaddress',
          width: 210,
          title: this.$t('label.ipaddress'),
          dataIndex: 'ipaddress'
        },
        {
          key: 'state',
          width: 100,
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          key: 'virtualmachineid',
          width: 220,
          title: this.$t('label.vm'),
          dataIndex: 'virtualmachineid'
        },
        {
          key: 'associatednetworkname',
          width: 220,
          title: this.$t('label.network'),
          dataIndex: 'associatednetworkname'
        },
        {
          key: 'actions',
          width: 70,
          title: ''
        }
      ],
      showAcquireIp: false,
      acquireLoading: false,
      acquireIp: null,
      sourceNatIp: null,
      zoneExtNetProvider: ''
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, previous) {
        if (!newItem || !newItem.id) {
          return
        }
        if (newItem.id !== previous?.id) {
          this.ips = []
          this.totalIps = 0
          this.sourceNatIp = null
          this.showAcquireIp = false
          this.resetSelection()
        }
        this.fetchData()
      }
    }
  },
  inject: ['parentFetchData'],
  computed: {
    isShared () { return this.resource.type === 'Shared' },
    visibleColumns () { return this.isShared ? this.columns.filter(column => column.key !== 'actions') : this.columns },
    isVpc () { return this.$route.path.startsWith('/vpc') },
    sourceNatCommand () { return this.isVpc ? 'updateVPC' : 'updateNetwork' },
    canAcquireIp () {
      return 'associateIpAddress' in this.$store.getters.apis && !this.networkMutationBlocked &&
        !this.listRefreshFailed && !networkBusy(this.resource) && (this.isVpc || publicIpService(this.resource, 'SourceNat'))
    },
    canReleaseIps () {
      return 'disassociateIpAddress' in this.$store.getters.apis && this.resource.type !== 'Shared' &&
        !this.networkMutationBlocked && !this.listRefreshFailed && !networkBusy(this.resource)
    },
    listApiParams () {
      const params = {
        zoneid: this.resource.zoneid,
        domainid: this.resource.domainid,
        account: this.resource.account,
        forvirtualnetwork: true,
        allocatedonly: false
      }
      if (['nsx', 'netris'].includes(this.zoneExtNetProvider?.toLowerCase())) {
        params.forprovider = true
      }
      return params
    },
    listApiParamsForAssociate () {
      const params = this.listApiParams
      params.state = 'Free,Reserved'
      return params
    }
  },
  methods: {
    async fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      const params = {
        listall: true,
        page: this.page,
        pagesize: this.pageSize
      }
      if (this.$route.path.startsWith('/vpc')) {
        this.networksList = this.resource.network
        params.vpcid = this.resource.id
        params.forvirtualnetwork = true
        if (this.vpcTier) {
          params.associatednetworkid = this.vpcTier
        }
      } else if (this.resource.type === 'Shared') {
        params.networkid = this.resource.id
        params.allocatedonly = false
        params.forvirtualnetwork = false
      } else {
        params.associatednetworkid = this.resource.id
      }
      this.fetchLoading = !listRequest.loaded
      try {
        if (!this.isShared) {
          const zones = await getAPI('listZones', { id: this.resource.zoneid }, { preserveOnFailure: true })
          if (!this.isListRequestCurrent('fetchData', listRequest)) return
          this.zoneExtNetProvider = zones?.listzonesresponse?.zone?.[0]?.provider || null
          if (['nsx', 'netris'].includes(this.zoneExtNetProvider?.toLowerCase())) params.forprovider = true
        }
        const json = await getAPI('listPublicIpAddresses', params, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.totalIps = json.listpublicipaddressesresponse.count || 0
        this.ips = json.listpublicipaddressesresponse.publicipaddress || []
        const ids = this.selectedRowKeys.filter(id => this.ips.some(ip => ip.id === id && this.releasableIp(ip)))
        if (ids.length !== this.selectedRowKeys.length && !this.showGroupActionModal) this.setSelection(ids)
      } catch (error) {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      } finally {
        if (this.isListRequestCurrent('fetchData', listRequest)) this.fetchLoading = false
      }
    },
    releasableIp (ip) {
      return usablePublicIp(ip) && ip.forvirtualnetwork === true && !ip.issourcenat &&
        (this.isVpc ? ip.vpcid === this.resource.id : ip.associatednetworkid === this.resource.id)
    },
    canChangeSourceNat (ip) {
      return this.releasableIp(ip) && !ip.isstaticnat && ip.hasrules === false && !this.listRefreshFailed &&
        this.sourceNatCommand in this.$store.getters.apis && !networkBusy(this.resource) &&
        (this.isVpc || publicIpService(this.resource, 'SourceNat'))
    },
    handleTierSelect (tier) {
      this.vpcTier = tier
      this.page = 1
      this.resetSelection()
      this.fetchData()
    },
    setSelection (selection) {
      this.selectedRowKeys = selection
      this.$emit('selection-change', this.selectedRowKeys)
      this.selectedItems = (this.ips.filter(function (item) {
        return selection.indexOf(item.id) !== -1
      }))
    },
    async setSourceNatIp () {
      const ip = this.ips.find(row => row.id === this.sourceNatIp?.id)
      if (!ip || !this.canChangeSourceNat(ip) || this.networkMutationBlocked) return
      await this.submitNetworkMutation(this.sourceNatCommand, {
        id: this.resource.id,
        sourcenatipaddress: ip.ipaddress
      }, async () => {
        await this.fetchData()
        this.sourceNatIp = null
      })
    },
    resetSelection () {
      this.setSelection([])
    },
    onSelectChange (selectedRowKeys, selectedRows) {
      this.setSelection(selectedRowKeys.filter(id => this.ips.some(ip => ip.id === id && this.releasableIp(ip))))
    },
    changePage (page, pageSize) {
      this.resetSelection()
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    changePageSize (currentPage, pageSize) {
      this.resetSelection()
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    },
    bulkActionConfirmation () {
      if (!this.canReleaseIps || !this.selectedItems.length) return
      this.showConfirmationAction = true
      this.selectedColumns = this.columns.filter(column => {
        return !this.filterColumns.includes(column.title)
      })
      this.selectedItems = this.selectedItems.map(v => ({ ...v, status: 'InProgress' }))
    },
    async acquireIpAddress () {
      if (!this.canAcquireIp) return
      const params = this.$route.path.startsWith('/vpc')
        ? { vpcid: this.resource.id, ...(this.vpcTier ? { networkid: this.vpcTier } : {}) }
        : { networkid: this.resource.id }
      params.ipaddress = this.acquireIp
      return this.submitNetworkMutation('associateIpAddress', params, async () => {
        this.showAcquireIp = false
        await this.fetchData()
      })
    },
    handleCancel () {
      eventBus.emit('update-bulk-job-status', { items: this.selectedItems, action: false })
      this.showGroupActionModal = false
      this.selectedItems = []
      this.selectedColumns = []
      this.selectedRowKeys = []
      this.parentFetchData()
    },
    releaseIpAddresses () {
      this.showConfirmationAction = false
      if (!this.canReleaseIps) return
      this.selectedItems = this.selectedItems.filter(ip => this.ips.some(row => row.id === ip.id && this.releasableIp(row)))
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
      if (this.selectedItems.length > 0) {
        this.showGroupActionModal = true
      }
      for (const ip of this.selectedItems) {
        this.releaseIpAddress(ip)
      }
    },
    releaseIpAddress (ip) {
      if (!this.canReleaseIps || !this.releasableIp(ip)) return
      this.fetchLoading = true
      postAPI('disassociateIpAddress', {
        id: ip.id
      }).then(response => {
        const jobId = response.disassociateipaddressresponse.jobid
        eventBus.emit('update-job-details', { jobId, resourceId: null })
        this.$pollJob({
          title: this.$t('label.action.release.ip'),
          description: ip.id,
          jobId: jobId,
          successMessage: this.$t('message.success.release.ip'),
          successMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: ip.id, state: 'success' })
            }
            this.fetchData()
          },
          errorMessage: this.$t('message.release.ip.failed'),
          errorMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: ip.id, state: 'failed' })
            }
            this.fetchData()
          },
          loadingMessage: `${this.$t('label.releasing.ip')} ${this.$t('label.for')} ${this.resource.name} ${this.$t('label.is.in.progress')}`,
          catchMessage: this.$t('error.fetching.async.job.result'),
          bulkAction: `${this.selectedItems.length > 0}` && this.showGroupActionModal
        })
      }).catch(error => {
        this.fetchLoading = false
        this.$notification.error({
          message: `${this.$t('label.error')} ${error.response.status}`,
          description: error.response.data.errorresponse.errortext,
          duration: 0
        })
      })
    },
    async onShowAcquireIp () {
      if (!this.canAcquireIp) return
      if (this.networkMutation?.status === 'success') { this.resetNetworkMutation(); this.acquireIp = null }
      this.showAcquireIp = true
    },
    onCloseModal () {
      if (!this.networkMutationSubmitting) this.showAcquireIp = false
    },
    closeModal () {
      this.showConfirmationAction = false
    },
    showChangeSourceNat (ip) {
      if (!this.canChangeSourceNat(ip) || this.networkMutationBlocked) return
      this.resetNetworkMutation()
      this.sourceNatIp = ip
    },
    cancelChangeSourceNat () {
      if (!this.networkMutationBlocked) this.sourceNatIp = null
    }
  }
}
</script>

<style lang="scss" scoped>
.network-ip-tab { min-width: 0; }
.network-ip-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.network-ip-refresh-alert { margin-bottom: 16px; }
.network-source-nat-warning { margin: 16px 0; }

.list {
    max-height: 95vh;
    width: 95vw;
    overflow-y: scroll;
    margin: -24px;

    @media (min-width: 1000px) {
      max-height: 70vh;
      width: 900px;
    }

    &__header,
    &__footer {
      padding: 20px;
    }

    &__header {
      display: flex;

      .ant-select {
        min-width: 200px;
      }

      &__col {

        &:not(:last-child) {
          margin-right: 20px;
        }

        &--full {
          flex: 1;
        }

      }

    }

    &__footer {
      display: flex;
      justify-content: flex-end;

      button {
        &:not(:last-child) {
          margin-right: 10px;
        }
      }
    }

    &__item {
      padding-right: 20px;
      padding-left: 20px;

      &--selected {
        background-color: #e6f7ff;
      }

    }

    &__title {
      font-weight: bold;
    }

    &__outer-container {
      width: 100%;
      display: flex;
      flex-direction: column;
    }

    &__container {
      display: flex;
      flex-direction: column;
      width: 100%;
      cursor: pointer;

      @media (min-width: 760px) {
        flex-direction: row;
        align-items: center;
      }

    }

    &__row {
      margin-bottom: 10px;

      @media (min-width: 760px) {
        margin-right: 20px;
        margin-bottom: 0;
      }
    }

    &__radio {
      display: flex;
      justify-content: flex-end;
    }

  }
</style>
