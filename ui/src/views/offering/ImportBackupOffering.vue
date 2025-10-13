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
      <a-form-item name="zoneid" ref="zoneid">
        <template #label>
          <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
        </template>
        <a-select
          allowClear
          v-model:value="form.zoneid"
          :loading="zones.loading"
          @change="onChangeZone"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option v-for="zone in zones.opts" :key="zone.name" :label="zone.name">
            <span>
              <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
              <global-outlined v-else style="margin-right: 5px"/>
              {{ zone.name }}
            </span>
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="providername" ref="providername">
        <template #label>
          <tooltip-label :title="$t('label.provider')" :tooltip="apiParams.provider.description"/>
        </template>
        <a-select
          allowClear
          v-model:value="form.providername"
          :loading="providers.loading"
          @change="onChangeProvider"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option v-for="provider in providers.opts" :key="provider.name" :label="provider.name">
            {{ provider.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="externalid" ref="externalid">
        <template #label>
          <tooltip-label :title="$t('label.externalid')" :tooltip="apiParams.externalid.description"/>
        </template>
        <a-select
          allowClear
          v-model:value="form.externalid"
          :loading="externals.loading"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option v-for="opt in externals.opts" :key="opt.id" :label="opt.name">
            {{ opt.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="allowuserdrivenbackups" ref="allowuserdrivenbackups" v-if="!isCommvaultProvider">
        <template #label>
          <tooltip-label :title="$t('label.allowuserdrivenbackups')" :tooltip="apiParams.allowuserdrivenbackups.description"/>
        </template>
        <a-switch v-model:checked="form.allowuserdrivenbackups"/>
      </a-form-item>
      <a-form-item name="retentionperiod" ref="retentionperiod" v-if="isCommvaultProvider">
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
        <a-button :loading="loading" @click="closeAction">{{ this.$t('label.cancel') }}</a-button>
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
  name: 'ImportBackupOffering',
  components: {
    TooltipLabel,
    ResourceIcon
  },
  data () {
    return {
      loading: false,
      zones: {
        loading: false,
        opts: []
      },
      providers: {
        loading: false,
        opts: []
      },
      externals: {
        loading: false,
        opts: []
      },
      selectedZoneId: null,
      selectedProviderName: null,
      useCommvault: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('importBackupOffering')
  },
  created () {
    this.initForm()
    this.fetchData()
    this.checkBackupOffering()
  },
  computed: {
    isCommvaultProvider () {
      return this.selectedProviderName && this.selectedProviderName.toLowerCase() === 'commvault'
    },
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
        allowuserdrivenbackups: true,
        retentionPeriodValue: '',
        retentionPeriodUnit: 'Day'
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        description: [{ required: true, message: this.$t('message.error.required.input') }],
        zoneid: [{ required: true, message: this.$t('message.error.select') }],
        providername: [{ required: true, message: this.$t('message.error.select') }],
        externalid: [{ required: true, message: this.$t('message.error.select') }],
        retentionperiod: [{
          validator: (rule, value) => {
            if (!this.isCommvaultProvider) {
              return Promise.resolve()
            }
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
      this.fetchZone()
    },
    fetchZone () {
      this.zones.loading = true
      api('listZones', { available: true, showicon: true }).then(json => {
        this.zones.opts = json.listzonesresponse.zone || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.zones.loading = false
      })
    },
    checkBackupOffering () {
      api('listBackupOfferings').then(json => {
        var backupOff = json.listbackupofferingsresponse.backupoffering || []
        for (const off of backupOff) {
          if (off.provider === 'commvault') {
            this.useCommvault = true
            return
          }
        }
      })
    },
    fetchProvider (zoneId) {
      if (!zoneId) {
        this.providers.opts = []
        return
      }
      this.providers.loading = true
      api('listBackupProvidersForZone', { zoneid: zoneId }).then(json => {
        this.providers.opts = json.listbackupprovidersforzoneresponse.providers || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.providers.loading = false
      })
    },
    fetchExternal (zoneId, providerName) {
      if (!zoneId || !providerName) {
        this.externals.opts = []
        return
      }
      this.externals.loading = true
      api('listBackupProviderOfferings', { zoneid: zoneId, provider: providerName }).then(json => {
        this.externals.opts = json.listbackupproviderofferingsresponse.backupoffering || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.externals.loading = false
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
        if (this.useCommvault && this.selectedProviderName === 'commvault') {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.error.import.backup.offering')
          })
          return
        }
        const values = toRaw(this.form)
        const params = {}
        params.name = values.name
        params.description = values.description
        params.zoneid = this.selectedZoneId
        params.provider = this.selectedProviderName
        params.externalid = values.externalid
        if (this.isCommvaultProvider) {
          params.retentionperiod = this.retentionPeriodInDays
          params.allowuserdrivenbackups = true
        } else {
          params.allowuserdrivenbackups = values.allowuserdrivenbackups
        }
        this.loading = true
        const title = this.$t('label.import.offering')
        api('importBackupOffering', params).then(json => {
          const jobId = json.importbackupofferingresponse.jobid
          this.$pollJob({
            jobId,
            title,
            description: values.name,
            successMessage: `${title} ${params.name}`,
            loadingMessage: `${title} ${this.$t('label.in.progress')} ${this.$t('label.for')} ${params.name}`,
            catchMessage: this.$t('error.fetching.async.job.result')
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
    onChangeZone (value) {
      if (!value) {
        this.selectedZoneId = null
        this.selectedProviderName = null
        this.providers.opts = []
        this.externals.opts = []
        this.form.providername = undefined
        this.form.externalid = undefined
        return
      }
      const zone = this.zones.opts.find(zone => zone.name === value)
      this.selectedZoneId = zone ? zone.id : null
      this.selectedProviderName = null
      this.form.providername = undefined
      this.form.externalid = undefined
      this.externals.opts = []
      this.fetchProvider(this.selectedZoneId)
    },
    onChangeProvider (value) {
      if (!value) {
        this.selectedProviderName = null
        this.externals.opts = []
        this.form.externalid = undefined
        return
      }
      const provider = this.providers.opts.find(provider => provider.name === value)
      this.selectedProviderName = provider ? provider.name : null
      this.form.externalid = undefined
      this.externals.opts = []
      if (this.selectedZoneId && this.selectedProviderName) {
        this.fetchExternal(this.selectedZoneId, this.selectedProviderName)
      }
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
