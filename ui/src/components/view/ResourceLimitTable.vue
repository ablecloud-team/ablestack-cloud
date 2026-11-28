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
  <detail-tab-table
    size="small"
    row-key="key"
    :columns="columns"
    :data-source="displayRows"
    :loading="loading"
    :pagination="pagination"
    :scroll="{ x: canConfigure ? 760 : 470 }"
    @change="changePage">
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'label'">
        <table-overflow-text :text="resourceLabel(record)" />
        <a-tag v-if="record.tag" class="resource-limit-tag">{{ record.tag }}</a-tag>
      </template>
      <template v-else-if="column.key === 'used'">{{ formatValue(record.total) }}</template>
      <template v-else-if="column.key === 'available'">{{ formatValue(record.available) }}</template>
      <template v-else-if="column.key === 'saved'">{{ formatValue(record.saved ?? record.limit) }}</template>
      <a-input-number
        v-else-if="column.key === 'draft' && record.setting"
        :value="record.setting.draft"
        :aria-label="resourceLabel(record) + (record.tag ? ' ' + record.tag : '')"
        :disabled="!canEdit"
        :min="-1"
        :precision="0"
        class="resource-limit-input"
        @change="value => $emit('edit', record.setting, value)" />
      <template v-else-if="column.key === 'result' && record.setting?.status">
        <a-tag :color="{ saved: 'green', failed: 'red', unknown: 'orange' }[record.setting.status]">
          {{ $t('label.project.limit.' + record.setting.status) }}
        </a-tag>
        <p v-if="record.setting.detail" class="resource-limit-detail">{{ record.setting.detail }}</p>
      </template>
    </template>
  </detail-tab-table>
</template>

<script>
import TableOverflowText from '@/components/view/TableOverflowText'
import { projectLimitKey } from '@/utils/projectLimits'

// Resource counts use API response field prefixes; limits use numeric resource type IDs.
const usageNames = ['vm', 'ip', 'volume', 'snapshot', 'template', 'project', 'network', 'vpc', 'cpu',
  'memory', 'primarystorage', 'secondarystorage', 'backup', 'backupstorage', 'bucket', 'objectstorage', 'gpu']

export default {
  name: 'ResourceLimitTable',
  components: { TableOverflowText },
  emits: ['edit'],
  props: {
    resource: { type: Object, required: true },
    settings: { type: Array, default: () => [] },
    loading: Boolean,
    canConfigure: Boolean,
    canEdit: Boolean,
    project: Boolean
  },
  data () {
    return { current: 1, pageSize: 10 }
  },
  computed: {
    columns () {
      return [
        { key: 'label', title: this.$t('label.project.limit.resource'), width: 220 },
        { key: 'used', title: this.$t('label.used'), width: 90 },
        { key: 'available', title: this.$t('label.available'), width: 100 },
        { key: 'saved', title: this.$t('label.limit'), width: 110 },
        ...(this.canConfigure ? [
          { key: 'draft', title: this.$t('label.project.limit.requested'), width: 120 },
          { key: 'result', title: this.$t('label.project.limit.result') }
        ] : [])
      ]
    },
    pagination () {
      const current = Math.min(this.current, Math.ceil(this.displayRows.length / this.pageSize) || 1)
      return { current, pageSize: this.pageSize, showSizeChanger: true, pageSizeOptions: ['10', '20', '40', '80'] }
    },
    displayRows () {
      const byKey = new Map()
      usageNames.forEach((name, type) => {
        if (this.project && type === 5) return
        const { [name + 'total']: total, [name + 'available']: available, [name + 'limit']: limit } = this.resource
        if ([total, available, limit].some(value => value != null)) {
          const key = projectLimitKey(type, null)
          byKey.set(key, { key, type, total, available, limit })
        }
      })
      for (const entry of this.resource.taggedresources || []) {
        const type = Number(entry.resourcetype)
        if (!usageNames[type] || !entry.tag || (this.project && type === 5)) continue
        const key = projectLimitKey(type, entry.tag)
        const amount = value => type === 10 && value != null && Number(value) >= 0 ? this.$bytesToGiB(value) : value
        byKey.set(key, { key, type, tag: entry.tag, total: amount(entry.total), available: amount(entry.available), limit: amount(entry.limit) })
      }
      for (const setting of this.settings) {
        byKey.set(setting.key, { ...(byKey.get(setting.key) || setting), saved: setting.saved, setting })
      }
      return [...byKey.values()].sort((a, b) => a.type - b.type || (a.tag || '').localeCompare(b.tag || ''))
    }
  },
  watch: {
    'resource.id' () { this.current = 1 }
  },
  methods: {
    resourceLabel (row) {
      const usage = usageNames[row.type]
      if (usage) return this.$t('label.' + usage + 'limit')
      return row.name ? this.$t('label.max' + row.name.replaceAll('_', '')) : String(row.type)
    },
    formatValue (value) {
      return value === -1 || value === '-1' || value === 'Unlimited' ? this.$t('label.unlimited') : value ?? '—'
    },
    changePage (pagination) {
      this.current = pagination.current
      this.pageSize = pagination.pageSize
    }
  }
}
</script>

<style scoped>
.resource-limit-tag { margin-inline-start: 8px; max-width: 100%; white-space: normal; }
.resource-limit-input { width: 100%; }
.resource-limit-detail { margin: 4px 0 0; overflow-wrap: anywhere; }
</style>
