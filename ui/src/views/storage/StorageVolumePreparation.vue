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
      <a-select :value="selectedVolume" :placeholder="$t('label.storage.service.backing.volume')" style="min-width: 240px" @change="selectVolume">
        <a-select-option v-for="volume in volumes" :key="volume.id" :value="volume.id">{{ volume.name || volume.id }}</a-select-option>
      </a-select>
      <a-button :loading="loading" :disabled="!selectedVolume" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-switch :checked="automatic" :checked-children="$t('label.storage.volume.auto.observe')" :un-checked-children="$t('label.storage.volume.auto.observe')" @change="setAutomatic" />
    </a-space>
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.volume.observe.failed')" />
    <a-descriptions v-if="observation" bordered :column="{ xs: 1, sm: 2 }" size="small" aria-live="polite">
      <a-descriptions-item :label="$t('label.state')"><a-tag :color="phaseColor">{{ observation.status || operation.phase || '-' }}</a-tag></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.formatter.active')">{{ observation.formatterActive ? $t('label.yes') : $t('label.no') }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.operation')"><code>{{ observation.operationId || operation.operationId || '-' }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.deadline')">{{ operation.formatDeadlineSeconds ?? '-' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.current.device')"><code>{{ identity.observedDevicePath || '-' }}</code> · {{ observation.currentIdentityStatus || 'UNAVAILABLE' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.fs.uuid')"><code>{{ identity.filesystemUuid || operation.filesystemUuid || '-' }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.historical.device')"><code>{{ operation.devicePath || '-' }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.volume.mount')"><code>{{ identity.mountPath || operation.mountPath || '-' }}</code></a-descriptions-item>
    </a-descriptions>
    <a-alert v-if="observation" type="info" show-icon :message="$t('message.storage.volume.historical.device')" />
    <a-alert v-if="operation.diagnostic || observation.currentIdentityDiagnostic" type="warning" show-icon :message="operation.diagnostic || observation.currentIdentityDiagnostic" />
  </section>
</template>
<script>
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
const parse = value => { try { return typeof value === 'string' ? JSON.parse(value) : (value || {}) } catch (error) { return {} } }
const terminal = new Set(['COMPLETE', 'RECOVERY_REQUIRED', 'RECONCILE_REQUIRED', 'NOT_STARTED', 'IDENTITY_MISMATCH', 'ERROR'])
export default {
  name: 'StorageVolumePreparation',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true }, volumes: { type: Array, default: () => [] } },
  data: () => ({ selectedVolume: '', observation: null, loading: false, readError: false, automatic: false, generation: 0, timer: null, disposed: false }),
  computed: {
    operation () { return this.observation?.operation || {} },
    identity () { return this.observation?.currentIdentity || {} },
    phaseColor () {
      const state = this.observation?.status || this.operation.phase
      if (state === 'COMPLETE') return 'green'
      if (['RECOVERY_REQUIRED', 'IDENTITY_MISMATCH', 'ERROR'].includes(state)) return 'red'
      return 'orange'
    }
  },
  watch: {
    instanceId () { this.invalidate(); this.selectedVolume = ''; this.automatic = false },
    volumes () { if (!this.volumes.some(volume => volume.id === this.selectedVolume)) { this.invalidate(); this.selectedVolume = ''; this.automatic = false } }
  },
  beforeUnmount () { this.disposed = true; this.invalidate() },
  methods: {
    invalidate () { this.generation++; clearTimeout(this.timer); this.timer = null; this.observation = null; this.readError = false; this.loading = false },
    selectVolume (id) { this.invalidate(); this.selectedVolume = id; this.refresh() },
    setAutomatic (enabled) { this.automatic = enabled; clearTimeout(this.timer); this.timer = null; if (enabled) this.refresh() },
    async refresh () {
      if (this.loading || this.disposed || !this.instanceId || !this.selectedVolume) return
      clearTimeout(this.timer); this.timer = null
      const generation = this.generation; const instance = this.instanceId; const volume = this.selectedVolume
      this.loading = true
      try {
        const result = await getAPI('getStorageServiceVolumePreparation', { instanceid: instance, volumeid: volume }, { timeout: 15000, preserveOnFailure: true })
        if (this.disposed || generation !== this.generation || instance !== this.instanceId || volume !== this.selectedVolume) return
        const envelope = result.getstorageservicevolumepreparationresponse
        const response = envelope?.storageserviceruntime || envelope
        if (!response || response.success !== true) throw new Error('Volume preparation observation unavailable')
        const observed = parse(response.resultjson)
        if (!observed.status || typeof observed.status !== 'string' || (observed.operation?.volumeUuid && observed.operation.volumeUuid !== volume)) throw new Error('Invalid volume preparation observation')
        this.observation = observed
        this.readError = false
      } catch (error) {
        if (!this.disposed && generation === this.generation) this.readError = true
      } finally {
        if (!this.disposed && generation === this.generation) {
          this.loading = false
          // Only one bounded read runs at a time. A failed observation requires an explicit retry.
          if (this.automatic && !this.readError && !terminal.has(this.observation?.status || this.operation.phase)) this.timer = setTimeout(() => this.refresh(), 5000)
        }
      }
    }
  }
}
</script>
<style scoped>
.storage-volume-preparation > * + * { margin-top: 12px; }
</style>
