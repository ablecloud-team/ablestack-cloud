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
      <a-button :loading="loading" :disabled="!!busy" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
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
        <a-descriptions-item :label="$t('label.storage.operation.observed.at')">{{ observedAt || '—' }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="control.controlSupported !== true" type="info" show-icon :message="$t('message.storage.operation.control.unsupported')" />
      <a-alert v-if="control.drainRequired === true && control.drainSupported !== true" type="warning" show-icon :message="$t('message.storage.operation.drain.unsupported')">
        <template #description><span v-if="control.maintenanceAlternative === 'APPROVED_MAINTENANCE_PLAN_REQUIRED'">{{ $t('message.storage.operation.maintenance.plan.required') }}</span></template>
      </a-alert>
      <a-alert v-if="control.observed?.sessionObservationKind === 'TCP_CONNECTIONS_ONLY'" type="info" show-icon :message="$t('message.storage.operation.tcp.connections.only')" />
      <a-table size="small" :columns="requirementColumns" :data-source="requirementRows" :pagination="false" row-key="key" />
      <a-alert v-for="blocker in (control.blockers || [])" :key="blocker" type="warning" show-icon :message="blocker" />
      <a-space v-if="control.drainRequired === true && can('drainStorageServiceOperation')" wrap>
        <a-input v-model:value="confirmation" :placeholder="instanceName" :disabled="!!busy" autocomplete="off" />
        <a-button :disabled="!canDrain" @click="request('drain')">{{ $t('label.storage.operation.drain.request') }}</a-button>
      </a-space>
      <a-alert type="info" show-icon :message="$t('message.storage.operation.cancel.boundary')" />
      <a-alert type="info" show-icon :message="$t('message.storage.control.policy.logical')" />
    </template>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { createListRefresh, canRefreshList } from '@/utils/listRefresh'

const securityScope = vm => [vm.$store?.getters?.userInfo?.id || '', vm.$store?.getters?.project?.id || ''].join('|')
const nonnegative = value => typeof value === 'number' && Number.isFinite(value) && value >= 0
export default {
  name: 'StorageOperationControl',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true }, operationId: { type: String, required: true }, instanceName: { type: String, default: '' } },
  emits: ['operation-updated'],
  data: () => ({ generation: 0, readSequence: 0, disposed: false, control: null, loading: false, readError: false, error: '', busy: '', confirmation: '', observedAt: '' }),
  computed: {
    operationSecurityScope () { return securityScope(this) },
    writable () { return !this.disposed && !this.loading && !this.busy && !this.readError && this.control?.controlSupported === true && this.control.operationUuid === this.operationId && Number.isSafeInteger(this.control.controlRevision) && this.control.controlRevision >= 0 },
    canCancel () { return this.writable && this.can('cancelStorageServiceOperation') && this.control.cancelable === true && this.control.cancelRequested !== true },
    canDrain () { return this.writable && this.can('drainStorageServiceOperation') && this.control.cancelable === true && this.control.drainRequired === true && this.control.drainSupported === true && this.control.drainState === 'NOT_REQUESTED' && !!this.instanceName && this.confirmation === this.instanceName },
    requirementColumns () { return [{ title: this.$t('label.type'), dataIndex: 'label' }, { title: this.$t('label.storage.operation.required'), dataIndex: 'required' }, { title: this.$t('label.storage.operation.observed'), dataIndex: 'observed' }] },
    requirementRows () {
      const observed = this.control?.observed || {}
      const loadPerCpu = nonnegative(observed.loadOneMinute) && Number.isSafeInteger(observed.onlineCpuCount) && observed.onlineCpuCount > 0 ? observed.loadOneMinute / observed.onlineCpuCount : undefined
      return [
        ['memory', 'minimumMemoryAvailableBytes', 'memoryAvailableBytes'],
        ['staging', 'stagingRequiredBytes', 'stagingFreeBytes'],
        ['load', 'maxLoadPerCpu', 'loadOneMinute'],
        ['sessions', 'requireSessionDrain', 'activeSessions']
      ].map(([key, requirement, observation]) => ({ key, label: this.$t(key === 'sessions' && observed.sessionObservationKind === 'TCP_CONNECTIONS_ONLY' ? 'label.storage.operation.resource.tcp.connections' : 'label.storage.operation.resource.' + key), required: this.control?.requirements?.[requirement] ?? '—', observed: (key === 'load' ? loadPerCpu : observed[observation]) ?? '—' }))
    }
  },
  watch: {
    instanceId () { this.invalidate(); this.refresh() },
    operationId () { this.invalidate(); this.refresh() },
    operationSecurityScope () { this.invalidate(); this.refresh() },
    '$store.state.user.token' (token) { this.invalidate(); if (token) this.refresh() }
  },
  mounted () {
    this.refresh()
    this.observationRefresh = createListRefresh({
      interval: 5000,
      active: () => !this.disposed && !this.busy && !this.loading && (this.control?.state === 'RUNNING' || this.readError) && canRefreshList(this.$el),
      refresh: async () => { await this.refresh(); if (this.readError) throw new Error('Operation control observation unavailable') }
    })
  },
  beforeUnmount () { this.disposed = true; this.generation++; this.readSequence++; if (this.observationRefresh) this.observationRefresh.stop() },
  methods: {
    can (api) { return api in (this.$store?.getters?.apis || {}) },
    invalidate () { this.generation++; this.readSequence++; this.control = null; this.loading = false; this.readError = false; this.error = ''; this.busy = ''; this.confirmation = ''; this.observedAt = '' },
    unwrap (response, api) {
      const root = response[api.toLowerCase() + 'response']?.storageserviceruntime
      if (root?.success !== true) throw new Error(root?.errormessage || 'Operation control observation unavailable')
      const result = typeof root.resultjson === 'string' ? JSON.parse(root.resultjson) : root.resultjson
      if (root.id !== this.instanceId || !result || Array.isArray(result) || result.operationUuid !== this.operationId || (result.instanceUuid !== undefined && result.instanceUuid !== this.instanceId)) throw new Error('Operation control scope mismatch')
      if (!Number.isSafeInteger(result.controlRevision) || result.controlRevision < 0 || typeof result.state !== 'string' || !result.state || typeof result.phase !== 'string' || !result.phase ||
        ['controlSupported', 'cancelable', 'cancelRequested', 'drainRequired'].some(key => typeof result[key] !== 'boolean') ||
        (result.drainSupported !== undefined && typeof result.drainSupported !== 'boolean') ||
        (result.maintenanceAlternative !== undefined && !['NONE', 'APPROVED_MAINTENANCE_PLAN_REQUIRED'].includes(result.maintenanceAlternative))) throw new Error('Operation control observation unavailable')
      return result
    },
    async refresh () {
      if (this.disposed || !this.can('getStorageServiceOperationControl') || this.loading || this.busy) return
      const generation = this.generation; const sequence = ++this.readSequence; const instance = this.instanceId; const operation = this.operationId; const security = securityScope(this)
      const current = () => !this.disposed && generation === this.generation && sequence === this.readSequence && instance === this.instanceId && operation === this.operationId && security === securityScope(this)
      this.loading = true
      try {
        const response = await getAPI('getStorageServiceOperationControl', { instanceid: instance, operationid: operation }, { timeout: 15000, preserveOnFailure: true })
        if (!current()) return
        const control = this.unwrap(response, 'getStorageServiceOperationControl')
        if (this.control && control.controlRevision < this.control.controlRevision) throw new Error('Operation control revision moved backwards')
        this.control = control; this.readError = false; this.error = ''; this.observedAt = new Date().toISOString()
      } catch (error) { if (current()) this.readError = true } finally { if (current()) this.loading = false }
    },
    async request (action) {
      if (action === 'cancel' ? !this.canCancel : action !== 'drain' || !this.canDrain) return
      const generation = this.generation; const instance = this.instanceId; const operation = this.operationId; const security = securityScope(this)
      const current = () => !this.disposed && generation === this.generation && instance === this.instanceId && operation === this.operationId && security === securityScope(this)
      const api = action === 'cancel' ? 'cancelStorageServiceOperation' : 'drainStorageServiceOperation'
      const params = { instanceid: instance, operationid: operation, expectedrevision: this.control.controlRevision }
      if (action === 'drain') params.confirmation = this.confirmation
      this.readSequence++; this.busy = action; this.error = ''
      try {
        const response = await postAPI(api, params, { timeout: 15000, preserveOnFailure: true })
        if (!current()) return
        this.control = this.unwrap(response, api); this.readError = false; this.confirmation = ''; this.observedAt = new Date().toISOString(); this.$emit('operation-updated', instance)
      } catch (error) {
        if (current()) { this.error = this.$t('message.storage.operation.control.result.unknown'); this.readError = true }
      } finally { if (current()) this.busy = '' }
    }
  }
}
</script>
