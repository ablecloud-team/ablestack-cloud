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
  <div v-ctrl-enter="handleSubmit" class="list-static-nat">
    <div class="list__header">
      <div class="list__header__col" v-if="tiersSelect">
        <a-select
          v-focus="true"
          @change="handleTierSelect"
          v-model:value="selectedVpcTier"
          :placeholder="$t('label.select.tier')"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option v-for="network in networksList" :key="network.id" :value="network.id" :label="network.name">
            {{ network.name }}
          </a-select-option>
        </a-select>
      </div>

      <div class="list__header__col list__header__col--full">
        <a-input-search
          :placeholder="$t('label.search')"
          v-model:value="searchQuery"
          @search="fetchData" />
      </div>
    </div>

    <a-table
      :scroll="{ x: 640, y: 225 }"
      style="margin-top: 20px"
      size="small"
      :loading="loading"
      :columns="columns"
      :dataSource="vmsList"
      :pagination="false"
      :rowKey="record => record.id || record.account">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'name'">
          <div>
            {{ record.name }}
          </div>
          <a-select
            v-if="nicsList.length && selectedVm && selectedVm === record.id"
            class="nic-select"
            v-model:value="selectedNic"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option
              v-for="item in nicsList"
              :key="item"
              :value="item"
              :label="item">
              {{ item }}
            </a-select-option>
          </a-select>
        </template>
        <template v-if="column.key === 'state'">
          <status :text="text ? text : ''" displayText />
        </template>
        <template v-if="column.key === 'radio'">
          <a-radio
            class="list__radio"
            :value="text"
            :checked="selectedVm && selectedVm === text"
            @change="fetchNics"></a-radio>
        </template>
      </template>
    </a-table>

    <a-pagination
      class="row-element pagination"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="vmCount"
      :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="['10', '20', '40', '80', '100']"
      @change="changePage"
      @showSizeChange="changePageSize"
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </a-pagination>

    <div class="list__footer">
      <a-button @click="handleClose">{{ $t('label.cancel') }}</a-button>
      <a-button @click="handleSubmit" type="primary" :loading="submitting" :disabled="loading || nicLoading || !selectedVm || !nicsList.includes(selectedNic)">{{ $t('label.ok') }}</a-button>
    </div>
  </div>

</template>

<script>
import { getAPI, postAPI } from '@/api'
import { publicIpNicAddresses, publicIpScope } from '@/utils/publicIpRules'
import Status from '@/components/widgets/Status'

export default {
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  components: {
    Status
  },
  inject: ['parentFetchData'],
  data () {
    return {
      loading: false,
      nicLoading: false,
      submitting: false,
      vmRequest: 0,
      nicRequest: 0,
      vmsList: [],
      selectedVm: null,
      nicsList: [],
      searchQuery: null,
      selectedNic: null,
      columns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          width: 200
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.displayname'),
          dataIndex: 'displayname'
        },
        {
          title: this.$t('label.account'),
          dataIndex: 'account'
        },
        {
          title: this.$t('label.zonenamelabel'),
          dataIndex: 'zonename'
        },
        {
          key: 'radio',
          title: this.$t('label.select'),
          dataIndex: 'id',
          width: 70
        }
      ],
      tiersSelect: false,
      networksList: [],
      selectedVpcTier: null,
      page: 1,
      pageSize: 10,
      vmCount: 0
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    clearTarget () {
      this.nicRequest++
      this.selectedVm = null
      this.selectedNic = null
      this.nicsList = []
      this.nicLoading = false
    },
    async fetchData () {
      const request = ++this.vmRequest
      const ip = this.resource
      const current = () => request === this.vmRequest && ip.id === this.resource.id
      this.clearTarget()
      this.loading = true
      this.vmsList = []
      this.vmCount = 0
      try {
        if (ip.vpcid && !this.tiersSelect) {
          this.tiersSelect = true
          const json = await getAPI('listNetworks', { ...publicIpScope(ip), vpcid: ip.vpcid, supportedservices: 'StaticNat', listall: true })
          if (!current()) return
          this.networksList = (json.listnetworksresponse.network || []).filter(network => network.vpcid === ip.vpcid &&
            (!ip.associatednetworkid || ip.isportable || network.id === ip.associatednetworkid))
          this.selectedVpcTier = this.networksList.find(network => network.id === ip.associatednetworkid)?.id || null
        }
        const networkId = ip.vpcid ? this.selectedVpcTier : ip.associatednetworkid
        if (!networkId) return
        const json = await getAPI('listVirtualMachines', {
          ...publicIpScope(ip),
          page: this.page,
          pagesize: this.pageSize,
          listall: true,
          networkid: networkId,
          state: 'Present',
          keyword: this.searchQuery
        })
        if (!current()) return
        this.vmCount = json.listvirtualmachinesresponse.count || 0
        this.vmsList = json.listvirtualmachinesresponse.virtualmachine || []
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.loading = false
      }
    },
    async fetchNics (event) {
      this.clearTarget()
      const vmId = event.target.value
      const request = this.nicRequest
      const ipId = this.resource.id
      const networkId = this.resource.vpcid ? this.selectedVpcTier : this.resource.associatednetworkid
      if (!networkId || !this.vmsList.some(vm => vm.id === vmId)) return
      this.selectedVm = vmId
      this.nicLoading = true
      const current = () => request === this.nicRequest && ipId === this.resource.id
      try {
        const json = await getAPI('listNics', { virtualmachineid: vmId, networkid: networkId })
        if (!current()) return
        this.nicsList = publicIpNicAddresses(json.listnicsresponse.nic, networkId)
        this.selectedNic = this.nicsList[0] || null
      } catch (error) {
        if (current()) this.$notifyError(error)
      } finally {
        if (current()) this.nicLoading = false
      }
    },
    async handleSubmit () {
      if (this.loading || this.nicLoading || this.submitting || !this.selectedVm || !this.nicsList.includes(this.selectedNic)) return
      const ip = this.resource
      const networkId = ip.vpcid ? this.selectedVpcTier : ip.associatednetworkid
      if (!networkId || !this.$store.getters.apis.enableStaticNat) return
      this.submitting = true
      try {
        await postAPI('enableStaticNat', {
          ipaddressid: ip.id,
          virtualmachineid: this.selectedVm,
          vmguestip: this.selectedNic,
          networkid: networkId
        })
        this.handleClose()
        this.parentFetchData()
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.submitting = false
      }
    },
    handleClose () {
      this.$emit('close-action')
    },
    handleTierSelect () {
      this.page = 1
      this.fetchData()
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    changePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    }
  }
}
</script>

<style scoped lang="scss">
  .list-static-nat {
    width: 100%;
    min-width: 0;
  }

  .list {
    &__header {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;

      .ant-select {
        min-width: 200px;
      }

      &__col {

        &--full {
          flex: 1;
        }

      }

    }

    &__footer {
      display: flex;
      justify-content: flex-end;

      button {
        &:not(:last-child) {
          margin-right: 10px;
        }
      }
    }

    &__item {
      padding-right: 20px;
      padding-left: 20px;

      &--selected {
        background-color: #e6f7ff;
      }

    }

    &__title {
      font-weight: bold;
    }

    &__outer-container {
      width: 100%;
      display: flex;
      flex-direction: column;
    }

    &__container {
      display: flex;
      flex-direction: column;
      width: 100%;
      cursor: pointer;

      @media (min-width: 760px) {
        flex-direction: row;
        align-items: center;
      }

    }

    &__row {
      margin-bottom: 10px;

      @media (min-width: 760px) {
        margin-right: 20px;
        margin-bottom: 0;
      }
    }

    &__radio {
      display: flex;
    }

    &__content {
      padding: 0 20px;
    }
  }

  .nic-select {
    margin-top: 10px;
    margin-right: auto;
    min-width: 150px;
  }

  .pagination {
    margin-top: 20px;
  }

  :deep(.ant-table-small) {
    border: 0
  }
</style>
