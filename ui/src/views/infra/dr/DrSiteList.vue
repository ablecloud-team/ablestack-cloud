<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<template>
  <div class="cross-dr-page cross-dr-standard-page">
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <a-affix
      :key="'affix-' + showSearchFilters"
      :offsetTop="this.$store.getters.maintenanceInitiated || this.$store.getters.shutdownTriggered ? 103 : 78">
      <a-card class="breadcrumb-card" style="z-index: 10">
        <a-row>
          <a-col
            :span="device === 'mobile' ? 24 : 12"
            style="padding-left: 12px; margin-top: 10px">
            <breadcrumb :resource="breadcrumbResource">
              <template #end>
                <a-button
                  :loading="loading"
                  style="margin-bottom: 5px"
                  shape="round"
                  size="small"
                  @click="fetchData">
                  <template #icon><ReloadOutlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
                <a-tooltip placement="right">
                  <template #title>
                    {{ $t('label.filterby') }}
                  </template>
                  <a-select
                    v-if="!detailId"
                    :placeholder="$t('label.filterby')"
                    :value="filterValue"
                    style="min-width: 100px; margin-left: 10px; margin-bottom: 5px"
                    size="small"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    @change="changeFilter">
                    <template #suffixIcon><filter-outlined class="ant-select-suffix" /></template>
                    <a-select-option key="all" :label="$t('label.all')">
                      {{ $t('label.all') }}
                    </a-select-option>
                    <a-select-option
                      v-for="state in healthStates"
                      :key="state"
                      :label="state">
                      {{ state }}
                    </a-select-option>
                  </a-select>
                </a-tooltip>
              </template>
            </breadcrumb>
          </a-col>
          <a-col
            :span="device === 'mobile' ? 24 : 12"
            :style="device === 'mobile' ? { float: 'right', 'margin-top': '12px', 'margin-bottom': '-6px', display: 'table' } : { float: 'right', display: 'table', 'margin-top': '6px' }">
            <dr-resource-action-menu
              v-if="detailId && detailSite.id"
              :actions="siteActions"
              :resource="detailSite"
              :triggerStyle="{ float: device === 'mobile' ? 'left' : 'right' }"
              @exec-action="execSiteAction" />
            <action-button
              v-else-if="'createDrSite' in $store.getters.apis"
              :style="{ 'margin-right': '10px', display: 'inline-flex' }"
              :loading="loading"
              :actions="createSiteActions"
              :selectedRowKeys="selectedRowKeys"
              :selectedItems="[]"
              :dataView="false"
              :resource="{}"
              @exec-action="openCreateModal" />
            <search-view
              v-if="!detailId"
              :searchFilters="searchFilters"
              :searchParams="searchParams"
              apiName="listDrSites"
              @search="onSearch" />
          </a-col>
        </a-row>
        <a-row
          v-if="showSearchFilters"
          style="min-height: 36px; padding-top: 12px; padding-left: 12px;">
          <search-filter
            :filters="activeFiltersList"
            apiName="listDrSites"
            @removeFilter="removeFilter" />
        </a-row>
      </a-card>
    </a-affix>

    <a-alert
      v-if="!hasListApi"
      type="warning"
      show-icon
      :message="$t('message.dr.api.unavailable')" />

    <template v-else-if="detailId">
      <resource-layout>
        <template #left>
          <dr-resource-info-card
            resourceType="site"
            :resource="detailSite"
            :title="detailSite.name || detailSite.id || '-'"
            :tags="siteInfoTags"
            :summaryFields="siteSummaryFields"
            :loading="loading"
            @contextmenu="openSiteContextMenu" />
        </template>

        <template #right>
          <a-card
            class="spin-content"
            :loading="loading"
            :bordered="true"
            style="width: 100%"
            @contextmenu.stop.prevent="openSiteContextMenu($event, detailSite)">
            <a-tabs
              :tabPosition="resourceTabPosition"
              :style="{ width: '100%', marginTop: resourceTabPosition === 'top' ? '-12px' : '0' }"
              :activeKey="activeTab"
              :animated="false"
              @change="changeTab">
              <a-tab-pane key="details" :tab="$t('label.details')">
                <div class="cross-dr-overview">
                  <dr-resource-details-tab
                    :resource="detailSite"
                    :fields="siteDetailFields" />
                </div>
              </a-tab-pane>
              <a-tab-pane key="plans" :tab="$t('label.dr.plans')">
                <a-table
                  size="middle"
                  :columns="planColumns"
                  :dataSource="sitePlans"
                  :rowKey="record => record.id"
                  :pagination="{ pageSize: 10 }">
                  <template #bodyCell="{ column, record, text }">
                    <template v-if="column.key === 'name'">
                      <router-link :to="{ path: '/drplan/' + record.id }">{{ text || record.id }}</router-link>
                    </template>
                    <template v-else-if="column.key === 'state'">
                      <status :text="text || ''" displayText />
                    </template>
                  </template>
                </a-table>
              </a-tab-pane>
              <a-tab-pane key="healthChecks" :tab="$t('label.dr.site.health.history')">
                <a-table
                  size="middle"
                  :columns="healthCheckColumns"
                  :dataSource="healthChecks"
                  :rowKey="record => record.id"
                  :loading="healthCheckLoading"
                  :pagination="{ pageSize: 10, total: healthCheckCount }">
                  <template #bodyCell="{ column, text }">
                    <template v-if="column.key === 'healthstate'">
                      <status :text="text || ''" displayText />
                    </template>
                    <template v-else-if="column.key === 'latencyms'">
                      {{ text === undefined || text === null || text === '' ? '-' : `${text} ms` }}
                    </template>
                  </template>
                </a-table>
              </a-tab-pane>
            </a-tabs>
          </a-card>
        </template>
      </resource-layout>
    </template>

    <template v-else>
      <div class="row-element" @contextmenu="openListContextMenu">
        <a-table
          class="cross-dr-standard-table"
          size="middle"
          :columns="tableColumns"
          :dataSource="pagedSites"
          :rowKey="record => record.id"
          :loading="loading"
          :pagination="false"
          :rowSelection="listRowSelection">
          <template #customFilterDropdown>
            <div style="padding: 8px" class="filter-dropdown">
              <a-menu>
                <a-menu-item
                  v-for="column in columnSelectorColumns"
                  :key="column.key"
                  @click="updateSelectedColumns(column.dataIndex)">
                  <a-checkbox :checked="selectedColumns.includes(column.dataIndex)" />
                  {{ column.title }}
                </a-menu-item>
              </a-menu>
            </div>
          </template>
          <template #bodyCell="{ column, record, text }">
            <template v-if="column.key === 'name'">
              <span class="cross-dr-resource-name">
                <GlobalOutlined />
                <router-link :to="{ path: '/drsite/' + record.id }">{{ text || record.id }}</router-link>
              </span>
            </template>
            <template v-else-if="column.key === 'healthstate' || column.key === 'state'">
              <status :text="text || ''" displayText />
            </template>
            <template v-else-if="column.key === 'sitetype'">
              {{ $t(siteTypeLabel(text)) }}
            </template>
          </template>
        </a-table>
        <a-pagination
          class="row-element"
          style="margin-top: 10px"
          size="small"
          :current="normalizedPage"
          :pageSize="pageSize"
          :total="filteredSites.length"
          :showTotal="paginationTotal"
          :pageSizeOptions="pageSizeOptions"
          @change="changePage"
          @showSizeChange="changePageSize"
          showSizeChanger
          showQuickJumper>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>
      </div>
    </template>

    <dr-form-modal
      :visible="showCreateModal"
      :title="siteModalTitle"
      :confirm-loading="createLoading"
      @cancel="closeCreateModal"
      @ok="submitSite">
      <div class="form-layout cross-dr-form-layout" v-ctrl-enter="submitSite">
        <a-form layout="vertical">
          <a-form-item required>
            <template #label>
              <tooltip-label
                :title="$t('label.name')"
                :tooltip="$t('message.dr.site.name.tooltip')" />
            </template>
            <a-input
              v-model:value="createForm.name"
              :maxlength="255"
              :placeholder="$t('message.dr.site.name.placeholder')" />
          </a-form-item>
          <a-form-item>
            <template #label>
              <tooltip-label
                :title="$t('label.description')"
                :tooltip="$t('message.dr.site.description.tooltip')" />
            </template>
            <a-input
              v-model:value="createForm.description"
              :maxlength="1024"
              :placeholder="$t('message.dr.site.description.placeholder')" />
          </a-form-item>
          <a-form-item required>
            <template #label>
              <tooltip-label
                :title="$t('label.type')"
                :tooltip="$t('message.dr.site.type.tooltip')" />
            </template>
            <a-select
              v-model:value="createForm.sitetype"
              :placeholder="$t('message.dr.site.type.placeholder')"
              @change="changeCreateSiteType">
              <a-select-option
                v-for="option in siteTypeOptions"
                :key="option.value"
                :value="option.value">
                {{ $t(option.label) }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <template v-if="usesMoldCredential">
            <div key="mold-credentials" class="cross-dr-form-section-title"><span>{{ $t('label.dr.site.connection.info') }}</span></div>
            <a-form-item key="mold-api-url" :required="siteFormMode === 'create'">
              <template #label>
                <tooltip-label
                  :title="$t('label.dr.mold.api.url')"
                  :tooltip="$t('message.dr.site.mold.api.url.tooltip')" />
              </template>
              <a-input
                v-model:value="createForm.moldapiurl"
                :placeholder="$t('message.dr.site.mold.api.url.placeholder')"
                @change="resetSiteInventory" />
            </a-form-item>
            <a-form-item key="mold-api-key" :required="siteFormMode === 'create'">
              <template #label>
                <tooltip-label
                  :title="$t('label.dr.mold.api.key')"
                  :tooltip="$t('message.dr.site.mold.api.key.tooltip')" />
              </template>
              <a-input
                v-model:value="createForm.moldapikey"
                :placeholder="$t('message.dr.site.mold.api.key.placeholder')"
                @change="resetSiteInventory" />
            </a-form-item>
            <a-form-item key="mold-secret-key" :required="siteFormMode === 'create'">
              <template #label>
                <tooltip-label
                  :title="$t('label.dr.mold.secret.key')"
                  :tooltip="$t('message.dr.site.mold.secret.key.tooltip')" />
              </template>
              <a-input-password
                v-model:value="createForm.moldsecretkey"
                autocomplete="new-password"
                :placeholder="$t('message.dr.site.mold.secret.key.placeholder')"
                @change="resetSiteInventory" />
            </a-form-item>
          </template>
          <template v-else-if="usesVCenterCredential">
            <div key="vcenter-credentials" class="cross-dr-form-section-title"><span>{{ $t('label.dr.site.connection.info') }}</span></div>
            <a-form-item key="vcenter-url" :required="siteFormMode === 'create'">
              <template #label>
                <tooltip-label
                  :title="$t('label.dr.vcenter.url')"
                  :tooltip="$t('message.dr.site.vcenter.url.tooltip')" />
              </template>
              <a-input
                v-model:value="createForm.vcenterurl"
                :placeholder="$t('message.dr.site.vcenter.url.placeholder')" />
            </a-form-item>
            <a-form-item key="vcenter-username" :required="siteFormMode === 'create'">
              <template #label>
                <tooltip-label
                  :title="$t('label.dr.vcenter.username')"
                  :tooltip="$t('message.dr.site.vcenter.username.tooltip')" />
              </template>
              <a-input
                v-model:value="createForm.vcenterusername"
                data-testid="dr-site-vcenter-username"
                :placeholder="$t('message.dr.site.vcenter.username.placeholder')" />
            </a-form-item>
            <a-form-item key="vcenter-password" :required="siteFormMode === 'create'">
              <template #label>
                <tooltip-label
                  :title="$t('label.dr.vcenter.password')"
                  :tooltip="$t('message.dr.site.vcenter.password.tooltip')" />
              </template>
              <a-input-password
                v-model:value="createForm.vcenterpassword"
                autocomplete="new-password"
                :placeholder="$t('message.dr.site.vcenter.password.placeholder')" />
            </a-form-item>
          </template>
          <a-form-item
            v-if="usesMoldCredential || usesVCenterCredential">
            <template #label>
              <tooltip-label
                :title="$t(tlsVerifyLabel)"
                :tooltip="$t('message.dr.site.tls.verify.tooltip')" />
            </template>
            <a-switch v-model:checked="createForm.tlsverify" />
          </a-form-item>
          <a-collapse v-if="showCreateAdvancedSettings" ghost class="cross-dr-advanced-collapse">
            <a-collapse-panel key="site-advanced" :header="$t('label.dr.site.advanced.settings')">
              <a-form-item v-if="usesMoldCredential">
                <template #label>
                  <tooltip-label
                    :title="$t('label.zoneid')"
                    :tooltip="$t('message.dr.site.zone.tooltip')" />
                </template>
                <a-select
                  v-model:value="createForm.zoneexternalid"
                  :options="siteZoneOptions"
                  :loading="siteInventoryLoading"
                  :placeholder="$t('message.dr.site.select.zone')"
                  show-search
                  allow-clear
                  option-filter-prop="label"
                  @focus="fetchSiteInventory"
                  @change="changeCreateZone" />
                <div v-if="siteInventoryHelpText" class="cross-dr-form-help">{{ siteInventoryHelpText }}</div>
              </a-form-item>
              <a-form-item v-if="createForm.sitetype === 'MOLD_VMWARE'">
                <template #label>
                  <tooltip-label
                    :title="$t('label.dr.vmware.dc')"
                    :tooltip="$t('message.dr.site.vmware.dc.tooltip')" />
                </template>
                <a-select
                  v-model:value="createForm.vmwaredcexternalid"
                  :options="siteVmwareDcOptions"
                  :loading="siteInventoryLoading"
                  :disabled="siteZoneOptions.length > 0 && !createForm.zoneexternalid"
                  :placeholder="$t('message.dr.site.select.vmware.dc')"
                  show-search
                  allow-clear
                  option-filter-prop="label"
                  @focus="fetchSiteInventory"
                  @change="changeCreateVmwareDatacenter" />
              </a-form-item>
            </a-collapse-panel>
          </a-collapse>
        </a-form>
      </div>
    </dr-form-modal>
    <dr-resource-context-menu
      :visible="contextMenuVisible"
      :actions="siteActions"
      :resource="contextMenuSite"
      :position="contextMenuPosition"
      @close="closeContextMenu"
      @exec-action="execSiteAction" />
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { notification } from 'ant-design-vue'
import ActionButton from '@/components/view/ActionButton'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import DrFormModal from '@/components/dr/DrFormModal.vue'
import DrResourceDetailsTab from '@/components/dr/DrResourceDetailsTab.vue'
import DrResourceInfoCard from '@/components/dr/DrResourceInfoCard.vue'
import DrResourceActionMenu from '@/components/dr/DrResourceActionMenu.vue'
import DrResourceContextMenu from '@/components/dr/DrResourceContextMenu.vue'
import ResourceLayout from '@/layouts/ResourceLayout'
import SearchFilter from '@/components/view/SearchFilter'
import SearchView from '@/components/view/SearchView'
import Status from '@/components/widgets/Status'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { checkDrSite, createDrSite, deleteDrSite, discoverDrSiteInventory, getDrSite, listDrPlans, listDrSiteHealthChecks, listDrSites, updateDrSite } from '@/api/dr'
import { buildDrSiteActions } from '@/utils/dr/resourceActions'
import { mixinDevice } from '@/utils/mixin.js'
import { ClockCircleOutlined, GlobalOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'

export default {
  name: 'DrSiteList',
  components: {
    ActionButton,
    Breadcrumb,
    DrFormModal,
    DrResourceDetailsTab,
    DrResourceInfoCard,
    DrResourceActionMenu,
    DrResourceContextMenu,
    GlobalOutlined,
    ResourceLayout,
    SearchFilter,
    SearchView,
    Status,
    TooltipLabel
  },
  mixins: [mixinDevice, listRefreshMixin(['fetchList', 'refreshSiteDetail'], { select: vm => [vm.detailId ? 'refreshSiteDetail' : 'fetchList'] })],
  data () {
    return {
      loading: false,
      checking: false,
      checkingId: '',
      createLoading: false,
      showCreateModal: false,
      siteFormMode: 'create',
      editingSite: {},
      contextMenuVisible: false,
      contextMenuSite: {},
      contextMenuPosition: { x: 0, y: 0 },
      sites: [],
      plans: [],
      healthChecks: [],
      healthCheckCount: 0,
      healthCheckLoading: false,
      siteInventoryLoading: false,
      siteInventoryError: '',
      siteInventoryLoadedKey: '',
      siteZoneOptions: [],
      siteVmwareDcOptions: [],
      detailSite: {},
      activeTab: this.normalizeDetailTab(this.$route.query.tab),
      searchQuery: '',
      searchParams: {},
      selectedRowKeys: [],
      selectedColumns: ['name', 'sitetype', 'hypervisortype', 'endpoint', 'healthstate', 'state', 'lastchecked'],
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize || 20,
      filters: {
        health: undefined
      },
      createForm: this.defaultCreateForm(),
      healthStates: ['CONNECTED', 'DEGRADED', 'DISCONNECTED', 'UNKNOWN'],
      columns: [
        { key: 'name', title: this.$t('label.name'), dataIndex: 'name', sorter: this.sortBy('name') },
        { key: 'sitetype', title: this.$t('label.type'), dataIndex: 'sitetype', sorter: this.sortBy('sitetype') },
        { key: 'hypervisortype', title: this.$t('label.hypervisor'), dataIndex: 'hypervisortype', sorter: this.sortBy('hypervisortype') },
        { key: 'endpoint', title: this.$t('label.dr.endpoint'), dataIndex: 'endpoint', ellipsis: true, sorter: this.sortBy('endpoint') },
        { key: 'healthstate', title: this.$t('label.dr.site.health'), dataIndex: 'healthstate', sorter: this.sortBy('healthstate') },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state', sorter: this.sortBy('state') },
        { key: 'lastchecked', title: this.$t('label.dr.last.checked'), dataIndex: 'lastchecked', sorter: this.sortBy('lastchecked') }
      ],
      planColumns: [
        { key: 'name', title: this.$t('label.name'), dataIndex: 'name' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'direction', title: this.$t('label.dr.direction'), dataIndex: 'direction' },
        { key: 'enginetype', title: this.$t('label.dr.engine'), dataIndex: 'enginetype' },
        { key: 'targetreadyat', title: this.$t('label.dr.target.ready.at'), dataIndex: 'targetreadyat' }
      ],
      healthCheckColumns: [
        { key: 'checkedat', title: this.$t('label.dr.site.health.checked.at'), dataIndex: 'checkedat' },
        { key: 'triggertype', title: this.$t('label.dr.site.health.trigger'), dataIndex: 'triggertype' },
        { key: 'healthstate', title: this.$t('label.dr.site.health'), dataIndex: 'healthstate' },
        { key: 'reasoncode', title: this.$t('label.dr.site.health.reason'), dataIndex: 'reasoncode' },
        { key: 'message', title: this.$t('label.dr.site.health.message'), dataIndex: 'message', ellipsis: true },
        { key: 'latencyms', title: this.$t('label.dr.site.health.latency'), dataIndex: 'latencyms' },
        { key: 'endpoint', title: this.$t('label.dr.endpoint'), dataIndex: 'endpoint', ellipsis: true },
        { key: 'credentialstate', title: this.$t('label.dr.credential.status'), dataIndex: 'credentialstate' }
      ]
    }
  },
  computed: {
    hasListApi () {
      return 'listDrSites' in this.$store.getters.apis
    },
    detailId () {
      return this.$route.params.id || ''
    },
    breadcrumbResource () {
      return this.detailId ? this.detailSite : {}
    },
    createSiteActions () {
      return [{
        api: 'createDrSite',
        icon: 'plus-outlined',
        label: 'label.dr.site.add',
        listView: true
      }]
    },
    siteActions () {
      return buildDrSiteActions()
    },
    siteModalTitle () {
      return this.siteFormMode === 'edit' ? this.$t('label.dr.site.edit') : this.$t('label.dr.site.add')
    },
    searchFilters () {
      return ['sitetype', 'hypervisortype']
    },
    usesMoldCredential () {
      return String(this.createForm.sitetype || '').startsWith('MOLD_')
    },
    usesVCenterCredential () {
      return this.createForm.sitetype === 'VMWARE_DIRECT'
    },
    showCreateAdvancedSettings () {
      return this.usesMoldCredential
    },
    canDiscoverSiteInventory () {
      if (!this.usesMoldCredential || !('discoverDrSiteInventory' in this.$store.getters.apis)) {
        return false
      }
      if (this.siteFormMode === 'edit' && this.editingSite?.id && !this.hasAllCredentialValues(['moldapiurl', 'moldapikey', 'moldsecretkey'])) {
        return true
      }
      return this.hasAllCredentialValues(['moldapiurl', 'moldapikey', 'moldsecretkey'])
    },
    siteInventoryHelpText () {
      if (!this.usesMoldCredential) {
        return ''
      }
      if (this.siteInventoryError) {
        return this.siteInventoryError
      }
      if (!this.canDiscoverSiteInventory) {
        return this.$t('message.dr.site.inventory.credential.required')
      }
      if (!this.siteInventoryLoading && this.siteZoneOptions.length === 0 && this.siteInventoryLoadedKey) {
        return this.$t('message.dr.site.inventory.empty')
      }
      return ''
    },
    siteTypeOptions () {
      return [
        { value: 'MOLD_KVM', label: 'label.dr.site.type.mold.kvm' },
        { value: 'VMWARE_DIRECT', label: 'label.dr.site.type.vmware.direct' }
      ]
    },
    tlsVerifyLabel () {
      return this.usesVCenterCredential ? 'label.dr.vcenter.tls.verify' : 'label.dr.mold.tls.verify'
    },
    columnSelectorColumns () {
      return this.columns.filter(column => !column.alwaysVisible)
    },
    tableColumns () {
      return [
        ...this.columns.filter(column => column.alwaysVisible || this.selectedColumns.includes(column.dataIndex)),
        {
          key: 'filtercolumn',
          dataIndex: 'filtercolumn',
          title: '',
          customFilterDropdown: true,
          width: 5
        }
      ]
    },
    listRowSelection () {
      return {
        selectedRowKeys: this.selectedRowKeys,
        onChange: this.onRowSelectionChange,
        columnWidth: 30
      }
    },
    filterValue () {
      return this.filters.health || 'all'
    },
    activeFiltersList () {
      const activeFilters = []
      if (this.filters.health) {
        activeFilters.push({ key: 'healthstate', value: this.filters.health, isTag: false })
      }
      for (const key in this.searchParams) {
        const value = this.searchParams[key]
        if (value !== '' && value !== undefined && value !== null) {
          activeFilters.push({ key, value, isTag: false })
        }
      }
      return activeFilters
    },
    showSearchFilters () {
      const excludedKeys = ['page', 'pagesize', 'q', 'keyword', 'tags', 'projectid']
      return !this.detailId && this.activeFiltersList.some(f => !excludedKeys.includes(f.key))
    },
    filteredSites () {
      const keyword = this.normalizeText(this.searchQuery)
      return this.sites.filter(site => {
        if (this.filters.health && site.healthstate !== this.filters.health) {
          return false
        }
        if (!this.matchesSearchParams(site, this.searchParams, ['sitetype', 'hypervisortype'])) {
          return false
        }
        if (keyword && !this.matchesKeyword(site, keyword, ['name', 'description', 'sitetype', 'hypervisortype', 'endpoint', 'state', 'healthstate'])) {
          return false
        }
        return true
      })
    },
    normalizedPage () {
      const maxPage = Math.max(1, Math.ceil(this.filteredSites.length / this.pageSize))
      return Math.min(this.page, maxPage)
    },
    pagedSites () {
      const offset = (this.normalizedPage - 1) * this.pageSize
      return this.filteredSites.slice(offset, offset + this.pageSize)
    },
    sitePlans () {
      return this.plans.filter(plan => plan.sourcesiteid === this.detailSite.id || plan.targetsiteid === this.detailSite.id)
    },
    siteInfoTags () {
      return [
        {
          key: 'sitetype',
          label: this.detailSite.sitetype ? this.$t(this.siteTypeLabel(this.detailSite.sitetype)) : '',
          visible: !!this.detailSite.sitetype
        },
        {
          key: 'hypervisortype',
          label: this.detailSite.hypervisortype,
          visible: !!this.detailSite.hypervisortype
        }
      ]
    },
    siteSummaryFields () {
      const site = this.detailSite || {}
      return [
        {
          key: 'healthstate',
          label: this.$t('label.dr.site.health'),
          component: Status,
          props: { text: site.healthstate || '', displayText: true },
          visible: !!site.healthstate
        },
        {
          key: 'healthreason',
          label: this.$t('label.dr.site.health.reason'),
          value: this.healthReasonSummary(site),
          iconComponent: SafetyCertificateOutlined,
          visible: !!site.healthreasoncode || !!site.healthmessage
        },
        {
          key: 'state',
          label: this.$t('label.status'),
          component: Status,
          props: { text: site.state || '', displayText: true },
          visible: !!site.state
        },
        {
          key: 'id',
          label: this.$t('label.id'),
          value: site.id,
          icon: 'barcode-outlined',
          copy: true,
          copyTooltip: this.$t('label.copyid'),
          copyResource: String(site.id || ''),
          copyLabel: true,
          visible: !!site.id
        },
        {
          key: 'endpoint',
          label: this.$t('label.dr.endpoint'),
          value: site.endpoint,
          icon: 'environment-outlined',
          copy: true,
          copyResource: String(site.endpoint || ''),
          copyLabel: true,
          visible: !!site.endpoint
        },
        {
          key: 'credentialstate',
          label: this.$t('label.dr.credential.status'),
          value: this.credentialSummary(site),
          iconComponent: SafetyCertificateOutlined,
          visible: site.credentialconfigured !== undefined || !!site.credentialstate
        },
        {
          key: 'lastchecked',
          label: this.$t('label.dr.last.checked'),
          value: site.lastchecked,
          iconComponent: ClockCircleOutlined,
          visible: !!site.lastchecked
        }
      ]
    },
    siteDetailFields () {
      const site = this.detailSite || {}
      return [
        { key: 'id', label: this.$t('label.id'), value: site.id },
        { key: 'name', label: this.$t('label.name'), value: site.name },
        { key: 'description', label: this.$t('label.description'), value: site.description },
        { key: 'sitetype', label: this.$t('label.type'), value: site.sitetype ? this.$t(this.siteTypeLabel(site.sitetype)) : '-' },
        { key: 'hypervisortype', label: this.$t('label.hypervisor'), value: site.hypervisortype },
        { key: 'endpoint', label: this.$t('label.dr.endpoint'), value: site.endpoint },
        { key: 'zone', label: this.$t('label.zoneid'), value: this.inventoryDisplayValue(site.zonename, site.zoneexternalid, site.zoneid) },
        { key: 'vmwaredc', label: this.$t('label.dr.vmware.dc'), value: this.inventoryDisplayValue(site.vmwaredcname, site.vmwaredcexternalid, site.vmwaredcid) },
        { key: 'healthstate', label: this.$t('label.dr.site.health'), component: Status, props: { text: site.healthstate || '', displayText: true } },
        { key: 'healthreasoncode', label: this.$t('label.dr.site.health.reason'), value: site.healthreasoncode },
        { key: 'healthmessage', label: this.$t('label.dr.site.health.message'), value: site.healthmessage },
        { key: 'healthlatencyms', label: this.$t('label.dr.site.health.latency'), value: this.healthLatencySummary(site) },
        { key: 'credentialstate', label: this.$t('label.dr.credential.status'), value: this.credentialSummary(site) },
        { key: 'credentialtype', label: this.$t('label.dr.credential.type'), value: site.credentialtype },
        { key: 'credentialendpoint', label: this.$t('label.dr.credential.endpoint'), value: site.credentialendpoint },
        { key: 'credentialprincipal', label: this.$t('label.dr.credential.principal'), value: site.credentialprincipal },
        { key: 'created', label: this.$t('label.created'), value: site.created }
      ]
    },
    pageSizeOptions () {
      return this.device === 'desktop' ? ['20', '50', '100', '200'] : ['10', '20', '50', '100', '200']
    }
  },
  watch: {
    '$route.fullPath': function () {
      this.activeTab = this.normalizeDetailTab(this.$route.query.tab)
      this.fetchData()
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    defaultCreateForm () {
      return {
        name: '',
        description: '',
        sitetype: 'MOLD_KVM',
        hypervisortype: 'KVM',
        endpoint: '',
        moldapiurl: '',
        moldapikey: '',
        moldsecretkey: '',
        vcenterurl: '',
        vcenterusername: '',
        vcenterpassword: '',
        tlsverify: true,
        zoneid: undefined,
        zoneexternalid: undefined,
        zonename: '',
        vmwaredcid: undefined,
        vmwaredcexternalid: undefined,
        vmwaredcname: ''
      }
    },
    fetchData () {
      if (this.detailId) {
        this.fetchDetail()
      } else {
        this.fetchList()
      }
    },
    fetchList () {
      const listRequest = this.listRequestToken('fetchList')
      this.loading = !listRequest.loaded
      return listDrSites().then(result => {
        if (!this.isListRequestCurrent('fetchList', listRequest)) return
        this.sites = result.items || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchList', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchList', listRequest)) return
        this.loading = false
      })
    },
    async refreshSiteDetail () {
      if (!this.detailId) return
      const request = this.listRequestToken('refreshSiteDetail')
      try {
        const [site, plans, health] = await Promise.all([
          getDrSite(this.detailId),
          'listDrPlans' in this.$store.getters.apis ? listDrPlans() : Promise.resolve({ items: [] }),
          this.activeTab === 'healthChecks' && 'listDrSiteHealthChecks' in this.$store.getters.apis
            ? listDrSiteHealthChecks({ id: this.detailId, page: 1, pagesize: 50 }) : Promise.resolve(null)
        ])
        if (!this.isListRequestCurrent('refreshSiteDetail', request)) return
        this.detailSite = site || {}
        this.plans = plans.items || []
        if (health) {
          this.healthChecks = health.items || []
          this.healthCheckCount = health.count || 0
        }
      } catch (error) {
        if (!this.isListRequestCurrent('refreshSiteDetail', request)) return
        request.failed = true
        this.listRefreshFailed = true
      }
    },
    fetchDetail () {
      if (!this.detailId) {
        return
      }
      this.loading = true
      Promise.all([
        getDrSite(this.detailId).then(site => {
          this.detailSite = site || {}
        }),
        this.fetchPlans()
      ]).then(() => {
        if (this.activeTab === 'healthChecks') {
          return this.fetchHealthChecks()
        }
      }).finally(() => {
        this.loading = false
      })
    },
    fetchPlans () {
      if (!('listDrPlans' in this.$store.getters.apis)) {
        this.plans = []
        return Promise.resolve()
      }
      return listDrPlans().then(result => {
        this.plans = result.items || []
      })
    },
    fetchHealthChecks () {
      if (!this.detailId || !('listDrSiteHealthChecks' in this.$store.getters.apis)) {
        this.healthChecks = []
        this.healthCheckCount = 0
        return Promise.resolve()
      }
      this.healthCheckLoading = true
      return listDrSiteHealthChecks({
        id: this.detailSite.id || this.detailId,
        page: 1,
        pagesize: 50
      }).then(result => {
        this.healthChecks = result.items || []
        this.healthCheckCount = result.count || this.healthChecks.length
      }).finally(() => {
        this.healthCheckLoading = false
      })
    },
    normalizeDetailTab (tab) {
      if (tab === 'overview') {
        return 'details'
      }
      if (tab === 'health' || tab === 'healthHistory') {
        return 'healthChecks'
      }
      return ['details', 'plans', 'healthChecks'].includes(tab) ? tab : 'details'
    },
    changeTab (tab) {
      const normalizedTab = this.normalizeDetailTab(tab)
      this.activeTab = normalizedTab
      if (normalizedTab === 'healthChecks') {
        this.fetchHealthChecks()
      }
      this.$router.replace({ path: this.$route.path, query: Object.assign({}, this.$route.query, { tab: normalizedTab }) }).catch(() => {})
    },
    siteTypeLabel (siteType) {
      return {
        MOLD_KVM: 'label.dr.site.type.mold.kvm',
        MOLD_VMWARE: 'label.dr.site.type.mold.vmware',
        VMWARE_DIRECT: 'label.dr.site.type.vmware.direct'
      }[siteType] || siteType || '-'
    },
    credentialSummary (site) {
      if (site.credentialstate) {
        return site.credentialstate
      }
      if (site.credentialconfigured !== undefined) {
        return site.credentialconfigured ? this.$t('label.configured') : this.$t('label.not.configured')
      }
      return '-'
    },
    healthReasonSummary (site) {
      const reason = site?.healthreasoncode || ''
      const message = site?.healthmessage || ''
      if (reason && message) {
        return `${reason}: ${message}`
      }
      return reason || message || '-'
    },
    healthLatencySummary (site) {
      if (site?.healthlatencyms === undefined || site?.healthlatencyms === null || site?.healthlatencyms === '') {
        return '-'
      }
      return `${site.healthlatencyms} ms`
    },
    healthCheckDescription (site) {
      const parts = [site?.healthstate, site?.healthreasoncode, site?.healthmessage].filter(Boolean)
      return parts.length > 0 ? parts.join(' - ') : this.$t('label.success')
    },
    checkSite (site) {
      if (!site?.id) {
        return
      }
      this.checking = true
      this.checkingId = site.id
      checkDrSite(site.id, true).then(result => {
        notification.success({
          message: this.$t('label.dr.site.check'),
          description: this.healthCheckDescription(result)
        })
        if (this.activeTab === 'healthChecks') {
          this.fetchHealthChecks()
        }
        this.fetchData()
      }).finally(() => {
        this.checking = false
        this.checkingId = ''
      })
    },
    openCreateModal () {
      this.siteFormMode = 'create'
      this.editingSite = {}
      this.createForm = this.defaultCreateForm()
      this.resetSiteInventory()
      this.showCreateModal = true
    },
    openEditModal (site) {
      if (!site?.id) {
        return
      }
      this.siteFormMode = 'edit'
      this.editingSite = site
      const form = Object.assign(this.defaultCreateForm(), {
        name: site.name || '',
        description: site.description || '',
        sitetype: site.sitetype || 'MOLD_KVM',
        hypervisortype: site.hypervisortype || this.resolveHypervisorType(site.sitetype),
        endpoint: site.endpoint || '',
        tlsverify: true,
        zoneid: site.zoneid,
        zoneexternalid: site.zoneexternalid,
        zonename: site.zonename || '',
        vmwaredcid: site.vmwaredcid,
        vmwaredcexternalid: site.vmwaredcexternalid,
        vmwaredcname: site.vmwaredcname || ''
      })
      this.createForm = form
      this.resetSiteInventory()
      this.showCreateModal = true
      this.$nextTick(() => this.fetchSiteInventory())
    },
    closeCreateModal () {
      this.showCreateModal = false
      this.siteFormMode = 'create'
      this.editingSite = {}
      this.resetSiteInventory()
    },
    changeCreateSiteType (siteType) {
      this.createForm.hypervisortype = this.resolveHypervisorType(siteType)
      this.createForm.endpoint = ''
      this.resetSiteInventory()
      if (siteType === 'VMWARE_DIRECT') {
        this.createForm.moldapiurl = ''
        this.createForm.moldapikey = ''
        this.createForm.moldsecretkey = ''
        this.createForm.zoneid = undefined
        this.createForm.zoneexternalid = undefined
        this.createForm.zonename = ''
        this.createForm.vmwaredcid = undefined
        this.createForm.vmwaredcexternalid = undefined
        this.createForm.vmwaredcname = ''
      } else {
        this.createForm.vcenterurl = ''
        this.createForm.vcenterusername = ''
        this.createForm.vcenterpassword = ''
        if (siteType !== 'MOLD_VMWARE') {
          this.createForm.vmwaredcid = undefined
          this.createForm.vmwaredcexternalid = undefined
          this.createForm.vmwaredcname = ''
        }
      }
    },
    changeCreateZone (value, option) {
      this.createForm.zoneexternalid = value
      this.createForm.zonename = this.selectedInventoryName(option)
      this.createForm.zoneid = this.selectedInventoryLocalId(option)
      this.createForm.vmwaredcid = undefined
      this.createForm.vmwaredcexternalid = undefined
      this.createForm.vmwaredcname = ''
      this.siteVmwareDcOptions = []
      this.siteInventoryLoadedKey = ''
      if (this.createForm.sitetype === 'MOLD_VMWARE') {
        this.fetchSiteInventory()
      }
    },
    changeCreateVmwareDatacenter (value, option) {
      this.createForm.vmwaredcexternalid = value
      this.createForm.vmwaredcname = this.selectedInventoryName(option)
      this.createForm.vmwaredcid = this.selectedInventoryLocalId(option)
    },
    resetSiteInventory () {
      this.siteInventoryError = ''
      this.siteInventoryLoadedKey = ''
      this.siteZoneOptions = this.currentValueOption(this.createForm?.zoneexternalid, 'label.zoneid', this.createForm?.zonename)
      this.siteVmwareDcOptions = this.currentValueOption(this.createForm?.vmwaredcexternalid, 'label.dr.vmware.dc', this.createForm?.vmwaredcname)
    },
    fetchSiteInventory () {
      if (!this.usesMoldCredential) {
        return Promise.resolve()
      }
      if (!this.canDiscoverSiteInventory) {
        return Promise.resolve()
      }
      const loadedKey = this.buildSiteInventoryKey()
      if (this.siteInventoryLoadedKey === loadedKey) {
        return Promise.resolve()
      }
      this.siteInventoryLoading = true
      this.siteInventoryError = ''
      return discoverDrSiteInventory(this.buildSiteInventoryParams()).then(result => {
        this.applySiteInventory(result || {})
        this.siteInventoryLoadedKey = loadedKey
      }).catch(error => {
        this.siteInventoryError = this.resolveErrorText(error, 'message.dr.site.inventory.unavailable')
      }).finally(() => {
        this.siteInventoryLoading = false
      })
    },
    buildSiteInventoryKey () {
      return [
        this.siteFormMode,
        this.editingSite?.id || '',
        this.createForm.sitetype || '',
        this.createForm.moldapiurl || '',
        this.createForm.moldapikey || '',
        this.createForm.zoneexternalid || '',
        this.createForm.tlsverify ? 'tls' : 'notls'
      ].join('|')
    },
    buildSiteInventoryParams () {
      const params = {
        sitetype: this.createForm.sitetype,
        includezones: true,
        includevmwaredcs: this.createForm.sitetype === 'MOLD_VMWARE',
        zoneexternalid: this.createForm.zoneexternalid,
        zoneid: this.normalizedNumericValue(this.createForm.zoneid)
      }
      if (this.siteFormMode === 'edit' && this.editingSite?.id && !this.hasAllCredentialValues(['moldapiurl', 'moldapikey', 'moldsecretkey'])) {
        params.id = this.editingSite.id
      } else {
        params.moldapiurl = this.createForm.moldapiurl
        params.moldapikey = this.createForm.moldapikey
        params.moldsecretkey = this.createForm.moldsecretkey
        params.tlsverify = this.createForm.tlsverify
      }
      return this.compactPayload(params)
    },
    applySiteInventory (result) {
      this.siteZoneOptions = this.mergeCurrentOption(
        this.normalizeInventoryOptions(result.zones || []),
        this.createForm.zoneexternalid,
        'label.zoneid',
        this.createForm.zonename
      )
      this.siteVmwareDcOptions = this.mergeCurrentOption(
        this.normalizeInventoryOptions(result.vmwaredatacenters || []),
        this.createForm.vmwaredcexternalid,
        'label.dr.vmware.dc',
        this.createForm.vmwaredcname
      )
    },
    normalizeInventoryOptions (items) {
      return (items || []).map(item => {
        const details = this.inventoryOptionDetails(item)
        const value = item.externalid || details.externalId || item.value || item.id
        const localId = this.normalizedNumericValue(item.localid || details.localId)
        const label = [item.name || item.id || item.value, item.description].filter(Boolean).join(' - ')
        const selectable = !!value && item.selectable !== false
        return {
          value: String(value || `unsupported:${item.id || item.name || Math.random()}`),
          label: label || String(value || ''),
          disabled: !selectable,
          name: item.name || '',
          externalid: item.externalid || details.externalId || '',
          localid: localId
        }
      }).filter(option => option.label)
    },
    mergeCurrentOption (options, value, labelKey, labelValue) {
      const current = this.currentValueOption(value, labelKey, labelValue)
      if (current.length === 0) {
        return options
      }
      return options.some(option => String(option.value) === String(current[0].value))
        ? options
        : current.concat(options)
    },
    currentValueOption (value, labelKey, labelValue) {
      const current = String(value === undefined || value === null ? '' : value).trim()
      if (!current) {
        return []
      }
      return [{ value: current, label: labelValue || `${this.$t(labelKey)} ${current}` }]
    },
    inventoryOptionDetails (item) {
      const details = item?.details || item?.detailsjson || {}
      if (typeof details === 'string') {
        try {
          return JSON.parse(details)
        } catch (e) {
          return {}
        }
      }
      return details || {}
    },
    selectedInventoryName (option) {
      const selected = Array.isArray(option) ? option[0] : option
      if (!selected) {
        return ''
      }
      return selected.name || selected.label || ''
    },
    selectedInventoryLocalId (option) {
      const selected = Array.isArray(option) ? option[0] : option
      return this.normalizedNumericValue(selected?.localid)
    },
    inventoryDisplayValue (name, externalId, localId) {
      return name || externalId || localId || '-'
    },
    normalizedNumericValue (value) {
      const text = String(value === undefined || value === null ? '' : value).trim()
      return /^[0-9]+$/.test(text) ? text : undefined
    },
    resolveErrorText (error, fallbackKey) {
      return error?.response?.data?.errorresponse?.errortext ||
        error?.response?.data?.discoverdrsiteinventoryresponse?.errortext ||
        error?.message ||
        this.$t(fallbackKey)
    },
    submitSite () {
      if (this.siteFormMode === 'edit') {
        this.updateSite()
        return
      }
      this.createSite()
    },
    createSite () {
      const validationMessage = this.siteFormValidationMessage()
      if (validationMessage) {
        notification.warning({
          message: this.$t('label.dr.site.add'),
          description: validationMessage
        })
        return
      }
      this.createLoading = true
      createDrSite(this.buildSitePayload()).then(site => {
        notification.success({
          message: this.$t('label.dr.site.add'),
          description: site.name || site.id || this.$t('label.success')
        })
        this.closeCreateModal()
        this.fetchList()
      }).finally(() => {
        this.createLoading = false
      })
    },
    updateSite () {
      const validationMessage = !this.editingSite?.id ? this.$t('message.dr.required.fields') : this.siteFormValidationMessage()
      if (validationMessage) {
        notification.warning({
          message: this.$t('label.dr.site.edit'),
          description: validationMessage
        })
        return
      }
      this.createLoading = true
      updateDrSite(this.editingSite.id, this.buildSitePayload()).then(site => {
        notification.success({
          message: this.$t('label.dr.site.edit'),
          description: site.name || site.id || this.$t('label.success')
        })
        this.closeCreateModal()
        this.fetchData()
      }).finally(() => {
        this.createLoading = false
      })
    },
    buildSitePayload () {
      const siteType = this.createForm.sitetype
      const payload = {
        name: this.createForm.name,
        description: this.createForm.description,
        sitetype: siteType,
        hypervisortype: this.resolveHypervisorType(siteType)
      }
      if (this.usesMoldCredential) {
        if (this.shouldSubmitCredential(['moldapiurl', 'moldapikey', 'moldsecretkey'])) {
          payload.credentialtype = 'MOLD_API'
          payload.moldapiurl = this.createForm.moldapiurl
          payload.moldapikey = this.createForm.moldapikey
          payload.moldsecretkey = this.createForm.moldsecretkey
          payload.tlsverify = this.createForm.tlsverify
          payload.endpoint = this.createForm.moldapiurl
        }
        payload.zoneid = this.normalizedNumericValue(this.createForm.zoneid)
        payload.zoneexternalid = this.createForm.zoneexternalid
        payload.zonename = this.createForm.zonename
        if (siteType === 'MOLD_VMWARE') {
          payload.vmwaredcid = this.normalizedNumericValue(this.createForm.vmwaredcid)
          payload.vmwaredcexternalid = this.createForm.vmwaredcexternalid
          payload.vmwaredcname = this.createForm.vmwaredcname
        }
      }
      if (this.usesVCenterCredential && this.shouldSubmitCredential(['vcenterurl', 'vcenterusername', 'vcenterpassword'])) {
        payload.credentialtype = 'VCENTER'
        payload.vcenterurl = this.createForm.vcenterurl
        payload.vcenterusername = this.createForm.vcenterusername
        payload.vcenterpassword = this.createForm.vcenterpassword
        payload.tlsverify = this.createForm.tlsverify
        payload.endpoint = this.createForm.vcenterurl
      }
      return this.compactPayload(payload)
    },
    validateSiteForm () {
      return !this.siteFormValidationMessage()
    },
    siteFormValidationMessage () {
      if (!String(this.createForm.name || '').trim()) {
        return this.$t('message.dr.site.validation.name.required')
      }
      if (!this.createForm.sitetype) {
        return this.$t('message.dr.site.validation.type.required')
      }
      if (this.usesMoldCredential) {
        const fields = ['moldapiurl', 'moldapikey', 'moldsecretkey']
        const hasNone = this.hasNoCredentialValues(fields)
        const hasAll = this.hasAllCredentialValues(fields)
        if (this.siteFormMode === 'create' && !hasAll) {
          return this.$t('message.dr.site.validation.mold.credentials.required')
        }
        if (this.siteFormMode === 'edit' && !hasNone && !hasAll) {
          return this.$t('message.dr.site.validation.mold.credentials.partial')
        }
        if (hasAll && !this.isHttpUrl(this.createForm.moldapiurl)) {
          return this.$t('message.dr.site.validation.url')
        }
        return ''
      }
      if (this.usesVCenterCredential) {
        const fields = ['vcenterurl', 'vcenterusername', 'vcenterpassword']
        const hasNone = this.hasNoCredentialValues(fields)
        const hasAll = this.hasAllCredentialValues(fields)
        if (this.siteFormMode === 'create' && !hasAll) {
          return this.$t('message.dr.site.validation.vcenter.credentials.required')
        }
        if (this.siteFormMode === 'edit' && !hasNone && !hasAll) {
          return this.$t('message.dr.site.validation.vcenter.credentials.partial')
        }
        if (hasAll && !this.isHttpUrl(this.createForm.vcenterurl)) {
          return this.$t('message.dr.site.validation.url')
        }
        return ''
      }
      return ''
    },
    resolveHypervisorType (siteType) {
      return siteType === 'MOLD_KVM' ? 'KVM' : 'VMWARE'
    },
    hasAllCredentialValues (fields) {
      return fields.every(field => String(this.createForm[field] || '').trim() !== '')
    },
    hasNoCredentialValues (fields) {
      return fields.every(field => String(this.createForm[field] || '').trim() === '')
    },
    shouldSubmitCredential (fields) {
      return this.siteFormMode === 'create' || this.hasAllCredentialValues(fields)
    },
    isHttpUrl (value) {
      try {
        const url = new URL(String(value || '').trim())
        return ['http:', 'https:'].includes(url.protocol)
      } catch (e) {
        return false
      }
    },
    execSiteAction (action, site) {
      const target = site || action.resource || this.contextMenuSite || this.detailSite
      if (!target?.id) {
        return
      }
      if (action.api === 'checkDrSite') {
        this.checkSite(target)
      } else if (action.api === 'updateDrSite') {
        this.openEditModal(target)
      } else if (action.api === 'deleteDrSite') {
        this.confirmDeleteSite(target)
      }
    },
    confirmDeleteSite (site) {
      if (!site?.id) {
        return
      }
      this.$confirm({
        title: this.$t('label.dr.site.delete'),
        content: this.$t('message.dr.confirm.delete.site'),
        okType: 'danger',
        okText: this.$t('label.yes'),
        cancelText: this.$t('label.no'),
        onOk: () => {
          return deleteDrSite(site.id).then(result => this.waitForDeleteJob(result?.jobid, {
            title: this.$t('label.dr.site.delete'),
            description: site.name || site.id || ''
          })).then(() => {
            notification.success({
              message: this.$t('label.dr.site.delete'),
              description: site.name || site.id || this.$t('label.success')
            })
            if (this.detailId) {
              this.$router.push({ path: '/drsite' }).catch(() => {})
            } else {
              this.fetchList()
            }
          })
        }
      })
    },
    waitForDeleteJob (jobId, options = {}) {
      if (!jobId) {
        return Promise.resolve()
      }
      return new Promise((resolve, reject) => {
        this.$pollJob({
          jobId,
          title: options.title || this.$t('label.dr.site.delete'),
          description: options.description || '',
          showSuccessMessage: false,
          showLoading: true,
          loadingMessage: `${this.$t('label.loading')}...`,
          errorMessage: options.title || this.$t('label.error'),
          successMethod: resolve,
          errorMethod: reject,
          catchMethod: reject,
          action: { isFetchData: false }
        })
      })
    },
    openSiteContextMenu (event, site) {
      if (!site?.id) {
        return
      }
      event.preventDefault()
      this.contextMenuSite = site
      this.contextMenuPosition = { x: event.clientX, y: event.clientY }
      this.contextMenuVisible = true
    },
    openListContextMenu (event) {
      const rowElement = event.target.closest('tr.ant-table-row')
      if (!rowElement) {
        this.closeContextMenu()
        return
      }
      const rowKey = rowElement.getAttribute('data-row-key')
      const site = this.pagedSites.find(item => String(item.id) === String(rowKey))
      if (site) {
        this.openSiteContextMenu(event, site)
      }
    },
    closeContextMenu () {
      this.contextMenuVisible = false
      this.contextMenuSite = {}
    },
    compactPayload (payload) {
      return Object.keys(payload || {}).reduce((result, key) => {
        const value = payload[key]
        if (value !== '' && value !== undefined && value !== null) {
          result[key] = value
        }
        return result
      }, {})
    },
    onSearch (opts) {
      if (opts && Object.prototype.hasOwnProperty.call(opts, 'searchQuery')) {
        this.searchQuery = opts.searchQuery || ''
        this.searchParams = {}
      } else {
        this.searchParams = opts || {}
      }
      this.resetPagination()
    },
    changeFilter (filter) {
      this.filters.health = filter === 'all' ? undefined : filter
      this.resetPagination()
    },
    resetPagination () {
      this.page = 1
    },
    removeFilter (filter) {
      if (filter.key === 'healthstate') {
        this.filters.health = undefined
      } else {
        const searchParams = Object.assign({}, this.searchParams)
        delete searchParams[filter.key]
        this.searchParams = searchParams
      }
      this.resetPagination()
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize || this.pageSize
    },
    changePageSize (current, size) {
      this.page = 1
      this.pageSize = size
    },
    updateSelectedColumns (name) {
      if (!name) {
        return
      }
      if (this.selectedColumns.includes(name)) {
        this.selectedColumns = this.selectedColumns.filter(column => column !== name)
      } else {
        this.selectedColumns.push(name)
      }
    },
    onRowSelectionChange (selectedRowKeys) {
      this.selectedRowKeys = selectedRowKeys
    },
    paginationTotal (total) {
      const start = total === 0 ? 0 : Math.min(total, 1 + ((this.normalizedPage - 1) * this.pageSize))
      const end = Math.min(this.normalizedPage * this.pageSize, total)
      if (this.$localStorage.get('LOCALE') === 'ko_KR') {
        return `${this.$t('label.total')} ${total} ${this.$t('label.items')} ${this.$t('label.of')} ${start}-${end} ${this.$t('label.showing')}`
      }
      return `${this.$t('label.showing')} ${start}-${end} ${this.$t('label.of')} ${total} ${this.$t('label.items')}`
    },
    normalizeText (value) {
      return String(value || '').trim().toLowerCase()
    },
    matchesKeyword (record, keyword, fields) {
      return fields.some(field => this.normalizeText(record[field]).includes(keyword))
    },
    matchesSearchParams (record, params, fields) {
      return fields.every(field => {
        const value = params[field]
        if (value === '' || value === undefined || value === null) {
          return true
        }
        return this.normalizeText(record[field]).includes(this.normalizeText(value))
      })
    },
    sortBy (field) {
      return (a, b) => this.normalizeText(a[field]).localeCompare(this.normalizeText(b[field]))
    }
  }
}
</script>

<style lang="less">
.cross-dr-code {
  max-height: 260px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border-radius: 6px;
  background: var(--cross-dr-code-bg, #f6f8fa);
  color: var(--cross-dr-text, rgba(0, 0, 0, 0.85));
  white-space: pre-wrap;
  word-break: break-word;
}

body.dark-mode .cross-dr-code {
  --cross-dr-code-bg: rgba(0, 0, 0, 0.24);
  --cross-dr-text: rgba(255, 255, 255, 0.86);
}
</style>
