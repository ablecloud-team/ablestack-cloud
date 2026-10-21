<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License. -->
<template>
  <div class="form-layout offering-performance-edit" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading || saving">
      <div class="offering-target"><strong>{{ resource.name }}</strong><a-tag>{{ record.state || resource.state }}</a-tag></div>
      <a-alert v-if="readError" type="error" :message="readError" show-icon>
        <template #description><a-button @click="load">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <a-form v-if="loaded" ref="formRef" :model="form" layout="vertical" @finish="handleSubmit">
        <a-form-item name="name" :label="$t('label.name')" :rules="[{ required: true, whitespace: true, message: $t('message.error.required.input') }]">
          <a-input v-model:value="form.name" :disabled="saving || unknownResult" v-focus="true" />
        </a-form-item>
        <a-form-item name="displaytext" :label="$t('label.description')">
          <a-textarea v-model:value="form.displaytext" :maxlength="4096" :disabled="saving || unknownResult" />
        </a-form-item>
        <a-form-item v-if="canEditTags && apiParams.tags" name="tags" :label="$t('label.storagetags')">
          <a-select v-model:value="form.tags" mode="tags" :disabled="saving || unknownResult" />
        </a-form-item>
        <a-form-item v-if="apiParams.cachemode" name="cachemode" :label="$t('label.cachemode')">
          <a-select v-model:value="form.cachemode" :disabled="saving || unknownResult || shareable" data-cachemode>
            <a-select-option v-for="mode in cacheModes" :key="mode.value" :value="mode.value">{{ $t(mode.label) }}</a-select-option>
          </a-select>
          <p class="form-help">{{ $t(shareable ? 'message.offering.cache.shareable' : 'message.offering.cache.apply') }}</p>
        </a-form-item>
        <DiskQosFields ref="qosRef" :form="form" :api-params="apiParams" :current="original" :disabled="saving || unknownResult" />
        <p class="form-help">{{ $t('message.offering.qos.apply') }}</p>
        <details class="immutable-details">
          <summary>{{ $t('label.offering.immutable') }}</summary>
          <p>{{ $t('message.offering.immutable') }}</p>
          <p>{{ $t('label.storagetype') }}: {{ record.storagetype }} · {{ $t('label.provisioningtype') }}: {{ record.provisioningtype }}</p>
          <p>{{ $t('label.disksize') }}: {{ record.iscustomized ? $t('label.custom') : record.disksize + ' GiB' }}</p>
          <p>{{ $t('label.iscustomizeddiskiops') }}: {{ $t(record.iscustomizediops ? 'label.yes' : 'label.no') }} · {{ $t('label.diskiopsmin') }}: {{ record.miniops ?? '—' }} · {{ $t('label.diskiopsmax') }}: {{ record.maxiops ?? '—' }}</p>
        </details>
        <a-alert v-if="submitError" type="error" :message="submitError" show-icon />
        <a-alert v-if="unknownResult" type="warning" :message="$t('message.offering.update.unknown')" show-icon>
          <template #description><a-button @click="load">{{ $t('label.refresh') }}</a-button></template>
        </a-alert>
      </a-form>
      <div class="action-button">
        <a-button :disabled="saving" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="saving" :disabled="!loaded || loading || unknownResult || saving || !!qosValidation" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import DiskQosFields from '@/components/offering/DiskQosFields'
import { qosForm, qosParams, qosError, trueValue } from '@/utils/offeringQos'
export default {
  name: 'UpdateDiskOffering',
  components: { DiskQosFields },
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      form: {},
      original: {},
      record: {},
      loading: false,
      loaded: false,
      saving: false,
      unknownResult: false,
      readError: '',
      submitError: '',
      requestId: 0,
      canEditTags: isAdmin(),
      cacheModes: [
        { value: 'none', label: 'label.nodiskcache' }, { value: 'writeback', label: 'label.writeback' },
        { value: 'writethrough', label: 'label.writethrough' }, { value: 'hypervisor_default', label: 'label.hypervisor.default' }
      ]
    }
  },
  computed: {
    apiParams () { return Object.fromEntries((this.$store.getters.apis.updateDiskOffering?.params || []).map(param => [param.name, param])) },
    shareable () { return trueValue(this.record.shareable) },
    qosValidation () { return qosError(this.form, this.original) }
  },
  watch: { 'resource.id': { immediate: true, handler () { this.load() } } },
  beforeUnmount () { this.requestId++ },
  methods: {
    reason (error) { return error?.response?.data?.errortext || error?.response?.data?.errorresponse?.errortext || error?.message || this.$t('message.error') },
    async load () {
      const request = ++this.requestId
      this.loading = true
      this.loaded = false
      this.readError = ''
      try {
        const response = await getAPI('listDiskOfferings', { id: this.resource.id, listall: true, state: 'all' }, { preserveOnFailure: true })
        if (request !== this.requestId) return
        const record = response.listdiskofferingsresponse?.diskoffering?.find(offering => offering.id === this.resource.id)
        if (!record) throw new Error(this.$t('message.offering.not.found'))
        this.record = record
        this.original = { ...qosForm(record), name: record.name, displaytext: record.displaytext || '', tags: record.tags ? record.tags.split(',') : [], cachemode: record.cachemode?.toLowerCase() }
        this.form = { ...this.original, tags: [...this.original.tags] }
        this.loaded = true
        this.unknownResult = false
        this.submitError = ''
        if (!isAdmin() && this.$store.getters.apis.isAccountAllowedToCreateOfferingsWithTags) {
          const result = await getAPI('isAccountAllowedToCreateOfferingsWithTags', { id: this.$store.getters.userInfo.accountid }, { preserveOnFailure: true })
          if (request === this.requestId) this.canEditTags = trueValue(result.isaccountallowedtocreateofferingswithtagsresponse?.isallowed?.isallowed)
        }
      } catch (error) {
        if (request === this.requestId) this.readError = this.reason(error)
      } finally { if (request === this.requestId) this.loading = false }
    },
    async handleSubmit () {
      if (!this.loaded || this.loading || this.saving || this.unknownResult || this.qosValidation) return
      this.saving = true
      this.submitError = ''
      try {
        await this.$refs.formRef.validate()
        await this.$refs.qosRef.validate()
        const params = { id: this.resource.id }
        for (const field of ['name', 'displaytext', 'cachemode']) {
          if (this.apiParams[field] && this.form[field] !== this.original[field]) params[field] = this.form[field]
        }
        if (this.canEditTags && this.apiParams.tags && this.form.tags.join(',') !== this.original.tags.join(',')) params.tags = this.form.tags.join(',')
        const current = qosParams(this.form, this.apiParams)
        const original = qosParams(this.original, this.apiParams)
        for (const [key, value] of Object.entries(current)) { if (value !== original[key]) params[key] = value }
        if (Object.keys(params).length === 1) { this.$emit('close-action'); return }
        try { await postAPI('updateDiskOffering', params, { preserveOnFailure: true }) } catch (error) {
          if (!error.response) this.unknownResult = true
          throw error
        }
        this.$message.success(this.$t('message.offering.update.saved'))
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (error) {
        if (!error.errorFields) this.submitError = this.reason(error)
      } finally { this.saving = false }
    }
  }
}
</script>
<style scoped lang="scss">
.form-layout { width: 85vw; max-width: 620px; }
.offering-target { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; overflow-wrap: anywhere; }
.form-help { font-size: 12px; opacity: .8; margin-top: 8px; }
.immutable-details { margin: 20px 0; }
.immutable-details summary { cursor: pointer; }
.immutable-details p { margin: 8px 0 0; font-size: 12px; }
</style>
