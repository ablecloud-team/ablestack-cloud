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
  <div class="network-offering-usage themed-list-toolbar">
    <div class="usage-toolbar autogen-toolbar-actions">
      <router-link :to="location"><a-button type="primary" :disabled="!allowed">{{ $t('label.network.offering.used.networks.view') }}</a-button></router-link>
      <a-tooltip :title="$t('label.refresh')"><a-button :aria-label="$t('label.refresh')" :loading="loading" :disabled="!allowed" @click="load"><template #icon><reload-outlined /></template></a-button></a-tooltip>
    </div>
    <a-alert type="info" show-icon :message="$t('message.network.offering.usage.scope')" />
    <a-alert type="info" show-icon :message="$t('message.offering.usage.lifecycle')" />
    <a-alert v-if="error" type="error" show-icon :message="$t(loaded ? 'message.network.offering.usage.stale' : 'error.fetching.data')"><template #description><a-button size="small" @click="load">{{ $t('label.retry') }}</a-button></template></a-alert>
    <p v-if="loaded && !error" class="usage-count">{{ $t('message.network.offering.usage.count', [total]) }}</p>
    <a-table
      size="small"
      row-key="id"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :scroll="{ x: 650 }"
      :pagination="loaded ? pagination : false"
      :locale="{ emptyText: $t(error ? 'error.fetching.data' : loaded ? 'label.no.data' : 'label.loading') }"
      @change="changePage">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'"><router-link :to="detail(record)">{{ record.name }}</router-link></template>
        <template v-else-if="column.key === 'state'"><status :text="record.state" display-text /></template>
        <template v-else-if="column.key === 'owner'">{{ record.project || record.account }}</template>
      </template>
    </a-table>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
import { offeringNetworkQuery, networkDetailLocation, readNetworkPage } from '@/utils/guestNetworkSearch'
export default {
  name: 'NetworkOfferingUsage',
  components: { Status },
  props: { resource: { type: Object, required: true } },
  data () { return { rows: [], total: 0, page: 1, size: 20, loading: false, loaded: false, error: false, version: 0, disposed: false } },
  computed: {
    allowed () { return !!this.$store.state?.user?.token && !!this.resource.id && 'listNetworks' in (this.$store.getters.apis || {}) },
    query () { return offeringNetworkQuery(this.resource, this.$route, this.$store.getters) },
    location () { return { path: '/guestnetwork', query: this.query } },
    context () { return JSON.stringify([this.query, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.domainid, this.$store.state?.user?.token, this.allowed]) },
    columns () { return ['name', 'state', 'type', 'vpcname', 'owner', 'zonename'].map(k => ({ key: k, dataIndex: k, title: k === 'owner' ? this.$t('label.account') + ' / ' + this.$t('label.project') : this.$t('label.' + (k === 'vpcname' ? 'vpc' : k)) })) },
    pagination () { return { current: this.page, pageSize: this.size, total: this.total, showSizeChanger: true, disabled: this.loading || this.error, showTotal: n => this.$t('label.total') + ' ' + n } }
  },
  watch: { context: { immediate: true, handler () { this.version++; this.rows = []; this.total = 0; this.loaded = false; this.page = 1; this.load() } } },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    detail (row) { return networkDetailLocation({ query: { ...this.query, page: String(this.page), pagesize: String(this.size) } }, row) },
    changePage (p) { this.page = p.pageSize !== this.size ? 1 : p.current; this.size = p.pageSize; this.rows = []; this.loaded = false; this.load() },
    async load () {
      const version = ++this.version; const context = this.context
      const current = () => !this.disposed && version === this.version && context === this.context
      this.error = false; this.loading = true
      if (!this.allowed) { this.rows = []; this.total = 0; this.loaded = false; this.loading = false; return }
      const params = { ...this.query, listall: true, page: this.page, pagesize: this.size }
      try {
        const json = await getAPI('listNetworks', params, { preserveOnFailure: true, timeout: 20000 })
        if (!current()) return
        const result = readNetworkPage(json, 'listNetworks', 'network', params)
        this.rows = result.rows; this.total = result.count; this.loaded = true
      } catch (e) {
        if (!current()) return
        const code = Number(Object.values(e?.response?.data || {}).find(v => v?.errorcode)?.errorcode || e?.response?.status)
        if ([401, 403, 431, 432, 531].includes(code)) { this.rows = []; this.total = 0; this.loaded = false }
        this.error = true
      } finally { if (current()) this.loading = false }
    }
  }
}
</script>
<style scoped src="@/style/components/view/list-toolbar.css" />
<style scoped>
.network-offering-usage { min-width: 0; }
.usage-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }
.network-offering-usage :deep(.ant-alert) { margin-bottom: 12px; }
.usage-count { color: var(--ui-text-secondary); }
.network-offering-usage :deep(.ant-table-cell) { overflow-wrap: anywhere; }
</style>
