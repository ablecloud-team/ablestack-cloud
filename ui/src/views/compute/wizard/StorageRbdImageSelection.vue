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
    <a-input-search
    class="search-input"
      :placeholder="$t('label.search')"
      @search="handleSearch">
      </a-input-search>
    <a-table
      :animated="false"
      :loading="loading"
      :columns="columns"
      :dataSource="tableSource"
      :rowSelection="rowSelection"
      :pagination="false"
      :customRow="onClickRow"
      :filteredRbdImages="filteredRbdImages"
      :input-decorator="inputDecorator"
      :selected="checkedValue"
      :preFillContent="preFillContent"
      @handle-search-filter="($event) => eventPagination($event)"
      size="middle"
      :scroll="{ y: 225 }">
      <template #headerCell="{ column }">
        <template v-if="column.key === 'size'"><IdcardOutlined /> {{ $t('label.size') }}</template>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">{{ record.name }}</template>
          <template v-if="column.key === 'size'">
            {{ record.key === "0" ? null : (record.size / 1024 / 1024 / 1024) + ' GB' }}
        </template>
      </template>
    </a-table>
    <div style="display: block; text-align: right;">
      <a-pagination
        size="small"
        :current="options.page"
        :pageSize="options.pageSize"
        :total="rowCount"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="['10', '20', '40', '80', '100', '200']"
        @change="onChangePage"
        @showSizeChange="onChangePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
  </div>
</template>

<script>

export default {
  name: 'StorageRbdImageSelection',
  props: {
    items: {
      type: Array,
      default: () => []
    },
    inputDecorator: {
      type: String,
      default: ''
    },
    rowCount: {
      type: Number,
      default: () => 0
    },
    value: {
      type: String,
      default: ''
    },
    loading: {
      type: Boolean,
      default: false
    },
    preFillContent: {
      type: Object,
      default: () => {}
    },
    zoneId: {
      type: String,
      default: () => ''
    },
    isRbdSelected: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      columns: [
        {
          key: 'name',
          dataIndex: 'name',
          title: this.$t('label.name'),
          width: '50%'
        },
        {
          key: 'size',
          dataIndex: 'size',
          title: this.$t('label.size'),
          width: '50%'
        }
      ],
      selectedRowKeys: ['0'],
      dataItems: [],
      oldZoneId: null,
      options: {
        page: 1,
        pageSize: 10,
        keyword: null
      },
      rbdSelected: {}
    }
  },
  mounted () {
    this.fillValue()
  },
  created () {
    this.initDataItem()
    if (this.items) {
      this.dataItems = this.dataItems.concat(this.items)
    }
  },
  computed: {
    tableSource () {
      return this.dataItems.map((item) => {
        return {
          key: item.id,
          name: item.name,
          size: item.size
        }
      })
    },
    rowSelection () {
      return {
        type: 'radio',
        selectedRowKeys: this.selectedRowKeys,
        onChange: this.onSelectRow
      }
    }
  },
  watch: {
    value (newValue, oldValue) {
      if (newValue && newValue !== oldValue) {
        this.selectedRowKeys = [newValue]
        this.onSelectRow(this.selectedRowKeys)
      }
    },
    items: {
      deep: true,
      handler (newData) {
        this.initDataItem()
        this.dataItems = this.dataItems.concat(newData)
      }
    }
  },
  isRbdSelected () {
    if (this.isRbdSelected) {
      this.dataItems = this.dataItems.filter(item => item.id !== '0')
    } else {
      this.dataItems.unshift({
        id: '0',
        name: this.$t('label.noselect'),
        size: undefined
      })
    }
  },
  methods: {
    fillValue () {
      this.$emit('update-rbd-images', this.inputDecorator, this.inputValue)
    },
    updateRbdImages (name, id) {
      this.checkedValue = id
      this.$emit('update-rbd-images', name, id)
    },
    initDataItem () {
      this.dataItems = []
      if (this.options.page === 1) {
        this.dataItems.push({
          id: '0',
          name: this.$t('label.noselect'),
          size: undefined
        })
      }
    },
    onSelectRow (value) {
      const rowSelected = this.items.filter(item => item.id === value[0])
      if (rowSelected && rowSelected.length > 0) {
        this.rbdSelected = rowSelected[0]
      }
      this.selectedRowKeys = value
      this.$emit('select-rbd-images-item', value[0])
      this.$emit('on-selected-rbd-size', this.rbdSelected)
    },
    handleSearch (value) {
      this.filter = value
      this.options.page = 1
      this.options.pageSize = 10
      this.options.keyword = this.filter
      this.$emit('handle-search-filter', this.options)
    },
    onChangePage (page, pageSize) {
      this.options.page = page
      this.options.pageSize = pageSize
      this.$emit('handle-search-filter', this.options)
    },
    onChangePageSize (page, pageSize) {
      this.options.page = page
      this.options.pageSize = pageSize
      this.$emit('handle-search-filter', this.options)
    },
    onClickRow (record) {
      return {
        onClick: () => {
          const rowSelected = this.items.filter(item => item.id === record.key)
          if (rowSelected && rowSelected.length > 0) {
            this.rbdSelected = rowSelected[0]
          }
          this.selectedRowKeys = [record.key]
          this.$emit('select-rbd-images-item', record.key)
          this.$emit('on-selected-rbd-size', this.rbdSelected)
        }
      }
    }
  }
}
</script>

<style lang="less" scoped>
  .ant-table-wrapper {
    margin: 2rem 0;
  }
  .search-input {
    width: 25vw;
    z-index: 8;
    position: absolute;
    top: 11px;
    right: 10px;

    @media (max-width: 600px) {
      position: relative;
      width: 100%;
      top: 0;
      right: 0;
    }
  }

  :deep(.ant-table-tbody) > tr > td {
    cursor: pointer;
  }
</style>
