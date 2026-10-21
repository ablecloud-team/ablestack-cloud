// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div class="volume-operation-form" v-ctrl-enter="submitForm">
    <div class="volume-operation-target"><hdd-outlined aria-hidden="true" /><span>{{ resource.name }}</span><span class="operation-secondary">{{ resource.type }}</span></div>
    <a-form layout="vertical">
      <a-form-item html-for="volume-migration-pool">
        <template #label><span class="operation-label"><span>{{ $t('label.volume.operation.target.pool') }}</span><span class="operation-secondary">{{ $t('label.current') }} {{ resource.storage || '—' }}</span></span></template>
        <a-select
          id="volume-migration-pool"
          :value="selectedStoragePool?.id"
          :loading="poolLoading"
          :disabled="disabled || explicit || !!poolError"
          show-search
          option-filter-prop="label"
          :placeholder="$t('label.select')"
          aria-describedby="volume-migration-pool-help"
          @change="selectStoragePool">
          <a-select-option v-for="pool in storagePools" :key="pool.id" :value="pool.id" :label="pool.name" :disabled="pool.state !== 'Up'">{{ pool.name }}<template v-if="pool.state !== 'Up'"> · {{ pool.state }}</template></a-select-option>
        </a-select>
        <a-alert v-if="explicit" id="volume-migration-pool-help" class="operation-help" type="warning" show-icon :message="$t('message.allocation.lifecycle.unsupported')" />
        <p v-else id="volume-migration-pool-help" class="operation-help" :class="{ 'operation-error': poolError }" aria-live="polite">
          <template v-if="poolError">{{ poolError }}</template>
          <template v-else-if="!poolLoading && !storagePools.length">{{ $t('message.volume.operation.no.pools') }}</template>
          <template v-else-if="selectedStoragePool">{{ $t(selectedStoragePool.suitableformigration ? 'label.volume.operation.suitable' : 'label.volume.operation.unsuitable') }} · {{ selectedStoragePool.state }} · {{ $t('label.disksizefree') }} {{ freeSize }}</template>
        </p>
        <a-button v-if="!explicit && (poolError || (!poolLoading && !storagePools.length))" class="operation-retry" :loading="poolLoading" :disabled="disabled" @click="fetchStoragePools">{{ $t('label.retry') }}</a-button>
        <details v-if="selectedStoragePool && !poolError && !explicit" class="pool-details">
          <summary>{{ $t('label.volume.operation.pool.details') }}</summary>
          <dl><dt>{{ $t('label.clusterid') }} / {{ $t('label.podid') }}</dt><dd>{{ selectedStoragePool.clustername || '—' }} / {{ selectedStoragePool.podname || '—' }}</dd><dt>{{ $t('label.disksizetotal') }} / {{ $t('label.disksizeused') }}</dt><dd>{{ bytes(selectedStoragePool.disksizetotal) }} / {{ bytes(selectedStoragePool.disksizeused) }}</dd><dt>{{ $t('label.size') }}</dt><dd>{{ bytes(resource.size) }}</dd></dl>
        </details>
        <p class="operation-help">{{ resource.virtualmachineid ? $t('label.virtualmachine') + ': ' + (resource.vmdisplayname || resource.vmname || resource.virtualmachineid) + ' · ' + $t(resource.vmstate) : $t('message.volume.operation.detached') }}</p>
      </a-form-item>
      <details v-if="resource.virtualmachineid && selectedStoragePool && !explicit && !poolError" class="operation-options">
        <summary>{{ $t('label.volume.resize.more.options') }} · {{ $t(replaceDiskOffering ? 'label.volume.operation.replace.offering' : 'label.volume.operation.keep.offering') }}</summary>
        <a-checkbox v-model:checked="replaceDiskOffering" :disabled="disabled">{{ $t('label.usenewdiskoffering') }}</a-checkbox>
        <a-form-item v-if="replaceDiskOffering" class="replacement-offering" html-for="volume-migration-offering">
          <template #label><span class="operation-label"><span>{{ $t('label.newdiskoffering') }}</span><span class="operation-secondary">{{ $t('label.current') }} {{ resource.diskofferingname || '—' }}</span></span></template>
          <a-select id="volume-migration-offering" v-model:value="selectedDiskOffering" :loading="diskOfferingLoading" :disabled="disabled || !!offeringError" show-search option-filter-prop="label" aria-describedby="volume-migration-offering-help">
            <a-select-option v-for="offering in diskOfferings" :value="offering.id" :key="offering.id" :label="offering.displaytext || offering.name" :disabled="!!offeringReason(offering)">{{ offering.displaytext || offering.name }}</a-select-option>
          </a-select>
          <p id="volume-migration-offering-help" class="operation-help" :class="{ 'operation-error': offeringError }">{{ offeringError || $t(!diskOfferingLoading && !diskOfferings.length ? 'message.volume.operation.no.offerings' : 'message.volume.operation.pool.offerings') }}</p>
          <a-button v-if="offeringError || (!diskOfferingLoading && !diskOfferings.length)" class="operation-retry" :disabled="disabled" :loading="diskOfferingLoading" @click="fetchDiskOfferings">{{ $t('label.retry') }}</a-button>
        </a-form-item>
      </details>
      <p v-if="errorText" class="operation-error operation-help" role="alert">{{ errorText }}</p>
      <div class="action-button operation-footer">
        <span class="operation-summary operation-secondary" aria-live="polite">{{ resource.storage || '—' }} → {{ selectedStoragePool?.name || '—' }}<template v-if="replaceDiskOffering"> · {{ selectedOffering?.displaytext || selectedOffering?.name || $t('label.volume.operation.replace.offering') }}</template></span>
        <a-button :disabled="isSubmitted" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button v-if="jobId" :loading="isSubmitted" type="primary" @click="resumeJob">{{ $t('message.volume.edit.check.result') }}</a-button>
        <a-button v-else type="primary" :loading="isSubmitted" :disabled="!canSubmit" @click="submitForm">{{ $t('label.volume.operation.move') }}</a-button>
      </div>
    </a-form>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { allocationIntent, allocationOfferingReason, readAllocationList } from '@/utils/volumeAllocation'

export default {
  name: 'MigrateVolume',
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () {
    return { storagePools: [], selectedStoragePool: null, poolLoading: false, poolError: '', diskOfferings: [], diskOfferingLoading: false, offeringError: '', replaceDiskOffering: false, selectedDiskOffering: null, isSubmitted: false, errorText: '', jobId: null, submissionUnknown: false, active: true, offeringRequest: 0 }
  },
  computed: {
    explicit () { return !!allocationIntent(this.resource) },
    disabled () { return this.isSubmitted || !!this.jobId || this.submissionUnknown || !this.current() },
    selectedOffering () { return this.diskOfferings.find(o => o.id === this.selectedDiskOffering) },
    canSubmit () { return !this.disabled && !this.explicit && !this.poolLoading && !this.poolError && this.selectedStoragePool?.state === 'Up' && (!this.replaceDiskOffering || (!this.diskOfferingLoading && !this.offeringError && !this.offeringReason(this.selectedOffering))) },
    freeSize () {
      const pool = this.selectedStoragePool
      return pool?.disksizetotal != null && pool?.disksizeused != null ? this.bytes(Math.max(0, Number(pool.disksizetotal) - Number(pool.disksizeused))) : '—'
    }
  },
  watch: {
    replaceDiskOffering (enabled) { if (enabled) this.fetchDiskOfferings(); else this.offeringRequest++ }
  },
  created () { this.originalScope = this.scope(); if (!this.explicit) this.fetchStoragePools() },
  beforeUnmount () { this.active = false; this.offeringRequest++ },
  methods: {
    scope () { const s = this.$store?.getters || {}; return JSON.stringify([this.resource.id, s.userInfo?.id, s.userInfo?.roletype, s.project?.id, this.$route?.path]) },
    current () { return this.active && this.originalScope === this.scope() },
    bytes (value) { return value == null ? '—' : this.$bytesToHumanReadableSize(value) },
    failureText (error) { return Object.values(error?.response?.data || {}).find(v => v?.errortext)?.errortext || error?.errortext || error?.message || this.$t('message.request.failed') },
    offeringReason (offering) { return allocationOfferingReason(this.resource, offering) },
    async fetchStoragePools () {
      if (this.poolLoading || this.disabled || this.explicit) return
      this.poolLoading = true
      this.poolError = ''
      try {
        const rows = await readAllocationList(getAPI, 'findStoragePoolsForMigration', 'storagepool', { id: this.resource.id }, () => this.current())
        if (!rows || !this.current()) return
        this.storagePools = rows.filter(p => p.id !== this.resource.storageid)
        this.selectedStoragePool = this.storagePools.find(p => p.id === this.selectedStoragePool?.id && p.state === 'Up') || this.storagePools.find(p => p.state === 'Up' && p.suitableformigration) || this.storagePools.find(p => p.state === 'Up') || null
        if (this.replaceDiskOffering) await this.fetchDiskOfferings()
      } catch (error) { if (this.active) this.poolError = this.failureText(error) } finally { this.poolLoading = false }
    },
    selectStoragePool (id) {
      this.selectedStoragePool = this.storagePools.find(p => p.id === id && p.state === 'Up') || null
      if (this.replaceDiskOffering) this.fetchDiskOfferings()
    },
    async fetchDiskOfferings () {
      const request = ++this.offeringRequest
      if (!this.resource.virtualmachineid || !this.replaceDiskOffering || !this.selectedStoragePool || this.disabled) return
      const poolId = this.selectedStoragePool.id
      this.diskOfferingLoading = true
      this.offeringError = ''
      try {
        const rows = await readAllocationList(getAPI, 'listDiskOfferings', 'diskoffering', { storageid: poolId, listall: true }, () => this.current() && request === this.offeringRequest)
        if (!rows || !this.current() || request !== this.offeringRequest) return
        this.diskOfferings = rows.filter(o => !!o.kvdoenable === !!this.resource.kvdoenable)
        this.selectedDiskOffering = this.diskOfferings.find(o => o.id === this.selectedDiskOffering && !this.offeringReason(o))?.id || this.diskOfferings.find(o => !this.offeringReason(o))?.id || null
      } catch (error) { if (this.active && request === this.offeringRequest) this.offeringError = this.failureText(error) } finally { if (request === this.offeringRequest) this.diskOfferingLoading = false }
    },
    async submitForm () {
      if (!this.canSubmit) return
      this.isSubmitted = true
      this.errorText = ''
      try {
        if (!this.current()) throw new Error(this.$t('message.volume.resize.context'))
        const params = { livemigrate: this.resource.vmstate === 'Running', storageid: this.selectedStoragePool.id, volumeid: this.resource.id }
        if (this.replaceDiskOffering) params.newdiskofferingid = this.selectedDiskOffering
        this.submissionUnknown = true
        const result = (await postAPI('migrateVolume', params, { preserveOnFailure: true }))?.migratevolumeresponse
        if (!result?.jobid) throw new Error(this.$t('message.volume.operation.unknown'))
        this.submissionUnknown = false
        this.jobId = result.jobid
        await this.poll(false)
      } catch (error) {
        if (error?.response?.status >= 400 && error?.response?.data) this.submissionUnknown = false
        if (this.active) this.errorText = this.submissionUnknown ? this.$t('message.volume.operation.unknown') : this.failureText(error)
      } finally { this.isSubmitted = false }
    },
    async poll (retry) {
      const result = await this.$pollJob({ jobId: this.jobId, batchKey: this.jobId, resourceId: this.resource.id, retry, title: this.$t('label.migrate.volume'), description: this.resource.name, successMessage: this.$t('message.success.migrate.volume'), errorMessage: this.$t('message.migrate.volume.failed') })
      if (!this.current()) return
      if (result.jobstatus === 1) { this.jobId = null; this.$emit('refresh-data'); this.closeModal() } else if (result.jobstatus === 2) { this.jobId = null; this.errorText = this.failureText(result.jobresult) } else this.errorText = this.$t('message.volume.operation.unknown')
    },
    async resumeJob () {
      if (this.isSubmitted || !this.jobId || !this.current()) return
      this.isSubmitted = true
      try { await this.poll(true) } catch (error) { if (this.active) this.errorText = this.failureText(error) } finally { this.isSubmitted = false }
    },
    closeModal () { this.$emit('close-action') }
  }
}
</script>

<style lang="scss" scoped>
@import '@/style/volume-operation-dialog';
.pool-details { margin-top: 10px; }
.pool-details dl { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px 12px; margin: 10px 0; font-size: 12px; }
.pool-details dt { opacity: .8; }
.pool-details dd { margin: 0; overflow-wrap: anywhere; }
.replacement-offering { margin-top: 20px; }
@media (max-width: 480px) { .pool-details dl { grid-template-columns: minmax(0, 1fr); gap: 3px; } .pool-details dd { margin-bottom: 8px; } }
</style>
