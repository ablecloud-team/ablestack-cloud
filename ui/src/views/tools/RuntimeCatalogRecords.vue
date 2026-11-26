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
  <div class="runtime-catalog-records">
    <div class="catalog-toolbar">
      <a-button :loading="busy" @click="load()"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="$t('message.runtime.catalog.records.load.failed')">
      <template #description>
        <a-space wrap>
          <span v-if="hasSuccessfulRead">{{ $t('message.runtime.catalog.records.previous.result') }}</span>
          <a-button size="small" :loading="busy" @click="load(error.page, error.pagesize)">{{ $t('label.retry') }}</a-button>
        </a-space>
      </template>
    </a-alert>
    <a-alert
      v-if="tab === 'runtime.usages' && hasSuccessfulRead && !error"
      :type="total ? 'warning' : 'info'"
      show-icon
      :message="$t(total ? 'message.runtime.catalog.used' : 'message.runtime.catalog.unused')" />
    <mold-list-table
      v-if="hasSuccessfulRead || busy"
      :columns="columns"
      :data-source="rows"
      row-key="id"
      :loading="busy && !hasSuccessfulRead"
      :pagination="false"
      :scroll="{ x: 700 }"
      size="small">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'"><a-tag><RuntimeCatalogValue :group="valueGroup('state')" :value="record.state" /></a-tag></template>
        <template v-else-if="column.key === 'target'">
          <router-link v-if="record.sharedfilesystemid" :to="'/sharedfs/' + record.sharedfilesystemid + '?tab=runtime'">{{ record.name }}</router-link>
          <router-link v-else-if="record.vmid" :to="'/vm/' + record.vmid">{{ record.name }}</router-link><span v-else>{{ record.name }}</span>
        </template>
        <template v-else-if="column.key === 'view'"><a-button size="small" @click="openRecord(record, $event)">{{ $t('label.details') }}</a-button></template>
        <RuntimeCatalogValue v-else-if="valueGroup(column.key)" :group="valueGroup(column.key)" :value="record[column.key]" />
      </template>
    </mold-list-table>
    <a-pagination v-if="total > 0" :current="page" :page-size="pagesize" :total="total" :disabled="busy" show-size-changer @change="changePage" />
    <MoldDialog
      v-if="detail"
      class="sharedfs-standard-dialog"
      :visible="true"
      :title="$t('label.details')"
      size="md"
      initial-focus=".ant-modal-close"
      focus-guard
      @cancel="closeRecord">
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item v-for="field in detailFields" :key="field" :label="$t(detailLabel(field))">
          <RuntimeCatalogValue :group="valueGroup(field)" :value="detailValues[field]" :show-code="field === 'errorcode'" />
        </a-descriptions-item>
      </a-descriptions>
      <details v-if="detail.detailsjson">
        <summary>{{ $t('label.runtime.catalog.diagnostics') }}</summary>
        <pre>{{ pretty(detail.detailsjson) }}</pre>
      </details>
    </MoldDialog>
  </div>
</template>
<script>
import MoldListTable from '@/components/view/MoldListTable'
import SharedFSTablePagination from '@/components/storage/SharedFSTablePagination.vue'
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import MoldDialog from '@/components/view/MoldDialog'
import RuntimeCatalogValue from '@/components/view/RuntimeCatalogValue'

const recordApis = {
  'runtime.validations': 'listStorageServiceRuntimeBundleValidations',
  'runtime.usages': 'listStorageServiceRuntimeBundleUsages',
  'runtime.events': 'listStorageServiceRuntimeBundleEvents'
}

export default {
  name: 'RuntimeCatalogRecords',
  components: { MoldListTable, APagination: SharedFSTablePagination, MoldDialog, ReloadOutlined, RuntimeCatalogValue },
  props: {
    resource: { type: Object, required: true },
    tab: { type: String, required: true },
    active: { type: Boolean, default: true }
  },
  data () { return { rows: [], total: 0, page: 1, pagesize: 10, busy: false, hasSuccessfulRead: false, error: null, epoch: 0, detail: null, detailTrigger: null } },
  computed: {
    scope () { return this.resource.id + ':' + this.tab },
    detailFields () {
      return this.tab === 'runtime.events'
        ? ['requestid', 'actoruserid', 'action', 'fromstate', 'tostate', 'fromrevision', 'revision', 'errorcode', 'reason', 'created']
        : ['requestid', 'state', 'phase', 'compatibility', 'observedrevision', 'errorcode', 'errormessage', 'created', 'completed']
    },
    detailValues () {
      if (!this.detail) return {}
      if (this.tab !== 'runtime.events') return { ...this.detail, observedrevision: this.detail.revision }
      const transition = this.parseDetails(this.detail.detailsjson)
      return {
        ...this.detail,
        fromstate: transition?.fromState,
        tostate: transition?.toState ?? this.detail.state,
        fromrevision: transition?.fromRevision
      }
    },
    columns () {
      const names = this.tab === 'runtime.usages' ? ['type', 'target', 'state', 'created'] : this.tab === 'runtime.events' ? ['created', 'action', 'state', 'reason', 'revision', 'view'] : ['created', 'state', 'compatibility', 'errorcode', 'view']
      return names.map(key => ({ key, dataIndex: key, title: this.$t('label.' + (key === 'target' ? 'resource' : key === 'view' ? 'details' : key)), width: key === 'target' || key === 'reason' ? 280 : 150 }))
    }
  },
  watch: {
    scope: 'resetScope',
    active (active) {
      if (active) {
        if (!this.hasSuccessfulRead) this.load()
      } else {
        this.epoch++
        this.busy = false
        this.detail = null
        this.detailTrigger = null
      }
    }
  },
  created () { this.load() },
  beforeUnmount () { this.epoch++ },
  methods: {
    valueGroup (field) {
      if (field === 'state') {
        if (this.tab === 'runtime.validations') return 'validationstatus'
        if (this.tab === 'runtime.usages') return 'usagestate'
        return 'state'
      }
      if (['fromstate', 'tostate'].includes(field)) return 'state'
      return ['type', 'action', 'phase', 'compatibility', 'errorcode'].includes(field) ? field : ''
    },
    openRecord (record, event) {
      this.detailTrigger = event.currentTarget
      this.detail = record
    },
    closeRecord () {
      const trigger = this.detailTrigger
      this.detail = null
      this.detailTrigger = null
      this.$nextTick(() => {
        if (this.active && trigger?.isConnected && trigger.getClientRects().length) trigger.focus({ preventScroll: true })
      })
    },
    parseDetails (value) {
      try { return JSON.parse(value) } catch { return null }
    },
    pretty (value) {
      const parsed = this.parseDetails(value)
      return parsed === null ? value : JSON.stringify(parsed, null, 2)
    },
    detailLabel (field) {
      const catalogueFields = ['actoruserid', 'fromstate', 'tostate', 'fromrevision', 'observedrevision', 'phase']
      return catalogueFields.includes(field) ? 'label.runtime.catalog.' + field : 'label.' + field
    },
    changePage (page, size) {
      if (this.page === page && this.pagesize === size) return
      this.detail = null
      this.load(page, size)
    },
    resetScope () {
      this.epoch++
      this.rows = []
      this.total = 0
      this.detail = null
      this.hasSuccessfulRead = false
      this.detailTrigger = null
      this.error = null
      this.busy = false
      this.page = 1
      this.pagesize = 10
      this.load()
    },
    async load (page = this.page, pagesize = this.pagesize) {
      const api = recordApis[this.tab]
      if (!this.active || !api || !this.resource.id || this.busy) return
      this.busy = true
      const epoch = ++this.epoch
      try {
        const response = await getAPI(api, { bundleid: this.resource.id, page, pagesize })
        if (epoch !== this.epoch) return
        const data = response[api.toLowerCase() + 'response']
        if (!data) throw new Error('Missing runtime catalogue records')
        this.rows = data.storageserviceruntimecatalogrecord || []
        this.total = data.count || 0
        this.page = page
        this.pagesize = pagesize
        this.hasSuccessfulRead = true
        this.error = null
      } catch {
        if (epoch === this.epoch) this.error = { page, pagesize }
      } finally {
        if (epoch === this.epoch) this.busy = false
      }
    }
  }
}
</script>
<style scoped lang="less">
.runtime-catalog-records { min-width: 0; }
.catalog-toolbar { display: flex; margin-bottom: 16px; }
.runtime-catalog-records :deep(.sharedfs-table .ant-table-thead > tr > th.ant-table-cell-fix-left),
.runtime-catalog-records :deep(.sharedfs-table .ant-table-thead > tr > th.ant-table-cell-fix-right) {
  background-color: var(--ui-bg-elevated);
}
.ant-alert, .ant-descriptions { margin-bottom: 16px; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; color:var(--ui-text-primary); background:var(--ui-bg-surface); padding:16px; }
summary { cursor: pointer; }
:deep(.ant-descriptions-item-content) { overflow-wrap: anywhere; word-break: break-word; }
</style>
