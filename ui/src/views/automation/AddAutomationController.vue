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
  <div class="form-layout">
    <a-spin :spinning="loading">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @submit="handleSubmit"
        layout="vertical">
        <a-form-item ref="name" name="name" :label="$t('label.name')">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="$t('placeholder.name')"/>
          </template>
          <a-input
            v-model:value="form.name"
            :placeholder="$t('placeholder.name')"/>
        </a-form-item>
        <a-form-item ref="description" name="description" :label="$t('label.description')">
          <template #label>
            <tooltip-label :title="$t('label.description')" :tooltip="$t('placeholder.description')"/>
          </template>
          <a-input
            v-model:value="form.description"
            :placeholder="$t('placeholder.description')"/>
        </a-form-item>
        <a-form-item
          ref="automationcontrollerversion"
          name="automationcontrollerversion"
          :label="$t('label.automation.controller.template.version')">
          <a-select
            v-model:value="form.automationcontrollerversion"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.automation.controller.template.version')"
            :loading="automationControllerLoading">
            <a-select-option v-for="(opt, optIndex) in this.automationControllerVersion" :key="optIndex">
              {{ opt.name }} {{ opt.version }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          ref="serviceoffering"
          name="serviceoffering"
          :label="$t('label.compute.offerings')">
          <a-select
            v-model:value="form.serviceoffering"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.compute.offering')"
            :loading="serviceOfferingLoading">
            <a-select-option v-for="(opt, optIndex) in this.serviceOfferings" :key="optIndex">
              {{ opt.name }} {{ opt.version }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          ref="network"
          name="network"
          :label="$t('label.network')">
          <a-select
            v-model:value="form.network"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.network')"
            :loading="networkLoading">
<!--            @change="val => { this.handleNetworkChange(this.networks[val]) }">-->
            <a-select-option v-for="(opt, optIndex) in this.networks" :key="optIndex">
              <span v-if="opt.type!=='L2'">
                {{ opt.name || opt.description }} ({{ `${$t('label.cidr')}: ${opt.cidr}` }})
              </span>
              <span v-else>{{ opt.name || opt.description }}</span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          ref="automationcontrollerip"
          name="automationcontrollerip"
          :label="$t('label.automation.controller.ip')">
          <template #label>
            <tooltip-label :title="$t('label.automation.controller.ip')" :tooltip="$t('placeholder.automation.controller.ip')"/>
          </template>
          <a-input
            v-model:value="form.automationcontrollerip"
            :placeholder="$t('placeholder.automation.controller.ip')"/>
        </a-form-item>
        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI } from '@/api'
import store from '@/store'
import eventBus from '@/config/eventBus'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'addAutomationController',
  components: {
    TooltipLabel
  },
  props: {},
  data () {
    return {
      loading: false,
      networks: [],
      networkLoading: false,
      automationController: [],
      automationControllerLoading: false,
      automationControllerVersion: [],
      automationControllerVersionLoading: false,
      serviceOfferings: [],
      serviceOfferingLoading: false
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        network: this.selectedNetwork
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        description: [{ required: true, message: this.$t('message.error.required.input') }],
        automationcontrollerversion: [{ required: true, message: this.$t('message.error.select') }],
        serviceoffering: [{ required: true, message: this.$t('message.error.select') }],
        network: [{ required: true, message: this.$t('message.error.select') }],
        automationcontrollerip: [{ required: false, message: this.$t('message.error.required.input') }]
      })
    },
    fetchData () {
      this.fetchControllerData()
      this.fetchAutomationControllerData()
      this.fetchAutomationControllerVersionData()
      this.fetchServiceOfferingData()
    },
    isValidValueForKey (obj, key) {
      return key in obj && obj[key] != null
    },
    arrayHasItems (array) {
      return array !== null && array !== undefined && Array.isArray(array) && array.length > 0
    },
    handleAccessTypeChange (pvlan) {
      this.accessType = pvlan
      this.fetchNetworkData()
    },
    fetchAutomationControllerData () {
      this.automationController = []
      const params = {}
      this.automationControllerLoading = true
      getAPI('listAutomationController', params).then(json => {
        var items = json.listautomationcontrollerresponse.automationcontroller
        if (items != null) {
          // this.controllerVersions = items.filter(it => it.state === 'Enabled')
          this.automationController = items
        }
      }).finally(() => {
        this.automationControllerLoading = false
        if (this.arrayHasItems(this.automationController)) {
          this.form.automationController = 0
        }
      })
    },
    fetchAutomationControllerVersionData () {
      this.automationControllerVersion = []
      const params = {}
      this.automationControllerVersionLoading = true
      getAPI('listAutomationControllerVersion', params).then(json => {
        var items = json.listautomationcontrollerversionresponse.automationcontrollerversion
        if (items != null) {
          this.automationControllerVersion = items.filter(it => it.state === 'Enabled')
        }
      }).finally(() => {
        this.automationControllerVersionLoading = false
        if (this.arrayHasItems(this.automationControllerVersion)) {
          this.form.automationControllerversion = 0
        }
      })
    },
    fetchServiceOfferingData () {
      this.serviceOfferings = []
      const params = {}
      this.serviceOfferingLoading = true
      getAPI('listServiceOfferings', params).then(json => {
        var items = json.listserviceofferingsresponse.serviceoffering
        if (items != null) {
          this.serviceOfferings = items.filter(it => it.iscustomized === false)
        }
      }).finally(() => {
        this.serviceOfferingLoading = false
        if (this.arrayHasItems(this.serviceOfferings)) {
          this.form.serviceofferingid = 0
        }
      })
    },
    fetchControllerData () {
      this.controllers = []
      const params = {
        domainid: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.account,
        listall: true
      }
      getAPI('listAutomationController', params).then(json => {
        var items = json.listautomationcontrollerresponse.automationcontroller
        if (items != null) {
          this.controllers.push(items)
        }
      }).finally(() => {
        this.fetchNetworkData()
      })
    },
    fetchNetworkData () {
      this.networks = []
      const params = {
        domainid: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.account
      }
      this.networkLoading = true
      getAPI('listNetworks', params).then(json => {
        const listNetworks = json.listnetworksresponse.network
        if (listNetworks !== null) {
          this.networks = listNetworks.filter(it => it.type.includes('Isolated'))
        }
      }).finally(() => {
        this.networkLoading = false
        if (this.arrayHasItems(this.networks)) {
          this.form.networkid = 0
        }
      })
    },
    handleNetworkChange (network) {
      this.selectedNetwork = network
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        const params = {
          name: values.name,
          description: values.description,
          serviceofferingid: this.serviceOfferings[values.serviceoffering].id,
          automationcontrollerip: values.automationcontrollerip,
          zoneid: this.networks[values.network].zoneid,
          domainid: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.domainid,
          domainname: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.domainname,
          account: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.account,
          accountid: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.accountid,
          automationtemplateid: this.automationControllerVersion[values.automationcontrollerversion].id
        }
        if (this.isValidValueForKey(values, 'network') && this.arrayHasItems(this.networks) && this.networks[values.network].id != null) {
          params.networkid = this.networks[values.network].id
          params.networkname = this.networks[values.network].name
        }
        getAPI('addAutomationController', params).then(json => {
          const jobId = json.addautomationcontrollerresponse.jobid
          this.$pollJob({
            jobId,
            title: this.$t('label.automation.controller.deploy'),
            description: values.name,
            successMethod: () => {
              this.$notification.success({
                message: this.$t('message.success.create.automation.controller'),
                duration: 0
              })
              eventBus.emit('automation-controller-refresh-data')
            },
            loadingMessage: `${this.$t('label.automation.controller.deploy')} ${values.name} ${this.$t('label.in.progress')}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              eventBus.emit('automation-controller-refresh-data')
            },
            action: {
              isFetchData: false
            }
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
  width: 80vw;
  @media (min-width: 600px) {
    width: 450px;
  }
}
.action-button {
  text-align: right;
  button {
    margin-right: 5px;
  }
}
</style>
