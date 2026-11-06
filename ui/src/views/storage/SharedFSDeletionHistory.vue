<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <SharedFSDeletion v-if="selected" :resource="{ id: selected.sharedfsuuid, name: selected.plan?.name }" :initial-operation="selected" history-mode @close-action="back" />
  <div v-else>
    <a-space class="toolbar"><a-button @click="load"><ReloadOutlined />{{ $t('label.refresh') }}</a-button></a-space>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table size="small" :loading="loading" :columns="columns" :data-source="rows" row-key="operationid" :pagination="false" :scroll="{x:900}">
      <template #bodyCell="{column,record}">
        <template v-if="column.key === 'state'">{{ $t('label.sharedfs.delete.state.' + record.state) }}</template>
        <template v-else-if="column.key === 'detail'"><a-button size="small" @click="selected = record">{{ $t('label.details') }}</a-button></template>
      </template>
    </a-table>
    <a-pagination :current="page" :total="total" :page-size="20" @change="changePage" />
    <MoldDialogActions><a-button @click="$emit('close-action')">{{ $t('label.close') }}</a-button></MoldDialogActions>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import SharedFSDeletion from './SharedFSDeletion'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'SharedFSDeletionHistory',
  components: { SharedFSDeletion, MoldDialogActions, ReloadOutlined },
  emits: ['close-action'],
  data () { return { selected: null, rows: [], total: 0, page: 1, loading: false, error: '', generation: 0 } },
  computed: {
    columns () { return [{ title: this.$t('label.created'), dataIndex: 'created', width: 210 }, { title: this.$t('label.name'), dataIndex: ['plan', 'name'], width: 220 }, { title: this.$t('label.id'), dataIndex: 'operationid', width: 290 }, { title: this.$t('label.state'), key: 'state', width: 180 }, { title: this.$t('label.details'), key: 'detail', width: 90 }] }
  },
  created () { this.load() },
  beforeUnmount () { this.generation++ },
  methods: {
    changePage (value) { this.page = value; this.load() },
    back () { this.selected = null; this.load() },
    async load () {
      const g = ++this.generation; this.loading = true; this.error = ''
      try {
        const json = await getAPI('listSharedFileSystemDeletionOperations', { page: this.page, pagesize: 20, listall: true, projectid: this.$store?.getters?.project?.id }, { preserveOnFailure: true })
        if (g !== this.generation) return
        const body = json.listsharedfilesystemdeletionoperationsresponse
        this.rows = (body.deletionoperation || []).map(x => x.result); this.total = body.count || 0
      } catch (e) { if (g === this.generation) this.error = e.response?.data?.errortext || e.message } finally { if (g === this.generation) this.loading = false }
    }
  }
}
</script>
<style scoped>
.toolbar { margin-bottom: 16px; }
</style>
