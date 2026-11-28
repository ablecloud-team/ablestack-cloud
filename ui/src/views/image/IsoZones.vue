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
  <div class="template-zones iso-zones">
    <div class="template-tab-toolbar">
      <a-button v-if="canSelectIsoRows" danger :disabled="operationActive || listRefreshFailed || !selectedRowKeys.length" @click="bulkActionConfirmation"><template #icon><delete-outlined /></template>{{ $t('label.action.bulk.delete.isos') }} <span class="zone-selection-count" aria-live="polite">({{ selectedRowKeys.length }})</span></a-button>
      <a-button :loading="fetchLoading" :disabled="operationActive" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="searchText" :aria-label="$t('label.iso.zone.search')" :placeholder="$t('label.search')" :disabled="operationActive" allow-clear @search="searchZones" />
    </div>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <detail-tab-table
size="small"
:loading="loading || fetchLoading"
:columns="columns"
:data-source="visibleRows"
:pagination="false"
:scroll="{x:780}"
:row-selection="canSelectIsoRows ? {selectedRowKeys,onChange:onSelectChange,getCheckboxProps:record=>({disabled:operationActive || !isoAction('delete',record)})} : null"
:row-key="record=>record.zoneid || record.id"
:expand-row-by-click="true"
:row-expandable="record=>(record.downloaddetails || []).length>0">
      <template #bodyCell="{column,record}">
        <a-tooltip v-if="column.key==='zonename'" :title="record.zonename || '—'" :trigger="['hover','focus']"><span tabindex="0" class="template-cell-value"><resource-icon v-if="record.zoneicon?.base64image" :image="record.zoneicon.base64image" size="1x" /><global-outlined v-else /> {{ record.zonename || '—' }}</span></a-tooltip>
        <template-availability v-else-if="column.key==='status'" kind="iso" :record="record" compact single-line :show-scope="false" />
        <span v-else-if="column.key==='isready'">{{ $t(isoBoolean(record.isready)===true?'label.yes':isoBoolean(record.isready)===false?'label.no':'label.unknown') }}</span>
        <a-tooltip v-else-if="column.key==='created'" :title="record.created ? $toLocaleDate(record.created) : '—'"><span class="template-cell-value">{{ record.created ? $toLocaleDate(record.created) : '—' }}</span></a-tooltip>
        <div v-else-if="column.key==='actions'" class="template-row-actions" @click.stop>
          <tooltip-button v-if="'deployVirtualMachine' in $store.getters.apis" :disabled="operationActive || !isoAction('deploy',record)" :tooltip="isoBoolean(record.bootable)===true?$t('label.vm.add'):$t('message.iso.bootable.required')" :aria-label="$t('label.vm.add')" icon="rocket-outlined" @onClick="onAddInstance(record)" />
          <tooltip-button v-if="'attachIso' in $store.getters.apis" :disabled="operationActive || !isoAction('attach',record)" :tooltip="$t('label.iso.attach.vm')" :aria-label="$t('label.iso.attach.vm')" icon="link-outlined" @onClick="attachRecord=record" />
          <tooltip-button v-if="'copyIso' in $store.getters.apis && isActionsOnIsoPermitted" :disabled="operationActive || !isoAction('copy',record)" :tooltip="$t('label.action.copy.iso')" icon="copy-outlined" @onClick="showCopyIso(record)" />
          <tooltip-button v-if="'deleteIso' in $store.getters.apis && isActionsOnIsoPermitted" :disabled="operationActive || !isoAction('delete',record)" :tooltip="$t('label.action.delete.iso')" :danger="true" icon="delete-outlined" @onClick="deleteIso(record)" />
        </div>
      </template>
      <template #expandedRowRender="{record}">
        <section v-for="section in storeSections.filter(s=>storeRows(record,s.role).length)" :key="section.role" class="template-zone-storage">
          <h5 class="template-tab-section-title">{{ $t(section.label) }}</h5>
          <a-table size="small" :columns="storeColumns" :data-source="storeRows(record,section.role)" :pagination="false" :scroll="{x:550}" :row-key="(row,index)=>row.datastoreId || (record.zoneid+':'+section.role+':'+index)">
            <template #bodyCell="{column,record:store}">
              <a-tooltip v-if="column.key==='datastore'" :title="store.datastore || '—'"><router-link v-if="store.datastoreId && section.api in $store.getters.apis" class="template-cell-value" :to="section.path+'/'+encodeURIComponent(store.datastoreId)">{{ store.datastore || '—' }}</router-link><span v-else class="template-cell-value">{{ store.datastore || '—' }}</span></a-tooltip>
              <template v-else-if="column.key==='downloadPercent'"><a-progress :format="percent => percent + '%'" v-if="storePresentation(store).percent!==null" :percent="storePresentation(store).percent" size="small" :status="storePresentation(store).badge==='error'?'exception':undefined" /><span v-else>—</span></template>
              <a-tooltip v-else-if="column.key==='downloadState'" :title="store.downloadState" :trigger="['hover','focus']"><span tabindex="0"><a-badge :status="storePresentation(store).badge" :text="$t(storePresentation(store).label)" /></span></a-tooltip>
            </template>
          </a-table>
        </section>
      </template>
    </detail-tab-table>
    <div class="template-tab-pagination"><span>{{ $t('label.total') }} {{ filteredRows.length }} {{ $t('label.items') }}</span><detail-tab-pagination
size="small"
:current="page"
:page-size="pageSize"
:total="filteredRows.length"
:disabled="operationActive"
:page-size-options="['10','20','40','80','100']"
show-size-changer
@change="handleChangePage"
@showSizeChange="handleChangePageSize"><template #buildOptionText="props"><span>{{ props.value }} / {{ $t('label.page') }}</span></template></detail-tab-pagination></div>
    <iso-vm-attach v-if="attachRecord" :resource="{...resource,...attachRecord}" @close-action="attachRecord=null" />
    <iso-zone-operations
ref="zoneOps"
:resource="resource"
:context="isoScope"
:permitted="isActionsOnIsoPermitted"
:allowed="isoAction"
@retained="operationRetained"
@refresh="fetchData"
@refresh-parent="$emit('refresh-parent')"
@closed="operationClosed" />
  </div>
</template>
<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { getAPI } from '@/api'
import { isoBoolean, isoOwner, canIsoAction, isoListResponse, readIsoResource } from '@/utils/isoModel'
import { isoZoneContext, isoStorePresentation, readOwnedIsoZones } from '@/utils/isoZoneOperations'
import TemplateAvailability from '@/components/widgets/TemplateAvailability'
import TooltipButton from '@/components/widgets/TooltipButton'
import ResourceIcon from '@/components/view/ResourceIcon'
import IsoVmAttach from '@/views/image/IsoVmAttach'
import IsoZoneOperations from '@/views/image/IsoZoneOperations'
export default {
  name: 'IsoZones',
  mixins: [listRefreshMixin(['fetchData'], { scopeKey: vm => vm.isoScope })],
  components: { TemplateAvailability, TooltipButton, ResourceIcon, IsoZoneOperations, IsoVmAttach },
  props: { resource: { type: Object, required: true }, loading: { type: Boolean, default: false } },
  emits: ['update-zones', 'selection-change', 'operation-retained', 'refresh-parent'],
  data () { return { dataSource: [], itemCount: 0, isoDataContext: '', page: 1, pageSize: 10, fetchLoading: false, fetchLoadingRequest: null, zones: [], zoneLoading: false, selectedItems: [], selectedRowKeys: [], osCategoryId: null, operationActive: false, attachRecord: null, searchText: '', keyword: '' } },
  created () { this.fetchData() },
  computed: {
    isoScope () { return isoZoneContext(this.resource, this.$store.getters, this.$store.state?.user?.token) },
    filteredRows () { const text = this.keyword.toLowerCase(); return this.dataSource.filter(r => !text || String(r.zonename || '').toLowerCase().includes(text)) },
    visibleRows () { return this.filteredRows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize) },
    canSelectIsoRows () { return this.dataSource.some(row => this.isoAction('delete', row)) },
    isActionsOnIsoPermitted () { return isoOwner(this.resource, this.$store.getters) },
    columns () { return [{ key: 'zonename', dataIndex: 'zonename', title: this.$t('label.zonename'), width: 180 }, { key: 'created', dataIndex: 'created', title: this.$t('label.created'), width: 168 }, { key: 'status', dataIndex: 'status', title: this.$t('label.status'), width: 140 }, { key: 'isready', dataIndex: 'isready', title: this.$t('label.isready'), width: 64 }, { key: 'actions', dataIndex: 'actions', title: '', width: 160 }] },
    storeSections () { return [{ role: 'Primary', label: 'label.primary.storage', path: '/storagepool', api: 'listStoragePools' }, { role: 'Image', label: 'label.secondary.storage', path: '/imagestore', api: 'listImageStores' }, { role: 'Unknown', label: 'label.storage', path: '', api: '' }] },
    storeColumns () { return ['datastore', 'downloadPercent', 'downloadState'].map((key, i) => ({ key, dataIndex: key, title: this.$t(['label.storage', 'label.download.percent', 'label.download.state'][i]), width: i === 0 ? 210 : 160 })) }
  },
  watch: {
    isoScope () { this.attachRecord = null; this.operationActive = false; this.$emit('operation-retained', { active: false, context: this.isoDataContext, busy: false }); this.resetSelection(); this.dataSource = []; this.itemCount = 0; this.isoDataContext = ''; this.keyword = ''; this.searchText = ''; this.page = 1; this.fetchData() },
    loading (value) { if (!value) this.fetchData() }
  },
  methods: {
    isoBoolean,
    storePresentation: isoStorePresentation,
    storeRows (record, role) { return (record.downloaddetails || []).filter(r => role === 'Unknown' ? !['Primary', 'Image'].includes(r.datastoreRole) : r.datastoreRole === role) },
    searchZones () { this.keyword = this.searchText.trim(); this.page = 1; this.resetSelection() },
    isoAction (action, record) { return !!record.zoneid && !this.listRefreshFailed && this.isoDataContext === this.isoScope && record.id === this.resource.id && canIsoAction(action, { ...this.resource, ...record, account: this.resource.account, domainid: this.resource.domainid, projectid: this.resource.projectid, templatetype: this.resource.templatetype }, this.$store.getters) },
    requireIsoAction (action, record) { if (record && this.isoAction(action, record)) return true; this.$message.error(this.$t('message.iso.action.unavailable')); return false },
    async readAllRows (params, request, scope) {
      if (isoOwner(this.resource, this.$store.getters)) {
        const rows = await readOwnedIsoZones(getAPI, this.resource, this.$store.getters, () => this.isListRequestCurrent('fetchData', request) && scope === this.isoScope)
        return rows === null ? null : { listisosresponse: { iso: rows, count: rows.length } }
      }
      const all = []; let total
      for (let page = 1; page <= 100; page++) {
        const json = await readIsoResource(getAPI, { ...params, page }, this.$store.getters, () => this.isListRequestCurrent('fetchData', request) && scope === this.isoScope)
        if (!this.isListRequestCurrent('fetchData', request) || scope !== this.isoScope) return null
        const d = isoListResponse(json); if (total !== undefined && total !== d.count) throw new Error('ISO Zone list changed')
        total = d.count; all.push(...d.rows)
        if (all.length >= total) return { listisosresponse: { count: total, iso: all } }
        if (!d.rows.length) break
      }
      throw new Error('Incomplete ISO Zone list')
    },
    async fetchData () {
      if (this.operationActive) return
      if (!this.resource.id) { this.dataSource = []; this.itemCount = 0; this.fetchLoading = false; return Promise.resolve() }
      const listRequest = this.listRequestToken('fetchData')
      const scope = this.isoScope
      const params = {}
      params.id = this.resource.id
      params.listall = true
      params.page = 1
      params.pagesize = 500

      this.fetchLoadingRequest = listRequest
      this.fetchLoading = !listRequest.loaded
      if (!listRequest.loaded) {
        this.fetchZoneData()
        this.fetchOsCategoryId()
      }
      return this.readAllRows(params, listRequest, scope).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest) || scope !== this.isoScope) return
        const result = isoListResponse(json)
        this.isoDataContext = scope
        if (result.rows.some(row => row.id !== this.resource.id)) throw new Error('invalid ISO record')
        this.dataSource = result.rows
        this.itemCount = result.count
        this.page = Math.min(this.page, Math.max(1, Math.ceil((this.filteredRows || this.dataSource).length / this.pageSize)))
        this.setSelection(this.selectedRowKeys)
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest) || scope !== this.isoScope) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (this.fetchLoadingRequest === listRequest) { this.fetchLoading = false; this.fetchLoadingRequest = null }
        if (!this.isListRequestCurrent('fetchData', listRequest) || scope !== this.isoScope) return

        this.fetchLoading = false
        this.updateImageZones()
      })
    },
    handleChangePage (page, size) { this.pageSize = size; this.page = page },
    handleChangePageSize (page, size) { this.pageSize = size; this.page = 1 },
    setSelection (keys) { this.selectedItems = this.dataSource.filter(r => keys.includes(r.zoneid) && this.isoAction('delete', r)); this.selectedRowKeys = this.selectedItems.map(r => r.zoneid); this.$emit('selection-change', this.selectedRowKeys) },
    resetSelection () { this.setSelection([]) },
    onSelectChange (keys) { this.setSelection(keys) },
    bulkActionConfirmation () { this.deleteIsos() },
    deleteIsos () { this.setSelection(this.selectedRowKeys); if (!this.operationActive && this.selectedItems.length) this.$refs.zoneOps.open('delete', this.selectedItems) },
    deleteIso (record) { if (!this.operationActive && this.requireIsoAction('delete', record)) this.$refs.zoneOps.open('delete', [record]) },
    showCopyIso (record) { if (!this.operationActive && this.requireIsoAction('copy', record)) this.$refs.zoneOps.open('copy', [record]) },
    operationRetained (value) { this.operationActive = !!value.active; this.$emit('operation-retained', value) },
    operationClosed (keys = []) { this.operationActive = false; this.setSelection(keys) },
    fetchZoneData () {
      this.zones = []
      this.zoneLoading = true
      if (!('listZones' in this.$store.getters.apis)) { this.zoneLoading = false; return }
      const scope = this.isoScope
      getAPI('listZones', { showicon: true }).then(json => {
        if (scope !== this.isoScope) return
        const zones = json.listzonesresponse.zone || []
        this.zones = zones
      }).catch(() => {}).finally(() => {
        if (scope !== this.isoScope) return
        this.zoneLoading = false
        this.updateImageZones()
      })
    },
    updateImageZones () {
      if (!Array.isArray(this.dataSource) || !Array.isArray(this.zones) ||
        this.dataSource.length === 0 || this.zones.length === 0) {
        return
      }
      const imageZones = []
      this.dataSource.forEach(item => {
        const zone = this.zones.find(zone => item.zoneid === zone.id)
        if (zone && zone.icon) {
          item.zoneicon = zone.icon
        }
        if (zone) imageZones.push(zone)
      })
      if (imageZones.length !== 0) {
        this.$emit('update-zones', imageZones)
      }
    },
    fetchOsCategoryId () {
      const needed = this.$route.meta.name === 'iso' &&
        'listOsTypes' in this.$store.getters.apis &&
        this.resource && this.resource.ostypeid &&
        (this.$config.imageSelectionInterface === undefined ||
        this.$config.imageSelectionInterface === 'modern')
      if (!needed) {
        return
      }
      const scope = this.isoScope
      getAPI('listOsTypes', { id: this.resource.ostypeid }).then(json => {
        if (scope !== this.isoScope) return
        this.osCategoryId = json?.listostypesresponse?.ostype?.[0]?.oscategoryid || null
      }).catch(() => {})
    },
    onAddInstance (record) {
      if (!this.requireIsoAction('deploy', record)) return
      const query = { isoid: this.resource.id, zoneid: record.zoneid }
      if (this.resource.arch) {
        query.arch = this.resource.arch
      }
      if (this.osCategoryId) {
        query.oscategoryid = this.osCategoryId
      }
      this.$router.push({
        path: '/action/deployVirtualMachine',
        query: query
      })
    }
  }
}
</script>
<style scoped lang="less">
.iso-zones .ant-alert {margin-bottom:16px;}
.iso-zones :deep(.ant-table), .iso-zones :deep(.ant-table-expanded-row > td) {background:var(--ui-bg-surface) !important;color:var(--ui-text-secondary);}
.iso-zones :deep(.ant-table-thead > tr > th) {background:var(--ui-bg-elevated) !important;color:var(--ui-text-primary);border-color:var(--ui-border) !important;}
.iso-zones :deep(.ant-table-tbody > tr > td) {background:var(--ui-bg-surface);border-color:var(--ui-border) !important;}
.iso-zones :deep(.ant-table-tbody > tr:hover > td) {background:var(--ui-bg-hover) !important;}
.iso-zones :deep(.ant-table-tbody > tr.ant-table-row-selected > td) {background:var(--ui-bg-selected) !important;}
.iso-zones :deep(.ant-table-expanded-row .ant-table-tbody > tr > td) {background:var(--ui-bg-surface) !important;}
.iso-zones :deep(.ant-progress-text) {color:var(--ui-text-secondary);}
.iso-zones :deep(.ant-input-search) {width:260px;max-width:100%;margin-left:auto;}
@media(max-width:576px) {.iso-zones :deep(.ant-input-search) {width:100%;margin-left:0;}}
</style>
