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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-alert v-if="pending" type="warning" show-icon class="quota-message">
      <template #message>{{ $t('message.bucket.quota.pending') }}</template>
      <template #description>
        <Status text="Pending" :display-label="$t('state.bucket.quota.' + resource.quotaoperationstate.toLowerCase())" display-text />
        <div>{{ resource.quotaoperationid }}</div>
        <div>{{ $t('label.bucket.quota.checked') }}: {{ $toLocaleDate(resource.quotaoperationupdated) }}</div>
        <a-button :loading="loading" :disabled="loading || !canReconcile" @click="reconcile">{{ $t('label.bucket.quota.reconcile') }}</a-button>
      </template>
    </a-alert>
    <a-alert v-if="legacy" type="warning" show-icon class="quota-message" :message="$t('message.bucket.quota.legacy')" />
    <a-alert v-if="errorText" type="error" show-icon class="quota-message" :message="errorText" />
    <BucketQuotaContext :store-id="resource.objectstorageid || ''" />
    <a-form
      :disabled="loading || pending"
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      @finish="handleSubmit"
    >
      <a-form-item name="quota" ref="quota" :label="$t('label.quotagib')" :validate-status="quotaError ? 'error' : undefined" :help="quotaError || undefined">
        <a-input
          v-model:value="form.quota"
inputmode="numeric"
autocomplete="off"
:maxlength="10"
@input="quotaTouched = true; quotaError = ''"
          :placeholder="$t('label.quota')"/>
      </a-form-item>
      <a-form-item name="encryption" ref="encryption" :label="$t('label.encryption')">
        <a-switch
          v-model:checked="form.encryption"
          :checked="encryption"
          @change="val => { encryption = val }"/>
      </a-form-item>
      <a-form-item name="versioning" ref="versioning" :label="$t('label.versioning')">
        <a-switch
          v-model:checked="form.versioning"
          :checked="versioning"
          @change="val => { versioning = val }"/>
      </a-form-item>
      <a-form-item name="Bucket Policy" ref="policy" :label="$t('label.bucket.policy')">
        <a-select
          v-model:value="form.policy"
          @change="val => { form.policy = val }"
          showSearch
          optionFilterProp="value"
          :filterOption="(input, option) => String(option.label || option.value).toLowerCase().includes(input.toLowerCase())" >
          <a-select-option
            :value="policy"
              :label="$t('label.bucket.policy.' + policy.toLowerCase())"
            v-for="(policy,idx) in policyList"
            :key="idx"
          >{{ $t('label.bucket.policy.' + policy.toLowerCase()) }}</a-select-option>
        </a-select>
      </a-form-item>
      <div :span="24" class="action-button">
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </div>
</template>
<script>
import { ref, reactive } from 'vue'
import { postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import BucketQuotaContext from '@/components/view/BucketQuotaContext'
import Status from '@/components/widgets/Status'
import { validBucketQuota, pendingBucketQuota, bucketChanges, quotaErrorField } from '@/utils/bucketQuota'

export default {
  name: 'updateBucket',
  mixins: [mixinForm],
  components: { BucketQuotaContext, Status },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () { return { loading: false, quotaTouched: false, errorText: '', quotaError: '' } },
  computed: {
    pending () { return pendingBucketQuota(this.resource) },
    canReconcile () { return !!this.$getApiParams('updateBucket')?.reconcile },
    legacy () { return !validBucketQuota(this.resource.quota) }
  },
  created () {
    this.initForm()
    this.policyList = ['Public', 'Private']
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        quota: [{
          required: true,
          validator: (_, value) => this.legacy && !this.quotaTouched && String(value) === String(this.resource.quota)
            ? Promise.resolve()
            : validBucketQuota(value) ? Promise.resolve() : Promise.reject(new Error(this.$t('message.bucket.quota.validation')))
        }]
      })
    },
    fetchData () {
      for (const key of ['quota', 'encryption', 'versioning', 'policy']) this.form[key] = this.resource[key]
    },
    async reconcile () {
      if (this.loading || !this.pending || !this.canReconcile) return
      this.loading = true; this.errorText = ''
      try {
        await postAPI('updateBucket', { id: this.resource.id, reconcile: true, expectedquotaoperationid: this.resource.quotaoperationid })
        this.$emit('refresh-data'); this.closeModal()
      } catch (error) {
        this.errorText = error?.response?.data?.updatebucketresponse?.errortext || this.$t('message.bucket.quota.retry.failed')
        this.$emit('refresh-data')
      } finally { this.loading = false }
    },
    handleSubmit (e) {
      if (e?.preventDefault) e.preventDefault()
      if (this.loading || this.pending) return
      this.quotaError = ''
      this.formRef.value.validate().then(async () => {
        const changes = bucketChanges(this.form, this.resource, this.quotaTouched)
        if (changes.quota !== undefined && Object.keys(changes).some(key => key !== 'quota')) {
          this.errorText = this.$t('message.bucket.quota.separate')
          return
        }
        if (!Object.keys(changes).length) { this.closeModal(); return }
        this.loading = true; this.errorText = ''
        try {
          await postAPI('updateBucket', { id: this.resource.id, ...changes })
          this.$notification.success({ message: this.$t('label.bucket.update'), description: this.$t('message.success.update.bucket') })
          this.closeModal()
        } catch (error) {
          this.errorText = error?.response?.data?.updatebucketresponse?.errortext || this.$t('message.bucket.quota.retry.failed')
          if (quotaErrorField(error)) this.quotaError = this.errorText
          this.$emit('refresh-data')
        } finally { this.loading = false }
      }).catch(error => {
        if (error?.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    closeModal () {
      this.$emit('refresh-data')
      this.$emit('close-action')
    }
  }
}
</script>
<style lang="scss" scoped>
.form-layout { width: 100%; min-width: 0; }
.quota-message { margin-bottom: 16px; overflow-wrap: anywhere; }
.quota-message .ant-btn { margin-top: 8px; height: 32px; border-radius: 4px; }
</style>
