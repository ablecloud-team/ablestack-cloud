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
  <section class="storage-table-section storage-network-readiness">
    <h4>{{ $t('label.storage.service.network.readiness') }}</h4>
    <a-space wrap>
      <a-tag :color="color" role="status">{{ $t('label.storage.service.network.status.' + status) }}</a-tag>
      <a-button :loading="loading" @click="$emit('refresh')"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="status !== 'OK'" :type="status === 'ERROR' ? 'error' : 'warning'" show-icon :message="summary" :description="reasons || undefined" />
    <a-descriptions bordered size="small" :column="1">
      <a-descriptions-item v-for="row in rows" :key="row.key" :label="$t('label.storage.service.network.' + row.key)">{{ row.value }}</a-descriptions-item>
    </a-descriptions>
  </section>
</template>
<script>
import { ReloadOutlined } from '@ant-design/icons-vue'
import { storageNetworkObservation, storageNetworkStatus, storageNetworkObservedTime, networkMismatchCodes } from '@/utils/storageNetworkReadiness'

export default {
  name: 'StorageNetworkStatus',
  components: { ReloadOutlined },
  props: {
    readiness: { type: Object, default: null },
    stale: { type: Boolean, default: false },
    readFailed: { type: Boolean, default: false },
    loading: { type: Boolean, default: false }
  },
  emits: ['refresh'],
  computed: {
    observation () { return storageNetworkObservation(this.readiness) },
    status () { return storageNetworkStatus(this.readiness, { stale: this.stale, readFailed: this.readFailed }) },
    color () { return { OK: 'green', ERROR: 'red', UNKNOWN: 'default' }[this.status] },
    summary () {
      if (this.status === 'OK') return ''
      return this.$t(this.readFailed ? 'message.storage.service.network.read.failed'
        : this.stale ? 'message.storage.service.network.stale'
          : 'message.storage.service.network.' + this.status.toLowerCase())
    },
    reasons () {
      return (this.observation?.mismatches || []).map(code => this.$t('message.storage.service.network.reason.' +
        (networkMismatchCodes.includes(code) ? code : 'UNKNOWN'))).join('; ')
    },
    rows () {
      const value = this.observation
      const expected = value?.expected || {}
      const actual = value?.actual || {}
      const expectedKnown = value && expected.mode !== 'UNKNOWN'
      const actualKnown = value && actual.interfaceName !== null && value.status !== 'UNKNOWN' && !this.readFailed
      const text = item => item === null ? this.$t('label.none') : item || '—'
      const observedText = item => typeof item === 'string' && item.length ? item : '—'
      const list = items => items.length ? items.join(', ') : this.$t('label.none')
      const bool = item => typeof item === 'boolean' ? this.$t(item ? 'label.yes' : 'label.no') : '—'
      return [
        { key: 'observed', value: storageNetworkObservedTime(this.readiness) },
        { key: 'expected.mode', value: expectedKnown ? expected.mode : '—' },
        { key: 'actual.mode', value: actualKnown && actual.mode !== 'UNKNOWN' ? actual.mode : '—' },
        { key: 'expected.mac', value: expectedKnown ? text(expected.macAddress) : '—' },
        { key: 'interface', value: actualKnown ? observedText(actual.interfaceName) : '—' },
        { key: 'actual.mac', value: actualKnown ? observedText(actual.macAddress) : '—' },
        { key: 'expected.ip', value: expectedKnown ? text(expected.ipAddress) : '—' },
        { key: 'expected.cidr', value: expectedKnown ? text(expected.cidr) : '—' },
        { key: 'actual.addresses', value: actualKnown ? list(actual.addresses.map(address => address.ipAddress + '/' + address.prefixLength)) : '—' },
        { key: 'expected.gateway', value: expectedKnown ? text(expected.gateway) : '—' },
        { key: 'actual.routes', value: actualKnown ? list(actual.defaultRoutes.map(route => 'default' + (route.gateway === null ? '' : ' via ' + route.gateway) + ' dev ' + route.interfaceName)) : '—' },
        { key: 'expected.dns', value: expectedKnown ? list(expected.dns) : '—' },
        { key: 'actual.dns', value: actualKnown ? list(actual.dns) : '—' },
        { key: 'dhcp.unit', value: actualKnown ? observedText(actual.dhcpUnit.name) : '—' },
        { key: 'dhcp.active', value: actualKnown ? bool(actual.dhcpUnit.active) : '—' },
        { key: 'dhcp.masked', value: actualKnown ? bool(actual.dhcpUnit.masked) : '—' },
        { key: 'dhcp.clients', value: actualKnown && actual.dhcpProcessCount !== null ? actual.dhcpProcessCount : '—' }
      ]
    }
  }
}
</script>
