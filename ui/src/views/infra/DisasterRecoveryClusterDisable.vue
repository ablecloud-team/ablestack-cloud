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
  <a-spin :spinning="loading">
    <a-form
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      class="form"
      @finish="handleSubmit"
      v-ctrl-enter="handleSubmit"
    >
      <a-alert style="margin-bottom: 5px" type="warning" show-icon>
        <template #message>
          <span v-html="$t('message.dr.mirrored.cluster.disable')" />
        </template>
      </a-alert>
      <a-form-item
        name="confirm"
        ref="confirm">
        <a-input
          v-model:value="form.confirm"
          :placeholder="$t('label.action.confirm.disable.dr.mirroring.cluster')" />
      </a-form-item>
      <div :span="24" class="action-button">
        <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
import eventBus from '@/config/eventBus'

export default {
  name: 'DisasterRecoveryClusterDisable',
  components: {
    TooltipLabel,
    ResourceIcon
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      loading: false,
      spinTemplate: false,
      confirm: '',
      params: []
    }
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        confirm: [{ required: true, message: this.$t('label.required') }]
      })
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value
        .validate()
        .then(() => {
          const values = toRaw(this.form)
          if (values.confirm !== '비활성화하겠습니다' && values.confirm !== 'I would like to disable it') {
            this.$notification.error({
              message: this.$t('message.request.failed'),
              description: this.$t('message.error.confirm.remove.dr.mirroring.vm')
            })
            return
          }
          this.loading = true
          const params = {
            id: this.resource.id
          }
          postAPI('disableDisasterRecoveryCluster', params).then(json => {
            const jobId = json.disabledisasterrecoveryclusterresponse.jobid
            this.$pollJob({
              jobId,
              title: this.$t('label.action.disable.disaster.recovery.cluster'),
              description: values.name,
              successMethod: () => {
                this.$notification.success({
                  message: this.$t('message.success.disable.disaster.recovery.cluster'),
                  duration: 0
                })
                eventBus.emit('dr-refresh-data')
              },
              loadingMessage: `${this.$t('label.action.disable.disaster.recovery.cluster')} ${this.resource.name} ${this.$t('label.in.progress')}`,
              catchMessage: this.$t('error.fetching.async.job.result'),
              catchMethod: () => {
                eventBus.emit('dr-refresh-data')
              }
            })
            eventBus.emit('dr-refresh-data')
            this.closeModal()
          }).finally(() => {
            this.loading = false
          })
        }).catch(error => {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        })
    },
    closeModal () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="scss">
.form {

  width: 80vw;

  @media (min-width: 700px) {
    width: 550px;
  }
}

.required {
  color: #ff0000;

  &-label {
    display: none;

    &--visible {
      display: block;
    }

  }
}

.ant-alert-warning {
  border: 1px solid #ffe58f;
  background-color: #fffbe6;
}

.test-con-content {
  padding: 50px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
}
</style>
