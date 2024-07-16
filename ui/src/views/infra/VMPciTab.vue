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
        <template v-if="column.key === 'pcitext'">
          <IdcardOutlined /> {{ $t('label.pcitext') }}
        </template>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'pciname'">{{ record.pciname }}</template>
        <template v-if="column.key === 'pcitext'">{{ record.pcitext }}</template>
      </template>
    </a-table>
  </div>
</template>

<script>
import { api } from '@/api'
export default {
  name: 'VMPciTab',
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
          key: 'pciname',
          dataIndex: 'pciname',
          title: this.$t('label.name'),
          width: '30%'
        },
        {
          key: 'pcitext',
          dataIndex: 'pcitext',
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
      console.log(1111)
      return this.dataItems.map((item) => {
        return {
          key: item.id,
          pciname: item.pciname,
          pcitext: item.pcitext
        }
      })
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    fetchData (pciname, pcitext) {
      this.loading = true
      api('listvmPci', {
        id: this.resource.id
      }).then(response => {
        console.log(response)
      }).catch(error => {
        this.$notifyError(error)
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
