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
    <a-alert v-if="readFailed" type="error" show-icon :message="$t('error.fetching.data')">
      <template #action><a-button size="small" :disabled="disabled" @click="fetchStores">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-input-search
      class="top-spaced"
      :placeholder="$t('label.search')"
      v-model:value="searchQuery"
      style="margin-bottom: 10px;"
      :disabled="disabled || loading || readFailed"
      @search="search"
      v-focus="true" />
    <a-table
      size="small"
      style="overflow-y: auto"
      :loading="loading"
      :columns="columns"
      :dataSource="imageStores"
      :pagination="false"
      :rowKey="record => record.id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          {{ record.name }}
        </template>
        <template v-if="column.key === 'disksizetotal'">
          <span v-if="record.disksizetotal">{{ $bytesToHumanReadableSize(record.disksizetotal) }}</span>
        </template>
        <template v-if="column.key === 'disksizeused'">
          <span v-if="record.disksizeused">{{ $bytesToHumanReadableSize(record.disksizeused) }}</span>
        </template>
        <template v-if="column.key === 'disksizefree'">
          <span v-if="record.disksizetotal && record.disksizeused">{{ $bytesToHumanReadableSize(record.disksizetotal * 1 - record.disksizeused * 1) }}</span>
        </template>
        <template v-if="column.key === 'select'">
          <a-radio
            :disabled="disabled"
            :aria-label="record.name || record.id"
            :checked="record.id === selectedId"
            @click="$emit('select', record.id)" />
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
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </a-pagination>
  </div>
</template>

<script>
import { listMigrationStores, migrationDestinations } from '@/utils/secondaryStorageMigration'

export default {
  name: 'ImageStoreSelector',
  props: {
    sourceImageStore: { type: Object, required: true },
    selectedId: { type: String, default: null },
    disabled: { type: Boolean, default: false }
  },
  emits: ['select', 'candidates'],
  data () {
    return { loading: false, readFailed: false, stores: [], searchQuery: '', keyword: '', page: 1, pageSize: 10 }
  },
  computed: {
    candidates () { return migrationDestinations(this.sourceImageStore, this.stores) },
    filtered () {
      return this.candidates.filter(row => [row.name, row.id, row.url].some(value => String(value || '').toLowerCase().includes(this.keyword)))
    },
    imageStores () {
      const start = (this.page - 1) * this.pageSize
      return this.filtered.slice(start, start + this.pageSize)
    },
    totalCount () { return this.filtered.length },
    columns () {
      return [
        { key: 'name', title: this.$t('label.storageid') },
        { key: 'disksizetotal', title: this.$t('label.disksizetotal') },
        { key: 'disksizeused', title: this.$t('label.disksizeused') },
        { key: 'disksizefree', title: this.$t('label.disksizefree') },
        { key: 'select', title: this.$t('label.select') }
      ]
    }
  },
  watch: {
    candidates (value) { this.$emit('candidates', value.map(row => row.id)) }
  },
  created () { this.fetchStores() },
  methods: {
    async fetchStores () {
      if (this.loading || this.disabled) return
      this.loading = true
      this.readFailed = false
      try {
        this.stores = await listMigrationStores()
        this.page = 1
      } catch {
        this.readFailed = true
        this.stores = []
      } finally {
        this.loading = false
      }
    },
    search (value) {
      this.keyword = value.trim().toLowerCase()
      this.page = 1
    },
    handleChangePage (page, pageSize) {
      this.page = pageSize === this.pageSize ? page : 1
      this.pageSize = pageSize
    }
  }
}
</script>

<style scoped lang="scss">
  .top-spaced {
    margin-top: 20px;
  }
  .table-tooltip-icon {
    color: rgba(0,0,0,.45);
  }
</style>
