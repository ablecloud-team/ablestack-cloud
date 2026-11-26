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
  <div class="dns-records-tab">
    <a-alert
      v-if="listRefreshFailed"
      class="dns-records-notice"
      :type="loadError ? 'error' : 'warning'"
      show-icon
      :message="$t(loadError ? 'error.fetching.data' : 'message.list.refresh.stale')">
      <template #description><a-button size="small" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-spin :spinning="fetchLoading">
      <div class="dns-records-toolbar">
        <a-button v-if="canCreate" type="primary" @click="showAddForm = true">
          <template #icon><plus-outlined aria-hidden="true" /></template>{{ $t('label.dns.create.record') }}
        </a-button>
        <a-button :aria-label="$t('label.refresh')" :title="$t('label.refresh')" @click="fetchData">
          <template #icon><reload-outlined /></template>
        </a-button>
        <a-input-search
          v-model:value="searchText"
          :aria-label="$t('label.search')"
          :placeholder="$t('label.search')"
          allow-clear
          @search="searchRecords" />
      </div>

      <detail-tab-table
        size="small"
        :scroll="{ x: 620 }"
        :columns="visibleColumns"
        :dataSource="records"
        :rowKey="item => `${item.name}-${item.type}`"
        :pagination="false">
        <template #emptyText>{{ $t(fetchLoading ? 'label.loading' : loadError ? 'error.fetching.data' : keyword ? 'message.dns.records.no.matches' : 'message.dns.records.empty') }}</template>

        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'contents'">
            <a-tag class="dns-record-content" v-for="(item, idx) in record.contents" :key="idx">{{ item }}</a-tag>
          </template>
          <template v-if="column.dataIndex === 'ttl'">
            {{ record.ttl }}
          </template>
          <template v-if="column.key === 'actions' && canDelete">
            <span v-if="isApexNs(record)">{{ $t('label.dns.record.apex.ns.protected') }}</span>
            <tooltip-button
              v-else
              tooltipPlacement="bottom"
              :tooltip="$t('label.delete')"
              type="primary"
              :danger="true"
              icon="delete-outlined"
              @onClick="deleteTarget = record" />
          </template>
        </template>
      </detail-tab-table>
    </a-spin>
    <detail-tab-pagination
      v-if="!loadError || total > 0"
      class="dns-records-pagination"
      :current="page"
      :page-size="pageSize"
      :total="total"
      :show-total="count => `${$t('label.total')} ${count} ${$t('label.items')}`"
      :page-size-options="['10', '20', '40', '80', '100']"
      show-size-changer
      @change="changePage" />

    <MoldDialog
      v-if="deleteTarget && canDelete"
      size="sm"
      form-actions
      focus-guard
      :title="$t('label.dns.delete.record')"
      @cancel="closeDeleteForm">
      <p>{{ $t('message.dns.record.delete.rrset', { name: deleteTarget.name, type: deleteTarget.type, count: deleteTarget.contents?.length || 0 }) }}</p>
      <a-alert v-if="jobError" :message="jobError" type="error" show-icon />
      <MoldDialogActions :busy="deleteLoading">
        <a-button @click="closeDeleteForm">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" danger :loading="deleteLoading" :disabled="jobTracking === 'unconfirmed'" @click="handleDeleteRecord">
          {{ $t(jobId ? 'label.dns.job.recheck' : 'label.delete') }}
        </a-button>
      </MoldDialogActions>
    </MoldDialog>

    <MoldDialog
      v-if="showAddForm && canCreate"
      size="md"
      form-actions
      focus-guard
      :title="$t('label.dns.create.record')"
      @cancel="handleCloseCreateForm">
      <CreateDnsRecord
        :resource="resource"
        @close-action="handleCloseCreateForm" />
    </MoldDialog>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI } from '@/api'
import { dnsJob } from './dnsJob'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipButton from '@/components/widgets/TooltipButton'
import CreateDnsRecord from '@/views/network/dns/CreateDnsRecord'

export default {
  mixins: [listRefreshMixin(['fetchData'], {
    active: vm => vm.canRead && vm.$route.params.id === vm.resource.id,
    scopeKey: vm => [vm.$route.params.id, vm.resource.id, vm.$store.getters.userInfo?.id,
      vm.$store.state?.user?.token, vm.canRead, vm.keyword, vm.page, vm.pageSize]
  }), dnsJob],
  name: 'DnsRecordsTab',
  components: {
    MoldDialog,
    MoldDialogActions,
    TooltipButton,
    CreateDnsRecord
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
      fetchLoading: false,
      deleteTarget: null,
      showAddForm: false,
      searchText: '',
      keyword: '',
      page: 1,
      pageSize: 10,
      total: 0,
      loadError: false,
      records: [],
      columns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          title: this.$t('label.type'),
          dataIndex: 'type'
        },
        {
          title: this.$t('label.contents'),
          dataIndex: 'contents'
        },
        {
          title: this.$t('label.ttl') + ' (' + this.$t('label.seconds') + ')',
          dataIndex: 'ttl'
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ]
    }
  },
  computed: {
    deleteLoading () { return this.jobLoading },
    canRead () { return !!this.$store.getters.apis?.listDnsRecords },
    canCreate () { return this.canRead && !!this.$store.getters.apis?.createDnsRecord },
    canDelete () { return this.canRead && !!this.$store.getters.apis?.deleteDnsRecord },
    visibleColumns () { return this.canDelete ? this.columns : this.columns.filter(column => column.key !== 'actions') }
  },
  created () {
    this.fetchData()
  },
  watch: {
    'resource.id' () {
      this.page = 1
      this.total = 0
      this.fetchData()
    }
  },
  methods: {
    searchRecords (value) {
      const keyword = value?.trim() || ''
      if (keyword !== this.keyword) this.total = 0
      this.keyword = keyword
      this.page = 1
      this.fetchData()
    },
    changePage (page, size) {
      this.page = size === this.pageSize ? page : 1
      this.pageSize = size
      this.fetchData()
    },
    async fetchData () {
      if (!this.canRead || !this.resource.id || this.$route.params.id !== this.resource.id) return
      const listRequest = this.listRequestToken('fetchData')
      const params = { dnszoneid: this.resource.id, page: this.page, pagesize: this.pageSize }
      if (this.keyword) params.keyword = this.keyword
      this.fetchLoading = !listRequest.loaded
      if (!listRequest.loaded) {
        this.records = []
        this.loadError = false
        this.listRefreshFailed = false
      }
      try {
        const json = await getAPI('listDnsRecords', params, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        const response = json?.listdnsrecordsresponse
        const rows = response?.dnsrecord || []
        const total = Number(response?.count ?? rows.length)
        if (!response || response.errorcode || !Array.isArray(rows) || !Number.isSafeInteger(total) || total < rows.length) {
          throw new Error('Invalid DNS record list response')
        }
        this.total = total
        const lastPage = Math.max(1, Math.ceil(total / this.pageSize))
        if (this.page > lastPage) {
          this.page = lastPage
          return this.fetchData()
        }
        this.records = rows
        this.loadError = false
      } catch (_) {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.loadError = !listRequest.loaded
        this.listRefreshFailed = true
      } finally {
        if (this.isListRequestCurrent('fetchData', listRequest)) this.fetchLoading = false
      }
    },
    isApexNs (record) {
      if (record.type !== 'NS') return false
      const name = record.name?.trim().toLowerCase().replace(/\.$/, '')
      return name === '@' || name === this.resource.name?.trim().toLowerCase().replace(/\.$/, '')
    },
    closeDeleteForm () {
      this.deleteTarget = null
      this.jobId = ''
      this.jobTracking = ''
      this.jobError = ''
    },
    handleDeleteRecord () {
      const record = this.deleteTarget
      if (!record || !this.canDelete || this.jobTracking === 'unconfirmed' || this.isApexNs(record)) return
      const params = { dnszoneid: this.resource.id, name: record.name, type: record.type }
      return this.submitDnsJob('deleteDnsRecord', params, 'label.dns.delete.record', record.name, () => {
        this.closeDeleteForm()
        this.fetchData()
      })
    },
    handleCloseCreateForm (payload) {
      this.showAddForm = false
      if (payload?.saved) this.fetchData()
    }
  }
}
</script>

<style scoped>
.dns-records-tab { min-width: 0; }
.dns-records-toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 16px; }
.dns-records-toolbar :deep(.ant-input-search) { width: min(100%, 250px); margin-inline-start: auto; }
.dns-records-pagination { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px; margin-top: 16px; }
.dns-records-notice { margin-bottom: 16px; }
.dns-record-content { max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
@media (max-width: 767px) {
  .dns-records-toolbar :deep(.ant-input-search) { flex: 1 0 100%; width: 100%; }
}
</style>
