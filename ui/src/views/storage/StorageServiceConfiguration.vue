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
          </a-space>
        </template>
        <template v-else>{{ record[column.dataIndex] }}</template>
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
        <a-form-item v-for="volume in sourceVolumes" :key="volume" :label="$t('label.storage.config.volume.mapping') + ': ' + volume">
          <a-select v-model:value="volumeMapping[volume]" :options="targetVolumes" :disabled="planPhase==='REVIEW'" />
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
  data: () => ({ rows: [], loading: false, readFailed: false, generation: 0, busy: '', error: '', backupDialog: false, includeRuntime: true, retentionHours: 168, planTarget: null, plan: null, planPhase: 'MAPPING', planToken: '', lkgPlan: false, planning: false, volumeMapping: {}, targetVolumes: [], credentialValues: {}, confirmation: '' }),
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
    async createBackup () {
      this.backupDialog = false; this.busy = 'BACKUP'; this.error = ''
      try { await this.mutation('createStorageServiceConfigBackup', { includeruntime: this.includeRuntime, retentionhours: this.retentionHours }); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async download (row) {
      this.busy = 'DOWNLOAD'; this.error = ''
      try {
        const issued = this.unwrap(await postAPI('downloadStorageServiceConfigBackup', { instanceid: this.instanceId, artifactid: row.id }), 'downloadStorageServiceConfigBackup')
        const value = this.unwrap(await postAPI('downloadStorageServiceConfigBackup', { instanceid: this.instanceId, artifactid: row.id, downloadtoken: issued.downloadToken }), 'downloadStorageServiceConfigBackup')
        const binary = Uint8Array.from(atob(value.data), value => value.charCodeAt(0))
        const href = URL.createObjectURL(new Blob([binary], { type: 'application/zip' })); const link = document.createElement('a')
        link.href = href; link.download = value.filename; link.click(); URL.revokeObjectURL(href)
      } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async importFile (file) {
      if (file.size > 8 * 1024 * 1024 || !file.name.toLowerCase().endsWith('.zip')) { this.error = this.$t('message.storage.config.file.invalid'); return false }
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
    async validateImport (row) {
      this.busy = 'VALIDATE'; this.error = ''
      try { await this.mutation('validateStorageServiceConfigImport', { artifactid: row.id }); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    async openPlan (row, lkg = false) {
      this.closePlan(); this.planTarget = row; this.lkgPlan = lkg; this.error = ''
      try {
        const volumes = (await getAPI('listVolumes', { virtualmachineid: this.resource.virtualmachineid }, { timeout: 15000, preserveOnFailure: true })).listvolumesresponse.volume || []
        this.targetVolumes = volumes.filter(volume => volume.type === 'DATADISK').map(volume => ({ value: volume.id, label: volume.name + ' · ' + volume.id }))
        await this.preparePlan()
      } catch (error) { this.error = error.message }
    },
    async preparePlan () {
      const target = this.planTarget; const instance = this.instanceId
      this.planning = true; this.error = ''
      try {
        const api = this.lkgPlan ? 'planStorageServiceLastKnownGoodRestore' : 'planStorageServiceConfigRestore'
        const result = await this.mutation(api, { artifactid: this.planTarget.id, targetmode: 'RESTORE_EXISTING', mapping: JSON.stringify({ volumes: this.volumeMapping }) })
        if (target !== this.planTarget || instance !== this.instanceId) return
        this.plan = result.metadata.plan; this.planToken = result.planToken || ''
        this.planPhase = this.plan.blockers.length ? 'MAPPING' : 'REVIEW'
        this.credentialValues = Object.fromEntries(this.plan.requiredCredentials.map(row => [row.ruleUuid, {}]))
      } catch (error) { this.error = error.message } finally { this.planning = false }
    },
    async applyPlan () {
      if (this.confirmation !== this.plan.targetName || !this.planToken) { this.error = this.$t('message.storage.config.confirmation.required'); return }
      const parameters = { artifactid: this.planTarget.id, plantoken: this.planToken, confirmation: this.confirmation, credentials: JSON.stringify(this.credentialValues) }
      const api = this.lkgPlan ? 'restoreStorageServiceLastKnownGood' : 'applyStorageServiceConfigRestore'
      this.closePlan(); this.busy = 'RESTORE'; this.error = ''
      try { await this.mutation(api, parameters); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    closePlan () { this.planTarget = null; this.plan = null; this.planToken = ''; this.planPhase = 'MAPPING'; this.volumeMapping = {}; this.credentialValues = {}; this.confirmation = ''; this.lkgPlan = false }
  }
}
</script>
<style scoped>
.storage-configuration { margin-top: 20px; }
.storage-configuration .ant-space, .storage-configuration .ant-alert { margin-bottom: 16px; }
</style>
