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
    :columns="desktopNicColumns"
    :dataSource="resource"
    :rowKey="item => item.id"
    :pagination="false"
  >
    <template #expandedRowRender="{ record }">
      <slot name="actions" :nic="record" />
      <a-descriptions style="margin-top: 10px" layout="vertical" :column="1" :bordered="false" size="small">
        <a-descriptions-item :label="$t('label.id')">
          {{ record.id }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.networkid')" v-if="record.networkid">
          {{ record.networkid }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.type')" v-if="record.type">
          {{ record.type }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.traffictype')" v-if="record.traffictype">
          {{ record.traffictype }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.secondaryips')" v-if="record.secondaryip && record.secondaryip.length > 0 && record.type !== 'L2'">
          {{ record.secondaryip.map(x => x.ipaddress).join(', ') }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.cidr')" v-if="record.cidr">
          {{ record.cidr }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.created')" v-if="record.created">
          {{ record.created }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.zonename')" v-if="record.zonename">
          {{ record.zonename }}
        </a-descriptions-item>
        <template v-if="['Admin', 'DomainAdmin'].includes($store.getters.userInfo.roletype)">
          <a-descriptions-item :label="$t('label.broadcasturi')" v-if="record.broadcasturi">
            {{ record.broadcasturi }}
          </a-descriptions-item>
          <a-descriptions-item :label="$t('label.isolationuri')" v-if="record.isolationuri">
            {{ record.isolationuri }}
          </a-descriptions-item>
        </template>
      </a-descriptions>
    </template>
    <template #name="{text, record}">
      <ApartmentOutlined />
      <router-link :to="{ path: '/guestnetwork/' + record.id }">
        {{ text }}
      </router-link>
      <a-tag v-if="record.isdefault">
        {{ $t('label.default') }}
      </a-tag>
    </template>
  </a-table>
</template>

<script>

export default {
  name: 'DesktopNicsTable',
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
  inject: ['parentFetchData'],
  data () {
    return {
      desktopNicColumns: [
        {
          title: this.$t('label.networkname'),
          dataIndex: 'name',
          slots: { customRender: 'name' }
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.netmask'),
          dataIndex: 'netmask'
        },
        {
          title: this.$t('label.gateway'),
          dataIndex: 'gateway'
        }
      ]
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        if (newItem && (!oldItem || (newItem.id !== oldItem.id))) {
          this.resources = newItem
        }
      }
    }
  }
}
</script>
