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
  <div class="template-zones">
    <div class="template-tab-toolbar">
      <a-button :loading="fetchLoading" :disabled="operationActive" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-button v-if="'deleteTemplate' in $store.getters.apis && isActionsOnTemplatePermitted" danger :disabled="operationActive || !selectedRowKeys.length" @click="bulkActionConfirmation"><template #icon><delete-outlined /></template>{{ $t('label.action.bulk.delete.templates') }} <span class="zone-selection-count" aria-live="polite">({{ selectedRowKeys.length }})</span></a-button>
    </div>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-table
size="small"
:loading="loading || fetchLoading"
:columns="columns"
:data-source="dataSource"
:pagination="false"
:scroll="{x:800}"
:row-selection="{selectedRowKeys,onChange:onSelectChange}"
:row-key="record=>record.zoneid"
:expand-row-by-click="true"
:row-expandable="record=>downloadDetails(record).length>0">
      <template #bodyCell="{column,record}">
        <template v-if="column.key==='zonename'"><a-tooltip :title="record.zonename"><span class="template-cell-value"><resource-icon v-if="record.zoneicon?.base64image" :image="record.zoneicon.base64image" size="1x" /><global-outlined v-else /> {{ record.zonename }}</span></a-tooltip></template>
        <template-availability v-else-if="column.dataIndex==='status'" :record="record" compact single-line :show-scope="false" />
        <span v-else-if="column.key==='isready'">{{ $t(record.isready===true?'label.yes':record.isready===false?'label.no':'label.unknown') }}</span>
        <a-tooltip v-else-if="column.key==='created'" :title="record.created ? $toLocaleDate(record.created) : '—'"><span class="template-cell-value zone-created">{{ record.created ? $toLocaleDate(record.created) : '—' }}</span></a-tooltip>
        <div v-else-if="column.key==='actions'" class="template-row-actions zone-row-actions" @click.stop>
          <tooltip-button v-if="'deployVirtualMachine' in $store.getters.apis" :disabled="!record.isready || operationActive" :tooltip="$t('label.vm.add')" icon="rocket-outlined" @onClick="onAddInstance(record)" />
          <template v-if="isActionsOnTemplatePermitted">
            <tooltip-button :disabled="operationActive || !('copyTemplate' in $store.getters.apis && record.isready)" :tooltip="$t('label.action.copy.template')" icon="copy-outlined" @onClick="showCopyTemplate(record)" />
            <tooltip-button v-if="canPrepare" :disabled="operationActive || record.isready!==true" :tooltip="$t('label.template.zone.prepare')" icon="download-outlined" @onClick="$refs.zoneOps.open('prepare',[record])" />
            <tooltip-button :disabled="operationActive || !('deleteTemplate' in $store.getters.apis)" :tooltip="$t('label.action.delete.template')" :danger="true" icon="delete-outlined" @onClick="onShowDeleteModal(record)" />
          </template>
        </div>
      </template>
      <template #expandedRowRender="{record}">
        <template v-for="section in storeSections" :key="section.role">
          <section v-if="storeRows(record,section.role).length" class="template-zone-storage">
            <h5 class="template-tab-section-title">{{ $t(section.label) }}</h5>
            <a-table size="small" :columns="storeColumns" :data-source="storeRows(record,section.role)" :pagination="false" :bordered="false" :scroll="{x:580}" :row-key="item=>item.datastoreId || item.datastore">
              <template #bodyCell="{column,record:item}">
                <a-tooltip v-if="column.key==='datastore'" :title="item.datastore"><router-link v-if="item.datastoreId" class="template-cell-value" :to="section.path+'/'+encodeURIComponent(item.datastoreId)">{{ item.datastore }}</router-link><span v-else class="template-cell-value">{{ item.datastore }}</span></a-tooltip>
                <template v-else-if="column.key==='downloadPercent'"><a-progress v-if="storePresentation(item).percent!==null" :percent="storePresentation(item).percent" size="small" :status="storePresentation(item).badge==='error'?'exception':undefined" /><span v-else>—</span></template>
                <a-tooltip v-else-if="column.key==='downloadState'" :title="item.downloadState"><a-badge :status="storePresentation(item).badge" :text="$t(storePresentation(item).label)" /></a-tooltip>
              </template>
            </a-table>
          </section>
        </template>
      </template>
    </a-table>
    <div class="template-tab-pagination"><span>{{ $t('label.total') }} {{ itemCount }} {{ $t('label.items') }}</span><a-pagination
size="small"
:current="page"
:page-size="pageSize"
:total="itemCount"
:disabled="operationActive"
:page-size-options="['10','20','40','80','100']"
show-size-changer
@change="handleChangePage"
@showSizeChange="handleChangePageSize"><template #buildOptionText="props"><span>{{ props.value }} / {{ $t('label.page') }}</span></template></a-pagination></div>
    <template-zone-operations
ref="zoneOps"
:resource="resource"
:context="templateScope"
:permitted="isActionsOnTemplatePermitted"
@retained="operationRetained"
@refresh="fetchData"
@refresh-parent="$emit('refresh-parent')"
@closed="resetSelection" />
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import TemplateAvailability from '@/components/widgets/TemplateAvailability'
import { templateObservationContext } from '@/utils/templateAvailability'

import { ref, reactive } from 'vue'
import { templateStorePresentation } from '@/utils/templateZoneOperations'
import { readTemplateResource } from '@/utils/templateSearch'
import { getAPI } from '@/api'
import OsLogo from '@/components/widgets/OsLogo'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipButton from '@/components/widgets/TooltipButton'
import TemplateZoneOperations from '@/views/image/TemplateZoneOperations'
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchData'], { scopeKey: vm => [vm.templateScope, vm.page, vm.pageSize] })],
  name: 'TemplateZones',
  components: {
    TemplateAvailability,
    TooltipButton,
    OsLogo,
    ResourceIcon,
    TemplateZoneOperations,
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
      operationActive: false,
      columns: [],
      dataSource: [],
      page: 1,
      pageSize: 10,
      itemCount: 0,
      fetchLoading: false,
      fetchLoadingRequest: null,
      showCopyActionForm: false,
      currentRecord: {},
      zones: [],
      copyZones: [],
      zoneLoading: false,
      copyLoading: false,
      deleteLoading: false,
      showDeleteTemplate: false,
      forcedDelete: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['Status', 'Ready'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.delete.templates'),
        confirmMessage: this.$t('label.confirm.delete.templates')
      },
      modalWidth: '30vw',
      showTable: false,
      osCategoryId: null
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('copyTemplate')
  },
  created () {
    this.columns = [
      {
        key: 'zonename',
        title: this.$t('label.zonename'),
        dataIndex: 'zonename',
        width: 180,
        ellipsis: true
      },
      {
        key: 'created',
        title: this.$t('label.created'),
        dataIndex: 'created',
        width: 168
      },
      {
        title: this.$t('label.status'),
        dataIndex: 'status',
        width: 160
      },
      {
        key: 'isready',
        title: this.$t('label.isready'),
        dataIndex: 'isready',
        width: 76
      },
      {
        key: 'actions',
        title: '',
        dataIndex: 'actions',
        width: 176
      }
    ]
    this.imageStoreInnerColumns = [
      {
        title: this.$t('label.secondary.storage'),
        dataIndex: 'datastore',
        width: '30%'
      },
      {
        title: this.$t('label.download.percent'),
        dataIndex: 'downloadPercent',
        width: '30%'
      },
      {
        title: this.$t('label.download.state'),
        dataIndex: 'downloadState'
      }
    ]
    this.storagePoolInnerColumns = [
      {
        title: this.$t('label.primary.storage'),
        dataIndex: 'datastore',
        width: '30%'
      },
      {
        title: this.$t('label.download.percent'),
        dataIndex: 'downloadPercent',
        width: '30%'
      },
      {
        title: this.$t('label.download.state'),
        dataIndex: 'downloadState'
      }
    ]

    const userInfo = this.$store.getters.userInfo
    if (!['Admin'].includes(userInfo.roletype) &&
      (userInfo.account !== this.resource.account || userInfo.domain !== this.resource.domain)) {
      this.columns = this.columns.filter(col => { return col.dataIndex !== 'status' })
    }
    this.initForm()
    this.fetchData()
  },
  watch: {
    templateScope () { this.dataSource = []; this.itemCount = 0; this.fetchData() },
    loading (newData, oldData) {
      if (!newData && !this.showGroupActionModal) {
        this.fetchData()
      }
    }
  },
  computed: {
    storeSections () { return [{ role: 'Primary', label: 'label.primary.storage', path: '/storagepool' }, { role: 'Secondary', label: 'label.secondary.storage', path: '/imagestore' }] },
    storeColumns () { return [{ key: 'datastore', dataIndex: 'datastore', title: this.$t('label.storage'), width: 220 }, { key: 'downloadPercent', dataIndex: 'downloadPercent', title: this.$t('label.download.percent'), width: 180 }, { key: 'downloadState', dataIndex: 'downloadState', title: this.$t('label.download.state'), width: 180 }] },
    canPrepare () { return this.$store.getters.userInfo.roletype === 'Admin' && ['prepareTemplate', 'listStoragePools'].every(k => k in this.$store.getters.apis) && !!this.$getApiParams('prepareTemplate')?.storageid && this.resource.hypervisor === 'KVM' && this.resource.format === 'QCOW2' && this.resource.directdownload !== true },
    templateScope () { return templateObservationContext(this.$route, this.resource, this.$store.getters, this.$store.state?.user?.token) },
    isActionsOnTemplatePermitted () {
      return (['Admin'].includes(this.$store.getters.userInfo.roletype) ||
        (this.resource.domainid === this.$store.getters.userInfo.domainid && this.resource.account === this.$store.getters.userInfo.account) ||
        (this.resource.domainid === this.$store.getters.userInfo.domainid && this.resource.projectid && this.$store.getters.project && this.$store.getters.project.id && this.resource.projectid === this.$store.getters.project.id)) &&
        (this.resource.isready || !this.resource.status || this.resource.status.indexOf('Downloaded') === -1) &&
        this.resource.templatetype !== 'SYSTEM'
    }
  },
  emits: ['update-zones', 'availability-observed', 'selection-change', 'operation-retained', 'refresh-parent'],
  methods: {
    storePresentation: templateStorePresentation,
    storeRows (record, role) { return this.downloadDetails(record).filter(row => role === 'Primary' ? row.datastoreRole === 'Primary' : row.datastoreRole !== 'Primary') },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        zoneid: [{ type: 'array', required: true, message: this.$t('message.error.select') }]
      })
    },
    downloadDetails (record) { return Array.isArray(record.downloaddetails) ? record.downloaddetails : [] },
    fetchData () {
      if (!this.resource.id) { this.dataSource = []; this.itemCount = 0; this.fetchLoading = false; this.fetchLoadingRequest = null; return Promise.resolve() }
      const context = this.templateScope
      const listRequest = this.listRequestToken('fetchData')
      const params = {}
      params.id = this.resource.id
      params.listall = true
      params.page = this.page
      params.pagesize = this.pageSize

      this.fetchLoadingRequest = listRequest
      this.fetchLoading = !listRequest.loaded
      if (!listRequest.loaded) {
        this.fetchZoneData()
        this.fetchOsCategoryId()
      }
      return readTemplateResource(getAPI, params, this.$store.getters, () => this.isListRequestCurrent('fetchData', listRequest) && context === this.templateScope).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest) || context !== this.templateScope) return
        this.dataSource = []
        this.itemCount = 0
        this.dataSource = json.listtemplatesresponse.template || []
        this.itemCount = json.listtemplatesresponse.count || 0
        this.$emit('availability-observed', { templateid: this.resource.id, context, rows: this.dataSource, count: this.itemCount, coverage: this.itemCount === this.dataSource.length ? 'complete' : 'partial' })
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest) || context !== this.templateScope) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (this.fetchLoadingRequest === listRequest) { this.fetchLoading = false; this.fetchLoadingRequest = null }
        if (!this.isListRequestCurrent('fetchData', listRequest) || context !== this.templateScope) return

        this.fetchLoading = false
        this.updateImageZones()
      })
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
    setSelection (selection) {
      this.selectedRowKeys = selection
      if (selection?.length > 0) {
        this.modalWidth = '50vw'
        this.$emit('selection-change', this.selectedRowKeys)
        this.selectedItems = (this.dataSource.filter(function (item) {
          return selection.indexOf(item.zoneid) !== -1
        }))
      } else {
        this.modalWidth = '30vw'
        this.selectedItems = []
      }
    },
    resetSelection () {
      this.setSelection([])
    },
    onSelectChange (selectedRowKeys, selectedRows) {
      this.setSelection(selectedRowKeys)
    },
    operationRetained (value) { this.operationActive = value.active; this.$emit('operation-retained', value) },
    bulkActionConfirmation () { this.$refs.zoneOps.open('delete', this.selectedItems) },
    fetchZoneData () {
      this.zones = []
      this.zoneLoading = true
      getAPI('listZones', { showicon: true }).then(json => {
        const zones = json.listzonesresponse.zone || []
        this.zones = zones
        this.copyZones = [...zones.filter((zone) => this.currentRecord.zoneid !== zone.id)]
      }).finally(() => {
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
        imageZones.push(zone)
      })
      if (imageZones.length !== 0) {
        this.$emit('update-zones', imageZones)
      }
    },
    fetchOsCategoryId () {
      const needed = this.$route.meta.name === 'template' &&
        'listOsTypes' in this.$store.getters.apis &&
        this.resource && this.resource.ostypeid &&
        (this.$config.imageSelectionInterface === undefined ||
        this.$config.imageSelectionInterface === 'modern')
      if (!needed) {
        return
      }
      getAPI('listOsTypes', { id: this.resource.ostypeid }).then(json => {
        this.osCategoryId = json?.listostypesresponse?.ostype?.[0]?.oscategoryid || null
      })
    },
    showCopyTemplate (record) { this.$refs.zoneOps.open('copy', [record]) },
    onShowDeleteModal (record) { this.$refs.zoneOps.open('delete', [record]) },
    onAddInstance (record) {
      const query = { templateid: this.resource.id, zoneid: record.zoneid }
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

<style lang="less" scoped>
.row-element {
  margin-top: 15px;
  margin-bottom: 15px;
}
</style>
