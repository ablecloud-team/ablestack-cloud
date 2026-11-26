<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <section class="cni-history" :aria-label="$t('label.cni.history')" :aria-busy="pending || parentPending">
    <div class="detail-tab-toolbar">
      <a-button :loading="pending" :disabled="!allowed || !parentReady" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-button :disabled="!canCompareSelection" @click="compareSelected">{{ $t('label.cni.history.compare.selected') }}</a-button>
      <a-input-search v-model:value="draftKeyword" class="cni-history-search" :disabled="!allowed" :placeholder="$t('label.search')" :aria-label="$t('label.search')" allow-clear @search="search" />
    </div>
    <a-alert v-if="error" type="error" show-icon role="status" :message="$t(error)">
      <template #description><span v-if="rows.length">{{ $t('message.cni.history.stale') }}</span><a-button v-if="allowed && parentReady" type="link" :loading="pending" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-alert v-if="!parentReady && !parentPending" type="warning" show-icon :message="$t('message.cni.history.unverified')" />
    <a-alert v-if="limited" type="warning" show-icon role="status" :message="$t('message.cni.history.limited')" />
    <a-alert v-if="warnings" type="warning" show-icon role="status" :message="$t('message.cni.history.warnings', [warnings])" />
    <div class="cni-history-table">
      <detail-tab-table
        size="small"
        :columns="columns"
        :dataSource="pageRows"
        rowKey="id"
        :pagination="false"
        :scroll="{ x: 1240 }"
        :loading="(pending || parentPending) && !rows.length"
        :row-selection="rowSelection"
        :locale="{ emptyText: $t(emptyMessage) }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <router-link v-if="record.available && parentReady" :title="record.row.name" :to="resourceLink(record.id)">{{ record.row.name }}</router-link>
            <span v-else>{{ record.row?.name || '—' }}</span>
            <a-tag v-if="record.current">{{ $t('label.cni.history.current') }}</a-tag>
          </template>
          <template v-else-if="column.key === 'source'">
            <router-link v-if="record.parent && record.state === 'valid' && parentReady" :title="record.parent.name + ' (' + record.parent.id + ')'" :to="resourceLink(record.parent.id)">{{ record.parent.name }} ({{ record.parent.id }})</router-link>
            <span v-else>{{ record.record?.sourceid || '—' }}</span>
          </template>
          <span v-else-if="column.key === 'state'">{{ $t('label.cni.history.state.' + record.state) }}</span>
          <span v-else-if="column.key === 'generation'">{{ record.generation ?? '—' }}</span>
          <a-button v-else-if="column.key === 'compare'" type="link" :disabled="!record.comparable || !parentReady || pending || comparing || !!error" @click="openComparison(record.parent, record.row, record.record)">{{ $t('label.cni.history.compare.parent') }}</a-button>
          <span v-else>{{ record.id }}</span>
        </template>
      </detail-tab-table>
    </div>
    <div v-if="rows.length || (parentReady && !pending && !error)" class="detail-tab-pagination">
      <detail-tab-pagination :current="page" :pageSize="pagesize" :total="filteredRows.length" show-size-changer :pageSizeOptions="['10', '20', '50']" :showTotal="total => $t('message.cni.history.total', [total])" @change="changePage" />
    </div>
    <MoldDialog v-if="comparisonOpen" class="cni-comparison-dialog" size="xl" :title="$t('label.cni.history.compare')" @cancel="closeDialog">
      <a-spin :spinning="comparing || parentPending">
        <a-alert v-if="comparisonError" type="error" show-icon role="alert" :message="$t(comparisonError)">
          <template #description><a-button type="link" :disabled="!allowed || !parentReady || comparing" @click="retryComparison">{{ $t('label.retry') }}</a-button></template>
        </a-alert>
        <template v-if="comparison">
          <a-descriptions class="mold-dialog-summary" size="small" :column="1">
            <a-descriptions-item :label="$t('label.cni.history.before')">{{ comparison.left.name }} ({{ comparison.left.id }})</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.after')">{{ comparison.right.name }} ({{ comparison.right.id }})</a-descriptions-item>
          </a-descriptions>
          <a-alert :type="comparison.difference.state === 'corrupt' ? 'error' : comparison.difference.equal && comparison.difference.paramsEqual ? 'success' : 'info'" show-icon :message="$t('message.cni.history.compare.' + comparison.difference.state)" />
          <a-descriptions size="small" :column="{ xs: 1, sm: 2 }" class="mold-dialog-section">
            <a-descriptions-item :label="$t('label.cni.clone.bytes')">{{ comparison.difference.before ?? '—' }} → {{ comparison.difference.after ?? '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.first')">{{ comparison.difference.first ?? '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.params.before')">{{ comparison.difference.beforeParams || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.params.after')">{{ comparison.difference.afterParams || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.params.state')">{{ $t(comparison.difference.paramsEqual ? 'label.cni.history.equal' : 'label.cni.history.changed') }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.params.added')">{{ comparison.difference.added.join(', ') || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.cni.history.params.removed')">{{ comparison.difference.removed.join(', ') || '—' }}</a-descriptions-item>
          </a-descriptions>
          <a-alert v-if="comparison.difference.binary" type="warning" show-icon :message="$t('message.cni.history.binary')" />
          <a-alert v-if="comparison.difference.limited" type="warning" show-icon :message="$t('message.cni.history.diff.limited')" />
          <div v-if="comparison.difference.rows.length" class="cni-diff-table mold-dialog-section">
            <a-table size="small" :columns="diffColumns" :dataSource="comparison.difference.rows" rowKey="key" :pagination="false" :scroll="{ x: 800, y: 320 }">
              <template #bodyCell="{ column, record }">
                <span v-if="column.key === 'kind'" :class="'cni-diff-' + record.kind">{{ $t('label.cni.history.diff.' + record.kind) }}</span>
                <pre v-else-if="column.key === 'text'" class="cni-diff-text">{{ record.text || ' ' }}</pre>
                <span v-else>{{ record.line }}</span>
              </template>
            </a-table>
          </div>
          <fieldset v-if="comparison.difference.state !== 'corrupt'" class="cni-comparison-bodies" :disabled="!parentReady || comparing">
            <details class="mold-dialog-section"><summary>{{ $t('label.cni.history.before') }}</summary><UserDataContent :resource="comparison.left" source-type="cni" :preview-encoded="comparison.left.userdata || ''" /></details>
            <details class="mold-dialog-section"><summary>{{ $t('label.cni.history.after') }}</summary><UserDataContent :resource="comparison.right" source-type="cni" :preview-encoded="comparison.right.userdata || ''" /></details>
          </fieldset>
        </template>
      </a-spin>
    </MoldDialog>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import UserDataContent from './UserDataContent'
import { cniDeleteError } from '@/utils/cniDeletion'
import { cniHistoryApis, readCniHistory, readCniComparison } from '@/utils/cniHistory'

const tabName = 'cni.history'
export default {
  name: 'CniHistory',
  components: { MoldDialog, UserDataContent },
  props: { resource: { type: Object, required: true }, tab: { type: String, default: tabName }, loading: Boolean },
  inject: { resourceActionReady: { default: () => () => true }, resourceReadPending: { default: () => () => false } },
  data () {
    return { rows: [], limited: false, warnings: 0, incomplete: false, pending: false, error: '', generation: 0, disposed: false, selected: [], keyword: '', draftKeyword: '', page: 1, pagesize: 20, comparisonOpen: false, comparing: false, comparison: null, comparisonError: '', comparisonPair: null, comparisonGeneration: 0 }
  },
  computed: {
    active () { return this.tab === tabName && this.$route.path.toLowerCase() === '/cniconfiguration/' + (this.resource.id || '').toLowerCase() },
    allowed () { return cniHistoryApis.every(api => !!this.$store.getters.apis?.[api]) },
    parentPending () { return this.loading || this.resourceReadPending() },
    parentReady () { return !this.parentPending && this.resourceActionReady() },
    parentState () { return this.parentPending ? 'pending' : this.parentReady ? 'ready' : 'unverified' },
    scope () { return JSON.stringify([this.resource.id, this.resource.name, this.resource.domainid, this.resource.account, this.resource.projectid, this.$route.path, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.allowed]) },
    queryKey () { return JSON.stringify(['cnihistorypage', 'cnihistorysize', 'cnihistoryq'].map(k => this.$route.query[k])) },
    emptyMessage () { return this.error || (!this.parentReady ? this.parentPending ? 'label.loading' : 'message.cni.history.unverified' : this.pending ? 'label.loading' : 'message.cni.history.empty') },
    filteredRows () { const q = this.keyword.trim().toLowerCase(); return this.rows.filter(r => !q || [r.id, r.row?.name, r.record?.sourceid].some(v => v?.toLowerCase().includes(q))) },
    pageRows () { return this.filteredRows.slice((this.page - 1) * this.pagesize, this.page * this.pagesize) },
    rowSelection () { return { preserveSelectedRowKeys: true, selectedRowKeys: this.selected, onChange: keys => { this.selected = keys.slice(-2) }, getCheckboxProps: record => ({ disabled: !record.available || record.state === 'cycle' || !this.parentReady || !!this.error || this.pending || this.comparing }) } },
    canCompareSelection () { return this.selected.length === 2 && this.allowed && this.parentReady && !this.pending && !this.comparing && !this.error },
    columns () { return ['generation', 'name', 'id', 'source', 'state', 'compare'].map(key => ({ key, ellipsis: ['name', 'source'].includes(key), title: this.$t('label.cni.history.column.' + key), width: { generation: 70, name: 220, id: 300, source: 300, state: 170, compare: 140 }[key] })) },
    diffColumns () { return ['kind', 'line', 'text'].map(key => ({ key, title: this.$t('label.cni.history.diff.' + key), width: { kind: 100, line: 90, text: 610 }[key] })) }
  },
  watch: {
    scope () { this.reset(); this.fetchData() },
    active (value) { this.generation++; this.pending = false; this.closeComparison(); if (value) this.fetchData() },
    parentState (value) {
      this.generation++; this.pending = false
      if (value === 'pending') { this.comparisonGeneration++; this.comparing = false; return }
      if (value === 'unverified') { this.closeComparison(); return }
      if (this.comparisonOpen && this.comparisonPair) return this.retryComparison()
      return this.fetchData()
    },
    queryKey () { this.readQuery() }
  },
  created () { this.readQuery(); this.fetchData() },
  beforeUnmount () { this.disposed = true; this.reset() },
  methods: {
    reset () { this.generation++; this.rows = []; this.limited = false; this.warnings = 0; this.incomplete = false; this.pending = false; this.error = ''; this.selected = []; this.closeComparison() },
    readQuery () {
      const q = this.$route.query
      this.keyword = typeof q.cnihistoryq === 'string' ? q.cnihistoryq.slice(0, 1024) : ''; this.draftKeyword = this.keyword
      this.page = Number.isSafeInteger(Number(q.cnihistorypage)) && Number(q.cnihistorypage) > 0 ? Number(q.cnihistorypage) : 1
      this.pagesize = [10, 20, 50].includes(Number(q.cnihistorysize)) ? Number(q.cnihistorysize) : 20
    },
    saveQuery (values) { return this.$router.push({ path: this.$route.path, query: { ...this.$route.query, tab: tabName, ...values } }) },
    search (value) { this.selected = []; this.saveQuery({ cnihistoryq: value || undefined, cnihistorypage: '1' }) },
    changePage (page, size) { this.saveQuery({ cnihistorypage: String(size === this.pagesize ? page : 1), cnihistorysize: String(size) }) },
    resourceLink (id) { return { path: '/cniconfiguration/' + id, query: { ...this.$route.query, tab: tabName, cnihistorypage: '1', ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}) } } },
    errorKey (error) {
      if ([401, 403, 432, 531].includes(cniDeleteError(error).code)) return 'label.forbidden'
      return error.historyState && error.historyState !== 'cancelled' ? 'message.cni.history.' + error.historyState : 'message.cni.history.error'
    },
    async fetchData () {
      if (!this.active || this.disposed || this.pending) return
      if (!this.allowed) { this.reset(); this.error = 'label.forbidden'; return }
      if (!this.parentReady) return
      const source = Object.freeze({ ...this.resource }); const project = this.$store.getters.project?.id
      if (project && project !== '-1' && project !== source.projectid) { this.reset(); this.error = 'message.cni.history.identity'; return }
      const generation = ++this.generation; const scope = this.scope
      const current = () => !this.disposed && this.active && this.parentReady && this.allowed && generation === this.generation && scope === this.scope
      this.pending = true; this.error = ''; this.closeComparison()
      try {
        const result = await readCniHistory(getAPI, source, current)
        if (!current()) return
        this.rows = result.rows; this.limited = result.limited; this.warnings = result.warnings; this.incomplete = result.incomplete; this.selected = this.selected.filter(id => result.rows.some(row => row.id === id && row.available && row.state !== 'cycle'))
        const last = Math.max(1, Math.ceil(this.filteredRows.length / this.pagesize))
        if (this.page > last) this.changePage(last, this.pagesize)
      } catch (error) {
        if (!current()) return
        this.error = this.errorKey(error)
        if (['label.forbidden', 'message.cni.history.identity', 'message.cni.history.unavailable'].includes(this.error)) this.rows = []
      } finally { if (current()) this.pending = false }
    },
    closeComparison () { this.comparisonGeneration++; this.comparisonOpen = false; this.comparing = false; this.comparison = null; this.comparisonError = ''; this.comparisonPair = null },
    closeDialog () { this.closeComparison(); this.fetchData() },
    compareSelected () { const rows = this.rows.filter(r => this.selected.includes(r.id) && r.available && r.state !== 'cycle'); if (this.canCompareSelection && rows.length === 2) this.openComparison(rows[0].row, rows[1].row) },
    retryComparison () { if (this.comparisonPair) return this.openComparison(...this.comparisonPair) },
    async openComparison (left, right, expectedRecord = null) {
      if (!left || !right || !this.active || !this.allowed || !this.parentReady || this.pending || this.comparing || this.error) return
      const generation = ++this.comparisonGeneration; const scope = this.scope; const source = Object.freeze({ ...this.resource })
      const current = () => !this.disposed && this.active && this.allowed && this.parentReady && generation === this.comparisonGeneration && scope === this.scope
      const keep = this.comparison && this.comparison.left.id === left.id && this.comparison.right.id === right.id
      this.comparisonPair = [left, right, expectedRecord]; this.comparisonOpen = true; this.comparing = true; if (!keep) this.comparison = null; this.comparisonError = ''
      try { const result = await readCniComparison(getAPI, source, left, right, current, expectedRecord); if (current()) this.comparison = result } catch (error) { if (current()) { this.comparison = null; this.comparisonError = this.errorKey(error) } } finally { if (current()) this.comparing = false }
    }
  }
}
</script>
<style scoped>
.cni-history { display: flex; flex-direction: column; gap: 16px; min-width: 0; color: var(--ui-text-primary); }
.cni-history-search { width: 240px; max-width: 100%; margin-left: auto; }
.cni-history-table { min-width: 0; }
.cni-history :deep(td) { overflow-wrap: anywhere; }
.cni-history :deep(.ant-tag) { margin-left: 6px; }
.cni-comparison-dialog :deep(.ant-descriptions-item-content) { min-width: 0; overflow-wrap: anywhere; }
.cni-comparison-bodies { min-width: 0; margin: 0; padding: 0; border: 0; }
.cni-diff-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--ui-text-primary); }
.cni-diff-removed { color: var(--ui-error-text); }
.cni-diff-added { color: var(--ui-success-text); }
.cni-comparison-dialog :deep(.content-code) { max-height: 30vh; }
.cni-comparison-dialog summary { cursor: pointer; }
</style>
