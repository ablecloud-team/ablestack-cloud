<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <div class="vm-processes">
    <div class="process-toolbar">
      <a-button v-if="canAdminAction" type="primary" :disabled="!primaryAction" @click="openPrimaryAction">
        {{ primaryAction === 'process.restart' ? $t('label.vmprocess.profile.restart') : primaryAction === 'service.restart' || !selected ? $t('label.vmprocess.restart') : $t('label.vmprocess.kill') }}
      </a-button>
      <a-button v-if="canAdminAction && isLinux" :disabled="!actionAvailable('process.terminate', selected)" @click="openAction('process.terminate', selected)">{{ $t('label.vmprocess.terminate') }}</a-button>
      <a-button :loading="refreshing" :disabled="disabled || actionBusy || initializing" @click="refreshAll"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-button :disabled="actionBusy || refreshing || profilesLoading || initializing" @click="checkCapability(true)">{{ $t('label.vmprocess.readiness') }}</a-button>
      <a-button v-if="diagnostic?.install" @click="openToolsDialog">{{ $t('label.vmprocess.tools.title') }}</a-button>
      <a-button v-if="operation && ['UNKNOWN', 'PENDING'].includes(operation.state)" :loading="operationChecking" @click="checkOperation">{{ $t('label.vmprocess.check.result') }}</a-button>
      <a-button v-if="canManageProfiles" :disabled="actionBusy" @click="profilesDialog = true">{{ $t('label.vmprocess.profiles') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.vmprocess.search')" :aria-label="$t('label.vmprocess.search')" :disabled="!snapshotId" @search="searchRows" />
    </div>

    <div v-if="capability || snapshotId" class="process-status">
      <a-tag v-if="snapshotId" :color="partialSnapshot || (stale && !loading) ? 'orange' : 'green'">{{ partialSnapshot ? $t('label.vmprocess.snapshot.partial') : !stale || loading ? $t('label.vmprocess.snapshot.ready') : $t('label.vmprocess.snapshot.stale') }}</a-tag>
      <span v-if="osLabel">{{ osLabel }}</span>
      <span v-if="observedAt">{{ $t('label.vmprocess.observed') }}: {{ $toLocaleDate(observedAt) }}</span>
      <span v-if="snapshotId">{{ $t('label.vmprocess.age') }}: {{ ageSeconds }}s / 10s</span>
      <span v-if="total">{{ $t(partialSnapshot ? 'label.vmprocess.collected' : 'label.vmprocess.total') }}: {{ total }}</span>
    </div>

    <a-alert v-if="disabled && !initializing" class="process-alert" type="warning" show-icon :message="$t('message.vmprocess.disabled')" :description="$t('message.vmprocess.disabled.detail')" />
    <a-alert v-else-if="diagnostic" class="process-alert" type="warning" show-icon :message="$t('message.vmprocess.status.' + diagnostic.kind)" :description="diagnostic.missing?.length ? diagnostic.missing.join(', ') + ' · ' + $t('message.vmprocess.status.' + diagnostic.kind + '.detail') : $t('message.vmprocess.status.' + diagnostic.kind + '.detail')" />
    <a-alert v-if="partialSnapshot" class="process-alert" type="warning" show-icon :message="$t('message.vmprocess.snapshot.partial')" />
    <a-alert v-if="errorText && !initializing" class="process-alert" type="error" show-icon :message="errorText" />
    <a-alert v-if="operation" class="process-alert" :type="operation.state === 'FAILED' ? 'error' : operation.state === 'SUCCEEDED' ? 'success' : 'warning'" show-icon>
      <template #message>{{ $t('label.vmprocess.operation') }}: {{ actionLabel(operation.action) }} · {{ operation.name }} · {{ operation.state }}</template>
      <template #description>
        <span v-if="operation.progress">{{ $t('label.vmprocess.profile.old') }}: {{ $t('label.vmprocess.profile.process.' + operation.progress.oldProcess) }} · {{ $t('label.vmprocess.profile.new') }}: {{ $t('label.vmprocess.profile.process.' + operation.progress.newProcess) }}<br></span>
        <span>{{ operation.state === 'UNKNOWN' ? $t('message.vmprocess.unknown') : operation.message }}</span>
      </template>
    </a-alert>

    <a-table
:columns="columns"
:data-source="rows"
:row-key="rowKey"
:pagination="false"
:scroll="{ x: 820 }"
size="small"
      class="process-table"
      @change="tableChanged">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'select'"><a-radio :checked="rowKey(selected) === rowKey(record)" :disabled="actionBusy" :aria-label="$t('label.vmprocess.select') + ' ' + record.name" @change="selectRow(record)" /></template>
        <template v-else-if="column.key === 'pid'"><span class="process-mono">{{ record.identity.pid }}</span></template>
        <template v-else-if="column.key === 'cpuPercent'">{{ cpuText(record.cpuPercent) }}</template>
        <template v-else-if="column.key === 'memoryBytes'">{{ memoryText(record.memoryBytes) }}</template>
        <template v-else-if="column.key === 'services'">
          <a-tag v-for="profile in rowProfiles(record)" :key="profile.id" color="blue">{{ profile.displayName }} · v{{ profile.version }}</a-tag>
          <a-tag v-for="service in record.services || []" :key="service.name">{{ service.name }}</a-tag>
          <span v-if="!record.services?.length && !rowProfiles(record).length">—</span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-dropdown v-if="canAdminAction" :trigger="['click']" placement="bottomRight">
            <a-button size="small" :disabled="actionBusy" :aria-label="$t('label.actions')"><template #icon><down-outlined /></template></a-button>
            <template #overlay><a-menu>
              <a-menu-item v-for="service in record.services || []" :key="'service:' + service.name" :disabled="!actionAvailable('service.restart', record, service)" @click="openAction('service.restart', record, service)">{{ $t('label.vmprocess.restart') }} · {{ service.name }}</a-menu-item>
              <a-menu-item v-for="profile in rowProfiles(record)" :key="'profile:' + profile.id" :disabled="!actionAvailable('process.restart', record, profile)" @click="openAction('process.restart', record, profile)">{{ $t('label.vmprocess.profile.restart') }} · {{ profile.displayName }}</a-menu-item>
              <a-menu-item v-if="isLinux" key="terminate" :disabled="!actionAvailable('process.terminate', record)" @click="openAction('process.terminate', record)">{{ $t('label.vmprocess.terminate') }}</a-menu-item>
              <a-menu-item key="kill" danger :disabled="!actionAvailable('process.kill', record)" @click="openAction('process.kill', record)">{{ $t('label.vmprocess.kill') }}</a-menu-item>
            </a-menu></template>
          </a-dropdown>
        </template>
      </template>
      <template #emptyText>{{ $t(initializing ? 'message.vmprocess.loading' : disabled ? 'message.vmprocess.disabled' : diagnostic ? 'message.vmprocess.no.snapshot' : 'message.vmprocess.empty') }}</template>
    </a-table>
    <a-pagination :current="page" :page-size="pageSize" :total="total" :show-size-changer="true" :page-size-options="['10', '20', '50', '100']" class="process-pagination" @change="pageChanged" />
    <p class="process-footnote">{{ $t('message.vmprocess.limit') }}</p>

    <a-modal
v-if="confirm"
:visible="!!confirm"
:title="actionLabel(confirm?.action)"
:mask-closable="false"
:confirm-loading="submitting"
:ok-text="actionLabel(confirm?.action)"
:ok-button-props="{ danger: confirm?.action === 'process.kill', disabled: !confirm || !actionAvailable(confirm.action, confirm.row, confirm.service) || (confirm.action === 'process.kill' && !ack) }"
@ok="submitAction"
      @cancel="closeConfirm">
      <a-descriptions v-if="confirm" bordered :column="1" size="small" class="process-confirmation">
        <a-descriptions-item :label="$t('label.vm')">{{ resource.displayname || resource.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmprocess.name')">{{ confirm.row.name }} · PID {{ confirm.row.identity.pid }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmprocess.owner')">{{ confirm.row.owner || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmprocess.identity')"><span class="process-mono">{{ confirm.row.identity.bootId }} / {{ confirm.row.identity.startTicks }}</span></a-descriptions-item>
        <a-descriptions-item :label="$t(confirm.action === 'process.restart' ? 'label.vmprocess.profile' : 'label.vmprocess.service')">{{ confirm.action === 'process.restart' ? confirm.service?.displayName + ' · v' + confirm.service?.version : confirm.service?.name || '—' }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="confirm" class="process-alert" :type="confirm.action === 'process.kill' ? 'error' : 'warning'" show-icon :message="$t(confirm.action === 'process.restart' ? 'message.vmprocess.profile.restart.impact' : confirm.action === 'service.restart' ? 'message.vmprocess.restart.impact' : confirm.action === 'process.kill' ? 'message.vmprocess.kill.impact' : 'message.vmprocess.terminate.impact')" />
      <p>{{ $t('message.vmprocess.revalidate') }}</p>
      <a-checkbox v-if="confirm?.action === 'process.kill'" v-model:checked="ack">{{ $t('message.vmprocess.kill.ack') }}</a-checkbox>
      <template #footer>
        <a-button @click="closeConfirm">{{ $t('label.cancel') }}</a-button>
        <a-button :type="confirm?.action === 'process.kill' ? 'default' : 'primary'" :danger="confirm?.action === 'process.kill'" :loading="submitting" :disabled="!confirm || !actionAvailable(confirm.action, confirm.row, confirm.service) || (confirm.action === 'process.kill' && !ack)" @click="submitAction">{{ actionLabel(confirm?.action) }}</a-button>
      </template>
    </a-modal>
    <VmProcessProfilesDialog :visible="profilesDialog" :resource="resource" :platform="capability?.os?.family" @close="profilesDialog = false" @updated="profilesUpdated" />
    <VmProcessToolsDialog :visible="toolsDialog" :resource="resource" :catalog="toolsDialogCatalog" :capability="toolsDialogCapability" @close="toolsDialog = false" @verified="refreshAll" />
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { requiredRpcs, processOsLabel, processDiagnostic } from './vmProcessDisplay'
import VmProcessProfilesDialog from './VmProcessProfilesDialog.vue'
import VmProcessToolsDialog from './VmProcessToolsDialog.vue'

const actionApis = { 'process.restart': 'restartVirtualMachineProcess', 'process.terminate': 'terminateVirtualMachineProcess', 'process.kill': 'killVirtualMachineProcess', 'service.restart': 'restartVirtualMachineService' }
const result = (json, command) => json?.[command.toLowerCase() + 'response']
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
function uuid () {
  const bytes = new Uint8Array(16)
  window.crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
function errorMessage (error) {
  const data = error?.response?.data || {}
  const envelope = Object.values(data).find(value => value && typeof value === 'object' && value.errortext)
  return envelope?.errortext || data.errortext || error?.message || String(error)
}

export default {
  name: 'VmProcessesTab',
  components: { VmProcessToolsDialog, VmProcessProfilesDialog },
  props: { resource: { type: Object, required: true }, active: { type: Boolean, default: false } },
  emits: ['open-iso'],
  data () {
    return { profiles: [], profilesDialog: false, profilesAvailable: false, capability: null, toolsIso: null, toolsDialog: false, toolsDialogCatalog: null, toolsDialogCapability: null, capabilityLoading: false, refreshing: false, profilesLoading: false, initializing: this.active, disabled: false, rows: [], snapshotId: null, collectionStatus: null, observedAt: null, receivedAt: 0, ageSeconds: 0, total: 0, page: 1, pageSize: 10, search: '', keyword: '', sortBy: 'pid', descending: false, loading: false, errorText: '', snapshotFailure: null, selected: null, confirm: null, ack: false, submitting: false, operation: null, operationChecking: false, generation: 0, disposed: false }
  },
  computed: {
    scopeKey () { return JSON.stringify([this.resource.id, this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token]) },
    apiSet () { return this.$store.getters.apis || {} },
    canAdminAction () { return this.$store.getters.userInfo?.roletype === 'Admin' && Object.values(actionApis).some(api => api in this.apiSet) },
    canManageProfiles () { return this.$store.getters.userInfo?.roletype === 'Admin' && 'manageVirtualMachineProcessProfile' in this.apiSet },
    isLinux () { return this.capability?.os?.family === 'linux' },
    osLabel () { return processOsLabel(this.capability?.os) },
    diagnostic () { return this.initializing ? null : processDiagnostic(this.capability, this.snapshotId, this.snapshotFailure) },
    rpcsReady () { return this.capability?.rpcs && requiredRpcs.every(rpc => this.capability.rpcs[rpc] === 'ENABLED') },
    partialSnapshot () { return this.collectionStatus === 'PARTIAL' },
    stale () { return !this.snapshotId || this.ageSeconds >= 10 || this.resource.state !== 'Running' },
    actionBusy () { return this.submitting || ['PENDING', 'UNKNOWN'].includes(this.operation?.state) },
    primaryAction () {
      if (!this.selected) return null
      const profile = this.rowProfiles(this.selected)[0]
      if (profile && this.actionAvailable('process.restart', this.selected, profile)) return 'process.restart'
      const service = this.selected.services?.[0]
      if (service && this.actionAvailable('service.restart', this.selected, service)) return 'service.restart'
      return this.actionAvailable('process.kill', this.selected) ? 'process.kill' : null
    },
    columns () {
      const col = (key, width, extra = {}) => ({ key, dataIndex: key, title: this.$t('label.vmprocess.' + key), width, ...extra })
      return [col('select', 36, { title: '' }), col('pid', 64, { sorter: true, sortOrder: this.sortBy === 'pid' ? this.descending ? 'descend' : 'ascend' : null }), col('name', 136, { sorter: true, sortOrder: this.sortBy === 'name' ? this.descending ? 'descend' : 'ascend' : null }), col('owner', 90), col('state', 80), col('cpuPercent', 104, { align: 'right', sorter: true, sortOrder: this.sortBy === 'cpuPercent' ? this.descending ? 'descend' : 'ascend' : null }), col('memoryBytes', 100, { align: 'right', sorter: true, sortOrder: this.sortBy === 'memoryBytes' ? this.descending ? 'descend' : 'ascend' : null }), col('services', 140), col('actions', 64, { align: 'right' })]
    }
  },
  watch: {
    active (value) { if (value) this.activate(); else this.deactivate() },
    scopeKey () { this.deactivate(); this.reset(); if (this.active) this.activate() }
  },
  mounted () { if (this.active) this.activate() },
  beforeUnmount () { this.disposed = true; this.deactivate() },
  methods: {
    profilesUpdated (profiles) { this.profiles = profiles; this.profilesAvailable = true },
    rowProfiles (row) { return this.profiles.filter(p => p.registrationState === 'APPROVED' && p.identity?.vmUuid === this.resource.id && p.identity?.pid === row?.identity?.pid && p.identity?.bootId === row?.identity?.bootId && p.identity?.startTicks === row?.identity?.startTicks) },
    async fetchProfiles (token = this.generation) {
      if (!this.current(token) || this.profilesLoading || !('listVirtualMachineProcessProfiles' in this.apiSet) || !this.rpcsReady) return
      this.profilesLoading = true
      try {
        const state = result(await getAPI('listVirtualMachineProcessProfiles', { virtualmachineid: this.resource.id }), 'listVirtualMachineProcessProfiles')?.processprofiles?.processstate
        if (!this.current(token)) return
        if (!Array.isArray(state?.profiles)) throw new Error('unavailable')
        this.profilesUpdated(state.profiles)
      } catch (_) { if (this.current(token)) this.profilesAvailable = false } finally { if (this.current(token)) this.profilesLoading = false }
    },
    rowKey (row) { return row?.identity ? `${row.identity.pid}:${row.identity.startTicks}` : '' },
    cpuText (value) { return value === null || value === undefined ? '—' : `${Number(value).toFixed(1)}%` },
    memoryText (value) { return value === null || value === undefined ? '—' : `${(Number(value) / 1048576).toFixed(1)} MB` },
    actionLabel (action) { return this.$t(action === 'process.restart' ? 'label.vmprocess.profile.restart' : action === 'service.restart' ? 'label.vmprocess.restart' : action === 'process.terminate' ? 'label.vmprocess.terminate' : 'label.vmprocess.kill') },
    storageKey () { return `vm-process-operation:${this.scopeKey}` },
    saveOperation () { try { if (this.operation && ['PENDING', 'UNKNOWN'].includes(this.operation.state)) sessionStorage.setItem(this.storageKey(), JSON.stringify(this.operation)); else sessionStorage.removeItem(this.storageKey()) } catch (_) {} },
    openToolsDialog () {
      this.toolsDialogCatalog = this.toolsIso ? { ...this.toolsIso } : null
      this.toolsDialogCapability = this.capability ? { ...this.capability } : null
      this.toolsDialog = true
    },
    reset () { this.profiles = []; this.profilesAvailable = false; this.profilesDialog = false; this.capability = null; this.toolsIso = null; this.toolsDialog = false; this.initializing = false; this.disabled = false; this.rows = []; this.snapshotId = null; this.collectionStatus = null; this.observedAt = null; this.receivedAt = 0; this.ageSeconds = 0; this.total = 0; this.selected = null; this.confirm = null; this.operation = null; this.errorText = ''; this.snapshotFailure = null; this.page = 1; this.keyword = ''; this.search = '' },
    deactivate () { this.profilesDialog = false; this.toolsDialog = false; this.generation++; clearInterval(this.tickTimer); clearInterval(this.refreshTimer); this.tickTimer = null; this.refreshTimer = null; this.confirm = null; this.loading = false; this.initializing = false; this.capabilityLoading = false; this.refreshing = false; this.profilesLoading = false; this.operationChecking = false; this.submitting = false },
    async activate () {
      if (this.disposed || !this.active) return
      const token = ++this.generation
      this.initializing = !this.snapshotId
      this.refreshing = true
      try {
        try { this.operation = JSON.parse(sessionStorage.getItem(this.storageKey()) || 'null') } catch (_) { this.operation = null }
        this.ageSeconds = this.receivedAt ? Math.floor((Date.now() - this.receivedAt) / 1000) : 0
        this.tickTimer = setInterval(() => { this.ageSeconds = this.receivedAt ? Math.floor((Date.now() - this.receivedAt) / 1000) : 0 }, 1000)
        this.refreshTimer = setInterval(() => { if (this.active && !this.actionBusy && !this.loading && !this.confirm && !this.toolsDialog && !this.profilesDialog) this.refreshAll() }, 9000)
        await this.checkCapability(false, token)
        if (this.current(token) && this.operation) await this.checkOperation()
        if (this.current(token) && !this.disabled && this.rpcsReady && !this.actionBusy) { await this.refreshSnapshot(token); await this.fetchProfiles(token) }
      } finally { if (this.current(token)) { this.initializing = false; this.refreshing = false } }
    },
    current (token) { return !this.disposed && this.active && token === this.generation },
    async checkCapability (refresh = false, token = this.generation) {
      if (!this.current(token) || this.capabilityLoading || !('getVirtualMachineProcessCapabilities' in this.apiSet)) return
      this.capabilityLoading = true
      try {
        const json = await getAPI('getVirtualMachineProcessCapabilities', { virtualmachineid: this.resource.id })
        if (!this.current(token)) return
        const response = result(json, 'getVirtualMachineProcessCapabilities')?.processcapability
        this.capability = response?.processstate || null
        this.toolsIso = response?.toolsiso || null
        this.disabled = false
        this.errorText = ''
        if (refresh && this.rpcsReady) await this.refreshSnapshot(token)
      } catch (error) {
        if (!this.current(token)) return
        const message = errorMessage(error)
        this.disabled = /disabled/i.test(message)
        this.errorText = this.disabled ? '' : message
        this.capability = null; this.toolsIso = null
        const denied = [401, 403, 432, 531].includes(Number(error?.response?.data?.errorresponse?.errorcode || error?.response?.status))
        if (this.disabled || denied) { this.rows = []; this.snapshotId = null; this.collectionStatus = null; this.snapshotFailure = null; this.total = 0; this.selected = null }
      } finally { if (this.current(token)) this.capabilityLoading = false }
    },
    async refreshAll () {
      const token = this.generation
      if (!this.current(token) || this.refreshing || this.initializing || this.profilesLoading || this.capabilityLoading || this.loading || this.actionBusy || this.disabled) return
      this.refreshing = true
      try {
        await this.checkCapability(false, token)
        if (this.current(token) && !this.disabled && this.rpcsReady) { await this.refreshSnapshot(token); await this.fetchProfiles(token) }
      } finally { if (this.current(token)) this.refreshing = false }
    },
    async refreshSnapshot (token = this.generation, forAction = false) {
      if (!this.current(token) || this.loading || (!forAction && this.actionBusy) || this.disabled || !this.rpcsReady) return false
      this.loading = true
      try {
        const response = result(await postAPI('refreshVirtualMachineProcesses', { virtualmachineid: this.resource.id }), 'refreshVirtualMachineProcesses')
        if (!this.current(token)) return
        if (!response?.jobid) throw new Error(this.$t('message.vmprocess.result.missing'))
        let job
        for (let attempt = 0; attempt < 30 && this.current(token); attempt++) {
          job = result(await getAPI('queryAsyncJobResult', { jobid: response.jobid }), 'queryAsyncJobResult')
          if (job?.jobstatus !== 0) break
          await pause(400)
        }
        if (!this.current(token)) return
        if (job?.jobstatus !== 1) throw new Error(job?.jobresult?.errortext || this.$t('message.vmprocess.result.missing'))
        const meta = job.jobresult?.processsnapshot
        const state = meta?.processstate
        if (state?.kind === 'failure' && state.authority?.vmUuid === this.resource.id) {
          this.snapshotFailure = state.error || { code: 'CHECK_FAILED' }
          return false
        }
        if (!state || state.kind !== 'snapshot' || state.authority?.vmUuid !== this.resource.id) throw new Error(this.$t('message.vmprocess.result.missing'))
        this.snapshotFailure = null
        this.errorText = ''
        this.snapshotId = state.snapshotId
        this.observedAt = state.observedAt
        this.receivedAt = Date.now()
        this.ageSeconds = 0
        const pageLoaded = await this.loadPage(token)
        if (this.current(token)) {
          const selectedIdentity = this.selected?.identity
          this.selected = pageLoaded && selectedIdentity
            ? this.rows.find(row => row.identity?.pid === selectedIdentity.pid && row.identity?.bootId === selectedIdentity.bootId && row.identity?.startTicks === selectedIdentity.startTicks) || null
            : null
        }
        return pageLoaded === true
      } catch (error) { if (this.current(token)) this.errorText = errorMessage(error); return false } finally { if (this.current(token)) this.loading = false }
    },
    async loadPage (token = this.generation) {
      if (!this.current(token) || !this.snapshotId) return
      const request = { virtualmachineid: this.resource.id, snapshotid: this.snapshotId, keyword: this.keyword || undefined, sortby: this.sortBy, descending: this.descending, page: this.page, pagesize: this.pageSize }
      const key = JSON.stringify(request)
      this.pageRequest = key
      try {
        const state = result(await getAPI('listVirtualMachineProcesses', request), 'listVirtualMachineProcesses')?.processsnapshot
        if (!this.current(token) || this.pageRequest !== key) return
        if (state?.stale || state?.processstate?.kind !== 'snapshot') { this.ageSeconds = 10; throw new Error(this.$t('message.vmprocess.stale')) }
        if (state.processstate.authority?.vmUuid !== this.resource.id || state.processstate.snapshotId !== this.snapshotId) throw new Error(this.$t('message.vmprocess.result.missing'))
        this.collectionStatus = state.processstate.status
        this.rows = state.processstate.processes || []
        this.total = state.count || 0
        return true
      } catch (error) { if (this.current(token) && this.pageRequest === key) this.errorText = errorMessage(error); return false }
    },
    selectRow (row) { if (!this.actionBusy) this.selected = row },
    searchRows () { this.keyword = this.search.trim(); this.page = 1; this.selected = null; if (this.stale) this.refreshSnapshot(); else this.loadPage() },
    tableChanged (_pagination, _filters, sorter) { if (!sorter?.columnKey) return; this.sortBy = sorter.columnKey; this.descending = sorter.order === 'descend'; this.page = 1; this.selected = null; this.loadPage() },
    pageChanged (page, size) { this.page = page; this.pageSize = size; this.selected = null; this.loadPage() },
    actionAvailable (action, row, service = null) {
      if (!row || !this.canAdminAction || !(actionApis[action] in this.apiSet) || this.actionBusy || this.disabled || !this.rpcsReady || this.resource.state !== 'Running') return false
      if (row.identity?.pid <= 1 || row.identity?.vmUuid !== this.resource.id) return false
      if (action === 'process.restart') return this.capability?.allowedActions?.some(item => ['process.kill', 'service.restart'].includes(item)) && this.profilesAvailable && !!service && this.rowProfiles(row).some(item => item.id === service.id && item.version === service.version && item.definitionHash === service.definitionHash)
      if (!this.capability?.allowedActions?.includes(action)) return false
      if (action === 'process.terminate' && !this.isLinux) return false
      // SCM service processes are protected from process.kill by the guest adapter.
      if (action === 'process.kill' && !this.isLinux && (row.identity.pid <= 4 || (row.services || []).some(item => item.manager === 'scm'))) return false
      if (action === 'service.restart' && /^ableprofile-[a-f0-9-]{36}\.service$/.test(service?.name || '')) return false
      if (action === 'service.restart' && (!service || !(row.services || []).some(item => item.name === service.name))) return false
      return true
    },
    openPrimaryAction () { this.openAction(this.primaryAction, this.selected, this.primaryAction === 'process.restart' ? this.rowProfiles(this.selected)[0] : this.primaryAction === 'service.restart' ? this.selected?.services?.[0] : null) },
    openAction (action, row, service = null) { if (!this.actionAvailable(action, row, service)) return; this.selected = row; this.confirm = { action, row, service }; this.ack = false },
    closeConfirm () { if (!this.submitting) this.confirm = null },
    async submitAction () {
      const choice = this.confirm
      if (!choice || this.submitting || !this.actionAvailable(choice.action, choice.row, choice.service) || (choice.action === 'process.kill' && !this.ack)) return
      const token = this.generation
      this.submitting = true
      try {
        const saved = choice.row.identity
        for (let attempt = 0; (this.refreshing || this.capabilityLoading || this.profilesLoading || this.loading) && attempt < 150 && this.current(token); attempt++) await pause(100)
        let refreshed = await this.refreshSnapshot(token, true)
        if (!refreshed && this.current(token) && !this.disabled && this.rpcsReady) refreshed = await this.refreshSnapshot(token, true)
        if (!this.current(token)) return
        if (!refreshed) throw new Error(this.errorText || this.$t('message.vmprocess.snapshot.refresh.failed'))
        if (!this.rows.some(row => row.identity?.pid === saved.pid && row.identity?.bootId === saved.bootId && row.identity?.startTicks === saved.startTicks)) throw new Error(this.$t('message.vmprocess.stale'))
        const requestId = uuid()
        const pending = { requestId, action: choice.action, name: choice.row.name, pid: saved.pid, state: 'PENDING', message: '' }
        this.operation = pending; this.saveOperation(); this.confirm = null
        const api = actionApis[choice.action]
        const args = { virtualmachineid: this.resource.id, requestid: requestId, snapshotid: this.snapshotId, pid: saved.pid }
        if (choice.action === 'process.restart') { args.profileid = choice.service.id; args.profileversion = choice.service.version } else if (choice.service) args.servicename = choice.service.name
        const response = result(await postAPI(api, args), api)
        if (!this.current(token)) return
        if (!response?.jobid) throw new Error(this.$t('message.vmprocess.result.missing'))
        let job
        for (let attempt = 0; attempt < 105 && this.current(token); attempt++) {
          job = result(await getAPI('queryAsyncJobResult', { jobid: response.jobid }), 'queryAsyncJobResult')
          if (job?.jobstatus !== 0) break
          await pause(1000)
        }
        if (!this.current(token)) return
        if (job?.jobstatus === 1 && job.jobresult?.processoperation?.processstate?.state === 'SUCCEEDED') {
          this.operation = { ...pending, state: 'SUCCEEDED', progress: job.jobresult.processoperation.processstate.progress, message: this.$t('message.vmprocess.succeeded') }
          this.saveOperation(); this.submitting = false; await this.refreshSnapshot(token); await this.fetchProfiles(token)
        } else {
          this.operation = { ...pending, state: 'UNKNOWN', jobCompleted: job?.jobstatus === 2, message: '' }; this.saveOperation()
          await this.checkOperation()
        }
      } catch (error) {
        if (this.current(token)) { if (this.operation?.state === 'PENDING') this.operation = { ...this.operation, state: 'UNKNOWN' }; this.saveOperation(); this.errorText = errorMessage(error) }
      } finally { this.submitting = false }
    },
    async checkOperation () {
      if (!this.operation || this.operationChecking || !this.active) return
      const token = this.generation
      const pending = this.operation
      this.operationChecking = true
      try {
        const state = result(await getAPI('getVirtualMachineProcessOperation', { virtualmachineid: this.resource.id, requestid: pending.requestId }), 'getVirtualMachineProcessOperation')?.processoperation?.processstate
        if (!this.current(token) || this.operation?.requestId !== pending.requestId) return
        if (['SUCCEEDED', 'FAILED', 'PARTIAL'].includes(state?.state)) {
          this.operation = { ...pending, state: state.state, progress: state.progress, message: state.state === 'PARTIAL' ? this.$t('message.vmprocess.profile.partial') : pending.action === 'process.restart' && state.state === 'FAILED' ? this.$t('message.vmprocess.profile.rejected') : state.error?.code === 'PROTECTED_TARGET' ? this.$t('message.vmprocess.protected') : state.error?.message || this.$t('message.vmprocess.succeeded'), operationId: state.operationId }
          this.saveOperation()
          if (['SUCCEEDED', 'PARTIAL'].includes(state.state)) { await this.refreshSnapshot(token); await this.fetchProfiles(token) }
        } else { this.operation = { ...pending, state: 'UNKNOWN', progress: state?.progress, operationId: state?.operationId }; this.saveOperation() }
      } catch (error) {
        if (this.current(token)) {
          const message = errorMessage(error)
          if (pending.jobCompleted && /not found/i.test(message)) {
            this.operation = { ...pending, state: 'FAILED', message }; this.saveOperation()
          } else {
            this.errorText = message
          }
        }
      } finally { this.operationChecking = false }
    }
  }
}
</script>

<style scoped lang="scss">
.vm-processes { color: var(--ui-text-primary); }
.process-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 16px; }
.process-toolbar :deep(.ant-input-search) { margin-left: auto; width: 275px; }
.process-status { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: var(--ui-text-secondary); margin-bottom: 12px; }
.process-alert { margin: 12px 0; }
.process-table :deep(.ant-table), .process-table :deep(.ant-table-thead > tr > th), .process-table :deep(.ant-table-tbody > tr > td) { color: var(--ui-text-primary); background: var(--ui-bg-surface); border-color: var(--ui-border); }
.process-table :deep(.ant-table-tbody > tr:hover > td) { background: var(--ui-bg-page); }
.process-table :deep(.ant-table-thead > tr > th) { background: var(--ui-bg-page); color: var(--ui-text-secondary); }
.process-confirmation :deep(.ant-descriptions-item-label) { background: var(--ui-bg-page); color: var(--ui-text-primary); }
.process-confirmation :deep(.ant-descriptions-item-content) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.process-confirmation :deep(.ant-descriptions-view), .process-confirmation :deep(.ant-descriptions-row), .process-confirmation :deep(.ant-descriptions-item-label), .process-confirmation :deep(.ant-descriptions-item-content) { border-color: var(--ui-border); }
.process-pagination { margin-top: 16px; text-align: right; }
.process-pagination :deep(.ant-pagination-item-link) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); border-color: var(--ui-border); }
.process-footnote { margin-top: 12px; color: var(--ui-text-secondary); font-size: 12px; }
.process-mono { font-family: Consolas, monospace; font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 768px) { .process-toolbar :deep(.ant-input-search) { width: 100%; } }
</style>
