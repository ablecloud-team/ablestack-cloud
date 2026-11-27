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
  <div>
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <a-space class="webhook-filter-toolbar" wrap>
      <a-button v-if="canAdd" type="primary" @click="openAddFilter">{{ $t('label.add.webhook.filter') }}</a-button>
      <a-input-search
        v-model:value="keywordInput"
        :placeholder="$t('label.webhook.filter.search')"
        :aria-label="$t('label.webhook.filter.search')"
        allow-clear
        @search="setKeyword"
        @change="clearEmptyKeyword" />
      <a-button @click="fetchData">{{ $t('label.refresh') }}</a-button>
      <a-button v-if="canDelete && selectedRowKeys.length" danger :disabled="!filterActionsReady" @click="confirmDelete(selectedFilters)">
        {{ $t('label.webhook.filter.delete.selected', { count: selectedRowKeys.length }) }}
      </a-button>
      <a-button v-if="canDelete && totalCount && !keyword" danger :disabled="!filterActionsReady" @click="confirmDelete(null)">
        {{ $t('label.webhook.filter.delete.all') }}
      </a-button>
    </a-space>
    <a-alert v-if="deleteFailures.length" type="warning" show-icon :message="$t('message.webhook.filter.delete.partial', { succeeded: deletedCount, failed: deleteFailures.length })">
      <template #description>
        <ul>
          <li v-for="item in deleteFailures" :key="item.id">{{ item.value }} · {{ item.reason }}</li>
        </ul>
      </template>
    </a-alert>
    <mold-dialog v-if="showAddFilter" :visible="true" :title="$t('label.add.webhook.filter')" size="md" form-actions @cancel="closeAddFilter">
      <p>{{ $t('message.webhook.filter.add') }}</p>
      <a-form
        :ref="addFormRef"
        :model="addFilterForm"
        :rules="addFormRules"
        @finish="addFilter"
        layout="vertical"
        class="add-filter-form">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item name="mode" ref="mode">
              <template #label>
                <tooltip-label :title="$t('label.mode')" :tooltip="addFilterApiParams.mode.description"/>
              </template>
              <a-radio-group v-model:value="addFilterForm.mode" button-style="solid">
                <a-radio-button value="include">{{ $t('label.include') }}</a-radio-button>
                <a-radio-button value="exclude">{{ $t('label.exclude') }}</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </a-col>

          <a-col :span="12">
            <a-form-item name="matchtype" ref="matchtype">
              <template #label>
                <tooltip-label :title="$t('label.matchtype')" :tooltip="addFilterApiParams.matchtype.description"/>
              </template>
              <a-select
                style="margin-left: 0"
                v-model:value="addFilterForm.matchtype"
                :placeholder="$t('label.matchtype')"
                allow-clear>
                <a-select-option value="exact">{{ $t('label.exact') }}</a-select-option>
                <a-select-option value="prefix">{{ $t('label.prefix') }}</a-select-option>
                <a-select-option value="suffix">{{ $t('label.suffix') }}</a-select-option>
                <a-select-option value="contains">{{ $t('label.contains') }}</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16" style="margin-top: 8px;">
          <a-col :span="24">
            <a-form-item name="value" ref="value">
              <template #label>
                <tooltip-label :title="$t('label.value')" :tooltip="addFilterApiParams.value.description"/>
              </template>
              <a-input v-model:value="addFilterForm.value" :placeholder="$t('label.webhook.filter.value')" />
            </a-form-item>
          </a-col>
        </a-row>

      </a-form>
      <mold-dialog-actions :busy="adding">
        <a-button @click="closeAddFilter">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="adding" @click="addFilter">{{ $t('label.add') }}</a-button>
      </mold-dialog-actions>
    </mold-dialog>
    <list-view
detail-tab
      :loading="tabLoading"
      :columns="columns"
      :items="filters"
      :actions="actions"
      :columnKeys="columnKeys"
      :explicitlyAllowRowSelection="canDelete"
      :selectedColumns="selectedColumnKeys"
      ref="listview"
      @update-selected-columns="updateSelectedColumns"
      @refresh="this.fetchData"
      @selection-change="updateSelectedRows"/>
    <detail-tab-pagination
      class="row-element"
      style="margin-top: 10px"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalCount"
      :showTotal="total => `${$t('label.showing')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="pageSizeOptions"
      @change="changePage"
      showSizeChanger
      showQuickJumper>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </detail-tab-pagination>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import { genericCompare } from '@/utils/sort.js'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import ListView from '@/components/view/ListView'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'

export default {
  name: 'WebhookFiltersTab',
  mixins: [listRefreshMixin(['fetchFilters'])],
  components: {
    TooltipLabel,
    ListView,
    MoldDialog,
    MoldDialogActions
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      required: true
    }
  },
  data () {
    return {
      tabLoading: false,
      adding: false,
      deleting: false,
      showAddFilter: false,
      keywordInput: '',
      keyword: '',
      deletedCount: 0,
      deleteFailures: [],
      columnKeys: ['value', 'type', 'mode', 'matchtype'],
      selectedColumnKeys: ['value', 'mode', 'matchtype'],
      selectedRowKeys: [],
      columns: [],
      filters: [],
      actions: [
        {
          api: 'deleteWebhookFilter',
          icon: 'delete-outlined',
          label: 'label.delete.webhook.filter',
          message: 'message.delete.webhook.filter',
          dataView: true,
          popup: true
        }
      ],
      page: 1,
      pageSize: 20,
      totalCount: 0
    }
  },
  computed: {
    canAdd () { return 'addWebhookFilter' in this.$store.getters.apis },
    canDelete () { return 'deleteWebhookFilter' in this.$store.getters.apis },
    filterActionsReady () {
      return !!this.resource.id && this.$route.params.id === this.resource.id &&
        this.$route.query.tab === 'filters' && !this.tabLoading && !this.listRefreshFailed && !this.deleting
    },
    selectedFilters () { return this.filters.filter(item => this.selectedRowKeys.includes(item.id)) },
    pageSizeOptions () {
      var sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    }
  },
  beforeCreate () {
    this.addFilterApiParams = this.$getApiParams('addWebhookFilter')
  },
  created () {
    this.updateColumns()
    this.pageSize = this.pageSizeOptions[0] * 1
    this.fetchData()
  },
  watch: {
    'resource.id' () {
      this.filters = []
      this.totalCount = 0
      this.selectedRowKeys = []
      this.page = 1
      this.keyword = ''
      this.keywordInput = ''
      this.deleteFailures = []
      this.showAddFilter = false
      this.fetchData()
    }
  },
  methods: {
    setKeyword (value) {
      const keyword = value?.trim() || ''
      if (keyword === this.keyword) return
      this.keyword = keyword
      this.page = 1
      this.fetchData()
    },
    clearEmptyKeyword (event) {
      if (!event.target.value) this.setKeyword('')
    },
    fetchData () {
      if ('listview' in this.$refs && this.$refs.listview) {
        this.$refs.listview.resetSelection()
      }
      this.fetchFilters()
    },
    fetchFilters () {
      const listRequest = this.listRequestToken('fetchFilters')

      if (!this.resource.id) {
        return
      }
      const params = {
        page: this.page,
        pagesize: this.pageSize,
        webhookid: this.resource.id,
        listall: true,
        ...(this.keyword ? { keyword: this.keyword } : {})
      }
      this.tabLoading = !listRequest.loaded
      return getAPI('listWebhookFilters', params).then(json => {
        if (!this.isListRequestCurrent('fetchFilters', listRequest)) return
        const response = json?.listwebhookfiltersresponse
        this.totalCount = response?.count || 0
        const lastPage = Math.max(1, Math.ceil(this.totalCount / this.pageSize))
        if (this.page > lastPage) {
          this.page = lastPage
          return this.fetchFilters()
        }
        this.filters = response?.webhookfilter || []
        this.tabLoading = false
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchFilters', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchFilters', listRequest)) return
        this.tabLoading = false
      })
    },
    changePage (page, pageSize) {
      const nextPage = this.pageSize === pageSize ? page : 1
      if (nextPage === this.page && pageSize === this.pageSize) return
      this.page = nextPage
      this.pageSize = pageSize
      this.fetchData()
    },
    updateSelectedColumns (key) {
      if (this.selectedColumnKeys.includes(key)) {
        this.selectedColumnKeys = this.selectedColumnKeys.filter(x => x !== key)
      } else {
        this.selectedColumnKeys.push(key)
      }
      this.updateColumns()
    },
    updateColumns () {
      this.columns = []
      for (var columnKey of this.columnKeys) {
        const key = columnKey
        if (!this.selectedColumnKeys.includes(key)) continue
        var title = this.$t('label.' + String(key).toLowerCase())
        this.columns.push({
          key: key,
          title: title,
          dataIndex: key,
          ...(['mode', 'matchtype'].includes(key) ? { customRender: ({ text }) => text ? this.$t('label.' + text.toLowerCase()) : '—' } : {}),
          sorter: (a, b) => { return genericCompare(a[key] || '', b[key] || '') }
        })
      }
      if (this.columns.length > 0) {
        this.columns[this.columns.length - 1].customFilterDropdown = true
      }
    },
    openAddFilter () {
      this.addFormRef = ref()
      this.addFilterForm = reactive({ mode: 'include', matchtype: 'exact', value: '' })
      this.addFormRules = { value: [{ required: true, whitespace: true, message: this.$t('message.error.required.input') }] }
      this.showAddFilter = true
    },
    closeAddFilter () {
      if (!this.adding) this.showAddFilter = false
    },
    async addFilter (event) {
      if (event && typeof event.preventDefault === 'function') event.preventDefault()
      if (this.adding || !this.canAdd || !this.resource.id) return
      const webhookid = this.resource.id
      this.adding = true
      try {
        await this.addFormRef.value.validate()
        const { mode, matchtype, value } = this.addFilterForm
        await postAPI('addWebhookFilter', { webhookid, mode, matchtype, value: value.trim() })
        if (this.resource.id !== webhookid) return
        this.showAddFilter = false
        this.$message.success(this.$t('message.success.add.webhook.filter'))
        this.fetchData()
      } catch (error) {
        if (this.resource.id === webhookid && !error.errorFields) this.$notifyError(error)
      } finally {
        this.adding = false
      }
    },
    updateSelectedRows (keys) {
      this.selectedRowKeys = keys
    },
    confirmDelete (items) {
      const all = items === null
      if (!this.canDelete || !this.filterActionsReady || (all ? !this.totalCount || this.keyword : !items.length)) return
      const webhookid = this.resource.id
      const count = all ? this.totalCount : items.length
      this.$confirm({
        title: this.$t(all ? 'label.webhook.filter.delete.all' : 'label.action.delete.webhook.filters'),
        content: this.$t(all ? 'message.webhook.filter.delete.confirm.all' : 'message.webhook.filter.delete.confirm.selected', { count, name: this.resource.name }),
        okText: this.$t('label.remove'),
        okType: 'danger',
        cancelText: this.$t('label.cancel'),
        onOk: () => this.filterActionsReady && this.resource.id === webhookid &&
          (!all || (!this.keyword && this.totalCount === count)) && this.deleteFilters(webhookid, items)
      })
    },
    async deleteFilters (webhookid, items) {
      if (this.deleting || !this.canDelete) return
      this.deleting = true
      this.deleteFailures = []
      try {
        if (items === null) {
          await postAPI('deleteWebhookFilter', { webhookid })
          this.$message.success(this.$t('message.success.clear.webhook.filters'))
        } else {
          const results = await Promise.allSettled(items.map(({ id }) => postAPI('deleteWebhookFilter', { id, webhookid })))
          if (this.resource.id !== webhookid) return
          this.deleteFailures = results.flatMap(({ status, reason }, index) => status === 'rejected'
            ? [{ ...items[index], reason: Object.values(reason?.response?.data || {}).find(value => value?.errortext)?.errortext || this.$t('message.request.failed') }]
            : [])
          this.deletedCount = results.length - this.deleteFailures.length
          if (!this.deleteFailures.length) this.$message.success(this.$t('message.success.delete'))
        }
      } catch (error) {
        if (this.resource.id === webhookid) this.$notifyError(error)
      } finally {
        this.deleting = false
        if (this.resource.id === webhookid) this.fetchData()
      }
    },
    execAction (action) {
      if (action.api === 'deleteWebhookFilter') this.confirmDelete([action.resource])
    }
  }
}
</script>

<style scoped>
.webhook-filter-toolbar {
  margin-bottom: 12px;
}
</style>
