<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for copyright ownership.
Licensed under the Apache License, Version 2.0 (the "License");
http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <MoldDialog
:visible="!closed"
:title="$t('label.delete.snapshot.policy')"
size="md"
form-actions
focus-guard
:closable="!busy"
:keyboard="!busy"
:after-close="finishClose"
@cancel="close">
    <a-spin :spinning="busy">
      <div v-if="target && allowed" class="policy-delete-target" data-testid="policy-delete-target">
        <strong>{{ target.volume.name }}</strong>
        <span>{{ $t('label.id') }}: {{ identity.id }}</span>
      </div>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-button class="policy-delete-reload" :disabled="busy || uncertain || !allowed" @click="load">{{ $t('label.snapshot.policy.delete.reload') }}</a-button>
      <template v-if="target">
        <a-descriptions :column="1" size="small" bordered class="policy-delete-summary">
          <a-descriptions-item :label="$t('label.volumeid')">{{ target.volume.id }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.intervaltype')">{{ $t('label.' + (intervals[Number(target.policy.intervaltype)] || 'unknown')) }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.snapshot.policy.delete.schedule')">{{ schedule }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.timezone')">{{ target.policy.timezone }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.keep')">{{ target.policy.maxsnaps }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.account')">{{ target.volume.account || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.domain')">{{ target.volume.domain || target.volume.domainid || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.project')">{{ target.volume.project || target.volume.projectid || $t('label.snapshot.policy.delete.personal') }}</a-descriptions-item>
        </a-descriptions>
        <a-alert class="policy-delete-effect" type="warning" show-icon :message="$t('message.snapshot.policy.delete.effect')" :description="$t('message.snapshot.policy.running.effect')" />
        <a-checkbox v-model:checked="acknowledged" :disabled="busy || !allowed || needsReload || uncertain">{{ $t('message.snapshot.policy.delete.ack') }}</a-checkbox>
      </template>
    </a-spin>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="target" type="primary" :danger="!uncertain" :loading="busy" :disabled="busy || !allowed || (!uncertain && (!acknowledged || needsReload))" @click="submit">{{ $t(uncertain ? 'label.snapshot.policy.delete.check' : 'label.delete') }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { getAPI } from '@/utils/storageApi'
import { postAPI } from '@/api'
import { canDeletePolicy, deletionIdentity, deletionFingerprint, deletionOwner, deletionSchedule, readPolicyDeletionTarget } from '@/utils/snapshotPolicyDeletion'
import { policyIntervals } from '@/utils/snapshotPolicyInput'

export default {
  name: 'SnapshotPolicyDeleteDialog',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () { return { identity: deletionIdentity(this.resource), target: null, busy: false, acknowledged: false, needsReload: true, uncertain: false, deleteAccepted: false, error: '', origin: '', closed: false, closeResult: null, reportedClose: false, disposed: false, version: 0, intervals: policyIntervals } },
  computed: {
    schedule () { return this.target ? deletionSchedule(this.target.policy, this.$t) : '' },
    scope () { return JSON.stringify([this.$route.path, this.resource.id, this.resource.volumeid, this.resource.intervaltype, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.state?.user?.token, this.$store.getters.project?.id, canDeletePolicy(this.$store.getters.apis)]) },
    allowed () { return this.current() && canDeletePolicy(this.$store.getters.apis) }
  },
  created () { this.origin = this.scope; this.load() },
  watch: { scope () { this.version++; this.target = null; this.acknowledged = false; this.needsReload = true; this.error = this.$t('message.snapshot.policy.context.changed') } },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    current () { return !this.closed && !this.disposed && this.origin === this.scope },
    failure (error) { return error.message?.startsWith('message.') ? this.$t(error.message) : Object.values(error.response?.data || {}).find(v => v?.errortext)?.errortext || error.message || this.$t('message.request.failed') },
    close () { if (this.busy) return; this.closed = true; if (this.uncertain) this.$emit('refresh-data') },
    finishClose () {
      if (this.disposed || !this.closed || this.reportedClose) return
      this.reportedClose = true
      this.$emit('close-action', this.origin === this.scope ? this.closeResult || undefined : undefined)
    },
    async load () {
      if (this.busy || !this.allowed || this.uncertain) return
      const version = ++this.version; this.busy = true; this.error = ''; this.acknowledged = false; this.needsReload = true
      try {
        const target = await readPolicyDeletionTarget(getAPI, this.identity, () => this.allowed && version === this.version)
        if (!this.allowed || version !== this.version) return
        this.target = target; this.needsReload = false
      } catch (error) { if (this.current()) { this.target = null; this.error = this.failure(error) } } finally { this.busy = false }
    },
    complete () { this.$notification.success({ message: this.$t('label.delete.snapshot.policy'), description: this.$t('message.success.delete.snapshot.policy') }); this.closeResult = { removed: [this.identity.id] }; this.closed = true; this.$emit('refresh-data', this.closeResult) },
    async reconcile () {
      const latest = await readPolicyDeletionTarget(getAPI, this.identity, () => this.allowed, true)
      if (!this.allowed) return
      if (deletionOwner(latest.volume) !== deletionOwner(this.target.volume)) throw new Error('message.snapshot.policy.changed')
      if (!latest.policy) {
        // User lists can also omit a hidden policy. Absence cannot replace a lost success response.
        if (this.deleteAccepted) this.complete()
        else this.error = this.$t('message.snapshot.policy.delete.unverified')
        return
      }
      this.uncertain = false; this.needsReload = true; this.acknowledged = false
      this.error = this.$t('message.snapshot.policy.delete.still.exists')
    },
    async submit () {
      if (this.busy || !this.allowed || !this.target || (!this.uncertain && (!this.acknowledged || this.needsReload))) return
      this.busy = true; this.error = ''
      try {
        if (this.uncertain) { await this.reconcile(); return }
        const latest = await readPolicyDeletionTarget(getAPI, this.identity, () => this.allowed)
        if (!this.allowed) return
        if (deletionFingerprint(latest) !== deletionFingerprint(this.target)) throw new Error('message.snapshot.policy.changed')
        this.deleteAccepted = false; this.uncertain = true
        try {
          const response = await postAPI('deleteSnapshotPolicies', { ids: this.identity.id }, { preserveOnFailure: true, timeout: 30000 })
          if (response.deletesnapshotpoliciesresponse?.success !== true) throw new Error('message.snapshot.policy.delete.unknown')
          this.deleteAccepted = true
        } catch (error) {
          if (error.response?.status >= 400 && error.response?.status < 500) this.uncertain = false
          throw error
        }
        if (this.allowed) await this.reconcile()
      } catch (error) {
        if (this.current()) { this.needsReload = true; this.acknowledged = false; this.error = this.uncertain ? this.$t('message.snapshot.policy.delete.unknown') : this.failure(error) }
      } finally { this.busy = false }
    }
  }
}
</script>
<style scoped>
.policy-delete-target { display: flex; flex-direction: column; gap: 4px; overflow-wrap: anywhere; }
.policy-delete-target span { color: var(--ui-text-secondary); font-size: 12px; }
.policy-delete-reload, .policy-delete-effect { margin: 16px 0; }
.policy-delete-summary :deep(.ant-descriptions-item-content) { overflow-wrap: anywhere; }
</style>
