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
  <div class="snapshot-zones" data-testid="snapshot-zones">
    <div class="detail-tab-toolbar">
      <a-button v-if="deleteApi in $store.getters.apis" danger :disabled="actionsBlocked || !selectedRowKeys.length" @click="bulkActionConfirmation"><template #icon><delete-outlined /></template>{{ $t('label.snapshot.copy.delete.zones') }}</a-button>
      <a-button ref="zoneRefresh" :loading="fetchLoading || listRefreshing > 0" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-dropdown :trigger="['click']"><a-button :aria-label="$t('label.select.columns')"><template #icon><setting-outlined /></template>{{ $t('label.select.columns') }}</a-button>
        <template #overlay><a-menu><a-menu-item v-for="key in columnKeys" :key="key" @click="toggleColumn(key)"><a-checkbox :checked="visibleColumns.includes(key)" /> {{ $t(columnLabel(key)) }}</a-menu-item></a-menu></template>
      </a-dropdown>
      <a-input-search v-model:value="keyword" class="detail-tab-search" :placeholder="$t('label.snapshot.copy.search.page')" :aria-label="$t('label.snapshot.copy.search.page')" allow-clear />
    </div>
    <p class="snapshot-copy-caption">{{ $t('message.snapshot.copy.units') }}</p>
    <a-alert v-if="listRefreshFailed" class="detail-tab-notice" type="error" show-icon :message="$t(copyReadDenied ? 'message.snapshot.copy.denied' : loadedCopyScope ? 'message.list.refresh.stale' : 'error.fetching.data')">
      <template #description><p>{{ copyReadError.startsWith('message.') ? $t(copyReadError) : copyReadError }}</p><a-button size="small" :disabled="fetchLoading || listRefreshing > 0" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <p class="snapshot-copy-caption">{{ $t('message.snapshot.copy.page', [visibleZoneGroups.length, dataSource.length]) }}</p>
    <a-table
      v-if="dataSource.length || (!fetchLoading && !listRefreshFailed)"
      class="snapshot-zone-table"
      size="small"
      :loading="loading || fetchLoading"
      :columns="columns"
      :data-source="visibleZoneGroups"
      :pagination="false"
      :locale="{ emptyText: $t(keyword ? 'message.snapshot.copy.search.empty' : 'message.snapshot.copy.empty') }"
      :scroll="{ x: 680 }"
      :row-selection="deleteApi in $store.getters.apis ? { selectedRowKeys, onChange: onSelectChange, getCheckboxProps: record => ({ disabled: !record.zoneid || actionsBlocked }) } : undefined"
      :row-key="record => record.key"
      :expanded-row-keys="expandedZoneKeys"
      @expand="onExpandZone">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'zonename'">
          <router-link v-if="record.zoneid && ('listZones' in $store.getters.apis)" :to="'/zone/' + encodeURIComponent(record.zoneid)">
            <resource-icon v-if="fetchZoneIcon(record.zoneid)" :image="fetchZoneIcon(record.zoneid)" size="1x" />
            <global-outlined v-else /> {{ record.zonename || record.zoneid }}
          </router-link>
          <span v-else>{{ record.zonename || $t('label.snapshot.copy.unknown') }}</span>
        </template>
        <template v-else-if="column.key === 'state'"><Status :text="record.state || 'Unknown'" display-text /></template>
        <template v-else-if="column.key === 'interval'">{{ interval(record) }}</template>
        <template v-else-if="column.key === 'copies'">{{ record.copies.length }}</template>
        <template v-else-if="column.key === 'actions'">
          <tooltip-button v-if="record.zoneid && resource.state === 'BackedUp' && (copyApi in $store.getters.apis)" :disabled="actionsBlocked" :tooltip="$t('label.snapshot.copy.from.zone')" icon="copy-outlined" @onClick="showCopySnapshot(record)" />
          <tooltip-button v-if="record.zoneid && resource.state === 'BackedUp' && (deleteApi in $store.getters.apis)" :disabled="actionsBlocked" :tooltip="$t('label.snapshot.copy.delete.zone')" icon="delete-outlined" danger @onClick="onShowDeleteModal(record)" />
        </template>
      </template>
      <template #expandedRowRender="{ record: zone }">
        <a-table class="snapshot-copy-table" size="small" :columns="copyColumns" :data-source="zone.copies" :pagination="false" :row-key="record => record.copy.id" :scroll="{ x: 760 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'role'">{{ copyRole(record) }}</template>
            <template v-else-if="column.key === 'datastore'">
              <router-link v-if="storePath(record)" :to="storePath(record)">{{ record.datastorename || record.datastoreid }}</router-link>
              <span v-else>{{ record.datastorename || $t(record.copy.datastoreknown ? 'label.snapshot.copy.restricted.store' : 'label.snapshot.copy.unknown.store') }}</span>
            </template>
            <template v-else-if="column.key === 'state'"><Status :text="record.copy.state || 'Unknown'" :display-text="true" /></template>
            <template v-else-if="column.key === 'availability'">{{ $t('label.snapshot.copy.' + record.copy.availability.toLowerCase()) }}</template>
            <template v-else-if="column.key === 'size'">{{ copySize(record) }}</template>
            <template v-else-if="column.key === 'progress'">
              <span v-if="Number.isInteger(record.copy.downloadpercent)">{{ record.copy.downloadpercent }}%</span>
              <span v-else>{{ $t(downloadLabel(record)) }}</span>
              <p v-if="record.copy.error" class="snapshot-copy-error" role="status">{{ record.copy.error }}</p>
            </template>
          </template>
        </a-table>
      </template>
    </a-table>
    <a-pagination
      v-if="loadedCopyScope"
      class="detail-tab-pagination"
      size="small"
      :current="page"
      :page-size="pageSize"
      :total="itemCount"
      :show-total="(total, range) => $t('message.snapshot.copy.total.range', [total, range[0], range[1]])"
      :page-size-options="['10', '20', '40', '80', '100']"
      @change="handleChangePage"
      @showSizeChange="handleChangePageSize"
      showSizeChanger
showQuickJumper />

    <SnapshotCopyDialog v-if="copyRecord" :resource="resource" :source-zone="copyRecord" @close-action="onCloseModal" @refresh-data="fetchData" />

    <StorageSnapshotActionDialog v-if="safetyDeleteAction" :key="deleteGeneration" :resource="resource" :current-action="safetyDeleteAction" @refresh-data="fetchData" @close-action="closeDeleteDialog" />
  </div>
</template>

<script>
import StorageSnapshotActionDialog from '@/views/storage/StorageSnapshotActionDialog.vue'
import SnapshotCopyDialog from '@/views/storage/SnapshotCopyDialog.vue'
import { snapshotSecurityScope } from '@/utils/storageSnapshotContext'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { readSnapshotCopyPage, snapshotCopyZones } from '@/utils/snapshotCopies'

import { getAPI } from '@/api'
import { detailTabContext, detailTabDenied, detailTabError, translatedSnapshotDownload } from '@/utils/detailTabData'
import { snapshotListInterval, snapshotListSize } from '@/utils/storageSnapshotPresentation'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipButton from '@/components/widgets/TooltipButton'
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => vm.copySecurityScope, active: vm => !vm.copyRecord && !vm.safetyDeleteAction })],
  name: 'SnapshotZones',
  inject: { resourceActionReady: { default: () => () => true } },
  components: {
    TooltipButton,
    ResourceIcon,
    StorageSnapshotActionDialog,
    SnapshotCopyDialog,
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
      keyword: '',
      columnKeys: ['zonename', 'state', 'interval', 'copies'],
      visibleColumns: ['zonename', 'state', 'interval', 'copies'],
      safetyDeleteAction: null,
      deleteGeneration: 0,
      copySequence: 0,
      copyRecord: null,
      expandedZoneKeys: [],
      loadedCopyScope: '',
      copyReadDenied: false,
      copyReadError: '',
      zoneIcons: {},
      dataSource: [],
      page: 1,
      pageSize: 10,
      itemCount: 0,
      fetchLoading: false,
      showCopyActionForm: false,
      currentRecord: {},
      zones: [],
      zoneLoading: false,
      copyLoading: false,
      deleteLoading: false,
      showDeleteSnapshot: false,
      forcedDelete: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['Status', 'Ready'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.delete.snapshots'),
        confirmMessage: this.$t('label.confirm.delete.snapshot.zones')
      },
      modalWidth: '30vw',
      showTable: false
    }
  },
  beforeCreate () {
    this.deleteApi = 'deleteSnapshot'
    this.copyApi = 'copySnapshot'
  },
  created () {
    this.fetchData()
  },
  watch: {
    copySecurityScope () { this.resetCopyRead() },
    keyword: { flush: 'sync', handler () { this.resetSelection() } },
    loading (newData, oldData) {
      if (!newData && !this.showGroupActionModal) {
        this.fetchData()
      }
    }
  },
  computed: {
    copySecurityScope () { return detailTabContext(this, ['listSnapshots', 'copySnapshot', 'deleteSnapshot', 'archiveSnapshot', 'listZones', 'listStoragePools', 'listImageStores']) },
    actionsBlocked () { return this.resource.state !== 'BackedUp' || this.listRefreshFailed || this.fetchLoading || this.listRefreshing > 0 || !this.loadedCopyScope || !this.resourceActionReady() },
    visibleZoneGroups () { const key = this.keyword.trim().toLocaleLowerCase(); return this.zoneGroups.filter(row => !key || [row.zonename, row.zoneid, ...row.copies.map(copy => copy.datastorename)].some(value => value?.toLocaleLowerCase().includes(key))) },
    canReadCopies () { return 'listSnapshots' in this.$store.getters.apis },
    zoneGroups () { return snapshotCopyZones(this.dataSource) },
    columns () {
      const columns = this.columnKeys.filter(key => this.visibleColumns.includes(key)).map(key => ({ key, title: this.$t(this.columnLabel(key)) }))
      if ((this.copyApi in this.$store.getters.apis) || (this.deleteApi in this.$store.getters.apis)) columns.push({ key: 'actions', title: this.$t('label.snapshot.copy.zone.actions'), width: 110 })
      return columns
    },
    copyColumns () {
      return ['role', 'datastore', 'state', 'availability', 'size', 'progress'].map(key => ({ key, title: this.$t('label.snapshot.copy.column.' + key) }))
    }
  },
  methods: {
    async fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      const scope = JSON.stringify([this.resource.id, this.copySecurityScope, this.page, this.pageSize])
      if (scope !== this.loadedCopyScope) {
        this.dataSource = []; this.itemCount = 0; this.resetSelection(); this.expandedZoneKeys = []
      }
      if (!this.canReadCopies) { this.fetchLoading = false; return }
      const params = { id: this.resource.id, showunique: false, listall: true, page: this.page, pagesize: this.pageSize }
      const project = this.resource.projectid || this.$store.getters.project?.id
      if (project && project !== '0') params.projectid = project
      this.fetchLoading = !listRequest.loaded
      if (!listRequest.loaded) this.fetchZoneData()
      try {
        const json = await getAPI('listSnapshots', params, { preserveOnFailure: true, timeout: 20000 })
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        const result = readSnapshotCopyPage(json, this.resource.id, this.pageSize, this.page)
        if (result.rows.length !== Math.min(this.pageSize, Math.max(0, result.count - (this.page - 1) * this.pageSize))) throw new Error('message.snapshot.copy.incomplete')
        const last = Math.max(1, Math.ceil(result.count / this.pageSize))
        if (this.page > last) {
          this.page = last; this.resetSelection()
          return this.fetchData()
        }
        const expanded = new Set(this.expandedZoneKeys)
        const known = new Set(this.zoneGroups.map(row => row.key))
        this.dataSource = result.rows; this.itemCount = result.count; this.loadedCopyScope = scope
        this.expandedZoneKeys = this.zoneGroups.filter(row => expanded.has(row.key) || !known.has(row.key)).map(row => row.key)
        this.setSelection(this.selectedRowKeys.filter(key => this.zoneGroups.some(row => row.key === key)))
        this.listRefreshFailed = false; this.copyReadDenied = false; this.copyReadError = ''
      } catch (error) {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true; this.listRefreshFailed = true
        this.copyReadDenied = detailTabDenied(error); this.copyReadError = detailTabError(error)
        if (this.copyReadDenied) {
          this.dataSource = []; this.itemCount = 0; this.loadedCopyScope = ''; this.expandedZoneKeys = []; this.resetSelection()
        }
        if (!listRequest.loaded) this.$notifyError(error)
      } finally {
        if (this.isListRequestCurrent('fetchData', listRequest)) this.fetchLoading = false
      }
    },
    resetCopyRead () {
      this.onCloseModal(); this.safetyDeleteAction = null; this.fetchLoading = false; this.copyReadDenied = false; this.copyReadError = ''; this.listRefreshFailed = false
      this.dataSource = []; this.itemCount = 0; this.loadedCopyScope = ''; this.expandedZoneKeys = []; this.page = 1
      this.keyword = ''; this.resetSelection(); this.fetchData()
    },
    onExpandZone (expanded, row) {
      this.expandedZoneKeys = expanded ? [...new Set([...this.expandedZoneKeys, row.key])] : this.expandedZoneKeys.filter(key => key !== row.key)
    },
    fetchZoneIcon (zoneid) { return this.zoneIcons[zoneid] || '' },
    copyRole (row) { return this.$t('label.snapshot.copy.' + (row.copy.role === 'Primary' ? 'primary' : row.copy.role === 'Image' ? 'secondary' : 'unknown')) },
    copySize (row) { return snapshotListSize(row.copy.virtualsize) ?? this.$t('label.snapshot.copy.unknown') },
    interval (row) { return this.$t(snapshotListInterval(row.intervaltype || this.resource.intervaltype)) },
    downloadLabel: translatedSnapshotDownload,
    columnLabel (key) { return { zonename: 'label.zonename', state: 'label.state', interval: 'label.intervaltype', copies: 'label.snapshot.copy.on.page' }[key] },
    toggleColumn (key) { if (!this.columnKeys.includes(key)) return; if (this.visibleColumns.includes(key)) { if (this.visibleColumns.length > 1) this.visibleColumns = this.visibleColumns.filter(value => value !== key) } else this.visibleColumns.push(key) },
    storePath (row) {
      if (!row.datastoreid) return ''
      if (row.copy.role === 'Primary' && ('listStoragePools' in this.$store.getters.apis)) return '/storagepool/' + encodeURIComponent(row.datastoreid)
      if (row.copy.role === 'Image' && ('listImageStores' in this.$store.getters.apis)) return '/imagestore/' + encodeURIComponent(row.datastoreid)
      return ''
    },
    handleChangePage (page, pageSize) {
      this.resetSelection()
      this.page = pageSize !== this.pageSize ? 1 : page
      this.pageSize = pageSize
      this.fetchData()
    },
    handleChangePageSize (currentPage, pageSize) {
      this.resetSelection()
      this.page = 1
      this.pageSize = pageSize
      this.fetchData()
    },
    isActionPermitted () {
      return this.resource.state && this.resource.state === 'BackedUp'
    },
    setSelection (selection) {
      this.selectedItems = this.visibleZoneGroups.filter(row => row.zoneid && selection.includes(row.key))
      this.selectedRowKeys = this.selectedItems.map(row => row.key)
      this.$emit('selection-change', this.selectedItems.map(row => row.zoneid))
    },
    resetSelection () {
      this.setSelection([])
    },
    onSelectChange (selectedRowKeys, selectedRows) {
      this.setSelection(selectedRowKeys)
    },
    bulkActionConfirmation () {
      if (!this.selectedItems.length || this.actionsBlocked) return
      this.showConfirmationAction = true
      this.selectedColumns = this.columns.filter(column => {
        return !this.filterColumns.includes(column.title)
      })
      this.selectedItems = this.selectedItems.map(v => ({ ...v, status: 'InProgress' }))
      this.onShowDeleteModal(this.selectedItems[0], true)
    },
    fetchZoneData () {
      const origin = JSON.stringify([this.resource.id, snapshotSecurityScope(this.$store)]); const sequence = this.copySequence
      this.zoneLoading = true
      if (!('listZones' in this.$store.getters.apis)) { this.zoneLoading = false; return }
      getAPI('listZones', { showicon: true }, { preserveOnFailure: true }).then(json => {
        if (origin !== JSON.stringify([this.resource.id, snapshotSecurityScope(this.$store)]) || sequence !== this.copySequence) return
        const zones = json.listzonesresponse.zone || []
        this.zoneIcons = Object.fromEntries(zones.filter(zone => zone.icon?.base64image).map(zone => [zone.id, zone.icon.base64image]))
      }).catch(error => { if (origin === JSON.stringify([this.resource.id, snapshotSecurityScope(this.$store)]) && sequence === this.copySequence) this.$notifyError(error) }).finally(() => {
        if (origin !== JSON.stringify([this.resource.id, snapshotSecurityScope(this.$store)]) || sequence !== this.copySequence) return
        this.zoneLoading = false
      })
    },
    showCopySnapshot (record) {
      if (!record?.zoneid || this.actionsBlocked) return
      this.captureDialogFocus()
      this.copyRecord = Object.freeze({ zoneid: record.zoneid, zonename: record.zonename })
    },
    onShowDeleteModal (record, bulk = false) {
      if (!record?.zoneid || this.actionsBlocked) return
      const targets = bulk ? this.selectedItems : [record]
      this.captureDialogFocus()
      this.deleteGeneration++
      this.safetyDeleteAction = { api: 'deleteSnapshot', label: 'label.action.delete.snapshot', message: 'message.action.delete.snapshot', zoneScope: true, storageSnapshotTargets: targets.map(row => Object.freeze({ ...this.resource, ...row })) }
    },
    captureDialogFocus () { this.snapshotDialogTrigger = document.activeElement; this.snapshotDialogScope = this.copySecurityScope },
    restoreDialogFocus () {
      const trigger = this.snapshotDialogTrigger; const scope = this.snapshotDialogScope
      this.$nextTick(() => {
        if (scope !== this.copySecurityScope || this.copyRecord || this.safetyDeleteAction) return
        const target = trigger?.isConnected && !trigger.disabled ? trigger : this.$refs.zoneRefresh?.$el
        if (target) target.focus({ preventScroll: true })
      })
    },
    closeDeleteDialog () { this.safetyDeleteAction = null; this.resetSelection(); this.fetchData(); this.restoreDialogFocus() },
    onCloseModal () {
      this.restoreDialogFocus()
      this.copySequence++; this.copyRecord = null
      this.showConfirmationAction = false; this.selectedRowKeys = []
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/style/components/view/DetailTab.scss';
.snapshot-copy-caption { color: var(--ui-text-secondary); margin-bottom: 16px; }
.snapshot-copy-error { color: var(--ui-warning-text); margin: 6px 0 0; overflow-wrap: anywhere; }
.snapshot-copy-form :deep(.ant-form-item) { margin-bottom: 24px; }
.snapshot-copy-table { overflow-wrap: anywhere; }

</style>
