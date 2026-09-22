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
  <div class="vm-backups">
    <div class="backup-toolbar">
      <a-button v-if="allowed('createBackup')" type="primary" :disabled="!visible('createBackup') || disabled('createBackup')" @click="openAction('createBackup')"><template #icon><plus-outlined /></template>{{ $t('label.create.backup') }}</a-button>
      <a-button @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.vmsnapshot.refresh') }}</a-button>
      <a-dropdown v-if="settings.length" :trigger="['click']"><a-button>{{ $t('label.vmbackup.settings') }} <down-outlined /></a-button><template #overlay><a-menu>
        <a-menu-item v-for="action in settings" :key="action.api" :danger="action.api === 'removeVirtualMachineFromBackupOffering'" :disabled="disabled(action.api)" @click="openAction(action.api)">{{ $t(action.label) }}</a-menu-item>
      </a-menu></template></a-dropdown>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" @search="searchBackups" />
    </div>
    <p class="backup-secondary">{{ $t('label.backupofferingname') }}: {{ resource.backupofferingname || $t('label.none') }} <template v-if="resource.backupprovider"> · {{ resource.backupprovider }}</template></p>
    <a-alert v-if="!resource.backupofferingid" class="backup-alert" type="info" show-icon :message="$t('message.vmbackup.assign')">
      <template #description><a-button v-if="visible('assignVirtualMachineToBackupOffering')" :disabled="disabled('assignVirtualMachineToBackupOffering')" @click="openAction('assignVirtualMachineToBackupOffering')">{{ $t('label.backup.offering.assign') }}</a-button></template>
    </a-alert>
    <a-alert v-if="listRefreshFailed" class="backup-alert" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-alert v-if="pending || unknown" class="backup-alert" :type="unknown ? 'warning' : 'info'" show-icon :message="$t(unknown ? 'message.job.result.unknown' : 'message.vmbackup.busy')" />
    <a-table :columns="columns" :data-source="rows" row-key="id" :loading="loading" :pagination="false" :scroll="{ x: 900 }" size="small">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'"><router-link :to="'/backup/' + record.id">{{ record.name || record.id }}</router-link><small v-if="provider(record) === 'kboss'" class="backup-secondary">{{ $t('label.compressionstatus') }}: {{ record.compressionstatus || '—' }} · {{ $t('label.validationstatus') }}: {{ record.validationstatus || '—' }}</small></template>
        <template v-else-if="column.key === 'status'"><status :text="record.status" /> {{ record.status }}</template>
        <template v-else-if="column.key === 'size'">{{ bytes(record.size) }} / {{ bytes(record.virtualsize) }}</template>
        <template v-else-if="column.key === 'type'">{{ record.type || '—' }} / {{ record.intervaltype || '—' }}</template>
        <template v-else-if="column.key === 'created'">{{ $toLocaleDate(record.created) }}</template>
        <template v-else-if="column.key === 'actions'">
          <div class="backup-actions">
            <a-tooltip :title="disabled('restoreBackup', record) ? $t('message.vmbackup.unavailable') : ''"><span v-if="allowed('restoreBackup')"><a-button size="small" :disabled="!visible('restoreBackup', record) || disabled('restoreBackup', record)" @click="openAction('restoreBackup', record)">{{ $t('label.backup.restore') }}</a-button></span></a-tooltip>
            <a-dropdown :trigger="['click']" placement="bottomRight"><a-button size="small" :aria-label="$t('label.actions')"><template #icon><down-outlined /></template></a-button><template #overlay><a-menu>
              <a-menu-item key="details"><router-link :to="'/backup/' + record.id">{{ $t('label.details') }}</router-link></a-menu-item>
              <a-menu-item v-for="action in rowActions(record)" :key="action.api" :danger="action.api === 'deleteBackup'" :disabled="disabled(action.api, record)" @click="openAction(action.api, record)">{{ $t(action.label) }}</a-menu-item>
            </a-menu></template></a-dropdown>
          </div>
        </template>
      </template>
    </a-table>
    <a-pagination v-model:current="page" v-model:pageSize="pageSize" :total="total" :show-size-changer="true" class="backup-pagination" @change="fetchData" />
    <a-modal :visible="!!selected" :title="$t(selectedAction.label || '')" :footer="null" :width="modalWidth" :mask-closable="false" @cancel="closeAction">
      <div v-if="selected" class="backup-dialog">
        <component v-if="selectedAction.component" :is="modalComponent" :resource="selected" @close-action="closeAction" @refresh="refresh" />
        <template v-else>
          <a-descriptions bordered :column="1" size="small" class="backup-description">
            <a-descriptions-item :label="$t('label.vm')">{{ resource.displayname || resource.name }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.backup')">{{ selected.name || selected.id }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.id')">{{ selected.id }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.created')">{{ $toLocaleDate(selected.created) }}</a-descriptions-item>
          </a-descriptions>
          <a-form layout="vertical" class="backup-form">
            <template v-if="actionApi === 'restoreBackup'">
              <a-form-item :label="$t('label.quickrestore')"><a-switch v-model:checked="quickrestore" /></a-form-item>
              <a-form-item v-if="isAdmin && quickrestore && allowed('listHosts')" :label="$t('label.hostid')"><a-select v-model:value="hostId" allow-clear :loading="hostsLoading" :placeholder="$t('label.vmbackup.automatic')"><a-select-option v-for="host in hosts" :key="host.id" :value="host.id">{{ host.name }}</a-select-option></a-select></a-form-item>
            </template>
            <a-form-item v-else :label="$t('label.forced')" :extra="$t('message.vmbackup.force')"><a-switch v-model:checked="forced" /></a-form-item>
          </a-form>
          <a-alert type="warning" show-icon :message="$t(actionApi === 'deleteBackup' ? 'message.vmbackup.delete' : 'message.vmbackup.restore')" />
          <div class="backup-footer"><a-button :disabled="submitting" @click="closeAction">{{ $t('label.cancel') }}</a-button><a-button type="primary" :danger="actionApi === 'deleteBackup'" :loading="submitting" :disabled="pending || unknown" @click="submitAction">{{ $t(selectedAction.label) }}</a-button></div>
        </template>
      </div>
    </a-modal>
  </div>
</template>

<script>
import { unref } from 'vue'
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'
import eventBus from '@/config/eventBus'
import Status from '@/components/widgets/Status'

const vmApis = ['createBackup', 'assignVirtualMachineToBackupOffering', 'removeVirtualMachineFromBackupOffering', 'createBackupSchedule', 'finishBackupChain']
const rowApis = ['restoreVolumeFromBackupAndAttachToVM', 'createVMFromBackup', 'deleteBackup']
const activeStates = ['Allocated', 'Queued', 'BackingUp', 'ReadyForImageTransfer', 'FinalizingImageTransfer', 'Restoring']
export default {
  name: 'VmBackupsTab',
  components: { Status },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => !!vm.resource.id })],
  inject: { parentFetchData: { default: null } },
  props: { resource: { type: Object, required: true } },
  data () { return { rows: [], total: 0, page: 1, pageSize: 10, search: '', keyword: '', loading: false, selected: null, actionApi: '', submitting: false, pending: false, unknown: false, opening: false, quickrestore: false, forced: false, hostId: undefined, hosts: [], hostsLoading: false } },
  computed: {
    scopeKey () { return JSON.stringify([this.resource.id, this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token]) },
    isAdmin () { return this.$store.getters.userInfo?.roletype === 'Admin' },
    selectedAction () { return this.definition(this.actionApi) || {} },
    modalComponent () { return unref(this.selectedAction.component) },
    modalWidth () { return this.actionApi === 'createVMFromBackup' ? '90%' : 680 },
    settings () { return vmApis.filter(api => api !== 'createBackup' && this.visible(api)).map(this.definition) },
    columns () { return ['name', 'status', 'size', 'type', 'created', 'actions'].map(key => ({ key, dataIndex: key, title: this.$t(key === 'size' ? 'label.vmbackup.sizes' : key === 'type' ? 'label.vmbackup.types' : 'label.' + key), ...(key === 'actions' ? { width: 165, fixed: 'right' } : {}) })) }
  },
  watch: {
    scopeKey () { this.rows = []; this.total = 0; this.page = 1; this.search = ''; this.keyword = ''; this.selected = null; this.opening = false; this.submitting = false; this.pending = false; this.unknown = false; this.fetchData() }
  },
  created () { this.fetchData(); this.onBackupJobComplete = () => this.refresh(); eventBus.on('async-job-complete', this.onBackupJobComplete) },
  beforeUnmount () { eventBus.off('async-job-complete', this.onBackupJobComplete) },
  methods: {
    allowed (api) { return api in this.$store.getters.apis },
    definition (api) { return (vmApis.includes(api) ? compute.children.find(item => item.name === 'vm') : storage.children.find(item => item.name === 'backup'))?.actions.find(action => action.api === api) },
    provider (row) { return (row?.provider || this.resource.backupprovider || '').toLowerCase() },
    context (row = this.resource) { return row === this.resource ? row : { ...row, provider: this.provider(row) } },
    visible (api, row = this.resource) { const action = this.definition(api); return !!action && this.allowed(api) && (!action.show || !!action.show(this.context(row), this.$store.getters)) },
    disabled (api, row = this.resource) {
      const action = this.definition(api)
      return !this.visible(api, row) || this.opening || this.submitting || this.pending || this.unknown || this.rows.some(item => activeStates.includes(item.status)) || this.resource.hostcontrolstate === 'Offline' || !!action?.disabled?.(this.context(row), this.$store.getters, [])
    },
    rowActions (row) { return rowApis.filter(api => this.visible(api, row)).map(this.definition) },
    bytes (value) { return value == null ? '—' : (Number(value) / 1073741824).toFixed(2) + ' GB' },
    async fetchData () {
      if (!this.resource.id || !this.allowed('listBackups')) return
      const request = this.listRequestToken('fetchData'); this.loading = !request.loaded
      try {
        const result = await getAPI('listBackups', { virtualmachineid: this.resource.id, listall: true, listvmdetails: true, page: this.page, pagesize: this.pageSize, name: this.keyword || undefined })
        if (!this.isListRequestCurrent('fetchData', request)) return
        const data = result.listbackupsresponse; const rows = (data.backup || []).filter(row => row.virtualmachineid === this.resource.id)
        if (JSON.stringify(rows) !== JSON.stringify(this.rows)) this.rows = rows
        this.total = data.count || 0
        if (!rows.length && this.page > 1) { this.page = Math.max(1, Math.ceil(this.total / this.pageSize)); return this.fetchData() }
      } catch (error) { if (this.isListRequestCurrent('fetchData', request)) { request.failed = true; this.listRefreshFailed = true } } finally { if (this.isListRequestCurrent('fetchData', request)) this.loading = false }
    },
    searchBackups () { this.keyword = this.search.trim(); this.page = 1; this.fetchData() },
    refresh () { if (!this.listRefreshDisposed) { this.fetchData(); if (this.parentFetchData) this.parentFetchData() } },
    closeAction () { if (!this.submitting) { this.selected = null; this.refresh() } },
    async openAction (api, row = this.resource) {
      if (this.disabled(api, row)) return
      const scope = this.scopeKey; this.opening = true
      try {
        const action = this.definition(api)
        if (vmApis.includes(api) && !action.component) {
          eventBus.emit('exec-action', { action: { ...action, resource: this.resource }, isGroupAction: false }); return
        }
        let fresh = row
        if (!vmApis.includes(api)) {
          const result = await getAPI('listBackups', { id: row.id, virtualmachineid: this.resource.id, listvmdetails: true })
          fresh = result.listbackupsresponse.backup?.find(item => item.id === row.id && item.virtualmachineid === this.resource.id)
          if (scope !== this.scopeKey || this.listRefreshDisposed) return
          if (!fresh || !this.visible(api, fresh)) throw new Error(this.$t('message.vmbackup.unavailable'))
        }
        this.actionApi = api; this.selected = { ...fresh }; this.quickrestore = false; this.forced = false; this.hostId = undefined; this.hosts = []
        if (api === 'restoreBackup' && this.isAdmin && this.allowed('listHosts')) {
          this.hostsLoading = true
          try {
            const response = await getAPI('listHosts', { zoneid: this.resource.zoneid, type: 'Routing', state: 'Up', resourcestate: 'Enabled', listall: true })
            if (scope === this.scopeKey && !this.listRefreshDisposed) this.hosts = response.listhostsresponse.host || []
          } finally { this.hostsLoading = false }
        }
      } catch (error) { if (scope === this.scopeKey && !this.listRefreshDisposed) this.$notifyError(error) } finally { if (scope === this.scopeKey) this.opening = false }
    },
    async submitAction () {
      if (this.submitting || this.pending || this.unknown || !this.selected) return
      const api = this.actionApi; const selected = { ...this.selected }; const scope = this.scopeKey
      if (!['restoreBackup', 'deleteBackup'].includes(api) || this.disabled(api, selected)) return
      const params = { id: selected.id, ...(api === 'deleteBackup' ? { forced: this.forced } : { quickrestore: this.quickrestore }) }
      if (api === 'restoreBackup' && this.isAdmin && this.quickrestore && this.hostId) params.hostid = this.hostId
      this.submitting = true
      let sent = false
      try {
        const [backups, machines] = await Promise.all([getAPI('listBackups', { id: selected.id, virtualmachineid: this.resource.id, listvmdetails: true }), getAPI('listVirtualMachines', { id: this.resource.id })])
        if (scope !== this.scopeKey || this.listRefreshDisposed) return
        const fresh = backups.listbackupsresponse.backup?.find(item => item.id === selected.id && item.virtualmachineid === this.resource.id)
        const vm = machines.listvirtualmachinesresponse.virtualmachine?.find(item => item.id === this.resource.id)
        if (!fresh || !vm || vm.hostcontrolstate === 'Offline' || activeStates.includes(fresh.status) || !this.visible(api, fresh)) throw new Error(this.$t('message.vmbackup.unavailable'))
        if (!this.isAdmin || !this.quickrestore || !this.allowed('listHosts')) delete params.hostid
        sent = true
        const result = await postAPI(api, params)
        if (scope !== this.scopeKey || this.listRefreshDisposed) return
        const jobId = result[api.toLowerCase() + 'response']?.jobid
        if (!jobId) { this.unknown = true; throw new Error(this.$t('message.job.result.unknown')) }
        this.selected = null; this.pending = true
        const refresh = () => { if (scope === this.scopeKey && !this.listRefreshDisposed) { this.pending = false; this.unknown = false; this.refresh() } }
        this.$pollJob({ jobId, originalPage: this.$route.path, title: this.$t(this.definition(api).label), description: selected.name || selected.id, resourceId: selected.id, action: { api, resource: selected, isFetchData: false }, successMethod: refresh, errorMethod: refresh, catchMethod: () => { if (scope === this.scopeKey && !this.listRefreshDisposed) { this.pending = false; this.unknown = true } } }).catch(() => { if (scope === this.scopeKey && !this.listRefreshDisposed && this.pending) { this.pending = false; this.unknown = true } })
      } catch (error) { if (scope === this.scopeKey && !this.listRefreshDisposed) { if (sent && !error.response) this.unknown = true; this.$notifyError(error) } } finally { if (scope === this.scopeKey) this.submitting = false }
    }
  }
}
</script>

<style scoped lang="scss">
.backup-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.backup-toolbar :deep(.ant-input-search) { margin-left: auto; width: 280px; }
.backup-secondary { display: block; margin-top: 6px; color: var(--ui-text-secondary); }
.backup-alert { margin: 12px 0; }
.backup-actions { display: inline-flex; align-items: center; gap: 8px; white-space: nowrap; }
.backup-actions > span { display: inline-flex; }
.backup-actions :deep(.ant-btn) { height: 24px; display: inline-flex; align-items: center; justify-content: center; margin: 0; }
.backup-pagination { margin-top: 20px; text-align: right; }
.backup-pagination :deep(.ant-pagination-item-link) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); border-color: var(--ui-border); }
.backup-form { margin-top: 20px; }
.backup-form :deep(.ant-form-item-extra) { margin-top: 8px; color: var(--ui-text-secondary); }
.backup-description :deep(.ant-descriptions-item-label) { background: var(--ui-bg-page); color: var(--ui-text-primary); }
.backup-description :deep(.ant-descriptions-item-content) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); }
.backup-description :deep(.ant-descriptions-view), .backup-description :deep(.ant-descriptions-row), .backup-description :deep(.ant-descriptions-item-label), .backup-description :deep(.ant-descriptions-item-content) { border-color: var(--ui-border); }
.backup-footer, .backup-dialog :deep(.action-button), .backup-dialog :deep(.card-footer) { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; margin-top: 20px; }
.backup-dialog :deep(.action-button .ant-btn), .backup-dialog :deep(.card-footer .ant-btn) { margin: 0 !important; }
.backup-dialog :deep(.form-layout), .backup-dialog :deep(.backup-layout) { width: 100%; max-width: 100%; }
@media (max-width: 768px) { .backup-toolbar :deep(.ant-input-search) { width: 100%; } }
</style>
