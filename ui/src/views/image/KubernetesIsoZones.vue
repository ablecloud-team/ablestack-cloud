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
  <div class="template-zones kubernetes-iso-zones">
    <div class="template-tab-toolbar">
      <a-button :loading="pending" :disabled="!canRead || pending" @click="$emit('refresh-availability')"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="keyword" :aria-label="$t('label.iso.zone.search')" :placeholder="$t('label.iso.zone.search')" allow-clear @search="page = 1" />
    </div>
    <div class="kubernetes-iso-zone-scope"><strong>{{ $t('label.kubernetes.iso.registration.scope') }}</strong>: <kubernetes-iso-scope :resource="resource" /></div>
    <template v-if="observation?.status === 'ready'">
      <detail-tab-table size="small" :columns="columns" :data-source="visibleRows" :pagination="false" :scroll="{ x: 890 }" :row-key="record => record.zoneid" :row-expandable="record => record.downloaddetails?.length > 0">
        <template #bodyCell="{ column, record }">
          <kubernetes-iso-scope v-if="column.key === 'zonename'" :resource="{ zoneid: record.zoneid, zonename: record.zonename }" />
          <kubernetes-iso-status v-else-if="column.key === 'availability'" :resource="resource" :observation="rowObservation(record)" compact />
          <span v-else-if="column.key === 'isready'">{{ $t(kubernetesIsoBooleanLabel(record.isready)) }}</span>
          <keyboard-tooltip v-else-if="column.key === 'reason'" :title="reason(record)" :trigger="['hover','focus']"><span tabindex="0" class="template-cell-value">{{ reason(record) || '—' }}</span></keyboard-tooltip>
          <router-link v-else-if="column.key === 'actions' && isoLink" :to="{ ...isoLink, query: { tab: 'zones' } }">{{ $t('label.kubernetes.iso.view.child') }}</router-link>
        </template>
        <template #expandedRowRender="{ record }">
          <section v-for="section in sections.filter(item => stores(record,item.role).length)" :key="section.role" class="template-zone-storage">
            <h5 class="template-tab-section-title">{{ $t(section.label) }}</h5>
            <a-table size="small" :columns="storeColumns" :data-source="stores(record,section.role)" :pagination="false" :scroll="{ x: 570 }" :row-key="(row,index) => row.datastoreId || index">
              <template #bodyCell="{ column, record: store }">
                <keyboard-tooltip v-if="column.key === 'datastore'" :title="store.datastore || '—'" :trigger="['hover','focus']"><span tabindex="0" class="template-cell-value">{{ store.datastore || '—' }}</span></keyboard-tooltip>
                <template v-else-if="column.key === 'downloadPercent'"><a-progress v-if="isoStorePresentation(store).percent !== null" :percent="isoStorePresentation(store).percent" size="small" :status="isoStorePresentation(store).badge === 'error' ? 'exception' : undefined" /><span v-else>—</span></template>
                <a-badge v-else-if="column.key === 'downloadState'" :status="isoStorePresentation(store).badge" :text="$t(isoStorePresentation(store).label)" />
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
        show-size-changer
        :page-size-options="['10','20','50','100']"
        @change="changePage"
        @showSizeChange="changeSize" /></div>
    </template>
    <a-empty v-else-if="observation?.status === 'empty'" :description="$t('message.kubernetes.iso.availability.empty')" />
    <a-alert v-else :type="pending ? 'info' : 'warning'" show-icon :message="$t('message.kubernetes.iso.availability.' + readStatus)">
      <template v-if="canRead && !pending && readStatus !== 'expired'" #action><a-button size="small" @click="$emit('refresh-availability')">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
  </div>
</template>
<script>
import { Empty } from 'ant-design-vue'
import KeyboardTooltip from '@/components/widgets/KeyboardTooltip'
import KubernetesIsoScope from '@/components/widgets/KubernetesIsoScope'
import KubernetesIsoStatus from '@/components/widgets/KubernetesIsoStatus'
import { kubernetesIsoCanReadChild, kubernetesIsoLink, kubernetesIsoBooleanLabel, kubernetesIsoZoneReason } from '@/utils/kubernetesIsoDetails'
import { isoStorePresentation, isoStoreReason } from '@/utils/isoZoneOperations'
export default {
  name: 'KubernetesIsoZones',
  components: { KeyboardTooltip, AEmpty: Empty, KubernetesIsoScope, KubernetesIsoStatus },
  props: { resource: { type: Object, required: true }, observation: { type: Object, default: null } },
  emits: ['refresh-availability'],
  data: () => ({ keyword: '', page: 1, pageSize: 20 }),
  computed: {
    canRead () { return kubernetesIsoCanReadChild(this.$store.getters) },
    pending () { return !this.observation || this.observation.refreshing || ['loading', 'unqueried'].includes(this.observation.status) },
    readStatus () { return this.observation?.status === 'unqueried' ? 'loading' : this.observation?.status || 'loading' },
    isoLink () { return kubernetesIsoLink(this.resource, this.$store.getters, this.observation) },
    filteredRows () { const key = this.keyword.trim().toLowerCase(); return (this.observation?.status === 'ready' ? this.observation.rows || [] : []).filter(row => !key || [row.zoneid, row.zonename].some(value => String(value || '').toLowerCase().includes(key))) },
    visibleRows () { return this.filteredRows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize) },
    columns () { return [['zonename', 'label.zone', 220], ['availability', 'label.kubernetes.iso.availability', 170], ['isready', 'label.isready', 100], ['reason', 'label.kubernetes.iso.reason', 240], ['actions', 'label.actions', 160]].map(([key, label, width]) => ({ key, dataIndex: key, title: this.$t(label), width })) },
    storeColumns () { return [['datastore', 'label.storage'], ['downloadPercent', 'label.download.percent'], ['downloadState', 'label.download.state']].map(([key, label]) => ({ key, dataIndex: key, title: this.$t(label), width: 190 })) },
    sections () { return [{ role: 'Image', label: 'label.secondary.storage' }, { role: 'Primary', label: 'label.primary.storage' }, { role: 'Unknown', label: 'label.storage' }] }
  },
  watch: {
    filteredRows () { this.page = Math.min(this.page, Math.max(1, Math.ceil(this.filteredRows.length / this.pageSize))) },
    'resource.id' () { this.keyword = ''; this.page = 1 }
  },
  methods: {
    kubernetesIsoBooleanLabel,
    isoStorePresentation,
    stores (row, role) { return (row.downloaddetails || []).filter(store => role === 'Unknown' ? !['Primary', 'Image'].includes(store.datastoreRole) : store.datastoreRole === role) },
    reason (row) { return isoStoreReason(kubernetesIsoZoneReason(row), this.$t) },
    rowObservation (row) { return { ...this.observation, rows: [row], count: 1, coverage: 'complete' } },
    changePage (page, size) { this.page = page; this.pageSize = size },
    changeSize (_, size) { this.page = 1; this.pageSize = size }
  }
}
</script>
<style scoped>
.kubernetes-iso-zone-scope { margin-bottom:16px; color:var(--ui-text-secondary); overflow-wrap:anywhere; }
.kubernetes-iso-zone-scope strong { color:var(--ui-text-primary); }
.kubernetes-iso-zones :deep(.ant-alert) { background:var(--ui-bg-elevated); border-color:var(--ui-border); color:var(--ui-text-primary); }
.kubernetes-iso-zones :deep(.ant-alert-message), .kubernetes-iso-zones :deep(.ant-empty-description) { color:var(--ui-text-primary); }
</style>
