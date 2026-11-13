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
  <resource-layout :wide="wideLayout" :class="{ 'autoscale-resource': $route.meta?.name === 'autoscalevmgroup', 'affinity-resource': $route.meta?.name === 'affinitygroup' }">
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
          <template #details><auto-scale-info-summary v-if="$route.meta?.name === 'autoscalevmgroup'" :resource="resource" /><AffinityGroupSummary v-if="$route.meta?.name === 'affinitygroup'" :resource="resource" /><StorageSnapshotSummary v-if="$route.meta?.name === 'snapshot'" :resource="resource" /><GuestNetworkDescription v-if="$route.meta?.name === 'guestnetwork'" :value="resource.displaytext" summary /></template>
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
        <keep-alive v-if="tabs.length === 1 && !isNetworkTabs && $route.meta?.name !== 'snapshotpolicy'">
          <component
            :is="tabs[0].component"
            :key="tabs[0].name"
            :resource="resource"
            :loading="loading"
            :tab="tabs[0].name"
            @refresh="['buckets', 'objectstore'].includes($route.meta.name) && $emit('refresh')"
            v-on="$route.meta?.name === 'template' && tabs[0].name === 'zones' ? { 'availability-observed': acceptTemplateObservation } : {}"
            @wide-layout-change="onWideLayoutChange" />
        </keep-alive>
        <a-tabs
          v-else
          :style="{ width: '100%', marginTop: resourceTabPosition === 'top' ? '-12px' : '0' }"
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
                  :resource="resource"
                  :resourceType="tab.resourceType"
                  :loading="loading && !(quietRefresh && tab.name === 'details' && resource.id && resource.id === $route.params?.id)"
                  :tab="activeTab"
                  @change-resource="$emit('change-resource', $event)"
                  @refresh="['buckets', 'objectstore'].includes($route.meta.name) && $emit('refresh')"
                  v-on="$route.meta?.name === 'template' && tab.name === 'zones' ? { 'availability-observed': acceptTemplateObservation } : {}"
            @wide-layout-change="onWideLayoutChange" />
              </keep-alive>
            </a-tab-pane>
          </template>
        </a-tabs>
      </a-card>
    </template>
  </resource-layout>
</template>

<script>
import { networkTabRoute, uniqueTabs, selectedNetworkTab } from '@/utils/networkTabs'
import { conditionalGuestTab, guestFeatureStatus } from '@/utils/conditionalGuestNetwork'
import DetailsTab from '@/components/view/DetailsTab'
import InfoCard from '@/components/view/InfoCard'
import AutoScaleInfoSummary from '@/views/compute/AutoScaleInfoSummary'
import AffinityGroupSummary from '@/views/compute/AffinityGroupSummary'
import StorageSnapshotSummary from '@/views/storage/StorageSnapshotSummary'
import GuestNetworkDescription from '@/components/view/GuestNetworkDescription'
import ResourceLayout from '@/layouts/ResourceLayout'
import { getAPI } from '@/api'
import { templateObservationContext } from '@/utils/templateAvailability'
import { mixinDevice } from '@/utils/mixin.js'

export default {
  name: 'ResourceView',
  components: {
    InfoCard,
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
      templateZoneObservation: null,
      conditionalTabNotice: '',
      networkService: null,
      projectAccount: null,
      wideLayout: false
    }
  },
  computed: {
    templateContext () { return templateObservationContext(this.$route, this.resource, this.$store.getters, this.$store.state?.user?.token) },
    isNetworkTabs () { return networkTabRoute(this.$route) },
    networkTabsReady () { return !!this.resource.id && (!this.$route.params?.id || this.$route.params.id === this.resource.id) && !this.loading && (this.managedNetworkContext || !this.resource.associatednetworkid || this.networkService !== null) },
    renderedTabs () {
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
    templateContext () { this.templateZoneObservation = null },
    renderedTabs () { if (this.isNetworkTabs) this.setActiveTab() },
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
    window.removeEventListener('popstate', this.setActiveTab)
  },
  methods: {
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
    tabInstanceKey (tab) { return this.isNetworkTabs ? this.networkTabScope + ':' + tab.name : tab.name },
    onTabChange (key) {
      this.conditionalTabNotice = ''
      this.activeTab = key
      this.wideLayout = false
      const query = Object.assign({}, this.$route.query)
      query.tab = key
      if (networkTabRoute(this.$route) || ['ssh', 'userdata', 'cniconfiguration', 'affinitygroup', 'volume', 'computeoffering', 'diskoffering'].includes(this.$route.meta?.name)) {
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
      if (networkTabRoute(this.$route)) {
        if (tab.show && !tab.show(this.resource, this.$route, this.$store.getters.userInfo)) return false
        return !tab.networkServiceFilter || !!(this.networkService?.service && tab.networkServiceFilter(this.networkService.service))
      }
      if (this.networkService && this.networkService.service && tab.networkServiceFilter) {
        return tab.networkServiceFilter(this.networkService.service)
      } else if ('show' in tab) {
        return tab.show(this.resource, this.$route, this.$store.getters.userInfo)
      } else {
        return true
      }
    },
    setActiveTab () {
      const requestedTab = this.$route.query.tab || this.historyTab
      const network = networkTabRoute(this.$route)
      const available = network ? uniqueTabs(this.tabs).filter(tab => !this.networkTabsReady || this.showTab(tab)) : ['ssh', 'userdata', 'cniconfiguration', 'affinitygroup', 'volume', 'computeoffering', 'diskoffering'].includes(this.$route.meta?.name) ? this.tabs.filter(tab => this.showTab(tab)) : this.tabs
      const selected = network ? selectedNetworkTab(available, requestedTab) : available.find(tab => tab.name === requestedTab) || available[0]
      this.activeTab = selected ? selected.name : ''
      // Invalid library deep links use the visible fallback tab in the URL too.
      // A departing KeepAlive view can observe the destination route before its
      // own tabs are replaced. Only the destination's tab configuration may
      // canonicalize its URL.
      const routeTabs = this.$route.meta?.tabs
      const ownsRouteTabs = (this.$route.meta?.name === 'publicip' || !routeTabs) || (routeTabs.length === this.tabs.length && routeTabs.every((tab, index) => tab.name === this.tabs[index].name))
      if (ownsRouteTabs && ((network && this.networkTabsReady) || ['userdata', 'cniconfiguration', 'volume', 'computeoffering', 'diskoffering'].includes(this.$route.meta?.name)) && this.$route.query.tab && selected && requestedTab !== selected.name) {
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
