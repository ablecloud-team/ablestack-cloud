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
  <section class="creation-source-selection" data-testid="creation-source-selection">
    <a-radio-group :value="imageType" @change="$emit('change-image-type', $event.target.value, true)">
      <a-radio-button v-for="kind in ['templateid', 'isoid', 'volumeid', 'snapshotid']" :key="kind" :value="kind">{{ $t('label.' + kind.replace(/id$/, '')) }}</a-radio-button>
    </a-radio-group>
    <a-alert type="info" show-icon :message="$t('message.creation.source.' + sourceKind)" />
    <div class="source-toolbar">
      <a-input-search v-model:value="keyword" :placeholder="$t('label.creation.source.search')" allow-clear @search="search" />
      <a-checkbox v-model:checked="availableOnly" @change="search">{{ $t('label.creation.source.available.only') }}</a-checkbox>
      <a-button :loading="loading" @click="fetchSources"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="$t('message.creation.source.fetch.failed')" :description="error" />
    <a-table
:columns="columns"
:data-source="sources"
row-key="id"
size="small"
:loading="loading"
:pagination="pagination"
:scroll="{ x: 880 }"
      :row-selection="rowSelection"
@change="changePage">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'"><strong>{{ record.name }}</strong><div class="source-meta">{{ record.id }}</div></template>
        <template v-else-if="column.key === 'origin'">{{ record.sourcevm?.displayname || record.sourcevm?.name || $t('label.creation.source.origin.unknown') }}<div class="source-meta">{{ record.bootprofile?.osname || $t('label.creation.source.os.unspecified') }}</div></template>
        <template v-else-if="column.key === 'state'"><a-tag class="source-state" :color="record.allowed ? 'green' : 'default'">{{ stateLabel(record.state) }}</a-tag><div v-if="record.snapshotcreated" class="source-meta">{{ date(record.snapshotcreated) }}</div></template>
        <template v-else-if="column.key === 'size'">{{ bytes(record.sizebytes) }} · {{ record.imageformat || '—' }}<div class="source-meta" v-if="record.formatorigin">{{ $t('label.creation.source.format.origin.' + record.formatorigin) }}</div><div class="source-meta">{{ [record.bootprofile?.boottype, record.bootprofile?.bootmode].filter(Boolean).join(' · ') || '—' }}</div></template>
        <template v-else-if="column.key === 'storage'">{{ record.sourceusage === 'stage-and-adopt' ? $t('label.creation.source.secondary') : record.storage?.name || '—' }}<div v-if="record.storage?.type || record.storage?.scope" class="source-meta">{{ [record.storage?.type, record.storage?.scope].filter(Boolean).join(' · ') }}</div></template>
        <template v-else-if="column.key === 'eligibility'">
          <span v-if="record.allowed">{{ $t('label.creation.source.available') }}</span><div v-if="record.allowed && record.requiresconfiguration" class="source-meta">{{ $t('label.creation.source.configuration.manual') }}</div>
          <div v-for="reason in record.reasoncodes" :key="reason" class="source-reason">{{ $t('message.creation.source.reason.' + reason) }}</div>
        </template>
      </template>
      <template #emptyText>{{ $t(!ownerReady ? 'message.creation.source.owner.required' : error ? 'message.creation.source.fetch.failed' : 'message.creation.source.empty') }}</template>
    </a-table>
    <p v-if="checkedAt" class="source-meta">{{ $t('label.creation.source.checked') }}: {{ checkedAt }}</p>
  </section>
</template>
<script>
import { ReloadOutlined } from '@ant-design/icons-vue'
import { getAPI } from '@/api'
export default {
  components: { ReloadOutlined },
  props: { ownerReady: { type: Boolean, default: true }, imageType: { type: String, required: true }, query: { type: Object, required: true }, selected: { type: Object, default: null }, preselectedId: { type: String, default: null } },
  emits: ['select', 'change-image-type', 'loading'],
  data () { return { sources: [], count: 0, page: 1, pageSize: 10, keyword: '', availableOnly: false, loading: false, error: '', requestSequence: 0, checkedAt: '', preselectionUsed: false } },
  computed: {
    sourceKind () { return this.imageType === 'volumeid' ? 'volume' : 'snapshot' },
    columns () { return ['name', 'origin', 'state', 'size', 'storage', 'eligibility'].map(key => ({ key, title: this.$t('label.creation.source.column.' + key) })) },
    pagination () { return { current: this.page, pageSize: this.pageSize, total: this.count, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] } },
    rowSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.selected ? [this.selected.id] : [],
        getCheckboxProps: record => ({ disabled: !record.allowed || this.loading, 'aria-label': record.name }),
        onChange: (keys, rows) => this.$emit('select', rows[0] || null)
      }
    }
  },
  watch: { query: { deep: true, immediate: true, handler () { this.page = 1; this.preselectionUsed = false; this.$emit('select', null); this.fetchSources() } } },
  beforeUnmount () { this.requestSequence++ },
  methods: {
    stateLabel (state) {
      const key = 'label.creation.source.state.' + state
      const translated = this.$t(key)
      return translated === key ? this.$t('label.creation.source.state.unknown') : translated
    },
    bytes (value) { return value == null ? '—' : (value / 1024 ** 3).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' GiB' },
    date (value) { return new Date(value).toLocaleString() },
    search () { this.page = 1; this.fetchSources() },
    changePage (pagination) { this.page = pagination.current; this.pageSize = pagination.pageSize; this.fetchSources() },
    async fetchSources () {
      const sequence = ++this.requestSequence
      this.loading = true; this.error = ''; this.$emit('loading', true)
      if (!this.query.zoneid) { this.sources = []; this.count = 0; this.checkedAt = ''; this.loading = false; this.$emit('loading', false); return }
      try {
        const args = Object.fromEntries(Object.entries({
          ...this.query,
          sourcekind: this.sourceKind,
          id: !this.preselectionUsed ? this.preselectedId || undefined : undefined,
          page: this.page,
          pagesize: this.pageSize,
          keyword: this.keyword,
          includeunavailable: !this.availableOnly
        }).filter(([, value]) => value != null && value !== ''))
        const response = await getAPI('listVirtualMachineCreationSources', args)
        if (sequence !== this.requestSequence) return
        const result = response.listvirtualmachinecreationsourcesresponse || {}
        this.sources = result.creationsource || []; this.count = result.count || 0; this.checkedAt = new Date().toLocaleTimeString()
        if (this.selected) {
          const current = this.sources.find(source => source.id === this.selected.id)
          if (current && (!current.allowed || current.revision !== this.selected.revision)) this.$emit('select', null)
        } else if (this.preselectedId && !this.preselectionUsed) {
          const source = this.sources.find(source => source.id === this.preselectedId && source.allowed)
          if (source) this.$emit('select', source)
        }
        this.preselectionUsed = true
      } catch (error) {
        if (sequence !== this.requestSequence) return
        this.sources = []; this.count = 0; const detail = error.response?.data?.errorresponse?.errortext || ''
        this.error = /Unknown API command/i.test(detail) ? this.$t('message.creation.source.api.required') : detail || this.$t('message.creation.source.fetch.failed'); this.$emit('select', null)
      } finally {
        if (sequence === this.requestSequence) { this.loading = false; this.$emit('loading', false) }
      }
    }
  }
}
</script>
<style lang="less" scoped>
.creation-source-selection { color: var(--ui-text); }
:deep(.ant-radio-button-wrapper-checked:not(.ant-radio-button-wrapper-disabled)) { background: var(--ui-bg-selected) !important; color: var(--ui-link) !important; border-color: var(--ui-focus) !important; font-weight: 600; }
:deep(.ant-radio-button-wrapper-checked::before) { background: var(--ui-focus) !important; }
:deep(.ant-radio-button-wrapper:focus-within) { outline: 2px solid var(--ui-focus); outline-offset: 2px; }
.source-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 16px 0; }
.source-toolbar .ant-input-search { max-width: 340px; }
:deep(.ant-input::placeholder) { color: var(--ui-text-secondary) !important; opacity: 1; }
.ant-alert { margin-top: 12px; }
.source-meta { color: var(--ui-text-secondary); font-size: 12px; overflow-wrap: anywhere; margin-top: 4px; }
.source-state { color: var(--ui-text) !important; }
.source-reason { color: var(--ui-text-secondary); overflow-wrap: anywhere; }
:deep(.ant-table-thead > tr > th) { background: var(--ui-bg-elevated) !important; color: var(--ui-text-secondary) !important; border-color: var(--ui-border); }
:deep(.ant-table-placeholder > td), :deep(.ant-empty-description) { color: var(--ui-text-secondary) !important; }
:deep(.ant-table-tbody > tr > td) { color: var(--ui-text); border-color: var(--ui-border); }
</style>
