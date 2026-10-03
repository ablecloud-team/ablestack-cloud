// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div class="volume-storage-capacity" :class="{ 'capacity-summary': summary }">
    <div class="capacity-metrics">
      <div v-for="metric in metrics" :key="metric.key" class="capacity-metric" :class="{ 'capacity-available': metric.key === 'available' }">
        <a-tooltip v-if="metric.key === 'allocated'" :title="$t('message.storage.allocated.meaning')"><span class="capacity-label">{{ $t(metric.label) }} <info-circle-outlined /></span></a-tooltip>
        <span v-else class="capacity-label">{{ $t(metric.label) }}</span>
        <strong class="capacity-value">{{ format(capacities[metric.key]) }}</strong>
      </div>
    </div>
    <p v-if="summary" class="capacity-caption" :title="$t('message.volume.storage.capacity.tooltip')">{{ $t('message.volume.storage.capacity') }}</p>
  </div>
</template>

<script>
import { storagePoolCapacities, formatStorageCapacity } from '@/utils/storagePoolCapacity'

export default {
  name: 'StoragePoolCapacity',
  props: {
    pool: { type: Object, default: () => ({}) },
    summary: { type: Boolean, default: false }
  },
  computed: {
    capacities () { return storagePoolCapacities(this.pool) },
    metrics () {
      return [
        { key: 'total', label: 'label.vm.storage.total' },
        { key: 'allocated', label: 'label.vm.storage.allocated' },
        { key: 'available', label: 'label.volume.storage.remaining' }
      ]
    }
  },
  methods: { format: formatStorageCapacity }
}
</script>

<style lang="less" scoped>
.capacity-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  font-variant-numeric: tabular-nums;
}
.capacity-label {
  display: block;
  margin-bottom: 2px;
  font-size: 12px;
  color: var(--ui-text-secondary);
}
.capacity-value {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-text-primary);
  overflow-wrap: anywhere;
}
.capacity-available .capacity-value { color: var(--ui-success-text); }
.capacity-summary {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid var(--ui-border);
  border-radius: 3px;
  background: var(--ui-bg-elevated);
}
.capacity-caption {
  margin: 7px 0 0;
  color: var(--ui-text-secondary);
  font-size: 12px;
}
@media (max-width: 480px) {
  .capacity-metrics { gap: 7px; }
  .capacity-summary { padding: 9px; }
}
</style>
