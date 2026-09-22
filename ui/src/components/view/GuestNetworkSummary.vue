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
  <div class="guest-network-summary">
    <div class="summary-line summary-line-compact">
      <span v-if="primaryAddress" class="primary-address">
        <copy-label :label="primaryAddress.address" :tooltip="primaryAddressTooltip" />
      </span>
      <a-tooltip v-else :title="showStatus ? statusTooltip : ''">
        <span>-</span>
      </a-tooltip>
      <a-popover
        v-if="remainingAddressCount > 0"
        overlayClassName="guest-network-summary-popover"
        placement="bottomLeft"
        trigger="click">
        <template #content>
          <div class="address-popover">
            <div class="address-popover-header">
              <strong>{{ $t('label.guest.ip') }} {{ allAddresses.length }}</strong>
              <copy-label
                :label="$t('label.copy.all')"
                :copyValue="allAddressCopyValue" />
            </div>
            <div class="address-popover-metadata">
              <span v-if="isCloudFallback">{{ $t('label.cloud.ip') }}</span>
              <span v-if="showStatus" :title="statusTooltip">{{ statusLabel }}</span>
              <span v-if="summary.observed">{{ $t('label.guest.network.observed') }}: {{ $toLocaleDate(summary.observed) }}</span>
            </div>
            <div
              v-for="item in allAddresses"
              :key="item.family + item.address"
              class="address-popover-row">
              <a-tag :color="item.family === 'IPv6' ? 'purple' : 'green'">
                {{ item.family }}
              </a-tag>
              <copy-label :label="item.address" />
              <a-tag v-if="item.representative" class="popover-role" color="blue">
                {{ $t('label.representative') }}
              </a-tag>
            </div>
          </div>
        </template>
        <a-button
          class="summary-more"
          size="small"
          :aria-label="'+' + remainingAddressCount + ' ' + $t('label.more')">
          +{{ remainingAddressCount }}
        </a-button>
      </a-popover>

    </div>
  </div>
</template>

<script>
import CopyLabel from '@/components/widgets/CopyLabel'

export default {
  name: 'GuestNetworkSummary',
  components: {
    CopyLabel
  },
  props: {
    cloudAddress: {
      type: String,
      default: ''
    },
    cloudNics: {
      type: Array,
      default: () => []
    },
    summary: {
      type: Object,
      default: () => ({})
    }
  },
  computed: {
    allAddresses () {
      const addresses = [
        ...(this.summary.ipv4addresses || []).map(address => ({ family: 'IPv4', address })),
        ...(this.summary.ipv6addresses || []).map(address => ({ family: 'IPv6', address }))
      ]
      const seen = new Set()
      return addresses.filter(item => {
        const key = this.normalizedAddress(item.address)
        if (!key || seen.has(key)) {
          return false
        }
        seen.add(key)
        return true
      }).map(item => ({
        ...item,
        representative: this.primaryAddress &&
          this.normalizedAddress(item.address) ===
            this.normalizedAddress(this.primaryAddress.address)
      }))
    },
    cloudPrimaryAddress () {
      const active = (this.cloudNics || []).filter(nic => nic.enabled !== false)
      const defaultNic = active.find(nic => nic.isdefault) || active[0]
      if (defaultNic) {
        return defaultNic.ipaddress || defaultNic.ip6address || ''
      }
      return String(this.cloudAddress || '').split(',')[0].trim()
    },
    primaryAddress () {
      if (this.summary.representativeaddress) {
        const prefix = this.summary.representativeprefix
        return {
          family: this.summary.representativefamily || 'IPv4',
          address: prefix === null || prefix === undefined
            ? this.summary.representativeaddress
            : this.summary.representativeaddress + '/' + prefix,
          source: 'QGA'
        }
      }
      if (this.cloudPrimaryAddress) {
        return {
          family: this.cloudPrimaryAddress.includes(':') ? 'IPv6' : 'IPv4',
          address: this.cloudPrimaryAddress,
          source: 'CLOUD'
        }
      }
      return null
    },
    isCloudFallback () {
      return this.primaryAddress?.source === 'CLOUD'
    },
    remainingAddressCount () {
      if (!this.primaryAddress) {
        return this.allAddresses.length
      }
      const included = this.allAddresses.some(item =>
        this.normalizedAddress(item.address) ===
          this.normalizedAddress(this.primaryAddress.address))
      return Math.max(0, this.allAddresses.length - (included ? 1 : 0))
    },
    allAddressCopyValue () {
      return this.allAddresses.map(item => item.address).join('\n')
    },
    showStatus () {
      return Boolean(this.summary.status)
    },
    statusLabel () {
      const key = 'label.guest.network.status.' + String(this.summary.status || '').toLowerCase()
      return this.$t(key)
    },
    statusTooltip () {
      const key = 'message.guest.network.status.' + String(this.summary.status || '').toLowerCase()
      const message = this.$t(key)
      if (!this.summary.observed) {
        return message
      }
      return message + ' · ' + this.$t('label.guest.network.observed') + ': ' +
        this.$toLocaleDate(this.summary.observed)
    },
    primaryAddressTooltip () {
      const source = this.primaryAddress.source === 'QGA'
        ? this.$t('message.representative.ip.qga')
        : this.$t('message.representative.ip.cloud.fallback')
      return [this.primaryAddress.address, source, this.showStatus ? this.statusTooltip : '']
        .filter(Boolean).join(' · ')
    }
  },
  methods: {
    normalizedAddress (address) {
      return String(address || '').split('/')[0].toLowerCase()
    }
  }
}
</script>

<style scoped lang="less">
.guest-network-summary {
  min-width: 0;
}

.summary-line {
  display: flex;
  align-items: center;
  min-height: 24px;
}

.summary-line-compact {
  gap: 6px;
  white-space: nowrap;
}

.primary-address {
  color: inherit;
  min-width: 0;
  max-width: 180px;

  :deep(a) {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.popover-role {
  margin: 0;
  padding: 0 5px;
  font-size: 10px;
  line-height: 17px;
  border-radius: 9px;
}

.summary-more {
  flex: none;
  height: 20px;
  padding: 0 7px;
  color: #096dd9;
  font-size: 11px;
  line-height: 18px;
  border-radius: 10px;
}

.address-popover {
  width: max-content;
  max-width: min(520px, calc(100vw - 48px));
  max-height: 280px;
  overflow-y: auto;
}

.address-popover-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-bottom: 8px;
}

.address-popover-header,
.address-popover-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.address-popover-header {
  justify-content: space-between;
  margin-bottom: 8px;
}

.address-popover-row {
  margin: 5px 0;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.address-popover-row .ant-tag {
  min-width: 42px;
  margin: 0;
  font-size: 10px;
  text-align: center;
}
</style>
