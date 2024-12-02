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
        class="form"
        :ref="formRef"
        :model="form"
        :rules="rules"
        layout="vertical"
        @finish="handleSubmit"
        v-ctrl-enter="handleSubmit"
    >
      <a-alert style="margin-bottom: 5px" type="warning" show-icon>
        <template #message>
          <span v-html="$t('message.dr.mirrored.vm.remove')" />
        </template>
      </a-alert>
      <a-form-item
        name="confirm"
        ref="confirm">
        <a-input
          v-model:value="form.confirm"
          :placeholder="$t('label.action.confirm.remove.dr.mirroring.vm')" />
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
import { api } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon.vue'
import TooltipLabel from '@/components/widgets/TooltipLabel.vue'
export default {
  name: 'DRMirroringVMRemove',
  components: {
    TooltipLabel,
    ResourceIcon
  },
  props: {
    resource: {
      type: Object,
      default: () => {}
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      loading: false,
      confirm: '',
      params: [],
      drName: '',
      drCluster: []
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        confirm: [{ required: true, message: this.$t('label.required') }]
      })
    },
    fetchData () {
      this.loading = true
      api('getDisasterRecoveryClusterList', { drclustertype: 'secondary' }).then(json => {
        this.drCluster = json.getdisasterrecoveryclusterlistresponse.disasterrecoverycluster
        for (const dr of this.drCluster) {
          for (const vm of dr.drclustervmmap) {
            if (vm.drclustervmid === this.resource.id) {
              this.drName = vm.drclustername
              break
            }
          }
        }
      }).finally(() => {
        this.loading = false
      })
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        if (values.confirm !== '삭제하겠습니다' && values.confirm !== 'I would like to delete it') {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.error.confirm.remove.dr.mirroring.vm')
          })
          return
        }
        this.loading = true
        const params = {
          virtualmachineid: this.resource.id,
          drclustername: this.drName
        }
        api('deleteDisasterRecoveryClusterVm', params).then(response => {
          this.$pollJob({
            jobId: response.deletedisasterrecoveryclustervmresponse.jobid,
            description: this.resource.id,
            successMessage: this.$t('message.success.remove.disaster.recovery.cluster.vm'),
            successMethod: () => {
              this.loading = false
              this.parentFetchData()
            },
            errorMessage: this.$t('message.error.remove.disaster.recovery.cluster.vm'),
            errorMethod: () => {
              this.loading = false
              this.parentFetchData()
            },
            loadingMessage: this.$t('message.remove.disaster.recovery.cluster.vm.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.loading = false
              this.parentFetchData()
            }
          })
          this.closeModal()
        }).catch(error => {
          this.$notifyError(error)
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
</style>
