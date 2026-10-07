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
  <section class="storage-configuration">
    <h4>{{ $t('label.storage.config.section') }}</h4>
    <a-space wrap>
      <a-button v-if="can('createStorageServiceConfigBackup')" type="primary" :loading="!!busy" @click="backupDialog=true"><template #icon><CloudDownloadOutlined /></template>{{ $t('label.storage.config.create') }}</a-button>
      <a-upload v-if="can('uploadStorageServiceConfigBackup')" :before-upload="importFile" :show-upload-list="false" accept=".zip"><a-button :disabled="!!busy"><template #icon><UploadOutlined /></template>{{ $t('label.storage.config.import') }}</a-button></a-upload>
      <a-button v-if="can('verifyStorageServiceConfiguration')" :disabled="!!busy" @click="verifyBaseline"><template #icon><ReloadOutlined /></template>{{ $t('label.storage.config.verify.baseline') }}</a-button>
      <a-button v-if="can('planStorageServiceLastKnownGoodRestore')" :disabled="!activePoint || !!busy" @click="openPlan(activePoint, true)"><template #icon><RollbackOutlined /></template>{{ $t('label.storage.config.lkg.restore') }}</a-button>
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="busy" type="info" show-icon :message="$t('message.storage.config.running')" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.storage.config.read.failed')" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :pagination="{ pageSize: 5 }" :scroll="{ x: 1100 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key==='state'"><a-tag>{{ $t('label.storage.config.state.' + record.state) }}</a-tag></template>
        <template v-else-if="column.key==='kind'">{{ $t('label.storage.config.kind.' + record.kind) }}</template>
        <template v-else-if="column.key==='created'">{{ new Date(record.created).toLocaleString() }}</template>
        <template v-else-if="column.key==='actions'">
          <a-space wrap>
            <a-button v-if="record.kind==='BACKUP' && ['COMPLETE','PARTIAL'].includes(record.state) && can('downloadStorageServiceConfigBackup')" size="small" :disabled="!!busy" @click="download(record)"><template #icon><DownloadOutlined /></template>{{ $t('label.download') }}</a-button>
            <a-button v-if="record.kind==='IMPORT' && record.state==='QUARANTINED' && can('validateStorageServiceConfigImport')" size="small" :disabled="!!busy" @click="validateImport(record)">{{ $t('label.storage.config.validate') }}</a-button>
            <a-button v-if="['COMPLETE','PARTIAL','ARCHIVE_VALIDATED','ACTIVE_LKG','SUPERSEDED'].includes(record.state) && can('planStorageServiceConfigRestore')" size="small" :disabled="!!busy" @click="openPlan(record)">{{ $t('label.storage.config.plan') }}</a-button>
            <a-popconfirm v-if="['BACKUP','IMPORT'].includes(record.kind) && can(record.kind==='BACKUP' ? 'deleteStorageServiceConfigBackup' : 'deleteStorageServiceConfigImport')" :title="$t('message.storage.config.delete.confirm')" @confirm="deleteArtifact(record)">
              <a-button size="small" danger :disabled="!!busy">{{ $t('label.delete') }}</a-button>
            </a-popconfirm>
          </a-space>
        </template>
        <template v-else>{{ record[column.dataIndex] }}</template>
      </template>
      <template #expandedRowRender="{ record }">
        <a-descriptions :column="2" bordered size="small">
          <a-descriptions-item :label="$t('label.storage.config.runtime.status')">{{ record.metadata?.runtimeStatus || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.secret.coverage')">{{ record.metadata?.credentialCoverage || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.verification')">{{ record.metadata?.verification || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.restore.status')">{{ record.metadata?.restoreState || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.size')">{{ record.size }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.expires')">{{ record.expires ? new Date(record.expires).toLocaleString() : '—' }}</a-descriptions-item>
          <a-descriptions-item v-if="record.metadata?.errorCode" :label="$t('label.error')">{{ record.metadata.errorCode }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-table>
    <a-modal :visible="backupDialog" :title="$t('label.storage.config.create')" :body-style="dialogBody" @cancel="backupDialog=false" @ok="createBackup">
      <a-alert type="info" show-icon :message="$t('message.storage.config.data.excluded')" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.config.runtime')"><a-switch v-model:checked="includeRuntime" /></a-form-item>
        <a-form-item :label="$t('label.storage.config.retention')"><a-input-number v-model:value="retentionHours" :min="1" :max="2160" /></a-form-item>
      </a-form>
    </a-modal>
    <a-modal
:visible="!!planTarget"
:title="$t('label.storage.config.plan')"
:body-style="dialogBody"
:width="900"
:ok-text="planPhase==='REVIEW' ? $t('label.storage.config.apply') : $t('label.storage.config.plan')"
:confirm-loading="planning"
@cancel="closePlan"
      @ok="planPhase==='REVIEW' ? applyPlan() : preparePlan()">
      <a-alert type="info" show-icon :message="$t('message.storage.config.data.excluded')" />
      <a-form layout="vertical">
        <a-form-item v-if="!lkgPlan" :label="$t('label.storage.config.target.mode')">
          <a-radio-group v-model:value="targetMode" :disabled="planPhase==='REVIEW'" @change="loadCloneOptions">
            <a-radio value="RESTORE_EXISTING">{{ $t('label.storage.config.target.existing') }}</a-radio>
            <a-radio value="CREATE_NEW">{{ $t('label.storage.config.target.new') }}</a-radio>
          </a-radio-group>
        </a-form-item>
        <template v-if="targetMode==='CREATE_NEW'">
          <a-form-item :label="$t('label.name')"><a-input v-model:value="clone.name" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.zoneid')"><a-select v-model:value="clone.zoneid" :options="cloneOptions.zones" :disabled="planPhase==='REVIEW'" @change="loadCloneZoneOptions" /></a-form-item>
          <a-form-item :label="$t('label.networkid')"><a-select v-model:value="clone.networkid" :options="cloneOptions.networks" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.serviceofferingid')"><a-select v-model:value="clone.serviceofferingid" :options="cloneOptions.offerings" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.diskofferingid')"><a-select v-model:value="clone.diskofferingid" :options="cloneOptions.disks" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.storage.service.primary.storage')"><a-select v-model:value="clone.storageid" :options="cloneOptions.pools" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.storage.config.clone.volume.mode')">
            <a-radio-group v-model:value="clone.backingvolumemode" :disabled="planPhase==='REVIEW'">
              <a-radio value="NEW">{{ $t('label.storage.config.clone.new.volume') }}</a-radio>
              <a-radio value="EXISTING">{{ $t('label.storage.service.existing.volume.select') }}</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="clone.backingvolumemode==='NEW'" :label="$t('label.storage.config.clone.size')"><a-input-number v-model:value="clone.size" :min="1" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item v-else :label="$t('label.storage.service.existing.volume.select')"><a-select v-model:value="clone.existingvolumeid" :options="cloneOptions.volumes" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.storage.config.clone.initial')"><a-select v-model:value="initialVolumeSource" :options="sourceVolumes.map(value => ({value,label:value}))" :disabled="planPhase==='REVIEW'" /></a-form-item>
          <a-form-item :label="$t('label.storage.config.clone.runtime')"><a-select v-model:value="cloneRuntime" :options="cloneOptions.bundles" :disabled="planPhase==='REVIEW'" /></a-form-item>
        </template>
        <a-form-item v-for="volume in sourceVolumes" :key="volume" :label="$t('label.storage.config.volume.mapping') + ': ' + volume">
          <a-input v-if="targetMode==='CREATE_NEW' && volume===initialVolumeSource && clone.backingvolumemode==='NEW'" :value="$t('label.storage.config.clone.new.volume')" disabled />
          <a-select v-else-if="targetMode==='CREATE_NEW' && volume===initialVolumeSource" :value="clone.existingvolumeid" :options="cloneOptions.volumes" disabled />
          <a-select v-else v-model:value="volumeMapping[volume]" :options="targetMode==='CREATE_NEW' ? cloneOptions.volumes : targetVolumes" :disabled="planPhase==='REVIEW'" />
        </a-form-item>
      </a-form>
      <template v-if="plan">
        <a-alert v-for="blocker in plan.blockers" :key="blocker" type="error" show-icon :message="blocker" />
        <a-descriptions :column="2" bordered size="small">
          <a-descriptions-item :label="$t('label.storage.config.create.resources')">{{ plan.create.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.update.resources')">{{ plan.update.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.keep.resources')">{{ plan.keep.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.preserve.resources')">{{ plan.preserve.length }}</a-descriptions-item>
        </a-descriptions>
        <a-table size="small" :columns="planColumns" :data-source="changes" :pagination="{ pageSize: 5 }" :scroll="{ x: 750 }" row-key="sourceUuid" />
        <a-form v-if="planPhase==='REVIEW'" layout="vertical">
          <template v-for="required in plan.requiredCredentials" :key="required.ruleUuid">
            <a-form-item v-for="field in required.fields" :key="required.ruleUuid+field" :label="required.principal + ' · ' + $t('label.storage.config.credential.' + field)">
              <a-input-password v-model:value="credentialValues[required.ruleUuid][field]" autocomplete="off" />
            </a-form-item>
          </template>
          <a-form-item :label="$t('label.storage.config.confirmation')"><a-input v-model:value="confirmation" :placeholder="plan.targetName" autocomplete="off" /></a-form-item>
        </a-form>
      </template>
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import SHA from 'sha.js'
import { CloudDownloadOutlined, UploadOutlined, DownloadOutlined, RollbackOutlined, ReloadOutlined } from '@ant-design/icons-vue'
const Sha256 = SHA.sha256

export default {
  name: 'StorageServiceConfiguration',
  components: { CloudDownloadOutlined, UploadOutlined, DownloadOutlined, RollbackOutlined, ReloadOutlined },
  props: { instanceId: { type: String, required: true }, resource: { type: Object, required: true } },
  data: () => ({ rows: [], loading: false, readFailed: false, generation: 0, busy: '', error: '', backupDialog: false, includeRuntime: true, retentionHours: 168, planTarget: null, plan: null, planPhase: 'MAPPING', planToken: '', lkgPlan: false, planning: false, volumeMapping: {}, targetVolumes: [], credentialValues: {}, confirmation: '', targetMode: 'RESTORE_EXISTING', clone: { name: '', size: 20, filesystem: 'XFS', networkmode: 'DHCP', backingvolumemode: 'NEW' }, initialVolumeSource: '', plannedVolume: '', cloneRuntime: '', cloneOptions: { zones: [], networks: [], offerings: [], disks: [], pools: [], bundles: [], volumes: [] } }),
  computed: {
    dialogBody () { return { maxHeight: '65vh', overflowY: 'auto' } },
    activePoint () { return this.rows.find(row => row.kind === 'RESTORE_POINT' && row.state === 'ACTIVE_LKG') },
    columns () {
      return [
        { title: this.$t('label.type'), dataIndex: 'kind', key: 'kind', width: 140 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 190 },
        { title: this.$t('label.posix.directory.revision'), dataIndex: 'desiredRevision', key: 'revision', width: 90 },
        { title: this.$t('label.created'), dataIndex: 'created', key: 'created', width: 190 },
        { title: 'SHA-256', dataIndex: 'sha256', key: 'sha256', width: 270 },
        { title: this.$t('label.actions'), key: 'actions', width: 260 }]
    },
    sourceVolumes () {
      const volumes = new Set()
      for (const row of this.changes) if (row.desired?.volumeUuid) volumes.add(row.desired.volumeUuid)
      return [...volumes]
    },
    changes () { return this.plan ? [...this.plan.create, ...this.plan.update, ...this.plan.keep] : [] },
    planColumns () {
      return [
        { title: this.$t('label.type'), dataIndex: 'kind', width: 180 },
        { title: this.$t('label.actions'), dataIndex: 'action', width: 100 },
        { title: this.$t('label.id'), dataIndex: 'sourceUuid', width: 280 }]
    }
  },
  watch: { instanceId () { this.generation++; this.rows = []; this.busy = ''; this.error = ''; this.closePlan(); this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++; this.credentialValues = {} },
  methods: {
    can (api) { return api in this.$store.getters.apis },
    unwrap (value, api) {
      const body = value[api.toLowerCase() + 'response'] || value
      const result = body.storageserviceconfiguration || body
      return typeof result.result === 'string' ? JSON.parse(result.result) : result.result || result
    },
    async refresh () {
      const token = ++this.generation; const instance = this.instanceId; this.loading = true
      try {
        const apis = ['listStorageServiceConfigBackups', 'listStorageServiceConfigImports', 'listStorageServiceRestorePoints'].filter(this.can)
        const values = await Promise.all(apis.map(api => getAPI(api, { instanceid: instance }, { preserveOnFailure: true, timeout: 15000 }).then(value => this.unwrap(value, api))))
        if (token !== this.generation || instance !== this.instanceId) return
        this.rows = values.flatMap(value => value.artifacts || []); this.readFailed = false
      } catch (error) { if (token === this.generation) this.readFailed = true } finally { if (token === this.generation) this.loading = false }
    },
    async mutation (api, parameters) {
      const instance = this.instanceId
      const value = await postAPI(api, { instanceid: instance, ...parameters })
      if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
      const body = value[api.toLowerCase() + 'response']; const job = body?.jobid
      if (!job) return this.unwrap(value, api)
      for (let attempt = 0; attempt < 180; attempt++) {
        if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
        const result = (await getAPI('queryAsyncJobResult', { jobid: job }, { preserveOnFailure: true, timeout: 15000 })).queryasyncjobresultresponse
        if (result.jobstatus === 1) return this.unwrap(result.jobresult, api)
        if (result.jobstatus === 2) throw new Error(result.jobresult?.errortext || this.$t('message.storage.config.failed'))
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
      throw new Error(this.$t('message.storage.config.timeout'))
    },
    async verifyBaseline () {
      this.busy = 'VERIFY'; this.error = ''
      try { await this.mutation('verifyStorageServiceConfiguration', {}); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async createBackup () {
      this.backupDialog = false; this.busy = 'BACKUP'; this.error = ''
      try { await this.mutation('createStorageServiceConfigBackup', { includeruntime: this.includeRuntime, retentionhours: this.retentionHours }); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async download (row) {
      const instance = this.instanceId
      this.busy = 'DOWNLOAD'; this.error = ''
      try {
        const issued = this.unwrap(await postAPI('downloadStorageServiceConfigBackup', { instanceid: instance, artifactid: row.id }), 'downloadStorageServiceConfigBackup')
        if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
        const value = this.unwrap(await postAPI('downloadStorageServiceConfigBackup', { instanceid: instance, artifactid: row.id, downloadtoken: issued.downloadToken }), 'downloadStorageServiceConfigBackup')
        if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
        const binary = Uint8Array.from(atob(value.data), value => value.charCodeAt(0))
        const href = URL.createObjectURL(new Blob([binary], { type: 'application/zip' })); const link = document.createElement('a')
        link.href = href; link.download = value.filename; link.click(); URL.revokeObjectURL(href)
      } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async importFile (file) {
      if (!file.size || file.size > 8 * 1024 * 1024 || !file.name.toLowerCase().endsWith('.zip')) { this.error = this.$t('message.storage.config.file.invalid'); return false }
      const instance = this.instanceId
      this.busy = 'UPLOAD'; this.error = ''
      try {
        const bytes = new Uint8Array(await file.arrayBuffer())
        const sha256 = new Sha256().update(bytes).digest('hex')
        const count = Math.ceil(bytes.length / (192 * 1024)); let artifact
        for (let index = 0; index < count; index++) {
          if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
          const chunk = bytes.slice(index * 192 * 1024, (index + 1) * 192 * 1024); let binary = ''
          for (const byte of chunk) binary += String.fromCharCode(byte)
          artifact = this.unwrap(await postAPI('uploadStorageServiceConfigBackup', { instanceid: instance, artifactid: artifact?.id, configurationpayload: btoa(binary), sha256, chunkindex: index, chunkcount: count }), 'uploadStorageServiceConfigBackup')
        }
        await this.refresh()
      } catch (error) { this.error = error.message } finally { this.busy = '' }
      return false
    },
    async deleteArtifact (row) {
      this.busy = 'DELETE'; this.error = ''
      try {
        await this.mutation(row.kind === 'BACKUP' ? 'deleteStorageServiceConfigBackup' : 'deleteStorageServiceConfigImport', { artifactid: row.id })
        await this.refresh()
      } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async validateImport (row) {
      this.busy = 'VALIDATE'; this.error = ''
      try { await this.mutation('validateStorageServiceConfigImport', { artifactid: row.id }); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async openPlan (row, lkg = false) {
      this.closePlan(); this.planTarget = row; this.lkgPlan = lkg; this.error = ''
      const instance = this.instanceId
      try {
        const volumes = (await getAPI('listVolumes', { virtualmachineid: this.resource.virtualmachineid }, { timeout: 15000, preserveOnFailure: true })).listvolumesresponse.volume || []
        if (instance !== this.instanceId || this.planTarget !== row) return
        this.targetVolumes = volumes.filter(volume => volume.type === 'DATADISK').map(volume => ({ value: volume.id, label: volume.name + ' · ' + volume.id }))
        await this.preparePlan()
      } catch (error) { this.error = error.message }
    },
    options (rows) { return (rows || []).map(row => ({ value: row.id, label: (row.name || row.version || row.id) + ' · ' + row.id })) },
    async loadCloneOptions () {
      if (this.targetMode !== 'CREATE_NEW') return
      this.planPhase = 'MAPPING'; this.planToken = ''; this.credentialValues = {}; this.confirmation = ''
      const target = this.planTarget; const instance = this.instanceId
      try {
        const zones = await getAPI('listZones', { available: true }, { timeout: 15000, preserveOnFailure: true })
        const bundles = await getAPI('listStorageServiceRuntimeBundles', {}, { timeout: 15000, preserveOnFailure: true })
        if (target !== this.planTarget || instance !== this.instanceId) return
        this.cloneOptions.zones = this.options(zones.listzonesresponse.zone)
        this.cloneOptions.bundles = this.options((bundles.liststorageserviceruntimebundlesresponse.storageserviceruntimebundle || []).filter(row => row.state === 'AVAILABLE' && row.serviceimpact === 'NONE'))
      } catch (error) { if (target === this.planTarget && instance === this.instanceId) this.error = error.message }
    },
    async loadCloneZoneOptions () {
      const target = this.planTarget; const instance = this.instanceId; const zoneid = this.clone.zoneid
      this.clone.networkid = undefined; this.clone.storageid = undefined; this.volumeMapping = {}
      try {
        const results = await Promise.all([
          getAPI('listNetworks', { zoneid }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listServiceOfferings', { zoneid, issystem: false }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listDiskOfferings', { zoneid }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listStoragePools', { zoneid }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listVolumes', { zoneid, type: 'DATADISK', state: 'Ready' }, { timeout: 15000, preserveOnFailure: true })])
        if (target !== this.planTarget || instance !== this.instanceId || zoneid !== this.clone.zoneid) return
        this.cloneOptions.networks = this.options(results[0].listnetworksresponse.network)
        this.cloneOptions.offerings = this.options(results[1].listserviceofferingsresponse.serviceoffering)
        this.cloneOptions.disks = this.options(results[2].listdiskofferingsresponse.diskoffering)
        this.cloneOptions.pools = this.options((results[3].liststoragepoolsresponse.storagepool || []).filter(row => row.state === 'Up'))
        this.cloneOptions.volumes = this.options((results[4].listvolumesresponse.volume || []).filter(row => !row.virtualmachineid))
      } catch (error) { if (target === this.planTarget && instance === this.instanceId) this.error = error.message }
    },
    async preparePlan () {
      const target = this.planTarget; const instance = this.instanceId
      this.planning = true; this.error = ''
      try {
        const api = this.lkgPlan ? 'planStorageServiceLastKnownGoodRestore' : 'planStorageServiceConfigRestore'
        const mappings = { volumes: { ...this.volumeMapping } }
        if (this.targetMode === 'CREATE_NEW') {
          if (!this.initialVolumeSource || !this.cloneRuntime) throw new Error(this.$t('message.storage.config.clone.required'))
          const existing = this.clone.backingvolumemode === 'EXISTING'
          if (existing && !this.clone.existingvolumeid) throw new Error(this.$t('message.storage.config.clone.required'))
          mappings.volumes[this.initialVolumeSource] = existing ? this.clone.existingvolumeid : 'NEW'
          mappings.createNew = { ...this.clone, backingvolumemode: existing ? 'EXISTING' : 'NEW' }
          if (existing) {
            delete mappings.createNew.diskofferingid; delete mappings.createNew.size; delete mappings.createNew.storageid
          } else delete mappings.createNew.existingvolumeid
          mappings.initialVolumeSourceUuid = this.initialVolumeSource; mappings.runtimeBundleUuid = this.cloneRuntime
        }
        const result = await this.mutation(api, { artifactid: this.planTarget.id, targetmode: this.targetMode, mapping: JSON.stringify(mappings) })
        if (target !== this.planTarget || instance !== this.instanceId) return
        this.plan = result.metadata.plan; this.planToken = result.planToken || ''
        this.planPhase = this.plan.blockers.length ? 'MAPPING' : 'REVIEW'
        this.credentialValues = Object.fromEntries(this.plan.requiredCredentials.map(row => [row.ruleUuid, {}]))
      } catch (error) { if (target === this.planTarget && instance === this.instanceId) this.error = error.message } finally { if (target === this.planTarget && instance === this.instanceId) this.planning = false }
    },
    async applyPlan () {
      if (this.confirmation !== this.plan.targetName || !this.planToken) { this.error = this.$t('message.storage.config.confirmation.required'); return }
      const parameters = { artifactid: this.planTarget.id, plantoken: this.planToken, confirmation: this.confirmation, credentials: JSON.stringify(this.credentialValues) }
      const api = this.lkgPlan ? 'restoreStorageServiceLastKnownGood' : 'applyStorageServiceConfigRestore'
      this.closePlan(); this.busy = 'RESTORE'; this.error = ''
      try { await this.mutation(api, parameters); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    closePlan () { this.planTarget = null; this.plan = null; this.planToken = ''; this.planPhase = 'MAPPING'; this.volumeMapping = {}; this.credentialValues = {}; this.confirmation = ''; this.lkgPlan = false; this.planning = false; this.targetMode = 'RESTORE_EXISTING'; this.initialVolumeSource = ''; this.plannedVolume = ''; this.cloneRuntime = ''; this.clone = { name: '', size: 20, filesystem: 'XFS', networkmode: 'DHCP', backingvolumemode: 'NEW' } }
  }
}
</script>
<style scoped>
.storage-configuration { margin-top: 20px; }
.storage-configuration .ant-space, .storage-configuration .ant-alert { margin-bottom: 16px; }
</style>
