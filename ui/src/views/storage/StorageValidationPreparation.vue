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
  <section v-if="supported" class="storage-validation-preparation">
    <a-space wrap>
      <a-button type="primary" :disabled="!canOpen" @click="open">{{ $t('label.storage.service.validation.fixture.prepare') }}</a-button>
      <a-button :loading="loading" :disabled="saving || !active" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert type="info" show-icon :message="$t('message.storage.service.validation.fixture.scope')" />
    <a-alert v-if="error" type="warning" show-icon :message="error" />
    <a-alert v-if="preview?.enabled && preview.expiresAtMillis" :type="profileActive ? 'success' : 'warning'" show-icon :message="$t(profileActive ? 'message.storage.service.validation.fixture.active' : 'message.storage.service.validation.fixture.expired')" :description="time(preview.expiresAtMillis)" />
    <a-alert v-for="blocker in (preview?.blockers || [])" :key="blocker" type="warning" show-icon :message="blockerText(blocker)" />
    <a-modal
      :visible="visible"
      :title="$t('label.storage.service.validation.fixture.prepare')"
      :confirm-loading="saving"
      :ok-text="$t(jobId ? 'label.storage.service.validation.fixture.follow.job' : 'label.ok')"
      :ok-button-props="{ disabled: !confirmed }"
      :cancel-button-props="{ disabled: saving }"
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      :mask-closable="false"
      @ok="save"
      @cancel="close">
      <a-alert type="warning" show-icon :message="$t('message.storage.service.validation.fixture.confirm')" />
      <a-descriptions v-if="preview" bordered size="small" :column="1">
        <a-descriptions-item :label="$t('label.name')">{{ preview.instanceName }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.virtualmachine')"><code>{{ preview.vmUuid || '—' }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.service.validation.fixture.root')"><code>{{ preview.bindings?.rootVolumeUuid || '—' }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.service.validation.fixture.runtime')"><code>{{ preview.expectedCliSha256 || '—' }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.service.validation.fixture.source')"><code>{{ preview.sourceCommit || '—' }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.service.validation.fixture.production')">{{ $t('label.no') }}</a-descriptions-item>
      </a-descriptions>
      <a-table size="small" :columns="columns" :data-source="volumes" :pagination="false" row-key="id" :scroll="{ x: 520 }" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.service.validation.fixture.hours')">
          <a-input-number v-model:value="expiresInHours" :min="1" :max="24" :precision="0" :disabled="saving || attempted" />
        </a-form-item>
        <a-form-item :label="$t('label.storage.config.confirmation')">
          <a-input v-model:value="confirmation" :placeholder="instanceName" :disabled="saving || attempted" autocomplete="off" />
        </a-form-item>
      </a-form>
      <a-alert v-if="saveError" type="error" show-icon :message="saveError" />
      <a-alert v-if="jobId" type="info" show-icon :message="jobId" />
    </a-modal>
  </section>
</template>
<script>
import { storageReadDeadline } from '@/utils/storageRead'
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { readStorageValidationPreparation, storageValidationScope, storageValidationBindings } from '@/utils/storageValidationPreparation'

const security = vm => JSON.stringify([vm.$store?.getters?.userInfo?.id, vm.$store?.getters?.userInfo?.roletype, vm.$store?.getters?.project?.id, vm.$store?.state?.user?.token])
export default {
  name: 'StorageValidationPreparation',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, required: true }, active: { type: Boolean, default: false }, scopeKey: { type: String, default: '' } },
  emits: ['operation-updated'],
  data: () => ({ generation: 0, disposed: false, loading: false, saving: false, preview: null, error: '', visible: false, confirmation: '', expiresInHours: 4, saveError: '', jobId: '', attempted: false, reviewedScope: '', clock: Date.now() }),
  computed: {
    supported () {
      const apis = this.$store?.getters?.apis || {}
      return this.$store?.getters?.userInfo?.roletype === 'Admin' && !!this.$store?.state?.user?.token && !this.$store?.getters?.project?.id &&
        'getStorageRenderedValidationPreparation' in apis && 'prepareStorageRenderedValidationProfile' in apis
    },
    contextKey () { return JSON.stringify([this.instanceId, this.instanceName, this.active, this.scopeKey, this.supported, security(this)]) },
    previewFresh () { return !!this.preview && this.preview.instanceUuid === this.instanceId && this.preview.instanceName === this.instanceName && this.preview.previewExpiresAtMillis > this.clock },
    profileActive () { return this.preview?.enabled === true && this.preview.expiresAtMillis > this.clock },
    canOpen () {
      if (this.attempted && this.jobId) return this.active && this.supported && !this.loading && !this.saving
      return this.active && this.supported && !this.loading && !this.saving && !this.attempted && this.previewFresh && !this.error && this.preview?.eligible === true && !this.profileActive
    },
    confirmed () {
      if (this.jobId && this.attempted) return !this.saving && this.active && this.supported
      return this.canOpen && !this.attempted && !!this.instanceName && this.confirmation === this.instanceName && this.reviewedScope === storageValidationScope(this.preview) &&
        Number.isInteger(this.expiresInHours) && this.expiresInHours >= 1 && this.expiresInHours <= 24
    },
    columns () { return [{ title: this.$t('label.id'), dataIndex: 'id' }, { title: this.$t('label.type'), dataIndex: 'type' }, { title: this.$t('label.storage.service.validation.fixture.provisioning'), dataIndex: 'provisioningType' }] },
    volumes () { return Object.entries(this.preview?.bindings?.volumes || {}).map(([id, row]) => ({ id, ...row })) }
  },
  watch: {
    contextKey () { this.reset(); if (this.active) this.refresh() }
  },
  mounted () { if (this.active) this.refresh() },
  beforeUnmount () { this.disposed = true; this.reset() },
  methods: {
    blockerText (reason) {
      const known = ['EXPLICIT_ISSUE909_TEST_FIXTURE_REQUIRED', 'NEW_OWNED_FIXTURE_VM_REQUIRED', 'CURRENT_BOOT_PREPARATION_REQUIRED', 'NEW_SPARSE_DATA4_BINDINGS_REQUIRED', 'SIGNED_INSTALLED_RUNTIME_REQUIRED', 'SIGNED_RUNTIME_SOURCE_PIN_REQUIRED', 'SIGNED_RUNTIME_ACTUAL_READBACK_REQUIRED', 'INSTALLED_RENDERED_HANDLER_REQUIRED', 'PROTECTED_ORIGINAL_EXCLUSION_SET_REQUIRED', 'PROFILE_ARTIFACT_UNAVAILABLE']
      return known.includes(reason) ? this.$t('message.storage.service.validation.fixture.blocker.' + reason) : reason || this.$t('message.storage.service.preparation.wait')
    },
    time (millis) { return Number.isSafeInteger(millis) && millis > 0 ? new Date(millis).toLocaleString() : '—' },
    reset () {
      this.generation++; this.loading = false; this.saving = false; this.preview = null; this.error = ''; this.visible = false
      this.confirmation = ''; this.saveError = ''; this.jobId = ''; this.attempted = false; this.reviewedScope = ''; clearTimeout(this.expiryTimer)
    },
    expire () {
      clearTimeout(this.expiryTimer)
      if (this.preview) this.expiryTimer = setTimeout(() => { this.clock = Date.now() }, Math.max(1, Math.min(this.preview.previewExpiresAtMillis + 1, this.preview.expiresAtMillis > Date.now() ? this.preview.expiresAtMillis + 1 : Infinity) - Date.now()))
    },
    async refresh () {
      if (this.disposed || !this.active || !this.supported || this.loading || this.saving) return
      const generation = this.generation; const context = this.contextKey
      const current = () => !this.disposed && generation === this.generation && context === this.contextKey && this.active && this.supported
      this.loading = true
      try {
        const response = await storageReadDeadline(getAPI('getStorageRenderedValidationPreparation', { instanceid: this.instanceId }, { timeout: 60000, preserveOnFailure: true }), 60000)
        if (!current()) return
        this.preview = readStorageValidationPreparation(response, 'getStorageRenderedValidationPreparation', this.instanceId, this.instanceName)
        this.clock = Date.now(); this.error = ''; this.expire()
        // Refresh is an explicit new review, never an automatic retry of an unknown approval.
        if (!this.visible) { this.attempted = false; this.jobId = ''; this.reviewedScope = '' }
      } catch (error) { if (current()) { this.preview = null; this.error = this.$t('message.storage.service.validation.fixture.read.failed') } } finally { if (current()) this.loading = false }
    },
    open () {
      if (!this.canOpen) return
      this.visible = true; this.saveError = ''
      if (this.attempted && this.jobId) return
      this.confirmation = ''; this.reviewedScope = storageValidationScope(this.preview)
    },
    close () { if (!this.saving) this.visible = false },
    async save () {
      if (!this.confirmed || this.saving) return
      const generation = this.generation; const context = this.contextKey
      const current = () => !this.disposed && generation === this.generation && context === this.contextKey && this.active && this.supported
      this.saving = true; this.saveError = ''
      try {
        if (!this.jobId) {
          const params = { instanceid: this.instanceId, expectedprofilerevision: this.preview.profileRevision, confirmation: this.confirmation, expiresinhours: this.expiresInHours, previewtoken: this.preview.previewToken }
          this.attempted = true
          const response = await postAPI('prepareStorageRenderedValidationProfile', params, { timeout: 30000, preserveOnFailure: true })
          if (!current()) return
          this.jobId = response.preparestoragerenderedvalidationprofileresponse?.jobid || ''
          if (!this.jobId) throw new Error(this.$t('message.storage.service.validation.fixture.unknown'))
        }
        let complete = false
        for (let attempt = 0; attempt < 90; attempt++) {
          if (!current()) return
          const response = await getAPI('queryAsyncJobResult', { jobid: this.jobId }, { timeout: 15000, preserveOnFailure: true })
          if (!current()) return
          const job = response.queryasyncjobresultresponse
          if (job?.jobstatus === 2) { this.jobId = ''; throw new Error(job.jobresult?.errortext || this.$t('message.storage.service.validation.fixture.failed')) }
          if (job?.jobstatus === 1) {
            const value = readStorageValidationPreparation(job.jobresult, 'prepareStorageRenderedValidationProfile', this.instanceId, this.instanceName)
            if (!value.enabled || value.productionCapability !== false || value.profileRevision !== this.preview.profileRevision + 1 || value.expiresAtMillis <= Date.now() ||
                !value.artifactUuid || !/^[a-f0-9]{64}$/.test(value.artifactSha256) || storageValidationBindings(value.bindings) !== storageValidationBindings(this.preview.bindings) ||
                value.expectedCliSha256 !== this.preview.expectedCliSha256) throw new Error(this.$t('message.storage.service.validation.fixture.unknown'))
            this.preview = value; this.clock = Date.now(); this.expire(); complete = true; break
          }
          if (job?.jobstatus !== 0) throw new Error(this.$t('message.storage.service.validation.fixture.unknown'))
          await new Promise(resolve => setTimeout(resolve, 1000))
        }
        if (!complete) throw new Error(this.$t('message.storage.service.validation.fixture.unknown'))
        this.visible = false; this.attempted = false; this.jobId = ''; this.error = ''; this.$emit('operation-updated', this.instanceId)
      } catch (error) {
        if (current()) {
          const root = error.response?.data?.preparestoragerenderedvalidationprofileresponse || error.response?.data?.errorresponse
          this.saveError = root?.errortext || error.message
        }
      } finally { if (current()) this.saving = false }
    }
  }
}
</script>
<style scoped>
.storage-validation-preparation { margin-bottom: 16px; }
.storage-validation-preparation > * + * { margin-top: 10px; }
.storage-validation-preparation code { overflow-wrap: anywhere; }
</style>
