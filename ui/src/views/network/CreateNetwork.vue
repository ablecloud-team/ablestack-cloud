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
  <a-spin :spinning="loading" class="form-layout">
    <a-tabs v-model:activeKey="defaultNetworkTypeTabKey" :animated="false" v-if="!loading">
      <a-tab-pane :tab="$t('label.isolated')" key="1" :disabled="Object.values(formBusy).some(Boolean) && defaultNetworkTypeTabKey !== '1'" v-if="isAdvancedZoneWithoutSGAvailable">
        <CreateIsolatedNetworkForm
          ref="isolated"
          :active="defaultNetworkTypeTabKey === '1'"
          @busy="value => { formBusy['1'] = value }"
          :loading="loading"
          :resource="resource"
          :submit-handler="submitHandler"
          @close-action="closeAction"
          @refresh-data="refreshParent"
          @refresh="handleRefresh"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.l2')" key="3" :disabled="Object.values(formBusy).some(Boolean) && defaultNetworkTypeTabKey !== '3'">
        <CreateL2NetworkForm
          ref="l2"
          :active="defaultNetworkTypeTabKey === '3'"
          @busy="value => { formBusy['3'] = value }"
          :loading="loading"
          :resource="resource"
          :submit-handler="submitHandler"
          @close-action="closeAction"
          @refresh-data="refreshParent"
          @refresh="handleRefresh"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.network.lifecycle.type.shared')" key="2" :disabled="Object.values(formBusy).some(Boolean) && defaultNetworkTypeTabKey !== '2'">
        <CreateSharedNetworkForm
          ref="shared"
          :active="defaultNetworkTypeTabKey === '2'"
          @busy="value => { formBusy['2'] = value }"
          :loading="loading"
          :resource="resource"
          :submit-handler="submitHandler"
          @close-action="closeAction"
          @refresh-data="refreshParent"
          @refresh="handleRefresh"/>
      </a-tab-pane>
    </a-tabs>
  </a-spin>
</template>

<script>
import { getAPI } from '@/api'
import CreateIsolatedNetworkForm from '@/views/network/CreateIsolatedNetworkForm'
import CreateL2NetworkForm from '@/views/network/CreateL2NetworkForm'
import CreateSharedNetworkForm from '@/views/network/CreateSharedNetworkForm'

export default {
  name: 'CreateNetwork',
  components: {
    CreateIsolatedNetworkForm,
    CreateL2NetworkForm,
    CreateSharedNetworkForm
  },
  props: {
    submitHandler: { type: Function, default: null },
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      isAdvancedZoneWithoutSGAvailable: false,
      defaultNetworkTypeTabKey: '1',
      loading: false,
      formBusy: {},
      actionZones: [],
      actionZoneLoading: false,
      zoneRequest: 0,
      initialized: false
    }
  },
  computed: {
    effectiveZoneId () {
      return (this.submitHandler || this.$route.name === 'deployVirtualMachine' || this.$route.path.startsWith('/backup'))
        ? this.resource?.zoneid
        : undefined
    }
  },
  watch: {
    effectiveZoneId () { this.fetchData() }
  },
  beforeUnmount () { this.zoneRequest++ },
  created () {
    this.fetchData()
  },
  methods: {
    submit () {
      const form = { 1: 'isolated', 2: 'shared', 3: 'l2' }[this.defaultNetworkTypeTabKey]
      if (this.$refs[form]) this.$refs[form].handleSubmit()
    },
    async fetchData () {
      const request = ++this.zoneRequest
      this.loading = !this.initialized
      this.actionZoneLoading = true
      try {
        const params = this.effectiveZoneId ? { id: this.effectiveZoneId } : {}
        const json = await getAPI('listZones', params, { preserveOnFailure: true })
        if (request !== this.zoneRequest) return
        this.actionZones = json.listzonesresponse.zone || []
        this.isAdvancedZoneWithoutSGAvailable = this.actionZones.some(zone =>
          zone.networktype === 'Advanced' && zone.securitygroupsenabled !== true)
        if (!this.initialized || (this.defaultNetworkTypeTabKey === '1' && !this.isAdvancedZoneWithoutSGAvailable)) {
          this.defaultNetworkTypeTabKey = this.isAdvancedZoneWithoutSGAvailable ? '1' : '2'
        }
        this.initialized = true
      } catch (error) {
        if (request === this.zoneRequest) this.$notifyError(error)
      } finally {
        if (request === this.zoneRequest) this.loading = this.actionZoneLoading = false
      }
    },
    handleRefresh () {
    },
    refreshParent () {
      this.$emit('refresh-data')
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="scss">
  .form-layout {
    width: 100%;
    min-width: 0;
  }
</style>
