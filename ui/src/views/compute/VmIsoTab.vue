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
  <div class="vm-iso-tab">
    <div class="iso-toolbar">
      <a-tooltip :title="reason(true)"><span><a-button v-if="allowed('attachIso')" type="primary" :disabled="busy || loading || !!reason(true)" @click="openAttach"><template #icon><plus-outlined /></template>{{ $t('label.vmiso.attach') }}</a-button></span></a-tooltip>
      <a-tooltip :title="reason(false)"><span><a-button v-if="allowed('detachIso')" :disabled="busy || !selected.length || !!reason(false)" @click="openDetach(rows.filter(row => selected.includes(row.id)))">{{ $t('label.vmiso.detach.selected') }}</a-button></span></a-tooltip>
      <a-button :loading="loading" @click="fetchData">{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" :aria-label="$t('label.search')" />
    </div>
    <a-alert v-if="listRefreshFailed" class="iso-spacing" type="error" show-icon :message="$t('message.list.refresh.stale')" />
    <a-alert v-if="operation" class="iso-spacing" :type="operation.items.every(i => i.status === 'success') ? 'success' : 'info'" show-icon>
      <template #message><a @click="form = 'progress'">{{ $t('label.vmiso.progress') }}: {{ summary }}</a></template>
    </a-alert>
    <a-table
:columns="columns"
:data-source="filteredRows"
:row-selection="selection"
row-key="id"
size="small"
:loading="loading"
:pagination="{ pageSize: 10, hideOnSinglePage: true }"
      :scroll="{ x: 730 }"
      :locale="{ emptyText: $t(listRefreshFailed ? 'message.list.refresh.stale' : 'message.vmiso.empty') }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'"><router-link :to="'/iso/' + record.id" class="iso-name">{{ name(record) }}</router-link></template>
        <template v-else-if="column.key === 'device'">{{ slot(record) }}</template>
        <template v-else-if="column.key === 'media'">{{ $t(record.bootable === true ? 'label.bootable' : record.bootable === false ? 'label.vmiso.data' : 'label.vmiso.unspecified') }}</template>
        <template v-else-if="column.key === 'state'"><a-tag color="green">{{ $t('label.vmiso.attached') }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <div class="iso-row-actions">
            <a-tooltip :title="reason(false)"><span><a-button v-if="allowed('detachIso')" size="small" :disabled="busy || !!reason(false)" @click="openDetach([record])">{{ $t('label.vmiso.detach') }}</a-button></span></a-tooltip>
            <a-dropdown :trigger="['click']"><a-button size="small" :aria-label="name(record) + ' ' + $t('label.actions')"><template #icon><down-outlined /></template></a-button><template #overlay><a-menu><a-menu-item key="details" @click="detail = record; form = 'details'">{{ $t('label.details') }}</a-menu-item></a-menu></template></a-dropdown>
          </div>
        </template>
      </template>
    </a-table>
    <a-modal :visible="!!form" :title="dialogTitle" :width="760" centered wrap-class-name="vm-iso-modal" :mask-closable="false" @cancel="form = ''">
      <a-descriptions :column="1" size="small" bordered class="iso-spacing">
        <a-descriptions-item :label="$t('label.vm')">{{ vm.displayname || vm.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.zone')">{{ vm.zonename }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.account')">{{ vm.account }}</a-descriptions-item>
      </a-descriptions>
      <template v-if="form === 'attach'">
        <a-alert class="iso-spacing" type="info" show-icon :message="$t('message.vmiso.capacity', { count: capacity })" />
        <a-alert v-if="candidateError" class="iso-spacing" type="error" :message="candidateError" />
        <a-input-search v-model:value="candidateSearch" :placeholder="$t('label.search')" :aria-label="$t('label.search')" class="iso-spacing" />
        <a-spin :spinning="candidatesLoading">
          <a-empty v-if="!candidatesLoading && !filteredCandidates.length" :description="$t('message.vmiso.candidates.empty')" />
          <a-checkbox-group v-model:value="attachIds" class="iso-candidates">
            <label v-for="iso in filteredCandidates" :key="iso.id" class="iso-choice">
              <a-checkbox :value="iso.id" :disabled="attachIds.length >= capacity && !attachIds.includes(iso.id)" />
              <span><strong>{{ name(iso) }}</strong><span class="iso-note">{{ iso.name }}</span><span class="iso-note">{{ iso.zonename || vm.zonename }} · {{ $t(iso.bootable ? 'label.bootable' : 'label.vmiso.data') }}</span></span>
            </label>
          </a-checkbox-group>
        </a-spin>
        <p class="iso-note">{{ $t('message.vmiso.attach.help') }}</p>
      </template>
      <template v-else-if="form === 'detach'">
        <a-alert class="iso-spacing" type="warning" show-icon :message="$t('message.vmiso.detach.help')" />
        <div v-for="iso in targets" :key="iso.id" class="iso-result"><strong>{{ name(iso) }}</strong><a-tag>{{ slot(iso) }}</a-tag></div>
      </template>
      <a-form v-if="['attach', 'detach'].includes(form) && vm.hypervisor === 'VMware'" layout="vertical" class="iso-spacing"><a-form-item :label="$t('label.forced')"><a-switch v-model:checked="forced" /></a-form-item></a-form>
      <template v-if="form === 'progress' && operation">
        <a-alert class="iso-spacing" :type="operation.running ? 'info' : operation.items.every(i => i.status === 'success') ? 'success' : 'warning'" show-icon :message="summary" />
        <div v-for="item in operation.items" :key="item.id" class="iso-result">
          <div><strong>{{ name(item) }}</strong><p v-if="item.error" class="iso-note">{{ $te(item.error) ? $t(item.error) : item.error }}</p><p v-if="item.jobId" class="iso-note">{{ $t('label.vmiso.job') }}: {{ item.jobId }}</p></div>
          <a-tag :color="item.status === 'success' ? 'green' : item.status === 'failed' ? 'red' : 'blue'">{{ $t('label.vmiso.' + item.status) }}</a-tag>
        </div>
        <p class="iso-note">{{ $t('message.vmiso.results.help') }}</p>
      </template>
      <a-descriptions v-if="form === 'details' && detail" :column="1" bordered size="small">
        <a-descriptions-item :label="$t('label.name')">{{ name(detail) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.id')">{{ detail.id }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmiso.device')">{{ slot(detail) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">{{ $t('label.vmiso.attached') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmiso.media')">{{ $t(detail.bootable === true ? 'label.bootable' : detail.bootable === false ? 'label.vmiso.data' : 'label.vmiso.unspecified') }}</a-descriptions-item>
      </a-descriptions>
      <template #footer>
        <a-button @click="form = ''">{{ $t(['attach', 'detach'].includes(form) ? 'label.cancel' : 'label.close') }}</a-button>
        <a-button v-if="form === 'attach'" type="primary" :disabled="busy || candidatesLoading || !!candidateError || !attachIds.length || attachIds.length > capacity || !!reason(true)" @click="begin('attachIso', candidates.filter(i => attachIds.includes(i.id)))">{{ $t('label.vmiso.attach.count', { count: attachIds.length }) }}</a-button>
        <a-button v-if="form === 'detach'" type="primary" :disabled="busy || !!reason(false)" @click="begin('detachIso', targets)">{{ $t('label.vmiso.detach.count', { count: targets.length }) }}</a-button>
        <template v-if="form === 'progress' && operation && !operation.running">
          <a-button v-if="operation.items.some(i => i.status === 'unknown' && i.jobId)" type="primary" @click="operation.check()">{{ $t('label.vmiso.check') }}</a-button>
          <a-button v-if="!busy && operation.items.some(i => i.status === 'failed')" type="primary" @click="operation.retry()">{{ $t('label.vmiso.retry') }}</a-button>
        </template>
      </template>
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { attachedIsos, isoCapacity, isoActionReason, isoSlot, isoOperations, startIsoOperation } from '@/utils/vmIsoActions'
import eventBus from '@/config/eventBus'

export default {
  name: 'VmIsoTab',
  props: { resource: { type: Object, required: true } },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => !!vm.resource.id })],
  data () { return { vm: this.resource, mediaById: {}, loading: false, selected: [], search: '', form: '', candidates: [], candidatesLoading: false, candidateError: '', candidateSearch: '', attachIds: [], targets: [], detail: null, forced: false, candidateRequest: 0 } },
  computed: {
    security () { return JSON.stringify([this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state.user.token]) },
    scopeKey () { return this.security + ':' + this.resource.id },
    rows () { return attachedIsos(this.vm).map(iso => ({ ...iso, bootable: this.mediaById[iso.id]?.bootable })) },
    capacity () { return isoCapacity(this.vm) },
    operation () { return isoOperations[this.scopeKey] },
    busy () { return !!(this.operation?.running || this.operation?.items.some(i => i.status === 'unknown')) },
    filteredRows () { return this.rows.filter(i => this.name(i).toLowerCase().includes(this.search.toLowerCase())) },
    filteredCandidates () { return this.candidates.filter(i => (this.name(i) + ' ' + i.name).toLowerCase().includes(this.candidateSearch.toLowerCase())) },
    selection () { return this.allowed('detachIso') ? { selectedRowKeys: this.selected, onChange: keys => { this.selected = keys }, getCheckboxProps: () => ({ disabled: this.busy || !!this.reason(false) }) } : undefined },
    columns () { return ['name', 'device', 'media', 'state', 'actions'].map(key => ({ key, title: this.$t(['device', 'media'].includes(key) ? 'label.vmiso.' + key : 'label.' + key), width: { name: 240, device: 90, media: 120, state: 90, actions: 146 }[key] })) },
    dialogTitle () { return this.$t(this.form === 'details' ? 'label.details' : 'label.vmiso.' + (this.form || 'attach')) },
    summary () { return this.$t('message.vmiso.summary', Object.fromEntries(['success', 'failed', 'unknown', 'pending', 'running'].map(status => [status, this.operation?.items.filter(i => i.status === status).length || 0]))) }
  },
  watch: {
    scopeKey () { this.vm = this.resource; this.mediaById = {}; this.form = ''; this.selected = []; this.candidateRequest++; this.fetchData() },
    resource (value) { this.vm = value },
    form () { this.candidateRequest++ }
  },
  created () { this.fetchData(); this.onComplete = () => this.fetchData(); eventBus.on('async-job-complete', this.onComplete) },
  beforeUnmount () { eventBus.off('async-job-complete', this.onComplete); this.candidateRequest++ },
  methods: {
    name (iso) { return iso.displaytext || iso.name || iso.id },
    slot (iso) { return isoSlot(iso, this.vm) },
    allowed (api) { return api in this.$store.getters.apis },
    reason (attach) { const reason = this.listRefreshFailed ? 'message.list.refresh.stale' : isoActionReason(this.vm, attach); return reason ? this.$t(reason) : '' },
    async fetchData () {
      const request = this.listRequestToken('fetchData'); this.loading = !request.loaded
      try {
        const json = await getAPI('listVirtualMachines', { id: this.resource.id })
        const vm = json.listvirtualmachinesresponse.virtualmachine?.find(v => v.id === this.resource.id)
        if (!this.isListRequestCurrent('fetchData', request)) return
        if (!vm) throw new Error('VM unavailable')
        // VM isos[].bootable describes the primary boot slot, not the media's capability.
        // Read ISO metadata through the existing API instead of labelling secondary media non-bootable.
        const media = await Promise.all(attachedIsos(vm).map(async iso => {
          try {
            const response = await getAPI('listIsos', { id: iso.id, zoneid: vm.zoneid, isofilter: 'executable', listall: true })
            return [iso.id, response.listisosresponse.iso?.find(item => item.id === iso.id) || {}]
          } catch (_) { return [iso.id, {}] }
        }))
        if (!this.isListRequestCurrent('fetchData', request)) return
        this.mediaById = Object.fromEntries(media)
        this.vm = vm; this.selected = this.selected.filter(id => attachedIsos(vm).some(i => i.id === id))
      } catch (_) { if (this.isListRequestCurrent('fetchData', request)) { request.failed = true; this.listRefreshFailed = true } } finally { if (this.isListRequestCurrent('fetchData', request)) this.loading = false }
    },
    async openAttach () {
      this.form = 'attach'; this.attachIds = []; this.candidates = []; this.candidateSearch = ''; this.candidateError = ''; this.forced = false; this.candidatesLoading = true
      await this.$nextTick()
      const request = ++this.candidateRequest; const scope = this.scopeKey
      try {
        const found = []
        for (const filter of ['featured', 'community', 'selfexecutable']) {
          let page = 1; let count = 0
          while (true) {
            const response = await getAPI('listIsos', { zoneid: this.vm.zoneid, isofilter: filter, isready: true, listall: true, page, pagesize: 100 })
            if (request !== this.candidateRequest || scope !== this.scopeKey || this.listRefreshDisposed) return
            const data = response.listisosresponse; const rows = data.iso || []; found.push(...rows); count += rows.length
            if (!rows.length || count >= (data.count || count)) break
            page++
          }
        }
        this.candidates = [...new Map(found.filter(i => !this.rows.some(r => r.id === i.id)).map(i => [i.id, i])).values()]
      } catch (_) { if (scope === this.scopeKey && request === this.candidateRequest) this.candidateError = this.$t('message.list.refresh.stale') } finally { if (scope === this.scopeKey && request === this.candidateRequest) this.candidatesLoading = false }
    },
    openDetach (items) { this.targets = items.map(i => ({ ...i })); this.forced = false; this.form = 'detach' },
    begin (api, items) {
      if (this.busy || !items.length || !this.allowed(api)) return
      const vm = { ...this.vm }; const key = this.scopeKey; const security = this.security; const forced = this.forced; const originalPage = this.$route.path
      startIsoOperation(key, { api, items }, {
        current: () => this.security === security,
        refresh: async () => { if (!this.listRefreshDisposed && key === this.scopeKey) await this.fetchData(); if (this.security === security) eventBus.emit('async-job-complete', { api, isFetchData: true }) },
        validate: async item => {
          if (!this.allowed(api)) throw new Error(this.$t('message.vmsnapshot.permission'))
          const response = await getAPI('listVirtualMachines', { id: vm.id })
          const fresh = response.listvirtualmachinesresponse.virtualmachine?.find(i => i.id === vm.id)
          const reason = isoActionReason(fresh, api === 'attachIso')
          if (reason) throw new Error(this.$t(reason))
          const present = attachedIsos(fresh).some(i => i.id === item.id)
          if ((api === 'attachIso' && present) || (api === 'detachIso' && !present)) throw new Error(this.$t('message.vmiso.changed'))
        },
        submit: item => postAPI(api, { virtualmachineid: vm.id, id: item.id, ...(forced && vm.hypervisor === 'VMware' ? { forced: true } : {}) }).then(r => r[api.toLowerCase() + 'response']),
        poll: (jobId, item) => this.$pollJob({ jobId, retry: true, originalPage, resourceId: vm.id, title: this.$t(api === 'attachIso' ? 'label.vmiso.attach' : 'label.vmiso.detach'), description: this.name(item), showLoading: false, showSuccessMessage: false, action: { api, isFetchData: true } })
      })
      this.selected = []; this.form = 'progress'
    }
  }
}
</script>

<style lang="scss" scoped>
.iso-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.iso-toolbar .ant-input-search { width: 240px; margin-left: auto; }
.iso-row-actions { display: flex; gap: 4px; white-space: nowrap; }
.iso-name { overflow-wrap: anywhere; }
.iso-spacing { margin-bottom: 16px; }
.iso-note { display: block; margin: 6px 0 0; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.iso-candidates { color: var(--ui-text-primary); display: flex; flex-direction: column; width: 100%; gap: 8px; }
.iso-choice { display: flex; align-items: flex-start; gap: 12px; padding: 14px; border: 1px solid rgba(128,128,128,.35); border-radius: 4px; cursor: pointer; }
.iso-choice strong { overflow-wrap: anywhere; }
.iso-result { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid rgba(128,128,128,.3); }
.iso-result > div { min-width: 0; }
.iso-result strong { overflow-wrap: anywhere; }
.iso-result .ant-tag { flex-shrink: 0; }
</style>
<style lang="scss">
.vm-iso-modal {
  .ant-modal { top: 0; padding-bottom: 0; max-width: calc(100vw - 32px); }
  .ant-modal-content { display: flex; flex-direction: column; overflow: hidden; max-height: calc(100vh - 48px); max-height: calc(100dvh - 48px); }
  .ant-modal-header, .ant-modal-footer { flex-shrink: 0; }
  .ant-modal-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 24px; }
  .ant-modal-footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; padding: 12px 24px; }
  .ant-modal-footer .ant-btn + .ant-btn { margin-left: 0; }
  .ant-descriptions-bordered .ant-descriptions-item-label { width: 128px; background: var(--ui-bg-page); color: var(--ui-text-secondary); }
  .ant-descriptions-bordered .ant-descriptions-item-content { color: var(--ui-text-primary); }
  .ant-descriptions-bordered .ant-descriptions-view, .ant-descriptions-bordered .ant-descriptions-row, .ant-descriptions-bordered .ant-descriptions-item-label, .ant-descriptions-bordered .ant-descriptions-item-content { border-color: var(--ui-border); }
  .ant-modal-title { padding-right: 24px; }
  @media (max-width: 600px) { .ant-modal-body { padding: 16px; } .ant-modal-footer { padding: 12px 16px; } .ant-descriptions-bordered .ant-descriptions-item-label { width: 96px; } }
  .ant-descriptions-item-content { overflow-wrap: anywhere; }
}
</style>
