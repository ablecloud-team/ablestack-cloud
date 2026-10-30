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
  <div class="backup-delete-dialog">
    <a-alert type="warning" :message="$t('message.delete.backup')" style="margin-bottom: 16px" />
    <a-form layout="vertical">
      <a-form-item :label="$t('label.forced')">
        <a-switch v-model:checked="forced" :disabled="busy" />
      </a-form-item>
    </a-form>
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert v-if="showDependentDeletion" type="warning" show-icon :message="$t('message.backup.delete.force')" />
      <a-alert v-if="forced && hasOther" type="warning" show-icon :message="$t('message.vmbackup.delete')" />
      <div v-if="showDependentDeletion" class="backup-delete-scope">
        <strong>{{ $t('label.backup.delete.affected') }} ({{ affected.length }})</strong>
        <ul><li v-for="point in affected" :key="point.id">{{ point.name || point.id }} · {{ point.type }}</li></ul>
      </div>
    </a-spin>
    <div class="backup-delete-footer">
      <a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="error" :disabled="busy || loading" @click="loadPlans">{{ $t('label.refresh') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="loading || !!error || !records.length" @click="submit">{{ $t('label.ok') }}</a-button>
    </div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { isAblestackBackupProvider } from '@/utils/ablestackBackupPolicy'
import { orderBackupDeletions, validBackupDeletionPlan, withinBackupDeletionPlan } from '@/utils/backupDeletion'

export default {
  name: 'DeleteBackup',
  props: {
    resource: { type: Object, default: () => ({}) },
    currentAction: { type: Object, default: () => ({}) },
    selectedItems: { type: Array, default: () => [] },
    selectedRowKeys: { type: Array, default: () => [] }
  },
  emits: ['close-action', 'refresh-data', 'refresh'],
  data () { return { records: [], plans: [], forced: false, loading: false, busy: false, error: '', revision: 0 } },
  computed: {
    hasAblestack () { return this.records.some(isAblestackBackupProvider) },
    hasOther () { return this.records.some(record => !isAblestackBackupProvider(record)) },
    affected () { return this.unique(this.plans.flatMap(plan => plan.backup || [])) },
    showDependentDeletion () {
      return this.forced && !this.loading && !this.error && this.plans.some(plan => plan.backup.some(point => point.id !== plan.id))
    }
  },
  watch: {
    forced () { if (this.records.length) this.loadPlans() },
    resource: { handler () { this.initialize() }, deep: true }
  },
  mounted () { this.initialize() },
  activated () { this.initialize() },
  deactivated () { this.revision++; this.busy = false },
  beforeUnmount () { this.revision++ },
  methods: {
    unique (points) { return [...new Map(points.map(point => [point.id, point])).values()] },
    failureMessage (error) {
      const apiError = Object.values(error?.response?.data || {}).find(value => value?.errortext)
      return error?.jobresult?.errortext || apiError?.errortext || error?.message || this.$t('message.backup.delete.unconfirmed')
    },
    async initialize () {
      if (this.busy) return
      this.forced = this.resource.deletionforced === true
      this.records = []
      const ids = this.currentAction.invokedAsGroupAction ? this.selectedRowKeys : [this.resource.id]
      const token = ++this.revision
      this.loading = true; this.error = ''; this.plans = []
      try {
        const records = await Promise.all(ids.map(async id => {
          const response = await getAPI('listBackups', { id, listall: true }, { preserveOnFailure: true })
          const record = response.listbackupsresponse?.backup?.find(point => point.id === id)
          if (!record) throw new Error(this.$t('message.vmbackup.unavailable'))
          return record
        }))
        if (token !== this.revision) return
        this.records = records
        await this.loadPlans()
      } catch (error) { if (token === this.revision) { this.error = this.failureMessage(error); this.loading = false } }
    },
    async loadPlans () {
      const token = ++this.revision
      const forced = this.forced
      this.loading = true; this.error = ''; this.plans = []
      try {
        const plans = await Promise.all(this.records.filter(isAblestackBackupProvider).map(async record => {
          const response = await getAPI('getBackupDeletionPlan', { id: record.id, forced }, { preserveOnFailure: true })
          const plan = response.getbackupdeletionplanresponse?.backupdeletionplan
          if (!validBackupDeletionPlan(plan, record, forced)) throw new Error(this.$t('message.backup.delete.preview.failed'))
          return plan
        }))
        if (token === this.revision) this.plans = plans
      } catch (error) { if (token === this.revision) this.error = this.failureMessage(error) } finally { if (token === this.revision) this.loading = false }
    },
    async submit () {
      if (this.busy || this.loading || this.error) return
      this.busy = true
      const forced = this.forced
      const planned = orderBackupDeletions(this.records, this.plans, forced)
      const originalPage = this.$route.path
      const revision = this.revision
      const scope = () => JSON.stringify([this.$store?.state?.user?.token, this.$store?.getters.userInfo?.id, this.$store?.getters.project?.id])
      const originalScope = scope()
      let closed = false
      let polling = false
      const ownsDialog = () => !closed && revision === this.revision
      const close = () => {
        if (closed) return
        if (ownsDialog()) {
          this.busy = false
          this.$emit('refresh-data'); this.$emit('refresh'); this.$emit('close-action')
        }
        closed = true
      }
      try {
        if (!planned?.length) throw new Error(this.$t('message.backup.delete.preview.failed'))
        for (const { record, plan: confirmed } of planned) {
          if (originalScope !== scope()) return
          polling = false
          let plan
          if (confirmed) {
            const listed = await getAPI('listBackups', { id: record.id, listall: true }, { preserveOnFailure: true })
            if (originalScope !== scope()) return
            if (!listed.listbackupsresponse) throw new Error(this.$t('message.backup.delete.preview.failed'))
            const fresh = (listed.listbackupsresponse.backup || []).find(point => point.id === record.id)
            // A preceding deletion may also sweep an already pending ancestor.
            if (!fresh) continue
            const response = await getAPI('getBackupDeletionPlan', { id: record.id, forced }, { preserveOnFailure: true })
            if (originalScope !== scope()) return
            plan = response.getbackupdeletionplanresponse?.backupdeletionplan
            if (!validBackupDeletionPlan(plan, record, forced) || fresh.provider !== record.provider || !withinBackupDeletionPlan(confirmed, plan)) {
              throw new Error(this.$t('message.backup.delete.scope.changed'))
            }
          }
          const response = await postAPI('deleteBackup', { id: record.id, forced, ...(plan ? { deletionplan: plan.deletionplan } : {}) }, { preserveOnFailure: true })
          if (originalScope !== scope()) return
          const jobId = response.deletebackupresponse?.jobid
          if (!jobId) throw new Error(this.$t('message.backup.delete.unconfirmed'))
          const name = record.name || record.id
          polling = true
          // Keep bulk operations ordered; a failed or unconfirmed deletion must stop the batch.
          const completion = new Promise((resolve, reject) => {
            this.$pollJob({
              jobId,
              originalPage,
              title: this.$t('label.delete.backup'),
              description: name,
              name,
              loadingMessage: `${this.$t('label.delete.backup')} - ${name}`,
              showLoading: true,
              showSuccessMessage: true,
              successMessage: plan?.state === 'WAITING_DEPENDENCIES' ? this.$t('label.backup.delete.requested') : undefined,
              resourceId: record.id,
              action: { api: 'deleteBackup', label: 'label.delete.backup' },
              successMethod: resolve,
              errorMethod: reject,
              catchMethod: reject
            }).catch(reject)
          })
          close()
          await completion
        }
        close()
      } catch (error) {
        if (originalScope !== scope()) return
        if (ownsDialog()) {
          this.error = this.failureMessage(error)
          this.$emit('refresh-data'); this.$emit('refresh')
        } else if (!polling) {
          // Job failures are reported by the common tracker; later submission failures need a notification.
          this.$notifyError(error)
        }
      } finally { if (ownsDialog()) this.busy = false }
    }
  }
}
</script>

<style scoped>
.backup-delete-dialog { min-width: 420px; max-width: 800px; }
.backup-delete-scope { margin-top: 16px; max-height: 350px; overflow-y: auto; overflow-wrap: anywhere; }
.backup-delete-footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
</style>
