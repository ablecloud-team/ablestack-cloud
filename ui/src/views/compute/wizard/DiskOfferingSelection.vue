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
  <div class="disk-offering-selection">
    <a-alert v-if="error" type="error" show-icon :message="$t('message.disk.offering.lookup.failed')" :description="error" />
    <a-button v-if="error" :loading="loading" style="margin: 8px 0" @click="$emit('retry')">{{ $t('label.retry') }}</a-button>
    <a-input-search
      style="width: 25vw;float: right;margin-bottom: 10px; z-index: 8"
      :placeholder="$t('label.search')"
      v-model:value="filter"
      @search="handleSearch" />
    <a-table
      :loading="loading"
      :columns="columns"
      :dataSource="tableSource"
      :pagination="false"
      :rowSelection="rowSelection"
      :customRow="onClickRow"
      size="middle"
      :scroll="{ y: 225 }">
      <template #headerCell="{ column }">
        <template v-if="column.key === 'diskSize'"><hdd-outlined /> {{ $t('label.disksize') }}</template>
        <template v-if="column.key === 'iops'"><rocket-outlined /> {{ $t('label.minmaxiops') }}</template>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.name }}<AllocationSummary :resource="record.allocationResource" compact /></template>
        <template v-if="column.key === 'diskSize'">
          <div v-if="record.isCustomized">{{ $t('label.iscustomized') }}</div>
          <div v-else-if="record.diskSize">{{ record.diskSize }} GB</div>
          <div v-else>-</div>
        </template>
        <template v-if="column.key === 'iops'">
          <span v-if="record.miniops && record.maxiops">{{ record.miniops }} - {{ record.maxiops }}</span>
          <span v-else-if="record.miniops">{{ record.miniops }}</span>
          <span v-else-if="record.maxiops">{{ record.maxiops }}</span>
          <span v-else>-</span>
        </template>
      </template>
    </a-table>
    <div style="display: block; text-align: right;">
      <a-pagination
        size="small"
        :current="options.page"
        :pageSize="options.pageSize"
        :total="rowCountNum"
        :disabled="loading"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="['10', '20', '40', '80', '100', '200']"
        @change="onChangePage"
        showSizeChanger>
        <template #buildOptionText="props"><span>{{ props.value }} / {{ $t('label.page') }}</span></template>
      </a-pagination>
    </div>
  </div>
</template>

<script>
import AllocationSummary from '@/components/view/AllocationSummary'
export default {
  components: { AllocationSummary },
  name: 'DiskOfferingSelection',
  props: {
    items: { type: Array, default: () => [] },
    rowCount: { type: Number, default: 0 },
    value: { type: String, default: '' },
    loading: Boolean,
    error: { type: String, default: '' },
    // DeployVM provides a complete, scope-qualified collection. Other consumers
    // retain their server pagination contract and use the API's total count.
    allItems: Boolean,
    scopeKey: { type: String, default: '' },
    preFillContent: { type: Object, default: () => ({}) },
    zoneId: { type: String, default: '' },
    isIsoSelected: Boolean,
    isRootDiskOffering: Boolean
  },
  data () {
    return {
      filter: '',
      columns: [
        { key: 'name', dataIndex: 'name', title: this.$t('label.diskoffering'), width: '40%' },
        { key: 'diskSize', dataIndex: 'disksize', width: '30%' },
        { key: 'iops', dataIndex: 'iops', width: '30%' }
      ],
      selectedRowKeys: [],
      oldZoneId: null,
      prefillApplied: false,
      options: { page: 1, pageSize: 10, keyword: null },
      diskSelected: {}
    }
  },
  computed: {
    eligibleItems () {
      const kvdo = this.preFillContent.kvdoenable
      return this.items.filter(item => kvdo === undefined || !!item.kvdoenable === !!kvdo)
    },
    filteredItems () {
      const keyword = (this.options.keyword || '').trim().toLowerCase()
      return this.allItems && keyword
        ? this.eligibleItems.filter(item => [item.name, item.displaytext].some(text => String(text || '').toLowerCase().includes(keyword)))
        : this.eligibleItems
    },
    rowCountNum () {
      return this.allItems ? this.filteredItems.length : Math.max(Number(this.rowCount) || 0, this.eligibleItems.length)
    },
    dataItems () {
      const start = (this.options.page - 1) * this.options.pageSize
      const rows = this.allItems ? this.filteredItems.slice(start, start + this.options.pageSize) : [...this.filteredItems]
      // "No selection" is a choice, not an offering; it does not consume a slot
      // or inflate the API count, and is never added to required ROOT selectors.
      return this.options.page === 1 && !this.isIsoSelected && !this.isRootDiskOffering
        ? [{ id: '0', name: this.$t('label.noselect') }, ...rows] : rows
    },
    tableSource () {
      return this.dataItems.map(item => ({
        allocationResource: item,
        key: item.id,
        name: item.name,
        diskSize: item.disksize,
        miniops: item.miniops,
        maxiops: item.maxiops,
        isCustomized: item.iscustomized
      }))
    },
    rowSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.selectedRowKeys,
        onChange: this.onSelectRow,
        getCheckboxProps: () => ({ disabled: this.loading || !!this.error })
      }
    }
  },
  watch: {
    value: { immediate: true, handler (value) { this.selectedRowKeys = value ? [value] : this.optionalKeys(); this.syncSelection() } },
    rowCountNum () { this.options.page = Math.min(this.options.page, Math.max(1, Math.ceil(this.rowCountNum / this.options.pageSize))) },
    items: { immediate: true, handler () { this.syncSelection() } },
    loading (loading) { if (!loading) this.syncSelection() },
    'preFillContent.kvdoenable' () { this.options.page = 1; this.syncSelection() },
    scopeKey () { this.resetScope() },
    zoneId () { this.resetScope() }
  },
  methods: {
    optionalKeys () { return !this.isIsoSelected && !this.isRootDiskOffering ? ['0'] : [] },
    resetScope () {
      this.filter = ''; this.options.page = 1; this.options.keyword = null
      this.diskSelected = {}; this.prefillApplied = false; this.selectedRowKeys = this.optionalKeys()
    },
    syncSelection () {
      if (this.loading || this.error) return
      if (this.value) {
        const selected = this.eligibleItems.find(item => item.id === this.value)
        if (this.allItems && !selected) { this.onSelectRow(['0']); return }
        this.selectedRowKeys = [this.value]
        if (selected && this.diskSelected.id !== selected.id) this.onSelectRow([selected.id])
        return
      }
      if (!this.prefillApplied && this.preFillContent.diskofferingid) {
        const selected = this.eligibleItems.find(item => item.id === this.preFillContent.diskofferingid)
        if (selected) this.onSelectRow([selected.id])
      }
      this.prefillApplied = true
    },
    onSelectRow (keys) {
      if (this.loading || this.error) return
      const id = keys[0]
      const selected = this.eligibleItems.find(item => item.id === id)
      if (id !== '0' && !selected) return
      this.diskSelected = selected || {}
      this.selectedRowKeys = id === '0' ? this.optionalKeys() : [id]
      this.prefillApplied = true
      this.$emit('select-disk-offering-item', id)
      this.$emit('on-selected-disk-size', this.diskSelected)
      this.$emit('on-selected-root-disk-size', this.diskSelected)
    },
    handleSearch (value) {
      this.filter = value; this.options.page = 1; this.options.keyword = value
      if (!this.allItems) this.$emit('handle-search-filter', { ...this.options })
    },
    onChangePage (page, pageSize) {
      this.options.page = pageSize !== this.options.pageSize ? 1 : page
      this.options.pageSize = pageSize
      if (!this.allItems) this.$emit('handle-search-filter', { ...this.options })
    },
    onChangePageSize (page, pageSize) { this.onChangePage(page, pageSize) },
    onClickRow (record) { return { onClick: () => this.onSelectRow([record.key]) } }
  }
}
</script>

<style lang="less" scoped>
.ant-table-wrapper { margin: 2rem 0; }
:deep(.ant-table-tbody) > tr > td { cursor: pointer; }
</style>
