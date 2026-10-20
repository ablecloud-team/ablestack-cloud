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
      <a-tag :color="statusColor" role="status">{{ $t('label.storage.service.preparation.status.' + status) }}</a-tag>
      <a-button :loading="loading" :disabled="!active" @click="refresh(true)"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.service.preparation.read.failed')" />
    <a-alert
v-else-if="receipt"
:type="confirmedReady ? 'success' : (status === 'BLOCKED' ? 'warning' : 'info')"
show-icon
      :message="reasonText(receipt.reason)"
      :description="receipt.ready && !confirmedReady ? $t('message.storage.service.preparation.confirming') : undefined" />
    <a-alert v-if="automaticEnded" type="info" show-icon :message="$t('message.storage.service.preparation.auto.limit')" />
    <a-descriptions v-if="receipt" bordered size="small" :column="2">
      <a-descriptions-item :label="$t('label.storage.service.preparation.purpose')">{{ $t('label.storage.service.preparation.purpose.' + purpose) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.boot.boot.id')"><code>{{ receipt.bootid || '—' }}</code></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.operation.observed.at')">{{ time(receipt.observedatmillis) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.service.preparation.reason')">{{ reasonText(receipt.reason) }}</a-descriptions-item>
    </a-descriptions>
  </section>
</template>
<script>
import { storageReadDeadline } from '@/utils/storageRead'
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { readStoragePreparation, readyAtSameBoot, storagePreparationWaitable } from '@/utils/storagePreparation'
import { canRefreshList } from '@/utils/listRefresh'

const security = vm => JSON.stringify([vm.$store?.getters?.userInfo?.id, vm.$store?.getters?.userInfo?.roletype, vm.$store?.getters?.project?.id, vm.$store?.state?.user?.token])
export default {
  name: 'StoragePreparationStatus',
  components: { ReloadOutlined },
  props: {
    sharedFileSystemId: { type: String, default: '' },
    instanceId: { type: String, default: '' },
    purpose: { type: String, default: 'INITIAL_CONFIG' },
    targetBundleId: { type: String, default: '' },
    active: { type: Boolean, default: false },
    scopeKey: { type: String, default: '' }
  },
  emits: ['status'],
  data: () => ({ generation: 0, disposed: false, receipt: null, previousReceipt: null, readError: false, loading: false, clock: Date.now(), startedAt: 0, automaticEnded: false }),
  computed: {
    canRead () { return !!this.$store?.getters?.userInfo?.id && !!this.$store?.state?.user?.token && 'getStorageServicePreparationStatus' in (this.$store?.getters?.apis || {}) },
    contextKey () { return JSON.stringify([this.sharedFileSystemId, this.instanceId, this.purpose, this.targetBundleId, this.scopeKey, this.active, this.canRead, security(this)]) },
    confirmedReady () { return readyAtSameBoot(this.previousReceipt, this.receipt, this.clock) },
    status () { return this.receipt && this.receipt.expiresatmillis > this.clock ? this.receipt.status : 'OBSERVATION_UNAVAILABLE' },
    statusColor () { return this.confirmedReady ? 'green' : ({ WAITING_VM: 'blue', WAITING_BOOT: 'blue', WAITING_WRITER: 'blue', BLOCKED: 'orange' }[this.status] || 'default') }
  },
  watch: {
    contextKey () { this.reset(); if (this.active) this.refresh() }
  },
  mounted () { if (this.active) this.refresh() },
  beforeUnmount () { this.disposed = true; this.reset() },
  methods: {
    reasonText (reason) {
      const known = ['INSTANCE_NOT_ATTACHED', 'VM_NOT_RUNNING', 'CURRENT_BOOT_UNOBSERVED', 'GENERATION_OBSERVATION_INVALID', 'BOOT_CHANGED_OR_STALE', 'HEALTH_OBSERVATION_STALE', 'BOOT_OBSERVATION_INVALID', 'BOOT_NOT_STARTED', 'BOOT_MAINTENANCE_HELD', 'BOOT_RECONCILE_FAILED', 'BOOT_RECONCILING', 'BOOT_READY_PROOF_INVALID', 'MAINTENANCE_UNOBSERVED', 'WRITER_UNOBSERVED', 'BOOT_CHILD_ACTIVE', 'WRITER_ACTIVE', 'FORMATTER_ACTIVE_OR_UNOBSERVED', 'NATIVE_GENERATION_RECOVERY_REQUIRED', 'CURRENT_BOOT_READY', 'NATIVE_OBSERVATION_UNAVAILABLE', 'OBSERVATION_WINDOW_EXPIRED', 'TARGET_RUNTIME_UNAVAILABLE']
      return known.includes(reason) ? this.$t('message.storage.service.preparation.reason.' + reason) : reason || this.$t('message.storage.service.preparation.wait')
    },
    time (millis) { return new Date(millis).toLocaleString() },
    publish () { this.$emit('status', { receipt: this.receipt, ready: this.confirmedReady, error: this.readError }) },
    reset () {
      this.generation++; this.loading = false; this.receipt = null; this.previousReceipt = null; this.readError = false; this.startedAt = 0; this.automaticEnded = false
      clearTimeout(this.refreshTimer); clearTimeout(this.expiryTimer); this.publish()
    },
    schedule () {
      clearTimeout(this.refreshTimer); clearTimeout(this.expiryTimer)
      if (this.receipt) {
        this.expiryTimer = setTimeout(() => { this.clock = Date.now(); this.publish() }, Math.max(1, this.receipt.expiresatmillis - Date.now() + 1))
      }
      if (this.disposed || !this.active || !this.canRead || this.readError || !storagePreparationWaitable(this.receipt?.status)) return
      if (Date.now() - this.startedAt >= 15 * 60 * 1000) { this.automaticEnded = true; return }
      this.refreshTimer = setTimeout(() => {
        if (canRefreshList(this.$el)) this.refresh()
        else this.schedule()
      }, this.confirmedReady ? 15000 : 3000)
    },
    async refresh (manual = false) {
      if (this.disposed || !this.active || !this.canRead || this.loading || !(this.sharedFileSystemId || this.instanceId)) return
      if (!this.startedAt || manual) { this.startedAt = Date.now(); this.automaticEnded = false }
      const generation = this.generation; const context = this.contextKey
      const current = () => !this.disposed && this.active && generation === this.generation && context === this.contextKey && this.canRead
      this.loading = true
      try {
        const params = { purpose: this.purpose, ...(this.sharedFileSystemId ? { sharedfilesystemid: this.sharedFileSystemId } : { instanceid: this.instanceId }), ...(this.targetBundleId ? { targetbundleid: this.targetBundleId } : {}) }
        const result = await storageReadDeadline(getAPI('getStorageServicePreparationStatus', params, { timeout: 60000, preserveOnFailure: true }), 60000)
        if (!current()) return
        this.clock = Date.now()
        const receipt = readStoragePreparation(result, { sharedfilesystemid: this.sharedFileSystemId, instanceid: this.instanceId, purpose: this.purpose }, this.clock)
        this.previousReceipt = this.receipt; this.receipt = receipt; this.readError = false
      } catch (error) {
        if (current()) { this.receipt = null; this.previousReceipt = null; this.readError = true }
      } finally {
        if (current()) { this.loading = false; this.publish(); this.schedule() }
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
