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
  <div class="vm-snapshots">
    <div class="snapshot-toolbar">
      <a-button v-if="canCreate" type="primary" :disabled="createDisabled" @click="createSnapshot">
        <template #icon><plus-outlined /></template>{{ $t('label.action.vmsnapshot.create') }}
      </a-button>
      <a-button @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.vmsnapshot.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" @search="searchSnapshots" />
    </div>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" class="snapshot-alert" />
    <a-alert v-if="busy" type="info" show-icon :message="$t('message.vmsnapshot.busy')" class="snapshot-alert" />
    <a-table :columns="columns" :data-source="rows" row-key="id" :loading="loading" :pagination="false" :scroll="{ x: 700 }" size="small">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'displayname'">
          <router-link :to="'/vmsnapshot/' + record.id">{{ record.displayname || record.name }}</router-link>
          <a-tag v-if="record.current" color="blue" class="current-tag">{{ $t('label.current') }}</a-tag>
          <small v-if="record.parentName" class="snapshot-parent">{{ $t('label.parentName') }}: {{ record.parentName }}</small>
        </template>
        <template v-else-if="column.key === 'state'"><status :text="record.state" /> {{ record.state }}</template>
        <template v-else-if="column.key === 'type'">{{ typeLabel(record.type) }}</template>
        <template v-else-if="column.key === 'created'">{{ $toLocaleDate(record.created) }}</template>
        <template v-else-if="column.key === 'actions'">
          <div class="snapshot-actions">
            <loading-outlined v-if="rowInProgress(record)" :aria-label="$t('label.loading')" />
            <a-tooltip v-if="allowed('revertToVMSnapshot')" :title="reason('revertToVMSnapshot', record) ? $t(reason('revertToVMSnapshot', record)) : ''">
              <span class="restore-button"><a-button type="link" size="small" :disabled="!!reason('revertToVMSnapshot', record)" @click="openAction('revertToVMSnapshot', record)">{{ $t('label.action.vmsnapshot.revert') }}</a-button></span>
            </a-tooltip>
            <a-dropdown :trigger="['click']" placement="bottomRight">
              <a-button size="small" :aria-label="$t('label.actions')"><template #icon><down-outlined /></template></a-button>
              <template #overlay><a-menu>
                <a-menu-item key="details"><router-link :to="'/vmsnapshot/' + record.id">{{ $t('label.details') }}</router-link></a-menu-item>
                <a-menu-item v-if="allowed('revertToVMSnapshot')" key="restore" class="mobile-restore" :disabled="!!reason('revertToVMSnapshot', record)" @click="openAction('revertToVMSnapshot', record)">{{ $t('label.action.vmsnapshot.revert') }}</a-menu-item>
                <a-menu-item v-if="allowed('createSnapshotFromVMSnapshot') && record.hypervisor === 'KVM'" key="volume" :disabled="!!reason('createSnapshotFromVMSnapshot', record)" @click="volumeSnapshot = record">{{ $t('label.action.create.snapshot.from.vmsnapshot') }}</a-menu-item>
                <a-menu-divider v-if="allowed('deleteVMSnapshot')" />
                <a-menu-item v-if="allowed('deleteVMSnapshot')" key="delete" danger :disabled="!!reason('deleteVMSnapshot', record)" @click="openAction('deleteVMSnapshot', record)">{{ $t('label.action.vmsnapshot.delete') }}</a-menu-item>
              </a-menu></template>
            </a-dropdown>
          </div>
          <small v-if="allowed('revertToVMSnapshot') && reason('revertToVMSnapshot', record)" class="snapshot-parent">{{ $t(reason('revertToVMSnapshot', record)) }}</small>
        </template>
      </template>
      <template #emptyText>{{ $t(listRefreshFailed ? 'message.vmsnapshot.load.failed' : 'message.vmsnapshot.empty') }}</template>
    </a-table>
    <a-pagination v-model:current="page" v-model:pageSize="pageSize" :total="total" :show-size-changer="true" class="snapshot-pagination" @change="fetchData" />
    <a-modal :visible="!!selected" :title="$t(actionLabel)" :confirm-loading="submitting" :ok-text="$t(actionLabel)" :ok-button-props="{ danger: actionApi === 'deleteVMSnapshot', disabled: !!confirmationReason }" @ok="submitAction" @cancel="cancelAction">
      <template v-if="selected">
        <a-descriptions class="snapshot-confirmation" :column="1" bordered size="small">
          <a-descriptions-item :label="$t('label.vm')">{{ resource.displayname || resource.name }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.vm.snapshot')">{{ selected.displayname || selected.name }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.type')">{{ typeLabel(selected.type) }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.created')">{{ $toLocaleDate(selected.created) }}</a-descriptions-item>
        </a-descriptions>
        <a-alert type="warning" show-icon :message="$t(actionApi === 'deleteVMSnapshot' ? 'message.vmsnapshot.delete.confirm' : 'message.vmsnapshot.restore.confirm')" class="snapshot-alert" />
        <p v-if="actionApi === 'deleteVMSnapshot' && selected.hypervisor === 'KVM' && selected.type === 'DiskAndMemory'">{{ $t('message.vmsnapshot.delete.pause') }}</p>
        <p v-if="confirmationReason" role="status">{{ $t(confirmationReason) }}</p>
      </template>
    </a-modal>
    <a-modal :visible="!!volumeSnapshot" :title="$t('label.action.create.snapshot.from.vmsnapshot')" :footer="null" @cancel="volumeSnapshot = null">
      <CreateSnapshotFromVMSnapshot v-if="volumeSnapshot" full-width :resource="volumeSnapshot" @close-action="volumeSnapshot = null" />
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { snapshotBusy, snapshotActionReason, snapshotJobs } from '@/utils/vmSnapshotActions'
import compute from '@/config/section/compute'
import eventBus from '@/config/eventBus'
import Status from '@/components/widgets/Status'
import CreateSnapshotFromVMSnapshot from '@/views/storage/CreateSnapshotFromVMSnapshot.vue'

export default {
  name: 'VmSnapshotsTab',
  components: { Status, CreateSnapshotFromVMSnapshot },
  mixins: [listRefreshMixin(['fetchData'], { interval: 10000, active: vm => !!vm.resource.id })],
  inject: { parentFetchData: { default: null } },
  props: { resource: { type: Object, required: true } },
  data () {
    return { rows: [], page: 1, pageSize: 10, total: 0, search: '', keyword: '', loading: false, selected: null, actionApi: '', submitting: false, volumeSnapshot: null }
  },
  computed: {
    busy () { return snapshotBusy(this.resource.id) || this.submitting || this.rows.some(row => ['Creating', 'Reverting', 'Expunging'].includes(row.state)) },
    createDefinition () { return compute.children.find(item => item.name === 'vm').actions.find(action => action.api === 'createVMSnapshot') },
    canCreate () { return this.allowed('createVMSnapshot') && this.createDefinition.show(this.resource, this.$store.getters) },
    createDisabled () { return this.busy || this.createDefinition.disabled(this.resource, this.$store.getters, []) },
    actionLabel () { return this.actionApi === 'deleteVMSnapshot' ? 'label.action.vmsnapshot.delete' : 'label.action.vmsnapshot.revert' },
    confirmationReason () { return this.selected ? this.reason(this.actionApi, this.selected, false) : '' },
    scopeKey () { return JSON.stringify([this.resource.id, this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token]) },
    columns () {
      return ['displayname', 'state', 'type', 'created', 'actions'].map(key => ({ key, dataIndex: key, title: this.$t('label.' + key), ...(key === 'actions' ? { width: 190, fixed: 'right' } : {}) }))
    }
  },
  watch: {
    scopeKey () {
      this.rows = []; this.total = 0; this.page = 1; this.keyword = ''; this.search = ''
      this.selected = null; this.volumeSnapshot = null
      this.fetchData()
    }
  },
  created () {
    this.fetchData()
    this.onJobComplete = () => { if (!this.listRefreshDisposed) this.fetchData() }
    eventBus.on('async-job-complete', this.onJobComplete)
  },
  beforeUnmount () { eventBus.off('async-job-complete', this.onJobComplete) },
  methods: {
    rowInProgress (record) { return Object.values(snapshotJobs).some(job => job.snapshotId === record.id && !job.unknown) },
    allowed (api) { return api in this.$store.getters.apis },
    typeLabel (type) { return this.$t(type === 'DiskAndMemory' ? 'label.vmsnapshot.disk.memory' : type === 'Disk' ? 'label.vmsnapshot.disk' : type) },
    reason (api, record, includeSubmit = true) {
      if (!this.allowed(api)) return 'message.vmsnapshot.permission'
      return snapshotActionReason(api, record, this.resource, snapshotBusy(this.resource.id) || (includeSubmit && this.submitting) || this.rows.some(row => ['Creating', 'Reverting', 'Expunging'].includes(row.state) && !(api === 'deleteVMSnapshot' && row.id === record.id && row.state === 'Expunging')))
    },
    async fetchData () {
      if (!this.resource.id || !this.allowed('listVMSnapshot')) return
      const request = this.listRequestToken('fetchData')
      this.loading = !request.loaded
      try {
        const response = await getAPI('listVMSnapshot', { virtualmachineid: this.resource.id, page: this.page, pagesize: this.pageSize, name: this.keyword || undefined, listall: true })
        if (!this.isListRequestCurrent('fetchData', request)) return
        const data = response.listvmsnapshotresponse
        this.rows = (data.vmSnapshot || []).filter(row => row.virtualmachineid === this.resource.id)
        this.total = data.count || 0
        if (!this.rows.length && this.page > 1) {
          this.page = Math.max(1, Math.ceil(this.total / this.pageSize))
          return this.fetchData()
        }
      } catch (error) {
        if (!this.isListRequestCurrent('fetchData', request)) return
        request.failed = true; this.listRefreshFailed = true
      } finally {
        if (this.isListRequestCurrent('fetchData', request)) this.loading = false
      }
    },
    searchSnapshots () { this.keyword = this.search.trim(); this.page = 1; this.fetchData() },
    createSnapshot () {
      if (!this.canCreate || this.createDisabled) return
      eventBus.emit('exec-action', { action: { ...this.createDefinition, resource: this.resource }, isGroupAction: false })
    },
    openAction (api, row) {
      if (this.reason(api, row)) return
      this.actionApi = api; this.selected = { ...row }
    },
    cancelAction () { if (!this.submitting) this.selected = null },
    async submitAction () {
      if (this.submitting || !this.selected || this.confirmationReason) return
      const scope = this.scopeKey
      const security = JSON.stringify([this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token])
      const originalPage = this.$route.path
      const snapshot = { ...this.selected }
      const api = this.actionApi
      this.submitting = true
      try {
        const [snapshotResponse, vmResponse] = await Promise.all([
          getAPI('listVMSnapshot', { vmsnapshotid: snapshot.id, virtualmachineid: this.resource.id }),
          getAPI('listVirtualMachines', { id: this.resource.id })
        ])
        if (scope !== this.scopeKey || this.listRefreshDisposed) return
        const fresh = snapshotResponse.listvmsnapshotresponse.vmSnapshot?.find(row => row.id === snapshot.id)
        const vm = vmResponse.listvirtualmachinesresponse.virtualmachine?.find(row => row.id === this.resource.id)
        const reason = !fresh || !vm ? 'message.vmsnapshot.not.ready' : snapshotActionReason(api, fresh, vm, snapshotBusy(vm.id))
        if (!this.allowed(api) || reason) throw new Error(this.$t(reason || 'message.vmsnapshot.permission'))
        const response = await postAPI(api, { vmsnapshotid: snapshot.id })
        const jobId = response[api.toLowerCase() + 'response']?.jobid
        if (!jobId) throw new Error(this.$t('message.job.result.unknown'))
        if (security !== JSON.stringify([this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token])) return
        if (scope === this.scopeKey) this.selected = null
        const refresh = () => {
          if (scope === this.scopeKey && !this.listRefreshDisposed) {
            this.fetchData()
            if (this.parentFetchData) this.parentFetchData()
          }
        }
        this.$pollJob({ jobId, originalPage, title: this.$t(api === 'deleteVMSnapshot' ? 'label.action.vmsnapshot.delete' : 'label.action.vmsnapshot.revert'), description: snapshot.displayname || snapshot.name, resourceId: snapshot.id, action: { api, resource: snapshot, isFetchData: false }, successMethod: refresh, errorMethod: refresh }).catch(() => {})
      } catch (error) {
        if (scope === this.scopeKey && !this.listRefreshDisposed) {
          this.$notifyError(error)
          this.fetchData()
        }
      } finally { this.submitting = false }
    }
  }
}
</script>

<style scoped lang="scss">
.snapshot-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.snapshot-toolbar :deep(.ant-input-search) { margin-left: auto; width: 280px; }
.snapshot-alert { margin: 12px 0; }
.snapshot-confirmation :deep(.ant-descriptions-item-label) { background: var(--ui-bg-page); color: var(--ui-text-primary); }
.snapshot-confirmation :deep(.ant-descriptions-item-content) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); }
.snapshot-confirmation :deep(.ant-descriptions-view), .snapshot-confirmation :deep(.ant-descriptions-row), .snapshot-confirmation :deep(.ant-descriptions-item-label), .snapshot-confirmation :deep(.ant-descriptions-item-content) { border-color: var(--ui-border); }
.snapshot-pagination :deep(.ant-pagination-item-link) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); border-color: var(--ui-border); }
.snapshot-pagination { margin-top: 20px; text-align: right; }
.snapshot-actions { display: flex; align-items: center; }
.snapshot-parent { display: block; margin-top: 6px; opacity: .8; }
.current-tag { margin-left: 8px; }
.mobile-restore { display: none; }
@media (max-width: 768px) {
  .restore-button { display: none; }
  .mobile-restore { display: block; }
  .snapshot-toolbar :deep(.ant-input-search) { width: 100%; }
}
</style>
