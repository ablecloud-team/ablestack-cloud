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
  <div class="deployment-storage-selection">
    <div class="storage-toolbar">
      <strong>{{ title }}</strong>
      <a-button @click="fetchPools" :loading="loading"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="keyword" :placeholder="$t('label.search')" allow-clear style="max-width: 240px" />
    </div>
    <a-radio :checked="!value.id" @change="select(null)">{{ $t('label.vm.storage.auto') }}</a-radio>
    <a-table
      :columns="columns"
      :data-source="filteredPools"
      row-key="id"
      size="small"
      :pagination="false"
      :loading="loading"
      :scroll="{ x: 760 }"
      :row-selection="rowSelection">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.name }}<div class="storage-meta">{{ record.type }} · {{ record.scope }}</div></template>
        <template v-else-if="column.key === 'requiredbytes'">
          {{ bytes(record.requiredbytes) }}
          <div v-if="record.requiredbytes == null" class="storage-meta">{{ $t('message.vm.storage.size.pending') }}</div>
          <div v-else-if="!record.suitable" class="storage-meta storage-warning">{{ $t(record.unsuitablereason === 'iops' ? 'message.vm.storage.iops.insufficient' : 'message.vm.storage.insufficient') }}</div>
        </template>
        <template v-else>{{ bytes(record[column.key]) }}</template>
      </template>
      <template #emptyText>{{ verified ? $t('message.vm.storage.empty') : $t('message.vm.storage.wait') }}</template>
    </a-table>
    <a-alert v-if="error" type="error" show-icon :message="$t('message.vm.storage.fetch.failed')" />
    <a-alert v-else-if="automaticUnavailable" type="warning" show-icon :message="$t('message.vm.storage.auto.unavailable')" />
    <a-alert v-else-if="value.id && verified && !valid && !selectedSizePending" type="warning" show-icon :message="$t('message.vm.storage.reselect')" />
    <p class="storage-meta">{{ $t('message.vm.storage.capacity') }}<span v-if="updatedAt"> · {{ updatedAt }}</span></p>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'

export default {
  name: 'DeploymentStorageSelection',
  components: { ReloadOutlined },
  props: {
    title: { type: String, required: true },
    query: { type: Object, required: true },
    value: { type: Object, default: () => ({}) }
  },
  emits: ['update:value'],
  data () {
    return { pools: [], keyword: '', loading: false, verified: false, error: false, valid: true, updatedAt: '', requestSequence: 0, refreshTimer: null }
  },
  computed: {
    // Validation updates replace selection objects. Only actual query changes trigger a fetch.
    queryKey () { return JSON.stringify(this.query) },
    automaticUnavailable () {
      return !this.value.id && this.verified && !this.error && !this.valid && this.pools.every(pool => pool.requiredbytes != null)
    },
    selectedSizePending () { return this.pools.find(pool => pool.id === this.value.id)?.requiredbytes == null },
    columns () {
      return [
        { key: 'name', title: this.$t('label.storage'), width: 150 },
        { key: 'disksizetotal', title: this.$t('label.vm.storage.total') },
        { key: 'disksizeallocated', title: this.$t('label.vm.storage.allocated') },
        { key: 'physicalavailable', title: this.$t('label.vm.storage.physical') },
        { key: 'allocationavailable', title: this.$t('label.vm.storage.allocatable') },
        { key: 'requiredbytes', title: this.$t('label.vm.storage.required') }
      ]
    },
    filteredPools () { return this.pools.filter(pool => pool.name.toLowerCase().includes(this.keyword.toLowerCase())) },
    rowSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.value.id ? [this.value.id] : [],
        onChange: (keys, rows) => this.select(rows[0]),
        getCheckboxProps: record => ({ disabled: record.requiredbytes != null && !record.suitable })
      }
    }
  },
  watch: {
    queryKey: {
      immediate: true,
      handler () {
        clearTimeout(this.refreshTimer)
        this.requestSequence++
        this.verified = false
        this.valid = false
        this.$emit('update:value', { ...this.value, valid: false })
        this.refreshTimer = setTimeout(() => this.fetchPools(), 200)
      }
    }
  },
  beforeUnmount () { clearTimeout(this.refreshTimer); this.requestSequence++ },
  methods: {
    bytes (value) {
      if (value == null || !Number.isFinite(Number(value))) return '—'
      const number = Number(value)
      if (number < 1024 ** 4) return (number / 1024 ** 3).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' GiB'
      return (number / 1024 ** 4).toLocaleString(undefined, { maximumFractionDigits: 2 }) + ' TiB'
    },
    select (pool) {
      this.valid = pool ? pool.suitable === true : this.verified && !this.loading && !this.error && this.pools.some(candidate => candidate.suitable === true)
      this.$emit('update:value', pool ? { id: pool.id, name: pool.name, valid: this.valid } : { valid: this.valid })
    },
    async fetchPools () {
      if (!this.query.zoneid || (!this.query.templateid && !this.query.snapshotid) || !this.query.serviceofferingid || !this.query.hypervisor || !Number.isInteger(this.query.diskcount || 1) || (this.query.diskcount != null && this.query.diskcount < 1)) { this.loading = false; return }
      const sequence = ++this.requestSequence
      this.loading = true
      this.error = false
      try {
        const result = await getAPI('listDeploymentStoragePools', Object.fromEntries(Object.entries(this.query).filter(([, value]) => value != null && value !== '')))
        if (sequence !== this.requestSequence) return
        this.pools = result.listdeploymentstoragepoolsresponse.deploymentstoragepool || []
        this.verified = true
        this.updatedAt = new Date().toLocaleTimeString()
        if (this.value.id) {
          const selected = this.pools.find(pool => pool.id === this.value.id)
          this.valid = selected?.suitable === true
          this.$emit('update:value', { ...this.value, name: selected?.name || this.value.name, valid: this.valid })
        } else {
          this.valid = this.pools.some(pool => pool.suitable === true)
          this.$emit('update:value', { valid: this.valid })
        }
      } catch (error) {
        if (sequence !== this.requestSequence) return
        this.error = true
        this.valid = false
        this.$emit('update:value', { ...this.value, valid: false })
      } finally {
        if (sequence === this.requestSequence) this.loading = false
      }
    }
  }
}
</script>

<style lang="less" scoped>
.deployment-storage-selection { margin: 16px 0; }
.storage-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.storage-toolbar strong { margin-right: auto; }
.storage-meta { font-size: 12px; opacity: .75; margin-top: 4px; }
.storage-warning { color: var(--warning-color, #d48806); opacity: 1; }
.deployment-storage-selection :deep(.ant-table-thead > tr > th) {
  color: var(--ui-text-secondary) !important;
  background: var(--ui-bg-elevated) !important;
  border-color: var(--ui-border);
}
.ant-alert { margin-top: 10px; }
</style>
