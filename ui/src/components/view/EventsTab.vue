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
  <div class="events-tab" data-testid="events-tab">
    <div class="detail-tab-toolbar">
      <a-button :loading="tabLoading || listRefreshing > 0" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-dropdown :trigger="['click']">
        <a-button :aria-label="$t('label.select.columns')"><template #icon><setting-outlined /></template>{{ $t('label.select.columns') }}</a-button>
        <template #overlay><a-menu><a-menu-item v-for="key in columnKeys" :key="key" @click="updateSelectedColumns(key)"><a-checkbox :checked="selectedColumnKeys.includes(key)" /> {{ $t('label.' + key) }}</a-menu-item></a-menu></template>
      </a-dropdown>
      <a-input-search v-model:value="eventSearch" class="detail-tab-search" :placeholder="$t('label.search')" :aria-label="$t('label.detail.tab.events.search')" allow-clear @search="searchEvents" />
    </div>
    <a-alert v-if="isKubernetesCluster" class="events-order-help" type="info" show-icon :message="$t('message.cks.events.order')" :description="$t('message.cks.events.record.created')" />
    <a-alert v-if="readError" class="detail-tab-notice" type="error" show-icon :message="$t(readDenied ? 'message.detail.tab.denied' : loadedContext ? 'message.list.refresh.stale' : 'error.fetching.data')">
      <template #description><p>{{ readError.startsWith('message.') ? $t(readError) : readError }}</p><a-button size="small" :disabled="tabLoading || listRefreshing > 0" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <list-view
v-if="events.length || (!tabLoading && !readError)"
:loading="tabLoading"
:columns="columns"
:table-scroll="{ x: 980 }"
:items="events"
:actions="actions"
:columnKeys="columnKeys"
:selectedColumns="selectedColumnKeys"
ref="listview"
@update-selected-columns="updateSelectedColumns"
@refresh="fetchData" />
    <a-pagination
v-if="loadedContext"
class="detail-tab-pagination"
size="small"
:current="page"
:pageSize="pageSize"
:total="totalCount"
:showTotal="(total, range) => $t('message.detail.tab.total', [total, range[0], range[1]])"
:pageSizeOptions="pageSizeOptions"
@change="changePage"
@showSizeChange="changePageSize"
showSizeChanger
showQuickJumper>
      <template #buildOptionText="props"><span>{{ props.value }} / {{ $t('label.page') }}</span></template>
    </a-pagination>
  </div>
</template>
<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { detailTabContext, detailTabDenied, detailTabError, readDetailTabPage } from '@/utils/detailTabData'
import { getAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import ListView from '@/components/view/ListView'
const EVENTS_TAB_COLUMNS_KEY = 'events_tab_columns'
export default {
  mixins: [listRefreshMixin(['fetchEvents'], { routeKey: vm => detailTabContext(vm, ['listEvents']) })],
  name: 'EventsTab',
  components: { ListView },
  props: { resource: { type: Object, required: true }, resourceType: { type: String, required: true }, loading: { type: Boolean, required: true } },
  data () { return { tabLoading: false, columnKeys: ['level', 'type', 'state', 'description', 'username', 'account', 'domain', 'created'], selectedColumnKeys: [], columns: [], events: [], actions: [], page: 1, pageSize: 20, totalCount: 0, eventSearch: '', keyword: '', loadedContext: '', readError: '', readDenied: false } },
  computed: {
    eventContext () { return detailTabContext(this, ['listEvents']) },
    isKubernetesCluster () { return this.resourceType === 'KubernetesCluster' },
    pageSizeOptions () { return [...new Set([10, 20, 50, 100, 200, this.$store.getters.defaultListViewPageSize].filter(n => Number.isInteger(n) && n > 0))].sort((a, b) => a - b).map(String) }
  },
  created () { this.setDefaultColumns(); this.pageSize = Number(this.pageSizeOptions[0]); this.fetchData() },
  watch: {
    eventContext () { this.events = []; this.totalCount = 0; this.loadedContext = ''; this.readError = ''; this.readDenied = false; this.page = 1; this.keyword = ''; this.eventSearch = ''; this.resetEventSelection(); this.fetchEvents() },
    resource () { this.fetchEvents() }
  },
  methods: {
    resetEventSelection () { if (typeof this.$refs?.listview?.resetSelection === 'function') this.$refs.listview.resetSelection() },
    setDefaultColumns () { const saved = this.$localStorage.get(EVENTS_TAB_COLUMNS_KEY); this.selectedColumnKeys = Array.isArray(saved) ? this.columnKeys.filter(key => saved.includes(key)) : []; if (!this.selectedColumnKeys.length) this.selectedColumnKeys = this.columnKeys.filter(key => key !== 'description'); this.updateColumns() },
    fetchData () { return this.fetchEvents() },
    async fetchEvents () {
      const request = this.listRequestToken('fetchEvents')
      if (!this.resource.id || !('listEvents' in this.$store.getters.apis)) { this.events = []; this.totalCount = 0; this.loadedContext = ''; this.tabLoading = false; return }
      const context = this.eventContext
      this.tabLoading = !request.loaded
      if (!request.loaded) { this.events = []; this.totalCount = 0; this.loadedContext = '' }
      const params = { page: this.page, pagesize: this.pageSize, resourceid: this.resource.id, resourcetype: this.resourceType, listall: true, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}), ...(this.keyword ? { keyword: this.keyword } : {}) }
      try {
        const json = await getAPI('listEvents', params, { preserveOnFailure: true, timeout: 20000 })
        if (!this.isListRequestCurrent('fetchEvents', request)) return
        const result = readDetailTabPage(json, 'listeventsresponse', 'event', { page: this.page, pageSize: this.pageSize, resourceId: this.resource.id })
        if (this.page > result.lastPage) { this.page = result.lastPage; return this.fetchEvents() }
        this.events = result.rows; this.totalCount = result.count; this.loadedContext = context; this.readError = ''; this.readDenied = false; this.listRefreshFailed = false
      } catch (error) {
        if (!this.isListRequestCurrent('fetchEvents', request)) return
        request.failed = true; this.listRefreshFailed = true; this.readError = detailTabError(error); this.readDenied = detailTabDenied(error)
        if (!request.loaded || this.readDenied) { this.events = []; this.totalCount = 0; this.loadedContext = ''; this.resetEventSelection() }
      } finally { if (this.isListRequestCurrent('fetchEvents', request)) this.tabLoading = false }
    },
    searchEvents () { const keyword = this.eventSearch.trim(); if (keyword === this.keyword && this.page === 1) return this.fetchEvents(); this.keyword = keyword; this.page = 1; this.events = []; this.totalCount = 0; this.loadedContext = ''; this.readError = ''; return this.fetchEvents() },
    changePage (page, pageSize) { this.page = pageSize !== this.pageSize ? 1 : page; this.pageSize = pageSize; return this.fetchData() },
    changePageSize (page, pageSize) { this.page = 1; this.pageSize = pageSize; return this.fetchData() },
    updateSelectedColumns (key) { if (!this.columnKeys.includes(key)) return; if (this.selectedColumnKeys.includes(key)) { if (this.selectedColumnKeys.length === 1) return; this.selectedColumnKeys = this.selectedColumnKeys.filter(x => x !== key) } else this.selectedColumnKeys.push(key); this.$localStorage.set(EVENTS_TAB_COLUMNS_KEY, this.selectedColumnKeys); this.updateColumns() },
    updateColumns () { this.columns = this.columnKeys.filter(key => this.selectedColumnKeys.includes(key)).map(key => ({ key, title: this.$t('label.' + key), dataIndex: key, width: { level: 70, type: 170, state: 90, description: 280, username: 120, account: 130, domain: 120, created: 180 }[key], sorter: (a, b) => genericCompare(a[key] || '', b[key] || ''), ...(this.isKubernetesCluster && key === 'created' ? { defaultSortOrder: 'descend' } : {}), ...(this.isKubernetesCluster && key === 'state' ? { stateLabel: record => record.state === 'Created' ? this.$t('state.cks.eventrecord.created') : undefined } : {}) })) }
  }
}
</script>
<style scoped lang="scss">
@import '@/style/components/view/DetailTab.scss';
.events-order-help { margin-bottom: 16px; }
</style>
