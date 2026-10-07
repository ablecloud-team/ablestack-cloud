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
    <a-spin :spinning="loading">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        layout="vertical"
        @finish="handleSubmit"
       >
        <a-form-item>
          <a-alert type="warning">
            <template #message>
             <span v-html="$t('message.confirm.change.service.offering.for.sharedfs')" />
            </template>
         </a-alert>
        </a-form-item>
        <a-alert
          v-if="resource.state === 'Ready'"
          :type="scalingReadiness?.ready ? 'info' : 'warning'"
          show-icon
          :message="$t(scalingReadiness?.ready ? 'message.sharedfs.scaling.online' : 'message.sharedfs.scaling.preparation')"
          :description="scalingReadinessReason"
          class="scaling-readiness" />
        <a-form-item ref="serviceofferingid" name="serviceofferingid">
          <template #label>
            <tooltip-label :title="$t('label.serviceofferingid')" :tooltip="apiParams.serviceofferingid.description || 'Service Offering'"/>
          </template>
          <a-select
            v-model:value="form.serviceofferingid"
            :loading="serviceofferingLoading"
            :placeholder="apiParams.serviceofferingid.description || $t('label.serviceofferingid')"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option
              v-for="(serviceoffering, index) in serviceofferings"
              :value="serviceoffering.id"
              :key="index"
              :disabled="!offeringSelectable(serviceoffering)"
              :title="offeringReason(serviceoffering)"
              :label="serviceoffering.name || serviceoffering.displaytext">
              <span v-if="!serviceoffering.compatibility?.compatible" class="field-hint">{{ offeringReason(serviceoffering) }} · </span>
              {{ serviceoffering.name || serviceoffering.displaytext }}
            </a-select-option>
          </a-select>
          <div class="field-hint">{{ $t('message.storage.service.offering.requirements', { cpu: offeringRequirements?.minimumcpu || minCpu, memory: offeringRequirements?.minimummemory || minMemory }) }}</div>
          <a-alert v-if="serviceOfferingReadError" type="warning" show-icon :message="$t('message.storage.service.offering.unavailable')" />
          <a-alert v-else-if="!serviceofferingLoading && !serviceofferings.some(item => offeringSelectable(item))" type="warning" show-icon :message="$t('message.storage.service.offering.no.compatible')" />
        </a-form-item>
        <div :span="24" class="action-button">
          <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" :disabled="serviceofferingLoading || !offeringSelectable(selectedOffering)" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>
<script>

import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import store from '@/store'

export default {
  name: 'ChangeSharedFSServiceOffering',
  mixins: [mixinForm],
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  components: {
    ResourceIcon,
    TooltipLabel
  },
  inject: ['parentFetchData'],
  data () {
    return {
      owner: {
        projectid: store.getters.project?.id,
        domainid: store.getters.project?.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project?.id ? null : store.getters.userInfo.account
      },
      loading: false,
      configLoading: false,
      serviceofferings: [],
      serviceofferingLoading: false,
      serviceOfferingRequestToken: 0,
      serviceOfferingReadError: false,
      offeringRequirements: null,
      scalingReadiness: null,
      scalingReadinessError: false,
      minCpu: store.getters.features?.sharedfsvmmincpucount || 2,
      minMemory: store.getters.features?.sharedfsvmminramsize || 1024
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('changeSharedFileSystemServiceOffering')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  computed: {
    selectedOffering () { return this.serviceofferings.find(item => item.id === this.form.serviceofferingid) },
    currentOffering () { return this.scalingReadiness?.currentOffering || this.serviceofferings.find(item => item.id === this.resource.serviceofferingid) },
    scalingReadinessReason () {
      if (this.scalingReadinessError) return this.$t('message.sharedfs.scaling.read.unavailable')
      return (this.scalingReadiness?.reasons || []).map(code => this.$t('message.sharedfs.scaling.reason.' + code.toLowerCase())).join(', ')
    }
  },
  beforeUnmount () { this.serviceOfferingRequestToken++ },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
      })
      this.rules = reactive({
        serviceofferingid: [{ required: true, message: this.$t('label.required') }]
      })
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    fetchData () {
      this.fetchServiceOfferings()
    },
    offeringSelectable (offering) {
      if (!offering?.compatibility?.compatible || offering.id === this.resource.serviceofferingid) return false
      if (this.resource.state === 'Ready' && !this.scalingReadiness?.ready) return false
      const current = this.currentOffering
      if (!current || !Number.isFinite(current.cpu) || !Number.isFinite(current.memory) || !Number.isFinite(current.cpuspeed)) return false
      if (offering.cpu < current.cpu || offering.memory < current.memory || offering.cpuspeed < current.cpuspeed) return false
      return this.resource.state !== 'Ready' || offering.cpu > current.cpu || offering.memory > current.memory
    },
    offeringReason (offering) {
      const current = this.currentOffering
      if (current && (offering.cpu < current.cpu || offering.memory < current.memory || offering.cpuspeed < current.cpuspeed)) return this.$t('message.sharedfs.scaling.downscale.unsupported')
      return (offering.compatibility?.reasons || ['CONSTRAINTS_UNAVAILABLE'])
        .map(code => this.$t('message.storage.service.offering.reason.' + code.toLowerCase())).join(', ')
    },
    async fetchServiceOfferings () {
      const request = ++this.serviceOfferingRequestToken
      const zoneId = this.resource.zoneid
      this.serviceofferingLoading = true
      this.serviceOfferingReadError = false
      this.serviceofferings = []
      this.form.serviceofferingid = ''
      const params = { zoneid: zoneId, listall: true, domainid: this.owner.domainid }
      if (this.owner.projectid) params.projectid = this.owner.projectid
      else params.account = this.owner.account
      try {
        const json = await getAPI('listServiceOfferings', params, { preserveOnFailure: true, timeout: 15000 })
        if (request !== this.serviceOfferingRequestToken) return
        const items = json.listserviceofferingsresponse.serviceoffering || []
        this.serviceofferings = items.map(item => ({ ...item, compatibility: null }))
        if (!items.length) return
        const response = await getAPI('listStorageServiceOfferingConstraints', {
          zoneid: zoneId, serviceofferingids: items.map(item => item.id).join(',')
        }, { preserveOnFailure: true, timeout: 15000 })
        if (request !== this.serviceOfferingRequestToken) return
        const entries = response.liststorageserviceofferingconstraintsresponse.storageserviceofferingconstraint || []
        const byId = Object.fromEntries(entries.map(item => [item.id, item]))
        this.offeringRequirements = entries[0] || null
        this.serviceofferings = items.map(item => ({ ...item, compatibility: byId[item.id] || null }))
        if (this.resource.state === 'Ready') {
          this.scalingReadiness = null
          this.scalingReadinessError = false
          try {
            const readiness = await getAPI('getSharedFileSystemScalingReadiness', { id: this.resource.id }, { preserveOnFailure: true, timeout: 15000 })
            if (request !== this.serviceOfferingRequestToken) return
            const result = readiness.getsharedfilesystemscalingreadinessresponse.sharedfilesystemscalingreadiness || readiness.getsharedfilesystemscalingreadinessresponse
            this.scalingReadiness = JSON.parse(result.resultjson)
          } catch (error) {
            if (request === this.serviceOfferingRequestToken) this.scalingReadinessError = true
          }
        }
        if (request !== this.serviceOfferingRequestToken) return
        this.form.serviceofferingid = this.serviceofferings.find(item => this.offeringSelectable(item))?.id || ''
      } catch (error) {
        if (request === this.serviceOfferingRequestToken) this.serviceOfferingReadError = true
      } finally {
        if (request === this.serviceOfferingRequestToken) this.serviceofferingLoading = false
      }
    },
    closeModal () {
      this.$emit('close-action')
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading || !this.offeringSelectable(this.selectedOffering)) return
      this.formRef.value.validate().then(async () => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)

        var data = {
          id: this.resource.id,
          serviceofferingid: values.serviceofferingid
        }
        this.loading = true
        postAPI('changeSharedFileSystemServiceOffering', data).then(response => {
          this.$pollJob({
            jobId: response.changesharedfilesystemserviceofferingresponse.jobid,
            title: this.$t('label.change.service.offering'),
            description: values.name,
            successMessage: this.$t('message.success.change.offering'),
            errorMessage: this.$t('message.change.service.offering.sharedfs.failed'),
            loadingMessage: this.$t('message.change.service.offering.sharedfs.processing'),
            catchMessage: this.$t('error.fetching.async.job.result')
          })
          this.closeModal()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch((error) => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    }
  }
}

</script>
<style lang="scss" scoped>
.scaling-readiness { margin-bottom: 16px; }
.form-layout {
  width: 85vw;

  @media (min-width: 1000px) {
    width: 35vw;
  }
}
</style>
