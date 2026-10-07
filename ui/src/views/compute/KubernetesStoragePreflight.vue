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
  <a-spin :spinning="busy">
    <a-card size="small" :title="$t('label.kubernetes.storage.preflight')">
      <a-alert v-if="warning" type="warning" show-icon :message="$t('message.kubernetes.storage.unverified')" />
      <a-descriptions :column="1" size="small">
        <a-descriptions-item v-for="row in rows" :key="row.key" :label="row.role">
          {{ row.offering }} / {{ row.disk }} / {{ $t('label.storage.tags') }}: {{ row.tags || $t('label.none') }} /
          {{ $t('label.diskofferingstrictness') }}: {{ row.strict ? $t('label.yes') : $t('label.no') }}
        </a-descriptions-item>
      </a-descriptions>
      <p>{{ $t('message.kubernetes.storage.constraint') }}</p>
    </a-card>
  </a-spin>
</template>
<script>
import { getAPI } from '@/api'
export default {
  name: 'KubernetesStoragePreflight',
  props: { offerings: { type: Array, default: () => [] }, network: { type: Object, default: null }, defaultNetworkOffering: { type: Object, default: null } },
  data () { return { busy: false, warning: false, rows: [], request: 0 } },
  watch: {
    offerings: { deep: true, handler () { this.fetchPlacement() } },
    network: { deep: true, handler () { this.fetchPlacement() } },
    defaultNetworkOffering: { deep: true, handler () { this.fetchPlacement() } }
  },
  created () { this.fetchPlacement() },
  beforeUnmount () { this.request++ },
  methods: {
    async placement (key, offering) {
      if (!offering?.id) throw new Error('Storage offering is unavailable')
      let disk = {}
      if (offering.diskofferingid) {
        const response = await getAPI('listDiskOfferings', { id: offering.diskofferingid, listAll: true })
        disk = response.listdiskofferingsresponse?.diskoffering?.find(d => d.id === offering.diskofferingid)
        if (!disk) throw new Error('Root disk offering is unavailable')
      }
      return { key: key + '/' + offering.id, role: key, offering: offering.name || offering.id, disk: disk.name || this.$t('label.unknown'), tags: disk.tags || '', strict: offering.diskofferingstrictness === true && !disk.iscomputeonly }
    },
    async fetchPlacement () {
      const request = ++this.request
      const rows = []
      let warning = false
      this.busy = true
      try {
        for (const item of this.offerings) rows.push(await this.placement(item.role, item.offering))
        let routerOfferingIds = []
        const network = this.network
        const scope = network?.projectid ? { projectid: network.projectid } : { account: network?.account, domainid: network?.domainid }
        if (network?.id && network.type !== 'Shared' && !network.ip4routing) {
          if (this.$store.getters.apis.listRouters) {
            const response = await getAPI('listRouters', { ...scope, listAll: true, ...(network.vpcid ? { vpcid: network.vpcid } : { networkid: network.id }) })
            routerOfferingIds = (response.listroutersresponse?.router || []).map(r => r.serviceofferingid).filter(Boolean)
          }
          if (!routerOfferingIds.length && !network.vpcid) {
            const response = await getAPI('listNetworkOfferings', { id: network.networkofferingid })
            const offering = response.listnetworkofferingsresponse?.networkoffering?.[0]
            if (offering?.serviceofferingid) routerOfferingIds.push(offering.serviceofferingid)
          }
          if (!routerOfferingIds.length && network.vpcid) {
            const response = await getAPI('listVPCs', { ...scope, id: network.vpcid, listAll: true })
            const vpc = response.listvpcsresponse?.vpc?.[0]
            const offerings = await getAPI('listVPCOfferings', { id: vpc?.vpcofferingid })
            const offering = offerings.listvpcofferingsresponse?.vpcoffering?.[0]
            if (offering?.serviceofferingid) routerOfferingIds.push(offering.serviceofferingid)
          }
        } else if (!network?.id && this.defaultNetworkOffering?.serviceofferingid) {
          routerOfferingIds.push(this.defaultNetworkOffering.serviceofferingid)
        }
        for (const id of [...new Set(routerOfferingIds)]) {
          const response = await getAPI('listServiceOfferings', { id, issystem: true, listAll: true })
          rows.push(await this.placement(this.$t('label.virtualrouter'), response.listserviceofferingsresponse?.serviceoffering?.find(offering => offering.id === id)))
        }
        const required = new Set(rows.filter(row => row.strict && row.role !== this.$t('label.virtualrouter')).flatMap(row => row.tags.split(',').map(tag => tag.trim()).filter(Boolean)))
        const routers = rows.filter(row => row.role === this.$t('label.virtualrouter'))
        const needsRouter = !network?.id || (network.type !== 'Shared' && !network.ip4routing)
        warning = needsRouter && (!routers.length || routers.some(row => !row.strict || [...required].some(tag => !row.tags.split(',').map(t => t.trim()).includes(tag))))
      } catch (_) { warning = true } finally {
        if (request === this.request) { this.rows = rows; this.warning = warning; this.busy = false }
      }
    }
  }
}
</script>
