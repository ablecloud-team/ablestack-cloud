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
    <div class="detail-tab-toolbar">
      <a-button v-if="canChange(remoteAction)" :danger="!!remoteAccessVpn" type="primary" :disabled="locked" @click="dialog = remoteAction">
        {{ $t(remoteAccessVpn ? 'label.disable.vpn' : 'label.enable.vpn') }}
      </a-button>
      <a-button v-if="canChange(gatewayAction)" :danger="!!vpnGateway" :disabled="locked" @click="dialog = gatewayAction">
        {{ $t(vpnGateway ? 'label.delete.vpn.gateway' : 'label.add.vpn.gateway') }}
      </a-button>
      <a-button :loading="loading" :disabled="networkMutationBusy" @click="refresh"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <router-link v-if="remoteAccessVpn && can('listVpnUsers')" to="/vpnuser">{{ $t('label.manage.vpn.user') }}</router-link>
    </div>

    <NetworkMutationStatus v-if="!dialog && networkMutationBlocked" :operation="networkMutation" @recheck="recheckNetworkMutation" />
    <a-alert v-if="readFailed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')" />
    <a-descriptions v-else-if="remoteAccessVpn" size="small" :column="1">
      <a-descriptions-item :label="$t('label.ipaddress')">{{ remoteAccessVpn.publicip }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.status')">{{ remoteAccessVpn.state }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.ipsecpsk')">
        <span v-if="showKey">{{ remoteAccessVpn.presharedkey }}</span>
        <span v-else aria-hidden="true">••••••••</span>
        <a-button type="link" size="small" @click="showKey = !showKey">{{ $t(showKey ? 'label.public.ip.vpn.hide.key' : 'label.public.ip.vpn.show.key') }}</a-button>
      </a-descriptions-item>
    </a-descriptions>
    <a-empty v-else-if="!loading && !vpnGateway" :description="$t('message.public.ip.vpn.empty')" />
    <a-descriptions v-if="vpnGateway && !readFailed" size="small" :column="1">
      <a-descriptions-item :label="$t('label.site.to.site.vpn')">{{ vpnGateway.publicip }}</a-descriptions-item>
    </a-descriptions>

    <MoldDialog
v-if="dialog"
:visible="!!dialog"
:title="$t(actions[dialog].title)"
size="md"
      :closable="!networkMutationSubmitting"
:keyboard="!networkMutationSubmitting"
@cancel="closeDialog">
      <NetworkMutationStatus :operation="networkMutation" @recheck="recheckNetworkMutation" />
      <p>{{ $t(actions[dialog].message) }}</p>
      <template #footer>
        <a-button :disabled="networkMutationSubmitting" @click="closeDialog">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :danger="dialog.startsWith('delete')" :loading="networkMutationBusy" :disabled="locked || !canChange(dialog)" @click="submit">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>
  </section>
</template>

<script>
import { getAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import NetworkMutationStatus from '@/components/view/NetworkMutationStatus'
import { networkMutationMixin } from '@/utils/networkMutation'
import { publicIpScope, usablePublicIp } from '@/utils/publicIpRules'

const actions = {
  createRemoteAccessVpn: { title: 'label.enable.vpn', message: 'message.enable.vpn' },
  deleteRemoteAccessVpn: { title: 'label.disable.vpn', message: 'message.disable.vpn' },
  createVpnGateway: { title: 'label.add.vpn.gateway', message: 'message.add.vpn.gateway' },
  deleteVpnGateway: { title: 'label.delete.vpn.gateway', message: 'message.delete.vpn.gateway' }
}

export default {
  components: { MoldDialog, NetworkMutationStatus },
  mixins: [networkMutationMixin],
  inject: ['publicIpRemoteVpnAllowed'],
  props: { resource: { type: Object, required: true } },
  data () {
    return { actions, remoteAccessVpn: null, vpnGateway: null, dialog: '', loading: false, readFailed: false, showKey: false, version: 0 }
  },
  computed: {
    remoteAction () { return this.remoteAccessVpn ? 'deleteRemoteAccessVpn' : 'createRemoteAccessVpn' },
    gatewayAction () { return this.vpnGateway ? 'deleteVpnGateway' : 'createVpnGateway' },
    locked () { return this.loading || this.readFailed || this.networkMutationBlocked }
  },
  watch: {
    'resource.id' () {
      this.remoteAccessVpn = null
      this.vpnGateway = null
      this.dialog = ''
      this.refresh()
    }
  },
  created () { this.refresh() },
  deactivated () { this.showKey = false },
  methods: {
    can (api) { return api in this.$store.getters.apis },
    canChange (api) {
      if (!usablePublicIp(this.resource) || !this.can(api)) return false
      if (api.endsWith('VpnGateway')) return !!this.resource.vpcid && this.can('listVpnGateways') && (api.startsWith('delete') || !this.remoteAccessVpn)
      return this.can('listRemoteAccessVpns') && (api.startsWith('delete') || (!this.vpnGateway && this.publicIpRemoteVpnAllowed()))
    },
    async refresh () {
      const ip = this.resource
      const version = ++this.version
      const current = () => version === this.version && this.resource.id === ip.id
      this.showKey = false
      this.loading = true
      this.readFailed = false
      try {
        const [remote, gateways] = await Promise.all([
          this.can('listRemoteAccessVpns') ? getAPI('listRemoteAccessVpns', { ...publicIpScope(ip), publicipid: ip.id, listall: true }, { preserveOnFailure: true }) : null,
          ip.vpcid && this.can('listVpnGateways') ? getAPI('listVpnGateways', { ...publicIpScope(ip), vpcid: ip.vpcid, listall: true }, { preserveOnFailure: true }) : null
        ])
        if (!current()) return
        this.remoteAccessVpn = remote?.listremoteaccessvpnsresponse?.remoteaccessvpn?.[0] || null
        this.vpnGateway = gateways?.listvpngatewaysresponse?.vpngateway?.find(row => row.publicip === ip.ipaddress) || null
      } catch (_) {
        if (current()) this.readFailed = true
      } finally {
        if (current()) this.loading = false
      }
    },
    closeDialog () {
      if (!this.networkMutationSubmitting) this.dialog = ''
    },
    async submit () {
      const api = this.dialog
      if (!api || this.locked || !this.canChange(api)) return
      const ip = this.resource
      let params
      if (api === 'deleteVpnGateway') params = { id: this.vpnGateway.id }
      else if (api === 'createVpnGateway') params = { ipaddressid: ip.id, vpcid: ip.vpcid }
      else params = { publicipid: ip.id, ...publicIpScope(ip) }
      await this.submitNetworkMutation(api, params, async () => {
        this.dialog = ''
        await this.refresh()
      })
    }
  }
}
</script>
