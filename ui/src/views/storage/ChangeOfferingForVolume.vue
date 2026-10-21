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
  <div class="volume-operation-form" v-ctrl-enter="submitChangeOfferingForVolume">
    <div class="volume-operation-target"><hdd-outlined aria-hidden="true" /><span>{{ resource.name }}</span><span class="operation-secondary">{{ resource.type }}</span></div>
    <a-form layout="vertical">
      <a-form-item html-for="volume-offering-select">
        <template #label><span class="operation-label"><span>{{ $t('label.newdiskoffering') }}</span><span class="operation-secondary">{{ $t('label.current') }} {{ resource.diskofferingname || '—' }}</span></span></template>
        <a-select
          id="volume-offering-select"
          v-model:value="form.diskofferingid"
          :loading="offeringLoading"
          :disabled="disabled || !!offeringError"
          show-search
          option-filter-prop="label"
          aria-describedby="volume-offering-help"
          @change="onChangeDiskOffering">
          <a-select-option v-for="offering in diskOfferings" :key="offering.id" :value="offering.id" :label="offering.displaytext || offering.name" :disabled="!!offeringAllocationReason(resource, offering)">{{ offering.displaytext || offering.name }}</a-select-option>
        </a-select>
        <p id="volume-offering-help" class="operation-help" :class="{ 'operation-error': offeringError || allocationReason }" aria-live="polite">{{ offeringError || (allocationReason ? $t(allocationReason) : $t(selectedOffering?.iscustomized ? 'message.volume.operation.custom.offering' : 'message.volume.operation.fixed.offering')) }}</p>
        <a-button v-if="offeringError || (!offeringLoading && !diskOfferings.length)" class="operation-retry" :loading="offeringLoading" :disabled="disabled" @click="fetchDiskOfferings">{{ $t('label.retry') }}</a-button>
      </a-form-item>
      <template v-if="selectedOffering">
        <a-form-item class="offering-size" html-for="volume-offering-size">
          <template #label><span class="operation-label"><span>{{ $t('label.volume.resize.new.size') }}</span><span class="operation-secondary">{{ $t('label.current') }} {{ currentSize }} GiB</span></span></template>
          <a-input v-if="selectedOffering.iscustomized" id="volume-offering-size" v-model:value="form.size" :disabled="disabled || explicit || !apiParams.size" addon-after="GiB" aria-describedby="volume-offering-size-help" />
          <a-input v-else id="volume-offering-size" :value="selectedOffering.disksize" disabled addon-after="GiB" aria-describedby="volume-offering-size-help" />
          <p id="volume-offering-size-help" class="operation-help">
            <template v-if="explicit">{{ $t('message.volume.operation.explicit.size') }}</template>
            <template v-else-if="!selectedOffering.iscustomized">{{ $t('message.volume.operation.fixed.size') }}</template>
            <template v-else-if="sizeDelta === null">{{ $t('message.volume.resize.check.input') }}</template>
            <template v-else-if="isShrinking && shrinkBlocked">{{ $t('message.volume.resize.shrink') }}</template>
            <template v-else-if="sizeDelta">{{ $t(sizeDelta > 0 ? 'message.volume.resize.grow.by' : 'message.volume.resize.shrink.by', { size: Math.abs(sizeDelta) }) }}</template>
            <template v-else>{{ $t('message.volume.resize.size.retained') }}</template>
          </p>
          <a-checkbox v-if="isShrinking && !shrinkBlocked && !explicit && apiParams.shrinkok" v-model:checked="form.shrinkok" :disabled="disabled" class="offering-shrink">{{ $t('label.shrinkok') }}</a-checkbox>
        </a-form-item>
        <section class="offering-iops" :aria-label="$t('label.volume.resize.iops')">
          <div class="operation-section-title">{{ $t('label.volume.resize.iops') }}</div>
          <div class="operation-columns">
            <a-form-item html-for="volume-offering-min">
              <template #label><span class="operation-label"><span>{{ $t('label.miniops') }}</span><span class="operation-secondary">{{ $t('label.current') }} {{ resource.miniops ?? '—' }}</span></span></template>
              <a-input v-if="selectedOffering.iscustomizediops" id="volume-offering-min" v-model:value="form.miniops" :disabled="disabled || !apiParams.miniops" aria-describedby="volume-offering-iops-help" />
              <a-input v-else id="volume-offering-min" :value="selectedOffering.miniops ?? $t('label.volume.operation.iops.unlimited')" disabled aria-describedby="volume-offering-iops-help" />
            </a-form-item>
            <a-form-item html-for="volume-offering-max">
              <template #label><span class="operation-label"><span>{{ $t('label.maxiops') }}</span><span class="operation-secondary">{{ $t('label.current') }} {{ resource.maxiops ?? '—' }}</span></span></template>
              <a-input v-if="selectedOffering.iscustomizediops" id="volume-offering-max" v-model:value="form.maxiops" :disabled="disabled || !apiParams.maxiops" aria-describedby="volume-offering-iops-help" />
              <a-input v-else id="volume-offering-max" :value="selectedOffering.maxiops ?? $t('label.volume.operation.iops.unlimited')" disabled aria-describedby="volume-offering-iops-help" />
            </a-form-item>
          </div>
          <p id="volume-offering-iops-help" class="operation-help">{{ $t(selectedOffering.iscustomizediops ? 'message.volume.operation.custom.iops' : 'message.volume.operation.fixed.iops') }}</p>
        </section>
        <details v-if="!resource.kvdoenable && apiParams.automigrate" class="operation-options">
          <summary>{{ $t('label.volume.resize.more.options') }} · {{ $t(form.automigrate ? 'label.volume.operation.auto.enabled' : 'label.volume.operation.auto.disabled') }}</summary>
          <a-checkbox v-model:checked="form.automigrate" :disabled="disabled">{{ $t('label.automigrate.volume') }}</a-checkbox>
          <p class="operation-help">{{ $t('message.volume.operation.auto.location') }}</p>
        </details>
      </template>
      <p v-if="validationError" class="operation-error operation-help" role="alert">{{ $t(validationError) }}</p>
      <p v-if="errorText" class="operation-error operation-help" role="alert">{{ errorText }}</p>
      <div class="action-button operation-footer">
        <span class="operation-summary operation-secondary" aria-live="polite"><span>{{ resource.diskofferingname || '—' }} → {{ selectedOffering?.displaytext || selectedOffering?.name || '—' }}</span><span v-if="selectedOffering">{{ changeSummary }}</span></span>
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button v-if="jobId" :loading="loading" type="primary" @click="resumeJob">{{ $t('message.volume.edit.check.result') }}</a-button>
        <a-button v-else type="primary" :loading="loading" :disabled="!canSubmit" @click="submitChangeOfferingForVolume">{{ $t('label.change') }}</a-button>
      </div>
    </a-form>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { allocationOfferingReason, allocationIntent, readAllocationList } from '@/utils/volumeAllocation'
import { offeringVolumeSize, offeringShrinkBlocked, volumeOfferingChangeParams } from '@/utils/volumeOfferingChange'
import { validateVolumeResizeSnapshot } from '@/utils/volumeResize'

export default {
  name: 'ChangeOfferingForVolume',
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () { return { diskOfferings: [], offeringLoading: false, offeringError: '', loading: false, errorText: '', jobId: null, submissionUnknown: false, active: true, form: { diskofferingid: null, size: '', miniops: '', maxiops: '', automigrate: true, shrinkok: false } } },
  beforeCreate () { this.apiParams = this.$getApiParams('changeOfferingForVolume') || {} },
  created () {
    this.target = Object.freeze({ ...this.resource })
    this.originalScope = this.scope()
    this.form.size = String(this.currentSize)
    this.form.miniops = this.resource.miniops == null ? '' : String(this.resource.miniops)
    this.form.maxiops = this.resource.maxiops == null ? '' : String(this.resource.maxiops)
    this.fetchDiskOfferings()
  },
  beforeUnmount () { this.active = false },
  computed: {
    selectedOffering () { return this.diskOfferings.find(o => o.id === this.form.diskofferingid) },
    allocationReason () { return allocationOfferingReason(this.resource, this.selectedOffering) },
    explicit () { return !!allocationIntent(this.resource) },
    currentSize () { return this.resource.size / 1073741824 },
    sizeDelta () { const size = offeringVolumeSize(this.resource, this.selectedOffering, this.form); return Number.isSafeInteger(size * 1073741824) && size > 0 ? size - this.currentSize : null },
    isShrinking () { return this.sizeDelta !== null && this.sizeDelta < 0 },
    shrinkBlocked () { return offeringShrinkBlocked(this.resource) },
    disabled () { return this.loading || !!this.jobId || this.submissionUnknown || !this.current() },
    validationError () {
      if (!this.selectedOffering || this.allocationReason) return ''
      try { volumeOfferingChangeParams(this.resource, this.selectedOffering, this.form, this.apiParams); return '' } catch (error) { return error.message }
    },
    canSubmit () { return !this.disabled && !this.offeringLoading && !this.offeringError && !this.allocationReason && !this.validationError },
    changeSummary () {
      if (this.validationError) return this.$t('message.volume.resize.check.input')
      const size = this.sizeDelta === 0 ? this.$t('message.volume.resize.size.retained') : this.$t(this.sizeDelta > 0 ? 'message.volume.resize.grow.by' : 'message.volume.resize.shrink.by', { size: Math.abs(this.sizeDelta) })
      const values = this.selectedOffering.iscustomizediops ? this.form : this.selectedOffering
      const changed = ['miniops', 'maxiops'].some(k => this.selectedOffering.iscustomizediops
        ? values[k] != null && String(values[k]).trim() !== '' && Number(values[k]) !== (this.resource[k] ?? 0)
        : Number(values[k] ?? 0) !== Number(this.resource[k] ?? 0))
      return size + ' · ' + this.$t(changed ? 'label.volume.resize.iops.changed' : 'label.volume.operation.iops.retained')
    }
  },
  methods: {
    offeringAllocationReason: allocationOfferingReason,
    scope () { const s = this.$store?.getters || {}; return JSON.stringify([this.resource.id, s.userInfo?.id, s.userInfo?.roletype, s.project?.id, this.$route?.path]) },
    current () { return this.active && this.originalScope === this.scope() },
    failureText (error) { const body = error?.response?.data; return Object.values(body || {}).find(v => v?.errortext)?.errortext || error?.errortext || (error?.message?.startsWith('message.') ? this.$t(error.message) : error?.message) || this.$t('message.request.failed') },
    async fetchDiskOfferings () {
      if (this.offeringLoading || this.disabled) return
      this.offeringLoading = true
      this.offeringError = ''
      try {
        const rows = await readAllocationList(getAPI, 'listDiskOfferings', 'diskoffering', { volumeid: this.target.id, listall: true }, () => this.current())
        if (!rows || !this.current()) return
        this.diskOfferings = rows.filter(o => !!o.kvdoenable === !!this.resource.kvdoenable)
        this.form.diskofferingid = this.diskOfferings.find(o => o.id === this.form.diskofferingid && !allocationOfferingReason(this.resource, o))?.id || this.diskOfferings.find(o => !allocationOfferingReason(this.resource, o))?.id || null
        if (!this.diskOfferings.length) this.offeringError = this.$t('message.volume.operation.no.offerings')
      } catch (error) { if (this.active) this.offeringError = this.failureText(error) } finally { this.offeringLoading = false }
    },
    onChangeDiskOffering () { this.form.shrinkok = false; this.errorText = '' },
    closeModal () { this.$emit('close-action') },
    async submitChangeOfferingForVolume () {
      if (!this.canSubmit) return
      this.loading = true
      this.errorText = ''
      try {
        if (!this.current()) throw new Error('message.volume.resize.context')
        const response = await getAPI('listVolumes', { id: this.target.id, listall: true, ...(this.target.projectid ? { projectid: this.target.projectid } : {}) }, { preserveOnFailure: true })
        if (!this.current()) throw new Error('message.volume.resize.context')
        const rows = response?.listvolumesresponse?.volume
        const fresh = Array.isArray(rows) && rows.length === 1 ? rows[0] : null
        validateVolumeResizeSnapshot(this.target, fresh, null, null)
        const offerings = await readAllocationList(getAPI, 'listDiskOfferings', 'diskoffering', { volumeid: this.target.id, listall: true }, () => this.current())
        if (!this.current()) throw new Error('message.volume.resize.context')
        const selected = offerings?.find(o => o.id === this.form.diskofferingid)
        if (JSON.stringify(selected) !== JSON.stringify(this.selectedOffering)) throw new Error('message.volume.resize.changed')
        const params = volumeOfferingChangeParams(fresh, selected, this.form, this.apiParams)
        this.submissionUnknown = true
        const result = (await postAPI('changeOfferingForVolume', params, { preserveOnFailure: true }))?.changeofferingforvolumeresponse
        if (!result?.jobid) throw new Error('message.volume.operation.unknown')
        this.submissionUnknown = false
        this.jobId = result.jobid
        await this.poll(false)
      } catch (error) {
        if (error?.response?.status >= 400 && error?.response?.data) this.submissionUnknown = false
        if (this.active) this.errorText = this.submissionUnknown ? this.$t('message.volume.operation.unknown') : this.failureText(error)
      } finally { this.loading = false }
    },
    async poll (retry) {
      const result = await this.$pollJob({ jobId: this.jobId, batchKey: this.jobId, resourceId: this.target.id, retry, title: this.$t('label.change.disk.offering'), description: this.target.name, successMessage: this.$t('message.change.offering.for.volume'), errorMessage: this.$t('message.change.offering.for.volume.failed') })
      if (!this.current()) return
      if (result.jobstatus === 1) { this.jobId = null; this.$emit('refresh-data'); this.closeModal() } else if (result.jobstatus === 2) { this.jobId = null; this.errorText = this.failureText(result.jobresult) } else this.errorText = this.$t('message.volume.operation.unknown')
    },
    async resumeJob () {
      if (this.loading || !this.jobId || !this.current()) return
      this.loading = true
      try { await this.poll(true) } catch (error) { if (this.active) this.errorText = this.failureText(error) } finally { this.loading = false }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/style/volume-operation-dialog';
.offering-iops { margin-top: 24px; }
.offering-shrink { margin-top: 8px; }
</style>
