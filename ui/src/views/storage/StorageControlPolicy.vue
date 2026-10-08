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
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-button v-if="canConfigure" :disabled="!writable" @click="open">{{ $t('label.storage.control.policy.configure') }}</a-button>
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
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'StorageControlPolicy',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, required: true } },
  emits: ['operation-updated'],
  data: () => ({ generation: 0, loading: false, saving: false, policy: null, error: '', visible: false, desiredEnabled: false, confirmation: '', requestKey: '', requestIntent: null, saveError: '', jobId: '' }),
  computed: {
    supported () { return 'getStorageServiceControlPolicy' in (this.$store?.getters?.apis || {}) },
    canConfigure () { return 'configureStorageServiceControlPolicy' in (this.$store?.getters?.apis || {}) },
    writable () { return this.canConfigure && !this.loading && !this.saving && !this.error && this.policy?.instanceUuid === this.instanceId && Number.isSafeInteger(this.policy.policyRevision) && this.policy.policyRevision >= 0 },
    confirmed () { return this.writable && !!this.instanceName && this.confirmation === this.instanceName && this.desiredEnabled !== this.policy.enabled },
    columns () { return [{ title: this.$t('label.type'), dataIndex: 'label' }, { title: this.$t('label.state'), dataIndex: 'state' }] },
    coverage () { return Object.entries(this.policy?.coverage || {}).map(([action, state]) => ({ action, label: this.$t('label.storage.control.policy.action.' + action), state: this.$t('label.storage.control.policy.coverage.' + state) })) }
  },
  watch: {
    instanceId () { this.generation++; this.policy = null; this.loading = false; this.saving = false; this.visible = false; this.error = ''; this.requestKey = ''; this.requestIntent = null; this.refresh() }
  },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++ },
  methods: {
    evidence (response, command, instance) {
      const root = response[command.toLowerCase() + 'response']?.storageserviceruntime || response.storageserviceruntime || response
      if (root.success !== true) throw new Error(root.errortext || root.details || this.$t('message.storage.control.policy.read.failed'))
      const value = typeof root.resultjson === 'string' ? JSON.parse(root.resultjson) : root.resultjson
      if (value?.instanceUuid !== instance || !Number.isSafeInteger(value.policyRevision) || value.policyRevision < 0 || typeof value.enabled !== 'boolean') throw new Error(this.$t('message.storage.control.policy.read.failed'))
      return value
    },
    async refresh () {
      if (!this.supported || this.loading) return
      const generation = this.generation; const instance = this.instanceId
      this.loading = true
      try {
        const result = await getAPI('getStorageServiceControlPolicy', { instanceid: instance }, { timeout: 15000, preserveOnFailure: true })
        if (generation !== this.generation || instance !== this.instanceId) return
        this.policy = this.evidence(result, 'getStorageServiceControlPolicy', instance); this.error = ''
      } catch (error) { if (generation === this.generation) this.error = error.message } finally { if (generation === this.generation) this.loading = false }
    },
    open () {
      if (!this.writable) return
      this.visible = true; this.desiredEnabled = this.requestIntent ? this.requestIntent.enabled : this.policy.enabled; this.confirmation = ''; this.saveError = ''; this.jobId = ''
      if (!this.requestKey) this.requestKey = 'control-policy-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
    },
    close () { if (!this.saving) this.visible = false },
    async save () {
      if (!this.confirmed || this.saving) return
      const generation = this.generation; const instance = this.instanceId
      this.saving = true; this.saveError = ''
      try {
        const intent = this.requestIntent || { instanceid: instance, enabled: this.desiredEnabled, expectedpolicyrevision: this.policy.policyRevision, confirmation: this.confirmation, idempotencykey: this.requestKey }
        if (intent.instanceid !== instance || intent.confirmation !== this.confirmation || intent.enabled !== this.desiredEnabled) throw new Error(this.$t('message.storage.control.policy.unknown'))
        this.requestIntent = intent
        const response = await postAPI('configureStorageServiceControlPolicy', intent, { timeout: 15000 })
        if (generation !== this.generation || instance !== this.instanceId) return
        this.jobId = response.configurestorageservicecontrolpolicyresponse?.jobid || ''
        if (!this.jobId) throw new Error(this.$t('message.storage.control.policy.unknown'))
        let verified = false
        for (let attempt = 0; attempt < 90; attempt++) {
          const response = await getAPI('queryAsyncJobResult', { jobid: this.jobId }, { timeout: 15000, preserveOnFailure: true })
          if (generation !== this.generation || instance !== this.instanceId) return
          const job = response.queryasyncjobresultresponse
          if (job.jobstatus === 2) { this.requestKey = ''; this.requestIntent = null; throw new Error(job.jobresult?.errortext || this.$t('message.storage.config.failed')) }
          if (job.jobstatus === 1) {
            const result = this.evidence(job.jobresult, 'configureStorageServiceControlPolicy', instance)
            if (result.enabled !== this.desiredEnabled) throw new Error(this.$t('message.storage.control.policy.unknown'))
            this.policy = result; verified = true; break
          }
          await new Promise(resolve => setTimeout(resolve, 1000))
        }
        if (!verified) throw new Error(this.$t('message.storage.control.policy.unknown'))
        this.visible = false; this.requestKey = ''; this.requestIntent = null; this.$emit('operation-updated', instance); await this.refresh()
      } catch (error) {
        if (generation === this.generation) {
          const root = error.response?.data?.configurestorageservicecontrolpolicyresponse || error.response?.data?.errorresponse
          this.saveError = root?.errortext || error.message
        }
      } finally { if (generation === this.generation) this.saving = false }
    }
  }
}
</script>
