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
          ref="automationcontroller"
          name="automationcontroller"
          :label="$t('label.automation.controller.template.version')">
          <a-select
            v-model:value="form.automationcontroller"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.automation.controller.template.version')"
            :loading="automationControllerLoading">
            <a-select-option v-for="(opt, optIndex) in this.automationController" :key="optIndex">
              {{ opt.automationtemplatename }}
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
          ref="networkid"
          name="networkid"
          :label="$t('label.network')">
          <a-select
            v-model:value="form.networkid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.network')"
            :loading="networkLoading"
            @change="val => { this.handleNetworkChange(this.networks[val]) }">
            >
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
import { api } from '@/api'
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
      accessType: 'external',
      clusters: [],
      networks: [],
      networkLoading: false,
      automationController: [],
      automationControllerLoading: false,
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
        networkid: this.selectedNetwork
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        description: [{ required: true, message: this.$t('message.error.required.input') }],
        addomainname: [{ required: true, message: this.$t('message.error.required.input') }],
        // automationcontroller: [{ required: true, message: this.$t('message.error.select') }],
        serviceoffering: [{ required: true, message: this.$t('message.error.select') }],
        networkid: [{ required: true, message: this.$t('message.error.select') }],
        automationcontrollerip: [{ required: true, message: this.$t('message.error.required.input') }],
        serviceip: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    fetchData () {
      this.fetchControllerData()
      this.fetchAutomationControllerData()
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
      api('listAutomationController', params).then(json => {
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
    fetchServiceOfferingData () {
      this.serviceOfferings = []
      const params = {}
      this.serviceOfferingLoading = true
      api('listServiceOfferings', params).then(json => {
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
      api('listAutomationController', params).then(json => {
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
      this.handleNetworkChange(null)
      api('listNetworks', params).then(json => {
        var items = json.listnetworksresponse.network
        if (items !== null) {
          if (this.accessType === 'internal') this.networks = items.filter(it => it.type.includes('L2'))
          if (this.accessType === 'external') this.networks = items.filter(it => it.type.includes('Isolated'))
          if (this.accessType === 'mixed') this.networks = items.filter(it => it.type.includes('Shared'))

          // console.log(this.accessType, this.networks)
          this.handleNetworkChange(this.networks[0])
        }
      }).finally(() => {
        this.networkLoading = false
        if (this.arrayHasItems(this.networks)) {
          this.form.networkid = 0
        } else {
          this.form.networkid = null
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
          addomainname: values.addomainname,
          // desktoppassword: values.password,
          controllerversion: this.controllerVersions[values.controllerversion].id,
          serviceofferingid: this.serviceOfferings[values.serviceofferingid].id,
          networkid: this.selectedNetwork.id,
          clustertype: this.accessType,
          worksip: values.worksvmip,
          serviceip: values.serviceip,
          dcip: values.dcvmip
        }
        if (values.masteruploadtype === 'url') {
          if (values.zoneid === this.$t('label.all.zone')) {
            delete params.zoneid
          } else {
            params.zoneid = values.zoneid
          }
          params.hypervisor = this.hyperVisor.opts[values.hypervisor].name
          params.format = values.format
          params.masterurl = values.masterurl
          params.masterostype = values.masterostype
        } else {
          params.templateid = values.mastertemplate
        }

        api('createDesktopCluster', params).then(json => {
          const jobId = json.createdesktopclusterresponse.jobid
          this.$pollJob({
            jobId,
            title: this.$t('label.desktop.cluster.deploy'),
            description: values.name,
            successMethod: () => {
              this.$notification.success({
                message: this.$t('message.success.create.desktop.cluter'),
                duration: 0
              })
              eventBus.emit('automation-refresh-data')
            },
            loadingMessage: `${this.$t('label.desktop.cluster.deploy')} ${values.name} ${this.$t('label.in.progress')}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              eventBus.emit('automation-refresh-data')
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

      // if (this.isValidValueForKey(values, 'gateway')) {
      //   params.gateway = values.gateway
      // }
      // if (this.isValidValueForKey(values, 'netmask')) {
      //   params.netmask = values.netmask
      // }
      // if (this.isValidValueForKey(values, 'startip')) {
      //   params.startip = values.startip
      // }
      // if (this.isValidValueForKey(values, 'endip')) {
      //   params.endip = values.endip
      // }
      // if (this.isValidValueForKey(values, 'worksip')) {
      //   params.worksip = values.worksip
      // }
      // if (this.isValidValueForKey(values, 'dcip')) {
      //   params.dcip = values.dcip
      // }
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
