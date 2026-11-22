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
  <a-spin :spinning="fetchLoading">
    <resource-table-toolbar v-if="traffictypes.length || listRefreshFailed" :loading="fetchLoading || listRefreshing > 0" @refresh="fetchData()">
      <select
        v-if="traffictypes.length"
        id="zone-traffic-type"
        class="ant-input traffic-type-select"
        :aria-label="$t('label.traffictype')"
        :value="selectedTraffic?.traffictype"
        @change="selectTraffic($event.target.value)">
        <option v-for="item in traffictypes" :key="item.id" :value="item.traffictype">{{ item.traffictype }}</option>
      </select>
      <template #actions>
        <a-popconfirm
          v-if="selectedTraffic"
          :title="$t('message.confirm.delete.traffic.type')"
          @confirm="deleteTrafficType(selectedTraffic)"
          :okText="$t('label.yes')"
          :cancelText="$t('label.no')" >
          <a-button
            type="primary"
            danger
            :loading="loading"
            :disabled="!('deleteTrafficType' in $store.getters.apis)">
            <template #icon><delete-outlined /></template> {{ $t('label.delete.traffic.type') }}
          </a-button>
        </a-popconfirm>
      </template>
    </resource-table-toolbar>
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <div v-if="selectedTraffic" :key="selectedTraffic.traffictype">
      <div
        v-for="(type, idx) in ['kvmnetworklabel', 'vmwarenetworklabel', 'xennetworklabel', 'hypervnetworklabel', 'ovm3networklabel']"
        :key="idx"
        style="margin-bottom: 10px;">
        <div><strong>{{ $t('label.' + type) }}</strong></div>
        <div>{{ selectedTraffic[type] || $t('label.network.label.display.for.blank.value') }}</div>
      </div>
      <div v-if="selectedTraffic.traffictype === 'Guest' && networkType === 'Advanced'">
        <a-divider />
        <IpRangesTabGuest :resource="resource" :loading="loading"/>
      </div>
      <div v-if="selectedTraffic.traffictype === 'Guest' && networkType === 'Basic'">
        <a-divider />
        <IpRangesTabPublic
          :resource="resource"
          :loading="loading"
          :network="guestNetwork"
          :basicGuestNetwork="networkType === 'Basic' && selectedTraffic.traffictype === 'Guest'"/>
      </div>
      <div v-if="selectedTraffic.traffictype === 'Public'">
        <div style="margin-bottom: 10px;">
          <div><strong>{{ $t('label.traffictype') }}</strong></div>
          <div>{{ publicNetwork.traffictype }}</div>
        </div>
        <div style="margin-bottom: 10px;">
          <div><strong>{{ $t('label.broadcastdomaintype') }}</strong></div>
          <div>{{ publicNetwork.broadcastdomaintype }}</div>
        </div>
        <a-divider />
        <IpRangesTabPublic :resource="resource" :loading="loading" :network="publicNetwork" :basicGuestNetwork="false" />
      </div>
      <div v-if="selectedTraffic.traffictype === 'Management'">
        <a-divider />
        <IpRangesTabManagement :resource="resource" :loading="loading" />
      </div>
      <div v-if="selectedTraffic.traffictype === 'Storage'">
        <a-divider />
        <IpRangesTabStorage :resource="resource" />
      </div>
    </div>
    <a-empty v-else-if="!fetchLoading" />
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import IpRangesTabPublic from './IpRangesTabPublic'
import IpRangesTabManagement from './IpRangesTabManagement'
import IpRangesTabStorage from './IpRangesTabStorage'
import IpRangesTabGuest from './IpRangesTabGuest'
import { trafficTypeTab } from '@/config/section/infra/phynetworks.js'

export default {
  name: 'TrafficTypesTab',
  mixins: [listRefreshMixin(['fetchData'], { routeKey: vm => vm.$route.path })],
  components: {
    ResourceTableToolbar,
    IpRangesTabPublic,
    IpRangesTabManagement,
    IpRangesTabStorage,
    IpRangesTabGuest
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      traffictypes: [],
      publicNetwork: {},
      guestNetwork: {},
      networkType: 'Advanced',
      fetchLoading: false
    }
  },
  computed: {
    selectedTraffic () {
      return this.traffictypes.find(item => item.traffictype === this.$route.query.traffic) || this.traffictypes[0]
    }
  },
  created () {
    if (this.resource.id) {
      this.fetchData()
    }
  },
  watch: {
    'resource.id' () { this.fetchData() },
    selectedTraffic (traffic) {
      trafficTypeTab.index = Math.max(0, this.traffictypes.indexOf(traffic))
    },
    loading (newData, oldData) {
      if (!newData && this.resource.id) {
        this.fetchData()
      }
    }
  },
  methods: {
    async fetchData () {
      const { id, zoneid } = this.resource
      if (!id || !zoneid) return
      const request = this.listRequestToken('fetchData')
      const current = () => this.isListRequestCurrent('fetchData', request)
      this.fetchLoading = !request.loaded
      if (!request.loaded) {
        this.traffictypes = []
        this.publicNetwork = {}
        this.guestNetwork = {}
      }
      try {
        const [traffic, zones] = await Promise.all([
          getAPI('listTrafficTypes', { physicalnetworkid: id }, { preserveOnFailure: true }),
          getAPI('listZones', { id: zoneid }, { preserveOnFailure: true })
        ])
        if (!current()) return
        const types = traffic.listtraffictypesresponse.traffictype || []
        const zone = zones.listzonesresponse.zone?.find(item => item.id === zoneid)
        if (!zone) throw new Error('Zone lookup failed')
        const [publicNetwork, guestNetwork] = await Promise.allSettled([
          types.some(type => type.traffictype === 'Public') ? this.fetchTrafficNetwork(zoneid, 'Public') : {},
          zone.networktype === 'Basic' && types.some(type => type.traffictype === 'Guest') ? this.fetchTrafficNetwork(zoneid, 'Guest') : {}
        ])
        if (!current()) return
        this.traffictypes = types
        this.networkType = zone.networktype
        if (publicNetwork.status === 'fulfilled') this.publicNetwork = publicNetwork.value
        if (guestNetwork.status === 'fulfilled') this.guestNetwork = guestNetwork.value
        request.failed = publicNetwork.status === 'rejected' || guestNetwork.status === 'rejected'
        if (request.failed) this.listRefreshFailed = true
      } catch (error) {
        if (!current()) return
        request.failed = true
        this.listRefreshFailed = true
        if (!request.loaded) this.$notifyError(error)
      } finally {
        if (current()) this.fetchLoading = false
      }
    },
    async fetchTrafficNetwork (zoneId, trafficType) {
      const response = await getAPI('listNetworks', {
        listAll: true,
        trafficType,
        zoneId,
        ...(trafficType === 'Public' ? { isSystem: true } : {})
      }, { preserveOnFailure: true })
      return response.listnetworksresponse.network?.[0] || {}
    },
    deleteTrafficType (trafficType) {
      postAPI('deleteTrafficType', { id: trafficType.id }).then(response => {
        const jobId = response.deletetraffictyperesponse.jobid
        if (!jobId) return this.fetchData()
        this.$pollJob({
          jobId,
          title: this.$t('label.delete.traffic.type'),
          description: trafficType.traffictype,
          successMessage: this.$t('message.traffic.type.deleted') + ' ' + trafficType.traffictype,
          successMethod: () => this.fetchData(),
          errorMessage: this.$t('message.delete.failed'),
          errorMethod: () => this.fetchData(),
          loadingMessage: this.$t('message.delete.traffic.type.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => this.fetchData()
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    selectTraffic (traffic) {
      this.$router.push({ query: { ...this.$route.query, traffic } })
    }
  }
}
</script>

<style lang="less" scoped>

</style>

<style scoped>
.traffic-type-select {
  width: 10rem;
  max-width: 100%;
  height: 32px;
}
@media (max-width: 768px) {
  .traffic-type-select { width: 100%; }
}
</style>
