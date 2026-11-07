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
  <a-form class="form" v-ctrl-enter="handleSubmit">

    <p v-html="$t('message.select.bgp.peers')" />

    <a-alert v-if="failed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')">
      <template #action><a-button size="small" @click="fetchData">{{ $t('label.refresh') }}</a-button></template>
    </a-alert>
    <div v-if="loading" class="loading">
      <loading-outlined style="color: #1890ff;" />
    </div>

    <div class="form__item">
      <a-input-search
        style="margin-bottom: 10px;"
        :placeholder="$t('label.search')"
        v-model:value="filter"
        @search="handleSearch"
        v-focus="true" />
    </div>

    <div class="form__item">
      <a-table
        size="small"
        :loading="loading"
        :columns="columns"
        :dataSource="items"
        :rowKey="record => record.id || record.name"
        :pagination="{showSizeChanger: true, total, current: options.page, pageSize: options.pageSize}"
        :scroll="{ x: 380 }"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
        @change="handleTableChange"
        @handle-search-filter="handleTableChange"
        style="overflow-y: auto"/>
    </div>

    <div :span="24" class="action-button">
      <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
      <a-button :loading="submitting" :disabled="!canSubmit" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
    </div>

  </a-form>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'

export default {
  name: 'ChangeBgpPeersForNetwork',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  emits: ['close-action', 'refresh-data'],
  data () {
    return {
      items: [],
      total: 0,
      columns: [
        {
          dataIndex: 'asnumber',
          title: this.$t('label.asnumber'),
          sorter: (a, b) => genericCompare(a?.asnumber || '', b?.asnumber || '')
        },
        {
          dataIndex: 'ipaddress',
          title: this.$t('label.ipaddress'),
          sorter: (a, b) => genericCompare(a?.ipaddress || '', b?.ipaddress || '')
        },
        {
          dataIndex: 'ip6address',
          title: this.$t('label.ip6address'),
          sorter: (a, b) => genericCompare(a?.ip6address || '', b?.ip6address || '')
        }
      ],
      selectedRowKeys: [],
      options: {
        page: 1,
        pageSize: 10,
        keyword: ''
      },
      filter: '',
      loading: false,
      loaded: false,
      failed: false,
      submitting: false,
      request: 0,
      originalSelection: []
    }
  },
  created () {
    if (this.resource.bgppeers) {
      for (const bgppeer of this.resource.bgppeers) {
        this.selectedRowKeys.push(bgppeer.id)
      }
    }
    this.originalSelection = [...this.selectedRowKeys]
    this.fetchData()
  },
  computed: {
    canSubmit () {
      return this.loaded && !this.loading && !this.failed && !this.submitting && 'changeBgpPeersForNetwork' in this.$store.getters.apis &&
        (this.originalSelection.length !== this.selectedRowKeys.length || this.originalSelection.some(id => !this.selectedRowKeys.includes(id)))
    }
  },
  methods: {
    fetchData () {
      const request = ++this.request
      const resourceId = this.resource.id
      this.loading = true
      this.failed = false
      const scope = this.resource.projectid ? { projectid: this.resource.projectid } : { account: this.resource.account, domainid: this.resource.domainid }
      if (!this.resource.zoneid || (!scope.projectid && (!scope.account || !scope.domainid))) {
        this.failed = true
        this.loading = false
        return
      }
      return getAPI('listBgpPeers', {
        ...scope,
        keyword: this.options.keyword,
        zoneid: this.resource.zoneid
      }).then(response => {
        if (request !== this.request || resourceId !== this.resource.id) return
        const peers = response.listbgppeersresponse?.bgppeer || []
        this.items = peers.map(({ id, asnumber, ipaddress, ip6address }) => ({ id, asnumber, ipaddress, ip6address }))
        this.total = this.items.length
        this.loaded = true
      }).catch(() => {
        if (request === this.request) this.failed = true
      }).finally(() => {
        if (request === this.request) this.loading = false
      })
    },
    onSelectChange (selectedRowKeys) {
      this.selectedRowKeys = [...new Set([...this.selectedRowKeys.filter(id => !this.items.some(peer => peer.id === id)), ...selectedRowKeys])]
    },
    handleSearch (keyword) {
      this.filter = keyword
      this.options.keyword = keyword
      this.options.page = 1
      this.fetchData()
    },
    handleTableChange (pagination) {
      this.options.page = pagination.current
      this.options.pageSize = pagination.pageSize
    },
    closeAction () {
      this.$emit('close-action')
    },
    async handleSubmit () {
      if (!this.canSubmit) return
      this.submitting = true
      try {
        const response = await postAPI('changeBgpPeersForNetwork', {
          networkid: this.resource.id,
          bgppeerids: this.selectedRowKeys.join(',')
        })
        const result = await this.$pollJob({
          jobId: response.changebgppeersfornetworkresponse.jobid,
          successMessage: this.$t('message.success.change.bgp.peers'),
          errorMessage: this.$t('message.update.failed'),
          catchMessage: this.$t('error.fetching.async.job.result')
        })
        this.$emit('refresh-data')
        if (result.jobstatus === 1) this.closeAction()
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped lang="scss">
.form {
  min-width: 0;
}
</style>
