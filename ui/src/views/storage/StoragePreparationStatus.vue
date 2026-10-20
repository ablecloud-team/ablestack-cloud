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
  <section v-if="canRead" class="storage-preparation-status">
    <a-space wrap>
      <a-tag :color="presentation.color" role="status">{{ $t('label.storage.service.preparation.status.' + presentation.status) }}</a-tag>
      <a-button :loading="loading" :disabled="presentation.refreshDisabled" @click="refresh(true)"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert :type="presentation.type" show-icon :message="presentation.message" :description="presentation.description || undefined" />
    <a-descriptions v-if="presentation.current" bordered size="small" :column="2">
      <a-descriptions-item :label="$t('label.storage.service.preparation.purpose')">{{ $t('label.storage.service.preparation.purpose.' + purpose) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.boot.boot.id')"><code>{{ receipt.bootid || '—' }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.operation.observed.at')">{{ time(receipt.observedatmillis) }}</a-descriptions-item>
      <a-descriptions-item v-if="receipt.status !== 'READY' || presentation.ready" :label="$t('label.storage.service.preparation.reason')">{{ presentation.message }}</a-descriptions-item>
    </a-descriptions>
    <storage-preparation-diagnostics :observation="diagnosticObservation" :first-failure="!!firstFailureObservation" />
  </section>
</template>
<script>
import { storageReadDeadline } from '@/utils/storageRead'
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { readStoragePreparation, readyAtSameBoot, storagePreparationWaitable, storagePreparationReasons, storagePreparationDiagnosticSnapshot } from '@/utils/storagePreparation'
import { canRefreshList } from '@/utils/listRefresh'
import StoragePreparationDiagnostics from '@/views/storage/StoragePreparationDiagnostics'

const security = vm => JSON.stringify([vm.$store?.getters?.userInfo?.id, vm.$store?.getters?.userInfo?.roletype, vm.$store?.getters?.project?.id, vm.$store?.state?.user?.token])
export default {
  name: 'StoragePreparationStatus',
  components: { ReloadOutlined, StoragePreparationDiagnostics },
  props: {
    sharedFileSystemId: { type: String, default: '' },
    instanceId: { type: String, default: '' },
    purpose: { type: String, default: 'INITIAL_CONFIG' },
    targetBundleId: { type: String, default: '' },
    active: { type: Boolean, default: false },
    scopeKey: { type: String, default: '' }
  },
  emits: ['status'],
  data: () => ({ generation: 0, disposed: false, receipt: null, previousReceipt: null, lastObservedReceipt: null, firstFailureObservation: null, readError: false, loading: false, clock: Date.now(), startedAt: 0, automaticEnded: false }),
  computed: {
    canRead () { return !!this.$store?.getters?.userInfo?.id && !!this.$store?.state?.user?.token && 'getStorageServicePreparationStatus' in (this.$store?.getters?.apis || {}) },
    contextKey () { return JSON.stringify([this.sharedFileSystemId, this.instanceId, this.purpose, this.targetBundleId, this.scopeKey, this.active, this.canRead, security(this)]) },
    presentation () {
      // Every visible field and published readiness uses this same current
      // observation model. Historical READY reasons are never current status.
      const last = this.receipt || this.lastObservedReceipt
      const base = { current: false, ready: false, status: 'OBSERVATION_UNAVAILABLE', color: 'default', type: 'info', description: '', refreshDisabled: !this.active || !this.canRead || this.loading }
      const history = (last ? this.$t('message.storage.service.preparation.last.observed', { time: this.time(last.observedatmillis) }) + ' ' : '') + this.$t('message.storage.service.preparation.update.required')
      if (this.readError) return { ...base, type: 'warning', message: this.$t('message.storage.service.preparation.read.failed'), description: history }
      if (this.automaticEnded) return { ...base, message: this.$t('message.storage.service.preparation.auto.limit'), description: history }
      if (!this.receipt) return { ...base, status: this.loading ? 'CHECKING' : base.status, message: this.$t('message.storage.service.preparation.wait'), description: this.loading ? '' : history }
      if (this.receipt.expiresatmillis <= this.clock) return { ...base, type: 'warning', message: this.$t('message.storage.service.preparation.observation.expired'), description: history }
      const confirmed = readyAtSameBoot(this.previousReceipt, this.receipt, this.clock)
      if (this.receipt.ready) {
        return confirmed
          ? { ...base, current: true, ready: true, status: 'READY', color: 'green', type: 'success', message: this.reasonText(this.receipt.reason) }
          : { ...base, current: true, status: 'CONFIRMING', color: 'blue', message: this.$t('message.storage.service.preparation.confirming') }
      }
      return {
        ...base,
        current: true,
        status: this.receipt.status,
        color: this.receipt.status === 'BLOCKED' ? 'orange' : (storagePreparationWaitable(this.receipt.status) ? 'blue' : 'default'),
        type: ['BLOCKED', 'OBSERVATION_UNAVAILABLE'].includes(this.receipt.status) ? 'warning' : 'info',
        message: this.reasonText(this.receipt.reason)
      }
    },
    diagnosticObservation () {
      const value = this.firstFailureObservation || storagePreparationDiagnosticSnapshot(this.lastObservedReceipt)
      if (!value || (this.sharedFileSystemId && value.sharedfilesystemid !== this.sharedFileSystemId) || (this.instanceId && value.id !== this.instanceId)) return null
      return value
    },
    confirmedReady () { return this.presentation.ready },
    status () { return this.presentation.status },
    statusColor () { return this.presentation.color }
  },
  watch: {
    contextKey () { this.reset(); if (this.active) this.refresh() }
  },
  mounted () { if (this.active) this.refresh() },
  beforeUnmount () { this.disposed = true; this.reset() },
  methods: {
    reasonText (reason) {
      return storagePreparationReasons.includes(reason) ? this.$t('message.storage.service.preparation.reason.' + reason) : this.$t('message.storage.service.preparation.reason.UNKNOWN')
    },
    time (millis) { return new Date(millis).toLocaleString() },
    publish () { this.$emit('status', { receipt: this.presentation.current ? this.receipt : null, ready: this.presentation.ready, error: this.readError, status: this.presentation.status }) },
    reset () {
      this.generation++; this.loading = false; this.receipt = null; this.previousReceipt = null; this.lastObservedReceipt = null; this.firstFailureObservation = null; this.readError = false; this.startedAt = 0; this.automaticEnded = false
      clearTimeout(this.refreshTimer); clearTimeout(this.expiryTimer); this.publish()
    },
    schedule () {
      clearTimeout(this.refreshTimer); clearTimeout(this.expiryTimer)
      if (this.receipt) {
        const expiry = this.confirmedReady ? Math.min(this.receipt.expiresatmillis, this.previousReceipt.expiresatmillis) : this.receipt.expiresatmillis
        this.expiryTimer = setTimeout(() => { this.clock = Date.now(); this.publish() }, Math.max(1, expiry - Date.now() + 1))
      }
      if (this.disposed || !this.active || !this.canRead || this.readError || this.automaticEnded || !storagePreparationWaitable(this.receipt?.status)) return
      if (Date.now() - this.startedAt >= 15 * 60 * 1000) { this.clock = Date.now(); this.automaticEnded = true; this.publish(); return }
      this.refreshTimer = setTimeout(() => {
        if (canRefreshList(this.$el)) this.refresh()
        else this.schedule()
      }, this.confirmedReady ? 15000 : 3000)
    },
    async refresh (manual = false) {
      if (this.disposed || !this.active || !this.canRead || this.loading || !(this.sharedFileSystemId || this.instanceId)) return
      if (!this.startedAt || manual) { this.startedAt = Date.now(); this.automaticEnded = false }
      if (manual) {
        this.lastObservedReceipt = this.receipt || this.lastObservedReceipt
        this.receipt = null; this.previousReceipt = null; this.readError = false; this.clock = Date.now()
      }
      const generation = this.generation; const context = this.contextKey
      const current = () => !this.disposed && this.active && generation === this.generation && context === this.contextKey && this.canRead
      this.loading = true
      if (manual) this.publish()
      try {
        const params = { purpose: this.purpose, ...(this.sharedFileSystemId ? { sharedfilesystemid: this.sharedFileSystemId } : { instanceid: this.instanceId }), ...(this.targetBundleId ? { targetbundleid: this.targetBundleId } : {}) }
        const result = await storageReadDeadline(getAPI('getStorageServicePreparationStatus', params, { timeout: 60000, preserveOnFailure: true }), 60000)
        if (!current()) return
        this.clock = Date.now()
        const receipt = readStoragePreparation(result, { sharedfilesystemid: this.sharedFileSystemId, instanceid: this.instanceId, purpose: this.purpose }, this.clock)
        this.previousReceipt = this.receipt; this.receipt = receipt; this.lastObservedReceipt = receipt; this.readError = false
        if (!storagePreparationWaitable(receipt.status) && !this.firstFailureObservation) this.firstFailureObservation = storagePreparationDiagnosticSnapshot(receipt)
      } catch (error) {
        if (current()) { this.lastObservedReceipt = this.receipt || this.lastObservedReceipt; this.receipt = null; this.previousReceipt = null; this.readError = true }
      } finally {
        if (current()) { this.loading = false; this.schedule(); this.publish() }
      }
    }
  }
}
</script>
<style scoped>
.storage-preparation-status { margin-bottom: 16px; }
.storage-preparation-status > * + * { margin-top: 10px; }
.storage-preparation-status code { overflow-wrap: anywhere; }
</style>
