// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div class="vm-group-members">
    <div class="member-toolbar">
      <div class="member-toolbar-actions">
        <a-button type="primary" :disabled="!!actionReason" @click="openLink"><template #icon><plus-outlined /></template>{{ $t('label.vm.group.members.link') }}</a-button>
        <a-button :loading="pending" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      </div>
      <a-input-search class="member-toolbar-search" v-model:value="draftKeyword" :placeholder="$t('label.search')" allow-clear @search="searchMembers" />
    </div>
    <p v-if="summary && canRead" class="member-summary">{{ $t('label.vm.group.members.total') }}: {{ summary.count }} <span v-for="[state, value] in summary.states" :key="state"><Status :text="state" /> {{ $t('label.' + state.toLowerCase()) }} {{ value }}</span></p>
    <p v-if="actionReason && actionReason !== 'message.vm.group.members.refresh' && !['error', 'forbidden'].includes(status)" role="status">{{ $t(actionReason) }}</p>
    <p v-if="['error', 'forbidden'].includes(status)" role="status">{{ $t('message.vm.group.members.' + status) }} <a-button type="link" @click="fetchData">{{ $t('label.retry') }}</a-button></p>
    <ListView
detail-tab
ref="membersTable"
resource-path="/vm"
:keyboardContextMenu="true"
:columns="columns"
:items="rows"
:loading="pending && !rows.length"
:actions="memberActions"
:explicitlyAllowRowSelection="!actionReason"
:selectionDisabled="() => !!actionReason"
:emptyText="$t('message.vm.group.members.' + (status === 'ready' ? 'empty' : status))"
@selection-change="selection = $event"
@table-change="sortMembers" />
    <div v-if="status === 'ready' || rows.length" class="member-pagination">
    <detail-tab-pagination
:current="page"
:pageSize="pagesize"
:total="count"
:showTotal="total => $t('label.vm.group.members.filtered', [total])"
show-size-changer
:pageSizeOptions="['10', '20', '50', '100']"
@change="changePage"
@showSizeChange="changePage" />
    </div>

    <a-modal
      centered
      wrap-class-name="vm-group-member-modal"
      :visible="modalVisible"
      :title="$t('label.vm.group.members.' + mode)"
      :maskClosable="false"
      :closable="!busy"
      :keyboard="!busy"
      :width="device === 'mobile' ? '96vw' : '760px'"
      @cancel="closeModal">
      <a-form layout="vertical">
        <a-descriptions :column="1" size="small" bordered>
          <a-descriptions-item :label="$t('label.instance.group')">{{ modalSource?.name }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.account')">{{ modalAccount }}</a-descriptions-item>
        </a-descriptions>
        <template v-if="stage === 'choose' && mode === 'link'">
          <p>{{ $t('message.vm.group.members.link') }}</p>
          <a-input-search ref="candidateSearch" v-model:value="candidateKeyword" :placeholder="$t('label.search')" allow-clear @search="searchCandidates" />
          <p v-if="['error', 'forbidden'].includes(candidateStatus)" role="status">{{ $t('message.vm.group.members.' + candidateStatus) }} <a-button type="link" @click="fetchCandidates">{{ $t('label.retry') }}</a-button></p>
          <div class="vm-group-member-candidates">
          <ListView
ref="candidateTable"
resource-path="/vm"
:columns="columns"
:items="candidates"
:loading="candidatePending"
:actions="[]"
:explicitlyAllowRowSelection="true"
:selectionDisabled="vm => candidateStatus !== 'ready' || candidatePending || vm.groupid === modalSource.id"
:emptyText="$t('message.vm.group.members.' + (candidateStatus === 'ready' ? 'candidates.empty' : candidateStatus))"
@selection-change="candidateSelection = $event" />
          </div>
          <p>{{ $t('message.vm.group.members.already') }}</p>
          <div class="member-pagination">
          <a-pagination :current="candidatePage" :pageSize="20" :showSizeChanger="false" :total="candidateCount" @change="candidatePage = $event; fetchCandidates()" />
          </div>
        </template>
        <template v-if="stage === 'choose' && mode === 'move'">
          <a-form-item :label="$t('label.vm.group.members.target')" required>
            <a-select v-model:value="targetId" :loading="groups.loading" :disabled="!groupsReady" :options="groupOptions" :placeholder="$t('label.vm.group.members.target')" />
          </a-form-item>
          <p v-if="!groupsReady || !groupOptions.length" role="status">{{ $t('message.vm.group.members.groups.' + (groups.status === 'ready' && !groupOptions.length ? 'empty' : groups.status)) }} <a-button v-if="['error', 'forbidden'].includes(groups.status)" type="link" @click="loadGroups">{{ $t('label.retry') }}</a-button></p>
        </template>
        <template v-if="stage === 'confirm' || stage === 'result'">
          <p>{{ $t('message.vm.group.members.' + (mode === 'clear' ? 'confirm.clear' : 'confirm.move')) }}</p>
          <p>{{ $t('label.vm.group.members.target') }}: {{ operationTarget?.name || $t('label.vm.group.members.ungrouped') }} <span v-if="operationTarget">({{ operationTarget.id }})</span></p>
          <a-list size="small" bordered :data-source="targets"><template #renderItem="{ item }"><a-list-item>{{ item.displayname || item.name }} ({{ item.id }}) — {{ item.group || $t('label.vm.group.members.ungrouped') }} → {{ operationTarget?.name || $t('label.vm.group.members.ungrouped') }}</a-list-item></template></a-list>
        </template>
        <a-list v-if="results.length" size="small" :data-source="results"><template #renderItem="{ item }"><a-list-item><span>{{ item.name }}</span><a-tag :color="item.status === 'success' ? 'green' : item.status === 'unknown' ? 'orange' : 'red'">{{ $t('label.vm.group.members.' + item.status) }}</a-tag><span v-if="item.message">{{ $t(item.message) }}</span></a-list-item></template></a-list>
      </a-form>
      <template #footer>
        <a-button :disabled="busy" @click="closeModal">{{ $t(stage === 'result' ? 'label.close' : 'label.cancel') }}</a-button>
        <a-button v-if="stage === 'choose'" type="primary" :disabled="mode === 'link' ? candidateStatus !== 'ready' || candidatePending || !candidateSelection.length : !groupsReady || !targetId" @click="confirmSelection">{{ $t('label.next') }}</a-button>
        <a-button v-if="stage === 'confirm'" type="primary" :loading="busy" :disabled="!!actionReason" @click="execute">{{ $t('label.ok') }}</a-button>
        <a-button v-if="stage === 'result' && results.some(row => row.status === 'failed')" type="primary" :loading="busy" :disabled="!!actionReason" @click="execute">{{ $t('label.retry') }}</a-button>
        <a-button v-if="stage === 'result' && results.some(row => row.status === 'unknown')" @click="fetchData(); parentFetchData()">{{ $t('label.refresh') }}</a-button>
      </template>
    </a-modal>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { createVmGroupLookup, vmGroupLookupReady, vmGroupLookupStatus } from '@/utils/vmGroupLookup'
import { vmGroupMemberParams, vmGroupMemberRows, vmGroupMemberScope, vmGroupMemberSummary, vmGroupMemberActionReason, vmGroupMemberSnapshot, updateVmGroupMembers } from '@/utils/vmGroupMembers'
import ListView from '@/components/view/ListView'
import Status from '@/components/widgets/Status'

export default {
  name: 'InstanceGroupMembers',
  components: { ListView, Status },
  mixins: [mixinDevice, listRefreshMixin(['fetchData'], { active: vm => vm.active && !vm.modalVisible })],
  props: { resource: { type: Object, required: true }, active: { type: Boolean, default: true } },
  inject: { parentFetchData: { default: () => () => {} }, resourceActionReady: { default: () => () => true } },
  data () {
    return {
      disposed: false,
      rows: [],
      count: 0,
      status: 'loading',
      pending: false,
      version: 0,
      loadedScope: '',
      selection: [],
      keyword: '',
      draftKeyword: '',
      page: 1,
      pagesize: 20,
      sortkey: '',
      sortorder: '',
      modalVisible: false,
      mode: 'link',
      stage: 'choose',
      modalSource: null,
      modalScope: '',
      targets: [],
      targetId: undefined,
      operationTarget: null,
      results: [],
      busy: false,
      candidates: [],
      candidateCount: 0,
      candidateStatus: 'loading',
      candidatePending: false,
      candidateKeyword: '',
      candidatePage: 1,
      candidateSelection: [],
      candidateVersion: 0,
      candidateQueryScope: '',
      groups: { status: 'loading', opts: [] },
      groupLookup: null,
      actionTrigger: null
    }
  },
  computed: {
    scope () { return vmGroupMemberScope(this.resource, this.$store, this.$route) },
    queryKey () { return JSON.stringify(['memberkeyword', 'memberpage', 'memberpagesize', 'membersort', 'memberorder'].map(key => this.$route.query[key])) },
    queryScope () { return JSON.stringify([this.scope, this.keyword, this.page, this.pagesize]) },
    canRead () { return 'listVirtualMachines' in this.$store.getters.apis },
    summary () { return this.canRead && this.status !== 'forbidden' ? vmGroupMemberSummary(this.resource) : null },
    modalAccount () { return this.modalSource?.account || this.$store.getters.userInfo?.username || '' },
    selectedRows () { return this.rows.filter(row => this.selection.includes(row.id)) },
    actionReason () { if (!this.canRead) return 'message.vm.group.members.forbidden'; return vmGroupMemberActionReason(this.resource, this.selectedRows, this.$store.getters.apis, this.status === 'ready' && !this.pending && !this.busy && this.resourceActionReady()) },
    groupsReady () { return vmGroupLookupReady(this.groups, this.modalSource || {}) },
    groupOptions () { return this.groups.opts.filter(group => group.groupId !== this.modalSource?.id).map(group => ({ label: group.label, value: group.groupId })) },
    columns () {
      return ['name', 'state', ...(this.resource.projectid ? ['project'] : ['account']), 'domain', 'group'].map(key => ({
        key,
        dataIndex: key,
        title: this.$t(key === 'name' ? 'label.vm.displayname' : 'label.' + key),
        sorter: (left, right) => String(left[key] || '').localeCompare(String(right[key] || '')),
        sortOrder: this.sortkey === key ? (this.sortorder === 'asc' ? 'ascend' : 'descend') : null
      }))
    },
    memberActions () {
      if (this.actionReason) return []
      return ['move', 'clear'].map(mode => ({ api: 'updateVirtualMachine', label: 'label.vm.group.members.' + mode, icon: mode === 'clear' ? 'disconnect-outlined' : 'edit-outlined', dataView: true, groupAction: true, memberMode: mode }))
    }
  },
  watch: {
    scope () { this.resetContext(); this.fetchData() },
    queryKey () { this.readQuery(); this.resetRows(); this.closeModal(); this.fetchData() },
    active (active) { if (active) this.fetchData(); else { this.closeModal(); this.resetSelection() } }
  },
  created () { this.readQuery(); this.fetchData() },
  beforeUnmount () { this.disposed = true; this.modalVisible = false; this.version++; this.candidateVersion++; if (this.groupLookup) this.groupLookup.dispose() },
  methods: {
    readQuery () {
      const query = this.$route.query
      this.keyword = query.memberkeyword || ''; this.draftKeyword = this.keyword; this.page = Number(query.memberpage) || 1; this.pagesize = Number(query.memberpagesize) || 20
      this.sortkey = query.membersort || ''; this.sortorder = query.memberorder || ''
    },
    saveQuery (values) {
      const query = { ...this.$route.query, tab: 'members', ...values }
      for (const key of Object.keys(query)) if (query[key] === '' || query[key] == null) delete query[key]
      return this.$router.replace({ query })
    },
    searchMembers (value) { this.saveQuery({ memberkeyword: value, memberpage: '1' }) },
    changePage (page, pagesize) { this.saveQuery({ memberpage: String(page), memberpagesize: String(pagesize || this.pagesize) }) },
    sortMembers (_, __, sorter) { this.saveQuery({ membersort: sorter.order ? sorter.field : undefined, memberorder: sorter.order ? (sorter.order === 'ascend' ? 'asc' : 'desc') : undefined }) },
    resetSelection () { this.selection = []; if (this.$refs.membersTable) this.$refs.membersTable.resetSelection() },
    resetRows () { this.version++; this.rows = []; this.count = 0; this.status = 'loading'; this.pending = false; this.loadedScope = ''; this.resetSelection() },
    resetContext () { this.resetRows(); this.closeModal(); this.candidates = []; this.results = [] },
    async fetchData () {
      if (!this.active || !this.resource.id) return
      if (!this.canRead) { this.rows = []; this.count = 0; this.status = 'forbidden'; this.resetSelection(); return }
      const version = ++this.version; const scope = this.queryScope
      const current = () => version === this.version && scope === this.queryScope && this.active
      if (this.loadedScope !== scope) { this.rows = []; this.count = 0; this.status = 'loading'; this.resetSelection() }
      this.pending = true
      try {
        const value = vmGroupMemberRows(await getAPI('listVirtualMachines', vmGroupMemberParams(this.resource, { keyword: this.keyword, page: this.page, pagesize: this.pagesize }), { preserveOnFailure: true, timeout: 20000 }), this.resource)
        if (!current()) return
        const last = Math.max(1, Math.ceil(value.count / this.pagesize))
        if (this.page > last) { this.changePage(last); return }
        this.rows = value.rows; this.count = value.count; this.status = 'ready'; this.loadedScope = scope
      } catch (error) {
        if (!current()) return
        this.status = vmGroupLookupStatus(error); this.resetSelection()
        if (this.status === 'forbidden') { this.rows = []; this.count = 0; this.loadedScope = '' }
      } finally { if (current()) this.pending = false }
    },
    openLink () { this.openAction('link', []) },
    execAction (action, bulk) { this.openAction(action.memberMode, bulk ? this.selectedRows : [action.resource]) },
    openAction (mode, rows) {
      if (this.actionReason || !['link', 'move', 'clear'].includes(mode) || rows.some(row => !row)) return
      const rowElement = rows[0]?.id && this.$el?.querySelector?.('tr[data-row-key="' + rows[0].id + '"]')
      this.actionTrigger = rowElement || document.activeElement
      this.modalScope = this.scope; this.modalSource = Object.freeze({ ...this.resource }); this.targets = vmGroupMemberSnapshot(rows)
      this.mode = mode; this.stage = mode === 'clear' ? 'confirm' : 'choose'; this.modalVisible = true; this.targetId = undefined; this.operationTarget = mode === 'link' ? this.modalSource : null; this.results = []
      if (mode === 'link') { this.candidateKeyword = ''; this.candidatePage = 1; this.candidates = []; this.candidateSelection = []; this.fetchCandidates() }
      if (mode === 'move') this.loadGroups()
    },
    closeModal () {
      this.modalVisible = false; this.candidateVersion++; if (this.groupLookup) this.groupLookup.dispose(); this.candidateSelection = []
      this.$nextTick(() => { if (this.actionTrigger?.isConnected) this.actionTrigger.focus() })
    },
    searchCandidates () { this.candidatePage = 1; this.candidates = []; this.candidateSelection = []; if (this.$refs.candidateTable) this.$refs.candidateTable.resetSelection(); this.fetchCandidates() },
    async fetchCandidates () {
      const version = ++this.candidateVersion; const scope = JSON.stringify([this.modalScope, this.candidateKeyword, this.candidatePage])
      const current = () => version === this.candidateVersion && this.modalVisible && this.modalScope === this.scope && scope === JSON.stringify([this.modalScope, this.candidateKeyword, this.candidatePage])
      if (this.candidateQueryScope !== scope) { this.candidates = []; this.candidateCount = 0; this.candidateQueryScope = scope }
      this.candidatePending = true; this.candidateStatus = 'loading'; this.candidateSelection = []; if (this.$refs.candidateTable) this.$refs.candidateTable.resetSelection()
      try {
        const value = vmGroupMemberRows(await getAPI('listVirtualMachines', vmGroupMemberParams(this.modalSource, { keyword: this.candidateKeyword, page: this.candidatePage, pagesize: 20 }, false), { preserveOnFailure: true, timeout: 20000 }), this.modalSource, false)
        if (!current()) return
        const last = Math.max(1, Math.ceil(value.count / 20))
        if (this.candidatePage > last) { this.candidatePage = last; this.fetchCandidates(); return }
        this.candidates = value.rows; this.candidateCount = value.count; this.candidateStatus = 'ready'
      } catch (error) { if (current()) { this.candidateStatus = vmGroupLookupStatus(error); if (this.candidateStatus === 'forbidden') this.candidates = [] } } finally { if (current()) this.candidatePending = false }
    },
    loadGroups () {
      if (this.groupLookup) this.groupLookup.dispose(); const scope = this.modalScope
      this.groupLookup = createVmGroupLookup(getAPI, value => { if (this.modalVisible && this.modalScope === scope && scope === this.scope) this.groups = value })
      this.groupLookup.load(this.modalSource, 'listInstanceGroups' in this.$store.getters.apis)
    },
    confirmSelection () {
      if (this.mode === 'link') {
        if (this.candidateStatus !== 'ready' || this.candidatePending) return
        const rows = this.candidates.filter(vm => this.candidateSelection.includes(vm.id) && vm.groupid !== this.modalSource.id)
        if (!rows.length) return
        this.targets = vmGroupMemberSnapshot(rows)
      } else {
        if (!this.groupsReady) return
        const group = this.groups.opts.find(group => group.groupId === this.targetId && group.groupId !== this.modalSource.id)
        if (!group) return
        this.operationTarget = Object.freeze({ ...this.modalSource, id: group.groupId, name: group.label })
      }
      this.stage = 'confirm'
    },
    async execute () {
      if (this.actionReason || this.busy || !this.targets.length || this.modalScope !== this.scope) return
      const scope = this.modalScope
      const current = () => !this.disposed && scope === this.scope && this.active && this.modalVisible && 'updateVirtualMachine' in this.$store.getters.apis
      this.busy = true
      try {
        const results = await updateVmGroupMembers(getAPI, postAPI, this.modalSource, this.targets, this.operationTarget, current, this.results)
        if (!current()) return
        this.results = results; this.stage = 'result'; this.resetSelection()
        await Promise.all([this.fetchData(), this.parentFetchData()])
      } finally { this.busy = false }
    }
  }
}
</script>
<style scoped>
.member-summary { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.member-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.member-toolbar-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.member-toolbar-search { width: 280px; max-width: 100%; margin-left: auto; }
.member-pagination { display: flex; justify-content: flex-end; margin-top: 16px; }
.member-pagination :deep(.ant-pagination) { text-align: right; }
.vm-group-members { min-width: 0; }
.vm-group-member-candidates { overflow-x: auto; }
.vm-group-members :deep(.list-view-table-wrapper) { overflow-x: auto; }
@media (max-width: 767px) {
  .vm-group-members :deep(.ant-table), .vm-group-member-candidates :deep(.ant-table) { min-width: 760px; }
}
</style>

<style>
.vm-group-member-modal { overflow: hidden; }
.vm-group-member-modal .ant-modal { top: 0; padding-bottom: 0; max-width: calc(100vw - 32px); }
.vm-group-member-modal .ant-modal-content { display: flex; flex-direction: column; overflow: hidden; max-height: calc(100vh - 48px); max-height: calc(100dvh - 48px); }
.vm-group-member-modal .ant-modal-header, .vm-group-member-modal .ant-modal-footer { flex-shrink: 0; }
.vm-group-member-modal .ant-modal-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.vm-group-member-modal .ant-descriptions-item-label { white-space: nowrap; }
.vm-group-member-modal .ant-descriptions-item-content { overflow-wrap: anywhere; }
</style>
