<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for copyright ownership.
Licensed under the Apache License, Version 2.0 (the "License");
http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <MoldDialog
:visible="!closed"
:title="$t(title)"
:size="isEdit ? 'lg' : 'md'"
form-actions
focus-guard
:closable="!busy"
:keyboard="!busy"
@cancel="close">
    <a-spin :spinning="loading">
      <div class="policy-target" data-testid="policy-action-target">
        <strong>{{ volume?.name || resource.volumename || resource.volumeid }}</strong>
        <span>{{ $t('label.' + interval) }} · {{ resource.id }}</span>
      </div>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-button class="policy-reload" :disabled="busy || !!jobId || !current()" @click="load">{{ $t(policy ? 'label.snapshot.policy.reload.values' : 'label.retry') }}</a-button>
      <FormSchedule
v-if="isEdit && policy && volume"
:key="loadVersion"
:resource="volume"
:policy="policy"
:validate-edit="validateEdit"
:data-source="[policy]"
resource-type="Volume"
:disabled="!allowed || loading || !!error"
@busy="formBusy = $event"
@refresh="completed"
@close-action="close" />
      <template v-if="!isEdit && policy">
        <a-descriptions :column="1" size="small" bordered>
          <a-descriptions-item :label="$t('label.time')">{{ schedule(policy) }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.timezone')">{{ policy.timezone }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.keep')">{{ policy.maxsnaps }}</a-descriptions-item>
        </a-descriptions>
        <a-alert class="policy-effect" type="warning" show-icon :message="$t(resume ? 'message.snapshot.policy.resume.effect' : 'message.snapshot.policy.pause.effect')" :description="$t('message.snapshot.policy.running.effect')" />
        <a-checkbox v-model:checked="acknowledged" :disabled="busy || !allowed || !!jobId">{{ $t('message.snapshot.policy.scheduling.ack') }}</a-checkbox>
      </template>
      <p v-if="jobId" role="status">{{ $t('message.snapshot.policy.job.pending') }} {{ jobId }}</p>
    </a-spin>
    <MoldDialogActions v-if="!isEdit || !policy" :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="!isEdit && policy" type="primary" :loading="busy" :disabled="busy || !allowed || (!jobId && (!acknowledged || !!error))" @click="submit">{{ $t(jobId ? 'label.snapshot.policy.check.job' : title) }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import FormSchedule from './FormSchedule'
import { getAPI } from '@/utils/storageApi'
import { postAPI } from '@/api'
import { canEditPolicy, canManagePolicy, freshPolicy, policyFingerprint, policyJobId } from '@/utils/snapshotPolicyEditing'
import { policyIntervals as intervals } from '@/utils/snapshotPolicyInput'
import { policySchedule } from '@/utils/snapshotPolicyPresentation'

export default {
  name: 'SnapshotPolicyActionDialog',
  components: { MoldDialog, MoldDialogActions, FormSchedule },
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () { return { policy: null, volume: null, loading: false, formBusy: false, submitting: false, closed: false, disposed: false, error: '', loadVersion: 0, acknowledged: false, jobId: '', origin: '' } },
  computed: {
    isEdit () { return this.currentAction.policyMode === 'edit' },
    resume () { return this.currentAction.policyMode === 'resume' },
    title () { return this.isEdit ? 'label.snapshot.policy.edit' : this.resume ? 'label.snapshot.policy.resume' : 'label.snapshot.policy.pause' },
    interval () { return intervals[Number(this.resource.intervaltype)] || 'unknown' },
    busy () { return this.loading || this.submitting || this.formBusy },
    scope () {
      return JSON.stringify([this.$route.path, this.resource.id, this.resource.volumeid, this.resource.intervaltype, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.project?.id,
        ...['createSnapshotPolicy', 'updateSnapshotPolicy', 'listSnapshotPolicies', 'listVolumes', 'queryAsyncJobResult'].map(api => api in (this.$store.getters.apis || {}))])
    },
    allowed () { return this.current() && (this.isEdit ? canEditPolicy(this.$store.getters.apis || {}) : canManagePolicy(this.$store.getters)) }
  },
  created () { this.origin = this.scope; this.load() },
  watch: { scope () { this.loadVersion++; this.policy = null; this.volume = null; this.loading = false; this.error = this.$t('message.snapshot.policy.context.changed') } },
  beforeUnmount () { this.disposed = true; this.loadVersion++ },
  methods: {
    schedule (policy) { return policySchedule(policy, this.$t) },
    current () { return !this.disposed && !this.closed && this.origin === this.scope },
    failure (error) { return error.message?.startsWith('message.') ? this.$t(error.message) : Object.values(error.response?.data || {}).find(v => v?.errortext)?.errortext || error.message || this.$t('message.request.failed') },
    async load () {
      if (this.busy || !this.allowed || this.jobId) return
      const version = ++this.loadVersion; this.loading = true; this.error = ''; this.acknowledged = false
      // An explicit reload discards the draft. Mount the replacement form only
      // after its policy and volume have both arrived, never from old props.
      this.policy = null; this.volume = null
      const current = () => this.current() && version === this.loadVersion && this.allowed
      try {
        const policy = await freshPolicy(getAPI, this.resource, current)
        const json = await getAPI('listVolumes', { id: policy.volumeid, listall: true }, { timeout: 20000 })
        if (!current()) return
        const volume = json.listvolumesresponse?.volume?.find(v => v.id === policy.volumeid)
        if (!volume) throw new Error('message.snapshot.policy.unavailable')
        if (!this.isEdit && typeof policy.fordisplay !== 'boolean') throw new Error('message.snapshot.policy.unavailable')
        this.policy = policy; this.volume = volume
      } catch (error) { if (current()) { this.error = this.failure(error); this.policy = null; this.volume = null } } finally { if (version === this.loadVersion) this.loading = false }
    },
    async validateEdit () {
      if (!this.allowed) throw new Error(this.$t('message.snapshot.policy.context.changed'))
      const latest = await freshPolicy(getAPI, this.resource, () => this.allowed)
      if (policyFingerprint(latest) !== policyFingerprint(this.policy)) throw new Error(this.$t('message.snapshot.policy.changed'))
      const json = await getAPI('listVolumes', { id: latest.volumeid, listall: true }, { timeout: 20000 })
      if (!this.allowed) throw new Error(this.$t('message.snapshot.policy.context.changed'))
      const volume = json.listvolumesresponse?.volume?.find(v => v.id === latest.volumeid)
      const owner = v => JSON.stringify([v?.id, v?.account, v?.domainid, v?.projectid, v?.zoneid])
      if (!volume || owner(volume) !== owner(this.volume)) throw new Error(this.$t('message.snapshot.policy.changed'))
    },
    completed () { this.$emit('refresh-data'); if (this.isEdit) { this.formBusy = false; this.closed = true; this.$emit('close-action') } },
    close () { if (this.busy) return; this.closed = true; this.$emit('close-action') },
    async submit () {
      if (this.busy || !this.allowed || (!this.jobId && (!this.acknowledged || this.error))) return
      this.submitting = true; this.error = ''
      try {
        if (!this.jobId) {
          const latest = await freshPolicy(getAPI, this.resource, () => this.allowed)
          if (!this.allowed) return
          if (latest.fordisplay === this.resume) { this.completed(); this.submitting = false; this.close(); return }
          if (policyFingerprint(latest) !== policyFingerprint(this.policy)) throw new Error('message.snapshot.policy.changed')
          // Retain the client job ID on an ambiguous submission; retry only queries its state.
          this.jobId = policyJobId()
          try {
            const json = await postAPI('updateSnapshotPolicy', { id: latest.id, fordisplay: this.resume, customjobid: this.jobId }, { preserveOnFailure: true })
            this.jobId = json.updatesnapshotpolicyresponse?.jobid || this.jobId
          } catch (error) {
            if (error.response?.status >= 400 && error.response?.status < 500) this.jobId = ''
            throw error
          }
        }
        for (let i = 0; i < 80 && this.allowed; i++) {
          const json = await getAPI('queryAsyncJobResult', { jobid: this.jobId }, { timeout: 15000, backgroundJob: true })
          if (!this.allowed) return
          const job = json.queryasyncjobresultresponse
          if (job?.jobstatus === 2) { this.jobId = ''; throw new Error(job.jobresult?.errortext || this.$t('message.request.failed')) }
          if (job?.jobstatus === 1) {
            const latest = await freshPolicy(getAPI, this.resource, () => this.allowed)
            if (latest.fordisplay !== this.resume) throw new Error('message.snapshot.policy.changed')
            this.jobId = ''; this.$notification.success({ message: this.$t(this.title), description: this.$t(this.resume ? 'message.snapshot.policy.resumed' : 'message.snapshot.policy.paused') }); this.completed(); this.submitting = false; this.close(); return
          }
          if (job?.jobstatus !== 0) throw new Error('message.snapshot.policy.job.unknown')
          await new Promise(resolve => setTimeout(resolve, 1500))
        }
        throw new Error('message.snapshot.policy.job.unknown')
      } catch (error) { if (this.current()) this.error = this.failure(error) } finally { this.submitting = false }
    }
  }
}
</script>
<style scoped>
.policy-target { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; overflow-wrap: anywhere; }
.policy-target span { color: var(--ui-text-secondary); font-size: 12px; }
.policy-reload { margin: 12px 0; }
.policy-effect { margin: 16px 0; }
</style>
