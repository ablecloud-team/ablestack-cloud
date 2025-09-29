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
  <a-spin :spinning="loading">
    <div>
      <a-table
        style="overflow-y: auto"
        :columns="columns"
        :dataSource="securityChecks"
        :pagination="false"
        :rowKey="record => record.checkname"
        size="large">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <status class="status" :text="record.success === true ? 'True' : 'False'" displayText />
          </template>
        </template>
      </a-table>
    </div>
  </a-spin>
</template>

<script>
import { ref, reactive } from 'vue'
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'SecurityCheckTab',
  components: {
    Status,
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      securityChecks: [],
      loading: false,
      columns: [
        {
          title: this.$t('label.security.check.name'),
          dataIndex: 'checkname'
        },
        {
          key: 'status',
          title: this.$t('label.security.check.success')
        },
        {
          title: this.$t('label.security.check.last.updated'),
          dataIndex: 'lastupdated'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'details'
        }
      ]
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
    },
    fetchData () {
      this.loading = true
      getAPI('getSecurityCheck', { managementserverid: this.resource.id }).then(json => {
        this.securityChecks = json.getsecuritycheckresponse.securitychecks.securitychecks
      }).catch(error => {
        this.$notifyError(error)
      }).finally(f => {
        this.loading = false
      })
    }
  }
}
</script>
