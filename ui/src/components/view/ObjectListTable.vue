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
  <a-table
    class="object-list-table"
    style="margin: 10px 0;"
    :columns="columns"
    :data-source="computedDataSource"
    :pagination="false"
    :bordered="true"
    :show-header="showHeader"
    :row-key="rowKey">
    <template #bodyCell="{ text }">
      {{ text }}
    </template>
  </a-table>
</template>

<script>
const objectListRowKey = Symbol('object-list-row-key')

export default {
  name: 'ObjectListTable',
  props: {
    dataMap: {
      type: Object,
      default: null
    },
    dataArray: {
      type: Array,
      default: null
    },
    showHeader: {
      type: Boolean,
      default: false
    }
  },
  beforeCreate () {
    this.defaultColumns = [
      {
        title: this.$t('label.key'),
        dataIndex: 'key'
      },
      {
        title: this.$t('label.value'),
        dataIndex: 'value'
      }
    ]
  },
  computed: {
    columns () {
      if (this.dataArray && this.dataArray.length > 0) {
        const cols = []
        Object.keys(this.dataArray[0]).forEach(key => {
          cols.push({
            title: this.$t('label.' + key),
            dataIndex: key
          })
        })
        return cols
      }
      return this.defaultColumns
    },
    computedDataSource () {
      if (this.dataArray) {
        return this.dataArray.map((record, index) => ({ ...record, [objectListRowKey]: index + 1 }))
      }
      return Object.entries(this.dataMap || {}).map(([key, value]) => ({
        [objectListRowKey]: key,
        key,
        value
      }))
    }
  },
  methods: {
    rowKey (record) {
      return record[objectListRowKey]
    }
  }
}
</script>

<style scoped>
.object-list-table :deep(.ant-table-container),
.object-list-table :deep(.ant-table-content),
.object-list-table :deep(table),
.object-list-table :deep(.ant-table-thead > tr > th),
.object-list-table :deep(.ant-table-tbody > tr > td) {
  border-color: var(--ui-border) !important;
}
</style>
