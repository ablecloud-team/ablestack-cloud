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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-alert v-if="!current()" type="error" show-icon :message="$t('message.volume.resize.context')" style="margin-bottom: 16px" />
    <a-alert v-if="errorText" type="error" show-icon :message="errorText" style="margin-bottom: 16px" />
    <a-button v-if="errorText && !contract && !jobId" :loading="loading" style="margin-bottom: 16px" @click="fetchData">{{ $t('label.retry') }}</a-button>
    <a-form :ref="formRef" :model="form" layout="vertical" @finish="handleSubmit">
      <div class="resize-target"><hdd-outlined aria-hidden="true" /><span>{{ target.name }}</span><span class="resize-secondary">{{ target.type }}</span></div>
      <a-form-item v-if="apiParams.size" name="size" class="resize-size">
        <template #label><span class="resize-label"><span>{{ $t('label.volume.resize.new.size') }}</span><span class="resize-secondary">{{ $t('label.current') }} {{ currentSize }} GiB</span></span></template>
        <a-input v-model:value="form.size" :disabled="disabled || !!sizeReason" :placeholder="$t('label.disksize')" addon-after="GiB" aria-describedby="volume-resize-size-help" />
        <p id="volume-resize-size-help" class="form-help">
          <template v-if="sizeReason">{{ $t(sizeReason) }}</template>
          <template v-else-if="rootFixed">{{ $t('message.volume.resize.root') }}</template>
          <template v-else-if="sizeDelta === null">{{ $t('message.volume.resize.check.input') }}</template>
          <template v-else-if="sizeDelta !== null && sizeDelta !== 0">{{ $t(sizeDelta > 0 ? 'message.volume.resize.grow.by' : 'message.volume.resize.shrink.by', { size: Math.abs(sizeDelta) }) }}</template>
          <template v-else>{{ $t('message.volume.resize.size.retained') }}</template>
        </p>
        <template v-if="isShrinking && apiParams.shrinkok && !sizeReason">
          <p v-if="shrinkBlocked" class="form-help">{{ $t('message.volume.resize.shrink') }}</p>
          <a-form-item-rest v-else><a-checkbox v-model:checked="form.shrinkok" :disabled="disabled" class="resize-shrink">{{ $t('label.shrinkok') }}</a-checkbox></a-form-item-rest>
        </template>
      </a-form-item>
      <section v-if="customIops && (apiParams.miniops || apiParams.maxiops)" class="resize-iops" :aria-label="$t('label.volume.resize.iops')">
        <div class="resize-section-title">{{ $t('label.volume.resize.iops') }}</div>
        <div class="resize-iops-fields">
          <a-form-item v-if="apiParams.miniops" name="miniops">
            <template #label><span class="resize-label"><span>{{ $t('label.miniops') }}</span><span class="resize-secondary">{{ $t('label.current') }} {{ target.miniops ?? '—' }}</span></span></template>
            <a-input v-model:value="form.miniops" :disabled="disabled" :placeholder="$t('label.miniops')" aria-describedby="volume-resize-iops-help" />
          </a-form-item>
          <a-form-item v-if="apiParams.maxiops" name="maxiops">
            <template #label><span class="resize-label"><span>{{ $t('label.maxiops') }}</span><span class="resize-secondary">{{ $t('label.current') }} {{ target.maxiops ?? '—' }}</span></span></template>
            <a-input v-model:value="form.maxiops" :disabled="disabled" :placeholder="$t('label.maxiops')" aria-describedby="volume-resize-iops-help" />
          </a-form-item>
        </div>
        <p id="volume-resize-iops-help" class="form-help">{{ $t('message.volume.resize.omitted') }}</p>
        <p v-if="target.type === 'ROOT'" class="form-help">{{ $t('message.volume.resize.root.iops') }}</p>
      </section>
      <p v-else-if="contract" class="form-help resize-fixed-iops">{{ $t('message.volume.resize.fixed.iops') }}: {{ target.miniops ?? '—' }} / {{ target.maxiops ?? '—' }}</p>
      <details v-if="apiParams.automigrate && sizeChanged && !sizeReason" class="resize-options">
        <summary>{{ $t('label.volume.resize.more.options') }}</summary>
        <a-checkbox v-model:checked="form.automigrate" :disabled="disabled">{{ $t('label.automigrate.volume') }}</a-checkbox>
      </details>
      <MoldDialogActions class="resize-footer" :busy="loading">
        <span class="resize-change-summary resize-secondary" aria-live="polite">{{ changeSummary }}</span>
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button v-if="jobId" :loading="loading" type="primary" @click="resumeJob">{{ $t('message.volume.edit.check.result') }}</a-button>
        <a-button v-else :loading="loading" :disabled="!contract || !current() || submissionUnknown" type="primary" ref="submit" @click="handleSubmit">{{ $t('label.change') }}</a-button>
      </MoldDialogActions>
    </a-form>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive } from 'vue'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import { readVolumeResizeContract, volumeResizeSizeReason, volumeResizeIops, volumeResizeParams, validateVolumeResizeSnapshot } from '@/utils/volumeResize'

export default {
  components: { MoldDialogActions },
  name: 'ResizeVolume',
  props: { resource: { type: Object, required: true } },
  emits: ['refresh-data', 'close-action'],
  data () { return { loading: false, contract: null, errorText: '', jobId: null, submissionUnknown: false, active: true } },
  beforeCreate () { this.apiParams = this.$getApiParams('resizeVolume') || {} },
  created () {
    this.target = Object.freeze({ ...this.resource })
    this.originalScope = this.scope()
    this.formRef = ref()
    this.form = reactive({ size: String(this.target.size / 1073741824), miniops: this.target.miniops == null ? '' : String(this.target.miniops), maxiops: this.target.maxiops == null ? '' : String(this.target.maxiops), shrinkok: false, automigrate: false })
    this.fetchData()
  },
  beforeUnmount () { this.active = false },
  computed: {
    sizeReason () { return volumeResizeSizeReason(this.target, this.contract) },
    rootFixed () { return this.contract?.kind === 'compute' && Number(this.contract.offering.rootdisksize) > 0 },
    customIops () { return volumeResizeIops(this.target, this.contract) },
    disabled () { return this.loading || !!this.jobId || !this.contract || !this.current() },
    currentSize () { return this.target.size / 1073741824 },
    sizeDelta () {
      const size = String(this.form.size).trim()
      if (!size) return 0
      const value = Number(size)
      return /^\d+$/.test(size) && Number.isSafeInteger(value * 1073741824) && value > 0 ? value - this.currentSize : null
    },
    sizeChanged () { return !!this.apiParams.size && !this.sizeReason && this.sizeDelta !== null && this.sizeDelta !== 0 },
    isShrinking () { return this.sizeChanged && this.sizeDelta < 0 },
    shrinkBlocked () { return this.target.hypervisor === 'XenServer' || (this.target.state !== 'Allocated' && String(this.target.allocation?.actualformat || this.target.format).toLowerCase() === 'qcow2') },
    changeSummary () {
      if (!this.contract) return ''
      try {
        const params = volumeResizeParams(this.target, { ...this.form }, this.contract, this.apiParams)
        const changes = []
        if (params.size !== undefined) changes.push(this.$t(this.sizeDelta > 0 ? 'message.volume.resize.grow.by' : 'message.volume.resize.shrink.by', { size: Math.abs(this.sizeDelta) }))
        if (params.miniops !== undefined || params.maxiops !== undefined) changes.push(this.$t('label.volume.resize.iops.changed'))
        return changes.join(' · ') || this.$t('message.volume.resize.no.changes')
      } catch (error) { return this.$t('message.volume.resize.check.input') }
    }
  },
  methods: {
    scope () {
      const s = this.$store.getters
      return JSON.stringify([this.resource.id, s.userInfo?.id, s.userInfo?.roletype, s.project?.id, this.$route.path])
    },
    current () { return this.active && this.originalScope === this.scope() },
    failure (error) {
      const body = error?.response?.data
      const text = body?.errorresponse?.errortext || Object.values(body || {}).find(value => value?.errortext)?.errortext || error?.errortext || error?.message
      this.errorText = text?.startsWith('message.') ? this.$t(text) : text || this.$t('message.request.failed')
    },
    async fetchData () {
      if (this.loading) return
      this.loading = true
      this.errorText = ''
      this.contract = null
      try {
        if (!this.current()) throw new Error('message.volume.resize.context')
        this.contract = await readVolumeResizeContract(getAPI, this.target, () => this.current())
      } catch (error) { if (this.active) this.failure(error) } finally { this.loading = false }
    },
    async handleSubmit () {
      if (this.loading || this.jobId || !this.contract || this.submissionUnknown) return
      this.loading = true
      this.errorText = ''
      try {
        if (!this.current()) throw new Error('message.volume.resize.context')
        const params = volumeResizeParams(this.target, { ...this.form }, this.contract, this.apiParams)
        if (Object.keys(params).length === 1) { this.closeModal(); return }
        const response = await getAPI('listVolumes', { id: this.target.id, listall: true, ...(this.target.projectid ? { projectid: this.target.projectid } : {}) }, { preserveOnFailure: true })
        if (!this.current()) throw new Error('message.volume.resize.context')
        const rows = response?.listvolumesresponse?.volume
        const fresh = Array.isArray(rows) && rows.length === 1 ? rows[0] : null
        if (!fresh || fresh.id !== this.target.id) throw new Error('message.volume.resize.changed')
        const contract = await readVolumeResizeContract(getAPI, fresh, () => this.current())
        validateVolumeResizeSnapshot(this.target, fresh, this.contract, contract)
        this.submissionUnknown = true
        const result = (await postAPI('resizeVolume', params, { preserveOnFailure: true }))?.resizevolumeresponse
        if (!result?.jobid) throw new Error('message.volume.resize.unknown')
        if (!this.current()) return
        this.submissionUnknown = false
        this.jobId = result.jobid
        await this.poll(false)
      } catch (error) {
        if (error?.response?.status >= 400 && error?.response?.data) this.submissionUnknown = false
        if (this.active) {
          if (this.submissionUnknown) this.failure(new Error('message.volume.resize.unknown'))
          else this.failure(error)
        }
      } finally { this.loading = false }
    },
    async poll (retry) {
      const result = await this.$pollJob({
        jobId: this.jobId,
        batchKey: this.jobId,
        resourceId: this.target.id,
        retry,
        title: this.$t('label.action.resize.volume'),
        description: this.target.name,
        successMessage: this.$t('message.success.resize.volume'),
        errorMessage: this.$t('message.resize.volume.failed')
      })
      if (!this.current()) return
      if (result.jobstatus === 1) { this.jobId = null; this.$emit('refresh-data'); this.$emit('close-action') } else if (result.jobstatus === 2) { this.jobId = null; this.failure(result.jobresult) } else this.errorText = this.$t('message.volume.resize.unknown')
    },
    async resumeJob () {
      if (this.loading || !this.jobId) return
      if (!this.current()) { this.failure(new Error('message.volume.resize.context')); return }
      this.loading = true
      try { await this.poll(true) } catch (error) { if (this.active) this.failure(error) } finally { this.loading = false }
    },
    closeModal () { this.$emit('close-action') }
  }
}
</script>
<style lang="scss" scoped>
.form-layout {
  width: 85vw;
  @media (min-width: 760px) { width: 500px; }
}
.resize-target { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 24px; overflow-wrap: anywhere; }
.resize-secondary, .form-help { font-size: 12px; opacity: .8; }
.resize-label { display: flex; justify-content: space-between; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; width: 100%; }
:deep(.ant-form-item-label > label) { width: 100%; }
.form-help { margin: 8px 0 0; }
.resize-size { margin-bottom: 24px; }
.resize-section-title { margin-bottom: 12px; font-weight: 500; }
.resize-iops-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
.resize-iops-fields .ant-form-item { display: block; min-width: 0; margin-bottom: 0; }
.resize-iops-fields :deep(.ant-form-item-label), .resize-iops-fields :deep(.ant-form-item-control) { width: 100%; max-width: 100%; min-width: 0; }
.resize-shrink { margin-top: 8px; }
.resize-options { margin-top: 20px; }
.resize-options summary { cursor: pointer; padding: 4px 0; }
.resize-options .ant-checkbox-wrapper { margin-top: 12px; }
.resize-footer { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; border-top: 1px solid var(--ui-border); padding-top: 16px; margin-top: 24px; }
.resize-footer .ant-btn { margin: 0; }
.resize-change-summary { flex: 1; min-width: 100px; text-align: left; font-variant-numeric: tabular-nums; }
@media (max-width: 480px) { .resize-iops-fields { grid-template-columns: minmax(0, 1fr); } }
</style>
