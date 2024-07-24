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
    <a-table
      :columns="columns"
      :dataSource="tableSource"
      :pagination="false"
      size="middle"
      :scroll="{ y: 225 }">
      <template #headerCell="{ column }">
        <template v-if="column.key === 'pciTexts'">
          <IdcardOutlined /> {{ $t('label.pciTexts') }}
        </template>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'pciNames'">{{ record.pciNames }}</template>
        <template v-if="column.key === 'pciTexts'">{{ record.pciTexts }}</template>
      </template>
    </a-table>
  </div>
</template>

<script>
import { api } from '@/api'
import { IdcardOutlined } from '@ant-design/icons-vue'

export default {
  name: 'VMPciTab',
  components: {
    IdcardOutlined
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      columns: [
        {
          key: 'pciNames',
          dataIndex: 'pciNames',
          title: this.$t('label.name'),
          width: '30%'
        },
        {
          key: 'pciTexts',
          dataIndex: 'pciTexts',
          title: this.$t('label.text'),
          width: '70%'
        }
      ],
      dataItems: [],
      loading: false
    }
  },
  computed: {
    tableSource () {
      return this.dataItems.map((item, index) => {
        return {
          key: index,
          pciNames: item.pciNames,
          pciTexts: item.pciTexts
        }
      })
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    fetchData () {
      this.loading = true
      api('listVMPci', {
        id: this.resource.id
      }).then(json => {
        this.dataSource = json.listvmpciresponse.listvmpci
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style lang="less" scoped>
  .ant-table-wrapper {
    margin: 2rem 0;
  }
    @media (max-width: 600px) {
      position: relative;
      width: 100%;
      top: 0;
      right: 0;
    }

  :deep(.ant-table-tbody) > tr > td {
    cursor: pointer;
  }
</style>
