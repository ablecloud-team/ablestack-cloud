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
  <section class="storage-table-section storage-operation-history">
    <h4>{{ $t('label.storage.operation.history') }}</h4>
    <a-space wrap><a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button></a-space>
    <a-alert v-if="saving" type="info" show-icon :message="$t('message.storage.operation.reconcile.running')" />
    <a-alert v-if="error && !reconcileTarget" type="error" show-icon :message="error" />
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.operation.read.failed')" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :pagination="{ pageSize: 10 }" :scroll="{ x: 1500 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'"><a-tag :color="color(record.state)">{{ $t('label.storage.operation.state.' + record.state) }}</a-tag></template>
        <template v-else-if="column.key === 'actions'"><a-button v-if="canReconcile && ['RECOVERY_REQUIRED','BLOCKED','ROLLED_BACK'].includes(record.state)" size="small" :loading="saving === record.id" @click="openRecovery(record)">{{ $t('label.storage.operation.reconcile') }}</a-button></template>
        <template v-else>{{ record[column.dataIndex] }}</template>
      </template>
      <template v-if="canControl" #expandedRowRender="{ record }">
        <storage-operation-control :instance-id="instanceId" :operation-id="record.id" :instance-name="instanceName" @operation-updated="refresh" />
      </template>
    </a-table>
    <a-modal :visible="!!reconcileTarget" :title="$t('label.storage.operation.reconcile')" :confirm-loading="!!saving" :body-style="{ maxHeight: '65vh', overflowY: 'auto' }" @cancel="cancelRecovery" :ok-button-props="{ disabled: recoveryMode === currentRecoveryMode && !currentRecoveryCanSubmit }" @ok="reconcile">
      <a-alert type="info" show-icon :message="$t('message.storage.operation.reconcile.help')" />
      <p>{{ reconcileTarget?.id }}</p><p>{{ reconcileTarget?.diagnostic }}</p>
      <a-form v-if="canCurrentRecovery" layout="vertical">
        <a-form-item :label="$t('label.storage.operation.recovery.mode')">
          <a-radio-group v-model:value="recoveryMode">
            <a-radio value="ORIGINAL_OPERATION">{{ $t('label.storage.operation.recovery.original') }}</a-radio>
            <a-radio :value="currentRecoveryMode">{{ $t('label.storage.operation.recovery.current') }}</a-radio>
          </a-radio-group>
        </a-form-item>
        <template v-if="recoveryMode === currentRecoveryMode">
          <a-alert type="warning" show-icon :message="$t('message.storage.operation.recovery.current.help')" />
          <a-form-item>
            <a-button :loading="currentReviewLoading" :disabled="!!saving" @click="readCurrentRecoveryReview">{{ $t('label.storage.operation.recovery.review') }}</a-button>
          </a-form-item>
          <a-descriptions v-if="currentReview" size="small" :column="1">
            <a-descriptions-item :label="$t('label.posix.directory.revision')">{{ currentReview.committedRevision }} → {{ currentReview.scope.revision }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.source.sha')"><code>{{ currentReview.sourceConfigurationSha256 }}</code></a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.review.hash')"><code>{{ currentReview.currentReviewHash }}</code></a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.boot')"><code>{{ currentReview.publicFacts.bootId }}</code></a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.database.count')">{{ currentReview.publicFacts.databaseCount }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.master.count')">{{ currentReview.publicFacts.ownedMasterCount }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.sessions')">{{ $t('message.storage.operation.recovery.sessions.empty') }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.recovery.namespace')">{{ $t('message.storage.operation.recovery.namespace.only') }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.storage.operation.phase')">{{ currentReview.recoveryPhase }}</a-descriptions-item>
          </a-descriptions>
          <a-form-item>
            <a-checkbox v-model:checked="currentMaintenanceApproved">{{ $t('message.storage.operation.recovery.current.maintenance') }}</a-checkbox>
          </a-form-item>
          <a-form-item :label="$t('label.storage.operation.recovery.confirm.name')">
            <a-input v-model:value="currentConfirmation" :placeholder="instanceName" autocomplete="off" />
          </a-form-item>
        </template>
      </a-form>
      <a-alert v-if="error" type="error" show-icon :message="error" />
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { SMB_CURRENT_RETAIN_MODE, supportsSmbCurrentRecovery, readSmbCurrentRecoveryReview, smbCurrentRecoveryParameters, requireSmbCurrentRecoveryResult } from '@/utils/storageSmbCurrentIdentityRecovery'
import StorageOperationControl from '@/views/storage/StorageOperationControl'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'StorageOperationHistory',
  components: { ReloadOutlined, StorageOperationControl },
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, default: '' } },
  data: () => ({ rows: [], loading: false, readError: false, generation: 0, reconcileTarget: null, saving: '', error: '', recoveryMode: 'ORIGINAL_OPERATION', currentRecoveryMode: SMB_CURRENT_RETAIN_MODE, currentReview: null, currentReviewLoading: false, currentMaintenanceApproved: false, currentConfirmation: '', currentApprovedParameters: null, recoveryToken: 0, recoveryDisposed: false }),
  computed: {
    canControl () { return 'getStorageServiceOperationControl' in this.$store.getters.apis },
    canReconcile () { return 'reconcileStorageServiceOperation' in this.$store.getters.apis },
    canCurrentRecovery () {
      return this.reconcileTarget?.state === 'RECOVERY_REQUIRED' && supportsSmbCurrentRecovery(this.$store.getters.apis, command => this.$getApiParams?.(command))
    },
    currentRecoveryCanSubmit () {
      return this.canCurrentRecovery && !!this.currentReview && !this.currentReviewLoading && !this.saving && this.currentMaintenanceApproved === true && this.currentConfirmation === this.instanceName && !!this.instanceName
    },
    columns () {
      return [
        { title: this.$t('label.storage.operation.action'), dataIndex: 'action', key: 'action', width: 270 },
        { title: this.$t('label.posix.directory.revision'), dataIndex: 'revision', key: 'revision', width: 100 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 180 },
        { title: this.$t('label.storage.operation.phase'), dataIndex: 'phase', key: 'phase', width: 180 },
        { title: this.$t('label.storage.operation.progress'), dataIndex: 'progress', key: 'progress', width: 100 },
        { title: this.$t('label.storage.operation.diagnostic'), dataIndex: 'diagnostic', key: 'diagnostic', width: 420 },
        { title: this.$t('label.actions'), key: 'actions', width: 180, fixed: 'right', align: 'right' }]
    }
  },
  watch: {
    instanceId () { this.generation++; this.rows = []; this.cancelRecovery(); this.saving = ''; this.error = ''; this.refresh() },
    instanceName () { this.resetCurrentRecovery() },
    recoveryMode () { this.resetCurrentRecovery() }
  },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++; this.recoveryDisposed = true; this.cancelRecovery() },
  methods: {
    resetCurrentRecovery () {
      this.recoveryToken++; this.currentReview = null; this.currentReviewLoading = false; this.currentMaintenanceApproved = false; this.currentConfirmation = ''; this.currentApprovedParameters = null
    },
    openRecovery (record) {
      this.resetCurrentRecovery(); this.recoveryMode = 'ORIGINAL_OPERATION'; this.error = ''; this.reconcileTarget = record
    },
    cancelRecovery () {
      this.resetCurrentRecovery(); this.reconcileTarget = null; this.recoveryMode = 'ORIGINAL_OPERATION'
    },
    currentRecoveryScope () {
      return { instanceId: this.instanceId, instanceName: this.instanceName, operationId: this.reconcileTarget?.id, revision: this.reconcileTarget?.revision }
    },
    currentRecoveryScopeMatches (expected, token) {
      const current = this.currentRecoveryScope()
      return !this.recoveryDisposed && token === this.recoveryToken && this.recoveryMode === SMB_CURRENT_RETAIN_MODE && this.reconcileTarget?.state === 'RECOVERY_REQUIRED' && Object.keys(expected).every(key => current[key] === expected[key])
    },
    async readCurrentRecoveryReview () {
      if (!this.canCurrentRecovery || this.currentReviewLoading || this.saving) return
      const expected = this.currentRecoveryScope(); const token = ++this.recoveryToken
      this.currentReviewLoading = true; this.error = ''
      try {
        const review = await readSmbCurrentRecoveryReview(expected)
        if (!this.currentRecoveryScopeMatches(expected, token)) return
        if (this.currentReview?.currentReviewHash !== review.currentReviewHash) { this.currentMaintenanceApproved = false; this.currentConfirmation = '' }
        this.currentReview = review
      } catch (error) {
        if (this.currentRecoveryScopeMatches(expected, token)) { this.currentReview = null; this.currentMaintenanceApproved = false; this.currentConfirmation = ''; this.error = this.$t('message.storage.operation.recovery.review.failed') }
      } finally { if (token === this.recoveryToken) this.currentReviewLoading = false }
    },
    async retainCurrentIdentity () {
      if (!this.currentRecoveryCanSubmit || this.recoveryDisposed) return
      const expected = this.currentRecoveryScope(); const token = this.recoveryToken; const review = this.currentReview
      const approval = { maintenancewindow: this.currentMaintenanceApproved, confirmation: this.currentConfirmation }
      this.error = ''; this.saving = expected.operationId
      try {
        const fresh = await readSmbCurrentRecoveryReview(expected)
        if (!this.currentRecoveryScopeMatches(expected, token)) return
        if (fresh.currentReviewHash !== review.currentReviewHash) {
          this.currentReview = fresh; this.currentMaintenanceApproved = false; this.currentConfirmation = ''
          throw new Error(this.$t('message.storage.operation.recovery.review.changed'))
        }
        if (this.currentApprovedParameters && fresh.approvalStored === true && fresh.resumeAllowed !== true) throw new Error(this.$t('message.storage.operation.recovery.review.changed'))
        const parameters = smbCurrentRecoveryParameters(fresh, expected, approval)
        if (this.currentApprovedParameters && JSON.stringify(parameters) !== JSON.stringify(this.currentApprovedParameters)) throw new Error(this.$t('message.storage.operation.recovery.review.changed'))
        this.currentApprovedParameters = parameters
        let response = await postAPI('repairStorageServiceSmbIdentity', parameters)
        const job = response.repairstorageservicesmbidentityresponse?.jobid
        let value = response.repairstorageservicesmbidentityresponse
        if (job) {
          let done = false
          for (let i = 0; i < 120; i++) {
            if (!this.currentRecoveryScopeMatches(expected, token)) return
            response = await getAPI('queryAsyncJobResult', { jobid: job }, { preserveOnFailure: true, timeout: 15000 })
            const result = response.queryasyncjobresultresponse
            if (result?.jobstatus === 1) { done = true; value = result.jobresult?.storageserviceruntime || result.jobresult; break }
            if (result?.jobstatus === 2) throw new Error(this.$t('message.storage.operation.recovery.current.failed'))
            await new Promise(resolve => setTimeout(resolve, 1000))
          }
          if (!done) throw new Error(this.$t('message.posix.directory.timeout'))
        }
        if (!this.currentRecoveryScopeMatches(expected, token)) return
        let payload
        try { payload = typeof value?.resultjson === 'string' ? JSON.parse(value.resultjson) : value?.resultjson } catch (error) { payload = null }
        if (value?.success !== true) throw new Error('SMB_CURRENT_RECOVERY_RESULT_UNVERIFIED')
        requireSmbCurrentRecoveryResult(payload, expected, review.currentReviewHash, value.status)
        this.cancelRecovery(); await this.refresh()
      } catch (error) {
        if (this.currentRecoveryScopeMatches(expected, token)) {
          const known = [this.$t('message.storage.operation.recovery.review.changed'), this.$t('message.posix.directory.timeout')]
          this.error = known.includes(error.message) ? error.message : this.$t('message.storage.operation.recovery.current.failed')
        }
      } finally { if (this.saving === expected.operationId) this.saving = '' }
    },
    color (state) { return state === 'COMPLETE' || state === 'COMPLETE_NO_CONFIG_CHANGE' || state === 'RECONCILED_SUPERSEDED' ? 'green' : (state === 'RECOVERY_REQUIRED' ? 'red' : (state === 'RUNNING' ? 'blue' : 'orange')) },
    async refresh () {
      const token = ++this.generation; const instance = this.instanceId; this.loading = true
      try {
        const result = await getAPI('listStorageServiceOperations', { instanceid: instance }, { preserveOnFailure: true, timeout: 15000 })
        if (token !== this.generation || instance !== this.instanceId) return
        this.rows = result.liststorageserviceoperationsresponse?.storageserviceoperation || []; this.readError = false
      } catch (error) { if (token === this.generation) this.readError = true } finally { if (token === this.generation) this.loading = false }
    },
    async reconcile () {
      if (this.recoveryMode === SMB_CURRENT_RETAIN_MODE) return this.retainCurrentIdentity()
      if (!this.canReconcile || !this.reconcileTarget || this.saving) return
      const instance = this.instanceId; const operation = this.reconcileTarget.id
      this.error = ''; this.saving = operation
      try {
        let result = await postAPI('reconcileStorageServiceOperation', { operationid: operation }); const job = result.reconcilestorageserviceoperationresponse?.jobid
        if (instance !== this.instanceId) return
        this.reconcileTarget = null
        if (job) {
          let done = false
          for (let i = 0; i < 120; i++) {
            if (instance !== this.instanceId) return
            result = await getAPI('queryAsyncJobResult', { jobid: job }, { preserveOnFailure: true, timeout: 15000 }); const value = result.queryasyncjobresultresponse
            if (value.jobstatus === 1) { done = true; break }
            if (value.jobstatus === 2) throw new Error(value.jobresult?.errortext || 'Operation failed')
            await new Promise(resolve => setTimeout(resolve, 1000))
          }
          if (!done) throw new Error(this.$t('message.posix.directory.timeout'))
        }
        if (instance === this.instanceId) await this.refresh()
      } catch (error) { if (instance === this.instanceId) this.error = error.message } finally { if (this.saving === operation) this.saving = '' }
    }
  }
}
</script>
<style scoped>
.storage-operation-history { margin-top: 20px; }
.storage-operation-history .ant-space { margin-bottom: 16px; }
</style>
