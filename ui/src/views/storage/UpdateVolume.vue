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
      <a-form-item name="name" ref="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description" />
        </template>
        <a-input v-model:value="form.name" :placeholder="$t('label.name')" />
      </a-form-item>
      <a-form-item v-if="!this.resource.kvdoenable" name="path" ref="path">
        <template #label>
          <tooltip-label :title="$t('label.path')" :tooltip="apiParams.path.description" />
        </template>
        <a-switch v-model:checked="form.check" />
      </a-form-item>
      <a-form-item name="path" ref="path" v-if="form.check">
        <a-input v-model:value="form.path" :placeholder="$t('label.path')" />
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
  name: 'UpdateVolume',
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
    this.apiParams = this.$getApiParams('updateVolume')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        name: this.resource.name,
        path: this.resource.path,
        account: this.resource.account,
        domainid: this.resource.domainid
      })
      this.rules = reactive({})
    },
    fetchData () {
      this.loading = true
      api('listDiskOfferings', {
        zoneid: this.resource.zoneid,
        listall: true
      })
        .then((json) => {
          this.offerings = json.listdiskofferingsresponse.diskoffering || []
          this.form.diskofferingid = this.offerings[0].id || ''
          this.customDiskOffering = this.offerings[0].iscustomized || false
          this.customDiskOfferingIops = this.offerings[0].iscustomizediops || false
        })
        .finally(() => {
          this.loading = false
        })
    },
    handleSubmit (e) {
      if (this.loading) return
      this.formRef.value
        .validate()
        .then(() => {
          const values = toRaw(this.form)
          const params = {}
          params.id = this.resource.id
          params.name = values.name
          params.account = values.account
          params.domainid = values.domainid
          params.path = values.path
          this.loading = true
          api('updateVolume', params)
            .then((response) => {
              this.$pollJob({
                jobId: response.updatevolumeresponse.jobid,
                title: this.$t('label.action.update.volume'),
                description: values.name,
                successMessage: this.$t('message.success.update.volume'),
                successMethod: () => {},
                errorMessage: this.$t('message.update.volume.failed'),
                errorMethod: () => {
                  this.closeModal()
                },
                loadingMessage: this.$t('message.update.volume.processing'),
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
