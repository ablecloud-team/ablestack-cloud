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
  <div class="staging-management">
    <div class="staging-header">
      <p class="staging-purpose">{{ $t(readOnly ? 'message.backup.image.purpose' : 'message.backup.staging.purpose') }}</p>
      <div class="staging-toolbar">
        <a-select v-if="!readOnly" v-model:value="operation" :disabled="busy || loading" style="width: 140px">
          <a-select-option value="BACKUP">{{ $t('label.backup') }}</a-select-option>
          <a-select-option value="RESTORE">{{ $t('label.restore') }}</a-select-option>
        </a-select>
        <a-select
          v-if="operation === 'RESTORE' && info?.restoreattempt?.length"
          v-model:value="selectedRestoreJobId"
          :disabled="busy || loading"
          style="max-width: 100%; min-width: 280px"
          @change="changeRestoreAttempt">
          <a-select-option v-for="attempt in info.restoreattempt" :key="attempt.stagingjobid" :value="attempt.current ? '' : attempt.stagingjobid">
            {{ $t(attempt.current ? 'label.backup.staging.current.attempt' : 'label.backup.staging.past.attempt') }} · {{ attempt.createdat ? $toLocaleDate(attempt.createdat) : attempt.stagingjobid }} · {{ attempt.hostname || $t('label.backup.staging.not.recorded') }}
          </a-select-option>
        </a-select>
        <a-button :loading="loading" :disabled="busy" @click="load">{{ $t('label.backup.staging.refresh') }}</a-button>
        <a-tooltip :title="$t('message.backup.staging.recheck')">
          <a-button v-if="canManage" :disabled="!canMutate || busy || loading" @click="run('RECHECK')">{{ $t('label.backup.staging.recheck') }}</a-button>
        </a-tooltip>
      </div>
    </div>
    <a-alert
      v-if="error"
      :type="isReconciling ? 'info' : 'warning'"
      show-icon
      :message="errorMessage"
      :description="isReconciling ? $t('message.backup.staging.reconciling') : undefined"
      class="staging-alert" />
    <a-alert
      v-if="resource.externalrestorevalidation"
      :type="resource.externalrestorevalidation.state === 'SUBMITTED' ? 'info' : 'warning'"
      show-icon
      :message="$t('label.backup.external.restore.job') + ': ' + resource.externalrestorevalidation.jobid + ' · ' + resource.externalrestorevalidation.state"
      :description="$t('message.backup.external.selection.' + resource.externalrestorevalidation.code)"
      class="staging-alert" />
    <a-alert v-if="isExpired && !info" type="warning" show-icon :message="$t('state.expired')" :description="$t('message.backup.catalog.expired')" class="staging-alert" />
    <a-alert
      v-if="!loading && !error && !info"
      type="info"
      show-icon
      :message="$t(readOnly ? 'message.backup.image.no.details' : 'message.backup.staging.no.details')"
      class="staging-alert" />
    <a-descriptions v-if="!loading && !info && restoreEntryPath" bordered :column="1" size="small" class="staging-catalog">
      <a-descriptions-item :label="$t('label.backup.image.restore.entry')">
        {{ restoreEntryPath }}
        <small>{{ $t('message.backup.netbackup.restore.entry') }}</small>
      </a-descriptions-item>
    </a-descriptions>
    <template v-if="info">
      <a-alert
        v-if="displaySituation && displaySituation.type === 'warning'"
        :type="displaySituation.type"
        show-icon
        :message="$t(displaySituation.title)"
        :description="$t(displaySituation.description)"
        class="staging-alert" />
      <div v-else-if="displaySituation && displaySituation.title !== 'message.backup.staging.inspect.title'" class="staging-status">
        <a-tag :color="displaySituation.type === 'success' ? 'green' : undefined">{{ $t(displaySituation.title) }}</a-tag>
        <span>{{ $t(displaySituation.description) }}</span>
      </div>
      <a-descriptions :title="$t('label.backup.inspection.basic')" bordered :column="1" size="small" class="staging-basics">
        <a-descriptions-item :label="$t('label.backup.image.logical.id')">{{ info.id || resource.id }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.type')">{{ resource.type || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vm')">{{ info.vmname || resource.vmname || '—' }}<span v-if="info.timestamp"> · {{ info.timestamp }}</span></a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE' && info.targetvmname" :label="$t('label.backup.staging.restore.target')">{{ info.targetvmname }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.staging.worker.host')">{{ info.hostname || $t('label.backup.staging.not.confirmed') }}</a-descriptions-item>
        <a-descriptions-item v-if="info.destination" :label="$t('label.backup.staging.destination')">
          {{ info.destination }}
          <small>{{ $t('message.backup.inspection.path') }}</small>
        </a-descriptions-item>
        <a-descriptions-item v-if="endedNetBackup && resource.backupjobdetails" :label="$t('label.backup.staging.end.reason')">{{ resource.backupjobdetails }}</a-descriptions-item>
      </a-descriptions>
      <a-collapse :default-active-key="readOnly ? ['images'] : ['staging']" class="staging-details">
        <a-collapse-panel v-for="section in inspectionSections" :key="section" :header="$t(section === 'images' ? 'label.backup.inspection.images' : 'label.backup.inspection.staging')">
          <template v-if="section === 'staging'">
            <p>{{ $t('message.backup.inspection.staging') }}</p>
            <a-descriptions bordered :column="1" size="small" class="staging-execution">
              <a-descriptions-item :label="$t('label.operation.status')">{{ info.hoststate || 'UNKNOWN' }}</a-descriptions-item>
              <a-descriptions-item v-if="info.step" :label="$t('label.step')">{{ info.step }}</a-descriptions-item>
              <a-descriptions-item v-if="info.volumecount" :label="$t('label.backup.staging.volume.progress')">{{ info.volumeindex || '—' }} / {{ info.volumecount }}</a-descriptions-item>
              <a-descriptions-item v-if="info.stagingqueue" :label="$t('label.backup.staging.admission.state')">{{ info.stagingqueue.state }}<div v-if="info.stagingqueue.reason">{{ info.stagingqueue.reason }}</div></a-descriptions-item>
              <a-descriptions-item v-if="info.cleanupstate" :label="$t('label.backup.staging.temporary.cleanup')">
                {{ info.cleanupstate === 'NONE' ? $t('label.backup.staging.cleanup.none') : info.cleanupstate }}
                <div v-if="info.cleanupreason">{{ cleanupReason }}</div>
                <small v-if="successfulBackup">{{ $t('message.backup.staging.metadata.persisted') }}</small>
              </a-descriptions-item>
              <a-descriptions-item v-if="isExpired && operation === 'BACKUP' && resource.catalogcleanupafter" :label="$t('label.backup.catalog.cleanup.after')">{{ $toLocaleDate(resource.catalogcleanupafter) }}</a-descriptions-item>
              <a-descriptions-item v-if="info.jobcleanupstate" :label="$t('label.backup.staging.job.cleanup')">{{ info.jobcleanupstate }}<div v-if="info.jobcleanupreason">{{ info.jobcleanupreason }}</div></a-descriptions-item>
              <a-descriptions-item v-if="operation === 'RESTORE' && info.vmrestore" :label="$t('label.backup.vm.restore.result')">{{ info.vmrestore.outcome }} · {{ info.vmrestore.phase }}</a-descriptions-item>
              <a-descriptions-item v-if="operation === 'BACKUP' && info.sourcecleanupstate && info.sourcecleanupstate !== 'NONE'" :label="$t(sourceCleanupLabel)">
                {{ info.sourcecleanupstate }}
                <div v-if="info.sourcecleanupreason">{{ info.sourcecleanupreason }}</div>
              </a-descriptions-item>
            </a-descriptions>
            <a-alert v-if="diagnosticErrors" type="warning" show-icon :message="diagnosticErrors" class="staging-alert" />
            <div v-if="canCleanup" class="staging-actions">
              <p>{{ $t('message.backup.staging.cleanup') }}</p>
              <a-button :disabled="busy || loading" @click="run('RETRY_CLEANUP')">{{ $t('label.backup.staging.retry.cleanup') }}</a-button>
            </div>
            <a-collapse v-if="canMutate && unresolvedArtifacts.length" class="staging-details">
              <a-collapse-panel key="link" :header="$t('label.backup.staging.manual.link')">
                <a-alert type="warning" show-icon :message="linkGuide" class="staging-alert" />
                <a-table
                  :columns="columns.slice(0, 3)"
                  :data-source="unresolvedArtifacts"
                  :row-key="row => row.path"
                  :pagination="false"
                  :row-selection="rowSelection"
                  :scroll="{ x: 900 }"
                  size="small"
                  class="staging-artifacts">
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'volume'"><div>{{ record.metadata ? $t('label.metadata') : record.volumeid }}</div><small>{{ record.path }}</small></template>
                    <template v-else-if="column.key === 'job'">{{ record.externaljobid || 'UNCONFIRMED' }}</template>
                    <template v-else>{{ record.restorestate || 'UNCONFIRMED' }}</template>
                  </template>
                </a-table>
                <a-form layout="vertical">
                  <a-form-item :label="$t('label.backup.staging.external.job.id')" :extra="$t('message.backup.staging.job.id')">
                    <a-input v-model:value="externalJobId" :disabled="busy || loading" />
                  </a-form-item>
                  <a-button :disabled="busy || loading || !selected || !externalJobId.trim()" @click="run('LINK_JOB')">{{ $t('label.backup.staging.connect.job') }}</a-button>
                </a-form>
              </a-collapse-panel>
            </a-collapse>
          </template>
          <template v-else>
            <p>{{ $t('message.backup.image.saved.references') }}</p>
            <a-descriptions bordered :column="1" size="small" class="staging-catalog">
              <a-descriptions-item :label="$t('label.backup.catalog.state')">{{ resource.catalogstate || 'UNKNOWN' }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.backup.catalog.checked')">{{ resource.catalogchecked ? $toLocaleDate(resource.catalogchecked) : $t('label.backup.staging.file.not.checked') }}</a-descriptions-item>
              <a-descriptions-item v-if="info.retentionstate" :label="$t('label.backup.retention.protection')">
                {{ $t(retentionLabel) }}
                <div v-if="info.retentionuntil">{{ $t('label.backup.retention.until') }}: {{ info.retentionuntil }}</div>
                <div v-if="retentionHistorical">{{ $t('message.backup.retention.historical') }}</div>
                <div v-else-if="info.retentionstate !== 'COMPLETED'">{{ $t('message.backup.retention.pending') }}</div>
                <div v-if="info.retentionreason">{{ info.retentionreason }}</div>
              </a-descriptions-item>
              <a-descriptions-item v-if="isExpired && resource.catalogexpiredsince" :label="$t('label.backup.catalog.expired.since')">{{ $toLocaleDate(resource.catalogexpiredsince) }}</a-descriptions-item>
              <a-descriptions-item v-if="restoreEntryPath" :label="$t('label.backup.image.restore.entry')">
                {{ restoreEntryPath }}
                <small>{{ $t('message.backup.netbackup.restore.entry') }}</small>
              </a-descriptions-item>
            </a-descriptions>
            <p v-if="info.artifact?.length" class="staging-files-guide">{{ $t('message.backup.staging.files') }}</p>
            <a-table
              v-if="info.artifact?.length"
              :columns="columns"
              :data-source="info.artifact"
              :row-key="row => [row.backupid, row.volumeid, row.path].join(':')"
              :pagination="{ pageSize: 20, hideOnSinglePage: true }"
              :scroll="{ x: 1000 }"
              size="small">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'volume'">
                  <template v-if="readOnly">
                    <div>{{ imageVolumeLabel(record) }}</div>
                    <small v-if="!record.metadata">{{ record.volumeid }}</small>
                    <a-tag :color="record.owned ? 'blue' : undefined">{{ $t(record.owned ? 'label.backup.image.owned' : 'label.backup.image.inherited') }}</a-tag>
                  </template>
                  <div v-else>{{ record.metadata ? $t('label.metadata') : record.volumeid }}</div>
                  <small>{{ record.path }}</small>
                  <small v-if="record.destination">{{ $t('label.backup.staging.destination') }}: {{ record.destination }}</small>
                  <small v-if="!record.owned">{{ $t('label.backup.staging.parent.backup') }}: {{ record.backupid }}</small>
                </template>
                <template v-else-if="column.key === 'job'">
                  <div><span v-if="readOnly">{{ $t(/^\d+$/.test(String(record.externaljobid || '')) ? 'label.backup.image.netbackup.job.id' : 'label.backup.image.transfer.reference') }}: </span>{{ record.externaljobid || (record.submissionpending ? 'UNCONFIRMED' : '—') }}</div>
                  <small v-if="record.externalid">{{ $t('label.backup.staging.catalog.reference') }}: {{ record.externalid }}</small>
                  <small v-if="record.backuptime">{{ backupTime(record.backuptime) }}</small>
                </template>
                <template v-else-if="column.key === 'state'">
                  {{ record.restorestate || (record.completed ? 'COMPLETED' : record.terminal ? 'TERMINAL' : record.submissionpending ? 'UNCONFIRMED' : record.externaljobid ? 'UNKNOWN' : 'NOT_STARTED') }}
                  <small v-if="record.failure">{{ record.failure }}</small><small v-if="record.queryerror">{{ record.queryerror }}</small>
                </template>
                <template v-else-if="column.key === 'availability'">
                  <a-tag :color="availabilityColor(record)">{{ availabilityLabel(record) }}</a-tag>
                  <small v-if="record.availabilityerror" class="staging-error">{{ record.availabilityerror }}</small>
                  <small v-if="record.availabilitycheckedat">{{ $toLocaleDate(record.availabilitycheckedat) }}</small>
                </template>
              </template>
            </a-table>
            <p v-else class="staging-files-guide">{{ $t('message.backup.image.no.artifacts') }}</p>
          </template>
        </a-collapse-panel>
      </a-collapse>
    </template>
    <div class="staging-footer"><a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.close') }}</a-button></div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'

export default {
  name: 'BackupStagingManagement',
  props: {
    resource: { type: Object, required: true },
    readOnly: { type: Boolean, default: false },
    inspectionApi: { type: String, default: 'getBackupStagingInfo' }
  },
  emits: ['close-action', 'refresh'],
  data () {
    return { operation: this.readOnly ? 'BACKUP' : this.resource.stagingqueue?.operation || 'BACKUP', selectedRestoreJobId: '', info: null, selectedKeys: [], externalJobId: '', loading: false, busy: false, error: '', requestToken: 0, disposed: false }
  },
  computed: {
    inspectionSections () { return this.readOnly ? ['images', 'staging'] : ['staging', 'images'] },
    restoreEntryPath () { return !this.isExpired && this.resource.catalogstate !== 'PARTIAL' ? this.resource.externalrestoreentrypath : '' },
    displaySituation () {
      if (!this.readOnly || this.deletionPending) return this.situation
      if (this.isExpired) return { type: 'warning', title: 'state.expired', description: 'message.backup.image.expired' }
      if (this.endedNetBackup) return { type: 'warning', title: 'message.backup.staging.backup.ended.title', description: 'message.backup.image.ended' }
      if (this.info?.retentionstate && this.info.retentionstate !== 'COMPLETED') {
        return { type: 'warning', title: 'label.backup.retention.pending', description: 'message.backup.retention.pending' }
      }
      if (this.availabilityErrors.length) {
        return { type: 'warning', title: 'message.backup.staging.query.failed.title', description: 'message.backup.image.query.failed' }
      }
      return null
    },
    isExpired () {
      return String(this.resource.status || '').toLowerCase() === 'expired' || this.resource.catalogstate === 'EXPIRED'
    },
    netBackupInspection () {
      return this.operation === 'BACKUP' && String(this.info?.provider || this.resource.provider || '').toLowerCase() === 'ablestack-netbackup'
    },
    endedNetBackup () {
      return this.netBackupInspection && ['failed', 'canceled', 'error'].includes(String(this.resource.status || '').toLowerCase())
    },
    retentionHistorical () { return this.netBackupInspection && (this.endedNetBackup || this.isExpired) },
    retentionLabel () {
      if (this.retentionHistorical) return this.info?.retentionstate === 'COMPLETED' ? 'label.backup.retention.historical.verified' : 'label.backup.retention.historical.unconfirmed'
      return this.info?.retentionstate === 'COMPLETED' ? 'label.backup.retention.protected' : 'label.backup.retention.pending'
    },
    cleanupReason () {
      if (!this.isExpired || this.operation !== 'BACKUP') return this.info?.cleanupreason
      if (this.info?.cleanupreason === 'External artifact absence will be reconfirmed before expiration cleanup') {
        return this.$t('message.backup.catalog.expiration.confirmation')
      }
      if (this.info?.cleanupreason === 'External artifacts have expired; local artifacts and job records await cleanup') {
        return this.$t('message.backup.catalog.expiration.cleanup')
      }
      return this.$t('message.backup.catalog.expiration.blocked') + ' ' + (this.info?.cleanupreason || '')
    },
    isReconciling () {
      return this.error === 'Staging job is being reconciled; retry shortly'
    },
    errorMessage () {
      if (this.isReconciling) return this.$t('message.backup.staging.reconciling.title')
      return this.error === 'No staged restore attempt is recorded'
        ? this.$t('message.backup.staging.no.restore.attempt') : this.error
    },
    sourceCleanupLabel () {
      const engine = String(this.resource.backupengine || '').toUpperCase()
      if (engine.startsWith('RBD')) return 'label.backup.source.cleanup.state'
      return engine === 'QCOW2' ? 'label.backup.qcow2.source.cleanup.state' : 'label.backup.source.cleanup.generic.state'
    },
    canManage () { return !this.readOnly && 'reconcileBackupStagingJob' in this.$store.getters.apis },
    deletionPending () { return this.resource.deletionpending === true || this.info?.deletionpending === true },
    canMutate () { return this.canManage && !this.deletionPending && typeof this.info?.stagingjobid === 'string' && !!this.info.stagingjobid.trim() && !this.info.historical },
    unresolvedArtifacts () {
      return (this.info?.artifact || []).filter(row => row.index != null && !row.completed && !row.terminal &&
        (row.submissionpending || row.externaljobid) && (this.operation !== 'BACKUP' || row.owned) &&
        (this.operation !== 'RESTORE' || row.requestrecorded))
    },
    selected () { return this.unresolvedArtifacts.find(row => this.selectedKeys.includes(row.path)) },
    linkGuide () {
      return this.$t(this.operation === 'RESTORE' && ['ablestack-netbackup', 'ablestack-veeam'].includes(this.info?.provider)
        ? 'message.backup.staging.link.restore' : 'message.backup.staging.link')
    },
    diagnosticErrors () {
      return [this.info?.hosterror, this.info?.reservationerror, this.info?.reconciliationerror,
        this.info?.vmrestore?.failure, this.info?.vmrestore?.recoveryerror, this.info?.vmrestore?.queryerror].filter(Boolean).join(' · ')
    },
    availabilityErrors () { return (this.info?.artifact || []).filter(row => row.availabilityerror) },
    successfulBackup () {
      return this.operation === 'BACKUP' && this.resource.status === 'BackedUp' &&
        this.info?.hoststate === 'COMPLETED' && this.info?.stagingqueue?.state === 'RELEASED' &&
        this.info?.cleanupstate === 'COMPLETED'
    },
    situation () {
      let key = 'inspect'
      let type = 'info'
      if (this.deletionPending) {
        key = 'delete.pending'; type = 'warning'
      } else if (this.isExpired && this.operation === 'BACKUP') {
        return { type: 'warning', title: 'state.expired', description: 'message.backup.catalog.expired' }
      } else if (this.info?.historical) {
        key = 'history'
      } else if (this.endedNetBackup) {
        key = 'backup.ended'; type = 'warning'
      } else if (this.info?.retentionstate && this.info.retentionstate !== 'COMPLETED') {
        return { type: 'warning', title: 'label.backup.retention.pending', description: 'message.backup.retention.pending' }
      } else if (this.diagnosticErrors || this.availabilityErrors.length) {
        key = 'query.failed'; type = 'warning'
      } else if (this.unresolvedArtifacts.some(row => row.submissionpending)) {
        key = 'unconfirmed'; type = 'warning'
      } else if (this.canCleanup || ['WAITING', 'RUNNING'].includes(this.info?.cleanupstate) ||
        ['WAITING', 'RUNNING'].includes(this.info?.sourcecleanupstate) || ['WAITING', 'RUNNING'].includes(this.info?.jobcleanupstate)) {
        key = 'cleanup.pending'; type = 'warning'
      } else if (this.successfulBackup) {
        key = 'backup.completed'; type = 'success'
      } else if (this.info?.cleanupstate === 'COMPLETED') key = 'cleanup.completed'
      return { type, title: 'message.backup.staging.' + key + '.title', description: 'message.backup.staging.' + key }
    },
    canCleanup () {
      if (!this.canMutate) return false
      const failedBackup = ['Failed', 'Canceled', 'Error'].includes(this.resource.status)
      if (this.info.cleanupstate === 'COMPLETED') {
        // Finished staging removal is durable proof for retrying the remaining records/slot release.
        return this.operation === 'BACKUP' && (failedBackup || this.successfulBackup) &&
          (['WAITING', 'RUNNING'].includes(this.info.jobcleanupstate) ||
            (!!this.info.stagingqueue && this.info.stagingqueue.state !== 'RELEASED'))
      }
      const terminal = ['COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELED'].includes(this.info?.hoststate)
      const unstarted = this.info?.hoststate === 'UNKNOWN' && ['PREPARING', 'PREPARED', 'SUBMISSION_PENDING', 'START_FAILED'].includes(this.info?.startstate)
      return (terminal || unstarted) && (this.operation === 'RESTORE' || failedBackup)
    },
    columns () {
      return [{ key: 'volume', title: this.$t('label.backup.image.volume.file'), width: 350 },
        { key: 'job', title: this.$t('label.backup.staging.external.job'), width: 220 },
        { key: 'state', title: this.$t('label.backup.staging.external.state'), width: 140 },
        { key: 'availability', title: this.$t('label.backup.image.availability'), width: 200, fixed: 'right' }]
    },
    rowSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.selectedKeys,
        onChange: keys => { this.selectedKeys = keys },
        getCheckboxProps: () => ({ disabled: this.busy || this.loading })
      }
    }
  },
  watch: {
    operation () { this.selectedRestoreJobId = ''; this.info = null; this.selectedKeys = []; this.externalJobId = ''; this.load() },
    'resource.id' () {
      this.requestToken++; this.busy = false; this.loading = false; this.selectedRestoreJobId = ''; this.info = null; this.selectedKeys = []; this.externalJobId = ''
      const operation = this.readOnly ? 'BACKUP' : this.resource.stagingqueue?.operation || 'BACKUP'
      if (operation !== this.operation) this.operation = operation
      else this.load()
    }
  },
  mounted () { this.load() },
  beforeUnmount () { this.disposed = true; this.requestToken++ },
  methods: {
    imageVolumeLabel (artifact) {
      if (artifact.metadata) return this.$t('label.metadata')
      try {
        const volumes = typeof this.resource.volumes === 'string' ? JSON.parse(this.resource.volumes) : this.resource.volumes
        const volume = (Array.isArray(volumes) ? volumes : []).find(item => item.uuid === artifact.volumeid)
        return volume?.type === 'DATADISK' ? 'DATA' : volume?.type || artifact.volumeid
      } catch (_) { return artifact.volumeid }
    },
    availabilityLabel (artifact) {
      const key = artifact.availabilityerror ? 'query.failed'
        : artifact.availability === 'AVAILABLE' ? 'available'
          : artifact.availability === 'MISSING' ? 'missing'
            : artifact.availabilitycheckedat ? 'unknown' : 'not.checked'
      return this.$t('label.backup.staging.file.' + key)
    },
    availabilityColor (artifact) {
      if (artifact.availabilityerror) return 'orange'
      if (artifact.availability === 'AVAILABLE') return 'green'
      if (artifact.availability === 'MISSING') return 'red'
      return undefined
    },
    backupTime (value) {
      const date = /^\d+$/.test(String(value)) ? new Date(Number(value) * 1000) : new Date(value)
      return Number.isNaN(date.getTime()) ? String(value) : this.$toLocaleDate(date.toISOString())
    },
    current (token) { return !this.disposed && token === this.requestToken },
    stagingInfo (result) {
      const info = result?.backupstaginginfo || result
      return typeof info?.stagingjobid === 'string' && info.stagingjobid.trim() ? info : null
    },
    changeRestoreAttempt () { this.selectedKeys = []; this.externalJobId = ''; this.load() },
    async load () {
      if (this.busy || this.disposed) return
      const token = ++this.requestToken
      this.loading = true; this.error = ''; this.info = null; this.selectedKeys = []; this.externalJobId = ''
      try {
        const params = this.readOnly ? { id: this.resource.id } : { id: this.resource.id, operation: this.operation }
        if (this.operation === 'RESTORE' && this.selectedRestoreJobId) params.stagingjobid = this.selectedRestoreJobId
        const response = await getAPI(this.inspectionApi, params)
        if (this.current(token)) this.info = this.stagingInfo(response[this.inspectionApi.toLowerCase() + 'response'])
      } catch (error) {
        if (this.current(token)) this.error = error.response?.data?.[this.inspectionApi.toLowerCase() + 'response']?.errortext || error.message || String(error)
      } finally {
        if (this.current(token)) this.loading = false
      }
    },
    async run (action) {
      if (!this.canMutate || this.busy || this.loading) return
      if (action === 'LINK_JOB' && (!this.selected || !this.externalJobId.trim())) return
      if (action === 'RETRY_CLEANUP' && !this.canCleanup) return
      if (!['RECHECK', 'LINK_JOB', 'RETRY_CLEANUP'].includes(action)) return
      const token = ++this.requestToken
      const params = { id: this.resource.id, operation: this.operation, stagingjobid: this.info.stagingjobid, action }
      if (action === 'LINK_JOB') { params.artifactindex = this.selected.index; params.externaljobid = this.externalJobId.trim() }
      this.busy = true; this.error = ''
      const failed = result => {
        if (this.current(token)) {
          this.busy = false
          this.error = result?.jobresult?.errortext || result?.response?.data?.reconcilebackupstagingjobresponse?.errortext || result?.message || this.$t('message.backup.staging.action.unconfirmed')
        }
      }
      try {
        const response = await postAPI('reconcileBackupStagingJob', params)
        const jobId = response.reconcilebackupstagingjobresponse?.jobid
        if (!jobId) throw new Error(this.$t('message.backup.staging.action.unconfirmed'))
        await this.$pollJob({
          jobId,
          originalPage: this.$route.path,
          title: this.$t('label.backup.staging.management'),
          description: params.stagingjobid,
          resourceId: params.id,
          showLoading: false,
          showSuccessMessage: false,
          successMethod: result => {
            if (!this.current(token)) return
            this.busy = false; this.selectedKeys = []; this.externalJobId = ''
            const info = this.stagingInfo(result.jobresult)
            this.info = info
            if (!info) this.error = this.$t('message.backup.staging.result.missing')
            this.$emit('refresh')
          },
          errorMethod: failed,
          catchMethod: failed
        })
      } catch (error) { failed(error); if (this.current(token)) this.$notifyError(error) }
    }
  }
}
</script>

<style scoped>
.staging-header, .staging-status, .staging-alert, .staging-artifacts, .staging-actions { margin-bottom: 16px; }
.staging-header { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 12px 24px; }
.staging-purpose { flex: 1 1 320px; margin: 0; color: var(--ant-text-color-secondary, #666); line-height: 1.7; }
.staging-toolbar { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 8px; margin-left: auto; }
.staging-status { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px; color: var(--ant-text-color-secondary, #666); }
.staging-error { color: var(--ant-warning-color, #ad6800); }
.staging-actions, .staging-details, .staging-files-guide { margin-top: 16px; }
.staging-management { overflow-wrap: anywhere; }
.staging-management small { display: block; }
.staging-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
