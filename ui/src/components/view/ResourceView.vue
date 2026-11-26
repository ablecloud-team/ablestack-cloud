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
  <resource-layout :wide="wideLayout" :class="{ 'event-resource': isEventDetailRoute($route), 'dns-zone-resource': $route.meta?.name === 'dnszone', 'kms-resource': kmsDetailRoute($route), 's2s-gateway-resource': $route.meta?.name === 's2svpn', 'autoscale-resource': $route.meta?.name === 'autoscalevmgroup', 'affinity-resource': $route.meta?.name === 'affinitygroup', 'kubernetes-iso-resource': $route.meta?.name === 'kubernetesiso', 'template-resource': ['template', 'iso', 'kubernetesiso'].includes($route.meta?.name), 'acl-resource': $route.meta?.name === 'acllist', 'project-resource': isProjectDetailRoute($route), 'system-offering-resource': isSystemOffering }">
    <template #left>
      <slot name="info-card">
        <info-card
          :resource="resource"
          :template-observation="templateZoneObservation"
          @show-template-zones="onTabChange('zones')"
          :loading="loading && !(quietRefresh && resource.id && resource.id === $route.params?.id)"
          :actions="actions"
          @backup-controls-change="$emit('backup-controls-change', $event)"
          @exec-action="$emit('exec-action', $event)">
          <template #details>
            <div v-if="$route.meta?.name === 'vpncustomergateway' && resource.gateway" class="gateway-summary-peer">
              <strong>{{ $t('label.vpn.customer.gateway.peer') }}</strong>
              <div>{{ resource.gateway }}</div>
            </div>
            <div v-if="$route.meta?.name === 'guestvlans' && resource.physicalnetworkname" class="gateway-summary-peer">
              <strong>{{ $t('label.physical.network') }}</strong>
              <div>{{ resource.physicalnetworkname }}</div>
            </div>
            <KubernetesIsoSummary v-if="$route.meta?.name === 'kubernetesiso' && resource.id" ref="kubernetesIsoSummary" :resource="resource" :loading="loading && !(quietRefresh && resource.id && resource.id === $route.params?.id)" @observed="acceptKubernetesIsoObservation" @show-zones="onTabChange('zones')" /><auto-scale-info-summary v-if="$route.meta?.name === 'autoscalevmgroup'" :resource="resource" /><AffinityGroupSummary v-if="$route.meta?.name === 'affinitygroup'" :resource="resource" /><StorageSnapshotSummary v-if="$route.meta?.name === 'snapshot'" :resource="resource" /><GuestNetworkDescription v-if="$route.meta?.name === 'guestnetwork'" :value="resource.displaytext" summary /></template>
        </info-card>
      </slot>
    </template>
    <template #right>
      <a-card
        class="spin-content resource-content-card"
        :loading="loading && !resource.id"
        :bordered="true"
        style="width:100%">
        <a-alert
          v-if="conditionalTabNotice"
          class="guest-feature-notice"
          type="info"
          show-icon
          closable
          :message="$t('message.guest.feature.' + conditionalTabNotice)"
          @close="conditionalTabNotice = ''" />
        <keep-alive v-if="tabs.length === 1 && !isNetworkTabs && !['snapshotpolicy', 's2svpn'].includes($route.meta?.name)">
          <component
            :is="tabs[0].component"
            :key="tabs[0].name"
            :resource="tabResource(tabs[0])"
            :loading="loading"
            :tab="tabs[0].name"
            v-bind="$route.meta?.name === 'kubernetesiso' && ['details','zones'].includes(tabs[0].name) ? { observation: kubernetesIsoObservation } : {}"
            v-on="tabListeners(tabs[0])" />
        </keep-alive>
        <a-tabs
          v-else
          :class="{'s2s-gateway-tabs': $route.meta?.name === 's2svpn', 'template-main-tabs':['template','iso','kubernetesiso'].includes($route.meta?.name), 'project-tabs': isProjectDetailRoute($route), 'infrastructure-tabs': ['cluster', 'storagepool', 'imagestore'].includes($route.meta?.name), 'system-offering-tabs': isSystemOffering}"
          :style="{ width: '100%', marginTop: isSystemOffering ? undefined : resourceTabPosition === 'top' ? '-12px' : '0' }"
          :tabPosition="resourceTabPosition"
          :animated="false"
          :activeKey="activeTab"
          @change="onTabChange" >
          <template v-for="tab in renderedTabs" :key="tab.name">
            <a-tab-pane
              :key="tab.name"
              :tab="$t('label.' + tabName(tab))"
              v-if="showTab(tab)">
              <keep-alive>
                <component
                  :is="tab.component"
                  :key="tabInstanceKey(tab)"
                  :resource="tabResource(tab)"
                  :loading="loading && !(quietRefresh && tab.name === 'details' && resource.id && resource.id === $route.params?.id)"
                  :tab="['runtimebundle', 's2svpnconn'].includes($route.meta?.name) ? tab.name : activeTab"
                  v-bind="tabProperties(tab)"
                  v-on="tabListeners(tab, true)" />
              </keep-alive>
            </a-tab-pane>
          </template>
        </a-tabs>
      </a-card>
    </template>
  </resource-layout>
</template>

<script>
import '@/style/components/view/template-tabs.less'
import { isProjectDetailRoute, projectRouteHistory } from '@/utils/projectSearch'
import { isAccountDetailRoute, accountRouteHistory } from '@/utils/accountSearch'
import { isRoleDetailRoute, roleRouteHistory } from '@/utils/roleSearch'
import { gatewayContext } from '@/utils/s2sGateway'
import { networkTabRoute, uniqueTabs, selectedNetworkTab } from '@/utils/networkTabs'
import { conditionalGuestTab, guestFeatureStatus } from '@/utils/conditionalGuestNetwork'
import DetailsTab from '@/components/view/DetailsTab'
import InfoCard from '@/components/view/InfoCard'
import KubernetesIsoSummary from '@/components/view/KubernetesIsoSummary'
import { kubernetesIsoDetailContext } from '@/utils/kubernetesIsoDetails'
import AutoScaleInfoSummary from '@/views/compute/AutoScaleInfoSummary'
import AffinityGroupSummary from '@/views/compute/AffinityGroupSummary'
import StorageSnapshotSummary from '@/views/storage/StorageSnapshotSummary'
import GuestNetworkDescription from '@/components/view/GuestNetworkDescription'
import ResourceLayout from '@/layouts/ResourceLayout'
import { getAPI } from '@/api'
import { kmsDetailRoute, kmsRouteHistory } from '@/utils/kmsNavigation'
import { eventRouteHistory, isEventDetailRoute } from '@/utils/eventTimeline'
import { templateObservationContext } from '@/utils/templateAvailability'
import { mixinDevice } from '@/utils/mixin.js'
import { loadHostBackupIntegrationProviders } from '@/utils/hostBackupIntegration'

export default {
  name: 'ResourceView',
  components: {
    InfoCard,
    KubernetesIsoSummary,
    AutoScaleInfoSummary,
    AffinityGroupSummary,
    StorageSnapshotSummary,
    GuestNetworkDescription,
    ResourceLayout
  },
  mixins: [mixinDevice],
  props: {
    quietRefresh: { type: Boolean, default: false },
    readyForActions: { type: Boolean, default: true },
    readPending: { type: Boolean, default: false },
    readFailed: { type: Boolean, default: false },
    managedNetworkContext: { type: Boolean, default: false },
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    },
    actions: {
      type: Array,
      default: function () {
        return []
      }
    },
    tabs: {
      type: Array,
      default: function () {
        return [{
          name: 'details',
          component: DetailsTab
        }]
      }
    },
    historyTab: {
      type: String,
      default: ''
    }
  },
  provide () { return { resourceActionReady: () => this.readyForActions, resourceReadPending: () => this.readPending || this.loading, resourceReadFailed: () => this.readFailed, resourceActiveTab: () => this.activeTab } },
  data () {
    return {
      activeTab: '',
      hostBackupProviders: [],
      hostBackupLoaded: false,
      hostBackupRevision: 0,
      templateZoneObservation: null,
      conditionalTabNotice: '',
      networkService: null,
      projectAccount: null,
      kubernetesIsoObservation: null,
      wideLayout: false
    }
  },
  computed: {
    isSystemOffering () { return this.$route.meta?.name === 'systemoffering' },
    kubernetesIsoContext () { return this.$route.meta?.name === 'kubernetesiso' ? kubernetesIsoDetailContext(this.$route, this.resource, this.$store.getters, this.$store.state?.user?.token) : '' },
    templateTabsReady () { return this.$route.meta?.name === 'template' && !!this.resource.id && (!this.$route.params?.id || this.$route.params.id === this.resource.id) },
    templateTabScope () { return this.$route.meta?.name === 'template' ? JSON.stringify([this.templateContext, this.resource.templatetype, Object.keys(this.$store.getters.apis || {}).sort()]) : '' },
    templateContext () { return templateObservationContext(this.$route, this.resource, this.$store.getters, this.$store.state?.user?.token) },
    hostBackupTabScope () {
      if (this.$route.meta?.name !== 'host') return ''
      return JSON.stringify([this.$route.path, this.$route.params?.id, this.resource.id, this.resource.zoneid, this.resource.hypervisor, this.resource.type,
        this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.roletype,
        this.$store.state?.user?.token, !!this.$store.getters.apis?.listConfigurations, !!this.$store.getters.apis?.getNetBackupHostConfiguration])
    },
    isNetworkTabs () { return networkTabRoute(this.$route) },
    networkTabsReady () { return !!this.resource.id && (!this.$route.params?.id || this.$route.params.id === this.resource.id) && !this.loading && (this.managedNetworkContext || !this.resource.associatednetworkid || this.networkService !== null) },
    s2sTabScope () { return this.$route.meta?.name === 's2svpn' ? gatewayContext(this) : '' },
    dnsZoneTabScope () {
      if (this.$route.meta?.name !== 'dnszone') return ''
      const user = this.$store.getters.userInfo || {}
      const apis = this.$store.getters.apis || {}
      return JSON.stringify([this.resource.id, user.id, user.roleid, this.$store.getters.project?.id, !!apis.listDnsRecords, !!apis.createDnsRecord, !!apis.deleteDnsRecord])
    },
    guestVlanTabScope () {
      const { id, allocationstate } = this.resource
      return this.$route.meta?.name === 'guestvlans' && id && String(id) === String(this.$route.params?.id) && allocationstate
        ? id + ':' + allocationstate
        : ''
    },
    renderedTabs () {
      if (this.dnsZoneTabScope || ['host', 's2svpn'].includes(this.$route.meta?.name)) return this.tabs.filter(tab => this.showTab(tab))
      if (!this.isNetworkTabs) return this.tabs
      return this.networkTabsReady ? uniqueTabs(this.tabs).filter(tab => this.showTab(tab)) : []
    },
    networkTabScope () {
      if (!this.isNetworkTabs) return ''
      const user = this.$store.getters.userInfo || {}
      return JSON.stringify([this.resource.id, user.id, user.roleid, user.roletype, this.$store.getters.project?.id, Object.keys(this.$store.getters.apis || {}).sort()])
    }
  },
  watch: {
    kubernetesIsoContext: { flush: 'sync', handler () { this.kubernetesIsoObservation = null } },
    templateTabScope () { if (this.templateTabsReady) this.setActiveTab() },
    templateContext () { this.templateZoneObservation = null },
    s2sTabScope: { flush: 'sync', handler () { if (this.$route.meta?.name === 's2svpn') this.setActiveTab() } },
    dnsZoneTabScope () { this.setActiveTab() },
    hostBackupTabScope: {
      immediate: true,
      handler () {
        this.hostBackupProviders = []
        this.hostBackupLoaded = false
        this.refreshHostBackupIntegration()
      }
    },
    readPending (pending, previous) {
      if (!pending && previous && this.$route.meta?.name === 'host') this.refreshHostBackupIntegration()
    },
    guestVlanTabScope (scope) { if (scope) this.setActiveTab() },
    renderedTabs () { if (this.isNetworkTabs || this.$route.meta?.name === 's2svpn') this.setActiveTab() },
    networkTabScope () { if (this.isNetworkTabs) this.setActiveTab() },
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (newItem.id === oldItem.id && newItem.associatednetworkid === oldItem.associatednetworkid) return

        this.wideLayout = false
        this.conditionalTabNotice = ''
        this.fetchData()
      }
    },
    '$route.fullPath': function () {
      this.setActiveTab()
    },
    tabs: {
      handler () {
        this.setActiveTab()
      }
    }
  },
  created () {
    this.setActiveTab()
    window.addEventListener('popstate', this.setActiveTab)
    this.fetchData()
  },
  beforeUnmount () {
    this.hostBackupRevision++
    window.removeEventListener('popstate', this.setActiveTab)
  },
  methods: {
    tabResource (tab) {
      // The provider list is a UI observation, not an added Host API field.
      return this.$route.meta?.name === 'host' && tab?.name === 'backup.integration'
        ? { ...this.resource, backupintegrationproviders: this.hostBackupProviders }
        : this.resource
    },
    async refreshHostBackupIntegration () {
      const revision = ++this.hostBackupRevision
      const scope = this.hostBackupTabScope
      if (this.$route.meta?.name !== 'host' || !this.resource.id) return
      if (this.$route.params?.id && this.$route.params.id !== this.resource.id) return
      if (!this.resource.zoneid || this.resource.hypervisor !== 'KVM' || (this.resource.type && this.resource.type !== 'Routing') ||
          this.$store.getters.userInfo?.roletype !== 'Admin' || !this.$store.getters.apis?.listConfigurations) {
        this.hostBackupProviders = []
        this.hostBackupLoaded = true
        this.setActiveTab()
        return
      }
      try {
        const providers = await loadHostBackupIntegrationProviders(getAPI, this.resource.zoneid)
        if (revision !== this.hostBackupRevision || scope !== this.hostBackupTabScope) return
        this.hostBackupProviders = providers
      } catch {
        if (revision !== this.hostBackupRevision || scope !== this.hostBackupTabScope) return
        this.hostBackupProviders = []
      }
      this.hostBackupLoaded = true
      this.setActiveTab()
    },
    isProjectDetailRoute,
    kmsDetailRoute,
    isEventDetailRoute,
    refreshKubernetesIsoAvailability () { return this.$refs.kubernetesIsoSummary?.refresh() },
    acceptKubernetesIsoObservation (value) {
      if (this.$route.meta?.name === 'kubernetesiso' && value?.versionid === this.resource.id && value?.isoid === this.resource.isoid && value.context === this.kubernetesIsoContext) this.kubernetesIsoObservation = value
    },
    acceptTemplateObservation (observation) {
      if (this.$route.meta?.name === 'template' && observation?.context === this.templateContext && observation.templateid === this.resource.id) this.templateZoneObservation = observation
    },
    fetchData () {
      const resourceId = this.resource.id
      const networkId = this.resource.associatednetworkid
      this.networkService = null
      if (networkId && !this.managedNetworkContext) {
        getAPI('listNetworks', { id: networkId, listall: true }).then(response => {
          if (this.resource.id !== resourceId || this.resource.associatednetworkid !== networkId) return
          if (response && response.listnetworksresponse && response.listnetworksresponse.network) {
            this.networkService = response.listnetworksresponse.network[0]
          } else {
            this.networkService = {}
          }
        }).catch(error => {
          if (this.resource.id !== resourceId || this.resource.associatednetworkid !== networkId) return
          this.networkService = {}
          this.$notifyError(error)
        })
      }
    },
    tabInstanceKey (tab) { if (this.dnsZoneTabScope) return this.dnsZoneTabScope + ':' + tab.name; if (this.$route.meta?.name === 's2svpn') return this.s2sTabScope + ':' + tab.name; if (this.$route.meta?.name === 'kubernetesiso') return this.kubernetesIsoContext + ':' + tab.name; return this.isNetworkTabs ? this.networkTabScope + ':' + tab.name : tab.name },
    tabProperties (tab) {
      const props = {}
      if (tab.resourceType) props.resourceType = tab.resourceType
      if (this.$route.meta?.name === 'kubernetesiso' && ['details', 'zones'].includes(tab.name)) {
        props.observation = this.kubernetesIsoObservation
      }
      if (this.$route.meta?.name === 'runtimebundle' && tab.name !== 'details') {
        props.active = tab.name === this.activeTab && this.resource.id === this.$route.params?.id
      }
      return props
    },
    tabListeners (tab, multiple = false) {
      const section = this.$route.meta?.name
      if (['extension', 'customaction'].includes(section)) return {}

      const listeners = { 'wide-layout-change': this.onWideLayoutChange }
      if (section === 'kubernetesiso') {
        listeners['show-zones'] = () => this.onTabChange('zones')
        listeners['refresh-availability'] = this.refreshKubernetesIsoAvailability
      }
      if (multiple) listeners['change-resource'] = value => this.$emit('change-resource', value)
      if (['buckets', 'objectstore'].includes(section) ||
          (multiple && ['account', 'domain'].includes(section) && tab.name === 'limits.configure') ||
          (section === 'template' && ['vnf.settings', 'settings'].includes(tab.name))) {
        listeners.refresh = () => this.$emit('refresh')
      }
      if (tab.name === 'zones' && ['template', 'iso'].includes(section)) {
        if (section === 'template') listeners['availability-observed'] = this.acceptTemplateObservation
        listeners['operation-retained'] = value => this.$emit(section + '-operation-retained', value)
        listeners['refresh-parent'] = () => this.$emit('refresh')
      }
      return listeners
    },
    onTabChange (key) {
      this.conditionalTabNotice = ''
      this.activeTab = key
      this.wideLayout = false
      const query = Object.assign({}, this.$route.query)
      query.tab = key
      const historyState = this.$router?.options?.history?.state
      if (isAccountDetailRoute(this.$route)) {
        this.$router.replace({ path: this.$route.path, query, state: accountRouteHistory(this.$route, historyState, this.$store.getters, this.$store.state?.user?.token) })
        this.$emit('onTabChange', key)
        return
      }
      if (isRoleDetailRoute(this.$route)) {
        this.$router.replace({ path: this.$route.path, query, state: roleRouteHistory(this.$route, historyState, this.$store.getters, this.$store.state?.user?.token) })
        this.$emit('onTabChange', key)
        return
      }
      if (isProjectDetailRoute(this.$route)) {
        this.$router.replace({ path: this.$route.path, query, state: projectRouteHistory(this.$route, historyState, this.$store.getters, this.$store.state?.user?.token) })
        this.$emit('onTabChange', key)
        return
      }
      if (isEventDetailRoute(this.$route)) {
        this.$router.replace({ path: this.$route.path, query, state: eventRouteHistory(this.$route, historyState, this.$store.getters, this.$store.state?.user?.token) })
        this.$emit('onTabChange', key)
        return
      }
      if (kmsDetailRoute(this.$route) || (this.$route.meta?.name === 'volume' && historyState?.kmsRelatedReturn)) {
        this.$router.push({ path: this.$route.path, query, state: kmsRouteHistory(historyState) })
        this.$emit('onTabChange', key)
        return
      }
      if (networkTabRoute(this.$route) || ['webhook', 'zone', 'physicalnetwork', 'kubernetesiso', 'template', 'iso', 'ssh', 'userdata', 'cniconfiguration', 'affinitygroup', 'volume', 'computeoffering', 'diskoffering', 'vpcoffering', 'privategw', 's2svpn', 's2svpnconn', 'vpncustomergateway', 'guestvlans', 'dnszone', 'extension', 'customaction'].includes(this.$route.meta?.name)) {
        this.$router.push({ path: this.$route.path, query })
        this.$emit('onTabChange', key)
        return
      }
      this.$route.query.tab = key
      history.pushState(
        {},
        null,
        '#' + this.$route.path + '?' + Object.keys(query).map(key => {
          return (
            encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
          )
        }).join('&')
      )
      this.$emit('onTabChange', key)
    },
    onWideLayoutChange (enabled) {
      this.wideLayout = !!enabled
    },
    tabName (tab) {
      if (typeof tab.label === 'function') return tab.label(this.resource)
      if (typeof tab.name === 'function') {
        return tab.name(this.resource)
      }
      return tab.name
    },
    showTab (tab) {
      if (tab.permission?.some(api => !this.$store.getters.apis?.[api])) return false
      if (networkTabRoute(this.$route)) {
        if (tab.show && !tab.show(this.resource, this.$route, this.$store.getters.userInfo)) return false
        return !tab.networkServiceFilter || !!(this.networkService?.service && tab.networkServiceFilter(this.networkService.service))
      }
      if (this.networkService && this.networkService.service && tab.networkServiceFilter) {
        return tab.networkServiceFilter(this.networkService.service)
      } else if ('show' in tab) {
        const record = this.$route.meta?.name === 'host' && tab.name === 'backup.integration' ? this.tabResource(tab) : this.resource
        return tab.show(record, this.$route, this.$store.getters.userInfo)
      } else {
        return true
      }
    },
    setActiveTab () {
      const requestedTab = this.$route.query.tab || this.historyTab
      const network = networkTabRoute(this.$route)
      const available = network ? uniqueTabs(this.tabs).filter(tab => !this.networkTabsReady || this.showTab(tab)) : kmsDetailRoute(this.$route) || ['host', 'kubernetesiso', 'template', 'ssh', 'userdata', 'cniconfiguration', 'affinitygroup', 'volume', 'computeoffering', 'diskoffering', 'vpcoffering', 'privategw', 's2svpn', 's2svpnconn', 'vpncustomergateway', 'guestvlans', 'dnszone'].includes(this.$route.meta?.name) ? this.tabs.filter(tab => this.showTab(tab)) : this.tabs
      const selected = network ? selectedNetworkTab(available, requestedTab) : available.find(tab => tab.name === requestedTab) || available[0]
      this.activeTab = selected ? selected.name : ''
      // Invalid library deep links use the visible fallback tab in the URL too.
      // A departing KeepAlive view can observe the destination route before its
      // own tabs are replaced. Only the destination's tab configuration may
      // canonicalize its URL.
      const routeTabs = this.$route.meta?.tabs
      const ownsRouteTabs = (this.$route.meta?.name === 'publicip' || !routeTabs) || (routeTabs.length === this.tabs.length && routeTabs.every((tab, index) => tab.name === this.tabs[index].name))
      if (ownsRouteTabs && ((this.$route.meta?.name === 'host' && this.hostBackupLoaded) || (network && this.networkTabsReady) || this.templateTabsReady || this.guestVlanTabScope || this.dnsZoneTabScope || kmsDetailRoute(this.$route) || ['kubernetesiso', 'userdata', 'cniconfiguration', 'volume', 'computeoffering', 'diskoffering', 'vpcoffering', 'privategw', 's2svpn', 's2svpnconn', 'vpncustomergateway'].includes(this.$route.meta?.name)) && this.$route.query.tab && selected && requestedTab !== selected.name) {
        if (this.$route.meta?.name === 'guestnetwork' && conditionalGuestTab(requestedTab)) {
          this.conditionalTabNotice = guestFeatureStatus(requestedTab, this.resource, this.$store.getters)
        } else if (this.$route.meta?.name === 'guestnetwork' && this.resource.type === 'L2' &&
          ['egress.rules', 'public.ip.addresses', 'ip.addresses', 'virtual.routers', 'guest.ip.range'].includes(requestedTab)) {
          this.conditionalTabNotice = 'unsupported'
        } else if (this.$route.meta?.name === 'publicip' && ['firewall', 'portforwarding', 'loadbalancing', 'vpn'].includes(requestedTab)) {
          this.conditionalTabNotice = 'unsupported'
        }
        this.$router.replace({ path: this.$route.path, query: { ...this.$route.query, tab: selected.name } })
      }
    }
  }
}
</script>

<style lang="less" scoped>
.infrastructure-tabs :deep(.ant-tabs-content-holder),
.infrastructure-tabs :deep(.ant-tabs-tabpane) { min-width: 0; }
.template-resource :deep(.resource-content-card .ant-tabs-content-holder),
.dns-zone-resource :deep(.resource-content-card .ant-tabs-content-holder),
.event-resource :deep(.resource-content-card .ant-tabs-content-holder) { min-width: 0; }
.dns-zone-resource :deep(.resource-content-card .ant-tabs-tabpane),
.template-resource :deep(.resource-content-card .ant-tabs-tabpane) { min-width: 0; }
/* Match the notice-line spacing and rail minimum of VM InstanceTab. */
.s2s-gateway-resource :deep(.s2s-gateway-tabs) { margin-top: 22px !important; }
.s2s-gateway-resource :deep(.s2s-gateway-tabs.ant-tabs-left > .ant-tabs-nav) { min-width: 100px; }
.gateway-summary-peer { margin-top: 12px; overflow-wrap: anywhere; }

/* Match the initial spacing and navigation rail in VM InstanceTab. */
.system-offering-tabs { margin-top: 22px; }
.system-offering-tabs.ant-tabs-left > :deep(.ant-tabs-nav) { min-inline-size: 100px; }
.system-offering-tabs > :deep(.ant-tabs-content-holder),
.system-offering-tabs :deep(.ant-tabs-tabpane) { min-width: 0; }
.system-offering-resource :deep(.ant-list-item) { padding-block: 12px; }
.system-offering-resource :deep(.resource-details__name > :last-child) { min-width: 0; }
.system-offering-resource :deep(.resource-details__name .name) { word-break: normal; overflow-wrap: anywhere; }
.guest-feature-notice {
  margin-bottom: 16px;
}

.resource-content-card {
  background: var(--ui-bg-surface);
}

:deep(.resource-content-card > .ant-card-body),
:deep(.resource-content-card .ant-spin-nested-loading),
:deep(.resource-content-card .ant-spin-container) {
  background: var(--ui-bg-surface);
}
</style>

<style scoped>
.kms-resource :deep(.resource-content-card),
.kms-resource :deep(.ant-tabs),
.kms-resource :deep(.ant-tabs-content-holder),
.kms-resource :deep(.ant-tabs-tabpane) { min-width: 0; max-width: 100%; }
.kms-resource :deep(.ant-list-item) { overflow-wrap: anywhere; }
</style>

<style scoped>
.project-resource :deep(.resource-content-card),
.project-resource :deep(.ant-tabs-content-holder),
.project-resource :deep(.ant-tabs-tabpane),
.project-resource :deep(.resource-details__name > :last-child) { min-width: 0; }
.project-resource :deep(.resource-details__name .name),
.project-resource :deep(.resource-detail-item__details),
.project-resource :deep(.project-tab-table .ant-table-cell) { overflow-wrap: anywhere; }
.project-resource :deep(.project-tab-toolbar) {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;
}
.project-resource :deep(.project-tab-toolbar .ant-input-search) {
  width: 280px;
  max-width: 100%;
  margin-inline-start: auto;
}
.project-resource :deep(.project-tab-notice) { margin-bottom: 12px; }
.project-resource :deep(.project-tabs.ant-tabs-left > .ant-tabs-content-holder > .ant-tabs-content > .ant-tabs-tabpane) {
  padding-inline-start: 16px;
}
@media (max-width: 768px) {
  .project-resource :deep(.resource-content-card > .ant-card-body) { padding: 16px; }
  .project-resource :deep(.project-tab-toolbar .ant-input-search) { width: 100%; margin-inline-start: 0; }
}
</style>
