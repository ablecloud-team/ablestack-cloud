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
  <section>
    <a-space wrap>
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-popconfirm v-if="can('cancelStorageServiceOperation')" :title="$t('message.storage.operation.cancel.confirm')" @confirm="request('cancel')">
        <a-button :disabled="!canCancel">{{ $t('label.storage.operation.cancel.request') }}</a-button>
      </a-popconfirm>
    </a-space>
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.operation.read.failed')" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <template v-if="control">
      <a-descriptions bordered size="small" :column="2">
        <a-descriptions-item :label="$t('label.state')">{{ control.state }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.phase')">{{ control.phase }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.control.revision')">{{ control.controlRevision }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.reservation')">{{ control.reservation?.state || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.cancel.request')">{{ control.cancelRequested === true ? $t('label.yes') : $t('label.no') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.drain')">{{ control.drainState || '—' }}</a-descriptions-item>
      </a-descriptions>
      <a-table size="small" :columns="requirementColumns" :data-source="requirementRows" :pagination="false" row-key="key" />
      <a-alert v-for="blocker in (control.blockers || [])" :key="blocker" type="warning" show-icon :message="blocker" />
      <a-space v-if="control.drainRequired === true && can('drainStorageServiceOperation')" wrap>
        <a-input v-model:value="confirmation" :placeholder="instanceName" :disabled="!!busy" autocomplete="off" />
        <a-button :disabled="!canDrain" @click="request('drain')">{{ $t('label.storage.operation.drain.request') }}</a-button>
      </a-space>
      <a-alert type="info" show-icon :message="$t('message.storage.operation.cancel.boundary')" />
    </template>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'StorageOperationControl',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true }, operationId: { type: String, required: true }, instanceName: { type: String, default: '' } },
  emits: ['operation-updated'],
  data: () => ({ generation: 0, control: null, loading: false, readError: false, error: '', busy: '', confirmation: '' }),
  computed: {
    writable () { return !this.loading && !this.busy && !this.readError && this.control?.operationUuid === this.operationId && Number.isSafeInteger(this.control?.controlRevision) && this.control.controlRevision >= 0 },
    canCancel () { return this.writable && this.can('cancelStorageServiceOperation') && this.control.cancelable === true && this.control.cancelRequested !== true },
    canDrain () { return this.writable && this.can('drainStorageServiceOperation') && this.control.drainRequired === true && !!this.instanceName && this.confirmation === this.instanceName },
    requirementColumns () { return [{ title: this.$t('label.type'), dataIndex: 'label' }, { title: this.$t('label.storage.operation.required'), dataIndex: 'required' }, { title: this.$t('label.storage.operation.observed'), dataIndex: 'observed' }] },
    requirementRows () {
      const observed = this.control?.observed || {}
      const loadPerCpu = typeof observed.loadOneMinute === 'number' && Number.isSafeInteger(observed.onlineCpuCount) && observed.onlineCpuCount > 0 ? observed.loadOneMinute / observed.onlineCpuCount : undefined
      return [
        ['memory', 'minimumMemoryAvailableBytes', 'memoryAvailableBytes'],
        ['staging', 'stagingRequiredBytes', 'stagingFreeBytes'],
        ['load', 'maxLoadPerCpu', 'loadOneMinute'],
        ['sessions', 'requireSessionDrain', 'activeSessions']
      ].map(([key, requirement, observation]) => ({ key, label: this.$t('label.storage.operation.resource.' + key), required: this.control?.requirements?.[requirement] ?? '—', observed: (key === 'load' ? loadPerCpu : observed[observation]) ?? '—' }))
    }
  },
  watch: { instanceId () { this.invalidate(); this.refresh() }, operationId () { this.invalidate(); this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++ },
  methods: {
    can (api) { return api in (this.$store?.getters?.apis || {}) },
    invalidate () { this.generation++; this.control = null; this.loading = false; this.readError = false; this.error = ''; this.busy = ''; this.confirmation = '' },
    unwrap (response, api) {
      const root = response[api.toLowerCase() + 'response']?.storageserviceruntime
      if (root?.success !== true) throw new Error(root?.errormessage || 'Operation control observation unavailable')
      const result = typeof root.resultjson === 'string' ? JSON.parse(root.resultjson) : root.resultjson
      if (!result || result.operationUuid !== this.operationId) throw new Error('Operation control scope mismatch')
      return result
    },
    async refresh () {
      if (!this.can('getStorageServiceOperationControl') || this.loading) return
      const generation = this.generation; const instance = this.instanceId; const operation = this.operationId
      this.loading = true
      try {
        const response = await getAPI('getStorageServiceOperationControl', { instanceid: instance, operationid: operation }, { timeout: 15000, preserveOnFailure: true })
        if (generation !== this.generation || instance !== this.instanceId || operation !== this.operationId) return
        this.control = this.unwrap(response, 'getStorageServiceOperationControl'); this.readError = false
      } catch (error) { if (generation === this.generation) this.readError = true } finally { if (generation === this.generation) this.loading = false }
    },
    async request (action) {
      if (action === 'cancel' ? !this.canCancel : action !== 'drain' || !this.canDrain) return
      const generation = this.generation; const instance = this.instanceId; const operation = this.operationId
      const api = action === 'cancel' ? 'cancelStorageServiceOperation' : 'drainStorageServiceOperation'
      const params = { instanceid: instance, operationid: operation, expectedrevision: this.control.controlRevision }
      if (action === 'drain') params.confirmation = this.confirmation
      this.busy = action; this.error = ''
      try {
        const response = await postAPI(api, params, { timeout: 15000 })
        if (generation !== this.generation || instance !== this.instanceId || operation !== this.operationId) return
        this.control = this.unwrap(response, api); this.confirmation = ''; this.$emit('operation-updated', instance)
      } catch (error) {
        if (generation === this.generation) { this.error = error.message; this.readError = true }
      } finally { if (generation === this.generation) this.busy = '' }
    }
  }
}
</script>
