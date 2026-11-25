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
  <S2sGatewaySearch v-if="['listVpnGateways', 'listVpnConnections'].includes(apiName)" :api-name="apiName" :validation-error="s2sGatewaySearchFailure" @search="$emit('search', $event)" />
  <AsnSearch v-else-if="apiName === 'listASNumbers'" :validation-error="asnSearchFailure" @search="$emit('search', $event)" />
  <KubernetesIsoSearch v-else-if="apiName === 'listKubernetesSupportedVersions'" @search="$emit('search', $event)" />
  <PublicIpSearch v-else-if="apiName === 'listPublicIpAddresses'" @search="$emit('search', $event)" />
  <SnapshotSearch v-else-if="apiName === 'listSnapshots'" @search="$emit('search', $event)" />
  <SnapshotPolicySearch v-else-if="apiName === 'listSnapshotPolicies'" @search="$emit('search', $event)" />
  <RuntimeCatalogSearch v-else-if="apiName === 'listStorageServiceRuntimeBundles'" @search="$emit('search', $event)" />
  <span v-else :style="styleSearch">
    <span v-if="!searchFilters || searchFilters.length === 0" style="display: flex;">
      <a-input-search
        v-model:value="searchQuery"
        :aria-label="isGuestVlanSearch ? $t('label.guest.vlan.search.keyword') : isVpnUserSearch ? $t('label.vpn.user.search.keyword') : undefined"
        :placeholder="isGuestVlanSearch ? $t('label.guest.vlan.search.keyword') : apiName === 'listVpnCustomerGateways' ? $t('label.vpn.customer.gateway.search.keyword') : isVpnUserSearch ? $t('label.vpn.user.search.keyword') : isAclSearch ? $t('label.acl.search.keyword') : isEventSearch ? $t('label.events.search.keyword') : apiName === 'listVnfAppliances' ? $t('label.vnf.name.search') : $t('label.search')"
        allowClear
        @search="onSearch"
      />
    </span>

    <span
      v-else
      class="filter-group">
      <a-input-search
        allowClear
        class="input-search"
        :aria-label="isOauthSearch ? $t('label.oauth.search.keyword') : isLdapSearch ? $t('label.ldap.search.host') : isGuestVlanSearch ? $t('label.guest.vlan.search.keyword') : isVpnUserSearch ? $t('label.vpn.user.search.keyword') : isAclSearch ? $t('label.acl.search.keyword') : isEventSearch ? $t('label.events.search.keyword') : apiName === 'listIsos' ? $t('label.search') : undefined"
        :placeholder="isGuestVlanSearch ? $t('label.guest.vlan.search.keyword') : apiName === 'listVpnCustomerGateways' ? $t('label.vpn.customer.gateway.search.keyword') : isVpnUserSearch ? $t('label.vpn.user.search.keyword') : isAclSearch ? $t('label.acl.search.keyword') : isEventSearch ? $t('label.events.search.keyword') : apiName === 'listVnfAppliances' ? $t('label.vnf.name.search') : $t('label.search')"
        :title="isLdapSearch ? $t('label.ldap.search.host') : apiName === 'listIsos' ? $t('message.iso.search.keyword') : undefined"
        v-model:value="searchQuery"
        @search="onSearch">
        <template #addonBefore>
          <a-popover
            placement="bottomRight"
            trigger="click"
            v-model:visible="visibleFilter">
            <template #content v-if="visibleFilter">
              <a-form
                style="min-width: 170px"
                :class="{ 'acl-list-search-form': isAclSearch || isVpnUserSearch, 'infra-search-form': isInfrastructureSearch, 'storage-standard-search-form': ['listTemplates', 'listIsos', 'listVolumes', 'listServiceOfferings', 'listDiskOfferings'].includes(apiName) || isGuestNetworkSearch || isVnfSearch || isEventSearch, 'guest-network-search-form': isGuestNetworkSearch || isVnfSearch || isEventSearch }"
                :ref="formRef"
                :model="form"
                :rules="rules"
                layout="vertical"
                @finish="handleSubmit"
                v-ctrl-enter="handleSubmit">
                <a-form-item
                  v-for="(field, index) in fields"
                  :ref="field.name"
                  :name="field.name"
                  :key="index"
                  :label="retrieveFieldLabel(field.name)"
                  :html-for="isOauthSearch ? 'oauth-filter-' + field.name : isInfrastructureSearch ? infrastructureFilterId(field.name) : isZoneSearch ? 'zone-filter-' + field.name : isVpnUserSearch ? 'vpn-user-filter-' + field.name : isAclSearch && field.name === 'vpcid' ? 'pip-search-candidate-vpcid' : isKmsSearch ? 'kms-filter-' + field.name : undefined"
                  :extra="isLdapSearch && field.name === 'id' ? $t('message.ldap.search.uuid.only') : (isInfrastructureSearch || isVpnUserSearch || isIpv4SubnetSearch) && field.error ? $t('error.fetching.data') : ['listTemplates', 'listIsos'].includes(apiName) && ['name', 'isready', 'userdataid'].includes(field.name) ? $t('message.template.search.' + field.name) : undefined">
                  <PublicIpSearchSelect
                    v-if="isAclSearch && field.name === 'vpcid'"
                    v-model:value="form.vpcid"
                    kind="vpcid"
                    :scope="aclVpcScope"
                    @blocked="aclVpcBlocked = $event" />
                  <VnfSearchSelect
                    v-else-if="isVnfSearch && vnfPickerKinds.includes(field.name)"
                    :key="field.name + ':' + volumeLookupReset"
                    v-model:value="form[field.name]"
                    :kind="field.name"
                    :scope="vnfScope"
                    @update:value="onSelectFieldChange(field.name)"
                    @blocked="vnfBlocked[field.name] = $event" />
                  <VolumeSearchSelect
                    v-else-if="field.name === 'volumeid'"
                    :key="volumeLookupReset"
                    v-model:value="form.volumeid"
                    :scope="volumeScope"
                    @blocked="volumeLookupBlocked = $event" />
                  <NetworkSearchSelect
                    v-else-if="(isGuestNetworkSearch && ['networkofferingid', 'physicalnetworkid'].includes(field.name)) || (isGuestVlanSearch && field.name === 'physicalnetworkid')"
                    :key="field.name + ':' + volumeLookupReset"
                    v-model:value="form[field.name]"
                    :kind="field.name"
                    :scope="networkScope" />
                  <a-select
                    v-else-if="isIpv4SubnetSearch && ipv4SubnetZoneChoices.includes(field.name)"
                    v-model:value="form[field.name]"
                    :aria-label="retrieveFieldLabel(field.name)"
                    :options="field.opts.map(option => ({ value: option.id, label: option.name }))"
                    :loading="field.loading"
                    :get-popup-container="filterPopupContainer"
                    option-filter-prop="label"
                    show-search
                    allow-clear />
                  <a-select
                    allowClear
                    v-else-if="field.type==='list'"
                    :aria-label="(isInfrastructureSearch || isZoneSearch) ? retrieveFieldLabel(field.name) : undefined"
                    :id="isOauthSearch ? 'oauth-filter-' + field.name : isInfrastructureSearch ? infrastructureFilterId(field.name) : isZoneSearch ? 'zone-filter-' + field.name : isVpnUserSearch ? 'vpn-user-filter-' + field.name : isKmsSearch ? 'kms-filter-' + field.name : undefined"
                    :get-popup-container="isOauthSearch || isKmsSearch || isIpv4SubnetSearch ? filterPopupContainer : undefined"
                    v-model:value="form[field.name]"
                    showSearch
                    :dropdownMatchSelectWidth="false"
                    optionFilterProp="label"
                    :filterOption="field.name === 'virtualmachineid' && $route.name === 'vmsnapshot' ? false : (input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    @search="searchVmOptions($event, field)"
                    @popupScroll="moreVmOptions($event, field)"
                    :loading="field.loading"
                    :disabled="isInfrastructureSearch && field.loading || isVpnUserSearch && (field.loading || field.error || field.name === 'account' && !form.domainid) || isKmsSearch && (field.loading || field.name === 'account' && (!form.domainid || !!form.projectid) || ['domainid', 'account'].includes(field.name) && !!form.projectid) || apiName === 'listAffinityGroups' && (field.loading || !!field.error || (field.name === 'account' && !form.domainid))"
                    :placeholder="isVpnUserSearch && field.name === 'account' && !form.domainid ? $t('message.vpn.user.search.account.domain') : isKmsSearch && kmsBooleans.includes(field.name) ? $t('label.all') : (isGuestNetworkSearch && field.name === 'forvpc') || (apiName === 'listBackupSchedule' && ['quiescevm', 'intervaltype'].includes(field.name)) ? $t('label.all') : apiName === 'listAffinityGroups' && field.name === 'account' && !form.domainid ? $t('message.affinity.search.account.domain') : undefined"
                    @input="isInfrastructureSearch ? null : field.name === 'virtualmachineid' && $route.name === 'vmsnapshot' ? null : onchange($event, field.name)"
                    @change="onSelectFieldChange(field.name)">
                    <a-select-option
                      v-for="(opt, idx) in field.opts"
                      :key="idx"
                      :value="['account'].includes(field.name) ? opt.name : opt.id"
                      :label="field.name === 'vgpuprofileid' ? `${opt.gpucardname} - ${opt.name}` : $t((field.name.startsWith('domain') && opt.path) ? opt.path : opt.name)">
                      <div>
                        <span v-if="(field.name.startsWith('zone'))">
                          <span v-if="opt.icon">
                            <resource-icon :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                          </span>
                          <global-outlined v-else style="margin-right: 5px" />
                        </span>
                        <span v-if="(field.name.startsWith('domain') || field.name === 'account' || field.name.startsWith('associatednetwork'))">
                          <span v-if="opt.icon">
                            <resource-icon :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                          </span>
                          <block-outlined v-else style="margin-right: 5px" />
                        </span>
                        <span v-if="(field.name.startsWith('managementserver'))">
                          <status :text="opt.state" />
                        </span>
                        <span v-if="field.name === 'vgpuprofileid'">
                          {{ opt.gpucardname }} - {{ opt.name }}
                        </span>
                        <span v-else>
                          {{ $t((field.name.startsWith('domain') && opt.path) ? opt.path : opt.name) }}
                        </span>
                      </div>
                    </a-select-option>
                  </a-select>
                  <a-date-picker
                    v-else-if="field.type === 'eventDate'"
                    v-model:value="form[field.name]"
                    value-format="YYYY-MM-DD"
                    allow-clear
                    style="width: 100%"
                    :aria-label="retrieveFieldLabel(field.name)" />
                  <a-input
                    v-else-if="field.type==='input'"
                    v-model:value="form[field.name]"
                    :id="isOauthSearch ? 'oauth-filter-' + field.name : isInfrastructureSearch ? infrastructureFilterId(field.name) : isZoneSearch ? 'zone-filter-' + field.name : isVpnUserSearch ? 'vpn-user-filter-' + field.name : undefined"
                    :aria-label="apiName === 'listWebhooks' || isOauthSearch || isInfrastructureSearch || isZoneSearch || isEventSearch || isVpnUserSearch ? retrieveFieldLabel(field.name) : undefined" />
                  <div v-else-if="field.type==='tag'">
                    <a-input-group
                      type="text"
                      size="small"
                      compact>
                      <a-input ref="input" v-model:value="inputKey" style="width: 50px; text-align: center" :placeholder="$t('label.key')" />
                      <a-input
                        class="tag-disabled-input"
                        style=" width: 20px; border-left: 0; pointer-events: none; text-align: center"
                        placeholder="="
                        disabled />
                      <a-input v-model:value="inputValue" style="width: 50px; text-align: center; border-left: 0" :placeholder="$t('label.value')" />
                      <tooltip-button :tooltip="$t('label.clear')" icon="close-outlined" size="small" @onClick="inputKey = inputValue = ''" />
                    </a-input-group>
                  </div>
                  <a-switch
                    v-else-if="field.type==='boolean'"
                    v-model:checked="form[field.name]"
                  />
                  <a-auto-complete
                    v-else-if="field.type==='autocomplete'"
                    :id="isKmsSearch ? 'kms-filter-' + field.name : undefined"
                    :get-popup-container="isKmsSearch ? filterPopupContainer : undefined"
                    v-model:value="form[field.name]"
                    :placeholder="isEventSearch && field.name === 'type' ? $t('message.events.search.type.placeholder') : isKmsSearch ? $t('label.all') : $t('message.error.input.value')"
                    :aria-label="isEventSearch ? retrieveFieldLabel(field.name) : undefined"
                    :options="field.opts"
                    :filterOption="(inputValue, option) => {
                      return option.value.toLowerCase().indexOf(inputValue.toLowerCase()) !== -1
                    }" />
                  <small v-if="isEventSearch && field.name === 'type' && field.eventTypesUnavailable">{{ $t('message.events.search.type.unavailable') }}</small>
                  <small v-if="isEventSearch && field.name === 'startdate'">{{ $t('message.events.search.date.scope') }}</small>
                  <a-alert v-if="(isDnsZoneSearch || isDnsServerSearch) && field.error" type="error" :message="$t('error.fetching.data')">
                    <template #description><a-button size="small" @click="fetchDnsSearchOptions(field)">{{ $t('label.retry') }}</a-button></template>
                  </a-alert>
                  <a-alert v-if="apiName === 'listAffinityGroups' && field.error" type="error" :message="$t('error.fetching.data')">
                    <template #description><a-button size="small" @click="fetchAffinityFilters([field.name])">{{ $t('label.retry') }}</a-button></template>
                  </a-alert>
                </a-form-item>
                <a-alert v-if="isEventSearch && eventFilterError" type="error" show-icon :message="$t(eventFilterError)" />
                <div class="filter-group-button" :class="{ 'event-search-actions': isEventSearch }">
                  <a-button v-if="isEventSearch" size="small" @click="visibleFilter = false">{{ $t('label.cancel') }}</a-button>
                  <a-button
                    class="filter-group-button-clear"
                    type="default"
                    size="small"
                    @click="onClear">
                    <template #icon><stop-outlined /></template>
                    {{ $t('label.reset') }}
                  </a-button>
                  <a-button
                    class="filter-group-button-search"
                    type="primary"
                    size="small"
                    ref="submit"
                    :disabled="(fields.some(field => field.name === 'volumeid') && volumeLookupBlocked) || (isVnfSearch && Object.values(vnfBlocked).some(Boolean)) || (isAclSearch && aclVpcBlocked)"
                    html-type="submit">
                    <template #icon><search-outlined /></template>
                    {{ $t('label.search') }}
                  </a-button>
                </div>
              </a-form>
            </template>
            <a-button
              class="filter-button"
              size="small"
              :aria-label="$t('label.advanced.search')"
                  @click="() => { if (!isOauthSearch && !isLdapSearch && !isInfrastructureSearch && !isDnsZoneSearch && !isDnsServerSearch && !isIpv4SubnetSearch && !isGuestVlanSearch && !isAclSearch && !isVpnUserSearch && !['listTemplates', 'listIsos', 'listCniConfiguration', 'listAffinityGroups', 'listVnfAppliances', 'listEvents', 'listRoles', 'listExtensions'].includes(apiName)) searchQuery = null }">
              <filter-two-tone v-if="isFiltered" />
              <filter-outlined v-else />
            </a-button>
          </a-popover>
        </template>
      </a-input-search>
    </span>
  </span>
</template>

<script>
import { clusterFilterOptions } from '@/utils/clusterSearch'
import { hostSearchLabels } from '@/utils/hostSearch'
import { roleStates, roleTypes } from '@/utils/roleSearch'
import KubernetesIsoSearch from '@/components/view/KubernetesIsoSearch'
import { ref, reactive, toRaw } from 'vue'
import { kmsApis, kmsBooleans, kmsLookup, kmsProjectScope, kmsProtocolOptions } from '@/utils/kmsSearch'
import { ipv4SubnetApi, ipv4SubnetFilterLabel, ipv4SubnetFilterOptions, ipv4SubnetZoneChoices } from '@/utils/ipv4Subnet'
import { getAPI } from '@/api'
import { eventSearchError } from '@/utils/eventSearch'
import { vpnUserProject, vpnUserSearchAuth } from '@/utils/vpnUserSearch'
import { sourceToken } from '@/utils/request'
import { isAdmin } from '@/role'
import TooltipButton from '@/components/widgets/TooltipButton'
import ResourceIcon from '@/components/view/ResourceIcon'
import Status from '@/components/widgets/Status'
import S2sGatewaySearch from './S2sGatewaySearch'
import AsnSearch from '@/components/view/AsnSearch'
import PublicIpSearch from '@/components/view/PublicIpSearch'
import PublicIpSearchSelect from '@/components/view/PublicIpSearchSelect'
import { publicIpProject } from '@/utils/publicIpSearch'
import SnapshotSearch from '@/components/view/SnapshotSearch'
import SnapshotPolicySearch from '@/components/view/SnapshotPolicySearch'
import RuntimeCatalogSearch from '@/components/view/RuntimeCatalogSearch'
import VolumeSearchSelect from '@/components/view/VolumeSearchSelect'
import NetworkSearchSelect from '@/components/view/NetworkSearchSelect'
import VnfSearchSelect from '@/components/view/VnfSearchSelect'
import { vnfPickerKinds, vnfPickerConfig, vnfStates, vnfProject, vnfSearchError, vnfAuth } from '@/utils/vnfSearch'
import { i18n } from '@/locales'
import { affinityOwnerOptions, affinityTypeOptions } from '@/utils/affinityGroupCreation'

export default {
  name: 'SearchView',
  components: {
    KubernetesIsoSearch,
    TooltipButton,
    ResourceIcon,
    Status,
    SnapshotSearch,
    PublicIpSearch,
    PublicIpSearchSelect,
    S2sGatewaySearch,
    AsnSearch,
    SnapshotPolicySearch,
    RuntimeCatalogSearch,
    VolumeSearchSelect,
    NetworkSearchSelect,
    VnfSearchSelect
  },
  props: {
    s2sGatewaySearchFailure: { type: String, default: '' },
    asnSearchFailure: { type: String, default: '' },
    searchFilters: {
      type: Array,
      default: () => []
    },
    apiName: {
      type: String,
      default: () => ''
    },
    searchParams: {
      type: Object,
      default: () => ({})
    }
  },
  data () {
    return {
      searchQuery: null,
      paramsFilter: {},
      eventFilterError: '',
      visibleFilter: false,
      aclVpcBlocked: false,
      fields: [],
      inputKey: null,
      inputValue: null,
      fieldValues: {},
      isFiltered: false,
      alertTypes: [],
      vmOptionPage: 1,
      vmOptionTotal: 0,
      vmOptionKeyword: '',
      cniOptionsVersion: {},
      affinityOptionsVersion: {},
      vmOptionVersion: 0,
      volumeLookupReset: 0,
      volumeLookupBlocked: true,
      vnfPickerKinds,
      vnfBlocked: {},
      kmsBooleans,
      kmsLookupVersion: {}
    }
  },
  created () {
    this.formRef = ref()
    this.form = reactive({})
    this.rules = reactive({})
  },
  watch: {
    vpnUserFilterContext: {
      flush: 'sync',
      handler () {
        if (!this.isVpnUserSearch) return
        this.visibleFilter = false
        this.form = reactive({})
        this.fields = []
      }
    },
    vnfSearchContext: { flush: 'sync', handler () { if (this.isVnfSearch) { this.visibleFilter = false; this.form = reactive({}); this.fields = []; this.vnfBlocked = {}; this.volumeLookupReset++ } } },
    volumeOwnerContext () {
      this.visibleFilter = false
      if (['listTemplates', 'listIsos'].includes(this.apiName)) { this.form = reactive({}); this.fields = [] }
      this.form.volumeid = undefined
      this.volumeLookupReset++
    },
    visibleFilter (newValue, oldValue) {
      if (newValue) {
        this.initFormFieldData()
      }
    },
    searchParams () {
      if (this.apiName !== 'listWebhookDeliveries') return
      this.searchQuery = this.searchParams.searchQuery || ''
      this.updateIsFiltered()
      if (this.visibleFilter) this.fillFormFieldValues()
    },
    '$route' (to, from) {
      if (this.apiName === 'listWebhookDeliveries') return
      this.searchQuery = ''
      if (to && to.query && 'q' in to.query) {
        this.searchQuery = to.query.q
      }
      if (this.apiName === 'listExtensions' || this.isInfrastructureSearch || this.isZoneSearch || this.isDnsZoneSearch || this.isDnsServerSearch) this.searchQuery = to.query.keyword || ''
      if (this.isOauthSearch || this.isLdapSearch || this.isIpv4SubnetSearch && !this.searchQuery) this.searchQuery = to.query.keyword || ''
      this.updateIsFiltered()
      if (this.isAclSearch || this.isVpnUserSearch || this.isVpnCustomerGatewaySearch || this.isGuestVlanSearch || this.isIpv4SubnetSearch) this.visibleFilter = false
      if (this.apiName === 'listIsos' && to?.meta?.name === 'iso' && this.visibleFilter) this.fillFormFieldValues()
      if (['listBackups', 'listBackupSchedule'].includes(this.apiName) && this.visibleFilter) this.fillFormFieldValues()
      if (this.apiName === 'listAffinityGroups' && to?.meta?.name === 'affinitygroup' && this.visibleFilter) {
        this.fillFormFieldValues()
        this.fetchAffinityFilters(this.fields.map(field => field.name))
      }
      if (this.isKmsSearch && this.visibleFilter) this.initFormFieldData()
      if (this.isVnfSearch && this.visibleFilter) { this.fillFormFieldValues(); this.vnfBlocked = {}; this.volumeLookupReset++ }
    }
  },
  mounted () {
    if (this.apiName === 'listWebhookDeliveries') {
      this.searchQuery = this.searchParams.searchQuery || ''
      this.updateIsFiltered()
      return
    }
    this.searchQuery = ''
    if (this.$route && this.$route.query && 'q' in this.$route.query) {
      this.searchQuery = this.$route.query.q
    }
    if (this.apiName === 'listExtensions' || this.isInfrastructureSearch || this.isZoneSearch || this.isDnsZoneSearch || this.isDnsServerSearch) this.searchQuery = this.$route.query.keyword || ''
    if (this.isOauthSearch || this.isLdapSearch || this.isIpv4SubnetSearch && !this.searchQuery) this.searchQuery = this.$route.query.keyword || ''
    this.updateIsFiltered()
  },
  computed: {
    isKmsSearch () { return kmsApis.includes(this.apiName) },
    isVpnUserSearch () { return this.apiName === 'listVpnUsers' },
    isAccountSearch () { return this.apiName === 'listAccounts' },
    isRoleSearch () { return this.apiName === 'listRoles' },
    isVpnCustomerGatewaySearch () { return this.apiName === 'listVpnCustomerGateways' },
    vpnUserFilterContext () {
      return this.isVpnUserSearch
        ? JSON.stringify([vpnUserProject(this.$route.query, this.$store.getters), vpnUserSearchAuth(this.$store.getters, this.$store.state?.user?.token)])
        : null
    },
    isEventSearch () { return this.apiName === 'listEvents' },
    isVnfSearch () { return this.apiName === 'listVnfAppliances' },
    vnfSearchContext () { return this.isVnfSearch ? vnfAuth(this.$store.getters, this.$store.state?.user?.token) : '' },
    vnfScope () { return { ...Object.fromEntries(['zoneid', 'domainid', 'account'].filter(k => this.form[k]).map(k => [k, this.form[k]])), projectid: this.$route.query.projectid || vnfProject(this.$store.getters) } },
    isAclSearch () { return this.apiName === 'listNetworkACLLists' && this.$route.meta?.name === 'acllist' },
    aclVpcScope () { return { projectid: publicIpProject(this.$store.getters) } },
    isHostSearch () { return this.$route.meta?.name === 'host' && ['listHosts', 'listHostsMetrics'].includes(this.apiName) },
    isClusterSearch () { return this.$route.meta?.name === 'cluster' && ['listClusters', 'listClustersMetrics'].includes(this.apiName) },
    isPrimaryStorageSearch () { return this.$route.meta?.name === 'storagepool' && ['listStoragePools', 'listStoragePoolsMetrics'].includes(this.apiName) },
    isSecondaryStorageSearch () { return this.$route.meta?.name === 'imagestore' && this.apiName === 'listImageStores' },
    isInfrastructureSearch () { return this.isHostSearch || this.isClusterSearch || this.isPrimaryStorageSearch || this.isSecondaryStorageSearch },
    isZoneSearch () { return this.$route.meta?.name === 'zone' && ['listZones', 'listZonesMetrics'].includes(this.apiName) },
    isDnsServerSearch () { return this.apiName === 'listDnsServers' },
    isDnsZoneSearch () { return this.apiName === 'listDnsZones' },
    isLdapSearch () { return this.$route.meta?.name === 'ldapsetting' },
    isOauthSearch () { return this.$route.meta?.name === 'oauthsetting' },
    isIpv4SubnetSearch () { return this.apiName === ipv4SubnetApi },
    ipv4SubnetZoneChoices () { return ipv4SubnetZoneChoices },
    isGuestVlanSearch () { return this.apiName === 'listGuestVlans' },
    isGuestNetworkSearch () { return this.apiName === 'listNetworks' && this.$route.meta?.name === 'guestnetwork' },
    networkScope () { return Object.fromEntries(['zoneid', 'domainid', 'account'].filter(k => this.form[k]).map(k => [k, this.form[k]])) },
    volumeOwnerContext () {
      return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$route.query.projectid, Object.keys(this.$store.getters.apis || {}).sort()])
    },
    volumeScope () {
      const projectid = this.$route.query.projectid || this.$store.getters.project?.id || (this.$store.getters.listAllProjects ? '-1' : '')
      if (projectid) return { projectid }
      return Object.fromEntries(['domainid', 'account', 'zoneid'].filter(key => this.searchFilters.includes(key) && this.form[key]).map(key => [key, this.form[key]]))
    },
    styleSearch () {
      if (!this.searchFilters || this.searchFilters.length === 0) {
        return {
          width: '100%',
          display: 'table-cell'
        }
      }

      return {
        width: '100%',
        display: 'table-cell',
        lineHeight: '31px'
      }
    }
  },
  methods: {
    filterPopupContainer (trigger) { return trigger.parentElement },
    async searchVmOptions (keyword, field) {
      if (field.name !== 'virtualmachineid' || this.$route.name !== 'vmsnapshot') return
      this.vmOptionKeyword = keyword; this.vmOptionPage = 1
      const version = ++this.vmOptionVersion
      field.loading = true
      try {
        const response = await this.fetchVirtualMachines(keyword, 1)
        if (version === this.vmOptionVersion) field.opts = response.data || []
      } catch (error) { this.$notifyError(error) } finally { if (version === this.vmOptionVersion) field.loading = false }
    },
    async moreVmOptions (event, field) {
      if (field.name !== 'virtualmachineid' || this.$route.name !== 'vmsnapshot' || field.loading || field.opts.length >= this.vmOptionTotal) return
      if (event.target.scrollHeight - event.target.scrollTop - event.target.clientHeight > 24) return
      const version = this.vmOptionVersion
      field.loading = true
      try {
        const response = await this.fetchVirtualMachines(this.vmOptionKeyword, this.vmOptionPage + 1)
        if (version === this.vmOptionVersion) { this.vmOptionPage++; field.opts = [...new Map([...field.opts, ...(response.data || [])].map(row => [row.id, row])).values()] }
      } catch (error) { this.$notifyError(error) } finally { if (version === this.vmOptionVersion) field.loading = false }
    },
    onchange: async function (event, fieldname) {
      this.fetchDynamicFieldData(fieldname, event.target.value)
    },
    onSelectFieldChange (fieldname) {
      if (this.isInfrastructureSearch) {
        if (fieldname === 'zoneid') {
          this.form.podid = undefined
          this.form.clusterid = undefined
          if (this.isPrimaryStorageSearch) this.form.hostid = undefined
          this.fetchInfrastructureSearchOptions(['podid', 'clusterid', 'hostid'])
        } else if (fieldname === 'podid') {
          this.form.clusterid = undefined
          if (this.isPrimaryStorageSearch) this.form.hostid = undefined
          this.fetchInfrastructureSearchOptions(['clusterid', 'hostid'])
        } else if (fieldname === 'clusterid' && this.isPrimaryStorageSearch) {
          this.form.hostid = undefined
          this.fetchInfrastructureSearchOptions(['hostid'])
        }
        return
      }
      if (this.isVpnUserSearch) {
        if (fieldname === 'domainid') {
          this.form.account = undefined
          this.fetchAffinityFilters(['account'])
        }
        return
      }
      if (this.isKmsSearch) {
        if (fieldname === 'domainid') {
          this.form.account = undefined
          this.fetchKmsFields(['account'])
        }
        if (fieldname === 'projectid') {
          this.form.account = undefined
          this.form.domainid = undefined
          this.form.hsmprofileid = undefined
          this.fetchKmsFields(['account', 'hsmprofileid'])
        }
        return
      }
      if (this.isVnfSearch) {
        if (fieldname === 'zoneid') ['networkid', 'templateid', 'serviceofferingid'].forEach(k => { this.form[k] = undefined })
        if (fieldname === 'domainid') ['account', 'networkid', 'templateid', 'groupid', 'serviceofferingid'].forEach(k => { this.form[k] = undefined })
        if (fieldname === 'account') ['networkid', 'templateid', 'groupid', 'serviceofferingid'].forEach(k => { this.form[k] = undefined })
        return
      }
      if (this.isIpv4SubnetSearch && fieldname === 'zoneid') {
        ipv4SubnetZoneChoices.forEach(key => { this.form[key] = undefined })
        this.fetchIpv4SubnetOptions()
      }
      if ((this.isGuestNetworkSearch || this.isGuestVlanSearch) && fieldname === 'zoneid') this.form.physicalnetworkid = undefined
      if (this.apiName === 'listAffinityGroups') {
        if (fieldname === 'domainid') {
          this.form.account = undefined; this.form.projectid = undefined
          this.fetchAffinityFilters(['account', 'projectid'])
        } else if (fieldname === 'projectid' && this.form.projectid) this.form.account = undefined
        else if (fieldname === 'account' && this.form.account) this.form.projectid = undefined
        return
      }
      const fetchAccountOptions = fieldname === 'domainid' && this.fields.some((field) => field.name === 'account')
      if (fetchAccountOptions) {
        if (this.isEventSearch || this.isGuestNetworkSearch || this.apiName === 'listCniConfiguration') this.form.account = undefined
        this.fetchDynamicFieldData('account')
      }

      const fetchVgpuProfileOptions = fieldname === 'gpucardid' && this.fields.some((field) => field.name === 'vgpuprofileid')
      if (fetchVgpuProfileOptions) {
        // Clear the currently selected vGPU profile when GPU card changes
        this.form.vgpuprofileid = null
        this.fetchDynamicFieldData('vgpuprofileid')
      }
    },
    onVisibleForm () {
      this.visibleFilter = !this.visibleFilter
      if (!this.visibleFilter) return
      this.initFormFieldData()
    },
    infrastructureFilterId (key) {
      if (this.isSecondaryStorageSearch) return 'secondary-storage-filter-' + key
      return (this.isPrimaryStorageSearch ? 'primary-storage-filter-' : this.isClusterSearch ? 'cluster-filter-' : 'host-filter-') + key
    },
    retrieveFieldLabel (fieldName) {
      if (this.apiName === 'listWebhooks' && fieldName === 'id') return 'UUID'
      if (this.apiName === 'listWebhooks' && fieldName === 'name') return this.$t('label.webhook.search.exact')
      if (this.apiName === 'listExtensions') return this.$t(fieldName === 'id' ? 'label.extension.uuid' : 'label.extension.name.exact')
      if (this.apiName === 'listBackups' && fieldName === 'virtualmachineid') return this.$t('label.virtualmachinename')
      if ((this.isClusterSearch || this.isPrimaryStorageSearch) && fieldName === 'storageaccessgroup') return this.$t('label.storageaccessgroups')
      if (this.isHostSearch && hostSearchLabels[fieldName]) return this.$t(hostSearchLabels[fieldName])
      if ((this.isRoleSearch || this.isAccountSearch) && fieldName === 'id') return 'UUID'
      if (this.isAccountSearch && ['name', 'isrecursive', 'iscleanuprequired'].includes(fieldName)) return this.$t('label.account.search.' + fieldName)
      if (this.apiName === 'listVpnCustomerGateways' && fieldName === 'keyword') return this.$t('label.vpn.customer.gateway.search.keyword')
      if (this.isVpnUserSearch && fieldName === 'username') return this.$t('label.vpn.user.search.exact')
      if (this.isVpnUserSearch && fieldName === 'domainid') return this.$t('label.domain')
      if (this.isAclSearch && fieldName === 'name') return this.$t('label.acl.search.exact')
      if (this.isEventSearch && ['keyword', 'type', 'eventuuid', 'resourceid'].includes(fieldName)) {
        return this.$t('label.events.search.' + fieldName)
      }
      if (this.isKmsSearch && fieldName === 'projectid') return this.$t('label.project')
      if (this.isVnfSearch && vnfPickerConfig(fieldName)) return this.$t(vnfPickerConfig(fieldName).label)
      if (this.apiName === 'listVnfAppliances' && fieldName === 'name') return this.$t('label.vm.name')
      if (['listTemplates', 'listIsos'].includes(this.apiName) && fieldName === 'name') return this.$t('label.template.name.exact')
      if (this.isGuestNetworkSearch && fieldName === 'keyword') return this.$t('label.name')
      if (fieldName === 'networkofferingid') return this.$t('label.network.offering')
      if (this.isDnsServerSearch && fieldName === 'providertype') return this.$t('label.provider')
      if (this.isDnsZoneSearch && fieldName === 'dnsserverid') return this.$t('label.dns.server')
      if (this.isLdapSearch && fieldName === 'domainid') return this.$t('label.domain')
      if (this.isOauthSearch && fieldName === 'provider') return this.$t('label.oauth.search.provider')
      if (this.isOauthSearch && fieldName === 'domainid') return this.$t('label.domain')
      if (this.isLdapSearch && fieldName === 'id') return this.$t('label.ldap.configuration.uuid')
      if (this.isIpv4SubnetSearch && ipv4SubnetFilterLabel[fieldName]) return this.$t(ipv4SubnetFilterLabel[fieldName])
      if (this.isGuestVlanSearch && fieldName === 'vnet') return this.$t('label.guest.vlan.search.exact')
      if (fieldName === 'physicalnetworkid') return this.$t('label.physical.network')
      if (fieldName === 'forvpc') return this.$t('label.network.search.forvpc')
      if (this.apiName === 'listAffinityGroups' && fieldName === 'projectid') return this.$t('label.project')
      if (fieldName === 'groupid') {
        fieldName = 'group'
      }
      if (fieldName === 'hsmprofileid') {
        fieldName = 'hsm.profile'
      }
      if (fieldName === 'kmskeyid') {
        fieldName = 'kms.key'
      }
      if (fieldName === 'keyword') {
        if ('listAnnotations' in this.$store.getters.apis) {
          return this.$t('label.annotation')
        } else {
          return this.$t('label.name')
        }
      }
      return this.$t('label.' + fieldName)
    },
    initFields () {
      const arrayField = []
      this.fields = []
      this.searchFilters.forEach(item => {
        let type = 'input'
        if ((this.isInfrastructureSearch || this.isZoneSearch || this.isAccountSearch) && !this.$getApiParams(this.apiName)?.[item]) return
        if (this.isEventSearch && !this.$getApiParams('listEvents')?.[item]) return
        if (this.isEventSearch && item === 'account' &&
            (this.$route.query.projectid ? this.$route.query.projectid !== '0' : this.$store.getters.project?.id || this.$store.getters.listAllProjects)) return
        if (this.isVnfSearch && !(item === 'tags' ? this.$getApiParams('listVnfAppliances')?.tags : this.$getApiParams('listVnfAppliances')?.[item])) return
        if (this.isVnfSearch && ['account', 'domainid'].includes(item) && (this.$route.query.projectid || vnfProject(this.$store.getters)) !== '0') return
        if (['listTemplates', 'listIsos'].includes(this.apiName) && item !== 'tags' && !this.$getApiParams(this.apiName)?.[item]) return

        if (item === 'domainid' && !('listDomains' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'account' && !('listAccounts' in this.$store.getters.apis)) {
          return true
        }
        if (!this.isVpnUserSearch && !this.isKmsSearch && item === 'account' && !('addAccountToProject' in this.$store.getters.apis || 'createAccount' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'podid' && !('listPods' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'clusterid' && !('listClusters' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'groupid' && !('listInstanceGroups' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'associatednetworkid' && this.$route.meta.name === 'asnumbers') {
          item = 'networkid'
        }
        if (item === 'usagetype' && !('listUsageTypes' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'volumeid' && !('listVolumes' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'backupofferingid' && !('listBackupOfferings' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'hsmprofileid' && !('listHSMProfiles' in this.$store.getters.apis)) {
          return true
        }
        if (item === 'kmskeyid' && !('listKMSKeys' in this.$store.getters.apis)) {
          return true
        }
        if (['zoneid', 'domainid', 'imagestoreid', 'storageid', 'state', 'account', 'hypervisor', 'level',
          'clusterid', 'podid', 'groupid', 'entitytype', 'accounttype', 'systemvmtype', 'scope', 'provider',
          'type', 'scope', 'managementserverid', 'serviceofferingid',
          'diskofferingid', 'networkid', 'usagetype', 'restartrequired', 'gpuenabled',
          'displaynetwork', 'forvpc', 'guestiptype', 'usersource', 'arch', 'oscategoryid', 'templatetype', 'gpucardid', 'vgpuprofileid',
          'extensionid', 'backupoffering', 'volumeid', 'virtualmachineid', 'hsmprofileid', 'kmskeyid', 'current'].includes(item) || (item === 'status' && this.apiName === 'listBackups')
        ) {
          type = 'list'
        } else if (item === 'tags') {
          type = 'tag'
        } else if (['resourcetype', 'apikeyaccess'].includes(item)) {
          type = 'autocomplete'
        } else if (item === 'isencrypted') {
          type = 'boolean'
        }

        if (this.isOauthSearch && item === 'provider') type = 'input'
        if (this.apiName === 'listAffinityGroups' && item === 'projectid') {
          if (!('listProjects' in this.$store.getters.apis)) return
          type = 'list'
        }
        if ((this.apiName === 'listTemplates' && ['isready', 'forcks'].includes(item)) || (this.apiName === 'listIsos' && ['bootable', 'isready', 'ispublic'].includes(item))) type = 'list'
        if (this.apiName === 'listBackupSchedule' && ['quiescevm', 'intervaltype'].includes(item)) type = 'list'
        if (this.isAccountSearch && ['isrecursive', 'iscleanuprequired'].includes(item)) type = 'list'
        if (this.isKmsSearch && [...kmsBooleans, 'projectid'].includes(item)) type = 'list'
        if (this.isKmsSearch && item === 'protocol') type = 'autocomplete'
        if (this.isInfrastructureSearch) {
          const apis = this.$store.getters.apis
          if (['outofbandmanagementenabled', 'outofbandmanagementpowerstate'].includes(item) || (this.isClusterSearch && clusterFilterOptions[item])) type = 'list'
          if (item === 'storageaccessgroup' && apis.listStorageAccessGroups) type = 'list'
          if (this.isPrimaryStorageSearch && item === 'scope') type = 'list'
          if (this.isPrimaryStorageSearch && item === 'hostid') type = apis.listHosts ? 'list' : 'input'
          if (item === 'managementserverid' && !apis.listManagementServers) type = 'input'
        }
        if (this.isSecondaryStorageSearch && item === 'readonly') type = 'list'
        if (this.isZoneSearch && item === 'networktype') type = 'list'
        if (this.isDnsZoneSearch && item === 'dnsserverid' || this.isDnsServerSearch && item === 'providertype') type = 'list'
        if (this.isEventSearch && item === 'type') type = 'autocomplete'
        if (this.isEventSearch && ['startdate', 'enddate'].includes(item)) type = 'eventDate'
        this.fields.push({
          type: type,
          name: item,
          opts: [],
          loading: false
        })
        arrayField.push(item)
      })
      return arrayField
    },
    fetchStaticFieldData (arrayField) {
      if (this.apiName === 'listBackupSchedule') {
        this.fields.filter(field => field.name === 'quiescevm').forEach(field => { field.opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }] })
        this.fields.filter(field => field.name === 'intervaltype').forEach(field => { field.opts = ['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY'].map(id => ({ id, name: 'label.' + id.toLowerCase() })) })
      }
      if (this.isZoneSearch) {
        const field = this.fields.find(field => field.name === 'networktype')
        if (field) field.opts = ['Basic', 'Advanced'].map(id => ({ id, name: id }))
      }
      if (this.isVnfSearch) { const state = this.fields.find(field => field.name === 'state'); if (state) state.opts = vnfStates(this.$store.getters).map(id => ({ id, name: 'state.' + id.toLowerCase() })); return }
      if (this.isAccountSearch) {
        this.fields.filter(field => ['isrecursive', 'iscleanuprequired'].includes(field.name)).forEach(field => {
          field.opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }]
        })
      }
      if (this.apiName === 'listIsos') this.fields.filter(field => ['bootable', 'isready', 'ispublic'].includes(field.name)).forEach(field => { field.opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }] })
      if (this.apiName === 'listTemplates') {
        this.fields.filter(field => ['isready', 'forcks'].includes(field.name)).forEach(field => { field.opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }] })
      }
      if (this.isGuestNetworkSearch && arrayField.includes('forvpc')) this.fields.find(f => f.name === 'forvpc').opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }]
      if (this.$route.name === 'vmsnapshot') {
        const type = this.fields.find(field => field.name === 'type')
        if (type) type.opts = [{ id: 'Disk', name: 'label.vmsnapshot.disk' }, { id: 'DiskAndMemory', name: 'label.vmsnapshot.disk.memory' }]
        const current = this.fields.find(field => field.name === 'current')
        if (current) current.opts = [{ id: 'true', name: 'label.vmsnapshot.current.yes' }, { id: 'false', name: 'label.vmsnapshot.current.filter.no' }]
      }
      if (arrayField.includes('displaynetwork')) {
        const typeIndex = this.fields.findIndex(item => item.name === 'displaynetwork')
        this.fields[typeIndex].loading = true
        this.fields[typeIndex].opts = this.fetchBoolean()
        this.fields[typeIndex].loading = false
      }
      if (arrayField.includes('type') || arrayField.includes('guestiptype')) {
        if (this.$route.path.includes('/guestnetwork') || this.$route.path.includes('/networkoffering')) {
          const typeIndex = this.fields.findIndex(item => ['type', 'guestiptype'].includes(item.name))
          this.fields[typeIndex].loading = true
          this.fields[typeIndex].opts = this.fetchGuestNetworkTypes()
          this.fields[typeIndex].loading = false
        } else if (this.$route.path === '/role' || this.$route.path.includes('/role/')) {
          const typeIndex = this.fields.findIndex(item => item.name === 'type')
          this.fields[typeIndex].loading = true
          this.fields[typeIndex].opts = this.fetchRoleTypes()
          this.fields[typeIndex].loading = false
        }
      }

      if (arrayField.includes('scope')) {
        const scopeIndex = this.fields.findIndex(item => item.name === 'scope')
        this.fields[scopeIndex].loading = true
        this.fields[scopeIndex].opts = this.fetchScope()
        this.fields[scopeIndex].loading = false
      }

      if (arrayField.includes('state')) {
        const stateIndex = this.fields.findIndex(item => item.name === 'state')
        this.fields[stateIndex].loading = true
        this.fields[stateIndex].opts = this.fetchState()
        this.fields[stateIndex].loading = false
      }

      if (arrayField.includes('level')) {
        const levelIndex = this.fields.findIndex(item => item.name === 'level')
        this.fields[levelIndex].loading = true
        this.fields[levelIndex].opts = this.fetchLevel()
        this.fields[levelIndex].loading = false
      }

      if (arrayField.includes('entitytype')) {
        const entityTypeIndex = this.fields.findIndex(item => item.name === 'entitytype')
        this.fields[entityTypeIndex].loading = true
        this.fields[entityTypeIndex].opts = this.fetchEntityType()
        this.fields[entityTypeIndex].loading = false
      }

      if (arrayField.includes('accounttype')) {
        const accountTypeIndex = this.fields.findIndex(item => item.name === 'accounttype')
        this.fields[accountTypeIndex].loading = true
        this.fields[accountTypeIndex].opts = this.fetchAccountTypes()
        this.fields[accountTypeIndex].loading = false
      }

      if (arrayField.includes('systemvmtype')) {
        const systemVmTypeIndex = this.fields.findIndex(item => item.name === 'systemvmtype')
        this.fields[systemVmTypeIndex].loading = true
        this.fields[systemVmTypeIndex].opts = this.fetchSystemVmTypes()
        this.fields[systemVmTypeIndex].loading = false
      }

      if (arrayField.includes('scope')) {
        const scopeIndex = this.fields.findIndex(item => item.name === 'scope')
        this.fields[scopeIndex].loading = true
        this.fields[scopeIndex].opts = this.fetchStoragePoolScope()
        this.fields[scopeIndex].loading = false
      }

      if (this.isSecondaryStorageSearch && arrayField.includes('readonly')) {
        this.fields.find(field => field.name === 'readonly').opts = [
          { id: 'true', name: this.$t('label.yes') },
          { id: 'false', name: this.$t('label.no') }
        ]
      }
      if (arrayField.includes('provider')) {
        const providerIndex = this.fields.findIndex(item => item.name === 'provider')
        this.fields[providerIndex].loading = true
        this.fields[providerIndex].opts = this.fetchImageStoreProviders()
        this.fields[providerIndex].loading = false
      }

      if (arrayField.includes('restartrequired')) {
        const restartRequiredIndex = this.fields.findIndex(item => item.name === 'restartrequired')
        this.fields[restartRequiredIndex].loading = true
        this.fields[restartRequiredIndex].opts = [
          { id: 'true', name: 'label.yes' },
          { id: 'false', name: 'label.no' }
        ]
        this.fields[restartRequiredIndex].loading = false
      }

      if (arrayField.includes('gpuenabled')) {
        const gpuEnabledIndex = this.fields.findIndex(item => item.name === 'gpuenabled')
        this.fields[gpuEnabledIndex].loading = true
        this.fields[gpuEnabledIndex].opts = [
          { id: 'true', name: 'label.yes' },
          { id: 'false', name: 'label.no' }
        ]
        this.fields[gpuEnabledIndex].loading = false
      }

      if (arrayField.includes('resourcetype')) {
        const resourceTypeIndex = this.fields.findIndex(item => item.name === 'resourcetype')
        this.fields[resourceTypeIndex].loading = true
        this.fields[resourceTypeIndex].opts = [
          { value: 'Account' },
          { value: 'Domain' },
          { value: 'Iso' },
          { value: 'Network' },
          { value: 'Template' },
          { value: 'User' },
          { value: 'VirtualMachine' },
          { value: 'Volume' },
          { value: 'QuotaTariff' },
          { value: 'DisasterRecoveryCluster' }
        ]
        this.fields[resourceTypeIndex].loading = false
      }

      if (arrayField.includes('apikeyaccess')) {
        const apiKeyAccessIndex = this.fields.findIndex(item => item.name === 'apikeyaccess')
        this.fields[apiKeyAccessIndex].loading = true
        this.fields[apiKeyAccessIndex].opts = [
          { value: 'Disabled' },
          { value: 'Enabled' },
          { value: 'Inherit' }
        ]
        this.fields[apiKeyAccessIndex].loading = false
      }

      if (arrayField.includes('usersource')) {
        const userSourceIndex = this.fields.findIndex(item => item.name === 'usersource')
        this.fields[userSourceIndex].loading = true
        this.fields[userSourceIndex].opts = this.fetchAvailableUserSourceTypes()
        this.fields[userSourceIndex].loading = false
      }

      if (arrayField.includes('arch')) {
        const typeIndex = this.fields.findIndex(item => item.name === 'arch')
        this.fields[typeIndex].loading = true
        this.fields[typeIndex].opts = this.$fetchCpuArchitectureTypes()
        this.fields[typeIndex].loading = false
      }

      if (arrayField.includes('templatetype')) {
        const typeIndex = this.fields.findIndex(item => item.name === 'templatetype')
        this.fields[typeIndex].loading = true
        this.fields[typeIndex].opts = this.$fetchTemplateTypes()
        this.fields[typeIndex].loading = false
      }
    },
    async fetchDynamicFieldData (arrayField, searchKeyword) {
      if (this.isVnfSearch) return
      if (this.apiName === 'listAffinityGroups') return this.fetchAffinityFilters(arrayField)
      if (this.apiName === 'listCniConfiguration') return this.fetchCniOwnerOptions(arrayField, searchKeyword)
      const templateLookup = ['listTemplates', 'listIsos'].includes(this.apiName) ? this.volumeOwnerContext : null
      const templateFields = this.fields
      const templateLookupPath = this.$route.path
      const templateLookupStale = () => templateLookup && (!['listTemplates', 'listIsos'].includes(this.apiName) || templateLookup !== this.volumeOwnerContext || templateLookupPath !== this.$route.path || templateFields !== this.fields)
      const guestLookup = this.isGuestNetworkSearch
      const promises = []
      let typeIndex = -1
      let zoneIndex = -1
      let domainIndex = -1
      let accountIndex = -1
      let hypervisorIndex = -1
      let imageStoreIndex = -1
      let storageIndex = -1
      let podIndex = -1
      let clusterIndex = -1
      let groupIndex = -1
      let managementServerIdIndex = -1
      let serviceOfferingIndex = -1
      let diskOfferingIndex = -1
      let networkIndex = -1
      let usageTypeIndex = -1
      let virtualmachineIndex = -1
      let backupOfferingIndex = -1
      let osCategoryIndex = -1
      let gpuCardIndex = -1
      let vgpuProfileIndex = -1
      let extensionIndex = -1
      let backupStatusIndex = -1
      let hsmProfileIndex = -1
      let kmsKeyIndex = -1

      if (arrayField.includes('type')) {
        if (this.$route.path === '/alert') {
          typeIndex = this.fields.findIndex(item => item.name === 'type')
          this.fields[typeIndex].loading = true
          promises.push(await this.fetchAlertTypes())
        } else if (this.$route.path === '/affinitygroup') {
          typeIndex = this.fields.findIndex(item => item.name === 'type')
          this.fields[typeIndex].loading = true
          promises.push(await this.fetchAffinityGroupTypes())
        }
      }

      if (arrayField.includes('zoneid')) {
        zoneIndex = this.fields.findIndex(item => item.name === 'zoneid')
        this.fields[zoneIndex].loading = true
        promises.push(await this.fetchZones(searchKeyword, guestLookup))
      }

      if (arrayField.includes('extensionid')) {
        extensionIndex = this.fields.findIndex(item => item.name === 'extensionid')
        this.fields[extensionIndex].loading = true
        promises.push(await this.fetchExtensions(searchKeyword))
      }

      if (arrayField.includes('domainid')) {
        domainIndex = this.fields.findIndex(item => item.name === 'domainid')
        this.fields[domainIndex].loading = true
        promises.push(await this.fetchDomains(searchKeyword, guestLookup))
      }

      if (arrayField.includes('account')) {
        accountIndex = this.fields.findIndex(item => item.name === 'account')
        this.fields[accountIndex].loading = true
        promises.push(await this.fetchAccounts(searchKeyword, guestLookup))
      }

      if (arrayField.includes('hypervisor')) {
        hypervisorIndex = this.fields.findIndex(item => item.name === 'hypervisor')
        this.fields[hypervisorIndex].loading = true
        promises.push(await this.fetchHypervisors())
      }

      if (arrayField.includes('imagestoreid')) {
        imageStoreIndex = this.fields.findIndex(item => item.name === 'imagestoreid')
        this.fields[imageStoreIndex].loading = true
        promises.push(await this.fetchImageStores(searchKeyword))
      }

      if (arrayField.includes('storageid')) {
        storageIndex = this.fields.findIndex(item => item.name === 'storageid')
        this.fields[storageIndex].loading = true
        promises.push(await this.fetchStoragePools(searchKeyword))
      }

      if (arrayField.includes('podid')) {
        podIndex = this.fields.findIndex(item => item.name === 'podid')
        this.fields[podIndex].loading = true
        promises.push(await this.fetchPods(searchKeyword))
      }

      if (arrayField.includes('clusterid')) {
        clusterIndex = this.fields.findIndex(item => item.name === 'clusterid')
        this.fields[clusterIndex].loading = true
        promises.push(await this.fetchClusters(searchKeyword))
      }

      if (arrayField.includes('groupid')) {
        groupIndex = this.fields.findIndex(item => item.name === 'groupid')
        this.fields[groupIndex].loading = true
        promises.push(await this.fetchInstanceGroups(searchKeyword))
      }

      if (arrayField.includes('virtualmachineid')) {
        virtualmachineIndex = this.fields.findIndex(item => item.name === 'virtualmachineid')
        this.fields[virtualmachineIndex].loading = true
        promises.push(await this.fetchVirtualMachines(searchKeyword))
      }

      if (arrayField.includes('managementserverid')) {
        managementServerIdIndex = this.fields.findIndex(item => item.name === 'managementserverid')
        this.fields[managementServerIdIndex].loading = true
        promises.push(await this.fetchManagementServers(searchKeyword))
      }

      if (arrayField.includes('serviceofferingid')) {
        serviceOfferingIndex = this.fields.findIndex(item => item.name === 'serviceofferingid')
        this.fields[serviceOfferingIndex].loading = true
        promises.push(await this.fetchServiceOfferings(searchKeyword))
      }

      if (arrayField.includes('diskofferingid')) {
        diskOfferingIndex = this.fields.findIndex(item => item.name === 'diskofferingid')
        this.fields[diskOfferingIndex].loading = true
        promises.push(await this.fetchDiskOfferings(searchKeyword))
      }

      if (arrayField.includes('networkid')) {
        networkIndex = this.fields.findIndex(item => item.name === 'networkid')
        this.fields[networkIndex].loading = true
        promises.push(await this.fetchNetworks(searchKeyword))
      }

      if (arrayField.includes('usagetype')) {
        usageTypeIndex = this.fields.findIndex(item => item.name === 'usagetype')
        this.fields[usageTypeIndex].loading = true
        promises.push(await this.fetchUsageTypes())
      }

      if (arrayField.includes('oscategoryid')) {
        osCategoryIndex = this.fields.findIndex(item => item.name === 'oscategoryid')
        this.fields[osCategoryIndex].loading = true
        promises.push(await this.fetchOsCategories(searchKeyword))
      }

      if (arrayField.includes('backupofferingid')) {
        backupOfferingIndex = this.fields.findIndex(item => item.name === 'backupofferingid')
        this.fields[backupOfferingIndex].loading = true
        promises.push(await this.fetchBackupOfferings(searchKeyword))
      }

      if (arrayField.includes('gpucardid')) {
        gpuCardIndex = this.fields.findIndex(item => item.name === 'gpucardid')
        this.fields[gpuCardIndex].loading = true
        promises.push(await this.fetchGpuCards(searchKeyword))
      }

      if (arrayField.includes('vgpuprofileid')) {
        vgpuProfileIndex = this.fields.findIndex(item => item.name === 'vgpuprofileid')
        this.fields[vgpuProfileIndex].loading = true
        promises.push(await this.fetchVgpuProfiles(searchKeyword))
      }

      if (this.apiName === 'listBackups' && arrayField.includes('status')) {
        backupStatusIndex = this.fields.findIndex(item => item.name === 'status')
        this.fields[backupStatusIndex].opts = this.fetchAvailableBackupStatus()
      }

      if (arrayField.includes('hsmprofileid')) {
        hsmProfileIndex = this.fields.findIndex(item => item.name === 'hsmprofileid')
        this.fields[hsmProfileIndex].loading = true
        promises.push(await this.fetchHSMProfiles(searchKeyword))
      }

      if (arrayField.includes('kmskeyid')) {
        kmsKeyIndex = this.fields.findIndex(item => item.name === 'kmskeyid')
        this.fields[kmsKeyIndex].loading = true
        promises.push(await this.fetchKMSKeys(searchKeyword))
      }

      Promise.all(promises).then(response => {
        if (templateLookupStale()) return
        if (typeIndex > -1) {
          const types = response.filter(item => item.type === 'type')
          if (types && types.length > 0) {
            this.fields[typeIndex].opts = this.sortArray(types[0].data)
          }
        }
        if (zoneIndex > -1) {
          const zones = response.filter(item => item.type === 'zoneid')
          if (zones && zones.length > 0) {
            this.fields[zoneIndex].opts = this.sortArray(zones[0].data)
          }
        }
        if (extensionIndex > -1) {
          const extensions = response.filter(item => item.type === 'extensionid')
          if (extensions && extensions.length > 0) {
            this.fields[extensionIndex].opts = this.sortArray(extensions[0].data)
          }
        }
        if (domainIndex > -1) {
          const domain = response.filter(item => item.type === 'domainid')
          if (domain && domain.length > 0) {
            this.fields[domainIndex].opts = this.sortArray(domain[0].data, 'path')
          }
        }
        if (accountIndex > -1) {
          const account = response.filter(item => item.type === 'account')
          if (account && account.length > 0) {
            this.fields[accountIndex].opts = this.sortArray(account[0].data, 'name')
          }
        }
        if (hypervisorIndex > -1) {
          const hypervisor = response.filter(item => item.type === 'hypervisor')
          if (hypervisor && hypervisor.length > 0) {
            this.fields[hypervisorIndex].opts = this.sortArray(hypervisor[0].data, 'name')
          }
        }
        if (imageStoreIndex > -1) {
          const imageStore = response.filter(item => item.type === 'imagestoreid')
          if (imageStore && imageStore.length > 0) {
            this.fields[imageStoreIndex].opts = this.sortArray(imageStore[0].data, 'name')
          }
        }
        if (storageIndex > -1) {
          const storagePool = response.filter(item => item.type === 'storageid')
          if (storagePool && storagePool.length > 0) {
            this.fields[storageIndex].opts = this.sortArray(storagePool[0].data, 'name')
          }
        }
        if (podIndex > -1) {
          const pod = response.filter(item => item.type === 'podid')
          if (pod && pod.length > 0) {
            this.fields[podIndex].opts = this.sortArray(pod[0].data)
          }
        }
        if (clusterIndex > -1) {
          const cluster = response.filter(item => item.type === 'clusterid')
          if (cluster && cluster.length > 0) {
            this.fields[clusterIndex].opts = this.sortArray(cluster[0].data)
          }
        }
        if (groupIndex > -1) {
          const groups = response.filter(item => item.type === 'groupid')
          if (groups && groups.length > 0) {
            this.fields[groupIndex].opts = this.sortArray(groups[0].data)
          }
        }

        if (managementServerIdIndex > -1) {
          const managementServers = response.filter(item => item.type === 'managementserverid')
          if (managementServers && managementServers.length > 0) {
            this.fields[managementServerIdIndex].opts = this.sortArray(managementServers[0].data)
          }
        }

        if (serviceOfferingIndex > -1) {
          const serviceOfferings = response.filter(item => item.type === 'serviceofferingid')
          if (serviceOfferings && serviceOfferings.length > 0) {
            this.fields[serviceOfferingIndex].opts = this.sortArray(serviceOfferings[0].data)
          }
        }

        if (diskOfferingIndex > -1) {
          const diskOfferings = response.filter(item => item.type === 'diskofferingid')
          if (diskOfferings && diskOfferings.length > 0) {
            this.fields[diskOfferingIndex].opts = this.sortArray(diskOfferings[0].data)
          }
        }

        if (networkIndex > -1) {
          const networks = response.filter(item => item.type === 'networkid')
          if (networks && networks.length > 0) {
            this.fields[networkIndex].opts = this.sortArray(networks[0].data)
          }
        }

        if (usageTypeIndex > -1) {
          const usageTypes = response.filter(item => item.type === 'usagetype')
          if (usageTypes?.length > 0) {
            this.fields[usageTypeIndex].opts = this.sortArray(usageTypes[0].data)
          }
        }

        if (osCategoryIndex > -1) {
          const osCategories = response.filter(item => item.type === 'oscategoryid')
          if (osCategories && osCategories.length > 0) {
            this.fields[osCategoryIndex].opts = this.sortArray(osCategories[0].data)
          }
        }

        if (backupOfferingIndex > -1) {
          const backupOfferings = response.filter(item => item.type === 'backupofferingid')
          if (backupOfferings?.length > 0) {
            this.fields[backupOfferingIndex].opts = this.sortArray(backupOfferings[0].data)
          }
        }

        if (gpuCardIndex > -1) {
          const gpuCards = response.filter(item => item.type === 'gpucardid')
          if (gpuCards && gpuCards.length > 0) {
            this.fields[gpuCardIndex].opts = this.sortArray(gpuCards[0].data)
          }
        }

        if (vgpuProfileIndex > -1) {
          const vgpuProfiles = response.filter(item => item.type === 'vgpuprofileid')
          if (vgpuProfiles && vgpuProfiles.length > 0) {
            this.fields[vgpuProfileIndex].opts = this.sortArray(vgpuProfiles[0].data)
          }
        }

        if (virtualmachineIndex > -1) {
          const virtualMachines = response.filter(item => item.type === 'virtualmachineid')
          if (virtualMachines && virtualMachines.length > 0) {
            this.fields[virtualmachineIndex].opts = this.sortArray(virtualMachines[0].data)
          }
        }

        if (hsmProfileIndex > -1) {
          const hsmProfiles = response.filter(item => item.type === 'hsmprofileid')
          if (hsmProfiles && hsmProfiles.length > 0) {
            this.fields[hsmProfileIndex].opts = this.sortArray(hsmProfiles[0].data)
          }
        }

        if (kmsKeyIndex > -1) {
          const kmsKeys = response.filter(item => item.type === 'kmskeyid')
          if (kmsKeys && kmsKeys.length > 0) {
            this.fields[kmsKeyIndex].opts = this.sortArray(kmsKeys[0].data)
          }
        }
      }).finally(() => {
        if (templateLookupStale()) return
        if (typeIndex > -1) {
          this.fields[typeIndex].loading = false
        }
        if (zoneIndex > -1) {
          this.fields[zoneIndex].loading = false
        }
        if (extensionIndex > -1) {
          this.fields[extensionIndex].loading = false
        }
        if (domainIndex > -1) {
          this.fields[domainIndex].loading = false
        }
        if (accountIndex > -1) {
          this.fields[accountIndex].loading = false
        }
        if (hypervisorIndex > -1) {
          this.fields[hypervisorIndex].loading = false
        }
        if (imageStoreIndex > -1) {
          this.fields[imageStoreIndex].loading = false
        }
        if (storageIndex > -1) {
          this.fields[storageIndex].loading = false
        }
        if (podIndex > -1) {
          this.fields[podIndex].loading = false
        }
        if (clusterIndex > -1) {
          this.fields[clusterIndex].loading = false
        }
        if (groupIndex > -1) {
          this.fields[groupIndex].loading = false
        }
        if (managementServerIdIndex > -1) {
          this.fields[managementServerIdIndex].loading = false
        }
        if (serviceOfferingIndex > -1) {
          this.fields[serviceOfferingIndex].loading = false
        }
        if (diskOfferingIndex > -1) {
          this.fields[diskOfferingIndex].loading = false
        }
        if (networkIndex > -1) {
          this.fields[networkIndex].loading = false
        }
        if (usageTypeIndex > -1) {
          this.fields[usageTypeIndex].loading = false
        }
        if (osCategoryIndex > -1) {
          this.fields[osCategoryIndex].loading = false
        }
        if (backupOfferingIndex > -1) {
          this.fields[backupOfferingIndex].loading = false
        }
        if (gpuCardIndex > -1) {
          this.fields[gpuCardIndex].loading = false
        }
        if (vgpuProfileIndex > -1) {
          this.fields[vgpuProfileIndex].loading = false
        }
        if (virtualmachineIndex > -1) {
          this.fields[virtualmachineIndex].loading = false
        }
        if (hsmProfileIndex > -1) {
          this.fields[hsmProfileIndex].loading = false
        }
        if (kmsKeyIndex > -1) {
          this.fields[kmsKeyIndex].loading = false
        }
        if (!['listTemplates', 'listIsos'].includes(this.apiName) && !this.isGuestNetworkSearch && !this.isVpnCustomerGatewaySearch && Array.isArray(arrayField) && !arrayField.includes('volumeid')) {
          this.fillFormFieldValues()
        }
      })
    },
    async fetchAffinityFilters (arrayField) {
      const domainid = this.form.domainid
      const fields = this.fields.filter(field => ['type', 'domainid', 'account', 'projectid'].includes(field.name) && arrayField.includes(field.name))
      const vpnContext = this.vpnUserFilterContext
      await Promise.all(fields.map(async field => {
        const version = this.affinityOptionsVersion[field.name] = (this.affinityOptionsVersion[field.name] || 0) + 1
        const current = () => version === this.affinityOptionsVersion[field.name] &&
          (!vpnContext || this.visibleFilter && vpnContext === this.vpnUserFilterContext && this.fields.includes(field))
        field.loading = true; field.error = false; field.opts = []
        try {
          if (field.name === 'account' && !domainid) return
          const rows = field.name === 'type'
            ? affinityTypeOptions(await getAPI('listAffinityGroupTypes', { ignoreproject: true }, { preserveOnFailure: true, timeout: 15000 }))
            : await affinityOwnerOptions(getAPI, { domainid: 'domain', account: 'account', projectid: 'project' }[field.name], domainid)
          if (!current() || (['account', 'projectid'].includes(field.name) && domainid !== this.form.domainid)) return
          // Root-admin listAccounts can include descendants despite isrecursive=false.
          const eligible = rows.filter(row => !['account', 'projectid'].includes(field.name) || !domainid || row.domainid === domainid)
          field.opts = this.sortArray(eligible.map(row => ({ ...row, ...(row.labelKey ? { name: this.$t(row.labelKey) } : {}) })), field.name === 'domainid' ? 'path' : 'name')
        } catch (_) { if (current()) field.error = true } finally {
          if (current()) field.loading = false
        }
      }))
    },
    async fetchCniOwnerOptions (arrayField, searchKeyword) {
      const domainid = this.form.domainid
      const fields = this.fields.filter(field => ['domainid', 'account'].includes(field.name) && arrayField.includes(field.name))
      fields.forEach(field => { field.loading = true; field.opts = [] })
      await Promise.all(fields.map(async field => {
        const version = this.cniOptionsVersion[field.name] = (this.cniOptionsVersion[field.name] || 0) + 1
        try {
          const isAccount = field.name === 'account'
          const json = await getAPI(isAccount ? 'listAccounts' : 'listDomains', { listall: true, keyword: searchKeyword, ...(isAccount ? { isrecursive: false, ...(domainid ? { domainid } : {}) } : { details: 'min' }) })
          if (version !== this.cniOptionsVersion[field.name] || (isAccount && domainid !== this.form.domainid)) return
          const rows = isAccount ? json?.listaccountsresponse?.account : json?.listdomainsresponse?.domain
          field.opts = this.sortArray((rows || []).filter(row => !isAccount || !domainid || row.domainid === domainid), isAccount ? 'name' : 'path')
        } catch (_) {
          if (version === this.cniOptionsVersion[field.name]) this.$message.error(this.$t('error.fetching.data'))
        } finally {
          if (version === this.cniOptionsVersion[field.name]) field.loading = false
        }
      }))
    },
    async fetchEventTypes (field) {
      if (!field) return
      if (!('listEventTypes' in this.$store.getters.apis)) {
        field.eventTypesUnavailable = true
        return
      }
      try {
        const response = await getAPI('listEventTypes', {}, { preserveOnFailure: true, timeout: 10000 })
        if (!this.visibleFilter || !this.fields.includes(field)) return
        const options = response?.listeventtypesresponse?.eventtype || []
        field.opts = [...new Set(options.map(option => option.name).filter(Boolean))].sort().map(value => ({ value }))
        field.eventTypesUnavailable = field.opts.length === 0
      } catch (_) {
        if (this.visibleFilter && this.fields.includes(field)) field.eventTypesUnavailable = true
      }
    },
    async fetchDnsSearchOptions (field) {
      const providerSearch = field?.name === 'providertype'
      const api = providerSearch ? 'listDnsProviders' : 'listDnsServers'
      if (!field || !this.$store.getters.apis?.[api]) return
      field.loading = true
      field.error = false
      try {
        const response = await getAPI(api, { pagesize: -1 }, { preserveOnFailure: true })
        const body = response?.[api.toLowerCase() + 'response']
        if (!body || body.errorcode) throw new Error(api)
        const rows = providerSearch ? (body.dnsprovider || []).map(({ name }) => ({ id: name, name })) : body.dnsserver || []
        field.opts = this.sortArray(rows, 'name')
      } catch {
        field.error = true
      } finally {
        field.loading = false
      }
    },
    initFormFieldData () {
      const arrayField = this.initFields()
      if (this.isInfrastructureSearch) {
        this.form = reactive({})
        this.fillFormFieldValues()
        this.fetchStaticFieldData(arrayField)
        const active = this.fields.find(field => field.name === 'outofbandmanagementenabled')
        if (active) active.opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }]
        const power = this.fields.find(field => field.name === 'outofbandmanagementpowerstate')
        if (power) power.opts = ['On', 'Off', 'Disabled', 'Unknown'].map(id => ({ id, name: 'label.host.power.' + id.toLowerCase() }))
        if (this.isClusterSearch) {
          this.fields.forEach(field => {
            if (clusterFilterOptions[field.name]) field.opts = clusterFilterOptions[field.name]
          })
        }
        if (this.isPrimaryStorageSearch) {
          const scope = this.fields.find(field => field.name === 'scope')
          if (scope) {
            scope.opts = [
              { id: 'ZONE', name: 'label.zoneid' },
              { id: 'CLUSTER', name: 'label.clusterid' },
              { id: 'HOST', name: 'label.hostid' }
            ]
          }
        }
        this.fetchInfrastructureSearchOptions(arrayField)
        return
      }
      if (this.isDnsZoneSearch || this.isDnsServerSearch) {
        this.form = reactive({})
        this.fillFormFieldValues()
        this.fetchDnsSearchOptions(this.fields.find(field => ['dnsserverid', 'providertype'].includes(field.name)))
        return
      }
      if (this.isIpv4SubnetSearch) {
        this.form = reactive({})
        this.fillFormFieldValues()
        this.fetchDynamicFieldData(['zoneid'])
        this.fetchIpv4SubnetOptions()
        return
      }
      if (this.isVpnCustomerGatewaySearch || this.isRoleSearch) {
        this.form = reactive({})
        this.fillFormFieldValues()
        this.rules.id = [{
          validator: async (_, value) => {
            if (value && !this.$isValidUuid(value.trim())) throw new Error(this.$t('message.error.input.invaliduuid'))
          }
        }]
        if (this.isRoleSearch) this.fetchStaticFieldData(arrayField)
        this.fetchDynamicFieldData(arrayField)
        return
      }
      if (this.isVpnUserSearch) {
        this.form = reactive({})
        this.fillFormFieldValues()
        this.fetchAffinityFilters(arrayField)
        return
      }
      if (this.isAclSearch) {
        this.aclVpcBlocked = false
        this.fillFormFieldValues()
        return
      }
      if (this.isEventSearch) {
        this.eventFilterError = ''
        this.form = reactive({})
        this.fillFormFieldValues()
        this.fetchEventTypes(this.fields.find(field => field.name === 'type'))
      }
      if (this.isKmsSearch) {
        this.fillFormFieldValues()
        this.fetchKmsFields(arrayField)
        return
      }
      if (['listTemplates', 'listIsos'].includes(this.apiName)) this.rules.userdataid = [{ validator: async (rule, value) => { if (value && !this.$isValidUuid(value)) throw new Error(this.$t('message.template.search.userdataid')) } }]
      if (this.isAccountSearch) {
        this.fillFormFieldValues()
        this.rules.id = [{
          validator: async (rule, value) => {
            if (value && !this.$isValidUuid(value.trim())) throw new Error(this.$t('message.error.input.invaliduuid'))
          }
        }]
      }
      if (this.isOauthSearch || this.isZoneSearch || this.isGuestVlanSearch || this.isGuestNetworkSearch || this.isVnfSearch || ['listTemplates', 'listIsos', 'listCniConfiguration', 'listAffinityGroups', 'listBackups', 'listBackupSchedule'].includes(this.apiName) || arrayField.includes('volumeid')) this.fillFormFieldValues()

      this.fetchStaticFieldData(arrayField)

      this.fetchDynamicFieldData(arrayField)
    },
    fetchIpv4SubnetOptions () {
      const zoneid = this.form.zoneid
      const scope = zoneid ? { zoneid } : {}
      const fields = this.fields
      const request = Symbol('IPv4 subnet options')
      this.ipv4OptionsRequest = request
      const current = () => this.visibleFilter && this.fields === fields && this.ipv4OptionsRequest === request
      const load = async (api, responseKey, kinds, extra) => {
        const selected = fields.filter(field => kinds.includes(field.name))
        if (!selected.length) return
        selected.forEach(field => { field.opts = []; field.loading = true; field.error = false })
        try {
          const response = await getAPI(api, { ...scope, ...extra, ignoreproject: true }, { preserveOnFailure: true })
          if (!current()) return
          const rows = response[api.toLowerCase() + 'response']?.[responseKey] || []
          selected.forEach(field => { field.opts = ipv4SubnetFilterOptions(field.name, rows) })
        } catch {
          if (current()) selected.forEach(field => { field.error = true })
        } finally {
          if (current()) selected.forEach(field => { field.loading = false })
        }
      }
      load('listIpv4SubnetsForZone', 'zoneipv4subnet', ['parentid'], {})
      load(ipv4SubnetApi, 'ipv4subnetforguestnetwork', ['networkid', 'vpcid'], { pagesize: -1 })
    },
    async fetchKmsFields (names) {
      const scope = { domainid: this.form.domainid, projectid: this.form.projectid || kmsProjectScope(this.$store.getters) }
      await Promise.all(this.fields.filter(field => names.includes(field.name)).map(async field => {
        const name = field.name
        if (kmsBooleans.includes(name)) {
          field.opts = [{ id: 'true', name: 'label.yes' }, { id: 'false', name: 'label.no' }]
          return
        }
        if (name === 'protocol') {
          field.opts = kmsProtocolOptions(this.$store.getters.features, this.form.protocol).map(p => ({ value: p.id, label: p.name }))
          return
        }
        const generation = (this.kmsLookupVersion[name] || 0) + 1
        this.kmsLookupVersion[name] = generation
        const current = () => this.visibleFilter && this.fields.includes(field) && generation === this.kmsLookupVersion[name]
        field.loading = true
        field.opts = []
        try {
          const rows = await kmsLookup(getAPI, name, scope, current)
          if (current()) field.opts = this.sortArray(rows)
        } catch (_) {
          if (current()) this.$message.error(this.$t('error.fetching.data'))
        } finally {
          if (current()) field.loading = false
        }
      }))
    },
    sortArray (data, key = 'name') {
      if (!data) {
        return []
      }
      return data.sort(function (a, b) {
        if (a[key] < b[key]) { return -1 }
        if (a[key] > b[key]) { return 1 }

        return 0
      })
    },
    async fetchInfrastructureSearchOptions (keys) {
      const form = this.form
      const zoneid = form.zoneid
      const podid = form.podid
      const clusterid = form.clusterid
      const pickers = {
        zoneid: ['listZones', 'zone', {}],
        podid: ['listPods', 'pod', { zoneid }],
        clusterid: ['listClusters', 'cluster', { zoneid, podid }],
        hostid: ['listHosts', 'host', { zoneid, podid, clusterid, type: 'Routing' }],
        managementserverid: ['listManagementServers', 'managementserver', {}],
        storageaccessgroup: ['listStorageAccessGroups', 'storageaccessgroup', {}]
      }
      await Promise.all(this.fields.filter(field => keys.includes(field.name) && field.type === 'list' && pickers[field.name]).map(async field => {
        const [api, key, params] = pickers[field.name]
        const isCurrent = () => this.form === form &&
          (field.name !== 'podid' || form.zoneid === zoneid) &&
          (field.name !== 'clusterid' || form.podid === podid && form.zoneid === zoneid) &&
          (field.name !== 'hostid' || form.clusterid === clusterid && form.podid === podid && form.zoneid === zoneid)
        field.error = false
        if (!params || !this.$store.getters.apis[api]) {
          field.opts = []
          field.loading = false
          return
        }
        field.loading = true
        try {
          const wire = await getAPI(api, params, { preserveOnFailure: true })
          if (!isCurrent()) return
          const entries = wire[api.toLowerCase() + 'response']?.[key] || []
          field.opts = entries.map(row => ({ id: key === 'storageaccessgroup' ? row.name : row.id, name: row.name || row.id }))
        } catch {
          if (!isCurrent()) return
          field.opts = []
          field.error = true
        } finally {
          if (isCurrent()) field.loading = false
        }
      }))
    },
    fillFormFieldValues () {
      this.fieldValues = {}
      if (Object.keys(this.$route.query).length > 0) {
        this.fieldValues = { ...this.$route.query }
      }
      if (this.$route.meta.params) {
        Object.assign(this.fieldValues, this.$route.meta.params)
      }
      if (this.apiName === 'listWebhookDeliveries') this.fieldValues = { ...this.searchParams }
      this.fields.forEach(field => {
        const value = this.fieldValues[field.name]
        this.form[field.name] = field.type === 'boolean'
          ? value === true || value === 'true' ? true : value === false || value === 'false' ? false : undefined
          : value
      })
      this.inputKey = this.fieldValues['tags[0].key'] || null
      this.inputValue = this.fieldValues['tags[0].value'] || null
    },
    fetchZones (searchKeyword, guestLookup = this.isGuestNetworkSearch) {
      if (guestLookup && (!this.isGuestNetworkSearch || !this.$store.state?.user?.token || !('listZones' in (this.$store.getters.apis || {})))) return Promise.resolve({ type: 'zoneid', data: [] })
      return new Promise((resolve, reject) => {
        getAPI('listZones', { showicon: true, keyword: searchKeyword }).then(json => {
          const zones = json.listzonesresponse.zone
          resolve({
            type: 'zoneid',
            data: zones
          })
        }).catch(error => {
          if (guestLookup && sourceToken.isCancel(error)) { resolve({ type: 'zoneid', data: [] }); return }
          reject(error?.response?.headers?.['x-description'] || error)
        })
      })
    },
    fetchExtensions (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listExtensions', { details: 'min', showicon: true, keyword: searchKeyword }).then(json => {
          const extensions = json.listextensionsresponse.extension
          resolve({
            type: 'extensionid',
            data: extensions
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchDomains (searchKeyword, guestLookup = this.isGuestNetworkSearch) {
      if (guestLookup && (!this.isGuestNetworkSearch || !this.$store.state?.user?.token || !('listDomains' in (this.$store.getters.apis || {})))) return Promise.resolve({ type: 'domainid', data: [] })
      return new Promise((resolve, reject) => {
        getAPI('listDomains', { listAll: true, details: 'min', showicon: true, keyword: searchKeyword }).then(json => {
          const rows = json.listdomainsresponse?.domain || []
          const domain = this.isOauthSearch ? rows.filter(row => row.name !== 'ROOT') : rows
          resolve({
            type: 'domainid',
            data: domain
          })
        }).catch(error => {
          if (guestLookup && sourceToken.isCancel(error)) { resolve({ type: 'domainid', data: [] }); return }
          reject(error?.response?.headers?.['x-description'] || error)
        })
      })
    },
    fetchAccounts (searchKeyword, guestLookup = this.isGuestNetworkSearch) {
      if (guestLookup && (!this.isGuestNetworkSearch || !this.$store.state?.user?.token || !('listAccounts' in (this.$store.getters.apis || {})))) return Promise.resolve({ type: 'account', data: [] })
      return new Promise((resolve, reject) => {
        const params = { listAll: true, isrecursive: false, showicon: true, keyword: searchKeyword }
        if (this.form.domainid) {
          params.domainid = this.form.domainid
        }
        getAPI('listAccounts', params).then(json => {
          let account = json?.listaccountsresponse?.account || []
          if (this.form.domainid) {
            account = account.filter(a => a.domainid === this.form.domainid)
          }
          resolve({
            type: 'account',
            data: account
          })
        }).catch(error => {
          if (guestLookup && sourceToken.isCancel(error)) { resolve({ type: 'account', data: [] }); return }
          reject(error?.response?.headers?.['x-description'] || error)
        })
      })
    },
    fetchHypervisors () {
      return new Promise((resolve, reject) => {
        getAPI('listHypervisors').then(json => {
          const hypervisor = json.listhypervisorsresponse.hypervisor.map(a => { return { id: a.name, name: a.name } })
          resolve({
            type: 'hypervisor',
            data: hypervisor
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchImageStores (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listImageStores', { listAll: true, showicon: true, keyword: searchKeyword }).then(json => {
          const imageStore = json.listimagestoresresponse.imagestore
          resolve({
            type: 'imagestoreid',
            data: imageStore
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchStoragePools (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listStoragePools', { listAll: true, showicon: true, keyword: searchKeyword }).then(json => {
          const storagePool = json.liststoragepoolsresponse.storagepool
          resolve({
            type: 'storageid',
            data: storagePool
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchPods (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listPods', { keyword: searchKeyword }).then(json => {
          const pods = json.listpodsresponse.pod
          resolve({
            type: 'podid',
            data: pods
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchClusters (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listClusters', { keyword: searchKeyword }).then(json => {
          const clusters = json.listclustersresponse.cluster
          resolve({
            type: 'clusterid',
            data: clusters
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchInstanceGroups (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listInstanceGroups', { listAll: true, keyword: searchKeyword }).then(json => {
          const instancegroups = json.listinstancegroupsresponse.instancegroup
          resolve({
            type: 'groupid',
            data: instancegroups
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchServiceOfferings (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listServiceOfferings', { listAll: true, keyword: searchKeyword }).then(json => {
          const serviceOfferings = json.listserviceofferingsresponse.serviceoffering
          resolve({
            type: 'serviceofferingid',
            data: serviceOfferings
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchDiskOfferings (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listDiskOfferings', { listAll: true, keyword: searchKeyword }).then(json => {
          const diskOfferings = json.listdiskofferingsresponse.diskoffering
          resolve({
            type: 'diskofferingid',
            data: diskOfferings
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchNetworks (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listNetworks', { listAll: true, keyword: searchKeyword }).then(json => {
          const networks = json.listnetworksresponse.network
          resolve({
            type: 'networkid',
            data: networks
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchAlertTypes () {
      if (this.alertTypes.length > 0) {
        return new Promise((resolve, reject) => {
          resolve({
            type: 'type',
            data: this.alertTypes
          })
        })
      } else {
        return new Promise((resolve, reject) => {
          getAPI('listAlertTypes').then(json => {
            const alerttypes = json.listalerttypesresponse.alerttype.map(a => { return { id: a.alerttypeid, name: a.name } })
            this.alertTypes = alerttypes
            resolve({
              type: 'type',
              data: alerttypes
            })
          }).catch(error => {
            reject(error.response.headers['x-description'])
          })
        })
      }
    },
    fetchAffinityGroupTypes () {
      if (this.alertTypes.length > 0) {
        return new Promise((resolve, reject) => {
          resolve({
            type: 'type',
            data: this.alertTypes
          })
        })
      } else {
        return new Promise((resolve, reject) => {
          getAPI('listAffinityGroupTypes').then(json => {
            const alerttypes = json.listaffinitygrouptypesresponse.affinityGroupType.map(a => {
              let name = a.type
              if (a.type === 'host anti-affinity') {
                name = 'host anti-affinity (Strict)'
              } else if (a.type === 'host affinity') {
                name = 'host affinity (Strict)'
              } else if (a.type === 'non-strict host anti-affinity') {
                name = 'host anti-affinity (Non-Strict)'
              } else if (a.type === 'non-strict host affinity') {
                name = 'host affinity (Non-Strict)'
              }
              return { id: a.type, name: name }
            })
            this.alertTypes = alerttypes
            resolve({
              type: 'type',
              data: alerttypes
            })
          }).catch(error => {
            reject(error.response.headers['x-description'])
          })
        })
      }
    },
    fetchVirtualMachines (searchKeyword, page = 1) {
      return new Promise((resolve, reject) => {
        const snapshotList = this.$route.name === 'vmsnapshot'
        getAPI('listVirtualMachines', { listAll: true, keyword: searchKeyword, ...(snapshotList ? { page, pagesize: 50 } : {}) }).then(json => {
          let virtualMachines = json.listvirtualmachinesresponse.virtualmachine
          if (snapshotList) {
            this.vmOptionTotal = json.listvirtualmachinesresponse.count || 0
            virtualMachines = (virtualMachines || []).map(vm => ({ ...vm, name: `${vm.displayname || vm.name} · ${vm.account || ''} · ${vm.id}` }))
          }
          resolve({
            type: 'virtualmachineid',
            data: virtualMachines
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchManagementServers (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listManagementServers', { listAll: true, keyword: searchKeyword }).then(json => {
          const managementservers = json.listmanagementserversresponse.managementserver
          resolve({
            type: 'managementserverid',
            data: managementservers
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchOsCategories (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listOsCategories', { showicon: true, keyword: searchKeyword }).then(json => {
          const osCategories = json.listoscategoriesresponse.oscategory
          resolve({
            type: 'oscategoryid',
            data: osCategories
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchGuestNetworkTypes () {
      const types = []
      if (['listNetworks', 'listNetworkOfferings'].includes(this.apiName)) {
        types.push({
          id: 'Isolated',
          name: 'label.isolated'
        })
        types.push({
          id: 'Shared',
          name: 'label.shared'
        })
        types.push({
          id: 'L2',
          name: 'label.l2'
        })
      }
      return types
    },
    fetchBoolean () {
      const types = []
      types.push({
        id: 'true',
        name: 'label.true'
      })
      types.push({
        id: 'false',
        name: 'label.false'
      })
      return types
    },
    fetchAccountTypes () {
      const types = []
      if (this.apiName.indexOf('listAccounts') > -1) {
        types.push({
          id: '1',
          name: 'Admin'
        })
        types.push({
          id: '2',
          name: 'DomainAdmin'
        })
        types.push({
          id: '3',
          name: 'User'
        })
      }
      return types
    },
    fetchSystemVmTypes () {
      const types = []
      if (this.apiName.indexOf('listSystemVms') > -1) {
        types.push({
          id: 'consoleproxy',
          name: 'label.console.proxy.vm'
        })
        types.push({
          id: 'secondarystoragevm',
          name: 'label.secondary.storage.vm'
        })
      }
      return types
    },
    fetchStoragePoolScope () {
      const types = []
      if (this.apiName.indexOf('listStoragePools') > -1) {
        types.push({
          id: 'HOST',
          name: 'label.hostname'
        })
        types.push({
          id: 'CLUSTER',
          name: 'label.cluster'
        })
        types.push({
          id: 'ZONE',
          name: 'label.zone'
        })
        types.push({
          id: 'REGION',
          name: 'label.region'
        })
        types.push({
          id: 'GLOBAL',
          name: 'label.global'
        })
      }
      return types
    },
    fetchImageStoreProviders () {
      const types = []
      if (this.apiName.indexOf('listImageStores') > -1) {
        types.push({
          id: 'NFS',
          name: 'NFS'
        })
        types.push({
          id: 'SMB/CIFS',
          name: 'SMB/CIFS'
        })
        types.push({
          id: 'S3',
          name: 'S3'
        })
        types.push({
          id: 'Swift',
          name: 'Swift'
        })
      }
      return types
    },
    fetchRoleTypes () {
      return this.isRoleSearch ? roleTypes.map(id => ({ id, name: id })) : []
    },
    fetchScope () {
      const scope = []
      if (this.apiName.indexOf('listWebhooks') > -1) {
        scope.push({
          id: 'Local',
          name: 'label.local'
        })
        scope.push({
          id: 'Domain',
          name: 'label.domain'
        })
        if (isAdmin()) {
          scope.push({
            id: 'Global',
            name: 'label.global'
          })
        }
      }
      return scope
    },
    fetchState () {
      if (this.isRoleSearch) return roleStates.map(id => ({ id, name: 'label.' + id }))
      var state = []
      if (this.apiName === 'listVMSnapshot') {
        state = ['Allocated', 'Creating', 'Ready', 'Reverting', 'Expunging', 'Error'].map(id => ({ id, name: 'state.' + id.toLowerCase() }))
      } else if (this.apiName.includes('listVolumes')) {
        state = [
          {
            id: 'Allocated',
            name: 'label.allocated'
          },
          {
            id: 'Ready',
            name: 'label.isready'
          },
          {
            id: 'Destroy',
            name: 'label.destroy'
          },
          {
            id: 'Expunging',
            name: 'label.expunging'
          },
          {
            id: 'Expunged',
            name: 'label.expunged'
          },
          {
            id: 'Migrating',
            name: 'label.migrating'
          }
        ]
      } else if (this.apiName.includes('listKubernetesClusters')) {
        state = [
          {
            id: 'Created',
            name: 'label.created'
          },
          {
            id: 'Starting',
            name: 'label.starting'
          },
          {
            id: 'Running',
            name: 'label.running'
          },
          {
            id: 'Stopping',
            name: 'label.stopping'
          },
          {
            id: 'Stopped',
            name: 'label.stopped'
          },
          {
            id: 'Scaling',
            name: 'label.scaling'
          },
          {
            id: 'Upgrading',
            name: 'label.upgrading'
          },
          {
            id: 'Alert',
            name: 'label.alert'
          },
          {
            id: 'Recovering',
            name: 'label.recovering'
          },
          {
            id: 'Destroyed',
            name: 'label.destroyed'
          },
          {
            id: 'Destroying',
            name: 'label.destroying'
          },
          {
            id: 'Error',
            name: 'label.error'
          }
        ]
      } else if (this.apiName.indexOf('listWebhooks') > -1) {
        state = [
          {
            id: 'Enabled',
            name: 'label.enabled'
          },
          {
            id: 'Disabled',
            name: 'label.disabled'
          }
        ]
      } else if (this.apiName.indexOf('listEvents') > -1) {
        state = [
          {
            id: 'Created',
            name: 'label.created'
          },
          {
            id: 'Scheduled',
            name: 'label.scheduled'
          },
          {
            id: 'Started',
            name: 'label.started'
          },
          {
            id: 'Completed',
            name: 'label.completed'
          }
        ]
      }
      return state
    },
    fetchEntityType () {
      const entityType = []
      if (this.apiName.indexOf('listAnnotations') > -1) {
        const allowedTypes = {
          VM: 'Virtual Machine',
          HOST: 'Host',
          VOLUME: 'Volume',
          SNAPSHOT: 'Snapshot',
          VM_SNAPSHOT: 'VM Snapshot',
          INSTANCE_GROUP: 'Instance Group',
          NETWORK: 'Network',
          VPC: 'VPC',
          PUBLIC_IP_ADDRESS: 'Public IP Address',
          VPN_CUSTOMER_GATEWAY: 'VPC Customer Gateway',
          TEMPLATE: 'Template',
          ISO: 'ISO',
          SSH_KEYPAIR: 'SSH Key Pair',
          DOMAIN: 'Domain',
          SERVICE_OFFERING: 'Service Offfering',
          DISK_OFFERING: 'Disk Offering',
          NETWORK_OFFERING: 'Network Offering',
          POD: 'Pod',
          ZONE: 'Zone',
          CLUSTER: 'Cluster',
          PRIMARY_STORAGE: 'Primary Storage',
          SECONDARY_STORAGE: 'Secondary Storage',
          VR: 'Virtual Router',
          SYSTEM_VM: 'System VM',
          KUBERNETES_CLUSTER: 'Kubernetes Cluster',
          DISASTER_RECOVERY: 'Disaster Recovery'
        }
        for (var key in allowedTypes) {
          entityType.push({
            id: key,
            name: allowedTypes[key]
          })
        }
      }
      return entityType
    },
    fetchLevel () {
      const levels = []
      levels.push({
        id: 'INFO',
        name: 'label.info.upper'
      })
      levels.push({
        id: 'WARN',
        name: 'label.warn.upper'
      })
      levels.push({
        id: 'ERROR',
        name: 'label.error.upper'
      })
      return levels
    },
    fetchUsageTypes () {
      return new Promise((resolve, reject) => {
        getAPI('listUsageTypes')
          .then(json => {
            const usageTypes = json.listusagetypesresponse.usagetype.map(entry => {
              return {
                id: entry.id,
                name: i18n.global.t(entry.name)
              }
            })

            resolve({
              type: 'usagetype',
              data: usageTypes
            })
          })
          .catch(error => {
            reject(error.response.headers['x-description'])
          })
      })
    },
    fetchBackupOfferings (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listBackupOfferings').then(json => {
          const backupOfferings = json.listbackupofferingsresponse.backupoffering
          resolve({
            type: 'backupofferingid',
            data: backupOfferings
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchAvailableUserSourceTypes () {
      return [
        {
          id: 'native',
          name: 'label.native'
        },
        {
          id: 'saml2',
          name: 'label.saml'
        },
        {
          id: 'saml2disabled',
          name: 'label.saml.disabled'
        },
        {
          id: 'ldap',
          name: 'label.ldap'
        }
      ]
    },
    fetchGpuCards (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listGpuCards', { keyword: searchKeyword }).then(json => {
          const gpuCards = json.listgpucardsresponse.gpucard
          resolve({
            type: 'gpucardid',
            data: gpuCards
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchVgpuProfiles (searchKeyword) {
      return new Promise((resolve, reject) => {
        const params = { keyword: searchKeyword }

        // If a GPU card is selected, filter vGPU profiles by that GPU card
        if (this.form.gpucardid) {
          params.gpucardid = this.form.gpucardid
        }

        getAPI('listVgpuProfiles', params).then(json => {
          const vgpuProfiles = json.listvgpuprofilesresponse.vgpuprofile
          resolve({
            type: 'vgpuprofileid',
            data: vgpuProfiles
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchAvailableBackupStatus () {
      return [
        {
          id: 'Allocated',
          name: 'label.allocated'
        },
        {
          id: 'Queued',
          name: 'label.queued'
        },
        {
          id: 'BackingUp',
          name: 'label.backingup'
        },
        {
          id: 'BackedUp',
          name: 'label.backedup'
        },
        {
          id: 'Error',
          name: 'label.error'
        },
        {
          id: 'Failed',
          name: 'label.failed'
        },
        {
          id: 'Restoring',
          name: 'label.restoring'
        }
      ]
    },
    fetchHSMProfiles (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listHSMProfiles', { listAll: true, keyword: searchKeyword }).then(json => {
          const hsmProfiles = json.listhsmprofilesresponse.hsmprofile
          resolve({
            type: 'hsmprofileid',
            data: hsmProfiles || []
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    fetchKMSKeys (searchKeyword) {
      return new Promise((resolve, reject) => {
        getAPI('listKMSKeys', { listAll: true, keyword: searchKeyword }).then(json => {
          const kmsKeys = json.listkmskeysresponse.kmskey
          resolve({
            type: 'kmskeyid',
            data: kmsKeys || []
          })
        }).catch(error => {
          reject(error.response.headers['x-description'])
        })
      })
    },
    openAdvancedFilters () { this.visibleFilter = true },
    onSearch (value) {
      this.paramsFilter = {}
      this.searchQuery = value
      this.$emit('search', { searchQuery: this.searchQuery })
    },
    async onClear () {
      if (this.isOauthSearch) {
        this.visibleFilter = false
        this.$emit('search', {})
        return
      }
      if (this.isInfrastructureSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        const reset = this.isSecondaryStorageSearch ? 'secondaryStorageReset' : this.isPrimaryStorageSearch ? 'primaryStorageReset' : this.isClusterSearch ? 'clusterReset' : 'hostReset'
        this.$emit('search', { [reset]: true })
        return
      }
      if (this.isZoneSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { zoneReset: true })
        return
      }
      if (this.isDnsServerSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { dnsServerReset: true })
        return
      }
      if (this.isDnsZoneSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { dnsReset: true })
        return
      }
      if (this.isIpv4SubnetSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { ipv4SubnetReset: true })
        return
      }
      if (this.isGuestVlanSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { guestVlanReset: true })
        return
      }
      if (this.isVpnUserSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { reset: true })
        return
      }
      if (this.isAclSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', { aclReset: true })
        return
      }
      if (this.isEventSearch || this.isRoleSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.eventFilterError = ''
        this.$emit('search', {})
        return
      }
      if (this.isKmsSearch) {
        this.visibleFilter = false
        this.searchQuery = ''
        this.$emit('search', {})
        return
      }
      if (this.isVnfSearch) { this.vnfBlocked = {}; this.form = reactive({}); this.visibleFilter = false; this.searchQuery = ''; this.$emit('search', {}); return }
      this.volumeLookupReset++
      this.formRef.value.resetFields()
      this.form = reactive({})
      this.isFiltered = false
      this.inputKey = null
      this.inputValue = null
      this.searchQuery = null
      this.paramsFilter = {}

      if (this.apiName === 'listAffinityGroups') {
        await this.fetchAffinityFilters(['domainid', 'account', 'projectid', 'type'])
        this.$emit('search', {})
        return
      }
      const refreshAccountOptions = ['account', 'domainid'].every((field) => {
        return this.fields.some((searchViewField) => searchViewField.name === field)
      })
      if (refreshAccountOptions) {
        await this.fetchDynamicFieldData('account')
      }

      const refreshVgpuProfileOptions = ['gpucardid', 'vgpuprofileid'].every((field) => {
        return this.fields.some((searchViewField) => searchViewField.name === field)
      })
      if (refreshVgpuProfileOptions) {
        await this.fetchDynamicFieldData('vgpuprofileid')
      }

      this.$emit('search', this.paramsFilter)
    },
    handleSubmit () {
      if (this.isOauthSearch) {
        const id = this.form.id?.trim()
        if (id && !this.$isValidUuid(id)) { this.$message.error(this.$t('message.error.input.invaliduuid')); return }
        this.visibleFilter = false
        this.$emit('search', { ...toRaw(this.form), id })
        return
      }
      if (this.isLdapSearch) {
        const id = this.form.id?.trim()
        if (id && !this.$isValidUuid(id)) { this.$message.error(this.$t('message.error.input.invaliduuid')); return }
        const port = String(this.form.port ?? '').trim()
        if (!id && port && (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535)) {
          this.$message.error(this.$t('message.ldap.port.range'))
          return
        }
        this.visibleFilter = false
        this.$emit('search', { ...toRaw(this.form), id, port })
        return
      }
      if (this.isVpnUserSearch) {
        this.visibleFilter = false
        this.$emit('search', { filters: Object.fromEntries(this.fields.map(field => [field.name, this.form[field.name]])) })
        return
      }
      if (this.isAclSearch) {
        if (this.aclVpcBlocked) return
        this.visibleFilter = false
        this.$emit('search', { aclAdvanced: Object.fromEntries(this.fields.map(field => [field.name, this.form[field.name]])) })
        return
      }
      if (this.isEventSearch) {
        this.eventFilterError = eventSearchError(this.form, this.$isValidUuid)
        if (this.eventFilterError) return
      }
      if (this.isVnfSearch) {
        if (Object.values(this.vnfBlocked).some(Boolean)) return
        const error = vnfSearchError({ ...this.form, projectid: this.vnfScope.projectid }, this.$store.getters, this.$getApiParams('listVnfAppliances'))
        if (error) { this.$message.error(this.$t(error)); return }
      }
      if (this.fields.some(field => field.name === 'volumeid') && this.volumeLookupBlocked) return
      if (this.apiName === 'listAffinityGroups' && this.form.account && !this.form.domainid) {
        this.$message.error(this.$t('message.affinity.search.account.domain'))
        return
      }
      this.paramsFilter = {}
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.isFiltered = true
        for (const key in values) {
          const input = values[key]
          if (input === '' || input === null || input === undefined) {
            continue
          }
          this.paramsFilter[key] = input
        }
        if (this.searchFilters.includes('tags')) {
          if (this.inputKey) {
            this.paramsFilter['tags[0].key'] = this.inputKey
            this.paramsFilter['tags[0].value'] = this.inputValue
          }
        }
        if (this.isKmsSearch) {
          this.visibleFilter = false
          this.$emit('search', { kmsAdvanced: this.paramsFilter })
          return
        }
        if (this.isRoleSearch) {
          this.visibleFilter = false
          this.$emit('search', { roleFilters: this.paramsFilter })
          return
        }
        if (this.apiName === 'listExtensions' || this.isInfrastructureSearch || this.isZoneSearch || this.isDnsZoneSearch || this.isDnsServerSearch || this.isAccountSearch || this.isEventSearch || this.isVpnCustomerGatewaySearch || this.isGuestVlanSearch || this.isIpv4SubnetSearch) this.visibleFilter = false
        if (this.isVpnCustomerGatewaySearch && this.paramsFilter.id) this.paramsFilter.id = this.paramsFilter.id.trim()
        if (this.isVnfSearch) {
          if (this.searchQuery) this.paramsFilter.keyword = this.paramsFilter.q = this.searchQuery.trim()
          this.visibleFilter = false
        }
        this.$emit('search', this.paramsFilter)
      }).catch(error => {
        if (error.errorFields?.length) {
          if (this.formRef.value.scrollToField) this.formRef.value.scrollToField(error.errorFields[0].name)
        } else throw error
      })
    },
    changeFilter (filter) {
      this.$emit('change-filter', filter)
    },
    updateIsFiltered () {
      const params = this.apiName === 'listExtensions' || this.isOauthSearch || this.isLdapSearch || this.isInfrastructureSearch || this.isZoneSearch || this.isDnsZoneSearch || this.isDnsServerSearch || this.isAccountSearch || this.isIpv4SubnetSearch || this.isRoleSearch || this.isGuestVlanSearch || this.isVpnUserSearch || this.isVpnCustomerGatewaySearch || this.isAclSearch || this.isEventSearch || this.isKmsSearch || this.isGuestNetworkSearch || this.isVnfSearch || ['listTemplates', 'listIsos', 'listCniConfiguration', 'listAffinityGroups'].includes(this.apiName) ? this.$route.query : this.searchParams
      this.isFiltered = this.searchFilters.some(item => {
        if (params[item]) {
          return true
        }
      })
    }
  }
}
</script>

<style scoped lang="less" src="@/style/components/view/search-view.less" />
<style scoped>
.infra-search-form { width: min(24rem, calc(100vw - 58px)); max-height: calc(100vh - 120px); overflow: auto; }
.acl-list-search-form { width: min(20rem, calc(100vw - 64px)); }
</style>
