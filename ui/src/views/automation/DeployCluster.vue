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
        <a-form-item ref="addomainname" name="addomainname" :label="$t('label.addomainname')">
          <template #label>
            <tooltip-label :title="$t('label.addomainname')" :tooltip="$t('placeholder.addomainname')"/>
          </template>
          <a-input
            v-model:value="form.addomainname"
            :placeholder="$t('placeholder.addomainname')"/>
        </a-form-item>
        <!-- <a-row :gutter="12">
          <a-col :md="24" :lg="12">
            <a-form-item>
              <span slot="label">
                {{ $t('label.password') }}
                <a-tooltip :title="$t('placeholder.password')">
                  <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
                </a-tooltip>
              </span>
              <a-input-password
                v-decorator="['password', {
                  rules: [{ required: true, message: $t('message.error.required.input') }]
                }]"
                :placeholder="$t('placeholder.password')"/>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item>
              <span slot="label">
                {{ $t('label.confirmpassword') }}
                <a-tooltip :title="$t('placeholder.confirmpassword')">
                  <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
                </a-tooltip>
              </span>
              <a-input-password
                v-decorator="['confirmpassword', {
                  rules: [
                    { required: true, message: $t('message.error.required.input') },
                    { validator: validateConfirmPassword }
                  ]
                }]"
                :placeholder="$t('placeholder.confirmpassword')"/>
            </a-form-item>
          </a-col>
        </a-row> -->
        <a-form-item
          ref="controllerversion"
          name="controllerversion"
          :label="$t('label.desktop.controller.template.version')">
          <a-select
            v-model:value="form.controllerversion"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="$t('placeholder.desktop.controller.template.version')"
            :loading="controllerVersionLoading">
            <a-select-option v-for="(opt, optIndex) in this.controllerVersions" :key="optIndex">
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
            :loading="templateVersionLoading">
            <a-select-option v-for="(opt, optIndex) in this.serviceOfferings" :key="optIndex">
              {{ opt.name }} {{ opt.version }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <!-- <a-form-item>
          <span slot="label">
            {{ $t('label.access.type') }}
            <a-tooltip :title="$t('placeholder.accesstype')">
              <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
            </a-tooltip>
          </span>
          <a-radio-group
            v-decorator="['accesstype', {
              initialValue: this.accessType,
              rules: [{ required: true, message: $t('message.error.select') }]
            }]"
            buttonStyle="solid"
            @change="selected => { this.handleAccessTypeChange(selected.target.value) }">
            <a-radio-button value="external">
              {{ $t('label.access.external') }}
            </a-radio-button>
            <a-radio-button value="internal">
              {{ $t('label.access.internal') }}
            </a-radio-button>
            <a-radio-button value="mixed">
              {{ $t('label.access.mixed') }}
            </a-radio-button>
          </a-radio-group>
        </a-form-item> -->
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
        <!-- <a-row :gutter="12">
          <a-col :md="24" :lg="12">
            <a-form-item v-if="this.accessType=='internal'">
              <span slot="label">
                {{ $t('label.gateway') }}
                <a-tooltip :title="$t('label.gateway')">
                  <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
                </a-tooltip>
              </span>
              <a-input
                v-decorator="['gateway', {
                  rules: [{ required: true, message: $t('message.error.required.input') }]
                }]"
                :placeholder="$t('label.gateway')" />
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item v-if="this.accessType=='internal'">
              <span slot="label">
                {{ $t('label.netmask') }}
                <a-tooltip :title="$t('placeholder.netmask')">
                  <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
                </a-tooltip>
              </span>
              <a-input
                v-decorator="['netmask', {
                  rules: [{ required: true, message: $t('message.error.required.input') }]
                }]"
                :placeholder="$t('placeholder.netmask')" />
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item v-if="this.accessType=='internal'">
              <span slot="label">
                {{ $t('label.startip') }}
                <a-tooltip :title="$t('placeholder.startip')">
                  <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
                </a-tooltip>
              </span>
              <a-input
                v-decorator="['startip', {
                  rules: [{ required: true, message: $t('message.error.required.input') }]
                }]"
                :placeholder="$t('placeholder.startip')" />
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item v-if="this.accessType=='internal'">
              <span slot="label">
                {{ $t('label.endip') }}
                <a-tooltip :title="$t('placeholder.endip')">
                  <a-icon type="info-circle" style="color: rgba(0,0,0,.45)" />
                </a-tooltip>
              </span>
              <a-input
                v-decorator="['endip', {
                  rules: [{ required: true, message: $t('message.error.required.input') }]
                }]"
                :placeholder="$t('placeholder.endip')" />
            </a-form-item>
          </a-col>
        </a-row> -->
        <a-form-item
          ref="worksvmip"
          name="worksvmip"
          :label="$t('label.worksvmip')">
          <template #label>
            <tooltip-label :title="$t('label.worksvmip')" :tooltip="$t('placeholder.worksvmip')"/>
          </template>
          <a-input
            v-model:value="form.worksvmip"
            :placeholder="$t('placeholder.worksvmip')"/>
        </a-form-item>
        <a-form-item
          ref="dcvmip"
          name="dcvmip"
          :label="$t('label.dcvmip')">
          <template #label>
            <tooltip-label :title="$t('label.dcvmip')" :tooltip="$t('placeholder.dcvmip')"/>
          </template>
          <a-input
            v-model:value="form.dcvmip"
            :placeholder="$t('placeholder.dcvmip')"/>
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
  name: 'CreateDesktopCluster',
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
      controllerVersions: [],
      controllerVersionLoading: false,
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
        controllerversion: [{ required: true, message: this.$t('message.error.select') }],
        serviceoffering: [{ required: true, message: this.$t('message.error.select') }],
        networkid: [{ required: true, message: this.$t('message.error.select') }],
        worksvmip: [{ required: true, message: this.$t('message.error.required.input') }],
        dcvmip: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    fetchData () {
      this.fetchClusterData()
      this.fetchTemplateVersionData()
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
    // validateConfirmPassword (rule, value, callback) {
    //   if (!value || value.length === 0) {
    //     callback()
    //   } else if (rule.field === 'confirmpassword') {
    //     const form = this.form
    //     const messageConfirm = this.$t('error.password.not.match')
    //     const passwordVal = form.getFieldValue('password')
    //     if (passwordVal && passwordVal !== value) {
    //       callback(messageConfirm)
    //     } else {
    //       callback()
    //     }
    //   } else {
    //     callback()
    //   }
    // },
    fetchTemplateVersionData () {
      this.controllerVersions = []
      const params = {}
      this.controllerVersionLoading = true
      api('listAutomationControllerVersion', params).then(json => {
        var items = json.listdesktopcontrollerversionsresponse.desktopcontrollerversion
        if (items != null) {
          this.controllerVersions = items.filter(it => it.state === 'Enabled')
        }
      }).finally(() => {
        this.controllerVersionLoading = false
        if (this.arrayHasItems(this.controllerVersions)) {
          this.form.controllerversion = 0
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
    fetchClusterData () {
      this.clusters = []
      const params = {
        domainid: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.domainid,
        account: store.getters.project && store.getters.project.id ? null : store.getters.userInfo.account,
        listall: true
      }
      api('listDesktopClusters', params).then(json => {
        var items = json.listdesktopclustersresponse.desktopcluster
        if (items != null) {
          this.clusters.push(items)
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

          // for (var i = 0; i < items.length; i++) {
          //   if (this.accessType === 'internal' && items[i].type === 'L2') {
          //     this.networks.push(items[i])
          //     this.handleNetworkChange(this.networks[0])
          //   }
          //   if (this.accessType === 'external' && items[i].type === 'Isolated') {
          //     this.networks.push(items[i])
          //     if (this.clusters.length !== 0) {
          //       for (var j = 0; j < this.clusters[0].length; j++) {
          //         if ([this.clusters[0][j].networkid].includes(items[i].id)) {
          //           this.networks.pop(items[i])
          //         }
          //         this.handleNetworkChange(this.networks[0])
          //       }
          //     } else {
          //       this.handleNetworkChange(this.networks[0])
          //     }
          //   }
          //   if (this.accessType === 'mixed' && items[i].type === 'Shared') {
          //     this.networks.push(items[i])
          //     this.handleNetworkChange(this.networks[0])
          //   }
          // }
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
