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
      <a-button v-if="can('createStorageServiceConfigBackup')" type="primary" :loading="!!busy" :disabled="configurationLocked" @click="openBackupDialog"><template #icon><CloudDownloadOutlined /></template>{{ $t('label.storage.config.create') }}</a-button>
      <a-upload v-if="can('uploadStorageServiceConfigBackup')" :before-upload="importFile" :show-upload-list="false" accept=".zip"><a-button :disabled="configurationLocked"><template #icon><UploadOutlined /></template>{{ $t('label.storage.config.import') }}</a-button></a-upload>
      <a-button v-if="can('verifyStorageServiceConfiguration')" :disabled="storageMutationDisabled || configurationLocked" @click="verifyBaseline"><template #icon><ReloadOutlined /></template>{{ $t('label.storage.config.verify.baseline') }}</a-button>
      <a-button v-if="can('planStorageServiceLastKnownGoodRestore')" :disabled="!activePoint || storageMutationDisabled || configurationLocked" @click="openPlan(activePoint, true)"><template #icon><RollbackOutlined /></template>{{ $t('label.storage.config.lkg.restore') }}</a-button>
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="busy" type="info" show-icon :message="$t('message.storage.config.running')" />
    <a-alert v-if="configurationIntent && (configurationIntent.jobId || ['unknown','submitting'].includes(configurationIntent.status))" :type="configurationIntent.status==='failed' ? 'error' : configurationIntent.status==='unknown' ? 'warning' : 'info'" show-icon :message="$t('label.storage.config.job.' + configurationIntent.status)">
      <template #description><span v-if="configurationIntent.jobId">{{ configurationIntent.jobId }}</span><span v-else-if="configurationIntent.status==='unknown'">{{ $t('message.storage.config.job.no.id') }}</span><span v-if="configurationIntent.review.targetMode"> · {{ $t(configurationIntent.review.targetMode==='CREATE_NEW' ? 'label.storage.config.target.new' : 'label.storage.config.target.existing') }}</span><span v-if="configurationIntent.artifactId"> · {{ configurationIntent.artifactId }}</span><span v-if="configurationIntent.review.targetMode"> · {{ $t('message.storage.config.job.review', configurationIntent.review) }}</span></template>
      <template #action><a-button v-if="configurationIntent.jobId && ['running','unknown'].includes(configurationIntent.status)" :loading="busy==='TRACKING'" @click="resumeConfigurationJob">{{ $t('label.storage.config.job.query') }}</a-button></template>
    </a-alert>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.storage.config.read.failed')" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :pagination="{ pageSize: 5 }" :scroll="{ x: 1100 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key==='state'"><a-tag>{{ artifactStateLabel(record) }}</a-tag></template>
        <template v-else-if="column.key==='kind'">{{ $t('label.storage.config.kind.' + record.kind) }}</template>
        <template v-else-if="column.key==='created'">{{ new Date(record.created).toLocaleString() }}</template>
        <template v-else-if="column.key==='actions'">
          <a-space wrap>
            <a-button v-if="record.kind==='BACKUP' && ['COMPLETE','PARTIAL'].includes(record.state) && can('downloadStorageServiceConfigBackup')" size="small" :disabled="configurationLocked" @click="download(record)"><template #icon><DownloadOutlined /></template>{{ $t('label.download') }}</a-button>
            <a-button v-if="record.kind==='IMPORT' && record.state==='QUARANTINED' && can('validateStorageServiceConfigImport')" size="small" :disabled="configurationLocked" @click="validateImport(record)">{{ $t('label.storage.config.validate') }}</a-button>
            <a-button v-if="['COMPLETE','PARTIAL','ARCHIVE_VALIDATED','ACTIVE_LKG','SUPERSEDED'].includes(record.state) && can('planStorageServiceConfigRestore')" size="small" :disabled="configurationLocked" @click="openPlan(record)">{{ $t('label.storage.config.plan') }}</a-button>
            <a-popconfirm v-if="['BACKUP','IMPORT'].includes(record.kind) && can(record.kind==='BACKUP' ? 'deleteStorageServiceConfigBackup' : 'deleteStorageServiceConfigImport')" :title="$t('message.storage.config.delete.confirm')" @confirm="deleteArtifact(record)">
              <a-button size="small" danger :disabled="configurationLocked">{{ $t('label.delete') }}</a-button>
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
:ok-button-props="{ disabled: cloneResumeUnavailable || configurationLocked || ((lkgPlan || planPhase==='REVIEW') && storageMutationDisabled) }"
@cancel="closePlan"
      @ok="planPhase==='REVIEW' ? applyPlan() : preparePlan()">
      <a-alert type="info" show-icon :message="$t('message.storage.config.data.excluded')" />
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert v-if="cloneResume" type="info" show-icon :message="$t('message.storage.config.clone.resume')">
        <template #description>{{ cloneResume.targetName }} · {{ cloneResume.createdTargetInstanceUuid || cloneResume.plan.targetInstanceUuid }}</template>
      </a-alert>
      <a-form layout="vertical">
        <a-form-item v-if="!lkgPlan" :label="$t('label.storage.config.target.mode')">
          <a-radio-group v-model:value="targetMode" :disabled="mappingLocked" @change="loadCloneOptions">
            <a-radio value="RESTORE_EXISTING">{{ $t('label.storage.config.target.existing') }}</a-radio>
            <a-radio value="CREATE_NEW">{{ $t('label.storage.config.target.new') }}</a-radio>
          </a-radio-group>
        </a-form-item>
        <template v-if="targetMode==='CREATE_NEW'">
          <a-form-item :label="$t('label.name')"><a-input v-model:value="clone.name" :disabled="mappingLocked" /></a-form-item>
          <a-form-item :label="$t('label.zoneid')"><a-select v-model:value="clone.zoneid" :options="cloneOptions.zones" :disabled="mappingLocked" @change="loadCloneZoneOptions" /></a-form-item>
          <a-form-item v-if="!cloneRequiresIdentityTemplate" :label="$t('label.storage.config.clone.template.source')">
            <a-radio-group v-model:value="cloneTemplateSelection" :disabled="mappingLocked" @change="loadCloneTemplates">
              <a-radio value="DEFAULT_SYSTEM">{{ $t('label.storage.config.clone.template.default') }}</a-radio>
              <a-radio value="EXPLICIT">{{ $t('label.storage.config.clone.template.explicit') }}</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="cloneRequiresIdentityTemplate || cloneTemplateSelection==='EXPLICIT'" :label="$t('label.templateid')">
            <a-select v-model:value="clone.templateid" :options="cloneOptions.templates" :loading="cloneTemplateLoading" :disabled="mappingLocked" show-search option-filter-prop="label" />
            <a-alert v-if="cloneRequiresIdentityTemplate" type="info" show-icon :message="$t('message.storage.config.clone.identity.template.required')" />
            <a-alert v-else type="info" show-icon :message="$t('message.storage.config.clone.template.explicit')" />
          </a-form-item>
          <template v-if="ordinaryClonePrivateTemplate">
            <a-form-item :label="$t('label.storage.config.clone.template.approval.id')"><a-input v-model:value="clone.validationartifactuuid" :disabled="mappingLocked" autocomplete="off" /></a-form-item>
            <a-form-item :label="$t('label.storage.config.clone.template.approval.sha')"><a-input v-model:value="clone.validationartifactsha256" :disabled="mappingLocked" autocomplete="off" /></a-form-item>
          </template>
          <a-form-item :label="$t('label.networkid')"><a-select v-model:value="clone.networkid" :options="cloneOptions.networks" :disabled="mappingLocked" /></a-form-item>
          <a-form-item :label="$t('label.sharedfs.network.mode')">
            <a-radio-group v-model:value="clone.networkmode" :disabled="mappingLocked" @change="editPlanMapping">
              <a-radio value="DHCP">DHCP</a-radio><a-radio value="STATIC">STATIC</a-radio>
            </a-radio-group>
          </a-form-item>
          <template v-if="clone.networkmode==='STATIC'">
            <a-form-item :label="$t('label.sharedfs.ip.cidr')" required><a-input v-model:value="clone.ipcidr" :disabled="mappingLocked" @change="editPlanMapping" /></a-form-item>
            <a-form-item :label="$t('label.gateway')"><a-input v-model:value="clone.gateway" :disabled="mappingLocked" @change="editPlanMapping" /></a-form-item>
            <a-form-item :label="$t('label.dns1')"><a-input v-model:value="clone.dns1" :disabled="mappingLocked" @change="editPlanMapping" /></a-form-item>
            <a-form-item :label="$t('label.dns2')"><a-input v-model:value="clone.dns2" :disabled="mappingLocked" @change="editPlanMapping" /></a-form-item>
          </template>
          <a-form-item :label="$t('label.serviceofferingid')"><a-select v-model:value="clone.serviceofferingid" :options="cloneOptions.offerings" :disabled="mappingLocked" /></a-form-item>
          <a-form-item :label="$t('label.diskofferingid')"><a-select v-model:value="clone.diskofferingid" :options="cloneOptions.disks" :disabled="mappingLocked" /></a-form-item>
          <a-form-item :label="$t('label.storage.service.primary.storage')"><a-select v-model:value="clone.storageid" :options="cloneOptions.pools" :disabled="mappingLocked" /></a-form-item>
          <a-form-item :label="$t('label.storage.config.clone.volume.mode')">
            <a-radio-group v-model:value="clone.backingvolumemode" :disabled="mappingLocked">
              <a-radio value="NEW">{{ $t('label.storage.config.clone.new.volume') }}</a-radio>
              <a-radio value="EXISTING">{{ $t('label.storage.service.existing.volume.select') }}</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="clone.backingvolumemode==='NEW'" :label="$t('label.storage.config.clone.size')"><a-input-number v-model:value="clone.size" :min="1" :disabled="mappingLocked" /></a-form-item>
          <a-form-item v-else :label="$t('label.storage.service.existing.volume.select')"><a-select v-model:value="clone.existingvolumeid" :options="cloneOptions.volumes" :disabled="mappingLocked" /></a-form-item>
          <a-form-item :label="$t('label.storage.config.clone.initial')"><a-select v-model:value="initialVolumeSource" :options="initialSourceVolumeOptions" :disabled="planning || mappingLocked" @change="selectInitialVolume" /></a-form-item>
          <a-form-item :label="$t('label.filesystem')">
            <a-select v-model:value="clone.filesystem" :disabled="planning || mappingLocked" @change="editPlanMapping">
              <a-select-option value="XFS" label="XFS">XFS</a-select-option>
              <a-select-option value="EXT4" label="EXT4">EXT4</a-select-option>
            </a-select>
            <a-alert v-if="initialSourceFilesystemHint" type="info" show-icon :message="$t('message.storage.config.clone.source.filesystem', { filesystem: initialSourceFilesystemHint })" />
          </a-form-item>
          <a-form-item :label="$t('label.storage.config.clone.runtime')"><a-select v-model:value="cloneRuntime" :options="cloneOptions.bundles" :disabled="mappingLocked" /></a-form-item>
        </template>
        <a-form-item v-for="volume in sourceVolumes" :key="volume" :label="$t('label.storage.config.volume.mapping') + ': ' + volume">
          <a-input v-if="targetMode==='CREATE_NEW' && volume===initialVolumeSource && clone.backingvolumemode==='NEW'" :value="$t('label.storage.config.clone.new.volume')" disabled />
          <a-select v-else-if="targetMode==='CREATE_NEW' && volume===initialVolumeSource" :value="clone.existingvolumeid" :options="cloneOptions.volumes" disabled />
          <template v-else>
            <a-select v-model:value="volumeMapping[volume]" :options="targetMode==='CREATE_NEW' ? [{ value: 'NEW', label: $t('label.storage.config.clone.new.volume') }, ...cloneOptions.volumes] : targetVolumes" :disabled="mappingLocked" @change="setVolumeMapping(volume)" />
            <template v-if="targetMode==='CREATE_NEW' && volumeMapping[volume]==='NEW'">
              <a-form-item :label="$t('label.diskofferingid')"><a-select v-model:value="newVolumeSpecs[volume].diskofferingid" :options="cloneOptions.disks" :disabled="mappingLocked" /></a-form-item>
              <a-form-item :label="$t('label.storage.service.primary.storage')"><a-select v-model:value="newVolumeSpecs[volume].storageid" :options="cloneOptions.pools" :disabled="mappingLocked" /></a-form-item>
              <a-form-item :label="$t('label.storage.config.clone.size')"><a-input-number v-model:value="newVolumeSpecs[volume].sizeGiB" :min="1" :disabled="mappingLocked" /></a-form-item>
            </template>
          </template>
        </a-form-item>
      </a-form>
      <template v-if="plan">
        <a-button v-if="planPhase==='REVIEW' && !cloneResume" :disabled="configurationLocked" @click="editPlanMapping">{{ $t('label.storage.config.mapping.edit') }}</a-button>
        <a-form v-if="listenerAddressRows.length" layout="vertical">
          <a-form-item v-for="row in listenerAddressRows" :key="row.sourceAddress" :label="$t('label.storage.config.listener.mapping') + ': ' + row.sourceAddress">
            <a-radio-group :value="row.choice" :disabled="mappingLocked" @change="event => setListenerMapping(row.sourceAddress, event.target.value)">
              <a-radio value="PRIMARY">{{ $t('label.storage.config.listener.primary') }}</a-radio><a-radio value="CUSTOM">{{ $t('label.storage.config.listener.custom') }}</a-radio>
            </a-radio-group>
            <a-input v-if="row.choice==='CUSTOM'" :value="row.address" :disabled="mappingLocked" @update:value="value => setListenerAddress(row.sourceAddress, value)" />
            <div v-if="row.resolvedTarget">{{ $t('label.ipaddress') }}: {{ row.resolvedTarget }}</div>
            <div v-for="location in row.locations" :key="location.kind + ':' + location.sourceUuid + ':' + location.path">{{ location.kind }} · {{ location.sourceUuid }} · {{ location.path }}</div>
          </a-form-item>
        </a-form>
        <a-form v-if="resourceMappingRows.length" layout="vertical">
          <a-form-item v-for="row in resourceMappingRows" :key="row.key" :label="$t('label.storage.config.resource.mapping') + ': ' + row.kind + ' · ' + row.sourceUuid">
            <a-select :value="row.selected" :options="row.options" :disabled="mappingLocked" @change="value => setResourceMapping(row, value)" />
          </a-form-item>
        </a-form>
        <template v-if="(plan.forcedFileExecutePolicies || []).length">
          <a-alert type="warning" show-icon :message="$t('message.storage.config.file.execute')" />
          <a-checkbox v-model:checked="confirmFileExecute" :disabled="mappingLocked">{{ $t('label.storage.config.file.execute.confirm') }}</a-checkbox>
        </template>
        <a-alert v-for="blocker in plan.blockers" :key="blocker" type="error" show-icon :message="configurationBlockerLabel(blocker)" />
        <a-descriptions :column="2" bordered size="small">
          <a-descriptions-item :label="$t('label.storage.config.create.resources')">{{ plan.create.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.update.resources')">{{ plan.update.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.keep.resources')">{{ plan.keep.length }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.preserve.resources')">{{ plan.preserve.length }}</a-descriptions-item>
        </a-descriptions>
        <a-alert v-for="directory in (plan.directoryPreparation || [])" :key="directory.sourceUuid" type="info" show-icon :message="$t('label.storage.config.directory.prepare') + ': ' + directory.relativePath" />
        <a-alert v-if="allocationRows.length" type="info" show-icon :message="$t('message.storage.config.allocation.preserve')" />
        <a-table v-if="allocationRows.length" size="small" :columns="allocationColumns" :data-source="allocationRows" :pagination="false" :scroll="{ x: 1300 }" row-key="sourceUuid" />
        <a-table size="small" :columns="planColumns" :data-source="changes" :pagination="{ pageSize: 5 }" :scroll="{ x: 820 }" row-key="rowKey">
          <template #expandedRowRender="{ record }">
            <a-alert v-if="record.action==='PRESERVE'" type="info" :message="$t('message.storage.config.diff.preserve')" />
            <a-table v-if="record.current || record.desired" size="small" :columns="differenceColumns" :data-source="configurationDifference(record.current, record.action==='PRESERVE' ? record.current : record.desired)" :pagination="false" row-key="field" />
          </template>
        </a-table>
        <a-form v-if="planPhase==='REVIEW'" layout="vertical">
          <template v-for="required in plan.requiredCredentials" :key="required.ruleUuid">
            <a-form-item v-for="field in required.fields" :key="required.ruleUuid+field" :label="required.principal + ' · ' + $t('label.storage.config.credential.' + field)">
              <a-input-password v-model:value="credentialValues[required.ruleUuid][field]" autocomplete="off" />
            </a-form-item>
          </template>
          <a-checkbox v-if="!lkgPlan && targetMode==='RESTORE_EXISTING' && plan.adIdentityRestoreRequiresMaintenance !== true && can('applyStorageServiceApprovedMaintenance')" v-model:checked="restoreMaintenance">{{ $t('message.storage.template.maintenance.confirm') }}</a-checkbox>
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
import { storageMutationGateMixin, requireStorageMutationAllowed } from '@/utils/storageRecoveryGate'
import { getAPI, postAPI } from '@/api'
import SHA from 'sha.js'
import { configurationIntents, configurationActor, configurationScope, ensureConfigurationActor, activeConfigurationIntent, beginConfigurationIntent, configurationCommandIsAsync, validConfigurationJobId, definitiveConfigurationRejection, trackConfigurationIntent, configurationDifference, validConfigurationStaticNetwork } from '@/utils/storageConfigurationJobs'
import { partialConfigurationClone, configurationCloneResume, configurationCloneResumeMapping, requireConfigurationCloneResumePlan, cloneResumeUnavailable } from '@/utils/storageConfigurationResume'
import { supportsStorageFormatting, diskProvisioningLabel } from '@/utils/storageDiskProvisioning'
import { requireAdServiceApproval } from '@/utils/storageAdIdentity'
import { CloudDownloadOutlined, UploadOutlined, DownloadOutlined, RollbackOutlined, ReloadOutlined } from '@ant-design/icons-vue'
const Sha256 = SHA.sha256

export default {
  mixins: [storageMutationGateMixin],
  name: 'StorageServiceConfiguration',
  components: { CloudDownloadOutlined, UploadOutlined, DownloadOutlined, RollbackOutlined, ReloadOutlined },
  emits: ['operation-updated'],
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, default: '' }, resource: { type: Object, required: true } },
  data: () => ({ disposed: false, rows: [], loading: false, readFailed: false, generation: 0, busy: '', error: '', backupDialog: false, includeAdIdentity: false, backupMaintenance: false, backupConfirmation: '', includeRuntime: true, retentionHours: 168, planRequestToken: 0, planTarget: null, cloneResume: null, cloneResumeUnavailable: false, plan: null, planPhase: 'MAPPING', planToken: '', resourceMapping: {}, listenerMapping: {}, listenerChoices: {}, lkgPlan: false, planning: false, volumeMapping: {}, newVolumeSpecs: {}, targetVolumes: [], credentialValues: {}, confirmation: '', restoreMaintenance: false, reviewedPlan: '', confirmFileExecute: false, targetMode: 'RESTORE_EXISTING', clone: { name: '', size: 20, filesystem: 'XFS', networkmode: 'DHCP', backingvolumemode: 'NEW' }, initialVolumeSource: '', plannedVolume: '', cloneRuntime: '', cloneOptionToken: 0, cloneOptionScope: null, cloneOfferingRows: [], cloneOfferingLoading: false, cloneOfferingError: false, cloneTemplateToken: 0, cloneTemplateScope: null, cloneTemplateBackingMode: null, cloneTemplateSelection: 'DEFAULT_SYSTEM', cloneTemplateRows: [], cloneTemplateLoading: false, cloneTemplateError: false, cloneOptions: { zones: [], networks: [], offerings: [], disks: [], pools: [], bundles: [], volumes: [], templates: [] } }),
  computed: {
    mappingLocked () { return this.planPhase === 'REVIEW' || !!this.cloneResume || this.cloneResumeUnavailable },
    configurationActor () { return configurationActor(this) },
    configurationIntent () { return configurationIntents[configurationScope(this)] || null },
    configurationLocked () { return !!this.busy || activeConfigurationIntent(this.configurationIntent) },
    differenceColumns () { return [{ title: this.$t('label.storage.config.diff.field'), dataIndex: 'field' }, { title: this.$t('label.storage.config.diff.current'), dataIndex: 'before' }, { title: this.$t('label.storage.config.diff.desired'), dataIndex: 'after' }] },
    listenerAddressRows () { return (this.plan?.listenerAddressRequirements || []).map(row => ({ ...row, choice: this.listenerChoices[row.sourceAddress] || (this.listenerMapping[row.sourceAddress] === 'PRIMARY' ? 'PRIMARY' : this.listenerMapping[row.sourceAddress] ? 'CUSTOM' : undefined), address: this.listenerMapping[row.sourceAddress] === 'PRIMARY' ? '' : this.listenerMapping[row.sourceAddress] || '' })) },
    resourceMappingRows () {
      if (!this.plan || this.targetMode !== 'RESTORE_EXISTING') return []
      const changes = [...this.plan.create, ...this.plan.update, ...this.plan.keep]
      return changes.map(row => {
        const targets = [...new Map([...changes.filter(item => item.kind === row.kind && item.current?.uuid).map(item => [item.current.uuid, item.current]), ...(this.plan.preserve || []).filter(item => item.kind === row.kind).map(item => [item.targetUuid, { ...(item.current || {}), ...item }])]).values()]
        const selected = this.resourceMapping[row.kind]?.[row.sourceUuid] || this.plan.resourceMappings?.[row.kind]?.[row.sourceUuid] || 'NEW'
        const options = targets.map(item => ({ value: item.uuid || item.targetUuid, label: (item.name || item.principal || item.protocol || item.uuid || item.targetUuid) + ' · ' + (item.uuid || item.targetUuid) }))
        if (!targets.some(item => (item.uuid || item.targetUuid) === row.sourceUuid)) options.unshift({ value: 'NEW', label: this.$t('label.storage.config.resource.create') })
        return { ...row, selected, options, key: row.kind + ':' + row.sourceUuid }
      })
    },
    cloneRequiresIdentityTemplate () {
      const metadata = this.planTarget?.metadata || {}
      return metadata.adIdentitySourceDescriptor !== undefined || metadata.adIdentityCoverage !== undefined || this.plan?.adIdentitySourceDescriptor !== undefined
    },
    ordinaryClonePrivateTemplate () { return !this.cloneRequiresIdentityTemplate && this.cloneTemplateRows.some(row => row.id === this.clone.templateid && row.templatetype === 'USER') },
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
      const volumes = new Set((this.plan?.sourceVolumeInventory || []).filter(volume => volume.type === 'DATADISK' && typeof volume.uuid === 'string' && volume.uuid).map(volume => volume.uuid))
      for (const row of this.changes) if (row.desired?.volumeUuid) volumes.add(row.desired.volumeUuid)
      return [...volumes]
    },
    changes () { return this.plan ? [...this.plan.create, ...this.plan.update, ...this.plan.keep, ...(this.plan.preserve || []).map(row => ({ ...row, action: 'PRESERVE' }))].map(row => ({ ...row, rowKey: [row.kind, row.action, row.sourceUuid || '', row.targetUuid || ''].join(':') })) : [] },
    initialSourceVolumeOptions () {
      const inventory = this.plan?.sourceVolumeInventory || []
      return this.sourceVolumes.map(uuid => {
        const volume = inventory.find(row => row.uuid === uuid) || {}
        return { value: uuid, label: [volume.name, uuid, volume.usage, volume.filesystemHint].filter(Boolean).join(' · '), usage: volume.usage }
      }).filter(row => row.usage !== 'BLOCK_RAW').sort((left, right) => ({ FILE: 0, UNUSED: 1 }[left.usage] ?? 2) - ({ FILE: 0, UNUSED: 1 }[right.usage] ?? 2))
    },
    initialSourceFilesystemHint () {
      const value = (this.plan?.sourceVolumeInventory || []).find(volume => volume.uuid === this.initialVolumeSource)
      const filesystem = typeof value?.filesystemHint === 'string' ? value.filesystemHint.toUpperCase() : ''
      return value?.usage === 'FILE' && ['XFS', 'EXT4'].includes(filesystem) ? filesystem : ''
    },
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
        { title: this.$t('label.storage.config.source.volume'), dataIndex: 'sourceUuid', width: 280 },
        { title: this.$t('label.storage.config.target.volume'), dataIndex: 'targetUuid', width: 260 }]
    }
  },
  watch: {
    configurationActor (value) { ensureConfigurationActor(value); this.rows = []; this.readFailed = false; this.error = ''; this.busy = ''; this.closeBackupDialog(); this.closePlan(); this.refresh() },
    instanceId () { this.generation++; this.rows = []; this.busy = ''; this.error = ''; this.closeBackupDialog(); this.closePlan(); this.refresh() },
    instanceName () { this.closeBackupDialog() },
    includeAdIdentity (value) { if (!value) { this.backupMaintenance = false; this.backupConfirmation = '' } },
    plan: { deep: true, handler () { if (this.reviewedPlan && this.reviewedPlan !== this.restoreReviewFingerprint()) this.restoreMaintenance = false } },
    planToken () { if (this.reviewedPlan && this.reviewedPlan !== this.restoreReviewFingerprint()) this.restoreMaintenance = false },
    'resource.account' () { this.clearCloneZoneOptions() },
    'resource.domainid' () { this.clearCloneZoneOptions() },
    'resource.projectid' () { this.clearCloneZoneOptions() },
    cloneActiveProject () { this.clearCloneZoneOptions() },
    'clone.validationartifactuuid' () { this.reviewedPlan = ''; this.planToken = ''; this.confirmation = '' },
    'clone.validationartifactsha256' () { this.reviewedPlan = ''; this.planToken = ''; this.confirmation = '' },
    'clone.templateid' () { this.restoreMaintenance = false; this.reviewedPlan = ''; this.planToken = ''; this.confirmation = '' },
    'clone.backingvolumemode' () {
      if (this.targetMode !== 'CREATE_NEW' || this.cloneRequiresIdentityTemplate || this.cloneResume || this.cloneResumeUnavailable) return
      this.editPlanMapping()
      if (this.cloneTemplateSelection === 'EXPLICIT') return this.loadOrdinaryCloneTemplates(true)
    },
    'planTarget.metadata': {
      deep: true,
      handler () {
        if (!this.planTarget || this.cloneResumeUnavailable) return
        if (this.cloneResume) {
          // Opening a recorded restore is hydration, not a change of the user's mapping.
          // Volatile receipts may change; the recorded target and allocation may not.
          try { requireConfigurationCloneResumePlan(this.cloneResume, this.planTarget.metadata) } catch (_) { this.invalidateCloneResume() }
          return
        }
        this.clearCloneZoneOptions()
      }
    }
  },
  mounted () { ensureConfigurationActor(this.configurationActor); this.refresh(); if (this.configurationIntent?.jobId && activeConfigurationIntent(this.configurationIntent)) this.resumeConfigurationJob() },
  beforeUnmount () { this.disposed = true; this.planRequestToken++; this.generation++; this.cloneOptionToken++; this.cloneOptionScope = null; this.cloneOfferingRows = []; if (this.clearCloneTemplates) this.clearCloneTemplates(); this.credentialValues = {} },
  methods: {
    can (api) { return api in this.$store.getters.apis },
    unwrap (value, api) {
      const body = value[api.toLowerCase() + 'response'] || value
      let result = body.storageserviceconfigartifact || body.storageserviceconfiguration || body
      if (body.storageserviceruntime) {
        const runtime = body.storageserviceruntime
        if (runtime.id !== this.instanceId || runtime.success !== true || runtime.status !== 'COMPLETE' || typeof runtime.resultjson !== 'string') throw new Error(this.$t('message.storage.config.failed'))
        result = JSON.parse(runtime.resultjson)
      }
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
    async mutation (api, parameters, review = this.plan || {}) {
      const instance = this.instanceId; const scope = configurationScope(this); const actor = configurationActor(this)
      requireStorageMutationAllowed(this, api)
      const intent = beginConfigurationIntent(this, api, parameters.artifactid, review)
      let value
      try {
        const pending = postAPI(api, { instanceid: instance, ...parameters }, { preserveOnFailure: true, backgroundJob: true })
        if (parameters.credentials !== undefined) parameters.credentials = ''
        value = await pending
      } catch (error) {
        if (configurationIntents[scope] === intent) intent.status = definitiveConfigurationRejection(error, api) ? 'failed' : 'unknown'
        throw new Error(this.$t(intent.status === 'unknown' ? 'message.storage.config.job.unknown' : 'message.storage.config.failed'))
      } finally { if (parameters.credentials !== undefined) parameters.credentials = '' }
      const body = value?.[api.toLowerCase() + 'response'] || value
      const job = body?.jobid
      if (!validConfigurationJobId(job)) {
        if (configurationCommandIsAsync(api)) {
          intent.status = 'unknown'
          throw new Error(this.$t('message.storage.config.job.unknown'))
        }
        const result = this.unwrap(value, api)
        if (!result || typeof result.id !== 'string' || !result.id || body?.errorcode) {
          intent.status = 'unknown'
          throw new Error(this.$t('message.storage.config.job.unknown'))
        }
        intent.status = 'complete'
        if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
        return result
      }
      intent.jobId = job; intent.status = 'running'
      const result = await trackConfigurationIntent(intent, () => configurationActor(this) === actor)
      if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
      if (result.jobstatus === 1 || result.jobstatus === 2) {
        if (['verifyStorageServiceConfiguration', 'applyStorageServiceConfigRestore', 'applyStorageServiceApprovedMaintenance', 'restoreStorageServiceLastKnownGood'].includes(api)) this.$emit('operation-updated', instance)
        if (result.jobstatus === 2) {
          try { await this.refresh() } catch (_) { this.readFailed = true }
          throw new Error(this.$t('message.storage.config.job.failed', { job: intent.jobId, code: intent.errorCode ?? '—' }))
        }
        return this.unwrap(result.jobresult, api)
      }
      throw new Error(this.$t('message.storage.config.job.unknown'))
    },
    async resumeConfigurationJob () {
      const intent = this.configurationIntent; const scope = configurationScope(this); const actor = configurationActor(this)
      if (!intent?.jobId || this.busy) return
      this.busy = 'TRACKING'; this.error = ''
      try {
        const result = await trackConfigurationIntent(intent, () => configurationActor(this) === actor)
        if ((scope !== configurationScope(this) || this.disposed)) return
        if (result.jobstatus === 1 || result.jobstatus === 2) {
          this.$emit('operation-updated', this.instanceId); await this.refresh()
          if (result.jobstatus === 2) this.error = this.$t('message.storage.config.job.failed', { job: intent.jobId, code: intent.errorCode ?? '—' })
        }
      } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
    },
    editPlanMapping () {
      this.planPhase = 'MAPPING'; this.planToken = ''; this.reviewedPlan = ''; this.confirmation = ''; this.credentialValues = {}; this.restoreMaintenance = false
    },
    setListenerMapping (source, choice) {
      this.listenerChoices[source] = choice; this.listenerMapping[source] = choice === 'PRIMARY' ? 'PRIMARY' : ''
      this.planPhase = 'MAPPING'; this.planToken = ''; this.reviewedPlan = ''; this.confirmation = ''; this.credentialValues = {}; this.restoreMaintenance = false
    },
    setListenerAddress (source, value) {
      this.listenerChoices[source] = 'CUSTOM'; this.listenerMapping[source] = value.trim()
      this.planPhase = 'MAPPING'; this.planToken = ''; this.reviewedPlan = ''; this.confirmation = ''; this.credentialValues = {}; this.restoreMaintenance = false
    },
    setResourceMapping (row, target) {
      this.resourceMapping[row.kind] = { ...(this.resourceMapping[row.kind] || {}), [row.sourceUuid]: target }
      this.planPhase = 'MAPPING'; this.planToken = ''; this.reviewedPlan = ''; this.confirmation = ''; this.credentialValues = {}; this.restoreMaintenance = false
    },
    configurationDifference,
    configurationBlockerLabel (blocker) {
      const protocol = typeof blocker === 'string' && /^PROTOCOL_MAPPING_REQUIRED:([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$/i.exec(blocker)
      if (protocol) return this.$t('label.protocol') + ' · ' + this.$t('label.storage.config.resource.mapping') + ' (' + this.$t('label.required') + '): ' + protocol[1] + '. ' + this.$t('message.error.select')
      return ['PRIMARY_LISTENER_REQUIRES_STATIC_ADDRESS', 'PRIMARY_LISTENER_MIX_REQUIRES_STATIC_ADDRESS'].includes(blocker)
        ? this.$t('message.storage.config.listener.static.required') : blocker
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
      const scope = configurationScope(this)
      this.busy = 'VERIFY'; this.error = ''
      try { await this.mutation('verifyStorageServiceConfiguration', {}); if ((scope === configurationScope(this) && !this.disposed)) await this.refresh() } catch (error) { if ((scope === configurationScope(this) && !this.disposed)) this.error = error.message } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
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
      const scope = configurationScope(this)
      if (this.busy) return
      const instance = this.instanceId; const name = this.instanceName
      let submitted = false
      this.error = ''
      try {
        const request = this.buildBackupRequest()
        this.backupDialog = false; this.busy = 'BACKUP'; submitted = true
        await this.mutation('createStorageServiceConfigBackup', request)
        if (instance !== this.instanceId || name !== this.instanceName) throw new Error(this.$t('message.storage.config.scope.changed'))
        if ((scope === configurationScope(this) && !this.disposed)) await this.refresh()
      } catch (error) { if ((scope === configurationScope(this) && !this.disposed)) this.error = error.message } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = ''; if ((scope === configurationScope(this) && !this.disposed)) { if (submitted) this.includeAdIdentity = false; this.backupMaintenance = false; this.backupConfirmation = '' } }
    },
    async download (row) {
      const instance = this.instanceId; const scope = configurationScope(this)
      this.busy = 'DOWNLOAD'; this.error = ''
      try {
        const issued = this.unwrap(await postAPI('downloadStorageServiceConfigBackup', { instanceid: instance, artifactid: row.id }, { preserveOnFailure: true }), 'downloadStorageServiceConfigBackup')
        if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
        const value = this.unwrap(await postAPI('downloadStorageServiceConfigBackup', { instanceid: instance, artifactid: row.id, downloadtoken: issued.downloadToken }, { preserveOnFailure: true }), 'downloadStorageServiceConfigBackup')
        if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
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
      } catch (error) { if ((scope === configurationScope(this) && !this.disposed)) this.error = error.message } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
    },
    async importFile (file) {
      if (this.configurationLocked) return false
      if (!file.size || file.size > 8 * 1024 * 1024 || !file.name.toLowerCase().endsWith('.zip')) { this.error = this.$t('message.storage.config.file.invalid'); return false }
      const instance = this.instanceId; const scope = configurationScope(this)
      const intent = beginConfigurationIntent(this, 'uploadStorageServiceConfigBackup', '')
      this.busy = 'UPLOAD'; this.error = ''
      try {
        const bytes = new Uint8Array(await file.arrayBuffer())
        const sha256 = new Sha256().update(bytes).digest('hex')
        const count = Math.ceil(bytes.length / (192 * 1024)); let artifact
        for (let index = 0; index < count; index++) {
          if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
          const chunk = bytes.slice(index * 192 * 1024, (index + 1) * 192 * 1024); let binary = ''
          for (const byte of chunk) binary += String.fromCharCode(byte)
          artifact = this.unwrap(await postAPI('uploadStorageServiceConfigBackup', { instanceid: instance, artifactid: artifact?.id, configurationpayload: btoa(binary), sha256, chunkindex: index, chunkcount: count }, { preserveOnFailure: true }), 'uploadStorageServiceConfigBackup')
        }
        if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
        if (!artifact?.id || artifact.state !== 'QUARANTINED') throw new Error(this.$t('message.storage.config.job.unknown'))
        intent.artifactId = artifact.id; intent.status = 'complete'
        await this.refresh()
      } catch (error) { intent.status = definitiveConfigurationRejection(error, 'uploadStorageServiceConfigBackup') ? 'failed' : 'unknown'; if ((scope === configurationScope(this) && !this.disposed)) this.error = this.$t('message.storage.config.job.unknown') } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
      return false
    },
    async deleteArtifact (row) {
      const scope = configurationScope(this)
      this.busy = 'DELETE'; this.error = ''
      try {
        await this.mutation(row.kind === 'BACKUP' ? 'deleteStorageServiceConfigBackup' : 'deleteStorageServiceConfigImport', { artifactid: row.id })
        if ((scope === configurationScope(this) && !this.disposed)) await this.refresh()
      } catch (error) { if ((scope === configurationScope(this) && !this.disposed)) this.error = error.message } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
    },
    async validateImport (row) {
      const scope = configurationScope(this)
      this.busy = 'VALIDATE'; this.error = ''
      try { await this.mutation('validateStorageServiceConfigImport', { artifactid: row.id }); if ((scope === configurationScope(this) && !this.disposed)) await this.refresh() } catch (error) { if ((scope === configurationScope(this) && !this.disposed)) this.error = error.message } finally { if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
    },
    async openPlan (row, lkg = false) {
      if (this.readFailed) { this.error = this.$t('message.storage.config.read.failed'); return }
      const instance = this.instanceId
      try {
        if (lkg) requireStorageMutationAllowed(this, 'restoreStorageServiceLastKnownGood')
        this.closePlan(); this.planTarget = row; this.lkgPlan = lkg; this.error = ''
        if (!lkg && partialConfigurationClone(row.metadata)) {
          this.targetMode = 'CREATE_NEW'
          try { this.cloneResume = configurationCloneResume(row.metadata) } catch (error) { this.cloneResumeUnavailable = true; throw error }
          const resume = this.cloneResume
          this.clone = JSON.parse(JSON.stringify(resume.requested.createNew))
          this.plan = JSON.parse(JSON.stringify(resume.plan))
          this.volumeMapping = { ...resume.requested.volumes }
          this.newVolumeSpecs = JSON.parse(JSON.stringify(resume.requested.newVolumes || {}))
          this.initialVolumeSource = resume.requested.initialVolumeSourceUuid
          this.cloneRuntime = resume.requested.runtimeBundleUuid
          this.listenerMapping = { ...resume.listenerAddresses }
          this.confirmFileExecute = resume.confirmFileExecute
          this.cloneTemplateSelection = Object.prototype.hasOwnProperty.call(this.clone, 'templateid') ? 'EXPLICIT' : 'DEFAULT_SYSTEM'
          await this.preparePlan()
          return
        }
        const volumes = (await getAPI('listVolumes', { virtualmachineid: this.resource.virtualmachineid }, { timeout: 15000, preserveOnFailure: true })).listvolumesresponse.volume || []
        if (instance !== this.instanceId || this.planTarget !== row) return
        this.targetVolumes = volumes.filter(volume => volume.type === 'DATADISK').map(volume => ({ value: volume.id, label: volume.name + ' · ' + volume.id }))
        await this.preparePlan()
      } catch (error) { this.error = error.message === cloneResumeUnavailable ? this.$t(cloneResumeUnavailable) : error.message }
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
    cloneIdentityTemplateScope () {
      const metadata = this.planTarget?.metadata || {}
      return JSON.stringify({ scope: this.cloneDiscoveryScope(), descriptor: metadata.adIdentitySourceDescriptor || this.plan?.adIdentitySourceDescriptor, coverage: metadata.adIdentityCoverage })
    },
    eligibleCloneIdentityTemplate (template) {
      const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
      return typeof template?.id === 'string' && uuid.test(template.id) && template.templatetype === 'SYSTEM' && template.ispublic === true &&
        template.isready === true && template.isdynamicallyscalable === true && template.hypervisor === 'KVM' &&
        template.arch === 'x86_64' && template.zoneid === this.clone.zoneid && template.details?.['storage.service.local.identity.seed.absent'] === 'true'
    },
    clearCloneTemplates (preserveSelection = false) {
      this.cloneTemplateToken++; this.cloneTemplateScope = null; this.cloneTemplateBackingMode = null; this.cloneTemplateRows = []; this.cloneTemplateLoading = false; this.cloneTemplateError = false
      this.cloneOptions.templates = []
      if (!preserveSelection) { delete this.clone.templateid; delete this.clone.validationartifactuuid; delete this.clone.validationartifactsha256 }
    },
    async loadCloneTemplates () {
      this.clearCloneTemplates()
      if (!this.clone.zoneid) return
      if (!this.cloneRequiresIdentityTemplate) return this.loadOrdinaryCloneTemplates()
      const token = ++this.cloneTemplateToken; const scope = this.cloneIdentityTemplateScope()
      this.cloneTemplateLoading = true
      const admin = ['Admin', 'DomainAdmin'].includes(this.$store?.getters?.userInfo?.roletype)
      const owner = this.resource?.projectid ? { projectid: this.resource.projectid } : { account: this.resource?.account, domainid: this.resource?.domainid }
      const parameters = { zoneid: this.clone.zoneid, hypervisor: 'KVM', system: true, templatefilter: admin ? 'all' : 'executable', ...owner }
      if (admin) parameters.listall = true
      try {
        if (!this.can('listTemplates')) throw new Error('unsupported')
        const response = await getAPI('listTemplates', parameters, { timeout: 15000, preserveOnFailure: true })
        if (token !== this.cloneTemplateToken || scope !== this.cloneIdentityTemplateScope() || this.targetMode !== 'CREATE_NEW') return
        this.cloneTemplateRows = (response.listtemplatesresponse?.template || []).filter(row => this.eligibleCloneIdentityTemplate(row))
        this.cloneOptions.templates = this.options(this.cloneTemplateRows); this.cloneTemplateScope = scope
      } catch (_) { if (token === this.cloneTemplateToken && scope === this.cloneIdentityTemplateScope()) this.cloneTemplateError = true } finally { if (token === this.cloneTemplateToken) this.cloneTemplateLoading = false }
    },
    eligibleOrdinaryCloneTemplate (template) {
      if (!template || template.isready !== true || template.isdynamicallyscalable !== true || template.hypervisor !== 'KVM' || template.arch !== 'x86_64' ||
          template.format !== 'QCOW2' || template.removed || (template.templatestate !== undefined && template.templatestate !== 'Active') || template.zoneid !== this.clone.zoneid || typeof template.checksum !== 'string' || !template.checksum.trim() ||
          !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(template.id)) return false
      if (template.templatetype === 'SYSTEM') return true
      return template.templatetype === 'USER' && template.ispublic === false && this.clone.backingvolumemode === 'NEW' &&
        (this.resource.projectid ? template.projectid === this.resource.projectid : template.account === this.resource.account && template.domainid === this.resource.domainid)
    },
    async loadOrdinaryCloneTemplates (preserveSelection = false) {
      this.planPhase = 'MAPPING'; this.reviewedPlan = ''; this.planToken = ''; this.confirmation = ''
      const selected = preserveSelection ? this.cloneTemplateRows.find(row => row.id === this.clone.templateid && this.eligibleOrdinaryCloneTemplate(row))?.id : undefined
      this.clearCloneTemplates()
      if (this.cloneTemplateSelection !== 'EXPLICIT') return
      const token = ++this.cloneTemplateToken; const scope = this.cloneDiscoveryScope(); const backingMode = this.clone.backingvolumemode
      this.cloneTemplateLoading = true
      const owner = this.resource?.projectid ? { projectid: this.resource.projectid } : { account: this.resource?.account, domainid: this.resource?.domainid }
      try {
        if (!this.can('listTemplates')) throw new Error('unsupported')
        const responses = await Promise.all([
          getAPI('listTemplates', { zoneid: this.clone.zoneid, hypervisor: 'KVM', templatefilter: 'executable', ...owner }, { preserveOnFailure: true, timeout: 15000 }),
          getAPI('listTemplates', { zoneid: this.clone.zoneid, hypervisor: 'KVM', templatefilter: 'all', templatetype: 'SYSTEM' }, { preserveOnFailure: true, timeout: 15000 })])
        if (token !== this.cloneTemplateToken || scope !== this.cloneDiscoveryScope() || backingMode !== this.clone.backingvolumemode || this.targetMode !== 'CREATE_NEW') return
        this.cloneTemplateRows = [...new Map(responses.flatMap(value => value.listtemplatesresponse?.template || []).filter(row => this.eligibleOrdinaryCloneTemplate(row)).map(row => [row.id, row])).values()]
        this.cloneOptions.templates = this.options(this.cloneTemplateRows); this.cloneTemplateScope = scope; this.cloneTemplateBackingMode = backingMode
        if (selected && this.cloneTemplateRows.some(row => row.id === selected)) this.clone.templateid = selected
      } catch (_) { if (token === this.cloneTemplateToken) this.cloneTemplateError = true } finally { if (token === this.cloneTemplateToken) this.cloneTemplateLoading = false }
    },
    assertOrdinaryCloneTemplate (reviewedPlan) {
      if (this.cloneRequiresIdentityTemplate || this.cloneTemplateSelection !== 'EXPLICIT') return
      const row = this.cloneTemplateRows.find(value => value.id === this.clone.templateid)
      if (this.cloneTemplateLoading || this.cloneTemplateError || this.cloneTemplateScope !== this.cloneDiscoveryScope() || this.cloneTemplateBackingMode !== this.clone.backingvolumemode || !this.eligibleOrdinaryCloneTemplate(row) ||
          (reviewedPlan && reviewedPlan.createNew?.templateid !== row.id)) throw new Error(this.$t('message.storage.config.clone.template.required'))
      if (row.templatetype === 'USER' && (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(this.clone.validationartifactuuid || '') ||
          !/^[a-f0-9]{64}$/.test(this.clone.validationartifactsha256 || '') ||
          (reviewedPlan && (reviewedPlan.createNew?.validationartifactuuid !== this.clone.validationartifactuuid || reviewedPlan.createNew?.validationartifactsha256 !== this.clone.validationartifactsha256)))) throw new Error(this.$t('message.storage.config.clone.template.approval.required'))
    },
    assertCloneIdentityTemplate (reviewedPlan) {
      if (!this.cloneRequiresIdentityTemplate) return
      const metadata = this.planTarget?.metadata || {}
      const descriptor = metadata.adIdentitySourceDescriptor || reviewedPlan?.adIdentitySourceDescriptor
      if (!this.validIdentityDescriptor(descriptor) || (!reviewedPlan && metadata.adIdentityCoverage !== 'VERIFIED_ENCRYPTED_FULL_IDENTITY') ||
        (reviewedPlan && (!this.validIdentityDescriptor(reviewedPlan.adIdentitySourceDescriptor) || Object.keys(descriptor).some(key => descriptor[key] !== reviewedPlan.adIdentitySourceDescriptor[key])))) throw new Error(this.$t('message.storage.service.ad.receipt.unverified'))
      const template = this.cloneTemplateRows.find(row => row.id === this.clone.templateid)
      if (this.cloneTemplateLoading || this.cloneTemplateError || this.cloneTemplateScope !== this.cloneIdentityTemplateScope() || !this.eligibleCloneIdentityTemplate(template) ||
        (reviewedPlan && reviewedPlan.createNew?.templateid !== template.id)) throw new Error(this.$t('message.storage.config.clone.identity.template.required'))
    },
    invalidateCloneResume () {
      this.planRequestToken++; this.cloneResumeUnavailable = true; this.planPhase = 'MAPPING'; this.planToken = ''; this.reviewedPlan = ''
      this.restoreMaintenance = false; this.confirmation = ''; this.credentialValues = {}; this.planning = false
      this.error = this.$t(cloneResumeUnavailable)
    },
    clearCloneZoneOptions () {
      if (this.clearCloneTemplates) this.clearCloneTemplates(!!this.cloneResume)
      this.cloneOptionToken++; this.cloneOptionScope = null; this.cloneOfferingRows = []; this.cloneOfferingLoading = false; this.cloneOfferingError = false
      for (const key of ['networks', 'offerings', 'disks', 'pools', 'volumes']) this.cloneOptions[key] = []
      if (this.cloneResume) { this.invalidateCloneResume(); return }
      for (const key of ['networkid', 'storageid', 'diskofferingid', 'serviceofferingid', 'existingvolumeid']) this.clone[key] = undefined
      this.volumeMapping = {}; this.newVolumeSpecs = {}; this.listenerMapping = {}; this.listenerChoices = {}; this.restoreMaintenance = false; this.reviewedPlan = ''; this.planToken = ''; this.confirmation = ''
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
      if (this.cloneRequiresIdentityTemplate || this.cloneTemplateSelection === 'EXPLICIT') await this.loadCloneTemplates()
    },
    selectInitialVolume (uuid) {
      this.initialVolumeSource = uuid
      const volume = (this.plan?.sourceVolumeInventory || []).find(row => row.uuid === uuid)
      const filesystem = typeof volume?.filesystemHint === 'string' ? volume.filesystemHint.toUpperCase() : ''
      if (volume?.usage === 'FILE' && ['XFS', 'EXT4'].includes(filesystem)) this.clone.filesystem = filesystem
      this.editPlanMapping()
    },
    setVolumeMapping (source) {
      this.planPhase = 'MAPPING'
      if (this.volumeMapping[source] === 'NEW') this.newVolumeSpecs[source] = this.newVolumeSpecs[source] || { dataPolicy: 'PRESERVE' }
      else delete this.newVolumeSpecs[source]
      this.planToken = ''; this.confirmation = ''; this.restoreMaintenance = false; this.reviewedPlan = ''
    },
    async preparePlan () {
      if (this.planning || activeConfigurationIntent(configurationIntents[configurationScope(this)])) return
      const target = this.planTarget; const instance = this.instanceId
      const request = this.planRequestToken = (this.planRequestToken || 0) + 1
      this.planning = true; this.error = ''; this.restoreMaintenance = false; this.reviewedPlan = ''
      try {
        if (this.lkgPlan) requireStorageMutationAllowed(this, 'restoreStorageServiceLastKnownGood')
        const api = this.lkgPlan ? 'planStorageServiceLastKnownGoodRestore' : 'planStorageServiceConfigRestore'
        let mappings = { volumes: { ...this.volumeMapping }, confirmFileExecute: this.confirmFileExecute }
        if (this.cloneResumeUnavailable) throw new Error(cloneResumeUnavailable)
        if (this.cloneResume) {
          if (this.targetMode !== 'CREATE_NEW' || this.lkgPlan) throw new Error(cloneResumeUnavailable)
          this.planToken = ''
          mappings = configurationCloneResumeMapping(this.cloneResume)
        } else {
          if (Object.keys(this.listenerMapping || {}).length) mappings.listenerAddresses = Object.fromEntries(Object.entries(this.listenerMapping).filter(([, value]) => typeof value === 'string' && value.length))
          if (this.targetMode === 'RESTORE_EXISTING') for (const [kind, values] of Object.entries(this.resourceMapping || {})) mappings[kind] = Object.fromEntries(Object.entries(values).filter(([, value]) => value !== 'NEW'))
          if (this.targetMode === 'CREATE_NEW') {
            if (!this.initialVolumeSource || !this.cloneRuntime) throw new Error(this.$t('message.storage.config.clone.required'))
            if ((this.plan?.sourceVolumeInventory || []).some(row => row.uuid === this.initialVolumeSource && row.usage === 'BLOCK_RAW')) throw new Error(this.$t('message.storage.config.clone.initial.raw'))
            this.assertCloneOffering()
            if (this.cloneRequiresIdentityTemplate) this.assertCloneIdentityTemplate()
            else if (this.cloneTemplateSelection === 'EXPLICIT') this.assertOrdinaryCloneTemplate()
            const existing = this.clone.backingvolumemode === 'EXISTING'
            if (existing && !this.clone.existingvolumeid) throw new Error(this.$t('message.storage.config.clone.required'))
            mappings.volumes[this.initialVolumeSource] = existing ? this.clone.existingvolumeid : 'NEW'
            mappings.createNew = { ...this.clone, backingvolumemode: existing ? 'EXISTING' : 'NEW' }
            if (mappings.createNew.networkmode === 'STATIC' && !validConfigurationStaticNetwork(mappings.createNew)) throw new Error(this.$t('message.sharedfs.static.ip.cidr.invalid'))
            if (mappings.createNew.networkmode !== 'STATIC') for (const field of ['ipcidr', 'gateway', 'dns1', 'dns2']) delete mappings.createNew[field]
            if (!this.cloneRequiresIdentityTemplate && this.cloneTemplateSelection !== 'EXPLICIT') { delete mappings.createNew.templateid; delete mappings.createNew.validationartifactuuid; delete mappings.createNew.validationartifactsha256 }
            if (!this.ordinaryClonePrivateTemplate) { delete mappings.createNew.validationartifactuuid; delete mappings.createNew.validationartifactsha256 }
            if (existing) {
              delete mappings.createNew.diskofferingid; delete mappings.createNew.size; delete mappings.createNew.storageid
            } else delete mappings.createNew.existingvolumeid
            const specifications = Object.fromEntries(Object.entries(this.volumeMapping).filter(([source, target]) => source !== this.initialVolumeSource && target === 'NEW').map(([source]) => [source, { ...(this.newVolumeSpecs?.[source] || {}), dataPolicy: 'PRESERVE' }]))
            if (Object.keys(specifications).length) mappings.newVolumes = specifications
            mappings.initialVolumeSourceUuid = this.initialVolumeSource; mappings.runtimeBundleUuid = this.cloneRuntime
          }
        }
        const mode = this.targetMode
        const cloneScope = mode === 'CREATE_NEW' && this.cloneRequiresIdentityTemplate ? this.cloneIdentityTemplateScope() : null
        const selectedTemplate = this.clone?.templateid
        const result = await this.mutation(api, { artifactid: this.planTarget.id, targetmode: mode, mapping: JSON.stringify(mappings) })
        if (request !== this.planRequestToken || target !== this.planTarget || instance !== this.instanceId || this.cloneResumeUnavailable) return
        if (mode !== this.targetMode || (mode === 'CREATE_NEW' && ((cloneScope !== null && cloneScope !== this.cloneIdentityTemplateScope()) || selectedTemplate !== this.clone?.templateid))) throw new Error(this.$t('message.storage.config.scope.changed'))
        if (this.cloneResume) requireConfigurationCloneResumePlan(this.cloneResume, result.metadata)
        this.plan = result.metadata.plan; this.planToken = result.planToken || ''
        this.listenerMapping = { ...(this.plan.listenerAddressMappings || {}), ...(this.listenerMapping || {}) }
        if (this.targetMode === 'RESTORE_EXISTING') this.resourceMapping = Object.fromEntries([...new Set([...this.plan.create, ...this.plan.update, ...this.plan.keep].map(row => row.kind))].map(kind => [kind, { ...(this.plan.resourceMappings?.[kind] || {}), ...(this.resourceMapping?.[kind] || {}) }]))
        this.planPhase = 'MAPPING'
        if (this.targetMode === 'CREATE_NEW' && this.cloneRequiresIdentityTemplate && !this.cloneResume) {
          if (this.cloneTemplateScope !== this.cloneIdentityTemplateScope()) await this.loadCloneTemplates()
          this.assertCloneIdentityTemplate(this.plan)
        }
        this.planPhase = this.plan.blockers.length ? 'MAPPING' : 'REVIEW'
        this.reviewedPlan = JSON.stringify({ instance: this.instanceId, artifact: this.planTarget.id, token: this.planToken, plan: this.plan })
        this.credentialValues = Object.fromEntries(this.plan.requiredCredentials.map(row => [row.ruleUuid, {}]))
      } catch (error) { if (request === this.planRequestToken && target === this.planTarget && instance === this.instanceId) this.error = error.message === cloneResumeUnavailable ? this.$t(cloneResumeUnavailable) : error.message } finally { if (request === this.planRequestToken && target === this.planTarget && instance === this.instanceId) this.planning = false }
    },
    restoreReviewFingerprint () {
      return JSON.stringify({ instance: this.instanceId, artifact: this.planTarget?.id, token: this.planToken, plan: this.plan })
    },
    async applyPlan () {
      if (this.cloneResumeUnavailable) { this.error = this.$t(cloneResumeUnavailable); return }
      const requiresIdentity = this.plan?.adIdentityRestoreRequiresMaintenance === true
      if (!this.plan || this.confirmation !== this.plan.targetName || typeof this.planToken !== 'string' || !this.planToken) { this.error = this.$t('message.storage.config.confirmation.required'); return }
      if (!this.reviewedPlan || this.reviewedPlan !== this.restoreReviewFingerprint()) { this.restoreMaintenance = false; this.error = this.$t('message.storage.config.scope.changed'); return }
      const approvedMaintenance = !this.lkgPlan && this.targetMode === 'RESTORE_EXISTING' && !requiresIdentity && this.restoreMaintenance === true
      const api = this.lkgPlan ? 'restoreStorageServiceLastKnownGood' : approvedMaintenance ? 'applyStorageServiceApprovedMaintenance' : 'applyStorageServiceConfigRestore'
      if (!this.can(api)) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.setup.api.missing.with.name', { api }); return }
      if (approvedMaintenance && !this.$getApiParams?.(api)?.maintenancewindow) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.setup.api.missing.with.name', { api }); return }
      if ((this.plan.adIdentityRestoreRequiresMaintenance !== undefined && typeof this.plan.adIdentityRestoreRequiresMaintenance !== 'boolean') ||
        (this.plan.adIdentitySourceDescriptor !== undefined && !requiresIdentity)) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.ad.receipt.unverified'); return }
      if (requiresIdentity) {
        if (!this.validIdentityDescriptor(this.plan.adIdentitySourceDescriptor) || typeof this.plan.artifactSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(this.plan.artifactSha256)) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.ad.receipt.unverified'); return }
        if (!this.$getApiParams?.(api)?.maintenancewindow || this.restoreMaintenance !== true) { this.restoreMaintenance = false; this.error = this.$t('message.storage.service.ad.maintenance.required'); return }
      }
      if (this.targetMode === 'CREATE_NEW') {
        try {
          if (this.cloneResume) requireConfigurationCloneResumePlan(this.cloneResume, { ...this.planTarget.metadata, plan: this.plan })
          else { this.assertCloneIdentityTemplate(this.plan); if (!this.cloneRequiresIdentityTemplate && this.cloneTemplateSelection === 'EXPLICIT') this.assertOrdinaryCloneTemplate(this.plan) }
        } catch (error) { this.restoreMaintenance = false; this.error = error.message === cloneResumeUnavailable ? this.$t(cloneResumeUnavailable) : error.message; return }
      }
      const parameters = { artifactid: this.planTarget.id, plantoken: this.planToken, confirmation: this.confirmation, credentials: JSON.stringify(this.credentialValues) }
      if (requiresIdentity || approvedMaintenance) parameters.maintenancewindow = true
      const scope = configurationScope(this)
      const review = { targetMode: this.plan.targetMode || this.targetMode, create: { length: this.plan.create?.length || 0 }, update: { length: this.plan.update?.length || 0 }, keep: { length: this.plan.keep?.length || 0 }, preserve: { length: this.plan.preserve?.length || 0 } }
      this.closePlan(); this.busy = 'RESTORE'; this.error = ''
      try {
        await this.mutation(api, parameters, review)
        if ((scope !== configurationScope(this) || this.disposed)) throw new Error(this.$t('message.storage.config.scope.changed'))
        await this.refresh()
      } catch (error) { if ((scope === configurationScope(this) && !this.disposed)) this.error = error?.message || this.$t('message.storage.config.failed') } finally { parameters.credentials = ''; if ((scope === configurationScope(this) && !this.disposed)) this.busy = '' }
    },
    closePlan () { this.planRequestToken = (this.planRequestToken || 0) + 1; if (this.clearCloneTemplates) this.clearCloneTemplates(); this.cloneOptionToken++; this.cloneOptionScope = null; this.cloneOfferingRows = []; this.cloneOfferingLoading = false; this.cloneOfferingError = false; this.planTarget = null; this.cloneResume = null; this.cloneResumeUnavailable = false; this.plan = null; this.planToken = ''; this.restoreMaintenance = false; this.reviewedPlan = ''; this.planPhase = 'MAPPING'; this.resourceMapping = {}; this.listenerMapping = {}; this.listenerChoices = {}; this.volumeMapping = {}; this.newVolumeSpecs = {}; this.credentialValues = {}; this.confirmation = ''; this.lkgPlan = false; this.planning = false; this.confirmFileExecute = false; this.targetMode = 'RESTORE_EXISTING'; this.initialVolumeSource = ''; this.plannedVolume = ''; this.cloneRuntime = ''; this.cloneTemplateSelection = 'DEFAULT_SYSTEM'; this.clone = { name: '', size: 20, filesystem: 'XFS', networkmode: 'DHCP', backingvolumemode: 'NEW' } }
  }
}
</script>
<style scoped>
.storage-configuration { margin-top: 20px; }
.storage-configuration .ant-space, .storage-configuration .ant-alert { margin-bottom: 16px; }
</style>
