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
        @finish="handleSubmit"
        layout="vertical">
        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input
            v-model:value="form.name"
            :placeholder="apiParams.name.description"
            v-focus="true" />
        </a-form-item>
        <a-form-item name="displaytext" ref="displaytext">
          <template #label>
            <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
          </template>
          <a-input
            v-model:value="form.displaytext"
            :placeholder="apiParams.displaytext.description"
            v-focus="true" />
        </a-form-item>

        <a-row :gutter="12" v-if="resource.hypervisor !== 'VMware' || (resource.hypervisor === 'VMware' && !resource.deployasis)">
          <a-col :md="24" :lg="24">
            <a-form-item name="keyboard" ref="keyboard" v-if="resource.hypervisor === 'VMware' && !resource.deployasis" :label="$t('label.keyboardtype')">
              <a-select
                v-model:value="form.keyboard"
                :placeholder="$t('label.keyboard')">
                <a-select-option v-for="opt in keyboardType.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="24">
            <a-form-item name="ostypeid" ref="ostypeid" :label="$t('label.ostypeid')">
              <a-select
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                v-model:value="form.ostypeid"
                :loading="osTypes.loading"
                :placeholder="apiParams.ostypeid.description">
                <a-select-option v-for="opt in osTypes.opts.filter((c) => (c.name === 'Windows 10 (64-bit)') || (c.name === 'Rocky Linux 9'))" :key="opt.id" :label="opt.name || opt.description">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { api } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'UpdateTemplate',
  components: {
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      templatetypes: ['BUILTIN', 'USER', 'SYSTEM', 'ROUTING', 'VNF'],
      rootDisk: {},
      nicAdapterType: {},
      keyboardType: {},
      osTypes: {},
      loading: false,
      selectedTemplateType: '',
      userdata: {},
      userdataid: null,
      userdatapolicy: null,
      userdatapolicylist: {}
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateTemplate')
    this.isAdmin = ['Admin'].includes(this.$store.getters.userInfo.roletype)
    this.linkUserDataParams = this.$getApiParams('linkUserDataToTemplate')
  },
  created () {
    this.initForm()
    this.rootDisk.loading = false
    this.rootDisk.opts = []
    this.nicAdapterType.loading = false
    this.nicAdapterType.opts = []
    this.keyboardType.loading = false
    this.keyboardType.opts = []
    this.osTypes.loading = false
    this.osTypes.opts = []
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        displaytext: [{ required: true, message: this.$t('message.error.required.input') }],
        ostypeid: [{ required: true, message: this.$t('message.error.select') }]
      })
      const resourceFields = ['name', 'displaytext', 'passwordenabled', 'ostypeid', 'isdynamicallyscalable', 'userdataid', 'userdatapolicy']
      if (this.isAdmin) {
        resourceFields.push('templatetype')
      }
      for (var field of resourceFields) {
        var fieldValue = this.resource[field]
        if (fieldValue) {
          switch (field) {
            case 'userdataid':
              this.userdataid = fieldValue
              break
            case 'userdatapolicy':
              this.userdatapolicy = fieldValue
              break
            default:
              this.form[field] = fieldValue
              break
          }
        }
      }
      const resourceDetailsFields = []
      if (this.resource.hypervisor === 'KVM') {
        resourceDetailsFields.push('rootDiskController')
      } else if (this.resource.hypervisor === 'VMware' && !this.resource.deployasis) {
        resourceDetailsFields.push(...['rootDiskController', 'nicAdapter', 'keyboard'])
      }
      for (var detailsField of resourceDetailsFields) {
        var detailValue = this.resource?.details?.[detailsField] || null
        if (detailValue) {
          this.form[detailValue] = fieldValue
        }
      }
    },
    fetchData () {
      this.fetchOsTypes()
      this.fetchRootDiskControllerTypes(this.resource.hypervisor)
      this.fetchNicAdapterTypes()
      this.fetchKeyboardTypes()
      this.fetchUserdata()
      this.fetchUserdataPolicy()
    },
    isValidValueForKey (obj, key) {
      return key in obj && obj[key] != null && obj[key] !== undefined && obj[key] !== ''
    },
    fetchOsTypes () {
      const params = {}
      params.listAll = true
      this.osTypes.opts = []
      this.osTypes.loading = true
      api('listOsTypes', params).then(json => {
        const listOsTypes = json.listostypesresponse.ostype
        this.osTypes.opts = listOsTypes
      }).finally(() => {
        this.osTypes.loading = false
      })
    },
    fetchRootDiskControllerTypes (hyperVisor) {
      const controller = []
      this.rootDisk.opts = []

      if (hyperVisor === 'KVM') {
        controller.push({
          id: '',
          description: ''
        })
        controller.push({
          id: 'ide',
          description: 'ide'
        })
        controller.push({
          id: 'osdefault',
          description: 'osdefault'
        })
        controller.push({
          id: 'scsi',
          description: 'scsi'
        })
        controller.push({
          id: 'virtio',
          description: 'virtio'
        })
      } else if (hyperVisor === 'VMware') {
        controller.push({
          id: '',
          description: ''
        })
        controller.push({
          id: 'scsi',
          description: 'scsi'
        })
        controller.push({
          id: 'ide',
          description: 'ide'
        })
        controller.push({
          id: 'osdefault',
          description: 'osdefault'
        })
        controller.push({
          id: 'pvscsi',
          description: 'pvscsi'
        })
        controller.push({
          id: 'lsilogic',
          description: 'lsilogic'
        })
        controller.push({
          id: 'lsisas1068',
          description: 'lsilogicsas'
        })
        controller.push({
          id: 'buslogic',
          description: 'buslogic'
        })
      }

      this.rootDisk.opts = controller
    },
    fetchNicAdapterTypes () {
      const nicAdapterType = []
      nicAdapterType.push({
        id: '',
        description: ''
      })
      nicAdapterType.push({
        id: 'E1000',
        description: 'E1000'
      })
      nicAdapterType.push({
        id: 'PCNet32',
        description: 'PCNet32'
      })
      nicAdapterType.push({
        id: 'Vmxnet2',
        description: 'Vmxnet2'
      })
      nicAdapterType.push({
        id: 'Vmxnet3',
        description: 'Vmxnet3'
      })

      this.nicAdapterType.opts = nicAdapterType
    },
    fetchKeyboardTypes () {
      const keyboardType = []
      const keyboardOpts = this.$config.keyboardOptions || {}
      keyboardType.push({
        id: '',
        description: ''
      })

      Object.keys(keyboardOpts).forEach(keyboard => {
        keyboardType.push({
          id: keyboard,
          description: this.$t(keyboardOpts[keyboard])
        })
      })

      this.keyboardType.opts = keyboardType
    },
    fetchUserdataPolicy () {
      const userdataPolicy = []
      userdataPolicy.push({
        id: '',
        description: ''
      })
      userdataPolicy.push({
        id: 'allowoverride',
        description: 'allowoverride'
      })
      userdataPolicy.push({
        id: 'append',
        description: 'append'
      })
      userdataPolicy.push({
        id: 'denyoverride',
        description: 'denyoverride'
      })
      this.userdatapolicylist.opts = userdataPolicy
    },
    fetchUserdata () {
      const params = {}
      params.listAll = true

      this.userdata.opts = []
      this.userdata.loading = true

      api('listUserData', params).then(json => {
        const userdataIdAndName = []
        const userdataOpts = json.listuserdataresponse.userdata
        userdataIdAndName.push({
          id: '',
          name: ''
        })

        Object.values(userdataOpts).forEach(userdata => {
          userdataIdAndName.push({
            id: userdata.id,
            name: userdata.name
          })
        })

        this.userdata.opts = userdataIdAndName
      }).finally(() => {
        this.userdata.loading = false
      })
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        const params = {
          id: this.resource.id
        }
        const detailsField = ['rootDiskController', 'nicAdapter', 'keyboard']
        for (const key in values) {
          if (!this.isValidValueForKey(values, key)) continue
          if (detailsField.includes(key)) {
            params['details[0].' + key] = values[key]
            continue
          }
          params[key] = values[key]
        }
        api('updateTemplate', params).then(json => {
          if (this.userdataid !== null) {
            this.linkUserdataToTemplate(this.userdataid, json.updatetemplateresponse.template.id, this.userdatapolicy)
          }
          this.$message.success(`${this.$t('message.success.update.template')}: ${this.resource.name}`)
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
    closeAction () {
      this.$emit('close-action')
    },
    linkUserdataToTemplate (userdataid, templateid, userdatapolicy) {
      this.loading = true
      const params = {}
      if (userdataid && userdataid.length > 0) {
        params.userdataid = userdataid
      }
      params.templateid = templateid
      if (userdatapolicy) {
        params.userdatapolicy = userdatapolicy
      }
      api('linkUserDataToTemplate', params).then(json => {
        this.closeAction()
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 60vw;

    @media (min-width: 500px) {
      width: 450px;
    }
  }
</style>
