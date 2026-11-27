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
  <div style="margin-top: 10px;">
    <label>{{ $t('message.select.security.groups') }}</label>
    <a-input-search
      style="float: right; margin-bottom: 10px; z-index: 8;"
      :placeholder="$t('label.search')"
      v-model:value="filter"
      @search="handleSearch" />
    <a-alert
      v-if="fetchError && requireOwner"
      type="error"
      show-icon
      :message="fetchError" />
    <a-table
      :loading="loading || fetchLoading"
      :columns="columns"
      :dataSource="items"
      :rowKey="record => record.id"
      :pagination="false"
      :rowSelection="rowSelection"
      size="middle"
      :scroll="{ y: 225 }"
    ></a-table>

    <div style="display: block; text-align: right; margin-top: 30px">
      <a-pagination
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="rowCount"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="['10', '20', '40', '80', '100', '200']"
        @change="onChangePage"
        @showSizeChange="onChangePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import _ from 'lodash'

export default {
  name: 'SecurityGroupSelection',
  props: {
    value: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    zoneId: {
      type: String,
      default: () => ''
    },
    preFillContent: {
      type: Object,
      default: () => ({})
    },
    domainId: {
      type: String,
      default: () => ''
    },
    account: {
      type: String,
      default: () => ''
    },
    projectId: {
      type: String,
      default: () => ''
    },
    requireOwner: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      filter: '',
      fetchLoading: false,
      fetchError: '',
      fetchVersion: 0,
      columns: [
        {
          dataIndex: 'name',
          title: this.$t('label.security.groups'),
          width: '40%'
        },
        {
          dataIndex: 'description',
          title: this.$t('label.description'),
          width: '60%'
        }
      ],
      items: [],
      selectedRowKeys: this.preFillContent?.securitygroupids || this.value || [],
      page: 1,
      pageSize: 10,
      keyword: null,
      rowCount: 0
    }
  },
  computed: {
    ownerParams () {
      const projectid = this.projectId || (!this.requireOwner && this.$store.getters.project?.id)
      if (projectid) return { projectid }
      const domainid = this.domainId || (!this.requireOwner && this.$store.getters.userInfo?.domainid)
      const account = this.account || (!this.requireOwner && this.$store.getters.userInfo?.account)
      if (!domainid || !account) return this.requireOwner ? null : {}
      return { domainid, account, ignoreproject: this.requireOwner }
    },
    ownerKey () {
      return JSON.stringify(this.ownerParams)
    },
    rowSelection () {
      return {
        type: 'checkbox',
        selectedRowKeys: this.selectedRowKeys,
        preserveSelectedRowKeys: this.requireOwner,
        getCheckboxProps: () => ({ disabled: this.requireOwner && (this.fetchLoading || !!this.fetchError) }),
        onChange: (rows) => {
          this.$emit('select-security-group-item', rows)
        }
      }
    }
  },
  mounted () {
    this.fetchData()
  },
  beforeUnmount () {
    this.fetchVersion++
  },
  watch: {
    value (newValue, oldValue) {
      if (newValue && !_.isEqual(newValue, oldValue)) {
        this.selectedRowKeys = newValue
      }
    },
    ownerKey () {
      if (!this.requireOwner) {
        this.selectedRowKeys = []
        this.$emit('select-security-group-item', null)
      }
      this.page = 1
      this.keyword = null
      this.filter = ''
      this.fetchData()
    },
    loading () {
      if (!this.loading) {
        if (this.preFillContent?.securitygroupids) {
          this.selectedRowKeys = this.preFillContent?.securitygroupids
          this.$emit('select-security-group-item', this.preFillContent?.securitygroupids)
        } else {
          if (this.oldZoneId === this.zoneId) {
            return
          }
          this.oldZoneId = this.zoneId
          this.selectedRowKeys = []
          this.$emit('select-security-group-item', null)
        }
      }
    }
  },
  methods: {
    async fetchData () {
      const version = ++this.fetchVersion
      const owner = this.ownerParams
      this.fetchError = ''
      this.$emit('options-ready', false)
      if (!owner || !('listSecurityGroups' in (this.$store.getters.apis || {}))) {
        this.items = []
        this.rowCount = 0
        this.fetchLoading = false
        return
      }

      const params = { ...owner, page: this.page, pagesize: this.pageSize }
      if (this.keyword) params.keyword = this.keyword
      this.fetchLoading = true

      try {
        const json = await getAPI('listSecurityGroups', params, { preserveOnFailure: this.requireOwner })
        if (version !== this.fetchVersion) return
        const response = json.listsecuritygroupsresponse
        if (!response || response.errorcode) throw new Error(response?.errortext)
        this.items = [...(response.securitygroup || [])].sort((a, b) => a.name.localeCompare(b.name))
        this.rowCount = response.count || 0
        this.$emit('options-ready', true)
      } catch (error) {
        if (version !== this.fetchVersion) return
        this.items = []
        this.rowCount = 0
        this.fetchError = error?.response?.data?.listsecuritygroupsresponse?.errortext || this.$t('message.security.groups.load.failed')
      } finally {
        if (version === this.fetchVersion) this.fetchLoading = false
      }
    },
    handleSearch (value) {
      this.filter = value
      this.page = 1
      this.pageSize = 10
      this.keyword = this.filter
      this.fetchData()
    },
    onChangePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    onChangePageSize (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    }
  }
}
</script>

<style lang="less" scoped>
  .ant-table-wrapper {
    margin: 2rem 0;
  }
</style>
