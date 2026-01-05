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
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      @finish="handleSubmit"
     >
      <a-form-item name="name" ref="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
        </template>
        <a-input
          v-focus="true"
          v-model:value="form.name"/>
      </a-form-item>
      <a-form-item name="description" ref="description">
        <template #label>
          <tooltip-label :title="$t('label.description')" :tooltip="apiParams.description.description"/>
        </template>
        <a-input v-model:value="form.description"/>
      </a-form-item>
      <a-form-item name="allowuserdrivenbackups" ref="allowuserdrivenbackups" v-if="resource.provider!=='commvault'">
        <template #label>
          <tooltip-label :title="$t('label.allowuserdrivenbackups')" :tooltip="apiParams.allowuserdrivenbackups.description"/>
        </template>
        <a-switch v-model:checked="form.allowuserdrivenbackups"/>
      </a-form-item>
      <a-form-item name="retentionperiod" ref="retentionperiod" v-if="resource.provider==='commvault'">
        <template #label>
          <tooltip-label :title="$t('label.retentionperiod')" :tooltip="apiParams.retentionperiod.description"/>
        </template>
        <a-input-group compact>
          <a-input
            ref="retentionInput"
            v-if="form.retentionPeriodUnit !== 'Infinite'"
            v-model:value="form.retentionPeriodValue"
            style="width: 50%"
            type="number"
            min="1"/>
          <a-select
            v-model:value="form.retentionPeriodUnit"
            :style="form.retentionPeriodUnit === 'Infinite' ? 'width: 100%' : 'width: 50%'">
            <a-select-option value="Day">{{ $t('label.day') }}</a-select-option>
            <a-select-option value="Week">{{ $t('label.week') }}</a-select-option>
            <a-select-option value="Month">{{ $t('label.month') }}</a-select-option>
            <a-select-option value="Years">{{ $t('label.years') }}</a-select-option>
            <a-select-option value="Infinite">{{ $t('label.infinite') }}</a-select-option>
          </a-select>
        </a-input-group>
      </a-form-item>
      <div :span="24" class="action-button">
        <a-button @click="closeAction">{{ this.$t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ this.$t('label.ok') }}</a-button>
      </div>
    </a-form>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { api } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'UpdateBackupOffering',
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
  data () {
    return {
      loading: false,
      provider: ''
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateBackupOffering')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  computed: {
    retentionPeriodInDays () {
      const value = parseInt(this.form.retentionPeriodValue)
      switch (this.form.retentionPeriodUnit) {
        case 'Day':
          return value
        case 'Week':
          return value * 7
        case 'Month':
          return value * 30
        case 'Years':
          return value * 365
        case 'Infinite':
          return -1
        default:
          return value
      }
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        name: this.resource.name,
        description: this.resource.description,
        allowuserdrivenbackups: this.resource.allowuserdrivenbackups,
        retentionPeriodValue: this.resource.retentionperiod,
        retentionPeriodUnit: 'Day'
      })
      this.rules = reactive({
        retentionperiod: [{
          validator: (rule, value) => {
            if (this.form.retentionPeriodUnit === 'Infinite') {
              return Promise.resolve()
            }
            if (!this.form.retentionPeriodValue || this.form.retentionPeriodValue === '') {
              return Promise.reject(this.$t('message.error.required.input'))
            }
            return Promise.resolve()
          }
        }]
      })
    },
    fetchData () {
      this.isCommvault()
    },
    isCommvault () {
      api('listConfigurations', { name: 'backup.framework.provider.plugin' }).then(json => {
        if (json.listconfigurationsresponse.configuration[0]) {
          this.provider = json.listconfigurationsresponse.configuration[0].value
        }
      })
    },
    forceUpdateRetentionValue () {
      if (this.$refs.retentionInput) {
        const inputValue = this.$refs.retentionInput.$el.querySelector('input').value
        this.form.retentionPeriodValue = inputValue
      }
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        const params = {}
        for (const key in values) {
          const input = values[key]
          params[key] = input
        }
        if (this.resource.provider === 'commvault') {
          params.retentionperiod = this.retentionPeriodInDays
        }
        params.id = this.resource.id
        params.allowuserdrivenbackups = values.allowuserdrivenbackups
        this.loading = true
        const title = this.$t('label.update.backupoffering')
        api('updateBackupOffering', params).then(json => {
          this.$emit('refresh-data')
          this.$notification.success({
            message: `${title} ${params.name}`,
            description: values.name
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
    }
  }
}
</script>

<style scoped lang="less">
.form-layout {
  width: 30vw;

  @media (min-width: 500px) {
    width: 450px;
  }
}
</style>
