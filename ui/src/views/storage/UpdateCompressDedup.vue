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
    <a-form :ref="formRef" :model="form" :rules="rules" layout="vertical" @finish="handleSubmit">
      <a-form-item name="compress" ref="compress">
        <template #label>
          <tooltip-label :title="$t('label.compress')" :tooltip="apiParams.compress.description" />
        </template>
        <a-switch v-model:checked="form.compress" />
      </a-form-item>
      <a-form-item name="dedup" ref="dedup">
        <template #label>
          <tooltip-label :title="$t('label.dedup')" :tooltip="apiParams.dedup.description" />
        </template>
        <a-switch v-model:checked="form.dedup" />
      </a-form-item>
      <div :span="24" class="action-button">
        <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </div>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { api } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'updateCompressDedup',
  components: {
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateCompressDedup')
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        compress: this.resource.compress,
        dedup: this.resource.dedup
      })
      this.rules = reactive({})
    },
    handleSubmit (e) {
      if (this.loading) return
      this.formRef.value
        .validate()
        .then(() => {
          const values = toRaw(this.form)
          const params = {}
          params.id = this.resource.id
          params.compress = values.compress
          params.dedup = values.dedup
          params.path = this.resource.path
          this.loading = true
          api('updateCompressDedup', params)
            .then((response) => {
              this.$pollJob({
                jobId: response.updatecompressdedupresponse.jobid,
                title: this.$t('label.action.update.compress.dedup'),
                description: values.name,
                successMessage: this.$t('message.success.update.compress.dedup'),
                successMethod: () => {},
                errorMessage: this.$t('message.update.compress.dedup.failed'),
                errorMethod: () => {
                  this.closeModal()
                },
                loadingMessage: this.$t('message.update.compress.dedup.processing'),
                catchMessage: this.$t('error.fetching.async.job.result'),
                catchMethod: () => {
                  this.loading = false
                  this.closeModal()
                }
              })
              this.closeModal()
            })
            .catch((error) => {
              this.$notification.error({
                message: `${this.$t('label.error')} ${error.response.status}`,
                description: error.response.data.errorresponse.errortext,
                duration: 0
              })
            })
            .finally(() => {
              this.loading = false
            })
        })
        .catch((error) => {
          this.formRef.value.scrollToField(error.errorFields[0].name)
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
.form-layout {
  width: 85vw;

  @media (min-width: 760px) {
    width: 500px;
  }
}
</style>
