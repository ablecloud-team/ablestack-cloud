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
  <div class="template-upgrade-layout">
    <div class="template-upgrade-content">
      <a-alert type="warning" show-icon :message="$t('message.storage.template.impact')" />
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.storage.template.read.failed')" />
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item :label="$t('label.storage.template.current')">{{ capability.currentTemplateName || capability.currentTemplateUuid || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.template.current.root')">{{ capability.currentRootVolumeUuid || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.posix.directory.revision')">{{ capability.desiredRevision ?? '—' }}</a-descriptions-item>
      </a-descriptions>
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.template.target')" required>
          <a-select v-model:value="targetTemplate" :disabled="loading || submitting || !!plan" @change="clearPlan">
            <a-select-option v-for="template in templates" :key="template.templateUuid" :value="template.templateUuid" :disabled="!template.compatible || template.current">
              {{ template.templateName }} · {{ template.templateVersion || '—' }}
              <span v-if="!template.compatible"> · {{ template.blockers.join(', ') }}</span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <template v-if="plan">
          <a-alert v-for="blocker in blockers" :key="blocker" type="error" show-icon :message="blocker" />
          <a-descriptions bordered size="small" :column="1">
            <a-descriptions-item :label="$t('label.storage.template.sessions')">{{ preflight.activeSessions ?? '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.template.identity')">{{ identityLabel }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.template.data.preserved')">{{ preflight.dataVolumes?.length ?? '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.template.rollback.available')">{{ preflight.rollbackAvailable === false ? $t('label.no') : $t('label.yes') }}</a-descriptions-item>
          </a-descriptions>
          <a-checkbox v-model:checked="maintenanceWindow">{{ $t('message.storage.template.maintenance.confirm') }}</a-checkbox>
          <a-form-item :label="$t('label.storage.config.confirmation')" required>
            <a-input v-model:value="confirmation" :placeholder="resource.name" autocomplete="off" />
          </a-form-item>
        </template>
      </a-form>
    </div>
    <div class="template-upgrade-footer">
      <a-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="plan ? upgrade() : preflightPlan()">
        {{ $t(plan ? 'label.storage.template.execute' : 'label.storage.template.preflight') }}
      </a-button>
      <a-button :disabled="submitting" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button :loading="loading" :disabled="submitting" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-button v-if="plan" :disabled="submitting" @click="clearPlan">{{ $t('label.storage.template.change.target') }}</a-button>
    </div>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'

export default {
  name: 'StorageServiceSystemVmTemplateUpgrade',
  components: { ReloadOutlined },
  props: { resource: { type: Object, required: true }, pollJob: { type: Boolean, default: true } },
  emits: ['close-action', 'accepted', 'operation-updated'],
  data: () => ({ loading: false, submitting: false, error: '', readFailed: false, scope: 0, capability: {}, templates: [], targetTemplate: undefined, plan: null, confirmation: '', maintenanceWindow: false }),
  computed: {
    preflight () { return this.plan?.preflight || {} },
    blockers () { return this.preflight.blockers || this.plan?.blockers || [] },
    identityLabel () {
      const migration = this.preflight.identityMigration || this.capability.identityMigration
      if (!migration) return '—'
      if (typeof migration === 'string') return migration
      return migration.available === false ? this.$t('label.no') : this.$t('label.storage.template.identity.protected')
    },
    canSubmit () {
      if (this.loading || this.submitting || !this.targetTemplate) return false
      if (!this.plan) return this.templates.some(row => row.templateUuid === this.targetTemplate && row.compatible && !row.current)
      return this.preflight.compatible === true && !this.blockers.length && this.maintenanceWindow && this.confirmation === this.resource.name && this.preflight.rollbackAvailable !== false
    }
  },
  watch: { 'resource.id' () { this.scope++; this.submitting = false; this.clearPlan(); this.capability = {}; this.templates = []; this.targetTemplate = undefined; this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.scope++ },
  methods: {
    unwrap (response, api) {
      const body = response[api.toLowerCase() + 'response'] || response
      const value = body.storageservicetemplateupgrade || body
      return typeof value.result === 'string' ? JSON.parse(value.result) : value.result || value
    },
    clearPlan () { this.plan = null; this.confirmation = ''; this.maintenanceWindow = false; this.error = '' },
    async refresh () {
      const token = ++this.scope; const id = this.resource.id; this.loading = true
      try {
        const responses = await Promise.all([
          getAPI('getStorageServiceTemplateUpgradeCapabilities', { sharedfilesystemid: id }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listStorageServiceSystemVmTemplates', { sharedfilesystemid: id }, { timeout: 15000, preserveOnFailure: true })
        ])
        if (token !== this.scope || id !== this.resource.id) return
        this.capability = this.unwrap(responses[0], 'getStorageServiceTemplateUpgradeCapabilities')
        this.templates = this.unwrap(responses[1], 'listStorageServiceSystemVmTemplates').templates || []
        this.readFailed = false
      } catch (error) { if (token === this.scope) this.readFailed = true } finally { if (token === this.scope) this.loading = false }
    },
    async preflightPlan () {
      if (!this.canSubmit || this.plan) return
      const token = this.scope; const id = this.resource.id; this.submitting = true; this.error = ''
      try {
        const result = this.unwrap(await postAPI('preflightStorageServiceSystemVmTemplateUpgrade', { sharedfilesystemid: id, templateid: this.targetTemplate }), 'preflightStorageServiceSystemVmTemplateUpgrade')
        if (token !== this.scope || id !== this.resource.id) return
        this.plan = { ...(result.upgrade || result), preflight: result.preflight || result.upgrade?.preflight || {} }
      } catch (error) { if (token === this.scope) this.error = error.message } finally { if (token === this.scope) this.submitting = false }
    },
    async upgrade () {
      if (!this.canSubmit || !this.plan) return
      const token = this.scope; const id = this.resource.id; const name = this.resource.name; this.submitting = true; this.error = ''
      try {
        const api = 'upgradeStorageServiceSystemVmTemplate'
        const result = await postAPI(api, { sharedfilesystemid: id, upgradeid: this.plan.id, templateid: this.targetTemplate, expectedrevision: this.plan.revision, maintenancewindow: true, confirmation: name })
        if (token !== this.scope || id !== this.resource.id) return
        const body = result[api.toLowerCase() + 'response'] || result
        this.$emit('accepted', { jobid: body.jobid, resourceId: id })
        this.$emit('close-action')
        if (body.jobid && this.pollJob) {
          this.$pollJob({ jobId: body.jobid, title: this.$t('label.storage.template.execute'), description: name, resourceId: id, showLoading: false,
            successMethod: () => { if (token === this.scope && id === this.resource.id) this.$emit('operation-updated', id) } })
        } else if (!body.jobid) this.$emit('operation-updated', id)
      } catch (error) { if (token === this.scope) this.error = error.message } finally { if (token === this.scope) this.submitting = false }
    }
  }
}
</script>
<style scoped>
.template-upgrade-layout { display: flex; flex-direction: column; width: min(780px, 82vw); max-height: calc(100vh - 180px); }
.template-upgrade-content { min-height: 0; overflow-y: auto; flex: 1 1 auto; padding-right: 8px; }
.template-upgrade-content .ant-alert, .template-upgrade-content .ant-descriptions { margin-bottom: 16px; }
.template-upgrade-footer { flex: 0 0 auto; display: flex; flex-wrap: wrap; gap: 8px; padding-top: 12px; border-top: 1px solid #d9d9d9; }
:global(body.dark-mode .template-upgrade-footer) { border-top-color: #46515c; }
</style>
