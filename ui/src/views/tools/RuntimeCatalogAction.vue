<!-- Licensed under the Apache License, Version 2.0. See the NOTICE file. -->
<template>
  <MoldDialog :visible="true" :title="$t('label.runtime.catalog.' + kind.toLowerCase())" size="lg" form-actions @cancel="close">
    <a-alert show-icon type="info" :message="$t('message.runtime.catalog.separate')" />
    <template v-if="kind === 'REGISTER'">
      <a-form layout="vertical">
        <a-form-item v-for="field in fields" :key="field" :label="$t('label.' + field)" :required="field !== 'artifactsize'">
          <a-input-number v-if="field === 'artifactsize'" v-model:value="form[field]" :min="1" :max="67108864" style="width:100%" />
          <a-select v-else-if="field === 'serviceimpact'" v-model:value="form[field]">
            <a-select-option v-for="value in ['NONE','PROTOCOL_RESTART','VM_REBOOT']" :key="value" :value="value">{{ value }}</a-select-option>
          </a-select>
          <a-input v-else v-model:value="form[field]" :aria-label="$t('label.' + field)" />
        </a-form-item>
      </a-form>
      <a-alert type="warning" show-icon :message="$t('message.runtime.catalog.identity')" />
    </template>
    <template v-else>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item :label="$t('label.version')">{{ resource.version }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">{{ resource.state }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.validationstatus')">{{ resource.validationstatus }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.referencecount')">{{ resource.referencecount }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="kind === 'REVOKE'" type="error" show-icon :message="$t('message.runtime.catalog.revoke')" />
      <a-alert v-if="kind === 'DELETE'" type="warning" show-icon :message="$t('message.runtime.catalog.delete')" />
      <a-form v-if="kind !== 'VALIDATE'" layout="vertical">
        <a-form-item :label="$t('label.reason')" required><a-textarea v-model:value="reason" :maxlength="1024" :rows="3" /></a-form-item>
      </a-form>
      <a-checkbox v-if="kind === 'REVOKE'" v-model:checked="acknowledged">{{ $t('message.runtime.catalog.ack') }}</a-checkbox>
    </template>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :disabled="!valid" :loading="busy" @click="submit">{{ $t('label.runtime.catalog.' + kind.toLowerCase()) }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import { postAPI } from '@/api'
import { creationRequestId } from '@/utils/sharedFsBacking'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
export default {
  name: 'RuntimeCatalogAction',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, default: () => ({}) }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      busy: false,
      error: '',
      reason: '',
      acknowledged: false,
      requestid: null,
      form: { version: '', signingkeyid: '', runtimeabiversion: '1', desiredstateschemaversion: '1', serviceimpact: 'NONE', artifacturl: '', manifesturl: '', signatureurl: '', artifactsize: undefined, sha256: '', manifestsha256: '' }
    }
  },
  computed: {
    kind () { return this.currentAction.catalogAction || 'REGISTER' },
    fields () { return ['version', 'signingkeyid', 'runtimeabiversion', 'desiredstateschemaversion', 'serviceimpact', 'artifacturl', 'manifesturl', 'signatureurl', 'artifactsize', 'sha256', 'manifestsha256'] },
    valid () {
      if (this.busy) return false
      if (this.kind === 'DELETE' && Number(this.resource.referencecount) !== 0) return false
      if (this.kind !== 'REGISTER') return this.kind === 'VALIDATE' || (!!this.reason.trim() && (this.kind !== 'REVOKE' || this.acknowledged))
      return this.fields.filter(x => x !== 'artifactsize').every(x => !!this.form[x]) &&
        ['sha256', 'manifestsha256'].every(x => /^[0-9a-fA-F]{64}$/.test(this.form[x])) &&
        ['version', 'signingkeyid', 'runtimeabiversion', 'desiredstateschemaversion'].every(x => /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(this.form[x])) &&
        ['artifacturl', 'manifesturl', 'signatureurl'].every(x => {
          try { const u = new URL(this.form[x]); return ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password && !u.hash } catch (_) { return false }
        })
    }
  },
  methods: {
    close () { if (!this.busy) this.$emit('close-action') },
    async submit () {
      if (!this.valid) return
      this.busy = true; this.error = ''
      try {
        if (this.kind === 'REGISTER') {
          await postAPI('registerStorageServiceRuntimeBundle', { ...this.form })
          this.$emit('refresh-data'); this.busy = false; this.close(); return
        }
        if (!this.requestid) this.requestid = creationRequestId('catalog')
        const api = this.kind === 'VALIDATE' ? 'validateStorageServiceRuntimeBundle' : 'transitionStorageServiceRuntimeBundle'
        const params = { bundleid: this.resource.id, requestid: this.requestid, expectedrevision: this.resource.revision }
        if (this.kind !== 'VALIDATE') Object.assign(params, { action: this.kind, reason: this.reason.trim() })
        const response = await postAPI(api, params)
        const job = response[api.toLowerCase() + 'response'].jobid
        this.$pollJob({
          jobId: job,
          title: this.$t('label.runtime.catalog.' + this.kind.toLowerCase()),
          catchMessage: this.$t('error.fetching.data'),
          successMethod: () => { this.busy = false; this.$emit('refresh-data'); this.close() },
          errorMethod: () => { this.busy = false; this.requestid = null; this.$emit('refresh-data') }
        })
      } catch (e) {
        this.busy = false; this.error = e?.response?.data?.errortext || e?.message || this.$t('error.fetching.data')
        this.$notifyError(e); this.$emit('refresh-data')
      }
    }
  }
}
</script>
<style scoped lang="less">
.ant-alert, .ant-descriptions { margin-bottom: 16px; }
:deep(.ant-form-item) { margin-bottom: 16px; }
</style>
