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
  <div class="staging-storage-dialog">
    <div class="staging-storage-toolbar">
      <a-button id="secondary-staging-refresh" :loading="loading" :disabled="!!deleting" @click="fetchStores">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert v-if="failed" show-icon type="error" :message="$t('message.secondary.staging.list.failed')" />
    <a-table
      v-if="!failed || rows.length"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{ current: page, pageSize, total, showSizeChanger: false }"
      :scroll="{ x: 560 }"
      row-key="id"
      size="small"
      @change="changePage">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <span :title="record.id">{{ record.name || record.id }}</span>
        </template>
        <a-popconfirm
          v-else-if="column.key === 'actions' && record.id"
          :title="$t('message.secondary.staging.delete.confirm', [record.name ? record.name + ' (' + record.id + ')' : record.id])"
          :ok-text="$t('label.delete')"
          :cancel-text="$t('label.cancel')"
          :ok-button-props="{ danger: true }"
          @confirm="removeStore(record)">
          <a-button danger size="small" :loading="deleting === record.id" :disabled="loading || failed || !!deleting">
            {{ $t('label.delete') }}
          </a-button>
        </a-popconfirm>
      </template>
    </a-table>
    <mold-dialog-actions :busy="!!deleting">
      <a-button :disabled="!!deleting" @click="$emit('close-action')">{{ $t('label.close') }}</a-button>
    </mold-dialog-actions>
  </div>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { getAPI, postAPI } from '@/api'

export default {
  components: { MoldDialogActions },
  emits: ['close-action'],
  data () {
    return { rows: [], total: 0, page: 1, pageSize: 10, loading: false, failed: false, deleting: null }
  },
  computed: {
    canDelete () { return !!this.$store.getters.apis.deleteSecondaryStagingStore },
    columns () {
      const columns = [
        { key: 'name', title: this.$t('label.name') },
        { dataIndex: 'zonename', title: this.$t('label.zone') },
        { dataIndex: 'protocol', title: this.$t('label.protocol') },
        { dataIndex: 'scope', title: this.$t('label.scope') }
      ]
      if (this.canDelete) columns.push({ key: 'actions', title: this.$t('label.actions') })
      return columns
    }
  },
  created () { this.fetchStores() },
  methods: {
    async fetchStores () {
      if (!this.$store.getters.apis.listSecondaryStagingStores || this.loading) return
      this.loading = true
      this.failed = false
      try {
        const json = await getAPI('listSecondaryStagingStores', { page: this.page, pagesize: this.pageSize }, { preserveOnFailure: true })
        const result = json.listsecondarystagingstoreresponse
        this.rows = result.imagestore || []
        this.total = Number(result.count || 0)
      } catch {
        this.failed = true
      } finally {
        this.loading = false
      }
    },
    changePage ({ current }) {
      this.page = current
      this.fetchStores()
    },
    async removeStore (record) {
      if (!this.canDelete || this.deleting || this.loading || this.failed || !this.rows.some(row => row.id === record.id)) return
      this.deleting = record.id
      try {
        await postAPI('deleteSecondaryStagingStore', { id: record.id }, { preserveOnFailure: true })
        if (this.rows.length === 1 && this.page > 1) this.page--
        await this.fetchStores()
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.deleting = null
      }
    }
  }
}
</script>

<style scoped>
.staging-storage-toolbar { margin-bottom: 8px; }
</style>
