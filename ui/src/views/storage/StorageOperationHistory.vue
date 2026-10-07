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
  <section class="storage-table-section storage-operation-history">
    <h4>{{ $t('label.storage.operation.history') }}</h4>
    <a-space wrap><a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button></a-space>
    <a-alert v-if="saving" type="info" show-icon :message="$t('message.storage.operation.reconcile.running')" />
    <a-alert v-if="error && !reconcileTarget" type="error" show-icon :message="error" />
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.storage.operation.read.failed')" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :pagination="{ pageSize: 10 }" :scroll="{ x: 1500 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'"><a-tag :color="color(record.state)">{{ $t('label.storage.operation.state.' + record.state) }}</a-tag></template>
        <template v-else-if="column.key === 'actions'"><a-button v-if="canReconcile && ['RECOVERY_REQUIRED','BLOCKED','ROLLED_BACK'].includes(record.state)" size="small" :loading="saving === record.id" @click="reconcileTarget=record">{{ $t('label.storage.operation.reconcile') }}</a-button></template>
        <template v-else>{{ record[column.dataIndex] }}</template>
      </template>
    </a-table>
    <a-modal :visible="!!reconcileTarget" :title="$t('label.storage.operation.reconcile')" :confirm-loading="!!saving" :body-style="{ maxHeight: '65vh', overflowY: 'auto' }" @cancel="reconcileTarget=null" @ok="reconcile">
      <a-alert type="info" show-icon :message="$t('message.storage.operation.reconcile.help')" />
      <p>{{ reconcileTarget?.id }}</p><p>{{ reconcileTarget?.diagnostic }}</p>
      <a-alert v-if="error" type="error" show-icon :message="error" />
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'StorageOperationHistory',
  components: { ReloadOutlined },
  props: { instanceId: { type: String, required: true } },
  data: () => ({ rows: [], loading: false, readError: false, generation: 0, reconcileTarget: null, saving: '', error: '' }),
  computed: {
    canReconcile () { return 'reconcileStorageServiceOperation' in this.$store.getters.apis },
    columns () {
      return [
        { title: this.$t('label.storage.operation.action'), dataIndex: 'action', key: 'action', width: 270 },
        { title: this.$t('label.posix.directory.revision'), dataIndex: 'revision', key: 'revision', width: 100 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 180 },
        { title: this.$t('label.storage.operation.phase'), dataIndex: 'phase', key: 'phase', width: 180 },
        { title: this.$t('label.storage.operation.progress'), dataIndex: 'progress', key: 'progress', width: 100 },
        { title: this.$t('label.storage.operation.diagnostic'), dataIndex: 'diagnostic', key: 'diagnostic', width: 420 },
        { title: this.$t('label.actions'), key: 'actions', width: 180, fixed: 'right', align: 'right' }]
    }
  },
  watch: { instanceId () { this.generation++; this.rows = []; this.reconcileTarget = null; this.saving = ''; this.error = ''; this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++ },
  methods: {
    color (state) { return state === 'COMPLETE' || state === 'RECONCILED_SUPERSEDED' ? 'green' : (state === 'RECOVERY_REQUIRED' ? 'red' : (state === 'RUNNING' ? 'blue' : 'orange')) },
    async refresh () {
      const token = ++this.generation; const instance = this.instanceId; this.loading = true
      try {
        const result = await getAPI('listStorageServiceOperations', { instanceid: instance }, { preserveOnFailure: true, timeout: 15000 })
        if (token !== this.generation || instance !== this.instanceId) return
        this.rows = result.liststorageserviceoperationsresponse?.storageserviceoperation || []; this.readError = false
      } catch (error) { if (token === this.generation) this.readError = true } finally { if (token === this.generation) this.loading = false }
    },
    async reconcile () {
      if (!this.canReconcile || !this.reconcileTarget || this.saving) return
      const instance = this.instanceId; const operation = this.reconcileTarget.id
      this.error = ''; this.saving = operation
      try {
        let result = await postAPI('reconcileStorageServiceOperation', { operationid: operation }); const job = result.reconcilestorageserviceoperationresponse?.jobid
        if (instance !== this.instanceId) return
        this.reconcileTarget = null
        if (job) {
          let done = false
          for (let i = 0; i < 120; i++) {
            if (instance !== this.instanceId) return
            result = await getAPI('queryAsyncJobResult', { jobid: job }, { preserveOnFailure: true, timeout: 15000 }); const value = result.queryasyncjobresultresponse
            if (value.jobstatus === 1) { done = true; break }
            if (value.jobstatus === 2) throw new Error(value.jobresult?.errortext || 'Operation failed')
            await new Promise(resolve => setTimeout(resolve, 1000))
          }
          if (!done) throw new Error(this.$t('message.posix.directory.timeout'))
        }
        if (instance === this.instanceId) await this.refresh()
      } catch (error) { if (instance === this.instanceId) this.error = error.message } finally { if (this.saving === operation) this.saving = '' }
    }
  }
}
</script>
<style scoped>
.storage-operation-history { margin-top: 20px; }
.storage-operation-history .ant-space { margin-bottom: 16px; }
</style>
