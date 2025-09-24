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
      class="form-layout"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      v-ctrl-enter="handleSubmit"
      @finish="handleSubmit">
      <!-- <a-alert style="margin-bottom: 5px" type="info" show-icon>
        <template #message>
          <span v-html="$t('message.clone.vm')" />
        </template>
      </a-alert> -->
      <a-form-item name="name" ref="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
        </template>
        <a-input v-model:value="form.name" v-focus="true" />
      </a-form-item>
      <a-form-item v-if="resource.pooltype == 'RBD'" ref="clonetype" name="clonetype">
        <template #label>
          <tooltip-label :title="$t('label.clonetype')" :tooltip="apiParams.clonetype.description"/>
        </template>
        <a-select v-model:value="form.clonetype" @change="typeChange">
          <a-select-option value='full'>Full</a-select-option>
          <a-select-option value='linked'>Linked</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="startvm" ref="startvm" >
        <template #label>
          <tooltip-label :title="$t('label.action.start.instance')" :tooltip="apiParams.startvm.description"/>
        </template>
        <a-switch v-model:checked="form.startvm" />
      </a-form-item>
      <a-form-item :label="$t('label.deploy.vm.number')" name="vmNumber" ref="vmNumber">
        <a-input-number :min=1 :max=50 :maxlength="2" v-model:value="form.count" />
      </a-form-item>

      <div :span="24" class="action-button">
        <a-button :loading="loading" @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </a-spin>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'CloneVM',
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
    this.apiParams = this.$getApiParams('cloneVirtualMachine')
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        clonetype: 'full',
        startvm: false,
        count: 1
      })
      this.rules = reactive({
        name: [{ required: true, message: `${this.$t('label.required')}` }]
      })
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)

        this.loading = true
        const params = {
          virtualmachineid: this.resource.id
        }
        for (const key in values) {
          if (values[key]) {
            params[key] = values[key]
          }
        }
        params.startvm = this.form.startvm
        if (this.resource.pooltype !== 'RBD') {
          params.clonetype = null
        }
        // console.log(params)
        getAPI('cloneVirtualMachine', params).then(json => {
          const jobId = json.clonevirtualmachineresponse.jobid
          this.$pollJob({
            jobId,
            title: this.$t('label.action.clone.vm'),
            description: this.resource.name,
            loadingMessage: `${this.$t('label.action.clone.vm')} ${this.resource.name}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            successMessage: `${this.$t('label.action.clone.vm')} ${this.resource.name}`
          })
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    closeAction () {
      this.$emit('close-action')
    },
    typeChange (val) {
      this.form.clonetype = val
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 80vw;
    @media (min-width: 800px) {
      width: 400px;
    }
  }
</style>
