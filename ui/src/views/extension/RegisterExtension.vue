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
      :rules="rules"
      :loading="loading"
      layout="vertical"
      @finish="handleSubmit">
      <a-form-item name="resourcetype" :label="$t('label.resourcetype')">
        <a-input :value="$t(resourceType === 'Cluster' ? 'label.cluster' : 'label.physicalnetworkid')" readonly />
      </a-form-item>
      <a-alert v-if="resourceType === 'PhysicalNetwork'" class="registration-notice" type="info" show-icon :message="$t('message.extension.register.provider')" />
      <a-form-item ref="resourceid" name="resourceid">
        <template #label>
          <tooltip-label :title="$t('label.resourceid')" :tooltip="apiParams.resourceid.description"/>
        </template>
        <a-select
          showSearch
          v-model:value="form.resourceid"
          :placeholder="$t('message.error.select')"
          :loading="resources.loading"
          :disabled="resources.loading || !resources.options.length"
          :aria-label="$t('label.resourceid')"
          allow-clear
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option v-for="opt in resources.options" :key="opt.id" :label="resourceLabel(opt)">
            {{ resourceLabel(opt) }}
          </a-select-option>
        </a-select>
        <a-alert v-if="resources.message" class="registration-notice" :type="resources.retry ? 'warning' : 'info'" show-icon :message="$t(resources.message)">
          <template v-if="resources.retry" #description><a-button size="small" @click="fetchResources">{{ $t('label.refresh') }}</a-button></template>
        </a-alert>
      </a-form-item>
      <a-form-item>
        <template #label>
          <tooltip-label :title="$t('label.details')" :tooltip="apiParams.details.description"/>
        </template>
        <div style="margin-bottom: 10px">{{ $t('message.add.extension.resource.details') }}</div>
        <details-input
          v-model:value="form.details" />
      </a-form-item>
      <mold-dialog-actions :busy="loading">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" :disabled="!form.resourceid" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </mold-dialog-actions>
    </a-form>
  </div>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import DetailsInput from '@/components/widgets/DetailsInput'

export default {
  name: 'RegisterExtension',
  components: {
    MoldDialogActions,
    TooltipLabel,
    DetailsInput
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      resources: { loading: false, options: [], message: '', retry: false },
      loading: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('registerExtension')
  },
  created () {
    this.formRef = ref()
    this.form = reactive({})
    this.rules = { resourceid: [{ required: true, message: this.$t('message.error.select') }] }
    this.fetchResources()
  },
  computed: {
    resourceType () {
      return { Orchestrator: 'Cluster', NetworkOrchestrator: 'PhysicalNetwork' }[this.resource.type]
    },
    listApi () {
      return { Cluster: 'listClusters', PhysicalNetwork: 'listPhysicalNetworks' }[this.resourceType]
    }
  },
  methods: {
    resourceLabel (candidate) {
      const zone = candidate.zonename || this.$store.getters.zones?.find(zone => zone.id === candidate.zoneid)?.name || candidate.zoneid
      return zone ? candidate.name + ' · ' + zone : candidate.name
    },
    async fetchResources () {
      if (!this.listApi || !(this.listApi in this.$store.getters.apis)) {
        this.resources.message = 'message.extension.register.unavailable'
        return
      }
      this.resources.loading = true
      this.resources.message = ''
      this.resources.retry = false
      try {
        const cluster = this.resourceType === 'Cluster'
        const params = cluster ? { hypervisor: 'External' } : {}
        const data = await getAPI(this.listApi, params, { preserveOnFailure: true })
        const candidates = data[this.listApi.toLowerCase() + 'response']?.[this.resourceType.toLowerCase()] || []
        const linked = new Set((this.resource.resources || []).map(resource => resource.id))
        const eligible = candidates.filter(candidate => !cluster || candidate.hypervisortype === 'External')
        this.resources.options = eligible.filter(candidate => !linked.has(candidate.id))
        if (!this.resources.options.length) {
          this.resources.message = eligible.length ? 'message.extension.register.already.linked' : 'message.extension.register.no.candidates'
        }
      } catch (error) {
        this.resources.options = []
        this.resources.retry = true
        this.resources.message = error.response?.status === 403
          ? 'message.extension.register.unavailable'
          : 'message.extension.register.load.failed'
      } finally {
        this.resources.loading = false
      }
    },
    async handleSubmit (event) {
      if (typeof event?.preventDefault === 'function') event.preventDefault()
      if (this.loading || !this.resources.options.some(option => option.id === this.form.resourceid)) return
      this.loading = true
      try {
        await this.formRef.value.validate()
        const params = {
          extensionid: this.resource.id,
          resourcetype: this.resourceType,
          resourceid: this.form.resourceid
        }
        for (const [key, value] of Object.entries(this.form.details || {})) params['details[0].' + key] = value
        await postAPI('registerExtension', params)
        this.$emit('refresh-data')
        this.$notification.success({
          message: this.$t('label.register.extension'),
          description: this.$t('message.success.register.extension')
        })
        this.closeAction()
      } catch (error) {
        if (!error.errorFields) this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
.form-layout { width: 100%; min-width: 0; }
.registration-notice { margin: 8px 0; }
</style>
