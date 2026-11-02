<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <MoldDialog :visible="true" :title="$t('label.storage.client.policy')" size="lg" form-actions @cancel="close">
    <StorageConfigurationOperationPanel v-if="operation" :operation="operation" @refresh="$emit('refresh')" @resume="$emit('resume',$event)" />
    <a-form layout="vertical">
      <a-descriptions bordered size="small" :column="2" class="spaced">
        <a-descriptions-item :label="$t('label.storage.service.smb.share.name')">{{ row.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.client.saved')">{{ baseline.length ? baseline.join(', ') : $t('label.storage.client.any') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.client.observation')">{{ $t('label.storage.client.' + (row.clientaccess?.status || 'UNKNOWN')) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.creation.existing')">{{ $t('message.storage.client.preserve') }}</a-descriptions-item>
      </a-descriptions>
      <SmbClientAccessFields class="spaced" v-model:value="form" :require-confirmation="baseline.length>0" :disabled="guarded || busy || checking" />
      <a-alert v-if="validationError" class="spaced" type="error" show-icon :message="reason(validationError)" />
      <a-descriptions v-if="report?.clientaccesspreview" bordered size="small" :column="1" class="spaced">
        <a-descriptions-item :label="$t('label.storage.client.after')">{{ previewAfter.length ? previewAfter.join(', ') : $t('label.storage.client.any') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.client.observation')">{{ report.clientaccesspreview.current?.status || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.client.scope')">{{ $t('message.storage.client.sessions') }}</a-descriptions-item>
      </a-descriptions>
      <a-descriptions v-if="profileRows.length" bordered size="small" :column="1" class="spaced">
        <a-descriptions-item v-for="profile in profileRows" :key="profile.port" :label="$t('label.storage.client.observation') + ' · ' + profile.port">{{ $t('label.storage.client.' + profile.status) }}</a-descriptions-item>
      </a-descriptions>
      <StorageConfigurationPreflightPanel v-if="report" class="spaced" :report="report" :loading="checking" :acknowledged="ack" @refresh="check" @acknowledge="ack=$event" />
      <a-alert v-if="error" class="spaced" type="error" show-icon :message="error" />
    </a-form>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy || checking" :disabled="guarded || !dirty || !!validationError || (report && !applicable)" @click="report ? apply() : check()">{{ $t(report ? 'label.storage.preflight.apply' : 'label.storage.preflight.check') }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import { postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import StorageConfigurationPreflightPanel from '@/components/storage/StorageConfigurationPreflightPanel'
import StorageConfigurationOperationPanel from '@/components/storage/StorageConfigurationOperationPanel'
import SmbClientAccessFields from '@/components/storage/SmbClientAccessFields'
import { smbClientSources, clientAccessForm, clientAccessParameters, clientAccessIntent } from '@/utils/smbClientAccess'
import { decodeConfigurationPreflight, preflightApplicable, configurationPreflightFailure } from '@/utils/storageConfigPreflight'
export default {
  name: 'SmbClientAccessDialog',
  components: { MoldDialog, MoldDialogActions, StorageConfigurationPreflightPanel, StorageConfigurationOperationPanel, SmbClientAccessFields },
  props: { row: { type: Object, required: true }, instance: { type: Object, required: true }, guarded: Boolean, operation: Object, execute: { type: Function, required: true } },
  emits: ['close', 'done', 'refresh', 'resume'],
  data () {
    let baseline = []; let form = { mode: 'ANY_SOURCE', input: '', confirmed: false }; let initialError = ''
    try { baseline = smbClientSources(this.row.config); form = clientAccessForm(this.row.config) } catch (e) { initialError = e.message }
    return { baseline, form, initialError, report: null, ack: false, busy: false, checking: false, error: '', generation: 0, disposed: false, expiry: null }
  },
  computed: {
    validationError () { if (this.initialError) return this.initialError; try { clientAccessParameters(this.baseline, this.form); return '' } catch (e) { return e.message } },
    dirty () { if (this.validationError) return false; return Object.keys(clientAccessParameters(this.baseline, this.form)).length > 0 },
    applicable () { return !this.validationError && preflightApplicable(this.report, this.ack) },
    profileRows () { return this.report?.clientaccesspreview?.current?.profiles || this.row.clientaccess?.profiles || [] },
    previewAfter () { return this.report?.clientaccesspreview?.after || [] }
  },
  watch: { form: { deep: true, handler () { if (!this.busy) { this.generation++; this.checking = false; this.invalidate() } } } },
  beforeUnmount () { this.disposed = true; this.generation++; clearTimeout(this.expiry) },
  methods: {
    reason (code) { return code?.startsWith('SMB_CLIENT_ACCESS_') ? this.$t('message.storage.client.' + code) : code },
    invalidate () { clearTimeout(this.expiry); this.report = null; this.ack = false },
    close () { if (!this.busy) this.$emit('close') },
    parameters () { return { id: this.row.id, ...clientAccessParameters(this.baseline, this.form) } },
    async check () {
      if (this.guarded || this.busy || this.checking || !this.dirty || this.validationError) return
      const generation = ++this.generation; this.checking = true; this.error = ''; this.invalidate()
      try {
        const intent = { api: 'updateStorageSmbShare', protocol: 'SMB', resourceid: this.row.id, ...clientAccessIntent(this.parameters()) }
        const wire = await postAPI('preflightStorageServiceConfiguration', { instanceid: this.instance.id, intent: JSON.stringify(intent) }, { timeout: 30000, preserveOnFailure: true })
        if (this.disposed || generation !== this.generation) return
        this.report = decodeConfigurationPreflight(wire)
        if (!this.report) throw new Error('SMB_CLIENT_ACCESS_OBSERVATION_UNAVAILABLE')
        const failure = this.report.checks.find(check => check.blocking)
        if (failure) this.error = this.reason(failure.code)
        if (this.report.status === 'READY') this.expiry = setTimeout(() => this.invalidate(), Math.max(0, Date.parse(this.report.expiresat) - Date.now()))
      } catch (e) {
        if (!this.disposed && generation === this.generation) { this.report = configurationPreflightFailure(e, this.instance.configurationrevision); this.error = this.reason(e.message) }
      } finally { if (generation === this.generation) this.checking = false }
    },
    async apply () {
      if (this.guarded || this.busy || !this.applicable || !this.dirty) return
      const generation = this.generation; this.busy = true
      try {
        const result = await this.execute('updateStorageSmbShare', this.parameters(), this.report)
        if (this.disposed || generation !== this.generation) return
        if (result?.state === 'COMMITTED') this.$emit('done')
        else { this.error = this.reason(result?.originalfailure?.code || result?.state || 'SMB_CLIENT_ACCESS_OBSERVATION_UNAVAILABLE'); this.invalidate() }
      } catch (e) { if (!this.disposed && generation === this.generation) this.error = this.reason(e.message) } finally { if (generation === this.generation) this.busy = false }
    }
  }
}
</script>
<style scoped>
.spaced { margin-top: 16px; }
</style>
