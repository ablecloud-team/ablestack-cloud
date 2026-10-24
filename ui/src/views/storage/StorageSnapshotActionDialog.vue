<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
<template>
  <MoldDialog
    :visible="!closed"
    :size="isStandard ? 'md' : 'lg'"
    :class="{ 'snapshot-standard-dialog': isStandard }"
    :title="$t(actionLabel)"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="false"
    :form-actions="true"
    @cancel="close">
    <div :class="{ 'snapshot-restore-content': isStandard }">
      <a-alert v-if="!isStandard" type="warning" show-icon :message="$t(actionMessage)" />
      <a-alert v-if="error && (!isStandard || !contexts.length)" type="error" show-icon role="alert" :message="error">
        <template v-if="isStandard" #description><a-button type="link" size="small" :disabled="busy" @click="refresh">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <StorageSnapshotSafety
        v-for="(context, i) in contexts"
        :key="targets[i].id + targets[i].zoneId"
        :context="context"
        :compact="isStandard && contexts.length === 1"
        :recovery-message="isDownload ? 'message.snapshot.download.source.absent' : undefined"
        :error="isStandard && i === 0 ? error : undefined"
        :loading="busy"
        :pending="operationPending"
        @refresh="refresh" />
      <a-alert v-if="isRestore" type="warning" show-icon :message="$t('message.snapshot.restore.data.loss')" :description="restoreDescription" />
      <a-alert v-if="isArchive && contexts.length && !contexts[0].reason" type="info" show-icon :message="$t('message.snapshot.archive.effect')" :description="$t('message.snapshot.archive.reason.' + contexts[0].diagnosis.reason)" />
      <a-alert v-if="isDownload" type="info" show-icon :message="$t('message.snapshot.download.prepare')" :description="$t('message.snapshot.download.replace')" />
      <p v-if="!contexts.length && !error" role="status">{{ $t('message.snapshot.action.validating') }}</p>
      <a-checkbox v-if="!isDownload" v-model:checked="acknowledged" :disabled="busy || blocked">{{ $t(isRestore ? 'message.snapshot.restore.acknowledge' : isArchive ? 'message.snapshot.archive.acknowledge' : 'message.snapshot.action.acknowledge') }}</a-checkbox>
      <p v-for="result in results" :key="result.id">{{ result.target.name || result.target.id }} · {{ $t('label.snapshot.action.status.' + result.status) }} · {{ result.jobid }}</p>
    </div>
    <template #footer>
      <a-button :disabled="busy" @click="close">{{ $t(isStandard ? 'label.cancel' : 'label.close') }}</a-button>
      <a-button v-if="!isStandard" :disabled="busy" @click="refresh">{{ $t('label.snapshot.action.refresh') }}</a-button>
      <a-button type="primary" :danger="!isDownload && !isArchive" :loading="busy" :disabled="busy || blocked || (!isDownload && !acknowledged)" @click="submit">{{ $t(isDownload ? 'label.snapshot.download.prepare' : isRestore ? 'label.restore' : actionLabel) }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog.vue'
import StorageSnapshotSafety from '@/components/view/StorageSnapshotSafety.vue'
import { freezeSnapshotTarget, freshStorageSnapshotContext, snapshotSecurityScope, snapshotActionParams, snapshotResourceKeys } from '@/utils/storageSnapshotContext'
import { submitSnapshotOperation, snapshotOperationBusy } from '@/utils/storageSnapshotOperations'
export default {
  name: 'StorageSnapshotActionDialog',
  components: { MoldDialog, StorageSnapshotSafety },
  emits: ['close-action', 'refresh-data'],
  props: { resource: { type: Object, default: () => ({}) }, currentAction: { type: Object, default: () => ({}) } },
  data () { return { targets: [], contexts: [], results: [], acknowledged: false, busy: false, error: '', closed: false, active: true, originPath: '', actionApi: '', actionLabel: '', actionMessage: '', security: '', sequence: 0 } },
  computed: {
    isRestore () { return this.actionApi === 'revertSnapshot' },
    isDownload () { return this.actionApi === 'extractSnapshot' },
    isArchive () { return this.actionApi === 'archiveSnapshot' },
    isStandard () { return this.isRestore || this.isDownload || this.isArchive },
    restoreDescription () {
      const context = this.contexts[0]
      if (this.targets.length !== 1) return this.$t('message.snapshot.restore.targets', [this.targets.length])
      return this.$t('message.snapshot.restore.target', [context?.source.volumename || context?.record.volumename || context?.record.volumeid || this.targets[0]?.record.volumeid || '', this.targets[0]?.record.name || this.targets[0]?.id || ''])
    },
    operationPending () { return this.contexts.some(c => snapshotOperationBusy(this.security, snapshotResourceKeys(c.target, c))) },
    blocked () { return !!this.error || !this.contexts.length || this.contexts.some(c => c.reason || snapshotOperationBusy(this.security, snapshotResourceKeys(c.target, c))) || !this.current() }
  },
  created () {
    this.actionApi = this.currentAction.api
    this.actionLabel = this.currentAction.label || (this.isRestore ? 'label.action.revert.snapshot' : 'label.action.delete.snapshot')
    this.actionMessage = this.currentAction.message || 'message.action.delete.snapshot'
    this.originPath = this.$route.path; this.security = snapshotSecurityScope(this.$store)
    const rows = this.currentAction.storageSnapshotTargets || [this.resource]
    this.targets = rows.map(row => freezeSnapshotTarget(row, { zoneId: this.currentAction.zoneScope ? row.zoneid || null : 'all' }))
    this.refresh()
  },
  beforeUnmount () { this.active = false },
  methods: {
    current () { return this.active && !this.closed && this.originPath === this.$route.path && this.security === snapshotSecurityScope(this.$store) },
    close () { this.closed = true; this.active = false; this.$emit('close-action') },
    async refresh () {
      if (this.busy || !this.current()) return
      const sequence = ++this.sequence; this.busy = true; this.error = ''; this.acknowledged = false
      try {
        const contexts = await Promise.all(this.targets.map(target => freshStorageSnapshotContext(target, this.actionApi, () => this.current() && sequence === this.sequence)))
        if (this.current() && sequence === this.sequence) this.contexts = contexts
      } catch (error) { if (this.current()) this.error = this.$t(error.message) } finally { this.busy = false }
    },
    async submit () {
      if (this.busy || this.blocked || (!this.isDownload && !this.acknowledged)) return
      this.busy = true; this.error = ''
      const store = this.$store; const scope = this.security
      try {
        // Check the complete frozen selection before sending the first mutation.
        const latest = await Promise.all(this.contexts.map(c => freshStorageSnapshotContext(c.target, this.actionApi, () => this.current())))
        if (latest.some((c, i) => c.reason || c.fingerprint !== this.contexts[i].fingerprint)) throw new Error('message.snapshot.action.changed')
        for (const context of this.contexts) {
          if (!this.current()) break
          const operation = await submitSnapshotOperation({
            api: this.actionApi,
            scope,
            target: { id: context.target.id, name: context.record.name, volumeid: context.record.volumeid, zoneid: context.target.zoneId },
            keys: snapshotResourceKeys(context.target, context),
            current: () => this.current(),
            securityCurrent: () => snapshotSecurityScope(store) === scope,
            validate: async () => {
              const fresh = await freshStorageSnapshotContext(context.target, this.actionApi, () => this.current())
              if (fresh.reason || fresh.fingerprint !== context.fingerprint) throw new Error('message.snapshot.action.changed')
              return snapshotActionParams(context.target, this.actionApi, {}, fresh)
            }
          })
          this.results.push(operation)
          if (this.targets.length === 1 && operation.jobid) { this.close(); return }
          if (operation.completion) await operation.completion
          if (operation.status !== 'complete') { this.error = this.$t(operation.error || 'message.job.result.unknown'); break }
        }
        if (this.current() && this.results.length === this.targets.length && this.results.every(r => r.status === 'complete')) { this.$emit('refresh-data'); this.close() }
      } catch (error) { if (this.current()) this.error = this.$t(error.message) } finally { this.busy = false; this.acknowledged = false }
    }
  }
}
</script>
<style scoped>
.snapshot-restore-content { display: flex; flex-direction: column; gap: 24px; min-width: 0; }
.snapshot-restore-content > .snapshot-safety-compact { margin: 0; }
.snapshot-restore-content > p { margin: 0; }
.snapshot-restore-content :deep(.ant-checkbox-wrapper) { display: flex; align-items: flex-start; margin: 0; white-space: normal; }
.snapshot-restore-content :deep(.ant-checkbox) { flex: 0 0 auto; margin-top: 4px; top: 0; }
.snapshot-restore-content :deep(.ant-checkbox-wrapper > span:last-child) { min-width: 0; overflow-wrap: anywhere; }
</style>
