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
  <div class="security-group-members">
    <div class="detail-tab-toolbar">
      <a-button :loading="pending" :disabled="!scope || !canReadVms" @click="load(page, pageSize)">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert
      v-if="listMessage"
      class="detail-tab-notice"
      :type="listMessageType"
      :message="$t(listMessage)"
      show-icon />
    <a-alert
      v-if="rows.length && (!canReadNetworks || networkError)"
      class="detail-tab-notice"
      :type="networkError ? 'warning' : 'info'"
      :message="$t(networkError ? 'message.security.group.members.networks.failed' : 'message.security.group.members.networks.forbidden')"
      show-icon />
    <detail-tab-table
      size="small"
      row-key="id"
      :columns="columns"
      :data-source="rows"
      :loading="pending && !loaded"
      :scroll="{ x: 1080 }"
      :pagination="pagination"
      :locale="{ emptyText }">
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'vm'">
          <router-link class="member-link" :to="'/vm/' + record.id">{{ record.displayname || record.name || record.id }}</router-link>
        </template>
        <template v-else-if="column.key === 'state'">
          <status v-if="record.state" :text="record.state" display-text vm-state />
          <span v-else>—</span>
        </template>
        <template v-else-if="column.key === 'networks'">
          <ul v-if="record.networks.length" class="member-networks">
            <li v-for="nic in record.networks" :key="nic.id">
              <router-link class="member-link" :to="'/guestnetwork/' + nic.networkid">{{ nic.name }}</router-link>
              <span v-if="nic.details" class="member-network-details">{{ nic.details }}</span>
            </li>
          </ul>
          <span v-else>—</span>
        </template>
        <span v-else class="member-value">{{ text || '—' }}</span>
      </template>
    </detail-tab-table>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
import { networkCollection } from '@/utils/networkLifecycle'

export default {
  name: 'SecurityGroupMembers',
  components: { Status },
  inheritAttrs: false,
  props: {
    resource: { type: Object, required: true },
    tab: { type: String, default: '' }
  },
  data () {
    return {
      rows: [],
      page: 1,
      pageSize: 10,
      total: 0,
      pending: false,
      loaded: false,
      listError: false,
      networkError: false,
      request: null
    }
  },
  computed: {
    canReadVms () {
      return !!this.$store.getters.apis?.listVirtualMachines
    },
    canReadNetworks () {
      return !!this.$store.getters.apis?.listNetworks
    },
    scope () {
      if (this.tab !== 'security.group.members' || !this.resource.id || this.resource.id !== this.$route.params.id) return ''
      return JSON.stringify([this.resource.id, this.resource.projectid, this.canReadVms, this.canReadNetworks])
    },
    listMessage () {
      if (!this.canReadVms) return 'message.security.group.members.forbidden'
      if (!this.listError) return ''
      return this.loaded ? 'message.security.group.members.stale' : 'message.security.group.members.failed'
    },
    listMessageType () {
      if (!this.canReadVms) return 'info'
      return this.loaded ? 'warning' : 'error'
    },
    emptyText () {
      return this.loaded && !this.rows.length ? this.$t('message.security.group.members.empty') : ' '
    },
    columns () {
      return [
        { key: 'vm', title: this.$t('label.vm.displayname'), width: 250 },
        { key: 'state', title: this.$t('label.state'), width: 105 },
        { key: 'networks', title: this.$t('label.security.group.members.networks'), width: 275 },
        { key: 'zone', title: this.$t('label.zone'), dataIndex: 'zonename', width: 105 },
        { key: 'account', title: this.$t('label.account'), dataIndex: 'account', width: 140 },
        { key: 'project', title: this.$t('label.project'), dataIndex: 'project', width: 130 },
        { key: 'domain', title: this.$t('label.domain'), dataIndex: 'domain' }
      ]
    },
    pagination () {
      if (!this.loaded && !this.pending) return false
      return {
        current: this.page,
        pageSize: this.pageSize,
        total: this.total,
        pageSizeOptions: ['10', '20', '50', '100'],
        showSizeChanger: true,
        disabled: this.pending,
        onChange: (page, size) => this.load(size === this.pageSize ? page : 1, size)
      }
    }
  },
  watch: {
    scope: {
      immediate: true,
      handler () {
        if (this.request) this.request.abort()
        this.request = null
        this.rows = []
        this.total = 0
        this.page = 1
        this.loaded = false
        this.pending = false
        this.listError = false
        this.networkError = false
        if (this.scope && this.canReadVms) this.load(1, this.pageSize)
      }
    }
  },
  beforeUnmount () {
    if (this.request) this.request.abort()
  },
  methods: {
    async load (page, size) {
      if (!this.scope || !this.canReadVms || this.pending) return
      const request = new AbortController()
      const scope = this.scope
      const active = () => !request.signal.aborted && this.scope === scope
      const get = (command, args) => getAPI(command, args, { signal: request.signal, timeout: 20000, preserveOnFailure: true })
      const owner = this.resource.projectid ? { projectid: this.resource.projectid } : { ignoreproject: true }

      this.request = request
      this.pending = true
      this.listError = false

      try {
        let result = await this.getVmPage(get, owner, page, size)
        if (!active()) return
        const lastPage = Math.max(1, Math.ceil(result.total / size))
        if (page > lastPage) {
          page = lastPage
          result = await this.getVmPage(get, owner, page, size)
          if (!active()) return
        }

        const networks = new Map()
        let networkError = false
        if (this.canReadNetworks && result.rows.some(vm => vm.nic?.some(nic => nic.networkid))) {
          try {
            const supported = await networkCollection(get, 'listNetworks', 'network', {
              ...owner, listall: true, traffictype: 'Guest', supportedservices: 'SecurityGroup'
            })
            for (const network of supported) networks.set(network.id, network)
          } catch {
            networkError = true
          }
        }
        if (!active()) return

        this.rows = result.rows.map(vm => ({
          ...vm,
          networks: (vm.nic || []).filter(nic => networks.has(nic.networkid)).map(nic => {
            const network = networks.get(nic.networkid)
            const vlan = /^vlan:\/\/(\d+|untagged)$/i.exec(network.broadcasturi || '')?.[1]
            const details = [vlan && (vlan === 'untagged' ? this.$t('label.security.group.members.untagged') : 'VLAN ' + vlan), nic.ipaddress, nic.ip6address]
            return { ...nic, name: network.name || nic.networkname || nic.networkid, details: details.filter(Boolean).join(' · ') }
          })
        }))
        this.page = page
        this.pageSize = size
        this.total = result.total
        this.networkError = networkError
        this.loaded = true
      } catch {
        if (active()) this.listError = true
      } finally {
        if (active()) {
          this.pending = false
          this.request = null
        }
      }
    },
    async getVmPage (get, owner, page, size) {
      const json = await get('listVirtualMachines', {
        ...owner, securitygroupid: this.resource.id, details: 'nics', listall: true, page, pagesize: size
      })
      const body = json.listvirtualmachinesresponse
      const rows = body?.virtualmachine ?? []
      const total = Number(body?.count ?? (rows.length === 0 ? 0 : NaN))
      if (!body || body.errorcode || !Array.isArray(rows) || !Number.isSafeInteger(total) || total < rows.length ||
        rows.some(vm => !vm.id || (vm.nic != null && !Array.isArray(vm.nic)))) throw new Error('Invalid instances page')
      return { rows, total }
    }
  }
}
</script>

<style scoped>
  .member-link {
    color: var(--ui-link);
    overflow-wrap: anywhere;
  }

  .member-link:hover {
    color: var(--ui-link);
    text-decoration: underline;
  }

  .member-networks {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .member-networks li + li {
    margin-block-start: 4px;
  }

  .member-network-details {
    display: block;
    color: var(--ui-text-muted);
  }

  .member-network-details,
  .member-value {
    overflow-wrap: anywhere;
  }

  .security-group-members :deep(.detail-table-footer .ant-pagination-item-active a) {
    color: var(--ui-link);
  }
</style>
