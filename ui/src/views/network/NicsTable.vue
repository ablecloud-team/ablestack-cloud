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
  <a-table
    size="small"
    :columns="nicColumns"
    :dataSource="resource.nic"
    :rowKey="item => item.id"
    :pagination="false"
  >
    <template #expandedRowRender="{ record }">
    <slot name="actions" :nic="record" />
    <a-list size="small">
      <a-list-item />
      <a-list-item>
        <strong>{{ $t('label.nic.linkstate') }}</strong> : {{ record.linkstate ? 'UP' : 'DOWN' }}
      </a-list-item>
      <a-list-item>
        <strong>{{ $t('label.id') }}</strong> : {{ record.id }}
      </a-list-item>
      <a-list-item v-if="record.networkid">
        <strong>{{ $t('label.networkid') }}</strong> : {{ record.networkid }}
      </a-list-item>
      <a-list-item v-if="record.type">
        <strong>{{ $t('label.type') }}</strong> : {{ record.type }}
      </a-list-item>
      <a-list-item v-if="record.traffictype">
        <strong>{{ $t('label.traffictype') }}</strong> : {{ record.traffictype }}
      </a-list-item>
      <a-list-item v-if="record.secondaryip && record.secondaryip.length > 0 && record.type !== 'L2'">
        <strong>{{ $t('label.secondaryips') }}</strong> : {{ record.secondaryip.map(x => x.ipaddress).join(', ') }}
      </a-list-item>
      <a-list-item v-if="record.ip6address">
        <strong>{{ $t('label.ip6address') }}</strong> : {{ record.ip6address }}
      </a-list-item>
      <a-list-item v-if="record.ip6address">
        <strong>{{ $t('label.ip6gateway') }}</strong> : {{ record.ip6gateway }}
      </a-list-item>
      <a-list-item v-if="record.ip6address">
        <strong>{{ $t('label.ip6cidr') }}</strong> : {{ record.ip6cidr }}
      </a-list-item >
      <a-list-item v-if="['Admin', 'DomainAdmin'].includes($store.getters.userInfo.roletype) && record.broadcasturi">
        <strong>{{ $t('label.broadcasturi') }}</strong> : {{ record.broadcasturi }}
      </a-list-item>
      <a-list-item v-if="record.isolationuri">
        <strong>{{ $t('label.isolationuri') }}</strong> : {{ record.isolationuri }}
      </a-list-item>
    </a-list>
    </template>
    <template #bodyCell="{ column, text, record }">
      <template v-if="column.key === 'networkname'">
        <resource-icon v-if="!networkIconLoading && networkicon[record.id]" :image="networkicon[record.id]" size="1x" style="margin-right: 5px"/>
        <apartment-outlined v-else style="margin-right: 5px" />
        <router-link :to="{ path: '/guestnetwork/' + record.networkid }">
          {{ text }}
        </router-link>
        <a-tag v-if="record.isdefault">
          {{ $t('label.default') }}
        </a-tag>
      </template>
    </template>
  </a-table>
</template>

<script>
import { api } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon'

export default {
  name: 'NicsTable',
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
  components: {
    ResourceIcon
  },
  inject: ['parentFetchData'],
  data () {
    return {
      nicColumns: [
        {
          key: 'deviceid',
          title: this.$t('label.deviceid'),
          dataIndex: 'deviceid'
        },
        {
          key: 'networkname',
          title: this.$t('label.networkname'),
          dataIndex: 'networkname'
        },
        {
          title: this.$t('label.macaddress'),
          dataIndex: 'macaddress'
        },
        {
          title: this.$t('label.ipaddress'),
          dataIndex: 'ipaddress'
        },
        {
          title: this.$t('label.netmask'),
          dataIndex: 'netmask'
        },
        {
          title: this.$t('label.gateway'),
          dataIndex: 'gateway'
        }
      ],
      networkicon: {},
      networkIconLoading: false
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (newItem && (!oldItem || (newItem.id !== oldItem.id))) {
          this.fetchNetworks()
        }
      }
    }
  },
  created () {
    this.fetchNetworks()
  },
  methods: {
    fetchNetworks () {
      if (!this.resource || !this.resource.nic) return
      this.networkIconLoading = true
      this.networkicon = {}
      const promises = []
      this.resource.nic.forEach((item, index) => {
        promises.push(this.fetchNetworkIcon(item.id, item.networkid))
      })
      Promise.all(promises).catch((reason) => {
        console.log(reason)
      }).finally(() => {
        this.networkIconLoading = false
      })
    },
    fetchNetworkIcon (id, networkid) {
      return new Promise((resolve, reject) => {
        this.networkicon[id] = null
        api('listNetworks', {
          id: networkid,
          showicon: true
        }).then(json => {
          const network = json.listnetworksresponse?.network || []
          if (network?.[0]?.icon) {
            this.networkicon[id] = network[0]?.icon?.base64image
            resolve(this.networkicon)
          } else {
            this.networkicon[id] = ''
            resolve(this.networkicon)
          }
        }).catch(error => {
          reject(error)
        })
      })
    }
  }
}
</script>
