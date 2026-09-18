<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<template>
  <div class="backup-progress">
    <status :text="displayStatus" displayText :styles="{ 'min-width': '80px' }">
      <template v-if="showJobDetails" #tooltip>
        <div class="backup-progress-tooltip">
          <div class="backup-progress-tooltip-title">{{ displayStatus }}</div>
          <div v-if="showProgress" class="backup-progress-tooltip-row">
            <span>{{ $t('label.progress') }} :</span>
            <span>{{ progress }}%</span>
          </div>
          <div v-if="jobState" class="backup-progress-tooltip-row">
            <span>{{ $t('label.state') }} :</span>
            <span>{{ jobState }}</span>
          </div>
          <div v-if="displayStep" class="backup-progress-tooltip-row">
            <span>{{ $t('label.step') }} :</span>
            <span>{{ displayStep }}</span>
          </div>
          <div v-if="bandwidthLimitMbps !== null" class="backup-progress-tooltip-row">
            <span>{{ $t('label.bandwidth') }} :</span>
            <span>{{ bandwidthLimitMbps === 0 ? $t('label.unlimited') : bandwidthLimitMbps + ' Mbps' }}</span>
          </div>
          <div v-if="bandwidthStatusLabel" class="backup-progress-tooltip-row" :class="{ 'backup-progress-tooltip-error': bandwidthStatus === 'failed' }">
            <span>{{ $t('label.status') }} :</span>
            <span>{{ bandwidthStatusLabel }}</span>
          </div>
        </div>
      </template>
    </status>
    <div v-if="showProgress && isActive" class="backup-progress-line">
      <a-progress
        :percent="progress"
        :showInfo="false"
        size="small"
        status="active" />
      <span class="backup-progress-percent">{{ progress }}%</span>
    </div>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'

const POLL_INTERVAL_MS = 5000

export default {
  name: 'BackupProgress',
  emits: ['capabilities-change', 'restore-finished'],
  components: {
    Status
  },
  props: {
    record: {
      type: Object,
      required: true
    },
    statusText: {
      type: String,
      default: ''
    }
  },
  data () {
    return {
      timer: null,
      localStatus: String(this.record?.status || this.statusText || ''),
      progress: this.normalizeProgress(this.record?.backupjobprogress ?? this.record?.progress),
      jobState: this.record?.restorejobstate || this.record?.backupjobstate || '',
      step: this.record?.backupjobstep || '',
      logPath: this.record?.backupjoblogpath || this.record?.restorejoblogpath || '',
      bandwidthLimitMbps: this.normalizeBandwidth(this.record?.bandwidthlimitmbps),
      bandwidthStatus: this.record?.bandwidthstatus || '',
      restoreJobId: this.record?.restorejobid || '',
      restoreFinished: this.isTerminalJobState(this.record?.restorejobstate),
      restorePending: this.isRestoreJobPending(this.record)
    }
  },
  computed: {
    displayStatus () {
      const status = this.localStatus || String(this.record?.status || this.statusText || '')
      if (this.isRestoring) {
        return 'Restoring'
      }
      return status
    },
    isActive () {
      const status = String(this.localStatus || this.record?.status || '').toLowerCase()
      return ['backingup', 'restoring'].includes(status) || this.hasTrackedRestoreJob
    },
    isRestoring () {
      const status = String(this.localStatus || this.record?.status || '').toLowerCase()
      return status === 'restoring' || (this.hasTrackedRestoreJob && status !== 'backingup')
    },
    hasTrackedRestoreJob () {
      return this.restorePending || (!!this.restoreJobId && !this.restoreFinished)
    },
    hasProgress () {
      return this.progress !== null
    },
    showProgress () {
      return this.hasProgress && this.isActive
    },
    isAwaitingBackupFinalization () {
      return String(this.localStatus || this.record?.status || '').toLowerCase() === 'backingup' &&
        this.isTerminalJobState(this.jobState) &&
        this.normalizeProgress(this.progress) === 100
    },
    isAwaitingRestoreFinalization () {
      return this.isRestoring &&
        this.isTerminalJobState(this.jobState) &&
        this.normalizeProgress(this.progress) === 100
    },
    displayStep () {
      if (this.isAwaitingRestoreFinalization) {
        return this.$t('label.restore.finalizing')
      }
      if (this.isAwaitingBackupFinalization) {
        return this.$t('label.backup.finalizing')
      }
      const restoreStepLabels = {
        REQUESTED: 'label.restore.requested',
        QUEUED: 'label.restore.queued',
        PREPARE_SOURCE: 'label.restore.prepare.source',
        VALIDATE_CHAIN: 'label.restore.validate.chain',
        RESTORE_DATA: 'label.restore.data',
        ATTACH_VOLUME: 'label.restore.attach.volume',
        CLEANUP_SOURCE: 'label.restore.cleanup.source',
        FINALIZING: 'label.restore.finalizing',
        FINALIZATION_FAILED: 'label.restore.finalization.failed',
        COMPLETED: 'label.completed',
        FAILED: 'label.failed'
      }
      const translationKey = restoreStepLabels[String(this.step || '').toUpperCase()]
      return translationKey ? this.$t(translationKey) : this.step
    },
    bandwidthStatusLabel () {
      if (!this.bandwidthStatus) {
        return ''
      }
      return this.$t('message.backup.bandwidth.' + this.bandwidthStatus)
    },
    showJobDetails () {
      return this.isActive && (this.hasProgress || !!this.jobState || !!this.displayStep || !!this.logPath ||
        this.bandwidthLimitMbps !== null || !!this.bandwidthStatus)
    }
  },
  watch: {
    record: {
      deep: true,
      handler () {
        this.syncFromRecord()
        this.restartPolling()
      }
    }
  },
  mounted () {
    this.restartPolling()
  },
  beforeUnmount () {
    this.stopPolling()
  },
  methods: {
    restartPolling () {
      this.stopPolling()
      if (this.shouldPoll()) {
        this.fetchStatus()
        this.timer = window.setInterval(this.fetchStatus, POLL_INTERVAL_MS)
      }
    },
    shouldPoll () {
      const api = this.isRestoring ? 'getBackupRestoreJobStatus' : 'getBackupJobStatus'
      return this.isActive && this.record?.id && (api in this.$store.getters.apis)
    },
    stopPolling () {
      if (this.timer) {
        window.clearInterval(this.timer)
        this.timer = null
      }
    },
    syncFromRecord () {
      this.localStatus = String(this.record?.status || this.statusText || this.localStatus || '')
      if (Object.prototype.hasOwnProperty.call(this.record || {}, 'restorejobid')) {
        this.restoreJobId = this.record?.restorejobid || ''
      }
      this.restoreFinished = this.isTerminalJobState(this.record?.restorejobstate)
      if (this.restoreFinished) {
        this.restorePending = false
      } else if (this.isRestoreJobPending(this.record)) {
        this.restorePending = true
      }
      const progress = this.normalizeProgress(this.record?.backupjobprogress ?? this.record?.progress)
      if (progress !== null) {
        this.progress = progress
      }
      this.jobState = this.record?.restorejobstate || this.record?.backupjobstate || ''
      this.step = this.record?.backupjobstep || this.step
      this.logPath = this.record?.restorejoblogpath || this.record?.backupjoblogpath || this.logPath
      const bandwidthLimitMbps = this.normalizeBandwidth(this.record?.bandwidthlimitmbps)
      if (bandwidthLimitMbps !== null) {
        this.bandwidthLimitMbps = bandwidthLimitMbps
      }
      this.bandwidthStatus = this.record?.bandwidthstatus || this.bandwidthStatus
      if (!this.isActive) {
        this.progress = null
        this.jobState = ''
        this.step = ''
        this.logPath = ''
        this.bandwidthLimitMbps = null
        this.bandwidthStatus = ''
      }
    },
    fetchStatus () {
      if (!this.shouldPoll()) {
        this.stopPolling()
        return
      }
      const api = this.isRestoring ? 'getBackupRestoreJobStatus' : 'getBackupJobStatus'
      const responseKey = this.isRestoring ? 'getbackuprestorejobstatusresponse' : 'getbackupjobstatusresponse'
      getAPI(api, {
        id: this.record.id,
        limit: 5
      }).then(json => {
        const response = this.unwrapStatusResponse(json?.[responseKey] || {})
        this.applyStatus(response)
      }).catch(() => {})
    },
    unwrapStatusResponse (response) {
      if (!response || typeof response !== 'object') {
        return {}
      }
      if (Object.prototype.hasOwnProperty.call(response, 'progress') ||
          Object.prototype.hasOwnProperty.call(response, 'state') ||
          Object.prototype.hasOwnProperty.call(response, 'status')) {
        return response
      }
      const nestedResponse = Object.values(response).find(value => {
        return value && typeof value === 'object' &&
          (Object.prototype.hasOwnProperty.call(value, 'progress') ||
           Object.prototype.hasOwnProperty.call(value, 'state') ||
           Object.prototype.hasOwnProperty.call(value, 'status'))
      })
      return nestedResponse || {}
    },
    applyStatus (response) {
      const wasRestoring = this.isRestoring
      if (wasRestoring) {
        this.localStatus = this.isTerminalJobState(response.state) ? String(response.status || this.record?.status || this.statusText || '') : 'Restoring'
      } else if (response.status) {
        this.localStatus = response.status
      }
      this.jobState = response.state || this.jobState
      this.step = response.step || this.step
      this.logPath = response.logpath || this.logPath
      if (Object.prototype.hasOwnProperty.call(response, 'progress')) {
        this.progress = this.normalizeProgress(response.progress)
      }
      if (Object.prototype.hasOwnProperty.call(response, 'bandwidthlimitmbps')) {
        this.bandwidthLimitMbps = this.normalizeBandwidth(response.bandwidthlimitmbps)
      }
      if (Object.prototype.hasOwnProperty.call(response, 'capabilities')) {
        this.$emit('capabilities-change', response.capabilities || '')
      }
      this.bandwidthStatus = response.bandwidthstatus || this.bandwidthStatus
      if (this.isTerminalJobState(response.state)) {
        this.restoreFinished = true
        this.restorePending = false
        this.$emit('restore-finished', response.state)
      } else if (['starting', 'running'].includes(String(response.state || '').toLowerCase())) {
        this.restoreFinished = false
        this.restorePending = true
      }
      if (!this.isActive) {
        this.stopPolling()
      }
    },
    isTerminalJobState (state) {
      return ['completed', 'failed', 'canceled', 'cancelled', 'interrupted'].includes(String(state || '').toLowerCase())
    },
    isRestoreJobPending (record) {
      const state = String(record?.restorejobstate || '').toLowerCase()
      return record?.restoreoperationpending === true || ['starting', 'running'].includes(state) ||
        (!!record?.restorejobid && !this.isTerminalJobState(state))
    },
    normalizeProgress (value) {
      const progress = Number.parseInt(value, 10)
      if (!Number.isFinite(progress)) {
        return null
      }
      return Math.min(Math.max(progress, 0), 100)
    },
    normalizeBandwidth (value) {
      if (value === undefined || value === null || value === '') {
        return null
      }
      const bandwidth = Number.parseInt(value, 10)
      if (!Number.isFinite(bandwidth)) {
        return null
      }
      return Math.max(bandwidth, 0)
    }
  }
}
</script>

<style scoped>
.backup-progress {
  min-width: 120px;
}

.backup-progress-line {
  align-items: center;
  display: flex;
  gap: 6px;
  margin-top: 4px;
  max-width: 180px;
}

.backup-progress-line :deep(.ant-progress) {
  flex: 1;
  margin: 0;
  min-width: 72px;
}

.backup-progress-percent {
  color: rgba(0, 0, 0, 0.65);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}

.backup-progress-tooltip {
  max-width: 360px;
}

.backup-progress-tooltip-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.backup-progress-tooltip-row {
  display: flex;
  gap: 6px;
}

.backup-progress-tooltip-error span:last-child {
  color: #cf1322;
}

</style>
