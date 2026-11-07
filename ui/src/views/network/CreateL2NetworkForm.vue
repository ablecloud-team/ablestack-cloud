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
  <a-spin :spinning="loading">
    <div class="form-layout" v-ctrl-enter="handleSubmit">
      <div v-if="isNsxEnabled">
        <a-alert type="warning">
          <template #message>
            <span v-html="$t('message.l2.network.unsupported.for.nsx')" />
          </template>
        </a-alert>
      </div>
      <div v-else class="form">
        <a-form
          :id="formName"
          :name="formName"
          :ref="formRef"
          :model="form"
          :rules="rules"
          layout="vertical"
          @finish="handleSubmit"
         >
          <a-form-item name="name" ref="name">
            <template #label>
              <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
            </template>
            <a-input
              v-model:value="form.name"
              :placeholder="apiParams.name.description"
              v-focus="true"/>
          </a-form-item>
          <a-form-item name="displaytext" ref="displaytext">
            <template #label>
              <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
            </template>
            <a-textarea
              v-model:value="form.displaytext"
              :auto-size="{ minRows: 2, maxRows: 5 }"
              :placeholder="apiParams.displaytext.description"/>
          </a-form-item>
          <a-form-item name="zoneid" ref="zoneid">
            <template #label>
              <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
            </template>
            <a-select
              v-model:value="form.zoneid"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="zoneLoading"
              :disabled="zoneLoading || actionLoading"
              :placeholder="apiParams.zoneid.description"
              @change="val => handleZoneChange(zones.find(zone => zone.id === val))">
              <a-select-option v-for="opt in zones" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                <span>
                  <resource-icon v-if="opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <global-outlined v-else style="margin-right: 5px" />
                  {{ opt.name || opt.description }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <ownership-selection v-if="chooseOwner" safe-scope :disabled="actionLoading" @fetch-owner="fetchOwnerOptions"/>
          <a-form-item name="networkofferingid" ref="networkofferingid">
            <template #label>
              <tooltip-label :title="$t('label.networkofferingid')" :tooltip="apiParams.networkofferingid.description"/>
            </template>
            <a-select
              v-model:value="form.networkofferingid"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="networkOfferingLoading"
              :disabled="!ownerReady || zoneLoading || networkOfferingLoading || actionLoading"
              :placeholder="apiParams.networkofferingid.description"
              @change="val => handleNetworkOfferingChange(networkOfferings.find(offering => offering.id === val))">
              <a-select-option v-for="opt in networkOfferings" :key="opt.id" :value="opt.id" :label="opt.displaytext || opt.name || opt.description">
                {{ opt.displaytext || opt.name || opt.description }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-alert v-if="choiceError" type="error" show-icon class="l2-network-notice" :message="$t('message.request.failed')">
            <template #action>
              <a-button size="small" @click="choiceError === 'zone' ? fetchZoneData() : updateOfferings()">{{ $t('label.refresh') }}</a-button>
            </template>
          </a-alert>
          <a-alert v-else-if="ownerReady && selectedZone.id && !networkOfferingLoading && !networkOfferings.length" type="info" show-icon class="l2-network-notice" :message="$t('message.l2.network.no.offering')" />
          <a-alert
            v-if="selectedNetworkOffering.id"
            type="info"
            show-icon
            class="l2-network-notice"
            :message="$t(configDrive ? 'message.l2.network.configdrive' : 'message.l2.network.basic')" />
          <network-offering-diagnostics ref="offeringDiagnostics" :offering="selectedNetworkOffering" :zone-id="selectedZone?.id" @state="providerCheck = $event" />
          <a-form-item
            v-if="specifyVlan"
            name="vlanid"
            ref="vlanid">
            <template #label>
              <tooltip-label :title="$t('label.vlan')" :tooltip="apiParams.vlan ? apiParams.vlan.description : $t('label.vlanid')"/>
            </template>
            <a-input
              v-model:value="form.vlanid"
              :placeholder="apiParams.vlan ? apiParams.vlan.description : $t('label.vlanid')"/>
          </a-form-item>
          <a-form-item
            v-if="specifyVlan && canBypass"
            name="bypassvlanoverlapcheck"
            ref="bypassvlanoverlapcheck">
            <template #label>
              <tooltip-label :title="$t('label.bypassvlanoverlapcheck')" :tooltip="apiParams.bypassvlanoverlapcheck ? apiParams.bypassvlanoverlapcheck.description : null"/>
            </template>
            <a-switch v-model:checked="form.bypassvlanoverlapcheck" />
          </a-form-item>
          <a-form-item
            v-if="canUsePvlan"
            name="isolatedpvlantype"
            ref="isolatedpvlantype">
            <template #label>
              <tooltip-label :title="$t('label.isolatedpvlantype')" :tooltip="apiParams.isolatedpvlantype.description"/>
            </template>
            <a-radio-group
              v-model:value="form.isolatedpvlantype"
              class="l2-pvlan-options"
              buttonStyle="solid"
              @change="form.isolatedpvlanid = undefined">
              <a-radio-button value="none">
                {{ $t('label.none') }}
              </a-radio-button>
              <a-radio-button value="community">
                {{ $t('label.l2.pvlan.community') }}
              </a-radio-button>
              <a-radio-button value="isolated">
                {{ $t('label.l2.pvlan.isolated') }}
              </a-radio-button>
              <a-radio-button value="promiscuous">
                {{ $t('label.l2.pvlan.promiscuous') }}
              </a-radio-button>
            </a-radio-group>
          </a-form-item>
          <a-form-item
            v-if="canUsePvlan && ['community', 'isolated'].includes(form.isolatedpvlantype)"
            name="isolatedpvlanid"
            ref="isolatedpvlanid">
            <template #label>
              <tooltip-label :title="$t('label.isolatedpvlanid')" :tooltip="apiParams.isolatedpvlan.description"/>
            </template>
            <a-input
              v-model:value="form.isolatedpvlanid"
              :placeholder="apiParams.isolatedpvlan.description"/>
          </a-form-item>
          <a-alert v-if="specifyVlan && !canUsePvlan" type="info" show-icon class="l2-network-notice" :message="$t('message.l2.network.pvlan.unavailable')" />
          <a-alert v-if="tagError" type="error" show-icon class="l2-network-notice" :message="$t('message.l2.network.' + tagError)" />
          <MoldDialogActions v-if="!submitHandler && active" :busy="actionLoading">
            <a-button
              :loading="actionLoading"
              @click="closeAction">
              {{ $t('label.cancel') }}
            </a-button>
            <a-button
              :loading="actionLoading"
              :disabled="!canSubmit"
              type="primary"
              :form="formName"
              htmlType="submit"
              @click="handleSubmit">
              {{ $t('label.ok') }}
            </a-button>
          </MoldDialogActions>
        </a-form>
      </div>
    </div>
  </a-spin>
</template>

<script>
import { ref, reactive, getCurrentInstance } from 'vue'
import { getAPI, postAPI } from '@/api'
import { isAdmin, isAdminOrDomainAdmin } from '@/role'
import { l2ConfigDrive, l2TagFields } from '@/utils/l2Network'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import NetworkOfferingDiagnostics from '@/components/network/NetworkOfferingDiagnostics.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection.vue'

export default {
  name: 'CreateL2NetworkForm',
  components: {
    NetworkOfferingDiagnostics,
    MoldDialogActions,
    OwnershipSelection,
    TooltipLabel,
    ResourceIcon
  },
  props: {
    active: { type: Boolean, default: true },
    submitHandler: { type: Function, default: null },
    loading: {
      type: Boolean,
      default: false
    },
    resource: {
      type: Object,
      default: () => { return {} }
    }
  },
  setup () { return { formName: 'network-create-l2-' + getCurrentInstance().uid } },
  data () {
    return {
      actionLoading: false,
      providerCheck: { blocked: false },
      owner: {},
      ownerReady: false,
      zoneRequest: 0,
      offeringRequest: 0,
      choiceError: '',
      tagError: '',
      zones: [],
      zoneLoading: false,
      selectedZone: {},
      networkOfferings: [],
      networkOfferingLoading: false,
      selectedNetworkOffering: {}
    }
  },
  watch: {
    actionLoading (value) { this.$emit('busy', value) },
    activeProject () {
      this.setImplicitOwner()
      this.updateOfferings()
    },
    'form.vlanid' () { this.tagError = '' },
    'form.isolatedpvlanid' () { this.tagError = '' },
    'form.isolatedpvlantype' () { this.tagError = '' },
    resource: {
      deep: true,
      handler () {
        this.setImplicitOwner()
        this.fetchZoneData()
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createNetwork')
  },
  created () {
    this.initForm()
    this.setImplicitOwner()
    this.fetchZoneData()
  },
  beforeUnmount () {
    this.zoneRequest++
    this.offeringRequest++
  },
  computed: {
    activeProject () { return this.$store.getters.project?.id },
    chooseOwner () { return !this.submitHandler && isAdminOrDomainAdmin() && !this.activeProject },
    configDrive () { return l2ConfigDrive(this.selectedNetworkOffering) },
    specifyVlan () { return isAdmin() && !!this.selectedNetworkOffering.specifyvlan },
    isolation () { return this.providerCheck.network?.isolation },
    canBypass () { return isAdmin() && !!this.apiParams.bypassvlanoverlapcheck },
    canUsePvlan () {
      return this.specifyVlan && !this.selectedZone.isnsxenabled && !this.selectedZone.securitygroupsenabled &&
        this.isolation !== 'vxlan' && this.selectedNetworkOffering.internetprotocol !== 'IPv6' &&
        this.selectedNetworkOffering.internetprotocol !== 'DualStack'
    },
    canSubmit () {
      return this.active && !this.loading && !this.actionLoading && this.ownerReady && !this.zoneLoading &&
        !this.networkOfferingLoading && !this.choiceError && !this.providerCheck.blocked &&
        !!this.selectedZone.id && !!this.selectedNetworkOffering.id
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        isolatedpvlantype: 'none'
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.name') }],
        zoneid: [{ required: true, message: this.$t('message.error.select') }],
        networkofferingid: [{ required: true, message: this.$t('message.error.select') }],
        vlanid: [{ required: true, message: this.$t('message.please.enter.value') }]
      })
    },
    setImplicitOwner () {
      if (this.chooseOwner) {
        this.ownerReady = false
        return
      }
      const scope = this.submitHandler ? this.resource : {}
      const projectid = this.submitHandler ? scope.projectid : this.activeProject
      this.owner = projectid ? { projectid } : { account: scope.account, domainid: scope.domainid }
      this.ownerReady = true
    },
    async fetchZoneData () {
      const request = ++this.zoneRequest
      const previous = this.form.zoneid
      this.zoneLoading = true
      this.choiceError = ''
      this.selectedZone = {}
      this.updateOfferings()
      const params = { showicon: true }
      if (this.resource.zoneid && (this.submitHandler || this.$route.name === 'deployVirtualMachine' || this.$route.path.startsWith('/backup'))) {
        params.id = this.resource.zoneid
      }
      try {
        const json = await getAPI('listZones', params, { preserveOnFailure: true })
        if (request !== this.zoneRequest) return
        this.zones = (json.listzonesresponse.zone || []).filter(zone =>
          zone.networktype === 'Advanced')
        const selected = this.zones.find(zone => zone.id === previous) || this.zones[0]
        this.form.zoneid = selected?.id
        this.handleZoneChange(selected)
      } catch (_) {
        if (request === this.zoneRequest) this.choiceError = 'zone'
      } finally {
        if (request === this.zoneRequest) this.zoneLoading = false
      }
    },
    handleZoneChange (zone) {
      this.selectedZone = zone || {}
      this.updateOfferings()
    },
    fetchOwnerOptions (options) {
      this.ownerReady = options.ready
      this.owner = options.selectedAccountType === 'Project'
        ? { projectid: options.selectedProject, domainid: options.selectedDomain }
        : { account: options.selectedAccount, domainid: options.selectedDomain }
      this.updateOfferings()
    },
    async updateOfferings () {
      const request = ++this.offeringRequest
      const zone = this.selectedZone
      const previous = this.form.networkofferingid
      this.networkOfferings = []
      this.form.networkofferingid = undefined
      this.handleNetworkOfferingChange()
      this.networkOfferingLoading = !!zone.id && this.ownerReady
      if (!this.networkOfferingLoading) return
      this.choiceError = ''
      const params = { zoneid: zone.id, guestiptype: 'L2', forvpc: false, state: 'Enabled' }
      if (isAdminOrDomainAdmin() && this.owner.domainid) params.domainid = this.owner.domainid
      if (!isAdmin()) params.specifyvlan = false
      try {
        const json = await getAPI('listNetworkOfferings', params, { preserveOnFailure: true })
        if (request !== this.offeringRequest) return
        this.networkOfferings = (json.listnetworkofferingsresponse.networkoffering || []).filter(offering =>
          !!offering.fornsx === !!zone.isnsxenabled)
        const selected = this.networkOfferings.find(offering => offering.id === previous) || this.networkOfferings[0]
        this.form.networkofferingid = selected?.id
        this.handleNetworkOfferingChange(selected)
      } catch (_) {
        if (request === this.offeringRequest) this.choiceError = 'offering'
      } finally {
        if (request === this.offeringRequest) this.networkOfferingLoading = false
      }
    },
    handleNetworkOfferingChange (offering = {}) {
      this.selectedNetworkOffering = offering
      this.form.vlanid = undefined
      this.form.isolatedpvlantype = 'none'
      this.form.isolatedpvlanid = undefined
      this.form.bypassvlanoverlapcheck = false
      this.tagError = ''
    },
    async handleSubmit () {
      if (!this.canSubmit) return
      let submitting = false
      try {
        await this.formRef.value.validate()
        if (!this.canSubmit) return
        submitting = true
        this.actionLoading = true
        const scope = JSON.stringify([this.form, this.owner, this.selectedZone, this.selectedNetworkOffering, this.offeringRequest])
        if (!await this.$refs.offeringDiagnostics.validate() ||
            scope !== JSON.stringify([this.form, this.owner, this.selectedZone, this.selectedNetworkOffering, this.offeringRequest])) return
        const tags = l2TagFields(this.form, {
          specifyVlan: this.specifyVlan,
          canBypass: this.canBypass,
          canUsePvlan: this.canUsePvlan,
          isolation: this.isolation
        })
        this.tagError = tags.error || ''
        if (tags.error) {
          this.formRef.value.scrollToField(tags.error === 'pvlan.secondary' || tags.error === 'pvlan.same' ? 'isolatedpvlanid' : 'vlanid')
          return
        }
        const params = {
          zoneId: this.selectedZone.id,
          name: this.form.name,
          displayText: this.form.displaytext,
          networkOfferingId: this.selectedNetworkOffering.id,
          ...this.owner,
          ...tags.params
        }
        if (this.submitHandler) {
          this.submitHandler(params)
          return
        }
        await postAPI('createNetwork', params, { preserveOnFailure: true })
        this.$notification.success({
          message: 'Network',
          description: this.$t('message.success.create.l2.network')
        })
        this.$emit('refresh-data')
        this.closeAction()
      } catch (error) {
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      } finally {
        if (submitting) this.actionLoading = false
      }
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style lang="less" scoped>
.form-layout {
  width: 100%;
  min-width: 0;

  .ant-tag {
    margin-bottom: 10px;
  }

  :deep(.custom-time-select) .ant-time-picker {
    width: 100%;
  }

  :deep(.ant-divider-horizontal) {
    margin-top: 0;
  }
}

.l2-network-notice {
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}

.l2-pvlan-options {
  display: flex;
  flex-wrap: wrap;
  row-gap: 8px;
}

.form {
  margin: 10px 0;
}

.tagsTitle {
  font-weight: 500;
  color: rgba(0, 0, 0, 0.85);
  margin-bottom: 12px;
}
</style>
