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
    <a-space class="staging-toolbar">
      <a-select v-model:value="operation" :disabled="busy || loading" style="width: 140px">
        <a-select-option value="BACKUP">BACKUP</a-select-option>
        <a-select-option value="RESTORE">RESTORE</a-select-option>
      </a-select>
      <a-select
        v-if="operation === 'RESTORE' && info?.restoreattempt?.length"
        v-model:value="selectedRestoreJobId"
        :disabled="busy || loading"
        style="min-width: 360px"
        @change="changeRestoreAttempt">
        <a-select-option v-for="attempt in info.restoreattempt" :key="attempt.stagingjobid" :value="attempt.current ? '' : attempt.stagingjobid">
          {{ attempt.current ? 'CURRENT' : 'HISTORY' }} · {{ attempt.stagingjobid }} · {{ attempt.hostname || 'UNKNOWN' }} · {{ attempt.vmrestoreoutcome || 'NOT_RECORDED' }}
        </a-select-option>
      </a-select>
      <a-button :loading="loading" :disabled="busy" @click="load">{{ $t('label.refresh') }}</a-button>
      <a-button v-if="canManage" :disabled="!canMutate || busy || loading" @click="run('RECHECK')">Recheck external jobs and files</a-button>
    </a-space>
    <a-alert v-if="error" type="warning" show-icon :message="error" class="staging-alert" />
    <template v-if="info">
      <a-alert v-if="info.historical" type="info" show-icon message="Recorded restore history. Host state is the last confirmed state; management actions are available only for the current attempt." class="staging-alert" />
      <a-descriptions bordered :column="1" size="small">
        <a-descriptions-item label="Staging Job ID">{{ info.stagingjobid }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vm')">{{ info.vmname }} · {{ info.timestamp }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE'" label="Restore target VM">{{ info.targetvmname || 'NOT_RECORDED' }}</a-descriptions-item>
        <a-descriptions-item v-if="info.startstate || operation === 'RESTORE'" :label="operation === 'RESTORE' ? 'Restore start' : 'Backup start'">{{ info.startstate || 'NOT_RECORDED' }} · {{ info.startreason || '—' }}<div v-if="info.startsubmittedat">Dispatch intent: {{ $toLocaleDate(info.startsubmittedat) }}</div><div v-if="info.startcheckedat">Confirmed: {{ $toLocaleDate(info.startcheckedat) }}</div></a-descriptions-item>
        <a-descriptions-item v-if="info.cancelrequestedat" :label="operation === 'RESTORE' ? 'Restore cancellation' : 'Backup cancellation'">
          {{ info.cancelreason || 'Cancellation requested; termination and cleanup must be confirmed' }}
          <div>Requested: {{ $toLocaleDate(info.cancelrequestedat) }}</div>
          <div v-if="info.cancelconfirmedat">Host acknowledged: {{ $toLocaleDate(info.cancelconfirmedat) }}</div>
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.host')">{{ info.hostname || 'UNKNOWN' }}</a-descriptions-item>
        <a-descriptions-item label="Destination">{{ info.destination }}</a-descriptions-item>
        <a-descriptions-item label="Host state / step">{{ info.hoststate }} / {{ info.step || '—' }} <span v-if="info.progress != null">({{ info.progress }}%)</span></a-descriptions-item>
        <a-descriptions-item v-if="info.hostcheckedat" label="Host state confirmed">{{ $toLocaleDate(info.hostcheckedat) }}</a-descriptions-item>
        <a-descriptions-item label="Current / last volume">{{ info.volumeindex || '—' }} / {{ info.volumecount || '—' }} · {{ info.currentvolumeid || '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="!info.historical" label="Reserved capacity">{{ bytes(info.reservedbytes) }} · {{ info.reservationstate }}</a-descriptions-item>
        <a-descriptions-item v-if="!info.historical" label="Admission">{{ info.stagingqueue?.state || '—' }} · {{ info.stagingqueue?.reason || '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="!info.historical" label="Required / effective available">{{ bytes(info.stagingqueue?.requiredbytes) }} / {{ bytes(info.stagingqueue?.effectiveavailablebytes) }}</a-descriptions-item>
        <a-descriptions-item v-if="!info.historical && info.stagingqueue?.primarystorage?.length" label="Primary storage reservations">
          <div v-for="claim in info.stagingqueue.primarystorage" :key="claim.storagekey">
            {{ claim.storagekey }} · Required: {{ bytes(claim.requiredbytes) }} · Effective available: {{ bytes(claim.effectiveavailablebytes) }}
            · {{ ['ADMITTING', 'ADMITTED', 'CANCEL_PENDING'].includes(info.stagingqueue.state) ? 'HELD' : info.stagingqueue.state === 'RELEASED' ? 'RELEASED' : 'NOT_RESERVED' }}
          </div>
        </a-descriptions-item>
        <a-descriptions-item label="Cleanup">{{ info.cleanupstate || '—' }} · {{ info.cleanupreason || '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'BACKUP' && info.sourcecleanupstate" label="Previous RBD snapshot cleanup">{{ info.sourcecleanupstate }} · {{ info.sourcecleanupreason || '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'BACKUP' && info.jobcleanupstate" label="Host job record cleanup">{{ info.jobcleanupstate }} · {{ info.jobcleanupreason || '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'BACKUP' && info.finalizationstate" label="Backup finalization">{{ info.finalizationstate }} · {{ info.finalizationreason || '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE'" label="VM restore outcome">{{ info.vmrestore?.outcome || 'NOT_RECORDED' }} · {{ info.vmrestore?.phase || 'NOT_RECORDED' }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE'" label="Primary volume cleanup">{{ info.vmrestore?.primarycleanupstate || 'NOT_RECORDED' }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE' && info.vmrestore?.transactionid" label="VM transaction">{{ info.vmrestore.transactionid }} · Revision {{ info.vmrestore.revision }}</a-descriptions-item>
        <a-descriptions-item v-if="operation === 'RESTORE' && info.vmrestore?.updatedat" label="VM result times">
          <div>Updated: {{ $toLocaleDate(info.vmrestore.updatedat) }}</div>
          <div v-if="info.vmrestore.preparedat">All volumes prepared: {{ $toLocaleDate(info.vmrestore.preparedat) }}</div>
          <div v-if="info.vmrestore.switchstartedat">Switch started: {{ $toLocaleDate(info.vmrestore.switchstartedat) }}</div>
          <div v-if="info.vmrestore.committedat">Committed: {{ $toLocaleDate(info.vmrestore.committedat) }}</div>
          <div v-if="info.vmrestore.rollbackstartedat">Rollback started: {{ $toLocaleDate(info.vmrestore.rollbackstartedat) }}</div>
          <div v-if="info.vmrestore.rolledbackat">Rolled back: {{ $toLocaleDate(info.vmrestore.rolledbackat) }}</div>
          <div v-if="info.vmrestore.primarycleanupcompletedat">Primary cleanup completed: {{ $toLocaleDate(info.vmrestore.primarycleanupcompletedat) }}</div>
          <div v-if="info.vmrestore.checkedat">Result checked: {{ $toLocaleDate(info.vmrestore.checkedat) }}</div>
        </a-descriptions-item>
      </a-descriptions>
      <a-alert
        v-if="operation === 'RESTORE' && (info.vmrestore?.failure || info.vmrestore?.recoveryerror || info.vmrestore?.queryerror)"
        type="warning"
        show-icon
        :message="[info.vmrestore.failure, info.vmrestore.recoveryerror, info.vmrestore.queryerror].filter(Boolean).join(' · ')"
        class="staging-alert" />
      <a-table
        v-if="operation === 'RESTORE' && info.vmrestore?.volume?.length"
        :columns="vmColumns"
        :data-source="info.vmrestore.volume"
        row-key="volumeid"
        :pagination="false"
        :scroll="{ x: 1500 }"
        size="small"
        class="staging-artifacts">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'volume'"><div>{{ record.index }} · {{ record.volumeid }}</div><small>{{ record.destination || 'NOT_RECORDED' }}</small><small v-if="record.poolid">Pool: {{ record.poolid }}</small><small v-if="record.hadoriginal != null">Original existed: {{ record.hadoriginal }}</small></template>
          <template v-else-if="column.key === 'prepare'"><div>{{ record.preparestate }}</div><small v-if="record.preparestartedat">Started: {{ $toLocaleDate(record.preparestartedat) }}</small><small v-if="record.preparedat">Prepared: {{ $toLocaleDate(record.preparedat) }}</small></template>
          <template v-else-if="column.key === 'switch'"><div>{{ record.switchstate }}</div><small v-if="record.switchstartedat">Started: {{ $toLocaleDate(record.switchstartedat) }}</small><small v-if="record.switchedat">Switched: {{ $toLocaleDate(record.switchedat) }}</small></template>
          <template v-else-if="column.key === 'rollback'"><div>{{ record.rollbackstate }}</div><small v-if="record.rollbackstartedat">Started: {{ $toLocaleDate(record.rollbackstartedat) }}</small><small v-if="record.rolledbackat">Rolled back: {{ $toLocaleDate(record.rolledbackat) }}</small></template>
          <template v-else-if="column.key === 'cleanup'"><div>{{ record.cleanupstate }}</div><small v-if="record.cleanedat">Cleaned: {{ $toLocaleDate(record.cleanedat) }}</small><small v-if="record.failure">Failure: {{ record.failure }}</small></template>
        </template>
      </a-table>
      <a-alert
        v-if="info.hosterror || info.reservationerror || info.reconciliationerror"
        type="warning"
        show-icon
        :message="[info.hosterror, info.reservationerror, info.reconciliationerror].filter(Boolean).join(' · ')"
        class="staging-alert" />
      <a-table
        :columns="columns"
        :data-source="info.artifact || []"
        :row-key="row => row.path"
        :pagination="{ pageSize: 20, hideOnSinglePage: true }"
        :row-selection="canMutate ? rowSelection : undefined"
        :scroll="{ x: operation === 'RESTORE' ? 1600 : 1100 }"
        size="small"
        class="staging-artifacts">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'volume'"><div>{{ record.metadata ? 'METADATA' : record.volumeid }}</div><small v-if="operation === 'RESTORE'">Sequence {{ record.index }}<span v-if="record.chainindex != null"> · Chain {{ record.chainindex }}</span></small><small>{{ record.path }}</small><small v-if="record.destination">Destination: {{ record.destination }}</small><small v-if="!record.owned">Inherited: {{ record.backupid }}</small></template>
          <template v-else-if="column.key === 'job'"><div>{{ record.externaljobid || (record.submissionpending ? 'UNCONFIRMED' : '—') }}</div><small>{{ record.externalid || '—' }}</small><small>{{ record.backuptime || '—' }}</small></template>
          <template v-else-if="column.key === 'state'">{{ record.restorestate || (record.completed ? 'COMPLETED' : record.terminal ? 'TERMINAL' : record.submissionpending ? 'UNCONFIRMED' : record.externaljobid ? 'RUNNING / UNKNOWN' : '—') }}<small v-if="record.failure">Failure: {{ record.failure }}</small><small v-if="record.queryerror">Query: {{ record.queryerror }}</small></template>
          <template v-else-if="column.key === 'history'">
            <small v-if="record.recordedat">Recorded: {{ $toLocaleDate(record.recordedat) }}</small>
            <small v-if="record.submittedat">Submission intent: {{ $toLocaleDate(record.submittedat) }}</small>
            <small v-if="record.completedat">Completed: {{ $toLocaleDate(record.completedat) }}</small>
            <small v-if="record.terminalat">Terminal: {{ $toLocaleDate(record.terminalat) }}</small>
            <small v-if="record.acknowledgedat">Acknowledged: {{ $toLocaleDate(record.acknowledgedat) }}</small>
            <small v-if="record.checkedat">Checked: {{ $toLocaleDate(record.checkedat) }}</small>
            <span v-if="!record.recordedat && !record.checkedat">—</span>
          </template>
          <template v-else-if="column.key === 'availability'"><a-tooltip :title="record.availabilityerror"><span>{{ record.availability || 'UNKNOWN' }}</span></a-tooltip><small v-if="record.availabilitycheckedat">{{ $toLocaleDate(record.availabilitycheckedat) }}</small></template>
        </template>
      </a-table>
      <a-form v-if="canMutate" layout="vertical">
        <a-form-item label="External Job ID" :extra="linkGuide">
          <a-input v-model:value="externalJobId" :disabled="busy || loading" />
        </a-form-item>
        <a-space>
          <a-button :disabled="busy || loading || !selected || !externalJobId.trim()" @click="run('LINK_JOB')">Connect verified Job ID</a-button>
          <a-button :disabled="busy || loading || !canCleanup" @click="run('RETRY_CLEANUP')">Retry cleanup</a-button>
        </a-space>
      </a-form>
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
    canManage () { return 'reconcileBackupStagingJob' in this.$store.getters.apis },
    canMutate () { return this.canManage && !!this.info && !this.info.historical },
    selected () { return this.info?.artifact?.find(row => this.selectedKeys.includes(row.path)) },
    linkGuide () {
      const base = 'Select an unresolved operation. The provider must verify the exact VM, volume, timestamp and operation before connecting it.'
      return this.operation === 'RESTORE' && ['ablestack-netbackup', 'ablestack-veeam'].includes(this.info?.provider)
        ? base + ' A matching saved restore request is required for NetBackup/Veeam; an unverified Job ID cannot be connected.' : base
    },
    canCleanup () {
      const terminal = ['COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELED'].includes(this.info?.hoststate)
      const unstarted = this.info?.hoststate === 'UNKNOWN' && ['PREPARING', 'PREPARED', 'SUBMISSION_PENDING', 'START_FAILED'].includes(this.info?.startstate)
      return !this.info?.historical && (terminal || unstarted) && this.info?.cleanupstate !== 'COMPLETED' &&
        (this.operation === 'RESTORE' || ['Failed', 'Canceled', 'Error'].includes(this.resource.status))
    },
    columns () {
      const columns = [{ key: 'volume', title: 'Volume / path', width: 400 }, { key: 'job', title: 'External job / catalog / point', width: 300 }, { key: 'state', title: 'External state', width: 220 }]
      if (this.operation === 'RESTORE') columns.push({ key: 'history', title: 'Recorded times', width: 280 })
      columns.push({ key: 'availability', title: 'Artifact availability', width: 180 })
      return columns
    },
    vmColumns () {
      return [{ key: 'volume', title: 'VM volume / destination', width: 400 }, { key: 'prepare', title: 'Primary preparation', width: 250 },
        { key: 'switch', title: 'Volume switch', width: 250 }, { key: 'rollback', title: 'Rollback', width: 250 }, { key: 'cleanup', title: 'Primary cleanup', width: 300 }]
    },
    rowSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.selectedKeys,
        onChange: keys => { this.selectedKeys = keys },
        getCheckboxProps: row => ({ disabled: this.busy || this.loading || row.index == null || row.completed || row.terminal || (!row.submissionpending && !row.externaljobid) || (this.operation === 'BACKUP' && !row.owned) || (this.operation === 'RESTORE' && !row.requestrecorded) })
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
    bytes (value) { return value == null ? 'UNKNOWN' : (Number(value) / 1073741824).toFixed(2) + ' GiB' },
    current (token) { return !this.disposed && token === this.requestToken },
    changeRestoreAttempt () { this.selectedKeys = []; this.externalJobId = ''; this.load() },
    async load () {
      if (this.busy || this.disposed) return
      const token = ++this.requestToken
      this.loading = true; this.error = ''
      try {
        const params = { id: this.resource.id, operation: this.operation }
        if (this.operation === 'RESTORE' && this.selectedRestoreJobId) params.stagingjobid = this.selectedRestoreJobId
        const response = await getAPI('getBackupStagingInfo', params)
        if (this.current(token)) { this.info = response.getbackupstaginginforesponse; this.selectedKeys = [] }
      } catch (error) {
        if (this.current(token)) {
          this.info = null; this.error = error.response?.data?.getbackupstaginginforesponse?.errortext || error.message || String(error)
        }
      } finally {
        if (this.current(token)) this.loading = false
      }
    },
    async run (action) {
      if (!this.canMutate || this.busy || this.loading) return
      if (action === 'LINK_JOB' && (!this.selected || !this.externalJobId.trim())) return
      const token = ++this.requestToken
      const params = { id: this.resource.id, operation: this.operation, stagingjobid: this.info.stagingjobid, action }
      if (action === 'LINK_JOB') { params.artifactindex = this.selected.index; params.externaljobid = this.externalJobId.trim() }
      this.busy = true; this.error = ''
      const failed = result => {
        if (this.current(token)) { this.busy = false; this.error = result?.jobresult?.errortext || result?.message || 'Staging reconciliation was not confirmed. Refresh the operation before retrying.' }
      }
      try {
        const response = await postAPI('reconcileBackupStagingJob', params)
        const jobId = response.reconcilebackupstagingjobresponse?.jobid
        if (!jobId) throw new Error('Staging reconciliation acceptance is unconfirmed. Refresh before retrying.')
        await this.$pollJob({
          jobId,
          originalPage: this.$route.path,
          title: 'Staging management',
          description: params.stagingjobid,
          resourceId: params.id,
          showLoading: false,
          showSuccessMessage: false,
          successMethod: result => {
            if (!this.current(token)) return
            this.busy = false; this.selectedKeys = []; this.externalJobId = ''
            const info = result.jobresult?.backupstaginginfo || result.jobresult
            if (!info?.stagingjobid) {
              this.error = 'The result does not include staging details. Refresh to inspect the operation.'
            } else {
              this.info = info
            }
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
.staging-toolbar, .staging-alert, .staging-artifacts { margin-bottom: 16px; }
.staging-artifacts small { display: block; overflow-wrap: anywhere; }
.staging-footer { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
