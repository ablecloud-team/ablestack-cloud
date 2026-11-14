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
  <MoldDialog class="image-userdata-dialog" size="md" :title="$t('label.action.edit.template')" :closable="!loading" :keyboard="!loading" @cancel="closeAction">
    <a-alert v-if="submitError" ref="error" tabindex="-1" type="error" show-icon role="alert" :message="submitError" />
    <div ref="content" class="image-userdata-content" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-form
        id="image-userdata-form"
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
            :placeholder="apiParams.name.description" />
        </a-form-item>
        <a-form-item name="displaytext" ref="displaytext">
          <template #label>
            <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
          </template>
          <a-input
            v-model:value="form.displaytext"
            :placeholder="apiParams.displaytext.description" />
        </a-form-item>
        <a-form-item v-for="field in ['sshkeyenabled', 'requireshvm'].filter(field => apiParams[field])" :key="field" :name="field">
          <template #label><tooltip-label :title="$t('label.' + field)" :tooltip="$t('message.template.' + field)" /></template>
          <a-switch v-model:checked="form[field]" :aria-label="$t('label.' + field)" />
        </a-form-item>
        <a-form-item name="passwordenabled" ref="passwordenabled">
          <template #label>
            <tooltip-label :title="$t('label.passwordenabled')" :tooltip="apiParams.passwordenabled.description"/>
          </template>
          <a-switch v-model:checked="form.passwordenabled" />
        </a-form-item>

        <a-row :gutter="12" v-if="['KVM', 'VMware'].includes(resource.hypervisor)">
          <a-col :md="24" :lg="resource.hypervisor === 'KVM' ? 24 : 12" v-if="resource.hypervisor === 'KVM' || (resource.hypervisor === 'VMware' && !resource.deployasis)">
            <a-form-item name="rootDiskController" ref="rootDiskController" :label="$t('label.rootdiskcontrollertype')">
              <a-select
                v-model:value="form.rootDiskController"
                :loading="rootDisk.loading"
                :placeholder="$t('label.rootdiskcontrollertype')">
                <a-select-option v-for="opt in rootDisk.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12" v-if="resource.hypervisor === 'VMware' && !resource.deployasis">
            <a-form-item name="nicAdapter" ref="nicAdapter" :label="$t('label.nicadaptertype')">
              <a-select
                v-model:value="form.nicAdapter"
                :placeholder="$t('label.nicadaptertype')">
                <a-select-option v-for="opt in nicAdapterType.opts" :key="opt.id">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
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
                <a-select-option v-for="opt in osTypes.opts" :key="opt.id" :label="opt.name || opt.description">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="24" v-if="hasOstypeidChanged()">
            <a-form-item name="forceupdateostype" ref="forceupdateostype">
              <template #label>
                <tooltip-label :title="$t('label.force.update.os.type')" :tooltip="apiParams.forceupdateostype.description"/>
              </template>
              <a-switch v-model:checked="form.forceupdateostype" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :md="24" :lg="12">
            <a-form-item
              name="userdataid"
              ref="userdataid"
              :label="$t('label.user.data')">
              <UserDataSelection v-if="canLinkUserData" :scope="resource" :value="userdataid || '0'" :disabled="loading" @select-user-data-item="id => { userdataid = id === '0' ? '' : id }" @ready="userDataSelectionReady = $event" />
              <span v-else>{{ resource.userdataname || resource.userdataid || '-' }}</span>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item ref="userdatapolicy" name="userdatapolicy">
              <template #label>
                <tooltip-label :title="$t('label.user.data.policy')" :tooltip="$t('label.user.data.policy.tooltip')"/>
              </template>
              <a-select
                showSearch
                v-model:value="userdatapolicy"
                :disabled="loading || !canLinkUserData"
                :placeholder="linkUserDataParams.userdatapolicy.description"
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option v-for="opt in userdatapolicylist.opts" :key="opt.id" :label="opt.id || opt.description">
                  {{ opt.id || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item name="isdynamicallyscalable" ref="isdynamicallyscalable">
          <template #label>
            <tooltip-label :title="$t('label.isdynamicallyscalable')" :tooltip="apiParams.isdynamicallyscalable.description"/>
          </template>
          <a-switch v-model:checked="form.isdynamicallyscalable" />
        </a-form-item>
        <a-form-item name="forcks" ref="forcks">
          <template #label>
            <tooltip-label :title="$t('label.forcks')" :tooltip="apiParams.forcks.description"/>
          </template>
          <a-switch v-model:checked="form.forcks" />
        </a-form-item>
        <a-form-item name="templatetype" ref="templatetype" v-if="isAdmin">
          <template #label>
            <tooltip-label :title="$t('label.templatetype')" :tooltip="apiParams.templatetype.description"/>
          </template>
          <span v-if="selectedTemplateType ==='SYSTEM'">
            <a-alert type="warning">
              <template #message>
                <span v-html="$t('message.template.type.change.warning')" />
              </template>
            </a-alert>
            <br/>
          </span>
          <a-select
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            v-model:value="form.templatetype"
            :placeholder="apiParams.templatetype.description"
            @change="val => { selectedTemplateType = val }">
            <a-select-option v-for="opt in templatetypes" :key="opt.id" :label="opt.name || opt.description">
              {{ opt.name || opt.description }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item ref="templatetag" name="templatetag" v-if="isAdmin">
          <template #label>
            <tooltip-label :title="$t('label.templatetag')" :tooltip="apiParams.templatetag.description"/>
          </template>
          <a-input
            v-model:value="form.templatetag"
            :placeholder="apiParams.templatetag.description"
            v-focus="currentForm !== 'Create'"/>
        </a-form-item>
        <a-form-item
          name="arch"
          ref="arch"
v-if="apiParams.arch">
          <template #label>
            <tooltip-label :title="$t('label.arch')" :tooltip="apiParams.arch.description"/>
          </template>
          <a-select
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            v-model:value="form.arch"
            :placeholder="apiParams.arch.description">
            <a-select-option v-for="opt in architectureTypes.opts" :key="opt.id" :label="opt.name || opt.description">
              {{ opt.name || opt.description }}
            </a-select-option>
          </a-select>
        </a-form-item>

        <button type="submit" hidden aria-hidden="true" tabindex="-1"></button>
      </a-form>
    </a-spin>
    </div>
    <template #footer>
      <a-button :disabled="loading" @click="closeAction">{{ $t('label.cancel') }}</a-button>
      <a-button :loading="loading" :disabled="canLinkUserData && !userDataSelectionReady" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { readTemplateResource } from '@/utils/templateSearch'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import MoldDialog from '@/components/view/MoldDialog'
import UserDataSelection from '@/views/compute/wizard/UserDataSelection'

export default {
  name: 'UpdateTemplate',
  components: {
    TooltipLabel, MoldDialog, UserDataSelection
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      templatetypes: [],
      emptyAllowedFields: ['templatetag'],
      rootDisk: {},
      nicAdapterType: {},
      keyboardType: {},
      osTypes: {},
      loading: false,
      selectedTemplateType: '',
      userdata: {},
      userDataSelectionReady: false,
      submitError: '',
      savedMetadata: '',
      userdataid: null,
      userdatapolicy: null,
      userdatapolicylist: {},
      architectureTypes: {},
      originalOstypeid: null,
      detailsFields: [],
      details: {}
    }
  },
  mounted () { this.$nextTick(() => { const input = this.$refs.content?.querySelector('input:not([disabled])'); if (input) input.focus() }) },
  computed: {
    canLinkUserData () { return ['listUserData', 'linkUserDataToTemplate'].every(api => api in this.$store.getters.apis) },
    blocksNavigation () { return this.loading }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateTemplate')
    this.isAdmin = ['Admin'].includes(this.$store.getters.userInfo.roletype)
    this.linkUserDataParams = this.$getApiParams('linkUserDataToTemplate')
  },
  created () {
    this.initForm()
    this.templatetypes = this.$fetchTemplateTypes(this.resource.hypervisor)
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
      const resourceFields = ['name', 'displaytext', 'arch', 'sshkeyenabled', 'requireshvm', 'passwordenabled', 'ostypeid', 'isdynamicallyscalable', 'userdataid', 'userdatapolicy', 'forcks']
      if (this.isAdmin) {
        resourceFields.push('templatetype')
        resourceFields.push('templatetag')
      }
      for (var field of resourceFields) {
        var fieldValue = this.resource[field]
        if (fieldValue !== undefined && fieldValue !== null) {
          switch (field) {
            case 'arch':
            case 'sshkeyenabled':
            case 'requireshvm':
              if (this.apiParams[field]) this.form[field] = fieldValue
              break
            case 'userdataid':
              this.userdataid = fieldValue
              break
            case 'userdatapolicy':
              this.userdatapolicy = fieldValue
              break
            case 'ostypeid':
              this.form[field] = fieldValue
              this.originalOstypeid = fieldValue
              break
            default:
              this.form[field] = fieldValue
              break
          }
        }
      }
      if (this.resource.hypervisor === 'KVM') {
        this.detailsFields.push('rootDiskController')
      } else if (this.resource.hypervisor === 'VMware' && !this.resource.deployasis) {
        this.detailsFields.push(...['rootDiskController', 'nicAdapter', 'keyboard'])
      }
    },
    fetchData () {
      this.fetchOsTypes()
      this.architectureTypes.opts = this.$fetchCpuArchitectureTypes()
      this.fetchRootDiskControllerTypes(this.resource.hypervisor)
      this.fetchNicAdapterTypes()
      this.fetchKeyboardTypes()
      this.fetchUserdataPolicy()
      this.fetchDetails()
    },
    isValidValueForKey (obj, key) {
      if (this.emptyAllowedFields.includes(key) && obj[key] === '') {
        return true
      }
      return key in obj && obj[key] != null && obj[key] !== undefined && obj[key] !== ''
    },
    fetchOsTypes () {
      const params = {}
      params.listAll = true
      this.osTypes.opts = []
      this.osTypes.loading = true
      getAPI('listOsTypes', params).then(json => {
        const listOsTypes = json.listostypesresponse.ostype
        this.osTypes.opts = listOsTypes
      }).catch(error => { this.submitError = error.message || this.$t('message.request.failed') }).finally(() => {
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
        controller.push({
          id: 'virtio-blk',
          description: 'virtio-blk'
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
    fetchDetails () {
      const params = {}
      params.id = this.resource.id

      readTemplateResource(getAPI, params, this.$store.getters).then(response => {
        if (response?.listtemplatesresponse?.template?.length > 0) {
          this.details = response.listtemplatesresponse.template[0].details
          if (this.details) {
            for (var detailsField of this.detailsFields) {
              var detailValue = this.details?.[detailsField] || null
              if (detailValue) {
                this.form[detailsField] = detailValue
              }
            }
          }
        }
      }).catch(error => { this.submitError = error.message || this.$t('message.request.failed') })
    },
    async handleSubmit (e) {
      if (e && e.preventDefault) e.preventDefault()
      if (this.loading || (this.canLinkUserData && !this.userDataSelectionReady)) return
      this.submitError = ''; this.loading = true
      try { await this.formRef.value.validate() } catch (error) {
        if (error.errorFields?.length) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
          this.$nextTick(() => { const input = this.$refs.content?.querySelector('.ant-form-item-has-error input'); if (input) input.focus() })
        }
        this.loading = false
        return
      }
      const values = toRaw(this.form)
      const params = { id: this.resource.id }
      if (this.details) Object.keys(this.details).forEach(detail => { params['details[0].' + detail] = this.details[detail] })
      for (const key in values) {
        if (!this.isValidValueForKey(values, key)) continue
        if (this.detailsFields.includes(key)) { params['details[0].' + key] = values[key]; continue }
        params[key] = values[key]
      }
      params.forceupdateostype = this.form.forceupdateostype || false
      const changed = this.canLinkUserData && ((this.userdataid || '') !== (this.resource.userdataid || '') || String(this.userdatapolicy || '').toUpperCase() !== String(this.resource.userdatapolicy || '').toUpperCase())
      const link = { templateid: this.resource.id, ignoreproject: true, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}), ...(this.userdataid ? { userdataid: this.userdataid, userdatapolicy: this.userdatapolicy || 'ALLOWOVERRIDE' } : {}) }
      this.loading = true
      const metadata = JSON.stringify(params)
      let updated = this.savedMetadata === metadata
      try {
        if (!updated) {
          await postAPI('updateTemplate', params, { preserveOnFailure: true })
          this.savedMetadata = metadata
          updated = true
        }
        if (changed) await postAPI('linkUserDataToTemplate', link, { preserveOnFailure: true })
        this.savedMetadata = ''
        this.$message.success(`${this.$t('message.success.update.template')}: ${this.resource.name}`)
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (error) {
        this.submitError = (updated ? this.$t('message.userdata.image.partial') + ' ' : '') + (error.response?.headers?.['x-description'] || error.message || this.$t('message.request.failed'))
        if (updated) this.$emit('refresh-data')
        this.$nextTick(() => {
          const alert = this.$refs.error?.$el
          if (alert) { alert.scrollIntoView({ block: 'nearest' }); alert.focus({ preventScroll: true }) }
        })
      } finally { this.loading = false }
    },
    closeAction () { if (!this.loading) this.$emit('close-action') },
    hasOstypeidChanged () {
      return this.form.ostypeid !== this.originalOstypeid
    }
  }
}
</script>

<style scoped lang="less">
.image-userdata-content { min-width: 0; }
.image-userdata-content :deep(.ant-form-item) { margin-bottom: 20px; }
.image-userdata-dialog :deep(.ant-alert) { margin-bottom: 16px; }
</style>
