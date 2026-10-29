<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for copyright ownership.
Licensed under the Apache License, Version 2.0 (the "License");
http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <MoldDialog
    :visible="!closed"
    :title="$t('label.snapshot.policy.bulk.delete')"
    size="lg"
    form-actions
    focus-guard
    :closable="!busy"
    :keyboard="!busy"
    :after-close="finishClose"
    @cancel="close">
    <a-spin :spinning="busy">
      <a-alert type="info" show-icon :message="$t('message.snapshot.policy.bulk.scope', [entries.length])" />
      <a-alert v-if="error" class="policy-bulk-message" type="error" show-icon :message="error" />
      <a-alert v-if="attempted" class="policy-bulk-message" :type="allRemoved ? 'success' : 'warning'" show-icon :message="$t('message.snapshot.policy.bulk.results', [removed.length, retained.length, unknown.length])" />
      <a-collapse class="policy-bulk-message">
        <a-collapse-panel v-for="entry in entries" :key="entry.identity.id">
          <template #header><span class="policy-bulk-identity">{{ entry.target?.volume.name || entry.identity.volumeid }} · {{ policyInterval(entry.identity.intervaltype, $t) }} · {{ entry.identity.id }} · {{ $t('label.snapshot.policy.bulk.' + entry.status) }}</span></template>
          <a-descriptions v-if="entry.target && allowed" :column="1" size="small" bordered class="policy-bulk-summary">
            <a-descriptions-item :label="$t('label.volumeid')">{{ entry.identity.volumeid }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.snapshot.policy.delete.schedule')">{{ deletionSchedule(entry.target.policy, $t) }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.timezone')">{{ entry.target.policy.timezone }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.keep')">{{ entry.target.policy.maxsnaps }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.account')">{{ entry.target.volume.account || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.domain')">{{ entry.target.volume.domain || entry.target.volume.domainid || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.project')">{{ entry.target.volume.project || entry.target.volume.projectid || $t('label.snapshot.policy.delete.personal') }}</a-descriptions-item>
          </a-descriptions>
          <a-alert v-if="entry.error" type="error" show-icon :message="entry.error" />
        </a-collapse-panel>
      </a-collapse>
      <template v-if="!results">
        <a-alert class="policy-bulk-message" type="warning" show-icon :message="$t('message.snapshot.policy.delete.effect')" :description="$t('message.snapshot.policy.bulk.partial')" />
        <a-checkbox v-model:checked="acknowledged" :disabled="busy || needsReload || !allowed || !ready.length">{{ $t('message.snapshot.policy.bulk.ack', [ready.length]) }}</a-checkbox>
      </template>
    </a-spin>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t(attempted ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="results" :disabled="busy || !allowed" @click="reconcile">{{ $t('label.snapshot.policy.delete.check') }}</a-button>
      <a-button v-if="results && retained.length" :disabled="busy || !allowed" @click="prepareRetry">{{ $t('label.snapshot.policy.bulk.retry') }}</a-button>
      <a-button v-if="!results" :disabled="busy || !allowed" @click="load">{{ $t('label.snapshot.policy.delete.reload') }}</a-button>
      <a-button v-if="!results" type="primary" danger :loading="busy" :disabled="busy || !allowed || needsReload || !acknowledged || !ready.length" @click="submit">{{ $t('label.snapshot.policy.bulk.submit', [ready.length]) }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { getAPI } from '@/utils/storageApi'
import { postAPI } from '@/api'
import { canDeletePolicy, deletionIdentity, deletionFingerprint, deletionOwner, deletionSchedule, readPolicyDeletionTarget } from '@/utils/snapshotPolicyDeletion'
import { policyInterval } from '@/utils/snapshotPolicyPresentation'
export default {
  name: 'SnapshotPolicyBulkDeleteDialog',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () {
    const unique = new Map((this.currentAction.policyDeleteTargets || []).map(p => [p.id, p]))
    return { entries: [...unique.values()].map(p => ({ identity: deletionIdentity(p), target: null, fingerprint: '', status: 'loading', error: '' })), origin: '', busy: false, acknowledged: false, needsReload: true, error: '', results: false, attempted: false, accepted: false, lastIds: [], closed: false, disposed: false, reportedClose: false, version: 0 }
  },
  computed: {
    scope () { return JSON.stringify([this.$route.fullPath || this.$route.path, this.currentAction.policyDeleteTargets?.map(p => [p.id, p.volumeid, p.intervaltype, p.projectid]), this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, canDeletePolicy(this.$store.getters.apis)]) },
    allowed () { return this.current() && canDeletePolicy(this.$store.getters.apis) },
    ready () { return this.entries.filter(e => e.status === 'ready') },
    removed () { return this.entries.filter(e => e.status === 'removed') },
    retained () { return this.entries.filter(e => e.status === 'present') },
    unknown () { return this.entries.filter(e => ['unknown', 'absent', 'error'].includes(e.status)) },
    allRemoved () { return this.removed.length === this.entries.length }
  },
  created () { this.origin = this.scope; this.load() },
  watch: { scope () { this.version++; this.entries.forEach(e => { e.target = null; e.status = 'error' }); this.acknowledged = false; this.needsReload = true; this.error = this.$t('message.snapshot.policy.context.changed') } },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    deletionSchedule,
    policyInterval,
    current () { return !this.closed && !this.disposed && this.origin === this.scope },
    failure (error) { return error.message?.startsWith('message.') ? this.$t(error.message) : this.$t('message.request.failed') },
    close () { if (this.busy) return; this.closed = true; if (this.attempted) this.$emit('refresh-data') },
    finishClose () { if (this.disposed || !this.closed || this.reportedClose) return; this.reportedClose = true; this.$emit('close-action', this.origin === this.scope ? { removed: this.removed.map(e => e.identity.id), clearSelection: this.attempted } : undefined) },
    async load () {
      if (this.busy || !this.allowed || this.results) return
      this.busy = true; this.needsReload = true; this.acknowledged = false; this.error = ''
      const version = ++this.version; const current = () => this.allowed && this.version === version
      try {
        if (!this.entries.length) throw new Error('message.snapshot.policy.unavailable')
        const candidates = this.entries.filter(e => !['removed', 'unknown'].includes(e.status))
        const fetched = await Promise.all(candidates.map(e => readPolicyDeletionTarget(getAPI, e.identity, current, true)))
        if (!current()) return
        candidates.forEach((entry, i) => { const target = fetched[i]; entry.target = target.policy ? target : null; entry.fingerprint = target.policy ? deletionFingerprint(target) : ''; entry.status = target.policy ? 'ready' : 'absent'; entry.error = '' })
        this.needsReload = false
      } catch (error) { if (current()) this.error = this.failure(error) } finally { this.busy = false }
    },
    async prepareRetry () { if (this.busy || !this.allowed) return; this.results = false; this.needsReload = true; this.acknowledged = false; await this.load() },
    async reconcileEntries () {
      const version = ++this.version; const current = () => this.allowed && this.version === version
      const candidates = this.entries.filter(e => this.lastIds.includes(e.identity.id))
      await Promise.all(candidates.map(async entry => {
        try {
          const latest = await readPolicyDeletionTarget(getAPI, entry.identity, current, true)
          if (!current()) return
          if (deletionOwner(latest.volume) !== deletionOwner(entry.target.volume)) throw new Error('message.snapshot.policy.changed')
          entry.status = latest.policy ? 'present' : this.accepted ? 'removed' : 'unknown'; entry.error = ''
        } catch (error) { if (current()) { entry.status = 'error'; entry.error = this.failure(error) } }
      }))
    },
    async reconcile () { if (this.busy || !this.allowed || !this.results) return; this.busy = true; try { await this.reconcileEntries() } finally { this.busy = false } },
    async submit () {
      if (this.busy || !this.allowed || this.results || this.needsReload || !this.acknowledged || !this.ready.length) return
      const candidates = [...this.ready]; const version = ++this.version; const current = () => this.allowed && this.version === version
      this.busy = true; this.error = ''
      try {
        const fresh = await Promise.all(candidates.map(e => readPolicyDeletionTarget(getAPI, e.identity, current)))
        if (!current()) return
        if (fresh.some((target, i) => deletionFingerprint(target) !== candidates[i].fingerprint)) throw new Error('message.snapshot.policy.changed')
        this.attempted = true; this.accepted = false; this.lastIds = candidates.map(e => e.identity.id)
        try {
          const reply = await postAPI('deleteSnapshotPolicies', { ids: this.lastIds.join(',') }, { preserveOnFailure: true, timeout: 30000 })
          if (reply.deletesnapshotpoliciesresponse?.success !== true) throw new Error('message.snapshot.policy.delete.unknown')
          this.accepted = true
        } catch { if (this.allowed) this.error = this.$t('message.snapshot.policy.bulk.failed') }
        if (!this.allowed) return
        this.results = true; this.acknowledged = false
        await this.reconcileEntries()
        if (this.allowed && this.allRemoved) { this.$notification.success({ message: this.$t('label.snapshot.policy.bulk.delete'), description: this.$t('message.snapshot.policy.bulk.success', [this.removed.length]) }); this.busy = false; this.close() }
      } catch (error) { if (this.current()) { this.needsReload = true; this.acknowledged = false; this.error = this.failure(error) } } finally { this.busy = false }
    }
  }
}
</script>
<style scoped>
.policy-bulk-message { margin: 16px 0; }
.policy-bulk-identity, .policy-bulk-summary :deep(.ant-descriptions-item-content) { overflow-wrap: anywhere; }
</style>
