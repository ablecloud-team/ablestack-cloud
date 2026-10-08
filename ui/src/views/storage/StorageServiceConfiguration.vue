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
      <a-button v-if="can('createStorageServiceConfigBackup')" type="primary" :loading="!!busy" @click="openBackupDialog"><template #icon><CloudDownloadOutlined /></template>{{ $t('label.storage.config.create') }}</a-button>
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
        <template v-if="column.key==='state'"><a-tag>{{ artifactStateLabel(record) }}</a-tag></template>
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
          <a-descriptions-item :label="$t('label.storage.config.runtime.status')">{{ runtimeStateLabel(record.metadata?.runtimeStatus) }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.secret.coverage')">{{ record.metadata?.credentialCoverage || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.identity.include')">{{ identityCoverageLabel(record) }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.verification')">{{ record.metadata?.verification || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.runtime.revision')">{{ record.metadata?.runtimeRevision ?? '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.generation.operation')">{{ record.metadata?.nativeGeneration?.operationUuid || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.generation.checksum')">{{ record.metadata?.nativeGeneration?.configurationSha256 || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.restore.status')">{{ record.metadata?.restoreState || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.size')">{{ record.size }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.expires')">{{ record.expires ? new Date(record.expires).toLocaleString() : '—' }}</a-descriptions-item>
          <a-descriptions-item v-if="record.metadata?.errorCode" :label="$t('label.error')">{{ record.metadata.errorCode }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-table>
    <a-modal :visible="backupDialog" :title="$t('label.storage.config.create')" :body-style="dialogBody" @cancel="closeBackupDialog" @ok="createBackup">
      <a-alert type="info" show-icon :message="$t('message.storage.config.data.excluded')" />
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.config.runtime')"><a-switch v-model:checked="includeRuntime" /></a-form-item>
        <a-form-item><a-checkbox v-model:checked="includeAdIdentity" :disabled="!identityBackupSupported">{{ $t('label.storage.config.identity.include') }}</a-checkbox></a-form-item>
        <a-alert v-if="!identityBackupSupported" type="info" show-icon :message="$t('message.storage.service.ad.maintenance.unsupported')" />
        <template v-if="includeAdIdentity">
          <a-alert type="warning" show-icon :message="$t('message.storage.config.identity.backup.help')" />
          <a-form-item required><a-checkbox v-model:checked="backupMaintenance">{{ $t('message.storage.template.maintenance.confirm') }}</a-checkbox></a-form-item>
          <a-form-item required :label="$t('label.storage.config.confirmation')"><a-input v-model:value="backupConfirmation" :placeholder="instanceName" /></a-form-item>
        </template>
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
          <template v-else>
            <a-select v-model:value="volumeMapping[volume]" :options="targetMode==='CREATE_NEW' ? [{ value: 'NEW', label: $t('label.storage.config.clone.new.volume') }, ...cloneOptions.volumes] : targetVolumes" :disabled="planPhase==='REVIEW'" @change="setVolumeMapping(volume)" />
            <template v-if="targetMode==='CREATE_NEW' && volumeMapping[volume]==='NEW'">
              <a-form-item :label="$t('label.diskofferingid')"><a-select v-model:value="newVolumeSpecs[volume].diskofferingid" :options="cloneOptions.disks" :disabled="planPhase==='REVIEW'" /></a-form-item>
              <a-form-item :label="$t('label.storage.service.primary.storage')"><a-select v-model:value="newVolumeSpecs[volume].storageid" :options="cloneOptions.pools" :disabled="planPhase==='REVIEW'" /></a-form-item>
              <a-form-item :label="$t('label.storage.config.clone.size')"><a-input-number v-model:value="newVolumeSpecs[volume].sizeGiB" :min="1" :disabled="planPhase==='REVIEW'" /></a-form-item>
            </template>
          </template>
        </a-form-item>
      </a-form>
      <template v-if="plan">
        <template v-if="(plan.forcedFileExecutePolicies || []).length">
          <a-alert type="warning" show-icon :message="$t('message.storage.config.file.execute')" />
          <a-checkbox v-model:checked="confirmFileExecute" :disabled="planPhase==='REVIEW'">{{ $t('label.storage.config.file.execute.confirm') }}</a-checkbox>
        </template>
        <a-alert v-for="blocker in plan.blockers" :key="blocker" type="error" show-icon :message="blocker" />
        <a-descriptions :column="2" bordered size="small">
          <a-descriptions-item :label="$t('label.storage.config.create.resources')">{{ plan.create.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.update.resources')">{{ plan.update.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.keep.resources')">{{ plan.keep.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.preserve.resources')">{{ plan.preserve.length }}</a-descriptions-item>
        </a-descriptions>
        <a-alert v-for="directory in (plan.directoryPreparation || [])" :key="directory.sourceUuid" type="info" show-icon :message="$t('label.storage.config.directory.prepare') + ': ' + directory.relativePath" />
        <a-alert v-if="allocationRows.length" type="info" show-icon :message="$t('message.storage.config.allocation.preserve')" />
        <a-table v-if="allocationRows.length" size="small" :columns="allocationColumns" :data-source="allocationRows" :pagination="false" :scroll="{ x: 1300 }" row-key="sourceUuid" />
        <a-table size="small" :columns="planColumns" :data-source="changes" :pagination="{ pageSize: 5 }" :scroll="{ x: 750 }" row-key="sourceUuid" />
        <a-form v-if="planPhase==='REVIEW'" layout="vertical">
          <template v-for="required in plan.requiredCredentials" :key="required.ruleUuid">
            <a-form-item v-for="field in required.fields" :key="required.ruleUuid+field" :label="required.principal + ' · ' + $t('label.storage.config.credential.' + field)">
              <a-input-password v-model:value="credentialValues[required.ruleUuid][field]" autocomplete="off" />
            </a-form-item>
          </template>
          <template v-if="plan.adIdentityRestoreRequiresMaintenance === true">
            <a-alert type="warning" show-icon :message="$t('message.storage.service.ad.maintenance.help')" />
            <a-checkbox v-model:checked="restoreMaintenance">{{ $t('message.storage.template.maintenance.confirm') }}</a-checkbox>
            <a-descriptions :column="1" bordered size="small">
              <a-descriptions-item :label="$t('label.storage.config.identity.source.artifact')">{{ plan.adIdentitySourceDescriptor?.ownerArtifactUuid }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.storage.config.identity.source.service')">{{ plan.adIdentitySourceDescriptor?.sourceInstanceUuid }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.storage.config.generation.operation')">{{ plan.adIdentitySourceDescriptor?.sourceOperationUuid }}</a-descriptions-item>
            </a-descriptions>
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
import { supportsStorageFormatting, diskProvisioningLabel } from '@/utils/storageDiskProvisioning'
import { requireAdServiceApproval } from '@/utils/storageAdIdentity'
import { CloudDownloadOutlined, UploadOutlined, DownloadOutlined, RollbackOutlined, ReloadOutlined } from '@ant-design/icons-vue'
const Sha256 = SHA.sha256

export default {
  name: 'StorageServiceConfiguration',
  components: { CloudDownloadOutlined, UploadOutlined, DownloadOutlined, RollbackOutlined, ReloadOutlined },
  emits: ['operation-updated'],
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, default: '' }, resource: { type: Object, required: true } },
  data: () => ({ rows: [], loading: false, readFailed: false, generation: 0, busy: '', error: '', backupDialog: false, includeAdIdentity: false, backupMaintenance: false, backupConfirmation: '', includeRuntime: true, retentionHours: 168, planTarget: null, plan: null, planPhase: 'MAPPING', planToken: '', lkgPlan: false, planning: false, volumeMapping: {}, newVolumeSpecs: {}, targetVolumes: [], credentialValues: {}, confirmation: '', restoreMaintenance: false, reviewedPlan: '', confirmFileExecute: false, targetMode: 'RESTORE_EXISTING', clone: { name: '', size: 20, filesystem: 'XFS', networkmode: 'DHCP', backingvolumemode: 'NEW' }, initialVolumeSource: '', plannedVolume: '', cloneRuntime: '', cloneOptionToken: 0, cloneOptionScope: null, cloneOfferingRows: [], cloneOfferingLoading: false, cloneOfferingError: false, cloneOptions: { zones: [], networks: [], offerings: [], disks: [], pools: [], bundles: [], volumes: [] } }),
  computed: {
    cloneActiveProject () { return this.$store?.getters?.project?.id },
    identityBackupSupported () {
      const params = this.$getApiParams?.('createStorageServiceConfigBackup')
      return !!this.instanceName && !!params?.includeadidentity && !!params?.maintenancewindow && !!params?.confirmation
    },
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
    allocationRows () { return this.plan?.volumeAllocationPlan?.allocations || [] },
    allocationColumns () {
      return [
        { title: this.$t('label.storage.config.source.volume'), dataIndex: 'sourceUuid', width: 260 },
        { title: this.$t('label.storage.config.target.volume'), dataIndex: 'plannedUuid', width: 260 },
        { title: this.$t('label.type'), dataIndex: 'usage', width: 120 },
        { title: this.$t('label.storage.config.clone.volume.mode'), dataIndex: 'mode', width: 100 },
        { title: this.$t('label.provisioningtype'), dataIndex: 'provisioningType', width: 120 },
        { title: this.$t('label.diskofferingid'), dataIndex: 'offeringUuid', width: 260 },
        { title: this.$t('label.storage.service.primary.storage'), dataIndex: 'poolUuid', width: 260 },
        { title: this.$t('label.storage.config.size'), dataIndex: 'sizeBytes', width: 150 },
        { title: this.$t('label.storage.config.data.policy'), dataIndex: 'dataPolicy', width: 130 }]
    },
    planColumns () {
      return [
        { title: this.$t('label.type'), dataIndex: 'kind', width: 180 },
        { title: this.$t('label.actions'), dataIndex: 'action', width: 100 },
        { title: this.$t('label.id'), dataIndex: 'sourceUuid', width: 280 }]
    }
  },
  watch: {
    instanceId () { this.generation++; this.rows = []; this.busy = ''; this.error = ''; this.closeBackupDialog(); this.closePlan(); this.refresh() },
    instanceName () { this.closeBackupDialog() },
    includeAdIdentity (value) { if (!value) { this.backupMaintenance = false; this.backupConfirmation = '' } },
    plan: { deep: true, handler () { if (this.reviewedPlan && this.reviewedPlan !== this.restoreReviewFingerprint()) this.restoreMaintenance = false } },
    planToken () { if (this.reviewedPlan && this.reviewedPlan !== this.restoreReviewFingerprint()) this.restoreMaintenance = false },
    'resource.account' () { this.clearCloneZoneOptions() },
    'resource.domainid' () { this.clearCloneZoneOptions() },
    'resource.projectid' () { this.clearCloneZoneOptions() },
    cloneActiveProject () { this.clearCloneZoneOptions() }
  },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++; this.cloneOptionToken++; this.cloneOptionScope = null; this.cloneOfferingRows = []; this.credentialValues = {} },
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
        if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
        if (result.jobstatus === 1 || result.jobstatus === 2) {
          if (['verifyStorageServiceConfiguration', 'applyStorageServiceConfigRestore', 'restoreStorageServiceLastKnownGood'].includes(api)) this.$emit('operation-updated', instance)
          if (result.jobstatus === 2) throw new Error(result.jobresult?.errortext || this.$t('message.storage.config.failed'))
          return this.unwrap(result.jobresult, api)
        }
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
      throw new Error(this.$t('message.storage.config.timeout'))
    },
    artifactStateLabel (record) {
      const runtime = record.metadata?.runtimeStatus
      return record.state === 'PARTIAL' && ['UNAVAILABLE', 'NOT_REQUESTED'].includes(runtime)
        ? this.$t('label.storage.config.runtime.' + runtime)
        : this.$t('label.storage.config.state.' + record.state)
    },
    runtimeStateLabel (status) {
      return ['AVAILABLE', 'UNAVAILABLE', 'PARTIAL', 'NOT_REQUESTED', 'UNAVAILABLE_OR_PARTIAL'].includes(status)
        ? this.$t('label.storage.config.runtime.' + status) : (status || '—')
    },
    validIdentityDescriptor (descriptor) {
      const fields = ['schemaVersion', 'kind', 'ownerArtifactUuid', 'sourceInstanceUuid', 'sourceOperationUuid', 'sourceConfigurationSha256', 'ciphertextSha256', 'issuerMac']
      const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
      const sha = /^[a-f0-9]{64}$/
      return descriptor && typeof descriptor === 'object' && !Array.isArray(descriptor) &&
        Object.keys(descriptor).length === fields.length && fields.every(field => Object.prototype.hasOwnProperty.call(descriptor, field)) &&
        descriptor.schemaVersion === 1 && descriptor.kind === 'STORAGE_AD_SEMANTIC_SOURCE' &&
        ['ownerArtifactUuid', 'sourceInstanceUuid', 'sourceOperationUuid'].every(field => typeof descriptor[field] === 'string' && uuid.test(descriptor[field])) &&
        ['sourceConfigurationSha256', 'ciphertextSha256', 'issuerMac'].every(field => typeof descriptor[field] === 'string' && sha.test(descriptor[field]))
    },
    identityCoverageLabel (record) {
      const metadata = record.metadata || {}
      const valid = metadata.adIdentityCoverage === 'VERIFIED_ENCRYPTED_FULL_IDENTITY' && this.validIdentityDescriptor(metadata.adIdentitySourceDescriptor)
      return this.$t(valid ? 'label.storage.config.identity.preserved' : 'label.storage.config.identity.unverified')
    },
    async verifyBaseline () {
      this.busy = 'VERIFY'; this.error = ''
      try { await this.mutation('verifyStorageServiceConfiguration', {}); await this.refresh() } catch (error) { this.error = error.message } finally { this.busy = '' }
    },
    openBackupDialog () {
      this.includeAdIdentity = false; this.backupMaintenance = false; this.backupConfirmation = ''; this.error = ''; this.backupDialog = true
    },
    closeBackupDialog () {
      this.backupDialog = false; this.includeAdIdentity = false; this.backupMaintenance = false; this.backupConfirmation = ''
    },
    buildBackupRequest () {
      const request = { includeruntime: this.includeRuntime, retentionhours: this.retentionHours }
      if (this.includeAdIdentity !== undefined && typeof this.includeAdIdentity !== 'boolean') throw new Error(this.$t('message.storage.service.ad.maintenance.required'))
      if (this.includeAdIdentity === true) {
        if (!this.identityBackupSupported) throw new Error(this.$t('message.storage.service.ad.maintenance.unsupported'))
        try {
          Object.assign(request, { includeadidentity: true }, requireAdServiceApproval({ id: this.instanceId, name: this.instanceName }, { maintenancewindow: this.backupMaintenance, confirmation: this.backupConfirmation }))
        } catch (_) { throw new Error(this.$t('message.storage.service.ad.maintenance.required')) }
      }
      return request
    },
    async createBackup () {
      if (this.busy) return
      const instance = this.instanceId; const name = this.instanceName
      let submitted = false
      this.error = ''
      try {
        const request = this.buildBackupRequest()
        this.backupDialog = false; this.busy = 'BACKUP'; submitted = true
        await this.mutation('createStorageServiceConfigBackup', request)
        if (instance !== this.instanceId || name !== this.instanceName) throw new Error(this.$t('message.storage.config.scope.changed'))
        await this.refresh()
      } catch (error) { this.error = error.message } finally { this.busy = ''; if (submitted) this.includeAdIdentity = false; this.backupMaintenance = false; this.backupConfirmation = '' }
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
        if (!value.sha256 || new Sha256().update(binary).digest('hex') !== value.sha256) throw new Error(this.$t('message.storage.config.download.integrity'))
        const href = URL.createObjectURL(new Blob([binary], { type: 'application/zip' })); const link = document.createElement('a')
        link.href = href; link.download = value.filename; link.style.display = 'none'
        document.body.appendChild(link)
        try { link.click() } finally {
          link.remove()
          // Let asynchronous browser download handling acquire the Blob before releasing it.
          setTimeout(() => URL.revokeObjectURL(href), 60000)
        }
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
    cloneDiscoveryScope () {
      return JSON.stringify({ instance: this.instanceId, artifact: this.planTarget?.id, zone: this.clone.zoneid, account: this.resource?.account, domain: this.resource?.domainid, project: this.resource?.projectid, activeProject: this.$store?.getters?.project?.id })
    },
    sparseCloneRootOffering (offering) {
      const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
      return typeof offering?.id === 'string' && uuid.test(offering.id) && typeof offering.provisioningtype === 'string' &&
        ['sparse', 'fat'].includes(offering.provisioningtype.toLowerCase()) && typeof offering.diskofferingid === 'string' && uuid.test(offering.diskofferingid)
    },
    clearCloneZoneOptions () {
      this.cloneOptionToken++; this.cloneOptionScope = null; this.cloneOfferingRows = []; this.cloneOfferingLoading = false; this.cloneOfferingError = false
      for (const key of ['networks', 'offerings', 'disks', 'pools', 'volumes']) this.cloneOptions[key] = []
      for (const key of ['networkid', 'storageid', 'diskofferingid', 'serviceofferingid', 'existingvolumeid']) this.clone[key] = undefined
      this.volumeMapping = {}; this.newVolumeSpecs = {}; this.restoreMaintenance = false; this.reviewedPlan = ''; this.planToken = ''; this.confirmation = ''
    },
    assertCloneOffering () {
      const offering = this.cloneOfferingRows.find(row => row.id === this.clone.serviceofferingid)
      if (this.cloneOfferingLoading || this.cloneOfferingError || this.cloneOptionScope !== this.cloneDiscoveryScope() || !this.sparseCloneRootOffering(offering) || offering.compatibility?.compatible !== true) {
        throw new Error(this.$t('message.storage.disk.sparse.required'))
      }
    },
    async loadCloneZoneOptions () {
      const target = this.planTarget; const instance = this.instanceId; const zoneid = this.clone.zoneid
      this.clearCloneZoneOptions()
      const token = ++this.cloneOptionToken; const scope = this.cloneDiscoveryScope()
      if (!zoneid) return
      this.cloneOfferingLoading = true
      const owner = this.resource?.projectid ? { projectid: this.resource.projectid } : { account: this.resource?.account, domainid: this.resource?.domainid }
      try {
        const results = await Promise.all([
          getAPI('listNetworks', { zoneid, ...owner }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listServiceOfferings', { zoneid, issystem: false, ...owner }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listDiskOfferings', { zoneid, ...owner }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listStoragePools', { zoneid }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listVolumes', { zoneid, type: 'DATADISK', state: 'Ready', ...owner }, { timeout: 15000, preserveOnFailure: true })])
        if (token !== this.cloneOptionToken || target !== this.planTarget || instance !== this.instanceId || zoneid !== this.clone.zoneid || scope !== this.cloneDiscoveryScope()) return
        const offers = results[1].listserviceofferingsresponse?.serviceoffering || []
        const constraints = offers.length ? await getAPI('listStorageServiceOfferingConstraints', { zoneid, serviceofferingids: offers.map(row => row.id).join(',') }, { timeout: 15000, preserveOnFailure: true }) : {}
        if (token !== this.cloneOptionToken || scope !== this.cloneDiscoveryScope()) return
        const byId = Object.fromEntries((constraints.liststorageserviceofferingconstraintsresponse?.storageserviceofferingconstraint || []).map(row => [row.id, row]))
        this.cloneOfferingRows = offers.map(row => ({ ...row, compatibility: byId[row.id] || null }))
        const compatible = this.cloneOfferingRows.filter(row => this.sparseCloneRootOffering(row) && row.compatibility?.compatible === true)
        this.cloneOptions.networks = this.options(results[0].listnetworksresponse.network)
        this.cloneOptions.offerings = this.options(compatible)
        this.cloneOptions.disks = (results[2].listdiskofferingsresponse.diskoffering || []).filter(supportsStorageFormatting).map(row => ({ value: row.id, label: (row.name || row.id) + ' · ' + diskProvisioningLabel(row) + ' · ' + row.id }))
        this.cloneOptions.pools = this.options((results[3].liststoragepoolsresponse.storagepool || []).filter(row => row.state === 'Up'))
        this.cloneOptions.volumes = this.options((results[4].listvolumesresponse.volume || []).filter(row => !row.virtualmachineid))
        this.cloneOptionScope = scope
        this.clone.serviceofferingid = compatible[0]?.id
        if (!compatible.length) this.error = this.$t('message.storage.service.offering.no.compatible')
      } catch (error) { if (token === this.cloneOptionToken && scope === this.cloneDiscoveryScope()) { this.cloneOfferingError = true; this.error = this.$t('message.storage.service.offering.unavailable') } } finally { if (token === this.cloneOptionToken) this.cloneOfferingLoading = false }
    },
    setVolumeMapping (source) {
      if (this.volumeMapping[source] === 'NEW') this.newVolumeSpecs[source] = this.newVolumeSpecs[source] || { dataPolicy: 'PRESERVE' }
      else delete this.newVolumeSpecs[source]
      this.planToken = ''; this.confirmation = ''; this.restoreMaintenance = false; this.reviewedPlan = ''
    },
    async preparePlan () {
      const target = this.planTarget; const instance = this.instanceId
      this.planning = true; this.error = ''; this.restoreMaintenance = false; this.reviewedPlan = ''
      try {
        const api = this.lkgPlan ? 'planStorageServiceLastKnownGoodRestore' : 'planStorageServiceConfigRestore'
        const mappings = { volumes: { ...this.volumeMapping }, confirmFileExecute: this.confirmFileExecute }
        if (this.targetMode === 'CREATE_NEW') {
          if (!this.initialVolumeSource || !this.cloneRuntime) throw new Error(this.$t('message.storage.config.clone.required'))
          this.assertCloneOffering()
          const existing = this.clone.backingvolumemode === 'EXISTING'
          if (existing && !this.clone.existingvolumeid) throw new Error(this.$t('message.storage.config.clone.required'))
          mappings.volumes[this.initialVolumeSource] = existing ? this.clone.existingvolumeid : 'NEW'
          mappings.createNew = { ...this.clone, backingvolumemode: existing ? 'EXISTING' : 'NEW' }
          if (existing) {
            delete mappings.createNew.diskofferingid; delete mappings.createNew.size; delete mappings.createNew.storageid
          } else delete mappings.createNew.existingvolumeid
          const specifications = Object.fromEntries(Object.entries(this.volumeMapping).filter(([source, target]) => source !== this.initialVolumeSource && target === 'NEW').map(([source]) => [source, { ...(this.newVolumeSpecs?.[source] || {}), dataPolicy: 'PRESERVE' }]))
          if (Object.keys(specifications).length) mappings.newVolumes = specifications
          mappings.initialVolumeSourceUuid = this.initialVolumeSource; mappings.runtimeBundleUuid = this.cloneRuntime
        }
        const result = await this.mutation(api, { artifactid: this.planTarget.id, targetmode: this.targetMode, mapping: JSON.stringify(mappings) })
        if (target !== this.planTarget || instance !== this.instanceId) return
        this.plan = result.metadata.plan; this.planToken = result.planToken || ''
        this.planPhase = this.plan.blockers.length ? 'MAPPING' : 'REVIEW'
        this.reviewedPlan = JSON.stringify({ instance: this.instanceId, artifact: this.planTarget.id, token: this.planToken, plan: this.plan })
        this.credentialValues = Object.fromEntries(this.plan.requiredCredentials.map(row => [row.ruleUuid, {}]))
      } catch (error) { if (target === this.planTarget && instance === this.instanceId) this.error = error.message } finally { if (target === this.planTarget && instance === this.instanceId) this.planning = false }
    },
    restoreReviewFingerprint () {
      return JSON.stringify({ instance: this.instanceId, artifact: this.planTarget?.id, token: this.planToken, plan: this.plan })
    },
    async applyPlan () {
      const requiresIdentity = this.plan?.adIdentityRestoreRequiresMaintenance === true
      if (!this.plan || this.confirmation !== this.plan.targetName || typeof this.planToken !== 'string' || !this.planToken) { this.error = this.$t('message.storage.config.confirmation.required'); return }
      if (!this.reviewedPlan || this.reviewedPlan !== this.restoreReviewFingerprint()) { this.restoreMaintenance = false; this.error = this.$t('message.storage.config.scope.changed'); return }
      const api = this.lkgPlan ? 'restoreStorageServiceLastKnownGood' : 'applyStorageServiceConfigRestore'
      if (!this.can(api)) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.setup.api.missing.with.name', { api }); return }
      if ((this.plan.adIdentityRestoreRequiresMaintenance !== undefined && typeof this.plan.adIdentityRestoreRequiresMaintenance !== 'boolean') ||
        (this.plan.adIdentitySourceDescriptor !== undefined && !requiresIdentity)) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.ad.receipt.unverified'); return }
      if (requiresIdentity) {
        if (!this.validIdentityDescriptor(this.plan.adIdentitySourceDescriptor) || typeof this.plan.artifactSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(this.plan.artifactSha256)) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.ad.receipt.unverified'); return }
        if (!this.$getApiParams?.(api)?.maintenancewindow || this.restoreMaintenance !== true) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.ad.maintenance.required'); return }
      }
      const parameters = { artifactid: this.planTarget.id, plantoken: this.planToken, confirmation: this.confirmation, credentials: JSON.stringify(this.credentialValues) }
      if (requiresIdentity) parameters.maintenancewindow = true
      const instance = this.instanceId
      this.closePlan(); this.busy = 'RESTORE'; this.error = ''
      try {
        await this.mutation(api, parameters)
        if (instance !== this.instanceId) throw new Error(this.$t('message.storage.config.scope.changed'))
        await this.refresh()
      } catch (_) { this.error = this.$t('message.storage.config.failed') } finally { parameters.credentials = ''; this.busy = '' }
    },
    closePlan () { this.cloneOptionToken++; this.cloneOptionScope = null; this.cloneOfferingRows = []; this.cloneOfferingLoading = false; this.cloneOfferingError = false; this.planTarget = null; this.plan = null; this.planToken = ''; this.restoreMaintenance = false; this.reviewedPlan = ''; this.planPhase = 'MAPPING'; this.volumeMapping = {}; this.newVolumeSpecs = {}; this.credentialValues = {}; this.confirmation = ''; this.lkgPlan = false; this.planning = false; this.confirmFileExecute = false; this.targetMode = 'RESTORE_EXISTING'; this.initialVolumeSource = ''; this.plannedVolume = ''; this.cloneRuntime = ''; this.clone = { name: '', size: 20, filesystem: 'XFS', networkmode: 'DHCP', backingvolumemode: 'NEW' } }
  }
}
</script>
<style scoped>
.storage-configuration { margin-top: 20px; }
.storage-configuration .ant-space, .storage-configuration .ant-alert { margin-bottom: 16px; }
</style>
