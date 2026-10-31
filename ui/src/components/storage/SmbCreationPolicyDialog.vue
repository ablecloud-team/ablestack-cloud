<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <MoldDialog :visible="true" :title="$t('label.storage.creation.settings')" size="lg" form-actions class="smb-creation-dialog" @cancel="close">
    <StorageConfigurationOperationPanel v-if="operation" :operation="operation" @refresh="$emit('refresh')" @resume="$emit('resume', $event)" />
    <a-form layout="vertical">
      <a-descriptions bordered size="small" :column="2">
        <a-descriptions-item :label="$t('label.storage.service.smb.share.name')">{{ row.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.creation.scope')">{{ $t('message.storage.creation.new.only') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.creation.existing')">{{ $t('message.storage.creation.preserve') }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.creation.apply.mode')">{{ $t('label.storage.preflight.ONLINE_RELOAD') }}</a-descriptions-item>
      </a-descriptions>
      <p class="help">{{ $t('message.storage.creation.input') }}</p>
      <a-row :gutter="16">
        <a-col v-for="key in fieldKeys" :key="key" :xs="24" :sm="12">
          <a-form-item :label="$t('label.storage.creation.' + key)">
            <a-input v-model:value="form[key]" :aria-label="$t('label.storage.creation.' + key)" :maxlength="5" spellcheck="false" :disabled="busy || checking || guarded" />
            <span class="help">{{ $t('label.storage.creation.current') }} {{ baseline[key] }}</span>
          </a-form-item>
        </a-col>
      </a-row>
      <a-alert v-if="validationError" type="error" show-icon :message="reason(validationError)" />
      <a-alert v-if="executable" class="spaced" type="warning" show-icon :message="$t('message.storage.creation.execute')" />
      <a-alert class="spaced" type="info" show-icon :message="$t('message.storage.creation.acl')" />
      <a-descriptions v-if="preview" bordered size="small" :column="2" class="spaced">
        <a-descriptions-item :label="$t('label.storage.inheritance.parent')">{{ preview.parent?.owneruid }}:{{ preview.parent?.ownergid }} / {{ preview.parent?.mode }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.inheritance.new.uid')">{{ $t((preview.after?.inheritOwner ?? preview.after?.inheritowner) ? 'label.storage.inheritance.parent.uid' : 'label.storage.inheritance.creator.uid') }}</a-descriptions-item>
        <a-descriptions-item v-for="key in fieldKeys" :key="key" :label="$t('label.storage.creation.' + key)">{{ preview.before?.[key] ?? preview.before?.[key.toLowerCase()] }} → {{ preview.after?.[key] ?? preview.after?.[key.toLowerCase()] }}</a-descriptions-item>
      </a-descriptions>
      <a-descriptions v-if="preview?.runtime?.length" bordered size="small" :column="1" class="spaced">
        <a-descriptions-item v-for="runtime in preview.runtime" :key="runtime.config" :label="$t('label.storage.creation.effective') + ' · ' + (runtime.ports || '-')">{{ runtime.createMask ?? runtime.createmask }} / {{ runtime.directoryMask ?? runtime.directorymask }} · {{ runtime.forceCreateMode ?? runtime.forcecreatemode }} / {{ runtime.forceDirectoryMode ?? runtime.forcedirectorymode }}</a-descriptions-item>
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
import { smbModeDefaults, smbCreationModes, smbCreationDelta, smbCreationIntent } from '@/utils/smbCreationPolicy'
import { decodeConfigurationPreflight, preflightApplicable, configurationPreflightFailure } from '@/utils/storageConfigPreflight'
export default {
  name: 'SmbCreationPolicyDialog',
  components: { MoldDialog, MoldDialogActions, StorageConfigurationPreflightPanel, StorageConfigurationOperationPanel },
  props: { row: { type: Object, required: true }, instance: { type: Object, required: true }, guarded: Boolean, operation: Object, execute: { type: Function, required: true } },
  emits: ['close', 'done', 'refresh', 'resume'],
  data () {
    let baseline; let initialError = ''
    try { baseline = smbCreationModes(this.row.config) } catch (error) { baseline = { ...smbModeDefaults }; initialError = error.message }
    return { baseline, form: { ...baseline }, initialError, fieldKeys: ['createMask', 'forceCreateMode', 'directoryMask', 'forceDirectoryMode'], report: null, ack: false, busy: false, checking: false, error: '', generation: 0, disposed: false, expiry: null }
  },
  computed: {
    validationError () { if (this.initialError) return this.initialError; try { smbCreationModes(this.form); return '' } catch (error) { return error.message } },
    dirty () { if (this.validationError) return false; return Object.keys(smbCreationDelta(this.baseline, this.form)).length > 0 },
    applicable () { return !this.validationError && preflightApplicable(this.report, this.ack) },
    preview () { return this.report?.creationpreview },
    executable () { return !this.validationError && !!(parseInt(this.form.createMask, 8) & 0o111) }
  },
  watch: { form: { deep: true, handler () { if (!this.busy) { this.generation++; this.checking = false; this.invalidate() } } } },
  beforeUnmount () { this.disposed = true; this.generation++; clearTimeout(this.expiry) },
  methods: {
    reason (code) {
      const known = ['SMB_CREATION_MODE_INVALID', 'SMB_FORCE_MODE_OUTSIDE_MASK', 'SMB_CREATION_POLICY_INHERIT_CONFLICT', 'SMB_CREATION_POLICY_PROTECTION_REQUIRED', 'SMB_CREATION_POLICY_CAPABILITY_REQUIRED', 'SMB_CREATION_POLICY_DRIFT']
      return known.includes(code) ? this.$t('message.storage.creation.' + code) : code
    },
    invalidate () { clearTimeout(this.expiry); this.report = null; this.ack = false },
    close () { if (!this.busy) this.$emit('close') },
    async check () {
      if (this.guarded || this.busy || this.checking || !this.dirty || this.validationError) return
      const generation = ++this.generation; this.checking = true; this.error = ''; this.invalidate()
      try {
        const wire = await postAPI('preflightStorageServiceConfiguration', { instanceid: this.instance.id, intent: JSON.stringify(smbCreationIntent(this.row, this.form)) }, { timeout: 30000, preserveOnFailure: true })
        if (this.disposed || generation !== this.generation) return
        this.report = decodeConfigurationPreflight(wire)
        if (!this.report) throw new Error('Invalid SMB creation preflight response')
        const failure = this.report.checks.find(check => check.blocking)
        if (failure) this.error = this.reason(failure.code)
        if (this.report.status === 'READY') this.expiry = setTimeout(() => this.invalidate(), Math.max(0, Date.parse(this.report.expiresat) - Date.now()))
      } catch (error) {
        if (!this.disposed && generation === this.generation) { this.report = configurationPreflightFailure(error, this.instance.configurationrevision); this.error = error.message }
      } finally { if (generation === this.generation) this.checking = false }
    },
    async apply () {
      if (this.guarded || this.busy || !this.applicable || !this.dirty) return
      const generation = this.generation; this.busy = true
      try {
        const result = await this.execute('updateStorageSmbShare', { id: this.row.id, ...smbCreationDelta(this.baseline, this.form) }, this.report)
        if (this.disposed || generation !== this.generation) return
        if (result?.state === 'COMMITTED') this.$emit('done')
        else { this.error = this.reason(result?.originalfailure?.code || result?.state || 'CONFIGURATION_OBSERVATION_UNAVAILABLE'); this.invalidate() }
      } catch (error) { if (!this.disposed && generation === this.generation) this.error = error.message } finally { if (generation === this.generation) this.busy = false }
    }
  }
}
</script>
<style scoped>
.spaced { margin-top: 16px; }
.help { color: var(--ui-text-secondary); font-size: 12px; line-height: 1.7; }
p.help { margin: 12px 0; }
</style>
