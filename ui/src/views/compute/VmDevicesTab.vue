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
  <div class="vm-devices">
    <div class="device-toolbar">
      <a-tooltip :title="blockReason"><span v-if="canManage"><a-button type="primary" :disabled="!!blockReason" @click="openAllocate"><template #icon><plus-outlined /></template>{{ d('allocate') }}</a-button></span></a-tooltip>
      <a-button :loading="loading" @click="refresh"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-select v-model:value="filter" :aria-label="d('type')" @change="page = 1"><a-select-option value="">{{ d('all') }}</a-select-option><a-select-option v-for="type in types" :key="type" :value="type">{{ type.toUpperCase() }}</a-select-option></a-select>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" @change="page = 1" />
    </div>
    <a-alert v-if="error" type="warning" show-icon :message="error" />
    <a-alert v-if="blockReason && canManage" type="info" show-icon :message="blockReason" />
    <a-table :columns="columns" :data-source="visibleRows" :row-key="rowKey" :loading="loading" :pagination="false" :scroll="{ x: 760 }" size="small">
      <template #emptyText>{{ d('empty') }}</template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'type'"><a-tag>{{ (record.devicetype || '').toUpperCase() }}</a-tag></template>
        <template v-if="column.key === 'name'"><span>{{ record.hostdevicesname }}</span><small>{{ record.hostdevicestext }}</small></template>
        <template v-if="column.key === 'state'">{{ d('record') }}<small>{{ d('unverified') }}</small></template>
        <template v-if="column.key === 'actions'">
          <div class="device-actions">
            <a-tooltip v-if="allowed(record.devicetype)" :title="reason(record.devicetype, record)"><span><a-button type="link" size="small" :disabled="!!reason(record.devicetype, record)" @click="openRecord('release', record)">{{ d(record.devicetype === 'pci' ? 'releasePci' : 'release') }}</a-button></span></a-tooltip>
            <a-dropdown :trigger="['click']" placement="bottomRight"><a-button size="small" :aria-label="$t('label.actions')"><down-outlined /></a-button><template #overlay><a-menu @click="({ key }) => openRecord(key, record)"><a-menu-item key="inspect">{{ d('inspect') }}</a-menu-item><a-menu-item v-if="record.hostuuid" key="host">{{ d('viewHost') }}</a-menu-item><a-menu-divider /><a-menu-item key="details">{{ $t('label.details') }}</a-menu-item></a-menu></template></a-dropdown>
          </div>
        </template>
      </template>
    </a-table>
    <div class="device-pagination"><a-pagination v-model:current="page" v-model:page-size="pageSize" :total="filteredRows.length" show-size-changer :page-size-options="['10', '20', '50']" /></div>
    <p class="device-help">{{ d('recordHelp') }}</p>
    <a-modal
:visible="!!dialog"
:title="dialogTitle"
:width="720"
centered
wrap-class-name="vm-device-dialog"
:mask-closable="false"
:closable="!submitting"
:keyboard="!submitting"
@cancel="close">
      <a-descriptions :column="2" size="small" bordered><a-descriptions-item :label="$t('label.virtualmachine')">{{ vm.displayname || vm.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.state')">{{ vm.state }}</a-descriptions-item><a-descriptions-item :label="$t('label.host')">{{ vm.hostname || hostId || d('unknown') }}</a-descriptions-item><a-descriptions-item :label="$t('label.account')">{{ vm.account }}</a-descriptions-item></a-descriptions>
      <a-alert v-if="dialogError" type="error" show-icon :message="dialogError" />
      <template v-if="dialog === 'allocate'">
        <a-form layout="vertical">
          <a-form-item :label="d('mode')"><a-select v-model:value="mode" :disabled="submitting" @change="changeMode"><a-select-option value="existing">{{ d('existing') }}</a-select-option><a-select-option v-if="api('createVhbaDevice') && allowed('vhba')" value="create">{{ d('createVhba') }}</a-select-option></a-select></a-form-item>
          <a-form-item v-if="!vm.hostid" :label="$t('label.host')"><a-select v-model:value="hostId" :disabled="submitting" @change="fetchCandidates"><a-select-option v-for="host in hosts" :key="host.id" :value="host.id">{{ host.name }}</a-select-option></a-select><p class="device-help">{{ d('hostHelp') }}</p></a-form-item>
          <a-form-item v-if="mode === 'existing'" :label="d('type')"><a-select v-model:value="type" :disabled="submitting" @change="fetchCandidates"><a-select-option v-for="t in types.filter(allowed)" :key="t" :value="t">{{ t.toUpperCase() }}</a-select-option></a-select></a-form-item>
          <a-form-item v-if="type === 'lun' && mode === 'existing'" :label="d('pathMode')"><a-select v-model:value="pathMode" :disabled="submitting" @change="fetchCandidates"><a-select-option value="single">{{ d('single') }}</a-select-option><a-select-option value="multipath">{{ d('multipath') }}</a-select-option></a-select></a-form-item>
          <a-alert v-if="operationReason" type="warning" show-icon :message="operationReason" />
          <a-form-item :label="mode === 'create' ? d('parentHba') : d('device')"><a-select v-model:value="choice" :loading="candidateLoading" :disabled="busy || !hostId" show-search option-filter-prop="label"><a-select-option v-for="item in candidates" :key="item.name" :value="item.name" :label="item.name + ' ' + item.text" :disabled="!!item.allocation || item.protected">{{ item.name }} — {{ item.text }}{{ item.protected ? ' · ' + d('protected') : (item.allocation ? ' · ' + d('occupied') : '') }}</a-select-option></a-select><p class="device-help">{{ d('candidateHelp') }}</p></a-form-item>
          <a-button v-if="mode === 'existing' && type === 'vhba' && choice && api('deleteVhbaDevice')" :disabled="submitting" @click="openDeleteCandidate">{{ d('deleteVhba') }}</a-button>
          <a-form-item v-if="mode === 'create'" :label="d('vhbaName')"><a-input v-model:value="vhbaName" :maxlength="80" :disabled="submitting" /><p class="device-help">{{ d('vhbaHelp') }}</p></a-form-item>
          <a-form-item v-if="['hba', 'vhba'].includes(type) && mode === 'existing'" :label="d('scsiAddress')"><a-select v-model:value="address" :disabled="submitting" :options="scsiChoices" /><p class="device-help">{{ d('scsiHelp') }}</p></a-form-item>
          <a-alert v-if="['lun', 'scsi', 'hba', 'vhba'].includes(type)" type="warning" show-icon :message="d('storageWarning')" />
          <a-checkbox v-model:checked="ack" :disabled="submitting">{{ d('ack') }}</a-checkbox>
        </a-form>
      </template>
      <template v-else-if="selected">
        <a-descriptions :column="1" size="small" bordered><a-descriptions-item :label="d('type')">{{ selected.devicetype.toUpperCase() }}</a-descriptions-item><a-descriptions-item :label="d('device')">{{ selected.hostdevicesname }}</a-descriptions-item><a-descriptions-item :label="$t('label.details')">{{ selected.hostdevicestext || '—' }}</a-descriptions-item><a-descriptions-item :label="$t('label.host')">{{ selected.hostname }}</a-descriptions-item><a-descriptions-item :label="d('state')">{{ d('unverified') }}</a-descriptions-item></a-descriptions>
        <template v-if="dialog === 'release'"><a-alert type="warning" show-icon :message="d(selected.devicetype === 'pci' ? 'pciWarning' : 'releaseWarning')" /><a-checkbox v-model:checked="ack" :disabled="submitting">{{ d('ackRelease') }}</a-checkbox></template>
        <a-alert v-if="dialog === 'inspect'" type="warning" show-icon :message="d('cleanupBlocked')" />
      </template>
      <template v-if="dialog === 'result'">
        <a-alert :type="resultFailed ? 'warning' : 'success'" show-icon :message="d(resultFailed ? 'partial' : 'complete')" />
        <p v-for="step in steps" :key="step">{{ step }}</p>
        <p v-if="createdDevice" class="device-help">{{ d('retained') }}: {{ createdDevice }}</p>
        <a-button v-if="createdDevice && api('deleteVhbaDevice')" @click="dialog = 'deleteVhba'; ack = false">{{ d('deleteVhba') }}</a-button>
      </template>
      <template v-if="dialog === 'deleteVhba'"><p>{{ createdDevice }}</p><a-alert type="warning" show-icon :message="d('deleteWarning')" /><a-checkbox v-model:checked="ack">{{ d('ackRelease') }}</a-checkbox></template>
      <template #footer>
        <a-button :disabled="submitting" @click="close">{{ $t(['details', 'inspect', 'result'].includes(dialog) ? 'label.close' : 'label.cancel') }}</a-button>
        <a-button v-if="dialog === 'inspect'" disabled>{{ d('cleanup') }}</a-button>
        <a-button v-if="['allocate', 'release', 'deleteVhba'].includes(dialog)" type="primary" :danger="dialog !== 'allocate'" :loading="busy" :disabled="submitDisabled" @click="submit">{{ dialogTitle }}</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { deviceTypes, asArray, deviceCandidates, deviceXml, vhbaXml } from '@/utils/vmDevices'
export default {
  name: 'VmDevicesTab',
  props: { resource: { type: Object, required: true }, active: Boolean },
  data () {
    return { vm: this.resource, rows: [], loading: false, busy: false, submitting: false, error: '', snapshots: null, search: '', filter: '', page: 1, pageSize: 10, dialog: '', selected: null, ack: false, dialogError: '', mode: 'existing', type: 'usb', pathMode: 'multipath', candidates: [], candidateLoading: false, choice: undefined, hostId: this.resource.hostid, hosts: [], address: undefined, scsiDevices: [], vhbaName: '', createdDevice: '', createdHost: '', resultFailed: false, steps: [], revision: 0, candidateRevision: 0 }
  },
  computed: {
    types () { return Object.keys(deviceTypes) },
    canManage () { return this.types.some(this.allowed) },
    blockReason () {
      if (this.loading || this.busy || this.snapshots === null || this.error) return this.d('verifyFirst')
      if (this.snapshots) return this.d('snapshotBlocked')
      if (this.vm.hypervisor !== 'KVM' || !['Running', 'Stopped'].includes(this.vm.state)) return this.d('stateBlocked')
      return ''
    },
    operationReason () { return this.reason(this.mode === 'create' ? 'vhba' : this.type) },
    columns () { return [{ key: 'type', title: this.d('type'), width: 80 }, { key: 'name', title: this.d('device') }, { key: 'host', dataIndex: 'hostname', title: this.$t('label.host'), width: 120 }, { key: 'state', title: this.d('state'), width: 170 }, { key: 'actions', title: this.$t('label.actions'), width: 170 }] },
    filteredRows () { const q = this.search.toLowerCase(); return this.rows.filter(r => (!this.filter || r.devicetype === this.filter) && [r.hostdevicesname, r.hostdevicestext, r.hostname].join(' ').toLowerCase().includes(q)) },
    visibleRows () { return this.filteredRows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize) },
    dialogTitle () { return this.dialog === 'details' ? this.$t('label.details') : this.d({ allocate: this.mode === 'create' ? 'createVhba' : 'allocate', release: this.selected?.devicetype === 'pci' ? 'releasePci' : 'release', inspect: 'inspect', result: 'result', deleteVhba: 'deleteVhba' }[this.dialog] || 'device') },
    submitDisabled () { return this.submitting || this.busy || !this.ack || (this.dialog === 'allocate' && (!!this.operationReason || this.candidateLoading || !this.choice || !this.hostId || (this.mode === 'create' && !/^[\w-]{1,80}$/.test(this.vhbaName)))) || (this.dialog === 'release' && !!this.reason(this.selected?.devicetype, this.selected)) },
    scsiChoices () { return this.scsiDevices.filter(d => (d.text || '').includes('[' + (this.choice || '').replace('scsi_host', '') + ':')).map(d => ({ value: (d.text.match(/\[(\d+:\d+:\d+:\d+)\]/) || [])[1], label: d.name + ' — ' + d.text })).filter(d => d.value) }
  },
  watch: {
    active: { immediate: true, handler (value) { if (value) this.refresh() } },
    'resource.state' () { if (this.active) this.refresh() },
    'resource.hostid' () { if (this.active) this.refresh() },
    'resource.id' () { this.revision++; this.candidateRevision++; this.dialog = ''; this.rows = []; this.snapshots = null; if (this.active) this.refresh() }
  },
  beforeUnmount () { this.revision++; this.candidateRevision++ },
  methods: {
    d (key) { return this.$t('label.vmdevice.' + key) },
    api (name) { return name in this.$store.getters.apis },
    allowed (type) { return !!deviceTypes[type] && this.api(deviceTypes[type][1]) },
    rowKey (r) { return [r.hostid, r.devicetype, r.hostdevicesname].join(':') },
    reason (type, row) {
      if (this.blockReason) return this.blockReason
      if (!this.allowed(type)) return this.d('permission')
      if (type === 'pci' ? this.vm.state !== 'Stopped' : this.vm.state !== 'Running') return this.d(type === 'pci' ? 'stopPci' : 'runningRequired')
      if (row && (!row.hostuuid || (this.vm.hostid && row.hostuuid !== this.vm.hostid))) return this.d('hostBlocked')
      return ''
    },
    async refresh () {
      const version = ++this.revision
      this.loading = true
      try {
        const [v, a, s] = await Promise.all([getAPI('listVirtualMachines', { id: this.resource.id, details: 'all' }), getAPI('listVmDeviceAssignments', { virtualmachineid: this.resource.id }), this.canManage ? getAPI('listVMSnapshot', { virtualmachineid: this.resource.id, listall: true }) : Promise.resolve(null)])
        if (version !== this.revision) return
        const vm = asArray(v.listvirtualmachinesresponse?.virtualmachine)[0]
        if (!vm) throw new Error(this.d('verifyFirst'))
        this.vm = vm
        this.rows = asArray(a.listvmdeviceassignmentsresponse?.vmdeviceassignment)
        this.snapshots = s ? (s.listvmsnapshotresponse?.count || asArray(s.listvmsnapshotresponse?.vmSnapshot || s.listvmsnapshotresponse?.vmsnapshot).length) : null
        this.page = Math.min(this.page, Math.max(1, Math.ceil(this.filteredRows.length / this.pageSize)))
        this.error = ''
      } catch (e) { if (version === this.revision) { this.error = this.d('loadFailed'); this.snapshots = null } } finally { if (version === this.revision) this.loading = false }
    },
    async openAllocate () {
      this.selected = null; this.mode = 'existing'; this.type = this.vm.state === 'Stopped' ? 'pci' : this.types.find(t => t !== 'pci' && this.allowed(t)); this.dialog = 'allocate'; this.dialogError = ''; this.ack = false; this.createdDevice = ''; this.hostId = this.vm.hostid
      if (!this.hostId) {
        try { const r = await getAPI('listHosts', { zoneid: this.vm.zoneid, type: 'Routing' }); this.hosts = asArray(r.listhostsresponse?.host).filter(h => h.hypervisor === 'KVM' && h.state === 'Up'); this.hostId = this.rows.find(r => r.hostuuid)?.hostuuid } catch (e) { this.dialogError = this.d('loadFailed') }
      }
      await this.fetchCandidates()
    },
    openRecord (dialog, record) { if (dialog === 'host') { this.$router.push('/host/' + record.hostuuid); return } this.selected = record; this.dialog = dialog; this.dialogError = ''; this.ack = false },
    openDeleteCandidate () {
      const item = this.candidates.find(c => c.name === this.choice && !c.allocation)
      if (!item) return
      this.createdDevice = item.name; this.createdHost = this.hostId; this.dialog = 'deleteVhba'; this.ack = false; this.dialogError = ''
    },
    close () { if (!this.submitting) { this.dialog = ''; this.candidateRevision++ } },
    changeMode () { this.type = this.mode === 'create' ? 'vhba' : 'usb'; this.fetchCandidates() },
    async loadCandidates (type, hostId) {
      const name = deviceTypes[type][0]
      const params = type === 'vhba' ? { hostid: hostId } : { id: hostId }
      if (type === 'lun') params.lunpathmode = this.pathMode
      return deviceCandidates(await getAPI(name, params), type)
    },
    async fetchCandidates () {
      const revision = ++this.candidateRevision
      this.choice = undefined; this.address = undefined; this.candidates = []; this.scsiDevices = []; this.dialogError = ''
      if (!this.hostId) return
      this.candidateLoading = true
      try {
        const type = this.mode === 'create' ? 'hba' : this.type
        const candidates = await this.loadCandidates(type, this.hostId)
        const scsi = ['hba', 'vhba'].includes(type) && this.mode !== 'create' ? await this.loadCandidates('scsi', this.hostId) : []
        if (revision !== this.candidateRevision) return
        this.candidates = candidates; this.scsiDevices = scsi
      } catch (e) { if (revision === this.candidateRevision) this.dialogError = this.d('loadFailed') } finally { if (revision === this.candidateRevision) this.candidateLoading = false }
    },
    async submit () {
      if (this.submitDisabled) return
      this.submitting = true
      const action = this.dialog
      const vmId = this.resource.id
      const hostId = this.hostId
      this.dialogError = ''
      await this.refresh()
      if (this.resource.id !== vmId || this.dialog !== action) { this.submitting = false; return }
      const reason = action === 'release' ? this.reason(this.selected.devicetype, this.selected) : this.operationReason
      if (action !== 'deleteVhba' && reason) { this.dialogError = reason; this.submitting = false; return }
      this.busy = true
      try {
        if (action === 'deleteVhba') {
          const candidates = await this.loadCandidates('vhba', this.createdHost)
          const current = candidates.find(c => c.name === this.createdDevice)
          if (!current || current.allocation) throw new Error(this.d('occupied'))
          await postAPI('deleteVhbaDevice', { hostid: this.createdHost, hostdevicesname: this.createdDevice })
          this.createdDevice = ''; this.dialog = ''; this.$message.success(this.d('complete'))
        } else if (action === 'release') {
          const row = this.rows.find(r => this.rowKey(r) === this.rowKey(this.selected))
          if (!row) throw new Error(this.d('verifyFirst'))
          const xml = deviceXml({ ...row, type: row.devicetype })
          await postAPI(deviceTypes[row.devicetype][1], { hostid: row.hostuuid, hostdevicesname: row.hostdevicesname, currentvmid: vmId, xmlconfig: xml })
          if (row.devicetype === 'vhba') {
            this.createdDevice = row.hostdevicesname; this.createdHost = row.hostuuid; this.resultFailed = false; this.steps = [this.d('released')]; this.dialog = 'result'
          } else { this.dialog = ''; this.$message.success(this.d('complete')) }
        } else if (this.mode === 'create') {
          this.steps = []; this.resultFailed = false; this.createdHost = this.hostId
          const parents = await this.loadCandidates('hba', this.hostId)
          if (!parents.some(c => c.name === this.choice && !c.allocation)) throw new Error(this.d('verifyFirst'))
          if (this.resource.id !== vmId) throw new Error(this.d('verifyFirst'))
          const r = await postAPI('createVhbaDevice', { hostid: this.hostId, parenthbaname: this.choice, vhbaname: this.vhbaName, xmlconfig: vhbaXml(this.choice) })
          const body = r.createvhbadeviceresponse || r
          const result = asArray(body.createvhbadevice)[0] || body
          if (!result.success) throw new Error(this.d('loadFailed'))
          this.createdDevice = result.details || result.vhbaname
          this.steps.push(this.d('created') + ': ' + this.createdDevice)
          const candidates = await this.loadCandidates('vhba', this.hostId)
          const device = candidates.find(c => c.name === this.createdDevice)
          if (!device || device.allocation || device.protected) throw new Error(this.d('verifyFirst'))
          const children = (await this.loadCandidates('scsi', hostId)).filter(c => c.text.includes('[' + device.name.replace('scsi_host', '') + ':'))
          if (children.length !== 1) throw new Error('device-address-unverified')
          const detail = children[0].text + ' ' + device.text
          await postAPI(deviceTypes.vhba[1], { hostid: hostId, virtualmachineid: vmId, hostdevicesname: device.name, hostdevicestext: detail, xmlconfig: deviceXml({ ...device, text: detail }) })
          this.steps.push(this.d('allocated')); this.createdDevice = ''; this.dialog = 'result'
        } else {
          const candidates = await this.loadCandidates(this.type, this.hostId)
          const device = candidates.find(c => c.name === this.choice)
          if (!device || device.allocation || device.protected) throw new Error(this.d('occupied'))
          const xml = deviceXml({ ...device, address: this.address })
          const detail = ['hba', 'vhba'].includes(this.type) ? `SCSI_Address: [${this.address}] ${device.text}` : device.text
          if (this.resource.id !== vmId) throw new Error(this.d('verifyFirst'))
          await postAPI(deviceTypes[this.type][1], { hostid: hostId, hostdevicesname: device.name, hostdevicestext: detail, virtualmachineid: vmId, xmlconfig: xml })
          this.dialog = ''; this.$message.success(this.d('complete'))
        }
      } catch (e) {
        this.dialogError = e.message === 'device-address-unverified' ? this.d('addressUnverified') : (Object.values(e.response?.data || {}).find(v => v?.errortext)?.errortext || e.message || this.d('failed'))
        if (this.createdDevice && action === 'allocate') { this.resultFailed = true; this.dialog = 'result'; this.steps.push(this.d('failed')) }
      } finally { this.busy = false; this.submitting = false; await this.refresh() }
    }
  }
}
</script>

<style lang="scss">
.vm-devices {
  color: var(--ui-text-primary);
  .device-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; align-items: center; }
  .device-toolbar .ant-select { min-width: 120px; margin-left: auto; }
  .device-toolbar .ant-input-search { width: 240px; }
  .device-actions { display: flex; gap: 8px; align-items: center; white-space: nowrap; }
  small { display: block; color: var(--ui-text-secondary); margin-top: 6px; overflow-wrap: anywhere; }
  .device-pagination { display: flex; justify-content: flex-end; margin-top: 20px; }
  .ant-pagination-item-link { background: var(--ui-bg-surface) !important; border-color: var(--ui-border) !important; color: var(--ui-text-primary) !important; }
  .ant-pagination-disabled .ant-pagination-item-link { color: var(--ui-text-secondary) !important; opacity: 0.55; }
  .ant-alert { margin: 12px 0; }
}
.device-help { color: var(--ui-text-secondary); margin: 10px 0; line-height: 1.6; }
.vm-device-dialog {
  .ant-modal { padding-bottom: 0; max-width: calc(100vw - 32px); }
  .ant-modal-content { display: flex; flex-direction: column; max-height: calc(100dvh - 48px); background: var(--ui-bg-surface); color: var(--ui-text-primary); }
  .ant-modal-header, .ant-modal-footer { flex: none; background: var(--ui-bg-surface); border-color: var(--ui-border); }
  .ant-modal-title, .ant-modal-close, .ant-form-item-label > label, .ant-checkbox-wrapper { color: var(--ui-text-primary); }
  .ant-modal-body { overflow-y: auto; min-height: 0; }
  .ant-form { margin-top: 20px; }
  .ant-descriptions { margin-bottom: 20px; }
  .ant-descriptions-bordered .ant-descriptions-item-label { background: var(--ui-bg-page) !important; color: var(--ui-text-primary) !important; }
  .ant-descriptions-bordered .ant-descriptions-item-content { background: var(--ui-bg-surface) !important; color: var(--ui-text-secondary) !important; overflow-wrap: anywhere; }
  .ant-descriptions-bordered .ant-descriptions-view, .ant-descriptions-bordered .ant-descriptions-row, .ant-descriptions-bordered .ant-descriptions-item-label, .ant-descriptions-bordered .ant-descriptions-item-content { border-color: var(--ui-border) !important; }
  .ant-alert { margin: 16px 0; }
  .ant-select { width: 100%; }
}
</style>
