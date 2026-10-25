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
    focus-guard
:visible="!closed"
size="md"
class="snapshot-standard-dialog"
form-actions
:title="$t('label.action.copy.snapshot')"
:closable="!busy"
    :keyboard="!busy"
:mask-closable="false"
@cancel="close">
    <div class="snapshot-transfer-content" @keydown.ctrl.enter.prevent="submit">
      <StorageSnapshotSafety compact :context="context" :error="error" :loading="busy" :pending="pending" @refresh="refresh" />
      <a-alert type="info" show-icon :message="$t('message.snapshot.transfer.keep.source')" :description="sourceDescription" />
      <a-alert v-if="context && !context.reason && !destinations.length && !error" type="info" show-icon :message="$t('message.snapshot.transfer.empty')" />
      <a-alert v-else-if="context && !context.reason && destinations.length && !available.length && !error" type="warning" show-icon :message="$t('message.snapshot.transfer.none.eligible')" />
      <a-form layout="vertical" class="snapshot-transfer-form snapshot-dialog-form">
        <a-form-item :label="$t('label.snapshot.transfer.destinations')" required>
          <a-select
id="snapshot-copy-destinations"
v-model:value="selected"
mode="multiple"
show-search
option-filter-prop="label"
:placeholder="$t('label.select.zones')"
            :loading="busy"
:disabled="busy || !!error || !context || !!context.reason || pending">
            <a-select-option v-for="row in destinations" :key="row.zoneid" :value="row.zoneid" :label="row.name" :disabled="!row.eligible">
              {{ row.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <ul v-if="destinations.length" class="snapshot-transfer-targets" aria-live="polite">
          <li v-for="row in destinations" :key="row.zoneid" :data-zone-id="row.zoneid">
            <span class="snapshot-transfer-zone">{{ row.name }}</span>
            <span>{{ row.storename || $t('label.snapshot.transfer.secondary.auto') }} · {{ $t('message.snapshot.transfer.reason.' + (row.reason || 'AVAILABLE')) }}</span>
          </li>
        </ul>
        <a-button type="link" size="small" :disabled="busy" @click="refresh">{{ $t('label.snapshot.transfer.refresh') }}</a-button>
      </a-form>
    </div>
    <template #footer>
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="blocked" @click="submit">{{ $t('label.action.copy.snapshot') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog.vue'
import StorageSnapshotSafety from '@/components/view/StorageSnapshotSafety.vue'
import { freezeSnapshotTarget, freshStorageSnapshotContext, snapshotSecurityScope, snapshotActionParams, snapshotResourceKeys } from '@/utils/storageSnapshotContext'
import { snapshotTransferFailure } from '@/utils/storageSnapshotTransfer'
import { submitSnapshotOperation, snapshotOperationBusy } from '@/utils/storageSnapshotOperations'
export default {
  name: 'SnapshotCopyDialog',
  components: { MoldDialog, StorageSnapshotSafety },
  emits: ['close-action', 'refresh-data'],
  props: { resource: { type: Object, required: true }, sourceZone: { type: Object, required: true } },
  data () { return { target: null, context: null, selected: [], security: '', originPath: '', sequence: 0, active: true, closed: false, busy: false, error: '' } },
  computed: {
    destinations () { return this.context?.copyplan?.destinations || [] },
    available () { return this.destinations.filter(row => row.eligible) },
    pending () { return !!this.target && snapshotOperationBusy(this.security, snapshotResourceKeys(this.target, this.context)) },
    blocked () { return this.busy || !!this.error || !this.context || !!this.context.reason || this.pending || !this.current() || !this.selected.length || this.selected.some(id => !this.available.some(row => row.zoneid === id)) },
    sourceDescription () {
      const plan = this.context?.copyplan
      return plan ? this.$t('message.snapshot.dialog.copy.source', [this.target.record.name || this.target.id, plan.sourcezonename || this.target.zoneId, plan.sourcestorename || this.$t('label.snapshot.transfer.secondary.auto')]) : ''
    },
    securityScope () { return snapshotSecurityScope(this.$store) }
  },
  watch: {
    securityScope () { this.close() },
    'resource.id' () { this.close() },
    'sourceZone.zoneid' () { this.close() }
  },
  created () {
    this.security = this.securityScope; this.originPath = this.$route.path
    this.target = freezeSnapshotTarget(this.resource, { zoneId: this.sourceZone.zoneid || null })
    this.refresh()
  },
  beforeUnmount () { this.active = false; this.sequence++ },
  methods: {
    current () { return this.active && !this.closed && this.originPath === this.$route.path && this.security === snapshotSecurityScope(this.$store) },
    close () { if (this.closed) return; this.closed = true; this.active = false; this.sequence++; this.$emit('close-action') },
    async refresh () {
      if (this.busy || !this.current()) return
      const sequence = ++this.sequence; this.busy = true; this.error = ''
      try {
        const context = await freshStorageSnapshotContext(this.target, 'copySnapshot', () => this.current() && sequence === this.sequence)
        if (!this.current() || sequence !== this.sequence) return
        this.context = context
        this.selected = this.selected.filter(id => context.copyplan?.destinations.some(row => row.zoneid === id && row.eligible))
      } catch (error) {
        if (this.current() && sequence === this.sequence) {
          const message = snapshotTransferFailure(error); this.error = this.$t(message)
          if (message === 'message.snapshot.action.permission') { this.context = null; this.selected = [] }
        }
      } finally { if (sequence === this.sequence) this.busy = false }
    },
    async submit () {
      if (this.blocked) return
      const selected = [...this.selected]; const context = this.context; const store = this.$store; const scope = this.security
      this.busy = true; this.error = ''
      try {
        const operation = await submitSnapshotOperation({
          api: 'copySnapshot',
          scope,
          target: { id: this.target.id, name: this.target.record.name, zoneid: this.target.zoneId, destinationzoneids: selected, destinationnames: selected.map(id => this.destinations.find(row => row.zoneid === id).name) },
          keys: snapshotResourceKeys(this.target, context),
          current: () => this.current(),
          securityCurrent: () => snapshotSecurityScope(store) === scope,
          validate: async () => {
            const latest = await freshStorageSnapshotContext(this.target, 'copySnapshot', () => this.current())
            if (latest.reason || latest.fingerprint !== context.fingerprint || selected.some(id => !latest.copyplan?.destinations.some(row => row.zoneid === id && row.eligible))) throw new Error('message.snapshot.action.destination.changed')
            return snapshotActionParams(this.target, 'copySnapshot', { destzoneids: selected.join(',') }, latest)
          }
        })
        if (operation.jobid && this.current()) { this.$emit('refresh-data'); this.close() } else if (this.current()) this.error = this.$t(operation.error || 'message.job.result.unknown')
      } catch (error) { if (this.current()) this.error = this.$t(snapshotTransferFailure(error)) } finally { this.busy = false }
    }
  }
}
</script>
<style scoped>
.snapshot-transfer-content { display: flex; flex-direction: column; gap: 24px; min-width: 0; }
.snapshot-transfer-content > .snapshot-safety-compact { margin: 0; }
.snapshot-transfer-form :deep(.ant-form-item) { margin: 0 0 16px; }
.snapshot-transfer-targets { margin: 0; padding: 0; list-style: none; max-height: 240px; overflow-y: auto; color: var(--ui-text-secondary); }
.snapshot-transfer-targets li { display: flex; flex-direction: column; gap: 4px; padding: 10px 0; border-bottom: 1px solid var(--ui-border-color); overflow-wrap: anywhere; }
.snapshot-transfer-zone { color: var(--ui-text-primary); font-weight: 500; }
.snapshot-transfer-form > .ant-btn { padding-left: 0; margin-top: 12px; }
</style>
