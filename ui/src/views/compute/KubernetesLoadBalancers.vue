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
  <div class="kubernetes-load-balancers">
    <load-balancing v-if="selectedIp && inventoryReady" :key="selectedIp.id" :resource="selectedIp" :rule-owners="ruleOwners" :context-loading="busy || failed" @refresh-inventory="fetchRules">
      <template #toolbar-context><label class="detail-tab-toolbar-context"><span>{{ $t('label.publicip') }}</span><a-select v-model:value="selectedIpId" :placeholder="$t('label.publicip')" :options="publicIps.map(ip => ({ value: ip.id, label: ip.ipaddress }))" /></label></template>
      <template #guidance><a-alert class="network-rules-guidance" type="info" show-icon :message="$t('message.kubernetes.lb.readonly')" /></template>
    </load-balancing>
    <div v-else class="detail-tab-toolbar"><a-button :loading="busy" @click="fetchRules"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button></div>
    <a-collapse v-if="rows.length" class="mold-dialog-section"><a-collapse-panel key="ownership" :header="$t('label.kubernetes.lb.owner')">
    <a-alert v-if="failed" type="error" show-icon :message="$t('message.kubernetes.lb.incomplete')" />
    <a-table v-else :columns="columns" :dataSource="rows" :rowKey="item => item.id" :pagination="{ pageSize: 10 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'owner'">{{ $t('label.kubernetes.lb.' + record.owner.kind) }}<br>{{ record.owner.serviceUID }}</template>
        <template v-else-if="column.key === 'backend'">
          <div v-for="vm in record.backends" :key="vm.id">
            <router-link :to="'/vm/' + vm.id">{{ vm.displayname || vm.name || vm.id }}</router-link> — {{ vm.state }}
          </div>
        </template>
        <template v-else-if="column.key === 'access'">
          <div v-if="record.acls.length">{{ $t('label.kubernetes.lb.acl') }}: {{ record.acls.join('; ') }}</div>
          <div v-else>{{ $t('label.unknown') }}</div>
        </template>
      </template>
    </a-table>
    </a-collapse-panel></a-collapse>
    <p class="network-rule-secondary mold-dialog-section">{{ $t('message.kubernetes.lb.health') }}</p>
  </div>
</template>

<script>
import LoadBalancing from '@/views/network/LoadBalancing'
import { getAPI } from '@/api'
import { listKubernetesNetworkResources, kubernetesLoadBalancerOwner, clusterApiAddress } from '@/utils/kubernetesLoadBalancers'

export default {
  name: 'KubernetesLoadBalancers',
  components: { LoadBalancing },
  props: { resource: { type: Object, required: true } },
  data () { return { busy: false, failed: false, inventoryReady: false, rows: [], publicIps: [], selectedIpId: null, request: 0 } },
  computed: {
    selectedIp () { return this.publicIps.find(ip => ip.id === this.selectedIpId) },
    ruleOwners () { return Object.fromEntries(this.rows.filter(row => row.owner).map(row => [row.id, row.owner])) },
    columns () {
      return [
        { title: this.$t('label.name'), dataIndex: 'name' },
        { title: this.$t('label.kubernetes.lb.owner'), key: 'owner' },
        { title: this.$t('label.publicip'), dataIndex: 'ipaddress' },
        { title: this.$t('label.publicport'), dataIndex: 'publicport' },
        { title: this.$t('label.privateport'), dataIndex: 'privateport' },
        { title: this.$t('label.protocol'), dataIndex: 'protocol' },
        { title: this.$t('label.state'), dataIndex: 'state' },
        { title: this.$t('label.kubernetes.lb.access'), key: 'access' },
        { title: this.$t('label.instances'), key: 'backend' }
      ]
    }
  },
  watch: { resource: { deep: true, handler () { this.fetchRules() } } },
  created () { this.fetchRules() },
  beforeUnmount () { this.request++ },
  methods: {
    async fetchRules () {
      const resource = { ...this.resource }
      const request = ++this.request
      const current = () => request === this.request && resource.id === this.resource.id
      this.busy = true
      this.failed = false
      const list = (command, body, item, params) => listKubernetesNetworkResources(getAPI, command, body, item, params)
      const scope = resource.projectid ? { projectid: resource.projectid } : { account: resource.account, domainid: resource.domainid }
      try {
        if (!resource.id || !resource.networkid) return
        const networks = await list('listNetworks', 'listnetworksresponse', 'network', { ...scope, id: resource.networkid })
        const network = networks.find(n => n.id === resource.networkid)
        if (!network) throw new Error('Cluster network is unavailable')
        if (network.type === 'Shared' || network.ip4routing) return
        const ips = await list('listPublicIpAddresses', 'listpublicipaddressesresponse', 'publicipaddress', { ...scope, associatednetworkid: resource.networkid })
        if (current()) {
          this.publicIps = ips
          if (!ips.some(ip => ip.id === this.selectedIpId)) this.selectedIpId = ips.find(ip => ip.id === resource.ipaddressid)?.id || ips[0]?.id
        }
        const aclRules = network.vpcid
          ? await list('listNetworkACLs', 'listnetworkaclsresponse', 'networkacl', { ...scope, aclid: network.aclid }) : []
        const rows = []
        for (const ip of ips) {
          const lbs = await list('listLoadBalancerRules', 'listloadbalancerrulesresponse', 'loadbalancerrule', { ...scope, publicipid: ip.id })
          const firewalls = network.vpcid ? [] : await list('listFirewallRules', 'listfirewallrulesresponse', 'firewallrule', { ...scope, ipaddressid: ip.id })
          for (const rule of lbs) {
            const entries = await list('listLoadBalancerRuleInstances', 'listloadbalancerruleinstancesresponse', 'lbrulevmidip', { ...scope, id: rule.id, lbvmips: true })
            const backends = entries.map(entry => entry.loadbalancerruleinstance).filter(Boolean)
            const owner = kubernetesLoadBalancerOwner(resource, ip, rule, backends)
            if (!owner) continue
            rows.push({ ...rule, owner, backends, ipaddress: ip.ipaddress, acls: this.accessRules(network.vpcid ? aclRules : firewalls, rule, !!network.vpcid) })
          }
          // Single-control clusters use an API PF rule instead of an API LB.
          if (ip.ipaddress === clusterApiAddress(resource)) {
            const ports = await list('listPortForwardingRules', 'listportforwardingrulesresponse', 'portforwardingrule', { ...scope, ipaddressid: ip.id })
            for (const rule of ports) {
              const vm = (resource.virtualmachines || []).find(vm => vm.iscontrolnode && vm.id === rule.virtualmachineid)
              if (!vm || Number(rule.publicport) !== 6443 || Number(rule.privateport) !== 6443 || String(rule.protocol).toLowerCase() !== 'tcp') continue
              rows.push({ ...rule, name: this.$t('label.kubernetes.lb.api.pf'), owner: { kind: 'api', serviceUID: '' }, backends: [vm], ipaddress: ip.ipaddress, acls: this.accessRules(network.vpcid ? aclRules : firewalls, rule, !!network.vpcid) })
            }
          }
        }
        if (current()) { this.rows = rows; this.inventoryReady = true }
      } catch (error) {
        if (current()) { this.failed = true; this.$notifyError(error) }
      } finally { if (current()) this.busy = false }
    },
    accessRules (rules, lb, vpc) {
      return rules.filter(rule => (!vpc || String(rule.traffictype).toLowerCase() === 'ingress') &&
        (String(rule.protocol).toLowerCase() === 'all' || (String(rule.protocol).toLowerCase() === String(lb.protocol || 'tcp').toLowerCase() &&
        (rule.startport == null || Number(rule.startport) <= Number(lb.publicport)) && (rule.endport == null || Number(rule.endport) >= Number(lb.publicport)))))
        .sort((a, b) => Number(a.number || 0) - Number(b.number || 0))
        .map(rule => `${vpc ? '#' + rule.number + ' ' + rule.action + ' ' : ''}${rule.cidrlist || ''} (${rule.state || ''})`)
    }
  }
}
</script>
<style scoped>
.refresh { margin: 12px 0; }
</style>
