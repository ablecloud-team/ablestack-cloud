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
  <section class="object-store-buckets" :aria-label="$t('label.objectstore.buckets')">
    <div class="detail-tab-toolbar bucket-related-toolbar">
      <a-button type="primary" :loading="loading" :disabled="disabled || !canRead" @click="load"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <div class="detail-tab-toolbar-context"><a-input-search v-model:value="draft" allow-clear :disabled="disabled || !canRead" :aria-label="$t('label.objectstore.buckets.search')" :placeholder="$t('label.search')" @search="search" /></div>
    </div>
    <p class="bucket-related-note">{{ $t('message.objectstore.buckets.scope') }}</p>
    <a-alert v-if="error" show-icon type="error" :message="$t(loadedScope === queryScope ? 'message.objectstore.buckets.stale' : 'message.objectstore.buckets.failed')"><template #description><a-button :disabled="disabled" :loading="loading" @click="load">{{ $t('label.retry') }}</a-button></template></a-alert>
    <div class="bucket-related-table" role="region" tabindex="0" :aria-label="$t('label.objectstore.buckets')">
      <detail-tab-table
:enabled="detailViewport"
row-key="id"
size="middle"
:columns="columns"
:data-source="rows"
:loading="loading"
:pagination="false"
:scroll="{ x: 900 }"
:locale="{ emptyText: $t(error ? 'message.objectstore.buckets.failed' : total === null ? 'label.loading' : 'message.objectstore.buckets.empty') }">
        <template #bodyCell="{ column, record, text }">
          <router-link v-if="column.key === 'name'" :to="bucketLink(record)">{{ record.name }}</router-link>
          <Status v-else-if="column.key === 'state'" :text="record.state" display-text />
          <span v-else-if="column.key === 'size'">{{ record.size ?? '—' }} KiB</span>
          <span v-else-if="column.key === 'quota'">{{ record.quota ?? '—' }} GiB</span>
          <span v-else>{{ text || '—' }}</span>
        </template>
      </detail-tab-table>
    </div>
    <div class="bucket-related-footer">
      <span role="status" aria-live="polite">{{ $t('label.total') }}: {{ total === null ? $t('state.bucket.protection.unknown') : total }}</span>
      <detail-tab-pagination
:enabled="detailViewport"
:current="page"
:page-size="pagesize"
:total="total || 0"
:disabled="disabled || loading || !!error || total === null"
:page-size-options="['2', '10', '20', '50']"
show-size-changer
@change="changePage"
@showSizeChange="changePage" />
    </div>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
export default {
  name: 'ObjectStoreBuckets',
  components: { Status },
  props: { detailViewport: { type: Boolean, default: true }, resource: { type: Object, required: true }, disabled: Boolean },
  emits: ['state'],
  data () { return { rows: [], total: null, page: 1, pagesize: 10, draft: '', keyword: '', loading: false, error: false, generation: 0, loadedScope: '' } },
  computed: {
    canRead () { return this.$store.getters.userInfo?.roletype === 'Admin' && !!this.$store.getters.apis?.listBuckets },
    context () { return JSON.stringify([this.resource.id, this.canRead, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roleid, this.$store.state?.user?.token, this.$store.getters.project?.id]) },
    queryScope () { return JSON.stringify([this.context, this.page, this.pagesize, this.keyword]) },
    columns () { return ['name', 'state', 'size', 'quota', 'account', 'project', 'domain'].map(key => ({ key, dataIndex: key, title: this.$t(key === 'size' ? 'label.used.capacity' : key === 'quota' ? 'label.total.allocated.capacity' : 'label.' + key) })) }
  },
  watch: { context: { immediate: true, handler () { this.rows = []; this.total = null; this.loadedScope = ''; this.page = 1; this.keyword = ''; this.draft = ''; this.load() } } },
  beforeUnmount () { ++this.generation },
  methods: {
    report () { this.$emit('state', { id: this.resource.id, known: this.loadedScope === this.queryScope && !this.loading && !this.error && !this.keyword && this.canRead, total: this.total, error: this.error, loading: this.loading }) },
    bucketLink (row) { return { path: '/buckets/' + row.id, query: row.projectid ? { projectid: row.projectid } : {} } },
    search () { this.keyword = this.draft.trim(); this.page = 1; this.load() },
    changePage (page, size) { this.page = size !== this.pagesize ? 1 : page; this.pagesize = size; this.load() },
    async load () {
      const generation = ++this.generation; const scope = this.queryScope
      if (!this.resource.id || !this.canRead) { this.loading = false; this.error = true; this.rows = []; this.total = null; this.report(); return }
      if (this.loadedScope !== scope) { this.rows = []; this.total = null; this.loadedScope = '' }
      this.loading = true; this.error = false; this.report()
      try {
        const response = await getAPI('listBuckets', { objectstorageid: this.resource.id, listall: true, projectid: '-1', ignoreproject: true, page: this.page, pagesize: this.pagesize, ...(this.keyword ? { keyword: this.keyword } : {}) }, { preserveOnFailure: true, timeout: 20000 })
        if (generation !== this.generation || scope !== this.queryScope) return
        const body = response.listbucketsresponse
        if (!body || typeof body !== 'object' || body.errorcode) throw new Error('Invalid bucket response')
        const rows = body.bucket || []; const total = Number(body.count ?? (Object.keys(body).length === 0 ? 0 : NaN))
        if (!Array.isArray(rows) || !Number.isInteger(total) || total < rows.length) throw new Error('Invalid bucket count')
        if (rows.some(row => row.objectstorageid !== this.resource.id)) { const failure = new Error('Bucket scope mismatch'); failure.scopeMismatch = true; throw failure }
        const lastPage = Math.max(1, Math.ceil(total / this.pagesize))
        if (this.page > lastPage) { this.page = lastPage; return this.load() }
        this.rows = rows; this.total = total; this.loadedScope = scope
      } catch (error) {
        if (generation !== this.generation || scope !== this.queryScope) return
        this.error = true
        const code = Number(Object.values(error.response?.data || {}).find(value => value?.errorcode)?.errorcode || error.response?.status)
        if (error.scopeMismatch || [401, 403, 431, 432, 531].includes(code)) { this.rows = []; this.total = null; this.loadedScope = '' }
      } finally { if (generation === this.generation) { this.loading = false; this.report() } }
    }
  }
}
</script>
<style scoped>
.object-store-buckets { min-width: 0; width: 100%; color: var(--ui-text-primary); }
.bucket-related-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.bucket-related-toolbar .ant-btn, .object-store-buckets .ant-alert .ant-btn { height: 32px; font-size: 14px; border-radius: 4px; }
.detail-tab-toolbar-context { margin-left: auto; width: 240px; max-width: 100%; }
.bucket-related-note { color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.object-store-buckets .ant-alert { margin-bottom: 16px; }
.bucket-related-table { min-width: 0; max-width: 100%; overflow-x: auto; }
.bucket-related-footer { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; margin-top: 16px; }
@media (max-width: 575px) { .detail-tab-toolbar-context { flex: 1 1 180px; min-width: 0; } }
</style>
