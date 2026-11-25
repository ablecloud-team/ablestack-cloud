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
    <a-form
      :ref="formRef"
      :model="form"
      :loading="loading"
      layout="vertical"
      @finish="handleSubmit">
      <a-form-item name="details" ref="details">
        <template #label>
          <tooltip-label :title="$t('label.details')" :tooltip="$t('message.add.extension.resource.details')"/>
        </template>
        <div style="margin-bottom: 10px">{{ $t('message.add.extension.resource.details') }}</div>
        <details-input
          v-model:value="form.details" />
      </a-form-item>
      <mold-dialog-actions :busy="loading">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </mold-dialog-actions>
    </a-form>
  </div>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import DetailsInput from '@/components/widgets/DetailsInput'

export default {
  name: 'UpdateRegisteredExtension',
  components: {
    MoldDialogActions,
    TooltipLabel,
    DetailsInput
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    extensionResource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false
    }
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        details: this.extensionResource.details ? { ...this.extensionResource.details } : {}
      })
    },
    handleSubmit (e) {
      if (typeof e?.preventDefault === 'function') e.preventDefault()
      if (this.loading) return
      this.loading = true
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        const params = {
          extensionid: this.resource.id,
          resourceid: this.extensionResource.id,
          resourcetype: this.extensionResource.type
        }
        if (values.details && Object.keys(values.details).length > 0) {
          Object.entries(values.details).forEach(([key, value]) => {
            params['details[0].' + key] = value
          })
        } else {
          params.cleanupdetails = true
        }
        return postAPI('updateRegisteredExtension', params).then(() => {
          this.$emit('refresh-data')
          this.$notification.success({
            message: this.$t('label.action.update.extension.resource'),
            description: this.$t('message.success.update.extension.resource')
          })
          this.closeAction()
        }).catch(error => {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: (error.response && error.response.headers && error.response.headers['x-description']) || error.message,
            duration: 0
          })
        })
      }).catch(error => {
        if (!error.errorFields) this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
.form-layout { width: 100%; min-width: 0; }
</style>
