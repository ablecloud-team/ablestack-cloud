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
  <MoldDialog
    class="sharedfs-standard-dialog"
    :visible="true"
    :title="$t('label.runtime.catalog.' + kind.toLowerCase())"
    size="md"
    :initial-focus="initialFocus"
    focus-guard
    form-actions
    @cancel="close">
    <template v-if="kind === 'REGISTER'">
      <a-alert show-icon type="info" :message="$t('message.runtime.catalog.separate')" />
      <a-form layout="vertical">
        <a-form-item
          v-for="field in fields"
          :key="field"
          :label="$t('label.' + field)"
          :html-for="'runtime-catalog-' + field"
          :extra="$t('message.runtime.catalog.register.field.' + field)"
          :validate-status="field === 'artifactsize' && artifactSizeInvalid ? 'error' : undefined"
          :help="field === 'artifactsize' && artifactSizeInvalid ? $t('message.runtime.catalog.invalid.artifactsize') : undefined"
          :required="field !== 'artifactsize'">
          <a-input
            v-if="field === 'artifactsize'"
            id="runtime-catalog-artifactsize"
            v-model:value="form.artifactsize"
            :aria-invalid="artifactSizeInvalid"
            inputmode="numeric" />
          <a-select v-else-if="field === 'serviceimpact'" :id="'runtime-catalog-' + field" v-model:value="form[field]">
            <a-select-option v-for="value in ['NONE', 'PROTOCOL_RESTART', 'VM_REBOOT']" :key="value" :value="value">
              {{ $t('label.runtime.catalog.serviceimpact.' + value) }}
            </a-select-option>
          </a-select>
          <a-input v-else :id="'runtime-catalog-' + field" v-model:value="form[field]" :aria-label="$t('label.' + field)" />
        </a-form-item>
      </a-form>
      <a-alert type="warning" show-icon :message="$t('message.runtime.catalog.identity')" />
    </template>
    <template v-else>
      <a-descriptions :column="1" bordered size="small">
        <a-descriptions-item :label="$t('label.version')">{{ resource.version }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')"><RuntimeCatalogValue group="state" :value="resource.state" /></a-descriptions-item>
        <a-descriptions-item :label="$t('label.validationstatus')"><RuntimeCatalogValue group="validationstatus" :value="resource.validationstatus" /></a-descriptions-item>
        <a-descriptions-item :label="$t('label.referencecount')">{{ resource.referencecount }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="kind === 'REVOKE'" type="error" show-icon :message="$t('message.runtime.catalog.revoke')" />
      <a-alert v-if="kind === 'DELETE'" type="warning" show-icon :message="$t('message.runtime.catalog.delete')" />
      <a-form v-if="kind !== 'VALIDATE'" layout="vertical">
        <a-form-item :label="$t('label.reason')" html-for="runtime-catalog-reason" required>
          <a-textarea id="runtime-catalog-reason" v-model:value="reason" :maxlength="1024" :rows="3" />
        </a-form-item>
      </a-form>
      <a-checkbox v-if="kind === 'REVOKE'" v-model:checked="acknowledged">{{ $t('message.runtime.catalog.ack') }}</a-checkbox>
    </template>
    <a-alert v-if="error" type="error" show-icon>
      <template #message><RuntimeCatalogValue group="errorcode" :value="errorCode || error" show-code /></template>
      <template v-if="errorCode && errorCode !== error" #description><span>{{ error }}</span></template>
    </a-alert>
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
import RuntimeCatalogValue from '@/components/view/RuntimeCatalogValue'
import { runtimeCatalogTranslationKey } from '@/utils/runtimeCatalog'

const maximumBundleBytes = 64 * 1024 * 1024

export default {
  name: 'RuntimeCatalogAction',
  components: { MoldDialog, MoldDialogActions, RuntimeCatalogValue },
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
    errorCode () {
      return (this.error.match(/[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+/g) || [])
        .find(code => runtimeCatalogTranslationKey(this.$i18n, 'errorcode', code))
    },
    initialFocus () { return this.kind === 'REGISTER' ? '#runtime-catalog-version' : this.kind === 'VALIDATE' ? '.ant-modal-close' : '#runtime-catalog-reason' },
    fields () { return ['version', 'signingkeyid', 'runtimeabiversion', 'desiredstateschemaversion', 'serviceimpact', 'artifacturl', 'manifesturl', 'signatureurl', 'artifactsize', 'sha256', 'manifestsha256'] },
    artifactSize () {
      const value = String(this.form.artifactsize ?? '').trim()
      if (!value) return null
      return /^\d+$/.test(value) ? Number(value) : NaN
    },
    artifactSizeInvalid () {
      const size = this.artifactSize
      return size !== null && (!Number.isInteger(size) || size < 1 || size > maximumBundleBytes)
    },
    valid () {
      if (this.busy) return false
      if (this.kind === 'DELETE' && Number(this.resource.referencecount) !== 0) return false
      if (this.kind !== 'REGISTER') return this.kind === 'VALIDATE' || (!!this.reason.trim() && (this.kind !== 'REVOKE' || this.acknowledged))
      return !this.artifactSizeInvalid && this.fields.filter(x => x !== 'artifactsize').every(x => !!this.form[x]) &&
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
          const { artifactsize, ...params } = this.form
          if (this.artifactSize !== null) params.artifactsize = this.artifactSize
          await postAPI('registerStorageServiceRuntimeBundle', params)
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
          action: { label: 'label.runtime.catalog.' + this.kind.toLowerCase(), suppressErrorNotification: true },
          errorMethod: result => {
            this.busy = false
            this.requestid = null
            this.error = result?.jobresult?.errortext || this.$t('message.request.failed')
            this.$emit('refresh-data')
          }
        })
      } catch (e) {
        const body = e?.response?.data || {}
        this.busy = false
        this.error = body.errortext || Object.values(body).find(value => value?.errortext)?.errortext || e?.message || this.$t('message.request.failed')
        this.$emit('refresh-data')
      }
    }
  }
}
</script>
<style scoped lang="less">
.ant-alert, .ant-descriptions { margin-bottom: 16px; }
:deep(.ant-form-item) { margin-bottom: 16px; }
</style>
