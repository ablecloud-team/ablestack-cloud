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
  <section>
    <NetworkRulesToolbar :can-add="canChange" :loading="loading" @add="open = true" @refresh="refresh">
      <template #add-label>{{ $t('label.change.bgp.peers') }}</template>
    </NetworkRulesToolbar>
    <a-alert v-if="failed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')" />
    <a-table
      v-else
      size="small"
      :columns="columns"
      :data-source="peers"
      :row-key="peer => peer.id"
      :scroll="{ x: 480 }"
      :pagination="false"
      :loading="loading">
      <template #emptyText>{{ $t('message.guest.feature.empty.bgp') }}</template>
    </a-table>
    <MoldDialog
      v-if="open && canChange"
      :visible="open"
      :title="$t('label.change.bgp.peers')"
      size="lg"
      :footer="null"
      @cancel="open = false">
      <ChangeBgpPeerForNetwork :resource="resource" @close-action="open = false" @refresh-data="refresh" />
    </MoldDialog>
  </section>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import ChangeBgpPeerForNetwork from '@/views/network/ChangeBgpPeerForNetwork'
import { canWriteGuestFeature } from '@/utils/conditionalGuestNetwork'

export default {
  components: { MoldDialog, NetworkRulesToolbar, ChangeBgpPeerForNetwork },
  props: { resource: { type: Object, required: true }, loading: Boolean },
  inject: ['parentFetchData', 'resourceActionReady'],
  data () {
    return { open: false, failed: false }
  },
  computed: {
    peers () {
      return (this.resource.bgppeers || []).filter(peer => peer?.id).map(({ id, asnumber, ipaddress, ip6address, state }) => ({ id, asnumber, ipaddress, ip6address, state }))
    },
    columns () {
      return ['asnumber', 'ipaddress', 'ip6address', 'state'].map(dataIndex => ({ dataIndex, title: this.$t('label.' + dataIndex) }))
    },
    canChange () {
      return this.resourceActionReady() && canWriteGuestFeature('bgp.peers', 'changeBgpPeersForNetwork', this.resource, this.$store.getters)
    }
  },
  methods: {
    async refresh () {
      this.failed = false
      try {
        await this.parentFetchData()
      } catch (_) {
        this.failed = true
      }
    }
  }
}
</script>
