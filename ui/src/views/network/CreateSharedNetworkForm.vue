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
          <a-form-item :label="$t('label.name')" name="name" ref="name">
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
            <a-textarea
              v-model:value="form.displaytext"
              :auto-size="{ minRows: 2, maxRows: 5 }"
              :placeholder="apiParams.displaytext.description"/>
          </a-form-item>
          <a-form-item v-if="isObjectEmpty(zone)" name="zoneid" ref="zoneid">
            <template #label>
              <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
            </template>
            <a-select
              v-model:value="form.zoneid"
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="zoneLoading"
              :placeholder="apiParams.zoneid.description"
              @change="handleZoneChange">
              <a-select-option v-for="opt in zones" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                <span>
                  <resource-icon v-if="opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <global-outlined v-else style="margin-right: 5px" />
                  {{ opt.name || opt.description }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-if="isAdmin()" name="physicalnetworkid" ref="physicalnetworkid">
            <template #label>
              <tooltip-label :title="$t('label.physicalnetworkid')" :tooltip="apiParams.physicalnetworkid.description"/>
            </template>
            <a-select
              v-model:value="form.physicalnetworkid"
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="formPhysicalNetworkLoading"
              :placeholder="apiParams.physicalnetworkid.description"
              @change="handlePhysicalNetworkChange">
              <a-select-option v-for="opt in formPhysicalNetworks" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                {{ opt.name || opt.description }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-if="!fixedScope && selectedZone.id" :label="$t('label.scope')" name="scope" ref="scope">
            <a-radio-group
              v-model:value="form.scope"
              buttonStyle="solid"
              @change="selected => { handleScopeTypeChange(selected.target.value) }">
              <a-radio-button value="all" v-if="isAdmin()">
                {{ $t('label.all') }}
              </a-radio-button>
              <a-radio-button value="domain" v-if="scopeOptions.includes('domain')">
                {{ $t('label.domain') }}
              </a-radio-button>
              <a-radio-button value="account" v-if="scopeOptions.includes('account')">
                {{ $t('label.account') }}
              </a-radio-button>
              <a-radio-button value="project" v-if="scopeOptions.includes('project')">
                {{ $t('label.project') }}
              </a-radio-button>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="chooseDomain" name="domainid" ref="domainid">
            <template #label>
              <tooltip-label :title="$t('label.domainid')" :tooltip="apiParams.domainid.description"/>
            </template>
            <a-select
              v-model:value="form.domainid"
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="domainLoading"
              :placeholder="apiParams.domainid.description"
              @change="handleDomainChange">
              <a-select-option v-for="opt in domains" :key="opt.id" :value="opt.id" :label="opt.path || opt.name || opt.description">
                <span>
                  <resource-icon v-if="opt && opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <block-outlined v-else style="margin-right: 5px" />
                  {{ opt.path || opt.name || opt.description }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-if="!fixedScope && form.scope === 'domain' && apiParams.subdomainaccess" name="subdomainaccess" ref="subdomainaccess">
            <template #label>
              <tooltip-label :title="$t('label.subdomainaccess')" :tooltip="apiParams.subdomainaccess.description"/>
            </template>
            <a-switch v-model:checked="form.subdomainaccess" />
          </a-form-item>
          <a-form-item v-if="!fixedScope && form.scope === 'account' && isAdminOrDomainAdmin()" name="account" ref="account">
            <template #label>
              <tooltip-label :title="$t('label.account')" :tooltip="apiParams.account.description"/>
            </template>
            <a-select
              v-model:value="form.account"
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="accountLoading"
              :placeholder="apiParams.account.description"
              @change="handleAccountChange">
              <a-select-option v-for="opt in accounts" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                <span>
                  <resource-icon v-if="opt && opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <user-outlined v-else style="margin-right: 5px" />
                  {{ opt.name || opt.description }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-if="!fixedScope && form.scope === 'project'" name="projectid" ref="projectid">
            <template #label>
              <tooltip-label :title="$t('label.project')" :tooltip="apiParams.projectid.description"/>
            </template>
            <a-select
              v-model:value="form.projectid"
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="projectLoading"
              :placeholder="apiParams.projectid.description"
              @change="handleProjectChange">
              <a-select-option v-for="opt in projects" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                {{ opt.name || opt.description }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item name="networkofferingid" ref="networkofferingid">
            <template #label>
              <tooltip-label :title="$t('label.networkofferingid')" :tooltip="apiParams.networkofferingid.description"/>
            </template>
            <a-select
              v-model:value="form.networkofferingid"
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="networkOfferingLoading"
              :placeholder="apiParams.networkofferingid.description"
              @change="handleNetworkOfferingChange">
              <a-select-option v-for="opt in networkOfferings" :key="opt.id" :value="opt.id" :label="opt.displaytext || opt.name || opt.description">
                {{ opt.displaytext || opt.name || opt.description }}
              </a-select-option>
            </a-select>
            <a-alert type="warning" v-if="ownerReady && !formPhysicalNetworkLoading && !networkOfferingLoading && !choiceError && networkOfferings.length === 0">
              <template #message>{{ $t('message.shared.network.offering.warning') }}</template>
            </a-alert>
          </a-form-item>
          <network-offering-diagnostics ref="offeringDiagnostics" :offering="selectedNetworkOffering" :zone-id="selectedZone?.id" :physical-network-id="formSelectedPhysicalNetwork?.id || ''" @state="providerCheck = $event" />
          <a-alert v-if="choiceError" type="warning" show-icon class="shared-form-alert" :message="$t('message.shared.choices.failed')">
            <template #description>
              <a-button size="small" @click="retryChoices"><reload-outlined /> {{ $t('label.refresh') }}</a-button>
            </template>
          </a-alert>
          <a-alert
v-if="selectedNetworkOffering.id"
class="shared-form-alert"
type="info"
show-icon
            :message="$t(isVirtualRouterForAtLeastOneService ? 'message.shared.router' : 'message.shared.external')"
            :description="offeringServices" />
          <a-form-item v-if="specifyVlan" name="vlan" ref="vlan">
            <template #label>
              <tooltip-label :title="$t('label.vlan')" :tooltip="apiParams.vlan.description"/>
            </template>
            <a-input
              v-model:value="form.vlan"
              :placeholder="apiParams.vlan.description"/>
          </a-form-item>
          <a-form-item name="bypassvlanoverlapcheck" ref="bypassvlanoverlapcheck" v-if="canBypass">
            <template #label>
              <tooltip-label :title="$t('label.bypassvlanoverlapcheck')" :tooltip="apiParams.bypassvlanoverlapcheck.description"/>
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
              @change="form.isolatedpvlan = undefined"
              buttonStyle="solid">
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
          <a-form-item v-if="canUsePvlan && ['community', 'isolated'].includes(form.isolatedpvlantype)" name="isolatedpvlan" ref="isolatedpvlan">
            <template #label>
              <tooltip-label :title="$t('label.isolatedpvlan')" :tooltip="apiParams.isolatedpvlan.description"/>
            </template>
            <a-input
              v-model:value="form.isolatedpvlan"
              :placeholder="apiParams.isolatedpvlan.description"/>
          </a-form-item>
          <a-row :gutter="12" v-if="setMTU && isVirtualRouterForAtLeastOneService">
            <a-col :xs="24" :sm="12">
              <a-form-item
                v-if="apiParams.publicmtu"
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
                <div class="shared-form-error" v-if="errorPublicMtu">{{ errorPublicMtu }}</div>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12">
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
                <div class="shared-form-error" v-if="errorPrivateMtu">{{ errorPrivateMtu }}</div>
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item v-if="showAssociated" name="associatednetworkid" ref="associatednetworkid">
            <template #label>
              <tooltip-label :title="$t('label.associatednetwork')" :tooltip="apiParams.associatednetworkid.description"/>
            </template>
            <a-select
              v-model:value="form.associatednetworkid"
              allowClear
              showSearch
              optionFilterProp="label"
              optionLabelProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
              :loading="networkLoading"
              :placeholder="this.$t('label.associatednetwork')"
              @change="handleNetworkChange">
              <a-select-option v-for="opt in networks" :key="opt.id" :value="opt.id" :label="opt.name || opt.description">
                <span>
                  <resource-icon v-if="opt && opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <apartment-outlined v-else style="margin-right: 5px" />
                  {{ opt.name || opt.description }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item :label="$t('label.shared.address.family')" name="family" v-if="selectedNetworkOffering.id">
            <a-select v-model:value="form.family" @change="handleFamilyChange">
              <a-select-option value="ipv4">{{ $t('label.ip.v4') }}</a-select-option>
              <a-select-option value="dual">{{ $t('label.ip.v4.v6') }}</a-select-option>
              <a-select-option value="ipv6" :disabled="isVirtualRouterForAtLeastOneService">{{ $t('label.ip.v6') }}</a-select-option>
            </a-select>
            <p v-if="isVirtualRouterForAtLeastOneService" class="network-ip-usage-help">{{ $t('message.shared.ipv6.router') }}</p>
          </a-form-item>
          <a-card v-if="selectedNetworkOffering.id && form.family !== 'ipv6'" size="small" :title="$t('label.ip.v4')" style="margin-top: 15px">
            <a-row :gutter="12">
              <a-col :xs="24" :sm="12">
                <a-form-item name="gateway" ref="gateway">
                  <template #label>
                    <tooltip-label :title="$t('label.ip4gateway')" :tooltip="apiParams.gateway.description"/>
                  </template>
                  <a-input
                    v-model:value="form.gateway"
                    :placeholder="apiParams.gateway.description"/>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-form-item name="netmask" ref="netmask">
                  <template #label>
                    <tooltip-label :title="$t('label.ip4netmask')" :tooltip="apiParams.netmask.description"/>
                  </template>
                  <a-input
                    v-model:value="form.netmask"
                    :placeholder="apiParams.netmask.description"/>
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :xs="24" :sm="12">
                <a-form-item name="startip" ref="startip">
                  <template #label>
                    <tooltip-label :title="$t('label.startipv4')" :tooltip="apiParams.startip.description"/>
                  </template>
                  <a-input
                    v-model:value="form.startip"
                    :placeholder="apiParams.startip.description"/>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-form-item name="endip" ref="endip">
                  <template #label>
                    <tooltip-label :title="$t('label.endipv4')" :tooltip="apiParams.endip.description"/>
                  </template>
                  <a-input
                    v-model:value="form.endip"
                    :placeholder="apiParams.endip.description"/>
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item v-if="isVirtualRouterForAtLeastOneService && apiParams.routerip" name="routerip" ref="routerip">
              <template #label>
                <tooltip-label :title="$t('label.routerip')" :tooltip="apiParams.routerip.description"/>
              </template>
              <a-input
                v-model:value="form.routerip"
                :placeholder="apiParams.routerip.description"/>
            </a-form-item>
            <a-row :gutter="12" v-if="selectedNetworkOfferingSupportsDns">
              <a-col :xs="24" :sm="12">
                <a-form-item v-if="'dns1' in apiParams" name="dns1" ref="dns1">
                  <template #label>
                    <tooltip-label :title="$t('label.dns1')" :tooltip="apiParams.dns1.description"/>
                  </template>
                  <a-input
                    v-model:value="form.dns1"
                    :placeholder="apiParams.dns1.description"/>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12">
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
          </a-card>
          <a-card v-if="selectedNetworkOffering.id && form.family !== 'ipv4'" size="small" :title="$t('label.ip.v6')" style="margin-top: 15px; margin-bottom: 10px">
            <a-row :gutter="12">
              <a-col :xs="24" :sm="12">
                <a-form-item name="ip6gateway" ref="ip6gateway">
                  <template #label>
                    <tooltip-label :title="$t('label.ip6gateway')" :tooltip="apiParams.ip6gateway.description"/>
                  </template>
                  <a-input
                    v-model:value="form.ip6gateway"
                    :placeholder="apiParams.ip6gateway.description"/>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-form-item name="ip6cidr" ref="ip6cidr">
                  <template #label>
                    <tooltip-label :title="$t('label.ip6cidr')" :tooltip="apiParams.ip6cidr.description"/>
                  </template>
                  <a-input
                    v-model:value="form.ip6cidr"
                    :placeholder="apiParams.ip6cidr.description"/>
                </a-form-item>
              </a-col>
            </a-row>
            <a-row :gutter="12">
              <a-col :xs="24" :sm="12">
                <a-form-item name="startipv6" ref="startipv6">
                  <template #label>
                    <tooltip-label :title="$t('label.startipv6')" :tooltip="apiParams.startipv6.description"/>
                  </template>
                  <a-input
                    v-model:value="form.startipv6"
                    :placeholder="apiParams.startipv6.description"/>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-form-item name="endipv6" ref="endipv6">
                  <template #label>
                    <tooltip-label :title="$t('label.endipv6')" :tooltip="apiParams.endipv6.description"/>
                  </template>
                  <a-input
                    v-model:value="form.endipv6"
                    :placeholder="apiParams.endipv6.description"/>
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item v-if="isVirtualRouterForAtLeastOneService && apiParams.routeripv6" name="routeripv6" ref="routeripv6">
              <template #label>
                <tooltip-label :title="$t('label.routeripv6')" :tooltip="apiParams.routeripv6.description"/>
              </template>
              <a-input
                v-model:value="form.routeripv6"
                :placeholder="apiParams.routeripv6.description"/>
            </a-form-item>
            <a-row :gutter="12" v-if="selectedNetworkOfferingSupportsDns">
              <a-col :xs="24" :sm="12">
                <a-form-item v-if="'ip6dns1' in apiParams" name="ip6dns1" ref="ip6dns1">
                  <template #label>
                    <tooltip-label :title="$t('label.ip6dns1')" :tooltip="apiParams.ip6dns1.description"/>
                  </template>
                  <a-input
                    v-model:value="form.ip6dns1"
                    :placeholder="apiParams.ip6dns1.description"/>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :sm="12">
                <a-form-item v-if="'ip6dns2' in apiParams" name="ip6dns2" ref="ip6dns2">
                  <template #label>
                    <tooltip-label :title="$t('label.ip6dns2')" :tooltip="apiParams.ip6dns2.description"/>
                  </template>
                  <a-input
                    v-model:value="form.ip6dns2"
                    :placeholder="apiParams.ip6dns2.description"/>
                </a-form-item>
              </a-col>
            </a-row>
          </a-card>
          <a-form-item v-if="selectedNetworkOfferingSupportsDns && apiParams.networkdomain" name="networkdomain" ref="networkdomain">
            <template #label>
              <tooltip-label :title="$t('label.networkdomain')" :tooltip="apiParams.networkdomain.description"/>
            </template>
            <a-input
              v-model:value="form.networkdomain"
              :placeholder="apiParams.networkdomain.description"/>
          </a-form-item>
          <a-form-item v-if="isAdmin() && 'hideipaddressusage' in apiParams" name="hideipaddressusage" ref="hideipaddressusage">
            <template #label>
              <tooltip-label :title="$t('label.hideipaddressusage')" :tooltip="apiParams.hideipaddressusage.description"/>
            </template>
            <a-switch v-model:checked="form.hideipaddressusage" />
            <p class="network-ip-usage-help">{{ $t('message.network.ip.usage.help') }}</p>
          </a-form-item>
          <a-alert v-if="formError" class="shared-form-alert" type="error" show-icon role="alert" :message="$t(formError)" />
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
              ref="submit">
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
import { l2TagFields } from '@/utils/l2Network'
import { sharedScopeParams, sharedAddressParams } from '@/utils/sharedNetwork'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import NetworkOfferingDiagnostics from '@/components/network/NetworkOfferingDiagnostics.vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'

export default {
  name: 'CreateSharedNetworkForm',
  components: { NetworkOfferingDiagnostics, MoldDialogActions, TooltipLabel, ResourceIcon },
  props: {
    active: { type: Boolean, default: true },
    submitHandler: { type: Function, default: null },
    loading: { type: Boolean, default: false },
    zone: { type: Object, default: null },
    resource: { type: Object, default: () => ({}) }
  },
  setup () { return { formName: 'network-create-shared-' + getCurrentInstance().uid } },
  data () {
    return {
      actionLoading: false,
      providerCheck: { blocked: false },
      zones: [],
      formPhysicalNetworks: [],
      domains: [],
      accounts: [],
      projects: [],
      networks: [],
      networkOfferings: [],
      zoneLoading: false,
      formPhysicalNetworkLoading: false,
      domainLoading: false,
      accountLoading: false,
      projectLoading: false,
      networkLoading: false,
      networkOfferingLoading: false,
      choiceError: '',
      formError: '',
      requests: { zone: 0, physical: 0, owner: 0, offering: 0, related: 0 },
      errorPublicMtu: '',
      errorPrivateMtu: ''
    }
  },
  beforeCreate () { this.apiParams = this.$getApiParams('createNetwork') || {} },
  created () {
    this.formRef = ref()
    this.form = reactive({ scope: this.defaultScope(), family: 'ipv4', isolatedpvlantype: 'none' })
    const required = { required: true, message: this.$t('message.error.select') }
    this.rules = {
      name: [{ required: true, message: this.$t('message.error.name') }],
      zoneid: [required],
      physicalnetworkid: [required],
      networkofferingid: [required],
      domainid: [required],
      account: [required],
      projectid: [required],
      vlan: [{ required: true, message: this.$t('message.please.enter.value') }]
    }
    this.fetchZoneData()
  },
  beforeUnmount () { Object.keys(this.requests).forEach(key => this.requests[key]++) },
  watch: {
    actionLoading (value) { this.$emit('busy', value) },
    resourceScope () { this.fetchZoneData() },
    zone () { this.fetchZoneData() },
    activeProject () {
      this.form.scope = this.defaultScope()
      this.handleScopeTypeChange()
    }
  },
  computed: {
    activeProject () { return this.$store.getters.project?.id },
    resourceScope () {
      if (this.submitHandler) return JSON.stringify([this.resource.zoneid, this.resource.domainid, this.resource.account, this.resource.projectid])
      return this.$route.name === 'deployVirtualMachine' || this.$route.path.startsWith('/backup') ? this.resource.zoneid : undefined
    },
    fixedScope () { return !!(this.submitHandler || this.activeProject) },
    selectedZone () { return this.zones.find(row => row.id === this.form.zoneid) || {} },
    formSelectedPhysicalNetwork () { return this.formPhysicalNetworks.find(row => row.id === this.form.physicalnetworkid) },
    selectedNetworkOffering () { return this.networkOfferings.find(row => row.id === this.form.networkofferingid) || {} },
    scopeOptions () {
      if (isAdmin()) return ['all', 'domain', 'account', 'project']
      if (this.selectedZone.securitygroupsenabled) return []
      return isAdminOrDomainAdmin() ? ['domain', 'account', 'project'] : ['account', 'project']
    },
    chooseDomain () { return !this.fixedScope && this.form.scope !== 'all' && isAdminOrDomainAdmin() },
    owner () {
      return sharedScopeParams({
        scope: this.form.scope,
        domainId: this.domains.find(row => row.id === this.form.domainid)?.id,
        account: this.accounts.find(row => row.id === this.form.account),
        project: this.projects.find(row => row.id === this.form.projectid),
        manageOwner: isAdminOrDomainAdmin(),
        fixed: this.fixedScope
          ? (this.submitHandler ? { ...this.resource, projectid: this.resource.projectid || this.activeProject } : { projectid: this.activeProject })
          : null,
        subdomainAccess: this.form.subdomainaccess === true
      })
    },
    ownerReady () { return !!this.selectedZone.id && !!this.owner },
    selectedNetworkOfferingSupportsDns () { return this.offeringHasService('Dns') },
    isVirtualRouterForAtLeastOneService () {
      return (this.selectedNetworkOffering.service || []).some(s => s.provider?.some(p => p.name === 'VirtualRouter'))
    },
    offeringServices () {
      return (this.selectedNetworkOffering.service || []).map(s => [s.name, ...(s.provider || []).map(p => p.name)].join(' · ')).join(', ')
    },
    setMTU () { return this.selectedZone.allowuserspecifyvrmtu && !!this.apiParams.privatemtu },
    privateMtuMax () { return this.selectedZone.routerprivateinterfacemaxmtu || 1500 },
    publicMtuMax () { return this.selectedZone.routerpublicinterfacemaxmtu || 1500 },
    specifyVlan () { return isAdmin() && this.selectedNetworkOffering.specifyvlan === true },
    canBypass () { return this.specifyVlan && !!this.apiParams.bypassvlanoverlapcheck },
    isolation () { return this.providerCheck.network?.isolation || this.formSelectedPhysicalNetwork?.isolationmethods?.toLowerCase().split(',')[0] },
    canUsePvlan () {
      return this.specifyVlan && !!this.apiParams.isolatedpvlantype && !!this.apiParams.isolatedpvlan &&
        !this.selectedZone.isnsxenabled && !this.selectedZone.securitygroupsenabled && this.isolation === 'vlan' && this.form.family === 'ipv4'
    },
    showAssociated () { return !!this.selectedNetworkOffering.id && !this.selectedNetworkOffering.specifyvlan && !!this.apiParams.associatednetworkid },
    canSubmit () {
      return this.active && !this.loading && !this.actionLoading && !this.choiceError &&
        !this.zoneLoading && !this.formPhysicalNetworkLoading && !this.networkOfferingLoading && !this.networkLoading &&
        this.ownerReady && (!isAdmin() || !!this.formSelectedPhysicalNetwork) &&
        !!this.selectedNetworkOffering.id && !this.providerCheck.blocked
    }
  },
  methods: {
    isAdmin,
    isAdminOrDomainAdmin,
    isObjectEmpty (value) { return !value || !Object.keys(value).length },
    offeringHasService (name) { return (this.selectedNetworkOffering.service || []).some(service => service.name === name) },
    defaultScope () { return isAdmin() ? 'all' : isAdminOrDomainAdmin() ? 'domain' : 'account' },
    async rows (api, params, key) {
      const result = await getAPI(api, params, { preserveOnFailure: true })
      return result?.[api.toLowerCase() + 'response']?.[key] || []
    },
    invalidateOfferings () {
      this.requests.offering++
      this.networkOfferingLoading = false
      this.networkOfferings = []
      this.form.networkofferingid = undefined
      this.handleNetworkOfferingChange()
    },
    async fetchZoneData () {
      const id = ++this.requests.zone
      this.requests.physical++
      this.requests.owner++
      this.zones = []
      this.formPhysicalNetworks = []
      this.form.physicalnetworkid = undefined
      this.invalidateOfferings()
      this.choiceError = ''
      this.zoneLoading = true
      try {
        const constrained = this.submitHandler || this.$route.name === 'deployVirtualMachine' || this.$route.path.startsWith('/backup')
        const candidates = this.zone?.id ? [this.zone] : await this.rows('listZones', {
          showicon: true,
          ...(constrained && this.resource.zoneid ? { id: this.resource.zoneid } : {})
        }, 'zone')
        if (id !== this.requests.zone) return
        this.zones = candidates.filter(z => z.networktype === 'Advanced' && (isAdmin() || !z.securitygroupsenabled))
        this.form.zoneid = (this.zones.find(z => z.id === this.form.zoneid) || this.zones[0])?.id
        await this.handleZoneChange()
      } catch (_) {
        if (id === this.requests.zone) this.choiceError = 'zone'
      } finally {
        if (id === this.requests.zone) this.zoneLoading = false
      }
    },
    async handleZoneChange () {
      const id = ++this.requests.physical
      this.requests.owner++
      this.formPhysicalNetworks = []
      this.form.physicalnetworkid = undefined
      this.invalidateOfferings()
      this.choiceError = ''
      this.formPhysicalNetworkLoading = isAdmin() && !!this.selectedZone.id
      if (!this.scopeOptions.includes(this.form.scope)) this.form.scope = this.defaultScope()
      try {
        if (this.formPhysicalNetworkLoading) {
          const rows = await this.rows('listPhysicalNetworks', { zoneid: this.selectedZone.id }, 'physicalnetwork')
          const valid = await Promise.all(rows.filter(row => row.state === 'Enabled' && !row.isolationmethods?.toUpperCase().split(',').includes('NSX')).map(async row => {
            const traffic = await this.rows('listTrafficTypes', { physicalnetworkid: row.id }, 'traffictype')
            return traffic.some(type => type.traffictype === 'Guest') ? row : null
          }))
          if (id !== this.requests.physical) return
          this.formPhysicalNetworks = valid.filter(Boolean)
          this.form.physicalnetworkid = this.formPhysicalNetworks[0]?.id
          if (!this.form.physicalnetworkid) {
            this.choiceError = 'physical'
            return
          }
        }
        if (id === this.requests.physical) await this.handleScopeTypeChange()
      } catch (_) {
        if (id === this.requests.physical) this.choiceError = 'physical'
      } finally {
        if (id === this.requests.physical) this.formPhysicalNetworkLoading = false
      }
    },
    handlePhysicalNetworkChange () { this.fetchNetworkOfferingData() },
    async handleScopeTypeChange () {
      const id = ++this.requests.owner
      this.invalidateOfferings()
      this.choiceError = ''
      this.form.domainid = this.form.account = this.form.projectid = undefined
      this.form.subdomainaccess = false
      this.domains = []
      this.accounts = []
      this.projects = []
      this.domainLoading = this.accountLoading = this.projectLoading = false
      if (!this.selectedZone.id) return
      if (!this.chooseDomain) {
        if (!this.fixedScope && this.form.scope === 'project') await this.handleDomainChange()
        else await this.fetchNetworkOfferingData()
        return
      }
      this.domainLoading = true
      try {
        const params = this.selectedZone.domainid
          ? { id: this.selectedZone.domainid, isrecursive: true }
          : { listall: true }
        const rows = await this.rows('listDomains', { ...params, showicon: true }, 'domain')
        if (id !== this.requests.owner) return
        this.domains = rows
      } catch (_) {
        if (id === this.requests.owner) this.choiceError = 'owner'
      } finally {
        if (id === this.requests.owner) this.domainLoading = false
      }
    },
    async handleDomainChange () {
      const id = ++this.requests.owner
      this.invalidateOfferings()
      this.choiceError = ''
      this.form.account = this.form.projectid = undefined
      this.form.subdomainaccess = false
      this.accounts = []
      this.projects = []
      this.accountLoading = this.projectLoading = false
      if (this.chooseDomain && !this.form.domainid) return
      if (this.form.scope === 'domain') {
        await this.fetchNetworkOfferingData()
        return
      }
      const project = this.form.scope === 'project'
      const busy = project ? 'projectLoading' : 'accountLoading'
      this[busy] = true
      try {
        const rows = await this.rows(project ? 'listProjects' : 'listAccounts', {
          listall: true, showicon: true, details: 'min', ...(this.form.domainid ? { domainid: this.form.domainid } : {})
        }, project ? 'project' : 'account')
        if (id !== this.requests.owner) return
        const filtered = rows.filter(row => !this.form.domainid || row.domainid === this.form.domainid)
        if (project) this.projects = filtered.filter(row => row.state === 'Active')
        else this.accounts = filtered.filter(row => row.state === 'enabled' || !row.state)
      } catch (_) {
        if (id === this.requests.owner) this.choiceError = 'owner'
      } finally {
        if (id === this.requests.owner) this[busy] = false
      }
    },
    handleAccountChange () { this.fetchNetworkOfferingData() },
    handleProjectChange () { this.fetchNetworkOfferingData() },
    async fetchNetworkOfferingData () {
      this.invalidateOfferings()
      const id = this.requests.offering
      this.choiceError = ''
      if (!this.ownerReady || (isAdmin() && !this.formSelectedPhysicalNetwork)) return
      this.networkOfferingLoading = true
      try {
        const tags = this.formSelectedPhysicalNetwork?.tags
        const params = {
          zoneid: this.selectedZone.id,
          guestiptype: 'Shared',
          forvpc: false,
          specifyipranges: true,
          state: 'Enabled',
          ...(this.owner.domainid && isAdminOrDomainAdmin() ? { domainid: this.owner.domainid } : {}),
          ...(!isAdmin() ? { specifyvlan: false } : {}),
          ...(isAdmin() ? (tags?.length ? { tags } : { istagged: false }) : {})
        }
        const rows = await this.rows('listNetworkOfferings', params, 'networkoffering')
        if (id !== this.requests.offering) return
        this.networkOfferings = rows.filter(row => row.specifyipranges && !row.fornsx)
        // Keep the choice explicit when changing the scope, physical network or offering list.
      } catch (_) {
        if (id === this.requests.offering) this.choiceError = 'offering'
      } finally {
        if (id === this.requests.offering) this.networkOfferingLoading = false
      }
    },
    handleNetworkOfferingChange () {
      this.requests.related++
      this.networkLoading = false
      this.networks = []
      this.form.associatednetworkid = undefined
      this.form.vlan = this.form.isolatedpvlan = undefined
      this.form.isolatedpvlantype = 'none'
      this.form.bypassvlanoverlapcheck = false
      this.form.routerip = this.form.routeripv6 = undefined
      this.form.dns1 = this.form.dns2 = this.form.ip6dns1 = this.form.ip6dns2 = undefined
      this.form.publicmtu = this.form.privatemtu = undefined
      this.errorPublicMtu = this.errorPrivateMtu = this.formError = ''
      this.providerCheck = { blocked: false }
      if (this.isVirtualRouterForAtLeastOneService && this.form.family === 'ipv6') this.form.family = 'dual'
      if (this.showAssociated) this.fetchNetworkData()
    },
    handleFamilyChange () {
      this.formError = ''
      if (!this.canUsePvlan) {
        this.form.isolatedpvlantype = 'none'
        this.form.isolatedpvlan = undefined
      }
    },
    handleNetworkChange () { this.formError = '' },
    async fetchNetworkData () {
      const id = ++this.requests.related
      this.choiceError = ''
      this.networkLoading = true
      const owner = this.owner
      const params = {
        zoneid: this.selectedZone.id,
        networkfilter: 'Account',
        ...(!owner.projectid ? { ignoreproject: true } : {}),
        ...(owner.domainid ? { domainid: owner.domainid } : {}),
        ...(owner.account ? { account: owner.account } : {}),
        ...(owner.projectid ? { projectid: owner.projectid } : {}),
        ...(this.formSelectedPhysicalNetwork ? { physicalnetworkid: this.formSelectedPhysicalNetwork.id } : {})
      }
      try {
        const rows = await this.rows('listNetworks', params, 'network')
        if (id === this.requests.related) {
          this.networks = rows.filter(row => ['L2', 'Isolated'].includes(row.type) &&
          row.zoneid === this.selectedZone.id && (!owner.projectid || row.projectid === owner.projectid) &&
          (!owner.account || row.account === owner.account) && (!owner.domainid || row.domainid === owner.domainid))
        }
      } catch (_) {
        if (id === this.requests.related) this.choiceError = 'related'
      } finally {
        if (id === this.requests.related) this.networkLoading = false
      }
    },
    retryChoices () {
      if (this.choiceError === 'related') this.fetchNetworkData()
      else if (this.choiceError === 'offering') this.fetchNetworkOfferingData()
      else if (this.choiceError === 'owner') this.form.domainid ? this.handleDomainChange() : this.handleScopeTypeChange()
      else this.fetchZoneData()
    },
    submitFingerprint () {
      return JSON.stringify([this.form, this.owner, this.selectedZone.id, this.requests, this.activeProject])
    },
    async handleSubmit () {
      if (!this.canSubmit) return
      let submitting = false
      try {
        await this.formRef.value.validate()
        if (!this.canSubmit) return
        this.formError = ''
        const address = sharedAddressParams(this.form, {
          router: this.isVirtualRouterForAtLeastOneService,
          dns: this.selectedNetworkOfferingSupportsDns,
          zone: this.selectedZone,
          api: this.apiParams
        })
        const tag = l2TagFields({ ...this.form, vlanid: this.form.vlan, isolatedpvlanid: this.form.isolatedpvlan }, {
          specifyVlan: this.specifyVlan, canBypass: this.canBypass, canUsePvlan: this.canUsePvlan, isolation: this.isolation
        })
        if (tag.error) throw new Error('message.l2.network.' + tag.error)
        submitting = true
        this.actionLoading = true
        const fingerprint = this.submitFingerprint()
        if (!await this.$refs.offeringDiagnostics.validate() || fingerprint !== this.submitFingerprint()) return
        const params = {
          zoneid: this.selectedZone.id,
          name: this.form.name,
          displaytext: this.form.displaytext,
          networkofferingid: this.selectedNetworkOffering.id,
          ...this.owner,
          ...tag.params,
          ...address
        }
        if (this.formSelectedPhysicalNetwork) params.physicalnetworkid = this.formSelectedPhysicalNetwork.id
        if (this.showAssociated && this.networks.some(row => row.id === this.form.associatednetworkid)) params.associatednetworkid = this.form.associatednetworkid
        if (isAdmin() && this.apiParams.hideipaddressusage && this.form.hideipaddressusage) params.hideipaddressusage = true
        if (this.setMTU && this.isVirtualRouterForAtLeastOneService) {
          for (const field of ['publicmtu', 'privatemtu']) {
            if (this.apiParams[field] && this.form[field] != null) params[field] = this.form[field]
          }
        }
        if (this.submitHandler) {
          this.submitHandler(params)
          return
        }
        await postAPI('createNetwork', params, { preserveOnFailure: true })
        this.$notification.success({ message: this.$t('label.network'), description: this.$t('message.success.add.guest.network') })
        this.$emit('refresh-data')
        this.closeAction()
      } catch (error) {
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else if (error.message?.startsWith('message.')) this.formError = error.message
        else this.$notifyError(error)
      } finally {
        if (submitting) this.actionLoading = false
      }
    },
    closeAction () { this.$emit('close-action') },
    updateMtu (isPublic) {
      const [field, max, errorKey] = isPublic ? ['publicmtu', this.publicMtuMax, 'errorPublicMtu'] : ['privatemtu', this.privateMtuMax, 'errorPrivateMtu']
      const value = this.form[field]
      this[errorKey] = ''
      if (value == null) return
      if (value > max) {
        this.form[field] = max
        this[errorKey] = this.$t(isPublic ? 'message.error.mtu.public.max.exceed' : 'message.error.mtu.private.max.exceed').replace('%x', max)
      } else if (value < 68) {
        this.form[field] = 68
        this[errorKey] = this.$t('message.error.mtu.below.min').replace('%x', 68)
      }
    }
  }
}
</script>

<style lang="less" scoped>
.network-ip-usage-help { color: var(--ui-text-secondary); margin: 8px 0 0; overflow-wrap: anywhere; }
.shared-form-error { color: var(--ui-danger, #ff4d4f); }
.shared-form-alert { margin: 0 0 16px; overflow-wrap: anywhere; }
.form-layout { width: 100%; min-width: 0; }
.form { margin: 10px 0; }
</style>
