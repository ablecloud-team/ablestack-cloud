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
        @submit="handleSubmit"
        layout="vertical">
        <a-row :gutter="12">
          <a-col :md="24" :lg="24">
            <a-form-item ref="controllerversionname" name="controllerversionname" :label="$t('label.name')">
              <template #label>
                <tooltip-label :title="$t('label.name')" :tooltip="$t('placeholder.name')"/>
              </template>
              <a-input
                v-model:value="form.controllerversionname"
                :placeholder="$t('placeholder.name')"/>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :md="24" :lg="24">
            <a-form-item ref="description" name="description" :label="$t('label.description')">
              <template #label>
                <tooltip-label :title="$t('label.description')" :tooltip="$t('placeholder.description')"/>
              </template>
              <a-input
                v-model:value="form.description"
                :placeholder="$t('placeholder.description')"/>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :md="24" :lg="24">
            <a-form-item ref="controllerversion" name="controllerversion" :label="$t('label.controllerversion')">
              <template #label>
                <tooltip-label :title="$t('label.version')" :tooltip="$t('placeholder.version')"/>
              </template>
              <a-input
                v-model:value="form.controllerversion"
                :placeholder="$t('placeholder.version')"/>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :md="24" :lg="24">
            <a-form-item ref="controlleruploadtype" name="controlleruploadtype" :label="$t('label.controlleruploadtype')">
              <template #label>
                <tooltip-label :title="$t('label.controlleruploadtype')" :tooltip="$t('placeholder.controlleruploadtype')"/>
              </template>
              <a-radio-group
                v-model:value="form.controlleruploadtype"
                buttonStyle="solid"
                @change="selected => { handleUploadTypeChange(selected.target.value) }">
                <a-radio-button value="template">
                  {{ $t('label.templatename') }}
                </a-radio-button>
                <a-radio-button value="url">
                  {{ $t('label.url') }}
                </a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='url'">
          <a-col :md="24" :lg="24">
            <a-form-item
              :label="$t('label.zones')"
              ref="zoneid"
              name="zoneid">
              <a-select
                v-model:value="form.zoneid"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                @change="handlerSelectZone"
                :placeholder="$t('placeholder.zones')"
                :loading="zones.loading">
                <a-select-option :value="zone.id" v-for="zone in zones.opts" :key="zone.id" :label="zone.name || zone.description">
                  <span>
                    <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
                    <global-outlined v-else style="margin-right: 5px" />
                    {{ zone.name || zone.description }}
                  </span>
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='url'">
          <a-col :md="24" :lg="12">
            <a-form-item ref="hypervisor" name="hypervisor" :label="$t('label.hypervisor')">
              <a-select
                v-model:value="form.hypervisor"
                :loading="hyperVisor.loading"
                :placeholder="$t('placeholder.hypervisor')"
                @change="handlerSelectHyperVisor"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option v-for="(opt, optIndex) in hyperVisor.opts" :key="optIndex">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item ref="format" name="format" :label="$t('label.format')">
              <a-select
                v-model:value="form.format"
                :placeholder="$t('placeholder.format')"
                @change="val => { selectedFormat = val }"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option v-for="opt in format.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='url'">
          <a-col :md="24" :lg="24">
            <a-form-item ref="dcurl" name="dcurl"  :label="$t('label.dcvm.template.upload.url')" >
              <a-input
                v-model:value="form.dcurl"
                :placeholder="$t('placeholder.dcvm.template.upload.url')" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='url'">
          <a-col :md="24" :lg="24">
            <a-form-item
              name="dcostype"
              ref="dcostype"
              :label="$t('label.dcvm.template.ostype')">
              <a-select
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                v-model:value="form.dcostype"
                :loading="osTypes.loading"
                :placeholder="$t('placeholder.dcvm.template.ostype')">
                <a-select-option v-for="opt in osTypes.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='url'">
          <a-col :md="24" :lg="24">
            <a-form-item ref="worksurl" name="worksurl"  :label="$t('label.worksvm.template.upload.url')" >
              <a-input
                v-model:value="form.worksurl"
                :placeholder="$t('placeholder.worksvm.template.upload.url')" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='url'">
          <a-col :md="24" :lg="24">
            <a-form-item
              name="worksostype"
              ref="worksostype"
              :label="$t('label.worksvm.template.ostype')">
              <a-select
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                v-model:value="form.worksostype"
                :loading="osTypes.loading"
                :placeholder="$t('placeholder.worksvm.template.ostype')">
                <a-select-option v-for="opt in osTypes.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12" v-if="form.controlleruploadtype =='template'">
          <a-col :md="24" :lg="24">
            <a-form-item
              name="dctemplate"
              ref="dctemplate"
              :label="$t('label.dctemplate')">
              <a-select
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                v-model:value="form.dctemplate"
                :loading="template.loading"
                :placeholder="$t('placeholder.template')">
                <a-select-option v-for="opt in template.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="24">
            <a-form-item
              name="workstemplate"
              ref="workstemplate"
              :label="$t('label.workstemplate')">
              <a-select
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                v-model:value="form.workstemplate"
                :loading="template.loading"
                :placeholder="$t('placeholder.template')">
                <a-select-option v-for="opt2 in template.opts" :key="opt2.id">
                  {{ opt2.name || opt2.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ this.$t('label.cancel') }}</a-button>
          <a-button :loading="loading" type="primary" @click="handleSubmit">{{ this.$t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { api } from '@/api'
import store from '@/store'
import TooltipLabel from '@/components/widgets/TooltipLabel'
export default {
  name: 'AddDesktopControllerVersion',
  components: {
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    action: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      zones: [],
      defaultZone: '',
      zoneSelected: '',
      hyperVisor: {},
      template: {},
      format: {},
      osTypes: {},
      defaultOsType: '',
      defaultOsId: null,
      selectedFormat: '',
      zoneError: '',
      zoneErrorMessage: '',
      loading: false,
      rootAdmin: 'Admin',
      osTypeLoading: false
    }
  },
  created () {
    this.zones = [
      {
        id: null,
        name: this.$t('label.all.zone')
      }
    ]
    this.initForm()
    this.fetchData()
  },
  computed: {
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        controlleruploadtype: 'template'
      })
      this.rules = reactive({
        controllerversionname: [{ required: true, message: this.$t('message.error.required.input') }],
        description: [{ required: true, message: this.$t('message.error.required.input') }],
        controllerversion: [{ required: true, message: this.$t('message.error.required.input') }],
        controlleruploadtype: [{ required: true, message: this.$t('message.error.select') }],
        zoneid: [{ required: true, message: this.$t('message.error.select') }],
        hypervisor: [{ required: true, message: this.$t('message.error.select') }],
        format: [{ required: true, message: this.$t('message.error.select') }],
        dcurl: [{ required: true, message: this.$t('message.error.required.input') }],
        dcostype: [{ required: true, message: this.$t('message.error.select') }],
        worksurl: [{ required: true, message: this.$t('message.error.required.input') }],
        worksostype: [{ required: true, message: this.$t('message.error.select') }],
        dctemplate: [{ required: true, message: this.$t('message.error.select') }],
        workstemplate: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      this.fetchZone()
      this.fetchOsTypes()
      this.fetchTemplateData()
    },
    fetchZone () {
      const params = {}
      let listZones = []
      params.listAll = true
      params.showicon = true

      if (store.getters.userInfo.roletype === this.rootAdmin) {
        listZones.push({
          id: this.$t('label.all.zone'),
          name: this.$t('label.all.zone')
        })
      }

      this.zones.loading = true
      this.zones.opts = []

      api('listZones', params).then(json => {
        const listZonesResponse = json.listzonesresponse.zone
        listZones = listZones.concat(listZonesResponse)
        this.zones.opts = listZones
      }).finally(() => {
        this.form.zoneid = (this.zones.opts && this.zones.opts[1]) ? this.zones.opts[1].id : ''
        this.zones.loading = false
        this.fetchHyperVisor({ zoneid: this.form.zoneid })
      })
    },
    fetchHyperVisor (params) {
      this.hyperVisor.loading = true
      let listhyperVisors = this.hyperVisor.opts || []

      api('listHypervisors', params).then(json => {
        const listResponse = json.listhypervisorsresponse.hypervisor || []
        if (listResponse) {
          listhyperVisors = listhyperVisors.concat(listResponse)
        }
        this.hyperVisor.opts = listhyperVisors
      }).finally(() => {
        this.hyperVisor.loading = false
      })
    },
    fetchOsTypes () {
      const params = {}
      params.listAll = true

      this.osTypes.opts = []
      this.osTypes.loading = true

      api('listOsTypes', params).then(json => {
        const listOsTypes = json.listostypesresponse.ostype
        this.osTypes.opts = listOsTypes
        this.defaultOsType = this.osTypes.opts[1].description
        this.defaultOsId = this.osTypes.opts[1].id
      }).finally(() => {
        this.osTypes.loading = false
      })
    },
    fetchTemplateData () {
      let listTemplates = []
      const params = {}
      params.templatefilter = 'executable'
      params.listall = true
      this.template.loading = true
      this.template.opts = []
      api('listTemplates', params).then(json => {
        const listTemplatesResponse = json.listtemplatesresponse.template
        listTemplates = listTemplates.concat(listTemplatesResponse)
        this.template.opts = listTemplates
      }).finally(() => {
        // this.zoneSelected = (this.template.opts && this.template.opts[1]) ? this.template.opts[1].id : ''
        this.template.loading = false
      })
    },
    fetchFormat (hyperVisor) {
      const format = []

      switch (hyperVisor) {
        case 'Hyperv':
          format.push({
            id: 'VHD',
            description: 'VHD'
          })
          format.push({
            id: 'VHDX',
            description: 'VHDX'
          })
          break
        case 'KVM':
          this.hyperKVMShow = true
          format.push({
            id: 'QCOW2',
            description: 'QCOW2'
          })
          format.push({
            id: 'RAW',
            description: 'RAW'
          })
          format.push({
            id: 'VHD',
            description: 'VHD'
          })
          format.push({
            id: 'VMDK',
            description: 'VMDK'
          })
          break
        case 'XenServer':
          this.hyperXenServerShow = true
          format.push({
            id: 'VHD',
            description: 'VHD'
          })
          break
        case 'Simulator':
          format.push({
            id: 'VHD',
            description: 'VHD'
          })
          format.push({
            id: 'QCOW2',
            description: 'QCOW2'
          })
          break
        case 'VMware':
          this.hyperVMWShow = true
          format.push({
            id: 'OVA',
            description: 'OVA'
          })
          break
        case 'BareMetal':
          format.push({
            id: 'BareMetal',
            description: 'BareMetal'
          })
          break
        case 'Ovm':
          format.push({
            id: 'RAW',
            description: 'RAW'
          })
          break
        case 'LXC':
          format.push({
            id: 'TAR',
            description: 'TAR'
          })
          break
        default:
          break
      }
      this.format.opts = format
    },
    handlerSelectZone (value) {
      if (!Array.isArray(value)) {
        value = [value]
      }
      this.hyperVisor.opts = []

      if (this.zoneError !== '') {
        return
      }

      const arrSelectReset = ['hypervisor', 'format']
      this.resetSelect(arrSelectReset)

      const params = {}

      if (value.includes(this.$t('label.all.zone'))) {
        params.listAll = true
        this.fetchHyperVisor(params)
        return
      }

      for (let i = 0; i < value.length; i++) {
        const zoneSelected = this.zones.opts.filter(zone => zone.id === value[i])

        if (zoneSelected.length > 0) {
          params.zoneid = zoneSelected[0].id
          this.fetchHyperVisor(params)
        }
      }
    },
    handlerSelectHyperVisor (value) {
      const hyperVisor = this.hyperVisor.opts[value].name
      const arrSelectReset = ['format']

      this.hyperXenServerShow = false
      this.hyperVMWShow = false
      this.hyperKVMShow = false

      this.resetSelect(arrSelectReset)
      this.fetchFormat(hyperVisor)
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        const params = {
          controllerversionname: values.controllerversionname,
          description: values.description,
          controllerversion: values.controllerversion,
          controlleruploadtype: values.controlleruploadtype
        }

        if (values.controlleruploadtype === 'url') {
          if (values.zoneid === this.$t('label.all.zone')) {
            delete params.zoneid
          } else {
            params.zoneid = values.zoneid
          }
          params.hypervisor = this.hyperVisor.opts[values.hypervisor].name
          params.format = values.format
          params.dcurl = values.dcurl
          params.dcostype = values.dcostype
          params.worksurl = values.worksurl
          params.worksostype = values.worksostype
        } else {
          params.dctemplateid = values.dctemplate
          params.workstemplateid = values.workstemplate
        }

        api('addDesktopControllerVersion', params).then(json => {
          this.$notification.success({
            message: this.$t('label.register.template'),
            description: `${this.$t('message.success.register.contoller.template.version')}`
          })
          this.$emit('refresh-data')
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
    handleUploadTypeChange (val) {
      this.form.controlleruploadtype = val
    },
    validZone (zones) {
      const allZoneExists = zones.filter(zone => zone === this.$t('label.all.zone'))

      this.zoneError = ''
      this.zoneErrorMessage = ''

      if (allZoneExists.length > 0 && zones.length > 1) {
        this.zoneError = 'error'
        this.zoneErrorMessage = this.$t('message.error.zone.combined')
      }
    },
    closeAction () {
      this.$emit('close-action')
    },
    resetSelect (arrSelectReset) {
      arrSelectReset.forEach(name => {
        this.form[name] = undefined
      })
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 80vw;

    @media (min-width: 700px) {
      width: 550px;
    }
  }

  .action-button {
    text-align: right;

    button {
      margin-right: 5px;
    }
  }
</style>
