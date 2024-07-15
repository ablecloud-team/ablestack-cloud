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
        <template v-if="column.key === 'pciText'"><IdcardOutlined /> {{ $t('label.pcitext') }}</template>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'pciName'">{{ record.pciName }}</template>
        <template v-if="column.key === 'pciText'">{{ record.pciText }}</template>
      </template>
    </a-table>
  </div>
</template>

<script>
import { api } from '@/api'
export default {
  name: 'VMPciTab',
  props: {
    pciNames: {
      type: Array,
      default: () => []
    },
    pciTexts: {
      type: Array,
      default: () => []
    },
    resource: {
      type: Object,
      required: true
    }
  },
  computed: {
    tableSource () {
      return this.pciNames.map((pciName, index) => {
        return {
          key: index,
          pciName: pciName,
          pciText: this.pciTexts[index]
        }
      })
    }
  },
  data () {
    return {
      columns: [
        {
          key: 'pciName',
          dataIndex: 'pciName',
          title: this.$t('label.name'),
          width: '30%'
        },
        {
          key: 'pciText',
          dataIndex: 'pciText',
          title: this.$t('label.pcitext'),
          width: '70%'
        }
      ]
    }
  },
  methods: {
    fetchData () {
      this.fetchLoading = true
      api('listvmPci', { id: this.resource.id }).then(json => {
        this.host = json.listhostsresponse.host[0]
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.fetchLoading = false
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
