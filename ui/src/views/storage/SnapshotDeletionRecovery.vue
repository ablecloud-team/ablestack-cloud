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
  <div class="snapshot-deletion-recovery">
    <a-alert type="warning" :message="$t('message.snapshot.recovery.scope')" show-icon />
    <a-form layout="vertical" style="margin-top: 16px">
      <a-form-item :label="$t('label.snapshot.id')">
        <a-input v-model:value="snapshotId" :disabled="busy || !!pendingJob" data-testid="recovery-snapshot-id" />
      </a-form-item>
      <a-form-item :label="$t('label.snapshot.recovery.zone')">
        <a-input v-model:value="zoneId" :disabled="busy || !!pendingJob" data-testid="recovery-zone-id" />
      </a-form-item>
      <a-form-item v-if="canForce">
        <a-checkbox v-model:checked="force" :disabled="busy || !!pendingJob" data-testid="recovery-force">
          {{ $t('label.snapshot.force.delete') }}
        </a-checkbox>
        <a-alert v-if="force" type="warning" :message="$t('message.snapshot.force.scope')" show-icon style="margin-top: 12px" />
      </a-form-item>
      <a-button :loading="busy" :disabled="busy || !!pendingJob" @click="diagnose">{{ $t('label.snapshot.recovery.diagnose') }}</a-button>
      <a-alert v-if="error" type="error" :message="error" show-icon style="margin-top: 16px" />
      <a-alert v-if="completed" :type="dbOnlyResult ? 'warning' : 'success'" :message="$t(dbOnlyResult ? 'message.snapshot.force.db.complete' : 'message.snapshot.recovery.complete')" style="margin-top: 16px" />
      <ul v-if="executionResult && force" data-testid="force-cleanup-results">
        <li v-for="(result, index) in (executionResult.cleanupresult || [])" :key="index">
          {{ result.role }} #{{ result.referenceid }}: {{ $t(result.action === 'PHYSICAL_DELETED' ? 'label.snapshot.force.physical.deleted' : 'label.snapshot.force.db.only') }}
        </li>
      </ul>
      <p v-if="executionResult && executionResult.pendingcleanup">{{ $t('label.snapshot.force.pending') }}: {{ executionResult.pendingcleanup }}</p>
      <div v-if="preview" class="recovery-preview" style="margin-top: 16px">
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item :label="$t('label.name')">{{ preview.name }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.snapshot.id')">{{ preview.snapshotid }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.state')">{{ preview.state }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.zone')">{{ preview.zoneid || $t('label.snapshot.recovery.allzones') }}</a-descriptions-item>
        </a-descriptions>
        <ul data-testid="recovery-references">
          <li v-for="ref in (preview.reference || [])" :key="ref.referenceid">
            #{{ ref.referenceid }} · {{ ref.storename }} · {{ ref.role }} · {{ ref.state }} · {{ ref.zoneid }}
          </li>
        </ul>
        <ul v-if="force && !completed" data-testid="force-cleanup-plan">
          <li v-for="(result, index) in (preview.cleanupresult || [])" :key="index">
            {{ result.role }} #{{ result.referenceid }}: {{ $t(result.action === 'DB_ONLY' ? 'label.snapshot.force.db.plan' : 'label.snapshot.force.physical.plan') }}
          </li>
        </ul>
        <p v-if="!(preview.reference || []).length">{{ $t(force && unmappedPlan ? 'message.snapshot.force.no.references' : 'message.snapshot.recovery.no.references') }}</p>
        <a-checkbox v-model:checked="acknowledged" :disabled="busy || !!pendingJob || completed">
          {{ $t(force ? 'message.snapshot.force.acknowledge' : 'message.snapshot.recovery.acknowledge') }}
        </a-checkbox>
      </div>
      <p v-if="pendingJob">{{ $t('label.job.id') }}: {{ pendingJob }}</p>
      <a-button v-if="pendingJob && !busy" @click="checkResult">{{ $t('label.snapshot.recovery.check.result') }}</a-button>
      <MoldDialogActions :busy="busy">
        <a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.close') }}</a-button>
        <a-button type="primary" danger :loading="busy" :disabled="!canRecover" @click="recover">
          {{ $t(force ? 'label.snapshot.force.execute' : 'label.snapshot.recovery.execute') }}
        </a-button>
      </MoldDialogActions>
    </a-form>
  </div>
</template>

<script>
import { snapshotSecurityScope, readSnapshotApiPermission, readSnapshotDiagnosis, copyTarget } from '@/utils/storageSnapshotContext'
import { storageSnapshotOperations, submitSnapshotOperation, querySnapshotOperation } from '@/utils/storageSnapshotOperations'
import { getAPI, postAPI } from '@/api'
import storageDialogContext from '@/utils/storageDialogContext'
import MoldDialogActions from '@/components/view/MoldDialogActions'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export default {
  name: 'SnapshotDeletionRecovery',
  components: { MoldDialogActions },
  mixins: [storageDialogContext],
  emits: ['close-action', 'refresh-data'],
  props: { resource: { type: Object, default: () => ({}) } },
  data () {
    return { operationId: '', force: false, executionResult: null, snapshotId: '', zoneId: '', preview: null, previewScope: null, acknowledged: false, busy: false, error: '', completed: false, pendingJob: '', blockedSubmission: false, sequence: 0 }
  },
  computed: {
    canForce () { return this.$store.getters.userInfo?.roletype === 'Admin' },
    unmappedPlan () { return !!this.preview?.cleanupresult?.some(result => result.reason === 'NO_IDENTIFIABLE_STORE_REFERENCE') },
    dbOnlyResult () { return this.executionResult?.force && this.executionResult.physicalcleanupcomplete === false },
    canRecover () { return !!this.preview && this.acknowledged && !this.busy && !this.pendingJob && !this.blockedSubmission && !this.completed && this.dialogCurrent() }
  },
  watch: {
    force () { this.invalidate() },
    snapshotId () { this.invalidate() },
    zoneId () { this.invalidate() }
  },
  methods: {
    invalidate () { this.sequence++; this.preview = null; this.previewScope = null; this.acknowledged = false; this.completed = false; this.executionResult = null },
    async waitForJob (jobId, sequence) {
      for (let i = 0; i < 120; i++) {
        if (!this.dialogCurrent() || sequence !== this.sequence) throw new Error(this.$t('message.snapshot.recovery.context.changed'))
        const json = await getAPI('queryAsyncJobResult', { jobid: jobId }, { preserveOnFailure: true, timeout: 20000 })
        const job = json.queryasyncjobresultresponse
        if (job.jobstatus === 1) return job.jobresult?.deletesnapshotresponse || job.jobresult
        if (job.jobstatus === 2) {
          const error = new Error(job.jobresult?.errortext || this.$t('message.snapshot.recovery.failed'))
          error.terminal = true
          throw error
        }
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
      throw new Error(this.$t('message.snapshot.recovery.unknown'))
    },
    showError (error) { this.error = error?.response?.data?.errorresponse?.errortext || error.message || this.$t('message.snapshot.recovery.failed') },
    async diagnose () {
      if (this.busy || this.pendingJob || !this.dialogCurrent()) return
      this.invalidate()
      this.error = ''
      if (this.force && !this.canForce) { this.error = this.$t('message.snapshot.force.admin'); return }
      const scope = { id: this.snapshotId.trim().toLowerCase() }
      if (this.force) scope.force = true
      if (this.zoneId.trim()) scope.zoneid = this.zoneId.trim().toLowerCase()
      if (!uuid.test(scope.id) || (scope.zoneid && !uuid.test(scope.zoneid))) {
        this.error = this.$t('message.snapshot.recovery.invalid.uuid'); return
      }
      const sequence = this.sequence
      this.busy = true
      try {
        const json = await postAPI('deleteSnapshot', { ...scope, dryrun: true })
        const response = json.deletesnapshotresponse
        const result = response.jobid ? await this.waitForJob(response.jobid, sequence) : response
        if (!this.dialogCurrent() || sequence !== this.sequence) return
        if (result.snapshotid !== scope.id || !result.scopehash || !!result.force !== !!scope.force) throw new Error(this.$t('message.snapshot.recovery.failed'))
        this.preview = result
        this.previewScope = scope
      } catch (error) { if (this.dialogCurrent() && sequence === this.sequence) this.showError(error) } finally { this.busy = false }
    },
    async recover () {
      if (!this.canRecover || !this.dialogCurrent()) return
      this.busy = true
      this.error = ''
      try {
        this.blockedSubmission = true
        const scope = copyTarget(this.previewScope); const expected = this.preview.scopehash; const store = this.$store; const security = snapshotSecurityScope(store); const sequence = this.sequence
        const operation = await submitSnapshotOperation({
          api: 'deleteSnapshot',
          scope: security,
          target: { id: scope.id, name: this.preview.name, zoneid: scope.zoneid || 'all', force: scope.force === true },
          keys: ['snapshot:' + scope.id],
          current: () => this.dialogCurrent() && sequence === this.sequence && snapshotSecurityScope(store) === security,
          securityCurrent: () => snapshotSecurityScope(store) === security,
          validate: async () => {
            await readSnapshotApiPermission('deleteSnapshot')
            const latest = await readSnapshotDiagnosis('deleteSnapshot', scope, () => this.dialogCurrent() && sequence === this.sequence)
            if (latest.snapshotid !== scope.id || latest.scopehash !== expected || !!latest.force !== !!scope.force) throw new Error('message.snapshot.action.changed')
            return { ...scope, expectedscopehash: expected }
          }
        })
        this.operationId = operation.id
        if (!operation.jobid) throw Object.assign(new Error(this.$t(operation.error || 'message.snapshot.recovery.unknown')), { submissionPrevented: operation.status === 'failed' })
        this.pendingJob = operation.jobid
        this.blockedSubmission = false
        await this.finishJob()
      } catch (error) {
        if (error.submissionPrevented || (error?.response?.status >= 400 && error?.response?.status < 500)) {
          this.blockedSubmission = false
          this.preview = null
          this.acknowledged = false
        }
        this.showError(error)
      } finally { this.busy = false }
    },
    async finishJob () {
      try {
        const operation = storageSnapshotOperations[this.operationId]
        let result
        if (operation) {
          const store = this.$store; const security = snapshotSecurityScope(store)
          const job = await (operation.status === 'unknown' ? querySnapshotOperation(operation, () => snapshotSecurityScope(store) === security) : operation.completion)
          if (job?.jobstatus === 2) throw Object.assign(new Error(job.jobresult?.errortext || this.$t('message.snapshot.recovery.failed')), { terminal: true })
          if (job?.jobstatus !== 1 || operation.status !== 'complete') throw new Error(this.$t('message.snapshot.recovery.unknown'))
          result = job.jobresult?.deletesnapshotresponse || job.jobresult
        } else result = await this.waitForJob(this.pendingJob, this.sequence)
        if (!this.dialogCurrent()) return
        if (this.previewScope?.force && (!result?.force || result.dbcleanupcomplete !== true)) {
          throw new Error(this.$t('message.snapshot.recovery.unknown'))
        }
        this.executionResult = result
        if (result?.force && this.preview) {
          this.preview = { ...this.preview, state: result.state || this.preview.state, reference: (this.preview.reference || []).map(ref => ({ ...ref, state: 'Destroyed' })) }
        }
        this.pendingJob = ''
        this.completed = true
        this.acknowledged = false
        this.$emit('refresh-data')
      } catch (error) {
        if (error.terminal) {
          this.pendingJob = ''
          this.preview = null
          this.acknowledged = false
        }
        // Unknown results retain the accepted job ID and cannot resubmit deletion.
        throw error
      }
    },
    async checkResult () {
      if (this.busy || !this.pendingJob) return
      this.busy = true
      this.error = ''
      try { await this.finishJob() } catch (error) { this.showError(error) } finally { this.busy = false }
    }
  }
}
</script>

<style lang="less" scoped>
.snapshot-deletion-recovery { width: 85vw; max-width: 680px; }
.recovery-preview { overflow-wrap: anywhere; }
</style>
