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
  <section class="template-history">
    <h4>{{ $t('label.storage.template.section') }}</h4>
    <a-space wrap>
      <a-button v-if="can('preflightStorageServiceSystemVmTemplateUpgrade')" type="primary" @click="dialog=true"><template #icon><CloudUploadOutlined /></template>{{ $t('label.storage.template.execute') }}</a-button>
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="busy" type="info" show-icon :message="$t('message.storage.template.running')" />
    <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.storage.template.read.failed')" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :pagination="{ pageSize: 5 }" :scroll="{ x: 1100 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key==='state'"><a-tag :color="record.state==='COMPLETE' ? 'green' : record.state==='RECOVERY_REQUIRED' ? 'red' : 'blue'">{{ stateLabel(record.state) }}</a-tag></template>
        <template v-else-if="column.key==='progress'"><a-progress :percent="record.progress || 0" size="small" /></template>
        <template v-else-if="column.key==='actions'">
          <a-space wrap>
            <a-button v-if="record.state==='RECOVERY_REQUIRED' && can('upgradeStorageServiceSystemVmTemplate')" size="small" :disabled="busy" @click="openAction(record,'resume')">{{ $t('label.storage.template.resume') }}</a-button>
            <a-button v-if="record.rollbackAllowed && can('rollbackStorageServiceSystemVmTemplateUpgrade')" size="small" :disabled="busy" @click="openAction(record,'rollback')">{{ $t('label.storage.template.rollback') }}</a-button>
            <a-button v-if="record.finalizeAllowed && can('finalizeStorageServiceSystemVmTemplateUpgrade')" size="small" danger :disabled="busy" @click="openAction(record,'finalize')">{{ $t('label.storage.template.finalize') }}</a-button>
          </a-space>
        </template>
        <template v-else>{{ record[column.dataIndex] || '—' }}</template>
      </template>
      <template #expandedRowRender="{ record }">
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item :label="$t('label.storage.template.previous.root')">{{ record.previousRootVolumeUuid || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.template.target.root')">{{ record.targetRootVolumeUuid || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.template.retain.until')">{{ record.rollbackRetainUntil ? new Date(record.rollbackRetainUntil).toLocaleString() : '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.config.verification')">{{ record.verification?.status || '—' }}</a-descriptions-item>
          <a-descriptions-item v-if="record.errorMessage" :label="$t('label.error')">{{ record.errorMessage }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-table>
    <a-modal :visible="dialog" :title="$t('label.storage.template.execute')" :footer="null" :width="860" @cancel="dialog=false">
      <storage-service-system-vm-template-upgrade v-if="dialog" :resource="resource" :poll-job="false" @close-action="dialog=false" @accepted="accepted" @operation-updated="refresh" />
    </a-modal>
    <a-modal :visible="!!actionTarget" :title="$t('label.storage.template.' + action)" :confirm-loading="busy" :body-style="{ maxHeight: '65vh', overflowY: 'auto' }" @cancel="actionTarget=null" @ok="performAction">
      <a-alert type="warning" show-icon :message="$t(action==='finalize' ? 'message.storage.template.finalize.confirm' : 'message.storage.template.impact')" />
      <a-checkbox v-if="action!=='finalize'" v-model:checked="maintenanceWindow">{{ $t('message.storage.template.maintenance.confirm') }}</a-checkbox>
      <a-form layout="vertical"><a-form-item :label="$t('label.storage.config.confirmation')"><a-input v-model:value="confirmation" :placeholder="resource.name" autocomplete="off" /></a-form-item></a-form>
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { CloudUploadOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import StorageServiceSystemVmTemplateUpgrade from '@/views/storage/StorageServiceSystemVmTemplateUpgrade'

export default {
  name: 'StorageServiceTemplateUpgradeHistory',
  components: { CloudUploadOutlined, ReloadOutlined, StorageServiceSystemVmTemplateUpgrade },
  props: { resource: { type: Object, required: true } },
  emits: ['operation-updated'],
  data: () => ({ rows: [], loading: false, readFailed: false, error: '', busy: false, scope: 0, dialog: false, actionTarget: null, action: '', confirmation: '', maintenanceWindow: false, timer: null, alive: true }),
  computed: {
    columns () {
      return [
        { title: this.$t('label.storage.template.target'), dataIndex: 'targetTemplateUuid', width: 220 },
        { title: this.$t('label.state'), key: 'state', width: 170 },
        { title: this.$t('label.storage.operation.phase'), dataIndex: 'phase', width: 180 },
        { title: this.$t('label.storage.operation.progress'), key: 'progress', width: 110 },
        { title: this.$t('label.actions'), key: 'actions', width: 280, fixed: 'right' }]
    }
  },
  watch: { 'resource.id' () { this.scope++; this.rows = []; this.dialog = false; this.actionTarget = null; this.busy = false; this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.alive = false; this.scope++; clearTimeout(this.timer) },
  methods: {
    can (api) { return api in this.$store.getters.apis },
    unwrap (value, api) {
      const body = value[api.toLowerCase() + 'response'] || value
      const entity = body.storageservicetemplateupgrade || body
      return typeof entity.result === 'string' ? JSON.parse(entity.result) : entity.result || entity
    },
    stateLabel (state) { return this.$t('label.storage.template.state.' + state) },
    async refresh () {
      const token = ++this.scope; const id = this.resource.id; this.loading = true; clearTimeout(this.timer)
      try {
        const value = this.unwrap(await getAPI('listStorageServiceTemplateUpgrades', { sharedfilesystemid: id }, { timeout: 15000, preserveOnFailure: true }), 'listStorageServiceTemplateUpgrades')
        if (token !== this.scope || id !== this.resource.id) return
        this.rows = value.upgrades || []; this.readFailed = false
        if (this.rows.some(row => row.state === 'RUNNING') || this.busy) this.timer = setTimeout(() => this.refresh(), 10000)
      } catch (error) { if (token === this.scope) { this.readFailed = true; if (this.busy) this.timer = setTimeout(() => this.refresh(), 10000) } } finally { if (token === this.scope) this.loading = false }
    },
    accepted (event) {
      const id = this.resource.id
      this.dialog = false; this.busy = true; this.refresh()
      if (event.jobid) {
        this.$pollJob({
          jobId: event.jobid,
          title: this.$t('label.storage.template.execute'),
          resourceId: id,
          showLoading: false,
          successMethod: () => { if (this.alive && id === this.resource.id) { this.busy = false; this.refresh(); this.$emit('operation-updated', id) } },
          errorMethod: () => { if (this.alive && id === this.resource.id) { this.busy = false; this.refresh() } }
        })
      } else { this.busy = false; this.refresh() }
    },
    openAction (record, action) { this.actionTarget = record; this.action = action; this.confirmation = ''; this.maintenanceWindow = false; this.error = '' },
    async performAction () {
      if (!this.actionTarget || this.busy || this.confirmation !== this.resource.name || this.action !== 'finalize' && !this.maintenanceWindow) {
        this.error = this.$t('message.storage.config.confirmation.required'); return
      }
      const id = this.resource.id; const action = this.action; const target = this.actionTarget; this.busy = true; this.error = ''
      const api = action === 'resume' ? 'upgradeStorageServiceSystemVmTemplate' : action === 'rollback' ? 'rollbackStorageServiceSystemVmTemplateUpgrade' : 'finalizeStorageServiceSystemVmTemplateUpgrade'
      try {
        const value = await postAPI(api, { sharedfilesystemid: id, upgradeid: target.id, confirmation: this.confirmation, maintenancewindow: this.maintenanceWindow })
        if (id !== this.resource.id) return
        this.actionTarget = null
        const job = (value[api.toLowerCase() + 'response'] || value).jobid
        if (job) {
          this.$pollJob({
            jobId: job,
            title: this.$t('label.storage.template.' + action),
            resourceId: id,
            showLoading: false,
            successMethod: () => { if (this.alive && id === this.resource.id) { this.busy = false; this.refresh(); this.$emit('operation-updated', id) } },
            errorMethod: () => { if (this.alive && id === this.resource.id) { this.busy = false; this.refresh() } }
          })
        } else { this.busy = false; await this.refresh() }
        this.refresh()
      } catch (error) { if (this.alive && id === this.resource.id) { this.error = error.message; this.busy = false } }
    }
  }
}
</script>
<style scoped>
.template-history { margin-top: 20px; }
.template-history .ant-space, .template-history .ant-alert { margin-bottom: 16px; }
</style>
