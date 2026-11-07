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
  <div class="form" v-ctrl-enter="handleKeyboardSubmit">
    <div>
      <a-input-search
        class="top-spaced"
        :placeholder="$t('label.search')"
        v-model:value="searchQuery"
        style="margin-bottom: 10px;"
        @search="fetchNetworks"
        v-focus="true" />
      <a-table
        size="small"
        style="overflow-y: auto"
        :loading="loading"
        :columns="columns"
        :dataSource="networks"
        :pagination="false"
        :rowKey="record => record.id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'select'">
            <a-radio
              @click="updateSelection(record)"
              :checked="selectedNetwork != null && record.id === selectedNetwork.id">
            </a-radio>
          </template>
        </template>
      </a-table>
      <a-pagination
        class="top-spaced"
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="totalCount"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="['10', '20', '40', '80', '100']"
        @change="handleChangePage"
        @showSizeChange="handleChangePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>

    <a-divider />

    <div class="actions">
      <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" ref="submit" :disabled="loading || !selectedNetwork" @click="submitForm">{{ $t('label.ok') }}</a-button>
    </div>

  </div>
</template>

<script>
import { getAPI } from '@/api'

export default {
  name: 'NicNetworkSelectForm',
  props: {
    resource: {
      type: Object,
      required: true
    },
    zoneid: {
      type: String,
      required: true
    },
    owner: Object
  },
  data () {
    return {
      loading: false,
      networks: [],
      searchQuery: '',
      totalCount: 0,
      page: 1,
      pageSize: 10,
      selectedNetwork: null,
      requestVersion: 0,
      columns: [
        {
          key: 'name',
          title: this.$t('label.networkid'),
          dataIndex: 'name'
        },
        {
          key: 'type',
          title: this.$t('label.guestiptype'),
          dataIndex: 'type'
        },
        {
          key: 'vpcName',
          title: this.$t('label.vpc'),
          dataIndex: 'vpcName'
        },
        {
          key: 'cidr',
          title: this.$t('label.cidr'),
          dataIndex: 'cidr'
        },
        {
          key: 'select',
          title: this.$t('label.select')
        }
      ]
    }
  },
  computed: {
    ownership () {
      const { project, userInfo } = this.$store.getters
      const owner = this.owner || { projectid: project?.id, domainid: userInfo.domainid, account: userInfo.account }
      return owner.projectid ? { projectid: owner.projectid } : { domainid: owner.domainid, account: owner.account }
    },
    scope () {
      const { userInfo } = this.$store.getters
      return JSON.stringify([this.zoneid, this.resource.id, this.ownership, userInfo?.id, userInfo?.roleid, this.$store.state?.user?.token])
    }
  },
  created () {
    this.fetchNetworks(this.resource.selectednetworkid)
  },
  watch: {
    scope () {
      this.clearView()
      this.fetchNetworks()
    }
  },
  beforeUnmount () { this.requestVersion++ },
  methods: {
    async fetchNetworks (preselectId) {
      const version = ++this.requestVersion
      const scope = this.scope
      const current = () => version === this.requestVersion && scope === this.scope
      this.loading = true
      const params = {
        zoneid: this.zoneid,
        keyword: this.searchQuery,
        page: this.page,
        pagesize: this.pageSize,
        canusefordeploy: true,
        ignoreproject: true,
        ...this.ownership
      }
      try {
        const response = await getAPI('listNetworks', params, { preserveOnFailure: true })
        if (!current()) return
        this.networks = (response.listnetworksresponse.network || []).filter(row => row.zoneid === this.zoneid)
        this.totalCount = response.listnetworksresponse.count ?? 0
        if (typeof preselectId === 'string') this.selectedNetwork = this.networks.find(row => row.id === preselectId) || null
      } catch (error) {
        if (current()) {
          this.networks = []
          this.selectedNetwork = null
          this.totalCount = 0
          this.$notifyError(error)
        }
      } finally {
        if (current()) this.loading = false
      }
    },
    handleChangePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchNetworks()
    },
    handleChangePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchNetworks()
    },
    clearView () {
      this.networks = []
      this.searchQuery = ''
      this.totalCount = 0
      this.page = 1
      this.pageSize = 10
      this.selectedNetwork = null
    },
    updateSelection (network) {
      this.selectedNetwork = network
    },
    closeModal () {
      this.$emit('close-action')
    },
    handleKeyboardSubmit () {
      if (this.selectedNetwork != null) {
        this.submitForm()
      }
    },
    submitForm () {
      if (this.loading || !this.selectedNetwork) return
      this.$emit('select', this.resource.id, this.selectedNetwork)
      this.closeModal()
    }
  }
}
</script>

<style scoped lang="scss">
  .form {
    width: 80vw;

    @media (min-width: 900px) {
      width: 850px;
    }
  }

  .top-spaced {
    margin-top: 20px;
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    margin-top: 20px;

    button {
      &:not(:last-child) {
        margin-right: 10px;
      }
    }
  }
</style>
