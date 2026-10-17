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
  <a-modal
    class="mold-dialog sharedfs-removal-dialog"
    centered
    :visible="true"
    :maskClosable="false"
    :width="900"
    :title="$t(currentAction.label || 'label.destroy.sharedfs')"
    :confirmLoading="submitting"
    :okButtonProps="{ disabled: !canSubmit }"
    :okText="$t(knownJob ? 'label.storage.service.removal.check.job' : policy === 'DELETE_VOLUMES' ? 'label.storage.service.removal.review.delete' : 'label.ok')"
    :cancelText="$t('label.cancel')"
    @cancel="close"
    @ok="submit">
    <a-spin :spinning="loading">
      <a-alert show-icon type="warning" :message="$t('message.storage.service.removal.downtime')" />
      <a-alert v-if="!authorized" type="error" show-icon :message="$t('message.storage.service.removal.permission')" />
      <a-descriptions :column="1" size="small">
        <a-descriptions-item :label="$t('label.name')">{{ resource.name }} ({{ resource.id }})</a-descriptions-item>
        <a-descriptions-item :label="$t('label.account')">{{ resource.account || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.domain')">{{ resource.domain || resource.domainid || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.project')">{{ resource.project || resource.projectname || resource.projectid || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.zone')">{{ resource.zonename || resource.zoneid || '-' }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="operationError" type="error" show-icon :message="$t(operationError)" :description="operation?.error || ''" />
      <a-alert v-if="knownJob" type="info" show-icon :message="$t('message.storage.service.removal.known.job')" :description="knownJob" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.service.removal.policy')">
          <a-select v-model:value="policy" :disabled="intentLocked || submitting" @change="refreshPlan">
            <a-select-option value="PRESERVE_VOLUMES">{{ $t('label.storage.service.removal.preserve') }}</a-select-option>
            <a-select-option value="DELETE_VOLUMES">{{ $t('label.storage.service.removal.delete') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-alert v-if="readError" type="error" show-icon :message="$t('message.storage.service.removal.preview.failed')" />
        <a-button :loading="loading" :disabled="intentLocked || submitting || !authorized" @click="refreshPlan"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
        <a-table :dataSource="impacts" :columns="volumeColumns" :pagination="false" rowKey="rowKey" :scroll="{ x: 1280 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'role'">{{ $t(roleLabel(record.role)) }}</template>
            <template v-if="column.key === 'name'">{{ record.name || '-' }}<div class="removal-volume-id">{{ record.uuid || $t('label.storage.service.removal.unknown') }}</div></template>
            <template v-if="column.key === 'protocols'">{{ protocolLabel(record) }}</template>
            <template v-if="column.key === 'sizeBytes'">{{ record.sizeBytes == null ? '-' : $bytesToHumanReadableSize(record.sizeBytes) }}</template>
            <template v-if="column.key === 'scope'">{{ volumeScope(record) }}</template>
            <template v-if="column.key === 'action'">{{ $t(record.role === 'ROOT' || record.role === 'ROOT_UNKNOWN' ? 'message.storage.service.removal.root.finalization' : policy === 'PRESERVE_VOLUMES' ? 'label.storage.service.removal.preserve' : 'label.storage.service.removal.delete') }}</template>
          </template>
        </a-table>
        <a-alert v-if="plan" type="info" show-icon :message="$t('message.storage.service.removal.ownership')" />
        <a-form-item v-if="apiName === 'destroySharedFileSystem'" :label="$t('label.expunge')">
          <a-switch v-model:checked="expunge" :disabled="intentLocked || submitting" @change="finalConfirmation = false" />
          <div>{{ $t('message.storage.service.removal.finalization') }}</div>
        </a-form-item>
        <template v-if="policy === 'DELETE_VOLUMES'">
          <a-alert type="error" show-icon :message="$t('message.storage.service.removal.data.loss')" />
          <a-form-item :label="$t('label.storage.service.removal.confirm.name')">
            <a-input v-model:value="confirmation" :disabled="intentLocked || submitting" :placeholder="resource.name" @change="finalConfirmation = false" />
          </a-form-item>
          <a-checkbox v-model:checked="acknowledged" :disabled="intentLocked || submitting" @change="finalConfirmation = false">{{ $t('message.storage.service.removal.confirm.loss') }}</a-checkbox>
        </template>
      </a-form>
    </a-spin>
  </a-modal>
  <a-modal
    class="mold-dialog sharedfs-removal-dialog"
    centered
    :visible="finalConfirmation"
    :maskClosable="false"
    :title="$t('label.storage.service.removal.final.confirmation')"
    :confirmLoading="submitting"
    :okButtonProps="{ disabled: !canApprove, danger: true }"
    :okText="$t('label.storage.service.removal.delete')"
    :cancelText="$t('label.cancel')"
    @cancel="finalConfirmation = false"
    @ok="execute">
    <a-alert type="error" show-icon :message="$t('message.storage.service.removal.data.loss')" />
    <p>{{ resource.name }} ({{ resource.id }})</p>
    <p>{{ $t('label.storage.service.removal.delete') }}: {{ plan?.volumes?.length || 0 }} {{ $t('label.volumes') }}</p>
    <p>{{ $t('message.storage.service.removal.root.finalization') }}</p>
    <p>{{ $t('message.storage.service.removal.finalization') }}</p>
  </a-modal>
</template>
<script>
import { ReloadOutlined } from '@ant-design/icons-vue'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { sharedfsRemovalIntents, removalActor, validRemovalPlan, removalImpacts } from '@/utils/sharedfsRemoval'

const protocolNames = { NFS: 'NFS', SMB: 'SMB', ISCSI: 'iSCSI', NVME_OF: 'NVMe-oF' }

export default {
  name: 'SharedFSRemoval',
  components: { ReloadOutlined },
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, required: true } },
  emits: ['close-action'],
  data () {
    return {
      policy: 'PRESERVE_VOLUMES',
      expunge: false,
      confirmation: '',
      acknowledged: false,
      loading: false,
      submitting: false,
      readError: false,
      plan: null,
      planContext: '',
      requestToken: 0,
      finalConfirmation: false,
      approvalContext: '',
      disposed: false
    }
  },
  computed: {
    apiName () { return this.currentAction.api || 'destroySharedFileSystem' },
    authorized () { return ['destroySharedFileSystem', 'expungeSharedFileSystem'].includes(this.apiName) && [this.apiName, 'getSharedFileSystemDeletionPlan', 'queryAsyncJobResult'].every(api => api in (this.$store?.getters?.apis || {})) },
    actor () { return removalActor(this) },
    contextKey () { return JSON.stringify([this.resource.id, this.resource.name, this.resource.state, this.resource.virtualmachineid, this.resource.volumeid, this.resource.account, this.resource.domainid, this.resource.projectid, this.resource.zoneid, this.apiName, this.actor, this.authorized]) },
    operation () {
      const intent = sharedfsRemovalIntents[this.resource.id]
      return intent?.actor === this.actor ? intent : null
    },
    knownJob () { return this.operation && this.operation.status !== 'failed' && this.operation.status !== 'complete' ? this.operation.jobId : '' },
    intentLocked () { return !!this.operation && ['submitting', 'running', 'unknown'].includes(this.operation.status) },
    operationError () { return this.operation?.status === 'unknown' ? 'message.storage.service.removal.unknown' : this.operation?.status === 'failed' ? 'message.storage.service.removal.failed' : '' },
    impacts () { return this.plan ? removalImpacts(this.plan) : [] },
    planReady () { return this.planContext === this.contextKey && validRemovalPlan(this.plan, this.resource, this.policy) },
    canSubmit () {
      if (!this.authorized || this.loading || this.submitting) return false
      if (this.knownJob) return true
      return !this.intentLocked && !this.readError && this.planReady &&
        (this.policy === 'PRESERVE_VOLUMES' || (this.acknowledged && this.confirmation === this.resource.name))
    },
    canApprove () { return this.finalConfirmation && this.canSubmit && this.policy === 'DELETE_VOLUMES' && this.approvalContext === this.confirmationContext() },
    volumeColumns () {
      const columns = [
        ['role', 'label.type', 150], ['name', 'label.name', 280], ['protocols', 'label.storage.service.removal.protocols', 150],
        ['sizeBytes', 'label.size', 120], ['scope', 'label.storage.service.removal.owner.ids', 200], ['action', 'label.action', 380]
      ]
      return columns.map(([key, title, width]) => ({ key, dataIndex: key, title: this.$t(title), width }))
    }
  },
  watch: {
    contextKey () { this.invalidate(); this.initialize() }
  },
  created () { this.initialize() },
  beforeUnmount () { this.disposed = true; this.requestToken++ },
  methods: {
    roleLabel (role) { return { ROOT: 'label.storage.service.removal.root', ROOT_UNKNOWN: 'label.storage.service.removal.root.unknown', DEFAULT_DATA: 'label.storage.service.removal.default.data', ADDITIONAL_DATA: 'label.storage.service.removal.additional.data' }[role] || 'label.storage.service.removal.data.unknown' },
    protocolLabel (row) {
      if (row.role === 'ROOT' || row.role === 'ROOT_UNKNOWN') return '-'
      if (!Array.isArray(row.protocols)) return this.$t('label.storage.service.removal.unknown')
      return row.protocols.length ? row.protocols.map(protocol => protocolNames[protocol] || this.$t('label.storage.service.removal.unknown')).join(', ') : this.$t('label.storage.service.removal.unassigned')
    },
    volumeScope (row) { return ['accountId', 'domainId', 'zoneId'].map(key => row[key] ?? '?').join(' / ') },
    invalidate () { this.requestToken++; this.plan = null; this.planContext = ''; this.loading = false; this.confirmation = ''; this.acknowledged = false; this.finalConfirmation = false },
    initialize () {
      if (this.disposed) return
      if (this.intentLocked) {
        this.policy = this.operation.params.datavolumepolicy
        this.expunge = this.operation.params.expunge === true
        this.plan = this.operation.plan; this.planContext = this.contextKey
      } else this.refreshPlan()
    },
    close () { if (!this.submitting) this.$emit('close-action') },
    confirmationContext () { return JSON.stringify([this.contextKey, this.policy, this.expunge, this.plan?.planHash, this.confirmation, this.acknowledged]) },
    async refreshPlan () {
      if (this.intentLocked || this.submitting || this.disposed) return
      const request = ++this.requestToken; const context = this.contextKey; const policy = this.policy
      this.plan = null; this.planContext = ''; this.loading = true; this.readError = false
      this.confirmation = ''; this.acknowledged = false; this.finalConfirmation = false
      if (!this.authorized) { this.loading = false; this.readError = true; return }
      try {
        const response = await getAPI('getSharedFileSystemDeletionPlan', { id: this.resource.id, datavolumepolicy: policy }, { preserveOnFailure: true, timeout: 15000 })
        if (this.disposed || request !== this.requestToken || context !== this.contextKey) return
        const entry = response?.getsharedfilesystemdeletionplanresponse?.sharedfilesystemdeletionplan
        const plan = JSON.parse(entry?.plan)
        if (!validRemovalPlan(plan, this.resource, policy) || (entry.id && entry.id !== this.resource.id) || (entry.planhash && entry.planhash !== plan.planHash)) throw new Error('Invalid removal preview')
        this.plan = plan; this.planContext = context
      } catch (error) {
        if (!this.disposed && request === this.requestToken && context === this.contextKey) this.readError = true
      } finally {
        if (!this.disposed && request === this.requestToken && context === this.contextKey) this.loading = false
      }
    },
    async submit () {
      if (!this.canSubmit) return
      if (this.knownJob) return this.track(this.operation)
      if (this.policy === 'DELETE_VOLUMES') {
        this.approvalContext = this.confirmationContext(); this.finalConfirmation = true
        return
      }
      return this.execute()
    },
    async execute () {
      if (!this.canSubmit || this.knownJob || this.policy === 'DELETE_VOLUMES' && !this.canApprove) return
      const params = { id: this.resource.id, datavolumepolicy: this.policy, expectedplanhash: this.plan.planHash }
      if (this.apiName === 'destroySharedFileSystem') { params.expunge = this.expunge; params.forced = this.resource.state === 'Ready' }
      if (this.policy === 'DELETE_VOLUMES') params.confirmdataloss = this.confirmation
      const intent = { actor: this.actor, api: this.apiName, params, plan: this.plan, jobId: '', status: 'submitting', error: '' }
      sharedfsRemovalIntents[this.resource.id] = intent
      const operation = this.operation
      const current = () => sharedfsRemovalIntents[params.id] === operation && operation.actor === this.actor
      this.submitting = true; this.finalConfirmation = false
      try {
        const response = await postAPI(intent.api, params, { preserveOnFailure: true, timeout: 15000 })
        if (!current()) return
        const job = response?.[intent.api.toLowerCase() + 'response']?.jobid
        if (typeof job !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(job)) { operation.status = 'unknown'; return }
        operation.jobId = job; operation.status = 'running'
      } catch (error) {
        if (!current()) return
        // A definitive API error permits fresh review. Transport ambiguity never permits another POST.
        const reply = error?.response?.data
        const rejection = reply?.errorresponse || reply?.[intent.api.toLowerCase() + 'response']
        operation.status = rejection && Number.isInteger(rejection.errorcode) && typeof rejection.errortext === 'string' && !rejection.jobid ? 'failed' : 'unknown'
        operation.error = operation.status === 'failed' ? this.failureText(error) : ''
        this.$notifyError(error)
      } finally { if (!this.disposed) this.submitting = false }
      if (!current() || this.resource.id !== params.id) return
      if (operation.jobId) return this.track(operation)
      if (operation.status === 'failed' && !this.disposed) return this.refreshPlan()
    },
    failureText (error) {
      const body = error?.response?.data
      const detail = body && typeof body === 'object' ? Object.values(body).find(value => value?.errortext)?.errortext : ''
      return String(detail || error?.jobresult?.errortext || error?.message || '').slice(0, 1000)
    },
    async track (operation) {
      if (this.submitting || !this.authorized || !operation?.jobId || !['running', 'unknown'].includes(operation.status)) return
      const id = this.resource.id; const actor = this.actor
      const current = () => sharedfsRemovalIntents[id] === operation && actor === this.actor && this.resource.id === id
      this.submitting = true; operation.status = 'running'; operation.error = ''
      try {
        const result = await this.$pollJob({
          jobId: operation.jobId,
          resourceId: id,
          retry: true,
          refreshOnComplete: false,
          title: this.$t(this.currentAction.label),
          description: this.resource.name,
          successMessage: this.$t('message.success'),
          errorMessage: this.$t('message.error')
        })
        if (!current()) return
        operation.status = result?.jobstatus === 1 ? 'complete' : result?.jobstatus === 2 ? 'failed' : 'unknown'
        if (operation.status === 'failed') operation.error = this.failureText(result)
        if (operation.status === 'complete' && !this.disposed) {
          this.$emit('close-action')
          eventBus.emit('async-job-complete', { api: operation.api })
        }
      } catch (error) { if (current()) operation.status = 'unknown' } finally { if (!this.disposed) this.submitting = false }
      if (current() && operation.status === 'failed' && !this.disposed) await this.refreshPlan()
    }
  }
}
</script>
<style scoped>
.sharedfs-removal-dialog :deep(.ant-modal-content) { display: flex; flex-direction: column; max-height: calc(100vh - 48px); }
.sharedfs-removal-dialog :deep(.ant-modal-body) { overflow-y: auto; min-height: 0; }
.sharedfs-removal-dialog :deep(.ant-modal-header), .sharedfs-removal-dialog :deep(.ant-modal-footer) { flex-shrink: 0; }
.removal-volume-id { overflow-wrap: anywhere; }
</style>
