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
  <section class="storage-table-section storage-volume-preparation">
    <h4>{{ $t('label.storage.volume.preparation') }}</h4>
    <a-space wrap>
      <a-button v-if="resumeSupported" type="primary" :disabled="!canOpenResume || loading || resuming" @click="openResume">{{ $t('label.storage.volume.resume') }}</a-button>
      <a-select :value="selectedVolume" :placeholder="$t('label.storage.service.backing.volume')" style="min-width: 240px" @change="selectVolume">
        <a-select-option v-for="volume in volumes" :key="volume.id" :value="volume.id">{{ volume.name || volume.id }}</a-select-option>
      </a-select>
      <a-button :loading="loading" :disabled="!selectedVolume || !active" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-switch :checked="automatic" :checked-children="$t('label.storage.volume.auto.observe')" :un-checked-children="$t('label.storage.volume.auto.observe')" @change="setAutomatic" />
    </a-space>
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.volume.observe.failed')" />
    <a-descriptions v-if="selectedVolume" bordered :column="{ xs: 1, sm: 2 }" size="small">
      <a-descriptions-item :label="$t('label.name')">{{ selectedVolumeInfo.name || selectedVolume }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.size')">{{ volumeCapacity }}</a-descriptions-item>
    </a-descriptions>
    <a-descriptions v-if="observation" bordered :column="{ xs: 1, sm: 2 }" size="small" aria-live="polite">
      <a-descriptions-item :label="$t('label.storage.operation.phase')"><a-tag :color="phaseColor">{{ $t('label.storage.volume.phase.' + observation.status) }}</a-tag></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.formatter.active')">{{ formatterLabel }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.operation')"><code>{{ observation.operationId }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.deadline')">{{ deadlineSeconds }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.deadline.at')">{{ deadlineAt }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.diagnostic.code')"><code>{{ diagnosticCode }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.elapsed')">{{ elapsedSeconds }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.lastheartbeat')">{{ heartbeatAt }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.observed.at')">{{ observedAt }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.current.device')"><code>{{ identity.observedDevicePath || '—' }}</code> · {{ observation.currentIdentityStatus || 'UNAVAILABLE' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.filesystem')">{{ identity.filesystem || '—' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.requested.filesystem')">{{ operation.filesystem || '—' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.fs.uuid')"><code>{{ identity.filesystemUuid || '—' }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.mount')"><code>{{ identity.mountPath || '—' }}</code></a-descriptions-item>
    </a-descriptions>
    <a-alert v-if="observation" :type="phaseColor === 'red' ? 'warning' : 'info'" show-icon :message="$t(summaryKey)" />
    <a-alert v-if="observation && !canResume && !canTrackResume" type="info" show-icon :message="$t('message.storage.volume.resume.proof.required')" />
    <a-alert v-if="resumeCompleted" type="success" show-icon :message="$t('message.storage.volume.resume.mount.only')" />
    <details v-if="observation" class="storage-volume-preparation-diagnostics">
      <summary>{{ $t('label.storage.volume.diagnostic.details') }}</summary>
      <p>{{ $t('message.storage.volume.historical.device') }}</p>
      <p>{{ $t('label.storage.volume.historical.device') }}: <code>{{ operation.devicePath || '—' }}</code></p>
      <p>{{ $t('label.storage.volume.fs.uuid') }} ({{ $t('label.storage.volume.journal') }}): <code>{{ operation.filesystemUuid || '—' }}</code></p>
      <pre v-if="diagnosticDetails">{{ diagnosticDetails }}</pre>
    </details>
    <a-modal
      :visible="resumeVisible"
      :title="$t('label.storage.volume.resume')"
      :confirm-loading="resuming"
      :cancel-button-props="{ disabled: resuming }"
      :ok-button-props="{ disabled: !canSubmitResume }"
      :ok-text="$t(canTrackResume ? 'label.storage.volume.resume.track' : 'label.ok')"
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      @ok="resume"
      @cancel="closeResume">
      <a-alert type="info" show-icon :message="$t('message.storage.volume.resume.safe')" />
      <p><code>{{ resumeIntent?.params.operationid || observation?.operationId }}</code></p>
      <p><code>{{ resumeIntent?.params.expectedfilesystemuuid || identity.filesystemUuid }}</code></p>
      <a-alert v-if="resumeJob" type="info" show-icon :message="resumeJob" />
      <a-alert v-if="resumeError" type="error" show-icon :message="resumeError" />
      <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.volume.observe.failed')">
        <template #description><a-button :loading="loading" :disabled="loading || resuming" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button></template>
      </a-alert>
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { createListRefresh, canRefreshList } from '@/utils/listRefresh'
import { preparationObservation, preparationResumeAllowed, preparationTime, preparationNumber, preparationCapacity, preparationDiagnostics } from '@/utils/storageVolumePreparation'

const terminal = new Set(['TIMED_OUT_PENDING_RECONCILE', 'COMPLETE', 'RECOVERY_REQUIRED', 'RECONCILE_REQUIRED', 'NOT_STARTED', 'IDENTITY_MISMATCH', 'ERROR'])
const securityScope = vm => [vm.$store?.getters?.userInfo?.id || '', vm.$store?.getters?.project?.id || ''].join('|')
const token = vm => vm.$store?.state?.user?.token
export default {
  name: 'StorageVolumePreparation',
  components: { ReloadOutlined },
  emits: ['operation-updated'],
  props: {
    instanceId: { type: String, required: true },
    volumes: { type: Array, default: () => [] },
    initialVolumeId: { type: String, default: '' },
    active: { type: Boolean, default: true }
  },
  data: () => ({
    selectedVolume: '',
    observation: null,
    loading: false,
    readError: false,
    automatic: true,
    generation: 0,
    readSequence: 0,
    disposed: false,
    resumeVisible: false,
    modalEpoch: 0,
    resuming: false,
    resumeKey: '',
    resumeError: '',
    resumeJob: '',
    resumeIntent: null,
    resumeCompleted: false,
    jobTimer: null,
    resolveJobWait: null
  }),
  computed: {
    preparationSecurityScope () { return securityScope(this) },
    preparationCapabilities () { return ['getStorageServiceVolumePreparation', 'resumeStorageServiceVolumePreparation'].map(api => this.can(api)).join('|') },
    resumeSupported () { return this.can('resumeStorageServiceVolumePreparation') },
    canResume () { return this.resumeSupported && this.can('getStorageServiceVolumePreparation') && !!this.$store?.getters?.userInfo?.id && !!token(this) && !this.disposed && this.active && !this.readError && this.resumeIntent?.outcome !== 'failed' && preparationResumeAllowed(this.observation) && (!this.retainedIntent || this.resumeIntent.outcome === 'complete' || this.sameIntentParameters()) },
    retainedIntent () { return !!this.resumeIntent && this.resumeIntent.instance === this.instanceId && this.resumeIntent.volume === this.selectedVolume && this.resumeIntent.security === securityScope(this) && this.resumeIntent.token === token(this) },
    canTrackResume () { return this.retainedIntent && !!this.resumeJob && !['failed', 'complete'].includes(this.resumeIntent.outcome) },
    canOpenResume () { return this.active && !this.disposed && (this.canTrackResume || this.canResume) },
    canSubmitResume () { return !this.resuming && !this.loading && !this.disposed && this.active && (this.canTrackResume || this.canResume) },
    selectedVolumeInfo () { return this.volumes.find(volume => volume.id === this.selectedVolume) || {} },
    volumeCapacity () { return preparationCapacity(this.selectedVolumeInfo.size) },
    observedAt () { return preparationTime(this.observation?.observedEpoch) },
    heartbeatAt () { return preparationTime(this.operation.heartbeat) },
    deadlineAt () { return preparationNumber(this.operation.started) && this.operation.started > 0 && preparationNumber(this.operation.formatDeadlineSeconds) && this.operation.formatDeadlineSeconds > 0 ? preparationTime(this.operation.started + this.operation.formatDeadlineSeconds) : '—' },
    diagnosticCode () { return [this.observation?.errorCode, this.observation?.resumeBlocker].find(value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(value)) || '—' },
    deadlineSeconds () { return preparationNumber(this.operation.formatDeadlineSeconds) ? this.operation.formatDeadlineSeconds : '—' },
    elapsedSeconds () { return preparationNumber(this.observedOperation.elapsedSeconds) ? Math.round(this.observedOperation.elapsedSeconds) : '—' },
    formatterLabel () { return this.observation?.formatterObservationComplete === true && typeof this.observation?.formatterActive === 'boolean' ? this.$t(this.observation.formatterActive ? 'label.yes' : 'label.no') : '—' },
    operation () { return this.observation?.operation || {} },
    observedOperation () { return this.observation?.observedOperation || {} },
    identity () { return this.observation?.currentIdentity || {} },
    diagnosticDetails () { return preparationDiagnostics(this.observation) },
    summaryKey () {
      if (this.observation?.status === 'COMPLETE') return 'message.storage.volume.preparation.complete'
      if (['RECOVERY_REQUIRED', 'TIMED_OUT_PENDING_RECONCILE', 'RECONCILE_REQUIRED', 'IDENTITY_MISMATCH', 'ERROR'].includes(this.observation?.status)) return 'message.storage.volume.preparation.recovery'
      return 'message.storage.volume.preparation.observing'
    },
    phaseColor () {
      if (this.observation?.status === 'COMPLETE') return 'green'
      if (['TIMED_OUT_PENDING_RECONCILE', 'RECOVERY_REQUIRED', 'IDENTITY_MISMATCH', 'ERROR'].includes(this.observation?.status)) return 'red'
      return 'orange'
    }
  },
  watch: {
    instanceId () { this.invalidate(); this.selectedVolume = ''; this.initializeVolume() },
    volumes () { if (!this.volumes.some(volume => volume.id === this.selectedVolume)) { this.invalidate(); this.selectedVolume = ''; this.initializeVolume() } },
    initialVolumeId () { if (this.initialVolumeId && this.initialVolumeId !== this.selectedVolume && this.volumes.some(volume => volume.id === this.initialVolumeId)) this.selectVolume(this.initialVolumeId) },
    active (value) { this.pauseContext(); if (value) { this.initializeVolume(); this.refresh() } },
    preparationSecurityScope () { this.invalidate(); this.initializeVolume() },
    preparationCapabilities () { this.invalidate(); this.initializeVolume() },
    '$store.state.user.token' (value) { this.invalidate(); if (value) this.initializeVolume() }
  },
  mounted () {
    this.initializeVolume()
    this.observationRefresh = createListRefresh({
      interval: 5000,
      active: () => this.active && this.automatic && !this.disposed && !this.resuming && !this.loading && !this.readError && !!this.selectedVolume && !terminal.has(this.observation?.status) && canRefreshList(this.$el),
      refresh: () => this.refresh()
    })
  },
  beforeUnmount () { this.disposed = true; this.invalidate(); if (this.observationRefresh) this.observationRefresh.stop() },
  methods: {
    can (api) { return api in (this.$store?.getters?.apis || {}) },
    context () { return { generation: this.generation, instance: this.instanceId, volume: this.selectedVolume, security: securityScope(this), token: token(this), capabilities: this.preparationCapabilities } },
    current (context) { return !this.disposed && this.active && context.generation === this.generation && context.instance === this.instanceId && context.volume === this.selectedVolume && context.security === securityScope(this) && context.token === token(this) && context.capabilities === this.preparationCapabilities },
    parameters () { return { instanceid: this.instanceId, volumeid: this.selectedVolume, operationid: this.observation?.operationId, expectedfilesystemuuid: this.identity.filesystemUuid } },
    sameIntentParameters () { return JSON.stringify(this.resumeIntent?.params) === JSON.stringify(this.parameters()) },
    initializeVolume () {
      if (this.disposed || !this.active) return
      if (this.selectedVolume && this.volumes.some(volume => volume.id === this.selectedVolume)) { this.refresh(); return }
      const id = this.initialVolumeId || (this.volumes.length === 1 ? this.volumes[0].id : '')
      if (id && this.volumes.some(volume => volume.id === id)) {
        if (id !== this.selectedVolume) this.selectVolume(id)
        else this.refresh()
      }
    },
    openResume () {
      if (!this.canOpenResume || this.resuming) return
      if (!this.retainedIntent || this.resumeIntent.outcome === 'complete') {
        this.resumeKey = 'volume-resume-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
        this.resumeIntent = { instance: this.instanceId, volume: this.selectedVolume, security: securityScope(this), token: token(this), params: this.parameters(), key: this.resumeKey, attempted: false, outcome: 'new' }
        this.resumeJob = ''
      }
      this.modalEpoch++; this.resumeError = ''; this.resumeVisible = true
    },
    closeResume () { if (!this.resuming) { this.modalEpoch++; this.resumeVisible = false; this.resumeError = '' } },
    async resume () {
      if (!this.resumeVisible || !this.canSubmitResume || !this.retainedIntent) return false
      const context = this.context(); const epoch = this.modalEpoch; const intent = this.resumeIntent
      const current = () => this.current(context) && this.modalEpoch === epoch && this.resumeIntent === intent
      this.resuming = true; this.resumeError = ''
      try {
        if (!this.resumeJob) {
          if (!this.canResume || !this.sameIntentParameters()) return false
          intent.attempted = true; intent.outcome = 'unknown'
          const response = await postAPI('resumeStorageServiceVolumePreparation', { ...intent.params, idempotencykey: intent.key }, { timeout: 15000, preserveOnFailure: true })
          if (!current()) return false
          const job = response?.resumestorageservicevolumepreparationresponse?.jobid
          if (typeof job !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(job)) { this.resumeError = this.$t('message.storage.volume.resume.unknown'); return false }
          this.resumeJob = job; intent.outcome = 'running'
        }
        const job = this.resumeJob
        for (let count = 0; count < 120; count++) {
          if (!current()) return false
          const reply = await getAPI('queryAsyncJobResult', { jobid: job }, { timeout: 15000, preserveOnFailure: true })
          if (!current()) return false
          const result = reply?.queryasyncjobresultresponse
          if (result?.jobstatus === 1) {
            intent.outcome = 'complete'; this.resumeCompleted = true; this.resumeVisible = false
            await this.refresh()
            if (current()) this.$emit('operation-updated', context.instance)
            return true
          }
          if (result?.jobstatus === 2) {
            intent.outcome = 'failed'; this.resumeError = this.$t('message.storage.volume.resume.failed'); return false
          }
          if (result?.jobstatus !== 0) break
          await new Promise(resolve => { this.resolveJobWait = resolve; this.jobTimer = setTimeout(() => { this.jobTimer = null; this.resolveJobWait = null; resolve() }, 1000) })
        }
        if (current()) { intent.outcome = 'unknown'; this.resumeError = this.$t('message.storage.volume.resume.unknown') }
      } catch (error) {
        if (current()) { intent.outcome = 'unknown'; this.resumeError = this.$t('message.storage.volume.resume.unknown') }
      } finally { if (current()) this.resuming = false }
      return false
    },
    pauseContext () {
      this.generation++; this.readSequence++; this.modalEpoch++
      clearTimeout(this.jobTimer); this.jobTimer = null
      if (this.resolveJobWait) this.resolveJobWait()
      this.resolveJobWait = null; this.loading = false; this.resuming = false; this.resumeVisible = false
    },
    invalidate () {
      this.pauseContext(); this.resumeError = ''; this.resumeJob = ''; this.resumeKey = ''; this.resumeIntent = null
      this.resumeCompleted = false; this.observation = null; this.readError = false
    },
    selectVolume (id) { if (!this.volumes.some(volume => volume.id === id)) return; this.invalidate(); this.selectedVolume = id; this.refresh() },
    setAutomatic (enabled) { this.automatic = enabled === true; if (this.automatic) this.refresh() },
    async refresh () {
      if (this.loading || this.disposed || !this.active || !this.can('getStorageServiceVolumePreparation') || !this.instanceId || !this.selectedVolume) return
      const context = this.context(); const sequence = ++this.readSequence
      const current = () => this.current(context) && sequence === this.readSequence
      this.loading = true
      try {
        const result = await getAPI('getStorageServiceVolumePreparation', { instanceid: context.instance, volumeid: context.volume }, { timeout: 15000, preserveOnFailure: true })
        if (!current()) return
        this.observation = preparationObservation(result, context.instance, context.volume); this.readError = false
      } catch (error) { if (current()) this.readError = true } finally { if (current()) this.loading = false }
    }
  }
}
</script>
<style scoped>
.storage-volume-preparation > * + * { margin-top: 12px; }
.storage-volume-preparation-diagnostics pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
