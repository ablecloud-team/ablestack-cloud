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
  <section v-if="supported">
    <a-space wrap>
      <a-button :loading="loading" :disabled="saving" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-button v-if="canConfigure" :disabled="!canOpen" @click="open">{{ $t('label.storage.control.policy.configure') }}</a-button>
    </a-space>
    <a-alert v-if="error" type="warning" show-icon :message="error" />
    <a-descriptions v-if="policy" bordered size="small" :column="2">
      <a-descriptions-item :label="$t('label.storage.control.policy.enabled')">{{ policy.enabled ? $t('label.yes') : $t('label.no') }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.control.policy.active')">{{ policy.active ? $t('label.yes') : $t('label.no') }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.control.policy.revision')">{{ policy.policyRevision }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.control.policy.global')">{{ policy.globalEnabled ? $t('label.yes') : $t('label.no') }}</a-descriptions-item>
    </a-descriptions>
    <a-alert v-if="policy" type="info" show-icon :message="$t('message.storage.control.policy.logical')" />
    <a-alert v-for="blocker in (policy?.blockers || [])" :key="blocker" type="warning" show-icon :message="blocker" />
    <a-table v-if="policy" size="small" :columns="columns" :data-source="coverage" :pagination="false" row-key="action" />
    <a-modal
:visible="visible"
:title="$t('label.storage.control.policy.configure')"
:confirm-loading="saving"
:ok-button-props="{ disabled: !confirmed || saving }"
:cancel-button-props="{ disabled: saving }"
:body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
@ok="save"
@cancel="close">
      <a-alert type="info" show-icon :message="$t('message.storage.control.policy.scope')" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.control.policy.enabled')"><a-switch v-model:checked="desiredEnabled" :disabled="saving || !!requestIntent" /></a-form-item>
        <a-form-item :label="$t('label.storage.config.confirmation')"><a-input v-model:value="confirmation" :placeholder="instanceName" :disabled="saving" autocomplete="off" /></a-form-item>
      </a-form>
      <a-alert v-if="saveError" type="error" show-icon :message="saveError" />
      <a-alert v-if="jobId" type="info" show-icon :message="jobId" />
    </a-modal>
  </section>
</template>
<script>
import { storageMutationGateMixin, requireStorageMutationAllowed } from '@/utils/storageRecoveryGate'
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'

const securityScope = vm => [vm.$store?.getters?.userInfo?.id || '', vm.$store?.getters?.project?.id || ''].join('|')
export default {
  mixins: [storageMutationGateMixin],
  name: 'StorageControlPolicy',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, required: true } },
  emits: ['operation-updated'],
  data: () => ({ generation: 0, readSequence: 0, disposed: false, requestAttempted: false, loading: false, saving: false, policy: null, error: '', visible: false, desiredEnabled: false, confirmation: '', requestKey: '', requestIntent: null, saveError: '', jobId: '' }),
  computed: {
    supported () { return 'getStorageServiceControlPolicy' in (this.$store?.getters?.apis || {}) },
    canConfigure () { return 'configureStorageServiceControlPolicy' in (this.$store?.getters?.apis || {}) },
    operationSecurityScope () { return securityScope(this) },
    writable () { return !this.disposed && this.canConfigure && !this.loading && !this.saving && !this.error && this.policy?.instanceUuid === this.instanceId && Number.isSafeInteger(this.policy.policyRevision) && this.policy.policyRevision >= 0 },
    retainedRequest () {
      const intent = this.requestIntent
      return !this.disposed && this.requestAttempted && !!this.requestKey && intent?.idempotencykey === this.requestKey && intent.instanceid === this.instanceId &&
        intent.confirmation === this.instanceName && typeof intent.enabled === 'boolean' && Number.isSafeInteger(intent.expectedpolicyrevision) && intent.expectedpolicyrevision >= 0
    },
    canOpen () {
      const knownRead = this.retainedRequest && !!this.jobId && this.canConfigure && !this.disposed && !this.loading && !this.saving
      return (this.writable || knownRead) && (!this.storageMutationDisabled || this.retainedRequest)
    },
    confirmed () {
      const retained = this.retainedRequest && this.desiredEnabled === this.requestIntent.enabled
      const knownRead = retained && !!this.jobId && this.canConfigure && !this.disposed && !this.loading && !this.saving
      return (this.writable || knownRead) && !!this.instanceName && this.confirmation === this.instanceName &&
        (this.requestIntent ? retained : this.desiredEnabled !== this.policy?.enabled) && (!this.storageMutationDisabled || retained)
    },
    columns () { return [{ title: this.$t('label.type'), dataIndex: 'label' }, { title: this.$t('label.state'), dataIndex: 'state' }] },
    coverage () { return Object.entries(this.policy?.coverage || {}).map(([action, state]) => ({ action, label: this.$t('label.storage.control.policy.action.' + action), state: this.$t('label.storage.control.policy.coverage.' + state) })) }
  },
  watch: {
    instanceId () { this.invalidate(); this.refresh() },
    operationSecurityScope () { this.invalidate(); this.refresh() },
    '$store.state.user.token' (token) { this.invalidate(); if (token) this.refresh() }
  },
  mounted () { this.refresh() },
  beforeUnmount () { this.disposed = true; this.generation++; this.readSequence++ },
  methods: {
    invalidate () {
      this.generation++; this.readSequence++; this.policy = null; this.loading = false; this.saving = false; this.visible = false
      this.error = ''; this.saveError = ''; this.confirmation = ''; this.desiredEnabled = false; this.clearRequest()
    },
    clearRequest () { this.requestKey = ''; this.requestIntent = null; this.jobId = ''; this.requestAttempted = false },
    evidence (response, command, instance) {
      const root = response[command.toLowerCase() + 'response']?.storageserviceruntime || response.storageserviceruntime || response
      if (root.success !== true) throw new Error(root.errortext || root.details || this.$t('message.storage.control.policy.read.failed'))
      const value = typeof root.resultjson === 'string' ? JSON.parse(root.resultjson) : root.resultjson
      if (value?.instanceUuid !== instance || !Number.isSafeInteger(value.policyRevision) || value.policyRevision < 0 || typeof value.enabled !== 'boolean') throw new Error(this.$t('message.storage.control.policy.read.failed'))
      return value
    },
    async refresh () {
      if (this.disposed || !this.supported || this.loading || this.saving) return
      const generation = this.generation; const sequence = ++this.readSequence; const instance = this.instanceId; const security = securityScope(this)
      const current = () => !this.disposed && generation === this.generation && sequence === this.readSequence && instance === this.instanceId && security === securityScope(this)
      this.loading = true
      try {
        const result = await getAPI('getStorageServiceControlPolicy', { instanceid: instance }, { timeout: 15000, preserveOnFailure: true })
        if (!current()) return
        const policy = this.evidence(result, 'getStorageServiceControlPolicy', instance)
        if (this.policy && policy.policyRevision < this.policy.policyRevision) throw new Error(this.$t('message.storage.control.policy.read.failed'))
        this.policy = policy; this.error = ''
      } catch (error) { if (current()) this.error = error.message } finally { if (current()) this.loading = false }
    },
    open () {
      if (!this.canOpen) return
      this.visible = true; this.desiredEnabled = this.retainedRequest ? this.requestIntent.enabled : this.policy.enabled; this.confirmation = ''; this.saveError = ''
      if (!this.retainedRequest) this.clearRequest()
    },
    close () { if (!this.saving) { this.visible = false; if (!this.requestAttempted) this.clearRequest() } },
    async save () {
      if (!this.confirmed || this.saving) return
      const generation = this.generation; const instance = this.instanceId; const security = securityScope(this)
      const current = () => !this.disposed && generation === this.generation && instance === this.instanceId && security === securityScope(this)
      const retained = this.retainedRequest
      let refreshAfter = false
      this.readSequence++; this.saving = true; this.saveError = ''
      try {
        // Only an already attempted exact intent may replay; a known job is READ-only.
        if (!this.jobId) {
          if (!retained) {
            requireStorageMutationAllowed(this, 'configureStorageServiceControlPolicy')
            if (!this.requestKey) this.requestKey = 'control-policy-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
            this.requestIntent = { instanceid: instance, enabled: this.desiredEnabled, expectedpolicyrevision: this.policy.policyRevision, confirmation: this.confirmation, idempotencykey: this.requestKey }
          }
          this.requestAttempted = true
          const response = await postAPI('configureStorageServiceControlPolicy', this.requestIntent, { timeout: 15000, preserveOnFailure: true })
          if (!current()) return
          this.jobId = response.configurestorageservicecontrolpolicyresponse?.jobid || ''
          if (!this.jobId) throw new Error(this.$t('message.storage.control.policy.unknown'))
        }
        const jobId = this.jobId
        let verified = false
        for (let attempt = 0; attempt < 90; attempt++) {
          if (!current()) return
          const response = await getAPI('queryAsyncJobResult', { jobid: jobId }, { timeout: 15000, preserveOnFailure: true })
          if (!current()) return
          const job = response.queryasyncjobresultresponse
          if (job?.jobstatus === 2) { this.clearRequest(); throw new Error(job.jobresult?.errortext || this.$t('message.storage.config.failed')) }
          if (job?.jobstatus === 1) {
            const result = this.evidence(job.jobresult, 'configureStorageServiceControlPolicy', instance)
            if (result.enabled !== this.requestIntent.enabled) throw new Error(this.$t('message.storage.control.policy.unknown'))
            this.policy = result; verified = true; break
          }
          if (job?.jobstatus !== 0) throw new Error(this.$t('message.storage.control.policy.unknown'))
          await new Promise(resolve => setTimeout(resolve, 1000))
        }
        if (!verified) throw new Error(this.$t('message.storage.control.policy.unknown'))
        this.visible = false; this.clearRequest(); this.error = ''; this.$emit('operation-updated', instance); refreshAfter = true
      } catch (error) {
        if (current()) {
          const root = error.response?.data?.configurestorageservicecontrolpolicyresponse || error.response?.data?.errorresponse
          this.saveError = root?.errortext || error.message
        }
      } finally {
        if (current()) { this.saving = false; if (refreshAfter) await this.refresh() }
      }
    }
  }
}
</script>
