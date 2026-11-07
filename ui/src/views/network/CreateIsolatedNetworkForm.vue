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
      <div class="form">
        <a-form
          :id="formName"
          :name="formName"
          :ref="formRef"
          :model="form"
          :rules="rules"
          layout="vertical"
          @finish="handleSubmit"
         >
          <a-form-item ref="name" name="name">
            <template #label>
              <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
            </template>
            <a-input
             v-model:value="form.name"
              :placeholder="apiParams.name.description"
              v-focus="true"/>
          </a-form-item>
          <a-form-item ref="displaytext" name="displaytext">
            <template #label>
              <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
            </template>
            <a-textarea
             v-model:value="form.displaytext"
              :auto-size="{ minRows: 2, maxRows: 5 }"
              :placeholder="apiParams.displaytext.description"/>
          </a-form-item>
          <a-form-item ref="zoneid" name="zoneid">
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
          <a-form-item
            ref="networkdomain"
            name="networkdomain"
            v-if="!isObjectEmpty(selectedNetworkOffering) && !selectedNetworkOffering.forvpc">
            <template #label>
              <tooltip-label :title="$t('label.networkdomain')" :tooltip="apiParams.networkdomain.description"/>
            </template>
            <a-input
             v-model:value="form.networkdomain"
              :placeholder="apiParams.networkdomain.description"/>
          </a-form-item>
          <a-form-item ref="networkofferingid" name="networkofferingid">
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
              :disabled="!ownerReady || zoneLoading || actionLoading || networkOfferingLoading"
              :placeholder="apiParams.networkofferingid.description"
              @change="val => handleNetworkOfferingChange(networkOfferings.find(offering => offering.id === val))">
              <a-select-option v-for="opt in networkOfferings" :key="opt.id" :value="opt.id" :label="opt.displaytext || opt.name || opt.description">
                {{ opt.displaytext || opt.name || opt.description }}
              </a-select-option>
            </a-select>
            <a-alert v-if="choiceError" type="error" show-icon class="network-choice-alert" :message="$t('message.request.failed')">
              <template #action>
                <a-button size="small" @click="retryChoices">{{ $t('label.refresh') }}</a-button>
              </template>
            </a-alert>
            <a-alert type="warning" v-else-if="hasVPC === false" class="network-choice-alert">
              <template #message>
                <span v-html="$t('message.warn.vpc.offerings')"/>
              </template>
            </a-alert>
          </a-form-item>
          <network-offering-diagnostics ref="offeringDiagnostics" :offering="selectedNetworkOffering" :zone-id="selectedZone?.id" @state="providerCheck = $event" />
          <a-form-item ref="asnumber" name="asnumber" v-if="isASNumberRequired()">
            <template #label>
              <tooltip-label :title="$t('label.asnumber')" :tooltip="apiParams.asnumber.description"/>
            </template>
            <a-select
             v-model:value="form.asnumber"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="asNumberLoading"
              :placeholder="apiParams.asnumber.description">
              <a-select-option v-for="opt in asNumbersZone" :key="opt.asnumber" :value="opt.asnumber" :label="String(opt.asnumber)">
                {{ opt.asnumber }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-row :gutter="12" v-if="setMTU">
            <a-col :md="12" :lg="12">
              <a-form-item
                ref="publicmtu"
                name="publicmtu">
                <template #label>
                  <tooltip-label :title="$t('label.publicmtu')" :tooltip="apiParams.publicmtu.description"/>
                </template>
                <a-input-number
                style="width: 100%;"
                v-model:value="form.publicmtu"
                  :placeholder="apiParams.publicmtu.description"
                  @change="updateMtu(true)"/>
                <div style="color: red" v-if="errorPublicMtu" v-html="errorPublicMtu"></div>
              </a-form-item>
            </a-col>
            <a-col :md="12" :lg="12">
              <a-form-item
                ref="privatemtu"
                name="privatemtu">
                <template #label>
                  <tooltip-label :title="$t('label.privatemtu')" :tooltip="apiParams.privatemtu.description"/>
                </template>
                <a-input-number
                style="width: 100%;"
                v-model:value="form.privatemtu"
                  :placeholder="apiParams.privatemtu.description"
                  @change="updateMtu(false)"/>
                <div style="color: red" v-if="errorPrivateMtu"  v-html="errorPrivateMtu"></div>
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item
            ref="vlan"
            name="vlan"
            v-if="!isObjectEmpty(selectedNetworkOffering) && selectedNetworkOffering.specifyvlan">
            <template #label>
              <tooltip-label :title="$t('label.vlan')" :tooltip="apiParams.vlan.description"/>
            </template>
            <a-input
             v-model:value="form.vlan"
              :placeholder="apiParams.vlan.description"/>
          </a-form-item>
          <a-form-item
            ref="vpcid"
            name="vpcid"
            v-if="!isObjectEmpty(selectedNetworkOffering) && selectedNetworkOffering.forvpc">
            <template #label>
              <tooltip-label :title="$t('label.vpcid')" :tooltip="apiParams.vpcid.description"/>
            </template>
            <a-select
             v-model:value="form.vpcid"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :placeholder="apiParams.vpcid.description">
              <a-select-option v-for="opt in compatibleVpcs" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                {{ opt.name || opt.description }}
              </a-select-option>
            </a-select>
            <a-alert v-if="!compatibleVpcs.length" class="network-choice-alert" type="warning" show-icon :message="$t('message.network.diagnostics.vpc.incompatible')" />
          </a-form-item>
          <a-form-item
            ref="externalid"
            name="externalid">
            <template #label>
              <tooltip-label :title="$t('label.externalid')" :tooltip="apiParams.externalid.description"/>
            </template>
            <a-input
             v-model:value="form.externalid"
              :placeholder="apiParams.externalid.description"/>
          </a-form-item>
          <a-form-item
            v-if="selectedNetworkOffering && (selectedNetworkOffering.networkmode !== 'ROUTED' || isAdmin())"
            ref="gateway"
            name="gateway">
            <template #label>
              <tooltip-label :title="$t('label.gateway')" :tooltip="apiParams.gateway.description"/>
            </template>
            <a-input
             v-model:value="form.gateway"
              :placeholder="apiParams.gateway.description"/>
          </a-form-item>
          <a-form-item
            v-if="selectedNetworkOffering && (selectedNetworkOffering.networkmode !== 'ROUTED' || isAdmin())"
            ref="netmask"
            name="netmask">
            <template #label>
              <tooltip-label :title="$t('label.netmask')" :tooltip="apiParams.netmask.description"/>
            </template>
            <a-input
             v-model:value="form.netmask"
              :placeholder="apiParams.netmask.description"/>
          </a-form-item>
          <a-form-item
            v-if="selectedNetworkOffering && selectedNetworkOffering.networkmode === 'ROUTED'"
            ref="cidrsize"
            name="cidrsize">
            <template #label>
              <tooltip-label :title="$t('label.cidrsize')" :tooltip="apiParams.cidrsize.description"/>
            </template>
            <a-input
              v-model:value="form.cidrsize"
              :placeholder="apiParams.cidrsize.description"/>
          </a-form-item>
          <a-form-item v-if="selectedNetworkOffering && selectedNetworkOffering.specifyipranges" name="startip" ref="startip">
            <template #label>
              <tooltip-label :title="$t('label.startipv4')" :tooltip="apiParams.startip.description"/>
            </template>
            <a-input
              v-model:value="form.startip"
              :placeholder="apiParams.startip.description"/>
          </a-form-item>
          <a-form-item v-if="selectedNetworkOffering && selectedNetworkOffering.specifyipranges" name="endip" ref="endip">
            <template #label>
              <tooltip-label :title="$t('label.endip')" :tooltip="apiParams.endip.description"/>
            </template>
            <a-input
              v-model:value="form.endip"
              :placeholder="apiParams.endip.description"/>
          </a-form-item>
          <div v-if="selectedNetworkOfferingSupportsDns">
            <a-row :gutter="12">
              <a-col :md="12" :lg="12">
                <a-form-item v-if="'dns1' in apiParams" name="dns1" ref="dns1">
                  <template #label>
                    <tooltip-label :title="$t('label.dns1')" :tooltip="apiParams.dns1.description"/>
                  </template>
                  <a-input
                    v-model:value="form.dns1"
                    :placeholder="apiParams.dns1.description"/>
                </a-form-item>
              </a-col>
              <a-col :md="12" :lg="12">
                <a-form-item v-if="'dns2' in apiParams" name="dns2" ref="dns2">
                  <template #label>
                    <tooltip-label :title="$t('label.dns2')" :tooltip="apiParams.dns2.description"/>
                  </template>
                  <a-input
                    v-model:value="form.dns2"
                    :placeholder="apiParams.dns2.description"/>
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :md="12" :lg="12">
                <a-form-item v-if="selectedNetworkOffering && selectedNetworkOffering.internetprotocol === 'DualStack' && 'ip6dns1' in apiParams" name="ip6dns1" ref="ip6dns1">
                  <template #label>
                    <tooltip-label :title="$t('label.ip6dns1')" :tooltip="apiParams.ip6dns1.description"/>
                  </template>
                  <a-input
                    v-model:value="form.ip6dns1"
                    :placeholder="apiParams.ip6dns1.description"/>
                </a-form-item>
              </a-col>
              <a-col :md="12" :lg="12">
                <a-form-item v-if="selectedNetworkOffering && selectedNetworkOffering.internetprotocol === 'DualStack' && 'ip6dns2' in apiParams" name="ip6dns2" ref="ip6dns2">
                  <template #label>
                    <tooltip-label :title="$t('label.ip6dns2')" :tooltip="apiParams.ip6dns2.description"/>
                  </template>
                  <a-input
                    v-model:value="form.ip6dns2"
                    :placeholder="apiParams.ip6dns2.description"/>
                </a-form-item>
              </a-col>
            </a-row>
          </div>
          <a-form-item v-if="selectedNetworkOfferingSupportsSourceNat" name="sourcenatipaddress" ref="sourcenatipaddress">
            <template #label>
              <tooltip-label :title="$t('label.routerip')" :tooltip="apiParams.sourcenatipaddress?.description"/>
            </template>
            <a-input
              v-model:value="form.sourcenatipaddress"
              :placeholder="apiParams.sourcenatipaddress?.description"/>
          </a-form-item>
          <a-form-item name="keepMacAddressOnPublicNic" ref="keepMacAddressOnPublicNic" v-if="isAdmin() && selectedNetworkOfferingSupportsSourceNat && !selectedNetworkOffering?.forvpc">
            <template #label>
              <tooltip-label
                :title="$t('label.keep.mac.address.on.public.nic')"
                :tooltip="apiParams.keepmacaddressonpublicnic?.description"
              />
            </template>
            <a-switch v-model:checked="form.keepMacAddressOnPublicNic" />
          </a-form-item>
          <MoldDialogActions v-if="!submitHandler && active" :busy="actionLoading">
            <a-button
              :loading="actionLoading"
              @click="closeAction">
              {{ $t('label.cancel') }}
            </a-button>
            <a-button
              :loading="actionLoading"
              ref="submit"
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
import { ref, reactive, toRaw, getCurrentInstance } from 'vue'
import { getAPI, postAPI } from '@/api'
import { isAdmin, isAdminOrDomainAdmin } from '@/role'
import { mixinForm } from '@/utils/mixin'
import { vpcSupportsTierOffering } from '@/utils/networkOfferingDiagnostics'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import NetworkOfferingDiagnostics from '@/components/network/NetworkOfferingDiagnostics.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection.vue'

export default {
  name: 'CreateIsolatedNetworkForm',
  mixins: [mixinForm],
  components: {
    NetworkOfferingDiagnostics,
    MoldDialogActions,
    TooltipLabel,
    ResourceIcon,
    OwnershipSelection
  },
  props: {
    active: { type: Boolean, default: true },
    submitHandler: { type: Function, default: null },
    loading: {
      type: Boolean,
      default: false
    },
    vpc: {
      type: Object,
      default: null
    },
    resource: {
      type: Object,
      default: () => { return {} }
    }
  },
  setup () { return { formName: 'network-create-isolated-' + getCurrentInstance().uid } },
  data () {
    return {
      actionLoading: false,
      providerCheck: { blocked: false },
      owner: {},
      ownerReady: false,
      zoneRequest: 0,
      offeringRequest: 0,
      asNumberRequest: 0,
      choiceError: '',
      zones: [],
      zoneLoading: false,
      selectedZone: {},
      networkOfferings: [],
      networkOfferingLoading: false,
      selectedNetworkOffering: {},
      vpcs: [],
      privateMtuMax: 1500,
      publicMtuMax: 1500,
      minMTU: 68,
      errorPublicMtu: '',
      errorPrivateMtu: '',
      setMTU: false,
      asNumberLoading: false,
      asNumbersZone: [],
      hasVPC: null
    }
  },
  watch: {
    actionLoading (value) { this.$emit('busy', value) },
    activeProject () {
      this.setImplicitOwner()
      this.updateOfferings()
    },
    resource: {
      deep: true,
      handler () {
        this.setImplicitOwner()
        this.fetchData()
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createNetwork')
  },
  created () {
    this.initForm()
    this.setImplicitOwner()
    this.fetchData()
  },
  beforeUnmount () {
    this.zoneRequest++
    this.offeringRequest++
    this.asNumberRequest++
  },
  computed: {
    activeProject () { return this.$store.getters.project?.id },
    chooseOwner () { return !this.submitHandler && isAdminOrDomainAdmin() && !this.activeProject },
    compatibleVpcs () {
      return this.vpcs.filter(vpc => vpcSupportsTierOffering(vpc, this.selectedNetworkOffering))
    },
    canSubmit () {
      return this.active && !this.loading && !this.actionLoading && this.ownerReady && !this.zoneLoading &&
        !this.networkOfferingLoading && !this.asNumberLoading && !this.choiceError &&
        !this.providerCheck.blocked && !!this.selectedZone?.id && !!this.selectedNetworkOffering?.id &&
        (!this.selectedNetworkOffering.forvpc || this.compatibleVpcs.some(vpc => vpc.id === this.form.vpcid)) &&
        (!this.isASNumberRequired() || this.asNumbersZone.some(row => row.asnumber === this.form.asnumber))
    },
    selectedNetworkOfferingSupportsDns () {
      if (this.selectedNetworkOffering) {
        const services = this.selectedNetworkOffering?.service || []
        const dnsServices = services.filter(service => service.name === 'Dns')
        return dnsServices && dnsServices.length === 1
      }
      return false
    },
    selectedNetworkOfferingSupportsSourceNat () {
      if (this.selectedNetworkOffering) {
        const services = this.selectedNetworkOffering?.service || []
        const sourcenatService = services.filter(service => service.name === 'SourceNat')
        return sourcenatService && sourcenatService.length === 1
      }
      return false
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        keepMacAddressOnPublicNic: true
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.name') }],
        zoneid: [{ required: true, message: this.$t('message.error.select') }],
        networkofferingid: [{ required: true, message: this.$t('message.error.select') }],
        vpcid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      this.fetchZoneData()
    },
    isAdmin () {
      return isAdmin()
    },
    isASNumberRequired () {
      return this.selectedNetworkOffering?.specifyasnumber &&
        this.selectedNetworkOffering.routingmode?.toLowerCase() === 'dynamic'
    },
    isObjectEmpty (obj) {
      return !obj || !Object.keys(obj).length
    },
    isValidTextValueForKey (obj, key) {
      return key in obj && obj[key] != null && String(obj[key]).length > 0
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
          zone.networktype === 'Advanced' && zone.securitygroupsenabled !== true && zone.type !== 'Edge')
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
      const changed = this.selectedZone?.id !== zone?.id
      this.selectedZone = zone || {}
      this.setMTU = zone?.allowuserspecifyvrmtu || false
      this.privateMtuMax = zone?.routerprivateinterfacemaxmtu || 1500
      this.publicMtuMax = zone?.routerpublicinterfacemaxmtu || 1500
      if (changed) {
        this.updateMtu(true)
        this.updateMtu(false)
      }
      this.updateOfferings()
    },
    retryChoices () {
      if (this.choiceError === 'zone') return this.fetchZoneData()
      if (this.choiceError === 'asnumber') return this.fetchZoneASNumbers()
      return this.updateOfferings()
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
      this.vpcs = []
      this.hasVPC = null
      this.form.networkofferingid = undefined
      this.handleNetworkOfferingChange(null)
      this.networkOfferingLoading = !!zone?.id && this.ownerReady
      if (!this.networkOfferingLoading) return
      this.choiceError = ''
      const owner = { ...this.owner }
      try {
        if ('listVPCs' in this.$store.getters.apis) {
          const vpcScope = this.vpc ? { id: this.vpc.id } : { ...owner, zoneid: zone.id }
          const json = await getAPI('listVPCs', vpcScope, { preserveOnFailure: true })
          if (request !== this.offeringRequest) return
          this.vpcs = (json.listvpcsresponse.vpc || []).filter(vpc => vpc.zoneid === zone.id)
        }
        this.hasVPC = this.vpcs.length > 0
        const params = { zoneid: zone.id, guestiptype: 'Isolated', state: 'Enabled' }
        if (this.vpc || !this.hasVPC) params.forvpc = !!this.vpc
        if (isAdminOrDomainAdmin() && owner.domainid && owner.domainid !== '-1') params.domainid = owner.domainid
        if (!isAdmin()) params.specifyvlan = false
        const json = await getAPI('listNetworkOfferings', params, { preserveOnFailure: true })
        if (request !== this.offeringRequest) return
        this.networkOfferings = (json.listnetworkofferingsresponse.networkoffering || []).filter(offering =>
          !!offering.fornsx === !!zone.isnsxenabled && (zone.routedmodeenabled || offering.networkmode !== 'ROUTED'))
        const selected = this.networkOfferings.find(offering => offering.id === previous) || this.networkOfferings[0]
        this.form.networkofferingid = selected?.id
        this.handleNetworkOfferingChange(selected)
      } catch (_) {
        if (request === this.offeringRequest) this.choiceError = 'offering'
      } finally {
        if (request === this.offeringRequest) this.networkOfferingLoading = false
      }
    },
    handleNetworkOfferingChange (offering) {
      this.selectedNetworkOffering = offering || {}
      this.form.vpcid = offering?.forvpc ? this.compatibleVpcs[0]?.id : undefined
      this.form.asnumber = undefined
      this.asNumbersZone = []
      this.asNumberLoading = false
      this.asNumberRequest++
      if (this.choiceError === 'asnumber') this.choiceError = ''
      if (this.isASNumberRequired()) this.fetchZoneASNumbers()
    },
    async fetchZoneASNumbers () {
      const request = ++this.asNumberRequest
      this.asNumberLoading = true
      this.choiceError = ''
      try {
        const json = await getAPI('listASNumbers', { zoneid: this.selectedZone.id, isallocated: false }, { preserveOnFailure: true })
        if (request === this.asNumberRequest) this.asNumbersZone = json.listasnumbersresponse.asnumber || []
      } catch (_) {
        if (request === this.asNumberRequest) this.choiceError = 'asnumber'
      } finally {
        if (request === this.asNumberRequest) this.asNumberLoading = false
      }
    },
    handleSubmit () {
      if (!this.canSubmit) return
      this.formRef.value.validate().then(async () => {
        if (!this.canSubmit) return
        this.actionLoading = true
        const scope = JSON.stringify([this.form, this.owner, this.selectedZone, this.selectedNetworkOffering, this.offeringRequest])
        if (!await this.$refs.offeringDiagnostics.validate() || scope !== JSON.stringify([this.form, this.owner, this.selectedZone, this.selectedNetworkOffering, this.offeringRequest])) {
          this.actionLoading = false
          return
        }
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        this.actionLoading = true
        const params = {
          zoneId: this.selectedZone.id,
          name: values.name,
          displayText: values.displaytext,
          networkOfferingId: this.selectedNetworkOffering.id
        }
        const usefulFields = ['gateway', 'netmask', 'cidrsize', 'startip', 'startipv4', 'endip', 'endipv4', 'dns1', 'dns2', 'ip6dns1', 'ip6dns2', 'sourcenatipaddress', 'externalid', 'vpcid', 'vlan', 'networkdomain']
        for (const field of usefulFields) {
          if (this.isValidTextValueForKey(values, field)) {
            params[field] = values[field]
          }
        }
        if (this.isValidTextValueForKey(values, 'publicmtu')) {
          params.publicmtu = values.publicmtu
        }
        if (this.isValidTextValueForKey(values, 'privatemtu')) {
          params.privatemtu = values.privatemtu
        }
        if ('keepMacAddressOnPublicNic' in values && this.selectedNetworkOfferingSupportsSourceNat) {
          params.keepmacaddressonpublicnic = values.keepMacAddressOnPublicNic
        }
        Object.assign(params, this.owner)

        if ('asnumber' in values && this.isASNumberRequired()) {
          params.asnumber = values.asnumber
        }

        if (this.submitHandler) {
          this.submitHandler(params)
          this.actionLoading = false
          return
        }
        postAPI('createNetwork', params, { preserveOnFailure: true }).then(() => {
          this.$notification.success({
            message: 'Network',
            description: this.$t('message.success.create.isolated.network')
          })
          this.$emit('refresh-data')
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.actionLoading = false
        })
      }).catch(error => {
        this.actionLoading = false
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      })
    },
    updateMtu (isPublic) {
      if (isPublic) {
        if (this.form.publicmtu > this.publicMtuMax) {
          this.errorPublicMtu = this.$t('message.error.mtu.public.max.exceed')
          this.form.publicmtu = this.publicMtuMax
        } else if (this.form.publicmtu < this.minMTU) {
          this.errorPublicMtu = `${this.$t('message.error.mtu.below.min').replace('%x', this.minMTU)}`
          this.form.publicmtu = this.minMTU
        } else {
          this.errorPublicMtu = ''
        }
      } else {
        if (this.form.privatemtu > this.privateMtuMax) {
          this.errorPrivateMtu = this.$t('message.error.mtu.private.max.exceed')
          this.form.privatemtu = this.privateMtuMax
        } else if (this.form.privatemtu < this.minMTU) {
          this.errorPrivateMtu = `${this.$t('message.error.mtu.below.min').replace('%x', this.minMTU)}`
          this.form.privatemtu = this.minMTU
        } else {
          this.errorPrivateMtu = ''
        }
      }
    },
    showInput () {
      this.inputVisible = true
      this.$nextTick(function () {
        this.$refs.input.focus()
      })
    },
    resetForm () {
      this.formRef.value.resetFields()
      this.tags = []
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

.network-choice-alert {
  margin-top: 8px;
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
