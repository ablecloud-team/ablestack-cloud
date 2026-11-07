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
  <div class="guest-ip-ranges">
    <div class="range-toolbar">
      <a-button v-if="allowed('createVlanIpRange')" type="primary" :disabled="busy || editorBusy || !ready" @click="openCreate"><template #icon><plus-outlined /></template>{{ $t('label.add.ip.range') }}</a-button>
      <a-tooltip :title="$t('label.refresh')"><a-button shape="circle" :aria-label="$t('label.refresh')" :loading="fetchLoading || listRefreshing > 0" :disabled="busy || editorBusy || !canRead" @click="fetchData"><template #icon><reload-outlined /></template></a-button></a-tooltip>
    </div>
    <a-alert v-if="!canRead" class="range-notice" type="warning" show-icon :message="$t('message.guest.range.unsupported')" />
    <a-alert v-else-if="listRefreshFailed" class="range-notice" type="warning" show-icon :message="$t(loaded ? 'message.list.refresh.stale' : 'message.guest.range.read.failed')" />
    <a-table
v-if="canRead"
size="small"
:columns="columns"
:data-source="ipranges"
row-key="id"
:loading="fetchLoading"
:pagination="false"
:scroll="{ x: 940 }"
:locale="{ emptyText: $t(listRefreshFailed ? 'message.guest.range.read.failed' : 'label.no.data') }">
      <template #bodyCell="{column, record}">
        <template v-if="column.key === 'ipv4'"><div class="range-address">{{ record.startip || '—' }}<template v-if="record.endip"> – {{ record.endip }}</template></div><div v-if="record.startip && record.gateway" class="range-meta">{{ $t('label.gateway') }}: {{ record.gateway }} · {{ record.netmask }}</div></template>
        <template v-else-if="column.key === 'ipv6'"><div class="range-address">{{ record.startipv6 || '—' }}<template v-if="record.endipv6"> – {{ record.endipv6 }}</template></div><div v-if="record.ip6gateway" class="range-meta">{{ $t('label.gateway') }}: {{ record.ip6gateway }}</div><div v-if="record.ip6cidr" class="range-meta">{{ record.ip6cidr }}</div></template>
        <template v-else-if="column.key === 'actions'"><div class="range-row-actions">
          <a-tooltip :title="$t('label.edit')"><a-button v-if="allowed('updateVlanIpRange')" size="small" :aria-label="$t('label.edit') + ' ' + rangeLabel(record)" :disabled="busy || editorBusy || !ready" @click="openUpdate(record)"><template #icon><edit-outlined /></template></a-button></a-tooltip>
          <a-tooltip :title="$t('label.action.delete.ip.range')"><a-button v-if="allowed('deleteVlanIpRange')" size="small" danger :aria-label="$t('label.action.delete.ip.range') + ' ' + rangeLabel(record)" :disabled="busy || editorBusy || !ready" @click="openDelete(record)"><template #icon><delete-outlined /></template></a-button></a-tooltip>
        </div></template>
      </template>
    </a-table>
    <a-pagination
v-if="canRead && total"
class="range-pagination"
size="small"
:current="page"
:page-size="pageSize"
:total="total"
:show-total="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
:page-size-options="['10','20','40','80','100']"
show-size-changer
:disabled="busy || editorBusy"
@change="changePage" />
    <MoldDialog
v-if="editor"
:visible="true"
size="lg"
form-actions
focus-guard
:title="$t(selectedItem ? 'label.update.ip.range' : 'label.add.ip.range')"
:closable="!editorBusy"
:keyboard="!editorBusy"
@cancel="!editorBusy && closeEditor()">
      <CreateVlanIpRange :resource="resource" :range="selectedItem" :defaults="rangeDefaults" :writable="ready" @busy="editorBusy = $event" @close-action="closeEditor" @refresh-data="fetchData" />
    </MoldDialog>
    <MoldDialog
v-if="deleteItem"
:visible="true"
size="sm"
focus-guard
:title="$t('label.action.delete.ip.range')"
:closable="!busy"
:keyboard="!busy"
@cancel="!busy && (deleteItem = null)">
      <p>{{ resource.name }} · {{ rangeLabel(deleteItem) }}</p>
      <a-alert type="warning" show-icon :message="$t('message.confirm.remove.ip.range')" />
      <a-alert v-if="deleteError" class="range-notice delete-error" :type="deletePosted ? 'warning' : 'error'" show-icon :message="deleteError" />
      <template #footer><a-button :disabled="busy" @click="deleteItem = null">{{ $t('label.cancel') }}</a-button><a-button type="primary" danger :loading="busy" :disabled="busy || !allowed('deleteVlanIpRange')" @click="removeIpRange">{{ $t(deletePosted ? 'label.guest.range.check' : 'label.yes') }}</a-button></template>
    </MoldDialog>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import MoldDialog from '@/components/view/MoldDialog'
import CreateVlanIpRange from '@/views/network/CreateVlanIpRange'
import { currentRangeNetwork, guestIpRangeVisible, rangeQuery, rangeProject, rangeScope, rangeSignature, rangeError, listAllGuestRanges } from '@/utils/guestIpRanges'

export default {
  name: 'GuestIpRanges',
  components: { MoldDialog, CreateVlanIpRange },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => vm.canRead && !vm.editor && !vm.deleteItem, scopeKey: vm => [vm.scopeKey, vm.page, vm.pageSize] })],
  props: { resource: { type: Object, required: true }, loading: { type: Boolean, default: false } },
  data () { return { fetchLoading: false, loaded: false, ipranges: [], total: 0, page: 1, pageSize: 10, editor: false, editorBusy: false, selectedItem: null, deleteItem: null, deleteError: '', deletePosted: false, busy: false } },
  computed: {
    canRead () { return !this.loading && !!this.resource.id && guestIpRangeVisible(this.resource, this.$store.getters.apis) },
    scopeKey () { return rangeScope(this.resource, this.$store.getters, this.$store.state?.user?.token) },
    ready () { return this.canRead && this.loaded && !this.listRefreshFailed && !this.fetchLoading && this.listRefreshing === 0 },
    rangeDefaults () { const v4 = this.ipranges.find(r => r.startip); const v6 = this.ipranges.find(r => r.ip6cidr); return { gateway: v4?.gateway, netmask: v4?.netmask, ip6gateway: v6?.ip6gateway, ip6cidr: v6?.ip6cidr } },
    columns () { return [{ key: 'ipv4', title: 'IPv4', width: 330 }, { key: 'ipv6', title: 'IPv6', width: 410 }, { key: 'vlan', dataIndex: 'vlan', title: this.$t('label.vlan'), width: 100 }, { key: 'actions', title: this.$t('label.actions'), width: 100, fixed: 'right' }] }
  },
  watch: {
    scopeKey () { this.resetScope(); this.fetchData() },
    loading (value) { if (!value) this.fetchData() }
  },
  created () { this.fetchData() },
  methods: {
    allowed (api) { const apis = this.$store.getters.apis || {}; return this.canRead && 'listNetworks' in apis && api in apis },
    closeEditor () { this.editor = false; this.editorBusy = false },
    rangeLabel (row) { return [row.startip ? `${row.startip}–${row.endip || row.startip}` : '', row.startipv6 ? `${row.startipv6}–${row.endipv6 || row.startipv6}` : ''].filter(Boolean).join(' / ') },
    resetScope () { this.ipranges = []; this.total = 0; this.page = 1; this.loaded = false; this.fetchLoading = false; this.listRefreshFailed = false; this.editor = false; this.editorBusy = false; this.selectedItem = null; this.deleteItem = null; this.busy = false; this.deletePosted = false; this.deleteError = '' },
    async fetchData () {
      const request = this.listRequestToken('fetchData')
      if (!this.canRead) return
      this.fetchLoading = !this.loaded
      try {
        const json = await getAPI('listVlanIpRanges', { ...rangeQuery(this.resource), page: this.page, pagesize: this.pageSize }, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchData', request)) return
        const body = json?.listvlaniprangesresponse
        if (!body || (body.vlaniprange !== undefined && !Array.isArray(body.vlaniprange))) throw new Error('message.guest.range.read.failed')
        const rows = body.vlaniprange || []
        if (rows.some(row => !row.id || row.networkid !== this.resource.id)) throw new Error('message.guest.range.read.failed')
        this.total = Number(body.count ?? rows.length); this.ipranges = rows; this.loaded = true
        if (!rows.length && this.total && this.page > Math.ceil(this.total / this.pageSize)) { this.page = Math.max(1, Math.ceil(this.total / this.pageSize)); return this.fetchData() }
      } catch (_) {
        if (!this.isListRequestCurrent('fetchData', request)) return
        request.failed = true; this.listRefreshFailed = true
      } finally { if (this.isListRequestCurrent('fetchData', request)) this.fetchLoading = false }
    },
    openCreate () { if (!this.ready || !this.allowed('createVlanIpRange') || this.busy) return; this.selectedItem = null; this.editor = true },
    openUpdate (row) { if (!this.ready || !this.allowed('updateVlanIpRange') || this.busy) return; this.selectedItem = { ...row }; this.editor = true },
    openDelete (row) { if (!this.ready || !this.allowed('deleteVlanIpRange') || this.busy) return; this.deleteItem = { ...row }; this.deleteError = ''; this.deletePosted = false },
    async removeIpRange () {
      if (this.busy || !this.deleteItem || !this.allowed('deleteVlanIpRange')) return
      const scope = this.scopeKey; const item = { ...this.deleteItem }
      const current = () => this.scopeKey === scope && !this.listRefreshDisposed
      this.busy = true; this.deleteError = ''
      try {
        await currentRangeNetwork(getAPI, this.resource)
        const rows = await listAllGuestRanges(getAPI, this.resource)
        if (!current() || !this.allowed('deleteVlanIpRange')) return
        const row = rows.find(row => row.id === item.id)
        if (!row) { this.deleteItem = null; await this.fetchData(); return }
        if (this.deletePosted) throw new Error('message.guest.range.verify.failed')
        if (rangeSignature(row) !== rangeSignature(item)) throw new Error('message.guest.range.scope.changed')
        this.deletePosted = true
        try { await postAPI('deleteVlanIpRange', { id: item.id, ...rangeProject(this.resource) }, { preserveOnFailure: true }) } catch (error) { if (error.response && current()) this.deletePosted = false; throw error }
        if (!current()) return
        const after = await listAllGuestRanges(getAPI, this.resource)
        if (!current()) return
        if (after.some(row => row.id === item.id)) throw new Error('message.guest.range.verify.failed')
        this.$message.success(this.$t('message.success.delete')); this.deleteItem = null; await this.fetchData()
      } catch (error) { if (current()) this.deleteError = rangeError(error, this.$t) } finally { if (current()) this.busy = false }
    },
    changePage (page, size) { this.page = size !== this.pageSize ? 1 : page; this.pageSize = size; this.ipranges = []; this.loaded = false; this.fetchData() }
  }
}
</script>
<style scoped>
.guest-ip-ranges { min-width: 0; }
.range-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.range-notice { margin-bottom: 12px; }
.delete-error { margin-top: 12px; }
.range-address, .range-meta { overflow-wrap: anywhere; }
.range-meta { margin-top: 4px; font-size: 12px; }
.range-row-actions { display: flex; gap: 8px; }
.range-pagination { margin-top: 12px; text-align: right; }
</style>
