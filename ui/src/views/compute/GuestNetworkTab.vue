<!-- Licensed to the Apache Software Foundation (ASF) under one -->
<!-- or more contributor license agreements.  See the NOTICE file -->
<!-- distributed with this work for additional information -->
<!-- regarding copyright ownership.  The ASF licenses this file -->
<!-- to you under the Apache License, Version 2.0 (the -->
<!-- "License"); you may not use this file except in compliance -->
<!-- with the License.  You may obtain a copy of the License at -->
<!-- -->
<!--   http://www.apache.org/licenses/LICENSE-2.0 -->
<!-- -->
<!-- Unless required by applicable law or agreed to in writing, -->
<!-- software distributed under the License is distributed on an -->
<!-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY -->
<!-- KIND, either express or implied.  See the License for the -->
<!-- specific language governing permissions and limitations -->
<!-- under the License. -->

<template>
  <a-spin :spinning="loading">
    <div class="guest-network-tab">
    <div class="guest-network-header">
      <a-alert
        type="info"
        show-icon
        :message="$t('message.guest.network.persisted.snapshot')" />
      <a-space>
        <a-button
          class="refresh-button"
          :loading="loading"
          @click="fetchData">
          <template #icon><reload-outlined /></template>
          {{ $t('label.refresh') }}
        </a-button>
        <a-button
          type="primary"
          :loading="recollecting"
          :disabled="resource.state !== 'Running'"
          @click="requestRecollection">
          {{ $t('label.guest.network.recollect.now') }}
        </a-button>
      </a-space>
    </div>

    <a-alert
      v-if="showStatusAlert"
      class="status-alert"
      show-icon
      :type="statusAlertType"
      :message="statusMessage"
      :description="state.errormessage" />

    <a-descriptions
      bordered
      size="small"
      :column="device === 'mobile' ? 1 : 2">
      <a-descriptions-item :label="$t('label.guest.network.collection.status')">
        <a-tag :color="statusColor">{{ state.status || 'NOT_COLLECTED' }}</a-tag>
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.qga.version')">
        {{ state.qgaversion || '-' }}
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.guest.network.observed')">
        {{ state.observed ? $toLocaleDate(state.observed) : '-' }}
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.guest.network.last.success')">
        {{ state.lastsuccess ? $toLocaleDate(state.lastsuccess) : '-' }}
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.guest.network.interface.count')">
        {{ interfaces.length }}
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.guest.network.schema.version')">
        {{ state.schemaversion || '-' }}
      </a-descriptions-item>
    </a-descriptions>

    <div class="guest-network-section-title">
      <strong>{{ $t('label.guest.network.interfaces.addresses') }}</strong>
      <a-tooltip :title="$t('message.guest.network.interfaces.addresses.help')">
        <span
          class="section-info-icon"
          role="img"
          tabindex="0"
          :aria-label="$t('message.guest.network.interfaces.addresses.help')">
          <info-circle-outlined />
        </span>
      </a-tooltip>
    </div>
    <a-empty
      v-if="!interfaces.length"
      :description="$t('message.guest.network.no.interfaces')" />
    <template v-else>
      <a-card
        v-for="networkInterface in interfaces"
        :key="networkInterface.name + (networkInterface.hardwareaddress || '')"
        size="small"
        class="interface-card">
        <template #title>
          <span>{{ networkInterface.name || $t('label.interface') }}</span>
          <a-tag v-if="networkInterface.loopback" class="interface-tag">loopback</a-tag>
        </template>
        <a-descriptions
          size="small"
          :column="device === 'mobile' ? 1 : 2">
          <a-descriptions-item :label="$t('label.hardware.address')">
            <copy-label
              v-if="networkInterface.hardwareaddress"
              :label="networkInterface.hardwareaddress" />
            <span v-else>-</span>
          </a-descriptions-item>
          <a-descriptions-item :label="$t('label.cloud.nic')">
            <copy-label
              v-if="networkInterface.cloudnicid"
              :label="networkInterface.cloudnicid" />
            <span v-else>-</span>
          </a-descriptions-item>
        </a-descriptions>
        <div class="address-list">
          <span class="address-list-label">{{ $t('label.addresses') }}</span>
          <div class="address-items">
            <template v-if="networkInterface.addresses && networkInterface.addresses.length">
              <div
                v-for="address in sortedAddresses(networkInterface)"
                :key="address.family + address.address + address.prefix"
                class="address-item">
                <a-tag
                  :color="String(address.family).toLowerCase() === 'ipv6' ? 'purple' : 'green'"
                  class="address-family-tag">
                  {{ address.family }}
                </a-tag>
                <span class="address-value">
                  <copy-label :label="formatAddress(address)" />
                </span>
                <span class="address-role-group">
                  <a-tooltip :title="addressRoleTooltip(address)">
                    <a-tag
                      class="address-role-tag"
                      :color="addressRoleColor(address.role)">
                      {{ addressRoleLabel(address.role) }}
                    </a-tag>
                  </a-tooltip>
                  <a-tag
                    v-if="address.representative"
                    class="address-role-tag"
                    color="blue">
                    {{ $t('label.representative') }}
                  </a-tag>
                  <a-tag
                    v-if="cloudAddressRole(networkInterface, address)"
                    class="address-role-tag"
                    :color="cloudAddressRole(networkInterface, address) === 'PRIMARY' ? 'cyan' : 'default'">
                    {{ cloudAddressRole(networkInterface, address) === 'PRIMARY'
                      ? $t('label.cloud.primary.ip')
                      : $t('label.cloud.secondary.ip') }}
                  </a-tag>
                </span>
              </div>
            </template>
            <span v-else>-</span>
          </div>
        </div>
      </a-card>
    </template>

    <div class="guest-network-section-title">
      <strong>{{ $t('label.guest.network.routes') }}</strong>
      <a-tooltip :title="$t('message.guest.network.routes.help')">
        <span
          class="section-info-icon"
          role="img"
          tabindex="0"
          :aria-label="$t('message.guest.network.routes.help')">
          <info-circle-outlined />
        </span>
      </a-tooltip>
    </div>
    <a-alert
      v-if="routeSection && !['OK', 'EMPTY'].includes(routeSection.status)"
      class="section-status-alert"
      show-icon
      :type="routeSection.status === 'UNAVAILABLE' ? 'error' : 'warning'"
      :message="$t('message.guest.network.route.status', { status: routeSection.status })"
      :description="routeSection.details" />
    <div class="route-toolbar">
      <a-input-search
        v-model:value="routeSearch"
        allow-clear
        :placeholder="$t('label.guest.network.route.search')"
        class="route-search" />
      <a-select
        v-model:value="routeFamily"
        class="route-family"
        :options="routeFamilyOptions" />
      <span class="route-count">
        {{ $t('label.guest.network.route.count', { count: filteredRoutes.length }) }}
      </span>
    </div>
    <a-table
      v-if="filteredRoutes.length"
      size="small"
      :columns="routeColumns"
      :dataSource="filteredRoutes"
      :pagination="{ pageSize: 10, hideOnSinglePage: true }"
      :rowKey="routeKey"
      :scroll="{ x: 1050 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'default'">
          <a-tag v-if="record.default" color="blue">DEFAULT</a-tag>
        </template>
        <template v-else-if="column.key === 'family'">
          <a-tag :color="record.family === 'IPv6' ? 'purple' : 'green'">
            {{ record.family }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'destination'">
          <copy-label :label="formatRouteDestination(record)" />
        </template>
        <template v-else-if="column.key === 'gateway'">
          <copy-label v-if="record.gateway" :label="record.gateway" />
          <span v-else>{{ $t('label.on.link') }}</span>
        </template>
      </template>
    </a-table>
    <a-empty
      v-else
      :description="$t('message.guest.network.no.routes')" />

    <div class="guest-network-section-title">
      <strong>{{ $t('label.guest.network.dns') }}</strong>
      <a-tooltip :title="$t('message.guest.network.dns.help')">
        <span
          class="section-info-icon"
          role="img"
          tabindex="0"
          :aria-label="$t('message.guest.network.dns.help')">
          <info-circle-outlined />
        </span>
      </a-tooltip>
    </div>
    <a-alert
      v-if="dnsSection && !['OK', 'EMPTY'].includes(dnsSection.status)"
      class="section-status-alert"
      show-icon
      :type="dnsSection.status === 'UNAVAILABLE' ? 'error' : 'warning'"
      :message="$t('message.guest.network.dns.status', { status: dnsSection.status })"
      :description="dnsSection.details" />
    <a-descriptions
      bordered
      size="small"
      :column="device === 'mobile' ? 1 : 3"
      class="dns-summary">
      <a-descriptions-item :label="$t('label.dns.source')">
        {{ dns.source || '-' }}
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.dns.servers')">
        {{ dns.servers ? dns.servers.length : 0 }}
      </a-descriptions-item>
      <a-descriptions-item :label="$t('label.upstream.dns')">
        <a-tag :color="dns.upstreamserversknown ? 'green' : 'orange'">
          {{ dns.upstreamserversknown
            ? $t('label.upstream.dns.known')
            : $t('label.upstream.dns.unknown') }}
        </a-tag>
      </a-descriptions-item>
    </a-descriptions>
    <a-alert
      v-if="dnsConfigurations.length && !dns.upstreamserversknown"
      class="dns-upstream-alert"
      type="info"
      show-icon
      :message="$t('message.guest.network.upstream.unknown')" />
    <a-table
      v-if="dnsConfigurations.length"
      size="small"
      :columns="dnsColumns"
      :dataSource="dnsConfigurations"
      :pagination="false"
      :rowKey="dnsConfigKey"
      :scroll="{ x: 850 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'scope'">
          <a-tag :color="record.global ? 'blue' : 'default'">
            {{ record.global ? $t('label.dns.global') : (record.interfacename || '-') }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'servers'">
          <template v-if="record.servers && record.servers.length">
            <a-tag
              v-for="server in record.servers"
              :key="server.family + server.address"
              :color="server.localstub ? 'orange' : (server.family === 'IPv6' ? 'purple' : 'green')"
              class="dns-tag">
              {{ server.family }} {{ server.address }}
              <span v-if="server.localstub">({{ $t('label.local.stub') }})</span>
            </a-tag>
          </template>
          <span v-else>-</span>
        </template>
        <template v-else-if="column.key === 'domains'">
          <template v-if="record.domains && record.domains.length">
            <a-tag
              v-for="domain in record.domains"
              :key="domain.domain + domain.routingonly"
              :color="domain.routingonly ? 'cyan' : 'default'"
              class="dns-tag">
              {{ domain.routingonly ? '~' : '' }}{{ domain.domain }}
            </a-tag>
          </template>
          <span v-else>-</span>
        </template>
      </template>
    </a-table>
      <a-empty
        v-else
        :description="$t('message.guest.network.no.dns')" />
    </div>
  </a-spin>
</template>

<script>
import { InfoCircleOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import CopyLabel from '@/components/widgets/CopyLabel'

export default {
  name: 'GuestNetworkTab',
  components: {
    CopyLabel,
    InfoCircleOutlined,
    ReloadOutlined
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      recollecting: false,
      pollTimer: null,
      state: {
        status: 'NOT_COLLECTED',
        interfaces: [],
        routes: [],
        dns: {
          configurations: []
        }
      },
      routeSearch: '',
      routeFamily: 'all'
    }
  },
  computed: {
    interfaces () {
      return this.state.interfaces || []
    },
    routes () {
      return this.state.routes || []
    },
    routeSection () {
      return (this.state.sections || []).find(section => section.name === 'routes')
    },
    dns () {
      return this.state.dns || { configurations: [] }
    },
    dnsConfigurations () {
      return this.dns.configurations || []
    },
    dnsSection () {
      return (this.state.sections || []).find(section => section.name === 'dns')
    },
    routeFamilyOptions () {
      return [
        { value: 'all', label: this.$t('label.all') },
        { value: 'IPv4', label: 'IPv4' },
        { value: 'IPv6', label: 'IPv6' },
        { value: 'default', label: this.$t('label.default.routes') }
      ]
    },
    filteredRoutes () {
      const query = this.routeSearch.trim().toLowerCase()
      return this.routes.filter(route => {
        const familyMatches = this.routeFamily === 'all' ||
          (this.routeFamily === 'default' ? route.default : route.family === this.routeFamily)
        if (!familyMatches) {
          return false
        }
        if (!query) {
          return true
        }
        return [
          route.family,
          this.formatRouteDestination(route),
          route.gateway,
          route.interfacename,
          route.metric,
          route.table,
          route.protocol,
          route.scope
        ].some(value => String(value || '').toLowerCase().includes(query))
      })
    },
    routeColumns () {
      return [
        { title: this.$t('label.default'), key: 'default', width: 90, sorter: (a, b) => Number(b.default) - Number(a.default) },
        { title: this.$t('label.family'), dataIndex: 'family', key: 'family', width: 85, sorter: (a, b) => String(a.family).localeCompare(String(b.family)) },
        { title: this.$t('label.destination'), key: 'destination', width: 220, sorter: (a, b) => this.formatRouteDestination(a).localeCompare(this.formatRouteDestination(b)) },
        { title: this.$t('label.gateway'), dataIndex: 'gateway', key: 'gateway', width: 180, sorter: (a, b) => String(a.gateway || '').localeCompare(String(b.gateway || '')) },
        { title: this.$t('label.interface'), dataIndex: 'interfacename', key: 'interfacename', width: 130, sorter: (a, b) => String(a.interfacename || '').localeCompare(String(b.interfacename || '')) },
        { title: this.$t('label.metric'), dataIndex: 'metric', key: 'metric', width: 90, sorter: (a, b) => Number(a.metric || 0) - Number(b.metric || 0) },
        { title: this.$t('label.table'), dataIndex: 'table', key: 'table', width: 100, sorter: (a, b) => String(a.table || '').localeCompare(String(b.table || '')) },
        { title: this.$t('label.protocol'), dataIndex: 'protocol', key: 'protocol', width: 110 },
        { title: this.$t('label.scope'), dataIndex: 'scope', key: 'scope', width: 100 }
      ]
    },
    dnsColumns () {
      return [
        { title: this.$t('label.dns.scope'), key: 'scope', width: 140 },
        { title: this.$t('label.dns.source'), dataIndex: 'source', key: 'source', width: 130 },
        { title: this.$t('label.dns.servers'), key: 'servers', width: 300 },
        { title: this.$t('label.search.domains'), key: 'domains', width: 280 }
      ]
    },
    showStatusAlert () {
      return this.state.status && this.state.status !== 'OK'
    },
    statusColor () {
      const colors = {
        OK: 'green',
        PARTIAL: 'orange',
        STALE: 'gold',
        STOPPED: 'default',
        UNSUPPORTED: 'red',
        UNAVAILABLE: 'red',
        NOT_COLLECTED: 'default'
      }
      return colors[this.state.status] || 'default'
    },
    statusAlertType () {
      return ['UNSUPPORTED', 'UNAVAILABLE'].includes(this.state.status) ? 'error' : 'warning'
    },
    statusMessage () {
      const key = 'message.guest.network.status.' + String(this.state.status || 'not_collected').toLowerCase()
      return this.$t(key)
    }
  },
  watch: {
    'resource.id' () {
      this.fetchData()
    }
  },
  created () {
    this.fetchData()
  },
  beforeUnmount () {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer)
    }
  },
  methods: {
    fetchData () {
      if (!this.resource.id) {
        return
      }
      this.loading = true
      getAPI('getVirtualMachineGuestNetworkState', {
        virtualmachineid: this.resource.id
      }).then(json => {
        this.state = json.getvirtualmachineguestnetworkstateresponse.guestnetworkstate || {
          status: 'NOT_COLLECTED',
          interfaces: [],
          routes: [],
          dns: {
            configurations: []
          }
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    requestRecollection () {
      if (!this.resource.id || this.recollecting) {
        return
      }
      const previousObserved = this.state.observed
      this.recollecting = true
      postAPI('refreshVirtualMachineGuestNetworkState', {
        virtualmachineid: this.resource.id,
        sections: 'interfaces,routes,dns,readiness'
      }).then(json => {
        const response = json.refreshvirtualmachineguestnetworkstateresponse || {}
        if (!response.guestnetworkrefresh || !response.guestnetworkrefresh.accepted) {
          this.$message.info(this.$t('message.guest.network.recollect.pending'))
        }
        // Allow one scheduler interval plus a bounded collection cycle.
        this.pollRecollection(previousObserved, Date.now() + 120000, 2000)
      }).catch(error => {
        this.recollecting = false
        this.$notifyError(error)
      })
    },
    pollRecollection (previousObserved, deadline, delay) {
      this.pollTimer = setTimeout(() => {
        getAPI('getVirtualMachineGuestNetworkState', {
          virtualmachineid: this.resource.id
        }).then(json => {
          const next = json.getvirtualmachineguestnetworkstateresponse.guestnetworkstate
          if (next) {
            this.state = next
          }
          if ((next && next.observed && next.observed !== previousObserved) ||
              Date.now() >= deadline) {
            if (!next?.observed || next.observed === previousObserved) {
              this.$message.info(this.$t('message.guest.network.recollect.pending'))
            }
            this.recollecting = false
            this.pollTimer = null
            return
          }
          this.pollRecollection(previousObserved, deadline, Math.min(delay * 1.5, 5000))
        }).catch(error => {
          this.recollecting = false
          this.pollTimer = null
          this.$notifyError(error)
        })
      }, delay)
    },
    formatAddress (address) {
      return address.prefix === null || address.prefix === undefined
        ? address.address
        : address.address + '/' + address.prefix
    },
    normalizedAddress (address) {
      return String(address || '').split('/')[0].toLowerCase()
    },
    cloudNic (networkInterface) {
      const nics = this.resource.nic || []
      if (networkInterface.cloudnicid) {
        const match = nics.find(nic => nic.id === networkInterface.cloudnicid)
        if (match) {
          return match
        }
      }
      const mac = String(networkInterface.hardwareaddress || '')
        .replace(/-/g, ':').toLowerCase()
      return nics.find(nic =>
        String(nic.macaddress || '').replace(/-/g, ':').toLowerCase() === mac)
    },
    cloudAddressRole (networkInterface, address) {
      const nic = this.cloudNic(networkInterface)
      if (!nic) {
        return ''
      }
      const value = this.normalizedAddress(address.address)
      if ([nic.ipaddress, nic.ip6address].some(candidate =>
        this.normalizedAddress(candidate) === value)) {
        return 'PRIMARY'
      }
      const secondary = Array.isArray(nic.secondaryip)
        ? nic.secondaryip
        : (nic.secondaryip ? [nic.secondaryip] : [])
      return secondary.some(item =>
        this.normalizedAddress(item.ipaddress || item.ip6address || item) === value)
        ? 'SECONDARY'
        : ''
    },
    addressRoleLabel (role) {
      if (role === 'PRIMARY') {
        return this.$t('label.primary.ip')
      }
      if (role === 'SECONDARY') {
        return this.$t('label.secondary.ip')
      }
      return this.$t('label.ip.role.unknown')
    },
    addressRoleColor (role) {
      if (role === 'PRIMARY') {
        return 'blue'
      }
      if (role === 'SECONDARY') {
        return 'gold'
      }
      return 'default'
    },
    addressRoleTooltip (address) {
      const parts = [
        this.addressRoleLabel(address.role),
        address.rolesource || this.$t('message.ip.role.unavailable'),
        address.scope
      ]
      return parts.filter(Boolean).join(' · ')
    },
    sortedAddresses (networkInterface) {
      const rank = address => {
        if (address.representative) return 0
        if (address.role === 'PRIMARY') return 1
        if (address.role === 'SECONDARY') return 2
        return 3
      }
      return [...(networkInterface.addresses || [])].sort((a, b) => {
        const role = rank(a) - rank(b)
        if (role !== 0) return role
        const family = String(a.family).localeCompare(String(b.family))
        if (family !== 0) return family
        return String(a.address).localeCompare(String(b.address))
      })
    },
    formatRouteDestination (route) {
      return route.prefix === null || route.prefix === undefined
        ? route.destination
        : route.destination + '/' + route.prefix
    },
    routeKey (route, index) {
      return [
        route.family,
        route.destination,
        route.prefix,
        route.gateway,
        route.interfacename,
        route.table,
        route.metric,
        index
      ].join('|')
    },
    dnsConfigKey (config, index) {
      return [
        config.global,
        config.interfacename,
        config.source,
        index
      ].join('|')
    }
  }
}
</script>

<style scoped lang="less">
.guest-network-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.guest-network-header :deep(.ant-alert) {
  flex: 1;
}

.refresh-button {
  flex: none;
}

.status-alert {
  margin-bottom: 16px;
}

.guest-network-section-title {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 24px 0 12px;
  line-height: 22px;
}

.section-info-icon {
  display: inline-flex;
  align-items: center;
  color: inherit;
  cursor: help;
  opacity: 0.65;
}

.section-info-icon:focus-visible {
  border-radius: 50%;
  outline: 2px solid currentColor;
  outline-offset: 2px;
}

.interface-card {
  margin-bottom: 12px;
}

.interface-tag {
  margin-left: 8px;
}

.address-list {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  column-gap: 12px;
  align-items: start;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}

.address-list-label {
  padding-top: 4px;
  color: inherit;
  opacity: 0.65;
}

.address-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.address-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 26px;
  padding: 2px 6px;
  border: 1px solid rgba(128, 128, 128, 0.35);
  border-radius: 4px;
  background: transparent;
}

.address-value {
  white-space: nowrap;
}

.address-role-group {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: 2px;
}

.address-family-tag,
.address-role-tag {
  margin: 0;
}

.address-role-tag {
  font-size: 11px;
  line-height: 18px;
}

.section-status-alert {
  align-items: flex-start;
  margin-bottom: 12px;
  padding: 9px 12px;
}

.section-status-alert :deep(.ant-alert-icon) {
  flex: none;
  margin-right: 8px;
  font-size: 16px;
  line-height: 20px;
}

.section-status-alert :deep(.ant-alert-content) {
  min-width: 0;
}

.section-status-alert :deep(.ant-alert-message) {
  margin-bottom: 2px;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
}

.section-status-alert :deep(.ant-alert-description) {
  font-size: 12px;
  line-height: 18px;
  word-break: break-word;
}

.route-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.route-search {
  width: 320px;
}

.route-family {
  width: 150px;
}

.route-count {
  color: inherit;
  opacity: 0.65;
}

.dns-summary {
  margin-bottom: 12px;
}

.dns-upstream-alert {
  margin-bottom: 12px;
}

.dns-tag {
  margin-bottom: 4px;
}

@media (max-width: 576px) {
  .address-list {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 6px;
  }

  .address-list-label {
    padding-top: 0;
  }
}
</style>
