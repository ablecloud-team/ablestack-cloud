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
    ref="internalFormRef"
    :model="form"
    :rules="rules"
    @finish="onInternalSubmit"
    layout="vertical">
    <a-form-item name="name" ref="name">
      <template #label>
        <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
      </template>
      <a-input
        v-focus="true"
        v-model:value="form.name"
        :placeholder="apiParams.name.description"/>
    </a-form-item>
    <a-form-item name="displaytext" ref="displaytext">
      <template #label>
        <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
      </template>
      <a-input
        v-model:value="form.displaytext"
        :placeholder="apiParams.displaytext.description"/>
    </a-form-item>
    <a-form-item name="storagetype" ref="storagetype">
      <template #label>
        <tooltip-label :title="$t('label.storagetype')" :tooltip="apiParams.storagetype.description"/>
      </template>
      <a-radio-group
        v-model:value="form.storagetype"
        buttonStyle="solid">
        <a-radio-button value="shared">
          {{ $t('label.shared') }}
        </a-radio-button>
        <a-radio-button value="local">
          {{ $t('label.local') }}
        </a-radio-button>
      </a-radio-group>
    </a-form-item>
    <AllocationPolicyEditor ref="allocationEditor" :form="form" @change="Object.assign(form, $event)" />
    <a-form-item name="encryptdisk" ref="encryptdisk">
      <template #label>
        <tooltip-label :title="$t('label.encrypt')" :tooltip="apiParams.encrypt.description" />
      </template>
      <a-switch v-model:checked="form.encryptdisk" />
    </a-form-item>
    <a-form-item name="disksizestrictness" ref="disksizestrictness">
      <template #label>
        <tooltip-label :title="$t('label.disksizestrictness')" :tooltip="apiParams.disksizestrictness.description" />
      </template>
      <a-switch v-model:checked="form.disksizestrictness" />
    </a-form-item>
    <a-form-item name="customdisksize" ref="customdisksize">
      <template #label>
        <tooltip-label :title="$t('label.customdisksize')" :tooltip="apiParams.customized.description"/>
      </template>
      <a-switch v-model:checked="form.customdisksize" />
    </a-form-item>
    <a-form-item v-if="!form.customdisksize" name="disksize" ref="disksize">
      <template #label>
        <tooltip-label :title="$t('label.disksize')" :tooltip="apiParams.disksize.description"/>
      </template>
      <a-input
        v-model:value="form.disksize"
        :placeholder="apiParams.disksize.description"/>
    </a-form-item>
    <a-form-item name="qostype" ref="qostype" :label="$t('label.qostype')">
      <a-radio-group
        v-model:value="form.qostype"
        buttonStyle="solid">
        <a-radio-button value="">
          {{ $t('label.none') }}
        </a-radio-button>
        <a-radio-button value="hypervisor">
          {{ $t('label.hypervisor') }}
        </a-radio-button>
        <a-radio-button value="storage">
          {{ $t('label.storage') }}
        </a-radio-button>
      </a-radio-group>
    </a-form-item>
    <DiskQosFields v-if="form.qostype === 'hypervisor'" ref="qosEditor" :form="form" :api-params="apiParams" />
    <a-form-item v-if="form.qostype === 'storage'" name="iscustomizeddiskiops" ref="iscustomizeddiskiops">
      <template #label>
        <tooltip-label :title="$t('label.iscustomizeddiskiops')" :tooltip="apiParams.customizediops.description"/>
      </template>
      <a-switch v-model:checked="form.iscustomizeddiskiops" />
    </a-form-item>
    <a-form-item v-if="form.qostype === 'storage' && !form.iscustomizeddiskiops" name="diskiopsmin" ref="diskiopsmin">
      <template #label>
        <tooltip-label :title="$t('label.diskiopsmin')" :tooltip="apiParams.miniops.description"/>
      </template>
      <a-input
        v-model:value="form.diskiopsmin"
        :placeholder="apiParams.miniops.description"/>
    </a-form-item>
    <a-form-item v-if="form.qostype === 'storage' && !form.iscustomizeddiskiops" name="diskiopsmax" ref="diskiopsmax">
      <template #label>
        <tooltip-label :title="$t('label.diskiopsmax')" :tooltip="apiParams.maxiops.description"/>
      </template>
      <a-input
        v-model:value="form.diskiopsmax"
        :placeholder="apiParams.maxiops.description"/>
    </a-form-item>
    <a-form-item v-if="form.qostype === 'storage'" name="hypervisorsnapshotreserve" ref="hypervisorsnapshotreserve">
      <template #label>
        <tooltip-label :title="$t('label.hypervisorsnapshotreserve')" :tooltip="apiParams.hypervisorsnapshotreserve.description"/>
      </template>
      <a-input
        v-model:value="form.hypervisorsnapshotreserve"
        :placeholder="apiParams.hypervisorsnapshotreserve.description"/>
    </a-form-item>
    <a-form-item v-if="!form.shareable" name="kvdoenable" ref="kvdoenable">
      <template #label>
        <tooltip-label :title="$t('label.kvdoenable')" :tooltip="apiParams.kvdoenable ? apiParams.kvdoenable.description : ''"/>
      </template>
      <a-switch v-model:checked="form.kvdoenable" />
    </a-form-item>
    <a-form-item v-if="!form.kvdoenable" name="shareable" ref="shareable">
      <template #label>
        <tooltip-label :title="$t('label.shareable')" :tooltip="apiParams.shareable ? apiParams.shareable.description : ''"/>
      </template>
      <a-switch v-model:checked="form.shareable" />
    </a-form-item>
    <a-form-item v-if="!form.shareable" name="writecachetype" ref="writecachetype">
      <template #label>
        <tooltip-label :title="$t('label.writecachetype')" :tooltip="apiParams.cachemode.description"/>
      </template>
      <a-radio-group
        v-model:value="form.writecachetype"
        buttonStyle="solid"
        @change="selected => { handleWriteCacheTypeChange(selected.target.value) }">
        <a-radio-button value="writeback">
          {{ $t('label.writeback') }}
        </a-radio-button>
        <a-radio-button value="writethrough">
          {{ $t('label.writethrough') }}
        </a-radio-button>
        <a-radio-button value="none">
          {{ $t('label.nodiskcache') }}
        </a-radio-button>
        <a-radio-button value="hypervisor_default">
          {{ $t('label.hypervisor.default') }}
        </a-radio-button>
      </a-radio-group>
    </a-form-item>
    <a-form-item v-if="isAdmin() || isDomainAdminAllowedToInformTags" name="tags" ref="tags">
      <template #label>
        <tooltip-label :title="$t('label.storagetags')" :tooltip="apiParams.tags.description"/>
      </template>
      <a-select
        :getPopupContainer="(trigger) => trigger.parentNode"
        mode="tags"
        v-model:value="form.tags"
        showSearch
        optionFilterProp="value"
        :filterOption="(input, option) => {
          return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
        }"
        :loading="storageTagLoading"
        :placeholder="apiParams.tags.description"
        v-if="isAdmin() || isDomainAdminAllowedToInformTags">
        <a-select-option v-for="(opt) in storageTags" :key="opt">
          {{ opt }}
        </a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item :label="$t('label.ispublic')" v-show="isAdmin()" name="ispublic" ref="ispublic">
      <a-switch v-model:checked="form.ispublic" />
    </a-form-item>
    <a-form-item v-if="!form.ispublic" name="domainid" ref="domainid">
      <template #label>
        <tooltip-label :title="$t('label.domainid')" :tooltip="apiParams.domainid.description"/>
      </template>
      <a-select
        mode="multiple"
        :getPopupContainer="(trigger) => trigger.parentNode"
        v-model:value="form.domainid"
        showSearch
        optionFilterProp="label"
        :filterOption="(input, option) => {
          return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
        }"
        :loading="domainLoading"
        :placeholder="apiParams.domainid.description">
        <a-select-option v-for="(opt, optIndex) in domains" :key="optIndex" :label="opt.path || opt.name || opt.description">
          <span>
            <resource-icon v-if="opt && opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
            <block-outlined v-else style="margin-right: 5px" />
            {{ opt.path || opt.name || opt.description }}
          </span>
        </a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item name="zoneid" ref="zoneid">
      <template #label>
        <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
      </template>
      <a-select
        id="zone-selection"
        mode="multiple"
        :getPopupContainer="(trigger) => trigger.parentNode"
        v-model:value="form.zoneid"
        showSearch
        optionFilterProp="label"
        :filterOption="(input, option) => {
          return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
        }"
        @select="val => fetchvSphereStoragePolicies(val)"
        :loading="zoneLoading"
        :placeholder="apiParams.zoneid.description">
        <a-select-option v-for="(opt, optIndex) in zones" :key="optIndex" :label="opt.name || opt.description">
          <span>
            <resource-icon v-if="opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
            <global-outlined v-else style="margin-right: 5px"/>
            {{ opt.name || opt.description }}
          </span>
        </a-select-option>
      </a-select>
    </a-form-item>
    <a-form-item v-if="'listVsphereStoragePolicies' in $store.getters.apis && storagePolicies !== null" name="storagepolicy" ref="storagepolicy">
      <template #label>
        <tooltip-label :title="$t('label.vmware.storage.policy')" :tooltip="apiParams.storagepolicy.description"/>
      </template>
      <a-select
        :getPopupContainer="(trigger) => trigger.parentNode"
        v-model:value="form.storagepolicy"
        :placeholder="apiParams.storagepolicy.description"
        showSearch
        optionFilterProp="label"
        :filterOption="(input, option) => {
          return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
        }">
        <a-select-option v-for="policy in storagePolicies" :key="policy.id" :label="policy.name || policy.id || ''">
          {{ policy.name || policy.id }}
        </a-select-option>
      </a-select>
    </a-form-item>
    <slot name="form-actions"></slot>
  </a-form>
</template>

<script>
import { reactive, toRaw } from 'vue'
import { getAPI } from '@/api'
import { isAdmin } from '@/role'
import { mixinForm } from '@/utils/mixin'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import store from '@/store'
import DiskQosFields from '@/components/offering/DiskQosFields'
import { performanceFormValues, qosFields } from '@/utils/offeringQos'
import AllocationPolicyEditor from '@/components/offering/AllocationPolicyEditor'
import AllocationSummary from '@/components/view/AllocationSummary'
import { BlockOutlined, GlobalOutlined } from '@ant-design/icons-vue'

export default {
  name: 'DiskOfferingForm',
  mixins: [mixinForm],
  components: {
    AllocationPolicyEditor,
    DiskQosFields,
    AllocationSummary,
    ResourceIcon,
    TooltipLabel,
    BlockOutlined,
    GlobalOutlined
  },
  props: {
    initialValues: {
      type: Object,
      default: () => ({})
    },
    apiParams: {
      type: Object,
      default: () => ({})
    },
    isAdmin: {
      type: Function,
      default: () => false
    }
  },
  data () {
    return {
      internalFormRef: null,
      form: reactive(Object.assign({
        storagetype: 'shared',
        provisioningtype: 'thin',
        allocationmode: 'legacy',
        customdisksize: true,
        writecachetype: 'writeback',
        qostype: '',
        ispublic: true,
        disksizestrictness: false,
        encryptdisk: false,
        kvdoenable: false,
        shareable: false
      }, this.initialValues || {})),
      rules: reactive({}),
      storageTags: [],
      storagePolicies: null,
      storageTagLoading: false,
      isPublic: true,
      domains: [],
      domainLoading: false,
      zones: [],
      zoneLoading: false,
      isDomainAdminAllowedToInformTags: false
    }
  },
  watch: {
    initialValues: {
      deep: true,
      handler (values) {
        this.applyInitialValues(values)
      }
    }
  },
  created () {
    this.zones = [{ id: null, name: this.$t('label.all.zone') }]
    this.initForm()
    this.applyInitialValues(this.initialValues)
    this.fetchData()
    if (this.form.ispublic === undefined) {
      this.form.ispublic = isAdmin()
    }
  },
  methods: {
    initForm () {
      this.formRef = this.$refs.internalFormRef
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        disksize: [
          { required: true, message: this.$t('message.error.required.input') },
          { type: 'number', validator: this.validateNumber }
        ],
        diskbytesreadrate: [{ type: 'number', validator: this.validateNumber }],
        diskbytesreadratemax: [{ type: 'number', validator: this.validateNumber }],
        diskbyteswriterate: [{ type: 'number', validator: this.validateNumber }],
        diskbyteswriteratemax: [{ type: 'number', validator: this.validateNumber }],
        diskiopsreadrate: [{ type: 'number', validator: this.validateNumber }],
        diskiopswriterate: [{ type: 'number', validator: this.validateNumber }],
        diskiopsmin: [{ type: 'number', validator: this.validateNumber }],
        diskiopsmax: [{ type: 'number', validator: this.validateNumber }],
        hypervisorsnapshotreserve: [{ type: 'number', validator: this.validateNumber }],
        domainid: [{ type: 'array', required: true, message: this.$t('message.error.select') }],
        zoneid: [{
          type: 'array',
          validator: async (rule, value) => {
            if (value && value.length > 1 && value.indexOf(0) !== -1) {
              return Promise.reject(this.$t('message.error.zone.combined'))
            }
            return Promise.resolve()
          }
        }]
      })
    },
    applyInitialValues (values) {
      if (!values) {
        return
      }
      Object.assign(this.form, values)
      this.normalizeQosType()
      this.syncUiStateFromForm()
    },
    normalizeQosType () {
      const hasHypervisorQos = qosFields.some(field => Number(this.form[field.form]) > 0)
      const hasStorageQos = [
        this.form.diskiopsmin,
        this.form.diskiopsmax,
        this.form.hypervisorsnapshotreserve
      ].some(value => value !== undefined && value !== null && value !== '' && Number(value) > 0) || this.form.iscustomizeddiskiops === true

      if (hasHypervisorQos) {
        this.form.qostype = 'hypervisor'
      } else if (hasStorageQos) {
        this.form.qostype = 'storage'
      }
    },
    syncUiStateFromForm () {
      this.isPublic = this.form.ispublic !== false
      this.normalizeDomainSelection()
      this.normalizeZoneSelection()
    },
    fetchData () {
      this.fetchDomainData()
      this.fetchZoneData()
      if (isAdmin()) {
        this.fetchStorageTagData()
      }
      if (this.isDomainAdmin()) {
        this.checkIfDomainAdminIsAllowedToInformTag()
        if (this.isDomainAdminAllowedToInformTags) {
          this.fetchStorageTagData()
        }
      }
    },
    handleWriteCacheTypeChange (val) {
      this.form.writecachetype = val
    },
    isDomainAdmin () {
      return ['DomainAdmin'].includes(this.$store.getters.userInfo.roletype)
    },
    checkIfDomainAdminIsAllowedToInformTag () {
      const params = { id: store.getters.userInfo.accountid }
      getAPI('isAccountAllowedToCreateOfferingsWithTags', params).then(json => {
        this.isDomainAdminAllowedToInformTags = json.isaccountallowedtocreateofferingswithtagsresponse.isallowed.isallowed
      })
    },
    fetchDomainData () {
      const params = {}
      params.listAll = true
      params.showicon = true
      params.details = 'min'
      this.domainLoading = true
      getAPI('listDomains', params).then(json => {
        const listDomains = json.listdomainsresponse.domain
        this.domains = this.domains.concat(listDomains)
        this.normalizeDomainSelection()
      }).finally(() => {
        this.domainLoading = false
      })
    },
    fetchZoneData () {
      const params = {}
      params.showicon = true
      this.zoneLoading = true
      getAPI('listZones', params).then(json => {
        const listZones = json.listzonesresponse.zone
        if (listZones) {
          this.zones = this.zones.concat(listZones)
        }
        this.normalizeZoneSelection()
      }).finally(() => {
        this.zoneLoading = false
      })
    },
    fetchStorageTagData () {
      const params = {}
      this.storageTagLoading = true
      getAPI('listStorageTags', params).then(json => {
        const tags = json.liststoragetagsresponse.storagetag || []
        for (const tag of tags) {
          if (!this.storageTags.includes(tag.name)) {
            this.storageTags.push(tag.name)
          }
        }
      }).finally(() => {
        this.storageTagLoading = false
      })
    },
    fetchvSphereStoragePolicies (zoneIndex) {
      if (zoneIndex === 0 || this.form.zoneid.length > 1) {
        this.storagePolicies = null
        return
      }
      const zoneid = this.zones[zoneIndex].id
      if ('importVsphereStoragePolicies' in this.$store.getters.apis) {
        this.storagePolicies = []
        getAPI('listVsphereStoragePolicies', {
          zoneid: zoneid
        }).then(response => {
          this.storagePolicies = response.listvspherestoragepoliciesresponse.StoragePolicy || []
        })
      }
    },
    normalizeDomainSelection () {
      if (!Array.isArray(this.form.domainid) || this.form.domainid.length === 0 || this.domains.length === 0) {
        return
      }
      if (typeof this.form.domainid[0] === 'number') {
        return
      }
      const domainIndexes = this.form.domainid.map(domainId => {
        return this.domains.findIndex(domain => domain.id === domainId)
      }).filter(index => index >= 0)
      if (domainIndexes.length > 0) {
        this.form.domainid = domainIndexes
      }
    },
    normalizeZoneSelection () {
      if (!Array.isArray(this.form.zoneid) || this.form.zoneid.length === 0 || this.zones.length === 0) {
        return
      }
      if (typeof this.form.zoneid[0] === 'number') {
        return
      }
      const zoneIndexes = this.form.zoneid.map(zoneId => {
        return this.zones.findIndex(zone => zone.id === zoneId)
      }).filter(index => index >= 0)
      if (zoneIndexes.length > 0) {
        this.form.zoneid = zoneIndexes
        this.fetchvSphereStoragePolicies(zoneIndexes[0])
      }
    },
    validate () {
      return (this.$refs.allocationEditor?.validate() || Promise.resolve()).then(() => this.$refs.qosEditor?.validate()).then(() => this.$refs.internalFormRef.validate()).then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        // Allocation controls live in a child component, so they do not have parent form refs.
        return {
          ...values,
          ...performanceFormValues(formRaw),
          allocationmode: formRaw.allocationmode,
          allocationpolicy: formRaw.allocationpolicy,
          allocationformat: formRaw.allocationformat,
          provisioningtype: formRaw.provisioningtype
        }
      })
    },
    onInternalSubmit () {
      this.$emit('submit')
    },
    async validateNumber (rule, value) {
      if (value && (isNaN(value) || value <= 0)) {
        return Promise.reject(this.$t('message.error.number'))
      }
      return Promise.resolve()
    }
  }
}
</script>
