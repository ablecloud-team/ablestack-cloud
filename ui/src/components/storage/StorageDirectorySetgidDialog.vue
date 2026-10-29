<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <MoldDialog class="directory-setgid-dialog" :visible="true" :title="$t('label.storage.inheritance.group.settings')" size="lg" form-actions @cancel="close">
    <a-form layout="vertical">
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item :label="$t('label.storage.directory.path')">{{ row.path }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.inheritance.parent')">{{ parent.owneruid }}:{{ parent.ownergid }} / {{ parent.mode }} · inode {{ parent.inode }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.directory.revision')">{{ row.policyrevision }}</a-descriptions-item>
      </a-descriptions>
      <a-form-item class="spaced" :label="$t('label.storage.inheritance.group')">
        <a-switch v-model:checked="enabled" :disabled="busy || checking || guarded" />
        <span class="switch-label">{{ $t(enabled ? 'label.storage.inheritance.enabled' : 'label.storage.inheritance.disabled') }}</span>
      </a-form-item>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item :label="$t('label.storage.directory.after')">{{ parent.owneruid }}:{{ parent.ownergid }} / {{ afterMode }} · inode {{ parent.inode }}</a-descriptions-item>
      </a-descriptions>
      <a-alert class="spaced" type="info" show-icon :message="$t('message.storage.inheritance.setgid.only')" :description="$t('message.storage.inheritance.children')" />
      <StorageConfigurationPreflightPanel v-if="report" :report="report" :loading="checking" :acknowledged="ack" @refresh="check" @acknowledge="ack=$event" />
      <a-alert v-if="error" type="error" show-icon :message="error" />
    </a-form>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="checking || busy" :disabled="guarded || !dirty || (report && !applicable)" @click="report ? apply() : check()">{{ $t(report ? 'label.storage.preflight.apply' : 'label.storage.preflight.check') }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import { postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import StorageConfigurationPreflightPanel from '@/components/storage/StorageConfigurationPreflightPanel'
import { setgidEnabled, setgidParameters, setgidIntent } from '@/utils/smbParentInheritance'
import { decodeConfigurationPreflight, preflightApplicable, configurationPreflightFailure } from '@/utils/storageConfigPreflight'
export default {
  name: 'StorageDirectorySetgidDialog',
  components: { MoldDialog, MoldDialogActions, StorageConfigurationPreflightPanel },
  props: { row: { type: Object, required: true }, instance: { type: Object, required: true }, guarded: Boolean, execute: { type: Function, required: true } },
  emits: ['close', 'done'],
  data () { return { enabled: setgidEnabled(this.row.observation?.mode), report: null, ack: false, checking: false, busy: false, error: '', generation: 0, disposed: false, expiry: null } },
  computed: {
    parent () { return this.row.observation || {} },
    dirty () { return this.enabled !== setgidEnabled(this.parent.mode) },
    afterMode () { try { return setgidParameters(this.row, this.enabled).mode } catch { return '-' } },
    applicable () { return preflightApplicable(this.report, this.ack) }
  },
  watch: { enabled () { if (!this.busy) { this.generation++; this.checking = false; this.invalidate() } } },
  beforeUnmount () { this.disposed = true; this.generation++; clearTimeout(this.expiry) },
  methods: {
    invalidate () { clearTimeout(this.expiry); this.report = null; this.ack = false },
    close () { if (!this.busy) this.$emit('close') },
    async check () {
      if (this.guarded || this.busy || this.checking || !this.dirty) return
      const generation = ++this.generation; this.checking = true; this.error = ''; this.invalidate()
      try {
        const wire = await postAPI('preflightStorageServiceConfiguration', { instanceid: this.instance.id, intent: JSON.stringify(setgidIntent(this.row, this.enabled)) }, { timeout: 30000, preserveOnFailure: true })
        if (this.disposed || generation !== this.generation) return
        this.report = decodeConfigurationPreflight(wire)
        if (!this.report) throw new Error('Invalid directory preflight response')
        this.expiry = setTimeout(() => this.invalidate(), Math.max(0, Date.parse(this.report.expiresat) - Date.now()))
      } catch (error) {
        if (!this.disposed && generation === this.generation) { this.report = configurationPreflightFailure(error, this.instance.configurationrevision); this.error = error.message }
      } finally { if (generation === this.generation) this.checking = false }
    },
    async apply () {
      if (this.guarded || this.busy || !this.applicable) return
      const generation = this.generation; this.busy = true
      try {
        const result = await this.execute('updateStorageDirectoryPolicy', { instanceid: this.instance.id, ...setgidParameters(this.row, this.enabled) }, this.report)
        if (this.disposed || generation !== this.generation) return
        if (result?.state === 'COMMITTED') this.$emit('done')
        else { this.error = result?.message || result?.originalfailure?.code || result?.state || 'CONFIGURATION_OBSERVATION_UNAVAILABLE'; this.invalidate() }
      } catch (error) { if (!this.disposed && generation === this.generation) this.error = error.message } finally { if (generation === this.generation) this.busy = false }
    }
  }
}
</script>
<style scoped>
.spaced { margin-top: 16px; }
.switch-label { margin-left: 12px; color: var(--ui-text-secondary); }
:global(.directory-setgid-dialog .ant-btn-primary:not([disabled])) { background: #096dd9; border-color: #096dd9; color: #fff; }
</style>
