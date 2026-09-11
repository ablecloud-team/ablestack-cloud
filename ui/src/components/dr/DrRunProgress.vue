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
  <div class="cross-dr-run-progress">
    <div class="cross-dr-run-progress__head">
      <div>
        <div class="cross-dr-run-progress__title">{{ titleText }}</div>
        <div class="cross-dr-run-progress__meta">
          <span
            v-for="item in metaFields"
            :key="item.label">
            {{ item.label }}: {{ item.value }}
          </span>
        </div>
      </div>
      <dr-status-pill :status="effectiveRunState" />
    </div>

    <div v-if="hasProgress" class="cross-dr-run-progress__workflow">
      <div class="cross-dr-run-progress__workflow-label">
        <span>{{ $t('label.dr.operation.progress') }}</span>
        <span>{{ progress }}%</span>
      </div>
      <a-progress
        :percent="progress"
        :status="progressStatus"
        size="small" />
    </div>
    <div v-else class="cross-dr-run-progress__unknown">
      {{ $t('message.dr.progress.waiting') }}
    </div>

    <div v-if="showTransferPanel" class="cross-dr-transfer-progress">
      <div class="cross-dr-transfer-progress__head">
        <span>{{ $t('label.dr.transfer.progress') }}</span>
        <span v-if="hasTransferProgress">{{ transferPercent }}%</span>
      </div>
      <a-progress
        v-if="hasTransferProgress"
        :percent="transferPercent"
        :status="transferProgressStatus"
        :aria-valuenow="transferPercent"
        aria-valuemin="0"
        aria-valuemax="100"
        size="small" />
      <div v-else class="cross-dr-transfer-progress__preparing">
        {{ $t('message.dr.transfer.progress.preparing') }}
      </div>
      <div v-if="hasTransferProgress" class="cross-dr-transfer-progress__meta">
        <span>{{ formatBytes(transferBytesProcessed) }} / {{ formatBytes(transferBytesTotal) }}</span>
        <span>{{ formatRate(transferThroughputBps) }}</span>
        <span>{{ $t('label.dr.transfer.eta') }}: {{ formatDuration(transferEtaSeconds) }}</span>
        <span v-if="transferDiskCount > 0">{{ $t('label.dr.transfer.disk') }}: {{ transferCurrentDisk }}/{{ transferDiskCount }}</span>
        <span>{{ transferPhase || '-' }} / {{ transferMode || '-' }}</span>
      </div>
      <div v-if="transferProgressStale" class="cross-dr-transfer-progress__stale">
        {{ $t('message.dr.transfer.progress.stale') }}
      </div>
    </div>

    <div v-if="retryNotice" class="cross-dr-run-progress__notice">
      {{ retryNotice }}
    </div>

    <div v-if="failbackLifecycleNotice" class="cross-dr-run-progress__notice cross-dr-run-progress__notice--info">
      {{ failbackLifecycleNotice }}
    </div>

    <div v-if="cbtNotice" class="cross-dr-run-progress__notice cross-dr-run-progress__notice--info">
      {{ cbtNotice }}
    </div>

    <div
      v-if="testLifecycleNotice"
      class="cross-dr-run-progress__notice"
      :class="{ 'cross-dr-run-progress__notice--success': testFailoverActive }">
      {{ testLifecycleNotice }}
    </div>

    <div v-if="normalizedSteps.length" class="cross-dr-run-progress__steps">
      <div
        v-for="step in normalizedSteps"
        :key="step.id || step.stepname || step.steporder"
        class="cross-dr-run-step">
        <div class="cross-dr-run-step__main">
          <span class="cross-dr-run-step__name">{{ step.stepname || '-' }}</span>
          <dr-status-pill :status="step.state" />
        </div>
        <div class="cross-dr-run-step__meta">
          <span v-if="step.progress !== undefined && step.progress !== null">{{ step.progress }}%</span>
          <span v-if="formatStepError(step)"> | {{ formatStepError(step) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import DrStatusPill from '@/components/dr/DrStatusPill.vue'
import {
  drOperationProgress,
  drStateProgress,
  drTransferPercent,
  drTransferWorkflowProgress,
  hasDrTransferProgress
} from '@/utils/drProgress'

export default {
  name: 'DrRunProgress',
  components: {
    DrStatusPill
  },
  props: {
    run: {
      type: Object,
      default: () => ({})
    },
    steps: {
      type: Array,
      default: () => []
    },
    runtime: {
      type: Object,
      default: () => ({})
    }
  },
  computed: {
    titleText () {
      const runType = this.run.runtype || this.run.runType || this.$t('label.dr.run')
      const runId = this.run.id ? ` #${this.run.id}` : ''
      return `${runType}${runId}`
    },
    metaFields () {
      return [
        { label: this.$t('label.dr.current.step'), value: this.currentStepText },
        { label: this.$t('label.dr.runtime.state'), value: this.run.runtimestate },
        { label: this.$t('label.dr.test.session.state'), value: this.run.testsessionstate },
        { label: this.$t('label.dr.boot.validation.mode'), value: this.run.testbootvalidationmode },
        { label: this.$t('label.dr.boot.validation.result'), value: this.run.testbootvalidationstate },
        { label: this.$t('label.dr.worker.state'), value: this.run.workerstate },
        { label: this.$t('label.dr.external.job'), value: this.run.externaljobref },
        { label: this.$t('label.dr.retry'), value: this.retryMeta },
        { label: this.$t('label.error.code'), value: this.errorCode },
        { label: this.$t('label.details'), value: this.failureText }
      ].filter(item => item.value)
    },
    currentStepText () {
      if (this.run.testsessionstate === 'CLOUD_VM_VALIDATING') return this.$t('message.dr.test.failover.boot.validating')
      const step = String(this.run.runtimestep || this.run.currentstep || '')
      const normalized = step.trim().toUpperCase().replace(/_/g, '-')
      if (normalized.includes('REMOTE-SOURCE-PROTECTION-RESUME')) {
        return this.$t('label.dr.failback.protection.resume.verifying')
      }
      return step
    },
    retryMeta () {
      if (this.run.testsessionstate === 'CLOUD_VM_VALIDATING') return ''
      if (!this.run.retryable && String(this.run.state || '').toUpperCase() !== 'RETRYING') {
        return ''
      }
      const fields = []
      if (this.run.retrycount !== undefined && this.run.retrycount !== null) {
        fields.push(`#${this.run.retrycount}`)
      }
      if (this.run.retryafterseconds !== undefined && this.run.retryafterseconds !== null) {
        fields.push(`${this.run.retryafterseconds}s`)
      }
      if (this.run.nextretryat) {
        fields.push(this.run.nextretryat)
      }
      return fields.join(' / ') || 'scheduled'
    },
    retryNotice () {
      if (this.run.testsessionstate === 'CLOUD_VM_VALIDATING') return ''
      if (!this.run.retryable && String(this.run.state || '').toUpperCase() !== 'RETRYING') {
        return ''
      }
      if (this.hasTransferProgress && !this.transferProgressStale &&
        ['PREPARING', 'COPYING', 'VERIFYING'].includes(String(this.transferValue.transferactivitystate || '').toUpperCase())) {
        return ''
      }
      const reason = this.run.errormessage || this.errorText || 'FTCTL engine is busy'
      const meta = this.retryMeta ? ` (${this.retryMeta})` : ''
      return this.$t('message.dr.retry.scheduled', { reason, meta })
    },
    cbtNotice () {
      const fields = []
      const lifecycle = String(this.run.runtimecbtlifecyclestate || '').toUpperCase()
      if (lifecycle === 'ACTIVE') {
        fields.push(this.$t('label.dr.cbt.active'))
      } else if (lifecycle === 'CONFIGURED_PENDING_ACTIVATION') {
        fields.push(this.$t('label.dr.cbt.pending.activation'))
      } else if (lifecycle === 'CONFIG_REQUIRED') {
        fields.push(this.$t('label.dr.cbt.configuration.required'))
      } else if (this.run.runtimecbtenabled === true || this.run.runtimecbtenabled === 'true') {
        fields.push(this.$t('label.dr.cbt.enabled'))
      } else if (this.run.runtimecbtenabled === false || this.run.runtimecbtenabled === 'false') {
        fields.push(this.$t('label.dr.cbt.disabled'))
      }
      if (this.run.runtimecbtdiskid) {
        fields.push(`${this.$t('label.dr.cbt.disk.id')}: ${this.run.runtimecbtdiskid}`)
      }
      if (this.run.runtimecbtmessage) {
        fields.push(this.run.runtimecbtmessage)
      }
      if (this.run.runtimecbtgovcbin) {
        fields.push(`${this.$t('label.dr.govc.binary')}: ${this.run.runtimecbtgovcbin}`)
      }
      return fields.length ? `${this.$t('label.dr.cbt.status')}: ${fields.join(' / ')}` : ''
    },
    testFailoverActive () {
      const runType = String(this.run.runtype || this.run.runType || '').toUpperCase()
      const runState = String(this.run.state || '').toUpperCase()
      const sessionState = String(this.run.testsessionstate || '').toUpperCase()
      return runType === 'TEST_FAILOVER' && runState === 'SUCCEEDED' && sessionState === 'ACTIVE'
    },
    testLifecycleNotice () {
      const runType = String(this.run.runtype || this.run.runType || '').toUpperCase()
      if (runType !== 'TEST_FAILOVER') return ''
      if (this.testFailoverActive) return this.$t('message.dr.test.failover.active')
      if (String(this.run.state || '').toUpperCase() === 'FAILED') return ''
      const sessionState = String(this.run.testsessionstate || '').toUpperCase()
      if (['CLOUD_VM_STARTING', 'CLOUD_VM_VALIDATING'].includes(sessionState)) return this.$t('message.dr.test.failover.boot.validating')
      if (sessionState === 'CLOUD_VM_CREATING') return this.$t('message.dr.test.failover.vm.creating')
      if (sessionState === 'CLOUD_VOLUMES_IMPORTING') return this.$t('message.dr.test.failover.disks.importing')
      if (sessionState === 'ARTIFACTS_READY') return this.$t('message.dr.test.failover.artifacts.ready')
      return this.$t('message.dr.test.failover.accepted')
    },
    failbackLifecycleNotice () {
      const runType = String(this.run.runtype || this.run.runType || '').toUpperCase()
      const state = String(this.run.state || '').toUpperCase()
      if (runType !== 'FAILBACK' || ['SUCCEEDED', 'FAILED', 'CANCELED'].includes(state)) return ''
      const step = String(this.run.runtimestep || this.run.currentstep || '').toUpperCase().replace(/_/g, '-')
      if (step.includes('REMOTE-SOURCE-PROTECTION-RESUME') || step === 'PROTECTION-RESUMING') {
        return this.$t('message.dr.failback.protection.resume.verifying')
      }
      return ''
    },
    errorText () {
      return this.failureText || this.errorCode
    },
    errorCode () {
      const runFailed = String(this.run.state || '').toUpperCase() === 'FAILED'
      const code = this.run.runtimeerrorcode || (runFailed ? this.run.errorcode : null)
      return code || ''
    },
    failureText () {
      const code = this.errorCode
      if (!code) return this.run.errormessage || ''
      const key = `message.dr.error.${String(code).toLowerCase().replace(/_/g, '.')}`
      return this.$te && this.$te(key) ? this.$t(key) : (this.run.errormessage || code)
    },
    progress () {
      return drOperationProgress(this.run, this.transferValue)
    },
    transferWorkflowFloor () {
      return drTransferWorkflowProgress(this.run, this.transferValue)
    },
    stateProgress () {
      return drStateProgress(this.run.state)
    },
    hasProgress () {
      return (this.run.progresspercent !== undefined && this.run.progresspercent !== null) || this.stateProgress > 0
    },
    progressStatus () {
      const state = String(this.effectiveRunState || '').toUpperCase()
      if (state === 'FAILED') {
        return 'exception'
      }
      if (state === 'SUCCEEDED') {
        return 'success'
      }
      if (state === 'CANCELED') {
        return 'normal'
      }
      return 'active'
    },
    transferValue () {
      const runValue = this.run || {}
      const runtimeValue = this.runtime || {}
      const runValid = this.isValidTransferValue(runValue)
      const runtimeValid = this.isValidTransferValue(runtimeValue, true)
      if (runValid && runtimeValid) {
        return this.compareTransferValues(runtimeValue, runValue) >= 0 ? runtimeValue : runValue
      }
      if (runtimeValid) return runtimeValue
      if (runValid) return runValue
      return {}
    },
    hasTransferProgress () {
      return hasDrTransferProgress(this.transferValue)
    },
    transferExpected () {
      const step = String(this.run.runtimestep || this.run.currentstep || '').toUpperCase()
      const phase = String(this.transferValue.transferphase || '').toUpperCase()
      const activity = String(this.transferValue.transferactivitystate || '').toUpperCase()
      return step.includes('TRANSFER') || phase === 'TRANSFER' || ['PREPARING', 'COPYING', 'VERIFYING'].includes(activity)
    },
    showTransferPanel () {
      return this.hasTransferProgress || this.transferExpected
    },
    transferPercent () {
      return drTransferPercent(this.transferValue)
    },
    transferBytesTotal () { return Number(this.transferValue.transferbytestotal || 0) },
    transferBytesProcessed () { return Number(this.transferValue.transferbytesprocessed || this.transferValue.transferpayloadbytes || 0) },
    transferThroughputBps () { return Number(this.transferValue.transferthroughputbps || 0) },
    transferEtaSeconds () { return Number(this.transferValue.transferetaseconds || 0) },
    transferCurrentDisk () {
      const count = this.transferDiskCount
      if (count <= 0) return 0
      const raw = Number(this.transferValue.transfercurrentdiskindex || 0)
      if (!Number.isFinite(raw)) return 1
      return Math.min(count, Math.max(1, Math.trunc(raw) + 1))
    },
    transferDiskCount () { return Number(this.transferValue.transferdiskcount || 0) },
    transferPhase () { return this.transferValue.transferphase || this.transferValue.transferactivitystate || '' },
    transferMode () { return this.transferValue.transfermode || '' },
    transferProgressStale () { return this.transferValue.transferprogressstale === true || this.transferValue.transferprogressstale === 'true' },
    transferProgressStatus () {
      const complete = ['COMPLETE', 'COMPLETED'].includes(String(this.transferValue.transferactivitystate || '').toUpperCase())
      return this.transferProgressStale ? 'exception' : (complete && this.transferPercent >= 100 ? 'success' : 'active')
    },
    normalizedSteps () {
      return this.steps.length ? this.steps : (this.run.steps || [])
    },
    effectiveRunState () {
      const runtime = String(this.run.runtimestate || '').toUpperCase()
      const worker = String(this.run.workerstate || '').toUpperCase()
      if (['ERROR', 'FAILED'].includes(runtime) || worker === 'FAILED' || this.run.runtimeerrorcode) {
        return 'FAILED'
      }
      return this.run.state || runtime || 'UNKNOWN'
    }
  },
  methods: {
    formatStepError (step) {
      const code = step && (step.errorcode || step.errorCode)
      if (!code) return step && step.errormessage ? step.errormessage : ''
      const key = `message.dr.error.${String(code).toLowerCase().replace(/_/g, '.')}`
      return this.$te && this.$te(key) ? this.$t(key) : (step.errormessage || code)
    },
    isValidTransferValue (value, planScoped = false) {
      if (!hasDrTransferProgress(value) || value.completedcycleprojected) return false
      const runId = this.run.id || this.run.uuid || ''
      const owner = value.transferrunuuid || ''
      const reverse = String(this.run.runtype || this.run.runType || '').toUpperCase() === 'FAILBACK'
      // Plan samples belong to a scheduler cycle, not necessarily this operation.
      // Never compare sequence numbers from unrelated producers to select a winner.
      if ((planScoped || reverse || owner) && (!runId || owner !== runId)) return false
      if (this.run.planid && value.transferplanuuid && this.run.planid !== value.transferplanuuid) return false
      if (reverse && String(value.transferdirection || '').toUpperCase() === 'VMWARE_TO_KVM') return false
      if (planScoped && reverse && Number(this.run.transfercyclesequence || 0) > 0 &&
        Number(value.transfercyclesequence || 0) !== Number(this.run.transfercyclesequence)) return false
      return true
    },
    compareTransferValues (left, right) {
      const leftCycle = Number(left && left.transfercyclesequence || 0)
      const rightCycle = Number(right && right.transfercyclesequence || 0)
      if (leftCycle !== rightCycle) return leftCycle - rightCycle
      const leftSample = Number(left && left.transfersamplesequence || 0)
      const rightSample = Number(right && right.transfersamplesequence || 0)
      if (leftSample !== rightSample) return leftSample - rightSample
      return Number(left && left.transferprogresssampleepochms || 0) -
        Number(right && right.transferprogresssampleepochms || 0)
    },
    formatBytes (value) {
      let bytes = Number(value || 0)
      if (!Number.isFinite(bytes) || bytes < 0) return '-'
      const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
      let index = 0
      while (bytes >= 1024 && index < units.length - 1) {
        bytes /= 1024
        index += 1
      }
      return `${bytes.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
    },
    formatRate (value) {
      return `${this.formatBytes(value)}/s`
    },
    formatDuration (seconds) {
      const value = Number(seconds || 0)
      if (!Number.isFinite(value) || value <= 0) return '-'
      if (value < 60) return `${Math.ceil(value)}s`
      const minutes = Math.floor(value / 60)
      const remain = Math.ceil(value % 60)
      return `${minutes}m ${remain}s`
    }
  }
}
</script>

<style lang="less">
.cross-dr-run-progress {
  padding: 12px;
  border: 1px solid var(--cross-dr-border, #e8e8e8);
  border-radius: 6px;
  background: var(--cross-dr-surface, #ffffff);
}

.cross-dr-run-progress__head,
.cross-dr-run-step__main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.cross-dr-run-progress__title {
  color: var(--cross-dr-text, rgba(0, 0, 0, 0.85));
  font-weight: 600;
  line-height: 22px;
}

.cross-dr-run-progress__meta,
.cross-dr-run-step__meta,
.cross-dr-run-progress__unknown,
.cross-dr-run-progress__notice {
  color: var(--cross-dr-text-secondary, rgba(0, 0, 0, 0.45));
  font-size: 12px;
  line-height: 18px;
}

.cross-dr-run-progress__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
}

.cross-dr-run-progress__workflow {
  margin-top: 10px;
}

.cross-dr-run-progress__workflow-label {
  display: flex;
  justify-content: space-between;
  color: var(--cross-dr-text-secondary, rgba(0, 0, 0, 0.45));
  font-size: 12px;
}

.cross-dr-run-progress__unknown {
  margin-top: 10px;
}

.cross-dr-transfer-progress {
  margin-top: 12px;
  padding: 10px;
  border: 1px solid var(--cross-dr-border, #e8e8e8);
  border-radius: 6px;
  background: var(--cross-dr-surface-muted, #fafafa);
}

.cross-dr-transfer-progress__head,
.cross-dr-transfer-progress__meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 12px;
}

.cross-dr-transfer-progress__head {
  color: var(--cross-dr-text, rgba(0, 0, 0, 0.85));
  font-weight: 600;
}

.cross-dr-transfer-progress__meta {
  color: var(--cross-dr-text-secondary, rgba(0, 0, 0, 0.45));
  font-size: 12px;
}

.cross-dr-transfer-progress__stale {
  margin-top: 6px;
  color: var(--cross-dr-warning-text, #874d00);
  font-size: 12px;
}

.cross-dr-transfer-progress__preparing {
  margin-top: 6px;
  color: var(--cross-dr-text-secondary, rgba(0, 0, 0, 0.45));
  font-size: 12px;
}

.cross-dr-run-progress__notice {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--cross-dr-warning-border, #ffe58f);
  border-radius: 6px;
  background: var(--cross-dr-warning-bg, #fffbe6);
  color: var(--cross-dr-warning-text, #874d00);
}

.cross-dr-run-progress__notice--info {
  border-color: var(--cross-dr-info-border, #91d5ff);
  background: var(--cross-dr-info-bg, #e6f7ff);
  color: var(--cross-dr-info-text, #0050b3);
}

.cross-dr-run-progress__notice--success {
  border-color: var(--cross-dr-success-border, #b7eb8f);
  background: var(--cross-dr-success-bg, #f6ffed);
  color: var(--cross-dr-success-text, #237804);
}

.cross-dr-run-progress__steps {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.cross-dr-run-step {
  padding: 8px 10px;
  border: 1px solid var(--cross-dr-border, #e8e8e8);
  border-radius: 6px;
  background: var(--cross-dr-surface-muted, #fafafa);
}

.cross-dr-run-step__name {
  color: var(--cross-dr-text, rgba(0, 0, 0, 0.85));
  font-weight: 600;
}

body.dark-mode .cross-dr-run-progress,
body.dark-mode .cross-dr-run-step {
  --cross-dr-border: rgba(255, 255, 255, 0.12);
  --cross-dr-surface: rgba(255, 255, 255, 0.04);
  --cross-dr-surface-muted: rgba(255, 255, 255, 0.06);
  --cross-dr-text: rgba(255, 255, 255, 0.86);
  --cross-dr-text-secondary: rgba(255, 255, 255, 0.58);
  --cross-dr-warning-bg: rgba(250, 173, 20, 0.12);
  --cross-dr-warning-border: rgba(250, 173, 20, 0.38);
  --cross-dr-warning-text: #ffe58f;
  --cross-dr-success-bg: rgba(82, 196, 26, 0.12);
  --cross-dr-success-border: rgba(82, 196, 26, 0.42);
  --cross-dr-success-text: #b7eb8f;
  --cross-dr-info-bg: rgba(24, 144, 255, 0.12);
  --cross-dr-info-border: rgba(24, 144, 255, 0.42);
  --cross-dr-info-text: #91d5ff;
}
</style>
