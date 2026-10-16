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
    <div class="staging-toolbar">
      <a-select v-model:value="operation" :disabled="busy || loading" style="width: 140px">
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
    <a-alert v-if="error" type="warning" show-icon :message="errorMessage" class="staging-alert" />
    <a-alert
      v-if="!loading && !error && !info"
      type="info"
      show-icon
      :message="$t('message.backup.staging.no.details')"
      class="staging-alert" />
    <template v-if="info">
      <a-alert
        :type="situation.type"
        show-icon
        :message="$t(situation.title)"
        :description="$t(situation.description)"
        class="staging-alert" />
      <a-descriptions bordered :column="1" size="small">
        <a-descriptions-item :label="$t('label.vm')">{{ info.vmname || resource.vmname || '—' }}<span v-if="info.timestamp"> · {{ info.timestamp }}</span></a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE' && info.targetvmname" :label="$t('label.backup.staging.restore.target')">{{ info.targetvmname }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.backup.staging.worker.host')">{{ info.hostname || $t('label.backup.staging.not.confirmed') }}</a-descriptions-item>
        <a-descriptions-item v-if="info.destination" :label="$t('label.backup.staging.destination')">{{ info.destination }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.operation.status')">{{ info.hoststate || 'UNKNOWN' }}</a-descriptions-item>
        <a-descriptions-item v-if="info.step" :label="$t('label.step')">{{ info.step }}</a-descriptions-item>
        <a-descriptions-item v-if="info.volumecount" :label="$t('label.backup.staging.volume.progress')">{{ info.volumeindex || '—' }} / {{ info.volumecount }}</a-descriptions-item>
        <a-descriptions-item v-if="info.stagingqueue" :label="$t('label.backup.staging.state')">{{ info.stagingqueue.state }}<div v-if="info.stagingqueue.reason">{{ info.stagingqueue.reason }}</div></a-descriptions-item>
        <a-descriptions-item v-if="info.cleanupstate" :label="$t('label.backup.cleanup.state')">
          {{ info.cleanupstate === 'NONE' ? $t('label.backup.staging.cleanup.none') : info.cleanupstate }}
          <div v-if="info.cleanupreason">{{ info.cleanupreason }}</div>
          <small v-if="successfulBackup">{{ $t('message.backup.staging.metadata.persisted') }}</small>
        </a-descriptions-item>
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
      <a-collapse v-if="info.artifact?.length" class="staging-details">
        <a-collapse-panel key="files" :header="$t('label.backup.staging.files')">
          <p>{{ $t('message.backup.staging.files') }}</p>
          <a-table
            :columns="columns"
            :data-source="info.artifact"
            :row-key="row => row.path"
            :pagination="{ pageSize: 20, hideOnSinglePage: true }"
            :scroll="{ x: 1000 }"
            size="small">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'volume'">
                <div>{{ record.metadata ? $t('label.metadata') : record.volumeid }}</div>
                <small>{{ record.path }}</small>
                <small v-if="record.destination">{{ $t('label.backup.staging.destination') }}: {{ record.destination }}</small>
                <small v-if="!record.owned">{{ $t('label.backup.staging.parent.backup') }}: {{ record.backupid }}</small>
              </template>
              <template v-else-if="column.key === 'job'">
                <div>{{ record.externaljobid || (record.submissionpending ? 'UNCONFIRMED' : '—') }}</div>
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
        </a-collapse-panel>
        <a-collapse-panel v-if="canMutate && unresolvedArtifacts.length" key="link" :header="$t('label.backup.staging.manual.link')">
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
    <div class="staging-footer"><a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.close') }}</a-button></div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'

export default {
  name: 'BackupStagingManagement',
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh'],
  data () {
    return { operation: this.resource.stagingqueue?.operation || 'BACKUP', selectedRestoreJobId: '', info: null, selectedKeys: [], externalJobId: '', loading: false, busy: false, error: '', requestToken: 0, disposed: false }
  },
  computed: {
    errorMessage () {
      return this.error === 'No staged restore attempt is recorded'
        ? this.$t('message.backup.staging.no.restore.attempt') : this.error
    },
    sourceCleanupLabel () {
      const engine = String(this.resource.backupengine || '').toUpperCase()
      if (engine.startsWith('RBD')) return 'label.backup.source.cleanup.state'
      return engine === 'QCOW2' ? 'label.backup.qcow2.source.cleanup.state' : 'label.backup.source.cleanup.generic.state'
    },
    canManage () { return 'reconcileBackupStagingJob' in this.$store.getters.apis },
    canMutate () { return this.canManage && typeof this.info?.stagingjobid === 'string' && !!this.info.stagingjobid.trim() && !this.info.historical },
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
      if (this.info?.historical) {
        key = 'history'
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
        return this.operation === 'BACKUP' && failedBackup &&
          (['WAITING', 'RUNNING'].includes(this.info.jobcleanupstate) ||
            (!!this.info.stagingqueue && this.info.stagingqueue.state !== 'RELEASED'))
      }
      const terminal = ['COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELED'].includes(this.info?.hoststate)
      const unstarted = this.info?.hoststate === 'UNKNOWN' && ['PREPARING', 'PREPARED', 'SUBMISSION_PENDING', 'START_FAILED'].includes(this.info?.startstate)
      return (terminal || unstarted) && (this.operation === 'RESTORE' || failedBackup)
    },
    columns () {
      return [{ key: 'volume', title: this.$t('label.backup.staging.volume.file'), width: 350 },
        { key: 'job', title: this.$t('label.backup.staging.external.job'), width: 220 },
        { key: 'state', title: this.$t('label.backup.staging.external.state'), width: 140 },
        { key: 'availability', title: this.$t('label.backup.staging.file.availability'), width: 200, fixed: 'right' }]
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
      const operation = this.resource.stagingqueue?.operation || 'BACKUP'
      if (operation !== this.operation) this.operation = operation
      else this.load()
    }
  },
  mounted () { this.load() },
  beforeUnmount () { this.disposed = true; this.requestToken++ },
  methods: {
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
        const params = { id: this.resource.id, operation: this.operation }
        if (this.operation === 'RESTORE' && this.selectedRestoreJobId) params.stagingjobid = this.selectedRestoreJobId
        const response = await getAPI('getBackupStagingInfo', params)
        if (this.current(token)) this.info = this.stagingInfo(response.getbackupstaginginforesponse)
      } catch (error) {
        if (this.current(token)) this.error = error.response?.data?.getbackupstaginginforesponse?.errortext || error.message || String(error)
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
.staging-toolbar, .staging-alert, .staging-artifacts, .staging-actions { margin-bottom: 16px; }
.staging-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.staging-error { color: var(--ant-warning-color, #ad6800); }
.staging-actions, .staging-details { margin-top: 16px; }
.staging-management { overflow-wrap: anywhere; }
.staging-management small { display: block; }
.staging-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
