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
  <div class="volume-operation-form" v-ctrl-enter="submit">
    <a-spin :spinning="loading || saving">
      <div class="volume-operation-target"><strong>{{ resource.name }}</strong><a-tag>{{ record.state }}</a-tag></div>
      <a-alert v-if="readError" type="error" :message="readError" show-icon />
      <a-button v-if="readError" class="operation-retry" @click="load">{{ $t('label.retry') }}</a-button>
      <a-form v-if="loaded" ref="formRef" :model="form" layout="vertical">
        <a-form-item name="name" :label="$t('label.name')" :rules="[{ required: true, whitespace: true, message: $t('message.error.required.input') }]">
          <a-input v-model:value="form.name" :disabled="managementDisabled" v-focus="true" />
        </a-form-item>
        <a-form-item :label="$t('label.description')"><a-textarea v-model:value="form.displaytext" :maxlength="4096" :disabled="managementDisabled" /></a-form-item>
        <a-form-item v-for="key in tagFields" :key="key" :label="$t('label.' + key)">
          <a-input v-model:value="form[key]" :disabled="managementDisabled" />
        </a-form-item>
        <a-form-item v-if="managementFields.purgeresources" :label="$t('label.purgeresources')">
          <a-switch v-model:checked="form.purgeresources" :disabled="managementDisabled" :aria-label="$t('label.purgeresources')" />
          <p class="operation-help">{{ $t('message.storage.management.purge') }}</p>
        </a-form-item>
        <details v-if="managementFields.externaldetails || managementFields.cleanupexternaldetails" class="operation-options">
          <summary>{{ $t('label.externaldetails') }} ({{ Object.keys(currentExternal).length }})</summary>
          <p class="operation-help">{{ $t('message.storage.management.external') }}</p>
          <object-list-table v-if="Object.keys(currentExternal).length" :data-map="currentExternal" />
          <a-radio-group v-model:value="form.externalMode" :disabled="managementDisabled">
            <a-radio value="preserve">{{ $t('label.storage.management.preserve') }}</a-radio>
            <a-radio v-if="managementFields.externaldetails" value="edit">{{ $t('label.edit') }}</a-radio>
            <a-radio v-if="managementFields.cleanupexternaldetails && Object.keys(currentExternal).length" value="clear">{{ $t('label.storage.management.clear') }}</a-radio>
          </a-radio-group>
          <details-input v-if="form.externalMode === 'edit' && !managementDisabled" v-model:value="form.external" />
          <a-checkbox v-if="form.externalMode === 'clear'" v-model:checked="form.confirmClear" :disabled="managementDisabled">{{ $t('message.storage.management.clear.confirm') }}</a-checkbox>
        </details>
        <p class="operation-help">{{ $t(record.diskofferingcomputeonly ? 'message.offering.compute.dedicated' : 'message.offering.compute.linked') }}</p>
      </a-form>
      <a-alert v-if="submitError" class="operation-retry" type="error" :message="submitError" show-icon />
      <a-alert v-if="unknownResult" class="operation-retry" type="warning" :message="$t('message.offering.update.unknown')" show-icon />
      <a-button v-if="unknownResult || submitError" class="operation-retry" :disabled="saving" @click="load">{{ $t('label.refresh') }}</a-button>
      <MoldDialogActions class="operation-footer" :busy="loading || saving">
        <a-button :disabled="saving" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="saving" :disabled="managementDisabled" @click="submit">{{ $t('label.ok') }}</a-button>
      </MoldDialogActions>
    </a-spin>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import management from '@/utils/storageManagementMixin'
import { offeringSnapshot, computeEditParams } from '@/utils/storageManagement'
import { trueValue } from '@/utils/offeringQos'
import { getFilteredExternalDetails } from '@/utils/extension'
import DetailsInput from '@/components/widgets/DetailsInput'
import ObjectListTable from '@/components/view/ObjectListTable'
export default {
  name: 'UpdateComputeOffering',
  components: { MoldDialogActions, DetailsInput, ObjectListTable },
  mixins: [management],
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data: () => ({ record: {}, form: {}, tagsAllowed: false, postStarted: false }),
  computed: {
    managementApi: () => 'updateServiceOffering',
    currentExternal () { return getFilteredExternalDetails(this.record.serviceofferingdetails) || {} },
    tagFields () { return this.tagsAllowed ? ['storagetags', 'hosttags'].filter(key => key in this.managementFields) : [] }
  },
  methods: {
    async readRecord () {
      const response = await getAPI('listServiceOfferings', { id: this.resource.id, listall: true, state: 'all', details: 'all' }, { preserveOnFailure: true })
      const record = response?.listserviceofferingsresponse?.serviceoffering?.find(row => row.id === this.resource.id)
      if (!record) throw new Error('message.offering.not.found')
      return record
    },
    async load () {
      const version = ++this.version; const context = this.managementContext
      this.loading = true; this.loaded = false; this.readError = ''; this.submitError = ''; this.unknownResult = false; this.postStarted = false
      try {
        this.checkManagementPermission()
        const record = await this.readRecord()
        let tagsAllowed = this.managementAdmin
        if (!tagsAllowed && this.$store.getters.apis.isAccountAllowedToCreateOfferingsWithTags) {
          const result = await getAPI('isAccountAllowedToCreateOfferingsWithTags', { id: this.$store.getters.userInfo.accountid }, { preserveOnFailure: true })
          tagsAllowed = trueValue(result.isaccountallowedtocreateofferingswithtagsresponse?.isallowed?.isallowed)
        }
        if (!this.currentRequest(version, context)) return
        this.record = record; this.tagsAllowed = tagsAllowed
        this.form = { ...Object.fromEntries(['name', 'displaytext', 'storagetags', 'hosttags'].map(key => [key, record[key] || ''])), purgeresources: trueValue(record.purgeresources), external: { ...getFilteredExternalDetails(record.serviceofferingdetails) }, externalMode: 'preserve', confirmClear: false }
        this.loaded = true
      } catch (error) { if (this.currentRequest(version, context)) this.readError = this.managementReason(error) } finally { if (this.currentRequest(version, context)) this.loading = false }
    },
    async submit () {
      if (this.managementDisabled) return
      const version = this.version; const context = this.managementContext
      this.saving = true; this.submitError = ''; this.postStarted = false
      try {
        this.checkManagementPermission(); await this.$refs.formRef.validate()
        const fresh = await this.readRecord()
        if (!this.currentRequest(version, context)) return
        if (offeringSnapshot(fresh) !== offeringSnapshot(this.record)) throw new Error('message.storage.management.changed')
        const params = computeEditParams(this.record, this.form, this.managementFields, this.tagsAllowed)
        if (Object.keys(params).length === 1) { this.$emit('close-action'); return }
        this.postStarted = true
        await postAPI(this.managementApi, params, { preserveOnFailure: true })
        if (!this.currentRequest(version, context)) return
        this.$message.success(this.$t('message.offering.update.saved')); this.$emit('refresh-data'); this.$emit('close-action')
      } catch (error) { if (this.currentRequest(version, context)) this.failedSubmission(error) } finally { if (this.currentRequest(version, context)) this.saving = false }
    }
  }
}
</script>
<style scoped lang="scss">
@import '@/style/volume-operation-dialog';
.operation-options { margin-bottom: 20px; }
.operation-options .ant-radio-group { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
</style>
