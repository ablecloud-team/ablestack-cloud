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
  <section class="configuration-table">
    <mold-list-table
      size="small"
      :columns="columns"
      :data-source="config"
      :expanded-row-keys="expandedRowKeys"
      :row-key="record => record.name"
      :pagination="false"
      :loading="loading"
      :scroll="{ x: 760 }"
      :scope="grouped ? String(grouped) : page"
      :context-actions="configurationActions"
      @update:expanded-row-keys="$emit('update:expandedRowKeys', $event)">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <div class="configuration-table-name">
            <button type="button" class="configuration-record-link" @click="$emit('show-setting', record)">
              <TableOverflowText :text="localizedText(record, 'displaytext') || record.name" />
            </button>
            <button v-if="grouped && record.parent && !record.attachedToParent" type="button" class="configuration-parent-link" @click="$emit('open-parent', record)">
              {{ $t('label.global.setting.parent') }} {{ record.parent }}
            </button>
          </div>
        </template>
        <template v-else-if="column.key === 'value' || column.key === 'defaultvalue'">
          <TableOverflowText :text="formatConfigurationValue(record, column.key)" />
        </template>
        <template v-else-if="column.key === 'apply'">
          {{ configurationApplyLabel(record) ? $t(configurationApplyLabel(record)) : '—' }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <slot name="actions" :record="record">
            <a-space size="small">
              <tooltip-button
                v-if="canUpdate"
                icon="edit-outlined"
                :tooltip="$t('label.edit')"
                @onClick="$emit('edit-setting', record)" />
              <tooltip-button
                v-if="canReset"
                icon="reload-outlined"
                :tooltip="$t('label.reset.config.value')"
                :disabled="record.value === record.defaultvalue"
                @onClick="$emit('reset-setting', record)" />
            </a-space>
          </slot>
        </template>
      </template>
    </mold-list-table>
    <a-pagination
      v-if="!grouped && count > 0"
      class="configuration-pagination"
      size="small"
      :current="page"
      :page-size="pagesize"
      :total="count"
      :page-size-options="pageSizeOptions"
      :show-total="showTotal"
      show-size-changer
      show-quick-jumper
      @change="(page, size) => $emit('change-page', page, size)" />
  </section>
</template>

<script>
import MoldListTable from '@/components/view/MoldListTable'
import TableOverflowText from '@/components/view/TableOverflowText'
import TooltipButton from '@/components/widgets/TooltipButton'
import { configurationText } from './configurationLocale'
import { formatConfigurationValue, configurationApplyLabel } from './configurationPresentation'

export default {
  name: 'ConfigurationHierarchy',
  components: { MoldListTable, TableOverflowText, TooltipButton },
  emits: ['change-page', 'update:expandedRowKeys', 'open-parent', 'show-setting', 'edit-setting', 'reset-setting'],
  props: {
    koreanCatalog: { type: Object, default: null },
    config: { type: Array, default: () => [] },
    expandedRowKeys: { type: Array, default: () => [] },
    grouped: { type: Boolean, default: false },
    rowActions: { type: Function, default: null },
    loading: { type: Boolean, default: false },
    count: { type: Number, default: 0 },
    page: { type: Number, default: 1 },
    pagesize: { type: Number, default: 20 }
  },
  computed: {
    canUpdate () {
      return 'updateConfiguration' in this.$store.getters.apis
    },
    canReset () {
      return 'resetConfiguration' in this.$store.getters.apis
    },
    columns () {
      return [
        { key: 'name', title: this.$t('label.global.setting.key'), width: 312 },
        { key: 'value', title: this.$t('label.global.setting.current'), width: 150 },
        { key: 'defaultvalue', title: this.$t('label.global.setting.default'), width: 140 },
        { key: 'apply', title: this.$t('label.global.setting.apply'), width: 144 },
        { key: 'actions', title: this.$t('label.actions'), width: 108, fixed: 'right' }
      ]
    },
    pageSizeOptions () {
      return [...new Set([20, 50, 100, 200, this.pagesize, Number(this.$store.getters.defaultListViewPageSize)])]
        .filter(size => size > 0).sort((a, b) => a - b).map(String)
    }
  },
  methods: {
    configurationActions (record) {
      const details = { key: 'details', label: 'label.details', icon: 'search-outlined', handler: () => this.$emit('show-setting', record) }
      if (this.rowActions) return [details, ...this.rowActions(record)]
      return [
        details,
        ...(this.canUpdate ? [{ key: 'edit', label: 'label.edit', icon: 'edit-outlined', handler: () => this.$emit('edit-setting', record) }] : []),
        ...(this.canReset ? [{ key: 'reset', label: 'label.reset.config.value', icon: 'reload-outlined', disabled: record.value === record.defaultvalue, handler: () => this.$emit('reset-setting', record) }] : [])
      ]
    },
    localizedText (record, field) {
      return configurationText(record, field, this.$i18n.locale, this.koreanCatalog)
    },
    formatConfigurationValue,
    configurationApplyLabel,
    showTotal (total, [start, end]) {
      if (this.$i18n.locale === 'ko_KR') {
        return this.$t('label.total') + ' ' + total + ' ' + this.$t('label.items') + ' ' + this.$t('label.of') + ' ' + start + '–' + end + ' ' + this.$t('label.showing')
      }
      return this.$t('label.showing') + ' ' + start + '–' + end + ' ' + this.$t('label.of') + ' ' + total + ' ' + this.$t('label.items')
    }
  }
}
</script>

<style scoped>
.configuration-table { min-width: 0; }
.configuration-table-name { display: flex; min-width: 0; align-items: center; gap: 6px; overflow: hidden; }
.configuration-table-name .configuration-record-link { min-width: 0; width: auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.configuration-table-name .configuration-parent-link { flex-shrink: 0; }
.configuration-record-link,
.configuration-parent-link {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ui-link);
  text-align: start;
  white-space: normal;
  line-height: 1.5;
  cursor: pointer;
}
.configuration-record-link:hover,
.configuration-parent-link:hover { text-decoration: underline; }
.configuration-record-link:focus-visible,
.configuration-parent-link:focus-visible { outline: 2px solid var(--ui-focus); outline-offset: 2px; }
.configuration-record-link { font-weight: 600; }
.configuration-record-key,
.configuration-record-description { color: var(--ui-text-secondary); }
.configuration-record-key { font-size: 12px; }
.configuration-record-value { overflow-wrap: anywhere; white-space: pre-wrap; }
.configuration-pagination { margin-top: 12px; text-align: right; }
</style>
