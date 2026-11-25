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
  <a-breadcrumb class="breadcrumb" v-bind="$attrs">
    <a-breadcrumb-item v-for="(item, index) in resolvedTrail" :key="index">
      <router-link
        v-if="item && item.name && !tungstenPaths.includes(item.path)"
        :to="breadcrumbLocation(item)"
      >
        <render-icon v-if="index == 0" :icon="item.meta.icon" style="font-size: 16px" @click="resetToMainView" />
        <span v-if="item.text || item.meta.title">{{ item.text || (item.vpcParent ? $t('label.vpc') + ' · ' + (resource.vpcname || resource.vpcid) : $t(item.meta.title)) }}</span>
      </router-link>
      <router-link
        v-else-if="tungstenPaths.includes(item.path)"
        :to="{ path: item.path === '' ? '/' : item.path, query: { zoneid: $route.query.zoneid } }">
        <render-icon v-if="index == 0" :icon="item.meta.icon" style="font-size: 16px" @click="resetToMainView" />
        {{ $t(item.meta.title) }}
      </router-link>
      <span v-else-if="item.current" class="vm-acl-breadcrumb-current" aria-current="page">
        {{ item.text || $t(item.meta.title) }}
      </span>
      <span
        v-else-if="$route.params.id"
        :class="{ 'vm-acl-breadcrumb-current': standardLayout }"
        :aria-current="standardLayout ? 'page' : undefined"
        :title="standardLayout ? resourceTitle : undefined">
        <label
          v-if="'name' in resource &&
            ['USER.LOGIN', 'USER.LOGOUT', 'ROUTER.HEALTH.CHECKS', 'FIREWALL.CLOSE', 'ALERT.SERVICE.DOMAINROUTER'].includes(resource.name)">
          <span>
            {{ $t(resource.name.toLowerCase()) }}
          </span>
        </label>
        <keyboard-tooltip v-else-if="$route.meta?.name === 'kubernetesiso'" :title="resource.name || $route.params.id" :trigger="['hover','focus']">
          <span tabindex="0" class="kubernetes-iso-breadcrumb-name">{{ resource.name || $route.params.id }}</span>
        </keyboard-tooltip>
        <span v-else-if="isEventDetailRoute($route)" class="event-breadcrumb-title">{{ eventBreadcrumbTitle(resource) || $route.params.id }}</span>
        <span v-else-if="vpnConnectionRoute($route)" class="s2s-connection-breadcrumb-name">{{ vpnConnectionName(resource, $route.params.id) }}</span>
        <label v-else :class="{ 'kms-breadcrumb-name': kmsDetailRoute($route) }">
          {{ resourceTitle }}
        </label>
      </span>
      <span v-else>
        {{ $t(item.meta.title) }}
      </span>
      <BreadcrumbControls
        v-if="!standardLayout && index === resolvedTrail.length - 1"
        :doc-url="helpUrl(item)"
        :parent-location="kmsParentLocation">
        <template #end><slot name="end" /></template>
      </BreadcrumbControls>
    </a-breadcrumb-item>
  </a-breadcrumb>
  <BreadcrumbControls v-if="standardLayout && resolvedTrail.length" standard :doc-url="helpUrl(resolvedTrail.at(-1))">
    <template #end><slot name="end" /></template>
  </BreadcrumbControls>
</template>

<script>
import { runtimeCatalogListQuery } from '@/utils/runtimeCatalog'
import { oauthListLocation } from '@/utils/oauthConfiguration'
import { ldapConfigRoute, ldapEndpointLabel, ldapListQuery } from '@/utils/ldapConfiguration'
import { dnsServerListQuery } from '@/utils/dnsServer'
import { systemOfferingListQuery } from '@/utils/systemOfferingTypes'
import { physicalNetworkZoneLocation, zoneListQuery } from '@/utils/zone'
import { extensionListQuery, extensionActionListQuery } from '@/utils/extension'
import { webhookBackQuery } from '@/utils/webhookDeliveryQuery'
import { clusterListQuery } from '@/utils/clusterSearch'
import { primaryStorageListQuery } from '@/utils/primaryStorageSearch'
import { secondaryStorageListQuery } from '@/utils/secondaryStorageSearch'
import { hostListQuery } from '@/utils/hostSearch'
import { dnsZoneListQuery } from '@/utils/dnsZone'
import { ipv4SubnetListQuery } from '@/utils/ipv4Subnet'
import BreadcrumbControls from '@/components/widgets/BreadcrumbControls'
import { isProjectDetailRoute, projectListLocation } from '@/utils/projectSearch'
import { isAccountDetailRoute, accountListLocation } from '@/utils/accountSearch'
import { isRoleDetailRoute, roleListLocation } from '@/utils/roleSearch'
import { aclListLocation, isAclListRoute } from '@/utils/vpcAclAccess'
import KeyboardTooltip from '@/components/widgets/KeyboardTooltip'
import { kubernetesIsoListLocation } from '@/utils/kubernetesIsoSearch'
import { templateListLocation } from '@/utils/templateSearch'
import { isoListLocation } from '@/utils/isoSearch'
import { guestVlanListQuery, guestVlanRoute } from '@/utils/guestVlan'
import { networkListLocation } from '@/utils/guestNetworkSearch'
import { documentationUrl } from '@/utils/cniFileImport'

import { vmDisplayName, isVmIdentityRoute } from '@/utils/vmIdentity'
import { publicIpListLocation } from '@/utils/publicIpSearch'
import { vpnUserListLocation, vpnUserRoute } from '@/utils/vpnUserSearch'
import { gatewayName } from '@/utils/s2sGateway'
import { s2sGatewayListLocation, s2sGatewayRoute, vpnConnectionApi, vpnConnectionName, vpnConnectionRoute } from '@/utils/s2sGatewaySearch'
import { vnfListLocation } from '@/utils/vnfSearch'
import { kmsDetailRoute, kmsListLocation } from '@/utils/kmsNavigation'
import { eventDisplayTitle, eventListLocation, isEventDetailRoute } from '@/utils/eventTimeline'
import { kmsRelatedParentLocation } from '@/utils/kmsRelated'

export default {
  name: 'Breadcrumb',
  inheritAttrs: false,
  components: { BreadcrumbControls, KeyboardTooltip },
  props: {
    standardLayout: { type: Boolean, default: false },
    trail: { type: Array, default: null },
    resource: {
      type: Object,
      default: function () {
        return {}
      }
    }
  },
  data () {
    return {
      name: '',
      breadList: [],
      tungstenPaths: ['/tungstennetworkroutertable', '/tungstenpolicy', '/tungsteninterfaceroutertable',
        '/tungstenpolicyset', '/tungstenroutingpolicy', '/firewallrule', '/tungstenfirewallpolicy']
    }
  },
  computed: {
    resolvedTrail () {
      const trail = this.trail || this.breadList
      if (this.$route.meta?.name !== 'customaction' || !this.resource.extensionid || !trail.length || !('listExtensions' in this.$store.getters.apis)) return trail
      const list = extensionListQuery(this.$route.query)
      return [
        trail[0],
        { name: 'extension', path: '/extension', location: { path: '/extension', query: list }, meta: { title: 'label.extensions' } },
        {
          name: 'extension-parent',
          text: this.resource.extensionname || this.resource.extensionid,
          location: { path: '/extension/' + this.resource.extensionid, query: { ...list, ...extensionActionListQuery(this.$route.query), tab: 'customactions' } },
          meta: {}
        },
        trail.at(-1)
      ]
    },
    resourceTitle () {
      if (s2sGatewayRoute(this.$route)) return gatewayName(this.resource)
      if (isVmIdentityRoute(this.$route)) return vmDisplayName(this.resource)
      if (ldapConfigRoute(this.$route)) return ldapEndpointLabel(this.resource) || this.$route.params.id
      if (this.$route.meta?.name === 'ipv4subnets' && this.resource.subnet) return this.resource.subnet
      if (guestVlanRoute(this.$route) && this.resource.vlan) return this.$t('label.vlan') + ' ' + this.resource.vlan
      const row = this.resource
      return row.displayname || row.name || row.displaytext || row.hostname || row.username || row.ipaddress || row.osname || row.osdisplayname || this.$route.params.id
    },
    kmsParentLocation () {
      if (!['volume', 'kmskey'].includes(this.$route.meta?.name) || !this.$route.params?.id) return null
      const state = this.$router?.options?.history?.state
      return state?.kmsRelatedReturn ? kmsRelatedParentLocation(this.$route, state, this.$store.getters, this.$store.state?.user?.token) : null
    }
  },
  created () {
    this.getBreadcrumb()
  },
  watch: {
    'resource.vpcid' () { this.getBreadcrumb() },
    '$route.fullPath' () {
      this.getBreadcrumb()
    }
  },
  methods: {
    helpUrl (item) {
      return item?.meta?.docHelp ? documentationUrl(this.$config.docBase, this.$route.meta.docHelp, this.$i18n.locale) : ''
    },
    gatewayName,
    vpnConnectionName,
    vpnConnectionRoute,
    s2sGatewayRoute,
    vmDisplayName,
    isVmIdentityRoute,
    documentationUrl,
    kmsDetailRoute,
    isEventDetailRoute,
    eventBreadcrumbTitle (event) { return eventDisplayTitle(event, this.$t, date => this.$toLocaleDate(date)) },
    breadcrumbLocation (item) {
      if (item.location) return item.location
      if (item.path === '/runtimebundle' && this.$route.params?.id) return { path: item.path, query: runtimeCatalogListQuery(this.$route.query) }
      if (item.path === '/webhook' && this.$route.params?.id) return { path: item.path, query: webhookBackQuery(this.$route.query) }
      if (item.path === '/extension' && this.$route.params?.id) return { path: item.path, query: extensionListQuery(this.$route.query) }
      if (item.path === '/oauthsetting' && this.$route.params?.id) return oauthListLocation(this.$route)
      if (item.path === '/ldapsetting' && this.$route.params?.id) return { path: item.path, query: ldapListQuery(this.$route.query) }
      if (item.path === '/account' && isAccountDetailRoute(this.$route)) {
        return accountListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token)
      }
      if (item.path === '/role' && isRoleDetailRoute(this.$route)) {
        return roleListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token)
      }
      if (item.path === '/project' && isProjectDetailRoute(this.$route)) {
        return projectListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token)
      }
      if (item.path === '/acllist' && isAclListRoute(this.$route) && this.$route.params?.id) {
        return aclListLocation(this.$route, this.resource, this.$store.getters)
      }
      if (item.path === '/event' && isEventDetailRoute(this.$route)) {
        return eventListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token, this.resource)
      }
      if (kmsDetailRoute(this.$route) && item.path === '/' + this.$route.meta.name) {
        return kmsListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token)
      }
      if (item.path === '/s2svpnconn' && this.$route.meta?.name === 's2svpnconn' && this.$route.params?.id) return s2sGatewayListLocation(this.$route, this.$store.getters, vpnConnectionApi)
      if (item.path === '/s2svpn' && this.$route.meta?.name === 's2svpn' && this.$route.params?.id) return s2sGatewayListLocation(this.$route, this.$store.getters)
      if (item.path === '/kubernetesiso' && this.$route.meta?.name === 'kubernetesiso' && this.$route.params?.id) return kubernetesIsoListLocation(this.$route, this.$store.getters, this.$store.state?.user?.token)
      if (item.path === '/vpnuser' && vpnUserRoute(this.$route) && this.$route.params?.id) return vpnUserListLocation(this.$route, this.$store.getters)
      if (item.path === '/systemoffering' && this.$route.params?.id) return { path: item.path, query: systemOfferingListQuery(this.$route.query) }
      if (item.path === '/physicalnetwork' && this.$route.params?.id && this.resource?.zoneid) return physicalNetworkZoneLocation(this.$route, this.resource.zoneid)
      if (item.path === '/imagestore' && this.$route.meta?.name === 'imagestore' && this.$route.params?.id) return { path: item.path, query: secondaryStorageListQuery(this.$route.query) }
      if (item.path === '/storagepool' && this.$route.meta?.name === 'storagepool' && this.$route.params?.id) return { path: item.path, query: primaryStorageListQuery(this.$route.query) }
      if (item.path === '/cluster' && this.$route.meta?.name === 'cluster' && this.$route.params?.id) return { path: item.path, query: clusterListQuery(this.$route.query) }
      if (item.path === '/host' && this.$route.meta?.name === 'host' && this.$route.params?.id) return { path: item.path, query: hostListQuery(this.$route.query) }
      if (item.path === '/zone' && this.$route.params?.id) return { path: item.path, query: zoneListQuery(this.$route.query) }
      if (item.path === '/dnsserver' && this.$route.params?.id) return { path: item.path, query: dnsServerListQuery(this.$route.query) }
      if (item.path === '/dnszone' && this.$route.params?.id) return { path: item.path, query: dnsZoneListQuery(this.$route.query) }
      if (item.path === '/ipv4subnets' && this.$route.params?.id) return { path: item.path, query: ipv4SubnetListQuery(this.$route.query) }
      if (item.path === '/guestvlans' && guestVlanRoute(this.$route) && this.$route.params?.id) {
        return { path: item.path, query: guestVlanListQuery(this.$route.query) }
      }
      if (item.path === '/vpncustomergateway' && this.$route.params?.id) {
        const { tab, ...query } = this.$route.query
        return { path: item.path, query }
      }
      if (item.path === '/publicip' && this.$route.meta?.name === 'publicip' && this.$route.params?.id) return publicIpListLocation(this.$route, this.$store.getters)
      if (item.path === '/vnfapp' && this.$route.meta?.name === 'vnfapp' && this.$route.params?.id) return vnfListLocation(this.$route, this.$store.getters)
      if (item.path === '/iso' && this.$route.meta?.name === 'iso' && this.$route.params?.id) return isoListLocation(this.$route, this.$store.getters)
      if (item.vpcParent) return { path: item.path, query: { tab: 'pgw' } }
      if (item.path === '/template' && this.$route.meta?.name === 'template' && this.$route.params?.id) return templateListLocation(this.$route, this.$store.getters)
      return item.path === '/guestnetwork' && this.$route.meta?.name === 'guestnetwork' && this.$route.params?.id ? networkListLocation(this.$route) : { path: item.path === '' ? '/' : item.path }
    },
    getBreadcrumb () {
      this.name = this.$route.name
      this.breadList = []
      this.$route.matched.forEach((item, idx) => {
        const parent = this.$route.matched[idx - 1]
        if (item && parent && parent.name !== 'index' && !item.path.endsWith(':id') && !item.path.endsWith(':id(.*)')) {
          this.breadList.pop()
        }
        this.breadList.push(item)
      })
      if (this.$route.meta?.name === 'privategw' && this.resource.vpcid) {
        this.breadList = this.breadList.filter(item => item.path !== '/privategw')
        this.breadList.splice(this.breadList.length - 1, 0, { name: 'vpc-parent', path: '/vpc/' + this.resource.vpcid, vpcParent: true, meta: { title: 'label.vpc' } })
      }
    },
    resetToMainView () {
      this.$store.dispatch('SetProject', {})
      this.$store.dispatch('ToggleTheme', 'light')
    }
  }
}
</script>

<style>
.ant-breadcrumb {
  vertical-align: text-bottom;
}

.ant-breadcrumb .anticon {
  vertical-align: text-bottom;
}
.kms-breadcrumb-name,
.event-breadcrumb-title {
  white-space: normal;
  overflow-wrap: anywhere;
}
.kms-parent-link { margin-inline-end: 8px; }
</style>
