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
  <div class="detail-tab-toolbar resource-table-toolbar">
    <slot />
    <div v-if="refreshable || $slots.actions" class="resource-table-toolbar__actions">
      <a-button v-if="refreshable" :loading="loading" @click="$emit('refresh')">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
      <slot name="actions" />
    </div>
    <a-input-search
      v-if="searchable"
      :value="search"
      :placeholder="searchLabel || $t('label.search')"
      :aria-label="searchLabel || $t('label.search')"
      allow-clear
      @update:value="$emit('update:search', $event)"
      @search="$emit('search', $event)" />
  </div>
</template>

<script>
import { ReloadOutlined } from '@ant-design/icons-vue'

export default {
  name: 'ResourceTableToolbar',
  components: { ReloadOutlined },
  props: {
    refreshable: { type: Boolean, default: true },
    searchable: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    search: { type: String, default: '' },
    searchLabel: { type: String, default: '' }
  },
  emits: ['refresh', 'search', 'update:search']
}
</script>

<style scoped>
.resource-table-toolbar__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}
.resource-table-toolbar :deep(.ant-input-search) {
  width: min(100%, 280px);
  margin-inline-start: auto;
}
@media (max-width: 768px) {
  .resource-table-toolbar :deep(.ant-input-search) { width: 100%; }
}
</style>
