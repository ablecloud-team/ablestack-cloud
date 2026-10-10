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
  <a-card class="creation-source-configuration" :title="$t('label.creation.source.configuration')" size="small" data-testid="creation-source-configuration">
    <a-form-item :label="$t('label.creation.source.configuration.mode')">
      <a-radio-group :value="value.mode" @change="update({ mode: $event.target.value })">
        <a-radio value="inherit" :disabled="source.requiresconfiguration">{{ $t('label.creation.source.configuration.inherit') }}</a-radio>
        <a-radio value="manual">{{ $t('label.creation.source.configuration.manual') }}</a-radio>
      </a-radio-group>
    </a-form-item>
    <template v-if="value.mode === 'manual'">
      <a-form-item :label="$t('label.creation.source.os.declared')">
        <a-select :value="value.ostypeid || ''" show-search :loading="loading" :filter-option="filterOs" @change="selectOs" :aria-label="$t('label.creation.source.os.declared')">
          <a-select-option value="">{{ $t('label.creation.source.os.unspecified') }}</a-select-option>
          <a-select-option v-for="os in operatingSystems" :key="os.id" :value="os.id">{{ os.description }}</a-select-option>
        </a-select>
        <div class="source-meta">{{ $t('message.creation.source.os.declared') }}</div>
        <a-alert v-if="error" type="warning" show-icon :message="$t('message.creation.source.os.fetch.failed')" />
      </a-form-item>
      <a-form-item :label="$t('label.boottype')">
        <a-radio-group :value="value.boottype" @change="update({ boottype: $event.target.value, bootmode: 'LEGACY' })">
          <a-radio value="BIOS">BIOS</a-radio><a-radio value="UEFI">UEFI</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item v-if="value.boottype === 'UEFI'" :label="$t('label.bootmode')"><a-tag>LEGACY</a-tag></a-form-item>
      <a-form-item :label="$t('label.creation.source.root.bus')">
        <a-select :value="value.rootbus" @change="update({ rootbus: $event })" :aria-label="$t('label.creation.source.root.bus')">
          <a-select-option v-for="bus in ['os-default', 'virtio', 'scsi', 'sata', 'ide']" :key="bus" :value="bus">{{ bus === 'os-default' ? $t('label.creation.source.controller.default') : bus }}</a-select-option>
        </a-select>
      </a-form-item>
    </template>
    <a-alert type="info" show-icon :message="$t('message.creation.source.configuration')" />
  </a-card>
</template>
<script>
import { getAPI } from '@/api'
export default {
  props: { source: { type: Object, required: true }, value: { type: Object, required: true } },
  emits: ['update:value'],
  data () { return { operatingSystems: [], loading: false, error: false } },
  mounted () { this.fetchOperatingSystems() },
  methods: {
    update (changes) { this.$emit('update:value', { ...this.value, ...changes }) },
    selectOs (id) { this.update({ ostypeid: id || undefined, osname: this.operatingSystems.find(os => os.id === id)?.description }) },
    filterOs (input, option) { return (this.operatingSystems.find(os => os.id === option.value)?.description || this.$t('label.creation.source.os.unspecified')).toLowerCase().includes(input.toLowerCase()) },
    async fetchOperatingSystems () {
      this.loading = true
      try {
        const entries = []
        for (let page = 1; ; page++) {
          const result = (await getAPI('listOsTypes', { page, pagesize: 500 })).listostypesresponse || {}
          const items = result.ostype || []
          entries.push(...items)
          if (items.length === 0 || entries.length >= (result.count || items.length)) break
        }
        this.operatingSystems = entries
      } catch (error) { this.error = true } finally { this.loading = false }
    }
  }
}
</script>
<style lang="less" scoped>
.creation-source-configuration { margin: 16px 0; color: var(--ui-text); background: var(--ui-bg-elevated); border-color: var(--ui-border); }
:deep(.ant-card-head), :deep(.ant-form-item-label > label), :deep(.ant-radio-wrapper) { color: var(--ui-text); }
:deep(.ant-select) { width: 100%; }
.source-meta { color: var(--ui-text-secondary); font-size: 12px; margin-top: 8px; overflow-wrap: anywhere; }
</style>
