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
  <section class="storage-table-section storage-boot-reconcile">
    <h4>{{ $t('label.storage.boot.title') }}</h4>
    <a-space wrap>
      <a-tag :color="statusColor" role="status">{{ $t('label.storage.boot.status.' + status) }}</a-tag>
      <a-button :loading="loading" :disabled="pending || !active || !canRead" @click="refresh(true)"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="readError || status === 'UNKNOWN'" type="warning" show-icon :message="$t(readError ? 'message.storage.boot.read.failed' : 'message.storage.boot.unavailable')" />
    <a-alert v-if="lifetimeEnded" type="info" show-icon :message="$t('message.storage.boot.auto.limit')" />
    <template v-if="observation">
      <a-alert v-if="observation.phase === 'SKIPPED'" type="info" show-icon :message="$t('message.storage.boot.skipped')" />
      <a-alert v-else-if="observation.runtimeReady && !observation.detailedVerified" type="info" show-icon :message="$t('message.storage.boot.probes.pending')" />
      <a-descriptions bordered size="small" :column="2" aria-live="polite">
        <a-descriptions-item :label="$t('label.storage.operation.phase')">{{ $t('label.storage.boot.phase.' + observation.phase) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.attempt')">{{ observation.attempt }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.runtime.ready')">{{ bool(observation.runtimeReady) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.detailed.verified')">{{ bool(observation.detailedVerified) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.elapsed')">{{ observation.elapsedSeconds }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.remaining')">{{ observation.remainingSeconds }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.deadline')">{{ observation.deadlineSeconds }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.updated')">{{ time(observation.updatedEpoch) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.observed.at')">{{ time(health.generatedEpoch) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.boot.id')"><code>{{ observation.bootId }}</code></a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="hasAclSkips" type="warning" show-icon :message="$t('message.storage.boot.acl.skipped')" />
      <a-table size="small" :scroll="{ x: 1050 }" :columns="columns" :data-source="protocolRows" :pagination="false" row-key="protocol" />
      <a-descriptions v-if="observation.lastFailure" :title="$t('label.storage.boot.last.failure')" bordered size="small" :column="2">
        <a-descriptions-item :label="$t('label.type')">{{ protocolName(observation.lastFailure.protocol) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.operation.phase')">{{ $t('label.storage.boot.phase.' + observation.lastFailure.phase) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.endpoint')">{{ failureEndpoint }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.export')">{{ observation.lastFailure.exportName || observation.lastFailure.exportUuid || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.mode')">{{ observation.lastFailure.protocolMode || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.volume.diagnostic.code')"><code>{{ observation.lastFailure.code }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.timeout')">{{ bool(observation.lastFailure.timedOut) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.retryable')">{{ bool(observation.lastFailure.retryable) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.cleanup')">{{ bool(observation.lastFailure.cleanup.complete) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.child')">{{ bool(observation.lastFailure.cleanup.childActive) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.failure.mount')">{{ bool(observation.lastFailure.cleanup.mountPresent) }}</a-descriptions-item>
      </a-descriptions>
    </template>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { createListRefresh, canRefreshList } from '@/utils/listRefresh'
import { bootReconcileObservation, bootProtocols } from '@/utils/storageBootReconcile'

const security = vm => JSON.stringify([vm.$store?.getters?.userInfo?.id, vm.$store?.getters?.project?.id, vm.$store?.state?.user?.token])
const LIFETIME_MS = 1530000
export default {
  name: 'StorageBootReconcileStatus',
  components: { ReloadOutlined },
  props: {
    instanceId: { type: String, required: true },
    active: { type: Boolean, default: false },
    scopeKey: { type: String, default: '' }
  },
  data: () => ({ health: null, readError: false, loading: false, pending: false, generation: 0, pendingGeneration: -1, queuedRead: false, disposed: false, clock: Date.now() / 1000, startedAt: 0, lifetimeEnded: false }),
  computed: {
    securityScope () { return security(this) },
    canRead () { return !!this.$store?.getters?.userInfo?.id && !!this.$store?.state?.user?.token && 'listStorageServiceHealth' in (this.$store?.getters?.apis || {}) },
    observation () { return bootReconcileObservation(this.health, { instanceId: this.instanceId, now: this.clock, readFailed: this.readError }) },
    status () { return this.observation?.status || 'UNKNOWN' },
    statusColor () { return { RECONCILING: 'blue', READY: 'green', DEGRADED: 'orange', FAILED: 'red', HELD: 'orange', UNKNOWN: 'default' }[this.status] },
    columns () {
      return [
        ['protocolLabel', 'label.type'], ['configured', 'label.storage.boot.configured'],
        ['apply', 'label.storage.boot.apply'], ['listener', 'label.storage.boot.listener'],
        ['probe', 'label.storage.boot.probe'], ['kind', 'label.storage.boot.verification.kind'], ['skipped', 'label.storage.boot.skipped.acl'], ['count', 'label.storage.boot.probe.count'],
        ['verified', 'label.storage.boot.detailed.verified']
      ].map(([dataIndex, title]) => ({ dataIndex, key: dataIndex, title: this.$t(title) }))
    },
    hasAclSkips () { return this.observation && bootProtocols.some(protocol => this.observation.protocols[protocol].skippedAclCount > 0) },
    protocolRows () {
      return this.observation ? bootProtocols.map(protocol => {
        const row = this.observation.protocols[protocol]
        return { protocol, protocolLabel: this.protocolName(protocol), configured: this.bool(row.configured), apply: this.$t('label.storage.boot.apply.' + row.applyState), listener: row.configured && row.observationComplete ? this.bool(row.listenerReady) : '—', probe: this.$t('label.storage.boot.probe.' + row.probeState), kind: this.$t('label.storage.boot.verification.' + row.detailVerificationKind), skipped: row.configured ? row.skippedAclCount : '—', count: row.configured ? row.completedProbeCount + ' / ' + row.totalProbeCount : '—', verified: row.configured ? this.bool(row.verified) : '—' }
      }) : []
    },
    failureEndpoint () {
      const failure = this.observation?.lastFailure
      return failure?.endpointIp ? failure.endpointIp + (failure.endpointPort !== null ? ':' + failure.endpointPort : '') : '—'
    }
  },
  watch: {
    active () { this.reset(); if (this.active) this.refresh() },
    instanceId () { this.reset(); if (this.active) this.refresh() },
    scopeKey () { this.reset(); if (this.active) this.refresh() },
    securityScope () { this.reset(); if (this.active) this.refresh() },
    canRead () { this.reset(); if (this.active) this.refresh() }
  },
  mounted () { if (this.active) this.refresh() },
  beforeUnmount () { this.disposed = true; this.reset() },
  methods: {
    protocolName (value) { return { ISCSI: 'iSCSI', NVMEOF: 'NVMe-oF' }[value] || value || '—' },
    bool (value) { return this.$t(value ? 'label.yes' : 'label.no') },
    time (epoch) { return new Date(epoch * 1000).toISOString() },
    stopPolling () { if (this.observationRefresh) this.observationRefresh.stop(); this.observationRefresh = null },
    reset () {
      this.generation++; this.health = null; this.readError = false; this.loading = false; this.startedAt = 0; this.lifetimeEnded = false
      this.stopPolling(); clearTimeout(this.expiryTimer); clearTimeout(this.lifetimeTimer)
    },
    startBudget (manual) {
      if (!this.startedAt || manual) {
        this.startedAt = Date.now(); this.lifetimeEnded = false; clearTimeout(this.lifetimeTimer)
        this.lifetimeTimer = setTimeout(() => { this.lifetimeEnded = true; this.stopPolling() }, LIFETIME_MS)
      }
    },
    updatePolling () {
      this.stopPolling()
      if (this.readError || (this.observation && this.observation.status !== 'RECONCILING')) clearTimeout(this.lifetimeTimer)
      if (this.disposed || !this.active || this.readError || this.lifetimeEnded || this.observation?.status !== 'RECONCILING') return
      this.observationRefresh = createListRefresh({
        interval: 5000,
        active: () => !this.disposed && this.active && !this.loading && !this.pending && !this.readError && !this.lifetimeEnded && Date.now() - this.startedAt < LIFETIME_MS && this.observation?.status === 'RECONCILING' && canRefreshList(this.$el),
        refresh: () => this.refresh()
      })
    },
    expireObservation () {
      clearTimeout(this.expiryTimer)
      if (!this.observation) return
      const limit = Math.min(this.health.generatedEpoch + this.health.staleAfterSeconds, this.observation.status === 'RECONCILING' ? this.observation.updatedEpoch + 15 : Infinity)
      this.expiryTimer = setTimeout(() => { this.clock = Date.now() / 1000; if (!this.observation) { this.stopPolling(); clearTimeout(this.lifetimeTimer) } }, Math.max(1, (limit - Date.now() / 1000) * 1000 + 1))
    },
    async refresh (manual = false) {
      if (this.disposed || !this.active || !this.canRead || !this.instanceId) return
      if (this.pending) { if (this.pendingGeneration !== this.generation) this.queuedRead = true; return }
      if (!manual && this.lifetimeEnded) return
      this.startBudget(manual)
      const generation = this.generation; const instance = this.instanceId; const scope = this.scopeKey; const user = security(this)
      const current = () => !this.disposed && this.active && generation === this.generation && instance === this.instanceId && scope === this.scopeKey && user === security(this) && this.canRead
      this.pending = true; this.pendingGeneration = generation; this.loading = true
      try {
        const response = await getAPI('listStorageServiceHealth', { instanceid: instance }, { timeout: 15000, preserveOnFailure: true })
        if (!current()) return
        const envelope = response?.liststorageservicehealthresponse
        const rows = envelope?.storageserviceruntime
        if (!Array.isArray(rows) || rows.length !== 1 || rows[0]?.id !== instance || envelope.errorcode) throw new Error('Boot observation scope unavailable')
        const value = typeof rows[0].resultjson === 'string' ? JSON.parse(rows[0].resultjson) : rows[0].resultjson
        this.clock = Date.now() / 1000
        if (!bootReconcileObservation(value, { instanceId: instance, now: this.clock })) throw new Error('Boot observation unavailable')
        this.health = value; this.readError = false; this.expireObservation()
      } catch (error) {
        if (current()) { this.health = null; this.readError = true; clearTimeout(this.expiryTimer) }
      } finally {
        this.pending = false
        if (current()) { this.loading = false; this.updatePolling() }
        if (this.queuedRead) { this.queuedRead = false; if (!this.disposed && this.active) this.refresh() }
      }
    }
  }
}
</script>
