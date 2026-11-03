<!-- Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License. -->
<template>
  <span v-if="active" class="clone-flatten-control" :class="{ 'clone-flatten-control--status': showStatusLabel }" @click.stop @dblclick.stop>
    <a-popover v-model:visible="visible" trigger="click" placement="bottomLeft">
      <template #content>
        <div class="clone-flatten-panel" @click.stop>
          <div class="clone-flatten-panel__heading">
            <div class="clone-flatten-panel__status">
              <span class="clone-flatten-panel__status-label">{{ $t('label.status') }} :</span>
              <a-tag :color="phaseColor">{{ statusLabel }}</a-tag>
            </div>
            <a-tooltip :title="$t('label.refresh')">
              <a-button type="text" size="small" :loading="refreshing" :aria-label="$t('label.refresh')" @click="refresh">
                <template #icon><reload-outlined /></template>
              </a-button>
            </a-tooltip>
          </div>
          <div v-if="description" class="clone-flatten-panel__description">{{ description }}</div>
          <dl v-if="isClone || hasVolumeInfo" class="clone-flatten-panel__disk">
            <dt>{{ $t('label.type') }} :</dt>
            <dd>{{ volumeType }}</dd>
            <dt>{{ $t('label.name') }} :</dt>
            <dd>{{ current.clonefastflattenvolumename || '-' }}</dd>
            <dt>{{ $t('label.deviceid') }} :</dt>
            <dd>{{ current.clonefastflattendeviceid ?? '-' }}</dd>
            <dt>{{ $t('label.progress') }} :</dt>
            <dd class="clone-flatten-panel__progress-row">
              <a-progress
                v-if="progress !== null"
                class="clone-flatten-panel__progress"
                :percent="progress"
                :status="failed ? 'exception' : running ? 'active' : 'normal'"
                :show-info="false"
                role="progressbar"
                :aria-label="$t('label.progress')"
                :aria-valuenow="progress"
                :aria-valuemin="0"
                :aria-valuemax="100"
                size="small" />
              <span class="clone-flatten-panel__progress-value">{{ progress === null ? '-' : progress.toFixed(2) + '%' }}</span>
            </dd>
          </dl>
          <div v-if="isClone" class="clone-flatten-panel__bandwidth">
            <label :for="'flatten-bandwidth-' + current.id">{{ $t('label.clone.flatten.bandwidth') }}</label>
            <div class="clone-flatten-panel__input">
              <a-input-number
                :id="'flatten-bandwidth-' + current.id"
                v-model:value="draft"
                :min="0"
                :max="2147483647"
                :precision="0"
                :step="10"
                :disabled="!canEdit || busy"
                @change="dirty = true"
                @pressEnter="apply" />
              <span>MiB/s</span>
              <a-tooltip :title="$t('label.apply')">
                <a-button
                  type="primary"
                  :loading="busy"
                  :disabled="!canEdit || !validDraft"
                  :aria-label="$t('label.apply')"
                  @click="apply">
                  <template #icon><check-outlined /></template>
                  {{ $t('label.apply') }}
                </a-button>
              </a-tooltip>
            </div>
            <div class="clone-flatten-panel__hint">{{ $t('message.clone.flatten.bandwidth.units') }}</div>
            <div v-if="bandwidthDisabledReason" class="clone-flatten-panel__hint" role="status">{{ $t(bandwidthDisabledReason) }}</div>
            <div v-if="bandwidthStatus" class="clone-flatten-panel__hint" :class="{ 'clone-flatten-panel__error': bandwidthStatus === 'failed' }" :role="bandwidthStatus === 'failed' ? 'alert' : undefined">
              {{ $t('message.clone.flatten.bandwidth.' + bandwidthStatus) }}
            </div>
          </div>
          <div v-if="error && (bandwidthStatus !== 'failed' || error !== $t('message.clone.flatten.bandwidth.failed'))" class="clone-flatten-panel__error" role="alert">{{ error }}</div>
        </div>
      </template>
      <a-tooltip :title="visible ? '' : (bandwidthStatus === 'failed' ? $t('message.clone.flatten.bandwidth.failed') : statusLabel)">
        <a-button size="small" class="clone-flatten-button" :danger="failed" :aria-label="showStatusLabel ? statusLabel : 'Flatten'" :aria-expanded="visible">
          <template #icon>
            <exclamation-circle-outlined v-if="failed || bandwidthStatus === 'failed'" />
            <sync-outlined v-else :spin="running" />
          </template>
          {{ showStatusLabel ? statusLabel : 'Flatten' }}
        </a-button>
      </a-tooltip>
    </a-popover>
  </span>
</template>

<script>
import { CheckOutlined, ExclamationCircleOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons-vue'
import { getAPI, postAPI } from '@/api'
import { getFastClonePhase, getFastClonePhaseLabel, getFastClonePhaseDescription, getFastClonePhaseColor, isFastCloneFlattenStatusVisible } from '@/utils/fastClone'

export default {
  name: 'CloneFlattenControl',
  components: { CheckOutlined, ExclamationCircleOutlined, ReloadOutlined, SyncOutlined },
  props: {
    record: { type: Object, required: true },
    showStatusLabel: { type: Boolean, default: false }
  },
  emits: ['refresh'],
  data () {
    return { visible: false, latest: null, draft: null, dirty: false, busy: false, refreshing: false, error: '', timer: null, sequence: 0, disposed: false }
  },
  computed: {
    current () { return this.latest || this.record },
    phase () { return getFastClonePhase(this.current) },
    cloneStatus () { return String(this.current.clonefaststatus || this.current.details?.['clone.fast.status'] || '').toLowerCase() },
    active () { return isFastCloneFlattenStatusVisible(this.current) },
    hasVolumeInfo () {
      return [this.current.clonefastflattenvolumetype, this.current.clonefastflattenvolumename, this.current.clonefastflattendeviceid]
        .some(value => value !== undefined && value !== null && value !== '')
    },
    isClone () { return this.phase ? this.phase.startsWith('clone_') : this.hasVolumeInfo },
    volumeType () { return this.current.clonefastflattenvolumetype ? this.current.clonefastflattenvolumetype + ' ' + this.$t('label.volume') : '-' },
    failed () { return this.phase.endsWith('_failed') },
    running () { return !this.failed && this.cloneStatus === 'running' && this.phase !== 'clone_paused' },
    phaseColor () { return getFastClonePhaseColor(this.current) },
    statusLabel () {
      const label = getFastClonePhaseLabel(this.current)
      return this.$t(label || (this.running ? 'label.sharedmountpoint.clone.flatten.running' : 'label.sharedmountpoint.clone.flatten.pending'))
    },
    description () {
      const label = getFastClonePhaseDescription(this.current)
      return label ? this.$t(label) : ''
    },
    progress () {
      const raw = this.current.clonefastflattenprogress ?? this.current.details?.['clone.fast.flatten.progress']
      if (!this.isClone || raw == null || raw === '') return null
      const value = Number(raw)
      return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : null
    },
    bandwidthStatus () {
      const status = this.current.clonefastflattenbandwidthstatus
      return ['applying', 'applied', 'pending', 'failed'].includes(status) ? status : ''
    },
    bandwidthDisabledReason () {
      if (!this.phase || !Number.isInteger(this.current.clonefastflattenbandwidth) || this.current.clonefastflattenbandwidth < 0) {
        return 'message.clone.flatten.bandwidth.metadata.missing'
      }
      if (!this.$store.getters.apis?.updateVmCloneFlattenBandwidth) return 'message.clone.flatten.bandwidth.api.unavailable'
      if (!['clone_ready', 'clone_paused'].includes(this.phase) || !['Running', 'Stopped'].includes(this.current.state)) {
        return 'message.clone.flatten.bandwidth.phase.blocked'
      }
      return ''
    },
    canEdit () { return this.isClone && !this.bandwidthDisabledReason && this.bandwidthStatus !== 'applying' },
    validDraft () { return Number.isInteger(this.draft) && this.draft >= 0 && this.draft <= 2147483647 }
  },
  watch: {
    record (value, previous) {
      this.sequence++
      this.latest = null
      this.refreshing = false
      if (value.id !== previous.id) {
        this.visible = false
        this.busy = false
        this.dirty = false
        this.error = ''
      }
      if (!this.dirty) this.draft = this.current.clonefastflattenbandwidth ?? null
    },
    active (value) { if (!value) this.visible = false },
    visible (value) {
      clearInterval(this.timer)
      window.removeEventListener('resize', this.closePopover)
      if (value) {
        this.dirty = false
        this.draft = this.current.clonefastflattenbandwidth ?? null
        this.refresh()
        this.timer = setInterval(() => this.refresh(), 5000)
        window.addEventListener('resize', this.closePopover)
      }
    }
  },
  beforeUnmount () {
    this.disposed = true
    this.sequence++
    clearInterval(this.timer)
    window.removeEventListener('resize', this.closePopover)
  },
  methods: {
    closePopover () { this.visible = false },
    async refresh () {
      const sequence = ++this.sequence
      const id = this.record.id
      this.refreshing = true
      try {
        const json = await getAPI('listVirtualMachines', { id, details: 'min' })
        if (this.disposed || sequence !== this.sequence || id !== this.record.id) return
        const vm = json.listvirtualmachinesresponse?.virtualmachine?.[0]
        if (!vm) throw new Error(this.$t('message.clone.flatten.refresh.failed'))
        this.latest = vm
        if (this.error === this.$t('message.clone.flatten.refresh.failed')) this.error = ''
        if (!this.dirty) this.draft = vm.clonefastflattenbandwidth ?? null
      } catch (error) {
        if (!this.disposed && sequence === this.sequence) this.error = this.$t('message.clone.flatten.refresh.failed')
      } finally {
        if (!this.disposed && sequence === this.sequence) this.refreshing = false
      }
    },
    async apply () {
      if (this.busy || !this.canEdit || !this.validDraft) return
      this.busy = true
      this.error = ''
      const id = this.record.id
      const bandwidth = this.draft
      const finish = (failed) => {
        if (this.disposed || id !== this.record.id) return
        this.busy = false
        this.dirty = failed
        this.error = failed ? this.$t('message.clone.flatten.bandwidth.failed') : ''
        this.refresh()
        this.$emit('refresh')
      }
      try {
        const json = await postAPI('updateVmCloneFlattenBandwidth', { id, bandwidth })
        const jobId = json.updatevmcloneflattenbandwidthresponse?.jobid
        if (!jobId) throw new Error('Missing bandwidth update job')
        this.$pollJob({
          jobId,
          title: this.$t('label.clone.flatten.bandwidth'),
          name: this.current.displayname || this.current.name,
          resourceId: id,
          showLoading: false,
          successMessage: this.$t('message.clone.flatten.bandwidth.saved'),
          successMethod: () => finish(false),
          errorMethod: () => finish(true),
          catchMethod: () => finish(true)
        })
      } catch (error) {
        finish(true)
        this.$notifyError(error)
      }
    }
  }
}
</script>

<style scoped lang="less">
.clone-flatten-control { display: inline-flex; vertical-align: middle; margin-left: 6px; }
.clone-flatten-button {
  height: 20px;
  padding: 0 7px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 18px;
  :deep(.anticon + span) { margin-left: 4px; }
}
.clone-flatten-control--status {
  margin-left: 0;
  max-width: 100%;
  .clone-flatten-button { height: auto; min-height: 20px; max-width: 100%; white-space: normal; text-align: left; }
}
.clone-flatten-panel {
  width: 340px;
  max-width: calc(100vw - 48px);
  font-size: 13px;
  overflow-wrap: anywhere;
  .ant-tag { max-width: 100%; white-space: normal; margin: 8px 0; }
}
.clone-flatten-panel__heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  .ant-tag { margin: 0; }
}
.clone-flatten-panel__description { line-height: 1.5; margin-bottom: 10px; }
.clone-flatten-panel__status {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  .ant-tag { justify-self: start; }
}
.clone-flatten-panel__status-label { color: #666; }
.clone-flatten-panel__disk {
  display: grid;
  grid-template-columns: 60px minmax(0, 1fr);
  gap: 4px 6px;
  margin: 8px 0;
  dt { color: #666; }
  dd { margin: 0; }
}
.clone-flatten-panel__bandwidth { border-top: 1px solid #e8e8e8; margin-top: 12px; padding-top: 12px; }
.clone-flatten-panel__progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.clone-flatten-panel__progress-value { flex-shrink: 0; white-space: nowrap; }
.clone-flatten-panel__progress {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  :deep(.ant-progress-outer) { flex: 1; min-width: 0; margin-right: 0; padding-right: 0; }
}
.clone-flatten-panel__input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
  margin-top: 6px;
  .ant-input-number { width: 100%; }
  .ant-btn { min-width: 76px; }
}
.clone-flatten-panel__hint { color: #666; margin-top: 6px; font-size: 12px; }
.clone-flatten-panel__error { color: #cf1322; margin-top: 8px; }
</style>
