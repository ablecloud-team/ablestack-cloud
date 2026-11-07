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
  <div>
    <autogen-view @change-resource="changeResource">
      <template #resource>
        <a-alert v-if="contextFailed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')">
          <template #action><a-button size="small" @click="fetchData">{{ $t('label.refresh') }}</a-button></template>
        </a-alert>
        <resource-view
          v-if="isPublicIpAddress && 'id' in resource"
          :key="resource.id"
          :loading="loading"
          :managed-network-context="true"
          :resource="resource"
          :historyTab="activeTab"
          :tabs="tabs"
          :actions="actions"
          @exec-action="(action) => execAction(action, action.groupAction && !dataView)"
          @onTabChange="(tab) => { activeTab = tab }" />
      </template>
    </autogen-view>
  </div>
</template>

<script>
import { shallowRef, defineAsyncComponent } from 'vue'
import { getAPI } from '@/api'
import { publicIpRemoteVpn, publicIpRuleTabs, publicIpScope, publicIpService, usablePublicIp } from '@/utils/publicIpRules'
import { mixinDevice } from '@/utils/mixin.js'
import eventBus from '@/config/eventBus'
import AutogenView from '@/views/AutogenView.vue'
import ResourceView from '@/components/view/ResourceView'

export default {
  name: 'PublicIpResource',
  components: {
    AutogenView,
    ResourceView
  },
  data () {
    return {
      loading: false,
      contextFailed: false,
      contextNetwork: null,
      contextVpc: null,
      contextCounts: {},
      tabRequestVersion: 0,
      selectedRowKeys: [],
      actions: [],
      resource: {},
      tabs: [{
        name: 'details',
        component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
      },
      {
        name: 'events',
        resourceType: 'IpAddress',
        component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
        show: () => { return 'listEvents' in this.$store.getters.apis }
      }],
      defaultTabs: [{
        name: 'details',
        component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
      },
      {
        name: 'events',
        resourceType: 'IpAddress',
        component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
        show: () => { return 'listEvents' in this.$store.getters.apis }
      },
      {
        name: 'comments',
        component: shallowRef(defineAsyncComponent(() => import('@/components/view/AnnotationsTab.vue')))
      }],
      activeTab: ''
    }
  },
  mixins: [mixinDevice],
  provide: function () {
    return {
      parentFetchData: this.fetchData,
      publicIpRemoteVpnAllowed: () => publicIpRemoteVpn(this.resource, this.contextNetwork, this.contextVpc),
      parentToggleLoading: this.toggleLoading
    }
  },
  computed: {
    isPublicIpAddress () {
      return this.$route.path.startsWith('/publicip') && this.$route.path.includes('/publicip/')
    }
  },
  watch: {
    resource: {
      deep: true,
      handler () {
        if ('id' in this.resource) {
          this.fetchData()
        }
      }
    }
  },
  created () {
    if ('id' in this.resource) {
      this.fetchData()
    }
  },
  beforeUnmount () { this.tabRequestVersion++ },
  methods: {
    async fetchData () {
      if (Object.keys(this.resource).length === 0) {
        return
      }

      const version = ++this.tabRequestVersion
      const resource = this.resource
      this.loading = true
      this.contextFailed = false
      try {
        const tabs = await this.filterTabs(resource)
        if (version !== this.tabRequestVersion || resource.id !== this.resource.id) return
        this.tabs = Array.isArray(tabs) ? tabs : tabs.tabs
        this.contextNetwork = tabs.network || null
        this.contextVpc = tabs.vpc || null
        this.contextCounts = tabs.counts || {}
        this.fetchAction()
      } catch (error) {
        if (version !== this.tabRequestVersion || resource.id !== this.resource.id) return
        this.tabs = this.defaultTabs
        this.contextFailed = true
        this.contextNetwork = null
        this.contextVpc = null
        this.contextCounts = {}
        if (this.fetchAction) this.fetchAction()
        this.$notifyError(error)
      } finally {
        if (version === this.tabRequestVersion && resource.id === this.resource.id) this.loading = false
      }
    },
    async filterTabs (resource = this.resource) {
      const [network, vpc] = usablePublicIp(resource) ? await Promise.all([
        resource.associatednetworkid ? this.readContext('listNetworks', 'network', resource.associatednetworkid, resource) : null,
        resource.vpcid ? this.readContext('listVPCs', 'vpc', resource.vpcid, resource) : null
      ]) : [null, null]
      const counts = {}
      const conserve = network?.networkofferingconservemode ?? vpc?.vpcofferingconservemode
      const needsRuleCounts = (vpc && conserve !== true) || resource.hasrules
      if (usablePublicIp(resource) && needsRuleCounts && !resource.isstaticnat && !resource.issourcenat) {
        const apis = this.$store.getters.apis
        const [pf, lb] = await Promise.all([
          'listPortForwardingRules' in apis ? this.readRuleCount('listPortForwardingRules', { ipaddressid: resource.id }, resource) : null,
          'listLoadBalancerRules' in apis ? this.readRuleCount('listLoadBalancerRules', { publicipid: resource.id }, resource) : null
        ])
        Object.assign(counts, { pf, lb })
      }
      const keys = publicIpRuleTabs(resource, network, vpc, counts)
      // Site-to-site gateways remain separate from remote access and use a VPC address.
      if (vpc && !resource.isstaticnat && publicIpService(vpc, 'Vpn') && !keys.includes('vpn')) keys.splice(keys.indexOf('events'), 0, 'vpn')
      return { tabs: this.$route.meta.tabs.filter(tab => keys.includes(tab.name)), network, vpc, counts }
    },
    fetchAction () {
      this.actions = (this.$route.meta.actions || []).filter(action => {
        if (action.api !== 'enableStaticNat') return true
        return !this.contextFailed && publicIpService(this.contextVpc || this.contextNetwork, 'StaticNat') &&
          (!this.resource.hasrules || (this.contextCounts.pf === 0 && this.contextCounts.lb === 0))
      })
    },
    async readContext (api, key, id, ip) {
      const json = await getAPI(api, { ...publicIpScope(ip), id, listall: true }, { preserveOnFailure: true })
      const record = json[api.toLowerCase() + 'response']?.[key]?.find(row => row.id === id)
      if (!record) throw new Error(this.$t('message.guest.feature.load.failed'))
      return record
    },
    async readRuleCount (api, params, ip) {
      const json = await getAPI(api, { ...publicIpScope(ip), ...params, page: 1, pagesize: 1 }, { preserveOnFailure: true })
      return json[api.toLowerCase() + 'response']?.count || 0
    },
    changeResource (resource) {
      this.resource = resource
    },
    toggleLoading () {
      this.loading = !this.loading
    },
    execAction (action, isGroupAction) {
      eventBus.emit('exec-action', { action, isGroupAction })
    }
  }
}
</script>
