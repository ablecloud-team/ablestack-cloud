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
  <div class="runtime-upgrade-layout">
    <div class="runtime-upgrade-content">
      <a-alert
        class="runtime-upgrade-alert"
        type="warning"
        show-icon
        :message="$t('message.storage.service.runtime.upgrade.impact')" />

      <section class="runtime-upgrade-section">
        <div class="runtime-upgrade-section__title">{{ $t('label.storage.service.runtime.current') }}</div>
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item :label="$t('label.storage.service.runtime.available')">
            <a-tag :color="capability.available ? 'green' : 'red'">
              {{ capability.available ? $t('label.yes') : $t('label.no') }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.service.runtime.current.version')">
            <span class="runtime-upgrade-value">{{ capability.currentversion || '-' }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.service.runtime.previous.version')">
            <span class="runtime-upgrade-value">{{ capability.previousversion || '-' }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.service.runtime.abi')">
            <span class="runtime-upgrade-value">{{ capability.runtimeabiversion || '-' }}</span>
          </a-descriptions-item>
        </a-descriptions>
        <a-alert
          v-if="capability.details && !capability.available"
          class="runtime-upgrade-inline-alert"
          type="error"
          show-icon
          :message="capability.details" />
      </section>

      <section class="runtime-upgrade-section">
        <div class="runtime-upgrade-section__title">{{ $t('label.storage.service.runtime.target') }}</div>
        <a-form layout="vertical">
          <a-form-item required>
            <template #label>
              <tooltip-label
                :title="$t('label.storage.service.runtime.bundle')"
                :tooltip="$t('message.storage.service.runtime.bundle.help')" />
            </template>
            <a-select
              v-model:value="selectedBundleId"
              :disabled="submitting || bundles.length === 0"
              :placeholder="$t('message.storage.service.runtime.bundle.select')">
              <a-select-option v-for="bundle in bundles" :key="bundle.id" :value="bundle.id">
                {{ bundle.version }} · {{ bundle.serviceimpact }} · ABI {{ bundle.runtimeabiversion }}
              </a-select-option>
            </a-select>
          </a-form-item>
        </a-form>
        <storage-runtime-compatibility v-if="selectedBundleId" :manifest="selectedBundleVerification.manifest || {}" :verified="selectedBundleVerification.verified === true" :observation="consumerObservation" />
        <a-empty
          v-if="!loading && bundles.length === 0"
          :description="$t('message.storage.service.runtime.bundle.empty')" />
      </section>

      <section v-if="latestUpgrade" class="runtime-upgrade-section">
        <div class="runtime-upgrade-section__title">{{ $t('label.storage.service.runtime.operation') }}</div>
        <div class="runtime-upgrade-progress">
          <div>
            <a-tag :color="stateColor(latestUpgrade.state)">{{ latestUpgrade.state }}</a-tag>
            <span class="runtime-upgrade-phase">{{ latestUpgrade.phase }}</span>
          </div>
          <a-progress :percent="latestUpgrade.progress || 0" :status="progressStatus(latestUpgrade.state)" />
        </div>
        <a-alert
          v-if="latestUpgrade.errormessage"
          class="runtime-upgrade-inline-alert"
          type="error"
          show-icon
          :message="latestUpgrade.errorcode || $t('label.error')"
          :description="latestUpgrade.errormessage" />
      </section>

      <section v-if="upgrades.length > 0" class="runtime-upgrade-section">
        <div class="runtime-upgrade-section__title">{{ $t('label.storage.service.runtime.history') }}</div>
        <a-table
          size="small"
          :columns="historyColumns"
          :dataSource="upgrades"
          :pagination="false"
          :scroll="{ x: 620 }"
          rowKey="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'state'">
              <a-tag :color="stateColor(record.state)">{{ record.state }}</a-tag>
            </template>
            <template v-else-if="column.key === 'progress'">
              {{ record.progress || 0 }}%
            </template>
          </template>
        </a-table>
      </section>
    </div>

    <div class="runtime-upgrade-actions">
      <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
      <a-button :disabled="loading || submitting" @click="fetchData">{{ $t('label.refresh') }}</a-button>
      <a-button
        type="primary"
        :loading="submitting && activeAction === 'preflight'"
        :disabled="!canPreflight"
        @click="runPreflight">
        {{ $t('label.storage.service.runtime.preflight') }}
      </a-button>
      <a-button
        type="primary"
        :loading="submitting && activeAction === 'upgrade'"
        :disabled="!canUpgrade"
        @click="runUpgrade">
        {{ $t('label.storage.service.runtime.upgrade') }}
      </a-button>
      <a-button
        danger
        :loading="submitting && activeAction === 'rollback'"
        :disabled="!canRollback"
        @click="runRollback">
        {{ $t('label.storage.service.runtime.rollback') }}
      </a-button>
    </div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import StorageRuntimeCompatibility from '@/views/storage/StorageRuntimeCompatibility'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'StorageServiceRuntimeUpgrade',
  components: { TooltipLabel, StorageRuntimeCompatibility },
  props: {
    resource: { type: Object, required: true }
  },
  data () {
    return {
      generation: 0,
      loading: false,
      submitting: false,
      activeAction: '',
      capability: {},
      bundles: [],
      upgrades: [],
      selectedBundleId: undefined
    }
  },
  computed: {
    consumerObservation () {
      try {
        const value = this.capability.consumerobservation
        const parsed = typeof value === 'string' ? JSON.parse(value) : value
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
      } catch (error) { return {} }
    },
    selectedBundleVerification () {
      try { return JSON.parse(this.bundles.find(bundle => bundle.id === this.selectedBundleId)?.catalog || '{}').verification || {} } catch (error) { return {} }
    },
    latestUpgrade () {
      return this.upgrades.length > 0 ? this.upgrades[0] : null
    },
    canPreflight () {
      return !this.loading && !this.submitting && !!this.selectedBundleId &&
        (!this.latestUpgrade || !['RUNNING', 'PREFLIGHT_READY'].includes(this.latestUpgrade.state))
    },
    canUpgrade () {
      return !this.loading && !this.submitting && this.latestUpgrade?.state === 'PREFLIGHT_READY' && this.latestUpgrade.bundleid === this.selectedBundleId
    },
    canRollback () {
      return !this.loading && !this.submitting && this.latestUpgrade?.state === 'COMPLETE' &&
        !!this.capability.previousversion
    },
    historyColumns () {
      return [
        { title: this.$t('label.storage.service.runtime.bundle'), dataIndex: 'bundleversion', key: 'bundle', width: 150, ellipsis: true },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 130, fixed: 'left' },
        { title: this.$t('label.storage.service.runtime.phase'), dataIndex: 'phase', key: 'phase', width: 150, ellipsis: true },
        { title: this.$t('label.storage.service.runtime.progress'), dataIndex: 'progress', key: 'progress', width: 90 },
        { title: this.$t('label.created'), dataIndex: 'started', key: 'started', width: 180, ellipsis: true }
      ]
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    'resource.id' () {
      this.generation++; this.loading = false; this.submitting = false; this.activeAction = ''
      this.capability = {}; this.bundles = []; this.upgrades = []; this.selectedBundleId = undefined
      this.fetchData()
    }
  },
  beforeUnmount () { this.generation++ },
  methods: {
    async fetchData () {
      if (this.loading) return
      const token = ++this.generation; const resourceId = this.resource.id
      this.loading = true
      try {
        const responses = await Promise.all([
          getAPI('getStorageServiceRuntimeUpgradeCapabilities', { sharedfilesystemid: resourceId }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listStorageServiceRuntimeBundles', { listall: true }, { timeout: 15000, preserveOnFailure: true }),
          getAPI('listStorageServiceRuntimeUpgrades', { sharedfilesystemid: resourceId, listall: true }, { timeout: 15000, preserveOnFailure: true })
        ])
        if (token !== this.generation || resourceId !== this.resource.id) return
        this.capability = responses[0].getstorageserviceruntimeupgradecapabilitiesresponse?.storageserviceruntimecapability || {}
        this.bundles = responses[1].liststorageserviceruntimebundlesresponse?.storageserviceruntimebundle || []
        this.upgrades = (responses[2].liststorageserviceruntimeupgradesresponse?.storageserviceruntimeupgrade || [])
          .sort((left, right) => String(right.started || '').localeCompare(String(left.started || '')))
        if (!this.selectedBundleId && this.bundles.length > 0) {
          this.selectedBundleId = this.upgrades[0]?.state === 'PREFLIGHT_READY' && this.bundles.some(bundle => bundle.id === this.upgrades[0].bundleid)
            ? this.upgrades[0].bundleid : this.bundles.find(bundle => bundle.state === 'AVAILABLE')?.id
        }
      } catch (error) {
        if (token === this.generation && resourceId === this.resource.id) this.$notifyError(error)
      } finally {
        if (token === this.generation) this.loading = false
      }
    },
    runPreflight () {
      this.startAsync('preflight', 'preflightStorageServiceRuntimeUpgrade', {
        sharedfilesystemid: this.resource.id,
        bundleid: this.selectedBundleId
      })
    },
    runUpgrade () {
      if (!this.canUpgrade) return
      this.startAsync('upgrade', 'upgradeStorageServiceRuntime', { upgradeid: this.latestUpgrade.id })
    },
    runRollback () {
      this.startAsync('rollback', 'rollbackStorageServiceRuntimeUpgrade', { upgradeid: this.latestUpgrade.id })
    },
    startAsync (action, api, params) {
      if (this.submitting) return
      const token = this.generation; const resourceId = this.resource.id
      const current = () => token === this.generation && resourceId === this.resource.id
      this.submitting = true
      this.activeAction = action
      postAPI(api, params).then(response => {
        if (!current()) return
        const root = response[`${api.toLowerCase()}response`] || response[Object.keys(response)[0]] || {}
        this.$pollJob({
          jobId: root.jobid,
          title: this.$t(`label.storage.service.runtime.${action}`),
          description: this.resource.name,
          showLoading: false,
          successMessage: this.$t(`message.storage.service.runtime.${action}.success`),
          errorMessage: this.$t(`message.storage.service.runtime.${action}.failed`),
          successMethod: () => { if (!current()) return; this.submitting = false; this.activeAction = ''; this.fetchData() },
          errorMethod: () => { if (!current()) return; this.submitting = false; this.activeAction = ''; this.fetchData() },
          catchMethod: () => { if (!current()) return; this.submitting = false; this.activeAction = '' },
          resourceId
        })
      }).catch(error => {
        if (!current()) return
        this.$notifyError(error)
        this.submitting = false
        this.activeAction = ''
      })
    },
    progressStatus (state) {
      if (['FAILED', 'MANUAL_RECOVERY'].includes(state)) return 'exception'
      if (['COMPLETE', 'ROLLED_BACK'].includes(state)) return 'success'
      return 'active'
    },
    stateColor (state) {
      if (state === 'COMPLETE') return 'green'
      if (state === 'PREFLIGHT_READY') return 'blue'
      if (state === 'ROLLED_BACK') return 'orange'
      if (['FAILED', 'MANUAL_RECOVERY'].includes(state)) return 'red'
      return 'processing'
    },
    closeModal () {
      this.$emit('close-action')
    }
  }
}
</script>

<style lang="scss" scoped>
.runtime-upgrade-layout {
  width: min(760px, 82vw);
  height: min(700px, calc(100vh - 160px));
  max-height: calc(100vh - 160px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.runtime-upgrade-content {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 2px 6px 14px 2px;
  scrollbar-width: thin;
  scrollbar-color: #637b94 transparent;
}
.runtime-upgrade-content::-webkit-scrollbar,
.runtime-upgrade-layout :deep(.ant-table-body::-webkit-scrollbar) { width: 6px; height: 6px; }
.runtime-upgrade-content::-webkit-scrollbar-track,
.runtime-upgrade-layout :deep(.ant-table-body::-webkit-scrollbar-track) { background: transparent; }
.runtime-upgrade-content::-webkit-scrollbar-thumb,
.runtime-upgrade-layout :deep(.ant-table-body::-webkit-scrollbar-thumb) { background: #637b94; border-radius: 3px; }
.runtime-upgrade-layout :deep(.ant-table-body) { scrollbar-width: thin; scrollbar-color: #637b94 transparent; }
.runtime-upgrade-alert,
.runtime-upgrade-section { margin-bottom: 14px; }
.runtime-upgrade-section {
  border: 1px solid #d9d9d9;
  background: #fff;
  padding: 14px;
  border-radius: 4px;
}
.runtime-upgrade-section__title { margin-bottom: 12px; font-size: 14px; font-weight: 600; }
.runtime-upgrade-value { overflow-wrap: anywhere; }
.runtime-upgrade-inline-alert { margin-top: 12px; }
.runtime-upgrade-phase { margin-left: 8px; color: rgba(0, 0, 0, 0.65); }
.runtime-upgrade-progress :deep(.ant-progress) { margin-top: 10px; }
.runtime-upgrade-actions { flex: 0 0 auto; display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; padding: 12px 6px 0 2px; border-top: 1px solid #d9d9d9; background: #fff; }
:global(body.dark-mode .runtime-upgrade-section) { background: #242b33; border-color: #46515c; color: rgba(255, 255, 255, 0.88); }
:global(body.dark-mode .runtime-upgrade-section__title) { color: rgba(255, 255, 255, 0.92); }
:global(body.dark-mode .runtime-upgrade-phase) { color: rgba(255, 255, 255, 0.72); }
:global(body.dark-mode .runtime-upgrade-section .ant-descriptions-item-label) { background: #1f252c; color: rgba(255, 255, 255, 0.72); }
:global(body.dark-mode .runtime-upgrade-section .ant-descriptions-item-content) { background: #242b33; color: rgba(255, 255, 255, 0.88); }
:global(body.dark-mode .runtime-upgrade-section .ant-descriptions-bordered .ant-descriptions-view),
:global(body.dark-mode .runtime-upgrade-section .ant-descriptions-bordered .ant-descriptions-row > th),
:global(body.dark-mode .runtime-upgrade-section .ant-descriptions-bordered .ant-descriptions-row > td) { border-color: #3b4650 !important; }
:global(body.dark-mode .runtime-upgrade-section .ant-descriptions-bordered .ant-descriptions-row) { border-bottom-color: #3b4650 !important; }
:global(body.dark-mode .runtime-upgrade-section .ant-table),
:global(body.dark-mode .runtime-upgrade-section .ant-table-thead > tr > th),
:global(body.dark-mode .runtime-upgrade-section .ant-table-tbody > tr > td) { background: #242b33; color: rgba(255, 255, 255, 0.86); border-color: #3b4650; }
:global(body.dark-mode .runtime-upgrade-section .ant-table-cell-scrollbar) { box-shadow: 0 1px 0 1px #242b33; }
:global(body.dark-mode .runtime-upgrade-actions) { background: #20262d; border-top-color: #3b4650; }
@media (max-width: 768px) { .runtime-upgrade-layout { width: 84vw; height: calc(100vh - 140px); max-height: calc(100vh - 140px); } }
</style>
