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
  <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical">
    <a-col :md="24" :lg="24">
      <div>
        <a-form-item :label="$t('label.select.source.vcenter.datacenter')" name="vmwareopt" ref="vmwareopt">
          <a-radio-group
            style="text-align: center; width: 100%"
            v-model:value="vcenterSelectedOption"
            buttonStyle="solid"
            @change="onVcenterTypeChange">
            <a-radio-button value="existing" style="width: 50%; text-align: center">
              {{ $t('label.existing') }}
            </a-radio-button>
            <a-radio-button value="new" style="width: 50%; text-align: center">
              {{ $t('label.external') }}
            </a-radio-button>
          </a-radio-group>
        </a-form-item>
      </div>
      <div v-if="vcenterSelectedOption === 'existing'">
        <a-form-item name="sourcezoneid" ref="sourcezoneid" :label="$t('label.zoneid')">
          <a-select
            v-model:value="form.sourcezoneid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            @change="onSelectZoneId"
            :loading="zonesLoading || loading"
          >
            <a-select-option v-for="zoneitem in zones" :key="zoneitem.id" :label="zoneitem.name">
              <span>
                <resource-icon v-if="zoneitem.icon" :image="zoneitem.icon" size="1x" style="margin-right: 5px"/>
                <global-outlined v-else style="margin-right: 5px" />
                {{ zoneitem.name }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <div v-if="sourcezoneid">
          <a-form-item :label="$t('label.vcenter')" name="vmwaredatacenter" ref="vmwaredatacenter" v-if="existingvcenter.length > 0">
            <a-select
              v-model:value="form.vmwaredatacenter"
              :loading="loading"
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return  option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :placeholder="$t('label.vcenter.datacenter')"
              @change="onSelectExistingVmwareDatacenter">
              <a-select-option v-for="opt in existingvcenter" :key="opt.id">
                  {{ 'VC: ' + opt.vcenter + ' - DC: ' + opt.name }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <div v-else>
            {{ $t('message.list.zone.vmware.datacenter.empty') }}
          </div>
        </div>
      </div>
      <div v-else-if="vcenterSelectedOption === 'new'">
        <a-form-item ref="vcenter" name="vcenter">
          <template #label>
            <tooltip-label :title="$t('label.vcenter')" :tooltip="getApiParamDescription('vcenter', 'label.vcenter')"/>
          </template>
          <a-input
            v-model:value="vcenter"
            :placeholder="getApiParamDescription('vcenter', 'label.vcenter')"
            @blur="onSelectExternalVmwareDatacenter"
            @pressEnter="onSelectExternalVmwareDatacenter"
          />
        </a-form-item>
        <a-form-item ref="datacenter" name="datacenter">
          <template #label>
            <tooltip-label :title="$t('label.vcenter.datacenter')" :tooltip="getApiParamDescription('datacentername', 'label.vcenter.datacenter')"/>
          </template>
          <a-input
            v-model:value="datacenter"
            :placeholder="getApiParamDescription('datacentername', 'label.vcenter.datacenter')"
            @blur="onSelectExternalVmwareDatacenter"
            @pressEnter="onSelectExternalVmwareDatacenter"
          />
        </a-form-item>
        <a-form-item ref="username" name="username">
          <template #label>
            <tooltip-label :title="$t('label.vcenter.username')" :tooltip="getApiParamDescription('username', 'label.vcenter.username')"/>
          </template>
          <a-input
            v-model:value="username"
            :placeholder="getApiParamDescription('username', 'label.vcenter.username')"
            @blur="onSelectExternalVmwareDatacenter"
            @pressEnter="onSelectExternalVmwareDatacenter"
          />
        </a-form-item>
        <a-form-item ref="password" name="password">
          <template #label>
            <tooltip-label :title="$t('label.vcenter.password')" :tooltip="getApiParamDescription('password', 'label.vcenter.password')"/>
          </template>
          <a-input-password
            v-model:value="password"
            :placeholder="getApiParamDescription('password', 'label.vcenter.password')"
            @blur="onSelectExternalVmwareDatacenter"
            @pressEnter="onSelectExternalVmwareDatacenter"
          />
        </a-form-item>
        &nbsp;
        <tooltip-label :title="$t('label.press.enter')" :tooltip="$t('label.press.enter.tooltip')"/>
      </div>
      <div class="card-footer">
        <a-button
          v-if="vcenterSelectedOption == 'existing' || vcenterSelectedOption == 'new'"
          :disabled="(vcenterSelectedOption === 'new' && (vcenter === '' || datacenter === '' || username === '' || password === '')) ||
            (vcenterSelectedOption === 'existing' && selectedExistingVcenterId === '')"
          :loading="loading"
          type="primary"
          @click="listVmwareDatacenterVms">{{ $t('label.list.vmware.vcenter.vms') }}</a-button>
      </div>
    </a-col>
  </a-form>
</template>

<script>
import { getAPI } from '@/api'
import { ref, reactive } from 'vue'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import Status from '@/components/widgets/Status'

export default {
  name: 'SelectVmwareVcenter',
  components: {
    TooltipLabel,
    Status
  },
  props: {
    zoneid: {
      type: String,
      required: false
    },
    clusterid: {
      type: String,
      required: false
    },
    zones: {
      type: Array,
      required: true
    },
    zonesLoading: {
      type: Boolean,
      default: false
    },
    useAblestackV2KInventory: {
      type: Boolean,
      required: false,
      default: false
    }
  },
  data () {
    return {
      vcenter: '',
      datacenter: '',
      username: '',
      password: '',
      apiParams: {},
      loading: false,
      vcenterSelectedOption: '',
      existingvcenter: [],
      selectedExistingVcenterId: '',
      selectedPoweredOnVm: false,
      vmwareDcVms: [],
      vmwareDcVmSelectedRows: [],
      vmwareDcVmsColumns: [
        {
          title: this.$t('label.hostname'),
          dataIndex: 'hostname'
        },
        {
          title: this.$t('label.cluster'),
          dataIndex: 'clustername'
        },
        {
          title: this.$t('label.virtualmachinename'),
          dataIndex: 'virtualmachinename'
        },
        {
          title: this.$t('label.powerstate'),
          key: 'powerstate',
          dataIndex: 'powerstate'
        }
      ]
    }
  },
  computed: {
    supportsVmwareDatacenterImport () {
      return 'listVmwareDcVms' in this.$store.getters.apis && 'listVmwareDcs' in this.$store.getters.apis
    },
    vmwareDcVmsSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.vmwareDcVmSelectedRows || [],
        onChange: this.onVmwareDcVmSelectRow
      }
    }
  },
  created () {
    this.apiParams = this.$getApiParams('listVmwareDcVms') || {}
    this.initForm()
    if (this.useAblestackV2KInventory && !this.supportsVmwareDatacenterImport) {
      this.vcenterSelectedOption = 'new'
      this.$emit('onVcenterTypeChanged', this.vcenterSelectedOption)
    }
  },
  methods: {
    getApiParamDescription (paramName, fallbackTranslationKey) {
      return this.apiParams?.[paramName]?.description || this.$t(fallbackTranslationKey)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        vcenter: '',
        username: '',
        password: ''
      })
      this.rules = reactive({})
    },
    listVmwareDatacenterVms () {
      this.loading = true
      this.$emit('loadingVmwareUnmanagedInstances')
      const params = {}
      if (this.vcenterSelectedOption === 'new') {
        params.datacentername = this.datacenter
        params.vcenter = this.vcenter
        params.host = this.vcenter
        params.username = this.username
        params.password = this.password
      } else {
        params.existingvcenterid = this.selectedExistingVcenterId
      }
      params.page = 1
      params.pagesize = 10
      let apiName = 'listVmwareDcVms'
      let responseKey = 'listvmwaredcvmsresponse'
      if (this.useAblestackV2KInventory) {
        apiName = 'listVmsForImport'
        responseKey = 'listvmsforimportresponse'
        params.zoneid = this.zoneid
        params.clusterid = this.clusterid
        params.hypervisor = 'VMware'
        params.sourceprovider = 'vmware'
      }
      getAPI(apiName, params).then(json => {
        const obj = {
          params: params,
          response: json[responseKey]
        }
        this.$emit('listedVmwareUnmanagedInstances', obj)
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    onSelectZoneId (value) {
      this.sourcezoneid = value
      this.listZoneVmwareDcs()
    },
    listZoneVmwareDcs () {
      if (!this.supportsVmwareDatacenterImport) {
        this.existingvcenter = []
        return
      }
      this.loading = true
      getAPI('listVmwareDcs', { zoneid: this.sourcezoneid }).then(response => {
        if (response.listvmwaredcsresponse.VMwareDC && response.listvmwaredcsresponse.VMwareDC.length > 0) {
          this.existingvcenter = response.listvmwaredcsresponse.VMwareDC
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    onSelectExternalVmwareDatacenter (value) {
      if (this.vcenterSelectedOption === 'new' && !(this.vcenter === '' || this.datacenter === '' || this.username === '' || this.password === '')) {
        this.listVmwareDatacenterVms()
      }
    },
    onSelectExistingVmwareDatacenter (value) {
      this.selectedExistingVcenterId = value
    },
    onVcenterTypeChange () {
      this.$emit('onVcenterTypeChanged', this.vcenterSelectedOption)
    }
  }
}
</script>

<style scoped>
.card-footer {
  text-align: right;
}

.card-footer button {
  width: 50%;
  text-align: center;
}
</style>
