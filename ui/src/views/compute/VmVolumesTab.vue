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
  <div>
    <div class="volume-toolbar">
      <a-button v-if="allowed('createVolume') && allowed('attachVolume')" type="primary" :disabled="busy || !!reason('createVolume')" @click="form = 'create'"><template #icon><plus-outlined /></template>{{ $t('label.vmvolume.create') }}</a-button>
      <a-button v-if="allowed('attachVolume')" :disabled="busy || !!reason('createVolume')" @click="openAttach">{{ $t('label.vmvolume.attach') }}</a-button>
      <a-button @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.vmsnapshot.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" />
    </div>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" class="volume-alert" />
    <a-alert v-if="operation" :type="operation.status === 'complete' ? 'success' : 'info'" show-icon class="volume-alert">
      <template #message><a @click="progressVisible = true">{{ $t('label.vmvolume.progress') }}: {{ $t('label.vmvolume.' + operation.status) }}</a></template>
    </a-alert>
    <a-table :columns="columns" :data-source="filteredRows" row-key="id" :loading="loading" :pagination="{ pageSize: 10, hideOnSinglePage: true }" :scroll="{ x: 700 }" size="small">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'"><hdd-outlined /> <router-link :to="'/volume/' + record.id">{{ record.name }}</router-link> <a-tag v-if="record.provisioningtype">{{ record.provisioningtype }}</a-tag></template>
        <template v-else-if="column.key === 'state'"><status :text="record.state" /> {{ record.state }}</template>
        <template v-else-if="column.key === 'size'">{{ (record.size / 1073741824).toFixed(2) }} GB</template>
        <template v-else-if="column.key === 'storage'"><router-link v-if="record.storageid" :to="'/storagepool/' + record.storageid">{{ record.storage }}</router-link></template>
        <template v-else-if="column.key === 'kmskey'"><router-link v-if="record.kmskeyid" :to="'/kmskey/' + record.kmskeyid">{{ record.kmskey }}</router-link></template>
        <template v-else-if="column.key === 'actions'">
          <div class="volume-row-actions">
          <a-tooltip :title="reason('detachVolume', record) ? $t(reason('detachVolume', record)) : ''"><span><a-button v-if="allowed('detachVolume')" size="small" :disabled="busy || !!reason('detachVolume', record)" @click="openDetach(record)">{{ $t('label.action.detach.disk') }}</a-button></span></a-tooltip>
          <a-dropdown :trigger="['click']"><a-button size="small" :aria-label="$t('label.actions')"><template #icon><down-outlined /></template></a-button><template #overlay><a-menu><a-menu-item v-if="allowed('detachVolume')" key="detach" :disabled="busy || !!reason('detachVolume', record)" @click="openDetach(record)">{{ $t('label.vmvolume.detach') }}</a-menu-item><a-menu-item key="details"><router-link :to="'/volume/' + record.id">{{ $t('label.details') }}</router-link></a-menu-item></a-menu></template></a-dropdown>
          </div>
        </template>
      </template>
    </a-table>
    <a-modal :visible="form === 'create'" :title="$t('label.vmvolume.create')" :footer="null" :mask-closable="false" @cancel="form = ''"><CreateVolume v-if="form === 'create'" :resource="resource" :submit-handler="createAndAttach" @close-action="form = ''" /></a-modal>
    <a-modal :visible="form === 'attach'" :title="$t('label.vmvolume.attach')" :ok-button-props="{ disabled: !attachId || busy || candidatesLoading || !!deviceReason }" @ok="attachExisting" @cancel="form = ''">
      <p>{{ resource.displayname || resource.name }}</p>
      <a-select v-model:value="attachId" show-search :filter-option="filterOption" :loading="candidatesLoading" style="width: 100%" :placeholder="$t('label.volumes')"><a-select-option v-for="volume in candidates" :key="volume.id" :value="volume.id" :label="volume.name">{{ volume.name }} ({{ (volume.size / 1073741824).toFixed(2) }} GB)</a-select-option></a-select>
      <a-form layout="vertical" class="volume-device-form">
        <a-form-item :label="$t('label.vmvolume.deviceid')" :validate-status="deviceReason ? 'error' : ''" :help="deviceReason ? $t(deviceReason) : $t('message.vmvolume.device.auto')">
          <a-input-number v-model:value="attachDeviceId" :min="1" :precision="0" :aria-label="$t('label.vmvolume.deviceid')" :placeholder="$t('label.vmvolume.device.auto')" style="width: 100%" />
        </a-form-item>
      </a-form>
      <a-alert v-if="candidateError" class="volume-alert" type="error" :message="candidateError" />
    </a-modal>
    <a-modal :visible="!!selected" :title="$t('label.vmvolume.detach')" :ok-text="$t(mode === 'preserve' ? 'label.action.detach.disk' : 'label.vmvolume.' + mode)" :ok-button-props="{ danger: mode !== 'preserve', disabled: busy }" @ok="detach" @cancel="selected = null">
      <template v-if="selected">
        <a-descriptions bordered :column="1" size="small" class="volume-description"><a-descriptions-item :label="$t('label.vm')">{{ resource.displayname || resource.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.volumes')">{{ selected.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.id')">{{ selected.id }}</a-descriptions-item><a-descriptions-item :label="$t('label.type')">{{ selected.type }}</a-descriptions-item><a-descriptions-item :label="$t('label.size')">{{ (selected.size / 1073741824).toFixed(2) }} GB</a-descriptions-item></a-descriptions>
        <a-radio-group v-model:value="mode" class="volume-options"><a-radio value="preserve">{{ $t('label.vmvolume.preserve') }}</a-radio><a-radio v-if="selected.type === 'DATADISK' && allowed('destroyVolume') && !selected.deleteprotection" value="destroy">{{ $t('label.vmvolume.destroy') }}</a-radio><a-radio v-if="selected.type === 'DATADISK' && canExpunge && !selected.deleteprotection" value="expunge">{{ $t('label.vmvolume.expunge') }}</a-radio></a-radio-group>
        <a-alert show-icon :type="mode === 'expunge' ? 'error' : 'warning'" :message="$t('message.vmvolume.' + mode)" />
      </template>
    </a-modal>
    <a-modal :visible="progressVisible && !!operation" :title="$t('label.vmvolume.progress')" :footer="null" @cancel="progressVisible = false">
      <template v-if="operation">
        <p>{{ operation.vm.displayname || operation.vm.name }} / <router-link v-if="operation.volume?.id" :to="'/volume/' + operation.volume.id">{{ operation.volume.name }}</router-link><span v-else>{{ operation.values?.name }}</span></p>
        <a-steps direction="vertical" size="small" :current="operation.stage" :status="operation.status === 'failed' ? 'error' : 'process'"><a-step v-for="step in operation.steps" :key="step" :title="$t('label.vmvolume.step.' + step)" /></a-steps>
        <a-alert class="volume-alert" :type="operation.status === 'complete' ? 'success' : operation.status === 'failed' ? 'error' : 'info'" :message="$t('label.vmvolume.' + operation.status)" :description="operation.error ? $t(operation.error) : ''" />
        <p v-if="operation.stage > 0 && operation.status !== 'complete'">{{ $t('message.vmvolume.partial') }}</p>
        <div class="volume-dialog-actions">
        <a-button v-if="operation.status === 'failed' || (operation.status === 'unknown' && operation.jobId)" :loading="operation.checking" @click="operation.resume()">{{ $t(operation.jobId && operation.status === 'unknown' ? 'label.vmvolume.check' : 'label.vmvolume.retry') }}</a-button>
        <a-button v-if="operation.status === 'failed'" @click="operation.abandon(); progressVisible = false">{{ $t('label.vmvolume.abandon') }}</a-button>
        <a-button @click="progressVisible = false">{{ $t('label.close') }}</a-button>
        </div>
      </template>
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { volumeOperations, startVolumeOperation, volumeActionReason, volumeDeviceIdReason } from '@/utils/vmVolumeActions'
import Status from '@/components/widgets/Status'
import CreateVolume from '@/views/storage/CreateVolume.vue'
import eventBus from '@/config/eventBus'

export default {
  name: 'VmVolumesTab',
  components: { Status, CreateVolume },
  props: { resource: { type: Object, required: true } },
  mixins: [listRefreshMixin(['fetchData'], { interval: 10000, active: vm => !!vm.resource.id })],
  data () { return { rows: [], loading: false, search: '', form: '', selected: null, mode: 'preserve', candidates: [], candidatesLoading: false, candidateError: '', attachId: undefined, attachDeviceId: undefined, progressVisible: false } },
  computed: {
    security () { return JSON.stringify([this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token]) },
    scopeKey () { return this.security + ':' + this.resource.id },
    operation () { return volumeOperations[this.scopeKey] },
    busy () { return this.operation && this.operation.status !== 'complete' },
    deviceReason () { return volumeDeviceIdReason(this.attachDeviceId, this.rows) },
    canExpunge () { return this.allowed('destroyVolume') && (this.$store.getters.userInfo?.roletype === 'Admin' || this.$store.getters.features?.allowuserexpungerecovervolume) },
    filteredRows () { const query = this.search.trim().toLowerCase(); return this.rows.filter(row => (row.name || '').toLowerCase().includes(query)) },
    columns () { return ['name', 'state', 'type', 'deviceid', 'size', 'storage', ...(this.allowed('listKMSKeys') ? ['kmskey'] : []), 'actions'].map(key => ({ key, dataIndex: key, title: this.$t(key === 'kmskey' ? 'label.kms.key' : key === 'deviceid' ? 'label.vmvolume.deviceid' : 'label.' + key), ...(key === 'actions' ? { width: 160, fixed: 'right' } : {}) })) }
  },
  watch: { scopeKey () { this.rows = []; this.search = ''; this.form = ''; this.selected = null; this.progressVisible = false; this.fetchData() } },
  created () { this.fetchData(); this.onJobComplete = () => this.fetchData(); eventBus.on('async-job-complete', this.onJobComplete) },
  beforeUnmount () { eventBus.off('async-job-complete', this.onJobComplete) },
  methods: {
    allowed (api) { return api in this.$store.getters.apis },
    reason (api, volume) {
      if (volume?.type === 'ROOT' && !this.allowed('listStoragePools')) return 'message.vmvolume.root'
      return volumeActionReason(api, volume, this.resource)
    },
    filterOption (input, option) { return option.label.toLowerCase().includes(input.toLowerCase()) },
    async fetchData () {
      if (!this.resource.id || !this.allowed('listVolumes')) return
      const request = this.listRequestToken('fetchData'); this.loading = !request.loaded
      try {
        const response = await getAPI('listVolumes', { virtualmachineid: this.resource.id, listall: true, listsystemvms: true })
        if (!this.isListRequestCurrent('fetchData', request)) return
        const rows = (response.listvolumesresponse.volume || []).filter(row => row.virtualmachineid === this.resource.id).sort((a, b) => a.deviceid - b.deviceid)
        if (JSON.stringify(rows) !== JSON.stringify(this.rows)) this.rows = rows
      } catch (error) { if (this.isListRequestCurrent('fetchData', request)) { request.failed = true; this.listRefreshFailed = true } } finally { if (this.isListRequestCurrent('fetchData', request)) this.loading = false }
    },
    async openAttach () {
      const scope = this.scopeKey
      this.form = 'attach'; this.attachId = undefined; this.attachDeviceId = undefined; this.candidates = []; this.candidateError = ''; this.candidatesLoading = true
      try {
        const params = { listall: true, zoneid: this.resource.zoneid, type: 'DATADISK', pagesize: 500, ...(this.resource.projectid ? { projectid: this.resource.projectid } : { account: this.resource.account, domainid: this.resource.domainid }) }
        let page = 1
        let fetched = 0
        do {
          const response = await getAPI('listVolumes', { ...params, page })
          if (scope !== this.scopeKey || this.form !== 'attach' || this.listRefreshDisposed) return
          const data = response.listvolumesresponse
          const volumes = data.volume || []
          this.candidates.push(...volumes.filter(volume => !this.reason('attachVolume', volume)))
          fetched += volumes.length
          if (!volumes.length || fetched >= (data.count || fetched)) break
          page++
        } while (true)
      } catch (error) { if (scope === this.scopeKey) this.candidateError = this.$t('message.list.refresh.stale') } finally { if (scope === this.scopeKey) this.candidatesLoading = false }
    },
    openDetach (record) { this.selected = { ...record }; this.mode = 'preserve' },
    createAndAttach (values) {
      const reason = volumeDeviceIdReason(values.deviceid, this.rows)
      if (reason) throw new Error(this.$t(reason))
      this.begin(['createVolume', 'attachVolume'], null, values)
    },
    attachExisting () { const volume = this.candidates.find(item => item.id === this.attachId); if (volume && !this.deviceReason) this.begin(['attachVolume'], volume, { deviceid: this.attachDeviceId }) },
    detach () { if (this.selected) this.begin(this.mode === 'preserve' ? ['detachVolume'] : ['detachVolume', 'destroyVolume'], this.selected) },
    begin (steps, volume, values) {
      if (this.busy) return
      const key = this.scopeKey; const security = this.security; const vm = { ...this.resource }; const mode = this.mode; const originalPage = this.$route.path
      const current = () => this.security === security
      const refresh = () => { if (key === this.scopeKey && !this.listRefreshDisposed) this.fetchData() }
      startVolumeOperation(key, { steps, vm, volume: volume ? { ...volume } : null, values, mode, deviceId: values?.deviceid }, {
        current,
        refresh,
        validate: async operation => {
          const api = operation.steps[operation.stage]
          if (!this.allowed(api) || (api === 'destroyVolume' && operation.mode === 'expunge' && !this.canExpunge)) throw new Error(this.$t('message.vmsnapshot.permission'))
          const response = await getAPI('listVirtualMachines', { id: vm.id })
          const freshVm = response.listvirtualmachinesresponse.virtualmachine?.find(item => item.id === vm.id)
          let freshVolume = operation.volume
          if (freshVolume?.id) { const result = await getAPI('listVolumes', { id: freshVolume.id, listall: true }); freshVolume = result.listvolumesresponse.volume?.find(item => item.id === freshVolume.id) }
          if (['createVolume', 'attachVolume'].includes(api) && operation.deviceId !== undefined && operation.deviceId !== null && operation.deviceId !== '') {
            const attached = await getAPI('listVolumes', { virtualmachineid: vm.id, listall: true })
            const deviceReason = volumeDeviceIdReason(operation.deviceId, attached.listvolumesresponse.volume || [])
            if (deviceReason) throw new Error(this.$t(deviceReason))
          }
          const reason = volumeActionReason(api, freshVolume, freshVm)
          if (reason) throw new Error(this.$t(reason))
          if (api === 'detachVolume' && freshVolume.type === 'ROOT') {
            if (!this.allowed('listStoragePools')) throw new Error(this.$t('message.vmvolume.root'))
            const pools = await getAPI('listStoragePools', { id: freshVolume.storageid })
            const pool = pools.liststoragepoolsresponse.storagepool?.find(item => item.id === freshVolume.storageid)
            if (!pool || pool.managed !== false) throw new Error(this.$t('message.vmvolume.root'))
          }
        },
        submit: operation => {
          const api = operation.steps[operation.stage]
          const params = api === 'createVolume' ? { ...operation.values } : { id: operation.volume.id }
          if (api === 'createVolume') {
            delete params.virtualmachineid
            delete params.deviceid
            params.zoneid = vm.zoneid
            if (vm.projectid) { params.projectid = vm.projectid; delete params.account; delete params.domainid } else { params.account = vm.account; params.domainid = vm.domainid; delete params.projectid }
          }
          if (api === 'attachVolume') {
            params.virtualmachineid = vm.id
            if (operation.deviceId !== undefined && operation.deviceId !== null && operation.deviceId !== '') params.deviceid = Number(operation.deviceId)
          }
          if (api === 'destroyVolume') params.expunge = operation.mode === 'expunge'
          return postAPI(api, params)
        },
        poll: operation => this.$pollJob({ jobId: operation.jobId, retry: operation.status === 'unknown', originalPage, title: this.$t('label.vmvolume.step.' + operation.steps[operation.stage]), description: operation.volume?.name || values?.name, resourceId: operation.volume?.id, action: { api: operation.steps[operation.stage], resource: operation.volume || vm, isFetchData: false } })
      })
      this.form = ''; this.selected = null; this.progressVisible = true
    }
  }
}
</script>

<style scoped lang="scss">
.volume-row-actions { display: inline-flex; align-items: center; gap: 8px; white-space: nowrap; }
.volume-row-actions > span { display: inline-flex; align-items: center; }
.volume-row-actions :deep(.ant-btn) { height: 24px; display: inline-flex; align-items: center; justify-content: center; margin: 0; }
.volume-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.volume-toolbar :deep(.ant-input-search) { width: 280px; margin-left: auto; }
.volume-device-form :deep(.ant-form-item-explain) {
  margin-top: 8px;
  color: var(--ui-text-secondary);
}
.volume-device-form :deep(.ant-form-item-explain-error) { color: var(--ui-error-icon); }
.volume-device-form { margin-top: 20px; }
.volume-dialog-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; margin-top: 20px; }
.volume-alert { margin: 12px 0; }
.volume-options { display: flex; flex-direction: column; gap: 12px; margin: 20px 0; }
.volume-description :deep(.ant-descriptions-item-label) { background: var(--ui-bg-page); color: var(--ui-text-primary); }
.volume-description :deep(.ant-descriptions-item-content) { background: var(--ui-bg-surface); color: var(--ui-text-secondary); }
.volume-description :deep(.ant-descriptions-view), .volume-description :deep(.ant-descriptions-row), .volume-description :deep(.ant-descriptions-item-label), .volume-description :deep(.ant-descriptions-item-content) { border-color: var(--ui-border); }
@media (max-width: 768px) { .volume-toolbar :deep(.ant-input-search) { width: 100%; } }
</style>
