// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div class="s2s-connections">
    <div class="s2s-connections-toolbar">
      <a-button :loading="pending" :disabled="!allowed" @click="load"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="search" :aria-label="$t('label.search')" :placeholder="$t('label.search')" allow-clear />
    </div>
    <a-alert v-if="error" type="error" show-icon :message="$t(error)" role="alert" />
    <detail-tab-table :columns="columns" :data-source="filteredRows" row-key="id" :loading="pending && !rows.length" :scroll="{ x: 760 }" :pagination="{ pageSize: 10, showSizeChanger: false }" :locale="{ emptyText: $t(error || 'label.no.data') }">
      <template #bodyCell="{ column, record, text }">
        <router-link v-if="column.key === 'publicip'" :to="{ path: '/s2svpnconn/' + record.id, ...(resource.projectid ? { query: { projectid: resource.projectid } } : {}) }">{{ text || record.id }}</router-link>
        <status v-else-if="column.key === 'state' && text" :text="text" display-text />
        <span v-else-if="column.key === 'created'">{{ text ? $toLocaleDate(text) : '-' }}</span>
        <span v-else>{{ text || '-' }}</span>
      </template>
    </detail-tab-table>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
import { gatewayContext, gatewayOwner, gatewayRows, gatewayConnectionRows } from '@/utils/s2sGateway'
export default {
  name: 'S2sVpnConnectionsTab',
  components: { Status },
  props: { resource: { type: Object, required: true }, loading: Boolean, tab: String },
  data () { return { rows: [], search: '', pending: false, error: '', generation: 0, disposed: false } },
  computed: {
    scope () { return gatewayContext(this) },
    allowed () { return this.$route.meta?.name === 's2svpn' && this.resource.id === this.$route.params?.id && !!this.resource.id && !!this.resource.vpcid && !!this.$store.getters.userInfo?.id && !!this.$store.state?.user?.token && 'listVpnConnections' in (this.$store.getters.apis || {}) },
    columns () { return ['publicip', 'gateway', 'state', 'created'].map(key => ({ key, dataIndex: key, title: this.$t('label.' + key), width: key === 'created' ? 220 : 180 })) },
    filteredRows () { const query = this.search.trim().toLowerCase(); return this.rows.filter(row => !query || [row.id, row.publicip, row.gateway, row.state].some(value => String(value || '').toLowerCase().includes(query))) }
  },
  watch: {
    scope: { flush: 'sync', handler () { this.generation++; this.rows = []; this.search = ''; this.pending = false; this.error = ''; this.load() } },
    loading (value, previous) { if (previous && !value && this.tab === 'vpn.connections') this.load() },
    tab (value) { if (value === 'vpn.connections') this.load() }
  },
  created () { this.load() },
  beforeUnmount () { this.disposed = true; this.generation++ },
  methods: {
    async load () {
      if (this.disposed || this.pending) return
      if (!this.allowed) { this.error = 'message.s2s.gateway.permission'; this.rows = []; return }
      const scope = this.scope; const generation = ++this.generation
      const current = () => !this.disposed && scope === this.scope && generation === this.generation
      this.pending = true; this.error = ''
      try {
        const rows = await gatewayRows(getAPI, 'listVpnConnections', 'vpnconnection', { ...gatewayOwner(this.resource), vpcid: this.resource.vpcid }, current)
        if (current()) this.rows = gatewayConnectionRows(rows, this.resource)
      } catch (error) {
        if (current()) {
          const denied = [401, 403, 531].includes(Number(error?.response?.status))
          if (denied) this.rows = []
          this.error = denied ? 'message.s2s.gateway.permission' : error?.message?.startsWith('message.s2s.') ? error.message : 'message.s2s.gateway.lookup.failed'
        }
      } finally { if (current()) this.pending = false }
    }
  }
}
</script>
<style scoped>
.s2s-connections { min-width: 0; }
.s2s-connections-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.s2s-connections-toolbar :deep(.ant-input-search) { width: 250px; max-width: 100%; }
.ant-alert { margin-bottom: 16px; }
</style>
