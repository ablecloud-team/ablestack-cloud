<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <div>
    <a-space class="mold-dialog-toolbar" wrap>
      <a-button type="primary" @click="openRegistration"><template #icon><PlusOutlined /></template>{{ $t('label.storage.runtime.register') }}</a-button>
      <a-button :loading="loading" @click="fetchBundles"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-table :columns="columns" :data-source="bundles" row-key="id" :loading="loading" :scroll="{ x: 1300 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'version'"><a @click="showDetails(record)">{{ record.version }}</a></template>
        <template v-else-if="column.key === 'state'"><a-tag :color="stateColor(record.state)">{{ $t('label.storage.runtime.state.' + record.state.toLowerCase()) }}</a-tag></template>
        <template v-else-if="column.key === 'channel'">{{ metadata(record).channel || 'stable' }}</template>
        <template v-else-if="column.key === 'consumers'">{{ (record.instances || []).length }}</template>
        <template v-else-if="column.key === 'actions'">
          <a-space wrap>
            <a-button v-for="state in actions(record)" :key="state" size="small" :disabled="busy" @click="openTransition(record, state)">
              {{ $t('label.storage.runtime.action.' + state.toLowerCase()) }}
            </a-button>
            <a-button v-if="record.state === 'REGISTERED' && !(record.instances || []).length" size="small" danger :disabled="busy" @click="openTransition(record, 'REMOVED')">{{ $t('label.delete') }}</a-button>
          </a-space>
        </template>
      </template>
    </a-table>
    <MoldDialog v-if="registration.visible" :visible="registration.visible" :title="$t('label.storage.runtime.register')" @cancel="registration.visible = false">
      <a-form layout="vertical">
        <a-form-item v-for="field in registrationFields" :key="field" :label="$t('label.storage.runtime.' + field)" :required="field !== 'artifactsize'">
          <a-input v-model:value="registration.form[field]" :disabled="busy" />
        </a-form-item>
        <a-form-item :label="$t('label.storage.runtime.releasechannel')"><a-input v-model:value="registration.form.releasechannel" :maxlength="64" /></a-form-item>
        <a-form-item :label="$t('label.storage.runtime.releasenotes')"><a-textarea v-model:value="registration.form.releasenotes" :maxlength="4096" :rows="4" /></a-form-item>
        <a-form-item :label="$t('label.storage.runtime.serviceimpact')" required>
          <a-select v-model:value="registration.form.serviceimpact"><a-select-option v-for="impact in ['NONE', 'PROTOCOL_RESTART', 'VM_REBOOT']" :key="impact" :value="impact">{{ impact }}</a-select-option></a-select>
        </a-form-item>
      </a-form>
      <template #footer>
        <a-button :disabled="busy" @click="registration.visible = false">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="busy" :disabled="!registrationValid" @click="registerBundle">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
    <MoldDialog v-if="transition.visible" :title="$t('label.storage.runtime.action.' + transition.state.toLowerCase())" @cancel="transition.visible = false">
      <a-descriptions :column="1">
        <a-descriptions-item :label="$t('label.version')">{{ transition.bundle.version }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">{{ transition.bundle.state }} → {{ transition.state }}</a-descriptions-item>
      </a-descriptions>
      <a-form layout="vertical"><a-form-item :label="$t('label.storage.runtime.reason')" required><a-textarea v-model:value="transition.reason" :maxlength="1024" :rows="4" /></a-form-item></a-form>
      <a-alert v-if="jobId" type="info" show-icon :message="$t('label.storage.runtime.job') + ': ' + jobId" />
      <template #footer>
        <a-button :disabled="busy" @click="transition.visible = false">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="busy" :disabled="!transition.reason.trim()" @click="applyTransition">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
    <MoldDialog v-if="details" :title="details.version" @cancel="details = null">
      <a-tabs tab-position="left" :animated="false">
        <a-tab-pane key="metadata" :tab="$t('label.details')">
          <a-descriptions :column="1" bordered>
            <a-descriptions-item v-for="key in detailKeys" :key="key" :label="$t('label.storage.runtime.' + key)"><span class="runtime-value">{{ details[key] || '-' }}</span></a-descriptions-item>
          </a-descriptions>
        </a-tab-pane>
        <a-tab-pane key="compatibility" :tab="$t('label.storage.runtime.compatibility')">
          <storage-runtime-compatibility :manifest="metadata(details).verification?.manifest || {}" :verified="metadata(details).verification?.verified === true" />
        </a-tab-pane>
        <a-tab-pane key="integrity" :tab="$t('label.storage.runtime.verification')">
          <pre class="runtime-value">{{ JSON.stringify(metadata(details).verification || {}, null, 2) }}</pre>
        </a-tab-pane>
        <a-tab-pane key="instances" :tab="$t('label.storage.runtime.consumers')">
          <a-list :data-source="details.instances || []"><template #renderItem="{ item }"><a-list-item>{{ item }}</a-list-item></template></a-list>
        </a-tab-pane>
        <a-tab-pane key="audit" :tab="$t('label.storage.runtime.audit')">
          <a-table :data-source="metadata(details).audit || []" :columns="auditColumns" :pagination="false" :row-key="row => row.at + ':' + row.action" :scroll="{ x: 550 }" />
        </a-tab-pane>
      </a-tabs>
    </MoldDialog>
  </div>
</template>

<script>
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import StorageRuntimeCompatibility from '@/views/storage/StorageRuntimeCompatibility'
import MoldDialog from '@/components/view/MoldDialog'
import { getAPI, postAPI } from '@/api'
import { createJobTracker } from '@/utils/jobTracker'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

export default {
  name: 'StorageServiceRuntimeBundles',
  components: { MoldDialog, PlusOutlined, ReloadOutlined, StorageRuntimeCompatibility },
  mixins: [listRefreshMixin(['fetchBundles'])],
  data () {
    return {
      bundles: [],
      loading: false,
      readFailed: false,
      busy: false,
      jobId: null,
      details: null,
      registration: { visible: false, form: {} },
      transition: { visible: false, bundle: null, state: '', reason: '' },
      registrationFields: ['version', 'runtimeabiversion', 'desiredstateschemaversion', 'artifacturl', 'manifesturl', 'signatureurl', 'artifactsize', 'sha256', 'manifestsha256', 'signingkeyid'],
      detailKeys: ['state', 'runtimeabiversion', 'desiredstateschemaversion', 'serviceimpact', 'artifacturl', 'sha256', 'manifestsha256', 'signingkeyid', 'created']
    }
  },
  computed: {
    columns () {
      return [
        { key: 'version', dataIndex: 'version', title: this.$t('label.version'), fixed: 'left', width: 180 },
        { key: 'channel', title: this.$t('label.storage.runtime.releasechannel'), width: 110 },
        { key: 'state', dataIndex: 'state', title: this.$t('label.state'), width: 150 },
        { dataIndex: 'runtimeabiversion', title: this.$t('label.storage.runtime.runtimeabiversion'), width: 100 },
        { dataIndex: 'desiredstateschemaversion', title: this.$t('label.storage.runtime.desiredstateschemaversion'), width: 100 },
        { dataIndex: 'serviceimpact', title: this.$t('label.storage.runtime.serviceimpact'), width: 160 },
        { key: 'consumers', title: this.$t('label.storage.runtime.consumers'), width: 100 },
        { dataIndex: 'created', title: this.$t('label.created'), width: 170 },
        { key: 'actions', title: this.$t('label.actions'), fixed: 'right', width: 380 }
      ]
    },
    auditColumns () {
      return ['action', 'reason', 'userId', 'at'].map(key => ({ key, dataIndex: key, title: this.$t('label.storage.runtime.' + key.toLowerCase()) }))
    },
    registrationValid () {
      return this.registrationFields.filter(key => key !== 'artifactsize').every(key => String(this.registration.form[key] || '').trim())
    }
  },
  mounted () { this.fetchBundles() },
  methods: {
    metadata (bundle) {
      try { return JSON.parse(bundle.catalog || '{}') } catch (_) { return {} }
    },
    stateColor (state) {
      return { VERIFIED: 'cyan', AVAILABLE: 'green', DISABLED: 'orange', DEPRECATED: 'orange', REVOKED: 'red' }[state]
    },
    actions (bundle) {
      return {
        REGISTERED: ['VERIFIED', 'REVOKED'],
        VERIFIED: ['VERIFIED', 'AVAILABLE', 'REVOKED'],
        AVAILABLE: ['AVAILABLE', 'DISABLED', 'DEPRECATED', 'REVOKED'],
        DISABLED: ['AVAILABLE', 'DEPRECATED', 'REVOKED'],
        DEPRECATED: ['REVOKED'],
        REVOKED: []
      }[bundle.state] || []
    },
    async fetchBundles () {
      const request = this.listRequestToken('fetchBundles')
      this.loading = true
      try {
        const result = await getAPI('listStorageServiceRuntimeBundles', { catalog: true }, { preserveOnFailure: true, timeout: 15000 })
        if (!this.isListRequestCurrent('fetchBundles', request)) return
        this.bundles = result.liststorageserviceruntimebundlesresponse.storageserviceruntimebundle || []
        this.readFailed = false
      } catch (error) {
        if (!this.isListRequestCurrent('fetchBundles', request)) return
        this.readFailed = true
        request.failed = true
      } finally {
        if (!this.listRefreshDisposed) this.loading = false
      }
    },
    openRegistration () {
      this.registration = {
        visible: true,
        form: { version: '', runtimeabiversion: '1', desiredstateschemaversion: '1', serviceimpact: 'NONE', releasechannel: 'stable', releasenotes: '' }
      }
    },
    async registerBundle () {
      this.busy = true
      try {
        const params = { ...this.registration.form }
        if (!params.artifactsize) delete params.artifactsize
        await postAPI('registerStorageServiceRuntimeBundle', params)
        this.registration.visible = false
        await this.fetchBundles()
      } catch (error) { this.$notifyError(error) } finally { this.busy = false }
    },
    showDetails (bundle) { this.details = bundle },
    openTransition (bundle, state) {
      this.jobId = null
      this.transition = { visible: true, bundle, state, reason: '' }
    },
    async applyTransition () {
      this.busy = true
      try {
        if (this.transition.state === 'REMOVED') {
          await postAPI('deleteStorageServiceRuntimeBundle', { id: this.transition.bundle.id })
        } else {
          const result = await postAPI('updateStorageServiceRuntimeBundle', {
            id: this.transition.bundle.id,
            state: this.transition.state,
            expectedstate: this.transition.bundle.state,
            reason: this.transition.reason
          })
          this.jobId = result.updatestorageserviceruntimebundleresponse.jobid
          const tracker = createJobTracker({
            query: async id => {
              const result = await getAPI('queryAsyncJobResult', { jobid: id }, { backgroundJob: true, timeout: 15000 })
              return result.queryasyncjobresultresponse
            },
            onState: () => {}
          })
          const completed = await tracker.track(this.jobId)
          if (completed.jobstatus !== 1) throw new Error(completed.jobresult?.errortext || this.$t('message.storage.runtime.tracking.unknown'))
        }
        this.transition.visible = false
        await this.fetchBundles()
      } catch (error) { this.$notifyError(error) } finally { this.busy = false }
    }
  }
}
</script>

<style scoped>
.runtime-value { white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-all; }
</style>
