<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. -->
<template>
  <section class="affinity-members">
    <div class="detail-tab-toolbar affinity-members-toolbar">
      <a-button type="primary" :disabled="!canChange || busy" @click="openAdd">{{ $t('label.affinity.members.add') }}</a-button>
      <a-button :disabled="!canChange || busy || !selection.length || !!error" @click="confirmRemove">{{ $t('label.affinity.members.remove') }} ({{ selection.length }})</a-button>
      <a-button v-if="batch.targets.length" @click="dialog = 'results'">{{ $t('label.affinity.members.results') }}</a-button>
      <a-button :loading="loading" :aria-label="$t('label.refresh')" :title="$t('label.refresh')" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <div class="detail-tab-toolbar-context">
        <a-input-search v-model:value="draftKeyword" :aria-label="$t('label.affinity.members.search')" :placeholder="$t('label.search')" allow-clear @search="search" />
      </div>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="text(error)"><template #description><a-button :loading="loading" @click="fetchData">{{ $t('label.retry') }}</a-button></template></a-alert>
    <a-alert v-if="!canRead || (!keyword && memberCount > total)" type="info" show-icon :message="$t('message.affinity.members.restricted')" />
    <div v-if="selection.length" class="affinity-selection-context"><span>{{ $t('label.selected') }}: {{ selection.length }}</span><a-button size="small" @click="selection = []; selected = {}">{{ $t('label.clear') }}</a-button></div>
    <div class="affinity-table-region" role="region" tabindex="0" :aria-label="$t('label.affinity.members')">
      <detail-tab-table
size="small"
:columns="columns"
:data-source="rows"
:row-key="row => row.id"
:pagination="false"
:loading="loading"
:locale="{ emptyText }"
        :row-selection="canChange ? { selectedRowKeys: selection, preserveSelectedRowKeys: true, onChange: selectMembers, getCheckboxProps: vm => ({ disabled: !eligible(vm) || busy || !!error, 'aria-label': vm.displayname || vm.name }) } : undefined">
        <template #bodyCell="{ column, record, text: value }">
          <template v-if="column.key === 'displayname'"><router-link class="affinity-vm-name" :to="vmLink(record)">{{ record.displayname || record.name }}</router-link><div class="affinity-vm-internal" :title="record.instancename ? '' : $t('message.affinity.value.unavailable')">{{ record.instancename || '—' }}</div></template>
          <template v-else-if="column.key === 'state'"><Status :text="record.state" display-text vm-state /></template>
          <template v-else-if="column.key === 'reason'"><span class="affinity-secondary">{{ eligible(record) ? '—' : $t('message.affinity.vm.stopped') }}</span></template>
          <template v-else><span :title="value ? '' : $t('message.affinity.value.unavailable')">{{ value || '—' }}</span></template>
        </template>
      </detail-tab-table>
    </div>
    <div class="affinity-members-footer">
      <p class="affinity-secondary" role="status" aria-live="polite">{{ $t('label.affinitymembercount') }}: {{ memberCount }} · {{ $t('label.affinity.members.visible') }}: {{ total }}</p>
      <detail-tab-pagination
class="affinity-members-pagination"
:current="page"
:page-size="pagesize"
:total="total"
:page-size-options="['2', '10', '20', '50']"
show-size-changer
@change="changePage"
@showSizeChange="changePage" />
    </div>

    <MoldDialog v-if="dialog" class="affinity-dialog" size="lg" :title="$t('label.affinity.members.' + (dialog === 'blocked' ? 'previous' : dialog === 'results' ? 'results' : mode))" @cancel="closeDialog">
      <div class="affinity-dialog-summary"><strong>{{ resource.name }}</strong><span class="affinity-secondary">{{ resource.project || resource.account }} / {{ resource.domain }}</span></div>
      <p class="affinity-secondary">{{ $t('message.affinity.members.help') }}</p>
      <p v-if="busy" role="status" aria-live="polite">{{ $t('message.affinity.operation.close') }}</p>
      <template v-if="dialog === 'choose'">
        <a-input-search class="affinity-dialog-search" v-model:value="candidateDraft" :aria-label="$t('label.affinity.members.search')" :placeholder="$t('label.search')" allow-clear @search="candidateSearch" />
        <a-alert v-if="candidateError" type="error" show-icon :message="text(candidateError)"><template #description><a-button :loading="candidateLoading" @click="fetchCandidates">{{ $t('label.retry') }}</a-button></template></a-alert>
        <div class="affinity-selection-context"><span>{{ $t('label.selected') }}: {{ candidateSelection.length }}</span><a-button v-if="candidateSelection.length" size="small" @click="candidateSelection = []; candidatesSelected = {}">{{ $t('label.clear') }}</a-button></div>
        <p class="affinity-secondary">{{ $t('message.affinity.members.page.selection') }}</p>
        <div class="affinity-table-region" role="region" tabindex="0" :aria-label="$t('label.affinity.members.add')">
          <a-table
size="small"
:columns="columns"
:data-source="candidates"
:row-key="row => row.id"
:loading="candidateLoading"
:pagination="false"
            :row-selection="{ selectedRowKeys: candidateSelection, preserveSelectedRowKeys: true, onChange: selectCandidates, getCheckboxProps: vm => ({ disabled: !eligible(vm) || linked(vm) || !!candidateError || candidateLoading, 'aria-label': vm.displayname || vm.name }) }">
            <template #bodyCell="{ column, record, text: value }">
              <template v-if="column.key === 'displayname'"><router-link class="affinity-vm-name" :to="vmLink(record)">{{ record.displayname || record.name }}</router-link><div class="affinity-vm-internal" :title="record.instancename ? '' : $t('message.affinity.value.unavailable')">{{ record.instancename || '—' }}</div></template>
              <template v-else-if="column.key === 'state'"><Status :text="record.state" display-text vm-state /></template>
              <template v-else-if="column.key === 'reason'"><span class="affinity-secondary">{{ linked(record) ? $t('message.affinity.members.already') : (!eligible(record) ? $t('message.affinity.vm.stopped') : '—') }}</span></template>
              <template v-else><span :title="value ? '' : $t('message.affinity.value.unavailable')">{{ value || '—' }}</span></template>
            </template>
          </a-table>
        </div>
        <a-pagination class="affinity-members-pagination" :current="candidatePage" :page-size="10" :total="candidateTotal" @change="candidatePage = $event; fetchCandidates()" />
      </template>
      <template v-else-if="dialog === 'blocked' && blocked">
        <a-alert type="warning" show-icon :message="$t('message.affinity.members.previous')" />
        <router-link :to="vmLink(blocked.vm)">{{ blocked.vm.displayname || blocked.vm.name }}</router-link>
        <p>{{ $t('label.affinity.members.previous') }}: {{ $t('message.affinity.operation.' + blocked.state.status) }}</p>
        <p v-if="blocked.state.error" role="alert">{{ text(blocked.state.error) }}</p>
        <p v-if="blocked.state.jobId" class="affinity-job">{{ $t('label.affinity.job') }}: <a-typography-text copyable>{{ blocked.state.jobId }}</a-typography-text></p>
        <p v-if="blocked.state.status === 'unknown' && !blocked.state.jobId">{{ $t('message.affinity.operation.no.receipt') }}</p>
        <a-button v-if="recheckable(blocked.state)" :loading="blocked.loading" @click="recheckBlocked">{{ $t('label.affinity.recheck') }}</a-button>
      </template>
      <template v-else>
        <p v-if="dialog === 'confirm'">{{ $t('message.affinity.members.confirm') }}</p>
        <ul class="affinity-member-results" aria-live="polite">
          <li v-for="vm in batch.targets" :key="vm.id">
            <div class="affinity-result-heading"><router-link :to="vmLink(vm)">{{ vm.displayname || vm.name }}</router-link><a-tag v-if="batch.results[vm.id]" :color="resultTone(batch.results[vm.id].status)">{{ $t('message.affinity.operation.' + batch.results[vm.id].status) }}</a-tag></div>
            <div v-if="batch.results[vm.id]?.error" role="alert">{{ text(batch.results[vm.id].error) }}</div>
            <p v-if="batch.results[vm.id]?.jobId" class="affinity-job">{{ $t('label.affinity.job') }}: <a-typography-text copyable>{{ batch.results[vm.id].jobId }}</a-typography-text></p>
            <p v-if="batch.results[vm.id]?.status === 'unknown' && !batch.results[vm.id]?.jobId">{{ $t('message.affinity.operation.no.receipt') }}</p>
            <div class="affinity-result-actions"><a-button v-if="recheckable(batch.results[vm.id])" size="small" @click="recheck(vm)">{{ $t('label.affinity.recheck') }}</a-button><a-button v-if="batch.results[vm.id]?.previous" size="small" @click="showBlocked(vm)">{{ $t('label.affinity.members.previous') }}</a-button></div>
          </li>
        </ul>
      </template>
      <template #footer>
        <a-button @click="closeDialog">{{ $t(['choose', 'confirm'].includes(dialog) ? 'label.cancel' : 'label.close') }}</a-button>
        <a-button v-if="dialog === 'choose'" type="primary" :disabled="!candidateSelection.length || !!candidateError || candidateLoading" @click="confirmAdd">{{ $t('label.next') }}</a-button>
        <a-button v-if="dialog === 'confirm'" type="primary" :loading="busy" :disabled="!canChange" @click="execute(false)">{{ $t('label.ok') }}</a-button>
        <a-button v-if="dialog === 'results' && hasIdle" type="primary" :loading="busy" :disabled="!canChange" @click="execute(false)">{{ $t('label.affinity.members.continue') }}</a-button>
        <a-button v-if="dialog === 'results' && hasFailed" type="primary" :loading="busy" :disabled="!canChange" @click="execute(true)">{{ $t('label.affinity.members.retry') }}</a-button>
      </template>
    </MoldDialog>
  </section>
</template>
<script>
import { reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog.vue'
import Status from '@/components/widgets/Status'
import { affinityOwnerKey, affinitySessionScope, affinityVmCanChange, affinityApiError, getAffinityOperation } from '@/utils/affinityGroupOperations'
import { affinityMemberCount, affinityVmIds, affinityMemberSnapshot, affinityMemberLocked, affinityMemberPage, affinityMemberParams, affinityGroupQueryParams, prepareAffinityMemberChange, affinityMemberBatch, resetAffinityMemberBatch, observeAffinityMemberBatch } from '@/utils/affinityGroupMembers'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { affinityResultTone } from '@/utils/affinityGroupPresentation'

export default {
  name: 'AffinityGroupMembers',
  components: { MoldDialog, Status },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => vm.active && !vm.dialog && !vm.busy })],
  props: { resource: { type: Object, required: true }, active: { type: Boolean, default: true } },
  inject: { parentFetchData: { default: () => () => {} } },
  data: () => ({
    rows: [],
    total: 0,
    memberCount: 0,
    error: '',
    loading: false,
    page: 1,
    pagesize: 10,
    keyword: '',
    draftKeyword: '',
    selection: [],
    selected: {},
    dialog: '',
    blocked: null,
    mode: '',
    candidates: [],
    candidateTotal: 0,
    candidatePage: 1,
    candidateKeyword: '',
    candidateDraft: '',
    candidateError: '',
    candidateLoading: false,
    candidateSelection: [],
    candidatesSelected: {},
    batch: { mode: '', targets: [], results: {}, busy: false }
  }),
  computed: {
    securityScope () { return affinitySessionScope(this.$store) },
    scope () { try { return [this.securityScope, this.resource.id, affinityOwnerKey(this.resource)].join('|') } catch (_) { return '' } },
    canRead () { return !!this.scope && 'listVirtualMachines' in this.$store.getters.apis },
    canChange () { return this.canRead && 'updateVMAffinityGroup' in this.$store.getters.apis && 'listAffinityGroups' in this.$store.getters.apis },
    busy () { return this.batch.busy || Object.values(this.batch.results).some(row => ['preparing', 'submitting', 'pending', 'retrying'].includes(row.status)) },
    hasFailed () { return Object.values(this.batch.results).some(row => row.status === 'failed') },
    hasIdle () { return Object.values(this.batch.results).some(row => row.status === 'idle') },
    emptyText () { return this.$t(!this.canRead ? 'message.affinity.members.restricted' : this.error ? 'message.affinity.members.lookup' : this.keyword || this.memberCount ? 'message.affinity.members.no.results' : 'message.affinity.members.empty') },
    columns () { return ['displayname', 'state', 'hostname', 'account', 'project', 'domain', 'reason'].map((key, i) => ({ key, dataIndex: key, width: [200, 110, 140, 120, 140, 120, 240][i], title: this.$t('label.' + (key === 'reason' ? 'affinity.members.reason' : key)) })) }
  },
  created () { this.alive = true; this.version = 0; this.candidateVersion = 0; this.memberCount = affinityMemberCount(this.resource); this.bindBatch(); this.fetchData() },
  beforeUnmount () { this.alive = false; this.version++; this.candidateVersion++ },
  watch: {
    scope () { this.version++; this.candidateVersion++; this.rows = []; this.total = 0; this.memberCount = affinityMemberCount(this.resource); this.selection = []; this.selected = {}; this.error = ''; this.dialog = ''; this.blocked = null; this.page = 1; this.keyword = ''; this.draftKeyword = ''; this.bindBatch(); this.fetchData() },
    active (value) { if (value) this.fetchData(); else { this.version++; this.candidateVersion++; this.dialog = ''; this.blocked = null } }
  },
  methods: {
    resultTone: affinityResultTone,
    text (value) { return value.startsWith('message.') ? this.$t(value) : value },
    eligible: affinityVmCanChange,
    linked (vm) { return affinityVmIds(vm).includes(this.resource.id) },
    vmLink (vm) { return { path: '/vm/' + vm.id, query: vm.projectid ? { projectid: vm.projectid } : {} } },
    recheckable (row) { return row?.jobId && ['unknown', 'timeout', 'cancelled'].includes(row.status) },
    operation (vm) { const store = this.$store; return getAffinityOperation(this.securityScope, vm.id, { get: getAPI, post: postAPI, getScope: () => affinitySessionScope(store) }) },
    bindBatch () {
      this.batch = reactive(affinityMemberBatch(this.securityScope, this.resource.id))
      for (const vm of this.batch.targets) this.observe(vm)
    },
    observe (vm) { observeAffinityMemberBatch(this.batch, vm.id, this.operation(vm)) },
    async fetchData () {
      if (!this.active || !this.canRead) return
      const version = ++this.version; const scope = this.scope
      const current = () => this.alive && this.active && version === this.version && scope === this.scope
      this.loading = true
      try {
        const [response, groupResponse] = await Promise.all([
          getAPI('listVirtualMachines', affinityMemberParams(this.resource, this.page, this.pagesize, this.keyword), { preserveOnFailure: true, timeout: 15000 }),
          getAPI('listAffinityGroups', affinityGroupQueryParams(this.resource), { preserveOnFailure: true, timeout: 15000 })
        ])
        const group = groupResponse?.listaffinitygroupsresponse?.affinitygroup?.find(row => row.id === this.resource.id)
        if (!group || affinityOwnerKey(group) !== affinityOwnerKey(this.resource)) throw new Error('message.affinity.owner.changed')
        const { rows, count } = affinityMemberPage(response)
        if (!current()) return
        this.error = ''; this.memberCount = affinityMemberCount(group)
        if (this.page > Math.max(1, Math.ceil(count / this.pagesize))) { this.page = Math.max(1, Math.ceil(count / this.pagesize)); this.fetchData(); return }
        this.rows = rows; this.total = count
      } catch (error) { if (current()) this.error = affinityApiError(error) || error.message || 'message.affinity.members.lookup' } finally { if (current()) this.loading = false }
    },
    search (keyword) { this.keyword = keyword; this.page = 1; this.fetchData() },
    changePage (page, size) { this.page = size !== this.pagesize ? 1 : page; this.pagesize = size; this.fetchData() },
    selectMembers (ids) { this.selection = [...new Set(ids)]; for (const vm of this.rows) if (ids.includes(vm.id) && !this.selected[vm.id]) this.selected[vm.id] = affinityMemberSnapshot(vm); for (const id of Object.keys(this.selected)) if (!ids.includes(id)) delete this.selected[id] },
    selectCandidates (ids) { this.candidateSelection = [...new Set(ids)]; for (const vm of this.candidates) if (ids.includes(vm.id) && !this.candidatesSelected[vm.id]) this.candidatesSelected[vm.id] = affinityMemberSnapshot(vm); for (const id of Object.keys(this.candidatesSelected)) if (!ids.includes(id)) delete this.candidatesSelected[id] },
    openAdd () {
      if (!this.canChange || this.busy) return
      this.mode = 'add'; this.dialog = 'choose'; this.candidatePage = 1; this.candidateKeyword = ''; this.candidateDraft = ''; this.candidateSelection = []; this.candidatesSelected = {}; this.candidates = []; this.candidateError = ''; this.fetchCandidates()
    },
    closeDialog () { this.dialog = ''; this.blocked = null; this.candidateVersion++ },
    candidateSearch (keyword) { this.candidateKeyword = keyword; this.candidatePage = 1; this.fetchCandidates() },
    async fetchCandidates () {
      const version = ++this.candidateVersion; const scope = this.scope
      const current = () => this.alive && this.active && scope === this.scope && this.dialog === 'choose' && version === this.candidateVersion
      this.candidateLoading = true
      try {
        const value = affinityMemberPage(await getAPI('listVirtualMachines', affinityMemberParams(this.resource, this.candidatePage, 10, this.candidateKeyword, false), { preserveOnFailure: true, timeout: 15000 }))
        if (!current()) return
        const last = Math.max(1, Math.ceil(value.count / 10))
        if (this.candidatePage > last) { this.candidatePage = last; this.fetchCandidates(); return }
        this.candidates = value.rows; this.candidateTotal = value.count; this.candidateError = ''
      } catch (error) { if (current()) this.candidateError = affinityApiError(error) || 'message.affinity.members.lookup' } finally { if (current()) this.candidateLoading = false }
    },
    confirmAdd () { if (!this.candidateError && !this.candidateLoading) this.confirm('add', this.candidateSelection.map(id => this.candidatesSelected[id])) },
    confirmRemove () { if (!this.error) this.confirm('remove', this.selection.map(id => this.selected[id])) },
    confirm (mode, targets) {
      if (!this.canChange || this.busy || !targets.length || targets.some(vm => !vm || !this.eligible(vm))) return
      // Explicit confirmation starts a new batch. Never discard unresolved receipts.
      if (Object.values(this.batch.results).some(row => affinityMemberLocked(row.status))) { this.dialog = 'results'; return }
      const locked = targets.find(vm => affinityMemberLocked(this.operation(vm).snapshot().status))
      if (locked) { this.showBlocked(locked); return }
      this.blocked = null; this.mode = mode; resetAffinityMemberBatch(this.batch, mode, targets)
      for (const vm of targets) { const op = this.operation(vm); if (['succeeded', 'failed'].includes(op.snapshot().status)) op.acknowledge(); this.observe(vm) }
      this.dialog = 'confirm'
    },
    async execute (retry) {
      if (!this.canChange || this.busy || !this.batch.targets.length) return
      const scope = this.scope; const group = { ...this.resource }; const batch = this.batch
      const current = () => this.alive && this.active && scope === this.scope && this.canChange
      batch.busy = true; this.dialog = 'results'
      try {
        for (const original of batch.targets) {
          if (!current()) break
          const op = this.operation(original); const previous = batch.results[original.id]
          if (retry ? previous?.status !== 'failed' : previous?.status !== 'idle') continue
          const receipt = op.snapshot()
          if (receipt.context !== batch.token && affinityMemberLocked(receipt.status)) {
            batch.results[original.id] = { status: 'failed', error: 'message.affinity.members.previous', previous: receipt }; continue
          }
          if (receipt.context !== batch.token && ['succeeded', 'failed'].includes(receipt.status)) op.acknowledge()
          const result = await op.run('updateVMAffinityGroup', { id: original.id }, async () => {
            let snapshot = original
            if (retry) {
              const result = await getAPI('listVirtualMachines', { id: original.id, details: 'min,affgrp', ...(original.projectid ? { projectid: original.projectid } : {}), listall: true }, { preserveOnFailure: true, timeout: 15000 })
              snapshot = result.listvirtualmachinesresponse?.virtualmachine?.find(vm => vm.id === original.id)
              if (!snapshot || affinityOwnerKey(snapshot) !== affinityOwnerKey(original)) throw new Error('message.affinity.owner.changed')
            }
            return prepareAffinityMemberChange(getAPI, group, snapshot, batch.mode, current)
          }, batch.token)
          if (result.context !== batch.token) batch.results[original.id] = { status: 'failed', error: 'message.affinity.members.previous', previous: result }
        }
        if (current()) { this.selection = []; this.selected = {}; await Promise.all([this.fetchData(), this.parentFetchData({ irefresh: true })]) }
      } finally { batch.busy = false }
    },
    async recheck (vm) {
      if (this.operation(vm).snapshot().context !== this.batch.token) { this.showBlocked(vm); return }
      await this.operation(vm).recheck(); if (this.alive) { await this.fetchData(); this.parentFetchData({ irefresh: true }) }
    },
    showBlocked (vm) { this.blocked = { vm: affinityMemberSnapshot(vm), state: this.operation(vm).snapshot(), loading: false }; this.dialog = 'blocked' },
    async recheckBlocked () {
      const blocked = this.blocked; const scope = this.scope
      if (!blocked || blocked.loading) return
      blocked.loading = true
      try {
        await this.operation(blocked.vm).recheck()
        if (!this.alive || scope !== this.scope || this.blocked !== blocked) return
        blocked.state = this.operation(blocked.vm).snapshot()
        await Promise.all([this.fetchData(), this.parentFetchData({ irefresh: true })])
      } finally { blocked.loading = false }
    }
  }
}
</script>
<style scoped>
.affinity-members { min-width: 0; }
.affinity-members-toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
.affinity-members-toolbar :deep(.ant-input-search) { width: 280px; max-width: 100%; margin-left: auto; }
.affinity-members-pagination { margin-top: 16px; text-align: right; }
.affinity-members-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-top: 16px; }
.affinity-members-footer p { margin: 0; }
.affinity-members-footer :deep(.ant-pagination) { margin-top: 0; }
.affinity-member-results { padding-left: 20px; }
.affinity-member-results li { margin-bottom: 12px; }
</style>
